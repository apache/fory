// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package fory

import (
	"fmt"
	"reflect"
	"sort"
	"strings"
)

// FieldKind describes how a field is stored in Go memory.
type FieldKind uint8

const (
	FieldKindValue FieldKind = iota
	FieldKindPointer
	FieldKindOptional
)

// PrimitiveFieldInfo contains only the fields needed for hot primitive serialization loops.
// This minimal struct improves cache efficiency during iteration.
type PrimitiveFieldInfo struct {
	Offset      uintptr    // Field offset for unsafe access
	DispatchId  DispatchId // Type dispatch ID
	WriteOffset uint8      // Offset within fixed-fields buffer (0-255, sufficient for fixed primitives)
	Kind        FieldKind
	Meta        *FieldMeta
}

// FieldMeta contains cold/rarely-accessed field metadata.
// Accessed via pointer from FieldInfo to keep FieldInfo small for cache efficiency.
type FieldMeta struct {
	Name       string
	Type       reflect.Type
	TypeId     TypeId // Fory type ID for the serializer
	Nullable   bool
	FieldIndex int      // -1 if field doesn't exist in current struct (for compatible mode)
	FieldDef   FieldDef // original FieldDef from remote TypeDef (for compatible mode skip)

	// Optional fields (fory/optional.Optional[T]) - only valid when FieldKindOptional
	OptionalInfo optionalInfo

	// Pre-computed sizes (for fixed primitives)
	FixedSize int // 0 if not fixed-size, else 1/2/4/8

	// Pre-computed flags for serialization (computed at init time)
	WriteType      bool // whether to write type info (true for struct fields in compatible mode)
	CachedTypeInfo *TypeInfo
	HasGenerics    bool // whether element types are known from TypeDef (for container fields)

	// Tag-based configuration (from fory struct tags)
	TagID          int  // -1 = use field name, >=0 = use tag ID
	HasForyTag     bool // Whether field has explicit fory tag
	TagRefSet      bool // Whether ref was explicitly set via fory tag
	TagRef         bool // The ref value from fory tag (only valid if TagRefSet is true)
	TagNullableSet bool // Whether nullable was explicitly set via fory tag
	TagNullable    bool // The nullable value from fory tag (only valid if TagNullableSet is true)
}

// FieldInfo stores field metadata computed ENTIRELY at init time.
// Hot fields are kept inline for cache efficiency, cold fields accessed via Meta pointer.
type FieldInfo struct {
	// Hot fields - accessed frequently during serialization
	Offset      uintptr    // Field offset for unsafe access
	DispatchId  DispatchId // Type dispatch ID
	WriteOffset int        // Offset within fixed-fields buffer region (sum of preceding field sizes)
	RefMode     RefMode    // ref mode for serializer.Write/Read
	Kind        FieldKind
	Serializer  Serializer // Serializer for this field

	// Cold fields - accessed less frequently
	Meta *FieldMeta
}

// FieldGroup holds categorized and sorted fields for optimized serialization.
// Fields are stored as values (not pointers) for better cache locality.
// Each field belongs to exactly one category:
// - FixedFields: non-nullable fixed-size primitives (bool, int8-64, uint8-64, float32/64)
// - VarintFields: non-nullable varint primitives (varint32/64, var_uint32/64, tagged_int64/uint64)
// - RemainingFields: all other fields (nullable primitives, strings, collections, structs, etc.)
type FieldGroup struct {
	// Primitive field slices - minimal data for fast iteration in hot loops
	PrimitiveFixedFields  []PrimitiveFieldInfo // Minimal fixed field info for hot loop
	PrimitiveVarintFields []PrimitiveFieldInfo // Minimal varint field info for hot loop

	// Full field info for remaining fields and fallback paths
	FixedFields     []FieldInfo // Non-nullable fixed-size primitives
	VarintFields    []FieldInfo // Non-nullable varint primitives
	RemainingFields []FieldInfo // All other fields
	FixedSize       int         // Total bytes for fixed-size fields
	MaxVarintSize   int         // Maximum bytes for varint fields
}

// FieldCount returns the total number of fields across all categories.
func (g *FieldGroup) FieldCount() int {
	return len(g.FixedFields) + len(g.VarintFields) + len(g.RemainingFields)
}

// ForEachField iterates over all fields in serialization order (fixed, varint, remaining).
func (g *FieldGroup) ForEachField(fn func(*FieldInfo)) {
	for i := range g.FixedFields {
		fn(&g.FixedFields[i])
	}
	for i := range g.VarintFields {
		fn(&g.VarintFields[i])
	}
	for i := range g.RemainingFields {
		fn(&g.RemainingFields[i])
	}
}

// DebugPrint prints field group information for debugging.
func (g *FieldGroup) DebugPrint(typeName string) {
	if !DebugOutputEnabled {
		return
	}
	fmt.Printf("[Go] ========== Sorted fields for %s ==========\n", typeName)
	fmt.Printf("[Go] Go sorted fixedFields (%d):\n", len(g.FixedFields))
	for i := range g.FixedFields {
		f := &g.FixedFields[i]
		fmt.Printf("[Go]   [%d] %s -> dispatchId=%d, typeId=%d, size=%d, nullable=%v\n",
			i, f.Meta.Name, f.DispatchId, f.Meta.TypeId, f.Meta.FixedSize, f.Meta.Nullable)
	}
	fmt.Printf("[Go] Go sorted varintFields (%d):\n", len(g.VarintFields))
	for i := range g.VarintFields {
		f := &g.VarintFields[i]
		fmt.Printf("[Go]   [%d] %s -> dispatchId=%d, typeId=%d, nullable=%v\n",
			i, f.Meta.Name, f.DispatchId, f.Meta.TypeId, f.Meta.Nullable)
	}
	fmt.Printf("[Go] Go sorted remainingFields (%d):\n", len(g.RemainingFields))
	for i := range g.RemainingFields {
		f := &g.RemainingFields[i]
		fmt.Printf("[Go]   [%d] %s -> dispatchId=%d, typeId=%d, nullable=%v\n",
			i, f.Meta.Name, f.DispatchId, f.Meta.TypeId, f.Meta.Nullable)
	}
	fmt.Printf("[Go] ===========================================\n")
}

// GroupFields categorizes and sorts fields into FixedFields, VarintFields, and RemainingFields.
// It computes pre-computed sizes and WriteOffset for batch buffer reservation.
// Fields are sorted within each group to match Java's wire format order.
func GroupFields(fields []FieldInfo) FieldGroup {
	var g FieldGroup

	// Categorize fields
	for i := range fields {
		field := &fields[i]
		if isFixedSizePrimitive(field.DispatchId) {
			// Non-nullable fixed-size primitives only
			field.Meta.FixedSize = getFixedSizeByDispatchId(field.DispatchId)
			g.FixedFields = append(g.FixedFields, *field)
		} else if isVarintPrimitive(field.DispatchId) {
			// Non-nullable varint primitives only
			g.VarintFields = append(g.VarintFields, *field)
		} else {
			// All other fields including nullable primitives
			g.RemainingFields = append(g.RemainingFields, *field)
		}
	}

	// Sort fixedFields: size desc, typeId desc, name asc
	sort.SliceStable(g.FixedFields, func(i, j int) bool {
		fi, fj := &g.FixedFields[i], &g.FixedFields[j]
		if fi.Meta.FixedSize != fj.Meta.FixedSize {
			return fi.Meta.FixedSize > fj.Meta.FixedSize // size descending
		}
		if fi.Meta.TypeId != fj.Meta.TypeId {
			return fi.Meta.TypeId > fj.Meta.TypeId // typeId descending
		}
		return getFieldSortKey(fi) < getFieldSortKey(fj) // tag ID or name ascending
	})

	// Compute WriteOffset after sorting and build primitive field slice
	g.PrimitiveFixedFields = make([]PrimitiveFieldInfo, len(g.FixedFields))
	for i := range g.FixedFields {
		g.FixedFields[i].WriteOffset = g.FixedSize
		g.PrimitiveFixedFields[i] = PrimitiveFieldInfo{
			Offset:      g.FixedFields[i].Offset,
			DispatchId:  g.FixedFields[i].DispatchId,
			WriteOffset: uint8(g.FixedSize),
			Kind:        g.FixedFields[i].Kind,
			Meta:        g.FixedFields[i].Meta,
		}
		g.FixedSize += g.FixedFields[i].Meta.FixedSize
	}

	// Sort varintFields: underlying type size desc, typeId desc, name asc
	// Note: Java uses primitive type size (8 for long, 4 for int), not encoding max size
	sort.SliceStable(g.VarintFields, func(i, j int) bool {
		fi, fj := &g.VarintFields[i], &g.VarintFields[j]
		sizeI := getUnderlyingTypeSize(fi.DispatchId)
		sizeJ := getUnderlyingTypeSize(fj.DispatchId)
		if sizeI != sizeJ {
			return sizeI > sizeJ // size descending
		}
		if fi.Meta.TypeId != fj.Meta.TypeId {
			return fi.Meta.TypeId > fj.Meta.TypeId // typeId descending
		}
		return getFieldSortKey(fi) < getFieldSortKey(fj) // tag ID or name ascending
	})

	// Compute maxVarintSize and build primitive varint field slice
	g.PrimitiveVarintFields = make([]PrimitiveFieldInfo, len(g.VarintFields))
	for i := range g.VarintFields {
		g.MaxVarintSize += getVarintMaxSizeByDispatchId(g.VarintFields[i].DispatchId)
		g.PrimitiveVarintFields[i] = PrimitiveFieldInfo{
			Offset:     g.VarintFields[i].Offset,
			DispatchId: g.VarintFields[i].DispatchId,
			Kind:       g.VarintFields[i].Kind,
			Meta:       g.VarintFields[i].Meta,
			// WriteOffset not used for varint fields (variable length)
		}
	}

	// Sort remainingFields: nullable primitives first (by primitiveComparator),
	// then other internal types (typeId, name), then lists, sets, maps, other (by name)
	sort.SliceStable(g.RemainingFields, func(i, j int) bool {
		fi, fj := &g.RemainingFields[i], &g.RemainingFields[j]
		catI, catJ := getFieldCategory(fi), getFieldCategory(fj)
		if catI != catJ {
			return catI < catJ
		}
		// Within nullable primitives category, use primitiveComparator logic
		if catI == 0 {
			return comparePrimitiveFields(fi, fj)
		}
		// Within internal/build-in or collection categories, sort by typeId then sort key.
		if catI == 1 || catI == 2 || catI == 3 {
			if fi.Meta.TypeId != fj.Meta.TypeId {
				return fi.Meta.TypeId < fj.Meta.TypeId
			}
			return getFieldSortKey(fi) < getFieldSortKey(fj)
		}
		// Other categories (struct, enum, etc.): sort by sort key
		return getFieldSortKey(fi) < getFieldSortKey(fj)
	})

	return g
}

// fieldHasNonPrimitiveSerializer returns true if the field has a serializer with a non-primitive type ID.
// This is used to skip the fast path for fields like enums where DispatchId is int32 but the serializer
// writes a different format (e.g., unsigned varint for enum ordinals vs signed zigzag for int32).
func fieldHasNonPrimitiveSerializer(field *FieldInfo) bool {
	if field.Serializer == nil {
		return false
	}
	// ENUM (numeric ID), NAMED_STRUCT, NAMED_COMPATIBLE_STRUCT, NAMED_EXT
	// all require special serialization and should not use the primitive fast path
	// Note: ENUM uses unsigned VarUint32Small7 for ordinals, not signed zigzag varint
	switch TypeId(field.Meta.TypeId) {
	case ENUM, NAMED_STRUCT, NAMED_COMPATIBLE_STRUCT, NAMED_EXT:
		return true
	default:
		return false
	}
}

// isEnumField checks if a field is an enum type based on its TypeId
func isEnumField(field *FieldInfo) bool {
	if field.Serializer == nil {
		return false
	}
	internalTypeId := field.Meta.TypeId
	return internalTypeId == ENUM
}

// getFieldCategory returns the category for sorting remainingFields:
// 0: nullable primitives (sorted by primitiveComparator)
// 1: internal build-in types (sorted by typeId, then sort key)
// 2: list/set collections (sorted by typeId, then sort key)
// 3: map collections (sorted by typeId, then sort key)
// 4: struct, enum, and all other types (sorted by sort key)
func getFieldCategory(field *FieldInfo) int {
	if isNullableFixedSizePrimitive(field.DispatchId) || isNullableVarintPrimitive(field.DispatchId) {
		return 0
	}
	typeId := field.Meta.TypeId
	if typeId == UNKNOWN {
		return 4
	}
	if isUserDefinedType(typeId) {
		return 4
	}
	if typeId == LIST || typeId == SET {
		return 2
	}
	if typeId == MAP {
		return 3
	}
	// Internal build-in types: sorted by typeId, then sort key (matches Java build-in group)
	return 1
}

// comparePrimitiveFields compares two nullable primitive fields using Java's primitiveComparator logic:
// fixed before varint, then underlying type size desc, typeId desc, name asc
func comparePrimitiveFields(fi, fj *FieldInfo) bool {
	iFixed := isNullableFixedSizePrimitive(fi.DispatchId)
	jFixed := isNullableFixedSizePrimitive(fj.DispatchId)
	if iFixed != jFixed {
		return iFixed // fixed before varint
	}
	// Same category: compare by underlying type size desc, typeId desc, name asc
	// Note: Java uses primitive type size (8, 4, 2, 1), not encoding size
	sizeI := getUnderlyingTypeSize(fi.DispatchId)
	sizeJ := getUnderlyingTypeSize(fj.DispatchId)
	if sizeI != sizeJ {
		return sizeI > sizeJ // size descending
	}
	if fi.Meta.TypeId != fj.Meta.TypeId {
		return fi.Meta.TypeId > fj.Meta.TypeId // typeId descending
	}
	return getFieldSortKey(fi) < getFieldSortKey(fj) // tag ID or name ascending
}

// getNullableFixedSize returns the fixed size for nullable fixed primitives
func getNullableFixedSize(dispatchId DispatchId) int {
	switch dispatchId {
	case NullableBoolDispatchId, NullableInt8DispatchId, NullableUint8DispatchId:
		return 1
	case NullableInt16DispatchId, NullableUint16DispatchId:
		return 2
	case NullableInt32DispatchId, NullableUint32DispatchId, NullableFloat32DispatchId:
		return 4
	case NullableInt64DispatchId, NullableUint64DispatchId, NullableFloat64DispatchId:
		return 8
	default:
		return 0
	}
}

// getNullableVarintMaxSize returns the max size for nullable varint primitives
func getNullableVarintMaxSize(dispatchId DispatchId) int {
	switch dispatchId {
	case NullableVarint32DispatchId, NullableVarUint32DispatchId:
		return 5
	case NullableVarint64DispatchId, NullableVarUint64DispatchId, NullableIntDispatchId, NullableUintDispatchId:
		return 10
	case NullableTaggedInt64DispatchId, NullableTaggedUint64DispatchId:
		return 9
	default:
		return 0
	}
}

// getUnderlyingTypeSize returns the size of the underlying primitive type (8 for 64-bit, 4 for 32-bit, etc.)
// This matches Java's getSizeOfPrimitiveType() which uses the type size, not encoding size
func getUnderlyingTypeSize(dispatchId DispatchId) int {
	switch dispatchId {
	// 64-bit types
	case PrimitiveInt64DispatchId, PrimitiveUint64DispatchId, PrimitiveFloat64DispatchId,
		PrimitiveVarint64DispatchId, PrimitiveVarUint64DispatchId,
		PrimitiveTaggedInt64DispatchId, PrimitiveTaggedUint64DispatchId,
		PrimitiveIntDispatchId, PrimitiveUintDispatchId:
		return 8
	// 32-bit types
	case PrimitiveInt32DispatchId, PrimitiveUint32DispatchId, PrimitiveFloat32DispatchId,
		PrimitiveVarint32DispatchId, PrimitiveVarUint32DispatchId:
		return 4
	// 16-bit types
	case PrimitiveInt16DispatchId, PrimitiveUint16DispatchId:
		return 2
	// 8-bit types
	case PrimitiveBoolDispatchId, PrimitiveInt8DispatchId, PrimitiveUint8DispatchId:
		return 1
	// Nullable types
	case NullableInt64DispatchId, NullableUint64DispatchId, NullableFloat64DispatchId,
		NullableVarint64DispatchId, NullableVarUint64DispatchId,
		NullableTaggedInt64DispatchId, NullableTaggedUint64DispatchId,
		NullableIntDispatchId, NullableUintDispatchId:
		return 8
	case NullableInt32DispatchId, NullableUint32DispatchId, NullableFloat32DispatchId,
		NullableVarint32DispatchId, NullableVarUint32DispatchId:
		return 4
	case NullableInt16DispatchId, NullableUint16DispatchId:
		return 2
	case NullableBoolDispatchId, NullableInt8DispatchId, NullableUint8DispatchId:
		return 1
	default:
		return 0
	}
}

func isNonNullablePrimitiveKind(kind reflect.Kind) bool {
	switch kind {
	case reflect.Bool, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64,
		reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64,
		reflect.Float32, reflect.Float64, reflect.Int, reflect.Uint:
		return true
	default:
		return false
	}
}

// isInternalTypeWithoutTypeMeta checks if a type is serialized without type meta per xlang spec.
// Per the spec (struct field serialization), these types use format: | ref/null flag | value data | (NO type meta)
// - Nullable primitives (*int32, *float64, etc.): | null flag | field value |
// - Strings (string): | null flag | value data |
// - Binary ([]byte): | null flag | value data |
// - List/Slice: | ref meta | value data |
// - Set: | ref meta | value data |
// - Map: | ref meta | value data |
// Only struct/enum/ext types need type meta: | ref flag | type meta | value data |
func isInternalTypeWithoutTypeMeta(t reflect.Type) bool {
	kind := t.Kind()
	// String type - no type meta needed
	if kind == reflect.String {
		return true
	}
	// Slice (list or byte slice) - no type meta needed
	if kind == reflect.Slice {
		return true
	}
	// Map type - no type meta needed
	if kind == reflect.Map {
		return true
	}
	// Pointer to primitive - no type meta needed
	if kind == reflect.Ptr {
		elemKind := t.Elem().Kind()
		switch elemKind {
		case reflect.Bool, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64,
			reflect.Int, reflect.Float32, reflect.Float64, reflect.String:
			return true
		}
	}
	return false
}

var (
	unionMarkerType = reflect.TypeOf((*UnionMarker)(nil)).Elem()
	unionGetterType = reflect.TypeOf((*UnionGetter)(nil)).Elem()
	unionSetterType = reflect.TypeOf((*UnionSetter)(nil)).Elem()
)

// isUnionType checks if a type implements the union accessors.
func isUnionType(t reflect.Type) bool {
	if t == nil {
		return false
	}
	if info, ok := getOptionalInfo(t); ok {
		t = info.valueType
	}
	if t.Implements(unionMarkerType) || reflect.PtrTo(t).Implements(unionMarkerType) {
		return true
	}
	if t.Kind() == reflect.Ptr {
		t = t.Elem()
	}
	if t.Kind() != reflect.Struct {
		return false
	}
	if t.Implements(unionGetterType) || reflect.PtrTo(t).Implements(unionGetterType) {
		return true
	}
	return t.Implements(unionSetterType) || reflect.PtrTo(t).Implements(unionSetterType)
}

// isStructField checks if a type is a struct type (directly or via pointer) excluding unions.
func isStructField(t reflect.Type) bool {
	if t == nil {
		return false
	}
	if info, ok := getOptionalInfo(t); ok {
		t = info.valueType
	}
	if t.Kind() == reflect.Ptr {
		t = t.Elem()
	}
	if isUnionType(t) {
		return false
	}
	// Date/Timestamp are built-in types with dedicated encodings, not user structs.
	if t == dateType || t == timestampType {
		return false
	}
	if t.Kind() == reflect.Struct {
		return true
	}
	return false
}

// isSetReflectType checks if a reflect.Type is a Set type (map[T]struct{})
// fory.Set[T] is defined as map[T]struct{} where the value type is empty struct
func isSetReflectType(t reflect.Type) bool {
	if t.Kind() != reflect.Map {
		return false
	}
	// Check if value type is empty struct (struct{})
	elemType := t.Elem()
	return elemType.Kind() == reflect.Struct && elemType.NumField() == 0
}

// isStructFieldType checks if a FieldType represents a type that needs type info written
// This is used to determine if type info was written for the field in compatible mode
// In compatible mode, Java writes type info for struct and ext types, but NOT for enum types
// Enum fields only have null flag + ordinal, no type ID
func isStructFieldType(ft FieldType) bool {
	if ft == nil {
		return false
	}
	typeId := ft.TypeId()
	// Check base type IDs that need type info (struct and ext, NOT enum)
	switch TypeId(typeId) {
	case STRUCT, NAMED_STRUCT, COMPATIBLE_STRUCT, NAMED_COMPATIBLE_STRUCT,
		EXT, NAMED_EXT:
		return true
	}
	return false
}

// FieldFingerprintInfo contains the information needed to compute a field's fingerprint.
type FieldFingerprintInfo struct {
	// FieldID is the tag ID if configured (>= 0), or -1 to use field name
	FieldID int
	// FieldName is the snake_case field name (used when FieldID < 0)
	FieldName string
	// TypeID is the Fory type ID for the field
	TypeID TypeId
	// Ref is true if reference tracking is enabled for this field
	Ref bool
	// Nullable is true if null flag is written for this field
	Nullable bool
}

// ComputeStructFingerprint computes the fingerprint string for a struct type.
//
// Fingerprint Format:
//
//	Each field contributes: "<field_id_or_name>,<type_id>,<ref>,<nullable>;"
//	Fields are sorted by field_id_or_name (lexicographically as strings)
//
// Field Components:
//   - field_id_or_name: Tag ID as string if configured (e.g., "0", "1"), otherwise snake_case field name
//   - type_id: Fory TypeId as decimal string (e.g., "4" for INT32)
//   - ref: "1" if reference tracking enabled, "0" otherwise
//   - nullable: "1" if null flag is written, "0" otherwise
//
// Example fingerprints:
//   - With tag IDs: "0,4,0,0;1,4,0,1;2,9,0,1;"
//   - With field names: "age,4,0,0;name,9,0,1;"
//
// The fingerprint is used to compute a hash for struct schema versioning.
// Different nullable/ref settings will produce different fingerprints,
// ensuring schema compatibility is properly validated.
func ComputeStructFingerprint(fields []FieldFingerprintInfo) string {
	// Sort fields by their identifier (field ID or name)
	type fieldWithKey struct {
		field   FieldFingerprintInfo
		sortKey string
	}
	fieldsWithKeys := make([]fieldWithKey, 0, len(fields))
	for _, field := range fields {
		var sortKey string
		if field.FieldID >= 0 {
			sortKey = fmt.Sprintf("%d", field.FieldID)
		} else {
			sortKey = field.FieldName
		}
		fieldsWithKeys = append(fieldsWithKeys, fieldWithKey{field: field, sortKey: sortKey})
	}

	sort.Slice(fieldsWithKeys, func(i, j int) bool {
		return fieldsWithKeys[i].sortKey < fieldsWithKeys[j].sortKey
	})

	var sb strings.Builder
	for _, fw := range fieldsWithKeys {
		// Field identifier
		sb.WriteString(fw.sortKey)
		sb.WriteString(",")
		// Type ID
		sb.WriteString(fmt.Sprintf("%d", fw.field.TypeID))
		sb.WriteString(",")
		// Ref flag
		if fw.field.Ref {
			sb.WriteString("1")
		} else {
			sb.WriteString("0")
		}
		sb.WriteString(",")
		// Nullable flag
		if fw.field.Nullable {
			sb.WriteString("1")
		} else {
			sb.WriteString("0")
		}
		sb.WriteString(";")
	}
	return sb.String()
}

// Field sorting helpers

type triple struct {
	typeID     TypeId
	serializer Serializer
	name       string
	nullable   bool
	tagID      int // -1 = use field name, >=0 = use tag ID for sorting
}

// getSortKey returns the sort key for a triple.
// If tagID >= 0, returns the tag ID as string (for tag-based sorting).
// Otherwise returns the snake_case field name.
func (t triple) getSortKey() string {
	if t.tagID >= 0 {
		return fmt.Sprintf("%d", t.tagID)
	}
	return SnakeCase(t.name)
}

// getFieldSortKey returns the sort key for a FieldInfo.
// If TagID >= 0, returns the tag ID as string (for tag-based sorting).
// Otherwise returns the field name (which is already snake_case).
func getFieldSortKey(f *FieldInfo) string {
	if f.Meta.TagID >= 0 {
		return fmt.Sprintf("%d", f.Meta.TagID)
	}
	return f.Meta.Name
}

// sortFields sorts fields with nullable information to match Java's field ordering.
// Java separates primitive types (int, long) from boxed types (Integer, Long).
// In Go, this corresponds to non-pointer primitives vs pointer-to-primitive.
// When tagIDs are provided (>= 0), fields are sorted by tag ID instead of field name.
func sortFields(
	typeResolver *TypeResolver,
	fieldNames []string,
	serializers []Serializer,
	typeIds []TypeId,
	nullables []bool,
	tagIDs []int,
) ([]Serializer, []string) {
	var (
		typeTriples []triple
		others      []triple
		userDefined []triple
	)

	for i, name := range fieldNames {
		ser := serializers[i]
		tagID := TagIDUseFieldName // default: use field name
		if tagIDs != nil && i < len(tagIDs) {
			tagID = tagIDs[i]
		}
		typeID := typeIds[i]
		if typeID == UNKNOWN {
			// Unknown stays in "other" group regardless of serializer presence.
			others = append(others, triple{UNKNOWN, ser, name, nullables[i], tagID})
			continue
		}
		// Serializer may be nil for dynamic element types; field ordering must rely on typeId only.
		typeTriples = append(typeTriples, triple{typeID, ser, name, nullables[i], tagID})
	}
	// Ordering:
	// 1) primitives (nullable=false), 2) primitives (nullable=true),
	// 3) built-in non-container, 4) list/set, 5) map, 6) user-defined/unknown
	// primitives = non-nullable primitive types (int, long, etc.)
	// boxed = nullable boxed types (Integer, Long, etc. which are pointers in Go)
	var primitives, boxed, listSet, maps, otherInternalTypeFields []triple

	for _, t := range typeTriples {
		switch {
		case isPrimitiveType(t.typeID):
			// Separate non-nullable primitives from nullable (boxed) primitives
			if t.nullable {
				boxed = append(boxed, t)
			} else {
				primitives = append(primitives, t)
			}
		case isPrimitiveArrayType(t.typeID):
			// Primitive arrays: built-in non-container types (sorted by typeId then name)
			otherInternalTypeFields = append(otherInternalTypeFields, t)
		case isListType(t.typeID), isSetType(t.typeID):
			// LIST, SET: collection group
			listSet = append(listSet, t)
		case isMapType(t.typeID):
			// MAP: map group
			maps = append(maps, t)
		case isUserDefinedType(t.typeID):
			userDefined = append(userDefined, t)
		case t.typeID == UNKNOWN:
			others = append(others, t)
		default:
			// STRING, BINARY, and other internal types (category 1 in reflection)
			otherInternalTypeFields = append(otherInternalTypeFields, t)
		}
	}
	// Sort primitives (non-nullable) - same logic as boxed
	// Java sorts by: compressed (varint) types last, then by size (largest first), then by type ID (descending)
	// Fixed types: BOOL, INT8, UINT8, INT16, UINT16, INT32, UINT32, INT64, UINT64, FLOAT32, FLOAT64
	// Varint types: VARINT32, VARINT64, VAR_UINT32, VAR_UINT64, TAGGED_INT64, TAGGED_UINT64
	isVarintTypeId := func(typeID TypeId) bool {
		return typeID == VARINT32 || typeID == VARINT64 ||
			typeID == VAR_UINT32 || typeID == VAR_UINT64 ||
			typeID == TAGGED_INT64 || typeID == TAGGED_UINT64
	}
	sortPrimitiveSlice := func(s []triple) {
		sort.Slice(s, func(i, j int) bool {
			ai, aj := s[i], s[j]
			compressI := isVarintTypeId(ai.typeID)
			compressJ := isVarintTypeId(aj.typeID)
			if compressI != compressJ {
				return !compressI && compressJ
			}
			szI, szJ := getPrimitiveTypeSize(ai.typeID), getPrimitiveTypeSize(aj.typeID)
			if szI != szJ {
				return szI > szJ
			}
			// Tie-breaker: type ID descending (higher type ID first), then field name
			if ai.typeID != aj.typeID {
				return ai.typeID > aj.typeID
			}
			return ai.getSortKey() < aj.getSortKey()
		})
	}
	sortPrimitiveSlice(primitives)
	sortPrimitiveSlice(boxed)
	// Sort internal types (STRING, BINARY, LIST, SET, MAP) by typeId then name only.
	// Java does NOT sort by nullable flag for these types.
	sortByTypeIDThenName := func(s []triple) {
		sort.Slice(s, func(i, j int) bool {
			if s[i].typeID != s[j].typeID {
				return s[i].typeID < s[j].typeID
			}
			return s[i].getSortKey() < s[j].getSortKey()
		})
	}
	sortTuple := func(s []triple) {
		sort.Slice(s, func(i, j int) bool {
			return s[i].getSortKey() < s[j].getSortKey()
		})
	}
	sortByTypeIDThenName(otherInternalTypeFields)
	sortByTypeIDThenName(listSet)
	sortByTypeIDThenName(maps)
	// Merge all category 2 fields (primitive arrays, userDefined, others) and sort by name
	// This matches GroupFields' getFieldCategory which sorts all category 4 fields together
	otherGroup := make([]triple, 0, len(userDefined)+len(others))
	otherGroup = append(otherGroup, userDefined...) // structs, enums, ext
	otherGroup = append(otherGroup, others...)      // unknown types
	sortTuple(otherGroup)

	// Order: primitives, boxed, built-in non-container, list/set, map, other (by name)
	// This aligns with GroupFields' getFieldCategory sorting and spec ordering.
	all := make([]triple, 0, len(fieldNames))
	all = append(all, primitives...)
	all = append(all, boxed...)
	all = append(all, otherInternalTypeFields...) // STRING, BINARY, primitive arrays, time, unions, etc.
	all = append(all, listSet...)
	all = append(all, maps...)
	all = append(all, otherGroup...)

	outSer := make([]Serializer, len(all))
	outNam := make([]string, len(all))
	for i, t := range all {
		outSer[i] = t.serializer
		outNam[i] = t.name
	}
	return outSer, outNam
}

func typesCompatible(actual, expected reflect.Type) bool {
	if actual == nil || expected == nil {
		return false
	}
	if info, ok := getOptionalInfo(actual); ok {
		actual = info.valueType
	}
	if info, ok := getOptionalInfo(expected); ok {
		expected = info.valueType
	}
	if actual == expected {
		return true
	}
	if isIntUintWidthCompatible(actual.Kind(), expected.Kind()) {
		return true
	}
	// any can accept any value
	if actual.Kind() == reflect.Interface && actual.NumMethod() == 0 {
		return true
	}
	if actual.AssignableTo(expected) || expected.AssignableTo(actual) {
		return true
	}
	if actual.Kind() == reflect.Ptr && actual.Elem() == expected {
		return true
	}
	if expected.Kind() == reflect.Ptr && expected.Elem() == actual {
		return true
	}
	// Handle pointer vs non-pointer width compatibility in both directions.
	if actual.Kind() == reflect.Ptr && expected.Kind() != reflect.Ptr {
		if isIntUintWidthCompatible(actual.Elem().Kind(), expected.Kind()) {
			return true
		}
	}
	// Symmetric case: expected is pointer, actual is non-pointer.
	if expected.Kind() == reflect.Ptr && actual.Kind() != reflect.Ptr {
		if isIntUintWidthCompatible(actual.Kind(), expected.Elem().Kind()) {
			return true
		}
	}
	if actual.Kind() == expected.Kind() {
		switch actual.Kind() {
		case reflect.Slice, reflect.Array:
			return elementTypesCompatible(actual.Elem(), expected.Elem())
		case reflect.Map:
			return elementTypesCompatible(actual.Key(), expected.Key()) && elementTypesCompatible(actual.Elem(), expected.Elem())
		}
	}
	if (actual.Kind() == reflect.Array && expected.Kind() == reflect.Slice) ||
		(actual.Kind() == reflect.Slice && expected.Kind() == reflect.Array) {
		return true
	}
	return false
}

func isIntUintWidthCompatible(actualKind, expectedKind reflect.Kind) bool {
	return (actualKind == reflect.Int && expectedKind == reflect.Int64) ||
		(actualKind == reflect.Int64 && expectedKind == reflect.Int) ||
		(actualKind == reflect.Uint && expectedKind == reflect.Uint64) ||
		(actualKind == reflect.Uint64 && expectedKind == reflect.Uint)
}

func elementTypesCompatible(actual, expected reflect.Type) bool {
	if actual == nil || expected == nil {
		return false
	}
	if info, ok := getOptionalInfo(actual); ok {
		actual = info.valueType
	}
	if info, ok := getOptionalInfo(expected); ok {
		expected = info.valueType
	}
	if actual == expected || actual.AssignableTo(expected) || expected.AssignableTo(actual) {
		return true
	}
	if isIntUintWidthCompatible(actual.Kind(), expected.Kind()) {
		return true
	}
	if actual.Kind() == reflect.Ptr && expected.Kind() == reflect.Ptr {
		return elementTypesCompatible(actual.Elem(), expected.Elem())
	}
	if actual.Kind() == reflect.Ptr && expected.Kind() != reflect.Ptr {
		return elementTypesCompatible(actual.Elem(), expected)
	}
	if expected.Kind() == reflect.Ptr && actual.Kind() != reflect.Ptr {
		return elementTypesCompatible(actual, expected.Elem())
	}
	return false
}

// typeIdFromKind derives a TypeId from a reflect.Type's kind
// This is used when the type is not registered in typesInfo
// Note: Uses VARINT32/VARINT64/VAR_UINT32/VAR_UINT64 to match Java xlang mode and Rust
func typeIdFromKind(type_ reflect.Type) TypeId {
	if info, ok := getOptionalInfo(type_); ok {
		return typeIdFromKind(info.valueType)
	}
	if type_ == dateType {
		return DATE
	}
	if type_ == timestampType {
		return TIMESTAMP
	}
	switch type_.Kind() {
	case reflect.Bool:
		return BOOL
	case reflect.Int8:
		return INT8
	case reflect.Int16:
		return INT16
	case reflect.Int32:
		return VARINT32
	case reflect.Int64, reflect.Int:
		return VARINT64
	case reflect.Uint8:
		return UINT8
	case reflect.Uint16:
		return UINT16
	case reflect.Uint32:
		return VAR_UINT32
	case reflect.Uint64, reflect.Uint:
		return VAR_UINT64
	case reflect.Float32:
		return FLOAT32
	case reflect.Float64:
		return FLOAT64
	case reflect.String:
		return STRING
	case reflect.Slice:
		// For slices, return the appropriate primitive array type ID based on element type
		elemKind := type_.Elem().Kind()
		switch elemKind {
		case reflect.Bool:
			return BOOL_ARRAY
		case reflect.Int8:
			return INT8_ARRAY
		case reflect.Uint8:
			return UINT8_ARRAY
		case reflect.Int16:
			return INT16_ARRAY
		case reflect.Uint16:
			return UINT16_ARRAY
		case reflect.Int32:
			return INT32_ARRAY
		case reflect.Uint32:
			return UINT32_ARRAY
		case reflect.Int64, reflect.Int:
			return INT64_ARRAY
		case reflect.Uint64, reflect.Uint:
			return UINT64_ARRAY
		case reflect.Float32:
			return FLOAT32_ARRAY
		case reflect.Float64:
			return FLOAT64_ARRAY
		default:
			// Non-primitive slices use LIST
			return LIST
		}
	case reflect.Array:
		// For arrays, return the appropriate primitive array type ID based on element type
		elemKind := type_.Elem().Kind()
		switch elemKind {
		case reflect.Bool:
			return BOOL_ARRAY
		case reflect.Int8:
			return INT8_ARRAY
		case reflect.Uint8:
			return UINT8_ARRAY
		case reflect.Int16:
			return INT16_ARRAY
		case reflect.Uint16:
			return UINT16_ARRAY
		case reflect.Int32:
			return INT32_ARRAY
		case reflect.Uint32:
			return UINT32_ARRAY
		case reflect.Int64, reflect.Int:
			return INT64_ARRAY
		case reflect.Uint64, reflect.Uint:
			return UINT64_ARRAY
		case reflect.Float32:
			return FLOAT32_ARRAY
		case reflect.Float64:
			return FLOAT64_ARRAY
		default:
			// Non-primitive arrays use LIST
			return LIST
		}
	case reflect.Map:
		// fory.Set[T] is defined as map[T]struct{} - check for struct{} elem type
		if isSetReflectType(type_) {
			return SET
		}
		return MAP
	case reflect.Struct:
		return NAMED_STRUCT
	case reflect.Ptr:
		// For pointer types, get the type ID of the element type
		return typeIdFromKind(type_.Elem())
	default:
		return UNKNOWN
	}
}

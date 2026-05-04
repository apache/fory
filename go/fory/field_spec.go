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
	"strconv"
	"strings"
)

type TypeSpecKind uint8

const (
	TypeSpecScalar TypeSpecKind = iota
	TypeSpecList
	TypeSpecArray
	TypeSpecSet
	TypeSpecMap
	TypeSpecPlaceholder
)

type FieldSpec struct {
	Name        string
	GoType      reflect.Type
	TagID       int
	Ignore      bool
	HasTag      bool
	RawTag      string
	Type        *TypeSpec
	Nullable    bool
	NullableSet bool
	Ref         bool
	RefSet      bool
}

type TypeSpec struct {
	Kind         TypeSpecKind
	TypeID       TypeId
	Nullable     bool
	TrackRef     bool
	declNullable bool
	declRef      bool
	hasDeclNull  bool
	hasDeclRef   bool
	GoType       reflect.Type
	Element      *TypeSpec
	Key          *TypeSpec
	Value        *TypeSpec
	elementType  *TypeSpec
	keyType      *TypeSpec
	valueType    *TypeSpec
}

func NewSimpleTypeSpec(typeID TypeId) *TypeSpec {
	return &TypeSpec{Kind: TypeSpecScalar, TypeID: typeID}
}

func NewDynamicTypeSpec(typeID TypeId) *TypeSpec {
	return &TypeSpec{Kind: TypeSpecScalar, TypeID: typeID}
}

func NewCollectionTypeSpec(typeID TypeId, element *TypeSpec) *TypeSpec {
	spec := &TypeSpec{
		TypeID:      typeID,
		Element:     element,
		elementType: element,
	}
	if typeID == SET {
		spec.Kind = TypeSpecSet
	} else {
		spec.Kind = TypeSpecList
	}
	return spec
}

func NewMapTypeSpec(typeID TypeId, keyType, valueType *TypeSpec) *TypeSpec {
	return &TypeSpec{
		Kind:      TypeSpecMap,
		TypeID:    typeID,
		Key:       keyType,
		Value:     valueType,
		keyType:   keyType,
		valueType: valueType,
	}
}

func (t *TypeSpec) normalizeChildren() {
	if t == nil {
		return
	}
	if t.Element == nil {
		t.Element = t.elementType
	}
	if t.elementType == nil {
		t.elementType = t.Element
	}
	if t.Key == nil {
		t.Key = t.keyType
	}
	if t.keyType == nil {
		t.keyType = t.Key
	}
	if t.Value == nil {
		t.Value = t.valueType
	}
	if t.valueType == nil {
		t.valueType = t.Value
	}
}

func (t *TypeSpec) Clone() *TypeSpec {
	if t == nil {
		return nil
	}
	cloned := *t
	if t.Element != nil {
		cloned.Element = t.Element.Clone()
		cloned.elementType = cloned.Element
	}
	if t.Key != nil {
		cloned.Key = t.Key.Clone()
		cloned.keyType = cloned.Key
	}
	if t.Value != nil {
		cloned.Value = t.Value.Clone()
		cloned.valueType = cloned.Value
	}
	return &cloned
}

func (t *TypeSpec) declaredNullable() bool {
	if t != nil && t.hasDeclNull {
		return t.declNullable
	}
	return true
}

func (t *TypeSpec) declaredTrackRef() bool {
	if t != nil && t.hasDeclRef {
		return t.declRef
	}
	return false
}

func (t *TypeSpec) typeDefProjection(preserveRootFlags bool) *TypeSpec {
	return t.typeDefProjectionWithMode(true, preserveRootFlags)
}

func (t *TypeSpec) typeDefProjectionWithMode(isRoot bool, preserveRootFlags bool) *TypeSpec {
	if t == nil {
		return nil
	}
	projected := *t
	switch {
	case isRoot && !preserveRootFlags:
		projected.Nullable = false
		projected.TrackRef = false
	case !isRoot:
		projected.Nullable = t.declaredNullable()
		projected.TrackRef = t.declaredTrackRef()
	}
	if t.TypeID != LIST && t.TypeID != SET && t.TypeID != MAP {
		projected.Kind = TypeSpecScalar
		projected.Element = nil
		projected.Key = nil
		projected.Value = nil
		projected.elementType = nil
		projected.keyType = nil
		projected.valueType = nil
		return &projected
	}
	if t.Element != nil {
		projected.Element = t.Element.typeDefProjectionWithMode(false, preserveRootFlags)
		projected.elementType = projected.Element
	}
	if t.Key != nil {
		projected.Key = t.Key.typeDefProjectionWithMode(false, preserveRootFlags)
		projected.keyType = projected.Key
	}
	if t.Value != nil {
		projected.Value = t.Value.typeDefProjectionWithMode(false, preserveRootFlags)
		projected.valueType = projected.Value
	}
	return &projected
}

func (t *TypeSpec) TypeId() TypeId {
	if t == nil {
		return UNKNOWN
	}
	return t.TypeID
}

func (t *TypeSpec) UserTypeId() uint32 {
	return invalidUserTypeID
}

func (t *TypeSpec) String() string {
	if t == nil {
		return "nil"
	}
	switch t.Kind {
	case TypeSpecList:
		return fmt.Sprintf("TypeSpec{typeId=%d, nullable=%v, trackRef=%v, element=%s}", t.TypeID, t.Nullable, t.TrackRef, t.Element)
	case TypeSpecArray:
		return fmt.Sprintf("TypeSpec{typeId=%d, nullable=%v, trackRef=%v, element=%s}", t.TypeID, t.Nullable, t.TrackRef, t.Element)
	case TypeSpecSet:
		return fmt.Sprintf("TypeSpec{typeId=%d, nullable=%v, trackRef=%v, element=%s}", t.TypeID, t.Nullable, t.TrackRef, t.Element)
	case TypeSpecMap:
		return fmt.Sprintf("TypeSpec{typeId=%d, nullable=%v, trackRef=%v, key=%s, value=%s}", t.TypeID, t.Nullable, t.TrackRef, t.Key, t.Value)
	default:
		return fmt.Sprintf("TypeSpec{typeId=%d, nullable=%v, trackRef=%v}", t.TypeID, t.Nullable, t.TrackRef)
	}
}

func (t *TypeSpec) Equal(other *TypeSpec) bool {
	if t == nil || other == nil {
		return t == other
	}
	t.normalizeChildren()
	other.normalizeChildren()
	if t.Kind != other.Kind || t.TypeID != other.TypeID || t.Nullable != other.Nullable || t.TrackRef != other.TrackRef {
		return false
	}
	switch t.TypeID {
	case LIST, SET:
		if !t.Element.Equal(other.Element) {
			return false
		}
	case MAP:
		if !t.Key.Equal(other.Key) {
			return false
		}
		if !t.Value.Equal(other.Value) {
			return false
		}
	}
	return true
}

func (t *TypeSpec) EqualForDiff(other *TypeSpec) bool {
	if t == nil || other == nil {
		return t == other
	}
	t.normalizeChildren()
	other.normalizeChildren()
	if !typeIdEqualForDiff(t.TypeID, other.TypeID) || t.Nullable != other.Nullable || t.TrackRef != other.TrackRef {
		return false
	}
	switch t.TypeID {
	case LIST, SET:
		if !t.Element.EqualForDiff(other.Element) {
			return false
		}
	case MAP:
		if !t.Key.EqualForDiff(other.Key) {
			return false
		}
		if !t.Value.EqualForDiff(other.Value) {
			return false
		}
	}
	return true
}

func (t *TypeSpec) write(buffer *ByteBuffer) {
	buffer.WriteUint8(uint8(t.TypeID))
	t.writeChildren(buffer)
}

func (t *TypeSpec) writeWithFlags(buffer *ByteBuffer, nullable bool, trackRef bool) {
	value := uint32(t.TypeID) << 2
	if nullable {
		value |= 0b10
	}
	if trackRef {
		value |= 0b01
	}
	buffer.WriteVarUint32Small7(value)
	t.writeChildrenWithOwnFlags(buffer)
}

func (t *TypeSpec) writeChildren(buffer *ByteBuffer) {
	t.normalizeChildren()
	switch t.TypeID {
	case LIST, SET:
		if t.Element != nil {
			t.Element.writeWithFlags(buffer, t.Element.Nullable, t.Element.TrackRef)
		}
	case MAP:
		if t.Key != nil {
			t.Key.writeWithFlags(buffer, t.Key.Nullable, t.Key.TrackRef)
		}
		if t.Value != nil {
			t.Value.writeWithFlags(buffer, t.Value.Nullable, t.Value.TrackRef)
		}
	}
}

func (t *TypeSpec) writeChildrenWithOwnFlags(buffer *ByteBuffer) {
	t.writeChildren(buffer)
}

func (t *TypeSpec) getTypeInfo(fory *Fory) (TypeInfo, error) {
	return t.getTypeInfoWithResolver(fory.typeResolver)
}

func (t *TypeSpec) getTypeInfoWithResolver(resolver *TypeResolver) (TypeInfo, error) {
	if t == nil {
		return TypeInfo{}, nil
	}
	goType, err := t.goTypeForResolver(resolver)
	if err != nil {
		return TypeInfo{}, err
	}
	serializer, err := serializerForTypeSpec(resolver, goType, t)
	if err != nil {
		return TypeInfo{}, err
	}
	return TypeInfo{Type: goType, TypeID: uint32(t.TypeID), Serializer: serializer}, nil
}

func (t *TypeSpec) goTypeForResolver(resolver *TypeResolver) (reflect.Type, error) {
	if t == nil {
		return nil, nil
	}
	if t.GoType != nil {
		return t.GoType, nil
	}
	t.normalizeChildren()
	switch t.Kind {
	case TypeSpecList:
		if t.Element == nil {
			return nil, nil
		}
		elemType, err := t.Element.goTypeForResolver(resolver)
		if err != nil {
			return nil, err
		}
		if elemType == nil {
			return reflect.TypeOf([]any{}), nil
		}
		return reflect.SliceOf(elemType), nil
	case TypeSpecSet:
		if t.Element == nil {
			return nil, nil
		}
		elemType, err := t.Element.goTypeForResolver(resolver)
		if err != nil {
			return nil, err
		}
		if elemType == nil {
			elemType = reflect.TypeOf((*any)(nil)).Elem()
		}
		return reflect.MapOf(elemType, reflect.TypeOf(struct{}{})), nil
	case TypeSpecMap:
		if t.Key == nil || t.Value == nil {
			return nil, nil
		}
		keyType, err := t.Key.goTypeForResolver(resolver)
		if err != nil {
			return nil, err
		}
		valueType, err := t.Value.goTypeForResolver(resolver)
		if err != nil {
			return nil, err
		}
		if keyType == nil || valueType == nil {
			return reflect.TypeOf(map[any]any{}), nil
		}
		return reflect.MapOf(keyType, valueType), nil
	default:
		if goType, ok := goTypeForTypeID(t.TypeID, resolver); ok {
			return goType, nil
		}
		info, err := resolver.getTypeInfoById(uint32(t.TypeID))
		if err == nil && info != nil {
			return info.Type, nil
		}
		return reflect.TypeOf((*any)(nil)).Elem(), nil
	}
}

type parsedFieldTag struct {
	hasTag      bool
	rawTag      string
	tagID       int
	idSet       bool
	nullable    bool
	nullableSet bool
	ref         bool
	refSet      bool
	ignore      bool
	ignoreSet   bool
	encoding    string
	encodingSet bool
	typeHint    *parsedTypeHint
	typeHintSet bool
}

type parsedTypeHint struct {
	kind        TypeSpecKind
	name        string
	nullable    *bool
	ref         *bool
	encoding    *string
	element     *parsedTypeHint
	key         *parsedTypeHint
	value       *parsedTypeHint
	rawChildren map[string]*parsedTypeHint
}

func parseFieldSpec(field reflect.StructField, xlang bool, trackRef bool) (FieldSpec, error) {
	parsed, err := parseFieldTag(field)
	if err != nil {
		return FieldSpec{}, err
	}
	spec := FieldSpec{
		Name:        SnakeCase(field.Name),
		GoType:      field.Type,
		TagID:       TagIDUseFieldName,
		Ignore:      parsed.ignore,
		HasTag:      parsed.hasTag,
		RawTag:      parsed.rawTag,
		Nullable:    parsed.nullable,
		NullableSet: parsed.nullableSet,
		Ref:         parsed.ref,
		RefSet:      parsed.refSet,
	}
	if parsed.idSet {
		spec.TagID = parsed.tagID
	}
	if parsed.ignore {
		return spec, nil
	}
	typeSpec, err := resolveFieldTypeSpec(field, parsed, xlang, trackRef)
	if err != nil {
		return FieldSpec{}, err
	}
	spec.Type = typeSpec
	return spec, nil
}

func parseFieldTag(field reflect.StructField) (parsedFieldTag, error) {
	parsed := parsedFieldTag{tagID: TagIDUseFieldName}
	tagValue, ok := field.Tag.Lookup("fory")
	if !ok {
		return parsed, nil
	}
	parsed.hasTag = true
	parsed.rawTag = tagValue
	if tagValue == "-" {
		parsed.ignore = true
		parsed.ignoreSet = true
		return parsed, nil
	}
	if tagValue == "" {
		return parsed, nil
	}
	parts := splitTopLevel(tagValue)
	seen := make(map[string]struct{}, len(parts))
	for _, part := range parts {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		key := part
		value := ""
		hasValue := false
		if idx := indexTopLevel(part, '='); idx >= 0 {
			key = strings.TrimSpace(part[:idx])
			value = strings.TrimSpace(part[idx+1:])
			hasValue = true
		}
		if _, ok := seen[key]; ok {
			return parsedFieldTag{}, InvalidTagErrorf("duplicate fory tag key %q on field %s", key, field.Name)
		}
		seen[key] = struct{}{}
		switch key {
		case "id":
			if !hasValue {
				return parsedFieldTag{}, InvalidTagErrorf("invalid fory tag on field %s: id requires a value", field.Name)
			}
			id, err := strconv.Atoi(value)
			if err != nil {
				return parsedFieldTag{}, InvalidTagErrorf("invalid fory tag id=%q on field %s", value, field.Name)
			}
			parsed.idSet = true
			parsed.tagID = id
		case "nullable":
			var boolVal bool
			if hasValue {
				v, ok := parseBoolStrict(value)
				if !ok {
					return parsedFieldTag{}, InvalidTagErrorf("invalid nullable value %q on field %s", value, field.Name)
				}
				boolVal = v
			} else {
				boolVal = true
			}
			parsed.nullableSet = true
			parsed.nullable = boolVal
		case "ref":
			var boolVal bool
			if hasValue {
				v, ok := parseBoolStrict(value)
				if !ok {
					return parsedFieldTag{}, InvalidTagErrorf("invalid ref value %q on field %s", value, field.Name)
				}
				boolVal = v
			} else {
				boolVal = true
			}
			parsed.refSet = true
			parsed.ref = boolVal
		case "ignore":
			var boolVal bool
			if hasValue {
				v, ok := parseBoolStrict(value)
				if !ok {
					return parsedFieldTag{}, InvalidTagErrorf("invalid ignore value %q on field %s", value, field.Name)
				}
				boolVal = v
			} else {
				boolVal = true
			}
			parsed.ignoreSet = true
			parsed.ignore = boolVal
		case "encoding":
			if !hasValue {
				return parsedFieldTag{}, InvalidTagErrorf("invalid fory tag on field %s: encoding requires a value", field.Name)
			}
			parsed.encodingSet = true
			parsed.encoding = strings.ToLower(value)
		case "type":
			if !hasValue {
				return parsedFieldTag{}, InvalidTagErrorf("invalid fory tag on field %s: type requires a value", field.Name)
			}
			hint, err := parseTypeHint(value)
			if err != nil {
				return parsedFieldTag{}, InvalidTagErrorf("invalid fory tag type=%q on field %s: %v", value, field.Name, err)
			}
			parsed.typeHintSet = true
			parsed.typeHint = hint
		case "compress", "nested_ref":
			return parsedFieldTag{}, InvalidTagErrorf("unsupported legacy fory tag key %q on field %s", key, field.Name)
		default:
			return parsedFieldTag{}, InvalidTagErrorf("unknown fory tag key %q on field %s", key, field.Name)
		}
	}
	return parsed, nil
}

func resolveFieldTypeSpec(field reflect.StructField, parsed parsedFieldTag, xlang bool, trackRef bool) (*TypeSpec, error) {
	inferred, err := inferFieldTypeSpec(field.Type, xlang, trackRef, true)
	if err != nil {
		return nil, fmt.Errorf("field %s: %w", field.Name, err)
	}
	if parsed.typeHintSet {
		if parsed.encodingSet {
			return nil, InvalidTagErrorf("field %s: encoding cannot be combined with type=", field.Name)
		}
		inferred, err = applyTypeHint(field.Type, inferred, parsed.typeHint, xlang, trackRef, true)
		if err != nil {
			return nil, fmt.Errorf("field %s: %w", field.Name, err)
		}
	}
	if parsed.encodingSet {
		encoded, err := applyScalarEncoding(field.Type, inferred.TypeID, parsed.encoding)
		if err != nil {
			return nil, fmt.Errorf("field %s: %w", field.Name, err)
		}
		inferred.TypeID = encoded
	}
	if parsed.nullableSet {
		inferred.Nullable = parsed.nullable
		inferred.declNullable = parsed.nullable
		inferred.hasDeclNull = true
	}
	if parsed.refSet {
		inferred.TrackRef = parsed.ref
		inferred.declRef = parsed.ref
		inferred.hasDeclRef = true
	}
	inferred, err = finalizeResolvedTypeSpec(field.Type, inferred)
	if err != nil {
		return nil, fmt.Errorf("field %s: %w", field.Name, err)
	}
	enforceTopLevelFlags(field.Type, inferred)
	inferred.GoType = field.Type
	inferred.normalizeChildren()
	return inferred, nil
}

func fieldDeclaredNullable(spec FieldSpec) bool {
	if spec.Type == nil {
		return false
	}
	return spec.Type.Nullable
}

func fieldDeclaredTrackRef(spec FieldSpec, xlang bool, defaultTrackRef bool) bool {
	if spec.Type == nil {
		return false
	}
	explicit := false
	trackRef := defaultTrackRef
	if spec.Type.hasDeclRef {
		explicit = true
		trackRef = spec.Type.declRef
	}
	if spec.RefSet {
		explicit = true
		trackRef = spec.Ref
	}
	if !trackRef || !NeedWriteRef(spec.Type.TypeID) {
		return false
	}
	if xlang && isCollectionType(spec.Type.TypeID) && !explicit {
		return false
	}
	return true
}

func finalizeResolvedTypeSpec(goType reflect.Type, spec *TypeSpec) (*TypeSpec, error) {
	if spec == nil || goType == nil {
		return spec, nil
	}
	spec.GoType = goType
	spec.normalizeChildren()
	baseType := goType
	if info, ok := getOptionalInfo(baseType); ok {
		baseType = info.valueType
	}
	if baseType.Kind() == reflect.Ptr {
		baseType = baseType.Elem()
	}
	switch baseType.Kind() {
	case reflect.Slice, reflect.Array:
		if spec.Element != nil {
			child, err := finalizeResolvedTypeSpec(baseType.Elem(), spec.Element)
			if err != nil {
				return nil, err
			}
			spec.Element = child
			spec.elementType = child
		}
		if spec.TypeID == LIST || spec.TypeID == BINARY {
			return spec, nil
		}
		if typeID, ok := inferPackedArrayTypeID(baseType, spec.Element); ok {
			spec.TypeID = typeID
			return spec, nil
		}
		return nil, fmt.Errorf("slice/array field %s requires type=list(...) for non-packed element semantics", goType)
	case reflect.Map:
		if isSetReflectType(baseType) {
			if spec.Element != nil {
				child, err := finalizeResolvedTypeSpec(baseType.Key(), spec.Element)
				if err != nil {
					return nil, err
				}
				spec.Element = child
				spec.elementType = child
			}
			return spec, nil
		}
		if spec.Key != nil {
			child, err := finalizeResolvedTypeSpec(baseType.Key(), spec.Key)
			if err != nil {
				return nil, err
			}
			spec.Key = child
			spec.keyType = child
		}
		if spec.Value != nil {
			child, err := finalizeResolvedTypeSpec(baseType.Elem(), spec.Value)
			if err != nil {
				return nil, err
			}
			spec.Value = child
			spec.valueType = child
		}
	}
	return spec, nil
}

func bindResolvedTypeSpec(resolver *TypeResolver, goType reflect.Type, spec *TypeSpec) *TypeSpec {
	if resolver == nil || spec == nil || goType == nil {
		return spec
	}
	spec.GoType = goType
	baseType := goType
	if info, ok := getOptionalInfo(baseType); ok {
		baseType = info.valueType
	}
	if baseType.Kind() == reflect.Ptr {
		baseType = baseType.Elem()
	}
	spec.normalizeChildren()
	switch baseType.Kind() {
	case reflect.Slice, reflect.Array:
		if spec.Element != nil {
			spec.Element = bindResolvedTypeSpec(resolver, baseType.Elem(), spec.Element)
			spec.elementType = spec.Element
		}
	case reflect.Map:
		if isSetReflectType(baseType) {
			if spec.Element != nil {
				spec.Element = bindResolvedTypeSpec(resolver, baseType.Key(), spec.Element)
				spec.elementType = spec.Element
			}
		} else {
			if spec.Key != nil {
				spec.Key = bindResolvedTypeSpec(resolver, baseType.Key(), spec.Key)
				spec.keyType = spec.Key
			}
			if spec.Value != nil {
				spec.Value = bindResolvedTypeSpec(resolver, baseType.Elem(), spec.Value)
				spec.valueType = spec.Value
			}
		}
	}
	if typeID := resolver.getTypeIdByType(baseType); typeID != 0 {
		switch typeID {
		case NAMED_ENUM, ENUM:
			spec.TypeID = ENUM
		case NAMED_UNION, TYPED_UNION, UNION:
			spec.TypeID = UNION
		default:
			switch spec.TypeID {
			case UNKNOWN, STRUCT, COMPATIBLE_STRUCT, NAMED_STRUCT, NAMED_COMPATIBLE_STRUCT,
				EXT, NAMED_EXT, ENUM, NAMED_ENUM, UNION, NAMED_UNION, TYPED_UNION:
				spec.TypeID = typeID
			}
		}
	}
	return spec
}

func enforceTopLevelFlags(goType reflect.Type, spec *TypeSpec) {
	if spec == nil {
		return
	}
	if !NeedWriteRef(spec.TypeID) {
		spec.TrackRef = false
	}
	baseType := goType
	isOptionalCarrier := false
	if info, ok := getOptionalInfo(baseType); ok {
		isOptionalCarrier = true
		baseType = info.valueType
	}
	if baseType.Kind() == reflect.Ptr {
		isOptionalCarrier = true
		baseType = baseType.Elem()
	}
	if isNonNullablePrimitiveKind(baseType.Kind()) && spec.TypeID != ENUM && !isOptionalCarrier {
		spec.Nullable = false
	}
}

func inferFieldTypeSpec(goType reflect.Type, xlang bool, trackRef bool, isRoot bool) (*TypeSpec, error) {
	nullable := inferNullable(goType, xlang, isRoot)
	baseType := goType
	if info, ok := getOptionalInfo(baseType); ok {
		baseType = info.valueType
		nullable = true
	}
	if baseType.Kind() == reflect.Ptr {
		nullable = true
		baseType = baseType.Elem()
	}
	spec, err := inferBaseTypeSpec(baseType, xlang, trackRef, true)
	if err != nil {
		return nil, err
	}
	spec.Nullable = nullable
	spec.TrackRef = inferTrackRef(goType, spec.TypeID, trackRef)
	spec.GoType = goType
	enforceTopLevelFlags(goType, spec)
	return spec, nil
}

func inferBaseTypeSpec(goType reflect.Type, xlang bool, trackRef bool, forceGeneralList bool) (*TypeSpec, error) {
	if goType == nil {
		return nil, fmt.Errorf("type is nil")
	}
	if info, ok := getOptionalInfo(goType); ok {
		spec, err := inferBaseTypeSpec(info.valueType, xlang, trackRef, forceGeneralList)
		if err != nil {
			return nil, err
		}
		spec.Nullable = true
		spec.GoType = goType
		return spec, nil
	}
	if goType.Kind() == reflect.Ptr {
		spec, err := inferBaseTypeSpec(goType.Elem(), xlang, trackRef, forceGeneralList)
		if err != nil {
			return nil, err
		}
		spec.Nullable = true
		spec.TrackRef = inferTrackRef(goType, spec.TypeID, trackRef)
		spec.GoType = goType
		return spec, nil
	}
	if isUnionType(goType) {
		spec := NewSimpleTypeSpec(UNION)
		spec.GoType = goType
		return spec, nil
	}
	switch goType.Kind() {
	case reflect.Interface:
		spec := NewDynamicTypeSpec(UNKNOWN)
		spec.Nullable = true
		spec.TrackRef = trackRef
		spec.GoType = goType
		return spec, nil
	case reflect.Slice, reflect.Array:
		if !forceGeneralList {
			packedElemSpec, err := inferPackedCarrierElementTypeSpec(goType.Elem(), xlang, trackRef)
			if err != nil {
				return nil, err
			}
			if typeID, ok := inferPackedArrayTypeID(goType, packedElemSpec); ok {
				spec := NewCollectionTypeSpec(typeID, packedElemSpec)
				spec.GoType = goType
				return spec, nil
			}
		}
		elemSpec, err := inferNestedTypeSpec(goType.Elem(), xlang, trackRef)
		if err != nil {
			return nil, err
		}
		spec := NewCollectionTypeSpec(LIST, elemSpec)
		spec.GoType = goType
		return spec, nil
	case reflect.Map:
		if isSetReflectType(goType) {
			elemSpec, err := inferNestedTypeSpec(goType.Key(), xlang, trackRef)
			if err != nil {
				return nil, err
			}
			spec := NewCollectionTypeSpec(SET, elemSpec)
			spec.GoType = goType
			return spec, nil
		}
		keySpec, err := inferNestedTypeSpec(goType.Key(), xlang, trackRef)
		if err != nil {
			return nil, err
		}
		valueSpec, err := inferNestedTypeSpec(goType.Elem(), xlang, trackRef)
		if err != nil {
			return nil, err
		}
		spec := NewMapTypeSpec(MAP, keySpec, valueSpec)
		spec.GoType = goType
		return spec, nil
	}
	typeID, err := inferScalarTypeID(goType)
	if err != nil {
		return nil, err
	}
	spec := NewSimpleTypeSpec(typeID)
	spec.GoType = goType
	return spec, nil
}

func inferNestedTypeSpec(goType reflect.Type, xlang bool, trackRef bool) (*TypeSpec, error) {
	spec, err := inferBaseTypeSpec(goType, xlang, trackRef, true)
	if err != nil {
		return nil, err
	}
	spec.Nullable = inferNullable(goType, xlang, false)
	spec.TrackRef = inferTrackRef(goType, spec.TypeID, trackRef)
	spec.GoType = goType
	spec.normalizeChildren()
	return spec, nil
}

func inferPackedCarrierElementTypeSpec(goType reflect.Type, xlang bool, trackRef bool) (*TypeSpec, error) {
	if packedSpec, ok := inferPackedElementTypeSpec(goType); ok {
		packedSpec.GoType = goType
		return packedSpec, nil
	}
	return inferNestedTypeSpec(goType, xlang, trackRef)
}

func isByteElementType(goType reflect.Type) bool {
	return goType != nil && goType.Kind() == reflect.Uint8
}

func inferPackedElementTypeSpec(goType reflect.Type) (*TypeSpec, bool) {
	if info, ok := getOptionalInfo(goType); ok {
		_ = info
		return nil, false
	}
	if goType.Kind() == reflect.Ptr {
		return nil, false
	}
	if goType == durationType {
		return NewSimpleTypeSpec(DURATION), true
	}
	switch goType.Kind() {
	case reflect.Bool:
		return NewSimpleTypeSpec(BOOL), true
	case reflect.Int8:
		return NewSimpleTypeSpec(INT8), true
	case reflect.Int16:
		return NewSimpleTypeSpec(INT16), true
	case reflect.Int32:
		return NewSimpleTypeSpec(INT32), true
	case reflect.Int64:
		return NewSimpleTypeSpec(INT64), true
	case reflect.Int:
		if reflect.TypeOf(int(0)).Size() == 8 {
			return NewSimpleTypeSpec(INT64), true
		}
		return NewSimpleTypeSpec(INT32), true
	case reflect.Uint8:
		return NewSimpleTypeSpec(UINT8), true
	case reflect.Uint16:
		if goType == float16Type {
			return NewSimpleTypeSpec(FLOAT16), true
		}
		if goType == bfloat16Type {
			return NewSimpleTypeSpec(BFLOAT16), true
		}
		return NewSimpleTypeSpec(UINT16), true
	case reflect.Uint32:
		return NewSimpleTypeSpec(UINT32), true
	case reflect.Uint64:
		return NewSimpleTypeSpec(UINT64), true
	case reflect.Uint:
		if reflect.TypeOf(uint(0)).Size() == 8 {
			return NewSimpleTypeSpec(UINT64), true
		}
		return NewSimpleTypeSpec(UINT32), true
	case reflect.Float32:
		return NewSimpleTypeSpec(FLOAT32), true
	case reflect.Float64:
		return NewSimpleTypeSpec(FLOAT64), true
	default:
		return nil, false
	}
}

func inferNullable(goType reflect.Type, xlang bool, isRoot bool) bool {
	if info, ok := getOptionalInfo(goType); ok {
		_ = info
		return true
	}
	if goType.Kind() == reflect.Ptr {
		return true
	}
	if isRoot && xlang {
		return false
	}
	switch goType.Kind() {
	case reflect.Slice, reflect.Map, reflect.Interface:
		return true
	default:
		return false
	}
}

func inferTrackRef(goType reflect.Type, typeID TypeId, trackRef bool) bool {
	if !trackRef || !NeedWriteRef(typeID) {
		return false
	}
	if info, ok := getOptionalInfo(goType); ok {
		goType = info.valueType
	}
	return isRefType(goType, true)
}

func inferPackedArrayTypeID(goType reflect.Type, elemSpec *TypeSpec) (TypeId, bool) {
	if goType.Kind() != reflect.Slice && goType.Kind() != reflect.Array {
		return UNKNOWN, false
	}
	elemType := goType.Elem()
	if info, ok := getOptionalInfo(elemType); ok {
		_ = info
		return UNKNOWN, false
	}
	if elemType.Kind() == reflect.Ptr {
		return UNKNOWN, false
	}
	if elemSpec == nil || elemSpec.Nullable || elemSpec.TrackRef {
		return UNKNOWN, false
	}
	switch elemType.Kind() {
	case reflect.Bool:
		if elemSpec.TypeID != BOOL {
			return UNKNOWN, false
		}
		return BOOL_ARRAY, true
	case reflect.Int8:
		if elemSpec.TypeID != INT8 {
			return UNKNOWN, false
		}
		return INT8_ARRAY, true
	case reflect.Uint8:
		if elemSpec.TypeID != UINT8 {
			return UNKNOWN, false
		}
		return UINT8_ARRAY, true
	case reflect.Int16:
		if elemSpec.TypeID != INT16 {
			return UNKNOWN, false
		}
		return INT16_ARRAY, true
	case reflect.Uint16:
		if elemType == float16Type {
			if elemSpec.TypeID != FLOAT16 {
				return UNKNOWN, false
			}
			return FLOAT16_ARRAY, true
		}
		if elemType == bfloat16Type {
			if elemSpec.TypeID != BFLOAT16 {
				return UNKNOWN, false
			}
			return BFLOAT16_ARRAY, true
		}
		if elemSpec.TypeID != UINT16 {
			return UNKNOWN, false
		}
		return UINT16_ARRAY, true
	case reflect.Int32:
		if elemSpec.TypeID != INT32 {
			return UNKNOWN, false
		}
		return INT32_ARRAY, true
	case reflect.Uint32:
		if elemSpec.TypeID != UINT32 {
			return UNKNOWN, false
		}
		return UINT32_ARRAY, true
	case reflect.Int64:
		if elemSpec.TypeID != INT64 {
			return UNKNOWN, false
		}
		return INT64_ARRAY, true
	case reflect.Uint64:
		if elemSpec.TypeID != UINT64 {
			return UNKNOWN, false
		}
		return UINT64_ARRAY, true
	case reflect.Int:
		if reflect.TypeOf(int(0)).Size() == 8 {
			if elemSpec.TypeID != INT64 {
				return UNKNOWN, false
			}
			return INT64_ARRAY, true
		}
		if elemSpec.TypeID != INT32 {
			return UNKNOWN, false
		}
		return INT32_ARRAY, true
	case reflect.Uint:
		if reflect.TypeOf(uint(0)).Size() == 8 {
			if elemSpec.TypeID != UINT64 {
				return UNKNOWN, false
			}
			return UINT64_ARRAY, true
		}
		if elemSpec.TypeID != UINT32 {
			return UNKNOWN, false
		}
		return UINT32_ARRAY, true
	case reflect.Float32:
		if elemSpec.TypeID != FLOAT32 {
			return UNKNOWN, false
		}
		return FLOAT32_ARRAY, true
	case reflect.Float64:
		if elemSpec.TypeID != FLOAT64 {
			return UNKNOWN, false
		}
		return FLOAT64_ARRAY, true
	default:
		return UNKNOWN, false
	}
}

func resolveArrayElementTypeSpec(goType reflect.Type, hint *parsedTypeHint) (*TypeSpec, error) {
	if hint == nil {
		return nil, fmt.Errorf("array type hint requires element=...")
	}
	if hint.kind != TypeSpecScalar {
		return nil, fmt.Errorf("array element must be a number or bool scalar")
	}
	if hint.nullable != nil {
		return nil, fmt.Errorf("array element cannot be nullable")
	}
	if hint.ref != nil {
		return nil, fmt.Errorf("array element cannot use ref tracking")
	}
	if hint.encoding != nil {
		return nil, fmt.Errorf("array element cannot use scalar encoding modifiers")
	}
	typeID, err := arrayElementTypeIDFromHintName(goType, hint.name)
	if err != nil {
		return nil, err
	}
	spec := NewSimpleTypeSpec(typeID)
	spec.Nullable = false
	spec.TrackRef = false
	spec.GoType = goType
	return spec, nil
}

func arrayElementTypeIDFromHintName(goType reflect.Type, name string) (TypeId, error) {
	switch name {
	case "bool":
		return BOOL, nil
	case "int8":
		return INT8, nil
	case "int16":
		return INT16, nil
	case "int32":
		return INT32, nil
	case "int64":
		return INT64, nil
	case "uint8":
		return UINT8, nil
	case "uint16":
		if goType == float16Type {
			return FLOAT16, nil
		}
		if goType == bfloat16Type {
			return BFLOAT16, nil
		}
		return UINT16, nil
	case "uint32":
		return UINT32, nil
	case "uint64":
		return UINT64, nil
	case "float16":
		return FLOAT16, nil
	case "bfloat16":
		return BFLOAT16, nil
	case "float32":
		return FLOAT32, nil
	case "float64":
		return FLOAT64, nil
	default:
		return UNKNOWN, fmt.Errorf("array element %q is not a supported number or bool scalar", name)
	}
}

func inferScalarTypeID(goType reflect.Type) (TypeId, error) {
	if goType == durationType {
		return DURATION, nil
	}
	switch goType.Kind() {
	case reflect.Bool:
		return BOOL, nil
	case reflect.Int8:
		return INT8, nil
	case reflect.Int16:
		return INT16, nil
	case reflect.Int32:
		return VARINT32, nil
	case reflect.Int64:
		return VARINT64, nil
	case reflect.Int:
		if reflect.TypeOf(int(0)).Size() == 8 {
			return VARINT64, nil
		}
		return VARINT32, nil
	case reflect.Uint8:
		return UINT8, nil
	case reflect.Uint16:
		if goType == float16Type {
			return FLOAT16, nil
		}
		if goType == bfloat16Type {
			return BFLOAT16, nil
		}
		return UINT16, nil
	case reflect.Uint32:
		return VAR_UINT32, nil
	case reflect.Uint64:
		return VAR_UINT64, nil
	case reflect.Uint:
		if reflect.TypeOf(uint(0)).Size() == 8 {
			return VAR_UINT64, nil
		}
		return VAR_UINT32, nil
	case reflect.Float32:
		return FLOAT32, nil
	case reflect.Float64:
		return FLOAT64, nil
	case reflect.String:
		return STRING, nil
	case reflect.Struct:
		if goType == timestampType {
			return TIMESTAMP, nil
		}
		if goType == dateType {
			return DATE, nil
		}
		if goType == durationType {
			return DURATION, nil
		}
		if goType == decimalType {
			return DECIMAL, nil
		}
		return STRUCT, nil
	default:
		return UNKNOWN, nil
	}
}

func applyScalarEncoding(goType reflect.Type, current TypeId, encoding string) (TypeId, error) {
	baseType := goType
	if info, ok := getOptionalInfo(baseType); ok {
		baseType = info.valueType
	}
	if baseType.Kind() == reflect.Ptr {
		baseType = baseType.Elem()
	}
	switch baseType.Kind() {
	case reflect.Int32, reflect.Int:
		switch encoding {
		case "fixed":
			return INT32, nil
		case "varint":
			return VARINT32, nil
		}
	case reflect.Uint32, reflect.Uint:
		switch encoding {
		case "fixed":
			return UINT32, nil
		case "varint":
			return VAR_UINT32, nil
		}
	case reflect.Int64:
		switch encoding {
		case "fixed":
			return INT64, nil
		case "varint":
			return VARINT64, nil
		case "tagged":
			return TAGGED_INT64, nil
		}
	case reflect.Uint64:
		switch encoding {
		case "fixed":
			return UINT64, nil
		case "varint":
			return VAR_UINT64, nil
		case "tagged":
			return TAGGED_UINT64, nil
		}
	}
	return current, fmt.Errorf("encoding=%s is not valid for %s", encoding, goType)
}

func parseTypeHint(text string) (*parsedTypeHint, error) {
	p := &typeHintParser{text: strings.TrimSpace(text)}
	node, err := p.parseNode()
	if err != nil {
		return nil, err
	}
	p.skipSpace()
	if !p.eof() {
		return nil, fmt.Errorf("unexpected trailing input %q", p.text[p.pos:])
	}
	return node, nil
}

type typeHintParser struct {
	text string
	pos  int
}

func (p *typeHintParser) parseNode() (*parsedTypeHint, error) {
	p.skipSpace()
	name := p.parseIdentifier()
	if name == "" {
		return nil, fmt.Errorf("expected type identifier")
	}
	node := &parsedTypeHint{name: name}
	switch name {
	case "_":
		node.kind = TypeSpecPlaceholder
	case "list":
		node.kind = TypeSpecList
	case "array":
		node.kind = TypeSpecArray
	case "set":
		node.kind = TypeSpecSet
	case "map":
		node.kind = TypeSpecMap
	default:
		if !isPrimitiveTypeName(name) {
			return nil, fmt.Errorf("unsupported type name %q", name)
		}
		node.kind = TypeSpecScalar
	}
	p.skipSpace()
	if p.peek() != '(' {
		return node, nil
	}
	p.pos++
	p.skipSpace()
	if p.peek() == ')' {
		p.pos++
		return node, nil
	}
	seen := map[string]struct{}{}
	for {
		p.skipSpace()
		key := p.parseIdentifier()
		if key == "" {
			return nil, fmt.Errorf("expected parameter name")
		}
		if _, ok := seen[key]; ok {
			return nil, fmt.Errorf("duplicate parameter %q", key)
		}
		seen[key] = struct{}{}
		p.skipSpace()
		if p.peek() != '=' {
			return nil, fmt.Errorf("expected '=' after %q", key)
		}
		p.pos++
		p.skipSpace()
		switch key {
		case "nullable":
			value := p.parseIdentifier()
			boolVal, ok := parseBoolStrict(value)
			if !ok {
				return nil, fmt.Errorf("invalid nullable value %q", value)
			}
			node.nullable = &boolVal
		case "ref":
			value := p.parseIdentifier()
			boolVal, ok := parseBoolStrict(value)
			if !ok {
				return nil, fmt.Errorf("invalid ref value %q", value)
			}
			node.ref = &boolVal
		case "encoding":
			value := strings.ToLower(p.parseIdentifier())
			if value == "" {
				return nil, fmt.Errorf("expected encoding value")
			}
			node.encoding = &value
		case "element":
			child, err := p.parseNode()
			if err != nil {
				return nil, err
			}
			node.element = child
		case "key":
			child, err := p.parseNode()
			if err != nil {
				return nil, err
			}
			node.key = child
		case "value":
			child, err := p.parseNode()
			if err != nil {
				return nil, err
			}
			node.value = child
		default:
			return nil, fmt.Errorf("unknown parameter %q", key)
		}
		p.skipSpace()
		switch p.peek() {
		case ',':
			p.pos++
			continue
		case ')':
			p.pos++
			return node, nil
		default:
			return nil, fmt.Errorf("expected ',' or ')'")
		}
	}
}

func (p *typeHintParser) parseIdentifier() string {
	start := p.pos
	for !p.eof() {
		ch := p.text[p.pos]
		if (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '_' {
			p.pos++
			continue
		}
		break
	}
	return strings.TrimSpace(p.text[start:p.pos])
}

func (p *typeHintParser) skipSpace() {
	for !p.eof() {
		switch p.text[p.pos] {
		case ' ', '\t', '\n', '\r':
			p.pos++
		default:
			return
		}
	}
}

func (p *typeHintParser) peek() byte {
	if p.eof() {
		return 0
	}
	return p.text[p.pos]
}

func (p *typeHintParser) eof() bool {
	return p.pos >= len(p.text)
}

func applyTypeHint(goType reflect.Type, inferred *TypeSpec, hint *parsedTypeHint, xlang bool, trackRef bool, isRoot bool) (*TypeSpec, error) {
	if hint == nil {
		return inferred, nil
	}
	if inferred == nil {
		var err error
		inferred, err = inferFieldTypeSpec(goType, xlang, trackRef, isRoot)
		if err != nil {
			return nil, err
		}
	}
	if hint.kind == TypeSpecPlaceholder {
		spec := inferred.Clone()
		return applyHintModifiers(goType, spec, hint, xlang, trackRef, isRoot)
	}
	baseType := goType
	if info, ok := getOptionalInfo(baseType); ok {
		baseType = info.valueType
	}
	if baseType.Kind() == reflect.Ptr {
		baseType = baseType.Elem()
	}
	switch hint.kind {
	case TypeSpecScalar:
		if (baseType.Kind() == reflect.Slice || baseType.Kind() == reflect.Array) && hint.name == "bytes" && isByteElementType(baseType.Elem()) {
			typeID, err := typeIDFromHintName(baseType, hint.name, hint.encoding)
			if err != nil {
				return nil, err
			}
			spec := NewSimpleTypeSpec(typeID)
			spec.GoType = goType
			spec.Nullable = inferred.Nullable
			spec.TrackRef = inferred.TrackRef
			return applyHintModifiers(goType, spec, hint, xlang, trackRef, isRoot)
		}
		if baseType.Kind() == reflect.Slice || baseType.Kind() == reflect.Array || baseType.Kind() == reflect.Map {
			return nil, fmt.Errorf("type hint %q is incompatible with %s", hint.name, goType)
		}
		typeID, err := typeIDFromHintName(baseType, hint.name, hint.encoding)
		if err != nil {
			return nil, err
		}
		spec := NewSimpleTypeSpec(typeID)
		spec.GoType = goType
		spec.Nullable = inferred.Nullable
		spec.TrackRef = inferred.TrackRef
		return applyHintModifiers(goType, spec, hint, xlang, trackRef, isRoot)
	case TypeSpecList:
		if baseType.Kind() != reflect.Slice && baseType.Kind() != reflect.Array {
			return nil, fmt.Errorf("list type hint requires slice or array field, got %s", goType)
		}
		elemType := baseType.Elem()
		elemDefault, err := inferNestedTypeSpec(elemType, xlang, trackRef)
		if err != nil {
			return nil, err
		}
		elemSpec, err := applyTypeHint(elemType, elemDefault, hint.element, xlang, trackRef, false)
		if err != nil {
			return nil, err
		}
		spec := NewCollectionTypeSpec(LIST, elemSpec)
		spec.Nullable = inferred.Nullable
		spec.TrackRef = inferred.TrackRef
		spec.GoType = goType
		return applyHintModifiers(goType, spec, hint, xlang, trackRef, isRoot)
	case TypeSpecArray:
		if baseType.Kind() != reflect.Slice && baseType.Kind() != reflect.Array {
			return nil, fmt.Errorf("array type hint requires slice or array field, got %s", goType)
		}
		elemSpec, err := resolveArrayElementTypeSpec(baseType.Elem(), hint.element)
		if err != nil {
			return nil, err
		}
		typeID, ok := inferPackedArrayTypeID(baseType, elemSpec)
		if !ok {
			return nil, fmt.Errorf("array element %s is incompatible with %s", hint.element.name, goType)
		}
		spec := NewCollectionTypeSpec(typeID, elemSpec)
		spec.Kind = TypeSpecArray
		spec.Nullable = inferred.Nullable
		spec.TrackRef = inferred.TrackRef
		spec.GoType = goType
		return applyHintModifiers(goType, spec, hint, xlang, trackRef, isRoot)
	case TypeSpecSet:
		if !isSetReflectType(baseType) {
			return nil, fmt.Errorf("set type hint requires fory.Set/map[T]struct{} field, got %s", goType)
		}
		elemType := baseType.Key()
		elemDefault, err := inferNestedTypeSpec(elemType, xlang, trackRef)
		if err != nil {
			return nil, err
		}
		elemSpec, err := applyTypeHint(elemType, elemDefault, hint.element, xlang, trackRef, false)
		if err != nil {
			return nil, err
		}
		spec := NewCollectionTypeSpec(SET, elemSpec)
		spec.Nullable = inferred.Nullable
		spec.TrackRef = inferred.TrackRef
		spec.GoType = goType
		return applyHintModifiers(goType, spec, hint, xlang, trackRef, isRoot)
	case TypeSpecMap:
		if baseType.Kind() != reflect.Map || isSetReflectType(baseType) {
			return nil, fmt.Errorf("map type hint requires map field, got %s", goType)
		}
		keyDefault, err := inferNestedTypeSpec(baseType.Key(), xlang, trackRef)
		if err != nil {
			return nil, err
		}
		valueDefault, err := inferNestedTypeSpec(baseType.Elem(), xlang, trackRef)
		if err != nil {
			return nil, err
		}
		keySpec, err := applyTypeHint(baseType.Key(), keyDefault, hint.key, xlang, trackRef, false)
		if err != nil {
			return nil, err
		}
		if keySpec != nil && keySpec.Kind == TypeSpecArray {
			return nil, fmt.Errorf("array type hint is not valid for map keys")
		}
		valueSpec, err := applyTypeHint(baseType.Elem(), valueDefault, hint.value, xlang, trackRef, false)
		if err != nil {
			return nil, err
		}
		spec := NewMapTypeSpec(MAP, keySpec, valueSpec)
		spec.Nullable = inferred.Nullable
		spec.TrackRef = inferred.TrackRef
		spec.GoType = goType
		return applyHintModifiers(goType, spec, hint, xlang, trackRef, isRoot)
	default:
		return nil, fmt.Errorf("unsupported type hint kind")
	}
}

func applyHintModifiers(goType reflect.Type, spec *TypeSpec, hint *parsedTypeHint, xlang bool, trackRef bool, isRoot bool) (*TypeSpec, error) {
	if spec == nil {
		return nil, fmt.Errorf("resolved type spec is nil")
	}
	if hint.nullable != nil {
		spec.Nullable = *hint.nullable
		spec.declNullable = *hint.nullable
		spec.hasDeclNull = true
	}
	if hint.ref != nil {
		spec.TrackRef = *hint.ref
		spec.declRef = *hint.ref
		spec.hasDeclRef = true
	}
	if hint.encoding != nil {
		typeID, err := applyScalarEncoding(goType, spec.TypeID, *hint.encoding)
		if err != nil {
			return nil, err
		}
		spec.TypeID = typeID
	}
	if hint.kind == TypeSpecPlaceholder {
		baseType := goType
		if info, ok := getOptionalInfo(baseType); ok {
			baseType = info.valueType
		}
		if baseType.Kind() == reflect.Ptr {
			baseType = baseType.Elem()
		}
		switch {
		case hint.element != nil:
			if spec.Element == nil {
				return nil, fmt.Errorf("placeholder element override requires inferred list/set type")
			}
			child, err := applyTypeHint(baseType.Elem(), spec.Element, hint.element, xlang, trackRef, false)
			if err != nil {
				return nil, err
			}
			spec.Element = child
			spec.elementType = child
		case hint.key != nil || hint.value != nil:
			if spec.Key == nil || spec.Value == nil {
				return nil, fmt.Errorf("placeholder key/value override requires inferred map type")
			}
			if hint.key != nil {
				child, err := applyTypeHint(baseType.Key(), spec.Key, hint.key, xlang, trackRef, false)
				if err != nil {
					return nil, err
				}
				spec.Key = child
				spec.keyType = child
			}
			if hint.value != nil {
				child, err := applyTypeHint(baseType.Elem(), spec.Value, hint.value, xlang, trackRef, false)
				if err != nil {
					return nil, err
				}
				spec.Value = child
				spec.valueType = child
			}
		}
	}
	if isRoot {
		enforceTopLevelFlags(goType, spec)
	} else if !NeedWriteRef(spec.TypeID) {
		spec.TrackRef = false
	}
	spec.normalizeChildren()
	return spec, nil
}

func typeIDFromHintName(goType reflect.Type, name string, encoding *string) (TypeId, error) {
	if name == "_" {
		return UNKNOWN, fmt.Errorf("placeholder requires inferred type")
	}
	baseType := goType
	if info, ok := getOptionalInfo(baseType); ok {
		baseType = info.valueType
	}
	if baseType.Kind() == reflect.Ptr {
		baseType = baseType.Elem()
	}
	switch name {
	case "bool":
		return BOOL, nil
	case "int8":
		return INT8, nil
	case "int16":
		return INT16, nil
	case "int32":
		if encoding == nil || *encoding == "varint" {
			return VARINT32, nil
		}
		if *encoding == "fixed" {
			return INT32, nil
		}
	case "int64":
		if encoding == nil || *encoding == "varint" {
			return VARINT64, nil
		}
		if *encoding == "fixed" {
			return INT64, nil
		}
		if *encoding == "tagged" {
			return TAGGED_INT64, nil
		}
	case "uint8":
		return UINT8, nil
	case "uint16":
		if baseType == float16Type {
			return FLOAT16, nil
		}
		if baseType == bfloat16Type {
			return BFLOAT16, nil
		}
		return UINT16, nil
	case "uint32":
		if encoding == nil || *encoding == "varint" {
			return VAR_UINT32, nil
		}
		if *encoding == "fixed" {
			return UINT32, nil
		}
	case "uint64":
		if encoding == nil || *encoding == "varint" {
			return VAR_UINT64, nil
		}
		if *encoding == "fixed" {
			return UINT64, nil
		}
		if *encoding == "tagged" {
			return TAGGED_UINT64, nil
		}
	case "float16":
		return FLOAT16, nil
	case "bfloat16":
		return BFLOAT16, nil
	case "float32":
		return FLOAT32, nil
	case "float64":
		return FLOAT64, nil
	case "string":
		return STRING, nil
	case "bytes":
		return BINARY, nil
	case "timestamp":
		return TIMESTAMP, nil
	case "date":
		return DATE, nil
	case "duration":
		return DURATION, nil
	case "decimal":
		return DECIMAL, nil
	}
	if encoding != nil {
		return UNKNOWN, fmt.Errorf("encoding=%s is not valid for %s", *encoding, name)
	}
	return UNKNOWN, fmt.Errorf("unsupported primitive type %q", name)
}

func isPrimitiveTypeName(name string) bool {
	switch name {
	case "bool", "int8", "int16", "int32", "int64", "uint8", "uint16", "uint32", "uint64",
		"float16", "bfloat16", "float32", "float64", "string", "bytes", "timestamp", "date", "duration", "decimal":
		return true
	default:
		return false
	}
}

func splitTopLevel(input string) []string {
	var parts []string
	depth := 0
	start := 0
	for i := 0; i < len(input); i++ {
		switch input[i] {
		case '(':
			depth++
		case ')':
			if depth > 0 {
				depth--
			}
		case ',':
			if depth == 0 {
				parts = append(parts, input[start:i])
				start = i + 1
			}
		}
	}
	parts = append(parts, input[start:])
	return parts
}

func indexTopLevel(input string, target byte) int {
	depth := 0
	for i := 0; i < len(input); i++ {
		switch input[i] {
		case '(':
			depth++
		case ')':
			if depth > 0 {
				depth--
			}
		default:
			if depth == 0 && input[i] == target {
				return i
			}
		}
	}
	return -1
}

func serializerForTypeSpec(resolver *TypeResolver, goType reflect.Type, spec *TypeSpec) (Serializer, error) {
	if spec == nil {
		return nil, nil
	}
	spec.normalizeChildren()
	if goType == nil {
		var err error
		goType, err = spec.goTypeForResolver(resolver)
		if err != nil {
			return nil, err
		}
	}
	if info, ok := getOptionalInfo(goType); ok {
		inner, err := serializerForTypeSpec(resolver, info.valueType, spec.Clone())
		if err != nil {
			return nil, err
		}
		return newOptionalSerializer(goType, info, inner), nil
	}
	if goType.Kind() == reflect.Ptr {
		inner, err := serializerForTypeSpec(resolver, goType.Elem(), spec)
		if err != nil {
			return nil, err
		}
		return &ptrToValueSerializer{valueSerializer: inner}, nil
	}
	switch spec.TypeID {
	case LIST:
		if goType.Kind() != reflect.Slice && goType.Kind() != reflect.Array {
			return nil, fmt.Errorf("LIST type spec requires slice/array Go type, got %s", goType)
		}
		if spec.Element != nil && !spec.Element.TrackRef {
			if serializer, ok := newPrimitiveListSerializer(goType, spec.Element.TypeID); ok {
				return serializer, nil
			}
		}
		if goType.Kind() == reflect.Slice && goType.Elem().Kind() == reflect.String {
			return stringSliceSerializer{}, nil
		}
		if spec.Element == nil || spec.Element.TypeID == UNKNOWN || goType.Elem().Kind() == reflect.Interface {
			switch goType.Kind() {
			case reflect.Slice:
				return resolver.GetSliceSerializer(goType)
			case reflect.Array:
				return resolver.GetArraySerializer(goType)
			}
		}
		elemSerializer, err := serializerForTypeSpec(resolver, goType.Elem(), spec.Element)
		if err != nil {
			return nil, err
		}
		referencable := spec.Element != nil && spec.Element.TrackRef
		return newDeclaredSliceSerializer(goType, elemSerializer, referencable)
	case SET:
		elemSerializer, err := serializerForTypeSpec(resolver, goType.Key(), spec.Element)
		if err != nil {
			return nil, err
		}
		return setSerializer{
			elemSerializer:   elemSerializer,
			elemReferencable: spec.Element != nil && spec.Element.TrackRef,
			hasGenerics:      true,
		}, nil
	case MAP:
		if spec.Key == nil || spec.Value == nil || spec.Key.TypeID == UNKNOWN || spec.Value.TypeID == UNKNOWN ||
			goType.Key().Kind() == reflect.Interface || goType.Elem().Kind() == reflect.Interface {
			return resolver.getSerializerByType(goType, true)
		}
		keySerializer, err := serializerForTypeSpec(resolver, goType.Key(), spec.Key)
		if err != nil {
			return nil, err
		}
		valueSerializer, err := serializerForTypeSpec(resolver, goType.Elem(), spec.Value)
		if err != nil {
			return nil, err
		}
		return mapSerializer{
			type_:             goType,
			keySerializer:     keySerializer,
			valueSerializer:   valueSerializer,
			keyReferencable:   spec.Key != nil && spec.Key.TrackRef,
			valueReferencable: spec.Value != nil && spec.Value.TrackRef,
			hasGenerics:       true,
		}, nil
	}
	if serializer, ok, err := serializerForEncodedScalar(goType, spec.TypeID); ok || err != nil {
		return serializer, err
	}
	if isPrimitiveArrayType(spec.TypeID) || spec.TypeID == BINARY {
		switch goType.Kind() {
		case reflect.Slice:
			return resolver.GetSliceSerializer(goType)
		case reflect.Array:
			return resolver.GetArraySerializer(goType)
		}
	}
	return resolver.getSerializerByType(goType, true)
}

func goTypeForTypeID(typeID TypeId, resolver *TypeResolver) (reflect.Type, bool) {
	switch typeID {
	case BOOL:
		return boolType, true
	case INT8:
		return int8Type, true
	case INT16:
		return int16Type, true
	case INT32, VARINT32:
		return int32Type, true
	case INT64, VARINT64, TAGGED_INT64:
		return int64Type, true
	case UINT8:
		return byteType, true
	case UINT16:
		return uint16Type, true
	case UINT32, VAR_UINT32:
		return uint32Type, true
	case UINT64, VAR_UINT64, TAGGED_UINT64:
		return uint64Type, true
	case FLOAT16:
		return float16Type, true
	case BFLOAT16:
		return bfloat16Type, true
	case FLOAT32:
		return float32Type, true
	case FLOAT64:
		return float64Type, true
	case STRING:
		return stringType, true
	case TIMESTAMP:
		return timestampType, true
	case DATE:
		return dateType, true
	case DURATION:
		return durationType, true
	case DECIMAL:
		return decimalType, true
	case BINARY:
		return byteSliceType, true
	case BOOL_ARRAY:
		return boolSliceType, true
	case INT8_ARRAY:
		return int8SliceType, true
	case INT16_ARRAY:
		return int16SliceType, true
	case INT32_ARRAY:
		return int32SliceType, true
	case INT64_ARRAY:
		return int64SliceType, true
	case UINT8_ARRAY:
		return byteSliceType, true
	case UINT16_ARRAY:
		return uint16SliceType, true
	case UINT32_ARRAY:
		return uint32SliceType, true
	case UINT64_ARRAY:
		return uint64SliceType, true
	case FLOAT16_ARRAY:
		return reflect.SliceOf(float16Type), true
	case BFLOAT16_ARRAY:
		return reflect.SliceOf(bfloat16Type), true
	case FLOAT32_ARRAY:
		return float32SliceType, true
	case FLOAT64_ARRAY:
		return float64SliceType, true
	default:
		if resolver != nil {
			info, err := resolver.getTypeInfoById(uint32(typeID))
			if err == nil && info != nil && info.Type != nil {
				return info.Type, true
			}
		}
		return nil, false
	}
}

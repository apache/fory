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
	"errors"
	"fmt"
	"hash/fnv"
	"reflect"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
	"unsafe"

	"github.com/apache/fory/go/fory/bfloat16"
	"github.com/apache/fory/go/fory/float16"
	"github.com/apache/fory/go/fory/meta"
)

// typePointer extracts the underlying pointer from a reflect.Type for fast cache lookup
// reflect.Type is actually an interface containing a *rtype pointer
func typePointer(t reflect.Type) uintptr {
	// reflect.Type is an interface, and the concrete type is *rtype
	// We use unsafe to extract the data pointer for O(1) cache lookup
	type iface struct {
		_    uintptr // type pointer (itab)
		data uintptr // data pointer (*rtype)
	}
	return (*iface)(unsafe.Pointer(&t)).data
}

const (
	NotSupportCrossLanguage = 0
	useStringValue          = 0
	useStringId             = 1
	SMALL_STRING_THRESHOLD  = 16
	// 0xffffffff is reserved for "unset".
	maxUserTypeID       uint32 = 0xfffffffe
	invalidUserTypeID   uint32 = 0xffffffff
	internalTypeIDLimit        = 0xFF
)

var (
	interfaceType = reflect.TypeOf((*any)(nil)).Elem()
	stringType    = reflect.TypeOf((*string)(nil)).Elem()
	// Make compilation support tinygo
	stringPtrType = reflect.TypeOf((*string)(nil))
	//stringPtrType      = reflect.TypeOf((**string)(nil)).Elem()
	stringSliceType      = reflect.TypeOf((*[]string)(nil)).Elem()
	byteSliceType        = reflect.TypeOf((*[]byte)(nil)).Elem()
	boolSliceType        = reflect.TypeOf((*[]bool)(nil)).Elem()
	int8SliceType        = reflect.TypeOf((*[]int8)(nil)).Elem()
	int16SliceType       = reflect.TypeOf((*[]int16)(nil)).Elem()
	int32SliceType       = reflect.TypeOf((*[]int32)(nil)).Elem()
	int64SliceType       = reflect.TypeOf((*[]int64)(nil)).Elem()
	intSliceType         = reflect.TypeOf((*[]int)(nil)).Elem()
	uintSliceType        = reflect.TypeOf((*[]uint)(nil)).Elem()
	uint16SliceType      = reflect.TypeOf((*[]uint16)(nil)).Elem()
	uint32SliceType      = reflect.TypeOf((*[]uint32)(nil)).Elem()
	uint64SliceType      = reflect.TypeOf((*[]uint64)(nil)).Elem()
	float32SliceType     = reflect.TypeOf((*[]float32)(nil)).Elem()
	float64SliceType     = reflect.TypeOf((*[]float64)(nil)).Elem()
	float16SliceType     = reflect.TypeOf((*[]float16.Float16)(nil)).Elem()
	bfloat16SliceType    = reflect.TypeOf((*[]bfloat16.BFloat16)(nil)).Elem()
	interfaceSliceType   = reflect.TypeOf((*[]any)(nil)).Elem()
	interfaceMapType     = reflect.TypeOf((*map[any]any)(nil)).Elem()
	stringStringMapType  = reflect.TypeOf((*map[string]string)(nil)).Elem()
	stringInt64MapType   = reflect.TypeOf((*map[string]int64)(nil)).Elem()
	stringIntMapType     = reflect.TypeOf((*map[string]int)(nil)).Elem()
	stringFloat64MapType = reflect.TypeOf((*map[string]float64)(nil)).Elem()
	stringBoolMapType    = reflect.TypeOf((*map[string]bool)(nil)).Elem()
	int32Int32MapType    = reflect.TypeOf((*map[int32]int32)(nil)).Elem()
	int64Int64MapType    = reflect.TypeOf((*map[int64]int64)(nil)).Elem()
	intIntMapType        = reflect.TypeOf((*map[int]int)(nil)).Elem()
	emptyStructType      = reflect.TypeOf((*struct{})(nil)).Elem()
	boolType             = reflect.TypeOf((*bool)(nil)).Elem()
	byteType             = reflect.TypeOf((*byte)(nil)).Elem()
	uint8Type            = reflect.TypeOf((*uint8)(nil)).Elem()
	uint16Type           = reflect.TypeOf((*uint16)(nil)).Elem()
	uint32Type           = reflect.TypeOf((*uint32)(nil)).Elem()
	uint64Type           = reflect.TypeOf((*uint64)(nil)).Elem()
	uintType             = reflect.TypeOf((*uint)(nil)).Elem()
	int8Type             = reflect.TypeOf((*int8)(nil)).Elem()
	int16Type            = reflect.TypeOf((*int16)(nil)).Elem()
	int32Type            = reflect.TypeOf((*int32)(nil)).Elem()
	int64Type            = reflect.TypeOf((*int64)(nil)).Elem()
	intType              = reflect.TypeOf((*int)(nil)).Elem()
	float32Type          = reflect.TypeOf((*float32)(nil)).Elem()
	float64Type          = reflect.TypeOf((*float64)(nil)).Elem()
	float16Type          = reflect.TypeOf((*float16.Float16)(nil)).Elem()
	bfloat16Type         = reflect.TypeOf((*bfloat16.BFloat16)(nil)).Elem()
	dateType             = reflect.TypeOf((*Date)(nil)).Elem()
	timestampType        = reflect.TypeOf((*time.Time)(nil)).Elem()
	genericSetType       = reflect.TypeOf((*Set[any])(nil)).Elem()
)

// Global registry for generated serializer factories
var generatedSerializerFactories = struct {
	mu        sync.RWMutex
	factories map[reflect.Type]func() Serializer
}{
	factories: make(map[reflect.Type]func() Serializer),
}

// RegisterSerializerFactory registers a factory function for a generated serializer
func RegisterSerializerFactory(type_ any, factory func() Serializer) {
	reflectType := reflect.TypeOf(type_)
	if reflectType.Kind() == reflect.Ptr {
		reflectType = reflectType.Elem()
	}

	generatedSerializerFactories.mu.Lock()
	defer generatedSerializerFactories.mu.Unlock()
	generatedSerializerFactories.factories[reflectType] = factory
}

type TypeInfo struct {
	Type          reflect.Type
	FullNameBytes []byte
	PkgPathBytes  *MetaStringBytes
	NameBytes     *MetaStringBytes
	IsDynamic     bool
	TypeID        uint32
	// User type ID is stored as unsigned uint32; 0xffffffff means unset.
	UserTypeID   uint32
	DispatchId   DispatchId
	Serializer   Serializer
	NeedWriteDef bool
	NeedWriteRef bool // Whether this type needs reference tracking
	hashValue    uint64
	TypeDef      *TypeDef
}
type (
	namedTypeKey [2]string
)

type nsTypeKey struct {
	Namespace int64
	TypeName  int64
}

type TypeResolver struct {
	typeTagToSerializers map[string]Serializer
	typeToSerializers    map[reflect.Type]Serializer
	typeToTypeInfo       map[reflect.Type]string
	typeToTypeTag        map[reflect.Type]string
	typeInfoToType       map[string]reflect.Type
	typeIdToType         map[TypeId]reflect.Type
	dynamicStringToId    map[string]int16
	dynamicIdToString    map[int16]string
	dynamicStringId      int16

	fory *Fory
	//metaStringResolver  MetaStringResolver
	isXlang             bool
	metaStringResolver  *MetaStringResolver
	requireRegistration bool

	// String mappings
	metaStrToStr     map[string]string
	metaStrToClass   map[string]reflect.Type
	hashToMetaString map[uint64]string

	// Type tracking
	dynamicWrittenMetaStr []string
	typeIDToTypeInfo      map[uint32]*TypeInfo
	userTypeIdToTypeInfo  map[uint32]*TypeInfo
	typeIDCounter         uint32
	dynamicWriteStringID  uint32

	// Class registries
	typesInfo           map[reflect.Type]*TypeInfo
	nsTypeToTypeInfo    map[nsTypeKey]*TypeInfo
	namedTypeToTypeInfo map[namedTypeKey]*TypeInfo

	// Encoders/Decoders
	namespaceEncoder *meta.Encoder
	namespaceDecoder *meta.Decoder
	typeNameEncoder  *meta.Encoder
	typeNameDecoder  *meta.Decoder

	// meta share related
	typeToTypeDef  map[reflect.Type]*TypeDef
	defIdToTypeDef map[int64]*TypeDef

	// Fast type cache for O(1) lookup using type pointer
	typePointerCache map[uintptr]*TypeInfo

	// Cache for union type detection to avoid repeated reflect.Implements in hot paths.
	unionTypeCache map[reflect.Type]bool
}

func newTypeResolver(fory *Fory) *TypeResolver {
	r := &TypeResolver{
		typeTagToSerializers: map[string]Serializer{},
		typeToSerializers:    map[reflect.Type]Serializer{},
		typeIdToType:         map[TypeId]reflect.Type{},
		typeToTypeInfo:       map[reflect.Type]string{},
		typeInfoToType:       map[string]reflect.Type{},
		dynamicStringToId:    map[string]int16{},
		dynamicIdToString:    map[int16]string{},
		fory:                 fory,

		isXlang:             fory.config.IsXlang,
		metaStringResolver:  NewMetaStringResolver(),
		requireRegistration: false,

		metaStrToStr:     make(map[string]string),
		metaStrToClass:   make(map[string]reflect.Type),
		hashToMetaString: make(map[uint64]string),

		dynamicWrittenMetaStr: make([]string, 0),
		typeIDToTypeInfo:      make(map[uint32]*TypeInfo),
		userTypeIdToTypeInfo:  make(map[uint32]*TypeInfo),
		typeIDCounter:         300,
		dynamicWriteStringID:  0,

		typesInfo:           make(map[reflect.Type]*TypeInfo),
		nsTypeToTypeInfo:    make(map[nsTypeKey]*TypeInfo),
		namedTypeToTypeInfo: make(map[namedTypeKey]*TypeInfo),

		namespaceEncoder: meta.NewEncoder('.', '_'),
		namespaceDecoder: meta.NewDecoder('.', '_'),
		typeNameEncoder:  meta.NewEncoder('$', '_'),
		typeNameDecoder:  meta.NewDecoder('$', '_'),

		typeToTypeDef:    make(map[reflect.Type]*TypeDef),
		defIdToTypeDef:   make(map[int64]*TypeDef),
		typePointerCache: make(map[uintptr]*TypeInfo),
		unionTypeCache:   make(map[reflect.Type]bool),
	}
	// base type info for encode/decode types.
	// composite types info will be constructed dynamically.
	for _, t := range []reflect.Type{
		boolType,
		byteType,
		uint16Type,
		uint32Type,
		uint64Type,
		int8Type,
		int16Type,
		int32Type,
		intType,
		int64Type,
		float32Type,
		float64Type,
		float16Type,
		bfloat16Type,
		stringType,
		dateType,
		timestampType,
		interfaceType,
		genericSetType,
	} {
		r.typeInfoToType[t.String()] = t
		r.typeToTypeInfo[t] = t.String()
	}
	r.initialize()

	// Register generated serializers from factories with complete type information
	generatedSerializerFactories.mu.RLock()
	for type_, factory := range generatedSerializerFactories.factories {
		codegenSerializer := factory()
		pkgPath := type_.PkgPath()
		typeName := type_.Name()
		typeTag := pkgPath + "." + typeName

		// Create ptrToValueSerializer wrapper for pointer type
		ptrType := reflect.PtrTo(type_)
		ptrCodegenSer := &ptrToValueSerializer{
			valueSerializer: codegenSerializer,
		}

		// 1. Basic type mappings - use the generated serializer directly
		r.typeToSerializers[type_] = codegenSerializer // Value type -> generated serializer
		r.typeToSerializers[ptrType] = ptrCodegenSer   // Pointer type -> ptrToValueSerializer wrapper

		// 2. Cross-language critical mapping
		r.typeTagToSerializers[typeTag] = ptrCodegenSer // "pkg.Type" -> ptrToValueSerializer

		// 3. Register complete type information (critical for proper serialization)
		// Codegen serializers are for named structs
		_, err := r.registerType(type_, uint32(NAMED_STRUCT), invalidUserTypeID, pkgPath, typeName, codegenSerializer, false)
		if err != nil {
			panic(fmt.Errorf("failed to register codegen type %s: %v", typeTag, err))
		}
		// 4. Register pointer type information
		_, err = r.registerType(ptrType, uint32(NAMED_STRUCT), invalidUserTypeID, pkgPath, typeName, ptrCodegenSer, false)
		if err != nil {
			panic(fmt.Errorf("failed to register codegen pointer type %s: %v", typeTag, err))
		}

		// 5. Type info mappings
		r.typeToTypeInfo[type_] = "@" + typeTag    // Type -> "@pkg.Type"
		r.typeToTypeInfo[ptrType] = "*@" + typeTag // *Type -> "*@pkg.Type"
		r.typeInfoToType["@"+typeTag] = type_      // "@pkg.Type" -> Type
		r.typeInfoToType["*@"+typeTag] = ptrType   // "*@pkg.Type" -> *Type
	}
	generatedSerializerFactories.mu.RUnlock()

	return r
}

// TrackRef returns whether reference tracking is enabled for this Fory instance
func (r *TypeResolver) TrackRef() bool {
	return r.fory.config.TrackRef
}

// Compatible returns whether schema evolution compatibility mode is enabled
func (r *TypeResolver) Compatible() bool {
	return r.fory.config.Compatible
}

// IsXlang returns whether xlang (cross-language) mode is enabled
func (r *TypeResolver) IsXlang() bool {
	return r.isXlang
}

func (r *TypeResolver) getTypeInfoByType(type_ reflect.Type) *TypeInfo {
	if type_ == nil {
		return nil
	}
	typePtr := typePointer(type_)
	if cachedInfo, ok := r.typePointerCache[typePtr]; ok {
		return cachedInfo
	}
	info, ok := r.typesInfo[type_]
	if !ok {
		return nil
	}
	if info.Serializer == nil {
		serializer, err := r.createSerializer(type_, false)
		if err != nil {
			return nil
		}
		info.Serializer = serializer
	}
	r.typePointerCache[typePtr] = info
	return info
}

// IsUnionType returns true if the type is a union, using a local cache for performance.
// Note: Fory/TypeResolver are expected to be used single-threaded.
func (r *TypeResolver) IsUnionType(t reflect.Type) bool {
	if t == nil {
		return false
	}
	if v, ok := r.unionTypeCache[t]; ok {
		return v
	}
	base := t
	if info, ok := getOptionalInfo(t); ok {
		base = info.valueType
	}
	if v, ok := r.unionTypeCache[base]; ok {
		r.unionTypeCache[t] = v
		return v
	}

	v := isUnionType(base)
	r.unionTypeCache[t] = v
	r.unionTypeCache[base] = v
	if base.Kind() == reflect.Ptr {
		r.unionTypeCache[base.Elem()] = v
	} else {
		r.unionTypeCache[reflect.PtrTo(base)] = v
	}
	return v
}

// GetTypeInfo returns TypeInfo for the given value. This is exported for generated serializers.
func (r *TypeResolver) GetTypeInfo(value reflect.Value, create bool) (*TypeInfo, error) {
	return r.getTypeInfo(value, create)
}

func (r *TypeResolver) initialize() {
	serializers := []struct {
		reflect.Type
		TypeId
		Serializer
	}{
		{stringType, STRING, stringSerializer{}},
		{stringPtrType, STRING, ptrToStringSerializer{}},
		// Register interface types first so typeIDToTypeInfo maps to generic types
		// that can hold any element type when deserializing into any
		{interfaceSliceType, LIST, sliceDynSerializer{}},
		{interfaceMapType, MAP, mapSerializer{type_: interfaceMapType, keyReferencable: true, valueReferencable: true}},
		// stringSliceType uses dedicated stringSliceSerializer for optimized serialization
		// This ensures CollectionIsDeclElementType is set for Java compatibility
		{stringSliceType, LIST, stringSliceSerializer{}},
		{byteSliceType, BINARY, byteSliceSerializer{}},
		// Map basic type slices to slice serializers for xlang compatibility
		{boolSliceType, BOOL_ARRAY, boolSliceSerializer{}},
		{int8SliceType, INT8_ARRAY, int8SliceSerializer{}},
		{int16SliceType, INT16_ARRAY, int16SliceSerializer{}},
		{int32SliceType, INT32_ARRAY, int32SliceSerializer{}},
		{int64SliceType, INT64_ARRAY, int64SliceSerializer{}},
		{intSliceType, INT64_ARRAY, intSliceSerializer{}}, // int is typically 64-bit
		{uintSliceType, INT64_ARRAY, uintSliceSerializer{}},
		{uint16SliceType, UINT16_ARRAY, uint16SliceSerializer{}},
		{uint32SliceType, UINT32_ARRAY, uint32SliceSerializer{}},
		{uint64SliceType, UINT64_ARRAY, uint64SliceSerializer{}},
		{float32SliceType, FLOAT32_ARRAY, float32SliceSerializer{}},
		{float64SliceType, FLOAT64_ARRAY, float64SliceSerializer{}},
		{float16SliceType, FLOAT16_ARRAY, float16SliceSerializer{}},
		{bfloat16SliceType, BFLOAT16_ARRAY, bfloat16SliceSerializer{}},
		// Register common map types for fast path with optimized serializers
		{stringStringMapType, MAP, stringStringMapSerializer{}},
		{stringInt64MapType, MAP, stringInt64MapSerializer{}},
		{stringIntMapType, MAP, stringIntMapSerializer{}},
		{stringFloat64MapType, MAP, stringFloat64MapSerializer{}},
		{stringBoolMapType, MAP, stringBoolMapSerializer{}}, // map[string]bool is a regular map
		{int32Int32MapType, MAP, int32Int32MapSerializer{}},
		{int64Int64MapType, MAP, int64Int64MapSerializer{}},
		{intIntMapType, MAP, intIntMapSerializer{}},
		// Register primitive types
		{boolType, BOOL, boolSerializer{}},
		{byteType, UINT8, byteSerializer{}},
		{uint16Type, UINT16, uint16Serializer{}},
		{uint32Type, VAR_UINT32, uint32Serializer{}},
		{uint64Type, VAR_UINT64, uint64Serializer{}},
		{uintType, VAR_UINT64, uintSerializer{}},
		{int8Type, INT8, int8Serializer{}},
		{int16Type, INT16, int16Serializer{}},
		{int32Type, VARINT32, int32Serializer{}},
		{int64Type, VARINT64, int64Serializer{}},
		{intType, VARINT64, intSerializer{}}, // int maps to int64 for xlang
		{float32Type, FLOAT32, float32Serializer{}},
		{float64Type, FLOAT64, float64Serializer{}},
		{float16Type, FLOAT16, float16Serializer{}},
		{bfloat16Type, BFLOAT16, bfloat16Serializer{}},
		{dateType, DATE, dateSerializer{}},
		{timestampType, TIMESTAMP, timeSerializer{}},
		{genericSetType, SET, setSerializer{}},
	}
	for _, elem := range serializers {
		_, err := r.registerType(elem.Type, uint32(elem.TypeId), invalidUserTypeID, "", "", elem.Serializer, true)
		if err != nil {
			panic(fmt.Errorf("init type error: %v", err))
		}
	}

	// Register additional TypeIds for types that support multiple encodings.
	// This allows Go to deserialize data from Java that uses different encoding variants.
	// For example, Java may send UINT32 (fixed) but Go only registered VAR_UINT32 by default.
	// We need to map all encoding variants to the same Go type.
	additionalTypeIds := []struct {
		typeId TypeId
		goType reflect.Type
	}{
		// Byte slice can be encoded as BINARY or UINT8_ARRAY
		{UINT8_ARRAY, byteSliceType},
		// Fixed-size integer encodings (in addition to varint defaults)
		{UINT32, uint32Type},        // Fixed UINT32 (11) → uint32
		{UINT64, uint64Type},        // Fixed UINT64 (13) → uint64
		{TAGGED_UINT64, uint64Type}, // Tagged UINT64 (15) → uint64
		{INT32, int32Type},          // Fixed INT32 (3) → int32
		{INT64, int64Type},          // Fixed INT64 (5) → int64
		{TAGGED_INT64, int64Type},   // Tagged INT64 (7) → int64
	}
	for _, entry := range additionalTypeIds {
		if _, exists := r.typeIDToTypeInfo[uint32(entry.typeId)]; !exists {
			// Get the existing TypeInfo for this Go type and create a reference to it
			if existingInfo, ok := r.typesInfo[entry.goType]; ok {
				r.typeIDToTypeInfo[uint32(entry.typeId)] = existingInfo
			}
		}
	}
}

func (r *TypeResolver) registerSerializer(type_ reflect.Type, typeId TypeId, s Serializer) error {
	if prev, ok := r.typeToSerializers[type_]; ok {
		return fmt.Errorf("type %s already has a serializer %s registered", type_, prev)
	}
	r.typeToSerializers[type_] = s
	// Skip type ID registration for namespaced types, collection types, and primitive array types
	// Collection types (LIST, SET, MAP) can have multiple Go types mapping to them
	// Primitive array types can also have multiple Go types (e.g., []int and []int64 both map to INT64_ARRAY on 64-bit systems)
	// Also skip if type ID already registered (e.g., string and *string both map to STRING)
	if !IsNamespacedType(typeId) && !isCollectionType(typeId) && !isPrimitiveArrayType(typeId) {
		if typeId > NotSupportCrossLanguage {
			if _, ok := r.typeIdToType[typeId]; !ok {
				r.typeIdToType[typeId] = type_
			}
		}
	}
	return nil
}

func validateOptionalFields(type_ reflect.Type) error {
	if type_ == nil {
		return nil
	}
	if type_.Kind() == reflect.Ptr {
		type_ = type_.Elem()
	}
	if type_.Kind() != reflect.Struct {
		return nil
	}
	for i := 0; i < type_.NumField(); i++ {
		field := type_.Field(i)
		if field.PkgPath != "" {
			continue
		}
		foryTag := parseForyTag(field)
		if foryTag.Ignore {
			continue
		}
		optionalInfo, isOptional := getOptionalInfo(field.Type)
		if isOptional {
			if err := validateOptionalValueType(optionalInfo.valueType); err != nil {
				return fmt.Errorf("field %s: %w", field.Name, err)
			}
		}
	}
	return nil
}

// RegisterStruct registers a type with a numeric user type ID for cross-language serialization.
func (r *TypeResolver) RegisterStruct(type_ reflect.Type, typeID TypeId, userTypeID uint32) error {
	// Check if already registered
	if info, ok := r.userTypeIdToTypeInfo[userTypeID]; ok {
		if info.Type == type_ {
			return nil
		}
		return fmt.Errorf("type %s with id %d has been registered", info.Type, userTypeID)
	}

	switch type_.Kind() {
	case reflect.Struct:
		if err := validateOptionalFields(type_); err != nil {
			return err
		}
		// For struct types, check if serializer already registered
		if prev, ok := r.typeToSerializers[type_]; ok {
			return fmt.Errorf("type %s already has a serializer %s registered", type_, prev)
		}

		// Create struct serializer
		tag := type_.Name()
		serializer := newStructSerializer(type_, tag)
		r.typeToSerializers[type_] = serializer
		r.typeToTypeInfo[type_] = "@" + tag
		r.typeInfoToType["@"+tag] = type_

		// Create pointer serializer
		ptrType := reflect.PtrTo(type_)
		ptrSerializer, ok := r.typeToSerializers[ptrType]
		if !ok {
			ptrSerializer = &ptrToValueSerializer{
				valueSerializer: serializer,
			}
			r.typeToSerializers[ptrType] = ptrSerializer
		}
		r.typeTagToSerializers[tag] = ptrSerializer
		r.typeToTypeInfo[ptrType] = "*@" + tag
		r.typeInfoToType["*@"+tag] = ptrType

		// Register value type with fullTypeID
		_, err := r.registerType(type_, uint32(typeID), userTypeID, "", "", serializer, false)
		if err != nil {
			return fmt.Errorf("failed to register type by ID: %w", err)
		}

		// Register pointer type with same fullTypeID (Java treats value and pointer types the same)
		_, err = r.registerType(ptrType, uint32(typeID), userTypeID, "", "", ptrSerializer, false)
		if err != nil {
			return fmt.Errorf("failed to register pointer type by ID: %w", err)
		}

	default:
		return fmt.Errorf("unsupported type for ID registration: %v (use RegisterEnum for enum types)", type_.Kind())
	}

	return nil
}

// RegisterUnion registers a union type with a numeric user type ID for cross-language serialization.
func (r *TypeResolver) RegisterUnion(type_ reflect.Type, userTypeID uint32, serializer Serializer) error {
	if serializer == nil {
		return fmt.Errorf("RegisterUnion requires a non-nil serializer")
	}
	if info, ok := r.userTypeIdToTypeInfo[userTypeID]; ok {
		return fmt.Errorf("type %s with id %d has been registered", info.Type, userTypeID)
	}
	if type_.Kind() != reflect.Struct {
		return fmt.Errorf("RegisterUnion only supports struct types; got: %v", type_.Kind())
	}
	if prev, ok := r.typeToSerializers[type_]; ok {
		return fmt.Errorf("type %s already has a serializer %s registered", type_, prev)
	}

	tag := type_.Name()
	r.typeToSerializers[type_] = serializer
	r.typeToTypeInfo[type_] = "@" + tag
	r.typeInfoToType["@"+tag] = type_

	ptrType := reflect.PtrTo(type_)
	ptrSerializer := &ptrToValueSerializer{valueSerializer: serializer}
	r.typeToSerializers[ptrType] = ptrSerializer
	r.typeTagToSerializers[tag] = ptrSerializer
	r.typeToTypeInfo[ptrType] = "*@" + tag
	r.typeInfoToType["*@"+tag] = ptrType

	_, err := r.registerType(type_, uint32(TYPED_UNION), userTypeID, "", "", serializer, false)
	if err != nil {
		return fmt.Errorf("failed to register union by ID: %w", err)
	}
	_, err = r.registerType(ptrType, uint32(TYPED_UNION), userTypeID, "", "", ptrSerializer, false)
	if err != nil {
		return fmt.Errorf("failed to register pointer union by ID: %w", err)
	}
	return nil
}

// RegisterEnum registers an enum type (numeric type in Go) with a user type ID.
func (r *TypeResolver) RegisterEnum(type_ reflect.Type, userTypeID uint32) error {
	// Check if already registered
	if info, ok := r.userTypeIdToTypeInfo[userTypeID]; ok {
		return fmt.Errorf("type %s with id %d has been registered", info.Type, userTypeID)
	}

	// Verify it's a numeric type
	switch type_.Kind() {
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64,
		reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64:
		// OK
	default:
		return fmt.Errorf("RegisterEnum only supports numeric types; got: %v", type_.Kind())
	}

	// Create enum serializer
	serializer := &enumSerializer{type_: type_, typeID: uint32(ENUM)}
	tag := type_.Name()

	r.typeToSerializers[type_] = serializer
	r.typeToTypeInfo[type_] = "@" + tag
	r.typeInfoToType["@"+tag] = type_

	// Create TypeInfo with serializer
	typeInfo := &TypeInfo{
		Type:       type_,
		TypeID:     uint32(ENUM),
		UserTypeID: userTypeID,
		Serializer: serializer,
		IsDynamic:  isDynamicType(type_),
		DispatchId: GetDispatchId(type_),
		hashValue:  calcTypeHash(type_),
	}
	r.userTypeIdToTypeInfo[userTypeID] = typeInfo
	r.typesInfo[type_] = typeInfo

	return nil
}

// RegisterNamedEnum registers an enum type (numeric type in Go) with a namespace and type name
func (r *TypeResolver) RegisterNamedEnum(type_ reflect.Type, namespace, typeName string) error {
	// Check if already registered
	if prev, ok := r.typeToSerializers[type_]; ok {
		return fmt.Errorf("type %s already has a serializer %s registered", type_, prev)
	}

	// Verify it's a numeric type
	switch type_.Kind() {
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64,
		reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64:
		// OK
	default:
		return fmt.Errorf("RegisterNamedEnum only supports numeric types; got: %v", type_.Kind())
	}

	// Parse namespace from typeName if not provided
	if namespace == "" {
		if idx := strings.LastIndex(typeName, "."); idx != -1 {
			namespace = typeName[:idx]
			typeName = typeName[idx+1:]
		}
	}

	// Compute type ID for NAMED_ENUM
	typeId := uint32(NAMED_ENUM)

	// Create enum serializer
	serializer := &enumSerializer{type_: type_, typeID: typeId}

	var tag string
	if namespace == "" {
		tag = typeName
	} else {
		tag = namespace + "." + typeName
	}

	r.typeToSerializers[type_] = serializer
	r.typeToTypeInfo[type_] = "@" + tag
	r.typeInfoToType["@"+tag] = type_

	// Register the type
	_, err := r.registerType(type_, typeId, invalidUserTypeID, namespace, typeName, serializer, false)
	if err != nil {
		return fmt.Errorf("failed to register enum by name: %w", err)
	}

	return nil
}

func (r *TypeResolver) RegisterNamedStruct(
	type_ reflect.Type,
	typeId uint32,
	namespace string,
	typeName string,
) error {
	if prev, ok := r.typeToSerializers[type_]; ok {
		return fmt.Errorf("type %s already has a serializer %s registered", type_, prev)
	}
	registerById := (typeId != 0)
	if registerById && typeName != "" {
		return fmt.Errorf("typename %s and typeId %d cannot be both register", typeName, typeId)
	}
	if namespace == "" {
		if idx := strings.LastIndex(typeName, "."); idx != -1 {
			namespace = typeName[:idx]
			typeName = typeName[idx+1:]
		}
	}
	if typeName == "" && namespace != "" {
		return fmt.Errorf("typeName cannot be empty if namespace is provided")
	}
	if typeId > 0 && typeId > maxUserTypeID {
		return fmt.Errorf("typeId must be in range [0, 0xfffffffe], got %d", typeId)
	}
	var tag string
	if namespace == "" {
		tag = typeName
	} else {
		tag = namespace + "." + typeName
	}
	serializer := newStructSerializer(type_, tag)
	r.typeToSerializers[type_] = serializer
	// multiple struct with same name defined inside function will have same `type_.String()`, but they are
	// different types. so we use tag to encode type info.
	// tagged type encode as `@$tag`/`*@$tag`.
	r.typeToTypeInfo[type_] = "@" + tag
	r.typeInfoToType["@"+tag] = type_

	ptrType := reflect.PtrTo(type_)
	ptrSerializer := &ptrToValueSerializer{valueSerializer: serializer}
	r.typeToSerializers[ptrType] = ptrSerializer
	// use `ptrToValueSerializer` as default deserializer when deserializing data from other languages.
	r.typeTagToSerializers[tag] = ptrSerializer
	r.typeToTypeInfo[ptrType] = "*@" + tag
	r.typeInfoToType["*@"+tag] = ptrType
	var internalTypeID TypeId
	userTypeID := invalidUserTypeID
	if typeId == 0 {
		internalTypeID = r.structTypeID(type_, true)
	} else {
		internalTypeID = r.structTypeID(type_, false)
		userTypeID = typeId
	}
	if registerById {
		if info, ok := r.userTypeIdToTypeInfo[userTypeID]; ok {
			return fmt.Errorf("type %s with id %d has been registered", info.Type, typeId)
		}
	}
	// For named structs, directly register both their value and pointer types with same typeId
	_, err := r.registerType(type_, uint32(internalTypeID), userTypeID, namespace, typeName, nil, false)
	if err != nil {
		return fmt.Errorf("failed to register named structs: %w", err)
	}
	_, err = r.registerType(ptrType, uint32(internalTypeID), userTypeID, namespace, typeName, nil, false)
	if err != nil {
		return fmt.Errorf("failed to register named structs: %w", err)
	}
	return nil
}

// RegisterNamedUnion registers a union type with a namespace and type name.
// Union types always use NAMED_UNION and follow the same meta-share rules as other named types.
func (r *TypeResolver) RegisterNamedUnion(
	type_ reflect.Type,
	namespace string,
	typeName string,
	serializer Serializer,
) error {
	if serializer == nil {
		return fmt.Errorf("RegisterNamedUnion requires a non-nil serializer")
	}
	if prev, ok := r.typeToSerializers[type_]; ok {
		return fmt.Errorf("type %s already has a serializer %s registered", type_, prev)
	}
	if type_.Kind() != reflect.Struct {
		return fmt.Errorf("RegisterNamedUnion only supports struct types; got: %v", type_.Kind())
	}
	if namespace == "" {
		if idx := strings.LastIndex(typeName, "."); idx != -1 {
			namespace = typeName[:idx]
			typeName = typeName[idx+1:]
		}
	}
	if typeName == "" && namespace != "" {
		return fmt.Errorf("typeName cannot be empty if namespace is provided")
	}
	var tag string
	if namespace == "" {
		tag = typeName
	} else {
		tag = namespace + "." + typeName
	}
	r.typeToSerializers[type_] = serializer
	r.typeToTypeInfo[type_] = "@" + tag
	r.typeInfoToType["@"+tag] = type_

	ptrType := reflect.PtrTo(type_)
	ptrSerializer := &ptrToValueSerializer{valueSerializer: serializer}
	r.typeToSerializers[ptrType] = ptrSerializer
	r.typeTagToSerializers[tag] = ptrSerializer
	r.typeToTypeInfo[ptrType] = "*@" + tag
	r.typeInfoToType["*@"+tag] = ptrType

	typeId := uint32(NAMED_UNION)
	_, err := r.registerType(type_, typeId, invalidUserTypeID, namespace, typeName, serializer, false)
	if err != nil {
		return fmt.Errorf("failed to register union by name: %w", err)
	}
	_, err = r.registerType(ptrType, typeId, invalidUserTypeID, namespace, typeName, ptrSerializer, false)
	if err != nil {
		return fmt.Errorf("failed to register pointer union by name: %w", err)
	}
	return nil
}

func (r *TypeResolver) RegisterExt(extId int16, type_ reflect.Type) error {
	// Registering type is necessary, otherwise we may don't have the symbols of corresponding type when deserializing.
	panic("not supported")
}

// RegisterNamedExtension registers a type as an extension type (NAMED_EXT).
// Extension types use a user-provided serializer for custom serialization logic.
// This is used for types with custom serializers in cross-language serialization.
func (r *TypeResolver) RegisterNamedExtension(
	type_ reflect.Type,
	namespace string,
	typeName string,
	userSerializer ExtensionSerializer,
) error {
	if userSerializer == nil {
		return fmt.Errorf("serializer cannot be nil for extension type %s", type_)
	}
	if prev, ok := r.typeToSerializers[type_]; ok {
		return fmt.Errorf("type %s already has a serializer %s registered", type_, prev)
	}
	if namespace == "" {
		if idx := strings.LastIndex(typeName, "."); idx != -1 {
			namespace = typeName[:idx]
			typeName = typeName[idx+1:]
		}
	}
	if typeName == "" && namespace != "" {
		return fmt.Errorf("typeName cannot be empty if namespace is provided")
	}
	var tag string
	if namespace == "" {
		tag = typeName
	} else {
		tag = namespace + "." + typeName
	}

	// Create adapter wrapping the user's ExtensionSerializer
	serializer := &extensionSerializerAdapter{type_: type_, typeTag: tag, userSerial: userSerializer}
	r.typeToSerializers[type_] = serializer
	r.typeToTypeInfo[type_] = "@" + tag
	r.typeInfoToType["@"+tag] = type_

	ptrType := reflect.PtrTo(type_)
	ptrSerializer := &ptrToValueSerializer{valueSerializer: serializer}
	r.typeToSerializers[ptrType] = ptrSerializer
	r.typeTagToSerializers[tag] = ptrSerializer
	r.typeToTypeInfo[ptrType] = "*@" + tag
	r.typeInfoToType["*@"+tag] = ptrType

	// Use NAMED_EXT type ID for extension types
	typeId := uint32(NAMED_EXT)

	// Register both value and pointer types
	_, err := r.registerType(type_, typeId, invalidUserTypeID, namespace, typeName, nil, false)
	if err != nil {
		return fmt.Errorf("failed to register extension type: %w", err)
	}
	_, err = r.registerType(ptrType, typeId, invalidUserTypeID, namespace, typeName, nil, false)
	if err != nil {
		return fmt.Errorf("failed to register extension type: %w", err)
	}
	return nil
}

// RegisterExtension registers a type as an extension type with a numeric ID.
func (r *TypeResolver) RegisterExtension(
	type_ reflect.Type,
	userTypeID uint32,
	userSerializer ExtensionSerializer,
) error {
	if userTypeID > maxUserTypeID {
		return fmt.Errorf("typeID must be in range [0, 0xfffffffe], got %d", userTypeID)
	}
	if userSerializer == nil {
		return fmt.Errorf("serializer cannot be nil for extension type %s", type_)
	}
	if prev, ok := r.typeToSerializers[type_]; ok {
		return fmt.Errorf("type %s already has a serializer %s registered", type_, prev)
	}

	// Create adapter wrapping the user's ExtensionSerializer
	serializer := &extensionSerializerAdapter{type_: type_, typeTag: "", userSerial: userSerializer}
	r.typeToSerializers[type_] = serializer

	ptrType := reflect.PtrTo(type_)
	ptrSerializer := &ptrToValueSerializer{valueSerializer: serializer}
	r.typeToSerializers[ptrType] = ptrSerializer

	// Register type info for both value and pointer types
	typeInfo := &TypeInfo{
		Type:       type_,
		TypeID:     uint32(EXT),
		UserTypeID: userTypeID,
		Serializer: serializer,
	}
	r.userTypeIdToTypeInfo[userTypeID] = typeInfo
	r.typesInfo[type_] = typeInfo
	r.typesInfo[ptrType] = typeInfo

	return nil
}

func (r *TypeResolver) getSerializerByType(type_ reflect.Type, mapInStruct bool) (Serializer, error) {
	if serializer, ok := r.typeToSerializers[type_]; !ok {
		if serializer, err := r.createSerializer(type_, mapInStruct); err != nil {
			return nil, err
		} else {
			r.typeToSerializers[type_] = serializer
			return serializer, nil
		}
	} else {
		return serializer, nil
	}
}

// getTypeIdByType returns the TypeId for a given type, or 0 if not found in typesInfo.
// This is used to get the type ID without calling Serializer.TypeId().
func (r *TypeResolver) getTypeIdByType(type_ reflect.Type) TypeId {
	if info, ok := getOptionalInfo(type_); ok {
		type_ = info.valueType
	}
	if info, ok := r.typesInfo[type_]; ok {
		return TypeId(info.TypeID)
	}
	if type_ != nil && type_.Kind() == reflect.Ptr {
		if info, ok := r.typesInfo[type_.Elem()]; ok {
			return TypeId(info.TypeID)
		}
	}
	return 0
}

func (r *TypeResolver) getSerializerByTypeTag(typeTag string) (Serializer, error) {
	if serializer, ok := r.typeTagToSerializers[typeTag]; !ok {
		return nil, fmt.Errorf("type %s not supported", typeTag)
	} else {
		return serializer, nil
	}
}

// getSerializerByTypeID returns the serializer for a given type ID, or nil if not found.
func (r *TypeResolver) getSerializerByTypeID(typeID uint32) Serializer {
	// First try to get the type from typeIdToType
	if t, ok := r.typeIdToType[TypeId(typeID)]; ok {
		if serializer, ok := r.typeToSerializers[t]; ok {
			return serializer
		}
	}
	// Also check typeIDToTypeInfo for the type
	if info, ok := r.typeIDToTypeInfo[typeID]; ok {
		if serializer, ok := r.typeToSerializers[info.Type]; ok {
			return serializer
		}
	}
	return nil
}

func (r *TypeResolver) getTypeInfo(value reflect.Value, create bool) (*TypeInfo, error) {
	// First check if type info exists in cache
	if value.Kind() == reflect.Interface {
		// make sure the concrete value don't miss its real typeInfo
		value = value.Elem()
	}

	// Fast path: check type pointer cache for O(1) lookup
	typeString := value.Type()
	typePtr := typePointer(typeString)
	if cachedInfo, ok := r.typePointerCache[typePtr]; ok {
		return cachedInfo, nil
	}

	// Slow path: map lookup by reflect.Type
	if info, ok := r.typesInfo[typeString]; ok {
		if info.Serializer == nil {
			/*
			   Lazy initialize serializer if not created yet
			   mapInStruct equals false because this path isn't taken when extracting field info from structs;
			   for all other map cases, it remains false
			*/
			serializer, err := r.createSerializer(value.Type(), false)
			if err != nil {
				fmt.Errorf("failed to create serializer: %w", err)
			}
			info.Serializer = serializer
		}
		// Cache for future fast lookups
		r.typePointerCache[typePtr] = info
		return info, nil
	}

	var internal = false
	type_ := value.Type()
	// Get package path and type name for registration
	var typeName string
	var pkgPath string
	rawInfo, ok := r.typeToTypeInfo[type_]
	if !ok {
		// Type not explicitly registered - extract from reflect.Type
		pkgPath = type_.PkgPath()
		typeName = type_.Name()
	} else {
		clean := strings.TrimPrefix(rawInfo, "*@")
		clean = strings.TrimPrefix(clean, "@")
		typeName = clean
		if idx := strings.LastIndex(clean, "."); idx != -1 {
			pkgPath = clean[:idx]
			typeName = clean[idx+1:]
		}
	}

	// Handle special types that require explicit registration
	switch {
	case type_.Kind() == reflect.Ptr:
		elemType := type_.Elem()

		// Check if the element type is already registered
		if elemInfo, ok := r.typesInfo[elemType]; ok {
			// Element type is registered, create pointer serializer using the same type info
			var ptrSerializer Serializer

			// Get the serializer for the element type
			elemSerializer := elemInfo.Serializer
			if elemSerializer == nil {
				elemSerializer, _ = r.getSerializerByType(elemType, false)
			}

			if elemType.Kind() == reflect.Interface {
				// Pointer to interface
				ptrSerializer = &ptrToInterfaceSerializer{}
			} else {
				// Pointer to concrete value
				ptrSerializer = &ptrToValueSerializer{valueSerializer: elemSerializer}
			}

			// Create TypeInfo for pointer using element's namespace/typename
			ptrInfo := &TypeInfo{
				Type:          type_,
				FullNameBytes: elemInfo.FullNameBytes,
				PkgPathBytes:  elemInfo.PkgPathBytes,
				NameBytes:     elemInfo.NameBytes,
				IsDynamic:     elemInfo.IsDynamic,
				TypeID:        elemInfo.TypeID,
				UserTypeID:    elemInfo.UserTypeID,
				DispatchId:    elemInfo.DispatchId,
				Serializer:    ptrSerializer,
				NeedWriteDef:  elemInfo.NeedWriteDef,
				hashValue:     elemInfo.hashValue,
			}

			// Cache the pointer type info
			r.typesInfo[type_] = ptrInfo
			return ptrInfo, nil
		}

		// Element type not registered - try auto-registration for structs
		if elemType.Kind() == reflect.Struct {
			// First register the value type
			elemPkgPath := elemType.PkgPath()
			elemTypeName := elemType.Name()
			if err := r.RegisterNamedStruct(elemType, 0, elemPkgPath, elemTypeName); err != nil {
				// Might already be registered, that's okay
				_ = err
			}
			// Now the pointer type should be registered
			if info, ok := r.typesInfo[type_]; ok {
				return info, nil
			}
			return nil, fmt.Errorf("failed to find registered pointer type %v", type_)
		}

		// For primitive types and other types, we can auto-create pointer serializer
		elemSerializer, err := r.getSerializerByType(elemType, false)
		if err == nil && elemSerializer != nil {
			// Create pointer serializer for primitive/basic types
			ptrSerializer := &ptrToValueSerializer{valueSerializer: elemSerializer}

			// Create minimal TypeInfo for pointer (no cross-language type info for primitives)
			ptrInfo := &TypeInfo{
				Type:       type_,
				TypeID:     0, // Dynamic type
				Serializer: ptrSerializer,
			}

			r.typesInfo[type_] = ptrInfo
			return ptrInfo, nil
		}

		return nil, fmt.Errorf("pointer element type %v must be registered", elemType)
	case type_.Kind() == reflect.Interface:
		return nil, fmt.Errorf("interface types must be registered explicitly")
	case pkgPath == "" && typeName == "":
		// Allow anonymous collection types (maps, slices, arrays) without registration
		kind := type_.Kind()
		if kind != reflect.Map && kind != reflect.Slice && kind != reflect.Array {
			return nil, fmt.Errorf("anonymous types must be registered explicitly")
		}
		// For collections, continue with auto-registration below
	}

	// Determine type ID and registration strategy
	var typeID uint32
	switch {
	case r.isXlang && !r.requireRegistration:
		// Auto-assign IDs
		typeID = 0
	case type_.Kind() == reflect.Array || type_.Kind() == reflect.Slice || type_.Kind() == reflect.Map:
		// Allow anonymous collection types to use dynamic type ID 0
		typeID = 0
	default:
		panic(fmt.Errorf("type %v must be registered explicitly", type_))
	}

	/*
	   There are still some issues to address when adapting structs:
	   Named structs need both value and pointer types registered using the negative ID system
	   to assign the correct typeID.
	   Multidimensional slices should use typeID = 21 for recursive serialization; on
	   deserialization, users receive []any and must apply conversion function.
	   Array types aren’t tracked separately in fory-go’s type system; semantically,
	   arrays reuse their corresponding slice serializer/deserializer. We serialize arrays
	   via their slice metadata and convert back to arrays by conversion function.
	   All other slice types are treated as lists (typeID 21).
	*/
	if value.Kind() == reflect.Struct {
		typeID = uint32(r.structTypeID(value.Type(), true))
	} else if value.IsValid() && value.Kind() == reflect.Interface && value.Elem().Kind() == reflect.Struct {
		typeID = uint32(r.structTypeID(value.Elem().Type(), true))
	} else if value.IsValid() && value.Kind() == reflect.Ptr && value.Elem().Kind() == reflect.Struct {
		typeID = uint32(r.structTypeID(value.Elem().Type(), true))
	} else if value.Kind() == reflect.Map {
		typeID = MAP
	} else if value.Kind() == reflect.Array {
		// Handle primitive arrays with specific type IDs
		elemKind := type_.Elem().Kind()
		var arrayTypeID uint32
		var serializer Serializer
		switch elemKind {
		case reflect.Bool:
			arrayTypeID = BOOL_ARRAY
			serializer = boolArraySerializer{arrayType: type_}
		case reflect.Int8:
			arrayTypeID = INT8_ARRAY
			serializer = int8ArraySerializer{arrayType: type_}
		case reflect.Int16:
			arrayTypeID = INT16_ARRAY
			serializer = int16ArraySerializer{arrayType: type_}
		case reflect.Int32:
			arrayTypeID = INT32_ARRAY
			serializer = int32ArraySerializer{arrayType: type_}
		case reflect.Int64:
			arrayTypeID = INT64_ARRAY
			serializer = int64ArraySerializer{arrayType: type_}
		case reflect.Uint8:
			arrayTypeID = BINARY
			serializer = uint8ArraySerializer{arrayType: type_}
		case reflect.Float32:
			arrayTypeID = FLOAT32_ARRAY
			serializer = float32ArraySerializer{arrayType: type_}
		case reflect.Float64:
			arrayTypeID = FLOAT64_ARRAY
			serializer = float64ArraySerializer{arrayType: type_}
		case reflect.Int:
			if intSize == 8 {
				arrayTypeID = INT64_ARRAY
				serializer = int64ArraySerializer{arrayType: type_}
			} else {
				arrayTypeID = INT32_ARRAY
				serializer = int32ArraySerializer{arrayType: type_}
			}
		case reflect.Uint16:
			arrayTypeID = UINT16_ARRAY
			serializer = uint16ArraySerializer{arrayType: type_}
		case reflect.Uint32:
			arrayTypeID = UINT32_ARRAY
			serializer = uint32ArraySerializer{arrayType: type_}
		case reflect.Uint64:
			arrayTypeID = UINT64_ARRAY
			serializer = uint64ArraySerializer{arrayType: type_}
		case reflect.Uint:
			if intSize == 8 {
				arrayTypeID = UINT64_ARRAY
				serializer = uint64ArraySerializer{arrayType: type_}
			} else {
				arrayTypeID = UINT32_ARRAY
				serializer = uint32ArraySerializer{arrayType: type_}
			}
		default:
			// Generic array - use LIST type ID
			arrayTypeID = LIST
			// Create arrayConcreteValueSerializer for non-primitive arrays
			elemSerializer, err := r.getSerializerByType(type_.Elem(), false)
			if err == nil && elemSerializer != nil {
				serializer = &arrayConcreteValueSerializer{
					type_:          type_,
					elemSerializer: elemSerializer,
					referencable:   isRefType(type_.Elem(), r.isXlang),
				}
			}
		}
		// Create and cache type info for the array
		arrayInfo := &TypeInfo{
			Type:       type_,
			TypeID:     arrayTypeID,
			Serializer: serializer,
		}
		r.typesInfo[type_] = arrayInfo
		return arrayInfo, nil
	} else if isMultiDimensionaSlice(value) {
		typeID = LIST
		info := r.typeIDToTypeInfo[typeID]
		return info, nil
	} else if value.Kind() == reflect.Slice {
		// Regular slices are treated as LIST
		typeID = LIST
	}

	// Register the type with full metadata
	return r.registerType(
		type_,
		typeID,
		invalidUserTypeID,
		pkgPath,
		typeName,
		nil, // serializer will be created during registration
		internal)
}

// Check if the slice is multidimensional
func isMultiDimensionaSlice(v reflect.Value) bool {
	t := v.Type()
	if t.Kind() != reflect.Slice {
		return false
	}
	return t.Elem().Kind() == reflect.Slice
}

func (r *TypeResolver) registerType(
	type_ reflect.Type,
	typeID uint32,
	userTypeID uint32,
	namespace string,
	typeName string,
	serializer Serializer,
	internal bool,
) (*TypeInfo, error) {
	// Input validation
	if type_ == nil {
		panic("nil type")
	}
	if typeName == "" && namespace != "" {
		panic("namespace provided without typeName")
	}
	if internal && typeID > internalTypeIDLimit {
		panic(fmt.Sprintf("internal type id overflow: %d", typeID))
	}
	if internal && serializer != nil {
		if err := r.registerSerializer(type_, TypeId(typeID), serializer); err != nil {
			panic(fmt.Errorf("impossible error: %s", err))
		}
	}
	// Serializer initialization
	if !internal && serializer == nil {
		var err error
		serializer = r.typeToSerializers[type_] // Check pre-registered serializers
		if serializer == nil {
			// Create new serializer if not found
			if serializer, err = r.createSerializer(type_, false); err != nil {
				panic(fmt.Sprintf("failed to create serializer: %v", err))
			}
		}
	}

	// Encode type metadata strings
	var nsBytes, typeBytes *MetaStringBytes
	if typeName != "" {
		// Handle namespace extraction from typeName if needed
		if namespace == "" {
			if lastDot := strings.LastIndex(typeName, "."); lastDot != -1 {
				namespace = typeName[:lastDot]
				typeName = typeName[lastDot+1:]
			}
		}

		nsMeta, _ := r.namespaceEncoder.EncodePackage(namespace)
		if nsBytes = r.metaStringResolver.GetMetaStrBytes(&nsMeta); nsBytes == nil {
			panic("failed to encode namespace")
		}

		typeMeta, _ := r.typeNameEncoder.EncodeTypeName(typeName)
		if typeBytes = r.metaStringResolver.GetMetaStrBytes(&typeMeta); typeBytes == nil {
			panic("failed to encode type name")
		}
	}

	// Build complete type information structure
	typeInfo := &TypeInfo{
		Type:         type_,
		TypeID:       typeID,
		UserTypeID:   userTypeID,
		Serializer:   serializer,
		PkgPathBytes: nsBytes,   // Encoded namespace bytes
		NameBytes:    typeBytes, // Encoded type name bytes
		IsDynamic:    isDynamicType(type_),
		DispatchId:   GetDispatchId(type_), // Static type ID for fast path
		hashValue:    calcTypeHash(type_),  // Precomputed hash for fast lookups
		NeedWriteRef: NeedWriteRef(TypeId(typeID)),
	}
	if structSer, ok := serializer.(*structSerializer); ok {
		structSer.typeID = typeID
		structSer.userTypeID = userTypeID
	}
	// Update resolver caches:
	r.typesInfo[type_] = typeInfo // Cache by type string
	if typeName != "" {
		nameKey := [2]string{namespace, typeName}
		// For struct types, prefer value type over pointer type in namedTypeToTypeInfo
		// This prevents pointer type from overwriting value type registration
		if existing, exists := r.namedTypeToTypeInfo[nameKey]; !exists {
			r.namedTypeToTypeInfo[nameKey] = typeInfo
		} else if type_.Kind() != reflect.Ptr && existing.Type.Kind() == reflect.Ptr {
			// If existing is pointer but we're registering value type, prefer value type
			r.namedTypeToTypeInfo[nameKey] = typeInfo
		}
		// Cache by hashed namespace/name bytes
		r.nsTypeToTypeInfo[nsTypeKey{nsBytes.Hashcode, typeBytes.Hashcode}] = typeInfo
	}

	// Cache by type ID (for cross-language support)
	if (TypeId(typeID) == ENUM || TypeId(typeID) == STRUCT ||
		TypeId(typeID) == COMPATIBLE_STRUCT || TypeId(typeID) == EXT ||
		TypeId(typeID) == TYPED_UNION) &&
		userTypeID != invalidUserTypeID {
		if _, ok := r.userTypeIdToTypeInfo[userTypeID]; !ok {
			r.userTypeIdToTypeInfo[userTypeID] = typeInfo
		}
	} else if TypeId(typeID) != ENUM && TypeId(typeID) != STRUCT &&
		TypeId(typeID) != COMPATIBLE_STRUCT && TypeId(typeID) != EXT &&
		TypeId(typeID) != TYPED_UNION &&
		!IsNamespacedType(TypeId(typeID)) {
		/*
		   This function is required to maintain the typeID registry: all types
		   are registered at startup, and we keep this table updated.
		   We only insert into this map if the entry does not already exist
		   to avoid overwriting correct entries.
		   After removing allocate ID, for map[x]y cases we uniformly use
		   the serializer for typeID 23.
		   Overwriting here would replace info.Type with incorrect data,
		   causing map deserialization to load the wrong type.
		   Therefore, we always keep the initial record for map[any]any.
		   For standalone maps, we use this generic type loader.
		   For maps inside named structs, the map serializer
		   will be supplied with the correct element type at serialization time.
		*/
		if _, ok := r.typeIDToTypeInfo[typeID]; !ok {
			r.typeIDToTypeInfo[typeID] = typeInfo
		}
	}
	return typeInfo, nil
}

func calcTypeHash(type_ reflect.Type) uint64 {
	// Implement proper hash calculation based on type
	h := fnv.New64a()
	h.Write([]byte(type_.PkgPath()))
	h.Write([]byte(type_.Name()))
	h.Write([]byte(type_.Kind().String()))
	return h.Sum64()
}

func (r *TypeResolver) metaShareEnabled() bool {
	return r.fory != nil && r.fory.metaContext != nil && r.fory.config.Compatible
}

func (r *TypeResolver) structTypeID(type_ reflect.Type, named bool) TypeId {
	useCompatible := r.metaShareEnabled()
	if useCompatible {
		if evolving, ok := structEvolvingOverride(type_); ok && !evolving {
			useCompatible = false
		}
	}
	if named {
		if useCompatible {
			return NAMED_COMPATIBLE_STRUCT
		}
		return NAMED_STRUCT
	}
	if useCompatible {
		return COMPATIBLE_STRUCT
	}
	return STRUCT
}

// WriteTypeInfo writes type info to buffer.
// This is exported for use by generated code.
func (r *TypeResolver) WriteTypeInfo(buffer *ByteBuffer, typeInfo *TypeInfo, err *Error) {
	if typeInfo == nil {
		return
	}
	typeID := typeInfo.TypeID
	buffer.WriteUint8(uint8(typeID))

	// Handle type meta based on internal type ID (matching Java XtypeResolver.writeTypeInfo)
	switch TypeId(typeID) {
	case ENUM, STRUCT, EXT, TYPED_UNION:
		buffer.WriteVarUint32(typeInfo.UserTypeID)
	case COMPATIBLE_STRUCT, NAMED_COMPATIBLE_STRUCT:
		r.writeSharedTypeMeta(buffer, typeInfo, err)
	case NAMED_ENUM, NAMED_STRUCT, NAMED_EXT, NAMED_UNION:
		if r.metaShareEnabled() {
			r.writeSharedTypeMeta(buffer, typeInfo, err)
			return
		}
		// WriteData package path (namespace) metadata
		r.metaStringResolver.WriteMetaStringBytes(buffer, typeInfo.PkgPathBytes, err)
		// WriteData type name metadata
		r.metaStringResolver.WriteMetaStringBytes(buffer, typeInfo.NameBytes, err)
	default:
		break
	}
}

func (r *TypeResolver) writeSharedTypeMeta(buffer *ByteBuffer, typeInfo *TypeInfo, err *Error) {
	context := r.fory.MetaContext()
	key := typePointer(typeInfo.Type)
	writeTypeDefInline := func() {
		// Only build TypeDef for struct types - enums don't have field definitions
		actualType := typeInfo.Type
		if actualType.Kind() == reflect.Ptr {
			actualType = actualType.Elem()
		}
		if actualType.Kind() == reflect.Struct {
			typeDef, typeDefErr := r.getTypeDef(typeInfo.Type, true)
			if typeDefErr != nil {
				err.SetError(typeDefErr)
				return
			}
			// Write TypeDef bytes inline
			typeDef.writeTypeDef(buffer, err)
		}
	}
	writeTypeDefWithZeroMarker := func() {
		actualType := typeInfo.Type
		if actualType.Kind() == reflect.Ptr {
			actualType = actualType.Elem()
		}
		if actualType.Kind() != reflect.Struct {
			buffer.WriteUint8(0)
			return
		}
		typeDef, typeDefErr := r.getTypeDef(typeInfo.Type, true)
		if typeDefErr != nil {
			err.SetError(typeDefErr)
			return
		}
		buffer.WriteUint8(0)
		typeDef.writeTypeDef(buffer, err)
	}
	if !context.typeMapActive {
		if !context.hasFirstType {
			context.hasFirstType = true
			context.firstTypePtr = key
			// New type: index << 1, LSB=0, followed by TypeDef bytes inline
			writeTypeDefWithZeroMarker()
			return
		}
		if key == context.firstTypePtr {
			// Reference to first type: (0 << 1) | 1
			buffer.WriteUint8(1)
			return
		}
		context.typeMapActive = true
		if context.typeMap == nil {
			context.typeMap = make(map[uintptr]uint32, 8)
		} else if len(context.typeMap) != 0 {
			for k := range context.typeMap {
				delete(context.typeMap, k)
			}
		}
		context.typeMap[context.firstTypePtr] = 0
	} else if key == context.firstTypePtr {
		buffer.WriteUint8(1)
		return
	}

	if index, exists := context.typeMap[key]; exists {
		// Reference to previously written type: (index << 1) | 1, LSB=1
		marker := (index << 1) | 1
		if marker < 0x80 {
			buffer.WriteUint8(uint8(marker))
		} else {
			buffer.WriteVarUint32(marker)
		}
		return
	}

	// New type: index << 1, LSB=0, followed by TypeDef bytes inline
	newIndex := uint32(len(context.typeMap))
	marker := newIndex << 1
	if marker < 0x80 {
		buffer.WriteUint8(uint8(marker))
	} else {
		buffer.WriteVarUint32(marker)
	}
	context.typeMap[key] = newIndex
	writeTypeDefInline()
}

func (r *TypeResolver) getTypeDef(typ reflect.Type, create bool) (*TypeDef, error) {
	// Normalize pointer types to their element type for consistent caching.
	if typ.Kind() == reflect.Ptr {
		typ = typ.Elem()
	}

	if existingTypeDef, exists := r.typeToTypeDef[typ]; exists {
		return existingTypeDef, nil
	}

	if !create {
		return nil, fmt.Errorf("TypeDef not found for type %s", typ)
	}

	zero := reflect.Zero(typ)
	typeDef, err := buildTypeDef(r.fory, zero)
	if err != nil {
		return nil, err
	}
	r.typeToTypeDef[typ] = typeDef
	return typeDef, nil
}

func (r *TypeResolver) readSharedTypeMeta(buffer *ByteBuffer, err *Error) *TypeInfo {
	context := r.fory.MetaContext()
	if context == nil {
		err.SetError(fmt.Errorf("MetaContext is nil - ensure compatible mode is enabled"))
		return nil
	}

	// Read index marker using streaming protocol
	indexMarker := buffer.ReadVarUint32(err)
	if err.HasError() {
		return nil
	}

	isRef := (indexMarker & 1) == 1
	index := int32(indexMarker >> 1)

	if isRef {
		// Reference to previously read type
		if index < 0 || index >= int32(len(context.readTypeInfos)) {
			err.SetError(fmt.Errorf("TypeInfo not found for index %d (have %d type infos)", index, len(context.readTypeInfos)))
			return nil
		}
		info := context.readTypeInfos[index]

		// Validate that we got a valid TypeInfo
		if info.Serializer == nil {
			err.SetError(fmt.Errorf("TypeInfo at index %d has nil Serializer (type=%v, typeID=%d)", index, info.Type, info.TypeID))
			return nil
		}

		return info
	}

	// New type - read TypeDef inline
	id := buffer.ReadInt64(err)
	if err.HasError() {
		return nil
	}

	var td *TypeDef
	if existingTd, exists := r.defIdToTypeDef[id]; exists {
		skipTypeDef(buffer, id, err)
		td = existingTd
	} else {
		newTd := readTypeDef(r.fory, buffer, id, err)
		if err.HasError() {
			return nil
		}
		r.defIdToTypeDef[id] = newTd
		td = newTd
	}

	typeInfo, typeInfoErr := td.getOrBuildTypeInfo(r)
	if typeInfoErr != nil {
		err.SetError(typeInfoErr)
		return nil
	}

	context.readTypeInfos = append(context.readTypeInfos, typeInfo)
	return typeInfo
}

func (r *TypeResolver) createSerializer(type_ reflect.Type, mapInStruct bool) (s Serializer, err error) {
	if info, ok := getOptionalInfo(type_); ok {
		optionalType := type_
		if optionalType.Kind() == reflect.Ptr {
			optionalType = optionalType.Elem()
		}
		if err := validateOptionalValueType(info.valueType); err != nil {
			return nil, err
		}
		valueSerializer, err := r.getSerializerByType(info.valueType, false)
		if err != nil {
			return nil, err
		}
		if valueSerializer == nil {
			return nil, fmt.Errorf("no serializer found for optional element type %s", info.valueType)
		}
		return newOptionalSerializer(optionalType, info, valueSerializer), nil
	}
	kind := type_.Kind()
	switch kind {
	case reflect.Ptr:
		elemType := type_.Elem()
		elemKind := elemType.Kind()

		// Check for pointer to pointer (not supported)
		if elemKind == reflect.Ptr {
			return nil, fmt.Errorf("pointer to pointer is not supported but got type %s", type_)
		}

		// Check for pointer to interface
		if elemKind == reflect.Interface {
			return &ptrToInterfaceSerializer{}, nil
		}

		// For pointer to slice/map, just use the element type's serializer directly
		// because slices and maps are already reference types in Go
		if elemKind == reflect.Slice || elemKind == reflect.Map {
			return r.getSerializerByType(elemType, mapInStruct)
		}

		// Pointer to concrete value (struct, primitive, etc.)
		valueSerializer, err := r.getSerializerByType(elemType, false)
		if err != nil {
			return nil, err
		}
		if valueSerializer == nil {
			return nil, fmt.Errorf("no serializer found for element type %s", elemType)
		}
		return &ptrToValueSerializer{valueSerializer}, nil
	case reflect.Slice:
		elem := type_.Elem()
		// Use optimized primitive slice serializers for all primitive numeric types
		// These use direct memory copy on little-endian systems for maximum performance
		switch elem.Kind() {
		case reflect.Bool:
			return boolSliceSerializer{}, nil
		case reflect.Int8:
			return int8SliceSerializer{}, nil
		case reflect.Int16:
			return int16SliceSerializer{}, nil
		case reflect.Int32:
			return int32SliceSerializer{}, nil
		case reflect.Int64:
			return int64SliceSerializer{}, nil
		case reflect.Float32:
			return float32SliceSerializer{}, nil
		case reflect.Float64:
			return float64SliceSerializer{}, nil
		case reflect.Int:
			return intSliceSerializer{}, nil
		case reflect.Uint:
			return uintSliceSerializer{}, nil
		case reflect.Uint8:
			// []byte uses byteSliceSerializer
			return byteSliceSerializer{}, nil
		case reflect.Uint16:
			// Check for fory.Float16 (aliased to uint16)
			if elem == float16Type {
				return float16SliceSerializer{}, nil
			}
			// Check for fory.BFloat16 (aliased to uint16)
			if elem == bfloat16Type {
				return bfloat16SliceSerializer{}, nil
			}
			return uint16SliceSerializer{}, nil
		case reflect.Uint32:
			return uint32SliceSerializer{}, nil
		case reflect.Uint64:
			return uint64SliceSerializer{}, nil
		case reflect.String:
			return stringSliceSerializer{}, nil
		}
		// For dynamic types, use dynamic slice serializer
		if isDynamicType(elem) {
			return sliceDynSerializer{}, nil
		} else {
			elemSerializer, err := r.getSerializerByType(type_.Elem(), false)
			if err != nil {
				return nil, err
			}
			// Always use xlang mode (LIST typeId) for non-primitive slices
			return newSliceSerializer(type_, elemSerializer, r.isXlang)
		}
	case reflect.Array:
		elem := type_.Elem()
		// For primitive arrays, use the array serializers from array_primitive.go
		elemKind := elem.Kind()
		switch elemKind {
		case reflect.Bool:
			return boolArraySerializer{arrayType: type_}, nil
		case reflect.Int8:
			return int8ArraySerializer{arrayType: type_}, nil
		case reflect.Int16:
			return int16ArraySerializer{arrayType: type_}, nil
		case reflect.Int32:
			return int32ArraySerializer{arrayType: type_}, nil
		case reflect.Int64:
			return int64ArraySerializer{arrayType: type_}, nil
		case reflect.Uint8:
			return uint8ArraySerializer{arrayType: type_}, nil
		case reflect.Float32:
			return float32ArraySerializer{arrayType: type_}, nil
		case reflect.Float64:
			return float64ArraySerializer{arrayType: type_}, nil
		case reflect.Int:
			// Platform-dependent int type - use int32 or int64 array serializer
			if reflect.TypeOf(int(0)).Size() == 8 {
				return int64ArraySerializer{arrayType: type_}, nil
			}
			return int32ArraySerializer{arrayType: type_}, nil
		case reflect.Uint16:
			// Check for fory.Float16 (aliased to uint16)
			if elem == float16Type {
				return float16ArraySerializer{arrayType: type_}, nil
			}
			// Check for fory.BFloat16 (aliased to uint16)
			if elem == bfloat16Type {
				return bfloat16ArraySerializer{arrayType: type_}, nil
			}
			return uint16ArraySerializer{arrayType: type_}, nil
		case reflect.Uint32:
			return uint32ArraySerializer{arrayType: type_}, nil
		case reflect.Uint64:
			return uint64ArraySerializer{arrayType: type_}, nil
		case reflect.Uint:
			// Platform-dependent uint type - use uint32 or uint64 array serializer
			// Wire format uses INT32_ARRAY or INT64_ARRAY respectively
			if reflect.TypeOf(uint(0)).Size() == 8 {
				return uint64ArraySerializer{arrayType: type_}, nil
			}
			return uint32ArraySerializer{arrayType: type_}, nil
		}

		if isDynamicType(elem) {
			return arraySerializer{}, nil
		} else {
			elemSerializer, err := r.getSerializerByType(type_.Elem(), false)
			if err != nil {
				return nil, err
			}
			return &arrayConcreteValueSerializer{
				type_:          type_,
				elemSerializer: elemSerializer,
				referencable:   isRefType(type_.Elem(), r.isXlang),
			}, nil
		}
	case reflect.Map:
		// Check if this is a Set type (map[T]struct{} where value is empty struct)
		// This includes both fory.Set[T] and raw map[T]struct{}
		if isSetReflectType(type_) {
			return setSerializer{}, nil
		}
		hasKeySerializer, hasValueSerializer := !isDynamicType(type_.Key()), !isDynamicType(type_.Elem())
		// Determine key/value referencability using isRefType which handles xlang mode
		keyReferencable := isRefType(type_.Key(), r.isXlang)
		valueReferencable := isRefType(type_.Elem(), r.isXlang)
		if hasKeySerializer || hasValueSerializer {
			var keySerializer, valueSerializer Serializer
			/*
			   Current tests do not cover scenarios where a map’s value is itself a map.
			   It’s undecided whether to use a type-specific map serializer or a generic one.
			   mapInStruct is currently set to false.
			*/
			if hasKeySerializer {
				keySerializer, err = r.getSerializerByType(type_.Key(), false)
				if err != nil {
					return nil, err
				}
			}
			if hasValueSerializer {
				valueSerializer, err = r.getSerializerByType(type_.Elem(), false)
				if err != nil {
					return nil, err
				}
			}
			return &mapSerializer{
				type_:             type_,
				keySerializer:     keySerializer,
				valueSerializer:   valueSerializer,
				keyReferencable:   keyReferencable,
				valueReferencable: valueReferencable,
				hasGenerics:       mapInStruct,
			}, nil
		}
		return mapSerializer{
			type_:             type_,
			keyReferencable:   keyReferencable,
			valueReferencable: valueReferencable,
			hasGenerics:       mapInStruct,
		}, nil
	case reflect.Struct:
		serializer := r.typeToSerializers[type_]
		if serializer == nil {
			// In xlang/compatible mode, auto-register struct types
			if r.isXlang || r.fory.config.Compatible {
				// Use the type's actual package path and name for auto-registration
				pkgPath := type_.PkgPath()
				typeName := type_.Name()
				if typeName == "" {
					return nil, fmt.Errorf("cannot auto-register anonymous struct type %s", type_.String())
				}
				// For auto-registered types, use package path as namespace and type name
				if err := r.RegisterNamedStruct(type_, 0, pkgPath, typeName); err != nil {
					return nil, fmt.Errorf("failed to auto-register struct %s: %w", type_.String(), err)
				}
				serializer = r.typeToSerializers[type_]
			}
			if serializer == nil {
				return nil, fmt.Errorf("struct type %s not registered", type_.String())
			}
		}
		return serializer, nil
	}
	return nil, fmt.Errorf("type %s not supported", type_.String())
}

// GetSliceSerializer returns the appropriate serializer for a slice type.
// For primitive element types (bool, int8, int16, int32, int64, uint8, float32, float64),
// it returns the dedicated primitive slice serializer that uses ARRAY protocol.
// For non-primitive element types, it returns sliceSerializer (LIST protocol).
func (r *TypeResolver) GetSliceSerializer(sliceType reflect.Type) (Serializer, error) {
	if sliceType.Kind() != reflect.Slice {
		return nil, fmt.Errorf("expected slice type but got %s", sliceType.Kind())
	}
	elemType := sliceType.Elem()
	// For primitive element types, use dedicated primitive slice serializers (ARRAY protocol)
	switch elemType.Kind() {
	case reflect.Bool:
		return boolSliceSerializer{}, nil
	case reflect.Int8:
		return int8SliceSerializer{}, nil
	case reflect.Int16:
		return int16SliceSerializer{}, nil
	case reflect.Int32:
		return int32SliceSerializer{}, nil
	case reflect.Int64:
		return int64SliceSerializer{}, nil
	case reflect.Uint8:
		return byteSliceSerializer{}, nil
	case reflect.Float32:
		return float32SliceSerializer{}, nil
	case reflect.Float64:
		return float64SliceSerializer{}, nil
	case reflect.Int:
		return intSliceSerializer{}, nil
	case reflect.Uint:
		return uintSliceSerializer{}, nil
	}
	// For non-primitive element types, use sliceSerializer
	elemSerializer, err := r.getSerializerByType(elemType, false)
	if err != nil {
		return nil, err
	}
	return newSliceSerializer(sliceType, elemSerializer, r.isXlang)
}

// GetSetSerializer returns the setSerializer for a Set[T] type.
// Accepts both fory.Set[T] and anonymous map[T]struct{} types.
func (r *TypeResolver) GetSetSerializer(setType reflect.Type) (Serializer, error) {
	if !isSetReflectType(setType) {
		return nil, fmt.Errorf("expected Set type (map[T]struct{}) but got %s", setType)
	}
	return setSerializer{}, nil
}

// GetArraySerializer returns the appropriate serializer for an array type.
// For primitive element types, it returns the dedicated primitive array serializer (ARRAY protocol).
// For non-primitive element types, it returns sliceSerializer (LIST protocol).
func (r *TypeResolver) GetArraySerializer(arrayType reflect.Type) (Serializer, error) {
	if arrayType.Kind() != reflect.Array {
		return nil, fmt.Errorf("expected array type but got %s", arrayType.Kind())
	}
	elemType := arrayType.Elem()
	// For primitive element types, use dedicated primitive array serializers (ARRAY protocol)
	switch elemType.Kind() {
	case reflect.Bool:
		return boolArraySerializer{arrayType: arrayType}, nil
	case reflect.Int8:
		return int8ArraySerializer{arrayType: arrayType}, nil
	case reflect.Int16:
		return int16ArraySerializer{arrayType: arrayType}, nil
	case reflect.Int32:
		return int32ArraySerializer{arrayType: arrayType}, nil
	case reflect.Int64:
		return int64ArraySerializer{arrayType: arrayType}, nil
	case reflect.Uint8:
		return uint8ArraySerializer{arrayType: arrayType}, nil
	case reflect.Float32:
		return float32ArraySerializer{arrayType: arrayType}, nil
	case reflect.Float64:
		return float64ArraySerializer{arrayType: arrayType}, nil
	case reflect.Int:
		// Platform-dependent int type
		if reflect.TypeOf(int(0)).Size() == 8 {
			return int64ArraySerializer{arrayType: arrayType}, nil
		}
		return int32ArraySerializer{arrayType: arrayType}, nil
	}
	// For non-primitive element types, use sliceSerializer
	elemSerializer, err := r.getSerializerByType(elemType, false)
	if err != nil {
		return nil, err
	}
	return newSliceSerializer(arrayType, elemSerializer, r.isXlang)
}

func isDynamicType(type_ reflect.Type) bool {
	return type_.Kind() == reflect.Interface || (type_.Kind() == reflect.Ptr && (type_.Elem().Kind() == reflect.Ptr ||
		type_.Elem().Kind() == reflect.Interface))
}

func (r *TypeResolver) writeType(buffer *ByteBuffer, type_ reflect.Type, err *Error) {
	typeInfo, ok := r.typeToTypeInfo[type_]
	if !ok {
		if encodeType, encErr := r.encodeType(type_); encErr != nil {
			err.SetError(encErr)
			return
		} else {
			typeInfo = encodeType
			r.typeToTypeInfo[type_] = encodeType
		}
	}
	r.writeMetaString(buffer, typeInfo, err)
}

func (r *TypeResolver) readType(buffer *ByteBuffer, err *Error) reflect.Type {
	metaString := r.readMetaString(buffer, err)
	type_, ok := r.typeInfoToType[metaString]
	if !ok {
		var decErr error
		type_, _, decErr = r.decodeType(metaString)
		if decErr != nil {
			err.SetError(decErr)
			return nil
		}
		r.typeInfoToType[metaString] = type_
	}
	return type_
}

func (r *TypeResolver) encodeType(type_ reflect.Type) (string, error) {
	if info, ok := r.typeToTypeInfo[type_]; ok {
		return info, nil
	}
	switch kind := type_.Kind(); kind {
	case reflect.Ptr, reflect.Array, reflect.Slice, reflect.Map:
		if elemTypeStr, err := r.encodeType(type_.Elem()); err != nil {
			return "", err
		} else {
			if kind == reflect.Ptr {
				return "*" + elemTypeStr, nil
			} else if kind == reflect.Array {
				return fmt.Sprintf("[%d]", type_.Len()) + elemTypeStr, nil
			} else if kind == reflect.Slice {
				return "[]" + elemTypeStr, nil
			} else if kind == reflect.Map {
				if keyTypeStr, err := r.encodeType(type_.Key()); err != nil {
					return "", err
				} else {
					return fmt.Sprintf("map[%s]%s", keyTypeStr, elemTypeStr), nil
				}
			}
		}
	}
	return type_.String(), nil
}

func (r *TypeResolver) decodeType(typeStr string) (reflect.Type, string, error) {
	if type_, ok := r.typeInfoToType[typeStr]; ok {
		return type_, typeStr, nil
	}
	if strings.HasPrefix(typeStr, "*") { // ptr
		subStr := typeStr[len("*"):]
		type_, subStr, err := r.decodeType(subStr)
		if err != nil {
			return nil, "", err
		} else {
			return reflect.PtrTo(type_), "*" + subStr, nil
		}
	} else if strings.HasPrefix(typeStr, "[]") { // slice
		subStr := typeStr[len("[]"):]
		type_, subStr, err := r.decodeType(subStr)
		if err != nil {
			return nil, "", err
		} else {
			return reflect.SliceOf(type_), "[]" + subStr, nil
		}
	} else if strings.HasPrefix(typeStr, "[") { // array
		arrTypeRegex, _ := regexp.Compile(`\[([0-9]+)]`)
		idx := arrTypeRegex.FindStringSubmatchIndex(typeStr)
		if idx == nil {
			return nil, "", fmt.Errorf("unparseable type %s", typeStr)
		}
		lenStr := typeStr[idx[2]:idx[3]]
		if length, err := strconv.Atoi(lenStr); err != nil {
			return nil, "", err
		} else {
			subStr := typeStr[idx[1]:]
			type_, elemStr, err := r.decodeType(subStr)
			if err != nil {
				return nil, "", err
			} else {
				return reflect.ArrayOf(length, type_), typeStr[idx[0]:idx[1]] + elemStr, nil
			}
		}
	} else if strings.HasPrefix(typeStr, "map[") {
		subStr := typeStr[len("map["):]
		keyType, keyStr, err := r.decodeType(subStr)
		if err != nil {
			return nil, "", fmt.Errorf("unparseable map type: %s : %s", typeStr, err)
		} else {
			subStr := typeStr[len("map[")+len(keyStr)+len("]"):]
			valueType, valueStr, err := r.decodeType(subStr)
			if err != nil {
				return nil, "", fmt.Errorf("unparseable map value type: %s : %s", subStr, err)
			} else {
				return reflect.MapOf(keyType, valueType), "map[" + keyStr + "]" + valueStr, nil
			}
		}
	} else {
		if idx := strings.Index(typeStr, "]"); idx >= 0 {
			return r.decodeType(typeStr[:idx])
		}
		if t, ok := r.typeInfoToType[typeStr]; !ok {
			return nil, "", fmt.Errorf("type %s not supported", typeStr)
		} else {
			return t, typeStr, nil
		}
	}
}

func (r *TypeResolver) writeTypeTag(buffer *ByteBuffer, typeTag string, err *Error) {
	r.writeMetaString(buffer, typeTag, err)
}

func (r *TypeResolver) readTypeByReadTag(buffer *ByteBuffer, err *Error) reflect.Type {
	metaString := r.readMetaString(buffer, err)
	ptrSer := r.typeTagToSerializers[metaString]
	if ptrValueSer, ok := ptrSer.(*ptrToValueSerializer); ok {
		// Extract the struct type from the pointer serializer
		// The pointer serializer wraps the value serializer, so we need to get the type from there
		if structSer, ok := ptrValueSer.valueSerializer.(*structSerializer); ok {
			return reflect.PtrTo(structSer.type_)
		}
	}
	err.SetError(fmt.Errorf("failed to extract type from serializer for %s", metaString))
	return nil
}

func (r *TypeResolver) resolveTypeInfoByMetaBytes(nsBytes, typeBytes *MetaStringBytes,
	compositeKey nsTypeKey, typeID uint32, err *Error) *TypeInfo {
	if typeInfo, exists := r.nsTypeToTypeInfo[compositeKey]; exists {
		return typeInfo
	}

	ns, decErr := r.namespaceDecoder.Decode(nsBytes.Data, nsBytes.Encoding)
	if decErr != nil {
		err.SetError(fmt.Errorf("namespace decode failed: %w", decErr))
		return nil
	}

	typeName, decErr := r.typeNameDecoder.Decode(typeBytes.Data, typeBytes.Encoding)
	if decErr != nil {
		err.SetError(fmt.Errorf("typename decode failed: %w", decErr))
		return nil
	}

	nameKey := [2]string{ns, typeName}
	if typeInfo, exists := r.namedTypeToTypeInfo[nameKey]; exists {
		r.nsTypeToTypeInfo[compositeKey] = typeInfo
		return typeInfo
	}

	fullName := typeName
	if ns != "" {
		fullName = ns + "." + typeName
	}
	err.SetError(fmt.Errorf("unregistered type: %s (typeID: %d)", fullName, typeID))
	return nil
}

// ReadTypeInfo reads type info from buffer and returns it.
// This is exported for use by generated code.
func (r *TypeResolver) ReadTypeInfo(buffer *ByteBuffer, err *Error) *TypeInfo {
	typeID := uint32(buffer.ReadUint8(err))
	internalTypeID := TypeId(typeID)

	switch internalTypeID {
	case ENUM, STRUCT, EXT, TYPED_UNION:
		userTypeID := buffer.ReadVarUint32(err)
		if err.HasError() {
			return nil
		}
		if typeInfo, exists := r.userTypeIdToTypeInfo[userTypeID]; exists {
			return typeInfo
		}
	case COMPATIBLE_STRUCT, NAMED_COMPATIBLE_STRUCT:
		return r.readSharedTypeMeta(buffer, err)
	case NAMED_ENUM, NAMED_STRUCT, NAMED_EXT, NAMED_UNION:
		if r.metaShareEnabled() {
			return r.readSharedTypeMeta(buffer, err)
		}
		// ReadData namespace and type name metadata bytes
		nsBytes, _ := r.metaStringResolver.ReadMetaStringBytes(buffer, err)
		typeBytes, _ := r.metaStringResolver.ReadMetaStringBytes(buffer, err)
		if err.HasError() {
			return nil
		}

		compositeKey := nsTypeKey{nsBytes.Hashcode, typeBytes.Hashcode}
		// For pointer and value types, use the negative ID system
		// to obtain the correct TypeInfo for subsequent deserialization
		return r.resolveTypeInfoByMetaBytes(nsBytes, typeBytes, compositeKey, typeID, err)
	default:
		break
	}

	// Handle simple type IDs (non-namespaced types)
	if typeInfo, exists := r.typeIDToTypeInfo[typeID]; exists {
		return typeInfo
	}

	// Handle UNKNOWN type (0) - used for polymorphic types
	if typeID == 0 {
		return &TypeInfo{
			Type:       interfaceType,
			TypeID:     typeID,
			DispatchId: UnknownDispatchId,
		}
	}

	err.SetError(DeserializationErrorf("unknown type id: %d", typeID))
	return nil
}

// readTypeInfoWithTypeID reads type info when the typeID has already been read from buffer.
// This is used by collection serializers that read typeID separately before deciding how to proceed.
func (r *TypeResolver) readTypeInfoWithTypeID(buffer *ByteBuffer, typeID uint32, err *Error) *TypeInfo {
	internalTypeID := TypeId(typeID)

	switch internalTypeID {
	case ENUM, STRUCT, EXT, TYPED_UNION:
		userTypeID := buffer.ReadVarUint32(err)
		if err.HasError() {
			return nil
		}
		if typeInfo, exists := r.userTypeIdToTypeInfo[userTypeID]; exists {
			return typeInfo
		}
	case COMPATIBLE_STRUCT, NAMED_COMPATIBLE_STRUCT:
		return r.readSharedTypeMeta(buffer, err)
	case NAMED_ENUM, NAMED_STRUCT, NAMED_EXT, NAMED_UNION:
		if r.metaShareEnabled() {
			return r.readSharedTypeMeta(buffer, err)
		}
		// ReadData namespace and type name metadata bytes
		nsBytes, _ := r.metaStringResolver.ReadMetaStringBytes(buffer, err)
		typeBytes, _ := r.metaStringResolver.ReadMetaStringBytes(buffer, err)
		if err.HasError() {
			return nil
		}

		compositeKey := nsTypeKey{nsBytes.Hashcode, typeBytes.Hashcode}
		return r.resolveTypeInfoByMetaBytes(nsBytes, typeBytes, compositeKey, typeID, err)
	default:
		break
	}

	// Handle simple type IDs (non-namespaced types)
	if typeInfo, exists := r.typeIDToTypeInfo[typeID]; exists {
		return typeInfo
	}

	// Handle UNKNOWN type (0) - used for polymorphic types
	if typeID == 0 {
		return &TypeInfo{
			Type:       interfaceType,
			TypeID:     typeID,
			DispatchId: UnknownDispatchId,
		}
	}

	err.SetError(fmt.Errorf("typeInfo of typeID %d not found", typeID))
	return nil
}

// ReadTypeInfoForType reads type info when the expected type is already known.
// This is an optimization that avoids expensive type resolution via namespace/typename map lookups.
// Instead of resolving the type from the buffer, it uses the passed reflect.Type directly.
//
// For STRUCT/NAMED_STRUCT: Gets serializer directly by the passed type (skips type resolution)
// For COMPATIBLE_STRUCT/NAMED_COMPATIBLE_STRUCT: Reads type def and creates serializer with passed type
func (r *TypeResolver) ReadTypeInfoForType(buffer *ByteBuffer, expectedType reflect.Type, err *Error) Serializer {
	typeID := uint32(buffer.ReadUint8(err))
	internalTypeID := TypeId(typeID)
	switch internalTypeID {
	case ENUM, STRUCT, EXT, TYPED_UNION:
		buffer.ReadVarUint32(err)
		if internalTypeID == STRUCT {
			return r.typeToSerializers[expectedType]
		}
		return nil
	case NAMED_ENUM, NAMED_STRUCT, NAMED_EXT, NAMED_UNION:
		if r.metaShareEnabled() {
			typeInfo := r.readSharedTypeMeta(buffer, err)
			if err.HasError() {
				return nil
			}
			if internalTypeID == NAMED_STRUCT {
				return typeInfo.Serializer
			}
			return nil
		}
		r.metaStringResolver.ReadMetaStringBytes(buffer, err)
		r.metaStringResolver.ReadMetaStringBytes(buffer, err)
		if internalTypeID == NAMED_STRUCT {
			return r.typeToSerializers[expectedType]
		}
		return nil
	case COMPATIBLE_STRUCT, NAMED_COMPATIBLE_STRUCT:
		// Compatible mode: read type def from shared meta
		typeInfo := r.readSharedTypeMeta(buffer, err)
		if err.HasError() {
			return nil
		}
		return typeInfo.Serializer
	default:
		// For other types, return nil - caller should handle
		return nil
	}
}

func (r *TypeResolver) getTypeById(id TypeId) (reflect.Type, error) {
	type_, ok := r.typeIdToType[id]
	if !ok {
		return nil, fmt.Errorf("type of id %d not supported, supported types: %v", id, r.typeIdToType)
	}
	return type_, nil
}

func (r *TypeResolver) getTypeInfoById(id uint32) (*TypeInfo, error) {
	if typeInfo, exists := r.typeIDToTypeInfo[id]; exists {
		return typeInfo, nil
	} else {
		return nil, fmt.Errorf("typeInfo of typeID %d not found", id)
	}
}

func (r *TypeResolver) getUserTypeInfoById(userTypeID uint32) *TypeInfo {
	if userTypeID == invalidUserTypeID {
		return nil
	}
	if typeInfo, exists := r.userTypeIdToTypeInfo[userTypeID]; exists {
		return typeInfo
	}
	return nil
}

func (r *TypeResolver) writeMetaString(buffer *ByteBuffer, str string, err *Error) {
	if id, ok := r.dynamicStringToId[str]; !ok {
		dynamicStringId := r.dynamicStringId
		r.dynamicStringId += 1
		r.dynamicStringToId[str] = dynamicStringId
		encodedMeta, encErr := r.namespaceEncoder.EncodeWithEncoding(str, meta.UTF_8)
		if encErr != nil {
			err.SetError(encErr)
			return
		}
		encoded := encodedMeta.GetEncodedBytes()
		length := len(encoded)
		buffer.WriteVarUint32(uint32(length << 1))
		if length <= SMALL_STRING_THRESHOLD {
			if length != 0 {
				buffer.WriteByte_(uint8(meta.UTF_8))
			}
		} else {
			// TODO this hash should be unique, since we don't compare data equality for performance
			h := fnv.New64a()
			if _, hashErr := h.Write(encoded); hashErr != nil {
				err.SetError(hashErr)
				return
			}
			hash := int64(h.Sum64() & 0xffffffffffffff00)
			buffer.WriteInt64(hash)
		}
		if len(str) > MaxInt16 {
			err.SetError(fmt.Errorf("too long string: %s", str))
			return
		}
		buffer.WriteBinary(encoded)
	} else {
		buffer.WriteVarUint32(uint32(((id + 1) << 1) | 1))
	}
}

func (r *TypeResolver) readMetaString(buffer *ByteBuffer, err *Error) string {
	header := buffer.ReadVarUint32(err)
	var length = int(header >> 1)
	if header&0b1 == 0 {
		encoding := meta.UTF_8
		if length <= SMALL_STRING_THRESHOLD {
			if length != 0 {
				encoding = meta.Encoding(buffer.ReadByte(err))
			}
		} else {
			// TODO support use computed hash
			hash := buffer.ReadInt64(err)
			encoding = meta.Encoding(hash & 0xFF)
		}
		raw := buffer.ReadBinary(length, err)
		if length == 0 {
			raw = nil
		}
		decoder := meta.NewDecoder('.', '_')
		str, decErr := decoder.Decode(raw, encoding)
		if decErr != nil {
			err.SetError(decErr)
			return ""
		}
		dynamicStringId := r.dynamicStringId
		r.dynamicStringId += 1
		r.dynamicIdToString[dynamicStringId] = str
		return str
	} else {
		return r.dynamicIdToString[int16(length-1)]
	}
}

func (r *TypeResolver) resetWrite() {
	if r.dynamicStringId > 0 {
		r.dynamicStringToId = map[string]int16{}
		r.dynamicIdToString = map[int16]string{}
		r.dynamicStringId = 0
	}
	// Reset meta string resolver to ensure each serialization is independent
	r.metaStringResolver.ResetWrite()
}

func (r *TypeResolver) resetRead() {
	if r.dynamicStringId > 0 {
		r.dynamicStringToId = map[string]int16{}
		r.dynamicIdToString = map[int16]string{}
		r.dynamicStringId = 0
	}
	// Reset meta string resolver to ensure each deserialization is independent
	r.metaStringResolver.ResetRead()
}

func computeStringHash(str string) int32 {
	strBytes := unsafeGetBytes(str)
	var hash int64 = 17
	for _, b := range strBytes {
		hash = hash*31 + int64(b)
		for hash >= MaxInt32 {
			hash = hash / 7
		}
	}
	return int32(hash)
}

// ErrTypeMismatch indicates a type ID mismatch during deserialization
var ErrTypeMismatch = errors.New("fory: type ID mismatch")

// MetaContext holds metadata for schema evolution and type sharing
type MetaContext struct {
	typeMap               map[uintptr]uint32 // For writing: tracks written types
	readTypeInfos         []*TypeInfo        // For reading: types read inline
	scopedMetaShareEnable bool
	firstTypePtr          uintptr
	hasFirstType          bool
	typeMapActive         bool
}

// IsScopedMetaShareEnabled returns whether scoped meta share is enabled
func (m *MetaContext) IsScopedMetaShareEnabled() bool {
	return m.scopedMetaShareEnable
}

// Reset clears the meta context for reuse
func (m *MetaContext) Reset() {
	m.hasFirstType = false
	m.typeMapActive = false
	m.firstTypePtr = 0
	if m.readTypeInfos != nil {
		m.readTypeInfos = m.readTypeInfos[:0]
	}
}

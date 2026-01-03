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
)

var (
	interfaceType = reflect.TypeOf((*interface{})(nil)).Elem()
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
	float32SliceType     = reflect.TypeOf((*[]float32)(nil)).Elem()
	float64SliceType     = reflect.TypeOf((*[]float64)(nil)).Elem()
	interfaceSliceType   = reflect.TypeOf((*[]interface{})(nil)).Elem()
	interfaceMapType     = reflect.TypeOf((*map[interface{}]interface{})(nil)).Elem()
	stringStringMapType  = reflect.TypeOf((*map[string]string)(nil)).Elem()
	stringInt64MapType   = reflect.TypeOf((*map[string]int64)(nil)).Elem()
	stringIntMapType     = reflect.TypeOf((*map[string]int)(nil)).Elem()
	stringFloat64MapType = reflect.TypeOf((*map[string]float64)(nil)).Elem()
	stringBoolMapType    = reflect.TypeOf((*map[string]bool)(nil)).Elem()
	int32Int32MapType    = reflect.TypeOf((*map[int32]int32)(nil)).Elem()
	int64Int64MapType    = reflect.TypeOf((*map[int64]int64)(nil)).Elem()
	intIntMapType        = reflect.TypeOf((*map[int]int)(nil)).Elem()
	boolType             = reflect.TypeOf((*bool)(nil)).Elem()
	byteType             = reflect.TypeOf((*byte)(nil)).Elem()
	uint8Type            = reflect.TypeOf((*uint8)(nil)).Elem()
	int8Type             = reflect.TypeOf((*int8)(nil)).Elem()
	int16Type            = reflect.TypeOf((*int16)(nil)).Elem()
	int32Type            = reflect.TypeOf((*int32)(nil)).Elem()
	int64Type            = reflect.TypeOf((*int64)(nil)).Elem()
	intType              = reflect.TypeOf((*int)(nil)).Elem()
	float32Type          = reflect.TypeOf((*float32)(nil)).Elem()
	float64Type          = reflect.TypeOf((*float64)(nil)).Elem()
	dateType             = reflect.TypeOf((*Date)(nil)).Elem()
	timestampType        = reflect.TypeOf((*time.Time)(nil)).Elem()
	genericSetType       = reflect.TypeOf((*GenericSet)(nil)).Elem()
)

// Global registry for generated serializer factories
var generatedSerializerFactories = struct {
	mu        sync.RWMutex
	factories map[reflect.Type]func() Serializer
}{
	factories: make(map[reflect.Type]func() Serializer),
}

// RegisterSerializerFactory registers a factory function for a generated serializer
func RegisterSerializerFactory(type_ interface{}, factory func() Serializer) {
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
	StaticId      StaticTypeId
	Serializer    Serializer
	NeedWriteDef  bool
	NeedWriteRef  bool // Whether this type needs reference tracking
	hashValue     uint64
	TypeDef       *TypeDef
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
	typeIdToType         map[int16]reflect.Type
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
	hashToClassInfo  map[uint64]*TypeInfo

	// Type tracking
	dynamicWrittenMetaStr []string
	typeIDToTypeInfo      map[uint32]*TypeInfo
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
}

func newTypeResolver(fory *Fory) *TypeResolver {
	r := &TypeResolver{
		typeTagToSerializers: map[string]Serializer{},
		typeToSerializers:    map[reflect.Type]Serializer{},
		typeIdToType:         map[int16]reflect.Type{},
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
		hashToClassInfo:  make(map[uint64]*TypeInfo),

		dynamicWrittenMetaStr: make([]string, 0),
		typeIDToTypeInfo:      make(map[uint32]*TypeInfo),
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
	}
	// base type info for encode/decode types.
	// composite types info will be constructed dynamically.
	for _, t := range []reflect.Type{
		boolType,
		byteType,
		int8Type,
		int16Type,
		int32Type,
		intType,
		int64Type,
		float32Type,
		float64Type,
		stringType,
		dateType,
		timestampType,
		interfaceType,
		genericSetType, // FIXME set should be a generic type
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
		_, err := r.registerType(type_, uint32(NAMED_STRUCT), pkgPath, typeName, codegenSerializer, false)
		if err != nil {
			panic(fmt.Errorf("failed to register codegen type %s: %v", typeTag, err))
		}
		// 4. Register pointer type information
		_, err = r.registerType(ptrType, uint32(NAMED_STRUCT), pkgPath, typeName, ptrCodegenSer, false)
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

func (r *TypeResolver) initialize() {
	serializers := []struct {
		reflect.Type
		TypeId
		Serializer
	}{
		{stringType, STRING, stringSerializer{}},
		{stringPtrType, STRING, ptrToStringSerializer{}},
		// Register interface types first so typeIDToTypeInfo maps to generic types
		// that can hold any element type when deserializing into interface{}
		{interfaceSliceType, LIST, sliceDynSerializer{}},
		{interfaceMapType, MAP, mapSerializer{}},
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
		{float32SliceType, FLOAT32_ARRAY, float32SliceSerializer{}},
		{float64SliceType, FLOAT64_ARRAY, float64SliceSerializer{}},
		// Register common map types for fast path with optimized serializers
		{stringStringMapType, MAP, stringStringMapSerializer{}},
		{stringInt64MapType, MAP, stringInt64MapSerializer{}},
		{stringIntMapType, MAP, stringIntMapSerializer{}},
		{stringFloat64MapType, MAP, stringFloat64MapSerializer{}},
		{stringBoolMapType, SET, setSerializer{}}, // map[T]bool represents a Set in Go
		{int32Int32MapType, MAP, int32Int32MapSerializer{}},
		{int64Int64MapType, MAP, int64Int64MapSerializer{}},
		{intIntMapType, MAP, intIntMapSerializer{}},
		// Register primitive types
		{boolType, BOOL, boolSerializer{}},
		{byteType, UINT8, byteSerializer{}},
		{int8Type, INT8, int8Serializer{}},
		{int16Type, INT16, int16Serializer{}},
		{int32Type, INT32, int32Serializer{}},
		{int64Type, INT64, int64Serializer{}},
		{intType, INT64, intSerializer{}}, // int maps to int64 for xlang
		{float32Type, FLOAT, float32Serializer{}},
		{float64Type, DOUBLE, float64Serializer{}},
		{dateType, LOCAL_DATE, dateSerializer{}},
		{timestampType, TIMESTAMP, timeSerializer{}},
		{genericSetType, SET, setSerializer{}},
	}
	for _, elem := range serializers {
		_, err := r.registerType(elem.Type, uint32(elem.TypeId), "", "", elem.Serializer, true)
		if err != nil {
			fmt.Errorf("init type error: %v", err)
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
	if !IsNamespacedType(typeId) && !isCollectionType(int16(typeId)) && !isPrimitiveArrayType(int16(typeId)) {
		if typeId > NotSupportCrossLanguage {
			if _, ok := r.typeIdToType[typeId]; !ok {
				r.typeIdToType[typeId] = type_
			}
		}
	}
	return nil
}

// RegisterByID registers a type with a numeric type ID for cross-language serialization.
// This is used when the full type ID (user_id << 8 | internal_id) is already calculated.
func (r *TypeResolver) RegisterByID(type_ reflect.Type, fullTypeID uint32) error {
	// Check if already registered
	if info, ok := r.typeIDToTypeInfo[fullTypeID]; ok {
		return fmt.Errorf("type %s with id %d has been registered", info.Type, fullTypeID)
	}

	switch type_.Kind() {
	case reflect.Struct:
		// For struct types, check if serializer already registered
		if prev, ok := r.typeToSerializers[type_]; ok {
			return fmt.Errorf("type %s already has a serializer %s registered", type_, prev)
		}

		// Create struct serializer
		tag := type_.Name()
		serializer := newStructSerializer(type_, tag, nil)
		r.typeToSerializers[type_] = serializer
		r.typeToTypeInfo[type_] = "@" + tag
		r.typeInfoToType["@"+tag] = type_

		// Create pointer serializer
		ptrType := reflect.PtrTo(type_)
		ptrSerializer := &ptrToValueSerializer{
			valueSerializer: serializer,
		}
		r.typeToSerializers[ptrType] = ptrSerializer
		r.typeTagToSerializers[tag] = ptrSerializer
		r.typeToTypeInfo[ptrType] = "*@" + tag
		r.typeInfoToType["*@"+tag] = ptrType

		// Register value type with fullTypeID
		_, err := r.registerType(type_, fullTypeID, "", "", serializer, false)
		if err != nil {
			return fmt.Errorf("failed to register type by ID: %w", err)
		}

		// Register pointer type with same fullTypeID (Java treats value and pointer types the same)
		_, err = r.registerType(ptrType, fullTypeID, "", "", ptrSerializer, false)
		if err != nil {
			return fmt.Errorf("failed to register pointer type by ID: %w", err)
		}

	default:
		return fmt.Errorf("unsupported type for ID registration: %v (use RegisterEnumByID for enum types)", type_.Kind())
	}

	return nil
}

// RegisterEnumByID registers an enum type (numeric type in Go) with a full type ID
func (r *TypeResolver) RegisterEnumByID(type_ reflect.Type, fullTypeID uint32) error {
	// Check if already registered
	if info, ok := r.typeIDToTypeInfo[fullTypeID]; ok {
		return fmt.Errorf("type %s with id %d has been registered", info.Type, fullTypeID)
	}

	// Verify it's a numeric type
	switch type_.Kind() {
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64,
		reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64:
		// OK
	default:
		return fmt.Errorf("RegisterEnumByID only supports numeric types; got: %v", type_.Kind())
	}

	// Create enum serializer
	serializer := &enumSerializer{type_: type_, typeID: fullTypeID}
	tag := type_.Name()

	r.typeToSerializers[type_] = serializer
	r.typeToTypeInfo[type_] = "@" + tag
	r.typeInfoToType["@"+tag] = type_

	// Create TypeInfo with serializer
	typeInfo := &TypeInfo{
		Type:       type_,
		TypeID:     fullTypeID,
		Serializer: serializer,
		IsDynamic:  isDynamicType(type_),
		StaticId:   GetStaticTypeId(type_),
		hashValue:  calcTypeHash(type_),
	}
	r.typeIDToTypeInfo[fullTypeID] = typeInfo
	r.typesInfo[type_] = typeInfo

	return nil
}

// RegisterEnumByName registers an enum type (numeric type in Go) with a namespace and type name
func (r *TypeResolver) RegisterEnumByName(type_ reflect.Type, namespace, typeName string) error {
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
		return fmt.Errorf("RegisterEnumByName only supports numeric types; got: %v", type_.Kind())
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
	_, err := r.registerType(type_, typeId, namespace, typeName, serializer, false)
	if err != nil {
		return fmt.Errorf("failed to register enum by name: %w", err)
	}

	return nil
}

func (r *TypeResolver) RegisterNamedType(
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
	var tag string
	if namespace == "" {
		tag = typeName
	} else {
		tag = namespace + "." + typeName
	}
	serializer := newStructSerializer(type_, tag, nil)
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
	if typeId == 0 {
		if r.metaShareEnabled() {
			typeId = (typeId << 8) | NAMED_COMPATIBLE_STRUCT
		} else {
			typeId = (typeId << 8) | NAMED_STRUCT
		}
	} else {
		if r.metaShareEnabled() {
			typeId = (typeId << 8) | COMPATIBLE_STRUCT
		} else {
			typeId = (typeId << 8) | STRUCT
		}
	}
	if registerById {
		if info, ok := r.typeIDToTypeInfo[typeId]; ok {
			return fmt.Errorf("type %s with id %d has been registered", info.Type, typeId)
		}
	}
	// For named structs, directly register both their value and pointer types with same typeId
	_, err := r.registerType(type_, typeId, namespace, typeName, nil, false)
	if err != nil {
		return fmt.Errorf("failed to register named structs: %w", err)
	}
	_, err = r.registerType(ptrType, typeId, namespace, typeName, nil, false)
	if err != nil {
		return fmt.Errorf("failed to register named structs: %w", err)
	}
	return nil
}

func (r *TypeResolver) RegisterExt(extId int16, type_ reflect.Type) error {
	// Registering type is necessary, otherwise we may don't have the symbols of corresponding type when deserializing.
	panic("not supported")
}

// RegisterExtensionType registers a type as an extension type (NAMED_EXT).
// Extension types use a user-provided serializer for custom serialization logic.
// This is used for types with custom serializers in cross-language serialization.
func (r *TypeResolver) RegisterExtensionType(
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

	// Use NAMED_STRUCT type ID to match Java's behavior
	// (Java uses NAMED_STRUCT when register() + registerSerializer() are used)
	typeId := uint32(NAMED_STRUCT)

	// Register both value and pointer types
	_, err := r.registerType(type_, typeId, namespace, typeName, nil, false)
	if err != nil {
		return fmt.Errorf("failed to register extension type: %w", err)
	}
	_, err = r.registerType(ptrType, typeId, namespace, typeName, nil, false)
	if err != nil {
		return fmt.Errorf("failed to register extension type: %w", err)
	}
	return nil
}

// RegisterExtensionTypeByID registers a type as an extension type with a numeric ID.
func (r *TypeResolver) RegisterExtensionTypeByID(
	type_ reflect.Type,
	userTypeID uint32,
	userSerializer ExtensionSerializer,
) error {
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

	// Use EXT type ID to match Java's behavior for extension types
	fullTypeID := (userTypeID << 8) | uint32(EXT)

	// Register type info for both value and pointer types
	typeInfo := &TypeInfo{
		Type:       type_,
		TypeID:     fullTypeID,
		Serializer: serializer,
	}
	r.typeIDToTypeInfo[fullTypeID] = typeInfo
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
	if info, ok := r.typesInfo[type_]; ok {
		return TypeId(info.TypeID & 0xFF) // Extract base type ID
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
	if t, ok := r.typeIdToType[int16(typeID)]; ok {
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

	// Early return if type registration is required but not allowed
	if !create {
		fmt.Errorf("type %v not registered and create=false", value.Type())
	}
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
				StaticId:      elemInfo.StaticId,
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
			if err := r.RegisterNamedType(elemType, 0, elemPkgPath, elemTypeName); err != nil {
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
	default:
		fmt.Errorf("type %v must be registered explicitly", type_)
	}

	/*
	   There are still some issues to address when adapting structs:
	   Named structs need both value and pointer types registered using the negative ID system
	   to assign the correct typeID.
	   Multidimensional slices should use typeID = 21 for recursive serialization; on
	   deserialization, users receive []interface{} and must apply conversion function.
	   Array types aren’t tracked separately in fory-go’s type system; semantically,
	   arrays reuse their corresponding slice serializer/deserializer. We serialize arrays
	   via their slice metadata and convert back to arrays by conversion function.
	   All other slice types are treated as lists (typeID 21).
	*/
	if value.Kind() == reflect.Struct {
		typeID = NAMED_STRUCT
	} else if value.IsValid() && value.Kind() == reflect.Interface && value.Elem().Kind() == reflect.Struct {
		typeID = NAMED_STRUCT
	} else if value.IsValid() && value.Kind() == reflect.Ptr && value.Elem().Kind() == reflect.Struct {
		typeID = NAMED_STRUCT
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
	if internal && serializer != nil {
		if err := r.registerSerializer(type_, TypeId(typeID&0xFF), serializer); err != nil {
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
		Serializer:   serializer,
		PkgPathBytes: nsBytes,   // Encoded namespace bytes
		NameBytes:    typeBytes, // Encoded type name bytes
		IsDynamic:    isDynamicType(type_),
		StaticId:     GetStaticTypeId(type_), // Static type ID for fast path
		hashValue:    calcTypeHash(type_),    // Precomputed hash for fast lookups
		NeedWriteRef: NeedWriteRef(TypeId(typeID)),
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
	if r.isXlang && !IsNamespacedType(TypeId(typeID)) {
		/*
		   This function is required to maintain the typeID registry: all types
		   are registered at startup, and we keep this table updated.
		   We only insert into this map if the entry does not already exist
		   to avoid overwriting correct entries.
		   After removing allocate ID, for map[x]y cases we uniformly use
		   the serializer for typeID 23.
		   Overwriting here would replace info.Type with incorrect data,
		   causing map deserialization to load the wrong type.
		   Therefore, we always keep the initial record for map[interface{}]interface{}.
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

// WriteTypeInfo writes type info to buffer.
// This is exported for use by generated code.
func (r *TypeResolver) WriteTypeInfo(buffer *ByteBuffer, typeInfo *TypeInfo, err *Error) {
	if typeInfo == nil {
		return
	}
	// Extract the internal type ID (lower 8 bits)
	typeID := typeInfo.TypeID
	internalTypeID := TypeId(typeID & 0xFF)
	// WriteData the type ID to buffer using Varuint32Small7 encoding (matches Java)
	buffer.WriteVaruint32Small7(typeID)

	// Handle type meta based on internal type ID (matching Java XtypeResolver.writeClassInfo)
	switch internalTypeID {
	case NAMED_ENUM, NAMED_STRUCT, NAMED_EXT:
		if r.metaShareEnabled() {
			r.writeSharedTypeMeta(buffer, typeInfo, err)
			return
		}
		// WriteData package path (namespace) metadata
		r.metaStringResolver.WriteMetaStringBytes(buffer, typeInfo.PkgPathBytes, err)
		// WriteData type name metadata
		r.metaStringResolver.WriteMetaStringBytes(buffer, typeInfo.NameBytes, err)
	case NAMED_COMPATIBLE_STRUCT, COMPATIBLE_STRUCT:
		// Meta share must be enabled for compatible mode
		if r.metaShareEnabled() {
			r.writeSharedTypeMeta(buffer, typeInfo, err)
		}
	}
}

func (r *TypeResolver) writeSharedTypeMeta(buffer *ByteBuffer, typeInfo *TypeInfo, err *Error) {
	context := r.fory.MetaContext()
	typ := typeInfo.Type

	if index, exists := context.typeMap[typ]; exists {
		buffer.WriteVaruint32(index)
		return
	}

	newIndex := uint32(len(context.typeMap))
	buffer.WriteVaruint32(newIndex)
	context.typeMap[typ] = newIndex

	// Only build TypeDef for struct types - enums don't have field definitions
	actualType := typ
	if actualType.Kind() == reflect.Ptr {
		actualType = actualType.Elem()
	}
	if actualType.Kind() == reflect.Struct {
		typeDef, typeDefErr := r.getTypeDef(typeInfo.Type, true)
		if typeDefErr != nil {
			err.SetError(typeDefErr)
			return
		}
		context.writingTypeDefs = append(context.writingTypeDefs, typeDef)
	}
}

func (r *TypeResolver) getTypeDef(typ reflect.Type, create bool) (*TypeDef, error) {
	if existingTypeDef, exists := r.typeToTypeDef[typ]; exists {
		return existingTypeDef, nil
	}

	if !create {
		return nil, fmt.Errorf("TypeDef not found for type %s", typ)
	}

	// don't create TypeDef for pointer types, we create TypeDef for its element type instead.
	if typ.Kind() == reflect.Ptr {
		typ = typ.Elem()
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
	index := int32(buffer.ReadVaruint32(err)) // shared meta index id (unsigned)
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

func (r *TypeResolver) writeTypeDefs(buffer *ByteBuffer, err *Error) {
	context := r.fory.MetaContext()
	if context == nil {
		buffer.WriteVaruint32Small7(0)
		return
	}
	sz := len(context.writingTypeDefs)
	buffer.WriteVaruint32Small7(uint32(sz))
	for _, typeDef := range context.writingTypeDefs {
		typeDef.writeTypeDef(buffer, err)
	}
	context.writingTypeDefs = nil
}

func (r *TypeResolver) readTypeDefs(buffer *ByteBuffer, err *Error) {
	numTypeDefs := int(buffer.ReadVaruint32Small7(err))
	if numTypeDefs == 0 {
		return
	}
	context := r.fory.MetaContext()
	if context == nil {
		err.SetError(fmt.Errorf("MetaContext is nil but type definitions are present"))
		return
	}
	for i := 0; i < numTypeDefs; i++ {
		id := buffer.ReadInt64(err)
		var td *TypeDef
		if existingTd, exists := r.defIdToTypeDef[id]; exists {
			skipTypeDef(buffer, id, err)
			td = existingTd
		} else {
			newTd := readTypeDef(r.fory, buffer, id, err)
			r.defIdToTypeDef[id] = newTd
			td = newTd
			// Note: We do NOT store remote TypeDef in typeToTypeDef.
			// typeToTypeDef is used for WRITING and must contain locally-built TypeDefs.
			// Remote TypeDefs have different field ordering/IDs based on the remote's struct.
			// defIdToTypeDef caches remote TypeDefs by header hash to avoid re-parsing.
		}
		typeInfo, typeInfoErr := td.buildTypeInfoWithResolver(r)
		if typeInfoErr != nil {
			err.SetError(typeInfoErr)
			return
		}
		context.readTypeInfos = append(context.readTypeInfos, &typeInfo)
		// Note: We intentionally do NOT update the original serializer's fieldDefs here.
		// When serializing, Go should use its own struct definition (via initFieldsFromContext),
		// not the remote TypeDef's field list. This is important for schema evolution
		// where Go's struct may have different fields than the remote.
	}
}

func (r *TypeResolver) createSerializer(type_ reflect.Type, mapInStruct bool) (s Serializer, err error) {
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
		hasKeySerializer, hasValueSerializer := !isDynamicType(type_.Key()), !isDynamicType(type_.Elem())
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
			// Determine key/value referencability using isRefType which handles xlang mode
			keyReferencable := isRefType(type_.Key(), r.isXlang)
			valueReferencable := isRefType(type_.Elem(), r.isXlang)
			return &mapSerializer{
				type_:             type_,
				keySerializer:     keySerializer,
				valueSerializer:   valueSerializer,
				keyReferencable:   keyReferencable,
				valueReferencable: valueReferencable,
				hasGenerics:       mapInStruct,
			}, nil
		} else {
			return mapSerializer{hasGenerics: mapInStruct}, nil
		}
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
				if err := r.RegisterNamedType(type_, 0, pkgPath, typeName); err != nil {
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

// GetSetSerializer returns the setSerializer for a map[T]bool type (used to represent sets in Go).
func (r *TypeResolver) GetSetSerializer(setType reflect.Type) (Serializer, error) {
	if setType.Kind() != reflect.Map {
		return nil, fmt.Errorf("expected map type but got %s", setType.Kind())
	}
	if setType.Elem().Kind() != reflect.Bool {
		return nil, fmt.Errorf("expected map[T]bool for set but got map[%s]%s", setType.Key(), setType.Elem())
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

// ReadTypeInfo reads type info from buffer and returns it.
// This is exported for use by generated code.
func (r *TypeResolver) ReadTypeInfo(buffer *ByteBuffer, err *Error) *TypeInfo {
	// ReadData variable-length type ID using Varuint32Small7 encoding (matches Java)
	typeID := buffer.ReadVaruint32Small7(err)
	internalTypeID := TypeId(typeID & 0xFF)

	// Handle type meta based on internal type ID (matching Java XtypeResolver.readClassInfo)
	switch internalTypeID {
	case NAMED_ENUM, NAMED_STRUCT, NAMED_EXT:
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
		if typeInfo, exists := r.nsTypeToTypeInfo[compositeKey]; exists {
			return typeInfo
		}

		// If not found, decode the bytes to strings and try again
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
		// Type not found
		fullName := typeName
		if ns != "" {
			fullName = ns + "." + typeName
		}
		err.SetError(fmt.Errorf("unregistered type: %s (typeID: %d)", fullName, typeID))
		return nil

	case NAMED_COMPATIBLE_STRUCT, COMPATIBLE_STRUCT:
		// Meta share must be enabled for compatible mode
		if r.metaShareEnabled() {
			return r.readSharedTypeMeta(buffer, err)
		}
	}

	// Handle simple type IDs (non-namespaced types)
	if typeInfo, exists := r.typeIDToTypeInfo[typeID]; exists {
		return typeInfo
	}

	// Handle collection types (LIST, SET, MAP) that don't have specific registration
	// Use generic types that can hold any element type
	switch TypeId(typeID) {
	case LIST, -LIST:
		return &TypeInfo{
			Type:       interfaceSliceType,
			TypeID:     typeID,
			Serializer: r.typeToSerializers[interfaceSliceType],
			StaticId:   ConcreteTypeOther,
		}
	case SET, -SET:
		return &TypeInfo{
			Type:       genericSetType,
			TypeID:     typeID,
			Serializer: r.typeToSerializers[genericSetType],
			StaticId:   ConcreteTypeOther,
		}
	case MAP, -MAP:
		return &TypeInfo{
			Type:       interfaceMapType,
			TypeID:     typeID,
			Serializer: r.typeToSerializers[interfaceMapType],
			StaticId:   ConcreteTypeOther,
		}
	case BOOL:
		return &TypeInfo{
			Type:       reflect.TypeOf(false),
			TypeID:     typeID,
			Serializer: r.typeToSerializers[reflect.TypeOf(false)],
			StaticId:   ConcreteTypeBool,
		}
	case INT8:
		return &TypeInfo{
			Type:       reflect.TypeOf(int8(0)),
			TypeID:     typeID,
			Serializer: r.typeToSerializers[reflect.TypeOf(int8(0))],
			StaticId:   ConcreteTypeInt8,
		}
	case UINT8:
		return &TypeInfo{
			Type:       reflect.TypeOf(uint8(0)),
			TypeID:     typeID,
			Serializer: r.typeToSerializers[reflect.TypeOf(uint8(0))],
			StaticId:   ConcreteTypeInt8, // Use Int8 static ID for uint8
		}
	case INT16:
		return &TypeInfo{
			Type:       reflect.TypeOf(int16(0)),
			TypeID:     typeID,
			Serializer: r.typeToSerializers[reflect.TypeOf(int16(0))],
			StaticId:   ConcreteTypeInt16,
		}
	case UINT16:
		return &TypeInfo{
			Type:       reflect.TypeOf(uint16(0)),
			TypeID:     typeID,
			Serializer: r.typeToSerializers[reflect.TypeOf(uint16(0))],
			StaticId:   ConcreteTypeInt16, // Use Int16 static ID for uint16
		}
	case INT32, VAR_INT32:
		return &TypeInfo{
			Type:       reflect.TypeOf(int32(0)),
			TypeID:     typeID,
			Serializer: r.typeToSerializers[reflect.TypeOf(int32(0))],
			StaticId:   ConcreteTypeInt32,
		}
	case UINT32:
		return &TypeInfo{
			Type:       reflect.TypeOf(uint32(0)),
			TypeID:     typeID,
			Serializer: r.typeToSerializers[reflect.TypeOf(uint32(0))],
			StaticId:   ConcreteTypeInt32, // Use Int32 static ID for uint32
		}
	case INT64, VAR_INT64, SLI_INT64:
		return &TypeInfo{
			Type:       reflect.TypeOf(int64(0)),
			TypeID:     typeID,
			Serializer: r.typeToSerializers[reflect.TypeOf(int64(0))],
			StaticId:   ConcreteTypeInt64,
		}
	case UINT64:
		return &TypeInfo{
			Type:       reflect.TypeOf(uint64(0)),
			TypeID:     typeID,
			Serializer: r.typeToSerializers[reflect.TypeOf(uint64(0))],
			StaticId:   ConcreteTypeInt64, // Use Int64 static ID for uint64
		}
	case FLOAT:
		return &TypeInfo{
			Type:       reflect.TypeOf(float32(0)),
			TypeID:     typeID,
			Serializer: r.typeToSerializers[reflect.TypeOf(float32(0))],
			StaticId:   ConcreteTypeFloat32,
		}
	case DOUBLE:
		return &TypeInfo{
			Type:       reflect.TypeOf(float64(0)),
			TypeID:     typeID,
			Serializer: r.typeToSerializers[reflect.TypeOf(float64(0))],
			StaticId:   ConcreteTypeFloat64,
		}
	case STRING:
		return &TypeInfo{
			Type:       reflect.TypeOf(""),
			TypeID:     typeID,
			Serializer: r.typeToSerializers[reflect.TypeOf("")],
			StaticId:   ConcreteTypeString,
		}
	case BINARY:
		return &TypeInfo{
			Type:       reflect.TypeOf([]byte(nil)),
			TypeID:     typeID,
			Serializer: r.typeToSerializers[reflect.TypeOf([]byte(nil))],
			StaticId:   ConcreteTypeOther,
		}
	}

	err.SetError(fmt.Errorf("unknown type id: %d", typeID))
	return nil
}

// readTypeInfoWithTypeID reads type info when the typeID has already been read from buffer.
// This is used by collection serializers that read typeID separately before deciding how to proceed.
func (r *TypeResolver) readTypeInfoWithTypeID(buffer *ByteBuffer, typeID uint32, err *Error) *TypeInfo {
	internalTypeID := TypeId(typeID & 0xFF)

	if IsNamespacedType(TypeId(typeID)) {
		if r.metaShareEnabled() {
			return r.readSharedTypeMeta(buffer, err)
		}
		// ReadData namespace and type name metadata bytes
		nsBytes, _ := r.metaStringResolver.ReadMetaStringBytes(buffer, err)
		typeBytes, _ := r.metaStringResolver.ReadMetaStringBytes(buffer, err)

		compositeKey := nsTypeKey{nsBytes.Hashcode, typeBytes.Hashcode}
		if typeInfo, exists := r.nsTypeToTypeInfo[compositeKey]; exists {
			return typeInfo
		}

		// If not found, decode the bytes to strings and try again
		ns, nsErr := r.namespaceDecoder.Decode(nsBytes.Data, nsBytes.Encoding)
		if nsErr != nil {
			err.SetError(fmt.Errorf("namespace decode failed: %w", nsErr))
			return nil
		}

		typeName, tnErr := r.typeNameDecoder.Decode(typeBytes.Data, typeBytes.Encoding)
		if tnErr != nil {
			err.SetError(fmt.Errorf("typename decode failed: %w", tnErr))
			return nil
		}

		nameKey := [2]string{ns, typeName}
		if typeInfo, exists := r.namedTypeToTypeInfo[nameKey]; exists {
			r.nsTypeToTypeInfo[compositeKey] = typeInfo
			return typeInfo
		}
		err.SetError(fmt.Errorf("namespaced type not found: %s.%s", ns, typeName))
		return nil
	}

	// Handle COMPATIBLE_STRUCT and STRUCT types - they also need to read shared type meta
	if (internalTypeID == COMPATIBLE_STRUCT || internalTypeID == STRUCT) && r.metaShareEnabled() {
		return r.readSharedTypeMeta(buffer, err)
	}

	// Handle simple type IDs (non-namespaced types)
	if typeInfo, exists := r.typeIDToTypeInfo[typeID]; exists {
		return typeInfo
	}

	// Handle collection types (LIST, SET, MAP) that don't have specific registration
	// Use generic types that can hold any element type
	switch TypeId(typeID) {
	case LIST:
		return &TypeInfo{
			Type:       interfaceSliceType,
			TypeID:     typeID,
			Serializer: r.typeToSerializers[interfaceSliceType],
			StaticId:   ConcreteTypeOther,
		}
	case SET:
		return &TypeInfo{
			Type:       genericSetType,
			TypeID:     typeID,
			Serializer: r.typeToSerializers[genericSetType],
			StaticId:   ConcreteTypeOther,
		}
	case MAP:
		return &TypeInfo{
			Type:       interfaceMapType,
			TypeID:     typeID,
			Serializer: r.typeToSerializers[interfaceMapType],
			StaticId:   ConcreteTypeOther,
		}
	// Handle primitive types that may not be explicitly registered
	case BOOL:
		return &TypeInfo{Type: boolType, TypeID: typeID, Serializer: r.typeToSerializers[boolType], StaticId: ConcreteTypeBool}
	case INT8:
		return &TypeInfo{Type: int8Type, TypeID: typeID, Serializer: r.typeToSerializers[int8Type], StaticId: ConcreteTypeInt8}
	case INT16:
		return &TypeInfo{Type: int16Type, TypeID: typeID, Serializer: r.typeToSerializers[int16Type], StaticId: ConcreteTypeInt16}
	case INT32, VAR_INT32:
		return &TypeInfo{Type: int32Type, TypeID: typeID, Serializer: r.typeToSerializers[int32Type], StaticId: ConcreteTypeInt32}
	case INT64, VAR_INT64, SLI_INT64:
		return &TypeInfo{Type: int64Type, TypeID: typeID, Serializer: r.typeToSerializers[int64Type], StaticId: ConcreteTypeInt64}
	case FLOAT:
		return &TypeInfo{Type: float32Type, TypeID: typeID, Serializer: r.typeToSerializers[float32Type], StaticId: ConcreteTypeFloat32}
	case DOUBLE:
		return &TypeInfo{Type: float64Type, TypeID: typeID, Serializer: r.typeToSerializers[float64Type], StaticId: ConcreteTypeFloat64}
	case STRING:
		return &TypeInfo{Type: stringType, TypeID: typeID, Serializer: r.typeToSerializers[stringType], StaticId: ConcreteTypeString}
	case BINARY:
		return &TypeInfo{Type: byteSliceType, TypeID: typeID, Serializer: r.typeToSerializers[byteSliceType], StaticId: ConcreteTypeByteSlice}
	}

	// Handle UNKNOWN type (0) - used for polymorphic types
	if typeID == 0 {
		return &TypeInfo{
			Type:     interfaceType,
			TypeID:   typeID,
			StaticId: ConcreteTypeOther,
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
	typeID := buffer.ReadVaruint32Small7(err)
	internalTypeID := TypeId(typeID & 0xFF)

	switch internalTypeID {
	case STRUCT, NAMED_STRUCT:
		// Non-compatible mode: skip namespace/typename meta strings if present
		if IsNamespacedType(TypeId(typeID)) {
			// Skip namespace meta string
			r.metaStringResolver.ReadMetaStringBytes(buffer, err)
			// Skip typename meta string
			r.metaStringResolver.ReadMetaStringBytes(buffer, err)
		}
		// Get serializer directly by the expected type - no map lookup needed
		return r.typeToSerializers[expectedType]

	case COMPATIBLE_STRUCT, NAMED_COMPATIBLE_STRUCT:
		// Compatible mode: read type def from shared meta
		if r.metaShareEnabled() {
			typeInfo := r.readSharedTypeMeta(buffer, err)
			if err.HasError() {
				return nil
			}
			return typeInfo.Serializer
		}
		// Fallback: skip namespace/typename and use expected type's serializer
		if IsNamespacedType(TypeId(typeID)) {
			r.metaStringResolver.ReadMetaStringBytes(buffer, err)
			r.metaStringResolver.ReadMetaStringBytes(buffer, err)
		}
		return r.typeToSerializers[expectedType]
	default:
		// For other types, return nil - caller should handle
		return nil
	}
}

func (r *TypeResolver) getTypeById(id int16) (reflect.Type, error) {
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

func (r *TypeResolver) writeMetaString(buffer *ByteBuffer, str string, err *Error) {
	if id, ok := r.dynamicStringToId[str]; !ok {
		dynamicStringId := r.dynamicStringId
		r.dynamicStringId += 1
		r.dynamicStringToId[str] = dynamicStringId
		length := len(str)
		buffer.WriteVaruint32(uint32(length << 1))
		if length <= SMALL_STRING_THRESHOLD {
			buffer.WriteByte_(uint8(meta.UTF_8))
		} else {
			// TODO this hash should be unique, since we don't compare data equality for performance
			h := fnv.New64a()
			if _, hashErr := h.Write([]byte(str)); hashErr != nil {
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
		buffer.WriteBinary(unsafeGetBytes(str))
	} else {
		buffer.WriteVaruint32(uint32(((id + 1) << 1) | 1))
	}
}

func (r *TypeResolver) readMetaString(buffer *ByteBuffer, err *Error) string {
	header := buffer.ReadVaruint32(err)
	var length = int(header >> 1)
	if header&0b1 == 0 {
		if length <= SMALL_STRING_THRESHOLD {
			buffer.ReadByte(err)
		} else {
			// TODO support use computed hash
			buffer.ReadInt64(err)
		}
		str := string(buffer.ReadBinary(length, err))
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
	typeMap               map[reflect.Type]uint32
	writingTypeDefs       []*TypeDef
	readTypeInfos         []*TypeInfo
	scopedMetaShareEnable bool
}

// IsScopedMetaShareEnabled returns whether scoped meta share is enabled
func (m *MetaContext) IsScopedMetaShareEnabled() bool {
	return m.scopedMetaShareEnable
}

// Reset clears the meta context for reuse
func (m *MetaContext) Reset() {
	m.typeMap = make(map[reflect.Type]uint32)
	m.writingTypeDefs = nil
	m.readTypeInfos = nil
}

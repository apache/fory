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
	"hash/fnv"
	"reflect"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/apache/fory/go/fory/meta"
)

type TypeId = int16

const (
	// NA A NullFlag type having no physical storage
	NA TypeId = iota // NA = 0
	// BOOL Boolean as 1 bit LSB bit-packed ordering
	BOOL = 1
	// INT8 Signed 8-bit little-endian integer
	INT8 = 2
	// INT16 Signed 16-bit little-endian integer
	INT16 = 3
	// INT32 Signed 32-bit little-endian integer
	INT32 = 4
	// VAR_INT32 a 32-bit signed integer which uses fory var_int32 encoding
	VAR_INT32 = 5
	// INT64 Signed 64-bit little-endian integer
	INT64 = 6
	// VAR_INT64 a 64-bit signed integer which uses fory PVL encoding
	VAR_INT64 = 7
	// SLI_INT64 a 64-bit signed integer which uses fory SLI encoding
	SLI_INT64 = 8
	// HALF_FLOAT 2-byte floating point value
	HALF_FLOAT = 9
	// FLOAT 4-byte floating point value
	FLOAT = 10
	// DOUBLE 8-byte floating point value
	DOUBLE = 11
	// STRING UTF8 variable-length string as List<Char>
	STRING = 12
	// ENUM a data type consisting of a set of named values
	ENUM = 13
	// NAMED_ENUM an enum whose value will be serialized as the registered name
	NAMED_ENUM = 14
	// STRUCT a morphic(final) type serialized by Fory Struct serializer
	STRUCT = 15
	// COMPATIBLE_STRUCT a morphic(final) type serialized by Fory compatible Struct serializer
	COMPATIBLE_STRUCT = 16
	// NAMED_STRUCT a struct whose type mapping will be encoded as a name
	NAMED_STRUCT = 17
	// NAMED_COMPATIBLE_STRUCT a compatible_struct whose type mapping will be encoded as a name
	NAMED_COMPATIBLE_STRUCT = 18
	// EXT a type which will be serialized by a customized serializer
	EXT = 19
	// NAMED_EXT an ext type whose type mapping will be encoded as a name
	NAMED_EXT = 20
	// LIST A list of some logical data type
	LIST = 21
	// SET an unordered set of unique elements
	SET = 22
	// MAP Map a repeated struct logical type
	MAP = 23
	// DURATION Measure of elapsed time in either seconds milliseconds microseconds
	DURATION = 24
	// TIMESTAMP Exact timestamp encoded with int64 since UNIX epoch
	TIMESTAMP = 25
	// LOCAL_DATE a naive date without timezone
	LOCAL_DATE = 26
	// DECIMAL128 Precision- and scale-based decimal type with 128 bits.
	DECIMAL128 = 27
	// BINARY Variable-length bytes (no guarantee of UTF8-ness)
	BINARY = 28
	// ARRAY a multidimensional array which every sub-array can have different sizes but all have the same type
	ARRAY = 29
	// BOOL_ARRAY one dimensional bool array
	BOOL_ARRAY = 30
	// INT8_ARRAY one dimensional int8 array
	INT8_ARRAY = 31
	// INT16_ARRAY one dimensional int16 array
	INT16_ARRAY = 32
	// INT32_ARRAY one dimensional int32 array
	INT32_ARRAY = 33
	// INT64_ARRAY one dimensional int64 array
	INT64_ARRAY = 34
	// FLOAT16_ARRAY one dimensional half_float_16 array
	FLOAT16_ARRAY = 35
	// FLOAT32_ARRAY one dimensional float32 array
	FLOAT32_ARRAY = 36
	// FLOAT64_ARRAY one dimensional float64 array
	FLOAT64_ARRAY = 37
	// ARROW_RECORD_BATCH an arrow record batch object
	ARROW_RECORD_BATCH = 38
	// ARROW_TABLE an arrow table object
	ARROW_TABLE = 39

	// UINT8 Unsigned 8-bit little-endian integer
	UINT8 = 100 // Not in mapping table, assign a higher value
	// UINT16 Unsigned 16-bit little-endian integer
	UINT16 = 101
	// UINT32 Unsigned 32-bit little-endian integer
	UINT32 = 102
	// UINT64 Unsigned 64-bit little-endian integer
	UINT64 = 103
	// FIXED_SIZE_BINARY Fixed-size binary. Each value occupies the same number of bytes
	FIXED_SIZE_BINARY = 104
	// DATE32 int32_t days since the UNIX epoch
	DATE32 = 105
	// DATE64 int64_t milliseconds since the UNIX epoch
	DATE64 = 106
	// TIME32 Time as signed 32-bit integer representing either seconds or milliseconds since midnight
	TIME32 = 107
	// TIME64 Time as signed 64-bit integer representing either microseconds or nanoseconds since midnight
	TIME64 = 108
	// INTERVAL_MONTHS YEAR_MONTH interval in SQL style
	INTERVAL_MONTHS = 109
	// INTERVAL_DAY_TIME DAY_TIME interval in SQL style
	INTERVAL_DAY_TIME = 110
	// DECIMAL256 Precision- and scale-based decimal type with 256 bits.
	DECIMAL256 = 111
	// SPARSE_UNION Sparse unions of logical types
	SPARSE_UNION = 112
	// DENSE_UNION Dense unions of logical types
	DENSE_UNION = 113
	// DICTIONARY Dictionary-encoded type also called "categorical" or "factor"
	DICTIONARY = 114
	// FIXED_SIZE_LIST Fixed size list of some logical type
	FIXED_SIZE_LIST = 115
	// LARGE_STRING Like STRING but with 64-bit offsets
	LARGE_STRING = 116
	// LARGE_BINARY Like BINARY but with 64-bit offsets
	LARGE_BINARY = 117
	// LARGE_LIST Like LIST but with 64-bit offsets
	LARGE_LIST = 118
	// MAX_ID Leave this at the end
	MAX_ID = 119

	DECIMAL = DECIMAL128

	// Fory added type for cross-language serialization.
	// FORY_TYPE_TAG for type identified by the tag
	FORY_TYPE_TAG               = 256
	FORY_SET                    = 257
	FORY_PRIMITIVE_BOOL_ARRAY   = 258
	FORY_PRIMITIVE_SHORT_ARRAY  = 259
	FORY_PRIMITIVE_INT_ARRAY    = 260
	FORY_PRIMITIVE_LONG_ARRAY   = 261
	FORY_PRIMITIVE_FLOAT_ARRAY  = 262
	FORY_PRIMITIVE_DOUBLE_ARRAY = 263
	FORY_STRING_ARRAY           = 264
	FORY_SERIALIZED_OBJECT      = 265
	FORY_BUFFER                 = 266
	FORY_ARROW_RECORD_BATCH     = 267
	FORY_ARROW_TABLE            = 268
)

var namedTypes = map[TypeId]struct{}{
	FORY_TYPE_TAG:           {},
	NAMED_EXT:               {},
	NAMED_ENUM:              {},
	NAMED_STRUCT:            {},
	NAMED_COMPATIBLE_STRUCT: {},
}

// IsNamespacedType checks whether the given type ID is a namespace type
func IsNamespacedType(typeID TypeId) bool {
	typeID = typeID & 0xFF
	_, exists := namedTypes[typeID]
	return exists
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
	stringSliceType    = reflect.TypeOf((*[]string)(nil)).Elem()
	byteSliceType      = reflect.TypeOf((*[]byte)(nil)).Elem()
	boolSliceType      = reflect.TypeOf((*[]bool)(nil)).Elem()
	int8SliceType      = reflect.TypeOf((*[]int8)(nil)).Elem()
	int16SliceType     = reflect.TypeOf((*[]int16)(nil)).Elem()
	int32SliceType     = reflect.TypeOf((*[]int32)(nil)).Elem()
	int64SliceType     = reflect.TypeOf((*[]int64)(nil)).Elem()
	float32SliceType   = reflect.TypeOf((*[]float32)(nil)).Elem()
	float64SliceType   = reflect.TypeOf((*[]float64)(nil)).Elem()
	interfaceSliceType = reflect.TypeOf((*[]interface{})(nil)).Elem()
	interfaceMapType   = reflect.TypeOf((*map[interface{}]interface{})(nil)).Elem()
	boolType           = reflect.TypeOf((*bool)(nil)).Elem()
	byteType           = reflect.TypeOf((*byte)(nil)).Elem()
	int8Type           = reflect.TypeOf((*int8)(nil)).Elem()
	int16Type          = reflect.TypeOf((*int16)(nil)).Elem()
	int32Type          = reflect.TypeOf((*int32)(nil)).Elem()
	int64Type          = reflect.TypeOf((*int64)(nil)).Elem()
	intType            = reflect.TypeOf((*int)(nil)).Elem()
	float32Type        = reflect.TypeOf((*float32)(nil)).Elem()
	float64Type        = reflect.TypeOf((*float64)(nil)).Elem()
	dateType           = reflect.TypeOf((*Date)(nil)).Elem()
	timestampType      = reflect.TypeOf((*time.Time)(nil)).Elem()
	genericSetType     = reflect.TypeOf((*GenericSet)(nil)).Elem()
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
	TypeID        int32
	LocalID       int16
	Serializer    Serializer
	NeedWriteDef  bool
	hashValue     uint64
}
type (
	namedTypeKey [2]string
)

type nsTypeKey struct {
	Namespace int64
	TypeName  int64
}

type typeResolver struct {
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
	language            Language
	metaStringResolver  *MetaStringResolver
	requireRegistration bool

	// String mappings
	metaStrToStr     map[string]string
	metaStrToClass   map[string]reflect.Type
	hashToMetaString map[uint64]string
	hashToClassInfo  map[uint64]TypeInfo

	// Type tracking
	dynamicWrittenMetaStr []string
	typeIDToTypeInfo      map[int32]TypeInfo
	typeIDCounter         int32
	dynamicWriteStringID  int32

	// Class registries
	typesInfo           map[reflect.Type]TypeInfo
	nsTypeToTypeInfo    map[nsTypeKey]TypeInfo
	namedTypeToTypeInfo map[namedTypeKey]TypeInfo

	// Encoders/Decoders
	namespaceEncoder *meta.Encoder
	namespaceDecoder *meta.Decoder
	typeNameEncoder  *meta.Encoder
	typeNameDecoder  *meta.Decoder

	// meta share related
	typeToTypeDef  map[reflect.Type]*TypeDef
	defIdToTypeDef map[int64]*TypeDef
}

func newTypeResolver(fory *Fory) *typeResolver {
	r := &typeResolver{
		typeTagToSerializers: map[string]Serializer{},
		typeToSerializers:    map[reflect.Type]Serializer{},
		typeIdToType:         map[int16]reflect.Type{},
		typeToTypeInfo:       map[reflect.Type]string{},
		typeInfoToType:       map[string]reflect.Type{},
		dynamicStringToId:    map[string]int16{},
		dynamicIdToString:    map[int16]string{},
		fory:                 fory,

		language:            fory.language,
		metaStringResolver:  NewMetaStringResolver(),
		requireRegistration: false,

		metaStrToStr:     make(map[string]string),
		metaStrToClass:   make(map[string]reflect.Type),
		hashToMetaString: make(map[uint64]string),
		hashToClassInfo:  make(map[uint64]TypeInfo),

		dynamicWrittenMetaStr: make([]string, 0),
		typeIDToTypeInfo:      make(map[int32]TypeInfo),
		typeIDCounter:         300,
		dynamicWriteStringID:  0,

		typesInfo:           make(map[reflect.Type]TypeInfo),
		nsTypeToTypeInfo:    make(map[nsTypeKey]TypeInfo),
		namedTypeToTypeInfo: make(map[namedTypeKey]TypeInfo),

		namespaceEncoder: meta.NewEncoder('.', '_'),
		namespaceDecoder: meta.NewDecoder('.', '_'),
		typeNameEncoder:  meta.NewEncoder('$', '_'),
		typeNameDecoder:  meta.NewDecoder('$', '_'),

		typeToTypeDef:  make(map[reflect.Type]*TypeDef),
		defIdToTypeDef: make(map[int64]*TypeDef),
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

		// Create structSerializer with codegen delegate (Python-style)
		structSer := &structSerializer{
			typeTag:         typeTag,
			type_:           type_,
			codegenDelegate: codegenSerializer, // Delegate to codegen for performance
		}
		// Create ptrToStructSerializer with codegen delegate (Python-style)
		ptrType := reflect.PtrTo(type_)
		ptrStructSer := &ptrToStructSerializer{
			type_:            ptrType,
			structSerializer: *structSer,
			codegenDelegate:  codegenSerializer, // Delegate to codegen for performance
		}

		// 1. Basic type mappings (same as reflection)
		r.typeToSerializers[type_] = structSer      // Value type -> structSerializer with codegen
		r.typeToSerializers[ptrType] = ptrStructSer // Pointer type -> ptrToStructSerializer with codegen

		// 2. Cross-language critical mapping (same as reflection)
		r.typeTagToSerializers[typeTag] = ptrStructSer // "pkg.Type" -> ptrToStructSerializer

		// 3. Register complete type information (critical for proper serialization)
		_, err := r.registerType(type_, int32(codegenSerializer.TypeId()), pkgPath, typeName, structSer, false)
		if err != nil {
			panic(fmt.Errorf("failed to register codegen type %s: %v", typeTag, err))
		}
		// 4. Register pointer type information
		_, err = r.registerType(ptrType, -int32(codegenSerializer.TypeId()), pkgPath, typeName, ptrStructSer, false)
		if err != nil {
			panic(fmt.Errorf("failed to register codegen pointer type %s: %v", typeTag, err))
		}

		// 5. Type info mappings (same as reflection) - these are redundant if registerType is called
		r.typeToTypeInfo[type_] = "@" + typeTag    // Type -> "@pkg.Type"
		r.typeToTypeInfo[ptrType] = "*@" + typeTag // *Type -> "*@pkg.Type"
		r.typeInfoToType["@"+typeTag] = type_      // "@pkg.Type" -> Type
		r.typeInfoToType["*@"+typeTag] = ptrType   // "*@pkg.Type" -> *Type
	}
	generatedSerializerFactories.mu.RUnlock()

	return r
}

func (r *typeResolver) initialize() {
	serializers := []struct {
		reflect.Type
		Serializer
	}{
		{stringType, stringSerializer{}},
		{stringPtrType, ptrToStringSerializer{}},
		{stringSliceType, stringSliceSerializer{}},
		{byteSliceType, byteSliceSerializer{}},
		// Map basic type slices to proper array types for xlang compatibility
		{boolSliceType, boolArraySerializer{}},
		{int8SliceType, int8ArraySerializer{}},
		{int16SliceType, int16ArraySerializer{}},
		{int32SliceType, int32ArraySerializer{}},
		{int64SliceType, int64ArraySerializer{}},
		{float32SliceType, float32ArraySerializer{}},
		{float64SliceType, float64ArraySerializer{}},
		{interfaceSliceType, sliceSerializer{}},
		{interfaceMapType, mapSerializer{}},
		{boolType, boolSerializer{}},
		{byteType, byteSerializer{}},
		{int8Type, int8Serializer{}},
		{int16Type, int16Serializer{}},
		{int32Type, int32Serializer{}},
		{int64Type, int64Serializer{}},
		{intType, intSerializer{}},
		{float32Type, float32Serializer{}},
		{float64Type, float64Serializer{}},
		{dateType, dateSerializer{}},
		{timestampType, timeSerializer{}},
		{genericSetType, setSerializer{}},
	}
	for _, elem := range serializers {
		_, err := r.registerType(elem.Type, int32(elem.Serializer.TypeId()), "", "", elem.Serializer, true)
		if err != nil {
			fmt.Errorf("init type error: %v", err)
		}
	}
}

func (r *typeResolver) RegisterSerializer(type_ reflect.Type, s Serializer) error {
	if prev, ok := r.typeToSerializers[type_]; ok {
		return fmt.Errorf("type %s already has a serializer %s registered", type_, prev)
	}
	r.typeToSerializers[type_] = s
	typeId := s.TypeId()
	if typeId != FORY_TYPE_TAG {
		if typeId > NotSupportCrossLanguage {
			if _, ok := r.typeIdToType[typeId]; ok {
				return fmt.Errorf("type %s with id %d has been registered", type_, typeId)
			}
			r.typeIdToType[typeId] = type_
		}
	}
	return nil
}

func (r *typeResolver) RegisterNamedType(
	type_ reflect.Type,
	typeId int32,
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
	serializer := &structSerializer{type_: type_, typeTag: tag}
	r.typeToSerializers[type_] = serializer
	// multiple struct with same name defined inside function will have same `type_.String()`, but they are
	// different types. so we use tag to encode type info.
	// tagged type encode as `@$tag`/`*@$tag`.
	r.typeToTypeInfo[type_] = "@" + tag
	r.typeInfoToType["@"+tag] = type_

	ptrType := reflect.PtrTo(type_)
	ptrSerializer := &ptrToStructSerializer{structSerializer: *serializer, type_: ptrType}
	r.typeToSerializers[ptrType] = ptrSerializer
	// use `ptrToStructSerializer` as default deserializer when deserializing data from other languages.
	r.typeTagToSerializers[tag] = ptrSerializer
	r.typeToTypeInfo[ptrType] = "*@" + tag
	r.typeInfoToType["*@"+tag] = ptrType
	if typeId == 0 {
		if r.metaShareEnabled() {
			typeId = (typeId << 8) + NAMED_COMPATIBLE_STRUCT
		} else {
			typeId = (typeId << 8) + NAMED_STRUCT
		}
	} else {
		if r.metaShareEnabled() {
			typeId = COMPATIBLE_STRUCT
		} else {
			typeId = STRUCT
		}
	}
	if registerById {
		if info, ok := r.typeIDToTypeInfo[typeId]; ok {
			return fmt.Errorf("type %s with id %d has been registered", info.Type, typeId)
		}
	}
	// For named structs, directly register both their value and pointer types
	_, err := r.registerType(type_, typeId, namespace, typeName, nil, false)
	if err != nil {
		return fmt.Errorf("failed to register named structs: %w", err)
	}
	_, err = r.registerType(ptrType, -typeId, namespace, typeName, nil, false)
	if err != nil {
		return fmt.Errorf("failed to register named structs: %w", err)
	}
	return nil
}

func (r *typeResolver) RegisterExt(extId int16, type_ reflect.Type) error {
	// Registering type is necessary, otherwise we may don't have the symbols of corresponding type when deserializing.
	panic("not supported")
}

func (r *typeResolver) getSerializerByType(type_ reflect.Type, mapInStruct bool) (Serializer, error) {
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

func (r *typeResolver) getSerializerByTypeTag(typeTag string) (Serializer, error) {
	if serializer, ok := r.typeTagToSerializers[typeTag]; !ok {
		return nil, fmt.Errorf("type %s not supported", typeTag)
	} else {
		return serializer, nil
	}
}

func (r *typeResolver) getTypeInfo(value reflect.Value, create bool) (TypeInfo, error) {
	// First check if type info exists in cache
	if value.Kind() == reflect.Interface {
		// make sure the concrete value don't miss its real typeInfo
		value = value.Elem()
	}

	type_ := value.Type()

	if type_.Kind() == reflect.Slice && type_.Elem().Kind() != reflect.Uint8 {
		info := r.typeIDToTypeInfo[LIST]
		info.Serializer = NewSliceSerializer(r.fory, nil, nil)
		return info, nil
	}

	if info, ok := r.typesInfo[type_]; ok {
		if info.Serializer == nil {
			/*
			   Lazy initialize serializer if not created yet
			   mapInStruct equals false because this path isn’t taken when extracting field info from structs;
			   for all other map cases, it remains false
			*/
			serializer, err := r.createSerializer(value.Type(), false)
			if err != nil {
				fmt.Errorf("failed to create serializer: %w", err)
			}
			info.Serializer = serializer
		}
		return info, nil
	}

	var internal = false

	// Early return if type registration is required but not allowed
	if !create {
		fmt.Errorf("type %v not registered and create=false", value.Type())
	}
	// Get package path and type name for registration
	var typeName string
	var pkgPath string
	rawInfo, ok := r.typeToTypeInfo[type_]
	if !ok {
		fmt.Errorf("type %v not registered with a tag", type_)
	}
	clean := strings.TrimPrefix(rawInfo, "*@")
	clean = strings.TrimPrefix(clean, "@")
	typeName = clean
	if idx := strings.LastIndex(clean, "."); idx != -1 {
		pkgPath = clean[:idx]
		typeName = clean[idx+1:]
	}

	// Handle special types that require explicit registration
	switch {
	case type_.Kind() == reflect.Ptr:
		fmt.Errorf("pointer types must be registered explicitly")
	case type_.Kind() == reflect.Interface:
		fmt.Errorf("interface types must be registered explicitly")
	case pkgPath == "" && typeName == "":
		fmt.Errorf("anonymous types must be registered explicitly")
	}

	// Determine type ID and registration strategy
	var typeID int32
	switch {
	case r.language == XLANG && !r.requireRegistration:
		// Auto-assign an invalid ID for later overwrite.
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
		typeID = -NAMED_STRUCT
	} else if value.Kind() == reflect.Map {
		typeID = MAP
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

func (r *typeResolver) getTypeInfoByType(t reflect.Type) (TypeInfo, error) {
	v, err := valueForTypeShape(t)
	if err != nil {
		return TypeInfo{}, err
	}
	return r.getTypeInfo(v, true)
}

func valueForTypeShape(t reflect.Type) (reflect.Value, error) {
	if t.Kind() == reflect.Interface {
		return reflect.Value{}, fmt.Errorf("need a concrete value for interface type %v", t)
	}
	switch t.Kind() {
	case reflect.Pointer:
		return reflect.New(t.Elem()), nil
	case reflect.Struct:
		return reflect.New(t).Elem(), nil
	default:
		return reflect.Zero(t), nil // slice/array/map/chan/func/basic types...
	}
}

func (r *typeResolver) registerType(
	type_ reflect.Type,
	typeID int32,
	namespace string,
	typeName string,
	serializer Serializer,
	internal bool,
) (TypeInfo, error) {
	// Input validation
	if type_ == nil {
		panic("nil type")
	}
	if typeName == "" && namespace != "" {
		panic("namespace provided without typeName")
	}
	if internal && serializer != nil {
		if err := r.RegisterSerializer(type_, serializer); err != nil {
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

	// Determine if this is a dynamic type (negative typeID)
	dynamicType := typeID < 0

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

		nsMeta, _ := r.namespaceEncoder.Encode(namespace)
		if nsBytes = r.metaStringResolver.GetMetaStrBytes(&nsMeta); nsBytes == nil {
			panic("failed to encode namespace")
		}

		typeMeta, _ := r.typeNameEncoder.Encode(typeName)
		if typeBytes = r.metaStringResolver.GetMetaStrBytes(&typeMeta); typeBytes == nil {
			panic("failed to encode type name")
		}
	}

	// Build complete type information structure
	typeInfo := TypeInfo{
		Type:         type_,
		TypeID:       typeID,
		Serializer:   serializer,
		PkgPathBytes: nsBytes,   // Encoded namespace bytes
		NameBytes:    typeBytes, // Encoded type name bytes
		IsDynamic:    dynamicType,
		hashValue:    calcTypeHash(type_), // Precomputed hash for fast lookups
	}
	// Update resolver caches:
	r.typesInfo[type_] = typeInfo // Cache by type string
	if typeName != "" {
		r.namedTypeToTypeInfo[[2]string{namespace, typeName}] = typeInfo
		// Cache by hashed namespace/name bytes
		r.nsTypeToTypeInfo[nsTypeKey{nsBytes.Hashcode, typeBytes.Hashcode}] = typeInfo
	}

	// Cache by type ID (for cross-language support)
	if r.language == XLANG && !IsNamespacedType(TypeId(typeID)) {
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

func (r *typeResolver) metaShareEnabled() bool {
	return r.fory != nil && r.fory.metaContext != nil && r.fory.compatible
}

func (r *typeResolver) writeTypeInfo(buffer *ByteBuffer, typeInfo TypeInfo) error {
	// Extract the internal type ID (lower 8 bits)
	typeID := typeInfo.TypeID
	internalTypeID := typeID
	if typeID < 0 {
		internalTypeID = -internalTypeID
	}

	// Write the type ID to buffer (variable-length encoding)
	buffer.WriteVarUint32(uint32(typeID))
	// For namespaced types, write additional metadata:
	if IsNamespacedType(TypeId(internalTypeID)) {
		if r.metaShareEnabled() {
			if err := r.writeSharedTypeMeta(buffer, typeInfo); err != nil {
				return err
			}
			return nil
		}
		// Write package path (namespace) metadata
		if err := r.metaStringResolver.WriteMetaStringBytes(buffer, typeInfo.PkgPathBytes); err != nil {
			return err
		}
		// Write type name metadata
		if err := r.metaStringResolver.WriteMetaStringBytes(buffer, typeInfo.NameBytes); err != nil {
			return err
		}
	}

	return nil
}

func (r *typeResolver) writeSharedTypeMeta(buffer *ByteBuffer, typeInfo TypeInfo) error {
	context := r.fory.metaContext
	typ := typeInfo.Type

	if index, exists := context.typeMap[typ]; exists {
		buffer.WriteVarUint32(index)
		return nil
	}

	newIndex := uint32(len(context.typeMap))
	buffer.WriteVarUint32(newIndex)
	context.typeMap[typ] = newIndex

	typeDef, err := r.getTypeDef(typeInfo.Type, true)
	if err != nil {
		return err
	}
	context.writingTypeDefs = append(context.writingTypeDefs, typeDef)
	return nil
}

func (r *typeResolver) getTypeDef(typ reflect.Type, create bool) (*TypeDef, error) {
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

func (r *typeResolver) readSharedTypeMeta(buffer *ByteBuffer) (TypeInfo, error) {
	context := r.fory.metaContext
	index := buffer.ReadVarInt32() // shared meta index id
	if index < 0 || index >= int32(len(context.readTypeInfos)) {
		return TypeInfo{}, fmt.Errorf("TypeInfo not found for index %d", index)
	}
	info := context.readTypeInfos[index]
	return info, nil
}

func (r *typeResolver) writeTypeDefs(buffer *ByteBuffer) {
	context := r.fory.metaContext
	sz := len(context.writingTypeDefs)
	buffer.WriteVarUint32Small7(uint32(sz))
	for _, typeDef := range context.writingTypeDefs {
		typeDef.writeTypeDef(buffer)
	}
	context.writingTypeDefs = nil
}

func (r *typeResolver) readTypeDefs(buffer *ByteBuffer) error {
	numTypeDefs := buffer.ReadVarUint32Small7()
	context := r.fory.metaContext
	for i := 0; i < numTypeDefs; i++ {
		id := buffer.ReadInt64()
		var td *TypeDef
		if existingTd, exists := r.defIdToTypeDef[id]; exists {
			skipTypeDef(buffer, id)
			td = existingTd
		} else {
			newTd, err := readTypeDef(r.fory, buffer, id)
			if err != nil {
				return err
			}
			r.defIdToTypeDef[id] = newTd
			td = newTd
		}
		typeInfo, err := td.buildTypeInfo()
		if err != nil {
			return err
		}
		context.readTypeInfos = append(context.readTypeInfos, typeInfo)
	}
	return nil
}

func (r *typeResolver) createSerializer(type_ reflect.Type, mapInStruct bool) (s Serializer, err error) {
	kind := type_.Kind()
	switch kind {
	case reflect.Ptr:
		if elemKind := type_.Elem().Kind(); elemKind == reflect.Ptr || elemKind == reflect.Interface {
			return nil, fmt.Errorf("pointer to pinter/interface are not supported but got type %s", type_)
		}
		valueSerializer, err := r.getSerializerByType(type_.Elem(), false)
		if err != nil {
			return nil, err
		}
		return &ptrToValueSerializer{valueSerializer}, nil
	case reflect.Slice:
		return NewSliceSerializer(r.fory, nil, nil), nil
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
			return &mapSerializer{
				type_:             type_,
				keySerializer:     keySerializer,
				valueSerializer:   valueSerializer,
				keyReferencable:   nullable(type_.Key()),
				valueReferencable: nullable(type_.Elem()),
				mapInStruct:       mapInStruct,
			}, nil
		} else {
			return mapSerializer{mapInStruct: mapInStruct}, nil
		}
	case reflect.Struct:
		return r.typeToSerializers[type_], nil
	}
	return nil, fmt.Errorf("type %s not supported", type_.String())
}

func isDynamicType(type_ reflect.Type) bool {
	return type_.Kind() == reflect.Interface || (type_.Kind() == reflect.Ptr && (type_.Elem().Kind() == reflect.Ptr ||
		type_.Elem().Kind() == reflect.Interface))
}

func (r *typeResolver) writeType(buffer *ByteBuffer, type_ reflect.Type) error {
	typeInfo, ok := r.typeToTypeInfo[type_]
	if !ok {
		if encodeType, err := r.encodeType(type_); err != nil {
			return err
		} else {
			typeInfo = encodeType
			r.typeToTypeInfo[type_] = encodeType
		}
	}
	if err := r.writeMetaString(buffer, typeInfo); err != nil {
		return err
	} else {
		return nil
	}
}

func (r *typeResolver) readType(buffer *ByteBuffer) (reflect.Type, error) {
	metaString, err := r.readMetaString(buffer)
	if err != nil {
		return nil, err
	}
	type_, ok := r.typeInfoToType[metaString]
	if !ok {
		type_, _, err = r.decodeType(metaString)
		if err != nil {
			return nil, err
		} else {
			r.typeInfoToType[metaString] = type_
		}
	}
	return type_, nil
}

func (r *typeResolver) encodeType(type_ reflect.Type) (string, error) {
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

func (r *typeResolver) decodeType(typeStr string) (reflect.Type, string, error) {
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

func (r *typeResolver) writeTypeTag(buffer *ByteBuffer, typeTag string) error {
	if err := r.writeMetaString(buffer, typeTag); err != nil {
		return err
	} else {
		return nil
	}
}

func (r *typeResolver) readTypeByReadTag(buffer *ByteBuffer) (reflect.Type, error) {
	metaString, err := r.readMetaString(buffer)
	if err != nil {
		return nil, err
	}
	return r.typeTagToSerializers[metaString].(*ptrToStructSerializer).type_, err
}

func (r *typeResolver) readTypeInfo(buffer *ByteBuffer) (TypeInfo, error) {
	// Read variable-length type ID
	typeID := buffer.ReadVarInt32()
	internalTypeID := typeID // Extract lower 8 bits for internal type ID
	if typeID < 0 {
		internalTypeID = -internalTypeID
	}
	if IsNamespacedType(TypeId(internalTypeID)) {
		if r.metaShareEnabled() {
			return r.readSharedTypeMeta(buffer)
		}
		// Read namespace and type name metadata bytes
		nsBytes, err := r.metaStringResolver.ReadMetaStringBytes(buffer)
		if err != nil {
			fmt.Errorf("failed to read namespace bytes: %w", err)
		}

		typeBytes, err := r.metaStringResolver.ReadMetaStringBytes(buffer)
		if err != nil {
			fmt.Errorf("failed to read type bytes: %w", err)
		}

		compositeKey := nsTypeKey{nsBytes.Hashcode, typeBytes.Hashcode}
		var typeInfo TypeInfo
		// For pointer and value types, use the negative ID system
		// to obtain the correct TypeInfo for subsequent deserialization
		if typeInfo, exists := r.nsTypeToTypeInfo[compositeKey]; exists {
			/*
			   If the expected ID indicates a value-type struct
			   but the registered entry was overwritten with the pointer type, restore it.
			   If the expected ID indicates a pointer-type struct
			   but the registered entry was overwritten with the value type, convert to pointer.
			   In all other cases, the ID matches the actual type and no adjustment is needed.
			*/

			if typeID > 0 && typeInfo.Type.Kind() == reflect.Ptr {
				typeInfo.Type = typeInfo.Type.Elem()
				typeInfo.Serializer = r.typeToSerializers[typeInfo.Type]
				typeInfo.TypeID = typeID
			} else if typeID < 0 && typeInfo.Type.Kind() != reflect.Ptr {
				realType := reflect.PtrTo(typeInfo.Type)
				typeInfo.Type = realType
				typeInfo.Serializer = r.typeToSerializers[typeInfo.Type]
				typeInfo.TypeID = typeID
			}
			return typeInfo, nil
		}

		// If not found, decode the bytes to strings and try again
		ns, err := r.namespaceDecoder.Decode(nsBytes.Data, nsBytes.Encoding)
		if err != nil {
			fmt.Errorf("namespace decode failed: %w", err)
		}

		typeName, err := r.typeNameDecoder.Decode(typeBytes.Data, typeBytes.Encoding)
		if err != nil {
			fmt.Errorf("typename decode failed: %w", err)
		}

		nameKey := [2]string{ns, typeName}
		if typeInfo, exists := r.namedTypeToTypeInfo[nameKey]; exists {
			r.nsTypeToTypeInfo[compositeKey] = typeInfo
			return typeInfo, nil
		}
		_ = typeName
		if ns != "" {
			_ = ns + "." + typeName
		}
		return typeInfo, nil
	}

	// Handle simple type IDs (non-namespaced types)
	if typeInfo, exists := r.typeIDToTypeInfo[typeID]; exists {
		return typeInfo, nil
	}

	return TypeInfo{}, nil
}

// TypeUnregisteredError indicates when a requested type is not registered
type TypeUnregisteredError struct {
	TypeName string
}

func (e *TypeUnregisteredError) Error() string {
	return fmt.Sprintf("type %s not registered", e.TypeName)
}

func (r *typeResolver) getTypeById(id int16) (reflect.Type, error) {
	type_, ok := r.typeIdToType[id]
	if !ok {
		return nil, fmt.Errorf("type of id %d not supported, supported types: %v", id, r.typeIdToType)
	}
	return type_, nil
}

func (r *typeResolver) getTypeInfoById(id int16) (TypeInfo, error) {
	if typeInfo, exists := r.typeIDToTypeInfo[int32(id)]; exists {
		return typeInfo, nil
	} else {
		return TypeInfo{}, fmt.Errorf("typeInfo of typeID %d not found", id)
	}
}

func (r *typeResolver) writeMetaString(buffer *ByteBuffer, str string) error {
	if id, ok := r.dynamicStringToId[str]; !ok {
		dynamicStringId := r.dynamicStringId
		r.dynamicStringId += 1
		r.dynamicStringToId[str] = dynamicStringId
		length := len(str)
		buffer.WriteVarInt32(int32(length << 1))
		if length <= SMALL_STRING_THRESHOLD {
			buffer.WriteByte_(uint8(meta.UTF_8))
		} else {
			// TODO this hash should be unique, since we don't compare data equality for performance
			h := fnv.New64a()
			if _, err := h.Write([]byte(str)); err != nil {
				return err
			}
			hash := int64(h.Sum64() & 0xffffffffffffff00)
			buffer.WriteInt64(hash)
		}
		if len(str) > MaxInt16 {
			return fmt.Errorf("too long string: %s", str)
		}
		buffer.WriteBinary(unsafeGetBytes(str))
	} else {
		buffer.WriteVarInt32(int32(((id + 1) << 1) | 1))
	}
	return nil
}

func (r *typeResolver) readMetaString(buffer *ByteBuffer) (string, error) {
	header := buffer.ReadVarInt32()
	var length = int(header >> 1)
	if header&0b1 == 0 {
		if length <= SMALL_STRING_THRESHOLD {
			buffer.ReadByte_()
		} else {
			// TODO support use computed hash
			buffer.ReadInt64()
		}
		str := string(buffer.ReadBinary(length))
		dynamicStringId := r.dynamicStringId
		r.dynamicStringId += 1
		r.dynamicIdToString[dynamicStringId] = str
		return str, nil
	} else {
		return r.dynamicIdToString[int16(length-1)], nil
	}
}

func (r *typeResolver) resetWrite() {
	if r.dynamicStringId > 0 {
		r.dynamicStringToId = map[string]int16{}
		r.dynamicIdToString = map[int16]string{}
		r.dynamicStringId = 0
	}
}

func (r *typeResolver) resetRead() {
	if r.dynamicStringId > 0 {
		r.dynamicStringToId = map[string]int16{}
		r.dynamicIdToString = map[int16]string{}
		r.dynamicStringId = 0
	}
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

func isPrimitiveType(typeID int16) bool {
	switch typeID {
	case BOOL,
		INT8,
		INT16,
		INT32,
		INT64,
		FLOAT,
		DOUBLE:
		return true
	default:
		return false
	}
}

func isPrimitiveType_(type_ reflect.Type) bool {
	switch type_.Kind() {
	case reflect.Bool,
		reflect.Int8,
		reflect.Int16,
		reflect.Int32,
		reflect.Int64,
		reflect.Float32,
		reflect.Float64:
		return true
	default:
		return false
	}
}

func isListType(typeID int16) bool {
	return typeID == LIST
}

func isSetType(typeID int16) bool {
	return typeID == SET
}

func isMapType(typeID int16) bool {
	return typeID == MAP
}

func isPrimitiveArrayType(typeID int16) bool {
	switch typeID {
	case BOOL_ARRAY,
		INT8_ARRAY,
		INT16_ARRAY,
		INT32_ARRAY,
		INT64_ARRAY,
		FLOAT32_ARRAY,
		FLOAT64_ARRAY:
		return true
	default:
		return false
	}
}

var primitiveTypeSizes = map[int16]int{
	BOOL:      1,
	INT8:      1,
	INT16:     2,
	INT32:     4,
	VAR_INT32: 4,
	INT64:     8,
	VAR_INT64: 8,
	FLOAT:     4,
	DOUBLE:    8,
}

func getPrimitiveTypeSize(typeID int16) int {
	if sz, ok := primitiveTypeSizes[typeID]; ok {
		return sz
	}
	return -1
}

func isUserDefinedType(typeID int16) bool {
	return typeID == STRUCT ||
		typeID == COMPATIBLE_STRUCT ||
		typeID == NAMED_STRUCT ||
		typeID == NAMED_COMPATIBLE_STRUCT ||
		typeID == EXT ||
		typeID == NAMED_EXT ||
		typeID == ENUM ||
		typeID == NAMED_ENUM
}

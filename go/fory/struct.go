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
	"unicode"
	"unicode/utf8"
	"unsafe"

	"github.com/spaolacci/murmur3"
)

// FieldInfo stores field metadata computed at init time
// Uses offset for unsafe direct memory access at runtime
type FieldInfo struct {
	Name         string
	Offset       uintptr
	Type         reflect.Type
	StaticId StaticTypeId
	Serializer   Serializer
	Referencable bool
	FieldIndex   int // -1 if field doesn't exist in current struct (for compatible mode)
}

type structSerializer struct {
	typeTag    string
	type_      reflect.Type
	fields     []*FieldInfo
	fieldMap   map[string]*FieldInfo // for compatible reading
	structHash int32
	fieldDefs  []FieldDef // for type_def compatibility
}

var UNKNOWN_TYPE_ID = int16(63)

func (s *structSerializer) TypeId() TypeId {
	return NAMED_STRUCT
}

func (s *structSerializer) NeedToWriteRef() bool {
	return true
}

func (s *structSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	buf := ctx.Buffer()
	if s.fields == nil {
		if s.type_ == nil {
			s.type_ = value.Type()
		}
		// Ensure s.type_ is the struct type, not a pointer type
		for s.type_.Kind() == reflect.Ptr {
			s.type_ = s.type_.Elem()
		}
		if err := s.initFieldsFromContext(ctx); err != nil {
			return err
		}
	}
	if s.structHash == 0 {
		s.structHash = s.computeHash()
	}

	buf.WriteInt32(s.structHash)

	// Check if value is addressable for unsafe access
	canUseUnsafe := value.CanAddr()
	var ptr unsafe.Pointer
	if canUseUnsafe {
		ptr = unsafe.Pointer(value.UnsafeAddr())
	}

	for _, field := range s.fields {
		// Fast path for primitive types using unsafe access
		if canUseUnsafe && field.StaticId != ConcreteTypeOther && !field.Referencable {
			fieldPtr := unsafe.Add(ptr, field.Offset)
			ctx.writeFast(fieldPtr, field.StaticId)
			continue
		}

		// Slow path for complex types or non-addressable values
		fieldValue := value.Field(field.FieldIndex)
		if field.Serializer != nil {
			if err := writeBySerializer(ctx, fieldValue, field.Serializer, field.Referencable); err != nil {
				return err
			}
		} else {
			if err := ctx.WriteValue(fieldValue); err != nil {
				return err
			}
		}
	}
	return nil
}

func (s *structSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	buf := ctx.Buffer()
	if value.Kind() == reflect.Ptr {
		if value.IsNil() {
			value.Set(reflect.New(type_.Elem()))
		}
		value = value.Elem()
		type_ = type_.Elem()
	}

	if s.fields == nil {
		if s.type_ == nil {
			s.type_ = type_
		}
		// Ensure s.type_ is the struct type, not a pointer type
		for s.type_.Kind() == reflect.Ptr {
			s.type_ = s.type_.Elem()
		}
		if err := s.initFieldsFromContext(ctx); err != nil {
			return err
		}
	}
	if s.structHash == 0 {
		s.structHash = s.computeHash()
	}

	structHash := buf.ReadInt32()
	if !ctx.Compatible() && structHash != s.structHash {
		return fmt.Errorf("hash %d is not consistent with %d for type %s",
			structHash, s.structHash, s.type_)
	}

	// Get base pointer for unsafe access
	ptr := unsafe.Pointer(value.UnsafeAddr())

	for _, field := range s.fields {
		if field.FieldIndex < 0 {
			// Field doesn't exist in current struct, create temp value to discard
			tempValue := reflect.New(field.Type).Elem()
			if field.Serializer != nil {
				if err := readBySerializer(ctx, tempValue, field.Serializer, field.Referencable); err != nil {
					return err
				}
			} else {
				if err := ctx.ReadValue(tempValue); err != nil {
					return err
				}
			}
			continue
		}
		// Fast path for primitive types using switch
		if field.StaticId != ConcreteTypeOther && !field.Referencable {
			fieldPtr := unsafe.Add(ptr, field.Offset)
			ctx.readFast(fieldPtr, field.StaticId)
			continue
		}

		// Slow path for complex types
		fieldValue := value.Field(field.FieldIndex)
		if field.Serializer != nil {
			if err := readBySerializer(ctx, fieldValue, field.Serializer, field.Referencable); err != nil {
				return err
			}
		} else {
			if err := ctx.ReadValue(fieldValue); err != nil {
				return err
			}
		}
	}
	return nil
}

// ReadCompatible reads struct data with schema evolution support
// It reads fields based on remote schema and maps to local fields by name
func (s *structSerializer) ReadCompatible(ctx *ReadContext, type_ reflect.Type, value reflect.Value, remoteFields []*FieldInfo) error {
	if value.Kind() == reflect.Ptr {
		if value.IsNil() {
			value.Set(reflect.New(type_.Elem()))
		}
		value = value.Elem()
	}

	if s.fieldMap == nil {
		s.fieldMap = make(map[string]*FieldInfo)
		for _, field := range s.fields {
			s.fieldMap[field.Name] = field
		}
	}

	ptr := unsafe.Pointer(value.UnsafeAddr())

	for _, remoteField := range remoteFields {
		localField, exists := s.fieldMap[remoteField.Name]

		if !exists {
			// Field doesn't exist locally, discard
			tempValue := reflect.New(remoteField.Type).Elem()
			if remoteField.Serializer != nil {
				if err := readBySerializer(ctx, tempValue, remoteField.Serializer, remoteField.Referencable); err != nil {
					return err
				}
			} else {
				if err := ctx.ReadValue(tempValue); err != nil {
					return err
				}
			}
			continue
		}

		fieldPtr := unsafe.Add(ptr, localField.Offset)

		// Fast path for primitive types
		if localField.StaticId != ConcreteTypeOther && !localField.Referencable {
			ctx.readFast(fieldPtr, localField.StaticId)
			continue
		}

		// Slow path
		fieldValue := value.Field(localField.FieldIndex)
		if localField.Serializer != nil {
			if err := readBySerializer(ctx, fieldValue, localField.Serializer, localField.Referencable); err != nil {
				return err
			}
		} else {
			if err := ctx.ReadValue(fieldValue); err != nil {
				return err
			}
		}
	}
	return nil
}

// initFieldsFromContext initializes fields using context's type resolver (for WriteContext)
func (s *structSerializer) initFieldsFromContext(ctx interface{ TypeResolver() *TypeResolver }) error {
	typeResolver := ctx.TypeResolver()

	// If we have fieldDefs from type_def (remote meta), use them
	if len(s.fieldDefs) > 0 {
		return s.initFieldsFromDefsWithResolver(typeResolver)
	}

	// Otherwise initialize from local struct type
	type_ := s.type_
	var fields []*FieldInfo
	var fieldNames []string
	var serializers []Serializer

	for i := 0; i < type_.NumField(); i++ {
		field := type_.Field(i)
		firstRune, _ := utf8.DecodeRuneInString(field.Name)
		if unicode.IsLower(firstRune) {
			continue // skip unexported fields
		}

		originalFieldType := field.Type
		fieldType := field.Type
		if fieldType.Kind() == reflect.Interface {
			fieldType = reflect.ValueOf(fieldType).Elem().Type()
		}

		var fieldSerializer Serializer
		if fieldType.Kind() != reflect.Struct {
			fieldSerializer, _ = typeResolver.getSerializerByType(fieldType, true)
			if fieldType.Kind() == reflect.Array && fieldType.Elem().Kind() != reflect.Interface {
				// For fixed-size arrays with primitive elements, use primitive array serializers
				// to match cross-language format (Python int8_array, int16_array, etc.)
				elemType := fieldType.Elem()
				switch elemType.Kind() {
				case reflect.Int8:
					fieldSerializer = int8ArraySerializer{}
				case reflect.Int16:
					fieldSerializer = int16ArraySerializer{}
				case reflect.Int32:
					fieldSerializer = int32ArraySerializer{}
				case reflect.Int64:
					fieldSerializer = int64ArraySerializer{}
				case reflect.Float32:
					fieldSerializer = float32ArraySerializer{}
				case reflect.Float64:
					fieldSerializer = float64ArraySerializer{}
				default:
					// For non-primitive arrays, use sliceSerializer
					fieldSerializer = sliceSerializer{
						elemInfo:     typeResolver.typesInfo[elemType],
						declaredType: elemType,
					}
				}
			} else if fieldType.Kind() == reflect.Slice && fieldType.Elem().Kind() != reflect.Interface {
				// For struct fields, always use the generic sliceSerializer for cross-language compatibility
				// The generic sliceSerializer uses collection flags and element type ID format
				// which matches the codegen format
				fieldSerializer = sliceSerializer{
					elemInfo:     typeResolver.typesInfo[fieldType.Elem()],
					declaredType: fieldType.Elem(),
				}
			}
		}

	fieldInfo := &FieldInfo{
		Name:         SnakeCase(field.Name),
		Offset:       field.Offset,
		Type:         fieldType,
		StaticId:     GetStaticTypeId(fieldType),
		Serializer:   fieldSerializer,
		Referencable: nullable(originalFieldType),
		FieldIndex:   i,
	}
	fields = append(fields, fieldInfo)
	fieldNames = append(fieldNames, fieldInfo.Name)
	serializers = append(serializers, fieldSerializer)
	}

	// Sort fields according to specification
	serializers, fieldNames = sortFields(typeResolver, fieldNames, serializers)
	order := make(map[string]int, len(fieldNames))
	for idx, name := range fieldNames {
		order[name] = idx
	}

	sort.SliceStable(fields, func(i, j int) bool {
		oi, okI := order[fields[i].Name]
		oj, okJ := order[fields[j].Name]
		switch {
		case okI && okJ:
			return oi < oj
		case okI:
			return true
		case okJ:
			return false
		default:
			return false
		}
	})

	s.fields = fields
	return nil
}

// initFieldsFromDefsWithResolver initializes fields from remote fieldDefs using typeResolver
func (s *structSerializer) initFieldsFromDefsWithResolver(typeResolver *TypeResolver) error {
	type_ := s.type_

	// Build map from field names to struct field indices
	fieldNameToIndex := make(map[string]int)
	fieldNameToOffset := make(map[string]uintptr)
	fieldNameToType := make(map[string]reflect.Type)
	for i := 0; i < type_.NumField(); i++ {
		field := type_.Field(i)
		name := SnakeCase(field.Name)
		fieldNameToIndex[name] = i
		fieldNameToOffset[name] = field.Offset
		fieldNameToType[name] = field.Type
	}

	var fields []*FieldInfo

	for _, def := range s.fieldDefs {
		fieldSerializer, _ := getFieldTypeSerializerWithResolver(typeResolver, def.fieldType)

		// Get the remote type from fieldDef
		remoteTypeInfo, _ := def.fieldType.getTypeInfoWithResolver(typeResolver)
		remoteType := remoteTypeInfo.Type
		if remoteType == nil {
			remoteType = reflect.TypeOf((*interface{})(nil)).Elem()
		}

		// Try to find corresponding local field
		fieldIndex := -1
		var offset uintptr
		var fieldType reflect.Type

		if idx, exists := fieldNameToIndex[def.name]; exists {
			localType := fieldNameToType[def.name]
			// Check if types are compatible
			if typesCompatible(localType, remoteType) {
				fieldIndex = idx
				offset = fieldNameToOffset[def.name]
				fieldType = localType
			} else {
				// Types are incompatible - use remote type but mark field as not settable
				fieldType = remoteType
				fieldIndex = -1
			}
		} else {
			// Field doesn't exist locally, use type from fieldDef
			fieldType = remoteType
		}

	fieldInfo := &FieldInfo{
		Name:         def.name,
		Offset:       offset,
		Type:         fieldType,
		StaticId: GetStaticTypeId(fieldType),
		Serializer:   fieldSerializer,
		Referencable: def.nullable,
		FieldIndex:   fieldIndex,
	}
	fields = append(fields, fieldInfo)
	}

	s.fields = fields
	return nil
}

// toSnakeCase converts CamelCase to snake_case
func toSnakeCase(s string) string {
	var result []rune
	for i, r := range s {
		if i > 0 && unicode.IsUpper(r) {
			result = append(result, '_')
		}
		result = append(result, unicode.ToLower(r))
	}
	return string(result)
}

func (s *structSerializer) computeHash() int32 {
	var sb strings.Builder

	for _, field := range s.fields {
		sb.WriteString(toSnakeCase(field.Name))
		sb.WriteString(",")

		var typeId TypeId
		if field.Serializer == nil {
			typeId = UNKNOWN
		} else {
			typeId = field.Serializer.TypeId()
			// For fixed-size arrays with primitive elements, use primitive array type IDs
			// This matches Python's int8_array, int16_array, etc. types
			if field.Type.Kind() == reflect.Array {
				elemKind := field.Type.Elem().Kind()
				switch elemKind {
				case reflect.Int8:
					typeId = INT8_ARRAY
				case reflect.Int16:
					typeId = INT16_ARRAY
				case reflect.Int32:
					typeId = INT32_ARRAY
				case reflect.Int64:
					typeId = INT64_ARRAY
				case reflect.Float32:
					typeId = FLOAT32_ARRAY
				case reflect.Float64:
					typeId = FLOAT64_ARRAY
				default:
					typeId = LIST
				}
			} else if field.Type.Kind() == reflect.Slice {
				// Slices use LIST type ID (maps to Python List[T])
				typeId = LIST
			}
		}
		sb.WriteString(fmt.Sprintf("%d", typeId))
		sb.WriteString(",")

		nullableFlag := "0"
		if field.Referencable {
			nullableFlag = "1"
		}
		sb.WriteString(nullableFlag)
		sb.WriteString(";")
	}

	hashString := sb.String()
	data := []byte(hashString)
	h1, _ := murmur3.Sum128WithSeed(data, 47)
	hash := int32(h1 & 0xFFFFFFFF)

	if hash == 0 {
		panic(fmt.Errorf("hash for type %v is 0", s.type_))
	}
	return hash
}

// ptrToStructSerializer serializes a *struct
type ptrToStructSerializer struct {
	type_ reflect.Type
	structSerializer
}

func (s *ptrToStructSerializer) TypeId() TypeId {
	return NAMED_STRUCT
}

func (s *ptrToStructSerializer) NeedToWriteRef() bool {
	return true
}

func (s *ptrToStructSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	elemValue := value.Elem()

	// In compatible mode, write typeInfo for the struct so TypeDefs are collected
	if ctx.Compatible() {
		typeInfo, err := ctx.TypeResolver().getTypeInfo(elemValue, true)
		if err != nil {
			return err
		}
		if err := ctx.TypeResolver().writeTypeInfo(ctx.Buffer(), typeInfo); err != nil {
			return err
		}
	}

	return s.structSerializer.Write(ctx, elemValue)
}

func (s *ptrToStructSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	newValue := reflect.New(type_.Elem())
	value.Set(newValue)
	elem := newValue.Elem()
	ctx.RefResolver().Reference(newValue)

	// In compatible mode, the structSerializer may have fieldDefs from TypeDef
	// which were set when this ptrToStructSerializer was created by readSharedTypeMeta
	return s.structSerializer.Read(ctx, type_.Elem(), elem)
}

// ptrToCodegenSerializer wraps a generated serializer for pointer types
type ptrToCodegenSerializer struct {
	type_             reflect.Type
	codegenSerializer Serializer
}

func (s *ptrToCodegenSerializer) TypeId() TypeId {
	return NAMED_STRUCT
}

func (s *ptrToCodegenSerializer) NeedToWriteRef() bool {
	return true
}

func (s *ptrToCodegenSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	// Dereference pointer and delegate to the generated serializer
	return s.codegenSerializer.Write(ctx, value.Elem())
}

func (s *ptrToCodegenSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	// Allocate new value if needed
	newValue := reflect.New(type_.Elem())
	value.Set(newValue)
	elem := newValue.Elem()
	ctx.RefResolver().Reference(newValue)
	return s.codegenSerializer.Read(ctx, type_.Elem(), elem)
}

// Field sorting helpers

type triple struct {
	typeID     int16
	serializer Serializer
	name       string
}

func sortFields(
	typeResolver *TypeResolver,
	fieldNames []string,
	serializers []Serializer,
) ([]Serializer, []string) {
	var (
		typeTriples []triple
		others      []triple
	)

	for i, name := range fieldNames {
		ser := serializers[i]
		if ser == nil {
			others = append(others, triple{UNKNOWN_TYPE_ID, nil, name})
			continue
		}
		typeTriples = append(typeTriples, triple{ser.TypeId(), ser, name})
	}
	var boxed, collection, setFields, maps, otherInternalTypeFields []triple

	for _, t := range typeTriples {
		switch {
		case isPrimitiveType(t.typeID):
			boxed = append(boxed, t)
		case isListType(t.typeID), isPrimitiveArrayType(t.typeID):
			collection = append(collection, t)
		case isSetType(t.typeID):
			setFields = append(setFields, t)
		case isMapType(t.typeID):
			maps = append(maps, t)
		case isUserDefinedType(t.typeID) || t.typeID == UNKNOWN_TYPE_ID:
			others = append(others, t)
		default:
			otherInternalTypeFields = append(otherInternalTypeFields, t)
		}
	}
	sort.Slice(boxed, func(i, j int) bool {
		ai, aj := boxed[i], boxed[j]
		compressI := ai.typeID == INT32 || ai.typeID == INT64 ||
			ai.typeID == VAR_INT32 || ai.typeID == VAR_INT64
		compressJ := aj.typeID == INT32 || aj.typeID == INT64 ||
			aj.typeID == VAR_INT32 || aj.typeID == VAR_INT64
		if compressI != compressJ {
			return !compressI && compressJ
		}
		szI, szJ := getPrimitiveTypeSize(ai.typeID), getPrimitiveTypeSize(aj.typeID)
		if szI != szJ {
			return szI > szJ
		}
		return toSnakeCase(ai.name) < toSnakeCase(aj.name)
	})
	sortByTypeIDThenName := func(s []triple) {
		sort.Slice(s, func(i, j int) bool {
			if s[i].typeID != s[j].typeID {
				return s[i].typeID < s[j].typeID
			}
			return toSnakeCase(s[i].name) < toSnakeCase(s[j].name)
		})
	}
	sortTuple := func(s []triple) {
		sort.Slice(s, func(i, j int) bool {
			return toSnakeCase(s[i].name) < toSnakeCase(s[j].name)
		})
	}
	sortByTypeIDThenName(otherInternalTypeFields)
	sortTuple(others)
	sortTuple(collection)
	sortTuple(maps)

	all := make([]triple, 0, len(fieldNames))
	all = append(all, boxed...)
	all = append(all, otherInternalTypeFields...)
	all = append(all, collection...)
	all = append(all, setFields...)
	all = append(all, maps...)
	all = append(all, others...)

	outSer := make([]Serializer, len(all))
	outNam := make([]string, len(all))
	for i, t := range all {
		outSer[i] = t.serializer
		outNam[i] = t.name
	}
	return outSer, outNam
}

// Legacy support types for type_def

type fieldInfo struct {
	name         string
	field        reflect.StructField
	fieldIndex   int
	type_        reflect.Type
	referencable bool
	serializer   Serializer
}

type structFieldsInfo []*fieldInfo

func (x structFieldsInfo) Len() int { return len(x) }
func (x structFieldsInfo) Less(i, j int) bool {
	return x[i].name < x[j].name
}
func (x structFieldsInfo) Swap(i, j int) { x[i], x[j] = x[j], x[i] }

// createStructFieldInfos creates legacy fieldInfo slice for compatibility
func createStructFieldInfos(f *Fory, type_ reflect.Type) (structFieldsInfo, error) {
	var fields structFieldsInfo
	serializers := make([]Serializer, 0)
	fieldnames := make([]string, 0)
	for i := 0; i < type_.NumField(); i++ {
		field := type_.Field(i)
		firstRune, _ := utf8.DecodeRuneInString(field.Name)
		if unicode.IsLower(firstRune) {
			continue
		}
		originalFieldType := field.Type
		if field.Type.Kind() == reflect.Interface {
			field.Type = reflect.ValueOf(field.Type).Elem().Type()
		}
		var fieldSerializer Serializer
		if field.Type.Kind() != reflect.Struct {
			var _ error
			fieldSerializer, _ = f.typeResolver.getSerializerByType(field.Type, true)
			if field.Type.Kind() == reflect.Array {
				elemType := field.Type.Elem()
				sliceType := reflect.SliceOf(elemType)
				fieldSerializer = f.typeResolver.typeToSerializers[sliceType]
			} else if field.Type.Kind() == reflect.Slice {
				if field.Type.Elem().Kind() != reflect.Interface {
					fieldSerializer = sliceSerializer{
						elemInfo: f.typeResolver.typesInfo[field.Type.Elem()],
					}
				}
			}
		}
		f := fieldInfo{
			name:         SnakeCase(field.Name),
			field:        field,
			fieldIndex:   i,
			type_:        field.Type,
			referencable: nullable(originalFieldType),
			serializer:   fieldSerializer,
		}
		fields = append(fields, &f)
		serializers = append(serializers, fieldSerializer)
		fieldnames = append(fieldnames, f.name)
	}
	sort.Sort(fields)
	fieldPairs := make([]fieldPair, len(fieldnames))
	for i := range fieldPairs {
		fieldPairs[i] = fieldPair{name: fieldnames[i], ser: serializers[i]}
	}

	sort.Slice(fieldPairs, func(i, j int) bool {
		return fieldPairs[i].name < fieldPairs[j].name
	})

	for i, p := range fieldPairs {
		fieldnames[i] = p.name
		serializers[i] = p.ser
	}
	serializers, fieldnames = sortFields(f.typeResolver, fieldnames, serializers)
	order := make(map[string]int, len(fieldnames))
	for idx, name := range fieldnames {
		order[name] = idx
	}
	sort.SliceStable(fields, func(i, j int) bool {
		oi, okI := order[fields[i].name]
		oj, okJ := order[fields[j].name]
		switch {
		case okI && okJ:
			return oi < oj
		case okI:
			return true
		case okJ:
			return false
		default:
			return false
		}
	})
	return fields, nil
}

type fieldPair struct {
	name string
	ser  Serializer
}

// createStructFieldInfosFromFieldDefs creates structFieldsInfo from fieldDefs
func createStructFieldInfosFromFieldDefs(f *Fory, fieldDefs []FieldDef, type_ reflect.Type) (structFieldsInfo, error) {
	fieldNameToIndex := make(map[string]int)

	for i := 0; i < type_.NumField(); i++ {
		field := type_.Field(i)
		fieldName := SnakeCase(field.Name)
		fieldNameToIndex[fieldName] = i
	}

	var fields structFieldsInfo

	for _, def := range fieldDefs {
		fieldTypeFromDef, err := resolveFieldDefType(f, def)
		if err != nil {
			return nil, err
		}

		fieldIndex := -1
		var fieldType reflect.Type
		var structField reflect.StructField

		if structFieldIndex, exists := fieldNameToIndex[def.name]; exists {
			structField = type_.Field(structFieldIndex)
			fieldType = fieldTypeFromDef
			if typesCompatible(structField.Type, fieldTypeFromDef) {
				fieldIndex = structFieldIndex
				fieldType = structField.Type
			} else {
				fieldType = fieldTypeFromDef
			}
		} else {
			fieldType = fieldTypeFromDef
		}

		fieldSerializer, err := getFieldTypeSerializer(f, def.fieldType)
		if err != nil {
			return nil, fmt.Errorf("failed to get serializer for field %s: %w", def.name, err)
		}

		fieldInfo := &fieldInfo{
			name:         def.name,
			field:        structField,
			fieldIndex:   fieldIndex,
			type_:        fieldType,
			referencable: def.nullable,
			serializer:   fieldSerializer,
		}

		fields = append(fields, fieldInfo)
	}

	return fields, nil
}

func resolveFieldDefType(f *Fory, def FieldDef) (reflect.Type, error) {
	typeInfo, err := def.fieldType.getTypeInfo(f)
	if err != nil {
		return nil, fmt.Errorf("unknown type for field %s with typeId %d: %w", def.name, def.fieldType.TypeId(), err)
	}
	if typeInfo.Type == nil {
		return nil, fmt.Errorf("type information missing for field %s with typeId %d", def.name, def.fieldType.TypeId())
	}
	return typeInfo.Type, nil
}

func typesCompatible(actual, expected reflect.Type) bool {
	if actual == nil || expected == nil {
		return false
	}
	if actual == expected {
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

func elementTypesCompatible(actual, expected reflect.Type) bool {
	if actual == nil || expected == nil {
		return false
	}
	if actual == expected || actual.AssignableTo(expected) || expected.AssignableTo(actual) {
		return true
	}
	if actual.Kind() == reflect.Ptr {
		return elementTypesCompatible(actual, expected.Elem())
	}
	return false
}

func computeStructHash(fieldsInfo structFieldsInfo, typeResolver *TypeResolver) (int32, error) {
	var sb strings.Builder

	for _, fieldInfo := range fieldsInfo {
		snakeCaseName := SnakeCase(fieldInfo.name)
		sb.WriteString(snakeCaseName)
		sb.WriteString(",")

		var typeId TypeId
		serializer := fieldInfo.serializer
		if serializer == nil {
			typeId = UNKNOWN
		} else {
			typeId = serializer.TypeId()
			// For fixed-size arrays with primitive elements, use primitive array type IDs
			// This matches Python's int8_array, int16_array, etc. types
			if fieldInfo.type_.Kind() == reflect.Array {
				elemKind := fieldInfo.type_.Elem().Kind()
				switch elemKind {
				case reflect.Int8:
					typeId = INT8_ARRAY
				case reflect.Int16:
					typeId = INT16_ARRAY
				case reflect.Int32:
					typeId = INT32_ARRAY
				case reflect.Int64:
					typeId = INT64_ARRAY
				case reflect.Float32:
					typeId = FLOAT32_ARRAY
				case reflect.Float64:
					typeId = FLOAT64_ARRAY
				default:
					typeId = LIST
				}
			} else if fieldInfo.type_.Kind() == reflect.Slice {
				// Slices use LIST type ID (maps to Python List[T])
				typeId = LIST
			}
		}
		sb.WriteString(fmt.Sprintf("%d", typeId))
		sb.WriteString(",")

		nullableFlag := "0"
		if fieldInfo.referencable {
			nullableFlag = "1"
		}
		sb.WriteString(nullableFlag)
		sb.WriteString(";")
	}

	hashString := sb.String()
	data := []byte(hashString)
	h1, _ := murmur3.Sum128WithSeed(data, 47)
	hash := int32(h1 & 0xFFFFFFFF)

	if hash == 0 {
		panic(fmt.Errorf("hash for type %v is 0", fieldsInfo))
	}
	return hash, nil
}

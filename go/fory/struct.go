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

	"github.com/spaolacci/murmur3"
)

type structSerializer struct {
	typeTag         string
	type_           reflect.Type
	fieldsInfo      structFieldsInfo
	structHash      int32
	fieldDefs       []FieldDef // defs obtained during reading
	codegenDelegate Serializer // Optional codegen serializer for performance (like Python's approach)
}

var UNKNOWN_TYPE_ID = int16(63)

func (s *structSerializer) TypeId() TypeId {
	return NAMED_STRUCT
}

func (s *structSerializer) NeedWriteRef() bool {
	return true
}

func (s *structSerializer) ensureFieldsInfo(f *Fory, fallbackType reflect.Type) error {
	if s.fieldsInfo != nil {
		return nil
	}

	var (
		infos structFieldsInfo
		err   error
	)
	if len(s.fieldDefs) == 0 {
		infos, err = createStructFieldInfos(f, s.type_)
	} else {
		infos, err = createStructFieldInfosFromFieldDefs(f, s.fieldDefs, fallbackType)
	}
	if err != nil {
		return err
	}

	s.fieldsInfo = infos
	return nil
}

func (s *structSerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	// If we have a codegen delegate, use it for optimal performance
	if s.codegenDelegate != nil {
		return s.codegenDelegate.Write(f, buf, value)
	}

	// Fall back to reflection-based serialization
	if err := s.ensureFieldsInfo(f, value.Type()); err != nil {
		return err
	}
	if s.structHash == 0 {
		if hash, err := computeStructHash(s.fieldsInfo, f.typeResolver); err != nil {
			return err
		} else {
			s.structHash = hash
		}
	}

	buf.WriteInt32(s.structHash)
	for _, fieldInfo_ := range s.fieldsInfo {
		fieldValue := value.Field(fieldInfo_.fieldIndex)
		if fieldInfo_.serializer != nil {
			err := writeBySerializer(f, buf, fieldValue, fieldInfo_.serializer, fieldInfo_.referencable)
			if err != nil {
				return err
			}
		} else {
			if err := f.WriteReferencable(buf, fieldValue); err != nil {
				return err
			}
		}
	}
	return nil
}

func (s *structSerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	// If we have a codegen delegate, use it for optimal performance
	if s.codegenDelegate != nil {
		return s.codegenDelegate.Read(f, buf, type_, value)
	}

	// Fall back to reflection-based deserialization
	// struct value may be a value type if it's not a pointer, so we don't invoke `refResolver.Reference` here,
	// but invoke it in `ptrToStructSerializer` instead.
	if value.Kind() == reflect.Ptr {
		if value.IsNil() {
			value.Set(reflect.New(type_.Elem()))
		}
		value = value.Elem()
	}
	if err := s.ensureFieldsInfo(f, type_); err != nil {
		return err
	}
	if s.structHash == 0 {
		if hash, err := computeStructHash(s.fieldsInfo, f.typeResolver); err != nil {
			return err
		} else {
			s.structHash = hash
		}
	}
	structHash := buf.ReadInt32()
	if !f.compatible && structHash != s.structHash {
		return fmt.Errorf("hash %d is not consistent with %d for type %s",
			structHash, s.structHash, s.type_)
	}
	for _, fieldInfo_ := range s.fieldsInfo {
		var fieldValue reflect.Value

		if fieldInfo_.fieldIndex >= 0 {
			// Field exists in current struct version
			fieldValue = value.Field(fieldInfo_.fieldIndex)
		} else {
			// Field doesn't exist in current struct version, create temporary value to discard
			fieldValue = reflect.New(fieldInfo_.type_).Elem()
		}

		fieldSerializer := fieldInfo_.serializer
		if fieldSerializer != nil {
			if err := readBySerializer(f, buf, fieldValue, fieldSerializer, fieldInfo_.referencable); err != nil {
				return err
			}
		} else {
			if err := f.ReadReferencable(buf, fieldValue); err != nil {
				return err
			}
		}
	}
	return nil
}

type fieldPair struct {
	name string
	ser  Serializer
}

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
		// We set mapInStruct to true directly to avoid reflection from type checks.
		// This only applies to maps within structs.
		originalFieldType := field.Type // Preserve original type for nullable calculation
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
				fieldSerializer = NewSliceSerializer(f, nil, nil)
			}
		}
		f := fieldInfo{
			name:         SnakeCase(field.Name), // TODO field name to lower case
			field:        field,
			fieldIndex:   i,
			type_:        field.Type,
			referencable: nullable(originalFieldType), // Use original type for nullable calculation
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

// createStructFieldInfosFromFieldDefs creates structFieldsInfo from fieldDefs and builds field name mapping
func createStructFieldInfosFromFieldDefs(f *Fory, fieldDefs []FieldDef, type_ reflect.Type) (structFieldsInfo, error) {
	fieldNameToIndex := make(map[string]int)

	// Build map from field names to struct field indices for quick lookup
	for i := 0; i < type_.NumField(); i++ {
		field := type_.Field(i)
		fieldName := SnakeCase(field.Name)
		fieldNameToIndex[fieldName] = i
	}

	var fields structFieldsInfo
	current_field_names := make(map[string]int)

	for i, def := range fieldDefs {
		current_field_names[def.name] = i

		fieldTypeFromDef, err := resolveFieldDefType(f, def)
		if err != nil {
			return nil, err
		}

		fieldIndex := -1 // Default to -1 if field doesn't exist in current struct
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

type triple struct {
	typeID     int16
	serializer Serializer
	name       string
}

func sortFields(
	typeResolver *typeResolver,
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
		case isListType(t.typeID):
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
		return ai.name < aj.name
	})
	sortTuple := func(s []triple) {
		sort.Slice(s, func(i, j int) bool {
			if s[i].typeID != s[j].typeID {
				return s[i].typeID < s[j].typeID
			}
			return s[i].name < s[j].name
		})
	}
	sortByTypeIDThenName := func(s []triple) {
		sort.Slice(s, func(i, j int) bool {
			return s[i].name < s[j].name
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

type fieldInfo struct {
	name         string
	field        reflect.StructField
	fieldIndex   int
	type_        reflect.Type
	referencable bool
	// maybe be nil: for interface fields, we need to check whether the value is a Reference.
	serializer Serializer
}

type structFieldsInfo []*fieldInfo

func (x structFieldsInfo) Len() int { return len(x) }
func (x structFieldsInfo) Less(i, j int) bool {
	return x[i].name < x[j].name
}
func (x structFieldsInfo) Swap(i, j int) { x[i], x[j] = x[j], x[i] }

// ptrToStructSerializer serialize a *struct
type ptrToStructSerializer struct {
	type_ reflect.Type
	structSerializer
	codegenDelegate Serializer // Optional codegen serializer for performance (like Python's approach)
}

func (s *ptrToStructSerializer) TypeId() TypeId {
	return FORY_TYPE_TAG
}

func (s *ptrToStructSerializer) NeedWriteRef() bool {
	return true
}

func (s *ptrToStructSerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	// If we have a codegen delegate, use it for optimal performance (Python-style approach)
	if s.codegenDelegate != nil {
		return s.codegenDelegate.Write(f, buf, value)
	}

	// Fall back to reflection-based serialization
	return s.structSerializer.Write(f, buf, value.Elem())
}

func (s *ptrToStructSerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	// If we have a codegen delegate, use it for optimal performance
	if s.codegenDelegate != nil {
		return s.codegenDelegate.Read(f, buf, type_, value)
	}

	// Fall back to reflection-based deserialization
	newValue := reflect.New(type_.Elem())
	value.Set(newValue)
	elem := newValue.Elem()
	f.refResolver.Reference(newValue)
	return s.structSerializer.Read(f, buf, type_.Elem(), elem)
}

func computeStructHash(fieldsInfo structFieldsInfo, typeResolver *typeResolver) (int32, error) {
	var sb strings.Builder

	// Iterate every field, append string by the specification
	for _, fieldInfo := range fieldsInfo {
		// 1. snake_case(field_name),
		snakeCaseName := SnakeCase(fieldInfo.name)
		sb.WriteString(snakeCaseName)
		sb.WriteString(",")

		// 2. $type_id, (for other fields, use type id UNKNOWN instead)
		var typeId TypeId
		serializer := fieldInfo.serializer
		if serializer == nil {
			typeId = UNKNOWN
		} else {
			typeId = serializer.TypeId()
			// For struct fields declared with concrete slice types,
			// use typeID = LIST uniformly for hash calculation to align cross-language behavior
			if fieldInfo.type_.Kind() == reflect.Slice {
				typeId = LIST
			}
		}
		sb.WriteString(fmt.Sprintf("%d", typeId))
		sb.WriteString(",")

		// 3. $nullable; (1 if nullable, 0 otherwise)
		nullableFlag := "0"
		if fieldInfo.referencable {
			nullableFlag = "1"
		}
		sb.WriteString(nullableFlag)
		sb.WriteString(";")
	}

	// Convert string to utf8 bytes
	hashString := sb.String()
	data := []byte(hashString)

	// Compute murmurhash3_x64_128, and use first 32 bits
	h1, _ := murmur3.Sum128WithSeed(data, 47)
	hash := int32(h1 & 0xFFFFFFFF)

	if hash == 0 {
		panic(fmt.Errorf("hash for type %v is 0", fieldsInfo))
	}
	return hash, nil
}

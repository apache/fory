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

package main

import (
	"flag"
	"fmt"
	"os"
	"runtime"

	"github.com/apache/fory/go/fory"
	"github.com/spaolacci/murmur3"
)

// ============================================================================
// Helper functions
// ============================================================================

func getDataFile() string {
	dataFile := os.Getenv("DATA_FILE")
	if dataFile == "" {
		panic("DATA_FILE environment variable not set")
	}
	return dataFile
}

func readFile(path string) []byte {
	data, err := os.ReadFile(path)
	if err != nil {
		panic(fmt.Sprintf("Failed to read file %s: %v", path, err))
	}
	return data
}

func writeFile(path string, data []byte) {
	err := os.WriteFile(path, data, 0644)
	if err != nil {
		panic(fmt.Sprintf("Failed to write file %s: %v", path, err))
	}
}

func assertEqual(expected, actual interface{}, name string) {
	if expected != actual {
		panic(fmt.Sprintf("%s: expected %v, got %v", name, expected, actual))
	}
}

// getStructValue extracts the struct value from either a struct or a pointer to struct.
// This handles the case where deserialization may return either type depending on
// reference tracking settings.
func getOneStringFieldStruct(obj interface{}) OneStringFieldStruct {
	switch v := obj.(type) {
	case OneStringFieldStruct:
		return v
	case *OneStringFieldStruct:
		return *v
	default:
		panic(fmt.Sprintf("expected OneStringFieldStruct, got %T", obj))
	}
}

func getTwoStringFieldStruct(obj interface{}) TwoStringFieldStruct {
	switch v := obj.(type) {
	case TwoStringFieldStruct:
		return v
	case *TwoStringFieldStruct:
		return *v
	default:
		panic(fmt.Sprintf("expected TwoStringFieldStruct, got %T", obj))
	}
}

func getOneEnumFieldStruct(obj interface{}) OneEnumFieldStruct {
	switch v := obj.(type) {
	case OneEnumFieldStruct:
		return v
	case *OneEnumFieldStruct:
		return *v
	default:
		panic(fmt.Sprintf("expected OneEnumFieldStruct, got %T", obj))
	}
}

func getTwoEnumFieldStruct(obj interface{}) TwoEnumFieldStruct {
	switch v := obj.(type) {
	case TwoEnumFieldStruct:
		return v
	case *TwoEnumFieldStruct:
		return *v
	default:
		panic(fmt.Sprintf("expected TwoEnumFieldStruct, got %T", obj))
	}
}

func getNullableComprehensiveSchemaConsistent(obj interface{}) NullableComprehensiveSchemaConsistent {
	switch v := obj.(type) {
	case NullableComprehensiveSchemaConsistent:
		return v
	case *NullableComprehensiveSchemaConsistent:
		return *v
	default:
		panic(fmt.Sprintf("expected NullableComprehensiveSchemaConsistent, got %T", obj))
	}
}

func getNullableComprehensiveCompatible(obj interface{}) NullableComprehensiveCompatible {
	switch v := obj.(type) {
	case NullableComprehensiveCompatible:
		return v
	case *NullableComprehensiveCompatible:
		return *v
	default:
		panic(fmt.Sprintf("expected NullableComprehensiveCompatible, got %T", obj))
	}
}

func assertEqualFloat32(expected, actual float32, name string) {
	diff := expected - actual
	if diff < 0 {
		diff = -diff
	}
	if diff > 0.0001 {
		panic(fmt.Sprintf("%s: expected %v, got %v", name, expected, actual))
	}
}

func assertEqualFloat64(expected, actual float64, name string) {
	diff := expected - actual
	if diff < 0 {
		diff = -diff
	}
	if diff > 0.000001 {
		panic(fmt.Sprintf("%s: expected %v, got %v", name, expected, actual))
	}
}

func murmurHash3_x64_128(data []byte, seed int64) (uint64, uint64) {
	h := murmur3.New128WithSeed(uint32(seed))
	h.Write(data)
	h1, h2 := h.Sum128()
	return h1, h2
}

// ============================================================================
// Test Data Structures
// ============================================================================

type Color int32

const (
	GREEN Color = 0
	RED   Color = 1
	BLUE  Color = 2
	WHITE Color = 3
)

type Item struct {
	Name string
}

// OneFieldStruct - simple struct with one int32 field for debugging
type OneFieldStruct struct {
	Value int32
}

// String field structs for schema evolution tests
type EmptyStruct struct{}

// F1 has @ForyField(id = -1, nullable = true) in Java
type OneStringFieldStruct struct {
	F1 *string `fory:"nullable"`
}

type TwoStringFieldStruct struct {
	F1 string
	F2 string
}

// Enum field structs for testing
type TestEnum int32

const (
	VALUE_A TestEnum = 0
	VALUE_B TestEnum = 1
	VALUE_C TestEnum = 2
)

type OneEnumFieldStruct struct {
	F1 TestEnum
}

type TwoEnumFieldStruct struct {
	F1 TestEnum
	F2 *TestEnum
}

type Item1 struct {
	F1 int32
	F2 int32
	F3 *int32
	F4 *int32
	F5 int32  // Non-nullable: null → 0 per test expectation
	F6 *int32 // Nullable: null stays nil
}

type SimpleStruct struct {
	F1   map[int32]float64
	F2   int32
	F3   Item
	F4   string
	F5   Color
	F6   []string
	F7   int32
	F8   int32
	Last int32
}

type StructWithList struct {
	Items []string
}

type StructWithMap struct {
	Data map[string]string
}

type MyExt struct {
	Id int32 `fory:"id"`
}

type EmptyWrapper struct {
}

type MyStruct struct {
	Id int32 `fory:"id"`
}

type VersionCheckStruct struct {
	F1 int32
	// Match Java's @ForyField(nullable = true) annotation
	F2 *string `fory:"nullable=true"`
	F3 float64
}

type Animal interface {
	GetAge() int32
	GetType() string
}

type Dog struct {
	// Match Java's @ForyField(nullable = true) annotation
	Name *string `fory:"nullable=true"`
	Age  int32
}

func (d *Dog) GetAge() int32 {
	return d.Age
}

func (d *Dog) GetType() string {
	return "Dog"
}

type Cat struct {
	Age   int32
	Lives int32
}

func (c *Cat) GetAge() int32 {
	return c.Age
}

func (c *Cat) GetType() string {
	return "Cat"
}

type AnimalListHolder struct {
	Animals []Animal
}

type AnimalMapHolder struct {
	AnimalMap map[string]Animal
}

// ============================================================================
// Nullable Field Test Types
// ============================================================================

// NullableComprehensiveSchemaConsistent matches Java's NullableComprehensiveSchemaConsistent
// for SCHEMA_CONSISTENT mode (type id 401)
type NullableComprehensiveSchemaConsistent struct {
	// Base non-nullable primitive fields
	ByteField   int8
	ShortField  int16
	IntField    int32
	LongField   int64
	FloatField  float32
	DoubleField float64
	BoolField   bool

	// Base non-nullable reference fields
	StringField string
	ListField   []string
	SetField    map[string]bool
	MapField    map[string]string

	// Nullable fields - first half (boxed types in Java)
	NullableInt   *int32   `fory:"nullable"`
	NullableLong  *int64   `fory:"nullable"`
	NullableFloat *float32 `fory:"nullable"`

	// Nullable fields - second half (reference types)
	NullableDouble *float64          `fory:"nullable"`
	NullableBool   *bool             `fory:"nullable"`
	NullableString *string           `fory:"nullable"`
	NullableList   []string          `fory:"nullable"`
	NullableSet    map[string]bool   `fory:"nullable"`
	NullableMap    map[string]string `fory:"nullable"`
}

// NullableComprehensiveCompatible - Cross-language schema evolution test struct.
// This struct has INVERTED nullability compared to Java:
// - Group 1: Nullable (pointer) in Go, Non-nullable in Java
// - Group 2: Non-nullable in Go, Nullable in Java (@ForyField(nullable=true))
// Type id 402
type NullableComprehensiveCompatible struct {
	// Group 1: Nullable in Go (pointer), Non-nullable in Java
	// Primitive fields
	ByteField   *int8    `fory:"nullable"`
	ShortField  *int16   `fory:"nullable"`
	IntField    *int32   `fory:"nullable"`
	LongField   *int64   `fory:"nullable"`
	FloatField  *float32 `fory:"nullable"`
	DoubleField *float64 `fory:"nullable"`
	BoolField   *bool    `fory:"nullable"`

	// Boxed fields - also nullable in Go
	BoxedInt    *int32   `fory:"nullable"`
	BoxedLong   *int64   `fory:"nullable"`
	BoxedFloat  *float32 `fory:"nullable"`
	BoxedDouble *float64 `fory:"nullable"`
	BoxedBool   *bool    `fory:"nullable"`

	// Reference fields - also nullable in Go
	StringField *string           `fory:"nullable"`
	ListField   []string          `fory:"nullable"`
	SetField    map[string]bool   `fory:"nullable"`
	MapField    map[string]string `fory:"nullable"`

	// Group 2: Non-nullable in Go, Nullable in Java (@ForyField(nullable=true))
	// Boxed types
	NullableInt1    int32
	NullableLong1   int64
	NullableFloat1  float32
	NullableDouble1 float64
	NullableBool1   bool

	// Reference types
	NullableString2 string
	NullableList2   []string
	NullableSet2    map[string]bool
	NullableMap2    map[string]string
}

// ============================================================================
// Custom Serializer implementing fory.ExtensionSerializer
// ============================================================================

type MyExtSerializer struct{}

func (s *MyExtSerializer) Write(buf *fory.ByteBuffer, value interface{}) error {
	myExt := value.(MyExt)
	// WriteVarint32 uses zigzag encoding (compatible with Java's writeVarint32)
	buf.WriteVarint32(myExt.Id)
	return nil
}

func (s *MyExtSerializer) Read(buf *fory.ByteBuffer) (interface{}, error) {
	var bufErr fory.Error
	// ReadVarint32 uses zigzag decoding (compatible with Java's readVarint32)
	id := buf.ReadVarint32(&bufErr)
	if bufErr.HasError() {
		return nil, bufErr.CheckError()
	}
	return MyExt{Id: id}, nil
}

// ============================================================================
// Test Cases
// ============================================================================

func testBuffer() {
	dataFile := getDataFile()
	data := readFile(dataFile)
	buf := fory.NewByteBuffer(data)
	var bufErr fory.Error

	boolVal := buf.ReadBool(&bufErr)
	assertEqual(true, boolVal, "bool")

	byteVal := buf.ReadByte(&bufErr)
	assertEqual(byte(0x7F), byteVal, "byte")

	int16Val := buf.ReadInt16(&bufErr)
	assertEqual(int16(32767), int16Val, "int16")

	int32Val := buf.ReadInt32(&bufErr)
	assertEqual(int32(2147483647), int32Val, "int32")

	int64Val := buf.ReadInt64(&bufErr)
	assertEqual(int64(9223372036854775807), int64Val, "int64")

	float32Val := buf.ReadFloat32(&bufErr)
	assertEqualFloat32(-1.1, float32Val, "float32")

	float64Val := buf.ReadFloat64(&bufErr)
	assertEqualFloat64(-1.1, float64Val, "float64")

	varUint32Val := buf.ReadVaruint32(&bufErr)
	assertEqual(uint32(100), varUint32Val, "varuint32")

	length := buf.ReadInt32(&bufErr)
	bytes := buf.ReadBinary(int(length), &bufErr)
	if string(bytes) != "ab" {
		panic(fmt.Sprintf("bytes: expected 'ab', got '%s'", string(bytes)))
	}

	outBuf := fory.NewByteBuffer(make([]byte, 0, 256))
	outBuf.WriteBool(true)
	outBuf.WriteByte_(byte(0x7F))
	outBuf.WriteInt16(32767)
	outBuf.WriteInt32(2147483647)
	outBuf.WriteInt64(9223372036854775807)
	outBuf.WriteFloat32(-1.1)
	outBuf.WriteFloat64(-1.1)
	outBuf.WriteVaruint32(100)
	outBuf.WriteInt32(2)
	outBuf.WriteBinary([]byte("ab"))

	writeFile(dataFile, outBuf.GetByteSlice(0, outBuf.WriterIndex()))
}

func testBufferVar() {
	dataFile := getDataFile()
	data := readFile(dataFile)
	buf := fory.NewByteBuffer(data)

	varInt32Values := []int32{
		-2147483648, -2147483647, -1000000, -1000, -128, -1, 0, 1,
		127, 128, 16383, 16384, 2097151, 2097152, 268435455, 268435456,
		2147483646, 2147483647,
	}
	var bufErr fory.Error
	for _, expected := range varInt32Values {
		val := buf.ReadVarint32(&bufErr)
		assertEqual(expected, val, fmt.Sprintf("varint32 %d", expected))
	}

	varUint32Values := []uint32{
		0, 1, 127, 128, 16383, 16384, 2097151, 2097152,
		268435455, 268435456, 2147483646, 2147483647,
	}
	for _, expected := range varUint32Values {
		val := buf.ReadVaruint32(&bufErr)
		assertEqual(expected, val, fmt.Sprintf("varuint32 %d", expected))
	}

	varUint64Values := []uint64{
		0, 1, 127, 128, 16383, 16384, 2097151, 2097152,
		268435455, 268435456, 34359738367, 34359738368,
		4398046511103, 4398046511104, 562949953421311, 562949953421312,
		72057594037927935, 72057594037927936, 9223372036854775807,
	}
	for _, expected := range varUint64Values {
		val := buf.ReadVaruint64(&bufErr)
		assertEqual(expected, val, fmt.Sprintf("varuint64 %d", expected))
	}

	varInt64Values := []int64{
		-9223372036854775808, -9223372036854775807, -1000000000000,
		-1000000, -1000, -128, -1, 0, 1, 127, 1000, 1000000,
		1000000000000, 9223372036854775806, 9223372036854775807,
	}
	for _, expected := range varInt64Values {
		val := buf.ReadVarint64(&bufErr)
		assertEqual(expected, val, fmt.Sprintf("varint64 %d", expected))
	}

	outBuf := fory.NewByteBuffer(make([]byte, 0, 512))
	for _, val := range varInt32Values {
		outBuf.WriteVarint32(val)
	}
	for _, val := range varUint32Values {
		outBuf.WriteVaruint32(val)
	}
	for _, val := range varUint64Values {
		outBuf.WriteVaruint64(val)
	}
	for _, val := range varInt64Values {
		outBuf.WriteVarint64(val)
	}

	writeFile(dataFile, outBuf.GetByteSlice(0, outBuf.WriterIndex()))
}

func testMurmurHash3() {
	dataFile := getDataFile()
	data := readFile(dataFile)
	buf := fory.NewByteBuffer(data)
	var bufErr fory.Error

	if len(data) == 32 {
		// First round: read Guava hashes (32 bytes), compute and write back
		_ = buf.ReadInt64(&bufErr)
		_ = buf.ReadInt64(&bufErr)
		_ = buf.ReadInt64(&bufErr)
		_ = buf.ReadInt64(&bufErr)

		h1_1, h1_2 := murmurHash3_x64_128([]byte{1, 2, 8}, 47)
		h2_1, h2_2 := murmurHash3_x64_128([]byte("01234567890123456789"), 47)

		outBuf := fory.NewByteBuffer(make([]byte, 0, 32))
		outBuf.WriteInt64(int64(h1_1))
		outBuf.WriteInt64(int64(h1_2))
		outBuf.WriteInt64(int64(h2_1))
		outBuf.WriteInt64(int64(h2_2))

		writeFile(dataFile, outBuf.GetByteSlice(0, outBuf.WriterIndex()))
	} else if len(data) == 16 {
		// Second round: read MurmurHash3 hashes (16 bytes), verify
		h1 := buf.ReadInt64(&bufErr)
		h2 := buf.ReadInt64(&bufErr)

		// Compute expected values
		expected1, expected2 := murmurHash3_x64_128([]byte{1, 2, 8}, 47)

		if h1 != int64(expected1) || h2 != int64(expected2) {
			panic(fmt.Sprintf("MurmurHash3 mismatch: got (%d, %d), expected (%d, %d)",
				h1, h2, int64(expected1), int64(expected2)))
		}

		// Write empty or same data to indicate success
		writeFile(dataFile, []byte{})
	} else {
		panic(fmt.Sprintf("unexpected data length: %d", len(data)))
	}
}

func testStringSerializer() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	f := fory.New(fory.WithXlang(true), fory.WithCompatible(true))

	testStrings := []string{
		"ab",
		"Rust123",
		"Çüéâäàåçêëèïî",
		"こんにちは",
		"Привет",
		"𝄞🎵🎶",
		"Hello, 世界",
	}

	buf := fory.NewByteBuffer(data)
	for range testStrings {
		var result string
		err := f.DeserializeWithCallbackBuffers(buf, &result, nil)
		if err != nil {
			panic(fmt.Sprintf("Failed to deserialize: %v", err))
		}
	}

	var outData []byte
	for _, s := range testStrings {
		serialized, err := f.Serialize(s)
		if err != nil {
			panic(fmt.Sprintf("Failed to serialize: %v", err))
		}
		outData = append(outData, serialized...)
	}

	writeFile(dataFile, outData)
}

func testCrossLanguageSerializer() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	f := fory.New(fory.WithXlang(true), fory.WithCompatible(true))
	// Use numeric ID 101 to match Java's fory.register(Color.class, 101)
	f.RegisterEnum(Color(0), 101)

	vals := make([]interface{}, 0)
	buf := fory.NewByteBuffer(data)
	for buf.ReaderIndex() < len(data) {
		var val interface{}
		err := f.DeserializeWithCallbackBuffers(buf, &val, nil)
		if err != nil {
			panic(fmt.Sprintf("Failed to deserialize at index %d: %v", len(vals), err))
		}
		vals = append(vals, val)
	}

	var outData []byte
	for _, val := range vals {
		serialized, err := f.Serialize(val)
		if err != nil {
			panic(fmt.Sprintf("Failed to serialize: %v", err))
		}
		outData = append(outData, serialized...)
	}

	writeFile(dataFile, outData)
}

func testSimpleStruct() {
	dataFile := getDataFile()
	data := readFile(dataFile)
	f := fory.New(fory.WithXlang(true), fory.WithCompatible(true))
	// Use numeric IDs to match Java's fory.register(Color.class, 101), etc.
	f.RegisterEnum(Color(0), 101)
	f.Register(Item{}, 102)
	f.Register(SimpleStruct{}, 103)

	var obj SimpleStruct
	if err := f.Deserialize(data, &obj); err != nil {
		panic(fmt.Sprintf("Failed to deserialize: %v", err))
	}
	fmt.Printf("Deserialized obj: %+v\n", obj)
	serialized, err := f.Serialize(obj)
	if err != nil {
		panic(fmt.Sprintf("Failed to serialize: %v", err))
	}
	writeFile(dataFile, serialized)
}

func testNamedSimpleStruct() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	f := fory.New(fory.WithXlang(true), fory.WithCompatible(true))
	// Use namespace "demo" to match Java's fory.register(Color.class, "demo", "color"), etc.
	f.RegisterEnumByName(Color(0), "demo.color")
	f.RegisterByName(Item{}, "demo.item")
	f.RegisterByName(SimpleStruct{}, "demo.simple_struct")

	var obj SimpleStruct
	if err := f.Deserialize(data, &obj); err != nil {
		panic(fmt.Sprintf("Failed to deserialize: %v", err))
	}
	fmt.Printf("Deserialized obj: %+v\n", obj)
	serialized, err := f.Serialize(obj)
	if err != nil {
		panic(fmt.Sprintf("Failed to serialize: %v", err))
	}
	writeFile(dataFile, serialized)
}

func testList() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	f := fory.New(fory.WithXlang(true), fory.WithCompatible(true))
	// Use numeric ID 102 to match Java's fory.register(Item.class, 102)
	f.Register(Item{}, 102)

	buf := fory.NewByteBuffer(data)
	lists := make([]interface{}, 4)

	for i := 0; i < 4; i++ {
		var obj interface{}
		err := f.DeserializeWithCallbackBuffers(buf, &obj, nil)
		if err != nil {
			panic(fmt.Sprintf("Failed to deserialize list %d: %v", i, err))
		}
		lists[i] = obj
	}

	var outData []byte
	for i, list := range lists {
		serialized, err := f.Serialize(list)
		if err != nil {
			panic(fmt.Sprintf("Failed to serialize list %d: %v", i, err))
		}
		outData = append(outData, serialized...)
	}

	writeFile(dataFile, outData)
}

func testMap() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	f := fory.New(fory.WithXlang(true), fory.WithCompatible(true))
	// Use numeric ID 102 to match Java's fory.register(Item.class, 102)
	f.Register(Item{}, 102)

	buf := fory.NewByteBuffer(data)
	maps := make([]interface{}, 2)

	for i := 0; i < 2; i++ {
		var obj interface{}
		err := f.DeserializeWithCallbackBuffers(buf, &obj, nil)
		if err != nil {
			panic(fmt.Sprintf("Failed to deserialize map %d: %v", i, err))
		}
		maps[i] = obj
	}

	var outData []byte
	for _, m := range maps {
		serialized, err := f.Serialize(m)
		if err != nil {
			panic(fmt.Sprintf("Failed to serialize: %v", err))
		}
		outData = append(outData, serialized...)
	}

	writeFile(dataFile, outData)
}

func testInteger() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	f := fory.New(fory.WithXlang(true), fory.WithCompatible(true))
	// Use numeric ID 101 to match Java's fory.register(Item1.class, 101)
	f.Register(Item1{}, 101)

	buf := fory.NewByteBuffer(data)

	// DeserializeWithCallbackBuffers Item1 struct
	var item1 Item1
	err := f.DeserializeWithCallbackBuffers(buf, &item1, nil)
	if err != nil {
		panic(fmt.Sprintf("Failed to deserialize Item1: %v", err))
	}

	// DeserializeWithCallbackBuffers standalone values with specific types matching Rust:
	// f1: int32 (non-nullable)
	// f2: int32 (non-nullable)
	// f3: *int32 (nullable)
	// f4: *int32 (nullable)
	// f5: int32 (non-nullable) - null becomes 0
	// f6: *int32 (nullable) - null stays nil
	var f1, f2 int32
	var f3, f4, f6 *int32
	var f5 int32

	err = f.DeserializeWithCallbackBuffers(buf, &f1, nil)
	if err != nil {
		panic(fmt.Sprintf("Failed to deserialize f1: %v", err))
	}
	err = f.DeserializeWithCallbackBuffers(buf, &f2, nil)
	if err != nil {
		panic(fmt.Sprintf("Failed to deserialize f2: %v", err))
	}
	err = f.DeserializeWithCallbackBuffers(buf, &f3, nil)
	if err != nil {
		panic(fmt.Sprintf("Failed to deserialize f3: %v", err))
	}
	err = f.DeserializeWithCallbackBuffers(buf, &f4, nil)
	if err != nil {
		panic(fmt.Sprintf("Failed to deserialize f4: %v", err))
	}
	err = f.DeserializeWithCallbackBuffers(buf, &f5, nil)
	if err != nil {
		panic(fmt.Sprintf("Failed to deserialize f5: %v", err))
	}
	err = f.DeserializeWithCallbackBuffers(buf, &f6, nil)
	if err != nil {
		panic(fmt.Sprintf("Failed to deserialize f6: %v", err))
	}

	// SerializeWithCallback back
	var outData []byte
	serialized, _ := f.Serialize(&item1)
	outData = append(outData, serialized...)
	serialized, _ = f.Serialize(f1)
	outData = append(outData, serialized...)
	serialized, _ = f.Serialize(f2)
	outData = append(outData, serialized...)
	serialized, _ = f.Serialize(f3)
	outData = append(outData, serialized...)
	serialized, _ = f.Serialize(f4)
	outData = append(outData, serialized...)
	serialized, _ = f.Serialize(f5)
	outData = append(outData, serialized...)
	serialized, _ = f.Serialize(f6)
	outData = append(outData, serialized...)

	writeFile(dataFile, outData)
}

func testItem() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	f := fory.New(fory.WithXlang(true), fory.WithCompatible(true))
	// Use numeric ID 102 to match Java's fory.register(Item.class, 102)
	f.Register(Item{}, 102)

	buf := fory.NewByteBuffer(data)
	items := make([]interface{}, 3)

	for i := 0; i < 3; i++ {
		var obj interface{}
		err := f.DeserializeWithCallbackBuffers(buf, &obj, nil)
		if err != nil {
			panic(fmt.Sprintf("Failed to deserialize item %d: %v", i, err))
		}
		items[i] = obj
	}

	var outData []byte
	for _, item := range items {
		serialized, err := f.Serialize(item)
		if err != nil {
			panic(fmt.Sprintf("Failed to serialize: %v", err))
		}
		outData = append(outData, serialized...)
	}

	writeFile(dataFile, outData)
}

func testColor() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	f := fory.New(fory.WithXlang(true), fory.WithCompatible(true))
	// Use numeric ID 101 to match Java's fory.register(Color.class, 101)
	f.RegisterEnum(Color(0), 101)

	buf := fory.NewByteBuffer(data)
	colors := make([]interface{}, 4)

	for i := 0; i < 4; i++ {
		var obj interface{}
		err := f.DeserializeWithCallbackBuffers(buf, &obj, nil)
		if err != nil {
			panic(fmt.Sprintf("Failed to deserialize color %d: %v", i, err))
		}
		colors[i] = obj
	}

	var outData []byte
	for _, color := range colors {
		serialized, err := f.Serialize(color)
		if err != nil {
			panic(fmt.Sprintf("Failed to serialize: %v", err))
		}
		outData = append(outData, serialized...)
	}

	writeFile(dataFile, outData)
}

func testStructWithList() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	f := fory.New(fory.WithXlang(true), fory.WithCompatible(true))
	// Use numeric ID 201 to match Java's fory.register(StructWithList.class, 201)
	f.Register(StructWithList{}, 201)

	// Java serializes two objects to the same buffer, so we need to deserialize twice
	readBuf := fory.NewByteBuffer(data)

	var obj1 StructWithList
	if err := f.DeserializeFrom(readBuf, &obj1); err != nil {
		panic(fmt.Sprintf("Failed to deserialize first object: %v", err))
	}

	var obj2 StructWithList
	if err := f.DeserializeFrom(readBuf, &obj2); err != nil {
		panic(fmt.Sprintf("Failed to deserialize second object: %v", err))
	}

	// Java reads two objects from the same buffer, so we need to serialize twice
	writeBuf := fory.NewByteBuffer(nil)

	err := f.SerializeTo(writeBuf, obj1)
	if err != nil {
		panic(fmt.Sprintf("Failed to serialize first object: %v", err))
	}

	err = f.SerializeTo(writeBuf, obj2)
	if err != nil {
		panic(fmt.Sprintf("Failed to serialize second object: %v", err))
	}

	writeFile(dataFile, writeBuf.Bytes())
}

func testStructWithMap() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	f := fory.New(fory.WithXlang(true), fory.WithCompatible(true))
	// Use numeric ID 202 to match Java's fory.register(StructWithMap.class, 202)
	f.Register(StructWithMap{}, 202)

	// Java serializes two objects to the same buffer, so we need to deserialize twice
	readBuf := fory.NewByteBuffer(data)

	var obj1 StructWithMap
	if err := f.DeserializeFrom(readBuf, &obj1); err != nil {
		panic(fmt.Sprintf("Failed to deserialize first object: %v", err))
	}

	var obj2 StructWithMap
	if err := f.DeserializeFrom(readBuf, &obj2); err != nil {
		panic(fmt.Sprintf("Failed to deserialize second object: %v", err))
	}

	// Java reads two objects from the same buffer, so we need to serialize twice
	writeBuf := fory.NewByteBuffer(nil)

	err := f.SerializeTo(writeBuf, obj1)
	if err != nil {
		panic(fmt.Sprintf("Failed to serialize first object: %v", err))
	}

	err = f.SerializeTo(writeBuf, obj2)
	if err != nil {
		panic(fmt.Sprintf("Failed to serialize second object: %v", err))
	}

	writeFile(dataFile, writeBuf.Bytes())
}

func testSkipIdCustom() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	f := fory.New(fory.WithXlang(true), fory.WithCompatible(true))
	// Use numeric IDs to match Java's registration:
	// fory2.register(MyExt.class, 103)
	// fory2.register(EmptyWrapper.class, 104)
	f.RegisterExtensionType(MyExt{}, 103, &MyExtSerializer{})
	f.Register(EmptyWrapper{}, 104)

	var obj EmptyWrapper
	if err := f.Deserialize(data, &obj); err != nil {
		panic(fmt.Sprintf("Failed to deserialize: %v", err))
	}

	serialized, err := f.Serialize(obj)
	if err != nil {
		panic(fmt.Sprintf("Failed to serialize: %v", err))
	}

	writeFile(dataFile, serialized)
}

func testSkipNameCustom() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	f := fory.New(fory.WithXlang(true), fory.WithCompatible(true))
	f.RegisterExtensionTypeByName(MyExt{}, "my_ext", &MyExtSerializer{})
	f.RegisterByName(EmptyWrapper{}, "my_wrapper")

	var obj EmptyWrapper
	if err := f.Deserialize(data, &obj); err != nil {
		panic(fmt.Sprintf("Failed to deserialize: %v", err))
	}

	serialized, err := f.Serialize(obj)
	if err != nil {
		panic(fmt.Sprintf("Failed to serialize: %v", err))
	}

	writeFile(dataFile, serialized)
}

func testConsistentNamed() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	// Java uses SCHEMA_CONSISTENT mode which doesn't enable metaShare
	// So Go should NOT expect meta offset field
	f := fory.New(fory.WithXlang(true), fory.WithCompatible(false))
	f.RegisterEnumByName(Color(0), "color")
	f.RegisterByName(MyStruct{}, "my_struct")
	// MyExt uses an extension serializer in Java (MyExtSerializer), so register as extension type
	f.RegisterExtensionTypeByName(MyExt{}, "my_ext", &MyExtSerializer{})

	buf := fory.NewByteBuffer(data)
	values := make([]interface{}, 9)

	for i := 0; i < 9; i++ {
		var obj interface{}
		err := f.DeserializeWithCallbackBuffers(buf, &obj, nil)
		fmt.Printf("Deserialized value %d: %+v\n", i, obj)
		if err != nil {
			panic(fmt.Sprintf("Failed to deserialize value %d: %v", i, err))
		}
		values[i] = obj
	}

	var outData []byte
	for i, val := range values {
		serialized, err := f.Serialize(val)
		fmt.Printf("Serialized value %d: %+v, size: %d\n", i, val, len(serialized))
		if err != nil {
			panic(fmt.Sprintf("Failed to serialize: %v", err))
		}
		outData = append(outData, serialized...)
	}

	writeFile(dataFile, outData)
}

func testStructVersionCheck() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	f := fory.New(fory.WithXlang(true), fory.WithCompatible(false))
	// Use numeric ID 201 to match Java's fory.register(VersionCheckStruct.class, 201)
	f.Register(VersionCheckStruct{}, 201)

	var obj VersionCheckStruct
	if err := f.Deserialize(data, &obj); err != nil {
		panic(fmt.Sprintf("Failed to deserialize: %v", err))
	}

	serialized, err := f.Serialize(obj)
	if err != nil {
		panic(fmt.Sprintf("Failed to serialize: %v", err))
	}

	writeFile(dataFile, serialized)
}

func testPolymorphicList() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	f := fory.New(fory.WithXlang(true), fory.WithCompatible(true))
	// Use numeric IDs to match Java's registration: Dog=302, Cat=303, AnimalListHolder=304
	f.Register(&Dog{}, 302)
	f.Register(&Cat{}, 303)
	f.Register(AnimalListHolder{}, 304)

	buf := fory.NewByteBuffer(data)
	values := make([]interface{}, 2)

	for i := 0; i < 2; i++ {
		var obj interface{}
		err := f.DeserializeWithCallbackBuffers(buf, &obj, nil)
		fmt.Printf("Deserialized: %v", obj)
		if err != nil {
			panic(fmt.Sprintf("Failed to deserialize value %d: %v", i, err))
		}
		values[i] = obj
	}

	var outData []byte
	for _, val := range values {
		serialized, err := f.Serialize(val)
		if err != nil {
			panic(fmt.Sprintf("Failed to serialize: %v", err))
		}
		outData = append(outData, serialized...)
	}

	writeFile(dataFile, outData)
}

func testPolymorphicMap() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	f := fory.New(fory.WithXlang(true), fory.WithCompatible(true))
	// Use numeric IDs to match Java's registration: Dog=302, Cat=303, AnimalMapHolder=305
	f.Register(&Dog{}, 302)
	f.Register(&Cat{}, 303)
	f.Register(AnimalMapHolder{}, 305)

	buf := fory.NewByteBuffer(data)
	values := make([]interface{}, 2)

	for i := 0; i < 2; i++ {
		var obj interface{}
		err := f.DeserializeWithCallbackBuffers(buf, &obj, nil)
		if err != nil {
			panic(fmt.Sprintf("Failed to deserialize value %d: %v", i, err))
		}
		values[i] = obj
	}

	var outData []byte
	for _, val := range values {
		serialized, err := f.Serialize(val)
		if err != nil {
			panic(fmt.Sprintf("Failed to serialize: %v", err))
		}
		outData = append(outData, serialized...)
	}

	writeFile(dataFile, outData)
}

func testOneFieldStructCompatible() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	// Debug: Print Java serialized bytes
	fmt.Printf("Java serialized (compatible) %d bytes:\n  ", len(data))
	for _, b := range data {
		fmt.Printf("%02x ", b)
	}
	fmt.Println()

	f := fory.New(fory.WithXlang(true), fory.WithCompatible(true))
	// Register with numeric ID 200 to match Java's fory.register(OneFieldStruct.class, 200)
	f.Register(OneFieldStruct{}, 200)

	// Parse header and meta offset manually for debugging
	if len(data) >= 8 {
		metaOffset := int32(data[4]) | int32(data[5])<<8 | int32(data[6])<<16 | int32(data[7])<<24
		fmt.Printf("DEBUG: metaOffset = %d (0x%x)\n", metaOffset, metaOffset)
		if metaOffset > 0 && int(8+metaOffset) < len(data) {
			typeDefStart := 8 + metaOffset
			fmt.Printf("DEBUG: TypeDefs start at byte %d\n", typeDefStart)
			fmt.Printf("DEBUG: First bytes of TypeDefs section: ")
			for i := 0; i < 10 && int(typeDefStart)+i < len(data); i++ {
				fmt.Printf("%02x ", data[int(typeDefStart)+i])
			}
			fmt.Println()
		}
	}

	buf := fory.NewByteBuffer(data)
	var obj interface{}
	err := f.DeserializeWithCallbackBuffers(buf, &obj, nil)
	if err != nil {
		panic(fmt.Sprintf("Failed to deserialize: %v", err))
	}

	result := obj.(OneFieldStruct)
	assertEqual(int32(42), result.Value, "value")

	serialized, err := f.Serialize(result)
	if err != nil {
		panic(fmt.Sprintf("Failed to serialize: %v", err))
	}

	// Debug: Print Go serialized bytes
	fmt.Printf("Go serialized (compatible) %d bytes:\n  ", len(serialized))
	for _, b := range serialized {
		fmt.Printf("%02x ", b)
	}
	fmt.Println()

	writeFile(dataFile, serialized)
}

func testOneFieldStructSchema() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	// Debug: Print Java serialized bytes
	fmt.Printf("Java serialized (schema) %d bytes:\n  ", len(data))
	for _, b := range data {
		fmt.Printf("%02x ", b)
	}
	fmt.Println()

	f := fory.New(fory.WithXlang(true), fory.WithCompatible(false))
	f.Register(OneFieldStruct{}, 200)

	buf := fory.NewByteBuffer(data)
	var obj interface{}
	err := f.DeserializeWithCallbackBuffers(buf, &obj, nil)
	if err != nil {
		panic(fmt.Sprintf("Failed to deserialize: %v", err))
	}

	result := obj.(OneFieldStruct)
	assertEqual(int32(42), result.Value, "value")

	serialized, err := f.Serialize(result)
	if err != nil {
		panic(fmt.Sprintf("Failed to serialize: %v", err))
	}

	// Debug: Print Go serialized bytes
	fmt.Printf("Go serialized (schema) %d bytes:\n  ", len(serialized))
	for _, b := range serialized {
		fmt.Printf("%02x ", b)
	}
	fmt.Println()

	writeFile(dataFile, serialized)
}

func testOneStringFieldSchemaConsistent() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	f := fory.New(fory.WithXlang(true), fory.WithCompatible(false))
	f.Register(OneStringFieldStruct{}, 200)

	buf := fory.NewByteBuffer(data)
	var obj interface{}
	err := f.DeserializeWithCallbackBuffers(buf, &obj, nil)
	if err != nil {
		panic(fmt.Sprintf("Failed to deserialize: %v", err))
	}

	result := getOneStringFieldStruct(obj)
	if result.F1 == nil || *result.F1 != "hello" {
		panic(fmt.Sprintf("f1 mismatch: expected 'hello', got '%v'", result.F1))
	}

	serialized, err := f.Serialize(result)
	if err != nil {
		panic(fmt.Sprintf("Failed to serialize: %v", err))
	}

	writeFile(dataFile, serialized)
}

func testOneStringFieldCompatible() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	f := fory.New(fory.WithXlang(true), fory.WithCompatible(true))
	f.Register(OneStringFieldStruct{}, 200)

	buf := fory.NewByteBuffer(data)
	var obj interface{}
	err := f.DeserializeWithCallbackBuffers(buf, &obj, nil)
	if err != nil {
		panic(fmt.Sprintf("Failed to deserialize: %v", err))
	}

	result := getOneStringFieldStruct(obj)
	if result.F1 == nil || *result.F1 != "hello" {
		panic(fmt.Sprintf("f1 mismatch: expected 'hello', got '%v'", result.F1))
	}

	serialized, err := f.Serialize(result)
	if err != nil {
		panic(fmt.Sprintf("Failed to serialize: %v", err))
	}

	writeFile(dataFile, serialized)
}

func testTwoStringFieldCompatible() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	f := fory.New(fory.WithXlang(true), fory.WithCompatible(true))
	f.Register(TwoStringFieldStruct{}, 201)

	buf := fory.NewByteBuffer(data)
	var obj interface{}
	err := f.DeserializeWithCallbackBuffers(buf, &obj, nil)
	if err != nil {
		panic(fmt.Sprintf("Failed to deserialize: %v", err))
	}

	result := getTwoStringFieldStruct(obj)
	assertEqual("first", result.F1, "f1")
	assertEqual("second", result.F2, "f2")

	serialized, err := f.Serialize(result)
	if err != nil {
		panic(fmt.Sprintf("Failed to serialize: %v", err))
	}

	writeFile(dataFile, serialized)
}

func testSchemaEvolutionCompatible() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	// Read TwoStringFieldStruct data, deserialize as EmptyStruct
	f := fory.New(fory.WithXlang(true), fory.WithCompatible(true))
	f.Register(EmptyStruct{}, 200)

	buf := fory.NewByteBuffer(data)
	var obj interface{}
	err := f.DeserializeWithCallbackBuffers(buf, &obj, nil)
	if err != nil {
		panic(fmt.Sprintf("Failed to deserialize as EmptyStruct: %v", err))
	}

	// SerializeWithCallback back as EmptyStruct
	serialized, err := f.Serialize(obj)
	if err != nil {
		panic(fmt.Sprintf("Failed to serialize: %v", err))
	}

	writeFile(dataFile, serialized)
}

func testSchemaEvolutionCompatibleReverse() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	// Read OneStringFieldStruct data, deserialize as TwoStringFieldStruct
	// Missing f2 field will be Go's zero value (empty string)
	f := fory.New(fory.WithXlang(true), fory.WithCompatible(true))
	f.Register(TwoStringFieldStruct{}, 200)

	buf := fory.NewByteBuffer(data)
	var obj interface{}
	err := f.DeserializeWithCallbackBuffers(buf, &obj, nil)
	if err != nil {
		panic(fmt.Sprintf("Failed to deserialize as TwoStringFieldStruct: %v", err))
	}

	result := getTwoStringFieldStruct(obj)
	assertEqual("only_one", result.F1, "f1")
	// f2 should be empty string since it wasn't in the source data
	assertEqual("", result.F2, "f2")

	// SerializeWithCallback back
	serialized, err := f.Serialize(result)
	if err != nil {
		panic(fmt.Sprintf("Failed to serialize: %v", err))
	}

	writeFile(dataFile, serialized)
}

// Enum field tests
func testOneEnumFieldSchemaConsistent() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	f := fory.New(fory.WithXlang(true), fory.WithCompatible(false))
	f.RegisterEnum(TestEnum(0), 210)
	f.Register(OneEnumFieldStruct{}, 211)

	buf := fory.NewByteBuffer(data)
	var obj interface{}
	err := f.DeserializeWithCallbackBuffers(buf, &obj, nil)
	if err != nil {
		panic(fmt.Sprintf("Failed to deserialize: %v", err))
	}

	result := getOneEnumFieldStruct(obj)
	if result.F1 != VALUE_B {
		panic(fmt.Sprintf("Expected VALUE_B (1), got %v", result.F1))
	}

	serialized, err := f.Serialize(result)
	if err != nil {
		panic(fmt.Sprintf("Failed to serialize: %v", err))
	}

	writeFile(dataFile, serialized)
}

func testOneEnumFieldCompatible() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	f := fory.New(fory.WithXlang(true), fory.WithCompatible(true))
	f.RegisterEnum(TestEnum(0), 210)
	f.Register(OneEnumFieldStruct{}, 211)

	buf := fory.NewByteBuffer(data)
	var obj interface{}
	err := f.DeserializeWithCallbackBuffers(buf, &obj, nil)
	if err != nil {
		panic(fmt.Sprintf("Failed to deserialize: %v", err))
	}

	result := getOneEnumFieldStruct(obj)
	if result.F1 != VALUE_A {
		panic(fmt.Sprintf("Expected VALUE_A (0), got %v", result.F1))
	}

	serialized, err := f.Serialize(result)
	if err != nil {
		panic(fmt.Sprintf("Failed to serialize: %v", err))
	}

	writeFile(dataFile, serialized)
}

func testTwoEnumFieldCompatible() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	f := fory.New(fory.WithXlang(true), fory.WithCompatible(true))
	f.RegisterEnum(TestEnum(0), 210)
	f.Register(TwoEnumFieldStruct{}, 212)

	buf := fory.NewByteBuffer(data)
	var obj interface{}
	err := f.DeserializeWithCallbackBuffers(buf, &obj, nil)
	if err != nil {
		panic(fmt.Sprintf("Failed to deserialize: %v", err))
	}

	result := getTwoEnumFieldStruct(obj)
	if result.F1 != VALUE_A {
		panic(fmt.Sprintf("Expected F1=VALUE_A (0), got %v", result.F1))
	}
	if result.F2 == nil || *result.F2 != VALUE_C {
		panic(fmt.Sprintf("Expected F2=VALUE_C (2), got %v", result.F2))
	}

	serialized, err := f.Serialize(result)
	if err != nil {
		panic(fmt.Sprintf("Failed to serialize: %v", err))
	}

	writeFile(dataFile, serialized)
}

func testEnumSchemaEvolutionCompatible() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	// Read TwoEnumFieldStruct data, deserialize as EmptyStruct
	f := fory.New(fory.WithXlang(true), fory.WithCompatible(true))
	f.RegisterEnum(TestEnum(0), 210)
	f.Register(EmptyStruct{}, 211)

	buf := fory.NewByteBuffer(data)
	var obj interface{}
	err := f.DeserializeWithCallbackBuffers(buf, &obj, nil)
	if err != nil {
		panic(fmt.Sprintf("Failed to deserialize as EmptyStruct: %v", err))
	}

	// SerializeWithCallback back as EmptyStruct
	serialized, err := f.Serialize(obj)
	if err != nil {
		panic(fmt.Sprintf("Failed to serialize: %v", err))
	}

	writeFile(dataFile, serialized)
}

func testEnumSchemaEvolutionCompatibleReverse() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	// Read OneEnumFieldStruct data, deserialize as TwoEnumFieldStruct
	// Missing f2 field will be Go's zero value (nil for pointer)
	f := fory.New(fory.WithXlang(true), fory.WithCompatible(true))
	f.RegisterEnum(TestEnum(0), 210)
	f.Register(TwoEnumFieldStruct{}, 211)

	buf := fory.NewByteBuffer(data)
	var obj interface{}
	err := f.DeserializeWithCallbackBuffers(buf, &obj, nil)
	if err != nil {
		panic(fmt.Sprintf("Failed to deserialize as TwoEnumFieldStruct: %v", err))
	}

	result := getTwoEnumFieldStruct(obj)
	if result.F1 != VALUE_C {
		panic(fmt.Sprintf("Expected F1=VALUE_C (2), got %v", result.F1))
	}
	// f2 should be nil since it wasn't in the source data
	if result.F2 != nil {
		panic(fmt.Sprintf("Expected F2=nil, got %v", result.F2))
	}

	// SerializeWithCallback back
	serialized, err := f.Serialize(result)
	if err != nil {
		panic(fmt.Sprintf("Failed to serialize: %v", err))
	}

	writeFile(dataFile, serialized)
}

// ============================================================================
// Nullable Field Tests
// ============================================================================

func testNullableFieldSchemaConsistentNotNull() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	f := fory.New(fory.WithXlang(true), fory.WithCompatible(false))
	f.Register(NullableComprehensiveSchemaConsistent{}, 401)

	buf := fory.NewByteBuffer(data)
	var obj interface{}
	err := f.DeserializeWithCallbackBuffers(buf, &obj, nil)
	if err != nil {
		panic(fmt.Sprintf("Failed to deserialize: %v", err))
	}

	result := getNullableComprehensiveSchemaConsistent(obj)

	// Verify base non-nullable primitive fields
	assertEqual(int8(1), result.ByteField, "ByteField")
	assertEqual(int16(2), result.ShortField, "ShortField")
	assertEqual(int32(42), result.IntField, "IntField")
	assertEqual(int64(123456789), result.LongField, "LongField")
	assertEqualFloat32(1.5, result.FloatField, "FloatField")
	assertEqualFloat64(2.5, result.DoubleField, "DoubleField")
	assertEqual(true, result.BoolField, "BoolField")

	// Verify base non-nullable reference fields
	assertEqual("hello", result.StringField, "StringField")
	if len(result.ListField) != 3 || result.ListField[0] != "a" || result.ListField[1] != "b" || result.ListField[2] != "c" {
		panic(fmt.Sprintf("ListField mismatch: expected [a, b, c], got %v", result.ListField))
	}
	if len(result.SetField) != 2 || !result.SetField["x"] || !result.SetField["y"] {
		panic(fmt.Sprintf("SetField mismatch: expected {x, y}, got %v", result.SetField))
	}
	if len(result.MapField) != 2 || result.MapField["key1"] != "value1" || result.MapField["key2"] != "value2" {
		panic(fmt.Sprintf("MapField mismatch: expected {key1:value1, key2:value2}, got %v", result.MapField))
	}

	// Verify nullable fields - first half (boxed types)
	if result.NullableInt == nil || *result.NullableInt != 100 {
		panic(fmt.Sprintf("NullableInt mismatch: expected 100, got %v", result.NullableInt))
	}
	if result.NullableLong == nil || *result.NullableLong != 200 {
		panic(fmt.Sprintf("NullableLong mismatch: expected 200, got %v", result.NullableLong))
	}
	if result.NullableFloat == nil {
		panic("NullableFloat mismatch: expected 1.5, got nil")
	}
	assertEqualFloat32(1.5, *result.NullableFloat, "NullableFloat")

	// Verify nullable fields - second half (reference types)
	if result.NullableDouble == nil {
		panic("NullableDouble mismatch: expected 2.5, got nil")
	}
	assertEqualFloat64(2.5, *result.NullableDouble, "NullableDouble")
	if result.NullableBool == nil || *result.NullableBool != false {
		panic(fmt.Sprintf("NullableBool mismatch: expected false, got %v", result.NullableBool))
	}
	if result.NullableString == nil || *result.NullableString != "nullable_value" {
		panic(fmt.Sprintf("NullableString mismatch: expected 'nullable_value', got %v", result.NullableString))
	}
	if len(result.NullableList) != 2 || result.NullableList[0] != "p" || result.NullableList[1] != "q" {
		panic(fmt.Sprintf("NullableList mismatch: expected [p, q], got %v", result.NullableList))
	}
	if len(result.NullableSet) != 2 || !result.NullableSet["m"] || !result.NullableSet["n"] {
		panic(fmt.Sprintf("NullableSet mismatch: expected {m, n}, got %v", result.NullableSet))
	}
	if len(result.NullableMap) != 1 || result.NullableMap["nk1"] != "nv1" {
		panic(fmt.Sprintf("NullableMap mismatch: expected {nk1:nv1}, got %v", result.NullableMap))
	}

	serialized, err := f.Serialize(result)
	if err != nil {
		panic(fmt.Sprintf("Failed to serialize: %v", err))
	}

	writeFile(dataFile, serialized)
}

func testNullableFieldSchemaConsistentNull() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	f := fory.New(fory.WithXlang(true), fory.WithCompatible(false))
	f.Register(NullableComprehensiveSchemaConsistent{}, 401)

	buf := fory.NewByteBuffer(data)
	var obj interface{}
	err := f.DeserializeWithCallbackBuffers(buf, &obj, nil)
	if err != nil {
		panic(fmt.Sprintf("Failed to deserialize: %v", err))
	}

	result := getNullableComprehensiveSchemaConsistent(obj)

	// Verify base non-nullable primitive fields
	assertEqual(int8(1), result.ByteField, "ByteField")
	assertEqual(int16(2), result.ShortField, "ShortField")
	assertEqual(int32(42), result.IntField, "IntField")
	assertEqual(int64(123456789), result.LongField, "LongField")
	assertEqualFloat32(1.5, result.FloatField, "FloatField")
	assertEqualFloat64(2.5, result.DoubleField, "DoubleField")
	assertEqual(true, result.BoolField, "BoolField")

	// Verify base non-nullable reference fields
	assertEqual("hello", result.StringField, "StringField")
	if len(result.ListField) != 3 || result.ListField[0] != "a" || result.ListField[1] != "b" || result.ListField[2] != "c" {
		panic(fmt.Sprintf("ListField mismatch: expected [a, b, c], got %v", result.ListField))
	}
	if len(result.SetField) != 2 || !result.SetField["x"] || !result.SetField["y"] {
		panic(fmt.Sprintf("SetField mismatch: expected {x, y}, got %v", result.SetField))
	}
	if len(result.MapField) != 2 || result.MapField["key1"] != "value1" || result.MapField["key2"] != "value2" {
		panic(fmt.Sprintf("MapField mismatch: expected {key1:value1, key2:value2}, got %v", result.MapField))
	}

	// Verify nullable fields - first half (boxed types) - all nil
	if result.NullableInt != nil {
		panic(fmt.Sprintf("NullableInt mismatch: expected nil, got %v", *result.NullableInt))
	}
	if result.NullableLong != nil {
		panic(fmt.Sprintf("NullableLong mismatch: expected nil, got %v", *result.NullableLong))
	}
	if result.NullableFloat != nil {
		panic(fmt.Sprintf("NullableFloat mismatch: expected nil, got %v", *result.NullableFloat))
	}

	// Verify nullable fields - second half (reference types) - all nil
	if result.NullableDouble != nil {
		panic(fmt.Sprintf("NullableDouble mismatch: expected nil, got %v", *result.NullableDouble))
	}
	if result.NullableBool != nil {
		panic(fmt.Sprintf("NullableBool mismatch: expected nil, got %v", *result.NullableBool))
	}
	if result.NullableString != nil {
		panic(fmt.Sprintf("NullableString mismatch: expected nil, got %v", *result.NullableString))
	}
	if result.NullableList != nil {
		panic(fmt.Sprintf("NullableList mismatch: expected nil, got %v", result.NullableList))
	}
	if result.NullableSet != nil {
		panic(fmt.Sprintf("NullableSet mismatch: expected nil, got %v", result.NullableSet))
	}
	if result.NullableMap != nil {
		panic(fmt.Sprintf("NullableMap mismatch: expected nil, got %v", result.NullableMap))
	}

	serialized, err := f.Serialize(result)
	if err != nil {
		panic(fmt.Sprintf("Failed to serialize: %v", err))
	}

	writeFile(dataFile, serialized)
}

// Test cross-language schema evolution - all fields have values.
// Java sends: Group 1 (non-nullable) + Group 2 (nullable with values)
// Go reads: Group 1 (nullable/pointer) + Group 2 (non-nullable)
func testNullableFieldCompatibleNotNull() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	f := fory.New(fory.WithXlang(true), fory.WithCompatible(true))
	f.Register(NullableComprehensiveCompatible{}, 402)

	buf := fory.NewByteBuffer(data)
	var obj interface{}
	err := f.DeserializeWithCallbackBuffers(buf, &obj, nil)
	if err != nil {
		panic(fmt.Sprintf("Failed to deserialize: %v", err))
	}

	result := getNullableComprehensiveCompatible(obj)

	// Verify Group 1: Nullable in Go (read from Java's non-nullable)
	if result.ByteField == nil || *result.ByteField != 1 {
		panic(fmt.Sprintf("ByteField mismatch: expected 1, got %v", result.ByteField))
	}
	if result.ShortField == nil || *result.ShortField != 2 {
		panic(fmt.Sprintf("ShortField mismatch: expected 2, got %v", result.ShortField))
	}
	if result.IntField == nil || *result.IntField != 42 {
		panic(fmt.Sprintf("IntField mismatch: expected 42, got %v", result.IntField))
	}
	if result.LongField == nil || *result.LongField != 123456789 {
		panic(fmt.Sprintf("LongField mismatch: expected 123456789, got %v", result.LongField))
	}
	if result.FloatField == nil {
		panic("FloatField mismatch: expected 1.5, got nil")
	}
	assertEqualFloat32(1.5, *result.FloatField, "FloatField")
	if result.DoubleField == nil {
		panic("DoubleField mismatch: expected 2.5, got nil")
	}
	assertEqualFloat64(2.5, *result.DoubleField, "DoubleField")
	if result.BoolField == nil || *result.BoolField != true {
		panic(fmt.Sprintf("BoolField mismatch: expected true, got %v", result.BoolField))
	}

	// Verify boxed fields (also nullable in Go)
	if result.BoxedInt == nil || *result.BoxedInt != 10 {
		panic(fmt.Sprintf("BoxedInt mismatch: expected 10, got %v", result.BoxedInt))
	}
	if result.BoxedLong == nil || *result.BoxedLong != 20 {
		panic(fmt.Sprintf("BoxedLong mismatch: expected 20, got %v", result.BoxedLong))
	}
	if result.BoxedFloat == nil {
		panic("BoxedFloat mismatch: expected 1.1, got nil")
	}
	assertEqualFloat32(1.1, *result.BoxedFloat, "BoxedFloat")
	if result.BoxedDouble == nil {
		panic("BoxedDouble mismatch: expected 2.2, got nil")
	}
	assertEqualFloat64(2.2, *result.BoxedDouble, "BoxedDouble")
	if result.BoxedBool == nil || *result.BoxedBool != true {
		panic(fmt.Sprintf("BoxedBool mismatch: expected true, got %v", result.BoxedBool))
	}

	// Verify reference fields (also nullable in Go)
	if result.StringField == nil || *result.StringField != "hello" {
		panic(fmt.Sprintf("StringField mismatch: expected 'hello', got %v", result.StringField))
	}
	if len(result.ListField) != 3 || result.ListField[0] != "a" || result.ListField[1] != "b" || result.ListField[2] != "c" {
		panic(fmt.Sprintf("ListField mismatch: expected [a, b, c], got %v", result.ListField))
	}
	if len(result.SetField) != 2 || !result.SetField["x"] || !result.SetField["y"] {
		panic(fmt.Sprintf("SetField mismatch: expected {x, y}, got %v", result.SetField))
	}
	if len(result.MapField) != 2 || result.MapField["key1"] != "value1" || result.MapField["key2"] != "value2" {
		panic(fmt.Sprintf("MapField mismatch: expected {key1:value1, key2:value2}, got %v", result.MapField))
	}

	// Verify Group 2: Non-nullable in Go (read from Java's nullable with values)
	assertEqual(int32(100), result.NullableInt1, "NullableInt1")
	assertEqual(int64(200), result.NullableLong1, "NullableLong1")
	assertEqualFloat32(1.5, result.NullableFloat1, "NullableFloat1")
	assertEqualFloat64(2.5, result.NullableDouble1, "NullableDouble1")
	assertEqual(false, result.NullableBool1, "NullableBool1")

	assertEqual("nullable_value", result.NullableString2, "NullableString2")
	if len(result.NullableList2) != 2 || result.NullableList2[0] != "p" || result.NullableList2[1] != "q" {
		panic(fmt.Sprintf("NullableList2 mismatch: expected [p, q], got %v", result.NullableList2))
	}
	if len(result.NullableSet2) != 2 || !result.NullableSet2["m"] || !result.NullableSet2["n"] {
		panic(fmt.Sprintf("NullableSet2 mismatch: expected {m, n}, got %v", result.NullableSet2))
	}
	if len(result.NullableMap2) != 1 || result.NullableMap2["nk1"] != "nv1" {
		panic(fmt.Sprintf("NullableMap2 mismatch: expected {nk1:nv1}, got %v", result.NullableMap2))
	}

	serialized, err := f.Serialize(result)
	if err != nil {
		panic(fmt.Sprintf("Failed to serialize: %v", err))
	}

	writeFile(dataFile, serialized)
}

// Test cross-language schema evolution - nullable fields are null.
// Java sends: Group 1 (non-nullable with values) + Group 2 (nullable with null)
// Go reads: Group 1 (nullable/pointer) + Group 2 (non-nullable -> defaults)
//
// When Java sends null for Group 2 fields, Go's non-nullable fields receive
// default values (0 for numbers, false for bool, empty/nil for collections/strings).
func testNullableFieldCompatibleNull() {
	dataFile := getDataFile()
	data := readFile(dataFile)

	f := fory.New(fory.WithXlang(true), fory.WithCompatible(true))
	f.Register(NullableComprehensiveCompatible{}, 402)

	buf := fory.NewByteBuffer(data)
	var obj interface{}
	err := f.DeserializeWithCallbackBuffers(buf, &obj, nil)
	if err != nil {
		panic(fmt.Sprintf("Failed to deserialize: %v", err))
	}

	result := getNullableComprehensiveCompatible(obj)

	// Verify Group 1: Nullable in Go (read from Java's non-nullable)
	if result.ByteField == nil || *result.ByteField != 1 {
		panic(fmt.Sprintf("ByteField mismatch: expected 1, got %v", result.ByteField))
	}
	if result.ShortField == nil || *result.ShortField != 2 {
		panic(fmt.Sprintf("ShortField mismatch: expected 2, got %v", result.ShortField))
	}
	if result.IntField == nil || *result.IntField != 42 {
		panic(fmt.Sprintf("IntField mismatch: expected 42, got %v", result.IntField))
	}
	if result.LongField == nil || *result.LongField != 123456789 {
		panic(fmt.Sprintf("LongField mismatch: expected 123456789, got %v", result.LongField))
	}
	if result.FloatField == nil {
		panic("FloatField mismatch: expected 1.5, got nil")
	}
	assertEqualFloat32(1.5, *result.FloatField, "FloatField")
	if result.DoubleField == nil {
		panic("DoubleField mismatch: expected 2.5, got nil")
	}
	assertEqualFloat64(2.5, *result.DoubleField, "DoubleField")
	if result.BoolField == nil || *result.BoolField != true {
		panic(fmt.Sprintf("BoolField mismatch: expected true, got %v", result.BoolField))
	}

	// Verify boxed fields (also nullable in Go)
	if result.BoxedInt == nil || *result.BoxedInt != 10 {
		panic(fmt.Sprintf("BoxedInt mismatch: expected 10, got %v", result.BoxedInt))
	}
	if result.BoxedLong == nil || *result.BoxedLong != 20 {
		panic(fmt.Sprintf("BoxedLong mismatch: expected 20, got %v", result.BoxedLong))
	}
	if result.BoxedFloat == nil {
		panic("BoxedFloat mismatch: expected 1.1, got nil")
	}
	assertEqualFloat32(1.1, *result.BoxedFloat, "BoxedFloat")
	if result.BoxedDouble == nil {
		panic("BoxedDouble mismatch: expected 2.2, got nil")
	}
	assertEqualFloat64(2.2, *result.BoxedDouble, "BoxedDouble")
	if result.BoxedBool == nil || *result.BoxedBool != true {
		panic(fmt.Sprintf("BoxedBool mismatch: expected true, got %v", result.BoxedBool))
	}

	// Verify reference fields (also nullable in Go)
	if result.StringField == nil || *result.StringField != "hello" {
		panic(fmt.Sprintf("StringField mismatch: expected 'hello', got %v", result.StringField))
	}
	if len(result.ListField) != 3 || result.ListField[0] != "a" || result.ListField[1] != "b" || result.ListField[2] != "c" {
		panic(fmt.Sprintf("ListField mismatch: expected [a, b, c], got %v", result.ListField))
	}
	if len(result.SetField) != 2 || !result.SetField["x"] || !result.SetField["y"] {
		panic(fmt.Sprintf("SetField mismatch: expected {x, y}, got %v", result.SetField))
	}
	if len(result.MapField) != 2 || result.MapField["key1"] != "value1" || result.MapField["key2"] != "value2" {
		panic(fmt.Sprintf("MapField mismatch: expected {key1:value1, key2:value2}, got %v", result.MapField))
	}

	// Verify Group 2: Non-nullable in Go (Java sent null -> use defaults)
	assertEqual(int32(0), result.NullableInt1, "NullableInt1")
	assertEqual(int64(0), result.NullableLong1, "NullableLong1")
	assertEqualFloat32(0.0, result.NullableFloat1, "NullableFloat1")
	assertEqualFloat64(0.0, result.NullableDouble1, "NullableDouble1")
	assertEqual(false, result.NullableBool1, "NullableBool1")

	assertEqual("", result.NullableString2, "NullableString2")
	if result.NullableList2 != nil && len(result.NullableList2) != 0 {
		panic(fmt.Sprintf("NullableList2 mismatch: expected empty/nil, got %v", result.NullableList2))
	}
	if result.NullableSet2 != nil && len(result.NullableSet2) != 0 {
		panic(fmt.Sprintf("NullableSet2 mismatch: expected empty/nil, got %v", result.NullableSet2))
	}
	if result.NullableMap2 != nil && len(result.NullableMap2) != 0 {
		panic(fmt.Sprintf("NullableMap2 mismatch: expected empty/nil, got %v", result.NullableMap2))
	}

	serialized, err := f.Serialize(result)
	if err != nil {
		panic(fmt.Sprintf("Failed to serialize: %v", err))
	}

	writeFile(dataFile, serialized)
}

// ============================================================================
// Main
// ============================================================================

func main() {
	caseName := flag.String("case", "", "Test case name")
	flag.Parse()

	if *caseName == "" {
		fmt.Println("Usage: go run xlang_test_main.go --case <case_name>")
		os.Exit(1)
	}

	defer func() {
		if r := recover(); r != nil {
			buf := make([]byte, 4096)
			n := runtime.Stack(buf, false)
			fmt.Printf("Test case %s failed: %v\nStack trace:\n%s\n", *caseName, r, buf[:n])
			os.Exit(1)
		}
	}()

	switch *caseName {
	case "test_buffer":
		testBuffer()
	case "test_buffer_var":
		testBufferVar()
	case "test_murmurhash3":
		testMurmurHash3()
	case "test_string_serializer":
		testStringSerializer()
	case "test_cross_language_serializer":
		testCrossLanguageSerializer()
	case "test_simple_struct":
		testSimpleStruct()
	case "test_named_simple_struct":
		testNamedSimpleStruct()
	case "test_list":
		testList()
	case "test_map":
		testMap()
	case "test_integer":
		testInteger()
	case "test_item":
		testItem()
	case "test_color":
		testColor()
	case "test_struct_with_list":
		testStructWithList()
	case "test_struct_with_map":
		testStructWithMap()
	case "test_skip_id_custom":
		testSkipIdCustom()
	case "test_skip_name_custom":
		testSkipNameCustom()
	case "test_consistent_named":
		testConsistentNamed()
	case "test_struct_version_check":
		testStructVersionCheck()
	case "test_polymorphic_list":
		testPolymorphicList()
	case "test_polymorphic_map":
		testPolymorphicMap()
	case "test_one_field_struct_compatible":
		testOneFieldStructCompatible()
	case "test_one_field_struct_schema":
		testOneFieldStructSchema()
	case "test_one_string_field_schema":
		testOneStringFieldSchemaConsistent()
	case "test_one_string_field_compatible":
		testOneStringFieldCompatible()
	case "test_two_string_field_compatible":
		testTwoStringFieldCompatible()
	case "test_schema_evolution_compatible":
		testSchemaEvolutionCompatible()
	case "test_schema_evolution_compatible_reverse":
		testSchemaEvolutionCompatibleReverse()
	case "test_one_enum_field_schema":
		testOneEnumFieldSchemaConsistent()
	case "test_one_enum_field_compatible":
		testOneEnumFieldCompatible()
	case "test_two_enum_field_compatible":
		testTwoEnumFieldCompatible()
	case "test_enum_schema_evolution_compatible":
		testEnumSchemaEvolutionCompatible()
	case "test_enum_schema_evolution_compatible_reverse":
		testEnumSchemaEvolutionCompatibleReverse()
	case "test_nullable_field_schema_consistent_not_null":
		testNullableFieldSchemaConsistentNotNull()
	case "test_nullable_field_schema_consistent_null":
		testNullableFieldSchemaConsistentNull()
	case "test_nullable_field_compatible_not_null":
		testNullableFieldCompatibleNotNull()
	case "test_nullable_field_compatible_null":
		testNullableFieldCompatibleNull()
	default:
		panic(fmt.Sprintf("Unknown test case: %s", *caseName))
	}

	fmt.Printf("Test case %s passed\n", *caseName)
}

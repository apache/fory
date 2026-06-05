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
	"bytes"
	"compress/zlib"
	"reflect"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// Test structs for encoding/decoding
type SimpleStruct struct {
	ID   int32
	Name string
}

type SliceStruct struct {
	ID    int32
	Items []string
}

type NestedSliceStruct struct {
	ID      int32
	Matrix  [][]int
	Records [][]string
}

type MapStruct struct {
	ID   int32
	Data map[string]int
}

type ComplexStruct struct {
	ID       int32
	SliceMap map[string][]int
	MapSlice []map[string]int
}

// TestTypeDefEncodingDecodingTableDriven tests encoding and decoding of TypeDef
// This ensure the peer can successfully encode and decode the same TypeDef, and obtain appropriate serializer to read or skip data
func TestTypeDefEncodingDecoding(t *testing.T) {
	tests := []struct {
		name       string
		tagName    string
		testStruct any
	}{
		{
			name:    "SimpleStruct with basic fields",
			tagName: "example.SimpleStruct",
			testStruct: SimpleStruct{
				ID:   42,
				Name: "test",
			},
		},
		{
			name:    "SliceStruct with basic items",
			tagName: "example.SliceStruct",
			testStruct: SliceStruct{
				ID:    100,
				Items: []string{"item1", "item2", "item3"},
			},
		},
		{
			name:    "NestedSliceStruct with nested collections",
			tagName: "example.NestedSliceStruct",
			testStruct: NestedSliceStruct{
				ID:      200,
				Matrix:  [][]int{{1, 2}, {3, 4}},
				Records: [][]string{{"a", "b"}, {"c", "d"}},
			},
		},
		{
			name:    "MapStruct with map fields",
			tagName: "example.MapStruct",
			testStruct: MapStruct{
				ID:   300,
				Data: map[string]int{"key1": 1, "key2": 2},
			},
		},
		{
			name:    "ComplexStruct with complex nested types",
			tagName: "example.ComplexStruct",
			testStruct: ComplexStruct{
				ID:       400,
				SliceMap: map[string][]int{"list1": {1, 2, 3}, "list2": {4, 5, 6}},
				MapSlice: []map[string]int{{"a": 1}, {"b": 2}},
			},
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			fory := NewFory(WithXlang(false), WithRefTracking(false), WithCompatible(false))

			if err := fory.RegisterStructByName(tt.testStruct, tt.tagName); err != nil {
				t.Fatalf("Failed to register tag type: %v", err)
			}

			structValue := reflect.ValueOf(tt.testStruct)
			originalTypeDef, err := buildTypeDef(fory, structValue)
			if err != nil {
				t.Fatalf("Failed to build TypeDef: %v", err)
			}

			buffer := NewByteBuffer(make([]byte, 0, 256))
			readErr := &Error{}
			originalTypeDef.writeTypeDef(buffer, readErr)

			decodedTypeDef := readTypeDef(fory, buffer, int64(buffer.ReadInt64(readErr)), readErr)
			if readErr.HasError() {
				t.Fatalf("Failed to decode TypeDef: %v", readErr.Error())
			}

			// basic checks
			assert.True(t, decodedTypeDef.typeId == originalTypeDef.typeId || decodedTypeDef.typeId == -originalTypeDef.typeId, "TypeId mismatch")
			assert.Equal(t, originalTypeDef.registerByName, decodedTypeDef.registerByName, "RegisterStructByName mismatch")
			assert.Equal(t, originalTypeDef.compressed, decodedTypeDef.compressed, "Compressed flag mismatch")
			assert.Equal(t, len(originalTypeDef.fieldDefs), len(decodedTypeDef.fieldDefs), "Field count mismatch")

			for i, originalField := range originalTypeDef.fieldDefs {
				checkFieldDef(t, originalField, decodedTypeDef.fieldDefs[i])
			}
		})
	}
}

func checkFieldDef(t *testing.T, original, decoded FieldDef) {
	assert.Equal(t, original.name, decoded.name, "Field name mismatch")
	assert.Equal(t, original.nameEncoding, decoded.nameEncoding, "Field name encoding mismatch")
	assert.Equal(t, original.nullable, decoded.nullable, "Field nullable mismatch")
	assert.Equal(t, original.trackRef, decoded.trackRef, "Field trackRef mismatch")
	checkTypeSpecRecursively(t, original.typeSpec, decoded.typeSpec, original.name, false)
}

func checkTypeSpecRecursively(t *testing.T, original, decoded *TypeSpec, path string, compareRootFlags bool) {
	assert.NotNil(t, original, "original type spec must not be nil at path %s", path)
	assert.NotNil(t, decoded, "decoded type spec must not be nil at path %s", path)
	if original == nil || decoded == nil {
		return
	}
	assert.Equal(t, original.TypeId(), decoded.TypeId(), "TypeSpec TypeId mismatch at path: %s", path)
	if compareRootFlags {
		assert.Equal(t, original.Nullable, decoded.Nullable, "TypeSpec Nullable mismatch at path: %s", path)
		assert.Equal(t, original.TrackRef, decoded.TrackRef, "TypeSpec TrackRef mismatch at path: %s", path)
	}
	switch original.TypeId() {
	case LIST, SET:
		checkTypeSpecRecursivelyOrNil(t, original.Element, decoded.Element, path+"[]", true)
	case MAP:
		checkTypeSpecRecursivelyOrNil(t, original.Key, decoded.Key, path+"[key]", true)
		checkTypeSpecRecursivelyOrNil(t, original.Value, decoded.Value, path+"[value]", true)
	}
}

func checkTypeSpecRecursivelyOrNil(t *testing.T, original, decoded *TypeSpec, path string, compareRootFlags bool) {
	if original == nil || decoded == nil {
		assert.Equal(t, original == nil, decoded == nil, "TypeSpec nil mismatch at path: %s", path)
		return
	}
	checkTypeSpecRecursively(t, original, decoded, path, compareRootFlags)
}

func typeDefTestBodyWithoutFields() []byte {
	buffer := NewByteBuffer(nil)
	buffer.WriteByte(StructTypeDefFlag)
	buffer.WriteVarUint32(0)
	return buffer.Bytes()
}

func typeDefTestFrame(t *testing.T, body []byte, compressed bool) (*ByteBuffer, int64) {
	t.Helper()
	bodyBuffer := NewByteBuffer(nil)
	bodyBuffer.WriteBinary(body)
	frame, err := prependGlobalHeader(bodyBuffer, compressed)
	require.NoError(t, err)
	readErr := &Error{}
	header := frame.ReadInt64(readErr)
	require.NoError(t, readErr.CheckError())
	return frame, header
}

func deflateTypeDefTestBody(t *testing.T, body []byte) []byte {
	t.Helper()
	var compressed bytes.Buffer
	writer := zlib.NewWriter(&compressed)
	_, err := writer.Write(body)
	require.NoError(t, err)
	require.NoError(t, writer.Close())
	return compressed.Bytes()
}

// Item1 struct with mixed nullable (pointer) and non-nullable (primitive) fields
type Item1 struct {
	F1 int32
	F2 int32
	F3 *int32
	F4 *int32
	F5 int32  // Non-nullable: null → 0 per test expectation
	F6 *int32 // Nullable: null stays nil
}

// TestTypeDefNullableFields verifies that pointer fields are correctly encoded as nullable
// and primitive fields are encoded as non-nullable in TypeDef
func TestTypeDefNullableFields(t *testing.T) {
	fory := NewFory(WithXlang(false), WithRefTracking(false), WithCompatible(false))

	// Register the type
	if err := fory.RegisterStructByName(Item1{}, "test.Item1"); err != nil {
		t.Fatalf("Failed to register type: %v", err)
	}

	// Create test instance with some pointer fields set, some nil
	v3, v4, v6 := int32(30), int32(40), int32(60)
	testItem := Item1{
		F1: 10,
		F2: 20,
		F3: &v3,
		F4: &v4,
		F5: 50,
		F6: &v6,
	}

	// Build TypeDef
	structValue := reflect.ValueOf(testItem)
	typeDef, err := buildTypeDef(fory, structValue)
	if err != nil {
		t.Fatalf("Failed to build TypeDef: %v", err)
	}

	// Expected nullable status for each field:
	// F1, F2, F5 = int32 = non-nullable
	// F3, F4, F6 = *int32 = nullable
	expectedNullable := map[string]bool{
		"f1": false, // int32
		"f2": false, // int32
		"f3": true,  // *int32
		"f4": true,  // *int32
		"f5": false, // int32
		"f6": true,  // *int32
	}

	// Verify original TypeDef has correct nullable flags
	t.Run("Original TypeDef nullable flags", func(t *testing.T) {
		for _, fieldDef := range typeDef.fieldDefs {
			expected, ok := expectedNullable[fieldDef.name]
			if !ok {
				t.Errorf("Unexpected field name: %s", fieldDef.name)
				continue
			}
			assert.Equal(t, expected, fieldDef.nullable,
				"Field %s nullable mismatch: expected %v, got %v",
				fieldDef.name, expected, fieldDef.nullable)
		}
	})

	// Encode and decode TypeDef, then verify nullable flags are preserved
	t.Run("Encoded/Decoded TypeDef nullable flags", func(t *testing.T) {
		buffer := NewByteBuffer(make([]byte, 0, 256))
		readErr := &Error{}
		typeDef.writeTypeDef(buffer, readErr)

		decodedTypeDef := readTypeDef(fory, buffer, int64(buffer.ReadInt64(readErr)), readErr)
		if readErr.HasError() {
			t.Fatalf("Failed to decode TypeDef: %v", readErr.Error())
		}

		// Verify decoded TypeDef has correct nullable flags
		for _, fieldDef := range decodedTypeDef.fieldDefs {
			expected, ok := expectedNullable[fieldDef.name]
			if !ok {
				t.Errorf("Unexpected field name in decoded TypeDef: %s", fieldDef.name)
				continue
			}
			assert.Equal(t, expected, fieldDef.nullable,
				"Decoded field %s nullable mismatch: expected %v, got %v",
				fieldDef.name, expected, fieldDef.nullable)
		}
	})

	// Test with nil pointer fields
	t.Run("TypeDef with nil pointer fields", func(t *testing.T) {
		testItemWithNils := Item1{
			F1: 10,
			F2: 20,
			F3: nil, // nil pointer
			F4: &v4,
			F5: 50,
			F6: nil, // nil pointer
		}

		structValue := reflect.ValueOf(testItemWithNils)
		typeDefWithNils, err := buildTypeDef(fory, structValue)
		if err != nil {
			t.Fatalf("Failed to build TypeDef with nils: %v", err)
		}

		// Nullable flags should be the same regardless of actual values
		for _, fieldDef := range typeDefWithNils.fieldDefs {
			expected, ok := expectedNullable[fieldDef.name]
			if !ok {
				t.Errorf("Unexpected field name: %s", fieldDef.name)
				continue
			}
			assert.Equal(t, expected, fieldDef.nullable,
				"Field %s nullable mismatch (with nils): expected %v, got %v",
				fieldDef.name, expected, fieldDef.nullable)
		}
	})
}

// TestTypeDefFieldCountOOMPanic verifies that decodeTypeDef rejects a crafted payload
// whose fieldCount (2 billion) far exceeds the hard cap and available buffer bytes,
// returning an error instead of performing the unbounded make([]FieldDef, fieldCount)
// allocation that would OOM-crash the process.
func TestTypeDefFieldCountOOMPanic(t *testing.T) {
	fory := NewFory(WithXlang(false), WithCompatible(false))

	buffer := NewByteBuffer(make([]byte, 0, 8))
	buffer.WriteByte(StructTypeDefFlag | SmallNumFieldsThreshold)
	buffer.WriteVarUint32(2000000000)
	buffer.SetReaderIndex(0)

	_, err := decodeTypeDef(fory, buffer, int64(buffer.WriterIndex()))
	if err == nil {
		t.Fatal("expected error for oversized fieldCount, got nil")
	}
}

func TestTypeDefRejectsReservedGlobalHeaderBits(t *testing.T) {
	fory := NewFory(WithXlang(false), WithCompatible(false))
	buffer := NewByteBuffer(nil)
	buffer.WriteByte(StructTypeDefFlag)
	buffer.WriteVarUint32(0)
	buffer.SetReaderIndex(0)

	_, err := decodeTypeDef(fory, buffer, int64(RESERVED_META_BITS|uint64(buffer.WriterIndex())))
	if err == nil {
		t.Fatal("expected error for reserved TypeDef global header bits")
	}
}

func TestTypeDefRejectsReservedNonStructKindBits(t *testing.T) {
	fory := NewFory(WithXlang(false), WithCompatible(false))
	body := []byte{0b0001_0000}
	frame, header := typeDefTestFrame(t, body, false)

	_, err := decodeTypeDef(fory, frame, header)
	if err == nil {
		t.Fatal("expected error for reserved non-struct TypeDef kind bits")
	}
}

func TestTypeDefRejectsTrailingMetadataBytes(t *testing.T) {
	fory := NewFory(WithXlang(false), WithCompatible(false))
	meta := NewByteBuffer(nil)
	meta.WriteByte(StructTypeDefFlag)
	meta.WriteVarUint32(0)
	meta.WriteByte(0xff)

	buffer := NewByteBuffer(nil)
	_, writeErr := buffer.Write(meta.Bytes())
	if writeErr != nil {
		t.Fatalf("failed to write type def metadata: %v", writeErr)
	}
	buffer.SetReaderIndex(0)

	_, err := decodeTypeDef(fory, buffer, int64(len(meta.Bytes())))
	if err == nil {
		t.Fatal("expected error for trailing TypeDef metadata bytes")
	}
}

func TestTypeDefExtendedFieldCountHeaderDoesNotSetRegisterByName(t *testing.T) {
	fields := make([]FieldDef, 32)
	for i := range fields {
		fields[i] = FieldDef{
			typeSpec: NewSimpleTypeSpec(INT32),
			tagID:    i + 1,
		}
	}
	typeDef := NewTypeDef(uint32(STRUCT), 700, nil, nil, false, false, fields)
	buffer := NewByteBuffer(nil)

	require.NoError(t, writeMetaHeader(buffer, typeDef))
	header := buffer.Bytes()[0]
	require.Equal(t, byte(StructTypeDefFlag|SmallNumFieldsThreshold), header)
	require.Zero(t, header&RegisterByNameFlag)
}

func TestTypeDefRejectsMetadataHashMismatch(t *testing.T) {
	fory := NewFory(WithXlang(false), WithCompatible(false))
	body := typeDefTestBodyWithoutFields()
	buffer := NewByteBuffer(nil)
	buffer.WriteBinary(body)
	buffer.SetReaderIndex(0)

	_, err := decodeTypeDef(fory, buffer, int64(len(body)))
	require.Error(t, err)
	require.Contains(t, err.Error(), "metadata hash")
}

func TestTypeDefHeaderHashIncludesHeaderLowBits(t *testing.T) {
	fory := NewFory(WithXlang(false), WithCompatible(false))
	body := typeDefTestBodyWithoutFields()
	_, header := typeDefTestFrame(t, body, false)

	hashMask := ^uint64(0)
	hashMask <<= uint(64 - NUM_HASH_BITS)
	bodyOnlyHash := bodyOnlyTypeDefHeaderHash(body)
	require.NotEqual(t, uint64(header)&hashMask, bodyOnlyHash)
	rewrittenHeader := int64(bodyOnlyHash | (uint64(header) &^ hashMask))
	buffer := NewByteBuffer(nil)
	buffer.WriteBinary(body)
	buffer.SetReaderIndex(0)

	_, err := decodeTypeDef(fory, buffer, rewrittenHeader)
	require.Error(t, err)
	require.Contains(t, err.Error(), "metadata hash")
}

func TestTypeDefRejectsEncodedMetadataAboveMaxBinarySize(t *testing.T) {
	fory := NewFory(WithXlang(false), WithMaxBinarySize(1), WithCompatible(false))
	body := typeDefTestBodyWithoutFields()
	frame, header := typeDefTestFrame(t, body, false)

	_, err := decodeTypeDef(fory, frame, header)
	require.Error(t, err)
	require.Contains(t, err.Error(), "max binary size exceeded")
}

func TestTypeDefRejectsCompressedMetadata(t *testing.T) {
	decoded := typeDefTestBodyWithoutFields()
	compressed := deflateTypeDefTestBody(t, decoded)
	fory := NewFory(WithXlang(false), WithMaxBinarySize(4096), WithCompatible(false))
	frame, header := typeDefTestFrame(t, compressed, true)

	_, err := decodeTypeDef(fory, frame, header)
	require.Error(t, err)
	require.Contains(t, err.Error(), "compressed xlang TypeDef")
}

func TestReadSharedTypeMetaCapsParsedTypeDefCache(t *testing.T) {
	fory := NewFory(WithXlang(false), WithCompatible(true))
	require.NoError(t, fory.RegisterStructByName(SimpleStruct{}, "example.SimpleStruct"))
	typeDef, err := buildTypeDef(fory, reflect.ValueOf(SimpleStruct{}))
	require.NoError(t, err)
	require.NotEmpty(t, typeDef.encoded)

	for i := 0; i < maxCachedTypeDefs; i++ {
		fory.typeResolver.defIdToTypeDef[int64(i)] = typeDef
	}
	headerErr := &Error{}
	header := NewByteBuffer(typeDef.encoded).ReadInt64(headerErr)
	require.NoError(t, headerErr.CheckError())
	require.NotContains(t, fory.typeResolver.defIdToTypeDef, header)

	buffer := NewByteBuffer(nil)
	buffer.WriteVarUint32(0)
	buffer.WriteBinary(typeDef.encoded)
	readErr := &Error{}
	typeInfo := fory.typeResolver.readSharedTypeMeta(buffer, readErr)
	require.NoError(t, readErr.CheckError())
	require.NotNil(t, typeInfo)
	require.Len(t, fory.typeResolver.defIdToTypeDef, maxCachedTypeDefs)
	require.NotContains(t, fory.typeResolver.defIdToTypeDef, header)
}

func TestDecodeTypeDefFallbackNamedTypeCacheRespectsCap(t *testing.T) {
	fory := NewFory(WithXlang(false), WithCompatible(true))
	require.NoError(t, fory.RegisterStructByName(SimpleStruct{}, "example.SimpleStruct"))
	typeDef, err := buildTypeDef(fory, reflect.ValueOf(SimpleStruct{}))
	require.NoError(t, err)
	require.NotNil(t, typeDef.nsName)
	require.NotNil(t, typeDef.typeName)

	nameKey := nsTypeKey{typeDef.nsName.Hashcode, typeDef.typeName.Hashcode}
	delete(fory.typeResolver.nsTypeToTypeInfo, nameKey)
	info := fory.typeResolver.namedTypeToTypeInfo[[2]string{"example", "SimpleStruct"}]
	require.NotNil(t, info)
	for i := 0; len(fory.typeResolver.nsTypeToTypeInfo) < maxCachedNamedTypeInfos; i++ {
		fory.typeResolver.nsTypeToTypeInfo[nsTypeKey{int64(i + 1), int64(i + 2)}] = info
	}
	require.NotContains(t, fory.typeResolver.nsTypeToTypeInfo, nameKey)

	buffer := NewByteBuffer(nil)
	readErr := &Error{}
	typeDef.writeTypeDef(buffer, readErr)
	require.NoError(t, readErr.CheckError())
	header := buffer.ReadInt64(readErr)
	require.NoError(t, readErr.CheckError())
	decoded := readTypeDef(fory, buffer, header, readErr)
	require.NoError(t, readErr.CheckError())
	require.NotNil(t, decoded)
	require.Len(t, fory.typeResolver.nsTypeToTypeInfo, maxCachedNamedTypeInfos)
	require.NotContains(t, fory.typeResolver.nsTypeToTypeInfo, nameKey)
}

func TestTypeDefRejectsNamespaceLengthBeyondMetadata(t *testing.T) {
	fory := NewFory(WithXlang(false), WithCompatible(false))
	meta := NewByteBuffer(nil)
	meta.WriteByte(StructTypeDefFlag | RegisterByNameFlag)
	meta.WriteByte(byte(BIG_NAME_THRESHOLD << 2))
	meta.WriteVarUint32Small7(100)
	frame, header := typeDefTestFrame(t, meta.Bytes(), false)

	_, err := decodeTypeDef(fory, frame, header)
	require.Error(t, err)
	require.Contains(t, err.Error(), "namespace length")
}

func TestTypeDefRejectsFieldNameLengthBeyondMetadata(t *testing.T) {
	fory := NewFory(WithXlang(false), WithCompatible(false))
	meta := NewByteBuffer(nil)
	meta.WriteByte(StructTypeDefFlag | 1)
	meta.WriteVarUint32(0)
	meta.WriteByte(0x0F << 2)
	meta.WriteVarUint32(100)
	meta.WriteUint8(uint8(INT32))
	frame, header := typeDefTestFrame(t, meta.Bytes(), false)

	_, err := decodeTypeDef(fory, frame, header)
	require.Error(t, err)
	require.Contains(t, err.Error(), "field name length")
}

func bodyOnlyTypeDefHeaderHash(data []byte) uint64 {
	hash := int64(Murmur3Sum64WithSeed(data, 47) << (64 - NUM_HASH_BITS))
	if hash < 0 {
		hash = -hash
	}
	hashMask := ^uint64(0)
	hashMask <<= uint(64 - NUM_HASH_BITS)
	return uint64(hash) & hashMask
}

// TestTypeDefNestedRecursionStackOverflowPanic verifies that readFieldTypeWithFlags
// rejects a crafted payload with 20 million nested LIST types, returning an error
// at depth 64 instead of recursing until a goroutine stack overflow crashes the process.
func TestTypeDefNestedRecursionStackOverflowPanic(t *testing.T) {
	depth := 20000000
	buffer := NewByteBuffer(make([]byte, 0, depth*2))
	for i := 0; i < depth; i++ {
		buffer.WriteVarUint32Small7(uint32(LIST) << 2)
	}
	buffer.WriteVarUint32Small7(uint32(INT32) << 2)
	buffer.SetReaderIndex(0)

	bufErr := &Error{}
	_, err := readFieldTypeWithFlags(buffer, 0, defaultConfig().MaxDepth, bufErr)
	if err == nil {
		t.Fatal("expected error for excessive nesting depth, got nil")
	}
}

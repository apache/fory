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

// Row format cross-language peer driven by Java's
// org.apache.fory.format.GoCrossLanguageTest: each case reads bytes
// written by Java, verifies them field by field, re-encodes the same
// value, and overwrites the file for Java to check.
package main

import (
	"bytes"
	"fmt"
	"os"
	"reflect"

	"github.com/apache/fory/go/fory/row"
)

// Mirrors org.apache.fory.format.CrossLanguageTest.A: pointer and
// value types are chosen so the inferred schema matches Java's
// (boxed Java types are nullable, so they map to Go pointers; string
// fields are nullable in both languages).
type a struct {
	F1 *int32
	F2 map[string]string
}

// Mirrors CrossLanguageTest.Bar.
type bar struct {
	F1 *int32
	F2 string
}

// Mirrors GoCrossLanguageTest.Blob: Java byte[] and Go []byte are both
// list<int8>.
type blob struct {
	F1 []byte
	F2 string
}

// Mirrors CrossLanguageTest.Foo. F3 uses []*string because the Java
// fixture contains a null list element.
type foo struct {
	F1 *int32
	F2 string
	F3 []*string
	F4 map[string]*int32
	F5 *bar
}

func main() {
	if len(os.Args) < 3 {
		fail("usage: row_xlang_bin <case> <file>...")
	}
	switch caseName := os.Args[1]; caseName {
	case "test_map_encoder":
		testMapEncoder(os.Args[2])
	case "test_serialization_without_schema":
		testSerializationWithoutSchema(os.Args[2])
	case "test_serialization_with_schema":
		if len(os.Args) < 4 {
			fail("test_serialization_with_schema needs <schemaFile> <dataFile>")
		}
		testSerializationWithSchema(os.Args[2], os.Args[3])
	case "test_byte_array_carrier":
		testByteArrayCarrier(os.Args[2])
	default:
		fail("unknown test case %q", caseName)
	}
}

func testMapEncoder(dataFile string) {
	encoder, err := row.NewEncoder[a]()
	must(err)
	data, err := os.ReadFile(dataFile)
	must(err)

	decoded, err := encoder.Decode(data)
	must(err)
	expected := a{F1: int32Ptr(1), F2: map[string]string{"pid": "12345", "ip": "0.0.0.0", "k1": "v1"}}
	check(reflect.DeepEqual(decoded, expected), "decoded %+v, expected %+v", decoded, expected)

	encoded, err := encoder.Encode(&expected)
	must(err)
	must(os.WriteFile(dataFile, encoded, 0o644))
}

func testByteArrayCarrier(dataFile string) {
	encoder, err := row.NewEncoder[blob]()
	must(err)
	data, err := os.ReadFile(dataFile)
	must(err)

	decoded, err := encoder.Decode(data)
	must(err)
	expected := blob{F1: []byte{0, 1, 0xff, 127, 0x80}, F2: "bytes"}
	check(reflect.DeepEqual(decoded, expected), "decoded %+v, expected %+v", decoded, expected)

	encoded, err := encoder.Encode(&expected)
	must(err)
	must(os.WriteFile(dataFile, encoded, 0o644))
}

func testSerializationWithoutSchema(dataFile string) {
	encoder, err := row.NewEncoder[foo]()
	must(err)
	data, err := os.ReadFile(dataFile)
	must(err)

	decoded, err := encoder.FromRow(data)
	must(err)
	expected := expectedFoo()
	check(reflect.DeepEqual(decoded, expected), "decoded %+v, expected %+v", decoded, expected)

	rowBytes, err := encoder.ToRow(&expected)
	must(err)
	must(os.WriteFile(dataFile, rowBytes, 0o644))
}

func testSerializationWithSchema(schemaFile, dataFile string) {
	encoder, err := row.NewEncoder[foo]()
	must(err)
	schemaBytes, err := os.ReadFile(schemaFile)
	must(err)

	parsed, err := row.SchemaFromBytes(schemaBytes)
	must(err)
	check(parsed.Equal(encoder.Schema()), "Java schema %v, inferred schema %v", parsed, encoder.Schema())
	check(row.ComputeSchemaHash(parsed) == encoder.SchemaHash(),
		"schema hash %d, encoder hash %d", row.ComputeSchemaHash(parsed), encoder.SchemaHash())
	reencoded, err := row.SchemaToBytes(encoder.Schema())
	must(err)
	check(bytes.Equal(reencoded, schemaBytes), "re-encoded schema bytes differ from Java's")

	testSerializationWithoutSchema(dataFile)
}

func expectedFoo() foo {
	return foo{
		F1: int32Ptr(1),
		F2: "str",
		F3: []*string{stringPtr("str1"), nil, stringPtr("str2")},
		F4: map[string]*int32{
			"k1": int32Ptr(1), "k2": int32Ptr(2), "k3": int32Ptr(3),
			"k4": int32Ptr(4), "k5": int32Ptr(5), "k6": int32Ptr(6),
		},
		F5: &bar{F1: int32Ptr(1), F2: "str"},
	}
}

func int32Ptr(v int32) *int32    { return &v }
func stringPtr(s string) *string { return &s }

func check(ok bool, format string, args ...any) {
	if !ok {
		fail(format, args...)
	}
}

func must(err error) {
	if err != nil {
		fail("%v", err)
	}
}

func fail(format string, args ...any) {
	fmt.Fprintf(os.Stderr, "row_xlang: "+format+"\n", args...)
	os.Exit(1)
}

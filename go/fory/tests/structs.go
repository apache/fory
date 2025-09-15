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

//go:generate go run ../cmd/fory/main.go --force -file structs.go

// BasicTypesStruct demonstrates basic data types serialization
// fory:gen
type BasicTypesStruct struct {
	BoolField    bool    `fory:"bool_field"`
	Int8Field    int8    `fory:"int8_field"`
	Int16Field   int16   `fory:"int16_field"`
	Int32Field   int32   `fory:"int32_field"`
	Int64Field   int64   `fory:"int64_field"`
	IntField     int     `fory:"int_field"`
	Uint8Field   uint8   `fory:"uint8_field"`
	Float32Field float32 `fory:"float32_field"`
	Float64Field float64 `fory:"float64_field"`
	StringField  string  `fory:"string_field"`
}

// CollectionTypesStruct demonstrates slice and map types serialization
// fory:gen
type CollectionTypesStruct struct {
	IntSlice      []int32          `fory:"int_slice"`
	StringSlice   []string         `fory:"string_slice"`
	BoolSlice     []bool           `fory:"bool_slice"`
	StringIntMap  map[string]int32 `fory:"string_int_map"`
	IntStringMap  map[int32]string `fory:"int_string_map"`
	StringBoolMap map[string]bool  `fory:"string_bool_map"`
}

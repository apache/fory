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

// ValidationDemo is a simple struct for testing code generation
// Contains various basic types to validate comprehensive type support

// fory:gen
type ValidationDemo struct {
	A int32   // int32 field
	B string  // string field
	C int64   // int64 field
	D float64 // float64 field
	E bool    // bool field
}

// SliceDemo is a struct for testing slice serialization
// Contains various slice types

// fory:gen
type SliceDemo struct {
	IntSlice    []int32   // slice of int32
	StringSlice []string  // slice of string
	FloatSlice  []float64 // slice of float64
	BoolSlice   []bool    // slice of bool
}

// DynamicSliceDemo is a struct for testing dynamic slice serialization
// fory:gen
type DynamicSliceDemo struct {
	DynamicSlice []interface{} // slice of interface{}
}

// MapDemo demonstrates map field support
// fory:gen
type MapDemo struct {
	StringMap map[string]string // map[string]string
	IntMap    map[int]int       // map[int]int
	MixedMap  map[string]int    // map[string]int
}

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

import "time"

// ValidationDemo is a simple struct for testing code generation
// Contains only basic types since PR1 only supports basic types

// fory:gen
type ValidationDemo struct {
	A int32            `json:"a"` // int32 field
	B string           `json:"b"` // string field
	C int64            `json:"c"` // int64 field
	D []int32          `json:"d"` // slice of int32
	E []string         `json:"e"` // slice of string
	F []bool           `json:"f"` // slice of bool
	G map[string]int32 `json:"g"` // map with string key and int32 value
	H map[int32]string `json:"h"` // map with int32 key and string value
	I map[string]bool  `json:"i"` // map with string key and bool value
	J time.Time        `json:"j"` // time.Time field
}

// SimpleStruct is a basic struct for testing simple serialization
// Contains only int and string fields for basic functionality testing

// fory:gen
type SimpleStruct struct {
	ID   int    `json:"id"`   // integer field
	Name string `json:"name"` // string field
}

// CompoundStruct is a complex struct for testing nested struct serialization
// Contains embedded ValidationDemo and SimpleStruct types plus additional fields

// fory:gen
type CompoundStruct struct {
	ValidationData ValidationDemo `json:"validation_data"` // nested ValidationDemo struct
	SimpleData     SimpleStruct   `json:"simple_data"`     // nested SimpleStruct struct
	Count          int            `json:"count"`           // additional int field
}

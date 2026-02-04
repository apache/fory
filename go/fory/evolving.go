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

import "reflect"

// ForyEvolving allows a struct to override schema evolution behavior.
// Returning false disables compatible struct type IDs for this struct.
type ForyEvolving interface {
	ForyEvolving() bool
}

var foryEvolvingType = reflect.TypeOf((*ForyEvolving)(nil)).Elem()

func structEvolvingOverride(type_ reflect.Type) (bool, bool) {
	if type_ == nil {
		return false, false
	}
	if type_.Kind() == reflect.Ptr {
		type_ = type_.Elem()
	}
	if type_.Kind() != reflect.Struct {
		return false, false
	}
	if type_.Implements(foryEvolvingType) {
		value := reflect.Zero(type_).Interface().(ForyEvolving)
		return value.ForyEvolving(), true
	}
	ptrType := reflect.PtrTo(type_)
	if ptrType.Implements(foryEvolvingType) {
		value := reflect.New(type_).Interface().(ForyEvolving)
		return value.ForyEvolving(), true
	}
	return false, false
}

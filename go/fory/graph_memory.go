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
	"reflect"
	"unsafe"
)

const graphContainerBytes int64 = 1

var stringElementBytes = graphSizeOf[string]()
var stringMaxLength = maxGraphCount(stringElementBytes)

func graphSizeOf[T any]() int64 {
	var v T
	return int64(unsafe.Sizeof(v))
}

func maxGraphCount(elemBytes int64) int64 {
	if elemBytes == 0 {
		return MaxInt64 - graphContainerBytes
	}
	return (MaxInt64 - graphContainerBytes) / elemBytes
}

func structGraphBytes(type_ reflect.Type) int64 {
	if type_.Kind() != reflect.Struct {
		return 0
	}
	bytes := int64(type_.Size())
	if bytes == 0 {
		return graphContainerBytes
	}
	return bytes
}

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

import "unsafe"

const graphReferenceBytes = 4
// Lower-bound owner costs for retained Go slice/map/set values. Counted element, key, and value
// storage is charged at each concrete owner path; these are not Fory wire header sizes.
const graphSliceOwnerBytes = int(unsafe.Sizeof([]any{}))
const graphMapOwnerBytes = int(unsafe.Sizeof(map[any]any{})) + int(unsafe.Sizeof(int(0))) + 2*int(unsafe.Sizeof(uintptr(0)))
const graphSetOwnerBytes = graphMapOwnerBytes
const graphMaxOwnerBytes = graphMapOwnerBytes

var stringElementBytes = graphSizeOf[string]()
var stringMaxLength = maxGraphCount(stringElementBytes)

func graphSizeOf[T any]() int {
	var v T
	return int(unsafe.Sizeof(v))
}

func maxGraphCount(elemBytes int) int64 {
	if elemBytes == 0 {
		return MaxInt64 - int64(graphMaxOwnerBytes)
	}
	return (MaxInt64 - int64(graphMaxOwnerBytes)) / int64(elemBytes)
}

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

// MetaContext used to share data across multiple serialization calls
type MetaContext struct {
	// typeMap make sure each type just fully serialize once, the following serialization will use the index
	typeMap map[reflect.Type]uint32
	// record typeDefs need to be serialized during one serialization
	writingTypeDefs []*TypeDef
	// read from peer
	readTypeInfos []TypeInfo
	// scopedMetaShareEnabled controls whether meta sharing is scoped to single serialization
	scopedMetaShareEnabled bool
}

// NewMetaContext creates a new MetaContext
func NewMetaContext(scopedMetaShareEnabled bool) *MetaContext {
	return &MetaContext{
		typeMap:                make(map[reflect.Type]uint32),
		scopedMetaShareEnabled: scopedMetaShareEnabled,
	}
}

// resetRead resets the read-related state of the MetaContext
func (mc *MetaContext) resetRead() {
	if mc.scopedMetaShareEnabled {
		mc.readTypeInfos = mc.readTypeInfos[:0] // Reset slice but keep capacity
	} else {
		mc.readTypeInfos = nil
	}
}

// resetWrite resets the write-related state of the MetaContext
func (mc *MetaContext) resetWrite() {
	if mc.scopedMetaShareEnabled {
		for k := range mc.typeMap {
			delete(mc.typeMap, k)
		}
		mc.writingTypeDefs = mc.writingTypeDefs[:0] // Reset slice but keep capacity
	} else {
		mc.typeMap = nil
		mc.writingTypeDefs = nil
	}
}

// SetScopedMetaShareEnabled sets the scoped meta share mode
func (mc *MetaContext) SetScopedMetaShareEnabled(enabled bool) {
	mc.scopedMetaShareEnabled = enabled
}

// IsScopedMetaShareEnabled returns whether scoped meta sharing is enabled
func (mc *MetaContext) IsScopedMetaShareEnabled() bool {
	return mc.scopedMetaShareEnabled
}

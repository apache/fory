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
	"testing"
	"github.com/stretchr/testify/require"
)

// TestMetaStringResolverNegativeIndexPanic reproduces the CRITICAL security bug 
// in MetaStringResolver where a header of 1 triggers a -1 index panic.
func TestMetaStringResolverNegativeIndexPanic(t *testing.T) {
	resolver := NewMetaStringResolver()
	buffer := NewByteBuffer(nil)
	
	// header = 1 means (header & 1 != 0) is true (it's a reference)
	// and length = header >> 1 = 0.
	// index = length - 1 = -1.
	buffer.WriteVarUint32Small7(1)
	buffer.SetReaderIndex(0)

	var ctxErr Error
	// This should NOT panic. The fix handles the negative index.
	require.NotPanics(t, func() {
		_, err := resolver.ReadMetaStringBytes(buffer, &ctxErr)
		if err == nil {
			t.Errorf("Expected error for negative index, got nil")
		}
	}, "MetaStringResolver should not panic on negative index")
}

// TestMetaStringResolverBoundaryRegression verifies that the smallest valid
// dynamic index (resulting in index 0) still resolves correctly.
func TestMetaStringResolverBoundaryRegression(t *testing.T) {
	resolver := NewMetaStringResolver()
	
	// Add a string to the cache so len is 1
	m := NewMetaStringBytes([]byte("test"), 123)
	resolver.dynamicIDToEnumString = append(resolver.dynamicIDToEnumString, m)

	// Craft a header that points to index 0
	// header = (index + 1) << 1 | 1 
	// For index 0: (0 + 1) << 1 | 1 = 3
	buffer := NewByteBuffer(nil)
	buffer.WriteVarUint32Small7(3) 
	buffer.SetReaderIndex(0)

	var ctxErr Error
	result, err := resolver.ReadMetaStringBytes(buffer, &ctxErr)
	require.NoError(t, err)
	require.Equal(t, m, result, "Should correctly resolve the first dynamic string (index 0)")
}

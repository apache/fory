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

	"github.com/apache/fory/go/fory"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

//go:generate fory -file structs.go

func TestValidationDemo(t *testing.T) {
	// 1. Create test instance
	original := &ValidationDemo{
		A: 12345,         // int32
		B: "Hello Fory!", // string
		C: 98765,         // int64
	}

	// Validate original data structure
	assert.Equal(t, int32(12345), original.A, "Original A should be 12345")
	assert.Equal(t, "Hello Fory!", original.B, "Original B should be 'Hello Fory!'")
	assert.Equal(t, int64(98765), original.C, "Original C should be 98765")

	// 2. Serialize using generated code
	f := fory.NewFory(true)
	data, err := f.Marshal(original)
	require.NoError(t, err, "Serialization should not fail")
	require.NotEmpty(t, data, "Serialized data should not be empty")
	assert.Greater(t, len(data), 0, "Serialized data should have positive length")

	// 3. Deserialize using generated code
	var result *ValidationDemo
	err = f.Unmarshal(data, &result)
	require.NoError(t, err, "Deserialization should not fail")
	require.NotNil(t, result, "Deserialized result should not be nil")

	// 4. Validate round-trip serialization
	assert.Equal(t, original.A, result.A, "Field A should match after round-trip")
	assert.Equal(t, original.B, result.B, "Field B should match after round-trip")
	assert.Equal(t, original.C, result.C, "Field C should match after round-trip")

	// 5. Validate data integrity
	assert.EqualValues(t, original, result, "Complete struct should match after round-trip")
}

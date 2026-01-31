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

	"github.com/apache/fory/go/fory/float16"
	"github.com/stretchr/testify/assert"
)

func TestFloat16Slice(t *testing.T) {
	f := NewFory()

	t.Run("float16_slice", func(t *testing.T) {
		slice := []float16.Float16{
			float16.Float16FromFloat32(1.0),
			float16.Float16FromFloat32(2.5),
			float16.Float16FromFloat32(-0.5),
		}
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []float16.Float16
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Equal(t, slice, result)
	})

	t.Run("float16_slice_empty", func(t *testing.T) {
		slice := []float16.Float16{}
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []float16.Float16
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.NotNil(t, result)
		assert.Empty(t, result)
	})

	t.Run("float16_slice_nil", func(t *testing.T) {
		var slice []float16.Float16 = nil
		data, err := f.Serialize(slice)
		assert.NoError(t, err)

		var result []float16.Float16
		err = f.Deserialize(data, &result)
		assert.NoError(t, err)
		assert.Nil(t, result)
	})
}

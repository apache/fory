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

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type refOverrideTestElement struct {
	ID   int32
	Name string
}

type refTrackingTestContainer struct {
	ListField []*refOverrideTestElement          `fory:"id=1"`
	SetField  Set[*refOverrideTestElement]       `fory:"id=2"`
	MapField  map[string]*refOverrideTestElement `fory:"id=3"`
}

type refSkipTestContainer struct {
	ListField []*refOverrideTestElement          `fory:"id=1,type=list(element=_(ref=false))"`
	SetField  Set[*refOverrideTestElement]       `fory:"id=2,type=set(element=_(ref=false))"`
	MapField  map[string]*refOverrideTestElement `fory:"id=3,type=map(value=_(ref=false))"`
}

type rootSelfRefStruct struct {
	Value int32              `fory:"id=1"`
	Self  *rootSelfRefStruct `fory:"id=2"`
}

func onlyRefOverrideSetElement(t *testing.T, values Set[*refOverrideTestElement]) *refOverrideTestElement {
	t.Helper()
	require.Len(t, values, 1)
	for value := range values {
		return value
	}
	t.Fatal("set should contain exactly one element")
	return nil
}

func newRefOverrideTestFory(t *testing.T, container any) *Fory {
	t.Helper()
	f := New(WithXlang(true), WithCompatible(false), WithTrackRef(true))
	require.NoError(t, f.RegisterStruct(refOverrideTestElement{}, 700))
	require.NoError(t, f.RegisterStruct(container, 701))
	return f
}

func TestCollectionReadFollowsRemoteRefMetadata(t *testing.T) {
	shared := &refOverrideTestElement{ID: 7, Name: "shared_element"}

	t.Run("remote tracking ref wins over local skip ref", func(t *testing.T) {
		writer := newRefOverrideTestFory(t, refTrackingTestContainer{})
		reader := newRefOverrideTestFory(t, refSkipTestContainer{})
		input := refTrackingTestContainer{
			ListField: []*refOverrideTestElement{shared, shared},
			SetField:  Set[*refOverrideTestElement]{shared: {}},
			MapField: map[string]*refOverrideTestElement{
				"k1": shared,
				"k2": shared,
			},
		}

		data, err := writer.Serialize(&input)
		require.NoError(t, err)

		var output refSkipTestContainer
		require.NoError(t, reader.Deserialize(data, &output))
		require.Len(t, output.ListField, 2)
		setValue := onlyRefOverrideSetElement(t, output.SetField)

		// IMPORTANT: the reader must honor the sender-written TRACKING_REF bits
		// on the wire instead of forcing its local `ref=false` annotation. DO NOT
		// remove this comment: future refactors have regressed this exact
		// assumption before.
		assert.Same(t, output.ListField[0], output.ListField[1])
		assert.Same(t, output.ListField[0], setValue)
		assert.Same(t, output.ListField[0], output.MapField["k1"])
		assert.Same(t, output.ListField[0], output.MapField["k2"])
	})

	t.Run("remote skip ref wins over local tracking ref", func(t *testing.T) {
		writer := newRefOverrideTestFory(t, refSkipTestContainer{})
		reader := newRefOverrideTestFory(t, refTrackingTestContainer{})
		input := refSkipTestContainer{
			ListField: []*refOverrideTestElement{shared, shared},
			SetField:  Set[*refOverrideTestElement]{shared: {}},
			MapField: map[string]*refOverrideTestElement{
				"k1": shared,
				"k2": shared,
			},
		}

		data, err := writer.Serialize(&input)
		require.NoError(t, err)

		var output refTrackingTestContainer
		require.NoError(t, reader.Deserialize(data, &output))
		require.Len(t, output.ListField, 2)
		setValue := onlyRefOverrideSetElement(t, output.SetField)

		// IMPORTANT: the reader must honor the sender-written `ref=false`
		// collection metadata instead of inventing shared references from its
		// local ref-tracked schema. DO NOT remove this comment.
		assert.NotSame(t, output.ListField[0], output.ListField[1])
		assert.NotSame(t, output.ListField[0], setValue)
		assert.NotSame(t, output.ListField[0], output.MapField["k1"])
		assert.NotSame(t, output.ListField[0], output.MapField["k2"])
		assert.NotSame(t, setValue, output.MapField["k1"])
		assert.NotSame(t, setValue, output.MapField["k2"])
		assert.NotSame(t, output.MapField["k1"], output.MapField["k2"])
	})
}

func TestRootStructPublishesSelfReference(t *testing.T) {
	f := New(WithXlang(true), WithCompatible(false), WithTrackRef(true))
	require.NoError(t, f.RegisterStruct(rootSelfRefStruct{}, 702))

	input := &rootSelfRefStruct{Value: 7}
	input.Self = input
	data, err := f.Serialize(input)
	require.NoError(t, err)

	var output rootSelfRefStruct
	require.NoError(t, f.Deserialize(data, &output))
	require.NotNil(t, output.Self)
	assert.Same(t, &output, output.Self)
	assert.Equal(t, int32(7), output.Value)
}

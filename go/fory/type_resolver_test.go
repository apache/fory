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
	"testing"

	"github.com/apache/fory/go/fory/optional"
	"github.com/stretchr/testify/require"
)

// Regression test for the lazy-serializer-init path in getTypeInfo.
//
// Before the fix, a createSerializer failure on this path silently discarded
// the error (fmt.Errorf result unused), stored a nil Serializer in the cache,
// and returned (info, nil) — hiding the failure from callers.
//
// After the fix the error is propagated and no nil serializer is cached.
func TestGetTypeInfo_LazyInitPropagatesError(t *testing.T) {
	f := New(WithCompatible(true))
	resolver := f.typeResolver

	// **int is explicitly rejected by createSerializer ("pointer to pointer is not
	// supported"), so it is a reliable trigger for the error path.
	ptrPtrInt := reflect.TypeOf((**int)(nil)) // **int

	// Seed typesInfo with an entry whose Serializer is nil, mimicking a type
	// that was registered but whose serializer has not been created yet.
	resolver.typesInfo[ptrPtrInt] = &TypeInfo{
		Type:       ptrPtrInt,
		Serializer: nil, // nil triggers the lazy-init branch
	}

	value := reflect.New(ptrPtrInt).Elem() // zero Value of type **int
	info, err := resolver.GetTypeInfo(value, true)

	// The bug: before the fix, err was nil and info had a nil Serializer.
	// The fix: err must be non-nil; info should be nil.
	require.Error(t, err, "expected error when createSerializer fails on lazy init, got nil")
	require.Nil(t, info, "expected nil TypeInfo when createSerializer fails, got non-nil")

	// Also verify the cache was NOT poisoned with a nil-serializer entry.
	typePtr := typePointer(ptrPtrInt)
	cached, ok := resolver.typePointerCache[typePtr]
	if ok {
		require.NotNil(t, cached.Serializer,
			"typePointerCache must not hold an entry with a nil Serializer")
	}
}

func TestTypeInfoPropagatesMetaError(t *testing.T) {
	expectedType := reflect.TypeOf(SimpleStruct{})
	tests := []struct {
		name string
		read func(*TypeResolver, *ByteBuffer, *Error) any
	}{
		{
			name: "with type id",
			read: func(resolver *TypeResolver, buffer *ByteBuffer, readErr *Error) any {
				return resolver.ReadTypeInfo(buffer, readErr)
			},
		},
		{
			name: "known type id",
			read: func(resolver *TypeResolver, buffer *ByteBuffer, readErr *Error) any {
				buffer.ReadUint8(readErr)
				return resolver.readTypeInfoWithTypeID(buffer, uint32(NAMED_STRUCT), nil, readErr)
			},
		},
		{
			name: "expected type",
			read: func(resolver *TypeResolver, buffer *ByteBuffer, readErr *Error) any {
				return resolver.readTypeInfoForType(buffer, expectedType, readErr)
			},
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			fory := NewFory(WithXlang(false), WithCompatible(false))
			buffer := NewByteBuffer(nil)
			buffer.WriteByte(byte(NAMED_STRUCT))
			buffer.WriteVarUint32Small7(1)
			buffer.SetReaderIndex(0)
			var readErr Error

			result := test.read(fory.typeResolver, buffer, &readErr)

			require.Nil(t, result)
			require.True(t, readErr.HasError())
			require.Contains(t, readErr.Error(), "invalid dynamic index")
		})
	}
}

func TestReadersStopOnTypeInfoError(t *testing.T) {
	optionalType := reflect.TypeOf(optional.None[*SimpleStruct]())
	info, ok := getOptionalInfo(optionalType)
	require.True(t, ok)

	tests := []struct {
		name string
		read func(*ReadContext)
	}{
		{
			name: "pointer",
			read: func(ctx *ReadContext) {
				var value *SimpleStruct
				serializer := &ptrToValueSerializer{}
				serializer.Read(ctx, RefModeNone, true, false, reflect.ValueOf(&value).Elem())
			},
		},
		{
			name: "optional",
			read: func(ctx *ReadContext) {
				value := reflect.New(optionalType).Elem()
				serializer := newOptionalSerializer(optionalType, info, nil)
				serializer.Read(ctx, RefModeNone, true, false, value)
			},
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			fory := NewFory(WithXlang(false), WithCompatible(false))
			ctx := fory.readCtx
			ctx.Reset()
			ctx.SetData([]byte{byte(NAMED_STRUCT), 1})

			require.NotPanics(t, func() {
				test.read(ctx)
			})
			require.True(t, ctx.HasError())
			require.Contains(t, ctx.Err().Error(), "invalid dynamic index")
		})
	}
}

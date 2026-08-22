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
)

// writeArrayRefAndType handles reference and type writing for array serializers.
// Arrays are value types, so they never produce back-references.
func writeArrayRefAndType(ctx *WriteContext, refMode RefMode, writeType bool, typeId TypeId) {
	if refMode != RefModeNone {
		ctx.Buffer().WriteInt8(NotNullValueFlag)
	}
	if writeType {
		ctx.Buffer().WriteUint8(uint8(typeId))
	}
}

// readArrayRefAndType handles reference and type reading for array serializers.
// Returns true if a reference was resolved (value already set), false if data should be read.
func readArrayRefAndType(ctx *ReadContext, refMode RefMode, readType bool, value reflect.Value) bool {
	done := readSliceOrArrayRef(ctx, refMode, value)
	if done || ctx.HasError() {
		return done
	}
	if readType {
		typeID := uint32(ctx.Buffer().ReadUint8(ctx.Err()))
		if ctx.HasError() {
			return false
		}
		if typeID != uint32(LIST) {
			ctx.SetError(DeserializationErrorf("array type mismatch: expected LIST (%d), got %d", LIST, typeID))
			return false
		}
	}
	return false
}

// arrayConcreteValueSerializer serialize an array/*array
type arrayConcreteValueSerializer struct {
	type_          reflect.Type
	elemSerializer Serializer
	referencable   bool
}

func (s *arrayConcreteValueSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	length := value.Len()
	buf := ctx.Buffer()
	ctxErr := ctx.Err()

	// Write length
	buf.WriteVarUint32(uint32(length))
	if length == 0 {
		return
	}

	// Determine collection flags - same logic as slices
	collectFlag := CollectionIsSameType
	hasNull := false
	elemType := s.type_.Elem()
	isPointerElem := elemType.Kind() == reflect.Ptr
	var firstNonNull reflect.Value

	// Check for null values (only for pointer element types)
	if isPointerElem {
		for i := 0; i < length; i++ {
			elem := value.Index(i)
			if elem.IsNil() {
				hasNull = true
			} else if !firstNonNull.IsValid() {
				firstNonNull = elem.Elem()
			}
			if hasNull && firstNonNull.IsValid() {
				break
			}
		}
	}

	// Preserve the existing value-based lookup for ordinary writes. Only an
	// all-null pointer array needs the registered static element descriptor.
	var elemTypeInfo *TypeInfo
	var typeErr error
	if isPointerElem {
		if firstNonNull.IsValid() {
			elemTypeInfo, typeErr = ctx.TypeResolver().GetTypeInfo(firstNonNull, true)
		} else {
			elemTypeInfo = ctx.TypeResolver().getTypeInfoByType(elemType.Elem())
			if elemTypeInfo == nil {
				elemTypeInfo, typeErr = ctx.TypeResolver().GetTypeInfo(reflect.Zero(elemType.Elem()), true)
			}
		}
	} else {
		elemTypeInfo, typeErr = ctx.TypeResolver().GetTypeInfo(value.Index(0), true)
	}
	if typeErr != nil {
		ctxErr.SetError(typeErr)
		return
	}
	trackRefs := ctx.TrackRef() && s.referencable
	if hasNull {
		collectFlag |= CollectionHasNull
	}
	if trackRefs {
		collectFlag |= CollectionTrackingRef
	}
	buf.WriteInt8(int8(collectFlag))

	// Write element type info (handles namespaced types)
	var internalTypeID uint32
	if elemTypeInfo != nil {
		internalTypeID = elemTypeInfo.TypeID
	}
	if elemTypeInfo != nil {
		ctx.TypeResolver().WriteTypeInfo(buf, elemTypeInfo, ctxErr)
	} else {
		buf.WriteUint8(uint8(internalTypeID))
	}

	// Write elements
	for i := 0; i < length; i++ {
		elem := value.Index(i)

		// Handle null values (only for pointer element types)
		if hasNull && elem.IsNil() {
			if trackRefs {
				s.elemSerializer.Write(ctx, RefModeTracking, false, false, elem)
				if ctx.HasError() {
					return
				}
			} else {
				buf.WriteInt8(NullFlag)
			}
			continue
		}

		// Write element
		if trackRefs {
			s.elemSerializer.Write(ctx, RefModeTracking, false, false, elem)
			if ctx.HasError() {
				return
			}
		} else if hasNull {
			buf.WriteInt8(NotNullValueFlag)
			s.elemSerializer.WriteData(ctx, elem)
			if ctx.HasError() {
				return
			}
		} else {
			s.elemSerializer.WriteData(ctx, elem)
			if ctx.HasError() {
				return
			}
		}
	}
}

func (s *arrayConcreteValueSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	writeArrayRefAndType(ctx, refMode, writeType, LIST)
	if ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s *arrayConcreteValueSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	if ctx.HasError() || !ctx.enterDepth() {
		return
	}
	buf := ctx.Buffer()
	err := ctx.Err()
	length := int(buf.ReadVarUint32(err))
	if ctx.HasError() {
		return
	}
	if length != value.Len() {
		ctx.SetError(DeserializationErrorf("array length %d does not match serialized length %d", value.Len(), length))
		return
	}

	var trackRefs bool
	var hasNull bool
	if length > 0 {
		// Read collection flags (same format as slices)
		collectFlag := buf.ReadInt8(err)
		if ctx.HasError() {
			return
		}

		// Read element type info if present
		trackRefs = (collectFlag & CollectionTrackingRef) != 0
		hasNull = (collectFlag & CollectionHasNull) != 0
		if (collectFlag & CollectionIsSameType) != 0 {
			if (collectFlag & CollectionIsDeclElementType) == 0 {
				ctx.TypeResolver().ReadTypeInfo(buf, err)
			}
		}
	}

	elementReadAlwaysAdvances := trackRefs || hasNull || serializerReadDataAlwaysAdvances(s.elemSerializer)
	if !elementReadAlwaysAdvances {
		checkpoint := buf.logicalReaderIndex()
		for i := 0; i < length; i++ {
			elem := value.Index(i)
			s.elemSerializer.ReadData(ctx, elem)
			if ctx.HasError() {
				return
			}
			if (i+1)&(unbackedContainerCheckInterval-1) == 0 {
				if !ctx.settleUnbackedContainerItems(unbackedContainerCheckInterval, checkpoint) {
					return
				}
				checkpoint = buf.logicalReaderIndex()
			}
		}
		if tail := length & (unbackedContainerCheckInterval - 1); tail != 0 {
			if !ctx.settleUnbackedContainerItems(tail, checkpoint) {
				return
			}
		}
		ctx.decDepth()
		return
	}

	for i := 0; i < length && i < value.Len(); i++ {
		elem := value.Index(i)

		if trackRefs {
			// When tracking refs, the element serializer handles ref flags
			s.elemSerializer.Read(ctx, RefModeTracking, false, false, elem)
		} else if hasNull {
			flag := buf.ReadInt8(err)
			if flag == NullFlag {
				continue
			}
			s.elemSerializer.ReadData(ctx, elem)
		} else {
			s.elemSerializer.ReadData(ctx, elem)
		}
		if ctx.HasError() {
			return
		}
	}
	ctx.decDepth()
}

func (s *arrayConcreteValueSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done := readArrayRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
	if ctx.HasError() {
		return
	}
}

func (s *arrayConcreteValueSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

// arrayDynSerializer reuses slice wire logic for arrays with interface elements.
// Writes reuse its indexing path while reads target the caller-owned array directly.
type arrayDynSerializer struct {
	// Keep a pointer to the delegated slice serializer so array dynamic reads do not copy
	// slice serializer state.
	sliceSerializer *sliceDynSerializer
}

func newArrayDynSerializer(elemType reflect.Type) (*arrayDynSerializer, error) {
	sliceSer, err := newSliceDynSerializer(elemType)
	if err != nil {
		return nil, err
	}
	return &arrayDynSerializer{sliceSerializer: sliceSer}, nil
}

func (s *arrayDynSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	// The delegated writer only indexes the sequence. Passing the array directly
	// also supports unaddressable arrays obtained from interface values; slicing
	// such an array would panic.
	s.sliceSerializer.WriteData(ctx, value)
}

func (s *arrayDynSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	writeArrayRefAndType(ctx, refMode, writeType, LIST)
	if ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s *arrayDynSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	// The shared array ref path publishes the slice wire owner before children
	// can resolve back-references.
	s.sliceSerializer.readData(ctx, value, value.Len())
}

func (s *arrayDynSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done := readArrayRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s *arrayDynSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

type byteArraySerializer struct{}

func (s byteArraySerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	buf := ctx.Buffer()
	length := value.Len()
	buf.WriteLength(length)
	if value.CanAddr() {
		buf.WriteBinary(value.Slice(0, length).Bytes())
	} else {
		data := make([]byte, length)
		for i := 0; i < length; i++ {
			data[i] = byte(value.Index(i).Uint())
		}
		buf.WriteBinary(data)
	}
}

func (s byteArraySerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	writeArrayRefAndType(ctx, refMode, writeType, BINARY)
	if ctx.HasError() {
		return
	}
	s.WriteData(ctx, value)
}

func (s byteArraySerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	buf := ctx.Buffer()
	err := ctx.Err()
	length := buf.ReadLength(err)
	if ctx.HasError() {
		return
	}
	if length != value.Len() {
		ctx.SetError(DeserializationErrorf("array length %d does not match serialized binary length %d", value.Len(), length))
		return
	}
	if !buf.CheckReadable(length, err) {
		return
	}
	if length == 0 {
		return
	}
	if value.CanAddr() {
		buf.Read(value.Slice(0, length).Bytes())
		return
	}
	data := make([]byte, length)
	buf.Read(data)
	for i := 0; i < length && i < value.Len(); i++ {
		value.Index(i).SetUint(uint64(data[i]))
	}
}

func (s byteArraySerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done := readArrayRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s byteArraySerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

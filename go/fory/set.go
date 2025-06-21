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

// GenericSet type.
// TODO use golang generics; support more concrete key types
type GenericSet map[interface{}]bool

func (s GenericSet) Add(values ...interface{}) {
	for _, v := range values {
		s[v] = true
	}
}

type setSerializer struct {
}

func (s setSerializer) TypeId() TypeId {
	return SET
}

func (s setSerializer) NeedWriteRef() bool {
	return true
}

func (s setSerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	// Get all map keys (set elements)
	keys := value.MapKeys()
	length := len(keys)

	// Handle empty set case
	if length == 0 {
		buf.WriteVarUint32(0) // Write 0 length for empty set
		return nil
	}

	// Write collection header and get type information
	collectFlag, elemTypeInfo := s.writeHeader(f, buf, keys)

	// Check if all elements are of same type
	if (collectFlag & CollectionNotSameType) == 0 {
		// Optimized path for same-type elements
		return s.writeSameType(f, buf, keys, elemTypeInfo, collectFlag)
	}
	// Fallback path for mixed-type elements
	return s.writeDifferentTypes(f, buf, keys)
}

// writeHeader prepares and writes collection metadata including:
// - Collection size
// - Type consistency flags
// - Element type information (if homogeneous)
func (s setSerializer) writeHeader(f *Fory, buf *ByteBuffer, keys []reflect.Value) (byte, TypeInfo) {
	// Initialize collection flags and type tracking variables
	collectFlag := CollectionDefaultFlag
	var elemTypeInfo TypeInfo
	hasNull := false
	hasDifferentType := false

	// Check elements to detect types
	// Initialize element type information from first non-null element
	if len(keys) > 0 {
		firstElem := UnwrapReflectValue(keys[0])
		if isNull(firstElem) {
			hasNull = true
		} else {
			// Get type info for first element to use as reference
			elemTypeInfo, _ = f.typeResolver.getTypeInfo(firstElem, true)
		}
	}

	// Iterate through elements to check for nulls and type consistency
	for _, key := range keys {
		key = UnwrapReflectValue(key)
		if isNull(key) {
			hasNull = true
			continue
		}

		// Compare each element's type with the reference type
		currentTypeInfo, _ := f.typeResolver.getTypeInfo(key, true)
		if currentTypeInfo.TypeID != elemTypeInfo.TypeID {
			hasDifferentType = true
		}
	}

	// Set collection flags based on findings
	if hasNull {
		collectFlag |= CollectionHasNull // Mark if collection contains null values
	}
	if hasDifferentType {
		collectFlag |= CollectionNotSameType // Mark if elements have different types
	}

	// Enable reference tracking if configured
	if f.referenceTracking {
		collectFlag |= CollectionTrackingRef
	}

	// Write metadata to buffer
	buf.WriteVarUint32(uint32(len(keys))) // Collection size
	buf.WriteInt8(int8(collectFlag))      // Collection flags

	// Write element type ID if all elements have same type
	if !hasDifferentType {
		buf.WriteVarInt32(elemTypeInfo.TypeID)
	}

	return byte(collectFlag), elemTypeInfo
}

// writeSameType efficiently serializes a collection where all elements share the same type
func (s setSerializer) writeSameType(f *Fory, buf *ByteBuffer, keys []reflect.Value, typeInfo TypeInfo, flag byte) error {
	serializer := typeInfo.Serializer
	trackRefs := (flag & CollectionTrackingRef) != 0 // Check if reference tracking is enabled

	for _, key := range keys {
		key = UnwrapReflectValue(key)
		if isNull(key) {
			buf.WriteInt8(NullFlag) // Write null marker
			continue
		}

		if trackRefs {
			// Handle reference tracking if enabled
			refWritten, err := f.refResolver.WriteRefOrNull(buf, key)
			if err != nil {
				return err
			}
			if !refWritten {
				// Write actual value if not a reference
				if err := serializer.Write(f, buf, key); err != nil {
					return err
				}
			}
		} else {
			// Directly write value without reference tracking
			if err := serializer.Write(f, buf, key); err != nil {
				return err
			}
		}
	}
	return nil
}

// writeDifferentTypes handles serialization of collections with mixed element types
func (s setSerializer) writeDifferentTypes(f *Fory, buf *ByteBuffer, keys []reflect.Value) error {
	for _, key := range keys {
		key = UnwrapReflectValue(key)
		if isNull(key) {
			buf.WriteInt8(NullFlag) // Write null marker
			continue
		}

		// Get type info for each element (since types vary)
		typeInfo, _ := f.typeResolver.getTypeInfo(key, true)

		// Handle reference tracking
		refWritten, err := f.refResolver.WriteRefOrNull(buf, key)
		if err != nil {
			return err
		}

		// Write type ID for each element
		buf.WriteVarInt32(typeInfo.TypeID)

		if !refWritten {
			// Write actual value if not a reference
			if err := typeInfo.Serializer.Write(f, buf, key); err != nil {
				return err
			}
		}
	}
	return nil
}

// Read deserializes a set from the buffer into the provided reflect.Value
func (s setSerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	// Read collection length from buffer
	length := int(buf.ReadVarUint32())
	if length == 0 {
		// Initialize empty set if length is 0
		value.Set(reflect.MakeMap(type_))
		return nil
	}

	// Read collection flags that indicate special characteristics
	collectFlag := buf.ReadInt8()
	var elemTypeInfo TypeInfo

	// If all elements are same type, read the shared type info
	if (collectFlag & CollectionNotSameType) == 0 {
		typeID := buf.ReadVarInt32()
		elemTypeInfo, _ = f.typeResolver.getTypeInfoById(int16(typeID))
	}

	// Initialize set if nil
	if value.IsNil() {
		value.Set(reflect.MakeMap(type_))
	}
	// Register reference for tracking
	f.refResolver.Reference(value)

	// Choose appropriate deserialization path based on type consistency
	if (collectFlag & CollectionNotSameType) == 0 {
		return s.readSameType(f, buf, value, elemTypeInfo, collectFlag, length)
	}
	return s.readDifferentTypes(f, buf, value, length)
}

// readSameType handles deserialization of sets where all elements share the same type
func (s setSerializer) readSameType(f *Fory, buf *ByteBuffer, value reflect.Value, typeInfo TypeInfo, flag int8, length int) error {
	// Determine if reference tracking is enabled
	trackRefs := (flag & CollectionTrackingRef) != 0
	serializer := typeInfo.Serializer

	for i := 0; i < length; i++ {
		var refID int32
		if trackRefs {
			// Handle reference tracking if enabled
			refID, _ = f.refResolver.TryPreserveRefId(buf)
			if int8(refID) < NotNullValueFlag {
				// Use existing reference if available
				elem := f.refResolver.GetReadObject(refID)
				value.SetMapIndex(reflect.ValueOf(elem), reflect.ValueOf(true))
				continue
			}
		}

		// Create new element and deserialize from buffer
		elem := reflect.New(typeInfo.Type).Elem()
		if err := serializer.Read(f, buf, elem.Type(), elem); err != nil {
			return err
		}

		// Register new reference if tracking
		if trackRefs {
			f.refResolver.SetReadObject(refID, elem)
		}
		// Add element to set
		value.SetMapIndex(elem, reflect.ValueOf(true))
	}
	return nil
}

// readDifferentTypes handles deserialization of sets with mixed element types
func (s setSerializer) readDifferentTypes(f *Fory, buf *ByteBuffer, value reflect.Value, length int) error {
	for i := 0; i < length; i++ {
		// Handle reference tracking for each element
		refID, _ := f.refResolver.TryPreserveRefId(buf)
		// Read type ID for each element (since types vary)
		typeID := buf.ReadVarInt32()
		typeInfo, _ := f.typeResolver.getTypeInfoById(int16(typeID))

		if int8(refID) < NotNullValueFlag {
			// Use existing reference if available
			elem := f.refResolver.GetReadObject(refID)
			value.SetMapIndex(reflect.ValueOf(elem), reflect.ValueOf(true))
			continue
		}

		// Create new element and deserialize from buffer
		elem := reflect.New(typeInfo.Type).Elem()
		if err := typeInfo.Serializer.Read(f, buf, elem.Type(), elem); err != nil {
			return err
		}

		// Register new reference
		f.refResolver.SetReadObject(refID, elem)
		// Add element to set
		value.SetMapIndex(elem, reflect.ValueOf(true))
	}
	return nil
}

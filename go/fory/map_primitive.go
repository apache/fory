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

// ============================================================================
// Optimized map serializers for common types
// ============================================================================

// writeMapStringString writes map[string]string using chunk protocol
// When hasGenerics=true, element types are known so we set DECL_TYPE flags and skip type info
func writeMapStringString(buf *ByteBuffer, m map[string]string, hasGenerics bool) {
	length := len(m)
	buf.WriteVarUint32(uint32(length))
	if length == 0 {
		return
	}

	// WriteData chunks of entries
	remaining := length
	for remaining > 0 {
		chunkSize := remaining
		if chunkSize > MAX_CHUNK_SIZE {
			chunkSize = MAX_CHUNK_SIZE
		}

		if hasGenerics {
			// Element types are known from generics, set DECL_TYPE flags
			buf.WriteUint8(KEY_DECL_TYPE | VALUE_DECL_TYPE)
			buf.WriteUint8(uint8(chunkSize))
		} else {
			// Write type info for generic reader compatibility
			buf.WriteUint8(0)
			buf.WriteUint8(uint8(chunkSize))
			buf.WriteUint8(uint8(STRING)) // key type
			buf.WriteUint8(uint8(STRING)) // value type
		}

		// WriteData chunk entries
		count := 0
		for k, v := range m {
			writeString(buf, k)
			writeString(buf, v)
			count++
			if count >= chunkSize {
				break
			}
		}
		remaining -= chunkSize
	}
}

// readMapStringString reads map[string]string using chunk protocol
func readMapStringString(buf *ByteBuffer, err *Error, maxStringBytes, maxMapSize int) map[string]string {
	size := int(buf.ReadVarUint32(err))
if maxMapSize > 0 && size > maxMapSize {  
    err.SetError(DeserializationErrorf(   
        "fory: map size %d exceeds limit %d", size, maxMapSize)) 
    return nil                           
}
	result := make(map[string]string, size)
	if size == 0 {
		return result
	}

	for size > 0 {
		chunkHeader := buf.ReadUint8(err)

		// Handle null key/value cases
		keyHasNull := (chunkHeader & KEY_HAS_NULL) != 0
		valueHasNull := (chunkHeader & VALUE_HAS_NULL) != 0

		if keyHasNull && valueHasNull {
			// Both null - use empty strings for key and value
			result[""] = ""
			size--
			continue
		} else if keyHasNull {
			// Null key with non-null value
			valueDeclared := (chunkHeader & VALUE_DECL_TYPE) != 0
			if !valueDeclared {
				buf.ReadUint8(err) // skip value type
			}
			v := readString(buf, err, maxStringBytes)

			result[""] = v // empty string as null key
			size--
			continue
		} else if valueHasNull {
			// Non-null key with null value
			keyDeclared := (chunkHeader & KEY_DECL_TYPE) != 0
			if !keyDeclared {
				buf.ReadUint8(err) // skip key type
			}
			k := readString(buf, err, maxStringBytes)

			result[k] = "" // empty string as null value
			size--
			continue
		}

		// ReadData chunk size
		chunkSize := int(buf.ReadUint8(err))

		// Read type info if not DECL_TYPE
		if (chunkHeader & KEY_DECL_TYPE) == 0 {
			buf.ReadUint8(err) // skip key type
		}
		if (chunkHeader & VALUE_DECL_TYPE) == 0 {
			buf.ReadUint8(err) // skip value type
		}

		// ReadData chunk entries
		for i := 0; i < chunkSize && size > 0; i++ {
			k := readString(buf, err, maxStringBytes)

			v := readString(buf, err, maxStringBytes)

			result[k] = v
			size--
		}
	}
	return result
}

// writeMapStringInt64 writes map[string]int64 using chunk protocol
// When hasGenerics=true, element types are known so we set DECL_TYPE flags and skip type info
func writeMapStringInt64(buf *ByteBuffer, m map[string]int64, hasGenerics bool) {
	length := len(m)
	buf.WriteVarUint32(uint32(length))
	if length == 0 {
		return
	}

	remaining := length
	for remaining > 0 {
		chunkSize := remaining
		if chunkSize > MAX_CHUNK_SIZE {
			chunkSize = MAX_CHUNK_SIZE
		}

		if hasGenerics {
			buf.WriteUint8(KEY_DECL_TYPE | VALUE_DECL_TYPE)
			buf.WriteUint8(uint8(chunkSize))
		} else {
			buf.WriteUint8(0)
			buf.WriteUint8(uint8(chunkSize))
			buf.WriteUint8(uint8(STRING))   // key type
			buf.WriteUint8(uint8(VARINT64)) // value type
		}

		count := 0
		for k, v := range m {
			writeString(buf, k)
			buf.WriteVarint64(v)
			count++
			if count >= chunkSize {
				break
			}
		}
		remaining -= chunkSize
	}
}

// readMapStringInt64 reads map[string]int64 using chunk protocol
func readMapStringInt64(buf *ByteBuffer, err *Error, maxStringBytes, maxMapSize int) map[string]int64 {
	size := int(buf.ReadVarUint32(err))
if maxMapSize > 0 && size > maxMapSize {  
    err.SetError(DeserializationErrorf(   
        "fory: map size %d exceeds limit %d", size, maxMapSize)) 
    return nil                           
}
	result := make(map[string]int64, size)
	if size == 0 {
		return result
	}

	for size > 0 {
		chunkHeader := buf.ReadUint8(err)
		keyHasNull := (chunkHeader & KEY_HAS_NULL) != 0
		valueHasNull := (chunkHeader & VALUE_HAS_NULL) != 0

		if keyHasNull || valueHasNull {
			size--
			continue
		}

		chunkSize := int(buf.ReadUint8(err))
		if (chunkHeader & KEY_DECL_TYPE) == 0 {
			buf.ReadUint8(err)
		}
		if (chunkHeader & VALUE_DECL_TYPE) == 0 {
			buf.ReadUint8(err)
		}
		for i := 0; i < chunkSize && size > 0; i++ {
			k := readString(buf, err, maxStringBytes)

			v := buf.ReadVarint64(err)
			result[k] = v
			size--
		}
	}
	return result
}

// writeMapStringInt32 writes map[string]int32 using chunk protocol
// When hasGenerics=true, element types are known so we set DECL_TYPE flags and skip type info
func writeMapStringInt32(buf *ByteBuffer, m map[string]int32, hasGenerics bool) {
	length := len(m)
	buf.WriteVarUint32(uint32(length))
	if length == 0 {
		return
	}

	remaining := length
	for remaining > 0 {
		chunkSize := remaining
		if chunkSize > MAX_CHUNK_SIZE {
			chunkSize = MAX_CHUNK_SIZE
		}

		if hasGenerics {
			buf.WriteUint8(KEY_DECL_TYPE | VALUE_DECL_TYPE)
			buf.WriteUint8(uint8(chunkSize))
		} else {
			buf.WriteUint8(0)
			buf.WriteUint8(uint8(chunkSize))
			buf.WriteUint8(uint8(STRING))   // key type
			buf.WriteUint8(uint8(VARINT32)) // value type
		}

		count := 0
		for k, v := range m {
			writeString(buf, k)
			buf.WriteVarint32(v)
			count++
			if count >= chunkSize {
				break
			}
		}
		remaining -= chunkSize
	}
}

// readMapStringInt32 reads map[string]int32 using chunk protocol
func readMapStringInt32(buf *ByteBuffer, err *Error, maxStringBytes, maxMapSize int) map[string]int32 {
	size := int(buf.ReadVarUint32(err))
if maxMapSize > 0 && size > maxMapSize {  
    err.SetError(DeserializationErrorf(   
        "fory: map size %d exceeds limit %d", size, maxMapSize)) 
    return nil                           
}
	result := make(map[string]int32, size)
	if size == 0 {
		return result
	}

	for size > 0 {
		chunkHeader := buf.ReadUint8(err)
		keyHasNull := (chunkHeader & KEY_HAS_NULL) != 0
		valueHasNull := (chunkHeader & VALUE_HAS_NULL) != 0

		if keyHasNull || valueHasNull {
			size--
			continue
		}

		chunkSize := int(buf.ReadUint8(err))
		if (chunkHeader & KEY_DECL_TYPE) == 0 {
			buf.ReadUint8(err)
		}
		if (chunkHeader & VALUE_DECL_TYPE) == 0 {
			buf.ReadUint8(err)
		}
		for i := 0; i < chunkSize && size > 0; i++ {
			k := readString(buf, err, maxStringBytes)

			v := buf.ReadVarint32(err)
			result[k] = v
			size--
		}
	}
	return result
}

// writeMapStringInt writes map[string]int using chunk protocol
// When hasGenerics=true, element types are known so we set DECL_TYPE flags and skip type info
func writeMapStringInt(buf *ByteBuffer, m map[string]int, hasGenerics bool) {
	length := len(m)
	buf.WriteVarUint32(uint32(length))
	if length == 0 {
		return
	}

	remaining := length
	for remaining > 0 {
		chunkSize := remaining
		if chunkSize > MAX_CHUNK_SIZE {
			chunkSize = MAX_CHUNK_SIZE
		}

		if hasGenerics {
			buf.WriteUint8(KEY_DECL_TYPE | VALUE_DECL_TYPE)
			buf.WriteUint8(uint8(chunkSize))
		} else {
			buf.WriteUint8(0)
			buf.WriteUint8(uint8(chunkSize))
			buf.WriteUint8(uint8(STRING))   // key type
			buf.WriteUint8(uint8(VARINT64)) // value type (int serialized as varint64)
		}

		count := 0
		for k, v := range m {
			writeString(buf, k)
			buf.WriteVarint64(int64(v))
			count++
			if count >= chunkSize {
				break
			}
		}
		remaining -= chunkSize
	}
}

// readMapStringInt reads map[string]int using chunk protocol
func readMapStringInt(buf *ByteBuffer, err *Error, maxStringBytes, maxMapSize int) map[string]int {
	size := int(buf.ReadVarUint32(err))
if maxMapSize > 0 && size > maxMapSize {  
    err.SetError(DeserializationErrorf(   
        "fory: map size %d exceeds limit %d", size, maxMapSize)) 
    return nil                           
}
	result := make(map[string]int, size)
	if size == 0 {
		return result
	}

	for size > 0 {
		chunkHeader := buf.ReadUint8(err)
		keyHasNull := (chunkHeader & KEY_HAS_NULL) != 0
		valueHasNull := (chunkHeader & VALUE_HAS_NULL) != 0

		if keyHasNull || valueHasNull {
			size--
			continue
		}

		chunkSize := int(buf.ReadUint8(err))
		if (chunkHeader & KEY_DECL_TYPE) == 0 {
			buf.ReadUint8(err)
		}
		if (chunkHeader & VALUE_DECL_TYPE) == 0 {
			buf.ReadUint8(err)
		}
		for i := 0; i < chunkSize && size > 0; i++ {
			k := readString(buf, err, maxStringBytes)

			v := buf.ReadVarint64(err)
			result[k] = int(v)
			size--
		}
	}
	return result
}

// writeMapStringFloat64 writes map[string]float64 using chunk protocol
// When hasGenerics=true, element types are known so we set DECL_TYPE flags and skip type info
func writeMapStringFloat64(buf *ByteBuffer, m map[string]float64, hasGenerics bool) {
	length := len(m)
	buf.WriteVarUint32(uint32(length))
	if length == 0 {
		return
	}

	remaining := length
	for remaining > 0 {
		chunkSize := remaining
		if chunkSize > MAX_CHUNK_SIZE {
			chunkSize = MAX_CHUNK_SIZE
		}

		if hasGenerics {
			buf.WriteUint8(KEY_DECL_TYPE | VALUE_DECL_TYPE)
			buf.WriteUint8(uint8(chunkSize))
		} else {
			buf.WriteUint8(0)
			buf.WriteUint8(uint8(chunkSize))
			buf.WriteUint8(uint8(STRING))  // key type
			buf.WriteUint8(uint8(FLOAT64)) // value type
		}

		count := 0
		for k, v := range m {
			writeString(buf, k)
			buf.WriteFloat64(v)
			count++
			if count >= chunkSize {
				break
			}
		}
		remaining -= chunkSize
	}
}

// readMapStringFloat64 reads map[string]float64 using chunk protocol
func readMapStringFloat64(buf *ByteBuffer, err *Error, maxStringBytes, maxMapSize int) map[string]float64 {
	size := int(buf.ReadVarUint32(err))
if maxMapSize > 0 && size > maxMapSize {  
    err.SetError(DeserializationErrorf(   
        "fory: map size %d exceeds limit %d", size, maxMapSize)) 
    return nil                           
}
	result := make(map[string]float64, size)
	if size == 0 {
		return result
	}

	for size > 0 {
		chunkHeader := buf.ReadUint8(err)
		keyHasNull := (chunkHeader & KEY_HAS_NULL) != 0
		valueHasNull := (chunkHeader & VALUE_HAS_NULL) != 0

		if keyHasNull || valueHasNull {
			size--
			continue
		}

		chunkSize := int(buf.ReadUint8(err))
		if (chunkHeader & KEY_DECL_TYPE) == 0 {
			buf.ReadUint8(err)
		}
		if (chunkHeader & VALUE_DECL_TYPE) == 0 {
			buf.ReadUint8(err)
		}
		for i := 0; i < chunkSize && size > 0; i++ {
			k := readString(buf, err, maxStringBytes)

			v := buf.ReadFloat64(err)
			result[k] = v
			size--
		}
	}
	return result
}

// writeMapStringBool writes map[string]bool using chunk protocol
// When hasGenerics=true, element types are known so we set DECL_TYPE flags and skip type info
func writeMapStringBool(buf *ByteBuffer, m map[string]bool, hasGenerics bool) {
	length := len(m)
	buf.WriteVarUint32(uint32(length))
	if length == 0 {
		return
	}

	remaining := length
	for remaining > 0 {
		chunkSize := remaining
		if chunkSize > MAX_CHUNK_SIZE {
			chunkSize = MAX_CHUNK_SIZE
		}

		if hasGenerics {
			buf.WriteUint8(KEY_DECL_TYPE | VALUE_DECL_TYPE)
			buf.WriteUint8(uint8(chunkSize))
		} else {
			buf.WriteUint8(0)
			buf.WriteUint8(uint8(chunkSize))
			buf.WriteUint8(uint8(STRING)) // key type
			buf.WriteUint8(uint8(BOOL))   // value type
		}

		count := 0
		for k, v := range m {
			writeString(buf, k)
			buf.WriteBool(v)
			count++
			if count >= chunkSize {
				break
			}
		}
		remaining -= chunkSize
	}
}

// readMapStringBool reads map[string]bool using chunk protocol
func readMapStringBool(buf *ByteBuffer, err *Error, maxStringBytes, maxMapSize int) map[string]bool {
	size := int(buf.ReadVarUint32(err))
	if maxMapSize > 0 && size > maxMapSize {  
		err.SetError(DeserializationErrorf(   
			"fory: map size %d exceeds limit %d", size, maxMapSize)) 
		return nil                           
	}
	result := make(map[string]bool, size)
	if size == 0 {
		return result
	}

	for size > 0 {
		chunkHeader := buf.ReadUint8(err)
		keyHasNull := (chunkHeader & KEY_HAS_NULL) != 0
		valueHasNull := (chunkHeader & VALUE_HAS_NULL) != 0

		if keyHasNull || valueHasNull {
			size--
			continue
		}

		chunkSize := int(buf.ReadUint8(err))

		// Read type info (written by writeMapStringBool)
		keyDeclType := (chunkHeader & KEY_DECL_TYPE) != 0
		valDeclType := (chunkHeader & VALUE_DECL_TYPE) != 0
		if !keyDeclType {
			buf.ReadUint8(err) // skip key type info
		}
		if !valDeclType {
			buf.ReadUint8(err) // skip value type info
		}

		for i := 0; i < chunkSize && size > 0; i++ {
			k := readString(buf, err, maxStringBytes)

			v := buf.ReadBool(err)
			result[k] = v
			size--
		}
	}
	return result
}

// writeMapInt32Int32 writes map[int32]int32 using chunk protocol
// When hasGenerics=true, element types are known so we set DECL_TYPE flags and skip type info
func writeMapInt32Int32(buf *ByteBuffer, m map[int32]int32, hasGenerics bool) {
	length := len(m)
	buf.WriteVarUint32(uint32(length))
	if length == 0 {
		return
	}

	remaining := length
	for remaining > 0 {
		chunkSize := remaining
		if chunkSize > MAX_CHUNK_SIZE {
			chunkSize = MAX_CHUNK_SIZE
		}

		if hasGenerics {
			buf.WriteUint8(KEY_DECL_TYPE | VALUE_DECL_TYPE)
			buf.WriteUint8(uint8(chunkSize))
		} else {
			buf.WriteUint8(0)
			buf.WriteUint8(uint8(chunkSize))
			buf.WriteUint8(uint8(VARINT32)) // key type
			buf.WriteUint8(uint8(VARINT32)) // value type
		}

		count := 0
		for k, v := range m {
			buf.WriteVarint32(k)
			buf.WriteVarint32(v)
			count++
			if count >= chunkSize {
				break
			}
		}
		remaining -= chunkSize
	}
}

// readMapInt32Int32 reads map[int32]int32 using chunk protocol
func readMapInt32Int32(buf *ByteBuffer, err *Error) map[int32]int32 {
	size := int(buf.ReadVarUint32(err))
if maxMapSize > 0 && size > maxMapSize {  
    err.SetError(DeserializationErrorf(   
        "fory: map size %d exceeds limit %d", size, maxMapSize)) 
    return nil                           
}
	result := make(map[int32]int32, size)
	if size == 0 {
		return result
	}

	for size > 0 {
		chunkHeader := buf.ReadUint8(err)
		keyHasNull := (chunkHeader & KEY_HAS_NULL) != 0
		valueHasNull := (chunkHeader & VALUE_HAS_NULL) != 0

		if keyHasNull || valueHasNull {
			size--
			continue
		}

		chunkSize := int(buf.ReadUint8(err))
		if (chunkHeader & KEY_DECL_TYPE) == 0 {
			buf.ReadUint8(err)
		}
		if (chunkHeader & VALUE_DECL_TYPE) == 0 {
			buf.ReadUint8(err)
		}
		for i := 0; i < chunkSize && size > 0; i++ {
			k := buf.ReadVarint32(err)
			v := buf.ReadVarint32(err)
			result[k] = v
			size--
		}
	}
	return result
}

// writeMapInt64Int64 writes map[int64]int64 using chunk protocol
// When hasGenerics=true, element types are known so we set DECL_TYPE flags and skip type info
func writeMapInt64Int64(buf *ByteBuffer, m map[int64]int64, hasGenerics bool) {
	length := len(m)
	buf.WriteVarUint32(uint32(length))
	if length == 0 {
		return
	}

	remaining := length
	for remaining > 0 {
		chunkSize := remaining
		if chunkSize > MAX_CHUNK_SIZE {
			chunkSize = MAX_CHUNK_SIZE
		}

		if hasGenerics {
			buf.WriteUint8(KEY_DECL_TYPE | VALUE_DECL_TYPE)
			buf.WriteUint8(uint8(chunkSize))
		} else {
			buf.WriteUint8(0)
			buf.WriteUint8(uint8(chunkSize))
			buf.WriteUint8(uint8(VARINT64)) // key type
			buf.WriteUint8(uint8(VARINT64)) // value type
		}

		count := 0
		for k, v := range m {
			buf.WriteVarint64(k)
			buf.WriteVarint64(v)
			count++
			if count >= chunkSize {
				break
			}
		}
		remaining -= chunkSize
	}
}

// readMapInt64Int64 reads map[int64]int64 using chunk protocol
func readMapInt64Int64(buf *ByteBuffer, err *Error) map[int64]int64 {
	size := int(buf.ReadVarUint32(err))
if maxMapSize > 0 && size > maxMapSize {  
    err.SetError(DeserializationErrorf(   
        "fory: map size %d exceeds limit %d", size, maxMapSize)) 
    return nil                           
}
	result := make(map[int64]int64, size)
	if size == 0 {
		return result
	}

	for size > 0 {
		chunkHeader := buf.ReadUint8(err)
		keyHasNull := (chunkHeader & KEY_HAS_NULL) != 0
		valueHasNull := (chunkHeader & VALUE_HAS_NULL) != 0

		if keyHasNull || valueHasNull {
			size--
			continue
		}

		chunkSize := int(buf.ReadUint8(err))
		if (chunkHeader & KEY_DECL_TYPE) == 0 {
			buf.ReadUint8(err)
		}
		if (chunkHeader & VALUE_DECL_TYPE) == 0 {
			buf.ReadUint8(err)
		}
		for i := 0; i < chunkSize && size > 0; i++ {
			k := buf.ReadVarint64(err)
			v := buf.ReadVarint64(err)
			result[k] = v
			size--
		}
	}
	return result
}

// writeMapIntInt writes map[int]int using chunk protocol
// When hasGenerics=true, element types are known so we set DECL_TYPE flags and skip type info
func writeMapIntInt(buf *ByteBuffer, m map[int]int, hasGenerics bool) {
	length := len(m)
	buf.WriteVarUint32(uint32(length))
	if length == 0 {
		return
	}

	remaining := length
	for remaining > 0 {
		chunkSize := remaining
		if chunkSize > MAX_CHUNK_SIZE {
			chunkSize = MAX_CHUNK_SIZE
		}

		if hasGenerics {
			buf.WriteUint8(KEY_DECL_TYPE | VALUE_DECL_TYPE)
			buf.WriteUint8(uint8(chunkSize))
		} else {
			buf.WriteUint8(0)
			buf.WriteUint8(uint8(chunkSize))
			buf.WriteUint8(uint8(VARINT64)) // key type (int serialized as varint64)
			buf.WriteUint8(uint8(VARINT64)) // value type
		}

		count := 0
		for k, v := range m {
			buf.WriteVarint64(int64(k))
			buf.WriteVarint64(int64(v))
			count++
			if count >= chunkSize {
				break
			}
		}
		remaining -= chunkSize
	}
}

// readMapIntInt reads map[int]int using chunk protocol
func readMapIntInt(buf *ByteBuffer, err *Error) map[int]int {
	size := int(buf.ReadVarUint32(err))
if maxMapSize > 0 && size > maxMapSize {  
    err.SetError(DeserializationErrorf(   
        "fory: map size %d exceeds limit %d", size, maxMapSize)) 
    return nil                           
}
	result := make(map[int]int, size)
	if size == 0 {
		return result
	}

	for size > 0 {
		chunkHeader := buf.ReadUint8(err)
		keyHasNull := (chunkHeader & KEY_HAS_NULL) != 0
		valueHasNull := (chunkHeader & VALUE_HAS_NULL) != 0

		if keyHasNull || valueHasNull {
			size--
			continue
		}

		chunkSize := int(buf.ReadUint8(err))
		if (chunkHeader & KEY_DECL_TYPE) == 0 {
			buf.ReadUint8(err)
		}
		if (chunkHeader & VALUE_DECL_TYPE) == 0 {
			buf.ReadUint8(err)
		}
		for i := 0; i < chunkSize && size > 0; i++ {
			k := buf.ReadVarint64(err)
			v := buf.ReadVarint64(err)
			result[int(k)] = int(v)
			size--
		}
	}
	return result
}

// ============================================================================
// Dedicated map serializers
// ============================================================================

type stringStringMapSerializer struct{}

func (s stringStringMapSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	writeMapStringString(ctx.buffer, value.Interface().(map[string]string), false)
}

func (s stringStringMapSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	done := writeMapRefAndType(ctx, refMode, writeType, value)
	if done || ctx.HasError() {
		return
	}
	writeMapStringString(ctx.buffer, value.Interface().(map[string]string), hasGenerics)
}

func (s stringStringMapSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	if value.IsNil() {
		value.Set(reflect.MakeMap(value.Type()))
	}
	ctx.RefResolver().Reference(value)
	result := readMapStringString(ctx.buffer, ctx.Err(), ctx.maxStringBytes, ctx.maxMapSize)
	value.Set(reflect.ValueOf(result))
}

func (s stringStringMapSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done := readMapRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s stringStringMapSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

type stringInt64MapSerializer struct{}

func (s stringInt64MapSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	writeMapStringInt64(ctx.buffer, value.Interface().(map[string]int64), false)
}

func (s stringInt64MapSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	done := writeMapRefAndType(ctx, refMode, writeType, value)
	if done || ctx.HasError() {
		return
	}
	writeMapStringInt64(ctx.buffer, value.Interface().(map[string]int64), hasGenerics)
}

func (s stringInt64MapSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	if value.IsNil() {
		value.Set(reflect.MakeMap(value.Type()))
	}
	ctx.RefResolver().Reference(value)
	result := readMapStringInt64(ctx.buffer, ctx.Err(), ctx.maxStringBytes, ctx.maxMapSize)
	value.Set(reflect.ValueOf(result))
}

func (s stringInt64MapSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done := readMapRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s stringInt64MapSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

type stringIntMapSerializer struct{}

func (s stringIntMapSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	writeMapStringInt(ctx.buffer, value.Interface().(map[string]int), false)
}

func (s stringIntMapSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	done := writeMapRefAndType(ctx, refMode, writeType, value)
	if done || ctx.HasError() {
		return
	}
	writeMapStringInt(ctx.buffer, value.Interface().(map[string]int), hasGenerics)
}

func (s stringIntMapSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	if value.IsNil() {
		value.Set(reflect.MakeMap(value.Type()))
	}
	ctx.RefResolver().Reference(value)
	result := readMapStringInt(ctx.buffer, ctx.Err(), ctx.maxStringBytes, ctx.maxMapSize)
	value.Set(reflect.ValueOf(result))
}

func (s stringIntMapSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done := readMapRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s stringIntMapSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

type stringFloat64MapSerializer struct{}

func (s stringFloat64MapSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	writeMapStringFloat64(ctx.buffer, value.Interface().(map[string]float64), false)
}

func (s stringFloat64MapSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	done := writeMapRefAndType(ctx, refMode, writeType, value)
	if done || ctx.HasError() {
		return
	}
	writeMapStringFloat64(ctx.buffer, value.Interface().(map[string]float64), hasGenerics)
}

func (s stringFloat64MapSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	if value.IsNil() {
		value.Set(reflect.MakeMap(value.Type()))
	}
	ctx.RefResolver().Reference(value)
	result := readMapStringFloat64(ctx.buffer, ctx.Err(), ctx.maxStringBytes, ctx.maxMapSize)
	value.Set(reflect.ValueOf(result))
}

func (s stringFloat64MapSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done := readMapRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s stringFloat64MapSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

type stringBoolMapSerializer struct{}

func (s stringBoolMapSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	writeMapStringBool(ctx.buffer, value.Interface().(map[string]bool), false)
}

func (s stringBoolMapSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	done := writeMapRefAndType(ctx, refMode, writeType, value)
	if done || ctx.HasError() {
		return
	}
	writeMapStringBool(ctx.buffer, value.Interface().(map[string]bool), hasGenerics)
}

func (s stringBoolMapSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	if value.IsNil() {
		value.Set(reflect.MakeMap(value.Type()))
	}
	ctx.RefResolver().Reference(value)
	result := readMapStringBool(ctx.buffer, ctx.Err(), ctx.maxStringBytes, ctx.maxMapSize)
	value.Set(reflect.ValueOf(result))
}

func (s stringBoolMapSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done := readMapRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s stringBoolMapSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

type int32Int32MapSerializer struct{}

func (s int32Int32MapSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	writeMapInt32Int32(ctx.buffer, value.Interface().(map[int32]int32), false)
}

func (s int32Int32MapSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	done := writeMapRefAndType(ctx, refMode, writeType, value)
	if done || ctx.HasError() {
		return
	}
	writeMapInt32Int32(ctx.buffer, value.Interface().(map[int32]int32), hasGenerics)
}

func (s int32Int32MapSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	if value.IsNil() {
		value.Set(reflect.MakeMap(value.Type()))
	}
	ctx.RefResolver().Reference(value)
	result := readMapInt32Int32(ctx.buffer, ctx.Err())
	value.Set(reflect.ValueOf(result))
}

func (s int32Int32MapSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done := readMapRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s int32Int32MapSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

type int64Int64MapSerializer struct{}

func (s int64Int64MapSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	writeMapInt64Int64(ctx.buffer, value.Interface().(map[int64]int64), false)
}

func (s int64Int64MapSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	done := writeMapRefAndType(ctx, refMode, writeType, value)
	if done || ctx.HasError() {
		return
	}
	writeMapInt64Int64(ctx.buffer, value.Interface().(map[int64]int64), hasGenerics)
}

func (s int64Int64MapSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	if value.IsNil() {
		value.Set(reflect.MakeMap(value.Type()))
	}
	ctx.RefResolver().Reference(value)
	result := readMapInt64Int64(ctx.buffer, ctx.Err())
	value.Set(reflect.ValueOf(result))
}

func (s int64Int64MapSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done := readMapRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s int64Int64MapSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

type intIntMapSerializer struct{}

func (s intIntMapSerializer) WriteData(ctx *WriteContext, value reflect.Value) {
	writeMapIntInt(ctx.buffer, value.Interface().(map[int]int), false)
}

func (s intIntMapSerializer) Write(ctx *WriteContext, refMode RefMode, writeType bool, hasGenerics bool, value reflect.Value) {
	done := writeMapRefAndType(ctx, refMode, writeType, value)
	if done || ctx.HasError() {
		return
	}
	writeMapIntInt(ctx.buffer, value.Interface().(map[int]int), hasGenerics)
}

func (s intIntMapSerializer) ReadData(ctx *ReadContext, value reflect.Value) {
	if value.IsNil() {
		value.Set(reflect.MakeMap(value.Type()))
	}
	ctx.RefResolver().Reference(value)
	result := readMapIntInt(ctx.buffer, ctx.Err())
	value.Set(reflect.ValueOf(result))
}

func (s intIntMapSerializer) Read(ctx *ReadContext, refMode RefMode, readType bool, hasGenerics bool, value reflect.Value) {
	done := readMapRefAndType(ctx, refMode, readType, value)
	if done || ctx.HasError() {
		return
	}
	s.ReadData(ctx, value)
}

func (s intIntMapSerializer) ReadWithTypeInfo(ctx *ReadContext, refMode RefMode, typeInfo *TypeInfo, value reflect.Value) {
	s.Read(ctx, refMode, false, false, value)
}

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
	"fmt"
	"reflect"
)

const (
	TRACKING_KEY_REF   = 1 << 0 // 0b00000001
	KEY_HAS_NULL       = 1 << 1 // 0b00000010
	KEY_DECL_TYPE      = 1 << 2 // 0b00000100
	TRACKING_VALUE_REF = 1 << 3 // 0b00001000
	VALUE_HAS_NULL     = 1 << 4 // 0b00010000
	VALUE_DECL_TYPE    = 1 << 5 // 0b00100000
	MAX_CHUNK_SIZE     = 255
)

const (
	KV_NULL                               = KEY_HAS_NULL | VALUE_HAS_NULL                       // 0b00010010
	NULL_KEY_VALUE_DECL_TYPE              = KEY_HAS_NULL | VALUE_DECL_TYPE                      // 0b00100010
	NULL_KEY_VALUE_DECL_TYPE_TRACKING_REF = KEY_HAS_NULL | VALUE_DECL_TYPE | TRACKING_VALUE_REF // 0b00101010
	NULL_VALUE_KEY_DECL_TYPE              = VALUE_HAS_NULL | KEY_DECL_TYPE                      // 0b00010100
	NULL_VALUE_KEY_DECL_TYPE_TRACKING_REF = VALUE_HAS_NULL | KEY_DECL_TYPE | TRACKING_KEY_REF   // 0b00010101
)

type mapSerializer struct {
	type_             reflect.Type
	keySerializer     Serializer
	valueSerializer   Serializer
	keyReferencable   bool
	valueReferencable bool
	mapInStruct       bool // Use mapInStruct to distinguish concrete map types during deserialization

}

func (s mapSerializer) TypeId() TypeId {
	return MAP
}

func (s mapSerializer) NeedToWriteRef() bool {
	return true
}

func (s mapSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	buf := ctx.Buffer()
	if value.Kind() == reflect.Interface {
		value = value.Elem()
	}
	length := value.Len()
	buf.WriteVarUint32(uint32(length))
	if length == 0 {
		return nil
	}
	typeResolver := ctx.TypeResolver()
	refResolver := ctx.RefResolver()
	s.keySerializer = nil
	s.valueSerializer = nil
	keySerializer := s.keySerializer
	valueSerializer := s.valueSerializer
	iter := value.MapRange()
	if !iter.Next() {
		return nil
	}
	entryKey, entryVal := iter.Key(), iter.Value()
	if entryKey.Kind() == reflect.Interface {
		entryKey = entryKey.Elem()
	}
	if entryVal.Kind() == reflect.Interface {
		entryVal = entryVal.Elem()
	}
	hasNext := true
	for hasNext {
		for {
			keyValid := isValid(entryKey)
			valValid := isValid(entryVal)
			if keyValid {
				if valValid {
					break
				}
				if keySerializer != nil {
					if keySerializer.NeedToWriteRef() {
						buf.WriteInt8(NULL_VALUE_KEY_DECL_TYPE_TRACKING_REF)
						if written, err := refResolver.WriteRefOrNull(buf, entryKey); err != nil {
							return err
						} else if !written {
							err := s.writeObj(ctx, keySerializer, entryKey)
							if err != nil {
								return err
							}
						}
					} else {
						buf.WriteInt8(NULL_VALUE_KEY_DECL_TYPE)
						err := s.writeObj(ctx, keySerializer, entryKey)
						if err != nil {
							return err
						}
					}
				} else {
					buf.WriteInt8(VALUE_HAS_NULL | TRACKING_KEY_REF)
					err := ctx.WriteValue(entryKey)
					if err != nil {
						return err
					}
				}
			} else {
				if valValid {
					if valueSerializer != nil {
						if valueSerializer.NeedToWriteRef() {
							buf.WriteInt8(NULL_KEY_VALUE_DECL_TYPE_TRACKING_REF)
							if written, err := refResolver.WriteRefOrNull(buf, entryKey); err != nil {
								return err
							} else if !written {
								err := valueSerializer.Write(ctx, entryKey)
								if err != nil {
									return err
								}
							}
							if written, err := refResolver.WriteRefOrNull(buf, value); err != nil {
								return err
							} else if !written {
								err := valueSerializer.Write(ctx, entryVal)
								if err != nil {
									return err
								}
							}
						} else {
							buf.WriteInt8(NULL_KEY_VALUE_DECL_TYPE)
							err := valueSerializer.Write(ctx, entryVal)
							if err != nil {
								return err
							}
						}
					} else {
						buf.WriteInt8(KEY_HAS_NULL | TRACKING_VALUE_REF)
						err := ctx.WriteValue(entryVal)
						if err != nil {
							return err
						}
					}
				} else {
					buf.WriteInt8(KV_NULL)
				}
			}
			if iter.Next() {
				entryKey, entryVal = iter.Key(), iter.Value()
				if entryKey.Kind() == reflect.Interface {
					entryKey = entryKey.Elem()
				}
				if entryVal.Kind() == reflect.Interface {
					entryVal = entryVal.Elem()
				}
			} else {
				hasNext = false
				break
			}
		}
		if !hasNext {
			break
		}
		keyCls := getActualType(entryKey)
		valueCls := getActualType(entryVal)
		buf.WriteInt16(-1)
		chunkSizeOffset := buf.writerIndex
		chunkHeader := 0
		if keySerializer != nil {
			chunkHeader |= KEY_DECL_TYPE
		} else {
			keyTypeInfo, _ := getActualTypeInfo(entryKey, typeResolver)
			if err := typeResolver.writeTypeInfo(buf, keyTypeInfo); err != nil {
				return err
			}
			keySerializer = keyTypeInfo.Serializer
		}
		if valueSerializer != nil {
			chunkHeader |= VALUE_DECL_TYPE
		} else {
			valueTypeInfo, _ := getActualTypeInfo(entryVal, typeResolver)
			if err := typeResolver.writeTypeInfo(buf, valueTypeInfo); err != nil {
				return err
			}
			valueSerializer = valueTypeInfo.Serializer
		}
		keyWriteRef := s.keyReferencable
		if keySerializer != nil {
			keyWriteRef = keySerializer.NeedToWriteRef()
		} else {
			keyWriteRef = false
		}
		valueWriteRef := s.valueReferencable
		if valueSerializer != nil {
			valueWriteRef = valueSerializer.NeedToWriteRef()
		} else {
			valueWriteRef = false
		}

		if keyWriteRef {
			chunkHeader |= TRACKING_KEY_REF
		}
		if valueWriteRef {
			chunkHeader |= TRACKING_VALUE_REF
		}
		buf.PutUint8(chunkSizeOffset-2, uint8(chunkHeader))
		chunkSize := 0
		for chunkSize < MAX_CHUNK_SIZE {
			if !isValid(entryKey) || !isValid(entryVal) || getActualType(entryKey) != keyCls || getActualType(entryVal) != valueCls {
				break
			}
			if !keyWriteRef {
				err := s.writeObj(ctx, keySerializer, entryKey)
				if err != nil {
					return err
				}
			} else if written, err := refResolver.WriteRefOrNull(buf, entryKey); err != nil {
				return err
			} else if !written {
				err := s.writeObj(ctx, keySerializer, entryKey)
				if err != nil {
					return err
				}
			}

			if !valueWriteRef {
				err := s.writeObj(ctx, valueSerializer, entryVal)
				if err != nil {
					return err
				}
			} else if written, err := refResolver.WriteRefOrNull(buf, entryVal); err != nil {
				return err
			} else if !written {
				err := s.writeObj(ctx, valueSerializer, entryVal)
				if err != nil {
					return err
				}
			}

			chunkSize++

			if iter.Next() {
				entryKey, entryVal = iter.Key(), iter.Value()
				if entryKey.Kind() == reflect.Interface {
					entryKey = entryKey.Elem()
				}
				if entryVal.Kind() == reflect.Interface {
					entryVal = entryVal.Elem()
				}
			} else {
				hasNext = false
				break
			}
		}
		keySerializer = s.keySerializer
		valueSerializer = s.valueSerializer
		buf.PutUint8(chunkSizeOffset-1, uint8(chunkSize))
	}
	return nil
}

func (s mapSerializer) writeObj(ctx *WriteContext, serializer Serializer, obj reflect.Value) error {
	return serializer.Write(ctx, obj)
}

func (s mapSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	buf := ctx.Buffer()
	refResolver := ctx.RefResolver()
	if s.type_ == nil {
		s.type_ = type_
	}

	if value.IsNil() {
		isIfaceMap := func(t reflect.Type) bool {
			return t.Kind() == reflect.Map &&
				t.Key().Kind() == reflect.Interface &&
				t.Elem().Kind() == reflect.Interface
		}
		// case 1: A map inside a struct will have a fixed key and value type.
		// case 2: The user has specified the type of the map explicitly.
		// Otherwise, a generic map type will be used
		switch {
		case s.mapInStruct:
			value.Set(reflect.MakeMap(type_))
		case !isIfaceMap(type_):
			value.Set(reflect.MakeMap(type_))
		default:
			iface := reflect.TypeOf((*interface{})(nil)).Elem()
			newMapType := reflect.MapOf(iface, iface)
			value.Set(reflect.MakeMap(newMapType))
		}
	}

	refResolver.Reference(value)
	size := int(buf.ReadVarUint32())
	var chunkHeader uint8
	if size > 0 {
		chunkHeader = buf.ReadUint8()
	}

	keyType := type_.Key()
	valueType := type_.Elem()
	keySer := s.keySerializer
	valSer := s.valueSerializer
	typeResolver := ctx.TypeResolver()

	for size > 0 {
		for {
			keyHasNull := (chunkHeader & KEY_HAS_NULL) != 0
			valueHasNull := (chunkHeader & VALUE_HAS_NULL) != 0
			var k, v reflect.Value
			if !keyHasNull {
				if !valueHasNull {
					break
				} else {
					trackKeyRef := (chunkHeader & TRACKING_KEY_REF) != 0
					if (chunkHeader & KEY_DECL_TYPE) != 0 {
						if trackKeyRef {
							refID, err := refResolver.TryPreserveRefId(buf)
							if err != nil {
								return fmt.Errorf("failed to preserve reference ID: %w", err)
							}
							if refID < int32(NotNullValueFlag) {
								k = refResolver.GetCurrentReadObject()
							} else {
								k = reflect.New(value.Type().Key()).Elem()
								if err := s.readObj(ctx, &k, keySer); err != nil {
									return fmt.Errorf("failed to read map key object: %w", err)
								}
								refResolver.SetReadObject(refID, k)
							}
						} else {
							k = reflect.New(keyType).Elem()
							if err := s.readObj(ctx, &k, keySer); err != nil {
								return fmt.Errorf("failed to read map key object: %w", err)
							}
						}
					} else {
						if err := ctx.readReferencableBySerializer(k, keySer); err != nil {
							return fmt.Errorf("failed to deserialize key via serializer: %w", err)
						}
					}
					value.SetMapIndex(k, reflect.Value{})
				}
			} else {
				if !valueHasNull {
					trackValueRef := (chunkHeader & TRACKING_VALUE_REF) != 0
					if (chunkHeader & VALUE_DECL_TYPE) != 0 {
						if trackValueRef {
							refID, err := refResolver.TryPreserveRefId(buf)
							if err != nil {
								return fmt.Errorf("failed to preserve reference ID: %w", err)
							}
							if refID < int32(NotNullValueFlag) {
								v = refResolver.GetCurrentReadObject()
							} else {
								v = reflect.New(value.Type().Elem()).Elem()
								if err := s.readObj(ctx, &v, valSer); err != nil {
									return fmt.Errorf("failed to read map value object: %w", err)
								}
								refResolver.SetReadObject(refID, k)
							}
						}
					} else {
						if err := ctx.readReferencableBySerializer(v, valSer); err != nil {
							return fmt.Errorf("failed to deserialize map value via serializer: %w", err)
						}

					}
					value.SetMapIndex(reflect.Value{}, v)
				} else {
					value.SetMapIndex(reflect.Value{}, reflect.Value{})
				}
			}

			size--
			if size == 0 {
				return nil
			} else {
				chunkHeader = buf.ReadUint8()
			}
		}

		trackKeyRef := (chunkHeader & TRACKING_KEY_REF) != 0
		trackValRef := (chunkHeader & TRACKING_VALUE_REF) != 0
		keyDeclType := (chunkHeader & KEY_DECL_TYPE) != 0
		valDeclType := (chunkHeader & VALUE_DECL_TYPE) != 0
		chunkSize := int(buf.ReadUint8())
		if !keyDeclType {
			ti, err := typeResolver.readTypeInfo(buf, value)
			if err != nil {
				return err
			}
			keySer = ti.Serializer
			keyType = ti.Type
		}
		if !valDeclType {
			ti, err := typeResolver.readTypeInfo(buf, value)
			if err != nil {
				return err
			}
			valSer = ti.Serializer
			valueType = ti.Type
		}

		for i := 0; i < chunkSize; i++ {
			var k, v reflect.Value
			if trackKeyRef {
				refID, err := refResolver.TryPreserveRefId(buf)
				if err != nil {
					return err
				}
				if refID < int32(NotNullValueFlag) {
					k = refResolver.GetCurrentReadObject()
				} else {
					k = reflect.New(value.Type().Key()).Elem()
					if err := s.readObj(ctx, &k, keySer); err != nil {
						return err
					}
					refResolver.SetReadObject(refID, k)
				}
			} else {
				k = reflect.New(keyType).Elem()
				if err := s.readObj(ctx, &k, keySer); err != nil {
					return err
				}
			}

			if trackValRef {
				refID, err := refResolver.TryPreserveRefId(buf)
				if err != nil {
					return err
				}
				if refID < int32(NotNullValueFlag) {
					v = refResolver.GetCurrentReadObject()
				} else {
					v = reflect.New(value.Type().Elem()).Elem()
					if err := s.readObj(ctx, &v, valSer); err != nil {
						return err
					}
					refResolver.SetReadObject(refID, v)
				}
			} else {
				v = reflect.New(valueType).Elem()
				if err := s.readObj(ctx, &v, valSer); err != nil {
					return err
				}
			}
			if k.Kind() == reflect.Interface {
				k = k.Elem()
			}
			if v.Kind() == reflect.Interface {
				v = v.Elem()
			}
			value.SetMapIndex(k, v)
			size--
		}

		keySer = s.keySerializer
		valSer = s.valueSerializer
		if size > 0 {
			chunkHeader = buf.ReadUint8()
		}
	}
	return nil
}

func (s mapSerializer) readObj(
	ctx *ReadContext,
	v *reflect.Value,
	serializer Serializer,
) error {
	return serializer.Read(ctx, v.Type(), *v)
}

func getActualType(v reflect.Value) reflect.Type {
	if v.Kind() == reflect.Interface && !v.IsNil() {
		return v.Elem().Type()
	}
	return v.Type()
}

func getActualTypeInfo(v reflect.Value, resolver *TypeResolver) (TypeInfo, error) {
	if v.Kind() == reflect.Interface && !v.IsNil() {
		elem := v.Elem()
		if !elem.IsValid() {
			return TypeInfo{}, fmt.Errorf("invalid interface value")
		}
		return resolver.getTypeInfo(elem, true)
	}
	return resolver.getTypeInfo(v, true)
}

func UnwrapReflectValue(v reflect.Value) reflect.Value {
	for v.Kind() == reflect.Interface && !v.IsNil() {
		v = v.Elem()
	}
	return v
}

func isValid(v reflect.Value) bool {
	// Zero values are valid, so apply this change temporarily.
	return v.IsValid()
}

// ============================================================================
// Optimized map serializers for common types
// ============================================================================

// writeMapStringString writes map[string]string using chunk protocol
func writeMapStringString(buf *ByteBuffer, m map[string]string) {
	length := len(m)
	buf.WriteVarUint32(uint32(length))
	if length == 0 {
		return
	}

	// Write chunks of entries
	remaining := length
	for remaining > 0 {
		chunkSize := remaining
		if chunkSize > MAX_CHUNK_SIZE {
			chunkSize = MAX_CHUNK_SIZE
		}

		// Chunk header: -1 followed by header byte and size
		buf.WriteInt16(-1)
		// Header: KEY_DECL_TYPE | VALUE_DECL_TYPE (no refs for primitives)
		buf.WriteUint8(KEY_DECL_TYPE | VALUE_DECL_TYPE)
		buf.WriteUint8(uint8(chunkSize))

		// Write chunk entries
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
func readMapStringString(buf *ByteBuffer) map[string]string {
	size := int(buf.ReadVarUint32())
	result := make(map[string]string, size)
	if size == 0 {
		return result
	}

	for size > 0 {
		chunkHeader := buf.ReadUint8()
		
		// Handle null key/value cases
		keyHasNull := (chunkHeader & KEY_HAS_NULL) != 0
		valueHasNull := (chunkHeader & VALUE_HAS_NULL) != 0
		
		if keyHasNull || valueHasNull {
			// Skip null entries (strings can't be null in Go)
			size--
			continue
		}

		// Read chunk size
		chunkSize := int(buf.ReadUint8())
		
		// Read chunk entries
		for i := 0; i < chunkSize && size > 0; i++ {
			k := readString(buf)
			v := readString(buf)
			result[k] = v
			size--
		}
	}
	return result
}

// writeMapStringInt64 writes map[string]int64 using chunk protocol
func writeMapStringInt64(buf *ByteBuffer, m map[string]int64) {
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

		buf.WriteInt16(-1)
		buf.WriteUint8(KEY_DECL_TYPE | VALUE_DECL_TYPE)
		buf.WriteUint8(uint8(chunkSize))

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
func readMapStringInt64(buf *ByteBuffer) map[string]int64 {
	size := int(buf.ReadVarUint32())
	result := make(map[string]int64, size)
	if size == 0 {
		return result
	}

	for size > 0 {
		chunkHeader := buf.ReadUint8()
		keyHasNull := (chunkHeader & KEY_HAS_NULL) != 0
		valueHasNull := (chunkHeader & VALUE_HAS_NULL) != 0
		
		if keyHasNull || valueHasNull {
			size--
			continue
		}

		chunkSize := int(buf.ReadUint8())
		for i := 0; i < chunkSize && size > 0; i++ {
			k := readString(buf)
			v := buf.ReadVarint64()
			result[k] = v
			size--
		}
	}
	return result
}

// writeMapStringInt writes map[string]int using chunk protocol
func writeMapStringInt(buf *ByteBuffer, m map[string]int) {
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

		buf.WriteInt16(-1)
		buf.WriteUint8(KEY_DECL_TYPE | VALUE_DECL_TYPE)
		buf.WriteUint8(uint8(chunkSize))

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
func readMapStringInt(buf *ByteBuffer) map[string]int {
	size := int(buf.ReadVarUint32())
	result := make(map[string]int, size)
	if size == 0 {
		return result
	}

	for size > 0 {
		chunkHeader := buf.ReadUint8()
		keyHasNull := (chunkHeader & KEY_HAS_NULL) != 0
		valueHasNull := (chunkHeader & VALUE_HAS_NULL) != 0
		
		if keyHasNull || valueHasNull {
			size--
			continue
		}

		chunkSize := int(buf.ReadUint8())
		for i := 0; i < chunkSize && size > 0; i++ {
			k := readString(buf)
			v := buf.ReadVarint64()
			result[k] = int(v)
			size--
		}
	}
	return result
}

// writeMapStringFloat64 writes map[string]float64 using chunk protocol
func writeMapStringFloat64(buf *ByteBuffer, m map[string]float64) {
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

		buf.WriteInt16(-1)
		buf.WriteUint8(KEY_DECL_TYPE | VALUE_DECL_TYPE)
		buf.WriteUint8(uint8(chunkSize))

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
func readMapStringFloat64(buf *ByteBuffer) map[string]float64 {
	size := int(buf.ReadVarUint32())
	result := make(map[string]float64, size)
	if size == 0 {
		return result
	}

	for size > 0 {
		chunkHeader := buf.ReadUint8()
		keyHasNull := (chunkHeader & KEY_HAS_NULL) != 0
		valueHasNull := (chunkHeader & VALUE_HAS_NULL) != 0
		
		if keyHasNull || valueHasNull {
			size--
			continue
		}

		chunkSize := int(buf.ReadUint8())
		for i := 0; i < chunkSize && size > 0; i++ {
			k := readString(buf)
			v := buf.ReadFloat64()
			result[k] = v
			size--
		}
	}
	return result
}

// writeMapStringBool writes map[string]bool using chunk protocol
func writeMapStringBool(buf *ByteBuffer, m map[string]bool) {
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

		buf.WriteInt16(-1)
		buf.WriteUint8(KEY_DECL_TYPE | VALUE_DECL_TYPE)
		buf.WriteUint8(uint8(chunkSize))

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
func readMapStringBool(buf *ByteBuffer) map[string]bool {
	size := int(buf.ReadVarUint32())
	result := make(map[string]bool, size)
	if size == 0 {
		return result
	}

	for size > 0 {
		chunkHeader := buf.ReadUint8()
		keyHasNull := (chunkHeader & KEY_HAS_NULL) != 0
		valueHasNull := (chunkHeader & VALUE_HAS_NULL) != 0
		
		if keyHasNull || valueHasNull {
			size--
			continue
		}

		chunkSize := int(buf.ReadUint8())
		for i := 0; i < chunkSize && size > 0; i++ {
			k := readString(buf)
			v := buf.ReadBool()
			result[k] = v
			size--
		}
	}
	return result
}

// writeMapInt32Int32 writes map[int32]int32 using chunk protocol
func writeMapInt32Int32(buf *ByteBuffer, m map[int32]int32) {
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

		buf.WriteInt16(-1)
		buf.WriteUint8(KEY_DECL_TYPE | VALUE_DECL_TYPE)
		buf.WriteUint8(uint8(chunkSize))

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
func readMapInt32Int32(buf *ByteBuffer) map[int32]int32 {
	size := int(buf.ReadVarUint32())
	result := make(map[int32]int32, size)
	if size == 0 {
		return result
	}

	for size > 0 {
		chunkHeader := buf.ReadUint8()
		keyHasNull := (chunkHeader & KEY_HAS_NULL) != 0
		valueHasNull := (chunkHeader & VALUE_HAS_NULL) != 0
		
		if keyHasNull || valueHasNull {
			size--
			continue
		}

		chunkSize := int(buf.ReadUint8())
		for i := 0; i < chunkSize && size > 0; i++ {
			k := buf.ReadVarint32()
			v := buf.ReadVarint32()
			result[k] = v
			size--
		}
	}
	return result
}

// writeMapInt64Int64 writes map[int64]int64 using chunk protocol
func writeMapInt64Int64(buf *ByteBuffer, m map[int64]int64) {
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

		buf.WriteInt16(-1)
		buf.WriteUint8(KEY_DECL_TYPE | VALUE_DECL_TYPE)
		buf.WriteUint8(uint8(chunkSize))

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
func readMapInt64Int64(buf *ByteBuffer) map[int64]int64 {
	size := int(buf.ReadVarUint32())
	result := make(map[int64]int64, size)
	if size == 0 {
		return result
	}

	for size > 0 {
		chunkHeader := buf.ReadUint8()
		keyHasNull := (chunkHeader & KEY_HAS_NULL) != 0
		valueHasNull := (chunkHeader & VALUE_HAS_NULL) != 0
		
		if keyHasNull || valueHasNull {
			size--
			continue
		}

		chunkSize := int(buf.ReadUint8())
		for i := 0; i < chunkSize && size > 0; i++ {
			k := buf.ReadVarint64()
			v := buf.ReadVarint64()
			result[k] = v
			size--
		}
	}
	return result
}

// writeMapIntInt writes map[int]int using chunk protocol
func writeMapIntInt(buf *ByteBuffer, m map[int]int) {
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

		buf.WriteInt16(-1)
		buf.WriteUint8(KEY_DECL_TYPE | VALUE_DECL_TYPE)
		buf.WriteUint8(uint8(chunkSize))

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
func readMapIntInt(buf *ByteBuffer) map[int]int {
	size := int(buf.ReadVarUint32())
	result := make(map[int]int, size)
	if size == 0 {
		return result
	}

	for size > 0 {
		chunkHeader := buf.ReadUint8()
		keyHasNull := (chunkHeader & KEY_HAS_NULL) != 0
		valueHasNull := (chunkHeader & VALUE_HAS_NULL) != 0
		
		if keyHasNull || valueHasNull {
			size--
			continue
		}

		chunkSize := int(buf.ReadUint8())
		for i := 0; i < chunkSize && size > 0; i++ {
			k := buf.ReadVarint64()
			v := buf.ReadVarint64()
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

func (s stringStringMapSerializer) TypeId() TypeId       { return MAP }
func (s stringStringMapSerializer) NeedToWriteRef() bool { return true }

func (s stringStringMapSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	writeMapStringString(ctx.buffer, value.Interface().(map[string]string))
	return nil
}

func (s stringStringMapSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	result := readMapStringString(ctx.buffer)
	value.Set(reflect.ValueOf(result))
	return nil
}

type stringInt64MapSerializer struct{}

func (s stringInt64MapSerializer) TypeId() TypeId       { return MAP }
func (s stringInt64MapSerializer) NeedToWriteRef() bool { return true }

func (s stringInt64MapSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	writeMapStringInt64(ctx.buffer, value.Interface().(map[string]int64))
	return nil
}

func (s stringInt64MapSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	result := readMapStringInt64(ctx.buffer)
	value.Set(reflect.ValueOf(result))
	return nil
}

type stringIntMapSerializer struct{}

func (s stringIntMapSerializer) TypeId() TypeId       { return MAP }
func (s stringIntMapSerializer) NeedToWriteRef() bool { return true }

func (s stringIntMapSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	writeMapStringInt(ctx.buffer, value.Interface().(map[string]int))
	return nil
}

func (s stringIntMapSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	result := readMapStringInt(ctx.buffer)
	value.Set(reflect.ValueOf(result))
	return nil
}

type stringFloat64MapSerializer struct{}

func (s stringFloat64MapSerializer) TypeId() TypeId       { return MAP }
func (s stringFloat64MapSerializer) NeedToWriteRef() bool { return true }

func (s stringFloat64MapSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	writeMapStringFloat64(ctx.buffer, value.Interface().(map[string]float64))
	return nil
}

func (s stringFloat64MapSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	result := readMapStringFloat64(ctx.buffer)
	value.Set(reflect.ValueOf(result))
	return nil
}

type stringBoolMapSerializer struct{}

func (s stringBoolMapSerializer) TypeId() TypeId       { return MAP }
func (s stringBoolMapSerializer) NeedToWriteRef() bool { return true }

func (s stringBoolMapSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	writeMapStringBool(ctx.buffer, value.Interface().(map[string]bool))
	return nil
}

func (s stringBoolMapSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	result := readMapStringBool(ctx.buffer)
	value.Set(reflect.ValueOf(result))
	return nil
}

type int32Int32MapSerializer struct{}

func (s int32Int32MapSerializer) TypeId() TypeId       { return MAP }
func (s int32Int32MapSerializer) NeedToWriteRef() bool { return true }

func (s int32Int32MapSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	writeMapInt32Int32(ctx.buffer, value.Interface().(map[int32]int32))
	return nil
}

func (s int32Int32MapSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	result := readMapInt32Int32(ctx.buffer)
	value.Set(reflect.ValueOf(result))
	return nil
}

type int64Int64MapSerializer struct{}

func (s int64Int64MapSerializer) TypeId() TypeId       { return MAP }
func (s int64Int64MapSerializer) NeedToWriteRef() bool { return true }

func (s int64Int64MapSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	writeMapInt64Int64(ctx.buffer, value.Interface().(map[int64]int64))
	return nil
}

func (s int64Int64MapSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	result := readMapInt64Int64(ctx.buffer)
	value.Set(reflect.ValueOf(result))
	return nil
}

type intIntMapSerializer struct{}

func (s intIntMapSerializer) TypeId() TypeId       { return MAP }
func (s intIntMapSerializer) NeedToWriteRef() bool { return true }

func (s intIntMapSerializer) Write(ctx *WriteContext, value reflect.Value) error {
	writeMapIntInt(ctx.buffer, value.Interface().(map[int]int))
	return nil
}

func (s intIntMapSerializer) Read(ctx *ReadContext, type_ reflect.Type, value reflect.Value) error {
	result := readMapIntInt(ctx.buffer)
	value.Set(reflect.ValueOf(result))
	return nil
}

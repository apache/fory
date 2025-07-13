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

func (s mapSerializer) NeedWriteRef() bool {
	return true
}

func (s mapSerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	if value.Kind() == reflect.Interface {
		value = value.Elem()
	}
	length := value.Len()
	buf.WriteVarUint32(uint32(length))
	if length == 0 {
		return nil
	}
	typeResolver := f.typeResolver
	refResolver := f.refResolver
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
					if keySerializer.NeedWriteRef() {
						buf.WriteInt8(NULL_VALUE_KEY_DECL_TYPE_TRACKING_REF)
						if written, err := refResolver.WriteRefOrNull(buf, entryKey); err != nil {
							return err
						} else if !written {
							err := s.writeObj(f, keySerializer, buf, entryKey)
							if err != nil {
								return err
							}
						}
					} else {
						buf.WriteInt8(NULL_VALUE_KEY_DECL_TYPE)
						err := s.writeObj(f, keySerializer, buf, entryKey)
						if err != nil {
							return err
						}
					}
				} else {
					buf.WriteInt8(VALUE_HAS_NULL | TRACKING_KEY_REF)
					err := f.Write(buf, entryKey)
					if err != nil {
						return err
					}
				}
			} else {
				if valValid {
					if valueSerializer != nil {
						if valueSerializer.NeedWriteRef() {
							buf.WriteInt8(NULL_KEY_VALUE_DECL_TYPE_TRACKING_REF)
							if written, err := refResolver.WriteRefOrNull(buf, entryKey); err != nil {
								return err
							} else if !written {
								err := valueSerializer.Write(f, buf, entryKey)
								if err != nil {
									return err
								}
							}
							if written, err := refResolver.WriteRefOrNull(buf, value); err != nil {
								return err
							} else if !written {
								err := valueSerializer.Write(f, buf, entryVal)
								if err != nil {
									return err
								}
							}
						} else {
							buf.WriteInt8(NULL_KEY_VALUE_DECL_TYPE)
							err := valueSerializer.Write(f, buf, entryVal)
							if err != nil {
								return err
							}
						}
					} else {
						buf.WriteInt8(KEY_HAS_NULL | TRACKING_VALUE_REF)
						err := f.Write(buf, entryVal)
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
			keyWriteRef = keySerializer.NeedWriteRef()
		} else {
			keyWriteRef = false
		}
		valueWriteRef := s.valueReferencable
		if valueSerializer != nil {
			valueWriteRef = valueSerializer.NeedWriteRef()
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
				err := s.writeObj(f, keySerializer, buf, entryKey)
				if err != nil {
					return err
				}
			} else if written, err := refResolver.WriteRefOrNull(buf, entryKey); err != nil {
				return err
			} else if !written {
				err := s.writeObj(f, keySerializer, buf, entryKey)
				if err != nil {
					return err
				}
			}

			if !valueWriteRef {
				err := s.writeObj(f, valueSerializer, buf, entryVal)
				if err != nil {
					return err
				}
			} else if written, err := refResolver.WriteRefOrNull(buf, entryVal); err != nil {
				return err
			} else if !written {
				err := s.writeObj(f, valueSerializer, buf, entryVal)
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

func (s mapSerializer) writeObj(f *Fory, serializer Serializer, buf *ByteBuffer, obj reflect.Value) error {
	return serializer.Write(f, buf, obj)
}

func (s mapSerializer) Read(f *Fory, buf *ByteBuffer, typ reflect.Type, value reflect.Value) error {
	if s.type_ == nil {
		s.type_ = typ
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
			value.Set(reflect.MakeMap(typ))
		case !isIfaceMap(typ):
			value.Set(reflect.MakeMap(typ))
		default:
			iface := reflect.TypeOf((*interface{})(nil)).Elem()
			newMapType := reflect.MapOf(iface, iface)
			value.Set(reflect.MakeMap(newMapType))
		}
	}

	f.refResolver.Reference(value)
	size := int(buf.ReadUint8())
	var chunkHeader uint8
	if size > 0 {
		chunkHeader = buf.ReadUint8()
	}

	keyType := typ.Key()
	valueType := typ.Elem()
	keySer := s.keySerializer
	valSer := s.valueSerializer
	resolver := f.typeResolver

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
							refID, err := f.refResolver.TryPreserveRefId(buf)
							if err != nil {
								return fmt.Errorf("failed to preserve reference ID: %w", err)
							}
							if refID < int32(NotNullValueFlag) {
								k = f.refResolver.GetCurrentReadObject()
							} else {
								k = reflect.New(value.Type().Key()).Elem()
								if err := s.readObj(f, buf, &k, keySer); err != nil {
									return fmt.Errorf("failed to read map key object: %w", err)
								}
								f.refResolver.SetReadObject(refID, k)
							}
						} else {
							k = reflect.New(keyType).Elem()
							if err := s.readObj(f, buf, &k, keySer); err != nil {
								return fmt.Errorf("failed to read map key object: %w", err)
							}
						}
					} else {
						if err := f.readReferencableBySerializer(buf, k, keySer); err != nil {
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
							refID, err := f.refResolver.TryPreserveRefId(buf)
							if err != nil {
								return fmt.Errorf("failed to preserve reference ID: %w", err)
							}
							if refID < int32(NotNullValueFlag) {
								v = f.refResolver.GetCurrentReadObject()
							} else {
								v = reflect.New(value.Type().Elem()).Elem()
								if err := s.readObj(f, buf, &v, valSer); err != nil {
									return fmt.Errorf("failed to read map value object: %w", err)
								}
								f.refResolver.SetReadObject(refID, k)
							}
						}
					} else {
						if err := f.readReferencableBySerializer(buf, v, valSer); err != nil {
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
			ti, err := resolver.readTypeInfo(buf)
			if err != nil {
				return err
			}
			keySer = ti.Serializer
			keyType = ti.Type
		}
		if !valDeclType {
			ti, err := resolver.readTypeInfo(buf)
			if err != nil {
				return err
			}
			valSer = ti.Serializer
			valueType = ti.Type
		}

		for i := 0; i < chunkSize; i++ {
			var k, v reflect.Value
			if trackKeyRef {
				refID, err := f.refResolver.TryPreserveRefId(buf)
				if err != nil {
					return err
				}
				if refID < int32(NotNullValueFlag) {
					k = f.refResolver.GetCurrentReadObject()
				} else {
					k = reflect.New(value.Type().Key()).Elem()
					if err := s.readObj(f, buf, &k, keySer); err != nil {
						return err
					}
					f.refResolver.SetReadObject(refID, k)
				}
			} else {
				k = reflect.New(keyType).Elem()
				if err := s.readObj(f, buf, &k, keySer); err != nil {
					return err
				}
			}

			if trackValRef {
				refID, err := f.refResolver.TryPreserveRefId(buf)
				if err != nil {
					return err
				}
				if refID < int32(NotNullValueFlag) {
					v = f.refResolver.GetCurrentReadObject()
				} else {
					v = reflect.New(value.Type().Elem()).Elem()
					if err := s.readObj(f, buf, &v, valSer); err != nil {
						return err
					}
					f.refResolver.SetReadObject(refID, v)
				}
			} else {
				v = reflect.New(valueType).Elem()
				if err := s.readObj(f, buf, &v, valSer); err != nil {
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
	f *Fory,
	buf *ByteBuffer,
	v *reflect.Value,
	serializer Serializer,
) error {
	return serializer.Read(f, buf, v.Type(), *v)
}

func getActualType(v reflect.Value) reflect.Type {
	if v.Kind() == reflect.Interface && !v.IsNil() {
		return v.Elem().Type()
	}
	return v.Type()
}

func getActualTypeInfo(v reflect.Value, resolver *typeResolver) (TypeInfo, error) {
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

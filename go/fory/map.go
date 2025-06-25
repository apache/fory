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

	// Get resolvers from Fory instance
	typeResolver := f.typeResolver
	refResolver := f.refResolver
	keySerializer := s.keySerializer
	valueSerializer := s.valueSerializer

	// Initialize map iterator and get first key-value pair
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
		// Process null key/value pairs
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

			chunkSize += 1

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
		value.Set(reflect.MakeMap(typ))
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
			if !keyHasNull && !valueHasNull {
				break
			}

			var k, v reflect.Value

			if !keyHasNull {
				if (chunkHeader&KEY_DECL_TYPE) != 0 && (chunkHeader&TRACKING_KEY_REF) != 0 {
					refID, err := f.refResolver.TryPreserveRefId(buf)
					if err != nil {
						return err
					}
					switch {
					case refID == int32(NullFlag):
						k = reflect.Zero(keyType)
					case refID < int32(NotNullValueFlag):
						k = f.refResolver.GetCurrentReadObject()
					default:
						k = reflect.New(keyType).Elem()
						if err := s._readObj(f, buf, &k, keySer); err != nil {
							return err
						}
						f.refResolver.SetReadObject(refID, k)
					}
				} else if (chunkHeader & KEY_DECL_TYPE) != 0 {
					k = reflect.New(keyType).Elem()
					if err := s._readObj(f, buf, &k, keySer); err != nil {
						return err
					}
				} else {
					k = reflect.New(keyType).Elem()
					if err := f.ReadReferencable(buf, k); err != nil {
						return err
					}
				}
			} else {
				k = reflect.Zero(keyType)
			}

			if !valueHasNull {
				if (chunkHeader&VALUE_DECL_TYPE) != 0 && (chunkHeader&TRACKING_VALUE_REF) != 0 {
					refID, err := f.refResolver.TryPreserveRefId(buf)
					if err != nil {
						return err
					}
					switch {
					case refID == int32(NullFlag):
						v = reflect.Zero(valueType)
					case refID < int32(NotNullValueFlag):
						v = f.refResolver.GetCurrentReadObject()
					default:
						v = reflect.New(valueType).Elem()
						if err := s._readObj(f, buf, &v, valSer); err != nil {
							return err
						}
						f.refResolver.SetReadObject(refID, v)
					}
				} else if (chunkHeader & VALUE_DECL_TYPE) != 0 {
					v = reflect.New(valueType).Elem()
					if err := s._readObj(f, buf, &v, valSer); err != nil {
						return err
					}
				} else {
					v = reflect.New(valueType).Elem()
					if err := f.ReadReferencable(buf, v); err != nil {
						return err
					}
				}
			} else {
				v = reflect.Zero(valueType)
			}
			value.SetMapIndex(k, v)
			size--
			if size == 0 {
				return nil
			}

			chunkHeader = buf.ReadUint8()
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
					k = reflect.New(keyType).Elem()
					if err := s._readObj(f, buf, &k, keySer); err != nil {
						return err
					}
					f.refResolver.SetReadObject(refID, k)
				}
			} else {
				k = reflect.New(keyType).Elem()
				if err := s._readObj(f, buf, &k, keySer); err != nil {
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
					v = reflect.New(valueType).Elem()
					if err := s._readObj(f, buf, &v, valSer); err != nil {
						return err
					}
					f.refResolver.SetReadObject(refID, v)
				}
			} else {
				v = reflect.New(valueType).Elem()
				if err := s._readObj(f, buf, &v, valSer); err != nil {
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

func (s mapSerializer) _readObj(
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

func actualVal(t reflect.Type) (reflect.Value, error) {
	if t.Kind() == reflect.Interface {
		var container interface{}
		return reflect.ValueOf(&container).Elem(), nil
	}
	return reflect.New(t).Elem(), nil
}

func isValid(v reflect.Value) bool {
	return v.IsValid()
}

type mapConcreteKeyValueSerializer struct {
	type_             reflect.Type
	keySerializer     Serializer
	valueSerializer   Serializer
	keyReferencable   bool
	valueReferencable bool
}

func (s *mapConcreteKeyValueSerializer) TypeId() TypeId {
	return -MAP
}

func (s *mapConcreteKeyValueSerializer) Write(f *Fory, buf *ByteBuffer, value reflect.Value) error {
	length := value.Len()
	if err := f.writeLength(buf, length); err != nil {
		return err
	}
	iter := value.MapRange()
	for iter.Next() {
		if s.keySerializer != nil {
			err := writeBySerializer(f, buf, iter.Key(), s.keySerializer, s.keyReferencable)
			if err != nil {
				return err
			}
		} else {
			if err := f.WriteReferencable(buf, iter.Key()); err != nil {
				return err
			}
		}
		if s.valueSerializer != nil {
			err := writeBySerializer(f, buf, iter.Value(), s.valueSerializer, s.valueReferencable)
			if err != nil {
				return err
			}
		} else {
			if err := f.WriteReferencable(buf, iter.Value()); err != nil {
				return err
			}
		}
	}
	return nil
}
func (s *mapConcreteKeyValueSerializer) Read(f *Fory, buf *ByteBuffer, type_ reflect.Type, value reflect.Value) error {
	if value.IsNil() {
		value.Set(reflect.MakeMap(s.type_))
	}
	f.refResolver.Reference(value)
	keyType := s.type_.Key()
	valueType := s.type_.Elem()
	length := f.readLength(buf)
	for i := 0; i < length; i++ {
		mapKey := reflect.New(keyType).Elem()
		if s.keySerializer != nil {
			if err := readBySerializer(f, buf, mapKey, s.keySerializer, s.keyReferencable); err != nil {
				return err
			}
		} else {
			if err := f.ReadReferencable(buf, mapKey); err != nil {
				return err
			}
		}
		mapValue := reflect.New(valueType).Elem()
		if s.valueSerializer != nil {
			if err := readBySerializer(f, buf, mapValue, s.valueSerializer, s.valueReferencable); err != nil {
				return err
			}
		} else {
			if err := f.ReadReferencable(buf, mapValue); err != nil {
				return err
			}
		}
		value.SetMapIndex(mapKey, mapValue)
	}
	return nil
}

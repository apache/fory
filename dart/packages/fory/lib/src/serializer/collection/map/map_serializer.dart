/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import 'package:fory/src/deserializer_pack.dart';
import 'package:fory/src/dev_annotation/optimize.dart';
import 'package:fory/src/meta/spec_wraps/type_spec_wrap.dart';
import 'package:fory/src/collection/stack.dart';
import 'package:fory/src/const/types.dart';
import 'package:fory/src/memory/byte_reader.dart';
import 'package:fory/src/memory/byte_writer.dart';
import 'package:fory/src/serializer_pack.dart';
import 'package:fory/src/serializer/serializer.dart';

abstract base class MapSerializer<T extends Map<Object?,Object?>> extends Serializer<Map<Object?,Object?>> {

  const MapSerializer(bool writeRef): super(ObjType.MAP, writeRef);

  T newMap(int size);

  @override
  T read(ByteReader br, int refId, DeserializerPack pack) {
    int len = br.readVarUint32Small7();
    T map = newMap(len);
    if (writeRef){
      pack.refResolver.setRefTheLatestId(map);
    }
    TypeSpecWrap? typeWrap = pack.typeWrapStack.peek;
    if (typeWrap == null){
      // Traverse entries
      for (int i = 0; i < len; ++i) {
        Object? key = pack.foryDeser.xReadRefNoSer(br, pack);
        Object? value = pack.foryDeser.xReadRefNoSer(br, pack);
        map[key] = value;
      }
      return map;
    }
    Stack<TypeSpecWrap> typeWrapStack = pack.typeWrapStack;
    TypeSpecWrap keyWrap = typeWrap.param0!;
    TypeSpecWrap valueWrap = typeWrap.param1!;
    Serializer? keySer = keyWrap.ser;
    Serializer? valueSer = valueWrap.ser;

    if (!keyWrap.hasGenericsParam && !valueWrap.hasGenericsParam){
      // Traverse entries
      for (int i = 0; i < len; ++i) {
        Object? key = _readWithNullableSer(br, keySer, pack);
        Object? value = _readWithNullableSer(br, valueSer, pack);
        map[key] = value;
      }
      return map;
    }
    if (!keyWrap.hasGenericsParam && valueWrap.hasGenericsParam){
      // Traverse entries
      for (int i = 0; i < len; ++i) {
        Object? key = _readWithNullableSer(br, keySer, pack);
        typeWrapStack.push(valueWrap);
        Object? value = _readWithNullableSer(br, valueSer, pack);
        typeWrapStack.pop();
        map[key] = value;
      }
      return map;
    }

    if (keyWrap.hasGenericsParam && !valueWrap.hasGenericsParam){
      // Traverse entries
      for (int i = 0; i < len; ++i) {
        typeWrapStack.push(keyWrap);
        Object? key = _readWithNullableSer(br, keySer, pack);
        typeWrapStack.pop();
        Object? value = _readWithNullableSer(br, valueSer, pack);
        map[key] = value;
      }
      return map;
    }

    for (int i = 0; i < len; ++i) {
      typeWrapStack.push(keyWrap);
      Object? key = _readWithNullableSer(br, keySer, pack);
      typeWrapStack.changeTop(valueWrap);
      Object? value = _readWithNullableSer(br, valueSer, pack);
      typeWrapStack.pop();
      map[key] = value;
    }
    return map;
  }

  /// This code may look a bit lengthy, but to avoid unnecessary checks multiple times in the loop
  /// (since it only needs to be done once), the outer judgment cases are separated, making the code look a bit long
  @override
  void write(ByteWriter bw, covariant T v, SerializerPack pack) {
    bw.writeVarUint32Small7(v.length);
    TypeSpecWrap? typeWrap = pack.typeWrapStack.peek;
    if (typeWrap == null){
      // Traverse entries
      for (var entry in v.entries) {
        Object? key = entry.key;
        Object? value = entry.value;
        pack.forySer.xWriteRefNoSer(bw, key, pack);
        pack.forySer.xWriteRefNoSer(bw, value, pack);
      }
      return;
    }
    var typeWrapStack = pack.typeWrapStack;
    TypeSpecWrap keyWrap = typeWrap.param0!;
    TypeSpecWrap valueWrap = typeWrap.param1!;
    Serializer? keySer = keyWrap.ser;
    Serializer? valueSer = valueWrap.ser;

    if (!keyWrap.hasGenericsParam && !valueWrap.hasGenericsParam){
      // Traverse entries
      for (var entry in v.entries) {
        Object? key = entry.key;
        Object? value = entry.value;
        _writeWithNullableSer(bw, key, keySer, pack);
        _writeWithNullableSer(bw, value, valueSer, pack);
      }
      return;
    }

    if (keyWrap.hasGenericsParam && !valueWrap.hasGenericsParam){
      // Traverse entries
      for (var entry in v.entries) {
        Object? key = entry.key;
        Object? value = entry.value;
        typeWrapStack.push(keyWrap);
        _writeWithNullableSer(bw, key, keySer, pack);
        typeWrapStack.pop();
        _writeWithNullableSer(bw, value, valueSer, pack);
      }
      return;
    }

    if (!keyWrap.hasGenericsParam && valueWrap.hasGenericsParam){
      // Traverse entries
      for (var entry in v.entries) {
        Object? key = entry.key;
        Object? value = entry.value;
        _writeWithNullableSer(bw, key, keySer, pack);
        typeWrapStack.push(valueWrap);
        _writeWithNullableSer(bw, value, valueSer, pack);
        typeWrapStack.pop();
      }
      return;
    }
    // Traverse entries
    for (var entry in v.entries) {
      Object? key = entry.key;
      Object? value = entry.value;
      typeWrapStack.push(keyWrap);
      _writeWithNullableSer(bw, key, keySer, pack);
      typeWrapStack.changeTop(valueWrap);
      _writeWithNullableSer(bw, value, valueSer, pack);
      typeWrapStack.pop();
    }
  }

  @inline
  void _writeWithNullableSer(ByteWriter bw, Object? v, Serializer? ser, SerializerPack pack){
    ser == null ?
    pack.forySer.xWriteRefNoSer(bw, v, pack) :
    pack.forySer.xWriteRefWithSer(bw, ser, v, pack);
  }

  @inline
  Object? _readWithNullableSer(ByteReader br, Serializer? ser, DeserializerPack pack){
    return ser == null ?
    pack.foryDeser.xReadRefNoSer(br, pack) :
    pack.foryDeser.xReadRefWithSer(br, ser, pack);
  }
}

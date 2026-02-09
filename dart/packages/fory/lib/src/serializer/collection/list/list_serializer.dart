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

import 'package:fory/src/const/types.dart';
import 'package:fory/src/deserializer_pack.dart';
import 'package:fory/src/memory/byte_reader.dart';
import 'package:fory/src/meta/spec_wraps/type_spec_wrap.dart';
import 'package:fory/src/serializer/collection/iterable_serializer.dart';
import 'package:fory/src/serializer/serializer.dart';

abstract base class ListSerializer extends IterableSerializer {

  const ListSerializer(bool writeRef): super(ObjType.LIST, writeRef);
  
  List newList(int size, bool nullable);

  @override
  List read(ByteReader br, int refId, DeserializerPack pack) {
    int num = br.readVarUint32Small7();
    TypeSpecWrap? elemWrap = pack.typeWrapStack.peek?.param0;
    List list = newList(
      num,
      elemWrap == null || elemWrap.nullable,
    );
    if (writeRef){
      pack.refResolver.setRefTheLatestId(list);
    }
    if (elemWrap == null){
      for (int i = 0; i < num; ++i) {
        Object? o = pack.foryDeser.xReadRefNoSer(br, pack);
        list[i] = o;
      }
      return list;
    }
    if (elemWrap.hasGenericsParam){
      pack.typeWrapStack.push(elemWrap);
    }
    if (!elemWrap.certainForSer){
      for (int i = 0; i < num; ++i) {
        Object? o = pack.foryDeser.xReadRefNoSer(br, pack);
        list[i] = o;
      }
    }else {
      Serializer? ser = elemWrap.ser;
      if (ser == null){
        for (int i = 0; i < num; ++i) {
          Object? o = pack.foryDeser.xReadRefNoSer(br, pack);
          list[i] = o;
        }
      }else{
        for (int i = 0; i < num; ++i) {
          Object? o = pack.foryDeser.xReadRefWithSer(br, ser, pack);
          list[i] = o;
        }
      }
    }
    if (elemWrap.hasGenericsParam){
      pack.typeWrapStack.pop();
    }
    return list;
  }
}
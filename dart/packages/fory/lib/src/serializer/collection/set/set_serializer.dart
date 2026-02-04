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

/// In fact, during our serialization process, there is no difference between List and Set,
/// but in Dart, List and Set do not have a common parent type like Collection.
/// They both implement the Iterable interface, which allows using a unified method for reading.
/// However, there is no upper-level add method, so there is no way to use a unified method for writing.
/// Therefore, even though the overall logic is similar, to avoid breaking the inheritance structure of Serializer,
/// we still need to implement this separately, which may introduce duplicate code.
library;

import 'package:fory/src/const/types.dart';
import 'package:fory/src/deserializer_pack.dart';
import 'package:fory/src/memory/byte_reader.dart';
import 'package:fory/src/meta/spec_wraps/type_spec_wrap.dart';
import 'package:fory/src/serializer/collection/iterable_serializer.dart';
import 'package:fory/src/serializer/serializer.dart';

abstract base class SetSerializer extends IterableSerializer {

  const SetSerializer(bool writeRef): super(ObjType.SET, writeRef);
  
  Set newSet(bool nullable);

  @override
  Set read(ByteReader br, int refId, DeserializerPack pack) {
    int num = br.readVarUint32Small7();
    TypeSpecWrap? elemWrap = pack.typeWrapStack.peek?.param0;
    Set set = newSet(
      elemWrap == null || elemWrap.nullable,
    );
    if (writeRef){
      pack.refResolver.setRefTheLatestId(set);
    }
    if (elemWrap == null){
      for (int i = 0; i < num; ++i) {
        Object? o = pack.foryDeser.xReadRefNoSer(br, pack);
        set.add(o);
      }
      return set;
    }
    if (elemWrap.hasGenericsParam){
      pack.typeWrapStack.push(elemWrap);
    }
    if (!elemWrap.certainForSer){
      for (int i = 0; i < num; ++i) {
        Object? o = pack.foryDeser.xReadRefNoSer(br, pack);
        set.add(o);
      }
    }else {
      Serializer? ser = elemWrap.ser;
      if (ser == null){
        for (int i = 0; i < num; ++i) {
          Object? o = pack.foryDeser.xReadRefNoSer(br, pack);
          set.add(o);
        }
      }else{
        for (int i = 0; i < num; ++i) {
          Object? o = pack.foryDeser.xReadRefWithSer(br, ser, pack);
          set.add(o);
        }
      }
    }
    if (elemWrap.hasGenericsParam){
      pack.typeWrapStack.pop();
    }
    return set;
  }
}

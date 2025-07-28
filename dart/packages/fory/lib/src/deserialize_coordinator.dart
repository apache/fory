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

import 'dart:typed_data';
import 'package:fory/src/collection/stack.dart';
import 'package:fory/src/config/fory_config.dart';
import 'package:fory/src/const/obj_type.dart';
import 'package:fory/src/const/ref_flag.dart';
import 'package:fory/src/datatype/float32.dart';
import 'package:fory/src/datatype/int16.dart';
import 'package:fory/src/datatype/int32.dart';
import 'package:fory/src/datatype/int8.dart';
import 'package:fory/src/memory/byte_reader.dart';
import 'package:fory/src/meta/type_info.dart';
import 'package:fory/src/meta/spec_wraps/type_spec_wrap.dart';
import 'package:fory/src/resolver/deserialization_ref_resolver.dart';
import 'package:fory/src/resolver/struct_hash_resolver.dart';
import 'package:fory/src/resolver/xtype_resolver.dart';
import 'package:fory/src/serializer/fory_header_serializer.dart';
import 'package:fory/src/deserializer_pack.dart';
import 'package:fory/src/serializer/serializer.dart';

class DeserializeCoordinator {

  static final DeserializeCoordinator _instance = DeserializeCoordinator._internal();
  static DeserializeCoordinator get I => _instance;
  DeserializeCoordinator._internal();

  static final ForyHeaderSerializer _foryHeaderSer = ForyHeaderSerializer.I;

  Object? read(Uint8List bytes, ForyConfig conf, XtypeResolver xtypeResolver, [ByteReader? reader]) {
    var br = reader ?? ByteReader.forBytes(bytes,);
    HeaderBrief? header = _foryHeaderSer.read(br, conf);
    if (header == null) return null;

    DeserializerPack deserPack = DeserializerPack(
      StructHashResolver.inst,
      xtypeResolver.getTagByCustomDartType,
      header,
      this,
      DeserializationRefResolver.getOne(conf.refTracking),
      xtypeResolver,
      Stack<TypeSpecWrap>(),
    );
    return xReadRefNoSer(br, deserPack);
  }

  Object? xReadRefNoSer(ByteReader br, DeserializerPack pack) {
    int refFlag = br.readInt8();
    //assert(RefFlag.checkAllow(refFlag));
    //assert(refFlag >= RefFlag.NULL.id);
    if (refFlag == RefFlag.NULL.id) return null;
    DeserializationRefResolver refResolver = pack.refResolver;
    if (refFlag == RefFlag.TRACKED_ALREADY.id){
      int refId = br.readVarUint32Small14();
      return refResolver.getObj(refId);
    }
    if (refFlag >= RefFlag.UNTRACKED_NOT_NULL.id){
      // must deserialize
      TypeInfo typeInfo = pack.xtypeResolver.readTypeInfo(br);
      int refId = refResolver.reserveId();
      Object o = _xRead(br, typeInfo, refId, pack);
      refResolver.setRef(refId, o);
      return o;
    }
    assert(false);
    return null; // won't reach here
  }

  Object? xReadRefWithSer(ByteReader br, Serializer ser, DeserializerPack pack) {
    if (ser.writeRef){
      DeserializationRefResolver refResolver = pack.refResolver;
      int refFlag = br.readInt8();
      //assert(RefFlag.checkAllow(refFlag));
      //assert(refFlag >= RefFlag.NULL.id);
      if (refFlag == RefFlag.NULL.id) return null;
      if (refFlag == RefFlag.TRACKED_ALREADY.id){
        int refId = br.readVarUint32Small14();
        return refResolver.getObj(refId);
      }
      if (refFlag >= RefFlag.UNTRACKED_NOT_NULL.id){
        // must deserialize
        int refId = refResolver.reserveId();
        Object o = ser.read(br, refId, pack);
        refResolver.setRef(refId, o);
        return o;
      }
    }
    int headFlag = br.readInt8();
    if (headFlag == RefFlag.NULL.id) return null;
    return ser.read(br, -1, pack);
  }

  /// this method will only be invoked by Fory::_xReadRef
  Object _xRead(ByteReader br, TypeInfo typeInfo, int refId, DeserializerPack pack) {
    switch (typeInfo.objType) {
      case ObjType.BOOL:
        return br.readInt8() != 0;
      case ObjType.INT8:
        return Int8(br.readInt8());
      case ObjType.INT16:
        return Int16(br.readInt16());
      case ObjType.INT32:
      case ObjType.VAR_INT32:
        return Int32(br.readVarInt32());
      case ObjType.INT64:
      case ObjType.VAR_INT64:
      case ObjType.SLI_INT64:
        return br.readVarInt64();
      case ObjType.FLOAT32:
        return Float32(br.readFloat32());
      case ObjType.FLOAT64:
        return br.readFloat64();
      default:
        Object o = typeInfo.ser.read(br, refId, pack);
        return o;
    }
  }
}
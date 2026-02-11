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
import 'package:fory/src/const/types.dart';
import 'package:fory/src/const/ref_flag.dart';
import 'package:fory/src/memory/byte_writer.dart';
import 'package:fory/src/meta/type_info.dart';
import 'package:fory/src/meta/spec_wraps/type_spec_wrap.dart';
import 'package:fory/src/resolver/meta_string_writing_resolver.dart';
import 'package:fory/src/resolver/serialization_ref_resolver.dart';
import 'package:fory/src/resolver/struct_hash_resolver.dart';
import 'package:fory/src/resolver/xtype_resolver.dart';
import 'package:fory/src/serializer/fory_header_serializer.dart';
import 'package:fory/src/serializer/serializer.dart';
import 'package:fory/src/serializer_pack.dart';
import 'package:fory/src/datatype/int8.dart';
import 'package:fory/src/datatype/int16.dart';
import 'package:fory/src/datatype/int32.dart';
import 'package:fory/src/datatype/float32.dart';

class SerializeCoordinator {
  static final SerializeCoordinator _instance =
      SerializeCoordinator._internal();
  static SerializeCoordinator get I => _instance;
  SerializeCoordinator._internal();

  static final ForyHeaderSerializer _foryHeaderSer = ForyHeaderSerializer.I;

  void _write(Object? obj, ForyConfig conf, XtypeResolver xtypeResolver,
      ByteWriter writer) {
    _foryHeaderSer.write(writer, obj == null, conf);
    xtypeResolver.resetWriteContext();
    SerializerPack pack = SerializerPack(
      StructHashResolver.inst,
      xtypeResolver.getTagByCustomDartType,
      this,
      xtypeResolver,
      SerializationRefResolver.getOne(conf.refTracking),
      SerializationRefResolver.noRefResolver,
      MetaStringWritingResolver.newInst,
      Stack<TypeSpecWrap>(),
    );
    xWriteRefNoSer(writer, obj, pack);
    // pack.resetAndRecycle();
  }

  Uint8List write(
    Object? obj,
    ForyConfig conf,
    XtypeResolver xtypeResolver,
  ) {
    ByteWriter bw = ByteWriter();
    _write(obj, conf, xtypeResolver, bw);
    return bw.takeBytes();
  }

  void writeWithWriter(Object? obj, ForyConfig conf,
      XtypeResolver xtypeResolver, ByteWriter writer) {
    _write(obj, conf, xtypeResolver, writer);
  }

  void xWriteRefNoSer(ByteWriter bw, Object? obj, SerializerPack pack) {
    SerializationRefMeta serRef = pack.refResolver.getRefId(obj);
    bw.writeInt8(serRef.refFlag.id);
    if (serRef.refId != null) {
      bw.writeVarUint32(serRef.refId!);
    }
    if (serRef.refFlag.noNeedToSer) return;
    TypeInfo typeInfo = pack.xtypeResolver.writeGetTypeInfo(bw, obj!, pack);
    switch (typeInfo.objType) {
      case ObjType.BOOL:
        bw.writeBool(obj as bool);
        break;
      case ObjType.INT8:
        bw.writeInt8((obj as Int8).value);
        break;
      case ObjType.INT16:
        bw.writeInt16((obj as Int16).value);
        break;
      case ObjType.INT32:
      case ObjType.VAR_INT32:
        bw.writeVarInt32((obj as Int32).value);
        break;
      case ObjType.INT64:
      case ObjType.VAR_INT64:
        bw.writeVarInt64(obj as int);
        break;
      case ObjType.FLOAT32:
        bw.writeFloat32((obj as Float32).value);
        break;
      case ObjType.FLOAT64:
        bw.writeFloat64(obj as double);
        break;
      default:
        typeInfo.ser.write(bw, obj, pack);
    }
  }

  void xWriteNonRefNoSer(ByteWriter bw, Object obj, SerializerPack pack) {
    TypeInfo typeInfo = pack.xtypeResolver.writeGetTypeInfo(bw, obj, pack);
    switch (typeInfo.objType) {
      case ObjType.BOOL:
        bw.writeBool(obj as bool);
        break;
      case ObjType.INT8:
        bw.writeInt8((obj as Int8).value);
        break;
      case ObjType.INT16:
        bw.writeInt16((obj as Int16).value);
        break;
      case ObjType.INT32:
      case ObjType.VAR_INT32:
        bw.writeVarInt32((obj as Int32).value);
        break;
      case ObjType.INT64:
      case ObjType.VAR_INT64:
        bw.writeVarInt64(obj as int);
        break;
      case ObjType.FLOAT32:
        bw.writeFloat32((obj as Float32).value);
        break;
      case ObjType.FLOAT64:
        bw.writeFloat64(obj as double);
        break;
      default:
        typeInfo.ser.write(bw, obj, pack);
    }
  }

  void xWriteRefWithSer(
      ByteWriter bw, Serializer ser, Object? obj, SerializerPack pack) {
    if (ser.writeRef) {
      SerializationRefMeta serRef = pack.refResolver.getRefId(obj);
      bw.writeInt8(serRef.refFlag.id);
      if (serRef.refId != null) {
        bw.writeVarUint32(serRef.refId!);
      }
      if (serRef.refFlag.noNeedToSer) return;
      ser.write(bw, obj, pack);
    } else {
      RefFlag refFlag = pack.noRefResolver.getRefFlag(obj);
      bw.writeInt8(refFlag.id);
      if (refFlag.noNeedToSer) return;
      ser.write(bw, obj, pack);
    }
  }
}

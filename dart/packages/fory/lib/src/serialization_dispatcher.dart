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
import 'package:fory/src/resolver/type_resolver.dart';
import 'package:fory/src/serializer/fory_header_serializer.dart';
import 'package:fory/src/serializer/serializer.dart';
import 'package:fory/src/serialization_context.dart';
import 'package:fory/src/datatype/int8.dart';
import 'package:fory/src/datatype/int16.dart';
import 'package:fory/src/datatype/int32.dart';
import 'package:fory/src/datatype/float32.dart';

class SerializationDispatcher {
  static final SerializationDispatcher _instance =
      SerializationDispatcher._internal();
  static SerializationDispatcher get I => _instance;
  SerializationDispatcher._internal();

  static final ForyHeaderSerializer _foryHeaderSerializer =
      ForyHeaderSerializer.I;

  void _write(Object? obj, ForyConfig conf, TypeResolver typeResolver,
      ByteWriter writer) {
    _foryHeaderSerializer.write(writer, obj == null, conf);
    typeResolver.resetWriteContext();
    SerializationContext pack = SerializationContext(
      StructHashResolver.inst,
      typeResolver.getRegisteredTag,
      this,
      typeResolver,
      SerializationRefResolver.getOne(conf.ref),
      SerializationRefResolver.noRefResolver,
      MetaStringWritingResolver.newInst,
      Stack<TypeSpecWrap>(),
    );
    writeDynamicWithRef(writer, obj, pack);
    // pack.resetAndRecycle();
  }

  Uint8List write(
    Object? obj,
    ForyConfig conf,
    TypeResolver typeResolver,
  ) {
    ByteWriter bw = ByteWriter();
    _write(obj, conf, typeResolver, bw);
    return bw.takeBytes();
  }

  void writeWithWriter(Object? obj, ForyConfig conf, TypeResolver typeResolver,
      ByteWriter writer) {
    _write(obj, conf, typeResolver, writer);
  }

  void writeDynamicWithRef(
      ByteWriter bw, Object? obj, SerializationContext pack) {
    SerializationRefMeta serializerWithRef = pack.refResolver.getRefId(obj);
    bw.writeInt8(serializerWithRef.refFlag.id);
    if (serializerWithRef.refId != null) {
      bw.writeVarUint32(serializerWithRef.refId!);
    }
    if (serializerWithRef.refFlag.noNeedToSerialize) return;
    TypeInfo typeInfo = pack.typeResolver.writeTypeInfo(bw, obj!, pack);
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
        typeInfo.serializer.write(bw, obj, pack);
    }
  }

  void writeDynamicWithoutRef(
      ByteWriter bw, Object obj, SerializationContext pack) {
    TypeInfo typeInfo = pack.typeResolver.writeTypeInfo(bw, obj, pack);
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
        typeInfo.serializer.write(bw, obj, pack);
    }
  }

  void writeWithSerializer(ByteWriter bw, Serializer serializer, Object? obj,
      SerializationContext pack,
      {bool? trackingRefOverride}) {
    bool trackingRef = trackingRefOverride ?? serializer.writeRef;
    if (trackingRef) {
      SerializationRefMeta serializerWithRef = pack.refResolver.getRefId(obj);
      bw.writeInt8(serializerWithRef.refFlag.id);
      if (serializerWithRef.refId != null) {
        bw.writeVarUint32(serializerWithRef.refId!);
      }
      if (serializerWithRef.refFlag.noNeedToSerialize) return;
      serializer.write(bw, obj, pack);
    } else {
      RefFlag refFlag = pack.noRefResolver.getRefFlag(obj);
      bw.writeInt8(refFlag.id);
      if (refFlag.noNeedToSerialize) return;
      serializer.write(bw, obj, pack);
    }
  }
}

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
import 'package:collection/collection.dart';
import 'package:fory/src/const/types.dart';
import 'package:fory/src/serializer/array_serializer.dart';
import 'package:fory/src/deserialization_context.dart';
import 'package:fory/src/memory/byte_reader.dart';
import 'package:fory/src/memory/byte_writer.dart';
import 'package:fory/src/serializer/serializer_cache.dart';
import 'package:fory/src/serialization_context.dart';

final class _BoolListSerializerCache extends ArraySerializerCache {
  static BoolListSerializer? _serializerWithoutReference;
  static BoolListSerializer? _serializerWithReference;

  const _BoolListSerializerCache();

  @override
  BoolListSerializer getSerializerWithRef(bool writeRef) {
    if (writeRef) {
      _serializerWithReference ??= BoolListSerializer(true);
      return _serializerWithReference!;
    } else {
      _serializerWithoutReference ??= BoolListSerializer(false);
      return _serializerWithoutReference!;
    }
  }
}

final class BoolListSerializer extends ArraySerializer<bool> {
  static const SerializerCache cache = _BoolListSerializerCache();
  const BoolListSerializer(bool writeRef) : super(ObjType.BOOL_ARRAY, writeRef);

  @override
  BoolList read(ByteReader br, int refId, DeserializationContext pack) {
    int num = br.readVarUint32Small7();
    BoolList list = BoolList(num);
    Uint8List bytes = br.readBytesView(num);
    for (int i = 0; i < num; ++i) {
      list[i] = bytes[i] != 0;
    }
    return list;
  }

  @override
  void write(ByteWriter bw, covariant BoolList v, SerializationContext pack) {
    bw.writeVarUint32(v.length);
    Uint8List bytes = Uint8List(v.length);
    for (int i = 0; i < v.length; ++i) {
      bytes[i] = v[i] ? 1 : 0;
    }
    bw.writeBytes(bytes);
  }
}

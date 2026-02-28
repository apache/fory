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

import 'package:fory/src/config/fory_config.dart';
import 'package:fory/src/const/types.dart';
import 'package:fory/src/deserialization_context.dart';
import 'package:fory/src/fory_exception.dart';
import 'package:fory/src/memory/byte_reader.dart';
import 'package:fory/src/memory/byte_writer.dart';
import 'package:fory/src/meta/specs/enum_spec.dart';
import 'package:fory/src/serializer/custom_serializer.dart';
import 'package:fory/src/serializer/serializer_cache.dart';
import 'package:fory/src/serialization_context.dart';

final class _EnumSerializerCache extends SerializerCache {
  static final Map<Type, EnumSerializer> _cache = {};

  const _EnumSerializerCache();

  @override
  EnumSerializer getSerializerWithSpec(
      ForyConfig conf, covariant EnumSpec spec, Type dartType) {
    EnumSerializer? serializer = _cache[dartType];
    if (serializer != null) {
      return serializer;
    }
    // In foryJava, EnumSerializer does not perform reference tracking
    serializer = EnumSerializer(false, spec.values);
    _cache[dartType] = serializer;
    return serializer;
  }
}

final class EnumSerializer extends CustomSerializer<Enum> {
  static const SerializerCache cache = _EnumSerializerCache();

  final List<Enum> values;
  EnumSerializer(bool writeRef, this.values)
      : super(ObjType.NAMED_ENUM, writeRef);

  @override
  Enum read(ByteReader br, int refId, DeserializationContext pack) {
    int index = br.readVarUint32Small7();
    // foryJava supports deserializeUnknownEnumValueAsNull,
    // but here in Dart, it will definitely throw an error if the index is out of range
    if (index < 0 || index >= values.length) {
      throw DeserializationRangeException(index, values);
    }
    return values[index];
  }

  @override
  void write(ByteWriter bw, Enum v, SerializationContext pack) {
    bw.writeVarUint32Small7(v.index);
  }
}

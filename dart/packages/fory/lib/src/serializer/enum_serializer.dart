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
    serializer = EnumSerializer(false, spec.values, spec.idToValue);
    _cache[dartType] = serializer;
    return serializer;
  }
}

final class EnumSerializer extends CustomSerializer<Enum> {
  static const SerializerCache cache = _EnumSerializerCache();

  final List<Enum> values;
  final Map<int, Enum>? _idToValue;
  final Map<Enum, int>? _valueToId;
  final List<Object>? _idCandidates;

  EnumSerializer(bool writeRef, this.values, [Map<int, Enum>? idToValue])
      : _idToValue = idToValue,
        _valueToId = idToValue == null
            ? null
            : <Enum, int>{
                for (final MapEntry<int, Enum> entry in idToValue.entries)
                  entry.value: entry.key,
              },
        _idCandidates = idToValue == null
            ? null
            : List<Object>.unmodifiable(idToValue.keys),
        super(ObjType.NAMED_ENUM, writeRef);

  @override
  Enum read(ByteReader br, int refId, DeserializationContext pack) {
    final int indexOrId = br.readVarUint32Small7();
    final Map<int, Enum>? idToValue = _idToValue;
    if (idToValue != null) {
      final Enum? enumValue = idToValue[indexOrId];
      if (enumValue == null) {
        throw DeserializationRangeException(indexOrId, _idCandidates!);
      }
      return enumValue;
    }
    // foryJava supports deserializeUnknownEnumValueAsNull,
    // but here in Dart, it will definitely throw an error if the index is out of range
    // This is for the ordinal-based deserailization only when previous check fails
    // and the variable here still means index, not id.
    if (indexOrId < 0 || indexOrId >= values.length) {
      throw DeserializationRangeException(indexOrId, values);
    }
    return values[indexOrId];
  }

  @override
  void write(ByteWriter bw, Enum v, SerializationContext pack) {
    final Map<Enum, int>? valueToId = _valueToId;
    if (valueToId != null) {
      final int? id = valueToId[v];
      if (id == null) {
        throw ArgumentError.value(
          v,
          'v',
          'Enum value is missing from EnumSpec.idToValue mapping.',
        );
      }
      bw.writeVarUint32Small7(id);
      return;
    }

    bw.writeVarUint32Small7(v.index);
  }
}

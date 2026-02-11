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
import 'package:fory/src/codegen/entity/struct_hash_pair.dart';
import 'package:fory/src/config/fory_config.dart';
import 'package:fory/src/deserialization_runtime.dart';
import 'package:fory/src/dev_annotation/optimize.dart';
import 'package:fory/src/memory/byte_reader.dart';
import 'package:fory/src/memory/byte_writer.dart';
import 'package:fory/src/meta/specs/class_spec.dart';
import 'package:fory/src/meta/specs/custom_type_spec.dart';
import 'package:fory/src/meta/specs/enum_spec.dart';
import 'package:fory/src/resolver/type_resolver.dart';
import 'package:fory/src/serialization_runtime.dart';
import 'package:fory/src/serializer/serializer.dart';

final class Fory {
  static final DeserializationRuntime _deserializer = DeserializationRuntime.I;
  static final SerializationRuntime _serializer = SerializationRuntime.I;

  final ForyConfig _config;
  late final TypeResolver _typeResolver;

  Fory({
    bool compatible = false,
    bool ref = false,
    bool basicTypesRefIgnored = true,
    bool timeRefIgnored = true,
    bool stringRefIgnored = false,
  }) : this.fromConfig(
          ForyConfig(
            compatible: compatible,
            ref: ref,
            basicTypesRefIgnored: basicTypesRefIgnored,
            timeRefIgnored: timeRefIgnored,
            stringRefIgnored: stringRefIgnored,
          ),
        );

  Fory.fromConfig(this._config) {
    _typeResolver = TypeResolver.newOne(_config);
  }

  ForyConfig get config => _config;

  @inline
  void register(
    CustomTypeSpec spec, {
    int? typeId,
    String? namespace,
    String? typename,
  }) {
    registerType(
      spec,
      typeId: typeId,
      namespace: namespace,
      typename: typename,
    );
  }

  @inline
  void registerType(
    CustomTypeSpec spec, {
    int? typeId,
    String? namespace,
    String? typename,
  }) {
    _typeResolver.registerType(
      spec,
      typeId: typeId,
      namespace: namespace,
      typename: typename,
    );
  }

  @inline
  void registerClass(
    ClassSpec spec, {
    int? typeId,
    String? namespace,
    String? typename,
  }) {
    register(
      spec,
      typeId: typeId,
      namespace: namespace,
      typename: typename,
    );
  }

  @inline
  void registerEnum(
    EnumSpec spec, {
    int? typeId,
    String? namespace,
    String? typename,
  }) {
    register(
      spec,
      typeId: typeId,
      namespace: namespace,
      typename: typename,
    );
  }

  @inline
  void registerSerializer(Type type, Serializer serializer) {
    _typeResolver.registerSerializer(type, serializer);
  }

  @inline
  Object? deserialize(Uint8List bytes, [ByteReader? reader]) {
    return _deserializer.read(bytes, _config, _typeResolver, reader);
  }

  @inline
  T deserializeAs<T>(Uint8List bytes, {ByteReader? reader}) {
    final Object? value = deserialize(bytes, reader);
    if (value is T) {
      return value;
    }
    throw StateError(
      'Deserialized value has type ${value.runtimeType}, expected $T.',
    );
  }

  @inline
  Uint8List serialize(Object? value) {
    return _serializer.write(value, _config, _typeResolver);
  }

  @inline
  void serializeTo(Object? value, ByteWriter writer) {
    _serializer.writeWithWriter(value, _config, _typeResolver, writer);
  }

  StructHashPair structHashPairForTest(Type type) {
    return _typeResolver.getHashPairForTest(type);
  }
}

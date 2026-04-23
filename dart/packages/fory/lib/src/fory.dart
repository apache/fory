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

import 'package:fory/src/memory/buffer.dart';
import 'package:fory/src/config.dart';
import 'package:fory/src/context/meta_string_reader.dart';
import 'package:fory/src/context/meta_string_writer.dart';
import 'package:fory/src/context/read_context.dart';
import 'package:fory/src/context/ref_reader.dart';
import 'package:fory/src/context/ref_writer.dart';
import 'package:fory/src/context/write_context.dart';
import 'package:fory/src/resolver/type_resolver.dart';
import 'package:fory/src/serializer/serializer.dart';

/// Root facade for Apache Fory xlang serialization in Dart.
///
/// Typical usage is:
/// 1. create a [Fory] instance
/// 2. register generated types or register manual serializers
/// 3. call [serialize] and [deserialize]
///
/// The Dart runtime only supports xlang payloads.
final class Fory {
  static const int _nullHeaderFlag = 0x01;
  static const int _xlangHeaderFlag = 0x02;
  static const int _outOfBandHeaderFlag = 0x04;

  late final Buffer _buffer;
  late final WriteContext _writeContext;
  late final ReadContext _readContext;
  late final TypeResolver _typeResolver;

  /// Creates a runtime configured by direct constructor options.
  ///
  /// The same instance can be reused across many operations. Each operation
  /// resets its transient read/write state before work starts.
  Fory({
    bool compatible = false,
    bool checkStructVersion = true,
    int maxDepth = Config.defaultMaxDepth,
    int maxCollectionSize = Config.defaultMaxCollectionSize,
    int maxBinarySize = Config.defaultMaxBinarySize,
  }) {
    final config = Config(
      compatible: compatible,
      checkStructVersion: checkStructVersion,
      maxDepth: maxDepth,
      maxCollectionSize: maxCollectionSize,
      maxBinarySize: maxBinarySize,
    );
    _buffer = Buffer();
    _typeResolver = TypeResolver(config);
    _writeContext = WriteContext(
      config,
      _typeResolver,
      RefWriter(),
      MetaStringWriter(),
    );
    _readContext = ReadContext(
      config,
      _typeResolver,
      RefReader(),
      MetaStringReader(_typeResolver),
    );
  }

  /// Serializes [value] into a new byte array.
  ///
  /// Set [trackRef] to `true` only when the root value is a graph or container
  /// that needs shared-reference tracking and there is no field metadata to
  /// request it. Annotated fields should still use `@ForyField(ref: true)`.
  Uint8List serialize(Object? value, {bool trackRef = false}) {
    _buffer.clear();
    serializeTo(value, _buffer, trackRef: trackRef);
    return Uint8List.fromList(_buffer.toBytes());
  }

  /// Serializes [value] into [buffer].
  ///
  /// The target [buffer] is cleared before bytes are written. [trackRef] has
  /// the same root-level semantics as [serialize].
  void serializeTo(Object? value, Buffer buffer, {bool trackRef = false}) {
    buffer.clear();
    _writeContext.prepare(buffer, trackRef: trackRef);
    try {
      if (value == null) {
        buffer.writeUint8(_nullHeaderFlag);
        return;
      }
      buffer.writeUint8(_xlangHeaderFlag);
      _writeContext.writeRootValue(value, trackRef: trackRef);
    } finally {
      _writeContext.reset();
    }
  }

  /// Deserializes [bytes] and then checks that the result is assignable to [T].
  ///
  /// The payload is decoded from its wire metadata first. `T` is used as a
  /// post-read type check, not as an alternate schema.
  T deserialize<T>(Uint8List bytes) {
    _buffer.wrap(bytes);
    return deserializeFrom<T>(_buffer);
  }

  /// Deserializes a value from [buffer] and checks that it is assignable to
  /// [T].
  ///
  /// The wire metadata normally determines the decoded value type. Supplying
  /// [T] also preserves typed root `Int64` values for varint64 payloads so
  /// callers can distinguish them from plain `int` roots where the platform
  /// representation supports that distinction.
  ///
  /// Only xlang payloads are supported. This method consumes bytes from the
  /// current reader position of [buffer].
  T deserializeFrom<T>(Buffer buffer) {
    _readContext.prepare(buffer);
    try {
      final header = buffer.readUint8();
      if ((header & _outOfBandHeaderFlag) != 0) {
        throw StateError(
          'Out-of-band buffers are not supported by the Dart runtime.',
        );
      }
      if ((header & _nullHeaderFlag) != 0) {
        return null as T;
      }
      if ((header & _xlangHeaderFlag) == 0) {
        throw StateError(
          'Only xlang payloads are supported by the Dart runtime.',
        );
      }
      final value = _readContext.readRefAs<T>();
      if (value is T) {
        return value;
      }
      throw StateError(
        'Deserialized value has type ${value.runtimeType}, expected $T.',
      );
    } finally {
      _readContext.reset();
    }
  }

  /// Registers a generated type.
  ///
  /// Exactly one registration mode is required:
  /// - pass [id] for id-based registration, or
  /// - pass both [namespace] and [typeName] for name-based registration.
  ///
  /// Generated struct and enum registration should normally flow through the
  /// generated library namespace, which installs generated metadata into the
  /// internal registry before calling this method. For manual serializers,
  /// including unions, use [registerSerializer].
  void register(
    Type type, {
    int? id,
    String? namespace,
    String? typeName,
  }) {
    _typeResolver.registerGenerated(
      type,
      id: id,
      namespace: namespace,
      typeName: typeName,
    );
  }

  /// Registers a manual serializer for [type].
  ///
  /// Exactly one registration mode is required:
  /// - pass [id] for id-based registration, or
  /// - pass both [namespace] and [typeName] for name-based registration.
  ///
  /// This is the advanced escape hatch for external types, manual unions, or
  /// custom wire behavior. Prefer [register] for generated types.
  void registerSerializer(
    Type type,
    Serializer serializer, {
    int? id,
    String? namespace,
    String? typeName,
  }) {
    _typeResolver.registerSerializer(
      type,
      serializer,
      id: id,
      namespace: namespace,
      typeName: typeName,
    );
  }
}

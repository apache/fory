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

import 'dart:collection';

import 'package:meta/meta.dart';

import 'package:fory/src/buffer.dart';
import 'package:fory/src/config.dart';
import 'package:fory/src/context/meta_string_writer.dart';
import 'package:fory/src/context/ref_writer.dart';
import 'package:fory/src/meta/field_type.dart';
import 'package:fory/src/meta/type_def.dart';
import 'package:fory/src/meta/type_ids.dart';
import 'package:fory/src/resolver/type_resolver.dart';
import 'package:fory/src/serializer/collection_serializers.dart';
import 'package:fory/src/serializer/map_serializers.dart';
import 'package:fory/src/serializer/primitive_serializers.dart';
import 'package:fory/src/serializer/scalar_serializers.dart';
import 'package:fory/src/serializer/struct_slots.dart';
import 'package:fory/src/serializer/typed_array_serializers.dart';
import 'package:fory/src/types/bfloat16.dart';
import 'package:fory/src/types/float16.dart';
import 'package:fory/src/types/local_date.dart';
import 'package:fory/src/types/timestamp.dart';

/// Write-side serializer context.
///
/// Generated and manual serializers receive this during a single serialization
/// operation. Application code normally interacts with [Fory] instead of
/// constructing contexts directly.
final class WriteContext {
  /// Effective runtime configuration for the active operation.
  final Config config;
  final TypeResolver _typeResolver;
  final RefWriter _refWriter;
  final MetaStringWriter _metaStringWriter;

  late Buffer _buffer;
  final LinkedHashMap<TypeDef, int> _typeDefIds =
      LinkedHashMap<TypeDef, int>.identity();
  StructWriteSlots? _structWriteSlots;
  bool _rootTrackRef = false;
  int _depth = 0;

  @internal
  WriteContext(
    this.config,
    this._typeResolver,
    this._refWriter,
    this._metaStringWriter,
  );

  @internal
  void prepare(Buffer buffer, {required bool trackRef}) {
    _buffer = buffer;
    _rootTrackRef = trackRef;
  }

  @internal
  void reset() {
    _typeDefIds.clear();
    _refWriter.reset();
    _metaStringWriter.reset();
    _structWriteSlots = null;
    _rootTrackRef = false;
    _depth = 0;
  }

  /// The active output buffer for the current operation.
  Buffer get buffer => _buffer;

  @internal
  TypeResolver get typeResolver => _typeResolver;

  @internal
  RefWriter get refWriter => _refWriter;

  @internal
  bool get rootTrackRef => _rootTrackRef;

  @internal
  StructWriteSlots? get structWriteSlots => _structWriteSlots;

  @internal
  set structWriteSlots(StructWriteSlots? value) {
    _structWriteSlots = value;
  }

  /// Records entry into one more nested write frame.
  void increaseDepth() {
    _depth += 1;
    if (_depth > config.maxDepth) {
      throw StateError('Serialization depth exceeded ${config.maxDepth}.');
    }
  }

  /// Records exit from a nested write frame.
  void decreaseDepth() {
    _depth -= 1;
  }

  /// Writes a boolean value.
  void writeBool(bool value) => _buffer.writeBool(value);

  /// Writes a signed 8-bit integer.
  void writeByte(int value) => _buffer.writeByte(value);

  /// Writes an unsigned 8-bit integer.
  void writeUint8(int value) => _buffer.writeUint8(value);

  /// Writes a signed little-endian 16-bit integer.
  void writeInt16(int value) => _buffer.writeInt16(value);

  /// Writes a signed little-endian 32-bit integer.
  void writeInt32(int value) => _buffer.writeInt32(value);

  /// Writes a signed little-endian 64-bit integer.
  void writeInt64(int value) => _buffer.writeInt64(value);

  /// Writes a half-precision floating-point value.
  void writeFloat16(Float16 value) => _buffer.writeFloat16(value);

  /// Writes a brain floating-point (bfloat16) value.
  void writeBfloat16(BFloat16 value) => _buffer.writeBfloat16(value);

  /// Writes a single-precision floating-point value.
  void writeFloat32(double value) => _buffer.writeFloat32(value);

  /// Writes a double-precision floating-point value.
  void writeFloat64(double value) => _buffer.writeFloat64(value);

  /// Writes a zig-zag encoded signed 32-bit varint.
  void writeVarInt32(int value) => _buffer.writeVarInt32(value);

  /// Writes an unsigned 32-bit varint.
  void writeVarUint32(int value) => _buffer.writeVarUint32(value);

  /// Writes a zig-zag encoded signed 64-bit varint.
  void writeVarInt64(int value) => _buffer.writeVarInt64(value);

  /// Writes a tagged signed 64-bit integer.
  void writeTaggedInt64(int value) => _buffer.writeTaggedInt64(value);

  /// Writes an unsigned 64-bit varint.
  void writeVarUint64(int value) => _buffer.writeVarUint64(value);

  /// Writes a tagged unsigned 64-bit integer.
  void writeTaggedUint64(int value) => _buffer.writeTaggedUint64(value);

  /// Writes a non-null string payload without adding type metadata.
  void writeString(String value) => StringSerializer.writePayload(this, value);

  /// Writes a nullable value with Ref tracking and type metadata.
  void writeRef(Object? value) {
    _writeRef(value, trackRef: true);
  }

  /// Writes a non-null value together with its type metadata.
  void writeNonRef(Object value) {
    final resolved = _typeResolver.resolveValue(value);
    _writeTypeMeta(resolved, value);
    writeResolvedValue(resolved, value, null);
  }

  /// Writes only the ref-or-null header for [value].
  bool writeRefOrNull(Object? value) {
    return _refWriter.writeRefOrNull(_buffer, value);
  }

  /// Writes the fresh-value or back-reference header for non-null [value].
  bool writeRefValueFlag(Object value) {
    return _refWriter.writeRefValueFlag(_buffer, value);
  }

  /// Writes the null flag when [value] is `null`.
  bool writeNullFlag(Object? value) {
    return _refWriter.writeNullFlag(_buffer, value);
  }

  @internal
  void writeRootValue(Object? value, {required bool trackRef}) {
    if (value == null) {
      _writeRef(value, trackRef: trackRef);
      return;
    }
    final resolved = _typeResolver.resolveValue(value);
    if (trackRef) {
      _writeRef(value, trackRef: true);
      return;
    }
    _refWriter.writeRefOrNull(_buffer, value, trackRef: false);
    if (resolved.supportsRef) {
      _refWriter.reference(value);
    }
    _writeTypeMeta(resolved, value);
    writeResolvedValue(resolved, value, null);
  }

  void _writeRef(Object? value, {required bool trackRef}) {
    final resolved = value == null ? null : _typeResolver.resolveValue(value);
    final effectiveTrackRef =
        trackRef && value != null && resolved!.supportsRef;
    if (_refWriter.writeRefOrNull(
      _buffer,
      value,
      trackRef: effectiveTrackRef,
    )) {
      return;
    }
    if (value == null) {
      return;
    }
    _writeTypeMeta(resolved!, value);
    writeResolvedValue(resolved, value, null);
  }

  @internal
  void writePrimitiveValue(int typeId, Object value) =>
      PrimitiveSerializer.writePayload(this, typeId, value);

  @internal
  void writeResolvedValue(
    TypeInfo resolved,
    Object value,
    FieldType? declaredFieldType,
  ) {
    if (!_tracksDepth(resolved)) {
      _writePayloadValue(resolved, value, declaredFieldType);
      return;
    }
    increaseDepth();
    _writePayloadValue(resolved, value, declaredFieldType);
    decreaseDepth();
  }

  void _writePayloadValue(
    TypeInfo resolved,
    Object value,
    FieldType? declaredFieldType,
  ) {
    switch (resolved.typeId) {
      case TypeIds.boolType:
      case TypeIds.int8:
      case TypeIds.int16:
      case TypeIds.int32:
      case TypeIds.varInt32:
      case TypeIds.varInt64:
      case TypeIds.taggedInt64:
      case TypeIds.int64:
      case TypeIds.uint8:
      case TypeIds.uint16:
      case TypeIds.uint32:
      case TypeIds.varUint32:
      case TypeIds.uint64:
      case TypeIds.varUint64:
      case TypeIds.taggedUint64:
      case TypeIds.float16:
      case TypeIds.bfloat16:
      case TypeIds.float32:
      case TypeIds.float64:
        PrimitiveSerializer.writePayload(this, resolved.typeId, value);
        return;
      case TypeIds.string:
        StringSerializer.writePayload(this, value as String);
        return;
      case TypeIds.binary:
        BinarySerializer.writePayload(this, value as Uint8List);
        return;
      case TypeIds.boolArray:
        boolArraySerializer.write(this, value as List<bool>);
        return;
      case TypeIds.int8Array:
        int8ArraySerializer.write(this, value as Int8List);
        return;
      case TypeIds.int16Array:
        int16ArraySerializer.write(this, value as Int16List);
        return;
      case TypeIds.int32Array:
        int32ArraySerializer.write(this, value as Int32List);
        return;
      case TypeIds.int64Array:
        int64ArraySerializer.write(this, value as Int64List);
        return;
      case TypeIds.uint16Array:
        uint16ArraySerializer.write(this, value as Uint16List);
        return;
      case TypeIds.uint32Array:
        uint32ArraySerializer.write(this, value as Uint32List);
        return;
      case TypeIds.uint64Array:
        uint64ArraySerializer.write(this, value as Uint64List);
        return;
      case TypeIds.float32Array:
        float32ArraySerializer.write(this, value as Float32List);
        return;
      case TypeIds.float64Array:
        float64ArraySerializer.write(this, value as Float64List);
        return;
      case TypeIds.list:
        ListSerializer.writePayload(
          this,
          value as Iterable,
          declaredFieldType?.arguments.isEmpty ?? true
              ? null
              : declaredFieldType!.arguments.first,
          trackRef:
              declaredFieldType == null ? rootTrackRef : declaredFieldType.ref,
        );
        return;
      case TypeIds.set:
        ListSerializer.writePayload(
          this,
          value as Set,
          declaredFieldType?.arguments.isEmpty ?? true
              ? null
              : declaredFieldType!.arguments.first,
          trackRef:
              declaredFieldType == null ? rootTrackRef : declaredFieldType.ref,
        );
        return;
      case TypeIds.map:
        MapSerializer.writePayload(
          this,
          value as Map,
          declaredFieldType == null || declaredFieldType.arguments.isEmpty
              ? null
              : declaredFieldType.arguments[0],
          declaredFieldType == null || declaredFieldType.arguments.length < 2
              ? null
              : declaredFieldType.arguments[1],
          trackRef:
              declaredFieldType == null ? rootTrackRef : declaredFieldType.ref,
        );
        return;
      case TypeIds.date:
        localDateSerializer.write(this, value as LocalDate);
        return;
      case TypeIds.timestamp:
        timestampSerializer.write(this, value as Timestamp);
        return;
      default:
        if (resolved.kind == RegistrationKind.struct) {
          resolved.structSerializer!.writeValue(this, resolved, value);
          return;
        }
        resolved.serializer.write(this, value);
    }
  }

  void _writeTypeMeta(TypeInfo resolved, Object value) {
    _typeResolver.writeTypeMeta(
      _buffer,
      resolved,
      typeDef: resolved.structSerializer?.typeDefForWrite(
        this,
        resolved,
        value,
      ),
      typeDefIds: _typeDefIds,
      metaStringWriter: _metaStringWriter,
    );
  }

  @internal
  void writeTypeMetaValue(TypeInfo resolved, Object value) {
    _writeTypeMeta(resolved, value);
  }

  bool _tracksDepth(TypeInfo resolved) {
    if (TypeIds.isContainer(resolved.typeId)) {
      return true;
    }
    switch (resolved.kind) {
      case RegistrationKind.builtin:
      case RegistrationKind.enumType:
        return false;
      case RegistrationKind.struct:
      case RegistrationKind.ext:
      case RegistrationKind.union:
        return true;
    }
  }
}

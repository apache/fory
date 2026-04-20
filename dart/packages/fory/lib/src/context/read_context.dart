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

import 'package:meta/meta.dart';

import 'package:fory/src/buffer.dart';
import 'package:fory/src/config.dart';
import 'package:fory/src/context/meta_string_reader.dart';
import 'package:fory/src/context/ref_reader.dart';
import 'package:fory/src/context/ref_writer.dart';
import 'package:fory/src/meta/field_type.dart';
import 'package:fory/src/meta/type_ids.dart';
import 'package:fory/src/resolver/type_resolver.dart';
import 'package:fory/src/serializer/collection_serializers.dart';
import 'package:fory/src/serializer/map_serializers.dart';
import 'package:fory/src/serializer/primitive_serializers.dart';
import 'package:fory/src/serializer/scalar_serializers.dart';
import 'package:fory/src/serializer/serializer.dart';
import 'package:fory/src/serializer/struct_slots.dart';
import 'package:fory/src/serializer/typed_array_serializers.dart';
import 'package:fory/src/types/float16.dart';

/// Read-side serializer context.
///
/// Generated and manual serializers receive this during a single
/// deserialization operation. Application code normally interacts with [Fory]
/// instead of preparing contexts directly.
final class ReadContext {
  /// Effective runtime configuration for the active operation.
  final Config config;
  final TypeResolver _typeResolver;
  final RefReader _refReader;
  final MetaStringReader _metaStringReader;

  late Buffer _buffer;
  final List<TypeInfo> _sharedTypes = <TypeInfo>[];
  StructReadSlots? _structReadSlots;
  int _depth = 0;

  @internal
  ReadContext(
    this.config,
    this._typeResolver,
    this._refReader,
    this._metaStringReader,
  );

  @internal
  void prepare(Buffer buffer) {
    _buffer = buffer;
  }

  @internal
  void reset() {
    _sharedTypes.clear();
    _refReader.reset();
    _metaStringReader.reset();
    _structReadSlots = null;
    _depth = 0;
  }

  /// The active input buffer for the current operation.
  Buffer get buffer => _buffer;

  @internal
  TypeResolver get typeResolver => _typeResolver;

  @internal
  RefReader get refReader => _refReader;

  @internal
  StructReadSlots? get structReadSlots => _structReadSlots;

  @internal
  set structReadSlots(StructReadSlots? value) {
    _structReadSlots = value;
  }

  @internal
  TypeInfo readTypeMetaValue([
    TypeInfo? expectedNamedType,
  ]) =>
      _readTypeMeta(expectedNamedType);

  @internal
  Object readSerializerPayload(
      Serializer<Object?> serializer, TypeInfo resolved,
      {required bool hasCurrentPreservedRef}) {
    final int? sentinelId;
    final int? sentinelDepth;
    final needsSentinel = resolved.supportsRef && !hasCurrentPreservedRef;
    if (needsSentinel) {
      sentinelId = _refReader.preserveRefId(-1);
      sentinelDepth = _refReader.preservedRefDepth;
    } else {
      sentinelId = null;
      sentinelDepth = null;
    }
    final value = serializer.read(this) as Object;
    if (needsSentinel &&
        _refReader.preservedRefDepth == sentinelDepth &&
        _refReader.hasPreservedRefId &&
        _refReader.lastPreservedRefId == sentinelId) {
      _refReader.reference(null);
    }
    return value;
  }

  /// Records entry into one more nested read frame.
  void increaseDepth() {
    _depth += 1;
    if (_depth > config.maxDepth) {
      throw StateError('Deserialization depth exceeded ${config.maxDepth}.');
    }
  }

  /// Records exit from a nested read frame.
  void decreaseDepth() {
    _depth -= 1;
  }

  /// Reads a boolean value.
  bool readBool() => _buffer.readBool();

  /// Reads a signed 8-bit integer.
  int readByte() => _buffer.readByte();

  /// Reads an unsigned 8-bit integer.
  int readUint8() => _buffer.readUint8();

  /// Reads a signed little-endian 16-bit integer.
  int readInt16() => _buffer.readInt16();

  /// Reads a signed little-endian 32-bit integer.
  int readInt32() => _buffer.readInt32();

  /// Reads a signed little-endian 64-bit integer.
  int readInt64() => _buffer.readInt64();

  /// Reads a half-precision floating-point value.
  Float16 readFloat16() => _buffer.readFloat16();

  /// Reads a single-precision floating-point value.
  double readFloat32() => _buffer.readFloat32();

  /// Reads a double-precision floating-point value.
  double readFloat64() => _buffer.readFloat64();

  /// Reads a zig-zag encoded signed 32-bit varint.
  int readVarInt32() => _buffer.readVarInt32();

  /// Reads an unsigned 32-bit varint.
  int readVarUint32() => _buffer.readVarUint32();

  /// Reads a zig-zag encoded signed 64-bit varint.
  int readVarInt64() => _buffer.readVarInt64();

  /// Reads a tagged signed 64-bit integer.
  int readTaggedInt64() => _buffer.readTaggedInt64();

  /// Reads an unsigned 64-bit varint.
  int readVarUint64() => _buffer.readVarUint64();

  /// Reads a tagged unsigned 64-bit integer.
  int readTaggedUint64() => _buffer.readTaggedUint64();

  /// Binds [value] to the most recently preserved Ref slot.
  void reference(Object? value) {
    _refReader.reference(value);
  }

  /// Reads a non-null string payload without ref/null handling.
  String readString() => StringSerializer.readPayload(this);

  /// Reads a ref-or-null header and resolves back-references immediately.
  int readRefOrNull() => _refReader.readRefOrNull(_buffer);

  /// Reserves the next read ref id or reuses [refId] when provided.
  int preserveRefId([int? refId]) => _refReader.preserveRefId(refId);

  /// Reads a ref/value header and preserves a new id only for fresh values.
  int tryPreserveRefId() => _refReader.tryPreserveRefId(_buffer);

  /// Returns the last reserved read ref id.
  int get lastPreservedRefId => _refReader.lastPreservedRefId;

  /// Returns whether a reserved read ref id is waiting to be bound.
  bool get hasPreservedRefId => _refReader.hasPreservedRefId;

  /// Returns the resolved read ref or the read ref stored at [id].
  Object? getReadRef([int? id]) => _refReader.getReadRef(id);

  /// Associates [value] with a previously preserved read ref [id].
  void setReadRef(int id, Object? value) => _refReader.setReadRef(id, value);

  /// Reads a nullable value using Ref semantics and wire type metadata.
  Object? readRef() {
    final flag = _refReader.tryPreserveRefId(_buffer);
    final preservedRefId = flag >= RefWriter.refValueFlag ? flag : null;
    if (flag == RefWriter.nullFlag) {
      return null;
    }
    if (flag == RefWriter.refFlag) {
      return _refReader.getReadRef();
    }
    final resolved = _readTypeMeta();
    final rootPreservedRefId = preservedRefId == null &&
            flag == RefWriter.notNullValueFlag &&
            _depth == 0 &&
            resolved.supportsRef
        ? _refReader.preserveRefId()
        : null;
    final value = readResolvedValue(
      resolved,
      null,
      hasPreservedRef: preservedRefId != null || rootPreservedRefId != null,
    );
    if (preservedRefId != null &&
        resolved.supportsRef &&
        _refReader.readRefAt(preservedRefId) == null) {
      _refReader.setReadRef(preservedRefId, value);
    }
    if (rootPreservedRefId != null &&
        _refReader.readRefAt(rootPreservedRefId) == null) {
      _refReader.setReadRef(rootPreservedRefId, value);
    }
    return value;
  }

  /// Reads a non-null value using the type metadata stored in the payload.
  Object readNonRef() {
    final resolved = _readTypeMeta();
    return readResolvedValue(resolved, null) as Object;
  }

  /// Reads a nullable value using the standard Fory nullable framing.
  Object? readNullable() {
    final flag = _buffer.readByte();
    if (flag == RefWriter.nullFlag) {
      return null;
    }
    if (flag != RefWriter.notNullValueFlag) {
      throw StateError('Unexpected nullable flag $flag.');
    }
    return readNonRef();
  }

  @internal
  Object readPrimitiveValue(int typeId) =>
      PrimitiveSerializer.readPayload(this, typeId);

  @internal
  Object? readResolvedValue(TypeInfo resolved, FieldType? declaredFieldType,
      {bool hasPreservedRef = false}) {
    if (!_tracksDepth(resolved)) {
      return _readPayloadValue(
        resolved,
        declaredFieldType,
        hasPreservedRef: hasPreservedRef,
      );
    }
    increaseDepth();
    final value = _readPayloadValue(
      resolved,
      declaredFieldType,
      hasPreservedRef: hasPreservedRef,
    );
    decreaseDepth();
    return value;
  }

  Object? _readPayloadValue(
    TypeInfo resolved,
    FieldType? declaredFieldType, {
    required bool hasPreservedRef,
  }) {
    if (TypeIds.isPrimitive(resolved.typeId)) {
      return PrimitiveSerializer.readPayload(this, resolved.typeId);
    }
    switch (resolved.typeId) {
      case TypeIds.string:
        return StringSerializer.readPayload(this);
      case TypeIds.binary:
        return BinarySerializer.readPayload(this);
      case TypeIds.boolArray:
        return const BoolArraySerializer().read(this);
      case TypeIds.int8Array:
        return int8ArraySerializer.read(this);
      case TypeIds.int16Array:
        return int16ArraySerializer.read(this);
      case TypeIds.int32Array:
        return int32ArraySerializer.read(this);
      case TypeIds.int64Array:
        return int64ArraySerializer.read(this);
      case TypeIds.uint16Array:
        return uint16ArraySerializer.read(this);
      case TypeIds.uint32Array:
        return uint32ArraySerializer.read(this);
      case TypeIds.uint64Array:
        return uint64ArraySerializer.read(this);
      case TypeIds.float32Array:
        return float32ArraySerializer.read(this);
      case TypeIds.float64Array:
        return float64ArraySerializer.read(this);
      case TypeIds.list:
        return ListSerializer.readPayload(
          this,
          declaredFieldType?.arguments.isEmpty ?? true
              ? null
              : declaredFieldType!.arguments.first,
        );
      case TypeIds.set:
        return SetSerializer.readPayload(
          this,
          declaredFieldType?.arguments.isEmpty ?? true
              ? null
              : declaredFieldType!.arguments.first,
        );
      case TypeIds.map:
        return MapSerializer.readPayload(
          this,
          declaredFieldType == null || declaredFieldType.arguments.isEmpty
              ? null
              : declaredFieldType.arguments[0],
          declaredFieldType == null || declaredFieldType.arguments.length < 2
              ? null
              : declaredFieldType.arguments[1],
        );
      case TypeIds.date:
        return const LocalDateSerializer().read(this);
      case TypeIds.timestamp:
        return const TimestampSerializer().read(this);
      default:
        if (resolved.kind == RegistrationKind.struct) {
          return resolved.structSerializer!.readValue(
            this,
            resolved,
            hasCurrentPreservedRef: hasPreservedRef,
          );
        }
        return readSerializerPayload(
          resolved.serializer,
          resolved,
          hasCurrentPreservedRef: hasPreservedRef,
        );
    }
  }

  TypeInfo _readTypeMeta([
    TypeInfo? expectedNamedType,
  ]) {
    return _typeResolver.readTypeMeta(
      _buffer,
      expectedNamedType: expectedNamedType,
      sharedTypes: _sharedTypes,
      metaStringReader: _metaStringReader,
    );
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

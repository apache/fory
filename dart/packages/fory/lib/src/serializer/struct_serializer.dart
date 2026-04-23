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

import 'package:fory/src/context/read_context.dart';
import 'package:fory/src/context/write_context.dart';
import 'package:fory/src/meta/field_info.dart';
import 'package:fory/src/meta/type_def.dart';
import 'package:fory/src/resolver/type_resolver.dart';
import 'package:fory/src/serializer/compatible_struct_metadata.dart';
import 'package:fory/src/serializer/serializer.dart';
import 'package:fory/src/serializer/serialization_field_info.dart';
import 'package:fory/src/serializer/serializer_support.dart';
import 'package:fory/src/serializer/struct_slots.dart';
import 'package:fory/src/util/hash_util.dart';

typedef GeneratedStructCompatibleFactory<T> = T Function();

typedef GeneratedStructCompatibleFieldReader<T> = void Function(
  ReadContext context,
  T value,
  Object? rawValue,
);

final class StructSerializer extends Serializer<Object?> {
  final Serializer<Object?> _payloadSerializer;
  final TypeDef _typeDef;
  final TypeResolver _typeResolver;
  late final List<SerializationFieldInfo> _localFields =
      List<SerializationFieldInfo>.unmodifiable(
    List<SerializationFieldInfo>.generate(
      _typeDef.fields.length,
      (slot) => _typeResolver.serializationFieldInfo(
        _typeDef.fields[slot],
        slot: slot,
      ),
    ),
  );
  late final Map<String, SerializationFieldInfo> _localFieldsByIdentifier =
      <String, SerializationFieldInfo>{
    for (final field in _localFields) field.identifier: field,
  };
  final Expando<_CompatibleReadLayout> _compatibleReadLayouts =
      Expando<_CompatibleReadLayout>('fory_compatible_read_layout');
  final GeneratedStructCompatibleFactory<Object>? _compatibleFactory;
  final List<GeneratedStructCompatibleFieldReader<Object>>?
      _compatibleReadersBySlot;

  StructSerializer(
    this._payloadSerializer,
    this._typeDef,
    this._typeResolver, {
    GeneratedStructCompatibleFactory<Object>? compatibleFactory,
    List<GeneratedStructCompatibleFieldReader<Object>>? compatibleReadersBySlot,
  })  : _compatibleFactory = compatibleFactory,
        _compatibleReadersBySlot = compatibleReadersBySlot;

  @override
  bool get supportsRef => _payloadSerializer.supportsRef;

  @override
  void write(WriteContext context, Object? value) {
    throw StateError('StructSerializer.write requires struct dispatch.');
  }

  @override
  Object read(ReadContext context) {
    throw StateError('StructSerializer.read requires struct dispatch.');
  }

  List<SerializationFieldInfo> get localFields => _localFields;

  TypeDef typeDefForWrite(
    WriteContext context,
    TypeInfo resolved,
    Object value,
  ) {
    return resolved.typeDef!;
  }

  void writeValue(
    WriteContext context,
    TypeInfo resolved,
    Object value,
  ) {
    final internal = context;
    if (!context.config.compatible && context.config.checkStructVersion) {
      context.buffer.writeUint32(schemaHash(_typeDef));
    }
    final previousCompatibleFields =
        _replaceWriteSlots(internal, resolved, value);
    _payloadSerializer.write(context, value);
    internal.structWriteSlots = previousCompatibleFields;
  }

  @pragma('vm:prefer-inline')
  Object readValue(
    ReadContext context,
    TypeInfo resolved, {
    bool hasCurrentPreservedRef = false,
  }) {
    final internal = context;
    if (!context.config.compatible && context.config.checkStructVersion) {
      final expected = schemaHash(_typeDef);
      final actual = context.buffer.readUint32();
      if (actual != expected) {
        throw StateError(
          'Struct schema version mismatch for ${resolved.type}: $actual != $expected.',
        );
      }
    }
    if (context.config.compatible &&
        resolved.isCompatibleStruct &&
        resolved.remoteTypeDef != null) {
      return _readCompatible(
        internal,
        resolved,
        hasCurrentPreservedRef: hasCurrentPreservedRef,
      );
    }
    final previousReadSlots = internal.structReadSlots;
    internal.structReadSlots = null;
    final value = internal.readSerializerPayload(
      _payloadSerializer,
      resolved,
      hasCurrentPreservedRef: hasCurrentPreservedRef,
    ) as Object;
    internal.structReadSlots = previousReadSlots;
    _rememberRemoteMetadata(internal, resolved, value);
    return value;
  }

  StructWriteSlots? _replaceWriteSlots(
    WriteContext context,
    TypeInfo resolved,
    Object value,
  ) {
    final previous = context.structWriteSlots;
    context.structWriteSlots = null;
    return previous;
  }

  Object _readCompatible(
    ReadContext context,
    TypeInfo resolved, {
    required bool hasCurrentPreservedRef,
  }) {
    final layout = _compatibleReadLayoutForResolved(resolved);
    final compatibleFactory = _compatibleFactory;
    final compatibleReadersBySlot = _compatibleReadersBySlot;
    if (compatibleFactory != null && compatibleReadersBySlot != null) {
      final int? sentinelId;
      final needsSentinel = resolved.supportsRef && !hasCurrentPreservedRef;
      if (needsSentinel) {
        sentinelId = context.refReader.preserveRefId(-1);
      } else {
        sentinelId = null;
      }
      final value = compatibleFactory();
      context.reference(value);
      for (var index = 0; index < layout.fields.length; index += 1) {
        final localField = layout.fields[index];
        if (localField == null) {
          readCompatibleField(context, layout.remoteFields[index]);
          continue;
        }
        compatibleReadersBySlot[localField.slot](
          context,
          value,
          readFieldValue<Object?>(context, localField),
        );
      }
      if (needsSentinel &&
          context.refReader.hasPreservedRefId &&
          context.refReader.lastPreservedRefId == sentinelId) {
        context.refReader.reference(null);
      }
      _rememberRemoteMetadata(context, resolved, value);
      return value;
    }
    final compatibleValues = List<Object?>.filled(_localFields.length, null);
    final presentSlots = List<bool>.filled(_localFields.length, false);
    for (var index = 0; index < layout.fields.length; index += 1) {
      final localField = layout.fields[index];
      if (localField == null) {
        readCompatibleField(context, layout.remoteFields[index]);
        continue;
      }
      final slot = localField.slot;
      compatibleValues[slot] = readFieldValue(
        context,
        localField,
      );
      presentSlots[slot] = true;
    }
    final previousCompatibleFields = context.structReadSlots;
    context.structReadSlots = StructReadSlots(compatibleValues, presentSlots);
    final value = context.readSerializerPayload(
      _payloadSerializer,
      resolved,
      hasCurrentPreservedRef: hasCurrentPreservedRef,
    ) as Object;
    context.structReadSlots = previousCompatibleFields;
    _rememberRemoteMetadata(context, resolved, value);
    return value;
  }

  _CompatibleReadLayout _compatibleReadLayoutForResolved(
    TypeInfo resolved,
  ) {
    final remoteTypeDef = resolved.remoteTypeDef;
    if (remoteTypeDef == null) {
      return _CompatibleReadLayout(_typeDef.fields, _localFields);
    }
    final cached = _compatibleReadLayouts[remoteTypeDef];
    if (cached != null) {
      return cached;
    }
    final fields = <SerializationFieldInfo?>[];
    for (final remoteField in remoteTypeDef.fields) {
      final localField = _localFieldsByIdentifier[remoteField.identifier];
      if (localField == null) {
        fields.add(null);
        continue;
      }
      final mergedField = _typeResolver.serializationFieldInfo(
        mergeCompatibleReadField(localField.field, remoteField),
        slot: localField.slot,
      );
      fields.add(mergedField);
    }
    final layout = _CompatibleReadLayout(
      remoteTypeDef.fields,
      List<SerializationFieldInfo?>.unmodifiable(fields),
    );
    _compatibleReadLayouts[remoteTypeDef] = layout;
    return layout;
  }

  void _rememberRemoteMetadata(
    ReadContext context,
    TypeInfo resolved,
    Object value,
  ) {
    final remoteTypeDef = resolved.remoteTypeDef;
    if (remoteTypeDef != null) {
      CompatibleStructMetadata.rememberRemoteTypeDef(value, remoteTypeDef);
    }
  }
}

final class _CompatibleReadLayout {
  final List<FieldInfo> remoteFields;
  final List<SerializationFieldInfo?> fields;

  const _CompatibleReadLayout(this.remoteFields, this.fields);
}

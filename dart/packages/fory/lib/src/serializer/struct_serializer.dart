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
import 'package:fory/src/meta/field_type.dart';
import 'package:fory/src/meta/type_def.dart';
import 'package:fory/src/resolver/type_resolver.dart';
import 'package:fory/src/serializer/collection_serializers.dart';
import 'package:fory/src/serializer/generated_struct_serializer.dart';
import 'package:fory/src/serializer/scalar_conversion.dart';
import 'package:fory/src/serializer/serialization_field_info.dart';
import 'package:fory/src/serializer/serializer.dart';
import 'package:fory/src/serializer/serializer_support.dart';
import 'package:fory/src/util/hash_util.dart';

final class StructSerializer extends Serializer<Object?> {
  final Serializer<Object?> _payloadSerializer;
  final TypeDef _typeDef;
  final TypeResolver _typeResolver;
  late final List<SerializationFieldInfo> _localFields =
      List<SerializationFieldInfo>.unmodifiable(
        List<SerializationFieldInfo>.generate(
          _typeDef.fields.length,
          (index) => _typeResolver.serializationFieldInfo(
            _typeDef.fields[index],
            index: index,
          ),
        ),
      );
  late final Map<String, SerializationFieldInfo> _localFieldsByIdentifier =
      <String, SerializationFieldInfo>{
        for (final field in _localFields) field.identifier: field,
      };
  final Expando<CompatibleStructReadLayout> _compatibleReadLayouts =
      Expando<CompatibleStructReadLayout>('fory_compatible_read_layout');

  StructSerializer(this._payloadSerializer, this._typeDef, this._typeResolver);

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

  void writeValue(WriteContext context, TypeInfo resolved, Object value) {
    if (!context.config.compatible && context.config.checkStructVersion) {
      context.buffer.writeUint32(schemaHash(_typeDef));
    }
    _payloadSerializer.write(context, value);
  }

  @pragma('vm:prefer-inline')
  Object readValue(
    ReadContext context,
    TypeInfo resolved, {
    bool hasCurrentPreservedRef = false,
  }) {
    if (context.config.compatible &&
        resolved.isCompatibleStruct &&
        resolved.remoteTypeDef != null) {
      return _readCompatible(context, resolved);
    }
    return readSameTypeValue(context, resolved);
  }

  @pragma('vm:prefer-inline')
  Object readSameTypeValue(ReadContext context, TypeInfo resolved) {
    if (!context.config.compatible && context.config.checkStructVersion) {
      final expected = schemaHash(_typeDef);
      final actual = context.buffer.readUint32();
      if (actual != expected) {
        throw StateError(
          'Struct schema version mismatch for ${resolved.type}: $actual != $expected.',
        );
      }
    }
    return _payloadSerializer.read(context) as Object;
  }

  @pragma('vm:never-inline')
  Object _readCompatible(ReadContext context, TypeInfo resolved) {
    final payloadSerializer = _payloadSerializer;
    if (payloadSerializer is! GeneratedStructSerializer<Object?>) {
      throw StateError(
        'Compatible struct read for ${resolved.type} requires a generated struct serializer.',
      );
    }
    final layout = _compatibleReadLayoutForResolved(resolved);
    final value = payloadSerializer.readCompatibleStruct(context, layout);
    if (value == null) {
      throw StateError(
        'Compatible struct read for ${resolved.type} returned null.',
      );
    }
    return value;
  }

  @pragma('vm:prefer-inline')
  CompatibleStructReadLayout _compatibleReadLayoutForResolved(
    TypeInfo resolved,
  ) {
    final remoteTypeDef = resolved.remoteTypeDef;
    if (remoteTypeDef == null) {
      return CompatibleStructReadLayout(_typeDef.fields, _localFields);
    }
    final cached = _compatibleReadLayouts[remoteTypeDef];
    if (cached != null) {
      return cached;
    }
    return _buildCompatibleReadLayout(remoteTypeDef);
  }

  CompatibleStructReadLayout _buildCompatibleReadLayout(TypeDef remoteTypeDef) {
    final fields = <SerializationFieldInfo?>[];
    List<CompatibleScalarConversion?>? scalarConversions;
    var hasScalarConversions = false;
    List<bool>? topLevelListArrayPairs;
    var hasTopLevelListArrayPairs = false;
    for (final remoteField in remoteTypeDef.fields) {
      final localField = _localFieldsByIdentifier[remoteField.identifier];
      if (localField == null) {
        fields.add(null);
        scalarConversions?.add(null);
        topLevelListArrayPairs?.add(false);
        continue;
      }
      final topLevelListArrayPair = _topLevelListArrayPair(
        localField.field,
        remoteField,
      );
      if (_hasUnsupportedListArrayMismatch(
        localField.field.fieldType,
        remoteField.fieldType,
        topLevel: true,
      )) {
        throw StateError(
          'Compatible field ${localField.name} has unsupported list/array schema mismatch.',
        );
      }
      if (topLevelListArrayPair) {
        topLevelListArrayPairs ??= List<bool>.filled(
          fields.length,
          false,
          growable: true,
        );
        hasTopLevelListArrayPairs = true;
      }
      final scalarPair =
          isCompatibleScalarType(remoteField.fieldType.typeId) &&
          isCompatibleScalarType(localField.field.fieldType.typeId);
      final refTrackedScalarSchemaMismatch =
          scalarPair &&
          (remoteField.fieldType.ref != localField.field.fieldType.ref ||
              ((remoteField.fieldType.ref || localField.field.fieldType.ref) &&
                  (remoteField.fieldType.typeId !=
                          localField.field.fieldType.typeId ||
                      remoteField.fieldType.nullable !=
                          localField.field.fieldType.nullable)));
      if (refTrackedScalarSchemaMismatch) {
        fields.add(null);
        scalarConversions?.add(null);
        topLevelListArrayPairs?.add(false);
        continue;
      }
      final scalarConversion =
          topLevelListArrayPair
              ? null
              : compatibleScalarConversion(remoteField, localField.field);
      if (scalarConversion != null) {
        scalarConversions ??= List<CompatibleScalarConversion?>.filled(
          fields.length,
          null,
          growable: true,
        );
        hasScalarConversions = true;
      }
      final mergedField =
          topLevelListArrayPair || scalarConversion != null
              ? localField
              : _typeResolver.serializationFieldInfo(
                mergeCompatibleReadField(localField.field, remoteField),
                index: localField.index,
              );
      fields.add(mergedField);
      scalarConversions?.add(scalarConversion);
      topLevelListArrayPairs?.add(topLevelListArrayPair);
    }
    final layout = CompatibleStructReadLayout(
      remoteTypeDef.fields,
      List<SerializationFieldInfo?>.unmodifiable(fields),
      hasScalarConversions
          ? List<CompatibleScalarConversion?>.unmodifiable(scalarConversions!)
          : null,
      hasTopLevelListArrayPairs
          ? List<bool>.unmodifiable(topLevelListArrayPairs!)
          : null,
    );
    _compatibleReadLayouts[remoteTypeDef] = layout;
    return layout;
  }
}

bool _topLevelListArrayPair(FieldInfo localField, FieldInfo remoteField) {
  return isCompatibleCollectionArrayFieldPair(localField, remoteField);
}

bool _hasUnsupportedListArrayMismatch(
  FieldType localType,
  FieldType remoteType, {
  required bool topLevel,
}) {
  if (isCompatibleCollectionArrayRootTypePair(localType, remoteType)) {
    return !(topLevel &&
        isCompatibleCollectionArrayTypePair(localType, remoteType));
  }
  if (localType.typeId != remoteType.typeId ||
      localType.arguments.length != remoteType.arguments.length) {
    return false;
  }
  for (var index = 0; index < localType.arguments.length; index += 1) {
    if (_hasUnsupportedListArrayMismatch(
      localType.arguments[index],
      remoteType.arguments[index],
      topLevel: false,
    )) {
      return true;
    }
  }
  return false;
}

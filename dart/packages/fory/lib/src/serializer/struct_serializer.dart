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
import 'package:fory/src/meta/type_ids.dart';
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
  final Map<TypeDef, CompatibleStructReadLayout> _compatibleReadLayouts =
      Map<TypeDef, CompatibleStructReadLayout>.identity();
  TypeDef? _lastCompatibleRemoteTypeDef;
  CompatibleStructReadLayout? _lastCompatibleReadLayout;

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
    return readGeneratedCompatibleValue(context, resolved);
  }

  @pragma('vm:prefer-inline')
  Object readGeneratedCompatibleValue(ReadContext context, TypeInfo resolved) {
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
      return CompatibleStructReadLayout(
        List<CompatibleStructReadField>.unmodifiable(
          List<CompatibleStructReadField>.generate(
            _localFields.length,
            (index) => CompatibleStructReadField(
              remoteField: _typeDef.fields[index],
              matchedId: index * 2,
              localField: _localFields[index],
              scalarRead: null,
              topLevelListArrayPair: false,
            ),
          ),
        ),
      );
    }
    final lastRemoteTypeDef = _lastCompatibleRemoteTypeDef;
    if (identical(lastRemoteTypeDef, remoteTypeDef)) {
      return _lastCompatibleReadLayout!;
    }
    final cached = _compatibleReadLayouts[remoteTypeDef];
    if (cached != null) {
      _lastCompatibleRemoteTypeDef = remoteTypeDef;
      _lastCompatibleReadLayout = cached;
      return cached;
    }
    return _buildCompatibleReadLayout(remoteTypeDef);
  }

  CompatibleStructReadLayout _buildCompatibleReadLayout(TypeDef remoteTypeDef) {
    final fields = <CompatibleStructReadField>[];
    for (
      var remoteIndex = 0;
      remoteIndex < remoteTypeDef.fields.length;
      remoteIndex += 1
    ) {
      final remoteField = remoteTypeDef.fields[remoteIndex];
      final localField = _localFieldsByIdentifier[remoteField.identifier];
      if (localField == null) {
        fields.add(
          CompatibleStructReadField(
            remoteField: remoteField,
            matchedId: -1,
            localField: null,
            scalarRead: null,
            topLevelListArrayPair: false,
          ),
        );
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
      final scalarConversion =
          topLevelListArrayPair
              ? null
              : compatibleScalarConversion(remoteField, localField.field);
      final scalarRead =
          scalarConversion == null
              ? null
              : compatibleScalarReadDescriptor(scalarConversion);
      final exactField = _sameFieldType(
        localField.field.fieldType,
        remoteField.fieldType,
      );
      if (!exactField &&
          !topLevelListArrayPair &&
          scalarConversion == null &&
          !_compatibleFieldType(
            localField.field.fieldType,
            remoteField.fieldType,
            topLevel: true,
          )) {
        throw StateError(
          'Compatible field ${localField.name} has incompatible local and remote schemas.',
        );
      }
      final matchedId =
          exactField ? localField.index * 2 : localField.index * 2 + 1;
      final mergedField =
          exactField || topLevelListArrayPair || scalarRead != null
              ? localField
              : _typeResolver.serializationFieldInfo(
                mergeCompatibleReadField(localField.field, remoteField),
                index: localField.index,
              );
      fields.add(
        CompatibleStructReadField(
          remoteField: remoteField,
          matchedId: matchedId,
          localField: mergedField,
          scalarRead: scalarRead,
          topLevelListArrayPair: topLevelListArrayPair,
        ),
      );
    }
    final layout = CompatibleStructReadLayout(
      List<CompatibleStructReadField>.unmodifiable(fields),
    );
    _compatibleReadLayouts[remoteTypeDef] = layout;
    _lastCompatibleRemoteTypeDef = remoteTypeDef;
    _lastCompatibleReadLayout = layout;
    return layout;
  }
}

bool _topLevelListArrayPair(FieldInfo localField, FieldInfo remoteField) {
  return isCompatibleCollectionArrayFieldPair(localField, remoteField);
}

bool _sameFieldType(FieldType localType, FieldType remoteType) {
  if (localType.typeId != remoteType.typeId ||
      localType.nullable != remoteType.nullable ||
      localType.ref != remoteType.ref ||
      _explicitDynamic(localType) != _explicitDynamic(remoteType) ||
      localType.arguments.length != remoteType.arguments.length) {
    return false;
  }
  for (var index = 0; index < localType.arguments.length; index += 1) {
    if (!_sameFieldType(
      localType.arguments[index],
      remoteType.arguments[index],
    )) {
      return false;
    }
  }
  return true;
}

bool _compatibleFieldType(
  FieldType localType,
  FieldType remoteType, {
  required bool topLevel,
}) {
  if (_sameFieldType(localType, remoteType)) {
    return true;
  }
  if (_explicitDynamic(localType) != _explicitDynamic(remoteType)) {
    return false;
  }
  final scalarPair =
      isCompatibleScalarType(remoteType.typeId) &&
      isCompatibleScalarType(localType.typeId);
  if (scalarPair) {
    // Scalar conversion is an immediate field adaptation; nested container
    // element/key/value scalar types must stay schema-compatible as written.
    if (!topLevel) {
      return localType.typeId == remoteType.typeId &&
          localType.arguments.length == remoteType.arguments.length;
    }
    if (remoteType.ref || localType.ref) {
      return false;
    }
    if (localType.typeId == remoteType.typeId &&
        localType.arguments.length == remoteType.arguments.length) {
      return true;
    }
    return compatibleScalarConversion(
          FieldInfo(name: '', identifier: '', id: null, fieldType: remoteType),
          FieldInfo(name: '', identifier: '', id: null, fieldType: localType),
        ) !=
        null;
  }
  if (_isStructWireType(localType.typeId) &&
      _isStructWireType(remoteType.typeId) &&
      localType.arguments.length == remoteType.arguments.length) {
    return true;
  }
  if (topLevel && isCompatibleCollectionArrayTypePair(localType, remoteType)) {
    return true;
  }
  final sameWireFamily =
      localType.typeId == remoteType.typeId ||
      _compatibleUnknownUserType(localType, remoteType) ||
      _compatibleGeneratedManualUserType(localType, remoteType) ||
      (_isStructWireType(localType.typeId) &&
          _isStructWireType(remoteType.typeId));
  if (!sameWireFamily ||
      localType.arguments.length != remoteType.arguments.length) {
    return false;
  }
  for (var index = 0; index < localType.arguments.length; index += 1) {
    if (!_compatibleFieldType(
      localType.arguments[index],
      remoteType.arguments[index],
      topLevel: false,
    )) {
      return false;
    }
  }
  return true;
}

bool _explicitDynamic(FieldType fieldType) => fieldType.dynamic == true;

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

bool _isStructWireType(int typeId) =>
    typeId == TypeIds.struct ||
    typeId == TypeIds.compatibleStruct ||
    typeId == TypeIds.namedStruct ||
    typeId == TypeIds.namedCompatibleStruct;

bool _isManualUserWireType(int typeId) =>
    typeId == TypeIds.ext ||
    typeId == TypeIds.namedExt ||
    typeId == TypeIds.union ||
    typeId == TypeIds.typedUnion ||
    typeId == TypeIds.namedUnion;

bool _compatibleGeneratedManualUserType(
  FieldType localType,
  FieldType remoteType,
) {
  if (localType.arguments.isNotEmpty || remoteType.arguments.isNotEmpty) {
    return false;
  }
  return (_isStructWireType(localType.typeId) &&
          _isManualUserWireType(remoteType.typeId)) ||
      (_isManualUserWireType(localType.typeId) &&
          _isStructWireType(remoteType.typeId));
}

bool _compatibleUnknownUserType(FieldType localType, FieldType remoteType) {
  if (localType.typeId == TypeIds.unknown) {
    return remoteType.typeId == TypeIds.unknown ||
        TypeIds.isUserType(remoteType.typeId);
  }
  if (remoteType.typeId == TypeIds.unknown) {
    return TypeIds.isUserType(localType.typeId);
  }
  return false;
}

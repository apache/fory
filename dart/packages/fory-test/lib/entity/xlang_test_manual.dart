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

library;

// ignore_for_file: implementation_imports, invalid_use_of_internal_member

import 'package:fory/fory.dart';

import 'xlang_test_models.dart';

bool registerXlangManualType(Fory fory, Type type, {int? id, String? name}) {
  if (type == MyExt) {
    fory.registerSerializer(
      MyExt,
      const _MyExtSerializer(),
      id: id,
      name: name,
    );
    return true;
  }
  if (type == Union2) {
    fory.registerSerializer(
      Union2,
      const _Union2Serializer(),
      id: id,
      name: name,
    );
    return true;
  }
  if (type == RefOverrideContainer) {
    registerGeneratedStruct(
      fory,
      _refOverrideContainerForySchema,
      id: id,
      name: name,
    );
    return true;
  }
  return false;
}

final class _Union2Serializer extends UnionSerializer<Union2> {
  const _Union2Serializer();

  @override
  int caseId(Union2 value) => value.index;

  @override
  Object caseValue(Union2 value) => value.value;

  @override
  Union2 buildValue(int caseId, Object? value) {
    if (caseId == 0 && value is String) {
      return Union2.ofString(value);
    }
    if (caseId == 1 && value is int) {
      return Union2.ofInt64(value);
    }
    throw StateError('Unsupported Union2 case $caseId with value $value.');
  }
}

final class _MyExtSerializer extends Serializer<MyExt> {
  const _MyExtSerializer();

  @override
  void write(WriteContext context, MyExt value) {
    context.writeVarInt32(value.id);
  }

  @override
  MyExt read(ReadContext context) {
    return MyExt(context.readVarInt32());
  }
}

const GeneratedFieldType _refOverrideElementFieldType = GeneratedFieldType(
  type: RefOverrideElement,
  typeId: TypeIds.compatibleStruct,
  nullable: true,
  ref: true,
  dynamic: null,
  arguments: <GeneratedFieldType>[],
);

const List<GeneratedFieldInfo>
_refOverrideContainerForyFieldInfo = <GeneratedFieldInfo>[
  // Keep this manual fixture in the same protocol order as generated structs:
  // all fields are non-primitive, so field identifiers order the wire payload.
  GeneratedFieldInfo(
    name: 'listField',
    identifier: 'list_field',
    id: null,
    fieldType: GeneratedFieldType(
      type: List,
      typeId: TypeIds.list,
      nullable: false,
      ref: false,
      dynamic: null,
      arguments: <GeneratedFieldType>[_refOverrideElementFieldType],
    ),
  ),
  GeneratedFieldInfo(
    name: 'mapField',
    identifier: 'map_field',
    id: null,
    fieldType: GeneratedFieldType(
      type: Map,
      typeId: TypeIds.map,
      nullable: false,
      ref: false,
      dynamic: null,
      arguments: <GeneratedFieldType>[
        GeneratedFieldType(
          type: String,
          typeId: TypeIds.string,
          nullable: true,
          ref: false,
          dynamic: null,
          arguments: <GeneratedFieldType>[],
        ),
        _refOverrideElementFieldType,
      ],
    ),
  ),
  GeneratedFieldInfo(
    name: 'setField',
    identifier: 'set_field',
    id: null,
    fieldType: GeneratedFieldType(
      type: Set,
      typeId: TypeIds.set,
      nullable: false,
      ref: false,
      dynamic: null,
      arguments: <GeneratedFieldType>[_refOverrideElementFieldType],
    ),
  ),
];

final GeneratedStructSchema<RefOverrideContainer>
_refOverrideContainerForySchema = GeneratedStructSchema<RefOverrideContainer>(
  type: RefOverrideContainer,
  serializerFactory: _RefOverrideContainerForySerializer.new,
  evolving: true,
  needsRootRef: true,
  usesNestedTypeDefinitions: true,
  fields: _refOverrideContainerForyFieldInfo,
);

final class _RefOverrideContainerForySerializer
    extends Serializer<RefOverrideContainer>
    implements GeneratedStructSerializer<RefOverrideContainer> {
  List<GeneratedStructFieldInfo>? _generatedFields;

  _RefOverrideContainerForySerializer();

  List<GeneratedStructFieldInfo> _writeFields(WriteContext context) {
    return _generatedFields ??= buildGeneratedStructFieldInfos(
      context.typeResolver,
      _refOverrideContainerForySchema,
    );
  }

  List<GeneratedStructFieldInfo> _readFields(ReadContext context) {
    return _generatedFields ??= buildGeneratedStructFieldInfos(
      context.typeResolver,
      _refOverrideContainerForySchema,
    );
  }

  @override
  void write(WriteContext context, RefOverrideContainer value) {
    final fields = _writeFields(context);
    writeGeneratedStructFieldInfoValue(context, fields[0], value.listField);
    writeGeneratedStructFieldInfoValue(context, fields[1], value.mapField);
    writeGeneratedStructFieldInfoValue(context, fields[2], value.setField);
  }

  @override
  RefOverrideContainer read(ReadContext context) {
    final value = RefOverrideContainer();
    final fields = _readFields(context);
    value.listField = _readRefOverrideContainerListField(
      readGeneratedStructFieldInfoValue(context, fields[0], value.listField),
      value.listField,
    );
    value.mapField = _readRefOverrideContainerMapField(
      readGeneratedStructFieldInfoValue(context, fields[1], value.mapField),
      value.mapField,
    );
    value.setField = _readRefOverrideContainerSetField(
      readGeneratedStructFieldInfoValue(context, fields[2], value.setField),
      value.setField,
    );
    return value;
  }

  @override
  RefOverrideContainer readCompatibleStruct(
    ReadContext context,
    CompatibleStructReadLayout layout,
  ) {
    final value = RefOverrideContainer();
    for (var index = 0; index < layout.fieldCount; index += 1) {
      final field = layout.localFieldAt(index);
      if (field == null) {
        skipGeneratedCompatibleStructField(context, layout, index);
        continue;
      }
      switch (field.index) {
        case 0:
          value.listField = _readRefOverrideContainerListField(
            readGeneratedCompatibleStructField(context, layout, index),
            value.listField,
          );
          break;
        case 1:
          value.mapField = _readRefOverrideContainerMapField(
            readGeneratedCompatibleStructField(context, layout, index),
            value.mapField,
          );
          break;
        case 2:
          value.setField = _readRefOverrideContainerSetField(
            readGeneratedCompatibleStructField(context, layout, index),
            value.setField,
          );
          break;
        default:
          throw StateError(
            'Compatible field index is out of range for RefOverrideContainer.',
          );
      }
    }
    return value;
  }
}

List<RefOverrideElement> _readRefOverrideContainerListField(
  Object? value, [
  Object? fallback,
]) {
  return value == null
      ? (fallback != null
          ? fallback as List<RefOverrideElement>
          : (throw StateError(
            'Received null for non-nullable field listField.',
          )))
      : List<RefOverrideElement>.of(
        (value as List).map(
          (item) =>
              item == null
                  ? (throw StateError(
                    'Received null for non-nullable list item.',
                  ))
                  : item as RefOverrideElement,
        ),
      );
}

Set<RefOverrideElement> _readRefOverrideContainerSetField(
  Object? value, [
  Object? fallback,
]) {
  return value == null
      ? (fallback != null
          ? fallback as Set<RefOverrideElement>
          : (throw StateError(
            'Received null for non-nullable field setField.',
          )))
      : Set<RefOverrideElement>.of(
        (value as Set).map(
          (item) =>
              item == null
                  ? (throw StateError(
                    'Received null for non-nullable set item.',
                  ))
                  : item as RefOverrideElement,
        ),
      );
}

Map<String, RefOverrideElement> _readRefOverrideContainerMapField(
  Object? value, [
  Object? fallback,
]) {
  return value == null
      ? (fallback != null
          ? fallback as Map<String, RefOverrideElement>
          : (throw StateError(
            'Received null for non-nullable field mapField.',
          )))
      : Map<String, RefOverrideElement>.of(
        (value as Map).map(
          (key, mappedValue) => MapEntry(
            key == null
                ? (throw StateError('Received null for non-nullable map key.'))
                : key as String,
            mappedValue == null
                ? (throw StateError(
                  'Received null for non-nullable map value.',
                ))
                : mappedValue as RefOverrideElement,
          ),
        ),
      );
}

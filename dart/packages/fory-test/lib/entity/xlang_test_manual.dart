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
import 'package:fory/src/serializer/serializer_support.dart';
import 'package:fory/src/resolver/type_resolver.dart' as resolver;

import 'xlang_test_models.dart';

bool registerXlangManualType(
  Fory fory,
  Type type, {
  int? id,
  String? namespace,
  String? typeName,
}) {
  if (type == MyExt) {
    fory.registerSerializer(
      MyExt,
      const _MyExtSerializer(),
      id: id,
      namespace: namespace,
      typeName: typeName,
    );
    return true;
  }
  if (type == Union2) {
    fory.registerSerializer(
      Union2,
      const _Union2Serializer(),
      id: id,
      namespace: namespace,
      typeName: typeName,
    );
    return true;
  }
  if (type == RefOverrideContainer) {
    registerGeneratedStruct(
      fory,
      _refOverrideContainerForyRegistration,
      id: id,
      namespace: namespace,
      typeName: typeName,
    );
    return true;
  }
  return false;
}

final class _Union2Serializer extends UnionSerializer<Union2> {
  const _Union2Serializer();

  @override
  void write(WriteContext context, Union2 value) {
    final buffer = context.buffer;
    buffer.writeVarUint32(value.index);
    context.writeRef(value.value);
  }

  @override
  Union2 read(ReadContext context) {
    final buffer = context.buffer;
    final index = buffer.readVarUint32();
    final value = context.readRef();
    if (index == 0 && value is String) {
      return Union2.ofString(value);
    }
    if (index == 1 && value is int) {
      return Union2.ofInt64(value);
    }
    throw StateError('Unsupported Union2 case $index with value $value.');
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
  typeId: resolver.TypeIds.compatibleStruct,
  nullable: true,
  ref: true,
  dynamic: null,
  arguments: <GeneratedFieldType>[],
);

const List<GeneratedFieldInfo> _refOverrideContainerForyFieldInfo =
    <GeneratedFieldInfo>[
  GeneratedFieldInfo(
    name: 'listField',
    identifier: 'list_field',
    id: null,
    fieldType: GeneratedFieldType(
      type: List,
      typeId: resolver.TypeIds.list,
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
      typeId: resolver.TypeIds.map,
      nullable: false,
      ref: false,
      dynamic: null,
      arguments: <GeneratedFieldType>[
        GeneratedFieldType(
          type: String,
          typeId: resolver.TypeIds.string,
          nullable: true,
          ref: false,
          dynamic: null,
          arguments: <GeneratedFieldType>[],
        ),
        _refOverrideElementFieldType,
      ],
    ),
  ),
];

final GeneratedStructRegistration<RefOverrideContainer>
    _refOverrideContainerForyRegistration =
    GeneratedStructRegistration<RefOverrideContainer>(
  fieldWritersBySlot: <GeneratedStructFieldInfoWriter<RefOverrideContainer>>[
    _writeRefOverrideContainerField0,
    _writeRefOverrideContainerField1,
  ],
  type: RefOverrideContainer,
  serializerFactory: _RefOverrideContainerForySerializer.new,
  evolving: true,
  fields: _refOverrideContainerForyFieldInfo,
);

void _writeRefOverrideContainerField0(
  WriteContext context,
  GeneratedStructFieldInfo field,
  RefOverrideContainer value,
) {
  writeGeneratedStructFieldInfoValue(context, field, value.listField);
}

void _writeRefOverrideContainerField1(
  WriteContext context,
  GeneratedStructFieldInfo field,
  RefOverrideContainer value,
) {
  writeGeneratedStructFieldInfoValue(context, field, value.mapField);
}

final class _RefOverrideContainerForySerializer
    extends Serializer<RefOverrideContainer> {
  List<GeneratedStructFieldInfo>? _generatedFields;

  _RefOverrideContainerForySerializer();

  List<GeneratedStructFieldInfo> _writeFields(WriteContext context) {
    return _generatedFields ??= buildGeneratedStructFieldInfos(
      context.typeResolver,
      _refOverrideContainerForyRegistration,
    );
  }

  List<GeneratedStructFieldInfo> _readFields(ReadContext context) {
    return _generatedFields ??= buildGeneratedStructFieldInfos(
      context.typeResolver,
      _refOverrideContainerForyRegistration,
    );
  }

  @override
  void write(WriteContext context, RefOverrideContainer value) {
    final slots = generatedStructWriteSlots(context);
    if (slots == null) {
      final fields = _writeFields(context);
      writeGeneratedStructFieldInfoValue(context, fields[0], value.listField);
      writeGeneratedStructFieldInfoValue(context, fields[1], value.mapField);
      return;
    }
    final writers = _refOverrideContainerForyRegistration.fieldWritersBySlot;
    for (final field in slots.orderedFields) {
      writers[field.slot](context, field, value);
    }
  }

  @override
  RefOverrideContainer read(ReadContext context) {
    final slots = generatedStructReadSlots(context);
    final value = RefOverrideContainer();
    context.reference(value);
    if (slots == null) {
      final fields = _readFields(context);
      value.listField = _readRefOverrideContainerListField(
        readGeneratedStructFieldInfoValue(context, fields[0], value.listField),
        value.listField,
      );
      value.mapField = _readRefOverrideContainerMapField(
        readGeneratedStructFieldInfoValue(context, fields[1], value.mapField),
        value.mapField,
      );
      return value;
    }
    if (slots.containsSlot(0)) {
      final rawRefOverrideContainer0 = slots.valueForSlot(0);
      value.listField = _readRefOverrideContainerListField(
        rawRefOverrideContainer0 is DeferredReadRef
            ? context.getReadRef(rawRefOverrideContainer0.id)
            : rawRefOverrideContainer0,
        value.listField,
      );
    }
    if (slots.containsSlot(1)) {
      final rawRefOverrideContainer1 = slots.valueForSlot(1);
      value.mapField = _readRefOverrideContainerMapField(
        rawRefOverrideContainer1 is DeferredReadRef
            ? context.getReadRef(rawRefOverrideContainer1.id)
            : rawRefOverrideContainer1,
        value.mapField,
      );
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
            (item) => item == null
                ? (throw StateError(
                    'Received null for non-nullable list item.'))
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
                  ? (throw StateError(
                      'Received null for non-nullable map key.'))
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

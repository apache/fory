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

import 'package:fory/src/context/read_context.dart';
import 'package:fory/src/context/ref_writer.dart';
import 'package:fory/src/context/write_context.dart';
import 'package:fory/src/meta/field_info.dart';
import 'package:fory/src/meta/field_type.dart';
import 'package:fory/src/meta/type_ids.dart';
import 'package:fory/src/resolver/type_resolver.dart';
import 'package:fory/src/serializer/collection_flags.dart';
import 'package:fory/src/serializer/primitive_serializers.dart';
import 'package:fory/src/serializer/scalar_serializers.dart';
import 'package:fory/src/serializer/serializer.dart';
import 'package:fory/src/serializer/serialization_field_info.dart';
import 'package:fory/src/serializer/serializer_support.dart';
import 'package:fory/src/types/bfloat16.dart';
import 'package:fory/src/types/bool_list.dart';
import 'package:fory/src/types/float16.dart';
import 'package:fory/src/types/int64.dart';
import 'package:fory/src/types/uint64.dart';

const int _referenceBytes = 4;
const int _ownerBytes = 1;

@pragma('vm:prefer-inline')
void _writeDirectTypeInfoValue(
  WriteContext context,
  TypeInfo typeInfo,
  FieldType? fieldType,
  Object value,
) {
  if (TypeIds.isPrimitive(typeInfo.typeId)) {
    PrimitiveSerializer.writePayload(context, typeInfo.typeId, value);
    return;
  }
  if (typeInfo.typeId == TypeIds.string) {
    StringSerializer.writePayload(context, value as String);
    return;
  }
  if (typeInfo.kind == RegistrationKind.struct) {
    typeInfo.structSerializer!.writeValue(context, typeInfo, value);
    return;
  }
  if (typeInfo.typeId == TypeIds.list ||
      typeInfo.typeId == TypeIds.set ||
      typeInfo.typeId == TypeIds.map) {
    context.writeResolvedValue(typeInfo, value, fieldType);
    return;
  }
  typeInfo.serializer.write(context, value);
}

@pragma('vm:prefer-inline')
Object? readTypeInfoValue(
  ReadContext context,
  TypeInfo typeInfo,
  FieldType? fieldType, {
  bool hasPreservedRef = false,
}) {
  if (TypeIds.isPrimitive(typeInfo.typeId)) {
    return convertResolvedPrimitiveValue(
      PrimitiveSerializer.readPayload(context, typeInfo.typeId),
      typeInfo,
      fieldType,
    );
  }
  if (typeInfo.typeId == TypeIds.string) {
    return StringSerializer.readPayload(context);
  }
  if (typeInfo.kind == RegistrationKind.struct) {
    return typeInfo.structSerializer!.readValue(
      context,
      typeInfo,
      hasCurrentPreservedRef: hasPreservedRef,
    );
  }
  if (typeInfo.typeId == TypeIds.list ||
      typeInfo.typeId == TypeIds.set ||
      typeInfo.typeId == TypeIds.map) {
    return context.readResolvedValue(
      typeInfo,
      fieldType,
      hasPreservedRef: hasPreservedRef,
    );
  }
  return context.readSerializerPayload(
    typeInfo.serializer,
    typeInfo,
    hasCurrentPreservedRef: hasPreservedRef,
  );
}

bool tracksNestedPayloadDepth(TypeInfo typeInfo) {
  if (TypeIds.isContainer(typeInfo.typeId)) {
    return false;
  }
  switch (typeInfo.kind) {
    case RegistrationKind.builtin:
    case RegistrationKind.enumType:
      return false;
    case RegistrationKind.struct:
    case RegistrationKind.ext:
    case RegistrationKind.union:
      return true;
  }
}

bool sameTypeInfo(TypeInfo left, TypeInfo right) {
  if (left.kind != right.kind || left.typeId != right.typeId) {
    return false;
  }
  if (left.userTypeId != null || right.userTypeId != null) {
    return left.userTypeId == right.userTypeId;
  }
  if (left.namespace != null || right.namespace != null) {
    return left.namespace == right.namespace && left.typeName == right.typeName;
  }
  return true;
}

void writeFieldTypeValue(
  WriteContext context,
  FieldType fieldType,
  TypeInfo? declaredTypeInfo,
  bool usesDeclaredType,
  Object? value,
) {
  if (fieldType.isDynamic) {
    if (fieldType.ref) {
      context.writeRef(value);
      return;
    }
    if (context.writeNullFlag(value)) {
      return;
    }
    context.buffer.writeByte(RefWriter.notNullValueFlag);
    context.writeNonRef(value as Object);
    return;
  }
  if (fieldType.isPrimitive && !fieldType.nullable) {
    if (value == null) {
      throw StateError('Expected non-null field value.');
    }
    context.writePrimitiveValue(fieldType.typeId, value);
    return;
  }
  if (!usesDeclaredType) {
    if (fieldType.ref) {
      context.writeRef(value);
      return;
    }
    if (fieldType.nullable) {
      if (context.writeNullFlag(value)) {
        return;
      }
      context.buffer.writeByte(RefWriter.notNullValueFlag);
    } else if (value == null) {
      throw StateError('Expected non-null field value.');
    }
    context.writeNonRef(value as Object);
    return;
  }
  final resolved = declaredTypeInfo!;
  if (fieldType.nullable || fieldType.ref) {
    final handled = context.refWriter.writeRefOrNull(
      context.buffer,
      value,
      trackRef: fieldType.ref && resolved.supportsRef,
    );
    if (handled) {
      return;
    }
  }
  if (value == null) {
    throw StateError('Expected non-null field value.');
  }
  context.writeResolvedValue(resolved, value, fieldType);
}

T readFieldTypeValue<T>(
  ReadContext context,
  FieldType fieldType,
  TypeInfo? declaredTypeInfo,
  bool usesDeclaredType, [
  T? fallback,
]) {
  if (fieldType.isDynamic) {
    return context.readRef() as T;
  }
  if (fieldType.isPrimitive && !fieldType.nullable) {
    return convertPrimitiveFieldValue(
          context.readPrimitiveValue(fieldType.typeId),
          fieldType,
        )
        as T;
  }
  if (!usesDeclaredType) {
    if (fieldType.ref) {
      return context.readRef() as T;
    }
    if (fieldType.nullable) {
      return context.readNullable() as T;
    }
    return context.readNonRef() as T;
  }
  final resolved = declaredTypeInfo!;
  if (fieldType.nullable || fieldType.ref) {
    final flag = context.refReader.tryPreserveRefId(context.buffer);
    final preservedRefId = flag >= RefWriter.refValueFlag ? flag : null;
    if (flag == RefWriter.nullFlag) {
      return fallback as T;
    }
    if (flag == RefWriter.refFlag) {
      return context.refReader.getReadRef() as T;
    }
    final value = context.readResolvedValue(
      resolved,
      fieldType,
      hasPreservedRef: preservedRefId != null,
    );
    if (preservedRefId != null &&
        resolved.supportsRef &&
        context.refReader.readRefAt(preservedRefId) == null) {
      context.refReader.setReadRef(preservedRefId, value);
    }
    return value as T;
  }
  return context.readResolvedValue(resolved, fieldType) as T;
}

final class ListSerializer extends Serializer<List> {
  const ListSerializer();

  @override
  void write(WriteContext context, List value) {
    writePayload(context, value, null, trackRef: context.rootTrackRef);
  }

  @override
  List read(ReadContext context) {
    return readPayload(context, null);
  }

  static void writePayload(
    WriteContext context,
    Iterable values,
    FieldType? elementFieldType, {
    required bool trackRef,
  }) {
    final size = values.length;
    context.buffer.writeVarUint32(size);
    if (size == 0) {
      return;
    }
    final declaredTypeInfo =
        elementFieldType == null ||
                elementFieldType.isDynamic ||
                elementFieldType.typeId == TypeIds.unknown
            ? null
            : context.typeResolver.resolveFieldType(elementFieldType);
    final usesDeclaredType =
        declaredTypeInfo != null &&
        usesDeclaredTypeInfo(
          context.config.compatible,
          elementFieldType!,
          declaredTypeInfo,
        );
    final analysis = _analyzeElementsHeader(
      context,
      values,
      usesDeclaredType: usesDeclaredType,
    );
    final elementTrackRef =
        (elementFieldType?.ref ?? false) ||
        (elementFieldType == null && trackRef);
    final header = _buildCollectionHeader(
      trackRef: elementTrackRef,
      hasNull: analysis.hasNull,
      usesDeclaredType: usesDeclaredType,
      sameType: analysis.sameType,
    );
    context.buffer.writeUint8(header);
    final sameTypeInfo =
        !usesDeclaredType && analysis.sameType ? analysis.sameTypeInfo : null;
    if (!usesDeclaredType &&
        sameTypeInfo != null &&
        analysis.firstNonNull != null) {
      context.writeTypeMetaValue(sameTypeInfo);
    }
    if (declaredTypeInfo != null) {
      _writeSameTypeElements(
        context,
        values,
        declaredTypeInfo,
        elementFieldType,
        elementTrackRef,
        analysis.hasNull,
      );
      return;
    }
    if (sameTypeInfo != null) {
      _writeSameTypeElements(
        context,
        values,
        sameTypeInfo,
        usesDeclaredType ? elementFieldType : null,
        elementTrackRef,
        analysis.hasNull,
      );
      return;
    }
    _writeDifferentTypeElements(context, values, analysis, elementTrackRef);
  }

  static List<Object?> readPayload(
    ReadContext context,
    FieldType? elementFieldType, {
    bool hasPreservedRef = false,
  }) {
    final state = _prepareListRead(context, elementFieldType);
    context.buffer.checkReadableBytes(state.size);
    final result = List<Object?>.filled(state.size, null, growable: false);
    if (hasPreservedRef) {
      context.reference(result);
    }
    if (state.size == 0) {
      return result;
    }
    if (state.tracksDepth) {
      context.increaseDepth();
    }
    for (var index = 0; index < state.size; index += 1) {
      result[index] = _readPreparedListItem(context, state);
    }
    if (state.tracksDepth) {
      context.decreaseDepth();
    }
    return result;
  }
}

final class SetSerializer extends Serializer<Set> {
  const SetSerializer();

  @override
  void write(WriteContext context, Set value) {
    ListSerializer.writePayload(
      context,
      value,
      null,
      trackRef: context.rootTrackRef,
    );
  }

  @override
  Set read(ReadContext context) {
    return readPayload(context, null);
  }

  static Set<Object?> readPayload(
    ReadContext context,
    FieldType? elementFieldType, {
    bool hasPreservedRef = false,
  }) {
    final values = ListSerializer.readPayload(
      context,
      elementFieldType,
      hasPreservedRef: hasPreservedRef,
    );
    context.reserveGraphMemory(_ownerBytes + values.length * _referenceBytes);
    return Set<Object?>.of(values);
  }
}

const ListSerializer listSerializer = ListSerializer();
const SetSerializer setSerializer = SetSerializer();

@pragma('vm:never-inline')
Object? readCompatibleMatchedCollectionArrayField(
  ReadContext context,
  SerializationFieldInfo localField,
  FieldInfo remoteField,
) {
  final localType = localField.fieldType;
  final remoteType = remoteField.fieldType;
  if (isCompatibleArrayType(localType.typeId) &&
      remoteType.typeId == TypeIds.list) {
    final elementType =
        remoteType.arguments.isEmpty ? null : remoteType.arguments.single;
    if (elementType == null ||
        _arrayElementTypeId(localType.typeId) !=
            _compatibleArrayElementTypeId(elementType.typeId)) {
      throw StateError(
        'Compatible list-to-array field ${localField.name} is unsupported.',
      );
    }
    return _readCompatibleListAsArrayField(
      context,
      elementType,
      localType.typeId,
      localField.name,
    );
  }
  if (localType.typeId == TypeIds.list &&
      isCompatibleArrayType(remoteType.typeId)) {
    final localElementType =
        localType.arguments.isEmpty ? null : localType.arguments.single;
    if (localElementType == null ||
        _arrayElementTypeId(remoteType.typeId) !=
            _compatibleArrayElementTypeId(localElementType.typeId)) {
      throw StateError(
        'Compatible array-to-list field ${localField.name} is unsupported.',
      );
    }
    final raw = readCompatibleField(context, remoteField);
    return _arrayToListValue(context, raw);
  }
  return readFieldValue<Object?>(context, localField);
}

bool isCompatibleArrayType(int typeId) => _arrayElementTypeId(typeId) != null;

bool isCompatibleCollectionArrayFieldPair(
  FieldInfo localField,
  FieldInfo remoteField,
) {
  return isCompatibleCollectionArrayTypePair(
    localField.fieldType,
    remoteField.fieldType,
  );
}

bool isCompatibleCollectionArrayTypePair(
  FieldType localType,
  FieldType remoteType,
) {
  if (localType.nullable ||
      remoteType.nullable ||
      localType.ref ||
      remoteType.ref) {
    return false;
  }
  if (isCompatibleArrayType(localType.typeId) &&
      remoteType.typeId == TypeIds.list) {
    return _listElementMatchesArray(
      remoteType,
      localType.typeId,
      requireUnframedElement: true,
    );
  }
  if (localType.typeId == TypeIds.list &&
      isCompatibleArrayType(remoteType.typeId)) {
    return _listElementMatchesArray(
      localType,
      remoteType.typeId,
      requireUnframedElement: false,
    );
  }
  return false;
}

bool isCompatibleCollectionArrayRootTypePair(
  FieldType localType,
  FieldType remoteType,
) {
  final localTypeId = localType.typeId;
  final remoteTypeId = remoteType.typeId;
  return (localTypeId == TypeIds.list && isCompatibleArrayType(remoteTypeId)) ||
      (isCompatibleArrayType(localTypeId) && remoteTypeId == TypeIds.list);
}

bool _listElementMatchesArray(
  FieldType listType,
  int arrayTypeId, {
  required bool requireUnframedElement,
}) {
  final elementType =
      listType.arguments.isEmpty ? null : listType.arguments.single;
  // Nullable element schema is allowed for list<T?> -> array<T>; actual
  // null payload elements fail in the dense-array reader. Ref-tracked
  // element framing is rejected here because this path stays primitive-only.
  return elementType != null &&
      (!requireUnframedElement || !elementType.ref) &&
      _arrayElementTypeId(arrayTypeId) ==
          _compatibleArrayElementTypeId(elementType.typeId);
}

Object _readCompatibleListAsArrayField(
  ReadContext context,
  FieldType elementType,
  int arrayTypeId,
  String fieldName,
) {
  final size = context.buffer.readVarUint32();
  context.reserveGraphMemory(
    _ownerBytes + size * _arrayElementBytes(arrayTypeId),
  );
  if (size == 0) {
    return _newArrayValue(arrayTypeId, 0);
  }
  final header = context.buffer.readUint8();
  final trackRef = (header & CollectionFlags.trackingRef) != 0;
  final hasNull = (header & CollectionFlags.hasNull) != 0;
  final usesDeclaredType =
      (header & CollectionFlags.isDeclaredElementType) != 0;
  final sameType = (header & CollectionFlags.isSameType) != 0;
  if (hasNull || trackRef) {
    throw StateError(
      'Compatible list-to-array field $fieldName cannot read nullable or ref-tracked elements.',
    );
  }
  if (!sameType || !usesDeclaredType) {
    throw StateError(
      'Compatible list-to-array field $fieldName requires declared same-type elements.',
    );
  }
  final elementResolved = context.typeResolver.resolveFieldType(elementType);
  context.buffer.checkReadableBytes(size);
  final result = _newArrayValue(arrayTypeId, size);
  for (var index = 0; index < size; index += 1) {
    _setArrayValue(
      result,
      arrayTypeId,
      index,
      context.readResolvedValue(elementResolved, elementType),
    );
  }
  return result;
}

int? _arrayElementTypeId(int typeId) {
  return switch (typeId) {
    TypeIds.boolArray => TypeIds.boolType,
    TypeIds.int8Array => TypeIds.int8,
    TypeIds.int16Array => TypeIds.int16,
    TypeIds.int32Array => TypeIds.int32,
    TypeIds.int64Array => TypeIds.int64,
    TypeIds.uint8Array => TypeIds.uint8,
    TypeIds.uint16Array => TypeIds.uint16,
    TypeIds.uint32Array => TypeIds.uint32,
    TypeIds.uint64Array => TypeIds.uint64,
    TypeIds.float16Array => TypeIds.float16,
    TypeIds.bfloat16Array => TypeIds.bfloat16,
    TypeIds.float32Array => TypeIds.float32,
    TypeIds.float64Array => TypeIds.float64,
    _ => null,
  };
}

int _compatibleArrayElementTypeId(int typeId) {
  return switch (typeId) {
    TypeIds.varInt32 => TypeIds.int32,
    TypeIds.varInt64 || TypeIds.taggedInt64 => TypeIds.int64,
    TypeIds.varUint32 => TypeIds.uint32,
    TypeIds.varUint64 || TypeIds.taggedUint64 => TypeIds.uint64,
    _ => typeId,
  };
}

int _arrayElementBytes(int arrayTypeId) {
  return switch (arrayTypeId) {
    TypeIds.boolArray || TypeIds.int8Array || TypeIds.uint8Array => 1,
    TypeIds.int16Array ||
    TypeIds.uint16Array ||
    TypeIds.float16Array ||
    TypeIds.bfloat16Array => 2,
    TypeIds.int32Array || TypeIds.uint32Array || TypeIds.float32Array => 4,
    TypeIds.int64Array || TypeIds.uint64Array || TypeIds.float64Array => 8,
    _ =>
      throw StateError('Unsupported compatible array field type $arrayTypeId.'),
  };
}

Object _newArrayValue(int arrayTypeId, int length) {
  return switch (arrayTypeId) {
    TypeIds.boolArray => BoolList(length),
    TypeIds.int8Array => Int8List(length),
    TypeIds.int16Array => Int16List(length),
    TypeIds.int32Array => Int32List(length),
    TypeIds.int64Array => Int64List(length),
    TypeIds.uint8Array => Uint8List(length),
    TypeIds.uint16Array => Uint16List(length),
    TypeIds.uint32Array => Uint32List(length),
    TypeIds.uint64Array => Uint64List(length),
    TypeIds.float16Array => Float16List(length),
    TypeIds.bfloat16Array => Bfloat16List(length),
    TypeIds.float32Array => Float32List(length),
    TypeIds.float64Array => Float64List(length),
    _ =>
      throw StateError('Unsupported compatible array field type $arrayTypeId.'),
  };
}

void _setArrayValue(Object target, int arrayTypeId, int index, Object? value) {
  switch (arrayTypeId) {
    case TypeIds.boolArray:
      (target as BoolList)[index] = value as bool;
    case TypeIds.int8Array:
      (target as Int8List)[index] = value as int;
    case TypeIds.int16Array:
      (target as Int16List)[index] = value as int;
    case TypeIds.int32Array:
      (target as Int32List)[index] = value as int;
    case TypeIds.int64Array:
      (target as Int64List)[index] =
          value is int ? Int64(value) : value as Int64;
    case TypeIds.uint8Array:
      (target as Uint8List)[index] = value as int;
    case TypeIds.uint16Array:
      (target as Uint16List)[index] = value as int;
    case TypeIds.uint32Array:
      (target as Uint32List)[index] = value as int;
    case TypeIds.uint64Array:
      (target as Uint64List)[index] =
          value is int ? Uint64(value) : value as Uint64;
    case TypeIds.float16Array:
      (target as Float16List)[index] = value as double;
    case TypeIds.bfloat16Array:
      (target as Bfloat16List)[index] = value as double;
    case TypeIds.float32Array:
      (target as Float32List)[index] = (value as num).toDouble();
    case TypeIds.float64Array:
      (target as Float64List)[index] = (value as num).toDouble();
    default:
      throw StateError('Unsupported compatible array field type $arrayTypeId.');
  }
}

Object _arrayToListValue(ReadContext context, Object? raw) {
  if (raw is BoolList) {
    context.reserveGraphMemory(_ownerBytes + raw.length * _referenceBytes);
    return raw.toList();
  }
  if (raw is Iterable) {
    context.reserveGraphMemory(_ownerBytes + raw.length * _referenceBytes);
    return raw.toList();
  }
  throw StateError('Expected compatible array payload.');
}

@pragma('vm:prefer-inline')
List<T> readTypedListPayload<T>(
  ReadContext context,
  FieldType? elementFieldType,
  T Function(Object? value) convert, {
  bool reserveOwner = true,
}) {
  final state = _prepareListRead(
    context,
    elementFieldType,
    reserveOwner: reserveOwner,
  );
  if (state.size == 0) {
    return List<T>.empty(growable: false);
  }
  if (state.tracksDepth) {
    context.increaseDepth();
  }
  final directTypeInfo = state.declaredTypeInfo ?? state.sameTypeInfo;
  if (directTypeInfo != null && !state.trackRef && !state.hasNull) {
    final directFieldType =
        state.declaredTypeInfo != null ? state.elementFieldType : null;
    if (directTypeInfo.type == T &&
        directTypeInfo.kind == RegistrationKind.struct) {
      final structSerializer = directTypeInfo.structSerializer!;
      context.buffer.checkReadableBytes(state.size);
      final result =
          directTypeInfo.remoteTypeDef == null
              ? List<T>.generate(
                state.size,
                (_) => structSerializer.readValue(context, directTypeInfo) as T,
                growable: false,
              )
              : List<T>.generate(
                state.size,
                (_) =>
                    structSerializer.readGeneratedCompatibleValue(
                          context,
                          directTypeInfo,
                        )
                        as T,
                growable: false,
              );
      if (state.tracksDepth) {
        context.decreaseDepth();
      }
      return result;
    }
    if (directTypeInfo.type == T && directTypeInfo.typeId == TypeIds.string) {
      context.buffer.checkReadableBytes(state.size);
      final result = List<T>.generate(
        state.size,
        (_) => StringSerializer.readPayload(context) as T,
        growable: false,
      );
      if (state.tracksDepth) {
        context.decreaseDepth();
      }
      return result;
    }
    context.buffer.checkReadableBytes(state.size);
    final result = List<T>.generate(
      state.size,
      (_) =>
          convert(readTypeInfoValue(context, directTypeInfo, directFieldType)),
      growable: false,
    );
    if (state.tracksDepth) {
      context.decreaseDepth();
    }
    return result;
  }
  context.buffer.checkReadableBytes(state.size);
  final result = List<T>.generate(
    state.size,
    (_) => convert(_readPreparedListItem(context, state)),
    growable: false,
  );
  if (state.tracksDepth) {
    context.decreaseDepth();
  }
  return result;
}

Set<T> readTypedSetPayload<T>(
  ReadContext context,
  FieldType? elementFieldType,
  T Function(Object? value) convert,
) {
  final values = readTypedListPayload(
    context,
    elementFieldType,
    convert,
    reserveOwner: false,
  );
  context.reserveGraphMemory(_ownerBytes + values.length * _referenceBytes);
  return Set<T>.of(values);
}

void writeTypedListPayload<T>(
  WriteContext context,
  List<T> values,
  FieldType elementFieldType,
) {
  final size = values.length;
  context.buffer.writeVarUint32(size);
  if (size == 0) return;
  final declaredTypeInfo = context.typeResolver.resolveFieldType(
    elementFieldType,
  );
  final usesDeclaredType = usesDeclaredTypeInfo(
    context.config.compatible,
    elementFieldType,
    declaredTypeInfo,
  );
  context.buffer.writeUint8(
    _buildCollectionHeader(
      trackRef: elementFieldType.ref,
      hasNull: false,
      usesDeclaredType: usesDeclaredType,
      sameType: true,
    ),
  );
  if (!usesDeclaredType) {
    context.writeTypeMetaValue(declaredTypeInfo);
  }
  _writeSameTypeElements(
    context,
    values,
    declaredTypeInfo,
    usesDeclaredType ? elementFieldType : null,
    elementFieldType.ref,
    false,
  );
}

void writeTypedSetPayload<T>(
  WriteContext context,
  Set<T> values,
  FieldType elementFieldType,
) {
  writeTypedListPayload<T>(
    context,
    values.toList(growable: false),
    elementFieldType,
  );
}

int _buildCollectionHeader({
  required bool trackRef,
  required bool hasNull,
  required bool usesDeclaredType,
  required bool sameType,
}) {
  var header = 0;
  if (trackRef) {
    header |= CollectionFlags.trackingRef;
  }
  if (hasNull) {
    header |= CollectionFlags.hasNull;
  }
  if (usesDeclaredType) {
    header |= CollectionFlags.isDeclaredElementType;
  }
  if (sameType) {
    header |= CollectionFlags.isSameType;
  }
  return header;
}

void _writeSameTypeElements(
  WriteContext context,
  Iterable values,
  TypeInfo typeInfo,
  FieldType? fieldType,
  bool trackRef,
  bool hasNull,
) {
  final tracksDepth = tracksNestedPayloadDepth(typeInfo);
  if (tracksDepth) {
    context.increaseDepth();
  }
  for (final value in values) {
    if (value == null) {
      context.buffer.writeByte(RefWriter.nullFlag);
    } else if (trackRef) {
      writeTypeInfoValue(
        context,
        typeInfo,
        fieldType,
        value as Object,
        trackRef: true,
      );
    } else if (hasNull) {
      context.buffer.writeByte(RefWriter.notNullValueFlag);
      _writeDirectTypeInfoValue(context, typeInfo, fieldType, value as Object);
    } else {
      _writeDirectTypeInfoValue(context, typeInfo, fieldType, value as Object);
    }
  }
  if (tracksDepth) {
    context.decreaseDepth();
  }
}

void _writeDifferentTypeElements(
  WriteContext context,
  Iterable values,
  _ElementsHeaderAnalysis analysis,
  bool trackRef,
) {
  for (final value in values) {
    if (analysis.sameType && analysis.sameTypeInfo != null) {
      if (value == null) {
        context.buffer.writeByte(RefWriter.nullFlag);
      } else if (trackRef) {
        final handled = context.refWriter.writeRefOrNull(
          context.buffer,
          value,
          trackRef: analysis.sameTypeInfo!.supportsRef,
        );
        if (!handled) {
          context.writeResolvedValue(
            analysis.sameTypeInfo!,
            value as Object,
            null,
          );
        }
      } else if (analysis.hasNull) {
        context.buffer.writeByte(RefWriter.notNullValueFlag);
        context.writeResolvedValue(
          analysis.sameTypeInfo!,
          value as Object,
          null,
        );
      } else {
        context.writeResolvedValue(
          analysis.sameTypeInfo!,
          value as Object,
          null,
        );
      }
      continue;
    }
    if (trackRef) {
      context.writeRef(value);
    } else if (analysis.hasNull) {
      if (value == null) {
        context.buffer.writeByte(RefWriter.nullFlag);
      } else {
        context.buffer.writeByte(RefWriter.notNullValueFlag);
        context.writeNonRef(value as Object);
      }
    } else {
      context.writeNonRef(value as Object);
    }
  }
}

final class _PreparedListRead {
  final int size;
  final bool trackRef;
  final bool hasNull;
  final bool usesDeclaredType;
  final FieldType? elementFieldType;
  final TypeInfo? declaredTypeInfo;
  final TypeInfo? sameTypeInfo;
  final bool tracksDepth;

  const _PreparedListRead({
    required this.size,
    required this.trackRef,
    required this.hasNull,
    required this.usesDeclaredType,
    required this.elementFieldType,
    required this.declaredTypeInfo,
    required this.sameTypeInfo,
    required this.tracksDepth,
  });
}

@pragma('vm:prefer-inline')
_PreparedListRead _prepareListRead(
  ReadContext context,
  FieldType? elementFieldType, {
  bool reserveOwner = true,
}) {
  final size = context.buffer.readVarUint32();
  if (reserveOwner) {
    context.reserveGraphMemory(_ownerBytes + size * _referenceBytes);
  }
  if (size == 0) {
    return _PreparedListRead(
      size: 0,
      trackRef: false,
      hasNull: false,
      usesDeclaredType: false,
      elementFieldType: elementFieldType,
      declaredTypeInfo: null,
      sameTypeInfo: null,
      tracksDepth: false,
    );
  }
  final header = context.buffer.readUint8();
  // IMPORTANT: collection readers must obey the ref/null bits written on the
  // wire, not local Dart field metadata that may imply a different ref policy.
  // Shared xlang tests intentionally deserialize one ref policy and then
  // serialize another local payload. DO NOT REMOVE this comment.
  final trackRef = (header & CollectionFlags.trackingRef) != 0;
  final hasNull = (header & CollectionFlags.hasNull) != 0;
  final usesDeclaredType =
      (header & CollectionFlags.isDeclaredElementType) != 0;
  final sameType = (header & CollectionFlags.isSameType) != 0;
  final needsExpectedElementType =
      elementFieldType != null &&
      (usesDeclaredType ||
          (sameType && TypeIds.isUserType(elementFieldType.typeId)));
  final expectedElementTypeInfo =
      needsExpectedElementType
          ? context.typeResolver.tryResolveFieldType(elementFieldType)
          : null;
  final declaredTypeInfo = usesDeclaredType ? expectedElementTypeInfo : null;
  final sameTypeInfo =
      (!usesDeclaredType && sameType)
          ? context.readTypeMetaValue(expectedElementTypeInfo)
          : null;
  final tracksDepth =
      (declaredTypeInfo != null &&
          tracksNestedPayloadDepth(declaredTypeInfo)) ||
      (sameTypeInfo != null && tracksNestedPayloadDepth(sameTypeInfo));
  return _PreparedListRead(
    size: size,
    trackRef: trackRef,
    hasNull: hasNull,
    usesDeclaredType: usesDeclaredType,
    elementFieldType: elementFieldType,
    declaredTypeInfo: declaredTypeInfo,
    sameTypeInfo: sameTypeInfo,
    tracksDepth: tracksDepth,
  );
}

@pragma('vm:prefer-inline')
Object? _readPreparedListItem(ReadContext context, _PreparedListRead state) {
  if (state.declaredTypeInfo != null) {
    return _readSameTypeElement(
      context,
      state.declaredTypeInfo!,
      state.elementFieldType,
      state.trackRef,
      state.hasNull,
    );
  }
  if (state.sameTypeInfo != null) {
    return _readSameTypeElement(
      context,
      state.sameTypeInfo!,
      null,
      state.trackRef,
      state.hasNull,
    );
  }
  if (state.usesDeclaredType && state.elementFieldType != null) {
    return readFieldTypeValue<Object?>(
      context,
      state.elementFieldType!,
      state.declaredTypeInfo,
      state.usesDeclaredType,
    );
  }
  if (state.sameTypeInfo != null) {
    if (state.hasNull || state.trackRef) {
      final flag = context.refReader.tryPreserveRefId(context.buffer);
      final preservedRefId = flag >= RefWriter.refValueFlag ? flag : null;
      if (flag == RefWriter.nullFlag) {
        return null;
      }
      if (flag == RefWriter.refFlag) {
        return context.refReader.getReadRef();
      }
      final value = context.readResolvedValue(
        state.sameTypeInfo!,
        null,
        hasPreservedRef: preservedRefId != null,
      );
      if (preservedRefId != null &&
          state.sameTypeInfo!.supportsRef &&
          context.refReader.readRefAt(preservedRefId) == null) {
        context.refReader.setReadRef(preservedRefId, value);
      }
      return value;
    }
    return context.readResolvedValue(state.sameTypeInfo!, null);
  }
  return _readDifferentTypeElement(context, state.trackRef, state.hasNull);
}

@pragma('vm:prefer-inline')
Object? _readSameTypeElement(
  ReadContext context,
  TypeInfo typeInfo,
  FieldType? fieldType,
  bool trackRef,
  bool hasNull,
) {
  if (hasNull || trackRef) {
    final flag = context.refReader.tryPreserveRefId(context.buffer);
    final preservedRefId = flag >= RefWriter.refValueFlag ? flag : null;
    if (flag == RefWriter.nullFlag) {
      return null;
    }
    if (flag == RefWriter.refFlag) {
      return context.refReader.getReadRef();
    }
    final value = readTypeInfoValue(
      context,
      typeInfo,
      fieldType,
      hasPreservedRef: preservedRefId != null,
    );
    if (preservedRefId != null &&
        typeInfo.supportsRef &&
        context.refReader.readRefAt(preservedRefId) == null) {
      context.refReader.setReadRef(preservedRefId, value);
    }
    return value;
  }
  return readTypeInfoValue(context, typeInfo, fieldType);
}

@pragma('vm:prefer-inline')
Object? _readDifferentTypeElement(
  ReadContext context,
  bool trackRef,
  bool hasNull,
) {
  if (trackRef) {
    return context.readRef();
  }
  if (hasNull) {
    return context.readNullable();
  }
  return context.readNonRef();
}

@pragma('vm:prefer-inline')
void writeTypeInfoValue(
  WriteContext context,
  TypeInfo typeInfo,
  FieldType? fieldType,
  Object value, {
  required bool trackRef,
}) {
  if (!trackRef) {
    _writeDirectTypeInfoValue(context, typeInfo, fieldType, value);
    return;
  }
  final handled = context.refWriter.writeRefOrNull(
    context.buffer,
    value,
    trackRef: typeInfo.supportsRef,
  );
  if (!handled) {
    _writeDirectTypeInfoValue(context, typeInfo, fieldType, value);
  }
}

_ElementsHeaderAnalysis _analyzeElementsHeader(
  WriteContext context,
  Iterable values, {
  required bool usesDeclaredType,
}) {
  var hasNull = false;
  if (usesDeclaredType) {
    for (final value in values) {
      if (value == null) {
        hasNull = true;
        break;
      }
    }
    return _ElementsHeaderAnalysis(
      hasNull: hasNull,
      sameType: true,
      sameTypeInfo: null,
      firstNonNull: null,
    );
  }
  Object? firstNonNull;
  TypeInfo? sameTypeInfo;
  Type? firstRuntimeType;
  var sameType = true;
  for (final value in values) {
    if (value == null) {
      hasNull = true;
      continue;
    }
    if (firstNonNull == null) {
      firstNonNull = value;
      firstRuntimeType = value.runtimeType;
      sameTypeInfo = context.typeResolver.resolveValue(value as Object);
      continue;
    }
    if (!sameType) {
      continue;
    }
    if (value.runtimeType != firstRuntimeType) {
      sameType = false;
    }
  }
  return _ElementsHeaderAnalysis(
    hasNull: hasNull,
    sameType: sameType,
    sameTypeInfo: sameTypeInfo,
    firstNonNull: firstNonNull,
  );
}

final class _ElementsHeaderAnalysis {
  final bool hasNull;
  final bool sameType;
  final TypeInfo? sameTypeInfo;
  final Object? firstNonNull;

  const _ElementsHeaderAnalysis({
    required this.hasNull,
    required this.sameType,
    required this.sameTypeInfo,
    required this.firstNonNull,
  });
}

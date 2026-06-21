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

import 'package:meta/meta.dart';

import 'package:fory/fory.dart';
export 'package:fory/src/memory/buffer.dart'
    show
        bufferByteData,
        bufferBytes,
        bufferReaderIndex,
        bufferReserveBytes,
        bufferSetReaderIndex,
        bufferSetWriterIndex;
export 'package:fory/src/serializer/generated_struct_serializer.dart';

import 'package:fory/src/codegen/generated_registry.dart';
import 'package:fory/src/context/ref_writer.dart';
import 'package:fory/src/meta/field_info.dart' as meta;
import 'package:fory/src/meta/field_type.dart' as meta_types;
import 'package:fory/src/resolver/type_resolver.dart' as resolver;
import 'package:fory/src/serializer/collection_serializers.dart';
import 'package:fory/src/serializer/map_serializers.dart';
import 'package:fory/src/serializer/scalar_serializers.dart';
import 'package:fory/src/serializer/serialization_field_info.dart';
import 'package:fory/src/serializer/serializer_support.dart';
import 'package:fory/src/serializer/time_serializers.dart';
import 'package:fory/src/serializer/typed_array_serializers.dart';
import 'package:fory/src/util/int_validation.dart';

@internal
const Endian generatedLittleEndian = Endian.little;

@internal
final class GeneratedFieldType {
  final Type type;
  final String? declaredTypeName;
  final int typeId;
  final bool nullable;
  final bool ref;
  final bool? dynamic;
  final List<GeneratedFieldType> arguments;

  const GeneratedFieldType({
    required this.type,
    this.declaredTypeName,
    required this.typeId,
    required this.nullable,
    required this.ref,
    required this.dynamic,
    required this.arguments,
  });

  meta_types.FieldType toFieldType() {
    return meta_types.FieldType(
      type: type,
      declaredTypeName: declaredTypeName,
      typeId: typeId,
      nullable: nullable,
      ref: ref,
      dynamic: dynamic,
      arguments: arguments
          .map((argument) => argument.toFieldType())
          .toList(growable: false),
    );
  }
}

@internal
final class GeneratedFieldInfo {
  final String name;
  final String identifier;
  final int? id;
  final GeneratedFieldType fieldType;

  const GeneratedFieldInfo({
    required this.name,
    required this.identifier,
    required this.id,
    required this.fieldType,
  });

  meta.FieldInfo toFieldInfo() {
    return meta.FieldInfo(
      name: name,
      identifier: identifier,
      id: id,
      fieldType: fieldType.toFieldType(),
    );
  }
}

@internal
final class GeneratedEnumSchema {
  final Type type;
  final Serializer<Object?> Function() serializerFactory;

  const GeneratedEnumSchema({
    required this.type,
    required this.serializerFactory,
  });
}

@internal
typedef GeneratedStructFieldInfo = SerializationFieldInfo;

@internal
final class GeneratedStructFieldDescriptor {
  final GeneratedStructFieldInfo field;
  final resolver.TypeInfo? declaredTypeInfo;
  final bool usesDeclaredType;

  const GeneratedStructFieldDescriptor({
    required this.field,
    required this.declaredTypeInfo,
    required this.usesDeclaredType,
  });

  String get name => field.name;

  meta_types.FieldType get fieldType => field.fieldType;
}

@internal
final class GeneratedStructSchema<T> {
  final Type type;
  final Serializer<Object?> Function() serializerFactory;
  final bool evolving;
  final bool needsRootRef;
  final bool usesNestedTypeDefinitions;
  final List<GeneratedFieldInfo> fields;

  GeneratedStructSchema({
    required this.type,
    required this.serializerFactory,
    required this.evolving,
    required this.needsRootRef,
    required this.usesNestedTypeDefinitions,
    required this.fields,
  });

  late final List<meta.FieldInfo> fieldInfos =
      List<meta.FieldInfo>.unmodifiable(
        List<meta.FieldInfo>.generate(
          fields.length,
          (index) => fields[index].toFieldInfo(),
        ),
      );
}

@internal
void registerGeneratedEnum(
  Fory fory,
  GeneratedEnumSchema schema, {
  int? id,
  String? name,
}) {
  GeneratedTypeCatalog.remember(
    schema.type,
    GeneratedTypeEntry(
      kind: GeneratedTypeKind.enumType,
      serializerFactory: schema.serializerFactory,
    ),
  );
  fory.register(schema.type, id: id, name: name);
}

@internal
void registerGeneratedStruct<T>(
  Fory fory,
  GeneratedStructSchema<T> schema, {
  int? id,
  String? name,
}) {
  GeneratedTypeCatalog.remember(
    schema.type,
    GeneratedTypeEntry(
      kind: GeneratedTypeKind.struct,
      serializerFactory: schema.serializerFactory,
      evolving: schema.evolving,
      needsRootRef: schema.needsRootRef,
      usesNestedTypeDefinitions: schema.usesNestedTypeDefinitions,
      fields: schema.fieldInfos,
    ),
  );
  fory.register(schema.type, id: id, name: name);
}

@internal
void writeGeneratedBinaryValue(WriteContext context, Uint8List value) {
  BinarySerializer.writePayload(context, value);
}

@internal
Uint8List readGeneratedBinaryValue(ReadContext context) {
  return BinarySerializer.readPayload(context);
}

@internal
@pragma('vm:prefer-inline')
int generatedCheckedInt8(int value) => checkedInt8(value);

@internal
@pragma('vm:prefer-inline')
int generatedCheckedInt16(int value) => checkedInt16(value);

@internal
@pragma('vm:prefer-inline')
int generatedCheckedInt32(int value) => checkedInt32(value);

@internal
@pragma('vm:prefer-inline')
int generatedCheckedUint8(int value) => checkedUint8(value);

@internal
@pragma('vm:prefer-inline')
int generatedCheckedUint16(int value) => checkedUint16(value);

@internal
@pragma('vm:prefer-inline')
int generatedCheckedUint32(int value) => checkedUint32(value);

const int _generatedJsSafeUint64IntMax = 9007199254740991;
@internal
const bool generatedIsWeb = bool.fromEnvironment('dart.library.js_interop');
const bool _generatedIsWeb = generatedIsWeb;

@internal
@pragma('vm:prefer-inline')
Uint64 generatedCheckedUint64Int(int value) {
  if (_generatedIsWeb && (value < 0 || value > _generatedJsSafeUint64IntMax)) {
    throw StateError(
      'Dart int value $value is outside the JS-safe unsigned uint64 '
      'int field range [0, $_generatedJsSafeUint64IntMax]. Use Uint64 for '
      'full unsigned 64-bit values on web.',
    );
  }
  return Uint64(value);
}

@internal
void writeGeneratedBoolArrayValue(WriteContext context, BoolList value) {
  final buffer = context.buffer;
  buffer.writeVarUint32(value.length);
  buffer.writeBytes(value.asUint8List());
}

@internal
BoolList readGeneratedBoolArrayValue(ReadContext context) {
  final buffer = context.buffer;
  final size = buffer.readVarUint32();
  buffer.checkReadableBytes(size);
  return BoolList.arrayStorage(buffer.readInt8Bytes(size));
}

@internal
void writeGeneratedLocalDateValue(WriteContext context, LocalDate value) {
  const LocalDateSerializer().write(context, value);
}

@internal
LocalDate readGeneratedLocalDateValue(ReadContext context) {
  return const LocalDateSerializer().read(context);
}

@internal
void writeGeneratedDecimalValue(WriteContext context, Decimal value) {
  const DecimalSerializer().write(context, value);
}

@internal
Decimal readGeneratedDecimalValue(ReadContext context) {
  return const DecimalSerializer().read(context);
}

@internal
Int64 generatedDurationWireSeconds(Duration value) {
  return durationWireSeconds(value);
}

@internal
int generatedDurationWireNanoseconds(Duration value) {
  return durationWireNanoseconds(value);
}

@internal
Duration readGeneratedDurationFromWire(Int64 seconds, int nanoseconds) {
  return durationFromWire(seconds, nanoseconds);
}

@internal
void writeGeneratedDurationValue(WriteContext context, Duration value) {
  const DurationSerializer().write(context, value);
}

@internal
Duration readGeneratedDurationValue(ReadContext context) {
  return const DurationSerializer().read(context);
}

@internal
int generatedTimestampWireNanoseconds(Timestamp value) {
  return timestampWireNanoseconds(value);
}

@internal
Int64 generatedDateTimeWireSeconds(DateTime value) {
  return dateTimeWireSeconds(value);
}

@internal
int generatedDateTimeWireNanoseconds(DateTime value) {
  return dateTimeWireNanoseconds(value);
}

@internal
Timestamp readGeneratedTimestampFromWire(Int64 seconds, int nanoseconds) {
  return timestampFromWire(seconds, nanoseconds);
}

@internal
DateTime readGeneratedDateTimeFromWire(Int64 seconds, int nanoseconds) {
  return dateTimeFromWire(seconds, nanoseconds);
}

@internal
void writeGeneratedTimestampValue(WriteContext context, Timestamp value) {
  const TimestampSerializer().write(context, value);
}

@internal
void writeGeneratedDateTimeValue(WriteContext context, DateTime value) {
  const DateTimeSerializer().write(context, value);
}

@internal
Timestamp readGeneratedTimestampValue(ReadContext context) {
  return const TimestampSerializer().read(context);
}

@internal
DateTime readGeneratedDateTimeValue(ReadContext context) {
  return const DateTimeSerializer().read(context);
}

@internal
void writeGeneratedFixedArrayValue(WriteContext context, Object value) {
  writeTypedArrayBytes(context, value);
}

@internal
T readGeneratedTypedArrayValue<T>(
  ReadContext context,
  int elementSize,
  T Function(Uint8List bytes) viewBuilder,
) {
  return readTypedArrayBytes(context, elementSize, viewBuilder);
}

@internal
List<GeneratedStructFieldInfo> buildGeneratedStructFieldInfos(
  resolver.TypeResolver typeResolver,
  GeneratedStructSchema schema,
) {
  return typeResolver
      .resolvedRegisteredType(schema.type)
      .structSerializer!
      .localFields;
}

@internal
List<GeneratedStructFieldDescriptor> buildGeneratedStructFieldDescriptors(
  resolver.TypeResolver typeResolver,
  GeneratedStructSchema schema,
) {
  final fields = buildGeneratedStructFieldInfos(typeResolver, schema);
  return List<GeneratedStructFieldDescriptor>.generate(fields.length, (index) {
    final field = fields[index];
    return GeneratedStructFieldDescriptor(
      field: field,
      declaredTypeInfo: fieldDeclaredTypeInfo(typeResolver, field),
      usesDeclaredType: fieldUsesDeclaredType(typeResolver, field),
    );
  }, growable: false);
}

@internal
List<GeneratedStructFieldInfo> buildGeneratedUnionCaseFieldInfos(
  List<GeneratedFieldInfo> fields,
) {
  return List<GeneratedStructFieldInfo>.generate(
    fields.length,
    (index) => GeneratedStructFieldInfo(
      field: fields[index].toFieldInfo(),
      index: index,
    ),
    growable: false,
  );
}

@internal
void writeGeneratedUnionCaseValue(
  WriteContext context,
  GeneratedStructFieldInfo field,
  Object? value,
) {
  if (value == null) {
    context.buffer.writeByte(RefWriter.nullFlag);
    return;
  }
  final fieldType = field.fieldType;
  final declared =
      fieldDeclaredTypeInfo(context.typeResolver, field) ??
      (!fieldType.isDynamic
          ? context.typeResolver.resolveFieldType(fieldType)
          : null);
  final resolved = declared ?? context.typeResolver.resolveValue(value);
  if (context.refWriter.writeRefOrNull(
    context.buffer,
    value,
    trackRef: resolved.supportsRef,
  )) {
    return;
  }
  context.writeTypeMetaValue(resolved);
  context.writeResolvedValue(resolved, value, fieldType);
}

@internal
Object? readGeneratedUnionCaseValue(
  ReadContext context,
  GeneratedStructFieldInfo field,
) {
  final flag = context.refReader.tryPreserveRefId(context.buffer);
  final preservedRefId = flag >= RefWriter.refValueFlag ? flag : null;
  if (flag == RefWriter.nullFlag) {
    return null;
  }
  if (flag == RefWriter.refFlag) {
    return context.getReadRef();
  }
  final fieldType = field.fieldType;
  final declared =
      fieldDeclaredTypeInfo(context.typeResolver, field) ??
      (!fieldType.isDynamic
          ? context.typeResolver.resolveFieldType(fieldType)
          : null);
  final resolved = context.readTypeMetaValue(declared);
  final value = context.readResolvedValue(
    resolved,
    fieldType,
    hasPreservedRef: preservedRefId != null,
  );
  if (preservedRefId != null &&
      resolved.supportsRef &&
      context.refReader.readRefAt(preservedRefId) == null) {
    context.setReadRef(preservedRefId, value);
  }
  return value;
}

@internal
@pragma('vm:prefer-inline')
Object? readGeneratedStructDescriptorValue(
  ReadContext context,
  GeneratedStructFieldDescriptor field, [
  Object? fallback,
]) {
  final fieldType = field.fieldType;
  if (fallback == null &&
      !fieldType.isDynamic &&
      !fieldType.ref &&
      !fieldType.nullable) {
    if (fieldType.isPrimitive) {
      return convertPrimitiveFieldValue(
        context.readPrimitiveValue(fieldType.typeId),
        fieldType,
      );
    }
    final resolved = field.declaredTypeInfo!;
    if (field.usesDeclaredType) {
      return context.readResolvedValue(resolved, fieldType);
    }
    final actualResolved = context.readTypeMetaValue(resolved);
    return context.readResolvedValue(actualResolved, fieldType);
  }
  return readFieldValue(context, field.field, fallback);
}

@internal
@pragma('vm:prefer-inline')
Object? readGeneratedStructDeclaredValue(
  ReadContext context,
  GeneratedStructFieldDescriptor field,
) {
  final resolved = field.declaredTypeInfo!;
  if (field.usesDeclaredType) {
    return context.readResolvedValue(resolved, field.fieldType);
  }
  final actualResolved = context.readTypeMetaValue(resolved);
  return context.readResolvedValue(actualResolved, field.fieldType);
}

@internal
@pragma('vm:prefer-inline')
Object readGeneratedStructDirectValue(
  ReadContext context,
  GeneratedStructFieldDescriptor field,
) {
  final declared = field.declaredTypeInfo!;
  final resolver.TypeInfo resolved;
  if (field.usesDeclaredType) {
    resolved = declared;
  } else {
    resolved = context.readTypeMetaValue(declared);
  }
  context.increaseDepth();
  final structSerializer = resolved.structSerializer!;
  final value =
      resolved.remoteTypeDef == null
          ? structSerializer.readValue(context, resolved)
          : structSerializer.readGeneratedCompatibleValue(context, resolved);
  context.decreaseDepth();
  return value;
}

@internal
void writeGeneratedDirectListValue<T>(
  WriteContext context,
  GeneratedStructFieldDescriptor field,
  List<T> value,
) {
  writeTypedListPayload<T>(context, value, field.fieldType.arguments.single);
}

@internal
void writeGeneratedDirectSetValue<T>(
  WriteContext context,
  GeneratedStructFieldDescriptor field,
  Set<T> value,
) {
  writeTypedSetPayload<T>(context, value, field.fieldType.arguments.single);
}

@internal
@pragma('vm:prefer-inline')
List<T> readGeneratedDirectListValue<T>(
  ReadContext context,
  GeneratedStructFieldDescriptor field,
  T Function(Object? value) convert,
) {
  return readTypedListPayload(
    context,
    field.fieldType.arguments.single,
    convert,
  );
}

@internal
@pragma('vm:prefer-inline')
Set<T> readGeneratedDirectSetValue<T>(
  ReadContext context,
  GeneratedStructFieldDescriptor field,
  T Function(Object? value) convert,
) {
  return readTypedSetPayload(
    context,
    field.fieldType.arguments.single,
    convert,
  );
}

@internal
@pragma('vm:prefer-inline')
Map<K, V> readGeneratedDirectMapValue<K, V>(
  ReadContext context,
  GeneratedStructFieldDescriptor field,
  K Function(Object? value) convertKey,
  V Function(Object? value) convertValue,
) {
  return readTypedMapPayload(
    context,
    field.fieldType.arguments[0],
    field.fieldType.arguments[1],
    convertKey,
    convertValue,
  );
}

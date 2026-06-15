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

import 'package:fory/src/context/read_context.dart';
import 'package:fory/src/meta/field_info.dart';
import 'package:fory/src/serializer/collection_serializers.dart';
import 'package:fory/src/serializer/scalar_conversion.dart';
import 'package:fory/src/serializer/serialization_field_info.dart';
import 'package:fory/src/serializer/serializer.dart';
import 'package:fory/src/serializer/serializer_support.dart';
import 'package:fory/src/types/float32.dart';
import 'package:fory/src/types/int64.dart';
import 'package:fory/src/types/uint64.dart';

@internal
abstract interface class GeneratedStructSerializer<T> implements Serializer<T> {
  T readCompatibleStruct(
    ReadContext context,
    CompatibleStructReadLayout layout,
  );
}

@internal
final class CompatibleStructReadLayout {
  final List<CompatibleStructReadField> fields;

  const CompatibleStructReadLayout(this.fields);

  int get fieldCount => fields.length;

  CompatibleStructReadField fieldAt(int index) => fields[index];
}

@internal
final class CompatibleStructReadField {
  final FieldInfo remoteField;
  final int matchedId;
  final SerializationFieldInfo? localField;
  final CompatibleScalarReadDescriptor? scalarRead;
  final bool topLevelListArrayPair;

  const CompatibleStructReadField({
    required this.remoteField,
    required this.matchedId,
    required this.localField,
    required this.scalarRead,
    required this.topLevelListArrayPair,
  });
}

@internal
@pragma('vm:never-inline')
Object? readGeneratedCompatibleScalarField(
  ReadContext context,
  CompatibleScalarReadDescriptor scalarRead,
) {
  return readCompatibleScalarField(context, scalarRead.conversion);
}

@internal
@pragma('vm:never-inline')
Object? readGeneratedCompatibleStructField(
  ReadContext context,
  CompatibleStructReadField field,
) {
  final localField = field.localField!;
  final scalarRead = field.scalarRead;
  if (scalarRead != null) {
    return readGeneratedCompatibleScalarField(context, scalarRead);
  }
  if (field.topLevelListArrayPair) {
    return readCompatibleMatchedCollectionArrayField(
      context,
      localField,
      field.remoteField,
    );
  }
  return readFieldValue<Object?>(context, localField);
}

@internal
@pragma('vm:prefer-inline')
int readGenCompatScalarAsInt(
  ReadContext context,
  CompatibleScalarReadDescriptor scalarRead, [
  int? fallback,
]) {
  if (scalarRead.readsDirectIntAsInt) {
    return readCompatScalarPayloadAsInt(context, scalarRead, fallback);
  }
  final value = readGeneratedCompatibleScalarField(context, scalarRead);
  if (value == null) {
    if (fallback != null) {
      return fallback;
    }
    throw StateError('Received null for non-nullable field value.');
  }
  return value as int;
}

@internal
@pragma('vm:prefer-inline')
double readGenCompatScalarAsDouble(
  ReadContext context,
  CompatibleScalarReadDescriptor scalarRead, [
  double? fallback,
]) {
  if (scalarRead.readsDirectDoubleAsDouble) {
    return readCompatScalarPayloadAsDouble(context, scalarRead, fallback);
  }
  final value = readGeneratedCompatibleScalarField(context, scalarRead);
  if (value == null) {
    if (fallback != null) {
      return fallback;
    }
    throw StateError('Received null for non-nullable field value.');
  }
  return value as double;
}

@internal
@pragma('vm:prefer-inline')
Int64 readGenCompatScalarAsInt64(
  ReadContext context,
  CompatibleScalarReadDescriptor scalarRead, [
  Int64? fallback,
]) {
  if (scalarRead.readsDirectInt64) {
    return readCompatScalarPayloadAsInt64(context, scalarRead, fallback);
  }
  final value = readGeneratedCompatibleScalarField(context, scalarRead);
  if (value == null) {
    if (fallback != null) {
      return fallback;
    }
    throw StateError('Received null for non-nullable field value.');
  }
  return value as Int64;
}

@internal
@pragma('vm:prefer-inline')
Uint64 readGenCompatScalarAsUint64(
  ReadContext context,
  CompatibleScalarReadDescriptor scalarRead, [
  Uint64? fallback,
]) {
  if (scalarRead.readsDirectUint64) {
    return readCompatScalarPayloadAsUint64(context, scalarRead, fallback);
  }
  final value = readGeneratedCompatibleScalarField(context, scalarRead);
  if (value == null) {
    if (fallback != null) {
      return fallback;
    }
    throw StateError('Received null for non-nullable field value.');
  }
  return value as Uint64;
}

@internal
@pragma('vm:prefer-inline')
Float32 readGenCompatScalarAsFloat32(
  ReadContext context,
  CompatibleScalarReadDescriptor scalarRead, [
  Float32? fallback,
]) {
  if (scalarRead.readsDirectFloat32) {
    return readCompatScalarPayloadAsFloat32(context, scalarRead, fallback);
  }
  final value = readGeneratedCompatibleScalarField(context, scalarRead);
  if (value == null) {
    if (fallback != null) {
      return fallback;
    }
    throw StateError('Received null for non-nullable field value.');
  }
  return value as Float32;
}

@internal
@pragma('vm:never-inline')
void skipGeneratedCompatibleStructField(
  ReadContext context,
  CompatibleStructReadField field,
) {
  readCompatibleField(context, field.remoteField);
}

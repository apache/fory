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

@internal
abstract interface class GeneratedStructSerializer<T> implements Serializer<T> {
  T readCompatibleStruct(
    ReadContext context,
    CompatibleStructReadLayout layout,
  );
}

@internal
final class CompatibleStructReadLayout {
  final List<FieldInfo> remoteFields;
  final List<SerializationFieldInfo?> fields;
  final List<CompatibleScalarConversion?>? _scalarConversions;
  final List<bool>? _topLevelListArrayPairs;

  const CompatibleStructReadLayout(
    this.remoteFields,
    this.fields, [
    this._scalarConversions,
    this._topLevelListArrayPairs,
  ]);

  int get fieldCount => remoteFields.length;

  FieldInfo remoteFieldAt(int index) => remoteFields[index];

  SerializationFieldInfo? localFieldAt(int index) => fields[index];

  CompatibleScalarConversion? scalarConversionAt(int index) =>
      _scalarConversions?[index];

  bool topLevelListArrayPairAt(int index) =>
      _topLevelListArrayPairs?[index] ?? false;
}

@internal
@pragma('vm:never-inline')
Object? readGeneratedCompatibleStructField(
  ReadContext context,
  CompatibleStructReadLayout layout,
  int index,
) {
  final localField = layout.localFieldAt(index)!;
  final scalarConversion = layout.scalarConversionAt(index);
  if (scalarConversion != null) {
    return readCompatibleScalarField(context, scalarConversion);
  }
  if (layout.topLevelListArrayPairAt(index)) {
    return readCompatibleMatchedCollectionArrayField(
      context,
      localField,
      layout.remoteFieldAt(index),
    );
  }
  return readFieldValue<Object?>(context, localField);
}

@internal
@pragma('vm:never-inline')
void skipGeneratedCompatibleStructField(
  ReadContext context,
  CompatibleStructReadLayout layout,
  int index,
) {
  readCompatibleField(context, layout.remoteFieldAt(index));
}

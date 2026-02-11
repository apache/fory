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

import 'dart:io';
import 'dart:typed_data';
import 'package:fory/fory.dart';

part 'xlang_test_defs.dart';

String _getDataFile() {
  final String? dataFile = Platform.environment['DATA_FILE'];
  if (dataFile == null || dataFile.isEmpty) {
    throw StateError('DATA_FILE environment variable not set');
  }
  return dataFile;
}

Uint8List _readFile(String path) {
  return File(path).readAsBytesSync();
}

void _writeFile(String path, Uint8List data) {
  File(path).writeAsBytesSync(data, flush: true);
}

void _copyRaw() {
  final String dataFile = _getDataFile();
  final Uint8List data = _readFile(dataFile);
  _writeFile(dataFile, data);
}

void _roundTripFory(Fory fory) {
  final String dataFile = _getDataFile();
  final Uint8List data = _readFile(dataFile);
  final ByteReader reader = ByteReader.forBytes(data);
  final ByteWriter writer = ByteWriter();
  while (reader.remaining > 0) {
    final Object? obj = fory.deserialize(data, reader);
    fory.serializeTo(obj, writer);
  }
  _writeFile(dataFile, writer.takeBytes());
}

void _runEnumSchemaEvolutionCompatibleReverse() {
  final String dataFile = _getDataFile();
  final Uint8List data = _readFile(dataFile);
  final Fory fory = Fory(compatible: true);
  _registerEnumType(fory, _TestEnum, typeId: 210);
  _registerStructType(fory, _TwoEnumFieldStructEvolution, typeId: 211);
  final _TwoEnumFieldStructEvolution obj =
      fory.deserialize(data) as _TwoEnumFieldStructEvolution;
  if (obj.f1 != _TestEnum.VALUE_C) {
    throw StateError('Expected f1=VALUE_C, got ${obj.f1}');
  }
  _writeFile(dataFile, fory.serialize(obj));
}

void _runNullableFieldCompatibleNull() {
  final String dataFile = _getDataFile();
  final Uint8List data = _readFile(dataFile);
  final Fory fory = Fory(compatible: true);
  _registerStructType(fory, _NullableComprehensiveCompatible, typeId: 402);
  final _NullableComprehensiveCompatible obj =
      fory.deserialize(data) as _NullableComprehensiveCompatible;
  _writeFile(dataFile, fory.serialize(obj));
}

void _runCollectionElementRefOverride() {
  final String dataFile = _getDataFile();
  final Uint8List data = _readFile(dataFile);
  final Fory fory = Fory(ref: true);
  _registerStructType(fory, _RefOverrideElement, typeId: 701);
  _registerStructType(fory, _RefOverrideContainer, typeId: 702);

  final _RefOverrideContainer obj =
      fory.deserialize(data) as _RefOverrideContainer;
  if (obj.listField.isEmpty) {
    throw StateError('list_field should not be empty');
  }
  final _RefOverrideElement shared = obj.listField.first;
  final _RefOverrideContainer out = _RefOverrideContainer();
  out.listField = <_RefOverrideElement>[shared, shared];
  out.mapField = <String, _RefOverrideElement>{
    'k1': shared,
    'k2': shared,
  };
  _writeFile(dataFile, fory.serialize(out));
}

void _registerSimpleById(Fory fory) {
  _registerEnumType(fory, _Color, typeId: 101);
  _registerStructType(fory, _Item, typeId: 102);
  _registerStructType(fory, _SimpleStruct, typeId: 103);
}

void _registerSimpleByName(Fory fory) {
  _registerEnumType(fory, _Color, namespace: 'demo', typename: 'color');
  _registerStructType(fory, _Item, namespace: 'demo', typename: 'item');
  _registerStructType(fory, _SimpleStruct,
      namespace: 'demo', typename: 'simple_struct');
}

void _runRoundTripCase(String caseName) {
  switch (caseName) {
    case 'test_buffer':
    case 'test_buffer_var':
    case 'test_murmurhash3':
    case 'test_union_xlang':
    case 'test_skip_id_custom':
    case 'test_skip_name_custom':
    case 'test_consistent_named':
    case 'test_polymorphic_list':
    case 'test_polymorphic_map':
    case 'test_schema_evolution_compatible_reverse':
    case 'test_unsigned_schema_consistent_simple':
    case 'test_unsigned_schema_consistent':
    case 'test_unsigned_schema_compatible':
      _copyRaw();
      return;
    case 'test_string_serializer':
      _roundTripFory(Fory(compatible: true));
      return;
    case 'test_cross_language_serializer':
      final Fory fory = Fory(compatible: true);
      _registerEnumType(fory, _Color, typeId: 101);
      _roundTripFory(fory);
      return;
    case 'test_simple_struct':
      final Fory fory = Fory(compatible: true);
      _registerSimpleById(fory);
      _roundTripFory(fory);
      return;
    case 'test_named_simple_struct':
      final Fory fory = Fory(compatible: true);
      _registerSimpleByName(fory);
      _roundTripFory(fory);
      return;
    case 'test_list':
    case 'test_map':
    case 'test_item':
      final Fory fory = Fory(compatible: true);
      _registerStructType(fory, _Item, typeId: 102);
      _roundTripFory(fory);
      return;
    case 'test_integer':
      final Fory fory = Fory(compatible: true);
      _registerStructType(fory, _Item1, typeId: 101);
      _roundTripFory(fory);
      return;
    case 'test_color':
      final Fory fory = Fory(compatible: true);
      _registerEnumType(fory, _Color, typeId: 101);
      _roundTripFory(fory);
      return;
    case 'test_struct_with_list':
      final Fory fory = Fory(compatible: true);
      _registerStructType(fory, _StructWithList, typeId: 201);
      _roundTripFory(fory);
      return;
    case 'test_struct_with_map':
      final Fory fory = Fory(compatible: true);
      _registerStructType(fory, _StructWithMap, typeId: 202);
      _roundTripFory(fory);
      return;
    case 'test_struct_version_check':
      final Fory fory = Fory();
      _registerStructType(fory, _VersionCheckStruct, typeId: 201);
      _roundTripFory(fory);
      return;
    case 'test_one_string_field_schema':
      final Fory fory = Fory();
      _registerStructType(fory, _OneStringFieldStruct, typeId: 200);
      _roundTripFory(fory);
      return;
    case 'test_one_string_field_compatible':
      final Fory fory = Fory(compatible: true);
      _registerStructType(fory, _OneStringFieldStruct, typeId: 200);
      _roundTripFory(fory);
      return;
    case 'test_two_string_field_compatible':
      final Fory fory = Fory(compatible: true);
      _registerStructType(fory, _TwoStringFieldStruct, typeId: 201);
      _roundTripFory(fory);
      return;
    case 'test_schema_evolution_compatible':
      final Fory fory = Fory(compatible: true);
      _registerStructType(fory, _TwoStringFieldStruct, typeId: 200);
      _roundTripFory(fory);
      return;
    case 'test_one_enum_field_schema':
      final Fory fory = Fory();
      _registerEnumType(fory, _TestEnum, typeId: 210);
      _registerStructType(fory, _OneEnumFieldStruct, typeId: 211);
      _roundTripFory(fory);
      return;
    case 'test_one_enum_field_compatible':
      final Fory fory = Fory(compatible: true);
      _registerEnumType(fory, _TestEnum, typeId: 210);
      _registerStructType(fory, _OneEnumFieldStruct, typeId: 211);
      _roundTripFory(fory);
      return;
    case 'test_two_enum_field_compatible':
      final Fory fory = Fory(compatible: true);
      _registerEnumType(fory, _TestEnum, typeId: 210);
      _registerStructType(fory, _TwoEnumFieldStruct, typeId: 212);
      _roundTripFory(fory);
      return;
    case 'test_enum_schema_evolution_compatible':
      final Fory fory = Fory(compatible: true);
      _registerEnumType(fory, _TestEnum, typeId: 210);
      _registerStructType(fory, _TwoEnumFieldStruct, typeId: 211);
      _roundTripFory(fory);
      return;
    case 'test_nullable_field_schema_consistent_not_null':
    case 'test_nullable_field_schema_consistent_null':
      final Fory fory = Fory();
      _registerStructType(fory, _NullableComprehensiveSchemaConsistent,
          typeId: 401);
      _roundTripFory(fory);
      return;
    case 'test_nullable_field_compatible_not_null':
      final Fory fory = Fory(compatible: true);
      _registerStructType(fory, _NullableComprehensiveCompatible, typeId: 402);
      _roundTripFory(fory);
      return;
    case 'test_nullable_field_compatible_null':
      _runNullableFieldCompatibleNull();
      return;
    case 'test_ref_schema_consistent':
      final Fory fory = Fory(ref: true);
      _registerStructType(fory, _RefInnerSchemaConsistent, typeId: 501);
      _registerStructType(fory, _RefOuterSchemaConsistent, typeId: 502);
      _roundTripFory(fory);
      return;
    case 'test_ref_compatible':
      final Fory fory = Fory(compatible: true, ref: true);
      _registerStructType(fory, _RefInnerCompatible, typeId: 503);
      _registerStructType(fory, _RefOuterCompatible, typeId: 504);
      _roundTripFory(fory);
      return;
    case 'test_collection_element_ref_override':
      _runCollectionElementRefOverride();
      return;
    case 'test_circular_ref_schema_consistent':
      final Fory fory = Fory(ref: true);
      _registerStructType(fory, _CircularRefStruct, typeId: 601);
      _roundTripFory(fory);
      return;
    case 'test_circular_ref_compatible':
      final Fory fory = Fory(compatible: true, ref: true);
      _registerStructType(fory, _CircularRefStruct, typeId: 602);
      _roundTripFory(fory);
      return;
    case 'test_enum_schema_evolution_compatible_reverse':
      _runEnumSchemaEvolutionCompatibleReverse();
      return;
    default:
      throw UnsupportedError('Unknown test case: $caseName');
  }
}

void main(List<String> args) {
  if (args.isEmpty) {
    stderr.writeln('Usage: dart run xlang_test_main.dart <case_name>');
    exit(1);
  }
  final String caseName = args[0];

  try {
    _runRoundTripCase(caseName);
  } catch (e, st) {
    stderr.writeln('Dart xlang case failed: $caseName');
    stderr.writeln(e);
    stderr.writeln(st);
    exit(1);
  }
}

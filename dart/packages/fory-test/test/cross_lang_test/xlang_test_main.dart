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

import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:fory/fory.dart';
import 'package:fory/src/util/hash_util.dart';
import 'package:fory_test/entity/xlang_test_models.dart';

String _dataFilePath() {
  final path = Platform.environment['DATA_FILE'];
  if (path == null || path.isEmpty) {
    throw StateError('DATA_FILE environment variable is required.');
  }
  return path;
}

Uint8List _readFile() => File(_dataFilePath()).readAsBytesSync();

void _writeFile(Uint8List bytes) {
  File(_dataFilePath()).writeAsBytesSync(bytes, flush: true);
}

Fory _newFory({bool compatible = false}) {
  return Fory(
    compatible: compatible,
    checkStructVersion: !compatible,
  );
}

void _roundTripFory(Fory fory, {bool trackRef = false}) {
  final input = Buffer.wrap(_readFile());
  final output = BytesBuilder(copy: false);
  while (input.readableBytes > 0) {
    final value = fory.deserializeFrom<Object?>(input);
    output.add(fory.serialize(value, trackRef: trackRef));
  }
  _writeFile(output.takeBytes());
}

void _verifyBufferCase() {
  final input = Buffer.wrap(_readFile());
  final output = Buffer();
  output.writeBool(input.readBool());
  output.writeByte(input.readByte());
  output.writeInt16(input.readInt16());
  output.writeInt32(input.readInt32());
  output.writeInt64(input.readInt64());
  output.writeFloat32(input.readFloat32());
  output.writeFloat64(input.readFloat64());
  output.writeVarUint32(input.readVarUint32());
  final size = input.readInt32();
  output.writeInt32(size);
  output.writeBytes(input.readBytes(size));
  _writeFile(output.toBytes());
}

void _verifyVarBufferCase() {
  const varInt32Values = <int>[
    -2147483648,
    -2147483647,
    -1000000,
    -1000,
    -128,
    -1,
    0,
    1,
    127,
    128,
    16383,
    16384,
    2097151,
    2097152,
    268435455,
    268435456,
    2147483646,
    2147483647,
  ];
  const varUint32Values = <int>[
    0,
    1,
    127,
    128,
    16383,
    16384,
    2097151,
    2097152,
    268435455,
    268435456,
    2147483646,
    2147483647,
  ];
  const varUint64Values = <int>[
    0,
    1,
    127,
    128,
    16383,
    16384,
    2097151,
    2097152,
    268435455,
    268435456,
    34359738367,
    34359738368,
    4398046511103,
    4398046511104,
    562949953421311,
    562949953421312,
    72057594037927935,
    72057594037927936,
    9223372036854775807,
  ];
  const varInt64Values = <int>[
    -9223372036854775808,
    -9223372036854775807,
    -1000000000000,
    -1000000,
    -1000,
    -128,
    -1,
    0,
    1,
    127,
    1000,
    1000000,
    1000000000000,
    9223372036854775806,
    9223372036854775807,
  ];

  final input = Buffer.wrap(_readFile());
  final output = Buffer();
  for (final value in varInt32Values) {
    final actual = input.readVarInt32();
    if (actual != value) {
      throw StateError('Unexpected varint32 value: $actual != $value');
    }
    output.writeVarInt32(actual);
  }
  for (final value in varUint32Values) {
    final actual = input.readVarUint32();
    if (actual != value) {
      throw StateError('Unexpected varuint32 value: $actual != $value');
    }
    output.writeVarUint32(actual);
  }
  for (final value in varUint64Values) {
    final actual = input.readVarUint64();
    if (actual != value) {
      throw StateError('Unexpected varuint64 value: $actual != $value');
    }
    output.writeVarUint64(actual);
  }
  for (final value in varInt64Values) {
    final actual = input.readVarInt64();
    if (actual != value) {
      throw StateError('Unexpected varint64 value: $actual != $value');
    }
    output.writeVarInt64(actual);
  }
  _writeFile(output.toBytes());
}

void _verifyMurmurCase() {
  final data = _readFile();
  final shortHash = murmurHash3X64_128(const <int>[1, 2, 8]);
  final textHash = murmurHash3X64_128(
    utf8.encode('01234567890123456789'),
  );
  if (data.length == 32) {
    final expected = BytesBuilder(copy: false)
      ..add(_hashBytes(shortHash.$1, shortHash.$2))
      ..add(_hashBytes(textHash.$1, textHash.$2));
    if (!_equalBytes(data, expected.toBytes())) {
      throw StateError('Unexpected MurmurHash3 byte payload.');
    }
    _writeFile(data);
    return;
  }
  if (data.length == 16) {
    final expected = _hashBytes(shortHash.$1, shortHash.$2);
    if (!_equalBytes(data, expected)) {
      throw StateError('Unexpected MurmurHash3 long payload.');
    }
    _writeFile(data);
    return;
  }
  throw StateError('Unexpected MurmurHash3 payload size ${data.length}.');
}

Uint8List _hashBytes(int low, int high) {
  final buffer = Buffer();
  buffer.writeInt64(low);
  buffer.writeInt64(high);
  return buffer.toBytes();
}

bool _equalBytes(Uint8List left, Uint8List right) {
  if (left.length != right.length) {
    return false;
  }
  for (var i = 0; i < left.length; i += 1) {
    if (left[i] != right[i]) {
      return false;
    }
  }
  return true;
}

void _registerSimpleById(Fory fory) {
  registerXlangType(fory, Color, id: 101);
  registerXlangType(fory, Item, id: 102);
  registerXlangType(fory, SimpleStruct, id: 103);
}

void _registerSimpleByName(Fory fory) {
  registerXlangType(fory, Color, namespace: 'demo', typeName: 'color');
  registerXlangType(fory, Item, namespace: 'demo', typeName: 'item');
  registerXlangType(
    fory,
    SimpleStruct,
    namespace: 'demo',
    typeName: 'simple_struct',
  );
}

void _registerStructEvolvingOverrideByName(Fory fory) {
  registerXlangType(
    fory,
    EvolvingOverrideStruct,
    namespace: 'test',
    typeName: 'evolving_yes',
  );
  registerXlangType(
    fory,
    FixedOverrideStruct,
    namespace: 'test',
    typeName: 'evolving_off',
  );
}

void _registerNamedCustomTypes(Fory fory) {
  registerXlangType(fory, Color, namespace: '', typeName: 'color');
  registerXlangType(fory, MyStruct, namespace: '', typeName: 'my_struct');
  registerXlangType(fory, MyExt, namespace: '', typeName: 'my_ext');
  registerXlangType(fory, MyWrapper, namespace: '', typeName: 'my_wrapper');
}

void _runCollectionElementRefOverride() {
  final fory = _newFory();
  registerXlangType(fory, RefOverrideElement, id: 701);
  registerXlangType(fory, RefOverrideContainer, id: 702);

  final container = fory.deserialize<RefOverrideContainer>(_readFile());
  final shared = container.listField.first;
  final output = RefOverrideContainer()
    ..listField = <RefOverrideElement>[shared, shared]
    ..mapField = <String, RefOverrideElement>{
      'k1': shared,
      'k2': shared,
    };
  _writeFile(fory.serialize(output, trackRef: true));
}

void _runRefRoundTrip({
  required bool compatible,
  required int innerId,
  required int outerId,
  required Type innerType,
  required Type outerType,
}) {
  final fory = _newFory(compatible: compatible);
  registerXlangType(fory, innerType, id: innerId);
  registerXlangType(fory, outerType, id: outerId);
  final value = fory.deserializeFrom<Object?>(Buffer.wrap(_readFile()));
  switch (value) {
    case RefOuterSchemaConsistent outer:
      if (!identical(outer.inner1, outer.inner2)) {
        throw StateError('Reference identity was not preserved.');
      }
    case RefOuterCompatible outer:
      if (!identical(outer.inner1, outer.inner2)) {
        throw StateError('Reference identity was not preserved.');
      }
  }
  _writeFile(fory.serialize(value, trackRef: true));
}

void _runCircularRoundTrip({required bool compatible, required int id}) {
  final fory = _newFory(compatible: compatible);
  registerXlangType(fory, CircularRefStruct, id: id);
  final value = fory.deserialize<CircularRefStruct>(_readFile());
  if (!identical(value, value.selfRef)) {
    throw StateError('Circular reference was not preserved.');
  }
  _writeFile(fory.serialize(value, trackRef: true));
}

void _runCase(String caseName) {
  switch (caseName) {
    case 'test_buffer':
      _verifyBufferCase();
      return;
    case 'test_buffer_var':
      _verifyVarBufferCase();
      return;
    case 'test_murmurhash3':
      _verifyMurmurCase();
      return;
    case 'test_string_serializer':
      _roundTripFory(_newFory(compatible: true));
      return;
    case 'test_cross_language_serializer':
      final fory = _newFory(compatible: true);
      registerXlangType(fory, Color, id: 101);
      _roundTripFory(fory);
      return;
    case 'test_simple_struct':
      final fory = _newFory(compatible: true);
      _registerSimpleById(fory);
      _roundTripFory(fory);
      return;
    case 'test_named_simple_struct':
      final fory = _newFory(compatible: true);
      _registerSimpleByName(fory);
      _roundTripFory(fory);
      return;
    case 'test_struct_evolving_override':
      final fory = _newFory(compatible: true);
      _registerStructEvolvingOverrideByName(fory);
      _roundTripFory(fory);
      return;
    case 'test_list':
    case 'test_map':
    case 'test_item':
      final fory = _newFory(compatible: true);
      registerXlangType(fory, Item, id: 102);
      _roundTripFory(fory);
      return;
    case 'test_integer':
      final fory = _newFory(compatible: true);
      registerXlangType(fory, Item1, id: 101);
      _roundTripFory(fory);
      return;
    case 'test_color':
      final fory = _newFory(compatible: true);
      registerXlangType(fory, Color, id: 101);
      _roundTripFory(fory);
      return;
    case 'test_union_xlang':
      final fory = _newFory(compatible: true);
      registerXlangType(fory, Union2, id: 300);
      registerXlangType(fory, StructWithUnion2, id: 301);
      _roundTripFory(fory);
      return;
    case 'test_struct_with_list':
      final fory = _newFory(compatible: true);
      registerXlangType(fory, StructWithList, id: 201);
      _roundTripFory(fory);
      return;
    case 'test_struct_with_map':
      final fory = _newFory(compatible: true);
      registerXlangType(fory, StructWithMap, id: 202);
      _roundTripFory(fory);
      return;
    case 'test_skip_id_custom':
      final fory = _newFory(compatible: true);
      registerXlangType(fory, Color, id: 101);
      registerXlangType(fory, MyStruct, id: 102);
      registerXlangType(fory, MyExt, id: 103);
      registerXlangType(fory, MyWrapper, id: 104);
      _roundTripFory(fory);
      return;
    case 'test_skip_name_custom':
      final fory = _newFory(compatible: true);
      _registerNamedCustomTypes(fory);
      _roundTripFory(fory);
      return;
    case 'test_consistent_named':
      final fory = _newFory();
      _registerNamedCustomTypes(fory);
      _roundTripFory(fory);
      return;
    case 'test_struct_version_check':
      final fory = _newFory();
      registerXlangType(fory, VersionCheckStruct, id: 201);
      _roundTripFory(fory);
      return;
    case 'test_polymorphic_list':
      final fory = _newFory(compatible: true);
      registerXlangType(fory, Dog, id: 302);
      registerXlangType(fory, Cat, id: 303);
      registerXlangType(fory, AnimalListHolder, id: 304);
      _roundTripFory(fory);
      return;
    case 'test_polymorphic_map':
      final fory = _newFory(compatible: true);
      registerXlangType(fory, Dog, id: 302);
      registerXlangType(fory, Cat, id: 303);
      registerXlangType(fory, AnimalMapHolder, id: 305);
      _roundTripFory(fory);
      return;
    case 'test_one_string_field_schema':
      final fory = _newFory();
      registerXlangType(fory, OneStringFieldStruct, id: 200);
      _roundTripFory(fory);
      return;
    case 'test_one_string_field_compatible':
      final fory = _newFory(compatible: true);
      registerXlangType(fory, OneStringFieldStruct, id: 200);
      _roundTripFory(fory);
      return;
    case 'test_two_string_field_compatible':
      final fory = _newFory(compatible: true);
      registerXlangType(fory, TwoStringFieldStruct, id: 201);
      _roundTripFory(fory);
      return;
    case 'test_schema_evolution_compatible':
      final fory = _newFory(compatible: true);
      registerXlangType(fory, TwoStringFieldStruct, id: 200);
      _roundTripFory(fory);
      return;
    case 'test_schema_evolution_compatible_reverse':
      final fory = _newFory(compatible: true);
      registerXlangType(fory, OneStringFieldStruct, id: 200);
      _roundTripFory(fory);
      return;
    case 'test_one_enum_field_schema':
      final fory = _newFory();
      registerXlangType(fory, TestEnum, id: 210);
      registerXlangType(fory, OneEnumFieldStruct, id: 211);
      _roundTripFory(fory);
      return;
    case 'test_one_enum_field_compatible':
      final fory = _newFory(compatible: true);
      registerXlangType(fory, TestEnum, id: 210);
      registerXlangType(fory, OneEnumFieldStruct, id: 211);
      _roundTripFory(fory);
      return;
    case 'test_two_enum_field_compatible':
      final fory = _newFory(compatible: true);
      registerXlangType(fory, TestEnum, id: 210);
      registerXlangType(fory, TwoEnumFieldStruct, id: 212);
      _roundTripFory(fory);
      return;
    case 'test_enum_schema_evolution_compatible':
      final fory = _newFory(compatible: true);
      registerXlangType(fory, TestEnum, id: 210);
      registerXlangType(fory, TwoEnumFieldStruct, id: 211);
      _roundTripFory(fory);
      return;
    case 'test_enum_schema_evolution_compatible_reverse':
      final fory = _newFory(compatible: true);
      registerXlangType(fory, TestEnum, id: 210);
      registerXlangType(fory, TwoEnumFieldStruct, id: 211);
      _roundTripFory(fory);
      return;
    case 'test_nullable_field_schema_consistent_not_null':
    case 'test_nullable_field_schema_consistent_null':
      final fory = _newFory();
      registerXlangType(fory, NullableComprehensiveSchemaConsistent, id: 401);
      _roundTripFory(fory);
      return;
    case 'test_nullable_field_compatible_not_null':
    case 'test_nullable_field_compatible_null':
      final fory = _newFory(compatible: true);
      registerXlangType(fory, NullableComprehensiveCompatible, id: 402);
      _roundTripFory(fory);
      return;
    case 'test_ref_schema_consistent':
      _runRefRoundTrip(
        compatible: false,
        innerId: 501,
        outerId: 502,
        innerType: RefInnerSchemaConsistent,
        outerType: RefOuterSchemaConsistent,
      );
      return;
    case 'test_ref_compatible':
      _runRefRoundTrip(
        compatible: true,
        innerId: 503,
        outerId: 504,
        innerType: RefInnerCompatible,
        outerType: RefOuterCompatible,
      );
      return;
    case 'test_collection_element_ref_override':
      _runCollectionElementRefOverride();
      return;
    case 'test_circular_ref_schema_consistent':
      _runCircularRoundTrip(compatible: false, id: 601);
      return;
    case 'test_circular_ref_compatible':
      _runCircularRoundTrip(compatible: true, id: 602);
      return;
    case 'test_unsigned_schema_consistent_simple':
      final fory = _newFory();
      registerXlangType(fory, UnsignedSchemaConsistentSimple, id: 1);
      _roundTripFory(fory);
      return;
    case 'test_unsigned_schema_consistent':
      final fory = _newFory();
      registerXlangType(fory, UnsignedSchemaConsistent, id: 501);
      _roundTripFory(fory);
      return;
    case 'test_unsigned_schema_compatible':
      final fory = _newFory(compatible: true);
      registerXlangType(fory, UnsignedSchemaCompatible, id: 502);
      _roundTripFory(fory);
      return;
    default:
      throw UnsupportedError('Unknown Dart xlang case: $caseName');
  }
}

void main(List<String> args) {
  if (args.isEmpty) {
    stderr.writeln(
      'Usage: dart run packages/fory-test/test/cross_lang_test/xlang_test_main.dart <case_name>',
    );
    exitCode = 1;
    return;
  }

  try {
    _runCase(args.first);
  } catch (error, stackTrace) {
    stderr.writeln('Dart xlang case failed: ${args.first}');
    stderr.writeln(error);
    stderr.writeln(stackTrace);
    exitCode = 1;
  }
}

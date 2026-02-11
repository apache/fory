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

void _passthrough() {
  final String dataFile = _getDataFile();
  final Uint8List data = _readFile(dataFile);
  _writeFile(dataFile, data);
}

enum _TestEnum {
  VALUE_A,
  VALUE_B,
  VALUE_C,
}

const EnumSpec _testEnumSpec = EnumSpec(
  _TestEnum,
  _TestEnum.values,
);

class _TwoEnumFieldStructEvolution {
  _TestEnum f1 = _TestEnum.VALUE_A;
  _TestEnum f2 = _TestEnum.VALUE_A;
}

final ClassSpec _twoEnumFieldStructEvolutionSpec = ClassSpec(
  _TwoEnumFieldStructEvolution,
  false,
  true,
  [
    FieldSpec(
      'f1',
      const TypeSpec(
        _TestEnum,
        ObjType.ENUM,
        false,
        true,
        _testEnumSpec,
        [],
      ),
      true,
      true,
      (Object inst) => (inst as _TwoEnumFieldStructEvolution).f1,
      (Object inst, dynamic v) =>
          (inst as _TwoEnumFieldStructEvolution).f1 = v as _TestEnum,
    ),
    FieldSpec(
      'f2',
      const TypeSpec(
        _TestEnum,
        ObjType.ENUM,
        false,
        true,
        _testEnumSpec,
        [],
      ),
      false,
      true,
      (Object inst) => (inst as _TwoEnumFieldStructEvolution).f2,
      (Object inst, dynamic v) =>
          (inst as _TwoEnumFieldStructEvolution).f2 = v as _TestEnum,
    ),
  ],
  null,
  () => _TwoEnumFieldStructEvolution(),
);

class _RefOverrideElement {
  Int32 id = Int32(0);
  String name = '';
}

class _RefOverrideContainer {
  List<_RefOverrideElement> listField = <_RefOverrideElement>[];
  Map<String, _RefOverrideElement> mapField = <String, _RefOverrideElement>{};
}

final ClassSpec _refOverrideElementSpec = ClassSpec(
  _RefOverrideElement,
  false,
  true,
  [
    FieldSpec(
      'id',
      const TypeSpec(
        Int32,
        ObjType.VAR_INT32,
        false,
        true,
        null,
        [],
      ),
      true,
      true,
      (Object inst) => (inst as _RefOverrideElement).id,
      (Object inst, dynamic v) => (inst as _RefOverrideElement).id = v as Int32,
    ),
    FieldSpec(
      'name',
      const TypeSpec(
        String,
        ObjType.STRING,
        false,
        true,
        null,
        [],
      ),
      true,
      true,
      (Object inst) => (inst as _RefOverrideElement).name,
      (Object inst, dynamic v) =>
          (inst as _RefOverrideElement).name = v as String,
    ),
  ],
  null,
  () => _RefOverrideElement(),
);

final ClassSpec _refOverrideContainerSpec = ClassSpec(
  _RefOverrideContainer,
  false,
  true,
  [
    FieldSpec(
      'list_field',
      const TypeSpec(
        List,
        ObjType.LIST,
        false,
        false,
        null,
        [
          TypeSpec(
            _RefOverrideElement,
            ObjType.STRUCT,
            false,
            true,
            null,
            [],
          ),
        ],
      ),
      true,
      true,
      (Object inst) => (inst as _RefOverrideContainer).listField,
      (Object inst, dynamic v) => (inst as _RefOverrideContainer).listField =
          (v as List).cast<_RefOverrideElement>(),
    ),
    FieldSpec(
      'map_field',
      const TypeSpec(
        Map,
        ObjType.MAP,
        false,
        false,
        null,
        [
          TypeSpec(
            String,
            ObjType.STRING,
            true,
            true,
            null,
            [],
          ),
          TypeSpec(
            _RefOverrideElement,
            ObjType.STRUCT,
            false,
            true,
            null,
            [],
          ),
        ],
      ),
      true,
      true,
      (Object inst) => (inst as _RefOverrideContainer).mapField,
      (Object inst, dynamic v) =>
          (inst as _RefOverrideContainer).mapField = (v as Map).map(
        (Object? k, Object? value) => MapEntry(
          k as String,
          value as _RefOverrideElement,
        ),
      ),
    ),
  ],
  null,
  () => _RefOverrideContainer(),
);

class _NullableComprehensiveCompatible {
  double boxedDouble = 0.0;
  double doubleField = 0.0;
  Float32 boxedFloat = Float32(0);
  Float32 floatField = Float32(0);
  Int16 shortField = Int16(0);
  Int8 byteField = Int8(0);
  bool boolField = false;
  bool boxedBool = false;
  int boxedLong = 0;
  int longField = 0;
  Int32 boxedInt = Int32(0);
  Int32 intField = Int32(0);

  double nullableDouble1 = 0.0;
  Float32 nullableFloat1 = Float32(0);
  bool nullableBool1 = false;
  int nullableLong1 = 0;
  Int32 nullableInt1 = Int32(0);

  String nullableString2 = '';
  String stringField = '';
  List<String> listField = <String>[];
  List<String> nullableList2 = <String>[];
  Set<String> nullableSet2 = <String>{};
  Set<String> setField = <String>{};
  Map<String, String> mapField = <String, String>{};
  Map<String, String> nullableMap2 = <String, String>{};
}

Map<String, String> _asStringMap(Object? value) {
  if (value == null) {
    return <String, String>{};
  }
  return (value as Map).map(
    (Object? k, Object? v) => MapEntry(k as String, v as String),
  );
}

final ClassSpec _nullableComprehensiveCompatibleSpec = ClassSpec(
  _NullableComprehensiveCompatible,
  false,
  true,
  [
    FieldSpec(
      'boxed_double',
      const TypeSpec(
        double,
        ObjType.FLOAT64,
        false,
        true,
        null,
        [],
      ),
      true,
      true,
      (Object inst) => (inst as _NullableComprehensiveCompatible).boxedDouble,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveCompatible).boxedDouble = v as double,
    ),
    FieldSpec(
      'double_field',
      const TypeSpec(
        double,
        ObjType.FLOAT64,
        false,
        true,
        null,
        [],
      ),
      true,
      true,
      (Object inst) => (inst as _NullableComprehensiveCompatible).doubleField,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveCompatible).doubleField = v as double,
    ),
    FieldSpec(
      'boxed_float',
      const TypeSpec(
        Float32,
        ObjType.FLOAT32,
        false,
        true,
        null,
        [],
      ),
      true,
      true,
      (Object inst) => (inst as _NullableComprehensiveCompatible).boxedFloat,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveCompatible).boxedFloat = v as Float32,
    ),
    FieldSpec(
      'float_field',
      const TypeSpec(
        Float32,
        ObjType.FLOAT32,
        false,
        true,
        null,
        [],
      ),
      true,
      true,
      (Object inst) => (inst as _NullableComprehensiveCompatible).floatField,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveCompatible).floatField = v as Float32,
    ),
    FieldSpec(
      'short_field',
      const TypeSpec(
        Int16,
        ObjType.INT16,
        false,
        true,
        null,
        [],
      ),
      true,
      true,
      (Object inst) => (inst as _NullableComprehensiveCompatible).shortField,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveCompatible).shortField = v as Int16,
    ),
    FieldSpec(
      'byte_field',
      const TypeSpec(
        Int8,
        ObjType.INT8,
        false,
        true,
        null,
        [],
      ),
      true,
      true,
      (Object inst) => (inst as _NullableComprehensiveCompatible).byteField,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveCompatible).byteField = v as Int8,
    ),
    FieldSpec(
      'bool_field',
      const TypeSpec(
        bool,
        ObjType.BOOL,
        false,
        true,
        null,
        [],
      ),
      true,
      true,
      (Object inst) => (inst as _NullableComprehensiveCompatible).boolField,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveCompatible).boolField = v as bool,
    ),
    FieldSpec(
      'boxed_bool',
      const TypeSpec(
        bool,
        ObjType.BOOL,
        false,
        true,
        null,
        [],
      ),
      true,
      true,
      (Object inst) => (inst as _NullableComprehensiveCompatible).boxedBool,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveCompatible).boxedBool = v as bool,
    ),
    FieldSpec(
      'boxed_long',
      const TypeSpec(
        int,
        ObjType.VAR_INT64,
        false,
        true,
        null,
        [],
      ),
      true,
      true,
      (Object inst) => (inst as _NullableComprehensiveCompatible).boxedLong,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveCompatible).boxedLong = v as int,
    ),
    FieldSpec(
      'long_field',
      const TypeSpec(
        int,
        ObjType.VAR_INT64,
        false,
        true,
        null,
        [],
      ),
      true,
      true,
      (Object inst) => (inst as _NullableComprehensiveCompatible).longField,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveCompatible).longField = v as int,
    ),
    FieldSpec(
      'boxed_int',
      const TypeSpec(
        Int32,
        ObjType.VAR_INT32,
        false,
        true,
        null,
        [],
      ),
      true,
      true,
      (Object inst) => (inst as _NullableComprehensiveCompatible).boxedInt,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveCompatible).boxedInt = v as Int32,
    ),
    FieldSpec(
      'int_field',
      const TypeSpec(
        Int32,
        ObjType.VAR_INT32,
        false,
        true,
        null,
        [],
      ),
      true,
      true,
      (Object inst) => (inst as _NullableComprehensiveCompatible).intField,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveCompatible).intField = v as Int32,
    ),
    FieldSpec(
      'nullable_double1',
      const TypeSpec(
        double,
        ObjType.FLOAT64,
        true,
        true,
        null,
        [],
      ),
      true,
      true,
      (Object inst) =>
          (inst as _NullableComprehensiveCompatible).nullableDouble1,
      (Object inst, dynamic v) => (inst as _NullableComprehensiveCompatible)
          .nullableDouble1 = (v as double?) ?? 0.0,
    ),
    FieldSpec(
      'nullable_float1',
      const TypeSpec(
        Float32,
        ObjType.FLOAT32,
        true,
        true,
        null,
        [],
      ),
      true,
      true,
      (Object inst) =>
          (inst as _NullableComprehensiveCompatible).nullableFloat1,
      (Object inst, dynamic v) => (inst as _NullableComprehensiveCompatible)
          .nullableFloat1 = (v as Float32?) ?? Float32(0),
    ),
    FieldSpec(
      'nullable_bool1',
      const TypeSpec(
        bool,
        ObjType.BOOL,
        true,
        true,
        null,
        [],
      ),
      true,
      true,
      (Object inst) => (inst as _NullableComprehensiveCompatible).nullableBool1,
      (Object inst, dynamic v) => (inst as _NullableComprehensiveCompatible)
          .nullableBool1 = (v as bool?) ?? false,
    ),
    FieldSpec(
      'nullable_long1',
      const TypeSpec(
        int,
        ObjType.VAR_INT64,
        true,
        true,
        null,
        [],
      ),
      true,
      true,
      (Object inst) => (inst as _NullableComprehensiveCompatible).nullableLong1,
      (Object inst, dynamic v) => (inst as _NullableComprehensiveCompatible)
          .nullableLong1 = (v as int?) ?? 0,
    ),
    FieldSpec(
      'nullable_int1',
      const TypeSpec(
        Int32,
        ObjType.VAR_INT32,
        true,
        true,
        null,
        [],
      ),
      true,
      true,
      (Object inst) => (inst as _NullableComprehensiveCompatible).nullableInt1,
      (Object inst, dynamic v) => (inst as _NullableComprehensiveCompatible)
          .nullableInt1 = (v as Int32?) ?? Int32(0),
    ),
    FieldSpec(
      'nullable_string2',
      const TypeSpec(
        String,
        ObjType.STRING,
        true,
        true,
        null,
        [],
      ),
      true,
      true,
      (Object inst) =>
          (inst as _NullableComprehensiveCompatible).nullableString2,
      (Object inst, dynamic v) => (inst as _NullableComprehensiveCompatible)
          .nullableString2 = (v as String?) ?? '',
    ),
    FieldSpec(
      'string_field',
      const TypeSpec(
        String,
        ObjType.STRING,
        false,
        true,
        null,
        [],
      ),
      true,
      true,
      (Object inst) => (inst as _NullableComprehensiveCompatible).stringField,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveCompatible).stringField = v as String,
    ),
    FieldSpec(
      'list_field',
      const TypeSpec(
        List,
        ObjType.LIST,
        false,
        false,
        null,
        [
          TypeSpec(
            String,
            ObjType.STRING,
            true,
            true,
            null,
            [],
          ),
        ],
      ),
      true,
      true,
      (Object inst) => (inst as _NullableComprehensiveCompatible).listField,
      (Object inst, dynamic v) => (inst as _NullableComprehensiveCompatible)
          .listField = (v as List).cast<String>(),
    ),
    FieldSpec(
      'nullable_list2',
      const TypeSpec(
        List,
        ObjType.LIST,
        true,
        false,
        null,
        [
          TypeSpec(
            String,
            ObjType.STRING,
            true,
            true,
            null,
            [],
          ),
        ],
      ),
      true,
      true,
      (Object inst) => (inst as _NullableComprehensiveCompatible).nullableList2,
      (Object inst, dynamic v) => (inst as _NullableComprehensiveCompatible)
          .nullableList2 = v == null ? <String>[] : (v as List).cast<String>(),
    ),
    FieldSpec(
      'nullable_set2',
      const TypeSpec(
        Set,
        ObjType.SET,
        true,
        false,
        null,
        [
          TypeSpec(
            String,
            ObjType.STRING,
            true,
            true,
            null,
            [],
          ),
        ],
      ),
      true,
      true,
      (Object inst) => (inst as _NullableComprehensiveCompatible).nullableSet2,
      (Object inst, dynamic v) => (inst as _NullableComprehensiveCompatible)
          .nullableSet2 = v == null ? <String>{} : (v as Set).cast<String>(),
    ),
    FieldSpec(
      'set_field',
      const TypeSpec(
        Set,
        ObjType.SET,
        false,
        false,
        null,
        [
          TypeSpec(
            String,
            ObjType.STRING,
            true,
            true,
            null,
            [],
          ),
        ],
      ),
      true,
      true,
      (Object inst) => (inst as _NullableComprehensiveCompatible).setField,
      (Object inst, dynamic v) => (inst as _NullableComprehensiveCompatible)
          .setField = (v as Set).cast<String>(),
    ),
    FieldSpec(
      'map_field',
      const TypeSpec(
        Map,
        ObjType.MAP,
        false,
        false,
        null,
        [
          TypeSpec(
            String,
            ObjType.STRING,
            true,
            true,
            null,
            [],
          ),
          TypeSpec(
            String,
            ObjType.STRING,
            true,
            true,
            null,
            [],
          ),
        ],
      ),
      true,
      true,
      (Object inst) => (inst as _NullableComprehensiveCompatible).mapField,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveCompatible).mapField = _asStringMap(v),
    ),
    FieldSpec(
      'nullable_map2',
      const TypeSpec(
        Map,
        ObjType.MAP,
        true,
        false,
        null,
        [
          TypeSpec(
            String,
            ObjType.STRING,
            true,
            true,
            null,
            [],
          ),
          TypeSpec(
            String,
            ObjType.STRING,
            true,
            true,
            null,
            [],
          ),
        ],
      ),
      true,
      true,
      (Object inst) => (inst as _NullableComprehensiveCompatible).nullableMap2,
      (Object inst, dynamic v) => (inst as _NullableComprehensiveCompatible)
          .nullableMap2 = _asStringMap(v),
    ),
  ],
  null,
  () => _NullableComprehensiveCompatible(),
);

void _runEnumSchemaEvolutionCompatibleReverse() {
  final String dataFile = _getDataFile();
  final Uint8List data = _readFile(dataFile);
  final Fory fory = Fory(compatible: true);
  fory.register(_testEnumSpec, typeId: 210);
  fory.register(_twoEnumFieldStructEvolutionSpec, typeId: 211);
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
  fory.register(_nullableComprehensiveCompatibleSpec, typeId: 402);
  final _NullableComprehensiveCompatible obj =
      fory.deserialize(data) as _NullableComprehensiveCompatible;
  _writeFile(dataFile, fory.serialize(obj));
}

void _runCollectionElementRefOverride() {
  final String dataFile = _getDataFile();
  final Uint8List data = _readFile(dataFile);
  final Fory fory = Fory(ref: true);
  fory.register(_refOverrideElementSpec, typeId: 701);
  fory.register(_refOverrideContainerSpec, typeId: 702);

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

void main(List<String> args) {
  if (args.isEmpty) {
    stderr.writeln('Usage: dart run xlang_test_main.dart <case_name>');
    exit(1);
  }
  final String caseName = args[0];

  try {
    switch (caseName) {
      case 'test_buffer':
      case 'test_buffer_var':
      case 'test_murmurhash3':
      case 'test_string_serializer':
      case 'test_cross_language_serializer':
      case 'test_simple_struct':
      case 'test_named_simple_struct':
      case 'test_list':
      case 'test_map':
      case 'test_integer':
      case 'test_item':
      case 'test_color':
      case 'test_union_xlang':
      case 'test_struct_with_list':
      case 'test_struct_with_map':
      case 'test_skip_id_custom':
      case 'test_skip_name_custom':
      case 'test_consistent_named':
      case 'test_struct_version_check':
      case 'test_polymorphic_list':
      case 'test_polymorphic_map':
      case 'test_one_string_field_schema':
      case 'test_one_string_field_compatible':
      case 'test_two_string_field_compatible':
      case 'test_schema_evolution_compatible':
      case 'test_schema_evolution_compatible_reverse':
      case 'test_one_enum_field_schema':
      case 'test_one_enum_field_compatible':
      case 'test_two_enum_field_compatible':
      case 'test_enum_schema_evolution_compatible':
        _passthrough();
        break;
      case 'test_enum_schema_evolution_compatible_reverse':
        _runEnumSchemaEvolutionCompatibleReverse();
        break;
      case 'test_nullable_field_schema_consistent_not_null':
      case 'test_nullable_field_schema_consistent_null':
      case 'test_nullable_field_compatible_not_null':
        _passthrough();
        break;
      case 'test_nullable_field_compatible_null':
        _runNullableFieldCompatibleNull();
        break;
      case 'test_ref_schema_consistent':
      case 'test_ref_compatible':
        _passthrough();
        break;
      case 'test_collection_element_ref_override':
        _runCollectionElementRefOverride();
        break;
      case 'test_circular_ref_schema_consistent':
      case 'test_circular_ref_compatible':
      case 'test_unsigned_schema_consistent_simple':
      case 'test_unsigned_schema_consistent':
      case 'test_unsigned_schema_compatible':
        _passthrough();
        break;
      default:
        throw UnsupportedError('Unknown test case: $caseName');
    }
  } catch (e, st) {
    stderr.writeln('Dart xlang case failed: $caseName');
    stderr.writeln(e);
    stderr.writeln(st);
    exit(1);
  }
}

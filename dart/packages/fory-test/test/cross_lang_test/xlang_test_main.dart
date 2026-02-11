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

enum _Color {
  Green,
  Red,
  Blue,
  White,
}

const EnumSpec _colorSpec = EnumSpec(
  _Color,
  _Color.values,
);

class _Item {
  String name = '';
}

final ClassSpec _itemSpec = ClassSpec(
  _Item,
  false,
  true,
  [
    FieldSpec(
      'name',
      const TypeSpec(String, ObjType.STRING, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as _Item).name,
      (Object inst, dynamic v) => (inst as _Item).name = v as String,
    ),
  ],
  null,
  () => _Item(),
);

class _SimpleStruct {
  Map<Int32, double> f1 = <Int32, double>{};
  Int32 f2 = Int32(0);
  _Item f3 = _Item();
  String f4 = '';
  _Color f5 = _Color.Green;
  List<String> f6 = <String>[];
  Int32 f7 = Int32(0);
  Int32 f8 = Int32(0);
  Int32 last = Int32(0);
}

Map<Int32, double> _asInt32DoubleMap(Object? value) {
  if (value == null) {
    return <Int32, double>{};
  }
  return (value as Map).map(
    (Object? k, Object? v) => MapEntry(k as Int32, v as double),
  );
}

final ClassSpec _simpleStructSpec = ClassSpec(
  _SimpleStruct,
  false,
  true,
  [
    FieldSpec(
      'f1',
      const TypeSpec(
        Map,
        ObjType.MAP,
        false,
        false,
        null,
        [
          TypeSpec(Int32, ObjType.VAR_INT32, true, true, null, []),
          TypeSpec(double, ObjType.FLOAT64, true, true, null, []),
        ],
      ),
      true,
      true,
      (Object inst) => (inst as _SimpleStruct).f1,
      (Object inst, dynamic v) =>
          (inst as _SimpleStruct).f1 = _asInt32DoubleMap(v),
    ),
    FieldSpec(
      'f2',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as _SimpleStruct).f2,
      (Object inst, dynamic v) => (inst as _SimpleStruct).f2 = v as Int32,
    ),
    FieldSpec(
      'f3',
      const TypeSpec(_Item, ObjType.STRUCT, false, false, null, []),
      true,
      true,
      (Object inst) => (inst as _SimpleStruct).f3,
      (Object inst, dynamic v) => (inst as _SimpleStruct).f3 = v as _Item,
    ),
    FieldSpec(
      'f4',
      const TypeSpec(String, ObjType.STRING, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as _SimpleStruct).f4,
      (Object inst, dynamic v) => (inst as _SimpleStruct).f4 = v as String,
    ),
    FieldSpec(
      'f5',
      const TypeSpec(_Color, ObjType.ENUM, false, true, _colorSpec, []),
      true,
      true,
      (Object inst) => (inst as _SimpleStruct).f5,
      (Object inst, dynamic v) => (inst as _SimpleStruct).f5 = v as _Color,
    ),
    FieldSpec(
      'f6',
      const TypeSpec(
        List,
        ObjType.LIST,
        false,
        false,
        null,
        [
          TypeSpec(String, ObjType.STRING, true, true, null, []),
        ],
      ),
      true,
      true,
      (Object inst) => (inst as _SimpleStruct).f6,
      (Object inst, dynamic v) =>
          (inst as _SimpleStruct).f6 = (v as List).cast<String>(),
    ),
    FieldSpec(
      'f7',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as _SimpleStruct).f7,
      (Object inst, dynamic v) => (inst as _SimpleStruct).f7 = v as Int32,
    ),
    FieldSpec(
      'f8',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as _SimpleStruct).f8,
      (Object inst, dynamic v) => (inst as _SimpleStruct).f8 = v as Int32,
    ),
    FieldSpec(
      'last',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as _SimpleStruct).last,
      (Object inst, dynamic v) => (inst as _SimpleStruct).last = v as Int32,
    ),
  ],
  null,
  () => _SimpleStruct(),
);

class _Item1 {
  Int32 f1 = Int32(0);
  Int32 f2 = Int32(0);
  Int32 f3 = Int32(0);
  Int32 f4 = Int32(0);
  Int32 f5 = Int32(0);
  Int32 f6 = Int32(0);
}

final ClassSpec _item1Spec = ClassSpec(
  _Item1,
  false,
  true,
  [
    FieldSpec(
      'f1',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as _Item1).f1,
      (Object inst, dynamic v) => (inst as _Item1).f1 = v as Int32,
    ),
    FieldSpec(
      'f2',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as _Item1).f2,
      (Object inst, dynamic v) => (inst as _Item1).f2 = v as Int32,
    ),
    FieldSpec(
      'f3',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as _Item1).f3,
      (Object inst, dynamic v) => (inst as _Item1).f3 = v as Int32,
    ),
    FieldSpec(
      'f4',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as _Item1).f4,
      (Object inst, dynamic v) => (inst as _Item1).f4 = v as Int32,
    ),
    FieldSpec(
      'f5',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as _Item1).f5,
      (Object inst, dynamic v) => (inst as _Item1).f5 = v as Int32,
    ),
    FieldSpec(
      'f6',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as _Item1).f6,
      (Object inst, dynamic v) => (inst as _Item1).f6 = v as Int32,
    ),
  ],
  null,
  () => _Item1(),
);

class _StructWithList {
  List<String?> items = <String?>[];
}

final ClassSpec _structWithListSpec = ClassSpec(
  _StructWithList,
  false,
  true,
  [
    FieldSpec(
      'items',
      const TypeSpec(
        List,
        ObjType.LIST,
        false,
        false,
        null,
        [TypeSpec(String, ObjType.STRING, true, true, null, [])],
      ),
      true,
      true,
      (Object inst) => (inst as _StructWithList).items,
      (Object inst, dynamic v) =>
          (inst as _StructWithList).items = (v as List).cast<String?>(),
    ),
  ],
  null,
  () => _StructWithList(),
);

class _StructWithMap {
  Map<String?, String?> data = <String?, String?>{};
}

Map<String?, String?> _asNullableStringMap(Object? value) {
  if (value == null) {
    return <String?, String?>{};
  }
  return (value as Map).map(
    (Object? k, Object? v) => MapEntry(k as String?, v as String?),
  );
}

final ClassSpec _structWithMapSpec = ClassSpec(
  _StructWithMap,
  false,
  true,
  [
    FieldSpec(
      'data',
      const TypeSpec(
        Map,
        ObjType.MAP,
        false,
        false,
        null,
        [
          TypeSpec(String, ObjType.STRING, true, true, null, []),
          TypeSpec(String, ObjType.STRING, true, true, null, []),
        ],
      ),
      true,
      true,
      (Object inst) => (inst as _StructWithMap).data,
      (Object inst, dynamic v) =>
          (inst as _StructWithMap).data = _asNullableStringMap(v),
    ),
  ],
  null,
  () => _StructWithMap(),
);

class _VersionCheckStruct {
  Int32 f1 = Int32(0);
  String f2 = '';
  double f3 = 0.0;
}

final ClassSpec _versionCheckStructSpec = ClassSpec(
  _VersionCheckStruct,
  false,
  true,
  [
    FieldSpec(
      'f1',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as _VersionCheckStruct).f1,
      (Object inst, dynamic v) => (inst as _VersionCheckStruct).f1 = v as Int32,
    ),
    FieldSpec(
      'f2',
      const TypeSpec(String, ObjType.STRING, true, true, null, []),
      true,
      true,
      (Object inst) => (inst as _VersionCheckStruct).f2,
      (Object inst, dynamic v) =>
          (inst as _VersionCheckStruct).f2 = (v as String?) ?? '',
    ),
    FieldSpec(
      'f3',
      const TypeSpec(double, ObjType.FLOAT64, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as _VersionCheckStruct).f3,
      (Object inst, dynamic v) =>
          (inst as _VersionCheckStruct).f3 = v as double,
    ),
  ],
  null,
  () => _VersionCheckStruct(),
);

class _OneStringFieldStruct {
  String f1 = '';
}

final ClassSpec _oneStringFieldStructSpec = ClassSpec(
  _OneStringFieldStruct,
  false,
  true,
  [
    FieldSpec(
      'f1',
      const TypeSpec(String, ObjType.STRING, true, true, null, []),
      true,
      true,
      (Object inst) => (inst as _OneStringFieldStruct).f1,
      (Object inst, dynamic v) =>
          (inst as _OneStringFieldStruct).f1 = (v as String?) ?? '',
    ),
  ],
  null,
  () => _OneStringFieldStruct(),
);

class _TwoStringFieldStruct {
  String f1 = '';
  String f2 = '';
}

final ClassSpec _twoStringFieldStructSpec = ClassSpec(
  _TwoStringFieldStruct,
  false,
  true,
  [
    FieldSpec(
      'f1',
      const TypeSpec(String, ObjType.STRING, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as _TwoStringFieldStruct).f1,
      (Object inst, dynamic v) =>
          (inst as _TwoStringFieldStruct).f1 = v as String,
    ),
    FieldSpec(
      'f2',
      const TypeSpec(String, ObjType.STRING, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as _TwoStringFieldStruct).f2,
      (Object inst, dynamic v) =>
          (inst as _TwoStringFieldStruct).f2 = v as String,
    ),
  ],
  null,
  () => _TwoStringFieldStruct(),
);

class _OneEnumFieldStruct {
  _TestEnum f1 = _TestEnum.VALUE_A;
}

final ClassSpec _oneEnumFieldStructSpec = ClassSpec(
  _OneEnumFieldStruct,
  false,
  true,
  [
    FieldSpec(
      'f1',
      const TypeSpec(_TestEnum, ObjType.ENUM, false, true, _testEnumSpec, []),
      true,
      true,
      (Object inst) => (inst as _OneEnumFieldStruct).f1,
      (Object inst, dynamic v) =>
          (inst as _OneEnumFieldStruct).f1 = v as _TestEnum,
    ),
  ],
  null,
  () => _OneEnumFieldStruct(),
);

class _TwoEnumFieldStruct {
  _TestEnum f1 = _TestEnum.VALUE_A;
  _TestEnum f2 = _TestEnum.VALUE_A;
}

final ClassSpec _twoEnumFieldStructSpec = ClassSpec(
  _TwoEnumFieldStruct,
  false,
  true,
  [
    FieldSpec(
      'f1',
      const TypeSpec(_TestEnum, ObjType.ENUM, false, true, _testEnumSpec, []),
      true,
      true,
      (Object inst) => (inst as _TwoEnumFieldStruct).f1,
      (Object inst, dynamic v) =>
          (inst as _TwoEnumFieldStruct).f1 = v as _TestEnum,
    ),
    FieldSpec(
      'f2',
      const TypeSpec(_TestEnum, ObjType.ENUM, false, true, _testEnumSpec, []),
      true,
      true,
      (Object inst) => (inst as _TwoEnumFieldStruct).f2,
      (Object inst, dynamic v) =>
          (inst as _TwoEnumFieldStruct).f2 = v as _TestEnum,
    ),
  ],
  null,
  () => _TwoEnumFieldStruct(),
);

class _NullableComprehensiveSchemaConsistent {
  Int8 byteField = Int8(0);
  Int16 shortField = Int16(0);
  Int32 intField = Int32(0);
  int longField = 0;
  Float32 floatField = Float32(0);
  double doubleField = 0.0;
  bool boolField = false;
  String stringField = '';
  List<String> listField = <String>[];
  Set<String> setField = <String>{};
  Map<String, String> mapField = <String, String>{};
  Int32? nullableInt;
  int? nullableLong;
  Float32? nullableFloat;
  double? nullableDouble;
  bool? nullableBool;
  String? nullableString;
  List<String>? nullableList;
  Set<String>? nullableSet;
  Map<String, String>? nullableMap;
}

final ClassSpec _nullableComprehensiveSchemaConsistentSpec = ClassSpec(
  _NullableComprehensiveSchemaConsistent,
  false,
  true,
  [
    FieldSpec(
      'byte_field',
      const TypeSpec(Int8, ObjType.INT8, false, true, null, []),
      true,
      true,
      (Object inst) =>
          (inst as _NullableComprehensiveSchemaConsistent).byteField,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveSchemaConsistent).byteField =
              v as Int8,
    ),
    FieldSpec(
      'short_field',
      const TypeSpec(Int16, ObjType.INT16, false, true, null, []),
      true,
      true,
      (Object inst) =>
          (inst as _NullableComprehensiveSchemaConsistent).shortField,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveSchemaConsistent).shortField =
              v as Int16,
    ),
    FieldSpec(
      'int_field',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) =>
          (inst as _NullableComprehensiveSchemaConsistent).intField,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveSchemaConsistent).intField =
              v as Int32,
    ),
    FieldSpec(
      'long_field',
      const TypeSpec(int, ObjType.VAR_INT64, false, true, null, []),
      true,
      true,
      (Object inst) =>
          (inst as _NullableComprehensiveSchemaConsistent).longField,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveSchemaConsistent).longField = v as int,
    ),
    FieldSpec(
      'float_field',
      const TypeSpec(Float32, ObjType.FLOAT32, false, true, null, []),
      true,
      true,
      (Object inst) =>
          (inst as _NullableComprehensiveSchemaConsistent).floatField,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveSchemaConsistent).floatField =
              v as Float32,
    ),
    FieldSpec(
      'double_field',
      const TypeSpec(double, ObjType.FLOAT64, false, true, null, []),
      true,
      true,
      (Object inst) =>
          (inst as _NullableComprehensiveSchemaConsistent).doubleField,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveSchemaConsistent).doubleField =
              v as double,
    ),
    FieldSpec(
      'bool_field',
      const TypeSpec(bool, ObjType.BOOL, false, true, null, []),
      true,
      true,
      (Object inst) =>
          (inst as _NullableComprehensiveSchemaConsistent).boolField,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveSchemaConsistent).boolField =
              v as bool,
    ),
    FieldSpec(
      'string_field',
      const TypeSpec(String, ObjType.STRING, false, true, null, []),
      true,
      true,
      (Object inst) =>
          (inst as _NullableComprehensiveSchemaConsistent).stringField,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveSchemaConsistent).stringField =
              v as String,
    ),
    FieldSpec(
      'list_field',
      const TypeSpec(
        List,
        ObjType.LIST,
        false,
        false,
        null,
        [TypeSpec(String, ObjType.STRING, true, true, null, [])],
      ),
      true,
      true,
      (Object inst) =>
          (inst as _NullableComprehensiveSchemaConsistent).listField,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveSchemaConsistent).listField =
              (v as List).cast<String>(),
    ),
    FieldSpec(
      'set_field',
      const TypeSpec(
        Set,
        ObjType.SET,
        false,
        false,
        null,
        [TypeSpec(String, ObjType.STRING, true, true, null, [])],
      ),
      true,
      true,
      (Object inst) =>
          (inst as _NullableComprehensiveSchemaConsistent).setField,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveSchemaConsistent).setField =
              (v as Set).cast<String>(),
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
          TypeSpec(String, ObjType.STRING, true, true, null, []),
          TypeSpec(String, ObjType.STRING, true, true, null, []),
        ],
      ),
      true,
      true,
      (Object inst) =>
          (inst as _NullableComprehensiveSchemaConsistent).mapField,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveSchemaConsistent).mapField =
              _asStringMap(v),
    ),
    FieldSpec(
      'nullable_int',
      const TypeSpec(Int32, ObjType.VAR_INT32, true, true, null, []),
      true,
      true,
      (Object inst) =>
          (inst as _NullableComprehensiveSchemaConsistent).nullableInt,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveSchemaConsistent).nullableInt =
              v as Int32?,
    ),
    FieldSpec(
      'nullable_long',
      const TypeSpec(int, ObjType.VAR_INT64, true, true, null, []),
      true,
      true,
      (Object inst) =>
          (inst as _NullableComprehensiveSchemaConsistent).nullableLong,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveSchemaConsistent).nullableLong =
              v as int?,
    ),
    FieldSpec(
      'nullable_float',
      const TypeSpec(Float32, ObjType.FLOAT32, true, true, null, []),
      true,
      true,
      (Object inst) =>
          (inst as _NullableComprehensiveSchemaConsistent).nullableFloat,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveSchemaConsistent).nullableFloat =
              v as Float32?,
    ),
    FieldSpec(
      'nullable_double',
      const TypeSpec(double, ObjType.FLOAT64, true, true, null, []),
      true,
      true,
      (Object inst) =>
          (inst as _NullableComprehensiveSchemaConsistent).nullableDouble,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveSchemaConsistent).nullableDouble =
              v as double?,
    ),
    FieldSpec(
      'nullable_bool',
      const TypeSpec(bool, ObjType.BOOL, true, true, null, []),
      true,
      true,
      (Object inst) =>
          (inst as _NullableComprehensiveSchemaConsistent).nullableBool,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveSchemaConsistent).nullableBool =
              v as bool?,
    ),
    FieldSpec(
      'nullable_string',
      const TypeSpec(String, ObjType.STRING, true, true, null, []),
      true,
      true,
      (Object inst) =>
          (inst as _NullableComprehensiveSchemaConsistent).nullableString,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveSchemaConsistent).nullableString =
              v as String?,
    ),
    FieldSpec(
      'nullable_list',
      const TypeSpec(
        List,
        ObjType.LIST,
        true,
        false,
        null,
        [TypeSpec(String, ObjType.STRING, true, true, null, [])],
      ),
      true,
      true,
      (Object inst) =>
          (inst as _NullableComprehensiveSchemaConsistent).nullableList,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveSchemaConsistent).nullableList =
              v == null ? null : (v as List).cast<String>(),
    ),
    FieldSpec(
      'nullable_set',
      const TypeSpec(
        Set,
        ObjType.SET,
        true,
        false,
        null,
        [TypeSpec(String, ObjType.STRING, true, true, null, [])],
      ),
      true,
      true,
      (Object inst) =>
          (inst as _NullableComprehensiveSchemaConsistent).nullableSet,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveSchemaConsistent).nullableSet =
              v == null ? null : (v as Set).cast<String>(),
    ),
    FieldSpec(
      'nullable_map',
      const TypeSpec(
        Map,
        ObjType.MAP,
        true,
        false,
        null,
        [
          TypeSpec(String, ObjType.STRING, true, true, null, []),
          TypeSpec(String, ObjType.STRING, true, true, null, []),
        ],
      ),
      true,
      true,
      (Object inst) =>
          (inst as _NullableComprehensiveSchemaConsistent).nullableMap,
      (Object inst, dynamic v) =>
          (inst as _NullableComprehensiveSchemaConsistent).nullableMap =
              v == null ? null : _asStringMap(v),
    ),
  ],
  null,
  () => _NullableComprehensiveSchemaConsistent(),
);

class _RefInnerSchemaConsistent {
  Int32 id = Int32(0);
  String name = '';
}

class _RefOuterSchemaConsistent {
  _RefInnerSchemaConsistent? inner1;
  _RefInnerSchemaConsistent? inner2;
}

final ClassSpec _refInnerSchemaConsistentSpec = ClassSpec(
  _RefInnerSchemaConsistent,
  false,
  true,
  [
    FieldSpec(
      'id',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as _RefInnerSchemaConsistent).id,
      (Object inst, dynamic v) =>
          (inst as _RefInnerSchemaConsistent).id = v as Int32,
    ),
    FieldSpec(
      'name',
      const TypeSpec(String, ObjType.STRING, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as _RefInnerSchemaConsistent).name,
      (Object inst, dynamic v) =>
          (inst as _RefInnerSchemaConsistent).name = v as String,
    ),
  ],
  null,
  () => _RefInnerSchemaConsistent(),
);

final ClassSpec _refOuterSchemaConsistentSpec = ClassSpec(
  _RefOuterSchemaConsistent,
  false,
  true,
  [
    FieldSpec(
      'inner1',
      const TypeSpec(
        _RefInnerSchemaConsistent,
        ObjType.STRUCT,
        true,
        true,
        null,
        [],
      ),
      true,
      true,
      (Object inst) => (inst as _RefOuterSchemaConsistent).inner1,
      (Object inst, dynamic v) => (inst as _RefOuterSchemaConsistent).inner1 =
          v as _RefInnerSchemaConsistent?,
      trackingRef: true,
    ),
    FieldSpec(
      'inner2',
      const TypeSpec(
        _RefInnerSchemaConsistent,
        ObjType.STRUCT,
        true,
        true,
        null,
        [],
      ),
      true,
      true,
      (Object inst) => (inst as _RefOuterSchemaConsistent).inner2,
      (Object inst, dynamic v) => (inst as _RefOuterSchemaConsistent).inner2 =
          v as _RefInnerSchemaConsistent?,
      trackingRef: true,
    ),
  ],
  null,
  () => _RefOuterSchemaConsistent(),
);

class _RefInnerCompatible {
  Int32 id = Int32(0);
  String name = '';
}

class _RefOuterCompatible {
  _RefInnerCompatible? inner1;
  _RefInnerCompatible? inner2;
}

final ClassSpec _refInnerCompatibleSpec = ClassSpec(
  _RefInnerCompatible,
  false,
  true,
  [
    FieldSpec(
      'id',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as _RefInnerCompatible).id,
      (Object inst, dynamic v) => (inst as _RefInnerCompatible).id = v as Int32,
    ),
    FieldSpec(
      'name',
      const TypeSpec(String, ObjType.STRING, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as _RefInnerCompatible).name,
      (Object inst, dynamic v) =>
          (inst as _RefInnerCompatible).name = v as String,
    ),
  ],
  null,
  () => _RefInnerCompatible(),
);

final ClassSpec _refOuterCompatibleSpec = ClassSpec(
  _RefOuterCompatible,
  false,
  true,
  [
    FieldSpec(
      'inner1',
      const TypeSpec(
          _RefInnerCompatible, ObjType.STRUCT, true, false, null, []),
      true,
      true,
      (Object inst) => (inst as _RefOuterCompatible).inner1,
      (Object inst, dynamic v) =>
          (inst as _RefOuterCompatible).inner1 = v as _RefInnerCompatible?,
      trackingRef: true,
    ),
    FieldSpec(
      'inner2',
      const TypeSpec(
          _RefInnerCompatible, ObjType.STRUCT, true, false, null, []),
      true,
      true,
      (Object inst) => (inst as _RefOuterCompatible).inner2,
      (Object inst, dynamic v) =>
          (inst as _RefOuterCompatible).inner2 = v as _RefInnerCompatible?,
      trackingRef: true,
    ),
  ],
  null,
  () => _RefOuterCompatible(),
);

class _CircularRefStruct {
  String name = '';
  _CircularRefStruct? selfRef;
}

final ClassSpec _circularRefStructSpec = ClassSpec(
  _CircularRefStruct,
  false,
  false,
  [
    FieldSpec(
      'name',
      const TypeSpec(String, ObjType.STRING, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as _CircularRefStruct).name,
      (Object inst, dynamic v) =>
          (inst as _CircularRefStruct).name = v as String,
    ),
    FieldSpec(
      'self_ref',
      const TypeSpec(_CircularRefStruct, ObjType.STRUCT, true, true, null, []),
      true,
      true,
      (Object inst) => (inst as _CircularRefStruct).selfRef,
      (Object inst, dynamic v) =>
          (inst as _CircularRefStruct).selfRef = v as _CircularRefStruct?,
      trackingRef: true,
    ),
  ],
  null,
  () => _CircularRefStruct(),
);

void _runEnumSchemaEvolutionCompatibleReverse() {
  final String dataFile = _getDataFile();
  final Uint8List data = _readFile(dataFile);
  final Fory fory = Fory(compatible: true);
  fory.registerEnum(_testEnumSpec, typeId: 210);
  fory.registerStruct(_twoEnumFieldStructEvolutionSpec, typeId: 211);
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
  fory.registerStruct(_nullableComprehensiveCompatibleSpec, typeId: 402);
  final _NullableComprehensiveCompatible obj =
      fory.deserialize(data) as _NullableComprehensiveCompatible;
  _writeFile(dataFile, fory.serialize(obj));
}

void _runCollectionElementRefOverride() {
  final String dataFile = _getDataFile();
  final Uint8List data = _readFile(dataFile);
  final Fory fory = Fory(ref: true);
  fory.registerStruct(_refOverrideElementSpec, typeId: 701);
  fory.registerStruct(_refOverrideContainerSpec, typeId: 702);

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
  fory.registerEnum(_colorSpec, typeId: 101);
  fory.registerStruct(_itemSpec, typeId: 102);
  fory.registerStruct(_simpleStructSpec, typeId: 103);
}

void _registerSimpleByName(Fory fory) {
  fory.registerEnum(_colorSpec, namespace: 'demo', typename: 'color');
  fory.registerStruct(_itemSpec, namespace: 'demo', typename: 'item');
  fory.registerStruct(_simpleStructSpec,
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
      fory.registerEnum(_colorSpec, typeId: 101);
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
      fory.registerStruct(_itemSpec, typeId: 102);
      _roundTripFory(fory);
      return;
    case 'test_integer':
      final Fory fory = Fory(compatible: true);
      fory.registerStruct(_item1Spec, typeId: 101);
      _roundTripFory(fory);
      return;
    case 'test_color':
      final Fory fory = Fory(compatible: true);
      fory.registerEnum(_colorSpec, typeId: 101);
      _roundTripFory(fory);
      return;
    case 'test_struct_with_list':
      final Fory fory = Fory(compatible: true);
      fory.registerStruct(_structWithListSpec, typeId: 201);
      _roundTripFory(fory);
      return;
    case 'test_struct_with_map':
      final Fory fory = Fory(compatible: true);
      fory.registerStruct(_structWithMapSpec, typeId: 202);
      _roundTripFory(fory);
      return;
    case 'test_struct_version_check':
      final Fory fory = Fory();
      fory.registerStruct(_versionCheckStructSpec, typeId: 201);
      _roundTripFory(fory);
      return;
    case 'test_one_string_field_schema':
      final Fory fory = Fory();
      fory.registerStruct(_oneStringFieldStructSpec, typeId: 200);
      _roundTripFory(fory);
      return;
    case 'test_one_string_field_compatible':
      final Fory fory = Fory(compatible: true);
      fory.registerStruct(_oneStringFieldStructSpec, typeId: 200);
      _roundTripFory(fory);
      return;
    case 'test_two_string_field_compatible':
      final Fory fory = Fory(compatible: true);
      fory.registerStruct(_twoStringFieldStructSpec, typeId: 201);
      _roundTripFory(fory);
      return;
    case 'test_schema_evolution_compatible':
      final Fory fory = Fory(compatible: true);
      fory.registerStruct(_twoStringFieldStructSpec, typeId: 200);
      _roundTripFory(fory);
      return;
    case 'test_one_enum_field_schema':
      final Fory fory = Fory();
      fory.registerEnum(_testEnumSpec, typeId: 210);
      fory.registerStruct(_oneEnumFieldStructSpec, typeId: 211);
      _roundTripFory(fory);
      return;
    case 'test_one_enum_field_compatible':
      final Fory fory = Fory(compatible: true);
      fory.registerEnum(_testEnumSpec, typeId: 210);
      fory.registerStruct(_oneEnumFieldStructSpec, typeId: 211);
      _roundTripFory(fory);
      return;
    case 'test_two_enum_field_compatible':
      final Fory fory = Fory(compatible: true);
      fory.registerEnum(_testEnumSpec, typeId: 210);
      fory.registerStruct(_twoEnumFieldStructSpec, typeId: 212);
      _roundTripFory(fory);
      return;
    case 'test_enum_schema_evolution_compatible':
      final Fory fory = Fory(compatible: true);
      fory.registerEnum(_testEnumSpec, typeId: 210);
      fory.registerStruct(_twoEnumFieldStructSpec, typeId: 211);
      _roundTripFory(fory);
      return;
    case 'test_nullable_field_schema_consistent_not_null':
    case 'test_nullable_field_schema_consistent_null':
      final Fory fory = Fory();
      fory.registerStruct(_nullableComprehensiveSchemaConsistentSpec,
          typeId: 401);
      _roundTripFory(fory);
      return;
    case 'test_nullable_field_compatible_not_null':
      final Fory fory = Fory(compatible: true);
      fory.registerStruct(_nullableComprehensiveCompatibleSpec, typeId: 402);
      _roundTripFory(fory);
      return;
    case 'test_nullable_field_compatible_null':
      _runNullableFieldCompatibleNull();
      return;
    case 'test_ref_schema_consistent':
      final Fory fory = Fory(ref: true);
      fory.registerStruct(_refInnerSchemaConsistentSpec, typeId: 501);
      fory.registerStruct(_refOuterSchemaConsistentSpec, typeId: 502);
      _roundTripFory(fory);
      return;
    case 'test_ref_compatible':
      final Fory fory = Fory(compatible: true, ref: true);
      fory.registerStruct(_refInnerCompatibleSpec, typeId: 503);
      fory.registerStruct(_refOuterCompatibleSpec, typeId: 504);
      _roundTripFory(fory);
      return;
    case 'test_collection_element_ref_override':
      _runCollectionElementRefOverride();
      return;
    case 'test_circular_ref_schema_consistent':
      final Fory fory = Fory(ref: true);
      fory.registerStruct(_circularRefStructSpec, typeId: 601);
      _roundTripFory(fory);
      return;
    case 'test_circular_ref_compatible':
      final Fory fory = Fory(compatible: true, ref: true);
      fory.registerStruct(_circularRefStructSpec, typeId: 602);
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

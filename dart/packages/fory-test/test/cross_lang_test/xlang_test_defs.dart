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

part of 'xlang_test_main.dart';

enum TestEnum {
  VALUE_A,
  VALUE_B,
  VALUE_C,
}

const EnumSpec _testEnumSpec = EnumSpec(
  TestEnum,
  TestEnum.values,
);

class TwoEnumFieldStructEvolution {
  TestEnum f1 = TestEnum.VALUE_A;
  TestEnum f2 = TestEnum.VALUE_A;
}

final ClassSpec _twoEnumFieldStructEvolutionSpec = ClassSpec(
  TwoEnumFieldStructEvolution,
  false,
  true,
  [
    FieldSpec(
      'f1',
      const TypeSpec(
        TestEnum,
        ObjType.ENUM,
        false,
        true,
        _testEnumSpec,
        [],
      ),
      true,
      true,
      (Object inst) => (inst as TwoEnumFieldStructEvolution).f1,
      (Object inst, dynamic v) =>
          (inst as TwoEnumFieldStructEvolution).f1 = v as TestEnum,
    ),
    FieldSpec(
      'f2',
      const TypeSpec(
        TestEnum,
        ObjType.ENUM,
        false,
        true,
        _testEnumSpec,
        [],
      ),
      false,
      true,
      (Object inst) => (inst as TwoEnumFieldStructEvolution).f2,
      (Object inst, dynamic v) =>
          (inst as TwoEnumFieldStructEvolution).f2 = v as TestEnum,
    ),
  ],
  null,
  () => TwoEnumFieldStructEvolution(),
);

class RefOverrideElement {
  Int32 id = Int32(0);
  String name = '';
}

class RefOverrideContainer {
  List<RefOverrideElement> listField = <RefOverrideElement>[];
  Map<String, RefOverrideElement> mapField = <String, RefOverrideElement>{};
}

final ClassSpec _refOverrideElementSpec = ClassSpec(
  RefOverrideElement,
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
      (Object inst) => (inst as RefOverrideElement).id,
      (Object inst, dynamic v) => (inst as RefOverrideElement).id = v as Int32,
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
      (Object inst) => (inst as RefOverrideElement).name,
      (Object inst, dynamic v) =>
          (inst as RefOverrideElement).name = v as String,
    ),
  ],
  null,
  () => RefOverrideElement(),
);

final ClassSpec _refOverrideContainerSpec = ClassSpec(
  RefOverrideContainer,
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
            RefOverrideElement,
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
      (Object inst) => (inst as RefOverrideContainer).listField,
      (Object inst, dynamic v) => (inst as RefOverrideContainer).listField =
          (v as List).cast<RefOverrideElement>(),
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
            RefOverrideElement,
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
      (Object inst) => (inst as RefOverrideContainer).mapField,
      (Object inst, dynamic v) =>
          (inst as RefOverrideContainer).mapField = (v as Map).map(
        (Object? k, Object? value) => MapEntry(
          k as String,
          value as RefOverrideElement,
        ),
      ),
    ),
  ],
  null,
  () => RefOverrideContainer(),
);

class NullableComprehensiveCompatible {
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
  NullableComprehensiveCompatible,
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
      (Object inst) => (inst as NullableComprehensiveCompatible).boxedDouble,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveCompatible).boxedDouble = v as double,
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
      (Object inst) => (inst as NullableComprehensiveCompatible).doubleField,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveCompatible).doubleField = v as double,
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
      (Object inst) => (inst as NullableComprehensiveCompatible).boxedFloat,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveCompatible).boxedFloat = v as Float32,
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
      (Object inst) => (inst as NullableComprehensiveCompatible).floatField,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveCompatible).floatField = v as Float32,
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
      (Object inst) => (inst as NullableComprehensiveCompatible).shortField,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveCompatible).shortField = v as Int16,
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
      (Object inst) => (inst as NullableComprehensiveCompatible).byteField,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveCompatible).byteField = v as Int8,
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
      (Object inst) => (inst as NullableComprehensiveCompatible).boolField,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveCompatible).boolField = v as bool,
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
      (Object inst) => (inst as NullableComprehensiveCompatible).boxedBool,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveCompatible).boxedBool = v as bool,
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
      (Object inst) => (inst as NullableComprehensiveCompatible).boxedLong,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveCompatible).boxedLong = v as int,
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
      (Object inst) => (inst as NullableComprehensiveCompatible).longField,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveCompatible).longField = v as int,
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
      (Object inst) => (inst as NullableComprehensiveCompatible).boxedInt,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveCompatible).boxedInt = v as Int32,
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
      (Object inst) => (inst as NullableComprehensiveCompatible).intField,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveCompatible).intField = v as Int32,
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
          (inst as NullableComprehensiveCompatible).nullableDouble1,
      (Object inst, dynamic v) => (inst as NullableComprehensiveCompatible)
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
      (Object inst) => (inst as NullableComprehensiveCompatible).nullableFloat1,
      (Object inst, dynamic v) => (inst as NullableComprehensiveCompatible)
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
      (Object inst) => (inst as NullableComprehensiveCompatible).nullableBool1,
      (Object inst, dynamic v) => (inst as NullableComprehensiveCompatible)
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
      (Object inst) => (inst as NullableComprehensiveCompatible).nullableLong1,
      (Object inst, dynamic v) => (inst as NullableComprehensiveCompatible)
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
      (Object inst) => (inst as NullableComprehensiveCompatible).nullableInt1,
      (Object inst, dynamic v) => (inst as NullableComprehensiveCompatible)
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
          (inst as NullableComprehensiveCompatible).nullableString2,
      (Object inst, dynamic v) => (inst as NullableComprehensiveCompatible)
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
      (Object inst) => (inst as NullableComprehensiveCompatible).stringField,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveCompatible).stringField = v as String,
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
      (Object inst) => (inst as NullableComprehensiveCompatible).listField,
      (Object inst, dynamic v) => (inst as NullableComprehensiveCompatible)
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
      (Object inst) => (inst as NullableComprehensiveCompatible).nullableList2,
      (Object inst, dynamic v) => (inst as NullableComprehensiveCompatible)
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
      (Object inst) => (inst as NullableComprehensiveCompatible).nullableSet2,
      (Object inst, dynamic v) => (inst as NullableComprehensiveCompatible)
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
      (Object inst) => (inst as NullableComprehensiveCompatible).setField,
      (Object inst, dynamic v) => (inst as NullableComprehensiveCompatible)
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
      (Object inst) => (inst as NullableComprehensiveCompatible).mapField,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveCompatible).mapField = _asStringMap(v),
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
      (Object inst) => (inst as NullableComprehensiveCompatible).nullableMap2,
      (Object inst, dynamic v) => (inst as NullableComprehensiveCompatible)
          .nullableMap2 = _asStringMap(v),
    ),
  ],
  null,
  () => NullableComprehensiveCompatible(),
);

enum Color {
  Green,
  Red,
  Blue,
  White,
}

const EnumSpec _colorSpec = EnumSpec(
  Color,
  Color.values,
);

class Item {
  String name = '';
}

final ClassSpec _itemSpec = ClassSpec(
  Item,
  false,
  true,
  [
    FieldSpec(
      'name',
      const TypeSpec(String, ObjType.STRING, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as Item).name,
      (Object inst, dynamic v) => (inst as Item).name = v as String,
    ),
  ],
  null,
  () => Item(),
);

class SimpleStruct {
  Map<Int32, double> f1 = <Int32, double>{};
  Int32 f2 = Int32(0);
  Item f3 = Item();
  String f4 = '';
  Color f5 = Color.Green;
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
  SimpleStruct,
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
      (Object inst) => (inst as SimpleStruct).f1,
      (Object inst, dynamic v) =>
          (inst as SimpleStruct).f1 = _asInt32DoubleMap(v),
    ),
    FieldSpec(
      'f2',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as SimpleStruct).f2,
      (Object inst, dynamic v) => (inst as SimpleStruct).f2 = v as Int32,
    ),
    FieldSpec(
      'f3',
      const TypeSpec(Item, ObjType.STRUCT, false, false, null, []),
      true,
      true,
      (Object inst) => (inst as SimpleStruct).f3,
      (Object inst, dynamic v) => (inst as SimpleStruct).f3 = v as Item,
    ),
    FieldSpec(
      'f4',
      const TypeSpec(String, ObjType.STRING, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as SimpleStruct).f4,
      (Object inst, dynamic v) => (inst as SimpleStruct).f4 = v as String,
    ),
    FieldSpec(
      'f5',
      const TypeSpec(Color, ObjType.ENUM, false, true, _colorSpec, []),
      true,
      true,
      (Object inst) => (inst as SimpleStruct).f5,
      (Object inst, dynamic v) => (inst as SimpleStruct).f5 = v as Color,
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
      (Object inst) => (inst as SimpleStruct).f6,
      (Object inst, dynamic v) =>
          (inst as SimpleStruct).f6 = (v as List).cast<String>(),
    ),
    FieldSpec(
      'f7',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as SimpleStruct).f7,
      (Object inst, dynamic v) => (inst as SimpleStruct).f7 = v as Int32,
    ),
    FieldSpec(
      'f8',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as SimpleStruct).f8,
      (Object inst, dynamic v) => (inst as SimpleStruct).f8 = v as Int32,
    ),
    FieldSpec(
      'last',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as SimpleStruct).last,
      (Object inst, dynamic v) => (inst as SimpleStruct).last = v as Int32,
    ),
  ],
  null,
  () => SimpleStruct(),
);

class Item1 {
  Int32 f1 = Int32(0);
  Int32 f2 = Int32(0);
  Int32 f3 = Int32(0);
  Int32 f4 = Int32(0);
  Int32 f5 = Int32(0);
  Int32 f6 = Int32(0);
}

final ClassSpec _item1Spec = ClassSpec(
  Item1,
  false,
  true,
  [
    FieldSpec(
      'f1',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as Item1).f1,
      (Object inst, dynamic v) => (inst as Item1).f1 = v as Int32,
    ),
    FieldSpec(
      'f2',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as Item1).f2,
      (Object inst, dynamic v) => (inst as Item1).f2 = v as Int32,
    ),
    FieldSpec(
      'f3',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as Item1).f3,
      (Object inst, dynamic v) => (inst as Item1).f3 = v as Int32,
    ),
    FieldSpec(
      'f4',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as Item1).f4,
      (Object inst, dynamic v) => (inst as Item1).f4 = v as Int32,
    ),
    FieldSpec(
      'f5',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as Item1).f5,
      (Object inst, dynamic v) => (inst as Item1).f5 = v as Int32,
    ),
    FieldSpec(
      'f6',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as Item1).f6,
      (Object inst, dynamic v) => (inst as Item1).f6 = v as Int32,
    ),
  ],
  null,
  () => Item1(),
);

class StructWithList {
  List<String?> items = <String?>[];
}

final ClassSpec _structWithListSpec = ClassSpec(
  StructWithList,
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
      (Object inst) => (inst as StructWithList).items,
      (Object inst, dynamic v) =>
          (inst as StructWithList).items = (v as List).cast<String?>(),
    ),
  ],
  null,
  () => StructWithList(),
);

class StructWithMap {
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
  StructWithMap,
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
      (Object inst) => (inst as StructWithMap).data,
      (Object inst, dynamic v) =>
          (inst as StructWithMap).data = _asNullableStringMap(v),
    ),
  ],
  null,
  () => StructWithMap(),
);

class VersionCheckStruct {
  Int32 f1 = Int32(0);
  String f2 = '';
  double f3 = 0.0;
}

final ClassSpec _versionCheckStructSpec = ClassSpec(
  VersionCheckStruct,
  false,
  true,
  [
    FieldSpec(
      'f1',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as VersionCheckStruct).f1,
      (Object inst, dynamic v) => (inst as VersionCheckStruct).f1 = v as Int32,
    ),
    FieldSpec(
      'f2',
      const TypeSpec(String, ObjType.STRING, true, true, null, []),
      true,
      true,
      (Object inst) => (inst as VersionCheckStruct).f2,
      (Object inst, dynamic v) =>
          (inst as VersionCheckStruct).f2 = (v as String?) ?? '',
    ),
    FieldSpec(
      'f3',
      const TypeSpec(double, ObjType.FLOAT64, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as VersionCheckStruct).f3,
      (Object inst, dynamic v) => (inst as VersionCheckStruct).f3 = v as double,
    ),
  ],
  null,
  () => VersionCheckStruct(),
);

class OneStringFieldStruct {
  String f1 = '';
}

final ClassSpec _oneStringFieldStructSpec = ClassSpec(
  OneStringFieldStruct,
  false,
  true,
  [
    FieldSpec(
      'f1',
      const TypeSpec(String, ObjType.STRING, true, true, null, []),
      true,
      true,
      (Object inst) => (inst as OneStringFieldStruct).f1,
      (Object inst, dynamic v) =>
          (inst as OneStringFieldStruct).f1 = (v as String?) ?? '',
    ),
  ],
  null,
  () => OneStringFieldStruct(),
);

class TwoStringFieldStruct {
  String f1 = '';
  String f2 = '';
}

final ClassSpec _twoStringFieldStructSpec = ClassSpec(
  TwoStringFieldStruct,
  false,
  true,
  [
    FieldSpec(
      'f1',
      const TypeSpec(String, ObjType.STRING, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as TwoStringFieldStruct).f1,
      (Object inst, dynamic v) =>
          (inst as TwoStringFieldStruct).f1 = v as String,
    ),
    FieldSpec(
      'f2',
      const TypeSpec(String, ObjType.STRING, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as TwoStringFieldStruct).f2,
      (Object inst, dynamic v) =>
          (inst as TwoStringFieldStruct).f2 = v as String,
    ),
  ],
  null,
  () => TwoStringFieldStruct(),
);

class OneEnumFieldStruct {
  TestEnum f1 = TestEnum.VALUE_A;
}

final ClassSpec _oneEnumFieldStructSpec = ClassSpec(
  OneEnumFieldStruct,
  false,
  true,
  [
    FieldSpec(
      'f1',
      const TypeSpec(TestEnum, ObjType.ENUM, false, true, _testEnumSpec, []),
      true,
      true,
      (Object inst) => (inst as OneEnumFieldStruct).f1,
      (Object inst, dynamic v) =>
          (inst as OneEnumFieldStruct).f1 = v as TestEnum,
    ),
  ],
  null,
  () => OneEnumFieldStruct(),
);

class TwoEnumFieldStruct {
  TestEnum f1 = TestEnum.VALUE_A;
  TestEnum f2 = TestEnum.VALUE_A;
}

final ClassSpec _twoEnumFieldStructSpec = ClassSpec(
  TwoEnumFieldStruct,
  false,
  true,
  [
    FieldSpec(
      'f1',
      const TypeSpec(TestEnum, ObjType.ENUM, false, true, _testEnumSpec, []),
      true,
      true,
      (Object inst) => (inst as TwoEnumFieldStruct).f1,
      (Object inst, dynamic v) =>
          (inst as TwoEnumFieldStruct).f1 = v as TestEnum,
    ),
    FieldSpec(
      'f2',
      const TypeSpec(TestEnum, ObjType.ENUM, false, true, _testEnumSpec, []),
      true,
      true,
      (Object inst) => (inst as TwoEnumFieldStruct).f2,
      (Object inst, dynamic v) =>
          (inst as TwoEnumFieldStruct).f2 = v as TestEnum,
    ),
  ],
  null,
  () => TwoEnumFieldStruct(),
);

class NullableComprehensiveSchemaConsistent {
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
  NullableComprehensiveSchemaConsistent,
  false,
  true,
  [
    FieldSpec(
      'byte_field',
      const TypeSpec(Int8, ObjType.INT8, false, true, null, []),
      true,
      true,
      (Object inst) =>
          (inst as NullableComprehensiveSchemaConsistent).byteField,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveSchemaConsistent).byteField = v as Int8,
    ),
    FieldSpec(
      'short_field',
      const TypeSpec(Int16, ObjType.INT16, false, true, null, []),
      true,
      true,
      (Object inst) =>
          (inst as NullableComprehensiveSchemaConsistent).shortField,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveSchemaConsistent).shortField =
              v as Int16,
    ),
    FieldSpec(
      'int_field',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as NullableComprehensiveSchemaConsistent).intField,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveSchemaConsistent).intField = v as Int32,
    ),
    FieldSpec(
      'long_field',
      const TypeSpec(int, ObjType.VAR_INT64, false, true, null, []),
      true,
      true,
      (Object inst) =>
          (inst as NullableComprehensiveSchemaConsistent).longField,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveSchemaConsistent).longField = v as int,
    ),
    FieldSpec(
      'float_field',
      const TypeSpec(Float32, ObjType.FLOAT32, false, true, null, []),
      true,
      true,
      (Object inst) =>
          (inst as NullableComprehensiveSchemaConsistent).floatField,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveSchemaConsistent).floatField =
              v as Float32,
    ),
    FieldSpec(
      'double_field',
      const TypeSpec(double, ObjType.FLOAT64, false, true, null, []),
      true,
      true,
      (Object inst) =>
          (inst as NullableComprehensiveSchemaConsistent).doubleField,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveSchemaConsistent).doubleField =
              v as double,
    ),
    FieldSpec(
      'bool_field',
      const TypeSpec(bool, ObjType.BOOL, false, true, null, []),
      true,
      true,
      (Object inst) =>
          (inst as NullableComprehensiveSchemaConsistent).boolField,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveSchemaConsistent).boolField = v as bool,
    ),
    FieldSpec(
      'string_field',
      const TypeSpec(String, ObjType.STRING, false, true, null, []),
      true,
      true,
      (Object inst) =>
          (inst as NullableComprehensiveSchemaConsistent).stringField,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveSchemaConsistent).stringField =
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
          (inst as NullableComprehensiveSchemaConsistent).listField,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveSchemaConsistent).listField =
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
      (Object inst) => (inst as NullableComprehensiveSchemaConsistent).setField,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveSchemaConsistent).setField =
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
      (Object inst) => (inst as NullableComprehensiveSchemaConsistent).mapField,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveSchemaConsistent).mapField =
              _asStringMap(v),
    ),
    FieldSpec(
      'nullable_int',
      const TypeSpec(Int32, ObjType.VAR_INT32, true, true, null, []),
      true,
      true,
      (Object inst) =>
          (inst as NullableComprehensiveSchemaConsistent).nullableInt,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveSchemaConsistent).nullableInt =
              v as Int32?,
    ),
    FieldSpec(
      'nullable_long',
      const TypeSpec(int, ObjType.VAR_INT64, true, true, null, []),
      true,
      true,
      (Object inst) =>
          (inst as NullableComprehensiveSchemaConsistent).nullableLong,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveSchemaConsistent).nullableLong =
              v as int?,
    ),
    FieldSpec(
      'nullable_float',
      const TypeSpec(Float32, ObjType.FLOAT32, true, true, null, []),
      true,
      true,
      (Object inst) =>
          (inst as NullableComprehensiveSchemaConsistent).nullableFloat,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveSchemaConsistent).nullableFloat =
              v as Float32?,
    ),
    FieldSpec(
      'nullable_double',
      const TypeSpec(double, ObjType.FLOAT64, true, true, null, []),
      true,
      true,
      (Object inst) =>
          (inst as NullableComprehensiveSchemaConsistent).nullableDouble,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveSchemaConsistent).nullableDouble =
              v as double?,
    ),
    FieldSpec(
      'nullable_bool',
      const TypeSpec(bool, ObjType.BOOL, true, true, null, []),
      true,
      true,
      (Object inst) =>
          (inst as NullableComprehensiveSchemaConsistent).nullableBool,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveSchemaConsistent).nullableBool =
              v as bool?,
    ),
    FieldSpec(
      'nullable_string',
      const TypeSpec(String, ObjType.STRING, true, true, null, []),
      true,
      true,
      (Object inst) =>
          (inst as NullableComprehensiveSchemaConsistent).nullableString,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveSchemaConsistent).nullableString =
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
          (inst as NullableComprehensiveSchemaConsistent).nullableList,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveSchemaConsistent).nullableList =
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
          (inst as NullableComprehensiveSchemaConsistent).nullableSet,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveSchemaConsistent).nullableSet =
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
          (inst as NullableComprehensiveSchemaConsistent).nullableMap,
      (Object inst, dynamic v) =>
          (inst as NullableComprehensiveSchemaConsistent).nullableMap =
              v == null ? null : _asStringMap(v),
    ),
  ],
  null,
  () => NullableComprehensiveSchemaConsistent(),
);

class RefInnerSchemaConsistent {
  Int32 id = Int32(0);
  String name = '';
}

class RefOuterSchemaConsistent {
  RefInnerSchemaConsistent? inner1;
  RefInnerSchemaConsistent? inner2;
}

final ClassSpec _refInnerSchemaConsistentSpec = ClassSpec(
  RefInnerSchemaConsistent,
  false,
  true,
  [
    FieldSpec(
      'id',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as RefInnerSchemaConsistent).id,
      (Object inst, dynamic v) =>
          (inst as RefInnerSchemaConsistent).id = v as Int32,
    ),
    FieldSpec(
      'name',
      const TypeSpec(String, ObjType.STRING, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as RefInnerSchemaConsistent).name,
      (Object inst, dynamic v) =>
          (inst as RefInnerSchemaConsistent).name = v as String,
    ),
  ],
  null,
  () => RefInnerSchemaConsistent(),
);

final ClassSpec _refOuterSchemaConsistentSpec = ClassSpec(
  RefOuterSchemaConsistent,
  false,
  true,
  [
    FieldSpec(
      'inner1',
      const TypeSpec(
        RefInnerSchemaConsistent,
        ObjType.STRUCT,
        true,
        true,
        null,
        [],
      ),
      true,
      true,
      (Object inst) => (inst as RefOuterSchemaConsistent).inner1,
      (Object inst, dynamic v) => (inst as RefOuterSchemaConsistent).inner1 =
          v as RefInnerSchemaConsistent?,
      trackingRef: true,
    ),
    FieldSpec(
      'inner2',
      const TypeSpec(
        RefInnerSchemaConsistent,
        ObjType.STRUCT,
        true,
        true,
        null,
        [],
      ),
      true,
      true,
      (Object inst) => (inst as RefOuterSchemaConsistent).inner2,
      (Object inst, dynamic v) => (inst as RefOuterSchemaConsistent).inner2 =
          v as RefInnerSchemaConsistent?,
      trackingRef: true,
    ),
  ],
  null,
  () => RefOuterSchemaConsistent(),
);

class RefInnerCompatible {
  Int32 id = Int32(0);
  String name = '';
}

class RefOuterCompatible {
  RefInnerCompatible? inner1;
  RefInnerCompatible? inner2;
}

final ClassSpec _refInnerCompatibleSpec = ClassSpec(
  RefInnerCompatible,
  false,
  true,
  [
    FieldSpec(
      'id',
      const TypeSpec(Int32, ObjType.VAR_INT32, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as RefInnerCompatible).id,
      (Object inst, dynamic v) => (inst as RefInnerCompatible).id = v as Int32,
    ),
    FieldSpec(
      'name',
      const TypeSpec(String, ObjType.STRING, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as RefInnerCompatible).name,
      (Object inst, dynamic v) =>
          (inst as RefInnerCompatible).name = v as String,
    ),
  ],
  null,
  () => RefInnerCompatible(),
);

final ClassSpec _refOuterCompatibleSpec = ClassSpec(
  RefOuterCompatible,
  false,
  true,
  [
    FieldSpec(
      'inner1',
      const TypeSpec(RefInnerCompatible, ObjType.STRUCT, true, false, null, []),
      true,
      true,
      (Object inst) => (inst as RefOuterCompatible).inner1,
      (Object inst, dynamic v) =>
          (inst as RefOuterCompatible).inner1 = v as RefInnerCompatible?,
      trackingRef: true,
    ),
    FieldSpec(
      'inner2',
      const TypeSpec(RefInnerCompatible, ObjType.STRUCT, true, false, null, []),
      true,
      true,
      (Object inst) => (inst as RefOuterCompatible).inner2,
      (Object inst, dynamic v) =>
          (inst as RefOuterCompatible).inner2 = v as RefInnerCompatible?,
      trackingRef: true,
    ),
  ],
  null,
  () => RefOuterCompatible(),
);

class CircularRefStruct {
  String name = '';
  CircularRefStruct? selfRef;
}

final ClassSpec _circularRefStructSpec = ClassSpec(
  CircularRefStruct,
  false,
  false,
  [
    FieldSpec(
      'name',
      const TypeSpec(String, ObjType.STRING, false, true, null, []),
      true,
      true,
      (Object inst) => (inst as CircularRefStruct).name,
      (Object inst, dynamic v) =>
          (inst as CircularRefStruct).name = v as String,
    ),
    FieldSpec(
      'self_ref',
      const TypeSpec(CircularRefStruct, ObjType.STRUCT, true, true, null, []),
      true,
      true,
      (Object inst) => (inst as CircularRefStruct).selfRef,
      (Object inst, dynamic v) =>
          (inst as CircularRefStruct).selfRef = v as CircularRefStruct?,
      trackingRef: true,
    ),
  ],
  null,
  () => CircularRefStruct(),
);

final Map<Type, ClassSpec> _structSpecByType = <Type, ClassSpec>{
  TwoEnumFieldStructEvolution: _twoEnumFieldStructEvolutionSpec,
  RefOverrideElement: _refOverrideElementSpec,
  RefOverrideContainer: _refOverrideContainerSpec,
  NullableComprehensiveCompatible: _nullableComprehensiveCompatibleSpec,
  Item: _itemSpec,
  SimpleStruct: _simpleStructSpec,
  Item1: _item1Spec,
  StructWithList: _structWithListSpec,
  StructWithMap: _structWithMapSpec,
  VersionCheckStruct: _versionCheckStructSpec,
  OneStringFieldStruct: _oneStringFieldStructSpec,
  TwoStringFieldStruct: _twoStringFieldStructSpec,
  OneEnumFieldStruct: _oneEnumFieldStructSpec,
  TwoEnumFieldStruct: _twoEnumFieldStructSpec,
  NullableComprehensiveSchemaConsistent:
      _nullableComprehensiveSchemaConsistentSpec,
  RefInnerSchemaConsistent: _refInnerSchemaConsistentSpec,
  RefOuterSchemaConsistent: _refOuterSchemaConsistentSpec,
  RefInnerCompatible: _refInnerCompatibleSpec,
  RefOuterCompatible: _refOuterCompatibleSpec,
  CircularRefStruct: _circularRefStructSpec,
};

final Map<Type, EnumSpec> _enumSpecByType = <Type, EnumSpec>{
  TestEnum: _testEnumSpec,
  Color: _colorSpec,
};

void _registerStructType(
  Fory fory,
  Type type, {
  int? typeId,
  String? namespace,
  String? typename,
}) {
  final ClassSpec? spec = _structSpecByType[type];
  if (spec == null) {
    throw StateError('No struct spec registered for $type');
  }
  fory.registerStruct(
    spec,
    typeId: typeId,
    namespace: namespace,
    typename: typename,
  );
}

void _registerEnumType(
  Fory fory,
  Type type, {
  int? typeId,
  String? namespace,
  String? typename,
}) {
  final EnumSpec? spec = _enumSpecByType[type];
  if (spec == null) {
    throw StateError('No enum spec registered for $type');
  }
  fory.registerEnum(
    spec,
    typeId: typeId,
    namespace: namespace,
    typename: typename,
  );
}

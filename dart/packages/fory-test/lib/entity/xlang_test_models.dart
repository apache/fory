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

import 'package:fory/fory.dart';

part '../generated/xlang_test_models.g.dart';

bool _xlangSpecLookupReady = false;

void _ensureXlangSpecLookupReady() {
  if (_xlangSpecLookupReady) {
    return;
  }
  SpecLookup.register($TestEnum);
  SpecLookup.register($TwoEnumFieldStructEvolution);
  SpecLookup.register($RefOverrideElement);
  SpecLookup.register($RefOverrideContainer);
  SpecLookup.register($NullableComprehensiveCompatible);
  SpecLookup.register($Color);
  SpecLookup.register($Item);
  SpecLookup.register($SimpleStruct);
  SpecLookup.register($Item1);
  SpecLookup.register($StructWithList);
  SpecLookup.register($StructWithMap);
  SpecLookup.register($VersionCheckStruct);
  SpecLookup.register($OneStringFieldStruct);
  SpecLookup.register($TwoStringFieldStruct);
  SpecLookup.register($OneEnumFieldStruct);
  SpecLookup.register($TwoEnumFieldStruct);
  SpecLookup.register($NullableComprehensiveSchemaConsistent);
  SpecLookup.register($RefInnerSchemaConsistent);
  SpecLookup.register($RefOuterSchemaConsistent);
  SpecLookup.register($RefInnerCompatible);
  SpecLookup.register($RefOuterCompatible);
  SpecLookup.register($CircularRefStruct);
  _xlangSpecLookupReady = true;
}

void registerXlangStruct(
  Fory fory,
  Type type, {
  int? typeId,
  String? namespace,
  String? typename,
}) {
  _ensureXlangSpecLookupReady();
  fory.registerStruct(
    type,
    typeId: typeId,
    namespace: namespace,
    typename: typename,
  );
}

void registerXlangEnum(
  Fory fory,
  Type type, {
  int? typeId,
  String? namespace,
  String? typename,
}) {
  _ensureXlangSpecLookupReady();
  fory.registerEnum(
    type,
    typeId: typeId,
    namespace: namespace,
    typename: typename,
  );
}

@foryEnum
enum TestEnum {
  valueA,
  valueB,
  valueC,
}

@foryClass
class TwoEnumFieldStructEvolution {
  TestEnum f1 = TestEnum.valueA;

  @ForyKey(includeFromFory: false)
  TestEnum f2 = TestEnum.valueA;
}

@foryClass
class RefOverrideElement {
  Int32 id = Int32(0);
  String name = '';
}

@foryClass
class RefOverrideContainer {
  List<RefOverrideElement> listField = <RefOverrideElement>[];
  Map<String, RefOverrideElement> mapField = <String, RefOverrideElement>{};
}

@foryClass
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

  double? nullableDouble1;
  Float32? nullableFloat1;
  bool? nullableBool1;
  int? nullableLong1;
  Int32? nullableInt1;

  String? nullableString2;
  String stringField = '';
  List<String?> listField = <String?>[];
  List<String?>? nullableList2;
  Set<String?>? nullableSet2;
  Set<String?> setField = <String?>{};
  Map<String?, String?> mapField = <String?, String?>{};
  Map<String?, String?>? nullableMap2;

  void normalizeForCompatibleRoundTrip() {
    nullableDouble1 ??= 0.0;
    nullableFloat1 ??= Float32(0);
    nullableBool1 ??= false;
    nullableLong1 ??= 0;
    nullableInt1 ??= Int32(0);
    nullableString2 ??= '';
    nullableList2 ??= <String>[];
    nullableSet2 ??= <String>{};
    nullableMap2 ??= <String, String>{};
  }
}

@foryEnum
enum Color {
  green,
  red,
  blue,
  white,
}

@foryClass
class Item {
  String name = '';
}

@foryClass
class SimpleStruct {
  Map<Int32?, double?> f1 = <Int32?, double?>{};
  Int32 f2 = Int32(0);
  Item f3 = Item();
  String f4 = '';
  Color f5 = Color.green;
  List<String> f6 = <String>[];
  Int32 f7 = Int32(0);
  Int32 f8 = Int32(0);
  Int32 last = Int32(0);
}

@foryClass
class Item1 {
  Int32 f1 = Int32(0);
  Int32 f2 = Int32(0);
  Int32 f3 = Int32(0);
  Int32 f4 = Int32(0);
  Int32 f5 = Int32(0);
  Int32 f6 = Int32(0);
}

@foryClass
class StructWithList {
  List<String?> items = <String?>[];
}

@foryClass
class StructWithMap {
  Map<String?, String?> data = <String?, String?>{};
}

@foryClass
class VersionCheckStruct {
  Int32 f1 = Int32(0);
  String? f2 = '';
  double f3 = 0.0;
}

@foryClass
class OneStringFieldStruct {
  String? f1 = '';
}

@foryClass
class TwoStringFieldStruct {
  String f1 = '';
  String f2 = '';
}

@foryClass
class OneEnumFieldStruct {
  TestEnum f1 = TestEnum.valueA;
}

@foryClass
class TwoEnumFieldStruct {
  TestEnum f1 = TestEnum.valueA;
  TestEnum f2 = TestEnum.valueA;
}

@foryClass
class NullableComprehensiveSchemaConsistent {
  Int8 byteField = Int8(0);
  Int16 shortField = Int16(0);
  Int32 intField = Int32(0);
  int longField = 0;
  Float32 floatField = Float32(0);
  double doubleField = 0.0;
  bool boolField = false;
  String stringField = '';
  List<String?> listField = <String?>[];
  Set<String?> setField = <String?>{};
  Map<String?, String?> mapField = <String?, String?>{};
  Int32? nullableInt;
  int? nullableLong;
  Float32? nullableFloat;
  double? nullableDouble;
  bool? nullableBool;
  String? nullableString;
  List<String?>? nullableList;
  Set<String?>? nullableSet;
  Map<String?, String?>? nullableMap;
}

@foryClass
class RefInnerSchemaConsistent {
  Int32 id = Int32(0);
  String name = '';
}

@foryClass
class RefOuterSchemaConsistent {
  @ForyKey(ref: true)
  RefInnerSchemaConsistent? inner1;

  @ForyKey(ref: true)
  RefInnerSchemaConsistent? inner2;
}

@foryClass
class RefInnerCompatible {
  Int32 id = Int32(0);
  String name = '';
}

@foryClass
class RefOuterCompatible {
  @ForyKey(ref: true)
  RefInnerCompatible? inner1;

  @ForyKey(ref: true)
  RefInnerCompatible? inner2;
}

@foryClass
class CircularRefStruct {
  String name = '';

  @ForyKey(ref: true)
  CircularRefStruct? selfRef;
}

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

library;

import 'package:fory/fory.dart';

import 'xlang_test_manual.dart' as manual;

part 'xlang_test_models.fory.dart';

void registerXlangType(
  Fory fory,
  Type type, {
  int? id,
  String? namespace,
  String? typeName,
}) {
  if (manual.registerXlangManualType(
    fory,
    type,
    id: id,
    namespace: namespace,
    typeName: typeName,
  )) {
    return;
  }
  XlangTestModelsFory.register(
    fory,
    type,
    id: id,
    namespace: namespace,
    typeName: typeName,
  );
}

@ForyStruct()
enum Color {
  green,
  red,
  blue,
  white,
}

@ForyStruct()
enum TestEnum {
  valueA,
  valueB,
  valueC,
}

@ForyStruct()
class TwoEnumFieldStructEvolution {
  TwoEnumFieldStructEvolution();

  TestEnum f1 = TestEnum.valueA;
  TestEnum f2 = TestEnum.valueA;
}

@ForyStruct()
class Item {
  Item();

  String name = '';
}

@ForyStruct()
class SimpleStruct {
  SimpleStruct();

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

@ForyStruct()
class EvolvingOverrideStruct {
  EvolvingOverrideStruct();

  String f1 = '';
}

@ForyStruct(evolving: false)
class FixedOverrideStruct {
  FixedOverrideStruct();

  String f1 = '';
}

@ForyStruct()
class Item1 {
  Item1();

  Int32 f1 = Int32(0);
  Int32 f2 = Int32(0);
  Int32 f3 = Int32(0);
  Int32 f4 = Int32(0);
  Int32 f5 = Int32(0);
  Int32 f6 = Int32(0);
}

final class Union2 {
  const Union2._(this.index, this.value);

  final int index;
  final Object value;

  factory Union2.ofString(String value) => Union2._(0, value);

  factory Union2.ofInt64(int value) => Union2._(1, value);

  factory Union2.of(int index, Object value) => Union2._(index, value);

  bool get isString => index == 0;

  bool get isInt64 => index == 1;

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is Union2 && other.index == index && other.value == value;

  @override
  int get hashCode => Object.hash(index, value);
}

@ForyStruct()
class StructWithUnion2 {
  StructWithUnion2();

  Union2 union = Union2.ofString('');
}

@ForyStruct()
class StructWithList {
  StructWithList();

  List<String?> items = <String?>[];
}

@ForyStruct()
class StructWithMap {
  StructWithMap();

  Map<String?, String?> data = <String?, String?>{};
}

@ForyStruct()
class MyStruct {
  MyStruct();

  @Int32Type()
  int id = 0;
}

final class MyExt {
  MyExt([this.id = 0]);

  int id;

  @override
  bool operator ==(Object other) =>
      identical(this, other) || other is MyExt && other.id == id;

  @override
  int get hashCode => id.hashCode;
}

@ForyStruct()
class MyWrapper {
  MyWrapper();

  Color color = Color.white;
  MyExt myExt = MyExt();
  MyStruct myStruct = MyStruct();
}

@ForyStruct()
class EmptyWrapper {
  EmptyWrapper();
}

@ForyStruct()
class VersionCheckStruct {
  VersionCheckStruct();

  @Int32Type()
  int f1 = 0;

  @ForyField(nullable: true)
  String? f2;

  double f3 = 0;
}

abstract interface class Animal {
  int get age;
}

@ForyStruct()
class Dog implements Animal {
  Dog();

  @override
  @Int32Type()
  int age = 0;

  @ForyField(nullable: true)
  String? name;
}

@ForyStruct()
class Cat implements Animal {
  Cat();

  @Int32Type()
  @override
  int age = 0;

  @Int32Type()
  int lives = 0;
}

@ForyStruct()
class AnimalListHolder {
  AnimalListHolder();

  List<Animal> animals = <Animal>[];
}

@ForyStruct()
class AnimalMapHolder {
  AnimalMapHolder();

  Map<String, Animal> animalMap = <String, Animal>{};
}

@ForyStruct()
class EmptyStruct {
  EmptyStruct();
}

@ForyStruct()
class OneStringFieldStruct {
  OneStringFieldStruct();

  @ForyField(nullable: true)
  String? f1;
}

@ForyStruct()
class TwoStringFieldStruct {
  TwoStringFieldStruct();

  String f1 = '';
  String f2 = '';
}

@ForyStruct()
class OneEnumFieldStruct {
  OneEnumFieldStruct();

  TestEnum f1 = TestEnum.valueA;
}

@ForyStruct()
class TwoEnumFieldStruct {
  TwoEnumFieldStruct();

  TestEnum f1 = TestEnum.valueA;
  TestEnum f2 = TestEnum.valueA;
}

@ForyStruct()
class NullableComprehensiveSchemaConsistent {
  NullableComprehensiveSchemaConsistent();

  Int8 byteField = Int8(0);
  Int16 shortField = Int16(0);
  Int32 intField = Int32(0);
  int longField = 0;
  Float32 floatField = Float32(0);
  double doubleField = 0;
  bool boolField = false;
  String stringField = '';
  List<String> listField = <String>[];
  Set<String> setField = <String>{};
  Map<String, String> mapField = <String, String>{};

  @ForyField(nullable: true)
  Int32? nullableInt;

  @ForyField(nullable: true)
  int? nullableLong;

  @ForyField(nullable: true)
  Float32? nullableFloat;

  @ForyField(nullable: true)
  double? nullableDouble;

  @ForyField(nullable: true)
  bool? nullableBool;

  @ForyField(nullable: true)
  String? nullableString;

  @ForyField(nullable: true)
  List<String>? nullableList;

  @ForyField(nullable: true)
  Set<String>? nullableSet;

  @ForyField(nullable: true)
  Map<String, String>? nullableMap;
}

@ForyStruct()
class NullableComprehensiveCompatible {
  NullableComprehensiveCompatible();

  Int8 byteField = Int8(0);
  Int16 shortField = Int16(0);
  Int32 intField = Int32(0);
  int longField = 0;
  Float32 floatField = Float32(0);
  double doubleField = 0;
  bool boolField = false;

  Int32 boxedInt = Int32(0);
  int boxedLong = 0;
  Float32 boxedFloat = Float32(0);
  double boxedDouble = 0;
  bool boxedBool = false;

  String stringField = '';
  List<String> listField = <String>[];
  Set<String> setField = <String>{};
  Map<String, String> mapField = <String, String>{};

  Int32 nullableInt1 = Int32(0);
  int nullableLong1 = 0;
  Float32 nullableFloat1 = Float32(0);
  double nullableDouble1 = 0;
  bool nullableBool1 = false;
  String nullableString2 = '';
  List<String> nullableList2 = <String>[];
  Set<String> nullableSet2 = <String>{};
  Map<String, String> nullableMap2 = <String, String>{};
}

@ForyStruct()
class RefInnerSchemaConsistent {
  RefInnerSchemaConsistent();

  @Int32Type()
  int id = 0;
  String name = '';
}

@ForyStruct()
class RefOuterSchemaConsistent {
  RefOuterSchemaConsistent();

  @ForyField(ref: true, nullable: true, dynamic: false)
  RefInnerSchemaConsistent? inner1;

  @ForyField(ref: true, nullable: true, dynamic: false)
  RefInnerSchemaConsistent? inner2;
}

@ForyStruct()
class RefInnerCompatible {
  RefInnerCompatible();

  @Int32Type()
  int id = 0;
  String name = '';
}

@ForyStruct()
class RefOuterCompatible {
  RefOuterCompatible();

  @ForyField(ref: true, nullable: true)
  RefInnerCompatible? inner1;

  @ForyField(ref: true, nullable: true)
  RefInnerCompatible? inner2;
}

@ForyStruct()
class RefOverrideElement {
  RefOverrideElement();

  Int32 id = Int32(0);
  String name = '';
}

class RefOverrideContainer {
  RefOverrideContainer();

  List<RefOverrideElement> listField = <RefOverrideElement>[];
  Map<String, RefOverrideElement> mapField = <String, RefOverrideElement>{};
}

@ForyStruct()
class CircularRefStruct {
  CircularRefStruct();

  String name = '';

  @ForyField(ref: true, nullable: true)
  CircularRefStruct? selfRef;
}

@ForyStruct()
class UnsignedSchemaConsistent {
  UnsignedSchemaConsistent();

  UInt8 u8Field = UInt8(0);
  UInt16 u16Field = UInt16(0);

  @Uint32Type(compress: true)
  UInt32 u32VarField = UInt32(0);

  @Uint32Type(compress: false)
  UInt32 u32FixedField = UInt32(0);

  @Uint64Type(encoding: LongEncoding.varint)
  int u64VarField = 0;

  @Uint64Type(encoding: LongEncoding.fixed)
  int u64FixedField = 0;

  @Uint64Type(encoding: LongEncoding.tagged)
  int u64TaggedField = 0;

  @ForyField(nullable: true)
  UInt8? u8NullableField;

  @ForyField(nullable: true)
  UInt16? u16NullableField;

  @ForyField(nullable: true)
  @Uint32Type(compress: true)
  UInt32? u32VarNullableField;

  @ForyField(nullable: true)
  @Uint32Type(compress: false)
  UInt32? u32FixedNullableField;

  @ForyField(nullable: true)
  @Uint64Type(encoding: LongEncoding.varint)
  int? u64VarNullableField;

  @ForyField(nullable: true)
  @Uint64Type(encoding: LongEncoding.fixed)
  int? u64FixedNullableField;

  @ForyField(nullable: true)
  @Uint64Type(encoding: LongEncoding.tagged)
  int? u64TaggedNullableField;
}

@ForyStruct()
class UnsignedSchemaConsistentSimple {
  UnsignedSchemaConsistentSimple();

  @Uint64Type(encoding: LongEncoding.tagged)
  int u64Tagged = 0;

  @ForyField(nullable: true)
  @Uint64Type(encoding: LongEncoding.tagged)
  int? u64TaggedNullable;
}

@ForyStruct()
class UnsignedSchemaCompatible {
  UnsignedSchemaCompatible();

  @ForyField(nullable: true)
  UInt8? u8Field1;

  @ForyField(nullable: true)
  UInt16? u16Field1;

  @ForyField(nullable: true)
  @Uint32Type(compress: true)
  UInt32? u32VarField1;

  @ForyField(nullable: true)
  @Uint32Type(compress: false)
  UInt32? u32FixedField1;

  @ForyField(nullable: true)
  @Uint64Type(encoding: LongEncoding.varint)
  int? u64VarField1;

  @ForyField(nullable: true)
  @Uint64Type(encoding: LongEncoding.fixed)
  int? u64FixedField1;

  @ForyField(nullable: true)
  @Uint64Type(encoding: LongEncoding.tagged)
  int? u64TaggedField1;

  UInt8 u8Field2 = UInt8(0);
  UInt16 u16Field2 = UInt16(0);

  @Uint32Type(compress: true)
  UInt32 u32VarField2 = UInt32(0);

  @Uint32Type(compress: false)
  UInt32 u32FixedField2 = UInt32(0);

  @Uint64Type(encoding: LongEncoding.varint)
  int u64VarField2 = 0;

  @Uint64Type(encoding: LongEncoding.fixed)
  int u64FixedField2 = 0;

  @Uint64Type(encoding: LongEncoding.tagged)
  int u64TaggedField2 = 0;
}

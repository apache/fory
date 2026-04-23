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
import 'package:fory/src/serializer/compatible_struct_metadata.dart';
import 'package:test/test.dart';

part 'unsigned_serializer_test.fory.dart';

const int _uint32Midpoint = 0x80000000;
const int _jsSafeIntMax = 9007199254740991;
const int _jsUnsafeInt = 9007199254740992;
final Uint64 _uint64Midpoint = Uint64.parseHex('8000000000000000');
final Uint64 _uint64Max = Uint64.parseHex('ffffffffffffffff');

@ForyStruct()
class UnsignedFields {
  UnsignedFields();

  @Uint8Type()
  int u8 = 0;

  @Uint16Type()
  int u16 = 0;

  @Uint32Type(compress: true)
  int u32Var = 0;

  @Uint32Type(compress: false)
  int u32Fixed = 0;

  @Uint64Type(encoding: LongEncoding.varint)
  Uint64 u64Var = Uint64(0);

  @Uint64Type(encoding: LongEncoding.fixed)
  Uint64 u64Fixed = Uint64(0);

  @Uint64Type(encoding: LongEncoding.tagged)
  Uint64 u64Tagged = Uint64(0);

  @Uint64Type(encoding: LongEncoding.varint)
  int u64VarInt = 0;

  @Uint64Type(encoding: LongEncoding.fixed)
  int u64FixedInt = 0;

  @Uint64Type(encoding: LongEncoding.tagged)
  int u64TaggedInt = 0;

  @Uint8Type()
  int? u8Nullable;

  @Uint16Type()
  int? u16Nullable;

  @Uint32Type(compress: true)
  int? u32VarNullable;

  @Uint32Type(compress: false)
  int? u32FixedNullable;

  @Uint64Type(encoding: LongEncoding.varint)
  Uint64? u64VarNullable;

  @Uint64Type(encoding: LongEncoding.fixed)
  Uint64? u64FixedNullable;

  @Uint64Type(encoding: LongEncoding.tagged)
  Uint64? u64TaggedNullable;

  @Uint64Type(encoding: LongEncoding.varint)
  int? u64VarIntNullable;
}

@ForyStruct()
class UnsignedMetadataReader {
  UnsignedMetadataReader();

  @Uint8Type()
  int u8 = 0;

  int extra = 42;
}

@ForyStruct()
class UnsignedIntFieldsReader {
  UnsignedIntFieldsReader();

  @Uint64Type(encoding: LongEncoding.varint)
  int u64Var = 0;

  @Uint64Type(encoding: LongEncoding.fixed)
  int u64Fixed = 0;

  @Uint64Type(encoding: LongEncoding.tagged)
  int u64Tagged = 0;
}

@ForyStruct()
class UnsignedWrapperFields {
  UnsignedWrapperFields();

  @Uint64Type(encoding: LongEncoding.varint)
  Uint64 u64Var = Uint64(0);

  @Uint64Type(encoding: LongEncoding.fixed)
  Uint64 u64Fixed = Uint64(0);

  @Uint64Type(encoding: LongEncoding.tagged)
  Uint64 u64Tagged = Uint64(0);
}

@ForyStruct()
class UnsignedWrapperAsIntFields {
  UnsignedWrapperAsIntFields();

  @Uint64Type(encoding: LongEncoding.varint)
  int u64Var = 0;

  @Uint64Type(encoding: LongEncoding.fixed)
  int u64Fixed = 0;

  @Uint64Type(encoding: LongEncoding.tagged)
  int u64Tagged = 0;
}

UnsignedFields _uint64ReadMismatchPayload(String encoding) {
  final value = _smallUnsignedFields()
    ..u64Var = Uint64(1)
    ..u64Fixed = Uint64(1)
    ..u64Tagged = Uint64(1);
  switch (encoding) {
    case 'varint':
      value.u64Var = _uint64Max;
    case 'fixed':
      value.u64Fixed = _uint64Max;
    case 'tagged':
      value.u64Tagged = _uint64Max;
    default:
      throw ArgumentError.value(encoding, 'encoding');
  }
  return value;
}

UnsignedWrapperFields _schemaUint64ReadMismatchPayload(String encoding) {
  final value = UnsignedWrapperFields()
    ..u64Var = Uint64(1)
    ..u64Fixed = Uint64(1)
    ..u64Tagged = Uint64(1);
  switch (encoding) {
    case 'varint':
      value.u64Var = _uint64Max;
    case 'fixed':
      value.u64Fixed = _uint64Max;
    case 'tagged':
      value.u64Tagged = _uint64Max;
    default:
      throw ArgumentError.value(encoding, 'encoding');
  }
  return value;
}

void _registerUnsignedFields(Fory fory) {
  UnsignedSerializerTestFory.register(
    fory,
    UnsignedFields,
    namespace: 'test',
    typeName: 'UnsignedFields',
  );
}

void _registerUnsignedMetadataReader(Fory fory) {
  UnsignedSerializerTestFory.register(
    fory,
    UnsignedMetadataReader,
    namespace: 'test',
    typeName: 'UnsignedFields',
  );
}

void _registerUnsignedIntFieldsReader(Fory fory) {
  UnsignedSerializerTestFory.register(
    fory,
    UnsignedIntFieldsReader,
    namespace: 'test',
    typeName: 'UnsignedFields',
  );
}

void _registerUnsignedWrapperFields(Fory fory) {
  UnsignedSerializerTestFory.register(
    fory,
    UnsignedWrapperFields,
    namespace: 'test',
    typeName: 'UnsignedSchemaUint64Fields',
  );
}

void _registerUnsignedWrapperAsIntFields(Fory fory) {
  UnsignedSerializerTestFory.register(
    fory,
    UnsignedWrapperAsIntFields,
    namespace: 'test',
    typeName: 'UnsignedSchemaUint64Fields',
  );
}

UnsignedFields _smallUnsignedFields() {
  return UnsignedFields()
    ..u8 = 0
    ..u16 = 1
    ..u32Var = 0
    ..u32Fixed = 1
    ..u64Var = Uint64(0)
    ..u64Fixed = Uint64(1)
    ..u64Tagged = Uint64(0)
    ..u64VarInt = 0
    ..u64FixedInt = 1
    ..u64TaggedInt = 0
    ..u8Nullable = 1
    ..u16Nullable = 0
    ..u32VarNullable = 1
    ..u32FixedNullable = 0
    ..u64VarNullable = Uint64(1)
    ..u64FixedNullable = Uint64(0)
    ..u64TaggedNullable = Uint64(1)
    ..u64VarIntNullable = 1;
}

UnsignedFields _taggedBoundaryUnsignedFields() {
  return UnsignedFields()
    ..u8 = 0x7f
    ..u16 = 0x7fff
    ..u32Var = 0x7fffffff
    ..u32Fixed = 0x80000000
    ..u64Var = Uint64(0x7fffffff)
    ..u64Fixed = Uint64(0x80000000)
    ..u64Tagged = Uint64(0x7fffffff)
    ..u64VarInt = 0x7fffffff
    ..u64FixedInt = 0x80000000
    ..u64TaggedInt = 0x7fffffff
    ..u8Nullable = 0x80
    ..u16Nullable = 0x8000
    ..u32VarNullable = 0x80000000
    ..u32FixedNullable = 0x7fffffff
    ..u64VarNullable = Uint64(0x80000000)
    ..u64FixedNullable = Uint64(0x7fffffff)
    ..u64TaggedNullable = Uint64(0x80000000)
    ..u64VarIntNullable = 0x80000000;
}

UnsignedFields _midpointUnsignedFields() {
  return UnsignedFields()
    ..u8 = 0x80
    ..u16 = 0x8000
    ..u32Var = _uint32Midpoint
    ..u32Fixed = _uint32Midpoint
    ..u64Var = _uint64Midpoint
    ..u64Fixed = _uint64Midpoint
    ..u64Tagged = _uint64Midpoint
    ..u64VarInt = _jsSafeIntMax
    ..u64FixedInt = _jsSafeIntMax
    ..u64TaggedInt = _jsSafeIntMax
    ..u8Nullable = 0x80
    ..u16Nullable = 0x8000
    ..u32VarNullable = _uint32Midpoint
    ..u32FixedNullable = _uint32Midpoint
    ..u64VarNullable = _uint64Midpoint
    ..u64FixedNullable = _uint64Midpoint
    ..u64TaggedNullable = _uint64Midpoint
    ..u64VarIntNullable = _jsSafeIntMax;
}

UnsignedFields _maxUnsignedFields() {
  return UnsignedFields()
    ..u8 = 0xff
    ..u16 = 0xffff
    ..u32Var = 0xffffffff
    ..u32Fixed = 0xffffffff
    ..u64Var = _uint64Max
    ..u64Fixed = _uint64Max
    ..u64Tagged = _uint64Max
    ..u64VarInt = _jsSafeIntMax
    ..u64FixedInt = _jsSafeIntMax
    ..u64TaggedInt = _jsSafeIntMax
    ..u8Nullable = 0xff
    ..u16Nullable = 0xffff
    ..u32VarNullable = 0xffffffff
    ..u32FixedNullable = 0xffffffff
    ..u64VarNullable = _uint64Max
    ..u64FixedNullable = _uint64Max
    ..u64TaggedNullable = _uint64Max
    ..u64VarIntNullable = _jsSafeIntMax;
}

UnsignedFields _nullUnsignedFields() {
  return UnsignedFields()
    ..u8 = 1
    ..u16 = 2
    ..u32Var = 3
    ..u32Fixed = 4
    ..u64Var = Uint64(5)
    ..u64Fixed = Uint64(6)
    ..u64Tagged = Uint64(7)
    ..u64VarInt = 5
    ..u64FixedInt = 6
    ..u64TaggedInt = 7
    ..u8Nullable = null
    ..u16Nullable = null
    ..u32VarNullable = null
    ..u32FixedNullable = null
    ..u64VarNullable = null
    ..u64FixedNullable = null
    ..u64TaggedNullable = null
    ..u64VarIntNullable = null;
}

void _expectUnsignedFieldsEqual(
    UnsignedFields actual, UnsignedFields expected) {
  expect(actual.u8, equals(expected.u8));
  expect(actual.u16, equals(expected.u16));
  expect(actual.u32Var, equals(expected.u32Var));
  expect(actual.u32Fixed, equals(expected.u32Fixed));
  expect(actual.u64Var, equals(expected.u64Var));
  expect(actual.u64Fixed, equals(expected.u64Fixed));
  expect(actual.u64Tagged, equals(expected.u64Tagged));
  expect(actual.u64VarInt, equals(expected.u64VarInt));
  expect(actual.u64FixedInt, equals(expected.u64FixedInt));
  expect(actual.u64TaggedInt, equals(expected.u64TaggedInt));
  expect(actual.u8Nullable, equals(expected.u8Nullable));
  expect(actual.u16Nullable, equals(expected.u16Nullable));
  expect(actual.u32VarNullable, equals(expected.u32VarNullable));
  expect(actual.u32FixedNullable, equals(expected.u32FixedNullable));
  expect(actual.u64VarNullable, equals(expected.u64VarNullable));
  expect(actual.u64FixedNullable, equals(expected.u64FixedNullable));
  expect(actual.u64TaggedNullable, equals(expected.u64TaggedNullable));
  expect(actual.u64VarIntNullable, equals(expected.u64VarIntNullable));
}

int _remoteFieldTypeId(Object value, String identifier) {
  final remoteTypeDef = CompatibleStructMetadata.remoteTypeDefFor(value);
  expect(remoteTypeDef, isNotNull);
  final field = remoteTypeDef!.fields.firstWhere(
    (field) => field.identifier == identifier,
  );
  return field.fieldType.typeId;
}

bool _remoteFieldNullable(Object value, String identifier) {
  final remoteTypeDef = CompatibleStructMetadata.remoteTypeDefFor(value);
  expect(remoteTypeDef, isNotNull);
  final field = remoteTypeDef!.fields.firstWhere(
    (field) => field.identifier == identifier,
  );
  return field.fieldType.nullable;
}

void main() {
  group('unsigned generated fields', () {
    test('round trips small, threshold, midpoint, max, and null cases', () {
      final fory = Fory();
      _registerUnsignedFields(fory);

      for (final value in <UnsignedFields>[
        _smallUnsignedFields(),
        _taggedBoundaryUnsignedFields(),
        _midpointUnsignedFields(),
        _maxUnsignedFields(),
        _nullUnsignedFields(),
      ]) {
        final roundTrip =
            fory.deserialize<UnsignedFields>(fory.serialize(value));
        _expectUnsignedFieldsEqual(roundTrip, value);
      }
    });

    test('compatible mode round trips unsigned encoding edge cases', () {
      final fory = Fory(compatible: true);
      _registerUnsignedFields(fory);

      for (final value in <UnsignedFields>[
        _smallUnsignedFields(),
        _taggedBoundaryUnsignedFields(),
        _midpointUnsignedFields(),
        _maxUnsignedFields(),
        _nullUnsignedFields(),
      ]) {
        final roundTrip =
            fory.deserialize<UnsignedFields>(fory.serialize(value));
        _expectUnsignedFieldsEqual(roundTrip, value);
      }
    });

    test('compatible metadata records unsigned wire types and nullability', () {
      final writer = Fory(compatible: true);
      final reader = Fory(compatible: true);
      _registerUnsignedFields(writer);
      _registerUnsignedMetadataReader(reader);

      final roundTrip = reader.deserialize<UnsignedMetadataReader>(
        writer.serialize(_maxUnsignedFields()),
      );
      expect(roundTrip.u8, equals(0xff));
      expect(roundTrip.extra, equals(42));

      expect(_remoteFieldTypeId(roundTrip, 'u8'), equals(TypeIds.uint8));
      expect(_remoteFieldTypeId(roundTrip, 'u16'), equals(TypeIds.uint16));
      expect(
          _remoteFieldTypeId(roundTrip, 'u32_var'), equals(TypeIds.varUint32));
      expect(
          _remoteFieldTypeId(roundTrip, 'u32_fixed'), equals(TypeIds.uint32));
      expect(
          _remoteFieldTypeId(roundTrip, 'u64_var'), equals(TypeIds.varUint64));
      expect(
          _remoteFieldTypeId(roundTrip, 'u64_fixed'), equals(TypeIds.uint64));
      expect(
        _remoteFieldTypeId(roundTrip, 'u64_tagged'),
        equals(TypeIds.taggedUint64),
      );
      expect(
        _remoteFieldTypeId(roundTrip, 'u64_var_int'),
        equals(TypeIds.varUint64),
      );
      expect(
        _remoteFieldTypeId(roundTrip, 'u64_fixed_int'),
        equals(TypeIds.uint64),
      );
      expect(
        _remoteFieldTypeId(roundTrip, 'u64_tagged_int'),
        equals(TypeIds.taggedUint64),
      );

      expect(_remoteFieldNullable(roundTrip, 'u8'), isFalse);
      expect(_remoteFieldNullable(roundTrip, 'u16'), isFalse);
      expect(_remoteFieldNullable(roundTrip, 'u8_nullable'), isTrue);
      expect(_remoteFieldNullable(roundTrip, 'u16_nullable'), isTrue);
      expect(_remoteFieldNullable(roundTrip, 'u32_var_nullable'), isTrue);
      expect(_remoteFieldNullable(roundTrip, 'u32_fixed_nullable'), isTrue);
      expect(_remoteFieldNullable(roundTrip, 'u64_var_nullable'), isTrue);
      expect(_remoteFieldNullable(roundTrip, 'u64_fixed_nullable'), isTrue);
      expect(_remoteFieldNullable(roundTrip, 'u64_tagged_nullable'), isTrue);
      expect(_remoteFieldNullable(roundTrip, 'u64_var_int_nullable'), isTrue);
    });

    test('web rejects JS-unsafe uint64 Dart int fields', () {
      final cases = <({String name, UnsignedFields value})>[
        (
          name: 'varint',
          value: _smallUnsignedFields()..u64VarInt = _jsUnsafeInt,
        ),
        (
          name: 'fixed',
          value: _smallUnsignedFields()..u64FixedInt = _jsUnsafeInt,
        ),
        (
          name: 'tagged',
          value: _smallUnsignedFields()..u64TaggedInt = _jsUnsafeInt,
        ),
        (
          name: 'nullable varint',
          value: _smallUnsignedFields()..u64VarIntNullable = _jsUnsafeInt,
        ),
      ];

      for (final testCase in cases) {
        for (final compatible in <bool>[false, true]) {
          final fory = Fory(compatible: compatible);
          _registerUnsignedFields(fory);
          if (identical(1, 1.0)) {
            expect(
              () => fory.serialize(testCase.value),
              throwsA(isA<StateError>()),
              reason: '${testCase.name}, compatible=$compatible',
            );
          } else {
            final roundTrip = fory.deserialize<UnsignedFields>(
              fory.serialize(testCase.value),
            );
            _expectUnsignedFieldsEqual(roundTrip, testCase.value);
          }
        }
      }
    });

    test('web rejects negative uint64 Dart int fields', () {
      if (!identical(1, 1.0)) {
        return;
      }

      final cases = <({String name, UnsignedFields value})>[
        (
          name: 'varint',
          value: _smallUnsignedFields()..u64VarInt = -1,
        ),
        (
          name: 'fixed',
          value: _smallUnsignedFields()..u64FixedInt = -1,
        ),
        (
          name: 'tagged',
          value: _smallUnsignedFields()..u64TaggedInt = -1,
        ),
        (
          name: 'nullable varint',
          value: _smallUnsignedFields()..u64VarIntNullable = -1,
        ),
      ];

      for (final testCase in cases) {
        for (final compatible in <bool>[false, true]) {
          final fory = Fory(compatible: compatible);
          _registerUnsignedFields(fory);
          expect(
            () => fory.serialize(testCase.value),
            throwsA(isA<StateError>()),
            reason: '${testCase.name}, compatible=$compatible',
          );
        }
      }
    });

    test(
        'schema-consistent Dart int uint64 readers reject full-range wrapper payloads',
        () {
      final writer = Fory();
      final reader = Fory();
      _registerUnsignedWrapperFields(writer);
      _registerUnsignedWrapperAsIntFields(reader);

      for (final encoding in <String>['varint', 'fixed', 'tagged']) {
        expect(
          () => reader.deserialize<UnsignedWrapperAsIntFields>(
            writer.serialize(_schemaUint64ReadMismatchPayload(encoding)),
          ),
          throwsA(isA<StateError>()),
          reason: encoding,
        );
      }
    });

    test(
        'compatible Dart int uint64 readers reject full-range wrapper payloads',
        () {
      final writer = Fory(compatible: true);
      final reader = Fory(compatible: true);
      _registerUnsignedFields(writer);
      _registerUnsignedIntFieldsReader(reader);

      for (final encoding in <String>['varint', 'fixed', 'tagged']) {
        expect(
          () => reader.deserialize<UnsignedIntFieldsReader>(
            writer.serialize(_uint64ReadMismatchPayload(encoding)),
          ),
          throwsA(isA<StateError>()),
          reason: encoding,
        );
      }
    });
  });
}

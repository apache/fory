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

part 'signed_serializer_test.fory.dart';

const int _jsSafeIntMax = 9007199254740991;
const int _jsSafeIntMin = -9007199254740991;
const int _jsUnsafeInt = 9007199254740992;
final Int64 _int64Min = Int64.parseHex('8000000000000000');
final Int64 _int64Max = Int64.parseHex('7fffffffffffffff');

@ForyStruct()
class SignedFields {
  SignedFields();

  int plainInt = 0;

  @Int32Type(compress: true)
  int i32Var = 0;

  @Int32Type(compress: false)
  int i32Fixed = 0;

  @Int64Type(encoding: LongEncoding.varint)
  int i64VarInt = 0;

  @Int64Type(encoding: LongEncoding.fixed)
  int i64FixedInt = 0;

  @Int64Type(encoding: LongEncoding.tagged)
  int i64TaggedInt = 0;

  Int64 i64Default = Int64(0);

  @Int64Type(encoding: LongEncoding.varint)
  Int64 i64Var = Int64(0);

  @Int64Type(encoding: LongEncoding.fixed)
  Int64 i64Fixed = Int64(0);

  @Int64Type(encoding: LongEncoding.tagged)
  Int64 i64Tagged = Int64(0);

  @Int64Type(encoding: LongEncoding.varint)
  int? optionalI64VarInt;

  @Int32Type(compress: true)
  int? optionalI32Var;

  @Int32Type(compress: false)
  int? optionalI32Fixed;

  @Int64Type(encoding: LongEncoding.fixed)
  Int64? optionalI64Fixed;

  @Int64Type(encoding: LongEncoding.tagged)
  Int64? optionalI64Tagged;
}

@ForyStruct()
class SignedMetadataReader {
  SignedMetadataReader();

  int plainInt = 0;
}

@ForyStruct()
class SignedIntFieldsReader {
  SignedIntFieldsReader();

  @Int64Type(encoding: LongEncoding.varint)
  int i64Var = 0;

  @Int64Type(encoding: LongEncoding.fixed)
  int i64Fixed = 0;

  @Int64Type(encoding: LongEncoding.tagged)
  int i64Tagged = 0;
}

void _registerSignedFields(Fory fory) {
  SignedSerializerTestFory.register(
    fory,
    SignedFields,
    namespace: 'test',
    typeName: 'SignedFields',
  );
}

void _registerSignedMetadataReader(Fory fory) {
  SignedSerializerTestFory.register(
    fory,
    SignedMetadataReader,
    namespace: 'test',
    typeName: 'SignedFields',
  );
}

void _registerSignedIntFieldsReader(Fory fory) {
  SignedSerializerTestFory.register(
    fory,
    SignedIntFieldsReader,
    namespace: 'test',
    typeName: 'SignedFields',
  );
}

SignedFields _smallSignedFields() {
  return SignedFields()
    ..plainInt = -1
    ..i32Var = -64
    ..i32Fixed = 63
    ..i64VarInt = -64
    ..i64FixedInt = 63
    ..i64TaggedInt = 0x3fffffff
    ..i64Default = Int64(-1)
    ..i64Var = Int64(1)
    ..i64Fixed = Int64(-0x40000000)
    ..i64Tagged = Int64(0x3fffffff)
    ..optionalI32Var = -128
    ..optionalI32Fixed = 0x40000000
    ..optionalI64VarInt = -128
    ..optionalI64Fixed = Int64(0x40000000)
    ..optionalI64Tagged = Int64(-0x40000001);
}

SignedFields _jsSafeBoundarySignedFields() {
  return SignedFields()
    ..plainInt = _jsSafeIntMin
    ..i32Var = -0x80000000
    ..i32Fixed = 0x7fffffff
    ..i64VarInt = _jsSafeIntMax
    ..i64FixedInt = _jsSafeIntMin
    ..i64TaggedInt = _jsSafeIntMax
    ..i64Default = Int64(_jsSafeIntMin)
    ..i64Var = Int64(_jsSafeIntMax)
    ..i64Fixed = Int64(_jsSafeIntMin)
    ..i64Tagged = Int64(_jsSafeIntMax)
    ..optionalI32Var = -0x80000000
    ..optionalI32Fixed = 0x7fffffff
    ..optionalI64VarInt = _jsSafeIntMin
    ..optionalI64Fixed = Int64(_jsSafeIntMax)
    ..optionalI64Tagged = Int64(_jsSafeIntMin);
}

SignedFields _fullRangeWrapperSignedFields() {
  return SignedFields()
    ..plainInt = 0
    ..i32Var = -0x80000000
    ..i32Fixed = 0x7fffffff
    ..i64VarInt = -0x40000001
    ..i64FixedInt = 0x40000000
    ..i64TaggedInt = -0x40000001
    ..i64Default = _int64Min
    ..i64Var = _int64Max
    ..i64Fixed = _int64Min
    ..i64Tagged = _int64Max
    ..optionalI32Var = -0x80000000
    ..optionalI32Fixed = 0x7fffffff
    ..optionalI64VarInt = 0x40000000
    ..optionalI64Fixed = _int64Max
    ..optionalI64Tagged = _int64Min;
}

SignedFields _nullSignedFields() {
  return SignedFields()
    ..plainInt = 1
    ..i32Var = 2
    ..i32Fixed = 3
    ..i64VarInt = 2
    ..i64FixedInt = 3
    ..i64TaggedInt = 4
    ..i64Default = Int64(5)
    ..i64Var = Int64(6)
    ..i64Fixed = Int64(7)
    ..i64Tagged = Int64(8)
    ..optionalI32Var = null
    ..optionalI32Fixed = null
    ..optionalI64VarInt = null
    ..optionalI64Fixed = null
    ..optionalI64Tagged = null;
}

SignedFields _int64ReadMismatchPayload(String encoding) {
  final value = _smallSignedFields()
    ..i64Var = Int64(1)
    ..i64Fixed = Int64(1)
    ..i64Tagged = Int64(1);
  switch (encoding) {
    case 'varint':
      value.i64Var = _int64Min;
    case 'fixed':
      value.i64Fixed = _int64Min;
    case 'tagged':
      value.i64Tagged = _int64Min;
    default:
      throw ArgumentError.value(encoding, 'encoding');
  }
  return value;
}

void _expectSignedFieldsEqual(SignedFields actual, SignedFields expected) {
  expect(actual.plainInt, equals(expected.plainInt));
  expect(actual.i32Var, equals(expected.i32Var));
  expect(actual.i32Fixed, equals(expected.i32Fixed));
  expect(actual.i64VarInt, equals(expected.i64VarInt));
  expect(actual.i64FixedInt, equals(expected.i64FixedInt));
  expect(actual.i64TaggedInt, equals(expected.i64TaggedInt));
  expect(actual.i64Default, equals(expected.i64Default));
  expect(actual.i64Var, equals(expected.i64Var));
  expect(actual.i64Fixed, equals(expected.i64Fixed));
  expect(actual.i64Tagged, equals(expected.i64Tagged));
  expect(actual.optionalI32Var, equals(expected.optionalI32Var));
  expect(actual.optionalI32Fixed, equals(expected.optionalI32Fixed));
  expect(actual.optionalI64VarInt, equals(expected.optionalI64VarInt));
  expect(actual.optionalI64Fixed, equals(expected.optionalI64Fixed));
  expect(actual.optionalI64Tagged, equals(expected.optionalI64Tagged));
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
  group('signed generated fields', () {
    test('round trips int and Int64 encoding edge cases', () {
      final fory = Fory();
      _registerSignedFields(fory);

      for (final value in <SignedFields>[
        _smallSignedFields(),
        _jsSafeBoundarySignedFields(),
        _fullRangeWrapperSignedFields(),
        _nullSignedFields(),
      ]) {
        final roundTrip = fory.deserialize<SignedFields>(fory.serialize(value));
        _expectSignedFieldsEqual(roundTrip, value);
      }
    });

    test('compatible mode round trips Int32 and Int64 encoding edge cases', () {
      final fory = Fory(compatible: true);
      _registerSignedFields(fory);

      for (final value in <SignedFields>[
        _smallSignedFields(),
        _jsSafeBoundarySignedFields(),
        _fullRangeWrapperSignedFields(),
        _nullSignedFields(),
      ]) {
        final roundTrip = fory.deserialize<SignedFields>(fory.serialize(value));
        _expectSignedFieldsEqual(roundTrip, value);
      }
    });

    test('native schema-consistent Int64 varint int fields decode signed min',
        () {
      if (identical(1, 1.0)) {
        return;
      }

      final signedMin = _int64Min.toInt();
      final fory = Fory();
      _registerSignedFields(fory);

      final value = _smallSignedFields()
        ..i64VarInt = signedMin
        ..optionalI64VarInt = signedMin;
      final roundTrip = fory.deserialize<SignedFields>(fory.serialize(value));
      expect(roundTrip.i64VarInt, equals(signedMin));
      expect(roundTrip.optionalI64VarInt, equals(signedMin));

      final buffer = Buffer();
      final writeCursor = GeneratedWriteCursor.reserve(buffer, 10);
      writeCursor.writeVarInt64FromInt(signedMin);
      writeCursor.finish();

      final readCursor =
          GeneratedReadCursor.start(Buffer.wrap(buffer.toBytes()));
      expect(readCursor.readVarInt64AsInt(), equals(signedMin));
      readCursor.finish();
    });

    test('compatible metadata records signed wire types and nullability', () {
      final writer = Fory(compatible: true);
      final reader = Fory(compatible: true);
      _registerSignedFields(writer);
      _registerSignedMetadataReader(reader);

      final roundTrip = reader.deserialize<SignedMetadataReader>(
        writer.serialize(_fullRangeWrapperSignedFields()),
      );
      expect(roundTrip.plainInt, equals(0));

      expect(
        _remoteFieldTypeId(roundTrip, 'plain_int'),
        equals(TypeIds.varInt64),
      );
      expect(
        _remoteFieldTypeId(roundTrip, 'i32_var'),
        equals(TypeIds.varInt32),
      );
      expect(
        _remoteFieldTypeId(roundTrip, 'i32_fixed'),
        equals(TypeIds.int32),
      );
      expect(
        _remoteFieldTypeId(roundTrip, 'i64_var_int'),
        equals(TypeIds.varInt64),
      );
      expect(
        _remoteFieldTypeId(roundTrip, 'i64_fixed_int'),
        equals(TypeIds.int64),
      );
      expect(
        _remoteFieldTypeId(roundTrip, 'i64_tagged_int'),
        equals(TypeIds.taggedInt64),
      );
      expect(
        _remoteFieldTypeId(roundTrip, 'i64_default'),
        equals(TypeIds.varInt64),
      );
      expect(
        _remoteFieldTypeId(roundTrip, 'i64_var'),
        equals(TypeIds.varInt64),
      );
      expect(_remoteFieldTypeId(roundTrip, 'i64_fixed'), equals(TypeIds.int64));
      expect(
        _remoteFieldTypeId(roundTrip, 'i64_tagged'),
        equals(TypeIds.taggedInt64),
      );

      expect(_remoteFieldNullable(roundTrip, 'plain_int'), isFalse);
      expect(_remoteFieldNullable(roundTrip, 'optional_i32_var'), isTrue);
      expect(_remoteFieldNullable(roundTrip, 'optional_i32_fixed'), isTrue);
      expect(_remoteFieldNullable(roundTrip, 'optional_i64_var_int'), isTrue);
      expect(_remoteFieldNullable(roundTrip, 'optional_i64_fixed'), isTrue);
      expect(_remoteFieldNullable(roundTrip, 'optional_i64_tagged'), isTrue);
    });

    test('web rejects JS-unsafe Dart int fields instead of corrupting bytes',
        () {
      final cases = <({String name, SignedFields value})>[
        (
          name: 'plain int',
          value: _smallSignedFields()..plainInt = _jsUnsafeInt,
        ),
        (
          name: 'varint',
          value: _smallSignedFields()..i64VarInt = _jsUnsafeInt,
        ),
        (
          name: 'fixed',
          value: _smallSignedFields()..i64FixedInt = _jsUnsafeInt,
        ),
        (
          name: 'tagged',
          value: _smallSignedFields()..i64TaggedInt = _jsUnsafeInt,
        ),
        (
          name: 'nullable varint',
          value: _smallSignedFields()..optionalI64VarInt = _jsUnsafeInt,
        ),
      ];

      for (final testCase in cases) {
        for (final compatible in <bool>[false, true]) {
          final fory = Fory(compatible: compatible);
          _registerSignedFields(fory);
          if (identical(1, 1.0)) {
            expect(
              () => fory.serialize(testCase.value),
              throwsA(isA<StateError>()),
              reason: '${testCase.name}, compatible=$compatible',
            );
          } else {
            final roundTrip = fory.deserialize<SignedFields>(
              fory.serialize(testCase.value),
            );
            _expectSignedFieldsEqual(roundTrip, testCase.value);
          }
        }
      }
    });

    test('web rejects JS-unsafe root and dynamic Dart ints', () {
      final fory = Fory();

      if (identical(1, 1.0)) {
        expect(() => fory.serialize(_jsUnsafeInt), throwsA(isA<StateError>()));
        expect(
          () => fory.serialize(<Object>[_jsUnsafeInt]),
          throwsA(isA<StateError>()),
        );
      } else {
        expect(
          fory.deserialize<int>(fory.serialize(_jsUnsafeInt)),
          equals(_jsUnsafeInt),
        );
        expect(
          fory.deserialize<List<dynamic>>(
            fory.serialize(<Object>[_jsUnsafeInt]),
          ),
          equals(<Object>[_jsUnsafeInt]),
        );
      }
    });

    test('web rejects full-range Int64 wrapper payloads read as Dart int', () {
      final writer = Fory(compatible: true);
      final reader = Fory(compatible: true);
      _registerSignedFields(writer);
      _registerSignedIntFieldsReader(reader);

      for (final encoding in <String>['varint', 'fixed', 'tagged']) {
        if (identical(1, 1.0)) {
          expect(
            () => reader.deserialize<SignedIntFieldsReader>(
              writer.serialize(_int64ReadMismatchPayload(encoding)),
            ),
            throwsA(isA<StateError>()),
            reason: encoding,
          );
        } else {
          final roundTrip = reader.deserialize<SignedIntFieldsReader>(
            writer.serialize(_int64ReadMismatchPayload(encoding)),
          );
          switch (encoding) {
            case 'varint':
              expect(roundTrip.i64Var, equals(_int64Min.toInt()));
            case 'fixed':
              expect(roundTrip.i64Fixed, equals(_int64Min.toInt()));
            case 'tagged':
              expect(roundTrip.i64Tagged, equals(_int64Min.toInt()));
          }
        }
      }
    });
  });
}

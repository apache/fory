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

import 'dart:typed_data';

import 'package:fory/fory.dart';
import 'package:test/test.dart';

part 'numeric_wrapper_test.fory.dart';

@ForyStruct()
class NumericWrappersEnvelope {
  NumericWrappersEnvelope();

  Int8 i8 = Int8(0);
  Int16 i16 = Int16(0);
  Int32 i32 = Int32(0);
  Uint8 u8 = Uint8(0);
  Uint16 u16 = Uint16(0);
  Uint32 u32 = Uint32(0);
  Uint64 u64 = Uint64(0);
  Float16 half = Float16(0);
  Bfloat16 brain = Bfloat16(0);
  Float32 single = Float32(0);
  Int8? optionalI8;
  Uint64? optionalU64;
  Float16? optionalHalf;
  Bfloat16? optionalBrain;
  Float32? optionalSingle;
}

void _registerNumericWrappers(Fory fory) {
  NumericWrapperTestFory.register(
    fory,
    NumericWrappersEnvelope,
    namespace: 'test',
    typeName: 'NumericWrappersEnvelope',
  );
}

T _roundTrip<T>(Fory fory, T value) =>
    fory.deserialize<T>(fory.serialize(value));

NumericWrappersEnvelope _sampleEnvelope() {
  return NumericWrappersEnvelope()
    ..i8 = Int8(-127)
    ..i16 = Int16(0x7fff)
    ..i32 = Int32(-2147483648)
    ..u8 = Uint8(0xff)
    ..u16 = Uint16(0xffff)
    ..u32 = Uint32(0xffffffff)
    ..u64 = Uint64(0xffffffffffffffff)
    ..half = Float16.fromBits(0x3555)
    ..brain = Bfloat16.fromBits(0x3eab)
    ..single = Float32.fromBits(0x40490fdb)
    ..optionalI8 = Int8(126)
    ..optionalU64 = Uint64(0x8000000000000000)
    ..optionalHalf = Float16.fromBits(0x8000)
    ..optionalBrain = Bfloat16.fromBits(0x8000)
    ..optionalSingle = Float32.fromBits(0x80000000);
}

void _expectEnvelopeEquals(
  NumericWrappersEnvelope actual,
  NumericWrappersEnvelope expected,
) {
  expect(actual.i8, equals(expected.i8));
  expect(actual.i16, equals(expected.i16));
  expect(actual.i32, equals(expected.i32));
  expect(actual.u8, equals(expected.u8));
  expect(actual.u16, equals(expected.u16));
  expect(actual.u32, equals(expected.u32));
  expect(actual.u64, equals(expected.u64));
  expect(actual.half.toBits(), equals(expected.half.toBits()));
  expect(actual.brain.toBits(), equals(expected.brain.toBits()));
  expect(actual.single.toBits(), equals(expected.single.toBits()));
  expect(actual.optionalI8, equals(expected.optionalI8));
  expect(actual.optionalU64, equals(expected.optionalU64));
  expect(
      actual.optionalHalf?.toBits(), equals(expected.optionalHalf?.toBits()));
  expect(
      actual.optionalBrain?.toBits(), equals(expected.optionalBrain?.toBits()));
  expect(
    actual.optionalSingle?.toBits(),
    equals(expected.optionalSingle?.toBits()),
  );
}

void main() {
  group('numeric wrappers', () {
    test('signed integer wrappers normalize arithmetic and bitwise operations',
        () {
      expect(Int8(127) + 1, equals(Int8(-128)));
      expect(Int8(-128) - 1, equals(Int8(127)));
      expect(Int8(12) * Int8(11), equals(Int8(-124)));
      expect(Int8(7) ~/ 2, equals(Int8(3)));
      expect(Int8(7) / 2, equals(3.5));
      expect(Int8(0x0f) & Int8(0x33), equals(Int8(0x03)));
      expect(Int8(0x0f) | Int8(0x33), equals(Int8(0x3f)));
      expect(Int8(0x0f) ^ Int8(0x33), equals(Int8(0x3c)));
      expect(Int8(0x40) >> 1, equals(Int8(0x20)));
      expect(Int8(0x40) << 2, equals(Int8(0x00)));
      expect(Int8(-1) < 0, isTrue);
      expect(Int8(10) >= Int8(10), isTrue);

      expect(Int16(0x7fff) + 1, equals(Int16(-0x8000)));
      expect(Int16(-0x8000) - 1, equals(Int16(0x7fff)));
      expect(Int32(0x7fffffff) + 1, equals(Int32(-0x80000000)));
      expect(Int32(-0x80000000) - 1, equals(Int32(0x7fffffff)));
      expect(Int32(0x12345678) >> 4, equals(Int32(0x01234567)));
      expect(Int32(123).toInt(), equals(123));
    });

    test(
        'unsigned integer wrappers normalize arithmetic and bitwise operations',
        () {
      expect(Uint8(0xff) + 1, equals(Uint8(0)));
      expect(Uint8(0) - 1, equals(Uint8(0xff)));
      expect(Uint8(0xf0) >> 4, equals(Uint8(0x0f)));
      expect(Uint8(0xf0) >>> 4, equals(Uint8(0x0f)));
      expect(Uint8(0xff) >> 1, equals(Uint8(0x7f)));
      expect(Uint8(0xff) >>> 1, equals(Uint8(0x7f)));
      expect(-Uint8(1), equals(Uint8(0xff)));

      expect(Uint16(0xffff) + 1, equals(Uint16(0)));
      expect(Uint16(0) - 1, equals(Uint16(0xffff)));
      expect(Uint16(0xffff) >> 1, equals(Uint16(0x7fff)));
      expect(Uint16(0xffff) >>> 1, equals(Uint16(0x7fff)));

      expect(Uint32(0xffffffff) + 1, equals(Uint32(0)));
      expect(Uint32(0) - 1, equals(Uint32(0xffffffff)));
      expect(
          Uint32(0xf0f0f0f0) & Uint32(0x0ff00ff0), equals(Uint32(0x00f000f0)));
      expect(Uint32(0xffffffff) >> 1, equals(Uint32(0x7fffffff)));
      expect(Uint32(0xffffffff) >>> 1, equals(Uint32(0x7fffffff)));

      expect(Uint64(0xffffffffffffffff) + 1, equals(Uint64(0)));
      expect(Uint64(0) - 1, equals(Uint64(0xffffffffffffffff)));
      expect(
          Uint64(0x123456789abcdef0) >> 4, equals(Uint64(0x0123456789abcdef)));
      expect(
          Uint64(0xffffffffffffffff) >> 1, equals(Uint64(0x7fffffffffffffff)));
      expect(
          Uint64(0xffffffffffffffff) >>> 1, equals(Uint64(0x7fffffffffffffff)));
      expect(Uint64(0xff).toInt(), equals(0xff));
    });

    test('Float16 arithmetic rounds back to binary16 precision', () {
      expect((Float16(1.5) + 2).toBits(), equals(Float16(3.5).toBits()));
      expect(
          (Float16(7) - Float16(2.5)).toBits(), equals(Float16(4.5).toBits()));
      expect((Float16(1) / 3).toBits(), equals(Float16(1 / 3).toBits()));
      expect((Float16(7.5) % 2).toBits(), equals(Float16(1.5).toBits()));
      expect(Float16(7.5) ~/ 2, equals(3));
      expect((-Float16(1.5)).toBits(), equals(Float16(-1.5).toBits()));
      expect(Float16(-1) < 0, isTrue);
      expect(Float16(3.5) >= Float16(3.5), isTrue);
      expect(Float16.fromBits(0x3555).value, closeTo(0.333251953125, 1e-12));
    });

    test('Bfloat16 arithmetic rounds back to bfloat16 precision', () {
      expect((Bfloat16(1.5) + 2).toBits(), equals(Bfloat16(3.5).toBits()));
      expect(
        (Bfloat16(7) - Bfloat16(2.5)).toBits(),
        equals(Bfloat16(4.5).toBits()),
      );
      expect((Bfloat16(1) / 3).toBits(), equals(Bfloat16(1 / 3).toBits()));
      expect((Bfloat16(7.5) % 2).toBits(), equals(Bfloat16(1.5).toBits()));
      expect(Bfloat16(7.5) ~/ 2, equals(3));
      expect((-Bfloat16(1.5)).toBits(), equals(Bfloat16(-1.5).toBits()));
      expect(Bfloat16(-1) < 0, isTrue);
      expect(Bfloat16(3.5) >= Bfloat16(3.5), isTrue);
      expect(Bfloat16.fromBits(0x3eab).value, closeTo(0.333984375, 1e-12));
    });

    test(
        'Bfloat16.fromDouble rounds directly from float64 and preserves NaN sign payload bits',
        () {
      final subnormalSource = ByteData(8)
        ..setUint64(0, 0x37da834f7e281cc1, Endian.little);
      final trickySubnormal = subnormalSource.getFloat64(0, Endian.little);
      final nanSource = ByteData(8)
        ..setUint64(0, 0xfff123456789abcd, Endian.little);
      final payloadNaN = nanSource.getFloat64(0, Endian.little);
      final convertedNaN = Bfloat16.fromDouble(payloadNaN);

      expect(Bfloat16.fromDouble(trickySubnormal).toBits(), equals(0x0007));
      expect(convertedNaN.toBits() & 0x8000, equals(0x8000));
      expect(convertedNaN.toBits() & 0x7f80, equals(0x7f80));
      expect(convertedNaN.toBits() & 0x0040, equals(0x0040));
      expect(convertedNaN.toBits() & 0x003f, isNot(0));
    });

    test('Float32 arithmetic rounds back to binary32 precision', () {
      expect((Float32(1.5) + 2).toBits(), equals(Float32(3.5).toBits()));
      expect(
          (Float32(7) - Float32(2.5)).toBits(), equals(Float32(4.5).toBits()));
      expect((Float32(1) / 3).toBits(), equals(Float32(1 / 3).toBits()));
      expect((Float32(7.5) % 2).toBits(), equals(Float32(1.5).toBits()));
      expect(Float32(7.5) ~/ 2, equals(3));
      expect((-Float32(1.5)).toBits(), equals(Float32(-1.5).toBits()));
      expect(Float32(-1) < 0, isTrue);
      expect(Float32(3.5) >= Float32(3.5), isTrue);
      expect(Float32.fromBits(0x3eaaaaab).value, closeTo(1 / 3, 1e-8));
    });

    test('round-trips root fixed-width wrappers', () {
      final fory = Fory();

      expect(_roundTrip<Int8>(fory, Int8(-129)), equals(Int8(127)));
      expect(_roundTrip<Int16>(fory, Int16(-32769)), equals(Int16(32767)));
      expect(
        _roundTrip<Int32>(fory, Int32(0x80000000)),
        equals(Int32(-0x80000000)),
      );
      expect(_roundTrip<Uint8>(fory, Uint8(-1)), equals(Uint8(0xff)));
      expect(_roundTrip<Uint16>(fory, Uint16(-1)), equals(Uint16(0xffff)));
      expect(_roundTrip<Uint32>(fory, Uint32(-1)), equals(Uint32(0xffffffff)));
      expect(
        _roundTrip<Uint64>(fory, Uint64(-1)),
        equals(Uint64(0xffffffffffffffff)),
      );
    });

    test('round-trips root Float16 payloads with exact bits', () {
      final fory = Fory();
      final cases = <Float16>[
        Float16.fromBits(0x0000),
        Float16.fromBits(0x8000),
        Float16.fromBits(0x3555),
        Float16.fromBits(0x7c00),
        Float16.fromBits(0x7e00),
      ];

      for (final value in cases) {
        expect(
            _roundTrip<Float16>(fory, value).toBits(), equals(value.toBits()));
      }
    });

    test('round-trips root Bfloat16 payloads with exact bits', () {
      final fory = Fory();
      final cases = <Bfloat16>[
        Bfloat16.fromBits(0x0000),
        Bfloat16.fromBits(0x8000),
        Bfloat16.fromBits(0x3eab),
        Bfloat16.fromBits(0x7f80),
        Bfloat16.fromBits(0x7fc0),
      ];

      for (final value in cases) {
        expect(
          _roundTrip<Bfloat16>(fory, value).toBits(),
          equals(value.toBits()),
        );
      }
    });

    test('round-trips root Float32 payloads with exact bits', () {
      final fory = Fory();
      final cases = <Float32>[
        Float32.fromBits(0x00000000),
        Float32.fromBits(0x80000000),
        Float32.fromBits(0x3eaaaaab),
        Float32.fromBits(0x7f800000),
        Float32.fromBits(0x7fc00000),
      ];

      for (final value in cases) {
        expect(
            _roundTrip<Float32>(fory, value).toBits(), equals(value.toBits()));
      }
    });

    test(
        'round-trips generated numeric wrapper fields in schema-consistent mode',
        () {
      final fory = Fory();
      _registerNumericWrappers(fory);

      final value = _sampleEnvelope();
      final roundTrip =
          fory.deserialize<NumericWrappersEnvelope>(fory.serialize(value));

      _expectEnvelopeEquals(roundTrip, value);
    });

    test('round-trips generated numeric wrapper fields in compatible mode', () {
      final fory = Fory(compatible: true);
      _registerNumericWrappers(fory);

      final value = _sampleEnvelope();
      final roundTrip =
          fory.deserialize<NumericWrappersEnvelope>(fory.serialize(value));

      _expectEnvelopeEquals(roundTrip, value);
    });

    test('supports null optional numeric wrapper fields', () {
      final fory = Fory();
      _registerNumericWrappers(fory);

      final roundTrip = _roundTrip<NumericWrappersEnvelope>(
        fory,
        NumericWrappersEnvelope(),
      );

      expect(roundTrip.optionalI8, isNull);
      expect(roundTrip.optionalU64, isNull);
      expect(roundTrip.optionalHalf, isNull);
      expect(roundTrip.optionalBrain, isNull);
      expect(roundTrip.optionalSingle, isNull);
    });
  });
}

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

part 'scalar_and_typed_array_serializer_test.fory.dart';

Timestamp _timestamp(int seconds, int nanoseconds) =>
    Timestamp(Int64(seconds), nanoseconds);

final Int64 _int64Min = Int64.parseHex('8000000000000000');
final Int64 _int64Max = Int64.parseHex('7fffffffffffffff');
final Uint64 _uint64HighBit = Uint64.parseHex('8000000000000000');
final Uint64 _uint64Max = Uint64.parseHex('ffffffffffffffff');

@ForyStruct()
class ScalarAndArrayEnvelope {
  ScalarAndArrayEnvelope();

  Uint8List binary = Uint8List(0);
  Int8List int8s = Int8List(0);
  Int16List int16s = Int16List(0);
  Int32List int32s = Int32List(0);
  Int64List int64s = Int64List(0);
  Uint16List uint16s = Uint16List(0);
  Uint32List uint32s = Uint32List(0);
  Uint64List uint64s = Uint64List(0);
  Float16List float16s = Float16List(0);
  Bfloat16List bfloat16s = Bfloat16List(0);
  Float32List float32s = Float32List(0);
  Float64List float64s = Float64List(0);
  List<bool> flags = <bool>[];
  Float16 half = Float16(0);
  Bfloat16 brain = Bfloat16(0);
  Float32 single = Float32(0);
  LocalDate date = const LocalDate(1970, 1, 1);
  Timestamp timestamp = _timestamp(0, 0);
}

@ForyStruct()
class ExplicitArrayEnvelope {
  ExplicitArrayEnvelope();

  @ArrayField(element: BoolType())
  BoolList flags = BoolList(0);

  @ArrayField(element: Int32Type())
  Int32List denseIds = Int32List(0);

  @ForyField(type: ArrayType(element: Uint8Type()))
  Uint8List pixels = Uint8List(0);

  List<int> normalList = <int>[];
}

void _registerScalarTypes(Fory fory) {
  ScalarAndTypedArraySerializerTestFory.register(
    fory,
    ScalarAndArrayEnvelope,
    namespace: 'test',
    typeName: 'ScalarAndArrayEnvelope',
  );
  ScalarAndTypedArraySerializerTestFory.register(
    fory,
    ExplicitArrayEnvelope,
    namespace: 'test',
    typeName: 'ExplicitArrayEnvelope',
  );
}

T _roundTripRoot<T>(Fory fory, Object value) {
  return fory.deserialize<T>(fory.serialize(value));
}

ScalarAndArrayEnvelope _sampleEnvelope() {
  return ScalarAndArrayEnvelope()
    ..binary = Uint8List.fromList(<int>[0, 1, 2, 255])
    ..int8s = Int8List.fromList(<int>[-128, -1, 0, 127])
    ..int16s = Int16List.fromList(<int>[-32768, -1, 0, 32767])
    ..int32s = Int32List.fromList(<int>[-1, 0, 1, 123456789])
    ..int64s =
        Int64List.fromList(<Object>[-1, 0, 1, 1 << 40, _int64Min, _int64Max])
    ..uint16s = Uint16List.fromList(<int>[0, 1, 65535])
    ..uint32s = Uint32List.fromList(<int>[0, 1, 0x7fffffff])
    ..uint64s = Uint64List.fromList(
      <Object>[0, 1, 1 << 40, _uint64HighBit, _uint64Max],
    )
    ..float16s = Float16List.fromList(<Float16>[
      Float16.fromBits(0x8000),
      Float16.fromBits(0x3555),
      Float16.fromBits(0x7c00),
    ])
    ..bfloat16s = Bfloat16List.fromList(<Bfloat16>[
      Bfloat16.fromBits(0x8000),
      Bfloat16.fromBits(0x3eab),
      Bfloat16.fromBits(0x7f80),
    ])
    ..float32s = Float32List.fromList(<double>[-1.5, 0.0, 3.25])
    ..float64s = Float64List.fromList(<double>[-2.5, 0.0, 4.5])
    ..flags = <bool>[true, false, true, true]
    ..half = const Float16.fromBits(0x8000)
    ..brain = const Bfloat16.fromBits(0x7fc0)
    ..single = Float32(3.5)
    ..date = LocalDate.fromEpochDay(Int64(-1))
    ..timestamp = _timestamp(-123, 456789123);
}

void _expectUint8ListEquals(Uint8List actual, Uint8List expected) {
  expect(actual.toList(), orderedEquals(expected.toList()));
}

void _expectInt8ListEquals(Int8List actual, Int8List expected) {
  expect(actual.toList(), orderedEquals(expected.toList()));
}

void _expectInt16ListEquals(Int16List actual, Int16List expected) {
  expect(actual.toList(), orderedEquals(expected.toList()));
}

void _expectInt32ListEquals(Int32List actual, Int32List expected) {
  expect(actual.toList(), orderedEquals(expected.toList()));
}

void _expectInt64ListEquals(Int64List actual, Int64List expected) {
  expect(actual.toList(), orderedEquals(expected.toList()));
}

void _expectUint16ListEquals(Uint16List actual, Uint16List expected) {
  expect(actual.toList(), orderedEquals(expected.toList()));
}

void _expectUint32ListEquals(Uint32List actual, Uint32List expected) {
  expect(actual.toList(), orderedEquals(expected.toList()));
}

void _expectUint64ListEquals(Uint64List actual, Uint64List expected) {
  expect(actual.toList(), orderedEquals(expected.toList()));
}

void _expectFloat16ListEquals(Float16List actual, Float16List expected) {
  expect(
    actual.map((value) => value.toBits()).toList(),
    orderedEquals(expected.map((value) => value.toBits()).toList()),
  );
}

void _expectBfloat16ListEquals(Bfloat16List actual, Bfloat16List expected) {
  expect(
    actual.map((value) => value.toBits()).toList(),
    orderedEquals(expected.map((value) => value.toBits()).toList()),
  );
}

void _expectFloat32ListEquals(Float32List actual, Float32List expected) {
  expect(actual.toList(), orderedEquals(expected.toList()));
}

void _expectFloat64ListEquals(Float64List actual, Float64List expected) {
  expect(actual.toList(), orderedEquals(expected.toList()));
}

void main() {
  group('scalar and typed array serializers', () {
    test('round-trips Float16 edge cases', () {
      final fory = Fory();
      final cases = <Float16>[
        const Float16.fromBits(0x8000),
        const Float16.fromBits(0x7e00),
        Float16(double.infinity),
        Float16(-1.5),
      ];

      for (final value in cases) {
        final roundTrip = _roundTripRoot<Float16>(fory, value);
        expect(roundTrip.toBits(), equals(value.toBits()));
      }
    });

    test('round-trips Float32 edge cases', () {
      final fory = Fory();

      final negativeZero = _roundTripRoot<Float32>(fory, Float32(-0.0));
      final nanValue = _roundTripRoot<Float32>(fory, Float32(double.nan));
      final infinity = _roundTripRoot<Float32>(fory, Float32(double.infinity));
      final ordinary = _roundTripRoot<Float32>(fory, Float32(-1.5));

      expect(negativeZero.value, equals(0.0));
      expect(1 / negativeZero.value, equals(double.negativeInfinity));
      expect(nanValue.value.isNaN, isTrue);
      expect(infinity.value, equals(double.infinity));
      expect(ordinary, equals(Float32(-1.5)));
    });

    test('round-trips Bfloat16 edge cases', () {
      final fory = Fory();
      final cases = <Bfloat16>[
        const Bfloat16.fromBits(0x8000),
        const Bfloat16.fromBits(0x7fc0),
        Bfloat16(double.infinity),
        Bfloat16(-1.5),
      ];

      for (final value in cases) {
        final roundTrip = _roundTripRoot<Bfloat16>(fory, value);
        expect(roundTrip.toBits(), equals(value.toBits()));
      }
    });

    test('round-trips LocalDate and decodes root timestamps as DateTime', () {
      final fory = Fory();

      final beforeEpoch = _roundTripRoot<LocalDate>(
        fory,
        LocalDate.fromEpochDay(Int64(-1)),
      );
      final leapDay = _roundTripRoot<LocalDate>(
        fory,
        const LocalDate(2024, 2, 29),
      );
      final negativeTimestamp = _roundTripRoot<DateTime>(
        fory,
        _timestamp(-123, 456789000),
      );
      final fromDateTime = _roundTripRoot<DateTime>(
        fory,
        Timestamp.fromDateTime(DateTime.utc(2024, 1, 2, 3, 4, 5, 6, 700)),
      );

      expect(beforeEpoch, equals(LocalDate.fromEpochDay(Int64(-1))));
      expect(leapDay, equals(const LocalDate(2024, 2, 29)));
      expect(
        negativeTimestamp,
        equals(_timestamp(-123, 456789000).toDateTime()),
      );
      expect(
        fromDateTime,
        equals(DateTime.utc(2024, 1, 2, 3, 4, 5, 6, 700)),
      );
    });

    test('round-trips root typed arrays and bool arrays', () {
      final fory = Fory();

      _expectUint8ListEquals(
        _roundTripRoot<Uint8List>(fory, Uint8List.fromList(<int>[0, 1, 255])),
        Uint8List.fromList(<int>[0, 1, 255]),
      );
      _expectInt8ListEquals(
        _roundTripRoot<Int8List>(
          fory,
          Int8List.fromList(<int>[-128, -1, 0, 127]),
        ),
        Int8List.fromList(<int>[-128, -1, 0, 127]),
      );
      _expectInt16ListEquals(
        _roundTripRoot<Int16List>(
          fory,
          Int16List.fromList(<int>[-32768, -1, 0, 32767]),
        ),
        Int16List.fromList(<int>[-32768, -1, 0, 32767]),
      );
      _expectInt32ListEquals(
        _roundTripRoot<Int32List>(
          fory,
          Int32List.fromList(<int>[-1, 0, 1, 123456789]),
        ),
        Int32List.fromList(<int>[-1, 0, 1, 123456789]),
      );
      _expectInt64ListEquals(
        _roundTripRoot<Int64List>(
          fory,
          Int64List.fromList(<Object>[
            -1,
            0,
            1,
            1 << 40,
            _int64Min,
            _int64Max,
          ]),
        ),
        Int64List.fromList(<Object>[
          -1,
          0,
          1,
          1 << 40,
          _int64Min,
          _int64Max,
        ]),
      );
      _expectUint16ListEquals(
        _roundTripRoot<Uint16List>(
          fory,
          Uint16List.fromList(<int>[0, 1, 65535]),
        ),
        Uint16List.fromList(<int>[0, 1, 65535]),
      );
      _expectUint32ListEquals(
        _roundTripRoot<Uint32List>(
          fory,
          Uint32List.fromList(<int>[0, 1, 0x7fffffff]),
        ),
        Uint32List.fromList(<int>[0, 1, 0x7fffffff]),
      );
      _expectUint64ListEquals(
        _roundTripRoot<Uint64List>(
          fory,
          Uint64List.fromList(<Object>[
            0,
            1,
            1 << 40,
            _uint64HighBit,
            _uint64Max,
          ]),
        ),
        Uint64List.fromList(<Object>[
          0,
          1,
          1 << 40,
          _uint64HighBit,
          _uint64Max,
        ]),
      );
      _expectFloat16ListEquals(
        _roundTripRoot<Float16List>(
          fory,
          Float16List.fromList(<Float16>[
            Float16.fromBits(0x8000),
            Float16.fromBits(0x3555),
            Float16.fromBits(0x7c00),
          ]),
        ),
        Float16List.fromList(<Float16>[
          Float16.fromBits(0x8000),
          Float16.fromBits(0x3555),
          Float16.fromBits(0x7c00),
        ]),
      );
      _expectBfloat16ListEquals(
        _roundTripRoot<Bfloat16List>(
          fory,
          Bfloat16List.fromList(<Bfloat16>[
            Bfloat16.fromBits(0x8000),
            Bfloat16.fromBits(0x3eab),
            Bfloat16.fromBits(0x7f80),
          ]),
        ),
        Bfloat16List.fromList(<Bfloat16>[
          Bfloat16.fromBits(0x8000),
          Bfloat16.fromBits(0x3eab),
          Bfloat16.fromBits(0x7f80),
        ]),
      );
      _expectFloat32ListEquals(
        _roundTripRoot<Float32List>(
          fory,
          Float32List.fromList(<double>[-1.5, 0.0, 3.25]),
        ),
        Float32List.fromList(<double>[-1.5, 0.0, 3.25]),
      );
      _expectFloat64ListEquals(
        _roundTripRoot<Float64List>(
          fory,
          Float64List.fromList(<double>[-2.5, 0.0, 4.5]),
        ),
        Float64List.fromList(<double>[-2.5, 0.0, 4.5]),
      );
      expect(
        _roundTripRoot<Object?>(fory, <bool>[true, false, true]),
        orderedEquals(<bool>[true, false, true]),
      );
    });

    test('round-trips empty binary and typed array payloads', () {
      final fory = Fory();

      expect(_roundTripRoot<Uint8List>(fory, Uint8List(0)), isEmpty);
      expect(_roundTripRoot<Int8List>(fory, Int8List(0)), isEmpty);
      expect(_roundTripRoot<Float16List>(fory, Float16List(0)), isEmpty);
      expect(_roundTripRoot<Bfloat16List>(fory, Bfloat16List(0)), isEmpty);
      expect(_roundTripRoot<Float32List>(fory, Float32List(0)), isEmpty);
      expect(_roundTripRoot<Object?>(fory, <bool>[]), isEmpty);
    });

    test('Float16List and Bfloat16List expose contiguous typed-data views', () {
      final storage =
          Uint16List.fromList(<int>[0x3c00, 0x3555, 0x3f80, 0x7fc0]);

      final float16s = Float16List.view(storage.buffer, 0, 2);
      final bfloat16s = Bfloat16List.sublistView(storage, 2, 4);

      expect(float16s.offsetInBytes, equals(0));
      expect(float16s.lengthInBytes, equals(4));
      expect(float16s[0].toBits(), equals(0x3c00));
      expect(float16s[1].toBits(), equals(0x3555));

      float16s[1] = Float16.fromBits(0x4200);
      expect(storage[1], equals(0x4200));

      expect(bfloat16s.offsetInBytes, equals(4));
      expect(bfloat16s[0].toBits(), equals(0x3f80));
      expect(bfloat16s[1].toBits(), equals(0x7fc0));

      bfloat16s[0] = Bfloat16.fromBits(0xbf80);
      expect(storage[2], equals(0xbf80));
    });

    test('round-trips generated struct fields for arrays and scalars', () {
      final fory = Fory();
      _registerScalarTypes(fory);

      final value = _sampleEnvelope();
      final roundTrip = fory.deserialize<ScalarAndArrayEnvelope>(
        fory.serialize(value),
      );

      _expectUint8ListEquals(roundTrip.binary, value.binary);
      _expectInt8ListEquals(roundTrip.int8s, value.int8s);
      _expectInt16ListEquals(roundTrip.int16s, value.int16s);
      _expectInt32ListEquals(roundTrip.int32s, value.int32s);
      _expectInt64ListEquals(roundTrip.int64s, value.int64s);
      _expectUint16ListEquals(roundTrip.uint16s, value.uint16s);
      _expectUint32ListEquals(roundTrip.uint32s, value.uint32s);
      _expectUint64ListEquals(roundTrip.uint64s, value.uint64s);
      _expectFloat16ListEquals(roundTrip.float16s, value.float16s);
      _expectBfloat16ListEquals(roundTrip.bfloat16s, value.bfloat16s);
      _expectFloat32ListEquals(roundTrip.float32s, value.float32s);
      _expectFloat64ListEquals(roundTrip.float64s, value.float64s);
      expect(roundTrip.flags, orderedEquals(value.flags));
      expect(roundTrip.half.toBits(), equals(value.half.toBits()));
      expect(roundTrip.brain.toBits(), equals(value.brain.toBits()));
      expect(roundTrip.single, equals(value.single));
      expect(roundTrip.date, equals(value.date));
      expect(roundTrip.timestamp, equals(value.timestamp));
    });

    test('separates normal list fields from explicit dense array fields', () {
      final writer = Fory();
      final reader = Fory();
      _registerScalarTypes(writer);
      _registerScalarTypes(reader);

      final value = ExplicitArrayEnvelope()
        ..flags = BoolList.fromList(<bool>[true, false, true])
        ..denseIds = Int32List.fromList(<int>[1, -2, 3])
        ..pixels = Uint8List.fromList(<int>[1, 2, 255])
        ..normalList = <int>[7, 8, 9];
      final roundTrip = reader.deserialize<ExplicitArrayEnvelope>(
        writer.serialize(value),
      );

      expect(roundTrip.flags, orderedEquals(value.flags));
      expect(roundTrip.flags, isA<BoolList>());
      _expectInt32ListEquals(roundTrip.denseIds, value.denseIds);
      _expectUint8ListEquals(roundTrip.pixels, value.pixels);
      expect(roundTrip.normalList, orderedEquals(value.normalList));

      final fieldsByName = <String, int>{
        for (final field in _explicitArrayEnvelopeForyFieldInfo)
          field.identifier: field.fieldType.typeId,
      };
      expect(fieldsByName['flags'], equals(TypeIds.boolArray));
      expect(fieldsByName['dense_ids'], equals(TypeIds.int32Array));
      expect(fieldsByName['pixels'], equals(TypeIds.uint8Array));
      expect(fieldsByName['normal_list'], equals(TypeIds.list));
    });

    test('enforces maxBinarySize on write and read', () {
      final oversized = Uint8List.fromList(<int>[1, 2, 3, 4]);

      expect(
        () => Fory(maxBinarySize: 3).serialize(oversized),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('Binary payload exceeds 3 bytes.'),
          ),
        ),
      );

      final bytes = Fory().serialize(oversized);
      expect(
        () => Fory(maxBinarySize: 3).deserialize<Uint8List>(bytes),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('Binary payload exceeds 3 bytes.'),
          ),
        ),
      );
    });
  });
}

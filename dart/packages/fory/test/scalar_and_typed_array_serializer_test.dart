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
import 'package:fory/src/context/meta_string_reader.dart';
import 'package:fory/src/context/ref_reader.dart';
import 'package:fory/src/context/ref_writer.dart';
import 'package:fory/src/meta/field_info.dart';
import 'package:fory/src/meta/field_type.dart';
import 'package:fory/src/resolver/type_resolver.dart';
import 'package:fory/src/serializer/scalar_conversion.dart';
import 'package:fory/src/serializer/serializer_support.dart';
import 'package:test/test.dart';

part 'scalar_and_typed_array_serializer_test.fory.dart';

Timestamp _timestamp(int seconds, int nanoseconds) =>
    Timestamp(Int64(seconds), nanoseconds);

final Int64 _int64Min = Int64.parseHex('8000000000000000');
final Int64 _int64Max = Int64.parseHex('7fffffffffffffff');
final Uint64 _uint64HighBit = Uint64.parseHex('8000000000000000');
final Uint64 _uint64Max = Uint64.parseHex('ffffffffffffffff');

FieldInfo _compatibleScalarField({
  required int typeId,
  required Type type,
  required bool ref,
}) {
  return FieldInfo(
    name: 'value',
    identifier: '1',
    id: 1,
    fieldType: FieldType(
      type: type,
      declaredTypeName: '$type',
      typeId: typeId,
      nullable: false,
      ref: ref,
      dynamic: null,
      arguments: const <FieldType>[],
    ),
  );
}

ReadContext _compatibleReadContext(Buffer buffer) {
  const config = Config();
  final resolver = TypeResolver(config);
  return ReadContext(config, resolver, RefReader(), MetaStringReader(resolver))
    ..prepare(buffer);
}

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
  @ForyField(type: Float16Type())
  double half = 0.0;

  @ForyField(type: Bfloat16Type())
  double brain = 0.0;
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

@ForyStruct()
class CompatibleListEnvelope {
  CompatibleListEnvelope();

  @ListField(element: Int32Type(encoding: Encoding.fixed))
  List<int> values = <int>[];
}

@ForyStruct()
class CompatibleArrayEnvelope {
  CompatibleArrayEnvelope();

  @ArrayField(element: Int32Type())
  Int32List values = Int32List(0);
}

@ForyStruct()
class CompatibleNullableListEnvelope {
  CompatibleNullableListEnvelope();

  @ListField(element: Int32Type(nullable: true, encoding: Encoding.fixed))
  List<int?> values = <int?>[];
}

@ForyStruct()
class CompatibleRootNullableListEnvelope {
  CompatibleRootNullableListEnvelope();

  @ForyField(
    type: ListType(
      element: Int32Type(encoding: Encoding.fixed),
      nullable: true,
    ),
  )
  List<int>? values = <int>[];
}

@ForyStruct()
class CompatibleStringListEnvelope {
  CompatibleStringListEnvelope();

  @ListField(element: StringType())
  List<String> values = <String>[];
}

@ForyStruct()
class CompatibleNestedArrayListEnvelope {
  CompatibleNestedArrayListEnvelope();

  @ListField(element: ArrayType(element: Int32Type()))
  List<Int32List> values = <Int32List>[];
}

@ForyStruct()
class CompatibleNestedListEnvelope {
  CompatibleNestedListEnvelope();

  @ListField(element: ListType(element: Int32Type(encoding: Encoding.fixed)))
  List<List<int>> values = <List<int>>[];
}

@ForyStruct()
class CompatibleNestedStringListEnvelope {
  CompatibleNestedStringListEnvelope();

  @ListField(element: ListType(element: StringType()))
  List<List<String>> values = <List<String>>[];
}

@ForyStruct()
class CompatibleNestedNullableListEnvelope {
  CompatibleNestedNullableListEnvelope();

  @ListField(
    element: ListType(
      element: Int32Type(nullable: true, encoding: Encoding.fixed),
    ),
  )
  List<List<int?>> values = <List<int?>>[];
}

@ForyStruct()
class CompatibleNestedIntMapEnvelope {
  CompatibleNestedIntMapEnvelope();

  @MapField(key: StringType(), value: Int32Type(encoding: Encoding.fixed))
  Map<String, int> values = <String, int>{};
}

@ForyStruct()
class CompatibleNestedNullableMapEnvelope {
  CompatibleNestedNullableMapEnvelope();

  @MapField(
    key: StringType(),
    value: Int32Type(nullable: true, encoding: Encoding.fixed),
  )
  Map<String, int?> values = <String, int?>{};
}

@ForyStruct()
class CompatibleNestedStringMapEnvelope {
  CompatibleNestedStringMapEnvelope();

  @MapField(key: StringType(), value: StringType())
  Map<String, String> values = <String, String>{};
}

@ForyStruct()
class CompatibleScalarStringEnvelope {
  CompatibleScalarStringEnvelope();

  @ForyField(id: 1)
  String value = '';
}

@ForyStruct()
class CompatibleScalarOptionalStringEnvelope {
  CompatibleScalarOptionalStringEnvelope();

  @ForyField(id: 1, type: StringType(nullable: true))
  String? value;
}

@ForyStruct()
class CompatibleScalarBoolEnvelope {
  CompatibleScalarBoolEnvelope();

  @ForyField(id: 1)
  bool value = false;
}

@ForyStruct()
class CompatibleScalarOptionalBoolEnvelope {
  CompatibleScalarOptionalBoolEnvelope();

  @ForyField(id: 1, type: BoolType(nullable: true))
  bool? value;
}

@ForyStruct()
class CompatibleScalarTrackingRefBoolEnvelope {
  CompatibleScalarTrackingRefBoolEnvelope();

  @ForyField(id: 1, ref: true)
  bool value = false;
}

@ForyStruct()
class CompatibleScalarOptionalTrackingRefBoolEnvelope {
  CompatibleScalarOptionalTrackingRefBoolEnvelope();

  @ForyField(id: 1, ref: true, type: BoolType(nullable: true))
  bool? value;
}

@ForyStruct()
class CompatibleScalarInt64Envelope {
  CompatibleScalarInt64Envelope();

  @ForyField(id: 1, type: Int64Type(encoding: Encoding.varint))
  int value = 0;
}

@ForyStruct()
class CompatibleScalarOptionalInt64Envelope {
  CompatibleScalarOptionalInt64Envelope();

  @ForyField(id: 1, type: Int64Type(encoding: Encoding.varint, nullable: true))
  int? value;
}

@ForyStruct()
class CompatibleScalarInt8Envelope {
  CompatibleScalarInt8Envelope();

  @ForyField(id: 1, type: Int8Type())
  int value = 0;
}

@ForyStruct()
class CompatibleScalarUint64Envelope {
  CompatibleScalarUint64Envelope();

  @ForyField(id: 1, type: Uint64Type(encoding: Encoding.varint))
  Uint64 value = Uint64(0);
}

@ForyStruct()
class CompatibleScalarWrappedInt64Envelope {
  CompatibleScalarWrappedInt64Envelope();

  @ForyField(id: 1, type: Int64Type(encoding: Encoding.varint))
  Int64 value = Int64(0);
}

@ForyStruct()
class CompatibleScalarFloat64Envelope {
  CompatibleScalarFloat64Envelope();

  @ForyField(id: 1, type: Float64Type())
  double value = 0.0;
}

@ForyStruct()
class CompatibleScalarFloat32Envelope {
  CompatibleScalarFloat32Envelope();

  @ForyField(id: 1, type: Float32Type())
  double value = 0.0;
}

@ForyStruct()
class CompatibleScalarWrappedFloat32Envelope {
  CompatibleScalarWrappedFloat32Envelope();

  @ForyField(id: 1, type: Float32Type())
  Float32 value = Float32(0);
}

@ForyStruct()
class CompatibleScalarDecimalEnvelope {
  CompatibleScalarDecimalEnvelope();

  @ForyField(id: 1)
  Decimal value = Decimal.zero();
}

void _registerScalarTypes(Fory fory) {
  ScalarAndTypedArraySerializerTestForyModule.register(
    fory,
    ScalarAndArrayEnvelope,
    name: 'test.ScalarAndArrayEnvelope',
  );
  ScalarAndTypedArraySerializerTestForyModule.register(
    fory,
    ExplicitArrayEnvelope,
    name: 'test.ExplicitArrayEnvelope',
  );
}

T _roundTripRoot<T>(Fory fory, Object value) {
  return fory.deserialize<T>(fory.serialize(value));
}

T _compatibleScalarRoundTrip<T>(
  Type writerType,
  Type readerType,
  Object value,
) {
  final writer = Fory();
  final reader = Fory();
  ScalarAndTypedArraySerializerTestForyModule.register(
    writer,
    writerType,
    name: 'test.CompatibleScalarEnvelope',
  );
  ScalarAndTypedArraySerializerTestForyModule.register(
    reader,
    readerType,
    name: 'test.CompatibleScalarEnvelope',
  );
  return reader.deserialize<T>(writer.serialize(value));
}

void _expectCompatibleScalarError(
  Type writerType,
  Type readerType,
  Object value,
) {
  final writer = Fory();
  final reader = Fory();
  ScalarAndTypedArraySerializerTestForyModule.register(
    writer,
    writerType,
    name: 'test.CompatibleScalarEnvelope',
  );
  ScalarAndTypedArraySerializerTestForyModule.register(
    reader,
    readerType,
    name: 'test.CompatibleScalarEnvelope',
  );
  final bytes = writer.serialize(value);
  expect(
    () => reader.deserialize<Object>(bytes),
    throwsA(isA<InvalidDataException>()),
  );
}

ScalarAndArrayEnvelope _sampleEnvelope() {
  return ScalarAndArrayEnvelope()
    ..binary = Uint8List.fromList(<int>[0, 1, 2, 255])
    ..int8s = Int8List.fromList(<int>[-128, -1, 0, 127])
    ..int16s = Int16List.fromList(<int>[-32768, -1, 0, 32767])
    ..int32s = Int32List.fromList(<int>[-1, 0, 1, 123456789])
    ..int64s = Int64List.fromList(<Object>[
      -1,
      0,
      1,
      1 << 40,
      _int64Min,
      _int64Max,
    ])
    ..uint16s = Uint16List.fromList(<int>[0, 1, 65535])
    ..uint32s = Uint32List.fromList(<int>[0, 1, 0x7fffffff])
    ..uint64s = Uint64List.fromList(<Object>[
      0,
      1,
      1 << 40,
      _uint64HighBit,
      _uint64Max,
    ])
    ..float16s = Float16List.fromList(<double>[
      fromFloat16Bits(0x8000),
      fromFloat16Bits(0x3555),
      fromFloat16Bits(0x7c00),
    ])
    ..bfloat16s = Bfloat16List.fromList(<double>[
      fromBfloat16Bits(0x8000),
      fromBfloat16Bits(0x3eab),
      fromBfloat16Bits(0x7f80),
    ])
    ..float32s = Float32List.fromList(<double>[-1.5, 0.0, 3.25])
    ..float64s = Float64List.fromList(<double>[-2.5, 0.0, 4.5])
    ..flags = <bool>[true, false, true, true]
    ..half = fromFloat16Bits(0x8000)
    ..brain = fromBfloat16Bits(0x3fc0)
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
    Uint16List.view(
      actual.buffer,
      actual.offsetInBytes,
      actual.length,
    ).toList(),
    orderedEquals(
      Uint16List.view(
        expected.buffer,
        expected.offsetInBytes,
        expected.length,
      ).toList(),
    ),
  );
}

void _expectBfloat16ListEquals(Bfloat16List actual, Bfloat16List expected) {
  expect(
    Uint16List.view(
      actual.buffer,
      actual.offsetInBytes,
      actual.length,
    ).toList(),
    orderedEquals(
      Uint16List.view(
        expected.buffer,
        expected.offsetInBytes,
        expected.length,
      ).toList(),
    ),
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
    test('round-trips float16 edge cases', () {
      final fory = Fory();
      final cases = <double>[
        fromFloat16Bits(0x8000),
        fromFloat16Bits(0x7e00),
        double.infinity,
        -1.5,
      ];

      for (final value in cases) {
        final roundTrip = fory.deserialize<double>(
          fory.serializeBuiltin(value, wireTypeId: TypeIds.float16),
        );
        expect(toFloat16Bits(roundTrip), equals(toFloat16Bits(value)));
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

    test('round-trips bfloat16 edge cases', () {
      final fory = Fory();
      final cases = <double>[
        fromBfloat16Bits(0x8000),
        fromBfloat16Bits(0x7fc0),
        double.infinity,
        -1.5,
      ];

      for (final value in cases) {
        final roundTrip = fory.deserialize<double>(
          fory.serializeBuiltin(value, wireTypeId: TypeIds.bfloat16),
        );
        expect(toBfloat16Bits(roundTrip), equals(toBfloat16Bits(value)));
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
      expect(fromDateTime, equals(DateTime.utc(2024, 1, 2, 3, 4, 5, 6, 700)));
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
          Int64List.fromList(<Object>[-1, 0, 1, 1 << 40, _int64Min, _int64Max]),
        ),
        Int64List.fromList(<Object>[-1, 0, 1, 1 << 40, _int64Min, _int64Max]),
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
          Float16List.fromList(<double>[
            fromFloat16Bits(0x8000),
            fromFloat16Bits(0x3555),
            fromFloat16Bits(0x7c00),
          ]),
        ),
        Float16List.fromList(<double>[
          fromFloat16Bits(0x8000),
          fromFloat16Bits(0x3555),
          fromFloat16Bits(0x7c00),
        ]),
      );
      _expectBfloat16ListEquals(
        _roundTripRoot<Bfloat16List>(
          fory,
          Bfloat16List.fromList(<double>[
            fromBfloat16Bits(0x8000),
            fromBfloat16Bits(0x3eab),
            fromBfloat16Bits(0x7f80),
          ]),
        ),
        Bfloat16List.fromList(<double>[
          fromBfloat16Bits(0x8000),
          fromBfloat16Bits(0x3eab),
          fromBfloat16Bits(0x7f80),
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

    test('reuses Fory after typed-array reads without corrupting views', () {
      final fory = Fory();
      final values = <Int32List>[
        Int32List.fromList(<int>[1, 2]),
        Int32List.fromList(<int>[3, 4]),
      ];

      final decoded = fory.deserialize<List<Object?>>(fory.serialize(values));
      final encodedAgain = fory.serialize(decoded);
      final roundTrip = fory.deserialize<List<Object?>>(encodedAgain);

      expect((roundTrip[0] as Int32List).toList(), orderedEquals(<int>[1, 2]));
      expect((roundTrip[1] as Int32List).toList(), orderedEquals(<int>[3, 4]));
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
      final storage = Uint16List.fromList(<int>[
        0x3c00,
        0x3555,
        0x3f80,
        0x7fc0,
      ]);

      final float16s = Float16List.view(storage.buffer, 0, 2);
      final bfloat16s = Bfloat16List.sublistView(storage, 2, 4);

      expect(float16s.offsetInBytes, equals(0));
      expect(float16s.lengthInBytes, equals(4));
      expect(toFloat16Bits(float16s[0]), equals(0x3c00));
      expect(toFloat16Bits(float16s[1]), equals(0x3555));

      float16s[1] = fromFloat16Bits(0x4200);
      expect(storage[1], equals(0x4200));

      expect(bfloat16s.offsetInBytes, equals(4));
      expect(toBfloat16Bits(bfloat16s[0]), equals(0x3f80));
      expect(toBfloat16Bits(bfloat16s[1]), equals(0x7fc0));

      bfloat16s[0] = fromBfloat16Bits(0xbf80);
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
      expect(toFloat16Bits(roundTrip.half), equals(toFloat16Bits(value.half)));
      expect(
        toBfloat16Bits(roundTrip.brain),
        equals(toBfloat16Bits(value.brain)),
      );
      expect(roundTrip.single, equals(value.single));
      expect(roundTrip.date, equals(value.date));
      expect(roundTrip.timestamp, equals(value.timestamp));
    });

    test('separates normal list fields from explicit dense array fields', () {
      final writer = Fory();
      final reader = Fory();
      _registerScalarTypes(writer);
      _registerScalarTypes(reader);

      final value =
          ExplicitArrayEnvelope()
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

    test('adapts immediate compatible list and dense array fields', () {
      final writer = Fory();
      final reader = Fory();
      ScalarAndTypedArraySerializerTestForyModule.register(
        writer,
        CompatibleListEnvelope,
        name: 'test.CompatibleListArrayEnvelope',
      );
      ScalarAndTypedArraySerializerTestForyModule.register(
        reader,
        CompatibleArrayEnvelope,
        name: 'test.CompatibleListArrayEnvelope',
      );

      final bytes = writer.serialize(
        CompatibleListEnvelope()..values = <int>[1, 2, 3],
      );
      final decoded = reader.deserialize<CompatibleArrayEnvelope>(bytes);

      _expectInt32ListEquals(
        decoded.values,
        Int32List.fromList(<int>[1, 2, 3]),
      );
    });

    test('adapts immediate compatible dense array and list fields', () {
      final writer = Fory();
      final reader = Fory();
      ScalarAndTypedArraySerializerTestForyModule.register(
        writer,
        CompatibleArrayEnvelope,
        name: 'test.CompatibleListArrayEnvelope',
      );
      ScalarAndTypedArraySerializerTestForyModule.register(
        reader,
        CompatibleListEnvelope,
        name: 'test.CompatibleListArrayEnvelope',
      );

      final bytes = writer.serialize(
        CompatibleArrayEnvelope()..values = Int32List.fromList(<int>[1, 2, 3]),
      );
      final decoded = reader.deserialize<CompatibleListEnvelope>(bytes);

      expect(decoded.values, orderedEquals(<int>[1, 2, 3]));
    });

    test('adapts compatible nullable list schema into dense array fields', () {
      final writer = Fory();
      final reader = Fory();
      ScalarAndTypedArraySerializerTestForyModule.register(
        writer,
        CompatibleNullableListEnvelope,
        name: 'test.CompatibleNullableListArrayEnvelope',
      );
      ScalarAndTypedArraySerializerTestForyModule.register(
        reader,
        CompatibleArrayEnvelope,
        name: 'test.CompatibleNullableListArrayEnvelope',
      );

      final bytes = writer.serialize(
        CompatibleNullableListEnvelope()..values = <int?>[1, 2, 3],
      );

      final decoded = reader.deserialize<CompatibleArrayEnvelope>(bytes);
      _expectInt32ListEquals(
        decoded.values,
        Int32List.fromList(<int>[1, 2, 3]),
      );

      final nullBytes = writer.serialize(
        CompatibleNullableListEnvelope()..values = <int?>[1, null, 3],
      );
      expect(
        () => reader.deserialize<CompatibleArrayEnvelope>(nullBytes),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('cannot read nullable'),
          ),
        ),
      );
    });

    test('rejects compatible nullable list field into dense array field', () {
      final writer = Fory();
      final reader = Fory();
      ScalarAndTypedArraySerializerTestForyModule.register(
        writer,
        CompatibleRootNullableListEnvelope,
        name: 'test.CompatibleRootNullableListArrayEnvelope',
      );
      ScalarAndTypedArraySerializerTestForyModule.register(
        reader,
        CompatibleArrayEnvelope,
        name: 'test.CompatibleRootNullableListArrayEnvelope',
      );

      final bytes = writer.serialize(
        CompatibleRootNullableListEnvelope()..values = <int>[1, 2, 3],
      );

      expect(
        () => reader.deserialize<CompatibleArrayEnvelope>(bytes),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('unsupported list/array schema mismatch'),
          ),
        ),
      );
    });

    test(
      'rejects incompatible compatible list and dense array element fields',
      () {
        final writer = Fory();
        final reader = Fory();
        ScalarAndTypedArraySerializerTestForyModule.register(
          writer,
          CompatibleStringListEnvelope,
          name: 'test.CompatibleMismatchedListArrayEnvelope',
        );
        ScalarAndTypedArraySerializerTestForyModule.register(
          reader,
          CompatibleArrayEnvelope,
          name: 'test.CompatibleMismatchedListArrayEnvelope',
        );

        final bytes = writer.serialize(
          CompatibleStringListEnvelope()..values = <String>['1', '2'],
        );
        expect(
          () => reader.deserialize<CompatibleArrayEnvelope>(bytes),
          throwsStateError,
        );
      },
    );

    test('rejects nested compatible list and dense array field positions', () {
      final writer = Fory();
      final reader = Fory();
      ScalarAndTypedArraySerializerTestForyModule.register(
        writer,
        CompatibleNestedArrayListEnvelope,
        name: 'test.CompatibleNestedListArrayEnvelope',
      );
      ScalarAndTypedArraySerializerTestForyModule.register(
        reader,
        CompatibleNestedListEnvelope,
        name: 'test.CompatibleNestedListArrayEnvelope',
      );

      final bytes = writer.serialize(
        CompatibleNestedArrayListEnvelope()
          ..values = <Int32List>[
            Int32List.fromList(<int>[1, 2]),
          ],
      );

      expect(
        () => reader.deserialize<CompatibleNestedListEnvelope>(bytes),
        throwsStateError,
      );
    });

    test('rejects nested list scalar changes', () {
      final writer = Fory();
      final reader = Fory();
      ScalarAndTypedArraySerializerTestForyModule.register(
        writer,
        CompatibleNestedStringListEnvelope,
        name: 'test.CompatibleNestedScalarListEnvelope',
      );
      ScalarAndTypedArraySerializerTestForyModule.register(
        reader,
        CompatibleNestedListEnvelope,
        name: 'test.CompatibleNestedScalarListEnvelope',
      );

      final bytes = writer.serialize(
        CompatibleNestedStringListEnvelope()
          ..values = <List<String>>[
            <String>['1'],
          ],
      );

      expect(
        () => reader.deserialize<CompatibleNestedListEnvelope>(bytes),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('incompatible local and remote schemas'),
          ),
        ),
      );
    });

    test('reads nested list scalar nullable changes', () {
      final writer = Fory();
      final reader = Fory();
      ScalarAndTypedArraySerializerTestForyModule.register(
        writer,
        CompatibleNestedNullableListEnvelope,
        name: 'test.CompatibleNestedScalarListNullableEnvelope',
      );
      ScalarAndTypedArraySerializerTestForyModule.register(
        reader,
        CompatibleNestedListEnvelope,
        name: 'test.CompatibleNestedScalarListNullableEnvelope',
      );

      final bytes = writer.serialize(
        CompatibleNestedNullableListEnvelope()
          ..values = <List<int?>>[
            <int?>[7],
          ],
      );

      final decoded = reader.deserialize<CompatibleNestedListEnvelope>(bytes);
      expect(decoded.values, <List<int>>[
        <int>[7],
      ]);
    });

    test('rejects nested map scalar changes', () {
      final writer = Fory();
      final reader = Fory();
      ScalarAndTypedArraySerializerTestForyModule.register(
        writer,
        CompatibleNestedStringMapEnvelope,
        name: 'test.CompatibleNestedScalarMapEnvelope',
      );
      ScalarAndTypedArraySerializerTestForyModule.register(
        reader,
        CompatibleNestedIntMapEnvelope,
        name: 'test.CompatibleNestedScalarMapEnvelope',
      );

      final bytes = writer.serialize(
        CompatibleNestedStringMapEnvelope()
          ..values = <String, String>{'x': '1'},
      );

      expect(
        () => reader.deserialize<CompatibleNestedIntMapEnvelope>(bytes),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('incompatible local and remote schemas'),
          ),
        ),
      );
    });

    test('reads nested map scalar nullable changes', () {
      final writer = Fory();
      final reader = Fory();
      ScalarAndTypedArraySerializerTestForyModule.register(
        writer,
        CompatibleNestedNullableMapEnvelope,
        name: 'test.CompatibleNestedScalarMapNullableEnvelope',
      );
      ScalarAndTypedArraySerializerTestForyModule.register(
        reader,
        CompatibleNestedIntMapEnvelope,
        name: 'test.CompatibleNestedScalarMapNullableEnvelope',
      );

      final bytes = writer.serialize(
        CompatibleNestedNullableMapEnvelope()..values = <String, int?>{'x': 9},
      );

      final decoded = reader.deserialize<CompatibleNestedIntMapEnvelope>(bytes);
      expect(decoded.values, <String, int>{'x': 9});
    });

    test('converts compatible scalar fields losslessly', () {
      expect(
        _compatibleScalarRoundTrip<CompatibleScalarBoolEnvelope>(
          CompatibleScalarStringEnvelope,
          CompatibleScalarBoolEnvelope,
          CompatibleScalarStringEnvelope()..value = 'true',
        ).value,
        isTrue,
      );
      expect(
        _compatibleScalarRoundTrip<CompatibleScalarStringEnvelope>(
          CompatibleScalarBoolEnvelope,
          CompatibleScalarStringEnvelope,
          CompatibleScalarBoolEnvelope()..value = true,
        ).value,
        equals('true'),
      );
      expect(
        _compatibleScalarRoundTrip<CompatibleScalarBoolEnvelope>(
          CompatibleScalarInt64Envelope,
          CompatibleScalarBoolEnvelope,
          CompatibleScalarInt64Envelope()..value = 1,
        ).value,
        isTrue,
      );
      expect(
        _compatibleScalarRoundTrip<CompatibleScalarInt8Envelope>(
          CompatibleScalarBoolEnvelope,
          CompatibleScalarInt8Envelope,
          CompatibleScalarBoolEnvelope()..value = true,
        ).value,
        equals(1),
      );
      expect(
        _compatibleScalarRoundTrip<CompatibleScalarInt8Envelope>(
          CompatibleScalarInt64Envelope,
          CompatibleScalarInt8Envelope,
          CompatibleScalarInt64Envelope()..value = 127,
        ).value,
        equals(127),
      );
      expect(
        _compatibleScalarRoundTrip<CompatibleScalarInt64Envelope>(
          CompatibleScalarUint64Envelope,
          CompatibleScalarInt64Envelope,
          CompatibleScalarUint64Envelope()..value = Uint64(42),
        ).value,
        equals(42),
      );
      expect(
        _compatibleScalarRoundTrip<CompatibleScalarStringEnvelope>(
          CompatibleScalarUint64Envelope,
          CompatibleScalarStringEnvelope,
          CompatibleScalarUint64Envelope()
            ..value = Uint64.fromBigInt((BigInt.one << 64) - BigInt.one),
        ).value,
        equals('18446744073709551615'),
      );
      expect(
        _compatibleScalarRoundTrip<CompatibleScalarUint64Envelope>(
          CompatibleScalarInt64Envelope,
          CompatibleScalarUint64Envelope,
          CompatibleScalarInt64Envelope()..value = 42,
        ).value,
        equals(Uint64(42)),
      );
      expect(
        _compatibleScalarRoundTrip<CompatibleScalarWrappedInt64Envelope>(
          CompatibleScalarUint64Envelope,
          CompatibleScalarWrappedInt64Envelope,
          CompatibleScalarUint64Envelope()..value = Uint64(42),
        ).value,
        equals(Int64(42)),
      );
      expect(
        _compatibleScalarRoundTrip<CompatibleScalarFloat32Envelope>(
          CompatibleScalarStringEnvelope,
          CompatibleScalarFloat32Envelope,
          CompatibleScalarStringEnvelope()..value = '0.5',
        ).value,
        equals(0.5),
      );
      expect(
        _compatibleScalarRoundTrip<CompatibleScalarFloat32Envelope>(
          CompatibleScalarFloat64Envelope,
          CompatibleScalarFloat32Envelope,
          CompatibleScalarFloat64Envelope()..value = 0.5,
        ).value,
        equals(0.5),
      );
      expect(
        _compatibleScalarRoundTrip<CompatibleScalarFloat64Envelope>(
          CompatibleScalarFloat32Envelope,
          CompatibleScalarFloat64Envelope,
          CompatibleScalarFloat32Envelope()..value = double.infinity,
        ).value,
        equals(double.infinity),
      );
      expect(
        _compatibleScalarRoundTrip<CompatibleScalarFloat32Envelope>(
          CompatibleScalarFloat64Envelope,
          CompatibleScalarFloat32Envelope,
          CompatibleScalarFloat64Envelope()..value = double.negativeInfinity,
        ).value,
        equals(double.negativeInfinity),
      );
      expect(
        _compatibleScalarRoundTrip<CompatibleScalarWrappedFloat32Envelope>(
          CompatibleScalarFloat64Envelope,
          CompatibleScalarWrappedFloat32Envelope,
          CompatibleScalarFloat64Envelope()..value = 0.5,
        ).value,
        equals(Float32(0.5)),
      );
      final negativeZero =
          _compatibleScalarRoundTrip<CompatibleScalarFloat32Envelope>(
            CompatibleScalarStringEnvelope,
            CompatibleScalarFloat32Envelope,
            CompatibleScalarStringEnvelope()..value = '-0e0',
          ).value;
      expect(negativeZero, equals(0.0));
      expect(negativeZero.isNegative, isTrue);
      expect(
        _compatibleScalarRoundTrip<CompatibleScalarStringEnvelope>(
          CompatibleScalarFloat64Envelope,
          CompatibleScalarStringEnvelope,
          CompatibleScalarFloat64Envelope()..value = -0.0,
        ).value,
        equals('-0.0'),
      );
      expect(
        _compatibleScalarRoundTrip<CompatibleScalarDecimalEnvelope>(
          CompatibleScalarStringEnvelope,
          CompatibleScalarDecimalEnvelope,
          CompatibleScalarStringEnvelope()..value = '1.2300',
        ).value,
        equals(Decimal(BigInt.from(123), 2)),
      );
      final digits256 = List<String>.filled(256, '1').join();
      final digitBound =
          _compatibleScalarRoundTrip<CompatibleScalarDecimalEnvelope>(
            CompatibleScalarStringEnvelope,
            CompatibleScalarDecimalEnvelope,
            CompatibleScalarStringEnvelope()..value = digits256,
          ).value;
      expect(digitBound, equals(Decimal(BigInt.parse(digits256), 0)));
      final exponentBound =
          _compatibleScalarRoundTrip<CompatibleScalarDecimalEnvelope>(
            CompatibleScalarStringEnvelope,
            CompatibleScalarDecimalEnvelope,
            CompatibleScalarStringEnvelope()..value = '1e255',
          ).value;
      expect(exponentBound.unscaledValue.toString().length, equals(256));
      expect(exponentBound.scale, equals(0));
      expect(
        _compatibleScalarRoundTrip<CompatibleScalarInt64Envelope>(
          CompatibleScalarDecimalEnvelope,
          CompatibleScalarInt64Envelope,
          CompatibleScalarDecimalEnvelope()
            ..value = Decimal(BigInt.from(10), 1),
        ).value,
        equals(1),
      );
    });

    test('rejects invalid compatible scalar payloads as invalid data', () {
      _expectCompatibleScalarError(
        CompatibleScalarStringEnvelope,
        CompatibleScalarBoolEnvelope,
        CompatibleScalarStringEnvelope()..value = 'True',
      );
      _expectCompatibleScalarError(
        CompatibleScalarInt64Envelope,
        CompatibleScalarBoolEnvelope,
        CompatibleScalarInt64Envelope()..value = 2,
      );
      _expectCompatibleScalarError(
        CompatibleScalarStringEnvelope,
        CompatibleScalarInt64Envelope,
        CompatibleScalarStringEnvelope()..value = '01',
      );
      _expectCompatibleScalarError(
        CompatibleScalarStringEnvelope,
        CompatibleScalarInt64Envelope,
        CompatibleScalarStringEnvelope()..value = '1.5',
      );
      _expectCompatibleScalarError(
        CompatibleScalarStringEnvelope,
        CompatibleScalarFloat32Envelope,
        CompatibleScalarStringEnvelope()..value = '0.1',
      );
      _expectCompatibleScalarError(
        CompatibleScalarStringEnvelope,
        CompatibleScalarDecimalEnvelope,
        CompatibleScalarStringEnvelope()
          ..value = List<String>.filled(257, '1').join(),
      );
      _expectCompatibleScalarError(
        CompatibleScalarStringEnvelope,
        CompatibleScalarDecimalEnvelope,
        CompatibleScalarStringEnvelope()
          ..value = '0.${List<String>.filled(319, '0').join()}',
      );
      _expectCompatibleScalarError(
        CompatibleScalarStringEnvelope,
        CompatibleScalarDecimalEnvelope,
        CompatibleScalarStringEnvelope()..value = '1e1000000',
      );
      _expectCompatibleScalarError(
        CompatibleScalarStringEnvelope,
        CompatibleScalarDecimalEnvelope,
        CompatibleScalarStringEnvelope()..value = '1e256',
      );
      _expectCompatibleScalarError(
        CompatibleScalarInt64Envelope,
        CompatibleScalarInt8Envelope,
        CompatibleScalarInt64Envelope()..value = 128,
      );
      _expectCompatibleScalarError(
        CompatibleScalarDecimalEnvelope,
        CompatibleScalarInt64Envelope,
        CompatibleScalarDecimalEnvelope()..value = Decimal(BigInt.one << 63, 0),
      );
      _expectCompatibleScalarError(
        CompatibleScalarUint64Envelope,
        CompatibleScalarInt64Envelope,
        CompatibleScalarUint64Envelope()..value = _uint64HighBit,
      );
      _expectCompatibleScalarError(
        CompatibleScalarDecimalEnvelope,
        CompatibleScalarStringEnvelope,
        CompatibleScalarDecimalEnvelope()..value = Decimal(BigInt.one, -256),
      );
      _expectCompatibleScalarError(
        CompatibleScalarFloat64Envelope,
        CompatibleScalarStringEnvelope,
        CompatibleScalarFloat64Envelope()..value = double.infinity,
      );

      final writer = Fory();
      final reader = Fory();
      ScalarAndTypedArraySerializerTestForyModule.register(
        writer,
        CompatibleScalarBoolEnvelope,
        name: 'test.CompatibleScalarEnvelope',
      );
      ScalarAndTypedArraySerializerTestForyModule.register(
        reader,
        CompatibleScalarStringEnvelope,
        name: 'test.CompatibleScalarEnvelope',
      );
      final corrupted = Uint8List.fromList(
        writer.serialize(CompatibleScalarBoolEnvelope()..value = true),
      );
      corrupted[corrupted.length - 1] = 2;
      expect(
        () => reader.deserialize<Object>(corrupted),
        throwsA(isA<InvalidDataException>()),
      );
    });

    test('composes compatible scalar conversion with nullable fields', () {
      expect(
        _compatibleScalarRoundTrip<CompatibleScalarBoolEnvelope>(
          CompatibleScalarOptionalStringEnvelope,
          CompatibleScalarBoolEnvelope,
          CompatibleScalarOptionalStringEnvelope()..value = 'false',
        ).value,
        isFalse,
      );
      expect(
        _compatibleScalarRoundTrip<CompatibleScalarBoolEnvelope>(
          CompatibleScalarOptionalStringEnvelope,
          CompatibleScalarBoolEnvelope,
          CompatibleScalarOptionalStringEnvelope()..value = null,
        ).value,
        isFalse,
      );
    });

    test('strictly reads same-type nullable compatible scalar fields', () {
      final writer = Fory();
      final reader = Fory();
      ScalarAndTypedArraySerializerTestForyModule.register(
        writer,
        CompatibleScalarOptionalBoolEnvelope,
        name: 'test.CompatibleScalarEnvelope',
      );
      ScalarAndTypedArraySerializerTestForyModule.register(
        reader,
        CompatibleScalarBoolEnvelope,
        name: 'test.CompatibleScalarEnvelope',
      );
      final bytes = writer.serialize(
        CompatibleScalarOptionalBoolEnvelope()..value = true,
      );
      final flagOffset = bytes.lastIndexOf(RefWriter.notNullValueFlag & 0xff);
      expect(flagOffset, isNonNegative);

      final badFlag = Uint8List.fromList(bytes);
      badFlag[flagOffset] = RefWriter.refValueFlag & 0xff;
      expect(
        () => reader.deserialize<Object>(badFlag),
        throwsA(isA<InvalidDataException>()),
      );

      final badPayload = Uint8List.fromList(bytes);
      badPayload[badPayload.length - 1] = 2;
      expect(
        () => reader.deserialize<Object>(badPayload),
        throwsA(isA<InvalidDataException>()),
      );

      expect(
        _compatibleScalarRoundTrip<CompatibleScalarOptionalInt64Envelope>(
          CompatibleScalarInt64Envelope,
          CompatibleScalarOptionalInt64Envelope,
          CompatibleScalarInt64Envelope()..value = 42,
        ).value,
        42,
      );
    });

    test('keeps same-schema scalar reads direct', () {
      final fory = Fory();
      ScalarAndTypedArraySerializerTestForyModule.register(
        fory,
        CompatibleScalarStringEnvelope,
        name: 'test.CompatibleScalarEnvelope',
      );

      final value = CompatibleScalarStringEnvelope()..value = 'True';
      final roundTrip = fory.deserialize<CompatibleScalarStringEnvelope>(
        fory.serialize(value),
      );

      expect(roundTrip.value, equals('True'));
    });

    test('does not select scalar conversion for ref tracking', () {
      final remoteRefBool = _compatibleScalarField(
        typeId: TypeIds.boolType,
        type: bool,
        ref: true,
      );
      final localString = _compatibleScalarField(
        typeId: TypeIds.string,
        type: String,
        ref: false,
      );
      final localRefString = _compatibleScalarField(
        typeId: TypeIds.string,
        type: String,
        ref: true,
      );

      expect(compatibleScalarConversion(remoteRefBool, localString), isNull);
      expect(compatibleScalarConversion(localString, localRefString), isNull);

      final buffer = Buffer()..writeBool(true);
      expect(
        readCompatibleField(_compatibleReadContext(buffer), remoteRefBool),
        isTrue,
      );
      expect(buffer.readableBytes, equals(0));
    });

    test('rejects tracked scalar nullable framing mismatch during layout', () {
      expect(
        () =>
            _compatibleScalarRoundTrip<CompatibleScalarTrackingRefBoolEnvelope>(
              CompatibleScalarOptionalTrackingRefBoolEnvelope,
              CompatibleScalarTrackingRefBoolEnvelope,
              CompatibleScalarOptionalTrackingRefBoolEnvelope()..value = true,
            ),
        throwsA(isA<StateError>()),
      );
      expect(
        () => _compatibleScalarRoundTrip<
          CompatibleScalarOptionalTrackingRefBoolEnvelope
        >(
          CompatibleScalarTrackingRefBoolEnvelope,
          CompatibleScalarOptionalTrackingRefBoolEnvelope,
          CompatibleScalarTrackingRefBoolEnvelope()..value = true,
        ),
        throwsA(isA<StateError>()),
      );
      expect(
        _compatibleScalarRoundTrip<
          CompatibleScalarOptionalTrackingRefBoolEnvelope
        >(
          CompatibleScalarOptionalTrackingRefBoolEnvelope,
          CompatibleScalarOptionalTrackingRefBoolEnvelope,
          CompatibleScalarOptionalTrackingRefBoolEnvelope()..value = true,
        ).value,
        isTrue,
      );
    });
  });
}

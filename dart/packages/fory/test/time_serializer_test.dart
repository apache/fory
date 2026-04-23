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

part 'time_serializer_test.fory.dart';

Timestamp _timestamp(int seconds, int nanoseconds) =>
    Timestamp(Int64(seconds), nanoseconds);

@ForyStruct()
class TimeEnvelope {
  TimeEnvelope();

  LocalDate date = const LocalDate(1970, 1, 1);
  Timestamp timestamp = _timestamp(0, 0);
  DateTime instant = DateTime.fromMicrosecondsSinceEpoch(0, isUtc: true);
  Duration duration = Duration.zero;
  LocalDate? optionalDate;
  Timestamp? optionalTimestamp;
  DateTime? optionalInstant;
  Duration? optionalDuration;
}

void _registerTimeTypes(Fory fory) {
  TimeSerializerTestFory.register(
    fory,
    TimeEnvelope,
    namespace: 'time',
    typeName: 'TimeEnvelope',
  );
}

void _expectTimeEnvelope(TimeEnvelope actual, TimeEnvelope expected) {
  expect(actual.date, equals(expected.date));
  expect(actual.timestamp, equals(expected.timestamp));
  expect(actual.instant, equals(expected.instant));
  expect(actual.duration, equals(expected.duration));
  expect(actual.optionalDate, equals(expected.optionalDate));
  expect(actual.optionalTimestamp, equals(expected.optionalTimestamp));
  expect(actual.optionalInstant, equals(expected.optionalInstant));
  expect(actual.optionalDuration, equals(expected.optionalDuration));
}

TimeEnvelope _sampleTimeEnvelope() {
  return TimeEnvelope()
    ..date = const LocalDate(2024, 2, 29)
    ..timestamp = _timestamp(-123456789, 987654321)
    ..instant = DateTime.fromMicrosecondsSinceEpoch(-1, isUtc: true)
    ..duration = const Duration(days: 2, seconds: 3, microseconds: 456789)
    ..optionalDate = LocalDate.fromEpochDay(Int64(-1))
    ..optionalTimestamp = Timestamp.fromDateTime(
      DateTime.utc(2024, 1, 2, 3, 4, 5, 6, 700),
    )
    ..optionalInstant = DateTime.utc(2024, 1, 2, 3, 4, 5, 6, 700)
    ..optionalDuration = const Duration(microseconds: -1500001);
}

void main() {
  group('time serializers', () {
    test('round-trips LocalDate edge cases', () {
      final fory = Fory();
      final cases = <LocalDate>[
        LocalDate.fromEpochDay(Int64(-1)),
        LocalDate.fromEpochDay(Int64(0)),
        const LocalDate(2024, 2, 29),
        const LocalDate(9999, 12, 31),
      ];

      for (final value in cases) {
        expect(
            fory.deserialize<LocalDate>(fory.serialize(value)), equals(value));
      }
    });

    test('encodes LocalDate as signed varint64 in xlang payloads', () {
      final fory = Fory();
      final value = LocalDate.fromEpochDay(Int64(-1));

      final bytes = fory.serialize(value);
      expect(
          bytes, equals(Uint8List.fromList([0x02, 0xff, TypeIds.date, 0x01])));
      expect(fory.deserialize<LocalDate>(bytes), equals(value));
    });

    test('LocalDate convenience methods bridge DateTime and epoch-day forms',
        () {
      final value = LocalDate.fromDateTime(DateTime.utc(2024, 1, 2, 3, 4, 5));

      expect(value, equals(const LocalDate(2024, 1, 2)));
      final Int64 epochDay = value.toEpochDay();
      expect(epochDay, equals(const LocalDate(2024, 1, 2).toEpochDay()));
      expect(value.toDateTime(), equals(DateTime.utc(2024, 1, 2)));
    });

    test('decodes root Timestamp payloads to DateTime by default', () {
      final fory = Fory();
      final cases = <MapEntry<Timestamp, DateTime>>[
        MapEntry(
          _timestamp(0, 0),
          DateTime.fromMicrosecondsSinceEpoch(0, isUtc: true),
        ),
        MapEntry(
          _timestamp(-1, 1000),
          DateTime.fromMicrosecondsSinceEpoch(-999999, isUtc: true),
        ),
        MapEntry(
          _timestamp(1, 999999000),
          DateTime.fromMicrosecondsSinceEpoch(1999999, isUtc: true),
        ),
        MapEntry(
          Timestamp.fromDateTime(DateTime.utc(2024, 1, 2, 3, 4, 5, 6, 700)),
          DateTime.utc(2024, 1, 2, 3, 4, 5, 6, 700),
        ),
      ];

      for (final entry in cases) {
        final roundTrip = fory.deserialize<Object?>(fory.serialize(entry.key));

        expect(roundTrip, isA<DateTime>());
        expect(roundTrip, equals(entry.value));
      }
    });

    test('round-trips DateTime edge cases as timestamp payloads', () {
      final fory = Fory();
      final cases = <DateTime>[
        DateTime.fromMicrosecondsSinceEpoch(-1, isUtc: true),
        DateTime.fromMicrosecondsSinceEpoch(0, isUtc: true),
        DateTime.utc(2024, 1, 2, 3, 4, 5, 6, 700),
      ];

      for (final value in cases) {
        expect(
            fory.deserialize<DateTime>(fory.serialize(value)), equals(value));
      }
    });

    test(
        'root containers without declared element types decode timestamps as DateTime',
        () {
      final fory = Fory();
      final roundTrip = fory.deserialize<List<Object?>>(
        fory.serialize(<Object>[_timestamp(-1, 999999000)]),
      );

      expect(
        roundTrip.single,
        isA<DateTime>().having(
          (value) => value,
          'value',
          equals(DateTime.fromMicrosecondsSinceEpoch(-1, isUtc: true)),
        ),
      );
    });

    test('reads timestamp payloads directly as DateTime', () {
      final fory = Fory();

      expect(
        fory.deserialize<DateTime>(fory.serialize(_timestamp(-1, 999999000))),
        equals(DateTime.fromMicrosecondsSinceEpoch(-1, isUtc: true)),
      );
    });

    test('normalizes local DateTime values to UTC instants', () {
      final fory = Fory();
      final value = DateTime(2024, 1, 2, 3, 4, 5, 6, 700);

      final roundTrip = fory.deserialize<DateTime?>(fory.serialize(value));

      expect(roundTrip, isNotNull);
      expect(roundTrip!.isUtc, isTrue);
      expect(roundTrip.isAtSameMomentAs(value), isTrue);
    });

    test('converts negative DateTime values to Timestamp correctly', () {
      final cases = <MapEntry<DateTime, Timestamp>>[
        MapEntry(
          DateTime.fromMicrosecondsSinceEpoch(-1, isUtc: true),
          _timestamp(-1, 999999000),
        ),
        MapEntry(
          DateTime.fromMicrosecondsSinceEpoch(-1000001, isUtc: true),
          _timestamp(-2, 999999000),
        ),
      ];

      for (final entry in cases) {
        expect(Timestamp.fromDateTime(entry.key), equals(entry.value));
        expect(entry.value.toDateTime(), equals(entry.key));
      }
    });

    test('round-trips Duration edge cases', () {
      final fory = Fory();
      final cases = <Duration>[
        Duration.zero,
        const Duration(microseconds: 1),
        const Duration(seconds: 1, microseconds: 234567),
        const Duration(microseconds: -1),
        const Duration(microseconds: -1500001),
        const Duration(days: 7, seconds: 3, microseconds: 999999),
      ];

      for (final value in cases) {
        expect(
            fory.deserialize<Duration>(fory.serialize(value)), equals(value));
      }
    });

    test('round-trips generated time fields in schema-consistent mode', () {
      final fory = Fory();
      _registerTimeTypes(fory);

      final value = _sampleTimeEnvelope();
      final roundTrip = fory.deserialize<TimeEnvelope>(fory.serialize(value));

      _expectTimeEnvelope(roundTrip, value);
    });

    test('round-trips generated time fields in compatible mode', () {
      final fory = Fory(compatible: true);
      _registerTimeTypes(fory);

      final value = _sampleTimeEnvelope();
      final roundTrip = fory.deserialize<TimeEnvelope>(fory.serialize(value));

      _expectTimeEnvelope(roundTrip, value);
    });

    test('does not preserve references for repeated temporal values', () {
      final fory = Fory();
      final duration = Duration(microseconds: 1);
      final timestamp = _timestamp(-1, 1000);
      final date = LocalDate.fromEpochDay(Int64(-1));

      final roundTrip = fory.deserialize<List<Object?>>(
        fory.serialize(
          <Object>[duration, duration, timestamp, timestamp, date, date],
          trackRef: true,
        ),
      );

      expect(roundTrip[0], equals(roundTrip[1]));
      expect(identical(roundTrip[0], roundTrip[1]), isFalse);
      expect(roundTrip[2], isA<DateTime>());
      expect(roundTrip[2], equals(roundTrip[3]));
      expect(identical(roundTrip[2], roundTrip[3]), isFalse);
      expect(roundTrip[4], equals(roundTrip[5]));
      expect(identical(roundTrip[4], roundTrip[5]), isFalse);
    });

    test('supports null optional time fields', () {
      final fory = Fory();
      _registerTimeTypes(fory);

      final roundTrip = fory.deserialize<TimeEnvelope>(
        fory.serialize(TimeEnvelope()),
      );

      expect(roundTrip.optionalDate, isNull);
      expect(roundTrip.optionalTimestamp, isNull);
      expect(roundTrip.optionalInstant, isNull);
      expect(roundTrip.optionalDuration, isNull);
    });

    test('preserves sub-microsecond duration wire precision on round-trip', () {
      final fory = Fory();

      for (final nanoseconds in <int>[1, -1]) {
        final bytes = Uint8List.fromList(fory.serialize(Duration.zero));
        final view = ByteData.sublistView(bytes);
        view.setInt32(bytes.length - 4, nanoseconds, Endian.little);

        final roundTrip = fory.deserialize<Duration>(bytes);

        expect(roundTrip, equals(Duration.zero));
        expect(fory.serialize(roundTrip), orderedEquals(bytes));
      }
    });

    test('rejects duration payloads with nanoseconds outside spec range', () {
      final fory = Fory();

      for (final nanoseconds in <int>[1000000000, -1000000000]) {
        final bytes = Uint8List.fromList(fory.serialize(Duration.zero));
        final view = ByteData.sublistView(bytes);
        view.setInt32(bytes.length - 4, nanoseconds, Endian.little);

        expect(
          () => fory.deserialize<Duration>(bytes),
          throwsA(
            isA<StateError>().having(
              (error) => error.toString(),
              'message',
              contains('out of range'),
            ),
          ),
        );
      }
    });

    test('rejects duration payloads with inconsistent signs', () {
      final fory = Fory();
      final positive =
          Uint8List.fromList(fory.serialize(const Duration(seconds: 1)));
      final negative =
          Uint8List.fromList(fory.serialize(const Duration(seconds: -1)));

      ByteData.sublistView(positive).setInt32(
        positive.length - 4,
        -1,
        Endian.little,
      );
      ByteData.sublistView(negative).setInt32(
        negative.length - 4,
        1,
        Endian.little,
      );

      for (final bytes in <Uint8List>[positive, negative]) {
        expect(
          () => fory.deserialize<Duration>(bytes),
          throwsA(
            isA<StateError>().having(
              (error) => error.toString(),
              'message',
              contains('inconsistent signs'),
            ),
          ),
        );
      }
    });

    test('rejects invalid timestamp nanoseconds on write', () {
      final fory = Fory();

      expect(
        () => fory.serialize(_timestamp(0, 1000000000)),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('out of range'),
          ),
        ),
      );
    });

    test('rejects timestamp payloads with nanoseconds outside spec range', () {
      final fory = Fory();
      final bytes = Uint8List.fromList(fory.serialize(_timestamp(0, 0)));
      final view = ByteData.sublistView(bytes);
      view.setUint32(bytes.length - 4, 1000000000, Endian.little);

      expect(
        () => fory.deserialize<Object?>(bytes),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('out of range'),
          ),
        ),
      );
    });

    test('rejects DateTime payloads with sub-microsecond precision', () {
      final fory = Fory();
      final bytes = Uint8List.fromList(
        fory.serialize(DateTime.fromMicrosecondsSinceEpoch(0, isUtc: true)),
      );
      final view = ByteData.sublistView(bytes);
      view.setUint32(bytes.length - 4, 1, Endian.little);

      expect(
        () => fory.deserialize<DateTime>(bytes),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('DateTime microsecond precision'),
          ),
        ),
      );
    });
  });
}

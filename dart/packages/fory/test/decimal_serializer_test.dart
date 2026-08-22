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

part 'decimal_serializer_test.fory.dart';

const int _decimalMagnitudeByteLimit = 10_000;
const int _decimalScaleLimit = 10_000;

@ForyStruct()
class DecimalEnvelope {
  DecimalEnvelope();

  Decimal amount = Decimal.zero();
  String note = '';
}

Decimal _decimal(String unscaled, int scale) {
  return Decimal(BigInt.parse(unscaled), scale);
}

Buffer _decimalRootBuffer(int scale) {
  return Buffer()
    ..writeUint8(0x01)
    ..writeByte(-1)
    ..writeVarUint32Small7(TypeIds.decimal)
    ..writeVarInt32(scale);
}

Uint64 _bigDecimalHeader(int magnitudeLength, [int sign = 0]) {
  final meta = (magnitudeLength << 1) | sign;
  return Uint64((meta << 1) | 1);
}

BigInt _magnitudeWithByteLength(int length) {
  return BigInt.one << (length * 8 - 1);
}

void _registerDecimalEnvelope(Fory fory) {
  DecimalSerializerTestForyModule.register(
    fory,
    DecimalEnvelope,
    name: 'test.DecimalEnvelope',
  );
}

void main() {
  group('decimal serializer', () {
    test('round-trips root decimal edge cases', () {
      final fory = Fory();
      final values = <Decimal>[
        Decimal.zero(),
        Decimal.zero(3),
        Decimal.fromInt(1),
        Decimal.fromInt(-1),
        Decimal.fromInt(12345, scale: 2),
        _decimal('9223372036854775807', 0),
        _decimal('-9223372036854775808', 0),
        _decimal('4611686018427387903', 0),
        _decimal('-4611686018427387904', 0),
        _decimal('9223372036854775808', 0),
        _decimal('-9223372036854775809', 0),
        _decimal('123456789012345678901234567890123456789', 37),
        _decimal('-123456789012345678901234567890123456789', -17),
      ];

      for (final value in values) {
        expect(fory.deserialize<Decimal>(fory.serialize(value)), equals(value));
      }
    });

    test('round-trips generated decimal fields', () {
      final fory = Fory();
      _registerDecimalEnvelope(fory);

      final value =
          DecimalEnvelope()
            ..amount = _decimal('123456789012345678901234567890123456789', 37)
            ..note = 'principal';

      final roundTrip = fory.deserialize<DecimalEnvelope>(
        fory.serialize(value),
      );
      expect(roundTrip.amount, equals(value.amount));
      expect(roundTrip.note, equals('principal'));
    });

    test('reader and writer enforce scale limits', () {
      final fory = Fory();
      for (final scale in <int>[-_decimalScaleLimit, _decimalScaleLimit]) {
        final value = Decimal(BigInt.one, scale);
        expect(fory.deserialize<Decimal>(fory.serialize(value)), equals(value));
      }

      for (final scale in <int>[
        -_decimalScaleLimit - 1,
        _decimalScaleLimit + 1,
        -0x80000000,
        0x7fffffff,
      ]) {
        expect(
          () => fory.serialize(Decimal(BigInt.one, scale)),
          throwsA(
            isA<StateError>().having(
              (error) => error.toString(),
              'message',
              contains('Decimal scale'),
            ),
          ),
          reason: 'writer scale=$scale',
        );
        expect(
          () => fory.deserializeFrom<Decimal>(_decimalRootBuffer(scale)),
          throwsA(
            isA<StateError>().having(
              (error) => error.toString(),
              'message',
              contains('Decimal scale'),
            ),
          ),
          reason: 'reader scale=$scale',
        );
      }
    });

    test('decodes maximum canonical magnitude payloads', () {
      const magnitudeLength = _decimalMagnitudeByteLimit;
      final magnitudeBytes = Uint8List(magnitudeLength)
        ..fillRange(0, magnitudeLength, 0xff);
      final magnitude = (BigInt.one << (magnitudeLength * 8)) - BigInt.one;

      for (final sign in <int>[0, 1]) {
        const scale = -17;
        final buffer =
            _decimalRootBuffer(scale)
              ..writeVarUint64(_bigDecimalHeader(magnitudeLength, sign))
              ..writeBytes(magnitudeBytes);

        expect(
          Fory().deserializeFrom<Decimal>(buffer),
          equals(Decimal(sign == 0 ? magnitude : -magnitude, scale)),
        );
      }
    });

    test('writer enforces magnitude byte limit', () {
      final fory = Fory();
      final maximum = Decimal(
        _magnitudeWithByteLength(_decimalMagnitudeByteLimit),
        0,
      );
      expect(fory.serialize(maximum), isNotEmpty);

      final oversized = Decimal(
        _magnitudeWithByteLength(_decimalMagnitudeByteLimit + 1),
        0,
      );
      expect(
        () => fory.serialize(oversized),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('Decimal magnitude length'),
          ),
        ),
      );
    });

    test('reader enforces magnitude byte limit before copying', () {
      final oversized = _decimalRootBuffer(0)
        ..writeVarUint64(_bigDecimalHeader(_decimalMagnitudeByteLimit + 1));
      expect(
        () => Fory().deserializeFrom<Decimal>(oversized),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('Decimal magnitude length'),
          ),
        ),
      );

      final truncated = _decimalRootBuffer(0)
        ..writeVarUint64(_bigDecimalHeader(_decimalMagnitudeByteLimit));
      expect(
        () => Fory().deserializeFrom<Decimal>(truncated),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('Insufficient readable bytes'),
          ),
        ),
      );
    });

    test('rejects non-canonical big decimal payloads', () {
      final fory = Fory();
      final zeroBigEncoding = Uint8List.fromList(<int>[
        0x01,
        0xff,
        TypeIds.decimal,
        0x00,
        0x01,
      ]);
      final trailingZeroPayload = Uint8List.fromList(<int>[
        0x01,
        0xff,
        TypeIds.decimal,
        0x00,
        0x09,
        0x01,
        0x00,
      ]);

      expect(
        () => fory.deserialize<Decimal>(zeroBigEncoding),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('Invalid decimal magnitude length'),
          ),
        ),
      );
      expect(
        () => fory.deserialize<Decimal>(trailingZeroPayload),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('trailing zero byte'),
          ),
        ),
      );
    });
  });
}

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

@ForyStruct()
class DecimalEnvelope {
  DecimalEnvelope();

  Decimal amount = Decimal.zero();
  String note = '';
}

Decimal _decimal(String unscaled, int scale) {
  return Decimal(BigInt.parse(unscaled), scale);
}

void _registerDecimalEnvelope(Fory fory) {
  DecimalSerializerTestFory.register(
    fory,
    DecimalEnvelope,
    namespace: 'test',
    typeName: 'DecimalEnvelope',
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

      final value = DecimalEnvelope()
        ..amount = _decimal('123456789012345678901234567890123456789', 37)
        ..note = 'principal';

      final roundTrip = fory.deserialize<DecimalEnvelope>(
        fory.serialize(value),
      );
      expect(roundTrip.amount, equals(value.amount));
      expect(roundTrip.note, equals('principal'));
    });

    test('rejects non-canonical big decimal payloads', () {
      final fory = Fory();
      final zeroBigEncoding = Uint8List.fromList(<int>[
        0x02,
        0xff,
        TypeIds.decimal,
        0x00,
        0x01,
      ]);
      final trailingZeroPayload = Uint8List.fromList(<int>[
        0x02,
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

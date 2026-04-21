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

void main() {
  group('Bfloat16', () {
    group('scalar conversions', () {
      test('positive zero', () {
        final bf = Bfloat16.fromFloat32(0.0);
        expect(bf.toBits(), equals(0x0000));
        expect(bf.toDouble(), equals(0.0));
      });

      test('negative zero', () {
        final bf = Bfloat16.fromFloat32(-0.0);
        expect(bf.toBits(), equals(0x8000));
        expect(bf.toDouble(), equals(-0.0));
        expect(bf.toDouble().isNegative, isTrue);
      });

      test('positive infinity', () {
        final bf = Bfloat16.fromFloat32(double.infinity);
        expect(bf.toBits(), equals(0x7F80));
        expect(bf.toDouble(), equals(double.infinity));
      });

      test('negative infinity', () {
        final bf = Bfloat16.fromFloat32(double.negativeInfinity);
        expect(bf.toBits(), equals(0xFF80));
        expect(bf.toDouble(), equals(double.negativeInfinity));
      });

      test('NaN', () {
        final bf = Bfloat16.fromFloat32(double.nan);
        expect(bf.toDouble().isNaN, isTrue);
      });

      test('one', () {
        final bf = Bfloat16.fromFloat32(1.0);
        expect(bf.toBits(), equals(0x3F80));
        expect(bf.toDouble(), equals(1.0));
      });

      test('negative one', () {
        final bf = Bfloat16.fromFloat32(-1.0);
        expect(bf.toBits(), equals(0xBF80));
        expect(bf.toDouble(), equals(-1.0));
      });

      test('small value round-trip', () {
        final bf = Bfloat16.fromFloat32(0.5);
        expect(bf.toDouble(), equals(0.5));
      });

      test('large value round-trip', () {
        final bf = Bfloat16.fromFloat32(256.0);
        expect(bf.toDouble(), equals(256.0));
      });

      test('fromBits masks to 16 bits', () {
        final bf = Bfloat16.fromBits(0x13F80);
        expect(bf.toBits(), equals(0x3F80));
      });

      test('num constructor', () {
        final bf = Bfloat16(1.5);
        expect(bf.toDouble(), equals(1.5));
      });

      test('subnormal Bfloat16', () {
        // Smallest positive subnormal: 0x0001
        final bf = Bfloat16.fromBits(0x0001);
        expect(bf.toDouble(), isNot(equals(0.0)));
        // Round-trip through fromFloat32
        final bf2 = Bfloat16.fromFloat32(bf.toDouble());
        expect(bf2.toBits(), equals(bf.toBits()));
      });

      test('max normal Bfloat16', () {
        // Largest finite Bfloat16: 0x7F7F
        final bf = Bfloat16.fromBits(0x7F7F);
        expect(bf.toDouble().isFinite, isTrue);
        expect(bf.toDouble().isNaN, isFalse);
      });

      test('min normal Bfloat16', () {
        // Smallest positive normal: 0x0080
        final bf = Bfloat16.fromBits(0x0080);
        expect(bf.toDouble(), greaterThan(0.0));
        expect(bf.toDouble().isFinite, isTrue);
      });
    });

    group('round-to-nearest ties-to-even', () {
      test('ties-to-even rounding', () {
        // 1.0 in Bfloat16 is 0x3F80, next is 0x3F81
        // midpoint between them should round to even
        final bf1 = Bfloat16.fromBits(0x3F80);
        final bf2 = Bfloat16.fromBits(0x3F81);
        final mid = (bf1.toDouble() + bf2.toDouble()) / 2;
        final bfMid = Bfloat16.fromFloat32(mid);
        // Should round to even (the one with LSB = 0)
        expect(bfMid.toBits() & 1, equals(0));
      });
    });

    group('equality and comparison', () {
      test('equal values', () {
        final a = Bfloat16.fromBits(0x3F80);
        final b = Bfloat16.fromBits(0x3F80);
        expect(a, equals(b));
        expect(a.hashCode, equals(b.hashCode));
      });

      test('different values', () {
        final a = Bfloat16.fromFloat32(1.0);
        final b = Bfloat16.fromFloat32(2.0);
        expect(a, isNot(equals(b)));
      });

      test('compareTo', () {
        final a = Bfloat16.fromFloat32(1.0);
        final b = Bfloat16.fromFloat32(2.0);
        expect(a.compareTo(b), lessThan(0));
        expect(b.compareTo(a), greaterThan(0));
        expect(a.compareTo(a), equals(0));
      });

      test('toString', () {
        final bf = Bfloat16.fromFloat32(1.0);
        expect(bf.toString(), equals('1.0'));
      });
    });

    group('buffer read/write', () {
      test('round-trip through buffer', () {
        final buffer = Buffer(64);
        final original = Bfloat16.fromFloat32(3.14);
        buffer.writeBfloat16(original);

        final readBuffer = Buffer.wrap(buffer.toBytes());
        final result = readBuffer.readBfloat16();

        expect(result.toBits(), equals(original.toBits()));
        expect(result.toDouble(), equals(original.toDouble()));
      });

      test('multiple values through buffer', () {
        final buffer = Buffer(64);
        final values = [
          Bfloat16.fromFloat32(0.0),
          Bfloat16.fromFloat32(1.0),
          Bfloat16.fromFloat32(-1.0),
          Bfloat16.fromFloat32(double.infinity),
          Bfloat16.fromFloat32(double.nan),
        ];

        for (final v in values) {
          buffer.writeBfloat16(v);
        }

        final readBuffer = Buffer.wrap(buffer.toBytes());
        for (int i = 0; i < values.length; i++) {
          final result = readBuffer.readBfloat16();
          if (values[i].toDouble().isNaN) {
            expect(result.toDouble().isNaN, isTrue);
          } else {
            expect(result.toBits(), equals(values[i].toBits()));
          }
        }
      });
    });

    group('Bfloat16 array serialization', () {
      test('packed Uint16List round-trip through buffer', () {
        final buffer = Buffer(128);

        final values = [1.0, 2.0, 0.5, -1.0, 0.0];
        final packed = Uint16List(values.length);
        for (int i = 0; i < values.length; i++) {
          packed[i] = Bfloat16.fromFloat32(values[i]).toBits();
        }

        // Write length + raw bytes
        buffer.writeVarUint32(packed.length);
        final bytes = packed.buffer.asUint8List(
          packed.offsetInBytes,
          packed.lengthInBytes,
        );
        buffer.writeBytes(bytes);

        // Read back
        final readBuffer = Buffer.wrap(buffer.toBytes());
        final length = readBuffer.readVarUint32();
        expect(length, equals(values.length));
        final rawBytes = readBuffer.copyBytes(length * 2);
        final result = rawBytes.buffer.asUint16List(
          rawBytes.offsetInBytes,
          rawBytes.lengthInBytes ~/ 2,
        );

        for (int i = 0; i < values.length; i++) {
          final bf = Bfloat16.fromBits(result[i]);
          if (values[i] == 0.0) {
            expect(bf.toDouble(), equals(0.0));
          } else {
            expect(bf.toDouble(),
                equals(Bfloat16.fromFloat32(values[i]).toDouble()));
          }
        }
      });
    });
  });
}

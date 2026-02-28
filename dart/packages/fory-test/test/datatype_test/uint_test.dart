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

import 'package:fory/src/datatype/uint8.dart';
import 'package:fory/src/datatype/uint16.dart';
import 'package:fory/src/datatype/uint32.dart';
import 'package:test/test.dart';

void main() {
  group('UInt8 behaviors', () {
    group('range & conversion', () {
      test('preserves in-range values', () {
        var a = UInt8(0);
        var b = UInt8(255);

        expect(a.value, 0);
        expect(b.value, 255);
      });

      test('wraps on overflow', () {
        var a = UInt8(256); // Overflow to 0
        var b = UInt8(257); // Overflow to 1
        var c = UInt8(512); // Overflow to 0
        var d = UInt8(-1); // Wraps to 255

        expect(a.value, 0);
        expect(b.value, 1);
        expect(c.value, 0);
        expect(d.value, 255);
      });

      test('truncates float inputs', () {
        var a = UInt8(42.7);
        var b = UInt8(255.9);

        expect(a.value, 42);
        expect(b.value, 255);
      });

      test('min/max constants and instances', () {
        expect(UInt8.MIN_VALUE, 0);
        expect(UInt8.MAX_VALUE, 255);
        expect(UInt8.minValue.value, 0);
        expect(UInt8.maxValue.value, 255);
      });
    });

    group('arithmetic operators with overflow', () {
      test('addition with overflow', () {
        var a = UInt8(200);
        var b = UInt8(100);
        var result = a + b;

        expect(result, isA<UInt8>());
        expect(result.value, 44); // 200 + 100 = 300, which overflows to 44
      });

      test('subtraction with underflow', () {
        var a = UInt8(50);
        var b = UInt8(100);
        var result = a - b;

        expect(result, isA<UInt8>());
        expect(result.value, 206); // 50 - 100 = -50, which wraps to 206
      });

      test('multiplication with overflow', () {
        var a = UInt8(20);
        var b = UInt8(20);
        var result = a * b;

        expect(result, isA<UInt8>());
        expect(result.value, 144); // 20 * 20 = 400, which overflows to 144
      });
    });

    group('comparison operators', () {
      test('less than', () {
        var a = UInt8(10);
        var b = UInt8(20);

        expect(a < b, isTrue);
        expect(b < a, isFalse);
        expect(a < 20, isTrue);
      });

      test('greater than', () {
        var a = UInt8(200);
        var b = UInt8(100);

        expect(a > b, isTrue);
        expect(b > a, isFalse);
        expect(a > 100, isTrue);
      });
    });

    group('equality & hashCode', () {
      test('equality', () {
        var a = UInt8(42);
        var b = UInt8(42);
        var c = UInt8(43);

        expect(a == b, isTrue);
        expect(a == c, isFalse);
        expect(a.value == 42, isTrue);
      });

      test('hashCode consistency', () {
        var a = UInt8(42);
        var b = UInt8(42);

        expect(a.hashCode == b.hashCode, isTrue);
      });
    });
  });

  group('UInt16 behaviors', () {
    group('range & conversion', () {
      test('preserves in-range values', () {
        var a = UInt16(0);
        var b = UInt16(65535);

        expect(a.value, 0);
        expect(b.value, 65535);
      });

      test('wraps on overflow', () {
        var a = UInt16(65536); // Overflow to 0
        var b = UInt16(65537); // Overflow to 1
        var c = UInt16(-1); // Wraps to 65535

        expect(a.value, 0);
        expect(b.value, 1);
        expect(c.value, 65535);
      });

      test('min/max constants', () {
        expect(UInt16.MIN_VALUE, 0);
        expect(UInt16.MAX_VALUE, 65535);
        expect(UInt16.minValue.value, 0);
        expect(UInt16.maxValue.value, 65535);
      });
    });

    group('arithmetic operators', () {
      test('addition with overflow', () {
        var a = UInt16(60000);
        var b = UInt16(10000);
        var result = a + b;

        expect(result, isA<UInt16>());
        expect(result.value, 4464); // 70000 overflows to 4464
      });

      test('subtraction with underflow', () {
        var a = UInt16(1000);
        var b = UInt16(2000);
        var result = a - b;

        expect(result, isA<UInt16>());
        expect(result.value, 64536); // -1000 wraps to 64536
      });
    });
  });

  group('UInt32 behaviors', () {
    group('range & conversion', () {
      test('preserves in-range values', () {
        var a = UInt32(0);
        var b = UInt32(4294967295);

        expect(a.value, 0);
        expect(b.value, 4294967295);
      });

      test('wraps on overflow', () {
        var a = UInt32(4294967296); // Overflow to 0
        var b = UInt32(4294967297); // Overflow to 1
        var c = UInt32(-1); // Wraps to 4294967295

        expect(a.value, 0);
        expect(b.value, 1);
        expect(c.value, 4294967295);
      });

      test('min/max constants', () {
        expect(UInt32.MIN_VALUE, 0);
        expect(UInt32.MAX_VALUE, 4294967295);
        expect(UInt32.minValue.value, 0);
        expect(UInt32.maxValue.value, 4294967295);
      });
    });

    group('arithmetic operators', () {
      test('addition with overflow', () {
        var a = UInt32(4000000000);
        var b = UInt32(500000000);
        var result = a + b;

        expect(result, isA<UInt32>());
        expect(result.value, 205032704); // 4500000000 % (2^32) = 205032704
      });

      test('large value operations', () {
        var a = UInt32(2147483648); // Half of max value
        var b = UInt32(2147483648);
        var result = a + b;

        expect(result, isA<UInt32>());
        expect(result.value, 0); // Exactly overflows to 0
      });
    });
  });
}

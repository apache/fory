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

const bool _isWeb = bool.fromEnvironment('dart.library.js_util');

final BigInt _mask32 = (BigInt.one << 32) - BigInt.one;
final BigInt _mask64 = (BigInt.one << 64) - BigInt.one;
final BigInt _signBit64 = BigInt.one << 63;
final BigInt _jsSafeMax = (BigInt.one << 53) - BigInt.one;
final BigInt _jsSafeMin = -_jsSafeMax;

BigInt _normalizeSigned64(BigInt value) {
  final masked = value & _mask64;
  return masked & _signBit64 == BigInt.zero
      ? masked
      : masked - (BigInt.one << 64);
}

BigInt _normalizeUnsigned64(BigInt value) => value & _mask64;

bool _isJsSafeSigned64(BigInt value) =>
    value >= _jsSafeMin && value <= _jsSafeMax;

bool _isJsSafeUnsigned64(BigInt value) =>
    value >= BigInt.zero && value <= _jsSafeMax;

void _expectInt64State(
  Int64 actual,
  BigInt expected, {
  String? reason,
}) {
  final normalized = _normalizeSigned64(expected);
  final low32 = (normalized & _mask32).toInt();
  final high32 = ((normalized >> 32) & _mask32).toInt();

  expect(actual.toBigInt(), equals(normalized), reason: reason);
  expect(actual.low32, equals(low32), reason: reason);
  expect(actual.high32Unsigned, equals(high32), reason: reason);
  expect(actual.high32Signed, equals(high32.toSigned(32)), reason: reason);
  expect(actual.isZero, equals(normalized == BigInt.zero), reason: reason);
  expect(actual.isNegative, equals(normalized.isNegative), reason: reason);

  if (_isWeb && !_isJsSafeSigned64(normalized)) {
    expect(() => actual.toInt(), throwsStateError, reason: reason);
    return;
  }
  expect(BigInt.from(actual.toInt()), equals(normalized), reason: reason);
}

void _expectUint64State(
  Uint64 actual,
  BigInt expected, {
  String? reason,
}) {
  final normalized = _normalizeUnsigned64(expected);
  final low32 = (normalized & _mask32).toInt();
  final high32 = ((normalized >> 32) & _mask32).toInt();

  expect(actual.toBigInt(), equals(normalized), reason: reason);
  expect(actual.low32, equals(low32), reason: reason);
  expect(actual.high32Unsigned, equals(high32), reason: reason);
  expect(actual.isZero, equals(normalized == BigInt.zero), reason: reason);

  if (_isWeb) {
    if (!_isJsSafeUnsigned64(normalized)) {
      expect(() => actual.toInt(), throwsStateError, reason: reason);
      return;
    }
  } else if (normalized >= _signBit64) {
    expect(() => actual.toInt(), throwsStateError, reason: reason);
    return;
  }

  expect(BigInt.from(actual.toInt()), equals(normalized), reason: reason);
}

void main() {
  group('Int64', () {
    test('constructors normalize words and hex edge cases', () {
      final cases = <({String name, Int64 value, BigInt expected})>[
        (name: 'zero', value: Int64.fromWords(0, 0), expected: BigInt.zero),
        (name: 'one', value: Int64.fromWords(1, 0), expected: BigInt.one),
        (
          name: '2^22',
          value: Int64.fromWords(0x00400000, 0),
          expected: BigInt.one << 22,
        ),
        (
          name: '2^23',
          value: Int64.fromWords(0x00800000, 0),
          expected: BigInt.one << 23,
        ),
        (
          name: '2^31',
          value: Int64.fromWords(0x80000000, 0),
          expected: BigInt.one << 31,
        ),
        (
          name: '2^32',
          value: Int64.fromWords(0, 1),
          expected: BigInt.one << 32,
        ),
        (
          name: '2^43',
          value: Int64.fromWords(0, 0x00000800),
          expected: BigInt.one << 43,
        ),
        (
          name: '2^44',
          value: Int64.fromWords(0, 0x00001000),
          expected: BigInt.one << 44,
        ),
        (
          name: '2^45',
          value: Int64.fromWords(0, 0x00002000),
          expected: BigInt.one << 45,
        ),
        (
          name: '2^62',
          value: Int64.fromWords(0, 0x40000000),
          expected: BigInt.one << 62,
        ),
        (
          name: 'signed min',
          value: Int64.fromWords(0, 0x80000000),
          expected: -(BigInt.one << 63),
        ),
        (
          name: 'signed max',
          value: Int64.fromWords(0xffffffff, 0x7fffffff),
          expected: (BigInt.one << 63) - BigInt.one,
        ),
        (
          name: 'negative one',
          value: Int64.fromWords(0xffffffff, 0xffffffff),
          expected: -BigInt.one,
        ),
        (
          name: 'parseHex signed min',
          value: Int64.parseHex('8000000000000000'),
          expected: -(BigInt.one << 63),
        ),
        (
          name: 'parseHex negative',
          value: Int64.parseHex('-7'),
          expected: BigInt.from(-7),
        ),
        (
          name: 'parseHex wraparound',
          value: Int64.parseHex('ffffffffffffffff'),
          expected: -BigInt.one,
        ),
      ];

      for (final testCase in cases) {
        _expectInt64State(
          testCase.value,
          testCase.expected,
          reason: testCase.name,
        );
      }
    });

    test('equality and compare respect signed ordering', () {
      final values = <Int64>[
        Int64.parseHex('8000000000000000'),
        Int64.parseHex('ffffffffffffffff'),
        Int64(0),
        Int64(1),
        Int64.parseHex('7fffffffffffffff'),
      ];

      expect(values[0] == values[0], isTrue);
      expect(values[0].compareTo(values[1]), lessThan(0));
      expect(values[1].compareTo(values[2]), lessThan(0));
      expect(values[2].compareTo(values[3]), lessThan(0));
      expect(values[3].compareTo(values[4]), lessThan(0));
      expect(values[4].compareTo(values[4]), equals(0));
      expect(values[1], equals(Int64.fromWords(0xffffffff, 0xffffffff)));
    });

    test('arithmetic wraps, borrows, and truncates with sign edge cases', () {
      _expectInt64State(
        Int64.parseHex('7fffffffffffffff') + 1,
        -(BigInt.one << 63),
        reason: 'signed overflow add',
      );
      _expectInt64State(
        Int64.parseHex('8000000000000000') - 1,
        (BigInt.one << 63) - BigInt.one,
        reason: 'signed underflow subtract',
      );
      _expectInt64State(
        Int64(-1) + Int64(1),
        BigInt.zero,
        reason: 'wrapper addition',
      );
      _expectInt64State(
        Int64(0) - 1,
        -BigInt.one,
        reason: 'borrow from zero',
      );
      _expectInt64State(
        Int64.parseHex('4000000000000000') * 2,
        -(BigInt.one << 63),
        reason: 'multiply overflow',
      );
      _expectInt64State(
        Int64.parseHex('8000000000000000') * -1,
        -(BigInt.one << 63),
        reason: 'multiply signed min by -1 wraps',
      );
      _expectInt64State(
        Int64.parseHex('-7') ~/ 3,
        BigInt.from(-2),
        reason: 'truncating division toward zero',
      );
      _expectInt64State(
        Int64.parseHex('-7') % 3,
        BigInt.from(2),
        reason: 'remainder follows Dart int semantics',
      );
      expect(Int64.parseHex('-7') / 2, equals(-3.5));
      expect((-Int64(1)).toBigInt(), equals(BigInt.from(-1)));
      expect((-Int64.parseHex('8000000000000000')).toBigInt(),
          equals(-(BigInt.one << 63)));
    });

    test('bitwise operators and shifts cross segment boundaries', () {
      final left = Int64.parseHex('f23456789abcdef0');
      final right = Int64.parseHex('00ff00ff00ff00ff');
      final leftBig = left.toBigInt();
      final rightBig = right.toBigInt();

      _expectInt64State(left & right, _normalizeSigned64(leftBig & rightBig));
      _expectInt64State(left | right, _normalizeSigned64(leftBig | rightBig));
      _expectInt64State(left ^ right, _normalizeSigned64(leftBig ^ rightBig));
      _expectInt64State(~left, _normalizeSigned64(~leftBig));
      _expectInt64State(Int64(0x7f) & 0x3, BigInt.from(0x3));
      _expectInt64State(Int64(0x7f) | Int64(0x80), BigInt.from(0xff));
      _expectInt64State(Int64(0x7f) ^ 0x3, BigInt.from(0x7c));

      const shifts = <int>[
        0,
        1,
        21,
        22,
        23,
        31,
        32,
        43,
        44,
        45,
        62,
        63,
        64,
        65
      ];
      for (final shift in shifts) {
        _expectInt64State(
          left << shift,
          _normalizeSigned64(leftBig << shift),
          reason: 'left shift $shift',
        );
        _expectInt64State(
          left >> shift,
          _normalizeSigned64(leftBig >> shift),
          reason: 'right shift $shift',
        );
        _expectInt64State(
          left >>> shift,
          _normalizeSigned64(_normalizeUnsigned64(leftBig) >> shift),
          reason: 'logical shift $shift',
        );
      }

      expect(() => left << -1, throwsArgumentError);
      expect(() => left >> -1, throwsArgumentError);
      expect(() => left >>> -1, throwsArgumentError);
    });

    test('toInt respects JS-safe and platform-specific unsafe boundaries', () {
      _expectInt64State(
        Int64.parseHex('1fffffffffffff'),
        _jsSafeMax,
        reason: 'safe positive max',
      );
      _expectInt64State(
        Int64.parseHex('-1fffffffffffff'),
        _jsSafeMin,
        reason: 'safe negative min',
      );
      _expectInt64State(
        Int64.parseHex('20000000000000'),
        BigInt.one << 53,
        reason: 'unsafe positive boundary',
      );
      _expectInt64State(
        Int64.parseHex('-20000000000000'),
        -(BigInt.one << 53),
        reason: 'unsafe negative boundary',
      );
    });

    test('list sublistView respects source typed-data element ranges', () {
      final bytes = Uint8List(24);
      final data = ByteData.sublistView(bytes);
      data.setUint32(8, 0x76543210, Endian.little);
      data.setUint32(12, 0x01234567, Endian.little);

      final byteView = Int64List.sublistView(bytes, 8, 16);
      expect(byteView.length, equals(1));
      expect(byteView[0], equals(Int64.fromWords(0x76543210, 0x01234567)));

      byteView[0] = Int64.fromWords(0x89abcdef, 0x76543210);
      expect(data.getUint32(8, Endian.little), equals(0x89abcdef));
      expect(data.getUint32(12, Endian.little), equals(0x76543210));

      final wordView =
          Int64List.sublistView(Uint32List.view(bytes.buffer), 2, 4);
      expect(wordView.length, equals(1));
      expect(wordView[0], equals(Int64.fromWords(0x89abcdef, 0x76543210)));

      expect(() => Int64List.sublistView(bytes, 1, 9), throwsArgumentError);
      expect(() => Int64List.sublistView(bytes, 8, 15), throwsArgumentError);
    });
  });

  group('Uint64', () {
    test('constructors normalize words and hex edge cases', () {
      final cases = <({String name, Uint64 value, BigInt expected})>[
        (name: 'zero', value: Uint64.fromWords(0, 0), expected: BigInt.zero),
        (name: 'one', value: Uint64.fromWords(1, 0), expected: BigInt.one),
        (
          name: '2^22',
          value: Uint64.fromWords(0x00400000, 0),
          expected: BigInt.one << 22,
        ),
        (
          name: '2^23',
          value: Uint64.fromWords(0x00800000, 0),
          expected: BigInt.one << 23,
        ),
        (
          name: '2^31',
          value: Uint64.fromWords(0x80000000, 0),
          expected: BigInt.one << 31,
        ),
        (
          name: '2^32',
          value: Uint64.fromWords(0, 1),
          expected: BigInt.one << 32,
        ),
        (
          name: '2^43',
          value: Uint64.fromWords(0, 0x00000800),
          expected: BigInt.one << 43,
        ),
        (
          name: '2^44',
          value: Uint64.fromWords(0, 0x00001000),
          expected: BigInt.one << 44,
        ),
        (
          name: '2^45',
          value: Uint64.fromWords(0, 0x00002000),
          expected: BigInt.one << 45,
        ),
        (
          name: '2^62',
          value: Uint64.fromWords(0, 0x40000000),
          expected: BigInt.one << 62,
        ),
        (
          name: '2^63',
          value: Uint64.fromWords(0, 0x80000000),
          expected: BigInt.one << 63,
        ),
        (
          name: 'max',
          value: Uint64.fromWords(0xffffffff, 0xffffffff),
          expected: _mask64,
        ),
        (
          name: 'parseHex max',
          value: Uint64.parseHex('ffffffffffffffff'),
          expected: _mask64,
        ),
        (
          name: 'parseHex negative one wraps',
          value: Uint64.parseHex('-1'),
          expected: _mask64,
        ),
      ];

      for (final testCase in cases) {
        _expectUint64State(
          testCase.value,
          testCase.expected,
          reason: testCase.name,
        );
      }
    });

    test('equality and compare respect unsigned ordering', () {
      final values = <Uint64>[
        Uint64(0),
        Uint64(1),
        Uint64.parseHex('7fffffffffffffff'),
        Uint64.parseHex('8000000000000000'),
        Uint64.parseHex('ffffffffffffffff'),
      ];

      expect(values[0] == values[0], isTrue);
      expect(values[0].compareTo(values[1]), lessThan(0));
      expect(values[1].compareTo(values[2]), lessThan(0));
      expect(values[2].compareTo(values[3]), lessThan(0));
      expect(values[3].compareTo(values[4]), lessThan(0));
      expect(values[4].compareTo(values[4]), equals(0));
      expect(values[4], equals(Uint64.fromWords(0xffffffff, 0xffffffff)));
    });

    test('arithmetic wraps, borrows, and truncates across the full range', () {
      _expectUint64State(
        Uint64.parseHex('ffffffffffffffff') + 1,
        BigInt.zero,
        reason: 'overflow add',
      );
      _expectUint64State(
        Uint64(0) - 1,
        _mask64,
        reason: 'borrow from zero',
      );
      _expectUint64State(
        Uint64.parseHex('8000000000000000') * 2,
        BigInt.zero,
        reason: 'multiply overflow',
      );
      _expectUint64State(
        Uint64.parseHex('ffffffffffffffff') * 2,
        _mask64 - BigInt.one,
        reason: 'multiply max by two',
      );
      _expectUint64State(
        Uint64.parseHex('ffffffffffffffff') ~/ 3,
        _mask64 ~/ BigInt.from(3),
        reason: 'truncating division',
      );
      _expectUint64State(
        Uint64.parseHex('ffffffffffffffff') % 3,
        BigInt.from(0),
        reason: 'remainder against three',
      );
      expect(Uint64.parseHex('7') / 2, equals(3.5));
      expect((-Uint64(1)).toBigInt(), equals(_mask64));
      expect((-Uint64.parseHex('8000000000000000')).toBigInt(),
          equals(BigInt.one << 63));
    });

    test('bitwise operators and shifts cross segment boundaries', () {
      final left = Uint64.parseHex('f23456789abcdef0');
      final right = Uint64.parseHex('00ff00ff00ff00ff');
      final leftBig = left.toBigInt();
      final rightBig = right.toBigInt();

      _expectUint64State(
          left & right, _normalizeUnsigned64(leftBig & rightBig));
      _expectUint64State(
          left | right, _normalizeUnsigned64(leftBig | rightBig));
      _expectUint64State(
          left ^ right, _normalizeUnsigned64(leftBig ^ rightBig));
      _expectUint64State(~left, _normalizeUnsigned64(~leftBig));
      _expectUint64State(Uint64(0x7f) & 0x3, BigInt.from(0x3));
      _expectUint64State(Uint64(0x7f) | Uint64(0x80), BigInt.from(0xff));
      _expectUint64State(Uint64(0x7f) ^ 0x3, BigInt.from(0x7c));

      const shifts = <int>[
        0,
        1,
        21,
        22,
        23,
        31,
        32,
        43,
        44,
        45,
        62,
        63,
        64,
        65
      ];
      for (final shift in shifts) {
        _expectUint64State(
          left << shift,
          _normalizeUnsigned64(leftBig << shift),
          reason: 'left shift $shift',
        );
        _expectUint64State(
          left >> shift,
          _normalizeUnsigned64(leftBig >> shift),
          reason: 'right shift $shift',
        );
        _expectUint64State(
          left >>> shift,
          _normalizeUnsigned64(leftBig >> shift),
          reason: 'logical shift $shift',
        );
      }

      expect(() => left << -1, throwsArgumentError);
      expect(() => left >> -1, throwsArgumentError);
      expect(() => left >>> -1, throwsArgumentError);
    });

    test('toInt respects JS-safe and native unsigned boundaries', () {
      _expectUint64State(
        Uint64.parseHex('1fffffffffffff'),
        _jsSafeMax,
        reason: 'safe positive max',
      );
      _expectUint64State(
        Uint64.parseHex('20000000000000'),
        BigInt.one << 53,
        reason: 'unsafe positive boundary',
      );
      _expectUint64State(
        Uint64.parseHex('8000000000000000'),
        BigInt.one << 63,
        reason: 'native-unsafe high-bit boundary',
      );
      _expectUint64State(
        Uint64.parseHex('ffffffffffffffff'),
        _mask64,
        reason: 'max boundary',
      );
    });

    test('list sublistView respects source typed-data element ranges', () {
      final bytes = Uint8List(24);
      final data = ByteData.sublistView(bytes);
      data.setUint32(8, 0xfedcba98, Endian.little);
      data.setUint32(12, 0x01234567, Endian.little);

      final byteView = Uint64List.sublistView(bytes, 8, 16);
      expect(byteView.length, equals(1));
      expect(byteView[0], equals(Uint64.fromWords(0xfedcba98, 0x01234567)));

      byteView[0] = Uint64.fromWords(0x76543210, 0xfedcba98);
      expect(data.getUint32(8, Endian.little), equals(0x76543210));
      expect(data.getUint32(12, Endian.little), equals(0xfedcba98));

      final wordView =
          Uint64List.sublistView(Uint32List.view(bytes.buffer), 2, 4);
      expect(wordView.length, equals(1));
      expect(wordView[0], equals(Uint64.fromWords(0x76543210, 0xfedcba98)));

      expect(() => Uint64List.sublistView(bytes, 1, 9), throwsArgumentError);
      expect(() => Uint64List.sublistView(bytes, 8, 15), throwsArgumentError);
    });
  });
}

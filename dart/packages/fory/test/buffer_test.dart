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

Int64 _i64Hex(String value) => Int64.parseHex(value);

Uint64 _u64Hex(String value) => Uint64.parseHex(value);

Int64 _i64Pow2(int shift) => Int64(1) << shift;

Uint64 _u64Pow2(int shift) => Uint64(1) << shift;

const int _jsSafeIntMax = 9007199254740991;
const int _jsSafeIntMin = -9007199254740991;
const int _jsUnsafeInt = 9007199254740992;

void main() {
  group('Buffer', () {
    test('round-trips fixed-width primitives, 16-bit floats, and bytes', () {
      final buffer = Buffer();
      final half = Float16(-2.5);
      final brain = Bfloat16(-3.5);

      buffer.writeBool(false);
      buffer.writeBool(true);
      buffer.writeByte(-128);
      buffer.writeUint8(255);
      buffer.writeInt16(-32768);
      buffer.writeUint16(0xffff);
      buffer.writeInt32(-0x80000000);
      buffer.writeUint32(0xffffffff);
      buffer.writeInt64(_i64Hex('8000000000000000'));
      buffer.writeUint64(_u64Hex('ffffffffffffffff'));
      buffer.writeFloat16(half);
      buffer.writeBfloat16(brain);
      buffer.writeFloat32(1.5);
      buffer.writeFloat64(2.5);
      buffer.writeBytes([1, 2, 3, 4]);

      expect(buffer.readBool(), isFalse);
      expect(buffer.readBool(), isTrue);
      expect(buffer.readByte(), equals(-128));
      expect(buffer.readUint8(), equals(255));
      expect(buffer.readInt16(), equals(-32768));
      expect(buffer.readUint16(), equals(0xffff));
      expect(buffer.readInt32(), equals(-0x80000000));
      expect(buffer.readUint32(), equals(0xffffffff));
      expect(buffer.readInt64(), equals(_i64Hex('8000000000000000')));
      expect(buffer.readUint64(), equals(_u64Hex('ffffffffffffffff')));
      expect(buffer.readFloat16(), equals(half));
      expect(buffer.readBfloat16(), equals(brain));
      expect(buffer.readFloat32(), closeTo(1.5, 0.0001));
      expect(buffer.readFloat64(), equals(2.5));
      expect(buffer.readBytes(4), orderedEquals([1, 2, 3, 4]));
      expect(buffer.readableBytes, equals(0));
    });

    test('grows, shares toBytes storage, and preserves content', () {
      final buffer = Buffer(1);
      final payload = List<int>.generate(600, (index) => index & 0xff);

      buffer.writeBytes(payload);

      expect(buffer.toBytes(), orderedEquals(payload));

      final written = buffer.toBytes();
      written[0] = 99;
      written[written.length - 1] = 7;

      expect(buffer.toBytes().first, equals(99));
      expect(buffer.toBytes().last, equals(7));
    });

    test('grows from zero-capacity storage', () {
      final buffer = Buffer(0);
      buffer.writeBytes([1, 2, 3]);
      expect(buffer.toBytes(), orderedEquals([1, 2, 3]));

      final wrapped = Buffer.wrap(Uint8List(0));
      wrapped.writeUint8(4);
      expect(wrapped.toBytes(), orderedEquals([4]));
    });

    test(
      'wrap constructor, wrap method, read views, copyBytes, skip, and clear manage indices',
      () {
        final backing = Uint8List.fromList([10, 20, 30, 40, 50]);
        final buffer = Buffer.wrap(backing);

        expect(buffer.readableBytes, equals(5));

        buffer.skip(1);
        expect(buffer.readableBytes, equals(4));

        final view = buffer.readBytes(2);
        expect(view, orderedEquals([20, 30]));
        view[0] = 99;
        expect(backing[1], equals(99));

        final copy = buffer.copyBytes(1);
        expect(copy, orderedEquals([40]));
        copy[0] = 7;
        expect(backing[3], equals(40));

        expect(buffer.readUint8(), equals(50));
        expect(buffer.readableBytes, equals(0));

        buffer.clear();
        expect(buffer.readableBytes, equals(0));
        buffer.writeBytes([1, 2]);
        expect(buffer.toBytes(), orderedEquals([1, 2]));

        final replacement = Uint8List.fromList([7, 8, 9]);
        buffer.wrap(replacement);
        expect(buffer.readableBytes, equals(3));
        expect(buffer.readBytes(3), orderedEquals([7, 8, 9]));
      },
    );

    test('round-trips UTF-8 strings with length prefixes', () {
      final buffer = Buffer();
      const ascii = 'Apache Fory';
      const unicode = '你好，Fory🙂';

      buffer.writeUtf8('');
      buffer.writeUtf8(ascii);
      buffer.writeUtf8(unicode);

      expect(buffer.readUtf8(), equals(''));
      expect(buffer.readUtf8(), equals(ascii));
      expect(buffer.readUtf8(), equals(unicode));
      expect(buffer.readableBytes, equals(0));
    });

    test('round-trips varuint32 boundary values with Java-aligned lengths', () {
      const cases = <({int bytes, int value})>[
        (bytes: 1, value: 0),
        (bytes: 1, value: 1),
        (bytes: 1, value: 1 << 6),
        (bytes: 2, value: 1 << 7),
        (bytes: 2, value: 1 << 13),
        (bytes: 3, value: 1 << 14),
        (bytes: 3, value: 1 << 20),
        (bytes: 4, value: 1 << 21),
        (bytes: 4, value: 1 << 27),
        (bytes: 5, value: 1 << 28),
        (bytes: 5, value: 0x7fffffff),
        (bytes: 5, value: 0xffffffff),
      ];

      for (final testCase in cases) {
        _expectEncodedIntRoundTrip(
          value: testCase.value,
          expectedBytes: testCase.bytes,
          write: (buffer, value) => buffer.writeVarUint32(value),
          read: (buffer) => buffer.readVarUint32(),
          cursorRead: (cursor) => cursor.readVarUint32(),
        );
      }
    });

    test('round-trips varint32 boundary values with Java-aligned lengths', () {
      const cases = <({int bytes, int value})>[
        (bytes: 1, value: 0),
        (bytes: 1, value: 1),
        (bytes: 1, value: -1),
        (bytes: 1, value: 1 << 5),
        (bytes: 1, value: -64),
        (bytes: 2, value: 1 << 6),
        (bytes: 2, value: 1 << 12),
        (bytes: 2, value: -8192),
        (bytes: 3, value: 1 << 13),
        (bytes: 3, value: 1 << 19),
        (bytes: 3, value: -1048576),
        (bytes: 4, value: 1 << 20),
        (bytes: 4, value: 1 << 26),
        (bytes: 4, value: -134217728),
        (bytes: 5, value: 1 << 27),
        (bytes: 5, value: 0x7fffffff),
        (bytes: 5, value: -0x80000000),
      ];

      for (final testCase in cases) {
        _expectEncodedIntRoundTrip(
          value: testCase.value,
          expectedBytes: testCase.bytes,
          write: (buffer, value) => buffer.writeVarInt32(value),
          read: (buffer) => buffer.readVarInt32(),
          cursorRead: (cursor) => cursor.readVarInt32(),
        );
      }
    });

    test('round-trips varuint64 boundary values with Java-aligned lengths', () {
      final cases = <({int bytes, Uint64 value})>[
        (bytes: 1, value: Uint64(0)),
        (bytes: 1, value: Uint64(1)),
        (bytes: 1, value: _u64Pow2(6)),
        (bytes: 2, value: _u64Pow2(7)),
        (bytes: 2, value: _u64Pow2(13)),
        (bytes: 3, value: _u64Pow2(14)),
        (bytes: 3, value: _u64Pow2(20)),
        (bytes: 4, value: _u64Pow2(21)),
        (bytes: 4, value: _u64Pow2(27)),
        (bytes: 5, value: _u64Pow2(28)),
        (bytes: 5, value: _u64Pow2(34)),
        (bytes: 6, value: _u64Pow2(35)),
        (bytes: 6, value: _u64Pow2(41)),
        (bytes: 7, value: _u64Pow2(42)),
        (bytes: 7, value: _u64Pow2(48)),
        (bytes: 8, value: _u64Pow2(49)),
        (bytes: 8, value: _u64Pow2(55)),
        (bytes: 9, value: _u64Pow2(56)),
        (bytes: 9, value: _u64Pow2(62)),
        (bytes: 9, value: _u64Hex('7fffffffffffffff')),
        (bytes: 9, value: _u64Hex('8000000000000000')),
        (bytes: 9, value: _u64Hex('ffffffffffffffff')),
      ];

      for (final testCase in cases) {
        _expectEncodedUint64RoundTrip(
          value: testCase.value,
          expectedBytes: testCase.bytes,
          write: (buffer, value) => buffer.writeVarUint64(value),
          read: (buffer) => buffer.readVarUint64(),
          cursorRead: (cursor) => cursor.readVarUint64(),
        );
      }
    });

    test('round-trips varint64 boundary values with Java-aligned lengths', () {
      final cases = <({int bytes, Int64 value})>[
        (bytes: 1, value: Int64(0)),
        (bytes: 1, value: Int64(1)),
        (bytes: 1, value: Int64(-1)),
        (bytes: 1, value: Int64(-64)),
        (bytes: 2, value: _i64Pow2(6)),
        (bytes: 2, value: Int64(-128)),
        (bytes: 3, value: _i64Pow2(13)),
        (bytes: 3, value: Int64(-16384)),
        (bytes: 4, value: _i64Pow2(20)),
        (bytes: 4, value: Int64(-2097152)),
        (bytes: 5, value: _i64Pow2(27)),
        (bytes: 5, value: Int64(-268435456)),
        (bytes: 6, value: _i64Pow2(34)),
        (bytes: 6, value: -_i64Pow2(35)),
        (bytes: 7, value: _i64Pow2(42)),
        (bytes: 7, value: -_i64Pow2(42)),
        (bytes: 8, value: _i64Pow2(49)),
        (bytes: 8, value: -_i64Pow2(49)),
        (bytes: 9, value: _i64Pow2(55)),
        (bytes: 9, value: -_i64Pow2(56)),
        (bytes: 9, value: _i64Hex('7fffffffffffffff')),
        (bytes: 9, value: _i64Hex('8000000000000000')),
      ];

      for (final testCase in cases) {
        _expectEncodedInt64RoundTrip(
          value: testCase.value,
          expectedBytes: testCase.bytes,
          write: (buffer, value) => buffer.writeVarInt64(value),
          read: (buffer) => buffer.readVarInt64(),
          cursorRead: (cursor) => cursor.readVarInt64(),
        );
      }
    });

    test('round-trips tagged int64 boundary values with Java-aligned lengths',
        () {
      final cases = <({int bytes, Int64 value})>[
        (bytes: 4, value: Int64(-0x40000000)),
        (bytes: 4, value: Int64(-1)),
        (bytes: 4, value: Int64(0)),
        (bytes: 4, value: Int64(1)),
        (bytes: 4, value: Int64(1 << 28)),
        (bytes: 4, value: Int64(0x3fffffff)),
        (bytes: 9, value: Int64(-0x40000001)),
        (bytes: 9, value: Int64(0x40000000)),
        (bytes: 9, value: _i64Hex('7fffffffffffffff')),
        (bytes: 9, value: _i64Hex('8000000000000000')),
      ];

      for (final testCase in cases) {
        _expectEncodedInt64RoundTrip(
          value: testCase.value,
          expectedBytes: testCase.bytes,
          write: (buffer, value) => buffer.writeTaggedInt64(value),
          read: (buffer) => buffer.readTaggedInt64(),
          cursorRead: (cursor) => cursor.readTaggedInt64(),
        );
      }
    });

    test(
      'round-trips tagged uint64 boundary values with Java-aligned lengths',
      () {
        final cases = <({int bytes, Uint64 value})>[
          (bytes: 4, value: Uint64(0)),
          (bytes: 4, value: Uint64(1)),
          (bytes: 4, value: Uint64(1 << 30)),
          (bytes: 4, value: Uint64(0x7fffffff)),
          (bytes: 9, value: Uint64(0x80000000)),
          (bytes: 9, value: _u64Pow2(32)),
          (bytes: 9, value: _u64Hex('7fffffffffffffff')),
          (bytes: 9, value: _u64Hex('8000000000000000')),
          (bytes: 9, value: _u64Hex('ffffffffffffffff')),
        ];

        for (final testCase in cases) {
          _expectEncodedUint64RoundTrip(
            value: testCase.value,
            expectedBytes: testCase.bytes,
            write: (buffer, value) => buffer.writeTaggedUint64(value),
            read: (buffer) => buffer.readTaggedUint64(),
            cursorRead: (cursor) => cursor.readTaggedUint64(),
          );
        }
      },
    );

    test('int64 int helpers match Int64 wrapper encodings at safe boundaries',
        () {
      const cases = <int>[
        _jsSafeIntMin,
        -0x40000001,
        -0x40000000,
        -1,
        0,
        1,
        0x3fffffff,
        0x40000000,
        _jsSafeIntMax,
      ];

      for (final value in cases) {
        _expectInt64IntHelperMatchesWrapper(
          value: value,
          writeInt: (buffer, value) => buffer.writeInt64FromInt(value),
          writeWrapper: (buffer, value) => buffer.writeInt64(Int64(value)),
          readInt: (buffer) => buffer.readInt64AsInt(),
          cursorWriteInt: (cursor, value) => cursor.writeInt64FromInt(value),
          cursorWriteWrapper: (cursor, value) =>
              cursor.writeInt64(Int64(value)),
          cursorReadInt: (cursor) => cursor.readInt64AsInt(),
        );
        _expectInt64IntHelperMatchesWrapper(
          value: value,
          writeInt: (buffer, value) => buffer.writeVarInt64FromInt(value),
          writeWrapper: (buffer, value) => buffer.writeVarInt64(Int64(value)),
          readInt: (buffer) => buffer.readVarInt64AsInt(),
          cursorWriteInt: (cursor, value) => cursor.writeVarInt64FromInt(value),
          cursorWriteWrapper: (cursor, value) =>
              cursor.writeVarInt64(Int64(value)),
          cursorReadInt: (cursor) => cursor.readVarInt64AsInt(),
        );
        _expectInt64IntHelperMatchesWrapper(
          value: value,
          writeInt: (buffer, value) => buffer.writeTaggedInt64FromInt(value),
          writeWrapper: (buffer, value) =>
              buffer.writeTaggedInt64(Int64(value)),
          readInt: (buffer) => buffer.readTaggedInt64AsInt(),
          cursorWriteInt: (cursor, value) =>
              cursor.writeTaggedInt64FromInt(value),
          cursorWriteWrapper: (cursor, value) =>
              cursor.writeTaggedInt64(Int64(value)),
          cursorReadInt: (cursor) => cursor.readTaggedInt64AsInt(),
        );
      }
    });

    test(
        'uint64 cursor int helpers match Uint64 wrapper encodings at safe boundaries',
        () {
      const cases = <int>[
        0,
        1,
        0x7fffffff,
        0x80000000,
        0xffffffff,
        _jsSafeIntMax,
      ];

      for (final value in cases) {
        _expectUint64CursorIntHelperMatchesWrapper(
          value: value,
          cursorWriteInt: (cursor, value) => cursor.writeUint64FromInt(value),
          cursorWriteWrapper: (cursor, value) => cursor.writeUint64(value),
        );
        _expectUint64CursorIntHelperMatchesWrapper(
          value: value,
          cursorWriteInt: (cursor, value) =>
              cursor.writeVarUint64FromInt(value),
          cursorWriteWrapper: (cursor, value) => cursor.writeVarUint64(value),
        );
        _expectUint64CursorIntHelperMatchesWrapper(
          value: value,
          cursorWriteInt: (cursor, value) =>
              cursor.writeTaggedUint64FromInt(value),
          cursorWriteWrapper: (cursor, value) =>
              cursor.writeTaggedUint64(value),
        );
      }
    });

    test('web rejects JS-unsafe int64 int helper values', () {
      if (!identical(1, 1.0)) {
        final buffer = Buffer();
        buffer.writeInt64FromInt(_jsUnsafeInt);
        buffer.writeVarInt64FromInt(_jsUnsafeInt);
        buffer.writeTaggedInt64FromInt(_jsUnsafeInt);
        expect(buffer.readInt64AsInt(), equals(_jsUnsafeInt));
        expect(buffer.readVarInt64AsInt(), equals(_jsUnsafeInt));
        expect(buffer.readTaggedInt64AsInt(), equals(_jsUnsafeInt));
        return;
      }

      expect(
        () => Buffer().writeInt64FromInt(_jsUnsafeInt),
        throwsA(isA<StateError>()),
      );
      expect(
        () => Buffer().writeVarInt64FromInt(_jsUnsafeInt),
        throwsA(isA<StateError>()),
      );
      expect(
        () => Buffer().writeTaggedInt64FromInt(_jsUnsafeInt),
        throwsA(isA<StateError>()),
      );

      final fixed = Buffer()..writeInt64(Int64(_jsUnsafeInt));
      final varint = Buffer()..writeVarInt64(Int64(_jsUnsafeInt));
      final tagged = Buffer()..writeTaggedInt64(Int64(_jsUnsafeInt));
      expect(
        () => Buffer.wrap(Uint8List.fromList(fixed.toBytes())).readInt64AsInt(),
        throwsA(isA<StateError>()),
      );
      expect(
        () => Buffer.wrap(Uint8List.fromList(varint.toBytes()))
            .readVarInt64AsInt(),
        throwsA(isA<StateError>()),
      );
      expect(
        () => Buffer.wrap(Uint8List.fromList(tagged.toBytes()))
            .readTaggedInt64AsInt(),
        throwsA(isA<StateError>()),
      );
    });

    test('web rejects unsafe uint64 cursor int helper values', () {
      if (!identical(1, 1.0)) {
        for (final value in <int>[-1, _jsUnsafeInt]) {
          _expectUint64CursorIntHelperMatchesWrapper(
            value: value,
            cursorWriteInt: (cursor, value) => cursor.writeUint64FromInt(value),
            cursorWriteWrapper: (cursor, value) => cursor.writeUint64(value),
          );
          _expectUint64CursorIntHelperMatchesWrapper(
            value: value,
            cursorWriteInt: (cursor, value) =>
                cursor.writeVarUint64FromInt(value),
            cursorWriteWrapper: (cursor, value) => cursor.writeVarUint64(value),
          );
          _expectUint64CursorIntHelperMatchesWrapper(
            value: value,
            cursorWriteInt: (cursor, value) =>
                cursor.writeTaggedUint64FromInt(value),
            cursorWriteWrapper: (cursor, value) =>
                cursor.writeTaggedUint64(value),
          );
        }
        return;
      }

      for (final value in <int>[-1, _jsUnsafeInt]) {
        expect(
          () => _writeWithCursor((cursor) => cursor.writeUint64FromInt(value)),
          throwsA(isA<StateError>()),
          reason: 'fixed $value',
        );
        expect(
          () =>
              _writeWithCursor((cursor) => cursor.writeVarUint64FromInt(value)),
          throwsA(isA<StateError>()),
          reason: 'varint $value',
        );
        expect(
          () => _writeWithCursor(
            (cursor) => cursor.writeTaggedUint64FromInt(value),
          ),
          throwsA(isA<StateError>()),
          reason: 'tagged $value',
        );
      }
    });

    test('round-trips small varuint helpers', () {
      const small7Cases = <({int bytes, int value})>[
        (bytes: 1, value: 0),
        (bytes: 1, value: 1),
        (bytes: 1, value: 127),
        (bytes: 3, value: 0x7fff),
        (bytes: 5, value: 0xffffffff),
      ];
      const small14Cases = <({int bytes, int value})>[
        (bytes: 1, value: 0),
        (bytes: 2, value: 1 << 7),
        (bytes: 3, value: 1 << 14),
        (bytes: 5, value: 0xffffffff),
      ];
      const small36Cases = <({int bytes, int value})>[
        (bytes: 1, value: 0),
        (bytes: 1, value: 10),
        (bytes: 3, value: 0x7fff),
        (bytes: 5, value: 0x7fffffff),
        (bytes: 6, value: 0xfffffffff),
      ];

      for (final testCase in small7Cases) {
        _expectEncodedIntRoundTrip(
          value: testCase.value,
          expectedBytes: testCase.bytes,
          write: (buffer, value) => buffer.writeVarUint32Small7(value),
          read: (buffer) => buffer.readVarUint32Small7(),
        );
      }
      for (final testCase in small14Cases) {
        _expectEncodedIntRoundTrip(
          value: testCase.value,
          expectedBytes: testCase.bytes,
          write: (buffer, value) => buffer.writeVarUint32Small14(value),
          read: (buffer) => buffer.readVarUint32Small14(),
        );
      }
      for (final testCase in small36Cases) {
        _expectEncodedIntRoundTrip(
          value: testCase.value,
          expectedBytes: testCase.bytes,
          write: (buffer, value) => buffer.writeVarUint36Small(value),
          read: (buffer) => buffer.readVarUint36Small(),
        );
      }
    });

    test('generated cursors match Buffer encodings', () {
      final buffer = Buffer();
      final generated = Buffer();

      buffer.writeBool(true);
      buffer.writeByte(-7);
      buffer.writeUint8(250);
      buffer.writeInt16(-1234);
      buffer.writeUint16(65000);
      buffer.writeInt32(-123456789);
      buffer.writeUint32(0x89abcdef);
      buffer.writeInt64(_i64Hex('-1234567890abcdef'));
      buffer.writeUint64(_u64Hex('fedcba9876543210'));
      buffer.writeFloat16(Float16(1.5));
      buffer.writeBfloat16(Bfloat16(2.5));
      buffer.writeFloat32(3.25);
      buffer.writeFloat64(-9.5);
      buffer.writeVarUint32(0xffffffff);
      buffer.writeVarInt32(-0x40000000);
      buffer.writeVarUint64(_u64Hex('ffffffffffffffff'));
      buffer.writeVarInt64(_i64Hex('c000000000000000'));
      buffer.writeTaggedInt64(Int64(0x40000000));
      buffer.writeTaggedUint64(Uint64(0x80000000));

      final cursor = GeneratedWriteCursor.reserve(generated, 128);
      cursor.writeBool(true);
      cursor.writeByte(-7);
      cursor.writeUint8(250);
      cursor.writeInt16(-1234);
      cursor.writeUint16(65000);
      cursor.writeInt32(-123456789);
      cursor.writeUint32(0x89abcdef);
      cursor.writeInt64(_i64Hex('-1234567890abcdef'));
      cursor.writeUint64(_u64Hex('fedcba9876543210'));
      cursor.writeFloat16(Float16(1.5));
      cursor.writeBfloat16(Bfloat16(2.5));
      cursor.writeFloat32(3.25);
      cursor.writeFloat64(-9.5);
      cursor.writeVarUint32(0xffffffff);
      cursor.writeVarInt32(-0x40000000);
      cursor.writeVarUint64(_u64Hex('ffffffffffffffff'));
      cursor.writeVarInt64(_i64Hex('c000000000000000'));
      cursor.writeTaggedInt64(Int64(0x40000000));
      cursor.writeTaggedUint64(Uint64(0x80000000));
      cursor.finish();

      expect(generated.toBytes(), orderedEquals(buffer.toBytes()));

      final readBuffer = Buffer.wrap(Uint8List.fromList(generated.toBytes()));
      final readCursor = GeneratedReadCursor.start(readBuffer);

      expect(readCursor.readBool(), isTrue);
      expect(readCursor.readByte(), equals(-7));
      expect(readCursor.readUint8(), equals(250));
      expect(readCursor.readInt16(), equals(-1234));
      expect(readCursor.readUint16(), equals(65000));
      expect(readCursor.readInt32(), equals(-123456789));
      expect(readCursor.readUint32(), equals(0x89abcdef));
      expect(readCursor.readInt64(), equals(_i64Hex('-1234567890abcdef')));
      expect(readCursor.readUint64(), equals(_u64Hex('fedcba9876543210')));
      expect(readCursor.readFloat16(), equals(Float16(1.5)));
      expect(readCursor.readBfloat16(), equals(Bfloat16(2.5)));
      expect(readCursor.readFloat32(), closeTo(3.25, 0.0001));
      expect(readCursor.readFloat64(), equals(-9.5));
      expect(readCursor.readVarUint32(), equals(0xffffffff));
      expect(readCursor.readVarInt32(), equals(-0x40000000));
      expect(readCursor.readVarUint64(), equals(_u64Hex('ffffffffffffffff')));
      expect(readCursor.readVarInt64(), equals(_i64Hex('c000000000000000')));
      expect(readCursor.readTaggedInt64(), equals(Int64(0x40000000)));
      expect(readCursor.readTaggedUint64(), equals(Uint64(0x80000000)));

      readCursor.finish();
      expect(readBuffer.readableBytes, equals(0));
    });
  });
}

void _expectInt64IntHelperMatchesWrapper({
  required int value,
  required void Function(Buffer buffer, int value) writeInt,
  required void Function(Buffer buffer, int value) writeWrapper,
  required int Function(Buffer buffer) readInt,
  required void Function(GeneratedWriteCursor cursor, int value) cursorWriteInt,
  required void Function(GeneratedWriteCursor cursor, int value)
      cursorWriteWrapper,
  required int Function(GeneratedReadCursor cursor) cursorReadInt,
}) {
  final intBuffer = Buffer();
  writeInt(intBuffer, value);

  final wrapperBuffer = Buffer();
  writeWrapper(wrapperBuffer, value);
  expect(intBuffer.toBytes(), orderedEquals(wrapperBuffer.toBytes()));

  final readBuffer = Buffer.wrap(Uint8List.fromList(intBuffer.toBytes()));
  expect(readInt(readBuffer), equals(value));
  expect(readBuffer.readableBytes, equals(0));

  final cursorIntBuffer = Buffer();
  final cursorInt = GeneratedWriteCursor.reserve(cursorIntBuffer, 10);
  cursorWriteInt(cursorInt, value);
  cursorInt.finish();
  expect(cursorIntBuffer.toBytes(), orderedEquals(wrapperBuffer.toBytes()));

  final cursorWrapperBuffer = Buffer();
  final cursorWrapper = GeneratedWriteCursor.reserve(cursorWrapperBuffer, 10);
  cursorWriteWrapper(cursorWrapper, value);
  cursorWrapper.finish();
  expect(cursorWrapperBuffer.toBytes(), orderedEquals(wrapperBuffer.toBytes()));

  final cursorReadBuffer = Buffer.wrap(Uint8List.fromList(intBuffer.toBytes()));
  final cursorRead = GeneratedReadCursor.start(cursorReadBuffer);
  expect(cursorReadInt(cursorRead), equals(value));
  cursorRead.finish();
  expect(cursorReadBuffer.readableBytes, equals(0));
}

void _expectUint64CursorIntHelperMatchesWrapper({
  required int value,
  required void Function(GeneratedWriteCursor cursor, int value) cursorWriteInt,
  required void Function(GeneratedWriteCursor cursor, Uint64 value)
      cursorWriteWrapper,
}) {
  final intBuffer = Buffer();
  final intCursor = GeneratedWriteCursor.reserve(intBuffer, 10);
  cursorWriteInt(intCursor, value);
  intCursor.finish();

  final wrapperBuffer = Buffer();
  final wrapperCursor = GeneratedWriteCursor.reserve(wrapperBuffer, 10);
  cursorWriteWrapper(wrapperCursor, Uint64(value));
  wrapperCursor.finish();

  expect(intBuffer.toBytes(), orderedEquals(wrapperBuffer.toBytes()));
}

void _writeWithCursor(void Function(GeneratedWriteCursor cursor) write) {
  final buffer = Buffer();
  final cursor = GeneratedWriteCursor.reserve(buffer, 10);
  write(cursor);
  cursor.finish();
}

void _expectEncodedIntRoundTrip({
  required int value,
  required int expectedBytes,
  required void Function(Buffer buffer, int value) write,
  required int Function(Buffer buffer) read,
  int Function(GeneratedReadCursor cursor)? cursorRead,
}) {
  final buffer = Buffer();
  write(buffer, value);
  final bytes = Uint8List.fromList(buffer.toBytes());

  expect(bytes.length, equals(expectedBytes));

  final wrapped = Buffer.wrap(Uint8List.fromList(bytes));
  expect(read(wrapped), equals(value));
  expect(wrapped.readableBytes, equals(0));

  if (cursorRead != null) {
    final cursorBuffer = Buffer.wrap(Uint8List.fromList(bytes));
    final cursor = GeneratedReadCursor.start(cursorBuffer);
    expect(cursorRead(cursor), equals(value));
    cursor.finish();
    expect(cursorBuffer.readableBytes, equals(0));
  }
}

void _expectEncodedUint64RoundTrip({
  required Uint64 value,
  required int expectedBytes,
  required void Function(Buffer buffer, Uint64 value) write,
  required Uint64 Function(Buffer buffer) read,
  Uint64 Function(GeneratedReadCursor cursor)? cursorRead,
}) {
  final buffer = Buffer();
  write(buffer, value);
  final bytes = Uint8List.fromList(buffer.toBytes());

  expect(bytes.length, equals(expectedBytes));

  final wrapped = Buffer.wrap(Uint8List.fromList(bytes));
  expect(read(wrapped), equals(value));
  expect(wrapped.readableBytes, equals(0));

  if (cursorRead != null) {
    final cursorBuffer = Buffer.wrap(Uint8List.fromList(bytes));
    final cursor = GeneratedReadCursor.start(cursorBuffer);
    expect(cursorRead(cursor), equals(value));
    cursor.finish();
    expect(cursorBuffer.readableBytes, equals(0));
  }
}

void _expectEncodedInt64RoundTrip({
  required Int64 value,
  required int expectedBytes,
  required void Function(Buffer buffer, Int64 value) write,
  required Int64 Function(Buffer buffer) read,
  Int64 Function(GeneratedReadCursor cursor)? cursorRead,
}) {
  final buffer = Buffer();
  write(buffer, value);
  final bytes = Uint8List.fromList(buffer.toBytes());

  expect(bytes.length, equals(expectedBytes));

  final wrapped = Buffer.wrap(Uint8List.fromList(bytes));
  expect(read(wrapped), equals(value));
  expect(wrapped.readableBytes, equals(0));

  if (cursorRead != null) {
    final cursorBuffer = Buffer.wrap(Uint8List.fromList(bytes));
    final cursor = GeneratedReadCursor.start(cursorBuffer);
    expect(cursorRead(cursor), equals(value));
    cursor.finish();
    expect(cursorBuffer.readableBytes, equals(0));
  }
}

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
      const half = -2.5;
      const brain = -3.5;

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

    test('root reads stop at writerIndex and restore spare storage', () {
      final fory = Fory();
      final outsideStorage = anyOf(isA<RangeError>(), isA<ArgumentError>());
      final cases = <({Object value, int typeId})>[
        (value: 0x10203040, typeId: TypeIds.int32),
        (value: 0x4000, typeId: TypeIds.varUint32),
        (value: Int64(0x10203040), typeId: TypeIds.int64),
      ];

      for (final testCase in cases) {
        final encoded = fory.serializeBuiltin(
          testCase.value,
          typeId: testCase.typeId,
        );
        final buffer = Buffer(encoded.length + 32)
          ..writeBytes(encoded.sublist(0, encoded.length - 1));
        final fullStorage = bufferBytes(buffer);

        expect(
          () => fory.deserializeFrom<Object>(buffer),
          throwsA(outsideStorage),
          reason: 'typeId=${testCase.typeId}',
        );
        expect(bufferBytes(buffer), same(fullStorage));

        fory.serializeBuiltinTo(
          testCase.value,
          buffer,
          typeId: testCase.typeId,
        );
        expect(
          fory.deserializeFrom<Object>(buffer),
          equals(testCase.value),
          reason: 'typeId=${testCase.typeId}',
        );
      }
    });

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
        );
      }
    });

    test('rejects varuint32 encodings wider than 32 bits', () {
      const malformed = <List<int>>[
        <int>[0x80, 0x80, 0x80, 0x80, 0x80],
        <int>[0xff, 0xff, 0xff, 0xff, 0x10],
      ];

      for (final bytes in malformed) {
        final buffer = Buffer.wrap(Uint8List.fromList(<int>[...bytes, 0x2a]));
        expect(() => buffer.readVarUint32(), throwsA(isA<StateError>()));
        expect(buffer.readableBytes, equals(1));
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
        );
      }
    });

    test(
      'round-trips tagged int64 boundary values with Java-aligned lengths',
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
          );
        }
      },
    );

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
          );
        }
      },
    );

    test(
      'int64 int helpers match Int64 wrapper encodings at safe boundaries',
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
          );
          _expectInt64IntHelperMatchesWrapper(
            value: value,
            writeInt: (buffer, value) => buffer.writeVarInt64FromInt(value),
            writeWrapper: (buffer, value) => buffer.writeVarInt64(Int64(value)),
            readInt: (buffer) => buffer.readVarInt64AsInt(),
          );
          _expectInt64IntHelperMatchesWrapper(
            value: value,
            writeInt: (buffer, value) => buffer.writeTaggedInt64FromInt(value),
            writeWrapper:
                (buffer, value) => buffer.writeTaggedInt64(Int64(value)),
            readInt: (buffer) => buffer.readTaggedInt64AsInt(),
          );
        }
      },
    );

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
        () =>
            Buffer.wrap(
              Uint8List.fromList(varint.toBytes()),
            ).readVarInt64AsInt(),
        throwsA(isA<StateError>()),
      );
      expect(
        () =>
            Buffer.wrap(
              Uint8List.fromList(tagged.toBytes()),
            ).readTaggedInt64AsInt(),
        throwsA(isA<StateError>()),
      );
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
  });
}

void _expectInt64IntHelperMatchesWrapper({
  required int value,
  required void Function(Buffer buffer, int value) writeInt,
  required void Function(Buffer buffer, int value) writeWrapper,
  required int Function(Buffer buffer) readInt,
}) {
  final intBuffer = Buffer();
  writeInt(intBuffer, value);

  final wrapperBuffer = Buffer();
  writeWrapper(wrapperBuffer, value);
  expect(intBuffer.toBytes(), orderedEquals(wrapperBuffer.toBytes()));

  final readBuffer = Buffer.wrap(Uint8List.fromList(intBuffer.toBytes()));
  expect(readInt(readBuffer), equals(value));
  expect(readBuffer.readableBytes, equals(0));
}

void _expectEncodedIntRoundTrip({
  required int value,
  required int expectedBytes,
  required void Function(Buffer buffer, int value) write,
  required int Function(Buffer buffer) read,
}) {
  final buffer = Buffer();
  write(buffer, value);
  final bytes = Uint8List.fromList(buffer.toBytes());

  expect(bytes.length, equals(expectedBytes));

  final wrapped = Buffer.wrap(Uint8List.fromList(bytes));
  expect(read(wrapped), equals(value));
  expect(wrapped.readableBytes, equals(0));
}

void _expectEncodedUint64RoundTrip({
  required Uint64 value,
  required int expectedBytes,
  required void Function(Buffer buffer, Uint64 value) write,
  required Uint64 Function(Buffer buffer) read,
}) {
  final buffer = Buffer();
  write(buffer, value);
  final bytes = Uint8List.fromList(buffer.toBytes());

  expect(bytes.length, equals(expectedBytes));

  final wrapped = Buffer.wrap(Uint8List.fromList(bytes));
  expect(read(wrapped), equals(value));
  expect(wrapped.readableBytes, equals(0));
}

void _expectEncodedInt64RoundTrip({
  required Int64 value,
  required int expectedBytes,
  required void Function(Buffer buffer, Int64 value) write,
  required Int64 Function(Buffer buffer) read,
}) {
  final buffer = Buffer();
  write(buffer, value);
  final bytes = Uint8List.fromList(buffer.toBytes());

  expect(bytes.length, equals(expectedBytes));

  final wrapped = Buffer.wrap(Uint8List.fromList(bytes));
  expect(read(wrapped), equals(value));
  expect(wrapped.readableBytes, equals(0));
}

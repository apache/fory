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

// ignore_for_file: unnecessary_library_name

library fory.src.codegen.generated_cursor;

import 'dart:typed_data';

import 'package:meta/meta.dart';

import 'package:fory/src/memory/buffer.dart';
import 'package:fory/src/types/bfloat16.dart';
import 'package:fory/src/types/float16.dart';
import 'package:fory/src/types/int64.dart';
import 'package:fory/src/types/uint64.dart';

part 'generated_cursor_mixin.dart';

const int _jsSafeIntMax = 9007199254740991;
const int _jsSafeIntMin = -9007199254740991;

@internal
final class GeneratedWriteCursor with _GeneratedWriteCursorMixin {
  GeneratedWriteCursor._();

  factory GeneratedWriteCursor.reserve(Buffer buffer, int maxBytes) {
    return GeneratedWriteCursor._().._initWriteCursor(buffer, maxBytes);
  }

  @pragma('vm:prefer-inline')
  void writeInt64(Int64 value) {
    _writeInt64Words(_offset, value);
    _offset += 8;
  }

  @pragma('vm:prefer-inline')
  void writeInt64FromInt(int value) {
    _writeInt64Words(_offset, _int64FromInt(value));
    _offset += 8;
  }

  @pragma('vm:prefer-inline')
  void writeUint64(Uint64 value) {
    _writeUint64Words(_offset, value);
    _offset += 8;
  }

  @pragma('vm:prefer-inline')
  void writeUint64FromInt(int value) {
    _checkUint64IntRange(value);
    _writeUint64Words(_offset, Uint64(value));
    _offset += 8;
  }

  @pragma('vm:prefer-inline')
  @override
  void writeVarUint64(Uint64 value) {
    var remaining = value;
    for (var shift = 0; shift < 56 && remaining > 0x7f; shift += 7) {
      _bytes[_offset] = (remaining.low32 & 0x7f) | 0x80;
      _offset += 1;
      remaining = remaining >> 7;
    }
    _bytes[_offset] = remaining.toInt();
    _offset += 1;
  }

  @pragma('vm:prefer-inline')
  void writeVarUint64FromInt(int value) {
    _checkUint64IntRange(value);
    writeVarUint64(Uint64(value));
  }

  @pragma('vm:prefer-inline')
  void writeVarInt64(Int64 value) {
    writeVarUint64(_zigZagEncodeInt64(value));
  }

  @pragma('vm:prefer-inline')
  void writeVarInt64FromInt(int value) {
    writeVarInt64(_int64FromInt(value));
  }

  @pragma('vm:prefer-inline')
  void writeTaggedInt64(Int64 value) {
    if (value >= -0x40000000 && value <= 0x3fffffff) {
      writeInt32((value.toInt() << 1).toSigned(32));
      return;
    }
    writeUint8(0x01);
    writeInt64(value);
  }

  @pragma('vm:prefer-inline')
  void writeTaggedInt64FromInt(int value) {
    if (value >= -0x40000000 && value <= 0x3fffffff) {
      writeInt32((value << 1).toSigned(32));
      return;
    }
    _checkInt64IntRange(value);
    writeUint8(0x01);
    writeInt64FromInt(value);
  }

  @pragma('vm:prefer-inline')
  void writeTaggedUint64(Uint64 value) {
    if (value >= 0 && value <= 0x7fffffff) {
      writeInt32(value.toInt() << 1);
      return;
    }
    writeUint8(0x01);
    writeUint64(value);
  }

  @pragma('vm:prefer-inline')
  void writeTaggedUint64FromInt(int value) {
    if (value >= 0 && value <= 0x7fffffff) {
      writeInt32(value << 1);
      return;
    }
    _checkUint64IntRange(value);
    writeUint8(0x01);
    writeUint64FromInt(value);
  }

  @pragma('vm:prefer-inline')
  void _writeInt64Words(int offset, Int64 value) {
    _view.setUint32(offset, value.low32, Endian.little);
    _view.setUint32(offset + 4, value.high32Unsigned, Endian.little);
  }

  @pragma('vm:prefer-inline')
  void _writeUint64Words(int offset, Uint64 value) {
    _view.setUint32(offset, value.low32, Endian.little);
    _view.setUint32(offset + 4, value.high32Unsigned, Endian.little);
  }
}

@internal
final class GeneratedReadCursor with _GeneratedReadCursorMixin {
  GeneratedReadCursor._();

  factory GeneratedReadCursor.start(Buffer buffer) {
    return GeneratedReadCursor._().._initReadCursor(buffer);
  }

  @pragma('vm:prefer-inline')
  Int64 readInt64() {
    final value = _readInt64Words(_offset);
    _offset += 8;
    return value;
  }

  @pragma('vm:prefer-inline')
  int readInt64AsInt() {
    final value = _int64ToInt(_readInt64Words(_offset));
    _offset += 8;
    return value;
  }

  @pragma('vm:prefer-inline')
  Uint64 readUint64() {
    final value = _readUint64Words(_offset);
    _offset += 8;
    return value;
  }

  @pragma('vm:prefer-inline')
  int readUint64AsInt() {
    final value = _readUint64Words(_offset).toInt();
    _offset += 8;
    return value;
  }

  @pragma('vm:prefer-inline')
  @override
  Uint64 readVarUint64() {
    var shift = 0;
    var result = Uint64(0);
    while (shift < 56) {
      final byte = readUint8();
      result = result | (Uint64(byte & 0x7f) << shift);
      if ((byte & 0x80) == 0) {
        return result;
      }
      shift += 7;
    }
    return result | (Uint64(readUint8()) << 56);
  }

  @pragma('vm:prefer-inline')
  int readVarUint64AsInt() {
    return readVarUint64().toInt();
  }

  @pragma('vm:prefer-inline')
  Int64 readVarInt64() {
    return _zigZagDecodeInt64(readVarUint64());
  }

  @pragma('vm:prefer-inline')
  int readVarInt64AsInt() {
    return _int64ToInt(readVarInt64());
  }

  @pragma('vm:prefer-inline')
  Int64 readTaggedInt64() {
    final readIndex = _offset;
    final first = _view.getInt32(readIndex, Endian.little);
    if ((first & 1) == 0) {
      _offset = readIndex + 4;
      return Int64(first.toSigned(32) ~/ 2);
    }
    final value = _readInt64Words(readIndex + 1);
    _offset = readIndex + 9;
    return value;
  }

  @pragma('vm:prefer-inline')
  int readTaggedInt64AsInt() {
    final readIndex = _offset;
    final first = _view.getInt32(readIndex, Endian.little);
    if ((first & 1) == 0) {
      _offset = readIndex + 4;
      return first.toSigned(32) ~/ 2;
    }
    final value = _int64ToInt(_readInt64Words(readIndex + 1));
    _offset = readIndex + 9;
    return value;
  }

  @pragma('vm:prefer-inline')
  Uint64 readTaggedUint64() {
    final readIndex = _offset;
    final first = _view.getUint32(readIndex, Endian.little);
    if ((first & 1) == 0) {
      _offset = readIndex + 4;
      return Uint64(first >>> 1);
    }
    final value = _readUint64Words(readIndex + 1);
    _offset = readIndex + 9;
    return value;
  }

  @pragma('vm:prefer-inline')
  int readTaggedUint64AsInt() {
    final readIndex = _offset;
    final first = _view.getUint32(readIndex, Endian.little);
    if ((first & 1) == 0) {
      _offset = readIndex + 4;
      return first >>> 1;
    }
    final value = _readUint64Words(readIndex + 1).toInt();
    _offset = readIndex + 9;
    return value;
  }

  @pragma('vm:prefer-inline')
  Int64 _readInt64Words(int offset) {
    return Int64.fromWords(
      _view.getUint32(offset, Endian.little),
      _view.getInt32(offset + 4, Endian.little),
    );
  }

  @pragma('vm:prefer-inline')
  Uint64 _readUint64Words(int offset) {
    return Uint64.fromWords(
      _view.getUint32(offset, Endian.little),
      _view.getUint32(offset + 4, Endian.little),
    );
  }
}

@pragma('vm:prefer-inline')
void _checkInt64IntRange(int value) {
  if (value < _jsSafeIntMin || value > _jsSafeIntMax) {
    throw StateError(
      'Dart int value $value is outside the JS-safe signed int64 range '
      '[$_jsSafeIntMin, $_jsSafeIntMax]. Use Int64 for full 64-bit values '
      'on web.',
    );
  }
}

@pragma('vm:prefer-inline')
Int64 _int64FromInt(int value) {
  _checkInt64IntRange(value);
  return Int64(value);
}

@pragma('vm:prefer-inline')
void _checkUint64IntRange(int value) {
  if (value < 0 || value > _jsSafeIntMax) {
    throw StateError(
      'Dart int value $value is outside the JS-safe unsigned uint64 int '
      'field range [0, $_jsSafeIntMax]. Use Uint64 for full unsigned '
      '64-bit values on web.',
    );
  }
}

@pragma('vm:prefer-inline')
int _int64ToInt(Int64 value) => value.toInt();

@pragma('vm:prefer-inline')
Uint64 _zigZagEncodeInt64(Int64 value) {
  final encoded = (value << 1) ^ (value >> 63);
  return Uint64.fromWords(encoded.low32, encoded.high32Unsigned);
}

@pragma('vm:prefer-inline')
Int64 _zigZagDecodeInt64(Uint64 encoded) {
  final magnitude = encoded >> 1;
  final decoded = Int64.fromWords(magnitude.low32, magnitude.high32Unsigned);
  if ((encoded.low32 & 1) == 0) {
    return decoded;
  }
  return -(decoded + 1);
}

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

@internal
final class GeneratedWriteCursor with _GeneratedWriteCursorMixin {
  GeneratedWriteCursor._();

  factory GeneratedWriteCursor.reserve(Buffer buffer, int maxBytes) {
    return GeneratedWriteCursor._().._initWriteCursor(buffer, maxBytes);
  }

  @pragma('vm:prefer-inline')
  void writeInt64(Int64 value) {
    _view.setInt64(_offset, value.toInt(), Endian.little);
    _offset += 8;
  }

  @pragma('vm:prefer-inline')
  void writeInt64FromInt(int value) {
    _view.setInt64(_offset, value, Endian.little);
    _offset += 8;
  }

  @pragma('vm:prefer-inline')
  void writeUint64(Uint64 value) {
    _view.setInt64(_offset, value.value, Endian.little);
    _offset += 8;
  }

  @pragma('vm:prefer-inline')
  void writeUint64FromInt(int value) {
    _view.setInt64(_offset, value.toSigned(64), Endian.little);
    _offset += 8;
  }

  @pragma('vm:prefer-inline')
  @override
  void writeVarUint64(Uint64 value) {
    _writeVarUint64Int(value.value);
  }

  @pragma('vm:prefer-inline')
  void writeVarUint64FromInt(int value) {
    _writeVarUint64Int(value.toSigned(64));
  }

  @pragma('vm:prefer-inline')
  void writeVarInt64(Int64 value) {
    _writeVarUint64Int((value << 1) ^ (value >> 63));
  }

  @pragma('vm:prefer-inline')
  void writeVarInt64FromInt(int value) {
    _writeVarUint64Int((value << 1) ^ (value >> 63));
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
    writeUint8(0x01);
    writeUint64FromInt(value);
  }

  @pragma('vm:prefer-inline')
  void _writeVarUint64Int(int value) {
    var remaining = value;
    for (var index = 0; index < 8; index += 1) {
      final chunk = remaining & 0x7f;
      remaining >>>= 7;
      if (remaining == 0) {
        _bytes[_offset] = chunk;
        _offset += 1;
        return;
      }
      _bytes[_offset] = chunk | 0x80;
      _offset += 1;
    }
    _bytes[_offset] = remaining & 0xff;
    _offset += 1;
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
    final value = Int64(_view.getInt64(_offset, Endian.little));
    _offset += 8;
    return value;
  }

  @pragma('vm:prefer-inline')
  int readInt64AsInt() {
    final value = _view.getInt64(_offset, Endian.little);
    _offset += 8;
    return value;
  }

  @pragma('vm:prefer-inline')
  Uint64 readUint64() {
    final value = Uint64(_view.getInt64(_offset, Endian.little));
    _offset += 8;
    return value;
  }

  @pragma('vm:prefer-inline')
  int readUint64AsInt() {
    final value = _view.getUint64(_offset, Endian.little);
    if ((_view.getUint32(_offset + 4, Endian.little) & 0x80000000) != 0) {
      throw StateError(
        'Uint64 value $value is not representable as a native int.',
      );
    }
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
    var shift = 0;
    var result = 0;
    while (shift < 56) {
      final byte = readUint8();
      result |= (byte & 0x7f) << shift;
      if ((byte & 0x80) == 0) {
        return result;
      }
      shift += 7;
    }
    final byte = readUint8();
    final value = result | (byte << 56);
    if ((byte & 0x80) != 0) {
      throw StateError(
        'Uint64 value $value is not representable as a native int.',
      );
    }
    return value;
  }

  @pragma('vm:prefer-inline')
  Int64 readVarInt64() {
    final encoded = readVarUint64();
    return Int64((encoded >>> 1) ^ -(encoded & 1));
  }

  @pragma('vm:prefer-inline')
  int readVarInt64AsInt() {
    var shift = 0;
    var encoded = 0;
    while (shift < 56) {
      final byte = readUint8();
      encoded |= (byte & 0x7f) << shift;
      if ((byte & 0x80) == 0) {
        return (encoded >>> 1) ^ -(encoded & 1);
      }
      shift += 7;
    }
    encoded |= readUint8() << 56;
    return (encoded >>> 1) ^ -(encoded & 1);
  }

  @pragma('vm:prefer-inline')
  Int64 readTaggedInt64() {
    final readIndex = _offset;
    final first = _view.getInt32(readIndex, Endian.little);
    if ((first & 1) == 0) {
      _offset = readIndex + 4;
      return Int64(first.toSigned(32) ~/ 2);
    }
    final value = Int64(_view.getInt64(readIndex + 1, Endian.little));
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
    final value = _view.getInt64(readIndex + 1, Endian.little);
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
    final value = Uint64(_view.getInt64(readIndex + 1, Endian.little));
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
    final value = _view.getUint64(readIndex + 1, Endian.little);
    if ((_view.getUint32(readIndex + 5, Endian.little) & 0x80000000) != 0) {
      throw StateError(
        'Uint64 value $value is not representable as a native int.',
      );
    }
    _offset = readIndex + 9;
    return value;
  }
}

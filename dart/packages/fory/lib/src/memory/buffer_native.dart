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

library fory.src.memory.buffer;

import 'dart:convert';
import 'dart:typed_data';

import 'package:meta/meta.dart';

import 'package:fory/src/types/bfloat16.dart';
import 'package:fory/src/types/float16.dart';
import 'package:fory/src/types/int64.dart';
import 'package:fory/src/types/uint64.dart';

part 'buffer_mixin.dart';

/// A reusable byte buffer with explicit reader and writer indices.
///
/// Fory uses little-endian fixed-width encodings and varint helpers on top of
/// this buffer. The same buffer can be reused across many operations by calling
/// [clear].
final class Buffer with _BufferMixin {
  /// Creates an empty buffer with [initialCapacity] bytes of storage.
  Buffer([int initialCapacity = 256]) {
    _initBuffer(initialCapacity);
  }

  /// Creates a buffer that reads from and writes into [bytes].
  ///
  /// The writer index starts at `bytes.length`, so the wrapped bytes are
  /// immediately readable.
  Buffer.wrap(Uint8List bytes) {
    _wrapBuffer(bytes);
  }

  /// Writes a signed little-endian 64-bit integer.
  void writeInt64(Int64 value) {
    ensureWritable(8);
    _view.setInt64(_writerIndex, value.toInt(), Endian.little);
    _writerIndex += 8;
  }

  /// Writes a signed little-endian 64-bit integer from a Dart [int].
  void writeInt64FromInt(int value) {
    ensureWritable(8);
    _view.setInt64(_writerIndex, value, Endian.little);
    _writerIndex += 8;
  }

  /// Reads a signed little-endian 64-bit integer.
  Int64 readInt64() {
    final value = Int64(_view.getInt64(_readerIndex, Endian.little));
    _readerIndex += 8;
    return value;
  }

  /// Reads a signed little-endian 64-bit integer as a Dart [int].
  int readInt64AsInt() {
    final value = _view.getInt64(_readerIndex, Endian.little);
    _readerIndex += 8;
    return value;
  }

  /// Writes an unsigned little-endian 64-bit integer.
  void writeUint64(Uint64 value) {
    ensureWritable(8);
    _view.setInt64(_writerIndex, value.value, Endian.little);
    _writerIndex += 8;
  }

  /// Reads an unsigned little-endian 64-bit integer.
  Uint64 readUint64() {
    final value = Uint64(_view.getInt64(_readerIndex, Endian.little));
    _readerIndex += 8;
    return value;
  }

  /// Writes an unsigned 64-bit varint.
  @override
  void writeVarUint64(Uint64 value) {
    ensureWritable(10);
    _writeVarUint64Int(value.value);
  }

  /// Reads an unsigned 64-bit varint.
  @override
  Uint64 readVarUint64() {
    var shift = 0;
    var result = Uint64(0);
    while (shift < 56) {
      final byte = _view.getUint8(_readerIndex);
      _readerIndex += 1;
      result = result | (Uint64(byte & 0x7f) << shift);
      if ((byte & 0x80) == 0) {
        return result;
      }
      shift += 7;
    }
    final byte = _view.getUint8(_readerIndex);
    _readerIndex += 1;
    return result | (Uint64(byte) << 56);
  }

  /// Writes a zig-zag encoded signed 64-bit varint.
  void writeVarInt64(Int64 value) {
    ensureWritable(10);
    _writeVarUint64Int((value << 1) ^ (value >> 63));
  }

  /// Writes a zig-zag encoded signed 64-bit varint from a Dart [int].
  void writeVarInt64FromInt(int value) {
    ensureWritable(10);
    _writeVarUint64Int((value << 1) ^ (value >> 63));
  }

  /// Reads a zig-zag encoded signed 64-bit varint.
  Int64 readVarInt64() {
    final encoded = readVarUint64();
    return Int64((encoded >>> 1) ^ -(encoded & 1));
  }

  /// Reads a zig-zag encoded signed 64-bit varint as a Dart [int].
  int readVarInt64AsInt() {
    final encoded = _readVarUint64AsInt();
    return (encoded >>> 1) ^ -(encoded & 1);
  }

  /// Writes a tagged signed 64-bit integer.
  ///
  /// Small values use four bytes. Larger values use a tag byte plus eight data
  /// bytes.
  void writeTaggedInt64(Int64 value) {
    if (value >= -0x40000000 && value <= 0x3fffffff) {
      writeInt32((value.toInt() << 1).toSigned(32));
      return;
    }
    writeUint8(0x01);
    writeInt64(value);
  }

  /// Writes a tagged signed 64-bit integer from a Dart [int].
  void writeTaggedInt64FromInt(int value) {
    if (value >= -0x40000000 && value <= 0x3fffffff) {
      writeInt32((value << 1).toSigned(32));
      return;
    }
    writeUint8(0x01);
    writeInt64FromInt(value);
  }

  /// Reads a signed 64-bit integer written by [writeTaggedInt64].
  Int64 readTaggedInt64() {
    final readIndex = _readerIndex;
    final first = _view.getInt32(readIndex, Endian.little);
    if ((first & 1) == 0) {
      _readerIndex = readIndex + 4;
      return Int64(first.toSigned(32) ~/ 2);
    }
    final value = Int64(_view.getInt64(readIndex + 1, Endian.little));
    _readerIndex = readIndex + 9;
    return value;
  }

  /// Reads a signed 64-bit integer written by [writeTaggedInt64] as an [int].
  int readTaggedInt64AsInt() {
    final readIndex = _readerIndex;
    final first = _view.getInt32(readIndex, Endian.little);
    if ((first & 1) == 0) {
      _readerIndex = readIndex + 4;
      return first.toSigned(32) ~/ 2;
    }
    final value = _view.getInt64(readIndex + 1, Endian.little);
    _readerIndex = readIndex + 9;
    return value;
  }

  /// Writes a tagged unsigned 64-bit integer.
  void writeTaggedUint64(Uint64 value) {
    if (value >= 0 && value <= 0x7fffffff) {
      writeInt32(value.toInt() << 1);
      return;
    }
    writeUint8(0x01);
    writeUint64(value);
  }

  /// Reads an unsigned 64-bit integer written by [writeTaggedUint64].
  Uint64 readTaggedUint64() {
    final readIndex = _readerIndex;
    final first = _view.getUint32(readIndex, Endian.little);
    if ((first & 1) == 0) {
      _readerIndex = readIndex + 4;
      return Uint64(first >>> 1);
    }
    final value = Uint64(_view.getInt64(readIndex + 1, Endian.little));
    _readerIndex = readIndex + 9;
    return value;
  }

  @pragma('vm:prefer-inline')
  void _writeVarUint64Int(int value) {
    var remaining = value;
    for (var index = 0; index < 8; index += 1) {
      final chunk = remaining & 0x7f;
      remaining >>>= 7;
      if (remaining == 0) {
        _bytes[_writerIndex] = chunk;
        _writerIndex += 1;
        return;
      }
      _bytes[_writerIndex] = chunk | 0x80;
      _writerIndex += 1;
    }
    _bytes[_writerIndex] = remaining & 0xff;
    _writerIndex += 1;
  }

  @pragma('vm:prefer-inline')
  int _readVarUint64AsInt() {
    var shift = 0;
    var result = 0;
    while (shift < 56) {
      final byte = _view.getUint8(_readerIndex);
      _readerIndex += 1;
      result |= (byte & 0x7f) << shift;
      if ((byte & 0x80) == 0) {
        return result;
      }
      shift += 7;
    }
    final byte = _view.getUint8(_readerIndex);
    _readerIndex += 1;
    return result | (byte << 56);
  }
}

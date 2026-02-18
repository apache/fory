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
import 'package:fory/src/dev_annotation/optimize.dart';
import 'package:fory/src/datatype/float16.dart';
import 'package:fory/src/memory/byte_writer.dart';

final class ByteWriterImpl extends ByteWriter {
  final BytesBuilder _buffer = BytesBuilder();
  final ByteData _tempByteData = ByteData(8); // Used to store converted data

  ByteWriterImpl() : super.internal();

  /// Get the current buffer size
  int get length => _buffer.length;

  /// Get the final `Uint8List`
  @override
  Uint8List toBytes() {
    return _buffer.toBytes();
  }

  @override
  Uint8List takeBytes() {
    return _buffer.takeBytes();
  }

  @inline
  @override
  void writeBool(bool v) {
    _buffer.addByte(v ? 1 : 0);
  }

  @inline

  /// Append a `Uint8` to the buffer
  @override
  void writeUint8(int value) {
    _buffer.addByte(value & 0xFF);
  }

  @inline

  /// Append a `Uint16` (2 bytes, Little Endian) to the buffer
  @override
  void writeUint16(int value) {
    _tempByteData.setUint16(0, value, endian);
    _buffer.add(_tempByteData.buffer.asUint8List(0, 2));
  }

  @inline

  /// Append a `Uint32` (4 bytes, Little Endian) to the buffer
  @override
  void writeUint32(int value) {
    _tempByteData.setUint32(0, value, endian);
    _buffer.add(_tempByteData.buffer.asUint8List(0, 4));
  }

  @inline

  /// Append a `Uint64` (8 bytes, Little Endian) to the buffer
  @override
  void writeUint64(int value) {
    _tempByteData.setUint64(0, value, endian);
    _buffer.add(_tempByteData.buffer.asUint8List(0, 8));
  }

  @inline

  /// Append an `Int8` to the buffer
  @override
  void writeInt8(int value) {
    _tempByteData.setInt8(0, value);
    _buffer.add(_tempByteData.buffer.asUint8List(0, 1));
  }

  @inline

  /// Append an `Int16` to the buffer
  @override
  void writeInt16(int value) {
    _tempByteData.setInt16(0, value, endian);
    _buffer.add(_tempByteData.buffer.asUint8List(0, 2));
  }

  @inline

  /// Append an `Int32` (4 bytes, Little Endian) to the buffer
  @override
  void writeInt32(int value, [int len = 4]) {
    _tempByteData.setInt32(0, value, endian);
    _buffer.add(_tempByteData.buffer.asUint8List(0, len));
  }

  @inline

  /// Append an `Int64` (8 bytes, Little Endian) to the buffer
  @override
  void writeInt64(int value, [int len = 8]) {
    _tempByteData.setInt64(0, value, endian);
    _buffer.add(_tempByteData.buffer.asUint8List(0, len));
  }

  /// Append a `Float32` (4 bytes, Little Endian) to the buffer
  @inline
  @override
  void writeFloat32(double value) {
    _tempByteData.setFloat32(0, value, endian);
    _buffer.add(_tempByteData.buffer.asUint8List(0, 4));
  }

  /// Append a `Float64` (8 bytes, Little Endian) to the buffer
  @inline
  @override
  void writeFloat64(double value) {
    _tempByteData.setFloat64(0, value, endian);
    _buffer.add(_tempByteData.buffer.asUint8List(0, 8));
  }

  /// Append a `Float16` (2 bytes, Little Endian) to the buffer
  @inline
  @override
  void writeFloat16(Float16 value) {
    writeUint16(value.toBits());
  }

  /// Append a list of bytes to the buffer
  @override
  @inline
  void writeBytes(List<int> bytes) {
    _buffer.add(bytes);
  }

  /// Write a 32-bit var int (sign bit shifted right)
  @override
  @inline
  void writeVarInt32(int v) {
    // Move the sign bit to the far right, shifting `v` to the left by 1 bit to make space for this position.
    writeVarUint36Small((v << 1) ^ (v >> 31));
  }

  /// Write a 64-bit var int (sign bit shifted right)
  @inline
  @override
  void writeVarInt64(int v) {
    writeVarUint64((v << 1) ^ (v >> 63));
  }

  /// Write a 32-bit unsigned var int
  @inline
  @override
  void writeVarUint32(int v) {
    writeVarUint36Small(
        v); // Similar to ForyJava, directly call this method first as well.
  }

  /// Continue writing a 36-bit var int
  @override
  void writeVarUint36Small(int v) {
    int encoded = (v & 0x7f);
    if (v >>> 7 == 0) {
      _buffer.addByte(encoded);
      return;
    }
    encoded |= (((v & 0x3f80) << 1) | 0x80);
    if (v >>> 14 == 0) {
      writeInt32(encoded, 2);
      return;
    }
    return _continuePutVarInt36(encoded, v);
  }

  void _continuePutVarInt36(int encoded, int v) {
    encoded |= (((v & 0x1fc000) << 2) | 0x8000);
    if (v >>> 21 == 0) {
      writeInt32(encoded, 3);
      return;
    }
    encoded |= (((v & 0xfe00000) << 3) | 0x800000);
    if (v >>> 28 == 0) {
      writeInt32(encoded, 4);
      return;
    }
    encoded |= (((v & 0xff0000000) << 4) | 0x80000000);
    writeInt64(encoded, 5);
  }

  /// Continue writing 32-bit var int with small chunk
  void _continueWriteVarUint32Small7(int v) {
    int encoded = (v & 0x7f);
    encoded |= (((v & 0x3f80) << 1) | 0x80);
    if (v >>> 14 == 0) {
      writeInt32(encoded, 2);
      return;
    }
    _continuePutVarInt36(encoded, v);
  }

  /// Write a 32-bit var int with a small 7-bit chunk
  @override
  void writeVarUint32Small7(int v) {
    if (v >>> 7 == 0) {
      _buffer.addByte(v);
      return;
    }
    _continueWriteVarUint32Small7(v);
  }

  /// Write a 64-bit unsigned var int
  @override
  void writeVarUint64(int v) {
    int varInt = (v & 0x7f);
    if (v >>> 7 == 0) {
      _buffer.addByte(varInt);
      return;
    }
    varInt |= (((v & 0x3f80) << 1) | 0x80);
    if (v >>> 14 == 0) {
      writeInt32(varInt, 2);
      return;
    }
    varInt |= (((v & 0x1fc000) << 2) | 0x8000);
    if (v >>> 21 == 0) {
      writeInt32(varInt, 3);
      return;
    }
    varInt |= (((v & 0xfe00000) << 3) | 0x800000);
    if (v >>> 28 == 0) {
      writeInt32(varInt, 4);
      return;
    }
    varInt |= (((v & 0x7f0000000) << 4) | 0x80000000);
    if (v >>> 35 == 0) {
      writeInt64(varInt, 5);
      return;
    }
    varInt |= (((v & 0x3f800000000) << 5) | 0x8000000000);
    if (v >>> 42 == 0) {
      writeInt64(varInt, 6);
      return;
    }
    varInt |= (((v & 0x1fc0000000000) << 6) | 0x800000000000);
    if (v >>> 49 == 0) {
      writeInt64(varInt, 7);
      return;
    }
    varInt |= (((v & 0xfe000000000000) << 7) | 0x80000000000000);
    v >>>= 56;
    if (v == 0) {
      writeInt64(varInt, 8);
      return;
    }
    writeInt64(varInt | 0x8000000000000000);
    writeInt8(v & 0xff);
  }
}

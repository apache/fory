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

// ignore_for_file: use_string_in_part_of_directives

part of fory.src.codegen.generated_cursor;

mixin _GeneratedWriteCursorMixin {
  late final Buffer _buffer;
  late final Uint8List _bytes;
  late final ByteData _view;
  int _offset = 0;

  void _initWriteCursor(Buffer buffer, int maxBytes) {
    final start = bufferReserveBytes(buffer, maxBytes);
    _buffer = buffer;
    _bytes = bufferBytes(buffer);
    _view = bufferByteData(buffer);
    _offset = start;
  }

  void finish() {
    bufferSetWriterIndex(_buffer, _offset);
  }

  @pragma('vm:prefer-inline')
  void writeBool(bool value) {
    _bytes[_offset] = value ? 1 : 0;
    _offset += 1;
  }

  @pragma('vm:prefer-inline')
  void writeByte(int value) {
    _view.setInt8(_offset, value);
    _offset += 1;
  }

  @pragma('vm:prefer-inline')
  void writeUint8(int value) {
    _view.setUint8(_offset, value);
    _offset += 1;
  }

  @pragma('vm:prefer-inline')
  void writeInt16(int value) {
    _view.setInt16(_offset, value, Endian.little);
    _offset += 2;
  }

  @pragma('vm:prefer-inline')
  void writeUint16(int value) {
    _view.setUint16(_offset, value, Endian.little);
    _offset += 2;
  }

  @pragma('vm:prefer-inline')
  void writeInt32(int value) {
    _view.setInt32(_offset, value, Endian.little);
    _offset += 4;
  }

  @pragma('vm:prefer-inline')
  void writeUint32(int value) {
    _view.setUint32(_offset, value, Endian.little);
    _offset += 4;
  }

  @pragma('vm:prefer-inline')
  void writeFloat16(Float16 value) {
    writeUint16(value.toBits());
  }

  @pragma('vm:prefer-inline')
  void writeBfloat16(Bfloat16 value) {
    writeUint16(value.toBits());
  }

  @pragma('vm:prefer-inline')
  void writeFloat32(double value) {
    _view.setFloat32(_offset, value, Endian.little);
    _offset += 4;
  }

  @pragma('vm:prefer-inline')
  void writeFloat64(double value) {
    _view.setFloat64(_offset, value, Endian.little);
    _offset += 8;
  }

  @pragma('vm:prefer-inline')
  void writeVarUint32(int value) {
    var remaining = value;
    while (remaining >= 0x80) {
      _bytes[_offset] = (remaining & 0x7f) | 0x80;
      _offset += 1;
      remaining >>>= 7;
    }
    _bytes[_offset] = remaining;
    _offset += 1;
  }

  @pragma('vm:prefer-inline')
  void writeVarInt32(int value) {
    writeVarUint32(((value << 1) ^ (value >> 31)).toUnsigned(32));
  }

  void writeVarUint64(Uint64 value);
}

mixin _GeneratedReadCursorMixin {
  late final Buffer _buffer;
  late final ByteData _view;
  int _offset = 0;

  void _initReadCursor(Buffer buffer) {
    _buffer = buffer;
    _view = bufferByteData(buffer);
    _offset = bufferReaderIndex(buffer);
  }

  void finish() {
    bufferSetReaderIndex(_buffer, _offset);
  }

  @pragma('vm:prefer-inline')
  bool readBool() => readUint8() != 0;

  @pragma('vm:prefer-inline')
  int readByte() {
    final value = _view.getInt8(_offset);
    _offset += 1;
    return value;
  }

  @pragma('vm:prefer-inline')
  int readUint8() {
    final value = _view.getUint8(_offset);
    _offset += 1;
    return value;
  }

  @pragma('vm:prefer-inline')
  int readInt16() {
    final value = _view.getInt16(_offset, Endian.little);
    _offset += 2;
    return value;
  }

  @pragma('vm:prefer-inline')
  int readUint16() {
    final value = _view.getUint16(_offset, Endian.little);
    _offset += 2;
    return value;
  }

  @pragma('vm:prefer-inline')
  int readInt32() {
    final value = _view.getInt32(_offset, Endian.little);
    _offset += 4;
    return value;
  }

  @pragma('vm:prefer-inline')
  int readUint32() {
    final value = _view.getUint32(_offset, Endian.little);
    _offset += 4;
    return value;
  }

  @pragma('vm:prefer-inline')
  Float16 readFloat16() => Float16.fromBits(readUint16());

  @pragma('vm:prefer-inline')
  Bfloat16 readBfloat16() => Bfloat16.fromBits(readUint16());

  @pragma('vm:prefer-inline')
  double readFloat32() {
    final value = _view.getFloat32(_offset, Endian.little);
    _offset += 4;
    return value;
  }

  @pragma('vm:prefer-inline')
  double readFloat64() {
    final value = _view.getFloat64(_offset, Endian.little);
    _offset += 8;
    return value;
  }

  @pragma('vm:prefer-inline')
  int readVarUint32() {
    var shift = 0;
    var result = 0;
    while (true) {
      final byte = readUint8();
      result |= (byte & 0x7f) << shift;
      if ((byte & 0x80) == 0) {
        return result;
      }
      shift += 7;
    }
  }

  @pragma('vm:prefer-inline')
  int readVarInt32() {
    final value = readVarUint32();
    return ((value >>> 1) ^ -(value & 1)).toSigned(32);
  }

  Uint64 readVarUint64();
}

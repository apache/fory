import 'dart:convert';
import 'dart:typed_data';

import 'package:meta/meta.dart';

import 'package:fory/src/types/float16.dart';

final BigInt _mask64Big = (BigInt.one << 64) - BigInt.one;
final BigInt _sevenBitMaskBig = BigInt.from(0x7f);
final BigInt _byteMaskBig = BigInt.from(0xff);
const bool _useBigIntVarint64 =
    bool.fromEnvironment('dart.library.js_interop') ||
        bool.fromEnvironment('dart.library.js_util');

/// A reusable byte buffer with explicit reader and writer indices.
///
/// Fory uses little-endian fixed-width encodings and varint helpers on top of
/// this buffer. The same buffer can be reused across many operations by calling
/// [clear].
final class Buffer {
  Uint8List _bytes;
  late ByteData _view;
  int _readerIndex;
  int _writerIndex;

  /// Creates an empty buffer with [initialCapacity] bytes of storage.
  Buffer([int initialCapacity = 256])
      : _bytes = Uint8List(initialCapacity),
        _readerIndex = 0,
        _writerIndex = 0 {
    _view = ByteData.sublistView(_bytes);
  }

  /// Creates a buffer that reads from and writes into [bytes].
  ///
  /// The writer index starts at `bytes.length`, so the wrapped bytes are
  /// immediately readable.
  Buffer.wrap(Uint8List bytes)
      : _bytes = bytes,
        _view = ByteData.sublistView(bytes),
        _readerIndex = 0,
        _writerIndex = bytes.length;

  /// Number of unread bytes between the reader and writer indices.
  int get readableBytes => _writerIndex - _readerIndex;

  /// Returns the written portion of the underlying storage.
  ///
  /// The returned view shares memory with the buffer.
  Uint8List toBytes() => Uint8List.sublistView(_bytes, 0, _writerIndex);

  /// Resets reader and writer indices to zero without shrinking storage.
  void clear() {
    _readerIndex = 0;
    _writerIndex = 0;
  }

  /// Replaces the underlying storage with [bytes] and resets both indices.
  void wrap(Uint8List bytes) {
    _bytes = bytes;
    _view = ByteData.sublistView(bytes);
    _readerIndex = 0;
    _writerIndex = bytes.length;
  }

  /// Ensures there is room for [additionalBytes] bytes past the writer index.
  void ensureWritable(int additionalBytes) {
    final required = _writerIndex + additionalBytes;
    if (required <= _bytes.length) {
      return;
    }
    var newLength = _bytes.length;
    while (newLength < required) {
      newLength *= 2;
    }
    final newBytes = Uint8List(newLength);
    newBytes.setRange(0, _writerIndex, _bytes);
    _bytes = newBytes;
    _view = ByteData.sublistView(newBytes);
  }

  /// Advances the reader index by [length] bytes.
  void skip(int length) {
    _readerIndex += length;
  }

  /// Writes a boolean as `0` or `1`.
  void writeBool(bool value) => writeUint8(value ? 1 : 0);

  /// Reads a boolean encoded by [writeBool].
  bool readBool() => readUint8() != 0;

  /// Writes a signed 8-bit integer.
  void writeByte(int value) {
    ensureWritable(1);
    _view.setInt8(_writerIndex, value);
    _writerIndex += 1;
  }

  /// Reads a signed 8-bit integer.
  int readByte() {
    final value = _view.getInt8(_readerIndex);
    _readerIndex += 1;
    return value;
  }

  /// Writes an unsigned 8-bit integer.
  void writeUint8(int value) {
    ensureWritable(1);
    _view.setUint8(_writerIndex, value);
    _writerIndex += 1;
  }

  /// Reads an unsigned 8-bit integer.
  int readUint8() {
    final value = _view.getUint8(_readerIndex);
    _readerIndex += 1;
    return value;
  }

  /// Writes a signed little-endian 16-bit integer.
  void writeInt16(int value) {
    ensureWritable(2);
    _view.setInt16(_writerIndex, value, Endian.little);
    _writerIndex += 2;
  }

  /// Reads a signed little-endian 16-bit integer.
  int readInt16() {
    final value = _view.getInt16(_readerIndex, Endian.little);
    _readerIndex += 2;
    return value;
  }

  /// Writes an unsigned little-endian 16-bit integer.
  void writeUint16(int value) {
    ensureWritable(2);
    _view.setUint16(_writerIndex, value, Endian.little);
    _writerIndex += 2;
  }

  /// Reads an unsigned little-endian 16-bit integer.
  int readUint16() {
    final value = _view.getUint16(_readerIndex, Endian.little);
    _readerIndex += 2;
    return value;
  }

  /// Writes a signed little-endian 32-bit integer.
  void writeInt32(int value) {
    ensureWritable(4);
    _view.setInt32(_writerIndex, value, Endian.little);
    _writerIndex += 4;
  }

  /// Reads a signed little-endian 32-bit integer.
  int readInt32() {
    final value = _view.getInt32(_readerIndex, Endian.little);
    _readerIndex += 4;
    return value;
  }

  /// Writes an unsigned little-endian 32-bit integer.
  void writeUint32(int value) {
    ensureWritable(4);
    _view.setUint32(_writerIndex, value, Endian.little);
    _writerIndex += 4;
  }

  /// Reads an unsigned little-endian 32-bit integer.
  int readUint32() {
    final value = _view.getUint32(_readerIndex, Endian.little);
    _readerIndex += 4;
    return value;
  }

  /// Writes a signed little-endian 64-bit integer.
  void writeInt64(int value) {
    ensureWritable(8);
    _view.setInt64(_writerIndex, value, Endian.little);
    _writerIndex += 8;
  }

  /// Reads a signed little-endian 64-bit integer.
  int readInt64() {
    final value = _view.getInt64(_readerIndex, Endian.little);
    _readerIndex += 8;
    return value;
  }

  /// Writes an unsigned little-endian 64-bit integer.
  void writeUint64(int value) {
    ensureWritable(8);
    _view.setUint64(_writerIndex, value, Endian.little);
    _writerIndex += 8;
  }

  /// Reads an unsigned little-endian 64-bit integer.
  int readUint64() {
    final value = _view.getUint64(_readerIndex, Endian.little);
    _readerIndex += 8;
    return value;
  }

  /// Writes a single-precision floating-point value.
  void writeFloat32(double value) {
    ensureWritable(4);
    _view.setFloat32(_writerIndex, value, Endian.little);
    _writerIndex += 4;
  }

  /// Reads a single-precision floating-point value.
  double readFloat32() {
    final value = _view.getFloat32(_readerIndex, Endian.little);
    _readerIndex += 4;
    return value;
  }

  /// Writes a double-precision floating-point value.
  void writeFloat64(double value) {
    ensureWritable(8);
    _view.setFloat64(_writerIndex, value, Endian.little);
    _writerIndex += 8;
  }

  /// Reads a double-precision floating-point value.
  double readFloat64() {
    final value = _view.getFloat64(_readerIndex, Endian.little);
    _readerIndex += 8;
    return value;
  }

  /// Writes a half-precision floating-point value.
  void writeFloat16(Float16 value) => writeUint16(value.toBits());

  /// Reads a half-precision floating-point value.
  Float16 readFloat16() => Float16.fromBits(readUint16());

  /// Writes [value] verbatim.
  void writeBytes(List<int> value) {
    ensureWritable(value.length);
    _bytes.setRange(_writerIndex, _writerIndex + value.length, value);
    _writerIndex += value.length;
  }

  /// Returns a view of the next [length] bytes and advances the reader index.
  Uint8List readBytes(int length) {
    final result = Uint8List.sublistView(
      _bytes,
      _readerIndex,
      _readerIndex + length,
    );
    _readerIndex += length;
    return result;
  }

  /// Copies the next [length] bytes into a new array.
  Uint8List copyBytes(int length) => Uint8List.fromList(readBytes(length));

  /// Writes a UTF-8 string prefixed by its byte length as `varuint32`.
  void writeUtf8(String value) {
    final bytes = utf8.encode(value);
    writeVarUint32(bytes.length);
    writeBytes(bytes);
  }

  /// Reads a UTF-8 string written by [writeUtf8].
  String readUtf8() => utf8.decode(readBytes(readVarUint32()));

  /// Writes an unsigned 32-bit varint.
  void writeVarUint32(int value) {
    var remaining = value;
    while (remaining >= 0x80) {
      writeUint8((remaining & 0x7f) | 0x80);
      remaining >>>= 7;
    }
    writeUint8(remaining);
  }

  /// Reads an unsigned 32-bit varint.
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

  /// Writes a zig-zag encoded signed 32-bit varint.
  void writeVarInt32(int value) => writeVarUint32((value << 1) ^ (value >> 31));

  /// Reads a zig-zag encoded signed 32-bit varint.
  int readVarInt32() {
    final value = readVarUint32();
    return (value >>> 1) ^ -(value & 1);
  }

  /// Writes an unsigned 64-bit varint.
  void writeVarUint64(int value) {
    if (!_useBigIntVarint64) {
      var remaining = value;
      for (var index = 0; index < 8; index += 1) {
        final chunk = remaining & 0x7f;
        remaining = remaining >>> 7;
        if (remaining == 0) {
          writeUint8(chunk);
          return;
        }
        writeUint8(chunk | 0x80);
      }
      writeUint8(remaining & 0xff);
      return;
    }
    _writeVarUint64BigInt(BigInt.from(value) & _mask64Big);
  }

  /// Reads an unsigned 64-bit varint.
  int readVarUint64() {
    if (!_useBigIntVarint64) {
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
      return result | (readUint8() << 56);
    }
    return _readVarUint64BigInt().toInt();
  }

  /// Writes a zig-zag encoded signed 64-bit varint.
  void writeVarInt64(int value) {
    if (!_useBigIntVarint64) {
      writeVarUint64((value << 1) ^ (value >> 63));
      return;
    }
    final signed = BigInt.from(value);
    final zigZag = ((signed << 1) ^ BigInt.from(value >> 63)) & _mask64Big;
    _writeVarUint64BigInt(zigZag);
  }

  /// Reads a zig-zag encoded signed 64-bit varint.
  int readVarInt64() {
    if (!_useBigIntVarint64) {
      final encoded = readVarUint64();
      return (encoded >>> 1) ^ -(encoded & 1);
    }
    final encoded = _readVarUint64BigInt();
    final magnitude = (encoded >> 1).toInt();
    if ((encoded & BigInt.one) == BigInt.zero) {
      return magnitude;
    }
    return -magnitude - 1;
  }

  /// Writes a tagged signed 64-bit integer.
  ///
  /// Small values use four bytes. Larger values use a tag byte plus eight data
  /// bytes.
  void writeTaggedInt64(int value) {
    if (value >= -0x40000000 && value <= 0x3fffffff) {
      writeInt32(value << 1);
      return;
    }
    writeUint8(0x01);
    writeInt64(value);
  }

  /// Reads a signed 64-bit integer written by [writeTaggedInt64].
  int readTaggedInt64() {
    final readIndex = _readerIndex;
    final first = _view.getInt32(readIndex, Endian.little);
    if ((first & 1) == 0) {
      _readerIndex = readIndex + 4;
      return first >> 1;
    }
    final value = _view.getInt64(readIndex + 1, Endian.little);
    _readerIndex = readIndex + 9;
    return value;
  }

  /// Writes a tagged unsigned 64-bit integer.
  void writeTaggedUint64(int value) {
    final unsigned = _toUnsigned64(value);
    if (unsigned <= 0x7fffffff) {
      writeInt32((unsigned << 1) & 0xffffffff);
      return;
    }
    writeUint8(0x01);
    writeUint64(unsigned);
  }

  /// Reads an unsigned 64-bit integer written by [writeTaggedUint64].
  int readTaggedUint64() {
    final readIndex = _readerIndex;
    final first = _view.getInt32(readIndex, Endian.little);
    if ((first & 1) == 0) {
      _readerIndex = readIndex + 4;
      return first >>> 1;
    }
    final value = _view.getUint64(readIndex + 1, Endian.little);
    _readerIndex = readIndex + 9;
    return value;
  }

  /// Writes a small unsigned integer using the same varint path as
  /// [writeVarUint32].
  void writeVarUint32Small7(int value) => writeVarUint32(value);

  /// Reads a small unsigned integer written by [writeVarUint32Small7].
  int readVarUint32Small7() => readVarUint32();

  /// Writes a small unsigned integer using the same varint path as
  /// [writeVarUint32].
  void writeVarUint32Small14(int value) => writeVarUint32(value);

  /// Reads a small unsigned integer written by [writeVarUint32Small14].
  int readVarUint32Small14() => readVarUint32();

  /// Writes a small unsigned integer using the 64-bit varint path.
  void writeVarUint36Small(int value) => writeVarUint64(value);

  /// Reads a small unsigned integer written by [writeVarUint36Small].
  int readVarUint36Small() => readVarUint64();
}

@internal
int bufferWriterIndex(Buffer buffer) => buffer._writerIndex;

@internal
int bufferReaderIndex(Buffer buffer) => buffer._readerIndex;

@internal
void bufferSetWriterIndex(Buffer buffer, int index) {
  buffer._writerIndex = index;
}

@internal
void bufferSetReaderIndex(Buffer buffer, int index) {
  buffer._readerIndex = index;
}

@internal
void bufferWriteUint8At(Buffer buffer, int index, int value) {
  buffer._view.setUint8(index, value);
}

@internal
int bufferReserveBytes(Buffer buffer, int length) {
  buffer.ensureWritable(length);
  final start = buffer._writerIndex;
  buffer._writerIndex += length;
  return start;
}

@internal
Uint8List bufferBytes(Buffer buffer) => buffer._bytes;

@internal
ByteData bufferByteData(Buffer buffer) => buffer._view;

int _toUnsigned64(int value) => (BigInt.from(value) & _mask64Big).toInt();

extension on Buffer {
  void _writeVarUint64BigInt(BigInt value) {
    var remaining = value & _mask64Big;
    for (var index = 0; index < 8; index += 1) {
      final chunk = (remaining & _sevenBitMaskBig).toInt();
      remaining >>= 7;
      if (remaining == BigInt.zero) {
        writeUint8(chunk);
        return;
      }
      writeUint8(chunk | 0x80);
    }
    writeUint8((remaining & _byteMaskBig).toInt());
  }

  BigInt _readVarUint64BigInt() {
    var shift = 0;
    var result = BigInt.zero;
    while (shift < 56) {
      final byte = readUint8();
      result |= BigInt.from(byte & 0x7f) << shift;
      if ((byte & 0x80) == 0) {
        return result;
      }
      shift += 7;
    }
    return result | ((BigInt.from(readUint8()) & _byteMaskBig) << 56);
  }
}

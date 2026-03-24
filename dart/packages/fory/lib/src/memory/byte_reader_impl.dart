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
import 'package:fory/src/memory/byte_reader.dart';

final class ByteReaderImpl extends ByteReader {
  ByteReaderImpl(Uint8List data,
      {int offset = 0, bool endian = true, int? length})
      : _offset = offset,
        _length = length ?? data.length,
        _bd = ByteData.sublistView(
            data, offset, length == null ? null : offset + length),
        super.internal() {
    // This section checks if the ByteData length is valid.
    // It is checked internally, so no explicit checks are performed here.
  }

  final ByteData _bd;
  int _offset;
  final int _length;

  @override
  int get remaining => _length - _offset;

  @override
  void skip(int length) {
    assert(length > 0);
    _offset += length;
    assert(_offset <= _length);
  }

  @override
  bool readBool() {
    return _bd.getInt8(_offset++) != 0;
  }

  @override
  int readUint8() {
    return _bd.getUint8(_offset++);
  }

  @override
  int readUint16() {
    int value = _bd.getUint16(_offset, endian);
    _offset += 2;
    return value;
  }

  @override
  int readUint32() {
    int value = _bd.getUint32(_offset, endian);
    _offset += 4;
    return value;
  }

  @override
  int readUint64() {
    int value = _bd.getUint64(_offset, endian);
    _offset += 8;
    return value;
  }

  @override
  int readInt8() {
    return _bd.getInt8(_offset++);
  }

  @override
  int readInt16() {
    int value = _bd.getInt16(_offset, endian);
    _offset += 2;
    return value;
  }

  @override
  int readInt32() {
    int value = _bd.getInt32(_offset, endian);
    _offset += 4;
    return value;
  }

  @override
  int readInt64() {
    int value = _bd.getInt64(_offset, endian);
    _offset += 8;
    return value;
  }

  @override
  double readFloat32() {
    double value = _bd.getFloat32(_offset, endian);
    _offset += 4;
    return value;
  }

  @override
  double readFloat64() {
    double value = _bd.getFloat64(_offset, endian);
    _offset += 8;
    return value;
  }

  @override
  Float16 readFloat16() {
    int value = _bd.getUint16(_offset, endian);
    _offset += 2;
    return Float16.fromBits(value);
  }

  @override
  Uint8List readBytesView(int length) {
    // create a view of the original list
    Uint8List view = _bd.buffer.asUint8List(_offset, length);
    _offset += length;
    return view;
  }

  /// To get a view of Uint16List, the start offset must be even, so copying is necessary
  @override
  Uint16List readCopyUint16List(int byteNum) {
    int len = byteNum ~/ 2;
    Uint8List subCopy =
        Uint8List.fromList(_bd.buffer.asUint8List(_offset, byteNum));
    Uint16List view = subCopy.buffer.asUint16List(0, len);
    _offset += byteNum;
    return view;
  }

  @override
  Uint8List copyBytes(int length) {
    Uint8List view = _bd.buffer.asUint8List(_offset, length);
    _offset += length;
    return Uint8List.fromList(view);
  }

  // no prob:2
  @inline
  int _truncateInt32(int v) {
    return (v & 0x80000000) != 0
        ? v | 0xffffffff00000000
        : v & 0x00000000ffffffff;
  }

  /*---------var int----------------------------------------------------------------------*/

  // no prob:2
  /// unroll the loop for better performance
  /// currently only support little-endian
  @inline
  int _readVarUint36Slow() {
    int b = readUint8();
    int result = b & 0x7F;
    // Note:
    //  Loop are not used here to improve performance.
    //  We manually unroll the loop for better performance.
    // noinspection Duplicates
    if ((b & 0x80) != 0) {
      b = readUint8();
      result |= (b & 0x7F) << 7;
      if ((b & 0x80) != 0) {
        b = readUint8();
        result |= (b & 0x7F) << 14;
        if ((b & 0x80) != 0) {
          b = readUint8();
          result |= (b & 0x7F) << 21;
          if ((b & 0x80) != 0) {
            b = readUint8();
            result |= (b & 0xff) << 28;
          }
        }
      }
    }
    return result;
  }

  // /// unroll the loop for better performance
  // /// currently only support little-endian
  // int _readVarUint32Slow() {
  //   int b = readUint8();
  //   int result = b & 0x7F;
  //   // Note:
  //   //  Loop are not used here to improve performance.
  //   //  We manually unroll the loop for better performance.
  //   // noinspection Duplicates
  //   if ((b & 0x80) != 0) {
  //     b = readUint8();
  //     result |= (b & 0x7F) << 7;
  //     if ((b & 0x80) != 0) {
  //       b = readUint8();
  //       result |= (b & 0x7F) << 14;
  //       if ((b & 0x80) != 0) {
  //         b = readUint8();
  //         result |= (b & 0x7F) << 21;
  //         if ((b & 0x80) != 0) {
  //           b = readUint8();
  //           result |= (b & 0x0f) << 28;
  //         }
  //       }
  //     }
  //   }
  //   return result;
  // }

  // TODO: implements faster version

  // no prob: 1
  @inline
  @override
  int readVarInt32() {
    int result = _readVarUint36Slow();
    result &= 0xffffffff;
    result = (result >>> 1) ^ -(result & 1);
    _truncateInt32(result);
    return result;
  }

  // no prob:1
  @inline
  @override
  int readVarInt64() {
    late int res;
    if (_length - _offset < 9) {
      res = _readVarUint64Slow();
    } else {
      int bulkVal = _bd.getInt64(_offset, endian);
      ++_offset;
      res = bulkVal & 0x7F;
      if ((bulkVal & 0x80) != 0) {
        ++_offset;
        // // 0x3f80: 0b1111111 << 7
        res |= ((bulkVal >>> 1) & 0x3f80);
        // 0x8000: 0b1 << 15
        if ((bulkVal & 0x8000) != 0) {
          res = _continueReadVarInt64(bulkVal, res);
        }
      }
    }
    return ((res >>> 1) ^ -(res & 1));
  }

  // no prob: untested
  @inline
  @override
  int readVarUint32Small7() {
    int b = _bd.getInt8(_offset);
    if ((b & 0x80) == 0) {
      ++_offset;
      return b;
    } else {
      return readVarUint32Small14();
    }
  }

  // no prob: untested
  @inline
  @override
  int readBytesAsInt64(int length) {
    int remaining = _length - _offset;
    if (remaining >= 8) {
      // means we can directly read 8 bytes
      int res;
      int off = 64 - length * 8;
      if (endian == Endian.little) {
        int v = _bd.getInt64(_offset, endian);
        res = length == 0
            ? v
            : v &
                (0xffffffffffffffff >>>
                    off); //TODO: currently sync with fory java
        _offset += length;
      } else {
        res = (_bd.getInt64(_offset, endian) >>> off) &
            (0xffffffffffffffff >>> off);
        _offset += length;
      }
      return res;
    }
    return _slowReadBytesAsInt64(length);
  }

  // no prob: untested
  @inline
  int _slowReadBytesAsInt64(int len) {
    // ensure the length is valid
    // assert(_offset + len <= _length);
    int res = 0;
    if (endian == Endian.little) {
      for (int i = 0; i < len; i++) {
        res |= (_bd.getUint8(_offset + i) << (i * 8));
      }
    } else {
      for (int i = 0; i < len; i++) {
        res |= (_bd.getUint8(_offset + i) << ((len - i - 1) * 8));
      }
    }
    _offset += len;
    return res;
  }

  // no prob:1
  @inline
  int _readVarUint64Slow() {
    int b = readInt8();
    int result = b & 0x7F;
    // Note:
    //  Loop are not used here to improve performance.
    //  We manually unroll the loop for better performance.
    if ((b & 0x80) != 0) {
      b = readUint8();
      result |= (b & 0x7F) << 7;
      if ((b & 0x80) != 0) {
        b = readUint8();
        result |= (b & 0x7F) << 14;
        if ((b & 0x80) != 0) {
          b = readUint8();
          result |= (b & 0x7F) << 21;
          if ((b & 0x80) != 0) {
            b = readUint8();
            result |= (b & 0x7F) << 28;
            if ((b & 0x80) != 0) {
              b = readUint8();
              result |= (b & 0x7F) << 35;
              if ((b & 0x80) != 0) {
                b = readUint8();
                result |= (b & 0x7F) << 42;
                if ((b & 0x80) != 0) {
                  b = readUint8();
                  result |= (b & 0x7F) << 49;
                  if ((b & 0x80) != 0) {
                    b = readUint8();
                    // highest bit in last byte is symbols bit.
                    result |= b << 56;
                  }
                }
              }
            }
          }
        }
      }
    }
    return result;
  }

  // @override
  // int readVarUint32() {
  //   assert(endian == Endian.little);
  //   int res = _readVarUint36Slow();
  //   return (res >>> 1) ^ -(res & 1);
  // }

  // unroll the loop for better perf

  // no prob: untested
  @override
  int readVarUint36Small() {
    if (_length - _offset < 9) {
      return _readVarUint36Slow();
    }
    int bulkVal = _bd.getInt64(_offset, endian);
    ++_offset;
    int res = bulkVal & 0x7F;
    if ((bulkVal & 0x80) != 0) {
      ++_offset;
      // // 0x3f80: 0b1111111 << 7
      res |= ((bulkVal >>> 1) & 0x3f80);
      // 0x8000: 0b1 << 15
      if ((bulkVal & 0x8000) != 0) {
        // now continue
        ++_offset;
        // 0x1fc000: 0b1111111 << 14
        res |= (bulkVal >>> 2) & 0x1fc000;
        if ((bulkVal & 0x800000) != 0) {
          ++_offset;
          // 0xfe00000: 0b1111111 << 21
          res |= (bulkVal >>> 3) & 0xfe00000;
          if ((bulkVal & 0x80000000) != 0) {
            ++_offset;
            // 0xfe00000: 0b1111111 << 28
            res |= (bulkVal >>> 4) & 0xff0000000;
          }
        }
      }
    }
    return res;
  }

  // no prob: 1
  int _continueReadVarInt64(int bulkVal, int res) {
    ++_offset;
    res |= ((bulkVal >>> 2) & 0x1fc000);
    if ((bulkVal & 0x800000) != 0) {
      ++_offset;
      res |= (bulkVal >>> 3) & 0xfe00000;
      if ((bulkVal & 0x80000000) != 0) {
        ++_offset;
        res |= (bulkVal >>> 4) & 0x7f0000000;
        if ((bulkVal & 0x8000000000) != 0) {
          ++_offset;
          res |= (bulkVal >>> 5) & 0x3f800000000;
          if ((bulkVal & 0x800000000000) != 0) {
            ++_offset;
            res |= (bulkVal >>> 6) & 0x1fc0000000000;
            if ((bulkVal & 0x80000000000000) != 0) {
              ++_offset;
              res |= (bulkVal >>> 7) & 0xfe000000000000;
              if ((bulkVal & 0x8000000000000000) != 0) {
                int b = _bd.getUint8(_offset);
                ++_offset;
                res |= b << 56;
              }
            }
          }
        }
      }
    }
    return res;
  }

  // no prob: untested
  @inline
  @override
  int readVarUint32Small14() {
    if (_length - _offset < 5) {
      return _readVarUint36Slow() & 0xffffffff;
    }
    int forByteVal =
        _bd.getUint32(_offset, endian); // Keep the high 32 bits as 0
    ++_offset;
    int val = forByteVal & 0x7F;
    if ((forByteVal & 0x80) != 0) {
      ++_offset;
      // // 0x3f80: 0b1111111 << 7
      val |= ((forByteVal >>> 1) & 0x3f80);
      // 0x8000: 0b1 << 15
      if ((forByteVal & 0x8000) != 0) {
        return _continueReadVarUint32(forByteVal, val);
      }
    }
    return val;
  }

  // no prob: untested
  @inline
  int _continueReadVarUint32(bulkVal, val) {
    ++_offset;
    val |= (bulkVal >>> 2) & 0x1fc000;
    if ((bulkVal & 0x800000) != 0) {
      ++_offset;
      val |= (bulkVal >>> 3) & 0xfe00000;
      if ((bulkVal & 0x80000000) != 0) {
        val |= (_bd.getUint8(_offset++) & 0x7f) << 28;
      }
    }
    return val;
  }

  @override
  int readVarUint32() {
    if (_length - _offset < 5) {
      return _truncateInt32(_readVarUint36Slow());
    }
    int bulkVal = _bd.getUint32(_offset, endian);
    ++_offset;
    int res = bulkVal & 0x7F;
    if ((bulkVal & 0x80) != 0) {
      ++_offset;
      // // 0x3f80: 0b1111111 << 7
      res |= ((bulkVal >>> 1) & 0x3f80);
      // 0x8000: 0b1 << 15
      if ((bulkVal & 0x8000) != 0) {
        ++_offset;
        // 0x1fc000: 0b1111111 << 14
        res |= (bulkVal >>> 2) & 0x1fc000;
        if ((bulkVal & 0x800000) != 0) {
          ++_offset;
          // 0xfe00000: 0b1111111 << 21
          res |= (bulkVal >>> 3) & 0xfe00000;
          if ((bulkVal & 0x80000000) != 0) {
            // 0xfe00000: 0b1111111 << 21
            res |= (_bd.getUint8(_offset++) & 0x0f) << 28;
          }
        }
      }
    }
    return res;
  }
}

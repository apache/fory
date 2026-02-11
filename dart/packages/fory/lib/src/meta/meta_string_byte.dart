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
import 'package:fory/src/codec/meta_string_encoding.dart';
import 'package:fory/src/meta/meta_string.dart';
import 'package:fory/src/util/extension/uint8list_extensions.dart';
import 'package:fory/src/util/murmur3hash.dart';

final class MetaStringBytes {
  // static late final MetaStringBytes empty;
  // static const int defDynamicWriteStrId = -1;
  static const int _headerMask = 0xff;
  // bytes maybe a view of a larger buffer, do not modify it
  final Uint8List _bytes;
  final int _hashCode;
  late final MetaStringEncoding _encoding;
  late final int _first8Bytes;
  late final int _second8Bytes;
  // int dynamicWriteStrId = defDynamicWriteStrId;

  // cons
  MetaStringBytes(this._bytes, this._hashCode) {
    assert(hashCode != 0);
    int header = hashCode & _headerMask;
    _encoding = MetaStringEncoding.fromId(header);
    // in case length is less than 16
    Uint8List data = _bytes;
    if (_bytes.length < 16) {
      data = Uint8List(16);
      data.setAll(0, _bytes);
    }
    ByteData byteData = data.buffer.asByteData();
    _first8Bytes = byteData.getInt64(0, Endian.little);
    _second8Bytes = byteData.getInt64(8, Endian.little);
  }

  factory MetaStringBytes.of(MetaString ms) {
    Uint8List bytes = ms.bytes;
    int hashCode = Murmur3Hash.hash128x64(bytes, 0, bytes.length, 47).$1;
    hashCode = hashCode.abs();
    if (hashCode == 0) {
      hashCode = 256;
    }
    hashCode &= 0xffffffffffffff00;
    MetaStringEncoding encoding = ms.encoding;
    int header = encoding.id & _headerMask;
    hashCode = hashCode | header;
    return MetaStringBytes(bytes, hashCode);
  }

  // getter
  Uint8List get bytes => _bytes;
  int get length => _bytes.length;
  MetaStringEncoding get encoding => _encoding;
  // bool get isDefWriteId => dynamicWriteStrId == defDynamicWriteStrId;

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        other is MetaStringBytes &&
            runtimeType == other.runtimeType &&
            _first8Bytes == other._first8Bytes &&
            _second8Bytes == other._second8Bytes &&
            _encoding == other._encoding &&
            _bytes.memEquals(other._bytes);
  }

  @override
  int get hashCode => _hashCode;

  // @inline
  // void resetDynamicWriteStrId(){
  //   dynamicWriteStrId = defDynamicWriteStrId;
  // }
}

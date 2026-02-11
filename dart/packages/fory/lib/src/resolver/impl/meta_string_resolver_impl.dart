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

import 'dart:collection';
import 'dart:typed_data';
import 'package:fory/src/codec/encoders.dart';
import 'package:fory/src/codec/meta_string_decoder.dart';
import 'package:fory/src/collection/long_long_key.dart';
import 'package:fory/src/dev_annotation/optimize.dart';
import 'package:fory/src/memory/byte_reader.dart';
import 'package:fory/src/meta/meta_string.dart';
import 'package:fory/src/meta/meta_string_byte.dart';
import 'package:fory/src/resolver/meta_string_resolver.dart';
import 'package:fory/src/util/murmur3hash.dart';

/// only serve one serialization/deserialization process
class MetaStringResolverImpl extends MetaStringResolver {
  // static const MetaStringDecoder _decoder = Encoders.genericDecoder;
  static const MetaStringDecoder _typeNameDecoder = Encoders.typeNameDecoder;
  static const MetaStringDecoder _pkgDecoder = Encoders.packageDecoder;

  final Map<int, MetaStringBytes> _hash2MSB = HashMap();
  final Map<LongLongKey, MetaStringBytes> _longLong2MSB = HashMap();
  final List<MetaStringBytes> _readId2MSB = [];
  final Map<MetaString, MetaStringBytes> _metaString2MSB = HashMap();
  // Empty meta string uses utf8 encoding id 0 and hashCode 256 (see MetaStringBytes.of).
  final MetaStringBytes _emptyMetaStringBytes =
      MetaStringBytes(Uint8List(0), 256);

  MetaStringResolverImpl();

  @override
  MetaStringBytes getOrCreateMetaStringBytes(MetaString mstr) {
    MetaStringBytes? bytes = _metaString2MSB[mstr];
    if (bytes == null) {
      bytes = MetaStringBytes.of(mstr);
      _metaString2MSB[mstr] = bytes;
    }
    return bytes;
  }

  MetaStringBytes _createAndCacheMetaStringBytes(
      int len, int encoding, int v1, int v2) {
    Uint8List bytes = Uint8List(16);
    ByteData data = ByteData.view(bytes.buffer);
    data.setInt64(0, v1, Endian.little);
    data.setInt64(8, v2, Endian.little);

    int hashCode = Murmur3Hash.hash128x64(bytes, len).$1;
    hashCode = hashCode.abs();
    hashCode = (hashCode & 0xffffffffffffff00) | encoding;
    // The bytes here are a view, not copied
    MetaStringBytes msb =
        MetaStringBytes(bytes.buffer.asUint8List(0, len), hashCode);
    _longLong2MSB[LongLongKey(v1, v2)] = msb;
    return msb;
  }

  /// read meta string bytes from reader
  /// if the bytes are not cached, cache them, if the bytes are cached, return the cached bytes
  /// [len] is the length of the string bytes, [hashCode] is the hash code of the string bytes
  MetaStringBytes _readBigStringBytes(
      ByteReader reader, int len, int hashCode) {
    MetaStringBytes? bytes = _hash2MSB[hashCode];
    if (bytes != null) {
      // not cached
      bytes = MetaStringBytes(reader.copyBytes(len), hashCode);
      _hash2MSB[hashCode] = bytes;
    } else {
      reader.skip(len);
    }
    return bytes!;
  }

  /// read meta string bytes from reader
  /// if the bytes are not cached, cache them, if the bytes are cached, return the cached bytes
  /// [len] is the length of the string bytes, [hashCode] is the hash code of the string bytes
  /// [v1] and [v2] are the two parts of the long long key
  /// [encoding] is the encoding of the string bytes
  MetaStringBytes _readSmallStringBytes(ByteReader reader, int len) {
    if (len == 0) {
      return _emptyMetaStringBytes;
    }
    late final int v1;
    int v2 = 0;
    int encoding = reader.readInt8();
    if (len <= 8) {
      v1 = reader.readBytesAsInt64(len);
    } else {
      v1 = reader.readInt64();
      v2 = reader.readBytesAsInt64(len - 8);
    }
    var key = LongLongKey(v1, v2);
    MetaStringBytes? bytes = _longLong2MSB[key];
    bytes ??= _createAndCacheMetaStringBytes(len, encoding, v1, v2);
    return bytes;
  }

  @override
  MetaStringBytes readMetaStringBytes(ByteReader br) {
    /* for big meta string
     |header  |hash   |contents|
     |7+1 bits|64 bits|len bits|
     for small meta string: no hash
     header :
     last bit: flag
     bit before last bit: length of the string bytes */
    int header = br.readVarUint32Small7();
    int len = header >>> 1;
    if ((header & 1) == 0) {
      // means that can't directly get MSB from _readId2MSB
      MetaStringBytes bytes = len > smallStringThreshold
          ? _readBigStringBytes(br, len, br.readInt64())
          : _readSmallStringBytes(br, len);
      _readId2MSB.add(bytes);
      return bytes;
    } else {
      // means that can directly get MSB from _readId2MSB, index is len - 1
      return _readId2MSB[len - 1];
    }
  }

  @override
  @inline
  String decodeNamespace(MetaStringBytes msb) {
    return _pkgDecoder.decode(msb.bytes, msb.encoding);
  }

  @override
  @inline
  String decodeTypename(MetaStringBytes msb) {
    return _typeNameDecoder.decode(msb.bytes, msb.encoding);
  }
}

// @override
// String readMetaString(ByteReader reader) {
//   MetaStringBytes bytes = readMetaStringBytes(reader);
//   String? str = _msb2String[bytes];
//   if (str == null){
//     // decode and cache
//     str = _decoder.decodeMetaString(bytes);
//     _msb2String[bytes] = str;
//   }
//   return str;
// }

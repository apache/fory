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

import 'package:fory/src/memory/buffer.dart';
import 'package:fory/src/meta/meta_string.dart';
import 'package:fory/src/resolver/type_resolver.dart';
import 'package:fory/src/types/int64.dart';

typedef _MetaStringWords = ({
  int length,
  int word0,
  int word1,
  int word2,
  int word3
});

/// Read-side state for meta-string references in one deserialization stream.
final class MetaStringReader {
  final TypeResolver _typeResolver;
  final List<EncodedMetaString> _dynamicReadMetaStrings = <EncodedMetaString>[];
  final Map<Int64, EncodedMetaString> _bigMetaStrings =
      <Int64, EncodedMetaString>{};
  final Map<int, List<EncodedMetaString>> _smallMetaStrings =
      <int, List<EncodedMetaString>>{};

  MetaStringReader(this._typeResolver);

  /// Clears dynamic ids so the reader can be reused for a new operation.
  void reset() {
    _dynamicReadMetaStrings.clear();
  }

  /// Reads one meta string, resolving dynamic references when present.
  ///
  /// Callers with a likely expected value may pass [expected] to avoid an
  /// additional map lookup in the common exact-match case.
  EncodedMetaString readMetaString(
    Buffer buffer, [
    EncodedMetaString? expected,
  ]) {
    final header = buffer.readVarUint32Small7();
    final length = header >>> 1;
    if ((header & 1) == 1) {
      return _dynamicReadMetaStrings[length - 1];
    }
    final encoded = length > metaStringSmallThreshold
        ? _readBigMetaString(buffer, length, expected)
        : _readSmallMetaString(buffer, length, expected);
    _dynamicReadMetaStrings.add(encoded);
    return encoded;
  }

  EncodedMetaString _readBigMetaString(
    Buffer buffer,
    int length,
    EncodedMetaString? expected,
  ) {
    final hash = buffer.readInt64();
    if (expected != null && expected.hash == hash) {
      buffer.skip(length);
      return expected;
    }
    final cached = _bigMetaStrings[hash];
    if (cached != null) {
      buffer.skip(length);
      return cached;
    }
    final encoded = _typeResolver.internEncodedMetaString(
      buffer.copyBytes(length),
      encoding: (hash & 0xff).toInt(),
    );
    _bigMetaStrings[hash] = encoded;
    return encoded;
  }

  EncodedMetaString _readSmallMetaString(
    Buffer buffer,
    int length,
    EncodedMetaString? expected,
  ) {
    if (length == 0) {
      return EncodedMetaString.empty;
    }
    final encoding = buffer.readByte() & 0xff;
    final words = _readMetaStringWords(buffer, length);
    final word0 = words.word0;
    final word1 = words.word1;
    final word2 = words.word2;
    final word3 = words.word3;
    if (expected != null &&
        expected.matchesPacked(
          encoding,
          length,
          word0,
          word1,
          word2,
          word3,
        )) {
      return expected;
    }
    final hash = _smallMetaStringHash(
      encoding,
      length,
      word0,
      word1,
      word2,
      word3,
    );
    final bucket = _smallMetaStrings[hash];
    if (bucket != null) {
      for (final cached in bucket) {
        if (cached.matchesPacked(
            encoding, length, word0, word1, word2, word3)) {
          return cached;
        }
      }
    }
    final encoded = _typeResolver.internEncodedMetaString(
      _materializeMetaStringWords(words),
      encoding: encoding,
    );
    (bucket ?? (_smallMetaStrings[hash] = <EncodedMetaString>[])).add(
      encoded,
    );
    return encoded;
  }
}

int _smallMetaStringHash(
  int encoding,
  int length,
  int word0,
  int word1,
  int word2,
  int word3,
) {
  var hash = 0x811c9dc5;
  hash = (hash ^ encoding) * 0x01000193;
  hash = (hash ^ length) * 0x01000193;
  hash = (hash ^ word0) * 0x01000193;
  hash = (hash ^ word1) * 0x01000193;
  hash = (hash ^ word2) * 0x01000193;
  hash = (hash ^ word3) * 0x01000193;
  return hash;
}

_MetaStringWords _readMetaStringWords(Buffer buffer, int length) {
  final start = bufferReaderIndex(buffer);
  bufferSetReaderIndex(buffer, start + length);
  final bytes = bufferBytes(buffer);
  var word0 = 0;
  var word1 = 0;
  var word2 = 0;
  var word3 = 0;
  for (var index = 0; index < length; index += 1) {
    final byte = bytes[start + index] & 0xff;
    final shift = (index & 0x03) << 3;
    switch (index >> 2) {
      case 0:
        word0 |= byte << shift;
        break;
      case 1:
        word1 |= byte << shift;
        break;
      case 2:
        word2 |= byte << shift;
        break;
      default:
        word3 |= byte << shift;
        break;
    }
  }
  return (
    length: length,
    word0: word0,
    word1: word1,
    word2: word2,
    word3: word3,
  );
}

Uint8List _materializeMetaStringWords(_MetaStringWords words) {
  final bytes = Uint8List(words.length);

  void unpackWord(int word, int offset) {
    final end = offset + 4;
    for (var index = offset; index < words.length && index < end; index += 1) {
      bytes[index] = (word >> ((index - offset) << 3)) & 0xff;
    }
  }

  unpackWord(words.word0, 0);
  unpackWord(words.word1, 4);
  unpackWord(words.word2, 8);
  unpackWord(words.word3, 12);
  return bytes;
}

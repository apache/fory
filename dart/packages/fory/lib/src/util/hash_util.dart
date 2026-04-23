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

import 'dart:convert';

import 'package:fory/src/meta/field_info.dart';
import 'package:fory/src/meta/type_def.dart';
import 'package:fory/src/meta/type_ids.dart';
import 'package:fory/src/types/int64.dart';
import 'package:fory/src/types/uint64.dart';

const int _typeDefCompressMetaFlag = 1 << 9;
const int _typeDefHasFieldsMetaFlag = 1 << 8;
const int _typeDefMetaSizeMask = 0xff;
const int _typeDefHashShift = 14;

final Uint64 _metaStringHashMask = Uint64.fromWords(0xffffff00, 0xffffffff);
final Uint64 _c1 = Uint64.fromWords(0x114253d5, 0x87c37b91);
final Uint64 _c2 = Uint64.fromWords(0x2745937f, 0x4cf5ad43);
final Uint64 _fmixC1 = Uint64.fromWords(0xed558ccd, 0xff51afd7);
final Uint64 _fmixC2 = Uint64.fromWords(0x1a85ec53, 0xc4ceb9fe);

(Int64, Int64) murmurHash3X64_128(List<int> bytes, {int seed = 47}) {
  final hash = _murmurHash3X64_128Bits(bytes, seed: seed);
  return (_int64FromUint64(hash.$1), _int64FromUint64(hash.$2));
}

(Uint64, Uint64) _murmurHash3X64_128Bits(List<int> bytes, {int seed = 47}) {
  var h1 = Uint64(seed & 0x00000000ffffffff);
  var h2 = Uint64(seed & 0x00000000ffffffff);

  final blockCount = bytes.length ~/ 16;
  for (var index = 0; index < blockCount; index += 1) {
    var k1 = _readLongLittleEndian(bytes, index * 16);
    var k2 = _readLongLittleEndian(bytes, index * 16 + 8);

    k1 = k1 * _c1;
    k1 = _rotateLeft64(k1, 31);
    k1 = k1 * _c2;
    h1 = h1 ^ k1;

    h1 = _rotateLeft64(h1, 27);
    h1 = h1 + h2;
    h1 = (h1 * 5) + 0x52dce729;

    k2 = k2 * _c2;
    k2 = _rotateLeft64(k2, 33);
    k2 = k2 * _c1;
    h2 = h2 ^ k2;

    h2 = _rotateLeft64(h2, 31);
    h2 = h2 + h1;
    h2 = (h2 * 5) + 0x38495ab5;
  }

  var k1 = Uint64(0);
  var k2 = Uint64(0);
  final tailOffset = blockCount * 16;
  final tailLength = bytes.length & 15;
  if (tailLength >= 15) {
    k2 = k2 ^ (Uint64(bytes[tailOffset + 14] & 0xff) << 48);
  }
  if (tailLength >= 14) {
    k2 = k2 ^ (Uint64(bytes[tailOffset + 13] & 0xff) << 40);
  }
  if (tailLength >= 13) {
    k2 = k2 ^ (Uint64(bytes[tailOffset + 12] & 0xff) << 32);
  }
  if (tailLength >= 12) {
    k2 = k2 ^ (Uint64(bytes[tailOffset + 11] & 0xff) << 24);
  }
  if (tailLength >= 11) {
    k2 = k2 ^ (Uint64(bytes[tailOffset + 10] & 0xff) << 16);
  }
  if (tailLength >= 10) {
    k2 = k2 ^ (Uint64(bytes[tailOffset + 9] & 0xff) << 8);
  }
  if (tailLength >= 9) {
    k2 = k2 ^ (bytes[tailOffset + 8] & 0xff);
    k2 = k2 * _c2;
    k2 = _rotateLeft64(k2, 33);
    k2 = k2 * _c1;
    h2 = h2 ^ k2;
  }
  if (tailLength >= 8) {
    k1 = k1 ^ (Uint64(bytes[tailOffset + 7] & 0xff) << 56);
  }
  if (tailLength >= 7) {
    k1 = k1 ^ (Uint64(bytes[tailOffset + 6] & 0xff) << 48);
  }
  if (tailLength >= 6) {
    k1 = k1 ^ (Uint64(bytes[tailOffset + 5] & 0xff) << 40);
  }
  if (tailLength >= 5) {
    k1 = k1 ^ (Uint64(bytes[tailOffset + 4] & 0xff) << 32);
  }
  if (tailLength >= 4) {
    k1 = k1 ^ (Uint64(bytes[tailOffset + 3] & 0xff) << 24);
  }
  if (tailLength >= 3) {
    k1 = k1 ^ (Uint64(bytes[tailOffset + 2] & 0xff) << 16);
  }
  if (tailLength >= 2) {
    k1 = k1 ^ (Uint64(bytes[tailOffset + 1] & 0xff) << 8);
  }
  if (tailLength >= 1) {
    k1 = k1 ^ (bytes[tailOffset] & 0xff);
    k1 = k1 * _c1;
    k1 = _rotateLeft64(k1, 31);
    k1 = k1 * _c2;
    h1 = h1 ^ k1;
  }

  h1 = h1 ^ bytes.length;
  h2 = h2 ^ bytes.length;

  h1 = h1 + h2;
  h2 = h2 + h1;

  h1 = _fmix64(h1);
  h2 = _fmix64(h2);

  h1 = h1 + h2;
  h2 = h2 + h1;

  return (h1, h2);
}

Int64 metaStringHash(List<int> bytes, {int encoding = 0}) {
  var hash =
      _absSigned64Bits(_int64FromUint64(_murmurHash3X64_128Bits(bytes).$1));
  if (hash.isZero) {
    hash = hash + 0x100;
  }
  hash = (hash & _metaStringHashMask) | (encoding & 0xff);
  return _int64FromUint64(hash);
}

Int64 typeDefHeader(
  List<int> bytes, {
  required bool hasFieldsMeta,
  bool compressed = false,
}) {
  final hash = _int64FromUint64(
    _murmurHash3X64_128Bits(bytes).$1 << _typeDefHashShift,
  );
  var header = _absSigned64Bits(hash);
  if (compressed) {
    header = header | _typeDefCompressMetaFlag;
  }
  if (hasFieldsMeta) {
    header = header | _typeDefHasFieldsMetaFlag;
  }
  header = header |
      (bytes.length > _typeDefMetaSizeMask
          ? _typeDefMetaSizeMask
          : bytes.length);
  return _int64FromUint64(header);
}

int schemaHash(TypeDef typeDef) {
  final parts = typeDef.fields
      .map(
        (field) => StringBuffer()
          ..write(field.identifier)
          ..write(',')
          ..write(_fingerprintTypeId(field))
          ..write(',')
          ..write(field.fieldType.ref ? '1' : '0')
          ..write(',')
          ..write(field.fieldType.nullable ? '1' : '0')
          ..write(';'),
      )
      .map((buffer) => buffer.toString())
      .toList(growable: false)
    ..sort();
  return _murmurHash3X64_128Bits(utf8.encode(parts.join())).$1.low32;
}

int _fingerprintTypeId(FieldInfo field) {
  final typeId = field.fieldType.typeId;
  if (field.fieldType.isDynamic || typeId == TypeIds.unknown) {
    return TypeIds.unknown;
  }
  switch (typeId) {
    case TypeIds.enumById:
    case TypeIds.namedEnum:
    case TypeIds.struct:
    case TypeIds.compatibleStruct:
    case TypeIds.namedStruct:
    case TypeIds.namedCompatibleStruct:
    case TypeIds.ext:
    case TypeIds.namedExt:
    case TypeIds.union:
    case TypeIds.typedUnion:
    case TypeIds.namedUnion:
      return TypeIds.unknown;
    default:
      return typeId;
  }
}

Uint64 _readLongLittleEndian(List<int> bytes, int offset) {
  final low = (bytes[offset] & 0xff) +
      ((bytes[offset + 1] & 0xff) << 8) +
      ((bytes[offset + 2] & 0xff) << 16) +
      ((bytes[offset + 3] & 0xff) * 0x1000000);
  final high = (bytes[offset + 4] & 0xff) +
      ((bytes[offset + 5] & 0xff) << 8) +
      ((bytes[offset + 6] & 0xff) << 16) +
      ((bytes[offset + 7] & 0xff) * 0x1000000);
  return Uint64.fromWords(low, high);
}

Uint64 _rotateLeft64(Uint64 value, int shift) {
  return (value << shift) | (value >> (64 - shift));
}

Uint64 _fmix64(Uint64 value) {
  var mixed = value;
  mixed = mixed ^ (mixed >> 33);
  mixed = mixed * _fmixC1;
  mixed = mixed ^ (mixed >> 33);
  mixed = mixed * _fmixC2;
  mixed = mixed ^ (mixed >> 33);
  return mixed;
}

Uint64 _absSigned64Bits(Int64 value) {
  if (!value.isNegative) {
    return _uint64FromInt64(value);
  }
  final negated = -value;
  return Uint64.fromWords(negated.low32, negated.high32Unsigned);
}

Int64 _int64FromUint64(Uint64 value) =>
    Int64.fromWords(value.low32, value.high32Unsigned);

Uint64 _uint64FromInt64(Int64 value) =>
    Uint64.fromWords(value.low32, value.high32Unsigned);

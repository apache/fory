import 'dart:convert';

import 'package:fory/src/meta/field_info.dart';
import 'package:fory/src/meta/type_def.dart';
import 'package:fory/src/resolver/type_resolver.dart' show TypeIds;

const int _typeDefCompressMetaFlag = 1 << 9;
const int _typeDefHasFieldsMetaFlag = 1 << 8;
const int _typeDefMetaSizeMask = 0xff;
const int _typeDefHashShift = 14;

final BigInt _mask64Big = (BigInt.one << 64) - BigInt.one;
final BigInt _signBit64Big = BigInt.one << 63;
final BigInt _metaStringHashMaskBig =
    BigInt.parse('ffffffffffffff00', radix: 16);
final BigInt _c1Big = BigInt.parse('87c37b91114253d5', radix: 16);
final BigInt _c2Big = BigInt.parse('4cf5ad432745937f', radix: 16);

(int, int) murmurHash3X64_128(List<int> bytes, {int seed = 47}) {
  var h1 = seed & 0x00000000ffffffff;
  var h2 = seed & 0x00000000ffffffff;

  final blockCount = bytes.length ~/ 16;
  for (var index = 0; index < blockCount; index += 1) {
    var k1 = _readLongLittleEndian(bytes, index * 16);
    var k2 = _readLongLittleEndian(bytes, index * 16 + 8);

    k1 = _mul64(k1, _c1Big);
    k1 = _rotateLeft64(k1, 31);
    k1 = _mul64(k1, _c2Big);
    h1 = _toSigned64(h1 ^ k1);

    h1 = _rotateLeft64(h1, 27);
    h1 = _add64(h1, h2);
    h1 = _add64(_mul64(h1, BigInt.from(5)), 0x52dce729);

    k2 = _mul64(k2, _c2Big);
    k2 = _rotateLeft64(k2, 33);
    k2 = _mul64(k2, _c1Big);
    h2 = _toSigned64(h2 ^ k2);

    h2 = _rotateLeft64(h2, 31);
    h2 = _add64(h2, h1);
    h2 = _add64(_mul64(h2, BigInt.from(5)), 0x38495ab5);
  }

  var k1 = 0;
  var k2 = 0;
  final tailOffset = blockCount * 16;
  final tailLength = bytes.length & 15;
  if (tailLength >= 15) {
    k2 ^= (bytes[tailOffset + 14] & 0xff) << 48;
  }
  if (tailLength >= 14) {
    k2 ^= (bytes[tailOffset + 13] & 0xff) << 40;
  }
  if (tailLength >= 13) {
    k2 ^= (bytes[tailOffset + 12] & 0xff) << 32;
  }
  if (tailLength >= 12) {
    k2 ^= (bytes[tailOffset + 11] & 0xff) << 24;
  }
  if (tailLength >= 11) {
    k2 ^= (bytes[tailOffset + 10] & 0xff) << 16;
  }
  if (tailLength >= 10) {
    k2 ^= (bytes[tailOffset + 9] & 0xff) << 8;
  }
  if (tailLength >= 9) {
    k2 ^= bytes[tailOffset + 8] & 0xff;
    k2 = _mul64(k2, _c2Big);
    k2 = _rotateLeft64(k2, 33);
    k2 = _mul64(k2, _c1Big);
    h2 = _toSigned64(h2 ^ k2);
  }
  if (tailLength >= 8) {
    k1 ^= _signedByte(bytes[tailOffset + 7]) << 56;
  }
  if (tailLength >= 7) {
    k1 ^= (bytes[tailOffset + 6] & 0xff) << 48;
  }
  if (tailLength >= 6) {
    k1 ^= (bytes[tailOffset + 5] & 0xff) << 40;
  }
  if (tailLength >= 5) {
    k1 ^= (bytes[tailOffset + 4] & 0xff) << 32;
  }
  if (tailLength >= 4) {
    k1 ^= (bytes[tailOffset + 3] & 0xff) << 24;
  }
  if (tailLength >= 3) {
    k1 ^= (bytes[tailOffset + 2] & 0xff) << 16;
  }
  if (tailLength >= 2) {
    k1 ^= (bytes[tailOffset + 1] & 0xff) << 8;
  }
  if (tailLength >= 1) {
    k1 ^= bytes[tailOffset] & 0xff;
    k1 = _mul64(k1, _c1Big);
    k1 = _rotateLeft64(k1, 31);
    k1 = _mul64(k1, _c2Big);
    h1 = _toSigned64(h1 ^ k1);
  }

  h1 = _toSigned64(h1 ^ bytes.length);
  h2 = _toSigned64(h2 ^ bytes.length);

  h1 = _add64(h1, h2);
  h2 = _add64(h2, h1);

  h1 = _fmix64(h1);
  h2 = _fmix64(h2);

  h1 = _add64(h1, h2);
  h2 = _add64(h2, h1);

  return (h1, h2);
}

int metaStringHash(List<int> bytes, {int encoding = 0}) {
  var hash = _absSigned64(murmurHash3X64_128(bytes).$1);
  if (hash == 0) {
    hash += 0x100;
  }
  hash = (BigInt.from(hash) & _metaStringHashMaskBig).toInt();
  return hash | (encoding & 0xff);
}

int typeDefHeader(
  List<int> bytes, {
  required bool hasFieldsMeta,
  bool compressed = false,
}) {
  final hash = _toSigned64(murmurHash3X64_128(bytes).$1 << _typeDefHashShift);
  var header = _absSigned64(hash);
  if (compressed) {
    header |= _typeDefCompressMetaFlag;
  }
  if (hasFieldsMeta) {
    header |= _typeDefHasFieldsMetaFlag;
  }
  header |=
      bytes.length > _typeDefMetaSizeMask ? _typeDefMetaSizeMask : bytes.length;
  return _toSigned64(header);
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
  final hash = murmurHash3X64_128(utf8.encode(parts.join())).$1;
  return hash & 0xffffffff;
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

int _toSigned64Big(BigInt value) {
  final normalized = _u64Big(value);
  if ((normalized & _signBit64Big) != BigInt.zero) {
    return (normalized - (BigInt.one << 64)).toInt();
  }
  return normalized.toInt();
}

int _readLongLittleEndian(List<int> bytes, int offset) {
  final value = (_signedByte(bytes[offset + 7]) << 56) |
      ((bytes[offset + 6] & 0xff) << 48) |
      ((bytes[offset + 5] & 0xff) << 40) |
      ((bytes[offset + 4] & 0xff) << 32) |
      ((bytes[offset + 3] & 0xff) << 24) |
      ((bytes[offset + 2] & 0xff) << 16) |
      ((bytes[offset + 1] & 0xff) << 8) |
      (bytes[offset] & 0xff);
  return _toSigned64(value);
}

int _signedByte(int value) => value >= 0x80 ? value - 0x100 : value;

int _rotateLeft64(int value, int shift) {
  final normalized = _u64Big(BigInt.from(value));
  return _toSigned64Big((normalized << shift) | (normalized >> (64 - shift)));
}

int _unsignedRightShift64(int value, int shift) =>
    (_u64Big(BigInt.from(value)) >> shift).toInt();

int _mul64(int value, BigInt factor) =>
    _toSigned64Big(BigInt.from(value) * factor);

int _add64(int left, int right) =>
    _toSigned64Big(BigInt.from(left) + BigInt.from(right));

int _fmix64(int value) {
  var mixed = value;
  mixed = _toSigned64(mixed ^ _unsignedRightShift64(mixed, 33));
  mixed = _mul64(mixed, BigInt.parse('ff51afd7ed558ccd', radix: 16));
  mixed = _toSigned64(mixed ^ _unsignedRightShift64(mixed, 33));
  mixed = _mul64(mixed, BigInt.parse('c4ceb9fe1a85ec53', radix: 16));
  mixed = _toSigned64(mixed ^ _unsignedRightShift64(mixed, 33));
  return mixed;
}

BigInt _u64Big(BigInt value) => value & _mask64Big;

int _toSigned64(int value) {
  return _toSigned64Big(BigInt.from(value));
}

int _absSigned64(int value) => value < 0 ? -value : value;

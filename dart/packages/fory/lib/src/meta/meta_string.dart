import 'dart:convert';
import 'dart:typed_data';

import 'package:fory/src/util/hash_util.dart';

const int metaStringUtf8Encoding = 0;
const int metaStringLowerSpecialEncoding = 1;
const int metaStringLowerUpperDigitSpecialEncoding = 2;
const int metaStringFirstToLowerSpecialEncoding = 3;
const int metaStringAllToLowerSpecialEncoding = 4;

const int metaStringSmallThreshold = 16;
const int typeDefSmallFieldCountThreshold = 0x1f;
const int typeDefRegisterByNameFlag = 0x20;
const int typeDefBigFieldNameThreshold = 0x0f;
const int typeDefBigNameThreshold = 0x3f;

const List<int> _packageNameCompactEncodings = <int>[
  metaStringUtf8Encoding,
  metaStringAllToLowerSpecialEncoding,
  metaStringLowerUpperDigitSpecialEncoding,
];
const List<int> _typeNameCompactEncodings = <int>[
  metaStringUtf8Encoding,
  metaStringAllToLowerSpecialEncoding,
  metaStringLowerUpperDigitSpecialEncoding,
  metaStringFirstToLowerSpecialEncoding,
];
const List<int> _fieldNameCompactEncodings = <int>[
  metaStringUtf8Encoding,
  metaStringAllToLowerSpecialEncoding,
  metaStringLowerUpperDigitSpecialEncoding,
];

final class EncodedMetaString {
  final Uint8List bytes;
  final int encoding;
  final int hash;
  final int firstWord0;
  final int firstWord1;
  final int secondWord0;
  final int secondWord1;

  static final EncodedMetaString empty =
      EncodedMetaString(Uint8List(0), metaStringUtf8Encoding);

  EncodedMetaString(this.bytes, this.encoding)
      : hash = metaStringHash(bytes, encoding: encoding),
        firstWord0 = _packLittleEndian32(bytes, 0),
        firstWord1 = _packLittleEndian32(bytes, 4),
        secondWord0 = _packLittleEndian32(bytes, 8),
        secondWord1 = _packLittleEndian32(bytes, 12);

  int get length => bytes.length;

  bool get isSmall => bytes.length <= metaStringSmallThreshold;

  bool matchesPacked(
    int packedEncoding,
    int packedLength,
    int packedWord0,
    int packedWord1,
    int packedWord2,
    int packedWord3,
  ) {
    return encoding == packedEncoding &&
        bytes.length == packedLength &&
        firstWord0 == packedWord0 &&
        firstWord1 == packedWord1 &&
        secondWord0 == packedWord2 &&
        secondWord1 == packedWord3;
  }
}

EncodedMetaString encodePackageMetaString(String value) => _encodeMetaString(
      value,
      specialChar1: '.',
      specialChar2: '_',
      allowedEncodings: _packageNameCompactEncodings,
    );

EncodedMetaString encodeTypeNameMetaString(String value) => _encodeMetaString(
      value,
      specialChar1: r'$',
      specialChar2: '_',
      allowedEncodings: _typeNameCompactEncodings,
    );

EncodedMetaString encodeFieldNameMetaString(String value) => _encodeMetaString(
      value,
      specialChar1: r'$',
      specialChar2: '_',
      allowedEncodings: _fieldNameCompactEncodings,
    );

int packageNameCompactEncoding(int encoding) =>
    _compactEncodingIndex(encoding, _packageNameCompactEncodings, 'package');

int typeNameCompactEncoding(int encoding) =>
    _compactEncodingIndex(encoding, _typeNameCompactEncodings, 'type name');

int fieldNameCompactEncoding(int encoding) =>
    _compactEncodingIndex(encoding, _fieldNameCompactEncodings, 'field name');

int packageNameEncoding(int compactEncoding) => _compactEncodingValue(
      compactEncoding,
      _packageNameCompactEncodings,
      'package',
    );

int typeNameEncoding(int compactEncoding) => _compactEncodingValue(
      compactEncoding,
      _typeNameCompactEncodings,
      'type name',
    );

String decodePackageMetaString(List<int> bytes, int encoding) =>
    decodeMetaString(
      bytes,
      encoding,
      specialChar1: '.',
      specialChar2: '_',
    );

String decodeTypeNameMetaString(List<int> bytes, int encoding) =>
    decodeMetaString(
      bytes,
      encoding,
      specialChar1: r'$',
      specialChar2: '_',
    );

String decodePackageName(List<int> bytes, int compactEncoding) =>
    decodePackageMetaString(
      bytes,
      _compactEncodingValue(
        compactEncoding,
        _packageNameCompactEncodings,
        'package',
      ),
    );

String decodeTypeName(List<int> bytes, int compactEncoding) =>
    decodeTypeNameMetaString(
      bytes,
      _compactEncodingValue(
        compactEncoding,
        _typeNameCompactEncodings,
        'type name',
      ),
    );

String decodeFieldName(List<int> bytes, int compactEncoding) =>
    decodeMetaString(
      bytes,
      _compactEncodingValue(
        compactEncoding,
        _fieldNameCompactEncodings,
        'field name',
      ),
      specialChar1: r'$',
      specialChar2: '_',
    );

String decodeMetaString(
  List<int> bytes,
  int encoding, {
  required String specialChar1,
  required String specialChar2,
}) {
  if (bytes.isEmpty) {
    return '';
  }
  switch (encoding) {
    case metaStringUtf8Encoding:
      return utf8.decode(bytes);
    case metaStringLowerSpecialEncoding:
      return _decodeLowerSpecial(bytes);
    case metaStringLowerUpperDigitSpecialEncoding:
      return _decodeLowerUpperDigitSpecial(
        bytes,
        specialChar1: specialChar1,
        specialChar2: specialChar2,
      );
    case metaStringFirstToLowerSpecialEncoding:
      final value = _decodeLowerSpecial(bytes);
      if (value.isEmpty) {
        return value;
      }
      return '${value[0].toUpperCase()}${value.substring(1)}';
    case metaStringAllToLowerSpecialEncoding:
      return _decodeAllToLowerSpecial(bytes);
    default:
      throw StateError('Unsupported meta-string encoding $encoding.');
  }
}

int _packLittleEndian32(List<int> bytes, int offset) {
  if (offset >= bytes.length) {
    return 0;
  }
  final end = offset + 4;
  var value = 0;
  for (var index = offset; index < bytes.length && index < end; index += 1) {
    value |= (bytes[index] & 0xff) << ((index - offset) * 8);
  }
  return value;
}

EncodedMetaString _encodeMetaString(
  String value, {
  required String specialChar1,
  required String specialChar2,
  required List<int> allowedEncodings,
}) {
  if (value.isEmpty) {
    return EncodedMetaString.empty;
  }
  for (final codeUnit in value.codeUnits) {
    if (codeUnit > 0x7f) {
      return EncodedMetaString(
        Uint8List.fromList(utf8.encode(value)),
        metaStringUtf8Encoding,
      );
    }
  }
  final encoding = _chooseEncoding(
    value,
    specialChar1: specialChar1,
    specialChar2: specialChar2,
    allowedEncodings: allowedEncodings,
  );
  switch (encoding) {
    case metaStringLowerSpecialEncoding:
      return EncodedMetaString(
        _encodeLowerSpecial(value.codeUnits),
        encoding,
      );
    case metaStringLowerUpperDigitSpecialEncoding:
      return EncodedMetaString(
        _encodeLowerUpperDigitSpecial(
          value.codeUnits,
          specialChar1: specialChar1,
          specialChar2: specialChar2,
        ),
        encoding,
      );
    case metaStringFirstToLowerSpecialEncoding:
      final codeUnits = value.codeUnits.toList(growable: false);
      codeUnits[0] = _toLowerAscii(codeUnits.first);
      return EncodedMetaString(
        _encodeLowerSpecial(codeUnits),
        encoding,
      );
    case metaStringAllToLowerSpecialEncoding:
      return EncodedMetaString(
        _encodeAllToLowerSpecial(value.codeUnits),
        encoding,
      );
    case metaStringUtf8Encoding:
      return EncodedMetaString(
        Uint8List.fromList(utf8.encode(value)),
        encoding,
      );
    default:
      throw StateError('Unsupported meta-string encoding $encoding.');
  }
}

int _chooseEncoding(
  String value, {
  required String specialChar1,
  required String specialChar2,
  required List<int> allowedEncodings,
}) {
  var canLowerUpperDigitSpecial = true;
  var canLowerSpecial = true;
  var digitCount = 0;
  var upperCount = 0;
  final codeUnits = value.codeUnits;
  for (final codeUnit in codeUnits) {
    if (canLowerUpperDigitSpecial &&
        !_isLowerUpperDigitSpecialChar(
          codeUnit,
          specialChar1: specialChar1.codeUnitAt(0),
          specialChar2: specialChar2.codeUnitAt(0),
        )) {
      canLowerUpperDigitSpecial = false;
    }
    if (canLowerSpecial && !_isLowerSpecialChar(codeUnit)) {
      canLowerSpecial = false;
    }
    if (_isAsciiDigit(codeUnit)) {
      digitCount += 1;
    }
    if (_isUpperAscii(codeUnit)) {
      upperCount += 1;
    }
  }
  if (canLowerSpecial &&
      allowedEncodings.contains(metaStringLowerSpecialEncoding)) {
    return metaStringLowerSpecialEncoding;
  }
  if (canLowerUpperDigitSpecial) {
    if (digitCount != 0 &&
        allowedEncodings.contains(metaStringLowerUpperDigitSpecialEncoding)) {
      return metaStringLowerUpperDigitSpecialEncoding;
    }
    if (upperCount == 1 &&
        _isUpperAscii(codeUnits.first) &&
        allowedEncodings.contains(metaStringFirstToLowerSpecialEncoding)) {
      return metaStringFirstToLowerSpecialEncoding;
    }
    if ((codeUnits.length + upperCount) * 5 < codeUnits.length * 6 &&
        allowedEncodings.contains(metaStringAllToLowerSpecialEncoding)) {
      return metaStringAllToLowerSpecialEncoding;
    }
    if (allowedEncodings.contains(metaStringLowerUpperDigitSpecialEncoding)) {
      return metaStringLowerUpperDigitSpecialEncoding;
    }
  }
  return metaStringUtf8Encoding;
}

Uint8List _encodeLowerSpecial(List<int> codeUnits) =>
    _encodeGeneric(codeUnits, 5, _encodeLowerSpecialChar);

Uint8List _encodeLowerUpperDigitSpecial(
  List<int> codeUnits, {
  required String specialChar1,
  required String specialChar2,
}) {
  return _encodeGeneric(
    codeUnits,
    6,
    (codeUnit) => _encodeLowerUpperDigitSpecialChar(
      codeUnit,
      specialChar1: specialChar1.codeUnitAt(0),
      specialChar2: specialChar2.codeUnitAt(0),
    ),
  );
}

Uint8List _encodeAllToLowerSpecial(List<int> codeUnits) {
  final escaped = <int>[];
  for (final codeUnit in codeUnits) {
    if (_isUpperAscii(codeUnit)) {
      escaped.add('|'.codeUnitAt(0));
      escaped.add(_toLowerAscii(codeUnit));
    } else {
      escaped.add(codeUnit);
    }
  }
  return _encodeLowerSpecial(escaped);
}

Uint8List _encodeGeneric(
  List<int> codeUnits,
  int bitsPerChar,
  int Function(int codeUnit) encodeChar,
) {
  final totalBits = codeUnits.length * bitsPerChar + 1;
  final byteLength = (totalBits + 7) >> 3;
  final bytes = Uint8List(byteLength);
  var byteIndex = 0;
  var bitIndex = 1;
  var charIndex = 0;
  var charBitRemain = bitsPerChar;
  while (charIndex < codeUnits.length) {
    final charValue = encodeChar(codeUnits[charIndex]);
    final nowByteRemain = 8 - bitIndex;
    if (nowByteRemain >= charBitRemain) {
      final mask = (1 << charBitRemain) - 1;
      bytes[byteIndex] |= (charValue & mask) << (nowByteRemain - charBitRemain);
      bitIndex += charBitRemain;
      if (bitIndex == 8) {
        byteIndex += 1;
        bitIndex = 0;
      }
      charIndex += 1;
      charBitRemain = bitsPerChar;
      continue;
    }
    final mask = (1 << nowByteRemain) - 1;
    bytes[byteIndex] |= (charValue >> (charBitRemain - nowByteRemain)) & mask;
    byteIndex += 1;
    bitIndex = 0;
    charBitRemain -= nowByteRemain;
  }
  final stripLastChar = bytes.length * 8 >= totalBits + bitsPerChar;
  if (stripLastChar) {
    bytes[0] |= 0x80;
  }
  return bytes;
}

String _decodeLowerSpecial(List<int> bytes) {
  final buffer = StringBuffer();
  final totalBits = bytes.length * 8;
  final stripLastChar = (bytes[0] & 0x80) != 0;
  var bitIndex = 1;
  while (bitIndex + 5 <= totalBits &&
      !(stripLastChar && (bitIndex + 10 > totalBits))) {
    final value = _readPackedValue(bytes, bitIndex, 5);
    bitIndex += 5;
    buffer.writeCharCode(_decodeLowerSpecialChar(value));
  }
  return buffer.toString();
}

String _decodeLowerUpperDigitSpecial(
  List<int> bytes, {
  required String specialChar1,
  required String specialChar2,
}) {
  final buffer = StringBuffer();
  final totalBits = bytes.length * 8;
  final stripLastChar = (bytes[0] & 0x80) != 0;
  var bitIndex = 1;
  while (bitIndex + 6 <= totalBits &&
      !(stripLastChar && (bitIndex + 12 > totalBits))) {
    final value = _readPackedValue(bytes, bitIndex, 6);
    bitIndex += 6;
    buffer.writeCharCode(
      _decodeLowerUpperDigitSpecialChar(
        value,
        specialChar1: specialChar1,
        specialChar2: specialChar2,
      ),
    );
  }
  return buffer.toString();
}

String _decodeAllToLowerSpecial(List<int> bytes) {
  final decoded = _decodeLowerSpecial(bytes);
  final buffer = StringBuffer();
  for (var index = 0; index < decoded.length; index += 1) {
    final character = decoded[index];
    if (character == '|' && index + 1 < decoded.length) {
      index += 1;
      buffer.write(decoded[index].toUpperCase());
      continue;
    }
    buffer.write(character);
  }
  return buffer.toString();
}

int _readPackedValue(List<int> bytes, int bitIndex, int width) {
  var value = 0;
  for (var offset = 0; offset < width; offset += 1) {
    final absoluteBit = bitIndex + offset;
    final byteIndex = absoluteBit ~/ 8;
    final intraByteIndex = 7 - (absoluteBit % 8);
    value = (value << 1) | ((bytes[byteIndex] >> intraByteIndex) & 1);
  }
  return value;
}

int _encodeLowerSpecialChar(int codeUnit) {
  if (codeUnit >= 0x61 && codeUnit <= 0x7a) {
    return codeUnit - 0x61;
  }
  switch (codeUnit) {
    case 0x2e:
      return 26;
    case 0x5f:
      return 27;
    case 0x24:
      return 28;
    case 0x7c:
      return 29;
    default:
      throw StateError(
        'Unsupported character for LOWER_SPECIAL encoding: ${String.fromCharCode(codeUnit)}.',
      );
  }
}

int _decodeLowerSpecialChar(int value) {
  if (value >= 0 && value <= 25) {
    return 'a'.codeUnitAt(0) + value;
  }
  switch (value) {
    case 26:
      return '.'.codeUnitAt(0);
    case 27:
      return '_'.codeUnitAt(0);
    case 28:
      return r'$'.codeUnitAt(0);
    case 29:
      return '|'.codeUnitAt(0);
    default:
      throw StateError('Invalid LOWER_SPECIAL value $value.');
  }
}

int _encodeLowerUpperDigitSpecialChar(
  int codeUnit, {
  required int specialChar1,
  required int specialChar2,
}) {
  if (codeUnit >= 0x61 && codeUnit <= 0x7a) {
    return codeUnit - 0x61;
  }
  if (codeUnit >= 0x41 && codeUnit <= 0x5a) {
    return 26 + (codeUnit - 0x41);
  }
  if (codeUnit >= 0x30 && codeUnit <= 0x39) {
    return 52 + (codeUnit - 0x30);
  }
  if (codeUnit == specialChar1) {
    return 62;
  }
  if (codeUnit == specialChar2) {
    return 63;
  }
  throw StateError(
    'Unsupported character for LOWER_UPPER_DIGIT_SPECIAL encoding: ${String.fromCharCode(codeUnit)}.',
  );
}

int _decodeLowerUpperDigitSpecialChar(
  int value, {
  required String specialChar1,
  required String specialChar2,
}) {
  if (value >= 0 && value <= 25) {
    return 'a'.codeUnitAt(0) + value;
  }
  if (value >= 26 && value <= 51) {
    return 'A'.codeUnitAt(0) + (value - 26);
  }
  if (value >= 52 && value <= 61) {
    return '0'.codeUnitAt(0) + (value - 52);
  }
  if (value == 62) {
    return specialChar1.codeUnitAt(0);
  }
  if (value == 63) {
    return specialChar2.codeUnitAt(0);
  }
  throw StateError('Invalid LOWER_UPPER_DIGIT_SPECIAL value $value.');
}

int _compactEncodingIndex(
  int encoding,
  List<int> encodings,
  String label,
) {
  final index = encodings.indexOf(encoding);
  if (index == -1) {
    throw StateError('Unsupported $label encoding $encoding.');
  }
  return index;
}

int _compactEncodingValue(
  int compactEncoding,
  List<int> encodings,
  String label,
) {
  if (compactEncoding < 0 || compactEncoding >= encodings.length) {
    throw StateError('Unsupported $label compact encoding $compactEncoding.');
  }
  return encodings[compactEncoding];
}

bool _isLowerUpperDigitSpecialChar(
  int codeUnit, {
  required int specialChar1,
  required int specialChar2,
}) {
  return (codeUnit >= 0x61 && codeUnit <= 0x7a) ||
      (codeUnit >= 0x41 && codeUnit <= 0x5a) ||
      (codeUnit >= 0x30 && codeUnit <= 0x39) ||
      codeUnit == specialChar1 ||
      codeUnit == specialChar2;
}

bool _isLowerSpecialChar(int codeUnit) {
  return (codeUnit >= 0x61 && codeUnit <= 0x7a) ||
      codeUnit == 0x2e ||
      codeUnit == 0x5f ||
      codeUnit == 0x24 ||
      codeUnit == 0x7c;
}

bool _isUpperAscii(int codeUnit) => codeUnit >= 0x41 && codeUnit <= 0x5a;

bool _isAsciiDigit(int codeUnit) => codeUnit >= 0x30 && codeUnit <= 0x39;

int _toLowerAscii(int codeUnit) =>
    _isUpperAscii(codeUnit) ? codeUnit + 0x20 : codeUnit;

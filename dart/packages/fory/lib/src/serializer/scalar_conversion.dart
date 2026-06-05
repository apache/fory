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

import 'package:fory/src/context/read_context.dart';
import 'package:fory/src/context/ref_writer.dart';
import 'package:fory/src/exceptions.dart';
import 'package:fory/src/meta/field_info.dart';
import 'package:fory/src/meta/field_type.dart';
import 'package:fory/src/meta/type_ids.dart';
import 'package:fory/src/serializer/primitive_serializers.dart';
import 'package:fory/src/serializer/scalar_serializers.dart';
import 'package:fory/src/serializer/serializer_support.dart';
import 'package:fory/src/types/bfloat16.dart';
import 'package:fory/src/types/decimal.dart';
import 'package:fory/src/types/float16.dart';
import 'package:fory/src/types/float32.dart';
import 'package:fory/src/types/int64.dart';
import 'package:fory/src/types/uint64.dart';

final BigInt _int8Min = BigInt.from(-128);
final BigInt _int8Max = BigInt.from(127);
final BigInt _int16Min = BigInt.from(-32768);
final BigInt _int16Max = BigInt.from(32767);
final BigInt _int32Min = BigInt.from(-2147483648);
final BigInt _int32Max = BigInt.from(2147483647);
final BigInt _int64Min = -(BigInt.one << 63);
final BigInt _int64Max = (BigInt.one << 63) - BigInt.one;
final BigInt _uint8Max = BigInt.from(255);
final BigInt _uint16Max = BigInt.from(65535);
final BigInt _uint32Max = BigInt.from(4294967295);
final BigInt _uint64Max = (BigInt.one << 64) - BigInt.one;
final BigInt _doubleSignMask = BigInt.one << 63;
final BigInt _doubleFractionMask = (BigInt.one << 52) - BigInt.one;
final BigInt _ten = BigInt.from(10);
const int _maxCompatibleDecimalDigits = 256;
const int _maxCompatibleNumericTextLength = 320;
final BigInt _maxCompatibleDecimalMagnitude = BigInt.from(
  10,
).pow(_maxCompatibleDecimalDigits);
final ByteData _floatData = ByteData(8);

final class CompatibleScalarConversion {
  final FieldInfo remoteField;
  final FieldInfo localField;

  const CompatibleScalarConversion(this.remoteField, this.localField);
}

CompatibleScalarConversion? compatibleScalarConversion(
  FieldInfo remoteField,
  FieldInfo localField,
) {
  if (remoteField.fieldType.ref || localField.fieldType.ref) {
    return null;
  }
  final exactScalarFieldType =
      remoteField.fieldType.typeId == localField.fieldType.typeId &&
      remoteField.fieldType.nullable == localField.fieldType.nullable &&
      remoteField.fieldType.ref == localField.fieldType.ref;
  if (exactScalarFieldType) {
    return null;
  }
  if (!_supportsCompatibleScalarConversion(
    remoteField.fieldType.typeId,
    localField.fieldType.typeId,
  )) {
    return null;
  }
  return CompatibleScalarConversion(remoteField, localField);
}

bool _supportsCompatibleScalarConversion(int remoteTypeId, int localTypeId) {
  if (remoteTypeId == localTypeId) {
    return _isScalarConversionType(remoteTypeId);
  }
  if (!_isScalarConversionType(remoteTypeId) ||
      !_isScalarConversionType(localTypeId)) {
    return false;
  }
  if (remoteTypeId == TypeIds.boolType) {
    return localTypeId == TypeIds.string || _isNumericType(localTypeId);
  }
  if (localTypeId == TypeIds.boolType) {
    return remoteTypeId == TypeIds.string || _isNumericType(remoteTypeId);
  }
  if (remoteTypeId == TypeIds.string) {
    return _isNumericType(localTypeId);
  }
  if (localTypeId == TypeIds.string) {
    return _isNumericType(remoteTypeId);
  }
  return _isNumericType(remoteTypeId) && _isNumericType(localTypeId);
}

bool _isScalarConversionType(int typeId) =>
    typeId == TypeIds.boolType ||
    typeId == TypeIds.string ||
    _isNumericType(typeId);

bool isCompatibleScalarType(int typeId) => _isScalarConversionType(typeId);

bool _isNumericType(int typeId) =>
    _isIntegerType(typeId) ||
    _isFloatingType(typeId) ||
    typeId == TypeIds.decimal;

bool _isIntegerType(int typeId) =>
    typeId == TypeIds.int8 ||
    typeId == TypeIds.int16 ||
    typeId == TypeIds.int32 ||
    typeId == TypeIds.varInt32 ||
    typeId == TypeIds.int64 ||
    typeId == TypeIds.varInt64 ||
    typeId == TypeIds.taggedInt64 ||
    typeId == TypeIds.uint8 ||
    typeId == TypeIds.uint16 ||
    typeId == TypeIds.uint32 ||
    typeId == TypeIds.varUint32 ||
    typeId == TypeIds.uint64 ||
    typeId == TypeIds.varUint64 ||
    typeId == TypeIds.taggedUint64;

bool _isFloatingType(int typeId) =>
    typeId == TypeIds.float16 ||
    typeId == TypeIds.bfloat16 ||
    typeId == TypeIds.float32 ||
    typeId == TypeIds.float64;

@pragma('vm:never-inline')
Object? readCompatibleScalarField(
  ReadContext context,
  CompatibleScalarConversion conversion,
) {
  final value = _readCompatibleScalarPayload(context, conversion);
  if (value == null) {
    return null;
  }
  try {
    return convertCompatibleScalarValue(value, conversion);
  } on InvalidDataException {
    rethrow;
  } catch (error) {
    _throwScalarConversionError(conversion, value, error);
  }
}

Object? _readCompatibleScalarPayload(
  ReadContext context,
  CompatibleScalarConversion conversion,
) {
  final fieldType = conversion.remoteField.fieldType;
  if (fieldType.nullable) {
    final flag = context.buffer.readByte();
    if (flag == RefWriter.nullFlag) {
      return null;
    }
    if (flag != RefWriter.notNullValueFlag) {
      throw InvalidDataException(
        'Invalid nullable compatible scalar field flag $flag.',
      );
    }
  }
  return _readCompatibleScalarValue(context, fieldType);
}

Object _readCompatibleScalarValue(ReadContext context, FieldType fieldType) {
  switch (fieldType.typeId) {
    case TypeIds.boolType:
      final raw = context.buffer.readUint8();
      if (raw == 0) {
        return false;
      }
      if (raw == 1) {
        return true;
      }
      throw InvalidDataException('Bool payload must be encoded as 0 or 1.');
    case TypeIds.string:
      return StringSerializer.readPayload(context);
    case TypeIds.decimal:
      return DecimalSerializer.readPayload(context);
    default:
      if (fieldType.isPrimitive) {
        return convertPrimitiveFieldValue(
          PrimitiveSerializer.readPayload(context, fieldType.typeId),
          fieldType,
        );
      }
      throw InvalidDataException(
        'Unsupported compatible scalar type ${fieldType.typeId}.',
      );
  }
}

@pragma('vm:never-inline')
Object convertCompatibleScalarValue(
  Object value,
  CompatibleScalarConversion conversion,
) {
  final localType = conversion.localField.fieldType;
  final remoteTypeId = conversion.remoteField.fieldType.typeId;
  final localTypeId = localType.typeId;
  if (remoteTypeId == localTypeId) {
    return convertPrimitiveFieldValue(value, localType);
  }
  if (localTypeId == TypeIds.boolType) {
    return _toBool(value);
  }
  if (localTypeId == TypeIds.string) {
    return _toString(value);
  }
  if (_isIntegerType(localTypeId)) {
    return _toIntegerTarget(value, localType);
  }
  if (_isFloatingType(localTypeId)) {
    return _toFloatingTarget(value, localType);
  }
  if (localTypeId == TypeIds.decimal) {
    return _toDecimal(value);
  }
  throw InvalidDataException(
    'Unsupported compatible scalar target $localTypeId.',
  );
}

bool _toBool(Object value) {
  if (value is bool) {
    return value;
  }
  if (value is String) {
    return switch (value) {
      '0' || 'false' => false,
      '1' || 'true' => true,
      _ => throw const FormatException('String is not a bool literal.'),
    };
  }
  final decimal = _numericDecimal(value);
  if (decimal.unscaled == BigInt.zero) {
    return false;
  }
  if (decimal.unscaled == BigInt.one && decimal.scale == 0) {
    return true;
  }
  throw const FormatException('Numeric bool value must be 0 or 1.');
}

String _toString(Object value) {
  if (value is bool) {
    return value ? 'true' : 'false';
  }
  if (value is Decimal) {
    return _canonicalDecimalText(_canonicalDecimal(value));
  }
  if (value is Float32) {
    return _exactFloatText(value.value);
  }
  if (value is double) {
    return _exactFloatText(value);
  }
  return _integerValue(value).toString();
}

Object _toIntegerTarget(Object value, FieldType localType) {
  final decimal = _numericDecimal(value);
  if (decimal.scale != 0) {
    throw const FormatException('Numeric value is not integral.');
  }
  final integer = decimal.unscaled;
  _checkIntegerRange(integer, localType.typeId);
  return _integerCarrier(integer, localType);
}

Object _toFloatingTarget(Object value, FieldType localType) {
  final localTypeId = localType.typeId;
  if (value is String && _isNegativeZeroLiteral(value)) {
    _parseDecimalLiteral(value);
    return _floatCarrier(_roundFloat(-0.0, localTypeId), localType);
  }
  if (value is double || value is Float32) {
    final source = value is Float32 ? value.value : value as double;
    if (source.isNaN) {
      throw const FormatException('NaN is not convertible.');
    }
    final rounded = _roundFloat(source, localTypeId);
    if (!_sameFloatValue(source, rounded)) {
      throw const FormatException('Floating value is not lossless.');
    }
    return _floatCarrier(rounded, localType);
  }
  final decimal = _numericDecimal(value);
  if (decimal.unscaled == BigInt.zero) {
    return _floatCarrier(0.0, localType);
  }
  final parsed = _decimalToDouble(decimal);
  final rounded = _roundFloat(parsed, localTypeId);
  if (_canonicalDecimalFromFiniteFloat(rounded) != decimal) {
    throw const FormatException('Decimal value is not representable.');
  }
  return _floatCarrier(rounded, localType);
}

Decimal _toDecimal(Object value) {
  if (value is bool) {
    return Decimal(value ? BigInt.one : BigInt.zero, 0);
  }
  if (value is Decimal) {
    return _canonicalDecimal(value).toDecimal();
  }
  if (value is String) {
    return _parseDecimalLiteral(value).toDecimal();
  }
  if (value is double || value is Float32) {
    final source = value is Float32 ? value.value : value as double;
    if (!source.isFinite) {
      throw const FormatException('Non-finite float is not decimal.');
    }
    if (source == 0.0) {
      return Decimal(BigInt.zero, 0);
    }
    return _canonicalDecimalFromFiniteFloat(source).toDecimal();
  }
  return Decimal(_integerValue(value), 0);
}

_DecimalValue _numericDecimal(Object value) {
  if (value is bool) {
    return _DecimalValue(value ? BigInt.one : BigInt.zero, 0);
  }
  if (value is String) {
    return _parseDecimalLiteral(value);
  }
  if (value is Decimal) {
    return _canonicalDecimal(value);
  }
  if (value is double || value is Float32) {
    final source = value is Float32 ? value.value : value as double;
    if (!source.isFinite) {
      throw const FormatException('Non-finite float is not numeric decimal.');
    }
    if (source == 0.0) {
      return _DecimalValue(BigInt.zero, 0);
    }
    return _canonicalDecimalFromFiniteFloat(source);
  }
  return _DecimalValue(_integerValue(value), 0);
}

BigInt _integerValue(Object value) {
  if (value is Uint64) {
    return value.toBigInt();
  }
  if (value is Int64) {
    return value.toBigInt();
  }
  if (value is int) {
    return BigInt.from(value);
  }
  throw StateError(
    'Expected integer-compatible value, got ${value.runtimeType}.',
  );
}

Object _integerCarrier(BigInt value, FieldType localType) {
  switch (localType.typeId) {
    case TypeIds.int64:
    case TypeIds.varInt64:
    case TypeIds.taggedInt64:
      if (localType.type == int) {
        return Int64.fromBigInt(value).toInt();
      }
      return Int64.fromBigInt(value);
    case TypeIds.uint64:
    case TypeIds.varUint64:
    case TypeIds.taggedUint64:
      if (localType.type == int) {
        return Uint64.fromBigInt(value).toInt();
      }
      return Uint64.fromBigInt(value);
    default:
      return value.toInt();
  }
}

void _checkIntegerRange(BigInt value, int typeId) {
  final min = _integerMin(typeId);
  final max = _integerMax(typeId);
  if (value < min || value > max) {
    throw FormatException('Integer value $value is outside [$min, $max].');
  }
}

BigInt _integerMin(int typeId) {
  return switch (typeId) {
    TypeIds.int8 => _int8Min,
    TypeIds.int16 => _int16Min,
    TypeIds.int32 || TypeIds.varInt32 => _int32Min,
    TypeIds.int64 || TypeIds.varInt64 || TypeIds.taggedInt64 => _int64Min,
    TypeIds.uint8 ||
    TypeIds.uint16 ||
    TypeIds.uint32 ||
    TypeIds.varUint32 ||
    TypeIds.uint64 ||
    TypeIds.varUint64 ||
    TypeIds.taggedUint64 => BigInt.zero,
    _ => throw StateError('Unsupported integer type $typeId.'),
  };
}

BigInt _integerMax(int typeId) {
  return switch (typeId) {
    TypeIds.int8 => _int8Max,
    TypeIds.int16 => _int16Max,
    TypeIds.int32 || TypeIds.varInt32 => _int32Max,
    TypeIds.int64 || TypeIds.varInt64 || TypeIds.taggedInt64 => _int64Max,
    TypeIds.uint8 => _uint8Max,
    TypeIds.uint16 => _uint16Max,
    TypeIds.uint32 || TypeIds.varUint32 => _uint32Max,
    TypeIds.uint64 || TypeIds.varUint64 || TypeIds.taggedUint64 => _uint64Max,
    _ => throw StateError('Unsupported integer type $typeId.'),
  };
}

Object _floatCarrier(double value, FieldType localType) {
  if (localType.typeId == TypeIds.float32) {
    if (localType.type == double) {
      return Float32(value).value;
    }
    return Float32(value);
  }
  return value;
}

double _roundFloat(double value, int typeId) {
  return switch (typeId) {
    TypeIds.float16 => fromFloat16Bits(toFloat16Bits(value)),
    TypeIds.bfloat16 => fromBfloat16Bits(toBfloat16Bits(value)),
    TypeIds.float32 => Float32(value).value,
    TypeIds.float64 => value,
    _ => throw StateError('Unsupported floating type $typeId.'),
  };
}

bool _sameFloatValue(double left, double right) {
  if (left.isNaN || right.isNaN) {
    return false;
  }
  if (left == 0.0 && right == 0.0) {
    return left.isNegative == right.isNegative;
  }
  return left == right;
}

double _decimalToDouble(_DecimalValue value) {
  final decimal = _canonicalDecimalValue(value.unscaled, value.scale);
  final sign = decimal.unscaled.isNegative ? '-' : '';
  final magnitude = decimal.unscaled.abs().toString();
  final text =
      decimal.scale == 0
          ? '$sign$magnitude'
          : '$sign${magnitude}e-${decimal.scale}';
  final parsed = double.parse(text);
  if (!parsed.isFinite) {
    throw const FormatException('Decimal is outside floating range.');
  }
  return parsed;
}

_DecimalValue _parseDecimalLiteral(String value) {
  if (value.isEmpty || value.length > _maxCompatibleNumericTextLength) {
    throw const FormatException('Invalid compatible numeric literal.');
  }

  var index = 0;
  final negative = value.codeUnitAt(index) == 45;
  if (negative) {
    index += 1;
    if (index == value.length) {
      throw const FormatException('Invalid compatible numeric literal.');
    }
  }

  final integerStart = index;
  var significantDigits = 0;
  var seenNonZero = false;
  void countDigit(int digit) {
    if (digit != 48 || seenNonZero) {
      seenNonZero = true;
      significantDigits += 1;
    }
  }

  var digit = value.codeUnitAt(index);
  if (digit == 48) {
    countDigit(digit);
    index += 1;
    if (index < value.length && _isDigit(value.codeUnitAt(index))) {
      throw const FormatException('Invalid compatible numeric literal.');
    }
  } else if (digit >= 49 && digit <= 57) {
    while (index < value.length && _isDigit(value.codeUnitAt(index))) {
      countDigit(value.codeUnitAt(index));
      index += 1;
    }
  } else {
    throw const FormatException('Invalid compatible numeric literal.');
  }
  final integerEnd = index;

  var fractionStart = index;
  var fractionEnd = index;
  if (index < value.length && value.codeUnitAt(index) == 46) {
    index += 1;
    fractionStart = index;
    while (index < value.length && _isDigit(value.codeUnitAt(index))) {
      countDigit(value.codeUnitAt(index));
      index += 1;
    }
    if (index == fractionStart) {
      throw const FormatException('Invalid compatible numeric literal.');
    }
    fractionEnd = index;
  }

  var exponent = 0;
  if (index < value.length) {
    final marker = value.codeUnitAt(index);
    if (marker == 101 || marker == 69) {
      index += 1;
      var exponentNegative = false;
      if (index < value.length && value.codeUnitAt(index) == 45) {
        exponentNegative = true;
        index += 1;
      }
      if (index == value.length) {
        throw const FormatException('Invalid compatible numeric literal.');
      }
      digit = value.codeUnitAt(index);
      if (digit == 48) {
        index += 1;
        if (index < value.length && _isDigit(value.codeUnitAt(index))) {
          throw const FormatException('Invalid compatible numeric literal.');
        }
      } else if (digit >= 49 && digit <= 57) {
        while (index < value.length && _isDigit(value.codeUnitAt(index))) {
          exponent = exponent * 10 + value.codeUnitAt(index) - 48;
          if (exponent > _maxCompatibleDecimalDigits) {
            throw const FormatException('Compatible exponent is too large.');
          }
          index += 1;
        }
      } else {
        throw const FormatException('Invalid compatible numeric literal.');
      }
      if (exponentNegative) {
        exponent = -exponent;
      }
    }
  }

  if (index != value.length ||
      significantDigits > _maxCompatibleDecimalDigits) {
    throw const FormatException('Compatible numeric literal is too large.');
  }

  final scale = fractionEnd - fractionStart - exponent;
  if (!_decimalShapeFits(significantDigits, scale)) {
    throw const FormatException('Compatible numeric literal is too large.');
  }

  var unscaled = BigInt.parse(
    '${value.substring(integerStart, integerEnd)}'
    '${value.substring(fractionStart, fractionEnd)}',
  );
  if (unscaled == BigInt.zero) {
    return _DecimalValue(BigInt.zero, 0);
  }
  if (negative) {
    unscaled = -unscaled;
  }
  return _canonicalDecimalValue(unscaled, scale);
}

bool _decimalShapeFits(int significantDigits, int scale) {
  if (scale > _maxCompatibleDecimalDigits) {
    return false;
  }
  return scale >= 0 ||
      significantDigits + (-scale) <= _maxCompatibleDecimalDigits;
}

bool _isDigit(int codeUnit) => codeUnit >= 48 && codeUnit <= 57;

bool _isNegativeZeroLiteral(String value) {
  if (!value.startsWith('-')) {
    return false;
  }
  final decimal = _parseDecimalLiteral(value);
  return decimal.unscaled == BigInt.zero;
}

_DecimalValue _canonicalDecimal(Decimal value) {
  return _canonicalDecimalValue(value.unscaledValue, value.scale);
}

_DecimalValue _canonicalDecimalValue(BigInt unscaled, int scale) {
  if (unscaled == BigInt.zero) {
    return _DecimalValue(BigInt.zero, 0);
  }
  var resultUnscaled = unscaled;
  var resultScale = scale;
  if (resultScale < 0) {
    final digitCount = _decimalDigitCount(resultUnscaled);
    if (digitCount - resultScale > _maxCompatibleDecimalDigits) {
      throw const FormatException('Compatible decimal magnitude is too large.');
    }
    resultUnscaled *= _ten.pow(-resultScale);
    resultScale = 0;
  }
  while (resultScale > 0 && resultUnscaled.remainder(_ten) == BigInt.zero) {
    resultUnscaled ~/= _ten;
    resultScale -= 1;
  }
  final digitCount = _decimalDigitCount(resultUnscaled);
  if (resultScale > _maxCompatibleDecimalDigits ||
      digitCount > _maxCompatibleDecimalDigits) {
    throw const FormatException('Compatible decimal is too large.');
  }
  return _DecimalValue(resultUnscaled, resultScale);
}

int _decimalDigitCount(BigInt value) {
  final magnitude = value.abs();
  if (magnitude >= _maxCompatibleDecimalMagnitude) {
    return _maxCompatibleDecimalDigits + 1;
  }
  return magnitude.toString().length;
}

String _canonicalDecimalText(_DecimalValue value) {
  final decimal = _canonicalDecimalValue(value.unscaled, value.scale);
  if (decimal.unscaled == BigInt.zero) {
    return '0';
  }
  final negative = decimal.unscaled.isNegative;
  final digits = decimal.unscaled.abs().toString();
  if (decimal.scale == 0) {
    return negative ? '-$digits' : digits;
  }
  final text =
      decimal.scale >= digits.length
          ? '0.${'0' * (decimal.scale - digits.length)}$digits'
          : '${digits.substring(0, digits.length - decimal.scale)}.'
              '${digits.substring(digits.length - decimal.scale)}';
  return negative ? '-$text' : text;
}

String _exactFloatText(double value) {
  if (!value.isFinite) {
    throw const FormatException('Non-finite float cannot convert to string.');
  }
  if (value == 0.0) {
    return value.isNegative ? '-0.0' : '0.0';
  }
  final decimal = _canonicalDecimalFromFiniteFloat(value);
  var text = _canonicalDecimalText(decimal);
  if (!text.contains('.')) {
    text = '$text.0';
  }
  return text;
}

_DecimalValue _canonicalDecimalFromFiniteFloat(double value) {
  final bits = _doubleBits(value);
  final negative = (bits & _doubleSignMask) != BigInt.zero;
  final exponentBits = ((bits >> 52) & BigInt.from(0x7ff)).toInt();
  final fraction = bits & _doubleFractionMask;
  if (exponentBits == 0x7ff) {
    throw const FormatException('Non-finite float.');
  }
  if (exponentBits == 0 && fraction == BigInt.zero) {
    return _DecimalValue(BigInt.zero, 0);
  }
  final significand =
      exponentBits == 0 ? fraction : (BigInt.one << 52) | fraction;
  final exponent = exponentBits == 0 ? -1074 : exponentBits - 1023 - 52;
  BigInt unscaled;
  int scale;
  if (exponent >= 0) {
    unscaled = significand << exponent;
    scale = 0;
  } else {
    scale = -exponent;
    if (scale > _maxCompatibleDecimalDigits) {
      throw const FormatException('Float decimal expansion is too large.');
    }
    unscaled = significand * BigInt.from(5).pow(scale);
  }
  if (negative) {
    unscaled = -unscaled;
  }
  return _canonicalDecimalValue(unscaled, scale);
}

BigInt _doubleBits(double value) {
  _floatData.setFloat64(0, value, Endian.little);
  final low = BigInt.from(_floatData.getUint32(0, Endian.little));
  final high = BigInt.from(_floatData.getUint32(4, Endian.little));
  return (high << 32) | low;
}

@pragma('vm:never-inline')
Never _throwScalarConversionError(
  CompatibleScalarConversion conversion,
  Object value,
  Object cause,
) {
  throw InvalidDataException(
    'Cannot convert compatible field ${conversion.localField.identifier} '
    'from type ${conversion.remoteField.fieldType.typeId} '
    'to type ${conversion.localField.fieldType.typeId}: $value',
    cause,
  );
}

final class _DecimalValue {
  final BigInt unscaled;
  final int scale;

  const _DecimalValue(this.unscaled, this.scale);

  Decimal toDecimal() => Decimal(unscaled, scale);

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is _DecimalValue &&
          other.unscaled == unscaled &&
          other.scale == scale;

  @override
  int get hashCode => Object.hash(unscaled, scale);
}

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
const int _int64SignHigh32 = 0x80000000;
const int _maxCompatibleDecimalDigits = 256;
const int _maxCompatibleNumericTextLength = 320;
final BigInt _maxCompatibleDecimalMagnitude = BigInt.from(
  10,
).pow(_maxCompatibleDecimalDigits);
final ByteData _floatData = ByteData(8);
const int _directTargetNone = 0;
const int _directTargetInt = 1;
const int _directTargetDouble = 2;
const int _directTargetInt64 = 3;
const int _directTargetUint64 = 4;
const int _directTargetFloat32 = 5;

final class CompatibleScalarConversion {
  final FieldInfo remoteField;
  final FieldInfo localField;

  const CompatibleScalarConversion(this.remoteField, this.localField);
}

final class CompatibleScalarReadDescriptor {
  final CompatibleScalarConversion conversion;
  final int directTargetKind;
  final int directSourceTypeId;
  final int directTargetTypeId;
  final bool sourceNullable;

  const CompatibleScalarReadDescriptor(
    this.conversion,
    this.directTargetKind,
    this.directSourceTypeId,
    this.directTargetTypeId,
    this.sourceNullable,
  );

  bool get readsDirectIntAsInt => directTargetKind == _directTargetInt;

  bool get readsDirectDoubleAsDouble => directTargetKind == _directTargetDouble;

  bool get readsDirectInt64 => directTargetKind == _directTargetInt64;

  bool get readsDirectUint64 => directTargetKind == _directTargetUint64;

  bool get readsDirectFloat32 => directTargetKind == _directTargetFloat32;
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

CompatibleScalarReadDescriptor compatibleScalarReadDescriptor(
  CompatibleScalarConversion conversion,
) {
  final localType = conversion.localField.fieldType;
  final remoteType = conversion.remoteField.fieldType;
  final directTargetKind = _directTargetKind(localType);
  final directSourceTypeId = switch (directTargetKind) {
    _directTargetInt ||
    _directTargetInt64 ||
    _directTargetUint64 => _directIntegerSourceTypeId(remoteType.typeId),
    _directTargetDouble ||
    _directTargetFloat32 => _directDoubleSourceTypeId(remoteType.typeId),
    _ => -1,
  };
  return CompatibleScalarReadDescriptor(
    conversion,
    directSourceTypeId >= 0 ? directTargetKind : _directTargetNone,
    directSourceTypeId,
    directSourceTypeId >= 0 ? localType.typeId : -1,
    remoteType.nullable,
  );
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

int _directTargetKind(FieldType localType) {
  if (localType.nullable || localType.ref || localType.dynamic == true) {
    return _directTargetNone;
  }
  if (localType.type == int && _isDirectIntTargetType(localType.typeId)) {
    return _directTargetInt;
  }
  if (localType.type == double && _isDirectDoubleTargetType(localType.typeId)) {
    return _directTargetDouble;
  }
  if (localType.type == Int64 && _isSigned64TargetType(localType.typeId)) {
    return _directTargetInt64;
  }
  if (localType.type == Uint64 && _isUnsigned64TargetType(localType.typeId)) {
    return _directTargetUint64;
  }
  if (localType.type == Float32 && localType.typeId == TypeIds.float32) {
    return _directTargetFloat32;
  }
  return _directTargetNone;
}

bool _isDirectIntTargetType(int typeId) =>
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
    typeId == TypeIds.varUint32;

bool _isDirectDoubleTargetType(int typeId) =>
    typeId == TypeIds.float32 || typeId == TypeIds.float64;

bool _isSigned64TargetType(int typeId) =>
    typeId == TypeIds.int64 ||
    typeId == TypeIds.varInt64 ||
    typeId == TypeIds.taggedInt64;

bool _isUnsigned64TargetType(int typeId) =>
    typeId == TypeIds.uint64 ||
    typeId == TypeIds.varUint64 ||
    typeId == TypeIds.taggedUint64;

int _directIntegerSourceTypeId(int remoteTypeId) {
  switch (remoteTypeId) {
    case TypeIds.boolType:
    case TypeIds.int8:
    case TypeIds.int16:
    case TypeIds.int32:
    case TypeIds.varInt32:
    case TypeIds.int64:
    case TypeIds.varInt64:
    case TypeIds.taggedInt64:
    case TypeIds.uint8:
    case TypeIds.uint16:
    case TypeIds.uint32:
    case TypeIds.varUint32:
    case TypeIds.uint64:
    case TypeIds.varUint64:
    case TypeIds.taggedUint64:
      return remoteTypeId;
    default:
      return -1;
  }
}

int _directDoubleSourceTypeId(int remoteTypeId) {
  switch (remoteTypeId) {
    case TypeIds.boolType:
    case TypeIds.int8:
    case TypeIds.int16:
    case TypeIds.int32:
    case TypeIds.varInt32:
    case TypeIds.uint8:
    case TypeIds.uint16:
    case TypeIds.uint32:
    case TypeIds.varUint32:
    case TypeIds.float16:
    case TypeIds.bfloat16:
    case TypeIds.float32:
    case TypeIds.float64:
      return remoteTypeId;
    default:
      return -1;
  }
}

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

@pragma('vm:prefer-inline')
int readCompatScalarPayloadAsInt(
  ReadContext context,
  CompatibleScalarReadDescriptor scalarRead, [
  int? fallback,
]) {
  if (!_readCompatibleScalarHeader(context, scalarRead.sourceNullable)) {
    if (fallback != null) {
      return fallback;
    }
    throw InvalidDataException(
      'Received null for non-nullable compatible scalar field.',
    );
  }
  final buffer = context.buffer;
  final value = switch (scalarRead.directSourceTypeId) {
    TypeIds.boolType => _readBoolIntPayload(buffer.readUint8()),
    TypeIds.int8 => buffer.readByte(),
    TypeIds.int16 => buffer.readInt16(),
    TypeIds.int32 => buffer.readInt32(),
    TypeIds.varInt32 => buffer.readVarInt32(),
    TypeIds.int64 => buffer.readInt64AsInt(),
    TypeIds.varInt64 => buffer.readVarInt64AsInt(),
    TypeIds.taggedInt64 => buffer.readTaggedInt64AsInt(),
    TypeIds.uint8 => buffer.readUint8(),
    TypeIds.uint16 => buffer.readUint16(),
    TypeIds.uint32 => buffer.readUint32(),
    TypeIds.varUint32 => buffer.readVarUint32(),
    TypeIds.uint64 => _uint64ToSignedInt64Target(buffer.readUint64()),
    TypeIds.varUint64 => _uint64ToSignedInt64Target(buffer.readVarUint64()),
    TypeIds.taggedUint64 => _uint64ToSignedInt64Target(
      buffer.readTaggedUint64(),
    ),
    _ =>
      throw StateError(
        'Unsupported int-compatible payload type ${scalarRead.directSourceTypeId}.',
      ),
  };
  _checkIntTargetRange(value, scalarRead.directTargetTypeId);
  return value;
}

@pragma('vm:prefer-inline')
double readCompatScalarPayloadAsDouble(
  ReadContext context,
  CompatibleScalarReadDescriptor scalarRead, [
  double? fallback,
]) {
  if (!_readCompatibleScalarHeader(context, scalarRead.sourceNullable)) {
    if (fallback != null) {
      return fallback;
    }
    throw InvalidDataException(
      'Received null for non-nullable compatible scalar field.',
    );
  }
  final buffer = context.buffer;
  final value = switch (scalarRead.directSourceTypeId) {
    TypeIds.boolType => _readBoolIntPayload(buffer.readUint8()).toDouble(),
    TypeIds.int8 => buffer.readByte().toDouble(),
    TypeIds.int16 => buffer.readInt16().toDouble(),
    TypeIds.int32 => buffer.readInt32().toDouble(),
    TypeIds.varInt32 => buffer.readVarInt32().toDouble(),
    TypeIds.uint8 => buffer.readUint8().toDouble(),
    TypeIds.uint16 => buffer.readUint16().toDouble(),
    TypeIds.uint32 => buffer.readUint32().toDouble(),
    TypeIds.varUint32 => buffer.readVarUint32().toDouble(),
    TypeIds.float16 => buffer.readFloat16(),
    TypeIds.bfloat16 => buffer.readBfloat16(),
    TypeIds.float32 => buffer.readFloat32(),
    TypeIds.float64 => buffer.readFloat64(),
    _ =>
      throw StateError(
        'Unsupported double-compatible payload type ${scalarRead.directSourceTypeId}.',
      ),
  };
  return _checkedDoubleTarget(value, scalarRead.directTargetTypeId);
}

@pragma('vm:prefer-inline')
Int64 readCompatScalarPayloadAsInt64(
  ReadContext context,
  CompatibleScalarReadDescriptor scalarRead, [
  Int64? fallback,
]) {
  if (!_readCompatibleScalarHeader(context, scalarRead.sourceNullable)) {
    if (fallback != null) {
      return fallback;
    }
    throw InvalidDataException(
      'Received null for non-nullable compatible scalar field.',
    );
  }
  final buffer = context.buffer;
  return switch (scalarRead.directSourceTypeId) {
    TypeIds.boolType => Int64(_readBoolIntPayload(buffer.readUint8())),
    TypeIds.int8 => Int64(buffer.readByte()),
    TypeIds.int16 => Int64(buffer.readInt16()),
    TypeIds.int32 => Int64(buffer.readInt32()),
    TypeIds.varInt32 => Int64(buffer.readVarInt32()),
    TypeIds.int64 => buffer.readInt64(),
    TypeIds.varInt64 => buffer.readVarInt64(),
    TypeIds.taggedInt64 => buffer.readTaggedInt64(),
    TypeIds.uint8 => Int64(buffer.readUint8()),
    TypeIds.uint16 => Int64(buffer.readUint16()),
    TypeIds.uint32 => Int64(buffer.readUint32()),
    TypeIds.varUint32 => Int64(buffer.readVarUint32()),
    TypeIds.uint64 => _uint64ToInt64Target(buffer.readUint64()),
    TypeIds.varUint64 => _uint64ToInt64Target(buffer.readVarUint64()),
    TypeIds.taggedUint64 => _uint64ToInt64Target(buffer.readTaggedUint64()),
    _ =>
      throw StateError(
        'Unsupported Int64-compatible payload type ${scalarRead.directSourceTypeId}.',
      ),
  };
}

@pragma('vm:prefer-inline')
Uint64 readCompatScalarPayloadAsUint64(
  ReadContext context,
  CompatibleScalarReadDescriptor scalarRead, [
  Uint64? fallback,
]) {
  if (!_readCompatibleScalarHeader(context, scalarRead.sourceNullable)) {
    if (fallback != null) {
      return fallback;
    }
    throw InvalidDataException(
      'Received null for non-nullable compatible scalar field.',
    );
  }
  final buffer = context.buffer;
  return switch (scalarRead.directSourceTypeId) {
    TypeIds.boolType => Uint64(_readBoolIntPayload(buffer.readUint8())),
    TypeIds.int8 => _signedIntToUint64Target(buffer.readByte()),
    TypeIds.int16 => _signedIntToUint64Target(buffer.readInt16()),
    TypeIds.int32 => _signedIntToUint64Target(buffer.readInt32()),
    TypeIds.varInt32 => _signedIntToUint64Target(buffer.readVarInt32()),
    TypeIds.int64 => _int64ToUint64Target(buffer.readInt64()),
    TypeIds.varInt64 => _int64ToUint64Target(buffer.readVarInt64()),
    TypeIds.taggedInt64 => _int64ToUint64Target(buffer.readTaggedInt64()),
    TypeIds.uint8 => Uint64(buffer.readUint8()),
    TypeIds.uint16 => Uint64(buffer.readUint16()),
    TypeIds.uint32 => Uint64(buffer.readUint32()),
    TypeIds.varUint32 => Uint64(buffer.readVarUint32()),
    TypeIds.uint64 => buffer.readUint64(),
    TypeIds.varUint64 => buffer.readVarUint64(),
    TypeIds.taggedUint64 => buffer.readTaggedUint64(),
    _ =>
      throw StateError(
        'Unsupported Uint64-compatible payload type ${scalarRead.directSourceTypeId}.',
      ),
  };
}

@pragma('vm:prefer-inline')
Float32 readCompatScalarPayloadAsFloat32(
  ReadContext context,
  CompatibleScalarReadDescriptor scalarRead, [
  Float32? fallback,
]) {
  if (!_readCompatibleScalarHeader(context, scalarRead.sourceNullable)) {
    if (fallback != null) {
      return fallback;
    }
    throw InvalidDataException(
      'Received null for non-nullable compatible scalar field.',
    );
  }
  final buffer = context.buffer;
  final value = switch (scalarRead.directSourceTypeId) {
    TypeIds.boolType => _readBoolIntPayload(buffer.readUint8()).toDouble(),
    TypeIds.int8 => buffer.readByte().toDouble(),
    TypeIds.int16 => buffer.readInt16().toDouble(),
    TypeIds.int32 => buffer.readInt32().toDouble(),
    TypeIds.varInt32 => buffer.readVarInt32().toDouble(),
    TypeIds.uint8 => buffer.readUint8().toDouble(),
    TypeIds.uint16 => buffer.readUint16().toDouble(),
    TypeIds.uint32 => buffer.readUint32().toDouble(),
    TypeIds.varUint32 => buffer.readVarUint32().toDouble(),
    TypeIds.float16 => buffer.readFloat16(),
    TypeIds.bfloat16 => buffer.readBfloat16(),
    TypeIds.float32 => buffer.readFloat32(),
    TypeIds.float64 => buffer.readFloat64(),
    _ =>
      throw StateError(
        'Unsupported Float32-compatible payload type ${scalarRead.directSourceTypeId}.',
      ),
  };
  return Float32(_checkedDoubleTarget(value, scalarRead.directTargetTypeId));
}

bool _readCompatibleScalarHeader(ReadContext context, bool nullable) {
  if (!nullable) {
    return true;
  }
  final flag = context.buffer.readByte();
  if (flag == RefWriter.nullFlag) {
    return false;
  }
  if (flag != RefWriter.notNullValueFlag) {
    throw InvalidDataException(
      'Invalid nullable compatible scalar field flag $flag.',
    );
  }
  return true;
}

int _readBoolIntPayload(int raw) {
  if (raw == 0) {
    return 0;
  }
  if (raw == 1) {
    return 1;
  }
  throw InvalidDataException('Bool payload must be encoded as 0 or 1.');
}

void _checkIntTargetRange(int value, int targetTypeId) {
  switch (targetTypeId) {
    case TypeIds.int8:
      if (value < -128 || value > 127) {
        throw InvalidDataException(
          'Integer value $value is outside int8 range.',
        );
      }
      return;
    case TypeIds.int16:
      if (value < -32768 || value > 32767) {
        throw InvalidDataException(
          'Integer value $value is outside int16 range.',
        );
      }
      return;
    case TypeIds.int32:
    case TypeIds.varInt32:
      if (value < -2147483648 || value > 2147483647) {
        throw InvalidDataException(
          'Integer value $value is outside int32 range.',
        );
      }
      return;
    case TypeIds.uint8:
      if (value < 0 || value > 255) {
        throw InvalidDataException(
          'Integer value $value is outside uint8 range.',
        );
      }
      return;
    case TypeIds.uint16:
      if (value < 0 || value > 65535) {
        throw InvalidDataException(
          'Integer value $value is outside uint16 range.',
        );
      }
      return;
    case TypeIds.uint32:
    case TypeIds.varUint32:
      if (value < 0 || value > 4294967295) {
        throw InvalidDataException(
          'Integer value $value is outside uint32 range.',
        );
      }
      return;
    case TypeIds.int64:
    case TypeIds.varInt64:
    case TypeIds.taggedInt64:
      return;
    default:
      throw StateError('Unsupported int-compatible target type $targetTypeId.');
  }
}

double _checkedDoubleTarget(double value, int targetTypeId) {
  if (value.isNaN) {
    throw const InvalidDataException('NaN is not convertible.');
  }
  switch (targetTypeId) {
    case TypeIds.float64:
      return value;
    case TypeIds.float32:
      final rounded = Float32(value).value;
      if (_sameFloatValue(value, rounded)) {
        return rounded;
      }
      throw const InvalidDataException(
        'Numeric value is not exactly representable by float32.',
      );
    default:
      throw StateError(
        'Unsupported double-compatible target type $targetTypeId.',
      );
  }
}

int _uint64ToSignedInt64Target(Uint64 value) {
  if (value.high32Unsigned >= _int64SignHigh32) {
    throw InvalidDataException(
      'Unsigned 64-bit compatible scalar value exceeds signed int64 range.',
    );
  }
  try {
    return value.toInt();
  } on StateError catch (error) {
    throw InvalidDataException(
      'Unsigned 64-bit compatible scalar value is not representable as a Dart int.',
      error,
    );
  }
}

Int64 _uint64ToInt64Target(Uint64 value) {
  if (value.high32Unsigned >= _int64SignHigh32) {
    throw InvalidDataException(
      'Unsigned 64-bit compatible scalar value exceeds signed int64 range.',
    );
  }
  return Int64.fromWords(value.low32, value.high32Unsigned);
}

Uint64 _signedIntToUint64Target(int value) {
  if (value < 0) {
    throw InvalidDataException(
      'Signed compatible scalar value $value is outside uint64 range.',
    );
  }
  return Uint64(value);
}

Uint64 _int64ToUint64Target(Int64 value) {
  if (value.isNegative) {
    throw InvalidDataException(
      'Signed compatible scalar value ${value.toBigInt()} is outside uint64 range.',
    );
  }
  return Uint64.fromWords(value.low32, value.high32Unsigned);
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
    return _toBool(value, remoteTypeId);
  }
  if (localTypeId == TypeIds.string) {
    return _toString(value, remoteTypeId);
  }
  if (_isIntegerType(localTypeId)) {
    return _toIntegerTarget(value, localType, remoteTypeId);
  }
  if (_isFloatingType(localTypeId)) {
    return _toFloatingTarget(value, localType, remoteTypeId);
  }
  if (localTypeId == TypeIds.decimal) {
    return _toDecimal(value, remoteTypeId);
  }
  throw InvalidDataException(
    'Unsupported compatible scalar target $localTypeId.',
  );
}

bool _toBool(Object value, int remoteTypeId) {
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
  final decimal = _numericDecimal(value, remoteTypeId);
  if (decimal.unscaled == BigInt.zero) {
    return false;
  }
  if (decimal.unscaled == BigInt.one && decimal.scale == 0) {
    return true;
  }
  throw const FormatException('Numeric bool value must be 0 or 1.');
}

String _toString(Object value, int remoteTypeId) {
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
  return _integerValue(value, remoteTypeId).toString();
}

Object _toIntegerTarget(Object value, FieldType localType, int remoteTypeId) {
  final decimal = _numericDecimal(value, remoteTypeId);
  if (decimal.scale != 0) {
    throw const FormatException('Numeric value is not integral.');
  }
  final integer = decimal.unscaled;
  _checkIntegerRange(integer, localType.typeId);
  return _integerCarrier(integer, localType);
}

Object _toFloatingTarget(Object value, FieldType localType, int remoteTypeId) {
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
  final decimal = _numericDecimal(value, remoteTypeId);
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

Decimal _toDecimal(Object value, int remoteTypeId) {
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
  return Decimal(_integerValue(value, remoteTypeId), 0);
}

_DecimalValue _numericDecimal(Object value, int remoteTypeId) {
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
  return _DecimalValue(_integerValue(value, remoteTypeId), 0);
}

BigInt _integerValue(Object value, int remoteTypeId) {
  switch (remoteTypeId) {
    case TypeIds.int8:
    case TypeIds.int16:
    case TypeIds.int32:
    case TypeIds.varInt32:
    case TypeIds.uint8:
    case TypeIds.uint16:
    case TypeIds.uint32:
    case TypeIds.varUint32:
      if (value is int) {
        return BigInt.from(value);
      }
      break;
    case TypeIds.int64:
    case TypeIds.varInt64:
    case TypeIds.taggedInt64:
      if (value is Int64) {
        return value.toBigInt();
      }
      if (value is int) {
        return Int64(value).toBigInt();
      }
      break;
    case TypeIds.uint64:
    case TypeIds.varUint64:
    case TypeIds.taggedUint64:
      if (value is Uint64) {
        return value.toBigInt();
      }
      if (value is int) {
        return Uint64(value).toBigInt();
      }
      break;
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

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
import 'package:fory/src/context/write_context.dart';
import 'package:fory/src/serializer/serializer.dart';
import 'package:fory/src/types/int64.dart';
import 'package:fory/src/types/uint64.dart';
import 'package:fory/src/util/string_util.dart';
import 'package:fory/src/types/decimal.dart';

// The small form reserves the low header bit to distinguish small/big
// encodings, so the zigzag value itself must still fit in 63 bits before the
// final << 1.
final BigInt _decimalSmallMin = -(BigInt.one << 62);
final BigInt _decimalSmallMax = (BigInt.one << 62) - BigInt.one;

// Wire Decimal bounds are independent of the 256-digit limit used only by
// compatible scalar conversion.
const int _maxDecimalMagnitudeBytes = 10_000;
const int _maxDecimalScale = 10_000;

bool _canUseSmallDecimalEncoding(BigInt value) {
  return value >= _decimalSmallMin && value <= _decimalSmallMax;
}

Uint8List _decimalMagnitudeToCanonicalLittleEndian(BigInt magnitude) {
  if (magnitude == BigInt.zero) {
    throw StateError('Zero must use the small decimal encoding.');
  }
  final bytes = <int>[];
  var remaining = magnitude;
  while (remaining > BigInt.zero) {
    bytes.add((remaining & BigInt.from(0xff)).toInt());
    remaining >>= 8;
  }
  return Uint8List.fromList(bytes);
}

BigInt _decimalMagnitudeFromCanonicalLittleEndian(Uint8List magnitudeBytes) {
  if (magnitudeBytes.isEmpty) {
    return BigInt.zero;
  }
  final hexBytes = Uint8List(magnitudeBytes.length * 2);
  var outputIndex = 0;
  for (var index = magnitudeBytes.length - 1; index >= 0; index -= 1) {
    final byte = magnitudeBytes[index];
    final high = byte >>> 4;
    final low = byte & 0x0f;
    hexBytes[outputIndex] = high < 10 ? 0x30 + high : 0x57 + high;
    hexBytes[outputIndex + 1] = low < 10 ? 0x30 + low : 0x57 + low;
    outputIndex += 2;
  }
  return BigInt.parse(String.fromCharCodes(hexBytes), radix: 16);
}

Uint64 _zigZagEncodeInt64(Int64 value) {
  final encoded = (value << 1) ^ (value >> 63);
  return Uint64.fromWords(encoded.low32, encoded.high32Unsigned);
}

Int64 _zigZagDecodeInt64(Uint64 encoded) {
  final magnitude = encoded >> 1;
  final decoded = Int64.fromWords(magnitude.low32, magnitude.high32Unsigned);
  if ((encoded.low32 & 1) == 0) {
    return decoded;
  }
  return -(decoded + 1);
}

@pragma('vm:never-inline')
Never _throwDecimalScaleOutOfRange(int scale) {
  throw StateError(
    'Decimal scale $scale exceeds supported range '
    '[-$_maxDecimalScale, $_maxDecimalScale].',
  );
}

@pragma('vm:never-inline')
Never _throwDecimalMagnitudeTooLarge(int length) {
  throw StateError(
    'Decimal magnitude length $length exceeds limit '
    '$_maxDecimalMagnitudeBytes.',
  );
}

final class NoneSerializer extends Serializer<Null> {
  const NoneSerializer();

  @override
  bool get supportsRef => false;

  @override
  void write(WriteContext context, Null value) {}

  @override
  Null read(ReadContext context) {
    return null;
  }
}

final class StringSerializer extends Serializer<String> {
  const StringSerializer();

  @override
  bool get supportsRef => false;

  @override
  void write(WriteContext context, String value) {
    writePayload(context, value);
  }

  @override
  String read(ReadContext context) {
    return readPayload(context);
  }

  static void writePayload(WriteContext context, String value) {
    writeString(context.buffer, value);
  }

  static String readPayload(ReadContext context) {
    final header = context.buffer.readVarUint36Small();
    final encoding = header & 0x03;
    final byteLength = header >>> 2;
    return readStringFromBuffer(context.buffer, byteLength, encoding);
  }
}

final class BinarySerializer extends Serializer<Uint8List> {
  const BinarySerializer();

  @override
  bool get supportsRef => false;

  @override
  void write(WriteContext context, Uint8List value) {
    writePayload(context, value);
  }

  @override
  Uint8List read(ReadContext context) {
    return readPayload(context);
  }

  static void writePayload(WriteContext context, Uint8List value) {
    context.buffer.writeVarUint32(value.length);
    context.buffer.writeBytes(value);
  }

  static Uint8List readPayload(ReadContext context) {
    final size = context.buffer.readVarUint32();
    context.buffer.checkReadableBytes(size);
    return context.buffer.copyBytes(size);
  }
}

final class DecimalSerializer extends Serializer<Decimal> {
  const DecimalSerializer();

  @override
  bool get supportsRef => false;

  @override
  void write(WriteContext context, Decimal value) {
    writePayload(context, value);
  }

  @override
  Decimal read(ReadContext context) {
    return readPayload(context);
  }

  static void writePayload(WriteContext context, Decimal value) {
    final scale = value.scale;
    // Compare directly because abs(scale) can overflow for the minimum int.
    if (scale < -_maxDecimalScale || scale > _maxDecimalScale) {
      _throwDecimalScaleOutOfRange(scale);
    }
    final unscaled = value.unscaledValue;
    if (_canUseSmallDecimalEncoding(unscaled)) {
      final buffer = context.buffer;
      buffer.writeVarInt32(scale);
      final zigZag = _zigZagEncodeInt64(Int64.fromBigInt(unscaled));
      buffer.writeVarUint64(zigZag << 1);
      return;
    }

    final magnitude = unscaled.abs();
    final magnitudeLength = (magnitude.bitLength + 7) >>> 3;
    if (magnitudeLength > _maxDecimalMagnitudeBytes) {
      _throwDecimalMagnitudeTooLarge(magnitudeLength);
    }
    final buffer = context.buffer;
    buffer.writeVarInt32(scale);
    final magnitudeBytes = _decimalMagnitudeToCanonicalLittleEndian(magnitude);
    final sign = unscaled.isNegative ? 1 : 0;
    final meta = (magnitudeBytes.length << 1) | sign;
    buffer.writeVarUint64(Uint64((meta << 1) | 1));
    buffer.writeBytes(magnitudeBytes);
  }

  static Decimal readPayload(ReadContext context) {
    final scale = context.buffer.readVarInt32();
    // Compare directly because abs(scale) can overflow for the minimum int.
    if (scale < -_maxDecimalScale || scale > _maxDecimalScale) {
      _throwDecimalScaleOutOfRange(scale);
    }
    final header = context.buffer.readVarUint64();
    if ((header.low32 & 1) == 0) {
      final zigZag = header >>> 1;
      return Decimal(_zigZagDecodeInt64(zigZag).toBigInt(), scale);
    }

    final meta = header >>> 1;
    // Keep Uint64-to-int overflow rejection before applying the smaller wire
    // resource limit.
    final length = (meta >>> 1).toInt();
    if (length <= 0) {
      throw StateError('Invalid decimal magnitude length $length.');
    }
    if (length > _maxDecimalMagnitudeBytes) {
      _throwDecimalMagnitudeTooLarge(length);
    }
    context.buffer.checkReadableBytes(length);
    final magnitudeBytes = context.buffer.copyBytes(length);
    if (magnitudeBytes[length - 1] == 0) {
      throw StateError(
        'Non-canonical decimal magnitude bytes: trailing zero byte.',
      );
    }
    final magnitude = _decimalMagnitudeFromCanonicalLittleEndian(
      magnitudeBytes,
    );
    if (magnitude == BigInt.zero) {
      throw StateError('Big decimal encoding must not represent zero.');
    }
    final sign = (meta & 1).toInt();
    return Decimal(sign == 0 ? magnitude : -magnitude, scale);
  }
}

const NoneSerializer noneSerializer = NoneSerializer();
const StringSerializer stringSerializer = StringSerializer();
const BinarySerializer binarySerializer = BinarySerializer();
const DecimalSerializer decimalSerializer = DecimalSerializer();

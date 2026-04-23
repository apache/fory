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

BigInt _decimalMagnitudeFromCanonicalLittleEndian(Uint8List payload) {
  var magnitude = BigInt.zero;
  for (var index = payload.length - 1; index >= 0; index -= 1) {
    magnitude = (magnitude << 8) | BigInt.from(payload[index]);
  }
  return magnitude;
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
    if (value.length > context.config.maxBinarySize) {
      throw StateError(
        'Binary payload exceeds ${context.config.maxBinarySize} bytes.',
      );
    }
    context.buffer.writeVarUint32(value.length);
    context.buffer.writeBytes(value);
  }

  static Uint8List readPayload(ReadContext context) {
    final size = context.buffer.readVarUint32();
    if (size > context.config.maxBinarySize) {
      throw StateError(
        'Binary payload exceeds ${context.config.maxBinarySize} bytes.',
      );
    }
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
    final buffer = context.buffer;
    final unscaled = value.unscaledValue;
    buffer.writeVarInt32(value.scale);
    if (_canUseSmallDecimalEncoding(unscaled)) {
      final zigZag = _zigZagEncodeInt64(Int64.fromBigInt(unscaled));
      buffer.writeVarUint64(zigZag << 1);
      return;
    }

    final payload = _decimalMagnitudeToCanonicalLittleEndian(unscaled.abs());
    final sign = unscaled.isNegative ? 1 : 0;
    final meta = (payload.length << 1) | sign;
    buffer.writeVarUint64(Uint64((meta << 1) | 1));
    buffer.writeBytes(payload);
  }

  static Decimal readPayload(ReadContext context) {
    final scale = context.buffer.readVarInt32();
    final header = context.buffer.readVarUint64();
    if ((header.low32 & 1) == 0) {
      final zigZag = header >>> 1;
      return Decimal(_zigZagDecodeInt64(zigZag).toBigInt(), scale);
    }

    final meta = header >>> 1;
    final length = (meta >>> 1).toInt();
    if (length <= 0) {
      throw StateError('Invalid decimal magnitude length $length.');
    }
    final payload = context.buffer.copyBytes(length);
    if (payload[length - 1] == 0) {
      throw StateError(
        'Non-canonical decimal payload: trailing zero byte.',
      );
    }
    final magnitude = _decimalMagnitudeFromCanonicalLittleEndian(payload);
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

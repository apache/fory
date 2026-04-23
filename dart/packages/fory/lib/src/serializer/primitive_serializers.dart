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

import 'package:fory/src/context/read_context.dart';
import 'package:fory/src/context/write_context.dart';
import 'package:fory/src/meta/type_ids.dart';
import 'package:fory/src/serializer/serializer.dart';
import 'package:fory/src/types/bfloat16.dart';
import 'package:fory/src/types/float16.dart';
import 'package:fory/src/types/float32.dart';
import 'package:fory/src/types/int16.dart';
import 'package:fory/src/types/int32.dart';
import 'package:fory/src/types/int64.dart';
import 'package:fory/src/types/int8.dart';
import 'package:fory/src/types/uint16.dart';
import 'package:fory/src/types/uint32.dart';
import 'package:fory/src/types/uint64.dart';
import 'package:fory/src/types/uint8.dart';

const int _jsSafeUint64IntMax = 9007199254740991;
const bool _isWeb = bool.fromEnvironment('dart.library.js_interop');

final class PrimitiveSerializer<T> extends Serializer<T> {
  final int typeId;
  final bool _supportsRef;

  const PrimitiveSerializer(
    this.typeId, {
    required bool supportsRef,
  }) : _supportsRef = supportsRef;

  @override
  bool get supportsRef => _supportsRef;

  @override
  void write(WriteContext context, T value) {
    writePayload(context, typeId, value as Object);
  }

  @override
  T read(ReadContext context) {
    return readPayload(context, typeId) as T;
  }

  static void writePayload(
    WriteContext context,
    int typeId,
    Object value,
  ) {
    final buffer = context.buffer;
    switch (typeId) {
      case TypeIds.boolType:
        buffer.writeBool(value as bool);
        return;
      case TypeIds.int8:
        buffer.writeByte((value as Int8).value);
        return;
      case TypeIds.int16:
        buffer.writeInt16((value as Int16).value);
        return;
      case TypeIds.int32:
        buffer.writeInt32((value as Int32).value);
        return;
      case TypeIds.varInt32:
        buffer.writeVarInt32(value is Int32 ? value.value : value as int);
        return;
      case TypeIds.int64:
        if (value is Int64) {
          buffer.writeInt64(value);
        } else {
          buffer.writeInt64FromInt(value as int);
        }
        return;
      case TypeIds.varInt64:
        if (value is Int64) {
          buffer.writeVarInt64(value);
        } else {
          buffer.writeVarInt64FromInt(value as int);
        }
        return;
      case TypeIds.taggedInt64:
        if (value is Int64) {
          buffer.writeTaggedInt64(value);
        } else {
          buffer.writeTaggedInt64FromInt(value as int);
        }
        return;
      case TypeIds.uint8:
        buffer.writeUint8(value is Uint8 ? value.value : value as int);
        return;
      case TypeIds.uint16:
        buffer.writeUint16(value is Uint16 ? value.value : value as int);
        return;
      case TypeIds.uint32:
        buffer.writeUint32(value is Uint32 ? value.value : value as int);
        return;
      case TypeIds.varUint32:
        buffer.writeVarUint32(value is Uint32 ? value.value : value as int);
        return;
      case TypeIds.uint64:
        buffer.writeUint64(_uint64Value(value));
        return;
      case TypeIds.varUint64:
        buffer.writeVarUint64(_uint64Value(value));
        return;
      case TypeIds.taggedUint64:
        buffer.writeTaggedUint64(_uint64Value(value));
        return;
      case TypeIds.float16:
        buffer.writeFloat16(value as Float16);
        return;
      case TypeIds.bfloat16:
        buffer.writeBfloat16(value as Bfloat16);
        return;
      case TypeIds.float32:
        buffer.writeFloat32((value as Float32).value);
        return;
      case TypeIds.float64:
        buffer.writeFloat64(value as double);
        return;
      default:
        throw StateError('Unsupported primitive type id $typeId.');
    }
  }

  static Object readPayload(
    ReadContext context,
    int typeId,
  ) {
    final buffer = context.buffer;
    switch (typeId) {
      case TypeIds.boolType:
        return buffer.readBool();
      case TypeIds.int8:
        return Int8(buffer.readByte());
      case TypeIds.int16:
        return Int16(buffer.readInt16());
      case TypeIds.int32:
        return Int32(buffer.readInt32());
      case TypeIds.varInt32:
        return Int32(buffer.readVarInt32());
      case TypeIds.int64:
        return buffer.readInt64();
      case TypeIds.varInt64:
        return buffer.readVarInt64();
      case TypeIds.taggedInt64:
        return buffer.readTaggedInt64();
      case TypeIds.uint8:
        return Uint8(buffer.readUint8());
      case TypeIds.uint16:
        return Uint16(buffer.readUint16());
      case TypeIds.uint32:
        return Uint32(buffer.readUint32());
      case TypeIds.varUint32:
        return Uint32(buffer.readVarUint32());
      case TypeIds.uint64:
        return buffer.readUint64();
      case TypeIds.varUint64:
        return buffer.readVarUint64();
      case TypeIds.taggedUint64:
        return buffer.readTaggedUint64();
      case TypeIds.float16:
        return buffer.readFloat16();
      case TypeIds.bfloat16:
        return buffer.readBfloat16();
      case TypeIds.float32:
        return Float32(buffer.readFloat32());
      case TypeIds.float64:
        return buffer.readFloat64();
      default:
        throw StateError('Unsupported primitive type id $typeId.');
    }
  }
}

Uint64 _uint64Value(Object value) {
  if (value is Uint64) {
    return value;
  }
  final intValue = value as int;
  if (_isWeb && (intValue < 0 || intValue > _jsSafeUint64IntMax)) {
    throw StateError(
      'Dart int value $intValue is outside the JS-safe unsigned uint64 '
      'int field range [0, $_jsSafeUint64IntMax]. Use Uint64 for full '
      'unsigned 64-bit values on web.',
    );
  }
  return Uint64(intValue);
}

const PrimitiveSerializer<bool> boolSerializer = PrimitiveSerializer<bool>(
  TypeIds.boolType,
  supportsRef: false,
);
const PrimitiveSerializer<Int8> int8Serializer = PrimitiveSerializer<Int8>(
  TypeIds.int8,
  supportsRef: false,
);
const PrimitiveSerializer<Int16> int16Serializer = PrimitiveSerializer<Int16>(
  TypeIds.int16,
  supportsRef: false,
);
const PrimitiveSerializer<Int32> int32Serializer = PrimitiveSerializer<Int32>(
  TypeIds.int32,
  supportsRef: false,
);
const PrimitiveSerializer<Int32> varInt32Serializer =
    PrimitiveSerializer<Int32>(
  TypeIds.varInt32,
  supportsRef: false,
);
const PrimitiveSerializer<Int64> int64Serializer = PrimitiveSerializer<Int64>(
  TypeIds.int64,
  supportsRef: false,
);
const PrimitiveSerializer<Int64> varInt64Serializer =
    PrimitiveSerializer<Int64>(
  TypeIds.varInt64,
  supportsRef: false,
);
const PrimitiveSerializer<Int64> taggedInt64Serializer =
    PrimitiveSerializer<Int64>(
  TypeIds.taggedInt64,
  supportsRef: false,
);
const PrimitiveSerializer<Uint8> uint8Serializer = PrimitiveSerializer<Uint8>(
  TypeIds.uint8,
  supportsRef: false,
);
const PrimitiveSerializer<Uint16> uint16Serializer =
    PrimitiveSerializer<Uint16>(
  TypeIds.uint16,
  supportsRef: false,
);
const PrimitiveSerializer<Uint32> uint32Serializer =
    PrimitiveSerializer<Uint32>(
  TypeIds.uint32,
  supportsRef: false,
);
const PrimitiveSerializer<Uint32> varUint32Serializer =
    PrimitiveSerializer<Uint32>(
  TypeIds.varUint32,
  supportsRef: false,
);
const PrimitiveSerializer<Uint64> uint64Serializer =
    PrimitiveSerializer<Uint64>(
  TypeIds.uint64,
  supportsRef: false,
);
const PrimitiveSerializer<Uint64> varUint64Serializer =
    PrimitiveSerializer<Uint64>(
  TypeIds.varUint64,
  supportsRef: false,
);
const PrimitiveSerializer<Uint64> taggedUint64Serializer =
    PrimitiveSerializer<Uint64>(
  TypeIds.taggedUint64,
  supportsRef: false,
);
const PrimitiveSerializer<Float16> float16Serializer =
    PrimitiveSerializer<Float16>(
  TypeIds.float16,
  supportsRef: false,
);
const PrimitiveSerializer<Bfloat16> bfloat16Serializer =
    PrimitiveSerializer<Bfloat16>(
  TypeIds.bfloat16,
  supportsRef: false,
);
const PrimitiveSerializer<Float32> float32Serializer =
    PrimitiveSerializer<Float32>(
  TypeIds.float32,
  supportsRef: false,
);
const PrimitiveSerializer<double> float64Serializer =
    PrimitiveSerializer<double>(
  TypeIds.float64,
  supportsRef: false,
);

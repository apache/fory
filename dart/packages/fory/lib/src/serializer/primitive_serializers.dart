import 'package:fory/src/context/read_context.dart';
import 'package:fory/src/context/write_context.dart';
import 'package:fory/src/meta/type_ids.dart';
import 'package:fory/src/serializer/serializer.dart';
import 'package:fory/src/types/fixed_ints.dart';
import 'package:fory/src/types/bfloat16.dart';
import 'package:fory/src/types/float16.dart';
import 'package:fory/src/types/float32.dart';

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
        buffer.writeInt64(value as int);
        return;
      case TypeIds.varInt64:
        buffer.writeVarInt64(value as int);
        return;
      case TypeIds.taggedInt64:
        buffer.writeTaggedInt64(value as int);
        return;
      case TypeIds.uint8:
        buffer.writeUint8(value is UInt8 ? value.value : value as int);
        return;
      case TypeIds.uint16:
        buffer.writeUint16(value is UInt16 ? value.value : value as int);
        return;
      case TypeIds.uint32:
        buffer.writeUint32(value is UInt32 ? value.value : value as int);
        return;
      case TypeIds.varUint32:
        buffer.writeVarUint32(value is UInt32 ? value.value : value as int);
        return;
      case TypeIds.uint64:
        buffer.writeUint64(value as int);
        return;
      case TypeIds.varUint64:
        buffer.writeVarUint64(value as int);
        return;
      case TypeIds.taggedUint64:
        buffer.writeTaggedUint64(value as int);
        return;
      case TypeIds.float16:
        buffer.writeFloat16(value as Float16);
        return;
      case TypeIds.bfloat16:
        buffer.writeBfloat16(value as BFloat16);
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
        return UInt8(buffer.readUint8());
      case TypeIds.uint16:
        return UInt16(buffer.readUint16());
      case TypeIds.uint32:
        return UInt32(buffer.readUint32());
      case TypeIds.varUint32:
        return UInt32(buffer.readVarUint32());
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
const PrimitiveSerializer<int> int64Serializer = PrimitiveSerializer<int>(
  TypeIds.int64,
  supportsRef: false,
);
const PrimitiveSerializer<int> varInt64Serializer = PrimitiveSerializer<int>(
  TypeIds.varInt64,
  supportsRef: false,
);
const PrimitiveSerializer<int> taggedInt64Serializer = PrimitiveSerializer<int>(
  TypeIds.taggedInt64,
  supportsRef: false,
);
const PrimitiveSerializer<UInt8> uint8Serializer = PrimitiveSerializer<UInt8>(
  TypeIds.uint8,
  supportsRef: false,
);
const PrimitiveSerializer<UInt16> uint16Serializer =
    PrimitiveSerializer<UInt16>(
  TypeIds.uint16,
  supportsRef: false,
);
const PrimitiveSerializer<UInt32> uint32Serializer =
    PrimitiveSerializer<UInt32>(
  TypeIds.uint32,
  supportsRef: false,
);
const PrimitiveSerializer<UInt32> varUint32Serializer =
    PrimitiveSerializer<UInt32>(
  TypeIds.varUint32,
  supportsRef: false,
);
const PrimitiveSerializer<int> uint64Serializer = PrimitiveSerializer<int>(
  TypeIds.uint64,
  supportsRef: false,
);
const PrimitiveSerializer<int> varUint64Serializer = PrimitiveSerializer<int>(
  TypeIds.varUint64,
  supportsRef: false,
);
const PrimitiveSerializer<int> taggedUint64Serializer =
    PrimitiveSerializer<int>(
  TypeIds.taggedUint64,
  supportsRef: false,
);
const PrimitiveSerializer<Float16> float16Serializer =
    PrimitiveSerializer<Float16>(
  TypeIds.float16,
  supportsRef: false,
);
const PrimitiveSerializer<BFloat16> bfloat16Serializer =
    PrimitiveSerializer<BFloat16>(
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

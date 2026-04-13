import 'dart:typed_data';

import 'package:fory/src/context/read_context.dart';
import 'package:fory/src/context/write_context.dart';
import 'package:fory/src/serializer/serializer.dart';
import 'package:fory/src/string_encoding.dart';
import 'package:fory/src/types/local_date.dart';
import 'package:fory/src/types/timestamp.dart';

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

final class LocalDateSerializer extends Serializer<LocalDate> {
  const LocalDateSerializer();

  @override
  bool get supportsRef => false;

  @override
  void write(WriteContext context, LocalDate value) {
    context.buffer.writeInt32(value.toEpochDay());
  }

  @override
  LocalDate read(ReadContext context) {
    return LocalDate.fromEpochDay(context.buffer.readInt32());
  }
}

final class TimestampSerializer extends Serializer<Timestamp> {
  const TimestampSerializer();

  @override
  bool get supportsRef => false;

  @override
  void write(WriteContext context, Timestamp value) {
    context.buffer.writeInt64(value.seconds);
    context.buffer.writeUint32(value.nanoseconds);
  }

  @override
  Timestamp read(ReadContext context) {
    return Timestamp(
      context.buffer.readInt64(),
      context.buffer.readUint32(),
    );
  }
}

const StringSerializer stringSerializer = StringSerializer();
const BinarySerializer binarySerializer = BinarySerializer();
const LocalDateSerializer localDateSerializer = LocalDateSerializer();
const TimestampSerializer timestampSerializer = TimestampSerializer();

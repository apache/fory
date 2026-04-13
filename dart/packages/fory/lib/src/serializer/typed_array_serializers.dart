import 'dart:typed_data';

import 'package:fory/src/context/read_context.dart';
import 'package:fory/src/context/write_context.dart';
import 'package:fory/src/resolver/type_resolver.dart';
import 'package:fory/src/serializer/serializer.dart';

void writeTypedArrayBytes(
  WriteContext context,
  TypedData values,
) {
  context.buffer.writeVarUint32(values.lengthInBytes);
  context.buffer.writeBytes(
    values.buffer.asUint8List(values.offsetInBytes, values.lengthInBytes),
  );
}

T readTypedArrayBytes<T>(
  ReadContext context,
  int elementSize,
  T Function(Uint8List bytes) viewBuilder,
) {
  final byteSize = context.buffer.readVarUint32();
  if (byteSize % elementSize != 0) {
    throw StateError(
      'Typed array byte size $byteSize is not aligned to element size $elementSize.',
    );
  }
  var bytes = context.buffer.readBytes(byteSize);
  if (bytes.offsetInBytes % elementSize != 0) {
    bytes = Uint8List.fromList(bytes);
  }
  return viewBuilder(bytes);
}

final class BoolArraySerializer extends Serializer<List<bool>> {
  const BoolArraySerializer();

  @override
  bool get supportsRef => false;

  @override
  void write(WriteContext context, List<bool> value) {
    context.buffer.writeVarUint32(value.length);
    for (final entry in value) {
      context.buffer.writeBool(entry);
    }
  }

  @override
  List<bool> read(ReadContext context) {
    final size = context.buffer.readVarUint32();
    return List<bool>.generate(
      size,
      (_) => context.buffer.readBool(),
      growable: false,
    );
  }
}

final class TypedArraySerializer<T extends TypedData> extends Serializer<T> {
  final int typeId;
  final int elementSize;
  final T Function(Uint8List bytes) viewBuilder;

  const TypedArraySerializer(
    this.typeId,
    this.elementSize,
    this.viewBuilder,
  );

  @override
  bool get supportsRef => false;

  @override
  void write(WriteContext context, T value) {
    if (typeId == TypeIds.int8Array) {
      final bytes = value as Int8List;
      context.buffer.writeVarUint32(bytes.length);
      context.buffer.writeBytes(bytes);
      return;
    }
    writeTypedArrayBytes(context, value);
  }

  @override
  T read(ReadContext context) {
    if (typeId == TypeIds.int8Array) {
      final size = context.buffer.readVarUint32();
      return Int8List.fromList(context.buffer.readBytes(size)) as T;
    }
    return readTypedArrayBytes(context, elementSize, viewBuilder);
  }
}

const BoolArraySerializer boolArraySerializer = BoolArraySerializer();
const TypedArraySerializer<Int8List> int8ArraySerializer =
    TypedArraySerializer<Int8List>(
  TypeIds.int8Array,
  1,
  Int8List.fromList,
);
const TypedArraySerializer<Int16List> int16ArraySerializer =
    TypedArraySerializer<Int16List>(
  TypeIds.int16Array,
  2,
  _asInt16List,
);
const TypedArraySerializer<Int32List> int32ArraySerializer =
    TypedArraySerializer<Int32List>(
  TypeIds.int32Array,
  4,
  _asInt32List,
);
const TypedArraySerializer<Int64List> int64ArraySerializer =
    TypedArraySerializer<Int64List>(
  TypeIds.int64Array,
  8,
  _asInt64List,
);
const TypedArraySerializer<Uint16List> uint16ArraySerializer =
    TypedArraySerializer<Uint16List>(
  TypeIds.uint16Array,
  2,
  _asUint16List,
);
const TypedArraySerializer<Uint32List> uint32ArraySerializer =
    TypedArraySerializer<Uint32List>(
  TypeIds.uint32Array,
  4,
  _asUint32List,
);
const TypedArraySerializer<Uint64List> uint64ArraySerializer =
    TypedArraySerializer<Uint64List>(
  TypeIds.uint64Array,
  8,
  _asUint64List,
);
const TypedArraySerializer<Float32List> float32ArraySerializer =
    TypedArraySerializer<Float32List>(
  TypeIds.float32Array,
  4,
  _asFloat32List,
);
const TypedArraySerializer<Float64List> float64ArraySerializer =
    TypedArraySerializer<Float64List>(
  TypeIds.float64Array,
  8,
  _asFloat64List,
);

Int16List _asInt16List(Uint8List bytes) => bytes.buffer.asInt16List(
      bytes.offsetInBytes,
      bytes.lengthInBytes ~/ 2,
    );

Int32List _asInt32List(Uint8List bytes) => bytes.buffer.asInt32List(
      bytes.offsetInBytes,
      bytes.lengthInBytes ~/ 4,
    );

Int64List _asInt64List(Uint8List bytes) => bytes.buffer.asInt64List(
      bytes.offsetInBytes,
      bytes.lengthInBytes ~/ 8,
    );

Uint16List _asUint16List(Uint8List bytes) => bytes.buffer.asUint16List(
      bytes.offsetInBytes,
      bytes.lengthInBytes ~/ 2,
    );

Uint32List _asUint32List(Uint8List bytes) => bytes.buffer.asUint32List(
      bytes.offsetInBytes,
      bytes.lengthInBytes ~/ 4,
    );

Uint64List _asUint64List(Uint8List bytes) => bytes.buffer.asUint64List(
      bytes.offsetInBytes,
      bytes.lengthInBytes ~/ 8,
    );

Float32List _asFloat32List(Uint8List bytes) => bytes.buffer.asFloat32List(
      bytes.offsetInBytes,
      bytes.lengthInBytes ~/ 4,
    );

Float64List _asFloat64List(Uint8List bytes) => bytes.buffer.asFloat64List(
      bytes.offsetInBytes,
      bytes.lengthInBytes ~/ 8,
    );

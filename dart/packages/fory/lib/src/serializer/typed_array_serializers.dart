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

import 'dart:typed_data' as td;

import 'package:fory/src/context/read_context.dart';
import 'package:fory/src/context/write_context.dart';
import 'package:fory/src/meta/type_ids.dart';
import 'package:fory/src/serializer/serializer.dart';
import 'package:fory/src/types/bfloat16.dart';
import 'package:fory/src/types/bool_list.dart';
import 'package:fory/src/types/float16.dart';
import 'package:fory/src/types/int64.dart';
import 'package:fory/src/types/uint64.dart';

void writeTypedArrayBytes(
  WriteContext context,
  Object values,
) {
  final bytes = switch (values) {
    Int64List typed => typed.buffer.asUint8List(
        typed.offsetInBytes,
        typed.lengthInBytes,
      ),
    Uint64List typed => typed.buffer.asUint8List(
        typed.offsetInBytes,
        typed.lengthInBytes,
      ),
    Float16List typed => typed.buffer.asUint8List(
        typed.offsetInBytes,
        typed.lengthInBytes,
      ),
    Bfloat16List typed => typed.buffer.asUint8List(
        typed.offsetInBytes,
        typed.lengthInBytes,
      ),
    td.TypedData typed => typed.buffer.asUint8List(
        typed.offsetInBytes,
        typed.lengthInBytes,
      ),
    _ => throw ArgumentError.value(
        values,
        'values',
        'Expected a supported contiguous typed array value.',
      ),
  };
  context.buffer.writeVarUint32(bytes.length);
  context.buffer.writeBytes(bytes);
}

T readTypedArrayBytes<T>(
  ReadContext context,
  int elementSize,
  T Function(td.Uint8List bytes) viewBuilder,
) {
  final byteSize = context.buffer.readVarUint32();
  if (byteSize % elementSize != 0) {
    throw StateError(
      'Typed array byte size $byteSize is not aligned to element size $elementSize.',
    );
  }
  var bytes = context.buffer.readBytes(byteSize);
  if (bytes.offsetInBytes % elementSize != 0) {
    bytes = td.Uint8List.fromList(bytes);
  }
  return viewBuilder(bytes);
}

final class BoolArraySerializer extends Serializer<BoolList> {
  const BoolArraySerializer();

  @override
  bool get supportsRef => false;

  @override
  void write(WriteContext context, BoolList value) {
    context.buffer.writeVarUint32(value.length);
    context.buffer.writeBytes(value.asUint8List());
  }

  @override
  BoolList read(ReadContext context) {
    final size = context.buffer.readVarUint32();
    return BoolList.arrayStorage(context.buffer.readInt8Bytes(size));
  }
}

final class TypedArraySerializer<T> extends Serializer<T> {
  final int typeId;
  final int elementSize;
  final T Function(td.Uint8List bytes) viewBuilder;

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
      final bytes = value as td.Int8List;
      context.buffer.writeVarUint32(bytes.length);
      context.buffer.writeBytes(bytes);
      return;
    }
    writeTypedArrayBytes(context, value as Object);
  }

  @override
  T read(ReadContext context) {
    if (typeId == TypeIds.int8Array) {
      final size = context.buffer.readVarUint32();
      return td.Int8List.fromList(context.buffer.readBytes(size)) as T;
    }
    return readTypedArrayBytes(context, elementSize, viewBuilder);
  }
}

const BoolArraySerializer boolArraySerializer = BoolArraySerializer();
const TypedArraySerializer<td.Int8List> int8ArraySerializer =
    TypedArraySerializer<td.Int8List>(
  TypeIds.int8Array,
  1,
  td.Int8List.fromList,
);
const TypedArraySerializer<td.Int16List> int16ArraySerializer =
    TypedArraySerializer<td.Int16List>(
  TypeIds.int16Array,
  2,
  _asInt16List,
);
const TypedArraySerializer<td.Int32List> int32ArraySerializer =
    TypedArraySerializer<td.Int32List>(
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
const TypedArraySerializer<td.Uint16List> uint16ArraySerializer =
    TypedArraySerializer<td.Uint16List>(
  TypeIds.uint16Array,
  2,
  _asUint16List,
);
const TypedArraySerializer<td.Uint32List> uint32ArraySerializer =
    TypedArraySerializer<td.Uint32List>(
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
const TypedArraySerializer<Float16List> float16ArraySerializer =
    TypedArraySerializer<Float16List>(
  TypeIds.float16Array,
  2,
  _asFloat16List,
);
const TypedArraySerializer<Bfloat16List> bfloat16ArraySerializer =
    TypedArraySerializer<Bfloat16List>(
  TypeIds.bfloat16Array,
  2,
  _asBfloat16List,
);
const TypedArraySerializer<td.Float32List> float32ArraySerializer =
    TypedArraySerializer<td.Float32List>(
  TypeIds.float32Array,
  4,
  _asFloat32List,
);
const TypedArraySerializer<td.Float64List> float64ArraySerializer =
    TypedArraySerializer<td.Float64List>(
  TypeIds.float64Array,
  8,
  _asFloat64List,
);

td.Int16List _asInt16List(td.Uint8List bytes) => bytes.buffer.asInt16List(
      bytes.offsetInBytes,
      bytes.lengthInBytes ~/ 2,
    );

td.Int32List _asInt32List(td.Uint8List bytes) => bytes.buffer.asInt32List(
      bytes.offsetInBytes,
      bytes.lengthInBytes ~/ 4,
    );

Int64List _asInt64List(td.Uint8List bytes) =>
    Int64List.view(bytes.buffer, bytes.offsetInBytes, bytes.lengthInBytes ~/ 8);

td.Uint16List _asUint16List(td.Uint8List bytes) => bytes.buffer.asUint16List(
      bytes.offsetInBytes,
      bytes.lengthInBytes ~/ 2,
    );

Float16List _asFloat16List(td.Uint8List bytes) => Float16List.view(
    bytes.buffer, bytes.offsetInBytes, bytes.lengthInBytes ~/ 2);

Bfloat16List _asBfloat16List(td.Uint8List bytes) => Bfloat16List.view(
      bytes.buffer,
      bytes.offsetInBytes,
      bytes.lengthInBytes ~/ 2,
    );

td.Uint32List _asUint32List(td.Uint8List bytes) => bytes.buffer.asUint32List(
      bytes.offsetInBytes,
      bytes.lengthInBytes ~/ 4,
    );

Uint64List _asUint64List(td.Uint8List bytes) => Uint64List.view(
      bytes.buffer,
      bytes.offsetInBytes,
      bytes.lengthInBytes ~/ 8,
    );

td.Float32List _asFloat32List(td.Uint8List bytes) => bytes.buffer.asFloat32List(
      bytes.offsetInBytes,
      bytes.lengthInBytes ~/ 4,
    );

td.Float64List _asFloat64List(td.Uint8List bytes) => bytes.buffer.asFloat64List(
      bytes.offsetInBytes,
      bytes.lengthInBytes ~/ 8,
    );

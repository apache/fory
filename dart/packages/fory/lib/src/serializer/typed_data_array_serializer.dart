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
import 'package:fory/src/const/types.dart';
import 'package:fory/src/dev_annotation/optimize.dart';
import 'package:fory/src/memory/byte_reader.dart';
import 'package:fory/src/memory/byte_writer.dart';
import 'package:fory/src/serializer/array_serializer.dart';
import 'package:fory/src/serializer/serializer_cache.dart';

final class _Uint8ListSerializerCache extends ArraySerializerCache {
  static Uint8ListSerializer? _serializerWithoutReference;
  static Uint8ListSerializer? _serializerWithReference;

  const _Uint8ListSerializerCache();

  @override
  Uint8ListSerializer getSerializerWithRef(bool writeRef) {
    if (writeRef) {
      _serializerWithReference ??= Uint8ListSerializer(true);
      return _serializerWithReference!;
    } else {
      _serializerWithoutReference ??= Uint8ListSerializer(false);
      return _serializerWithoutReference!;
    }
  }
}

final class Uint8ListSerializer extends NumericArraySerializer<int> {
  static const SerializerCache cache = _Uint8ListSerializerCache();

  const Uint8ListSerializer(bool writeRef) : super(ObjType.BINARY, writeRef);

  @inline
  @override
  TypedDataList<int> readToList(Uint8List copiedMem, ByteReader br) {
    // Single-byte type, no endian conversion needed
    return copiedMem;
  }

  @override
  int get bytesPerNum => 1;
}

final class _Int8ListSerializerCache extends ArraySerializerCache {
  static Int8ListSerializer? _serializerWithoutReference;
  static Int8ListSerializer? _serializerWithReference;

  const _Int8ListSerializerCache();

  @override
  Int8ListSerializer getSerializerWithRef(bool writeRef) {
    if (writeRef) {
      _serializerWithReference ??= Int8ListSerializer(true);
      return _serializerWithReference!;
    } else {
      _serializerWithoutReference ??= Int8ListSerializer(false);
      return _serializerWithoutReference!;
    }
  }
}

final class Int8ListSerializer extends NumericArraySerializer<int> {
  static const SerializerCache cache = _Int8ListSerializerCache();
  const Int8ListSerializer(bool writeRef) : super(ObjType.INT8_ARRAY, writeRef);

  @override
  TypedDataList<int> readToList(Uint8List copiedMem, ByteReader br) {
    // Single-byte type, no endian conversion needed
    Int8List list = copiedMem.buffer.asInt8List();
    return list;
  }

  @override
  int get bytesPerNum => 1;
}

final class _Int16ListSerializerCache extends ArraySerializerCache {
  static Int16ListSerializer? _serializerWithoutReference;
  static Int16ListSerializer? _serializerWithReference;

  const _Int16ListSerializerCache();

  @override
  Int16ListSerializer getSerializerWithRef(bool writeRef) {
    if (writeRef) {
      _serializerWithReference ??= Int16ListSerializer(true);
      return _serializerWithReference!;
    } else {
      _serializerWithoutReference ??= Int16ListSerializer(false);
      return _serializerWithoutReference!;
    }
  }
}

final class Int16ListSerializer extends NumericArraySerializer<int> {
  static const SerializerCache cache = _Int16ListSerializerCache();
  const Int16ListSerializer(bool writeRef)
      : super(ObjType.INT16_ARRAY, writeRef);

  @override
  TypedDataList<int> readToList(Uint8List copiedMem, ByteReader br) {
    // Fast path: direct memory interpretation (only called on little-endian)
    Int16List list = copiedMem.buffer.asInt16List();
    return list;
  }

  @override
  TypedDataList<int> readToListBigEndian(int length, ByteReader br) {
    Int16List list = Int16List(length);
    for (int i = 0; i < length; i++) {
      list[i] = br.readInt16();
    }
    return list;
  }

  @override
  void writeListBigEndian(ByteWriter bw, TypedDataList<int> v) {
    for (int i = 0; i < v.length; i++) {
      bw.writeInt16(v[i]);
    }
  }

  @override
  int get bytesPerNum => 2;
}

final class _Int32ListSerializerCache extends ArraySerializerCache {
  static Int32ListSerializer? _serializerWithoutReference;
  static Int32ListSerializer? _serializerWithReference;

  const _Int32ListSerializerCache();

  @override
  Int32ListSerializer getSerializerWithRef(bool writeRef) {
    if (writeRef) {
      _serializerWithReference ??= Int32ListSerializer(true);
      return _serializerWithReference!;
    } else {
      _serializerWithoutReference ??= Int32ListSerializer(false);
      return _serializerWithoutReference!;
    }
  }
}

final class Int32ListSerializer extends NumericArraySerializer<int> {
  static const SerializerCache cache = _Int32ListSerializerCache();
  const Int32ListSerializer(bool writeRef)
      : super(ObjType.INT32_ARRAY, writeRef);

  @override
  TypedDataList<int> readToList(Uint8List copiedMem, ByteReader br) {
    // Fast path: direct memory interpretation (only called on little-endian)
    Int32List list = copiedMem.buffer.asInt32List();
    return list;
  }

  @override
  TypedDataList<int> readToListBigEndian(int length, ByteReader br) {
    Int32List list = Int32List(length);
    for (int i = 0; i < length; i++) {
      list[i] = br.readInt32();
    }
    return list;
  }

  @override
  void writeListBigEndian(ByteWriter bw, TypedDataList<int> v) {
    for (int i = 0; i < v.length; i++) {
      bw.writeInt32(v[i]);
    }
  }

  @override
  int get bytesPerNum => 4;
}

final class _Float32ListSerializerCache extends ArraySerializerCache {
  static Float32ListSerializer? _serializerWithoutReference;
  static Float32ListSerializer? _serializerWithReference;

  const _Float32ListSerializerCache();

  @override
  Float32ListSerializer getSerializerWithRef(bool writeRef) {
    if (writeRef) {
      _serializerWithReference ??= Float32ListSerializer(true);
      return _serializerWithReference!;
    } else {
      _serializerWithoutReference ??= Float32ListSerializer(false);
      return _serializerWithoutReference!;
    }
  }
}

final class _Int64ListSerializerCache extends ArraySerializerCache {
  static Int64ListSerializer? _serializerWithoutReference;
  static Int64ListSerializer? _serializerWithReference;

  const _Int64ListSerializerCache();

  @override
  Int64ListSerializer getSerializerWithRef(bool writeRef) {
    if (writeRef) {
      _serializerWithReference ??= Int64ListSerializer(true);
      return _serializerWithReference!;
    } else {
      _serializerWithoutReference ??= Int64ListSerializer(false);
      return _serializerWithoutReference!;
    }
  }
}

final class Int64ListSerializer extends NumericArraySerializer<int> {
  static const SerializerCache cache = _Int64ListSerializerCache();
  const Int64ListSerializer(bool writeRef)
      : super(ObjType.INT64_ARRAY, writeRef);

  @override
  TypedDataList<int> readToList(Uint8List copiedMem, ByteReader br) {
    // Fast path: direct memory interpretation (only called on little-endian)
    Int64List list = copiedMem.buffer.asInt64List();
    return list;
  }

  @override
  TypedDataList<int> readToListBigEndian(int length, ByteReader br) {
    Int64List list = Int64List(length);
    for (int i = 0; i < length; i++) {
      list[i] = br.readInt64();
    }
    return list;
  }

  @override
  void writeListBigEndian(ByteWriter bw, TypedDataList<int> v) {
    for (int i = 0; i < v.length; i++) {
      bw.writeInt64(v[i]);
    }
  }

  @override
  int get bytesPerNum => 8;
}

final class Float32ListSerializer extends NumericArraySerializer<double> {
  static const SerializerCache cache = _Float32ListSerializerCache();

  const Float32ListSerializer(bool writeRef)
      : super(ObjType.FLOAT32_ARRAY, writeRef);

  @override
  TypedDataList<double> readToList(Uint8List copiedMem, ByteReader br) {
    // Fast path: direct memory interpretation (only called on little-endian)
    Float32List list = copiedMem.buffer.asFloat32List();
    return list;
  }

  @override
  TypedDataList<double> readToListBigEndian(int length, ByteReader br) {
    Float32List list = Float32List(length);
    for (int i = 0; i < length; i++) {
      list[i] = br.readFloat32();
    }
    return list;
  }

  @override
  void writeListBigEndian(ByteWriter bw, TypedDataList<double> v) {
    for (int i = 0; i < v.length; i++) {
      bw.writeFloat32(v[i]);
    }
  }

  @override
  int get bytesPerNum => 4;
}

final class _Float64ListSerializerCache extends ArraySerializerCache {
  static Float64ListSerializer? _serializerWithoutReference;
  static Float64ListSerializer? _serializerWithReference;

  const _Float64ListSerializerCache();

  @override
  Float64ListSerializer getSerializerWithRef(bool writeRef) {
    if (writeRef) {
      _serializerWithReference ??= Float64ListSerializer(true);
      return _serializerWithReference!;
    } else {
      _serializerWithoutReference ??= Float64ListSerializer(false);
      return _serializerWithoutReference!;
    }
  }
}

final class Float64ListSerializer extends NumericArraySerializer<double> {
  static const SerializerCache cache = _Float64ListSerializerCache();
  const Float64ListSerializer(bool writeRef)
      : super(ObjType.FLOAT64_ARRAY, writeRef);

  @override
  TypedDataList<double> readToList(Uint8List copiedMem, ByteReader br) {
    // Fast path: direct memory interpretation (only called on little-endian)
    Float64List list = copiedMem.buffer.asFloat64List();
    return list;
  }

  @override
  TypedDataList<double> readToListBigEndian(int length, ByteReader br) {
    Float64List list = Float64List(length);
    for (int i = 0; i < length; i++) {
      list[i] = br.readFloat64();
    }
    return list;
  }

  @override
  void writeListBigEndian(ByteWriter bw, TypedDataList<double> v) {
    for (int i = 0; i < v.length; i++) {
      bw.writeFloat64(v[i]);
    }
  }

  @override
  int get bytesPerNum => 8;
}

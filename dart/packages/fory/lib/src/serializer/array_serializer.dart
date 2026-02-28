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
import 'package:fory/src/deserialization_context.dart';
import 'package:fory/src/exception/deserialization_exception.dart';
import 'package:fory/src/memory/byte_reader.dart';
import 'package:fory/src/memory/byte_writer.dart';
import 'package:fory/src/serialization_context.dart';
import 'package:fory/src/util/math_checker.dart';
import 'package:fory/src/config/fory_config.dart';
import 'package:fory/src/serializer/serializer.dart';
import 'package:fory/src/serializer/serializer_cache.dart';

/// Whether the host machine is little-endian
final bool isLittleEndian = Endian.host == Endian.little;

abstract base class ArraySerializerCache extends SerializerCache {
  const ArraySerializerCache();

  @override
  Serializer getSerializer(
    ForyConfig conf,
  ) {
    return getSerializerWithRef(conf.ref);
  }

  Serializer getSerializerWithRef(bool writeRef);
}

abstract base class ArraySerializer<T> extends Serializer<List<T>> {
  const ArraySerializer(super.type, super.writeRef);
}

abstract base class NumericArraySerializer<T extends num>
    extends ArraySerializer<T> {
  const NumericArraySerializer(super.type, super.writeRef);

  /// Reads bytes and converts to a typed list.
  /// Subclasses must handle endianness for multi-byte types.
  TypedDataList<T> readToList(Uint8List copiedMem, ByteReader br);

  int get bytesPerNum;

  @override
  TypedDataList<T> read(ByteReader br, int refId, DeserializationContext pack) {
    int numBytes = br.readVarUint32Small7();
    if (objType == ObjType.BINARY) {
      final int? maxBinarySize = pack.config.maxBinarySize;
      if (maxBinarySize != null && numBytes > maxBinarySize) {
        throw DeserializationSizeException('Binary', numBytes, maxBinarySize);
      }
    }
    int length = numBytes ~/ bytesPerNum;
    if (isLittleEndian || bytesPerNum == 1) {
      // Fast path: direct memory copy on little-endian or for single-byte types
      return readToList(br.copyBytes(numBytes), br);
    } else {
      // Slow path: element-by-element read on big-endian machines
      return readToListBigEndian(length, br);
    }
  }

  /// Read elements one by one on big-endian machines.
  /// Default implementation; subclasses should override for multi-byte types.
  TypedDataList<T> readToListBigEndian(int length, ByteReader br) {
    throw UnsupportedError(
        'readToListBigEndian not implemented for $runtimeType');
  }

  @override
  void write(
      ByteWriter bw, covariant TypedDataList<T> v, SerializationContext pack) {
    if (!MathChecker.validInt32(v.lengthInBytes)) {
      throw ArgumentError(
          'NumArray lengthInBytes is not valid int32: ${v.lengthInBytes}');
    }
    bw.writeVarUint32(v.lengthInBytes);
    if (isLittleEndian || bytesPerNum == 1) {
      // Fast path: direct memory copy on little-endian or for single-byte types
      bw.writeBytes(v.buffer.asUint8List(v.offsetInBytes, v.lengthInBytes));
    } else {
      // Slow path: element-by-element write on big-endian machines
      writeListBigEndian(bw, v);
    }
  }

  /// Write elements one by one on big-endian machines.
  /// Default implementation; subclasses should override for multi-byte types.
  void writeListBigEndian(ByteWriter bw, TypedDataList<T> v) {
    throw UnsupportedError(
        'writeListBigEndian not implemented for $runtimeType');
  }
}

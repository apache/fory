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
import 'package:meta/meta.dart';
import 'package:fory/src/datatype/float16.dart';
import 'package:fory/src/memory/byte_reader_impl.dart';

abstract base class ByteReader {
  @protected
  final Endian endian = Endian.little;

  ByteReader.internal();

  factory ByteReader.forBytes(Uint8List data, {int offset = 0, int? length}) =>
      ByteReaderImpl(data, offset: offset, length: length);

  int get remaining;

  void skip(int length);

  bool readBool();

  /// Reads an unsigned 8-bit integer from the stream.
  int readUint8();

  /// Reads an unsigned 16-bit integer from the stream.
  int readUint16();

  /// Reads an unsigned 32-bit integer from the stream.
  int readUint32();

  /// Reads an unsigned 64-bit integer from the stream.
  int readUint64();

  /// Reads a signed 8-bit integer from the stream.
  int readInt8();

  /// Reads a signed 16-bit integer from the stream.
  int readInt16();

  /// Reads a signed 32-bit integer from the stream.
  int readInt32();

  /// Reads a signed 64-bit integer from the stream.
  int readInt64();

  /// Reads a 32-bit floating point number from the stream.
  double readFloat32();

  /// Reads a 64-bit floating point number from the stream.
  double readFloat64();

  /// Reads a 16-bit floating point number from the stream.
  Float16 readFloat16();

  int readVarUint36Small();

  int readVarInt32();

  int readVarUint32();

  int readVarInt64();

  int readVarUint32Small7();

  int readVarUint32Small14();

  /// read [length] bytes as int64
  int readBytesAsInt64(int length);

  Uint8List readBytesView(int length);

  Uint8List copyBytes(int length);

  Uint16List readCopyUint16List(int byteNum);
}

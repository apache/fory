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
import 'package:fory/src/memory/byte_writer_impl.dart';
import 'package:fory/src/datatype/float16.dart';

abstract base class ByteWriter {
  @protected
  final Endian endian = Endian.little;

  ByteWriter.internal();

  factory ByteWriter() => ByteWriterImpl();

  Uint8List toBytes();
  Uint8List takeBytes();

  void writeBool(bool v);
  void writeUint8(int byte);
  void writeUint16(int value);
  void writeUint32(int value);
  void writeUint64(int value);

  void writeInt8(int value);
  void writeInt16(int value);
  void writeInt32(int value);
  void writeInt64(int value);

  void writeFloat32(double value);
  void writeFloat64(double value);
  void writeFloat16(Float16 value);

  void writeBytes(List<int> bytes);

  void writeVarInt32(int v);
  void writeVarInt64(int v);
  void writeVarUint32Small7(int v);
  void writeVarUint36Small(int v);
  void writeVarUint32(int v);
  void writeVarUint64(int v);
}

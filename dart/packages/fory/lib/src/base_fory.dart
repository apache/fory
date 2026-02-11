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
import 'meta/specs/custom_type_spec.dart';
import 'package:fory/src/memory/byte_writer.dart';
import 'package:fory/src/memory/byte_reader.dart';
import 'package:fory/src/serializer/serializer.dart' show Serializer;

abstract class BaseFory {
  void register(CustomTypeSpec spec, [Object? tagOrTypeId]);
  void registerSerializer(Type type, Serializer ser);
  Object? fromFory(Uint8List bytes, [ByteReader? br]);
  Uint8List toFory(
    Object? obj,
  );
  void toForyWithWriter(Object? obj, ByteWriter writer);
}

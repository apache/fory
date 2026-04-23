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

import 'dart:collection';

import 'package:fory/src/memory/buffer.dart';
import 'package:fory/src/meta/meta_string.dart';

/// Write-side state for meta-string references in one serialization stream.
final class MetaStringWriter {
  final Map<EncodedMetaString, int> _writtenMetaStrings =
      LinkedHashMap<EncodedMetaString, int>.identity();

  /// Clears dynamic ids so the writer can be reused for a new operation.
  void reset() {
    _writtenMetaStrings.clear();
  }

  /// Writes [encoded] using the stream-local meta-string table.
  void writeMetaString(Buffer buffer, EncodedMetaString encoded) {
    final existing = _writtenMetaStrings[encoded];
    if (existing != null) {
      buffer.writeVarUint32Small7(((existing + 1) << 1) | 1);
      return;
    }
    _writtenMetaStrings[encoded] = _writtenMetaStrings.length;
    _writeNewMetaString(buffer, encoded);
  }

  /// Writes [encoded] without using the stream-local meta-string table.
  void writeStandaloneMetaString(
    Buffer buffer,
    EncodedMetaString encoded,
  ) {
    _writeNewMetaString(buffer, encoded);
  }

  void _writeNewMetaString(Buffer buffer, EncodedMetaString encoded) {
    final bytes = encoded.bytes;
    buffer.writeVarUint32Small7(bytes.length << 1);
    if (bytes.isNotEmpty && bytes.length <= metaStringSmallThreshold) {
      buffer.writeByte(encoded.encoding);
    } else if (bytes.length > metaStringSmallThreshold) {
      buffer.writeInt64(encoded.hash);
    }
    buffer.writeBytes(bytes);
  }
}

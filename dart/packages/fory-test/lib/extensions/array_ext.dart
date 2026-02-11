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

extension TypedDataExtensions on TypedDataList {
  /// Compares two [Uint8List]s by comparing 8 bytes at a time.
  bool memEquals(TypedDataList other) {
    if (identical(this, other)) {
      return true;
    }
    if (lengthInBytes != other.lengthInBytes) {
      return false;
    }
    // Treat the original byte lists as lists of 8-byte words.
    var numWords = lengthInBytes ~/ 8;
    var words1 = buffer.asUint64List(0, numWords);
    var words2 = other.buffer.asUint64List(0, numWords);

    for (var i = 0; i < words1.length; i += 1) {
      if (words1[i] != words2[i]) {
        return false;
      }
    }
    // Compare any remaining bytes.
    Uint8List remaining1 = buffer.asUint8List(offsetInBytes + numWords * 8);
    Uint8List remaining2 =
        other.buffer.asUint8List(other.offsetInBytes + numWords * 8);
    for (var i = 0; i < remaining1.length; i += 1) {
      if (remaining1[i] != remaining2[i]) {
        return false;
      }
    }
    return true;
  }
}

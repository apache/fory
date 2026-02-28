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
import 'package:fory/src/util/extension/uint8list_extensions.dart';
import 'package:fory/src/util/hash_util.dart';
import 'package:fory/src/codec/meta_string_encoding.dart';

class MetaString {
  final String value;
  final MetaStringEncoding encoding;
  final int specialChar1;
  final int specialChar2;
  final Uint8List bytes;
  late final bool stripLastChar;

  int? _hash;

  MetaString(this.value, this.encoding, this.specialChar1, this.specialChar2,
      this.bytes) {
    if (encoding != MetaStringEncoding.utf8) {
      // if not utf8, then the bytes should not be empty
      assert(bytes.isNotEmpty);
      stripLastChar = (bytes[0] & 0x80) != 0;
    } else {
      stripLastChar = false;
    }
  }

  @override
  int get hashCode {
    // TODO: Imitate the hash implementation from foryJava, maybe there is a better way
    if (_hash == null) {
      _hash = Object.hash(stripLastChar, encoding, specialChar1, specialChar2);
      _hash = 31 * _hash! + HashUtil.hashIntList(bytes);
    }
    return _hash!;
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other is MetaString &&
            other.runtimeType == runtimeType &&
            other.encoding == encoding &&
            other.stripLastChar == stripLastChar &&
            other.specialChar1 == specialChar1 &&
            other.specialChar2 == specialChar2 &&
            bytes.memEquals(other.bytes));
  }
}

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

import 'package:fory/src/memory/buffer.dart';
import 'package:fory/src/util/string_util.dart';
import 'package:test/test.dart';

void main() {
  group('string encoding', () {
    test('round-trips latin1 strings from the buffer fast path', () {
      const value = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789é';
      final buffer = Buffer();

      writeString(buffer, value);

      final header = buffer.readVarUint36Small();
      final encoding = header & 0x03;
      final byteLength = header >>> 2;
      expect(encoding, equals(stringLatin1Encoding));
      expect(
        readStringFromBuffer(buffer, byteLength, encoding),
        equals(value),
      );
    });

    test('round-trips utf8 strings from the buffer fast path', () {
      const value = '你好，Fory🙂';
      final buffer = Buffer();

      writeString(buffer, value);

      final header = buffer.readVarUint36Small();
      final encoding = header & 0x03;
      final byteLength = header >>> 2;
      expect(encoding, equals(stringUtf8Encoding));
      expect(
        readStringFromBuffer(buffer, byteLength, encoding),
        equals(value),
      );
    });

    test('round-trips empty strings', () {
      const value = '';
      final buffer = Buffer();

      writeString(buffer, value);

      final header = buffer.readVarUint36Small();
      final encoding = header & 0x03;
      final byteLength = header >>> 2;
      expect(byteLength, equals(0));
      expect(
        readStringFromBuffer(buffer, byteLength, encoding),
        equals(value),
      );
    });
  });
}

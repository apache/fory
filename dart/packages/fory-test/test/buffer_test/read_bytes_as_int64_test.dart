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

// @Skip()
library;

import 'package:checks/checks.dart';
import 'package:fory/fory.dart';
import 'package:test/test.dart';

void main() {
  group('ByteReader.readBytesAsInt64', () {
    test('reads values for lengths 1 through 8', () {
      ByteWriter writer = ByteWriter(); // big endian
      // Write test values of different sizes
      writer.writeUint8(0xAB); // 1 byte: 0xAB
      writer.writeUint16(0xCDEF); // 2 bytes: 0xCDEF
      writer.writeBytes([0x56, 0x34, 0x12]); // 3 bytes: 0x123456
      writer.writeUint32(0x789ABCDE); // 4 bytes: 0x789ABCDE
      writer
          .writeBytes([0x55, 0x44, 0x33, 0x22, 0x11]); // 5 bytes: 0x1122334455
      writer.writeBytes(
          [0x11, 0x00, 0x99, 0x88, 0x77, 0x66]); // 6 bytes: 0x667788990011
      writer.writeBytes([
        0x00,
        0xFF,
        0xEE,
        0xDD,
        0xCC,
        0xBB,
        0xAA
      ]); // 7 bytes: 0xAABBCCDDEEFF00
      writer.writeInt64(0x1234567890ABCDEF); // 8 bytes: 0x1234567890ABCDEF

      ByteReader reader = ByteReader.forBytes(writer.takeBytes());

      // Read and verify values
      check(reader.readBytesAsInt64(1)).equals(0xAB);
      check(reader.readBytesAsInt64(2)).equals(0xCDEF);
      check(reader.readBytesAsInt64(3)).equals(0x123456);
      check(reader.readBytesAsInt64(4)).equals(0x789ABCDE);
      check(reader.readBytesAsInt64(5)).equals(0x1122334455);
      check(reader.readBytesAsInt64(6)).equals(0x667788990011);
      check(reader.readBytesAsInt64(7)).equals(0xAABBCCDDEEFF00);
      check(reader.readBytesAsInt64(8)).equals(0x1234567890ABCDEF);
    });

    test('reads each byte correctly', () {
      final testValue = 0x123456789ABCDEF0;

      ByteWriter writer = ByteWriter();
      writer.writeInt64(testValue);

      ByteReader reader = ByteReader.forBytes(writer.takeBytes());

      // Read one byte at a time and verify
      check(reader.readBytesAsInt64(1)).equals(0xF0);
      check(reader.readBytesAsInt64(1)).equals(0xDE);
      check(reader.readBytesAsInt64(1)).equals(0xBC);
      check(reader.readBytesAsInt64(1)).equals(0x9A);
      check(reader.readBytesAsInt64(1)).equals(0x78);
      check(reader.readBytesAsInt64(1)).equals(0x56);
      check(reader.readBytesAsInt64(1)).equals(0x34);
      check(reader.readBytesAsInt64(1)).equals(0x12);
    });
  });
}

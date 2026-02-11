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

import 'dart:math';

import 'package:checks/checks.dart';
import 'package:fory/fory.dart';
import 'package:test/test.dart';

void main() {
  group('VarInt read/write', () {
    test('VarInt32 encode/decode', () {
      ByteWriter bw = ByteWriter();

      // Basic values
      bw.writeVarInt32(-1);
      bw.writeVarInt32(0);
      bw.writeVarInt32(1);

      // Boundary values
      bw.writeVarInt32(-2147483648); // Min int32
      bw.writeVarInt32(2147483647); // Max int32 (0x7FFFFFFF)

      // Powers of 2 to test binary encoding edge cases
      bw.writeVarInt32(128); // 2^7
      bw.writeVarInt32(16384); // 2^14
      bw.writeVarInt32(2097152); // 2^21
      bw.writeVarInt32(268435456); // 2^28

      // Values near encoding boundaries
      bw.writeVarInt32(127); // Just under 2^7
      bw.writeVarInt32(16383); // Just under 2^14
      bw.writeVarInt32(2097151); // Just under 2^21
      bw.writeVarInt32(268435455); // Just under 2^28

      // Read and verify
      ByteReader br = ByteReader.forBytes(bw.takeBytes());
      check(br.readVarInt32()).equals(-1);
      check(br.readVarInt32()).equals(0);
      check(br.readVarInt32()).equals(1);
      check(br.readVarInt32()).equals(-2147483648);
      check(br.readVarInt32()).equals(0x7FFFFFFF);
      check(br.readVarInt32()).equals(128);
      check(br.readVarInt32()).equals(16384);
      check(br.readVarInt32()).equals(2097152);
      check(br.readVarInt32()).equals(268435456);
      check(br.readVarInt32()).equals(127);
      check(br.readVarInt32()).equals(16383);
      check(br.readVarInt32()).equals(2097151);
      check(br.readVarInt32()).equals(268435455);
    });

    test('VarInt64 encode/decode', () {
      ByteWriter bw = ByteWriter();

      // Basic values
      bw.writeVarInt64(-1);
      bw.writeVarInt64(0);
      bw.writeVarInt64(1);

      // Boundary values
      bw.writeVarInt64(-9223372036854775808); // Min int64
      bw.writeVarInt64(9223372036854775807); // Max int64 (0x7FFFFFFFFFFFFFFF)

      // Powers of 2 to test binary encoding edge cases
      bw.writeVarInt64(128); // 2^7
      bw.writeVarInt64(16384); // 2^14
      bw.writeVarInt64(2097152); // 2^21
      bw.writeVarInt64(268435456); // 2^28
      bw.writeVarInt64(34359738368); // 2^35
      bw.writeVarInt64(4398046511104); // 2^42
      bw.writeVarInt64(562949953421312); // 2^49
      bw.writeVarInt64(72057594037927936); // 2^56

      // Large values that test different bytes in 64-bit encoding
      bw.writeVarInt64(1099511627776); // 2^40
      bw.writeVarInt64(-281474976710656); // -2^48

      // Read and verify
      ByteReader br = ByteReader.forBytes(bw.takeBytes());
      check(br.readVarInt64()).equals(-1);
      check(br.readVarInt64()).equals(0);
      check(br.readVarInt64()).equals(1);
      check(br.readVarInt64()).equals(-9223372036854775808);
      check(br.readVarInt64()).equals(0x7FFFFFFFFFFFFFFF);
      check(br.readVarInt64()).equals(128);
      check(br.readVarInt64()).equals(16384);
      check(br.readVarInt64()).equals(2097152);
      check(br.readVarInt64()).equals(268435456);
      check(br.readVarInt64()).equals(34359738368);
      check(br.readVarInt64()).equals(4398046511104);
      check(br.readVarInt64()).equals(562949953421312);
      check(br.readVarInt64()).equals(72057594037927936);
      check(br.readVarInt64()).equals(1099511627776);
      check(br.readVarInt64()).equals(-281474976710656);
    });

    test('VarUint32 encode/decode', () {
      ByteWriter bw = ByteWriter();

      // Basic values
      bw.writeVarUint32(0);
      bw.writeVarUint32(1);

      // Boundary values
      bw.writeVarUint32(4294967295); // Max uint32 (2^32-1)

      // Powers of 2 to test encoding efficiency
      bw.writeVarUint32(128); // 2^7
      bw.writeVarUint32(16384); // 2^14
      bw.writeVarUint32(2097152); // 2^21
      bw.writeVarUint32(268435456); // 2^28

      // Values at encoding boundaries
      bw.writeVarUint32(127); // Max 1-byte
      bw.writeVarUint32(16383); // Max 2-byte
      bw.writeVarUint32(2097151); // Max 3-byte
      bw.writeVarUint32(268435455); // Max 4-byte

      // Read and verify
      ByteReader br = ByteReader.forBytes(bw.takeBytes());
      check(br.readVarUint32()).equals(0);
      check(br.readVarUint32()).equals(1);
      check(br.readVarUint32()).equals(4294967295);
      check(br.readVarUint32()).equals(128);
      check(br.readVarUint32()).equals(16384);
      check(br.readVarUint32()).equals(2097152);
      check(br.readVarUint32()).equals(268435456);
      check(br.readVarUint32()).equals(127);
      check(br.readVarUint32()).equals(16383);
      check(br.readVarUint32()).equals(2097151);
      check(br.readVarUint32()).equals(268435455);
    });

    test('VarUint32Small7 encode/decode', () {
      ByteWriter bw = ByteWriter();

      // Basic values
      bw.writeVarUint32Small7(0);
      bw.writeVarUint32Small7(1);

      // Small values (optimized 7-bit range)
      bw.writeVarUint32Small7(63); // 2^6-1
      bw.writeVarUint32Small7(64); // 2^6
      bw.writeVarUint32Small7(127); // 2^7-1

      // Values beyond 7-bit range
      bw.writeVarUint32Small7(128); // 2^7
      bw.writeVarUint32Small7(255); // 2^8-1
      bw.writeVarUint32Small7(256); // 2^8

      // Medium values
      bw.writeVarUint32Small7(16383); // 2^14-1
      bw.writeVarUint32Small7(16384); // 2^14
      bw.writeVarUint32Small7(65535); // 2^16-1
      bw.writeVarUint32Small7(65536); // 2^16

      // Large values
      bw.writeVarUint32Small7(16777215); // 2^24-1
      bw.writeVarUint32Small7(16777216); // 2^24

      // Boundary values
      bw.writeVarUint32Small7(2147483647); // 2^31-1
      bw.writeVarUint32Small7(4294967295); // MAX uint32 (2^32-1)

      // Read and verify
      ByteReader br = ByteReader.forBytes(bw.takeBytes());
      check(br.readVarUint32Small7()).equals(0);
      check(br.readVarUint32Small7()).equals(1);
      check(br.readVarUint32Small7()).equals(63);
      check(br.readVarUint32Small7()).equals(64);
      check(br.readVarUint32Small7()).equals(127);
      check(br.readVarUint32Small7()).equals(128);
      check(br.readVarUint32Small7()).equals(255);
      check(br.readVarUint32Small7()).equals(256);
      check(br.readVarUint32Small7()).equals(16383);
      check(br.readVarUint32Small7()).equals(16384);
      check(br.readVarUint32Small7()).equals(65535);
      check(br.readVarUint32Small7()).equals(65536);
      check(br.readVarUint32Small7()).equals(16777215);
      check(br.readVarUint32Small7()).equals(16777216);
      check(br.readVarUint32Small7()).equals(2147483647);
      check(br.readVarUint32Small7()).equals(4294967295);
    });

    test('VarUint36Small encode/decode', () {
      ByteWriter bw = ByteWriter();

      // Basic values
      bw.writeVarUint36Small(0);
      bw.writeVarUint36Small(1);

      // Small values (likely optimized range)
      bw.writeVarUint36Small(63); // 2^6-1
      bw.writeVarUint36Small(64); // 2^6
      bw.writeVarUint36Small(127); // 2^7-1
      bw.writeVarUint36Small(128); // 2^7

      // Medium values
      bw.writeVarUint36Small(16383); // 2^14-1
      bw.writeVarUint36Small(16384); // 2^14
      bw.writeVarUint36Small(65535); // 2^16-1
      bw.writeVarUint36Small(65536); // 2^16

      // Large values
      bw.writeVarUint36Small(16777215); // 2^24-1
      bw.writeVarUint36Small(16777216); // 2^24

      bw.writeVarUint36Small(2147483647); // 2^31-1
      bw.writeVarUint36Small(2147483648); // 2^31

      bw.writeVarUint36Small(4294967295); // 2^32-1
      bw.writeVarUint36Small(4294967296); // 2^32

      // Boundary values for 36-bit
      bw.writeVarUint36Small(34359738367); // 2^35-1
      bw.writeVarUint36Small(34359738368); // 2^35
      bw.writeVarUint36Small(68719476735); // MAX 36-bit value (2^36-1)

      // Read and verify
      ByteReader br = ByteReader.forBytes(bw.takeBytes());
      check(br.readVarUint36Small()).equals(0);
      check(br.readVarUint36Small()).equals(1);
      check(br.readVarUint36Small()).equals(63);
      check(br.readVarUint36Small()).equals(64);
      check(br.readVarUint36Small()).equals(127);
      check(br.readVarUint36Small()).equals(128);
      check(br.readVarUint36Small()).equals(16383);
      check(br.readVarUint36Small()).equals(16384);
      check(br.readVarUint36Small()).equals(65535);
      check(br.readVarUint36Small()).equals(65536);
      check(br.readVarUint36Small()).equals(16777215);
      check(br.readVarUint36Small()).equals(16777216);
      check(br.readVarUint36Small()).equals(2147483647);
      check(br.readVarUint36Small()).equals(2147483648);
      check(br.readVarUint36Small()).equals(4294967295);
      check(br.readVarUint36Small()).equals(4294967296);
      check(br.readVarUint36Small()).equals(34359738367);
      check(br.readVarUint36Small()).equals(34359738368);
      check(br.readVarUint36Small()).equals(68719476735);
    });

    test('Random round‑trip values', () {
      final random = Random();
      ByteWriter bw = ByteWriter();

      // Create lists to store test values
      final int32Values = <int>[];
      final int64Values = <int>[];
      final uint32Values = <int>[];

      // Generate random values
      for (int i = 0; i < 100; i++) {
        int32Values
            .add(random.nextInt(0x7FFFFFFF) * (random.nextBool() ? 1 : -1));
        int64Values.add(random.nextInt(0x7FFFFFFF) *
            random.nextInt(0x7FFFFFFF) *
            (random.nextBool() ? 1 : -1));
        uint32Values.add(random.nextInt(0x7FFFFFFF));
      }

      // Write all values
      for (final val in int32Values) {
        bw.writeVarInt32(val);
      }
      for (final val in int64Values) {
        bw.writeVarInt64(val);
      }
      for (final val in uint32Values) {
        bw.writeVarUint32(val);
      }

      // Read and verify all values
      ByteReader br = ByteReader.forBytes(bw.takeBytes());
      for (final expected in int32Values) {
        check(br.readVarInt32()).equals(expected);
      }
      for (final expected in int64Values) {
        check(br.readVarInt64()).equals(expected);
      }
      for (final expected in uint32Values) {
        check(br.readVarUint32()).equals(expected);
      }
    });

    test('Encoding size efficiency', () {
      // Test that numbers are encoded with minimum number of bytes
      ByteWriter bw = ByteWriter();

      // Write values that should encode to specific byte lengths
      bw.writeVarInt32(63); // Should be 1 byte
      bw.writeVarInt32(8191); // Should be 2 bytes
      bw.writeVarInt32(1048575); // Should be 3 bytes
      bw.writeVarInt32(134217727); // Should be 4 bytes

      final bytes = bw.takeBytes();
      // Check total length is 1+2+3+4 = 10 bytes
      check(bytes.length).equals(10);
    });

    test('Consecutive read/write sequence', () {
      // Test the ability to handle sequences of writes followed by sequences of reads
      ByteWriter bw = ByteWriter();

      final testValues = [
        0,
        1,
        -1,
        127,
        128,
        255,
        256,
        32767,
        32768,
        65535,
        65536,
        2147483647,
        -2147483648
      ];

      // Write all values
      for (final val in testValues) {
        bw.writeVarInt32(val);
      }

      // Read all values
      ByteReader br = ByteReader.forBytes(bw.takeBytes());
      for (final expected in testValues) {
        check(br.readVarInt32()).equals(expected);
      }
    });
  });
}

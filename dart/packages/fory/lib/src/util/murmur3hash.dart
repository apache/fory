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
import 'package:fory/src/memory/memory_util.dart';
import 'package:fory/src/util/extension/int_extensions.dart';

class Murmur3Hash {
  static const int defaultSeed = 47;

  static int fmix64(int k) {
    k ^= k >>> 33;
    k *= 0xff51afd7ed558ccd;
    k ^= k >>> 33;
    k *= 0xc4ceb9fe1a85ec53;
    k ^= k >>> 33;
    return k;
  }

  static (int h1, int h2) hash128x64(Uint8List bytes, int len,
      [int offset = 0, int seed = defaultSeed]) {
    int h1 = seed & 0x00000000FFFFFFFF;
    int h2 = seed & 0x00000000FFFFFFFF;

    final int c1 = 0x87c37b91114253d5;
    final int c2 = 0x4cf5ad432745937f;

    int roundedEnd = offset + (len & 0xFFFFFFF0); // round down to 16 byte block

    for (int i = offset; i < roundedEnd; i += 16) {
      int k1 = MemoryUtil.getInt64LittleEndian(bytes, i);
      int k2 = MemoryUtil.getInt64LittleEndian(bytes, i + 8);

      k1 *= c1;
      k1 = k1.rotateLeft(31);
      k1 *= c2;
      h1 ^= k1;
      h1 = h1.rotateLeft(27);
      h1 += h2;
      h1 = h1 * 5 + 0x52dce729;

      k2 *= c2;
      k2 = k2.rotateLeft(33);
      k2 *= c1;
      h2 ^= k2;
      h2 = h2.rotateLeft(31);
      h2 += h1;
      h2 = h2 * 5 + 0x38495ab5;
    }
    int k1 = 0;
    int k2 = 0;

    // dart switch don't support fall through, so we use continue to simulate it
    switch (len & 15) {
      case 15:
        k2 = (bytes[roundedEnd + 14] & 0xff) << 48;
        continue case14;
      case14:
      case 14:
        k2 |= (bytes[roundedEnd + 13] & 0xff) << 40;
        continue case13;
      case13:
      case 13:
        k2 |= (bytes[roundedEnd + 12] & 0xff) << 32;
        continue case12;
      case12:
      case 12:
        k2 |= (bytes[roundedEnd + 11] & 0xff) << 24;
        continue case11;
      case11:
      case 11:
        k2 |= (bytes[roundedEnd + 10] & 0xff) << 16;
        continue case10;
      case10:
      case 10:
        k2 |= (bytes[roundedEnd + 9] & 0xff) << 8;
        continue case9;
      case9:
      case 9:
        k2 |= (bytes[roundedEnd + 8] & 0xff);
        k2 *= c2;
        k2 = k2.rotateLeft(33);
        k2 *= c1;
        h2 ^= k2;
        continue case8;
      case8:
      case 8:
        k1 = (bytes[roundedEnd + 7]) << 56;
        continue case7;
      case7:
      case 7:
        k1 |= (bytes[roundedEnd + 6] & 0xff) << 48;
        continue case6;
      case6:
      case 6:
        k1 |= (bytes[roundedEnd + 5] & 0xff) << 40;
        continue case5;
      case5:
      case 5:
        k1 |= (bytes[roundedEnd + 4] & 0xff) << 32;
        continue case4;
      case4:
      case 4:
        k1 |= (bytes[roundedEnd + 3] & 0xff) << 24;
        continue case3;
      case3:
      case 3:
        k1 |= (bytes[roundedEnd + 2] & 0xff) << 16;
        continue case2;
      case2:
      case 2:
        k1 |= (bytes[roundedEnd + 1] & 0xff) << 8;
        continue case1;
      case1:
      case 1:
        k1 |= (bytes[roundedEnd] & 0xff);
        k1 *= c1;
        k1 = k1.rotateLeft(31);
        k1 *= c2;
        h1 ^= k1;
    }
    // finalization
    h1 ^= len;
    h2 ^= len;

    h1 += h2;
    h2 += h1;

    h1 = fmix64(h1);
    h2 = fmix64(h2);

    h1 += h2;
    h2 += h1;

    return (h1, h2);
  }
}

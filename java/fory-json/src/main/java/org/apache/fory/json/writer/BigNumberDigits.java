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

package org.apache.fory.json.writer;

import java.math.BigInteger;

/**
 * Stateless decimal digit arithmetic shared by concrete writer implementations.
 *
 * <p>Digit-count methods accept non-negative magnitudes and use a bit-length estimate followed by
 * one exact power-of-ten comparison. The helper owns no writer state, buffer, callback, or
 * arbitrary-precision output loop.
 */
final class BigNumberDigits {
  // Packed 1-3 digit stores write one four-byte word; concrete writers reserve this tail once.
  static final int PACKED_WRITE_SLACK = 3;
  static final long[] LONG_POWERS_OF_TEN = {
    1L,
    10L,
    100L,
    1_000L,
    10_000L,
    100_000L,
    1_000_000L,
    10_000_000L,
    100_000_000L,
    1_000_000_000L,
    10_000_000_000L,
    100_000_000_000L,
    1_000_000_000_000L,
    10_000_000_000_000L,
    100_000_000_000_000L,
    1_000_000_000_000_000L,
    10_000_000_000_000_000L,
    100_000_000_000_000_000L,
    1_000_000_000_000_000_000L,
  };

  private BigNumberDigits() {}

  static boolean fitsLong(BigInteger value) {
    return value.bitLength() <= 63;
  }

  static int digitCount(int value) {
    if (value < 10) {
      return 1;
    }
    // 1233 / 4096 approximates log10(2); one power-of-ten comparison makes it exact.
    int estimate = ((33 - Integer.numberOfLeadingZeros(value)) * 1233) >>> 12;
    return value < LONG_POWERS_OF_TEN[estimate] ? estimate : estimate + 1;
  }

  static int digitCount(long value) {
    if (value < 10) {
      return 1;
    }
    int estimate = ((65 - Long.numberOfLeadingZeros(value)) * 1233) >>> 12;
    return estimate >= LONG_POWERS_OF_TEN.length || value < LONG_POWERS_OF_TEN[estimate]
        ? estimate
        : estimate + 1;
  }
}

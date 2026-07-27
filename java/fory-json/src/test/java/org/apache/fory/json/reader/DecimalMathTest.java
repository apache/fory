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

package org.apache.fory.json.reader;

import static org.testng.Assert.assertEquals;

import java.math.BigInteger;
import java.util.SplittableRandom;
import org.testng.annotations.Test;

public class DecimalMathTest {
  private static final BigInteger UNSIGNED_MASK =
      BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

  @Test
  public void unsignedHighHalf() {
    long[] boundaries = {
      0, 1, 2, 0xffff_ffffL, 0x1_0000_0000L, Long.MAX_VALUE, Long.MIN_VALUE, -2, -1
    };
    for (long x : boundaries) {
      for (long y : boundaries) {
        assertProduct(x, y);
      }
    }
    SplittableRandom random = new SplittableRandom(0x5f3759dfL);
    for (int i = 0; i < 10_000; i++) {
      assertProduct(random.nextLong(), random.nextLong());
    }
  }

  private static void assertProduct(long x, long y) {
    BigInteger product = unsigned(x).multiply(unsigned(y));
    assertEquals(DecimalMath.unsignedMultiplyHigh(x, y), product.shiftRight(64).longValue());
  }

  private static BigInteger unsigned(long value) {
    return BigInteger.valueOf(value).and(UNSIGNED_MASK);
  }
}

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

import static org.testng.Assert.assertEquals;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.apache.fory.memory.NativeByteOrder;
import org.apache.fory.platform.JdkVersion;
import org.testng.annotations.Test;

public class FloatingDecimalTest {
  @Test
  public void unsignedMultiplyHigh() {
    Random random = new Random(214671);
    BigInteger mask = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    for (int i = 0; i < 20000; i++) {
      long left = i < 64 ? 1L << i : random.nextLong();
      long right = i < 64 ? ~left : random.nextLong();
      long expected =
          BigInteger.valueOf(left)
              .and(mask)
              .multiply(BigInteger.valueOf(right).and(mask))
              .shiftRight(64)
              .longValue();
      assertEquals(FloatingDecimalMath.unsignedMultiplyHigh(left, right), expected);
    }
  }

  @Test
  public void binaryBoundaries() {
    byte[] bytes = new byte[48];
    for (int bits = 0; bits < 10000; bits++) {
      check(bytes, Float.intBitsToFloat(bits));
      check(bytes, Double.longBitsToDouble(bits));
    }
    for (int exponent = 0; exponent < 255; exponent++) {
      for (int mantissa :
          new int[] {0, 1, 2, 3, 0x3fffff, 0x400000, 0x7ffffd, 0x7ffffe, 0x7fffff}) {
        float value = Float.intBitsToFloat((exponent << 23) | mantissa);
        check(bytes, value);
        check(bytes, -value);
      }
    }
    for (int exponent = 0; exponent < 2047; exponent++) {
      for (long mantissa :
          new long[] {
            0,
            1,
            2,
            3,
            0x7ffffffffffffL,
            0x8000000000000L,
            0xffffffffffffdL,
            0xffffffffffffeL,
            0xfffffffffffffL
          }) {
        double value = Double.longBitsToDouble(((long) exponent << 52) | mantissa);
        check(bytes, value);
        check(bytes, -value);
      }
    }
  }

  @Test
  public void decimalBoundaries() {
    byte[] bytes = new byte[48];
    for (int exponent = -324; exponent <= 308; exponent++) {
      double center = Double.parseDouble("1e" + exponent);
      for (int i = 0; i < 20; i++) {
        if (Double.isFinite(center)) {
          check(bytes, center);
          check(bytes, -center);
          check(bytes, Math.nextDown(center));
        }
        center = Math.nextUp(center);
      }
    }
    for (int exponent = -45; exponent <= 38; exponent++) {
      float center = Float.parseFloat("1e" + exponent);
      for (int i = 0; i < 20; i++) {
        if (Float.isFinite(center)) {
          check(bytes, center);
          check(bytes, -center);
          check(bytes, Math.nextDown(center));
        }
        center = Math.nextUp(center);
      }
    }
  }

  @Test
  public void randomValues() {
    byte[] bytes = new byte[48];
    Random random = new Random(831098912341L);
    for (int i = 0; i < 500000; i++) {
      float single = Float.intBitsToFloat(random.nextInt());
      double twice = Double.longBitsToDouble(random.nextLong());
      if (Float.isFinite(single)) {
        check(bytes, single);
      }
      if (Double.isFinite(twice)) {
        check(bytes, twice);
      }
    }
  }

  @Test
  public void outputBounds() {
    for (double value :
        new double[] {
          -0.0,
          Double.MIN_VALUE,
          -Double.MIN_VALUE,
          Double.MAX_VALUE,
          -Double.MAX_VALUE,
          0.001,
          0.0001,
          9999999,
          1e7,
          12345.6789
        }) {
      for (int prefix = 0; prefix < 8; prefix++) {
        byte[] bytes = new byte[prefix + 24];
        int end = FloatingDecimal.write(bytes, prefix, value);
        String expected = new String(bytes, prefix, end - prefix, StandardCharsets.US_ASCII);
        byte[] wide = new byte[prefix * 2 + 48];
        int wideEnd = FloatingDecimal.writeUtf16(wide, prefix * 2, value);
        assertEquals(
            new String(
                wide,
                prefix * 2,
                wideEnd - prefix * 2,
                NativeByteOrder.IS_LITTLE_ENDIAN
                    ? StandardCharsets.UTF_16LE
                    : StandardCharsets.UTF_16BE),
            expected);
      }
    }
    for (float value :
        new float[] {
          -0.0f,
          Float.MIN_VALUE,
          -Float.MIN_VALUE,
          Float.MAX_VALUE,
          -Float.MAX_VALUE,
          0.001f,
          0.0001f,
          9999999f,
          1e7f,
          12345.6789f
        }) {
      for (int prefix = 0; prefix < 8; prefix++) {
        byte[] bytes = new byte[prefix + 16];
        int end = FloatingDecimal.write(bytes, prefix, value);
        String expected = new String(bytes, prefix, end - prefix, StandardCharsets.US_ASCII);
        byte[] wide = new byte[prefix * 2 + 32];
        int wideEnd = FloatingDecimal.writeUtf16(wide, prefix * 2, value);
        assertEquals(
            new String(
                wide,
                prefix * 2,
                wideEnd - prefix * 2,
                NativeByteOrder.IS_LITTLE_ENDIAN
                    ? StandardCharsets.UTF_16LE
                    : StandardCharsets.UTF_16BE),
            expected);
      }
    }
  }

  private static void check(byte[] bytes, float value) {
    int end = FloatingDecimal.write(bytes, 0, value);
    String actual = new String(bytes, 0, end, StandardCharsets.US_ASCII);
    assertEquals(
        Float.floatToRawIntBits(Float.parseFloat(actual)), Float.floatToRawIntBits(value), actual);
    if (JdkVersion.MAJOR_VERSION >= 19) {
      assertEquals(
          actual,
          Float.toString(value),
          "bits=" + Integer.toHexString(Float.floatToRawIntBits(value)));
    }
  }

  private static void check(byte[] bytes, double value) {
    int end = FloatingDecimal.write(bytes, 0, value);
    String actual = new String(bytes, 0, end, StandardCharsets.US_ASCII);
    assertEquals(
        Double.doubleToRawLongBits(Double.parseDouble(actual)),
        Double.doubleToRawLongBits(value),
        actual);
    if (JdkVersion.MAJOR_VERSION >= 19) {
      assertEquals(
          actual,
          Double.toString(value),
          "bits=" + Long.toHexString(Double.doubleToRawLongBits(value)));
    }
  }
}

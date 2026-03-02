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

package org.apache.fory.type;

import static org.testng.Assert.*;

import org.testng.annotations.Test;

public class Float16Test {

  @Test
  public void testSpecialValues() {
    assertTrue(Float16.NaN.isNaN());
    assertFalse(Float16.NaN.equalsValue(Float16.NaN));
    assertFalse(Float16.NaN.isFinite());
    assertFalse(Float16.NaN.isInfinite());

    assertTrue(Float16.POSITIVE_INFINITY.isInfinite());
    assertTrue(Float16.NEGATIVE_INFINITY.isInfinite());
    assertFalse(Float16.POSITIVE_INFINITY.signbit());
    assertTrue(Float16.NEGATIVE_INFINITY.signbit());
    assertFalse(Float16.POSITIVE_INFINITY.isFinite());

    assertTrue(Float16.ZERO.isZero());
    assertTrue(Float16.NEGATIVE_ZERO.isZero());
    assertTrue(Float16.ZERO.equalsValue(Float16.NEGATIVE_ZERO));
    assertFalse(Float16.ZERO.signbit());
    assertTrue(Float16.NEGATIVE_ZERO.signbit());

    assertFalse(Float16.ONE.isZero());
    assertTrue(Float16.ONE.isNormal());
    assertEquals(1.0f, Float16.ONE.floatValue(), 0.0f);
  }

  @Test
  public void testConversionBasic() {
    assertEquals((short) 0x0000, Float16.valueOf(0.0f).toBits());
    assertEquals((short) 0x8000, Float16.valueOf(-0.0f).toBits());

    assertEquals((short) 0x3c00, Float16.valueOf(1.0f).toBits());
    assertEquals((short) 0xbc00, Float16.valueOf(-1.0f).toBits());
    assertEquals((short) 0x4000, Float16.valueOf(2.0f).toBits());

    assertEquals(1.0f, Float16.valueOf(1.0f).floatValue(), 0.0f);
    assertEquals(-1.0f, Float16.valueOf(-1.0f).floatValue(), 0.0f);
    assertEquals(2.0f, Float16.valueOf(2.0f).floatValue(), 0.0f);
  }

  @Test
  public void testBoundaryValues() {
    Float16 max = Float16.valueOf(65504.0f);
    assertEquals(0x7bff, max.toBits());
    assertEquals(65504.0f, max.floatValue(), 0.0f);
    assertTrue(max.isNormal());
    assertTrue(max.isFinite());

    Float16 minNormal = Float16.valueOf((float) Math.pow(2, -14));
    assertEquals(0x0400, minNormal.toBits());
    assertTrue(minNormal.isNormal());
    assertFalse(minNormal.isSubnormal());

    Float16 minSubnormal = Float16.valueOf((float) Math.pow(2, -24));
    assertEquals(0x0001, minSubnormal.toBits());
    assertTrue(minSubnormal.isSubnormal());
    assertFalse(minSubnormal.isNormal());
  }

  @Test
  public void testSubnormalDecodeFromBits() {
    assertEquals(Float16.fromBits((short) 0x0001).floatValue(), 5.9604645E-8f, 0.0f);
    assertEquals(Float16.fromBits((short) 0x03FF).floatValue(), 6.097555E-5f, 0.0f);
  }

  @Test
  public void testOverflowUnderflow() {
    Float16 overflow = Float16.valueOf(70000.0f);
    assertTrue(overflow.isInfinite());
    assertFalse(overflow.signbit());

    Float16 negOverflow = Float16.valueOf(-70000.0f);
    assertTrue(negOverflow.isInfinite());
    assertTrue(negOverflow.signbit());

    Float16 underflow = Float16.valueOf((float) Math.pow(2, -30));
    assertTrue(underflow.isZero());
  }

  @Test
  public void testRoundingToNearestEven() {
    Float16 rounded = Float16.valueOf(1.0009765625f);
    assertTrue(Math.abs(rounded.floatValue() - 1.0009765625f) < 0.01f);
  }

  @Test
  public void testArithmetic() {
    Float16 one = Float16.ONE;
    Float16 two = Float16.valueOf(2.0f);
    Float16 three = Float16.valueOf(3.0f);

    assertEquals(3.0f, one.add(two).floatValue(), 0.01f);
    assertEquals(5.0f, two.add(three).floatValue(), 0.01f);

    assertEquals(-1.0f, one.subtract(two).floatValue(), 0.01f);
    assertEquals(1.0f, three.subtract(two).floatValue(), 0.01f);

    assertEquals(2.0f, one.multiply(two).floatValue(), 0.01f);
    assertEquals(6.0f, two.multiply(three).floatValue(), 0.01f);

    assertEquals(0.5f, one.divide(two).floatValue(), 0.01f);
    assertEquals(1.5f, three.divide(two).floatValue(), 0.01f);

    assertEquals(-1.0f, one.negate().floatValue(), 0.0f);
    assertEquals(1.0f, one.negate().negate().floatValue(), 0.0f);

    assertEquals(1.0f, one.abs().floatValue(), 0.0f);
    assertEquals(1.0f, one.negate().abs().floatValue(), 0.0f);
  }

  @Test
  public void testComparison() {
    Float16 one = Float16.ONE;
    Float16 two = Float16.valueOf(2.0f);
    Float16 nan = Float16.NaN;

    assertTrue(one.compareTo(two) < 0);
    assertTrue(two.compareTo(one) > 0);
    assertEquals(0, one.compareTo(Float16.ONE));

    assertTrue(one.equalsValue(Float16.ONE));
    assertFalse(nan.equalsValue(nan));
    assertTrue(Float16.ZERO.equalsValue(Float16.NEGATIVE_ZERO));

    assertTrue(one.equals(Float16.ONE));
    assertFalse(Float16.ZERO.equals(Float16.NEGATIVE_ZERO));

    assertTrue(one.lessThan(two));
    assertFalse(two.lessThan(one));
    assertFalse(nan.lessThan(one));
    assertFalse(one.lessThan(nan));

    assertTrue(one.lessThanOrEqual(two));
    assertTrue(one.lessThanOrEqual(Float16.ONE));
    assertFalse(nan.lessThanOrEqual(one));

    assertTrue(two.greaterThan(one));
    assertFalse(one.greaterThan(two));
    assertFalse(nan.greaterThan(one));

    assertTrue(two.greaterThanOrEqual(one));
    assertTrue(one.greaterThanOrEqual(Float16.ONE));
    assertFalse(nan.greaterThanOrEqual(one));

    assertTrue(Float16.compare(one, two) < 0);
    assertTrue(Float16.compare(two, one) > 0);
    assertEquals(0, Float16.compare(one, Float16.ONE));
  }

  @Test
  public void testParse() {
    assertEquals(1.0f, Float16.parse("1.0").floatValue(), 0.0f);
    assertEquals(2.5f, Float16.parse("2.5").floatValue(), 0.01f);
    assertEquals(-3.14f, Float16.parse("-3.14").floatValue(), 0.01f);
    assertTrue(Float16.parse("NaN").isNaN());
    assertTrue(Float16.parse("Infinity").isInfinite());
    assertTrue(Float16.parse("-Infinity").isInfinite());
  }

  @Test
  public void testToFloat() {
    Float16 f16 = Float16.valueOf(3.14f);
    assertEquals(f16.floatValue(), f16.toFloat(), 0.0f);
  }

  @Test
  public void testClassification() {
    assertTrue(Float16.ONE.isNormal());
    assertFalse(Float16.ZERO.isNormal());
    assertFalse(Float16.NaN.isNormal());
    assertFalse(Float16.POSITIVE_INFINITY.isNormal());

    Float16 subnormal = Float16.valueOf((float) Math.pow(2, -24));
    assertTrue(subnormal.isSubnormal());
    assertFalse(Float16.ONE.isSubnormal());

    assertTrue(Float16.ONE.isFinite());
    assertTrue(Float16.ZERO.isFinite());
    assertFalse(Float16.NaN.isFinite());
    assertFalse(Float16.POSITIVE_INFINITY.isFinite());

    assertFalse(Float16.ONE.signbit());
    assertTrue(Float16.valueOf(-1.0f).signbit());
    assertFalse(Float16.ZERO.signbit());
    assertTrue(Float16.NEGATIVE_ZERO.signbit());
  }

  @Test
  public void testToString() {
    assertEquals("1.0", Float16.ONE.toString());
    assertEquals("2.0", Float16.valueOf(2.0f).toString());
    assertTrue(Float16.NaN.toString().contains("NaN"));
    assertTrue(Float16.POSITIVE_INFINITY.toString().contains("Infinity"));
  }

  @Test
  public void testHashCode() {
    assertEquals(Float16.ONE.hashCode(), Float16.valueOf(1.0f).hashCode());

    assertNotEquals(Float16.ONE.hashCode(), Float16.valueOf(2.0f).hashCode());
  }

  @Test
  public void testNumberConversions() {
    Float16 f16 = Float16.valueOf(3.14f);

    assertEquals(3.14f, f16.floatValue(), 0.01f);

    assertEquals(3.14, f16.doubleValue(), 0.01);

    assertEquals(3, f16.intValue());

    assertEquals(3L, f16.longValue());

    assertEquals((byte) 3, f16.byteValue());

    assertEquals((short) 3, f16.shortValue());
  }

  @Test
  public void testAllBitPatterns() {
    int nanCount = 0;
    int normalCount = 0;
    int subnormalCount = 0;
    int zeroCount = 0;
    int infCount = 0;

    for (int bits = 0; bits <= 0xFFFF; bits++) {
      Float16 h = Float16.fromBits((short) bits);
      float f = h.floatValue();
      Float16 h2 = Float16.valueOf(f);

      if (h.isNaN()) {
        nanCount++;
        assertTrue(h2.isNaN(), "NaN should remain NaN after round-trip");
      } else {
        if (!h.equals(h2)) {
          assertEquals(
              h.floatValue(),
              h2.floatValue(),
              0.0001f,
              String.format("Round-trip failed for bits 0x%04x", bits));
        }

        if (h.isNormal()) {
          normalCount++;
        } else if (h.isSubnormal()) {
          subnormalCount++;
        } else if (h.isZero()) {
          zeroCount++;
        } else if (h.isInfinite()) {
          infCount++;
        }
      }
    }

    assertTrue(nanCount > 0, "Should have NaN values");
    assertTrue(normalCount > 0, "Should have normal values");
    assertTrue(subnormalCount > 0, "Should have subnormal values");
    assertEquals(2, zeroCount, "Should have exactly 2 zeros (+0 and -0)");
    assertEquals(2, infCount, "Should have exactly 2 infinities (+Inf and -Inf)");

    int total = nanCount + normalCount + subnormalCount + zeroCount + infCount;
    assertEquals(65536, total, "Total should be 65536");
  }

  @Test
  public void testConstants() {
    assertEquals((short) 0x7e00, Float16.NaN.toBits());
    assertEquals((short) 0x7c00, Float16.POSITIVE_INFINITY.toBits());
    assertEquals((short) 0xfc00, Float16.NEGATIVE_INFINITY.toBits());
    assertEquals((short) 0x0000, Float16.ZERO.toBits());
    assertEquals((short) 0x8000, Float16.NEGATIVE_ZERO.toBits());
    assertEquals((short) 0x3c00, Float16.ONE.toBits());
    assertEquals((short) 0x7bff, Float16.MAX_VALUE.toBits());
    assertEquals((short) 0x0400, Float16.MIN_NORMAL.toBits());
    assertEquals((short) 0x0001, Float16.MIN_VALUE.toBits());
  }

  @Test
  public void testSizeConstants() {
    assertEquals(16, Float16.SIZE_BITS);
    assertEquals(2, Float16.SIZE_BYTES);
  }
}

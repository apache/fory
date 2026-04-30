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

package org.apache.fory.type.unsigned;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.fory.Fory;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.annotation.UInt16Type;
import org.apache.fory.annotation.UInt32Type;
import org.apache.fory.annotation.UInt64Type;
import org.apache.fory.annotation.UInt8Type;
import org.apache.fory.config.ForyBuilder;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class UnsignedTest {
  @Test
  public void uint8ParsingAndFormatting() {
    UInt8 value = UInt8.parse("255");
    assertEquals(value.toInt(), 255);
    assertEquals(UInt8.parse("ff", 16).toInt(), 255);
    assertEquals(value.toUnsignedString(16), "ff");
    assertEquals(value.toHexString(), "ff");
    assertTrue(UInt8.parse("0").isZero());
    assertTrue(UInt8.MAX_VALUE.isMaxValue());
  }

  @Test(expectedExceptions = NumberFormatException.class)
  public void uint8ParseOutOfRange() {
    UInt8.parse("256");
  }

  @Test
  public void uint8ArithmeticAndBitwise() {
    UInt8 a = UInt8.parse("255");
    UInt8 b = UInt8.valueOf(1);

    assertEquals(a.add(b).toInt(), 0); // wraps
    assertEquals(UInt8.MIN_VALUE.subtract(b).toInt(), 255);
    assertEquals(a.divide(UInt8.valueOf(2)).toInt(), 127);
    assertEquals(a.remainder(UInt8.valueOf(2)).toInt(), 1);

    UInt8 lo = UInt8.valueOf(0x0F);
    UInt8 hi = UInt8.valueOf(0xF0);
    assertEquals(lo.or(hi).toInt(), 0xFF);
    assertEquals(hi.and(lo).toInt(), 0);
    assertEquals(lo.xor(hi).toInt(), 0xFF);
    assertEquals(lo.not().toInt(), 0xF0);

    assertEquals(UInt8.valueOf(0x81).shiftRight(1).toInt(), 0x40);
    assertEquals(UInt8.valueOf(0x01).shiftLeft(7).toInt(), 0x80);
  }

  @Test
  public void uint16ParsingAndFormatting() {
    UInt16 value = UInt16.parse("65535");
    assertEquals(value.toInt(), 65535);
    assertEquals(UInt16.parse("ffff", 16).toInt(), 65535);
    assertEquals(value.toUnsignedString(16), "ffff");
    assertEquals(value.toHexString(), "ffff");
    assertTrue(UInt16.parse("0").isZero());
    assertTrue(UInt16.MAX_VALUE.isMaxValue());
  }

  @Test(expectedExceptions = NumberFormatException.class)
  public void uint16ParseOutOfRange() {
    UInt16.parse("65536");
  }

  @Test
  public void uint16ArithmeticAndBitwise() {
    UInt16 a = UInt16.parse("65535");
    UInt16 b = UInt16.valueOf(1);

    assertEquals(a.add(b).toInt(), 0); // wraps
    assertEquals(UInt16.MIN_VALUE.subtract(b).toInt(), 65535);
    assertEquals(a.divide(UInt16.valueOf(2)).toInt(), 32767);
    assertEquals(a.remainder(UInt16.valueOf(2)).toInt(), 1);

    UInt16 lo = UInt16.valueOf(0x00FF);
    UInt16 hi = UInt16.valueOf(0xFF00);
    assertEquals(lo.or(hi).toInt(), 0xFFFF);
    assertEquals(hi.and(lo).toInt(), 0);
    assertEquals(lo.xor(hi).toInt(), 0xFFFF);
    assertEquals(lo.not().toInt(), 0xFF00);

    assertEquals(UInt16.valueOf(0x8001).shiftRight(1).toInt(), 0x4000);
    assertEquals(UInt16.valueOf(0x0001).shiftLeft(15).toInt(), 0x8000);
  }

  @Test
  public void uint32ParsingAndArithmetic() {
    UInt32 value = UInt32.parse("4294967295");
    assertEquals(value.toLong(), 4294967295L);
    assertEquals(UInt32.parse("ffffffff", 16).toLong(), 4294967295L);
    assertEquals(value.toUnsignedString(16), "ffffffff");
    assertEquals(value.toHexString(), "ffffffff");
    assertFalse(value.isZero());
    assertTrue(value.isMaxValue());

    UInt32 wrap = UInt32.valueOf(-1); // MAX
    assertEquals(wrap.add(UInt32.valueOf(1)).toLong(), 0L);
    assertEquals(wrap.divide(UInt32.valueOf(2)).toLong(), 2147483647L);
    assertEquals(wrap.remainder(UInt32.valueOf(2)).toLong(), 1L);

    UInt32 lo = UInt32.valueOf(0x00FF00FF);
    UInt32 hi = UInt32.valueOf(0xFF00FF00);
    assertEquals(lo.or(hi).toLong(), 0xFFFFFFFFL);
    assertEquals(hi.and(lo).toLong(), 0L);
    assertEquals(lo.xor(hi).toLong(), 0xFFFFFFFFL);
    assertEquals(lo.not().toLong(), 0xFF00FF00L);

    assertEquals(UInt32.valueOf(0x80000001).shiftRight(1).toLong(), 0x40000000L);
    assertEquals(UInt32.valueOf(0x00000001).shiftLeft(31).toLong(), 0x80000000L);
  }

  @Test
  public void uint64ParsingAndArithmetic() {
    UInt64 value = UInt64.parse("18446744073709551615");
    assertEquals(value.toUnsignedString(16), "ffffffffffffffff");
    assertEquals(value.toHexString(), "ffffffffffffffff");
    assertFalse(value.isZero());
    assertTrue(value.isMaxValue());

    UInt64 wrap = UInt64.valueOf(-1L); // MAX
    assertEquals(wrap.add(UInt64.valueOf(1)).longValue(), 0L);
    assertEquals(
        wrap.divide(UInt64.valueOf(2)).longValue(), Long.parseUnsignedLong("9223372036854775807"));
    assertEquals(wrap.remainder(UInt64.valueOf(2)).longValue(), 1L);

    UInt64 lo = UInt64.valueOf(0x00FF00FF00FF00FFL);
    UInt64 hi = UInt64.valueOf(0xFF00FF00FF00FF00L);
    assertEquals(lo.or(hi).toLong(), -1L);
    assertEquals(hi.and(lo).toLong(), 0L);
    assertEquals(lo.xor(hi).toLong(), -1L);
    assertEquals(lo.not().toLong(), 0xFF00FF00FF00FF00L);

    assertEquals(UInt64.valueOf(0x8000000000000001L).shiftRight(1).toLong(), 0x4000000000000000L);
    assertEquals(UInt64.valueOf(0x0000000000000001L).shiftLeft(63).toLong(), Long.MIN_VALUE);
  }

  // POJO1: Using UInt8/16/32/64 wrapper types as fields
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class UIntPojo {
    @ForyField(nullable = false)
    private UInt8 uInt8Field;

    @ForyField(nullable = false)
    private UInt16 uInt16Field;

    @ForyField(nullable = false)
    private UInt32 uInt32Field;

    @ForyField(nullable = false)
    private UInt64 uInt64Field;
  }

  // POJO2: Using primitive types with UInt annotations (primitives are never nullable)
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class PrimitiveUIntPojo {
    @UInt8Type private int uInt8Field;

    @UInt16Type private int uInt16Field;

    @UInt32Type private long uInt32Field;

    @UInt64Type private long uInt64Field;
  }

  // POJO3: Using boxed types with UInt annotations and non-nullable
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class BoxedUIntPojo {
    @UInt8Type
    @ForyField(nullable = false)
    private Integer uInt8Field;

    @UInt16Type
    @ForyField(nullable = false)
    private Integer uInt16Field;

    @UInt32Type
    @ForyField(nullable = false)
    private Long uInt32Field;

    @UInt64Type
    @ForyField(nullable = false)
    private Long uInt64Field;
  }

  @DataProvider(name = "configProvider")
  public Object[][] configProvider() {
    return new Object[][] {
      {false, false}, // codegen=false, xlang=false
      {true, false}, // codegen=true, xlang=false
      {false, true}, // codegen=false, xlang=true
      {true, true} // codegen=true, xlang=true
    };
  }

  @Test(dataProvider = "configProvider")
  public void testUIntPojoSerialization(boolean enableCodegen, boolean xlang) {
    ForyBuilder builder =
        Fory.builder().withXlang(xlang).withCodegen(enableCodegen).requireClassRegistration(false);
    Fory fory = builder.build();

    // Register UInt serializers
    UnsignedSerializers.registerSerializers(fory.getTypeResolver());

    // Register the UInt POJO class
    fory.register(UIntPojo.class);

    // Create test data with UInt wrapper types
    UIntPojo original =
        new UIntPojo(
            UInt8.valueOf(255), // max value
            UInt16.valueOf(65535), // max value
            UInt32.valueOf(-1), // max value (4294967295)
            UInt64.valueOf(-1L) // max value (18446744073709551615)
            );

    // Serialize and deserialize
    byte[] bytes = fory.serialize(original);
    UIntPojo deserialized = (UIntPojo) fory.deserialize(bytes);

    // Verify - values preserved as unsigned
    assertEquals(deserialized.getUInt8Field().toInt(), 255);
    assertEquals(deserialized.getUInt16Field().toInt(), 65535);
    assertEquals(deserialized.getUInt32Field().toLong(), 4294967295L);
    assertEquals(deserialized.getUInt64Field().toUnsignedString(10), "18446744073709551615");
  }

  @Test(dataProvider = "configProvider")
  public void testPrimitiveUIntPojoSerialization(boolean enableCodegen, boolean xlang) {
    ForyBuilder builder =
        Fory.builder().withXlang(xlang).withCodegen(enableCodegen).requireClassRegistration(false);
    Fory fory = builder.build();

    // Register the primitive POJO class
    fory.register(PrimitiveUIntPojo.class);

    // Create test data with primitive types (will be interpreted as unsigned)
    PrimitiveUIntPojo original =
        new PrimitiveUIntPojo(
            255,
            65535,
            4_294_967_295L,
            -1L // -1 as signed long, but 18446744073709551615 as unsigned
            );

    // Serialize and deserialize
    byte[] bytes = fory.serialize(original);
    PrimitiveUIntPojo deserialized = (PrimitiveUIntPojo) fory.deserialize(bytes);

    // Verify - values are preserved as unsigned
    assertEquals(deserialized.getUInt8Field(), 255);
    assertEquals(deserialized.getUInt16Field(), 65535);
    assertEquals(deserialized.getUInt32Field(), 4294967295L);
    assertEquals(Long.toUnsignedString(deserialized.getUInt64Field()), "18446744073709551615");
  }

  @Test(dataProvider = "configProvider")
  public void testAllPojoTypes(boolean enableCodegen, boolean xlang) {
    ForyBuilder builder =
        Fory.builder().withXlang(xlang).withCodegen(enableCodegen).requireClassRegistration(false);
    Fory fory = builder.build();

    // Register UInt serializers
    UnsignedSerializers.registerSerializers(fory.getTypeResolver());

    // Register all POJO classes
    fory.register(UIntPojo.class);
    fory.register(PrimitiveUIntPojo.class);
    fory.register(BoxedUIntPojo.class);

    // Test UIntPojo with UInt wrapper types
    UIntPojo uintPojo =
        new UIntPojo(
            UInt8.valueOf(200),
            UInt16.valueOf(50000),
            UInt32.valueOf((int) 3000000000L),
            UInt64.valueOf(-1000L));

    byte[] bytes1 = fory.serialize(uintPojo);
    UIntPojo deserialized1 = (UIntPojo) fory.deserialize(bytes1);
    assertEquals(deserialized1.getUInt8Field().toInt(), 200);
    assertEquals(deserialized1.getUInt16Field().toInt(), 50000);
    assertEquals(deserialized1.getUInt32Field().toLong(), 3000000000L);
    assertEquals(deserialized1.getUInt64Field().toLong(), -1000L);

    // Test PrimitiveUIntPojo with primitives
    PrimitiveUIntPojo primitivePojo = new PrimitiveUIntPojo(200, 50000, 3_000_000_000L, -1000L);

    byte[] bytes2 = fory.serialize(primitivePojo);
    PrimitiveUIntPojo deserialized2 = (PrimitiveUIntPojo) fory.deserialize(bytes2);
    assertEquals(deserialized2.getUInt8Field(), 200);
    assertEquals(deserialized2.getUInt16Field(), 50000);
    assertEquals(deserialized2.getUInt32Field(), 3000000000L);
    assertEquals(deserialized2.getUInt64Field(), -1000L);

    // Test BoxedUIntPojo with boxed types
    BoxedUIntPojo boxedPojo = new BoxedUIntPojo(200, 50000, 3_000_000_000L, -1000L);

    byte[] bytes3 = fory.serialize(boxedPojo);
    BoxedUIntPojo deserialized3 = (BoxedUIntPojo) fory.deserialize(bytes3);
    assertEquals(deserialized3.getUInt8Field(), Integer.valueOf(200));
    assertEquals(deserialized3.getUInt16Field(), Integer.valueOf(50000));
    assertEquals(deserialized3.getUInt32Field(), Long.valueOf(3000000000L));
    assertEquals(deserialized3.getUInt64Field(), -1000L);
  }

  @Test(dataProvider = "configProvider")
  public void testUIntPojoWithZeroValues(boolean enableCodegen, boolean xlang) {
    ForyBuilder builder =
        Fory.builder().withXlang(xlang).withCodegen(enableCodegen).requireClassRegistration(false);
    Fory fory = builder.build();

    // Register UInt serializers
    UnsignedSerializers.registerSerializers(fory.getTypeResolver());

    // Register the UInt POJO class
    fory.register(UIntPojo.class);

    // Test with zero/min values
    UIntPojo original =
        new UIntPojo(UInt8.valueOf(0), UInt16.valueOf(0), UInt32.valueOf(0), UInt64.valueOf(0));

    byte[] bytes = fory.serialize(original);
    UIntPojo deserialized = (UIntPojo) fory.deserialize(bytes);

    assertTrue(deserialized.getUInt8Field().isZero());
    assertTrue(deserialized.getUInt16Field().isZero());
    assertTrue(deserialized.getUInt32Field().isZero());
    assertTrue(deserialized.getUInt64Field().isZero());
  }

  @Test(dataProvider = "configProvider")
  public void testMaxValuesRoundTrip(boolean enableCodegen, boolean xlang) {
    ForyBuilder builder =
        Fory.builder().withXlang(xlang).withCodegen(enableCodegen).requireClassRegistration(false);
    Fory fory = builder.build();

    // Register UInt serializers
    UnsignedSerializers.registerSerializers(fory.getTypeResolver());

    // Register all POJO classes
    fory.register(UIntPojo.class);
    fory.register(PrimitiveUIntPojo.class);
    fory.register(BoxedUIntPojo.class);

    // Test UIntPojo with max unsigned values using UInt wrapper types
    UIntPojo originalUint =
        new UIntPojo(
            UInt8.valueOf(255), UInt16.valueOf(65535), UInt32.valueOf(-1), UInt64.valueOf(-1L));

    byte[] bytes1 = fory.serialize(originalUint);
    UIntPojo roundTrippedUint = (UIntPojo) fory.deserialize(bytes1);

    assertEquals(roundTrippedUint.getUInt8Field().toInt(), 255);
    assertEquals(roundTrippedUint.getUInt16Field().toInt(), 65535);
    assertEquals(roundTrippedUint.getUInt32Field().toLong(), 4294967295L);
    assertEquals(roundTrippedUint.getUInt64Field().toUnsignedString(10), "18446744073709551615");

    // Test PrimitiveUIntPojo with primitives
    PrimitiveUIntPojo originalPrimitive =
        new PrimitiveUIntPojo(255, 65535, 4_294_967_295L, -1L); // 18446744073709551615 as unsigned

    byte[] bytes2 = fory.serialize(originalPrimitive);
    PrimitiveUIntPojo roundTrippedPrimitive = (PrimitiveUIntPojo) fory.deserialize(bytes2);

    assertEquals(roundTrippedPrimitive.getUInt8Field(), 255);
    assertEquals(roundTrippedPrimitive.getUInt16Field(), 65535);
    assertEquals(roundTrippedPrimitive.getUInt32Field(), 4294967295L);
    assertEquals(
        Long.toUnsignedString(roundTrippedPrimitive.getUInt64Field()), "18446744073709551615");

    // Test BoxedUIntPojo with boxed types
    BoxedUIntPojo originalBoxed =
        new BoxedUIntPojo(255, 65535, 4_294_967_295L, -1L); // 18446744073709551615 as unsigned

    byte[] bytes3 = fory.serialize(originalBoxed);
    BoxedUIntPojo roundTrippedBoxed = (BoxedUIntPojo) fory.deserialize(bytes3);

    assertEquals(roundTrippedBoxed.getUInt8Field(), Integer.valueOf(255));
    assertEquals(roundTrippedBoxed.getUInt16Field(), Integer.valueOf(65535));
    assertEquals(roundTrippedBoxed.getUInt32Field(), Long.valueOf(4294967295L));
    assertEquals(Long.toUnsignedString(roundTrippedBoxed.getUInt64Field()), "18446744073709551615");
  }
}

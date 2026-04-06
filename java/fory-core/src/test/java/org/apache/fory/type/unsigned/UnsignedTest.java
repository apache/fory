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
import org.apache.fory.annotation.Uint16Type;
import org.apache.fory.annotation.Uint32Type;
import org.apache.fory.annotation.Uint64Type;
import org.apache.fory.annotation.Uint8Type;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.config.Language;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class UnsignedTest {
  @Test
  public void uint8ParsingAndFormatting() {
    Uint8 value = Uint8.parse("255");
    assertEquals(value.toInt(), 255);
    assertEquals(Uint8.parse("ff", 16).toInt(), 255);
    assertEquals(value.toUnsignedString(16), "ff");
    assertEquals(value.toHexString(), "ff");
    assertTrue(Uint8.parse("0").isZero());
    assertTrue(Uint8.MAX_VALUE.isMaxValue());
  }

  @Test(expectedExceptions = NumberFormatException.class)
  public void uint8ParseOutOfRange() {
    Uint8.parse("256");
  }

  @Test
  public void uint8ArithmeticAndBitwise() {
    Uint8 a = Uint8.parse("255");
    Uint8 b = Uint8.valueOf(1);

    assertEquals(a.add(b).toInt(), 0); // wraps
    assertEquals(Uint8.MIN_VALUE.subtract(b).toInt(), 255);
    assertEquals(a.divide(Uint8.valueOf(2)).toInt(), 127);
    assertEquals(a.remainder(Uint8.valueOf(2)).toInt(), 1);

    Uint8 lo = Uint8.valueOf(0x0F);
    Uint8 hi = Uint8.valueOf(0xF0);
    assertEquals(lo.or(hi).toInt(), 0xFF);
    assertEquals(hi.and(lo).toInt(), 0);
    assertEquals(lo.xor(hi).toInt(), 0xFF);
    assertEquals(lo.not().toInt(), 0xF0);

    assertEquals(Uint8.valueOf(0x81).shiftRight(1).toInt(), 0x40);
    assertEquals(Uint8.valueOf(0x01).shiftLeft(7).toInt(), 0x80);
  }

  @Test
  public void uint16ParsingAndFormatting() {
    Uint16 value = Uint16.parse("65535");
    assertEquals(value.toInt(), 65535);
    assertEquals(Uint16.parse("ffff", 16).toInt(), 65535);
    assertEquals(value.toUnsignedString(16), "ffff");
    assertEquals(value.toHexString(), "ffff");
    assertTrue(Uint16.parse("0").isZero());
    assertTrue(Uint16.MAX_VALUE.isMaxValue());
  }

  @Test(expectedExceptions = NumberFormatException.class)
  public void uint16ParseOutOfRange() {
    Uint16.parse("65536");
  }

  @Test
  public void uint16ArithmeticAndBitwise() {
    Uint16 a = Uint16.parse("65535");
    Uint16 b = Uint16.valueOf(1);

    assertEquals(a.add(b).toInt(), 0); // wraps
    assertEquals(Uint16.MIN_VALUE.subtract(b).toInt(), 65535);
    assertEquals(a.divide(Uint16.valueOf(2)).toInt(), 32767);
    assertEquals(a.remainder(Uint16.valueOf(2)).toInt(), 1);

    Uint16 lo = Uint16.valueOf(0x00FF);
    Uint16 hi = Uint16.valueOf(0xFF00);
    assertEquals(lo.or(hi).toInt(), 0xFFFF);
    assertEquals(hi.and(lo).toInt(), 0);
    assertEquals(lo.xor(hi).toInt(), 0xFFFF);
    assertEquals(lo.not().toInt(), 0xFF00);

    assertEquals(Uint16.valueOf(0x8001).shiftRight(1).toInt(), 0x4000);
    assertEquals(Uint16.valueOf(0x0001).shiftLeft(15).toInt(), 0x8000);
  }

  @Test
  public void uint32ParsingAndArithmetic() {
    Uint32 value = Uint32.parse("4294967295");
    assertEquals(value.toLong(), 4294967295L);
    assertEquals(Uint32.parse("ffffffff", 16).toLong(), 4294967295L);
    assertEquals(value.toUnsignedString(16), "ffffffff");
    assertEquals(value.toHexString(), "ffffffff");
    assertFalse(value.isZero());
    assertTrue(value.isMaxValue());

    Uint32 wrap = Uint32.valueOf(-1); // MAX
    assertEquals(wrap.add(Uint32.valueOf(1)).toLong(), 0L);
    assertEquals(wrap.divide(Uint32.valueOf(2)).toLong(), 2147483647L);
    assertEquals(wrap.remainder(Uint32.valueOf(2)).toLong(), 1L);

    Uint32 lo = Uint32.valueOf(0x00FF00FF);
    Uint32 hi = Uint32.valueOf(0xFF00FF00);
    assertEquals(lo.or(hi).toLong(), 0xFFFFFFFFL);
    assertEquals(hi.and(lo).toLong(), 0L);
    assertEquals(lo.xor(hi).toLong(), 0xFFFFFFFFL);
    assertEquals(lo.not().toLong(), 0xFF00FF00L);

    assertEquals(Uint32.valueOf(0x80000001).shiftRight(1).toLong(), 0x40000000L);
    assertEquals(Uint32.valueOf(0x00000001).shiftLeft(31).toLong(), 0x80000000L);
  }

  @Test
  public void uint64ParsingAndArithmetic() {
    Uint64 value = Uint64.parse("18446744073709551615");
    assertEquals(value.toUnsignedString(16), "ffffffffffffffff");
    assertEquals(value.toHexString(), "ffffffffffffffff");
    assertFalse(value.isZero());
    assertTrue(value.isMaxValue());

    Uint64 wrap = Uint64.valueOf(-1L); // MAX
    assertEquals(wrap.add(Uint64.valueOf(1)).longValue(), 0L);
    assertEquals(
        wrap.divide(Uint64.valueOf(2)).longValue(), Long.parseUnsignedLong("9223372036854775807"));
    assertEquals(wrap.remainder(Uint64.valueOf(2)).longValue(), 1L);

    Uint64 lo = Uint64.valueOf(0x00FF00FF00FF00FFL);
    Uint64 hi = Uint64.valueOf(0xFF00FF00FF00FF00L);
    assertEquals(lo.or(hi).toLong(), -1L);
    assertEquals(hi.and(lo).toLong(), 0L);
    assertEquals(lo.xor(hi).toLong(), -1L);
    assertEquals(lo.not().toLong(), 0xFF00FF00FF00FF00L);

    assertEquals(Uint64.valueOf(0x8000000000000001L).shiftRight(1).toLong(), 0x4000000000000000L);
    assertEquals(Uint64.valueOf(0x0000000000000001L).shiftLeft(63).toLong(), Long.MIN_VALUE);
  }

  // POJO1: Using Uint8/16/32/64 wrapper types as fields
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class UintPojo {
    @ForyField(nullable = false)
    private Uint8 uint8Field;

    @ForyField(nullable = false)
    private Uint16 uint16Field;

    @ForyField(nullable = false)
    private Uint32 uint32Field;

    @ForyField(nullable = false)
    private Uint64 uint64Field;
  }

  // POJO2: Using primitive types with Uint annotations (primitives are never nullable)
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class PrimitiveUintPojo {
    @Uint8Type private byte uint8Field;

    @Uint16Type private short uint16Field;

    @Uint32Type private int uint32Field;

    @Uint64Type private long uint64Field;
  }

  // POJO3: Using boxed types with Uint annotations and non-nullable
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class BoxedUintPojo {
    @Uint8Type
    @ForyField(nullable = false)
    private Byte uint8Field;

    @Uint16Type
    @ForyField(nullable = false)
    private Short uint16Field;

    @Uint32Type
    @ForyField(nullable = false)
    private Integer uint32Field;

    @Uint64Type
    @ForyField(nullable = false)
    private Long uint64Field;
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
  public void testUintPojoSerialization(boolean enableCodegen, boolean xlang) {
    ForyBuilder builder =
        Fory.builder()
            .withLanguage(xlang ? Language.XLANG : Language.JAVA)
            .withCodegen(enableCodegen)
            .requireClassRegistration(false);
    Fory fory = builder.build();

    // Register Uint serializers
    UnsignedSerializers.registerSerializers(fory.getTypeResolver());

    // Register the Uint POJO class
    fory.register(UintPojo.class);

    // Create test data with Uint wrapper types
    UintPojo original =
        new UintPojo(
            Uint8.valueOf(255), // max value
            Uint16.valueOf(65535), // max value
            Uint32.valueOf(-1), // max value (4294967295)
            Uint64.valueOf(-1L) // max value (18446744073709551615)
            );

    // Serialize and deserialize
    byte[] bytes = fory.serialize(original);
    UintPojo deserialized = (UintPojo) fory.deserialize(bytes);

    // Verify - values preserved as unsigned
    assertEquals(deserialized.getUint8Field().toInt(), 255);
    assertEquals(deserialized.getUint16Field().toInt(), 65535);
    assertEquals(deserialized.getUint32Field().toLong(), 4294967295L);
    assertEquals(deserialized.getUint64Field().toUnsignedString(10), "18446744073709551615");
  }

  @Test(dataProvider = "configProvider")
  public void testPrimitiveUintPojoSerialization(boolean enableCodegen, boolean xlang) {
    ForyBuilder builder =
        Fory.builder()
            .withLanguage(xlang ? Language.XLANG : Language.JAVA)
            .withCodegen(enableCodegen)
            .requireClassRegistration(false);
    Fory fory = builder.build();

    // Register the primitive POJO class
    fory.register(PrimitiveUintPojo.class);

    // Create test data with primitive types (will be interpreted as unsigned)
    PrimitiveUintPojo original =
        new PrimitiveUintPojo(
            (byte) 255, // -1 as signed byte, but 255 as unsigned
            (short) 65535, // -1 as signed short, but 65535 as unsigned
            -1, // -1 as signed int, but 4294967295 as unsigned
            -1L // -1 as signed long, but 18446744073709551615 as unsigned
            );

    // Serialize and deserialize
    byte[] bytes = fory.serialize(original);
    PrimitiveUintPojo deserialized = (PrimitiveUintPojo) fory.deserialize(bytes);

    // Verify - values are preserved as unsigned
    assertEquals(Byte.toUnsignedInt(deserialized.getUint8Field()), 255);
    assertEquals(Short.toUnsignedInt(deserialized.getUint16Field()), 65535);
    assertEquals(Integer.toUnsignedLong(deserialized.getUint32Field()), 4294967295L);
    assertEquals(Long.toUnsignedString(deserialized.getUint64Field()), "18446744073709551615");
  }

  @Test(dataProvider = "configProvider")
  public void testAllPojoTypes(boolean enableCodegen, boolean xlang) {
    ForyBuilder builder =
        Fory.builder()
            .withLanguage(xlang ? Language.XLANG : Language.JAVA)
            .withCodegen(enableCodegen)
            .requireClassRegistration(false);
    Fory fory = builder.build();

    // Register Uint serializers
    UnsignedSerializers.registerSerializers(fory.getTypeResolver());

    // Register all POJO classes
    fory.register(UintPojo.class);
    fory.register(PrimitiveUintPojo.class);
    fory.register(BoxedUintPojo.class);

    // Test UintPojo with Uint wrapper types
    UintPojo uintPojo =
        new UintPojo(
            Uint8.valueOf(200),
            Uint16.valueOf(50000),
            Uint32.valueOf((int) 3000000000L),
            Uint64.valueOf(-1000L));

    byte[] bytes1 = fory.serialize(uintPojo);
    UintPojo deserialized1 = (UintPojo) fory.deserialize(bytes1);
    assertEquals(deserialized1.getUint8Field().toInt(), 200);
    assertEquals(deserialized1.getUint16Field().toInt(), 50000);
    assertEquals(deserialized1.getUint32Field().toLong(), 3000000000L);
    assertEquals(deserialized1.getUint64Field().toLong(), -1000L);

    // Test PrimitiveUintPojo with primitives
    PrimitiveUintPojo primitivePojo =
        new PrimitiveUintPojo(
            (byte) -56, // 200 as unsigned
            (short) -15536, // 50000 as unsigned
            (int) -1294967296, // 3000000000 as unsigned
            -1000L);

    byte[] bytes2 = fory.serialize(primitivePojo);
    PrimitiveUintPojo deserialized2 = (PrimitiveUintPojo) fory.deserialize(bytes2);
    assertEquals(Byte.toUnsignedInt(deserialized2.getUint8Field()), 200);
    assertEquals(Short.toUnsignedInt(deserialized2.getUint16Field()), 50000);
    assertEquals(Integer.toUnsignedLong(deserialized2.getUint32Field()), 3000000000L);
    assertEquals(deserialized2.getUint64Field(), -1000L);

    // Test BoxedUintPojo with boxed types
    BoxedUintPojo boxedPojo =
        new BoxedUintPojo(
            (byte) -56, // 200 as unsigned
            (short) -15536, // 50000 as unsigned
            (int) -1294967296, // 3000000000 as unsigned
            -1000L);

    byte[] bytes3 = fory.serialize(boxedPojo);
    BoxedUintPojo deserialized3 = (BoxedUintPojo) fory.deserialize(bytes3);
    assertEquals(Byte.toUnsignedInt(deserialized3.getUint8Field()), 200);
    assertEquals(Short.toUnsignedInt(deserialized3.getUint16Field()), 50000);
    assertEquals(Integer.toUnsignedLong(deserialized3.getUint32Field()), 3000000000L);
    assertEquals(deserialized3.getUint64Field(), -1000L);
  }

  @Test(dataProvider = "configProvider")
  public void testUintPojoWithZeroValues(boolean enableCodegen, boolean xlang) {
    ForyBuilder builder =
        Fory.builder()
            .withLanguage(xlang ? Language.XLANG : Language.JAVA)
            .withCodegen(enableCodegen)
            .requireClassRegistration(false);
    Fory fory = builder.build();

    // Register Uint serializers
    UnsignedSerializers.registerSerializers(fory.getTypeResolver());

    // Register the Uint POJO class
    fory.register(UintPojo.class);

    // Test with zero/min values
    UintPojo original =
        new UintPojo(Uint8.valueOf(0), Uint16.valueOf(0), Uint32.valueOf(0), Uint64.valueOf(0));

    byte[] bytes = fory.serialize(original);
    UintPojo deserialized = (UintPojo) fory.deserialize(bytes);

    assertTrue(deserialized.getUint8Field().isZero());
    assertTrue(deserialized.getUint16Field().isZero());
    assertTrue(deserialized.getUint32Field().isZero());
    assertTrue(deserialized.getUint64Field().isZero());
  }

  @Test(dataProvider = "configProvider")
  public void testMaxValuesRoundTrip(boolean enableCodegen, boolean xlang) {
    ForyBuilder builder =
        Fory.builder()
            .withLanguage(xlang ? Language.XLANG : Language.JAVA)
            .withCodegen(enableCodegen)
            .requireClassRegistration(false);
    Fory fory = builder.build();

    // Register Uint serializers
    UnsignedSerializers.registerSerializers(fory.getTypeResolver());

    // Register all POJO classes
    fory.register(UintPojo.class);
    fory.register(PrimitiveUintPojo.class);
    fory.register(BoxedUintPojo.class);

    // Test UintPojo with max unsigned values using Uint wrapper types
    UintPojo originalUint =
        new UintPojo(
            Uint8.valueOf(255), Uint16.valueOf(65535), Uint32.valueOf(-1), Uint64.valueOf(-1L));

    byte[] bytes1 = fory.serialize(originalUint);
    UintPojo roundTrippedUint = (UintPojo) fory.deserialize(bytes1);

    assertEquals(roundTrippedUint.getUint8Field().toInt(), 255);
    assertEquals(roundTrippedUint.getUint16Field().toInt(), 65535);
    assertEquals(roundTrippedUint.getUint32Field().toLong(), 4294967295L);
    assertEquals(roundTrippedUint.getUint64Field().toUnsignedString(10), "18446744073709551615");

    // Test PrimitiveUintPojo with primitives
    PrimitiveUintPojo originalPrimitive =
        new PrimitiveUintPojo(
            (byte) -1, // 255 as unsigned
            (short) -1, // 65535 as unsigned
            -1, // 4294967295 as unsigned
            -1L); // 18446744073709551615 as unsigned

    byte[] bytes2 = fory.serialize(originalPrimitive);
    PrimitiveUintPojo roundTrippedPrimitive = (PrimitiveUintPojo) fory.deserialize(bytes2);

    assertEquals(Byte.toUnsignedInt(roundTrippedPrimitive.getUint8Field()), 255);
    assertEquals(Short.toUnsignedInt(roundTrippedPrimitive.getUint16Field()), 65535);
    assertEquals(Integer.toUnsignedLong(roundTrippedPrimitive.getUint32Field()), 4294967295L);
    assertEquals(
        Long.toUnsignedString(roundTrippedPrimitive.getUint64Field()), "18446744073709551615");

    // Test BoxedUintPojo with boxed types
    BoxedUintPojo originalBoxed =
        new BoxedUintPojo(
            (byte) -1, // 255 as unsigned
            (short) -1, // 65535 as unsigned
            -1, // 4294967295 as unsigned
            -1L); // 18446744073709551615 as unsigned

    byte[] bytes3 = fory.serialize(originalBoxed);
    BoxedUintPojo roundTrippedBoxed = (BoxedUintPojo) fory.deserialize(bytes3);

    assertEquals(Byte.toUnsignedInt(roundTrippedBoxed.getUint8Field()), 255);
    assertEquals(Short.toUnsignedInt(roundTrippedBoxed.getUint16Field()), 65535);
    assertEquals(Integer.toUnsignedLong(roundTrippedBoxed.getUint32Field()), 4294967295L);
    assertEquals(Long.toUnsignedString(roundTrippedBoxed.getUint64Field()), "18446744073709551615");
  }
}

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

package org.apache.fory.serializer;

import java.util.Objects;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.annotation.UInt16Type;
import org.apache.fory.annotation.UInt32Type;
import org.apache.fory.annotation.UInt64Type;
import org.apache.fory.annotation.UInt8Type;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.config.Int32Encoding;
import org.apache.fory.config.Int64Encoding;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Unsigned fields serialization tests for java native mode(xlang=false).
 *
 * <p>Type annotation constraints:
 *
 * <ul>
 *   <li>{@code @UInt8Type} can only be applied to {@code int} or {@code Integer} fields
 *   <li>{@code @UInt16Type} can only be applied to {@code int} or {@code Integer} fields
 *   <li>{@code @UInt32Type} can only be applied to {@code long} or {@code Long} fields
 *   <li>{@code @UInt64Type} can only be applied to {@code long} or {@code Long} fields
 * </ul>
 *
 * <p>The unsigned annotations indicate that the field should be treated as unsigned during
 * serialization, allowing the full unsigned range of the type to be used.
 */
public class UnsignedTest extends ForyTestBase {

  public static final int UINT8_MAX = 255;
  public static final int UINT16_MAX = 65535;
  public static final long UINT32_MAX = 4294967295L;
  public static final long UINT64_MAX = -1L; // 0xFFFFFFFFFFFFFFFF as signed long

  public static final int UINT8_MID = 128;
  public static final int UINT16_MID = 32768;
  public static final long UINT32_MID = 2147483648L;
  public static final long UINT64_MID = Long.MIN_VALUE; // 0x8000000000000000

  @Data
  public static class UnsignedSchemaConsistent {
    @UInt8Type int u8;

    @UInt16Type int u16;

    @UInt32Type long u32Var;

    @UInt32Type(encoding = Int32Encoding.FIXED)
    long u32Fixed;

    @UInt64Type(encoding = Int64Encoding.VARINT)
    long u64Var;

    @UInt64Type(encoding = Int64Encoding.FIXED)
    long u64Fixed;

    @UInt64Type(encoding = Int64Encoding.TAGGED)
    long u64Tagged;

    @ForyField(nullable = true)
    @UInt8Type
    Integer u8Nullable;

    @ForyField(nullable = true)
    @UInt16Type
    Integer u16Nullable;

    @ForyField(nullable = true)
    @UInt32Type
    Long u32VarNullable;

    @ForyField(nullable = true)
    @UInt32Type(encoding = Int32Encoding.FIXED)
    Long u32FixedNullable;

    @ForyField(nullable = true)
    @UInt64Type(encoding = Int64Encoding.VARINT)
    Long u64VarNullable;

    @ForyField(nullable = true)
    @UInt64Type(encoding = Int64Encoding.FIXED)
    Long u64FixedNullable;

    @ForyField(nullable = true)
    @UInt64Type(encoding = Int64Encoding.TAGGED)
    Long u64TaggedNullable;
  }

  public static class UnsignedSchemaCompatible {
    @UInt8Type int u8;

    @UInt16Type int u16;

    @UInt32Type long u32Var;

    @UInt32Type(encoding = Int32Encoding.FIXED)
    long u32Fixed;

    @UInt64Type(encoding = Int64Encoding.VARINT)
    long u64Var;

    @UInt64Type(encoding = Int64Encoding.FIXED)
    long u64Fixed;

    @UInt64Type(encoding = Int64Encoding.TAGGED)
    long u64Tagged;

    @ForyField(nullable = true)
    @UInt8Type
    Integer u8Field2;

    @ForyField(nullable = true)
    @UInt16Type
    Integer u16Field2;

    @ForyField(nullable = true)
    @UInt32Type
    Long u32VarField2;

    @ForyField(nullable = true)
    @UInt32Type(encoding = Int32Encoding.FIXED)
    Long u32FixedField2;

    @ForyField(nullable = true)
    @UInt64Type(encoding = Int64Encoding.VARINT)
    Long u64VarField2;

    @ForyField(nullable = true)
    @UInt64Type(encoding = Int64Encoding.FIXED)
    Long u64FixedField2;

    @ForyField(nullable = true)
    @UInt64Type(encoding = Int64Encoding.TAGGED)
    Long u64TaggedField2;

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      UnsignedSchemaCompatible that = (UnsignedSchemaCompatible) o;
      return u8 == that.u8
          && u16 == that.u16
          && u32Var == that.u32Var
          && u32Fixed == that.u32Fixed
          && u64Var == that.u64Var
          && u64Fixed == that.u64Fixed
          && u64Tagged == that.u64Tagged
          && Objects.equals(u8Field2, that.u8Field2)
          && Objects.equals(u16Field2, that.u16Field2)
          && Objects.equals(u32VarField2, that.u32VarField2)
          && Objects.equals(u32FixedField2, that.u32FixedField2)
          && Objects.equals(u64VarField2, that.u64VarField2)
          && Objects.equals(u64FixedField2, that.u64FixedField2)
          && Objects.equals(u64TaggedField2, that.u64TaggedField2);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          u8,
          u16,
          u32Var,
          u32Fixed,
          u64Var,
          u64Fixed,
          u64Tagged,
          u8Field2,
          u16Field2,
          u32VarField2,
          u32FixedField2,
          u64VarField2,
          u64FixedField2,
          u64TaggedField2);
    }
  }

  private static UnsignedSchemaConsistent createConsistentWithNormalValues() {
    UnsignedSchemaConsistent obj = new UnsignedSchemaConsistent();
    obj.u8 = 200;
    obj.u16 = 60000;
    obj.u32Var = 2000000000L;
    obj.u32Fixed = 2100000000L;
    obj.u64Var = 10000000000L;
    obj.u64Fixed = 15000000000L;
    obj.u64Tagged = 1000000000L;
    obj.u8Nullable = 128;
    obj.u16Nullable = 40000;
    obj.u32VarNullable = 1500000000L;
    obj.u32FixedNullable = 1800000000L;
    obj.u64VarNullable = 8000000000L;
    obj.u64FixedNullable = 12000000000L;
    obj.u64TaggedNullable = 500000000L;
    return obj;
  }

  private static UnsignedSchemaConsistent createConsistentWithZeroValues() {
    UnsignedSchemaConsistent obj = new UnsignedSchemaConsistent();
    obj.u8 = 0;
    obj.u16 = 0;
    obj.u32Var = 0L;
    obj.u32Fixed = 0L;
    obj.u64Var = 0;
    obj.u64Fixed = 0;
    obj.u64Tagged = 0;
    obj.u8Nullable = 0;
    obj.u16Nullable = 0;
    obj.u32VarNullable = 0L;
    obj.u32FixedNullable = 0L;
    obj.u64VarNullable = 0L;
    obj.u64FixedNullable = 0L;
    obj.u64TaggedNullable = 0L;
    return obj;
  }

  private static UnsignedSchemaConsistent createConsistentWithMaxValues() {
    UnsignedSchemaConsistent obj = new UnsignedSchemaConsistent();
    obj.u8 = UINT8_MAX;
    obj.u16 = UINT16_MAX;
    obj.u32Var = UINT32_MAX;
    obj.u32Fixed = UINT32_MAX;
    obj.u64Var = UINT64_MAX;
    obj.u64Fixed = UINT64_MAX;
    obj.u64Tagged = UINT64_MAX;
    obj.u8Nullable = UINT8_MAX;
    obj.u16Nullable = UINT16_MAX;
    obj.u32VarNullable = UINT32_MAX;
    obj.u32FixedNullable = UINT32_MAX;
    obj.u64VarNullable = UINT64_MAX;
    obj.u64FixedNullable = UINT64_MAX;
    obj.u64TaggedNullable = UINT64_MAX;
    return obj;
  }

  private static UnsignedSchemaConsistent createConsistentWithMidValues() {
    UnsignedSchemaConsistent obj = new UnsignedSchemaConsistent();
    obj.u8 = UINT8_MID;
    obj.u16 = UINT16_MID;
    obj.u32Var = UINT32_MID;
    obj.u32Fixed = UINT32_MID;
    obj.u64Var = UINT64_MID;
    obj.u64Fixed = UINT64_MID;
    obj.u64Tagged = UINT64_MID;
    obj.u8Nullable = UINT8_MID;
    obj.u16Nullable = UINT16_MID;
    obj.u32VarNullable = UINT32_MID;
    obj.u32FixedNullable = UINT32_MID;
    obj.u64VarNullable = UINT64_MID;
    obj.u64FixedNullable = UINT64_MID;
    obj.u64TaggedNullable = UINT64_MID;
    return obj;
  }

  private static UnsignedSchemaConsistent createConsistentWithNullValues() {
    UnsignedSchemaConsistent obj = new UnsignedSchemaConsistent();
    obj.u8 = 100;
    obj.u16 = 30000;
    obj.u32Var = 1500000000L;
    obj.u32Fixed = 2000000000L;
    obj.u64Var = 5000000000L;
    obj.u64Fixed = 7500000000L;
    obj.u64Tagged = 250000000L;
    obj.u8Nullable = null;
    obj.u16Nullable = null;
    obj.u32VarNullable = null;
    obj.u32FixedNullable = null;
    obj.u64VarNullable = null;
    obj.u64FixedNullable = null;
    obj.u64TaggedNullable = null;
    return obj;
  }

  private static UnsignedSchemaCompatible createCompatibleWithNormalValues() {
    UnsignedSchemaCompatible obj = new UnsignedSchemaCompatible();
    obj.u8 = 200;
    obj.u16 = 60000;
    obj.u32Var = 2000000000L;
    obj.u32Fixed = 2100000000L;
    obj.u64Var = 10000000000L;
    obj.u64Fixed = 15000000000L;
    obj.u64Tagged = 1000000000L;
    obj.u8Field2 = 128;
    obj.u16Field2 = 40000;
    obj.u32VarField2 = 1500000000L;
    obj.u32FixedField2 = 1800000000L;
    obj.u64VarField2 = 8000000000L;
    obj.u64FixedField2 = 12000000000L;
    obj.u64TaggedField2 = 500000000L;
    return obj;
  }

  private static UnsignedSchemaCompatible createCompatibleWithZeroValues() {
    UnsignedSchemaCompatible obj = new UnsignedSchemaCompatible();
    obj.u8 = 0;
    obj.u16 = 0;
    obj.u32Var = 0L;
    obj.u32Fixed = 0L;
    obj.u64Var = 0;
    obj.u64Fixed = 0;
    obj.u64Tagged = 0;
    obj.u8Field2 = 0;
    obj.u16Field2 = 0;
    obj.u32VarField2 = 0L;
    obj.u32FixedField2 = 0L;
    obj.u64VarField2 = 0L;
    obj.u64FixedField2 = 0L;
    obj.u64TaggedField2 = 0L;
    return obj;
  }

  private static UnsignedSchemaCompatible createCompatibleWithMaxValues() {
    UnsignedSchemaCompatible obj = new UnsignedSchemaCompatible();
    obj.u8 = UINT8_MAX;
    obj.u16 = UINT16_MAX;
    obj.u32Var = UINT32_MAX;
    obj.u32Fixed = UINT32_MAX;
    obj.u64Var = UINT64_MAX;
    obj.u64Fixed = UINT64_MAX;
    obj.u64Tagged = UINT64_MAX;
    obj.u8Field2 = UINT8_MAX;
    obj.u16Field2 = UINT16_MAX;
    obj.u32VarField2 = UINT32_MAX;
    obj.u32FixedField2 = UINT32_MAX;
    obj.u64VarField2 = UINT64_MAX;
    obj.u64FixedField2 = UINT64_MAX;
    obj.u64TaggedField2 = UINT64_MAX;
    return obj;
  }

  private static UnsignedSchemaCompatible createCompatibleWithMidValues() {
    UnsignedSchemaCompatible obj = new UnsignedSchemaCompatible();
    obj.u8 = UINT8_MID;
    obj.u16 = UINT16_MID;
    obj.u32Var = UINT32_MID;
    obj.u32Fixed = UINT32_MID;
    obj.u64Var = UINT64_MID;
    obj.u64Fixed = UINT64_MID;
    obj.u64Tagged = UINT64_MID;
    obj.u8Field2 = UINT8_MID;
    obj.u16Field2 = UINT16_MID;
    obj.u32VarField2 = UINT32_MID;
    obj.u32FixedField2 = UINT32_MID;
    obj.u64VarField2 = UINT64_MID;
    obj.u64FixedField2 = UINT64_MID;
    obj.u64TaggedField2 = UINT64_MID;
    return obj;
  }

  private static UnsignedSchemaCompatible createCompatibleWithNullValues() {
    UnsignedSchemaCompatible obj = new UnsignedSchemaCompatible();
    obj.u8 = 100;
    obj.u16 = 30000;
    obj.u32Var = 1500000000L;
    obj.u32Fixed = 2000000000L;
    obj.u64Var = 5000000000L;
    obj.u64Fixed = 7500000000L;
    obj.u64Tagged = 250000000L;
    obj.u8Field2 = null;
    obj.u16Field2 = null;
    obj.u32VarField2 = null;
    obj.u32FixedField2 = null;
    obj.u64VarField2 = null;
    obj.u64FixedField2 = null;
    obj.u64TaggedField2 = null;
    return obj;
  }

  @DataProvider
  public static Object[][] javaForyConfig() {
    return new Object[][] {
      {
        new ForyBuilder()
            .withXlang(false)
            .withCompatible(false)
            .withCodegen(false)
            .requireClassRegistration(false)
            .build()
      },
      {
        new ForyBuilder()
            .withXlang(false)
            .withCompatible(false)
            .withCodegen(true)
            .requireClassRegistration(false)
            .build()
      },
      {
        new ForyBuilder()
            .withXlang(false)
            .withCompatible(true)
            .withCodegen(false)
            .requireClassRegistration(false)
            .build()
      },
      {
        new ForyBuilder()
            .withXlang(false)
            .withCompatible(true)
            .withCodegen(true)
            .requireClassRegistration(false)
            .build()
      }
    };
  }

  @DataProvider(name = "fory")
  public static Object[][] foryProvider() {
    return javaForyConfig();
  }

  // Schema consistent tests
  @Test(dataProvider = "fory")
  public void testUnsignedSchemaConsistentNormalValues(Fory fory) {
    serDeCheck(fory, createConsistentWithNormalValues());
  }

  @Test(dataProvider = "fory")
  public void testUnsignedSchemaConsistentZeroValues(Fory fory) {
    serDeCheck(fory, createConsistentWithZeroValues());
  }

  @Test(dataProvider = "fory")
  public void testUnsignedSchemaConsistentMaxValues(Fory fory) {
    serDeCheck(fory, createConsistentWithMaxValues());
  }

  @Test(dataProvider = "fory")
  public void testUnsignedSchemaConsistentMidValues(Fory fory) {
    serDeCheck(fory, createConsistentWithMidValues());
  }

  @Test(dataProvider = "fory")
  public void testUnsignedSchemaConsistentNullValues(Fory fory) {
    serDeCheck(fory, createConsistentWithNullValues());
  }

  // Schema compatible tests
  @Test(dataProvider = "fory")
  public void testUnsignedSchemaCompatibleNormalValues(Fory fory) {
    serDeCheck(fory, createCompatibleWithNormalValues());
  }

  @Test(dataProvider = "fory")
  public void testUnsignedSchemaCompatibleZeroValues(Fory fory) {
    serDeCheck(fory, createCompatibleWithZeroValues());
  }

  @Test(dataProvider = "fory")
  public void testUnsignedSchemaCompatibleMaxValues(Fory fory) {
    serDeCheck(fory, createCompatibleWithMaxValues());
  }

  @Test(dataProvider = "fory")
  public void testUnsignedSchemaCompatibleMidValues(Fory fory) {
    serDeCheck(fory, createCompatibleWithMidValues());
  }

  @Test(dataProvider = "fory")
  public void testUnsignedSchemaCompatibleNullValues(Fory fory) {
    serDeCheck(fory, createCompatibleWithNullValues());
  }

  // Test specific edge cases for each unsigned type
  public static class UInt8OnlyStruct {
    @UInt8Type int value;

    @ForyField(nullable = true)
    @UInt8Type
    Integer nullableValue;

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      UInt8OnlyStruct that = (UInt8OnlyStruct) o;
      return value == that.value && Objects.equals(nullableValue, that.nullableValue);
    }

    @Override
    public int hashCode() {
      return Objects.hash(value, nullableValue);
    }
  }

  @Test(dataProvider = "fory")
  public void testUInt8EdgeCases(Fory fory) {
    // Test 0
    UInt8OnlyStruct zero = new UInt8OnlyStruct();
    zero.value = 0;
    zero.nullableValue = 0;
    serDeCheck(fory, zero);

    // Test 1
    UInt8OnlyStruct one = new UInt8OnlyStruct();
    one.value = 1;
    one.nullableValue = 1;
    serDeCheck(fory, one);

    UInt8OnlyStruct maxSignedInt8 = new UInt8OnlyStruct();
    maxSignedInt8.value = 127;
    maxSignedInt8.nullableValue = 127;
    serDeCheck(fory, maxSignedInt8);

    UInt8OnlyStruct val128 = new UInt8OnlyStruct();
    val128.value = 128;
    val128.nullableValue = 128;
    serDeCheck(fory, val128);

    UInt8OnlyStruct maxUInt8 = new UInt8OnlyStruct();
    maxUInt8.value = 255;
    maxUInt8.nullableValue = 255;
    serDeCheck(fory, maxUInt8);

    // Test null
    UInt8OnlyStruct withNull = new UInt8OnlyStruct();
    withNull.value = 200;
    withNull.nullableValue = null;
    serDeCheck(fory, withNull);
  }

  public static class UInt16OnlyStruct {
    @UInt16Type int value;

    @ForyField(nullable = true)
    @UInt16Type
    Integer nullableValue;

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      UInt16OnlyStruct that = (UInt16OnlyStruct) o;
      return value == that.value && Objects.equals(nullableValue, that.nullableValue);
    }

    @Override
    public int hashCode() {
      return Objects.hash(value, nullableValue);
    }
  }

  @Test(dataProvider = "fory")
  public void testUInt16EdgeCases(Fory fory) {
    // Test 0
    UInt16OnlyStruct zero = new UInt16OnlyStruct();
    zero.value = 0;
    zero.nullableValue = 0;
    serDeCheck(fory, zero);

    // Test 1
    UInt16OnlyStruct one = new UInt16OnlyStruct();
    one.value = 1;
    one.nullableValue = 1;
    serDeCheck(fory, one);

    UInt16OnlyStruct maxSignedInt16 = new UInt16OnlyStruct();
    maxSignedInt16.value = 32767;
    maxSignedInt16.nullableValue = 32767;
    serDeCheck(fory, maxSignedInt16);

    UInt16OnlyStruct val32768 = new UInt16OnlyStruct();
    val32768.value = 32768;
    val32768.nullableValue = 32768;
    serDeCheck(fory, val32768);

    UInt16OnlyStruct maxUInt16 = new UInt16OnlyStruct();
    maxUInt16.value = 65535;
    maxUInt16.nullableValue = 65535;
    serDeCheck(fory, maxUInt16);

    // Test null
    UInt16OnlyStruct withNull = new UInt16OnlyStruct();
    withNull.value = 50000;
    withNull.nullableValue = null;
    serDeCheck(fory, withNull);
  }

  public static class UInt32OnlyStruct {
    @UInt32Type long varValue;

    @UInt32Type(encoding = Int32Encoding.FIXED)
    long fixedValue;

    @ForyField(nullable = true)
    @UInt32Type
    Long varNullableValue;

    @ForyField(nullable = true)
    @UInt32Type(encoding = Int32Encoding.FIXED)
    Long fixedNullableValue;

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      UInt32OnlyStruct that = (UInt32OnlyStruct) o;
      return varValue == that.varValue
          && fixedValue == that.fixedValue
          && Objects.equals(varNullableValue, that.varNullableValue)
          && Objects.equals(fixedNullableValue, that.fixedNullableValue);
    }

    @Override
    public int hashCode() {
      return Objects.hash(varValue, fixedValue, varNullableValue, fixedNullableValue);
    }
  }

  @Test(dataProvider = "fory")
  public void testUInt32EdgeCases(Fory fory) {
    // Test 0
    UInt32OnlyStruct zero = new UInt32OnlyStruct();
    zero.varValue = 0L;
    zero.fixedValue = 0L;
    zero.varNullableValue = 0L;
    zero.fixedNullableValue = 0L;
    serDeCheck(fory, zero);

    // Test 1
    UInt32OnlyStruct one = new UInt32OnlyStruct();
    one.varValue = 1L;
    one.fixedValue = 1L;
    one.varNullableValue = 1L;
    one.fixedNullableValue = 1L;
    serDeCheck(fory, one);

    UInt32OnlyStruct maxSignedInt = new UInt32OnlyStruct();
    maxSignedInt.varValue = 2147483647L;
    maxSignedInt.fixedValue = 2147483647L;
    maxSignedInt.varNullableValue = 2147483647L;
    maxSignedInt.fixedNullableValue = 2147483647L;
    serDeCheck(fory, maxSignedInt);

    UInt32OnlyStruct val2147483648 = new UInt32OnlyStruct();
    val2147483648.varValue = 2147483648L;
    val2147483648.fixedValue = 2147483648L;
    val2147483648.varNullableValue = 2147483648L;
    val2147483648.fixedNullableValue = 2147483648L;
    serDeCheck(fory, val2147483648);

    UInt32OnlyStruct maxUInt32 = new UInt32OnlyStruct();
    maxUInt32.varValue = 4294967295L;
    maxUInt32.fixedValue = 4294967295L;
    maxUInt32.varNullableValue = 4294967295L;
    maxUInt32.fixedNullableValue = 4294967295L;
    serDeCheck(fory, maxUInt32);

    // Test null
    UInt32OnlyStruct withNull = new UInt32OnlyStruct();
    withNull.varValue = 1000000000L;
    withNull.fixedValue = 1000000000L;
    withNull.varNullableValue = null;
    withNull.fixedNullableValue = null;
    serDeCheck(fory, withNull);
  }

  public static class UInt64OnlyStruct {
    @UInt64Type(encoding = Int64Encoding.VARINT)
    long varValue;

    @UInt64Type(encoding = Int64Encoding.FIXED)
    long fixedValue;

    @UInt64Type(encoding = Int64Encoding.TAGGED)
    long taggedValue;

    @ForyField(nullable = true)
    @UInt64Type(encoding = Int64Encoding.VARINT)
    Long varNullableValue;

    @ForyField(nullable = true)
    @UInt64Type(encoding = Int64Encoding.FIXED)
    Long fixedNullableValue;

    @ForyField(nullable = true)
    @UInt64Type(encoding = Int64Encoding.TAGGED)
    Long taggedNullableValue;

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      UInt64OnlyStruct that = (UInt64OnlyStruct) o;
      return varValue == that.varValue
          && fixedValue == that.fixedValue
          && taggedValue == that.taggedValue
          && Objects.equals(varNullableValue, that.varNullableValue)
          && Objects.equals(fixedNullableValue, that.fixedNullableValue)
          && Objects.equals(taggedNullableValue, that.taggedNullableValue);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          varValue,
          fixedValue,
          taggedValue,
          varNullableValue,
          fixedNullableValue,
          taggedNullableValue);
    }
  }

  @Test(dataProvider = "fory")
  public void testUInt64EdgeCases(Fory fory) {
    // Test 0
    UInt64OnlyStruct zero = new UInt64OnlyStruct();
    zero.varValue = 0L;
    zero.fixedValue = 0L;
    zero.taggedValue = 0;
    zero.varNullableValue = 0L;
    zero.fixedNullableValue = 0L;
    zero.taggedNullableValue = 0L;
    serDeCheck(fory, zero);

    // Test 1
    UInt64OnlyStruct one = new UInt64OnlyStruct();
    one.varValue = 1L;
    one.fixedValue = 1L;
    one.taggedValue = 1;
    one.varNullableValue = 1L;
    one.fixedNullableValue = 1L;
    one.taggedNullableValue = 1L;
    serDeCheck(fory, one);

    // Test Long.MAX_VALUE (max signed long)
    UInt64OnlyStruct maxSignedLong = new UInt64OnlyStruct();
    maxSignedLong.varValue = Long.MAX_VALUE;
    maxSignedLong.fixedValue = Long.MAX_VALUE;
    maxSignedLong.taggedValue = Long.MAX_VALUE;
    maxSignedLong.varNullableValue = Long.MAX_VALUE;
    maxSignedLong.fixedNullableValue = Long.MAX_VALUE;
    maxSignedLong.taggedNullableValue = Long.MAX_VALUE;
    serDeCheck(fory, maxSignedLong);

    // Test Long.MIN_VALUE (this represents 2^63 as unsigned)
    UInt64OnlyStruct minValue = new UInt64OnlyStruct();
    minValue.varValue = Long.MIN_VALUE;
    minValue.fixedValue = Long.MIN_VALUE;
    minValue.taggedValue = Long.MIN_VALUE;
    minValue.varNullableValue = Long.MIN_VALUE;
    minValue.fixedNullableValue = Long.MIN_VALUE;
    minValue.taggedNullableValue = Long.MIN_VALUE;
    serDeCheck(fory, minValue);

    // Test -1 (this represents max uint64: 0xFFFFFFFFFFFFFFFF)
    UInt64OnlyStruct maxUInt64 = new UInt64OnlyStruct();
    maxUInt64.varValue = -1L;
    maxUInt64.fixedValue = -1L;
    maxUInt64.taggedValue = -1L;
    maxUInt64.varNullableValue = -1L;
    maxUInt64.fixedNullableValue = -1L;
    maxUInt64.taggedNullableValue = -1L;
    serDeCheck(fory, maxUInt64);

    // Test null
    UInt64OnlyStruct withNull = new UInt64OnlyStruct();
    withNull.varValue = 10000000000L;
    withNull.fixedValue = 10000000000L;
    withNull.taggedValue = 10000000000L;
    withNull.varNullableValue = null;
    withNull.fixedNullableValue = null;
    withNull.taggedNullableValue = null;
    serDeCheck(fory, withNull);
  }

  // Test tagged encoding boundary values
  @Test(dataProvider = "fory")
  public void testTaggedEncodingBoundaryValues(Fory fory) {
    UInt64OnlyStruct obj = new UInt64OnlyStruct();

    // Test value at tagged 4-byte boundary: -1073741824 (HALF_MIN_INT_VALUE)
    obj.varValue = -1073741824L;
    obj.fixedValue = -1073741824L;
    obj.taggedValue = -1073741824L;
    obj.varNullableValue = -1073741824L;
    obj.fixedNullableValue = -1073741824L;
    obj.taggedNullableValue = -1073741824L;
    serDeCheck(fory, obj);

    // Test value at tagged 4-byte boundary: 1073741823 (HALF_MAX_INT_VALUE)
    obj.varValue = 1073741823L;
    obj.fixedValue = 1073741823L;
    obj.taggedValue = 1073741823L;
    obj.varNullableValue = 1073741823L;
    obj.fixedNullableValue = 1073741823L;
    obj.taggedNullableValue = 1073741823L;
    serDeCheck(fory, obj);

    // Test value just below tagged 4-byte boundary
    obj.varValue = -1073741825L;
    obj.fixedValue = -1073741825L;
    obj.taggedValue = -1073741825L;
    obj.varNullableValue = -1073741825L;
    obj.fixedNullableValue = -1073741825L;
    obj.taggedNullableValue = -1073741825L;
    serDeCheck(fory, obj);

    // Test value just above tagged 4-byte boundary
    obj.varValue = 1073741824L;
    obj.fixedValue = 1073741824L;
    obj.taggedValue = 1073741824L;
    obj.varNullableValue = 1073741824L;
    obj.fixedNullableValue = 1073741824L;
    obj.taggedNullableValue = 1073741824L;
    serDeCheck(fory, obj);
  }

  // Test varint encoding boundary values
  @Test(dataProvider = "fory")
  public void testVarintEncodingBoundaryValues(Fory fory) {
    UInt32OnlyStruct obj32 = new UInt32OnlyStruct();

    // 1-byte varint boundary (0-127)
    obj32.varValue = 127L;
    obj32.fixedValue = 127L;
    obj32.varNullableValue = 127L;
    obj32.fixedNullableValue = 127L;
    serDeCheck(fory, obj32);

    // 2-byte varint boundary (128-16383)
    obj32.varValue = 128L;
    obj32.fixedValue = 128L;
    obj32.varNullableValue = 128L;
    obj32.fixedNullableValue = 128L;
    serDeCheck(fory, obj32);

    obj32.varValue = 16383L;
    obj32.fixedValue = 16383L;
    obj32.varNullableValue = 16383L;
    obj32.fixedNullableValue = 16383L;
    serDeCheck(fory, obj32);

    // 3-byte varint boundary (16384-2097151)
    obj32.varValue = 16384L;
    obj32.fixedValue = 16384L;
    obj32.varNullableValue = 16384L;
    obj32.fixedNullableValue = 16384L;
    serDeCheck(fory, obj32);

    obj32.varValue = 2097151L;
    obj32.fixedValue = 2097151L;
    obj32.varNullableValue = 2097151L;
    obj32.fixedNullableValue = 2097151L;
    serDeCheck(fory, obj32);

    // 4-byte varint boundary (2097152-268435455)
    obj32.varValue = 2097152L;
    obj32.fixedValue = 2097152L;
    obj32.varNullableValue = 2097152L;
    obj32.fixedNullableValue = 2097152L;
    serDeCheck(fory, obj32);

    obj32.varValue = 268435455L;
    obj32.fixedValue = 268435455L;
    obj32.varNullableValue = 268435455L;
    obj32.fixedNullableValue = 268435455L;
    serDeCheck(fory, obj32);

    // 5-byte varint boundary (268435456+)
    obj32.varValue = 268435456L;
    obj32.fixedValue = 268435456L;
    obj32.varNullableValue = 268435456L;
    obj32.fixedNullableValue = 268435456L;
    serDeCheck(fory, obj32);
  }
}

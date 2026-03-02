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

import static org.testng.Assert.*;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.collection.Float16List;
import org.apache.fory.config.Language;
import org.apache.fory.type.Float16;
import org.testng.annotations.Test;

public class Float16SerializerTest extends ForyTestBase {

  @Test
  public void testFloat16Serialization() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();

    byte[] bytes = fory.serialize(Float16.NaN);
    Float16 result = (Float16) fory.deserialize(bytes);
    assertTrue(result.isNaN());

    bytes = fory.serialize(Float16.POSITIVE_INFINITY);
    result = (Float16) fory.deserialize(bytes);
    assertTrue(result.isInfinite() && !result.signbit());

    bytes = fory.serialize(Float16.NEGATIVE_INFINITY);
    result = (Float16) fory.deserialize(bytes);
    assertTrue(result.isInfinite() && result.signbit());

    bytes = fory.serialize(Float16.ZERO);
    result = (Float16) fory.deserialize(bytes);
    assertEquals(Float16.ZERO.toBits(), result.toBits());

    bytes = fory.serialize(Float16.ONE);
    result = (Float16) fory.deserialize(bytes);
    assertEquals(Float16.ONE.toBits(), result.toBits());

    bytes = fory.serialize(Float16.valueOf(1.5f));
    result = (Float16) fory.deserialize(bytes);
    assertEquals(1.5f, result.floatValue(), 0.01f);
  }

  @Test
  public void testFloat16ArraySerialization() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();

    Float16[] array =
        new Float16[] {
          Float16.ZERO,
          Float16.ONE,
          Float16.valueOf(2.5f),
          Float16.valueOf(-1.5f),
          Float16.NaN,
          Float16.POSITIVE_INFINITY,
          Float16.NEGATIVE_INFINITY,
          Float16.MAX_VALUE,
          Float16.MIN_VALUE
        };

    byte[] bytes = fory.serialize(array);
    Float16[] result = (Float16[]) fory.deserialize(bytes);

    assertEquals(array.length, result.length);
    for (int i = 0; i < array.length; i++) {
      if (array[i].isNaN()) {
        assertTrue(result[i].isNaN(), "Index " + i + " should be NaN");
      } else {
        assertEquals(
            array[i].toBits(),
            result[i].toBits(),
            "Index "
                + i
                + " bits should match: expected 0x"
                + Integer.toHexString(array[i].toBits() & 0xFFFF)
                + " but got 0x"
                + Integer.toHexString(result[i].toBits() & 0xFFFF));
      }
    }
  }

  @Test
  public void testFloat16ArraySerializationWithNullElements() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();

    Float16[] array =
        new Float16[] {Float16.ONE, null, Float16.valueOf(-2.5f), null, Float16.MIN_VALUE};
    byte[] bytes = fory.serialize(array);
    Float16[] result = (Float16[]) fory.deserialize(bytes);

    assertEquals(result.length, array.length);
    assertEquals(result[0].toBits(), Float16.ONE.toBits());
    assertNull(result[1]);
    assertEquals(result[2].toBits(), Float16.valueOf(-2.5f).toBits());
    assertNull(result[3]);
    assertEquals(result[4].toBits(), Float16.MIN_VALUE.toBits());
  }

  @Test
  public void testFloat16EmptyArray() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();

    Float16[] empty = new Float16[0];
    byte[] bytes = fory.serialize(empty);
    Float16[] result = (Float16[]) fory.deserialize(bytes);
    assertEquals(0, result.length);
  }

  @Test
  public void testFloat16LargeArray() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();

    Float16[] large = new Float16[1000];
    for (int i = 0; i < large.length; i++) {
      large[i] = Float16.valueOf((float) i);
    }

    byte[] bytes = fory.serialize(large);
    Float16[] result = (Float16[]) fory.deserialize(bytes);

    assertEquals(large.length, result.length);
    for (int i = 0; i < large.length; i++) {
      assertEquals(
          large[i].floatValue(), result[i].floatValue(), 0.1f, "Index " + i + " should match");
    }
  }

  @Data
  @AllArgsConstructor
  public static class StructWithFloat16 {
    Float16 f16Field;
    Float16 f16Field2;
  }

  @Test
  public void testStructWithFloat16Field() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    fory.register(StructWithFloat16.class);

    StructWithFloat16 obj = new StructWithFloat16(Float16.valueOf(1.5f), Float16.valueOf(-2.5f));

    byte[] bytes = fory.serialize(obj);
    StructWithFloat16 result = (StructWithFloat16) fory.deserialize(bytes);

    assertEquals(obj.f16Field.toBits(), result.f16Field.toBits());
    assertEquals(obj.f16Field2.toBits(), result.f16Field2.toBits());
  }

  @Data
  @AllArgsConstructor
  public static class StructWithFloat16Array {
    Float16[] f16Array;
  }

  @Test
  public void testStructWithFloat16Array() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    fory.register(StructWithFloat16Array.class);

    Float16[] array = new Float16[] {Float16.ONE, Float16.valueOf(2.0f), Float16.valueOf(3.0f)};
    StructWithFloat16Array obj = new StructWithFloat16Array(array);

    byte[] bytes = fory.serialize(obj);
    StructWithFloat16Array result = (StructWithFloat16Array) fory.deserialize(bytes);

    assertEquals(obj.f16Array.length, result.f16Array.length);
    for (int i = 0; i < obj.f16Array.length; i++) {
      assertEquals(obj.f16Array[i].toBits(), result.f16Array[i].toBits());
    }
  }

  @Data
  @AllArgsConstructor
  public static class StructWithFloat16List {
    Float16List f16List;
  }

  @Test
  public void testStructWithFloat16ListField() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    fory.register(StructWithFloat16List.class);

    StructWithFloat16List obj = new StructWithFloat16List(buildFloat16List());
    byte[] bytes = fory.serialize(obj);
    StructWithFloat16List result = (StructWithFloat16List) fory.deserialize(bytes);

    assertFloat16ListBits(obj.f16List, result.f16List);
  }

  @Test
  public void testFloat16ListTopLevelSerialization() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();

    Float16List list = buildFloat16List();
    byte[] bytes = fory.serialize(list);
    Float16List result = (Float16List) fory.deserialize(bytes);

    assertFloat16ListBits(list, result);
  }

  @Test
  public void testFloat16XlangTopLevelSerialization() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();

    Float16 scalar = Float16.valueOf(-1.75f);
    byte[] bytes = fory.serialize(scalar);
    Float16 scalarResult = (Float16) fory.deserialize(bytes);
    assertEquals(scalarResult.toBits(), scalar.toBits());

    Float16[] array = new Float16[] {Float16.ONE, Float16.valueOf(-0.5f), Float16.MIN_VALUE};
    bytes = fory.serialize(array);
    Float16[] arrayResult = (Float16[]) fory.deserialize(bytes);
    assertEquals(arrayResult.length, array.length);
    for (int i = 0; i < array.length; i++) {
      assertEquals(arrayResult[i].toBits(), array[i].toBits(), "Index " + i + " should match");
    }

    Float16List list = buildFloat16List();
    bytes = fory.serialize(list);
    Object listResult = fory.deserialize(bytes);
    if (listResult instanceof Float16List) {
      assertFloat16ListBits(list, (Float16List) listResult);
    } else {
      Float16[] arrayResultFromList = (Float16[]) listResult;
      assertEquals(arrayResultFromList.length, list.size());
      for (int i = 0; i < arrayResultFromList.length; i++) {
        assertEquals(
            arrayResultFromList[i].toBits(), list.getShort(i), "Index " + i + " should match");
      }
    }
  }

  @Test
  public void testStructWithFloat16ListFieldInXlang() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();
    fory.register(StructWithFloat16List.class);

    StructWithFloat16List obj = new StructWithFloat16List(buildFloat16List());
    byte[] bytes = fory.serialize(obj);
    StructWithFloat16List result = (StructWithFloat16List) fory.deserialize(bytes);
    assertFloat16ListBits(obj.f16List, result.f16List);
  }

  @Test
  public void testFloat16WithNullableField() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();

    @Data
    @AllArgsConstructor
    class StructWithNullableFloat16 {
      Float16 nullableField;
    }

    fory.register(StructWithNullableFloat16.class);

    StructWithNullableFloat16 obj1 = new StructWithNullableFloat16(Float16.valueOf(1.5f));
    byte[] bytes = fory.serialize(obj1);
    StructWithNullableFloat16 result = (StructWithNullableFloat16) fory.deserialize(bytes);
    assertEquals(obj1.nullableField.toBits(), result.nullableField.toBits());

    StructWithNullableFloat16 obj2 = new StructWithNullableFloat16(null);
    bytes = fory.serialize(obj2);
    result = (StructWithNullableFloat16) fory.deserialize(bytes);
    assertNull(result.nullableField);
  }

  @Test
  public void testFloat16SpecialValuesInStruct() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();

    @Data
    @AllArgsConstructor
    class StructWithSpecialValues {
      Float16 nan;
      Float16 posInf;
      Float16 negInf;
      Float16 zero;
      Float16 negZero;
    }

    fory.register(StructWithSpecialValues.class);

    StructWithSpecialValues obj =
        new StructWithSpecialValues(
            Float16.NaN,
            Float16.POSITIVE_INFINITY,
            Float16.NEGATIVE_INFINITY,
            Float16.ZERO,
            Float16.NEGATIVE_ZERO);

    byte[] bytes = fory.serialize(obj);
    StructWithSpecialValues result = (StructWithSpecialValues) fory.deserialize(bytes);

    assertTrue(result.nan.isNaN());
    assertTrue(result.posInf.isInfinite() && !result.posInf.signbit());
    assertTrue(result.negInf.isInfinite() && result.negInf.signbit());
    assertTrue(result.zero.isZero() && !result.zero.signbit());
    assertTrue(result.negZero.isZero() && result.negZero.signbit());
  }

  @Test
  public void testFloat16BitPatternPreservation() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();

    short[] testBits = {
      (short) 0x0000,
      (short) 0x8000,
      (short) 0x3c00,
      (short) 0xbc00,
      (short) 0x7bff,
      (short) 0x0001,
      (short) 0x0400,
      (short) 0x7c00,
      (short) 0xfc00,
      (short) 0x7e00
    };

    for (short bits : testBits) {
      Float16 original = Float16.fromBits(bits);
      byte[] bytes = fory.serialize(original);
      Float16 result = (Float16) fory.deserialize(bytes);

      if (original.isNaN()) {
        assertTrue(result.isNaN(), "NaN should remain NaN");
      } else {
        assertEquals(
            original.toBits(),
            result.toBits(),
            "Bit pattern should be preserved for 0x" + Integer.toHexString(bits & 0xFFFF));
      }
    }
  }

  private static Float16List buildFloat16List() {
    return new Float16List(
        new short[] {
          Float16.ZERO.toBits(),
          Float16.ONE.toBits(),
          Float16.valueOf(2.5f).toBits(),
          Float16.valueOf(-3.25f).toBits(),
          Float16.NaN.toBits(),
          Float16.POSITIVE_INFINITY.toBits(),
          Float16.NEGATIVE_ZERO.toBits()
        });
  }

  private static void assertFloat16ListBits(Float16List expected, Float16List actual) {
    assertEquals(expected.size(), actual.size());
    for (int i = 0; i < expected.size(); i++) {
      assertEquals(expected.getShort(i), actual.getShort(i), "Index " + i + " should match");
    }
  }
}

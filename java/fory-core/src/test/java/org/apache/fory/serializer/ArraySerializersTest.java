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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.config.Language;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.test.bean.ArraysData;
import org.apache.fory.type.Descriptor;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ArraySerializersTest extends ForyTestBase {

  private static void registerTypes(Fory... forys) {
    Class<?>[] types =
        new Class<?>[] {
          Object.class,
          Boolean.class,
          Byte.class,
          Short.class,
          Integer.class,
          Float.class,
          Double.class,
          Long.class,
          String.class,
          Object[].class,
          Boolean[].class,
          Byte[].class,
          Short[].class,
          Integer[].class,
          Float[].class,
          Double[].class,
          Long[].class,
          String[].class,
          Object[][].class,
          Boolean[][].class,
          Byte[][].class,
          Short[][].class,
          Integer[][].class,
          Float[][].class,
          Double[][].class,
          Long[][].class,
          String[][].class
        };
    for (Fory f : forys) {
      for (Class<?> t : types) {
        f.getSerializer(t);
      }
      if (f.getConfig().getLanguage() == Language.JAVA) {
        f.getSerializer(Character.class);
        f.getSerializer(Character[].class);
        f.getSerializer(Character[][].class);
      }
    }
  }

  @Test(dataProvider = "crossLanguageReferenceTrackingConfig")
  public void testObjectArraySerialization(boolean referenceTracking, Language language) {
    if (language != Language.JAVA) {
      return;
    }
    ForyBuilder builder =
        Fory.builder()
            .withLanguage(language)
            .withRefTracking(referenceTracking)
            .requireClassRegistration(false);
    Fory fory1 = builder.build();
    Fory fory2 = builder.build();
    if (fory1.getConfig().isMetaShareEnabled()) {
      fory1.getSerializationContext().setMetaContext(new org.apache.fory.resolver.MetaContext());
      fory2.getSerializationContext().setMetaContext(new org.apache.fory.resolver.MetaContext());
    }
    registerTypes(fory1, fory2);
    serDeCheck(fory1, fory2, new Object[] {false, true});
    serDeCheck(fory1, fory2, new Object[] {(byte) 1, (byte) 1});
    serDeCheck(fory1, fory2, new Object[] {(short) 1, (short) 1});
    if (language == Language.JAVA) {
      serDeCheck(fory1, fory2, new Object[] {(char) 1, (char) 1});
    }
    serDeCheck(fory1, fory2, new Object[] {1, 1});
    serDeCheck(fory1, fory2, new Object[] {(float) 1.0, (float) 1.1});
    serDeCheck(fory1, fory2, new Object[] {1.0, 1.1});
    serDeCheck(fory1, fory2, new Object[] {1L, 2L});
    serDeCheck(fory1, fory2, new Boolean[] {false, true});
    serDeCheck(fory1, fory2, new Byte[] {(byte) 1, (byte) 1});
    serDeCheck(fory1, fory2, new Short[] {(short) 1, (short) 1});
    if (language == Language.JAVA) {
      serDeCheck(fory1, fory2, new Character[] {(char) 1, (char) 1});
    }
    serDeCheck(fory1, fory2, new Integer[] {1, 1});
    serDeCheck(fory1, fory2, new Float[] {(float) 1.0, (float) 1.1});
    serDeCheck(fory1, fory2, new Double[] {1.0, 1.1});
    serDeCheck(fory1, fory2, new Long[] {1L, 2L});
    serDeCheck(
        fory1, fory2, new Object[] {false, true, (byte) 1, (byte) 1, (float) 1.0, (float) 1.1});
    serDeCheck(fory1, fory2, new String[] {"str", "str"});
    serDeCheck(fory1, fory2, new Object[] {"str", 1});
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testObjectArrayCopy(Fory fory) {
    copyCheck(fory, new Object[] {false, true});
    copyCheck(fory, new Object[] {(byte) 1, (byte) 1});
    copyCheck(fory, new Object[] {(short) 1, (short) 1});
    copyCheck(fory, new Object[] {(char) 1, (char) 1});
    copyCheck(fory, new Object[] {1, 1});
    copyCheck(fory, new Object[] {(float) 1.0, (float) 1.1});
    copyCheck(fory, new Object[] {1.0, 1.1});
    copyCheck(fory, new Object[] {1L, 2L});
    copyCheck(fory, new Boolean[] {false, true});
    copyCheck(fory, new Byte[] {(byte) 1, (byte) 1});
    copyCheck(fory, new Short[] {(short) 1, (short) 1});
    copyCheck(fory, new Character[] {(char) 1, (char) 1});
    copyCheck(fory, new Integer[] {1, 1});
    copyCheck(fory, new Float[] {(float) 1.0, (float) 1.1});
    copyCheck(fory, new Double[] {1.0, 1.1});
    copyCheck(fory, new Long[] {1L, 2L});
    copyCheck(fory, new Object[] {false, true, (byte) 1, (byte) 1, (float) 1.0, (float) 1.1});
    copyCheck(fory, new String[] {"str", "str"});
    copyCheck(fory, new Object[] {"str", 1});
  }

  @Test(dataProvider = "crossLanguageReferenceTrackingConfig")
  public void testMultiArraySerialization(boolean referenceTracking, Language language) {
    if (language != Language.JAVA) {
      return;
    }
    ForyBuilder builder =
        Fory.builder()
            .withLanguage(language)
            .withRefTracking(referenceTracking)
            .requireClassRegistration(false);
    Fory fory1 = builder.build();
    Fory fory2 = builder.build();
    if (fory1.getConfig().isMetaShareEnabled()) {
      fory1.getSerializationContext().setMetaContext(new org.apache.fory.resolver.MetaContext());
      fory2.getSerializationContext().setMetaContext(new org.apache.fory.resolver.MetaContext());
    }
    registerTypes(fory1, fory2);
    serDeCheck(fory1, fory2, new Object[][] {{false, true}, {false, true}});
    serDeCheck(
        fory1,
        fory2,
        new Object[][] {
          {false, true, (byte) 1, (byte) 1, (float) 1.0, (float) 1.1},
          {false, true, (byte) 1, (byte) 1, (float) 1.0, (float) 1.1}
        });
    serDeCheck(fory1, fory2, new Integer[][] {{1, 2}, {1, 2}});
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testMultiArraySerialization(Fory fory) {
    copyCheck(fory, new Object[][] {{false, true}, {false, true}});
    copyCheck(
        fory,
        new Object[][] {
          {false, true, (byte) 1, (byte) 1, (float) 1.0, (float) 1.1},
          {false, true, (byte) 1, (byte) 1, (float) 1.0, (float) 1.1}
        });
    copyCheck(fory, new Integer[][] {{1, 2}, {1, 2}});
  }

  @Test(dataProvider = "crossLanguageReferenceTrackingConfig")
  public void testPrimitiveArray(boolean referenceTracking, Language language) {
    Supplier<ForyBuilder> builder =
        () ->
            Fory.builder()
                .withLanguage(language)
                .withRefTracking(referenceTracking)
                .requireClassRegistration(false);
    Fory fory1 = builder.get().build();
    Fory fory2 = builder.get().build();
    testPrimitiveArray(fory1, fory2);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testPrimitiveArray(Fory fory) {
    copyCheck(fory, new boolean[] {false, true});
    copyCheck(fory, new byte[] {1, 1});
    copyCheck(fory, new short[] {1, 1});
    copyCheck(fory, new int[] {1, 1});
    copyCheck(fory, new long[] {1, 1});
    copyCheck(fory, new float[] {1.f, 1.f});
    copyCheck(fory, new double[] {1.0, 1.0});
    copyCheck(fory, new char[] {'a', ' '});
  }

  public static void testPrimitiveArray(Fory fory1, Fory fory2) {
    assertTrue(
        Arrays.equals(
            new boolean[] {false, true},
            (boolean[]) serDe(fory1, fory2, new boolean[] {false, true})));
    assertEquals(new byte[] {1, 1}, (byte[]) serDe(fory1, fory2, new byte[] {1, 1}));
    assertEquals(new short[] {1, 1}, (short[]) serDe(fory1, fory2, new short[] {1, 1}));
    assertEquals(new int[] {1, 1}, (int[]) serDe(fory1, fory2, new int[] {1, 1}));
    assertEquals(new long[] {1, 1}, (long[]) serDe(fory1, fory2, new long[] {1, 1}));
    assertTrue(
        Arrays.equals(new float[] {1.f, 1.f}, (float[]) serDe(fory1, fory2, new float[] {1f, 1f})));
    assertTrue(
        Arrays.equals(
            new double[] {1.0, 1.0}, (double[]) serDe(fory1, fory2, new double[] {1.0, 1.0})));
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testArrayZeroCopy(boolean referenceTracking) {
    ForyBuilder builder =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(referenceTracking)
            .requireClassRegistration(false);
    Fory fory1 = builder.build();
    Fory fory2 = builder.build();
    AtomicInteger counter = new AtomicInteger(0);
    for (int i = 0; i < 4; i++) {
      ArraysData arraysData = new ArraysData(7 * i);
      Set<Field> fields = Descriptor.getFields(ArraysData.class);
      List<Object> fieldValues = ReflectionUtils.getFieldValues(fields, arraysData);
      Object[] array = fieldValues.toArray(new Object[0]);
      assertEquals(array, serDeOutOfBand(counter, fory1, fory1, array));
      assertEquals(array, serDeOutOfBand(counter, fory1, fory2, array));
    }
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testArrayStructZeroCopy(boolean referenceTracking) {
    ForyBuilder builder =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(referenceTracking)
            .requireClassRegistration(false);
    Fory fory1 = builder.build();
    Fory fory2 = builder.build();
    AtomicInteger counter = new AtomicInteger(0);
    for (int i = 0; i < 4; i++) {
      ArraysData arraysData = new ArraysData(7 * i);
      assertEquals(arraysData, serDeOutOfBand(counter, fory1, fory1, arraysData));
      assertEquals(arraysData, serDeOutOfBand(counter, fory1, fory2, arraysData));
    }
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testArrayStructZeroCopy(Fory fory) {
    for (int i = 0; i < 4; i++) {
      ArraysData arraysData = new ArraysData(7 * i);
      copyCheck(fory, arraysData);
    }
  }

  @EqualsAndHashCode
  static class A {
    final int f1;

    A(int f1) {
      this.f1 = f1;
    }
  }

  @EqualsAndHashCode(callSuper = true)
  static class B extends A {
    final String f2;

    B(int f1, String f2) {
      super(f1);
      this.f2 = f2;
    }
  }

  @Data
  static class Struct {
    A[] arr;

    public Struct(A[] arr) {
      this.arr = arr;
    }
  }

  static class GenericArrayWrapper<T> {
    private final T[] array;

    @SuppressWarnings("unchecked")
    public GenericArrayWrapper(Class<T> clazz, int capacity) {
      this.array = (T[]) Array.newInstance(clazz, capacity);
    }
  }

  @SuppressWarnings("unchecked")
  @Test(dataProvider = "enableCodegen")
  public void testArrayPolyMorphic(boolean enableCodegen) {
    Fory fory = Fory.builder().requireClassRegistration(false).withCodegen(enableCodegen).build();
    Object[] arr = new String[] {"a", "b"};
    serDeCheck(fory, arr);

    A[] arr1 = new B[] {new B(1, "a"), new B(2, "b")};
    serDeCheck(fory, arr1);

    Struct struct1 = new Struct(arr1);
    serDeCheck(fory, struct1);
    A[] arr2 = new A[] {new A(1), new B(2, "b")};
    Struct struct2 = new Struct(arr2);
    serDeCheck(fory, struct2);

    final GenericArrayWrapper<String> wrapper = new GenericArrayWrapper<>(String.class, 2);
    wrapper.array[0] = "Hello";
    final byte[] bytes = fory.serialize(wrapper);
    final GenericArrayWrapper<String> deserialized =
        (GenericArrayWrapper<String>) fory.deserialize(bytes);
    deserialized.array[1] = "World";
    Assert.assertEquals(deserialized.array, new String[] {"Hello", "World"});
  }

  @SuppressWarnings("unchecked")
  @Test(dataProvider = "foryCopyConfig")
  public void testArrayPolyMorphic(Fory fory) {
    Object[] arr = new String[] {"a", "b"};
    copyCheck(fory, arr);

    A[] arr1 = new B[] {new B(1, "a"), new B(2, "b")};
    copyCheck(fory, arr1);

    Struct struct1 = new Struct(arr1);
    copyCheck(fory, struct1);
    A[] arr2 = new A[] {new A(1), new B(2, "b")};
    Struct struct2 = new Struct(arr2);
    copyCheck(fory, struct2);

    final GenericArrayWrapper<String> wrapper = new GenericArrayWrapper<>(String.class, 2);
    wrapper.array[0] = "Hello";
    wrapper.array[1] = "World";
    GenericArrayWrapper<String> copy = fory.copy(wrapper);
    Assert.assertEquals(copy.array, wrapper.array);
    Assert.assertNotSame(copy.array, wrapper.array);
    Assert.assertNotSame(copy, wrapper);
  }
}

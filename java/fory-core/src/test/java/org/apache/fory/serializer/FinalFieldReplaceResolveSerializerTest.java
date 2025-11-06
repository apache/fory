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
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.Language;
import org.testng.annotations.Test;

/**
 * Test class for FieldReplaceResolveSerializer.
 * This serializer is used for final fields that have
 * writeReplace/readResolve methods.
 */
public class FinalFieldReplaceResolveSerializerTest extends ForyTestBase {

  @Data
  public static class CustomReplaceClass1 implements Serializable {
    public transient String name;

    public CustomReplaceClass1(String name) {
      this.name = name;
    }

    private Object writeReplace() {
      return new Replaced(name);
    }

    private static final class Replaced implements Serializable {
      public String name;

      public Replaced(String name) {
        this.name = name;
      }

      private Object readResolve() {
        return new CustomReplaceClass1(name);
      }
    }
  }

  /** Container class with final field that uses writeReplace/readResolve */
  @Data
  @AllArgsConstructor
  public static class ContainerWithFinalReplaceField implements Serializable {
    private final CustomReplaceClass1 finalField;
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testFinalFieldReplace(boolean referenceTracking) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(referenceTracking)
            .build();
    CustomReplaceClass1 o1 = new CustomReplaceClass1("abc");
    ContainerWithFinalReplaceField container = new ContainerWithFinalReplaceField(o1);
    serDeCheck(fory, container);
    ContainerWithFinalReplaceField deserialized = serDe(fory, container);
    assertEquals(deserialized.getFinalField().getName(), "abc");
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testFinalFieldReplaceCopy(Fory fory) {
    CustomReplaceClass1 o1 = new CustomReplaceClass1("abc");
    ContainerWithFinalReplaceField container = new ContainerWithFinalReplaceField(o1);
    copyCheck(fory, container);
    ContainerWithFinalReplaceField copy = fory.copy(container);
    assertEquals(copy.getFinalField().getName(), "abc");
  }

  @Data
  public static class CustomReplaceClass2 implements Serializable {
    public boolean copy;
    public transient int age;

    public CustomReplaceClass2(boolean copy, int age) {
      this.copy = copy;
      this.age = age;
    }

    Object writeReplace() {
      if (age > 5) {
        return new Object[] {copy, age};
      } else {
        if (copy) {
          return new CustomReplaceClass2(copy, age);
        } else {
          return this;
        }
      }
    }

    Object readResolve() {
      if (copy) {
        return new CustomReplaceClass2(copy, age);
      }
      return this;
    }
  }

  @Data
  @AllArgsConstructor
  public static class ContainerWithFinalReplaceField2 implements Serializable {
    private final CustomReplaceClass2 finalField;
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testFinalFieldWriteReplaceCircularClass(boolean referenceTracking) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(referenceTracking)
            .build();
    for (Object inner :
        new Object[] {
          new CustomReplaceClass2(false, 2), new CustomReplaceClass2(true, 2),
        }) {
      ContainerWithFinalReplaceField2 container =
          new ContainerWithFinalReplaceField2((CustomReplaceClass2) inner);
      serDeCheck(fory, container);
    }
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testFinalFieldCopyReplaceCircularClass(Fory fory) {
    for (Object inner :
        new Object[] {
          new CustomReplaceClass2(false, 2), new CustomReplaceClass2(true, 2),
        }) {
      ContainerWithFinalReplaceField2 container =
          new ContainerWithFinalReplaceField2((CustomReplaceClass2) inner);
      copyCheck(fory, container);
    }
  }

  public static class CustomReplaceClass3 implements Serializable {
    public Object ref;

    private Object writeReplace() {
      return ref;
    }

    private Object readResolve() {
      return ref;
    }
  }

  @Data
  @AllArgsConstructor
  @EqualsAndHashCode
  public static class ContainerWithFinalReplaceField3 implements Serializable {
    private final CustomReplaceClass3 finalField;
  }

  @Test
  public void testFinalFieldWriteReplaceSameClassCircularRef() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .build();
    {
      CustomReplaceClass3 o1 = new CustomReplaceClass3();
      o1.ref = o1;
      ContainerWithFinalReplaceField3 container = new ContainerWithFinalReplaceField3(o1);
      ContainerWithFinalReplaceField3 o3 = serDe(fory, container);
      assertSame(o3.getFinalField().ref, o3.getFinalField());
    }
    {
      CustomReplaceClass3 o1 = new CustomReplaceClass3();
      CustomReplaceClass3 o2 = new CustomReplaceClass3();
      o1.ref = o2;
      o2.ref = o1;
      ContainerWithFinalReplaceField3 container = new ContainerWithFinalReplaceField3(o1);
      ContainerWithFinalReplaceField3 newContainer = serDe(fory, container);
      CustomReplaceClass3 newObj1 = newContainer.getFinalField();
      assertSame(newObj1.ref, newObj1);
      assertSame(((CustomReplaceClass3) newObj1.ref).ref, newObj1);
    }
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testFinalFieldWriteReplaceSameClassCircularRefCopy(Fory fory) {
    {
      CustomReplaceClass3 o1 = new CustomReplaceClass3();
      o1.ref = o1;
      ContainerWithFinalReplaceField3 container = new ContainerWithFinalReplaceField3(o1);
      ContainerWithFinalReplaceField3 copy = fory.copy(container);
      assertSame(copy.getFinalField(), copy.getFinalField().ref);
    }
    {
      CustomReplaceClass3 o1 = new CustomReplaceClass3();
      CustomReplaceClass3 o2 = new CustomReplaceClass3();
      o1.ref = o2;
      o2.ref = o1;
      ContainerWithFinalReplaceField3 container = new ContainerWithFinalReplaceField3(o1);
      ContainerWithFinalReplaceField3 copy = fory.copy(container);
      CustomReplaceClass3 newObj1 = copy.getFinalField();
      assertNotSame(newObj1.ref, o2);
    }
  }

  @Data
  @AllArgsConstructor
  public static class ContainerWithFinalImmutableList implements Serializable {
    private final ImmutableList<Integer> finalList;
  }

  @Data
  @AllArgsConstructor
  public static class ContainerWithFinalImmutableMap implements Serializable {
    private final ImmutableMap<String, Integer> finalMap;
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testFinalFieldImmutableList(boolean referenceTracking) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(referenceTracking)
            .build();
    ImmutableList<Integer> list1 = ImmutableList.of(1, 2, 3, 4);
    ContainerWithFinalImmutableList container = new ContainerWithFinalImmutableList(list1);
    serDeCheck(fory, container);
    ContainerWithFinalImmutableList deserialized = serDe(fory, container);
    assertEquals(deserialized.getFinalList(), list1);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testFinalFieldImmutableListCopy(Fory fory) {
    ImmutableList<Integer> list1 = ImmutableList.of(1, 2, 3, 4);
    ContainerWithFinalImmutableList container = new ContainerWithFinalImmutableList(list1);
    copyCheck(fory, container);
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testFinalFieldImmutableMap(boolean referenceTracking) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(referenceTracking)
            .build();
    ImmutableMap<String, Integer> map1 = ImmutableMap.of("k1", 1, "k2", 2);
    ContainerWithFinalImmutableMap container = new ContainerWithFinalImmutableMap(map1);
    serDeCheck(fory, container);
    ContainerWithFinalImmutableMap deserialized = serDe(fory, container);
    assertEquals(deserialized.getFinalMap(), map1);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testFinalFieldImmutableMapCopy(Fory fory) {
    ImmutableMap<String, Integer> map1 = ImmutableMap.of("k1", 1, "k2", 2);
    ContainerWithFinalImmutableMap container = new ContainerWithFinalImmutableMap(map1);
    copyCheck(fory, container);
  }

  @Data
  @AllArgsConstructor
  public static class ComplexContainerWithMultipleFinalFields implements Serializable {
    private final CustomReplaceClass1 field1;
    private final ImmutableList<String> field2;
    private final CustomReplaceClass2 field3;
    private final ImmutableMap<String, Integer> field4;
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testMultipleFinalFieldsWithReplace(boolean referenceTracking) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(referenceTracking)
            .build();
    ComplexContainerWithMultipleFinalFields container =
        new ComplexContainerWithMultipleFinalFields(
            new CustomReplaceClass1("test"),
            ImmutableList.of("a", "b", "c"),
            new CustomReplaceClass2(true, 3),
            ImmutableMap.of("k1", 1, "k2", 2));
    serDeCheck(fory, container);
    ComplexContainerWithMultipleFinalFields deserialized = serDe(fory, container);
    assertEquals(deserialized.getField1().getName(), "test");
    assertEquals(deserialized.getField2(), ImmutableList.of("a", "b", "c"));
    assertEquals(deserialized.getField4(), ImmutableMap.of("k1", 1, "k2", 2));
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testMultipleFinalFieldsWithReplaceCopy(Fory fory) {
    ComplexContainerWithMultipleFinalFields container =
        new ComplexContainerWithMultipleFinalFields(
            new CustomReplaceClass1("test"),
            ImmutableList.of("a", "b", "c"),
            new CustomReplaceClass2(true, 3),
            ImmutableMap.of("k1", 1, "k2", 2));
    copyCheck(fory, container);
  }

  /**
   * Verify that FieldReplaceResolveSerializer does NOT write class names.
   * This is the key optimization for final fields - since the type is known at compile time,
   * we don't need to write class information.
   */
  @Test
  public void testNoClassNameWrittenForFinalField() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(false)
            .build();

    // Create a container with a final ImmutableList field
    ContainerWithFinalImmutableList containerFinal =
        new ContainerWithFinalImmutableList(ImmutableList.of(1, 2, 3));
    byte[] bytesFinal = fory.serialize(containerFinal);

    // Create a container with a non-final ImmutableList field for comparison
    ContainerWithNonFinalImmutableList containerNonFinal =
        new ContainerWithNonFinalImmutableList(ImmutableList.of(1, 2, 3));
    byte[] bytesNonFinal = fory.serialize(containerNonFinal);

    // The final field version should use fewer bytes because it doesn't write class name
    assertTrue(
        bytesFinal.length < bytesNonFinal.length,
        String.format(
            "Final field serialization (%d bytes) should be smaller than non-final (%d bytes) "
                + "because class name is not written",
            bytesFinal.length, bytesNonFinal.length));

    // Verify deserialization still works correctly
    ContainerWithFinalImmutableList deserialized = (ContainerWithFinalImmutableList) fory.deserialize(bytesFinal);
    assertEquals(deserialized.getFinalList(), ImmutableList.of(1, 2, 3));
  }

  /** Container with non-final ImmutableList for comparison */
  @Data
  @AllArgsConstructor
  public static class ContainerWithNonFinalImmutableList implements Serializable {
    private ImmutableList<Integer> nonFinalList; // Not final, so ReplaceResolveSerializer is used
  }

  /**
   * Verify that the writeClassInfo field is null for FieldReplaceResolveSerializer.
   * This is what prevents class names from being written.
   */
  @Test
  public void testWriteClassInfoIsNull() throws Exception {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(false)
            .build();

    // Get the serializer for a final field with writeReplace
    // ImmutableList uses writeReplace internally
    ImmutableList<Integer> list = ImmutableList.of(1, 2, 3);
    Class<?> listClass = list.getClass();

    // Create FieldReplaceResolveSerializer as it would be used for a final field
    FinalFieldReplaceResolveSerializer finalFieldSerializer =
        new FinalFieldReplaceResolveSerializer(fory, listClass);

    // Use reflection to check that writeClassInfo is null
    java.lang.reflect.Field writeClassInfoField =
        ReplaceResolveSerializer.class.getDeclaredField("writeClassInfo");
    writeClassInfoField.setAccessible(true);
    Object writeClassInfo = writeClassInfoField.get(finalFieldSerializer);

    // For FieldReplaceResolveSerializer, writeClassInfo should be null
    assertEquals(
        writeClassInfo,
        null,
        "FieldReplaceResolveSerializer should have writeClassInfo=null to avoid writing class names");

    // Compare with ReplaceResolveSerializer (non-final)
    ReplaceResolveSerializer nonFinalFieldSerializer =
        new ReplaceResolveSerializer(fory, listClass, false);
    Object writeClassInfoNonFinal = writeClassInfoField.get(nonFinalFieldSerializer);

    // For ReplaceResolveSerializer (non-final), writeClassInfo should NOT be null
    assertTrue(
        writeClassInfoNonFinal != null,
        "ReplaceResolveSerializer (non-final) should have writeClassInfo set to write class names");
  }

  /**
   * Test that verifies the overridden writeObject method in FieldReplaceResolveSerializer
   * does NOT call classResolver.writeClassInternal().
   */
  @Test
  public void testWriteObjectSkipsClassNameWrite() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(false)
            .build();

    // Serialize using a final field container
    ContainerWithFinalImmutableList container =
        new ContainerWithFinalImmutableList(ImmutableList.of(1, 2, 3, 4, 5));

    byte[] bytes = fory.serialize(container);

    // Verify it can be deserialized correctly
    ContainerWithFinalImmutableList deserialized = (ContainerWithFinalImmutableList) fory.deserialize(bytes);
    assertEquals(deserialized.getFinalList(), ImmutableList.of(1, 2, 3, 4, 5));

    // The key point: FieldReplaceResolveSerializer.writeObject() directly calls
    // jdkMethodInfoCache.objectSerializer.write(buffer, value)
    // without calling classResolver.writeClassInternal(buffer, writeClassInfo)
  }
}

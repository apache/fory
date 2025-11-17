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
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.ImmutableIntArray;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Language;
import org.testng.annotations.Ignore;
import org.testng.annotations.Test;

/**
 * Test class for FieldReplaceResolveSerializer. This serializer is used for final fields that have
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

  public static class CustomReplaceClass3 implements Serializable {
    public Object ref;

    private Object writeReplace() {
      return ref;
    }

    private Object readResolve() {
      return ref;
    }
  }

  /** Container class with final field that uses writeReplace/readResolve */
  @Data
  @AllArgsConstructor
  public static class ContainerWithFinalReplaceField implements Serializable {
    private final CustomReplaceClass1 finalField;
  }

  @Data
  @AllArgsConstructor
  public static class ContainerWithNonFinalImmutableIntArray implements Serializable {
    private ImmutableIntArray nonFinalIntArray;
  }

  @Data
  @AllArgsConstructor
  public static class ContainerWithFinalReplaceField2 implements Serializable {
    private final CustomReplaceClass2 finalField;
  }

  @Data
  @AllArgsConstructor
  @EqualsAndHashCode
  public static class ContainerWithFinalReplaceField3 implements Serializable {
    private final CustomReplaceClass3 finalField;
  }

  @Data
  @AllArgsConstructor
  public static class ComplexContainerWithMultipleFinalFields implements Serializable {
    private final CustomReplaceClass1 field1;
    private final ImmutableList<String> field2;
    private final CustomReplaceClass2 field3;
    private final ImmutableMap<String, Integer> field4;
  }

  @Data
  @AllArgsConstructor
  public static class ContainerWithFinalImmutableIntArray implements Serializable {
    private final ImmutableIntArray intArray;
  }

  @Data
  @AllArgsConstructor
  public static class ContainerWithFinalImmutableMap implements Serializable {
    private final ImmutableMap<String, Integer> finalMap;
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

  @Test(dataProvider = "referenceTrackingConfig")
  public void testFinalFieldImmutableList(boolean referenceTracking) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(referenceTracking)
            .build();
    ImmutableIntArray list1 = ImmutableIntArray.of(1, 2, 3, 4);
    ContainerWithFinalImmutableIntArray container = new ContainerWithFinalImmutableIntArray(list1);
    serDeCheck(fory, container);
    ContainerWithFinalImmutableIntArray deserialized = serDe(fory, container);
    assertEquals(deserialized.getIntArray(), list1);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testFinalFieldImmutableListCopy(Fory fory) {
    ImmutableIntArray list1 = ImmutableIntArray.of(1, 2, 3, 4);
    ContainerWithFinalImmutableIntArray container = new ContainerWithFinalImmutableIntArray(list1);
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
   * Verify that the writeClassInfo field is null for FieldReplaceResolveSerializer. This is what
   * prevents class names from being written.
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
    ImmutableIntArray list = ImmutableIntArray.of(1, 2, 3);
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
    assertNull(
        writeClassInfo,
        "FieldReplaceResolveSerializer should have writeClassInfo=null to avoid writing class names");

    // Compare with ReplaceResolveSerializer (non-final)
    ReplaceResolveSerializer nonFinalFieldSerializer =
        new ReplaceResolveSerializer(fory, listClass, false);
    Object writeClassInfoNonFinal = writeClassInfoField.get(nonFinalFieldSerializer);

    // For ReplaceResolveSerializer (non-final), writeClassInfo should NOT be null
    assertNotNull(
        writeClassInfoNonFinal,
        "ReplaceResolveSerializer (non-final) should have writeClassInfo set to write class names");
  }

  @Test(dataProvider = "enableCodegen")
  public void testNoClassNameWrittenForFinalField(boolean codegen) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withCodegen(codegen)
            .withRefTracking(false)
            .build();

    // Create a container with a final ImmutableList field
    ContainerWithFinalImmutableIntArray containerFinal =
        new ContainerWithFinalImmutableIntArray(ImmutableIntArray.of(1, 2, 3));
    byte[] bytesFinal = fory.serialize(containerFinal);
    byte[] bytesFinal2 = fory.serialize(containerFinal);
    assertEquals(bytesFinal, bytesFinal2);

    // Create a container with a non-final ImmutableList field for comparison
    ContainerWithNonFinalImmutableIntArray containerNonFinal =
        new ContainerWithNonFinalImmutableIntArray(ImmutableIntArray.of(1, 2, 3));
    byte[] bytesNonFinal = fory.serialize(containerNonFinal);

    // The final field version should use fewer bytes because it doesn't write class name
    System.out.println(bytesFinal.length + " " + bytesNonFinal.length);
    assertTrue(
        bytesFinal.length < bytesNonFinal.length,
        String.format(
            "Final field serialization (%d bytes) should be smaller than non-final (%d bytes) "
                + "because class name is not written",
            bytesFinal.length, bytesNonFinal.length));

    // Verify deserialization still works correctly
    ContainerWithFinalImmutableIntArray deserialized =
        (ContainerWithFinalImmutableIntArray) fory.deserialize(bytesFinal);
    assertEquals(deserialized.getIntArray(), ImmutableIntArray.of(1, 2, 3));
  }

  /**
   * Test that verifies the overridden writeObject method in FieldReplaceResolveSerializer does NOT
   * call classResolver.writeClassInternal().
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
    ContainerWithFinalImmutableIntArray container =
        new ContainerWithFinalImmutableIntArray(ImmutableIntArray.of(1, 2, 3, 4, 5));

    byte[] bytes = fory.serialize(container);

    // Verify it can be deserialized correctly
    ContainerWithFinalImmutableIntArray deserialized =
        (ContainerWithFinalImmutableIntArray) fory.deserialize(bytes);
    assertEquals(deserialized.getIntArray(), ImmutableIntArray.of(1, 2, 3, 4, 5));

    // The key point: FieldReplaceResolveSerializer.writeObject() directly calls
    // jdkMethodInfoCache.objectSerializer.write(buffer, value)
    // without calling classResolver.writeClassInternal(buffer, writeClassInfo)
  }

  // TODO fix: bug with CompatibleMode and final field replace/resolve on main branch
  @Ignore
  @Test(dataProvider = "referenceTrackingConfig")
  public void testFinalFieldReplaceWithCompatibleModeFinalClass(boolean refTracking) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withCodegen(false)
            .withRefTracking(refTracking)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();

    ImmutableIntArray list1 = ImmutableIntArray.of(10, 20, 30);
    ContainerWithFinalImmutableIntArray containerList =
        new ContainerWithFinalImmutableIntArray(list1);
    serDeCheck(fory, containerList);
    ContainerWithFinalImmutableIntArray deserializedList = serDe(fory, containerList);
    assertEquals(deserializedList.getIntArray(), list1);
  }

  /**
   * Test that final fields with writeReplace/readResolve work correctly with
   * CompatibleMode.COMPATIBLE which uses MetaSharedSerializer instead of ObjectSerializer.
   */
  @Test(dataProvider = "referenceTrackingConfig")
  public void testFinalFieldReplaceWithCompatibleMode(boolean refTracking) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withCodegen(false)
            .withRefTracking(refTracking)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();

    // Test CustomReplaceClass1 with final field
    CustomReplaceClass1 o1 = new CustomReplaceClass1("test_compatible");
    ContainerWithFinalReplaceField container = new ContainerWithFinalReplaceField(o1);
    serDeCheck(fory, container);
    ContainerWithFinalReplaceField deserialized = serDe(fory, container);
    assertEquals(deserialized.getFinalField().getName(), "test_compatible");

    ImmutableMap<String, Integer> map1 = ImmutableMap.of("a", 100, "b", 200);
    ContainerWithFinalImmutableMap containerMap = new ContainerWithFinalImmutableMap(map1);
    serDeCheck(fory, containerMap);
    ContainerWithFinalImmutableMap deserializedMap = serDe(fory, containerMap);
    assertEquals(deserializedMap.getFinalMap(), map1);

    ComplexContainerWithMultipleFinalFields complexContainer =
        new ComplexContainerWithMultipleFinalFields(
            new CustomReplaceClass1("complex"),
            ImmutableList.of("x", "y", "z"),
            new CustomReplaceClass2(true, 5),
            ImmutableMap.of("key1", 111, "key2", 222));
    serDeCheck(fory, complexContainer);
    ComplexContainerWithMultipleFinalFields deserializedComplex = serDe(fory, complexContainer);
    assertEquals(deserializedComplex.getField1().getName(), "complex");
    assertEquals(deserializedComplex.getField2(), ImmutableList.of("x", "y", "z"));
    assertEquals(deserializedComplex.getField4(), ImmutableMap.of("key1", 111, "key2", 222));
  }
}

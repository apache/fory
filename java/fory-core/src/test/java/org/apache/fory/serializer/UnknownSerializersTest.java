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

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.lang.reflect.Array;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.TestUtils;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.codegen.CompileUnit;
import org.apache.fory.codegen.JaninoUtils;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.config.Language;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.MetaContext;
import org.apache.fory.test.bean.Struct;
import org.codehaus.commons.compiler.util.reflect.ByteArrayClassLoader;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class UnknownSerializersTest extends ForyTestBase {
  @DataProvider
  public static Object[][] config() {
    return Sets.cartesianProduct(
            ImmutableSet.of(true, false), // referenceTracking
            ImmutableSet.of(true, false), // fory1 enable codegen
            ImmutableSet.of(true, false) // fory2 enable codegen
            )
        .stream()
        .map(List::toArray)
        .toArray(Object[][]::new);
  }

  @DataProvider
  public static Object[][] metaShareConfig() {
    return Sets.cartesianProduct(
            ImmutableSet.of(true, false), // referenceTracking
            ImmutableSet.of(true, false), // fory1 enable codegen
            ImmutableSet.of(true, false), // fory2 enable codegen
            ImmutableSet.of(true, false)) // fory3 enable codegen
        .stream()
        .map(List::toArray)
        .toArray(Object[][]::new);
  }

  private ForyBuilder foryBuilder() {
    return builder()
        .withLanguage(Language.JAVA)
        .withCompatibleMode(CompatibleMode.COMPATIBLE)
        .requireClassRegistration(false)
        .withCodegen(false)
        .withDeserializeUnknownClass(true);
  }

  @Test(dataProvider = "config")
  public void testSkipUnknown(
      boolean referenceTracking, boolean enableCodegen1, boolean enableCodegen2) {
    Fory fory =
        foryBuilder()
            .withRefTracking(referenceTracking)
            .withCodegen(enableCodegen1)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    ClassLoader classLoader = getClass().getClassLoader();
    for (Class<?> structClass :
        new Class<?>[] {
          Struct.createNumberStructClass("TestUnknownEmptyStructClass1", 2),
          Struct.createStructClass("TestUnknownEmptyStructClass1", 2)
        }) {
      Object pojo = Struct.createPOJO(structClass);
      byte[] bytes = fory.serialize(pojo);
      Fory fory2 =
          foryBuilder()
              .withRefTracking(referenceTracking)
              .withCodegen(enableCodegen2)
              .withClassLoader(classLoader)
              .build();
      Object o = fory2.deserialize(bytes);
      assertTrue(o instanceof Unknown, "Unexpected type " + o.getClass());
    }
  }

  @Test
  public void testUnknownEnum() {
    // Use scoped meta share for automatic MetaContext management
    Fory fory = foryBuilder().withDeserializeUnknownClass(true).build();
    String enumCode = ("enum TestEnum {" + " A, B" + "}");
    Class<?> cls = JaninoUtils.compileClass(getClass().getClassLoader(), "", "TestEnum", enumCode);
    Object c = cls.getEnumConstants()[1];
    assertEquals(c.toString(), "B");
    byte[] bytes = fory.serialize(c);
    Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
    Fory fory2 = foryBuilder().withDeserializeUnknownClass(true).build();
    Object o = fory2.deserialize(bytes);
    assertEquals(o, Unknown.UnknownEnum.V1);
  }

  @Test
  public void testUnknownEnum_AsString() {
    // Use scoped meta share for automatic MetaContext management
    Fory fory =
        foryBuilder().withDeserializeUnknownClass(true).serializeEnumByName(true).build();
    String enumCode = ("enum TestEnum {" + " A, B" + "}");
    Class<?> cls = JaninoUtils.compileClass(getClass().getClassLoader(), "", "TestEnum", enumCode);
    Object c = cls.getEnumConstants()[1];
    assertEquals(c.toString(), "B");
    byte[] bytes = fory.serialize(c);
    Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
    Fory fory2 =
        foryBuilder().withDeserializeUnknownClass(true).serializeEnumByName(true).build();
    Object o = fory2.deserialize(bytes);
    assertEquals(o, Unknown.UnknownEnum.UNKNOWN);
  }

  @Test
  public void testUnknownEnumAndArrayField() throws Exception {
    String enumStructCode1 =
        ("public class TestEnumStruct {\n"
            + "  public enum TestEnum {\n"
            + "    A, B\n"
            + "  }\n"
            + "  public String f1;\n"
            + "  public TestEnum f2;\n"
            + "  public TestEnum[] f3;\n"
            + "  public TestEnum[][] f4;\n"
            + "}");
    Class<?> cls1 =
        JaninoUtils.compileClass(
            getClass().getClassLoader(), "", "TestEnumStruct", enumStructCode1);
    Class<?> enumClass = cls1.getDeclaredClasses()[0];
    Object o = cls1.newInstance();
    ReflectionUtils.setObjectFieldValue(o, "f1", "str");
    ReflectionUtils.setObjectFieldValue(o, "f2", enumClass.getEnumConstants()[1]);
    Object[] enumArray = (Object[]) Array.newInstance(enumClass, 2);
    enumArray[0] = enumClass.getEnumConstants()[0];
    enumArray[1] = enumClass.getEnumConstants()[1];
    ReflectionUtils.setObjectFieldValue(o, "f3", enumArray);
    Object[] enumArray2 = (Object[]) Array.newInstance(enumClass, 2, 2);
    enumArray2[0] = enumArray;
    enumArray2[1] = enumArray;
    ReflectionUtils.setObjectFieldValue(o, "f4", enumArray2);
    Fory fory1 =
        foryBuilder()
            .withDeserializeUnknownClass(true)
            .withClassLoader(cls1.getClassLoader())
            .build();
    byte[] bytes = fory1.serialize(o);
    {
      Object o1 = fory1.deserialize(bytes);
      assertEquals(ReflectionUtils.getObjectFieldValue(o1, "f2"), enumClass.getEnumConstants()[1]);
      assertEquals(ReflectionUtils.getObjectFieldValue(o1, "f3"), enumArray);
    }
    ByteArrayClassLoader classLoader =
        JaninoUtils.compile(
            getClass().getClassLoader(),
            new CompileUnit(
                "",
                "TestEnumStruct",
                ("public class TestEnumStruct {" + " public String f1;" + "}")));
    Fory fory2 =
        foryBuilder().withDeserializeUnknownClass(true).withClassLoader(classLoader).build();
    Object o1 = fory2.deserialize(bytes);
    Assert.assertEquals(ReflectionUtils.getObjectFieldValue(o1, "f1"), "str");
  }

  @DataProvider
  public Object[][] componentFinal() {
    return new Object[][] {{false}, {true}};
  }

  @Test(dataProvider = "componentFinal")
  public void testUnknownEmptyStructObjectArrayField(boolean componentFinal) throws Exception {
    String enumStructCode1 =
        ("public class TestArrayStruct {\n"
            + "  public static "
            + (componentFinal ? " final " : "")
            + "class TestClass {\n"
            + "  }\n"
            + "  public String f1;\n"
            + "  public TestClass f2;\n"
            + "  public TestClass[] f3;\n"
            + "  public TestClass[][] f4;\n"
            + "}");
    Class<?> cls1 =
        JaninoUtils.compile(
                getClass().getClassLoader(),
                new CompileUnit("", "TestArrayStruct", enumStructCode1))
            .loadClass("TestArrayStruct");
    Class<?> testClass = cls1.getDeclaredClasses()[0];
    Object o = cls1.newInstance();
    ReflectionUtils.setObjectFieldValue(o, "f1", "str");
    ReflectionUtils.setObjectFieldValue(o, "f2", testClass.newInstance());
    Object[] arr = (Object[]) Array.newInstance(testClass, 2);
    arr[0] = testClass.newInstance();
    arr[1] = testClass.newInstance();
    ReflectionUtils.setObjectFieldValue(o, "f3", arr);
    Object[] arr2D = (Object[]) Array.newInstance(testClass, 2, 2);
    arr2D[0] = arr;
    arr2D[1] = arr;
    ReflectionUtils.setObjectFieldValue(o, "f4", arr2D);
    // Use scoped meta share for automatic MetaContext management
    Fory fory1 =
        foryBuilder()
            .withDeserializeUnknownClass(true)
            .withClassLoader(cls1.getClassLoader())
            .build();
    byte[] bytes = fory1.serialize(o);
    {
      Object o1 = fory1.deserialize(bytes);
      assertEquals(ReflectionUtils.getObjectFieldValue(o1, "f2").getClass(), testClass);
      assertEquals(ReflectionUtils.getObjectFieldValue(o1, "f3").getClass(), arr.getClass());
    }
    ByteArrayClassLoader classLoader =
        JaninoUtils.compile(
            getClass().getClassLoader(),
            new CompileUnit(
                "",
                "TestArrayStruct",
                ("public class TestArrayStruct {" + " public String f1;" + "}")));
    Fory fory2 =
        foryBuilder().withDeserializeUnknownClass(true).withClassLoader(classLoader).build();
    Object o1 = fory2.deserialize(bytes);
    Assert.assertEquals(ReflectionUtils.getObjectFieldValue(o1, "f1"), "str");
  }

  @Test(dataProvider = "metaShareConfig")
  public void testDeserializeUnknownNewFory(
      boolean referenceTracking,
      boolean enableCodegen1,
      boolean enableCodegen2,
      boolean enableCodegen3) {
    Fory fory =
        foryBuilder()
            .withRefTracking(referenceTracking)
            .withCodegen(enableCodegen1)
            .withMetaShare(true)
            .build();
    ClassLoader classLoader = getClass().getClassLoader();
    for (Class<?> structClass :
        new Class<?>[] {
          Struct.createNumberStructClass("TestUnknownEmptyStructClass2", 2),
          Struct.createStructClass("TestUnknownEmptyStructClass2", 2)
        }) {
      Object pojo = Struct.createPOJO(structClass);
      MetaContext context1 = new MetaContext();
      fory.getSerializationContext().setMetaContext(context1);
      byte[] bytes = fory.serialize(pojo);
      Fory fory2 =
          foryBuilder()
              .withRefTracking(referenceTracking)
              .withCodegen(enableCodegen2)
              .withMetaShare(true)
              .withClassLoader(classLoader)
              .build();
      MetaContext context2 = new MetaContext();
      fory2.getSerializationContext().setMetaContext(context2);
      Object o2 = fory2.deserialize(bytes);
      assertEquals(o2.getClass(), Unknown.UnknownStruct.class);
      fory2.getSerializationContext().setMetaContext(context2);
      byte[] bytes2 = fory2.serialize(o2);
      Fory fory3 =
          foryBuilder()
              .withRefTracking(referenceTracking)
              .withCodegen(enableCodegen3)
              .withMetaShare(true)
              .withClassLoader(pojo.getClass().getClassLoader())
              .build();
      MetaContext context3 = new MetaContext();
      fory3.getSerializationContext().setMetaContext(context3);
      Object o3 = fory3.deserialize(bytes2);
      assertEquals(o3.getClass(), structClass);
      assertEquals(o3, pojo);
    }
  }

  @Test(dataProvider = "metaShareConfig")
  public void testDeserializeUnknown(
      boolean referenceTracking,
      boolean enableCodegen1,
      boolean enableCodegen2,
      boolean enableCodegen3) {
    Fory fory =
        foryBuilder()
            .withRefTracking(referenceTracking)
            .withCodegen(enableCodegen1)
            .withMetaShare(true)
            .build();
    MetaContext context1 = new MetaContext();
    MetaContext context2 = new MetaContext();
    MetaContext context3 = new MetaContext();
    ClassLoader classLoader = getClass().getClassLoader();
    for (Class<?> structClass :
        new Class<?>[] {
          Struct.createNumberStructClass("TestUnknownEmptyStructClass3", 2),
          Struct.createStructClass("TestUnknownEmptyStructClass3", 2)
        }) {
      Fory fory2 =
          foryBuilder()
              .withRefTracking(referenceTracking)
              .withCodegen(enableCodegen2)
              .withMetaShare(true)
              .withClassLoader(classLoader)
              .build();
      Fory fory3 =
          foryBuilder()
              .withRefTracking(referenceTracking)
              .withCodegen(enableCodegen3)
              .withMetaShare(true)
              .withClassLoader(structClass.getClassLoader())
              .build();
      for (int i = 0; i < 2; i++) {
        Object pojo = Struct.createPOJO(structClass);
        fory.getSerializationContext().setMetaContext(context1);
        byte[] bytes = fory.serialize(pojo);

        fory2.getSerializationContext().setMetaContext(context2);
        Object o2 = fory2.deserialize(bytes);
        assertEquals(o2.getClass(), Unknown.UnknownStruct.class);
        fory2.getSerializationContext().setMetaContext(context2);
        byte[] bytes2 = fory2.serialize(o2);

        fory3.getSerializationContext().setMetaContext(context3);
        Object o3 = fory3.deserialize(bytes2);
        assertEquals(o3.getClass(), structClass);
        assertEquals(o3, pojo);
      }
    }
  }

  @Test
  public void testThrowExceptionIfClassNotExist() {
    // Use scoped meta share for automatic MetaContext management
    Fory fory = foryBuilder().withDeserializeUnknownClass(false).build();
    ClassLoader classLoader = getClass().getClassLoader();
    Class<?> structClass = Struct.createNumberStructClass("TestUnknownEmptyStructClass1", 2);
    Object pojo = Struct.createPOJO(structClass);
    Fory fory2 =
        foryBuilder().withDeserializeUnknownClass(false).withClassLoader(classLoader).build();
    byte[] bytes = fory.serialize(pojo);
    assertThrowsCause(RuntimeException.class, () -> fory2.deserialize(bytes));
  }

  /**
   * Simple test class with primitive types for UnknownClass serialization testing. Avoids
   * collection types which require complex type registration in xlang mode.
   */
  @Data
  static class SimpleTestClass {
    // Primitive fields
    byte byteField;
    short shortField;
    int intField;
    long longField;
    float floatField;
    double doubleField;
    boolean boolField;

    // Boxed fields
    Integer boxedInt;
    Long boxedLong;
    Float boxedFloat;
    Double boxedDouble;
    Boolean boxedBool;

    // String field
    String stringField;

    // Nullable fields
    @ForyField(nullable = true)
    Integer nullableInt;

    @ForyField(nullable = true)
    String nullableString;
  }

  /** Create a populated test object with all fields set. */
  private static SimpleTestClass createTestObject() {
    SimpleTestClass obj = new SimpleTestClass();
    // Primitive fields
    obj.byteField = 1;
    obj.shortField = 2;
    obj.intField = 42;
    obj.longField = 123456789L;
    obj.floatField = 1.5f;
    obj.doubleField = 2.5;
    obj.boolField = true;

    // Boxed fields
    obj.boxedInt = 10;
    obj.boxedLong = 20L;
    obj.boxedFloat = 1.1f;
    obj.boxedDouble = 2.2;
    obj.boxedBool = true;

    // String field
    obj.stringField = "hello";

    // Nullable fields - set values
    obj.nullableInt = 100;
    obj.nullableString = "nullable_value";

    return obj;
  }

  /**
   * Test that UnknownClass correctly preserves field values when deserializing an unknown
   * class. This simulates the scenario where fory2 doesn't have the class registered, so it
   * deserializes to UnknownStruct.
   */
  @Test(dataProvider = "language")
  public void testUnknownClassDeserializationPreservesValues(Language language) {
    // Fory1: serializer with class registered
    Fory fory1 =
        Fory.builder()
            .withLanguage(language)
            .withCodegen(false)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    fory1.register(SimpleTestClass.class, "test.SimpleTestClass");

    // Fory2: deserializer without class registered - will use UnknownClassSerializer
    Fory fory2 =
        Fory.builder()
            .withLanguage(language)
            .withCodegen(false)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    // Don't register SimpleTestClass - fory2 doesn't know this class

    // Create and serialize object with fory1
    SimpleTestClass obj = createTestObject();
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(1024);
    fory1.serialize(buffer, obj);

    // Convert original object to map for comparison
    Map<String, Object> expectedMap = TestUtils.objectToMap(fory1, obj);

    // Deserialize with fory2 - should return UnknownStruct
    buffer.readerIndex(0);
    Object result = fory2.deserialize(buffer);

    // Verify result is UnknownStruct
    assertEquals(result.getClass(), Unknown.UnknownStruct.class);

    Unknown.UnknownStruct nonexistent =
        (Unknown.UnknownStruct) result;

    // Convert UnknownStruct to a map keyed by simple field name
    Map<String, Object> actualMap = new HashMap<>();
    for (Object key : nonexistent.keySet()) {
      String qualifiedKey = (String) key;
      // Extract simple field name from "className.fieldName"
      String simpleFieldName = qualifiedKey.substring(qualifiedKey.lastIndexOf('.') + 1);
      actualMap.put(simpleFieldName, nonexistent.get(key));
    }

    // Verify all field values are preserved (compare by simple field name)
    for (Map.Entry<String, Object> entry : expectedMap.entrySet()) {
      String qualifiedFieldName = entry.getKey();
      String simpleFieldName =
          qualifiedFieldName.substring(qualifiedFieldName.lastIndexOf('.') + 1);
      Object expectedValue = entry.getValue();
      Object actualValue = actualMap.get(simpleFieldName);

      assertEquals(
          actualValue, expectedValue, String.format("Field '%s' value mismatch", simpleFieldName));
    }
  }

  /** Test UnknownClass with null values in nullable fields. */
  @Test(dataProvider = "language")
  public void testUnknownClassDeserializationWithNulls(Language language) {
    // Fory1: serializer with class registered
    Fory fory1 =
        Fory.builder()
            .withLanguage(language)
            .withCodegen(false)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    fory1.register(SimpleTestClass.class, "test.SimpleTestClass");

    // Fory2: deserializer without class registered
    Fory fory2 =
        Fory.builder()
            .withLanguage(language)
            .withCodegen(false)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();

    // Create object with null nullable fields
    SimpleTestClass obj = createTestObject();
    obj.nullableInt = null;
    obj.nullableString = null;

    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(1024);
    fory1.serialize(buffer, obj);

    Map<String, Object> expectedMap = TestUtils.objectToMap(fory1, obj);

    buffer.readerIndex(0);
    Object result = fory2.deserialize(buffer);

    assertEquals(result.getClass(), Unknown.UnknownStruct.class);
    Unknown.UnknownStruct nonexistent =
        (Unknown.UnknownStruct) result;

    // Convert UnknownStruct to a map keyed by simple field name
    Map<String, Object> actualMap = new HashMap<>();
    for (Object key : nonexistent.keySet()) {
      String qualifiedKey = (String) key;
      String simpleFieldName = qualifiedKey.substring(qualifiedKey.lastIndexOf('.') + 1);
      actualMap.put(simpleFieldName, nonexistent.get(key));
    }

    // Verify values including nulls (compare by simple field name)
    for (Map.Entry<String, Object> entry : expectedMap.entrySet()) {
      String qualifiedFieldName = entry.getKey();
      String simpleFieldName =
          qualifiedFieldName.substring(qualifiedFieldName.lastIndexOf('.') + 1);
      Object expectedValue = entry.getValue();
      Object actualValue = actualMap.get(simpleFieldName);

      assertEquals(
          actualValue, expectedValue, String.format("Field '%s' value mismatch", simpleFieldName));
    }
  }

  /**
   * Test that UnknownStruct can be serialized and deserialized again by the same Fory
   * instance that doesn't know the class. This verifies that unknown class data is preserved across
   * serialization cycles.
   */
  @Test(dataProvider = "language")
  public void testUnknownClassRoundTripWithinSameFory(Language language) {
    // Fory1: knows the class
    Fory fory1 =
        Fory.builder()
            .withLanguage(language)
            .withCodegen(false)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    fory1.register(SimpleTestClass.class, "test.SimpleTestClass");

    // Fory2: doesn't know the class
    Fory fory2 =
        Fory.builder()
            .withLanguage(language)
            .withCodegen(false)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();

    // Step 1: Serialize with fory1
    SimpleTestClass original = createTestObject();
    Map<String, Object> expectedMap = TestUtils.objectToMap(fory1, original);
    MemoryBuffer buffer1 = MemoryBuffer.newHeapBuffer(1024);
    fory1.serialize(buffer1, original);

    // Step 2: Deserialize with fory2 (gets UnknownStruct)
    buffer1.readerIndex(0);
    Object nonexistent1 = fory2.deserialize(buffer1);
    assertEquals(nonexistent1.getClass(), Unknown.UnknownStruct.class);

    // Step 3: Serialize UnknownStruct with fory2
    byte[] bytes = fory2.serialize(nonexistent1);

    // Step 4: Deserialize again with fory2 (should get UnknownStruct with same values)
    Object nonexistent2 = fory2.deserialize(bytes);
    assertEquals(nonexistent2.getClass(), Unknown.UnknownStruct.class);

    // Verify values are preserved across the round-trip
    Unknown.UnknownStruct result =
        (Unknown.UnknownStruct) nonexistent2;
    Map<String, Object> actualMap = new HashMap<>();
    for (Object key : result.keySet()) {
      String qualifiedKey = (String) key;
      String simpleFieldName = qualifiedKey.substring(qualifiedKey.lastIndexOf('.') + 1);
      actualMap.put(simpleFieldName, result.get(key));
    }

    for (Map.Entry<String, Object> entry : expectedMap.entrySet()) {
      String qualifiedFieldName = entry.getKey();
      String simpleFieldName =
          qualifiedFieldName.substring(qualifiedFieldName.lastIndexOf('.') + 1);
      Object expectedValue = entry.getValue();
      Object actualValue = actualMap.get(simpleFieldName);

      assertEquals(
          actualValue,
          expectedValue,
          String.format("Field '%s' value mismatch after round-trip", simpleFieldName));
    }
  }
}

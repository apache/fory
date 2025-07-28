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
import java.util.List;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.codegen.CompileUnit;
import org.apache.fory.codegen.JaninoUtils;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.config.Language;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.MetaContext;
import org.apache.fory.test.bean.Struct;
import org.codehaus.commons.compiler.util.reflect.ByteArrayClassLoader;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class NonexistentClassSerializersTest extends ForyTestBase {
  @DataProvider
  public static Object[][] config() {
    return Sets.cartesianProduct(
            ImmutableSet.of(true, false), // referenceTracking
            ImmutableSet.of(true, false), // scoped meta share
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

  private ForyBuilder foryBuilder(boolean scoped) {
    return builder()
        .withLanguage(Language.JAVA)
        .withCompatibleMode(CompatibleMode.COMPATIBLE)
        .requireClassRegistration(false)
        .withCodegen(false)
        .withScopedMetaShare(scoped)
        .withDeserializeNonexistentClass(true);
  }

  @Test(dataProvider = "config")
  public void testSkipNonexistent(
      boolean referenceTracking,
      boolean scopedMetaShare,
      boolean enableCodegen1,
      boolean enableCodegen2) {
    Fory fory =
        foryBuilder(scopedMetaShare)
            .withRefTracking(referenceTracking)
            .withCodegen(enableCodegen1)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    ClassLoader classLoader = getClass().getClassLoader();
    for (Class<?> structClass :
        new Class<?>[] {
          Struct.createNumberStructClass("TestSkipNonexistentClass1", 2),
          Struct.createStructClass("TestSkipNonexistentClass1", 2)
        }) {
      Object pojo = Struct.createPOJO(structClass);
      byte[] bytes = fory.serialize(pojo);
      Fory fory2 =
          foryBuilder(scopedMetaShare)
              .withRefTracking(referenceTracking)
              .withCodegen(enableCodegen2)
              .withClassLoader(classLoader)
              .build();
      Object o = fory2.deserialize(bytes);
      assertTrue(o instanceof NonexistentClass, "Unexpected type " + o.getClass());
    }
  }

  @Test(dataProvider = "scopedMetaShare")
  public void testNonexistentEnum(boolean scopedMetaShare) {
    Fory fory = foryBuilder(scopedMetaShare).withDeserializeNonexistentClass(true).build();
    String enumCode = ("enum TestEnum {" + " A, B" + "}");
    Class<?> cls = JaninoUtils.compileClass(getClass().getClassLoader(), "", "TestEnum", enumCode);
    Object c = cls.getEnumConstants()[1];
    assertEquals(c.toString(), "B");
    byte[] bytes = fory.serialize(c);
    Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
    Fory fory2 = foryBuilder(scopedMetaShare).withDeserializeNonexistentClass(true).build();
    Object o = fory2.deserialize(bytes);
    assertEquals(o, NonexistentClass.NonexistentEnum.V1);
  }

  @Test(dataProvider = "scopedMetaShare")
  public void testNonexistentEnum_AsString(boolean scopedMetaShare) {
    Fory fory =
        foryBuilder(scopedMetaShare)
            .withDeserializeNonexistentClass(true)
            .serializeEnumByName(true)
            .build();
    String enumCode = ("enum TestEnum {" + " A, B" + "}");
    Class<?> cls = JaninoUtils.compileClass(getClass().getClassLoader(), "", "TestEnum", enumCode);
    Object c = cls.getEnumConstants()[1];
    assertEquals(c.toString(), "B");
    byte[] bytes = fory.serialize(c);
    Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
    Fory fory2 =
        foryBuilder(scopedMetaShare)
            .withDeserializeNonexistentClass(true)
            .serializeEnumByName(true)
            .build();
    Object o = fory2.deserialize(bytes);
    assertEquals(o, NonexistentClass.NonexistentEnum.UNKNOWN);
  }

  @Test(dataProvider = "scopedMetaShare")
  public void testNonexistentEnumAndArrayField(boolean scopedMetaShare) throws Exception {
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
        foryBuilder(scopedMetaShare)
            .withDeserializeNonexistentClass(true)
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
        foryBuilder(scopedMetaShare)
            .withDeserializeNonexistentClass(true)
            .withClassLoader(classLoader)
            .build();
    Object o1 = fory2.deserialize(bytes);
    Assert.assertEquals(ReflectionUtils.getObjectFieldValue(o1, "f1"), "str");
  }

  @DataProvider
  public Object[][] componentFinal() {
    return new Object[][] {{false}, {true}};
  }

  @Test(dataProvider = "componentFinal")
  public void testSkipNonexistentObjectArrayField(boolean componentFinal) throws Exception {
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
    Fory fory1 =
        foryBuilder(false)
            .withDeserializeNonexistentClass(true)
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
        foryBuilder(false)
            .withDeserializeNonexistentClass(true)
            .withClassLoader(classLoader)
            .build();
    Object o1 = fory2.deserialize(bytes);
    Assert.assertEquals(ReflectionUtils.getObjectFieldValue(o1, "f1"), "str");
  }

  @Test(dataProvider = "metaShareConfig")
  public void testDeserializeNonexistentNewFory(
      boolean referenceTracking,
      boolean enableCodegen1,
      boolean enableCodegen2,
      boolean enableCodegen3) {
    Fory fory =
        foryBuilder(false)
            .withRefTracking(referenceTracking)
            .withCodegen(enableCodegen1)
            .withMetaShare(true)
            .build();
    ClassLoader classLoader = getClass().getClassLoader();
    for (Class<?> structClass :
        new Class<?>[] {
          Struct.createNumberStructClass("TestSkipNonexistentClass2", 2),
          Struct.createStructClass("TestSkipNonexistentClass2", 2)
        }) {
      Object pojo = Struct.createPOJO(structClass);
      MetaContext context1 = new MetaContext();
      fory.getSerializationContext().setMetaContext(context1);
      byte[] bytes = fory.serialize(pojo);
      Fory fory2 =
          foryBuilder(false)
              .withRefTracking(referenceTracking)
              .withCodegen(enableCodegen2)
              .withMetaShare(true)
              .withClassLoader(classLoader)
              .build();
      MetaContext context2 = new MetaContext();
      fory2.getSerializationContext().setMetaContext(context2);
      Object o2 = fory2.deserialize(bytes);
      assertEquals(o2.getClass(), NonexistentClass.NonexistentMetaShared.class);
      fory2.getSerializationContext().setMetaContext(context2);
      byte[] bytes2 = fory2.serialize(o2);
      Fory fory3 =
          foryBuilder(false)
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
  public void testDeserializeNonexistent(
      boolean referenceTracking,
      boolean enableCodegen1,
      boolean enableCodegen2,
      boolean enableCodegen3) {
    Fory fory =
        foryBuilder(false)
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
          Struct.createNumberStructClass("TestSkipNonexistentClass3", 2),
          Struct.createStructClass("TestSkipNonexistentClass3", 2)
        }) {
      Fory fory2 =
          foryBuilder(false)
              .withRefTracking(referenceTracking)
              .withCodegen(enableCodegen2)
              .withMetaShare(true)
              .withClassLoader(classLoader)
              .build();
      Fory fory3 =
          foryBuilder(false)
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
        assertEquals(o2.getClass(), NonexistentClass.NonexistentMetaShared.class);
        fory2.getSerializationContext().setMetaContext(context2);
        byte[] bytes2 = fory2.serialize(o2);

        fory3.getSerializationContext().setMetaContext(context3);
        Object o3 = fory3.deserialize(bytes2);
        assertEquals(o3.getClass(), structClass);
        assertEquals(o3, pojo);
      }
    }
  }

  @Test(dataProvider = "scopedMetaShare")
  public void testThrowExceptionIfClassNotExist(boolean scopedMetaShare) {
    Fory fory = foryBuilder(scopedMetaShare).withDeserializeNonexistentClass(false).build();
    ClassLoader classLoader = getClass().getClassLoader();
    Class<?> structClass = Struct.createNumberStructClass("TestSkipNonexistentClass1", 2);
    Object pojo = Struct.createPOJO(structClass);
    Fory fory2 =
        foryBuilder(scopedMetaShare)
            .withDeserializeNonexistentClass(false)
            .withClassLoader(classLoader)
            .build();
    byte[] bytes = fory.serialize(pojo);
    Assert.assertThrows(RuntimeException.class, () -> fory2.deserialize(bytes));
  }
}

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
import org.apache.fory.codegen.JaninoUtils;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.config.Language;
import org.apache.fory.exception.DeserializationException;
import org.testng.annotations.Test;

public class EnumSerializerTest extends ForyTestBase {

  @Test
  public void testWrite() {}

  public enum EnumFoo {
    A,
    B
  }

  public enum EnumSubClass {
    A {
      @Override
      void f() {}
    },
    B {
      @Override
      void f() {}
    };

    abstract void f();
  }

  @Test(dataProvider = "crossLanguageReferenceTrackingConfig")
  public void testEnumSerialization(boolean referenceTracking, Language language) {
    ForyBuilder builder =
        Fory.builder()
            .withLanguage(language)
            .withRefTracking(referenceTracking)
            .requireClassRegistration(false);
    Fory fory1 = builder.build();
    Fory fory2 = builder.build();
    if (fory1.isCrossLanguage()) {
      fory1.register(EnumSerializerTest.EnumFoo.class);
      fory2.register(EnumSerializerTest.EnumFoo.class);
      fory1.register(EnumSerializerTest.EnumSubClass.class);
      fory2.register(EnumSerializerTest.EnumSubClass.class);
    }
    assertEquals(EnumFoo.A, serDe(fory1, fory2, EnumFoo.A));
    assertEquals(EnumFoo.B, serDe(fory1, fory2, EnumFoo.B));
    assertEquals(EnumSubClass.A, serDe(fory1, fory2, EnumSubClass.A));
    assertEquals(EnumSubClass.B, serDe(fory1, fory2, EnumSubClass.B));
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testEnumSerializer(Fory fory) {
    copyCheckWithoutSame(fory, EnumFoo.A);
    copyCheckWithoutSame(fory, EnumFoo.B);
    copyCheckWithoutSame(fory, EnumSubClass.A);
    copyCheckWithoutSame(fory, EnumSubClass.B);
  }

  @Test
  public void testEnumSerializationUnexistentEnumValueAsNull() {
    String enumCode2 = "enum TestEnum2 {" + " A;" + "}";
    String enumCode1 = "enum TestEnum2 {" + " A, B" + "}";
    Class<?> cls1 =
        JaninoUtils.compileClass(getClass().getClassLoader(), "", "TestEnum2", enumCode1);
    Class<?> cls2 =
        JaninoUtils.compileClass(getClass().getClassLoader(), "", "TestEnum2", enumCode2);
    ForyBuilder builderSerialization =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .requireClassRegistration(false);
    ForyBuilder builderDeserialize =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .deserializeUnknownEnumValueAsNull(true)
            .withClassLoader(cls2.getClassLoader());
    Fory foryDeserialize = builderDeserialize.build();
    Fory forySerialization = builderSerialization.build();
    byte[] bytes = forySerialization.serialize(cls1.getEnumConstants()[1]);
    Object data = foryDeserialize.deserialize(bytes);
    assertNull(data);
  }

  @Test
  public void testEnumSerializationAsString() {
    String enumCode1 = "enum TestEnum1 {" + " A, B;" + "}";
    String enumCode2 = "enum TestEnum1 {" + " B;" + "}";
    Class<?> cls1 =
        JaninoUtils.compileClass(getClass().getClassLoader(), "", "TestEnum1", enumCode1);
    Class<?> cls2 =
        JaninoUtils.compileClass(getClass().getClassLoader(), "", "TestEnum1", enumCode2);

    Fory foryDeserialize =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .serializeEnumByName(true)
            .withAsyncCompilation(false)
            .build();
    Fory forySerialization =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .serializeEnumByName(true)
            .withAsyncCompilation(false)
            .build();

    // serialize enum "B"
    forySerialization.register(cls1);
    byte[] bytes = forySerialization.serializeJavaObject(cls1.getEnumConstants()[1]);

    foryDeserialize.register(cls2);
    Object data = foryDeserialize.deserializeJavaObject(bytes, cls2);
    assertEquals(cls2.getEnumConstants()[0], data);
  }

  @Test
  public void testEnumSerializationAsString_differentClass() {
    String enumCode1 = "enum TestEnum1 {" + " A, B;" + "}";
    String enumCode2 = "enum TestEnum2 {" + " B;" + "}";
    Class<?> cls1 =
        JaninoUtils.compileClass(getClass().getClassLoader(), "", "TestEnum1", enumCode1);
    Class<?> cls2 =
        JaninoUtils.compileClass(getClass().getClassLoader(), "", "TestEnum2", enumCode2);

    Fory foryDeserialize =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .serializeEnumByName(true)
            .withAsyncCompilation(false)
            .build();
    Fory forySerialization =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .serializeEnumByName(true)
            .withAsyncCompilation(false)
            .build();

    // serialize enum "B"
    forySerialization.register(cls1);
    byte[] bytes = forySerialization.serializeJavaObject(cls1.getEnumConstants()[1]);

    foryDeserialize.register(cls2);
    Object data = foryDeserialize.deserializeJavaObject(bytes, cls2);
    assertEquals(cls2.getEnumConstants()[0], data);
  }

  @Test
  public void testEnumSerializationAsString_invalidEnum() {
    String enumCode1 = "enum TestEnum1 {" + " A;" + "}";
    String enumCode2 = "enum TestEnum2 {" + " B;" + "}";
    Class<?> cls1 =
        JaninoUtils.compileClass(getClass().getClassLoader(), "", "TestEnum1", enumCode1);
    Class<?> cls2 =
        JaninoUtils.compileClass(getClass().getClassLoader(), "", "TestEnum2", enumCode2);

    Fory foryDeserialize =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .serializeEnumByName(true)
            .withAsyncCompilation(false)
            .build();
    Fory forySerialization =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .serializeEnumByName(true)
            .withAsyncCompilation(false)
            .build();

    forySerialization.register(cls1);
    byte[] bytes = forySerialization.serializeJavaObject(cls1.getEnumConstants()[0]);

    try {
      foryDeserialize.register(cls2);
      foryDeserialize.deserializeJavaObject(bytes, cls2);
      fail("expected to throw exception");
    } catch (Exception e) {
      assertTrue(e instanceof DeserializationException);
      assertTrue(e.getCause() instanceof IllegalArgumentException);
    }
  }

  @Test
  public void testEnumSerializationAsString_nullValue() {
    String enumCode1 = "enum TestEnum1 {" + " A;" + "}";
    String enumCode2 = "enum TestEnum2 {" + " B;" + "}";
    Class<?> cls1 =
        JaninoUtils.compileClass(getClass().getClassLoader(), "", "TestEnum1", enumCode1);
    Class<?> cls2 =
        JaninoUtils.compileClass(getClass().getClassLoader(), "", "TestEnum2", enumCode2);

    Fory foryDeserialize =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .serializeEnumByName(true)
            .withAsyncCompilation(false)
            .build();
    Fory forySerialization =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .serializeEnumByName(true)
            .withAsyncCompilation(false)
            .build();

    byte[] bytes = forySerialization.serializeJavaObject(null);

    Object data = foryDeserialize.deserializeJavaObject(bytes, cls2);
    assertNull(data);
  }

  @Data
  @AllArgsConstructor
  static class EnumSubclassFieldTest {
    EnumSubClass subEnum;
  }

  @Test(dataProvider = "enableCodegen")
  public void testEnumSubclassField(boolean enableCodegen) {
    serDeCheck(
        builder().withCodegen(enableCodegen).build(), new EnumSubclassFieldTest(EnumSubClass.B));
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testEnumSubclassField(Fory fory) {
    copyCheck(fory, new EnumSubclassFieldTest(EnumSubClass.B));
  }

  @Test(dataProvider = "scopedMetaShare")
  public void testEnumSubclassFieldCompatible(boolean scopedMetaShare) {
    serDeCheck(
        builder()
            .withScopedMetaShare(scopedMetaShare)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build(),
        new EnumSubclassFieldTest(EnumSubClass.B));
  }
}

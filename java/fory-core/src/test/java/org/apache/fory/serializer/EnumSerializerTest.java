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

import java.lang.reflect.Field;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.annotation.ForyEnumId;
import org.apache.fory.codegen.JaninoUtils;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.exception.DeserializationException;
import org.apache.fory.memory.MemoryBuffer;
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

  public enum EnumWithIdField {
    A(10),
    B(20),
    C(30);

    @ForyEnumId private final int id;

    EnumWithIdField(int id) {
      this.id = id;
    }
  }

  public enum EnumWithIdMethod {
    A(100),
    B(200),
    C(300);

    private final int id;

    EnumWithIdMethod(int id) {
      this.id = id;
    }

    @ForyEnumId
    public int getId() {
      return id;
    }
  }

  public enum EnumWithConstantIds {
    @ForyEnumId(3)
    A,
    @ForyEnumId(7)
    B,
    @ForyEnumId(11)
    C
  }

  public enum EnumWithLargeIds {
    A(4096),
    B(8192);

    private final int id;

    EnumWithLargeIds(int id) {
      this.id = id;
    }

    @ForyEnumId
    public int getId() {
      return id;
    }
  }

  public enum EnumWithPartialConstantIds {
    @ForyEnumId(1)
    A,
    B
  }

  public enum EnumWithDuplicateIds {
    A(1),
    B(1);

    private final int id;

    EnumWithDuplicateIds(int id) {
      this.id = id;
    }

    @ForyEnumId
    public int getId() {
      return id;
    }
  }

  public enum EnumWithConflictingIdStrategies {
    @ForyEnumId(1)
    A(10),
    @ForyEnumId(2)
    B(20);

    @ForyEnumId private final int id;

    EnumWithConflictingIdStrategies(int id) {
      this.id = id;
    }
  }

  @Test(dataProvider = "crossLanguageReferenceTrackingConfig")
  public void testEnumSerialization(boolean referenceTracking, boolean xlang) {
    ForyBuilder builder =
        Fory.builder()
            .withXlang(xlang)
            .withRefTracking(referenceTracking)
            .requireClassRegistration(false);
    Fory fory1 = builder.build();
    Fory fory2 = builder.build();
    if (fory1.getConfig().isXlang()) {
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
  public void testEnumSerializationUsesOrdinalArrayByDefault() throws Exception {
    Fory fory = Fory.builder().requireClassRegistration(false).build();
    EnumSerializer serializer = getEnumSerializer(fory, EnumFoo.class);

    assertEquals(writeEnumTag(fory, serializer, EnumFoo.B), 1);
    assertNotNull(readPrivateField(serializer, "enumConstantByTagArray"));
    assertNull(readPrivateField(serializer, "enumConstantByTagMap"));
  }

  @Test
  public void testEnumSerializationUsesAnnotatedFieldId() {
    Fory fory = Fory.builder().requireClassRegistration(false).build();
    EnumSerializer serializer = getEnumSerializer(fory, EnumWithIdField.class);

    assertEquals(writeEnumTag(fory, serializer, EnumWithIdField.B), 20);
    assertEquals(serDe(fory, fory, EnumWithIdField.C), EnumWithIdField.C);
  }

  @Test
  public void testEnumSerializationUsesAnnotatedMethodId() {
    Fory fory = Fory.builder().requireClassRegistration(false).build();
    EnumSerializer serializer = getEnumSerializer(fory, EnumWithIdMethod.class);

    assertEquals(writeEnumTag(fory, serializer, EnumWithIdMethod.B), 200);
    assertEquals(serDe(fory, fory, EnumWithIdMethod.A), EnumWithIdMethod.A);
  }

  @Test
  public void testEnumSerializationUsesAnnotatedConstantId() {
    Fory fory = Fory.builder().requireClassRegistration(false).build();
    EnumSerializer serializer = getEnumSerializer(fory, EnumWithConstantIds.class);

    assertEquals(writeEnumTag(fory, serializer, EnumWithConstantIds.B), 7);
    assertEquals(serDe(fory, fory, EnumWithConstantIds.C), EnumWithConstantIds.C);
  }

  @Test
  public void testEnumSerializationUsesSparseMapForLargeIds() throws Exception {
    Fory fory = Fory.builder().requireClassRegistration(false).build();
    EnumSerializer serializer = getEnumSerializer(fory, EnumWithLargeIds.class);

    assertEquals(writeEnumTag(fory, serializer, EnumWithLargeIds.B), 8192);
    assertNull(readPrivateField(serializer, "enumConstantByTagArray"));
    assertNotNull(readPrivateField(serializer, "enumConstantByTagMap"));
  }

  @Test
  public void testEnumSerializationRejectsPartialConstantIds() {
    Fory fory = Fory.builder().requireClassRegistration(false).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> getEnumSerializer(fory, EnumWithPartialConstantIds.class));
  }

  @Test
  public void testEnumSerializationRejectsDuplicateIds() {
    Fory fory = Fory.builder().requireClassRegistration(false).build();
    assertThrows(
        IllegalArgumentException.class, () -> getEnumSerializer(fory, EnumWithDuplicateIds.class));
  }

  @Test
  public void testEnumSerializationRejectsConflictingIdStrategies() {
    Fory fory = Fory.builder().requireClassRegistration(false).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> getEnumSerializer(fory, EnumWithConflictingIdStrategies.class));
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
        Fory.builder().withXlang(false).withRefTracking(true).requireClassRegistration(false);
    ForyBuilder builderDeserialize =
        Fory.builder()
            .withXlang(false)
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
            .withXlang(false)
            .withCompatible(true)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .serializeEnumByName(true)
            .withAsyncCompilation(false)
            .build();
    Fory forySerialization =
        Fory.builder()
            .withXlang(false)
            .withCompatible(true)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .serializeEnumByName(true)
            .withAsyncCompilation(false)
            .build();

    // serialize enum "B"
    forySerialization.register(cls1);
    byte[] bytes = forySerialization.serialize(cls1.getEnumConstants()[1]);

    foryDeserialize.register(cls2);
    Object data = foryDeserialize.deserialize(bytes, cls2);
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
            .withXlang(false)
            .withCompatible(true)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .serializeEnumByName(true)
            .withAsyncCompilation(false)
            .build();
    Fory forySerialization =
        Fory.builder()
            .withXlang(false)
            .withCompatible(true)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .serializeEnumByName(true)
            .withAsyncCompilation(false)
            .build();

    // serialize enum "B"
    forySerialization.register(cls1);
    byte[] bytes = forySerialization.serialize(cls1.getEnumConstants()[1]);

    foryDeserialize.register(cls2);
    Object data = foryDeserialize.deserialize(bytes, cls2);
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
            .withXlang(false)
            .withCompatible(true)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .serializeEnumByName(true)
            .withAsyncCompilation(false)
            .build();
    Fory forySerialization =
        Fory.builder()
            .withXlang(false)
            .withCompatible(true)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .serializeEnumByName(true)
            .withAsyncCompilation(false)
            .build();

    forySerialization.register(cls1);
    byte[] bytes = forySerialization.serialize(cls1.getEnumConstants()[0]);

    try {
      foryDeserialize.register(cls2);
      foryDeserialize.deserialize(bytes, cls2);
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
            .withXlang(false)
            .withCompatible(true)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .serializeEnumByName(true)
            .withAsyncCompilation(false)
            .build();
    Fory forySerialization =
        Fory.builder()
            .withXlang(false)
            .withCompatible(true)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .serializeEnumByName(true)
            .withAsyncCompilation(false)
            .build();

    byte[] bytes = forySerialization.serialize(null);

    Object data = foryDeserialize.deserialize(bytes, cls2);
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
        builder().withScopedMetaShare(scopedMetaShare).withCompatible(true).build(),
        new EnumSubclassFieldTest(EnumSubClass.B));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static EnumSerializer getEnumSerializer(Fory fory, Class<? extends Enum> enumClass) {
    return (EnumSerializer) fory.getSerializer((Class) enumClass);
  }

  private static int writeEnumTag(Fory fory, EnumSerializer serializer, Enum value) {
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(16);
    try {
      fory.getWriteContext().prepare(buffer, null);
      serializer.write(fory.getWriteContext(), value);
      return buffer.readVarUInt32Small7();
    } finally {
      fory.getWriteContext().reset();
    }
  }

  private static Object readPrivateField(Object target, String fieldName) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(target);
  }
}

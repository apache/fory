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

package org.apache.fory.integration_tests;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.apache.fory.Fory;
import org.apache.fory.integration_tests.model.NonSerializableNoNoArgBean;
import org.apache.fory.integration_tests.model.PrivateFieldBean;
import org.apache.fory.integration_tests.publicserializer.PublicSerializerValue;
import org.apache.fory.integration_tests.publicserializer.PublicSerializerValueSerializer;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.serializer.CodegenSerializer.LazyInitBeanSerializer;
import org.apache.fory.serializer.Serializer;
import org.testng.Assert;
import org.testng.annotations.Test;

public class JpmsFieldAccessorTest {
  private static final int JDK_MAJOR_VERSION = Runtime.version().feature();
  private static final String VAR_HANDLE = "java.lang.invoke.VarHandle";

  @Test
  public void testPrivateFieldAccess() throws Exception {
    PrivateFieldBean bean = new PrivateFieldBean(7);
    Field field = PrivateFieldBean.class.getDeclaredField("value");
    FieldAccessor accessor = FieldAccessor.createAccessor(field);
    Assert.assertEquals(accessor.getInt(bean), 7);
    accessor.putInt(bean, 9);
    Assert.assertEquals(bean.value(), 9);
  }

  @Test
  public void testPrivateFinalFieldSerialization() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withCodegen(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    PrivateFieldBean result =
        (PrivateFieldBean) fory.deserialize(fory.serialize(new PrivateFieldBean(13)));
    Assert.assertEquals(result.value(), 13);
  }

  @Test
  public void testNonSerializableNoNoArgSerialization() {
    if (JDK_MAJOR_VERSION < 25) {
      return;
    }
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withCodegen(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    byte[] bytes = fory.serialize(new NonSerializableNoNoArgBean(5, 13));
    NonSerializableNoNoArgBean.resetParentConstructorCalls();
    NonSerializableNoNoArgBean result = (NonSerializableNoNoArgBean) fory.deserialize(bytes);
    Assert.assertEquals(result.parentValue(), 5);
    Assert.assertEquals(result.value(), 13);
    Assert.assertEquals(NonSerializableNoNoArgBean.parentConstructorCalls(), 0);
  }

  @Test
  public void testSetFromMapJdkFieldRestore() throws Exception {
    if (JDK_MAJOR_VERSION < 25) {
      return;
    }
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withCodegen(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    Map<String, Boolean> backingMap = Collections.synchronizedMap(new HashMap<>());
    Set<String> set = Collections.newSetFromMap(backingMap);
    set.add("alpha");
    set.add("beta");

    Set<?> result = (Set<?>) fory.deserialize(fory.serialize(set));
    Assert.assertEquals(result, set);
    Field mapField = result.getClass().getDeclaredField("m");
    Object restoredMap = FieldAccessor.createAccessor(mapField).getObject(result);
    Assert.assertEquals(restoredMap.getClass().getName(), backingMap.getClass().getName());

    Set<?> copied = fory.copy(set);
    Assert.assertEquals(copied, set);
    Object copiedMap = FieldAccessor.createAccessor(mapField).getObject(copied);
    Assert.assertEquals(copiedMap.getClass().getName(), backingMap.getClass().getName());
  }

  @Test
  public void testCodegenFinalFieldAccess() throws Exception {
    if (JDK_MAJOR_VERSION < 25) {
      return;
    }
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withCodegen(true)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    PrivateFieldBean result =
        (PrivateFieldBean) fory.deserialize(fory.serialize(new PrivateFieldBean(17)));
    Assert.assertEquals(result.value(), 17);

    Class<?> serializerClass = serializerClass(fory, PrivateFieldBean.class);
    // Runtime codegen uses the normal CodeGenerator classloader path. JDK25+ final-field access is
    // guaranteed by the generated VarHandle field, not by hidden-nestmate source access.
    assertVarHandleField(serializerClass, "value");
  }

  @Test
  public void testReflectionFinalWriteDenied() throws Exception {
    if (JDK_MAJOR_VERSION < 26) {
      return;
    }
    PrivateFieldBean bean = new PrivateFieldBean(29);
    Field field = PrivateFieldBean.class.getDeclaredField("value");
    field.setAccessible(true);
    try {
      field.setInt(bean, 31);
      Assert.fail("JDK26 denial mode should reject ordinary final-field reflection writes");
    } catch (IllegalAccessException | RuntimeException expected) {
      Assert.assertEquals(bean.value(), 29);
    }
  }

  @Test
  public void testPublicSerializerInExportedPackage() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    fory.registerSerializer(PublicSerializerValue.class, PublicSerializerValueSerializer.class);
    PublicSerializerValue result =
        (PublicSerializerValue) fory.deserialize(fory.serialize(new PublicSerializerValue(11)));
    Assert.assertEquals(result.value, 11);
  }

  private static Class<?> serializerClass(Fory fory, Class<?> type) {
    Serializer<?> serializer = fory.getTypeResolver().getSerializer(type);
    if (serializer instanceof LazyInitBeanSerializer) {
      return ((LazyInitBeanSerializer<?>) serializer).getGeneratedSerializerClass();
    }
    return serializer.getClass();
  }

  private static void assertVarHandleField(Class<?> serializerClass, String fieldName) {
    for (Field field : serializerClass.getDeclaredFields()) {
      if (field.getName().contains(fieldName + "_varHandle_")) {
        Assert.assertEquals(field.getType().getName(), VAR_HANDLE);
        int modifiers = field.getModifiers();
        Assert.assertTrue(Modifier.isStatic(modifiers));
        Assert.assertTrue(Modifier.isFinal(modifiers));
        return;
      }
    }
    Assert.fail("Missing generated VarHandle field for " + fieldName + " in " + serializerClass);
  }
}

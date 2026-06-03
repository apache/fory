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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.TestUtils;
import org.apache.fory.builder.CodecUtils;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.test.bean.Cyclic;
import org.apache.fory.util.Preconditions;
import org.testng.Assert;
import org.testng.annotations.Test;

@SuppressWarnings("unchecked")
public class ObjectSerializerTest extends ForyTestBase {

  @Test
  public void testLocalClass() {
    String str = "str";
    class Foo {
      public String foo(String s) {
        return str + s;
      }
    }
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .build();
    ObjectSerializer serializer = new ObjectSerializer(fory.getTypeResolver(), Foo.class);
    MemoryBuffer buffer = MemoryUtils.buffer(32);
    Foo foo = new Foo();
    writeSerializer(fory, serializer, buffer, foo);
    Object obj = readSerializer(fory, serializer, buffer);
    assertEquals(foo.foo("str"), ((Foo) obj).foo("str"));
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testLocalClass(Fory fory) {
    String str = "str";
    class Foo {
      public String foo(String s) {
        return str + s;
      }
    }
    ObjectSerializer serializer = new ObjectSerializer(fory.getTypeResolver(), Foo.class);
    Foo foo = new Foo();
    Object obj = withCopyContext(fory, context -> serializer.copy(context, foo));
    assertEquals(foo.foo("str"), ((Foo) obj).foo("str"));
    Assert.assertNotSame(foo, obj);
  }

  @Test
  public void testAnonymousClass() {
    String str = "str";
    class Foo {
      public String foo(String s) {
        return str + s;
      }
    }
    Foo foo =
        new Foo() {
          @Override
          public String foo(String s) {
            return "Anonymous " + s;
          }
        };
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .build();
    ObjectSerializer serializer = new ObjectSerializer(fory.getTypeResolver(), foo.getClass());
    MemoryBuffer buffer = MemoryUtils.buffer(32);
    writeSerializer(fory, serializer, buffer, foo);
    Object obj = readSerializer(fory, serializer, buffer);
    assertEquals(foo.foo("str"), ((Foo) obj).foo("str"));
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testAnonymousClass(Fory fory) {
    String str = "str";
    class Foo {
      public String foo(String s) {
        return str + s;
      }
    }
    Foo foo =
        new Foo() {
          @Override
          public String foo(String s) {
            return "Anonymous " + s;
          }
        };
    ObjectSerializer serializer = new ObjectSerializer(fory.getTypeResolver(), foo.getClass());
    Object obj = withCopyContext(fory, context -> serializer.copy(context, foo));
    assertEquals(foo.foo("str"), ((Foo) obj).foo("str"));
    assertNotSame(foo, obj);
  }

  @Test
  public void testSerializeCircularReference() {
    Cyclic cyclic = Cyclic.create(true);
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();
    MemoryBuffer buffer = MemoryUtils.buffer(32);

    ObjectSerializer<Cyclic> serializer =
        new ObjectSerializer<>(fory.getTypeResolver(), Cyclic.class);
    withWriteContext(
        fory,
        buffer,
        context -> {
          context.writeRefOrNull(cyclic);
          serializer.write(context, cyclic);
        });
    Cyclic cyclic1 =
        withReadContext(
            fory,
            buffer,
            context -> {
              byte tag = context.readRefOrNull();
              Preconditions.checkArgument(tag == Fory.REF_VALUE_FLAG);
              context.preserveRefId();
              return serializer.read(context);
            });
    fory.reset();
    assertEquals(cyclic1, cyclic);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testCopyCircularReference(Fory fory) {
    Cyclic cyclic = Cyclic.create(true);
    ObjectSerializer<Cyclic> serializer =
        new ObjectSerializer<>(fory.getTypeResolver(), Cyclic.class);
    Cyclic cyclic1 = withCopyContext(fory, context -> serializer.copy(context, cyclic));
    assertEquals(cyclic1, cyclic);
    assertNotSame(cyclic1, cyclic);
  }

  public static final class FinalNoArgBean {
    private final int id;
    private final String name;
    private int count;

    public FinalNoArgBean() {
      id = -1;
      name = "default";
    }

    private FinalNoArgBean(int value, String text, int total) {
      id = value;
      name = text;
      count = total;
    }
  }

  public static final class FinalPostCtorBean {
    private final int id;
    private String label;

    public FinalPostCtorBean(String label) {
      id = -1;
      this.label = label;
    }

    private FinalPostCtorBean(int value, String label) {
      id = value;
      this.label = label;
    }
  }

  public static class PrivateNoArgParent {
    static int noArgCalls;
    private final String parentName;

    private PrivateNoArgParent() {
      noArgCalls++;
      parentName = "no-arg";
    }

    PrivateNoArgParent(String parentName) {
      this.parentName = parentName;
    }

    String parentName() {
      return parentName;
    }
  }

  public static final class SerializablePrivateParentBean extends PrivateNoArgParent
      implements Serializable {
    private String childName;

    public SerializablePrivateParentBean(String parentName, String childName) {
      super(parentName);
      this.childName = childName;
    }
  }

  @Test
  public void testFinalNoArgRestore() {
    FinalNoArgBean value = new FinalNoArgBean(7, "source", 9);
    for (boolean codegen : new boolean[] {false, true}) {
      Fory fory =
          Fory.builder()
              .withXlang(false)
              .withRefTracking(true)
              .withCodegen(codegen)
              .requireClassRegistration(false)
              .build();
      FinalNoArgBean newValue = (FinalNoArgBean) fory.deserialize(fory.serialize(value));
      assertEquals(newValue.id, value.id);
      assertEquals(newValue.name, value.name);
      assertEquals(newValue.count, value.count);
    }
  }

  @Test
  public void testFinalNoArgRestoreCodegen() {
    FinalNoArgBean value = new FinalNoArgBean(7, "source", 9);
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .withCodegen(true)
            .requireClassRegistration(false)
            .build();
    Serializer<FinalNoArgBean> serializer =
        Serializers.newSerializer(
            fory,
            FinalNoArgBean.class,
            CodecUtils.loadOrGenObjectCodecClass(FinalNoArgBean.class, fory));
    FinalNoArgBean newValue = roundTripWithSerializer(fory, serializer, value);
    assertEquals(newValue.id, value.id);
    assertEquals(newValue.name, value.name);
    assertEquals(newValue.count, value.count);
  }

  @Test
  public void testFinalPostCtorRestore() {
    FinalPostCtorBean value = new FinalPostCtorBean(8, "ctor");
    for (boolean codegen : new boolean[] {false, true}) {
      Fory fory =
          Fory.builder()
              .withXlang(false)
              .withRefTracking(true)
              .withCodegen(codegen)
              .requireClassRegistration(false)
              .build();
      FinalPostCtorBean newValue = (FinalPostCtorBean) fory.deserialize(fory.serialize(value));
      assertEquals(newValue.id, value.id);
      assertEquals(newValue.label, value.label);
    }
  }

  @Test
  public void testFinalPostCtorCodegen() {
    FinalPostCtorBean value = new FinalPostCtorBean(8, "ctor");
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .withCodegen(true)
            .requireClassRegistration(false)
            .build();
    Serializer<FinalPostCtorBean> serializer =
        Serializers.newSerializer(
            fory,
            FinalPostCtorBean.class,
            CodecUtils.loadOrGenObjectCodecClass(FinalPostCtorBean.class, fory));
    FinalPostCtorBean newValue = roundTripWithSerializer(fory, serializer, value);
    assertEquals(newValue.id, value.id);
    assertEquals(newValue.label, value.label);
  }

  @Test
  public void testNormalObjectSerializerBypassesObjectStreamParentRules() {
    SerializablePrivateParentBean value = new SerializablePrivateParentBean("parent", "child");
    for (boolean codegen : new boolean[] {false, true}) {
      PrivateNoArgParent.noArgCalls = 0;
      Fory fory =
          Fory.builder()
              .withXlang(false)
              .withRefTracking(true)
              .withCodegen(codegen)
              .requireClassRegistration(false)
              .build();
      Serializer<?> serializer =
          fory.getTypeResolver().getTypeInfo(SerializablePrivateParentBean.class).getSerializer();
      Assert.assertFalse(
          serializer instanceof ObjectStreamSerializer, serializer.getClass().getName());
      SerializablePrivateParentBean newValue =
          (SerializablePrivateParentBean) fory.deserialize(fory.serialize(value));
      assertEquals(newValue.parentName(), value.parentName());
      assertEquals(newValue.childName, value.childName);
      assertEquals(PrivateNoArgParent.noArgCalls, 0);
    }
  }

  private static <T> T roundTripWithSerializer(Fory fory, Serializer<T> serializer, T value) {
    MemoryBuffer buffer = MemoryUtils.buffer(32);
    withWriteContext(
        fory,
        buffer,
        context -> {
          context.writeRefOrNull(value);
          serializer.write(context, value);
        });
    T newValue =
        withReadContext(
            fory,
            buffer,
            context -> {
              byte tag = context.readRefOrNull();
              Preconditions.checkArgument(tag == Fory.REF_VALUE_FLAG);
              context.preserveRefId();
              return serializer.read(context);
            });
    fory.reset();
    return newValue;
  }

  @Data
  public static class A {
    Integer f1;
    Integer f2;
    Long f3;
    int f4;
    int f5;
    Integer f6;
    Long f7;
  }

  @Test
  public void testSerialization() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .build();
    MemoryBuffer buffer = MemoryUtils.buffer(32);
    ObjectSerializer<A> serializer = new ObjectSerializer<>(fory.getTypeResolver(), A.class);
    A a = new A();
    writeSerializer(fory, serializer, buffer, a);
    assertEquals(a, readSerializer(fory, serializer, buffer));
    assertEquals(a, withCopyContext(fory, context -> serializer.copy(context, a)));
  }

  @Test
  public void testAndroidReflectionPaths() throws Exception {
    ProcessBuilder processBuilder =
        new ProcessBuilder(TestUtils.javaCommand(AndroidObjectSerializerProbe.class))
            .redirectErrorStream(true);
    processBuilder.environment().put("FORY_ANDROID_ENABLED", "1");
    Process process = processBuilder.start();
    String output = readFully(process.getInputStream());
    Assert.assertEquals(process.waitFor(), 0, output);
  }

  private static String readFully(InputStream inputStream) throws IOException {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    int read;
    while ((read = inputStream.read(buffer)) != -1) {
      outputStream.write(buffer, 0, read);
    }
    return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
  }

  public static final class AndroidObjectSerializerProbe {
    public static void main(String[] args) {
      System.setProperty("java.vm.name", "Dalvik");
      System.setProperty("java.runtime.name", "Android Runtime");
      check(AndroidSupport.IS_ANDROID, "AndroidSupport should detect Dalvik runtime");

      Fory fory =
          Fory.builder()
              .withXlang(false)
              .withCodegen(true)
              .withRefTracking(true)
              .requireClassRegistration(false)
              .build();
      check(!fory.getConfig().isCodeGenEnabled(), "Android must force codegen off");

      PrivateAndroidBean bean = PrivateAndroidBean.create();
      PrivateAndroidBean restored = (PrivateAndroidBean) fory.deserialize(fory.serialize(bean));
      bean.assertSameData(restored);
      check(bean != restored, "Deserialization should create a new object");

      PrivateAndroidBean copied = fory.copy(bean);
      bean.assertSameData(copied);
      check(bean != copied, "Copy should create a new object");
      check(bean.child != copied.child, "Nested object should be copied");
    }

    private static void check(boolean value, String message) {
      if (!value) {
        throw new AssertionError(message);
      }
    }
  }

  private static final class PrivateAndroidBean {
    private int id;
    private long count;
    private Integer boxed;
    private NestedAndroidBean child;

    private PrivateAndroidBean() {}

    private static PrivateAndroidBean create() {
      PrivateAndroidBean bean = new PrivateAndroidBean();
      bean.id = 42;
      bean.count = 123456789L;
      bean.boxed = 77;
      bean.child = new NestedAndroidBean();
      bean.child.value = 9;
      return bean;
    }

    private void assertSameData(PrivateAndroidBean other) {
      Assert.assertEquals(other.id, id);
      Assert.assertEquals(other.count, count);
      Assert.assertEquals(other.boxed, boxed);
      Assert.assertNotNull(other.child);
      Assert.assertEquals(other.child.value, child.value);
    }
  }

  private static final class NestedAndroidBean {
    private int value;

    private NestedAndroidBean() {}
  }
}

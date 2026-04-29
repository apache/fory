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
import static org.testng.Assert.assertTrue;

import java.io.Serializable;
import java.util.Objects;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.codegen.JaninoUtils;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests that writeReplace-based serialization works correctly when the proxy class is unavailable
 * on the deserializing JVM. This reproduces the Hibernate proxy cross-JVM ClassNotFoundException
 * bug.
 *
 * <p>The root cause: when Fory serializes an unregistered class that has writeReplace(), it writes
 * the original class name in the outer type info (as NAMED_EXT). On a different JVM where that
 * class doesn't exist, deserialization fails with ClassNotFoundException before the
 * ReplaceResolveSerializer even gets a chance to read the replacement object.
 */
public class WriteReplaceCrossJvmTest extends ForyTestBase {

  /** A simple entity class that both JVMs know about. */
  public static class RealEntity implements Serializable {
    private static final long serialVersionUID = 1L;
    public String name;
    public int value;

    public RealEntity() {}

    public RealEntity(String name, int value) {
      this.name = name;
      this.value = value;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      RealEntity that = (RealEntity) o;
      return value == that.value && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, value);
    }
  }

  /**
   * A proxy class that simulates a Hibernate proxy. It extends RealEntity and has writeReplace()
   * that returns a RealEntity. This class only exists on the "serializing JVM".
   */
  public static class ProxyEntity extends RealEntity {
    private static final long serialVersionUID = 1L;

    public ProxyEntity() {}

    public ProxyEntity(String name, int value) {
      super(name, value);
    }

    private Object writeReplace() {
      return new RealEntity(name, value);
    }
  }

  @DataProvider
  public static Object[][] referenceTrackingAndCodegen() {
    return new Object[][] {
      {false, false},
      {true, false},
      {false, true},
      {true, true},
    };
  }

  /**
   * Tests that a proxy object serialized on one JVM can be deserialized on another JVM that doesn't
   * have the proxy class.
   *
   * <p>This simulates the Hibernate proxy scenario: - JVM A has both ProxyEntity and RealEntity -
   * JVM B only has RealEntity - ProxyEntity.writeReplace() returns a RealEntity
   *
   * <p>Before the fix, this test fails with ClassNotFoundException because the proxy class name is
   * written in the outer type info.
   */
  @Test(dataProvider = "referenceTrackingAndCodegen")
  public void testWriteReplaceCrossJvm(boolean refTracking, boolean codegen) {
    // fory1: "JVM A" - knows both ProxyEntity and RealEntity
    Fory fory1 =
        builder()
            .withRefTracking(refTracking)
            .withCodegen(codegen)
            .requireClassRegistration(false)
            .build();

    ProxyEntity proxy = new ProxyEntity("test-entity", 42);
    byte[] bytes = fory1.serialize(proxy);

    // fory2: "JVM B" - only knows RealEntity (uses parent classloader which
    // can't see ProxyEntity since we use a filtering classloader)
    ClassLoader restrictedLoader =
        new ClassLoader(getClass().getClassLoader()) {
          @Override
          public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(ProxyEntity.class.getName())) {
              throw new ClassNotFoundException("Simulated: " + name + " not on this JVM");
            }
            return super.loadClass(name, resolve);
          }
        };

    Fory fory2 =
        builder()
            .withRefTracking(refTracking)
            .withCodegen(codegen)
            .requireClassRegistration(false)
            .withClassLoader(restrictedLoader)
            .build();

    Object result = fory2.deserialize(bytes);
    assertNotNull(result);
    assertEquals(result.getClass(), RealEntity.class);
    RealEntity entity = (RealEntity) result;
    assertEquals(entity.name, "test-entity");
    assertEquals(entity.value, 42);
  }

  /**
   * Tests cross-JVM writeReplace with a dynamically generated proxy class. This more closely
   * simulates how Hibernate creates ByteBuddy proxy classes that don't exist on the remote JVM.
   */
  @Test(dataProvider = "referenceTrackingAndCodegen")
  public void testWriteReplaceCrossJvmDynamicProxy(boolean refTracking, boolean codegen) {
    // Compile a dynamic "proxy" class that extends RealEntity with writeReplace.
    // Use protected (not private) because Janino mangles private methods with $ suffix.
    String proxyCode =
        "import org.apache.fory.serializer.WriteReplaceCrossJvmTest.RealEntity;\n"
            + "public class DynamicProxy extends RealEntity {\n"
            + "  public DynamicProxy() {}\n"
            + "  public DynamicProxy(String name, int value) { super(name, value); }\n"
            + "  protected Object writeReplace() { return new RealEntity(name, value); }\n"
            + "}";

    Class<?> proxyClass =
        JaninoUtils.compileClass(getClass().getClassLoader(), "", "DynamicProxy", proxyCode);

    // Create a proxy instance via reflection
    Object proxy;
    try {
      proxy = proxyClass.getConstructor(String.class, int.class).newInstance("dynamic-entity", 99);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    // fory1: serialize using the classloader that knows the proxy
    Fory fory1 =
        builder()
            .withRefTracking(refTracking)
            .withCodegen(codegen)
            .requireClassRegistration(false)
            .withClassLoader(proxyClass.getClassLoader())
            .build();
    byte[] bytes = fory1.serialize(proxy);

    // fory2: deserialize using this class's classloader (can't see DynamicProxy)
    Fory fory2 =
        builder()
            .withRefTracking(refTracking)
            .withCodegen(codegen)
            .requireClassRegistration(false)
            .withClassLoader(getClass().getClassLoader())
            .build();

    Object result = fory2.deserialize(bytes);
    assertNotNull(result);
    assertEquals(result.getClass(), RealEntity.class);
    RealEntity entity = (RealEntity) result;
    assertEquals(entity.name, "dynamic-entity");
    assertEquals(entity.value, 99);
  }

  /**
   * Regression test: same-JVM writeReplace still works correctly. When the proxy class IS
   * available, deserialization should still unwrap to the real entity via readResolve.
   */
  @Test(dataProvider = "referenceTrackingAndCodegen")
  public void testWriteReplaceSameJvmStillWorks(boolean refTracking, boolean codegen) {
    Fory fory =
        builder()
            .withRefTracking(refTracking)
            .withCodegen(codegen)
            .requireClassRegistration(false)
            .build();

    ProxyEntity proxy = new ProxyEntity("same-jvm", 7);
    byte[] bytes = fory.serialize(proxy);
    Object result = fory.deserialize(bytes);

    assertNotNull(result);
    // writeReplace returns RealEntity, so deserialized type should be RealEntity
    assertEquals(result.getClass(), RealEntity.class);
    RealEntity entity = (RealEntity) result;
    assertEquals(entity.name, "same-jvm");
    assertEquals(entity.value, 7);
  }

  /**
   * Tests that writeReplace returning the same type (but different instance) still works correctly
   * across JVMs.
   */
  public static class SameTypeReplaceEntity implements Serializable {
    private static final long serialVersionUID = 1L;
    public String data;
    public boolean replaced;

    public SameTypeReplaceEntity() {}

    public SameTypeReplaceEntity(String data, boolean replaced) {
      this.data = data;
      this.replaced = replaced;
    }

    private Object writeReplace() {
      return new SameTypeReplaceEntity(data, true);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      SameTypeReplaceEntity that = (SameTypeReplaceEntity) o;
      return replaced == that.replaced && Objects.equals(data, that.data);
    }

    @Override
    public int hashCode() {
      return Objects.hash(data, replaced);
    }
  }

  @Test(dataProvider = "referenceTrackingAndCodegen")
  public void testWriteReplaceSameType(boolean refTracking, boolean codegen) {
    Fory fory =
        builder()
            .withRefTracking(refTracking)
            .withCodegen(codegen)
            .requireClassRegistration(false)
            .build();

    SameTypeReplaceEntity original = new SameTypeReplaceEntity("hello", false);
    byte[] bytes = fory.serialize(original);
    SameTypeReplaceEntity result = (SameTypeReplaceEntity) fory.deserialize(bytes);

    assertEquals(result.data, "hello");
    assertTrue(result.replaced, "writeReplace should have set replaced=true");
  }

  /**
   * Tests cross-JVM round trip: serialize proxy on JVM A, deserialize on JVM B, re-serialize on JVM
   * B, deserialize back on JVM A.
   */
  @Test(dataProvider = "referenceTrackingAndCodegen")
  public void testWriteReplaceCrossJvmRoundTrip(boolean refTracking, boolean codegen) {
    Fory fory1 =
        builder()
            .withRefTracking(refTracking)
            .withCodegen(codegen)
            .requireClassRegistration(false)
            .build();

    ClassLoader restrictedLoader =
        new ClassLoader(getClass().getClassLoader()) {
          @Override
          public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(ProxyEntity.class.getName())) {
              throw new ClassNotFoundException("Simulated: " + name + " not on this JVM");
            }
            return super.loadClass(name, resolve);
          }
        };

    Fory fory2 =
        builder()
            .withRefTracking(refTracking)
            .withCodegen(codegen)
            .requireClassRegistration(false)
            .withClassLoader(restrictedLoader)
            .build();

    // Step 1: Serialize proxy on "JVM A"
    ProxyEntity proxy = new ProxyEntity("round-trip", 100);
    byte[] bytes1 = fory1.serialize(proxy);

    // Step 2: Deserialize on "JVM B" (no ProxyEntity class)
    Object intermediate = fory2.deserialize(bytes1);
    assertEquals(intermediate.getClass(), RealEntity.class);

    // Step 3: Re-serialize on "JVM B"
    byte[] bytes2 = fory2.serialize(intermediate);

    // Step 4: Deserialize back on "JVM A"
    Object result = fory1.deserialize(bytes2);
    assertEquals(result.getClass(), RealEntity.class);
    RealEntity entity = (RealEntity) result;
    assertEquals(entity.name, "round-trip");
    assertEquals(entity.value, 100);
  }

  /**
   * Tests that a proxy object nested inside another object works correctly across JVMs. This
   * simulates having a Hibernate proxy entity as a field in a DTO.
   */
  public static class DtoWithProxy implements Serializable {
    private static final long serialVersionUID = 1L;
    public String label;
    public RealEntity entity;

    public DtoWithProxy() {}

    public DtoWithProxy(String label, RealEntity entity) {
      this.label = label;
      this.entity = entity;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      DtoWithProxy that = (DtoWithProxy) o;
      return Objects.equals(label, that.label) && Objects.equals(entity, that.entity);
    }

    @Override
    public int hashCode() {
      return Objects.hash(label, entity);
    }
  }

  @Test(dataProvider = "referenceTrackingAndCodegen")
  public void testWriteReplaceNestedProxyCrossJvm(boolean refTracking, boolean codegen) {
    Fory fory1 =
        builder()
            .withRefTracking(refTracking)
            .withCodegen(codegen)
            .requireClassRegistration(false)
            .build();

    ClassLoader restrictedLoader =
        new ClassLoader(getClass().getClassLoader()) {
          @Override
          public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(ProxyEntity.class.getName())) {
              throw new ClassNotFoundException("Simulated: " + name + " not on this JVM");
            }
            return super.loadClass(name, resolve);
          }
        };

    Fory fory2 =
        builder()
            .withRefTracking(refTracking)
            .withCodegen(codegen)
            .requireClassRegistration(false)
            .withClassLoader(restrictedLoader)
            .build();

    // Nest a ProxyEntity inside a DTO
    ProxyEntity proxy = new ProxyEntity("nested", 55);
    DtoWithProxy dto = new DtoWithProxy("my-dto", proxy);
    byte[] bytes = fory1.serialize(dto);

    // Deserialize on "JVM B" — DTO should deserialize, inner proxy should unwrap to RealEntity
    Object result = fory2.deserialize(bytes);
    assertNotNull(result);
    assertEquals(result.getClass(), DtoWithProxy.class);
    DtoWithProxy resultDto = (DtoWithProxy) result;
    assertEquals(resultDto.label, "my-dto");
    assertNotNull(resultDto.entity);
    assertEquals(resultDto.entity.getClass(), RealEntity.class);
    assertEquals(resultDto.entity.name, "nested");
    assertEquals(resultDto.entity.value, 55);
  }
}

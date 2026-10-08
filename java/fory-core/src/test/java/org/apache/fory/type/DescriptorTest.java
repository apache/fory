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

package org.apache.fory.type;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;
import java.util.SortedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.fory.TestUtils;
import org.apache.fory.platform.internal._JDKAccess;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.test.bean.BeanA;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

public class DescriptorTest {

  static class A {
    int f1;
  }

  static class B extends A {
    long f2;
  }

  @Test
  public void testBuildBeanedDescriptorsMap() throws Exception {
    Assert.assertEquals(BeanA.class.getDeclaredField("f1"), BeanA.class.getDeclaredField("f1"));
    Assert.assertNotSame(BeanA.class.getDeclaredField("f1"), BeanA.class.getDeclaredField("f1"));
    SortedMap<Field, Descriptor> map = Descriptor.buildBeanedDescriptorsMap(BeanA.class, true);
    Assert.assertTrue(map.containsKey(BeanA.class.getDeclaredField("f1")));
    Assert.assertEquals(
        map.get(BeanA.class.getDeclaredField("doubleList")).getTypeRef(),
        new TypeRef<List<Double>>() {});
    Assert.assertNotNull(map.get(BeanA.class.getDeclaredField("longStringField")).getReadMethod());
    Assert.assertEquals(
        map.get(BeanA.class.getDeclaredField("longStringField")).getWriteMethod(),
        BeanA.class.getDeclaredMethod("setLongStringField", String.class));

    SortedMap<Field, Descriptor> map2 = Descriptor.buildBeanedDescriptorsMap(B.class, false);
    Assert.assertEquals(map2.size(), 1);
  }

  @Test
  public void getDescriptorsTest() throws IntrospectionException {
    Class<?> clz = BeanA.class;
    TypeRef<?> typeRef = TypeRef.of(clz);
    // sort to fix field order
    List<?> descriptors =
        Arrays.stream(Introspector.getBeanInfo(clz).getPropertyDescriptors())
            .filter(d -> !d.getName().equals("class"))
            .filter(d -> !d.getName().equals("declaringClass"))
            .filter(d -> d.getReadMethod() != null && d.getWriteMethod() != null)
            .map(
                p -> {
                  TypeRef<?> returnType = TypeRef.of(p.getReadMethod().getReturnType());
                  return Arrays.asList(
                      p.getName(),
                      returnType,
                      p.getReadMethod().getName(),
                      p.getWriteMethod().getName());
                })
            .collect(Collectors.toList());

    Descriptor.getDescriptors(clz);
  }

  @Test
  public void testWarmField() throws Exception {
    Assert.assertEquals(int.class.getName(), "int");
    Assert.assertEquals(Integer.class.getName(), "java.lang.Integer");
    Descriptor.warmField(BeanA.class, BeanA.class.getDeclaredField("beanB"));
    Descriptor.getAllDescriptorsMap(BeanA.class);
    Descriptor.clearDescriptorCache();
    Descriptor.getAllDescriptorsMap(BeanA.class);
  }

  public static class WarmOuter {
    WarmInner inner;
  }

  public static class WarmInner {
    WarmLeaf leaf;
  }

  public static class WarmLeaf {}

  public static class WarmProbe implements Runnable {
    @Override
    public void run() {
      Descriptor.getAllDescriptorsMap(WarmOuter.class);
    }
  }

  /**
   * Builds descriptors with Fory loaded in its own classloader. Only the background warm-up of
   * {@code WarmInner} loads {@code WarmLeaf}, so the recorded loader is the TCCL of a warm-up
   * thread.
   */
  @Test
  public void testWarmContextClassLoader() throws Exception {
    warmInIsolatedLoader();
  }

  /** Descriptor warm-up must not keep an isolated Fory classloader reachable after close. */
  @Test
  public void testWarmReleasesLoader() throws Exception {
    if (_JDKAccess.IS_OPEN_J9) {
      throw new SkipException("OpenJ9 unsupported");
    }
    WeakReference<ClassLoader> isolate = warmInIsolatedLoader();
    // Idle compiler pool threads keep the loader reachable until their 5 second keep-alive ends.
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (isolate.get() != null && System.nanoTime() < deadline) {
      System.gc();
      Thread.sleep(100);
    }
    Assert.assertNull(isolate.get(), "isolated Fory classloader is still reachable");
  }

  private static WeakReference<ClassLoader> warmInIsolatedLoader() throws Exception {
    try (WarmLeafRecorder isolate = new WarmLeafRecorder()) {
      Assert.assertNotSame(Thread.currentThread().getContextClassLoader(), isolate);
      Class<?> probe = isolate.loadClass(WarmProbe.class.getName());
      ((Runnable) probe.getDeclaredConstructor().newInstance()).run();
      Assert.assertSame(isolate.leafContextClassLoader.get(30, TimeUnit.SECONDS), isolate);
      return new WeakReference<>(isolate);
    }
  }

  private static final class WarmLeafRecorder extends URLClassLoader {
    private final CompletableFuture<ClassLoader> leafContextClassLoader = new CompletableFuture<>();

    private WarmLeafRecorder() {
      super(TestUtils.forkClassPathUrls(), ClassLoader.getSystemClassLoader().getParent());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      Class<?> cls = super.loadClass(name, resolve);
      if (name.equals(WarmLeaf.class.getName())) {
        leafContextClassLoader.complete(Thread.currentThread().getContextClassLoader());
      }
      return cls;
    }
  }

  @Test
  public void testDescriptorBuilder() {
    Descriptor descriptor =
        new Descriptor(TypeRef.of(A.class), A.class.getName(), "c", -1, "TestClass", true, true);
    // test copyBuilder
    Descriptor descriptor1 = descriptor.copyBuilder().build();
    Assert.assertEquals(descriptor.getTypeRef(), descriptor1.getTypeRef());
    Assert.assertEquals(descriptor.getName(), descriptor1.getName());
    Assert.assertEquals(descriptor.getDeclaringClass(), descriptor1.getDeclaringClass());
    Assert.assertEquals(descriptor.getModifier(), descriptor1.getModifier());
    // test copyWithTypeName
    Descriptor descriptor2 = descriptor.copyWithTypeName("test");
    Assert.assertEquals(descriptor2.getTypeName(), "test");
    // test builder
    final Descriptor descriptor3 =
        new DescriptorBuilder(descriptor)
            .nullable(true)
            .trackingRef(false)
            .declaringClass("test1")
            .build();
    Assert.assertEquals(descriptor3.getTypeRef(), descriptor1.getTypeRef());
    Assert.assertEquals(descriptor3.getName(), descriptor1.getName());
    Assert.assertEquals(descriptor3.getDeclaringClass(), "test1");
    Assert.assertEquals(descriptor3.getModifier(), descriptor1.getModifier());
    Assert.assertTrue(descriptor3.isNullable());
    Assert.assertFalse(descriptor3.isTrackingRef());
  }
}

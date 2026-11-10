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

package org.apache.fory.builder;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.ThreadSafeFory;
import org.apache.fory.codegen.CompileUnit;
import org.apache.fory.codegen.JaninoUtils;
import org.apache.fory.context.MetaReadContext;
import org.apache.fory.context.MetaWriteContext;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.test.bean.BeanA;
import org.apache.fory.test.bean.BeanB;
import org.codehaus.commons.compiler.util.reflect.ByteArrayClassLoader;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class JITContextTest extends ForyTestBase {
  private static final Logger LOG = LoggerFactory.getLogger(JITContextTest.class);

  @DataProvider
  public static Object[][] config1() {
    return Sets.cartesianProduct(
            ImmutableSet.of(true, false), // referenceTracking
            ImmutableSet.of(true, false)) // compatible
        .stream()
        .map(List::toArray)
        .toArray(Object[][]::new);
  }

  @DataProvider
  public static Object[][] config2() {
    return Sets.cartesianProduct(
            ImmutableSet.of(true, false), // referenceTracking
            ImmutableSet.of(true, false), // compatible
            ImmutableSet.of(true, false)) // scopedMetaShare
        .stream()
        .map(List::toArray)
        .toArray(Object[][]::new);
  }

  @Test(dataProvider = "config1", timeOut = 60_000)
  public void testAsyncCompilation(boolean referenceTracking, boolean compatible)
      throws InterruptedException {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(referenceTracking)
            .withCompatible(compatible)
            .requireClassRegistration(false)
            .withAsyncCompilation(true)
            .build();
    BeanB beanB = BeanB.createBeanB(2);
    BeanA beanA = BeanA.createBeanA(2);
    byte[] bytes1 = fory.serialize(beanB);
    byte[] bytes2 = fory.serialize(beanA);

    while (!(getSerializer(fory, BeanB.class) instanceof Generated)) {
      LOG.info("Waiting {} serializer to be jit.", BeanB.class);
      Thread.sleep(100);
    }
    while (!(getSerializer(fory, BeanA.class) instanceof Generated)) {
      LOG.info("Waiting {} serializer to be jit.", BeanA.class);
      Thread.sleep(100);
    }
    Assert.assertTrue(getSerializer(fory, BeanB.class) instanceof Generated);
    Assert.assertTrue(getSerializer(fory, BeanA.class) instanceof Generated);
    assertEquals(fory.deserialize(bytes1), beanB);
    assertEquals(fory.deserialize(bytes2), beanA);
  }

  @Test(dataProvider = "config1", timeOut = 60_000)
  public void testAsyncTypeNames(boolean referenceTracking, boolean compatible) throws Exception {
    String pkg = "org.apache.fory.codegen.loaderconflict";
    ClassLoader parent = Fory.class.getClassLoader();
    ByteArrayClassLoader loader =
        JaninoUtils.compile(
            parent,
            new CompileUnit(pkg, "Object", "package " + pkg + "; public class Object {}"),
            new CompileUnit(pkg, "String", "package " + pkg + "; public class String {}"),
            new CompileUnit(
                pkg,
                "Bean",
                "package "
                    + pkg
                    + "; public class Bean {"
                    + " public java.lang.Object value;"
                    + " public java.lang.String text;"
                    + "}"));
    Assert.assertSame(loader.loadClass(Fory.class.getName()), Fory.class);
    Assert.expectThrows(ClassNotFoundException.class, () -> parent.loadClass(pkg + ".Object"));
    Assert.expectThrows(ClassNotFoundException.class, () -> parent.loadClass(pkg + ".String"));
    Class<?> beanClass = loader.loadClass(pkg + ".Bean");
    Object bean = beanClass.getDeclaredConstructor().newInstance();
    beanClass.getField("value").set(bean, 42);
    beanClass.getField("text").set(bean, "child loader");

    Thread current = Thread.currentThread();
    ClassLoader previous = current.getContextClassLoader();
    try {
      current.setContextClassLoader(loader);
      Fory fory =
          Fory.builder()
              .withXlang(false)
              .withRefTracking(referenceTracking)
              .withCompatible(compatible)
              .withClassLoader(loader)
              .withAsyncCompilation(true)
              .build();
      fory.register(beanClass);
      byte[] beforeJit = fory.serialize(bean);
      while (fory.getJITContext().hasJITResult(beanClass)) {
        Thread.sleep(10);
      }
      // A round trip alone can pass with the interpreter after an async compilation failure.
      Assert.assertTrue(getSerializer(fory, beanClass) instanceof Generated);
      for (byte[] bytes : new byte[][] {beforeJit, fory.serialize(bean)}) {
        Object copy = fory.deserialize(bytes);
        Assert.assertSame(copy.getClass(), beanClass);
        Assert.assertEquals(beanClass.getField("value").get(copy), 42);
        Assert.assertEquals(beanClass.getField("text").get(copy), "child loader");
      }
    } finally {
      current.setContextClassLoader(previous);
    }
  }

  private Serializer getSerializer(Fory fory, Class<?> cls) {
    try {
      fory.getJITContext().lock();
      Serializer<?> serializer = fory.getTypeResolver().getSerializer(cls);
      return serializer;
    } finally {
      fory.getJITContext().unlock();
    }
  }

  @Test(dataProvider = "config2", timeOut = 60_000)
  public void testAsyncCompilationMetaShare(
      boolean referenceTracking, boolean compatible, boolean scopedMetaShare)
      throws InterruptedException {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(referenceTracking)
            .withCompatible(compatible)
            .withScopedMetaShare(scopedMetaShare)
            .requireClassRegistration(false)
            .withAsyncCompilation(true)
            .build();
    BeanB beanB = BeanB.createBeanB(2);
    BeanA beanA = BeanA.createBeanA(2);
    MetaWriteContext metaWriteContext = new MetaWriteContext();
    MetaReadContext metaReadContext = new MetaReadContext();
    if (!scopedMetaShare) {
      setMetaContexts(fory, metaWriteContext, metaReadContext);
    }
    byte[] bytes1 = fory.serialize(beanB);
    if (!scopedMetaShare) setMetaContexts(fory, metaWriteContext, metaReadContext);
    byte[] bytes2 = fory.serialize(beanA);
    while (!(getSerializer(fory, BeanB.class) instanceof Generated)) {
      LOG.info("Waiting {} serializer to be jit.", BeanB.class);
      Thread.sleep(100);
    }
    while (!(getSerializer(fory, BeanA.class) instanceof Generated)) {
      LOG.info("Waiting {} serializer to be jit.", BeanA.class);
      Thread.sleep(100);
    }
    Assert.assertTrue(getSerializer(fory, BeanB.class) instanceof Generated);
    Assert.assertTrue(getSerializer(fory, BeanA.class) instanceof Generated);
    if (!scopedMetaShare) setMetaContexts(fory, metaWriteContext, metaReadContext);
    assertEquals(fory.deserialize(bytes1), beanB);
    if (!scopedMetaShare) setMetaContexts(fory, metaWriteContext, metaReadContext);
    assertEquals(fory.deserialize(bytes2), beanA);
  }

  @Test(timeOut = 60000)
  public void testAsyncCompilationSwitch() throws InterruptedException {
    final Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .withAsyncCompilation(true)
            .withCompatible(false)
            .build();

    TestAccessLevel o = new TestAccessLevel(new PkgAccessLevel(1), new PrivateAccessLevel(2));
    serDeCheck(fory, o);
    Class<?>[] classes = {PkgAccessLevel.class, PrivateAccessLevel.class};
    for (Class<?> cls : classes) {
      while (!(fory.getTypeResolver().getSerializer(cls) instanceof Generated)) {
        Thread.sleep(1000);
        LOG.warn("Wait async compilation finish for {}", cls);
      }
    }
    while (fory.getJITContext().hasJITResult(PkgAccessLevel.class)) {
      Thread.sleep(10); // allow serializer be switched to generated version
    }
    while (fory.getJITContext().hasJITResult(PrivateAccessLevel.class)) {
      Thread.sleep(10); // allow serializer be switched to generated version
    }
    Serializer<TestAccessLevel> serializer =
        fory.getTypeResolver().getSerializer(TestAccessLevel.class);
    assertTrue(ReflectionUtils.getObjectFieldValue(serializer, "serializer") instanceof Generated);
    assertTrue(ReflectionUtils.getObjectFieldValue(serializer, "serializer1") instanceof Generated);
    serDeCheck(fory, o);
  }

  @Test(timeOut = 60000)
  public void testThreadSafetyWithManyForyInstances() throws Exception {
    final int threadCount = 1000;
    final CountDownLatch latch = new CountDownLatch(threadCount);
    final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

    for (int i = 0; i < threadCount; i++) {
      new Thread(
              () -> {
                try {
                  ThreadSafeFory fory =
                      Fory.builder()
                          .withXlang(false)
                          .requireClassRegistration(true)
                          .withAsyncCompilation(true)
                          .withCompatible(true)
                          .buildThreadSafeForyPool(4);
                  fory.register(BeanB.class);
                  // fory.register(BeanA.class);
                } catch (Throwable t) {
                  errors.add(t);
                } finally {
                  latch.countDown();
                }
              })
          .start();
    }
    latch.await();
    // print stack trace in errors
    for (Throwable error : errors) {
      error.printStackTrace();
    }
    assertTrue(errors.isEmpty(), "No exceptions should be thrown: " + errors);
  }

  @Data
  public static final class TestAccessLevel {
    PkgAccessLevel f1;
    PrivateAccessLevel f2;

    public TestAccessLevel(PkgAccessLevel f1, PrivateAccessLevel f2) {
      this.f1 = f1;
      this.f2 = f2;
    }
  }

  // test pkg level class
  @Data
  private static final class PkgAccessLevel {
    private final int f1;

    public PkgAccessLevel(int f1) {
      this.f1 = f1;
    }
  }

  // test private class, class should be final for switch
  @Data
  private static final class PrivateAccessLevel {
    private final int f1;

    public PrivateAccessLevel(int f1) {
      this.f1 = f1;
    }
  }
}

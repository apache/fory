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
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Language;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.MetaContext;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.test.bean.BeanA;
import org.apache.fory.test.bean.BeanB;
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
  public void testAsyncCompilation(
      boolean referenceTracking, boolean compatible)
      throws InterruptedException {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
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

  private Serializer getSerializer(Fory fory, Class<?> cls) {
    try {
      fory.getJITContext().lock();
      Serializer<?> serializer = fory.getClassResolver().getSerializer(cls);
      return serializer;
    } finally {
      fory.getJITContext().unlock();
    }
  }

  @Test(dataProvider = "config2", timeOut = 60_000)
  public void testAsyncCompilationMetaShared(
      boolean referenceTracking, boolean compatible, boolean scopedMetaShare)
      throws InterruptedException {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(referenceTracking)
            .withCompatible(compatible)
            .withScopedMetaShare(scopedMetaShare)
            .requireClassRegistration(false)
            .withAsyncCompilation(true)
            .build();
    BeanB beanB = BeanB.createBeanB(2);
    BeanA beanA = BeanA.createBeanA(2);
    MetaContext context = new MetaContext();
    if (!scopedMetaShare) {
      fory.getSerializationContext().setMetaContext(context);
    }
    byte[] bytes1 = fory.serialize(beanB);
    if (!scopedMetaShare) fory.getSerializationContext().setMetaContext(context);
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
    if (!scopedMetaShare) fory.getSerializationContext().setMetaContext(context);
    assertEquals(fory.deserialize(bytes1), beanB);
    if (!scopedMetaShare) fory.getSerializationContext().setMetaContext(context);
    assertEquals(fory.deserialize(bytes2), beanA);
  }

  @Test(timeOut = 60000)
  public void testAsyncCompilationSwitch() throws InterruptedException {
    final Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .withAsyncCompilation(true)
            .build();

    TestAccessLevel o = new TestAccessLevel(new PkgAccessLevel(1), new PrivateAccessLevel(2));
    serDeCheck(fory, o);
    Class<?>[] classes = {PkgAccessLevel.class, PrivateAccessLevel.class};
    for (Class<?> cls : classes) {
      while (!(fory.getClassResolver().getSerializer(cls) instanceof Generated)) {
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
        fory.getClassResolver().getSerializer(TestAccessLevel.class);
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
                          .withLanguage(Language.JAVA)
                          .requireClassRegistration(true)
                          .withAsyncCompilation(true)
                          .withCompatibleMode(CompatibleMode.COMPATIBLE)
                          .buildThreadSafeForyPool(2, 4, 1, java.util.concurrent.TimeUnit.MINUTES);
                  fory.register(BeanB.class, true);
                  // fory.register(BeanA.class, true);
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

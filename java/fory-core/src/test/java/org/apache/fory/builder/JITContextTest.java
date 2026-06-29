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
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.ThreadSafeFory;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.CompileUnit;
import org.apache.fory.codegen.CompileUnit.DefinitionMode;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.context.MetaReadContext;
import org.apache.fory.context.MetaWriteContext;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.platform.JdkVersion;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.TypeChecker;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.test.bean.BeanA;
import org.apache.fory.test.bean.BeanB;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class JITContextTest extends ForyTestBase {
  private static final Logger LOG = LoggerFactory.getLogger(JITContextTest.class);
  private static final TypeChecker ALLOW_ALL_TYPES = (resolver, className) -> true;

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
    testAsyncCompilationSwitch(false);
  }

  @Test(timeOut = 60000)
  public void testAsyncCompilationSwitchAllowAllTypes() throws InterruptedException {
    testAsyncCompilationSwitch(true);
  }

  private void testAsyncCompilationSwitch(boolean allowAllTypes) throws InterruptedException {
    ForyBuilder builder =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .withAsyncCompilation(true)
            .withCompatible(false);
    if (allowAllTypes) {
      builder.withTypeChecker(ALLOW_ALL_TYPES);
    }
    final Fory fory = builder.build();

    ContainerPayload o =
        new ContainerPayload(new NestedPayload(1, "name"), new PayloadDetails("category", true));
    assertContainerPayloadRoundTrip(fory, o);
    Class<?>[] classes = {NestedPayload.class, PayloadDetails.class};
    for (Class<?> cls : classes) {
      while (!(fory.getTypeResolver().getSerializer(cls) instanceof Generated)) {
        Thread.sleep(1000);
        LOG.warn("Wait async compilation finish for {}", cls);
      }
    }
    while (fory.getJITContext().hasJITResult(NestedPayload.class)) {
      Thread.sleep(10); // allow serializer be switched to generated version
    }
    while (fory.getJITContext().hasJITResult(PayloadDetails.class)) {
      Thread.sleep(10); // allow serializer be switched to generated version
    }
    Serializer<ContainerPayload> serializer =
        fory.getTypeResolver().getSerializer(ContainerPayload.class);
    assertTrue(ReflectionUtils.getObjectFieldValue(serializer, "serializer") instanceof Generated);
    assertTrue(ReflectionUtils.getObjectFieldValue(serializer, "serializer1") instanceof Generated);
    assertContainerPayloadRoundTrip(fory, o);
  }

  @Test(timeOut = 60000)
  public void testGeneratedSerializerFieldAccessAllowAllTypes() throws Exception {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .withAsyncCompilation(true)
            .withCompatible(false)
            .withTypeChecker(ALLOW_ALL_TYPES)
            .build();
    ContainerPayload value =
        new ContainerPayload(new NestedPayload(1, "name"), new PayloadDetails("category", true));
    assertContainerPayloadRoundTrip(fory, value);

    Class<?> serializerClass = compileGeneratedSerializerClass();
    assertGeneratedSerializerClassShape(serializerClass);
    Object generatedSerializer = serializerClass.getConstructor().newInstance();
    Field field = serializerClass.getDeclaredField("serializer1");
    Serializer<?> nestedSerializer = fory.getTypeResolver().getSerializer(NestedPayload.class);

    ReflectionUtils.setObjectFieldValue(generatedSerializer, field, nestedSerializer);

    assertSame(ReflectionUtils.getObjectFieldValue(generatedSerializer, field), nestedSerializer);
  }

  private static Class<?> compileGeneratedSerializerClass() {
    String pkg = JITContextTest.class.getPackage().getName();
    CompileUnit unit =
        new CompileUnit(
            pkg,
            "ContainerPayloadForyCodec_0",
            ("package "
                + pkg
                + ";\n"
                + "import org.apache.fory.serializer.Serializer;\n"
                + "public class ContainerPayloadForyCodec_0 {\n"
                + "  public Serializer serializer1;\n"
                + "}"),
            JITContextTest.class,
            DefinitionMode.NORMAL);
    return new CodeGenerator(JITContextTest.class.getClassLoader())
        .compileAndLoad(unit, compileState -> compileState.lock.lock());
  }

  private static void assertGeneratedSerializerClassShape(Class<?> serializerClass)
      throws ReflectiveOperationException {
    assertSame(serializerClass.getClassLoader(), JITContextTest.class.getClassLoader());
    if (JdkVersion.MAJOR_VERSION >= 15) {
      assertFalse((Boolean) Class.class.getMethod("isHidden").invoke(serializerClass));
    }
  }

  private static void assertContainerPayloadRoundTrip(Fory fory, ContainerPayload value) {
    ContainerPayload roundTrip = (ContainerPayload) fory.deserialize(fory.serialize(value));
    assertEquals(roundTrip.nestedPayload.id, value.nestedPayload.id);
    assertEquals(roundTrip.nestedPayload.name, value.nestedPayload.name);
    assertEquals(roundTrip.details.category, value.details.category);
    assertEquals(roundTrip.details.enabled, value.details.enabled);
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

  public static final class ContainerPayload {
    public NestedPayload nestedPayload;
    public PayloadDetails details;

    public ContainerPayload() {}

    public ContainerPayload(NestedPayload nestedPayload, PayloadDetails details) {
      this.nestedPayload = nestedPayload;
      this.details = details;
    }
  }

  public static final class NestedPayload {
    public int id;
    public String name;

    public NestedPayload() {}

    public NestedPayload(int id, String name) {
      this.id = id;
      this.name = name;
    }
  }

  public static final class PayloadDetails {
    public String category;
    public boolean enabled;

    public PayloadDetails() {}

    public PayloadDetails(String category, boolean enabled) {
      this.category = category;
      this.enabled = enabled;
    }
  }
}

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

package org.apache.fory;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Data;
import org.apache.fory.config.Language;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.MetaContext;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.test.bean.BeanA;
import org.apache.fory.test.bean.BeanB;
import org.apache.fory.test.bean.Struct;
import org.apache.fory.util.LoaderBinding.StagingType;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class ThreadSafeForyTest extends ForyTestBase {
  private volatile boolean hasException;

  @Test
  public void testPoolSerialize() {
    BeanA beanA = BeanA.createBeanA(2);
    ThreadSafeFory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .withAsyncCompilation(true)
            .buildThreadSafeForyPool(5, 10);
    for (int i = 0; i < 2000; i++) {
      new Thread(
              () -> {
                for (int j = 0; j < 10; j++) {
                  try {
                    fory.setClassLoader(beanA.getClass().getClassLoader());
                    assertEquals(fory.deserialize(fory.serialize(beanA)), beanA);
                  } catch (Exception e) {
                    hasException = true;
                    e.printStackTrace();
                  }
                }
              })
          .start();
    }
    assertFalse(hasException);
  }

  @Test
  public void testRegistration() throws Exception {
    BeanB bean = BeanB.createBeanB(2);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    AtomicReference<Throwable> ex = new AtomicReference<>();
    {
      ThreadSafeFory fory =
          Fory.builder().requireClassRegistration(true).buildThreadSafeForyPool(2, 4);
      fory.register(BeanB.class);
      Assert.assertEquals(fory.deserialize(fory.serialize(bean)), bean);
      executor.execute(
          () -> {
            try {
              Assert.assertEquals(fory.deserialize(fory.serialize(bean)), bean);
            } catch (Throwable t) {
              ex.set(t);
            }
          });
      Assert.assertNull(ex.get());
    }
    {
      ThreadSafeFory fory = Fory.builder().requireClassRegistration(true).buildThreadLocalFory();
      fory.register(BeanB.class);
      Assert.assertEquals(fory.deserialize(fory.serialize(bean)), bean);
      executor.execute(
          () -> {
            try {
              Assert.assertEquals(fory.deserialize(fory.serialize(bean)), bean);
            } catch (Throwable t) {
              ex.set(t);
            }
          });
      Assert.assertNull(ex.get());
    }
  }

  @Test
  public void testSerialize() throws Exception {
    BeanA beanA = BeanA.createBeanA(2);
    ThreadSafeFory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .withAsyncCompilation(true)
            .buildThreadSafeFory();
    ExecutorService executorService = Executors.newFixedThreadPool(12);
    for (int i = 0; i < 2000; i++) {
      executorService.execute(
          () -> {
            for (int j = 0; j < 10; j++) {
              try {
                fory.setClassLoader(beanA.getClass().getClassLoader());
                assertEquals(fory.deserialize(fory.serialize(beanA)), beanA);
              } catch (Exception e) {
                hasException = true;
                e.printStackTrace();
              }
            }
          });
    }
    executorService.shutdown();
    assertTrue(executorService.awaitTermination(30, TimeUnit.SECONDS));
    assertFalse(hasException);
  }

  @Test
  public void testSerializeWithMetaShare() throws InterruptedException {
    ThreadSafeFory fory1 =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .buildThreadSafeFory();
    ThreadSafeFory fory2 =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withMetaShare(true)
            .requireClassRegistration(false)
            .buildThreadSafeFory();
    BeanA beanA = BeanA.createBeanA(2);
    ExecutorService executorService = Executors.newFixedThreadPool(12);
    ConcurrentHashMap<Thread, MetaContext> metaMap = new ConcurrentHashMap<>();
    for (int i = 0; i < 2000; i++) {
      executorService.execute(
          () -> {
            for (int j = 0; j < 10; j++) {
              try {
                {
                  fory1.setClassLoader(beanA.getClass().getClassLoader());
                  byte[] serialized = fory1.execute(f -> f.serialize(beanA));
                  Object newObj = fory1.execute(f -> f.deserialize(serialized));
                  assertEquals(newObj, beanA);
                }
                {
                  fory2.setClassLoader(beanA.getClass().getClassLoader());
                  byte[] serialized =
                      fory2.execute(
                          f -> {
                            f.getSerializationContext().setMetaContext(new MetaContext());
                            return f.serialize(beanA);
                          });
                  Object newObj =
                      fory2.execute(
                          f -> {
                            f.getSerializationContext().setMetaContext(new MetaContext());
                            return f.deserialize(serialized);
                          });
                  assertEquals(newObj, beanA);
                }
                {
                  MetaContext metaContext =
                      metaMap.computeIfAbsent(Thread.currentThread(), k -> new MetaContext());
                  fory2.setClassLoader(beanA.getClass().getClassLoader());
                  byte[] serialized =
                      fory2.execute(
                          f -> {
                            f.getSerializationContext().setMetaContext(metaContext);
                            return f.serialize(beanA);
                          });
                  Object newObj =
                      fory2.execute(
                          f -> {
                            f.getSerializationContext().setMetaContext(metaContext);
                            return f.deserialize(serialized);
                          });
                  assertEquals(newObj, beanA);
                }
              } catch (Exception e) {
                hasException = true;
                e.printStackTrace();
                throw e;
              }
            }
          });
    }
    executorService.shutdown();
    assertTrue(executorService.awaitTermination(30, TimeUnit.SECONDS));
    assertFalse(hasException);
  }

  @DataProvider(name = "stagingConfig")
  public static Object[][] stagingConfig() {
    return new Object[][] {{StagingType.NO_STAGING}, {StagingType.STRONG_STAGING}};
  }

  @Test(dataProvider = "stagingConfig")
  public void testClassDuplicateName(StagingType staging) {
    ThreadSafeFory fory = Fory.builder().requireClassRegistration(false).buildThreadSafeFory();
    String className = "DuplicateStruct";
    Class<?> structClass1 = Struct.createStructClass(className, 1);
    Object struct1 = Struct.createPOJO(structClass1);
    byte[] bytes1 = fory.serialize(struct1);
    Assert.assertEquals(fory.deserialize(bytes1), struct1);
    Class<? extends Serializer> serializerClass1 =
        fory.execute(f -> f.getClassResolver().getSerializerClass(structClass1));
    Assert.assertTrue(serializerClass1.getName().contains("Codec"));

    Class<?> structClass2 = Struct.createStructClass(className, 2);
    Object struct2 = Struct.createPOJO(structClass2);
    assertEquals(
        struct2.getClass().getDeclaredFields().length,
        struct1.getClass().getDeclaredFields().length * 2);
    AtomicReference<byte[]> bytesReference = new AtomicReference<>();
    CompletableFuture.runAsync(
            () -> {
              fory.setClassLoader(structClass2.getClassLoader(), staging);
              byte[] bytes2 = fory.serialize(struct2);
              bytesReference.set(bytes2);
            })
        .join();
    byte[] bytes2 = bytesReference.get();
    fory.setClassLoader(structClass2.getClassLoader());
    Assert.assertEquals(fory.deserialize(bytes2), struct2);
    Class<? extends Serializer> serializerClass2 =
        fory.execute(f -> f.getClassResolver().getSerializerClass(structClass2));
    Assert.assertTrue(serializerClass2.getName().contains("Codec"));
    Assert.assertNotSame(serializerClass2, serializerClass1);

    byte[] newBytes1 = fory.serialize(struct1);
    CompletableFuture.runAsync(
            () -> {
              fory.setClassLoader(structClass1.getClassLoader(), staging);
              fory.setTypeChecker((classResolver, className1) -> true);
              fory.setSerializerFactory((fory1, cls) -> null);
              Assert.assertEquals(fory.deserialize(newBytes1), struct1);
            })
        .join();
  }

  @Test(timeOut = 60_000)
  public void testClassGC() throws Exception {
    // Can't inline `generateClassForGC` in current method, generated classes won't be gc.
    WeakHashMap<Class<?>, Boolean> map = generateClassForGC();
    TestUtils.triggerOOMForSoftGC(
        () -> {
          if (!map.isEmpty()) {
            System.out.printf("Wait classes %s gc.\n", map.keySet());
            return true;
          } else {
            return false;
          }
        });
  }

  private WeakHashMap<Class<?>, Boolean> generateClassForGC() {
    ThreadSafeFory fory1 = Fory.builder().requireClassRegistration(false).buildThreadSafeFory();
    ThreadSafeFory fory2 =
        Fory.builder().requireClassRegistration(false).buildThreadSafeForyPool(1, 2);
    String className = "DuplicateStruct";
    WeakHashMap<Class<?>, Boolean> map = new WeakHashMap<>();
    {
      Class<?> structClass1 = Struct.createStructClass(className, 1, false);
      Object struct1 = Struct.createPOJO(structClass1);
      for (ThreadSafeFory fory : new ThreadSafeFory[] {fory1, fory2}) {
        fory.setClassLoader(structClass1.getClassLoader());
        byte[] bytes = fory.serialize(struct1);
        Assert.assertEquals(fory.deserialize(bytes), struct1);
        map.put(structClass1, true);
        System.out.printf(
            "structClass1 %s %s\n",
            structClass1.hashCode(), structClass1.getClassLoader().hashCode());
        fory.clearClassLoader(structClass1.getClassLoader());
      }
    }
    {
      Class<?> structClass2 = Struct.createStructClass(className, 2, false);
      map.put(structClass2, true);
      System.out.printf(
          "structClass2 %s %s\n ",
          structClass2.hashCode(), structClass2.getClassLoader().hashCode());
      for (ThreadSafeFory fory : new ThreadSafeFory[] {fory1, fory2}) {
        fory.setClassLoader(structClass2.getClassLoader());
        Object struct2 = Struct.createPOJO(structClass2);
        byte[] bytes2 = fory.serialize(struct2);
        Assert.assertEquals(fory.deserialize(bytes2), struct2);
        fory.clearClassLoader(structClass2.getClassLoader());
      }
    }
    return map;
  }

  @Test
  public void testSerializeJavaObject() {
    for (ThreadSafeFory fory :
        new ThreadSafeFory[] {
          Fory.builder().requireClassRegistration(false).buildThreadSafeFory(),
          Fory.builder().requireClassRegistration(false).buildThreadSafeForyPool(2, 2)
        }) {
      byte[] bytes = fory.serializeJavaObject("abc");
      Assert.assertEquals(fory.deserializeJavaObject(bytes, String.class), "abc");
      MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(8);
      fory.serializeJavaObject(buffer, "abc");
      Assert.assertEquals(fory.deserializeJavaObject(buffer, String.class), "abc");
    }
  }

  @Data
  static class Foo {
    int f1;
  }

  public static class FooSerializer extends Serializer<Foo> {
    public FooSerializer(Fory fory, Class<Foo> type) {
      super(fory, type);
    }

    @Override
    public void write(MemoryBuffer buffer, Foo value) {
      buffer.writeInt32(value.f1);
    }

    @Override
    public Foo read(MemoryBuffer buffer) {
      final Foo foo = new Foo();
      foo.f1 = buffer.readInt32();
      return foo;
    }
  }

  public static class CustomClassLoader extends ClassLoader {
    public CustomClassLoader(ClassLoader parent) {
      super(parent);
    }
  }

  @Test
  public void testSerializerRegister() {
    final ThreadSafeFory threadSafeFory =
        Fory.builder().requireClassRegistration(false).buildThreadSafeForyPool(0, 2);
    threadSafeFory.registerSerializer(Foo.class, FooSerializer.class);
    // create a new classLoader
    threadSafeFory.setClassLoader(new CustomClassLoader(ClassLoader.getSystemClassLoader()));
    threadSafeFory.execute(
        fory -> {
          Assert.assertEquals(
              fory.getClassResolver().getSerializer(Foo.class).getClass(), FooSerializer.class);
          return null;
        });
  }

  @Test
  public void testRegisterClassAfterInitialization() throws InterruptedException {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    AtomicReference<Throwable> ex = new AtomicReference<>();

    try {
      ThreadSafeFory fory = Fory.builder().requireClassRegistration(true).buildThreadLocalFory();

      fory.register(BeanB.class);

      BeanB bean = BeanB.createBeanB(2);
      byte[] bytes = fory.serialize(bean);

      executor.execute(
          () -> {
            try {
              Object deserialized = fory.deserialize(bytes);
              Assert.assertEquals(deserialized, bean);
            } catch (Throwable t) {
              ex.set(t);
            }
          });

      executor.shutdown();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

      if (ex.get() != null) {
        throw new AssertionError("error", ex.get());
      }
    } finally {
      if (!executor.isTerminated()) {
        executor.shutdownNow();
      }
    }
  }

  @Test
  public void testConcurrentRegisterAndDeserialize() throws InterruptedException {
    ExecutorService executor = Executors.newFixedThreadPool(4);
    ConcurrentHashMap<String, Throwable> errors = new ConcurrentHashMap<>();

    try {
      ThreadSafeFory fory = Fory.builder().requireClassRegistration(true).buildThreadLocalFory();

      fory.register(BeanA.class);
      fory.register(BeanB.class);
      BeanA beanA = BeanA.createBeanA(2);
      byte[] bytesA = fory.serialize(beanA);
      BeanB beanB = BeanB.createBeanB(2);
      byte[] bytesB = fory.serialize(beanB);

      for (int i = 0; i < 4; i++) {
        final int threadId = i;
        executor.execute(
            () -> {
              try {
                if (threadId % 2 == 0) {
                  Object deserialized = fory.deserialize(bytesA);
                  Assert.assertEquals(deserialized, beanA);
                } else {
                  Object deserialized = fory.deserialize(bytesB);
                  Assert.assertEquals(deserialized, beanB);
                }
              } catch (Exception e) {
                errors.put("thread-" + threadId, e);
              }
            });
      }

      executor.shutdown();
      assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));

      if (!errors.isEmpty()) {
        throw new AssertionError("error：" + errors);
      }
    } finally {
      if (!executor.isTerminated()) {
        executor.shutdownNow();
      }
    }
  }

  @Test
  public void testConcurrentRegisterInThreadPoolFory() throws InterruptedException {
    ExecutorService executor = Executors.newFixedThreadPool(8);
    ConcurrentHashMap<String, Throwable> errors = new ConcurrentHashMap<>();

    try {
      ThreadSafeFory fory =
          Fory.builder().requireClassRegistration(true).buildThreadSafeForyPool(2, 4);

      fory.register(BeanA.class);
      fory.register(BeanB.class);
      BeanA beanA = BeanA.createBeanA(2);
      BeanB beanB = BeanB.createBeanB(2);
      byte[] bytesA = fory.serialize(beanA);
      byte[] bytesB = fory.serialize(beanB);

      for (int i = 0; i < 32; i++) {
        final int threadId = i;
        executor.execute(
            () -> {
              try {
                if (threadId % 4 == 0) {
                  fory.register(BeanA.class);
                } else if (threadId % 4 == 1) {
                  fory.register(BeanB.class);
                } else if (threadId % 4 == 2) {
                  Object deserialized = fory.deserialize(bytesA);
                  Assert.assertEquals(deserialized, beanA);
                } else {
                  Object deserialized = fory.deserialize(bytesB);
                  Assert.assertEquals(deserialized, beanB);
                }
              } catch (Exception e) {
                errors.put("thread-" + threadId, e);
              }
            });
      }

      executor.shutdown();
      assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));

      if (!errors.isEmpty()) {
        throw new AssertionError("error：" + errors);
      }
    } finally {
      if (!executor.isTerminated()) {
        executor.shutdownNow();
      }
    }
  }
}

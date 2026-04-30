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
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Data;
import org.apache.fory.context.MetaReadContext;
import org.apache.fory.context.MetaWriteContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.exception.ForyException;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.pool.ThreadPoolFory;
import org.apache.fory.resolver.SharedRegistry;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.test.bean.BeanA;
import org.apache.fory.test.bean.BeanB;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ThreadSafeForyTest extends ForyTestBase {

  @Test
  public void testBuildThreadSafeForyUsesThreadPoolFory() {
    ThreadSafeFory fory = Fory.builder().requireClassRegistration(false).buildThreadSafeFory();
    assertTrue(fory instanceof ThreadPoolFory);
  }

  @Test
  public void testFunctionFactoryConstructorsUseBuilderProvidedClassLoader() {
    ClassLoader custom = new ClassLoader(ClassLoader.getSystemClassLoader()) {};
    ThreadLocalFory threadLocal =
        new ThreadLocalFory(
            builder -> builder.withClassLoader(custom).requireClassRegistration(false).build());
    ThreadPoolFory threadPool =
        new ThreadPoolFory(
            builder -> builder.withClassLoader(custom).requireClassRegistration(false).build(), 2);
    assertSame(threadLocal.execute(Fory::getClassLoader), custom);
    assertSame(threadPool.execute(Fory::getClassLoader), custom);
  }

  @Test
  public void testThreadSafeRuntimesShareRegistryAcrossRawForyInstances() throws Exception {
    ThreadLocalFory threadLocal =
        Fory.builder().requireClassRegistration(false).buildThreadLocalFory();
    AtomicReference<SharedRegistry> threadLocalRegistry1 = new AtomicReference<>();
    AtomicReference<SharedRegistry> threadLocalRegistry2 = new AtomicReference<>();
    Thread thread1 =
        new Thread(() -> threadLocalRegistry1.set(threadLocal.execute(Fory::getSharedRegistry)));
    Thread thread2 =
        new Thread(() -> threadLocalRegistry2.set(threadLocal.execute(Fory::getSharedRegistry)));
    thread1.start();
    thread1.join();
    thread2.start();
    thread2.join();
    assertSame(threadLocalRegistry1.get(), threadLocalRegistry2.get());

    ThreadPoolFory threadPool =
        (ThreadPoolFory) Fory.builder().requireClassRegistration(false).buildThreadSafeForyPool(2);
    CountDownLatch acquired = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<SharedRegistry> threadPoolRegistry1 = new AtomicReference<>();
    AtomicReference<SharedRegistry> threadPoolRegistry2 = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    Thread poolThread1 =
        new Thread(
            () -> {
              try {
                threadPool.execute(
                    fory -> {
                      threadPoolRegistry1.set(fory.getSharedRegistry());
                      acquired.countDown();
                      awaitUnchecked(release);
                      return null;
                    });
              } catch (Throwable t) {
                error.compareAndSet(null, t);
              }
            });
    Thread poolThread2 =
        new Thread(
            () -> {
              try {
                threadPool.execute(
                    fory -> {
                      threadPoolRegistry2.set(fory.getSharedRegistry());
                      acquired.countDown();
                      awaitUnchecked(release);
                      return null;
                    });
              } catch (Throwable t) {
                error.compareAndSet(null, t);
              }
            });
    poolThread1.start();
    poolThread2.start();
    assertTrue(acquired.await(30, TimeUnit.SECONDS));
    release.countDown();
    poolThread1.join();
    poolThread2.join();
    if (error.get() != null) {
      throw new AssertionError(error.get());
    }
    assertSame(threadPoolRegistry1.get(), threadPoolRegistry2.get());
  }

  private static void awaitUnchecked(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  @Test
  public void testThreadSafeSerialize() throws InterruptedException {
    BeanA beanA = BeanA.createBeanA(2);
    ThreadSafeFory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .withAsyncCompilation(true)
            .buildThreadSafeFory();
    assertConcurrentRoundTrip(fory, beanA);
  }

  @Test
  public void testPoolSerialize() throws InterruptedException {
    BeanA beanA = BeanA.createBeanA(2);
    ThreadSafeFory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .withAsyncCompilation(true)
            .buildThreadSafeForyPool(10);
    assertConcurrentRoundTrip(fory, beanA);
  }

  @Test
  public void testRegistration() throws Exception {
    BeanB bean = BeanB.createBeanB(2);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      AtomicReference<Throwable> error = new AtomicReference<>();
      ThreadSafeFory pooled =
          Fory.builder().requireClassRegistration(true).buildThreadSafeForyPool(4);
      pooled.register(BeanB.class);
      assertEquals(pooled.deserialize(pooled.serialize(bean)), bean);
      executor.execute(
          () -> {
            try {
              assertEquals(pooled.deserialize(pooled.serialize(bean)), bean);
            } catch (Throwable t) {
              error.set(t);
            }
          });
      executor.shutdown();
      assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
      assertNull(error.get());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void testThreadPoolReusesForyAcrossThreads() throws InterruptedException {
    ThreadSafeFory fory = Fory.builder().requireClassRegistration(false).buildThreadSafeForyPool(1);
    AtomicReference<Integer> firstForyId = new AtomicReference<>();
    AtomicReference<Integer> secondForyId = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    try {
      Thread first =
          new Thread(
              () -> {
                try {
                  firstForyId.set(fory.execute(System::identityHashCode));
                } catch (Throwable t) {
                  error.compareAndSet(null, t);
                }
              });
      Thread second =
          new Thread(
              () -> {
                try {
                  secondForyId.set(fory.execute(System::identityHashCode));
                } catch (Throwable t) {
                  error.compareAndSet(null, t);
                }
              });
      first.start();
      first.join();
      second.start();
      second.join();
      if (error.get() != null) {
        throw new AssertionError(error.get());
      }
      assertNotNull(firstForyId.get());
      assertEquals(secondForyId.get(), firstForyId.get());
    } finally {
      // no-op
    }
  }

  @Test
  public void testSerializeWithMetaShare() throws InterruptedException {
    ThreadSafeFory plain =
        Fory.builder().withXlang(false).requireClassRegistration(false).buildThreadSafeFory();
    ThreadSafeFory shared =
        Fory.builder()
            .withXlang(false)
            .withMetaShare(true)
            .requireClassRegistration(false)
            .buildThreadSafeFory();
    BeanA beanA = BeanA.createBeanA(2);
    ExecutorService executorService = Executors.newFixedThreadPool(12);
    AtomicReference<Throwable> error = new AtomicReference<>();
    for (int i = 0; i < 200; i++) {
      executorService.execute(
          () -> {
            try {
              for (int j = 0; j < 10; j++) {
                byte[] serialized = plain.execute(f -> f.serialize(beanA));
                assertEquals(plain.execute(f -> f.deserialize(serialized)), beanA);

                byte[] sharedBytes =
                    shared.execute(
                        f -> {
                          f.setMetaWriteContext(new MetaWriteContext());
                          return f.serialize(beanA);
                        });
                Object sharedObj =
                    shared.execute(
                        f -> {
                          f.setMetaReadContext(new MetaReadContext());
                          return f.deserialize(sharedBytes);
                        });
                assertEquals(sharedObj, beanA);
              }
            } catch (Throwable t) {
              error.compareAndSet(null, t);
            }
          });
    }
    executorService.shutdown();
    assertTrue(executorService.awaitTermination(30, TimeUnit.SECONDS));
    if (error.get() != null) {
      throw new AssertionError(error.get());
    }
  }

  @Test
  public void testThreadLocalMetaShareWithPerThreadMetaContexts() throws InterruptedException {
    ThreadSafeFory fory =
        Fory.builder()
            .withXlang(false)
            .withMetaShare(true)
            .requireClassRegistration(false)
            .buildThreadLocalFory();
    BeanA beanA = BeanA.createBeanA(2);
    ExecutorService executorService = Executors.newFixedThreadPool(12);
    ConcurrentHashMap<Thread, MetaWriteContext> writeMetaMap = new ConcurrentHashMap<>();
    ConcurrentHashMap<Thread, MetaReadContext> readMetaMap = new ConcurrentHashMap<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    for (int i = 0; i < 200; i++) {
      executorService.execute(
          () -> {
            try {
              for (int j = 0; j < 10; j++) {
                MetaWriteContext metaWriteContext =
                    writeMetaMap.computeIfAbsent(
                        Thread.currentThread(), t -> new MetaWriteContext());
                MetaReadContext metaReadContext =
                    readMetaMap.computeIfAbsent(Thread.currentThread(), t -> new MetaReadContext());
                byte[] serialized =
                    fory.execute(
                        f -> {
                          f.setMetaWriteContext(metaWriteContext);
                          return f.serialize(beanA);
                        });
                Object newObj =
                    fory.execute(
                        f -> {
                          f.setMetaReadContext(metaReadContext);
                          return f.deserialize(serialized);
                        });
                assertEquals(newObj, beanA);
              }
            } catch (Throwable t) {
              error.compareAndSet(null, t);
            }
          });
    }
    executorService.shutdown();
    assertTrue(executorService.awaitTermination(30, TimeUnit.SECONDS));
    if (error.get() != null) {
      throw new AssertionError(error.get());
    }
  }

  @Test
  public void testSerializeDeserializeWithType() {
    for (ThreadSafeFory fory :
        new ThreadSafeFory[] {
          Fory.builder().requireClassRegistration(false).buildThreadSafeFory(),
          Fory.builder().requireClassRegistration(false).buildThreadSafeForyPool(2)
        }) {
      byte[] bytes = fory.serialize("abc");
      Assert.assertEquals(fory.deserialize(bytes, String.class), "abc");
      MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(8);
      fory.serialize(buffer, "abc");
      Assert.assertEquals(fory.deserialize(buffer, String.class), "abc");
    }
  }

  @Data
  static class Foo {
    int f1;
  }

  public static class FooSerializer extends Serializer<Foo> {
    public FooSerializer(TypeResolver typeResolver, Class<Foo> type) {
      super(typeResolver.getConfig(), type);
    }

    @Override
    public void write(WriteContext writeContext, Foo value) {
      writeContext.getBuffer().writeInt32(value.f1);
    }

    @Override
    public Foo read(ReadContext readContext) {
      Foo foo = new Foo();
      foo.f1 = readContext.getBuffer().readInt32();
      return foo;
    }
  }

  public static class CustomClassLoader extends ClassLoader {
    public CustomClassLoader(ClassLoader parent) {
      super(parent);
    }
  }

  @Test
  public void testBuilderClassLoaderStaysFixed() throws Exception {
    ClassLoader loader = new CustomClassLoader(ClassLoader.getSystemClassLoader());
    ThreadSafeFory threadSafe =
        Fory.builder()
            .withClassLoader(loader)
            .requireClassRegistration(false)
            .buildThreadSafeFory();
    ThreadSafeFory threadLocal =
        Fory.builder()
            .withClassLoader(loader)
            .requireClassRegistration(false)
            .buildThreadLocalFory();
    ThreadSafeFory threadPool =
        Fory.builder()
            .withClassLoader(loader)
            .requireClassRegistration(false)
            .buildThreadSafeForyPool(2);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      for (ThreadSafeFory fory : new ThreadSafeFory[] {threadSafe, threadLocal, threadPool}) {
        AtomicReference<ClassLoader> seen = new AtomicReference<>();
        executor.submit(() -> seen.set(fory.execute(Fory::getClassLoader))).get();
        assertSame(seen.get(), loader);
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void testSerializerRegister() {
    ThreadSafeFory threadSafeFory =
        Fory.builder().requireClassRegistration(false).buildThreadSafeForyPool(2);
    threadSafeFory.registerSerializer(Foo.class, FooSerializer.class);
    threadSafeFory.execute(
        fory -> {
          Assert.assertEquals(
              fory.getTypeResolver().getSerializer(Foo.class).getClass(), FooSerializer.class);
          return null;
        });
  }

  @Test
  public void testRegisterAfterSerializeThrowsException() {
    ThreadSafeFory fory = Fory.builder().requireClassRegistration(true).buildThreadLocalFory();
    fory.register(BeanA.class);
    fory.serialize("ok");
    Assert.assertThrows(ForyException.class, () -> fory.register(BeanB.class));
  }

  @Test
  public void testRegisterAfterSerializeThrowsExceptionWithFory() {
    Fory fory = Fory.builder().requireClassRegistration(true).build();
    fory.register(BeanA.class);
    fory.serialize("ok");
    Assert.assertThrows(ForyException.class, () -> fory.register(BeanB.class));
  }

  @Test
  public void testRegisterAfterSerializeThrowsExceptionWithForyPool() {
    ThreadSafeFory fory = Fory.builder().requireClassRegistration(true).buildThreadSafeForyPool(2);
    fory.register(BeanA.class);
    fory.serialize("ok");
    Assert.assertThrows(ForyException.class, () -> fory.register(BeanB.class));
  }

  private void assertConcurrentRoundTrip(ThreadSafeFory fory, BeanA beanA)
      throws InterruptedException {
    ExecutorService executorService = Executors.newFixedThreadPool(12);
    AtomicReference<Throwable> error = new AtomicReference<>();
    for (int i = 0; i < 2000; i++) {
      executorService.execute(
          () -> {
            for (int j = 0; j < 10; j++) {
              try {
                assertEquals(fory.deserialize(fory.serialize(beanA)), beanA);
              } catch (Throwable t) {
                error.compareAndSet(null, t);
              }
            }
          });
    }
    executorService.shutdown();
    assertTrue(executorService.awaitTermination(30, TimeUnit.SECONDS));
    if (error.get() != null) {
      throw new AssertionError(error.get());
    }
  }
}

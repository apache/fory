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

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.fory.pool.ThreadPoolFory;
import org.testng.annotations.Test;

public class VirtualThreadSafeForyTest {

  static class CustomClassLoader extends ClassLoader {
    CustomClassLoader(ClassLoader parent) {
      super(parent);
    }
  }

  @Test
  public void testBuildThreadSafeForyUsesFixedBuilderClassLoader() throws InterruptedException {
    ClassLoader classLoader = new CustomClassLoader(ClassLoader.getSystemClassLoader());
    ThreadSafeFory fory =
        Fory.builder()
            .withClassLoader(classLoader)
            .requireClassRegistration(false)
            .buildThreadSafeFory();
    AtomicReference<Throwable> error = new AtomicReference<>();
    Thread thread =
        Thread.startVirtualThread(
            () -> {
              try {
                assertTrue(Thread.currentThread().isVirtual());
                assertSame(fory.execute(Fory::getClassLoader), classLoader);
                byte[] bytes = fory.serialize("abc");
                assertEquals(fory.deserialize(bytes), "abc");
              } catch (Throwable t) {
                error.set(t);
              }
            });
    thread.join();
    if (error.get() != null) {
      throw new AssertionError(error.get());
    }
  }

  @Test
  public void testVirtualThreadsUseFixedSizeThreadPoolFory() throws Exception {
    ThreadPoolFory fory =
        (ThreadPoolFory) Fory.builder().requireClassRegistration(false).buildThreadSafeForyPool(2);
    int threadCount = 8;
    CountDownLatch acquired = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    Set<Integer> identities = ConcurrentHashMap.newKeySet();
    AtomicReference<Throwable> error = new AtomicReference<>();
    List<Thread> threads = new ArrayList<>(threadCount);
    for (int i = 0; i < threadCount; i++) {
      threads.add(
          Thread.startVirtualThread(
              () -> {
                try {
                  fory.execute(
                      instance -> {
                        identities.add(System.identityHashCode(instance));
                        acquired.countDown();
                        await(release);
                        return null;
                      });
                } catch (Throwable t) {
                  error.compareAndSet(null, t);
                }
              }));
    }
    assertTrue(acquired.await(10, SECONDS));
    assertEquals(identities.size(), 2);
    release.countDown();
    for (Thread thread : threads) {
      thread.join();
    }
    if (error.get() != null) {
      throw new AssertionError(error.get());
    }
    assertEquals(identities.size(), 2);
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(10, SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}

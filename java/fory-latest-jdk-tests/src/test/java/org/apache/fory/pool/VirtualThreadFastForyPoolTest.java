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

package org.apache.fory.pool;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import org.apache.fory.Fory;
import org.testng.annotations.Test;

public class VirtualThreadFastForyPoolTest {

  @Test
  public void testBuildVirtualThreadSafeForyRespectsMaxIdlePoolSize() throws Exception {
    FastForyPool fory =
        (FastForyPool) Fory.builder().requireClassRegistration(false).buildVirtualThreadSafeFory(1);
    int threadCount = 8;
    CountDownLatch acquired = new CountDownLatch(threadCount);
    CountDownLatch release = new CountDownLatch(1);
    Set<Integer> identities = ConcurrentHashMap.newKeySet();
    List<Thread> threads = new ArrayList<>(threadCount);
    for (int i = 0; i < threadCount; i++) {
      threads.add(
          Thread.startVirtualThread(
              () ->
                  fory.execute(
                      instance -> {
                        identities.add(System.identityHashCode(instance));
                        acquired.countDown();
                        await(release);
                        return null;
                      })));
    }
    assertTrue(acquired.await(10, SECONDS));
    assertEquals(identities.size(), threadCount);
    release.countDown();
    for (Thread thread : threads) {
      thread.join();
    }
    assertEquals(fory.pooledForyCount(), 1);
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

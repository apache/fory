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

package org.apache.fory.collection;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.expectThrows;

import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;

public class ConcurrentIdentityMapTest {

  @Test
  public void testUsesIdentityForLookupAndRemove() {
    ConcurrentIdentityMap<String, Integer> map = new ConcurrentIdentityMap<>();
    String key = new String("key");
    String equalButDifferentKey = new String("key");

    assertNull(map.put(key, 1));
    assertEquals(map.get(key), Integer.valueOf(1));
    assertNull(map.get(equalButDifferentKey));
    assertEquals(map.remove(key), Integer.valueOf(1));
    assertNull(map.get(key));
  }

  @Test
  public void testPutIfAbsentUsesIdentity() {
    ConcurrentIdentityMap<String, Integer> map = new ConcurrentIdentityMap<>();
    String key = new String("key");
    String equalButDifferentKey = new String("key");

    assertNull(map.putIfAbsent(key, 1));
    assertEquals(map.putIfAbsent(key, 2), Integer.valueOf(1));
    assertEquals(map.get(key), Integer.valueOf(1));

    assertNull(map.putIfAbsent(equalButDifferentKey, 3));
    assertEquals(map.get(equalButDifferentKey), Integer.valueOf(3));
  }

  @Test
  public void testComputeIfAbsentUsesIdentity() {
    ConcurrentIdentityMap<String, Integer> map = new ConcurrentIdentityMap<>();
    AtomicInteger calls = new AtomicInteger();
    String key = new String("key");
    String equalButDifferentKey = new String("key");

    assertEquals(
        map.computeIfAbsent(
            key,
            k -> {
              assertSame(k, key);
              return calls.incrementAndGet();
            }),
        Integer.valueOf(1));
    assertEquals(map.computeIfAbsent(key, k -> calls.incrementAndGet()), Integer.valueOf(1));
    assertEquals(
        map.computeIfAbsent(
            equalButDifferentKey,
            k -> {
              assertSame(k, equalButDifferentKey);
              return calls.incrementAndGet();
            }),
        Integer.valueOf(2));
    assertEquals(calls.get(), 2);
  }

  @Test
  public void testRejectsNullKeys() {
    ConcurrentIdentityMap<String, Integer> map = new ConcurrentIdentityMap<>();

    expectThrows(NullPointerException.class, () -> map.get(null));
    expectThrows(NullPointerException.class, () -> map.put(null, 1));
    expectThrows(NullPointerException.class, () -> map.putIfAbsent(null, 1));
    expectThrows(NullPointerException.class, () -> map.remove(null));
    expectThrows(NullPointerException.class, () -> map.computeIfAbsent(null, k -> 1));
  }
}

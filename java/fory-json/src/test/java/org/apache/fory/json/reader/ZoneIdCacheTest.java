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

package org.apache.fory.json.reader;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.JsonConfig;
import org.apache.fory.json.resolver.JsonSharedRegistry;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.testng.annotations.Test;

public class ZoneIdCacheTest {
  private static final JsonConfig CONFIG = config();
  private static final JsonSharedRegistry REGISTRY = new JsonSharedRegistry(CONFIG);

  @Test
  public void hashCollisions() {
    ZoneIdCache cache = new ZoneIdCache();
    // Supply equal hashes to exercise comparison after both local and global candidate hits.
    long hash = Long.MIN_VALUE;
    ZoneId paris = read(cache, "Europe/Paris", hash);
    assertEquals(read(cache, "Europe/Rome", hash), ZoneId.of("Europe/Rome"));
    assertSame(read(cache, "Europe/Paris", hash), paris);
    ZoneIdCache other = new ZoneIdCache();
    assertEquals(read(other, "Europe/Rome", hash), ZoneId.of("Europe/Rome"));
    assertSame(read(other, "Europe/Paris", hash), paris);
    assertNotSame(read(other, "Europe/Rome", hash), read(other, "Europe/Rome", hash));
  }

  @Test
  public void concurrentPublication() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(4);
    CyclicBarrier barrier = new CyclicBarrier(4);
    try {
      List<Future<ZoneId>> futures = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        futures.add(
            executor.submit(
                () -> {
                  ZoneIdCache cache = new ZoneIdCache();
                  barrier.await();
                  return read(cache, "Europe/London", Long.MIN_VALUE + 1);
                }));
      }
      ZoneId zone = futures.get(0).get();
      assertEquals(zone, ZoneId.of("Europe/London"));
      for (Future<ZoneId> future : futures) {
        assertSame(future.get(), zone);
      }
    } finally {
      executor.shutdownNow();
    }
  }

  private static ZoneId read(ZoneIdCache cache, String id, long hash) {
    Utf8JsonReader reader =
        new Utf8JsonReader(
            CONFIG, new JsonTypeResolver(REGISTRY), id.getBytes(StandardCharsets.UTF_8));
    return cache.get(reader, 0, id.length(), hash);
  }

  private static JsonConfig config() {
    try {
      Field field = ForyJson.class.getDeclaredField("config");
      field.setAccessible(true);
      return (JsonConfig) field.get(ForyJson.builder().withCodegen(false).build());
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}

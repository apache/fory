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

package org.apache.fory.json;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.apache.fory.json.meta.JsonFieldNameHash;
import org.apache.fory.json.resolver.JsonSharedRegistry;
import org.apache.fory.json.resolver.JsonSharedRegistry.CachedMemberName;
import org.apache.fory.reflect.TypeRef;
import org.testng.annotations.Test;

public class JsonMemberNameCacheTest {
  @Test
  public void configuration() {
    JsonConfig defaults = JsonTestSupport.config(ForyJson.builder().build());
    assertEquals(defaults.maxCachedMemberNames(), ForyJson.DEFAULT_MAX_CACHED_MEMBER_NAMES);
    assertThrows(
        IllegalArgumentException.class, () -> ForyJson.builder().withMaxCachedMemberNames(-1));

    JsonConfig first =
        JsonTestSupport.config(
            ForyJson.builder().withConcurrencyLevel(1).withMaxCachedMemberNames(1).build());
    JsonConfig second =
        JsonTestSupport.config(
            ForyJson.builder().withConcurrencyLevel(1).withMaxCachedMemberNames(2).build());
    assertNotEquals(first, second);
    assertNotEquals(first.hashCode(), second.hashCode());
    assertEquals(first.getCodegenHash(), second.getCodegenHash());
    assertEquals(first.memberNameCacheSlots(), 1);
    assertEquals(JsonTestSupport.config(newJson(0)).memberNameCacheSlots(), 0);
    assertEquals(
        JsonTestSupport.config(newJson(ForyJson.DEFAULT_MAX_CACHED_MEMBER_NAMES))
            .memberNameCacheSlots(),
        1024);
  }

  @Test
  public void representations() {
    ForyJson json = newJson(64);
    String latin1 = firstKey(json.fromJson("{\"shared\":1}", JsonObject.class));
    String utf16 = firstKey(json.fromJson("{\"shared\":\"你好\"}", JsonObject.class));
    String utf8 =
        firstKey(
            json.fromJson("{\"shared\":1}".getBytes(StandardCharsets.UTF_8), JsonObject.class));
    assertSame(utf16, latin1);
    assertSame(utf8, latin1);
  }

  @Test
  public void retainedKeyPaths() {
    ForyJson json = newJson(64);
    String canonical = firstKey(json.fromJson("{\"common\":1}", JsonObject.class));

    Object dynamic = json.fromJson("{\"common\":1}", Object.class);
    assertSame(firstKey((Map<?, ?>) dynamic), canonical);
    assertSame(
        firstKey(json.fromJson("{\"common\":1}", new TypeRef<Map<String, Integer>>() {})),
        canonical);
    assertSame(
        firstKey(json.fromJson("{\"common\":\"v\"}", new TypeRef<Map<String, String>>() {})),
        canonical);
    assertSame(
        firstKey(json.fromJson("{\"common\":1}", new TypeRef<Map<Object, Object>>() {})),
        canonical);
  }

  @Test
  public void eligibilityBoundaries() {
    assertCached(newJson(64), "");
    assertCached(newJson(64), "a");
    assertCached(newJson(64), "12345678");
    assertCached(newJson(64), "123456789");
    assertCached(newJson(64), "1234567890123456");

    assertNotCached(newJson(64), "12345678901234567");
    assertNotCached(newJson(64), "café");
    assertNotCached(newJson(64), "键");
    ForyJson utf8 = newJson(64);
    byte[] multibyteName = "{\"键\":1}".getBytes(StandardCharsets.UTF_8);
    String utf8First = firstKey(utf8.fromJson(multibyteName, JsonObject.class));
    String utf8Second = firstKey(utf8.fromJson(multibyteName, JsonObject.class));
    assertEquals(utf8Second, "键");
    assertNotSame(utf8Second, utf8First);
    ForyJson json = newJson(64);
    String escapedFirst = firstKey(json.fromJson("{\"na\\u006de\":1}", JsonObject.class));
    String escapedSecond = firstKey(json.fromJson("{\"na\\u006de\":1}", JsonObject.class));
    assertEquals(escapedFirst, "name");
    assertNotSame(escapedSecond, escapedFirst);
  }

  @Test
  public void longMemberNames() {
    ForyJson json = newJson(64);
    assertNotCached(json, "abcdefghijklmnopqrstuvwxyz0123456789");
    assertNotCached(json, "abcdefghijklmnopé");

    String first = firstKey(json.fromJson("{\"abcdefghijklmnop\\u0071\":1}", JsonObject.class));
    String second = firstKey(json.fromJson("{\"abcdefghijklmnop\\u0071\":1}", JsonObject.class));
    assertEquals(first, "abcdefghijklmnopq");
    assertEquals(second, first);
    assertNotSame(second, first);
  }

  @Test
  public void disabledAndFull() {
    ForyJson disabled = newJson(0);
    assertNotCached(disabled, "name");

    ForyJson bounded = newJson(1);
    assertCached(bounded, "first");
    assertNotCached(bounded, "second");
    assertCached(bounded, "first");
  }

  @Test
  public void fullFallbacks() {
    ForyJson utf8 = newJson(1);
    byte[] utf8First = "{\"first\":1}".getBytes(StandardCharsets.UTF_8);
    byte[] utf8Second = "{\"second\":1}".getBytes(StandardCharsets.UTF_8);
    String utf8Canonical = firstKey(utf8.fromJson(utf8First, JsonObject.class));
    String utf8Second1 = firstKey(utf8.fromJson(utf8Second, JsonObject.class));
    String utf8Second2 = firstKey(utf8.fromJson(utf8Second, JsonObject.class));
    assertNotSame(utf8Second2, utf8Second1);
    byte[] utf8Long = "{\"abcdefghijklmnop\":1}".getBytes(StandardCharsets.UTF_8);
    String utf8Long1 = firstKey(utf8.fromJson(utf8Long, JsonObject.class));
    String utf8Long2 = firstKey(utf8.fromJson(utf8Long, JsonObject.class));
    assertEquals(utf8Long2, "abcdefghijklmnop");
    assertNotSame(utf8Long2, utf8Long1);
    assertSame(firstKey(utf8.fromJson(utf8First, JsonObject.class)), utf8Canonical);

    ForyJson utf16 = newJson(1);
    String utf16Canonical = firstKey(utf16.fromJson("{\"first\":\"中文\"}", JsonObject.class));
    String utf16Second1 = firstKey(utf16.fromJson("{\"second\":\"中文\"}", JsonObject.class));
    String utf16Second2 = firstKey(utf16.fromJson("{\"second\":\"中文\"}", JsonObject.class));
    assertNotSame(utf16Second2, utf16Second1);
    String utf16Long1 = firstKey(utf16.fromJson("{\"abcdefghijklmnop\":\"中文\"}", JsonObject.class));
    String utf16Long2 = firstKey(utf16.fromJson("{\"abcdefghijklmnop\":\"中文\"}", JsonObject.class));
    assertEquals(utf16Long2, "abcdefghijklmnop");
    assertNotSame(utf16Long2, utf16Long1);
    assertSame(firstKey(utf16.fromJson("{\"first\":\"中文\"}", JsonObject.class)), utf16Canonical);
  }

  @Test
  public void localReplacement() {
    ForyJson json = newJson(3);
    String first = firstKey(json.fromJson("{\"first\":1}", JsonObject.class));
    byte[] secondBytes = "{\"second\":1}".getBytes(StandardCharsets.UTF_8);
    String sharedSecond = firstKey(json.fromJson(secondBytes, JsonObject.class));

    String second1 = firstKey(json.fromJson("{\"second\":1}", JsonObject.class));
    String second2 = firstKey(json.fromJson("{\"second\":1}", JsonObject.class));
    assertSame(second1, sharedSecond);
    assertSame(second2, second1);
    assertSame(firstKey(json.fromJson("{\"first\":1}", JsonObject.class)), first);
  }

  @Test
  public void valuesAreNotCached() {
    ForyJson json = newJson(64);
    JsonObject first = json.fromJson("{\"name\":\"name\"}", JsonObject.class);
    JsonObject second = json.fromJson("{\"name\":\"name\"}", JsonObject.class);
    String key = firstKey(first);
    assertNotSame(first.get("name"), key);
    assertNotSame(second.get("name"), first.get("name"));
    assertSame(firstKey(second), key);
  }

  @Test
  public void separateRuntimes() {
    String first = firstKey(newJson(64).fromJson("{\"name\":1}", JsonObject.class));
    String second = firstKey(newJson(64).fromJson("{\"name\":1}", JsonObject.class));
    assertNotSame(second, first);
  }

  @Test
  public void malformedNames() {
    ForyJson json = newJson(64);
    assertThrows(ForyJsonException.class, () -> json.fromJson("{\"bad\\q\":1}", JsonObject.class));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"abcdefghijklmnop\\q\":1}", JsonObject.class));
    assertThrows(
        ForyJsonException.class, () -> json.fromJson("{\"bad\u0001\":1}", JsonObject.class));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"abcdefghijklmnop\u0001\":1}", JsonObject.class));
    assertThrows(
        ForyJsonException.class, () -> json.fromJson("{\"abcdefghijklmnopq", JsonObject.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("{\"\ud800\":1}", JsonObject.class));

    byte[] invalidUtf8 = {'{', '"', (byte) 0xc0, (byte) 0x80, '"', ':', '1', '}'};
    assertThrows(ForyJsonException.class, () -> json.fromJson(invalidUtf8, JsonObject.class));
    JsonSharedRegistry registry = JsonTestSupport.primaryTypeResolver(json).sharedRegistry();
    assertNull(registry.cachedMemberName(JsonFieldNameHash.hash("bad")));
    assertNull(registry.cachedMemberName(JsonFieldNameHash.hash("")));
  }

  @Test
  public void strictSharedLimit() throws Exception {
    int limit = 64;
    JsonConfig config =
        JsonTestSupport.config(
            ForyJson.builder().withConcurrencyLevel(1).withMaxCachedMemberNames(limit).build());
    JsonSharedRegistry registry = new JsonSharedRegistry(config);
    int threads = 8;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    for (int thread = 0; thread < threads; thread++) {
      final int threadIndex = thread;
      executor.execute(
          () -> {
            try {
              start.await();
              for (int i = 0; i < limit; i++) {
                String name = "k" + threadIndex + '-' + i;
                long[] words = pack(name);
                registry.cacheMemberName(JsonFieldNameHash.hash(name), name, words[0], words[1]);
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              done.countDown();
            }
          });
    }
    start.countDown();
    assertTrue(done.await(10, TimeUnit.SECONDS));
    executor.shutdown();
    executor.awaitTermination(10, TimeUnit.SECONDS);
    int cached = 0;
    for (int thread = 0; thread < threads; thread++) {
      for (int i = 0; i < limit; i++) {
        if (registry.cachedMemberName(JsonFieldNameHash.hash("k" + thread + '-' + i)) != null) {
          cached++;
        }
      }
    }
    assertEquals(cached, limit);
  }

  @Test
  public void sharedHashCollision() {
    JsonConfig config = JsonTestSupport.config(newJson(4));
    JsonSharedRegistry registry = new JsonSharedRegistry(config);
    CachedMemberName first = registry.cacheMemberName(1L, "a", 'a', 0);
    CachedMemberName second = registry.cacheMemberName(1L, "b", 'b', 0);
    assertSame(second, first);
    assertEquals(first.name(), "a");
    assertSame(registry.cachedMemberName(1L), first);
  }

  @Test
  public void readerHashCollision() {
    ForyJson json = newJson(8);
    String expected = "collisionKey";
    String different = "differentKey";
    long hash = JsonFieldNameHash.hash(expected);
    long[] words = pack(different);
    JsonSharedRegistry registry = JsonTestSupport.primaryTypeResolver(json).sharedRegistry();
    CachedMemberName collision =
        registry.cacheMemberName(hash, new String(different), words[0], words[1]);

    assertCollisionFallback(
        collision,
        firstKey(json.fromJson("{\"collisionKey\":1}", JsonObject.class)),
        firstKey(json.fromJson("{\"collisionKey\":2}", JsonObject.class)));
    assertCollisionFallback(
        collision,
        firstKey(json.fromJson("{\"collisionKey\":\"中文\"}", JsonObject.class)),
        firstKey(json.fromJson("{\"collisionKey\":\"中文\"}", JsonObject.class)));
    byte[] utf8 = "{\"collisionKey\":1}".getBytes(StandardCharsets.UTF_8);
    assertCollisionFallback(
        collision,
        firstKey(json.fromJson(utf8, JsonObject.class)),
        firstKey(json.fromJson(utf8, JsonObject.class)));
    assertSame(registry.cachedMemberName(hash), collision);
  }

  @Test
  public void concurrentPublication() throws Exception {
    JsonConfig config = JsonTestSupport.config(newJson(1));
    JsonSharedRegistry registry = new JsonSharedRegistry(config);
    int threads = 8;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    AtomicReferenceArray<CachedMemberName> results = new AtomicReferenceArray<>(threads);
    String name = "name";
    long hash = JsonFieldNameHash.hash(name);
    long[] words = pack(name);
    for (int i = 0; i < threads; i++) {
      final int index = i;
      executor.execute(
          () -> {
            try {
              start.await();
              results.set(
                  index, registry.cacheMemberName(hash, new String(name), words[0], words[1]));
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              done.countDown();
            }
          });
    }
    start.countDown();
    assertTrue(done.await(10, TimeUnit.SECONDS));
    executor.shutdown();
    executor.awaitTermination(10, TimeUnit.SECONDS);
    for (int i = 1; i < threads; i++) {
      assertSame(results.get(i), results.get(0));
    }
    assertSame(registry.cachedMemberName(hash), results.get(0));
  }

  private static ForyJson newJson(int maxCachedMemberNames) {
    return ForyJson.builder()
        .withCodegen(false)
        .withAsyncCompilation(false)
        .withConcurrencyLevel(1)
        .withMaxCachedMemberNames(maxCachedMemberNames)
        .build();
  }

  private static void assertCached(ForyJson json, String name) {
    String first = firstKey(json.fromJson(jsonForName(name), JsonObject.class));
    String second = firstKey(json.fromJson(jsonForName(name), JsonObject.class));
    assertSame(second, first);
    CachedMemberName entry =
        JsonTestSupport.primaryTypeResolver(json)
            .sharedRegistry()
            .cachedMemberName(JsonFieldNameHash.hash(name));
    assertNotNull(entry);
    assertSame(entry.name(), first);
  }

  private static void assertNotCached(ForyJson json, String name) {
    String first = firstKey(json.fromJson(jsonForName(name), JsonObject.class));
    String second = firstKey(json.fromJson(jsonForName(name), JsonObject.class));
    assertEquals(first, name);
    assertEquals(second, name);
    assertNotSame(second, first);
  }

  private static void assertCollisionFallback(
      CachedMemberName collision, String first, String second) {
    assertEquals(first, "collisionKey");
    assertEquals(second, "collisionKey");
    assertNotSame(first, collision.name());
    assertNotSame(second, collision.name());
    assertNotSame(second, first);
  }

  private static String jsonForName(String name) {
    return "{\"" + name + "\":1}";
  }

  private static String firstKey(Map<?, ?> map) {
    return (String) map.keySet().iterator().next();
  }

  private static long[] pack(String name) {
    long word0 = 0;
    long word1 = 0;
    for (int i = 0; i < name.length(); i++) {
      if (i < Long.BYTES) {
        word0 |= ((long) name.charAt(i)) << (i << 3);
      } else {
        word1 |= ((long) name.charAt(i)) << ((i - Long.BYTES) << 3);
      }
    }
    return new long[] {word0, word1};
  }
}

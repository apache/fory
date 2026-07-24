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

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.lang.reflect.Field;
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.meta.JsonFieldNameHash;
import org.apache.fory.json.resolver.JsonSharedRegistry;
import org.apache.fory.json.resolver.JsonSharedRegistry.CachedFieldName;
import org.testng.annotations.Test;

public class FieldNameCacheTest {
  private static final JsonSharedRegistry REGISTRY = registry();

  @Test
  public void fixedCandidatesAndLimit() {
    FieldNameCache cache = new FieldNameCache(3);
    CachedFieldName home = entry("home");
    CachedFieldName adjacent = entry("adjacent");
    CachedFieldName fallback = entry("fallback");
    CachedFieldName unrelated = entry("unrelated");

    assertNull(cache.get(0L));
    assertTrue(cache.canPut(0L));
    cache.put(0L, home);
    assertSame(cache.get(0L), home);

    assertTrue(cache.canPut(8L));
    cache.put(8L, adjacent);
    assertSame(cache.get(8L), adjacent);

    assertFalse(cache.canPut(16L));
    cache.put(16L, fallback);
    assertNull(cache.get(16L));
    assertSame(cache.get(0L), home);
    assertSame(cache.get(8L), adjacent);

    assertTrue(cache.canPut(2L));
    cache.put(2L, unrelated);
    assertSame(cache.get(2L), unrelated);
    assertFalse(cache.canPut(3L));
    cache.put(3L, fallback);
    assertNull(cache.get(3L));
  }

  @Test
  public void idempotentPut() {
    FieldNameCache cache = new FieldNameCache(1);
    CachedFieldName first = entry("first");
    CachedFieldName second = entry("second");

    cache.put(1L, first);
    cache.put(1L, first);
    cache.put(1L, second);

    assertSame(cache.get(1L), first);
    assertFalse(cache.canPut(1L));
  }

  private static JsonSharedRegistry registry() {
    try {
      ForyJson json = ForyJson.builder().withCodegen(false).build();
      Field field = ForyJson.class.getDeclaredField("sharedRegistry");
      field.setAccessible(true);
      return (JsonSharedRegistry) field.get(json);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }

  private static CachedFieldName entry(String name) {
    long word0 = 0;
    long word1 = 0;
    for (int i = 0; i < name.length(); i++) {
      if (i < Long.BYTES) {
        word0 |= ((long) name.charAt(i)) << (i << 3);
      } else {
        word1 |= ((long) name.charAt(i)) << ((i - Long.BYTES) << 3);
      }
    }
    return REGISTRY.cacheFieldName(JsonFieldNameHash.hash(name), name, word0, word1);
  }
}

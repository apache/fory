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

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * A thread-safe map that compares keys by identity instead of {@link Object#equals(Object)}.
 *
 * <p>This is intended for internal caches backed by {@link ConcurrentHashMap}, so it keeps the API
 * intentionally small. Null keys are rejected explicitly, and null values remain unsupported by the
 * underlying map.
 */
public final class ConcurrentIdentityMap<K, V> {
  private final ConcurrentHashMap<IdentityKey<K>, V> map = new ConcurrentHashMap<>();

  public V get(K key) {
    return map.get(new IdentityKey<K>(requireKey(key)));
  }

  public V put(K key, V value) {
    return map.put(new IdentityKey<K>(requireKey(key)), value);
  }

  public V putIfAbsent(K key, V value) {
    return map.putIfAbsent(new IdentityKey<K>(requireKey(key)), value);
  }

  public V remove(K key) {
    return map.remove(new IdentityKey<K>(requireKey(key)));
  }

  public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
    Objects.requireNonNull(mappingFunction, "mappingFunction");
    return map.computeIfAbsent(
        new IdentityKey<K>(requireKey(key)), identityKey -> mappingFunction.apply(identityKey.ref));
  }

  public void removeIf(BiPredicate<? super K, ? super V> predicate) {
    Objects.requireNonNull(predicate, "predicate");
    map.entrySet().removeIf(entry -> predicate.test(entry.getKey().ref, entry.getValue()));
  }

  private static <K> K requireKey(K key) {
    return Objects.requireNonNull(key, "key");
  }

  /**
   * Wraps a key so the backing {@link ConcurrentHashMap} can reuse identity-based equals/hashCode
   * without exposing wrapper objects to callers.
   */
  private static final class IdentityKey<K> {
    private final K ref;
    private final int hash;

    private IdentityKey(K ref) {
      this.ref = ref;
      this.hash = System.identityHashCode(ref);
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof IdentityKey)) {
        return false;
      }
      IdentityKey<?> other = (IdentityKey<?>) obj;
      return ref == other.ref;
    }

    @Override
    public int hashCode() {
      return hash;
    }
  }
}

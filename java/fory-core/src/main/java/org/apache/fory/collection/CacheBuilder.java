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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import org.apache.fory.util.Preconditions;

/** Minimal cache builder supporting the subset used by fory-core. */
public final class CacheBuilder<K, V> {
  private boolean weakKeys;
  private boolean softValues;
  private int concurrencyLevel = 4;

  private CacheBuilder() {}

  public static CacheBuilder<Object, Object> newBuilder() {
    return new CacheBuilder<>();
  }

  public CacheBuilder<K, V> weakKeys() {
    weakKeys = true;
    return this;
  }

  public CacheBuilder<K, V> softValues() {
    softValues = true;
    return this;
  }

  public CacheBuilder<K, V> concurrencyLevel(int concurrencyLevel) {
    Preconditions.checkArgument(concurrencyLevel > 0, "concurrencyLevel must be positive");
    this.concurrencyLevel = concurrencyLevel;
    return this;
  }

  public <K1 extends K, V1 extends V> Cache<K1, V1> build() {
    return new LocalCache<>(new ReferenceConcurrentMap<>(weakKeys, softValues, concurrencyLevel));
  }

  private static final class LocalCache<K, V> implements Cache<K, V> {
    private final ReferenceConcurrentMap<K, V> cache;

    private LocalCache(ReferenceConcurrentMap<K, V> cache) {
      this.cache = cache;
    }

    @Override
    public V getIfPresent(K key) {
      return cache.get(key);
    }

    @Override
    public V get(K key, Callable<? extends V> loader) throws ExecutionException {
      Objects.requireNonNull(loader);
      V value = cache.get(key);
      if (value != null) {
        return value;
      }
      V loadedValue;
      try {
        loadedValue = loader.call();
      } catch (Exception e) {
        throw new ExecutionException(e);
      }
      if (loadedValue == null) {
        return null;
      }
      V existing = cache.putIfAbsent(key, loadedValue);
      return existing != null ? existing : loadedValue;
    }

    @Override
    public void put(K key, V value) {
      cache.put(key, value);
    }

    @Override
    public void cleanUp() {
      cache.cleanUp();
    }
  }
}

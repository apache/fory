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

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import org.apache.fory.annotation.Internal;
import org.apache.fory.util.GraalvmSupport;

@Internal
public class ClassValueCache<T> {

  private final Cache<Class<?>, T> cache;

  private ClassValueCache(Cache<Class<?>, T> cache) {
    this.cache = cache;
  }

  public T getIfPresent(Class<?> k) {
    return cache.getIfPresent(k);
  }

  public T get(Class<?> k, Callable<? extends T> loader) {
    try {
      return cache.get(k, loader);
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  public void put(Class<?> k, T v) {
    cache.put(k, v);
  }

  /**
   * Create a cache with weak keys.
   *
   * <p>when in graalvm, the cache is a concurrent hash map. when in jvm, the cache is a weak hash
   * map.
   *
   * @param concurrencyLevel the concurrency level
   * @return the cache
   */
  public static <T> ClassValueCache<T> newClassKeyCache(int concurrencyLevel) {
    if (GraalvmSupport.isGraalBuildtime()) {
      return new ClassValueCache<>(
          CacheBuilder.newBuilder().concurrencyLevel(concurrencyLevel).build());
    } else {
      return new ClassValueCache<>(
          CacheBuilder.newBuilder().weakKeys().concurrencyLevel(concurrencyLevel).build());
    }
  }

  /**
   * Create a cache with weak keys and soft values.
   *
   * <p>when in graalvm, the cache is a concurrent hash map. when in jvm, the cache is a weak hash
   * map.
   *
   * @param concurrencyLevel the concurrency level
   * @return the cache
   */
  public static <T> ClassValueCache<T> newClassKeySoftCache(int concurrencyLevel) {
    if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      return new ClassValueCache<>(
          CacheBuilder.newBuilder().concurrencyLevel(concurrencyLevel).build());
    } else {
      return new ClassValueCache<>(
          CacheBuilder.newBuilder()
              .weakKeys()
              .softValues()
              .concurrencyLevel(concurrencyLevel)
              .build());
    }
  }
}

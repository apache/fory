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

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.MapMaker;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.apache.fory.util.GraalvmSupport;

@SuppressWarnings({"rawtypes", "unchecked"})
public class Collections {
  /**
   * Returns a sequential {@link Stream} of the contents of {@code iterable}, delegating to {@link
   * Collection#stream} if possible.
   */
  public static <T> Stream<T> stream(Iterable<T> iterable) {
    return (iterable instanceof Collection)
        ? ((Collection<T>) iterable).stream()
        : StreamSupport.stream(iterable.spliterator(), false);
  }

  /** Create an {@link ArrayList} from provided elements. */
  public static <T> ArrayList<T> ofArrayList(T e) {
    ArrayList<T> list = new ArrayList(1);
    list.add(e);
    return list;
  }

  /** Create an {@link ArrayList} from provided elements. */
  public static <T> ArrayList<T> ofArrayList(T e1, T e2) {
    ArrayList<T> list = new ArrayList(2);
    list.add(e1);
    list.add(e2);
    return list;
  }

  /** Create an {@link ArrayList} from provided elements. */
  public static <T> ArrayList<T> ofArrayList(T e1, T e2, T e3) {
    ArrayList<T> list = new ArrayList(3);
    list.add(e1);
    list.add(e2);
    list.add(e3);
    return list;
  }

  /** Create an {@link ArrayList} from provided elements. */
  public static <T> ArrayList<T> ofArrayList(T e1, T e2, T e3, T e4) {
    ArrayList<T> list = new ArrayList(4);
    list.add(e1);
    list.add(e2);
    list.add(e3);
    list.add(e4);
    return list;
  }

  /** Create an {@link ArrayList} from provided elements. */
  public static <T> ArrayList<T> ofArrayList(T e1, T e2, T e3, T e4, T e5) {
    ArrayList<T> list = new ArrayList(5);
    list.add(e1);
    list.add(e2);
    list.add(e3);
    list.add(e4);
    list.add(e5);
    return list;
  }

  public static <T> ArrayList<T> ofArrayList(T e1, List<T> items) {
    ArrayList<T> list = new ArrayList(1 + items.size());
    list.add(e1);
    list.addAll(items);
    return list;
  }

  public static <T> ArrayList<T> ofArrayList(T e1, T... items) {
    ArrayList<T> list = new ArrayList(1 + items.length);
    list.add(e1);
    java.util.Collections.addAll(list, items);
    return list;
  }

  public static <T> ArrayList<T> ofArrayList(T e1, T e2, T... items) {
    ArrayList<T> list = new ArrayList(2 + items.length);
    list.add(e1);
    list.add(e2);
    java.util.Collections.addAll(list, items);
    return list;
  }

  public static <E> HashSet<E> ofHashSet(E e) {
    HashSet<E> set = new HashSet<>(1);
    set.add(e);
    return set;
  }

  public static <E> HashSet<E> ofHashSet(E e1, E e2) {
    HashSet<E> set = new HashSet<>(2);
    set.add(e1);
    set.add(e2);
    return set;
  }

  public static <E> HashSet<E> ofHashSet(E e1, E e2, E e3) {
    HashSet<E> set = new HashSet<>(3);
    set.add(e1);
    set.add(e2);
    set.add(e3);
    return set;
  }

  public static <E> HashSet<E> ofHashSet(E e1, E e2, E e3, E e4) {
    HashSet<E> set = new HashSet<>(4);
    set.add(e1);
    set.add(e2);
    set.add(e3);
    set.add(e4);
    return set;
  }

  public static <E> HashSet<E> ofHashSet(E e1, E e2, E e3, E e4, E e5) {
    HashSet<E> set = ofHashSet(e1, e2, e3, e4);
    set.add(e5);
    return set;
  }

  public static <E> HashSet<E> ofHashSet(E e1, E e2, E e3, E e4, E e5, E e6) {
    HashSet<E> set = ofHashSet(e1, e2, e3, e4, e5);
    set.add(e6);
    return set;
  }

  public static <E> HashSet<E> ofHashSet(E e1, E e2, E e3, E e4, E e5, E e6, E e7) {
    HashSet<E> set = ofHashSet(e1, e2, e3, e4, e5, e6);
    set.add(e7);
    return set;
  }

  public static <E> HashSet<E> ofHashSet(E[] elements) {
    HashSet<E> set = new HashSet<>(elements.length);
    java.util.Collections.addAll(set, elements);
    return set;
  }

  /** Return trues if two sets has intersection. */
  public static <E> boolean hasIntersection(Set<E> set1, Set<E> set2) {
    Set<E> small = set1;
    Set<E> large = set2;
    if (set1.size() > set2.size()) {
      small = set2;
      large = set1;
    }
    for (E e : small) {
      if (large.contains(e)) {
        return true;
      }
    }
    return false;
  }

  /** Create a {@link HashMap} from provided kv pairs. */
  public static <K, V> HashMap<K, V> ofHashMap(Object... kv) {
    if (kv == null || kv.length == 0) {
      throw new IllegalArgumentException("entries got no objects, which aren't pairs");
    }
    if ((kv.length & 1) != 0) {
      throw new IllegalArgumentException(
          String.format("entries got %d objects, which aren't pairs", kv.length));
    }
    int size = kv.length >> 1;
    HashMap map = new HashMap<>(size);
    for (int i = 0; i < kv.length; i += 2) {
      map.put(kv[i], kv[i + 1]);
    }
    return map;
  }

  public static <T> Map<Class<?>, T> newClassKeyCacheMap() {
    if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      return new ConcurrentHashMap<>();
    } else {
      return new MapMaker().weakKeys().makeMap();
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
  public static <T> Cache<Class<?>, T> newClassKeySoftCache(int concurrencyLevel) {
    if (GraalvmSupport.isGraalBuildtime()) {
      return CacheBuilder.newBuilder().concurrencyLevel(concurrencyLevel).build();
    } else {
      return CacheBuilder.newBuilder()
          .weakKeys()
          .softValues()
          .concurrencyLevel(concurrencyLevel)
          .build();
    }
  }
}

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

import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Concurrent map implementation supporting weak keys and optional soft values.
 *
 * <p>The implementation only covers the behaviors needed by fory-core.
 */
final class ReferenceConcurrentMap<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V> {
  private final boolean weakKeys;
  private final boolean softValues;
  private final ConcurrentHashMap<Object, Object> map;
  private final ReferenceQueue<K> keyQueue;
  private final ReferenceQueue<V> valueQueue;

  ReferenceConcurrentMap(boolean weakKeys, boolean softValues, int concurrencyLevel) {
    this.weakKeys = weakKeys;
    this.softValues = softValues;
    map = new ConcurrentHashMap<>(Math.max(16, concurrencyLevel));
    keyQueue = weakKeys ? new ReferenceQueue<>() : null;
    valueQueue = softValues ? new ReferenceQueue<>() : null;
  }

  void cleanUp() {
    drainQueues();
  }

  @Override
  public V get(Object key) {
    drainQueues();
    return dereferenceValue(map.get(lookupKey(key)));
  }

  @Override
  public V put(K key, V value) {
    Objects.requireNonNull(key);
    Objects.requireNonNull(value);
    drainQueues();
    Object mapKey = storedKey(key);
    return dereferenceValue(map.put(mapKey, storedValue(value, mapKey)));
  }

  @Override
  public V putIfAbsent(K key, V value) {
    Objects.requireNonNull(key);
    Objects.requireNonNull(value);
    while (true) {
      drainQueues();
      Object lookupKey = lookupKey(key);
      Object current = map.get(lookupKey);
      V currentValue = dereferenceValue(current);
      if (currentValue != null) {
        return currentValue;
      }
      Object mapKey = storedKey(key);
      Object newValue = storedValue(value, mapKey);
      if (current == null) {
        Object existing = map.putIfAbsent(mapKey, newValue);
        if (existing == null) {
          return null;
        }
        current = existing;
      } else if (map.replace(lookupKey, current, newValue)) {
        return null;
      }
    }
  }

  @Override
  public V remove(Object key) {
    drainQueues();
    return dereferenceValue(map.remove(lookupKey(key)));
  }

  @Override
  public boolean remove(Object key, Object value) {
    drainQueues();
    Object lookupKey = lookupKey(key);
    Object current = map.get(lookupKey);
    if (current == null) {
      return false;
    }
    V currentValue = dereferenceValue(current);
    return currentValue != null
        && Objects.equals(currentValue, value)
        && map.remove(lookupKey, current);
  }

  @Override
  public V replace(K key, V value) {
    Objects.requireNonNull(key);
    Objects.requireNonNull(value);
    while (true) {
      drainQueues();
      Object lookupKey = lookupKey(key);
      Object current = map.get(lookupKey);
      if (current == null) {
        return null;
      }
      V currentValue = dereferenceValue(current);
      if (currentValue == null) {
        continue;
      }
      Object mapKey = storedKey(key);
      if (map.replace(lookupKey, current, storedValue(value, mapKey))) {
        return currentValue;
      }
    }
  }

  @Override
  public boolean replace(K key, V oldValue, V newValue) {
    Objects.requireNonNull(key);
    Objects.requireNonNull(oldValue);
    Objects.requireNonNull(newValue);
    while (true) {
      drainQueues();
      Object lookupKey = lookupKey(key);
      Object current = map.get(lookupKey);
      if (current == null) {
        return false;
      }
      V currentValue = dereferenceValue(current);
      if (currentValue == null) {
        continue;
      }
      if (!Objects.equals(currentValue, oldValue)) {
        return false;
      }
      Object mapKey = storedKey(key);
      if (map.replace(lookupKey, current, storedValue(newValue, mapKey))) {
        return true;
      }
    }
  }

  @Override
  public boolean containsKey(Object key) {
    return get(key) != null;
  }

  @Override
  public void clear() {
    map.clear();
    cleanUp();
  }

  @Override
  public int size() {
    drainQueues();
    return map.size();
  }

  @Override
  public boolean isEmpty() {
    drainQueues();
    return map.isEmpty();
  }

  @Override
  public Set<Map.Entry<K, V>> entrySet() {
    drainQueues();
    Set<Map.Entry<K, V>> entries = new HashSet<>();
    for (Map.Entry<Object, Object> entry : map.entrySet()) {
      K key = dereferenceKey(entry.getKey());
      V value = dereferenceValue(entry.getValue());
      if (key != null && value != null) {
        entries.add(new SimpleImmutableEntry<>(key, value));
      }
    }
    return new EntrySet(entries);
  }

  private void drainQueues() {
    if (weakKeys) {
      WeakKeyReference<K> ref;
      while ((ref = (WeakKeyReference<K>) keyQueue.poll()) != null) {
        map.remove(ref);
      }
    }
    if (softValues) {
      SoftValueReference<V> ref;
      while ((ref = (SoftValueReference<V>) valueQueue.poll()) != null) {
        map.remove(ref.mapKey, ref);
      }
    }
  }

  private Object lookupKey(Object key) {
    Objects.requireNonNull(key);
    return weakKeys ? new LookupKey<>(key) : key;
  }

  private Object storedKey(K key) {
    return weakKeys ? new WeakKeyReference<>(key, keyQueue) : key;
  }

  private Object storedValue(V value, Object mapKey) {
    return softValues ? new SoftValueReference<>(value, mapKey, valueQueue) : value;
  }

  @SuppressWarnings("unchecked")
  private K dereferenceKey(Object mapKey) {
    if (!weakKeys) {
      return (K) mapKey;
    }
    return ((WeakKeyReference<K>) mapKey).get();
  }

  @SuppressWarnings("unchecked")
  private V dereferenceValue(Object storedValue) {
    if (storedValue == null) {
      return null;
    }
    if (!softValues) {
      return (V) storedValue;
    }
    SoftValueReference<V> ref = (SoftValueReference<V>) storedValue;
    V value = ref.get();
    if (value == null) {
      map.remove(ref.mapKey, ref);
    }
    return value;
  }

  private static final class EntrySet<K, V> extends AbstractSet<Map.Entry<K, V>> {
    private final Set<Map.Entry<K, V>> entries;

    private EntrySet(Set<Map.Entry<K, V>> entries) {
      this.entries = entries;
    }

    @Override
    public Iterator<Map.Entry<K, V>> iterator() {
      return entries.iterator();
    }

    @Override
    public int size() {
      return entries.size();
    }
  }

  private static final class LookupKey<K> {
    private final K key;
    private final int hashCode;

    private LookupKey(K key) {
      this.key = key;
      hashCode = key.hashCode();
    }

    @Override
    public boolean equals(Object other) {
      if (other instanceof LookupKey) {
        return key.equals(((LookupKey<?>) other).key);
      }
      if (other instanceof WeakKeyReference) {
        return key.equals(((WeakKeyReference<?>) other).get());
      }
      return key.equals(other);
    }

    @Override
    public int hashCode() {
      return hashCode;
    }
  }

  private static final class WeakKeyReference<K> extends WeakReference<K> {
    private final int hashCode;

    private WeakKeyReference(K key, ReferenceQueue<K> queue) {
      super(key, queue);
      hashCode = key.hashCode();
    }

    @Override
    public boolean equals(Object other) {
      K key = get();
      if (key == null) {
        return false;
      }
      if (other instanceof LookupKey) {
        return key.equals(((LookupKey<?>) other).key);
      }
      if (other instanceof WeakKeyReference) {
        return key.equals(((WeakKeyReference<?>) other).get());
      }
      return key.equals(other);
    }

    @Override
    public int hashCode() {
      return hashCode;
    }
  }

  private static final class SoftValueReference<V> extends SoftReference<V> {
    private final Object mapKey;

    private SoftValueReference(V value, Object mapKey, ReferenceQueue<V> queue) {
      super(value, queue);
      this.mapKey = mapKey;
    }
  }
}

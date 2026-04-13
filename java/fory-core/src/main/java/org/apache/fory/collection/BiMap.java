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

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/** Minimal bidirectional map backed by caller-provided forward and reverse maps. */
public final class BiMap<K, V> {
  private final Map<K, V> valuesByKey;
  private final Map<V, K> keysByValue;
  private final Inverse inverse = new Inverse();

  public BiMap(Map<K, V> valuesByKey, Map<V, K> keysByValue) {
    this.valuesByKey = valuesByKey;
    this.keysByValue = keysByValue;
  }

  public static <K, V> BiMap<K, V> newHashIdentityBiMap(int expectedSize) {
    return new BiMap<>(new HashMap<>(expectedSize), new IdentityHashMap<>(expectedSize));
  }

  public V get(K key) {
    return valuesByKey.get(key);
  }

  public boolean containsKey(K key) {
    return valuesByKey.containsKey(key);
  }

  public void put(K key, V value) {
    valuesByKey.put(key, value);
    keysByValue.put(value, key);
  }

  public Inverse inverse() {
    return inverse;
  }

  public final class Inverse {
    private Inverse() {}

    public boolean containsKey(V value) {
      return keysByValue.containsKey(value);
    }

    public K get(V value) {
      return keysByValue.get(value);
    }
  }
}

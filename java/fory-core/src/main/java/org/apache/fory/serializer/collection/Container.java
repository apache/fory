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

package org.apache.fory.serializer.collection;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Set;
import org.apache.fory.exception.DeserializationException;

class Container {}

/** A collection container to hold collection elements by array. */
class CollectionContainer<T> extends AbstractCollection<T> {
  final Object[] elements;
  int size;

  public CollectionContainer(int capacity) {
    elements = new Object[capacity];
  }

  @Override
  public Iterator<T> iterator() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  public boolean add(Object o) {
    elements[size++] = o;
    return true;
  }
}

/** A sorted collection container to hold collection elements and comparator. */
class SortedCollectionContainer<T> extends CollectionContainer<T> {
  Comparator<T> comparator;

  public SortedCollectionContainer(Comparator<T> comparator, int capacity) {
    super(capacity);
    this.comparator = comparator;
  }
}

/** A map container to hold map key and value elements by arrays. */
class MapContainer<K, V> extends AbstractMap<K, V> {
  final Object[] keyArray;
  final Object[] valueArray;
  int size;

  public MapContainer(int capacity) {
    keyArray = new Object[capacity];
    valueArray = new Object[capacity];
  }

  @Override
  public Set<Entry<K, V>> entrySet() {
    throw new UnsupportedOperationException();
  }

  @Override
  public V put(K key, V value) {
    keyArray[size] = key;
    valueArray[size++] = value;
    return null;
  }
}

/** A sorted map container to hold map data and comparator. */
class SortedMapContainer<K, V> extends MapContainer<K, V> {

  final Comparator<K> comparator;

  public SortedMapContainer(Comparator<K> comparator, int capacity) {
    super(capacity);
    this.comparator = comparator;
  }
}

/** A map container to hold map key and value elements in one array. */
class JDKImmutableMapContainer<K, V> extends AbstractMap<K, V> {
  final Object[] array;
  private int offset;

  JDKImmutableMapContainer(int mapCapacity) {
    if (mapCapacity > (Integer.MAX_VALUE >>> 1)) {
      throw new DeserializationException(
          "Immutable map size exceeds internal array capacity: " + mapCapacity);
    }
    array = new Object[mapCapacity << 1];
  }

  @Override
  public Set<Entry<K, V>> entrySet() {
    throw new UnsupportedOperationException();
  }

  @Override
  public V put(K key, V value) {
    array[offset++] = key;
    array[offset++] = value;
    return null;
  }

  public int size() {
    return offset >> 1;
  }
}

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

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Objects;
import java.util.RandomAccess;
import org.apache.fory.type.unsigned.Uint64;

/**
 * Resizable list backed by a long array for unsigned 64-bit values.
 *
 * <p>Supports auto-growing on insertions, primitive overloads to avoid boxing, and direct access to
 * the backing array for zero-copy interop. Prefer primitive get/set/add to avoid boxing cost;
 * elements are always non-null. The {@link #size()} tracks the logical element count while the
 * backing array capacity may be larger.
 */
public final class Uint64List extends AbstractList<Uint64> implements RandomAccess {
  private static final int DEFAULT_CAPACITY = 10;

  private long[] array;
  private int size;

  /** Creates an empty list with default capacity. */
  public Uint64List() {
    this(DEFAULT_CAPACITY);
  }

  /**
   * Creates an empty list with a given initial capacity.
   *
   * @param initialCapacity starting backing array length; must be non-negative
   * @throws IllegalArgumentException if {@code initialCapacity} is negative
   */
  public Uint64List(int initialCapacity) {
    if (initialCapacity < 0) {
      throw new IllegalArgumentException("Illegal capacity: " + initialCapacity);
    }
    this.array = new long[initialCapacity];
    this.size = 0;
  }

  /**
   * Wraps an existing array as the backing storage.
   *
   * @param array source array; its current length becomes {@link #size()}
   */
  public Uint64List(long[] array) {
    this.array = array;
    this.size = array.length;
  }

  @Override
  public Uint64 get(int index) {
    checkIndex(index);
    return new Uint64(array[index]);
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  public Uint64 set(int index, Uint64 element) {
    checkIndex(index);
    Objects.requireNonNull(element, "element");
    long prev = array[index];
    array[index] = element.longValue();
    return new Uint64(prev);
  }

  /** Sets a value without boxing. */
  public void set(int index, long value) {
    checkIndex(index);
    array[index] = value;
  }

  @Override
  public void add(int index, Uint64 element) {
    checkPositionIndex(index);
    ensureCapacity(size + 1);
    System.arraycopy(array, index, array, index + 1, size - index);
    array[index] = element.longValue();
    size++;
    modCount++;
  }

  @Override
  public boolean add(Uint64 element) {
    Objects.requireNonNull(element, "element");
    ensureCapacity(size + 1);
    array[size++] = element.longValue();
    modCount++;
    return true;
  }

  /** Appends a value without boxing. */
  public boolean add(long value) {
    ensureCapacity(size + 1);
    array[size++] = value;
    modCount++;
    return true;
  }

  public long getLong(int index) {
    checkIndex(index);
    return array[index];
  }

  /**
   * Returns {@code true} if this list is backed by an accessible heap array.
   *
   * <p>If this method returns {@code true}, then {@link #getArray()} may be safely called to obtain
   * the underlying storage without incurring a data copy.
   *
   * @return {@code true} if this list is backed by a primitive array and is not read-only
   */
  public boolean hasArray() {
    return array != null;
  }

  /**
   * Returns the underlying heap array that backs this list.
   *
   * <p><b>Warning:</b> Users should check {@link #hasArray()} before calling this method. If {@code
   * hasArray()} returns {@code false}, this method will return a copy that does not reflect
   * subsequent mutations.
   *
   * <p>Modifying the returned array will directly affect the list, and vice versa. Elements beyond
   * {@link #size()} are undefined.
   *
   * @return the backing array
   * @throws UnsupportedOperationException if this list is not backed by an accessible heap array
   */
  public long[] getArray() {
    return array;
  }

  /** Returns a trimmed copy containing exactly {@code size()} elements. */
  public long[] copyArray() {
    return Arrays.copyOf(array, size);
  }

  private void ensureCapacity(int minCapacity) {
    if (array.length >= minCapacity) {
      return;
    }
    int newCapacity = array.length + (array.length >> 1) + 1;
    if (newCapacity < minCapacity) {
      newCapacity = minCapacity;
    }
    array = Arrays.copyOf(array, newCapacity);
  }

  private void checkIndex(int index) {
    if (index < 0 || index >= size) {
      throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
    }
  }

  private void checkPositionIndex(int index) {
    if (index < 0 || index > size) {
      throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
    }
  }
}

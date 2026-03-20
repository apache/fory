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

import java.util.Arrays;

/**
 * An auto-growing array which avoid checks in {@code ArrayList} and faster for {@code clear}
 * method.
 */
@SuppressWarnings("unchecked")
public final class ObjectArray<T> {
  private static final int COPY_THRESHOLD = 128;
  private static final int NIL_ARRAY_SIZE = 1024;
  private static final Object[] NIL_ARRAY = new Object[NIL_ARRAY_SIZE];

  public Object[] objects;
  public int size;

  public ObjectArray() {
    this(0);
  }

  public ObjectArray(int initialCapacity) {
    objects = new Object[initialCapacity];
  }

  public void add(T element) {
    Object[] objects = this.objects;
    int size = this.size++;
    if (objects.length <= size) {
      Object[] tmp = new Object[(size + 1) * 2]; // `size + 1` to avoid `size == 0`
      System.arraycopy(objects, 0, tmp, 0, objects.length);
      objects = tmp;
      this.objects = tmp;
    }
    objects[size] = element;
  }

  public void set(int index, T element) {
    objects[index] = element;
  }

  public T get(int index) {
    return (T) objects[index];
  }

  /** Returns tail item or null if no available item in the array. */
  public T popOrNull() {
    int size = this.size;
    if (size == 0) {
      return null;
    }
    this.size = --size;
    return (T) objects[size];
  }

  public void clear() {
    clearObjectArray(this.objects, 0, this.size);
    this.size = 0;
  }

  public void clearApproximate(int maximumCapacity) {
    if (objects.length < maximumCapacity) {
      clear();
    } else {
      size = 0;
      objects = new Object[maximumCapacity];
    }
  }

  public int size() {
    return size;
  }

  public boolean isEmpty() {
    return size == 0;
  }

  /**
   * Set all object array elements to null. This method is faster than {@link Arrays#fill} for large
   * arrays ({@code > 128}).
   */
  public static void clearObjectArray(Object[] objects, int start, int size) {
    if (size < COPY_THRESHOLD) {
      Arrays.fill(objects, start, start + size, null);
    } else {
      if (size < NIL_ARRAY_SIZE) {
        System.arraycopy(NIL_ARRAY, 0, objects, start, size);
      } else {
        while (size > NIL_ARRAY_SIZE) {
          System.arraycopy(NIL_ARRAY, 0, objects, start, NIL_ARRAY_SIZE);
          size -= NIL_ARRAY_SIZE;
          start += NIL_ARRAY_SIZE;
        }
        System.arraycopy(NIL_ARRAY, 0, objects, start, size);
      }
    }
  }

  @Override
  public String toString() {
    if (size == 0) {
      return "[]";
    }
    return Arrays.asList(objects).subList(0, size).toString();
  }
}

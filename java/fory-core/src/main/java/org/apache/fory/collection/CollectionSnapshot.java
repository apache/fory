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
import java.util.Collection;
import java.util.Iterator;
import org.apache.fory.annotation.Internal;

/**
 * A specialized collection implementation that creates a snapshot of elements for single iteration.
 * This class is designed to efficiently handle concurrent collection serialization by creating a
 * lightweight snapshot that can be iterated without holding references to the original collection
 * elements.
 *
 * <p>The implementation uses an array-based storage for elements and provides optimized iteration
 * through a custom iterator. It includes memory management features such as automatic array
 * reallocation when clearing large collections to prevent memory leaks.
 *
 * <p><strong>Important:</strong> The returned iterator from {@link #iterator()} must be consumed
 * completely before calling {@code iterator()} again. The iterator maintains its position through a
 * shared index, and calling {@code iterator()} again before the previous iterator is fully consumed
 * will result in incorrect iteration behavior.
 *
 * <p>This class is marked as {@code @Internal} and should not be used directly by application code.
 * It's specifically designed for internal serialization purposes.
 *
 * @param <E> the type of elements maintained by this collection
 * @since 1.0
 */
@Internal
public class CollectionSnapshot<E> extends AbstractList<E> {
  /** Threshold for array reallocation during clear operation to prevent memory leaks. */
  private static final int CLEAR_ARRAY_SIZE_THRESHOLD = 2048;

  /** Array storing the collection elements for iteration. */
  ObjectArray<E> array;

  /** Reference to the original collection (used for snapshot creation). */
  Collection<E> collection;

  /** Current number of elements in the snapshot. */
  int size;

  /** Current iteration index for the iterator. */
  int iterIndex;

  /** Cached iterator for this collection. */
  private final CollectionIterator iterator;

  /**
   * Constructs a new empty Snapshot. Initializes the internal array with a default capacity of 16
   * elements and creates the iterator instance.
   */
  public CollectionSnapshot() {
    array = new ObjectArray<>(16);
    iterator = new CollectionIterator();
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  public E get(int index) {
    return array.get(index);
  }

  /**
   * Returns an iterator over the elements in this collection. The iterator is designed for
   * single-pass iteration and maintains its position through a shared index.
   *
   * <p><strong>Important:</strong> The returned iterator must be consumed completely before calling
   * this method again. The iterator shares its position with other potential iterators through the
   * {@code iterIndex} field, and calling {@code iterator()} again before the current iterator is
   * fully consumed will result in incorrect iteration behavior.
   *
   * @return an iterator over the elements in this collection
   */
  @Override
  public Iterator<E> iterator() {
    iterIndex = 0;
    return iterator;
  }

  /**
   * Iterator implementation for the CollectionSnapshot. This iterator is designed for single-pass
   * iteration and maintains its position through the iterIndex field. It provides efficient access
   * to collection elements stored in the underlying array.
   *
   * <p><strong>Important:</strong> This iterator must be consumed completely before calling {@code
   * iterator()} again on the collection. The iterator shares its position with other potential
   * iterators through the {@code iterIndex} field, and calling {@code iterator()} again before the
   * current iterator is fully consumed will result in incorrect iteration behavior.
   */
  class CollectionIterator implements Iterator<E> {

    @Override
    public boolean hasNext() {
      return iterIndex < size;
    }

    @Override
    public E next() {
      return array.get(iterIndex++);
    }
  }

  /**
   * Creates a snapshot of the specified collection by copying all its elements into the internal
   * array. This method is used to create a stable view of a concurrent collection for serialization
   * purposes.
   *
   * <p>The method iterates through all elements in the provided collection and adds them to the
   * internal array. The iteration index is reset to 0 to prepare for subsequent iteration.
   *
   * @param collection the collection to create a snapshot of
   */
  public void setCollection(Collection<E> collection) {
    ObjectArray<E> array = this.array;
    int size = 0;
    for (E element : collection) {
      array.add(element);
      size++;
    }
    this.size = size;
  }

  @Override
  public void clear() {
    if (size > CLEAR_ARRAY_SIZE_THRESHOLD) {
      array = new ObjectArray<>(16);
    } else {
      array.clear();
    }
    iterIndex = 0;
    size = 0;
  }
}

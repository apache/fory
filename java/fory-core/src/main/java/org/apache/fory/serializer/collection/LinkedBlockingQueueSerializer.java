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

import java.util.concurrent.LinkedBlockingQueue;
import org.apache.fory.Fory;
import org.apache.fory.memory.MemoryBuffer;

/**
 * High-performance serializer for {@link LinkedBlockingQueue}.
 *
 * <p>This serializer avoids the deadlock issue that occurs with ObjectStreamSerializer when
 * serializing LinkedBlockingQueue. The LinkedBlockingQueue.readObject() method internally calls
 * add(), which requires acquiring locks. If locks are serialized/deserialized, they may be in an
 * incorrect state causing deadlock.
 *
 * <p>This serializer only serializes the queue's capacity and elements, and reconstructs the queue
 * with fresh locks during deserialization, avoiding the lock state issue entirely.
 *
 * <p>Performance characteristics:
 *
 * <ul>
 *   <li>Serialization: O(n) where n is the number of elements
 *   <li>Deserialization: O(n) with optimized element addition
 *   <li>Memory: Compact representation - only capacity + elements
 *   <li>Thread-safe: Properly handles concurrent access during serialization
 * </ul>
 *
 * @param <T> Element type in the queue
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class LinkedBlockingQueueSerializer<T>
    extends CollectionSerializer<LinkedBlockingQueue<T>> {

  public LinkedBlockingQueueSerializer(Fory fory, Class<LinkedBlockingQueue<T>> cls) {
    super(fory, cls, true);
  }

  @Override
  public void write(MemoryBuffer buffer, LinkedBlockingQueue<T> queue) {
    // Write capacity first
    // LinkedBlockingQueue capacity calculation: capacity = size + remainingCapacity
    int size = queue.size();
    int remainingCapacity = queue.remainingCapacity();
    int capacity = size + remainingCapacity;

    // Write capacity (use var encoding for space efficiency)
    buffer.writeVarUint32Small7(capacity);

    // Write number of elements
    buffer.writeVarUint32Small7(size);

    // Write elements
    // We convert to array to avoid ConcurrentModificationException
    // and to ensure consistent snapshot of queue state
    Object[] elements = queue.toArray();
    for (Object element : elements) {
      fory.writeRef(buffer, element);
    }
  }

  @Override
  public LinkedBlockingQueue<T> read(MemoryBuffer buffer) {
    // Read capacity
    int capacity = buffer.readVarUint32Small7();

    // Read number of elements
    int numElements = buffer.readVarUint32Small7();

    // Create new queue with specified capacity
    // This ensures fresh locks are created
    LinkedBlockingQueue<T> queue = new LinkedBlockingQueue<>(capacity);

    // Register for reference tracking before reading elements
    fory.getRefResolver().reference(queue);

    // Read and add elements
    // Using add() is safe here because we created a fresh queue with new locks
    for (int i = 0; i < numElements; i++) {
      T element = (T) fory.readRef(buffer);
      queue.add(element);
    }

    return queue;
  }

  @Override
  public LinkedBlockingQueue<T> copy(LinkedBlockingQueue<T> originQueue) {
    // Create new queue with same capacity
    int size = originQueue.size();
    int capacity = size + originQueue.remainingCapacity();
    LinkedBlockingQueue<T> newQueue = new LinkedBlockingQueue<>(capacity);

    if (needToCopyRef) {
      fory.reference(originQueue, newQueue);
    }

    // Copy elements
    // Convert to array to get consistent snapshot
    Object[] elements = originQueue.toArray();
    for (Object element : elements) {
      T copiedElement = element != null ? (T) fory.copy(element) : null;
      newQueue.add(copiedElement);
    }

    return newQueue;
  }

  @Override
  public LinkedBlockingQueue<T> newCollection(MemoryBuffer buffer) {
    // This method is called by parent class but we override read() completely
    // so this is not used in our implementation
    throw new UnsupportedOperationException(
        "LinkedBlockingQueueSerializer uses custom read() implementation");
  }

  @Override
  public void xwrite(MemoryBuffer buffer, LinkedBlockingQueue<T> value) {
    // For cross-language serialization, use the same efficient format
    write(buffer, value);
  }

  @Override
  public LinkedBlockingQueue<T> xread(MemoryBuffer buffer) {
    // For cross-language deserialization, use the same efficient format
    return read(buffer);
  }
}

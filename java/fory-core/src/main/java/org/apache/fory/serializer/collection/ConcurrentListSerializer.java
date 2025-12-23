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

import java.util.Collection;
import java.util.List;
import org.apache.fory.Fory;
import org.apache.fory.collection.ListSnapshot;
import org.apache.fory.collection.ObjectArray;
import org.apache.fory.memory.MemoryBuffer;

/**
 * Serializer for concurrent list implementations that require thread-safe serialization.
 *
 * <p>This serializer extends {@link CollectionSerializer} to provide specialized handling for
 * concurrent lists such as {@link java.util.concurrent.CopyOnWriteArrayList}. The key feature is
 * the use of {@link ListSnapshot} to create stable snapshots of concurrent lists during
 * serialization, avoiding potential {@link java.util.ConcurrentModificationException} and ensuring
 * thread safety.
 *
 * <p>The serializer maintains a pool of reusable {@link ListSnapshot} instances to minimize object
 * allocation overhead during serialization.
 *
 * <p>This implementation is particularly important for concurrent lists because:
 *
 * <ul>
 *   <li>Concurrent lists can be modified during iteration, causing exceptions
 *   <li>Creating snapshots ensures consistent serialization state
 *   <li>Object pooling reduces garbage collection pressure
 * </ul>
 *
 * @param <T> the type of concurrent list being serialized
 * @since 1.0
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class ConcurrentListSerializer<T extends List> extends CollectionSerializer<T> {

  /** Pool of reusable ListSnapshot instances for efficient serialization. */
  protected final ObjectArray<ListSnapshot> snapshots = new ObjectArray<>(1);

  /**
   * Constructs a new ConcurrentListSerializer for the specified concurrent list type.
   *
   * @param fory the Fory instance for serialization context
   * @param type the class type of the concurrent list to serialize
   * @param supportCodegen whether code generation is supported for this serializer
   */
  public ConcurrentListSerializer(Fory fory, Class<T> type, boolean supportCodegen) {
    super(fory, type, supportCodegen);
  }

  /**
   * Creates a snapshot of the concurrent list for safe serialization.
   *
   * <p>This method retrieves a reusable {@link ListSnapshot} from the pool, or creates a new one if
   * none are available. It then creates a snapshot of the concurrent list to avoid concurrent
   * modification issues during serialization. The list size is written to the buffer before
   * returning the snapshot.
   *
   * @param buffer the memory buffer to write serialization data to
   * @param value the concurrent collection to serialize
   * @return a snapshot of the collection for safe iteration during serialization
   */
  @Override
  public Collection onCollectionWrite(MemoryBuffer buffer, T value) {
    ListSnapshot snapshot = snapshots.popOrNull();
    if (snapshot == null) {
      snapshot = new ListSnapshot();
    }
    snapshot.setList(value);
    buffer.writeVarUint32Small7(snapshot.size());
    return snapshot;
  }

  /**
   * Cleans up the snapshot after serialization and returns it to the pool for reuse.
   *
   * <p>This method is called after the list serialization is complete. It clears the snapshot to
   * remove all references to the serialized data and returns the snapshot instance to the pool for
   * future reuse, improving memory efficiency.
   *
   * @param list the snapshot that was used for serialization
   */
  @Override
  public void onCollectionWriteFinish(Collection list) {
    ListSnapshot snapshot = (ListSnapshot) list;
    snapshot.clear();
    snapshots.add(snapshot);
  }
}

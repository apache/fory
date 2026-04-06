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

import java.util.Map;
import org.apache.fory.collection.MapSnapshot;
import org.apache.fory.collection.ObjectArray;
import org.apache.fory.context.WriteContext;
import org.apache.fory.resolver.TypeResolver;

/**
 * Serializer for concurrent map implementations that require thread-safe serialization.
 *
 * <p>This serializer extends {@link MapSerializer} to provide specialized handling for concurrent
 * maps such as {@link java.util.concurrent.ConcurrentHashMap}. The key feature is the use of {@link
 * MapSnapshot} to create stable snapshots of concurrent maps during serialization, avoiding
 * potential {@link java.util.ConcurrentModificationException} and ensuring thread safety.
 *
 * <p>The serializer maintains a pool of reusable {@link MapSnapshot} instances to minimize object
 * allocation overhead during serialization.
 *
 * <p>This implementation is particularly important for concurrent maps because:
 *
 * <ul>
 *   <li>Concurrent maps can be modified during iteration, causing exceptions
 *   <li>Creating snapshots ensures consistent serialization state
 *   <li>Object pooling reduces garbage collection pressure
 * </ul>
 *
 * @param <T> the type of concurrent map being serialized
 * @since 1.0
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class ConcurrentMapSerializer<T extends Map> extends MapSerializer<T> {
  /** Pool of reusable MapSnapshot instances for efficient serialization. */
  protected final ObjectArray<MapSnapshot> snapshots = new ObjectArray<>(1);

  /**
   * Constructs a new ConcurrentMapSerializer for the specified concurrent map type.
   *
   * @param typeResolver the resolver used for nested type handling
   * @param type the class type of the concurrent map to serialize
   * @param supportCodegen whether code generation is supported for this serializer
   */
  public ConcurrentMapSerializer(TypeResolver typeResolver, Class<T> type, boolean supportCodegen) {
    super(typeResolver, type, supportCodegen);
  }

  /**
   * Creates a snapshot of the concurrent map for safe serialization.
   *
   * <p>This method retrieves a reusable {@link MapSnapshot} from the pool, or creates a new one if
   * none are available. It then creates a snapshot of the concurrent map to avoid concurrent
   * modification issues during serialization. The map size is written to the buffer before
   * returning the snapshot.
   *
   * @param value the concurrent map to serialize
   * @return a snapshot of the map for safe iteration during serialization
   */
  @Override
  public MapSnapshot onMapWrite(WriteContext writeContext, T value) {
    MapSnapshot snapshot = snapshots.popOrNull();
    if (snapshot == null) {
      snapshot = new MapSnapshot();
    }
    snapshot.setMap(value);
    writeContext.getBuffer().writeVarUint32Small7(snapshot.size());
    return snapshot;
  }

  /**
   * Cleans up the snapshot after serialization and returns it to the pool for reuse.
   *
   * <p>This method is called after the map serialization is complete. It clears the snapshot to
   * remove all references to the serialized data and returns the snapshot instance to the pool for
   * future reuse, improving memory efficiency.
   *
   * @param map the snapshot that was used for serialization
   */
  @Override
  public void onMapWriteFinish(Map map) {
    MapSnapshot snapshot = (MapSnapshot) map;
    snapshot.clear();
    snapshots.add(snapshot);
  }
}

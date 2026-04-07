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

package org.apache.fory.context;

import org.apache.fory.Fory;
import org.apache.fory.collection.IdentityObjectIntMap;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.util.Preconditions;

/**
 * Default {@link RefWriter} implementation backed by an identity map.
 *
 * <p>Each distinct object written during one operation is assigned a dense ref id in encounter
 * order. Later visits to the same instance emit {@link Fory#REF_FLAG} instead of rewriting the
 * payload.
 */
public final class MapRefWriter implements RefWriter {
  private static final boolean ENABLE_FORY_REF_PROFILING =
      "true".equalsIgnoreCase(System.getProperty("fory.enable_ref_profiling"));
  private static final int DEFAULT_MAP_CAPACITY = 3;

  private long writeCounter;
  private long writeTotalObjectSize = 0;
  private final IdentityObjectIntMap<Object> writtenObjects;

  /**
   * Creates a writer with the given identity-map load factor.
   *
   * <p>The runtime uses this to tune ref-table behavior for different workloads.
   */
  public MapRefWriter(float loadFactor) {
    writtenObjects = new IdentityObjectIntMap<>(DEFAULT_MAP_CAPACITY, loadFactor);
  }

  /** Writes a ref-or-null header and records a fresh ref id for new non-null objects. */
  @Override
  public boolean writeRefOrNull(MemoryBuffer buffer, Object obj) {
    buffer.grow(10);
    if (obj == null) {
      buffer._unsafeWriteByte(Fory.NULL_FLAG);
      return true;
    }
    int newWriteRefId = writtenObjects.size;
    int writtenRefId =
        ENABLE_FORY_REF_PROFILING
            ? writtenObjects.profilingPutOrGet(obj, newWriteRefId)
            : writtenObjects.putOrGet(obj, newWriteRefId);
    if (writtenRefId >= 0) {
      buffer._unsafeWriteByte(Fory.REF_FLAG);
      buffer._unsafeWriteVarUint32(writtenRefId);
      return true;
    }
    buffer._unsafeWriteByte(Fory.REF_VALUE_FLAG);
    return false;
  }

  /** Writes a ref-or-value header for a non-null object. */
  @Override
  public boolean writeRefValueFlag(MemoryBuffer buffer, Object obj) {
    assert obj != null;
    buffer.grow(10);
    int newWriteRefId = writtenObjects.size;
    int writtenRefId =
        ENABLE_FORY_REF_PROFILING
            ? writtenObjects.profilingPutOrGet(obj, newWriteRefId)
            : writtenObjects.putOrGet(obj, newWriteRefId);
    if (writtenRefId >= 0) {
      buffer._unsafeWriteByte(Fory.REF_FLAG);
      buffer._unsafeWriteVarUint32(writtenRefId);
      return false;
    }
    buffer._unsafeWriteByte(Fory.REF_VALUE_FLAG);
    return true;
  }

  /** Writes a null header when needed and otherwise leaves the buffer unchanged. */
  @Override
  public boolean writeNullFlag(MemoryBuffer buffer, Object obj) {
    if (obj == null) {
      buffer._unsafeWriteByte(Fory.NULL_FLAG);
      return true;
    }
    return false;
  }

  /** Rebinds the ref id for {@code original} to the already assigned id of {@code newObject}. */
  @Override
  public void replaceRef(Object original, Object newObject) {
    int newObjectId = writtenObjects.get(newObject, -1);
    Preconditions.checkArgument(newObjectId != -1);
    writtenObjects.put(original, newObjectId);
  }

  /** Clears the current write state and keeps an approximate capacity for the next operation. */
  @Override
  public void reset() {
    long totalObjectSize = this.writeTotalObjectSize + writtenObjects.size;
    long counter = this.writeCounter + 1;
    if (counter < 0 || totalObjectSize < 0) {
      counter = 1;
      totalObjectSize = writtenObjects.size;
    }
    this.writeCounter = counter;
    this.writeTotalObjectSize = totalObjectSize;
    int avg = (int) (totalObjectSize / counter);
    if (avg <= DEFAULT_MAP_CAPACITY) {
      avg = DEFAULT_MAP_CAPACITY;
    }
    writtenObjects.clearApproximate(avg);
  }
}

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
import org.apache.fory.collection.IntArray;
import org.apache.fory.collection.ObjectArray;
import org.apache.fory.memory.MemoryBuffer;

/**
 * Default {@link RefReader} implementation backed by indexed arrays.
 *
 * <p>Read reference ids are assigned densely as objects appear in the input stream. The resolved
 * object instances are stored in {@link #readObjects}, while {@link #readRefIds} tracks ids that
 * have been reserved but not yet bound to concrete instances.
 */
public final class MapRefReader implements RefReader {
  private static final int DEFAULT_ARRAY_CAPACITY = 3;

  private long readCounter;
  private long readTotalObjectSize = 0;
  private final ObjectArray readObjects = new ObjectArray(DEFAULT_ARRAY_CAPACITY);
  private final IntArray readRefIds = new IntArray(DEFAULT_ARRAY_CAPACITY);
  private Object readObject;

  /** Reads a ref-or-null header and resolves cached references immediately when present. */
  @Override
  public byte readRefOrNull(MemoryBuffer buffer) {
    byte headFlag = buffer.readByte();
    if (headFlag == Fory.REF_FLAG) {
      readObject = getReadRef(buffer.readVarUInt32Small14());
    } else {
      readObject = null;
    }
    return headFlag;
  }

  /** Reserves the next dense read ref id for an object that is about to be materialized. */
  @Override
  public int preserveRefId() {
    int nextReadRefId = readObjects.size();
    readObjects.add(null);
    readRefIds.add(nextReadRefId);
    return nextReadRefId;
  }

  /** Pushes an already known ref id for later binding. */
  @Override
  public int preserveRefId(int refId) {
    readRefIds.add(refId);
    return refId;
  }

  /** Reads a ref/value header and reserves a new id only for fresh values. */
  @Override
  public int tryPreserveRefId(MemoryBuffer buffer) {
    byte headFlag = buffer.readByte();
    if (headFlag == Fory.REF_FLAG) {
      readObject = getReadRef(buffer.readVarUInt32Small14());
    } else {
      readObject = null;
      if (headFlag == Fory.REF_VALUE_FLAG) {
        return preserveRefId();
      }
    }
    return headFlag;
  }

  /**
   * Returns the last ref id reserved by {@link #preserveRefId()} or {@link #preserveRefId(int)}.
   */
  @Override
  public int lastPreservedRefId() {
    return readRefIds.get(readRefIds.size - 1);
  }

  /** Returns whether there is at least one reserved ref id waiting to be bound. */
  @Override
  public boolean hasPreservedRefId() {
    return readRefIds.size > 0;
  }

  /** Binds the most recently reserved ref id to {@code object}. */
  @Override
  public void reference(Object object) {
    int refId = readRefIds.pop();
    setReadRef(refId, object);
  }

  /** Returns the previously materialized object stored at {@code id}. */
  @Override
  public Object getReadRef(int id) {
    return readObjects.get(id);
  }

  /** Returns the object resolved by the last ref header that pointed to an existing instance. */
  @Override
  public Object getReadRef() {
    return readObject;
  }

  /** Stores {@code object} under an already reserved read ref id. */
  @Override
  public void setReadRef(int id, Object object) {
    if (id >= 0) {
      readObjects.set(id, object);
    }
  }

  /** Exposes the resolved read-reference table for debugging and focused tests. */
  public ObjectArray getReadRefs() {
    return readObjects;
  }

  /** Clears the current read state and keeps an approximate capacity for the next operation. */
  @Override
  public void reset() {
    long totalObjectSize = this.readTotalObjectSize + readObjects.size();
    long counter = this.readCounter + 1;
    if (counter < 0 || totalObjectSize < 0) {
      counter = 1;
      totalObjectSize = readObjects.size();
    }
    this.readCounter = counter;
    this.readTotalObjectSize = totalObjectSize;
    int avg = (int) (totalObjectSize / counter);
    if (avg <= DEFAULT_ARRAY_CAPACITY) {
      avg = DEFAULT_ARRAY_CAPACITY;
    }
    readObjects.clearApproximate(avg);
    readRefIds.clear();
    readObject = null;
  }
}

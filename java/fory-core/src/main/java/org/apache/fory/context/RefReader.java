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

import org.apache.fory.memory.MemoryBuffer;

/**
 * Read-side contract for object reference tracking.
 *
 * <p>Implementations decode ref/null/value headers, reserve ids for objects that are about to be
 * materialized, and resolve previously read references by id.
 */
public interface RefReader {
  /** Reads a ref-or-null header and returns the raw header byte. */
  byte readRefOrNull(MemoryBuffer buffer);

  /** Reserves the next read reference id for an object that will be materialized shortly. */
  int preserveRefId();

  /** Records an already known reference id as preserved for the next object. */
  int preserveRefId(int refId);

  /** Reads a ref/value header and returns either the preserved id or the raw header byte. */
  int tryPreserveRefId(MemoryBuffer buffer);

  /** Returns the most recently preserved read reference id. */
  int lastPreservedRefId();

  /** Returns whether there is a preserved id waiting to be bound to an object. */
  boolean hasPreservedRefId();

  /** Binds the most recently preserved reference id to {@code object}. */
  void reference(Object object);

  /** Returns the previously materialized object for a specific ref id. */
  Object getReadRef(int id);

  /** Returns the object resolved by the last ref-header read. */
  Object getReadRef();

  /** Replaces the object stored for a previously preserved ref id. */
  void setReadRef(int id, Object object);

  /** Clears all per-operation ref-tracking state. */
  void reset();

  /** Read-side implementation used when ref tracking is disabled. */
  final class NoRefReader implements RefReader {
    @Override
    public byte readRefOrNull(MemoryBuffer buffer) {
      return buffer.readByte();
    }

    @Override
    public int preserveRefId() {
      return -1;
    }

    @Override
    public int preserveRefId(int refId) {
      return -1;
    }

    @Override
    public int tryPreserveRefId(MemoryBuffer buffer) {
      return buffer.readByte();
    }

    @Override
    public int lastPreservedRefId() {
      return -1;
    }

    @Override
    public boolean hasPreservedRefId() {
      return false;
    }

    @Override
    public void reference(Object object) {}

    @Override
    public Object getReadRef(int id) {
      return null;
    }

    @Override
    public Object getReadRef() {
      return null;
    }

    @Override
    public void setReadRef(int id, Object object) {}

    @Override
    public void reset() {}
  }
}

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
import org.apache.fory.memory.MemoryBuffer;

/**
 * Write-side contract for object reference tracking.
 *
 * <p>Implementations encode ref/null/value headers, remember previously written objects by
 * identity, and support rebinding ref ids when serializers replace one object instance with
 * another.
 */
public interface RefWriter {
  /**
   * Writes a ref-or-null header for {@code obj} and returns whether the payload is fully handled.
   */
  boolean writeRefOrNull(MemoryBuffer buffer, Object obj);

  /** Writes a ref-or-value header for a non-null object. */
  boolean writeRefValueFlag(MemoryBuffer buffer, Object obj);

  /** Writes only a null marker when {@code obj} is {@code null}. */
  boolean writeNullFlag(MemoryBuffer buffer, Object obj);

  /**
   * Rebinds the recorded ref id of {@code original} to the id already assigned to {@code
   * newObject}.
   */
  void replaceRef(Object original, Object newObject);

  /** Clears all per-operation ref-tracking state. */
  void reset();

  /** Write-side implementation used when ref tracking is disabled. */
  final class NoRefWriter implements RefWriter {
    @Override
    public boolean writeRefOrNull(MemoryBuffer buffer, Object obj) {
      if (obj == null) {
        buffer.writeByte(Fory.NULL_FLAG);
        return true;
      }
      buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
      return false;
    }

    @Override
    public boolean writeRefValueFlag(MemoryBuffer buffer, Object obj) {
      assert obj != null;
      buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
      return true;
    }

    @Override
    public boolean writeNullFlag(MemoryBuffer buffer, Object obj) {
      if (obj == null) {
        buffer.writeByte(Fory.NULL_FLAG);
        return true;
      }
      return false;
    }

    @Override
    public void replaceRef(Object original, Object newObject) {}

    @Override
    public void reset() {}
  }
}

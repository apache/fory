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

import java.util.Objects;
import org.apache.fory.annotation.Internal;
import org.apache.fory.collection.IdentityObjectIntMap;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.EncodedMetaString;

/**
 * Write-side state for meta-string references.
 *
 * <p>The writer deduplicates {@link EncodedMetaString} instances by identity for one operation and
 * assigns dense dynamic ids to repeated occurrences. Only the encoded payload is shared; the write
 * ids are local to this writer instance.
 */
@Internal
public final class MetaStringWriter {
  private static final int INITIAL_CAPACITY = 2;
  private static final float LOAD_FACTOR = 0.5f;
  private static final int SMALL_STRING_THRESHOLD = 16;
  private static final int MISSING_DYNAMIC_WRITE_STRING_ID = Integer.MIN_VALUE;

  private final IdentityObjectIntMap<EncodedMetaString> dynamicWrittenStrings =
      new IdentityObjectIntMap<>(INITIAL_CAPACITY, LOAD_FACTOR);

  /** Creates an empty writer state for one serialization stream. */
  public MetaStringWriter() {}

  /**
   * Writes a meta string preceded by the protocol variant that carries an explicit flag bit.
   *
   * <p>If the same encoded meta string was already written by this writer, a compact dynamic ref id
   * is emitted instead of the full payload.
   */
  public void writeMetaStringWithFlag(MemoryBuffer buffer, EncodedMetaString encodedMetaString) {
    Objects.requireNonNull(encodedMetaString);
    int id = dynamicWrittenStrings.putOrGet(encodedMetaString, dynamicWrittenStrings.size);
    if (id == MISSING_DYNAMIC_WRITE_STRING_ID) {
      writeNewMetaStringWithFlag(buffer, encodedMetaString);
    } else {
      buffer.writeVarUint32Small7(((id + 1) << 2) | 0b11);
    }
  }

  /**
   * Writes a meta string using the protocol variant without an extra caller-supplied flag bit.
   *
   * <p>If the same encoded meta string was already written by this writer, a compact dynamic ref id
   * is emitted instead of the full payload.
   */
  public void writeMetaString(MemoryBuffer buffer, EncodedMetaString encodedMetaString) {
    Objects.requireNonNull(encodedMetaString);
    int id = dynamicWrittenStrings.putOrGet(encodedMetaString, dynamicWrittenStrings.size);
    if (id == MISSING_DYNAMIC_WRITE_STRING_ID) {
      writeNewMetaString(buffer, encodedMetaString);
    } else {
      buffer.writeVarUint32Small7(((id + 1) << 1) | 1);
    }
  }

  /** Clears all dynamic ids so this writer can be reused for a new serialization stream. */
  public void reset() {
    dynamicWrittenStrings.clear();
  }

  private void writeNewMetaStringWithFlag(
      MemoryBuffer buffer, EncodedMetaString encodedMetaString) {
    int length = encodedMetaString.bytes.length;
    buffer.writeVarUint32Small7(length << 2 | 0b1);
    if (length > SMALL_STRING_THRESHOLD) {
      buffer.writeInt64(encodedMetaString.hash);
    } else if (length != 0) {
      buffer.writeByte(encodedMetaString.encoding.getValue());
    }
    buffer.writeBytes(encodedMetaString.bytes);
  }

  private void writeNewMetaString(MemoryBuffer buffer, EncodedMetaString encodedMetaString) {
    int length = encodedMetaString.bytes.length;
    buffer.writeVarUint32Small7(length << 1);
    if (length > SMALL_STRING_THRESHOLD) {
      buffer.writeInt64(encodedMetaString.hash);
    } else if (length != 0) {
      buffer.writeByte(encodedMetaString.encoding.getValue());
    }
    buffer.writeBytes(encodedMetaString.bytes);
  }
}

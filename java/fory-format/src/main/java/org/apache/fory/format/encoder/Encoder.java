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

package org.apache.fory.format.encoder;

import org.apache.fory.memory.MemoryBuffer;

/**
 * The encoding interface for encode/decode object to/from binary. The implementation class must
 * have a constructor with signature {@code Object[] references}, so we can pass any params to
 * codec.
 *
 * @param <T> type of value
 */
public interface Encoder<T> {

  /** Decode a buffer with an embedded size. Variants without embedded size are not compatible. */
  T decode(MemoryBuffer buffer);

  /** Decode a buffer without an embedded size. Variants with embedded size are not compatible. */
  T decode(byte[] bytes);

  /** Decode a buffer without an embedded size. Variants with embedded size are not compatible. */
  T decodeRemaining(MemoryBuffer buffer);

  /** Encode to a buffer without embedded size. Variants with embedded size are not compatible. */
  byte[] encode(T obj);

  /**
   * Encode to a buffer with an embedded size. Variants without embedded size are not compatible.
   */
  void encode(MemoryBuffer buffer, T obj);

  /**
   * Encode to a buffer without an embedded size. Variants with embedded size are not compatible.
   * Returns number of bytes written to the buffer.
   */
  int bareEncode(MemoryBuffer buffer, T obj);

  /**
   * Encode to a buffer without an embedded size. Variants with embedded size are not compatible.
   * Returns a sliced buffer view of bytes written.
   */
  default MemoryBuffer bareEncodeSlice(final MemoryBuffer buffer, final T obj) {
    final int initialIndex = buffer.writerIndex();
    final int size = bareEncode(buffer, obj);
    return buffer.slice(initialIndex, size);
  }
}

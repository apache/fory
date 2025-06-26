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
  /** Decode a buffer. */
  T decode(MemoryBuffer buffer);

  /** Decode a byte array. */
  T decode(byte[] bytes);

  /** Encode to a byte array. */
  byte[] encode(T obj);

  /** Encode to a buffer. Returns number of bytes written to the buffer. */
  int encode(MemoryBuffer buffer, T obj);

  /** Encode to a buffer. Returns a sliced buffer view of bytes written. */
  default MemoryBuffer encodeSlice(final MemoryBuffer buffer, final T obj) {
    final int initialIndex = buffer.writerIndex();
    final int size = encode(buffer, obj);
    return buffer.slice(initialIndex, size);
  }
}

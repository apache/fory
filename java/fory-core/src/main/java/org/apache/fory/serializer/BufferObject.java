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

package org.apache.fory.serializer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.apache.fory.memory.MemoryBuffer;

/**
 * Fory serialized representation of an object. Note: This class is used for zero-copy out-of-band
 * serialization and shouldn't be used for any other cases.
 *
 * @see ByteBufferBufferObject
 * @see PrimitiveArraySerializers.PrimitiveArrayBufferObject
 */
public interface BufferObject {

  int totalBytes();

  /**
   * Write serialized object to buffer. Note: The caller should try to ensure `buffer.writerIndex`
   * is aligned, otherwise the memory copy will be inefficient.
   */
  void writeTo(MemoryBuffer buffer);

  /** Write serialized data as Buffer. */
  MemoryBuffer toBuffer();

  final class ByteBufferBufferObject implements BufferObject {
    private final ByteBuffer buffer;

    public ByteBufferBufferObject(ByteBuffer buffer) {
      this.buffer = buffer;
    }

    @Override
    public int totalBytes() {
      return buffer.remaining() + 1;
    }

    /** Writes a byte representing the byte order. */
    @Override
    public void writeTo(MemoryBuffer buffer) {
      // `writeByte` may make writerIndex not aligned, so write data first instead.
      buffer.write(this.buffer.duplicate());
      buffer.writeByte(this.buffer.order() == ByteOrder.BIG_ENDIAN ? (byte) 1 : 0);
    }

    @Override
    public MemoryBuffer toBuffer() {
      MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(totalBytes());
      writeTo(buffer);
      return buffer.slice(0, buffer.writerIndex());
    }
  }
}

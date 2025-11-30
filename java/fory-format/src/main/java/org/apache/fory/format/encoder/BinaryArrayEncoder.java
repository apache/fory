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

import org.apache.fory.format.row.binary.BinaryArray;
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.format.type.Field;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;

class BinaryArrayEncoder<T> implements ArrayEncoder<T> {
  private final BinaryArrayWriter writer;
  private final GeneratedArrayEncoder codec;
  private final boolean sizeEmbedded;

  BinaryArrayEncoder(
      final BinaryArrayWriter writer,
      final GeneratedArrayEncoder codec,
      final boolean sizeEmbedded) {
    this.writer = writer;
    this.codec = codec;
    this.sizeEmbedded = sizeEmbedded;
  }

  @Override
  public Field field() {
    return writer.getField();
  }

  @SuppressWarnings("unchecked")
  @Override
  public T fromArray(final BinaryArray array) {
    return (T) codec.fromArray(array);
  }

  @Override
  public BinaryArray toArray(final T obj) {
    return codec.toArray(obj);
  }

  @Override
  public T decode(final MemoryBuffer buffer) {
    return decode(buffer, sizeEmbedded ? buffer.readInt32() : buffer.remaining());
  }

  @Override
  public T decode(final byte[] bytes) {
    return decode(MemoryUtils.wrap(bytes), bytes.length);
  }

  T decode(final MemoryBuffer buffer, final int size) {
    final BinaryArray array = writer.newArray();
    final int readerIndex = buffer.readerIndex();
    array.pointTo(buffer, readerIndex, size);
    buffer.readerIndex(readerIndex + size);
    return fromArray(array);
  }

  @Override
  public byte[] encode(final T obj) {
    final BinaryArray array = toArray(obj);
    return writer.getBuffer().getBytes(0, array.getSizeInBytes());
  }

  @Override
  public int encode(final MemoryBuffer buffer, final T obj) {
    final MemoryBuffer prevBuffer = writer.getBuffer();
    final int writerIndex = buffer.writerIndex();
    if (sizeEmbedded) {
      buffer.writeInt32(-1);
    }
    try {
      writer.setBuffer(buffer);
      toArray(obj);
      final int size = buffer.writerIndex() - writerIndex;
      if (sizeEmbedded) {
        buffer.putInt32(writerIndex, size - 4);
      }
      return size;
    } finally {
      writer.setBuffer(prevBuffer);
    }
  }
}

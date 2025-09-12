package org.apache.fory.format.encoder;

import org.apache.arrow.vector.types.pojo.Field;
import org.apache.fory.format.row.binary.BinaryArray;
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;

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
class BufferResettingArrayEncoder<T> implements ArrayEncoder<T> {

  private final int initialBufferSize;
  private final BinaryArrayWriter writer;
  private final ArrayEncoder<T> encoder;

  BufferResettingArrayEncoder(
      final int initialBufferSize, final BinaryArrayWriter writer, final ArrayEncoder<T> encoder) {
    this.initialBufferSize = initialBufferSize;
    this.writer = writer;
    this.encoder = encoder;
  }

  @Override
  public T decode(final MemoryBuffer buffer) {
    return encoder.decode(buffer);
  }

  @Override
  public T decodeRemaining(final MemoryBuffer buffer) {
    return encoder.decodeRemaining(buffer);
  }

  @Override
  public T decode(final byte[] bytes) {
    return encoder.decode(bytes);
  }

  @Override
  public byte[] encode(final T obj) {
    writer.setBuffer(MemoryUtils.buffer(initialBufferSize));
    return encoder.encode(obj);
  }

  @Override
  public void encode(final MemoryBuffer buffer, final T obj) {
    encoder.encode(buffer, obj);
  }

  @Override
  public int bareEncode(final MemoryBuffer buffer, final T obj) {
    return encoder.bareEncode(buffer, obj);
  }

  @Override
  public MemoryBuffer bareEncodeSlice(final MemoryBuffer buffer, final T obj) {
    return encoder.bareEncodeSlice(buffer, obj);
  }

  @Override
  public Field field() {
    return encoder.field();
  }

  @Override
  public T fromArray(final BinaryArray array) {
    return encoder.fromArray(array);
  }

  @Override
  public BinaryArray toArray(final T obj) {
    writer.setBuffer(MemoryUtils.buffer(initialBufferSize));
    return encoder.toArray(obj);
  }
}

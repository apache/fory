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

import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.format.row.binary.writer.BaseBinaryRowWriter;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;

/** Wrap a RowEncoder to provide a fresh buffer for every toRow operation, when not streaming. */
class BufferResettingRowEncoder<T> implements RowEncoder<T> {

  private final int initialBufferSize;
  private final BaseBinaryRowWriter writer;
  private final RowEncoder<T> encoder;

  BufferResettingRowEncoder(
      final int initialBufferSize, final BaseBinaryRowWriter writer, final RowEncoder<T> encoder) {
    this.initialBufferSize = initialBufferSize;
    this.writer = writer;
    this.encoder = encoder;
  }

  @Override
  public Schema schema() {
    return encoder.schema();
  }

  @Override
  public T fromRow(final BinaryRow row) {
    return encoder.fromRow(row);
  }

  @Override
  public BinaryRow toRow(final T obj) {
    writer.setBuffer(MemoryUtils.buffer(initialBufferSize));
    writer.reset();
    return encoder.toRow(obj);
  }

  @Override
  public T decode(final MemoryBuffer buffer) {
    return encoder.decode(buffer);
  }

  @Override
  public T decode(final byte[] bytes) {
    return encoder.decode(bytes);
  }

  @Override
  public byte[] encode(final T obj) {
    return encoder.encode(obj);
  }

  @Override
  public int encode(final MemoryBuffer buffer, final T obj) {
    return encoder.encode(buffer, obj);
  }
}

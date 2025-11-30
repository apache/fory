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
import org.apache.fory.format.row.binary.BinaryMap;
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.format.type.Field;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;

/** Wrap a MapEncoder to provide a fresh buffer for every toMap operation, when not streaming. */
class BufferResettingMapEncoder<T> implements MapEncoder<T> {

  private final int initialBufferSize;
  private final BinaryArrayWriter keyWriter;
  private final BinaryArrayWriter valWriter;
  private final MapEncoder<T> encoder;

  BufferResettingMapEncoder(
      final int initialBufferSize,
      final BinaryArrayWriter keyWriter,
      final BinaryArrayWriter valWriter,
      final MapEncoder<T> encoder) {
    this.initialBufferSize = initialBufferSize;
    this.keyWriter = keyWriter;
    this.valWriter = valWriter;
    this.encoder = encoder;
  }

  @Override
  public Field keyField() {
    return encoder.keyField();
  }

  @Override
  public Field valueField() {
    return encoder.valueField();
  }

  @Override
  public T fromMap(final BinaryArray keyArray, final BinaryArray valArray) {
    return encoder.fromMap(keyArray, valArray);
  }

  @Override
  public BinaryMap toMap(final T obj) {
    final MemoryBuffer buffer = MemoryUtils.buffer(initialBufferSize);
    keyWriter.setBuffer(buffer);
    valWriter.setBuffer(buffer);
    return encoder.toMap(obj);
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
    final MemoryBuffer buffer = MemoryUtils.buffer(initialBufferSize);
    keyWriter.setBuffer(buffer);
    valWriter.setBuffer(buffer);
    return encoder.encode(obj);
  }

  @Override
  public int encode(final MemoryBuffer buffer, final T obj) {
    return encoder.encode(buffer, obj);
  }
}

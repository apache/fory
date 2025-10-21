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

import org.apache.arrow.vector.types.pojo.Field;
import org.apache.fory.format.row.binary.BinaryArray;
import org.apache.fory.format.row.binary.BinaryMap;
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;

class BinaryMapEncoder<M> implements MapEncoder<M> {
  private final Encoding format;
  private final Field mapField;
  private final BinaryArrayWriter valWriter;
  private final BinaryArrayWriter keyWriter;
  private final GeneratedMapEncoder codec;
  private final boolean sizeEmbedded;

  BinaryMapEncoder(
      final Encoding format,
      final Field mapField,
      final BinaryArrayWriter valWriter,
      final BinaryArrayWriter keyWriter,
      final GeneratedMapEncoder codec,
      final boolean sizeEmbedded) {
    this.format = format;
    this.mapField = mapField;
    this.valWriter = valWriter;
    this.keyWriter = keyWriter;
    this.codec = codec;
    this.sizeEmbedded = sizeEmbedded;
  }

  @Override
  public Field keyField() {
    return keyWriter.getField();
  }

  @Override
  public Field valueField() {
    return valWriter.getField();
  }

  @SuppressWarnings("unchecked")
  @Override
  public M fromMap(final BinaryArray key, final BinaryArray value) {
    return (M) codec.fromMap(key, value);
  }

  @Override
  public BinaryMap toMap(final M obj) {
    return codec.toMap(obj);
  }

  @Override
  public M decode(final MemoryBuffer buffer) {
    return decode(buffer, sizeEmbedded ? buffer.readInt32() : buffer.remaining());
  }

  M decode(final MemoryBuffer buffer, final int size) {
    final BinaryMap map = format.newMap(mapField);
    final int readerIndex = buffer.readerIndex();
    map.pointTo(buffer, readerIndex, size);
    buffer.readerIndex(readerIndex + size);
    return fromMap(map);
  }

  @Override
  public M decode(final byte[] bytes) {
    return decode(MemoryUtils.wrap(bytes), bytes.length);
  }

  @Override
  public byte[] encode(final M obj) {
    final BinaryMap map = toMap(obj);
    return map.getBuf().getBytes(map.getBaseOffset(), map.getSizeInBytes());
  }

  @Override
  public int encode(final MemoryBuffer buffer, final M obj) {
    final MemoryBuffer prevBuffer = keyWriter.getBuffer();
    final int writerIndex = buffer.writerIndex();
    if (sizeEmbedded) {
      buffer.writeInt32(-1);
    }
    try {
      keyWriter.setBuffer(buffer);
      valWriter.setBuffer(buffer);
      toMap(obj);
      final int size = buffer.writerIndex() - writerIndex;
      if (sizeEmbedded) {
        buffer.putInt32(writerIndex, size - 4);
      }
      return size;
    } finally {
      keyWriter.setBuffer(prevBuffer);
      valWriter.setBuffer(prevBuffer);
    }
  }
}

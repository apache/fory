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
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;

class BeanArrayEncoder<T> implements ArrayEncoder<T> {
    private final BinaryArrayWriter writer;
    private final Field field;
    private final GeneratedArrayEncoder codec;

    BeanArrayEncoder(BinaryArrayWriter writer, Field field,
            GeneratedArrayEncoder codec) {
        this.writer = writer;
        this.field = field;
        this.codec = codec;
    }

    @Override
    public Field field() {
      return field;
    }

    @SuppressWarnings("unchecked")
    @Override
    public T fromArray(BinaryArray array) {
      return (T) codec.fromArray(array);
    }

    @Override
    public BinaryArray toArray(T obj) {
      return codec.toArray(obj);
    }

    @Override
    public T decode(MemoryBuffer buffer) {
      return decode(buffer, buffer.readInt32());
    }

    public T decode(MemoryBuffer buffer, int size) {
      BinaryArray array = new BinaryArray(field);
      int readerIndex = buffer.readerIndex();
      array.pointTo(buffer, readerIndex, size);
      buffer.readerIndex(readerIndex + size);
      return fromArray(array);
    }

    @Override
    public T decode(byte[] bytes) {
      return decode(MemoryUtils.wrap(bytes), bytes.length);
    }

    @Override
    public byte[] encode(T obj) {
      BinaryArray array = toArray(obj);
      return writer.getBuffer().getBytes(0, 8 + array.getSizeInBytes());
    }

    @Override
    public void encode(MemoryBuffer buffer, T obj) {
      MemoryBuffer prevBuffer = writer.getBuffer();
      int writerIndex = buffer.writerIndex();
      buffer.writeInt32(-1);
      try {
        writer.setBuffer(buffer);
        BinaryArray array = toArray(obj);
        int size = buffer.writerIndex() - writerIndex - 4;
        assert size == array.getSizeInBytes();
        buffer.putInt32(writerIndex, size);
      } finally {
        writer.setBuffer(prevBuffer);
      }
    }
}

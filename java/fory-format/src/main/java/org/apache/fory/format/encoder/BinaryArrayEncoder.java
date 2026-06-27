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

import org.apache.fory.collection.LongMap;
import org.apache.fory.exception.ClassNotCompatibleException;
import org.apache.fory.format.row.binary.BinaryArray;
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.format.type.Field;
import org.apache.fory.memory.LittleEndian;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;

class BinaryArrayEncoder<T> implements ArrayEncoder<T> {
  private final BinaryArrayWriter writer;
  private final GeneratedArrayEncoder codec;
  private final boolean sizeEmbedded;

  /**
   * Strict hash of the element bean's current schema; written before the array payload when {@code
   * schemaEvolution} is on.
   */
  private final long currentHash;

  /** Per-version projection codecs and their element fields. {@code null} disables versioning. */
  private final LongMap<ProjectionArrayCodec> projections;

  /**
   * A projection variant of the array codec along with the writer used to materialize an array
   * instance of the right physical type (standard vs. compact) for the historical element field.
   */
  static final class ProjectionArrayCodec {
    final BinaryArrayWriter writer;
    final GeneratedArrayEncoder codec;

    ProjectionArrayCodec(BinaryArrayWriter writer, GeneratedArrayEncoder codec) {
      this.writer = writer;
      this.codec = codec;
    }
  }

  BinaryArrayEncoder(
      final BinaryArrayWriter writer,
      final GeneratedArrayEncoder codec,
      final boolean sizeEmbedded) {
    this(writer, codec, sizeEmbedded, 0L, null);
  }

  BinaryArrayEncoder(
      final BinaryArrayWriter writer,
      final GeneratedArrayEncoder codec,
      final boolean sizeEmbedded,
      final long currentHash,
      final LongMap<ProjectionArrayCodec> projections) {
    this.writer = writer;
    this.codec = codec;
    this.sizeEmbedded = sizeEmbedded;
    this.currentHash = currentHash;
    this.projections = projections;
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
    // byte[] overloads ignore sizeEmbedded: encode writes no length prefix (under schema evolution
    // an 8-byte hash leads the body, but that is data, not framing), so decode takes the size from
    // bytes.length.
    return decode(MemoryUtils.wrap(bytes), bytes.length);
  }

  @SuppressWarnings("unchecked")
  T decode(final MemoryBuffer buffer, final int size) {
    if (projections == null) {
      // Evolution off: the whole payload is body, with no hash prefix. Reading evolution-on bytes
      // here misreads the leading hash as data; that direction is documented as unsupported in the
      // row-format guide (producer and consumer must agree on the flag).
      final BinaryArray array = writer.newArray();
      final int readerIndex = buffer.readerIndex();
      array.pointTo(buffer, readerIndex, size);
      buffer.readerIndex(readerIndex + size);
      return fromArray(array);
    }
    if (size < 8) {
      throw new ClassNotCompatibleException(
          "Array payload too small for an 8-byte schema hash under schema evolution: size=" + size);
    }
    final long peerHash = buffer.readInt64();
    final int bodySize = size - 8;
    if (peerHash == currentHash) {
      final BinaryArray array = writer.newArray();
      final int readerIndex = buffer.readerIndex();
      array.pointTo(buffer, readerIndex, bodySize);
      buffer.readerIndex(readerIndex + bodySize);
      return fromArray(array);
    }
    ProjectionArrayCodec projection = projections.get(peerHash);
    if (projection == null) {
      throw new ClassNotCompatibleException(
          String.format(
              "Array element schema is not consistent. self/peer hash are %x/%x.",
              currentHash, peerHash));
    }
    BinaryArray array = projection.writer.newArray();
    final int readerIndex = buffer.readerIndex();
    array.pointTo(buffer, readerIndex, bodySize);
    buffer.readerIndex(readerIndex + bodySize);
    return (T) projection.codec.fromArray(array);
  }

  @Override
  public byte[] encode(final T obj) {
    final BinaryArray array = toArray(obj);
    if (projections == null) {
      return writer.getBuffer().getBytes(0, array.getSizeInBytes());
    }
    // Build the result with a single allocation: the result byte[]. The hash header is poked
    // in via LittleEndian (no buffer wrapper) and the body is copied in via System.arraycopy.
    final int n = array.getSizeInBytes();
    if (n > Integer.MAX_VALUE - 8) {
      throw new EncoderException("Array body too large to prepend schema hash header: " + n);
    }
    final byte[] result = new byte[8 + n];
    LittleEndian.putInt64(result, 0, currentHash);
    writer.getBuffer().get(0, result, 8, n);
    return result;
  }

  @Override
  public int encode(final MemoryBuffer buffer, final T obj) {
    final MemoryBuffer prevBuffer = writer.getBuffer();
    final int writerIndex = buffer.writerIndex();
    if (sizeEmbedded) {
      buffer.writeInt32(-1);
    }
    if (projections != null) {
      buffer.writeInt64(currentHash);
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

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
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.format.row.binary.writer.BaseBinaryRowWriter;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.Schema;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;

class BinaryRowEncoder<T> implements RowEncoder<T> {
  private final Schema schema;
  private final GeneratedRowEncoder codec;
  private final BaseBinaryRowWriter writer;
  private final boolean sizeEmbedded;
  private final long schemaHash;

  /**
   * Hash → (historical schema, projection codec) for older versions. {@code null} when schema
   * evolution is disabled; in that case a hash mismatch is a hard error.
   */
  private final LongMap<ProjectionCodec> projections;

  private final MemoryBuffer buffer = MemoryUtils.buffer(16);

  /**
   * A historical schema, the projection codec that reads it, and a row factory with that schema's
   * layout precomputed so projection decodes match the current-schema path's per-call cost.
   */
  static final class ProjectionCodec {
    final RowFactory rowFactory;
    final GeneratedRowEncoder codec;

    ProjectionCodec(RowFactory rowFactory, GeneratedRowEncoder codec) {
      this.rowFactory = rowFactory;
      this.codec = codec;
    }
  }

  BinaryRowEncoder(
      final Schema schema,
      final GeneratedRowEncoder codec,
      final BaseBinaryRowWriter writer,
      final boolean sizeEmbedded) {
    this(schema, codec, writer, sizeEmbedded, DataTypes.computeSchemaHash(schema), null);
  }

  BinaryRowEncoder(
      final Schema schema,
      final GeneratedRowEncoder codec,
      final BaseBinaryRowWriter writer,
      final boolean sizeEmbedded,
      final long schemaHash,
      final LongMap<ProjectionCodec> projections) {
    this.schema = schema;
    this.codec = codec;
    this.writer = writer;
    this.sizeEmbedded = sizeEmbedded;
    this.schemaHash = schemaHash;
    this.projections = projections;
  }

  @Override
  public Schema schema() {
    return schema;
  }

  @SuppressWarnings("unchecked")
  @Override
  public T fromRow(final BinaryRow row) {
    return (T) codec.fromRow(row);
  }

  @Override
  public BinaryRow toRow(final T obj) {
    return codec.toRow(obj);
  }

  @Override
  public T decode(final MemoryBuffer buffer) {
    return decode(buffer, sizeEmbedded ? buffer.readInt32() : buffer.remaining());
  }

  @SuppressWarnings("unchecked")
  T decode(final MemoryBuffer buffer, final int size) {
    if (size < 8) {
      throw new ClassNotCompatibleException(
          "Row payload too small for an 8-byte schema hash: size=" + size);
    }
    final long peerSchemaHash = buffer.readInt64();
    // The 8-byte hash has just been consumed; the row body occupies the remaining bytes.
    final int rowSize = size - 8;
    if (peerSchemaHash == schemaHash) {
      // Hot path: writer.newRow() reuses the writer's cached row layout for the current schema.
      final BinaryRow row = writer.newRow();
      row.pointTo(buffer, buffer.readerIndex(), rowSize);
      buffer.increaseReaderIndex(rowSize);
      return fromRow(row);
    }
    if (projections != null) {
      ProjectionCodec projection = projections.get(peerSchemaHash);
      if (projection != null) {
        // The writer is bound to the current schema, so the historical row comes from the
        // projection's own factory, which carries that schema's precomputed layout.
        final BinaryRow row = projection.rowFactory.newRow();
        row.pointTo(buffer, buffer.readerIndex(), rowSize);
        buffer.increaseReaderIndex(rowSize);
        return (T) projection.codec.fromRow(row);
      }
    }
    throw new ClassNotCompatibleException(
        String.format(
            "Schema is not consistent, encoder schema is %s. "
                + "self/peer schema hash are %x/%x. "
                + "Please check writer schema.",
            schema, schemaHash, peerSchemaHash));
  }

  @Override
  public T decode(final byte[] bytes) {
    // byte[] overloads ignore sizeEmbedded: encode writes no length prefix (the schema-hash prefix
    // is part of the body, not framing), so decode takes the size from bytes.length.
    return decode(MemoryUtils.wrap(bytes), bytes.length);
  }

  @Override
  public byte[] encode(final T obj) {
    buffer.writerIndex(0);
    buffer.writeInt64(schemaHash);
    writer.setBuffer(buffer);
    writer.reset();
    final BinaryRow row = toRow(obj);
    return buffer.getBytes(0, 8 + row.getSizeInBytes());
  }

  @Override
  public int encode(final MemoryBuffer buffer, final T obj) {
    final int writerIndex = buffer.writerIndex();
    if (sizeEmbedded) {
      buffer.writeInt32(-1);
    }
    try {
      buffer.writeInt64(schemaHash);
      writer.setBuffer(buffer);
      writer.reset();
      codec.toRow(obj);
      final int size = buffer.writerIndex() - writerIndex;
      if (sizeEmbedded) {
        buffer.putInt32(writerIndex, size - 4);
      }
      return size;
    } finally {
      writer.setBuffer(this.buffer);
    }
  }
}

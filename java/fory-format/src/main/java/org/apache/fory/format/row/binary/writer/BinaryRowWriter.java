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

package org.apache.fory.format.row.binary.writer;

import static org.apache.fory.memory.BitUtils.calculateBitmapWidthInBytes;

import java.math.BigDecimal;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.memory.MemoryBuffer;

/**
 * Writer to write data into buffer using row format, see {@link BinaryRow}.
 *
 * <p>Must call {@code reset()} before use this writer to write a nested row.
 *
 * <p>Must call {@code reset(buffer)}/{@code reset(buffer, offset)} before use this writer to write
 * a new row.
 */
public class BinaryRowWriter extends BinaryWriter {
  private final Schema schema;
  private final int headerInBytes;
  // fixed width size: bitmap + fixed width region
  // variable-length region follows startOffset + fixedSize
  private final int fixedSize;

  public BinaryRowWriter(Schema schema) {
    super(MemoryBuffer.buffer(schema.getFields().size() * 32), 0);
    super.startIndex = 0;
    this.schema = schema;
    this.headerInBytes = calculateBitmapWidthInBytes(schema.getFields().size());
    this.fixedSize = headerInBytes + schema.getFields().size() * 8;
  }

  public BinaryRowWriter(Schema schema, BinaryWriter writer) {
    super(writer.getBuffer(), 0);
    writer.children.add(this);
    // Since we must call reset before use this writer,
    // there's no need to set `super.startIndex = writer.writerIndex();`
    this.schema = schema;
    this.headerInBytes = calculateBitmapWidthInBytes(schema.getFields().size());
    this.fixedSize = headerInBytes + schema.getFields().size() * 8;
  }

  public Schema getSchema() {
    return schema;
  }

  /**
   * Call {@code reset()} before write nested row to buffer
   *
   * <p>reset BinaryRowWriter(schema, writer) increase writerIndex, which increase writer's
   * writerIndex, so we need to record writer's writerIndex before call reset, so we can call
   * writer's {@code setOffsetAndSize(int ordinal, int absoluteOffset, int size)}. <em>Reset will
   * change writerIndex, please use it very carefully</em>
   */
  public void reset() {
    super.startIndex = buffer.writerIndex();
    grow(fixedSize);
    buffer._increaseWriterIndexUnsafe(fixedSize);
    int end = startIndex + headerInBytes;
    for (int i = startIndex; i < end; i += 8) {
      buffer.putInt64(i, 0L);
    }
  }

  @Override
  public int getOffset(int ordinal) {
    return startIndex + headerInBytes + (ordinal << 3); // ordinal * 8 = (ordinal << 3)
  }

  @Override
  public void write(int ordinal, byte value) {
    final int offset = getOffset(ordinal);
    buffer.putInt64(offset, 0L);
    buffer.putByte(offset, value);
  }

  @Override
  public void write(int ordinal, boolean value) {
    final int offset = getOffset(ordinal);
    buffer.putInt64(offset, 0L);
    buffer.putBoolean(offset, value);
  }

  @Override
  public void write(int ordinal, short value) {
    final int offset = getOffset(ordinal);
    buffer.putInt64(offset, 0L);
    buffer.putInt16(offset, value);
  }

  @Override
  public void write(int ordinal, int value) {
    final int offset = getOffset(ordinal);
    buffer.putInt64(offset, 0L);
    buffer.putInt32(offset, value);
  }

  @Override
  public void write(int ordinal, float value) {
    final int offset = getOffset(ordinal);
    buffer.putInt64(offset, 0L);
    buffer.putFloat32(offset, value);
  }

  @Override
  public void write(int ordinal, BigDecimal value) {
    writeDecimal(ordinal, value, (ArrowType.Decimal) schema.getFields().get(ordinal).getType());
  }

  public BinaryRow getRow() {
    BinaryRow row = new BinaryRow(schema);
    int size = size();
    row.pointTo(buffer, startIndex, size);
    return row;
  }

  public BinaryRow copyToRow() {
    BinaryRow row = new BinaryRow(schema);
    int size = size();
    MemoryBuffer buffer = MemoryBuffer.buffer(size);
    this.buffer.copyTo(startIndex, buffer, 0, size);
    row.pointTo(buffer, startIndex, size);
    return row;
  }
}

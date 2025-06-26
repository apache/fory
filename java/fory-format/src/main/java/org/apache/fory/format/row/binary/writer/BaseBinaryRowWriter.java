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

import java.math.BigDecimal;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;

/**
 * Writer to write data into buffer using row format, see {@link BinaryRow}.
 *
 * <p>Must call {@code reset()} before use this writer to write a nested row.
 *
 * <p>Must call {@code reset(buffer)}/{@code reset(buffer, offset)} before use this writer to write
 * a new row.
 */
public abstract class BaseBinaryRowWriter extends BinaryWriter {
  private final Schema schema;

  public BaseBinaryRowWriter(Schema schema) {
    super(MemoryUtils.buffer(schema.getFields().size() * 32), 0);
    super.startIndex = 0;
    this.schema = schema;
  }

  protected BaseBinaryRowWriter(Schema schema, int bitMapOffset) {
    super(MemoryUtils.buffer(schema.getFields().size() * 32), bitMapOffset);
    super.startIndex = 0;
    this.schema = schema;
  }

  public BaseBinaryRowWriter(Schema schema, BinaryWriter writer) {
    super(writer.getBuffer(), 0);
    writer.children.add(this);
    // Since we must call reset before use this writer,
    // there's no need to set `super.startIndex = writer.writerIndex();`
    this.schema = schema;
  }

  protected BaseBinaryRowWriter(Schema schema, MemoryBuffer buffer, int bitMapOffset) {
    super(buffer, bitMapOffset);
    this.schema = schema;
  }

  protected BaseBinaryRowWriter(Schema schema, BinaryWriter writer, int bitMapOffset) {
    super(writer.getBuffer(), bitMapOffset);
    writer.children.add(this);
    // Since we must call reset before use this writer,
    // there's no need to set `super.startIndex = writer.writerIndex();`
    this.schema = schema;
  }

  protected abstract int fixedSize();

  protected abstract int headerInBytes();

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
    int fixedSize = fixedSize();
    grow(fixedSize);
    buffer._increaseWriterIndexUnsafe(fixedSize);
    resetHeader();
  }

  protected void resetHeader() {
    int bitmapStart = startIndex + bytesBeforeBitMap;
    int end = bitmapStart + headerInBytes();
    for (int i = bitmapStart; i < end; i += 1) {
      buffer.putByte(i, 0);
    }
  }

  @Override
  public void write(int ordinal, BigDecimal value) {
    writeDecimal(ordinal, value, (ArrowType.Decimal) schema.getFields().get(ordinal).getType());
  }

  @Override
  public void setNullAt(int ordinal) {
    super.setNullAt(ordinal);
    clearValue(ordinal);
  }

  protected void clearValue(int ordinal) {
    write(ordinal, 0L);
  }

  public BinaryRow getRow() {
    BinaryRow row = newRow();
    int size = size();
    row.pointTo(buffer, startIndex, size);
    return row;
  }

  public BinaryRow copyToRow() {
    BinaryRow row = newRow();
    int size = size();
    MemoryBuffer buffer = MemoryUtils.buffer(size);
    this.buffer.copyTo(startIndex, buffer, 0, size);
    row.pointTo(buffer, startIndex, size);
    return row;
  }

  protected abstract BinaryRow newRow();
}

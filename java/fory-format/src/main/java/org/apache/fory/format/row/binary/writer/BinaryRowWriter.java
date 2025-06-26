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
public class BinaryRowWriter extends BaseBinaryRowWriter {
  private final int headerInBytes;
  // fixed width size: bitmap + fixed width region
  // variable-length region follows startOffset + fixedSize
  private final int fixedSize;

  public BinaryRowWriter(Schema schema) {
    super(schema);
    headerInBytes = headerBytes(schema);
    fixedSize = fixedAreaSize();
  }

  public BinaryRowWriter(Schema schema, MemoryBuffer buffer) {
    super(schema, buffer, 0);
    headerInBytes = headerBytes(schema);
    fixedSize = fixedAreaSize();
  }

  public BinaryRowWriter(Schema schema, BinaryWriter writer) {
    super(schema, writer);
    this.headerInBytes = headerBytes(schema);
    this.fixedSize = fixedAreaSize();
  }

  private int fixedAreaSize() {
    return headerInBytes + getSchema().getFields().size() * 8;
  }

  static int headerBytes(Schema schema) {
    return calculateBitmapWidthInBytes(schema.getFields().size());
  }

  @Override
  protected int headerInBytes() {
    return headerInBytes;
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
  protected int fixedSize() {
    return fixedSize;
  }

  @Override
  public void setNullAt(int ordinal) {
    super.setNullAt(ordinal);
    write(ordinal, 0L);
  }

  @Override
  protected BinaryRow newRow() {
    return new BinaryRow(getSchema());
  }
}

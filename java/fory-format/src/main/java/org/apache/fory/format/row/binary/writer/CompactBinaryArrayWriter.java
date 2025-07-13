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

import static org.apache.fory.format.row.binary.writer.CompactBinaryRowWriter.fixedWidthFor;

import org.apache.arrow.vector.types.pojo.Field;
import org.apache.fory.format.row.binary.CompactBinaryArray;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;

public class CompactBinaryArrayWriter extends BinaryArrayWriter {

  private final boolean fixedWidth;

  /** Must call reset before using writer constructed by this constructor. */
  public CompactBinaryArrayWriter(final Field field) {
    // buffer size can grow
    this(field, MemoryUtils.buffer(64));
    super.startIndex = 0;
  }

  /**
   * Write data to writer's buffer.
   *
   * <p>Must call reset before using writer constructed by this constructor
   */
  public CompactBinaryArrayWriter(final Field field, final BinaryWriter writer) {
    this(field, writer.buffer);
    writer.children.add(this);
    // Since we must call reset before use this writer,
    // there's no need to set `super.startIndex = writer.writerIndex();`
  }

  public CompactBinaryArrayWriter(final Field field, final MemoryBuffer buffer) {
    super(field, buffer, 4, elementWidth(field));
    fixedWidth = fixedWidthFor(field.getChildren().get(0)) >= 0;
  }

  public static int elementWidth(final Field field) {
    final int width = fixedWidthFor(field.getChildren().get(0));
    if (width < 0) {
      return 8;
    } else {
      return width;
    }
  }

  @Override
  protected void writeNumElements() {
    buffer.putInt32(startIndex, numElements);
  }

  @Override
  protected int calculateHeaderInBytes() {
    return CompactBinaryArray.calculateHeaderInBytes(numElements);
  }

  @Override
  protected void resetAdvanceWriter(final int fixedPartInBytes) {
    if (fixedWidth) {
      buffer._increaseWriterIndexUnsafe(headerInBytes);
    } else {
      super.resetAdvanceWriter(fixedPartInBytes);
    }
  }

  @Override
  protected void primitiveArrayAdvance(final int size) {
    buffer._increaseWriterIndexUnsafe(size);
  }

  @Override
  public void setOffsetAndSize(final int ordinal, final int absoluteOffset, final int size) {
    if (fixedWidth) {
      return;
    }
    super.setOffsetAndSize(ordinal, absoluteOffset, size);
  }
}

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

import org.apache.fory.format.row.binary.BinaryArray;
import org.apache.fory.format.row.binary.CompactBinaryArray;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.Field;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;

public class CompactBinaryArrayWriter extends BinaryArrayWriter {

  private final int fixedWidth;
  private final boolean elementNullable;

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
    super(CompactBinaryRowWriter.sortField(field), buffer, 4, elementWidth(field));
    DataTypes.ListType listType = (DataTypes.ListType) this.field.type();
    final Field elementField = listType.valueField();
    fixedWidth = fixedWidthFor(elementField);
    elementNullable = elementField.nullable();
  }

  public static int elementWidth(final Field field) {
    DataTypes.ListType listType = (DataTypes.ListType) field.type();
    final int width = fixedWidthFor(listType.valueField());
    if (width < 0) {
      return 8;
    } else {
      return width;
    }
  }

  // The compact array sizes every element slot from the element's declared schema width, so a
  // fixed-slot write must match that width exactly; see BinaryWriter#checkFixedWidth.
  // Variable-width elements (fixedWidth < 0) store an offset/length pointer and are not checked.
  @Override
  protected void checkFixedWidth(final int ordinal, final int written) {
    assert fixedWidth < 0 || fixedWidth == written
        : "element "
            + ((DataTypes.ListType) field.type()).valueField()
            + " has a "
            + fixedWidth
            + "-byte slot but the codec wrote "
            + written
            + " bytes; getForyField width must match encodedType";
  }

  @Override
  protected int writeNumElements() {
    buffer.putInt32(startIndex, numElements);
    return 4;
  }

  @Override
  protected int calculateHeaderInBytes() {
    return CompactBinaryArray.calculateHeaderInBytes(fixedWidth, numElements, elementNullable);
  }

  // Binary-valued element codecs write through these paths rather than write(long), so the
  // fixed-slot width check must live here too; see CompactBinaryRowWriter for the row analogue.
  @Override
  public void writeUnaligned(
      final int ordinal, final byte[] input, final int offset, final int numBytes) {
    if (fixedWidth > 0) {
      checkFixedWidth(ordinal, numBytes);
    }
    super.writeUnaligned(ordinal, input, offset, numBytes);
  }

  @Override
  public void writeUnaligned(
      final int ordinal, final MemoryBuffer input, final int offset, final int numBytes) {
    if (fixedWidth > 0) {
      checkFixedWidth(ordinal, numBytes);
    }
    super.writeUnaligned(ordinal, input, offset, numBytes);
  }

  @Override
  public void writeAlignedBytes(
      final int ordinal, final MemoryBuffer input, final int baseOffset, final int numBytes) {
    if (fixedWidth > 0) {
      checkFixedWidth(ordinal, numBytes);
    }
    super.writeAlignedBytes(ordinal, input, baseOffset, numBytes);
  }

  @Override
  protected void primitiveArrayAdvance(final int size) {
    buffer._increaseWriterIndexUnsafe(size);
  }

  @Override
  protected int bufferWriteIndexFor(final int ordinal) {
    if (fixedWidth > 0) {
      return getOffset(ordinal);
    } else {
      return super.bufferWriteIndexFor(ordinal);
    }
  }

  @Override
  protected boolean copyShouldIncreaseWriterIndex(final int ordinal) {
    if (fixedWidth > 0) {
      return false;
    } else {
      return super.copyShouldIncreaseWriterIndex(ordinal);
    }
  }

  @Override
  public void setOffsetAndSize(final int ordinal, final int absoluteOffset, final int size) {
    if (fixedWidth > 0) {
      return;
    }
    super.setOffsetAndSize(ordinal, absoluteOffset, size);
  }

  @Override
  public void setNotNullAt(final int ordinal) {
    if (!elementNullable) {
      return;
    }
    super.setNotNullAt(ordinal);
  }

  @Override
  public void setNullAt(final int ordinal) {
    if (!elementNullable) {
      throw new NullPointerException("unexpected null element at ordinal " + ordinal);
    }
    super.setNullAt(ordinal);
    if (fixedWidth > 0) {
      final int off = getOffset(ordinal);
      for (int i = off; i < off + fixedWidth; i++) {
        getBuffer().putByte(i, 0);
      }
    }
  }

  public void resetFor(final CompactBinaryRowWriter nestedWriter, final int ordinal) {
    if (fixedWidth > 0) {
      nestedWriter.startIndex = getOffset(ordinal);
      nestedWriter.resetHeader();
    } else {
      nestedWriter.reset();
    }
  }

  @Override
  public BinaryArray newArray() {
    return new CompactBinaryArray(field);
  }
}

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

package org.apache.fory.format.row.binary;

import org.apache.fory.format.type.Field;
import org.apache.fory.format.type.Schema;
import org.apache.fory.memory.MemoryBuffer;

/**
 * A compact version of {@link BinaryRow}. The compact encoding includes additional optimizations:
 *
 * <ul>
 *   <li>fixed size binary objects are stored in the fixed size section with no pointer needed
 *   <li>small values can take up fewer than 8 bytes
 *   <li>null bitmap is skipped if all fields are primitive / not-nullable
 *   <li>the header is packed better, with the null-bitmap allowed to borrow alignment padding at
 *       end of fixed section
 *   <li>data alignment is relaxed, which could lead to less performance in very intensive memory
 *       operations
 * </ul>
 *
 * <b>The compact format is still under development and may not be stable yet.</b>
 */
public class CompactBinaryRow extends BinaryRow {
  private final CompactRowLayout layout;
  private final int bitmapOffset;

  public CompactBinaryRow(final Schema schema) {
    this(new CompactRowLayout(schema));
  }

  CompactBinaryRow(CompactRowLayout layout) {
    super(layout.schema, layout.bitmapWidthInBytes);
    this.layout = layout;
    this.bitmapOffset = layout.fixedOffsets[layout.fixedOffsets.length - 1];
  }

  CompactRowLayout getLayout() {
    return layout;
  }

  @Override
  public boolean isNullAt(final int ordinal) {
    if (layout.allFieldsNotNullable) {
      return false;
    }
    return super.isNullAt(ordinal);
  }

  @Override
  public int getOffset(final int ordinal) {
    return baseOffset + layout.fixedOffsets[ordinal];
  }

  @Override
  public MemoryBuffer getBuffer(final int ordinal) {
    final int fixedWidthBinary = layout.fixedWidths[ordinal];
    if (fixedWidthBinary >= 0) {
      if (isNullAt(ordinal)) {
        return null;
      }
      return getBuffer().slice(getOffset(ordinal), fixedWidthBinary);
    } else {
      return super.getBuffer(ordinal);
    }
  }

  @Override
  public byte[] getBinary(final int ordinal) {
    final int fixedWidthBinary = layout.fixedWidths[ordinal];
    if (fixedWidthBinary >= 0) {
      if (isNullAt(ordinal)) {
        return null;
      }
      final byte[] bytes = new byte[fixedWidthBinary];
      getBuffer().get(getOffset(ordinal), bytes, 0, fixedWidthBinary);
      return bytes;
    } else {
      return super.getBinary(ordinal);
    }
  }

  @Override
  protected BinaryRow getStruct(final int ordinal, final Field field, final int extDataSlot) {
    if (isNullAt(ordinal)) {
      return null;
    }
    final CompactBinaryRow row = layout.childLayouts[ordinal].newRow();
    final int fixedWidthBinary = layout.fixedWidths[ordinal];
    if (fixedWidthBinary == -1) {
      final long offsetAndSize = getInt64(ordinal);
      final int relativeOffset = (int) (offsetAndSize >> 32);
      final int size = (int) offsetAndSize;
      row.pointTo(getBuffer(), getBaseOffset() + relativeOffset, size);
    } else {
      row.pointTo(getBuffer(), getOffset(ordinal), fixedWidthBinary);
    }
    return row;
  }

  @Override
  BinaryArray getArray(final int ordinal, final Field field) {
    if (isNullAt(ordinal)) {
      return null;
    }
    final long offsetAndSize = getInt64(ordinal);
    final int relativeOffset = (int) (offsetAndSize >> 32);
    final int size = (int) offsetAndSize;
    final CompactBinaryArray array = new CompactBinaryArray(field, layout.childLayouts[ordinal]);
    array.pointTo(getBuffer(), getBaseOffset() + relativeOffset, size);
    return array;
  }

  @Override
  protected BinaryArray newArray(final Field field) {
    return new CompactBinaryArray(field);
  }

  @Override
  protected BinaryMap newMap(final Field field) {
    return new CompactBinaryMap(field);
  }

  @Override
  protected int nullBitmapOffset() {
    return baseOffset + bitmapOffset;
  }

  @Override
  protected BinaryRow rowForCopy() {
    return new CompactBinaryRow(layout);
  }
}

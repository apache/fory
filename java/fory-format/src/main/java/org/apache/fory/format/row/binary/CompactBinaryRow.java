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

import org.apache.fory.format.row.binary.writer.CompactBinaryRowWriter;
import org.apache.fory.format.type.DataTypes;
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
  private final boolean allFieldsNotNullable;
  private final int[] fixedOffsets;
  private final int bitmapOffset;

  public CompactBinaryRow(final Schema schema) {
    this(schema, CompactBinaryRowWriter.fixedOffsets(schema));
  }

  public CompactBinaryRow(final Schema schema, final int[] fixedOffsets) {
    super(schema);
    this.fixedOffsets = fixedOffsets;
    bitmapOffset = fixedOffsets[fixedOffsets.length - 1];
    allFieldsNotNullable = CompactBinaryRowWriter.allNotNullable(schema.fields());
  }

  @Override
  protected int computeBitmapWidthInBytes() {
    // cannot use field due to initialization order
    if (CompactBinaryRowWriter.allNotNullable(schema.fields())) {
      return 0;
    }
    return CompactBinaryRowWriter.headerBytes(schema);
  }

  @Override
  public boolean isNullAt(final int ordinal) {
    if (allFieldsNotNullable) {
      return false;
    }
    return super.isNullAt(ordinal);
  }

  // TODO: this should use StableValue once it's available
  @Override
  public int getOffset(final int ordinal) {
    return baseOffset + fixedOffsets[ordinal];
  }

  @Override
  public MemoryBuffer getBuffer(final int ordinal) {
    final int fixedWidthBinary = CompactBinaryRowWriter.fixedWidthFor(schema, ordinal);
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
    final int fixedWidthBinary = CompactBinaryRowWriter.fixedWidthFor(schema, ordinal);
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
    final int fixedWidthBinary = CompactBinaryRowWriter.fixedWidthFor(schema, ordinal);
    if (fixedWidthBinary == -1) {
      return super.getStruct(ordinal, field, extDataSlot);
    }
    if (extData[extDataSlot] == null) {
      extData[extDataSlot] = DataTypes.createSchema(field);
    }
    final BinaryRow row = newRow((Schema) extData[extDataSlot]);
    row.pointTo(getBuffer().slice(getOffset(ordinal), fixedWidthBinary), 0, fixedWidthBinary);
    return row;
  }

  @Override
  protected Schema newSchema(final Field field) {
    return CompactBinaryRowWriter.sortSchema(super.newSchema(field));
  }

  @Override
  protected BinaryRow newRow(final Schema schema) {
    // TODO: avoid re-computing these offsets
    return new CompactBinaryRow(schema, CompactBinaryRowWriter.fixedOffsets(schema));
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
    return new CompactBinaryRow(schema, fixedOffsets);
  }
}

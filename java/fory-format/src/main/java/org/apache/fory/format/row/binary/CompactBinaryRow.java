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

import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.fory.format.row.binary.writer.CompactRowWriter;
import org.apache.fory.memory.MemoryBuffer;

/**
 * A compact version of {@link BinaryRow}. The compact encoding includes additional optimizations:
 * fixed size binary objects are stored in the fixed size section with no pointer needed, smaller
 * values can take up fewer than 8 bytes, the header is packed better, and data alignment is
 * relaxed. The compact format is still under development and may not be stable yet.
 */
public class CompactBinaryRow extends BinaryRow {
  private final int[] fixedOffsets;
  private final int bitmapOffset;

  public CompactBinaryRow(final Schema schema, final int[] fixedOffsets) {
    super(schema);
    this.fixedOffsets = fixedOffsets;
    bitmapOffset = fixedOffsets[fixedOffsets.length - 1];
  }

  // TODO: this should use StableValue once it's available
  @Override
  public int getOffset(final int ordinal) {
    return baseOffset + fixedOffsets[ordinal];
  }

  @Override
  public MemoryBuffer getBuffer(final int ordinal) {
    final int fixedWidthBinary = CompactRowWriter.fixedWidthFor(schema, ordinal);
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
  protected BinaryRow newRow(final Schema schema) {
    // TODO: avoid re-computing these offsets
    return new CompactBinaryRow(schema, CompactRowWriter.fixedOffsets(schema));
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
  protected int isNullBitmapOffset() {
    return bitmapOffset;
  }

  @Override
  protected BinaryRow rowForCopy() {
    return new CompactBinaryRow(schema, fixedOffsets);
  }
}

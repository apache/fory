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
import org.apache.fory.format.row.binary.writer.BinaryWriter;
import org.apache.fory.format.row.binary.writer.CompactBinaryArrayWriter;
import org.apache.fory.format.row.binary.writer.CompactBinaryRowWriter;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.memory.MemoryBuffer;

public class CompactBinaryArray extends BinaryArray {
  private final Field elementField;
  private final int fixedWidth;
  private final boolean elementNullable;
  private int headerInBytes;

  public CompactBinaryArray(final Field field) {
    super(field, CompactBinaryArrayWriter.elementWidth(field));
    elementField = field.getChildren().get(0);
    fixedWidth = CompactBinaryRowWriter.fixedWidthFor(elementField);
    elementNullable = elementField.isNullable();
  }

  @Override
  protected int elementOffset() {
    return getBaseOffset() + headerInBytes;
  }

  public static int calculateHeaderInBytes(
      final int fixedWidth, final int numElements, final boolean elementNullable) {
    int headerInBytes = 4 + (elementNullable ? (numElements + 7) / 8 : 0);
    if (fixedWidth % 8 == 0) {
      headerInBytes = BinaryWriter.roundNumberOfBytesToNearestWord(headerInBytes);
    }
    return headerInBytes;
  }

  @Override
  protected int bitmapOffset() {
    return getBaseOffset() + 4;
  }

  @Override
  protected int readNumElements() {
    final var numElements = getBuffer().getInt32(getBaseOffset());
    headerInBytes = calculateHeaderInBytes(fixedWidth, numElements, elementNullable);
    return numElements;
  }

  @Override
  public boolean isNullAt(final int ordinal) {
    if (!elementNullable) {
      return false;
    }
    return super.isNullAt(ordinal);
  }

  @Override
  public MemoryBuffer getBuffer(final int ordinal) {
    if (fixedWidth == -1) {
      return super.getBuffer(ordinal);
    }
    if (isNullAt(ordinal)) {
      return null;
    }
    return getBuffer().slice(getOffset(ordinal), elementSize);
  }

  @Override
  public byte[] getBinary(final int ordinal) {
    if (fixedWidth == -1) {
      return super.getBinary(ordinal);
    }
    if (isNullAt(ordinal)) {
      return null;
    }
    final byte[] bytes = new byte[elementSize];
    getBuffer().get(getOffset(ordinal), bytes, 0, elementSize);
    return bytes;
  }

  @Override
  protected BinaryRow getStruct(final int ordinal, final Field field, final int extDataSlot) {
    if (isNullAt(ordinal)) {
      return null;
    }
    assert field == elementField;
    if (fixedWidth == -1) {
      return super.getStruct(ordinal, field, extDataSlot);
    }
    if (extData[extDataSlot] == null) {
      extData[extDataSlot] = DataTypes.createSchema(field);
    }
    final BinaryRow row = newRow((Schema) extData[extDataSlot]);
    row.pointTo(getBuffer(), getOffset(ordinal), fixedWidth);
    return row;
  }

  @Override
  protected Schema newSchema(final Field field) {
    return CompactBinaryRowWriter.sortSchema(super.newSchema(field));
  }

  @Override
  protected BinaryRow newRow(final Schema schema) {
    // TODO: don't re-compute fixed offsets
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
}

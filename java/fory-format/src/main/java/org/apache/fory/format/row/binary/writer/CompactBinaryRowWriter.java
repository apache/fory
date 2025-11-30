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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.format.row.binary.CompactBinaryRow;
import org.apache.fory.format.type.DataType;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.Field;
import org.apache.fory.format.type.Schema;
import org.apache.fory.memory.MemoryBuffer;

/** Writer class to produce {@link CompactBinaryRow}-formatted rows. */
public class CompactBinaryRowWriter extends BaseBinaryRowWriter {

  private final int headerSize;
  private final boolean allFieldsFixedSize;
  private final boolean allFieldsNotNullable;
  private final int fixedSize;
  private final int[] fixedOffsets;

  public CompactBinaryRowWriter(final Schema schema) {
    super(sortSchema(schema), computeFixedRegionSize(schema));
    headerSize = headerBytes(schema);
    allFieldsFixedSize = allFieldsFixedSize(schema);
    fixedSize = computeAlignedFixedRegionSize();
    fixedOffsets = fixedOffsets(schema);
    allFieldsNotNullable = allNotNullable(schema.fields());
  }

  public CompactBinaryRowWriter(final Schema schema, final BinaryWriter writer) {
    super(sortSchema(schema), writer, computeFixedRegionSize(schema));
    headerSize = headerBytes(schema);
    allFieldsFixedSize = allFieldsFixedSize(schema);
    fixedSize = computeAlignedFixedRegionSize();
    fixedOffsets = fixedOffsets(schema);
    allFieldsNotNullable = allNotNullable(schema.fields());
  }

  public CompactBinaryRowWriter(final Schema schema, final MemoryBuffer buffer) {
    super(sortSchema(schema), buffer, computeFixedRegionSize(schema));
    headerSize = headerBytes(schema);
    allFieldsFixedSize = allFieldsFixedSize(schema);
    fixedSize = computeAlignedFixedRegionSize();
    fixedOffsets = fixedOffsets(schema);
    allFieldsNotNullable = allNotNullable(schema.fields());
  }

  private static boolean allFieldsFixedSize(final Schema schema) {
    for (final Field f : schema.fields()) {
      if (fixedWidthFor(f) == -1) {
        return false;
      }
    }
    return true;
  }

  private int computeAlignedFixedRegionSize() {
    final int totalUnalignedSize = headerSize + bytesBeforeBitMap;
    if (allFieldsFixedSize) {
      return totalUnalignedSize;
    }
    return roundNumberOfBytesToNearestWord(totalUnalignedSize);
  }

  // TODO: this should use StableValue once it's available
  public static boolean allNotNullable(final List<Field> fields) {
    for (final Field f : fields) {
      if (f.nullable()) {
        return false;
      }
    }
    return true;
  }

  public static int headerBytes(final Schema schema) {
    return headerBytes(schema.fields());
  }

  public static int headerBytes(final List<Field> fields) {
    if (allNotNullable(fields)) {
      return 0;
    }
    return (fields.size() + 7) / 8;
  }

  public static Schema sortSchema(final Schema schema) {
    return new Schema(sortFields(schema.fields()), schema.metadata());
  }

  @Override
  protected int fixedSize() {
    return fixedSize;
  }

  @Override
  protected int headerInBytes() {
    return headerSize;
  }

  @Override
  public int getOffset(final int ordinal) {
    return startIndex + fixedOffsets[ordinal];
  }

  /** Total size of fixed region: fixed size inline values plus variable sized values' pointers. */
  static int computeFixedRegionSize(final Schema schema) {
    int fixedSize = 0;
    for (final Field f : schema.fields()) {
      fixedSize += fixedRegionSpaceFor(f);
    }
    return fixedSize;
  }

  /** Number of bytes used for a field if fixed, -1 if variable sized. */
  public static int fixedWidthFor(final Schema schema, final int ordinal) {
    return fixedWidthFor(schema.field(ordinal));
  }

  /** Number of bytes used for a field if fixed, -1 if variable sized. */
  public static int fixedWidthFor(final Field f) {
    DataType type = f.type();
    int fixedWidth = DataTypes.getTypeWidth(type);
    if (fixedWidth == -1) {
      if (type instanceof DataTypes.StructType) {
        DataTypes.StructType structType = (DataTypes.StructType) type;
        int nestedWidth = headerBytes(structType.fields());
        for (final Field child : structType.fields()) {
          final int childWidth = fixedWidthFor(child);
          if (childWidth == -1) {
            return -1;
          }
          nestedWidth += childWidth;
        }
        return nestedWidth;
      }
    }
    return fixedWidth;
  }

  /**
   * Number of bytes used in fixed-data area for a field - 8 (combined offset + size) if
   * variable-sized.
   */
  static int fixedRegionSpaceFor(final Field f) {
    final int fixedWidth = fixedWidthFor(f);
    if (fixedWidth == -1) {
      return 8;
    }
    return fixedWidth;
  }

  // TODO: this should use StableValue once it's available
  public static int[] fixedOffsets(final Schema schema) {
    final List<Field> fields = schema.fields();
    final int[] result = new int[fields.size() + 1];
    int off = 0;
    for (int i = 0; i < fields.size(); i++) {
      result[i] = off;
      off += fixedRegionSpaceFor(fields.get(i));
    }
    result[fields.size()] = off;
    return result;
  }

  static List<Field> sortFields(final List<Field> fields) {
    final ArrayList<Field> sortedFields = new ArrayList<>(fields);
    for (int i = 0; i < sortedFields.size(); i++) {
      sortedFields.set(i, sortField(sortedFields.get(i)));
    }
    Collections.sort(sortedFields, new FieldAlignmentComparator());
    return sortedFields;
  }

  static Field sortField(final Field field) {
    DataType type = field.type();
    if (!(type instanceof DataTypes.StructType)) {
      return field;
    }
    DataTypes.StructType structType = (DataTypes.StructType) type;
    final List<Field> children = structType.fields();
    final List<Field> sortedChildren = sortFields(children);
    if (children.equals(sortedChildren)) {
      return field;
    }
    return new Field(field.name(), new DataTypes.StructType(sortedChildren), field.nullable());
  }

  public void resetFor(final CompactBinaryRowWriter nestedWriter, final int ordinal) {
    if (fixedWidthFor(getSchema(), ordinal) == -1) {
      nestedWriter.reset();
    } else {
      nestedWriter.startIndex = getOffset(ordinal);
      nestedWriter.resetHeader();
    }
  }

  @Override
  protected void resetHeader() {
    final int bitmapStart = startIndex + bytesBeforeBitMap;
    final int end = startIndex + computeAlignedFixedRegionSize();
    for (int i = bitmapStart; i < end; i += 1) {
      buffer.putByte(i, 0);
    }
  }

  @Override
  public void setNullAt(final int ordinal) {
    if (allFieldsNotNullable) {
      throw new IllegalStateException(
          "Field " + getSchema().field(ordinal) + " has null value for not-null field");
    }
    super.setNullAt(ordinal);
  }

  @Override
  protected void clearValue(final int ordinal) {
    final int fixedWidth = fixedWidthFor(getSchema(), ordinal);
    if (fixedWidth > 0) {
      final int off = getOffset(ordinal);
      for (int i = off; i < off + fixedWidth; i++) {
        getBuffer().putByte(i, 0);
      }
    } else {
      super.clearValue(ordinal);
    }
  }

  @Override
  public void setNotNullAt(final int ordinal) {
    if (allFieldsNotNullable) {
      return;
    }
    super.setNotNullAt(ordinal);
  }

  @Override
  public boolean isNullAt(final int ordinal) {
    if (allFieldsNotNullable) {
      return false;
    }
    return super.isNullAt(ordinal);
  }

  @Override
  public void write(final int ordinal, final byte value) {
    final int offset = getOffset(ordinal);
    buffer.putByte(offset, value);
  }

  @Override
  public void write(final int ordinal, final boolean value) {
    final int offset = getOffset(ordinal);
    buffer.putBoolean(offset, value);
  }

  @Override
  public void write(final int ordinal, final short value) {
    final int offset = getOffset(ordinal);
    buffer.putInt16(offset, value);
  }

  @Override
  public void write(final int ordinal, final int value) {
    final int offset = getOffset(ordinal);
    buffer.putInt32(offset, value);
  }

  @Override
  public void write(final int ordinal, final float value) {
    final int offset = getOffset(ordinal);
    buffer.putFloat32(offset, value);
  }

  @Override
  public void writeUnaligned(
      final int ordinal, final byte[] input, final int offset, final int numBytes) {
    final int inlineWidth = fixedWidthFor(getSchema(), ordinal);
    if (inlineWidth > 0) {
      buffer.put(getOffset(ordinal), input, 0, numBytes);
    } else {
      super.writeUnaligned(ordinal, input, offset, numBytes);
    }
  }

  @Override
  public void writeUnaligned(
      final int ordinal, final MemoryBuffer input, final int offset, final int numBytes) {
    final int inlineWidth = fixedWidthFor(getSchema(), ordinal);
    if (inlineWidth > 0) {
      assert inlineWidth == numBytes;
      buffer.copyFrom(getOffset(ordinal), input, 0, numBytes);
    } else {
      super.writeUnaligned(ordinal, input, offset, numBytes);
    }
  }

  @Override
  public void writeAlignedBytes(
      final int ordinal, final MemoryBuffer input, final int baseOffset, final int numBytes) {
    final int inlineWidth = fixedWidthFor(getSchema(), ordinal);
    if (inlineWidth > 0) {
      buffer.copyFrom(getOffset(ordinal), input, 0, numBytes);
    } else {
      super.writeAlignedBytes(ordinal, input, baseOffset, numBytes);
    }
  }

  @Override
  protected BinaryRow newRow() {
    return new CompactBinaryRow(getSchema(), fixedOffsets);
  }
}

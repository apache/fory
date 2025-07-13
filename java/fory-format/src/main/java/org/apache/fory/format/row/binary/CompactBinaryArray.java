package org.apache.fory.format.row.binary;

import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.fory.format.row.binary.writer.BinaryWriter;
import org.apache.fory.format.row.binary.writer.CompactBinaryArrayWriter;
import org.apache.fory.format.row.binary.writer.CompactBinaryRowWriter;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.memory.MemoryBuffer;

public class CompactBinaryArray extends BinaryArray {
  private final boolean fixedWidth;

  public CompactBinaryArray(final Field field) {
    super(field, CompactBinaryArrayWriter.elementWidth(field));
    fixedWidth = CompactBinaryRowWriter.fixedWidthFor(field.getChildren().get(0)) >= 0;
  }

  @Override
  protected int elementOffset() {
    return getBaseOffset() + calculateHeaderInBytes(numElements());
  }

  public static int calculateHeaderInBytes(final int numElements) {
    return BinaryWriter.roundNumberOfBytesToNearestWord(4 + (numElements + 7) / 8);
  }

  @Override
  protected int bitmapOffset() {
    return getBaseOffset() + 4;
  }

  @Override
  protected int readNumElements() {
    return getBuffer().getInt32(getBaseOffset());
  }

  @Override
  public MemoryBuffer getBuffer(final int ordinal) {
    if (!fixedWidth) {
      return super.getBuffer(ordinal);
    }
    if (isNullAt(ordinal)) {
      return null;
    }
    return getBuffer().slice(getOffset(ordinal), elementSize);
  }

  @Override
  protected BinaryRow getStruct(final int ordinal, final Field field, final int extDataSlot) {
    if (isNullAt(ordinal)) {
      return null;
    }
    final int fixedBytes = CompactBinaryRowWriter.fixedWidthFor(field);
    if (fixedBytes == -1) {
      return super.getStruct(ordinal, field, extDataSlot);
    }
    if (extData[extDataSlot] == null) {
      extData[extDataSlot] = DataTypes.createSchema(field);
    }
    final BinaryRow row = newRow((Schema) extData[extDataSlot]);
    row.pointTo(getBuffer(), getOffset(ordinal), fixedBytes);
    return row;
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

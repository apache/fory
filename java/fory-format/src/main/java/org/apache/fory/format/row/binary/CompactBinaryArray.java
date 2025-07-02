package org.apache.fory.format.row.binary;

import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.fory.format.row.binary.writer.BinaryWriter;
import org.apache.fory.format.row.binary.writer.CompactBinaryRowWriter;

public class CompactBinaryArray extends BinaryArray {
  public CompactBinaryArray(final Field field) {
    super(field);
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

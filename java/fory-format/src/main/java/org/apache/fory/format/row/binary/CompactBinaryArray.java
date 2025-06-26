package org.apache.fory.format.row.binary;

import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.fory.format.row.binary.writer.CompactRowWriter;

class CompactBinaryArray extends BinaryArray {
  public CompactBinaryArray(final Field field) {
    super(field);
  }

  @Override
  protected BinaryRow newRow(final Schema schema) {
    // TODO: don't re-compute fixed offsets
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
}

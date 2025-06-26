package org.apache.fory.format.row.binary;

import org.apache.arrow.vector.types.pojo.Field;

class CompactBinaryMap extends BinaryMap {
  public CompactBinaryMap(final Field field) {
    super(field);
  }

  @Override
  protected BinaryArray newArray(final Field field) {
    return new CompactBinaryArray(field);
  }
}

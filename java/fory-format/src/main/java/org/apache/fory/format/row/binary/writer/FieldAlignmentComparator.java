package org.apache.fory.format.row.binary.writer;

import java.util.Comparator;
import org.apache.arrow.vector.types.pojo.Field;

class FieldAlignmentComparator implements Comparator<Field> {
  @Override
  public int compare(final Field f1, final Field f2) {
    final int f1Size = CompactRowWriter.fixedWidthFor(f1);
    final int f2Size = CompactRowWriter.fixedWidthFor(f2);
    final int f1Align = tryAlign(f1Size);
    final int f2Align = tryAlign(f2Size);
    final int alignCmp = Integer.compare(f1Align, f2Align);
    if (alignCmp != 0) {
      return alignCmp;
    }
    return -Integer.compare(f1Size, f2Size);
  }

  protected int tryAlign(final int size) {
    return size == 4 || size == 2 || (size & 7) == 0 ? 0 : 1;
  }
}

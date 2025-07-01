package org.apache.fory.format.row.binary.writer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.ArrowType.ArrowTypeID;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.format.row.binary.CompactBinaryRow;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.memory.MemoryBuffer;

/** Writer class to produce {@link CompactBinaryRow}-formatted rows. */
public class CompactRowWriter extends BaseBinaryRowWriter {

  private final int headerSize;
  private final boolean allNotNullable;
  private final int fixedSize;
  private final int[] fixedOffsets;

  public CompactRowWriter(final Schema schema) {
    super(sortSchema(schema), computeFixedRegionSize(schema));
    headerSize = headerBytes(schema);
    fixedSize = roundNumberOfBytesToNearestWord(headerSize + bytesBeforeBitMap);
    fixedOffsets = fixedOffsets(schema);
    allNotNullable = allNotNullable(schema.getFields());
  }

  public CompactRowWriter(final Schema schema, final BinaryWriter writer) {
    super(sortSchema(schema), writer, computeFixedRegionSize(schema));
    headerSize = headerBytes(schema);
    fixedSize = roundNumberOfBytesToNearestWord(headerSize + bytesBeforeBitMap);
    fixedOffsets = fixedOffsets(schema);
    allNotNullable = allNotNullable(schema.getFields());
  }

  private static boolean allNotNullable(final List<Field> fields) {
    for (final Field f : fields) {
      if (f.isNullable()) {
        return false;
      }
    }
    return true;
  }

  private int headerBytes(final Schema schema) {
    if (allNotNullable(schema.getFields())) {
      return 0;
    }
    return headerBytes(schema.getFields());
  }

  private static int headerBytes(final List<Field> fields) {
    return (fields.size() + 7) / 8;
  }

  public static Schema sortSchema(final Schema schema) {
    return new Schema(sortFields(schema.getFields()), schema.getCustomMetadata());
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

  private static int fixedBinarySize(final Field field) {
    final ArrowType type = field.getType();
    if (type.getTypeID() == ArrowTypeID.FixedSizeBinary) {
      return ((ArrowType.FixedSizeBinary) type).getByteWidth();
    }
    return -1;
  }

  /** Total size of fixed region: fixed size inline values plus variable sized values' pointers. */
  static int computeFixedRegionSize(final Schema schema) {
    int fixedSize = 0;
    for (final Field f : schema.getFields()) {
      fixedSize += fixedRegionSpaceFor(f);
    }
    return fixedSize;
  }

  /** Number of bytes used for a field if fixed, -1 if variable sized. */
  public static int fixedWidthFor(final Schema schema, final int ordinal) {
    return fixedWidthFor(schema.getFields().get(ordinal));
  }

  /** Number of bytes used for a field if fixed, -1 if variable sized. */
  static int fixedWidthFor(final Field f) {
    int fixedWidth = DataTypes.getTypeWidth(f.getType());
    if (fixedWidth == -1) {
      if (f.getType().getTypeID() == ArrowTypeID.Struct) {
        int nestedWidth = headerBytes(f.getChildren());
        for (final Field child : f.getChildren()) {
          final int childWidth = fixedWidthFor(child);
          if (childWidth == -1) {
            return -1;
          }
          nestedWidth += childWidth;
        }
        return nestedWidth;
      }
      fixedWidth = fixedBinarySize(f);
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
    final List<Field> fields = schema.getFields();
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
    final ArrayList<Field> sortedFields = new ArrayList<>();
    for (final Field f : fields) {
      final List<Field> children = f.getChildren();
      final List<Field> sortedChildren = sortFields(children);
      if (children.equals(sortedChildren)) {
        sortedFields.add(f);
      } else {
        sortedFields.add(new Field(f.getName(), f.getFieldType(), sortedChildren));
      }
    }
    Collections.sort(sortedFields, new FieldAlignmentComparator());
    return sortedFields;
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

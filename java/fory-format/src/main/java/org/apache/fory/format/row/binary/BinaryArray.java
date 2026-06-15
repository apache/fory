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

import static org.apache.fory.format.type.DataTypes.PRIMITIVE_BOOLEAN_ARRAY_FIELD;
import static org.apache.fory.format.type.DataTypes.PRIMITIVE_BYTE_ARRAY_FIELD;
import static org.apache.fory.format.type.DataTypes.PRIMITIVE_DOUBLE_ARRAY_FIELD;
import static org.apache.fory.format.type.DataTypes.PRIMITIVE_FLOAT_ARRAY_FIELD;
import static org.apache.fory.format.type.DataTypes.PRIMITIVE_INT_ARRAY_FIELD;
import static org.apache.fory.format.type.DataTypes.PRIMITIVE_LONG_ARRAY_FIELD;
import static org.apache.fory.format.type.DataTypes.PRIMITIVE_SHORT_ARRAY_FIELD;

import java.math.BigDecimal;
import org.apache.fory.format.row.ArrayData;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.Field;
import org.apache.fory.memory.BitUtils;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.memory.NativeByteOrder;
import org.apache.fory.util.Preconditions;

/**
 * Each array has four parts(8-byte aligned):
 *
 * <p>{@code [numElements][validity bits][values or offset&length][variable length portion]}
 *
 * <p>numElements is int, but use 8-byte to align
 *
 * <p>Primitive type is always considered to be not null.
 */
public class BinaryArray extends UnsafeTrait implements ArrayData {
  // Row-format stores multi-byte primitive values in little-endian order. MemoryBuffer typed
  // array copies are native/raw copies, so big-endian runtimes must use element accessors.
  private static final boolean LITTLE_ENDIAN = NativeByteOrder.IS_LITTLE_ENDIAN;

  private final Field field;
  protected final int elementSize;
  private MemoryBuffer buffer;
  private int numElements;
  private int bitmapOffset;
  private int elementOffset;
  private int baseOffset;
  private int sizeInBytes;

  public BinaryArray(Field field) {
    this(field, elementSize(field));
    initializeExtData(1); // Only require at most one slot to cache the schema for array type.
  }

  /** Skips {@code extData}; subclass must override {@code getStruct(int, Field, int)}. */
  protected BinaryArray(Field field, int elementSize) {
    this.field = field;
    this.elementSize = elementSize;
  }

  private static int elementSize(Field field) {
    DataTypes.ListType listType = (DataTypes.ListType) field.type();
    int width = DataTypes.getTypeWidth(listType.valueType());
    // variable-length element type
    if (width < 0) {
      return 8;
    } else {
      return width;
    }
  }

  public void pointTo(MemoryBuffer buffer, int offset, int sizeInBytes) {
    this.buffer = buffer;
    this.baseOffset = offset;
    // Read the numElements of key array from the aligned first 8 bytes as int.
    final int numElements = readNumElements();
    assert numElements >= 0 : "numElements (" + numElements + ") should >= 0";
    this.numElements = numElements;
    this.sizeInBytes = sizeInBytes;
    this.bitmapOffset = bitmapOffset();
    this.elementOffset = elementOffset();
  }

  protected int readNumElements() {
    return (int) buffer.getInt64(baseOffset);
  }

  protected int elementOffset() {
    return baseOffset + calculateHeaderInBytes(this.numElements);
  }

  protected int bitmapOffset() {
    return baseOffset + 8;
  }

  public Field getField() {
    return field;
  }

  @Override
  public int numElements() {
    return numElements;
  }

  @Override
  public MemoryBuffer getBuffer() {
    return buffer;
  }

  public int getSizeInBytes() {
    return sizeInBytes;
  }

  @Override
  public int getBaseOffset() {
    return baseOffset;
  }

  @Override
  public void assertIndexIsValid(int ordinal) {
    assert ordinal >= 0 : "ordinal (" + ordinal + ") should >= 0";
    assert ordinal < numElements : "ordinal (" + ordinal + ") should < " + numElements;
  }

  @Override
  int getOffset(int ordinal) {
    return elementOffset + ordinal * elementSize;
  }

  @Override
  public void setNotNullAt(int ordinal) {
    assertIndexIsValid(ordinal);
    BitUtils.unset(buffer, bitmapOffset, ordinal);
  }

  @Override
  public void setNullAt(int ordinal) {
    BitUtils.set(buffer, bitmapOffset, ordinal);
    // we assume the corresponding column was already 0
    // or will be set to 0 later by the caller side
  }

  @Override
  public boolean isNullAt(int ordinal) {
    return BitUtils.isSet(buffer, bitmapOffset, ordinal);
  }

  @Override
  public BigDecimal getDecimal(int ordinal) {
    throw new UnsupportedOperationException();
  }

  @Override
  public BinaryRow getStruct(int ordinal) {
    DataTypes.ListType listType = (DataTypes.ListType) field.type();
    return getStruct(ordinal, listType.valueField(), 0);
  }

  @Override
  public BinaryArray getArray(int ordinal) {
    DataTypes.ListType listType = (DataTypes.ListType) field.type();
    return getArray(ordinal, listType.valueField());
  }

  @Override
  public BinaryMap getMap(int ordinal) {
    DataTypes.ListType listType = (DataTypes.ListType) field.type();
    return getMap(ordinal, listType.valueField());
  }

  @Override
  public void setDecimal(int ordinal, BigDecimal value) {
    throw new UnsupportedOperationException();
  }

  public byte[] toBytes() {
    return buffer.getBytes(baseOffset, sizeInBytes);
  }

  public boolean[] toBooleanArray() {
    boolean[] values = new boolean[numElements];
    buffer.copyToBooleanArray(elementOffset, values, 0, numElements);
    return values;
  }

  public byte[] toByteArray() {
    byte[] values = new byte[numElements];
    buffer.copyToByteArray(elementOffset, values, 0, numElements);
    return values;
  }

  public short[] toShortArray() {
    short[] values = new short[numElements];
    if (LITTLE_ENDIAN) {
      buffer.copyToShortArray(elementOffset, values, 0, numElements * 2);
    } else {
      for (int i = 0, offset = elementOffset; i < numElements; i++, offset += 2) {
        values[i] = buffer.getInt16(offset);
      }
    }
    return values;
  }

  public int[] toIntArray() {
    int[] values = new int[numElements];
    if (LITTLE_ENDIAN) {
      buffer.copyToIntArray(elementOffset, values, 0, numElements * 4);
    } else {
      for (int i = 0, offset = elementOffset; i < numElements; i++, offset += 4) {
        values[i] = buffer.getInt32(offset);
      }
    }
    return values;
  }

  public long[] toLongArray() {
    long[] values = new long[numElements];
    if (LITTLE_ENDIAN) {
      buffer.copyToLongArray(elementOffset, values, 0, numElements * 8);
    } else {
      for (int i = 0, offset = elementOffset; i < numElements; i++, offset += 8) {
        values[i] = buffer.getInt64(offset);
      }
    }
    return values;
  }

  public float[] toFloatArray() {
    float[] values = new float[numElements];
    if (LITTLE_ENDIAN) {
      buffer.copyToFloatArray(elementOffset, values, 0, numElements * 4);
    } else {
      for (int i = 0, offset = elementOffset; i < numElements; i++, offset += 4) {
        values[i] = buffer.getFloat32(offset);
      }
    }
    return values;
  }

  public double[] toDoubleArray() {
    double[] values = new double[numElements];
    if (LITTLE_ENDIAN) {
      buffer.copyToDoubleArray(elementOffset, values, 0, numElements * 8);
    } else {
      for (int i = 0, offset = elementOffset; i < numElements; i++, offset += 8) {
        values[i] = buffer.getFloat64(offset);
      }
    }
    return values;
  }

  @Override
  public ArrayData copy() {
    MemoryBuffer copyBuf = MemoryUtils.buffer(sizeInBytes);
    buffer.copyTo(baseOffset, copyBuf, 0, sizeInBytes);
    BinaryArray arrayCopy = newArray(field);
    arrayCopy.pointTo(copyBuf, 0, sizeInBytes);
    return arrayCopy;
  }

  @Override
  public String toString() {
    DataTypes.ListType listType = (DataTypes.ListType) field.type();
    Field valueField = listType.valueField();
    StringBuilder builder = new StringBuilder("[");
    for (int i = 0; i < numElements; i++) {
      if (i != 0) {
        builder.append(',');
      }
      builder.append(get(i, valueField));
    }
    builder.append(']');

    return builder.toString();
  }

  private static BinaryArray newPrimitiveArray(int length, Field field) {
    BinaryArray result = new BinaryArray(field);
    final long headerInBytes = calculateHeaderInBytes(length);
    final long valueRegionInBytes = result.elementSize * length;
    final long totalSize = headerInBytes + valueRegionInBytes;
    if (totalSize > Integer.MAX_VALUE) {
      throw new UnsupportedOperationException(
          "Cannot convert this array to binary format as " + "it's too big.");
    }

    final byte[] data = new byte[(int) totalSize];
    MemoryBuffer memoryBuffer = MemoryUtils.wrap(data);
    memoryBuffer.putInt64(0, length);
    result.pointTo(memoryBuffer, 0, (int) totalSize);
    return result;
  }

  public static BinaryArray fromPrimitiveArray(byte[] arr) {
    BinaryArray result = newPrimitiveArray(arr.length, PRIMITIVE_BYTE_ARRAY_FIELD);
    result.buffer.copyFromByteArray(result.elementOffset, arr, 0, arr.length);
    return result;
  }

  public static BinaryArray fromPrimitiveArray(boolean[] arr) {
    BinaryArray result = newPrimitiveArray(arr.length, PRIMITIVE_BOOLEAN_ARRAY_FIELD);
    result.buffer.copyFromBooleanArray(result.elementOffset, arr, 0, arr.length);
    return result;
  }

  public static BinaryArray fromPrimitiveArray(short[] arr) {
    BinaryArray result = newPrimitiveArray(arr.length, PRIMITIVE_SHORT_ARRAY_FIELD);
    if (LITTLE_ENDIAN) {
      result.buffer.copyFromShortArray(result.elementOffset, arr, 0, arr.length * 2);
    } else {
      for (int i = 0, offset = result.elementOffset; i < arr.length; i++, offset += 2) {
        result.buffer.putInt16(offset, arr[i]);
      }
    }
    return result;
  }

  public static BinaryArray fromPrimitiveArray(int[] arr) {
    BinaryArray result = newPrimitiveArray(arr.length, PRIMITIVE_INT_ARRAY_FIELD);
    if (LITTLE_ENDIAN) {
      result.buffer.copyFromIntArray(result.elementOffset, arr, 0, arr.length * 4);
    } else {
      for (int i = 0, offset = result.elementOffset; i < arr.length; i++, offset += 4) {
        result.buffer.putInt32(offset, arr[i]);
      }
    }
    return result;
  }

  public static BinaryArray fromPrimitiveArray(long[] arr) {
    BinaryArray result = newPrimitiveArray(arr.length, PRIMITIVE_LONG_ARRAY_FIELD);
    if (LITTLE_ENDIAN) {
      result.buffer.copyFromLongArray(result.elementOffset, arr, 0, arr.length * 8);
    } else {
      for (int i = 0, offset = result.elementOffset; i < arr.length; i++, offset += 8) {
        result.buffer.putInt64(offset, arr[i]);
      }
    }
    return result;
  }

  public static BinaryArray fromPrimitiveArray(float[] arr) {
    BinaryArray result = newPrimitiveArray(arr.length, PRIMITIVE_FLOAT_ARRAY_FIELD);
    if (LITTLE_ENDIAN) {
      result.buffer.copyFromFloatArray(result.elementOffset, arr, 0, arr.length * 4);
    } else {
      for (int i = 0, offset = result.elementOffset; i < arr.length; i++, offset += 4) {
        result.buffer.putFloat32(offset, arr[i]);
      }
    }
    return result;
  }

  public static BinaryArray fromPrimitiveArray(double[] arr) {
    BinaryArray result = newPrimitiveArray(arr.length, PRIMITIVE_DOUBLE_ARRAY_FIELD);
    if (LITTLE_ENDIAN) {
      result.buffer.copyFromDoubleArray(result.elementOffset, arr, 0, arr.length * 8);
    } else {
      for (int i = 0, offset = result.elementOffset; i < arr.length; i++, offset += 8) {
        result.buffer.putFloat64(offset, arr[i]);
      }
    }
    return result;
  }

  public static int calculateHeaderInBytes(int numElements) {
    return 8 + BitUtils.calculateBitmapWidthInBytes(numElements);
  }

  public static int[] getDimensions(BinaryArray array, int numDimensions) {
    Preconditions.checkArgument(numDimensions >= 1);
    if (array == null) {
      return null;
    }

    // use deep-first search to search to numDimensions-1 layer to get dimensions.
    int depth = 0;
    int[] dimensions = new int[numDimensions];
    int[] startFromLefts = new int[numDimensions];
    BinaryArray[] arrs = new BinaryArray[numDimensions]; // root to current node
    BinaryArray arr = array;
    while (depth < numDimensions) {
      arrs[depth] = arr;
      int numElems = arr.numElements();
      dimensions[depth] = numElems;
      if (depth == numDimensions - 1) {
        break;
      }
      boolean allNull = true;
      if (startFromLefts[depth] == numElems) {
        // this node's subtree has all be traversed, but no node has depth count to numDimensions-1.
        startFromLefts[depth] = 0;
        depth--;
        continue;
      }
      for (int i = startFromLefts[depth]; i < numElems; i++) {
        if (!arr.isNullAt(i)) {
          arr = arr.getArray(i);
          allNull = false;
          break;
        }
      }
      if (allNull) {
        // startFromLefts[depth-1] = 0;
        depth--; // move up to parent node
        startFromLefts[depth]++;
        arr = arrs[depth];
      } else {
        depth++;
      }
      if (depth <= 0) {
        return null;
      }
    }

    return dimensions;
  }
}

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

import static org.apache.fory.format.type.DataTypes.PRIMITIVE_BOOLEAN_ARRAY_FIELD;
import static org.apache.fory.format.type.DataTypes.PRIMITIVE_BYTE_ARRAY_FIELD;
import static org.apache.fory.format.type.DataTypes.PRIMITIVE_DOUBLE_ARRAY_FIELD;
import static org.apache.fory.format.type.DataTypes.PRIMITIVE_FLOAT_ARRAY_FIELD;
import static org.apache.fory.format.type.DataTypes.PRIMITIVE_INT_ARRAY_FIELD;
import static org.apache.fory.format.type.DataTypes.PRIMITIVE_LONG_ARRAY_FIELD;
import static org.apache.fory.format.type.DataTypes.PRIMITIVE_SHORT_ARRAY_FIELD;

import java.math.BigDecimal;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.fory.format.row.binary.BinaryArray;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.memory.Platform;

/**
 * Writer for binary array. See {@link BinaryArray}
 *
 * <p>Must call reset(numElements) before use this writer to writer an array every time.
 *
 * <p>Must call reset(numElements) before call fromPrimitiveArray methods, so we can write multiple
 * primitive array into one binary array, and keep api consistent, rather call reset in
 * fromPrimitiveArray.
 */
public class BinaryArrayWriter extends BinaryWriter {
  public static int MAX_ROUNDED_ARRAY_LENGTH = Integer.MAX_VALUE - 15;

  protected final Field field;
  protected final int elementSize;
  protected int numElements;
  protected int headerInBytes;

  /** Must call reset before using writer constructed by this constructor. */
  public BinaryArrayWriter(Field field) {
    // buffer size can grow
    this(field, MemoryUtils.buffer(64));
    super.startIndex = 0;
  }

  /**
   * Write data to writer's buffer.
   *
   * <p>Must call reset before using writer constructed by this constructor
   */
  public BinaryArrayWriter(Field field, BinaryWriter writer) {
    this(field, writer.buffer);
    writer.children.add(this);
    // Since we must call reset before use this writer,
    // there's no need to set `super.startIndex = writer.writerIndex();`
  }

  public BinaryArrayWriter(Field field, MemoryBuffer buffer) {
    this(field, buffer, 8, elementWidth(field));
  }

  private static int elementWidth(Field field) {
    int width = DataTypes.getTypeWidth(field.getChildren().get(0).getType());
    // variable-length element type
    if (width < 0) {
      return 8;
    } else {
      return width;
    }
  }

  protected BinaryArrayWriter(
      Field field, MemoryBuffer buffer, int bytesBeforeBitMap, int elementSize) {
    super(buffer, bytesBeforeBitMap);
    this.field = field;
    this.elementSize = elementSize;
  }

  /**
   * reset BinaryArrayWriter(ArrayType type, BinaryWriter writer) increase writerIndex, which
   * increase writer's writerIndex, we need to record writer's writerIndex before call reset, so we
   * can call writer's {@code setOffsetAndSize(int ordinal, int absoluteOffset, int size)} <em>Reset
   * will change writerIndex, please use it very carefully</em>.
   */
  public void reset(int numElements) {
    startIndex = writerIndex();
    this.numElements = numElements;
    this.headerInBytes = calculateHeaderInBytes();
    long dataSize = numElements * (long) elementSize;
    if (dataSize > MAX_ROUNDED_ARRAY_LENGTH) {
      throw new UnsupportedOperationException("Can't alloc binary array, it's too big");
    }
    int fixedPartInBytes = roundNumberOfBytesToNearestWord((int) dataSize);
    buffer.grow(headerInBytes + fixedPartInBytes);

    // Write numElements and clear out null bits to header
    int numElementsSize = writeNumElements();
    int end = startIndex + headerInBytes;
    for (int i = startIndex + numElementsSize; i < end; i += 1) {
      buffer.putByte(i, 0);
    }

    // fill 0 into reminder part of 8-bytes alignment
    for (int i = elementSize * numElements; i < fixedPartInBytes; i++) {
      buffer.putByte(startIndex + headerInBytes + i, (byte) 0);
    }
    resetAdvanceWriter(fixedPartInBytes);
  }

  protected void resetAdvanceWriter(int fixedPartInBytes) {
    buffer._increaseWriterIndexUnsafe(headerInBytes + fixedPartInBytes);
  }

  protected int writeNumElements() {
    // store numElements in header in aligned 8 byte, though numElements is 4 byte int
    buffer.putInt64(startIndex, numElements);
    return 8;
  }

  protected int calculateHeaderInBytes() {
    // numElements use 8 byte, nullBitsSizeInBytes use multiple of 8 byte
    return BinaryArray.calculateHeaderInBytes(numElements);
  }

  @Override
  public int getOffset(int ordinal) {
    return startIndex + headerInBytes + ordinal * elementSize;
  }

  @Override
  public void write(int ordinal, byte value) {
    setNotNullAt(ordinal);
    buffer.putByte(getOffset(ordinal), value);
  }

  @Override
  public void write(int ordinal, boolean value) {
    setNotNullAt(ordinal);
    buffer.putBoolean(getOffset(ordinal), value);
  }

  @Override
  public void write(int ordinal, short value) {
    setNotNullAt(ordinal);
    buffer.putInt16(getOffset(ordinal), value);
  }

  @Override
  public void write(int ordinal, int value) {
    setNotNullAt(ordinal);
    buffer.putInt32(getOffset(ordinal), value);
  }

  @Override
  public void write(int ordinal, float value) {
    setNotNullAt(ordinal);
    buffer.putFloat32(getOffset(ordinal), value);
  }

  @Override
  public void write(int ordinal, BigDecimal value) {
    writeDecimal(ordinal, value, (ArrowType.Decimal) field.getChildren().get(0).getType());
  }

  protected void primitiveArrayAdvance(int size) {
    // no need to increasewriterIndex, because reset has already increased writerIndex
  }

  private void fromPrimitiveArray(Object arr, int offset, int numElements, Field type) {
    if (DataTypes.getTypeId(type.getChildren().get(0).getType())
        != DataTypes.getTypeId(this.field.getChildren().get(0).getType())) {
      String msg =
          String.format(
              "Element type %s is not %s",
              type.getChildren().get(0).getType(), this.field.getChildren().get(0).getType());
      throw new IllegalArgumentException(msg);
    }
    int size = numElements * elementSize;
    buffer.copyFromUnsafe(startIndex + headerInBytes, arr, offset, size);
    primitiveArrayAdvance(size);
  }

  public void fromPrimitiveArray(byte[] arr) {
    fromPrimitiveArray(arr, Platform.BYTE_ARRAY_OFFSET, arr.length, PRIMITIVE_BYTE_ARRAY_FIELD);
  }

  public void fromPrimitiveArray(boolean[] arr) {
    fromPrimitiveArray(
        arr, Platform.BOOLEAN_ARRAY_OFFSET, arr.length, PRIMITIVE_BOOLEAN_ARRAY_FIELD);
  }

  public void fromPrimitiveArray(short[] arr) {
    fromPrimitiveArray(arr, Platform.SHORT_ARRAY_OFFSET, arr.length, PRIMITIVE_SHORT_ARRAY_FIELD);
  }

  public void fromPrimitiveArray(int[] arr) {
    fromPrimitiveArray(arr, Platform.INT_ARRAY_OFFSET, arr.length, PRIMITIVE_INT_ARRAY_FIELD);
  }

  public void fromPrimitiveArray(long[] arr) {
    fromPrimitiveArray(arr, Platform.LONG_ARRAY_OFFSET, arr.length, PRIMITIVE_LONG_ARRAY_FIELD);
  }

  public void fromPrimitiveArray(float[] arr) {
    fromPrimitiveArray(arr, Platform.FLOAT_ARRAY_OFFSET, arr.length, PRIMITIVE_FLOAT_ARRAY_FIELD);
  }

  public void fromPrimitiveArray(double[] arr) {
    fromPrimitiveArray(arr, Platform.DOUBLE_ARRAY_OFFSET, arr.length, PRIMITIVE_DOUBLE_ARRAY_FIELD);
  }

  public BinaryArray toArray() {
    BinaryArray array = newArray();
    int size = size();
    array.pointTo(buffer, startIndex, size);
    return array;
  }

  public BinaryArray newArray() {
    return new BinaryArray(field);
  }

  public Field getField() {
    return field;
  }
}

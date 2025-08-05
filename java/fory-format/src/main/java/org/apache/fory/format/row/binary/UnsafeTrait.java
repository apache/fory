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

import static org.apache.fory.util.DecimalUtils.DECIMAL_BYTE_LENGTH;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.arrow.vector.util.DecimalUtility;
import org.apache.fory.format.row.Getters;
import org.apache.fory.format.row.Setters;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.vectorized.ArrowUtils;
import org.apache.fory.memory.MemoryBuffer;

/** Internal to binary row format to reuse code, don't use it in anywhere else. */
abstract class UnsafeTrait implements Getters, Setters {
  private Object[] extData;

  abstract MemoryBuffer getBuffer();

  @Override
  public MemoryBuffer getBuffer(int ordinal) {
    if (isNullAt(ordinal)) {
      return null;
    }
    final long offsetAndSize = getInt64(ordinal);
    final int relativeOffset = (int) (offsetAndSize >> 32);
    final int size = (int) offsetAndSize;
    return getBuffer().slice(getBaseOffset() + relativeOffset, size);
  }

  abstract int getBaseOffset();

  abstract void assertIndexIsValid(int index);

  abstract int getOffset(int ordinal);

  void initializeExtData(int numSlots) {
    extData = new Object[numSlots];
  }

  // ###########################################################
  // ####################### getters #######################
  // ###########################################################

  public boolean getBoolean(int ordinal) {
    assertIndexIsValid(ordinal);
    return getBuffer().getBoolean(getOffset(ordinal));
  }

  public byte getByte(int ordinal) {
    assertIndexIsValid(ordinal);
    return getBuffer().getByte(getOffset(ordinal));
  }

  public short getInt16(int ordinal) {
    assertIndexIsValid(ordinal);
    return getBuffer().getInt16(getOffset(ordinal));
  }

  public int getInt32(int ordinal) {
    assertIndexIsValid(ordinal);
    return getBuffer().getInt32(getOffset(ordinal));
  }

  public long getInt64(int ordinal) {
    assertIndexIsValid(ordinal);
    return getBuffer().getInt64(getOffset(ordinal));
  }

  public float getFloat32(int ordinal) {
    assertIndexIsValid(ordinal);
    return getBuffer().getFloat32(getOffset(ordinal));
  }

  public double getFloat64(int ordinal) {
    assertIndexIsValid(ordinal);
    return getBuffer().getFloat64(getOffset(ordinal));
  }

  public int getDate(int ordinal) {
    assertIndexIsValid(ordinal);
    return getBuffer().getInt32(getOffset(ordinal));
  }

  public long getTimestamp(int ordinal) {
    assertIndexIsValid(ordinal);
    return getBuffer().getInt64(getOffset(ordinal));
  }

  // TODO when length of string utf-8 bytes is less than 8, store it in fixed-width region. Use one
  // bit as mark
  @Override
  public String getString(int ordinal) {
    byte[] bytes = getBinary(ordinal);
    if (bytes != null) {
      return new String(bytes, StandardCharsets.UTF_8);
    } else {
      return null;
    }
  }

  @Override
  public byte[] getBinary(int ordinal) {
    if (isNullAt(ordinal)) {
      return null;
    } else {
      final long offsetAndSize = getInt64(ordinal);
      final int relativeOffset = (int) (offsetAndSize >> 32);
      final int size = (int) offsetAndSize;
      final byte[] bytes = new byte[size];
      getBuffer().get(getBaseOffset() + relativeOffset, bytes, 0, size);
      return bytes;
    }
  }

  BigDecimal getDecimal(int ordinal, ArrowType.Decimal decimalType) {
    if (isNullAt(ordinal)) {
      return null;
    }
    MemoryBuffer buffer = getBuffer(ordinal);
    ArrowBuf arrowBuf = ArrowUtils.decimalArrowBuf();
    buffer.copyToDirectUnsafe(0, arrowBuf.memoryAddress(), DECIMAL_BYTE_LENGTH);
    BigDecimal decimal =
        DecimalUtility.getBigDecimalFromArrowBuf(
            arrowBuf, 0, decimalType.getScale(), DECIMAL_BYTE_LENGTH);
    return decimal;
  }

  /**
   * Gets the field at a specific ordinal as a struct.
   *
   * @param ordinal the ordinal position of this field.
   * @param field the Arrow field corresponding to this struct.
   * @param extDataSlot the ext data slot used to cache the schema for the struct.
   * @return the binary row representation of the struct.
   */
  BinaryRow getStruct(int ordinal, Field field, int extDataSlot) {
    if (isNullAt(ordinal)) {
      return null;
    }
    final long offsetAndSize = getInt64(ordinal);
    final int relativeOffset = (int) (offsetAndSize >> 32);
    final int size = (int) offsetAndSize;
    if (extData[extDataSlot] == null) {
      extData[extDataSlot] = DataTypes.createSchema(field);
    }
    BinaryRow row = new BinaryRow((Schema) extData[extDataSlot]);
    row.pointTo(getBuffer(), getBaseOffset() + relativeOffset, size);
    return row;
  }

  BinaryArray getArray(int ordinal, Field field) {
    if (isNullAt(ordinal)) {
      return null;
    }
    final long offsetAndSize = getInt64(ordinal);
    final int relativeOffset = (int) (offsetAndSize >> 32);
    final int size = (int) offsetAndSize;
    BinaryArray array = new BinaryArray(field);
    array.pointTo(getBuffer(), getBaseOffset() + relativeOffset, size);
    return array;
  }

  BinaryMap getMap(int ordinal, Field field) {
    if (isNullAt(ordinal)) {
      return null;
    }
    final long offsetAndSize = getInt64(ordinal);
    final int relativeOffset = (int) (offsetAndSize >> 32);
    final int size = (int) offsetAndSize;
    BinaryMap map = new BinaryMap(field);
    map.pointTo(getBuffer(), getBaseOffset() + relativeOffset, size);
    return map;
  }

  // ###########################################################
  // ####################### setters #######################
  // ###########################################################

  @Override
  public void setBoolean(int ordinal, boolean value) {
    assertIndexIsValid(ordinal);
    setNotNullAt(ordinal);
    getBuffer().putBoolean(getOffset(ordinal), value);
  }

  @Override
  public void setByte(int ordinal, byte value) {
    assertIndexIsValid(ordinal);
    setNotNullAt(ordinal);
    getBuffer().putByte(getOffset(ordinal), value);
  }

  protected abstract void setNotNullAt(int ordinal);

  @Override
  public void setInt16(int ordinal, short value) {
    assertIndexIsValid(ordinal);
    setNotNullAt(ordinal);
    getBuffer().putInt16(getOffset(ordinal), value);
  }

  @Override
  public void setInt32(int ordinal, int value) {
    assertIndexIsValid(ordinal);
    setNotNullAt(ordinal);
    getBuffer().putInt32(getOffset(ordinal), value);
  }

  @Override
  public void setInt64(int ordinal, long value) {
    assertIndexIsValid(ordinal);
    setNotNullAt(ordinal);
    getBuffer().putInt64(getOffset(ordinal), value);
  }

  @Override
  public void setFloat32(int ordinal, float value) {
    assertIndexIsValid(ordinal);
    setNotNullAt(ordinal);
    getBuffer().putFloat32(getOffset(ordinal), value);
  }

  @Override
  public void setFloat64(int ordinal, double value) {
    assertIndexIsValid(ordinal);
    setNotNullAt(ordinal);
    getBuffer().putFloat64(getOffset(ordinal), value);
  }

  @Override
  public void setDate(int ordinal, int value) {
    assertIndexIsValid(ordinal);
    setNotNullAt(ordinal);
    getBuffer().putInt32(getOffset(ordinal), value);
  }

  @Override
  public void setTimestamp(int ordinal, long value) {
    assertIndexIsValid(ordinal);
    setNotNullAt(ordinal);
    getBuffer().putInt64(getOffset(ordinal), value);
  }
}

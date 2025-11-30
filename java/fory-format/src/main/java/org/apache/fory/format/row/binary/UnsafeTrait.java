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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.apache.fory.format.row.Getters;
import org.apache.fory.format.row.Setters;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.Field;
import org.apache.fory.format.type.Schema;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.util.DecimalUtils;

/** Internal to binary row format to reuse code, don't use it in anywhere else. */
abstract class UnsafeTrait implements Getters, Setters {
  protected Object[] extData;

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

  @Override
  public boolean getBoolean(int ordinal) {
    assertIndexIsValid(ordinal);
    return getBuffer().getBoolean(getOffset(ordinal));
  }

  @Override
  public byte getByte(int ordinal) {
    assertIndexIsValid(ordinal);
    return getBuffer().getByte(getOffset(ordinal));
  }

  @Override
  public short getInt16(int ordinal) {
    assertIndexIsValid(ordinal);
    return getBuffer().getInt16(getOffset(ordinal));
  }

  @Override
  public int getInt32(int ordinal) {
    assertIndexIsValid(ordinal);
    return getBuffer().getInt32(getOffset(ordinal));
  }

  @Override
  public long getInt64(int ordinal) {
    assertIndexIsValid(ordinal);
    return getBuffer().getInt64(getOffset(ordinal));
  }

  @Override
  public float getFloat32(int ordinal) {
    assertIndexIsValid(ordinal);
    return getBuffer().getFloat32(getOffset(ordinal));
  }

  @Override
  public double getFloat64(int ordinal) {
    assertIndexIsValid(ordinal);
    return getBuffer().getFloat64(getOffset(ordinal));
  }

  @Override
  public int getDate(int ordinal) {
    assertIndexIsValid(ordinal);
    return getBuffer().getInt32(getOffset(ordinal));
  }

  @Override
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

  BigDecimal getDecimal(int ordinal, DataTypes.DecimalType decimalType) {
    if (isNullAt(ordinal)) {
      return null;
    }
    MemoryBuffer buffer = getBuffer(ordinal);
    return readDecimal(buffer, 0, decimalType.scale());
  }

  /**
   * Reads a BigDecimal from the buffer at the given offset. The decimal is stored in little-endian
   * format with sign extension.
   */
  private static BigDecimal readDecimal(MemoryBuffer buffer, int offset, int scale) {
    byte[] bytes = new byte[DecimalUtils.DECIMAL_BYTE_LENGTH];
    // Read in little-endian format
    for (int i = 0; i < bytes.length; i++) {
      bytes[bytes.length - 1 - i] = buffer.getByte(offset + i);
    }
    java.math.BigInteger unscaledValue = new java.math.BigInteger(bytes);
    return new BigDecimal(unscaledValue, scale);
  }

  /**
   * Gets the field at a specific ordinal as a struct.
   *
   * @param ordinal the ordinal position of this field.
   * @param field the Fory field corresponding to this struct.
   * @param extDataSlot the ext data slot used to cache the schema for the struct.
   * @return the binary row representation of the struct.
   */
  protected BinaryRow getStruct(int ordinal, Field field, int extDataSlot) {
    if (isNullAt(ordinal)) {
      return null;
    }
    final long offsetAndSize = getInt64(ordinal);
    final int relativeOffset = (int) (offsetAndSize >> 32);
    final int size = (int) offsetAndSize;
    if (extData[extDataSlot] == null) {
      extData[extDataSlot] = newSchema(field);
    }
    BinaryRow row = newRow((Schema) extData[extDataSlot]);
    row.pointTo(getBuffer(), getBaseOffset() + relativeOffset, size);
    return row;
  }

  protected Schema newSchema(Field field) {
    return DataTypes.createSchema(field);
  }

  protected BinaryRow newRow(Schema schema) {
    return new BinaryRow(schema);
  }

  BinaryArray getArray(int ordinal, Field field) {
    if (isNullAt(ordinal)) {
      return null;
    }
    final long offsetAndSize = getInt64(ordinal);
    final int relativeOffset = (int) (offsetAndSize >> 32);
    final int size = (int) offsetAndSize;
    BinaryArray array = newArray(field);
    array.pointTo(getBuffer(), getBaseOffset() + relativeOffset, size);
    return array;
  }

  protected BinaryArray newArray(Field field) {
    return new BinaryArray(field);
  }

  BinaryMap getMap(int ordinal, Field field) {
    if (isNullAt(ordinal)) {
      return null;
    }
    final long offsetAndSize = getInt64(ordinal);
    final int relativeOffset = (int) (offsetAndSize >> 32);
    final int size = (int) offsetAndSize;
    BinaryMap map = newMap(field);
    map.pointTo(getBuffer(), getBaseOffset() + relativeOffset, size);
    return map;
  }

  protected BinaryMap newMap(Field field) {
    return new BinaryMap(field);
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

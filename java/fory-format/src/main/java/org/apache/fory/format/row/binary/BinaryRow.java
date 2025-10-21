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

import static org.apache.fory.util.Preconditions.checkArgument;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.fory.format.row.Row;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.memory.BitUtils;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.util.Preconditions;

/**
 * A binary implementation of {@link Row} backed by binary buffer instead of java objects.
 *
 * <ul>
 *   <li>Validity Bit Set Bitmap Region (1 bit/field) for tracking null values. Primitive type is
 *       always considered to be not null. Set bit to 1 indicate the value is not null, Set bit to 0
 *       indicate null
 *   <li>Fixed-Length 8-byte Values Region. if field isn't aligned, read any length-gt-1 value may
 *       need read multi times cache.
 *   <li>Variable-Length Data Section
 * </ul>
 *
 * <p>Equality comparison and hashing of rows can be performed on raw bytes since if two rows are
 * identical so should be their bit-wise representation.
 *
 * <ul>
 *   BinaryRow is inspired by Apache Spark tungsten UnsafeRow, the differences are
 *   <li>Use arrow schema to describe meta.
 *   <li>String support latin/utf16/utf8 encoding.
 *   <li>Decimal use arrow decimal format.
 *   <li>Variable-size field can be inline in fixed-size region if small enough.
 *   <li>Allow skip padding by generate Row using aot to put offsets in generated code.
 *   <li>The implementation support java/C++/python/etc..
 *   <li>Support adding fields without breaking compatibility
 * </ul>
 */
public class BinaryRow extends UnsafeTrait implements Row {
  final Schema schema;
  final int numFields;
  final int bitmapWidthInBytes;
  MemoryBuffer buffer;
  int baseOffset;
  int sizeInBytes;

  public BinaryRow(Schema schema) {
    this.schema = schema;
    this.numFields = schema.getFields().size();
    Preconditions.checkArgument(numFields > 0);
    this.bitmapWidthInBytes = computeBitmapWidthInBytes();
    initializeExtData(numFields);
  }

  protected int computeBitmapWidthInBytes() {
    return BitUtils.calculateBitmapWidthInBytes(numFields);
  }

  public void pointTo(MemoryBuffer buffer, int offset, int sizeInBytes) {
    this.buffer = buffer;
    this.baseOffset = offset;
    this.sizeInBytes = sizeInBytes;
  }

  @Override
  public Schema getSchema() {
    return schema;
  }

  @Override
  public int numFields() {
    return numFields;
  }

  public int getSizeInBytes() {
    return sizeInBytes;
  }

  @Override
  public int getBaseOffset() {
    return baseOffset;
  }

  @Override
  public MemoryBuffer getBuffer() {
    return buffer;
  }

  @Override
  public int getOffset(int ordinal) {
    return baseOffset + bitmapWidthInBytes + (ordinal << 3); // ordinal * 8 = (ordinal << 3)
  }

  @Override
  public void assertIndexIsValid(int index) {
    assert index >= 0 : "index (" + index + ") should >= 0";
    checkArgument(index < numFields, "index (%d) should < %d", index, numFields);
  }

  protected int nullBitmapOffset() {
    return baseOffset;
  }

  @Override
  public boolean isNullAt(int ordinal) {
    return BitUtils.isSet(buffer, nullBitmapOffset(), ordinal);
  }

  @Override
  public boolean anyNull() {
    return BitUtils.anySet(buffer, nullBitmapOffset(), bitmapWidthInBytes);
  }

  @Override
  public void setNullAt(int ordinal) {
    assertIndexIsValid(ordinal);
    BitUtils.set(buffer, nullBitmapOffset(), ordinal);
    assert DataTypes.getTypeWidth(schema.getFields().get(ordinal).getType()) > 0
        : "field[ "
            + ordinal
            + " "
            + schema.getFields().get(ordinal).getType()
            + " ] "
            + "must be fixed-width";
    // To preserve row equality, zero out the value when setting the column to null.
    // Since this row does not currently support updates to variable-length values, we don't
    // have to worry about zeroing out that data.
    buffer.putInt64(getOffset(ordinal), 0);
  }

  @Override
  public void setNotNullAt(int ordinal) {
    assertIndexIsValid(ordinal);
    BitUtils.unset(buffer, nullBitmapOffset(), ordinal);
  }

  @Override
  public BigDecimal getDecimal(int ordinal) {
    return getDecimal(ordinal, (ArrowType.Decimal) schema.getFields().get(ordinal).getType());
  }

  @Override
  public BinaryRow getStruct(int ordinal) {
    return getStruct(ordinal, schema.getFields().get(ordinal), ordinal);
  }

  @Override
  public BinaryArray getArray(int ordinal) {
    return getArray(ordinal, schema.getFields().get(ordinal));
  }

  @Override
  public BinaryMap getMap(int ordinal) {
    return getMap(ordinal, schema.getFields().get(ordinal));
  }

  @Override
  public Row copy() {
    MemoryBuffer copyBuf = MemoryUtils.buffer(sizeInBytes);
    buffer.copyTo(baseOffset, copyBuf, 0, sizeInBytes);
    BinaryRow copyRow = rowForCopy();
    copyRow.pointTo(copyBuf, 0, sizeInBytes);
    return copyRow;
  }

  protected BinaryRow rowForCopy() {
    return new BinaryRow(schema);
  }

  @Override
  public String toString() {
    if (buffer == null) {
      return "null";
    } else {
      StringBuilder build = new StringBuilder("{");
      for (int i = 0; i < numFields; i++) {
        if (i != 0) {
          build.append(", ");
        }
        Field field = schema.getFields().get(i);
        build.append(field.getName()).append("=");
        if (isNullAt(i)) {
          build.append("null");
        } else {
          build.append(get(i, field));
        }
      }

      build.append("}");
      return build.toString();
    }
  }

  public String toDebugString() {
    if (buffer == null) {
      return "null";
    } else {
      StringBuilder build = new StringBuilder();
      for (int i = 0; i < bitmapWidthInBytes + 8 * numFields; i += 8) {
        if (i != 0) {
          build.append(',');
        }
        build.append(Long.toHexString(buffer.getInt64(nullBitmapOffset() + i)));
      }
      return build.toString();
    }
  }

  public Map<String, Object> toMap() {
    Map<String, Object> map = new HashMap<>();
    for (int i = 0; i < numFields; i++) {
      Field field = schema.getFields().get(i);
      map.put(field.getName(), get(i, field));
    }
    return map;
  }

  public byte[] toBytes() {
    return buffer.getBytes(baseOffset, sizeInBytes);
  }

  /**
   * If it is a fixed-length field, we can call this BinaryRow's setXX method for in-place updates.
   * If it is variable-length field, can't use this method, because the underlying data is stored
   * continuously.
   */
  public static boolean isFixedLength(ArrowType type) {
    return DataTypes.getTypeWidth(type) > 0;
  }
}

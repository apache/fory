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

package org.apache.fory.format.row;

import java.math.BigDecimal;
import org.apache.fory.format.type.DataType;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.Field;
import org.apache.fory.memory.MemoryBuffer;

/**
 * Getter methods for row format. {@link #isNullAt(int)} must be checked before attempting to
 * retrieve a nullable value.
 */
public interface Getters {

  boolean isNullAt(int ordinal);

  boolean getBoolean(int ordinal);

  byte getByte(int ordinal);

  short getInt16(int ordinal);

  int getInt32(int ordinal);

  long getInt64(int ordinal);

  float getFloat32(int ordinal);

  double getFloat64(int ordinal);

  BigDecimal getDecimal(int ordinal);

  int getDate(int ordinal);

  long getTimestamp(int ordinal);

  String getString(int ordinal);

  byte[] getBinary(int ordinal);

  MemoryBuffer getBuffer(int ordinal);

  Row getStruct(int ordinal);

  ArrayData getArray(int ordinal);

  MapData getMap(int ordinal);

  default Object get(int ordinal, Field field) {
    if (isNullAt(ordinal)) {
      return null;
    }
    DataType type = field.type();
    int typeId = type.typeId();

    if (typeId == DataTypes.TYPE_BOOL) {
      return getBoolean(ordinal);
    } else if (typeId == DataTypes.TYPE_INT8) {
      return getByte(ordinal);
    } else if (typeId == DataTypes.TYPE_INT16) {
      return getInt16(ordinal);
    } else if (typeId == DataTypes.TYPE_INT32) {
      return getInt32(ordinal);
    } else if (typeId == DataTypes.TYPE_INT64) {
      return getInt64(ordinal);
    } else if (typeId == DataTypes.TYPE_FLOAT32) {
      return getFloat32(ordinal);
    } else if (typeId == DataTypes.TYPE_FLOAT64) {
      return getFloat64(ordinal);
    } else if (typeId == DataTypes.TYPE_DECIMAL) {
      return getDecimal(ordinal);
    } else if (typeId == DataTypes.TYPE_LOCAL_DATE) {
      return getDate(ordinal);
    } else if (typeId == DataTypes.TYPE_TIMESTAMP) {
      return getTimestamp(ordinal);
    } else if (typeId == DataTypes.TYPE_STRING) {
      return getString(ordinal);
    } else if (typeId == DataTypes.TYPE_BINARY) {
      return binaryToString(getBinary(ordinal));
    } else if (typeId == DataTypes.TYPE_STRUCT) {
      return getStruct(ordinal);
    } else if (typeId == DataTypes.TYPE_LIST) {
      return getArray(ordinal);
    } else if (typeId == DataTypes.TYPE_MAP) {
      return getMap(ordinal);
    } else {
      throw new UnsupportedOperationException("Unsupported type: " + type);
    }
  }

  private static String binaryToString(byte[] bytes) {
    if (bytes == null) {
      return null;
    }
    final int clampedLen = Math.min(bytes.length, 256);
    final StringBuilder result = new StringBuilder(clampedLen * 2 + 5);
    result.append("0x");
    for (int i = 0; i < clampedLen; i++) {
      final String hexStr = Integer.toHexString(bytes[i] & 0xff);
      if (hexStr.length() == 1) {
        result.append('0');
      }
      result.append(hexStr);
    }
    if (bytes.length > clampedLen) {
      result.append("...");
    }
    return result.toString();
  }
}

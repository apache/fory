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

import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.TypeResolutionContext;
import org.apache.fory.type.TypeUtils;

/** Util class for building generated binary encoder. */
@SuppressWarnings("UnstableApiUsage")
public class BinaryUtils {
  public static String getElemAccessMethodName(TypeRef<?> type, TypeResolutionContext ctx) {
    if (TypeUtils.PRIMITIVE_BYTE_TYPE.equals(type) || TypeUtils.BYTE_TYPE.equals(type)) {
      return "getByte";
    } else if (TypeUtils.PRIMITIVE_BOOLEAN_TYPE.equals(type)
        || TypeUtils.BOOLEAN_TYPE.equals(type)) {
      return "getBoolean";
    } else if (TypeUtils.PRIMITIVE_SHORT_TYPE.equals(type) || TypeUtils.SHORT_TYPE.equals(type)) {
      return "getInt16";
    } else if (TypeUtils.PRIMITIVE_INT_TYPE.equals(type)
        || TypeUtils.INT_TYPE.equals(type)
        || TypeUtils.OPTIONAL_INT_TYPE.equals(type)) {
      return "getInt32";
    } else if (TypeUtils.PRIMITIVE_LONG_TYPE.equals(type)
        || TypeUtils.LONG_TYPE.equals(type)
        || TypeUtils.OPTIONAL_LONG_TYPE.equals(type)) {
      return "getInt64";
    } else if (TypeUtils.PRIMITIVE_FLOAT_TYPE.equals(type) || TypeUtils.FLOAT_TYPE.equals(type)) {
      return "getFloat32";
    } else if (TypeUtils.PRIMITIVE_DOUBLE_TYPE.equals(type)
        || TypeUtils.DOUBLE_TYPE.equals(type)
        || TypeUtils.OPTIONAL_DOUBLE_TYPE.equals(type)) {
      return "getFloat64";
    } else if (TypeUtils.BIG_DECIMAL_TYPE.equals(type)) {
      return "getDecimal";
    } else if (TypeUtils.DATE_TYPE.equals(type)) {
      return "getDate";
    } else if (TypeUtils.TIMESTAMP_TYPE.equals(type)) {
      return "getTimestamp";
    } else if (TypeUtils.INSTANT_TYPE.equals(type)) {
      return "getInt64";
    } else if (TypeUtils.LOCAL_DATE_TYPE.equals(type)) {
      return "getInt32";
    } else if (TypeUtils.STRING_TYPE.equals(type)) {
      return "getString";
    } else if (isArray(type)) {
      // Since row-format serialize bytes as array data, instead of call `writer.write(int ordinal,
      // byte[] input)`,
      // we take BINARY_TYPE as byte[] array.
      return "getArray";
    } else if (TypeUtils.MAP_TYPE.isSupertypeOf(type)) {
      return "getMap";
    } else if (TypeUtils.isBean(type, ctx)) {
      return "getStruct";
    } else if (type.getRawType().isEnum()) {
      return "getString";
    } else {
      // take unknown type as OBJECT_TYPE, return as sliced MemoryBuffer
      // slice MemoryBuffer, then deserialize in EncodeExpressionBuilder.deserializeFor
      return "getBuffer";
    }
  }

  public static TypeRef<?> getElemReturnType(TypeRef<?> type, TypeResolutionContext ctx) {
    if (TypeUtils.PRIMITIVE_BYTE_TYPE.equals(type) || TypeUtils.BYTE_TYPE.equals(type)) {
      return TypeUtils.PRIMITIVE_BYTE_TYPE;
    } else if (TypeUtils.PRIMITIVE_BOOLEAN_TYPE.equals(type)
        || TypeUtils.BOOLEAN_TYPE.equals(type)) {
      return TypeUtils.PRIMITIVE_BOOLEAN_TYPE;
    } else if (TypeUtils.PRIMITIVE_SHORT_TYPE.equals(type) || TypeUtils.SHORT_TYPE.equals(type)) {
      return TypeUtils.PRIMITIVE_SHORT_TYPE;
    } else if (TypeUtils.PRIMITIVE_INT_TYPE.equals(type)
        || TypeUtils.INT_TYPE.equals(type)
        || TypeUtils.OPTIONAL_INT_TYPE.equals(type)) {
      return TypeUtils.PRIMITIVE_INT_TYPE;
    } else if (TypeUtils.PRIMITIVE_LONG_TYPE.equals(type)
        || TypeUtils.LONG_TYPE.equals(type)
        || TypeUtils.OPTIONAL_LONG_TYPE.equals(type)) {
      return TypeUtils.PRIMITIVE_LONG_TYPE;
    } else if (TypeUtils.PRIMITIVE_FLOAT_TYPE.equals(type) || TypeUtils.FLOAT_TYPE.equals(type)) {
      return TypeUtils.PRIMITIVE_FLOAT_TYPE;
    } else if (TypeUtils.PRIMITIVE_DOUBLE_TYPE.equals(type)
        || TypeUtils.DOUBLE_TYPE.equals(type)
        || TypeUtils.OPTIONAL_DOUBLE_TYPE.equals(type)) {
      return TypeUtils.PRIMITIVE_DOUBLE_TYPE;
    } else if (TypeUtils.BIG_DECIMAL_TYPE.equals(type)) {
      return TypeUtils.BIG_DECIMAL_TYPE;
    } else if (TypeUtils.DATE_TYPE.equals(type)) {
      return TypeUtils.INT_TYPE;
    } else if (TypeUtils.TIMESTAMP_TYPE.equals(type)) {
      return TypeUtils.LONG_TYPE;
    } else if (TypeUtils.INSTANT_TYPE.equals(type)) {
      return TypeUtils.LONG_TYPE;
    } else if (TypeUtils.LOCAL_DATE_TYPE.equals(type)) {
      return TypeUtils.INT_TYPE;
    } else if (TypeUtils.STRING_TYPE.equals(type)) {
      return TypeUtils.STRING_TYPE;
    } else if (isArray(type)) {
      // take BINARY_TYPE as array
      return TypeRef.of(BinaryArray.class);
    } else if (TypeUtils.MAP_TYPE.isSupertypeOf(type)) {
      return TypeRef.of(BinaryMap.class);
    } else if (TypeUtils.isBean(type, ctx)) {
      return TypeRef.of(BinaryRow.class);
    } else if (type.getRawType().isEnum()) {
      return TypeUtils.STRING_TYPE;
    } else {
      // take unknown type as OBJECT_TYPE, return as sliced MemoryBuffer
      // slice MemoryBuffer, then deserialize in EncodeExpressionBuilder.deserializeFor
      return TypeRef.of(MemoryBuffer.class);
    }
  }

  private static boolean isArray(TypeRef<?> type) {
    return type.isArray()
        || BinaryArray.class.equals(type.getRawType())
        || TypeUtils.ITERABLE_TYPE.isSupertypeOf(type);
  }
}

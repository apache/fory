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

package org.apache.fory.type;

import static org.apache.fory.collection.Collections.ofHashMap;

import java.util.Map;
import org.apache.fory.util.Preconditions;

public class Types {

  /** bool: a boolean value (true or false). */
  public static final int BOOL = 1;

  /** int8: a 8-bit signed integer. */
  public static final int INT8 = 2;

  /** int16: a 16-bit signed integer. */
  public static final int INT16 = 3;

  /** int32: a 32-bit signed integer. */
  public static final int INT32 = 4;

  /** var_int32: a 32-bit signed integer which uses fory var_int32 encoding. */
  public static final int VAR_INT32 = 5;

  /** int64: a 64-bit signed integer. */
  public static final int INT64 = 6;

  /** var_int64: a 64-bit signed integer which uses fory PVL encoding. */
  public static final int VAR_INT64 = 7;

  /** sli_int64: a 64-bit signed integer which uses fory SLI encoding. */
  public static final int SLI_INT64 = 8;

  /** float16: a 16-bit floating point number. */
  public static final int FLOAT16 = 9;

  /** float32: a 32-bit floating point number. */
  public static final int FLOAT32 = 10;

  /** float64: a 64-bit floating point number including NaN and Infinity. */
  public static final int FLOAT64 = 11;

  /** string: a text string encoded using Latin1/UTF16/UTF-8 encoding. */
  public static final int STRING = 12;

  /** enum: a data type consisting of a set of named values. */
  public static final int ENUM = 13;

  /** named_enum: an enum whose value will be serialized as the registered name. */
  public static final int NAMED_ENUM = 14;

  /**
   * A morphic(final) type serialized by Fory Struct serializer. i.e. it doesn't have subclasses.
   * Suppose we're deserializing {@code List<SomeClass>}, we can save dynamic serializer dispatch
   * since `SomeClass` is morphic(final).
   */
  public static final int STRUCT = 15;

  /** A morphic(final) type serialized by Fory compatible Struct serializer. */
  public static final int COMPATIBLE_STRUCT = 16;

  /** A `struct` whose type mapping will be encoded as a name. */
  public static final int NAMED_STRUCT = 17;

  /** A `compatible_struct` whose type mapping will be encoded as a name. */
  public static final int NAMED_COMPATIBLE_STRUCT = 18;

  /** A type which will be serialized by a customized serializer. */
  public static final int EXT = 19;

  /** An `ext` type whose type mapping will be encoded as a name. */
  public static final int NAMED_EXT = 20;

  /** A sequence of objects. */
  public static final int LIST = 21;

  /** An unordered set of unique elements. */
  public static final int SET = 22;

  /**
   * A map of key-value pairs. Mutable types such as `list/map/set/array/tensor/arrow` are not
   * allowed as key of map.
   */
  public static final int MAP = 23;

  /**
   * An absolute length of time, independent of any calendar/timezone, as a count of nanoseconds.
   */
  public static final int DURATION = 24;

  /**
   * A point in time, independent of any calendar/timezone, as a count of nanoseconds. The count is
   * relative to an epoch at UTC midnight on January 1, 1970.
   */
  public static final int TIMESTAMP = 25;

  /**
   * A naive date without timezone. The count is days relative to an epoch at UTC midnight on Jan 1,
   * 1970.
   */
  public static final int LOCAL_DATE = 26;

  /** Exact decimal value represented as an integer value in two's complement. */
  public static final int DECIMAL = 27;

  /** A variable-length array of bytes. */
  public static final int BINARY = 28;

  /**
   * A multidimensional array where every sub-array can have different sizes but all have the same
   * type. Only numeric components allowed. Other arrays will be taken as List. The implementation
   * should support interoperability between array and list.
   */
  public static final int ARRAY = 29;

  /** One dimensional bool array. */
  public static final int BOOL_ARRAY = 30;

  /** One dimensional int8 array. */
  public static final int INT8_ARRAY = 31;

  /** One dimensional int16 array. */
  public static final int INT16_ARRAY = 32;

  /** One dimensional int32 array. */
  public static final int INT32_ARRAY = 33;

  /** One dimensional int64 array. */
  public static final int INT64_ARRAY = 34;

  /** One dimensional half_float_16 array. */
  public static final int FLOAT16_ARRAY = 35;

  /** One dimensional float32 array. */
  public static final int FLOAT32_ARRAY = 36;

  /** One dimensional float64 array. */
  public static final int FLOAT64_ARRAY = 37;

  /** An (arrow record batch) object. */
  public static final int ARROW_RECORD_BATCH = 38;

  /** An (arrow table) object. */
  public static final int ARROW_TABLE = 39;

  public static final int UNKNOWN = 63;

  // Helper methods
  public static boolean isStructType(int value) {
    assert value < 0xff;
    return value == STRUCT
        || value == COMPATIBLE_STRUCT
        || value == NAMED_STRUCT
        || value == NAMED_COMPATIBLE_STRUCT;
  }

  public static boolean isExtType(int value) {
    assert value < 0xff;
    return value == EXT || value == NAMED_EXT;
  }

  public static boolean isEnumType(int value) {
    assert value < 0xff;
    return value == ENUM || value == NAMED_ENUM;
  }

  public static boolean isUserDefinedType(byte typeId) {
    return isStructType(typeId) || isExtType(typeId) || isEnumType(typeId);
  }

  private static final Map<Class, Integer> PRIMITIVE_TYPE_ID_MAP =
      ofHashMap(
          boolean.class, BOOL,
          byte.class, INT8,
          short.class, INT16,
          int.class, INT32,
          long.class, INT64,
          float.class, FLOAT32,
          double.class, FLOAT64);

  public static int getPrimitiveTypeId(Class<?> cls) {
    Preconditions.checkArgument(cls.isPrimitive(), "Class %s is not primitive", cls);
    return PRIMITIVE_TYPE_ID_MAP.getOrDefault(cls, -1);
  }

  public static boolean isPrimitiveType(int typeId) {
    // noinspection Duplicates
    switch (typeId) {
      case BOOL:
      case INT8:
      case INT16:
      case INT32:
      case INT64:
      case FLOAT32:
      case FLOAT64:
        return true;
      default:
        return false;
    }
  }

  public static boolean isPrimitiveArray(int typeId) {
    // noinspection Duplicates
    switch (typeId) {
      case BOOL_ARRAY:
      case INT8_ARRAY:
      case INT16_ARRAY:
      case INT32_ARRAY:
      case INT64_ARRAY:
      case FLOAT32_ARRAY:
      case FLOAT64_ARRAY:
        return true;
      default:
        return false;
    }
  }

  public static int getPrimitiveArrayTypeId(int typeId) {
    switch (typeId) {
      case BOOL:
        return BOOL_ARRAY;
      case INT8:
        return INT8_ARRAY;
      case INT16:
        return INT16_ARRAY;
      case INT32:
        return INT32_ARRAY;
      case INT64:
        return INT64_ARRAY;
      case FLOAT16:
        return FLOAT16_ARRAY;
      case FLOAT32:
        return FLOAT32_ARRAY;
      case FLOAT64:
        return FLOAT64_ARRAY;
      default:
        throw new IllegalArgumentException(
            String.format("Type id %d is not a primitive id", typeId));
    }
  }
}

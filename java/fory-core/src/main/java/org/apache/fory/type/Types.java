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

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import org.apache.fory.Fory;
import org.apache.fory.meta.TypeExtMeta;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.util.Preconditions;

public class Types {

  /** Unknown/polymorphic type marker. */
  public static final int UNKNOWN = 0;

  /** bool: a boolean value (true or false). */
  public static final int BOOL = 1;

  /** int8: an 8-bit signed integer. */
  public static final int INT8 = 2;

  /** int16: a 16-bit signed integer. */
  public static final int INT16 = 3;

  /** int32: a 32-bit signed integer. */
  public static final int INT32 = 4;

  /** varint32: a 32-bit signed integer which uses fory var_int32 encoding. */
  public static final int VARINT32 = 5;

  /** int64: a 64-bit signed integer. */
  public static final int INT64 = 6;

  /** varint64: a 64-bit signed integer which uses fory PVL encoding. */
  public static final int VARINT64 = 7;

  /** tagged_int64: a 64-bit signed integer which uses fory tagged encoding. */
  public static final int TAGGED_INT64 = 8;

  /** uint8: an 8-bit unsigned integer. */
  public static final int UINT8 = 9;

  /** uint16: a 16-bit unsigned integer. */
  public static final int UINT16 = 10;

  /** uint32: a 32-bit unsigned integer. */
  public static final int UINT32 = 11;

  /** var_uint32: a 32-bit unsigned integer which uses fory var_uint32 encoding. */
  public static final int VAR_UINT32 = 12;

  /** uint64: a 64-bit unsigned integer. */
  public static final int UINT64 = 13;

  /** var_uint64: a 64-bit unsigned integer which uses fory var_uint64 encoding. */
  public static final int VAR_UINT64 = 14;

  /** tagged_uint64: a 64-bit unsigned integer which uses fory tagged int64 encoding. */
  public static final int TAGGED_UINT64 = 15;

  /** float8: an 8-bit floating point number. */
  public static final int FLOAT8 = 16;

  /** float16: a 16-bit floating point number. */
  public static final int FLOAT16 = 17;

  /** bfloat16: a 16-bit brain floating point number. */
  public static final int BFLOAT16 = 18;

  /** float32: a 32-bit floating point number. */
  public static final int FLOAT32 = 19;

  /** float64: a 64-bit floating point number including NaN and Infinity. */
  public static final int FLOAT64 = 20;

  /** string: a text string encoded using Latin1/UTF16/UTF-8 encoding. */
  public static final int STRING = 21;

  /** A sequence of objects. */
  public static final int LIST = 22;

  /** An unordered set of unique elements. */
  public static final int SET = 23;

  /**
   * A map of key-value pairs. Mutable types such as `list/map/set/array/tensor/arrow` are not
   * allowed as key of map.
   */
  public static final int MAP = 24;

  /** enum: a data type consisting of a set of named values. */
  public static final int ENUM = 25;

  /** named_enum: an enum whose value will be serialized as the registered name. */
  public static final int NAMED_ENUM = 26;

  /**
   * A morphic(final) type serialized by Fory Struct serializer. i.e. it doesn't have subclasses.
   * Suppose we're deserializing {@code List<SomeClass>}, we can save dynamic serializer dispatch
   * since `SomeClass` is morphic(final).
   */
  public static final int STRUCT = 27;

  /** A morphic(final) type serialized by Fory compatible Struct serializer. */
  public static final int COMPATIBLE_STRUCT = 28;

  /** A `struct` whose type mapping will be encoded as a name. */
  public static final int NAMED_STRUCT = 29;

  /** A `compatible_struct` whose type mapping will be encoded as a name. */
  public static final int NAMED_COMPATIBLE_STRUCT = 30;

  /** A type which will be serialized by a customized serializer. */
  public static final int EXT = 31;

  /** An `ext` type whose type mapping will be encoded as a name. */
  public static final int NAMED_EXT = 32;

  /** A tagged union value whose schema identity is not embedded. */
  public static final int UNION = 33;

  /** A union value with embedded numeric union type ID. */
  public static final int TYPED_UNION = 34;

  /** A union value with embedded union type name/TypeDef. */
  public static final int NAMED_UNION = 35;

  /** Represents an empty/unit value with no data (e.g., for empty union alternatives). */
  public static final int NONE = 36;

  /**
   * An absolute length of time, independent of any calendar/timezone, as a count of nanoseconds.
   */
  public static final int DURATION = 37;

  /**
   * A point in time, independent of any calendar/timezone, as a count of nanoseconds. The count is
   * relative to an epoch at UTC midnight on January 1, 1970.
   */
  public static final int TIMESTAMP = 38;

  /**
   * A naive date without timezone. The count is days relative to an epoch at UTC midnight on Jan 1,
   * 1970.
   */
  public static final int DATE = 39;

  /** Exact decimal value represented as an integer value in two's complement. */
  public static final int DECIMAL = 40;

  /** A variable-length array of bytes. */
  public static final int BINARY = 41;

  /**
   * A multidimensional array where every sub-array can have different sizes but all have the same
   * type. Only numeric components allowed. Other arrays will be taken as List. The implementation
   * should support interoperability between array and list.
   */
  public static final int ARRAY = 42;

  /** One dimensional bool array. */
  public static final int BOOL_ARRAY = 43;

  /** One dimensional int8 array. */
  public static final int INT8_ARRAY = 44;

  /** One dimensional int16 array. */
  public static final int INT16_ARRAY = 45;

  /** One dimensional int32 array. */
  public static final int INT32_ARRAY = 46;

  /** One dimensional int64 array. */
  public static final int INT64_ARRAY = 47;

  /** One dimensional uint8 array. */
  public static final int UINT8_ARRAY = 48;

  /** One dimensional uint16 array. */
  public static final int UINT16_ARRAY = 49;

  /** One dimensional uint32 array. */
  public static final int UINT32_ARRAY = 50;

  /** One dimensional uint64 array. */
  public static final int UINT64_ARRAY = 51;

  /** One dimensional float8 array. */
  public static final int FLOAT8_ARRAY = 52;

  /** One dimensional float16 array. */
  public static final int FLOAT16_ARRAY = 53;

  /** One dimensional bfloat16 array. */
  public static final int BFLOAT16_ARRAY = 54;

  /** One dimensional float32 array. */
  public static final int FLOAT32_ARRAY = 55;

  /** One dimensional float64 array. */
  public static final int FLOAT64_ARRAY = 56;

  /** Bound value for range checks (types with id >= BOUND are not internal types). */
  public static final int BOUND = 64;

  public static final int INVALID_USER_TYPE_ID = -1;

  // Helper methods
  public static boolean isNamedType(int value) {
    switch (value) {
      case NAMED_STRUCT:
      case NAMED_COMPATIBLE_STRUCT:
      case NAMED_ENUM:
      case NAMED_EXT:
      case NAMED_UNION:
        return true;
      default:
        return false;
    }
  }

  /** Return true if type is user type and registered by id. */
  public static boolean isUserTypeRegisteredById(int typeId) {
    switch (typeId) {
      case Types.ENUM:
      case Types.STRUCT:
      case Types.COMPATIBLE_STRUCT:
      case Types.EXT:
      case Types.TYPED_UNION:
        return true;
      default:
        return false;
    }
  }

  public static boolean isStructType(int value) {
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

  public static boolean isUnionType(int value) {
    assert value < 0xff;
    return value == UNION || value == TYPED_UNION || value == NAMED_UNION;
  }

  public static boolean isUserDefinedType(int typeId) {
    return isStructType(typeId)
        || isExtType(typeId)
        || isEnumType(typeId)
        || typeId == UNION
        || typeId == TYPED_UNION
        || typeId == NAMED_UNION;
  }

  public static boolean isPrimitiveType(int typeId) {
    return typeId >= BOOL && typeId <= FLOAT64;
  }

  public static boolean isPrimitiveArray(int typeId) {
    // noinspection Duplicates
    switch (typeId) {
      case BOOL_ARRAY:
      case INT8_ARRAY:
      case INT16_ARRAY:
      case INT32_ARRAY:
      case INT64_ARRAY:
      case UINT8_ARRAY:
      case UINT16_ARRAY:
      case UINT32_ARRAY:
      case UINT64_ARRAY:
      case FLOAT8_ARRAY:
      case FLOAT16_ARRAY:
      case BFLOAT16_ARRAY:
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
      case VARINT32:
        return INT32_ARRAY;
      case INT64:
      case VARINT64:
        return INT64_ARRAY;
      case UINT8:
        return UINT8_ARRAY;
      case UINT16:
        return UINT16_ARRAY;
      case UINT32:
        return UINT32_ARRAY;
      case UINT64:
        return UINT64_ARRAY;
      case FLOAT8:
        return FLOAT8_ARRAY;
      case FLOAT16:
        return FLOAT16_ARRAY;
      case BFLOAT16:
        return BFLOAT16_ARRAY;
      case FLOAT32:
        return FLOAT32_ARRAY;
      case FLOAT64:
        return FLOAT64_ARRAY;
      default:
        throw new IllegalArgumentException(
            String.format("Type id %d is not a primitive id", typeId));
    }
  }

  public static int getDescriptorTypeId(Fory fory, Field field) {
    Annotation annotation = Descriptor.getAnnotation(field);
    Class<?> rawType = field.getType();
    if (annotation != null) {
      return TypeAnnotationUtils.getTypeId(annotation, rawType);
    } else {
      int unionTypeId = getUnionDescriptorTypeId(fory, rawType);
      if (unionTypeId != -1) {
        return unionTypeId;
      }
      return getTypeId(fory, rawType);
    }
  }

  public static int getDescriptorTypeId(Fory fory, Descriptor d) {
    TypeRef<?> typeRef = d.getTypeRef();
    TypeExtMeta extMeta = typeRef.getTypeExtMeta();
    if (extMeta != null) {
      return extMeta.typeId();
    } else {
      Class<?> rawType = typeRef.getRawType();
      Annotation typeAnnotation = d.getTypeAnnotation();
      if (typeAnnotation != null) {
        return TypeAnnotationUtils.getTypeId(typeAnnotation, rawType);
      } else {
        int unionTypeId = getUnionDescriptorTypeId(fory, rawType);
        if (unionTypeId != -1) {
          return unionTypeId;
        }
        return getTypeId(fory, rawType);
      }
    }
  }

  private static int getUnionDescriptorTypeId(Fory fory, Class<?> rawType) {
    TypeInfo typeInfo = fory.getTypeResolver().getTypeInfo(rawType, false);
    if (typeInfo == null) {
      return -1;
    }
    int typeId = typeInfo.getTypeId();
    if (Types.isUnionType(typeId)) {
      return Types.UNION;
    }
    return -1;
  }

  public static int getTypeId(Fory fory, Class<?> clz) {
    Class<?> unwrapped = TypeUtils.unwrap(clz);
    if (unwrapped == char.class) {
      Preconditions.checkArgument(!fory.isCrossLanguage(), "Char is not support for xlang");
      return clz.isPrimitive() ? ClassResolver.PRIMITIVE_CHAR_ID : ClassResolver.CHAR_ID;
    }
    if (unwrapped.isPrimitive()) {
      if (unwrapped == boolean.class) {
        return Types.BOOL;
      } else if (unwrapped == byte.class) {
        return Types.INT8;
      } else if (unwrapped == short.class) {
        return Types.INT16;
      } else if (unwrapped == int.class) {
        return fory.compressInt() ? Types.VARINT32 : Types.INT32;
      } else if (unwrapped == long.class) {
        return fory.compressLong() ? Types.VARINT64 : Types.INT64;
      } else if (unwrapped == float.class) {
        return Types.FLOAT32;
      } else if (unwrapped == double.class) {
        return Types.FLOAT64;
      }
    }
    TypeInfo typeInfo = fory.getTypeResolver().getTypeInfo(clz, false);
    if (typeInfo != null) {
      return typeInfo.getTypeId();
    }
    return Types.UNKNOWN;
  }

  public static Class<?> getClassForTypeId(int typeId) {
    switch (typeId) {
      case BOOL:
        return Boolean.class;
      case INT8:
      case UINT8:
        return Byte.class;
      case INT16:
      case UINT16:
        return Short.class;
      case INT32:
      case VARINT32:
      case UINT32:
      case VAR_UINT32:
        return Integer.class;
      case INT64:
      case VARINT64:
      case TAGGED_INT64:
      case UINT64:
      case VAR_UINT64:
      case TAGGED_UINT64:
        return Long.class;
      case FLOAT8:
      case BFLOAT16:
        return Float.class;
      case FLOAT16:
        return Float16.class;
      case FLOAT32:
        return Float.class;
      case FLOAT64:
        return Double.class;
      case STRING:
        return String.class;
      default:
        return null;
    }
  }

  public static boolean isCompressedType(int typeId) {
    switch (typeId) {
      case VARINT32:
      case VAR_UINT32:
      case VARINT64:
      case VAR_UINT64:
      case TAGGED_INT64:
      case TAGGED_UINT64:
        return true;
      default:
        return false;
    }
  }
}

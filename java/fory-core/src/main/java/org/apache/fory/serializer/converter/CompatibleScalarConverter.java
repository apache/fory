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

package org.apache.fory.serializer.converter;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.apache.fory.Fory;
import org.apache.fory.annotation.Internal;
import org.apache.fory.context.ReadContext;
import org.apache.fory.exception.DeserializationException;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.RefMode;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.serializer.FieldGroups.SerializationFieldInfo;
import org.apache.fory.type.BFloat16;
import org.apache.fory.type.DispatchId;
import org.apache.fory.type.Float16;
import org.apache.fory.type.unsigned.UInt16;
import org.apache.fory.type.unsigned.UInt32;
import org.apache.fory.type.unsigned.UInt64;
import org.apache.fory.type.unsigned.UInt8;

/** Cold compatible-field scalar conversion helpers. */
@Internal
final class CompatibleScalarConverter {
  private static final int NONE = 0;
  private static final int BOOL = 1;
  private static final int STRING = 2;
  private static final int SIGNED_INT = 3;
  private static final int UNSIGNED_INT = 4;
  private static final int FLOAT = 5;
  private static final int DECIMAL = 6;

  private static final BigInteger BIG_ZERO = BigInteger.ZERO;
  private static final BigInteger BIG_ONE = BigInteger.ONE;
  private static final BigInteger TWO_64 = BigInteger.ONE.shiftLeft(64);
  private static final BigInteger SIGNED_INT8_MIN = BigInteger.valueOf(Byte.MIN_VALUE);
  private static final BigInteger SIGNED_INT8_MAX = BigInteger.valueOf(Byte.MAX_VALUE);
  private static final BigInteger SIGNED_INT16_MIN = BigInteger.valueOf(Short.MIN_VALUE);
  private static final BigInteger SIGNED_INT16_MAX = BigInteger.valueOf(Short.MAX_VALUE);
  private static final BigInteger SIGNED_INT32_MIN = BigInteger.valueOf(Integer.MIN_VALUE);
  private static final BigInteger SIGNED_INT32_MAX = BigInteger.valueOf(Integer.MAX_VALUE);
  private static final BigInteger SIGNED_INT64_MIN = BigInteger.valueOf(Long.MIN_VALUE);
  private static final BigInteger SIGNED_INT64_MAX = BigInteger.valueOf(Long.MAX_VALUE);
  private static final BigInteger UNSIGNED_INT8_MAX = BigInteger.valueOf(0xFFL);
  private static final BigInteger UNSIGNED_INT16_MAX = BigInteger.valueOf(0xFFFFL);
  private static final BigInteger UNSIGNED_INT32_MAX = BigInteger.valueOf(0xFFFF_FFFFL);
  private static final BigInteger UNSIGNED_INT64_MAX = TWO_64.subtract(BigInteger.ONE);
  private static final BigDecimal DECIMAL_ZERO = BigDecimal.ZERO;
  private static final BigDecimal DECIMAL_ONE = BigDecimal.ONE;
  private static final int MAX_COMPATIBLE_DECIMAL_DIGITS = 256;
  private static final int MAX_COMPATIBLE_NUMERIC_TEXT_LENGTH = 320;

  private CompatibleScalarConverter() {}

  static int dispatchId(Class<?> type) {
    if (type == boolean.class || type == Boolean.class) {
      return DispatchId.BOOL;
    } else if (type == byte.class || type == Byte.class) {
      return DispatchId.INT8;
    } else if (type == short.class || type == Short.class) {
      return DispatchId.INT16;
    } else if (type == int.class || type == Integer.class) {
      return DispatchId.INT32;
    } else if (type == long.class || type == Long.class) {
      return DispatchId.INT64;
    } else if (type == float.class || type == Float.class) {
      return DispatchId.FLOAT32;
    } else if (type == double.class || type == Double.class) {
      return DispatchId.FLOAT64;
    } else if (type == String.class) {
      return DispatchId.STRING;
    } else if (type == UInt8.class) {
      return DispatchId.EXT_UINT8;
    } else if (type == UInt16.class) {
      return DispatchId.EXT_UINT16;
    } else if (type == UInt32.class) {
      return DispatchId.EXT_UINT32;
    } else if (type == UInt64.class) {
      return DispatchId.EXT_UINT64;
    } else if (type == Float16.class) {
      return DispatchId.FLOAT16;
    } else if (type == BFloat16.class) {
      return DispatchId.BFLOAT16;
    }
    return DispatchId.UNKNOWN;
  }

  static boolean isScalar(int dispatchId, Class<?> type) {
    return domain(dispatchId, type) != NONE;
  }

  static boolean canConvert(
      int fromDispatchId, Class<?> fromType, int toDispatchId, Class<?> toType) {
    int fromDomain = domain(fromDispatchId, fromType);
    int toDomain = domain(toDispatchId, toType);
    if (fromDomain == NONE || toDomain == NONE) {
      return false;
    }
    if (fromDomain == BOOL) {
      return toDomain == BOOL || toDomain == STRING || isNumeric(toDomain);
    }
    if (toDomain == BOOL) {
      return fromDomain == BOOL || fromDomain == STRING || isNumeric(fromDomain);
    }
    if (fromDomain == STRING) {
      return toDomain == STRING || isNumeric(toDomain);
    }
    if (toDomain == STRING) {
      return fromDomain == STRING || isNumeric(fromDomain);
    }
    return isNumeric(fromDomain) && isNumeric(toDomain);
  }

  static boolean sameScalar(
      int fromDispatchId, Class<?> fromType, int toDispatchId, Class<?> toType) {
    int fromDomain = domain(fromDispatchId, fromType);
    if (fromDomain == NONE || fromDomain != domain(toDispatchId, toType)) {
      return false;
    }
    if (fromDomain == SIGNED_INT || fromDomain == UNSIGNED_INT) {
      return integerBits(fromDispatchId, fromType) == integerBits(toDispatchId, toType)
          && fromDomain == domain(toDispatchId, toType);
    }
    if (fromDomain == FLOAT) {
      return normalizedFloatId(fromDispatchId, fromType) == normalizedFloatId(toDispatchId, toType);
    }
    return true;
  }

  static Object convert(
      int fromDispatchId,
      Class<?> fromType,
      int toDispatchId,
      Class<?> toType,
      Object value,
      String fieldName) {
    if (value == null) {
      return defaultValue(toDispatchId, toType);
    }
    int fromDomain = domain(fromDispatchId, fromType);
    int toDomain = domain(toDispatchId, toType);
    if (fromDomain == NONE || toDomain == NONE) {
      throw dataError(fromDispatchId, fromType, toDispatchId, toType, fieldName);
    }
    try {
      if (fromDomain == toDomain && sameScalar(fromDispatchId, fromType, toDispatchId, toType)) {
        return value;
      }
      if (toDomain == BOOL) {
        return toBool(fromDispatchId, fromType, fromDomain, value, toType, fieldName);
      }
      if (fromDomain == BOOL) {
        return fromBool((Boolean) value, toDispatchId, toType, toDomain, fieldName);
      }
      if (toDomain == STRING) {
        return toStringValue(fromDispatchId, fromType, fromDomain, value, fieldName);
      }
      if (fromDomain == STRING) {
        return fromString((String) value, toDispatchId, toType, toDomain, fieldName);
      }
      if (isNumeric(fromDomain) && isNumeric(toDomain)) {
        return convertNumber(
            fromDispatchId, fromType, fromDomain, value, toDispatchId, toType, toDomain, fieldName);
      }
    } catch (DeserializationException e) {
      throw e;
    } catch (RuntimeException e) {
      throw dataError(fromDispatchId, fromType, toDispatchId, toType, fieldName, e);
    }
    throw dataError(fromDispatchId, fromType, toDispatchId, toType, fieldName);
  }

  static boolean readBooleanTarget(
      ReadContext readContext, SerializationFieldInfo from, SerializationFieldInfo to) {
    MemoryBuffer buffer = readScalarBuffer(readContext, from);
    if (buffer == null) {
      return false;
    }
    return readBooleanTargetPayload(readContext, buffer, from, to);
  }

  static Boolean readBoxedBooleanTarget(
      ReadContext readContext, SerializationFieldInfo from, SerializationFieldInfo to) {
    MemoryBuffer buffer = readScalarBuffer(readContext, from);
    if (buffer == null) {
      return null;
    }
    return readBooleanTargetPayload(readContext, buffer, from, to);
  }

  static byte readByteTarget(
      ReadContext readContext, SerializationFieldInfo from, SerializationFieldInfo to) {
    MemoryBuffer buffer = readScalarBuffer(readContext, from);
    if (buffer == null) {
      return 0;
    }
    return (byte) readIntegerBitsTarget(readContext, buffer, from, to);
  }

  static Byte readBoxedByteTarget(
      ReadContext readContext, SerializationFieldInfo from, SerializationFieldInfo to) {
    MemoryBuffer buffer = readScalarBuffer(readContext, from);
    if (buffer == null) {
      return null;
    }
    return (byte) readIntegerBitsTarget(readContext, buffer, from, to);
  }

  static short readShortTarget(
      ReadContext readContext, SerializationFieldInfo from, SerializationFieldInfo to) {
    MemoryBuffer buffer = readScalarBuffer(readContext, from);
    if (buffer == null) {
      return 0;
    }
    return (short) readIntegerBitsTarget(readContext, buffer, from, to);
  }

  static Short readBoxedShortTarget(
      ReadContext readContext, SerializationFieldInfo from, SerializationFieldInfo to) {
    MemoryBuffer buffer = readScalarBuffer(readContext, from);
    if (buffer == null) {
      return null;
    }
    return (short) readIntegerBitsTarget(readContext, buffer, from, to);
  }

  static int readIntTarget(
      ReadContext readContext, SerializationFieldInfo from, SerializationFieldInfo to) {
    MemoryBuffer buffer = readScalarBuffer(readContext, from);
    if (buffer == null) {
      return 0;
    }
    return (int) readIntegerBitsTarget(readContext, buffer, from, to);
  }

  static Integer readBoxedIntTarget(
      ReadContext readContext, SerializationFieldInfo from, SerializationFieldInfo to) {
    MemoryBuffer buffer = readScalarBuffer(readContext, from);
    if (buffer == null) {
      return null;
    }
    return (int) readIntegerBitsTarget(readContext, buffer, from, to);
  }

  static long readLongTarget(
      ReadContext readContext, SerializationFieldInfo from, SerializationFieldInfo to) {
    MemoryBuffer buffer = readScalarBuffer(readContext, from);
    if (buffer == null) {
      return 0L;
    }
    return readIntegerBitsTarget(readContext, buffer, from, to);
  }

  static Long readBoxedLongTarget(
      ReadContext readContext, SerializationFieldInfo from, SerializationFieldInfo to) {
    MemoryBuffer buffer = readScalarBuffer(readContext, from);
    if (buffer == null) {
      return null;
    }
    return readIntegerBitsTarget(readContext, buffer, from, to);
  }

  static float readFloatTarget(
      ReadContext readContext, SerializationFieldInfo from, SerializationFieldInfo to) {
    MemoryBuffer buffer = readScalarBuffer(readContext, from);
    if (buffer == null) {
      return 0.0f;
    }
    return readFloatTargetPayload(readContext, buffer, from, to);
  }

  static Float readBoxedFloatTarget(
      ReadContext readContext, SerializationFieldInfo from, SerializationFieldInfo to) {
    MemoryBuffer buffer = readScalarBuffer(readContext, from);
    if (buffer == null) {
      return null;
    }
    return readFloatTargetPayload(readContext, buffer, from, to);
  }

  static double readDoubleTarget(
      ReadContext readContext, SerializationFieldInfo from, SerializationFieldInfo to) {
    MemoryBuffer buffer = readScalarBuffer(readContext, from);
    if (buffer == null) {
      return 0.0d;
    }
    return readDoubleTargetPayload(readContext, buffer, from, to);
  }

  static Double readBoxedDoubleTarget(
      ReadContext readContext, SerializationFieldInfo from, SerializationFieldInfo to) {
    MemoryBuffer buffer = readScalarBuffer(readContext, from);
    if (buffer == null) {
      return null;
    }
    return readDoubleTargetPayload(readContext, buffer, from, to);
  }

  static String readStringTarget(
      ReadContext readContext, SerializationFieldInfo from, SerializationFieldInfo to) {
    MemoryBuffer buffer = readScalarBuffer(readContext, from);
    if (buffer == null) {
      return null;
    }
    String fieldName = to.qualifiedFieldName;
    int fromDomain = domain(from.dispatchId, from.type);
    switch (fromDomain) {
      case BOOL:
        return readBool(buffer, from.dispatchId, from.type, fieldName) ? "true" : "false";
      case STRING:
        return readContext.readString();
      case SIGNED_INT:
      case UNSIGNED_INT:
        return readIntegerSource(buffer, from, fieldName).toString();
      case DECIMAL:
        return decimalText(readDecimalSource(readContext, from));
      case FLOAT:
        return readFloatText(buffer, from, fieldName);
      default:
        throw dataError(from.dispatchId, from.type, to.dispatchId, to.type, fieldName);
    }
  }

  static BigDecimal readDecimalTarget(
      ReadContext readContext, SerializationFieldInfo from, SerializationFieldInfo to) {
    MemoryBuffer buffer = readScalarBuffer(readContext, from);
    if (buffer == null) {
      return null;
    }
    return readNumericDecimal(readContext, buffer, from, to.qualifiedFieldName);
  }

  static UInt8 readUInt8Target(
      ReadContext readContext, SerializationFieldInfo from, SerializationFieldInfo to) {
    MemoryBuffer buffer = readScalarBuffer(readContext, from);
    if (buffer == null) {
      return null;
    }
    BigInteger value = readIntegerTargetPayload(readContext, buffer, from, to);
    return UInt8.valueOf(value.intValue());
  }

  static UInt16 readUInt16Target(
      ReadContext readContext, SerializationFieldInfo from, SerializationFieldInfo to) {
    MemoryBuffer buffer = readScalarBuffer(readContext, from);
    if (buffer == null) {
      return null;
    }
    BigInteger value = readIntegerTargetPayload(readContext, buffer, from, to);
    return UInt16.valueOf(value.intValue());
  }

  static UInt32 readUInt32Target(
      ReadContext readContext, SerializationFieldInfo from, SerializationFieldInfo to) {
    MemoryBuffer buffer = readScalarBuffer(readContext, from);
    if (buffer == null) {
      return null;
    }
    BigInteger value = readIntegerTargetPayload(readContext, buffer, from, to);
    return UInt32.valueOf(value.intValue());
  }

  static UInt64 readUInt64Target(
      ReadContext readContext, SerializationFieldInfo from, SerializationFieldInfo to) {
    MemoryBuffer buffer = readScalarBuffer(readContext, from);
    if (buffer == null) {
      return null;
    }
    BigInteger value = readIntegerTargetPayload(readContext, buffer, from, to);
    return UInt64.valueOf(value.longValue());
  }

  static Float16 readFloat16Target(
      ReadContext readContext, SerializationFieldInfo from, SerializationFieldInfo to) {
    MemoryBuffer buffer = readScalarBuffer(readContext, from);
    if (buffer == null) {
      return null;
    }
    return readFloat16TargetPayload(readContext, buffer, from, to);
  }

  static BFloat16 readBFloat16Target(
      ReadContext readContext, SerializationFieldInfo from, SerializationFieldInfo to) {
    MemoryBuffer buffer = readScalarBuffer(readContext, from);
    if (buffer == null) {
      return null;
    }
    return readBFloat16TargetPayload(readContext, buffer, from, to);
  }

  static boolean readBool(
      MemoryBuffer buffer, int fromDispatchId, Class<?> fromType, String fieldName) {
    byte raw = buffer.readByte();
    if (raw == 0) {
      return false;
    }
    if (raw == 1) {
      return true;
    }
    throw dataError(fromDispatchId, fromType, DispatchId.BOOL, Boolean.class, fieldName);
  }

  private static MemoryBuffer readScalarBuffer(
      ReadContext readContext, SerializationFieldInfo from) {
    if (from.refMode == RefMode.TRACKING) {
      throw new DeserializationException(
          "Reference-tracked scalar conversion is schema incompatible for "
              + from.qualifiedFieldName);
    }
    MemoryBuffer buffer = readContext.getBuffer();
    if (from.refMode == RefMode.NULL_ONLY) {
      byte flag = buffer.readByte();
      if (flag == Fory.NULL_FLAG) {
        return null;
      }
      if (flag != Fory.NOT_NULL_VALUE_FLAG) {
        throw new DeserializationException(
            "Invalid nullable compatible scalar field flag "
                + flag
                + " for "
                + from.qualifiedFieldName);
      }
    }
    return buffer;
  }

  private static boolean readBooleanTargetPayload(
      ReadContext readContext,
      MemoryBuffer buffer,
      SerializationFieldInfo from,
      SerializationFieldInfo to) {
    String fieldName = to.qualifiedFieldName;
    switch (domain(from.dispatchId, from.type)) {
      case BOOL:
        return readBool(buffer, from.dispatchId, from.type, fieldName);
      case STRING:
        return boolFromString(readContext.readString(), from, to);
      case SIGNED_INT:
      case UNSIGNED_INT:
        return boolFromInteger(readIntegerSource(buffer, from, fieldName), from, to);
      case DECIMAL:
        return boolFromDecimal(readDecimalSource(readContext, from), from, to);
      case FLOAT:
        return readFloatBool(buffer, from, to);
      default:
        throw dataError(from.dispatchId, from.type, to.dispatchId, to.type, fieldName);
    }
  }

  private static boolean boolFromString(
      String text, SerializationFieldInfo from, SerializationFieldInfo to) {
    if ("0".equals(text) || "false".equals(text)) {
      return false;
    }
    if ("1".equals(text) || "true".equals(text)) {
      return true;
    }
    throw dataError(from.dispatchId, from.type, DispatchId.BOOL, to.type, to.qualifiedFieldName);
  }

  private static boolean boolFromInteger(
      BigInteger value, SerializationFieldInfo from, SerializationFieldInfo to) {
    if (BIG_ZERO.equals(value)) {
      return false;
    }
    if (BIG_ONE.equals(value)) {
      return true;
    }
    throw dataError(from.dispatchId, from.type, DispatchId.BOOL, to.type, to.qualifiedFieldName);
  }

  private static boolean boolFromDecimal(
      BigDecimal value, SerializationFieldInfo from, SerializationFieldInfo to) {
    if (value.compareTo(DECIMAL_ZERO) == 0) {
      return false;
    }
    if (value.compareTo(DECIMAL_ONE) == 0) {
      return true;
    }
    throw dataError(from.dispatchId, from.type, DispatchId.BOOL, to.type, to.qualifiedFieldName);
  }

  private static BigInteger readIntegerTargetPayload(
      ReadContext readContext,
      MemoryBuffer buffer,
      SerializationFieldInfo from,
      SerializationFieldInfo to) {
    String fieldName = to.qualifiedFieldName;
    BigInteger value;
    switch (domain(from.dispatchId, from.type)) {
      case BOOL:
        value = readBool(buffer, from.dispatchId, from.type, fieldName) ? BIG_ONE : BIG_ZERO;
        break;
      case STRING:
        value =
            integerFromDecimal(parseDecimalString(readContext.readString(), from, to), from, to);
        break;
      case SIGNED_INT:
      case UNSIGNED_INT:
        value = readIntegerSource(buffer, from, fieldName);
        break;
      case DECIMAL:
        value = integerFromDecimal(readDecimalSource(readContext, from), from, to);
        break;
      case FLOAT:
        value = integerFromDecimal(readFiniteFloatDecimal(buffer, from, fieldName), from, to);
        break;
      default:
        throw dataError(from.dispatchId, from.type, to.dispatchId, to.type, fieldName);
    }
    checkIntegerRange(value, to.dispatchId, to.type, fieldName);
    return value;
  }

  private static long readIntegerBitsTarget(
      ReadContext readContext,
      MemoryBuffer buffer,
      SerializationFieldInfo from,
      SerializationFieldInfo to) {
    String fieldName = to.qualifiedFieldName;
    switch (domain(from.dispatchId, from.type)) {
      case BOOL:
        return checkedSignedIntegerBits(
            readBool(buffer, from.dispatchId, from.type, fieldName) ? 1L : 0L,
            to.dispatchId,
            to.type,
            fieldName);
      case STRING:
      case DECIMAL:
      case FLOAT:
        return readIntegerTargetPayload(readContext, buffer, from, to).longValue();
      case SIGNED_INT:
        return readSignedIntegerBitsTarget(buffer, from, to.dispatchId, to.type, fieldName);
      case UNSIGNED_INT:
        return readUnsignedIntegerBitsTarget(buffer, from, to.dispatchId, to.type, fieldName);
      default:
        throw dataError(from.dispatchId, from.type, to.dispatchId, to.type, fieldName);
    }
  }

  private static long readSignedIntegerBitsTarget(
      MemoryBuffer buffer,
      SerializationFieldInfo from,
      int toDispatchId,
      Class<?> toType,
      String fieldName) {
    long value;
    switch (from.dispatchId) {
      case DispatchId.INT8:
        value = buffer.readByte();
        break;
      case DispatchId.INT16:
        value = buffer.readInt16();
        break;
      case DispatchId.INT32:
        value = buffer.readInt32();
        break;
      case DispatchId.VARINT32:
        value = buffer.readVarInt32();
        break;
      case DispatchId.INT64:
        value = buffer.readInt64();
        break;
      case DispatchId.VARINT64:
        value = buffer.readVarInt64();
        break;
      case DispatchId.TAGGED_INT64:
        value = buffer.readTaggedInt64();
        break;
      default:
        throw dataError(from.dispatchId, from.type, toDispatchId, toType, fieldName);
    }
    return checkedSignedIntegerBits(value, toDispatchId, toType, fieldName);
  }

  private static long readUnsignedIntegerBitsTarget(
      MemoryBuffer buffer,
      SerializationFieldInfo from,
      int toDispatchId,
      Class<?> toType,
      String fieldName) {
    long value;
    boolean unsigned64 = false;
    switch (from.dispatchId) {
      case DispatchId.UINT8:
      case DispatchId.EXT_UINT8:
        value = buffer.readByte() & 0xFFL;
        break;
      case DispatchId.UINT16:
      case DispatchId.EXT_UINT16:
        value = buffer.readInt16() & 0xFFFFL;
        break;
      case DispatchId.UINT32:
      case DispatchId.EXT_UINT32:
        value = Integer.toUnsignedLong(buffer.readInt32());
        break;
      case DispatchId.VAR_UINT32:
      case DispatchId.EXT_VAR_UINT32:
        value = Integer.toUnsignedLong(buffer.readVarUInt32());
        break;
      case DispatchId.UINT64:
      case DispatchId.EXT_UINT64:
        value = buffer.readInt64();
        unsigned64 = true;
        break;
      case DispatchId.VAR_UINT64:
      case DispatchId.EXT_VAR_UINT64:
        value = buffer.readVarUInt64();
        unsigned64 = true;
        break;
      case DispatchId.TAGGED_UINT64:
        value = buffer.readTaggedUInt64();
        unsigned64 = true;
        break;
      default:
        throw dataError(from.dispatchId, from.type, toDispatchId, toType, fieldName);
    }
    return unsigned64
        ? checkedUnsigned64IntegerBits(value, toDispatchId, toType, fieldName)
        : checkedUnsignedIntegerBits(value, toDispatchId, toType, fieldName);
  }

  private static long checkedSignedIntegerBits(
      long value, int toDispatchId, Class<?> toType, String fieldName) {
    switch (normalizedIntegerId(toDispatchId, toType)) {
      case DispatchId.INT8:
        if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
          throwIntegerRangeError(toDispatchId, toType, fieldName);
        }
        return value;
      case DispatchId.INT16:
        if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
          throwIntegerRangeError(toDispatchId, toType, fieldName);
        }
        return value;
      case DispatchId.INT32:
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
          throwIntegerRangeError(toDispatchId, toType, fieldName);
        }
        return value;
      case DispatchId.INT64:
        return value;
      case DispatchId.UINT8:
        if (value < 0 || value > 0xFFL) {
          throwIntegerRangeError(toDispatchId, toType, fieldName);
        }
        return value;
      case DispatchId.UINT16:
        if (value < 0 || value > 0xFFFFL) {
          throwIntegerRangeError(toDispatchId, toType, fieldName);
        }
        return value;
      case DispatchId.UINT32:
        if (value < 0 || value > 0xFFFF_FFFFL) {
          throwIntegerRangeError(toDispatchId, toType, fieldName);
        }
        return value;
      case DispatchId.UINT64:
        if (value < 0) {
          throwIntegerRangeError(toDispatchId, toType, fieldName);
        }
        return value;
      default:
        throw dataError(DispatchId.UNKNOWN, BigInteger.class, toDispatchId, toType, fieldName);
    }
  }

  private static long checkedUnsignedIntegerBits(
      long value, int toDispatchId, Class<?> toType, String fieldName) {
    switch (normalizedIntegerId(toDispatchId, toType)) {
      case DispatchId.INT8:
        if (value > Byte.MAX_VALUE) {
          throwIntegerRangeError(toDispatchId, toType, fieldName);
        }
        return value;
      case DispatchId.INT16:
        if (value > Short.MAX_VALUE) {
          throwIntegerRangeError(toDispatchId, toType, fieldName);
        }
        return value;
      case DispatchId.INT32:
        if (value > Integer.MAX_VALUE) {
          throwIntegerRangeError(toDispatchId, toType, fieldName);
        }
        return value;
      case DispatchId.INT64:
      case DispatchId.UINT64:
        return value;
      case DispatchId.UINT8:
        if (value > 0xFFL) {
          throwIntegerRangeError(toDispatchId, toType, fieldName);
        }
        return value;
      case DispatchId.UINT16:
        if (value > 0xFFFFL) {
          throwIntegerRangeError(toDispatchId, toType, fieldName);
        }
        return value;
      case DispatchId.UINT32:
        if (value > 0xFFFF_FFFFL) {
          throwIntegerRangeError(toDispatchId, toType, fieldName);
        }
        return value;
      default:
        throw dataError(DispatchId.UNKNOWN, BigInteger.class, toDispatchId, toType, fieldName);
    }
  }

  private static long checkedUnsigned64IntegerBits(
      long value, int toDispatchId, Class<?> toType, String fieldName) {
    switch (normalizedIntegerId(toDispatchId, toType)) {
      case DispatchId.INT8:
        if (value < 0 || value > Byte.MAX_VALUE) {
          throwIntegerRangeError(toDispatchId, toType, fieldName);
        }
        return value;
      case DispatchId.INT16:
        if (value < 0 || value > Short.MAX_VALUE) {
          throwIntegerRangeError(toDispatchId, toType, fieldName);
        }
        return value;
      case DispatchId.INT32:
        if (value < 0 || value > Integer.MAX_VALUE) {
          throwIntegerRangeError(toDispatchId, toType, fieldName);
        }
        return value;
      case DispatchId.INT64:
        if (value < 0) {
          throwIntegerRangeError(toDispatchId, toType, fieldName);
        }
        return value;
      case DispatchId.UINT8:
        if (Long.compareUnsigned(value, 0xFFL) > 0) {
          throwIntegerRangeError(toDispatchId, toType, fieldName);
        }
        return value;
      case DispatchId.UINT16:
        if (Long.compareUnsigned(value, 0xFFFFL) > 0) {
          throwIntegerRangeError(toDispatchId, toType, fieldName);
        }
        return value;
      case DispatchId.UINT32:
        if (Long.compareUnsigned(value, 0xFFFF_FFFFL) > 0) {
          throwIntegerRangeError(toDispatchId, toType, fieldName);
        }
        return value;
      case DispatchId.UINT64:
        return value;
      default:
        throw dataError(DispatchId.UNKNOWN, BigInteger.class, toDispatchId, toType, fieldName);
    }
  }

  private static void throwIntegerRangeError(int toDispatchId, Class<?> toType, String fieldName) {
    throw dataError(DispatchId.UNKNOWN, BigInteger.class, toDispatchId, toType, fieldName);
  }

  private static BigInteger integerFromDecimal(
      BigDecimal decimal, SerializationFieldInfo from, SerializationFieldInfo to) {
    try {
      return canonicalDecimal(decimal).toBigIntegerExact();
    } catch (ArithmeticException e) {
      throw dataError(from.dispatchId, from.type, to.dispatchId, to.type, to.qualifiedFieldName, e);
    }
  }

  private static BigInteger readIntegerSource(
      MemoryBuffer buffer, SerializationFieldInfo from, String fieldName) {
    switch (from.dispatchId) {
      case DispatchId.INT8:
        return BigInteger.valueOf(buffer.readByte());
      case DispatchId.UINT8:
      case DispatchId.EXT_UINT8:
        return BigInteger.valueOf(buffer.readByte() & 0xFF);
      case DispatchId.INT16:
        return BigInteger.valueOf(buffer.readInt16());
      case DispatchId.UINT16:
      case DispatchId.EXT_UINT16:
        return BigInteger.valueOf(buffer.readInt16() & 0xFFFF);
      case DispatchId.INT32:
        return BigInteger.valueOf(buffer.readInt32());
      case DispatchId.UINT32:
      case DispatchId.EXT_UINT32:
        return BigInteger.valueOf(Integer.toUnsignedLong(buffer.readInt32()));
      case DispatchId.VARINT32:
        return BigInteger.valueOf(buffer.readVarInt32());
      case DispatchId.VAR_UINT32:
      case DispatchId.EXT_VAR_UINT32:
        return BigInteger.valueOf(Integer.toUnsignedLong(buffer.readVarUInt32()));
      case DispatchId.INT64:
        return BigInteger.valueOf(buffer.readInt64());
      case DispatchId.UINT64:
      case DispatchId.EXT_UINT64:
        return unsignedLongBigInteger(buffer.readInt64());
      case DispatchId.VARINT64:
        return BigInteger.valueOf(buffer.readVarInt64());
      case DispatchId.TAGGED_INT64:
        return BigInteger.valueOf(buffer.readTaggedInt64());
      case DispatchId.VAR_UINT64:
      case DispatchId.EXT_VAR_UINT64:
        return unsignedLongBigInteger(buffer.readVarUInt64());
      case DispatchId.TAGGED_UINT64:
        return unsignedLongBigInteger(buffer.readTaggedUInt64());
      default:
        throw dataError(
            from.dispatchId, from.type, DispatchId.UNKNOWN, BigInteger.class, fieldName);
    }
  }

  private static BigInteger unsignedLongBigInteger(long bits) {
    if (bits >= 0) {
      return BigInteger.valueOf(bits);
    }
    return BigInteger.valueOf(bits & Long.MAX_VALUE).setBit(63);
  }

  private static BigDecimal readNumericDecimal(
      ReadContext readContext, MemoryBuffer buffer, SerializationFieldInfo from, String fieldName) {
    switch (domain(from.dispatchId, from.type)) {
      case BOOL:
        return readBool(buffer, from.dispatchId, from.type, fieldName) ? DECIMAL_ONE : DECIMAL_ZERO;
      case STRING:
        return canonicalDecimal(parseDecimalString(readContext.readString(), from, from));
      case SIGNED_INT:
      case UNSIGNED_INT:
        return new BigDecimal(readIntegerSource(buffer, from, fieldName));
      case DECIMAL:
        return canonicalDecimal(readDecimalSource(readContext, from));
      case FLOAT:
        return readFiniteFloatDecimal(buffer, from, fieldName);
      default:
        throw dataError(
            from.dispatchId, from.type, DispatchId.UNKNOWN, BigDecimal.class, fieldName);
    }
  }

  private static BigDecimal parseDecimalString(
      String value, SerializationFieldInfo from, SerializationFieldInfo to) {
    if (!numericLiteralFits(value)) {
      throw dataError(from.dispatchId, from.type, to.dispatchId, to.type, to.qualifiedFieldName);
    }
    try {
      return new BigDecimal(value);
    } catch (NumberFormatException e) {
      throw dataError(from.dispatchId, from.type, to.dispatchId, to.type, to.qualifiedFieldName, e);
    }
  }

  private static BigDecimal readDecimalSource(
      ReadContext readContext, SerializationFieldInfo from) {
    if (from.useDeclaredTypeInfo) {
      readContext.preserveRefId(-1);
      return (BigDecimal) readContext.readNonRef(from.typeInfo);
    }
    TypeInfo typeInfo = readContext.getTypeResolver().readTypeInfo(readContext, from.type);
    return (BigDecimal) typeInfo.getSerializer().read(readContext, RefMode.NONE);
  }

  private static float readFloatTargetPayload(
      ReadContext readContext,
      MemoryBuffer buffer,
      SerializationFieldInfo from,
      SerializationFieldInfo to) {
    String fieldName = to.qualifiedFieldName;
    switch (domain(from.dispatchId, from.type)) {
      case BOOL:
        return readBool(buffer, from.dispatchId, from.type, fieldName) ? 1.0f : 0.0f;
      case STRING:
        {
          String value = readContext.readString();
          BigDecimal decimal = parseDecimalString(value, from, to);
          return decimalToFloat32(
              decimal, value.charAt(0) == '-' && decimal.signum() == 0, to, fieldName);
        }
      case SIGNED_INT:
      case UNSIGNED_INT:
        return decimalToFloat32(
            new BigDecimal(readIntegerSource(buffer, from, fieldName)), false, to, fieldName);
      case DECIMAL:
        return decimalToFloat32(readDecimalSource(readContext, from), false, to, fieldName);
      case FLOAT:
        return readFloatAsFloat32(buffer, from, to, fieldName);
      default:
        throw dataError(from.dispatchId, from.type, to.dispatchId, to.type, fieldName);
    }
  }

  private static double readDoubleTargetPayload(
      ReadContext readContext,
      MemoryBuffer buffer,
      SerializationFieldInfo from,
      SerializationFieldInfo to) {
    String fieldName = to.qualifiedFieldName;
    switch (domain(from.dispatchId, from.type)) {
      case BOOL:
        return readBool(buffer, from.dispatchId, from.type, fieldName) ? 1.0d : 0.0d;
      case STRING:
        {
          String value = readContext.readString();
          BigDecimal decimal = parseDecimalString(value, from, to);
          return decimalToFloat64(
              decimal, value.charAt(0) == '-' && decimal.signum() == 0, to, fieldName);
        }
      case SIGNED_INT:
      case UNSIGNED_INT:
        return decimalToFloat64(
            new BigDecimal(readIntegerSource(buffer, from, fieldName)), false, to, fieldName);
      case DECIMAL:
        return decimalToFloat64(readDecimalSource(readContext, from), false, to, fieldName);
      case FLOAT:
        return readFloatAsFloat64(buffer, from, to, fieldName);
      default:
        throw dataError(from.dispatchId, from.type, to.dispatchId, to.type, fieldName);
    }
  }

  private static Float16 readFloat16TargetPayload(
      ReadContext readContext,
      MemoryBuffer buffer,
      SerializationFieldInfo from,
      SerializationFieldInfo to) {
    if (sameScalar(from.dispatchId, from.type, to.dispatchId, to.type)) {
      return Float16.fromBits(buffer.readInt16());
    }
    return (Float16)
        decimalToFloat(
            readNumericDecimal(readContext, buffer, from, to.qualifiedFieldName),
            to.dispatchId,
            to.type,
            false,
            to.qualifiedFieldName);
  }

  private static BFloat16 readBFloat16TargetPayload(
      ReadContext readContext,
      MemoryBuffer buffer,
      SerializationFieldInfo from,
      SerializationFieldInfo to) {
    if (sameScalar(from.dispatchId, from.type, to.dispatchId, to.type)) {
      return BFloat16.fromBits(buffer.readInt16());
    }
    return (BFloat16)
        decimalToFloat(
            readNumericDecimal(readContext, buffer, from, to.qualifiedFieldName),
            to.dispatchId,
            to.type,
            false,
            to.qualifiedFieldName);
  }

  private static boolean readFloatBool(
      MemoryBuffer buffer, SerializationFieldInfo from, SerializationFieldInfo to) {
    switch (normalizedFloatId(from.dispatchId, from.type)) {
      case DispatchId.FLOAT16:
        {
          Float16 value = Float16.fromBits(buffer.readInt16());
          if (!value.isFinite()) {
            throw dataError(
                from.dispatchId, from.type, DispatchId.BOOL, to.type, to.qualifiedFieldName);
          }
          if (value.isZero()) {
            return false;
          }
          if (value.toBits() == Float16.ONE.toBits()) {
            return true;
          }
          throw dataError(
              from.dispatchId, from.type, DispatchId.BOOL, to.type, to.qualifiedFieldName);
        }
      case DispatchId.BFLOAT16:
        {
          BFloat16 value = BFloat16.fromBits(buffer.readInt16());
          if (!value.isFinite()) {
            throw dataError(
                from.dispatchId, from.type, DispatchId.BOOL, to.type, to.qualifiedFieldName);
          }
          if (value.isZero()) {
            return false;
          }
          if (value.toBits() == BFloat16.ONE.toBits()) {
            return true;
          }
          throw dataError(
              from.dispatchId, from.type, DispatchId.BOOL, to.type, to.qualifiedFieldName);
        }
      case DispatchId.FLOAT32:
        {
          float value = buffer.readFloat32();
          if (!Float.isFinite(value)) {
            throw dataError(
                from.dispatchId, from.type, DispatchId.BOOL, to.type, to.qualifiedFieldName);
          }
          if (value == 0.0f) {
            return false;
          }
          if (value == 1.0f) {
            return true;
          }
          throw dataError(
              from.dispatchId, from.type, DispatchId.BOOL, to.type, to.qualifiedFieldName);
        }
      case DispatchId.FLOAT64:
        {
          double value = buffer.readFloat64();
          if (!Double.isFinite(value)) {
            throw dataError(
                from.dispatchId, from.type, DispatchId.BOOL, to.type, to.qualifiedFieldName);
          }
          if (value == 0.0d) {
            return false;
          }
          if (value == 1.0d) {
            return true;
          }
          throw dataError(
              from.dispatchId, from.type, DispatchId.BOOL, to.type, to.qualifiedFieldName);
        }
      default:
        throw dataError(
            from.dispatchId, from.type, DispatchId.BOOL, to.type, to.qualifiedFieldName);
    }
  }

  private static BigDecimal readFiniteFloatDecimal(
      MemoryBuffer buffer, SerializationFieldInfo from, String fieldName) {
    switch (normalizedFloatId(from.dispatchId, from.type)) {
      case DispatchId.FLOAT16:
        {
          Float16 value = Float16.fromBits(buffer.readInt16());
          if (!value.isFinite()) {
            throw dataError(
                from.dispatchId, from.type, DispatchId.UNKNOWN, BigDecimal.class, fieldName);
          }
          return value.isZero() ? BigDecimal.ZERO : exactFloatDecimal(value.floatValue());
        }
      case DispatchId.BFLOAT16:
        {
          BFloat16 value = BFloat16.fromBits(buffer.readInt16());
          if (!value.isFinite()) {
            throw dataError(
                from.dispatchId, from.type, DispatchId.UNKNOWN, BigDecimal.class, fieldName);
          }
          return value.isZero() ? BigDecimal.ZERO : exactFloatDecimal(value.floatValue());
        }
      case DispatchId.FLOAT32:
        {
          float value = buffer.readFloat32();
          if (!Float.isFinite(value)) {
            throw dataError(
                from.dispatchId, from.type, DispatchId.UNKNOWN, BigDecimal.class, fieldName);
          }
          return value == 0.0f ? BigDecimal.ZERO : exactFloatDecimal(value);
        }
      case DispatchId.FLOAT64:
        {
          double value = buffer.readFloat64();
          if (!Double.isFinite(value)) {
            throw dataError(
                from.dispatchId, from.type, DispatchId.UNKNOWN, BigDecimal.class, fieldName);
          }
          return value == 0.0d ? BigDecimal.ZERO : exactDoubleDecimal(value);
        }
      default:
        throw dataError(
            from.dispatchId, from.type, DispatchId.UNKNOWN, BigDecimal.class, fieldName);
    }
  }

  private static String readFloatText(
      MemoryBuffer buffer, SerializationFieldInfo from, String fieldName) {
    switch (normalizedFloatId(from.dispatchId, from.type)) {
      case DispatchId.FLOAT16:
        {
          Float16 value = Float16.fromBits(buffer.readInt16());
          if (!value.isFinite()) {
            throw dataError(from.dispatchId, from.type, DispatchId.STRING, String.class, fieldName);
          }
          if (value.isZero()) {
            return value.signbit() ? "-0.0" : "0.0";
          }
          String text = decimalText(exactFloatDecimal(value.floatValue()));
          return text.indexOf('.') >= 0 ? text : text + ".0";
        }
      case DispatchId.BFLOAT16:
        {
          BFloat16 value = BFloat16.fromBits(buffer.readInt16());
          if (!value.isFinite()) {
            throw dataError(from.dispatchId, from.type, DispatchId.STRING, String.class, fieldName);
          }
          if (value.isZero()) {
            return value.signbit() ? "-0.0" : "0.0";
          }
          String text = decimalText(exactFloatDecimal(value.floatValue()));
          return text.indexOf('.') >= 0 ? text : text + ".0";
        }
      case DispatchId.FLOAT32:
        {
          float value = buffer.readFloat32();
          if (!Float.isFinite(value)) {
            throw dataError(from.dispatchId, from.type, DispatchId.STRING, String.class, fieldName);
          }
          if (value == 0.0f) {
            return Float.floatToRawIntBits(value) < 0 ? "-0.0" : "0.0";
          }
          String text = decimalText(exactFloatDecimal(value));
          return text.indexOf('.') >= 0 ? text : text + ".0";
        }
      case DispatchId.FLOAT64:
        {
          double value = buffer.readFloat64();
          if (!Double.isFinite(value)) {
            throw dataError(from.dispatchId, from.type, DispatchId.STRING, String.class, fieldName);
          }
          if (value == 0.0d) {
            return Double.doubleToRawLongBits(value) < 0 ? "-0.0" : "0.0";
          }
          String text = decimalText(exactDoubleDecimal(value));
          return text.indexOf('.') >= 0 ? text : text + ".0";
        }
      default:
        throw dataError(from.dispatchId, from.type, DispatchId.STRING, String.class, fieldName);
    }
  }

  private static float readFloatAsFloat32(
      MemoryBuffer buffer,
      SerializationFieldInfo from,
      SerializationFieldInfo to,
      String fieldName) {
    switch (normalizedFloatId(from.dispatchId, from.type)) {
      case DispatchId.FLOAT16:
        {
          Float16 value = Float16.fromBits(buffer.readInt16());
          if (value.isNaN()) {
            throw dataError(from.dispatchId, from.type, to.dispatchId, to.type, fieldName);
          }
          if (value.isInfinite()) {
            return value.signbit() ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
          }
          if (value.isZero()) {
            return value.signbit() ? -0.0f : 0.0f;
          }
          return decimalToFloat32(exactFloatDecimal(value.floatValue()), false, to, fieldName);
        }
      case DispatchId.BFLOAT16:
        {
          BFloat16 value = BFloat16.fromBits(buffer.readInt16());
          if (value.isNaN()) {
            throw dataError(from.dispatchId, from.type, to.dispatchId, to.type, fieldName);
          }
          if (value.isInfinite()) {
            return value.signbit() ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
          }
          if (value.isZero()) {
            return value.signbit() ? -0.0f : 0.0f;
          }
          return decimalToFloat32(exactFloatDecimal(value.floatValue()), false, to, fieldName);
        }
      case DispatchId.FLOAT32:
        return buffer.readFloat32();
      case DispatchId.FLOAT64:
        {
          double value = buffer.readFloat64();
          if (Double.isNaN(value)) {
            throw dataError(from.dispatchId, from.type, to.dispatchId, to.type, fieldName);
          }
          if (Double.isInfinite(value)) {
            return value < 0 ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
          }
          if (value == 0.0d) {
            return Double.doubleToRawLongBits(value) < 0 ? -0.0f : 0.0f;
          }
          return decimalToFloat32(exactDoubleDecimal(value), false, to, fieldName);
        }
      default:
        throw dataError(from.dispatchId, from.type, to.dispatchId, to.type, fieldName);
    }
  }

  private static double readFloatAsFloat64(
      MemoryBuffer buffer,
      SerializationFieldInfo from,
      SerializationFieldInfo to,
      String fieldName) {
    switch (normalizedFloatId(from.dispatchId, from.type)) {
      case DispatchId.FLOAT16:
        {
          Float16 value = Float16.fromBits(buffer.readInt16());
          if (value.isNaN()) {
            throw dataError(from.dispatchId, from.type, to.dispatchId, to.type, fieldName);
          }
          if (value.isInfinite()) {
            return value.signbit() ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
          }
          if (value.isZero()) {
            return value.signbit() ? -0.0d : 0.0d;
          }
          return decimalToFloat64(exactFloatDecimal(value.floatValue()), false, to, fieldName);
        }
      case DispatchId.BFLOAT16:
        {
          BFloat16 value = BFloat16.fromBits(buffer.readInt16());
          if (value.isNaN()) {
            throw dataError(from.dispatchId, from.type, to.dispatchId, to.type, fieldName);
          }
          if (value.isInfinite()) {
            return value.signbit() ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
          }
          if (value.isZero()) {
            return value.signbit() ? -0.0d : 0.0d;
          }
          return decimalToFloat64(exactFloatDecimal(value.floatValue()), false, to, fieldName);
        }
      case DispatchId.FLOAT32:
        {
          float value = buffer.readFloat32();
          if (Float.isNaN(value)) {
            throw dataError(from.dispatchId, from.type, to.dispatchId, to.type, fieldName);
          }
          if (Float.isInfinite(value)) {
            return value < 0 ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
          }
          if (value == 0.0f) {
            return Float.floatToRawIntBits(value) < 0 ? -0.0d : 0.0d;
          }
          return decimalToFloat64(exactFloatDecimal(value), false, to, fieldName);
        }
      case DispatchId.FLOAT64:
        return buffer.readFloat64();
      default:
        throw dataError(from.dispatchId, from.type, to.dispatchId, to.type, fieldName);
    }
  }

  private static float decimalToFloat32(
      BigDecimal decimal, boolean negativeZero, SerializationFieldInfo to, String fieldName) {
    decimal = canonicalDecimal(decimal);
    if (decimal.signum() == 0) {
      return negativeZero ? -0.0f : 0.0f;
    }
    float result = decimal.floatValue();
    if (!Float.isFinite(result) || exactFloatDecimal(result).compareTo(decimal) != 0) {
      throw dataError(DispatchId.UNKNOWN, BigDecimal.class, to.dispatchId, to.type, fieldName);
    }
    return result;
  }

  private static double decimalToFloat64(
      BigDecimal decimal, boolean negativeZero, SerializationFieldInfo to, String fieldName) {
    decimal = canonicalDecimal(decimal);
    if (decimal.signum() == 0) {
      return negativeZero ? -0.0d : 0.0d;
    }
    double result = decimal.doubleValue();
    if (!Double.isFinite(result) || exactDoubleDecimal(result).compareTo(decimal) != 0) {
      throw dataError(DispatchId.UNKNOWN, BigDecimal.class, to.dispatchId, to.type, fieldName);
    }
    return result;
  }

  private static Object fromBool(
      boolean value, int toDispatchId, Class<?> toType, int toDomain, String fieldName) {
    if (toDomain == BOOL) {
      return value;
    }
    if (toDomain == STRING) {
      return value ? "true" : "false";
    }
    return integerToTarget(value ? BIG_ONE : BIG_ZERO, toDispatchId, toType, toDomain, fieldName);
  }

  private static Boolean toBool(
      int fromDispatchId,
      Class<?> fromType,
      int fromDomain,
      Object value,
      Class<?> toType,
      String fieldName) {
    boolean result;
    if (fromDomain == STRING) {
      String text = (String) value;
      if ("0".equals(text) || "false".equals(text)) {
        result = false;
      } else if ("1".equals(text) || "true".equals(text)) {
        result = true;
      } else {
        throw dataError(fromDispatchId, fromType, DispatchId.BOOL, toType, fieldName);
      }
    } else if (fromDomain == SIGNED_INT || fromDomain == UNSIGNED_INT) {
      BigInteger integer = integerValue(fromDispatchId, fromType, value);
      if (BIG_ZERO.equals(integer)) {
        result = false;
      } else if (BIG_ONE.equals(integer)) {
        result = true;
      } else {
        throw dataError(fromDispatchId, fromType, DispatchId.BOOL, toType, fieldName);
      }
    } else if (fromDomain == DECIMAL) {
      BigDecimal decimal = (BigDecimal) value;
      if (decimal.compareTo(DECIMAL_ZERO) == 0) {
        result = false;
      } else if (decimal.compareTo(DECIMAL_ONE) == 0) {
        result = true;
      } else {
        throw dataError(fromDispatchId, fromType, DispatchId.BOOL, toType, fieldName);
      }
    } else if (fromDomain == FLOAT) {
      if (!isFiniteFloat(fromDispatchId, value)) {
        throw dataError(fromDispatchId, fromType, DispatchId.BOOL, toType, fieldName);
      }
      if (isFloatZero(fromDispatchId, value)) {
        result = false;
      } else if (isFloatOne(fromDispatchId, value)) {
        result = true;
      } else {
        throw dataError(fromDispatchId, fromType, DispatchId.BOOL, toType, fieldName);
      }
    } else {
      throw dataError(fromDispatchId, fromType, DispatchId.BOOL, toType, fieldName);
    }
    return result;
  }

  private static Object fromString(
      String value, int toDispatchId, Class<?> toType, int toDomain, String fieldName) {
    if (toDomain == BOOL) {
      return toBool(DispatchId.STRING, String.class, STRING, value, toType, fieldName);
    }
    if (!numericLiteralFits(value)) {
      throw dataError(DispatchId.STRING, String.class, toDispatchId, toType, fieldName);
    }
    BigDecimal decimal;
    try {
      decimal = new BigDecimal(value);
    } catch (NumberFormatException e) {
      throw dataError(DispatchId.STRING, String.class, toDispatchId, toType, fieldName, e);
    }
    if (toDomain == SIGNED_INT || toDomain == UNSIGNED_INT) {
      return decimalToInteger(decimal, toDispatchId, toType, fieldName);
    }
    if (toDomain == DECIMAL) {
      return canonicalDecimal(decimal);
    }
    if (toDomain == FLOAT) {
      return decimalToFloat(
          decimal,
          toDispatchId,
          toType,
          value.charAt(0) == '-' && decimal.signum() == 0,
          fieldName);
    }
    throw dataError(DispatchId.STRING, String.class, toDispatchId, toType, fieldName);
  }

  private static boolean numericLiteralFits(String value) {
    int length = value.length();
    if (length == 0 || length > MAX_COMPATIBLE_NUMERIC_TEXT_LENGTH) {
      return false;
    }
    int index = 0;
    if (value.charAt(index) == '-') {
      index++;
      if (index == length) {
        return false;
      }
    }

    int significantDigits = 0;
    boolean seenNonZero = false;
    if (value.charAt(index) == '0') {
      index++;
      if (index < length && isDigit(value.charAt(index))) {
        return false;
      }
    } else if (value.charAt(index) >= '1' && value.charAt(index) <= '9') {
      while (index < length && isDigit(value.charAt(index))) {
        if (value.charAt(index) != '0' || seenNonZero) {
          seenNonZero = true;
          significantDigits++;
        }
        index++;
      }
    } else {
      return false;
    }

    int fractionalDigits = 0;
    if (index < length && value.charAt(index) == '.') {
      index++;
      int fractionStart = index;
      while (index < length && isDigit(value.charAt(index))) {
        if (value.charAt(index) != '0' || seenNonZero) {
          seenNonZero = true;
          significantDigits++;
        }
        fractionalDigits++;
        index++;
      }
      if (index == fractionStart) {
        return false;
      }
    }

    int exponent = 0;
    if (index < length && (value.charAt(index) == 'e' || value.charAt(index) == 'E')) {
      index++;
      boolean exponentNegative = false;
      if (index < length && value.charAt(index) == '-') {
        exponentNegative = true;
        index++;
      }
      if (index == length) {
        return false;
      }
      if (value.charAt(index) == '0') {
        index++;
        if (index < length && isDigit(value.charAt(index))) {
          return false;
        }
      } else if (value.charAt(index) >= '1' && value.charAt(index) <= '9') {
        while (index < length && isDigit(value.charAt(index))) {
          exponent = exponent * 10 + (value.charAt(index) - '0');
          if (exponent > MAX_COMPATIBLE_DECIMAL_DIGITS) {
            return false;
          }
          index++;
        }
      } else {
        return false;
      }
      if (exponentNegative) {
        exponent = -exponent;
      }
    }

    if (index != length || significantDigits > MAX_COMPATIBLE_DECIMAL_DIGITS) {
      return false;
    }
    int finalScale = fractionalDigits - exponent;
    return decimalShapeFits(significantDigits, finalScale);
  }

  private static boolean decimalShapeFits(int significantDigits, int scale) {
    if (scale > MAX_COMPATIBLE_DECIMAL_DIGITS) {
      return false;
    }
    return scale >= 0 || significantDigits + (-scale) <= MAX_COMPATIBLE_DECIMAL_DIGITS;
  }

  private static boolean isDigit(char value) {
    return value >= '0' && value <= '9';
  }

  private static String toStringValue(
      int fromDispatchId, Class<?> fromType, int fromDomain, Object value, String fieldName) {
    if (fromDomain == BOOL) {
      return (Boolean) value ? "true" : "false";
    }
    if (fromDomain == SIGNED_INT || fromDomain == UNSIGNED_INT) {
      return integerValue(fromDispatchId, fromType, value).toString();
    }
    if (fromDomain == DECIMAL) {
      return decimalText((BigDecimal) value);
    }
    if (fromDomain == FLOAT) {
      return floatText(fromDispatchId, fromType, value, fieldName);
    }
    throw dataError(fromDispatchId, fromType, DispatchId.STRING, String.class, fieldName);
  }

  private static Object convertNumber(
      int fromDispatchId,
      Class<?> fromType,
      int fromDomain,
      Object value,
      int toDispatchId,
      Class<?> toType,
      int toDomain,
      String fieldName) {
    if (toDomain == SIGNED_INT || toDomain == UNSIGNED_INT) {
      if (fromDomain == SIGNED_INT || fromDomain == UNSIGNED_INT) {
        return integerToTarget(
            integerValue(fromDispatchId, fromType, value),
            toDispatchId,
            toType,
            toDomain,
            fieldName);
      } else if (fromDomain == DECIMAL) {
        return decimalToInteger((BigDecimal) value, toDispatchId, toType, fieldName);
      } else {
        return decimalToInteger(
            floatDecimal(fromDispatchId, value), toDispatchId, toType, fieldName);
      }
    }
    if (toDomain == DECIMAL) {
      if (fromDomain == SIGNED_INT || fromDomain == UNSIGNED_INT) {
        return new BigDecimal(integerValue(fromDispatchId, fromType, value));
      } else if (fromDomain == FLOAT) {
        if (!isFiniteFloat(fromDispatchId, value)) {
          throw dataError(fromDispatchId, fromType, toDispatchId, toType, fieldName);
        }
        return floatDecimal(fromDispatchId, value);
      }
      return canonicalDecimal((BigDecimal) value);
    }
    if (toDomain == FLOAT) {
      if (fromDomain == FLOAT) {
        return floatToFloat(fromDispatchId, fromType, value, toDispatchId, toType, fieldName);
      } else if (fromDomain == DECIMAL) {
        return decimalToFloat((BigDecimal) value, toDispatchId, toType, false, fieldName);
      }
      return decimalToFloat(
          new BigDecimal(integerValue(fromDispatchId, fromType, value)),
          toDispatchId,
          toType,
          false,
          fieldName);
    }
    throw dataError(fromDispatchId, fromType, toDispatchId, toType, fieldName);
  }

  private static Object decimalToInteger(
      BigDecimal decimal, int toDispatchId, Class<?> toType, String fieldName) {
    BigInteger value;
    try {
      value = canonicalDecimal(decimal).toBigIntegerExact();
    } catch (ArithmeticException e) {
      throw dataError(DispatchId.UNKNOWN, BigDecimal.class, toDispatchId, toType, fieldName, e);
    }
    return integerToTarget(value, toDispatchId, toType, domain(toDispatchId, toType), fieldName);
  }

  private static Object integerToTarget(
      BigInteger value, int toDispatchId, Class<?> toType, int toDomain, String fieldName) {
    if (toDomain == DECIMAL) {
      return new BigDecimal(value);
    }
    if (toDomain == FLOAT) {
      return decimalToFloat(new BigDecimal(value), toDispatchId, toType, false, fieldName);
    }
    checkIntegerRange(value, toDispatchId, toType, fieldName);
    switch (normalizedIntegerId(toDispatchId, toType)) {
      case DispatchId.INT8:
        return Byte.valueOf(value.byteValue());
      case DispatchId.INT16:
        return Short.valueOf(value.shortValue());
      case DispatchId.INT32:
        return Integer.valueOf(value.intValue());
      case DispatchId.INT64:
        return Long.valueOf(value.longValue());
      case DispatchId.UINT8:
        return isUIntWrapper(toType)
            ? UInt8.valueOf(value.intValue())
            : Integer.valueOf(value.intValue());
      case DispatchId.UINT16:
        return isUIntWrapper(toType)
            ? UInt16.valueOf(value.intValue())
            : Integer.valueOf(value.intValue());
      case DispatchId.UINT32:
        return isUIntWrapper(toType)
            ? UInt32.valueOf(value.intValue())
            : Long.valueOf(value.longValue());
      case DispatchId.UINT64:
        return isUIntWrapper(toType)
            ? UInt64.valueOf(value.longValue())
            : Long.valueOf(value.longValue());
      default:
        throw dataError(DispatchId.UNKNOWN, BigInteger.class, toDispatchId, toType, fieldName);
    }
  }

  private static Object floatToFloat(
      int fromDispatchId,
      Class<?> fromType,
      Object value,
      int toDispatchId,
      Class<?> toType,
      String fieldName) {
    if (isNaNFloat(fromDispatchId, value)) {
      if (sameScalar(fromDispatchId, fromType, toDispatchId, toType)) {
        return value;
      }
      throw dataError(fromDispatchId, fromType, toDispatchId, toType, fieldName);
    }
    if (isInfiniteFloat(fromDispatchId, value)) {
      return floatInfinity(toDispatchId, toType, isNegativeFloat(fromDispatchId, value), fieldName);
    }
    if (isFloatZero(fromDispatchId, value)) {
      return floatZero(toDispatchId, toType, isNegativeFloat(fromDispatchId, value));
    }
    return decimalToFloat(
        floatDecimal(fromDispatchId, value), toDispatchId, toType, false, fieldName);
  }

  private static Object decimalToFloat(
      BigDecimal decimal,
      int toDispatchId,
      Class<?> toType,
      boolean negativeZero,
      String fieldName) {
    decimal = canonicalDecimal(decimal);
    if (decimal.signum() == 0) {
      return floatZero(toDispatchId, toType, negativeZero);
    }
    switch (normalizedFloatId(toDispatchId, toType)) {
      case DispatchId.FLOAT16:
        {
          Float16 result = Float16.valueOf(decimal.floatValue());
          checkFloatExact(decimal, toDispatchId, toType, result, fieldName);
          return result;
        }
      case DispatchId.BFLOAT16:
        {
          BFloat16 result = BFloat16.valueOf(decimal.floatValue());
          checkFloatExact(decimal, toDispatchId, toType, result, fieldName);
          return result;
        }
      case DispatchId.FLOAT32:
        {
          Float result = Float.valueOf(decimal.floatValue());
          checkFloatExact(decimal, toDispatchId, toType, result, fieldName);
          return result;
        }
      case DispatchId.FLOAT64:
        {
          Double result = Double.valueOf(decimal.doubleValue());
          checkFloatExact(decimal, toDispatchId, toType, result, fieldName);
          return result;
        }
      default:
        throw dataError(DispatchId.UNKNOWN, BigDecimal.class, toDispatchId, toType, fieldName);
    }
  }

  private static void checkFloatExact(
      BigDecimal source, int toDispatchId, Class<?> toType, Object result, String fieldName) {
    if (!isFiniteFloat(toDispatchId, result)
        || floatDecimal(toDispatchId, result).compareTo(source) != 0) {
      throw dataError(DispatchId.UNKNOWN, BigDecimal.class, toDispatchId, toType, fieldName);
    }
  }

  private static String floatText(
      int fromDispatchId, Class<?> fromType, Object value, String fieldName) {
    if (!isFiniteFloat(fromDispatchId, value)) {
      throw dataError(fromDispatchId, fromType, DispatchId.STRING, String.class, fieldName);
    }
    if (isFloatZero(fromDispatchId, value)) {
      return isNegativeFloat(fromDispatchId, value) ? "-0.0" : "0.0";
    }
    String text = decimalText(floatDecimal(fromDispatchId, value));
    return text.indexOf('.') >= 0 ? text : text + ".0";
  }

  private static String decimalText(BigDecimal value) {
    return canonicalDecimal(value).toPlainString();
  }

  private static BigDecimal canonicalDecimal(BigDecimal value) {
    if (value.signum() == 0) {
      return BigDecimal.ZERO;
    }
    BigDecimal stripped = value.stripTrailingZeros();
    int strippedScale = stripped.scale();
    long scale = Math.max((long) strippedScale, 0L);
    long extraDigits = strippedScale < 0 ? -(long) strippedScale : 0L;
    long digitCount = (long) stripped.precision() + extraDigits;
    if (scale > MAX_COMPATIBLE_DECIMAL_DIGITS || digitCount > MAX_COMPATIBLE_DECIMAL_DIGITS) {
      throw new IllegalArgumentException("Decimal exceeds compatible conversion limit");
    }
    return strippedScale < 0 ? stripped.setScale(0) : stripped;
  }

  private static BigDecimal floatDecimal(int dispatchId, Object value) {
    switch (normalizedFloatId(dispatchId, value.getClass())) {
      case DispatchId.FLOAT16:
        return exactFloatDecimal(((Float16) value).floatValue());
      case DispatchId.BFLOAT16:
        return exactFloatDecimal(((BFloat16) value).floatValue());
      case DispatchId.FLOAT32:
        return exactFloatDecimal(((Float) value).floatValue());
      case DispatchId.FLOAT64:
        return exactDoubleDecimal(((Double) value).doubleValue());
      default:
        throw new IllegalArgumentException("Not a floating scalar: " + value.getClass());
    }
  }

  private static BigDecimal exactFloatDecimal(float value) {
    int bits = Float.floatToRawIntBits(value);
    int exponent = (bits >>> 23) & 0xFF;
    int mantissa = bits & 0x7FFFFF;
    if (exponent == 0 && mantissa == 0) {
      return BigDecimal.ZERO;
    }
    boolean negative = (bits & 0x8000_0000) != 0;
    BigInteger significand;
    int binaryExponent;
    if (exponent == 0) {
      significand = BigInteger.valueOf(mantissa);
      binaryExponent = -149;
    } else {
      significand = BigInteger.valueOf((1 << 23) | mantissa);
      binaryExponent = exponent - 150;
    }
    return binaryDecimal(negative, significand, binaryExponent);
  }

  private static BigDecimal exactDoubleDecimal(double value) {
    long bits = Double.doubleToRawLongBits(value);
    int exponent = (int) ((bits >>> 52) & 0x7FFL);
    long mantissa = bits & 0xF_FFFF_FFFF_FFFFL;
    if (exponent == 0 && mantissa == 0) {
      return BigDecimal.ZERO;
    }
    boolean negative = (bits & Long.MIN_VALUE) != 0;
    BigInteger significand;
    int binaryExponent;
    if (exponent == 0) {
      significand = BigInteger.valueOf(mantissa);
      binaryExponent = -1074;
    } else {
      significand = BigInteger.valueOf((1L << 52) | mantissa);
      binaryExponent = exponent - 1075;
    }
    return binaryDecimal(negative, significand, binaryExponent);
  }

  private static BigDecimal binaryDecimal(
      boolean negative, BigInteger significand, int binaryExponent) {
    BigDecimal decimal;
    if (binaryExponent >= 0) {
      decimal = new BigDecimal(significand.shiftLeft(binaryExponent));
    } else {
      int scale = -binaryExponent;
      if (scale > MAX_COMPATIBLE_DECIMAL_DIGITS) {
        throw new IllegalArgumentException("Float decimal expansion is too large");
      }
      BigInteger unscaled = significand.multiply(BigInteger.valueOf(5).pow(scale));
      decimal = new BigDecimal(unscaled, scale);
    }
    return canonicalDecimal(negative ? decimal.negate() : decimal);
  }

  private static BigInteger integerValue(int dispatchId, Class<?> type, Object value) {
    switch (normalizedIntegerId(dispatchId, type)) {
      case DispatchId.INT8:
      case DispatchId.INT16:
      case DispatchId.INT32:
        return BigInteger.valueOf(((Number) value).longValue());
      case DispatchId.INT64:
        return BigInteger.valueOf(((Number) value).longValue());
      case DispatchId.UINT8:
        return BigInteger.valueOf(uint8Value(value));
      case DispatchId.UINT16:
        return BigInteger.valueOf(uint16Value(value));
      case DispatchId.UINT32:
        return BigInteger.valueOf(uint32Value(value));
      case DispatchId.UINT64:
        return uint64Value(value);
      default:
        throw new IllegalArgumentException("Not an integer scalar: " + type);
    }
  }

  private static int uint8Value(Object value) {
    return value instanceof UInt8 ? ((UInt8) value).toInt() : ((Number) value).intValue() & 0xFF;
  }

  private static int uint16Value(Object value) {
    return value instanceof UInt16
        ? ((UInt16) value).toInt()
        : ((Number) value).intValue() & 0xFFFF;
  }

  private static long uint32Value(Object value) {
    return value instanceof UInt32 ? ((UInt32) value).toLong() : ((Number) value).longValue();
  }

  private static BigInteger uint64Value(Object value) {
    long bits = value instanceof UInt64 ? ((UInt64) value).toLong() : ((Number) value).longValue();
    if (bits >= 0) {
      return BigInteger.valueOf(bits);
    }
    return BigInteger.valueOf(bits & Long.MAX_VALUE).setBit(63);
  }

  private static void checkIntegerRange(
      BigInteger value, int toDispatchId, Class<?> toType, String fieldName) {
    BigInteger min;
    BigInteger max;
    switch (normalizedIntegerId(toDispatchId, toType)) {
      case DispatchId.INT8:
        min = SIGNED_INT8_MIN;
        max = SIGNED_INT8_MAX;
        break;
      case DispatchId.INT16:
        min = SIGNED_INT16_MIN;
        max = SIGNED_INT16_MAX;
        break;
      case DispatchId.INT32:
        min = SIGNED_INT32_MIN;
        max = SIGNED_INT32_MAX;
        break;
      case DispatchId.INT64:
        min = SIGNED_INT64_MIN;
        max = SIGNED_INT64_MAX;
        break;
      case DispatchId.UINT8:
        min = BIG_ZERO;
        max = UNSIGNED_INT8_MAX;
        break;
      case DispatchId.UINT16:
        min = BIG_ZERO;
        max = UNSIGNED_INT16_MAX;
        break;
      case DispatchId.UINT32:
        min = BIG_ZERO;
        max = UNSIGNED_INT32_MAX;
        break;
      case DispatchId.UINT64:
        min = BIG_ZERO;
        max = UNSIGNED_INT64_MAX;
        break;
      default:
        throw dataError(DispatchId.UNKNOWN, BigInteger.class, toDispatchId, toType, fieldName);
    }
    if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
      throw dataError(DispatchId.UNKNOWN, BigInteger.class, toDispatchId, toType, fieldName);
    }
  }

  private static Object defaultValue(int toDispatchId, Class<?> toType) {
    if (!toType.isPrimitive()) {
      return null;
    }
    switch (toDispatchId) {
      case DispatchId.BOOL:
        return Boolean.FALSE;
      case DispatchId.INT8:
        return Byte.valueOf((byte) 0);
      case DispatchId.INT16:
        return Short.valueOf((short) 0);
      case DispatchId.INT32:
      case DispatchId.UINT8:
      case DispatchId.UINT16:
      case DispatchId.VARINT32:
      case DispatchId.VAR_UINT32:
        return Integer.valueOf(0);
      case DispatchId.INT64:
      case DispatchId.UINT32:
      case DispatchId.UINT64:
      case DispatchId.VARINT64:
      case DispatchId.TAGGED_INT64:
      case DispatchId.VAR_UINT64:
      case DispatchId.TAGGED_UINT64:
        return Long.valueOf(0L);
      case DispatchId.FLOAT32:
        return Float.valueOf(0.0f);
      case DispatchId.FLOAT64:
        return Double.valueOf(0.0d);
      default:
        return null;
    }
  }

  private static int domain(int dispatchId, Class<?> type) {
    switch (dispatchId) {
      case DispatchId.BOOL:
        return BOOL;
      case DispatchId.STRING:
        return STRING;
      case DispatchId.INT8:
      case DispatchId.INT16:
      case DispatchId.INT32:
      case DispatchId.VARINT32:
      case DispatchId.INT64:
      case DispatchId.VARINT64:
      case DispatchId.TAGGED_INT64:
        return SIGNED_INT;
      case DispatchId.UINT8:
      case DispatchId.UINT16:
      case DispatchId.UINT32:
      case DispatchId.VAR_UINT32:
      case DispatchId.UINT64:
      case DispatchId.VAR_UINT64:
      case DispatchId.TAGGED_UINT64:
      case DispatchId.EXT_UINT8:
      case DispatchId.EXT_UINT16:
      case DispatchId.EXT_UINT32:
      case DispatchId.EXT_VAR_UINT32:
      case DispatchId.EXT_UINT64:
      case DispatchId.EXT_VAR_UINT64:
        return UNSIGNED_INT;
      case DispatchId.FLOAT16:
      case DispatchId.BFLOAT16:
      case DispatchId.FLOAT32:
      case DispatchId.FLOAT64:
        return FLOAT;
      default:
        if (type == BigDecimal.class) {
          return DECIMAL;
        }
        return NONE;
    }
  }

  private static boolean isNumeric(int domain) {
    return domain == SIGNED_INT || domain == UNSIGNED_INT || domain == FLOAT || domain == DECIMAL;
  }

  private static int integerBits(int dispatchId, Class<?> type) {
    switch (normalizedIntegerId(dispatchId, type)) {
      case DispatchId.INT8:
      case DispatchId.UINT8:
        return 8;
      case DispatchId.INT16:
      case DispatchId.UINT16:
        return 16;
      case DispatchId.INT32:
      case DispatchId.UINT32:
        return 32;
      case DispatchId.INT64:
      case DispatchId.UINT64:
        return 64;
      default:
        return -1;
    }
  }

  private static int normalizedIntegerId(int dispatchId, Class<?> type) {
    switch (dispatchId) {
      case DispatchId.VARINT32:
        return DispatchId.INT32;
      case DispatchId.VARINT64:
      case DispatchId.TAGGED_INT64:
        return DispatchId.INT64;
      case DispatchId.VAR_UINT32:
      case DispatchId.EXT_VAR_UINT32:
        return DispatchId.UINT32;
      case DispatchId.VAR_UINT64:
      case DispatchId.TAGGED_UINT64:
      case DispatchId.EXT_VAR_UINT64:
        return DispatchId.UINT64;
      case DispatchId.EXT_UINT8:
        return DispatchId.UINT8;
      case DispatchId.EXT_UINT16:
        return DispatchId.UINT16;
      case DispatchId.EXT_UINT32:
        return DispatchId.UINT32;
      case DispatchId.EXT_UINT64:
        return DispatchId.UINT64;
      default:
        return dispatchId == DispatchId.UNKNOWN ? dispatchId(type) : dispatchId;
    }
  }

  private static int normalizedFloatId(int dispatchId, Class<?> type) {
    int id = dispatchId == DispatchId.UNKNOWN ? dispatchId(type) : dispatchId;
    switch (id) {
      case DispatchId.FLOAT16:
      case DispatchId.BFLOAT16:
      case DispatchId.FLOAT32:
      case DispatchId.FLOAT64:
        return id;
      default:
        return DispatchId.UNKNOWN;
    }
  }

  private static boolean isUIntWrapper(Class<?> type) {
    return type == UInt8.class
        || type == UInt16.class
        || type == UInt32.class
        || type == UInt64.class;
  }

  private static boolean isFiniteFloat(int dispatchId, Object value) {
    switch (normalizedFloatId(dispatchId, value.getClass())) {
      case DispatchId.FLOAT16:
        return ((Float16) value).isFinite();
      case DispatchId.BFLOAT16:
        return ((BFloat16) value).isFinite();
      case DispatchId.FLOAT32:
        return Float.isFinite(((Float) value).floatValue());
      case DispatchId.FLOAT64:
        return Double.isFinite(((Double) value).doubleValue());
      default:
        return false;
    }
  }

  private static boolean isNaNFloat(int dispatchId, Object value) {
    switch (normalizedFloatId(dispatchId, value.getClass())) {
      case DispatchId.FLOAT16:
        return ((Float16) value).isNaN();
      case DispatchId.BFLOAT16:
        return ((BFloat16) value).isNaN();
      case DispatchId.FLOAT32:
        return Float.isNaN(((Float) value).floatValue());
      case DispatchId.FLOAT64:
        return Double.isNaN(((Double) value).doubleValue());
      default:
        return false;
    }
  }

  private static boolean isInfiniteFloat(int dispatchId, Object value) {
    switch (normalizedFloatId(dispatchId, value.getClass())) {
      case DispatchId.FLOAT16:
        return ((Float16) value).isInfinite();
      case DispatchId.BFLOAT16:
        return ((BFloat16) value).isInfinite();
      case DispatchId.FLOAT32:
        return Float.isInfinite(((Float) value).floatValue());
      case DispatchId.FLOAT64:
        return Double.isInfinite(((Double) value).doubleValue());
      default:
        return false;
    }
  }

  private static boolean isFloatZero(int dispatchId, Object value) {
    switch (normalizedFloatId(dispatchId, value.getClass())) {
      case DispatchId.FLOAT16:
        return ((Float16) value).isZero();
      case DispatchId.BFLOAT16:
        return ((BFloat16) value).isZero();
      case DispatchId.FLOAT32:
        return ((Float) value).floatValue() == 0.0f;
      case DispatchId.FLOAT64:
        return ((Double) value).doubleValue() == 0.0d;
      default:
        return false;
    }
  }

  private static boolean isFloatOne(int dispatchId, Object value) {
    switch (normalizedFloatId(dispatchId, value.getClass())) {
      case DispatchId.FLOAT16:
        return ((Float16) value).toBits() == Float16.ONE.toBits();
      case DispatchId.BFLOAT16:
        return ((BFloat16) value).toBits() == BFloat16.ONE.toBits();
      case DispatchId.FLOAT32:
        return ((Float) value).floatValue() == 1.0f;
      case DispatchId.FLOAT64:
        return ((Double) value).doubleValue() == 1.0d;
      default:
        return false;
    }
  }

  private static boolean isNegativeFloat(int dispatchId, Object value) {
    switch (normalizedFloatId(dispatchId, value.getClass())) {
      case DispatchId.FLOAT16:
        return ((Float16) value).signbit();
      case DispatchId.BFLOAT16:
        return ((BFloat16) value).signbit();
      case DispatchId.FLOAT32:
        return Float.floatToRawIntBits(((Float) value).floatValue()) < 0;
      case DispatchId.FLOAT64:
        return Double.doubleToRawLongBits(((Double) value).doubleValue()) < 0;
      default:
        return false;
    }
  }

  private static Object floatZero(int toDispatchId, Class<?> toType, boolean negative) {
    switch (normalizedFloatId(toDispatchId, toType)) {
      case DispatchId.FLOAT16:
        return negative ? Float16.NEGATIVE_ZERO : Float16.ZERO;
      case DispatchId.BFLOAT16:
        return negative ? BFloat16.NEGATIVE_ZERO : BFloat16.ZERO;
      case DispatchId.FLOAT32:
        return Float.valueOf(negative ? -0.0f : 0.0f);
      case DispatchId.FLOAT64:
        return Double.valueOf(negative ? -0.0d : 0.0d);
      default:
        throw new IllegalArgumentException("Not a floating scalar: " + toType);
    }
  }

  private static Object floatInfinity(
      int toDispatchId, Class<?> toType, boolean negative, String fieldName) {
    switch (normalizedFloatId(toDispatchId, toType)) {
      case DispatchId.FLOAT16:
        return negative ? Float16.NEGATIVE_INFINITY : Float16.POSITIVE_INFINITY;
      case DispatchId.BFLOAT16:
        return negative ? BFloat16.NEGATIVE_INFINITY : BFloat16.POSITIVE_INFINITY;
      case DispatchId.FLOAT32:
        return Float.valueOf(negative ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY);
      case DispatchId.FLOAT64:
        return Double.valueOf(negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY);
      default:
        throw dataError(DispatchId.UNKNOWN, Float.class, toDispatchId, toType, fieldName);
    }
  }

  private static DeserializationException dataError(
      int fromDispatchId, Class<?> fromType, int toDispatchId, Class<?> toType, String fieldName) {
    return dataError(fromDispatchId, fromType, toDispatchId, toType, fieldName, null);
  }

  private static DeserializationException dataError(
      int fromDispatchId,
      Class<?> fromType,
      int toDispatchId,
      Class<?> toType,
      String fieldName,
      Throwable cause) {
    String message =
        "Cannot convert compatible field "
            + fieldName
            + " from "
            + typeName(fromDispatchId, fromType)
            + " to "
            + typeName(toDispatchId, toType);
    return cause == null
        ? new DeserializationException(message)
        : new DeserializationException(message, cause);
  }

  private static String typeName(int dispatchId, Class<?> type) {
    return type.getName() + "(dispatchId=" + dispatchId + ")";
  }
}

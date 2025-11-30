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

package org.apache.fory.format.type;

import org.apache.fory.util.Preconditions;

/** Keep in sync with Type::type in arrow/type_fwd.h */
public enum ArrowType {
  /// A NULL type having no physical storage
  NA, // NA = 0

  /// Boolean as 1 bit, LSB bit-packed ordering
  BOOL,

  /// Unsigned 8-bit little-endian integer
  UINT8,

  /// Signed 8-bit little-endian integer
  INT8,

  /// Unsigned 16-bit little-endian integer
  UINT16,

  /// Signed 16-bit little-endian integer
  INT16,

  /// Unsigned 32-bit little-endian integer
  UINT32,

  /// Signed 32-bit little-endian integer
  INT32,

  /// Unsigned 64-bit little-endian integer
  UINT64,

  /// Signed 64-bit little-endian integer
  INT64,

  /// 2-byte floating point value
  HALF_FLOAT,

  /// 4-byte floating point value
  FLOAT,

  /// 8-byte floating point value
  DOUBLE,

  /// UTF8 variable-length string as List<Char>
  STRING,

  /// Variable-length bytes (no guarantee of UTF8-ness)
  BINARY,

  /// Fixed-size binary. Each value occupies the same number of bytes
  FIXED_SIZE_BINARY,

  /// int32_t days since the UNIX epoch
  DATE32,

  /// int64_t milliseconds since the UNIX epoch
  DATE64,

  /// Exact timestamp encoded with int64 since UNIX epoch
  /// Default unit millisecond
  TIMESTAMP,

  /// Time as signed 32-bit integer, representing either seconds or
  /// milliseconds since midnight
  TIME32,

  /// Time as signed 64-bit integer, representing either microseconds or
  /// nanoseconds since midnight
  TIME64,

  /// YEAR_MONTH interval in SQL style
  INTERVAL_MONTHS,

  /// DAY_TIME interval in SQL style
  INTERVAL_DAY_TIME,

  /// Precision- and scale-based decimal type with 128 bits.
  DECIMAL128,

  /// Precision- and scale-based decimal type with 256 bits.
  DECIMAL256,

  /// A list of some logical data type
  LIST,

  /// Struct of logical types
  STRUCT,

  /// Sparse unions of logical types
  SPARSE_UNION,

  /// Dense unions of logical types
  DENSE_UNION,

  /// Dictionary-encoded type, also called "categorical" or "factor"
  /// in other programming languages. Holds the dictionary value
  /// type but not the dictionary itself, which is part of the
  /// ArrayData struct
  DICTIONARY,

  /// Map, a repeated struct logical type
  MAP,

  /// Custom data type, implemented by user
  EXTENSION,

  /// Fixed size list of some logical type
  FIXED_SIZE_LIST,

  /// Measure of elapsed time in either seconds, milliseconds, microseconds
  /// or nanoseconds.
  DURATION,

  /// Like STRING, but with 64-bit offsets
  LARGE_STRING,

  /// Like BINARY, but with 64-bit offsets
  LARGE_BINARY,

  /// Like LIST, but with 64-bit offsets
  LARGE_LIST,

  // Leave this at the end
  MAX_ID,

  /// Defined for backward-compatibility.
  DECIMAL(DECIMAL128.getId()),

  /// Fory added type for cross-language serialization.
  FORY_TYPE_TAG(256),
  FORY_SET(257),
  FORY_PRIMITIVE_BOOL_ARRAY(258),
  FORY_PRIMITIVE_SHORT_ARRAY(259),
  FORY_PRIMITIVE_INT_ARRAY(260),
  FORY_PRIMITIVE_LONG_ARRAY(261),
  FORY_PRIMITIVE_FLOAT_ARRAY(262),
  FORY_PRIMITIVE_DOUBLE_ARRAY(263),
  FORY_STRING_ARRAY(264),
  FORY_SERIALIZED_OBJECT(265),
  FORY_BUFFER(266);

  private short id;

  ArrowType() {
    Preconditions.checkArgument(ordinal() < Short.MAX_VALUE);
    this.id = (short) ordinal();
  }

  ArrowType(int id) {
    Preconditions.checkArgument(id < Short.MAX_VALUE && id >= 0);
    this.id = (short) id;
  }

  public short getId() {
    return id;
  }
}

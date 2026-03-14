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

package org.apache.fory.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.apache.fory.config.LongEncoding;

/**
 * Annotation to specify encoding options for 64-bit signed integer fields.
 *
 * <p>When applied to a field of type {@code long} or {@code Long}, this annotation controls how the
 * value is serialized using different encoding strategies:
 *
 * <ul>
 *   <li>{@link LongEncoding#VARINT} (default): Variable-length encoding, compact for small values
 *       (type_id=7)
 *   <li>{@link LongEncoding#FIXED}: Fixed 8-byte encoding, consistent size (type_id=6)
 *   <li>{@link LongEncoding#TAGGED}: Tagged encoding that uses 4 bytes for values in range
 *       [-1073741824, 1073741823], otherwise 9 bytes (type_id=8)
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * public class MyStruct {
 *   {@literal @}Int64Type(encoding = LongEncoding.VARINT64)  // Variable-length (default)
 *   long compactId;
 *
 *   {@literal @}Int64Type(encoding = LongEncoding.FIXED_INT64)     // Fixed 8-byte
 *   long fixedTimestamp;
 *
 *   {@literal @}Int64Type(encoding = LongEncoding.TAGGED_INT64) // Tagged encoding
 *   long taggedValue;
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface Int64Type {
  /**
   * The encoding strategy to use for this int64 field.
   *
   * @return the encoding type for serialization
   */
  LongEncoding encoding() default LongEncoding.VARINT;
}

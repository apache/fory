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
 * Annotation to mark a field as an unsigned 64-bit integer.
 *
 * <p>When applied to a field of type {@code long}, this annotation indicates that the value should
 * be serialized as an unsigned 64-bit integer with a valid range of [0, 18446744073709551615].
 *
 * <p>Different encoding strategies are available:
 *
 * <ul>
 *   <li>{@link LongEncoding#VARINT} (default): Variable-length encoding (VAR_UINT64, type_id=14),
 *       compact for small values
 *   <li>{@link LongEncoding#FIXED}: Fixed 8-byte encoding (UINT64, type_id=13), consistent size
 *   <li>{@link LongEncoding#TAGGED}: Tagged encoding (TAGGED_UINT64, type_id=15) that uses 4 bytes
 *       for values in range [0, 2147483647], otherwise 9 bytes
 * </ul>
 *
 * <p>Benefits:
 *
 * <ul>
 *   <li>With {@link LongEncoding#VARINT}: skips zigzag encoding overhead for non-negative values
 *   <li>With {@link LongEncoding#TAGGED}: uses unsigned range [0, 2147483647] for 4-byte encoding
 *       instead of signed range [-1073741824, 1073741823]
 *   <li>Compatible with languages that have native unsigned integer types (e.g., Rust's u64, Go's
 *       uint64, C++'s uint64_t)
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * public class MyStruct {
 *   {@literal @}Uint64Type(encoding = LongEncoding.VARINT64)  // Variable-length (default)
 *   long compactId;
 *
 *   {@literal @}Uint64Type(encoding = LongEncoding.FIXED_INT64)     // Fixed 8-byte
 *   long fixedTimestamp;
 *
 *   {@literal @}Uint64Type(encoding = LongEncoding.TAGGED_INT64) // Tagged encoding
 *   long taggedValue;
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface Uint64Type {
  /**
   * The encoding strategy to use for this uint64 field.
   *
   * @return the encoding type for serialization
   */
  LongEncoding encoding() default LongEncoding.VARINT;
}

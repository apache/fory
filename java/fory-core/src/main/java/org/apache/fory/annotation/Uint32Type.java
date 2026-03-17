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

/**
 * Annotation to mark a field as an unsigned 32-bit integer.
 *
 * <p>When applied to a field of type {@code int} or {@code long}, this annotation indicates that
 * the value should be serialized as an unsigned 32-bit integer with a valid range of [0,
 * 4294967295].
 *
 * <ul>
 *   <li>{@code compress=true} (default): Uses variable-length encoding (VAR_UINT32, type_id=12)
 *       which is more compact for small values
 *   <li>{@code compress=false}: Uses fixed 4-byte encoding (UINT32, type_id=11) which has
 *       consistent size
 * </ul>
 *
 * <p>Benefits:
 *
 * <ul>
 *   <li>With {@code compress=true}: skips zigzag encoding overhead for non-negative values
 *   <li>Compatible with languages that have native unsigned integer types (e.g., Rust's u32, Go's
 *       uint32, C++'s uint32_t)
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * public class MyStruct {
 *   {@literal @}Uint32Type(compress = true)  // Uses varuint encoding (default)
 *   long compactCount;
 *
 *   {@literal @}Uint32Type(compress = false) // Uses fixed 4-byte encoding
 *   long fixedCount;
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface Uint32Type {
  /**
   * Whether to use variable-length compression for this uint32 field.
   *
   * @return true to use VAR_UINT32 encoding (compact for small values), false to use fixed UINT32
   *     encoding (4 bytes)
   */
  boolean compress() default true;
}

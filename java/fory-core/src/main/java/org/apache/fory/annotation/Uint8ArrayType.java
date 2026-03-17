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
 * Annotation to mark a field as an unsigned 8-bit integer array.
 *
 * <p>When applied to a field of type {@code byte[]}, this annotation indicates that the array
 * should be serialized as an unsigned 8-bit integer array (UINT8_ARRAY, type_id=44) with element
 * values in the range [0, 255].
 *
 * <p>This is useful for compatibility with languages that have native unsigned integer array types
 * (e.g., Rust's Vec&lt;u8&gt;, Go's []uint8, C++'s std::vector&lt;uint8_t&gt;).
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * public class MyStruct {
 *   {@literal @}Uint8ArrayType
 *   byte[] data;  // Will be serialized as unsigned 8-bit array
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface Uint8ArrayType {}

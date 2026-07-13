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

package org.apache.fory.json.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Configures the canonical JSON name, serialization index, and null-inclusion policy of one logical
 * property.
 *
 * <p>Fory JSON first groups an eligible field, getter, and setter by their Java property name. An
 * annotation on any one of those members configures the complete logical property. Repeating the
 * same explicit name, index, or inclusion policy is allowed; conflicting non-default declarations
 * are rejected when the object's metadata is built. {@link JsonIgnore} still removes the configured
 * read or write direction and cannot be overridden by this annotation.
 *
 * <p>On a {@link JsonCreator} parameter, this annotation supplies the input JSON name in
 * parameter-local creator mode. Creator parameter names are explicit and are never transformed by
 * the configured property naming strategy.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
public @interface JsonProperty {
  /** Value indicating that no serialization index is configured. */
  int INDEX_UNKNOWN = -1;

  /**
   * Returns the canonical JSON property name.
   *
   * <p>An empty value on a field or accessor means that the configured property naming strategy is
   * applied to the Java logical name. An empty value is invalid on a creator parameter in
   * parameter-local creator mode.
   */
  String value() default "";

  /**
   * Returns the relative serialization index of this property.
   *
   * <p>Indexed properties not selected by {@link JsonPropertyOrder} are written in ascending index
   * order before unindexed properties. Values greater than or equal to zero are valid and may have
   * gaps; values below {@link #INDEX_UNKNOWN} are invalid. Different writable properties must not
   * use the same index. A non-default index requires a writable field or getter, including when the
   * annotation is declared on a setter or matching {@link JsonCreator} parameter.
   *
   * <p>The index affects serialization order only. It is not a field ID, wire position, array
   * index, record-component index, or creator-parameter index.
   */
  int index() default INDEX_UNKNOWN;

  /**
   * Returns the null-inclusion policy for this property.
   *
   * <p>The policy affects writing only. A non-default policy is invalid when the logical property
   * has no write source, including creator-only input properties. Primitive properties are always
   * written because their Java value cannot be null, but conflicting primitive declarations are
   * still rejected during logical-property normalization.
   */
  Include include() default Include.DEFAULT;

  /** Null-inclusion policies supported by Fory JSON. */
  enum Include {
    /** Inherit the runtime's {@code writeNullFields} setting. */
    DEFAULT,
    /** Always write the property, including when its value is JSON {@code null}. */
    ALWAYS,
    /** Omit the property when its value is Java {@code null}. */
    NON_NULL
  }
}

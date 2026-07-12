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
 * Configures the canonical JSON name and null-inclusion policy of one logical property.
 *
 * <p>Fory JSON first groups an eligible field, getter, and setter by their Java property name. An
 * annotation on any one of those members configures the complete logical property. Repeating the
 * same explicit name or inclusion policy is allowed; conflicting non-default declarations are
 * rejected when the object's metadata is built. {@link JsonIgnore} still removes the configured
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
  /**
   * Returns the canonical JSON property name.
   *
   * <p>An empty value on a field or accessor means that the configured property naming strategy is
   * applied to the Java logical name. An empty value is invalid on a creator parameter in
   * parameter-local creator mode.
   */
  String value() default "";

  /**
   * Returns the null-inclusion policy for this property.
   *
   * <p>The policy affects writing only. A non-default policy is invalid when the logical property
   * has no write source, including creator-only input properties.
   */
  Include include() default Include.DEFAULT;

  /** Null-inclusion policies supported by Fory JSON version 1. */
  enum Include {
    /** Inherit the runtime's {@code writeNullFields} setting. */
    DEFAULT,
    /** Always write the property, including when its value is JSON {@code null}. */
    ALWAYS,
    /** Omit the property when its value is Java {@code null}. */
    NON_NULL
  }
}

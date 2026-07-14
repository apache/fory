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
 * Defines the serialization order of a class's JSON properties.
 *
 * <p>Names are matched first against final JSON property names and then against Java logical
 * property names. Unlisted properties with {@link JsonProperty#index()} follow in ascending index
 * order. Remaining unindexed properties are sorted by final JSON name when {@link #alphabetic()} is
 * true, or retain their existing relative order otherwise.
 *
 * <p>The nearest declaration on the concrete class or one of its superclasses is used. A subclass
 * declaration replaces its superclass declaration as a whole; arrays are never merged. Interface
 * declarations are not considered. The value may be empty only when alphabetic ordering is enabled.
 * Unknown, empty-string, and duplicate property entries are rejected when object metadata is built.
 *
 * <p>This annotation affects serialization only. It cannot reorder protocol metadata such as a
 * subtype discriminator.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface JsonPropertyOrder {
  /** Returns the ordered property prefix, or an empty array for alphabetic-only ordering. */
  String[] value() default {};

  /**
   * Returns whether remaining unindexed properties are sorted by final JSON property name using
   * natural, case-sensitive {@link String} order.
   */
  boolean alphabetic() default false;
}

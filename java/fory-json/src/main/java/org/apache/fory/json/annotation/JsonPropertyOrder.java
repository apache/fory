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
 * order. A {@link JsonUnwrapped} group participates as one position identified by its Java logical
 * property name, as does a write-enabled {@link JsonAnyProperty} or {@link JsonAnyGetter}.
 * Remaining unindexed properties and those positions are sorted by final JSON name or logical group
 * name when {@link #alphabetic()} is true, or retain their existing relative order otherwise.
 * Members inside an unwrapped group retain the child's own order. Dynamic Map entries retain Map
 * iteration order and cannot be named individually by this annotation.
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
  /**
   * Returns the ordered property prefix, or an empty array for alphabetic-only ordering.
   *
   * <p>Each name resolves first against final JSON property names and then against Java logical
   * property names. An unwrapped group and a write-enabled Any property are resolved only by their
   * Java logical names.
   */
  String[] value() default {};

  /**
   * Returns whether remaining unindexed properties are sorted using natural, case-sensitive {@link
   * String} order.
   *
   * <p>Fixed properties use final JSON names; unwrapped groups and write-enabled Any properties use
   * their Java logical names. Dynamic entries inside an Any Map are never sorted.
   */
  boolean alphabetic() default false;
}

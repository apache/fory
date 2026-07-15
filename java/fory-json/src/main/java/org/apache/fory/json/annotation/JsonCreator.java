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
 * Selects the single public constructor or public static factory used to create a JSON object.
 *
 * <p>Fory JSON supports two property-based forms. A non-empty {@link #value()} lists existing Java
 * logical property names in executable-parameter order and reuses their normalized JSON metadata.
 * An empty list normally selects parameter-local mode, where every parameter declares a non-empty
 * {@link JsonProperty} name and may introduce a creator-only input property.
 *
 * <p>For a type with an effective {@link JsonValue} member, an empty list also supports the value
 * form of exactly one {@link String} parameter without {@link JsonProperty}. That creator
 * reconstructs the owning value from its ordinary JSON string representation. No creator is invoked
 * when the input is JSON {@code null}.
 *
 * <p>A property-based creator is the complete object read schema: ordinary properties not selected
 * by it remain write-only, and setters are not invoked after construction. Missing reference
 * parameters receive {@code null}, missing primitive parameters receive their Java zero value, and
 * an explicit JSON {@code null} for a primitive parameter is rejected. The {@link JsonValue} form
 * instead owns the complete String input. Records cannot declare either property-based form, but a
 * record with an effective {@link JsonValue} may annotate its one-String canonical constructor for
 * the value form.
 *
 * <p>A constructor must be public. A factory must be public and static, declare the target class as
 * its exact return type, and return a non-null value whose runtime class is exactly that target.
 * Varargs, zero-argument, generic, synthetic, and bridge creators are rejected. Non-{@link Error}
 * exceptions thrown by a creator are wrapped in a JSON exception with the original cause; errors
 * propagate unchanged.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.CONSTRUCTOR, ElementType.METHOD})
public @interface JsonCreator {
  /**
   * Returns Java logical property names in executable-parameter order.
   *
   * <p>An empty array selects the single-String value form for a type with {@link JsonValue} when
   * its sole parameter has no {@link JsonProperty}; otherwise it selects parameter-local mode.
   * Entries must be non-empty and unique, and the number of entries must equal the executable's
   * parameter count. Parameters in property-list mode must not also declare {@link JsonProperty}.
   */
  String[] value() default {};
}

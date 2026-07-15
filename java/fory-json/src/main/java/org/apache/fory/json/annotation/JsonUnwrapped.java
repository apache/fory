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
 * Flattens the members of one object-valued logical property into its containing JSON object.
 *
 * <p>The property must resolve to an exact raw-class object codec. Maps should use {@link
 * JsonAnyProperty}, {@link JsonAnyGetter}, or {@link JsonAnySetter} instead. A null property emits
 * no members. On input, the property is created only when at least one of its flattened members is
 * present.
 *
 * <p>The optional prefix and suffix are applied to each final child member name after the child's
 * {@link JsonProperty} name and the configured property naming strategy.
 *
 * <p>Mutable classes, records, and {@link JsonCreator} classes may contain or be used as an
 * unwrapped object. Each child and intermediate object must be an exact raw, non-generic class
 * using Fory's standard object mapping. Scalar, array, collection, Map, polymorphic, custom-codec,
 * and JSON Any child roots are rejected. Ordinary leaf properties inside the child retain their
 * normal annotations.
 *
 * <p>The complete group occupies one position in the containing object's serialization order and is
 * identified there by its Java logical property name. A creator-only parameter defines a read-only
 * group; its required {@link JsonProperty#value()} identifies the creator argument and is not a
 * JSON wrapper name. Recursive chains made only of unwrapped properties and final-name or name-hash
 * collisions are rejected when model metadata is resolved.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
public @interface JsonUnwrapped {
  /** Prefix added to every flattened child member name. */
  String prefix() default "";

  /** Suffix added to every flattened child member name. */
  String suffix() default "";
}

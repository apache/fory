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
 * <p>This annotation is invalid on a logical property claimed by {@link JsonAnyProperty} or {@link
 * JsonAnyGetter}, and directly on a {@link JsonAnySetter}. Dynamic members have no fixed JSON name
 * or fixed-property inclusion policy.
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
   * Returns the inclusion policy for this property.
   *
   * <p>The policy affects writing only. A non-default policy is invalid when the logical property
   * has no write source, including creator-only input properties. {@code DEFAULT} inherits the
   * class's {@link JsonInclude} policy, then the runtime setting. {@code NON_DEFAULT} explicitly
   * authorizes evaluating a supported default source; see its safety and construction contract.
   * Other policies retain primitive zero and false.
   */
  Include include() default Include.DEFAULT;

  /**
   * Returns how an explicit JSON null is read for this property. {@code DEFAULT} inherits the
   * builder setting. {@code SKIP} consumes the null without assigning the field, invoking a setter,
   * or marking a creator argument present; existing missing-property defaults and requirements
   * still apply. This setting affects reading only.
   */
  NullHandling onNullRead() default NullHandling.DEFAULT;

  /**
   * Returns how explicit JSON nulls in this property's immediate array/collection elements or map
   * values are read. {@code SKIP} omits an element or map insertion. Nested containers resolve
   * their own settings. Explicit {@code SKIP} and {@code FAIL} require a container representation
   * owned by Fory; explicit {@code SET} also accepts a complete custom container codec. Encoded
   * binary strings and non-container targets reject explicit settings. This does not filter values
   * returned by a custom codec for non-null JSON tokens.
   */
  NullHandling onContentNullRead() default NullHandling.DEFAULT;

  /** Read-side handling of explicit JSON null values. */
  enum NullHandling {
    /** Inherit the corresponding builder default. Valid only on annotations. */
    DEFAULT,
    /** Use the normal type and codec read/assignment, including existing nullability checks. */
    SET,
    /** Consume the null without assigning the property or inserting the container item. */
    SKIP,
    /** Reject an explicit JSON null. Missing properties are unaffected. */
    FAIL
  }

  /** Property inclusion policies supported by Fory JSON. */
  enum Include {
    /** Inherit the class's JsonInclude policy, or the runtime default when absent. */
    DEFAULT,
    /** Always write the property, including when its value is JSON {@code null}. */
    ALWAYS,
    /** Omit the property when its value is Java {@code null}. */
    NON_NULL,
    /**
     * Omit null, empty CharSequence values, arrays, collections, maps, and absent JDK Optional
     * values. With the Scala module, also omit None and supported empty strict Scala collections.
     * Built-in Java types use direct empty checks; other types use the selected codec's {@code
     * isEmpty}, which defaults to false. A custom codec must override it to omit its own empty
     * values; producing an empty JSON representation alone does not make a value empty. Container
     * elements and present Optional or Scala Option contents are not inspected recursively. Zero
     * and false are not empty. A missing field may restore a nonempty constructor default; this
     * output policy does not guarantee lossless round trips for empty values.
     */
    NON_EMPTY,
    /**
     * Explicitly authorize omitting this property when it equals its supported default. The caller
     * guarantees stable defaults, evaluation without externally visible side effects, and recovery
     * of the same logical value when input omits the field. Fory does not prove expression purity.
     * This policy is accepted only on a property or through class-level {@link JsonInclude}; the
     * runtime-wide default rejects it. Class authorization also covers future added properties.
     *
     * <p>Scala declared constructor defaults are evaluated on each write through their compiler
     * methods, with actual preceding parameters from the current object. Missing dependencies or
     * reader-only type fallbacks are not default sources. Java no-argument models and Kotlin models
     * whose selected constructor has defaults for every parameter use one reference object per
     * model metadata initialization. Ordinary no-argument Kotlin models are also supported. This
     * authorization permits the full constructor, other field initializers, and init blocks to run
     * when building that reference. Only authorized property values are retained for comparison.
     * Required constructor arguments are never fabricated, and creator selection is unchanged.
     *
     * <p>Kotlin metadata does not expose default expressions or dependencies. A reference object
     * cannot represent defaults that vary with the current object's parameters, time, or external
     * state. Authorize only properties for which its baseline matches missing-field recovery.
     * Properties without defaults remain included under both property-level and class-level
     * authorization, including required constructor parameters and Kotlin lateinit properties. Java
     * models without a reader no-argument construction path retain their authorized properties.
     * Scala properties without a supported compiler default method also remain included. Declared
     * defaults requiring an unavailable reference object, or failed construction/evaluation, still
     * cause errors.
     *
     * <p>Primitive values compare directly; floating-point values preserve signed zero and never
     * omit non-finite values. References use {@code equals}, and arrays compare contents with exact
     * array types. Different values, including null and empty values, are written. Reading remains
     * independent: explicit null is not missing, and reference defaults are never assigned to
     * decoded objects. Root values and container contents are not filtered.
     */
    NON_DEFAULT
  }
}

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
 * Accepts common off-spec JSON value shapes when reading a property, as Jackson does by default or
 * behind its well-known coercion switches.
 *
 * <p>On a field, getter, setter, or {@link JsonCreator} parameter, the listed features replace the
 * class and builder settings for that logical property; {@code @JsonLenient({})} makes one property
 * strict. On a class, they are the default for every property of that class and replace {@code
 * ForyJsonBuilder.lenientRead}. A class annotation is not inherited by subclasses and does not
 * apply to nested objects, which follow their own class setting. An exact-target {@link JsonMixin}
 * may supply or replace either declaration.
 *
 * <p>Features apply to the property value and to the direct boolean, numeric, and string elements
 * of a property-owned array or collection. They apply only where Fory owns the representation: a
 * property with {@link JsonCodec}, {@link JsonFormat}, or {@link JsonByteArray}, and a type with a
 * registered codec or {@link JsonValue}, keep that representation. A class or builder setting
 * applies silently wherever a feature fits the property type and skips other properties. A
 * declaration on the property itself is rejected when metadata is built if no feature fits its
 * type, or if the property is never read, selects its own representation, is a JSON Any member, or
 * is {@link JsonUnwrapped}. Root values, map values, and optional contents are not affected.
 * Writing is unaffected.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
public @interface JsonLenient {
  /** Returns the enabled features; the default enables all of them. */
  Feature[] value() default {
    Feature.SINGLE_VALUE_AS_ARRAY,
    Feature.SCALAR_AS_STRING,
    Feature.FLOAT_AS_INT,
    Feature.TRIM_QUOTED_SCALAR,
    Feature.EMPTY_STRING_AS_NULL
  };

  /** Independently selectable read coercions. */
  enum Feature {
    /**
     * A JSON value other than an array or {@code null} read into an array or collection is read as
     * its single element, for example {@code "IAB1"} into {@code List<String>}. Byte arrays encoded
     * as binary strings are not affected.
     */
    SINGLE_VALUE_AS_ARRAY,
    /**
     * A JSON number or boolean read into a {@code String} is kept as its exact token text, for
     * example {@code 1.50} as {@code "1.50"}.
     */
    SCALAR_AS_STRING,
    /**
     * A JSON number with a fraction or exponent read into a {@code byte}, {@code short}, {@code
     * int}, {@code long}, their boxed types, or {@code BigInteger} is truncated toward zero, for
     * example {@code 300.7} as {@code 300} and {@code 1e3} as {@code 1000}. Values outside the
     * target range still fail, and a {@code BigInteger} may have at most as many integer digits as
     * its token has characters, and at least 19. Quoted numbers are truncated the same way.
     */
    FLOAT_AS_INT,
    /**
     * A quoted boolean or number read into a boolean or numeric target may have leading and
     * trailing spaces inside the quotes, for example {@code " 300 "}. Escapes and other characters
     * around the value are not accepted, and non-finite floating-point literals such as {@code
     * "NaN"} keep their exact quoted form.
     */
    TRIM_QUOTED_SCALAR,
    /**
     * An empty JSON string read into a boolean, numeric, non-polymorphic object, map, array, or
     * collection target is read as {@code null}; primitive targets receive {@code false} or zero.
     * With {@link #TRIM_QUOTED_SCALAR}, a space-only string read into a boolean or numeric target
     * is also empty. The value is assigned like any other decoded value: explicit-null handling
     * ({@link JsonProperty#onNullRead()} and {@link JsonProperty#onContentNullRead()}) does not
     * apply, while a non-null occurrence still rejects it. Text targets such as strings, enums,
     * date/time values, byte arrays encoded as binary strings, {@link JsonSubTypes} bases, and
     * dynamic {@code Object} targets are not affected.
     */
    EMPTY_STRING_AS_NULL
  }
}

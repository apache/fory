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
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.apache.fory.json.codec.JsonValueCodec;

/**
 * Selects a default codec for the properties of the annotated class that have one declared type.
 * Repeat the annotation for several types; one class may name each type at most once.
 *
 * <pre>{@code
 * @JsonDefaultCodec(type = BigDecimal.class, codec = PlainBigDecimalCodec.class)
 * public class Invoice { ... }
 * }</pre>
 *
 * <p>A property or creator parameter whose raw type equals {@link #type()} uses the codec as if it
 * declared {@code @JsonCodec(value = ...)}; a primitive and its wrapper are the same type.
 * Otherwise a {@link #type()} that equals the direct child of an object array, {@code Collection},
 * {@code AtomicReferenceArray}, {@code Optional}, {@code AtomicReference}, or the value of a {@code
 * Map} (Java containers only) is used as the matching {@link JsonCodec} child member, but only
 * where the container keeps its standard mapping: a registered codec or factory, a module, or a
 * type declaration that owns the container keeps it unchanged. Primitive arrays are not filled.
 *
 * <p>A property that declares its own {@link JsonCodec}, {@link JsonFormat}, {@link JsonByteArray},
 * {@link JsonRawValue}, or {@link JsonUnwrapped}, and JSON Any properties, do not use defaults.
 * Defaults declared on superclasses apply too, and a subclass default for the same type replaces
 * the inherited one. A default codec receives and owns null like a property {@link JsonCodec}.
 * Declarations on interfaces and Kotlin unsigned types are rejected. Defaults do not reach nested
 * object types, which use their own class declarations. A value-class property read and written
 * through its unboxed carrier cannot take a codec, so a default that matches one fails like a
 * declared {@link JsonCodec}; a nullable or container occurrence of the value class is boxed and
 * uses the default. An exact-target {@link JsonMixin} may supply or replace this annotation;
 * supplying or removing either the single or the repeated form replaces both forms declared on the
 * target, and a removal also drops inherited defaults.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(JsonDefaultCodec.List.class)
public @interface JsonDefaultCodec {
  /** Returns the raw property type the codec applies to. */
  Class<?> type();

  /** Returns the codec used for properties of {@link #type()}. */
  Class<? extends JsonValueCodec<?>> codec();

  /** Container of repeated {@link JsonDefaultCodec} declarations on one class. */
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  @interface List {
    JsonDefaultCodec[] value();
  }
}

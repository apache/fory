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
 * Selects default codecs for the properties of the annotated class by their declared type.
 *
 * <p>Codecs may be listed in one annotation, {@code @JsonDefaultCodec({A.class, B.class})}, or in
 * repeated annotations, {@code @JsonDefaultCodec(A.class) @JsonDefaultCodec(B.class)}.
 *
 * <p>Each codec targets the type argument it declares for {@link JsonValueCodec}: a codec of {@code
 * BigDecimal} targets {@code BigDecimal}, a codec of {@code Optional<BigDecimal>} targets exactly
 * {@code Optional<BigDecimal>}. That type must be concrete, without type variables or wildcards,
 * and one class may name at most one codec per target type.
 *
 * <p>A property or creator parameter whose resolved type equals a target uses that codec as if it
 * declared {@code @JsonCodec(value = ...)}; a primitive property or array element matches its
 * wrapper type. Without a whole-type match, a target that equals the direct child of an array,
 * {@code Collection}, {@code AtomicReferenceArray}, {@code Optional}, {@code AtomicReference}, or
 * the value of a {@code Map} is used as the matching {@link JsonCodec} child member, but only where
 * the container keeps its standard mapping: a registered codec or factory, a module, or a type
 * declaration that owns the container keeps it unchanged.
 *
 * <p>A property that declares its own {@link JsonCodec}, {@link JsonFormat}, {@link JsonByteArray},
 * {@link JsonRawValue}, or {@link JsonUnwrapped}, and JSON Any properties, do not use defaults.
 * Defaults declared on superclasses apply too, and a subclass default for the same target type
 * replaces the inherited one. Declarations on interfaces are rejected. Defaults do not reach nested
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
  /** Returns the default codecs; each targets its declared value type. */
  Class<? extends JsonValueCodec<?>>[] value();

  /** Container of repeated {@link JsonDefaultCodec} declarations on one class. */
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  @interface List {
    JsonDefaultCodec[] value();
  }
}

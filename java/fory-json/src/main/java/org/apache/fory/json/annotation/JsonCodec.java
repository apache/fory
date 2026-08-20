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
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.codec.MapKeyCodec;

/**
 * Selects JSON codecs for a type declaration or one logical JSON property.
 *
 * <p>On a class, record, enum, or interface declaration, this annotation defines an inheritable
 * JSON representation contract. Fory traverses both superclass and interface declarations
 * explicitly; this annotation deliberately does not use Java {@link java.lang.annotation.Inherited
 * Inherited}. The most-specific declaration wins. Incomparable declarations that select different
 * codec classes are rejected instead of being resolved by hierarchy traversal order. A concrete
 * declaration or an exact builder registration can disambiguate such a hierarchy. Type declarations
 * may set only {@link #value()}.
 *
 * <p>On a field or effective ordinary getter, this annotation configures the corresponding logical
 * JSON property. On a setter or creator parameter, it configures that parameter's logical property.
 * Repeated declarations for one logical property must be identical. A {@link JsonAnyProperty} field
 * or {@link JsonAnyGetter} may set only {@link #valueCodec()}, and a {@link JsonAnySetter} value
 * parameter uses the configuration for its direct value.
 *
 * <p>{@link #value()} selects a codec for the complete current value and cannot be combined with a
 * child codec. Child codecs apply to one direct child only: {@link #elementCodec()} handles a
 * {@link java.util.Collection}, Java array, or {@link
 * java.util.concurrent.atomic.AtomicReferenceArray} element; {@link #contentCodec()} handles the
 * single value in {@link java.util.Optional} or {@link
 * java.util.concurrent.atomic.AtomicReference}; and {@link #keyCodec()} and {@link #valueCodec()}
 * handle a {@link java.util.Map} key name and value. A non-Collection {@link Iterable} has no child
 * codec support.
 *
 * <p>The selected {@link JsonValueCodec} reads and writes one complete JSON value, including JSON
 * {@code null}, through Fory's concrete reader and writer APIs.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
public @interface JsonCodec {
  /** Returns the codec for the complete current value. */
  Class<? extends JsonValueCodec<?>> value() default NoJsonValueCodec.class;

  /** Returns the codec for one direct Collection, Java array, or AtomicReferenceArray element. */
  Class<? extends JsonValueCodec<?>> elementCodec() default NoJsonValueCodec.class;

  /** Returns the codec for the single value contained by Optional or AtomicReference. */
  Class<? extends JsonValueCodec<?>> contentCodec() default NoJsonValueCodec.class;

  /** Returns the codec that converts one direct Map key to and from a JSON object member name. */
  Class<? extends MapKeyCodec> keyCodec() default NoMapKeyCodec.class;

  /** Returns the codec for one direct Map value. */
  Class<? extends JsonValueCodec<?>> valueCodec() default NoJsonValueCodec.class;

  /** Sentinel used only as the default for JSON value codec members. */
  @Internal
  interface NoJsonValueCodec extends JsonValueCodec<Object> {}

  /** Sentinel used only as the default for {@link #keyCodec()}. */
  @Internal
  interface NoMapKeyCodec extends MapKeyCodec {}
}

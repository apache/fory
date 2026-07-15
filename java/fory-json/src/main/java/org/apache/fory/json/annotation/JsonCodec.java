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
import org.apache.fory.json.codec.JsonValueCodec;

/**
 * Selects the complete JSON value codec for a type declaration, field, readable method, or one
 * exact type-use occurrence.
 *
 * <p>On a class, record, enum, or interface declaration, this annotation defines an inheritable
 * JSON representation contract. Fory traverses both superclass and interface declarations
 * explicitly; this annotation deliberately does not use Java {@link java.lang.annotation.Inherited
 * Inherited}. The most-specific declaration wins. Incomparable declarations that select different
 * codec classes are rejected instead of being resolved by hierarchy traversal order. A concrete
 * declaration, a codec on the current type use, or an exact builder registration can disambiguate
 * such a hierarchy.
 *
 * <p>On a field, this annotation selects the codec for the field's root value. On a method, it is
 * valid only on an effective ordinary JSON getter or record accessor and selects the codec for the
 * return value. A {@link org.apache.fory.json.annotation.JsonAnyProperty JsonAnyProperty} field and
 * a {@link org.apache.fory.json.annotation.JsonAnyGetter JsonAnyGetter} are flattened into their
 * owning object and therefore cannot have a complete root codec, although their nested map values
 * may have type-use codecs. A declaration annotation takes precedence over an annotation on the
 * corresponding root type use.
 *
 * <p>On a type use, this annotation applies only to that resolved value node. It can select the
 * complete codec for a field, accessor or creator value, an array dimension or component, or a
 * nested collection, map-value, optional, or atomic-reference node. It does not apply to map keys,
 * which remain owned by {@link org.apache.fory.json.codec.MapCodec.MapKeyCodec}.
 *
 * <p>The selected {@link JsonValueCodec} reads and writes one complete JSON value, including JSON
 * {@code null}, through Fory's concrete reader and writer APIs.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.TYPE_USE})
public @interface JsonCodec {
  /** Returns the public, concrete codec class selected for this declaration or occurrence. */
  Class<? extends JsonValueCodec<?>> value();
}

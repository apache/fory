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
 * Declares the complete, finite subtype table for an interface or abstract base type.
 *
 * <p>JSON contains only the logical {@link Type#name() subtype name}; it never contains or selects
 * a Java class name. Every entry is resolved from either a class literal or a static binary class
 * name supplied by trusted application code. The table is validated atomically before it can be
 * used, including assignability, concreteness, duplicate-name, security, and type-checker rules.
 * Runtime registration and open subtype discovery are intentionally unsupported.
 *
 * <p>{@link Inclusion#PROPERTY} is the default representation and writes an inline discriminator
 * property as the first object member. {@link Inclusion#WRAPPER_OBJECT} writes one outer member
 * whose name is the subtype name. {@link Inclusion#WRAPPER_ARRAY} writes the subtype name and value
 * as a two-element array. Both wrapper modes delegate the complete subtype representation.
 * Serialization accepts only an exact runtime class present in the table; subclasses of listed
 * entries are not accepted implicitly. Property inclusion requires the ordinary object
 * representation so its members can follow the discriminator, while wrapper inclusions permit an
 * exact custom codec for a subtype. A null base value is encoded as plain JSON {@code null}
 * regardless of the selected inclusion.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface JsonSubTypes {
  /** Returns the complete closed subtype table. */
  Type[] value();

  /**
   * Returns the wire shape used to combine the logical subtype name with its value.
   *
   * <p>The default is {@link Inclusion#PROPERTY}. Readers accept only the configured shape; they do
   * not attempt the other inclusions as compatibility fallbacks.
   */
  Inclusion inclusion() default Inclusion.PROPERTY;

  /**
   * Returns the discriminator property name used by {@link Inclusion#PROPERTY}.
   *
   * <p>This must be non-empty for property inclusion and empty for both wrapper inclusions. The
   * name is already a JSON name and is not transformed by the property naming strategy.
   */
  String property() default "";

  /** Selects the closed wire shape for subtype names and values. */
  enum Inclusion {
    /** Writes the logical name as the first property of the subtype object. */
    PROPERTY,

    /** Writes exactly one outer object member as {@code {"logicalName": subtypeValue}}. */
    WRAPPER_OBJECT,

    /** Writes exactly two array elements as {@code ["logicalName", subtypeValue]}. */
    WRAPPER_ARRAY
  }

  /** Declares one logical subtype name and exactly one Java subtype reference. */
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @Target({})
  @interface Type {
    /**
     * Returns the subtype class literal, or {@link Void} when {@link #className()} is used.
     *
     * <p>Exactly one of this element and {@code className} must be configured.
     */
    Class<?> value() default Void.class;

    /**
     * Returns the subtype's binary class name, or an empty string when {@link #value()} is used.
     *
     * <p>This form permits an API module to describe implementations without a compile-time
     * dependency on their JAR. The fixed class loader configured on {@code ForyJsonBuilder}
     * resolves the name without class initialization. Resolution is table-eager: the complete
     * finite table must validate before any entry is published.
     */
    String className() default "";

    /** Returns the non-empty, case-sensitive logical name stored in JSON. */
    String name();
  }
}

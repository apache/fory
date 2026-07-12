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
 * <p>The default representation writes an inline discriminator property as the first object member.
 * Wrapper-object mode instead writes one outer member whose name is the subtype name and whose
 * value is the complete subtype representation.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface JsonSubTypes {
  /** Returns whether the logical subtype name is encoded as an outer wrapper object. */
  boolean wrapperObject() default false;

  /**
   * Returns the inline discriminator property name.
   *
   * <p>This must be non-empty in inline mode and empty in wrapper-object mode. The name is already
   * a JSON name and is not transformed by the property naming strategy.
   */
  String property() default "";

  /** Returns the complete closed subtype table. */
  Type[] value();

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
     * resolves the name without class initialization.
     */
    String className() default "";

    /** Returns the non-empty, case-sensitive logical name stored in JSON. */
    String name();
  }
}

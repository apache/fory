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

package org.apache.fory.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
public @interface ForyField {

  /** Largest field tag ID accepted by the cross-language protocol. */
  int MAX_ID = (1 << 29) - 1;

  /** Controls polymorphism behavior for struct fields in cross-language serialization. */
  enum Dynamic {
    /**
     * Auto-detect based on declared type (default).
     *
     * <ul>
     *   <li>Xlang mode: only interface/abstract class are treated as POLYMORPHIC, concrete classes
     *       are treated as FINAL (no type info written)
     *   <li>Java native mode: all classes without {@code final} modifier are treated as POLYMORPHIC
     * </ul>
     */
    AUTO,

    /** Treat as final/sealed - no type info written, uses declared type's serializer directly. */
    FALSE,

    /** Treat as polymorphic - type info written to support subtypes at runtime. */
    TRUE
  }

  /**
   * Field tag ID for schema evolution mode.
   *
   * <ul>
   *   <li>When {@code >= 0}: Uses this numeric ID instead of field name string for compact encoding
   *   <li>When {@code -1} (default): No tag ID is configured; uses field name with meta string
   *       encoding
   * </ul>
   *
   * <p>Configured tag IDs are in {@code [0, 2^29)}; they must be unique within the class and stable
   * across versions. Values below {@code -1} or above {@link #MAX_ID} are invalid.
   */
  int id() default -1;

  /**
   * Controls polymorphism behavior for this field in cross-language serialization.
   *
   * <ul>
   *   <li>{@link Dynamic#AUTO} (default): Interface/abstract types are dynamic(polymorphic),
   *       concrete types are not dynamic
   *   <li>{@link Dynamic#FALSE}: No type info written, uses declared type's serializer
   *   <li>{@link Dynamic#TRUE}: Type info written to support runtime subtypes
   * </ul>
   *
   * <p>Default: AUTO (concrete struct types are final, interface/abstract are polymorphic)
   */
  Dynamic dynamic() default Dynamic.AUTO;
}

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
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface ForyField {

  /**
   * Field tag ID for schema evolution mode (REQUIRED).
   *
   * <ul>
   *   <li>When >= 0: Uses this numeric ID instead of field name string for compact encoding
   *   <li>When -1: Explicitly opt-out of tag ID, use field name with meta string encoding
   * </ul>
   *
   * <p>Must be unique within the class (except -1) and stable across versions.
   */
  int id();

  /**
   * Whether this field can be null. When set to false (default), Fory skips writing the null flag
   * (saves 1 byte). When set to true, Fory writes null flag for nullable fields. Default: false
   * (field is non-nullable, aligned with xlang protocol defaults)
   */
  boolean nullable() default false;

  /**
   * Whether to track references for this field. When set to false (default): - Avoids adding the
   * object to IdentityMap (saves hash map overhead) - Skips writing ref tracking flag (saves 1 byte
   * when combined with nullable=false) When set to true, enables reference tracking for
   * shared/circular references. Default: false (no reference tracking, aligned with xlang protocol
   * defaults)
   */
  boolean ref() default false;
}

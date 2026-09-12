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

package org.apache.fory.format.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the version window in which a row-codec field is logically present. The window is
 * inclusive on the left and exclusive on the right, so {@code since=2, until=5} means versions 2,
 * 3, and 4.
 *
 * <p>Only effective when the codec builder is configured with {@code withSchemaEvolution()};
 * otherwise the annotation is ignored and the field is treated as always present.
 *
 * <p>May be placed on a field or an accessor method, which also covers a record component. Record
 * components are covered by {@code FIELD} and {@code METHOD} rather than {@code
 * ElementType.RECORD_COMPONENT}: the compiler propagates a record-component annotation to the
 * backing field and the accessor method (the targets it declares), and the codec reads the
 * annotation from those elements. {@code RECORD_COMPONENT} is a JDK 16 enum constant and would
 * break this Java 11 module at runtime, so it is intentionally omitted.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface ForyVersion {
  /** First version (inclusive) that contains this field. Defaults to the class base version. */
  int since() default 1;

  /**
   * First version (exclusive) that no longer contains this field. The default {@link
   * Integer#MAX_VALUE} means the field has no upper bound and is present in every version from
   * {@link #since()} onward.
   */
  int until() default Integer.MAX_VALUE;
}

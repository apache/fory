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
 * <p>Place it on a field of a class, on a record component, or on an accessor method of an
 * interface (a non-static method with no parameters and a return value). The codec reads it from no
 * other method, so building a schema-evolution codec fails when another method of the bean or of a
 * type that it inherits fields from carries it, such as the getter of a class field. A class bean
 * does not read the annotations of an interface that it implements: they belong to the codec of
 * that interface bean, so annotate the fields of the class. Record components are covered by {@code
 * FIELD} and {@code METHOD} rather than {@code ElementType.RECORD_COMPONENT}: the compiler
 * propagates a record-component annotation to the backing field and the accessor method (the
 * targets it declares), and the codec reads the annotation from those elements. {@code
 * RECORD_COMPONENT} is a JDK 16 enum constant and would break this Java 11 module at runtime, so it
 * is intentionally omitted.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface ForyRowVersion {
  /**
   * First version (inclusive) that contains this field. Defaults to 1, the first version. Must be
   * at least 1 and less than {@link Integer#MAX_VALUE}.
   */
  int since() default 1;

  /**
   * First version (exclusive) that no longer contains this field. The default {@link
   * Integer#MAX_VALUE} means the field has no upper bound and is present in every version from
   * {@link #since()} onward. Only a method of the interface named by {@link
   * ForyRowSchema#removedFields()} may set it; a live field or accessor that sets it fails the
   * codec build.
   */
  int until() default Integer.MAX_VALUE;
}

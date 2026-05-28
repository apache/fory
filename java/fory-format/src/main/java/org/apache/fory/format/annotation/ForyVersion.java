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
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT})
public @interface ForyVersion {
  /** First version (inclusive) that contains this field. Defaults to the class base version. */
  int since() default 1;

  /** First version (exclusive) that no longer contains this field. */
  int until() default Integer.MAX_VALUE;
}

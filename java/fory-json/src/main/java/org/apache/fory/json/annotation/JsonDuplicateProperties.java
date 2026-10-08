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
 * Sets whether reading the annotated class rejects a repeated known JSON property, overriding
 * {@code ForyJsonBuilder.failOnDuplicateProperties} for this class.
 *
 * <p>A known property is a read-enabled field, setter, {@code JsonCreator} parameter, or a name
 * flattened in by {@link JsonUnwrapped}. When {@link #fail()} is {@code true}, such a property
 * appearing twice in one JSON object fails the read, including when one occurrence is an explicit
 * {@code null} that {@link JsonProperty#onNullRead()} skips. Unknown and ignored names and Any
 * entries are not checked. When {@code false}, the last occurrence wins.
 *
 * <p>This annotation is not inherited by subclasses and does not apply to nested objects, which
 * follow their own class setting. An exact-target {@link JsonMixin} may supply or replace it. It
 * applies only where Fory reads the class as a JSON object; a class read through {@link JsonValue},
 * a {@link JsonCodec}, a registered codec, or as an enum is unaffected. Writing is unaffected.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface JsonDuplicateProperties {
  /** Returns whether a repeated known property fails the read. */
  boolean fail();
}

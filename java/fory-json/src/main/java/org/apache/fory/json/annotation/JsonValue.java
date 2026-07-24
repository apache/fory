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
 * Selects the single {@link String} field or public zero-argument method that represents its owning
 * object as one ordinary JSON string value.
 *
 * <p>The selected member must have the exact type {@link String}. The owning object is replaced by
 * that string for JSON writing, so its other properties are not written. A {@code null} owner or a
 * non-null owner whose selected member returns {@code null} is written as JSON {@code null}.
 *
 * <p>Reading requires a single {@link JsonCreator} constructor or static factory with one exact
 * {@link String} parameter. The creator annotation must have an empty {@link JsonCreator#value()}
 * and its parameter must not declare {@link JsonProperty}. JSON {@code null} is read as a null
 * owner without invoking the creator.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface JsonValue {}

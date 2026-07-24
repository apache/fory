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
 * Selects the raw JSON representation of an exact {@link String} field or getter.
 *
 * <p>A String is written directly into the JSON stream without quoting, escaping, or validation.
 * This is a trusted write-only escape hatch for a fixed ordinary JSON property; reading still
 * expects a normal JSON string. Null inclusion and omission follow the property's normal
 * configuration, and an included null is written as JSON {@code null}.
 *
 * <p>For a String property, the caller is responsible for supplying a complete valid JSON value.
 * Invalid or untrusted raw content can make the complete output invalid or unsafe.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface JsonRawValue {}

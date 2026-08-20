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
 * Passes otherwise unknown JSON object members to one method during reading.
 *
 * <p>The method must be a public instance method with signature {@code void method(String, V)}. The
 * decoded JSON member name is passed as the first argument and its value as the second. The method
 * is invoked for every repeated unknown member. A primitive value parameter is supported, but JSON
 * {@code null} is rejected before invocation. The method cannot be synthetic, bridge, varargs, or
 * generic.
 *
 * <p>This annotation has no logical property name and does not infer or claim a backing field. It
 * may be paired with one {@link JsonAnyGetter} whose resolved value type is equal after primitive
 * types are boxed, but it cannot be mixed with {@link JsonAnyProperty}. {@link JsonProperty} cannot
 * be declared on the annotated method.
 *
 * <p>At most one effective method in a type hierarchy may use this annotation. Records and types
 * using {@link JsonCreator} cannot use an any-setter because they are constructed only after their
 * input members have been collected.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface JsonAnySetter {}

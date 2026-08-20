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
import java.util.Map;

/**
 * Uses a method-supplied {@code Map<String, V>} as flattened members of the containing JSON object.
 *
 * <p>The method must be a public instance method with no arguments and a resolved {@link Map}
 * return type whose key type is exactly {@link String}. A null Map writes no members; otherwise its
 * entries are written in Map iteration order. Dynamic keys are emitted unchanged by property naming
 * strategies. The method cannot be synthetic, bridge, varargs, or generic.
 *
 * <p>A JavaBean getter uses its normal Java logical property name; another valid method uses its
 * method name. This annotation claims that complete logical property, so same-named ordinary
 * fields, getters, and setters are not also mapped as a fixed property. {@link JsonProperty} cannot
 * be declared on any member of the claimed logical property.
 *
 * <p>{@link JsonPropertyOrder} can place the complete entry sequence by this Java logical name.
 * Dynamic entries always retain Map iteration order.
 *
 * <p>At most one effective method in a type hierarchy may use this annotation. It may be paired
 * with one {@link JsonAnySetter} whose resolved value type is equal after primitive types are
 * boxed, but it cannot be mixed with {@link JsonAnyProperty}. Raw Maps, wildcard or unresolved
 * keys, and other key types are invalid.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface JsonAnyGetter {}

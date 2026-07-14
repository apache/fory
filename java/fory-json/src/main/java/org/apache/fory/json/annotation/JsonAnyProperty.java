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
 * Flattens the entries of one field-backed {@code Map<String, V>} into the containing JSON object.
 *
 * <p>The annotated field is not represented by a nested JSON member. Its entries are written as
 * object members in Map iteration order, and otherwise unknown input members are stored in the Map.
 * A null Map writes no members. During reading, an existing Map is reused; a null non-final field
 * is initialized when the first unknown member is encountered.
 *
 * <p>{@link JsonPropertyOrder} can place the complete entry sequence by the field's Java logical
 * name. Property naming strategies do not transform dynamic keys, and entries always retain Map
 * iteration order.
 *
 * <p>This annotation claims the complete logical property with the field's Java name, so a
 * same-named getter or setter is not also mapped as a fixed property. {@link JsonIgnore} may select
 * the read or write direction, but it cannot disable both. {@link JsonProperty} cannot be declared
 * on any member of the claimed logical property.
 *
 * <p>At most one effective field in a type hierarchy may use this annotation. It cannot be mixed
 * with {@link JsonAnyGetter} or {@link JsonAnySetter}, and its resolved key type must be exactly
 * {@link String}. Raw Maps, wildcard or unresolved keys, and other key types are invalid. A final
 * field used for reading must already contain a mutable Map.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface JsonAnyProperty {}

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
 * Declares the exact model class configured by this JSON annotation Mixin.
 *
 * <p>A Mixin source must be a named interface, top-level abstract class, or static abstract member
 * class. It must not extend or implement another type and is never instantiated. Its directly
 * declared annotated fields, methods, constructors, and parameters structurally select existing
 * declarations on {@link #target()}. Target fields match by name and erased type, methods by name
 * plus erased parameter and return types, constructors by erased parameter types, and parameters by
 * executable and index. The target declaration continues to own its Java type, generic type,
 * access, invocation, and value.
 *
 * <p>A Mixin may contribute {@link JsonAnyGetter}, {@link JsonAnyProperty}, {@link JsonAnySetter},
 * {@link JsonBase64}, {@link JsonCodec}, {@link JsonCreator}, {@link JsonIgnore}, {@link
 * JsonProperty}, {@link JsonPropertyOrder}, {@link JsonRawValue}, {@link JsonSubTypes}, {@link
 * JsonUnwrapped}, and {@link JsonValue}. {@link JsonType} remains a marker declared directly on a
 * model and cannot be contributed or removed by a Mixin. A contributed annotation completely
 * replaces the target annotation of the same type at the matched declaration; annotation members
 * are not merged individually. Use {@link JsonMixinRemove} for explicit removal.
 *
 * <p>A contributed {@link JsonCodec} follows the same codec resolution as a codec declared on the
 * target. An exact codec registered with {@link
 * org.apache.fory.json.ForyJsonBuilder#registerCodec(Class,
 * org.apache.fory.json.codec.JsonValueCodec)} takes precedence, while an effective type-level
 * {@code JsonCodec} takes precedence over the target's built-in JSON mapping.
 *
 * <p>Register a source with {@link org.apache.fory.json.ForyJsonBuilder#registerMixin(Class)}.
 * Registration applies only to the exact declared target. Registering a second source for that
 * target replaces the first source for subsequently built JSON runtimes. A source containing no
 * mapping annotations is a no-op and therefore clears an earlier overlay when registered later for
 * the same target.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface JsonMixin {
  /**
   * Returns the exact non-primitive, non-array, non-annotation model class configured by this
   * source.
   */
  Class<?> target();
}

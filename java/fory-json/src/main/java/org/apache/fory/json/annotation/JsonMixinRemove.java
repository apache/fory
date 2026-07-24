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

import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Removes selected target annotations from one matched declaration in a JSON Mixin.
 *
 * <p>This annotation is valid only on a {@link JsonMixin} source. Each value must be a mapping
 * annotation supported by {@link JsonMixin}, and that annotation's Java {@link Target} must permit
 * the selector kind. {@link JsonType}, {@link JsonMixin}, {@code JsonMixinRemove}, and unrelated
 * annotations cannot be removed. The list must be non-empty and contain no duplicates, and one
 * source declaration cannot both contribute and remove the same annotation type.
 *
 * <p>Removal affects only the matched physical declaration in the exact target context before
 * normal logical-property merging. Removing an absent annotation is allowed, which lets a
 * type-level removal mask an inherited {@link JsonCodec} or {@link JsonPropertyOrder}. It never
 * removes a field, method, constructor, parameter, or logical property.
 *
 * @see org.apache.fory.json.ForyJsonBuilder#registerMixin(Class)
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({
  ElementType.TYPE,
  ElementType.FIELD,
  ElementType.METHOD,
  ElementType.CONSTRUCTOR,
  ElementType.PARAMETER
})
public @interface JsonMixinRemove {
  /** Returns the Fory JSON annotation types removed from the matched target declaration. */
  Class<? extends Annotation>[] value();
}

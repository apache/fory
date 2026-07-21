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
 * Excludes one field-backed logical property from JSON reading, writing, or both directions.
 *
 * <p>An eligible backing field, getter, and setter with the same Java property name form one
 * logical property. This field annotation removes the selected direction from that complete
 * property, so a same-named accessor cannot restore it. The annotation does not make an otherwise
 * ineligible field or accessor eligible and cannot be overridden by {@link JsonProperty}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface JsonIgnore {
  /** Whether this field is ignored when reading JSON into an object. */
  boolean ignoreRead() default true;

  /** Whether this field is ignored when writing an object as JSON. */
  boolean ignoreWrite() default true;
}

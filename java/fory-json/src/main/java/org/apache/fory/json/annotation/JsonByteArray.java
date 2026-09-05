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
 * Selects the JSON representation of one exact {@code byte[]} field or getter for both reading and
 * writing. Unannotated byte arrays use a quoted standard Base64 string.
 *
 * <p>Null inclusion and omission follow the property's normal configuration, and an included null
 * is written as JSON {@code null}. This annotation cannot be combined with {@link JsonCodec} on the
 * same logical property.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface JsonByteArray {
  /** Returns the representation used when reading and writing this property. */
  Format value();

  /** The supported JSON representations of a byte array. */
  enum Format {
    /** A quoted standard Base64 string with padding. */
    BASE64,
    /** A JSON array of signed byte values in the range {@code [-128, 127]}. */
    ARRAY
  }
}

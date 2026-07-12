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

package org.apache.fory.json;

/** Defines the closed set of Java-logical-name to JSON-name transformations. */
public enum PropertyNamingStrategy {
  /** Preserve the Java logical property name exactly. */
  IDENTITY,
  /**
   * Convert camel-case words to lower snake case.
   *
   * <p>A boundary is inserted before an uppercase code point when it follows a lowercase letter or
   * digit, or when it ends an uppercase acronym immediately before a lowercase letter. Existing
   * underscores are preserved, digits remain attached to their surrounding word, and case
   * conversion is performed by Unicode code point. An explicit non-empty {@code JsonProperty} value
   * bypasses this transformation.
   */
  LOWER_SNAKE_CASE
}

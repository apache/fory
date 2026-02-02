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

package org.apache.fory.resolver;

import org.apache.fory.annotation.CodegenInvoke;

/**
 * Reference mode for field serialization, determining how null values and references are handled.
 *
 * <ul>
 *   <li>{@link #NONE} - No ref tracking, field is non-nullable. Write data directly without any
 *       flags.
 *   <li>{@link #NULL_ONLY} - Field is nullable but no ref tracking. Write null flag (-3) for null,
 *       or not-null flag (-1) then data.
 *   <li>{@link #TRACKING} - Ref tracking enabled (implies nullable). Write ref flag: -3 for null,
 *       -1 for first occurrence (then data), or ref id (>=0) for subsequent references.
 * </ul>
 */
public enum RefMode {
  /** No ref tracking, field is non-nullable. Write data directly. */
  NONE,

  /** Field is nullable but no ref tracking. Write null/not-null flag then data. */
  NULL_ONLY,

  /** Ref tracking enabled (implies nullable). Write ref flags and handle shared references. */
  TRACKING;

  /**
   * Determine RefMode from trackingRef and nullable flags.
   *
   * @param trackingRef whether reference tracking is enabled for this field
   * @param nullable whether this field can be null
   * @return the appropriate RefMode
   */
  @CodegenInvoke
  public static RefMode of(boolean trackingRef, boolean nullable) {
    if (trackingRef) {
      return TRACKING;
    } else if (nullable) {
      return NULL_ONLY;
    } else {
      return NONE;
    }
  }

  @CodegenInvoke
  public static RefMode ofNullable(boolean nullable) {
    if (nullable) {
      return NULL_ONLY;
    }
    return NONE;
  }
}

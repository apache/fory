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

package org.apache.fory.serializer.collection;

public class MapFlags {
  /** Whether track key ref. */
  public static final int TRACKING_KEY_REF = 0b1;

  /** Whether key has null. */
  public static final int KEY_HAS_NULL = 0b10;

  /** Whether key is declared type. */
  public static final int KEY_DECL_TYPE = 0b100;

  /** Whether track value ref. */
  public static final int TRACKING_VALUE_REF = 0b1000;

  /** Whether value has null. */
  public static final int VALUE_HAS_NULL = 0b10000;

  /** Whether value is declared type. */
  public static final int VALUE_DECL_TYPE = 0b100000;

  // When key or value is null that entry will be serialized as a new chunk with size 1.
  // In such cases, chunk size will be skipped writing.
  /** Both key and value are null. */
  public static final int KV_NULL = KEY_HAS_NULL | VALUE_HAS_NULL;

  /** Key is null, value type is declared type, and ref tracking for value is disabled. */
  public static final int NULL_KEY_VALUE_DECL_TYPE = KEY_HAS_NULL | VALUE_DECL_TYPE;

  /** Key is null, value type is declared type, and ref tracking for value is enabled. */
  public static final int NULL_KEY_VALUE_DECL_TYPE_TRACKING_REF =
      KEY_HAS_NULL | VALUE_DECL_TYPE | TRACKING_VALUE_REF;

  /** Value is null, key type is declared type, and ref tracking for key is disabled. */
  public static final int NULL_VALUE_KEY_DECL_TYPE = VALUE_HAS_NULL | KEY_DECL_TYPE;

  /** Value is null, key type is declared type, and ref tracking for key is enabled. */
  public static final int NULL_VALUE_KEY_DECL_TYPE_TRACKING_REF =
      VALUE_HAS_NULL | KEY_DECL_TYPE | TRACKING_KEY_REF;
}

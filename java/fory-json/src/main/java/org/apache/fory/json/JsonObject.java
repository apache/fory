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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Canonical mutable representation of a JSON object parsed through the dynamic {@code Object}
 * codec.
 *
 * <p>Keys are JSON member names and insertion order is retained. Values use natural JSON mappings:
 * strings, booleans, numbers, {@code null}, nested {@link JsonArray} values, and nested {@link
 * JsonObject} values. Typed map targets continue to use their requested Java map type.
 */
public final class JsonObject extends LinkedHashMap<String, Object> {
  public JsonObject() {
    // JSON input has no trusted object size; start from zero to avoid default capacity
    // amplification for many tiny objects.
    super(0);
  }

  public JsonObject(Map<String, ?> values) {
    super(values);
  }
}

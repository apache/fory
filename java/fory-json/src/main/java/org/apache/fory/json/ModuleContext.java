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

import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.codec.ObjectCodec;

/** Build-time registration surface exposed to a {@link ForyJsonModule}. */
public interface ModuleContext {
  /**
   * Registers a complete codec for one eligible exact class. Dedicated reader/writer scalar types
   * and {@code byte[]}, {@code String[]}, and {@code long[]} cannot be registered exactly. A
   * resolver-owned {@link ObjectCodec} must be supplied through a {@link JsonCodecFactory}.
   */
  <T> void registerCodec(Class<T> type, JsonValueCodec<T> codec);

  /**
   * Registers a resolver-owned codec factory for one eligible exact class. The same types as {@link
   * #registerCodec(Class, JsonValueCodec)} are rejected.
   */
  <T> void registerCodec(Class<T> type, JsonCodecFactory factory);

  /** Registers the target Mixin declared by {@code mixinType}. */
  void registerMixin(Class<?> mixinType);

  /** Registers one cold-path factory for parameterized type families. */
  void registerCodecFactory(JsonCodecFactory factory);
}

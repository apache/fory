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

import java.util.Collections;
import java.util.List;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.reflect.TypeRef;

/** Creates a complete JSON codec during a resolver-owned cold type lookup. */
@FunctionalInterface
public interface JsonCodecFactory {
  /**
   * Returns a codec for {@code type}, or {@code null} when this factory does not own it.
   *
   * @param runtimeType true only when {@code type} was selected from an actual value during a
   *     dynamic write; factories must not infer this from resolver state
   */
  JsonValueCodec<?> create(TypeRef<?> type, JsonTypeResolver resolver, boolean runtimeType);

  /**
   * Returns the deterministic semantic identity of this factory configuration.
   *
   * <p>A configurable factory must override this method and include every option that can change
   * the created codec class, object model, or generated operations. The default class name is
   * sufficient only for a configuration-free factory.
   */
  default String factoryKey() {
    return getClass().getName();
  }

  /**
   * Returns runtime classes represented by an exact closed root codec.
   *
   * <p>Dedicated reader/writer scalar types, {@code byte[]}, {@code String[]}, and {@code long[]}
   * are not allowed.
   */
  @Internal
  default List<Class<?>> handledRuntimeClasses() {
    return Collections.emptyList();
  }
}

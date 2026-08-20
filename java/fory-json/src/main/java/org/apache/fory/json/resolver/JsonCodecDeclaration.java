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

package org.apache.fory.json.resolver;

import java.lang.reflect.Type;
import org.apache.fory.json.codec.JsonValueCodec;

/** Immutable result of resolving one direct or inherited {@code @JsonCodec} declaration. */
final class JsonCodecDeclaration {
  private final Class<? extends JsonValueCodec<?>> codecClass;
  private final Class<?>[] origins;
  private final boolean inherited;

  JsonCodecDeclaration(
      Class<? extends JsonValueCodec<?>> codecClass, Class<?>[] origins, boolean inherited) {
    this.codecClass = codecClass;
    this.origins = origins.clone();
    this.inherited = inherited;
  }

  Class<? extends JsonValueCodec<?>> codecClass() {
    return codecClass;
  }

  boolean inherited() {
    return inherited;
  }

  JsonValueCodec<?> bind(Type targetType, Class<?> targetRawType, JsonValueCodec<?> codec) {
    if (!inherited) {
      return codec;
    }
    return new InheritedJsonValueCodec(targetType, targetRawType, codecClass, origins, codec);
  }
}

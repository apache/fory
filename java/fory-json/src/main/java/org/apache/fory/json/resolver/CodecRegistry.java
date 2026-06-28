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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.fory.json.codec.JsonCodec;
import org.apache.fory.util.Preconditions;

/** Registry for user-supplied JSON codecs. */
public final class CodecRegistry {
  private final ConcurrentMap<Class<?>, JsonCodec> codecs;

  public CodecRegistry() {
    codecs = new ConcurrentHashMap<>();
  }

  private CodecRegistry(ConcurrentMap<Class<?>, JsonCodec> codecs) {
    this.codecs = codecs;
  }

  public <T> void register(Class<T> type, JsonCodec codec) {
    Preconditions.checkNotNull(type);
    Preconditions.checkNotNull(codec);
    codecs.put(type, codec);
  }

  public JsonCodec get(Class<?> type) {
    return codecs.get(type);
  }

  public CodecRegistry copy() {
    ConcurrentMap<Class<?>, JsonCodec> copied = new ConcurrentHashMap<>(codecs.size());
    for (Map.Entry<Class<?>, JsonCodec> entry : codecs.entrySet()) {
      copied.put(entry.getKey(), entry.getValue());
    }
    return new CodecRegistry(copied);
  }
}

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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.util.Preconditions;

/**
 * Builder-side registry of exact user-supplied {@link JsonValueCodec} bindings.
 *
 * <p>Registration is keyed by class identity and replaces any previous codec for the exact class. A
 * {@link JsonSharedRegistry} receives a copy when a runtime is built, separating later builder
 * mutation from an existing {@code ForyJson}. The deterministic {@link #codegenKey()} describes
 * codec classes that can affect generated source without retaining codec instances in process-wide
 * code-generation naming state.
 */
public final class CodecRegistry {
  private final ConcurrentMap<Class<?>, JsonValueCodec<?>> codecs;

  public CodecRegistry() {
    codecs = new ConcurrentHashMap<>();
  }

  private CodecRegistry(ConcurrentMap<Class<?>, JsonValueCodec<?>> codecs) {
    this.codecs = codecs;
  }

  public <T> void register(Class<T> type, JsonValueCodec<T> codec) {
    Preconditions.checkNotNull(type);
    Preconditions.checkNotNull(codec);
    codecs.put(type, codec);
  }

  public JsonValueCodec<?> get(Class<?> type) {
    return codecs.get(type);
  }

  public CodecRegistry copy() {
    ConcurrentMap<Class<?>, JsonValueCodec<?>> copied = new ConcurrentHashMap<>(codecs.size());
    for (Map.Entry<Class<?>, JsonValueCodec<?>> entry : codecs.entrySet()) {
      copied.put(entry.getKey(), entry.getValue());
    }
    return new CodecRegistry(copied);
  }

  public String codegenKey() {
    List<Map.Entry<Class<?>, JsonValueCodec<?>>> entries = new ArrayList<>(codecs.entrySet());
    entries.sort(Comparator.comparing(entry -> entry.getKey().getName()));
    StringBuilder builder = new StringBuilder(entries.size() * 48);
    for (Map.Entry<Class<?>, JsonValueCodec<?>> entry : entries) {
      builder
          .append(entry.getKey().getName())
          .append('=')
          .append(entry.getValue().getClass().getName())
          .append(';');
    }
    return builder.toString();
  }
}

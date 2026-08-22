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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.JsonCodecFactory;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.util.Preconditions;

/**
 * Builder-side registry of exact user-supplied {@link JsonValueCodec} bindings.
 *
 * <p>Registration is keyed by class identity and replaces any previous codec for the exact class. A
 * {@code JsonConfig} receives a copy when a runtime is built, separating later builder mutation
 * from an existing {@code ForyJson}. The runtime registry reads that owned snapshot directly. The
 * deterministic {@link #codegenKey()} describes codec classes that can affect generated source
 * without retaining codec instances in process-wide code-generation naming state.
 */
public final class CodecRegistry {
  private final ConcurrentMap<Class<?>, JsonValueCodec<?>> codecs;
  private final ConcurrentMap<Class<?>, FactoryBinding> factories;

  public CodecRegistry() {
    codecs = new ConcurrentHashMap<>();
    factories = new ConcurrentHashMap<>();
  }

  private CodecRegistry(
      ConcurrentMap<Class<?>, JsonValueCodec<?>> codecs,
      ConcurrentMap<Class<?>, FactoryBinding> factories) {
    this.codecs = codecs;
    this.factories = factories;
  }

  public <T> void register(Class<T> type, JsonValueCodec<T> codec) {
    Preconditions.checkNotNull(type);
    Preconditions.checkNotNull(codec);
    codecs.put(type, codec);
    factories.remove(type);
  }

  public <T> void registerFactory(Class<T> type, JsonCodecFactory factory) {
    Preconditions.checkNotNull(type);
    Preconditions.checkNotNull(factory);
    factories.put(type, FactoryBinding.create(type, factory));
    codecs.remove(type);
  }

  public JsonValueCodec<?> get(Class<?> type) {
    return codecs.get(type);
  }

  public FactoryBinding getFactory(Class<?> type) {
    return factories.get(type);
  }

  /** Returns a read-only view of the exact factory registrations in this snapshot. */
  @Internal
  public Map<Class<?>, FactoryBinding> factoryBindings() {
    return Collections.unmodifiableMap(factories);
  }

  public boolean contains(Class<?> type) {
    return codecs.containsKey(type) || factories.containsKey(type);
  }

  /** Adds registrations not already owned by this registry. */
  public void putDefaults(CodecRegistry defaults) {
    for (Map.Entry<Class<?>, JsonValueCodec<?>> entry : defaults.codecs.entrySet()) {
      Class<?> type = entry.getKey();
      if (!contains(type)) {
        codecs.put(type, entry.getValue());
      }
    }
    for (Map.Entry<Class<?>, FactoryBinding> entry : defaults.factories.entrySet()) {
      Class<?> type = entry.getKey();
      if (!contains(type)) {
        factories.put(type, entry.getValue());
      }
    }
  }

  public CodecRegistry copy() {
    ConcurrentMap<Class<?>, JsonValueCodec<?>> copied = new ConcurrentHashMap<>(codecs.size());
    for (Map.Entry<Class<?>, JsonValueCodec<?>> entry : codecs.entrySet()) {
      copied.put(entry.getKey(), entry.getValue());
    }
    ConcurrentMap<Class<?>, FactoryBinding> copiedFactories =
        new ConcurrentHashMap<>(factories.size());
    copiedFactories.putAll(factories);
    return new CodecRegistry(copied, copiedFactories);
  }

  public String codegenKey() {
    List<Map.Entry<Class<?>, JsonValueCodec<?>>> entries = new ArrayList<>(codecs.entrySet());
    entries.sort(Comparator.comparing(entry -> entry.getKey().getName()));
    StringBuilder builder = new StringBuilder(entries.size() * 48);
    for (Map.Entry<Class<?>, JsonValueCodec<?>> entry : entries) {
      appendIdentity(builder, entry.getKey().getName());
      appendIdentity(builder, entry.getValue().getClass().getName());
    }
    List<Map.Entry<Class<?>, FactoryBinding>> factoryEntries =
        new ArrayList<>(factories.entrySet());
    factoryEntries.sort(Comparator.comparing(entry -> entry.getKey().getName()));
    for (Map.Entry<Class<?>, FactoryBinding> entry : factoryEntries) {
      FactoryBinding binding = entry.getValue();
      appendIdentity(builder, entry.getKey().getName());
      appendIdentity(builder, binding.factory.getClass().getName());
      appendIdentity(builder, binding.key);
      for (Class<?> runtimeType : binding.handledRuntimeClasses) {
        appendIdentity(builder, runtimeType.getName());
      }
    }
    return builder.toString();
  }

  private static void appendIdentity(StringBuilder builder, String value) {
    builder.append(value.length()).append(':').append(value);
  }

  /** Immutable build-time snapshot of one exact factory registration. */
  @Internal
  public static final class FactoryBinding {
    private final JsonCodecFactory factory;
    private final String key;
    private final List<Class<?>> handledRuntimeClasses;

    private FactoryBinding(
        JsonCodecFactory factory, String key, List<Class<?>> handledRuntimeClasses) {
      this.factory = factory;
      this.key = key;
      this.handledRuntimeClasses = handledRuntimeClasses;
    }

    private static FactoryBinding create(Class<?> target, JsonCodecFactory factory) {
      String key = Preconditions.checkNotNull(factory.factoryKey());
      if (key.isEmpty()) {
        throw new IllegalArgumentException("JSON codec factory key must not be empty");
      }
      List<Class<?>> declared = Preconditions.checkNotNull(factory.handledRuntimeClasses());
      ArrayList<Class<?>> handled = new ArrayList<>(declared.size());
      IdentityHashMap<Class<?>, Boolean> identities = new IdentityHashMap<>();
      HashSet<String> names = new HashSet<>();
      for (Class<?> runtimeType : declared) {
        Preconditions.checkNotNull(runtimeType);
        if (!target.isAssignableFrom(runtimeType)) {
          throw new IllegalArgumentException(
              runtimeType.getName() + " is not a subtype of " + target.getName());
        }
        if (identities.put(runtimeType, Boolean.TRUE) != null
            || !names.add(runtimeType.getName())) {
          throw new IllegalArgumentException(
              "Duplicate handled runtime class " + runtimeType.getName());
        }
        handled.add(runtimeType);
      }
      handled.sort(Comparator.comparing(Class::getName));
      return new FactoryBinding(factory, key, Collections.unmodifiableList(handled));
    }

    public JsonCodecFactory factory() {
      return factory;
    }

    public String key() {
      return key;
    }

    public List<Class<?>> handledRuntimeClasses() {
      return handledRuntimeClasses;
    }
  }
}

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.fory.json.annotation.JsonMixin;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.resolver.CodecRegistry;

/** Owns one transactional module installation performed by {@link ForyJsonBuilder#build()}. */
final class ModuleInstaller implements ModuleContext {
  private final CodecRegistry codecs = new CodecRegistry();
  private final Map<Class<?>, Class<?>> mixins = new IdentityHashMap<>();
  private final List<JsonCodecFactory> factories = new ArrayList<>();
  private final List<String> factoryIdentities = new ArrayList<>();
  private final Set<String> moduleIdentities = new HashSet<>();
  private final Set<String> fallbackIdentities = new HashSet<>();
  private String installingModule;
  private boolean frozen;

  static InstalledModules install(
      List<ForyJsonModule> modules,
      CodecRegistry applicationCodecs,
      Map<Class<?>, Class<?>> applicationMixins) {
    ModuleInstaller installer = new ModuleInstaller();
    try {
      for (ForyJsonModule module : modules) {
        String key = Objects.requireNonNull(module.moduleKey(), "moduleKey");
        if (key.isEmpty()) {
          throw new IllegalArgumentException("Fory JSON module key must not be empty");
        }
        String identity = module.getClass().getName() + ':' + key;
        if (!installer.moduleIdentities.add(identity)) {
          throw new IllegalArgumentException("Duplicate Fory JSON module " + identity);
        }
        installer.installingModule = identity;
        module.install(installer);
      }
      installer.frozen = true;
      CodecRegistry mergedCodecs = applicationCodecs.copy();
      mergedCodecs.putDefaults(installer.codecs);
      Map<Class<?>, Class<?>> mergedMixins = new IdentityHashMap<>(installer.mixins);
      mergedMixins.putAll(applicationMixins);
      return new InstalledModules(
          mergedCodecs,
          mergedMixins,
          installer.factories.toArray(new JsonCodecFactory[0]),
          Collections.unmodifiableList(new ArrayList<>(installer.factoryIdentities)));
    } finally {
      installer.frozen = true;
      installer.installingModule = null;
    }
  }

  @Override
  public <T> void registerCodec(Class<T> type, JsonValueCodec<T> codec) {
    checkMutable();
    requireNewExact(type);
    codecs.register(type, codec);
  }

  @Override
  public <T> void registerCodec(Class<T> type, JsonCodecFactory factory) {
    checkMutable();
    requireNewExact(type);
    codecs.registerFactory(type, factory);
  }

  @Override
  public void registerMixin(Class<?> mixinType) {
    checkMutable();
    Class<?> target = mixinTarget(mixinType);
    if (mixins.put(target, mixinType) != null) {
      throw new IllegalArgumentException(
          "Duplicate module JSON Mixin target " + target.getName() + " from " + installingModule);
    }
  }

  @Override
  public void registerCodecFactory(JsonCodecFactory factory) {
    checkMutable();
    Objects.requireNonNull(factory, "factory");
    String key = Objects.requireNonNull(factory.factoryKey(), "factoryKey");
    if (key.isEmpty()) {
      throw new IllegalArgumentException("JSON codec factory key must not be empty");
    }
    String identity = factory.getClass().getName() + ':' + key;
    if (!fallbackIdentities.add(identity)) {
      throw new IllegalArgumentException("Duplicate JSON codec factory " + identity);
    }
    factories.add(factory);
    factoryIdentities.add(identity);
  }

  private void requireNewExact(Class<?> type) {
    Objects.requireNonNull(type, "type");
    if (codecs.contains(type)) {
      throw new IllegalArgumentException(
          "Duplicate module JSON codec target " + type.getName() + " from " + installingModule);
    }
  }

  private void checkMutable() {
    if (frozen || installingModule == null) {
      throw new IllegalStateException("Fory JSON module context is no longer mutable");
    }
  }

  static Class<?> mixinTarget(Class<?> mixinType) {
    Objects.requireNonNull(mixinType, "mixinType");
    JsonMixin declaration;
    try {
      declaration = mixinType.getDeclaredAnnotation(JsonMixin.class);
    } catch (RuntimeException | LinkageError e) {
      throw new IllegalArgumentException(
          "Cannot read JSON Mixin declaration " + mixinType.getName(), e);
    }
    if (declaration == null) {
      throw new IllegalArgumentException(
          "JSON Mixin source is missing @JsonMixin: " + mixinType.getName());
    }
    try {
      return declaration.target();
    } catch (RuntimeException | LinkageError e) {
      throw new IllegalArgumentException(
          "Cannot resolve JSON Mixin target for " + mixinType.getName(), e);
    }
  }

  static final class InstalledModules {
    final CodecRegistry codecs;
    final Map<Class<?>, Class<?>> mixins;
    final JsonCodecFactory[] factories;
    final List<String> factoryIdentities;

    private InstalledModules(
        CodecRegistry codecs,
        Map<Class<?>, Class<?>> mixins,
        JsonCodecFactory[] factories,
        List<String> factoryIdentities) {
      this.codecs = codecs;
      this.mixins = mixins;
      this.factories = factories;
      this.factoryIdentities = factoryIdentities;
    }
  }
}

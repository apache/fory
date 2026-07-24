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
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.resolver.CodecRegistry;

/**
 * Build configuration used to create all pooled states of one {@link ForyJson} instance.
 *
 * <p>Scalar settings are fixed at construction. The codec registry is builder-owned mutable input
 * and is copied immediately by the runtime's shared registry; the JSON runtime never mutates it.
 * The type checker uses identity semantics because checker instances may carry different user
 * policy despite sharing a class. {@link #getCodegenHash()} identifies only settings that can
 * change generated source; runtime-only settings such as depth and asynchronous scheduling do not
 * fragment generated class names. Concurrency, per-reader field-name cache, and retained
 * writer-buffer limits are also runtime-only and do not fragment generated class names.
 */
public final class JsonConfig {
  private static final int MAX_CACHED_FIELD_NAMES = 1 << 29;

  private final boolean writeNullFields;
  private final boolean codegenEnabled;
  private final boolean asyncCompilationEnabled;
  private final boolean propertyDiscoveryEnabled;
  private final PropertyNamingStrategy propertyNamingStrategy;
  private final ClassLoader classLoader;
  private final int maxDepth;
  private final int maxCachedFieldNames;
  private final int concurrencyLevel;
  private final int bufferSizeLimitBytes;
  private final CodecRegistry codecRegistry;
  private final Map<Class<?>, Class<?>> mixins;
  private final JsonTypeChecker typeChecker;
  private final JsonTypeCheckContext typeCheckContext;
  private final String codecRegistryKey;
  private final CodegenKey codegenKey;
  private transient int codegenHash;

  JsonConfig(
      boolean writeNullFields,
      boolean codegenEnabled,
      boolean asyncCompilationEnabled,
      boolean propertyDiscoveryEnabled,
      PropertyNamingStrategy propertyNamingStrategy,
      ClassLoader classLoader,
      int maxDepth,
      int maxCachedFieldNames,
      int concurrencyLevel,
      int bufferSizeLimitBytes,
      CodecRegistry codecRegistry,
      Map<Class<?>, Class<?>> mixins,
      JsonTypeChecker typeChecker) {
    this.writeNullFields = writeNullFields;
    this.codegenEnabled = codegenEnabled;
    this.asyncCompilationEnabled = asyncCompilationEnabled;
    this.propertyDiscoveryEnabled = propertyDiscoveryEnabled;
    this.propertyNamingStrategy =
        Objects.requireNonNull(propertyNamingStrategy, "propertyNamingStrategy");
    this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    this.maxDepth = maxDepth;
    validateMaxCachedFieldNames(maxCachedFieldNames);
    this.maxCachedFieldNames = maxCachedFieldNames;
    this.concurrencyLevel = concurrencyLevel;
    this.bufferSizeLimitBytes = bufferSizeLimitBytes;
    this.codecRegistry = codecRegistry;
    this.mixins = immutableMixins(mixins);
    this.typeChecker = typeChecker;
    typeCheckContext = new JsonTypeCheckContext();
    codecRegistryKey = codecRegistry.codegenKey();
    codegenKey =
        new CodegenKey(
            writeNullFields,
            propertyDiscoveryEnabled,
            propertyNamingStrategy,
            codecRegistryKey,
            mixinKey(this.mixins));
  }

  public boolean writeNullFields() {
    return writeNullFields;
  }

  public boolean codegenEnabled() {
    return codegenEnabled;
  }

  public boolean asyncCompilationEnabled() {
    return asyncCompilationEnabled;
  }

  public boolean propertyDiscoveryEnabled() {
    return propertyDiscoveryEnabled;
  }

  /** Returns the fixed property naming strategy used by this runtime. */
  public PropertyNamingStrategy propertyNamingStrategy() {
    return propertyNamingStrategy;
  }

  /** Returns the fixed loader used to resolve annotation-declared subtype class names. */
  public ClassLoader classLoader() {
    return classLoader;
  }

  public int maxDepth() {
    return maxDepth;
  }

  public int maxCachedFieldNames() {
    return maxCachedFieldNames;
  }

  static void validateMaxCachedFieldNames(int maxCachedFieldNames) {
    if (maxCachedFieldNames < 0 || maxCachedFieldNames > MAX_CACHED_FIELD_NAMES) {
      throw new IllegalArgumentException(
          "maxCachedFieldNames must be between 0 and " + MAX_CACHED_FIELD_NAMES);
    }
  }

  public int concurrencyLevel() {
    return concurrencyLevel;
  }

  public int bufferSizeLimitBytes() {
    return bufferSizeLimitBytes;
  }

  public CodecRegistry codecRegistry() {
    return codecRegistry;
  }

  /** Returns the immutable exact target-to-Mixin registration snapshot. */
  @Internal
  public Map<Class<?>, Class<?>> mixins() {
    return mixins;
  }

  public JsonTypeChecker typeChecker() {
    return typeChecker;
  }

  public JsonTypeCheckContext typeCheckContext() {
    return typeCheckContext;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (other == null || getClass() != other.getClass()) {
      return false;
    }
    JsonConfig that = (JsonConfig) other;
    return writeNullFields == that.writeNullFields
        && codegenEnabled == that.codegenEnabled
        && asyncCompilationEnabled == that.asyncCompilationEnabled
        && propertyDiscoveryEnabled == that.propertyDiscoveryEnabled
        && propertyNamingStrategy == that.propertyNamingStrategy
        && classLoader == that.classLoader
        && maxDepth == that.maxDepth
        && maxCachedFieldNames == that.maxCachedFieldNames
        && concurrencyLevel == that.concurrencyLevel
        && bufferSizeLimitBytes == that.bufferSizeLimitBytes
        && typeChecker == that.typeChecker
        && Objects.equals(codecRegistryKey, that.codecRegistryKey)
        && mixins.equals(that.mixins);
  }

  @Override
  public int hashCode() {
    int result = Boolean.hashCode(writeNullFields);
    result = 31 * result + Boolean.hashCode(codegenEnabled);
    result = 31 * result + Boolean.hashCode(asyncCompilationEnabled);
    result = 31 * result + Boolean.hashCode(propertyDiscoveryEnabled);
    result = 31 * result + propertyNamingStrategy.hashCode();
    result = 31 * result + System.identityHashCode(classLoader);
    result = 31 * result + maxDepth;
    result = 31 * result + maxCachedFieldNames;
    result = 31 * result + concurrencyLevel;
    result = 31 * result + bufferSizeLimitBytes;
    result = 31 * result + System.identityHashCode(typeChecker);
    result = 31 * result + codecRegistryKey.hashCode();
    result = 31 * result + mixins.hashCode();
    return result;
  }

  private static Map<Class<?>, Class<?>> immutableMixins(Map<Class<?>, Class<?>> registrations) {
    if (registrations.isEmpty()) {
      return Collections.emptyMap();
    }
    return Collections.unmodifiableMap(new IdentityHashMap<>(registrations));
  }

  private static String mixinKey(Map<Class<?>, Class<?>> mixins) {
    if (mixins.isEmpty()) {
      return "";
    }
    List<Map.Entry<Class<?>, Class<?>>> entries = new ArrayList<>(mixins.entrySet());
    entries.sort(
        Comparator.comparing((Map.Entry<Class<?>, Class<?>> entry) -> entry.getKey().getName())
            .thenComparing(entry -> entry.getValue().getName()));
    StringBuilder builder = new StringBuilder(entries.size() * 64);
    for (Map.Entry<Class<?>, Class<?>> entry : entries) {
      builder
          .append(entry.getKey().getName())
          .append('=')
          .append(entry.getValue().getName())
          .append(';');
    }
    return builder.toString();
  }

  private static final AtomicInteger COUNTER = new AtomicInteger(0);

  // Equal generated source inputs share one map entry, following core generated-code naming model.
  // This process-wide map retains only immutable configuration text and integers, never user
  // classes, codec instances, class loaders, or generated classes.
  private static final ConcurrentMap<CodegenKey, Integer> CODEGEN_ID_MAP =
      new ConcurrentHashMap<>();

  public int getCodegenHash() {
    if (codegenHash == 0) {
      codegenHash = CODEGEN_ID_MAP.computeIfAbsent(codegenKey, key -> COUNTER.incrementAndGet());
    }
    return codegenHash;
  }

  private static final class CodegenKey {
    private final boolean writeNullFields;
    private final boolean propertyDiscoveryEnabled;
    private final PropertyNamingStrategy propertyNamingStrategy;
    private final String codecRegistryKey;
    private final String mixinKey;

    private CodegenKey(
        boolean writeNullFields,
        boolean propertyDiscoveryEnabled,
        PropertyNamingStrategy propertyNamingStrategy,
        String codecRegistryKey,
        String mixinKey) {
      this.writeNullFields = writeNullFields;
      this.propertyDiscoveryEnabled = propertyDiscoveryEnabled;
      this.propertyNamingStrategy = propertyNamingStrategy;
      this.codecRegistryKey = codecRegistryKey;
      this.mixinKey = mixinKey;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (other == null || getClass() != other.getClass()) {
        return false;
      }
      CodegenKey that = (CodegenKey) other;
      return writeNullFields == that.writeNullFields
          && propertyDiscoveryEnabled == that.propertyDiscoveryEnabled
          && propertyNamingStrategy == that.propertyNamingStrategy
          && Objects.equals(codecRegistryKey, that.codecRegistryKey)
          && Objects.equals(mixinKey, that.mixinKey);
    }

    @Override
    public int hashCode() {
      int result =
          Objects.hash(
              writeNullFields, propertyDiscoveryEnabled, propertyNamingStrategy, codecRegistryKey);
      return 31 * result + mixinKey.hashCode();
    }
  }
}

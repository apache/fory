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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.resolver.CodecRegistry;

/**
 * Build configuration used to create all pooled states of one {@link ForyJson} instance.
 *
 * <p>Scalar settings and the codec registry are snapshotted at construction; the JSON runtime never
 * observes later builder mutation.
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
  private final long maxGraphMemoryBytes;
  private final int concurrencyLevel;
  private final int bufferSizeLimitBytes;
  private final CodecRegistry codecRegistry;
  private final Map<Class<?>, Class<?>> mixins;
  private final JsonCodecFactory[] codecFactories;
  private final String[] codecFactoryIdentities;
  private final JsonTypeChecker typeChecker;
  private final JsonTypeCheckContext typeCheckContext;

  JsonConfig(
      boolean writeNullFields,
      boolean codegenEnabled,
      boolean asyncCompilationEnabled,
      boolean propertyDiscoveryEnabled,
      PropertyNamingStrategy propertyNamingStrategy,
      ClassLoader classLoader,
      int maxDepth,
      int maxCachedFieldNames,
      long maxGraphMemoryBytes,
      int concurrencyLevel,
      int bufferSizeLimitBytes,
      CodecRegistry codecRegistry,
      Map<Class<?>, Class<?>> mixins,
      JsonCodecFactory[] codecFactories,
      List<String> factoryIdentities,
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
    validateMaxGraphMemoryBytes(maxGraphMemoryBytes);
    this.maxGraphMemoryBytes = maxGraphMemoryBytes;
    this.concurrencyLevel = concurrencyLevel;
    this.bufferSizeLimitBytes = bufferSizeLimitBytes;
    this.codecRegistry = codecRegistry.copy();
    this.mixins = immutableMixins(mixins);
    this.codecFactories = codecFactories.clone();
    this.codecFactoryIdentities = factoryIdentities.toArray(new String[0]);
    this.typeChecker = typeChecker;
    typeCheckContext = new JsonTypeCheckContext();
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

  /** Returns the approximate root-operation graph-memory gate in bytes. */
  public long maxGraphMemoryBytes() {
    return maxGraphMemoryBytes;
  }

  static void validateMaxCachedFieldNames(int maxCachedFieldNames) {
    if (maxCachedFieldNames < 0 || maxCachedFieldNames > MAX_CACHED_FIELD_NAMES) {
      throw new IllegalArgumentException(
          "maxCachedFieldNames must be between 0 and " + MAX_CACHED_FIELD_NAMES);
    }
  }

  static void validateMaxGraphMemoryBytes(long maxGraphMemoryBytes) {
    if (maxGraphMemoryBytes <= 0) {
      throw new IllegalArgumentException("maxGraphMemoryBytes must be positive");
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

  /** Returns the immutable cold-path codec factory snapshot. */
  @Internal
  public JsonCodecFactory[] codecFactories() {
    return codecFactories.clone();
  }

  /** Returns identities parallel to {@link #codecFactories()}. */
  @Internal
  public String[] codecFactoryIdentities() {
    return codecFactoryIdentities.clone();
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

  private static Map<Class<?>, Class<?>> immutableMixins(Map<Class<?>, Class<?>> registrations) {
    if (registrations.isEmpty()) {
      return Collections.emptyMap();
    }
    return Collections.unmodifiableMap(new IdentityHashMap<>(registrations));
  }
}

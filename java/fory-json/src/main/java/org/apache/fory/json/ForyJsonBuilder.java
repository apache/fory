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

import java.util.Objects;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.resolver.CodecRegistry;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.GraalvmSupport;

/**
 * Configures and creates an immutable, thread-safe {@link ForyJson} facade.
 *
 * <p>The builder owns mutable registration state. {@link #build()} snapshots that registry through
 * the created JSON runtime, so later builder registrations do not mutate an existing runtime.
 * Generated object codecs are independently compiled for the concrete String writer, UTF-8 writer,
 * Latin1 reader, UTF16 reader, and UTF-8 reader paths; asynchronous compilation controls when those
 * path-specific replacements are installed, not codec semantics.
 *
 * <p>Defaults omit null object fields, enable code generation and asynchronous compilation where
 * supported, use JavaBean property discovery, use {@link PropertyNamingStrategy#LOWER_CAMEL_CASE},
 * snapshot the current thread context class loader, allow a nesting depth of 20, use twice the
 * available processors as the pooled-state concurrency level, retain writer buffers up to 2 MiB,
 * and install no custom type checker. Field mode disables getter and setter discovery but continues
 * to discover eligible instance fields across the class hierarchy.
 */
public final class ForyJsonBuilder {
  private boolean writeNullFields;
  private boolean codegenEnabled = true;
  private boolean asyncCompilationEnabled = true;
  private boolean propertyDiscoveryEnabled = true;
  private PropertyNamingStrategy propertyNamingStrategy = PropertyNamingStrategy.LOWER_CAMEL_CASE;
  private ClassLoader classLoader;
  private int maxDepth = ForyJson.DEFAULT_MAX_DEPTH;
  private int concurrencyLevel = Math.max(1, Runtime.getRuntime().availableProcessors() * 2);
  private int bufferSizeLimitBytes = 2 * 1024 * 1024;
  private JsonTypeChecker typeChecker;
  private final CodecRegistry codecRegistry = new CodecRegistry();

  ForyJsonBuilder() {}

  /**
   * Sets the default null-inclusion policy for object properties.
   *
   * <p>This setting applies only when a logical property's merged {@code JsonProperty.include}
   * value is {@code DEFAULT}. {@code ALWAYS} and {@code NON_NULL} override it. Exact custom codecs
   * own their complete representation and do not observe this property-selection setting.
   */
  public ForyJsonBuilder writeNullFields(boolean writeNullFields) {
    this.writeNullFields = writeNullFields;
    return this;
  }

  /**
   * Enables generated object codecs for supported classes. Enabled by default and automatically
   * disabled on Android and in a GraalVM native image.
   */
  public ForyJsonBuilder withCodegen(boolean codegenEnabled) {
    this.codegenEnabled = codegenEnabled;
    return this;
  }

  /**
   * Enables asynchronous runtime compilation for generated object codecs. Enabled by default and
   * automatically disabled when code generation is unavailable.
   */
  public ForyJsonBuilder withAsyncCompilation(boolean asyncCompilationEnabled) {
    this.asyncCompilationEnabled = asyncCompilationEnabled;
    return this;
  }

  /**
   * Enables field mode, where JSON object members are discovered from Java fields only. When
   * disabled, Fory JSON uses the default JavaBean property model: public getters, public setters,
   * and eligible fields are merged as JSON object members.
   */
  public ForyJsonBuilder withFieldMode(boolean fieldMode) {
    this.propertyDiscoveryEnabled = !fieldMode;
    return this;
  }

  /**
   * Sets the naming strategy applied to logical properties without an explicit JSON name.
   *
   * <p>The default is {@link PropertyNamingStrategy#LOWER_CAMEL_CASE}. A non-empty {@code
   * JsonProperty} value is already a JSON name and bypasses the strategy.
   *
   * @throws NullPointerException if {@code propertyNamingStrategy} is null
   */
  public ForyJsonBuilder withPropertyNamingStrategy(PropertyNamingStrategy propertyNamingStrategy) {
    this.propertyNamingStrategy =
        Objects.requireNonNull(propertyNamingStrategy, "propertyNamingStrategy");
    return this;
  }

  /**
   * Sets the fixed class loader used to resolve {@code JsonSubTypes.Type.className} entries.
   *
   * <p>If this method is not called, {@link #build()} snapshots the calling thread's context class
   * loader and falls back to the loader that defined {@link ForyJson} when the context loader is
   * null. The resulting runtime never consults a changing thread context loader.
   *
   * @throws NullPointerException if {@code classLoader} is null
   */
  public ForyJsonBuilder withClassLoader(ClassLoader classLoader) {
    this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    return this;
  }

  /** Sets the maximum nested JSON object/array depth allowed while reading or writing. */
  public ForyJsonBuilder maxDepth(int maxDepth) {
    if (maxDepth < 1) {
      throw new IllegalArgumentException("maxDepth must be positive");
    }
    this.maxDepth = maxDepth;
    return this;
  }

  /** Sets the number of reusable execution states available to concurrent root operations. */
  public ForyJsonBuilder withConcurrencyLevel(int concurrencyLevel) {
    if (concurrencyLevel < 1) {
      throw new IllegalArgumentException("concurrencyLevel must be positive");
    }
    this.concurrencyLevel = concurrencyLevel;
    return this;
  }

  /**
   * Sets the maximum byte-buffer capacity retained by each pooled String and UTF-8 writer.
   *
   * <p>This bounds reusable writer storage after a root operation; it does not limit JSON output
   * size.
   */
  public ForyJsonBuilder withBufferSizeLimitBytes(int bufferSizeLimitBytes) {
    if (bufferSizeLimitBytes < 1) {
      throw new IllegalArgumentException("bufferSizeLimitBytes must be positive");
    }
    this.bufferSizeLimitBytes = bufferSizeLimitBytes;
    return this;
  }

  /**
   * Registers an exact custom JSON codec for {@code type}, replacing an earlier registration.
   *
   * <p>The same codec instance may be called concurrently by pooled JSON states and must therefore
   * be thread-safe. Building snapshots the registration map, although the registered codec objects
   * themselves are intentionally shared.
   */
  public <T> ForyJsonBuilder registerCodec(Class<T> type, JsonValueCodec<T> codec) {
    codecRegistry.register(type, codec);
    return this;
  }

  /**
   * Sets the JSON type checker. Pass {@code null} to allow all non-disallowed classes.
   *
   * <p>The checker must be thread-safe because one {@link ForyJson} instance can be used
   * concurrently.
   */
  public ForyJsonBuilder withTypeChecker(JsonTypeChecker typeChecker) {
    this.typeChecker = typeChecker;
    return this;
  }

  /** Builds a JSON runtime from the current builder state. */
  public ForyJson build() {
    ClassLoader fixedClassLoader = classLoader;
    if (fixedClassLoader == null) {
      fixedClassLoader = Thread.currentThread().getContextClassLoader();
      if (fixedClassLoader == null) {
        fixedClassLoader = ForyJson.class.getClassLoader();
      }
    }
    boolean effectiveCodegen =
        codegenEnabled && !AndroidSupport.IS_ANDROID && !GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE;
    boolean effectiveAsyncCompilation = asyncCompilationEnabled && effectiveCodegen;
    return new ForyJson(
        new JsonConfig(
            writeNullFields,
            effectiveCodegen,
            effectiveAsyncCompilation,
            propertyDiscoveryEnabled,
            propertyNamingStrategy,
            fixedClassLoader,
            maxDepth,
            concurrencyLevel,
            bufferSizeLimitBytes,
            codecRegistry,
            typeChecker));
  }
}

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

import org.apache.fory.json.codec.JsonCodec;
import org.apache.fory.json.resolver.CodecRegistry;

/**
 * Configures and creates an immutable, thread-safe {@link ForyJson} facade.
 *
 * <p>The builder owns mutable registration state. {@link #build()} snapshots that registry through
 * the created JSON runtime, so later builder registrations do not mutate an existing runtime.
 * Generated object codecs are independently compiled for the concrete String writer, UTF-8 writer,
 * Latin1 reader, UTF16 reader, and UTF-8 reader paths; asynchronous compilation controls when those
 * path-specific replacements are installed, not codec semantics.
 *
 * <p>Defaults omit null object fields, enable code generation and asynchronous compilation, use
 * JavaBean property discovery, allow a nesting depth of 20, and install no custom type checker.
 * Field mode disables getter and setter discovery but continues to discover eligible instance
 * fields across the class hierarchy.
 */
public final class ForyJsonBuilder {
  private boolean writeNullFields;
  private boolean codegenEnabled = true;
  private boolean asyncCompilationEnabled = true;
  private boolean propertyDiscoveryEnabled = true;
  private int maxDepth = ForyJson.DEFAULT_MAX_DEPTH;
  private JsonTypeChecker typeChecker;
  private final CodecRegistry codecRegistry = new CodecRegistry();

  ForyJsonBuilder() {}

  /** Writes object fields with null values when enabled. */
  public ForyJsonBuilder writeNullFields(boolean writeNullFields) {
    this.writeNullFields = writeNullFields;
    return this;
  }

  /** Enables generated object codecs for supported classes. Enabled by default. */
  public ForyJsonBuilder withCodegen(boolean codegenEnabled) {
    this.codegenEnabled = codegenEnabled;
    return this;
  }

  /** Enables asynchronous runtime compilation for generated object codecs. Enabled by default. */
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

  /** Sets the maximum nested JSON object/array depth allowed while reading or writing. */
  public ForyJsonBuilder maxDepth(int maxDepth) {
    if (maxDepth < 1) {
      throw new IllegalArgumentException("maxDepth must be positive");
    }
    this.maxDepth = maxDepth;
    return this;
  }

  /**
   * Registers an exact custom JSON codec for {@code type}, replacing an earlier registration.
   *
   * <p>The same codec instance may be called concurrently by pooled JSON states and must therefore
   * be thread-safe. Building snapshots the registration map, although the registered codec objects
   * themselves are intentionally shared.
   */
  public <T> ForyJsonBuilder registerCodec(Class<T> type, JsonCodec<T> codec) {
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
    return new ForyJson(
        new JsonConfig(
            writeNullFields,
            codegenEnabled,
            asyncCompilationEnabled,
            propertyDiscoveryEnabled,
            maxDepth,
            codecRegistry,
            typeChecker));
  }
}

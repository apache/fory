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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.fory.json.resolver.CodecRegistry;

/** Immutable build-time configuration for one {@link ForyJson} instance. */
public final class JsonConfig {
  private final boolean writeNullFields;
  private final boolean codegenEnabled;
  private final boolean asyncCompilationEnabled;
  private final boolean propertyDiscoveryEnabled;
  private final int maxDepth;
  private final CodecRegistry codecRegistry;
  private final String codecRegistryKey;
  private final CodegenKey codegenKey;
  private transient int codegenHash;

  JsonConfig(
      boolean writeNullFields,
      boolean codegenEnabled,
      boolean asyncCompilationEnabled,
      boolean propertyDiscoveryEnabled,
      int maxDepth,
      CodecRegistry codecRegistry) {
    this.writeNullFields = writeNullFields;
    this.codegenEnabled = codegenEnabled;
    this.asyncCompilationEnabled = asyncCompilationEnabled;
    this.propertyDiscoveryEnabled = propertyDiscoveryEnabled;
    this.maxDepth = maxDepth;
    this.codecRegistry = codecRegistry;
    codecRegistryKey = codecRegistry.codegenKey();
    codegenKey = new CodegenKey(writeNullFields, propertyDiscoveryEnabled, codecRegistryKey);
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

  public int maxDepth() {
    return maxDepth;
  }

  public CodecRegistry codecRegistry() {
    return codecRegistry;
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
        && maxDepth == that.maxDepth
        && Objects.equals(codecRegistryKey, that.codecRegistryKey);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        writeNullFields,
        codegenEnabled,
        asyncCompilationEnabled,
        propertyDiscoveryEnabled,
        maxDepth,
        codecRegistryKey);
  }

  private static final AtomicInteger COUNTER = new AtomicInteger(0);

  // Equal generated source inputs share one map entry, following core generated-code naming model.
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
    private final String codecRegistryKey;

    private CodegenKey(
        boolean writeNullFields, boolean propertyDiscoveryEnabled, String codecRegistryKey) {
      this.writeNullFields = writeNullFields;
      this.propertyDiscoveryEnabled = propertyDiscoveryEnabled;
      this.codecRegistryKey = codecRegistryKey;
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
          && Objects.equals(codecRegistryKey, that.codecRegistryKey);
    }

    @Override
    public int hashCode() {
      return Objects.hash(writeNullFields, propertyDiscoveryEnabled, codecRegistryKey);
    }
  }
}

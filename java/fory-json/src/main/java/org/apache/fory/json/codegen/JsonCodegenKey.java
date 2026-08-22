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

package org.apache.fory.json.codegen;

import java.util.Objects;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.PropertyNamingStrategy;

/** Immutable identity for settings which can change generated Fory JSON source. */
@Internal
public final class JsonCodegenKey {
  private final boolean writeNullFields;
  private final boolean propertyDiscoveryEnabled;
  private final String propertyNamingStrategy;
  private final String codecRegistryKey;
  private final String mixinKey;

  public JsonCodegenKey(
      boolean writeNullFields,
      boolean propertyDiscoveryEnabled,
      PropertyNamingStrategy propertyNamingStrategy,
      String codecRegistryKey,
      String mixinKey) {
    this.writeNullFields = writeNullFields;
    this.propertyDiscoveryEnabled = propertyDiscoveryEnabled;
    this.propertyNamingStrategy = propertyNamingStrategy.name();
    this.codecRegistryKey = codecRegistryKey;
    this.mixinKey = mixinKey;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof JsonCodegenKey)) {
      return false;
    }
    JsonCodegenKey that = (JsonCodegenKey) other;
    return writeNullFields == that.writeNullFields
        && propertyDiscoveryEnabled == that.propertyDiscoveryEnabled
        && propertyNamingStrategy.equals(that.propertyNamingStrategy)
        && codecRegistryKey.equals(that.codecRegistryKey)
        && mixinKey.equals(that.mixinKey);
  }

  @Override
  public int hashCode() {
    int result =
        Objects.hash(
            writeNullFields, propertyDiscoveryEnabled, propertyNamingStrategy, codecRegistryKey);
    return 31 * result + mixinKey.hashCode();
  }

  /** Returns the deterministic generated-source identity used in generated class names. */
  public String identity() {
    StringBuilder builder = new StringBuilder();
    builder.append(writeNullFields ? '1' : '0');
    builder.append(propertyDiscoveryEnabled ? '1' : '0');
    append(builder, propertyNamingStrategy);
    append(builder, codecRegistryKey);
    append(builder, mixinKey);
    return builder.toString();
  }

  private static void append(StringBuilder builder, String value) {
    builder.append(value.length()).append(':').append(value);
  }
}

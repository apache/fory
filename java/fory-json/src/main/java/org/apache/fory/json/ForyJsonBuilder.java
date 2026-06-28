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

/** Builder for {@link ForyJson}. */
public final class ForyJsonBuilder {
  private boolean writeNullFields;
  private boolean codegenEnabled = true;
  private int maxDepth = ForyJson.DEFAULT_MAX_DEPTH;
  private final CodecRegistry codecRegistry = new CodecRegistry();

  ForyJsonBuilder() {}

  /** Writes object fields with null values when enabled. */
  public ForyJsonBuilder writeNullFields(boolean writeNullFields) {
    this.writeNullFields = writeNullFields;
    return this;
  }

  /** Enables runtime-generated writers for supported public-field classes. */
  public ForyJsonBuilder withCodegen(boolean codegenEnabled) {
    this.codegenEnabled = codegenEnabled;
    return this;
  }

  /** Sets the maximum nested JSON object/array depth allowed while parsing. */
  public ForyJsonBuilder maxDepth(int maxDepth) {
    if (maxDepth < 1) {
      throw new IllegalArgumentException("maxDepth must be positive");
    }
    this.maxDepth = maxDepth;
    return this;
  }

  /** Registers a custom JSON codec for {@code type}. */
  public <T> ForyJsonBuilder registerCodec(Class<T> type, JsonCodec codec) {
    codecRegistry.register(type, codec);
    return this;
  }

  public ForyJson build() {
    return new ForyJson(writeNullFields, codegenEnabled, maxDepth, codecRegistry);
  }
}

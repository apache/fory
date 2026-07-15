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

package org.apache.fory.json.meta;

import java.lang.reflect.Type;
import org.apache.fory.json.codec.JsonValueCodec;

/** Generated type-use state isolated from ordinary JVM type-use metadata. */
final class GeneratedJsonTypeUse extends JsonTypeUse {
  private final JsonCodecFactory codecFactory;
  private final int variableKey;
  private final int generatedHashCode;

  GeneratedJsonTypeUse(
      Type type,
      Class<? extends JsonValueCodec<?>> codecClass,
      JsonCodecFactory codecFactory,
      String[] codecSources,
      JsonTypeUse[] arguments,
      JsonTypeUse arrayComponent,
      JsonTypeUse[] upperBounds,
      JsonTypeUse[] lowerBounds,
      int variableKey) {
    super(type, codecClass, codecSources, arguments, arrayComponent, upperBounds, lowerBounds);
    this.codecFactory = codecFactory;
    this.variableKey = variableKey;
    int hash = 31 * super.hashCode() + System.identityHashCode(codecFactory);
    generatedHashCode = 31 * hash + variableKey;
  }

  static JsonTypeUse create(
      Type type,
      Class<? extends JsonValueCodec<?>> codecClass,
      JsonCodecFactory codecFactory,
      String[] codecSources,
      JsonTypeUse[] arguments,
      JsonTypeUse arrayComponent,
      JsonTypeUse[] upperBounds,
      JsonTypeUse[] lowerBounds,
      int variableKey) {
    return new GeneratedJsonTypeUse(
        type,
        codecClass,
        codecFactory,
        codecSources,
        arguments,
        arrayComponent,
        upperBounds,
        lowerBounds,
        variableKey);
  }

  @Override
  public JsonCodecFactory codecFactory() {
    return codecFactory;
  }

  @Override
  int generatedVariableKey() {
    return variableKey;
  }

  @Override
  public int hashCode() {
    return generatedHashCode;
  }
}

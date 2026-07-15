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

import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.meta.JsonCodecFactory;

/** Android codec declaration whose generated factory is owned outside the ordinary JVM value. */
final class AndroidJsonCodecDeclaration extends JsonCodecDeclaration {
  private final JsonCodecFactory codecFactory;

  static JsonCodecDeclaration create(
      Class<? extends JsonValueCodec<?>> codecClass,
      JsonCodecFactory codecFactory,
      Class<?>[] origins,
      boolean inherited) {
    return new AndroidJsonCodecDeclaration(codecClass, codecFactory, origins, inherited);
  }

  private AndroidJsonCodecDeclaration(
      Class<? extends JsonValueCodec<?>> codecClass,
      JsonCodecFactory codecFactory,
      Class<?>[] origins,
      boolean inherited) {
    super(codecClass, origins, inherited);
    this.codecFactory = codecFactory;
  }

  @Override
  JsonCodecFactory codecFactory() {
    return codecFactory;
  }
}

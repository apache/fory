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

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.GeneratedJsonCodecFactory;

/** Frozen GraalVM runtime reconstruction table for generated JSON companions. */
@Internal
public final class GeneratedJsonCodecFactories {
  private static Map<Class<?>, GeneratedJsonCodecFactory> factories = new IdentityHashMap<>();
  private static boolean frozen;

  private GeneratedJsonCodecFactories() {}

  /** Publishes one hosted-analysis factory. */
  public static synchronized void register(Class<?> type, GeneratedJsonCodecFactory factory) {
    if (frozen) {
      throw new ForyJsonException("Generated JSON codec factory table is already frozen");
    }
    GeneratedJsonCodecFactory previous = factories.get(type);
    if (previous == null) {
      factories.put(type, factory);
    } else if (previous.getClass() != factory.getClass()) {
      throw new ForyJsonException(
          "Conflicting generated JSON codec factories for "
              + type.getName()
              + ": "
              + previous.getClass().getName()
              + " and "
              + factory.getClass().getName());
    }
  }

  /** Freezes the hosted table into the native image heap. */
  public static synchronized void freeze() {
    if (!frozen) {
      factories = Collections.unmodifiableMap(new IdentityHashMap<>(factories));
      frozen = true;
    }
  }

  /** Returns the exact runtime factory, or {@code null} when no companion was generated. */
  public static synchronized GeneratedJsonCodecFactory get(Class<?> type) {
    return factories.get(type);
  }
}

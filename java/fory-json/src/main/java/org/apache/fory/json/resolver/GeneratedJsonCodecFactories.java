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
import java.util.HashMap;
import java.util.Map;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.GeneratedJsonCodecFactory;

/** Frozen GraalVM runtime reconstruction table for generated JSON companions. */
@Internal
public final class GeneratedJsonCodecFactories {
  private static Map<Key, GeneratedJsonCodecFactory> factories = new HashMap<>();
  private static boolean frozen;

  private GeneratedJsonCodecFactories() {}

  /** Publishes one hosted-analysis factory for an exact target and optional Mixin pair. */
  public static synchronized void register(
      Class<?> type, Class<?> mixinType, GeneratedJsonCodecFactory factory) {
    if (frozen) {
      throw new ForyJsonException("Generated JSON codec factory table is already frozen");
    }
    Key key = new Key(type, mixinType);
    GeneratedJsonCodecFactory previous = factories.get(key);
    if (previous == null) {
      factories.put(key, factory);
    } else if (previous.getClass() != factory.getClass()) {
      throw new ForyJsonException(
          "Conflicting generated JSON codec factories for "
              + type.getName()
              + (mixinType == null ? "" : " and " + mixinType.getName())
              + ": "
              + previous.getClass().getName()
              + " and "
              + factory.getClass().getName());
    }
  }

  /** Freezes the hosted table into the native image heap. */
  public static synchronized void freeze() {
    if (!frozen) {
      factories = Collections.unmodifiableMap(new HashMap<>(factories));
      frozen = true;
    }
  }

  /** Returns the exact pair factory, or {@code null} when no companion was generated. */
  public static synchronized GeneratedJsonCodecFactory get(Class<?> type, Class<?> mixinType) {
    return factories.get(new Key(type, mixinType));
  }

  private static final class Key {
    private final Class<?> type;
    private final Class<?> mixinType;

    private Key(Class<?> type, Class<?> mixinType) {
      this.type = type;
      this.mixinType = mixinType;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (other == null || getClass() != other.getClass()) {
        return false;
      }
      Key that = (Key) other;
      return type == that.type && mixinType == that.mixinType;
    }

    @Override
    public int hashCode() {
      return 31 * System.identityHashCode(type) + System.identityHashCode(mixinType);
    }
  }
}

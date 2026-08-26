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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.apache.fory.annotation.Internal;
import org.apache.fory.platform.GraalvmSupport;

/** Hosted-only owner that embeds complete inferred subtype schema tables in a native image. */
@Internal
public final class JsonNativeSubtypeRegistry {
  private static final Map<Key, Table> TABLES = new HashMap<>();
  private static boolean frozen;

  private JsonNativeSubtypeRegistry() {}

  static synchronized void publish(
      Class<?> baseType, Class<?> mixinType, Class<?>[] classes, String[] names) {
    if (!GraalvmSupport.isGraalBuildTime()) {
      return;
    }
    if (frozen) {
      throw new IllegalStateException("Native JSON subtype registry is frozen");
    }
    Key key = new Key(baseType, mixinType);
    Table candidate = new Table(classes, names);
    Table previous = TABLES.putIfAbsent(key, candidate);
    if (previous != null && !previous.matches(classes, names)) {
      throw new IllegalStateException("Conflicting inferred subtype tables for " + baseType);
    }
  }

  static synchronized Table table(Class<?> baseType, Class<?> mixinType) {
    return TABLES.get(new Key(baseType, mixinType));
  }

  /** Freezes hosted publication before the image heap is finalized. */
  @Internal
  public static synchronized void freeze() {
    frozen = true;
  }

  static final class Table {
    final Class<?>[] classes;
    final String[] names;

    private Table(Class<?>[] classes, String[] names) {
      this.classes = classes.clone();
      this.names = names.clone();
    }

    private boolean matches(Class<?>[] candidateClasses, String[] candidateNames) {
      return Arrays.equals(classes, candidateClasses) && Arrays.equals(names, candidateNames);
    }
  }

  private static final class Key {
    private final Class<?> baseType;
    private final Class<?> mixinType;

    private Key(Class<?> baseType, Class<?> mixinType) {
      this.baseType = baseType;
      this.mixinType = mixinType;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Key)) {
        return false;
      }
      Key key = (Key) other;
      return baseType == key.baseType && mixinType == key.mixinType;
    }

    @Override
    public int hashCode() {
      return 31 * System.identityHashCode(baseType) + System.identityHashCode(mixinType);
    }
  }
}

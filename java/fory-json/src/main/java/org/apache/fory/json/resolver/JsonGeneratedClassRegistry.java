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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.codegen.GeneratedCodecKey;

/** Frozen exact-key registry of generated JSON classes retained in a Native Image. */
@Internal
public final class JsonGeneratedClassRegistry {
  private static Map<GeneratedCodecKey, Class<?>> pendingClasses = new HashMap<>();
  private static Map<GeneratedCodecKey, Class<?>> generatedClasses = Collections.emptyMap();

  private JsonGeneratedClassRegistry() {}

  /** Publishes one hosted configuration's generated classes during Native Image analysis. */
  public static synchronized Set<Class<?>> register(JsonSharedRegistry hostedRegistry) {
    if (pendingClasses == null) {
      throw new IllegalStateException("Fory JSON generated class registry is frozen");
    }
    LinkedHashSet<Class<?>> added = new LinkedHashSet<>();
    for (Map.Entry<GeneratedCodecKey, Class<?>> entry :
        hostedRegistry.generatedClasses().entrySet()) {
      GeneratedCodecKey key = entry.getKey();
      Class<?> generatedClass = entry.getValue();
      Class<?> previous = pendingClasses.putIfAbsent(key, generatedClass);
      if (previous == null) {
        added.add(generatedClass);
      } else if (previous != generatedClass) {
        throw new IllegalStateException(
            "Conflicting generated Fory JSON classes for " + key.targetClass().getName());
      }
    }
    return added;
  }

  /** Finalizes Native runtime lookup and releases hosted mutable state. */
  public static synchronized void freeze() {
    if (pendingClasses == null) {
      return;
    }
    generatedClasses = pendingClasses;
    pendingClasses = null;
  }

  static Class<?> generatedClass(GeneratedCodecKey key) {
    Map<GeneratedCodecKey, Class<?>> pending = pendingClasses;
    if (pending != null) {
      return pending.get(key);
    }
    return generatedClasses.get(key);
  }
}

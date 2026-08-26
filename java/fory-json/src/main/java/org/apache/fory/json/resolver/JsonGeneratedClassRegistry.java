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
import org.apache.fory.json.codec.GeneratedJsonCodec;
import org.apache.fory.json.codegen.GeneratedCodecKey;
import org.apache.fory.json.resolver.JsonSharedRegistry.GeneratedClasses;
import org.apache.fory.reflect.TypeRef;

/** Frozen exact-key registry of generated JSON classes retained in a Native Image. */
@Internal
public final class JsonGeneratedClassRegistry {
  private static Map<GeneratedCodecKey, Class<?>> pendingClasses = new HashMap<>();
  private static Map<CompanionKey, GeneratedJsonCodec<?>> pendingCompanions = new HashMap<>();
  private static Map<GeneratedCodecKey, Class<?>> generatedClasses = Collections.emptyMap();
  private static CompanionEntry[] companionEntries = new CompanionEntry[0];

  private JsonGeneratedClassRegistry() {}

  /** Publishes one hosted configuration's generated classes during Native Image analysis. */
  public static synchronized Set<Class<?>> register(JsonSharedRegistry hostedRegistry) {
    if (pendingClasses == null) {
      throw new IllegalStateException("Fory JSON generated class registry is frozen");
    }
    GeneratedClasses generated = hostedRegistry.generatedClasses();
    LinkedHashSet<Class<?>> added = new LinkedHashSet<>();
    mergeClasses(generated.classes(), added);
    mergeSourceCodecs(generated.sourceCodecs(), pendingCompanions, added);
    return added;
  }

  /** Finalizes Native runtime lookup and releases hosted mutable state. */
  public static synchronized void freeze() {
    if (pendingClasses == null) {
      return;
    }
    generatedClasses = pendingClasses;
    snapshotCompanions();
    pendingClasses = null;
    pendingCompanions = null;
  }

  static Class<?> generatedClass(GeneratedCodecKey key) {
    Map<GeneratedCodecKey, Class<?>> pending = pendingClasses;
    if (pending != null) {
      return pending.get(key);
    }
    return generatedClasses.get(key);
  }

  static GeneratedJsonCodec<?> sourceCodec(CompanionKey key) {
    Map<CompanionKey, GeneratedJsonCodec<?>> pending = pendingCompanions;
    if (pending != null) {
      return pending.get(key);
    }
    for (CompanionEntry entry : companionEntries) {
      if (entry.key.equals(key)) {
        return entry.codec;
      }
    }
    return null;
  }

  private static void mergeClasses(Map<GeneratedCodecKey, Class<?>> source, Set<Class<?>> added) {
    for (Map.Entry<GeneratedCodecKey, Class<?>> entry : source.entrySet()) {
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
  }

  static void mergeSourceCodecs(
      Map<CompanionKey, ? extends GeneratedJsonCodec<?>> source,
      Map<CompanionKey, GeneratedJsonCodec<?>> target,
      Set<Class<?>> added) {
    for (Map.Entry<CompanionKey, ? extends GeneratedJsonCodec<?>> entry : source.entrySet()) {
      GeneratedJsonCodec<?> codec = entry.getValue();
      GeneratedJsonCodec<?> previous = target.putIfAbsent(entry.getKey(), codec);
      if (previous == null) {
        added.add(codec.getClass());
      } else if (previous.getClass() != codec.getClass()) {
        throw new IllegalStateException(
            "Conflicting source-generated Fory JSON companions for " + entry.getKey().type);
      }
    }
  }

  private static void snapshotCompanions() {
    // Companion keys retain TypeRef and Mixin identity hashes. Freeze them as entries so Native
    // runtime lookup uses equality instead of a hosted HashMap bucket computed before image start.
    companionEntries = new CompanionEntry[pendingCompanions.size()];
    int index = 0;
    for (Map.Entry<CompanionKey, GeneratedJsonCodec<?>> entry : pendingCompanions.entrySet()) {
      companionEntries[index++] = new CompanionEntry(entry.getKey(), entry.getValue());
    }
  }

  static final class CompanionKey {
    private final TypeRef<?> type;
    private final Class<?> mixinType;

    CompanionKey(TypeRef<?> type, Class<?> mixinType) {
      this.type = type;
      this.mixinType = mixinType;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof CompanionKey)) {
        return false;
      }
      CompanionKey that = (CompanionKey) other;
      return type.equals(that.type) && mixinType == that.mixinType;
    }

    @Override
    public int hashCode() {
      return type.hashCode() * 31 + System.identityHashCode(mixinType);
    }
  }

  private static final class CompanionEntry {
    private final CompanionKey key;
    private final GeneratedJsonCodec<?> codec;

    private CompanionEntry(CompanionKey key, GeneratedJsonCodec<?> codec) {
      this.key = key;
      this.codec = codec;
    }
  }
}

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
import org.apache.fory.json.codegen.JsonCodegenKey;
import org.apache.fory.json.resolver.JsonSharedRegistry.GeneratedClasses;
import org.apache.fory.reflect.TypeRef;

/** Frozen Native Image mapping from JSON configuration semantics to generated classes. */
@Internal
public final class JsonGeneratedClassRegistry {
  private static Map<JsonCodegenKey, MutableConfiguration> pending = new HashMap<>();
  private static Map<JsonCodegenKey, Configuration> configurations = Collections.emptyMap();
  private static boolean frozen;

  private JsonGeneratedClassRegistry() {}

  /** Publishes one hosted configuration's generated classes during Native Image analysis. */
  public static synchronized Set<Class<?>> register(
      JsonCodegenKey key, JsonSharedRegistry hostedRegistry) {
    if (frozen) {
      throw new IllegalStateException("Fory JSON generated class registry is frozen");
    }
    GeneratedClasses generatedClasses = hostedRegistry.generatedClasses();
    MutableConfiguration configuration = pending.get(key);
    if (configuration == null) {
      configuration = new MutableConfiguration();
      pending.put(key, configuration);
    }
    LinkedHashSet<Class<?>> added = new LinkedHashSet<>();
    configuration.merge(generatedClasses, added);
    configurations = snapshot();
    return added;
  }

  /** Finalizes generated class lookup after Native Image analysis. */
  public static synchronized void freeze() {
    if (frozen) {
      return;
    }
    pending = null;
    frozen = true;
  }

  private static Map<JsonCodegenKey, Configuration> snapshot() {
    Map<JsonCodegenKey, Configuration> snapshot = new HashMap<>(pending.size());
    for (Map.Entry<JsonCodegenKey, MutableConfiguration> entry : pending.entrySet()) {
      snapshot.put(entry.getKey(), entry.getValue().freeze());
    }
    return Collections.unmodifiableMap(snapshot);
  }

  static Configuration configuration(JsonCodegenKey key) {
    return configurations.get(key);
  }

  static void mergeSourceCodecs(
      Map<TypeRef<?>, GeneratedJsonCodec<?>> source,
      Map<TypeRef<?>, GeneratedJsonCodec<?>> target,
      Set<Class<?>> added) {
    for (Map.Entry<TypeRef<?>, GeneratedJsonCodec<?>> entry : source.entrySet()) {
      TypeRef<?> type = entry.getKey();
      GeneratedJsonCodec<?> codec = entry.getValue();
      GeneratedJsonCodec<?> previous = target.putIfAbsent(type, codec);
      if (previous == null) {
        added.add(codec.getClass());
      } else if (previous.getClass() != codec.getClass()) {
        throw new IllegalStateException(
            "Conflicting source-generated Fory JSON companions for " + type);
      }
    }
  }

  static final class Configuration {
    private final Map<TypeRef<?>, Class<?>> stringWriters;
    private final Map<TypeRef<?>, Class<?>> utf8Writers;
    private final Map<TypeRef<?>, Class<?>> latin1Readers;
    private final Map<TypeRef<?>, Class<?>> utf16Readers;
    private final Map<TypeRef<?>, Class<?>> utf8Readers;
    private final Map<TypeRef<?>, Class<?>> utf8CollectionWriters;
    private final Map<TypeRef<?>, Class<?>> utf8CollectionReaders;
    private final Map<TypeRef<?>, GeneratedJsonCodec<?>> sourceCodecs;

    private Configuration(MutableConfiguration source) {
      stringWriters = immutableValues(source.stringWriters);
      utf8Writers = immutableValues(source.utf8Writers);
      latin1Readers = immutableValues(source.latin1Readers);
      utf16Readers = immutableValues(source.utf16Readers);
      utf8Readers = immutableValues(source.utf8Readers);
      utf8CollectionWriters = immutableValues(source.utf8CollectionWriters);
      utf8CollectionReaders = immutableValues(source.utf8CollectionReaders);
      sourceCodecs = immutableValues(source.sourceCodecs);
    }

    Class<?> stringWriter(TypeRef<?> type) {
      return stringWriters.get(type);
    }

    Class<?> utf8Writer(TypeRef<?> type) {
      return utf8Writers.get(type);
    }

    Class<?> latin1Reader(TypeRef<?> type) {
      return latin1Readers.get(type);
    }

    Class<?> utf16Reader(TypeRef<?> type) {
      return utf16Readers.get(type);
    }

    Class<?> utf8Reader(TypeRef<?> type) {
      return utf8Readers.get(type);
    }

    Class<?> utf8CollectionWriter(TypeRef<?> type) {
      return utf8CollectionWriters.get(type);
    }

    Class<?> utf8CollectionReader(TypeRef<?> type) {
      return utf8CollectionReaders.get(type);
    }

    GeneratedJsonCodec<?> sourceCodec(TypeRef<?> type) {
      return sourceCodecs.get(type);
    }

    private static <K, V> Map<K, V> immutableValues(Map<K, V> values) {
      return values.isEmpty()
          ? Collections.emptyMap()
          : Collections.unmodifiableMap(new HashMap<>(values));
    }
  }

  private static final class MutableConfiguration {
    private final Map<TypeRef<?>, Class<?>> stringWriters = new HashMap<>();
    private final Map<TypeRef<?>, Class<?>> utf8Writers = new HashMap<>();
    private final Map<TypeRef<?>, Class<?>> latin1Readers = new HashMap<>();
    private final Map<TypeRef<?>, Class<?>> utf16Readers = new HashMap<>();
    private final Map<TypeRef<?>, Class<?>> utf8Readers = new HashMap<>();
    private final Map<TypeRef<?>, Class<?>> utf8CollectionWriters = new HashMap<>();
    private final Map<TypeRef<?>, Class<?>> utf8CollectionReaders = new HashMap<>();
    private final Map<TypeRef<?>, GeneratedJsonCodec<?>> sourceCodecs = new HashMap<>();

    private void merge(GeneratedClasses source, Set<Class<?>> added) {
      merge(source.stringWriters(), stringWriters, added);
      merge(source.utf8Writers(), utf8Writers, added);
      merge(source.latin1Readers(), latin1Readers, added);
      merge(source.utf16Readers(), utf16Readers, added);
      merge(source.utf8Readers(), utf8Readers, added);
      merge(source.utf8CollectionWriters(), utf8CollectionWriters, added);
      merge(source.utf8CollectionReaders(), utf8CollectionReaders, added);
      JsonGeneratedClassRegistry.mergeSourceCodecs(source.sourceCodecs(), sourceCodecs, added);
    }

    private static <K> void merge(
        Map<K, Class<?>> source, Map<K, Class<?>> target, Set<Class<?>> added) {
      for (Map.Entry<K, Class<?>> entry : source.entrySet()) {
        merge(entry.getKey(), entry.getValue(), target, added);
      }
    }

    private static <K> void merge(
        K key, Class<?> generatedClass, Map<K, Class<?>> target, Set<Class<?>> added) {
      Class<?> previous = target.putIfAbsent(key, generatedClass);
      if (previous == null) {
        added.add(generatedClass);
      } else if (previous != generatedClass) {
        throw new IllegalStateException("Conflicting generated Fory JSON classes for " + key);
      }
    }

    private Configuration freeze() {
      return new Configuration(this);
    }
  }
}

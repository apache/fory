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

package org.apache.fory.serializer;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.fory.collection.ClassValueCache;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.reflect.FieldAccessor;

/**
 * Opt-in sink for compatible-mode extra fields. A class participates by declaring a field of this
 * type; the serialization framework detects it, excludes it from the normal field set, and routes
 * unmatched remote fields into it instead of discarding them.
 *
 * <p>The remote {@link TypeDef} is stored transiently so that on re-serialization the framework can
 * emit the remote schema header and replay all fields in their original order, allowing a
 * downstream peer with the full schema to recover them.
 */
public final class ForyExtraFields {

  private final Map<String, Object> fields = new HashMap<>();

  public Object get(String name) {
    return fields.get(name);
  }

  public Object getOrDefault(String name, Object defaultValue) {
    return fields.getOrDefault(name, defaultValue);
  }

  public boolean isEmpty() {
    return fields.isEmpty();
  }

  public boolean containsKey(String name) {
    return fields.containsKey(name);
  }

  Object put(String name, Object value) {
    return fields.put(name, value);
  }

  /**
   * GC-transparent class-keyed cache: on JVM this is backed by ClassValue so entries do not prevent
   * the associated Class (or its ClassLoader) from being collected. On Android/GraalVM it falls
   * back to a ConcurrentHashMap.
   */
  private static final ClassValueCache<Optional<FieldAccessor>> SINK_CACHE =
      ClassValueCache.newClassKeyCache(16);

  public static Field findSinkField(Class<?> cls) {
    return SINK_CACHE
        .get(cls, () -> scanForExtraField(cls))
        .map(FieldAccessor::getField)
        .orElse(null);
  }

  /** Returns a {@link FieldAccessor} for the {@link ForyExtraFields} sink field on {@code cls}. */
  public static FieldAccessor findSinkAccessor(Class<?> cls) {
    return SINK_CACHE.get(cls, () -> scanForExtraField(cls)).orElse(null);
  }

  private static Optional<FieldAccessor> scanForExtraField(Class<?> cls) {
    for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
      for (Field f : c.getDeclaredFields()) {
        if (ForyExtraFields.class == f.getType()) {
          return Optional.of(FieldAccessor.createAccessor(f));
        }
      }
    }
    return Optional.empty();
  }

  // Excluded from Java serialization because TypeDef is runtime metadata used
  // only for Fory serialization. If a ForyExtraFields instance is serialized
  // with ObjectOutputStream, this field will be null after deserialization, so
  // the captured extra fields cannot later be replayed by Fory.
  private transient TypeDef typeDef;

  public TypeDef getTypeDef() {
    return typeDef;
  }

  public static void capture(
      Object target, FieldAccessor sinkAccessor, TypeDef typeDef, String name, Object value) {
    ForyExtraFields extraField = (ForyExtraFields) sinkAccessor.getObject(target);
    if (extraField == null) {
      extraField = new ForyExtraFields();
      extraField.typeDef = typeDef;
      sinkAccessor.putObject(target, extraField);
    }
    extraField.put(name, value);
  }
}

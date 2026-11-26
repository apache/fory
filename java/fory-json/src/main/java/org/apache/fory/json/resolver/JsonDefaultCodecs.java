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

import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonDefaultCodec;
import org.apache.fory.json.codec.CodecUtils;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.type.Types;

/**
 * Effective {@link JsonDefaultCodec} declarations of one object type and its superclasses.
 *
 * <p>Selection turns a matching default into the {@link JsonCodec} configuration a property would
 * declare itself, so codec resolution, validation, and generated code follow the occurrence path.
 */
@Internal
public final class JsonDefaultCodecs {
  private static final JsonDefaultCodecs NONE = new JsonDefaultCodecs(new Entry[0]);
  // The Kotlin module carries these as tagged JVM primitives, so no occurrence could match.
  private static final List<String> KOTLIN_UNSIGNED =
      Arrays.asList(
          "kotlin.UByte",
          "kotlin.UShort",
          "kotlin.UInt",
          "kotlin.ULong",
          "kotlin.UByteArray",
          "kotlin.UShortArray",
          "kotlin.UIntArray",
          "kotlin.ULongArray");

  private final Entry[] entries;

  private JsonDefaultCodecs(Entry[] entries) {
    this.entries = entries;
  }

  /** Collects the defaults of {@code type}; a subclass entry replaces a superclass one. */
  public static JsonDefaultCodecs of(Class<?> type, JsonSharedRegistry registry) {
    // Like a type-level JsonCodec, a Mixin removal on the target also drops inherited defaults.
    boolean inherit =
        !registry.mixinRemoves(type, type, JsonDefaultCodec.class)
            && !registry.mixinRemoves(type, type, JsonDefaultCodec.List.class);
    List<Entry> entries = null;
    for (Class<?> declaring = type;
        declaring != null && declaring != Object.class && (inherit || declaring == type);
        declaring = declaring.getSuperclass()) {
      rejectInterfaceDeclarations(type, declaring.getInterfaces(), registry);
      JsonDefaultCodec single = registry.annotation(type, declaring, JsonDefaultCodec.class);
      JsonDefaultCodec.List repeated =
          registry.annotation(type, declaring, JsonDefaultCodec.List.class);
      // Java stores repeated declarations in the container, so a Mixin that supplies or removes
      // either form replaces both forms declared on the target.
      boolean singleReplaced = registry.mixinOverrides(type, declaring, JsonDefaultCodec.class);
      boolean repeatedReplaced =
          registry.mixinOverrides(type, declaring, JsonDefaultCodec.List.class);
      if (singleReplaced && !repeatedReplaced) {
        repeated = null;
      } else if (repeatedReplaced && !singleReplaced) {
        single = null;
      }
      List<JsonDefaultCodec> annotations = new ArrayList<>();
      if (single != null) {
        annotations.add(single);
      }
      if (repeated != null) {
        annotations.addAll(Arrays.asList(repeated.value()));
      }
      if (annotations.isEmpty()) {
        continue;
      }
      List<Entry> declared = new ArrayList<>();
      for (JsonDefaultCodec annotation : annotations) {
        Entry entry = entry(declaring, annotation);
        if (find(declared, entry.target) != null) {
          throw new ForyJsonException(
              "Duplicate @JsonDefaultCodec type "
                  + entry.target.getName()
                  + " on "
                  + declaring.getName());
        }
        declared.add(entry);
      }
      if (entries == null) {
        entries = declared;
      } else {
        for (Entry entry : declared) {
          if (find(entries, entry.target) == null) {
            entries.add(entry);
          }
        }
      }
    }
    return entries == null ? NONE : new JsonDefaultCodecs(entries.toArray(new Entry[0]));
  }

  /** Interface defaults would need a merge order across unrelated interfaces, so they fail. */
  private static void rejectInterfaceDeclarations(
      Class<?> type, Class<?>[] interfaces, JsonSharedRegistry registry) {
    for (Class<?> interfaceType : interfaces) {
      if (registry.annotation(type, interfaceType, JsonDefaultCodec.class) != null
          || registry.annotation(type, interfaceType, JsonDefaultCodec.List.class) != null) {
        throw new ForyJsonException(
            "@JsonDefaultCodec is read from classes only, not from interface "
                + interfaceType.getName()
                + " of "
                + type.getName());
      }
      rejectInterfaceDeclarations(type, interfaceType.getInterfaces(), registry);
    }
  }

  private static Entry entry(Class<?> declaring, JsonDefaultCodec annotation) {
    Class<?> target = annotation.type();
    if (target == void.class
        || KOTLIN_UNSIGNED.contains(target.getName())
        || annotation.codec() == JsonCodec.NoJsonValueCodec.class) {
      throw new ForyJsonException(
          "Invalid @JsonDefaultCodec on "
              + declaring.getName()
              + ": type "
              + target.getName()
              + " with codec "
              + annotation.codec().getName());
    }
    // A primitive and its wrapper are one target.
    return new Entry(TypeUtils.wrap(target), annotation.codec());
  }

  private static Entry find(List<Entry> entries, Class<?> target) {
    for (Entry entry : entries) {
      if (entry.target == target) {
        return entry;
      }
    }
    return null;
  }

  public boolean isEmpty() {
    return entries.length == 0;
  }

  /** Returns the codecs a property of {@code type} defaults to, or null without a match. */
  public Selection select(TypeRef<?> type) {
    if (entries.length == 0 || semantic(type)) {
      return null;
    }
    Class<? extends JsonValueCodec<?>> codec = codec(type);
    if (codec != null) {
      return new Selection(codec, null, null, null);
    }
    Class<?> rawType = type.getRawType();
    if (rawType.isArray()) {
      // A primitive array keeps its own mapping, such as byteArrayFormat for byte[].
      codec = rawType.getComponentType().isPrimitive() ? null : childCodec(type.getComponentType());
      return codec == null ? null : new Selection(null, codec, null, null);
    }
    if (rawType.getTypeParameters().length != 0 && !type.hasExplicitTypeArguments()) {
      return null;
    }
    if (Collection.class.isAssignableFrom(rawType) || rawType == AtomicReferenceArray.class) {
      codec = childCodec(CodecUtils.elementTypeRef(type));
      return codec == null ? null : new Selection(null, codec, null, null);
    }
    if (rawType == Optional.class || rawType == AtomicReference.class) {
      codec = childCodec(CodecUtils.elementTypeRef(type));
      return codec == null ? null : new Selection(null, null, codec, null);
    }
    if (Map.class.isAssignableFrom(rawType)) {
      codec = childCodec(CodecUtils.mapKeyValueTypeRefs(type).f1);
      return codec == null ? null : new Selection(null, null, null, codec);
    }
    return null;
  }

  /** Returns the default whose type is the raw type of this occurrence, or null. */
  private Class<? extends JsonValueCodec<?>> codec(TypeRef<?> type) {
    Class<?> rawType = TypeUtils.wrap(type.getRawType());
    for (Entry entry : entries) {
      if (entry.target == rawType) {
        return entry.codecClass;
      }
    }
    return null;
  }

  /**
   * A wildcard or unresolved type-variable child is not concrete, which an explicit child member
   * rejects as well, and a child a language module tags with its own semantic type keeps that
   * module's mapping.
   */
  private Class<? extends JsonValueCodec<?>> childCodec(TypeRef<?> child) {
    Type type = child.getType();
    return type instanceof WildcardType || type instanceof TypeVariable || semantic(child)
        ? null
        : codec(child);
  }

  /** A type such as a Kotlin {@code UInt} carried as {@code int} keeps its module's mapping. */
  private static boolean semantic(TypeRef<?> type) {
    return type.getTypeExtMeta() != null && type.getTypeExtMeta().typeId() != Types.UNKNOWN;
  }

  private static final class Entry {
    private final Class<?> target;
    private final Class<? extends JsonValueCodec<?>> codecClass;

    private Entry(Class<?> target, Class<? extends JsonValueCodec<?>> codecClass) {
      this.target = target;
      this.codecClass = codecClass;
    }
  }

  /**
   * Codecs a class default selects for one occurrence. It deliberately does not implement {@link
   * JsonCodec}: a program class implementing an annotation interface lets a shrinker such as R8
   * devirtualize every annotation member call to it, breaking real annotation instances.
   */
  @Internal
  public static final class Selection {
    private final Class<? extends JsonValueCodec<?>> value;
    private final Class<? extends JsonValueCodec<?>> elementCodec;
    private final Class<? extends JsonValueCodec<?>> contentCodec;
    private final Class<? extends JsonValueCodec<?>> valueCodec;

    private Selection(
        Class<? extends JsonValueCodec<?>> value,
        Class<? extends JsonValueCodec<?>> elementCodec,
        Class<? extends JsonValueCodec<?>> contentCodec,
        Class<? extends JsonValueCodec<?>> valueCodec) {
      this.value = orNone(value);
      this.elementCodec = orNone(elementCodec);
      this.contentCodec = orNone(contentCodec);
      this.valueCodec = orNone(valueCodec);
    }

    private static Class<? extends JsonValueCodec<?>> orNone(
        Class<? extends JsonValueCodec<?>> codec) {
      return codec == null ? JsonCodec.NoJsonValueCodec.class : codec;
    }

    /** Returns the complete-value codec, or {@code NoJsonValueCodec} for a child selection. */
    public Class<? extends JsonValueCodec<?>> value() {
      return value;
    }

    public Class<? extends JsonValueCodec<?>> elementCodec() {
      return elementCodec;
    }

    public Class<? extends JsonValueCodec<?>> contentCodec() {
      return contentCodec;
    }

    public Class<? extends JsonValueCodec<?>> valueCodec() {
      return valueCodec;
    }

    /** Returns whether this selection fills a child member rather than the complete value. */
    public boolean child() {
      return value == JsonCodec.NoJsonValueCodec.class;
    }

    @Override
    public String toString() {
      return "@JsonDefaultCodec selection "
          + Arrays.asList(value, elementCodec, contentCodec, valueCodec);
    }
  }
}

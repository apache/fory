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

import java.lang.annotation.Annotation;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonDefaultCodec;
import org.apache.fory.json.codec.CodecUtils;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.codec.MapKeyCodec;
import org.apache.fory.reflect.TypeRef;
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
        for (Class<? extends JsonValueCodec<?>> codecClass : annotation.value()) {
          Entry entry = new Entry(targetType(declaring, codecClass), codecClass);
          if (find(declared, entry.target) != null) {
            throw new ForyJsonException(
                "Duplicate @JsonDefaultCodec target "
                    + entry.target.getTypeName()
                    + " on "
                    + declaring.getName());
          }
          declared.add(entry);
        }
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

  public boolean isEmpty() {
    return entries.length == 0;
  }

  /** Returns the codec configuration for a property of {@code type}, or null without a match. */
  public JsonCodec select(TypeRef<?> type) {
    if (entries.length == 0) {
      return null;
    }
    Class<? extends JsonValueCodec<?>> codec = codec(type);
    if (codec != null) {
      return new SelectedCodec(codec, null, null, null);
    }
    if (semantic(type)) {
      return null;
    }
    Class<?> rawType = type.getRawType();
    if (rawType.isArray()) {
      codec = codec(type.getComponentType());
      return codec == null ? null : new SelectedCodec(null, codec, null, null);
    }
    if (rawType.getTypeParameters().length != 0 && !type.hasExplicitTypeArguments()) {
      return null;
    }
    if (Collection.class.isAssignableFrom(rawType) || rawType == AtomicReferenceArray.class) {
      codec = codec(CodecUtils.elementTypeRef(type));
      return codec == null ? null : new SelectedCodec(null, codec, null, null);
    }
    if (rawType == Optional.class || rawType == AtomicReference.class) {
      codec = codec(CodecUtils.elementTypeRef(type));
      return codec == null ? null : new SelectedCodec(null, null, codec, null);
    }
    if (Map.class.isAssignableFrom(rawType)) {
      codec = codec(CodecUtils.mapKeyValueTypeRefs(type).f1);
      return codec == null ? null : new SelectedCodec(null, null, null, codec);
    }
    return null;
  }

  /** Names a class default as the source of {@code annotation} in an error, or returns "". */
  public static String origin(JsonCodec annotation) {
    return annotation instanceof SelectedCodec ? " selected by the class @JsonDefaultCodec" : "";
  }

  /**
   * Returns whether {@code annotation} is a default that fills a child member. The resolver keeps
   * the container's own mapping when it is not the standard container representation.
   */
  static boolean selectsChild(JsonCodec annotation) {
    return annotation instanceof SelectedCodec
        && annotation.value() == JsonCodec.NoJsonValueCodec.class;
  }

  /**
   * Returns the default for one occurrence. A primitive matches its wrapper, but a type a language
   * module tags with its own semantic type, such as a Kotlin {@code UInt} carried as {@code int},
   * keeps that module's mapping.
   */
  private Class<? extends JsonValueCodec<?>> codec(TypeRef<?> type) {
    if (semantic(type)) {
      return null;
    }
    return codec((type.isPrimitive() ? type.wrap() : type).getType());
  }

  private Class<? extends JsonValueCodec<?>> codec(Type type) {
    for (Entry entry : entries) {
      if (sameType(entry.target, type)) {
        return entry.codecClass;
      }
    }
    return null;
  }

  private static boolean semantic(TypeRef<?> type) {
    return type.getTypeExtMeta() != null && type.getTypeExtMeta().typeId() != Types.UNKNOWN;
  }

  private static Entry find(List<Entry> entries, Type target) {
    for (Entry entry : entries) {
      if (sameType(entry.target, target)) {
        return entry;
      }
    }
    return null;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Type targetType(
      Class<?> declaring, Class<? extends JsonValueCodec<?>> codecClass) {
    if (codecClass == JsonCodec.NoJsonValueCodec.class) {
      throw invalid(declaring, codecClass, "is not a codec");
    }
    List<TypeRef<?>> arguments =
        ((TypeRef) TypeRef.of(codecClass)).getSupertype(JsonValueCodec.class).getTypeArguments();
    Type target = arguments.size() == 1 ? arguments.get(0).getType() : null;
    if (target == null || !concrete(target)) {
      throw invalid(declaring, codecClass, "does not declare a concrete value type");
    }
    return target;
  }

  private static boolean concrete(Type type) {
    if (type instanceof Class) {
      return true;
    }
    if (type instanceof ParameterizedType) {
      ParameterizedType parameterized = (ParameterizedType) type;
      if (!(parameterized.getRawType() instanceof Class)) {
        return false;
      }
      for (Type argument : parameterized.getActualTypeArguments()) {
        if (!concrete(argument)) {
          return false;
        }
      }
      return parameterized.getOwnerType() == null || concrete(parameterized.getOwnerType());
    }
    if (type instanceof GenericArrayType) {
      return concrete(((GenericArrayType) type).getGenericComponentType());
    }
    return false;
  }

  /** Compares reflected types structurally, independent of their implementation classes. */
  private static boolean sameType(Type a, Type b) {
    if (a instanceof Class || b instanceof Class) {
      return a.equals(b);
    }
    if (a instanceof ParameterizedType && b instanceof ParameterizedType) {
      ParameterizedType left = (ParameterizedType) a;
      ParameterizedType right = (ParameterizedType) b;
      Type[] leftArguments = left.getActualTypeArguments();
      Type[] rightArguments = right.getActualTypeArguments();
      if (!left.getRawType().equals(right.getRawType())
          || leftArguments.length != rightArguments.length) {
        return false;
      }
      for (int i = 0; i < leftArguments.length; i++) {
        if (!sameType(leftArguments[i], rightArguments[i])) {
          return false;
        }
      }
      Type leftOwner = left.getOwnerType();
      Type rightOwner = right.getOwnerType();
      return leftOwner == null || rightOwner == null
          ? Objects.equals(leftOwner, rightOwner)
          : sameType(leftOwner, rightOwner);
    }
    if (a instanceof GenericArrayType && b instanceof GenericArrayType) {
      return sameType(
          ((GenericArrayType) a).getGenericComponentType(),
          ((GenericArrayType) b).getGenericComponentType());
    }
    return a.equals(b);
  }

  private static ForyJsonException invalid(Class<?> declaring, Class<?> codecClass, String reason) {
    return new ForyJsonException(
        "Invalid @JsonDefaultCodec on "
            + declaring.getName()
            + ": "
            + codecClass.getName()
            + " "
            + reason);
  }

  private static final class Entry {
    private final Type target;
    private final Class<? extends JsonValueCodec<?>> codecClass;

    private Entry(Type target, Class<? extends JsonValueCodec<?>> codecClass) {
      this.target = target;
      this.codecClass = codecClass;
    }
  }

  /** {@link JsonCodec} configuration selected by a default rather than declared on a property. */
  private static final class SelectedCodec implements JsonCodec {
    private final Class<? extends JsonValueCodec<?>> value;
    private final Class<? extends JsonValueCodec<?>> elementCodec;
    private final Class<? extends JsonValueCodec<?>> contentCodec;
    private final Class<? extends JsonValueCodec<?>> valueCodec;

    private SelectedCodec(
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

    @Override
    public Class<? extends JsonValueCodec<?>> value() {
      return value;
    }

    @Override
    public Class<? extends JsonValueCodec<?>> elementCodec() {
      return elementCodec;
    }

    @Override
    public Class<? extends JsonValueCodec<?>> contentCodec() {
      return contentCodec;
    }

    @Override
    public Class<? extends MapKeyCodec> keyCodec() {
      return JsonCodec.NoMapKeyCodec.class;
    }

    @Override
    public Class<? extends JsonValueCodec<?>> valueCodec() {
      return valueCodec;
    }

    @Override
    public Class<? extends Annotation> annotationType() {
      return JsonCodec.class;
    }

    // Annotation contract: equal to any JsonCodec with the same member values.
    @Override
    public boolean equals(Object other) {
      if (!(other instanceof JsonCodec)) {
        return false;
      }
      JsonCodec that = (JsonCodec) other;
      return value == that.value()
          && elementCodec == that.elementCodec()
          && contentCodec == that.contentCodec()
          && that.keyCodec() == JsonCodec.NoMapKeyCodec.class
          && valueCodec == that.valueCodec();
    }

    @Override
    public int hashCode() {
      return member("value", value)
          + member("elementCodec", elementCodec)
          + member("contentCodec", contentCodec)
          + member("keyCodec", JsonCodec.NoMapKeyCodec.class)
          + member("valueCodec", valueCodec);
    }

    private static int member(String name, Class<?> value) {
      return (127 * name.hashCode()) ^ value.hashCode();
    }

    @Override
    public String toString() {
      return "@JsonDefaultCodec selection "
          + Arrays.asList(value, elementCodec, contentCodec, valueCodec);
    }
  }
}

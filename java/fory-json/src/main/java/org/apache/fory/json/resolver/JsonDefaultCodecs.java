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
import java.lang.reflect.WildcardType;
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

  /** Returns the codecs a property of {@code type} defaults to, or null without a match. */
  public Selection select(TypeRef<?> type) {
    if (entries.length == 0) {
      return null;
    }
    Class<? extends JsonValueCodec<?>> codec = codec(type);
    if (codec != null) {
      return new Selection(codec, null, null, null);
    }
    if (semantic(type)) {
      return null;
    }
    Class<?> rawType = type.getRawType();
    if (rawType.isArray()) {
      codec = childCodec(type.getComponentType());
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

  /**
   * Returns the default for one occurrence. A primitive matches its wrapper, but a type a language
   * module tags with its own semantic type, such as a Kotlin {@code UInt} carried as {@code int},
   * keeps that module's mapping.
   */
  private Class<? extends JsonValueCodec<?>> codec(TypeRef<?> type) {
    if (semantic(type)) {
      return null;
    }
    TypeRef<?> occurrence = type.isPrimitive() ? type.wrap() : type;
    for (Entry entry : entries) {
      if (matches(entry.target, occurrence)) {
        return entry.codecClass;
      }
    }
    return null;
  }

  /**
   * Matches a target against an occurrence through the occurrence's own tokens. A language module
   * may describe a generic array, at any depth, as a raw array class with a parameterized component
   * token, so arrays are compared by component and parameterized types argument by argument.
   */
  private static boolean matches(Type target, TypeRef<?> occurrence) {
    Type type = occurrence.getType();
    if (type instanceof WildcardType) {
      return sameType(target, type);
    }
    if (occurrence.getRawType().isArray()) {
      // Compare by component for raw and generic array targets alike: an occurrence token may
      // carry a parameterized component that its raw array class does not show.
      if (target instanceof GenericArrayType) {
        return matches(
            ((GenericArrayType) target).getGenericComponentType(), occurrence.getComponentType());
      }
      if (target instanceof Class && ((Class<?>) target).isArray()) {
        return matches(((Class<?>) target).getComponentType(), occurrence.getComponentType());
      }
    }
    if (target instanceof ParameterizedType && type instanceof ParameterizedType) {
      ParameterizedType parameterized = (ParameterizedType) target;
      ParameterizedType declared = (ParameterizedType) type;
      Type[] arguments = parameterized.getActualTypeArguments();
      Type[] declaredArguments = declared.getActualTypeArguments();
      if (!parameterized.getRawType().equals(declared.getRawType())
          || arguments.length != declaredArguments.length) {
        return false;
      }
      List<TypeRef<?>> tokens = occurrence.getTypeArguments();
      for (int i = 0; i < arguments.length; i++) {
        if (!matches(arguments[i], argument(declaredArguments[i], tokens, i, arguments.length))) {
          return false;
        }
      }
      Type owner = parameterized.getOwnerType();
      return owner == null || sameType(owner, declared.getOwnerType());
    }
    return sameType(target, type);
  }

  /**
   * Returns the occurrence token of one declared type argument. {@code TypeRef} may normalize a
   * container subclass's arguments to its element type, so a token is used only when its reflected
   * type is the declared argument itself; it then adds what reflection lacks, such as a Kotlin
   * array's parameterized component token.
   */
  private static TypeRef<?> argument(Type declared, List<TypeRef<?>> tokens, int index, int count) {
    if (tokens.size() == count) {
      TypeRef<?> token = tokens.get(index);
      if (sameType(declared, token.getType())) {
        return token;
      }
    }
    return TypeRef.of(declared);
  }

  /** A wildcard child is not concrete, which an explicit child member rejects as well. */
  private Class<? extends JsonValueCodec<?>> childCodec(TypeRef<?> child) {
    return child.getType() instanceof WildcardType ? null : codec(child);
  }

  /**
   * Reads each covariant {@code ? extends T} in a Kotlin codec's declared type as {@code T}. Kotlin
   * writes declaration-site variance into generic signatures, so a Kotlin codec of {@code
   * List<Long>} declares {@code List<? extends Long>}; a Java codec's wildcards stay wildcards.
   */
  private static Type kotlinInvariant(Type type) {
    if (type instanceof WildcardType) {
      WildcardType wildcard = (WildcardType) type;
      // Kotlin writes Any as ? extends Object, which reflection cannot tell from a star
      // projection; a star-projected target names no type, so Any is the useful reading.
      if (wildcard.getLowerBounds().length == 0 && wildcard.getUpperBounds().length == 1) {
        return kotlinInvariant(wildcard.getUpperBounds()[0]);
      }
      return type;
    }
    if (type instanceof ParameterizedType) {
      ParameterizedType parameterized = (ParameterizedType) type;
      Type[] arguments = parameterized.getActualTypeArguments().clone();
      for (int i = 0; i < arguments.length; i++) {
        arguments[i] = kotlinInvariant(arguments[i]);
      }
      Type owner = parameterized.getOwnerType();
      return new InvariantParameterizedType(
          parameterized.getRawType(), arguments, owner == null ? null : kotlinInvariant(owner));
    }
    if (type instanceof GenericArrayType) {
      Type component = kotlinInvariant(((GenericArrayType) type).getGenericComponentType());
      return component instanceof Class
          ? java.lang.reflect.Array.newInstance((Class<?>) component, 0).getClass()
          : new InvariantArrayType(component);
    }
    return type;
  }

  /**
   * Returns whether any class on the codec's superclass chain is Kotlin. The target is resolved
   * through that whole chain, so a Kotlin base class can contribute the variance wildcards.
   */
  private static boolean kotlinCodec(Class<?> codecClass) {
    for (Class<?> type = codecClass; type != null; type = type.getSuperclass()) {
      for (Annotation annotation : type.getDeclaredAnnotations()) {
        if (annotation.annotationType().getName().equals("kotlin.Metadata")) {
          return true;
        }
      }
    }
    return false;
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
    if (target != null && kotlinCodec(codecClass)) {
      target = kotlinInvariant(target);
    }
    if (target == null || !concrete(target)) {
      throw invalid(declaring, codecClass, "does not declare a concrete value type");
    }
    if (target instanceof Class && KOTLIN_UNSIGNED.contains(((Class<?>) target).getName())) {
      // The Kotlin module carries these as tagged JVM primitives, so no occurrence could match.
      throw invalid(declaring, codecClass, "cannot target the Kotlin unsigned type " + target);
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

  /** A Kotlin codec target with its covariant wildcards read as their bounds. */
  private static final class InvariantParameterizedType implements ParameterizedType {
    private final Type rawType;
    private final Type[] arguments;
    private final Type ownerType;

    private InvariantParameterizedType(Type rawType, Type[] arguments, Type ownerType) {
      this.rawType = rawType;
      this.arguments = arguments;
      this.ownerType = ownerType;
    }

    @Override
    public Type[] getActualTypeArguments() {
      return arguments.clone();
    }

    @Override
    public Type getRawType() {
      return rawType;
    }

    @Override
    public Type getOwnerType() {
      return ownerType;
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder(rawType.getTypeName()).append('<');
      for (int i = 0; i < arguments.length; i++) {
        builder.append(i == 0 ? "" : ", ").append(arguments[i].getTypeName());
      }
      return builder.append('>').toString();
    }
  }

  private static final class InvariantArrayType implements GenericArrayType {
    private final Type component;

    private InvariantArrayType(Type component) {
      this.component = component;
    }

    @Override
    public Type getGenericComponentType() {
      return component;
    }

    @Override
    public String toString() {
      return component.getTypeName() + "[]";
    }
  }

  private static final class Entry {
    private final Type target;
    private final Class<? extends JsonValueCodec<?>> codecClass;

    private Entry(Type target, Class<? extends JsonValueCodec<?>> codecClass) {
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

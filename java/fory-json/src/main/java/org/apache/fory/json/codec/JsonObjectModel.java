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

package org.apache.fory.json.codec;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.apache.fory.annotation.Internal;
import org.apache.fory.reflect.TypeRef;

/** Immutable construction and accessor metadata supplied by a language JSON module. */
@Internal
public final class JsonObjectModel {
  private final Executable creator;
  private final Executable invocationCreator;
  private final Constructor<?> defaultConstructor;
  private final String[] parameterNames;
  private final Method[] accessors;
  private final Method[] defaultMethods;
  private final Object defaultsReceiver;
  private final int[] defaultMaskBits;
  private final boolean[] parameterNullable;
  private final TypeRef<?>[] parameterTypes;
  private final String[] propertyNames;
  private final Method[] propertyGetters;
  private final Method[] propertySetters;
  private final TypeRef<?>[] propertyTypes;
  private final boolean[] propertyReconstructible;
  private final boolean[] propertyRequired;
  private final Object fixedInstance;
  private final Field[] nonPropertyFields;

  /** Creates one ordinary language object model. */
  public JsonObjectModel(
      Constructor<?> constructor,
      Constructor<?> defaultConstructor,
      String[] parameterNames,
      Method[] accessors,
      Method[] defaultMethods,
      int[] defaultMaskBits,
      boolean[] parameterNullable,
      TypeRef<?>[] parameterTypes,
      String[] propertyNames,
      Method[] propertyGetters,
      Method[] propertySetters,
      TypeRef<?>[] propertyTypes) {
    this(
        constructor,
        defaultConstructor,
        parameterNames,
        accessors,
        defaultMethods,
        null,
        defaultMaskBits,
        parameterNullable,
        parameterTypes,
        propertyNames,
        propertyGetters,
        propertySetters,
        propertyTypes);
  }

  /**
   * Creates one ordinary language object model whose constructor defaults are instance methods on
   * {@code defaultsReceiver}. Scala emits {@code $lessinit$greater$default$N} on the companion
   * singleton and mirrors it as a static forwarder on the case class only for a top-level
   * companion, so a case class declared inside an {@code object} binds its defaults on that
   * singleton. Pass {@code null} when the defaults are static members of the created type.
   */
  public JsonObjectModel(
      Constructor<?> constructor,
      Constructor<?> defaultConstructor,
      String[] parameterNames,
      Method[] accessors,
      Method[] defaultMethods,
      Object defaultsReceiver,
      int[] defaultMaskBits,
      boolean[] parameterNullable,
      TypeRef<?>[] parameterTypes,
      String[] propertyNames,
      Method[] propertyGetters,
      Method[] propertySetters,
      TypeRef<?>[] propertyTypes) {
    this(
        (Executable) constructor,
        constructor,
        defaultConstructor,
        parameterNames,
        accessors,
        defaultMethods,
        defaultsReceiver,
        defaultMaskBits,
        parameterNullable,
        parameterTypes,
        propertyNames,
        propertyGetters,
        propertySetters,
        propertyTypes,
        allProperties(propertyNames.length),
        new boolean[propertyNames.length]);
  }

  /** Creates a model for an explicitly selected constructor or static factory. */
  public JsonObjectModel(
      Executable creator,
      Executable invocationCreator,
      Constructor<?> defaultConstructor,
      String[] parameterNames,
      Method[] accessors,
      Method[] defaultMethods,
      int[] defaultMaskBits,
      boolean[] parameterNullable,
      TypeRef<?>[] parameterTypes,
      String[] propertyNames,
      Method[] propertyGetters,
      Method[] propertySetters,
      TypeRef<?>[] propertyTypes) {
    this(
        creator,
        invocationCreator,
        defaultConstructor,
        parameterNames,
        accessors,
        defaultMethods,
        null,
        defaultMaskBits,
        parameterNullable,
        parameterTypes,
        propertyNames,
        propertyGetters,
        propertySetters,
        propertyTypes,
        allProperties(propertyNames.length),
        new boolean[propertyNames.length]);
  }

  /** Creates a model with exact reconstructibility and deferred-required facts. */
  public JsonObjectModel(
      Executable creator,
      Executable invocationCreator,
      Constructor<?> defaultConstructor,
      String[] parameterNames,
      Method[] accessors,
      Method[] defaultMethods,
      int[] defaultMaskBits,
      boolean[] parameterNullable,
      TypeRef<?>[] parameterTypes,
      String[] propertyNames,
      Method[] propertyGetters,
      Method[] propertySetters,
      TypeRef<?>[] propertyTypes,
      boolean[] propertyReconstructible,
      boolean[] propertyRequired) {
    this(
        creator,
        invocationCreator,
        defaultConstructor,
        parameterNames,
        accessors,
        defaultMethods,
        null,
        defaultMaskBits,
        parameterNullable,
        parameterTypes,
        propertyNames,
        propertyGetters,
        propertySetters,
        propertyTypes,
        propertyReconstructible,
        propertyRequired);
  }

  private JsonObjectModel(
      Executable creator,
      Executable invocationCreator,
      Constructor<?> defaultConstructor,
      String[] parameterNames,
      Method[] accessors,
      Method[] defaultMethods,
      Object defaultsReceiver,
      int[] defaultMaskBits,
      boolean[] parameterNullable,
      TypeRef<?>[] parameterTypes,
      String[] propertyNames,
      Method[] propertyGetters,
      Method[] propertySetters,
      TypeRef<?>[] propertyTypes,
      boolean[] propertyReconstructible,
      boolean[] propertyRequired) {
    this.creator = Objects.requireNonNull(creator, "creator");
    this.invocationCreator = Objects.requireNonNull(invocationCreator, "invocationCreator");
    this.defaultConstructor = defaultConstructor;
    this.parameterNames = parameterNames.clone();
    this.accessors = accessors.clone();
    this.defaultMethods = defaultMethods.clone();
    this.defaultsReceiver = defaultsReceiver;
    this.defaultMaskBits = defaultMaskBits.clone();
    this.parameterNullable = parameterNullable.clone();
    this.parameterTypes = parameterTypes.clone();
    this.propertyNames = propertyNames.clone();
    this.propertyGetters = propertyGetters.clone();
    this.propertySetters = propertySetters.clone();
    this.propertyTypes = propertyTypes.clone();
    this.propertyReconstructible = propertyReconstructible.clone();
    this.propertyRequired = propertyRequired.clone();
    this.fixedInstance = null;
    nonPropertyFields = new Field[0];
    validate();
  }

  private JsonObjectModel(
      Object fixedInstance,
      String[] propertyNames,
      Method[] propertyGetters,
      Method[] propertySetters,
      TypeRef<?>[] propertyTypes,
      Field[] nonPropertyFields) {
    creator = null;
    invocationCreator = null;
    defaultConstructor = null;
    parameterNames = new String[0];
    accessors = new Method[0];
    defaultMethods = new Method[0];
    defaultsReceiver = null;
    defaultMaskBits = new int[0];
    parameterNullable = new boolean[0];
    parameterTypes = new TypeRef<?>[0];
    if (propertyGetters.length != propertyNames.length
        || propertySetters.length != propertyNames.length
        || propertyTypes.length != propertyNames.length) {
      throw new IllegalArgumentException("Fixed JSON object-model property arrays must match");
    }
    HashSet<String> names = new HashSet<>();
    for (int i = 0; i < propertyNames.length; i++) {
      String name = propertyNames[i];
      if (name == null || name.isEmpty() || !names.add(name)) {
        throw new IllegalArgumentException("Invalid JSON object model property name " + name);
      }
      Objects.requireNonNull(propertyTypes[i], "propertyType");
    }
    this.propertyNames = propertyNames.clone();
    this.propertyGetters = propertyGetters.clone();
    this.propertySetters = propertySetters.clone();
    this.propertyTypes = propertyTypes.clone();
    propertyReconstructible = new boolean[propertyNames.length];
    propertyRequired = new boolean[propertyNames.length];
    this.fixedInstance = Objects.requireNonNull(fixedInstance, "fixedInstance");
    HashSet<Field> fields = new HashSet<>();
    Class<?> instanceType = fixedInstance.getClass();
    for (Field field : nonPropertyFields) {
      Objects.requireNonNull(field, "nonPropertyField");
      int modifiers = field.getModifiers();
      if (!field.getDeclaringClass().isAssignableFrom(instanceType)
          || Modifier.isStatic(modifiers)
          || !Modifier.isFinal(modifiers)
          || !fields.add(field)) {
        throw new IllegalArgumentException("Invalid fixed-model non-property field " + field);
      }
    }
    this.nonPropertyFields = nonPropertyFields.clone();
  }

  /**
   * Creates a stateless language singleton model whose JSON representation is exactly {@code {}}.
   */
  public static JsonObjectModel fixedInstance(Object instance) {
    return new JsonObjectModel(
        instance, new String[0], new Method[0], new Method[0], new TypeRef<?>[0], new Field[0]);
  }

  /** Creates a singleton candidate with effective-property validation. */
  public static JsonObjectModel fixedInstance(
      Object instance,
      String[] propertyNames,
      Method[] propertyGetters,
      Method[] propertySetters,
      TypeRef<?>[] propertyTypes) {
    return new JsonObjectModel(
        instance, propertyNames, propertyGetters, propertySetters, propertyTypes, new Field[0]);
  }

  /** Creates a fixed model with exact compiler storage excluded from logical JSON state. */
  public static JsonObjectModel fixedInstance(
      Object instance,
      String[] propertyNames,
      Method[] propertyGetters,
      Method[] propertySetters,
      TypeRef<?>[] propertyTypes,
      Field[] nonPropertyFields) {
    return new JsonObjectModel(
        instance,
        propertyNames,
        propertyGetters,
        propertySetters,
        propertyTypes,
        nonPropertyFields);
  }

  public Object fixedInstance() {
    return fixedInstance;
  }

  private void validate() {
    int count = creator.getParameterCount();
    if (parameterNames.length != count
        || accessors.length != count
        || defaultMethods.length != count
        || defaultMaskBits.length != count
        || parameterNullable.length != count
        || parameterTypes.length != count) {
      throw new IllegalArgumentException("JSON object model arrays must match constructor arity");
    }
    if (propertyGetters.length != propertyNames.length
        || propertySetters.length != propertyNames.length
        || propertyTypes.length != propertyNames.length
        || propertyReconstructible.length != propertyNames.length
        || propertyRequired.length != propertyNames.length) {
      throw new IllegalArgumentException(
          "JSON object model property arrays must have equal length");
    }
    Class<?>[] logicalCarriers = creator.getParameterTypes();
    Class<?>[] invocationCarriers = invocationCreator.getParameterTypes();
    if (invocationCreator.getDeclaringClass() != creator.getDeclaringClass()
        || invocationCarriers.length < count
        || invocationCarriers.length > count + 1) {
      throw new IllegalArgumentException("Invalid JSON object-model invocation constructor");
    }
    for (int i = 0; i < count; i++) {
      if (invocationCarriers[i] != logicalCarriers[i]) {
        throw new IllegalArgumentException("JSON invocation constructor carrier mismatch");
      }
    }
    if (invocationCarriers.length != count && invocationCarriers[count].isPrimitive()) {
      throw new IllegalArgumentException("JSON invocation marker must be a reference type");
    }
    if (creator instanceof Method || invocationCreator instanceof Method) {
      if (!(creator instanceof Method)
          || !(invocationCreator instanceof Method)
          || invocationCarriers.length != count
          || defaultConstructor != null) {
        throw new IllegalArgumentException("Invalid JSON object-model factory targets");
      }
      Class<?> owner = creator.getDeclaringClass();
      if (((Method) creator).getReturnType() != owner
          || ((Method) invocationCreator).getReturnType() != owner) {
        throw new IllegalArgumentException("JSON object-model factory must return its exact owner");
      }
    }
    HashSet<String> names = new HashSet<>();
    boolean hasDefaultMethod = false;
    for (int i = 0; i < parameterNames.length; i++) {
      String name = parameterNames[i];
      if (name == null || name.isEmpty() || !names.add(name)) {
        throw new IllegalArgumentException("Invalid JSON object model parameter name " + name);
      }
      Objects.requireNonNull(parameterTypes[i], "parameterType");
      if (defaultMethods[i] != null && defaultMaskBits[i] >= 0) {
        throw new IllegalArgumentException("A constructor parameter has two default mechanisms");
      }
      if (defaultMethods[i] != null
          && Modifier.isStatic(defaultMethods[i].getModifiers()) == (defaultsReceiver != null)) {
        throw new IllegalArgumentException(
            "A JSON constructor default receiver is required exactly for instance defaults "
                + defaultMethods[i]);
      }
      if (defaultsReceiver != null
          && defaultMethods[i] != null
          && !defaultMethods[i].getDeclaringClass().isInstance(defaultsReceiver)) {
        throw new IllegalArgumentException(
            "JSON constructor default receiver does not own " + defaultMethods[i]);
      }
      hasDefaultMethod |= defaultMethods[i] != null;
    }
    if (defaultsReceiver != null && !hasDefaultMethod) {
      throw new IllegalArgumentException(
          "A JSON constructor default receiver requires at least one instance default");
    }
    names.clear();
    for (int i = 0; i < propertyNames.length; i++) {
      String name = propertyNames[i];
      if (name == null || name.isEmpty() || !names.add(name)) {
        throw new IllegalArgumentException("Invalid JSON object model property name " + name);
      }
      Objects.requireNonNull(propertyTypes[i], "propertyType");
      if (propertyRequired[i] && !propertyReconstructible[i]) {
        throw new IllegalArgumentException(
            "Required deferred JSON property must be reconstructible " + name);
      }
      if (propertyRequired[i]
          && (propertyTypes[i].getRawType().isPrimitive()
              || propertyTypes[i].getTypeExtMeta() == null
              || propertyTypes[i].getTypeExtMeta().nullable()
              || propertyTypes[i].getTypeExtMeta().nullableWrapper())) {
        throw new IllegalArgumentException(
            "Required deferred JSON property must have a non-null reference setter " + name);
      }
    }
    boolean hasMaskedDefault = false;
    for (int bit : defaultMaskBits) {
      if (bit >= 0) {
        hasMaskedDefault = true;
        break;
      }
    }
    if (hasMaskedDefault != (defaultConstructor != null)) {
      throw new IllegalArgumentException(
          "Compiler-default mask metadata requires exactly one default constructor");
    }
    if (defaultConstructor != null) {
      Class<?>[] invocationTypes = defaultConstructor.getParameterTypes();
      int maskCount = (count + 31) >>> 5;
      if (defaultConstructor.getDeclaringClass() != creator.getDeclaringClass()
          || invocationTypes.length != count + maskCount + 1
          || !(creator instanceof Constructor)) {
        throw new IllegalArgumentException("Invalid compiler-default constructor shape");
      }
      for (int i = 0; i < count; i++) {
        if (invocationTypes[i] != creator.getParameterTypes()[i]) {
          throw new IllegalArgumentException("Compiler-default constructor carrier mismatch");
        }
      }
      for (int i = 0; i < maskCount; i++) {
        if (invocationTypes[count + i] != int.class) {
          throw new IllegalArgumentException("Compiler-default mask must use int words");
        }
      }
      if (invocationTypes[invocationTypes.length - 1].isPrimitive()) {
        throw new IllegalArgumentException("Compiler-default marker must be a reference type");
      }
      for (int i = 0; i < count; i++) {
        int bit = defaultMaskBits[i];
        if (bit >= 0 && bit != i) {
          throw new IllegalArgumentException(
              "Compiler-default mask bit must match parameter index");
        }
      }
    }
  }

  public Executable creator() {
    return creator;
  }

  public Executable invocationCreator() {
    return invocationCreator;
  }

  public Constructor<?> defaultConstructor() {
    return defaultConstructor;
  }

  public String[] parameterNames() {
    return parameterNames.clone();
  }

  public Method[] accessors() {
    return accessors.clone();
  }

  public Method[] defaultMethods() {
    return defaultMethods.clone();
  }

  /** Returns the receiver of instance constructor-default methods, or null when they are static. */
  public Object defaultsReceiver() {
    return defaultsReceiver;
  }

  public int[] defaultMaskBits() {
    return defaultMaskBits.clone();
  }

  public boolean[] parameterNullable() {
    return parameterNullable.clone();
  }

  public TypeRef<?>[] parameterTypes() {
    return parameterTypes.clone();
  }

  public String[] propertyNames() {
    return propertyNames.clone();
  }

  public Method[] propertyGetters() {
    return propertyGetters.clone();
  }

  public Method[] propertySetters() {
    return propertySetters.clone();
  }

  public TypeRef<?>[] propertyTypes() {
    return propertyTypes.clone();
  }

  /** Returns whether each property has stable constructor or deferred storage. */
  public boolean[] propertyReconstructible() {
    return propertyReconstructible.clone();
  }

  /** Returns required-deferred flags aligned with {@link #propertyNames()}. */
  public boolean[] propertyRequired() {
    return propertyRequired.clone();
  }

  /** Returns exact compiler storage which is not part of fixed-instance JSON state. */
  public Field[] nonPropertyFields() {
    return nonPropertyFields.clone();
  }

  /** Compares one exact JVM member type with its normalized language-model type. */
  @Internal
  public static boolean compatibleType(TypeRef<?> memberType, TypeRef<?> logicalType) {
    Type member = memberType.getType();
    Type logical = logicalType.getType();
    if (member.equals(logical)) {
      return true;
    }
    if (member instanceof WildcardType
        && logicalType.getTypeExtMeta() != null
        && logicalType.getTypeExtMeta().covariant()) {
      WildcardType wildcard = (WildcardType) member;
      Type[] upperBounds = wildcard.getUpperBounds();
      return wildcard.getLowerBounds().length == 0
          && upperBounds.length == 1
          && upperBounds[0] != Object.class
          && compatibleType(TypeRef.of(upperBounds[0]), logicalType);
    }
    Class<?> memberRaw = memberType.getRawType();
    if (memberRaw != logicalType.getRawType()) {
      return false;
    }
    if (memberRaw.isArray()) {
      TypeRef<?> memberComponent = memberType.getComponentType();
      TypeRef<?> logicalComponent = logicalType.getComponentType();
      return memberComponent != null
          && logicalComponent != null
          && compatibleType(memberComponent, logicalComponent);
    }
    List<TypeRef<?>> memberArguments = memberType.getTypeArguments();
    List<TypeRef<?>> logicalArguments = logicalType.getTypeArguments();
    if (memberArguments.size() != logicalArguments.size()) {
      return false;
    }
    for (int i = 0; i < memberArguments.size(); i++) {
      if (!compatibleType(memberArguments.get(i), logicalArguments.get(i))) {
        return false;
      }
    }
    if (member instanceof ParameterizedType && logical instanceof ParameterizedType) {
      return Objects.equals(
          ((ParameterizedType) member).getOwnerType(),
          ((ParameterizedType) logical).getOwnerType());
    }
    // Scala 2 qualifies erased Enumeration.Value occurrences with an exact semantic owner.
    return member instanceof Class
        && logical instanceof ParameterizedType
        && memberRaw.getTypeParameters().length == 0
        && ((ParameterizedType) logical).getActualTypeArguments().length == 0;
  }

  private static boolean[] allProperties(int count) {
    boolean[] reconstructible = new boolean[count];
    for (int i = 0; i < count; i++) {
      reconstructible[i] = true;
    }
    return reconstructible;
  }
}

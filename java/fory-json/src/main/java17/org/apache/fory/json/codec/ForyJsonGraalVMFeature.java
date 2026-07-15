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

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.annotation.JsonBase64;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.apache.fory.json.annotation.JsonType;
import org.apache.fory.json.resolver.GeneratedJsonCodecFactories;
import org.apache.fory.json.resolver.JsonSharedRegistry;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

/** Registers reachable Fory JSON models for GraalVM native image reflection. */
final class ForyJsonGraalVMFeature implements Feature {
  private static final String[] SQL_TYPES = {
    "java.sql.Date", "java.sql.Time", "java.sql.Timestamp"
  };

  private final Set<Class<?>> reachableTypes = ConcurrentHashMap.newKeySet();
  private final Set<Class<?>> processedReachableTypes = ConcurrentHashMap.newKeySet();
  private final Set<Class<?>> processedDeclarations = ConcurrentHashMap.newKeySet();
  private final Set<Class<?>> processedModels = ConcurrentHashMap.newKeySet();
  private final Set<Class<?>> processedCodecs = ConcurrentHashMap.newKeySet();
  private final Set<Class<?>> processedContainers = ConcurrentHashMap.newKeySet();

  @Override
  public String getDescription() {
    return "Registers reachable Fory JSON models for GraalVM native image";
  }

  @Override
  public void beforeAnalysis(BeforeAnalysisAccess access) {
    RuntimeClassInitialization.initializeAtBuildTime(GeneratedJsonCodecFactories.class);
    access.registerSubtypeReachabilityHandler(this::processReachableType, Object.class);
  }

  private void processReachableType(DuringAnalysisAccess ignored, Class<?> type) {
    reachableTypes.add(type);
  }

  @Override
  public void duringAnalysis(DuringAnalysisAccess access) {
    if (!reachableTypes.contains(ForyJson.class)) {
      return;
    }
    boolean changed = false;
    for (Class<?> type : reachableTypes) {
      if (processedReachableTypes.add(type)) {
        changed |= registerContainer(type);
        changed |= registerDeclarations(type);
        if (type.getDeclaredAnnotation(JsonType.class) != null) {
          changed |= registerModel(access, type);
        }
        if (type == ForyJson.class) {
          registerBuiltInTypes(access);
          changed = true;
        }
      }
    }
    if (changed) {
      access.requireAnalysisIteration();
    }
  }

  private boolean registerModel(DuringAnalysisAccess access, Class<?> type) {
    if (!processedModels.add(type)) {
      return false;
    }
    RuntimeReflection.register(type);
    registerContainer(type);
    registerDeclarations(type);
    if (!type.isEnum()
        && !Collection.class.isAssignableFrom(type)
        && !Map.class.isAssignableFrom(type)) {
      registerModelHierarchy(access, type);
      if (!type.isRecord() && GraalvmSupport.needReflectionRegisterForCreation(type)) {
        RuntimeReflection.registerForReflectiveInstantiation(type);
      }
    }
    registerGeneratedCodec(access, type);
    registerSubtypes(access, type);
    return true;
  }

  private void registerGeneratedCodec(DuringAnalysisAccess access, Class<?> type) {
    String codecName = JsonSharedRegistry.generatedCodecBinaryName(type);
    Class<?> codecClass = access.findClassByName(codecName);
    if (codecClass == null) {
      return;
    }
    if (!GeneratedJsonCodec.class.isAssignableFrom(codecClass)) {
      throw new IllegalStateException(
          codecName + " does not extend " + GeneratedJsonCodec.class.getName());
    }
    try {
      if (!Modifier.isPublic(codecClass.getModifiers())) {
        throw new IllegalStateException("Generated JSON codec must be public: " + codecName);
      }
      codecClass.getConstructor();
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(
          "Generated JSON codec must have a public no-argument constructor: " + codecName, e);
    }
    String factoryName = codecName + "$Factory";
    Class<?> factoryClass = access.findClassByName(factoryName);
    if (factoryClass == null || !GeneratedJsonCodecFactory.class.isAssignableFrom(factoryClass)) {
      throw new IllegalStateException(
          "Missing generated JSON codec factory " + factoryName + " for " + type.getName());
    }
    int modifiers = factoryClass.getModifiers();
    if (!Modifier.isPublic(modifiers)
        || !Modifier.isStatic(modifiers)
        || !Modifier.isFinal(modifiers)) {
      throw new IllegalStateException(
          "Generated JSON codec factory must be public static final: " + factoryName);
    }
    try {
      GeneratedJsonCodecFactory factory =
          (GeneratedJsonCodecFactory) ReflectionUtils.getCtrHandle(factoryClass).invoke();
      GeneratedJsonCodec<?> codec =
          JsonSharedRegistry.validateGeneratedCodec(type, factory.create());
      if (codec.validatedRecord() != type.isRecord()) {
        throw new IllegalStateException(
            "Generated JSON codec Record metadata does not match " + type.getName());
      }
      GeneratedJsonCodecFactories.register(type, factory);
    } catch (Throwable e) {
      throw new IllegalStateException(
          "Cannot initialize generated JSON codec factory " + factoryName, e);
    }
  }

  @Override
  public void afterAnalysis(AfterAnalysisAccess access) {
    GeneratedJsonCodecFactories.freeze();
  }

  private void registerModelHierarchy(BeforeAnalysisAccess access, Class<?> type) {
    TypeRef<?> ownerType = TypeRef.of(type);
    boolean record = type.isRecord();
    for (Class<?> current = type;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      // ObjectCodecBuilder still reads semantic annotations, generic types, and exact creator
      // signatures at image runtime. Register that metadata here instead of routing JSON models
      // through the core Fory feature, whose Record registration initializes RecordUtils and would
      // reintroduce the native-Record path that generated JSON companions replace.
      RuntimeReflection.register(current);
      RuntimeReflection.register(current.getDeclaredFields());
      RuntimeReflection.register(current.getDeclaredMethods());
      RuntimeReflection.register(current.getDeclaredConstructors());
      for (Field field : current.getDeclaredFields()) {
        if (isJsonField(field)) {
          if (!current.isRecord() && Runtime.version().feature() <= 24) {
            access.registerAsUnsafeAccessed(field);
          }
          registerOccurrenceCodecs(field);
          registerResolvedType(ownerType.resolveType(field.getGenericType()).getType());
        }
      }
    }
    for (Method method : type.getMethods()) {
      if (ObjectCodecBuilder.usesJsonMetadata(method, record)) {
        if (method.getDeclaringClass().isInterface()) {
          RuntimeReflection.register(method);
        }
        if (ObjectCodecBuilder.usesJsonReturn(method)) {
          registerOccurrenceCodecs(method);
          registerResolvedType(ownerType.resolveType(method.getGenericReturnType()).getType());
        }
        if (ObjectCodecBuilder.usesJsonParameters(method)) {
          registerParameterCodecs(method.getParameters());
          registerResolvedParameterTypes(ownerType, method.getParameters());
        }
      }
    }
    for (Constructor<?> constructor : type.getDeclaredConstructors()) {
      if (constructor.isAnnotationPresent(JsonCreator.class)) {
        registerParameterCodecs(constructor.getParameters());
        registerResolvedParameterTypes(ownerType, constructor.getParameters());
      }
    }
    for (Method method : type.getDeclaredMethods()) {
      if (method.isAnnotationPresent(JsonCreator.class)) {
        registerParameterCodecs(method.getParameters());
        registerResolvedParameterTypes(ownerType, method.getParameters());
      }
    }
  }

  private boolean registerDeclarations(Class<?> type) {
    if (type == null || type == Object.class || !processedDeclarations.add(type)) {
      return false;
    }
    boolean changed = false;
    JsonCodec annotation = type.getDeclaredAnnotation(JsonCodec.class);
    if (annotation != null) {
      RuntimeReflection.register(type);
      registerCodecs(annotation);
      changed = true;
    }
    changed |= registerDeclarations(type.getSuperclass());
    for (Class<?> interfaceType : type.getInterfaces()) {
      changed |= registerDeclarations(interfaceType);
    }
    return changed;
  }

  private void registerParameterCodecs(Parameter[] parameters) {
    for (Parameter parameter : parameters) {
      registerCodecs(parameter.getDeclaredAnnotation(JsonCodec.class));
    }
  }

  private void registerOccurrenceCodecs(AnnotatedElement element) {
    registerCodecs(element.getDeclaredAnnotation(JsonCodec.class));
    if (element.isAnnotationPresent(JsonBase64.class)) {
      registerCodec(Base64ByteArrayCodec.class);
    }
  }

  private void registerResolvedParameterTypes(TypeRef<?> ownerType, Parameter[] parameters) {
    for (Parameter parameter : parameters) {
      registerResolvedType(ownerType.resolveType(parameter.getParameterizedType()).getType());
    }
  }

  private void registerResolvedType(Type type) {
    Set<TypeVariable<?>> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
    registerResolvedType(type, visiting);
  }

  private void registerResolvedType(Type type, Set<TypeVariable<?>> visiting) {
    if (type == null) {
      return;
    }
    registerContainer(type);
    if (type instanceof ParameterizedType) {
      ParameterizedType parameterizedType = (ParameterizedType) type;
      registerResolvedType(parameterizedType.getOwnerType(), visiting);
      for (Type argument : parameterizedType.getActualTypeArguments()) {
        registerResolvedType(argument, visiting);
      }
    } else if (type instanceof GenericArrayType) {
      registerResolvedType(((GenericArrayType) type).getGenericComponentType(), visiting);
    } else if (type instanceof WildcardType) {
      WildcardType wildcardType = (WildcardType) type;
      registerResolvedTypes(wildcardType.getUpperBounds(), visiting);
      registerResolvedTypes(wildcardType.getLowerBounds(), visiting);
    } else if (type instanceof TypeVariable<?>) {
      TypeVariable<?> variable = (TypeVariable<?>) type;
      if (visiting.add(variable)) {
        registerResolvedTypes(variable.getBounds(), visiting);
        visiting.remove(variable);
      }
    }
  }

  private void registerResolvedTypes(Type[] types, Set<TypeVariable<?>> visiting) {
    for (Type type : types) {
      registerResolvedType(type, visiting);
    }
  }

  private void registerCodecs(JsonCodec annotation) {
    if (annotation == null) {
      return;
    }
    registerCodec(annotation.value());
    registerCodec(annotation.elementCodec());
    registerCodec(annotation.contentCodec());
    registerCodec(annotation.keyCodec());
    registerCodec(annotation.valueCodec());
  }

  private void registerCodec(Class<?> codecClass) {
    if (codecClass == JsonCodec.NoJsonValueCodec.class
        || codecClass == JsonCodec.NoMapKeyCodec.class) {
      return;
    }
    if (!processedCodecs.add(codecClass)) {
      return;
    }
    RuntimeReflection.register(codecClass);
    try {
      RuntimeReflection.register(codecClass.getConstructor());
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(
          "JSON codec class must have a public no-argument constructor: " + codecClass.getName(),
          e);
    }
  }

  private boolean registerContainer(Type type) {
    Class<?> rawType = null;
    if (type instanceof Class<?>) {
      rawType = (Class<?>) type;
    } else if (type instanceof ParameterizedType) {
      Type parameterizedRawType = ((ParameterizedType) type).getRawType();
      if (parameterizedRawType instanceof Class<?>) {
        rawType = (Class<?>) parameterizedRawType;
      }
    }
    if (rawType == null
        || rawType.isInterface()
        || Modifier.isAbstract(rawType.getModifiers())
        || (!Collection.class.isAssignableFrom(rawType) && !Map.class.isAssignableFrom(rawType))
        || !processedContainers.add(rawType)) {
      return false;
    }
    try {
      RuntimeReflection.register(rawType.getConstructor());
      return true;
    } catch (NoSuchMethodException ignored) {
      // CollectionCodec and MapCodec preserve the same runtime failure for a concrete container
      // without a public no-argument constructor.
      return false;
    }
  }

  private void registerSubtypes(DuringAnalysisAccess access, Class<?> type) {
    JsonSubTypes annotation = type.getDeclaredAnnotation(JsonSubTypes.class);
    if (annotation == null) {
      return;
    }
    for (JsonSubTypes.Type entry : annotation.value()) {
      Class<?> subtype = entry.value();
      if (subtype != Void.class) {
        registerModel(access, subtype);
      }
    }
  }

  private void registerSqlTypes(DuringAnalysisAccess access) {
    for (String className : SQL_TYPES) {
      Class<?> type = access.findClassByName(className);
      if (type != null) {
        RuntimeReflection.register(type);
        try {
          RuntimeReflection.register(type.getConstructor(long.class));
        } catch (NoSuchMethodException e) {
          throw new IllegalStateException("Missing Fory JSON SQL constructor for " + className, e);
        }
      }
    }
  }

  private void registerBuiltInTypes(DuringAnalysisAccess access) {
    registerSqlTypes(access);
    registerBigDecimalFields(access);
  }

  private void registerBigDecimalFields(BeforeAnalysisAccess access) {
    registerBigDecimalField(access, "intCompact", long.class);
    registerBigDecimalField(access, "intVal", BigInteger.class);
    registerBigDecimalField(access, "scale", int.class);
  }

  private void registerBigDecimalField(
      BeforeAnalysisAccess access, String fieldName, Class<?> fieldType) {
    try {
      Field field = BigDecimal.class.getDeclaredField(fieldName);
      if (field.getType() == fieldType) {
        RuntimeReflection.register(field);
        if (Runtime.version().feature() <= 24) {
          access.registerAsUnsafeAccessed(field);
        }
      }
    } catch (NoSuchFieldException ignored) {
      // BigDecimalFields preserves its public-JDK fallback if a future JDK changes this layout.
    }
  }

  private static boolean isJsonField(Field field) {
    int modifiers = field.getModifiers();
    return !Modifier.isStatic(modifiers)
        && !Modifier.isTransient(modifiers)
        && field.getType() != Class.class
        && !field.isSynthetic();
  }
}

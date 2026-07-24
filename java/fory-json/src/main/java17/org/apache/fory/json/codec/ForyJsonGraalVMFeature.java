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
import java.lang.reflect.Executable;
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
import org.apache.fory.json.annotation.JsonMixin;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.apache.fory.json.annotation.JsonType;
import org.apache.fory.json.annotation.JsonUnwrapped;
import org.apache.fory.json.annotation.JsonValue;
import org.apache.fory.json.resolver.GeneratedJsonCodecFactories;
import org.apache.fory.json.resolver.JsonSharedRegistry;
import org.apache.fory.json.resolver.JsonSharedRegistry.JsonMixinView;
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
  private final Set<Class<?>> processedMixins = ConcurrentHashMap.newKeySet();
  private final Set<Class<?>> processedCodecs = ConcurrentHashMap.newKeySet();
  private final Set<Class<?>> processedContainers = ConcurrentHashMap.newKeySet();

  @Override
  public String getDescription() {
    return "Registers reachable Fory JSON models for GraalVM native image";
  }

  @Override
  public void beforeAnalysis(BeforeAnalysisAccess access) {
    RuntimeClassInitialization.initializeAtBuildTime(GeneratedJsonCodecFactories.class);
    // Exact target-Mixin keys are retained in the frozen factory table and therefore become image
    // heap objects together with the table. Derive the private key name from its enclosing class so
    // relocated/shaded packages remain valid.
    RuntimeClassInitialization.initializeAtBuildTime(
        GeneratedJsonCodecFactories.class.getName() + "$Key");
    // Hosted Mixin discovery deliberately reuses the runtime's structural resolver so build-time
    // reachability and runtime semantics cannot drift. Its static state contains only the immutable
    // supported-annotation set and is therefore safe to initialize in the image builder.
    RuntimeClassInitialization.initializeAtBuildTime(
        ForyJson.class.getPackage().getName() + ".resolver.JsonMixinAnnotations");
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
        JsonMixin mixin = type.getDeclaredAnnotation(JsonMixin.class);
        if (mixin != null) {
          changed |= registerMixin(access, type, mixin.target());
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
    registerGeneratedCodec(access, type, null);
    registerSubtypes(access, type);
    return true;
  }

  private boolean registerMixin(
      DuringAnalysisAccess access, Class<?> mixinType, Class<?> targetType) {
    if (!processedMixins.add(mixinType)) {
      return false;
    }
    JsonMixinView annotations = JsonSharedRegistry.resolveMixin(targetType, mixinType);
    RuntimeReflection.register(mixinType);
    if (annotations.isEmpty()) {
      return true;
    }
    registerReflectiveDeclarations(annotations.sourceDeclarations());
    registerReflectiveDeclarations(annotations.targetDeclarations());
    // Retain every directly declared hierarchy codec plus the exact Mixin replacement. Runtime
    // resolution remains the sole owner of codec precedence and conflict validation.
    registerDeclarations(targetType);
    JsonCodec directTypeCodec = annotations.annotation(targetType, JsonCodec.class);
    registerCodecs(directTypeCodec);
    RuntimeReflection.register(targetType);
    registerContainer(targetType);
    boolean intrinsicTarget =
        targetType.isEnum()
            || Collection.class.isAssignableFrom(targetType)
            || Map.class.isAssignableFrom(targetType);
    boolean hasDirectTypeCodec = directTypeCodec != null;
    boolean hasTypeCodec = hasDirectTypeCodec || hasInheritedTypeCodec(targetType, annotations);
    boolean hasJsonValue =
        (!hasTypeCodec || isCompleteTypeCodec(directTypeCodec))
            && registerJsonValueDeclarations(access, targetType, annotations);
    JsonSubTypes subTypes = annotation(annotations, targetType, JsonSubTypes.class);
    // Annotation-selected complete representations make ordinary object metadata unreachable.
    // Keep builder registrations and built-in codec policy runtime-owned by treating only the
    // effective annotations visible here as hosted reachability facts.
    if (!intrinsicTarget && !hasTypeCodec && !hasJsonValue && subTypes == null) {
      registerModelHierarchy(access, targetType, annotations);
      if (!targetType.isRecord()
          && GraalvmSupport.needReflectionRegisterForCreation(targetType)) {
        RuntimeReflection.registerForReflectiveInstantiation(targetType);
      }
    }
    registerGeneratedCodec(access, targetType, mixinType);
    if (!hasTypeCodec && !hasJsonValue) {
      registerSubtypes(access, targetType, annotations);
    }
    return true;
  }

  private boolean hasInheritedTypeCodec(Class<?> type, JsonMixinView annotations) {
    Class<?> superclass = type.getSuperclass();
    if (superclass != null
        && (annotation(annotations, superclass, JsonCodec.class) != null
            || hasInheritedTypeCodec(superclass, annotations))) {
      return true;
    }
    for (Class<?> interfaceType : type.getInterfaces()) {
      if (annotation(annotations, interfaceType, JsonCodec.class) != null
          || hasInheritedTypeCodec(interfaceType, annotations)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isCompleteTypeCodec(JsonCodec annotation) {
    return annotation != null
        && annotation.value() != JsonCodec.NoJsonValueCodec.class
        && annotation.elementCodec() == JsonCodec.NoJsonValueCodec.class
        && annotation.contentCodec() == JsonCodec.NoJsonValueCodec.class
        && annotation.keyCodec() == JsonCodec.NoMapKeyCodec.class
        && annotation.valueCodec() == JsonCodec.NoJsonValueCodec.class;
  }

  private boolean registerJsonValueDeclarations(
      DuringAnalysisAccess access, Class<?> type, JsonMixinView annotations) {
    boolean hasValue = false;
    for (Class<?> current = type;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      for (Field field : current.getDeclaredFields()) {
        if (annotation(annotations, field, JsonValue.class) != null) {
          hasValue = true;
          RuntimeReflection.register(field);
          if (Runtime.version().feature() <= 24) {
            access.registerAsUnsafeAccessed(field);
          }
          registerOccurrenceCodecs(annotations, field);
        }
      }
    }
    for (Method method : type.getMethods()) {
      if (annotation(annotations, method, JsonValue.class) != null) {
        hasValue = true;
        RuntimeReflection.register(method);
        registerOccurrenceCodecs(annotations, method);
      }
    }
    for (Class<?> current = type;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      for (Method method : current.getDeclaredMethods()) {
        if (!Modifier.isPublic(method.getModifiers())
            && annotation(annotations, method, JsonValue.class) != null) {
          hasValue = true;
          RuntimeReflection.register(method);
          registerOccurrenceCodecs(annotations, method);
        }
      }
    }
    if (!hasValue) {
      return false;
    }
    for (Constructor<?> constructor : type.getDeclaredConstructors()) {
      if (annotation(annotations, constructor, JsonCreator.class) != null) {
        RuntimeReflection.register(constructor);
      }
    }
    for (Method method : type.getDeclaredMethods()) {
      if (annotation(annotations, method, JsonCreator.class) != null) {
        RuntimeReflection.register(method);
      }
    }
    return true;
  }

  private void registerReflectiveDeclarations(Set<AnnotatedElement> declarations) {
    for (AnnotatedElement declaration : declarations) {
      if (declaration instanceof Field) {
        RuntimeReflection.register((Field) declaration);
      } else if (declaration instanceof Method) {
        RuntimeReflection.register((Method) declaration);
      } else if (declaration instanceof Constructor<?>) {
        RuntimeReflection.register((Constructor<?>) declaration);
      } else if (declaration instanceof Parameter) {
        RuntimeReflection.register(((Parameter) declaration).getDeclaringExecutable());
      }
    }
  }

  private void registerGeneratedCodec(
      DuringAnalysisAccess access, Class<?> type, Class<?> mixinType) {
    String codecName =
        mixinType == null
            ? JsonSharedRegistry.generatedCodecBinaryName(type)
            : JsonSharedRegistry.generatedMixinCodecBinaryName(mixinType, type);
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
      GeneratedJsonCodecFactories.register(type, mixinType, factory);
    } catch (Throwable e) {
      throw new IllegalStateException(
          "Cannot initialize generated JSON codec factory " + factoryName, e);
    }
  }

  @Override
  public void afterAnalysis(AfterAnalysisAccess access) {
    GeneratedJsonCodecFactories.freeze();
  }

  private void registerModelHierarchy(DuringAnalysisAccess access, Class<?> type) {
    registerModelHierarchy(access, type, null);
  }

  private void registerModelHierarchy(
      DuringAnalysisAccess access, Class<?> type, JsonMixinView annotations) {
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
          registerOccurrenceCodecs(annotations, field);
          Type resolvedType = ownerType.resolveType(field.getGenericType()).getType();
          registerResolvedType(resolvedType);
          if (annotation(annotations, field, JsonUnwrapped.class) != null) {
            registerNestedModel(access, resolvedType);
          }
        }
      }
    }
    for (Method method : type.getMethods()) {
      boolean mixinSelector = hasMixinSelector(annotations, method);
      if (ObjectCodecBuilder.usesJsonMetadata(method, record)
          || mixinSelector) {
        if (method.getDeclaringClass().isInterface()) {
          RuntimeReflection.register(method);
        }
        if (ObjectCodecBuilder.usesJsonReturn(method)
            || mixinSelector && method.getReturnType() != void.class) {
          registerOccurrenceCodecs(annotations, method);
          Type resolvedType = ownerType.resolveType(method.getGenericReturnType()).getType();
          registerResolvedType(resolvedType);
          if (annotation(annotations, method, JsonUnwrapped.class) != null) {
            registerNestedModel(access, resolvedType);
          }
        }
        if (ObjectCodecBuilder.usesJsonParameters(method)
            || mixinSelector && method.getParameterCount() != 0) {
          registerParameterCodecs(annotations, method.getParameters());
          registerResolvedParameterTypes(ownerType, method.getParameters());
          registerUnwrappedParameters(access, ownerType, annotations, method.getParameters());
        }
      }
    }
    for (Constructor<?> constructor : type.getDeclaredConstructors()) {
      if (annotation(annotations, constructor, JsonCreator.class) != null
          || hasMixinSelector(annotations, constructor)) {
        registerParameterCodecs(annotations, constructor.getParameters());
        registerResolvedParameterTypes(ownerType, constructor.getParameters());
        registerUnwrappedParameters(
            access, ownerType, annotations, constructor.getParameters());
      }
    }
    for (Method method : type.getDeclaredMethods()) {
      if (annotation(annotations, method, JsonCreator.class) != null
          || hasMixinSelector(annotations, method)) {
        registerParameterCodecs(annotations, method.getParameters());
        registerResolvedParameterTypes(ownerType, method.getParameters());
        registerUnwrappedParameters(access, ownerType, annotations, method.getParameters());
      }
    }
  }

  private void registerNestedModel(DuringAnalysisAccess access, Type type) {
    Class<?> rawType = rawType(type);
    if (rawType != null && rawType != Object.class) {
      registerModel(access, rawType);
    }
  }

  private void registerUnwrappedParameters(
      DuringAnalysisAccess access,
      TypeRef<?> ownerType,
      JsonMixinView annotations,
      Parameter[] parameters) {
    for (Parameter parameter : parameters) {
      if (annotation(annotations, parameter, JsonUnwrapped.class) != null) {
        registerNestedModel(
            access, ownerType.resolveType(parameter.getParameterizedType()).getType());
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

  private void registerParameterCodecs(
      JsonMixinView annotations, Parameter[] parameters) {
    for (Parameter parameter : parameters) {
      registerCodecs(annotation(annotations, parameter, JsonCodec.class));
    }
  }

  private void registerOccurrenceCodecs(
      JsonMixinView annotations, AnnotatedElement element) {
    registerCodecs(annotation(annotations, element, JsonCodec.class));
    if (annotation(annotations, element, JsonBase64.class) != null) {
      registerCodec(Base64ByteArrayCodec.class);
    }
  }

  private static <A extends java.lang.annotation.Annotation> A annotation(
      JsonMixinView annotations, AnnotatedElement element, Class<A> annotationType) {
    return annotations == null
        ? element.getDeclaredAnnotation(annotationType)
        : annotations.annotation(element, annotationType);
  }

  private static boolean hasMixinSelector(JsonMixinView annotations, Executable executable) {
    if (annotations == null) {
      return false;
    }
    Set<AnnotatedElement> declarations = annotations.targetDeclarations();
    if (declarations.contains(executable)) {
      return true;
    }
    for (Parameter parameter : executable.getParameters()) {
      if (declarations.contains(parameter)) {
        return true;
      }
    }
    return false;
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
    Class<?> rawType = rawType(type);
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
    registerSubtypes(access, type, null);
  }

  private void registerSubtypes(
      DuringAnalysisAccess access, Class<?> type, JsonMixinView annotations) {
    JsonSubTypes subTypes = annotation(annotations, type, JsonSubTypes.class);
    if (subTypes == null) {
      return;
    }
    for (JsonSubTypes.Type entry : subTypes.value()) {
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

  private static Class<?> rawType(Type type) {
    if (type instanceof Class<?>) {
      return (Class<?>) type;
    }
    if (type instanceof ParameterizedType) {
      Type rawType = ((ParameterizedType) type).getRawType();
      return rawType instanceof Class<?> ? (Class<?>) rawType : null;
    }
    return null;
  }
}

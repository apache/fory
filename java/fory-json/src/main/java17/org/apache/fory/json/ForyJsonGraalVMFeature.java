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

package org.apache.fory.json;

import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.AnnotatedTypeVariable;
import java.lang.reflect.AnnotatedWildcardType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.apache.fory.json.annotation.JsonType;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.platform.GraalvmSupport;
import org.graalvm.nativeimage.hosted.Feature;
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
    access.registerSubtypeReachabilityHandler(this::processReachableType, Object.class);
  }

  private void processReachableType(DuringAnalysisAccess ignored, Class<?> type) {
    reachableTypes.add(type);
  }

  @Override
  public void duringAnalysis(DuringAnalysisAccess access) {
    boolean changed = false;
    for (Class<?> type : reachableTypes) {
      if (processedReachableTypes.add(type)) {
        changed |= registerDeclarations(type);
        if (type.getDeclaredAnnotation(JsonType.class) != null) {
          changed |= registerModel(access, type);
        }
        if (type == ForyJson.class) {
          registerSqlTypes(access);
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
    GraalvmSupport.registerClass(type);
    registerContainer(type);
    registerDeclarations(type);
    if (!type.isEnum()
        && !Collection.class.isAssignableFrom(type)
        && !Map.class.isAssignableFrom(type)) {
      RuntimeReflection.register(type.getMethods());
      registerModelHierarchy(access, type);
    }
    registerSubtypes(access, type);
    return true;
  }

  private void registerModelHierarchy(BeforeAnalysisAccess access, Class<?> type) {
    for (Class<?> current = type;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      for (Field field : current.getDeclaredFields()) {
        if (isJsonField(field)) {
          if (!current.isRecord() && Runtime.version().feature() <= 24) {
            access.registerAsUnsafeAccessed(field);
          }
          registerAnnotatedType(field.getAnnotatedType());
        }
      }
      for (Constructor<?> constructor : current.getDeclaredConstructors()) {
        if (constructor.isAnnotationPresent(JsonCreator.class)) {
          registerParameterTypes(constructor.getParameters());
        }
      }
    }
    for (Method method : type.getMethods()) {
      registerMethodTypes(method);
    }
    if (type.isRecord()) {
      for (RecordComponent component : type.getRecordComponents()) {
        registerAnnotatedType(component.getAnnotatedType());
      }
    }
  }

  private boolean registerDeclarations(Class<?> type) {
    Set<Class<?>> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    return registerDeclarations(type, visited);
  }

  private boolean registerDeclarations(Class<?> type, Set<Class<?>> visited) {
    if (type == null || type == Object.class || !visited.add(type)) {
      return false;
    }
    boolean changed = false;
    if (processedDeclarations.add(type)) {
      JsonCodec annotation = type.getDeclaredAnnotation(JsonCodec.class);
      if (annotation != null) {
        RuntimeReflection.register(type);
        registerCodec(annotation.value());
        changed = true;
      }
    }
    changed |= registerDeclarations(type.getSuperclass(), visited);
    for (Class<?> interfaceType : type.getInterfaces()) {
      changed |= registerDeclarations(interfaceType, visited);
    }
    return changed;
  }

  private void registerMethodTypes(Method method) {
    registerAnnotatedType(method.getAnnotatedReturnType());
    registerParameterTypes(method.getParameters());
  }

  private void registerParameterTypes(Parameter[] parameters) {
    for (Parameter parameter : parameters) {
      registerAnnotatedType(parameter.getAnnotatedType());
    }
  }

  private void registerAnnotatedType(AnnotatedType type) {
    Set<TypeVariable<?>> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
    registerAnnotatedType(type, visiting);
  }

  private void registerAnnotatedType(AnnotatedType type, Set<TypeVariable<?>> visiting) {
    if (type == null) {
      return;
    }
    registerContainer(type.getType());
    JsonCodec annotation = type.getDeclaredAnnotation(JsonCodec.class);
    if (annotation != null) {
      registerCodec(annotation.value());
    }
    if (type instanceof AnnotatedParameterizedType) {
      AnnotatedParameterizedType parameterizedType = (AnnotatedParameterizedType) type;
      registerAnnotatedType(parameterizedType.getAnnotatedOwnerType(), visiting);
      for (AnnotatedType argument : parameterizedType.getAnnotatedActualTypeArguments()) {
        registerAnnotatedType(argument, visiting);
      }
    } else if (type instanceof AnnotatedArrayType) {
      registerAnnotatedType(
          ((AnnotatedArrayType) type).getAnnotatedGenericComponentType(), visiting);
    } else if (type instanceof AnnotatedWildcardType) {
      AnnotatedWildcardType wildcardType = (AnnotatedWildcardType) type;
      registerAnnotatedTypes(wildcardType.getAnnotatedUpperBounds(), visiting);
      registerAnnotatedTypes(wildcardType.getAnnotatedLowerBounds(), visiting);
    } else if (type instanceof AnnotatedTypeVariable) {
      TypeVariable<?> variable = (TypeVariable<?>) type.getType();
      if (visiting.add(variable)) {
        registerAnnotatedTypes(((AnnotatedTypeVariable) type).getAnnotatedBounds(), visiting);
        visiting.remove(variable);
      }
    }
  }

  private void registerAnnotatedTypes(AnnotatedType[] types, Set<TypeVariable<?>> visiting) {
    for (AnnotatedType type : types) {
      registerAnnotatedType(type, visiting);
    }
  }

  private void registerCodec(Class<? extends JsonValueCodec<?>> codecClass) {
    if (!processedCodecs.add(codecClass)) {
      return;
    }
    RuntimeReflection.register(codecClass);
    try {
      RuntimeReflection.register(codecClass.getConstructor());
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(
          "@JsonCodec class must have a public no-argument constructor: " + codecClass.getName(),
          e);
    }
  }

  private void registerContainer(Type type) {
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
      return;
    }
    try {
      RuntimeReflection.register(rawType.getConstructor());
    } catch (NoSuchMethodException ignored) {
      // CollectionCodec and MapCodec preserve the same runtime failure for a concrete container
      // without a public no-argument constructor.
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

  private static boolean isJsonField(Field field) {
    int modifiers = field.getModifiers();
    return !Modifier.isStatic(modifiers)
        && !Modifier.isTransient(modifiers)
        && field.getType() != Class.class
        && !field.isSynthetic();
  }
}

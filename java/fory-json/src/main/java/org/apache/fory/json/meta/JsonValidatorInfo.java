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

package org.apache.fory.json.meta;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.fory.annotation.Internal;
import org.apache.fory.collection.ClassValueCache;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.GeneratedJsonCodec;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.platform.internal._JDKAccess;

/** Immutable invocation capability for the effective validators of one object type. */
@Internal
public final class JsonValidatorInfo {
  private static final MethodType VALIDATOR_TYPE = MethodType.methodType(void.class, Object.class);
  // The Feature resolves each discovered validator through validatorHandle during analysis, and
  // runtime configurations retrieve the retained handles through the same cache.
  private static final ClassValueCache<ConcurrentMap<Method, MethodHandle>> NATIVE_VALIDATORS =
      ClassValueCache.newClassKeyCache(32);

  private final Class<?> type;
  private final Method[] methods;
  private final MethodHandle[] handles;
  private final GeneratedJsonCodec<?> generatedCodec;

  private JsonValidatorInfo(
      Class<?> type,
      Method[] methods,
      MethodHandle[] handles,
      GeneratedJsonCodec<?> generatedCodec) {
    this.type = type;
    this.methods = methods;
    this.handles = handles;
    this.generatedCodec = generatedCodec;
  }

  /** Creates one cold validator capability, or {@code null} when the type has no validators. */
  public static JsonValidatorInfo create(
      Class<?> type, Method[] methods, GeneratedJsonCodec<?> generatedCodec) {
    if (generatedCodec != null && !generatedCodec.matchesValidators(methods)) {
      throw new ForyJsonException(
          "Generated JSON validators do not match runtime annotations on " + type.getName());
    }
    if (methods == null || methods.length == 0) {
      return null;
    }
    if (generatedCodec != null) {
      return new JsonValidatorInfo(type, methods, null, generatedCodec);
    }
    if (AndroidSupport.IS_ANDROID) {
      throw new ForyJsonException(
          "Generated JSON validator operations are required for " + type.getName());
    }
    MethodHandle[] handles = new MethodHandle[methods.length];
    for (int i = 0; i < methods.length; i++) {
      Method method = methods[i];
      handles[i] = validatorHandle(method);
    }
    return new JsonValidatorInfo(type, methods, handles, null);
  }

  /** Invokes every validator without allocating on the successful path. */
  public void validate(Object target) {
    if (generatedCodec != null) {
      invokeGenerated(target);
      return;
    }
    MethodHandle[] localHandles = handles;
    for (int i = 0; i < localHandles.length; i++) {
      try {
        localHandles[i].invokeExact(target);
      } catch (Throwable throwable) {
        throw validatorFailure(methods[i], throwable);
      }
    }
  }

  private void invokeGenerated(Object target) {
    try {
      generatedCodec.invokeValidators(target);
    } catch (RuntimeException throwable) {
      if (throwable instanceof ForyJsonException) {
        throw (ForyJsonException) throwable;
      }
      throw validatorFailure(null, throwable);
    }
  }

  private ForyJsonException validatorFailure(Method method, Throwable throwable) {
    if (throwable instanceof Error) {
      throw (Error) throwable;
    }
    String member = method == null ? "generated operation" : method.toString();
    return new ForyJsonException(
        "JSON validator failed for " + type.getName() + ": " + member, throwable);
  }

  /** Returns the invocation handle for one JSON validator. */
  @Internal
  public static MethodHandle validatorHandle(Method method) {
    if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      ConcurrentMap<Method, MethodHandle> validators =
          NATIVE_VALIDATORS.get(method.getDeclaringClass(), ConcurrentHashMap::new);
      return validators.computeIfAbsent(method, JsonValidatorInfo::newValidatorHandle);
    }
    return newValidatorHandle(method);
  }

  /** Returns whether {@code method} has the supported validator shape. */
  @Internal
  public static boolean isValidatorMethod(Method method) {
    int modifiers = method.getModifiers();
    return Modifier.isPublic(modifiers)
        && !Modifier.isStatic(modifiers)
        && !Modifier.isAbstract(modifiers)
        && !method.isSynthetic()
        && !method.isBridge()
        && method.getParameterCount() == 0
        && method.getReturnType() == void.class;
  }

  private static MethodHandle newValidatorHandle(Method method) {
    try {
      return _JDKAccess._trustedLookup(method.getDeclaringClass())
          .unreflect(method)
          .asType(VALIDATOR_TYPE);
    } catch (IllegalAccessException e) {
      throw new ForyJsonException("Cannot access JSON validator " + method, e);
    }
  }
}

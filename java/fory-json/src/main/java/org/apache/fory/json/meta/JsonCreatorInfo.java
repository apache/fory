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
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.fory.annotation.Internal;
import org.apache.fory.collection.ClassValueCache;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.GeneratedJsonCodec;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.platform.internal._JDKAccess;

/**
 * Immutable ordered construction metadata for one JSON object codec.
 *
 * <p>Record canonical constructors, property-based {@code JsonCreator} constructors, and
 * property-based {@code JsonCreator} factories share this owner. The separate complete-string
 * {@code JsonValue} representation owns its value creator in its value codec. The interpreted path
 * allocates exactly one fixed-size argument array per object. Generated JIT readers consume the
 * same field metadata and executable but invoke it directly with typed locals.
 */
@Internal
public final class JsonCreatorInfo {
  // The Feature resolves each discovered executable through creatorHandle during analysis, and
  // runtime configurations retrieve the retained handles through the same cache.
  private static final ClassValueCache<ConcurrentMap<Executable, CreatorHandles>> NATIVE_CREATORS =
      ClassValueCache.newClassKeyCache(32);

  private final Class<?> ownerType;
  private final Executable executable;
  private final JsonCreatorFieldInfo[] fields;
  private final Object[] defaults;
  private final long[] hashes;
  private final MethodHandle invoker;
  private final GeneratedJsonCodec<?> generatedCodec;

  public JsonCreatorInfo(
      Class<?> ownerType,
      Executable executable,
      JsonCreatorFieldInfo[] fields,
      Object[] defaults,
      GeneratedJsonCodec<?> generatedCodec) {
    this.ownerType = ownerType;
    this.executable = executable;
    this.fields = fields;
    this.defaults = defaults;
    this.generatedCodec = generatedCodec;
    invoker = generatedCodec == null ? buildInvoker(executable) : null;
    hashes = new long[fields.length];
    for (int i = 0; i < fields.length; i++) {
      hashes[i] = fields[i].nameHash();
    }
  }

  public Executable executable() {
    return executable;
  }

  public JsonCreatorFieldInfo[] fields() {
    return fields;
  }

  public Object[] newArguments() {
    return Arrays.copyOf(defaults, defaults.length);
  }

  public int index(long hash) {
    // Creator arity is deliberately finite and normally small. A linear exact-hash table avoids a
    // second object graph and is allocation-free.
    for (int i = 0; i < hashes.length; i++) {
      if (hashes[i] == hash) {
        return i;
      }
    }
    return -1;
  }

  public void resolveTypes(JsonTypeResolver resolver) {
    for (JsonCreatorFieldInfo field : fields) {
      field.resolveType(resolver);
    }
  }

  public Object create(Object[] arguments) {
    if (generatedCodec != null) {
      try {
        return requireResult(generatedCodec.newInstance(arguments));
      } catch (Throwable cause) {
        if (cause instanceof Error) {
          throw (Error) cause;
        }
        throw new ForyJsonException("JSON creator failed for " + ownerType.getName(), cause);
      }
    }
    if (invoker != null) {
      return invoke(arguments);
    }
    try {
      Object value =
          executable instanceof Constructor
              ? ((Constructor<?>) executable).newInstance(arguments)
              : ((Method) executable).invoke(null, arguments);
      return requireResult(value);
    } catch (InstantiationException | IllegalAccessException e) {
      throw new ForyJsonException("Failed to invoke JSON creator for " + ownerType.getName(), e);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw new ForyJsonException("JSON creator failed for " + ownerType.getName(), cause);
    }
  }

  private Object invoke(Object[] arguments) {
    Object value;
    try {
      value = (Object) invoker.invokeExact(arguments);
    } catch (Throwable cause) {
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw new ForyJsonException("JSON creator failed for " + ownerType.getName(), cause);
    }
    return requireResult(value);
  }

  private Object requireResult(Object value) {
    if (value == null || value.getClass() != ownerType) {
      throw new ForyJsonException(
          "JSON creator must return an exact non-null " + ownerType.getName());
    }
    return value;
  }

  private static MethodHandle buildInvoker(Executable executable) {
    if (AndroidSupport.IS_ANDROID) {
      // Android has no supported trusted MethodHandle lookup. Creator shape validation guarantees
      // a public executable; accessibility is needed only when its declaring class is non-public.
      executable.setAccessible(true);
      return null;
    }
    return creatorHandle(executable);
  }

  /** Returns the array-argument invocation handle for one JSON creator. */
  @Internal
  public static MethodHandle creatorHandle(Executable executable) {
    if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      return nativeCreatorHandles(executable).arrayInvoker;
    }
    return arrayInvoker(creatorTarget(executable), executable.getParameterCount());
  }

  private static MethodHandle arrayInvoker(MethodHandle target, int parameterCount) {
    // The interpreted reader already owns one trusted fixed-size argument array. Spread that
    // exact array into the creator without a second carrier or per-call reflective access check.
    return target
        .asSpreader(Object[].class, parameterCount)
        .asType(MethodType.methodType(Object.class, Object[].class));
  }

  /** Returns the one-String-argument creator used by a JsonValue representation. */
  @Internal
  public static MethodHandle stringCreatorHandle(Executable executable) {
    if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      return nativeCreatorHandles(executable).stringInvoker;
    }
    return creatorTarget(executable).asType(MethodType.methodType(Object.class, String.class));
  }

  private static CreatorHandles nativeCreatorHandles(Executable executable) {
    ConcurrentMap<Executable, CreatorHandles> creators =
        NATIVE_CREATORS.get(executable.getDeclaringClass(), ConcurrentHashMap::new);
    return creators.computeIfAbsent(executable, JsonCreatorInfo::newCreatorHandles);
  }

  private static CreatorHandles newCreatorHandles(Executable executable) {
    MethodHandle target = creatorTarget(executable);
    MethodHandle stringInvoker =
        executable.getParameterCount() == 1 && executable.getParameterTypes()[0] == String.class
            ? target.asType(MethodType.methodType(Object.class, String.class))
            : null;
    return new CreatorHandles(arrayInvoker(target, executable.getParameterCount()), stringInvoker);
  }

  private static MethodHandle creatorTarget(Executable executable) {
    Class<?> declaringClass = executable.getDeclaringClass();
    try {
      // A target-class trusted lookup has full member access without requiring the application
      // module to export or open its model package.
      return executable instanceof Constructor
          ? _JDKAccess._trustedLookup(declaringClass)
              .unreflectConstructor((Constructor<?>) executable)
          : _JDKAccess._trustedLookup(declaringClass).unreflect((Method) executable);
    } catch (IllegalAccessException e) {
      throw new ForyJsonException("Cannot access JSON creator " + executable, e);
    }
  }

  private static final class CreatorHandles {
    private final MethodHandle arrayInvoker;
    private final MethodHandle stringInvoker;

    private CreatorHandles(MethodHandle arrayInvoker, MethodHandle stringInvoker) {
      this.arrayInvoker = arrayInvoker;
      this.stringInvoker = stringInvoker;
    }
  }
}

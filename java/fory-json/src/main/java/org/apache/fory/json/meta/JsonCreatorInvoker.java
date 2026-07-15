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
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.internal._JDKAccess;

/** Stable generated call shape for one property-based JSON creator. */
@Internal
public abstract class JsonCreatorInvoker {
  public abstract Object create(Object[] arguments);

  /** Resolves one selected inaccessible creator to an exact cached call shape. */
  public static JsonCreatorInvoker forExecutable(Class<?> ownerType, Executable executable) {
    if (AndroidSupport.IS_ANDROID) {
      return androidInvoker(ownerType, executable);
    }
    return new HandleInvoker(ownerType, creatorHandle(ownerType, executable));
  }

  private static JsonCreatorInvoker androidInvoker(Class<?> ownerType, Executable executable) {
    try {
      // API 26 ART's raw generic MethodHandle invoke allocates six adapter objects per creator;
      // its exact return adapters are invalid for constructors. Cache the executable instead and
      // reuse the reader-owned fixed argument array, which is allocation-neutral on ART.
      executable.setAccessible(true);
      return executable instanceof Constructor
          ? new AndroidConstructorInvoker(ownerType, (Constructor<?>) executable)
          : new AndroidFactoryInvoker(ownerType, (Method) executable);
    } catch (RuntimeException e) {
      throw new ForyJsonException("Cannot access JSON creator for " + ownerType.getName(), e);
    }
  }

  static MethodHandle creatorHandle(Class<?> ownerType, Executable executable) {
    try {
      MethodHandle target =
          executable instanceof Constructor
              ? _JDKAccess._trustedLookup(ownerType)
                  .unreflectConstructor((Constructor<?>) executable)
              : _JDKAccess._trustedLookup(ownerType).unreflect((Method) executable);
      return target
          .asSpreader(Object[].class, executable.getParameterCount())
          .asType(MethodType.methodType(Object.class, Object[].class));
    } catch (IllegalAccessException | RuntimeException e) {
      throw new ForyJsonException("Cannot access JSON creator for " + ownerType.getName(), e);
    }
  }

  private static final class HandleInvoker extends JsonCreatorInvoker {
    private final Class<?> ownerType;
    private final MethodHandle handle;

    private HandleInvoker(Class<?> ownerType, MethodHandle handle) {
      this.ownerType = ownerType;
      this.handle = handle;
    }

    @Override
    public Object create(Object[] arguments) {
      try {
        return (Object) handle.invokeExact(arguments);
      } catch (Throwable cause) {
        throw creatorFailure(ownerType, cause);
      }
    }
  }

  private static final class AndroidConstructorInvoker extends JsonCreatorInvoker {
    private final Class<?> ownerType;
    private final Constructor<?> constructor;

    private AndroidConstructorInvoker(Class<?> ownerType, Constructor<?> constructor) {
      this.ownerType = ownerType;
      this.constructor = constructor;
    }

    @Override
    public Object create(Object[] arguments) {
      try {
        return constructor.newInstance(arguments);
      } catch (Throwable cause) {
        throw creatorFailure(ownerType, cause);
      }
    }
  }

  private static final class AndroidFactoryInvoker extends JsonCreatorInvoker {
    private final Class<?> ownerType;
    private final Method factory;

    private AndroidFactoryInvoker(Class<?> ownerType, Method factory) {
      this.ownerType = ownerType;
      this.factory = factory;
    }

    @Override
    public Object create(Object[] arguments) {
      try {
        return factory.invoke(null, arguments);
      } catch (Throwable cause) {
        throw creatorFailure(ownerType, cause);
      }
    }
  }

  private static RuntimeException creatorFailure(Class<?> ownerType, Throwable cause) {
    if (cause instanceof InvocationTargetException
        && ((InvocationTargetException) cause).getTargetException() != null) {
      cause = ((InvocationTargetException) cause).getTargetException();
    }
    if (cause instanceof Error) {
      throw (Error) cause;
    }
    return new ForyJsonException("JSON creator failed for " + ownerType.getName(), cause);
  }
}

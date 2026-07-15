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
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.internal._JDKAccess;

/** Stable generated call shape for one JSON Any setter. */
@Internal
public abstract class JsonAnySetterInvoker {
  private static final MethodType SETTER_TYPE =
      MethodType.methodType(void.class, Object.class, String.class, Object.class);
  private static final MethodHandle INVOKER_HANDLE = invokerHandle();

  public abstract void set(Object target, String name, Object value);

  /** Returns an exact bound handle for the generated setter invocation. */
  public final MethodHandle exactHandle() {
    return INVOKER_HANDLE.bindTo(this);
  }

  /** Resolves one selected inaccessible Any setter to an exact cached call shape. */
  public static JsonAnySetterInvoker forMethod(Method method) {
    return new HandleInvoker(method, setterHandle(method));
  }

  private static MethodHandle setterHandle(Method method) {
    try {
      MethodHandle target;
      if (AndroidSupport.IS_ANDROID) {
        method.setAccessible(true);
        target = MethodHandles.lookup().unreflect(method);
      } else {
        target = _JDKAccess._trustedLookup(method.getDeclaringClass()).unreflect(method);
      }
      return target.asType(SETTER_TYPE);
    } catch (IllegalAccessException | RuntimeException e) {
      throw new ForyJsonException("Cannot access @JsonAnySetter " + method, e);
    }
  }

  private static MethodHandle invokerHandle() {
    try {
      return MethodHandles.lookup().findVirtual(JsonAnySetterInvoker.class, "set", SETTER_TYPE);
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static final class HandleInvoker extends JsonAnySetterInvoker {
    private final Method method;
    private final MethodHandle handle;

    private HandleInvoker(Method method, MethodHandle handle) {
      this.method = method;
      this.handle = handle;
    }

    @Override
    public void set(Object target, String name, Object value) {
      try {
        handle.invokeExact(target, name, value);
      } catch (Throwable cause) {
        throw setterFailure(method, cause);
      }
    }
  }

  private static RuntimeException setterFailure(Method method, Throwable cause) {
    if (cause instanceof Error) {
      throw (Error) cause;
    }
    return new ForyJsonException("Cannot invoke @JsonAnySetter " + method, cause);
  }
}

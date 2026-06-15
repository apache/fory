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

package org.apache.fory.platform.internal;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import org.apache.fory.platform.JdkVersion;

// CHECKSTYLE.OFF:TypeName
class _Lookup {
  // CHECKSTYLE.ON:TypeName
  static final Lookup IMPL_LOOKUP;
  static volatile MethodHandle CONSTRUCTOR_LOOKUP;
  static volatile boolean CONSTRUCTOR_LOOKUP_ERROR;

  // inspired by fastjson to get trusted lookup for create lambdas function to
  // avoid cost of reflection.
  static {
    Lookup trustedLookup = null;
    {
      try {
        Field implLookup = Lookup.class.getDeclaredField("IMPL_LOOKUP");
        long fieldOffset = _UnsafeUtils.UNSAFE.staticFieldOffset(implLookup);
        Object fieldBase = _UnsafeUtils.UNSAFE.staticFieldBase(implLookup);
        trustedLookup = (Lookup) _UnsafeUtils.UNSAFE.getObject(fieldBase, fieldOffset);
      } catch (Throwable ignored) {
        // ignored
      }
      if (trustedLookup == null) {
        trustedLookup = MethodHandles.lookup();
      }
      IMPL_LOOKUP = trustedLookup;
    }
  }

  // CHECKSTYLE.OFF:MethodName
  public static Lookup _trustedLookup(Class<?> objectClass) {
    // CHECKSTYLE.OFF:MethodName
    if (!CONSTRUCTOR_LOOKUP_ERROR) {
      try {
        int trusted = -1;
        MethodHandle constructor = CONSTRUCTOR_LOOKUP;
        if (JdkVersion.MAJOR_VERSION < 14) {
          if (constructor == null) {
            constructor =
                IMPL_LOOKUP.findConstructor(
                    Lookup.class, MethodType.methodType(void.class, Class.class, int.class));
            CONSTRUCTOR_LOOKUP = constructor;
          }
          int fullAccessMask = 31; // for IBM Open J9 JDK
          return (Lookup)
              constructor.invoke(objectClass, _JDKAccess.IS_OPEN_J9 ? fullAccessMask : trusted);
        } else {
          if (constructor == null) {
            constructor =
                IMPL_LOOKUP.findConstructor(
                    Lookup.class,
                    MethodType.methodType(void.class, Class.class, Class.class, int.class));
            CONSTRUCTOR_LOOKUP = constructor;
          }
          return (Lookup) constructor.invoke(objectClass, null, trusted);
        }
      } catch (Throwable ignored) {
        CONSTRUCTOR_LOOKUP_ERROR = true;
      }
    }
    if (JdkVersion.MAJOR_VERSION < 11) {
      Lookup lookup = getLookupByReflection(objectClass);
      if (lookup != null) {
        return lookup;
      }
    }
    return IMPL_LOOKUP.in(objectClass);
  }

  private static MethodHandles.Lookup getLookupByReflection(Class<?> cls) {
    try {
      Constructor<Lookup> constructor =
          MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, int.class);
      constructor.setAccessible(true);
      return constructor.newInstance(
          cls, -1 // Lookup.TRUSTED
          );
    } catch (Exception e) {
      return null;
    }
  }

  private static volatile Method PRIVATE_LOOKUP_IN = null;

  public static Lookup privateLookupIn(Class<?> targetClass, Lookup caller) {
    try {
      // This doesn't have side effect, it's ok to read and assign it in multi-threaded way.
      if (PRIVATE_LOOKUP_IN == null) {
        Method m =
            MethodHandles.class.getDeclaredMethod("privateLookupIn", Class.class, Lookup.class);
        m.setAccessible(true);
        PRIVATE_LOOKUP_IN = m;
      }
      return (Lookup) PRIVATE_LOOKUP_IN.invoke(null, targetClass, caller);
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }

  private static volatile Method DEFINE_CLASS = null;

  /**
   * Creates and links a class or interface from {@code bytes} with the same class loader and in the
   * same runtime package and {@linkplain java.security.ProtectionDomain protection domain} as this
   * lookup's {@linkplain Lookup#lookupClass() lookup class} as if calling {@link
   * ClassLoader#defineClass(String,byte[],int,int, ProtectionDomain) ClassLoader::defineClass}.
   * Note that classes in bytecode must be in same package as lookup class.
   */
  public static Class<?> defineClass(Lookup lookup, byte[] bytes) {
    try {
      // This doesn't have side effect, it's ok to read and assign it in multi-threaded way.
      if (DEFINE_CLASS == null) {
        Method m = Lookup.class.getDeclaredMethod("defineClass", byte[].class);
        m.setAccessible(true);
        DEFINE_CLASS = m;
      }
      return (Class<?>) DEFINE_CLASS.invoke(lookup, (Object) bytes);
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }
}

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

package org.apache.fory.serializer;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.apache.fory.collection.ClassValueCache;
import org.apache.fory.platform.JdkVersion;
import org.apache.fory.platform.internal._JDKAccess;
import org.apache.fory.util.ExceptionUtils;

final class SerializationHookLookup {
  private SerializationHookLookup() {}

  static MethodHandle readResolveHandle(Class<?> type, Method method)
      throws IllegalAccessException {
    return _JDKAccess._trustedLookup(type).unreflect(method);
  }

  private static final class Methods {
    private static final Object REFLECTION_FACTORY;
    private static final MethodHandle WRITE_OBJECT;
    private static final MethodHandle READ_OBJECT;
    private static final MethodHandle READ_OBJECT_NO_DATA;
    private static final MethodHandle DEFAULT_READ_OBJECT;
    private static final MethodHandle WRITE_REPLACE;
    private static final MethodHandle READ_RESOLVE;

    static {
      Object reflectionFactory = null;
      MethodHandle writeObject = null;
      MethodHandle readObject = null;
      MethodHandle readObjectNoData = null;
      MethodHandle defaultReadObject = null;
      MethodHandle writeReplace = null;
      MethodHandle readResolve = null;
      if (JdkVersion.MAJOR_VERSION < 25) {
        try {
          Class<?> factoryClass = Class.forName("sun.reflect.ReflectionFactory");
          MethodHandles.Lookup lookup = _JDKAccess._trustedLookup(factoryClass);
          MethodHandle getReflectionFactory =
              lookup.findStatic(
                  factoryClass, "getReflectionFactory", MethodType.methodType(factoryClass));
          reflectionFactory = getReflectionFactory.invoke();
          MethodType hookType = MethodType.methodType(MethodHandle.class, Class.class);
          writeObject = lookup.findVirtual(factoryClass, "writeObjectForSerialization", hookType);
          readObject = lookup.findVirtual(factoryClass, "readObjectForSerialization", hookType);
          readObjectNoData =
              lookup.findVirtual(factoryClass, "readObjectNoDataForSerialization", hookType);
          try {
            defaultReadObject =
                lookup.findVirtual(factoryClass, "defaultReadObjectForSerialization", hookType);
          } catch (NoSuchMethodException | IllegalAccessException e) {
            ExceptionUtils.ignore(e);
          }
          writeReplace = lookup.findVirtual(factoryClass, "writeReplaceForSerialization", hookType);
          readResolve = lookup.findVirtual(factoryClass, "readResolveForSerialization", hookType);
        } catch (Throwable e) {
          ExceptionUtils.ignore(e);
        }
      }
      REFLECTION_FACTORY = reflectionFactory;
      WRITE_OBJECT = writeObject;
      READ_OBJECT = readObject;
      READ_OBJECT_NO_DATA = readObjectNoData;
      DEFAULT_READ_OBJECT = defaultReadObject;
      WRITE_REPLACE = writeReplace;
      READ_RESOLVE = readResolve;
    }
  }

  private static MethodHandle getHandle(Class<?> type, MethodHandle factoryMethod) {
    if (Methods.REFLECTION_FACTORY == null || factoryMethod == null) {
      return null;
    }
    try {
      return (MethodHandle) factoryMethod.invoke(Methods.REFLECTION_FACTORY, type);
    } catch (Throwable e) {
      ExceptionUtils.ignore(e);
      return null;
    }
  }

  private static Method getMethod(Class<?> type, MethodHandle factoryMethod) {
    MethodHandle handle = getHandle(type, factoryMethod);
    return handle == null ? null : MethodHandles.reflectAs(Method.class, handle);
  }

  private static final ClassValueCache<DirectMethods> directMethodsCache =
      ClassValueCache.newClassKeyCache(32);

  private static DirectMethods directMethods(Class<?> type) {
    // JavaSerializer intentionally supports non-Serializable compatibility hooks. Serializable
    // stream hooks must keep ObjectStreamClass inheritance rules, especially for private
    // superclass writeReplace/readResolve methods.
    return directMethodsCache.get(type, () -> new DirectMethods(type));
  }

  private static final class DirectMethods {
    private final Method writeObject;
    private final Method readObject;
    private final Method readObjectNoData;
    private final Method writeReplace;
    private final Method readResolve;

    private DirectMethods(Class<?> type) {
      writeObject = getPrivateMethod(type, "writeObject", void.class, ObjectOutputStream.class);
      readObject = getPrivateMethod(type, "readObject", void.class, ObjectInputStream.class);
      readObjectNoData = getPrivateMethod(type, "readObjectNoData", void.class);
      writeReplace = getInheritableObjectMethod(type, "writeReplace");
      readResolve = getInheritableObjectMethod(type, "readResolve");
    }
  }

  private static Method getPrivateMethod(
      Class<?> type, String methodName, Class<?> returnType, Class<?>... parameterTypes) {
    try {
      Method method = type.getDeclaredMethod(methodName, parameterTypes);
      int modifiers = method.getModifiers();
      if (Modifier.isPrivate(modifiers)
          && !Modifier.isStatic(modifiers)
          && method.getReturnType() == returnType) {
        return method;
      }
    } catch (NoSuchMethodException | SecurityException e) {
      ExceptionUtils.ignore(e);
    }
    return null;
  }

  private static Method getInheritableObjectMethod(Class<?> type, String methodName) {
    Class<?> cls = type;
    while (cls != null) {
      try {
        Method method = cls.getDeclaredMethod(methodName);
        int modifiers = method.getModifiers();
        if (!Modifier.isStatic(modifiers)
            && method.getParameterTypes().length == 0
            && method.getReturnType() == Object.class
            && isInheritable(type, cls, modifiers)) {
          return method;
        }
        return null;
      } catch (NoSuchMethodException e) {
        ExceptionUtils.ignore(e);
      } catch (SecurityException e) {
        return null;
      }
      cls = cls.getSuperclass();
    }
    return null;
  }

  private static boolean isInheritable(Class<?> type, Class<?> declaringClass, int modifiers) {
    if (Modifier.isPrivate(modifiers)) {
      return type == declaringClass;
    }
    if (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)) {
      return true;
    }
    return type.getClassLoader() == declaringClass.getClassLoader()
        && packageName(type).equals(packageName(declaringClass));
  }

  private static String packageName(Class<?> type) {
    String className = type.getName();
    int packageEnd = className.lastIndexOf('.');
    return packageEnd < 0 ? "" : className.substring(0, packageEnd);
  }

  static Method getWriteObjectMethod(Class<?> type) {
    Method method = getMethod(type, Methods.WRITE_OBJECT);
    return method == null ? directMethods(type).writeObject : method;
  }

  static Method getReadObjectMethod(Class<?> type) {
    Method method = getMethod(type, Methods.READ_OBJECT);
    return method == null ? directMethods(type).readObject : method;
  }

  static Method getReadObjectNoDataMethod(Class<?> type) {
    Method method = getMethod(type, Methods.READ_OBJECT_NO_DATA);
    return method == null ? directMethods(type).readObjectNoData : method;
  }

  static MethodHandle getDefaultReadObjectHandle(Class<?> type) {
    return getHandle(type, Methods.DEFAULT_READ_OBJECT);
  }

  static Method getWriteReplaceMethod(Class<?> type) {
    Method method = getMethod(type, Methods.WRITE_REPLACE);
    return method == null ? directMethods(type).writeReplace : method;
  }

  static Method getReadResolveMethod(Class<?> type) {
    Method method = getMethod(type, Methods.READ_RESOLVE);
    return method == null ? directMethods(type).readResolve : method;
  }
}

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

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import org.apache.fory.collection.ClassValueCache;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.platform.JdkVersion;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.util.ExceptionUtils;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.function.ToByteFunction;
import org.apache.fory.util.function.ToCharFunction;
import org.apache.fory.util.function.ToFloatFunction;
import org.apache.fory.util.function.ToShortFunction;

/** JDK lookup, module, and lambda factory utils. */
// CHECKSTYLE.OFF:TypeName
public class _JDKAccess {
  // CHECKSTYLE.ON:TypeName
  public static final boolean IS_OPEN_J9;
  public static final boolean JDK_INTERNAL_FIELD_ACCESS;
  public static final boolean JDK_LANG_FIELD_ACCESS;
  public static final boolean JDK_COLLECTION_FIELD_ACCESS;
  public static final boolean JDK_CONCURRENT_FIELD_ACCESS;
  public static final boolean JDK_PROXY_FIELD_ACCESS;

  static {
    String jmvName = System.getProperty("java.vm.name", "");
    IS_OPEN_J9 = jmvName.contains("OpenJ9");
    if (AndroidSupport.IS_ANDROID) {
      JDK_INTERNAL_FIELD_ACCESS = false;
      JDK_LANG_FIELD_ACCESS = false;
      JDK_COLLECTION_FIELD_ACCESS = false;
      JDK_CONCURRENT_FIELD_ACCESS = false;
      JDK_PROXY_FIELD_ACCESS = false;
    } else if (JdkVersion.MAJOR_VERSION >= 25) {
      // JDK25+ zero-Unsafe mode requires java.base/java.lang.invoke to be opened to fory-core.
      // Missing that open is an invalid runtime configuration, not a fallback signal.
      JDK_INTERNAL_FIELD_ACCESS = true;
      JDK_LANG_FIELD_ACCESS = true;
      JDK_COLLECTION_FIELD_ACCESS = true;
      JDK_CONCURRENT_FIELD_ACCESS = true;
      JDK_PROXY_FIELD_ACCESS = true;
    } else {
      JDK_INTERNAL_FIELD_ACCESS = true;
      JDK_LANG_FIELD_ACCESS = true;
      JDK_COLLECTION_FIELD_ACCESS = true;
      JDK_CONCURRENT_FIELD_ACCESS = true;
      JDK_PROXY_FIELD_ACCESS = true;
    }
  }

  private static final ClassValueCache<Lookup> lookupCache = ClassValueCache.newClassKeyCache(32);

  // CHECKSTYLE.OFF:MethodName

  public static Lookup _trustedLookup(Class<?> objectClass) {
    // CHECKSTYLE.ON:MethodName
    if (GraalvmSupport.isGraalBuildTime()) {
      // Lookup will init `java.io.FilePermission`,which is not allowed at graalvm build time
      // as a reachable object.
      return _Lookup._trustedLookup(objectClass);
    }
    return lookupCache.get(objectClass, () -> _Lookup._trustedLookup(objectClass));
  }

  public static String jdk25AccessMessage() {
    return "JDK25 zero-Unsafe mode requires java.base/java.lang.invoke to be open to Fory. "
        + "Use --add-opens=java.base/java.lang.invoke=ALL-UNNAMED when Fory is on the "
        + "classpath, or --add-opens=java.base/java.lang.invoke=org.apache.fory.core when "
        + "Fory is on the module path.";
  }

  public static <T> T tryMakeFunction(
      Lookup lookup, MethodHandle handle, Class<T> functionInterface) {
    try {
      return makeFunction(lookup, handle, functionInterface);
    } catch (Throwable e) {
      ExceptionUtils.ignore(e);
      throw new IllegalStateException();
    }
  }

  private static final MethodType jdkFunctionMethodType =
      MethodType.methodType(Object.class, Object.class);

  @SuppressWarnings("unchecked")
  public static <T, R> Function<T, R> makeJDKFunction(Lookup lookup, MethodHandle handle) {
    return makeJDKFunction(lookup, handle, jdkFunctionMethodType);
  }

  @SuppressWarnings("unchecked")
  public static <T, R> Function<T, R> makeJDKFunction(
      Lookup lookup, MethodHandle handle, MethodType methodType) {
    try {
      CallSite callSite =
          LambdaMetafactory.metafactory(
              lookup,
              "apply",
              MethodType.methodType(Function.class),
              methodType,
              handle,
              boxedMethodType(handle.type()));
      return (Function<T, R>) callSite.getTarget().invokeExact();
    } catch (Throwable e) {
      throw ExceptionUtils.throwException(e);
    }
  }

  private static final MethodType jdkConsumerMethodType =
      MethodType.methodType(void.class, Object.class);

  @SuppressWarnings("unchecked")
  public static <T> Consumer<T> makeJDKConsumer(Lookup lookup, MethodHandle handle) {
    try {
      CallSite callSite =
          LambdaMetafactory.metafactory(
              lookup,
              "accept",
              MethodType.methodType(Consumer.class),
              jdkConsumerMethodType,
              handle,
              boxedMethodType(handle.type()));
      return (Consumer<T>) callSite.getTarget().invokeExact();
    } catch (Throwable e) {
      throw ExceptionUtils.throwException(e);
    }
  }

  private static final MethodType jdkBiConsumerMethodType =
      MethodType.methodType(void.class, Object.class, Object.class);

  @SuppressWarnings("unchecked")
  public static <T, U> BiConsumer<T, U> makeJDKBiConsumer(Lookup lookup, MethodHandle handle) {
    try {
      CallSite callSite =
          LambdaMetafactory.metafactory(
              lookup,
              "accept",
              MethodType.methodType(BiConsumer.class),
              jdkBiConsumerMethodType,
              handle,
              boxedMethodType(handle.type()));
      return (BiConsumer<T, U>) callSite.getTarget().invokeExact();
    } catch (Throwable e) {
      throw ExceptionUtils.throwException(e);
    }
  }

  private static MethodType boxedMethodType(MethodType methodType) {
    Class<?>[] paramTypes = new Class[methodType.parameterCount()];
    for (int i = 0; i < paramTypes.length; i++) {
      Class<?> t = methodType.parameterType(i);
      if (t.isPrimitive()) {
        t = TypeUtils.wrap(t);
      }
      paramTypes[i] = t;
    }
    return MethodType.methodType(methodType.returnType(), paramTypes);
  }

  @SuppressWarnings("unchecked")
  public static <T> T makeFunction(Lookup lookup, MethodHandle handle, Method methodToImpl) {
    MethodType instantiatedMethodType = boxedMethodType(handle.type());
    MethodType methodToImplType =
        MethodType.methodType(methodToImpl.getReturnType(), methodToImpl.getParameterTypes());
    try {
      // Faster than handle.invokeExact.
      CallSite callSite =
          LambdaMetafactory.metafactory(
              lookup,
              methodToImpl.getName(),
              MethodType.methodType(methodToImpl.getDeclaringClass()),
              methodToImplType,
              handle,
              instantiatedMethodType);
      return (T) callSite.getTarget().invokeExact();
    } catch (Throwable e) {
      throw ExceptionUtils.throwException(e);
    }
  }

  public static <T> T makeFunction(Lookup lookup, MethodHandle handle, Class<T> functionInterface) {
    String invokedName = "apply";
    try {
      Method method = null;
      Method[] methods = functionInterface.getMethods();
      for (Method interfaceMethod : methods) {
        if (interfaceMethod.getName().equals(invokedName)) {
          method = interfaceMethod;
          break;
        }
      }
      if (method == null) {
        Preconditions.checkArgument(methods.length == 1);
        method = methods[0];
        invokedName = method.getName();
      }
      MethodType interfaceType =
          MethodType.methodType(method.getReturnType(), method.getParameterTypes());
      // Faster than handle.invokeExact.
      CallSite callSite =
          LambdaMetafactory.metafactory(
              lookup,
              invokedName,
              MethodType.methodType(functionInterface),
              interfaceType,
              handle,
              interfaceType);
      // FIXME(chaokunyang) why use invokeExact will fail.
      return (T) callSite.getTarget().invoke();
    } catch (Throwable e) {
      throw ExceptionUtils.throwException(e);
    }
  }

  private static final Map<Class<?>, Tuple2<Class<?>, String>> methodMap = new HashMap<>();

  static {
    methodMap.put(boolean.class, Tuple2.of(Predicate.class, "test"));
    methodMap.put(byte.class, Tuple2.of(ToByteFunction.class, "applyAsByte"));
    methodMap.put(char.class, Tuple2.of(ToCharFunction.class, "applyAsChar"));
    methodMap.put(short.class, Tuple2.of(ToShortFunction.class, "applyAsShort"));
    methodMap.put(int.class, Tuple2.of(ToIntFunction.class, "applyAsInt"));
    methodMap.put(long.class, Tuple2.of(ToLongFunction.class, "applyAsLong"));
    methodMap.put(float.class, Tuple2.of(ToFloatFunction.class, "applyAsFloat"));
    methodMap.put(double.class, Tuple2.of(ToDoubleFunction.class, "applyAsDouble"));
  }

  public static Tuple2<Class<?>, String> getterMethodInfo(Class<?> type) {
    Tuple2<Class<?>, String> info = methodMap.get(type);
    if (info == null) {
      return Tuple2.of(Function.class, "apply");
    }
    return info;
  }

  public static Object makeGetterFunction(
      MethodHandles.Lookup lookup, MethodHandle handle, Class<?> returnType) {
    Tuple2<Class<?>, String> methodInfo = methodMap.get(returnType);
    MethodType factoryType;
    if (methodInfo == null) {
      methodInfo = Tuple2.of(Function.class, "apply");
      factoryType = jdkFunctionMethodType;
    } else {
      factoryType = MethodType.methodType(returnType, Object.class);
    }
    try {
      CallSite callSite =
          LambdaMetafactory.metafactory(
              lookup,
              methodInfo.f1,
              MethodType.methodType(methodInfo.f0),
              factoryType,
              handle,
              handle.type());
      // Can't use invokeExact, since we can't specify exact target type for return variable.
      return callSite.getTarget().invoke();
    } catch (ClassNotFoundException | NoClassDefFoundError e) {
      // ToByteFunction/ToBoolFunction/.. are not defined in jdk, if the classloader of
      // fory functions `ToByteFunction/..` isn't parent classloader of classloader for getter
      // represented by handle, then exception will be thrown.
      return makeGetterFunction(lookup, handle, Object.class);
    } catch (Throwable e) {
      throw ExceptionUtils.throwException(e);
    }
  }

  private static volatile Method getModuleMethod;
  private static volatile Method isExportedMethod;

  public static Object getModule(Class<?> cls) {
    Preconditions.checkArgument(JdkVersion.MAJOR_VERSION >= 9);
    if (getModuleMethod == null) {
      try {
        getModuleMethod = Class.class.getDeclaredMethod("getModule");
      } catch (NoSuchMethodException e) {
        throw new RuntimeException(e);
      }
    }
    try {
      return getModuleMethod.invoke(cls);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw ExceptionUtils.throwException(e);
    }
  }

  public static boolean isExported(Class<?> cls) {
    if (JdkVersion.MAJOR_VERSION < 9) {
      return true;
    }
    if (cls.isArray()) {
      return isExported(cls.getComponentType());
    }
    if (cls.isPrimitive()) {
      return true;
    }
    try {
      if (isExportedMethod == null) {
        Class<?> moduleClass = Class.forName("java.lang.Module");
        isExportedMethod = moduleClass.getDeclaredMethod("isExported", String.class);
      }
      Package pkg = cls.getPackage();
      return (Boolean) isExportedMethod.invoke(getModule(cls), pkg == null ? "" : pkg.getName());
    } catch (ClassNotFoundException | NoSuchMethodException e) {
      throw new RuntimeException(e);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw ExceptionUtils.throwException(e);
    }
  }

  // caller sensitive, must use MethodHandle to walk around the check.
  private static volatile MethodHandle addReadsHandle;

  public static Object addReads(Object thisModule, Object otherModule) {
    Preconditions.checkArgument(JdkVersion.MAJOR_VERSION >= 9);
    try {
      if (addReadsHandle == null) {
        Class<?> cls = Class.forName("java.lang.Module");
        MethodHandles.Lookup lookup = _JDKAccess._trustedLookup(cls);
        addReadsHandle = lookup.findVirtual(cls, "addReads", MethodType.methodType(cls, cls));
      }
      return addReadsHandle.invoke(thisModule, otherModule);
    } catch (Throwable e) {
      throw ExceptionUtils.throwException(e);
    }
  }

  public static Lookup privateLookupIn(Class<?> targetClass, Lookup caller) {
    return _Lookup.privateLookupIn(targetClass, caller);
  }
}

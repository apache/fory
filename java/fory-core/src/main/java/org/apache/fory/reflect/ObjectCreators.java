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

package org.apache.fory.reflect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import org.apache.fory.collection.ClassValueCache;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.exception.ForyException;
import org.apache.fory.memory.Platform;
import org.apache.fory.util.GraalvmSupport;
import org.apache.fory.util.record.RecordUtils;
import org.apache.fory.util.unsafe._JDKAccess;

/**
 * Factory class for creating and caching {@link ObjectCreator} instances.
 *
 * <p>This class provides a centralized way to obtain optimized object creators for different types.
 * It automatically selects the most appropriate creation strategy based on the target type and
 * runtime environment:
 *
 * <ul>
 *   <li><strong>Record types:</strong> Uses {@link RecordObjectCreator} with MethodHandle for
 *       parameterized constructor invocation
 *   <li><strong>Classes with no-arg constructors:</strong> Uses {@link
 *       DeclaredNoArgCtrObjectCreator} with MethodHandle for fast invocation
 *   <li><strong>Classes without accessible constructors:</strong> Uses {@link UnsafeObjectCreator}
 *       with platform-specific unsafe allocation
 *   <li><strong>GraalVM native image compatibility:</strong> Uses {@link
 *       ParentNoArgCtrObjectCreator} for constructor generate-based creation when needed
 * </ul>
 *
 * <p>All created ObjectCreator instances are cached using a soft reference cache to improve
 * performance on repeated requests for the same type.
 *
 * <p><strong>Thread Safety:</strong> This class and all returned ObjectCreator instances are
 * thread-safe and can be safely used across multiple threads concurrently.
 */
@SuppressWarnings("unchecked")
public class ObjectCreators {
  private static final ClassValueCache<ObjectCreator<?>> cache =
      ClassValueCache.newClassKeySoftCache(8);

  /**
   * Returns an optimized ObjectCreator for the given type.
   *
   * <p>This method automatically selects the most appropriate creation strategy based on the type
   * characteristics and caches the result for future use. The selection logic prioritizes
   * performance and platform compatibility.
   *
   * @param <T> the type for which to create an ObjectCreator
   * @param type the Class object representing the target type
   * @return a cached ObjectCreator instance optimized for the given type
   * @throws ForyException if the type cannot be instantiated (e.g., missing no-arg constructor in
   *     GraalVM native image)
   */
  public static <T> ObjectCreator<T> getObjectCreator(Class<T> type) {
    return (ObjectCreator<T>) cache.get(type, () -> creategetObjectCreator(type));
  }

  /**
   * Checks if a class is problematic for object creation and requires special handling in GraalVM.
   *
   * <p>A class is considered problematic if it lacks a public no-arg constructor and would
   * typically require ReflectionFactory or unsafe allocation for instantiation.
   *
   * @param type the class to check
   * @return true if the class is problematic for creation, false otherwise
   */
  public static boolean isProblematicForCreation(Class<?> type) {
    if (type.isInterface()
        || java.lang.reflect.Modifier.isAbstract(type.getModifiers())
        || type.isArray()
        || type.isEnum()
        || type.isAnonymousClass()
        || type.isLocalClass()
        || RecordUtils.isRecord(type)) {
      return false;
    }
    Constructor<?>[] constructors = type.getDeclaredConstructors();
    if (constructors.length == 0) {
      return true;
    }
    for (Constructor<?> constructor : constructors) {
      if (constructor.getParameterCount() == 0) {
        return false;
      }
    }
    return true;
  }

  private static <T> ObjectCreator<T> creategetObjectCreator(Class<T> type) {
    if (RecordUtils.isRecord(type)) {
      return new RecordObjectCreator<>(type);
    }
    Constructor<T> noArgConstructor = ReflectionUtils.getNoArgConstructor(type);
    if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      if (noArgConstructor != null) {
        return new DeclaredNoArgCtrObjectCreator<>(type);
      } else {
        return new UnsafeObjectCreator<>(type);
      }
    }
    if (noArgConstructor == null) {
      return new UnsafeObjectCreator<>(type);
    }
    return new DeclaredNoArgCtrObjectCreator<>(type);
  }

  public static final class UnsafeObjectCreator<T> extends ObjectCreator<T> {

    public UnsafeObjectCreator(Class<T> type) {
      super(type);
    }

    @Override
    public T newInstance() {
      return Platform.newInstance(type);
    }

    @Override
    public T newInstanceWithArguments(Object... arguments) {
      throw new UnsupportedOperationException();
    }
  }

  public static final class DeclaredNoArgCtrObjectCreator<T> extends ObjectCreator<T> {
    private final MethodHandle handle;

    public DeclaredNoArgCtrObjectCreator(Class<T> type) {
      super(type);
      handle = ReflectionUtils.getCtrHandle(type, true);
    }

    @Override
    public T newInstance() {
      try {
        return (T) handle.invoke();
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public T newInstanceWithArguments(Object... arguments) {
      throw new UnsupportedOperationException();
    }
  }

  public static final class RecordObjectCreator<T> extends ObjectCreator<T> {
    private final MethodHandle handle;
    private final Constructor<?> constructor;

    public RecordObjectCreator(Class<T> type) {
      super(type);
      Tuple2<Constructor, MethodHandle> tuple2 = RecordUtils.getRecordConstructor(type);
      constructor = tuple2.f0;
      handle = tuple2.f1;
      if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE && Platform.JAVA_VERSION >= 25) {
        try {
          constructor.setAccessible(true);
        } catch (Throwable t) {
          throw new ForyException(
              "Failed to create instance, please provide a public constructor for " + type, t);
        }
      }
    }

    @Override
    public T newInstance() {
      throw new UnsupportedOperationException();
    }

    @Override
    public T newInstanceWithArguments(Object... arguments) {
      try {
        // compile-time constant is eligible for dead code elimination.
        if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE && Platform.JAVA_VERSION >= 25) {
          // GraalVM 25+ path: workaround for https://github.com/oracle/graal/issues/12106
          return (T) constructor.newInstance(arguments);
        } else {
          // Regular path: use method handle
          return (T) handle.invokeWithArguments(arguments);
        }
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }
  }

  public static final class ParentNoArgCtrObjectCreator<T> extends ObjectCreator<T> {
    private static volatile Object reflectionFactory;
    private static volatile MethodHandle newConstructorForSerializationMethod;

    private final Constructor<T> constructor;

    public ParentNoArgCtrObjectCreator(Class<T> type) {
      super(type);
      this.constructor = createSerializationConstructor(type);
    }

    private static <T> Constructor<T> createSerializationConstructor(Class<T> type) {
      try {
        // Get ReflectionFactory instance
        if (reflectionFactory == null) {
          Class<?> reflectionFactoryClass;
          if (Platform.JAVA_VERSION >= 9) {
            reflectionFactoryClass = Class.forName("jdk.internal.reflect.ReflectionFactory");
          } else {
            reflectionFactoryClass = Class.forName("sun.reflect.ReflectionFactory");
          }
          Lookup lookup = _JDKAccess._trustedLookup(reflectionFactoryClass);
          MethodHandle handle =
              lookup.findStatic(
                  reflectionFactoryClass,
                  "getReflectionFactory",
                  MethodType.methodType(reflectionFactoryClass));
          reflectionFactory = handle.invoke();
          newConstructorForSerializationMethod =
              lookup.findVirtual(
                  reflectionFactoryClass,
                  "newConstructorForSerialization",
                  MethodType.methodType(Constructor.class, Class.class, Constructor.class));
        }
        // Find a public no-arg constructor in parent classes that we can use as a template
        Constructor<?> parentConstructor = findPublicNoArgConstructor(type);
        if (parentConstructor == null) {
          // Use Object's constructor as fallback
          parentConstructor = Object.class.getDeclaredConstructor();
        } else {
          try {
            parentConstructor.newInstance();
          } catch (Throwable ignored) {
            parentConstructor = Object.class.getDeclaredConstructor();
          }
        }
        // Create serialization constructor using ReflectionFactory
        return (Constructor<T>)
            newConstructorForSerializationMethod.invoke(reflectionFactory, type, parentConstructor);
      } catch (Throwable e) {
        throw new ForyException(
            "Failed to create instance, please provide a no-arg constructor for " + type, e);
      }
    }

    private static Constructor<?> findPublicNoArgConstructor(Class<?> type) {
      Class<?> current = type.getSuperclass();
      while (current != null && current != Object.class) {
        try {
          Constructor<?> constructor = current.getDeclaredConstructor();
          if (constructor.getModifiers() == java.lang.reflect.Modifier.PUBLIC) {
            return constructor;
          }
        } catch (NoSuchMethodException ignored) {
          // Continue searching
        }
        current = current.getSuperclass();
      }
      return null;
    }

    @Override
    public T newInstance() {
      try {
        return constructor.newInstance();
      } catch (Exception e) {
        throw new ForyException(
            "Failed to create instance, please provide a no-arg constructor for " + type, e);
      }
    }

    @Override
    public T newInstanceWithArguments(Object... arguments) {
      throw new UnsupportedOperationException();
    }
  }
}

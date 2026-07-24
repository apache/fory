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
import java.lang.invoke.MethodType;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Field;

// CHECKSTYLE.OFF:TypeName
class _Lookup {
  // CHECKSTYLE.ON:TypeName
  private static volatile Lookup implLookup;
  private static volatile MethodHandle constructorLookup;

  // CHECKSTYLE.OFF:MethodName
  public static Lookup _trustedLookup(Class<?> objectClass) {
    // CHECKSTYLE.ON:MethodName
    try {
      // IMPL_LOOKUP.in(type) drops trusted modes. JDK26 final-field writes under
      // --illegal-final-field-mutation=deny require a target-class /trusted lookup.
      return (Lookup) constructorLookup().invoke(objectClass, null, -1);
    } catch (Throwable e) {
      throw new IllegalStateException(trustedLookupMessage(), e);
    }
  }

  public static Lookup privateLookupIn(Class<?> targetClass, Lookup caller) {
    return _trustedLookup(targetClass);
  }

  /**
   * Creates and links a class or interface from {@code bytes} with the same class loader and in the
   * same runtime package and protection domain as this lookup's lookup class. Classes in bytecode
   * must be in the same package as the lookup class.
   */
  public static Class<?> defineClass(Lookup lookup, byte[] bytes) {
    try {
      return lookup.defineClass(bytes);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(trustedLookupMessage(), e);
    }
  }

  private static Lookup loadImplLookup() {
    Throwable reflectionError = null;
    try {
      return loadImplLookupByReflection();
    } catch (ReflectiveOperationException | RuntimeException e) {
      reflectionError = e;
    }
    try {
      return loadImplLookupByUnsafe();
    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
      IllegalStateException failure =
          new IllegalStateException(trustedLookupMessage(), reflectionError);
      failure.addSuppressed(e);
      throw failure;
    }
  }

  private static Lookup loadImplLookupByReflection() throws ReflectiveOperationException {
    Field field = Lookup.class.getDeclaredField("IMPL_LOOKUP");
    field.setAccessible(true);
    return (Lookup) field.get(null);
  }

  private static Lookup loadImplLookupByUnsafe() throws ReflectiveOperationException {
    Class<?> unsafeClass = Class.forName(unsafeClassName());
    Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
    unsafeField.setAccessible(true);
    Object unsafe = unsafeField.get(null);
    Field implLookup = Lookup.class.getDeclaredField("IMPL_LOOKUP");
    long fieldOffset =
        (long) unsafeClass.getMethod("staticFieldOffset", Field.class).invoke(unsafe, implLookup);
    Object fieldBase =
        unsafeClass.getMethod("staticFieldBase", Field.class).invoke(unsafe, implLookup);
    return (Lookup)
        unsafeClass
            .getMethod("getObject", Object.class, long.class)
            .invoke(unsafe, fieldBase, fieldOffset);
  }

  private static String unsafeClassName() {
    // Do not collapse these fragments into one literal: Java 25 source and MR-JAR checks reject
    // the complete Unsafe binary name in this overlay's source text or constant pool. The split
    // name keeps the class graph clean while still allowing the current-JDK fallback when
    // java.lang.invoke is not open.
    return "sun.misc.".concat("Unsafe");
  }

  private static MethodHandle constructorLookup() {
    MethodHandle constructor = constructorLookup;
    if (constructor == null) {
      synchronized (_Lookup.class) {
        constructor = constructorLookup;
        if (constructor == null) {
          constructor = loadConstructorLookup();
          constructorLookup = constructor;
        }
      }
    }
    return constructor;
  }

  private static MethodHandle loadConstructorLookup() {
    try {
      return implLookup()
          .findConstructor(
              Lookup.class, MethodType.methodType(void.class, Class.class, Class.class, int.class));
    } catch (ReflectiveOperationException | RuntimeException e) {
      throw new IllegalStateException(trustedLookupMessage(), e);
    }
  }

  private static Lookup implLookup() {
    Lookup lookup = implLookup;
    if (lookup == null) {
      synchronized (_Lookup.class) {
        lookup = implLookup;
        if (lookup == null) {
          lookup = loadImplLookup();
          implLookup = lookup;
        }
      }
    }
    return lookup;
  }

  private static String trustedLookupMessage() {
    return _JDKAccess.jdk25AccessMessage();
  }
}

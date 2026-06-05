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

package org.apache.fory.builder;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.apache.fory.annotation.Internal;
import org.apache.fory.platform.JdkVersion;

/** Internal bridge for JDK8-24 generated code that still uses Unsafe fast paths. */
@Internal
public final class UnsafeCodegenSupport {
  private static final Object UNSAFE;
  private static final Method OBJECT_FIELD_OFFSET;

  static {
    if (JdkVersion.MAJOR_VERSION >= 25) {
      UNSAFE = null;
      OBJECT_FIELD_OFFSET = null;
    } else {
      try {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        UNSAFE = unsafeField.get(null);
        OBJECT_FIELD_OFFSET = unsafeClass.getMethod("objectFieldOffset", Field.class);
      } catch (ReflectiveOperationException e) {
        throw new UnsupportedOperationException("Unsafe is not supported in this platform.", e);
      }
    }
  }

  private UnsafeCodegenSupport() {}

  public static Object unsafe() {
    checkUnsafeSupported();
    return UNSAFE;
  }

  public static long objectFieldOffset(Field field) {
    checkUnsafeSupported();
    try {
      return (long) OBJECT_FIELD_OFFSET.invoke(UNSAFE, field);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException("Unsafe objectFieldOffset is not accessible.", e);
    } catch (InvocationTargetException e) {
      throw new RuntimeException(e.getCause());
    }
  }

  static String unsafeTypeName() {
    checkUnsafeSupported();
    return "sun.misc.Unsafe";
  }

  public static String unsafeInitCode() {
    checkUnsafeSupported();
    return "((sun.misc.Unsafe) " + UnsafeCodegenSupport.class.getName() + ".unsafe())";
  }

  private static void checkUnsafeSupported() {
    if (JdkVersion.MAJOR_VERSION >= 25) {
      throw new UnsupportedOperationException("Generated Unsafe access is unsupported on JDK25+");
    }
  }
}

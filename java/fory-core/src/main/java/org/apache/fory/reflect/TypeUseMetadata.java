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

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.apache.fory.annotation.Internal;
import org.apache.fory.annotation.Nullable;
import org.apache.fory.util.record.RecordComponent;

/** Platform-selected access to Java type-use metadata. */
@Internal
public final class TypeUseMetadata {
  private static final Support SUPPORT = loadSupport();

  private TypeUseMetadata() {}

  public static <T> TypeRef<T> typeRef(Object typeUse) {
    if (typeUse == null) {
      return null;
    }
    @SuppressWarnings("unchecked")
    TypeRef<T> typeRef = (TypeRef<T>) SUPPORT.typeRef(typeUse);
    return typeRef;
  }

  public static Object fieldTypeUse(Field field) {
    return SUPPORT.fieldTypeUse(field);
  }

  public static Object methodReturnTypeUse(Method method) {
    return SUPPORT.methodReturnTypeUse(method);
  }

  public static Object[] methodParameterTypeUses(Method method) {
    return SUPPORT.methodParameterTypeUses(method);
  }

  public static Object recordComponentTypeUse(RecordComponent component) {
    return SUPPORT.recordComponentTypeUse(component);
  }

  public static Annotation typeUseAnnotation(Object typeUse, String name) {
    return SUPPORT.typeUseAnnotation(typeUse, name);
  }

  public static <A extends Annotation> A typeUseAnnotation(
      Object typeUse, Class<A> annotationClass) {
    return SUPPORT.typeUseAnnotation(typeUse, annotationClass);
  }

  public static boolean isNullable(Object typeUse) {
    return typeUse != null && typeUseAnnotation(typeUse, Nullable.class) != null;
  }

  private static Support loadSupport() {
    // ART can load JvmTypeUseMetadata while deferring linkage of missing type-use methods until
    // their first invocation. Probe the API itself before selecting the implementation so Android
    // API 26 never reaches those deferred references.
    try {
      Field.class.getMethod("getAnnotatedType");
    } catch (NoSuchMethodException | LinkageError e) {
      return NoTypeUseSupport.INSTANCE;
    }
    try {
      String className = TypeUseMetadata.class.getPackage().getName() + ".Jvm" + "TypeUseMetadata";
      Class<?> cls = Class.forName(className, true, TypeUseMetadata.class.getClassLoader());
      return (Support) cls.getDeclaredConstructor().newInstance();
    } catch (ClassNotFoundException | LinkageError e) {
      return NoTypeUseSupport.INSTANCE;
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  interface Support {
    TypeRef<?> typeRef(Object typeUse);

    Object fieldTypeUse(Field field);

    Object methodReturnTypeUse(Method method);

    Object[] methodParameterTypeUses(Method method);

    Object recordComponentTypeUse(RecordComponent component);

    Annotation typeUseAnnotation(Object typeUse, String name);

    <A extends Annotation> A typeUseAnnotation(Object typeUse, Class<A> annotationClass);
  }

  private enum NoTypeUseSupport implements Support {
    INSTANCE;

    @Override
    public TypeRef<?> typeRef(Object typeUse) {
      throw new UnsupportedOperationException(
          "Type-use metadata is not available on this platform");
    }

    @Override
    public Object fieldTypeUse(Field field) {
      return null;
    }

    @Override
    public Object methodReturnTypeUse(Method method) {
      return null;
    }

    @Override
    public Object[] methodParameterTypeUses(Method method) {
      return null;
    }

    @Override
    public Object recordComponentTypeUse(RecordComponent component) {
      return null;
    }

    @Override
    public Annotation typeUseAnnotation(Object typeUse, String name) {
      return null;
    }

    @Override
    public <A extends Annotation> A typeUseAnnotation(Object typeUse, Class<A> annotationClass) {
      return null;
    }
  }
}

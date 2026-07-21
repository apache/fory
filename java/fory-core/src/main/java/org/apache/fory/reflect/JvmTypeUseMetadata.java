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
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.fory.annotation.ArrayType;
import org.apache.fory.annotation.Nullable;
import org.apache.fory.annotation.Ref;
import org.apache.fory.meta.TypeExtMeta;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.TypeAnnotationUtils;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.type.Types;
import org.apache.fory.util.record.RecordComponent;

/** JVM implementation for Java type-use metadata APIs that are absent from Android. */
final class JvmTypeUseMetadata implements TypeUseMetadata.Support {
  JvmTypeUseMetadata() {}

  @Override
  public TypeRef<?> typeRef(Object typeUse) {
    return typeRef((AnnotatedType) typeUse, true, false, false);
  }

  private static TypeRef<?> typeRef(
      AnnotatedType annotatedType,
      boolean includeRefMeta,
      boolean defaultNullable,
      boolean includeTypeAnnotation) {
    if (annotatedType == null) {
      return null;
    }
    Type type = annotatedType.getType();
    List<TypeRef<?>> typeArguments = null;
    TypeRef<?> componentType = null;
    if (annotatedType instanceof AnnotatedParameterizedType) {
      AnnotatedType[] annotatedArgs =
          ((AnnotatedParameterizedType) annotatedType).getAnnotatedActualTypeArguments();
      Type[] args = ((ParameterizedType) type).getActualTypeArguments();
      List<TypeRef<?>> argRefs = new ArrayList<>(args.length);
      boolean hasChildMeta = false;
      for (int i = 0; i < args.length; i++) {
        AnnotatedType annotatedArg = i < annotatedArgs.length ? annotatedArgs[i] : null;
        Class<?> argumentRawType =
            annotatedArg == null
                ? TypeUtils.getRawType(args[i])
                : TypeUtils.getRawType(annotatedArg.getType());
        TypeRef<?> argRef =
            annotatedArg != null
                ? typeRef(annotatedArg, true, !argumentRawType.isPrimitive(), true)
                : TypeRef.of(args[i]);
        if (argRef != null && argRef.hasTypeExtMeta()) {
          hasChildMeta = true;
        }
        argRefs.add(argRef);
      }
      if (hasChildMeta) {
        typeArguments = Collections.unmodifiableList(argRefs);
      }
    } else if (annotatedType instanceof AnnotatedArrayType) {
      AnnotatedType annotatedComponent =
          ((AnnotatedArrayType) annotatedType).getAnnotatedGenericComponentType();
      Class<?> componentRawType = TypeUtils.getRawType(annotatedComponent.getType());
      TypeRef<?> component =
          typeRef(
              annotatedComponent,
              !componentRawType.isPrimitive(),
              !componentRawType.isPrimitive(),
              true);
      if (component != null && component.hasTypeExtMeta()) {
        componentType = component;
      }
    }
    TypeExtMeta meta = null;
    if (includeRefMeta) {
      Annotation typeAnnotation = includeTypeAnnotation ? getTypeAnnotation(annotatedType) : null;
      boolean nullable = annotatedType.isAnnotationPresent(Nullable.class);
      Ref ref = annotatedType.getAnnotation(Ref.class);
      int arrayTypeId = arrayTypeId(annotatedType);
      if (arrayTypeId != Types.UNKNOWN) {
        meta =
            TypeExtMeta.of(arrayTypeId, nullable || defaultNullable, ref != null && ref.enable());
      } else if (typeAnnotation != null) {
        int typeId = TypeAnnotationUtils.getTypeId(typeAnnotation, TypeUtils.getRawType(type));
        meta = TypeExtMeta.of(typeId, nullable || defaultNullable, ref != null && ref.enable());
      } else if (ref != null || nullable) {
        meta =
            TypeExtMeta.of(Types.UNKNOWN, nullable || defaultNullable, ref != null && ref.enable());
      }
    }
    return TypeRef.of(type, meta, typeArguments, componentType);
  }

  private static int arrayTypeId(AnnotatedType annotatedType) {
    if (!hasArrayType(annotatedType)) {
      return Types.UNKNOWN;
    }
    Class<?> rawType = TypeUtils.getRawType(annotatedType.getType());
    if (!rawType.isArray() || !rawType.getComponentType().isPrimitive()) {
      return Types.UNKNOWN;
    }
    return TypeAnnotationUtils.getArrayTypeIdFromElementType(
        TypeRef.of(rawType.getComponentType()));
  }

  @Override
  public Object fieldTypeUse(Field field) {
    return field.getAnnotatedType();
  }

  @Override
  public Object methodReturnTypeUse(Method method) {
    return method.getAnnotatedReturnType();
  }

  @Override
  public Object[] methodParameterTypeUses(Method method) {
    return method.getAnnotatedParameterTypes();
  }

  @Override
  public Object recordComponentTypeUse(RecordComponent component) {
    return component.getTypeUseMetadata();
  }

  @Override
  public Annotation typeUseAnnotation(Object typeUse, String name) {
    return getTypeUseAnnotation((AnnotatedType) typeUse, name);
  }

  @Override
  public <A extends Annotation> A typeUseAnnotation(Object typeUse, Class<A> annotationClass) {
    return ((AnnotatedType) typeUse).getAnnotation(annotationClass);
  }

  private static Annotation getTypeAnnotation(AnnotatedType annotatedType) {
    Annotation typeAnnotation =
        TypeAnnotationUtils.getTypeAnnotation(annotatedType.getAnnotations());
    if (typeAnnotation != null || !(annotatedType instanceof AnnotatedArrayType)) {
      return typeAnnotation;
    }
    AnnotatedType annotatedComponent =
        ((AnnotatedArrayType) annotatedType).getAnnotatedGenericComponentType();
    Class<?> componentRawType = TypeUtils.getRawType(annotatedComponent.getType());
    if (!componentRawType.isPrimitive()) {
      return null;
    }
    return TypeAnnotationUtils.getTypeAnnotation(annotatedComponent.getAnnotations());
  }

  private static Annotation getTypeUseAnnotation(AnnotatedType annotatedType, String name) {
    if (annotatedType == null) {
      return null;
    }
    Annotation typeAnnotation = Descriptor.getAnnotation(annotatedType.getAnnotations(), name);
    if (typeAnnotation != null || !(annotatedType instanceof AnnotatedArrayType)) {
      return typeAnnotation;
    }
    AnnotatedType annotatedComponent =
        ((AnnotatedArrayType) annotatedType).getAnnotatedGenericComponentType();
    Class<?> componentRawType = TypeUtils.getRawType(annotatedComponent.getType());
    if (!componentRawType.isPrimitive()) {
      return null;
    }
    return Descriptor.getAnnotation(annotatedComponent.getAnnotations(), name);
  }

  private static boolean hasArrayType(AnnotatedType annotatedType) {
    if (annotatedType.getAnnotation(ArrayType.class) != null) {
      return true;
    }
    if (!(annotatedType instanceof AnnotatedArrayType)) {
      return false;
    }
    AnnotatedType annotatedComponent =
        ((AnnotatedArrayType) annotatedType).getAnnotatedGenericComponentType();
    Class<?> componentRawType = TypeUtils.getRawType(annotatedComponent.getType());
    return componentRawType.isPrimitive()
        && annotatedComponent.getAnnotation(ArrayType.class) != null;
  }
}

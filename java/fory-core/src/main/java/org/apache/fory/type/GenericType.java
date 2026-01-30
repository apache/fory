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

package org.apache.fory.type;

import static org.apache.fory.type.TypeUtils.getRawType;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.Serializer;

/**
 * GenericType for building java generics as a tree and binding with fory serializers. Note
 * GenericType for specific types such as Object.class can't be singleton, because GenericType has
 * some mutable fields
 */
// TODO(chaokunyang) refine generics which can be inspired by spring ResolvableType.
@SuppressWarnings("rawtypes")
public class GenericType {
  private static final Predicate<Type> defaultFinalPredicate =
      type -> {
        if (type.getClass() == Class.class) {
          return ReflectionUtils.isMonomorphic(((Class<?>) type));
        } else {
          return ReflectionUtils.isMonomorphic((getRawType(type)));
        }
      };

  final TypeRef<?> typeRef;
  final Class<?> cls;
  final GenericType[] typeParameters;
  final int typeParametersCount;
  final GenericType typeParameter0;
  final GenericType typeParameter1;
  final boolean hasGenericParameters;
  final boolean isMonomorphic;
  // Used to cache serializer for final class to avoid hash lookup for serializer.
  Serializer<?> serializer;
  private Boolean trackingRef;
  private Boolean trackingRefOverride;

  public GenericType(TypeRef<?> typeRef, boolean isMonomorphic, GenericType... typeParameters) {
    this.typeRef = typeRef;
    this.cls = getRawType(typeRef);
    this.typeParameters = typeParameters;
    typeParametersCount = typeParameters.length;
    hasGenericParameters = typeParameters.length > 0;
    this.isMonomorphic = isMonomorphic;
    if (typeParameters.length > 0) {
      typeParameter0 = typeParameters[0];
    } else {
      typeParameter0 = null;
    }
    if (typeParameters.length > 1) {
      typeParameter1 = typeParameters[1];
    } else {
      typeParameter1 = null;
    }
  }

  public static GenericType build(TypeRef<?> type) {
    return build(type.getType());
  }

  public static GenericType build(Type type) {
    return build(type, defaultFinalPredicate);
  }

  /**
   * Build generics based on an {@code context} which capture generic type information.
   *
   * <pre>{@code
   * class A<T> {
   *   List<String> f1;
   *   List<T> f2;
   *   List<T>[] f3;
   *   T[] f4;
   *   SomeClass<T> f5;
   *   Map<T, List<SomeClass<T>>> f6;
   *   Map f7;
   * }
   *
   * class B extends A<Long> {}
   * }</pre>
   */
  public static GenericType build(TypeRef<?> context, Type type) {
    return build(context, type, defaultFinalPredicate);
  }

  public static GenericType build(Class<?> context, Type type) {
    return build(context, type, defaultFinalPredicate);
  }

  public static GenericType build(Class<?> context, Type type, Predicate<Type> finalPredicate) {
    return build(TypeRef.of(context), type, finalPredicate);
  }

  public static GenericType build(TypeRef<?> context, Type type, Predicate<Type> finalPredicate) {
    return build(context.resolveType(type), finalPredicate);
  }

  public static GenericType build(Type type, Predicate<Type> finalPredicate) {
    return build(TypeRef.of(type), finalPredicate);
  }

  public static GenericType build(TypeRef<?> typeRef, Predicate<Type> finalPredicate) {
    Type type = typeRef.getType();
    if (type instanceof ParameterizedType) {
      // List<String>, List<T>, Map<String, List<String>>, SomeClass<T>
      Type[] actualTypeArguments = ((ParameterizedType) type).getActualTypeArguments();
      List<GenericType> list = new ArrayList<>();
      for (Type t : actualTypeArguments) {
        GenericType build = GenericType.build(t, finalPredicate);
        list.add(build);
      }
      GenericType[] genericTypes = list.toArray(new GenericType[0]);
      return new GenericType(typeRef, finalPredicate.test(type), genericTypes);
    } else if (type instanceof GenericArrayType) { // List<String>[] or T[]
      Type componentType = ((GenericArrayType) type).getGenericComponentType();
      return new GenericType(typeRef, finalPredicate.test(type), build(componentType));
    } else if (type instanceof TypeVariable) { // T
      TypeVariable typeVariable = (TypeVariable) type;
      Type typeVariableBound =
          typeVariable.getBounds()[0]; // Bound 0 are a class, other bounds are interface.
      return new GenericType(TypeRef.of(typeVariableBound), finalPredicate.test(type));
    } else if (type instanceof WildcardType) {
      // WildcardType: `T extends Number`, not a type, just an expression.
      // `? extends java.util.Collection<? extends java.util.Collection<java.lang.Integer>>`
      Type upperBound = ((WildcardType) type).getUpperBounds()[0];
      if (upperBound instanceof ParameterizedType) {
        return build(upperBound);
      } else {
        return new GenericType(TypeRef.of(upperBound), finalPredicate.test(type));
      }
    } else {
      // Class type: String, Integer
      return new GenericType(typeRef, finalPredicate.test(type));
    }
  }

  public TypeRef<?> getTypeRef() {
    return typeRef;
  }

  public Type getType() {
    return typeRef.getType();
  }

  public Class<?> getCls() {
    return cls;
  }

  public GenericType[] getTypeParameters() {
    return typeParameters;
  }

  public int getTypeParametersCount() {
    return typeParametersCount;
  }

  public GenericType getTypeParameter0() {
    return typeParameter0;
  }

  public GenericType getTypeParameter1() {
    return typeParameter1;
  }

  public void setSerializer(Serializer<?> serializer) {
    this.serializer = serializer;
  }

  public Serializer getSerializer(TypeResolver classResolver) {
    Serializer<?> serializer = this.serializer;
    if (serializer == null) {
      serializer = classResolver.getSerializer(typeRef);
      this.serializer = serializer;
    }
    return serializer;
  }

  public Serializer<?> getSerializer() {
    return serializer;
  }

  public boolean isMonomorphic() {
    return isMonomorphic;
  }

  public boolean trackingRef(TypeResolver classResolver) {
    Boolean trackingRefOverride = this.trackingRefOverride;
    if (trackingRefOverride != null) {
      return trackingRefOverride;
    }
    Boolean trackingRef = this.trackingRef;
    if (trackingRef == null) {
      trackingRef = this.trackingRef = classResolver.needToWriteRef(typeRef);
    }
    return trackingRef;
  }

  public void setTrackingRefOverride(Boolean trackingRefOverride) {
    this.trackingRefOverride = trackingRefOverride;
    if (trackingRefOverride != null) {
      this.trackingRef = trackingRefOverride;
    }
  }

  public boolean hasGenericParameters() {
    return hasGenericParameters;
  }

  @Override
  public String toString() {
    return "GenericType{" + typeRef.toString() + '}';
  }
}

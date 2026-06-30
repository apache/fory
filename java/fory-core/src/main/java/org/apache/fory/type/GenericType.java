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
import org.apache.fory.collection.Tuple2;
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
    return build(type, defaultFinalPredicate);
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
    Class<?> rawType = getRawType(typeRef);
    if (TypeUtils.isMap(rawType)) {
      return buildMap(typeRef, finalPredicate);
    }
    if (TypeUtils.isCollection(rawType)) {
      return buildCollection(typeRef, finalPredicate);
    }
    if (typeRef.hasExplicitTypeArguments()) {
      List<TypeRef<?>> explicitTypeArguments = typeRef.getTypeArguments();
      List<GenericType> list = new ArrayList<>(explicitTypeArguments.size());
      for (TypeRef<?> explicitTypeArgument : explicitTypeArguments) {
        list.add(GenericType.build(explicitTypeArgument, finalPredicate));
      }
      GenericType[] genericTypes = list.toArray(new GenericType[0]);
      return new GenericType(typeRef, finalPredicate.test(type), genericTypes);
    }
    if (typeRef.isArray()) {
      TypeRef<?> explicitComponentType = typeRef.getComponentType();
      return new GenericType(
          typeRef,
          finalPredicate.test(type),
          GenericType.build(explicitComponentType, finalPredicate));
    }
    if (type instanceof ParameterizedType) {
      // List<String>, List<T>, Map<String, List<String>>, SomeClass<T>
      Type[] actualTypeArguments = ((ParameterizedType) type).getActualTypeArguments();
      List<GenericType> list = new ArrayList<>();
      for (int i = 0; i < actualTypeArguments.length; i++) {
        list.add(GenericType.build(actualTypeArguments[i], finalPredicate));
      }
      GenericType[] genericTypes = list.toArray(new GenericType[0]);
      return new GenericType(typeRef, finalPredicate.test(type), genericTypes);
    } else if (type instanceof GenericArrayType) { // List<String>[] or T[]
      TypeRef<?> componentType = TypeRef.of(((GenericArrayType) type).getGenericComponentType());
      return new GenericType(typeRef, finalPredicate.test(type), build(componentType));
    } else if (type instanceof Class && ((Class<?>) type).isArray()) {
      TypeRef<?> componentType = typeRef.getComponentType();
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

  private static GenericType buildCollection(TypeRef<?> typeRef, Predicate<Type> finalPredicate) {
    TypeRef<?> collectionTypeRef = typeRef.hasWildcard() ? typeRef.resolveAllWildcards() : typeRef;
    TypeRef<?> elementTypeRef = TypeUtils.getElementType(collectionTypeRef);
    if (elementTypeRef.getType() instanceof WildcardType) {
      elementTypeRef = elementTypeRef.resolveAllWildcards();
    }
    // Raw custom collections have no actual argument for E; E and shapes containing E must stay
    // Object, because using E's bound would reject unchecked raw contents.
    if (!hasTypeArguments(collectionTypeRef) && hasUnresolvedTypeVariable(elementTypeRef)) {
      elementTypeRef = TypeUtils.OBJECT_TYPE;
    }
    // Recursive collection declarations such as SelfList extends ArrayList<SelfList> must not
    // push themselves as their own element generic type, or interpreter mode recurses forever.
    if (isSelfRecursiveTypeArg(typeRef, elementTypeRef)) {
      elementTypeRef = TypeUtils.OBJECT_TYPE;
    }
    if (elementTypeRef.equals(TypeUtils.OBJECT_TYPE) && !hasTypeArguments(collectionTypeRef)) {
      return new GenericType(typeRef, finalPredicate.test(typeRef.getType()));
    }
    // Collection serializers consume type parameter 0 as the element type. Custom collection
    // subclasses may declare unrelated parameters, so collection GenericType stores only the
    // resolved element view.
    return new GenericType(
        typeRef, finalPredicate.test(typeRef.getType()), build(elementTypeRef, finalPredicate));
  }

  private static GenericType buildMap(TypeRef<?> typeRef, Predicate<Type> finalPredicate) {
    TypeRef<?> mapTypeRef = typeRef.hasWildcard() ? typeRef.resolveAllWildcards() : typeRef;
    Tuple2<TypeRef<?>, TypeRef<?>> keyValueType = TypeUtils.getMapKeyValueType(mapTypeRef);
    TypeRef<?> keyTypeRef = normalizeMapTypeArg(mapTypeRef, keyValueType.f0);
    TypeRef<?> valueTypeRef = normalizeMapTypeArg(mapTypeRef, keyValueType.f1);
    if (keyTypeRef.getType() instanceof WildcardType) {
      keyTypeRef = keyTypeRef.resolveAllWildcards();
    }
    if (valueTypeRef.getType() instanceof WildcardType) {
      valueTypeRef = valueTypeRef.resolveAllWildcards();
    }
    // Raw custom maps have no actual K/V arguments; K/V and shapes containing them must stay
    // Object, because using their bounds would reject unchecked raw contents.
    if (!hasTypeArguments(mapTypeRef)) {
      if (hasUnresolvedTypeVariable(keyTypeRef)) {
        keyTypeRef = TypeUtils.OBJECT_TYPE;
      }
      if (hasUnresolvedTypeVariable(valueTypeRef)) {
        valueTypeRef = TypeUtils.OBJECT_TYPE;
      }
    }
    if (keyTypeRef.equals(TypeUtils.OBJECT_TYPE)
        && valueTypeRef.equals(TypeUtils.OBJECT_TYPE)
        && !hasTypeArguments(mapTypeRef)) {
      return new GenericType(typeRef, finalPredicate.test(typeRef.getType()));
    }
    // Map serializers consume type parameter 0 as key type and 1 as value type. Custom map
    // subclasses may declare unrelated parameters, so map GenericType stores only the resolved
    // key/value view.
    return new GenericType(
        typeRef,
        finalPredicate.test(typeRef.getType()),
        build(keyTypeRef, finalPredicate),
        build(valueTypeRef, finalPredicate));
  }

  private static TypeRef<?> normalizeMapTypeArg(TypeRef<?> ownerTypeRef, TypeRef<?> typeArg) {
    // Recursive map declarations such as SelfMap extends HashMap<SelfMap, ...> must not build the
    // same map GenericType as its own key/value parameter, or interpreter mode recurses forever.
    return isSelfRecursiveTypeArg(ownerTypeRef, typeArg) ? TypeUtils.OBJECT_TYPE : typeArg;
  }

  private static boolean isSelfRecursiveTypeArg(TypeRef<?> ownerTypeRef, TypeRef<?> typeArg) {
    if (getRawType(ownerTypeRef) != typeArg.getRawType()) {
      return false;
    }
    // Nested containers with the same raw type, such as List<List<String>> or Map<Map<K, V>, V>,
    // are valid declared arguments. Only normalize true self-recursive custom declarations like
    // SelfList extends ArrayList<SelfList>, where the child type is not one of the owner's own
    // actual arguments.
    for (TypeRef<?> declaredArg : ownerTypeRef.getTypeArguments()) {
      if (sameTypeView(declaredArg, typeArg)) {
        return false;
      }
    }
    return true;
  }

  private static boolean sameTypeView(TypeRef<?> left, TypeRef<?> right) {
    if (left.getType() instanceof WildcardType) {
      Type[] upperBounds = ((WildcardType) left.getType()).getUpperBounds();
      return upperBounds.length != 0 && sameTypeView(TypeRef.of(upperBounds[0]), right);
    }
    if (right.getType() instanceof WildcardType) {
      Type[] upperBounds = ((WildcardType) right.getType()).getUpperBounds();
      return upperBounds.length != 0 && sameTypeView(left, TypeRef.of(upperBounds[0]));
    }
    if (!left.getType().equals(right.getType())) {
      return false;
    }
    if (left.hasExplicitTypeArguments() || right.hasExplicitTypeArguments()) {
      List<TypeRef<?>> leftArgs = left.getTypeArguments();
      List<TypeRef<?>> rightArgs = right.getTypeArguments();
      if (leftArgs.size() != rightArgs.size()) {
        return false;
      }
      for (int i = 0; i < leftArgs.size(); i++) {
        if (!sameTypeView(leftArgs.get(i), rightArgs.get(i))) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean hasTypeArguments(TypeRef<?> typeRef) {
    if (typeRef.hasExplicitTypeArguments()) {
      return !typeRef.getTypeArguments().isEmpty();
    }
    Type type = typeRef.getType();
    return type instanceof ParameterizedType
        && ((ParameterizedType) type).getActualTypeArguments().length > 0;
  }

  private static boolean hasUnresolvedTypeVariable(TypeRef<?> typeRef) {
    Type type = typeRef.getType();
    if (type instanceof TypeVariable) {
      return true;
    }
    if (type instanceof WildcardType) {
      WildcardType wildcardType = (WildcardType) type;
      for (Type lowerBound : wildcardType.getLowerBounds()) {
        if (hasUnresolvedTypeVariable(TypeRef.of(lowerBound))) {
          return true;
        }
      }
      for (Type upperBound : wildcardType.getUpperBounds()) {
        if (hasUnresolvedTypeVariable(TypeRef.of(upperBound))) {
          return true;
        }
      }
      return false;
    }
    if (typeRef.isArray()) {
      return hasUnresolvedTypeVariable(typeRef.getComponentType());
    }
    for (TypeRef<?> typeArgument : typeRef.getTypeArguments()) {
      if (hasUnresolvedTypeVariable(typeArgument)) {
        return true;
      }
    }
    return false;
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

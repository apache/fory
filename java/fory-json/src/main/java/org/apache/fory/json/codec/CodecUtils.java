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

package org.apache.fory.json.codec;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.reflect.TypeRef;

/**
 * Cold-path helpers for resolving collection and map type arguments into concrete codec metadata.
 *
 * <p>Factories consume {@link TypeRef} values during codec construction and retain only resolved
 * element, key, or value bindings needed during execution. These helpers therefore do not
 * participate in per-element dispatch and are not a runtime type-policy owner.
 */
public final class CodecUtils {
  private CodecUtils() {}

  public static Class<?> rawType(Type type, Class<?> fallback) {
    if (type instanceof Class) {
      Class<?> rawType = (Class<?>) type;
      if (rawType == Object.class && fallback != null) {
        return fallback;
      }
      return rawType;
    }
    if (type instanceof ParameterizedType) {
      Type rawType = ((ParameterizedType) type).getRawType();
      if (rawType instanceof Class) {
        return (Class<?>) rawType;
      }
    }
    return fallback == null ? Object.class : fallback;
  }

  public static Type elementType(Type type) {
    if (type instanceof ParameterizedType) {
      Type[] arguments = ((ParameterizedType) type).getActualTypeArguments();
      if (arguments.length == 1) {
        return arguments[0];
      }
    }
    return Object.class;
  }

  public static Type mapValueType(Type type) {
    if (type instanceof ParameterizedType) {
      Type[] arguments = ((ParameterizedType) type).getActualTypeArguments();
      if (arguments.length == 2) {
        return arguments[1];
      }
    }
    return Object.class;
  }

  public static Type mapKeyType(Type type) {
    if (type instanceof ParameterizedType) {
      Type[] arguments = ((ParameterizedType) type).getActualTypeArguments();
      if (arguments.length == 2) {
        return arguments[0];
      }
    }
    return String.class;
  }

  public static TypeRef<?> elementTypeRef(TypeRef<?> typeRef) {
    List<TypeRef<?>> arguments = typeRef.getTypeArguments();
    if (arguments.size() == 1) {
      return arguments.get(0);
    }
    Class<?> rawType = typeRef.getRawType();
    if (Collection.class.isAssignableFrom(rawType)) {
      if (!typeRef.hasExplicitTypeArguments() && rawType.getTypeParameters().length > 0) {
        return TypeRef.of(Object.class);
      }
      @SuppressWarnings({"rawtypes", "unchecked"})
      TypeRef<?> collectionType = ((TypeRef) typeRef).getSupertype((Class) Collection.class);
      return collectionType.resolveType(Collection.class.getTypeParameters()[0]);
    }
    return TypeRef.of(Object.class);
  }

  public static Tuple2<TypeRef<?>, TypeRef<?>> mapKeyValueTypeRefs(TypeRef<?> typeRef) {
    List<TypeRef<?>> arguments = typeRef.getTypeArguments();
    if (arguments.size() == 2) {
      return Tuple2.of(arguments.get(0), arguments.get(1));
    }
    Class<?> rawType = typeRef.getRawType();
    if (Map.class.isAssignableFrom(rawType)) {
      if (!typeRef.hasExplicitTypeArguments() && rawType.getTypeParameters().length > 0) {
        return Tuple2.of(TypeRef.of(Object.class), TypeRef.of(Object.class));
      }
      @SuppressWarnings({"rawtypes", "unchecked"})
      TypeRef<?> mapType = ((TypeRef) typeRef).getSupertype((Class) Map.class);
      Type[] parameters = Map.class.getTypeParameters();
      return Tuple2.of(mapType.resolveType(parameters[0]), mapType.resolveType(parameters[1]));
    }
    return Tuple2.of(TypeRef.of(String.class), TypeRef.of(Object.class));
  }
}

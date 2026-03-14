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

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TimeZone;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import org.apache.fory.annotation.Ref;
import org.apache.fory.collection.BoolList;
import org.apache.fory.collection.Float16List;
import org.apache.fory.collection.Float32List;
import org.apache.fory.collection.Float64List;
import org.apache.fory.collection.IdentityMap;
import org.apache.fory.collection.Int16List;
import org.apache.fory.collection.Int32List;
import org.apache.fory.collection.Int64List;
import org.apache.fory.collection.Int8List;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.collection.Uint16List;
import org.apache.fory.collection.Uint32List;
import org.apache.fory.collection.Uint64List;
import org.apache.fory.collection.Uint8List;
import org.apache.fory.meta.TypeExtMeta;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeParameter;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.serializer.UnknownClass;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.StringUtils;
import org.apache.fory.util.record.RecordUtils;

/** Type utils for common type inference and extraction. */
@SuppressWarnings({"UnstableApiUsage", "unchecked"})
public class TypeUtils {
  public static final String JAVA_BOOLEAN = "boolean";
  public static final String JAVA_BYTE = "byte";
  public static final String JAVA_SHORT = "short";
  public static final String JAVA_INT = "int";
  public static final String JAVA_LONG = "long";
  public static final String JAVA_FLOAT = "float";
  public static final String JAVA_DOUBLE = "double";
  public static final TypeRef<?> PRIMITIVE_VOID_TYPE = TypeRef.of(void.class);
  public static final TypeRef<?> VOID_TYPE = TypeRef.of(Void.class);
  public static final TypeRef<?> PRIMITIVE_BYTE_TYPE = TypeRef.of(byte.class);
  public static final TypeRef<?> PRIMITIVE_BOOLEAN_TYPE = TypeRef.of(boolean.class);
  public static final TypeRef<?> PRIMITIVE_CHAR_TYPE = TypeRef.of(char.class);
  public static final TypeRef<?> PRIMITIVE_SHORT_TYPE = TypeRef.of(short.class);
  public static final TypeRef<?> PRIMITIVE_INT_TYPE = TypeRef.of(int.class);
  public static final TypeRef<?> PRIMITIVE_LONG_TYPE = TypeRef.of(long.class);
  public static final TypeRef<?> PRIMITIVE_FLOAT_TYPE = TypeRef.of(float.class);
  public static final TypeRef<?> PRIMITIVE_DOUBLE_TYPE = TypeRef.of(double.class);
  public static final TypeRef<?> BYTE_TYPE = TypeRef.of(Byte.class);
  public static final TypeRef<?> BOOLEAN_TYPE = TypeRef.of(Boolean.class);
  public static final TypeRef<?> CHAR_TYPE = TypeRef.of(Character.class);
  public static final TypeRef<?> SHORT_TYPE = TypeRef.of(Short.class);
  public static final TypeRef<?> INT_TYPE = TypeRef.of(Integer.class);
  public static final TypeRef<?> LONG_TYPE = TypeRef.of(Long.class);
  public static final TypeRef<?> FLOAT_TYPE = TypeRef.of(Float.class);
  public static final TypeRef<?> DOUBLE_TYPE = TypeRef.of(Double.class);
  public static final TypeRef<?> STRING_TYPE = TypeRef.of(String.class);
  public static final TypeRef<?> BIG_DECIMAL_TYPE = TypeRef.of(BigDecimal.class);
  public static final TypeRef<?> BIG_INTEGER_TYPE = TypeRef.of(BigInteger.class);
  public static final TypeRef<?> DATE_TYPE = TypeRef.of(Date.class);
  public static final TypeRef<?> LOCAL_DATE_TYPE = TypeRef.of(LocalDate.class);
  public static final TypeRef<?> TIMESTAMP_TYPE = TypeRef.of(Timestamp.class);
  public static final TypeRef<?> INSTANT_TYPE = TypeRef.of(Instant.class);
  public static final TypeRef<?> BINARY_TYPE = TypeRef.of(byte[].class);
  public static final TypeRef<?> ITERABLE_TYPE = TypeRef.of(Iterable.class);

  public static final TypeRef<?> ITERATOR_TYPE = TypeRef.of(Iterator.class);
  public static final TypeRef<?> COLLECTION_TYPE = TypeRef.of(Collection.class);
  public static final TypeRef<?> LIST_TYPE = TypeRef.of(List.class);
  public static final TypeRef<?> ARRAYLIST_TYPE = TypeRef.of(ArrayList.class);
  public static final TypeRef<?> SET_TYPE = TypeRef.of(Set.class);
  public static final TypeRef<?> HASHSET_TYPE = TypeRef.of(HashSet.class);
  public static final TypeRef<?> MAP_TYPE = TypeRef.of(Map.class);
  public static final TypeRef<?> MAP_ENTRY_TYPE = TypeRef.of(Map.Entry.class);
  public static final TypeRef<?> HASHMAP_TYPE = TypeRef.of(HashMap.class);
  public static final TypeRef<?> OPTIONAL_TYPE = TypeRef.of(Optional.class);
  public static final TypeRef<?> OPTIONAL_INT_TYPE = TypeRef.of(OptionalInt.class);
  public static final TypeRef<?> OPTIONAL_LONG_TYPE = TypeRef.of(OptionalLong.class);
  public static final TypeRef<?> OPTIONAL_DOUBLE_TYPE = TypeRef.of(OptionalDouble.class);
  public static final TypeRef<?> OBJECT_TYPE = TypeRef.of(Object.class);

  public static Type ITERATOR_RETURN_TYPE;
  public static Type NEXT_RETURN_TYPE;
  public static Type KEY_SET_RETURN_TYPE;
  public static Type VALUES_RETURN_TYPE;

  public static final TypeRef<?> PRIMITIVE_BYTE_ARRAY_TYPE = TypeRef.of(byte[].class);
  public static final TypeRef<?> PRIMITIVE_BOOLEAN_ARRAY_TYPE = TypeRef.of(boolean[].class);
  public static final TypeRef<?> PRIMITIVE_SHORT_ARRAY_TYPE = TypeRef.of(short[].class);
  public static final TypeRef<?> PRIMITIVE_CHAR_ARRAY_TYPE = TypeRef.of(char[].class);
  public static final TypeRef<?> PRIMITIVE_INT_ARRAY_TYPE = TypeRef.of(int[].class);
  public static final TypeRef<?> PRIMITIVE_LONG_ARRAY_TYPE = TypeRef.of(long[].class);
  public static final TypeRef<?> PRIMITIVE_FLOAT_ARRAY_TYPE = TypeRef.of(float[].class);
  public static final TypeRef<?> PRIMITIVE_DOUBLE_ARRAY_TYPE = TypeRef.of(double[].class);
  public static final TypeRef<?> OBJECT_ARRAY_TYPE = TypeRef.of(Object[].class);

  public static final TypeRef<?> CLASS_TYPE = TypeRef.of(Class.class);

  /**
   * bean fields should all be in SUPPORTED_TYPES, enum, array/ITERABLE_TYPE/MAP_TYPE type, bean
   * type.
   *
   * <p>If bean fields is ITERABLE_TYPE/MAP_TYPE, the type should be super class(inclusive) of
   * List/Set/Map, or else should be a no arg constructor.
   */
  public static Set<TypeRef<?>> SUPPORTED_TYPES = new HashSet<>();

  static {
    SUPPORTED_TYPES.add(PRIMITIVE_BYTE_TYPE);
    SUPPORTED_TYPES.add(PRIMITIVE_BOOLEAN_TYPE);
    SUPPORTED_TYPES.add(PRIMITIVE_CHAR_TYPE);
    SUPPORTED_TYPES.add(PRIMITIVE_SHORT_TYPE);
    SUPPORTED_TYPES.add(PRIMITIVE_INT_TYPE);
    SUPPORTED_TYPES.add(PRIMITIVE_LONG_TYPE);
    SUPPORTED_TYPES.add(PRIMITIVE_FLOAT_TYPE);
    SUPPORTED_TYPES.add(PRIMITIVE_DOUBLE_TYPE);

    SUPPORTED_TYPES.add(BYTE_TYPE);
    SUPPORTED_TYPES.add(BOOLEAN_TYPE);
    SUPPORTED_TYPES.add(CHAR_TYPE);
    SUPPORTED_TYPES.add(SHORT_TYPE);
    SUPPORTED_TYPES.add(INT_TYPE);
    SUPPORTED_TYPES.add(LONG_TYPE);
    SUPPORTED_TYPES.add(FLOAT_TYPE);
    SUPPORTED_TYPES.add(DOUBLE_TYPE);

    SUPPORTED_TYPES.add(STRING_TYPE);
    SUPPORTED_TYPES.add(BIG_DECIMAL_TYPE);
    // SUPPORTED_TYPES.add(BIG_INTEGER_TYPE);
    SUPPORTED_TYPES.add(DATE_TYPE);
    SUPPORTED_TYPES.add(LOCAL_DATE_TYPE);
    SUPPORTED_TYPES.add(TIMESTAMP_TYPE);
    SUPPORTED_TYPES.add(INSTANT_TYPE);
    SUPPORTED_TYPES.add(OPTIONAL_TYPE);
    SUPPORTED_TYPES.add(OPTIONAL_INT_TYPE);
    SUPPORTED_TYPES.add(OPTIONAL_LONG_TYPE);
    SUPPORTED_TYPES.add(OPTIONAL_DOUBLE_TYPE);
  }

  static {
    try {
      ITERATOR_RETURN_TYPE = Iterable.class.getMethod("iterator").getGenericReturnType();
      NEXT_RETURN_TYPE = Iterator.class.getMethod("next").getGenericReturnType();
      KEY_SET_RETURN_TYPE = Map.class.getMethod("keySet").getGenericReturnType();
      VALUES_RETURN_TYPE = Map.class.getMethod("values").getGenericReturnType();
    } catch (NoSuchMethodException e) {
      throw new Error(e); // should be impossible
    }
  }

  // sorted by size
  private static final List<Class<?>> sortedPrimitiveClasses =
      Arrays.asList(
          void.class,
          boolean.class,
          byte.class,
          char.class,
          short.class,
          int.class,
          float.class,
          long.class,
          double.class);
  private static final List<Class<?>> sortedBoxedClasses =
      Arrays.asList(
          Void.class,
          Boolean.class,
          Byte.class,
          Character.class,
          Short.class,
          Integer.class,
          Float.class,
          Long.class,
          Double.class);
  private static final int[] sortedSizes = new int[] {0, 1, 1, 2, 2, 4, 4, 8, 8};
  private static final IdentityMap<Class<?>, Class<?>> primToWrap = new IdentityMap<>(18);
  private static final IdentityMap<Class<?>, Class<?>> wrapToPrim = new IdentityMap<>(18);

  static {
    add(primToWrap, wrapToPrim, boolean.class, Boolean.class);
    add(primToWrap, wrapToPrim, byte.class, Byte.class);
    add(primToWrap, wrapToPrim, char.class, Character.class);
    add(primToWrap, wrapToPrim, double.class, Double.class);
    add(primToWrap, wrapToPrim, float.class, Float.class);
    add(primToWrap, wrapToPrim, int.class, Integer.class);
    add(primToWrap, wrapToPrim, long.class, Long.class);
    add(primToWrap, wrapToPrim, short.class, Short.class);
    add(primToWrap, wrapToPrim, void.class, Void.class);
  }

  private static void add(
      IdentityMap<Class<?>, Class<?>> forward,
      IdentityMap<Class<?>, Class<?>> backward,
      Class<?> key,
      Class<?> value) {
    forward.put(key, value);
    backward.put(value, key);
  }

  public static boolean isNullable(Class<?> clz) {
    return !isPrimitive(clz);
  }

  public static boolean isPrimitive(Class<?> clz) {
    return clz.isPrimitive();
  }

  public static boolean isBoxed(Class<?> clz) {
    return wrapToPrim.containsKey(clz);
  }

  public static Class<?> wrap(Class<?> clz) {
    return boxedType(clz);
  }

  public static Class<?> unwrap(Class<?> clz) {
    if (clz == null) {
      return null;
    }
    if (clz.isPrimitive()) {
      return clz;
    }
    return wrapToPrim.get(clz, clz);
  }

  public static Class<?> boxedType(Class<?> clz) {
    if (clz == null) {
      return null;
    }
    if (!clz.isPrimitive()) {
      return clz;
    }
    return primToWrap.get(clz);
  }

  public static List<Class<?>> getSortedPrimitiveClasses() {
    return sortedPrimitiveClasses;
  }

  public static List<Class<?>> getSortedBoxedClasses() {
    return sortedBoxedClasses;
  }

  /** Returns a primitive type class that has has max size between numericTypes. */
  public static Class<?> maxType(Class<?>... numericTypes) {
    Preconditions.checkArgument(numericTypes.length >= 2);
    int maxIndex = 0;
    for (Class<?> numericType : numericTypes) {
      int index;
      if (isPrimitive(numericType)) {
        index = sortedPrimitiveClasses.indexOf(numericType);
      } else {
        index = sortedBoxedClasses.indexOf(numericType);
      }
      if (index == -1) {
        throw new IllegalArgumentException(
            String.format("Wrong numericTypes %s", Arrays.toString(numericTypes)));
      }
      maxIndex = Math.max(maxIndex, index);
    }
    return sortedPrimitiveClasses.get(maxIndex);
  }

  /** Returns size of primitive type. */
  public static int getSizeOfPrimitiveType(TypeRef<?> numericType) {
    return getSizeOfPrimitiveType(getRawType(numericType));
  }

  public static int getSizeOfPrimitiveType(Class<?> numericType) {
    if (isPrimitive(numericType)) {
      int index = sortedPrimitiveClasses.indexOf(numericType);
      return sortedSizes[index];
    } else {
      String msg = String.format("Class %s must be primitive", numericType);
      throw new IllegalArgumentException(msg);
    }
  }

  /** Returns default value of class. */
  public static String defaultValue(Class<?> type) {
    return defaultValue(type.getSimpleName(), false);
  }

  /** Returns default value of class. */
  public static String defaultValue(String type) {
    return defaultValue(type, false);
  }

  /**
   * Returns the representation of default value for a given Java Type.
   *
   * @param type the string name of the Java type
   * @param typedNull if true, for null literals, return a typed (with a cast) version
   */
  public static String defaultValue(String type, boolean typedNull) {
    switch (type) {
      case JAVA_BOOLEAN:
        return "false";
      case JAVA_BYTE:
        return "(byte)0";
      case JAVA_SHORT:
        return "(short)0";
      case JAVA_INT:
        return "0";
      case JAVA_LONG:
        return "0L";
      case JAVA_FLOAT:
        return "0.0f";
      case JAVA_DOUBLE:
        return "0.0";
      default:
        if (typedNull) {
          return String.format("((%s)null)", type);
        } else {
          return "null";
        }
    }
  }

  /** Faster method to get raw type from {@link TypeRef} than {@link TypeRef#getRawType}. */
  public static Class<?> getRawType(TypeRef<?> typeRef) {
    Type type = typeRef.getType();
    if (type.getClass() == Class.class) {
      return (Class<?>) type;
    } else {
      return getRawType(type);
    }
  }

  /** Faster method to get raw type from {@link TypeRef} than {@link TypeRef#getRawType}. */
  public static Class<?> getRawType(Type type) {
    if (type instanceof TypeVariable) {
      return getRawType(((TypeVariable<?>) type).getBounds()[0]);
    } else if (type instanceof WildcardType) {
      return getRawType(((WildcardType) type).getUpperBounds()[0]);
    } else if (type instanceof ParameterizedType) {
      return (Class<?>) ((ParameterizedType) type).getRawType();
    } else if (type instanceof Class) {
      return ((Class<?>) type);
    } else if (type instanceof GenericArrayType) {
      Type componentType = ((GenericArrayType) type).getGenericComponentType();
      return Array.newInstance(getRawType(TypeRef.of(componentType)), 0).getClass();
    } else {
      throw new AssertionError("Unknown type: " + type);
    }
  }

  /** Returns dimensions of multi-dimension array. */
  public static int getArrayDimensions(TypeRef<?> type) {
    return getArrayDimensions(getRawType(type));
  }

  /** Returns dimensions of multi-dimension array. */
  public static int getArrayDimensions(Class<?> type) {
    return getArrayComponentInfo(type).f1;
  }

  public static int getArrayDimensions(String className) {
    int dimension = 0;
    while (className.charAt(dimension) == '[') {
      dimension++;
    }
    return dimension;
  }

  public static Class<?> getComponentIfArray(Class<?> type) {
    if (type.isArray()) {
      return getArrayComponent(type);
    }
    return type;
  }

  public static TypeRef<?> getArrayComponent(TypeRef<?> type) {
    if (type.getType() instanceof GenericArrayType) {
      Type componentType = ((GenericArrayType) (type.getType())).getGenericComponentType();
      return TypeRef.of(componentType);
    }
    return TypeRef.of(getArrayComponentInfo(type.getRawType()).f0);
  }

  public static Class<?> getArrayComponent(Class<?> type) {
    return getArrayComponentInfo(type).f0;
  }

  public static Tuple2<Class<?>, Integer> getArrayComponentInfo(Class<?> type) {
    Preconditions.checkArgument(type.isArray());
    Class<?> t = type;
    int dimension = 0;
    while (t != null && t.isArray()) {
      dimension++;
      t = t.getComponentType();
    }
    return Tuple2.of(t, dimension);
  }

  /** Returns s string that represents array type declaration of type. */
  public static String getArrayType(TypeRef<?> type) {
    return getArrayType(getRawType(type));
  }

  /** Returns s string that represents array type declaration of type. */
  public static String getArrayType(Class<?> type) {
    Tuple2<Class<?>, Integer> info = getArrayComponentInfo(type);
    StringBuilder typeBuilder = new StringBuilder(ReflectionUtils.getLiteralName(info.f0));
    for (int i = 0; i < info.f1; i++) {
      typeBuilder.append("[]");
    }
    return typeBuilder.toString();
  }

  /** Create an array type declaration from elemType and dimensions. */
  public static String getArrayType(Class<?> elemType, int[] dimensions) {
    StringBuilder typeBuilder = new StringBuilder(ReflectionUtils.getLiteralName(elemType));
    for (int dimension : dimensions) {
      typeBuilder.append('[').append(dimension).append(']');
    }
    return typeBuilder.toString();
  }

  public static String getArrayClass(Class<?> type) {
    Tuple2<Class<?>, Integer> info = getArrayComponentInfo(type);
    StringBuilder typeBuilder = new StringBuilder(info.f0.getName());
    for (int i = 0; i < info.f1; i++) {
      typeBuilder.append("[]");
    }
    return typeBuilder.toString();
  }

  /**
   * Get element type of multi dimension array.
   *
   * @param type array type
   * @return element type of multi-dimension array
   */
  public static TypeRef<?> getMultiDimensionArrayElementType(TypeRef<?> type) {
    TypeRef<?> t = type;
    while (t != null && t.isArray()) {
      t = t.getComponentType();
    }
    return t;
  }

  /** Returns element type of iterable. */
  public static TypeRef<?> getElementType(TypeRef<?> typeRef) {
    if (typeRef.hasTypeExtMeta() && typeRef.hasExplicitTypeArguments()) {
      List<TypeRef<?>> typeArguments = typeRef.getTypeArguments();
      if (typeArguments.size() == 1) {
        Class<?> rawType = getRawType(typeRef);
        if (Iterable.class.isAssignableFrom(rawType) && rawType.getTypeParameters().length == 1) {
          return typeArguments.get(0);
        }
      }
    }
    Type type = typeRef.getType();
    if (type instanceof ParameterizedType) {
      ParameterizedType parameterizedType = (ParameterizedType) type;
      if (parameterizedType.getRawType() == List.class) { // fastpath
        Type[] actualTypeArguments = (parameterizedType).getActualTypeArguments();
        Preconditions.checkState(actualTypeArguments.length == 1);
        Type t = actualTypeArguments[0];
        if (t.getClass() == Class.class) { // if t is wild type, upper should be parsed.
          return TypeRef.of(t);
        }
      }
    }
    if (typeRef.getType().getTypeName().startsWith("scala.collection")) {
      return ScalaTypes.getElementType(typeRef);
    }
    TypeRef<?> supertype = ((TypeRef<? extends Iterable<?>>) typeRef).getSupertype(Iterable.class);
    return supertype.resolveType(ITERATOR_RETURN_TYPE).resolveType(NEXT_RETURN_TYPE);
  }

  public static TypeRef<?> getCollectionType(TypeRef<?> typeRef) {
    @SuppressWarnings("unchecked")
    TypeRef<?> supertype = ((TypeRef<? extends Iterable<?>>) typeRef).getSupertype(Iterable.class);
    return supertype.getSubtype(Collection.class);
  }

  /** Returns key/value type of map. */
  public static Tuple2<TypeRef<?>, TypeRef<?>> getMapKeyValueType(TypeRef<?> typeRef) {
    if (typeRef.hasTypeExtMeta() && typeRef.hasExplicitTypeArguments()) {
      List<TypeRef<?>> typeArguments = typeRef.getTypeArguments();
      if (typeArguments.size() == 2) {
        Class<?> rawType = getRawType(typeRef);
        if (Map.class.isAssignableFrom(rawType) && rawType.getTypeParameters().length == 2) {
          return Tuple2.of(typeArguments.get(0), typeArguments.get(1));
        }
      }
    }
    Type type = typeRef.getType();
    if (type instanceof ParameterizedType) {
      ParameterizedType parameterizedType = (ParameterizedType) type;
      if (parameterizedType.getRawType() == Map.class) { // fastpath
        Type[] actualTypeArguments = (parameterizedType).getActualTypeArguments();
        Preconditions.checkState(actualTypeArguments.length == 2);
        if (actualTypeArguments[0].getClass() == Class.class
            && actualTypeArguments[1].getClass() == Class.class) {
          // if actualTypeArguments are wild type, upper should be parsed.
          return Tuple2.of(TypeRef.of(actualTypeArguments[0]), TypeRef.of(actualTypeArguments[1]));
        }
      }
    }
    if (typeRef.getType().getTypeName().startsWith("scala.collection")) {
      return ScalaTypes.getMapKeyValueType(typeRef);
    }
    @SuppressWarnings("unchecked")
    TypeRef<?> supertype = ((TypeRef<? extends Map<?, ?>>) typeRef).getSupertype(Map.class);
    TypeRef<?> keyType = getElementType(supertype.resolveType(KEY_SET_RETURN_TYPE));
    TypeRef<?> valueType = getElementType(supertype.resolveType(VALUES_RETURN_TYPE));
    return Tuple2.of(keyType, valueType);
  }

  public static void applyRefTrackingOverride(
      GenericType genericType, AnnotatedType annotatedType, boolean globalTrackingRef) {
    if (genericType == null || annotatedType == null) {
      return;
    }
    Ref ref = annotatedType.getAnnotation(Ref.class);
    if (ref != null) {
      genericType.setTrackingRefOverride(ref.enable() && globalTrackingRef);
    }
    if (annotatedType instanceof AnnotatedParameterizedType) {
      AnnotatedType[] annotatedArgs =
          ((AnnotatedParameterizedType) annotatedType).getAnnotatedActualTypeArguments();
      GenericType[] typeParameters = genericType.getTypeParameters();
      int len = Math.min(annotatedArgs.length, typeParameters.length);
      for (int i = 0; i < len; i++) {
        applyRefTrackingOverride(typeParameters[i], annotatedArgs[i], globalTrackingRef);
      }
    }
  }

  public static <E> TypeRef<ArrayList<E>> arrayListOf(Class<E> elemType) {
    return new TypeRef<ArrayList<E>>() {}.where(new TypeParameter<E>() {}, elemType);
  }

  public static <E> TypeRef<List<E>> listOf(Class<E> elemType) {
    return new TypeRef<List<E>>() {}.where(new TypeParameter<E>() {}, elemType);
  }

  public static <E> TypeRef<Collection<E>> collectionOf(Class<E> elemType) {
    return collectionOf(TypeRef.of(elemType));
  }

  public static <E> TypeRef<Collection<E>> collectionOf(TypeRef<E> elemType) {
    return new TypeRef<Collection<E>>() {}.where(new TypeParameter<E>() {}, elemType);
  }

  public static <E> TypeRef<Collection<E>> collectionOf(TypeRef<E> elemType, TypeExtMeta extMeta) {
    return new TypeRef<Collection<E>>(extMeta) {}.where(new TypeParameter<E>() {}, elemType);
  }

  public static <E> TypeRef<? extends Collection<E>> collectionOf(
      Class<?> collectionType, TypeRef<E> elemType, TypeExtMeta extMeta) {
    return new TypeRef<Collection<E>>(extMeta) {}.where(new TypeParameter<E>() {}, elemType)
        .getSubtype(collectionType);
  }

  public static <K, V> TypeRef<Map<K, V>> mapOf(Class<K> keyType, Class<V> valueType) {
    return mapOf(TypeRef.of(keyType), TypeRef.of(valueType));
  }

  public static <K, V> TypeRef<Map<K, V>> mapOf(TypeRef<K> keyType, TypeRef<V> valueType) {
    return new TypeRef<Map<K, V>>() {}.where(new TypeParameter<K>() {}, keyType)
        .where(new TypeParameter<V>() {}, valueType);
  }

  public static <K, V> TypeRef<Map<K, V>> mapOf(
      TypeRef<K> keyType, TypeRef<V> valueType, TypeExtMeta extMeta) {
    return new TypeRef<Map<K, V>>(extMeta) {}.where(new TypeParameter<K>() {}, keyType)
        .where(new TypeParameter<V>() {}, valueType);
  }

  public static <K, V> TypeRef<? extends Map<K, V>> mapOf(
      Class<?> mapType, TypeRef<K> keyType, TypeRef<V> valueType, TypeExtMeta extMeta) {
    TypeRef<Map<K, V>> mapTypeRef =
        new TypeRef<Map<K, V>>(extMeta) {}.where(new TypeParameter<K>() {}, keyType)
            .where(new TypeParameter<V>() {}, valueType);
    return mapTypeRef.getSubtype(mapType);
  }

  public static <K, V> TypeRef<? extends Map<K, V>> mapOf(
      Class<?> mapType, TypeRef<K> keyType, TypeRef<V> valueType) {
    TypeRef<Map<K, V>> mapTypeRef = mapOf(keyType, valueType);
    return mapTypeRef.getSubtype(mapType);
  }

  public static <K, V> TypeRef<? extends Map<K, V>> mapOf(
      Class<?> mapType, Class<K> keyType, Class<V> valueType) {
    TypeRef<Map<K, V>> mapTypeRef = mapOf(keyType, valueType);
    return mapTypeRef.getSubtype(mapType);
  }

  public static <K, V> TypeRef<HashMap<K, V>> hashMapOf(Class<K> keyType, Class<V> valueType) {
    return new TypeRef<HashMap<K, V>>() {}.where(new TypeParameter<K>() {}, keyType)
        .where(new TypeParameter<V>() {}, valueType);
  }

  public static boolean isContainer(Class<?> cls) {
    return isCollection(cls) || isMap(cls) || cls.isArray();
  }

  public static boolean isCollection(Class<?> cls) {
    if (isPrimitiveListClass(cls)) {
      return false;
    }
    return cls == ArrayList.class || Collection.class.isAssignableFrom(cls);
  }

  public static boolean isPrimitiveListClass(Class<?> cls) {
    return cls == BoolList.class
        || cls == Int8List.class
        || cls == Int16List.class
        || cls == Int32List.class
        || cls == Int64List.class
        || cls == Uint8List.class
        || cls == Uint16List.class
        || cls == Uint32List.class
        || cls == Uint64List.class
        || cls == Float16List.class
        || cls == Float32List.class
        || cls == Float64List.class;
  }

  public static boolean isMap(Class<?> cls) {
    if (cls == UnknownClass.UnknownStruct.class) {
      return false;
    }
    return cls == HashMap.class || Map.class.isAssignableFrom(cls);
  }

  public static boolean isBean(Type type) {
    return isBean(TypeRef.of(type));
  }

  public static boolean isBean(Class<?> clz) {
    return isBean(TypeRef.of(clz));
  }

  public static boolean isBean(Type type, CustomTypeRegistry customTypes) {
    return isBean(TypeRef.of(type), customTypes);
  }

  public static boolean isBean(TypeRef<?> type, CustomTypeRegistry customTypes) {
    return isBean(type, new TypeResolutionContext(customTypes));
  }

  /**
   * Returns true if class is not array/iterable/map, and all fields is {@link
   * TypeUtils#isSupported(TypeRef)}. Bean class can't be a non-static inner class. Public static
   * nested class is ok.
   */
  public static boolean isBean(TypeRef<?> typeRef) {
    return isBean(typeRef, new TypeResolutionContext(CustomTypeRegistry.EMPTY));
  }

  public static boolean isBean(Class<?> clz, TypeResolutionContext ctx) {
    return isBean(TypeRef.of(clz), ctx);
  }

  public static boolean isBean(TypeRef<?> typeRef, TypeResolutionContext ctx) {
    Class<?> cls = getRawType(typeRef);
    if (ctx.isSynthesizeInterfaces()
        && (RecordUtils.isRecord(cls) || isSynthesizableInterface(cls))) {
      return true;
    }
    if (Modifier.isAbstract(cls.getModifiers()) || Modifier.isInterface(cls.getModifiers())) {
      return false;
    }
    if (ctx.getWalkedTypePath().contains(typeRef)
        || ctx.getCustomTypeRegistry().isExtraSupportedType(typeRef)) {
      return false;
    }
    // if ReflectionUtils.hasNoArgConstructor(cls) return false, we use Unsafe to create object.
    // bean class can be static nested class, but can't be not a non-static inner class
    if (cls.getEnclosingClass() != null && !Modifier.isStatic(cls.getModifiers())) {
      return false;
    }
    TypeResolutionContext newTypePath = ctx.appendTypePath(typeRef);
    if (cls == Object.class) {
      // return false for typeToken that point to un-specialized generic type.
      return false;
    }
    boolean maybe =
        !SUPPORTED_TYPES.contains(typeRef)
            && !typeRef.isArray()
            && !cls.isEnum()
            && !ITERABLE_TYPE.isSupertypeOf(typeRef)
            && !MAP_TYPE.isSupertypeOf(typeRef);
    if (maybe) {
      for (Descriptor d : Descriptor.getDescriptors(cls)) {
        TypeRef<?> t = d.getTypeRef();
        // do field modifiers and getter/setter validation here, not in getDescriptors.
        // If Modifier.isFinal(d.getModifiers()), use reflection
        // private field that doesn't have getter/setter will be handled by reflection.
        TypeRef<?> replacementType =
            ctx.getCustomTypeRegistry().replacementTypeFor(cls, t.getRawType());
        if (replacementType != null) {
          t = replacementType;
        }
        if (!isSupported(t, newTypePath)) {
          return false;
        }
      }
      return true;
    } else {
      return false;
    }
  }

  /**
   * Check if a class is one of {@link Optional}, {@link OptionalInt}, {@link OptionalLong}, or
   * {@link OptionalDouble}.
   */
  public static boolean isOptionalType(Class<?> type) {
    return type == Optional.class
        || type == OptionalInt.class
        || type == OptionalLong.class
        || type == OptionalDouble.class;
  }

  private static boolean isSynthesizableInterface(Class<?> cls) {
    return cls.isInterface()
        && !Collection.class.isAssignableFrom(cls)
        && !Map.class.isAssignableFrom(cls);
  }

  /** Check if <code>typeToken</code> is supported by row-format. */
  public static boolean isSupported(TypeRef<?> typeRef) {
    return isSupported(typeRef, new TypeResolutionContext(CustomTypeRegistry.EMPTY));
  }

  private static boolean isSupported(TypeRef<?> typeRef, TypeResolutionContext ctx) {
    Class<?> cls = getRawType(typeRef);
    if (!Modifier.isPublic(cls.getModifiers())) {
      return false;
    }
    if (cls == Object.class) {
      // return true for typeToken that point to un-specialized generic type, take it as a black
      // box.
      return true;
    }
    TypeRef<?> replacementType =
        ctx.getCustomTypeRegistry()
            .replacementTypeFor(ctx.getEnclosingType().getRawType(), typeRef.getRawType());
    if (replacementType != null) {
      return isSupported(replacementType, ctx);
    } else if (SUPPORTED_TYPES.contains(typeRef)
        || ctx.getCustomTypeRegistry().isExtraSupportedType(typeRef)) {
      return true;
    } else if (typeRef.isArray()) {
      return isSupported(Objects.requireNonNull(typeRef.getComponentType()), ctx);
    } else if (ITERABLE_TYPE.isSupertypeOf(typeRef)) {
      TypeRef<?> elementType = getElementType(typeRef);
      boolean isSuperOfArrayList = cls.isAssignableFrom(ArrayList.class);
      boolean isSuperOfHashSet = cls.isAssignableFrom(HashSet.class);
      if ((!isSuperOfArrayList && !isSuperOfHashSet)
          && (cls.isInterface() || Modifier.isAbstract(cls.getModifiers()))
          && !ctx.getCustomTypeRegistry()
              .canConstructCollection(typeRef.getRawType(), elementType.getRawType())) {
        return false;
      }
      return isSupported(elementType, ctx);
    } else if (MAP_TYPE.isSupertypeOf(typeRef)) {
      boolean isSuperOfHashMap = cls.isAssignableFrom(HashMap.class);
      if (!isSuperOfHashMap && (cls.isInterface() || Modifier.isAbstract(cls.getModifiers()))) {
        return false;
      }
      Tuple2<TypeRef<?>, TypeRef<?>> mapKeyValueType = getMapKeyValueType(typeRef);
      return isSupported(mapKeyValueType.f0) && isSupported(mapKeyValueType.f1);
    } else if (cls.isEnum()) {
      return true;
    } else {
      ctx.checkNoCycle(typeRef);
      return isBean(typeRef, ctx);
    }
  }

  /**
   * listBeansRecursiveInclusive.
   *
   * @param beanClass beanClass
   * @return a bean classes list in this <code>beanClass</code>, all its fields and all type
   *     parameters recursively
   */
  public static LinkedHashSet<Class<?>> listBeansRecursiveInclusive(Class<?> beanClass) {
    return listBeansRecursiveInclusive(beanClass, CustomTypeRegistry.EMPTY);
  }

  public static LinkedHashSet<Class<?>> listBeansRecursiveInclusive(
      Class<?> beanClass, CustomTypeRegistry customTypes) {
    TypeResolutionContext ctx = new TypeResolutionContext(customTypes);
    return listBeansRecursiveInclusive(TypeRef.of(beanClass), ctx);
  }

  public static LinkedHashSet<Class<?>> listBeansRecursiveInclusive(
      Class<?> beanClass, TypeResolutionContext typeCtx) {
    return listBeansRecursiveInclusive(TypeRef.of(beanClass), typeCtx);
  }

  private static LinkedHashSet<Class<?>> listBeansRecursiveInclusive(
      TypeRef<?> typeRef, TypeResolutionContext ctx) {
    LinkedHashSet<Class<?>> beans = new LinkedHashSet<>();
    Class<?> enclosingType = ctx.getEnclosingType().getRawType();
    Class<?> type = typeRef.getRawType();
    TypeRef<?> replacementType =
        ctx.getCustomTypeRegistry().replacementTypeFor(enclosingType, type);
    if (replacementType != null && !replacementType.equals(typeRef)) {
      beans.addAll(listBeansRecursiveInclusive(replacementType, ctx));
      return beans;
    } else if (type == Optional.class) {
      TypeRef<?> elemType = getTypeArguments(typeRef).get(0);
      beans.addAll(listBeansRecursiveInclusive(elemType, ctx));
    } else if (isCollection(type) || Iterable.class == type) {
      TypeRef<?> elementType = getElementType(typeRef);
      beans.addAll(listBeansRecursiveInclusive(elementType, ctx));
    } else if (isMap(type)) {
      Tuple2<TypeRef<?>, TypeRef<?>> mapKeyValueType = getMapKeyValueType(typeRef);
      TypeResolutionContext mapCtx = ctx;
      beans.addAll(listBeansRecursiveInclusive(mapKeyValueType.f0, mapCtx));
      beans.addAll(listBeansRecursiveInclusive(mapKeyValueType.f1, mapCtx));
    } else if (type.isArray()) {
      Class<?> arrayComponent = getArrayComponent(type);
      beans.addAll(listBeansRecursiveInclusive(TypeRef.of(arrayComponent), ctx));
    } else if (isBean(type, ctx)) {
      List<Descriptor> descriptors = Descriptor.getDescriptors(type);
      beans.add(type);
      for (Descriptor descriptor : descriptors) {
        ctx.checkNoCycle(typeRef);
        beans.addAll(
            listBeansRecursiveInclusive(descriptor.getTypeRef(), ctx.appendTypePath(typeRef)));
      }
    }
    return beans;
  }

  public static int computeStringHash(String str) {
    byte[] strBytes = str.getBytes(StandardCharsets.UTF_8);
    long hash = 17;
    for (byte b : strBytes) {
      hash = hash * 31 + b;
      while (hash > Integer.MAX_VALUE) {
        hash = hash / 7;
      }
    }
    return (int) hash;
  }

  /** Returns generic type arguments of <code>typeToken</code>. */
  public static List<TypeRef<?>> getTypeArguments(TypeRef<?> typeRef) {
    if (typeRef.hasExplicitTypeArguments()) {
      return typeRef.getTypeArguments();
    }
    if (typeRef.getType() instanceof ParameterizedType) {
      ParameterizedType parameterizedType = (ParameterizedType) typeRef.getType();
      return Arrays.stream(parameterizedType.getActualTypeArguments())
          .map(TypeRef::of)
          .collect(Collectors.toList());
    } else {
      return new ArrayList<>();
    }
  }

  /**
   * Returns generic type arguments of <code>typeToken</code>, includes generic type arguments of
   * generic type arguments recursively.
   */
  public static List<TypeRef<?>> getAllTypeArguments(TypeRef<?> typeRef) {
    List<TypeRef<?>> types = getTypeArguments(typeRef);
    LinkedHashSet<TypeRef<?>> allTypeArguments = new LinkedHashSet<>(types);
    for (TypeRef<?> type : types) {
      allTypeArguments.addAll(getAllTypeArguments(type));
    }

    return new ArrayList<>(allTypeArguments);
  }

  public static boolean isEnumArray(Class<?> clz) {
    if (!clz.isArray()) {
      return false;
    }
    return getArrayComponent(clz).isEnum();
  }

  public static String qualifiedName(String pkg, String className) {
    if (StringUtils.isBlank(pkg)) {
      return className;
    } else {
      return pkg + "." + className;
    }
  }

  private static Set<Class<?>> leafTypes = new HashSet<>();

  static {
    leafTypes.addAll(sortedPrimitiveClasses);
    leafTypes.addAll(sortedBoxedClasses);
    leafTypes.add(String.class);
    leafTypes.addAll(
        Arrays.asList(
            java.util.Date.class,
            java.sql.Date.class,
            Time.class,
            Timestamp.class,
            LocalDate.class,
            LocalTime.class,
            LocalDateTime.class,
            Instant.class,
            Duration.class,
            ZoneId.class,
            ZoneOffset.class,
            ZonedDateTime.class,
            Year.class,
            YearMonth.class,
            MonthDay.class,
            Period.class,
            OffsetTime.class,
            OffsetDateTime.class,
            Calendar.class,
            GregorianCalendar.class,
            TimeZone.class));
  }

  private static final WeakHashMap<Class<?>, Boolean> hasExpandableLeafsCache = new WeakHashMap<>();

  public static synchronized boolean hasExpandableLeafs(Class<?> cls) {
    return hasExpandableLeafsCache.computeIfAbsent(
        cls,
        k -> {
          if (cls.isEnum()) {
            return false;
          }
          if (leafTypes.contains(cls)) {
            return false;
          }
          List<Field> fields = ReflectionUtils.getFields(cls, true);
          if (fields.isEmpty()) {
            return false;
          }
          for (Field field : fields) {
            Class<?> fieldType = field.getType();
            if (!leafTypes.contains(fieldType) && !fieldType.isEnum()) {
              return true;
            }
          }
          return false;
        });
  }
}

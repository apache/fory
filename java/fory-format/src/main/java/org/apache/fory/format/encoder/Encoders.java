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

package org.apache.fory.format.encoder;

import static org.apache.fory.type.TypeUtils.OBJECT_TYPE;
import static org.apache.fory.type.TypeUtils.getRawType;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.apache.fory.Fory;
import org.apache.fory.builder.CodecBuilder;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.CompileUnit;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.format.row.binary.writer.BinaryRowWriter;
import org.apache.fory.format.type.CustomTypeEncoderRegistry;
import org.apache.fory.format.type.CustomTypeRegistration;
import org.apache.fory.format.type.TypeInference;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.TypeResolutionContext;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.util.Preconditions;

/**
 * Factory to create {@link Encoder}.
 *
 * <p>, ganrunsheng
 */
public class Encoders {
  private static final Logger LOG = LoggerFactory.getLogger(Encoders.class);

  /** Build a row codec with configurable options through a builder. */
  public static <T> RowCodecBuilder<T> buildBeanCodec(Class<T> beanClass) {
    return new RowCodecBuilder<>(beanClass);
  }

  /** Build an array codec with configurable options through a builder. */
  public static <C extends Collection<?>> ArrayCodecBuilder<C> buildArrayCodec(
      TypeRef<C> collectionType) {
    return new ArrayCodecBuilder<>(collectionType);
  }

  /** Build a map codec with configurable options through a builder. */
  public static <M extends Map<?, ?>> MapCodecBuilder<M> buildMapCodec(TypeRef<M> mapType) {
    return new MapCodecBuilder<>(mapType);
  }

  public static <T> RowEncoder<T> bean(Class<T> beanClass) {
    return bean(beanClass, 16);
  }

  public static <T> RowEncoder<T> bean(Class<T> beanClass, int initialBufferSize) {
    return bean(beanClass, null, initialBufferSize);
  }

  public static <T> RowEncoder<T> bean(Class<T> beanClass, Fory fory) {
    return bean(beanClass, fory, 16);
  }

  public static <T> RowEncoder<T> bean(Class<T> beanClass, Fory fory, int initialBufferSize) {
    return buildBeanCodec(beanClass).fory(fory).initialBufferSize(initialBufferSize).build().get();
  }

  public static <T> RowEncoder<T> bean(Class<T> beanClass, BinaryRowWriter writer) {
    return bean(beanClass, writer, null);
  }

  /**
   * Creates an encoder for Java Bean of type T.
   *
   * <p>T must be publicly accessible.
   *
   * <p>supported types for java bean field:
   *
   * <ul>
   *   <li>primitive types: boolean, int, double, etc.
   *   <li>boxed types: Boolean, Integer, Double, etc.
   *   <li>String
   *   <li>Enum (as String)
   *   <li>java.math.BigDecimal, java.math.BigInteger
   *   <li>time related: java.sql.Date, java.sql.Timestamp, java.time.LocalDate, java.time.Instant
   *   <li>Optional and friends: OptionalInt, OptionalLong, OptionalDouble
   *   <li>collection types: only array and java.util.List currently, map support is in progress
   *   <li>record types
   *   <li>nested java bean
   * </ul>
   */
  public static <T> RowEncoder<T> bean(Class<T> beanClass, BinaryRowWriter writer, Fory fory) {
    return buildBeanCodec(beanClass).fory(fory).buildForWriter().apply(writer);
  }

  /**
   * Register a custom codec handling a given type, when it is enclosed in the given beanType.
   *
   * @param beanType the enclosing type to limit this custom codec to
   * @param type the type of field to handle
   * @param codec the codec to use
   */
  public static <T> void registerCustomCodec(
      Class<?> beanType, Class<T> type, CustomCodec<T, ?> codec) {
    TypeInference.registerCustomCodec(new CustomTypeRegistration(beanType, type), codec);
  }

  /**
   * Register a custom codec handling a given type.
   *
   * @param type the type of field to handle
   * @param codec the codec to use
   */
  public static <T> void registerCustomCodec(Class<T> type, CustomCodec<T, ?> codec) {
    registerCustomCodec(Object.class, type, codec);
  }

  /**
   * Register a custom collection factory for a given collection and element type.
   *
   * @param collectionType the type of collection to handle
   * @param elementType the type of element in the collection
   * @param factory the factory to use
   */
  public static <E, C extends Collection<E>> void registerCustomCollectionFactory(
      Class<?> collectionType, Class<E> elementType, CustomCollectionFactory<E, C> factory) {
    TypeInference.registerCustomCollectionFactory(collectionType, elementType, factory);
  }

  /**
   * Supported nested list format. For instance, nest collection can be expressed as Collection in
   * Collection. Input param must explicit specified type, like this: <code>
   * new TypeToken</code> instance with Collection in Collection type.
   *
   * @param token TypeToken instance which explicit specified the type.
   * @param <T> T is a array type, can be a nested list type.
   * @return
   */
  public static <T extends Collection<?>> ArrayEncoder<T> arrayEncoder(TypeRef<T> token) {
    return arrayEncoder(token, null);
  }

  public static <T extends Collection<?>> ArrayEncoder<T> arrayEncoder(
      TypeRef<T> token, Fory fory) {
    return buildArrayCodec(token).fory(fory).build().get();
  }

  /**
   * Supported nested map format. For instance, nest map can be expressed as Map in Map. Input param
   * must explicit specified type, like this: <code>
   * new TypeToken</code> instance with Collection in Collection type.
   *
   * @param token TypeToken instance which explicit specified the type.
   * @param <T> T is a array type, can be a nested list type.
   * @return
   */
  public static <T extends Map> MapEncoder<T> mapEncoder(TypeRef<T> token) {
    return mapEncoder(token, null);
  }

  /**
   * The underlying implementation uses array, only supported {@link Map} format, because generic
   * type such as List is erased to simply List, so a bean class input param is required.
   *
   * @return
   */
  @SuppressWarnings("unchecked")
  public static <T extends Map, K, V> MapEncoder<T> mapEncoder(
      Class<? extends Map> mapCls, Class<K> keyType, Class<V> valueType) {
    Preconditions.checkNotNull(keyType);
    Preconditions.checkNotNull(valueType);

    return (MapEncoder<T>) mapEncoder(TypeUtils.mapOf(keyType, valueType), null);
  }

  @SuppressWarnings("unchecked")
  public static <T extends Map<K, V>, K, V> MapEncoder<T> mapEncoder(TypeRef<T> token, Fory fory) {
    Preconditions.checkNotNull(token);
    final Tuple2<TypeRef<?>, TypeRef<?>> tuple2 = TypeUtils.getMapKeyValueType(token);

    final Set<TypeRef<?>> set1 = beanSet(tuple2.f0);
    final Set<TypeRef<?>> set2 = beanSet(tuple2.f1);
    LOG.info("Find beans to load: {}, {}", set1, set2);

    final TypeRef<K> keyToken =
        (TypeRef<K>) token4BeanLoad(set1, tuple2.f0, DefaultCodecFormat.INSTANCE);
    final TypeRef<V> valToken =
        (TypeRef<V>) token4BeanLoad(set2, tuple2.f1, DefaultCodecFormat.INSTANCE);

    return mapEncoder0(token, keyToken, valToken, fory);
  }

  /**
   * Creates an encoder for Java Bean of type T.
   *
   * <p>T must be publicly accessible.
   *
   * <p>supported types for java bean field: - primitive types: boolean, int, double, etc. - boxed
   * types: Boolean, Integer, Double, etc. - String - java.math.BigDecimal, java.math.BigInteger -
   * time related: java.sql.Date, java.sql.Timestamp, java.time.LocalDate, java.time.Instant -
   * collection types: only array and java.util.List currently, map support is in progress - nested
   * java bean.
   */
  public static <T extends Map<K, V>, K, V> MapEncoder<T> mapEncoder(
      TypeRef<T> mapToken, TypeRef<K> keyToken, TypeRef<V> valToken, Fory fory) {
    Preconditions.checkNotNull(mapToken);
    Preconditions.checkNotNull(keyToken);
    Preconditions.checkNotNull(valToken);
    return mapEncoder0(mapToken, keyToken, valToken, fory);
  }

  private static <T extends Map<K, V>, K, V> MapEncoder<T> mapEncoder0(
      TypeRef<T> mapToken, TypeRef<K> keyToken, TypeRef<V> valToken, Fory fory) {
    Preconditions.checkNotNull(mapToken);
    Preconditions.checkNotNull(keyToken);
    Preconditions.checkNotNull(valToken);
    return buildMapCodec(mapToken).fory(fory).build().get();
  }

  static void loadMapCodecs(TypeRef<?> type, Encoding codecFactory) {
    token4BeanLoad(beanSet(type), type, codecFactory);
  }

  private static Set<TypeRef<?>> beanSet(TypeRef<?> token) {
    Set<TypeRef<?>> set = new HashSet<>();
    if (TypeUtils.isBean(
        token, new TypeResolutionContext(CustomTypeEncoderRegistry.customTypeHandler(), true))) {
      set.add(token);
      return set;
    }
    findBeanToken(token, set);
    return set;
  }

  private static TypeRef<?> token4BeanLoad(
      Set<TypeRef<?>> set, TypeRef<?> init, Encoding codecFactory) {
    TypeRef<?> keyToken = init;
    for (TypeRef<?> tt : set) {
      keyToken = tt;
      Encoders.loadOrGenRowCodecClass(getRawType(tt), codecFactory);
      LOG.info("bean {} load finished", getRawType(tt));
    }
    return keyToken;
  }

  static void findBeanToken(TypeRef<?> typeRef, final Set<TypeRef<?>> set) {
    TypeResolutionContext typeCtx =
        new TypeResolutionContext(CustomTypeEncoderRegistry.customTypeHandler(), true);
    Set<TypeRef<?>> visited = new LinkedHashSet<>();
    while (TypeUtils.ITERABLE_TYPE.isSupertypeOf(typeRef)
        || TypeUtils.MAP_TYPE.isSupertypeOf(typeRef)) {
      if (visited.contains(typeRef)) {
        return;
      }
      visited.add(typeRef);
      if (TypeUtils.ITERABLE_TYPE.isSupertypeOf(typeRef)) {
        typeRef = TypeUtils.getElementType(typeRef);
        if (TypeUtils.isBean(typeRef, typeCtx)) {
          set.add(typeRef);
        }
        findBeanToken(typeRef, set);
      } else {
        Tuple2<TypeRef<?>, TypeRef<?>> tuple2 = TypeUtils.getMapKeyValueType(typeRef);
        if (TypeUtils.isBean(tuple2.f0, typeCtx)) {
          set.add(tuple2.f0);
        } else {
          typeRef = tuple2.f0;
          findBeanToken(tuple2.f0, set);
        }

        if (TypeUtils.isBean(tuple2.f1, typeCtx)) {
          set.add(tuple2.f1);
        } else {
          typeRef = tuple2.f1;
          findBeanToken(tuple2.f1, set);
        }
      }
    }
  }

  static Class<?> loadOrGenRowCodecClass(Class<?> beanClass, Encoding codecFactory) {
    Set<Class<?>> classes =
        TypeUtils.listBeansRecursiveInclusive(
            beanClass,
            new TypeResolutionContext(CustomTypeEncoderRegistry.customTypeHandler(), true));
    if (classes.isEmpty()) {
      return null;
    }
    LOG.info("Create codec for classes {}", classes);
    CompileUnit[] compileUnits =
        classes.stream()
            .map(
                cls -> {
                  final CodecBuilder codecBuilder = codecFactory.newRowEncoder(TypeRef.of(cls));
                  // use genCodeFunc to avoid gen code repeatedly
                  return new CompileUnit(
                      CodeGenerator.getPackage(cls),
                      codecBuilder.codecClassName(cls),
                      codecBuilder::genCode);
                })
            .toArray(CompileUnit[]::new);
    return loadCls(compileUnits);
  }

  static <B> Class<?> loadOrGenArrayCodecClass(
      TypeRef<? extends Collection<?>> arrayCls, TypeRef<B> elementType, Encoding codecFactory) {
    LOG.info("Create ArrayCodec for classes {}", elementType);
    Class<?> cls = getRawType(elementType);
    // class name prefix
    String prefix = TypeInference.inferTypeName(arrayCls);

    ArrayEncoderBuilder codecBuilder = codecFactory.newArrayEncoder(arrayCls, elementType);
    CompileUnit compileUnit =
        new CompileUnit(
            CodeGenerator.getPackage(cls),
            codecBuilder.codecClassName(cls, prefix),
            codecBuilder::genCode);

    return loadCls(compileUnit);
  }

  static <K, V> Class<?> loadOrGenMapCodecClass(
      TypeRef<? extends Map<?, ?>> mapCls,
      TypeRef<K> keyToken,
      TypeRef<V> valueToken,
      Encoding codecFactory) {
    LOG.info("Create MapCodec for classes {}, {}", keyToken, valueToken);
    boolean keyIsBean = TypeUtils.isBean(keyToken);
    boolean valIsBean = TypeUtils.isBean(valueToken);
    TypeRef<?> beanToken;
    Class<?> cls;
    if (keyIsBean) {
      cls = getRawType(keyToken);
      beanToken = keyToken;
    } else if (valIsBean) {
      cls = getRawType(valueToken);
      beanToken = valueToken;
    } else {
      cls = Object.class;
      beanToken = OBJECT_TYPE;
    }
    // class name prefix
    String prefix = TypeInference.inferTypeName(mapCls);

    MapEncoderBuilder codecBuilder = codecFactory.newMapEncoder(mapCls, beanToken);
    CompileUnit compileUnit =
        new CompileUnit(
            CodeGenerator.getPackage(cls),
            codecBuilder.codecClassName(cls, prefix),
            codecBuilder::genCode);

    return loadCls(compileUnit);
  }

  private static Class<?> loadCls(CompileUnit... compileUnit) {
    CodeGenerator codeGenerator =
        CodeGenerator.getSharedCodeGenerator(Thread.currentThread().getContextClassLoader());
    ClassLoader classLoader = codeGenerator.compile(compileUnit);
    String className = compileUnit[0].getQualifiedClassName();
    try {
      return classLoader.loadClass(className);
    } catch (final ClassNotFoundException e) {
      throw new IllegalStateException("Impossible because we just compiled class", e);
    }
  }
}

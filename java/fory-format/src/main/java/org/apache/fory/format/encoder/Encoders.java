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
import org.apache.arrow.util.Preconditions;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.fory.Fory;
import org.apache.fory.builder.CodecBuilder;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.CompileUnit;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.format.row.binary.BinaryArray;
import org.apache.fory.format.row.binary.BinaryMap;
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.format.row.binary.writer.BinaryRowWriter;
import org.apache.fory.format.type.CustomTypeEncoderRegistry;
import org.apache.fory.format.type.CustomTypeRegistration;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.TypeInference;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.TypeResolutionContext;
import org.apache.fory.type.TypeUtils;

/**
 * Factory to create {@link Encoder}.
 *
 * <p>, ganrunsheng
 */
public class Encoders {
  private static final Logger LOG = LoggerFactory.getLogger(Encoders.class);

  /** Build a row codec with configurable options through a builder. */
  public static <T> BeanCodecBuilder<T> buildBeanCodec(final Class<T> beanClass) {
    return new BeanCodecBuilder<>(beanClass);
  }

  /** Build an array codec with configurable options through a builder. */
  public static <C extends Collection<?>> ArrayCodecBuilder<C> buildArrayCodec(
      final TypeRef<C> collectionType) {
    return new ArrayCodecBuilder<>(collectionType);
  }

  public static <T> RowEncoder<T> bean(final Class<T> beanClass) {
    return bean(beanClass, 16);
  }

  public static <T> RowEncoder<T> bean(final Class<T> beanClass, final int initialBufferSize) {
    return bean(beanClass, null, initialBufferSize);
  }

  public static <T> RowEncoder<T> bean(final Class<T> beanClass, final Fory fory) {
    return bean(beanClass, fory, 16);
  }

  public static <T> RowEncoder<T> bean(
      final Class<T> beanClass, final Fory fory, final int initialBufferSize) {
    return buildBeanCodec(beanClass).fory(fory).initialBufferSize(initialBufferSize).build().get();
  }

  public static <T> RowEncoder<T> bean(final Class<T> beanClass, final BinaryRowWriter writer) {
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
  public static <T> RowEncoder<T> bean(
      final Class<T> beanClass, final BinaryRowWriter writer, final Fory fory) {
    return buildBeanCodec(beanClass).fory(fory).buildForCustomWriter().apply(writer);
  }

  /**
   * Register a custom codec handling a given type, when it is enclosed in the given beanType.
   *
   * @param beanType the enclosing type to limit this custom codec to
   * @param type the type of field to handle
   * @param codec the codec to use
   */
  public static <T> void registerCustomCodec(
      final Class<?> beanType, final Class<T> type, final CustomCodec<T, ?> codec) {
    TypeInference.registerCustomCodec(new CustomTypeRegistration(beanType, type), codec);
  }

  /**
   * Register a custom codec handling a given type.
   *
   * @param type the type of field to handle
   * @param codec the codec to use
   */
  public static <T> void registerCustomCodec(final Class<T> type, final CustomCodec<T, ?> codec) {
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
      final Class<?> collectionType,
      final Class<E> elementType,
      final CustomCollectionFactory<E, C> factory) {
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
  public static <T extends Collection<?>> ArrayEncoder<T> arrayEncoder(final TypeRef<T> token) {
    return arrayEncoder(token, null);
  }

  public static <T extends Collection<?>> ArrayEncoder<T> arrayEncoder(
      final TypeRef<T> token, final Fory fory) {
    return buildArrayCodec(token).fory(fory).buildForArray().get();
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
  public static <T extends Map> MapEncoder<T> mapEncoder(final TypeRef<T> token) {
    return mapEncoder(token, null);
  }

  /**
   * The underlying implementation uses array, only supported {@link Map} format, because generic
   * type such as List is erased to simply List, so a bean class input param is required.
   *
   * @return
   */
  public static <T extends Map, K, V> MapEncoder<T> mapEncoder(
      final Class<? extends Map> mapCls, final Class<K> keyType, final Class<V> valueType) {
    Preconditions.checkNotNull(keyType);
    Preconditions.checkNotNull(valueType);

    return (MapEncoder<T>) mapEncoder(TypeUtils.mapOf(keyType, valueType), null);
  }

  public static <T extends Map> MapEncoder<T> mapEncoder(final TypeRef<T> token, final Fory fory) {
    Preconditions.checkNotNull(token);
    final Tuple2<TypeRef<?>, TypeRef<?>> tuple2 = TypeUtils.getMapKeyValueType(token);

    final Set<TypeRef<?>> set1 = beanSet(tuple2.f0);
    final Set<TypeRef<?>> set2 = beanSet(tuple2.f1);
    LOG.info("Find beans to load: {}, {}", set1, set2);

    final TypeRef<?> keyToken = token4BeanLoad(set1, tuple2.f0);
    final TypeRef<?> valToken = token4BeanLoad(set2, tuple2.f1);

    final MapEncoder<T> encoder = mapEncoder0(token, keyToken, valToken, fory);
    return createMapEncoder(encoder);
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
  public static <T extends Map, K, V> MapEncoder<T> mapEncoder(
      final TypeRef<? extends Map> mapToken,
      final TypeRef<K> keyToken,
      final TypeRef<V> valToken,
      final Fory fory) {
    Preconditions.checkNotNull(mapToken);
    Preconditions.checkNotNull(keyToken);
    Preconditions.checkNotNull(valToken);

    final Set<TypeRef<?>> set1 = beanSet(keyToken);
    final Set<TypeRef<?>> set2 = beanSet(valToken);
    LOG.info("Find beans to load: {}, {}", set1, set2);

    token4BeanLoad(set1, keyToken);
    token4BeanLoad(set2, valToken);

    return mapEncoder0(mapToken, keyToken, valToken, fory);
  }

  private static <T extends Map, K, V> MapEncoder<T> mapEncoder0(
      final TypeRef<? extends Map> mapToken,
      final TypeRef<K> keyToken,
      final TypeRef<V> valToken,
      final Fory fory) {
    Preconditions.checkNotNull(mapToken);
    Preconditions.checkNotNull(keyToken);
    Preconditions.checkNotNull(valToken);

    final Schema schema = TypeInference.inferSchema(mapToken, false);
    final Field field = DataTypes.fieldOfSchema(schema, 0);
    final Field keyField = DataTypes.keyArrayFieldForMap(field);
    final Field valField = DataTypes.itemArrayFieldForMap(field);
    final BinaryArrayWriter keyWriter = new BinaryArrayWriter(keyField);
    final BinaryArrayWriter valWriter = new BinaryArrayWriter(valField, keyWriter.getBuffer());
    try {
      final Class<?> rowCodecClass = loadOrGenMapCodecClass(mapToken, keyToken, valToken);
      final Object references =
          new Object[] {keyField, valField, keyWriter, valWriter, fory, field};
      final GeneratedMapEncoder codec =
          rowCodecClass
              .asSubclass(GeneratedMapEncoder.class)
              .getConstructor(Object[].class)
              .newInstance(references);

      return new MapEncoder<T>() {
        @Override
        public Field keyField() {
          return keyField;
        }

        @Override
        public Field valueField() {
          return valField;
        }

        @SuppressWarnings("unchecked")
        @Override
        public T fromMap(final BinaryArray key, final BinaryArray value) {
          return (T) codec.fromMap(key, value);
        }

        @Override
        public BinaryMap toMap(final T obj) {
          return codec.toMap(obj);
        }

        @Override
        public T decode(final MemoryBuffer buffer) {
          return decode(buffer, buffer.readInt32());
        }

        public T decode(final MemoryBuffer buffer, final int size) {
          final BinaryMap map = new BinaryMap(field);
          final int readerIndex = buffer.readerIndex();
          map.pointTo(buffer, readerIndex, size);
          buffer.readerIndex(readerIndex + size);
          return fromMap(map);
        }

        @Override
        public T decode(final byte[] bytes) {
          return decode(MemoryUtils.wrap(bytes), bytes.length);
        }

        @Override
        public byte[] encode(final T obj) {
          final BinaryMap map = toMap(obj);
          return map.getBuf().getBytes(map.getBaseOffset(), map.getSizeInBytes());
        }

        @Override
        public void encode(final MemoryBuffer buffer, final T obj) {
          final MemoryBuffer prevBuffer = keyWriter.getBuffer();
          final int writerIndex = buffer.writerIndex();
          buffer.writeInt32(-1);
          try {
            keyWriter.setBuffer(buffer);
            valWriter.setBuffer(buffer);
            toMap(obj);
            buffer.putInt32(writerIndex, buffer.writerIndex() - writerIndex - 4);
          } finally {
            keyWriter.setBuffer(prevBuffer);
            valWriter.setBuffer(prevBuffer);
          }
        }
      };
    } catch (final Exception e) {
      final String msg =
          String.format("Create encoder failed, \nkeyType: %s, valueType: %s", keyToken, valToken);
      throw new EncoderException(msg, e);
    }
  }

  private static Set<TypeRef<?>> beanSet(final TypeRef<?> token) {
    final Set<TypeRef<?>> set = new HashSet<>();
    if (TypeUtils.isBean(
        token, new TypeResolutionContext(CustomTypeEncoderRegistry.customTypeHandler(), true))) {
      set.add(token);
      return set;
    }
    findBeanToken(token, set);
    return set;
  }

  private static TypeRef<?> token4BeanLoad(final Set<TypeRef<?>> set, final TypeRef<?> init) {
    TypeRef<?> keyToken = init;
    for (final TypeRef<?> tt : set) {
      keyToken = tt;
      Encoders.loadOrGenRowCodecClass(getRawType(tt), DefaultCodecFactory.INSTANCE);
      LOG.info("bean {} load finished", getRawType(tt));
    }
    return keyToken;
  }

  private static <T> MapEncoder<T> createMapEncoder(final MapEncoder<T> encoder) {
    return new MapEncoder<T>() {

      @Override
      public Field keyField() {
        return encoder.keyField();
      }

      @Override
      public Field valueField() {
        return encoder.valueField();
      }

      @Override
      public T fromMap(final BinaryArray key, final BinaryArray value) {
        return encoder.fromMap(key, value);
      }

      @Override
      public BinaryMap toMap(final T obj) {
        return encoder.toMap(obj);
      }

      @Override
      public T decode(final MemoryBuffer buffer) {
        return encoder.decode(buffer);
      }

      @Override
      public T decode(final byte[] bytes) {
        return encoder.decode(bytes);
      }

      @Override
      public byte[] encode(final T obj) {
        return encoder.encode(obj);
      }

      @Override
      public void encode(final MemoryBuffer buffer, final T obj) {
        encoder.encode(buffer, obj);
      }
    };
  }

  static void findBeanToken(TypeRef<?> typeRef, final java.util.Set<TypeRef<?>> set) {
    final TypeResolutionContext typeCtx =
        new TypeResolutionContext(CustomTypeEncoderRegistry.customTypeHandler(), true);
    final Set<TypeRef<?>> visited = new LinkedHashSet<>();
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
        final Tuple2<TypeRef<?>, TypeRef<?>> tuple2 = TypeUtils.getMapKeyValueType(typeRef);
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

  static Class<?> loadOrGenRowCodecClass(
      final Class<?> beanClass, final CodecFactory codecFactory) {
    final Set<Class<?>> classes =
        TypeUtils.listBeansRecursiveInclusive(
            beanClass,
            new TypeResolutionContext(CustomTypeEncoderRegistry.customTypeHandler(), true));
    if (classes.isEmpty()) {
      return null;
    }
    LOG.info("Create codec for classes {}", classes);
    final CompileUnit[] compileUnits =
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
      final TypeRef<? extends Collection<?>> arrayCls,
      final TypeRef<B> elementType,
      final CodecFactory codecFactory) {
    LOG.info("Create ArrayCodec for classes {}", elementType);
    final Class<?> cls = getRawType(elementType);
    // class name prefix
    final String prefix = TypeInference.inferTypeName(arrayCls);

    final ArrayEncoderBuilder codecBuilder = codecFactory.newArrayEncoder(arrayCls, elementType);
    final CompileUnit compileUnit =
        new CompileUnit(
            CodeGenerator.getPackage(cls),
            codecBuilder.codecClassName(cls, prefix),
            codecBuilder::genCode);

    return loadCls(compileUnit);
  }

  private static <K, V> Class<?> loadOrGenMapCodecClass(
      final TypeRef<? extends Map> mapCls, final TypeRef<K> keyToken, final TypeRef<V> valueToken) {
    LOG.info("Create MapCodec for classes {}, {}", keyToken, valueToken);
    final boolean keyIsBean = TypeUtils.isBean(keyToken);
    final boolean valIsBean = TypeUtils.isBean(valueToken);
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
    final String prefix = TypeInference.inferTypeName(mapCls);

    final MapEncoderBuilder codecBuilder = new MapEncoderBuilder(mapCls, beanToken);
    final CompileUnit compileUnit =
        new CompileUnit(
            CodeGenerator.getPackage(cls),
            codecBuilder.codecClassName(cls, prefix),
            codecBuilder::genCode);

    return loadCls(compileUnit);
  }

  private static Class<?> loadCls(final CompileUnit... compileUnit) {
    final CodeGenerator codeGenerator =
        CodeGenerator.getSharedCodeGenerator(Thread.currentThread().getContextClassLoader());
    final ClassLoader classLoader = codeGenerator.compile(compileUnit);
    final String className = compileUnit[0].getQualifiedClassName();
    try {
      return classLoader.loadClass(className);
    } catch (final ClassNotFoundException e) {
      throw new IllegalStateException("Impossible because we just compiled class", e);
    }
  }
}

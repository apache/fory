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
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.CompileUnit;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.exception.ClassNotCompatibleException;
import org.apache.fory.format.row.binary.BinaryArray;
import org.apache.fory.format.row.binary.BinaryMap;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.format.row.binary.writer.BinaryRowWriter;
import org.apache.fory.format.type.CustomTypeEncoderRegistry;
import org.apache.fory.format.type.CustomTypeRegistration;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.TypeInference;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
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
    Schema schema = TypeInference.inferSchema(beanClass);
    BinaryRowWriter writer = new BinaryRowWriter(schema);
    RowEncoder<T> encoder = bean(beanClass, writer, fory);
    return new RowEncoder<T>() {

      @Override
      public Schema schema() {
        return encoder.schema();
      }

      @Override
      public T fromRow(BinaryRow row) {
        return encoder.fromRow(row);
      }

      @Override
      public BinaryRow toRow(T obj) {
        writer.setBuffer(MemoryBuffer.buffer(initialBufferSize));
        writer.reset();
        return encoder.toRow(obj);
      }

      @Override
      public T decode(MemoryBuffer buffer) {
        return encoder.decode(buffer);
      }

      @Override
      public T decode(byte[] bytes) {
        return encoder.decode(bytes);
      }

      @Override
      public byte[] encode(T obj) {
        return encoder.encode(obj);
      }

      @Override
      public void encode(MemoryBuffer buffer, T obj) {
        encoder.encode(buffer, obj);
      }
    };
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
    Schema schema = writer.getSchema();

    try {
      Class<?> rowCodecClass = loadOrGenRowCodecClass(beanClass);
      Object references = new Object[] {schema, writer, fory};
      GeneratedRowEncoder codec =
          rowCodecClass
              .asSubclass(GeneratedRowEncoder.class)
              .getConstructor(Object[].class)
              .newInstance(references);
      long schemaHash = DataTypes.computeSchemaHash(schema);

      return new RowEncoder<T>() {
        private final MemoryBuffer buffer = MemoryBuffer.buffer(16);

        @Override
        public Schema schema() {
          return schema;
        }

        @SuppressWarnings("unchecked")
        @Override
        public T fromRow(BinaryRow row) {
          return (T) codec.fromRow(row);
        }

        @Override
        public BinaryRow toRow(T obj) {
          return codec.toRow(obj);
        }

        @Override
        public T decode(MemoryBuffer buffer) {
          return decode(buffer, buffer.readInt32());
        }

        public T decode(MemoryBuffer buffer, int size) {
          long peerSchemaHash = buffer.readInt64();
          if (peerSchemaHash != schemaHash) {
            throw new ClassNotCompatibleException(
                String.format(
                    "Schema is not consistent, encoder schema is %s. "
                        + "self/peer schema hash are %s/%s. "
                        + "Please check writer schema.",
                    schema, schemaHash, peerSchemaHash));
          }
          BinaryRow row = new BinaryRow(schema);
          row.pointTo(buffer, buffer.readerIndex(), size);
          buffer.increaseReaderIndex(size - 8);
          return fromRow(row);
        }

        @Override
        public T decode(byte[] bytes) {
          return decode(MemoryBuffer.wrap(bytes), bytes.length);
        }

        @Override
        public byte[] encode(T obj) {
          buffer.writerIndex(0);
          buffer.writeInt64(schemaHash);
          writer.setBuffer(buffer);
          writer.reset();
          BinaryRow row = toRow(obj);
          return buffer.getBytes(0, 8 + row.getSizeInBytes());
        }

        @Override
        public void encode(MemoryBuffer buffer, T obj) {
          int writerIndex = buffer.writerIndex();
          buffer.writeInt32(-1);
          try {
            buffer.writeInt64(schemaHash);
            writer.setBuffer(buffer);
            writer.reset();
            toRow(obj);
            buffer.putInt32(writerIndex, buffer.writerIndex() - writerIndex - 4);
          } finally {
            writer.setBuffer(this.buffer);
          }
        }
      };
    } catch (Exception e) {
      String msg = String.format("Create encoder failed, \nbeanClass: %s", beanClass);
      throw new EncoderException(msg, e);
    }
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
  public static <T extends Collection> ArrayEncoder<T> arrayEncoder(TypeRef<T> token) {
    return arrayEncoder(token, null);
  }

  public static <T extends Collection> ArrayEncoder<T> arrayEncoder(TypeRef<T> token, Fory fory) {
    Schema schema = TypeInference.inferSchema(token, false);
    Field field = DataTypes.fieldOfSchema(schema, 0);
    BinaryArrayWriter writer = new BinaryArrayWriter(field);

    Set<TypeRef<?>> set = new HashSet<>();
    findBeanToken(token, set);
    if (set.isEmpty()) {
      throw new IllegalArgumentException("can not find bean class.");
    }

    TypeRef<?> typeRef = null;
    for (TypeRef<?> tt : set) {
      typeRef = set.iterator().next();
      Encoders.loadOrGenRowCodecClass(getRawType(tt));
    }
    ArrayEncoder<T> encoder = arrayEncoder(token, typeRef, writer, fory);
    return new ArrayEncoder<T>() {

      @Override
      public Field field() {
        return encoder.field();
      }

      @Override
      public T fromArray(BinaryArray array) {
        return encoder.fromArray(array);
      }

      @Override
      public BinaryArray toArray(T obj) {
        return encoder.toArray(obj);
      }

      @Override
      public T decode(MemoryBuffer buffer) {
        return encoder.decode(buffer);
      }

      @Override
      public T decode(byte[] bytes) {
        return encoder.decode(bytes);
      }

      @Override
      public byte[] encode(T obj) {
        return encoder.encode(obj);
      }

      @Override
      public void encode(MemoryBuffer buffer, T obj) {
        encoder.encode(buffer, obj);
      }
    };
  }

  /**
   * The underlying implementation uses array, only supported {@link Collection} format, because
   * generic type such as List is erased to simply List, so a bean class input param is required.
   *
   * @return
   */
  public static <T extends Collection, B> ArrayEncoder<T> arrayEncoder(
      Class<? extends Collection> arrayCls, Class<B> elementType) {
    Preconditions.checkNotNull(elementType);

    return (ArrayEncoder<T>) arrayEncoder(TypeUtils.collectionOf(elementType), null);
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
  public static <T extends Collection, B> ArrayEncoder<T> arrayEncoder(
      TypeRef<? extends Collection> arrayToken,
      TypeRef<B> elementType,
      BinaryArrayWriter writer,
      Fory fory) {
    Field field = writer.getField();
    try {
      Class<?> rowCodecClass = loadOrGenArrayCodecClass(arrayToken, elementType);
      Object references = new Object[] {field, writer, fory};
      GeneratedArrayEncoder codec =
          rowCodecClass
              .asSubclass(GeneratedArrayEncoder.class)
              .getConstructor(Object[].class)
              .newInstance(references);

      return new ArrayEncoder<T>() {

        @Override
        public Field field() {
          return field;
        }

        @SuppressWarnings("unchecked")
        @Override
        public T fromArray(BinaryArray array) {
          return (T) codec.fromArray(array);
        }

        @Override
        public BinaryArray toArray(T obj) {
          return codec.toArray(obj);
        }

        @Override
        public T decode(MemoryBuffer buffer) {
          return decode(buffer, buffer.readInt32());
        }

        public T decode(MemoryBuffer buffer, int size) {
          BinaryArray array = new BinaryArray(field);
          int readerIndex = buffer.readerIndex();
          array.pointTo(buffer, readerIndex, size);
          buffer.readerIndex(readerIndex + size);
          return fromArray(array);
        }

        @Override
        public T decode(byte[] bytes) {
          return decode(MemoryBuffer.wrap(bytes), bytes.length);
        }

        @Override
        public byte[] encode(T obj) {
          BinaryArray array = toArray(obj);
          return writer.getBuffer().getBytes(0, 8 + array.getSizeInBytes());
        }

        @Override
        public void encode(MemoryBuffer buffer, T obj) {
          MemoryBuffer prevBuffer = writer.getBuffer();
          int writerIndex = buffer.writerIndex();
          buffer.writeInt32(-1);
          try {
            writer.setBuffer(buffer);
            BinaryArray array = toArray(obj);
            int size = buffer.writerIndex() - writerIndex - 4;
            assert size == array.getSizeInBytes();
            buffer.putInt32(writerIndex, size);
          } finally {
            writer.setBuffer(prevBuffer);
          }
        }
      };
    } catch (Exception e) {
      String msg = String.format("Create encoder failed, \nelementType: %s", elementType);
      throw new EncoderException(msg, e);
    }
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
  public static <T extends Map, K, V> MapEncoder<T> mapEncoder(
      Class<? extends Map> mapCls, Class<K> keyType, Class<V> valueType) {
    Preconditions.checkNotNull(keyType);
    Preconditions.checkNotNull(valueType);

    return (MapEncoder<T>) mapEncoder(TypeUtils.mapOf(keyType, valueType), null);
  }

  public static <T extends Map> MapEncoder<T> mapEncoder(TypeRef<T> token, Fory fory) {
    Preconditions.checkNotNull(token);
    Tuple2<TypeRef<?>, TypeRef<?>> tuple2 = TypeUtils.getMapKeyValueType(token);

    Set<TypeRef<?>> set1 = beanSet(tuple2.f0);
    Set<TypeRef<?>> set2 = beanSet(tuple2.f1);
    LOG.info("Find beans to load: {}, {}", set1, set2);

    TypeRef<?> keyToken = token4BeanLoad(set1, tuple2.f0);
    TypeRef<?> valToken = token4BeanLoad(set2, tuple2.f1);

    MapEncoder<T> encoder = mapEncoder(token, keyToken, valToken, fory);
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
      TypeRef<? extends Map> mapToken, TypeRef<K> keyToken, TypeRef<V> valToken, Fory fory) {
    Preconditions.checkNotNull(mapToken);
    Preconditions.checkNotNull(keyToken);
    Preconditions.checkNotNull(valToken);

    Schema schema = TypeInference.inferSchema(mapToken, false);
    Field field = DataTypes.fieldOfSchema(schema, 0);
    Field keyField = DataTypes.keyArrayFieldForMap(field);
    Field valField = DataTypes.itemArrayFieldForMap(field);
    BinaryArrayWriter keyWriter = new BinaryArrayWriter(keyField);
    BinaryArrayWriter valWriter = new BinaryArrayWriter(valField, keyWriter.getBuffer());
    try {
      Class<?> rowCodecClass = loadOrGenMapCodecClass(mapToken, keyToken, valToken);
      Object references = new Object[] {keyField, valField, keyWriter, valWriter, fory, field};
      GeneratedMapEncoder codec =
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
        public T fromMap(BinaryArray key, BinaryArray value) {
          return (T) codec.fromMap(key, value);
        }

        @Override
        public BinaryMap toMap(T obj) {
          return codec.toMap(obj);
        }

        @Override
        public T decode(MemoryBuffer buffer) {
          return decode(buffer, buffer.readInt32());
        }

        public T decode(MemoryBuffer buffer, int size) {
          BinaryMap map = new BinaryMap(field);
          int readerIndex = buffer.readerIndex();
          map.pointTo(buffer, readerIndex, size);
          buffer.readerIndex(readerIndex + size);
          return fromMap(map);
        }

        @Override
        public T decode(byte[] bytes) {
          return decode(MemoryBuffer.wrap(bytes), bytes.length);
        }

        @Override
        public byte[] encode(T obj) {
          BinaryMap map = toMap(obj);
          return map.getBuf().getBytes(map.getBaseOffset(), map.getSizeInBytes());
        }

        @Override
        public void encode(MemoryBuffer buffer, T obj) {
          MemoryBuffer prevBuffer = keyWriter.getBuffer();
          int writerIndex = buffer.writerIndex();
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
    } catch (Exception e) {
      String msg =
          String.format("Create encoder failed, \nkeyType: %s, valueType: %s", keyToken, valToken);
      throw new EncoderException(msg, e);
    }
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

  private static TypeRef<?> token4BeanLoad(Set<TypeRef<?>> set, TypeRef<?> init) {
    TypeRef<?> keyToken = init;
    for (TypeRef<?> tt : set) {
      keyToken = tt;
      Encoders.loadOrGenRowCodecClass(getRawType(tt));
      LOG.info("bean {} load finished", getRawType(tt));
    }
    return keyToken;
  }

  private static <T> MapEncoder<T> createMapEncoder(MapEncoder<T> encoder) {
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
      public T fromMap(BinaryArray key, BinaryArray value) {
        return encoder.fromMap(key, value);
      }

      @Override
      public BinaryMap toMap(T obj) {
        return encoder.toMap(obj);
      }

      @Override
      public T decode(MemoryBuffer buffer) {
        return encoder.decode(buffer);
      }

      @Override
      public T decode(byte[] bytes) {
        return encoder.decode(bytes);
      }

      @Override
      public byte[] encode(T obj) {
        return encoder.encode(obj);
      }

      @Override
      public void encode(MemoryBuffer buffer, T obj) {
        encoder.encode(buffer, obj);
      }
    };
  }

  private static void findBeanToken(TypeRef<?> typeRef, java.util.Set<TypeRef<?>> set) {
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

  public static Class<?> loadOrGenRowCodecClass(Class<?> beanClass) {
    Set<Class<?>> classes =
        TypeUtils.listBeansRecursiveInclusive(
            beanClass,
            new TypeResolutionContext(CustomTypeEncoderRegistry.customTypeHandler(), true));
    LOG.info("Create RowCodec for classes {}", classes);
    CompileUnit[] compileUnits =
        classes.stream()
            .map(
                cls -> {
                  RowEncoderBuilder codecBuilder = new RowEncoderBuilder(cls);
                  // use genCodeFunc to avoid gen code repeatedly
                  return new CompileUnit(
                      CodeGenerator.getPackage(cls),
                      codecBuilder.codecClassName(cls),
                      codecBuilder::genCode);
                })
            .toArray(CompileUnit[]::new);
    return loadCls(compileUnits);
  }

  private static <B> Class<?> loadOrGenArrayCodecClass(
      TypeRef<? extends Collection> arrayCls, TypeRef<B> elementType) {
    LOG.info("Create ArrayCodec for classes {}", elementType);
    Class<?> cls = getRawType(elementType);
    // class name prefix
    String prefix = TypeInference.inferTypeName(arrayCls);

    ArrayEncoderBuilder codecBuilder = new ArrayEncoderBuilder(arrayCls, elementType);
    CompileUnit compileUnit =
        new CompileUnit(
            CodeGenerator.getPackage(cls),
            codecBuilder.codecClassName(cls, prefix),
            codecBuilder::genCode);

    return loadCls(compileUnit);
  }

  private static <K, V> Class<?> loadOrGenMapCodecClass(
      TypeRef<? extends Map> mapCls, TypeRef<K> keyToken, TypeRef<V> valueToken) {
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

    MapEncoderBuilder codecBuilder = new MapEncoderBuilder(mapCls, beanToken);
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
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("Impossible because we just compiled class", e);
    }
  }
}

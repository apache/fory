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

import static org.apache.fory.type.TypeUtils.getRawType;

import java.lang.reflect.Constructor;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.fory.format.row.binary.writer.BaseBinaryRowWriter;
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.TypeInference;
import org.apache.fory.reflect.TypeRef;

public class BeanCodecBuilder<T> extends BaseCodecBuilder<BeanCodecBuilder<T>> {

  private final Class<T> beanClass;

  BeanCodecBuilder(final Class<T> beanClass) {
    super(TypeInference.inferSchema(beanClass));
    this.beanClass = beanClass;
  }

  /**
   * Create a codec factory with internal buffer management suitable for serializing individual
   * objects. The resulting factory should be re-used if possible for creating subsequent encoder
   * instances. Encoders are not thread-safe, so create a separate encoder for each thread that uses
   * it. For platform threads consider {@link ThreadLocal#withInitial(Supplier)}, or get a new
   * encoder instance for each virtual thread.
   */
  public Supplier<RowEncoder<T>> build() {
    final Function<BaseBinaryRowWriter, RowEncoder<T>> rowEncoderFactory = buildForCustomWriter();
    return new Supplier<RowEncoder<T>>() {
      @Override
      public RowEncoder<T> get() {
        final BaseBinaryRowWriter writer = codecFactory.newWriter(schema);
        return new BufferResettingEncoder<T>(
            initialBufferSize, writer, rowEncoderFactory.apply(writer));
      }
    };
  }

  /**
   * Create a codec factory with an externally managed writer and buffer, for writing many objects
   * to a stream. Encoders are not thread-safe so re-use the factory to create a new encoder for
   * each writer needed.
   */
  public Function<BaseBinaryRowWriter, RowEncoder<T>> buildForCustomWriter() {
    final Function<BaseBinaryRowWriter, GeneratedRowEncoder> rowEncoderFactory =
        rowEncoderFactory();
    return new Function<BaseBinaryRowWriter, RowEncoder<T>>() {
      @Override
      public RowEncoder<T> apply(final BaseBinaryRowWriter writer) {
        return new BeanEncoder<T>(schema, codecFactory, rowEncoderFactory.apply(writer), writer);
      }
    };
  }

  public <C extends Collection<? extends T>> Supplier<ArrayEncoder<C>> buildForArray(
      final TypeRef<C> type) {
    final Function<BinaryArrayWriter, ArrayEncoder<C>> arrayEncoderFactory =
        buildForArrayWithCustomWriter(type);
    final Schema arraySchema = TypeInference.inferSchema(type, false);
    final Field field = DataTypes.fieldOfSchema(arraySchema, 0);
    return new Supplier<ArrayEncoder<C>>() {
      @Override
      public ArrayEncoder<C> get() {
        final BinaryArrayWriter writer = codecFactory.newArrayWriter(field);
        return arrayEncoderFactory.apply(writer);
      }
    };
  }

  public <C extends Collection<? extends T>>
      Function<BinaryArrayWriter, ArrayEncoder<C>> buildForArrayWithCustomWriter(
          final TypeRef<C> type) {
    loadArrayInnerCodecs(type);
    final Function<BinaryArrayWriter, GeneratedArrayEncoder> arrayEncoderFactory =
        arrayEncoderFactory(type);
    return new Function<BinaryArrayWriter, ArrayEncoder<C>>() {
      @Override
      public ArrayEncoder<C> apply(final BinaryArrayWriter writer) {
        return new BeanArrayEncoder<>(writer, writer.getField(), arrayEncoderFactory.apply(writer));
      }
    };
  }

  private void loadArrayInnerCodecs(final TypeRef<?> collectionType) {
    final Set<TypeRef<?>> set = new HashSet<>();
    Encoders.findBeanToken(collectionType, set);
    if (set.isEmpty()) {
      throw new IllegalArgumentException("can not find bean class.");
    }

    TypeRef<?> typeRef = null;
    for (final TypeRef<?> tt : set) {
      typeRef = set.iterator().next();
      Encoders.loadOrGenRowCodecClass(getRawType(tt), codecFactory);
    }
  }

  // TODO: map and array encoder

  Function<BaseBinaryRowWriter, GeneratedRowEncoder> rowEncoderFactory() {
    final Class<?> rowCodecClass = Encoders.loadOrGenRowCodecClass(beanClass, codecFactory);
    Constructor<? extends GeneratedRowEncoder> constructor;
    try {
      constructor =
          rowCodecClass.asSubclass(GeneratedRowEncoder.class).getConstructor(Object[].class);
    } catch (final NoSuchMethodException e) {
      throw new EncoderException("Failed to construct codec for " + beanClass, e);
    }
    return new Function<BaseBinaryRowWriter, GeneratedRowEncoder>() {
      @Override
      public GeneratedRowEncoder apply(final BaseBinaryRowWriter writer) {
        try {
          final Object references = new Object[] {schema, writer, fory};
          return constructor.newInstance(references);
        } catch (final ReflectiveOperationException e) {
          throw new EncoderException("Failed to construct codec for " + beanClass, e);
        }
      }
    };
  }

  Function<BinaryArrayWriter, GeneratedArrayEncoder> arrayEncoderFactory(
      final TypeRef<? extends Collection<?>> collectionType) {
    final Class<?> arrayCodecClass =
        Encoders.loadOrGenArrayCodecClass(collectionType, TypeRef.of(beanClass), codecFactory);

    Constructor<? extends GeneratedArrayEncoder> constructor;
    try {
      constructor =
          arrayCodecClass.asSubclass(GeneratedArrayEncoder.class).getConstructor(Object[].class);
    } catch (final NoSuchMethodException e) {
      throw new EncoderException(
          "Failed to construct array codec for "
              + collectionType
              + " with element class "
              + beanClass,
          e);
    }
    return new Function<BinaryArrayWriter, GeneratedArrayEncoder>() {
      @Override
      public GeneratedArrayEncoder apply(final BinaryArrayWriter writer) {
        final Field field = writer.getField();
        final Object references = new Object[] {field, writer, fory};
        try {
          return constructor.newInstance(references);
        } catch (final ReflectiveOperationException e) {
          throw new EncoderException(
              "Failed to construct array codec for "
                  + collectionType
                  + " with element class "
                  + beanClass,
              e);
        }
      }
    };
  }
}

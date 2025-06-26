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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.TypeInference;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.util.ExceptionUtils;

public class ArrayCodecBuilder<C extends Collection<?>>
    extends BaseCodecBuilder<ArrayCodecBuilder<C>> {

  private final TypeRef<C> collectionType;
  private final Field elementField;

  ArrayCodecBuilder(final TypeRef<C> collectionType) {
    super(TypeInference.inferSchema(collectionType, false));
    this.collectionType = collectionType;
    elementField = DataTypes.fieldOfSchema(schema, 0);
  }

  public Supplier<ArrayEncoder<C>> build() {
    final Function<BinaryArrayWriter, ArrayEncoder<C>> arrayEncoderFactory = buildWithWriter();
    return new Supplier<ArrayEncoder<C>>() {
      @Override
      public ArrayEncoder<C> get() {
        final BinaryArrayWriter writer = codecFormat.newArrayWriter(elementField);
        return new BufferResettingArrayEncoder<>(
            initialBufferSize, writer, arrayEncoderFactory.apply(writer));
      }
    };
  }

  Function<BinaryArrayWriter, ArrayEncoder<C>> buildWithWriter() {
    loadArrayInnerCodecs();
    final Function<BinaryArrayWriter, GeneratedArrayEncoder> generatedEncoderFactory =
        generatedEncoderFactory();
    return new Function<BinaryArrayWriter, ArrayEncoder<C>>() {
      @Override
      public ArrayEncoder<C> apply(final BinaryArrayWriter writer) {
        return new BinaryArrayEncoder<>(
            writer, generatedEncoderFactory.apply(writer), sizeEmbedded);
      }
    };
  }

  private void loadArrayInnerCodecs() {
    final Set<TypeRef<?>> set = new HashSet<>();
    Encoders.findBeanToken(collectionType, set);
    if (set.isEmpty()) {
      throw new IllegalArgumentException("can not find bean class.");
    }

    for (final TypeRef<?> tt : set) {
      Encoders.loadOrGenRowCodecClass(getRawType(tt), codecFormat);
    }
  }

  Function<BinaryArrayWriter, GeneratedArrayEncoder> generatedEncoderFactory() {
    final TypeRef<?> elementType = TypeUtils.getElementType(collectionType);
    final Class<?> arrayCodecClass =
        Encoders.loadOrGenArrayCodecClass(collectionType, elementType, codecFormat);

    final MethodHandle constructorHandle;
    try {
      final var constructor =
          arrayCodecClass.asSubclass(GeneratedArrayEncoder.class).getConstructor(Object[].class);
      constructorHandle =
          MethodHandles.lookup()
              .unreflectConstructor(constructor)
              .asType(MethodType.methodType(GeneratedArrayEncoder.class, Object[].class));
    } catch (final NoSuchMethodException | IllegalAccessException e) {
      throw new EncoderException(
          "Failed to construct array codec for "
              + collectionType
              + " with element class "
              + elementType,
          e);
    }
    return new Function<BinaryArrayWriter, GeneratedArrayEncoder>() {
      @Override
      public GeneratedArrayEncoder apply(final BinaryArrayWriter writer) {
        final Object[] references = {writer.getField(), writer, fory};
        try {
          return (GeneratedArrayEncoder) constructorHandle.invokeExact(references);
        } catch (final Throwable t) {
          throw ExceptionUtils.throwAnyway(t);
        }
      }
    };
  }
}

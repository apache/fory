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
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.TypeInference;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.TypeUtils;

public class ArrayCodecBuilder<C extends Collection<?>>
    extends BaseCodecBuilder<ArrayCodecBuilder<C>> {

  private final TypeRef<C> collectionType;

  ArrayCodecBuilder(final TypeRef<C> collectionType) {
    super(TypeInference.inferSchema(collectionType, false));
    this.collectionType = collectionType;
  }

  public Supplier<ArrayEncoder<C>> buildForArray() {
    final Function<BinaryArrayWriter, ArrayEncoder<C>> arrayEncoderFactory =
        buildForArrayWithCustomWriter();
    final Schema collectionSchema = TypeInference.inferSchema(collectionType, false);
    final Field field = DataTypes.fieldOfSchema(collectionSchema, 0);
    return new Supplier<ArrayEncoder<C>>() {
      @Override
      public ArrayEncoder<C> get() {
        final BinaryArrayWriter writer = codecFactory.newArrayWriter(field);
        return arrayEncoderFactory.apply(writer);
      }
    };
  }

  public Function<BinaryArrayWriter, ArrayEncoder<C>> buildForArrayWithCustomWriter() {
    loadArrayInnerCodecs(collectionType);
    final Function<BinaryArrayWriter, GeneratedArrayEncoder> arrayEncoderFactory =
        arrayEncoderFactory(collectionType);
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

  Function<BinaryArrayWriter, GeneratedArrayEncoder> arrayEncoderFactory(
      final TypeRef<? extends Collection<?>> collectionType) {
    final TypeRef<?> elementType = TypeUtils.getElementType(collectionType);
    final Class<?> arrayCodecClass =
        Encoders.loadOrGenArrayCodecClass(collectionType, elementType, codecFactory);

    Constructor<? extends GeneratedArrayEncoder> constructor;
    try {
      constructor =
          arrayCodecClass.asSubclass(GeneratedArrayEncoder.class).getConstructor(Object[].class);
    } catch (final NoSuchMethodException e) {
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
        final Field field = writer.getField();
        final Object references = new Object[] {field, writer, fory};
        try {
          return constructor.newInstance(references);
        } catch (final ReflectiveOperationException e) {
          throw new EncoderException(
              "Failed to construct array codec for "
                  + collectionType
                  + " with element class "
                  + elementType,
              e);
        }
      }
    };
  }
}

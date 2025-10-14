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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.fory.format.row.binary.writer.BaseBinaryRowWriter;
import org.apache.fory.format.type.TypeInference;
import org.apache.fory.util.ExceptionUtils;

public class RowCodecBuilder<T> extends BaseCodecBuilder<RowCodecBuilder<T>> {

  private final Class<T> beanClass;

  RowCodecBuilder(final Class<T> beanClass) {
    super(TypeInference.inferSchema(beanClass));
    this.beanClass = beanClass;
  }

  /**
   * Create a codec factory with the configuration settings from this builder. The resulting factory
   * should be re-used if possible for creating subsequent encoder instances. Encoders are not
   * thread-safe, so create a separate encoder for each thread that uses it. For platform threads
   * consider {@link ThreadLocal#withInitial(Supplier)}, or get a new encoder instance for each
   * virtual thread.
   */
  public Supplier<RowEncoder<T>> build() {
    final Function<BaseBinaryRowWriter, RowEncoder<T>> rowEncoderFactory = buildForWriter();
    return new Supplier<RowEncoder<T>>() {
      @Override
      public RowEncoder<T> get() {
        final BaseBinaryRowWriter writer = codecFormat.newWriter(schema);
        return new BufferResettingRowEncoder<T>(
            initialBufferSize, writer, rowEncoderFactory.apply(writer));
      }
    };
  }

  Function<BaseBinaryRowWriter, RowEncoder<T>> buildForWriter() {
    final Function<BaseBinaryRowWriter, GeneratedRowEncoder> rowEncoderFactory =
        rowEncoderFactory();
    return new Function<BaseBinaryRowWriter, RowEncoder<T>>() {
      @Override
      public RowEncoder<T> apply(final BaseBinaryRowWriter writer) {
        return new BinaryRowEncoder<T>(
            schema, codecFormat, rowEncoderFactory.apply(writer), writer, sizeEmbedded);
      }
    };
  }

  Function<BaseBinaryRowWriter, GeneratedRowEncoder> rowEncoderFactory() {
    final Class<?> rowCodecClass = Encoders.loadOrGenRowCodecClass(beanClass, codecFormat);
    MethodHandle constructorHandle;
    try {
      final var constructor =
          rowCodecClass.asSubclass(GeneratedRowEncoder.class).getConstructor(Object[].class);
      constructorHandle =
          MethodHandles.lookup()
              .unreflectConstructor(constructor)
              .asType(MethodType.methodType(GeneratedRowEncoder.class, Object[].class));
    } catch (final NoSuchMethodException | IllegalAccessException e) {
      throw new EncoderException("Failed to construct codec for " + beanClass, e);
    }
    return new Function<BaseBinaryRowWriter, GeneratedRowEncoder>() {
      @Override
      public GeneratedRowEncoder apply(final BaseBinaryRowWriter writer) {
        try {
          final Object[] references = {schema, writer, fory};
          return (GeneratedRowEncoder) constructorHandle.invokeExact(references);
        } catch (final ReflectiveOperationException e) {
          throw new EncoderException("Failed to construct codec for " + beanClass, e);
        } catch (final Throwable e) {
          throw ExceptionUtils.throwAnyway(e);
        }
      }
    };
  }
}

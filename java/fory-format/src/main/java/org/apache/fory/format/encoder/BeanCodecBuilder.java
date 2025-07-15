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

import java.lang.reflect.Constructor;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.fory.Fory;
import org.apache.fory.builder.CodecBuilder;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.format.row.binary.CompactBinaryRow;
import org.apache.fory.format.row.binary.writer.BaseBinaryRowWriter;
import org.apache.fory.format.row.binary.writer.BinaryRowWriter;
import org.apache.fory.format.row.binary.writer.CompactBinaryRowWriter;
import org.apache.fory.format.type.TypeInference;
import org.apache.fory.memory.MemoryBuffer;

public class BeanCodecBuilder<T> {

  private final Class<T> beanClass;
  private Schema schema;
  private int initialBufferSize = 16;
  private Fory fory;
  private Function<Schema, BaseBinaryRowWriter> writerFactory = BinaryRowWriter::new;
  private Function<Class<?>, CodecBuilder> codecFactory = RowEncoderBuilder::new;
  private Function<Schema, BinaryRow> rowFactory = BinaryRow::new;

  BeanCodecBuilder(final Class<T> beanClass) {
    this.beanClass = beanClass;
    schema = TypeInference.inferSchema(beanClass);
  }

  /** Configure the Fory instance used for embedded binary serialized objects. */
  public BeanCodecBuilder<T> fory(final Fory fory) {
    this.fory = fory;
    return this;
  }

  /** Configure the initial buffer size used when writing a new row. */
  public BeanCodecBuilder<T> initialBufferSize(final int initialBufferSize) {
    this.initialBufferSize = initialBufferSize;
    return this;
  }

  /**
   * Configure compact encoding, which is more space efficient than the default encoding, but is not
   * yet stable. See {@link CompactBinaryRow} for details.
   */
  public BeanCodecBuilder<T> compactEncoding() {
    schema = CompactBinaryRowWriter.sortSchema(schema);
    writerFactory = CompactBinaryRowWriter::new;
    codecFactory = CompactRowEncoderBuilder::new;
    rowFactory = CompactBinaryRow::new;
    return this;
  }

  /**
   * Create a codec factory with internal buffer management suitable for serializing individual
   * objects. The resulting factory should be re-used if possible for creating subsequent encoder
   * instances. Encoders are not thread-safe, so create a separate encoder for each thread that uses
   * it.
   */
  public Supplier<RowEncoder<T>> build() {
    final Function<BaseBinaryRowWriter, GeneratedRowEncoder> codecFactory = codecFactory();
    return new Supplier<RowEncoder<T>>() {
      @Override
      public RowEncoder<T> get() {
        final BaseBinaryRowWriter writer = writerFactory.apply(schema);
        return new BeanEncoder<T>(schema, rowFactory, codecFactory.apply(writer), writer) {
          @Override
          protected void reset() {
            writer.setBuffer(MemoryBuffer.newHeapBuffer(initialBufferSize));
            writer.reset();
          }
        };
      }
    };
  }

  /**
   * Create a codec factory with an externally managed writer and buffer, for writing many objects
   * to a stream. Encoders are not thread-safe so re-use the factory to create a new encoder for
   * each writer needed.
   */
  public Function<BaseBinaryRowWriter, RowEncoder<T>> buildForCustomWriter() {
    final Function<BaseBinaryRowWriter, GeneratedRowEncoder> codecFactory = codecFactory();
    return new Function<BaseBinaryRowWriter, RowEncoder<T>>() {
      @Override
      public RowEncoder<T> apply(final BaseBinaryRowWriter writer) {
        return new BeanEncoder<T>(schema, rowFactory, codecFactory.apply(writer), writer);
      }
    };
  }

  Function<BaseBinaryRowWriter, GeneratedRowEncoder> codecFactory() {
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
}

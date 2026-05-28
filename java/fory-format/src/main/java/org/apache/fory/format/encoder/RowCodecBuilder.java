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
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.apache.fory.Fory;
import org.apache.fory.format.row.binary.writer.BaseBinaryRowWriter;
import org.apache.fory.format.row.binary.writer.CompactBinaryRowWriter;
import org.apache.fory.format.type.Schema;
import org.apache.fory.format.type.SchemaHistory;
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
    // Snapshot schema at build time so a supplier remains pinned to the schema in effect when
    // it was constructed, even if the builder is mutated afterwards.
    final Schema currentSchema = schema;
    return new Supplier<RowEncoder<T>>() {
      @Override
      public RowEncoder<T> get() {
        final BaseBinaryRowWriter writer = codecFormat.newWriter(currentSchema);
        return new BufferResettingRowEncoder<T>(
            initialBufferSize, writer, rowEncoderFactory.apply(writer));
      }
    };
  }

  Function<BaseBinaryRowWriter, RowEncoder<T>> buildForWriter() {
    if (!schemaEvolution) {
      return defaultBuildForWriter();
    }
    return evolvingBuildForWriter();
  }

  private Function<BaseBinaryRowWriter, RowEncoder<T>> defaultBuildForWriter() {
    final Schema currentSchema = schema;
    final Function<BaseBinaryRowWriter, GeneratedRowEncoder> rowEncoderFactory =
        rowEncoderFactory(currentSchema);
    return new Function<BaseBinaryRowWriter, RowEncoder<T>>() {
      @Override
      public RowEncoder<T> apply(final BaseBinaryRowWriter writer) {
        return new BinaryRowEncoder<T>(
            currentSchema, rowEncoderFactory.apply(writer), writer, sizeEmbedded);
      }
    };
  }

  private Function<BaseBinaryRowWriter, RowEncoder<T>> evolvingBuildForWriter() {
    UnaryOperator<Schema> schemaTransform =
        codecFormat == CompactCodecFormat.INSTANCE
            ? CompactBinaryRowWriter::sortSchema
            : UnaryOperator.identity();
    SchemaHistory history = SchemaHistory.build(beanClass, schemaTransform);
    SchemaHistory.VersionedSchema currentVersion = history.current();
    // The history-derived schema is the one writers, generated codec, and decode dispatch must
    // agree on. Pin it on the builder so build() picks up the rotated schema; pass it into the
    // current-version codec factory locally so a later mutation of the field cannot affect
    // already-constructed encoders.
    final Schema currentSchema = currentVersion.schema();
    schema = currentSchema;

    final Function<BaseBinaryRowWriter, GeneratedRowEncoder> currentFactory =
        rowEncoderFactory(currentSchema);
    // Projection codecs for each older version; classes are loaded eagerly.
    final Map<Long, ProjectionCodecFactory> projectionFactories = new HashMap<>();
    for (SchemaHistory.VersionedSchema vs : history.versions()) {
      if (vs == currentVersion) {
        continue;
      }
      String suffix = "_V" + vs.version();
      Class<?> projectionClass =
          Encoders.loadOrGenProjectionRowCodecClass(
              beanClass, codecFormat, vs.schema(), vs.liveFieldNames(), suffix);
      MethodHandle ctor =
          Encoders.constructorHandleFor(projectionClass, GeneratedRowEncoder.class);
      projectionFactories.put(vs.strictHash(), new ProjectionCodecFactory(vs.schema(), ctor));
    }

    final long currentHash = currentVersion.strictHash();
    return new Function<BaseBinaryRowWriter, RowEncoder<T>>() {
      @Override
      public RowEncoder<T> apply(final BaseBinaryRowWriter writer) {
        Map<Long, BinaryRowEncoder.ProjectionCodec> projections = new HashMap<>();
        for (Map.Entry<Long, ProjectionCodecFactory> entry : projectionFactories.entrySet()) {
          projections.put(entry.getKey(), entry.getValue().instantiate(codecFormat, writer, fory));
        }
        return new BinaryRowEncoder<T>(
            currentSchema,
            currentFactory.apply(writer),
            writer,
            sizeEmbedded,
            currentHash,
            projections);
      }
    };
  }

  private static final class ProjectionCodecFactory {
    private final Schema historicalSchema;
    private final MethodHandle ctor;

    ProjectionCodecFactory(Schema historicalSchema, MethodHandle ctor) {
      this.historicalSchema = historicalSchema;
      this.ctor = ctor;
    }

    BinaryRowEncoder.ProjectionCodec instantiate(Encoding codecFormat, BaseBinaryRowWriter writer, Fory fory) {
      try {
        Object[] references = {historicalSchema, writer, fory};
        GeneratedRowEncoder codec = (GeneratedRowEncoder) ctor.invokeExact(references);
        RowFactory rowFactory = codecFormat.newRowFactory(historicalSchema);
        return new BinaryRowEncoder.ProjectionCodec(rowFactory, codec);
      } catch (final ReflectiveOperationException e) {
        throw new EncoderException(
            "Failed to construct projection codec for schema " + historicalSchema, e);
      } catch (Throwable e) {
        throw ExceptionUtils.throwException(e);
      }
    }
  }

  Function<BaseBinaryRowWriter, GeneratedRowEncoder> rowEncoderFactory(final Schema codecSchema) {
    final Class<?> rowCodecClass = Encoders.loadOrGenRowCodecClass(beanClass, codecFormat);
    final MethodHandle constructorHandle =
        Encoders.constructorHandleFor(rowCodecClass, GeneratedRowEncoder.class);
    return new Function<BaseBinaryRowWriter, GeneratedRowEncoder>() {
      @Override
      public GeneratedRowEncoder apply(final BaseBinaryRowWriter writer) {
        try {
          final Object[] references = {codecSchema, writer, fory};
          return (GeneratedRowEncoder) constructorHandle.invokeExact(references);
        } catch (final ReflectiveOperationException e) {
          throw new EncoderException("Failed to construct codec for " + beanClass, e);
        } catch (Throwable e) {
          throw ExceptionUtils.throwException(e);
        }
      }
    };
  }
}

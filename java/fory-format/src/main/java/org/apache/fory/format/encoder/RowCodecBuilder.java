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
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.fory.Fory;
import org.apache.fory.collection.LongMap;
import org.apache.fory.format.row.binary.writer.BaseBinaryRowWriter;
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
    final RowEncoderFactory<T> factory = buildEncoderFactory();
    return new Supplier<RowEncoder<T>>() {
      @Override
      public RowEncoder<T> get() {
        final BaseBinaryRowWriter writer = codecFormat.newWriter(factory.schema);
        return new BufferResettingRowEncoder<T>(initialBufferSize, writer, factory.apply(writer));
      }
    };
  }

  Function<BaseBinaryRowWriter, RowEncoder<T>> buildForWriter() {
    return buildEncoderFactory();
  }

  /**
   * Resolve the schema and the per-writer encoder factory together. The evolution path rotates the
   * schema to the history-derived current version; returning it alongside the factory keeps that
   * resolution out of the mutable builder state, so a reused builder or a direct {@link
   * #buildForWriter()} caller is unaffected.
   */
  private RowEncoderFactory<T> buildEncoderFactory() {
    return schemaEvolution ? evolvingBuildForWriter() : defaultBuildForWriter();
  }

  private RowEncoderFactory<T> defaultBuildForWriter() {
    final Schema currentSchema = schema;
    final Function<BaseBinaryRowWriter, GeneratedRowEncoder> rowEncoderFactory =
        rowEncoderFactory(currentSchema);
    return new RowEncoderFactory<T>(currentSchema) {
      @Override
      public RowEncoder<T> apply(final BaseBinaryRowWriter writer) {
        return new BinaryRowEncoder<T>(
            currentSchema, rowEncoderFactory.apply(writer), writer, sizeEmbedded);
      }
    };
  }

  private RowEncoderFactory<T> evolvingBuildForWriter() {
    SchemaHistory history = buildSchemaHistory(beanClass);
    SchemaHistory.VersionedSchema currentVersion = history.current();
    // The history-derived schema is what writers, generated codec, and decode dispatch must agree
    // on. It travels back to build() through the returned factory rather than the mutable schema
    // field, so building does not rotate builder state that a later build()/buildForWriter() reads.
    final Schema currentSchema = currentVersion.schema();

    final Function<BaseBinaryRowWriter, GeneratedRowEncoder> currentFactory =
        rowEncoderFactory(currentSchema);
    // Index of hash → deferred projection source for each non-current combination of
    // (outer-version, inner-versions). Building it compiles nothing: a combination's codec class is
    // generated only the first time a payload with that hash is decoded. The suffix encodes the
    // combination so distinct cross-product entries get distinct generated classes; the nested-bean
    // version map directs the projection codec to embed the right inner projection class.
    //
    // Keyed by the raw strict hash straight from SchemaHistory, which already proves these hashes
    // are unique across versions() and distinct from the current schema (its hashToSignature guard
    // throws on a real collision). No builder-side collision check is needed here, unlike the map
    // codec, whose key is a combined (key, value) hash computed outside SchemaHistory.
    final LongMap<BinaryRowEncoder.ProjectionSource> projectionSources = new LongMap<>();
    for (SchemaHistory.VersionedSchema vs : history.versions()) {
      if (vs == currentVersion) {
        continue;
      }
      projectionSources.put(vs.strictHash(), new ProjectionSource(beanClass, codecFormat, vs));
    }

    final long currentHash = currentVersion.strictHash();
    return new RowEncoderFactory<T>(currentSchema) {
      @Override
      public RowEncoder<T> apply(final BaseBinaryRowWriter writer) {
        return new BinaryRowEncoder<T>(
            currentSchema,
            currentFactory.apply(writer),
            writer,
            sizeEmbedded,
            currentHash,
            projectionSources.size == 0 ? null : projectionSources,
            fory);
      }
    };
  }

  /**
   * A per-writer encoder factory that also carries the schema the writer must be created with. The
   * schema travels with the factory instead of through the mutable builder, so {@link #build()} can
   * create the writer without reading builder state that the evolution path would otherwise rotate.
   */
  abstract static class RowEncoderFactory<T>
      implements Function<BaseBinaryRowWriter, RowEncoder<T>> {
    final Schema schema;

    RowEncoderFactory(final Schema schema) {
      this.schema = schema;
    }
  }

  /**
   * Deferred projection codec for one historical version. Holds only the inputs needed to generate
   * the codec; the class is compiled on the first {@link #compile} call (the first decode of this
   * version's hash), not at build time. Shared across encoder instances and immutable, so the
   * compile relies on the shared code generator's own memoization rather than local locking.
   */
  private static final class ProjectionSource implements BinaryRowEncoder.ProjectionSource {
    private final Class<?> beanClass;
    private final Encoding codecFormat;
    private final SchemaHistory.VersionedSchema version;

    ProjectionSource(
        Class<?> beanClass, Encoding codecFormat, SchemaHistory.VersionedSchema version) {
      this.beanClass = beanClass;
      this.codecFormat = codecFormat;
      this.version = version;
    }

    @Override
    public BinaryRowEncoder.ProjectionCodec compile(BaseBinaryRowWriter writer, Fory fory) {
      Schema historicalSchema = version.schema();
      String suffix = ProjectionRouting.projectionSuffix(version);
      Map<Class<?>, String> nestedSuffixes =
          ProjectionRouting.nestedSuffixesFor(version, codecFormat);
      Class<?> projectionClass =
          Encoders.loadOrGenProjectionRowCodecClass(
              beanClass,
              codecFormat,
              historicalSchema,
              version.liveFieldNames(),
              suffix,
              nestedSuffixes);
      MethodHandle ctor = Encoders.constructorHandleFor(projectionClass, GeneratedRowEncoder.class);
      // The RowFactory depends only on the historical schema and codec format, so build it here
      // alongside the codec the first time this version is decoded.
      RowFactory rowFactory = codecFormat.newRowFactory(historicalSchema);
      try {
        Object[] references = {historicalSchema, writer, fory};
        GeneratedRowEncoder codec = (GeneratedRowEncoder) ctor.invokeExact(references);
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

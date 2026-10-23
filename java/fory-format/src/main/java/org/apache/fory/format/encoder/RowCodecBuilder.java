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
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.fory.Fory;
import org.apache.fory.codegen.CodeGenerator;
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
    final RowEncoderFactory<T> factory = buildForWriter();
    return new Supplier<RowEncoder<T>>() {
      @Override
      public RowEncoder<T> get() {
        final BaseBinaryRowWriter writer = codecFormat.newWriter(factory.schema);
        return new BufferResettingRowEncoder<T>(initialBufferSize, writer, factory.apply(writer));
      }
    };
  }

  /**
   * Resolve the schema and the per-writer encoder factory together. The evolution path rotates the
   * schema to the history-derived current version; returning it alongside the factory keeps that
   * resolution out of the mutable builder state, so a reused builder is unaffected.
   */
  RowEncoderFactory<T> buildForWriter() {
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
    checkCurrentLayout(beanClass.getName(), schema, currentSchema, currentVersion.strictHash());

    final CodeGenerator codeGenerator = Encoders.pinCodeGenerator();
    final Function<BaseBinaryRowWriter, GeneratedRowEncoder> currentFactory =
        rowEncoderFactory(currentSchema);
    // Index of hash → deferred projection source for each non-current combination of
    // (outer-version, inner-versions). Building it compiles nothing: a combination's codec class is
    // generated only the first time a payload with that hash is decoded. The suffix encodes the
    // combination so distinct cross-product entries get distinct generated classes; the nested-bean
    // version map directs the projection codec to embed the right inner projection class.
    //
    // Keyed by the raw strict hash straight from SchemaHistory, whose versions() hold one entry per
    // strict hash, so the keys are unique and none is the current hash. No builder-side collision
    // check is needed here, unlike the map codec, whose key is a combined (key, value) hash
    // computed
    // outside SchemaHistory. Two distinct layouts with one strict hash would share an entry;
    // SchemaHistory rejects most such collisions, which have odds of about 2^-64, but not all.
    final LongMap<RowProjectionSource> projectionSources = new LongMap<>();
    for (SchemaHistory.VersionedSchema vs : history.versions()) {
      if (vs == currentVersion) {
        continue;
      }
      projectionSources.put(
          vs.strictHash(), new RowProjectionSource(beanClass, codecFormat, vs, codeGenerator));
    }

    final long currentHash = currentVersion.strictHash();
    // The writer and codec need the history schema, which the compact format sorts at every depth
    // so the hash covers the wire order. schema() reports the builder's schema instead, the same
    // value an evolution-off codec reports. The two hold the same fields in the same top-level
    // order and differ only in the field order inside nested structs.
    final Schema reportedSchema = schema;
    return new RowEncoderFactory<T>(currentSchema) {
      @Override
      public RowEncoder<T> apply(final BaseBinaryRowWriter writer) {
        return new BinaryRowEncoder<T>(
            reportedSchema,
            currentFactory.apply(writer),
            writer,
            sizeEmbedded,
            currentHash,
            projectionSources.size == 0 ? null : new ProjectionCache<>(projectionSources, fory));
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
   * Deferred projection codec for one historical version, shared by every encoder of the codec. The
   * class is compiled on the first {@link #compile} call (the first decode of this version's hash
   * by any encoder), not at build time.
   */
  private static final class RowProjectionSource
      extends ProjectionSource<RowProjectionSource.Classes, BinaryRowEncoder.ProjectionCodec> {
    private final Class<?> beanClass;
    private final Encoding codecFormat;
    private final SchemaHistory.VersionedSchema version;
    private final CodeGenerator codeGenerator;

    RowProjectionSource(
        Class<?> beanClass,
        Encoding codecFormat,
        SchemaHistory.VersionedSchema version,
        CodeGenerator codeGenerator) {
      this.beanClass = beanClass;
      this.codecFormat = codecFormat;
      this.version = version;
      this.codeGenerator = codeGenerator;
    }

    @Override
    BinaryRowEncoder.ProjectionCodec bind(Classes r, Fory fory) {
      try {
        // A decode-only codec still builds child writers under its root writer, so give it a
        // detached one: the encoder's own writer would collect those children and walk them on
        // every encode.
        Object[] references = {version.schema(), codecFormat.newWriter(version.schema()), fory};
        GeneratedRowEncoder codec = (GeneratedRowEncoder) r.ctor.invokeExact(references);
        return new BinaryRowEncoder.ProjectionCodec(r.rowFactory, codec);
      } catch (final ReflectiveOperationException e) {
        throw new EncoderException(
            "Failed to construct projection codec for schema " + version.schema(), e);
      } catch (Throwable e) {
        throw ExceptionUtils.throwException(e);
      }
    }

    /** Generates the projection class and the row factory for its historical schema. */
    @Override
    Classes generate() {
      Schema historicalSchema = version.schema();
      Class<?> projectionClass =
          Encoders.loadOrGenProjectionRowCodecClass(
              beanClass,
              codecFormat,
              historicalSchema,
              version.liveFieldNames(),
              ProjectionRouting.projectionSuffix(version),
              ProjectionRouting.nestedSuffixesFor(version, codecFormat, codeGenerator),
              codeGenerator);
      return new Classes(
          Encoders.constructorHandleFor(projectionClass, GeneratedRowEncoder.class),
          codecFormat.newRowFactory(historicalSchema));
    }

    static final class Classes {
      final MethodHandle ctor;
      final RowFactory rowFactory;

      Classes(MethodHandle ctor, RowFactory rowFactory) {
        this.ctor = ctor;
        this.rowFactory = rowFactory;
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

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
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.apache.fory.Fory;
import org.apache.fory.collection.LongMap;
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.format.type.CustomTypeEncoderRegistry;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.Field;
import org.apache.fory.format.type.SchemaHistory;
import org.apache.fory.format.type.TypeInference;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.TypeResolutionContext;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.util.ExceptionUtils;

public class MapCodecBuilder<M extends Map<?, ?>> extends BaseCodecBuilder<MapCodecBuilder<M>> {

  private final TypeRef<M> mapType;
  private final Field field;
  private final Field keyField;
  private final Field valField;
  private final TypeRef<?> keyType;
  private final TypeRef<?> valType;

  MapCodecBuilder(final TypeRef<M> mapType) {
    super(TypeInference.inferSchema(mapType, false));
    this.mapType = mapType;
    field = DataTypes.fieldOfSchema(schema, 0);
    keyField = DataTypes.keyArrayFieldForMap(field);
    valField = DataTypes.itemArrayFieldForMap(field);
    final var kvType = TypeUtils.getMapKeyValueType(mapType);
    keyType = kvType.f0;
    valType = kvType.f1;
  }

  public Supplier<MapEncoder<M>> build() {
    loadMapInnerCodecs();
    if (!schemaEvolution || !isBeanValue()) {
      final var mapEncoderFactory = generatedMapEncoder();
      return new Supplier<MapEncoder<M>>() {
        @Override
        public MapEncoder<M> get() {
          final BinaryArrayWriter keyWriter = codecFormat.newArrayWriter(keyField);
          final BinaryArrayWriter valWriter =
              codecFormat.newArrayWriter(valField, keyWriter.getBuffer());
          final var codec = mapEncoderFactory.apply(keyWriter, valWriter);
          return new BufferResettingMapEncoder<>(
              initialBufferSize,
              keyWriter,
              valWriter,
              new BinaryMapEncoder<M>(
                  codecFormat, field, valWriter, keyWriter, codec, sizeEmbedded));
        }
      };
    }
    return buildVersioned();
  }

  /**
   * True if the value is a bean — the only case where schema evolution affects the wire format.
   * Unversioned beans still take the evolution path so the strict-hash prefix is always present and
   * an evolution-on consumer can detect a flag-mismatched producer cleanly.
   */
  private boolean isBeanValue() {
    return TypeUtils.isBean(
        valType, new TypeResolutionContext(CustomTypeEncoderRegistry.customTypeHandler(), true));
  }

  private Supplier<MapEncoder<M>> buildVersioned() {
    Class<?> valClass = TypeUtils.getRawType(valType);
    SchemaHistory history = buildSchemaHistory(valClass);
    SchemaHistory.VersionedSchema current = history.current();

    // Generate per-combination row codec classes and per-combination map codec classes. The
    // suffix encodes the outer version plus each chosen inner-bean version so that distinct
    // cross-product entries do not collide on a single generated class.
    Map<Long, ProjectionMapFactory> projectionFactories = new HashMap<>();
    for (SchemaHistory.VersionedSchema vs : history.versions()) {
      if (vs == current) {
        continue;
      }
      String suffix = ProjectionRouting.projectionSuffix(vs);
      Map<Class<?>, String> nestedSuffixes = ProjectionRouting.nestedSuffixesFor(vs, codecFormat);
      Encoders.loadOrGenProjectionRowCodecClass(
          valClass, codecFormat, vs.schema(), vs.liveFieldNames(), suffix, nestedSuffixes);
      Class<?> mapClass =
          Encoders.loadOrGenProjectionMapCodecClass(
              mapType, TypeRef.of(valClass), codecFormat, suffix);
      MethodHandle ctor = Encoders.constructorHandleFor(mapClass, GeneratedMapEncoder.class);
      // Build a MapType whose value is the historical element struct, keeping the same key.
      Field individualKey = DataTypes.keyFieldForMap(field);
      Field histIndividualVal =
          DataTypes.field(
              DataTypes.MAP_VALUE_NAME, new DataTypes.StructType(vs.schema().fields()), true);
      Field histMapField = DataTypes.mapField(field.name(), individualKey, histIndividualVal);
      projectionFactories.put(vs.strictHash(), new ProjectionMapFactory(histMapField, ctor));
    }
    final var currentFactory = generatedMapEncoder();
    long currentHash = current.strictHash();
    return new Supplier<MapEncoder<M>>() {
      @Override
      public MapEncoder<M> get() {
        BinaryArrayWriter keyWriter = codecFormat.newArrayWriter(keyField);
        BinaryArrayWriter valWriter = codecFormat.newArrayWriter(valField, keyWriter.getBuffer());
        var codec = currentFactory.apply(keyWriter, valWriter);
        LongMap<BinaryMapEncoder.ProjectionMapCodec> proj =
            new LongMap<>(projectionFactories.size());
        for (Map.Entry<Long, ProjectionMapFactory> entry : projectionFactories.entrySet()) {
          proj.put(entry.getKey(), entry.getValue().instantiate(codecFormat, fory));
        }
        return new BufferResettingMapEncoder<>(
            initialBufferSize,
            keyWriter,
            valWriter,
            new BinaryMapEncoder<M>(
                codecFormat, field, valWriter, keyWriter, codec, sizeEmbedded, currentHash, proj));
      }
    };
  }

  private final class ProjectionMapFactory {
    private final Field histMapField;
    private final MethodHandle ctor;

    ProjectionMapFactory(Field histMapField, MethodHandle ctor) {
      this.histMapField = histMapField;
      this.ctor = ctor;
    }

    BinaryMapEncoder.ProjectionMapCodec instantiate(Encoding format, Fory fory) {
      try {
        Field histKeyField = DataTypes.keyArrayFieldForMap(histMapField);
        Field histValField = DataTypes.itemArrayFieldForMap(histMapField);
        BinaryArrayWriter projKey = format.newArrayWriter(histKeyField);
        BinaryArrayWriter projVal = format.newArrayWriter(histValField, projKey.getBuffer());
        Object[] references = {histKeyField, histValField, projKey, projVal, fory, histMapField};
        GeneratedMapEncoder codec = (GeneratedMapEncoder) ctor.invokeExact(references);
        return new BinaryMapEncoder.ProjectionMapCodec(format, histMapField, codec);
      } catch (Throwable e) {
        throw ExceptionUtils.throwException(e);
      }
    }
  }

  private void loadMapInnerCodecs() {
    Encoders.loadMapCodecs(keyType, codecFormat);
    Encoders.loadMapCodecs(valType, codecFormat);
  }

  BiFunction<BinaryArrayWriter, BinaryArrayWriter, GeneratedMapEncoder> generatedMapEncoder() {
    final Class<?> arrayCodecClass =
        Encoders.loadOrGenMapCodecClass(mapType, keyType, valType, codecFormat);

    final MethodHandle constructorHandle =
        Encoders.constructorHandleFor(arrayCodecClass, GeneratedMapEncoder.class);
    return new BiFunction<BinaryArrayWriter, BinaryArrayWriter, GeneratedMapEncoder>() {
      @Override
      public GeneratedMapEncoder apply(
          final BinaryArrayWriter keyWriter, final BinaryArrayWriter valWriter) {
        final Object[] references = {keyField, valField, keyWriter, valWriter, fory, field};
        try {
          return (GeneratedMapEncoder) constructorHandle.invokeExact(references);
        } catch (Throwable t) {
          throw ExceptionUtils.throwException(t);
        }
      }
    };
  }
}

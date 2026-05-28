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
import java.util.function.UnaryOperator;
import org.apache.fory.Fory;
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.format.row.binary.writer.CompactBinaryRowWriter;
import org.apache.fory.format.type.CustomTypeEncoderRegistry;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.Field;
import org.apache.fory.format.type.Schema;
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
    if (!schemaEvolution || !isVersionedBeanValue()) {
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
              new BinaryMapEncoder<M>(codecFormat, field, valWriter, keyWriter, codec, sizeEmbedded));
        }
      };
    }
    return buildVersioned();
  }

  private boolean isVersionedBeanValue() {
    return TypeUtils.isBean(
        valType,
        new TypeResolutionContext(CustomTypeEncoderRegistry.customTypeHandler(), true));
  }

  private Supplier<MapEncoder<M>> buildVersioned() {
    Class<?> valClass = TypeUtils.getRawType(valType);
    UnaryOperator<Schema> schemaTransform =
        codecFormat == CompactCodecFormat.INSTANCE
            ? CompactBinaryRowWriter::sortSchema
            : UnaryOperator.identity();
    SchemaHistory history = SchemaHistory.build(valClass, schemaTransform);
    SchemaHistory.VersionedSchema current = history.current();

    Map<Long, ProjectionMapFactory> projectionFactories = new HashMap<>();
    for (SchemaHistory.VersionedSchema vs : history.versions()) {
      if (vs == current) {
        continue;
      }
      String suffix = "_V" + vs.version();
      Encoders.loadOrGenProjectionRowCodecClass(
          valClass, codecFormat, vs.schema(), vs.liveFieldNames(), suffix);
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
        Map<Long, BinaryMapEncoder.ProjectionMapCodec> proj = new HashMap<>();
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

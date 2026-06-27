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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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

  // Strict hash for a map position that carries no versioned bean. Such a position has a single
  // fixed wire layout, so a constant identity is correct: it is the same on writer and reader and
  // simply leaves the combined hash determined by the position that does evolve. 0L is also a
  // legitimate FNV result for a real schema, so this sentinel's safety does not rest on 0L being
  // unreachable; it rests on the build-time collision guards in buildVersioned, which reject any
  // combined hash that duplicates another combination or the current schema.
  private static final long NON_BEAN_POSITION_HASH = 0L;

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
    if (!schemaEvolution || (evolutionBean() == null && keyEvolutionBean() == null)) {
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
   * Bean this map evolves on, reachable through the value type. A directly-typed bean (versioned or
   * not) takes the evolution path so the strict-hash prefix is always present and an evolution-on
   * consumer can detect a flag-mismatched producer cleanly; a versioned bean nested inside a
   * list/map/array value is found by descending the wrapper. Null when the value carries no bean.
   */
  private Class<?> evolutionBean() {
    return SchemaHistory.evolutionBean(
        valType, new TypeResolutionContext(CustomTypeEncoderRegistry.customTypeHandler(), true));
  }

  /**
   * Bean this map's key evolves on, reachable through the key type, mirroring {@link
   * #evolutionBean()} for the value. Null when the key carries no bean. A versioned key is read at
   * the matching historical layout selected by the map header's combined hash, so an evolving key
   * no longer corrupts silently.
   */
  private Class<?> keyEvolutionBean() {
    return SchemaHistory.evolutionBean(
        keyType, new TypeResolutionContext(CustomTypeEncoderRegistry.customTypeHandler(), true));
  }

  /**
   * Combine the key and value strict hashes into one 64-bit map-layout hash. The map header carries
   * a single hash, so it must identify the (key-version, value-version) combination jointly. FNV-1a
   * mix over the two 64-bit hashes. Two distinct combinations that collide here would map to one
   * projection codec, so {@link #buildVersioned} proves the combined hashes are unique at build
   * time rather than letting one combination silently overwrite another.
   */
  private static long combinedHash(long keyHash, long valHash) {
    long h = 0xcbf29ce484222325L;
    h = (h ^ keyHash) * 0x100000001b3L;
    h = (h ^ valHash) * 0x100000001b3L;
    return h;
  }

  /**
   * Versions for one map position: the history's entries, or a single null when it does not evolve.
   */
  private static List<SchemaHistory.VersionedSchema> positionVersions(SchemaHistory history) {
    return history == null ? Collections.singletonList(null) : history.versions();
  }

  /** Strict hash contributed by one position: its schema's hash, or the non-bean constant. */
  private static long positionHash(SchemaHistory.VersionedSchema vs) {
    return vs == null ? NON_BEAN_POSITION_HASH : vs.strictHash();
  }

  private Supplier<MapEncoder<M>> buildVersioned() {
    // Either position may carry a versioned bean. A position with no bean contributes a single
    // current-only layout, so the cross-product degenerates to the evolving position's versions (or
    // to just the current layout when neither evolves, i.e. a non-versioned bean that still needs
    // the hash prefix for flag-mismatch detection).
    Class<?> valClass = evolutionBean();
    Class<?> keyClass = keyEvolutionBean();
    SchemaHistory valHistory = valClass == null ? null : buildSchemaHistory(valClass);
    SchemaHistory keyHistory = keyClass == null ? null : buildSchemaHistory(keyClass);
    SchemaHistory.VersionedSchema valCurrent = valHistory == null ? null : valHistory.current();
    SchemaHistory.VersionedSchema keyCurrent = keyHistory == null ? null : keyHistory.current();

    // Generate one projection map codec per (key-version, value-version) combination, keyed by the
    // combined hash. The current/current combination is the hot path and is handled by the
    // unsuffixed current codec, so it is skipped here. A null history means that position does not
    // evolve and contributes only its current entry.
    List<SchemaHistory.VersionedSchema> valVersions = positionVersions(valHistory);
    List<SchemaHistory.VersionedSchema> keyVersions = positionVersions(keyHistory);
    Map<Long, ProjectionMapFactory> projectionFactories = new HashMap<>();
    for (SchemaHistory.VersionedSchema valVs : valVersions) {
      for (SchemaHistory.VersionedSchema keyVs : keyVersions) {
        if (valVs == valCurrent && keyVs == keyCurrent) {
          continue;
        }
        long hash = combinedHash(positionHash(keyVs), positionHash(valVs));
        ProjectionMapFactory previous =
            projectionFactories.put(
                hash,
                buildProjectionFactory(valClass, keyClass, valVs, keyVs, valCurrent, keyCurrent));
        if (previous != null) {
          throw new IllegalStateException(
              "Combined (key, value) schema-hash collision for map "
                  + mapType
                  + ": two distinct version combinations produced the same map-layout hash. "
                  + "Please file an issue with the key and value bean definitions.");
        }
      }
    }
    final var currentFactory = generatedMapEncoder();
    long currentHash = combinedHash(positionHash(keyCurrent), positionHash(valCurrent));
    // The decode hot path matches currentHash before consulting the projection map, so a projection
    // colliding with it would be shadowed and never dispatched to. Prove that cannot happen.
    if (projectionFactories.containsKey(currentHash)) {
      throw new IllegalStateException(
          "Combined (key, value) schema-hash collision for map "
              + mapType
              + ": a historical version combination produced the same map-layout hash as the "
              + "current schema. Please file an issue with the key and value bean definitions.");
    }
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

  /**
   * Generate the projection row codec(s) and projection map codec for one (key-version,
   * value-version) combination, and rebuild the map field with each position projected onto its
   * historical schema. A position at its current schema gets an empty suffix and keeps its current
   * field, so a map where only one side evolves pays no codegen for the unchanged side.
   */
  private ProjectionMapFactory buildProjectionFactory(
      Class<?> valClass,
      Class<?> keyClass,
      SchemaHistory.VersionedSchema valVs,
      SchemaHistory.VersionedSchema keyVs,
      SchemaHistory.VersionedSchema valCurrent,
      SchemaHistory.VersionedSchema keyCurrent) {
    Field histVal = DataTypes.itemFieldForMap(field);
    String valSuffix = "";
    if (valVs != valCurrent) {
      valSuffix = ProjectionRouting.projectionSuffix(valVs);
      Map<Class<?>, String> nested = ProjectionRouting.nestedSuffixesFor(valVs, codecFormat);
      Encoders.loadOrGenProjectionRowCodecClass(
          valClass, codecFormat, valVs.schema(), valVs.liveFieldNames(), valSuffix, nested);
      histVal = SchemaHistory.projectThroughWrapper(histVal, valType, valVs);
    }
    Field histKey = DataTypes.keyFieldForMap(field);
    String keySuffix = "";
    if (keyVs != keyCurrent) {
      keySuffix = ProjectionRouting.projectionSuffix(keyVs);
      Map<Class<?>, String> nested = ProjectionRouting.nestedSuffixesFor(keyVs, codecFormat);
      Encoders.loadOrGenProjectionRowCodecClass(
          keyClass, codecFormat, keyVs.schema(), keyVs.liveFieldNames(), keySuffix, nested);
      histKey = SchemaHistory.projectThroughWrapper(histKey, keyType, keyVs);
    }
    Class<?> mapClass =
        Encoders.loadOrGenProjectionMapCodecClass(
            mapType,
            TypeRef.of(valClass != null ? valClass : keyClass),
            codecFormat,
            valSuffix,
            keySuffix);
    MethodHandle ctor = Encoders.constructorHandleFor(mapClass, GeneratedMapEncoder.class);
    Field histMapField = DataTypes.mapField(field.name(), histKey, histVal);
    return new ProjectionMapFactory(histMapField, ctor);
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

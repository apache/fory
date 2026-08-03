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
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.apache.fory.Fory;
import org.apache.fory.collection.LongMap;
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.Field;
import org.apache.fory.format.type.SchemaHistory;
import org.apache.fory.format.type.TypeInference;
import org.apache.fory.reflect.TypeRef;
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
    final Class<?> valClass = schemaEvolution ? evolutionBean() : null;
    final Class<?> keyClass = schemaEvolution ? keyEvolutionBean() : null;
    if (valClass == null && keyClass == null) {
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
    return buildVersioned(valClass, keyClass);
  }

  /**
   * Whether the value position takes the evolution path, and a representative bean for naming. A
   * directly-typed bean (versioned or not) takes the path so the strict-hash prefix is always
   * present and an evolution-on consumer can detect a flag-mismatched producer cleanly; a bean
   * nested inside a list/map/array value is found by descending the wrapper. Null when the value
   * carries no bean. The per-version enumeration over every reachable bean in the value position is
   * done by {@link #buildElementSchemaHistory}.
   */
  private Class<?> evolutionBean() {
    return SchemaHistory.evolutionBean(valType, typeCtx());
  }

  /**
   * Bean this map's key evolves on, reachable through the key type, mirroring {@link
   * #evolutionBean()} for the value. Null when the key carries no bean. A versioned key is read at
   * the matching historical layout selected by the map header's combined hash, so an evolving key
   * no longer corrupts silently.
   */
  private Class<?> keyEvolutionBean() {
    return SchemaHistory.evolutionBean(keyType, typeCtx());
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

  private Supplier<MapEncoder<M>> buildVersioned(final Class<?> valClass, final Class<?> keyClass) {
    // The map's own key and value are independent positions on the wire (each its own array), and
    // the map header's combined hash identifies the (key-layout, value-layout) pair. Each position
    // is enumerated over every versioned bean reachable through its wrappers, so a position that
    // itself wraps more than one bean (such as a value typed Map<KBean, VBean>) evolves all of
    // them.
    // A position with no bean contributes a single current-only layout, so the cross-product
    // degenerates to the evolving position's versions (or to just the current layout when neither
    // evolves, i.e. a non-versioned bean that still needs the hash prefix for flag-mismatch
    // detection).
    SchemaHistory valHistory =
        valClass == null ? null : buildElementSchemaHistory(field.name(), valType);
    SchemaHistory keyHistory =
        keyClass == null ? null : buildElementSchemaHistory(field.name(), keyType);
    SchemaHistory.VersionedSchema valCurrent = valHistory == null ? null : valHistory.current();
    SchemaHistory.VersionedSchema keyCurrent = keyHistory == null ? null : keyHistory.current();

    // Index one deferred projection source per (key-version, value-version) combination, keyed by
    // the combined hash. The current/current combination is the hot path and is handled by the
    // unsuffixed current codec, so it is skipped here. A null history means that position does not
    // evolve and contributes only its current entry. Building the index compiles nothing: a
    // combination's codec classes are generated the first time its hash is decoded. The collision
    // guards below still run eagerly over the full cross-product, so a hash clash fails fast at
    // build rather than surfacing on an unlucky decode.
    //
    // The full cross-product is required even when key and value are the same bean family: a writer
    // can pin them to different versions via distinct type arguments (Map<DefaultsV1, DefaultsV2>),
    // so off-diagonal pairs are reachable. See evolveMapSameBeanKeyAndValueCrossCombos.
    List<SchemaHistory.VersionedSchema> valVersions = positionVersions(valHistory);
    List<SchemaHistory.VersionedSchema> keyVersions = positionVersions(keyHistory);
    LongMap<BinaryMapEncoder.ProjectionSource> projectionSources = new LongMap<>();
    for (SchemaHistory.VersionedSchema valVs : valVersions) {
      for (SchemaHistory.VersionedSchema keyVs : keyVersions) {
        if (valVs == valCurrent && keyVs == keyCurrent) {
          continue;
        }
        // The map header carries a single hash, so combine the key and value strict hashes into
        // one 64-bit map-layout hash that identifies the (key-version, value-version) combination
        // jointly. The collision check below proves these combined hashes are unique at build time.
        long hash = SchemaHistory.combineHashes(positionHash(keyVs), positionHash(valVs));
        if (projectionSources.containsKey(hash)) {
          throw new IllegalStateException(
              "Combined (key, value) schema-hash collision for map "
                  + mapType
                  + ": two distinct version combinations produced the same map-layout hash. "
                  + "Please file an issue with the key and value bean definitions.");
        }
        projectionSources.put(
            hash, new ProjectionSource(valClass, keyClass, valVs, keyVs, valCurrent, keyCurrent));
      }
    }
    final var currentFactory = generatedMapEncoder();
    long currentHash =
        SchemaHistory.combineHashes(positionHash(keyCurrent), positionHash(valCurrent));
    // The decode hot path matches currentHash before consulting the projection map, so a projection
    // colliding with it would be shadowed and never dispatched to. Prove that cannot happen.
    if (projectionSources.containsKey(currentHash)) {
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
        return new BufferResettingMapEncoder<>(
            initialBufferSize,
            keyWriter,
            valWriter,
            new BinaryMapEncoder<M>(
                codecFormat,
                field,
                valWriter,
                keyWriter,
                codec,
                sizeEmbedded,
                currentHash,
                projectionSources,
                fory));
      }
    };
  }

  /**
   * Generate the projection row codec(s) and projection map codec for one (key-version,
   * value-version) combination, and rebuild the map field with each position projected onto its
   * historical schema. A position at its current schema gets an empty suffix and keeps its current
   * field, so a map where only one side evolves pays no codegen for the unchanged side.
   */
  /**
   * The map position field projected onto {@code positionVs}: the substituted type from the
   * position's historical schema, but the current position field's name and nullability (a map key
   * is non-nullable, a value nullable), which forElement's inferred field does not carry.
   */
  private static Field projectedPositionField(
      Field currentField, SchemaHistory.VersionedSchema positionVs) {
    Field projected = DataTypes.fieldOfSchema(positionVs.schema(), 0);
    return DataTypes.field(currentField.name(), projected.type(), currentField.nullable());
  }

  /**
   * Deferred projection codec for one (key-version, value-version) combination. Holds only the
   * chosen versions; the per-position row codecs and the map codec class are generated on the first
   * {@link #compile} call (the first decode of this combination's combined hash), not at build
   * time. The build-time collision guards already proved this combination's hash is unique.
   */
  private final class ProjectionSource implements BinaryMapEncoder.ProjectionSource {
    private final Class<?> valClass;
    private final Class<?> keyClass;
    private final SchemaHistory.VersionedSchema valVs;
    private final SchemaHistory.VersionedSchema keyVs;
    private final SchemaHistory.VersionedSchema valCurrent;
    private final SchemaHistory.VersionedSchema keyCurrent;

    ProjectionSource(
        Class<?> valClass,
        Class<?> keyClass,
        SchemaHistory.VersionedSchema valVs,
        SchemaHistory.VersionedSchema keyVs,
        SchemaHistory.VersionedSchema valCurrent,
        SchemaHistory.VersionedSchema keyCurrent) {
      this.valClass = valClass;
      this.keyClass = keyClass;
      this.valVs = valVs;
      this.keyVs = keyVs;
      this.valCurrent = valCurrent;
      this.keyCurrent = keyCurrent;
    }

    @Override
    public BinaryMapEncoder.ProjectionMapCodec compile(Encoding format, Fory fory) {
      // Each position's history is forElement over the position type, so its schema's single field
      // is the position field with every reachable bean projected onto this combination, and
      // nestedSuffixesFor routes each bean class in the position to its own historical row codec.
      // The projected field carries only the substituted type; the position's own nullability is
      // taken from the current map field (map keys are non-nullable, values nullable).
      Field currentVal = DataTypes.itemFieldForMap(field);
      Field histVal = currentVal;
      String valSuffix = "";
      Map<Class<?>, String> valNested = null;
      if (valVs != null && valVs != valCurrent) {
        valSuffix = ProjectionRouting.projectionSuffix(valVs);
        valNested = ProjectionRouting.nestedSuffixesFor(valVs, codecFormat);
        histVal = projectedPositionField(currentVal, valVs);
      }
      Field currentKey = DataTypes.keyFieldForMap(field);
      Field histKey = currentKey;
      String keySuffix = "";
      Map<Class<?>, String> keyNested = null;
      if (keyVs != null && keyVs != keyCurrent) {
        keySuffix = ProjectionRouting.projectionSuffix(keyVs);
        keyNested = ProjectionRouting.nestedSuffixesFor(keyVs, codecFormat);
        histKey = projectedPositionField(currentKey, keyVs);
      }
      Class<?> mapClass =
          Encoders.loadOrGenProjectionMapCodecClass(
              mapType,
              TypeRef.of(valClass != null ? valClass : keyClass),
              codecFormat,
              valSuffix,
              keySuffix,
              valNested,
              keyNested);
      MethodHandle ctor = Encoders.constructorHandleFor(mapClass, GeneratedMapEncoder.class);
      Field histMapField = DataTypes.mapField(field.name(), histKey, histVal);
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

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
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.apache.fory.Fory;
import org.apache.fory.codegen.CodeGenerator;
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
    final CodeGenerator codeGenerator = schemaEvolution ? Encoders.pinCodeGenerator() : null;
    loadMapInnerCodecs();
    if (!schemaEvolution) {
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
    return buildVersioned(
        SchemaHistory.evolutionBean(valType), SchemaHistory.evolutionBean(keyType), codeGenerator);
  }

  /**
   * Strict hash of one map position at {@code positionVs}: the position's one-field layout with the
   * nullability the map writes it with (a key is non-nullable, a value nullable), which the element
   * history's inferred field does not carry. A position without a bean still hashes its type, so a
   * change to it changes the map hash.
   */
  private long positionHash(SchemaHistory.VersionedSchema positionVs, Field wireField) {
    Field position = DataTypes.fieldOfSchema(positionVs.schema(), 0);
    return SchemaHistory.computeStrictSchemaHash(
        codecFormat.historyFormat(),
        DataTypes.schema(DataTypes.field(position.name(), position.type(), wireField.nullable())));
  }

  /**
   * The evolution path, taken by every map codec with the flag on so that the payload always
   * carries the combined key and value strict hash. {@code valClass} and {@code keyClass} name
   * generated projection classes; a position without a reachable bean has one version and no
   * projection.
   */
  private Supplier<MapEncoder<M>> buildVersioned(
      final Class<?> valClass, final Class<?> keyClass, final CodeGenerator codeGenerator) {
    // The map's own key and value are independent positions on the wire (each its own array), and
    // the map header's combined hash identifies the (key-layout, value-layout) pair. Each position
    // is enumerated over every versioned bean reachable through its wrappers, so a position that
    // itself wraps more than one bean (such as a value typed Map<KBean, VBean>) evolves all of
    // them.
    // A position with no versioned bean has a single current-only layout, so the cross-product
    // degenerates to the evolving position's versions (or to just the current layout when neither
    // evolves, i.e. a non-versioned bean that still needs the hash prefix for flag-mismatch
    // detection).
    SchemaHistory valHistory = buildElementSchemaHistory(field.name(), valType);
    SchemaHistory keyHistory = buildElementSchemaHistory(field.name(), keyType);
    SchemaHistory.VersionedSchema valCurrent = valHistory.current();
    SchemaHistory.VersionedSchema keyCurrent = keyHistory.current();
    Field valWire = DataTypes.itemFieldForMap(field);
    Field keyWire = DataTypes.keyFieldForMap(field);

    // Index one deferred projection source per (key-version, value-version) combination, keyed by
    // the combined hash. The current/current combination is the hot path and is handled by the
    // unsuffixed current codec, so it is skipped here. Building the index compiles nothing: a
    // combination's codec classes are generated the first time its hash is decoded. The collision
    // guards below still run eagerly over the full cross-product, so a hash clash fails fast at
    // build rather than surfacing on an unlucky decode.
    //
    // A writer has one definition of each bean class, so a pair that puts a class reached from
    // both the key and the value at two versions cannot occur on the wire; skip it, as the row
    // enumeration does.
    LongMap<MapProjectionSource> projectionSources = new LongMap<>();
    for (SchemaHistory.VersionedSchema valVs : valHistory.versions()) {
      for (SchemaHistory.VersionedSchema keyVs : keyHistory.versions()) {
        if (valVs == valCurrent && keyVs == keyCurrent
            || !SchemaHistory.sameClassVersions(keyVs, valVs)) {
          continue;
        }
        // The map header carries a single hash, so combine the key and value strict hashes into
        // one 64-bit map-layout hash that identifies the (key-version, value-version) combination
        // jointly. The collision check below proves these combined hashes are unique at build time.
        long hash =
            SchemaHistory.combineHashes(positionHash(keyVs, keyWire), positionHash(valVs, valWire));
        if (projectionSources.containsKey(hash)) {
          throw new IllegalStateException(
              "Combined (key, value) schema-hash collision for map "
                  + mapType
                  + ": two distinct version combinations produced the same map-layout hash. "
                  + "Please file an issue with the key and value bean definitions.");
        }
        projectionSources.put(
            hash,
            new MapProjectionSource(
                mapType,
                field,
                codecFormat,
                valClass,
                keyClass,
                valVs,
                keyVs,
                valCurrent,
                keyCurrent,
                codeGenerator));
      }
    }
    final var currentFactory = generatedMapEncoder();
    long currentHash =
        SchemaHistory.combineHashes(
            positionHash(keyCurrent, keyWire), positionHash(valCurrent, valWire));
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
                new ProjectionCache<>(projectionSources, fory)));
      }
    };
  }

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
   * Deferred projection codec for one (key-version, value-version) combination, shared by every
   * encoder of the codec. The per-position row codecs and the map codec class are generated on the
   * first {@link #compile} call (the first decode of this combination's combined hash by any
   * encoder), not at build time. The build-time collision guards already proved this combination's
   * hash is unique. Static and built from values captured at build time, so a later change to the
   * builder cannot reach a lazy compile.
   */
  private static final class MapProjectionSource
      extends ProjectionSource<MapProjectionSource.Classes, BinaryMapEncoder.ProjectionMapCodec> {
    private final TypeRef<? extends Map<?, ?>> mapType;
    private final Field field;
    private final Encoding codecFormat;
    private final Class<?> valClass;
    private final Class<?> keyClass;
    private final SchemaHistory.VersionedSchema valVs;
    private final SchemaHistory.VersionedSchema keyVs;
    private final SchemaHistory.VersionedSchema valCurrent;
    private final SchemaHistory.VersionedSchema keyCurrent;
    private final CodeGenerator codeGenerator;

    MapProjectionSource(
        TypeRef<? extends Map<?, ?>> mapType,
        Field field,
        Encoding codecFormat,
        Class<?> valClass,
        Class<?> keyClass,
        SchemaHistory.VersionedSchema valVs,
        SchemaHistory.VersionedSchema keyVs,
        SchemaHistory.VersionedSchema valCurrent,
        SchemaHistory.VersionedSchema keyCurrent,
        CodeGenerator codeGenerator) {
      this.mapType = mapType;
      this.field = field;
      this.codecFormat = codecFormat;
      this.valClass = valClass;
      this.keyClass = keyClass;
      this.valVs = valVs;
      this.keyVs = keyVs;
      this.valCurrent = valCurrent;
      this.keyCurrent = keyCurrent;
      this.codeGenerator = codeGenerator;
    }

    /**
     * Generate the projection row codec(s) and projection map codec for one (key-version,
     * value-version) combination, and rebuild the map field with each position projected onto its
     * historical schema. A position at its current schema gets an empty suffix and keeps its
     * current field, so a map where only one side evolves pays no codegen for the unchanged side.
     */
    @Override
    BinaryMapEncoder.ProjectionMapCodec bind(Classes r, Fory fory) {
      try {
        BinaryArrayWriter projKey = codecFormat.newArrayWriter(r.keyArrayField);
        BinaryArrayWriter projVal =
            codecFormat.newArrayWriter(r.valueArrayField, projKey.getBuffer());
        Object[] references = {
          r.keyArrayField, r.valueArrayField, projKey, projVal, fory, r.mapField
        };
        GeneratedMapEncoder codec = (GeneratedMapEncoder) r.ctor.invokeExact(references);
        return new BinaryMapEncoder.ProjectionMapCodec(codecFormat, r.mapField, codec);
      } catch (Throwable e) {
        throw ExceptionUtils.throwException(e);
      }
    }

    @Override
    Classes generate() {
      // Each position's history is forElement over the position type, so its schema's single field
      // is the position field with every reachable bean projected onto this combination, and
      // nestedSuffixesFor routes each bean class in the position to its own historical row codec.
      // sameClassVersions holds for every indexed combination, so a class that both positions
      // reach gets the same suffix from either side and one class-keyed map serves both.
      // The projected field carries only the substituted type; the position's own nullability is
      // taken from the current map field (map keys are non-nullable, values nullable).
      Field currentVal = DataTypes.itemFieldForMap(field);
      Field histVal = currentVal;
      String valSuffix = "";
      Map<Class<?>, String> nested = null;
      if (valVs != valCurrent) {
        valSuffix = ProjectionRouting.projectionSuffix(valVs);
        nested = ProjectionRouting.nestedSuffixesFor(valVs, codecFormat, codeGenerator);
        histVal = projectedPositionField(currentVal, valVs);
      }
      Field currentKey = DataTypes.keyFieldForMap(field);
      Field histKey = currentKey;
      String keySuffix = "";
      if (keyVs != keyCurrent) {
        keySuffix = ProjectionRouting.projectionSuffix(keyVs);
        Map<Class<?>, String> keyNested =
            ProjectionRouting.nestedSuffixesFor(keyVs, codecFormat, codeGenerator);
        if (nested == null) {
          nested = keyNested;
        } else {
          nested.putAll(keyNested);
        }
        histKey = projectedPositionField(currentKey, keyVs);
      }
      Class<?> mapClass =
          Encoders.loadOrGenProjectionMapCodecClass(
              mapType,
              TypeRef.of(valClass != null ? valClass : keyClass),
              codecFormat,
              valSuffix,
              keySuffix,
              nested,
              codeGenerator);
      Field histMapField = DataTypes.mapField(field.name(), histKey, histVal);
      return new Classes(
          Encoders.constructorHandleFor(mapClass, GeneratedMapEncoder.class),
          histMapField,
          DataTypes.keyArrayFieldForMap(histMapField),
          DataTypes.itemArrayFieldForMap(histMapField));
    }

    static final class Classes {
      final MethodHandle ctor;
      final Field mapField;
      final Field keyArrayField;
      final Field valueArrayField;

      Classes(MethodHandle ctor, Field mapField, Field keyArrayField, Field valueArrayField) {
        this.ctor = ctor;
        this.mapField = mapField;
        this.keyArrayField = keyArrayField;
        this.valueArrayField = valueArrayField;
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
        } catch (final Throwable t) {
          throw ExceptionUtils.throwException(t);
        }
      }
    };
  }
}

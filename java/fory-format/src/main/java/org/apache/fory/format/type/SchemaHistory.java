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

package org.apache.fory.format.type;

import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import org.apache.fory.annotation.Internal;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.format.annotation.ForySchema;
import org.apache.fory.format.annotation.ForyVersion;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.TypeResolutionContext;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.util.StringUtils;

/**
 * Resolves the version history of a row-codec bean. Each entry exposes the schema as it appeared at
 * a particular version, along with a strict hash that uniquely identifies the historical layout.
 * Only used when {@code withSchemaEvolution()} is configured on the codec builder.
 *
 * <p>The hash mixes field names and nullability in addition to types, so that two schemas that
 * differ only in field order or naming are distinguishable. This is intentionally a different hash
 * from {@link DataTypes#computeSchemaHash} and is used only by versioning code paths.
 */
@Internal
public final class SchemaHistory {

  /** Implicit version of a live field that carries no {@link ForyVersion}. */
  private static final int FIRST_VERSION = 1;

  /** One entry in a {@link SchemaHistory}. */
  public static final class VersionedSchema {
    private final int version;
    private final Schema schema;
    private final long strictHash;
    private final boolean current;
    private final Set<String> liveFieldNames;
    private final Map<Class<?>, VersionedSchema> nestedBeanSchemas;

    VersionedSchema(
        int version,
        Schema schema,
        long strictHash,
        boolean current,
        Set<String> liveFieldNames,
        Map<Class<?>, VersionedSchema> nestedBeanSchemas) {
      this.version = version;
      this.schema = schema;
      this.strictHash = strictHash;
      this.current = current;
      this.liveFieldNames = liveFieldNames;
      this.nestedBeanSchemas = nestedBeanSchemas;
    }

    public int version() {
      return version;
    }

    public Schema schema() {
      return schema;
    }

    public long strictHash() {
      return strictHash;
    }

    /**
     * True when this entry is its bean's current (writer-side) schema. Routing uses this to decide
     * whether a nested-bean slot embeds the current-version codec class (no suffix) or a historical
     * projection class.
     */
    public boolean isCurrent() {
      return current;
    }

    /**
     * Names of fields in this version that still have a Java member on the current bean class.
     * Other fields are read-and-discarded during projection.
     */
    public Set<String> liveFieldNames() {
      return liveFieldNames;
    }

    /**
     * For each nested versioned bean class referenced by this schema, the exact inner entry chosen
     * for this combination. Empty when the schema has no nested versioned beans. Each value carries
     * its own {@code strictHash} and {@code nestedBeanSchemas}, so routing can identify and recurse
     * into the inner subtree to arbitrary depth without re-deriving it from a version number.
     *
     * <p>Keyed by class, not by field. A writer writes one definition of a given bean class, so
     * every field of that class in a single payload is at the same version; the enumeration carries
     * one entry per class, and a class may back more than one field.
     */
    public Map<Class<?>, VersionedSchema> nestedBeanSchemas() {
      return nestedBeanSchemas;
    }
  }

  private final List<VersionedSchema> versions;
  private final VersionedSchema current;

  private SchemaHistory(List<VersionedSchema> versions, VersionedSchema current) {
    this.versions = versions;
    this.current = current;
  }

  /**
   * The writer-side schema (latest version, all nested beans current). This is the same {@link
   * VersionedSchema} instance that also appears in {@link #versions()}, so callers building
   * per-version projection codecs may skip it with a reference identity check ({@code vs ==
   * current()}) rather than comparing hashes.
   */
  public VersionedSchema current() {
    return current;
  }

  /**
   * All distinct historical schemas, in build order: outer version ascending, and within an outer
   * version, one entry per nested cross-product combination. This is not a strict sort by {@link
   * VersionedSchema#version()} — collapsing field-set duplicates can place a later combination in an
   * earlier slot. Dispatch keys on the strict hash, so callers must not rely on positional order.
   */
  public List<VersionedSchema> versions() {
    return versions;
  }

  /**
   * Build a history from the bean's annotations. The schema for each version is transformed by
   * {@code schemaTransform} after filtering; pass an identity for standard format, or {@code
   * CompactBinaryRowWriter::sortSchema} for compact format.
   */
  public static SchemaHistory build(Class<?> beanClass, UnaryOperator<Schema> schemaTransform) {
    ForySchema schemaAnn = beanClass.getAnnotation(ForySchema.class);
    Class<?> removedFieldsClass = schemaAnn == null ? void.class : schemaAnn.removedFields();

    List<FieldEntry> all = collectLiveFields(beanClass);
    if (removedFieldsClass != void.class) {
      all.addAll(collectRemovedFields(removedFieldsClass));
    }

    // Recursively expand any nested versioned bean field's own history. A versioned bean can be the
    // field type directly, or the element of a list, or the value of a map; we locate it at any of
    // those sites so the outer's enumeration can cross-product over the inner's versions. The inner
    // schema substitutes back into the same site at materialization time.
    //
    // This recursion needs no cycle guard. TypeInference.inferField calls ctx.checkNoCycle on every
    // bean it descends into, and RowCodecBuilder runs inferSchema in its constructor before build()
    // reaches here, so a self-referential bean is already rejected. Recursion depth is bounded by
    // the acyclic nesting of distinct versioned bean types.
    for (FieldEntry fe : all) {
      Class<?> nested = findVersionedBean(fe.typeRef);
      if (nested != null) {
        fe.nestedBeanClass = nested;
        fe.innerHistory = build(nested, schemaTransform);
      }
    }

    // Materialize a schema at every version V where the field set changes — both "since" and
    // "until" boundaries qualify, because either adds or removes a field from the active set.
    // FIRST_VERSION is always materialized even when every field declares since >= 2: a payload
    // written before those fields existed carries the v1 layout, so that schema must be decodable.
    TreeSet<Integer> schemaVersions = new TreeSet<>();
    schemaVersions.add(FIRST_VERSION);
    for (FieldEntry fe : all) {
      schemaVersions.add(fe.since);
      if (fe.until != Integer.MAX_VALUE) {
        schemaVersions.add(fe.until);
      }
    }

    validateNoNameCollision(all);

    // Sort by Java member name so the per-version schema matches the order
    // TypeInference.inferSchema produces (which iterates Descriptor.getDescriptors, alphabetical
    // by Java member name). Removed fields synthesize a Java name from their wire name.
    all.sort((a, b) -> a.javaName.compareTo(b.javaName));
    // A field with finite [since, until) can leave two boundaries with identical field sets
    // (e.g. v1 and v4 both lack a field that lived in [v2, v4)). Collapse boundaries that
    // produce the same schema into one VersionedSchema, since they round-trip identically.
    // A real strict-hash collision — two distinct schemas producing the same hash — is caught by
    // comparing schemas on insertion. Schema.equals compares fields by name, DataType, and
    // nullability, the same identity the strict hash mixes, so it is the canonical dedup key.
    int latestVersion = schemaVersions.last();
    Map<Schema, VersionedSchema> bySchema = new LinkedHashMap<>();
    Map<Long, Schema> hashToSchema = new HashMap<>();
    Schema currentSchema = null;
    for (int v : schemaVersions) {
      List<FieldEntry> activeEntries = new ArrayList<>();
      for (FieldEntry fe : all) {
        if (fe.since <= v && v < fe.until) {
          activeEntries.add(fe);
        }
      }
      // Cross-product over each nested versioned bean *class*, not each field. A writer always
      // writes one definition of a given bean class, so every field of that class in a single
      // payload is at the same version; the off-diagonal combinations (the same class at two
      // versions in one record) are unreachable on the wire. Enumerating one dimension per class
      // keeps the count a product over distinct nested classes rather than over fields, and lets
      // a class appear in more than one field. If no entries have nested histories, this yields a
      // single combination.
      //
      // The class count generated downstream is the product of the per-class version counts. If
      // that growth becomes a concern, drop entries from each bean's History interface once you
      // no longer need to read payloads from that range — that removes the corresponding
      // VersionedSchema from this enumeration. Retiring history entries is purely a read-side
      // concern; the writer always uses the current schema.
      LinkedHashMap<Class<?>, List<VersionedSchema>> innerChoices = new LinkedHashMap<>();
      for (FieldEntry fe : activeEntries) {
        if (fe.innerHistory != null) {
          innerChoices.putIfAbsent(fe.nestedBeanClass, fe.innerHistory.versions());
        }
      }
      for (Map<Class<?>, VersionedSchema> combination : cartesian(innerChoices)) {
        List<Field> fields = new ArrayList<>(activeEntries.size());
        Set<String> liveNames = new HashSet<>();
        for (FieldEntry fe : activeEntries) {
          Field current = TypeInference.inferNamedField(fe.name, fe.typeRef);
          VersionedSchema innerVs =
              fe.nestedBeanClass == null ? null : combination.get(fe.nestedBeanClass);
          // Substitute the chosen inner version's struct into the bean's site (direct field,
          // list element, or map value), keeping the collection wrapper intact.
          Field field =
              innerVs == null
                  ? current
                  : substituteNestedStruct(
                      current, fe.typeRef, new DataTypes.StructType(innerVs.schema().fields()));
          fields.add(field);
          if (fe.live) {
            liveNames.add(fe.name);
          }
        }
        Schema schema = schemaTransform.apply(new Schema(fields));
        long hash = computeStrictSchemaHash(schema);
        Schema previousSchema = hashToSchema.putIfAbsent(hash, schema);
        if (previousSchema != null && !previousSchema.equals(schema)) {
          throw new IllegalStateException(
              "Strict hash collision for bean "
                  + beanClass.getName()
                  + " at version "
                  + v
                  + ": two distinct historical schemas hashed to the same value. Please file an "
                  + "issue with the bean definition.");
        }
        // This combination represents the writer-side configuration at outer version v only when
        // every chosen inner is itself that inner's current schema. The bean's own current schema
        // is the writer-side configuration at the latest version.
        boolean innerAllCurrent = true;
        for (VersionedSchema inner : combination.values()) {
          if (!inner.isCurrent()) {
            innerAllCurrent = false;
            break;
          }
        }
        boolean isCurrent = v == latestVersion && innerAllCurrent;
        VersionedSchema vs =
            new VersionedSchema(
                v,
                schema,
                hash,
                isCurrent,
                Collections.unmodifiableSet(liveNames),
                Collections.unmodifiableMap(new HashMap<>(combination)));
        // Prefer the all-current combination on collapse so the stored VS's nestedBeanSchemas
        // map reflects the writer-side state at this outer version. This guards a contract on
        // current().nestedBeanSchemas() in case two combinations ever canonicalize to the same
        // schema; today's inner-by-schema collapse means inner.versions() has no wire-equal
        // duplicates, but the guard preserves the invariant for future callers.
        if (innerAllCurrent) {
          bySchema.put(schema, vs);
        } else {
          bySchema.putIfAbsent(schema, vs);
        }
        if (isCurrent) {
          currentSchema = schema;
        }
      }
    }
    // The all-current combination at the latest version is always one of the cartesian entries,
    // so currentSchema is always set and present here.
    VersionedSchema current = bySchema.get(currentSchema);
    if (current == null) {
      throw new IllegalStateException("No current schema resolved for bean " + beanClass.getName());
    }
    return new SchemaHistory(
        Collections.unmodifiableList(new ArrayList<>(bySchema.values())), current);
  }

  /** Cartesian product over (nested bean class, list-of-inner-VersionedSchema). */
  private static List<Map<Class<?>, VersionedSchema>> cartesian(
      LinkedHashMap<Class<?>, List<VersionedSchema>> choices) {
    List<Map<Class<?>, VersionedSchema>> out = new ArrayList<>();
    out.add(new HashMap<>());
    for (Map.Entry<Class<?>, List<VersionedSchema>> choice : choices.entrySet()) {
      Class<?> cls = choice.getKey();
      List<VersionedSchema> options = choice.getValue();
      List<Map<Class<?>, VersionedSchema>> next = new ArrayList<>(out.size() * options.size());
      for (Map<Class<?>, VersionedSchema> prefix : out) {
        for (VersionedSchema opt : options) {
          Map<Class<?>, VersionedSchema> extended = new HashMap<>(prefix);
          extended.put(cls, opt);
          next.add(extended);
        }
      }
      out = next;
    }
    return out;
  }

  /**
   * Bean a top-level array/map codec evolves on, reachable through {@code elementType} (the array
   * element or map value). Descends list/map/array wrappers and returns the bean at the leaf,
   * matching the way {@link #findVersionedBean} descends. The bean need not be versioned: an
   * unversioned bean must still take the evolution path so the strict-hash prefix is always present
   * and the producer and consumer stay wire-compatible. Returns null when no bean is reachable and
   * the codec needs no projection. Map keys are not inspected; they are always read at the current
   * schema.
   */
  public static Class<?> evolutionBean(TypeRef<?> elementType, TypeResolutionContext typeCtx) {
    Class<?> raw = TypeUtils.getRawType(elementType);
    if (raw == null) {
      return null;
    }
    if (raw.isArray() || TypeUtils.isCollection(raw)) {
      return evolutionBean(elementTypeRef(elementType, raw), typeCtx);
    }
    if (TypeUtils.isMap(raw)) {
      return evolutionBean(TypeUtils.getMapKeyValueType(elementType).f1, typeCtx);
    }
    return TypeUtils.isBean(TypeRef.of(raw), typeCtx) ? raw : null;
  }

  /**
   * Project {@code currentField} (an array element or map value field at the bean's current schema)
   * onto {@code historical}, swapping the bean's struct while keeping any list/map/array wrapper.
   * For a directly-typed bean this is just the historical struct; for {@code List<Bean>} or {@code
   * Map<K, Bean>} the wrapper is preserved around the historical struct.
   */
  public static Field projectThroughWrapper(
      Field currentField, TypeRef<?> elementType, VersionedSchema historical) {
    return substituteNestedStruct(
        currentField, elementType, new DataTypes.StructType(historical.schema().fields()));
  }

  /**
   * Find the versioned bean reachable from a field type: the field type itself, a list/array
   * element, or a map value. Returns null when no versioned bean is present. Map keys are not
   * inspected: they carry no per-payload hash on the wire and are always read with the current
   * schema, so enumerating key versions would only generate projection codecs that decode never
   * dispatches to.
   */
  private static Class<?> findVersionedBean(TypeRef<?> typeRef) {
    Class<?> raw = TypeUtils.getRawType(typeRef);
    if (raw == null) {
      return null;
    }
    if (raw.isArray() || TypeUtils.isCollection(raw)) {
      return findVersionedBean(elementTypeRef(typeRef, raw));
    }
    if (TypeUtils.isMap(raw)) {
      Tuple2<TypeRef<?>, TypeRef<?>> kv = TypeUtils.getMapKeyValueType(typeRef);
      return findVersionedBean(kv.f1);
    }
    return isBeanWithVersioning(raw) ? raw : null;
  }

  /**
   * Replace the nested bean's struct in {@code current} (the field at the bean's current schema)
   * with {@code historical}, keeping any list/map wrapper. The bean sits at the field, the
   * list/array element, or the map value, matching {@link #findVersionedBean}.
   */
  private static Field substituteNestedStruct(
      Field current, TypeRef<?> typeRef, DataTypes.StructType historical) {
    Class<?> raw = TypeUtils.getRawType(typeRef);
    if (raw != null && (raw.isArray() || TypeUtils.isCollection(raw))) {
      Field element =
          substituteNestedStruct(
              DataTypes.arrayElementField(current), elementTypeRef(typeRef, raw), historical);
      return DataTypes.arrayField(current.name(), element);
    }
    if (raw != null && TypeUtils.isMap(raw)) {
      Tuple2<TypeRef<?>, TypeRef<?>> kv = TypeUtils.getMapKeyValueType(typeRef);
      Field keyField = DataTypes.keyFieldForMap(current);
      Field itemField =
          substituteNestedStruct(DataTypes.itemFieldForMap(current), kv.f1, historical);
      return DataTypes.mapField(current.name(), keyField, itemField);
    }
    return DataTypes.field(current.name(), historical, current.nullable());
  }

  /**
   * Element type of a list/array field, derived the same way {@link TypeInference} does: arrays use
   * the component type, iterables use the element type.
   */
  private static TypeRef<?> elementTypeRef(TypeRef<?> typeRef, Class<?> raw) {
    return raw.isArray() ? typeRef.getComponentType() : TypeUtils.getElementType(typeRef);
  }

  /** True if the class is a row-codec bean and carries any schema-evolution annotations. */
  private static boolean isBeanWithVersioning(Class<?> cls) {
    if (cls.isAnnotationPresent(ForySchema.class)) {
      return true;
    }
    // Only introspect classes the row format actually treats as beans. TypeInference.inferField
    // routes collection/map/array/enum field types away from Descriptor.getDescriptors, so a
    // collection subclass that shadows a field name across its hierarchy round-trips fine even
    // though getDescriptors would reject it. Gating on isBean keeps this probe consistent with
    // inferField; getDescriptors then only throws for a class that genuinely cannot be a bean,
    // which fails identically on the real encode/decode path. Use the same synthesize-interfaces
    // context as inferField and the top-level array/map entry point (evolutionBean), so an
    // interface bean nested as a field type, list element, or map value is discovered as a bean
    // rather than rejected; otherwise its older versions are never enumerated and an older payload
    // decodes at the interface's current layout.
    TypeResolutionContext typeCtx =
        new TypeResolutionContext(CustomTypeEncoderRegistry.customTypeHandler(), true);
    if (!TypeUtils.isBean(cls, typeCtx)) {
      return false;
    }
    for (Descriptor d : Descriptor.getDescriptors(cls)) {
      if (lookupForyVersion(d) != null) {
        return true;
      }
    }
    return false;
  }

  private static List<FieldEntry> collectRemovedFields(Class<?> historyClass) {
    List<Descriptor> descriptors = Descriptor.getDescriptors(historyClass);
    List<FieldEntry> out = new ArrayList<>(descriptors.size());
    for (Descriptor d : descriptors) {
      ForyVersion ann = lookupForyVersion(d);
      if (ann == null) {
        throw new IllegalStateException(
            "Removed-field declaration "
                + historyClass.getName()
                + "."
                + d.getName()
                + " requires a @ForyVersion(until = ...) annotation");
      }
      if (ann.until() == Integer.MAX_VALUE) {
        throw new IllegalStateException(
            "Removed-field declaration "
                + historyClass.getName()
                + "."
                + d.getName()
                + " must specify @ForyVersion.until (no upper bound makes no sense for a field "
                + "that has been removed)");
      }
      if (ann.since() < FIRST_VERSION) {
        throw new IllegalStateException(
            "Invalid @ForyVersion on "
                + historyClass.getName()
                + "."
                + d.getName()
                + ": since ("
                + ann.since()
                + ") must be >= "
                + FIRST_VERSION
                + " (the first schema version). A since below that adds a version no writer can "
                + "emit.");
      }
      if (ann.since() >= ann.until()) {
        throw new IllegalStateException(
            "Invalid @ForyVersion on "
                + historyClass.getName()
                + "."
                + d.getName()
                + ": since ("
                + ann.since()
                + ") must be strictly less than until ("
                + ann.until()
                + ")");
      }
      // The history method's name must mirror the live field/method name. Wire names are
      // derived the same way the live path derives them: descriptor name -> lower_underscore.
      // For Lombok @Data or record-style beans the descriptor name is the field name
      // ("tags"); for interface beans or JavaBean-style classes it is the method name
      // ("getTags"). The user writes the history method to match.
      String wireName = StringUtils.lowerCamelToLowerUnderscore(d.getName());
      out.add(
          new FieldEntry(
              wireName, d.getName(), d.getTypeRef(), ann.since(), ann.until(), /*live*/ false));
    }
    return out;
  }

  private static List<FieldEntry> collectLiveFields(Class<?> beanClass) {
    List<Descriptor> descriptors = Descriptor.getDescriptors(beanClass);
    List<FieldEntry> out = new ArrayList<>(descriptors.size());
    for (Descriptor d : descriptors) {
      ForyVersion ann = lookupForyVersion(d);
      int since = ann == null ? FIRST_VERSION : ann.since();
      int until = ann == null ? Integer.MAX_VALUE : ann.until();
      if (since < FIRST_VERSION) {
        throw new IllegalStateException(
            "Invalid @ForyVersion on "
                + beanClass.getName()
                + "."
                + d.getName()
                + ": since ("
                + since
                + ") must be >= "
                + FIRST_VERSION
                + " (the first schema version). A since below that adds a version no writer can "
                + "emit.");
      }
      // A live field still exists as a Java member, so it has no end-of-life version. A finite
      // until would silently drop it from the current schema (until extends the version set, so
      // latestVersion >= until excludes the field), and the writer would stop serializing a field
      // the bean still has. Removals are declared on the history class via
      // @ForySchema.removedFields.
      if (until != Integer.MAX_VALUE) {
        throw new IllegalStateException(
            "Invalid @ForyVersion on "
                + beanClass.getName()
                + "."
                + d.getName()
                + ": a live field must not set until ("
                + until
                + "). Declare removed fields on the @ForySchema.removedFields history class instead.");
      }
      // No since/until ordering check here: a live field always has until == MAX_VALUE (enforced
      // above), so the ordering check lives only on the removed-field path in collectRemovedFields.
      String wireName = StringUtils.lowerCamelToLowerUnderscore(d.getName());
      out.add(new FieldEntry(wireName, d.getName(), d.getTypeRef(), since, until, /*live*/ true));
    }
    return out;
  }

  private static ForyVersion lookupForyVersion(Descriptor d) {
    ForyVersion ann = readAnnotation(d.getField());
    if (ann != null) {
      return ann;
    }
    return readAnnotation(d.getReadMethod());
  }

  private static ForyVersion readAnnotation(AnnotatedElement element) {
    return element == null ? null : element.getAnnotation(ForyVersion.class);
  }

  private static void validateNoNameCollision(List<FieldEntry> entries) {
    // For each pair with the same name, their [since, until) windows must not overlap.
    Map<String, List<FieldEntry>> byName = new HashMap<>();
    for (FieldEntry fe : entries) {
      byName.computeIfAbsent(fe.name, k -> new ArrayList<>()).add(fe);
    }
    for (Map.Entry<String, List<FieldEntry>> e : byName.entrySet()) {
      List<FieldEntry> group = e.getValue();
      if (group.size() < 2) {
        continue;
      }
      group.sort((a, b) -> Integer.compare(a.since, b.since));
      for (int i = 1; i < group.size(); i++) {
        FieldEntry prev = group.get(i - 1);
        FieldEntry curr = group.get(i);
        if (curr.since < prev.until) {
          throw new IllegalStateException(
              "Field name '"
                  + e.getKey()
                  + "' is declared with overlapping version windows ["
                  + prev.since
                  + ","
                  + prev.until
                  + ") and ["
                  + curr.since
                  + ","
                  + curr.until
                  + "); each version must have one definition per name. Adjust the @ForyVersion "
                  + "annotations on the live field or in the removed-fields class to make the "
                  + "windows disjoint.");
        }
      }
    }
  }

  /**
   * Strict schema hash, used only by versioning code paths. Distinguishes schemas that differ in
   * field name or nullability, unlike {@link DataTypes#computeSchemaHash}.
   */
  private static long computeStrictSchemaHash(Schema schema) {
    long hash = 1469598103934665603L; // FNV offset basis
    Set<String> seen = new HashSet<>();
    for (Field field : schema.fields()) {
      if (!seen.add(field.name())) {
        throw new IllegalStateException("Duplicate field name in schema: " + field.name());
      }
      hash = hashField(hash, field);
    }
    return hash;
  }

  private static long hashField(long hash, Field field) {
    hash = mix(hash, field.name());
    DataType type = field.type();
    // The type's name() carries its identity including any inline width (e.g.
    // fixedSizeBinary(N)), which is enough for every type except DecimalType, whose
    // precision and scale are stored separately. Mix those in explicitly so two decimals of
    // different shape don't collide.
    hash = mix(hash, type.name());
    if (type instanceof DataTypes.DecimalType) {
      hash = mix(hash, ((DataTypes.DecimalType) type).precision());
      hash = mix(hash, ((DataTypes.DecimalType) type).scale());
    }
    hash = mix(hash, field.nullable() ? 1 : 0);
    if (type instanceof DataTypes.ListType) {
      hash = hashField(hash, DataTypes.arrayElementField(field));
    } else if (type instanceof DataTypes.MapType) {
      hash = hashField(hash, DataTypes.keyFieldForMap(field));
      hash = hashField(hash, DataTypes.itemFieldForMap(field));
    } else if (type instanceof DataTypes.StructType) {
      for (Field child : type.fields()) {
        hash = hashField(hash, child);
      }
    }
    return hash;
  }

  private static long mix(long hash, long value) {
    hash ^= value;
    hash *= 1099511628211L; // FNV prime
    return hash;
  }

  private static long mix(long hash, String value) {
    for (int i = 0; i < value.length(); i++) {
      hash = mix(hash, value.charAt(i));
    }
    return mix(hash, 0);
  }

  private static final class FieldEntry {
    final String name;

    /**
     * Java member name used for canonical ordering. Matches {@link Descriptor#getName} so live
     * fields and removed fields (declared on the history class) sort into the same order as {@link
     * TypeInference#inferSchema} produces.
     */
    final String javaName;

    final TypeRef<?> typeRef;
    final int since;
    final int until;
    final boolean live;

    /**
     * The versioned bean reachable from this field (the field type, a list element, or a map
     * value), or null when none. Keys the outer cross-product so every field backed by the same
     * bean class shares one version dimension.
     */
    Class<?> nestedBeanClass;

    /** SchemaHistory of {@link #nestedBeanClass}, when this field references a versioned bean. */
    SchemaHistory innerHistory;

    FieldEntry(
        String name, String javaName, TypeRef<?> typeRef, int since, int until, boolean live) {
      this.name = name;
      this.javaName = javaName;
      this.typeRef = typeRef;
      this.since = since;
      this.until = until;
      this.live = live;
    }
  }
}

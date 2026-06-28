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
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import org.apache.fory.annotation.Internal;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.format.annotation.ForySchema;
import org.apache.fory.format.annotation.ForyVersion;
import org.apache.fory.format.encoder.CustomCodec;
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

  /** FNV-1a 64-bit offset basis, the seed for every strict-hash mix in this class. */
  private static final long FNV_OFFSET_BASIS = 1469598103934665603L;

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
   * VersionedSchema#version()} — collapsing field-set duplicates can place a later combination in
   * an earlier slot. Dispatch keys on the strict hash, so callers must not rely on positional
   * order.
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
    return build(beanClass, schemaTransform, new HashMap<>());
  }

  /**
   * Build a history, reusing {@code built} for any bean class already enumerated in this build, so
   * a bean reached through several field paths (a diamond in the type graph, or two fields of the
   * same type) is enumerated once rather than once per path. The memo is per top-level build, not
   * static: the transform (standard vs compact) and registry state must not leak across builds.
   */
  private static SchemaHistory build(
      Class<?> beanClass,
      UnaryOperator<Schema> schemaTransform,
      Map<Class<?>, SchemaHistory> built) {
    SchemaHistory memoized = built.get(beanClass);
    if (memoized != null) {
      return memoized;
    }
    ForySchema schemaAnn = beanClass.getAnnotation(ForySchema.class);
    Class<?> removedFieldsClass = schemaAnn == null ? void.class : schemaAnn.removedFields();

    List<FieldEntry> all = collectLiveFields(beanClass);
    if (removedFieldsClass != void.class) {
      all.addAll(collectRemovedFields(removedFieldsClass));
    }

    // Recursively expand any nested versioned bean field's own history. A versioned bean can be the
    // field type directly, the element of a list, or the key or value of a map; we locate it at any
    // of those sites so the outer's enumeration can cross-product over the inner's versions. A map
    // contributes a key site and a value site independently. The inner schema substitutes back into
    // the same site at materialization time.
    //
    // This recursion needs no cycle guard. TypeInference.inferField calls ctx.checkNoCycle on every
    // bean it descends into, and RowCodecBuilder runs inferSchema in its constructor before build()
    // reaches here, so a self-referential bean is already rejected. Recursion depth is bounded by
    // the acyclic nesting of distinct versioned bean types.
    for (FieldEntry fe : all) {
      collectNestedSites(beanClass, fe.typeRef, schemaTransform, built, fe.nestedSites);
    }

    validateNoNameCollision(all);
    SchemaHistory history = enumerate(beanClass.getName(), all, schemaTransform);
    built.put(beanClass, history);
    return history;
  }

  /**
   * History of a top-level array element or map entry field, which has no version annotations of
   * its own but may reach versioned beans through its list/map/array wrappers (a directly-typed
   * bean, a list element, or a map key or value, to any depth). The single element field is
   * enumerated over the cross-product of every reachable bean's versions, exactly as a bean field
   * is on the row path, so the element schema's strict hash identifies all nested layouts jointly
   * and an older payload restores every nested bean — on either map side — at its historical
   * layout.
   */
  public static SchemaHistory forElement(
      String fieldName, TypeRef<?> elementType, UnaryOperator<Schema> schemaTransform) {
    FieldEntry element =
        new FieldEntry(fieldName, fieldName, elementType, FIRST_VERSION, Integer.MAX_VALUE, true);
    // A top-level element has no enclosing bean; codec lookups behave as inferField's do at the
    // top level, where only globally-registered (enclosing-agnostic) codecs can match.
    collectNestedSites(
        void.class, elementType, schemaTransform, new HashMap<>(), element.nestedSites);
    List<FieldEntry> all = new ArrayList<>(1);
    all.add(element);
    return enumerate("element " + elementType, all, schemaTransform);
  }

  /**
   * Enumerate every distinct historical schema for {@code all} over its version boundaries and the
   * cross-product of nested bean versions, returning a {@link SchemaHistory}. {@code label} names
   * the owner for collision diagnostics.
   */
  private static SchemaHistory enumerate(
      String label, List<FieldEntry> all, UnaryOperator<Schema> schemaTransform) {
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
        for (NestedSite site : fe.nestedSites) {
          innerChoices.putIfAbsent(site.beanClass, site.history.versions());
        }
      }
      for (Map<Class<?>, VersionedSchema> combination : cartesian(innerChoices)) {
        List<Field> fields = new ArrayList<>(activeEntries.size());
        Set<String> liveNames = new HashSet<>();
        for (FieldEntry fe : activeEntries) {
          Field current = TypeInference.inferNamedField(fe.name, fe.typeRef);
          // Substitute each nested versioned site (a direct field, list element, map value, or map
          // key) with the version this combination chose for its bean class, keeping every
          // collection/map wrapper intact. A map can have both a key site and a value site, which
          // are substituted independently.
          Field field = current;
          for (NestedSite site : fe.nestedSites) {
            VersionedSchema innerVs = combination.get(site.beanClass);
            field =
                site.substitute(
                    field, fe.typeRef, new DataTypes.StructType(innerVs.schema().fields()));
          }
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
              "Strict hash collision for "
                  + label
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
      throw new IllegalStateException("No current schema resolved for " + label);
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
   * Whether a top-level array/map codec must take the evolution path for {@code elementType}, and a
   * representative reachable bean for naming the generated codec. Descends list/map/array wrappers
   * (a map on both its key and value sides) and returns the first bean at a leaf. The bean need not
   * be versioned: an unversioned bean must still take the evolution path so the strict-hash prefix
   * is always present and the producer and consumer stay wire-compatible. Returns null when no bean
   * is reachable and the codec needs no projection. The actual per-version enumeration over every
   * reachable bean is done by {@link #forElement}; this only decides whether to evolve and which
   * package/name to give the generated codec. The two walks descend the same wrappers but answer
   * different questions ("is any bean reachable, versioned or not" here, "which beans have more
   * than one version" in {@link #collectNestedSites}) at different phases, so they are deliberately
   * kept separate rather than merged into one traversal.
   */
  public static Class<?> evolutionBean(TypeRef<?> elementType, TypeResolutionContext typeCtx) {
    Wrapper w = Wrapper.classify(void.class, elementType);
    switch (w.kind) {
      case ENCODED:
      case OPTIONAL:
      case SEQUENCE:
        return evolutionBean(w.child, typeCtx);
      case MAP:
        // Return one representative bean (value side preferred) only to name the generated codec.
        // collectNestedSites enumerates both the key and value sites, so the codec's actual shape
        // covers both; this asymmetry is safe precisely because the result is naming-only. Do not
        // derive codec structure from it.
        Class<?> value = evolutionBean(w.value, typeCtx);
        return value != null ? value : evolutionBean(w.key, typeCtx);
      case LEAF:
        return w.raw != null && TypeUtils.isBean(TypeRef.of(w.raw), typeCtx) ? w.raw : null;
      default:
        throw new AssertionError("Unhandled wrapper kind: " + w.kind);
    }
  }

  /** A branch taken at a map node while descending toward a nested bean: its key or its value. */
  private enum MapBranch {
    KEY,
    VALUE
  }

  /**
   * The wrapper kinds the row format unwraps before reaching a leaf, in {@code inferField} order.
   */
  private enum WrapperKind {
    /** A custom codec whose {@code encodedType()} the field is re-inferred as. No path entry. */
    ENCODED,
    /** A {@link Optional} unwrapped to its element. No path entry. */
    OPTIONAL,
    /** An array or any {@code Iterable}, descended through its single element. No path entry. */
    SEQUENCE,
    /** A {@code Map}, descended independently through its key (KEY branch) and value (VALUE). */
    MAP,
    /** A non-wrapper type: a bean or a scalar. Terminates the descent. */
    LEAF
  }

  /**
   * One step of the wrapper-descent grammar shared by {@link #evolutionBean}, {@link
   * #collectNestedSites}, and {@link NestedSite#substitute}. It classifies a type into the next
   * wrapper kind and resolves the child type(s) to recurse into, so the three walks agree on which
   * types are transparent and how their children are derived. This grammar mirrors {@link
   * TypeInference#inferField}; a new wrapper type must be added here and in {@code inferField}
   * together, or nested versioned beans go undiscovered.
   */
  private static final class Wrapper {
    final WrapperKind kind;
    final Class<?> raw;
    final TypeRef<?> child; // ENCODED/OPTIONAL/SEQUENCE
    final TypeRef<?> key; // MAP
    final TypeRef<?> value; // MAP

    private Wrapper(
        WrapperKind kind, Class<?> raw, TypeRef<?> child, TypeRef<?> key, TypeRef<?> value) {
      this.kind = kind;
      this.raw = raw;
      this.child = child;
      this.key = key;
      this.value = value;
    }

    static Wrapper classify(Class<?> enclosing, TypeRef<?> typeRef) {
      // Resolve a bare type variable to its bound first, exactly as inferField does, so the
      // wrapper checks below see the same parameterized type inferField sees. Without this a field
      // typed as a type variable bounded to Optional/Iterable/Map would resolve its raw type to the
      // bound but carry no type arguments, and the wrapper branches would read element types off an
      // empty argument list.
      Type type = typeRef.getType();
      if (type instanceof TypeVariable) {
        return classify(enclosing, TypeRef.of(((TypeVariable<?>) type).getBounds()[0]));
      }
      Class<?> raw = TypeUtils.getRawType(typeRef);
      if (raw == null) {
        return new Wrapper(WrapperKind.LEAF, null, null, null, null);
      }
      TypeRef<?> encoded = encodedTypeOf(enclosing, raw, typeRef);
      if (encoded != null) {
        return new Wrapper(WrapperKind.ENCODED, raw, encoded, null, null);
      }
      if (raw == Optional.class) {
        return new Wrapper(
            WrapperKind.OPTIONAL, raw, TypeUtils.getTypeArguments(typeRef).get(0), null, null);
      }
      if (raw.isArray() || TypeUtils.ITERABLE_TYPE.isSupertypeOf(typeRef)) {
        return new Wrapper(WrapperKind.SEQUENCE, raw, elementTypeRef(typeRef, raw), null, null);
      }
      if (TypeUtils.MAP_TYPE.isSupertypeOf(typeRef)) {
        Tuple2<TypeRef<?>, TypeRef<?>> kv = TypeUtils.getMapKeyValueType(typeRef);
        return new Wrapper(WrapperKind.MAP, raw, null, kv.f0, kv.f1);
      }
      return new Wrapper(WrapperKind.LEAF, raw, null, null, null);
    }
  }

  /**
   * A versioned bean reachable from a field, together with the branch taken at each map on the way
   * down so the historical struct substitutes back into exactly that leaf. The path lists one entry
   * per map crossed, in order from the field root; list/array wrappers add no entry because they
   * have a single element leaf. A map field contributes a key site and a value site independently,
   * each with its own path; every other field shape has at most one site.
   */
  private static final class NestedSite {
    final Class<?> enclosing;
    final Class<?> beanClass;
    final SchemaHistory history;
    final List<MapBranch> path;

    NestedSite(
        Class<?> enclosing, Class<?> beanClass, SchemaHistory history, List<MapBranch> path) {
      this.enclosing = enclosing;
      this.beanClass = beanClass;
      this.history = history;
      this.path = path;
    }

    /**
     * Replace this site's bean struct in {@code current} with {@code historical}, descending
     * list/array wrappers and taking {@code path[depth]} at each map, while leaving every other
     * leaf intact so independent sites (a map's key and value, or beans under different map sides
     * at any depth) substitute without disturbing one another.
     */
    Field substitute(Field current, TypeRef<?> typeRef, DataTypes.StructType historical) {
      return substitute(current, typeRef, historical, 0);
    }

    private Field substitute(
        Field current, TypeRef<?> typeRef, DataTypes.StructType historical, int depth) {
      // A custom-codec'd field is already inferred as its encodedType() shape in `current`, and an
      // Optional unwraps straight through, so both descend without touching the field.
      Wrapper w = Wrapper.classify(enclosing, typeRef);
      switch (w.kind) {
        case ENCODED:
        case OPTIONAL:
          return substitute(current, w.child, historical, depth);
        case SEQUENCE:
          Field element =
              substitute(DataTypes.arrayElementField(current), w.child, historical, depth);
          return DataTypes.arrayField(current.name(), element);
        case MAP:
          Field keyField = DataTypes.keyFieldForMap(current);
          Field itemField = DataTypes.itemFieldForMap(current);
          if (path.get(depth) == MapBranch.KEY) {
            keyField = substitute(keyField, w.key, historical, depth + 1);
          } else {
            itemField = substitute(itemField, w.value, historical, depth + 1);
          }
          return DataTypes.mapField(current.name(), keyField, itemField);
        case LEAF:
          return DataTypes.field(current.name(), historical, current.nullable());
        default:
          throw new AssertionError("Unhandled wrapper kind: " + w.kind);
      }
    }
  }

  /**
   * Collect the versioned beans reachable from a field type into {@code out}: the field type
   * itself, a list/array element, a map value, and a map key, to arbitrary nesting depth. Each site
   * records the map-branch path needed to substitute its historical struct back. A map contributes
   * a key site and a value site independently, so an evolving key and an evolving value each become
   * their own cross-product dimension.
   */
  private static void collectNestedSites(
      Class<?> enclosing,
      TypeRef<?> typeRef,
      UnaryOperator<Schema> schemaTransform,
      Map<Class<?>, SchemaHistory> built,
      List<NestedSite> out) {
    collectNestedSites(enclosing, typeRef, new ArrayList<>(), schemaTransform, built, out);
  }

  private static void collectNestedSites(
      Class<?> enclosing,
      TypeRef<?> typeRef,
      List<MapBranch> path,
      UnaryOperator<Schema> schemaTransform,
      Map<Class<?>, SchemaHistory> built,
      List<NestedSite> out) {
    // A custom codec replaces the whole field with its encodedType(); an Optional unwraps; arrays
    // and any Iterable descend their element; all transparently, with no path entry. A map descends
    // its key and value independently, each adding a path entry so its historical struct
    // substitutes
    // back into exactly that branch.
    Wrapper w = Wrapper.classify(enclosing, typeRef);
    switch (w.kind) {
      case ENCODED:
      case OPTIONAL:
      case SEQUENCE:
        collectNestedSites(enclosing, w.child, path, schemaTransform, built, out);
        return;
      case MAP:
        collectNestedSites(
            enclosing, w.key, append(path, MapBranch.KEY), schemaTransform, built, out);
        collectNestedSites(
            enclosing, w.value, append(path, MapBranch.VALUE), schemaTransform, built, out);
        return;
      case LEAF:
        break;
      default:
        throw new AssertionError("Unhandled wrapper kind: " + w.kind);
    }
    if (w.raw != null && isBean(w.raw)) {
      // A bean is an evolution site when it has more than one version — whether the variation comes
      // from its own @ForyVersion fields or only from a nested versioned bean it reaches through
      // its
      // fields (e.g. a wrapper struct used as a map key, whose own fields are stable but whose
      // detail field evolves). build() already expands that nested cross-product, so its version
      // count is the exact evolution test; a single-version history means nothing here evolves and
      // the site needs no projection.
      SchemaHistory history = build(w.raw, schemaTransform, built);
      if (history.versions().size() > 1) {
        out.add(new NestedSite(enclosing, w.raw, history, path));
      }
    }
  }

  private static List<MapBranch> append(List<MapBranch> path, MapBranch branch) {
    List<MapBranch> next = new ArrayList<>(path.size() + 1);
    next.addAll(path);
    next.add(branch);
    return next;
  }

  /**
   * Element type of a list/array field, derived the same way {@link TypeInference} does: arrays use
   * the component type, iterables use the element type.
   */
  private static TypeRef<?> elementTypeRef(TypeRef<?> typeRef, Class<?> raw) {
    return raw.isArray() ? typeRef.getComponentType() : TypeUtils.getElementType(typeRef);
  }

  /**
   * The type a custom codec encodes {@code raw} into, when that codec replaces the field's encoding
   * with a recursively-inferred struct — the same recursion {@link TypeInference#inferField} takes
   * (find the codec for the enclosing/field pair, then descend its {@code encodedType()}). Returns
   * null when no codec applies, when the codec supplies its own terminal {@code foryField} (which
   * is never a versioned bean), or when the encoded type is the declared type itself. The evolution
   * walk follows this so a versioned bean reachable only through a codec is still enumerated.
   */
  private static TypeRef<?> encodedTypeOf(Class<?> enclosing, Class<?> raw, TypeRef<?> typeRef) {
    CustomCodec<?, ?> codec =
        CustomTypeEncoderRegistry.customTypeHandler().findCodec(enclosing, raw);
    if (codec == null || codec.getForyField("") != null) {
      return null;
    }
    TypeRef<?> encoded = codec.encodedType();
    return encoded == null || encoded.equals(typeRef) ? null : encoded;
  }

  /**
   * True if the row format treats {@code cls} as a bean, so it is safe to descend into for
   * evolution-site discovery. Whether the bean actually evolves is decided by its version count in
   * {@link #collectNestedSites}, not here.
   *
   * <p>Only beans are introspected. TypeInference.inferField routes collection/map/array/enum field
   * types away from Descriptor.getDescriptors, so a collection subclass that shadows a field name
   * across its hierarchy round-trips fine even though getDescriptors would reject it. Gating on
   * isBean keeps this probe consistent with inferField; getDescriptors then only throws for a class
   * that genuinely cannot be a bean, which fails identically on the real encode/decode path. Use
   * the same synthesize-interfaces context as inferField and the top-level array/map entry point
   * (evolutionBean), so an interface bean nested as a field type, list element, or map key/value is
   * discovered as a bean rather than rejected; otherwise its older versions are never enumerated
   * and an older payload decodes at the interface's current layout.
   */
  private static boolean isBean(Class<?> cls) {
    TypeResolutionContext typeCtx =
        new TypeResolutionContext(CustomTypeEncoderRegistry.customTypeHandler(), true);
    return TypeUtils.isBean(cls, typeCtx);
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
  static long computeStrictSchemaHash(Schema schema) {
    long hash = FNV_OFFSET_BASIS;
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
      // Mix the child count before recursing. Unlike list and map, whose arity is fixed by the
      // type kind, a struct has a variable number of children with no boundary marker between a
      // nested struct's last child and the parent's next field. Without the count, {a:struct<x>,b}
      // and {a:struct<x,b>} mix an identical byte sequence and collide. The count delimits the
      // struct's extent so the hash stays injective over nesting structure.
      List<Field> children = type.fields();
      hash = mix(hash, children.size());
      for (Field child : children) {
        hash = hashField(hash, child);
      }
    }
    return hash;
  }

  /**
   * Combine two strict hashes into one 64-bit value with the same FNV-1a mix used for schema
   * hashing, so a map header carrying a single hash can identify a (key, value) layout combination
   * jointly. Order-sensitive: {@code combineHashes(a, b) != combineHashes(b, a)}.
   */
  public static long combineHashes(long first, long second) {
    return mix(mix(FNV_OFFSET_BASIS, first), second);
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
     * The versioned beans reachable from this field (the field type, a list element, a map value,
     * or a map key), empty when none. Each site keys the outer cross-product by its bean class, so
     * every field backed by the same class shares one version dimension; a map field with an
     * evolving key and value contributes two sites.
     */
    final List<NestedSite> nestedSites = new ArrayList<>(2);

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

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

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.fory.format.annotation.ForyRowSchema;
import org.apache.fory.format.annotation.ForyRowVersion;
import org.apache.fory.format.row.binary.writer.CompactBinaryRowWriter;
import org.apache.fory.format.type.CustomTypeEncoderRegistry;
import org.apache.fory.format.type.DataType;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.Field;
import org.apache.fory.format.type.Schema;
import org.apache.fory.format.type.TypeInference;
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
 * <p>The hash mixes the row format, field names, and nullability in addition to types, so that two
 * schemas that differ only in field order or naming are distinguishable, and a reader of one format
 * rejects a payload of the other even when both formats write the same field order. This is
 * intentionally a different hash from {@link DataTypes#computeSchemaHash} and is used only by
 * versioning code paths.
 *
 * <p>Every hash input is language-neutral so another runtime can reproduce the same value from the
 * same declared schema: snake_case wire names, Fory type IDs, and the shape parameters a type ID
 * does not carry (decimal precision/scale, fixed-size binary width). The algorithm is specified in
 * {@code docs/specification/row_format_spec.md}. Versioned schemas additionally require the
 * canonical field order — ascending by wire name — which for Java coincides with the member-name
 * order the generated codecs bake in. Member names with an underscore are rejected at build time,
 * and so are beans whose member names sort differently by wire name (a character outside the Basic
 * Multilingual Plane against one above the surrogate range).
 */
final class SchemaHistory {

  /** Implicit version of a live field that carries no {@link ForyRowVersion}. */
  private static final int FIRST_VERSION = 1;

  /** FNV-1a 64-bit offset basis, the seed for every strict-hash mix in this class. */
  private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;

  /**
   * A row format as schema evolution sees it: the tag that every strict hash mixes first, and the
   * order in which the format writes the fields of each struct. The standard and compact formats
   * write one schema in the same field order when the compact sort moves no field (every field 8
   * bytes wide, for example), so only the tag keeps their hashes apart.
   */
  enum Format {
    STANDARD(0),
    COMPACT(1);

    private final long tag;

    Format(long tag) {
      this.tag = tag;
    }

    /**
     * Return {@code schema} with the fields of every struct, at any depth, in the order this format
     * writes them: unchanged for the standard format, and sorted at every depth, including structs
     * in list elements and map entries, for the compact format.
     */
    Schema wireOrder(Schema schema) {
      return this == COMPACT ? CompactBinaryRowWriter.sortSchemaDeep(schema) : schema;
    }
  }

  /** One entry in a {@link SchemaHistory}. */
  static final class VersionedSchema {
    private final int version;
    private final Schema schema;
    private final long strictHash;
    private final boolean current;
    private final Set<String> liveFieldNames;
    private final Map<Class<?>, VersionedSchema> nestedBeanSchemas;
    private final Set<Class<?>> liveNestedClasses;

    VersionedSchema(
        int version,
        Schema schema,
        long strictHash,
        boolean current,
        Set<String> liveFieldNames,
        Map<Class<?>, VersionedSchema> nestedBeanSchemas,
        Set<Class<?>> liveNestedClasses) {
      this.version = version;
      this.schema = schema;
      this.strictHash = strictHash;
      this.current = current;
      this.liveFieldNames = liveFieldNames;
      this.nestedBeanSchemas = nestedBeanSchemas;
      this.liveNestedClasses = liveNestedClasses;
    }

    int version() {
      return version;
    }

    Schema schema() {
      return schema;
    }

    long strictHash() {
      return strictHash;
    }

    /**
     * True when this entry is its bean's current (writer-side) schema. Routing uses this to decide
     * whether a nested-bean slot embeds the current-version codec class (no suffix) or a historical
     * projection class.
     */
    boolean isCurrent() {
      return current;
    }

    /**
     * Names of fields in this version that still have a Java member on the current bean class.
     * Other fields are read-and-discarded during projection.
     */
    Set<String> liveFieldNames() {
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
    Map<Class<?>, VersionedSchema> nestedBeanSchemas() {
      return nestedBeanSchemas;
    }

    /**
     * The keys of {@link #nestedBeanSchemas()} that a live field reaches. A class reached only
     * through removed fields shapes the hash, but projection skips removed slots and never decodes
     * it, so it needs no codec.
     */
    Set<Class<?>> liveNestedClasses() {
      return liveNestedClasses;
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
  VersionedSchema current() {
    return current;
  }

  /**
   * All distinct historical schemas, in build order: outer version ascending, and within an outer
   * version, one entry per nested cross-product combination. This is not a strict sort by {@link
   * VersionedSchema#version()} — collapsing field-set duplicates can place a later combination in
   * an earlier slot. Dispatch keys on the strict hash, so callers must not rely on positional
   * order.
   */
  List<VersionedSchema> versions() {
    return versions;
  }

  /**
   * Build a history from the bean's annotations. The schema for each version is put in the wire
   * order of {@code format} and hashed with its tag.
   */
  static SchemaHistory build(Class<?> beanClass, Format format) {
    return build(beanClass, format, new HashMap<>());
  }

  /**
   * Build a history, reusing {@code built} for any bean class already enumerated in this build, so
   * a bean reached through several field paths (a diamond in the type graph, or two fields of the
   * same type) is enumerated once rather than once per path. The memo is per top-level build, not
   * static: the format (standard vs compact) and registry state must not leak across builds.
   */
  private static SchemaHistory build(
      Class<?> beanClass, Format format, Map<Class<?>, SchemaHistory> built) {
    if (built.containsKey(beanClass)) {
      SchemaHistory memoized = built.get(beanClass);
      // A null memo entry marks a build in progress: this bean's history reaches back to itself,
      // which only removed-field declarations can do (live-field cycles are rejected by
      // inferSchema's cycle check before build() runs). The historical schema would be cyclic.
      if (memoized == null) {
        throw new IllegalStateException(
            "Circular schema history for "
                + beanClass.getName()
                + ": a removed field reaches back to a bean whose history is still being built. "
                + "Row format does not support circular references.");
      }
      return memoized;
    }
    built.put(beanClass, null);
    List<FieldEntry> all = collectLiveFields(beanClass);
    // A subclass's History interface can extend its parent's, so the same declaration can arrive
    // from both classes. Keep one copy of each, so an inherited removal is not an overlapping one.
    List<FieldEntry> removed = new ArrayList<>();
    for (Class<?> removedFieldsClass : removedFieldsClasses(beanClass)) {
      for (FieldEntry fe : collectRemovedFields(removedFieldsClass)) {
        if (!containsSameDeclaration(removed, fe)) {
          removed.add(fe);
        }
      }
    }
    all.addAll(removed);

    // Infer each field once and recursively expand the history of every versioned bean it reaches.
    // A versioned bean can be the field type directly, the element of a list, or the key or value
    // of a map; we locate it at any of those sites so the outer's enumeration can cross-product
    // over the inner's versions. A map contributes a key site and a value site independently. The
    // inner schema substitutes back into the same site at materialization time.
    //
    // Live-field cycles are rejected before build() runs: TypeInference.inferField calls
    // ctx.checkNoCycle on every bean it descends into, and RowCodecBuilder runs inferSchema in its
    // constructor. Removed fields never pass through inferSchema, so a cycle reachable only through
    // a removed-field declaration is caught by the in-progress memo entry above instead.
    for (FieldEntry fe : all) {
      inferEntry(beanClass, fe, format, built);
    }

    validateNoNameCollision(beanClass, all, format);
    SchemaHistory history = enumerate(beanClass.getName(), all, format);
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
  static SchemaHistory forElement(String fieldName, TypeRef<?> elementType, Format format) {
    FieldEntry element =
        new FieldEntry(fieldName, fieldName, elementType, FIRST_VERSION, Integer.MAX_VALUE, true);
    // A top-level element has no enclosing bean; codec lookups behave as inferField's do at the
    // top level, where only globally-registered (enclosing-agnostic) codecs can match.
    inferEntry(void.class, element, format, new HashMap<>());
    List<FieldEntry> all = new ArrayList<>(1);
    all.add(element);
    return enumerate("element " + elementType, all, format);
  }

  /**
   * Enumerate every distinct historical schema for {@code all} over its version boundaries and the
   * cross-product of nested bean versions, returning a {@link SchemaHistory}. {@code label} names
   * the owner for collision diagnostics. Each entry's field is already inferred.
   */
  private static SchemaHistory enumerate(String label, List<FieldEntry> all, Format format) {
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
    // The canonical cross-language order is ascending by wire name; checkCanonicalOrder below
    // verifies per version that the member-name order coincides with it, so the emitted schemas
    // comply with the spec while staying byte-identical to the generated codec's baked order.
    all.sort((a, b) -> a.javaName.compareTo(b.javaName));
    // A field with finite [since, until) can leave two boundaries with identical field sets
    // (e.g. v1 and v4 both lack a field that lived in [v2, v4)). Collapse boundaries that
    // produce the same layout into one VersionedSchema, since they round-trip identically.
    // The strict hash is the layout identity, so it is the dedup key. Schema.equals is weaker: it
    // ignores list element nullability and fixed-size binary width, so it would merge a
    // List<Integer> layout with an int[] layout and drop one of their hashes. The guard on
    // hashToSchema below rejects most real strict-hash collisions (two distinct schemas with one
    // hash), but not all of them.
    int latestVersion = schemaVersions.last();
    Map<Long, VersionedSchema> byHash = new LinkedHashMap<>();
    // A removed field that a live field adds again with the same wire name and declared type holds
    // the same data, so the projection reads it in every older layout. Reading it only where its
    // layout happens to collapse with a version that has the field live would make the result
    // depend on unrelated fields. A removed field with another declared type is discarded;
    // validateNoTypeChange already rejected the case where both types encode to the same row field.
    Map<String, TypeRef<?>> liveTypes = new HashMap<>();
    for (FieldEntry fe : all) {
      if (fe.live) {
        liveTypes.put(fe.name, fe.typeRef);
      }
    }
    Set<FieldEntry> readEntries = new HashSet<>();
    for (FieldEntry fe : all) {
      if (fe.live || fe.typeRef.equals(liveTypes.get(fe.name))) {
        readEntries.add(fe);
      }
    }
    // Versions that collapse into one entry read the same fields: a removed entry is read only with
    // a live twin of the same declared type, and validateNoTypeChange rejects every other type
    // change whose layout could coincide with a newer one.
    Map<Long, Schema> hashToSchema = new HashMap<>();
    Long currentHash = null;
    for (int v : schemaVersions) {
      List<FieldEntry> activeEntries = new ArrayList<>();
      for (FieldEntry fe : all) {
        if (fe.since <= v && v < fe.until) {
          activeEntries.add(fe);
        }
      }
      checkCanonicalOrder(label, v, activeEntries);
      // Cross-product over each nested versioned bean *class*, not each field. A writer always
      // writes one definition of a given bean class, so every field of that class in a single
      // payload is at the same version; the off-diagonal combinations (the same class at two
      // versions in one record) are unreachable on the wire. Enumerating one dimension per class
      // keeps the count a product over distinct nested classes rather than over fields, and lets
      // a class appear in more than one field. A class also reached through another nested bean's
      // own history is held to that same version (see cartesian). If no entries have nested
      // histories, this yields a single combination.
      //
      // The combination count is the product of the per-class version counts. There is no cap on
      // purpose: what a host can afford ranges from a microcontroller to a large server, so no one
      // limit fits. If the growth becomes a concern, drop entries from each bean's History
      // interface once you no longer need to read payloads from that range — that removes the
      // corresponding VersionedSchema from this enumeration. Retiring history entries is purely a
      // read-side concern; the writer always uses the current schema.
      LinkedHashMap<Class<?>, List<VersionedSchema>> innerChoices = new LinkedHashMap<>();
      Set<Class<?>> liveNestedClasses = new HashSet<>();
      for (FieldEntry fe : activeEntries) {
        for (NestedSite site : fe.nestedSites) {
          innerChoices.putIfAbsent(site.beanClass, site.history.versions());
          if (readEntries.contains(fe)) {
            liveNestedClasses.add(site.beanClass);
          }
        }
      }
      Set<Class<?>> liveNested = Collections.unmodifiableSet(liveNestedClasses);
      for (Map<Class<?>, VersionedSchema> combination : cartesian(innerChoices)) {
        List<Field> fields = new ArrayList<>(activeEntries.size());
        Set<String> liveNames = new HashSet<>();
        for (FieldEntry fe : activeEntries) {
          // Substitute each nested versioned site (a direct field, list element, map value, or map
          // key) with the version this combination chose for its bean class, keeping every
          // collection/map wrapper intact. A map can have both a key site and a value site, which
          // are substituted independently.
          Field field = fe.field;
          for (NestedSite site : fe.nestedSites) {
            VersionedSchema innerVs = combination.get(site.beanClass);
            field = site.substitute(field, new DataTypes.StructType(innerVs.schema().fields()));
          }
          fields.add(field);
          if (readEntries.contains(fe)) {
            liveNames.add(fe.name);
          }
        }
        Schema schema = format.wireOrder(new Schema(fields));
        long hash = computeStrictSchemaHash(format, schema);
        // Schema.equals ignores list element nullability and fixed-size binary width, so this guard
        // misses a collision between two schemas that differ only there. A collision has odds of
        // about 2^-64, so the gap is accepted.
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
                Collections.unmodifiableMap(new HashMap<>(combination)),
                liveNested);
        // Versions collapse when their layouts are identical: v1 and v4 of a field that lived in
        // [v2, v4), or a field removed and later added again. The latest version must win, so the
        // stored entry is the one flagged current. Enclosing histories test inner.isCurrent() to
        // find their own current combination, and fail to build if no inner entry is current.
        if (innerAllCurrent) {
          byHash.put(hash, vs);
        } else {
          byHash.putIfAbsent(hash, vs);
        }
        if (isCurrent) {
          currentHash = hash;
        }
      }
    }
    // The all-current combination at the latest version is always one of the cartesian entries,
    // so currentHash is always set and present here.
    VersionedSchema current = byHash.get(currentHash);
    if (current == null) {
      throw new IllegalStateException("No current schema resolved for " + label);
    }
    return new SchemaHistory(
        Collections.unmodifiableList(new ArrayList<>(byHash.values())), current);
  }

  /**
   * Cartesian product over (nested bean class, list-of-inner-VersionedSchema), keeping only the
   * combinations that put every class at one version. A class reached both directly and through
   * another chosen inner schema is at the same version in both places on the wire, because a writer
   * has one definition of each class. Each class has one history per build (the build memo), so its
   * entries compare by identity.
   */
  private static List<Map<Class<?>, VersionedSchema>> cartesian(
      LinkedHashMap<Class<?>, List<VersionedSchema>> choices) {
    List<Map<Class<?>, VersionedSchema>> out = new ArrayList<>();
    // Parallel to out: every class each partial combination fixes, at any depth.
    List<Map<Class<?>, VersionedSchema>> pinned = new ArrayList<>();
    out.add(new HashMap<>());
    pinned.add(new HashMap<>());
    for (Map.Entry<Class<?>, List<VersionedSchema>> choice : choices.entrySet()) {
      Class<?> cls = choice.getKey();
      List<VersionedSchema> options = choice.getValue();
      List<Map<Class<?>, VersionedSchema>> next = new ArrayList<>(out.size() * options.size());
      List<Map<Class<?>, VersionedSchema>> nextPinned = new ArrayList<>(next.size());
      for (int i = 0; i < out.size(); i++) {
        for (VersionedSchema opt : options) {
          Map<Class<?>, VersionedSchema> fixed = new HashMap<>(pinned.get(i));
          if (!pin(fixed, cls, opt)) {
            continue;
          }
          Map<Class<?>, VersionedSchema> extended = new HashMap<>(out.get(i));
          extended.put(cls, opt);
          next.add(extended);
          nextPinned.add(fixed);
        }
      }
      out = next;
      pinned = nextPinned;
    }
    return out;
  }

  /**
   * Whether {@code first} and {@code second}, which can come from separate builds, put every bean
   * class that both reach, at any depth, at the same version. Separate builds hold separate entries
   * for one version, so this compares versions by strict hash, which is unique within a history.
   */
  static boolean sameClassVersions(VersionedSchema first, VersionedSchema second) {
    Map<Class<?>, Long> versions = new HashMap<>();
    return collectVersions(first, versions) && collectVersions(second, versions);
  }

  private static boolean collectVersions(VersionedSchema vs, Map<Class<?>, Long> versions) {
    for (Map.Entry<Class<?>, VersionedSchema> nested : vs.nestedBeanSchemas().entrySet()) {
      Long prior = versions.putIfAbsent(nested.getKey(), nested.getValue().strictHash());
      if (prior != null && prior != nested.getValue().strictHash()) {
        return false;
      }
      if (!collectVersions(nested.getValue(), versions)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Fix {@code cls} at {@code vs} in {@code fixed}, together with every class that {@code vs} fixes
   * at any depth. Returns false when one of them is already fixed at another version.
   */
  private static boolean pin(
      Map<Class<?>, VersionedSchema> fixed, Class<?> cls, VersionedSchema vs) {
    VersionedSchema prior = fixed.putIfAbsent(cls, vs);
    if (prior != null) {
      return prior == vs;
    }
    for (Map.Entry<Class<?>, VersionedSchema> nested : vs.nestedBeanSchemas().entrySet()) {
      if (!pin(fixed, nested.getKey(), nested.getValue())) {
        return false;
      }
    }
    return true;
  }

  /**
   * A representative bean reachable from a top-level array element or map position, which names and
   * places the generated projection codecs: the first bean that the element type reaches when the
   * value side of every map comes first, or null when no bean is reachable; then the position has
   * one version and needs no projection. The per-version enumeration is done by {@link
   * #forElement}, which sites every reachable bean, so the choice here only names the codec. Do not
   * derive codec structure from it.
   */
  static Class<?> evolutionBean(TypeRef<?> elementType) {
    // The descent reaches a map's key before its value, so its last bean is the value-first one.
    List<Class<?>> beans = new ArrayList<>();
    TypeInference.inferNamedField(
        "",
        elementType,
        void.class,
        (leafType, path) -> {
          if (isBean(leafType)) {
            beans.add(leafType);
          }
        });
    return beans.isEmpty() ? null : beans.get(beans.size() - 1);
  }

  /**
   * A versioned bean reachable from a field, together with the branch taken at each map on the way
   * down so the historical struct substitutes back into exactly that leaf. The path lists one entry
   * per map crossed, in order from the field root; list/array wrappers add no entry because they
   * have a single element leaf. A map field contributes a key site and a value site independently,
   * each with its own path; every other field shape has at most one site.
   */
  private static final class NestedSite {
    final Class<?> beanClass;
    final SchemaHistory history;
    final List<TypeInference.MapBranch> path;

    NestedSite(Class<?> beanClass, SchemaHistory history, List<TypeInference.MapBranch> path) {
      this.beanClass = beanClass;
      this.history = history;
      this.path = path;
    }

    /**
     * Replace this site's bean struct in {@code current} with {@code historical}, descending list
     * elements and taking {@code path[depth]} at each map, while leaving every other leaf intact so
     * independent sites (a map's key and value, or beans under different map sides at any depth)
     * substitute without disturbing one another. {@code current} is the inferred field, whose shape
     * follows the descent that found the site: a custom codec or an Optional adds no level, an
     * array or iterable is a list, a map is a map, and the bean is the struct at the end.
     */
    Field substitute(Field current, DataTypes.StructType historical) {
      return substitute(current, historical, 0);
    }

    private Field substitute(Field current, DataTypes.StructType historical, int depth) {
      DataType type = current.type();
      if (type instanceof DataTypes.ListType) {
        Field element = substitute(DataTypes.arrayElementField(current), historical, depth);
        return DataTypes.arrayField(current.name(), element);
      }
      if (type instanceof DataTypes.MapType) {
        Field keyField = DataTypes.keyFieldForMap(current);
        Field itemField = DataTypes.itemFieldForMap(current);
        if (path.get(depth) == TypeInference.MapBranch.KEY) {
          keyField = substitute(keyField, historical, depth + 1);
        } else {
          itemField = substitute(itemField, historical, depth + 1);
        }
        // Rebuild through MapType, which keeps the key non-nullable: a substituted key that is a
        // list or map comes back nullable, and DataTypes.mapField rejects a nullable key.
        DataTypes.MapType mapType = (DataTypes.MapType) type;
        return new Field(
            current.name(),
            new DataTypes.MapType(keyField.type(), itemField.type(), mapType.keysSorted()),
            current.nullable());
      }
      return DataTypes.field(current.name(), historical, current.nullable());
    }
  }

  /**
   * Infer {@code fe}'s row field and collect the versioned beans that its type reaches: the field
   * type itself, a list/array element, a map value, and a map key, to arbitrary nesting depth. Each
   * site records the map-branch path needed to substitute its historical struct back. A map
   * contributes a key site and a value site independently, so an evolving key and an evolving value
   * each become their own cross-product dimension. {@code enclosing} is the class that declares the
   * field ({@code void.class} for a top-level element), so custom-codec lookups match the live
   * inference path.
   */
  private static void inferEntry(
      Class<?> enclosing, FieldEntry fe, Format format, Map<Class<?>, SchemaHistory> built) {
    fe.field =
        TypeInference.inferNamedField(
            fe.name,
            fe.typeRef,
            enclosing,
            (leafType, path) -> {
              if (!isBean(leafType)) {
                return;
              }
              // A bean is an evolution site when it has more than one version, whether the
              // variation comes from its own @ForyRowVersion fields or only from a nested versioned
              // bean it reaches through its fields (e.g. a wrapper struct used as a map key, whose
              // own fields are stable but whose detail field evolves). build() already expands that
              // nested cross-product, so its version count is the exact evolution test; a
              // single-version history means nothing here evolves and the site needs no projection.
              SchemaHistory history = build(leafType, format, built);
              if (history.versions().size() > 1) {
                fe.nestedSites.add(new NestedSite(leafType, history, path));
              }
            });
  }

  /**
   * True if the row format treats {@code cls} as a bean, so its history is built for evolution-site
   * discovery. Whether the bean actually evolves is decided by its version count in {@link
   * #inferEntry}, not here.
   *
   * <p>The inference descent reports every leaf that is not a scalar, so this skips a type that the
   * row format does not support; inference then fails on it as it does on the encode path. Use the
   * same synthesize-interfaces context as inferField, so an interface bean nested as a field type,
   * list element, or map key/value is discovered as a bean rather than rejected; otherwise its
   * older versions are never enumerated and an older payload decodes at the interface's current
   * layout. The context is a fresh one, not the descent's own: a bean on the descent's walked path
   * is not a bean there, and build() must see it to report a cycle through a removed field.
   */
  private static boolean isBean(Class<?> cls) {
    return TypeUtils.isBean(
        cls, new TypeResolutionContext(CustomTypeEncoderRegistry.customTypeHandler(), true));
  }

  /**
   * The {@link ForyRowSchema#removedFields} classes declared on {@code beanClass} and every type it
   * inherits live fields from: its superclasses, or for an interface bean its superinterfaces. Live
   * fields come from the whole hierarchy, so the removed fields must too, or a subclass codec would
   * omit the slots a parent removed and reject the parent's older payloads.
   */
  private static Set<Class<?>> removedFieldsClasses(Class<?> beanClass) {
    Set<Class<?>> out = new LinkedHashSet<>();
    Set<Class<?>> visited = new HashSet<>();
    ArrayDeque<Class<?>> pending = new ArrayDeque<>();
    pending.add(beanClass);
    while (!pending.isEmpty()) {
      Class<?> cls = pending.poll();
      if (!visited.add(cls)) {
        continue;
      }
      ForyRowSchema schemaAnn = cls.getAnnotation(ForyRowSchema.class);
      if (schemaAnn != null && schemaAnn.removedFields() != void.class) {
        Class<?> removedFields = schemaAnn.removedFields();
        // The removed fields are the descriptors of this type, and only an interface has its
        // methods as descriptors. A class would yield its fields, so its methods would be ignored.
        if (!removedFields.isInterface()) {
          throw new IllegalStateException(
              "@ForyRowSchema(removedFields = "
                  + removedFields.getName()
                  + ") on "
                  + cls.getName()
                  + " must name an interface: removed fields are declared as its accessor methods.");
        }
        out.add(removedFields);
      }
      if (cls.isInterface()) {
        pending.addAll(Arrays.asList(cls.getInterfaces()));
      } else if (cls.getSuperclass() != null && cls.getSuperclass() != Object.class) {
        pending.add(cls.getSuperclass());
      }
    }
    return out;
  }

  private static boolean containsSameDeclaration(List<FieldEntry> entries, FieldEntry fe) {
    for (FieldEntry other : entries) {
      if (other.name.equals(fe.name)
          && other.since == fe.since
          && other.until == fe.until
          && other.typeRef.equals(fe.typeRef)) {
        return true;
      }
    }
    return false;
  }

  private static List<FieldEntry> collectRemovedFields(Class<?> historyClass) {
    List<Descriptor> descriptors = Descriptor.getDescriptors(historyClass);
    checkVersionedMethodsRead(historyClass, descriptors);
    List<FieldEntry> out = new ArrayList<>(descriptors.size());
    for (Descriptor d : descriptors) {
      ForyRowVersion ann = lookupForyVersion(d);
      if (ann == null) {
        throw new IllegalStateException(
            "Removed-field declaration "
                + historyClass.getName()
                + "."
                + d.getName()
                + " requires a @ForyRowVersion(until = ...) annotation");
      }
      if (ann.until() == Integer.MAX_VALUE) {
        throw new IllegalStateException(
            "Removed-field declaration "
                + historyClass.getName()
                + "."
                + d.getName()
                + " must specify @ForyRowVersion.until (no upper bound makes no sense for a field "
                + "that has been removed)");
      }
      if (ann.since() < FIRST_VERSION) {
        throw new IllegalStateException(
            "Invalid @ForyRowVersion on "
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
            "Invalid @ForyRowVersion on "
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
      // For a class bean or a record the descriptor name is the field name ("tags"); for an
      // interface bean it is the accessor method name ("getTags"). The user writes the history
      // method to match.
      String wireName = wireName(historyClass, d);
      out.add(
          new FieldEntry(
              wireName, d.getName(), d.getTypeRef(), ann.since(), ann.until(), /*live*/ false));
    }
    return out;
  }

  private static List<FieldEntry> collectLiveFields(Class<?> beanClass) {
    List<Descriptor> descriptors = Descriptor.getDescriptors(beanClass);
    checkVersionedMethodsRead(beanClass, descriptors);
    List<FieldEntry> out = new ArrayList<>(descriptors.size());
    for (Descriptor d : descriptors) {
      ForyRowVersion ann = lookupForyVersion(d);
      int since = ann == null ? FIRST_VERSION : ann.since();
      int until = ann == null ? Integer.MAX_VALUE : ann.until();
      if (since < FIRST_VERSION) {
        throw new IllegalStateException(
            "Invalid @ForyRowVersion on "
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
      // until is exclusive and Integer.MAX_VALUE is its open bound, so a window that starts there
      // is empty. Every live field would leave the latest version, and the current schema would be
      // empty.
      if (since == Integer.MAX_VALUE) {
        throw new IllegalStateException(
            "Invalid @ForyRowVersion on "
                + beanClass.getName()
                + "."
                + d.getName()
                + ": since must be less than Integer.MAX_VALUE, the open upper bound of every "
                + "version window.");
      }
      // A live field still exists as a Java member, so it has no end-of-life version. A finite
      // until would silently drop it from the current schema (until extends the version set, so
      // latestVersion >= until excludes the field), and the writer would stop serializing a field
      // the bean still has. Removals are declared on the history class via
      // @ForyRowSchema.removedFields.
      if (until != Integer.MAX_VALUE) {
        throw new IllegalStateException(
            "Invalid @ForyRowVersion on "
                + beanClass.getName()
                + "."
                + d.getName()
                + ": a live field must not set until ("
                + until
                + "). Declare removed fields on the @ForyRowSchema.removedFields history class instead.");
      }
      // No since/until ordering check here: a live field always has until == MAX_VALUE (enforced
      // above), so the ordering check lives only on the removed-field path in collectRemovedFields.
      String wireName = wireName(beanClass, d);
      out.add(new FieldEntry(wireName, d.getName(), d.getTypeRef(), since, until, /*live*/ true));
    }
    return out;
  }

  /**
   * The wire name of member {@code d} of {@code type}. The generated codec finds the member of a
   * wire name by removing its underscores, so a member name with an underscore would bind to
   * another member ({@code a_} to {@code a}) or to none. Reject it.
   */
  private static String wireName(Class<?> type, Descriptor d) {
    String name = d.getName();
    if (name.indexOf('_') >= 0) {
      throw new IllegalStateException(
          "Member '"
              + name
              + "' of "
              + type.getName()
              + " has an underscore in its name, which schema evolution rejects. Rename it, for "
              + "example to lowerCamelCase.");
    }
    return StringUtils.lowerCamelToLowerUnderscore(name);
  }

  private static ForyRowVersion lookupForyVersion(Descriptor d) {
    ForyRowVersion ann = readAnnotation(d.getField());
    if (ann != null) {
      return ann;
    }
    return readAnnotation(d.getReadMethod());
  }

  private static ForyRowVersion readAnnotation(AnnotatedElement element) {
    return element == null ? null : element.getAnnotation(ForyRowVersion.class);
  }

  /**
   * Reject a {@link ForyRowVersion} on a method of {@code type} or the supertypes that it inherits
   * descriptors from (its superclasses, or for an interface its superinterfaces) that is not the
   * read method of one of its descriptors, such as a getter of a field-backed bean or an interface
   * method that takes parameters. {@link #lookupForyVersion} reads only the descriptor field and
   * read method, so such an annotation would have no effect. The interfaces of a class are not
   * checked: their annotations belong to the codecs of those interface beans.
   */
  private static void checkVersionedMethodsRead(Class<?> type, List<Descriptor> descriptors) {
    Set<Method> readMethods = new HashSet<>();
    for (Descriptor d : descriptors) {
      if (d.getReadMethod() != null) {
        readMethods.add(d.getReadMethod());
      }
    }
    Set<Class<?>> visited = new HashSet<>();
    ArrayDeque<Class<?>> pending = new ArrayDeque<>();
    pending.add(type);
    while (!pending.isEmpty()) {
      Class<?> cls = pending.poll();
      if (cls == Object.class || !visited.add(cls)) {
        continue;
      }
      for (Method method : cls.getDeclaredMethods()) {
        // A bridge method carries the annotations of the method that it forwards to.
        if (!method.isBridge()
            && method.isAnnotationPresent(ForyRowVersion.class)
            && !readMethods.contains(method)) {
          throw new IllegalStateException(
              "@ForyRowVersion on "
                  + cls.getName()
                  + "."
                  + method.getName()
                  + "() has no effect on "
                  + type.getName()
                  + ": schema evolution reads it only from fields, record components, and the "
                  + "accessor methods of an interface. On a class, annotate the field, not its "
                  + "getter.");
        }
      }
      if (cls.isInterface()) {
        pending.addAll(Arrays.asList(cls.getInterfaces()));
      } else if (cls.getSuperclass() != null) {
        pending.add(cls.getSuperclass());
      }
    }
  }

  private static void validateNoNameCollision(
      Class<?> beanClass, List<FieldEntry> entries, Format format) {
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
                  + "); each version must have one definition per name. Adjust the @ForyRowVersion "
                  + "annotations on the live field or in the removed-fields class to make the "
                  + "windows disjoint.");
        }
      }
      validateNoTypeChange(beanClass, e.getKey(), group, format);
    }
  }

  /**
   * Reject a removed entry whose declared type differs from the live entry's when an older layout
   * could be byte-identical to a newer one, so the reader would decode old values with the new type
   * (a String re-added as an enum fails in valueOf). That holds when both encode to the same row
   * field in the wire order of {@code format} (the same strict hash; the compact format sorts
   * struct children, so two codecs that write one struct in different orders encode the same
   * field), and also when either reaches a versioned bean, whose older versions can make two
   * different current shapes coincide. Otherwise the layouts stay distinct and the reader discards
   * the old value.
   */
  private static void validateNoTypeChange(
      Class<?> beanClass, String name, List<FieldEntry> group, Format format) {
    FieldEntry live = null;
    for (FieldEntry fe : group) {
      if (fe.live) {
        live = fe;
      }
    }
    if (live == null) {
      return;
    }
    long liveHash = computeStrictSchemaHash(format, format.wireOrder(DataTypes.schema(live.field)));
    for (FieldEntry fe : group) {
      if (fe.live || fe.typeRef.equals(live.typeRef)) {
        continue;
      }
      boolean reachesVersionedBean = !fe.nestedSites.isEmpty() || !live.nestedSites.isEmpty();
      if (reachesVersionedBean
          || computeStrictSchemaHash(format, format.wireOrder(DataTypes.schema(fe.field)))
              == liveHash) {
        throw new IllegalStateException(
            "Field '"
                + name
                + "' of "
                + beanClass.getName()
                + " is declared as "
                + fe.typeRef
                + " in the removed-fields class and as "
                + live.typeRef
                + " on the live member. An older layout can then be identical to a newer one, so "
                + "old values would decode as the new type. Give the live member a new name.");
      }
    }
  }

  /**
   * Strict schema hash of {@code schema}, whose fields are already in the wire order of {@code
   * format}; used only by versioning code paths. Distinguishes schemas that differ in row format,
   * field name, or nullability, unlike {@link DataTypes#computeSchemaHash}. Every input is
   * language-neutral so another runtime reproduces the value from the same declared schema: the
   * format tag, wire names as UTF-8 bytes, Fory type IDs, and the shape parameters a type ID does
   * not carry. List and map children have positions fixed by the parent type, so only struct-owned
   * fields mix a name. The algorithm is specified in {@code docs/specification/row_format_spec.md};
   * do not change it without updating the spec.
   */
  static long computeStrictSchemaHash(Format format, Schema schema) {
    long hash = mix(FNV_OFFSET_BASIS, format.tag);
    for (Field field : schema.fields()) {
      hash = hashNamedField(hash, field);
    }
    return hash;
  }

  private static long hashNamedField(long hash, Field field) {
    hash = mix(hash, field.name());
    return hashFieldShape(hash, field);
  }

  private static long hashFieldShape(long hash, Field field) {
    DataType type = field.type();
    // The Fory type ID identifies every type except the parameterized ones: TYPE_BINARY covers
    // both variable and fixed-size binary, so the byte width follows the ID (0 = variable), and a
    // decimal's precision and scale are not part of its ID either.
    hash = mix(hash, type.typeId());
    if (type instanceof DataTypes.BinaryType) {
      hash = mix(hash, 0);
    } else if (type instanceof DataTypes.FixedWidthBinaryType) {
      hash = mix(hash, type.byteWidth());
    } else if (type instanceof DataTypes.DecimalType) {
      hash = mix(hash, ((DataTypes.DecimalType) type).precision());
      hash = mix(hash, ((DataTypes.DecimalType) type).scale());
    }
    hash = mix(hash, field.nullable() ? 1 : 0);
    if (type instanceof DataTypes.ListType) {
      hash = hashFieldShape(hash, DataTypes.arrayElementField(field));
    } else if (type instanceof DataTypes.MapType) {
      hash = hashFieldShape(hash, DataTypes.keyFieldForMap(field));
      hash = hashFieldShape(hash, DataTypes.itemFieldForMap(field));
    } else if (type instanceof DataTypes.StructType) {
      // Mix the child count before recursing. Unlike list and map, whose arity is fixed by the
      // type kind, a struct has a variable number of children with no boundary marker between a
      // nested struct's last child and the parent's next field. Without the count, {a:struct<x>,b}
      // and {a:struct<x,b>} mix an identical byte sequence and collide. The count delimits the
      // struct's extent so the hash stays injective over nesting structure.
      List<Field> children = type.fields();
      hash = mix(hash, children.size());
      for (Field child : children) {
        hash = hashNamedField(hash, child);
      }
    }
    return hash;
  }

  /**
   * Verify that {@code active} — one version's field entries, already sorted by Java member name —
   * is also strictly ascending by wire name, the canonical cross-language order versioned schemas
   * are defined in. Member names have no underscore ({@link #wireName}), so the two orders diverge
   * only where {@link String#compareTo}, which compares UTF-16 code units, disagrees with code
   * point order: a character outside the Basic Multilingual Plane against one above the surrogate
   * range. Java cannot emit the canonical order then, because the generated codec bakes member-name
   * order, so reject the bean with rename guidance. Duplicate wire names within one version are
   * unreachable here ({@link #validateNoNameCollision} forces their windows apart), but the strict
   * comparison covers them all the same.
   */
  private static void checkCanonicalOrder(String label, int version, List<FieldEntry> active) {
    for (int i = 1; i < active.size(); i++) {
      FieldEntry prev = active.get(i - 1);
      FieldEntry curr = active.get(i);
      if (compareWireNames(prev.name, curr.name) >= 0) {
        throw new IllegalStateException(
            "Version "
                + version
                + " of "
                + label
                + " orders member '"
                + prev.javaName
                + "' (wire name '"
                + prev.name
                + "') before '"
                + curr.javaName
                + "' (wire name '"
                + curr.name
                + "'), but schema evolution orders fields by wire name, which requires '"
                + curr.name
                + "' to sort strictly after '"
                + prev.name
                + "'. Rename one of the members so the member-name order and the wire-name order "
                + "agree. The orders differ for a character outside the Basic Multilingual Plane.");
      }
    }
  }

  /**
   * Compare wire names by Unicode code point, equivalent to byte-wise comparison of their UTF-8
   * encodings — the order the spec defines. {@link String#compareTo} would misorder supplementary
   * characters against BMP characters above the surrogate range.
   */
  private static int compareWireNames(String a, String b) {
    int i = 0;
    int limit = Math.min(a.length(), b.length());
    while (i < limit) {
      int cpA = a.codePointAt(i);
      int cpB = b.codePointAt(i);
      if (cpA != cpB) {
        return Integer.compare(cpA, cpB);
      }
      // Equal code points consume equal char counts, so one index serves both strings.
      i += Character.charCount(cpA);
    }
    return Integer.compare(a.length(), b.length());
  }

  /**
   * Combine two strict hashes into one 64-bit value with the same FNV-1a mix used for schema
   * hashing, so a map header carrying a single hash can identify a (key, value) layout combination
   * jointly. Order-sensitive: {@code combineHashes(a, b) != combineHashes(b, a)}.
   */
  static long combineHashes(long first, long second) {
    return mix(mix(FNV_OFFSET_BASIS, first), second);
  }

  private static long mix(long hash, long value) {
    hash ^= value;
    hash *= 1099511628211L; // FNV prime
    return hash;
  }

  private static long mix(long hash, String value) {
    // A wire name mixes as its UTF-8 bytes followed by a zero terminator, so a runtime that
    // stores names as raw bytes reproduces the hash without a UTF-16 round trip.
    byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
    for (byte b : utf8) {
      hash = mix(hash, b & 0xFFL);
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

    /** The row field inferred from {@link #typeRef}, set by {@link #inferEntry}. */
    Field field;

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

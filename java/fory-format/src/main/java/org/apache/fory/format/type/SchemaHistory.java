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
import org.apache.fory.format.annotation.ForySchema;
import org.apache.fory.format.annotation.ForyVersion;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.Descriptor;
import org.apache.fory.util.StringUtils;

/**
 * Resolves the version history of a row-codec bean. Each entry exposes the schema as it appeared
 * at a particular version, along with a strict hash that uniquely identifies the historical
 * layout. Only used when {@code withSchemaEvolution()} is configured on the codec builder.
 *
 * <p>The hash mixes field names and nullability in addition to types, so that two schemas that
 * differ only in field order or naming are distinguishable. This is intentionally a different
 * hash from {@link DataTypes#computeSchemaHash} and is used only by versioning code paths.
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
    private final Set<String> liveFieldNames;

    VersionedSchema(int version, Schema schema, long strictHash, Set<String> liveFieldNames) {
      this.version = version;
      this.schema = schema;
      this.strictHash = strictHash;
      this.liveFieldNames = liveFieldNames;
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
     * Names of fields in this version that still have a Java member on the current bean class.
     * Other fields are read-and-discarded during projection.
     */
    public Set<String> liveFieldNames() {
      return liveFieldNames;
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

  /** All known versions, ordered by version number ascending. */
  public List<VersionedSchema> versions() {
    return versions;
  }

  /**
   * Build a history from the bean's annotations. The schema for each version is transformed by
   * {@code schemaTransform} after filtering; pass an identity for standard format, or
   * {@code CompactBinaryRowWriter::sortSchema} for compact format.
   */
  public static SchemaHistory build(Class<?> beanClass, UnaryOperator<Schema> schemaTransform) {
    ForySchema schemaAnn = beanClass.getAnnotation(ForySchema.class);
    Class<?> removedFieldsClass = schemaAnn == null ? void.class : schemaAnn.removedFields();

    List<FieldEntry> all = collectLiveFields(beanClass);
    if (removedFieldsClass != void.class) {
      all.addAll(collectRemovedFields(removedFieldsClass));
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
    // produce the same field set into one VersionedSchema, since they round-trip identically.
    // A real strict-hash collision — two distinct field sets producing the same hash — is
    // caught by comparing canonical signatures on insertion.
    int latestVersion = schemaVersions.last();
    Map<String, VersionedSchema> bySignature = new LinkedHashMap<>();
    Map<Long, String> hashToSignature = new HashMap<>();
    for (int v : schemaVersions) {
      List<Field> fields = new ArrayList<>();
      Set<String> liveNames = new HashSet<>();
      for (FieldEntry fe : all) {
        if (fe.since <= v && v < fe.until) {
          fields.add(TypeInference.inferNamedField(fe.name, fe.typeRef));
          if (fe.live) {
            liveNames.add(fe.name);
          }
        }
      }
      Schema schema = schemaTransform.apply(new Schema(fields));
      long hash = computeStrictSchemaHash(schema);
      String signature = schemaSignature(schema);
      String previousSig = hashToSignature.putIfAbsent(hash, signature);
      if (previousSig != null && !previousSig.equals(signature)) {
        throw new IllegalStateException(
            "Strict hash collision for bean "
                + beanClass.getName()
                + " at version "
                + v
                + ": two distinct historical schemas hashed to the same value. Please file an "
                + "issue with the bean definition.");
      }
      // Record the highest version at which this signature first appears. The latest boundary
      // is the writer's "current" version; preferring it over earlier first-appearances keeps
      // current().version() aligned with what writers emit.
      bySignature.put(
          signature,
          new VersionedSchema(v, schema, hash, Collections.unmodifiableSet(liveNames)));
    }
    // current is the schema in effect at latestVersion.
    VersionedSchema current = null;
    for (VersionedSchema vs : bySignature.values()) {
      if (vs.version() == latestVersion) {
        current = vs;
        break;
      }
    }
    return new SchemaHistory(
        Collections.unmodifiableList(new ArrayList<>(bySignature.values())), current);
  }

  /**
   * Canonical textual signature of a schema, used to distinguish a real strict-hash collision
   * (two genuinely different schemas with the same hash) from the benign case where two version
   * boundaries produce the same field set.
   */
  private static String schemaSignature(Schema schema) {
    StringBuilder sb = new StringBuilder(64);
    for (Field field : schema.fields()) {
      sb.append(field.name())
          .append(':')
          .append(field.type())
          .append(field.nullable() ? "?" : "!")
          .append(';');
    }
    return sb.toString();
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
      out.add(new FieldEntry(wireName, d.getName(), d.getTypeRef(), ann.since(), ann.until(), /*live*/ false));
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
      if (since >= until) {
        throw new IllegalStateException(
            "Invalid @ForyVersion on " + beanClass.getName() + "." + d.getName()
                + ": since (" + since + ") must be strictly less than until (" + until + ")");
      }
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
        throw new IllegalStateException(
            "Duplicate field name in schema: " + field.name());
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
     * fields and removed fields (declared on the history class) sort into the same order as
     * {@link TypeInference#inferSchema} produces.
     */
    final String javaName;
    final TypeRef<?> typeRef;
    final int since;
    final int until;
    final boolean live;

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

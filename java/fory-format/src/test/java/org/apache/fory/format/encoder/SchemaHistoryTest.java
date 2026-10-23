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

import static org.apache.fory.format.encoder.SchemaHistory.Format.COMPACT;
import static org.apache.fory.format.encoder.SchemaHistory.Format.STANDARD;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.lang.reflect.TypeVariable;
import java.util.Optional;
import org.apache.fory.format.annotation.ForyRowSchema;
import org.apache.fory.format.annotation.ForyRowVersion;
import org.apache.fory.format.row.binary.writer.CompactBinaryRowWriter;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.Field;
import org.apache.fory.format.type.Schema;
import org.apache.fory.format.type.TypeInference;
import org.apache.fory.reflect.TypeRef;
import org.testng.annotations.Test;

public class SchemaHistoryTest {

  /**
   * The strict hash must be injective over nesting structure. A struct has a variable number of
   * children with no boundary marker between a nested struct's last child and the parent's next
   * field, so without mixing the child count {@code {a: struct<x>, b}} and {@code {a: struct<x,
   * b>}} mix an identical byte sequence and collide. These two schemas round-trip differently, so a
   * shared hash would route an old payload to the wrong projection codec.
   */
  @Test
  public void structBoundaryDoesNotCollide() {
    Schema fieldOutsideStruct =
        DataTypes.schema(DataTypes.field("a", DataTypes.struct(field("x")), false), field("b"));
    Schema fieldInsideStruct =
        DataTypes.schema(DataTypes.field("a", DataTypes.struct(field("x"), field("b")), false));

    assertNotEquals(
        SchemaHistory.computeStrictSchemaHash(STANDARD, fieldOutsideStruct),
        SchemaHistory.computeStrictSchemaHash(STANDARD, fieldInsideStruct));
  }

  /**
   * The child-count delimiter must distinguish an empty nested struct followed by a sibling from a
   * nested struct that contains that sibling, the minimal form of the boundary ambiguity.
   */
  @Test
  public void emptyStructBoundaryDoesNotCollide() {
    Schema emptyThenSibling =
        DataTypes.schema(DataTypes.field("a", DataTypes.struct(), false), field("b"));
    Schema siblingInsideStruct =
        DataTypes.schema(DataTypes.field("a", DataTypes.struct(field("b")), false));

    assertNotEquals(
        SchemaHistory.computeStrictSchemaHash(STANDARD, emptyThenSibling),
        SchemaHistory.computeStrictSchemaHash(STANDARD, siblingInsideStruct));
  }

  /**
   * Structurally identical schemas must still hash equal; the delimiter must not over-discriminate.
   */
  @Test
  public void identicalNestedStructsHashEqual() {
    assertEquals(
        SchemaHistory.computeStrictSchemaHash(
            STANDARD,
            DataTypes.schema(
                DataTypes.field("a", DataTypes.struct(field("x"), field("b")), false))),
        SchemaHistory.computeStrictSchemaHash(
            STANDARD,
            DataTypes.schema(
                DataTypes.field("a", DataTypes.struct(field("x"), field("b")), false))));
  }

  /**
   * {@link DataTypes.DecimalType#name} returns a bare "decimal" with no precision or scale, so the
   * strict hash must mix those in explicitly. Two decimals that differ only in precision or scale
   * must hash apart, or an old payload would route to a projection codec with the wrong numeric
   * layout. This guards the dedicated decimal branch in {@code hashField}.
   */
  @Test
  public void decimalPrecisionAndScaleDoNotCollide() {
    long p10s2 =
        SchemaHistory.computeStrictSchemaHash(
            STANDARD, DataTypes.schema(DataTypes.field("a", DataTypes.decimal(10, 2), false)));
    long p20s2 =
        SchemaHistory.computeStrictSchemaHash(
            STANDARD, DataTypes.schema(DataTypes.field("a", DataTypes.decimal(20, 2), false)));
    long p10s4 =
        SchemaHistory.computeStrictSchemaHash(
            STANDARD, DataTypes.schema(DataTypes.field("a", DataTypes.decimal(10, 4), false)));
    assertNotEquals(p10s2, p20s2);
    assertNotEquals(p10s2, p10s4);
    assertNotEquals(p20s2, p10s4);
  }

  /**
   * Variable-width binary and fixed-size binary share {@code TYPE_BINARY}, and two fixed-size
   * binaries of different widths share the ID too, so the strict hash mixes the byte width after
   * the type ID (0 for variable). Without it an old payload with a resized fixed-binary field would
   * route to a projection codec with the wrong slot width.
   */
  @Test
  public void binaryWidthDoesNotCollide() {
    long variable =
        SchemaHistory.computeStrictSchemaHash(
            STANDARD, DataTypes.schema(DataTypes.field("a", DataTypes.binary(), false)));
    long fixed4 =
        SchemaHistory.computeStrictSchemaHash(
            STANDARD, DataTypes.schema(DataTypes.field("a", DataTypes.fixedWidthBinary(4), false)));
    long fixed8 =
        SchemaHistory.computeStrictSchemaHash(
            STANDARD, DataTypes.schema(DataTypes.field("a", DataTypes.fixedWidthBinary(8), false)));
    assertNotEquals(variable, fixed4);
    assertNotEquals(variable, fixed8);
    assertNotEquals(fixed4, fixed8);
    assertEquals(
        fixed4,
        SchemaHistory.computeStrictSchemaHash(
            STANDARD,
            DataTypes.schema(DataTypes.field("a", DataTypes.fixedWidthBinary(4), false))));
  }

  /**
   * The canonical cross-language field order is ascending by snake_case wire name, but Java's
   * generated codecs bake the member-name order. A literal underscore sorting against an uppercase
   * letter makes the two orders diverge ({@code aB} before {@code a_a} by member name, {@code a_a}
   * before {@code a_b} by wire name), so the bean cannot be emitted in canonical order and the
   * build must reject it with rename guidance.
   */
  @Test
  public void divergentWireNameOrderIsRejected() {
    IllegalStateException e =
        expectThrows(
            IllegalStateException.class,
            () -> SchemaHistory.build(DivergentNameOrder.class, STANDARD));
    assertTrue(e.getMessage().contains("wire name"), e.getMessage());
  }

  /**
   * The same divergence inside a nested bean must also be rejected. Nested-site discovery builds
   * every reachable bean's history (even a single-version one, to count its versions), so the
   * nested bean's own enumeration runs the order check.
   */
  @Test
  public void divergentWireNameOrderInNestedBeanIsRejected() {
    IllegalStateException e =
        expectThrows(
            IllegalStateException.class,
            () -> SchemaHistory.build(DivergentNestedHolder.class, STANDARD));
    assertTrue(e.getMessage().contains("wire name"), e.getMessage());
  }

  /**
   * Two live members with one wire name ({@code aB} and {@code a_b}) need a rename, not other
   * versions, so the build must reject them by member name.
   */
  @Test
  public void liveMembersWithOneWireNameAreRejected() {
    IllegalStateException e =
        expectThrows(
            IllegalStateException.class, () -> SchemaHistory.build(SameWireName.class, STANDARD));
    assertTrue(e.getMessage().contains("'aB' and 'a_b'"), e.getMessage());
  }

  @lombok.Data
  public static class SameWireName {
    private int aB;
    private int a_b;
  }

  @lombok.Data
  public static class DivergentNameOrder {
    private int aB;
    private int a_a;
  }

  @lombok.Data
  public static class DivergentNestedHolder {
    private DivergentNameOrder inner;

    @ForyRowVersion(since = 2)
    private int count;
  }

  /** Identical decimal shapes must still hash equal; the precision/scale mix must not be noisy. */
  @Test
  public void identicalDecimalsHashEqual() {
    assertEquals(
        SchemaHistory.computeStrictSchemaHash(
            STANDARD, DataTypes.schema(DataTypes.field("a", DataTypes.decimal(10, 2), false))),
        SchemaHistory.computeStrictSchemaHash(
            STANDARD, DataTypes.schema(DataTypes.field("a", DataTypes.decimal(10, 2), false))));
  }

  /**
   * A map header carries one combined hash for the (key, value) layout pair. When key and value are
   * the same versioned bean at different versions, the two cross combinations have swapped per-side
   * hashes, so {@link SchemaHistory#combineHashes} must be order-sensitive or the combinations
   * collide and one payload decodes with the other's codec. This pins the invariant the map
   * key/value cross-product dispatch relies on.
   */
  @Test
  public void combineHashesIsOrderSensitive() {
    long a = 0x0123456789abcdefL;
    long b = 0x7766554433221100L;
    assertNotEquals(SchemaHistory.combineHashes(a, b), SchemaHistory.combineHashes(b, a));
  }

  /**
   * A field typed as a bare type variable bounded to a wrapper (the Scala 3 LTS case that {@code
   * TypeInference.inferField} resolves to its bound, see issue 2439) must descend through the bound
   * during evolution-site discovery. {@code getRawType} resolves the variable to {@code Optional},
   * but the bare variable carries no type arguments, so a descent that does not resolve the bound
   * first fails reading the missing element. The bean inside the bound must be discovered instead.
   */
  @Test
  public void typeVariableBoundedToWrapperResolvesToInnerBean() {
    TypeVariable<?> boundedToOptionalBean = OptionalBeanHolder.class.getTypeParameters()[0];
    assertEquals(SchemaHistory.evolutionBean(TypeRef.of(boundedToOptionalBean)), Inner.class);
  }

  /** {@code <T extends Optional<Inner>>}: a wrapper-bounded type variable for the test above. */
  private static final class OptionalBeanHolder<T extends Optional<Inner>> {}

  @lombok.Data
  public static class Inner {
    private String name;
  }

  /**
   * The writer-side hash is {@code current().strictHash()}, but the non-evolution row codec and the
   * decode hot path infer the live layout straight from {@link TypeInference#inferSchema}. The two
   * must produce byte-identical schemas, or a current-version payload misses the fast path. The
   * history derives its per-version schema by sorting fields on the Java member name to match
   * {@code inferSchema}'s {@code Descriptor.getDescriptors} order; this pins that equivalence so a
   * drift in the sort key fails loudly. Fields are declared out of alphabetical order on purpose so
   * the assertion has teeth.
   */
  @Test
  public void currentSchemaMatchesInferSchema() {
    for (Class<?> bean :
        new Class<?>[] {
          OutOfOrderFields.class, NestedHolder.class, CollectionHolder.class, EvolvingBean.class
        }) {
      assertEquals(
          SchemaHistory.build(bean, STANDARD).current().schema(),
          TypeInference.inferSchema(bean),
          "current() schema diverged from inferSchema for " + bean.getSimpleName());
    }
  }

  /**
   * The compact codec bakes its field order from {@code sortSchema(inferSchema(bean))}, while the
   * compact evolution writer hashes the deep-sorted history schema. Their top-level orders must
   * agree, or a current-version compact payload hashes one order and writes another.
   */
  @Test
  public void compactCurrentSchemaMatchesCodecOrder() {
    for (Class<?> bean :
        new Class<?>[] {
          OutOfOrderFields.class, NestedHolder.class, CollectionHolder.class, EvolvingBean.class
        }) {
      Schema inferred = TypeInference.inferSchema(bean);
      Schema history = SchemaHistory.build(bean, COMPACT).current().schema();
      assertEquals(history, CompactBinaryRowWriter.sortSchemaDeep(inferred), bean.getSimpleName());
      Schema codecOrder = CompactBinaryRowWriter.sortSchema(inferred);
      for (int i = 0; i < history.numFields(); i++) {
        assertEquals(
            history.field(i).name(), codecOrder.field(i).name(), bean.getSimpleName() + " " + i);
      }
    }
  }

  @lombok.Data
  public static class OutOfOrderFields {
    private int zebra;
    private String alpha;
    private long mid;
  }

  @lombok.Data
  public static class NestedHolder {
    private Inner inner;
    private int count;
  }

  @lombok.Data
  public static class CollectionHolder {
    private java.util.List<Inner> items;
    private java.util.Map<String, Inner> byName;
  }

  @lombok.Data
  public static class EvolvingBean {
    private int base;

    @ForyRowVersion(since = 2)
    private String added;
  }

  /**
   * The compact format sorts every struct it writes, including list elements and map values. The
   * strict hash must follow that wire order whether or not the nested bean is versioned: a
   * versioned bean substitutes its own sorted history schema, so an unversioned bean with the same
   * layout must hash the same.
   */
  @Test
  public void compactHashFollowsWireOrderInsideCollections() {
    SchemaHistory plain = SchemaHistory.build(PlainCollectionHolder.class, COMPACT);
    SchemaHistory versioned = SchemaHistory.build(VersionedCollectionHolder.class, COMPACT);
    assertEquals(plain.current().strictHash(), versioned.current().strictHash());

    DataTypes.ListType items =
        (DataTypes.ListType) plain.current().schema().getFieldByName("items").type();
    DataTypes.StructType element = (DataTypes.StructType) items.valueType();
    assertEquals(element.fields().get(0).name(), "wide");
    assertEquals(element.fields().get(1).name(), "narrow");
  }

  @lombok.Data
  public static class PlainElement {
    private byte narrow;
    private long wide;
  }

  @lombok.Data
  @ForyRowSchema(removedFields = VersionedElement.History.class)
  public static class VersionedElement {
    private byte narrow;
    private long wide;

    interface History {
      @ForyRowVersion(until = 2)
      String retired();
    }
  }

  @lombok.Data
  public static class PlainCollectionHolder {
    private java.util.List<PlainElement> items;
    private java.util.Map<String, PlainElement> byName;
  }

  @lombok.Data
  public static class VersionedCollectionHolder {
    private java.util.List<VersionedElement> items;
    private java.util.Map<String, VersionedElement> byName;
  }

  /**
   * Known-answer vectors for the strict hash, computed from the algorithm in {@code
   * docs/specification/row_format_spec.md} independently of this implementation. The spec lists the
   * same values. A change to any of them breaks every payload already written with schema
   * evolution, so update them only together with a wire-format change.
   */
  @Test
  public void strictHashKnownAnswers() {
    assertEquals(hash(DataTypes.notNullField("id", DataTypes.int32())), 0x506a3242a9f93ae2L);
    assertEquals(
        SchemaHistory.computeStrictSchemaHash(
            COMPACT, DataTypes.schema(DataTypes.notNullField("id", DataTypes.int32()))),
        0xb7db30d6e6593b2fL);
    assertEquals(hash(DataTypes.field("name", DataTypes.utf8(), true)), 0x4bd0d984e7109ef6L);
    assertEquals(
        hash(DataTypes.notNullField("gr\u00f6\u00dfe", DataTypes.int64())), 0xe5fcaf6a66d32a0eL);
    assertEquals(
        hash(DataTypes.notNullField("amount", DataTypes.decimal(10, 2))), 0x2ef05b2b90e987a9L);
    assertEquals(
        hash(DataTypes.notNullField("amount", DataTypes.decimal(38, -3))), 0x7e49dad4d1337a8aL);
    assertEquals(
        hash(
            DataTypes.field("blob", DataTypes.binary(), true),
            DataTypes.notNullField("digest", DataTypes.fixedWidthBinary(4))),
        0xca28ad1d8bf595e7L);
    assertEquals(
        hash(
            DataTypes.arrayField(
                "tags", DataTypes.field(DataTypes.ARRAY_ITEM_NAME, DataTypes.utf8(), true))),
        0x55f88fd672e92ea9L);
    assertEquals(
        hash(DataTypes.mapField("counts", DataTypes.utf8(), DataTypes.int32())),
        0x570b6dadf84868c8L);
    assertEquals(
        hash(
            DataTypes.field(
                "point",
                DataTypes.struct(
                    DataTypes.notNullField("x", DataTypes.int32()),
                    DataTypes.notNullField("y", DataTypes.int32())),
                true),
            DataTypes.notNullField("z", DataTypes.int64())),
        0x793051ee66a15234L);
    assertEquals(
        SchemaHistory.combineHashes(0x506a3242a9f93ae2L, 0x4bd0d984e7109ef6L), 0x11ce9d3600e26f89L);
  }

  /**
   * A writer has one definition of each bean class, so a class reached at two depths (directly and
   * through another versioned bean) is at the same version in both places. The history must not
   * enumerate combinations that put it at two versions.
   */
  @Test
  public void sharedNestedClassHasOneVersionPerCombination() {
    SchemaHistory history = SchemaHistory.build(SharedOuter.class, STANDARD);
    assertEquals(history.versions().size(), 4);
    for (SchemaHistory.VersionedSchema vs : history.versions()) {
      SchemaHistory.VersionedSchema shared = vs.nestedBeanSchemas().get(SharedLeaf.class);
      SchemaHistory.VersionedSchema middle = vs.nestedBeanSchemas().get(SharedMiddle.class);
      assertTrue(shared == middle.nestedBeanSchemas().get(SharedLeaf.class));
    }
  }

  @lombok.Data
  public static class SharedLeaf {
    private int a;

    @ForyRowVersion(since = 2)
    private int b;
  }

  @lombok.Data
  public static class SharedMiddle {
    private SharedLeaf leaf;

    @ForyRowVersion(since = 2)
    private int extra;
  }

  @lombok.Data
  public static class SharedOuter {
    private SharedLeaf leaf;
    private SharedMiddle middle;
  }

  /**
   * A field removed at one version and added again with the same name and type gives two versions
   * with the same layout, which collapse into one history entry. A payload with that layout cannot
   * say which version wrote it, so every nested combination must treat the field the same way: as
   * live, so that its value is read.
   */
  @Test
  public void collapsedVersionsKeepReaddedFieldLive() {
    SchemaHistory history = SchemaHistory.build(ReaddedField.class, STANDARD);
    assertEquals(history.versions().size(), 2);
    for (SchemaHistory.VersionedSchema vs : history.versions()) {
      assertTrue(vs.liveFieldNames().contains("x"), "x not live in " + vs.schema());
    }
  }

  @lombok.Data
  @ForyRowSchema(removedFields = ReaddedField.History.class)
  public static class ReaddedField {
    private SharedLeaf leaf;

    @ForyRowVersion(since = 2)
    private int x;

    interface History {
      @ForyRowVersion(until = 2)
      int x();
    }
  }

  /**
   * The removed and live types of {@code pair} differ, and their codecs write the same two children
   * in opposite orders. The standard format keeps each order, so the two layouts differ. The
   * compact format sorts both into one order, so an older compact layout is identical to the
   * current one, and the history must reject the type change.
   */
  @Test
  public void permutedCodecChildrenTypeChangeRejectedInCompact() {
    Encoders.registerCustomCodec(
        PermutedCodecField.class, WideFirst.class, new PermutedStructCodec<>(true));
    Encoders.registerCustomCodec(
        PermutedCodecField.class, NarrowFirst.class, new PermutedStructCodec<>(false));

    SchemaHistory.build(PermutedCodecField.class, STANDARD);
    IllegalStateException e =
        expectThrows(
            IllegalStateException.class,
            () -> SchemaHistory.build(PermutedCodecField.class, COMPACT));
    assertTrue(e.getMessage().contains("'pair'"), e.getMessage());
  }

  public static final class WideFirst {}

  public static final class NarrowFirst {}

  /** Writes a struct of an int64 and an int32 child, in the order the constructor selects. */
  private static final class PermutedStructCodec<T> implements CustomCodec<T, Object> {
    private final boolean wideFirst;

    PermutedStructCodec(boolean wideFirst) {
      this.wideFirst = wideFirst;
    }

    @Override
    public Field getForyField(String fieldName) {
      Field wide = DataTypes.field("wide", DataTypes.int64(), false);
      Field narrow = DataTypes.field("narrow", DataTypes.int32(), false);
      return wideFirst
          ? DataTypes.structField(fieldName, true, wide, narrow)
          : DataTypes.structField(fieldName, true, narrow, wide);
    }

    @Override
    public TypeRef<Object> encodedType() {
      return TypeRef.of(Object.class);
    }

    @Override
    public Object encode(T value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public T decode(Object value) {
      throw new UnsupportedOperationException();
    }
  }

  @lombok.Data
  @ForyRowSchema(removedFields = PermutedCodecField.History.class)
  public static class PermutedCodecField {
    @ForyRowVersion(since = 2)
    private String label;

    @ForyRowVersion(since = 3)
    private NarrowFirst pair;

    interface History {
      @ForyRowVersion(until = 2)
      WideFirst pair();
    }
  }

  private static long hash(Field... fields) {
    return SchemaHistory.computeStrictSchemaHash(STANDARD, DataTypes.schema(fields));
  }

  private static Field field(String name) {
    return DataTypes.field(name, DataTypes.int32(), false);
  }
}

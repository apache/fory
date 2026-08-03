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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;

import java.lang.reflect.TypeVariable;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.apache.fory.format.annotation.ForyVersion;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.TypeResolutionContext;
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
        SchemaHistory.computeStrictSchemaHash(fieldOutsideStruct),
        SchemaHistory.computeStrictSchemaHash(fieldInsideStruct));
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
        SchemaHistory.computeStrictSchemaHash(emptyThenSibling),
        SchemaHistory.computeStrictSchemaHash(siblingInsideStruct));
  }

  /**
   * Structurally identical schemas must still hash equal; the delimiter must not over-discriminate.
   */
  @Test
  public void identicalNestedStructsHashEqual() {
    assertEquals(
        SchemaHistory.computeStrictSchemaHash(
            DataTypes.schema(
                DataTypes.field("a", DataTypes.struct(field("x"), field("b")), false))),
        SchemaHistory.computeStrictSchemaHash(
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
            DataTypes.schema(DataTypes.field("a", DataTypes.decimal(10, 2), false)));
    long p20s2 =
        SchemaHistory.computeStrictSchemaHash(
            DataTypes.schema(DataTypes.field("a", DataTypes.decimal(20, 2), false)));
    long p10s4 =
        SchemaHistory.computeStrictSchemaHash(
            DataTypes.schema(DataTypes.field("a", DataTypes.decimal(10, 4), false)));
    assertNotEquals(p10s2, p20s2);
    assertNotEquals(p10s2, p10s4);
    assertNotEquals(p20s2, p10s4);
  }

  /** Identical decimal shapes must still hash equal; the precision/scale mix must not be noisy. */
  @Test
  public void identicalDecimalsHashEqual() {
    assertEquals(
        SchemaHistory.computeStrictSchemaHash(
            DataTypes.schema(DataTypes.field("a", DataTypes.decimal(10, 2), false))),
        SchemaHistory.computeStrictSchemaHash(
            DataTypes.schema(DataTypes.field("a", DataTypes.decimal(10, 2), false))));
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
   * TypeInference.inferField} resolves to its bound, see issue 2439) must descend through the same
   * wrapper grammar during evolution-site discovery. Before the bound was resolved in {@code
   * Wrapper.classify}, {@code getRawType} resolved the variable to {@code Optional} but the bare
   * variable carried no type arguments, so the OPTIONAL branch threw {@link
   * IndexOutOfBoundsException} reading the missing element. The bean inside the bound must be
   * discovered instead.
   */
  @Test
  public void typeVariableBoundedToWrapperResolvesToInnerBean() {
    TypeVariable<?> boundedToOptionalBean = OptionalBeanHolder.class.getTypeParameters()[0];
    TypeResolutionContext ctx =
        new TypeResolutionContext(CustomTypeEncoderRegistry.customTypeHandler(), true);

    assertEquals(SchemaHistory.evolutionBean(TypeRef.of(boundedToOptionalBean), ctx), Inner.class);
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
          SchemaHistory.build(bean, UnaryOperator.identity()).current().schema(),
          TypeInference.inferSchema(bean),
          "current() schema diverged from inferSchema for " + bean.getSimpleName());
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

    @ForyVersion(since = 2)
    private String added;
  }

  private static Field field(String name) {
    return DataTypes.field(name, DataTypes.int32(), false);
  }
}

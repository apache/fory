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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import lombok.Data;
import org.apache.fory.exception.ClassNotCompatibleException;
import org.apache.fory.format.annotation.ForySchema;
import org.apache.fory.format.annotation.ForyVersion;
import org.apache.fory.format.type.SchemaHistory;
import org.apache.fory.reflect.TypeRef;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Stress tests for row-codec schema evolution. Each test probes a specific edge case; the names say
 * what is being stressed. Tests that surfaced real bugs are kept with a note pointing at the fix;
 * tests kept for coverage are short.
 */
public class SchemaEvolutionStressTest {

  // ---------------------------------------------------------------------------
  // Long version chain: a field added at each version 1..5, plus a removal at v3.
  // Verifies projection codecs are built and dispatched for every historical version.
  // ---------------------------------------------------------------------------

  @Data
  public static class ChainV1 {
    private int a; // since 1
  }

  @Data
  public static class ChainV2 {
    private int a;

    @ForyVersion(since = 2)
    private String b;
  }

  @Data
  public static class ChainV3 {
    private int a;

    @ForyVersion(since = 2)
    private String b;

    @ForyVersion(since = 3)
    private long c;
  }

  @Data
  public static class ChainV4 {
    private int a;

    @ForyVersion(since = 2)
    private String b;

    @ForyVersion(since = 3)
    private long c;

    @ForyVersion(since = 4)
    private double d;
  }

  /**
   * v5 also removes the v1 'a' field starting at v5. The reader must therefore know about three
   * different historical schemas: v1, v2-3, and v4 (since 'a' is removed and a new field 'e' shows
   * up in v5; 'a' removal makes v5 differ from v4).
   */
  @Data
  @ForySchema(removedFields = ChainV5.History.class)
  public static class ChainV5 {
    @ForyVersion(since = 2)
    private String b;

    @ForyVersion(since = 3)
    private long c;

    @ForyVersion(since = 4)
    private double d;

    @ForyVersion(since = 5)
    private boolean e;

    interface History {
      @ForyVersion(until = 5)
      int a();
    }
  }

  @Test
  public void longChainAllVersionsReadable() {
    RowEncoder<ChainV1> w1 = evolvingCodec(ChainV1.class);
    RowEncoder<ChainV2> w2 = evolvingCodec(ChainV2.class);
    RowEncoder<ChainV3> w3 = evolvingCodec(ChainV3.class);
    RowEncoder<ChainV4> w4 = evolvingCodec(ChainV4.class);
    RowEncoder<ChainV5> reader = evolvingCodec(ChainV5.class);

    ChainV1 v1 = new ChainV1();
    v1.setA(11);
    ChainV2 v2 = new ChainV2();
    v2.setA(21);
    v2.setB("two");
    ChainV3 v3 = new ChainV3();
    v3.setA(31);
    v3.setB("three");
    v3.setC(333L);
    ChainV4 v4 = new ChainV4();
    v4.setA(41);
    v4.setB("four");
    v4.setC(444L);
    v4.setD(4.4);

    ChainV5 out1 = reader.decode(w1.encode(v1));
    Assert.assertNull(out1.getB());
    Assert.assertEquals(out1.getC(), 0L);
    Assert.assertEquals(out1.getD(), 0.0);
    Assert.assertFalse(out1.isE());

    ChainV5 out2 = reader.decode(w2.encode(v2));
    Assert.assertEquals(out2.getB(), "two");
    Assert.assertEquals(out2.getC(), 0L);

    ChainV5 out3 = reader.decode(w3.encode(v3));
    Assert.assertEquals(out3.getC(), 333L);
    Assert.assertEquals(out3.getD(), 0.0);

    ChainV5 out4 = reader.decode(w4.encode(v4));
    Assert.assertEquals(out4.getB(), "four");
    Assert.assertEquals(out4.getC(), 444L);
    Assert.assertEquals(out4.getD(), 4.4);
    Assert.assertFalse(out4.isE());
  }

  // ---------------------------------------------------------------------------
  // Compact format with alignment shuffle: v1 has only longs; v2 adds a byte.
  // Compact sorts fields by alignment width so the v1 and v2 schemas have
  // different physical orders, even though their logical field sets differ by
  // only the added byte.
  // ---------------------------------------------------------------------------

  @Data
  public static class AlignV1 {
    private long x;
    private long y;
  }

  @Data
  public static class AlignV2 {
    private long x;
    private long y;

    @ForyVersion(since = 2)
    private byte flag;
  }

  @Test
  public void compactAlignmentReshuffleAcrossVersions() {
    RowEncoder<AlignV1> writer =
        Encoders.buildBeanCodec(AlignV1.class)
            .compactEncoding()
            .withSchemaEvolution()
            .build()
            .get();
    RowEncoder<AlignV2> reader =
        Encoders.buildBeanCodec(AlignV2.class)
            .compactEncoding()
            .withSchemaEvolution()
            .build()
            .get();
    AlignV1 in = new AlignV1();
    in.setX(11);
    in.setY(22);
    AlignV2 out = reader.decode(writer.encode(in));
    Assert.assertEquals(out.getX(), 11);
    Assert.assertEquals(out.getY(), 22);
    Assert.assertEquals(out.getFlag(), (byte) 0); // primitive default
  }

  // ---------------------------------------------------------------------------
  // Boxed vs primitive default for an absent field.
  // ---------------------------------------------------------------------------

  @Data
  public static class DefaultsV1 {
    private String name;
  }

  @Data
  public static class DefaultsV2 {
    private String name;

    @ForyVersion(since = 2)
    private int primitiveCount; // default 0

    @ForyVersion(since = 2)
    private Integer boxedCount; // default null
  }

  @Test
  public void primitiveAndBoxedDefaults() {
    RowEncoder<DefaultsV1> writer = evolvingCodec(DefaultsV1.class);
    RowEncoder<DefaultsV2> reader = evolvingCodec(DefaultsV2.class);
    DefaultsV1 in = new DefaultsV1();
    in.setName("n");
    DefaultsV2 out = reader.decode(writer.encode(in));
    Assert.assertEquals(out.getName(), "n");
    Assert.assertEquals(out.getPrimitiveCount(), 0);
    Assert.assertNull(out.getBoxedCount());
  }

  // ---------------------------------------------------------------------------
  // Disjoint-window false collision (regression). A field whose [since, until)
  // window starts above the base version and ends below infinity leaves the
  // pre-since and post-until boundaries with identical field sets. SchemaHistory
  // must collapse those into one entry rather than flagging a false collision.
  // ---------------------------------------------------------------------------

  @Data
  @ForySchema(removedFields = GappedWindow.History.class)
  public static class GappedWindow {
    private String name;

    interface History {
      @ForyVersion(since = 2, until = 4)
      int oldField();
    }
  }

  @Test
  public void disjointWindowDoesNotFalseCollide() {
    // Build alone is the assertion: the bug was an IllegalStateException at build time.
    RowEncoder<GappedWindow> codec = evolvingCodec(GappedWindow.class);
    GappedWindow in = new GappedWindow();
    in.setName("hi");
    Assert.assertEquals(codec.decode(codec.encode(in)).getName(), "hi");
  }

  // ---------------------------------------------------------------------------
  // Removed field whose original type was a nested struct. The projection
  // codec must skip the slot without trying to read or decode it.
  // ---------------------------------------------------------------------------

  @Data
  public static class StructRefV1 {
    private String id;
    private DefaultsV1 detail; // removed at v2
    private long tail; // live in both versions, positioned after the removed slot
  }

  @Data
  @ForySchema(removedFields = StructRefV2.History.class)
  public static class StructRefV2 {
    private String id;
    private long tail;

    interface History {
      @ForyVersion(until = 2)
      DefaultsV1 detail();
    }
  }

  // A struct that itself contains a live, evolving nested bean. Used as a map KEY so the key
  // position must keep inKeyPosition set while recursing into the nested bean, not just for the
  // top-level key bean.
  @Data
  public static class KeyHolderV1 {
    private String id;
    private DefaultsV1 detail;
  }

  @Data
  public static class KeyHolderV2 {
    private String id;
    private DefaultsV2 detail;
  }

  @Test
  public void removedNestedStructField() {
    RowEncoder<StructRefV1> writer = evolvingCodec(StructRefV1.class);
    RowEncoder<StructRefV2> reader = evolvingCodec(StructRefV2.class);
    StructRefV1 in = new StructRefV1();
    in.setId("x");
    DefaultsV1 d = new DefaultsV1();
    d.setName("inner");
    in.setDetail(d);
    in.setTail(42L);
    StructRefV2 out = reader.decode(writer.encode(in));
    Assert.assertEquals(out.getId(), "x");
    // The field after the removed slot must read correctly, proving projection skipped exactly the
    // removed struct's width.
    Assert.assertEquals(out.getTail(), 42L);
  }

  // ---------------------------------------------------------------------------
  // Removed collection-typed field. The history interface preserves the full
  // parameterized type, so List<String> and Map<String, Long> round-trip
  // through the projection without losing element-type information.
  // ---------------------------------------------------------------------------

  @Data
  public static class CollectionsV1 {
    private String id;
    private List<String> tags; // removed at v2
    private Map<String, Long> counters; // removed at v2
    private long tail; // live in both versions, positioned after the removed slots
  }

  @Data
  @ForySchema(removedFields = CollectionsV2.History.class)
  public static class CollectionsV2 {
    private String id;
    private long tail;

    interface History {
      @ForyVersion(until = 2)
      List<String> tags();

      @ForyVersion(until = 2)
      Map<String, Long> counters();
    }
  }

  @Test
  public void removedParameterizedCollectionFields() {
    RowEncoder<CollectionsV1> writer = evolvingCodec(CollectionsV1.class);
    RowEncoder<CollectionsV2> reader = evolvingCodec(CollectionsV2.class);
    CollectionsV1 in = new CollectionsV1();
    in.setId("c");
    in.setTags(Arrays.asList("alpha", "beta"));
    Map<String, Long> counters = new HashMap<>();
    counters.put("k1", 1L);
    counters.put("k2", 2L);
    in.setCounters(counters);
    in.setTail(42L);
    CollectionsV2 out = reader.decode(writer.encode(in));
    Assert.assertEquals(out.getId(), "c");
    // The field after the removed slots must read correctly, proving projection skipped exactly the
    // two removed collection slots' width.
    Assert.assertEquals(out.getTail(), 42L);
  }

  // ---------------------------------------------------------------------------
  // Same wire-name retyped across versions: 'tag' was int [1,3), then String [3,inf).
  // ---------------------------------------------------------------------------

  @Data
  public static class RetypeV1 {
    private int tag; // present in v1, v2
  }

  @Data
  @ForySchema(removedFields = RetypeV3.History.class)
  public static class RetypeV3 {
    @ForyVersion(since = 3)
    private String tag;

    interface History {
      @ForyVersion(until = 3)
      int tag();
    }
  }

  @Test
  public void retypedSameNameAcrossVersions() {
    RowEncoder<RetypeV1> writer = evolvingCodec(RetypeV1.class);
    RowEncoder<RetypeV3> reader = evolvingCodec(RetypeV3.class);
    RetypeV1 in = new RetypeV1();
    in.setTag(7);
    RetypeV3 out = reader.decode(writer.encode(in));
    // The 'tag' on the wire was int and is dropped during projection; the v3 String 'tag' has
    // no source in this payload so defaults to null.
    Assert.assertNull(out.getTag());
  }

  // ---------------------------------------------------------------------------
  // Wide schema (more than 64 fields) crossing the null-bitmap word boundary.
  // ---------------------------------------------------------------------------

  @Data
  public static class WideV1 {
    private int f00, f01, f02, f03, f04, f05, f06, f07, f08, f09;
    private int f10, f11, f12, f13, f14, f15, f16, f17, f18, f19;
    private int f20, f21, f22, f23, f24, f25, f26, f27, f28, f29;
    private int f30, f31, f32, f33, f34, f35, f36, f37, f38, f39;
    private int f40, f41, f42, f43, f44, f45, f46, f47, f48, f49;
    private int f50, f51, f52, f53, f54, f55, f56, f57, f58, f59;
    private int f60, f61, f62, f63, f64, f65, f66, f67;
  }

  @Data
  public static class WideV2 {
    private int f00, f01, f02, f03, f04, f05, f06, f07, f08, f09;
    private int f10, f11, f12, f13, f14, f15, f16, f17, f18, f19;
    private int f20, f21, f22, f23, f24, f25, f26, f27, f28, f29;
    private int f30, f31, f32, f33, f34, f35, f36, f37, f38, f39;
    private int f40, f41, f42, f43, f44, f45, f46, f47, f48, f49;
    private int f50, f51, f52, f53, f54, f55, f56, f57, f58, f59;
    private int f60, f61, f62, f63, f64, f65, f66, f67;

    @ForyVersion(since = 2)
    private String extra;
  }

  @Test
  public void wideSchemaAcrossBitmapWord() {
    RowEncoder<WideV1> writer = evolvingCodec(WideV1.class);
    RowEncoder<WideV2> reader = evolvingCodec(WideV2.class);
    WideV1 in = new WideV1();
    in.setF00(100);
    in.setF63(163);
    in.setF67(167); // past the first 64-bit bitmap word
    WideV2 out = reader.decode(writer.encode(in));
    Assert.assertEquals(out.getF00(), 100);
    Assert.assertEquals(out.getF63(), 163);
    Assert.assertEquals(out.getF67(), 167);
    Assert.assertNull(out.getExtra());
  }

  // ---------------------------------------------------------------------------
  // Many elements through a single projection codec: 100 elements written by the
  // same older version must all decode correctly via the same projection codec,
  // with each element's data preserved and no carry-over of state across slots.
  // ---------------------------------------------------------------------------

  @Test
  public void arrayManyElementsThroughOneProjection() {
    ArrayEncoder<List<ChainV2>> writer =
        Encoders.buildArrayCodec(new TypeRef<List<ChainV2>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    ArrayEncoder<List<ChainV5>> reader =
        Encoders.buildArrayCodec(new TypeRef<List<ChainV5>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    List<ChainV2> in = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      ChainV2 e = new ChainV2();
      e.setA(i);
      e.setB("elem-" + i);
      in.add(e);
    }
    List<ChainV5> out = reader.decode(writer.encode(in));
    Assert.assertEquals(out.size(), 100);
    for (int i = 0; i < 100; i++) {
      Assert.assertEquals(out.get(i).getB(), "elem-" + i);
      Assert.assertEquals(out.get(i).getC(), 0L);
      Assert.assertFalse(out.get(i).isE());
    }
  }

  // ---------------------------------------------------------------------------
  // Sanity: two readers for the same (class, history) co-exist without
  // interfering. The two readers share the cached generated codec class (by
  // design of the codec cache), so the test exercises whether
  // BinaryRowEncoder's per-instance projection map and current-codec instance
  // are correctly per-reader rather than accidentally shared.
  // ---------------------------------------------------------------------------

  @Test
  public void twoIndependentReadersForSameClass() {
    RowEncoder<DefaultsV1> writer = evolvingCodec(DefaultsV1.class);
    RowEncoder<DefaultsV2> r1 = evolvingCodec(DefaultsV2.class);
    RowEncoder<DefaultsV2> r2 = evolvingCodec(DefaultsV2.class);
    DefaultsV1 in1 = new DefaultsV1();
    in1.setName("first");
    DefaultsV1 in2 = new DefaultsV1();
    in2.setName("second");
    byte[] b1 = writer.encode(in1);
    byte[] b2 = writer.encode(in2);
    Assert.assertEquals(r1.decode(b1).getName(), "first");
    Assert.assertEquals(r2.decode(b2).getName(), "second");
    Assert.assertEquals(r1.decode(b2).getName(), "second");
    Assert.assertEquals(r2.decode(b1).getName(), "first");
  }

  // ---------------------------------------------------------------------------
  // Schema-history misconfiguration: overlapping windows for the same name
  // must fail builder construction, not at first bad payload.
  // ---------------------------------------------------------------------------

  @Data
  @ForySchema(removedFields = OverlapMisconfig.History.class)
  public static class OverlapMisconfig {
    // Live field 'x' since 1 (default) collides with the removed window [1, 5).
    private int x;

    interface History {
      @ForyVersion(since = 1, until = 5)
      int x();
    }
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void overlappingWindowFailsAtBuild() {
    evolvingCodec(OverlapMisconfig.class);
  }

  // ---------------------------------------------------------------------------
  // A removed-field history declaration must carry a well-formed @ForyVersion.
  // Each misconfiguration fails at build with a message that names the offending
  // declaration, so the user can fix the annotation rather than chase a decode error.
  // ---------------------------------------------------------------------------

  @Data
  @ForySchema(removedFields = MissingAnnotation.History.class)
  public static class MissingAnnotation {
    private int x;

    interface History {
      // No @ForyVersion: a removed field has no [since, until) window without it.
      String legacy();
    }
  }

  @Data
  @ForySchema(removedFields = MissingUntil.History.class)
  public static class MissingUntil {
    private int x;

    interface History {
      @ForyVersion(since = 2)
      String legacy();
    }
  }

  @Data
  @ForySchema(removedFields = EmptyWindow.History.class)
  public static class EmptyWindow {
    private int x;

    interface History {
      @ForyVersion(since = 5, until = 5)
      String legacy();
    }
  }

  @Test
  public void removedFieldWithoutForyVersionFailsAtBuild() {
    IllegalStateException e =
        Assert.expectThrows(
            IllegalStateException.class,
            () ->
                Encoders.buildBeanCodec(MissingAnnotation.class)
                    .withSchemaEvolution()
                    .build()
                    .get());
    Assert.assertTrue(e.getMessage().contains("requires a @ForyVersion"), e.getMessage());
  }

  @Test
  public void removedFieldWithoutUntilFailsAtBuild() {
    IllegalStateException e =
        Assert.expectThrows(IllegalStateException.class, () -> evolvingCodec(MissingUntil.class));
    Assert.assertTrue(e.getMessage().contains("must specify @ForyVersion.until"), e.getMessage());
  }

  @Test
  public void removedFieldEmptyWindowFailsAtBuild() {
    IllegalStateException e =
        Assert.expectThrows(IllegalStateException.class, () -> evolvingCodec(EmptyWindow.class));
    Assert.assertTrue(e.getMessage().contains("must be strictly less than until"), e.getMessage());
  }

  /** A still-present field carrying a finite until; removals belong on the history class. */
  @Data
  public static class LiveFieldWithUntil {
    private int x;

    @ForyVersion(until = 3)
    private String stillHere;
  }

  @Test
  public void liveFieldWithUntilFailsAtBuild() {
    IllegalStateException e =
        Assert.expectThrows(
            IllegalStateException.class, () -> evolvingCodec(LiveFieldWithUntil.class));
    Assert.assertTrue(e.getMessage().contains("live field must not set until"), e.getMessage());
  }

  /** A since below the first version adds a schema version no writer can emit. */
  @Data
  public static class LiveFieldSinceBelowFirst {
    private int x;

    @ForyVersion(since = 0)
    private String added;
  }

  @Test
  public void liveFieldSinceBelowFirstFailsAtBuild() {
    IllegalStateException e =
        Assert.expectThrows(
            IllegalStateException.class, () -> evolvingCodec(LiveFieldSinceBelowFirst.class));
    Assert.assertTrue(e.getMessage().contains("must be >= 1"), e.getMessage());
  }

  @Data
  @ForySchema(removedFields = RemovedFieldSinceBelowFirst.History.class)
  public static class RemovedFieldSinceBelowFirst {
    private int x;

    interface History {
      @ForyVersion(since = 0, until = 3)
      String legacy();
    }
  }

  @Test
  public void removedFieldSinceBelowFirstFailsAtBuild() {
    IllegalStateException e =
        Assert.expectThrows(
            IllegalStateException.class, () -> evolvingCodec(RemovedFieldSinceBelowFirst.class));
    Assert.assertTrue(e.getMessage().contains("must be >= 1"), e.getMessage());
  }

  // ---------------------------------------------------------------------------
  // A field whose type is a Collection subclass that shadows a field name across
  // its own hierarchy. The row format encodes it through the iterable branch and
  // never introspects it as a bean, so it round-trips fine. SchemaHistory must
  // apply the same iterable/map/bean classification before introspecting a nested
  // field type; otherwise it calls Descriptor.getDescriptors on the shadowed
  // collection class and fails the whole history build on a bean that works.
  // ---------------------------------------------------------------------------

  public static class TaggedListBase<E> extends ArrayList<E> {
    protected String marker;
  }

  // Shadows TaggedListBase.marker, which makes Descriptor.getDescriptors reject
  // this class even though the codec treats it purely as a List.
  public static class TaggedList<E> extends TaggedListBase<E> {
    protected String marker;
  }

  @Data
  public static class ShadowedCollectionV2 {
    private TaggedList<String> labels;

    @ForyVersion(since = 2)
    private String tag;
  }

  @Test
  public void versionedBeanWithShadowedCollectionFieldBuilds() {
    RowEncoder<ShadowedCollectionV2> codec = evolvingCodec(ShadowedCollectionV2.class);
    ShadowedCollectionV2 in = new ShadowedCollectionV2();
    TaggedList<String> labels = new TaggedList<>();
    labels.add("a");
    labels.add("b");
    in.setLabels(labels);
    in.setTag("t");
    ShadowedCollectionV2 out = codec.decode(codec.encode(in));
    Assert.assertEquals(out.getLabels(), Arrays.asList("a", "b"));
    Assert.assertEquals(out.getTag(), "t");
  }

  // ---------------------------------------------------------------------------
  // Roundtrip a List<DefaultsV1> field nested inside a versioned outer record.
  // Verifies the projection codec generated for the outer correctly handles
  // an inline list of plain beans whose layout is fixed.
  // ---------------------------------------------------------------------------

  @Data
  public static class NestedListV1 {
    private List<DefaultsV1> items;
  }

  @Data
  public static class NestedListV2 {
    private List<DefaultsV1> items;

    @ForyVersion(since = 2)
    private String tag;
  }

  // ---------------------------------------------------------------------------
  // Evolution flag asymmetry: same class, one side opt-in, the other not.
  // Documented as wire-incompatible across the row, array, and map codecs.
  // Forward (evolution-on reading evolution-off bytes) is a clean
  // ClassNotCompatibleException on every codec: the missing strict-hash prefix
  // is read as a mismatched hash. Reverse (evolution-off reading evolution-on
  // bytes) differs by codec. The row's hash slot exists in both modes, so the
  // off-reader sees a mismatched hash and throws cleanly. Array/map have no hash
  // slot when off, so the prefix bleeds into the header and trips an internal
  // bounds check (assertion-gated, fires under Surefire's -ea); we pin only that
  // the decode does not silently return.
  // ---------------------------------------------------------------------------

  @DataProvider
  public Object[][] flagAsymmetryCases() {
    DefaultsV1 v = new DefaultsV1();
    v.setName("hi");

    RowEncoder<DefaultsV1> rowOn = evolvingCodec(DefaultsV1.class);
    RowEncoder<DefaultsV1> rowOff = Encoders.buildBeanCodec(DefaultsV1.class).build().get();

    ArrayEncoder<List<DefaultsV1>> arrayOn =
        Encoders.buildArrayCodec(new TypeRef<List<DefaultsV1>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    ArrayEncoder<List<DefaultsV1>> arrayOff =
        Encoders.buildArrayCodec(new TypeRef<List<DefaultsV1>>() {}).build().get();
    List<DefaultsV1> list = Arrays.asList(v);

    MapEncoder<Map<String, DefaultsV1>> mapOn =
        Encoders.buildMapCodec(new TypeRef<Map<String, DefaultsV1>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    MapEncoder<Map<String, DefaultsV1>> mapOff =
        Encoders.buildMapCodec(new TypeRef<Map<String, DefaultsV1>>() {}).build().get();
    LinkedHashMap<String, DefaultsV1> map = new LinkedHashMap<>();
    map.put("k", v);

    return new Object[][] {
      {
        "row",
        new FlagAsymmetryCase(
            rowOn.encode(v), rowOff.encode(v), rowOn::decode, rowOff::decode, true)
      },
      {
        "array",
        new FlagAsymmetryCase(
            arrayOn.encode(list), arrayOff.encode(list), arrayOn::decode, arrayOff::decode, false)
      },
      {
        "map",
        new FlagAsymmetryCase(
            mapOn.encode(map), mapOff.encode(map), mapOn::decode, mapOff::decode, false)
      },
    };
  }

  @Test(dataProvider = "flagAsymmetryCases")
  public void evolutionFlagAsymmetryFailsLoud(String label, FlagAsymmetryCase c) {
    // Forward: evolution-on reader, evolution-off bytes -> clean ClassNotCompatibleException.
    Assert.expectThrows(ClassNotCompatibleException.class, () -> c.onDecoder.accept(c.offBytes));
    // Reverse: evolution-off reader, evolution-on bytes.
    if (c.reverseIsCleanException) {
      Assert.expectThrows(ClassNotCompatibleException.class, () -> c.offDecoder.accept(c.onBytes));
    } else {
      assertDecodeThrows(() -> c.offDecoder.accept(c.onBytes));
    }
  }

  private static final class FlagAsymmetryCase {
    final byte[] onBytes;
    final byte[] offBytes;
    final Consumer<byte[]> onDecoder;
    final Consumer<byte[]> offDecoder;
    final boolean reverseIsCleanException;

    FlagAsymmetryCase(
        byte[] onBytes,
        byte[] offBytes,
        Consumer<byte[]> onDecoder,
        Consumer<byte[]> offDecoder,
        boolean reverseIsCleanException) {
      this.onBytes = onBytes;
      this.offBytes = offBytes;
      this.onDecoder = onDecoder;
      this.offDecoder = offDecoder;
      this.reverseIsCleanException = reverseIsCleanException;
    }
  }

  // ---------------------------------------------------------------------------
  // mapClassSuffix injectivity: the generated map codec class name is val + "_K" + key, and the
  // class is cached by name, so two distinct (value-combo, key-combo) pairs must never produce the
  // same suffix or the second pair would reuse the first's codec class. The "_K" boundary is the
  // only place value and key strings meet, so the adversarial case is a value-side nested bean
  // whose
  // own suffix token contains the literal "_K_V" boundary string. A bean named K_V yields exactly
  // that token.
  // ---------------------------------------------------------------------------

  @Data
  public static class KVTokenV2 {
    private int a;

    @ForyVersion(since = 2)
    private String b;
  }

  /**
   * Outer value bean carrying a nested bean whose simple name is K_V, so its suffix token is _K_V*.
   */
  @Data
  public static class ValueWithKVNested {
    private K_V inner;

    @ForyVersion(since = 2)
    private String tag;
  }

  /** Simple name is literally "K_V": its projection token is "_K_V<ver>h<hash>". */
  @Data
  public static class K_V {
    private int x;

    @ForyVersion(since = 2)
    private String y;
  }

  @Test
  public void mapClassSuffixIsInjective() {
    // Build histories for a value position that contains the adversarial _K_V token and a key
    // position that also evolves, then join every (value, key) combination the way mapClassSuffix
    // does and require the joined suffixes to be distinct.
    SchemaHistory valHistory =
        SchemaHistory.build(ValueWithKVNested.class, UnaryOperator.identity());
    SchemaHistory keyHistory = SchemaHistory.build(KVTokenV2.class, UnaryOperator.identity());

    Set<String> joined = new HashSet<>();
    Set<String> pairs = new HashSet<>();
    for (SchemaHistory.VersionedSchema valVs : valHistory.versions()) {
      String val = valVs.isCurrent() ? "" : ProjectionRouting.projectionSuffix(valVs);
      for (SchemaHistory.VersionedSchema keyVs : keyHistory.versions()) {
        String keyRaw = keyVs.isCurrent() ? "" : ProjectionRouting.projectionSuffix(keyVs);
        String key = keyRaw.isEmpty() ? "" : "_K" + keyRaw;
        // Mirror mapClassSuffix() exactly.
        String suffix = val + key;
        pairs.add(val + "\0" + key);
        Assert.assertTrue(
            joined.add(suffix),
            "mapClassSuffix collision: distinct (value, key) combinations produced the same class "
                + "suffix \""
                + suffix
                + "\"");
      }
    }
    // Sanity: we actually exercised more than one distinct (value, key) pair, including a value
    // suffix that contains the _K_V boundary string.
    Assert.assertTrue(pairs.size() > 1, "expected multiple combinations");
    Assert.assertTrue(
        valHistory.versions().stream()
            .anyMatch(
                vs -> !vs.isCurrent() && ProjectionRouting.projectionSuffix(vs).contains("_K_V")),
        "test did not exercise the _K_V boundary token it was designed to probe");
  }

  /**
   * Asserts that an evolution-off decode of evolution-on bytes does not silently succeed. This
   * direction has no hash slot to compare, so the failure surfaces as an internal bounds check
   * rather than a {@link ClassNotCompatibleException}; that check is assertion-gated, so the test
   * only has teeth with JVM assertions enabled. Verify {@code -ea} is on first, otherwise this
   * helper would pass vacuously and the guarantee could rot unnoticed.
   */
  private static void assertDecodeThrows(Runnable decode) {
    boolean assertionsEnabled = false;
    assert assertionsEnabled = true;
    Assert.assertTrue(
        assertionsEnabled,
        "JVM assertions (-ea) must be enabled for this test: the evolution-off reverse-decode "
            + "guard is assertion-gated and would pass vacuously otherwise");
    try {
      decode.run();
    } catch (RuntimeException | AssertionError expected) {
      return;
    }
    Assert.fail("evolution-off decoder silently accepted evolution-on bytes");
  }

  // ---------------------------------------------------------------------------
  // Map with a versioned bean as the KEY: current-version round-trip.
  // Cross-version key evolution is covered by evolveMapKey* below; this pins the
  // same-schema baseline so a regression there is distinguishable from a build
  // or encode/decode fault.
  // ---------------------------------------------------------------------------

  @Test
  public void mapWithVersionedKey() {
    MapEncoder<Map<DefaultsV2, String>> codec =
        Encoders.buildMapCodec(new TypeRef<Map<DefaultsV2, String>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    DefaultsV2 k = new DefaultsV2();
    k.setName("k");
    k.setPrimitiveCount(1);
    k.setBoxedCount(2);
    Map<DefaultsV2, String> in = new HashMap<>();
    in.put(k, "v");
    Map<DefaultsV2, String> out = codec.decode(codec.encode(in));
    Assert.assertEquals(out.size(), 1);
    DefaultsV2 outKey = out.keySet().iterator().next();
    Assert.assertEquals(outKey.getName(), "k");
    Assert.assertEquals(outKey.getPrimitiveCount(), 1);
    Assert.assertEquals(outKey.getBoxedCount(), Integer.valueOf(2));
  }

  // A top-level map whose value evolves while the key stays a struct bean. The value projects from
  // an older version; the key (same shape on both sides) must round-trip unchanged. The map codec
  // only applies the value's projection suffix to the value position (MapEncoderBuilder scopes
  // nestedBeanSuffix to inValuePosition), so the key bean is always decoded at its current schema.
  @Test
  public void mapStructKeyValueEvolution() {
    MapEncoder<Map<DefaultsV2, DefaultsV1>> writer =
        Encoders.buildMapCodec(new TypeRef<Map<DefaultsV2, DefaultsV1>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    MapEncoder<Map<DefaultsV2, DefaultsV2>> reader =
        Encoders.buildMapCodec(new TypeRef<Map<DefaultsV2, DefaultsV2>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    DefaultsV2 key = new DefaultsV2();
    key.setName("k");
    key.setPrimitiveCount(7);
    key.setBoxedCount(8);
    DefaultsV1 val = new DefaultsV1();
    val.setName("val");
    Map<DefaultsV2, DefaultsV1> in = new HashMap<>();
    in.put(key, val);
    Map<DefaultsV2, DefaultsV2> out = reader.decode(writer.encode(in));
    Assert.assertEquals(out.size(), 1);
    Map.Entry<DefaultsV2, DefaultsV2> entry = out.entrySet().iterator().next();
    Assert.assertEquals(entry.getKey().getName(), "k");
    Assert.assertEquals(entry.getKey().getPrimitiveCount(), 7);
    Assert.assertEquals(entry.getKey().getBoxedCount(), Integer.valueOf(8));
    Assert.assertEquals(entry.getValue().getName(), "val");
    Assert.assertEquals(entry.getValue().getPrimitiveCount(), 0);
    Assert.assertNull(entry.getValue().getBoxedCount());
  }

  // A row field typed as Map<VersionedKeyBean, String>. findVersionedBean must not treat the map
  // key
  // as a version dimension: keys carry no per-payload hash and are read with the current schema, so
  // enumerating key versions would only generate projection codecs decode never dispatches to. The
  // outer bean still evolves on its own fields; the keyed map round-trips with the key at current.
  @Data
  public static class KeyMapHolderV1 {
    private Map<DefaultsV2, String> byKey;
  }

  @Data
  public static class KeyMapHolderV2 {
    private Map<DefaultsV2, String> byKey;

    @ForyVersion(since = 2)
    private String note;
  }

  @Test
  public void versionedBeanAsMapKeyInRowField() {
    RowEncoder<KeyMapHolderV1> writer = evolvingCodec(KeyMapHolderV1.class);
    RowEncoder<KeyMapHolderV2> reader = evolvingCodec(KeyMapHolderV2.class);
    DefaultsV2 key = new DefaultsV2();
    key.setName("k");
    key.setPrimitiveCount(7);
    key.setBoxedCount(8);
    KeyMapHolderV1 in = new KeyMapHolderV1();
    in.setByKey(new HashMap<>());
    in.getByKey().put(key, "v");
    KeyMapHolderV2 out = reader.decode(writer.encode(in));
    Assert.assertEquals(out.getByKey().size(), 1);
    DefaultsV2 outKey = out.getByKey().keySet().iterator().next();
    Assert.assertEquals(outKey.getName(), "k");
    Assert.assertEquals(outKey.getPrimitiveCount(), 7);
    Assert.assertEquals(out.getByKey().get(outKey), "v");
    Assert.assertNull(out.getNote()); // note added at v2; v1 payload defaults it
  }

  // A row field whose map KEY is a versioned bean that actually skews across writer and reader:
  // writer's key is DefaultsV1 (name only), reader's key is DefaultsV2 (adds two since=2 fields).
  // Unlike versionedBeanAsMapKeyInRowField, the key version differs on the two sides, so this is
  // the case that must not corrupt: the reader must materialize the since=2 fields at their
  // defaults rather than reinterpreting the writer's bytes against the current key layout.
  @Data
  public static class SkewKeyMapHolderV1 {
    private Map<DefaultsV1, String> byKey;
  }

  @Data
  public static class SkewKeyMapHolderV2 {
    private Map<DefaultsV2, String> byKey;
  }

  @Test
  public void evolvingMapKeyInRowField() {
    RowEncoder<SkewKeyMapHolderV1> writer = evolvingCodec(SkewKeyMapHolderV1.class);
    RowEncoder<SkewKeyMapHolderV2> reader = evolvingCodec(SkewKeyMapHolderV2.class);
    DefaultsV1 key = new DefaultsV1();
    key.setName("k");
    SkewKeyMapHolderV1 in = new SkewKeyMapHolderV1();
    in.setByKey(new HashMap<>());
    in.getByKey().put(key, "v");
    SkewKeyMapHolderV2 out = reader.decode(writer.encode(in));
    Assert.assertEquals(out.getByKey().size(), 1);
    DefaultsV2 outKey = out.getByKey().keySet().iterator().next();
    Assert.assertEquals(outKey.getName(), "k");
    Assert.assertEquals(outKey.getPrimitiveCount(), 0); // since=2 field absent in writer; defaults
    Assert.assertNull(outKey.getBoxedCount());
    Assert.assertEquals(out.getByKey().get(outKey), "v");
  }

  // A versioned bean as the KEY of a map nested inside another map's value: the substitution path
  // must take value at the outer map and key at the inner map. A single key/value flag cannot
  // express that two-step descent, so this exercises the multi-step branch path.
  @Data
  public static class NestedKeyMapHolderV1 {
    private Map<String, Map<DefaultsV1, String>> outer;
  }

  @Data
  public static class NestedKeyMapHolderV2 {
    private Map<String, Map<DefaultsV2, String>> outer;
  }

  @Test
  public void evolvingKeyOfNestedMap() {
    RowEncoder<NestedKeyMapHolderV1> writer = evolvingCodec(NestedKeyMapHolderV1.class);
    RowEncoder<NestedKeyMapHolderV2> reader = evolvingCodec(NestedKeyMapHolderV2.class);
    DefaultsV1 key = new DefaultsV1();
    key.setName("k");
    Map<DefaultsV1, String> inner = new HashMap<>();
    inner.put(key, "v");
    NestedKeyMapHolderV1 in = new NestedKeyMapHolderV1();
    in.setOuter(new HashMap<>());
    in.getOuter().put("o", inner);
    NestedKeyMapHolderV2 out = reader.decode(writer.encode(in));
    Map<DefaultsV2, String> outInner = out.getOuter().get("o");
    Assert.assertEquals(outInner.size(), 1);
    DefaultsV2 outKey = outInner.keySet().iterator().next();
    Assert.assertEquals(outKey.getName(), "k");
    Assert.assertEquals(outKey.getPrimitiveCount(), 0); // since=2 field absent in writer; defaults
    Assert.assertNull(outKey.getBoxedCount());
    Assert.assertEquals(outInner.get(outKey), "v");
  }

  // ---------------------------------------------------------------------------
  // Evolving map key: the map header's combined (key,value) hash selects the
  // historical key layout, so an older key decodes correctly instead of being
  // read against the current layout and corrupting silently.
  // ---------------------------------------------------------------------------

  @Test
  public void evolveMapKeyValuePrimitive() {
    // Writer key is DefaultsV1 (name only); reader key is DefaultsV2 (adds two since=2 fields).
    // Value is a plain String on both sides, so only the key dimension evolves.
    MapEncoder<Map<DefaultsV1, String>> writer =
        Encoders.buildMapCodec(new TypeRef<Map<DefaultsV1, String>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    MapEncoder<Map<DefaultsV2, String>> reader =
        Encoders.buildMapCodec(new TypeRef<Map<DefaultsV2, String>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    DefaultsV1 k = new DefaultsV1();
    k.setName("k");
    Map<DefaultsV1, String> in = new HashMap<>();
    in.put(k, "v");
    Map<DefaultsV2, String> out = reader.decode(writer.encode(in));
    Assert.assertEquals(out.size(), 1);
    Map.Entry<DefaultsV2, String> e = out.entrySet().iterator().next();
    Assert.assertEquals(e.getKey().getName(), "k");
    Assert.assertEquals(e.getKey().getPrimitiveCount(), 0); // absent since=2 field defaults
    Assert.assertNull(e.getKey().getBoxedCount());
    Assert.assertEquals(e.getValue(), "v");
  }

  @Test
  public void evolveBothMapKeyAndValue() {
    // Both key and value evolve independently: V1 writer, V2 reader on each side.
    MapEncoder<Map<DefaultsV1, DefaultsV1>> writer =
        Encoders.buildMapCodec(new TypeRef<Map<DefaultsV1, DefaultsV1>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    MapEncoder<Map<DefaultsV2, DefaultsV2>> reader =
        Encoders.buildMapCodec(new TypeRef<Map<DefaultsV2, DefaultsV2>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    DefaultsV1 k = new DefaultsV1();
    k.setName("k");
    DefaultsV1 v = new DefaultsV1();
    v.setName("val");
    Map<DefaultsV1, DefaultsV1> in = new HashMap<>();
    in.put(k, v);
    Map<DefaultsV2, DefaultsV2> out = reader.decode(writer.encode(in));
    Assert.assertEquals(out.size(), 1);
    Map.Entry<DefaultsV2, DefaultsV2> e = out.entrySet().iterator().next();
    Assert.assertEquals(e.getKey().getName(), "k");
    Assert.assertEquals(e.getKey().getPrimitiveCount(), 0);
    Assert.assertNull(e.getKey().getBoxedCount());
    Assert.assertEquals(e.getValue().getName(), "val");
    Assert.assertEquals(e.getValue().getPrimitiveCount(), 0);
    Assert.assertNull(e.getValue().getBoxedCount());
  }

  @Test
  public void evolveMapKeyCurrentValueOlder() {
    // Writer key is already current (V2) but value is older (V1). Exercises the (key-current,
    // value-older) projection, which must not collide with the (key-older, value-current) class
    // name -- both bean suffixes are otherwise _V1.
    MapEncoder<Map<DefaultsV2, DefaultsV1>> writer =
        Encoders.buildMapCodec(new TypeRef<Map<DefaultsV2, DefaultsV1>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    MapEncoder<Map<DefaultsV2, DefaultsV2>> reader =
        Encoders.buildMapCodec(new TypeRef<Map<DefaultsV2, DefaultsV2>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    DefaultsV2 k = new DefaultsV2();
    k.setName("k");
    k.setPrimitiveCount(9);
    k.setBoxedCount(3);
    DefaultsV1 v = new DefaultsV1();
    v.setName("val");
    Map<DefaultsV2, DefaultsV1> in = new HashMap<>();
    in.put(k, v);
    Map<DefaultsV2, DefaultsV2> out = reader.decode(writer.encode(in));
    Assert.assertEquals(out.size(), 1);
    Map.Entry<DefaultsV2, DefaultsV2> e = out.entrySet().iterator().next();
    Assert.assertEquals(e.getKey().getName(), "k");
    Assert.assertEquals(e.getKey().getPrimitiveCount(), 9); // key fully present
    Assert.assertEquals(e.getKey().getBoxedCount(), Integer.valueOf(3));
    Assert.assertEquals(e.getValue().getName(), "val");
    Assert.assertEquals(e.getValue().getPrimitiveCount(), 0); // value's since=2 fields default
    Assert.assertNull(e.getValue().getBoxedCount());
  }

  @Test
  public void evolveMapSameBeanKeyAndValueCrossCombos() {
    // Key and value are the SAME versioned bean class, so the (key-older, value-current) and
    // (key-current, value-older) combinations share both bean suffixes (_V1). Without namespacing
    // the key suffix in the map class name they would collapse onto one generated class, and one of
    // the two payloads below would decode with the wrong codec. Reader at (V2,V2) must decode both.
    MapEncoder<Map<DefaultsV1, DefaultsV2>> keyOlderWriter =
        Encoders.buildMapCodec(new TypeRef<Map<DefaultsV1, DefaultsV2>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    MapEncoder<Map<DefaultsV2, DefaultsV1>> valOlderWriter =
        Encoders.buildMapCodec(new TypeRef<Map<DefaultsV2, DefaultsV1>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    MapEncoder<Map<DefaultsV2, DefaultsV2>> reader =
        Encoders.buildMapCodec(new TypeRef<Map<DefaultsV2, DefaultsV2>>() {})
            .withSchemaEvolution()
            .build()
            .get();

    DefaultsV1 oldKey = new DefaultsV1();
    oldKey.setName("ok");
    DefaultsV2 newVal = new DefaultsV2();
    newVal.setName("nv");
    newVal.setPrimitiveCount(5);
    newVal.setBoxedCount(6);
    Map<DefaultsV1, DefaultsV2> keyOlder = new HashMap<>();
    keyOlder.put(oldKey, newVal);
    Map<DefaultsV2, DefaultsV2> outA = reader.decode(keyOlderWriter.encode(keyOlder));
    Map.Entry<DefaultsV2, DefaultsV2> a = outA.entrySet().iterator().next();
    Assert.assertEquals(a.getKey().getName(), "ok");
    Assert.assertEquals(a.getKey().getPrimitiveCount(), 0); // key was V1, since=2 fields default
    Assert.assertEquals(a.getValue().getName(), "nv");
    Assert.assertEquals(a.getValue().getPrimitiveCount(), 5); // value was V2, fully present
    Assert.assertEquals(a.getValue().getBoxedCount(), Integer.valueOf(6));

    DefaultsV2 newKey = new DefaultsV2();
    newKey.setName("nk");
    newKey.setPrimitiveCount(7);
    newKey.setBoxedCount(8);
    DefaultsV1 oldVal = new DefaultsV1();
    oldVal.setName("ov");
    Map<DefaultsV2, DefaultsV1> valOlder = new HashMap<>();
    valOlder.put(newKey, oldVal);
    Map<DefaultsV2, DefaultsV2> outB = reader.decode(valOlderWriter.encode(valOlder));
    Map.Entry<DefaultsV2, DefaultsV2> b = outB.entrySet().iterator().next();
    Assert.assertEquals(b.getKey().getName(), "nk");
    Assert.assertEquals(b.getKey().getPrimitiveCount(), 7); // key was V2, fully present
    Assert.assertEquals(b.getKey().getBoxedCount(), Integer.valueOf(8));
    Assert.assertEquals(b.getValue().getName(), "ov");
    Assert.assertEquals(
        b.getValue().getPrimitiveCount(), 0); // value was V1, since=2 fields default
  }

  @Test
  public void evolveMapKeyCompact() {
    // The key dimension must also project under the compact format, which sorts schema fields by
    // alignment; the historical key schema is sorted the same way as the writer's, so the layouts
    // line up.
    MapEncoder<Map<DefaultsV1, String>> writer =
        Encoders.buildMapCodec(new TypeRef<Map<DefaultsV1, String>>() {})
            .compactEncoding()
            .withSchemaEvolution()
            .build()
            .get();
    MapEncoder<Map<DefaultsV2, String>> reader =
        Encoders.buildMapCodec(new TypeRef<Map<DefaultsV2, String>>() {})
            .compactEncoding()
            .withSchemaEvolution()
            .build()
            .get();
    DefaultsV1 k = new DefaultsV1();
    k.setName("k");
    Map<DefaultsV1, String> in = new HashMap<>();
    in.put(k, "v");
    Map<DefaultsV2, String> out = reader.decode(writer.encode(in));
    Assert.assertEquals(out.size(), 1);
    Map.Entry<DefaultsV2, String> e = out.entrySet().iterator().next();
    Assert.assertEquals(e.getKey().getName(), "k");
    Assert.assertEquals(e.getKey().getPrimitiveCount(), 0);
    Assert.assertEquals(e.getValue(), "v");
  }

  @Test
  public void evolveMapStructKeyWithNestedBean() {
    // The key is a struct that itself wraps an evolving nested bean. inKeyPosition must stay set
    // while the key subtree recurses into the nested bean, both at expression construction and in
    // KeyPositionScope's genCode. If it does not, the nested key bean registers/decodes under the
    // value-position codec key and decode fails loud ("No bean codec registered ... key/value
    // position"). The nested detail is written at V1 and read by a V2 reader, so the nested key
    // bean must project to its historical layout, not the value's.
    MapEncoder<Map<KeyHolderV1, String>> writer =
        Encoders.buildMapCodec(new TypeRef<Map<KeyHolderV1, String>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    MapEncoder<Map<KeyHolderV2, String>> reader =
        Encoders.buildMapCodec(new TypeRef<Map<KeyHolderV2, String>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    KeyHolderV1 k = new KeyHolderV1();
    k.setId("h");
    DefaultsV1 d = new DefaultsV1();
    d.setName("inner");
    k.setDetail(d);
    Map<KeyHolderV1, String> in = new HashMap<>();
    in.put(k, "v");
    Map<KeyHolderV2, String> out = reader.decode(writer.encode(in));
    Assert.assertEquals(out.size(), 1);
    Map.Entry<KeyHolderV2, String> e = out.entrySet().iterator().next();
    Assert.assertEquals(e.getKey().getId(), "h");
    Assert.assertEquals(e.getKey().getDetail().getName(), "inner");
    Assert.assertEquals(e.getKey().getDetail().getPrimitiveCount(), 0); // since=2, absent in writer
    Assert.assertNull(e.getKey().getDetail().getBoxedCount());
    Assert.assertEquals(e.getValue(), "v");
  }

  @Test
  public void evolveMapCollectionKey() {
    // The key is a List of versioned beans, so the key subtree is a collection rather than a bare
    // bean. This drives the keyScoped(deserializeForCollection) branch: inKeyPosition must stay set
    // through the collection element decode so the element bean projects to its historical layout
    // under the key-position codec, not the value's.
    MapEncoder<Map<List<DefaultsV1>, String>> writer =
        Encoders.buildMapCodec(new TypeRef<Map<List<DefaultsV1>, String>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    MapEncoder<Map<List<DefaultsV2>, String>> reader =
        Encoders.buildMapCodec(new TypeRef<Map<List<DefaultsV2>, String>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    DefaultsV1 d = new DefaultsV1();
    d.setName("k");
    Map<List<DefaultsV1>, String> in = new HashMap<>();
    in.put(Arrays.asList(d), "v");
    Map<List<DefaultsV2>, String> out = reader.decode(writer.encode(in));
    Assert.assertEquals(out.size(), 1);
    Map.Entry<List<DefaultsV2>, String> e = out.entrySet().iterator().next();
    Assert.assertEquals(e.getKey().size(), 1);
    Assert.assertEquals(e.getKey().get(0).getName(), "k");
    Assert.assertEquals(e.getKey().get(0).getPrimitiveCount(), 0); // since=2, absent in writer
    Assert.assertNull(e.getKey().get(0).getBoxedCount());
    Assert.assertEquals(e.getValue(), "v");
  }

  // A versioned bean as the KEY of an inner map reached through a top-level array or map wrapper.
  // The top-level codec's own key/value hash only covers a top-level map's own key, and the
  // row-field path (collectNestedSites) only applies inside a bean field, so this wrapper-reached
  // inner key is the shape that must still evolve through the top-level array/map entry point.

  @Test
  public void arrayOfMapKeyOlderPayloadReadByNewerCodec() {
    ArrayEncoder<List<Map<DefaultsV1, String>>> writer =
        Encoders.buildArrayCodec(new TypeRef<List<Map<DefaultsV1, String>>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    ArrayEncoder<List<Map<DefaultsV2, String>>> reader =
        Encoders.buildArrayCodec(new TypeRef<List<Map<DefaultsV2, String>>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    DefaultsV1 k = new DefaultsV1();
    k.setName("k");
    Map<DefaultsV1, String> inner = new HashMap<>();
    inner.put(k, "v");
    List<Map<DefaultsV2, String>> out = reader.decode(writer.encode(Arrays.asList(inner)));
    Assert.assertEquals(out.size(), 1);
    Map.Entry<DefaultsV2, String> e = out.get(0).entrySet().iterator().next();
    Assert.assertEquals(e.getKey().getName(), "k");
    Assert.assertEquals(e.getKey().getPrimitiveCount(), 0); // since=2 field absent in writer
    Assert.assertNull(e.getKey().getBoxedCount());
    Assert.assertEquals(e.getValue(), "v");
  }

  // A top-level map whose VALUE is itself a Map<KBean, VBean> with two DIFFERENT versioned bean
  // classes. The value position must evolve both inner beans; before the fix the value position
  // enumerated only one and generated a codec for only it.
  @Test
  public void mapValueWrappingTwoBeansOlderPayloadReadByNewerCodec() {
    MapEncoder<Map<String, Map<AlignV1, DefaultsV1>>> writer =
        Encoders.buildMapCodec(new TypeRef<Map<String, Map<AlignV1, DefaultsV1>>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    MapEncoder<Map<String, Map<AlignV2, DefaultsV2>>> reader =
        Encoders.buildMapCodec(new TypeRef<Map<String, Map<AlignV2, DefaultsV2>>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    AlignV1 k = new AlignV1();
    k.setX(11);
    k.setY(22);
    DefaultsV1 v = new DefaultsV1();
    v.setName("val");
    Map<AlignV1, DefaultsV1> inner = new HashMap<>();
    inner.put(k, v);
    Map<String, Map<AlignV1, DefaultsV1>> in = new HashMap<>();
    in.put("o", inner);
    Map<String, Map<AlignV2, DefaultsV2>> out = reader.decode(writer.encode(in));
    Map.Entry<AlignV2, DefaultsV2> e = out.get("o").entrySet().iterator().next();
    Assert.assertEquals(e.getKey().getX(), 11);
    Assert.assertEquals(e.getKey().getY(), 22);
    Assert.assertEquals(e.getKey().getFlag(), (byte) 0);
    Assert.assertEquals(e.getValue().getName(), "val");
    Assert.assertEquals(e.getValue().getPrimitiveCount(), 0);
    Assert.assertNull(e.getValue().getBoxedCount());
  }

  @Test
  public void mapOfMapKeyOlderPayloadReadByNewerCodec() {
    MapEncoder<Map<String, Map<DefaultsV1, String>>> writer =
        Encoders.buildMapCodec(new TypeRef<Map<String, Map<DefaultsV1, String>>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    MapEncoder<Map<String, Map<DefaultsV2, String>>> reader =
        Encoders.buildMapCodec(new TypeRef<Map<String, Map<DefaultsV2, String>>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    DefaultsV1 k = new DefaultsV1();
    k.setName("k");
    Map<DefaultsV1, String> inner = new HashMap<>();
    inner.put(k, "v");
    Map<String, Map<DefaultsV1, String>> in = new HashMap<>();
    in.put("o", inner);
    Map<String, Map<DefaultsV2, String>> out = reader.decode(writer.encode(in));
    Map.Entry<DefaultsV2, String> e = out.get("o").entrySet().iterator().next();
    Assert.assertEquals(e.getKey().getName(), "k");
    Assert.assertEquals(e.getKey().getPrimitiveCount(), 0); // since=2 field absent in writer
    Assert.assertNull(e.getKey().getBoxedCount());
    Assert.assertEquals(e.getValue(), "v");
  }

  // A nested map whose KEY and VALUE are DIFFERENT versioned beans, reached through a top-level
  // array wrapper. Key is AlignV1/V2 (V2 adds a byte flag), value is DefaultsV1/V2 (V2 adds two
  // since=2 fields). The element schema's strict hash must cover both sides at their chosen
  // versions, just as the enclosing-bean hash does on the row-field path, so an older payload
  // restores both beans at their historical layout. Old field values are non-default so reading
  // either side at the wrong (current) layout would misread the bytes rather than coincide with a
  // default. The two beans evolve independently, so dispatch must distinguish their combination.
  @Test
  public void arrayOfMapBothSidesOlderPayloadReadByNewerCodec() {
    ArrayEncoder<List<Map<AlignV1, DefaultsV1>>> writer =
        Encoders.buildArrayCodec(new TypeRef<List<Map<AlignV1, DefaultsV1>>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    ArrayEncoder<List<Map<AlignV2, DefaultsV2>>> reader =
        Encoders.buildArrayCodec(new TypeRef<List<Map<AlignV2, DefaultsV2>>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    AlignV1 k = new AlignV1();
    k.setX(11);
    k.setY(22);
    DefaultsV1 v = new DefaultsV1();
    v.setName("val");
    Map<AlignV1, DefaultsV1> inner = new HashMap<>();
    inner.put(k, v);
    List<Map<AlignV2, DefaultsV2>> out = reader.decode(writer.encode(Arrays.asList(inner)));
    Assert.assertEquals(out.size(), 1);
    Map.Entry<AlignV2, DefaultsV2> e = out.get(0).entrySet().iterator().next();
    Assert.assertEquals(e.getKey().getX(), 11);
    Assert.assertEquals(e.getKey().getY(), 22);
    Assert.assertEquals(e.getKey().getFlag(), (byte) 0); // key since=2 field absent in writer
    Assert.assertEquals(e.getValue().getName(), "val");
    Assert.assertEquals(e.getValue().getPrimitiveCount(), 0); // value since=2 fields absent
    Assert.assertNull(e.getValue().getBoxedCount());
  }

  // ---------------------------------------------------------------------------
  // Removed nullable struct that was null on the wire: the v1 writer leaves
  // the slot's null bit set; the v2 reader skips the slot during projection.
  // ---------------------------------------------------------------------------

  @Data
  public static class NullableStructV1 {
    private String id;
    private DefaultsV1 detail; // nullable, removed at v2
    private long tail; // live in both versions, positioned after the removed slot
  }

  @Data
  @ForySchema(removedFields = NullableStructV2.History.class)
  public static class NullableStructV2 {
    private String id;
    private long tail;

    interface History {
      @ForyVersion(until = 2)
      DefaultsV1 detail();
    }
  }

  @Test
  public void removedNullableStructWasNullOnWire() {
    RowEncoder<NullableStructV1> writer = evolvingCodec(NullableStructV1.class);
    RowEncoder<NullableStructV2> reader = evolvingCodec(NullableStructV2.class);
    NullableStructV1 in = new NullableStructV1();
    in.setId("only-id");
    // detail intentionally left null
    in.setTail(42L);
    NullableStructV2 out = reader.decode(writer.encode(in));
    Assert.assertEquals(out.getId(), "only-id");
    // The field after the removed (null-on-wire) slot must read correctly, proving projection
    // skipped the slot rather than reading its null bit as part of a later field.
    Assert.assertEquals(out.getTail(), 42L);
  }

  // ---------------------------------------------------------------------------
  // Builder method ordering: compactEncoding() before vs after withSchemaEvolution()
  // must produce equivalent codecs.
  // ---------------------------------------------------------------------------

  @Test
  public void builderMethodOrderingIsCommutative() {
    RowEncoder<DefaultsV1> w =
        Encoders.buildBeanCodec(DefaultsV1.class)
            .compactEncoding()
            .withSchemaEvolution()
            .build()
            .get();
    RowEncoder<DefaultsV2> rOrderA =
        Encoders.buildBeanCodec(DefaultsV2.class)
            .compactEncoding()
            .withSchemaEvolution()
            .build()
            .get();
    RowEncoder<DefaultsV2> rOrderB =
        Encoders.buildBeanCodec(DefaultsV2.class)
            .withSchemaEvolution()
            .compactEncoding()
            .build()
            .get();
    DefaultsV1 in = new DefaultsV1();
    in.setName("commute");
    byte[] bytes = w.encode(in);
    Assert.assertEquals(rOrderA.decode(bytes).getName(), "commute");
    Assert.assertEquals(rOrderB.decode(bytes).getName(), "commute");
  }

  @Test
  public void nestedListSurvivesOuterProjection() {
    RowEncoder<NestedListV1> writer = evolvingCodec(NestedListV1.class);
    RowEncoder<NestedListV2> reader = evolvingCodec(NestedListV2.class);
    DefaultsV1 a = new DefaultsV1();
    a.setName("a");
    DefaultsV1 b = new DefaultsV1();
    b.setName("b");
    NestedListV1 in = new NestedListV1();
    in.setItems(Arrays.asList(a, b));
    NestedListV2 out = reader.decode(writer.encode(in));
    Assert.assertEquals(out.getItems().size(), 2);
    Assert.assertEquals(out.getItems().get(0).getName(), "a");
    Assert.assertEquals(out.getItems().get(1).getName(), "b");
    Assert.assertNull(out.getTag());
  }

  // ---------------------------------------------------------------------------
  // Nested versioned bean: a parent bean with a struct field whose own type is
  // versioned independently. The wire layout for the inner struct is inline in
  // the parent's bytes with no per-inner hash. The reader, dispatching on the
  // parent's strict hash, needs to choose an inner schema consistent with what
  // the writer used.
  // ---------------------------------------------------------------------------

  /** Stand-in for "older code that wrote the inner struct without field x". */
  @Data
  public static class NestedInnerWriter {
    private String name;
  }

  /** Stand-in for "older code that wrote the outer containing NestedInnerWriter". */
  @Data
  public static class NestedOuterWriter {
    private long id;
    private NestedInnerWriter inner;
  }

  /** Newer inner with an added field at v2. */
  @Data
  public static class NestedInnerV2 {
    private String name;

    @ForyVersion(since = 2)
    private String addedField;
  }

  /** Newer outer that still has just (id, inner) but its inner type evolved. */
  @Data
  public static class NestedOuterV2 {
    private long id;
    private NestedInnerV2 inner;
  }

  @Test
  public void nestedInnerEvolution_readerInnerNewerThanWriter() {
    // Writer uses the "older shape" inner. Both writer and reader are evolution-on so they
    // agree on strict-hash framing.
    RowEncoder<NestedOuterWriter> writer = evolvingCodec(NestedOuterWriter.class);
    RowEncoder<NestedOuterV2> reader = evolvingCodec(NestedOuterV2.class);

    NestedOuterWriter in = new NestedOuterWriter();
    in.setId(42);
    NestedInnerWriter inn = new NestedInnerWriter();
    inn.setName("hello");
    in.setInner(inn);

    byte[] bytes = writer.encode(in);
    NestedOuterV2 out = reader.decode(bytes);
    Assert.assertEquals(out.getId(), 42);
    Assert.assertNotNull(out.getInner());
    Assert.assertEquals(out.getInner().getName(), "hello");
    Assert.assertNull(out.getInner().getAddedField());
  }

  // ---------------------------------------------------------------------------
  // Outer + inner versioned independently. The cross-product enumeration must
  // generate a projection codec for each (outer-version, inner-version) pair
  // that isn't the current combination.
  // ---------------------------------------------------------------------------

  /** Outer with its own added field at v2; inner stays at v1. */
  @Data
  public static class CrossOuterV2_InnerV1 {
    private long id;
    private NestedInnerWriter inner;

    @ForyVersion(since = 2)
    private String label;
  }

  /** Outer v2 reader with inner evolved to v2. Both dimensions evolve independently. */
  @Data
  public static class CrossOuterV2_InnerV2 {
    private long id;
    private NestedInnerV2 inner;

    @ForyVersion(since = 2)
    private String label;
  }

  @Test
  public void crossOuterAndInnerEvolution() {
    // Writer writes outer V1 + inner V1 (no label, no addedField).
    RowEncoder<NestedOuterWriter> writer = evolvingCodec(NestedOuterWriter.class);
    RowEncoder<CrossOuterV2_InnerV2> reader = evolvingCodec(CrossOuterV2_InnerV2.class);

    NestedOuterWriter in = new NestedOuterWriter();
    in.setId(100);
    NestedInnerWriter inn = new NestedInnerWriter();
    inn.setName("legacy-inner");
    in.setInner(inn);

    byte[] bytes = writer.encode(in);
    CrossOuterV2_InnerV2 out = reader.decode(bytes);
    Assert.assertEquals(out.getId(), 100);
    Assert.assertEquals(out.getInner().getName(), "legacy-inner");
    Assert.assertNull(out.getInner().getAddedField());
    Assert.assertNull(out.getLabel());
  }

  /**
   * Contract: {@code SchemaHistory.current().nestedBeanSchemas()} must report each nested bean at
   * its current entry. Two cross-product combinations canonicalizing to the same signature is rare
   * today (the inner's own bySignature collapses wire-equal schemas before the outer sees them) but
   * the contract is documented and future callers may rely on it.
   */
  @Test
  public void schemaHistoryCurrentReflectsCurrentInnerVersions() {
    SchemaHistory history =
        SchemaHistory.build(CrossOuterV2_InnerV2.class, UnaryOperator.identity());
    SchemaHistory.VersionedSchema current = history.current();
    Assert.assertTrue(current.isCurrent(), "history.current() must be marked current");
    for (Map.Entry<Class<?>, SchemaHistory.VersionedSchema> e :
        current.nestedBeanSchemas().entrySet()) {
      SchemaHistory innerHistory = SchemaHistory.build(e.getKey(), UnaryOperator.identity());
      Assert.assertTrue(
          e.getValue().isCurrent(),
          "current().nestedBeanSchemas() must report inner " + e.getKey() + " at its current");
      Assert.assertEquals(
          e.getValue().version(),
          innerHistory.current().version(),
          "inner current version mismatch for " + e.getKey());
    }
  }

  // ---------------------------------------------------------------------------
  // Cross-product enumeration must route inner-bean versions through array and
  // map projection codecs, not just through the row codec. The reader's outer
  // type has N outer versions x M inner versions; multiple cross-product entries
  // share an outer version number, so the per-class suffix must encode the
  // inner version to keep them from colliding on the codegen cache.
  // ---------------------------------------------------------------------------

  @Test
  public void crossOuterAndInnerEvolution_array() {
    ArrayEncoder<List<NestedOuterWriter>> writer =
        Encoders.buildArrayCodec(new TypeRef<List<NestedOuterWriter>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    ArrayEncoder<List<CrossOuterV2_InnerV2>> reader =
        Encoders.buildArrayCodec(new TypeRef<List<CrossOuterV2_InnerV2>>() {})
            .withSchemaEvolution()
            .build()
            .get();

    List<NestedOuterWriter> in = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      NestedOuterWriter e = new NestedOuterWriter();
      e.setId(i);
      NestedInnerWriter inn = new NestedInnerWriter();
      inn.setName("legacy-" + i);
      e.setInner(inn);
      in.add(e);
    }

    List<CrossOuterV2_InnerV2> out = reader.decode(writer.encode(in));
    Assert.assertEquals(out.size(), 3);
    for (int i = 0; i < 3; i++) {
      Assert.assertEquals(out.get(i).getId(), i);
      Assert.assertEquals(out.get(i).getInner().getName(), "legacy-" + i);
      Assert.assertNull(out.get(i).getInner().getAddedField());
      Assert.assertNull(out.get(i).getLabel());
    }
  }

  @Test
  public void crossOuterAndInnerEvolution_map() {
    MapEncoder<Map<String, NestedOuterWriter>> writer =
        Encoders.buildMapCodec(new TypeRef<Map<String, NestedOuterWriter>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    MapEncoder<Map<String, CrossOuterV2_InnerV2>> reader =
        Encoders.buildMapCodec(new TypeRef<Map<String, CrossOuterV2_InnerV2>>() {})
            .withSchemaEvolution()
            .build()
            .get();

    LinkedHashMap<String, NestedOuterWriter> in = new LinkedHashMap<>();
    for (int i = 0; i < 3; i++) {
      NestedOuterWriter e = new NestedOuterWriter();
      e.setId(i);
      NestedInnerWriter inn = new NestedInnerWriter();
      inn.setName("legacy-" + i);
      e.setInner(inn);
      in.put("k" + i, e);
    }

    Map<String, CrossOuterV2_InnerV2> out = reader.decode(writer.encode(in));
    Assert.assertEquals(out.size(), 3);
    for (int i = 0; i < 3; i++) {
      CrossOuterV2_InnerV2 v = out.get("k" + i);
      Assert.assertNotNull(v, "missing key k" + i);
      Assert.assertEquals(v.getId(), i);
      Assert.assertEquals(v.getInner().getName(), "legacy-" + i);
      Assert.assertNull(v.getInner().getAddedField());
      Assert.assertNull(v.getLabel());
    }
  }

  // ---------------------------------------------------------------------------
  // Under evolution, array/map payloads carry an 8-byte schema-hash prefix. A
  // payload too small to hold that prefix is malformed and must fail loudly
  // rather than feed a negative size into pointTo.
  // ---------------------------------------------------------------------------

  @Test
  public void arrayPayloadBelowHashPrefixFailsLoudly() {
    ArrayEncoder<List<ChainV2>> codec =
        Encoders.buildArrayCodec(new TypeRef<List<ChainV2>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    Assert.expectThrows(ClassNotCompatibleException.class, () -> codec.decode(new byte[3]));
  }

  @Test
  public void mapPayloadBelowHashPrefixFailsLoudly() {
    MapEncoder<Map<String, DefaultsV1>> codec =
        Encoders.buildMapCodec(new TypeRef<Map<String, DefaultsV1>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    Assert.expectThrows(ClassNotCompatibleException.class, () -> codec.decode(new byte[3]));
  }

  // ---------------------------------------------------------------------------
  // A full-length payload whose strict schema hash matches neither the reader's
  // current schema nor any entry in its projection history must fail loudly.
  // This is the projection-map miss, distinct from the too-short-for-the-prefix
  // case above: the payload clears the 8-byte guard, so it can only be caught by
  // the unknown-hash branch. Encoding one bean and decoding it with an evolving
  // codec for a structurally different bean reaches that branch deterministically.
  // ---------------------------------------------------------------------------

  @Test
  public void rowUnknownSchemaHashFailsLoudly() {
    ChainV2 writer = new ChainV2();
    writer.setA(7);
    writer.setB("foreign");
    byte[] foreign = evolvingCodec(ChainV2.class).encode(writer);
    Assert.assertTrue(foreign.length > 8, "payload must clear the 8-byte hash prefix");
    RowEncoder<DefaultsV1> reader = evolvingCodec(DefaultsV1.class);
    Assert.expectThrows(ClassNotCompatibleException.class, () -> reader.decode(foreign));
  }

  @Test
  public void mapUnknownSchemaHashFailsLoudly() {
    MapEncoder<Map<String, ChainV2>> writer =
        Encoders.buildMapCodec(new TypeRef<Map<String, ChainV2>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    ChainV2 v = new ChainV2();
    v.setA(7);
    v.setB("foreign");
    LinkedHashMap<String, ChainV2> in = new LinkedHashMap<>();
    in.put("k", v);
    byte[] foreign = writer.encode(in);
    Assert.assertTrue(foreign.length > 8, "payload must clear the 8-byte hash prefix");
    MapEncoder<Map<String, DefaultsV1>> reader =
        Encoders.buildMapCodec(new TypeRef<Map<String, DefaultsV1>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    Assert.expectThrows(ClassNotCompatibleException.class, () -> reader.decode(foreign));
  }

  @Test
  public void arrayUnknownSchemaHashFailsLoudly() {
    ArrayEncoder<List<ChainV2>> writer =
        Encoders.buildArrayCodec(new TypeRef<List<ChainV2>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    ChainV2 v = new ChainV2();
    v.setA(7);
    v.setB("foreign");
    List<ChainV2> in = new ArrayList<>();
    in.add(v);
    byte[] foreign = writer.encode(in);
    Assert.assertTrue(foreign.length > 8, "payload must clear the 8-byte hash prefix");
    ArrayEncoder<List<DefaultsV1>> reader =
        Encoders.buildArrayCodec(new TypeRef<List<DefaultsV1>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    Assert.expectThrows(ClassNotCompatibleException.class, () -> reader.decode(foreign));
  }

  // ---------------------------------------------------------------------------
  // Three-level nesting: L1 -> L2 -> L3, each independently versioned. Because
  // L2's own history cross-products over L3's versions, L2's history holds two
  // entries that share a version number but differ in their L3 layout. Routing
  // must pick the L2 entry whose L3 matches the writer, not the first one with a
  // matching version number. Identifies the inner combination by strict hash, so
  // it resolves the correct subtree to arbitrary depth.
  // ---------------------------------------------------------------------------

  @Data
  public static class L3Writer {
    private String name;
  }

  @Data
  public static class L2Writer {
    private long tag;
    private L3Writer leaf;
  }

  @Data
  public static class L1Writer {
    private long id;
    private L2Writer mid;
  }

  @Data
  public static class L3V2 {
    private String name;

    @ForyVersion(since = 2)
    private String note;
  }

  @Data
  public static class L2V2 {
    private long tag;
    private L3V2 leaf;

    @ForyVersion(since = 2)
    private String midLabel;
  }

  @Data
  public static class L1V2 {
    private long id;
    private L2V2 mid;

    @ForyVersion(since = 2)
    private String outerLabel;
  }

  @Test
  public void threeLevelNestedEvolution() {
    RowEncoder<L1Writer> writer = evolvingCodec(L1Writer.class);
    RowEncoder<L1V2> reader = evolvingCodec(L1V2.class);

    L1Writer in = new L1Writer();
    in.setId(7);
    L2Writer mid = new L2Writer();
    mid.setTag(11);
    L3Writer leaf = new L3Writer();
    leaf.setName("deep");
    mid.setLeaf(leaf);
    in.setMid(mid);

    L1V2 out = reader.decode(writer.encode(in));
    Assert.assertEquals(out.getId(), 7);
    Assert.assertNull(out.getOuterLabel());
    Assert.assertEquals(out.getMid().getTag(), 11);
    Assert.assertNull(out.getMid().getMidLabel());
    Assert.assertEquals(out.getMid().getLeaf().getName(), "deep");
    Assert.assertNull(out.getMid().getLeaf().getNote());
  }

  // ---------------------------------------------------------------------------
  // The same versioned bean class in two fields. A writer writes one definition
  // of that class, so both fields are always at the same version on the wire;
  // the enumeration carries one version dimension per class, not per field, so a
  // class may back more than one slot.
  // ---------------------------------------------------------------------------

  @Data
  public static class TwoLeafWriter {
    private L3Writer first;
    private L3Writer second;
  }

  @Data
  public static class TwoLeafV2 {
    private L3V2 first;
    private L3V2 second;
  }

  @Test
  public void sameClassInTwoFields() {
    RowEncoder<TwoLeafWriter> writer = evolvingCodec(TwoLeafWriter.class);
    RowEncoder<TwoLeafV2> reader = evolvingCodec(TwoLeafV2.class);

    TwoLeafWriter in = new TwoLeafWriter();
    L3Writer a = new L3Writer();
    a.setName("alpha");
    L3Writer b = new L3Writer();
    b.setName("beta");
    in.setFirst(a);
    in.setSecond(b);

    TwoLeafV2 out = reader.decode(writer.encode(in));
    Assert.assertEquals(out.getFirst().getName(), "alpha");
    Assert.assertNull(out.getFirst().getNote());
    Assert.assertEquals(out.getSecond().getName(), "beta");
    Assert.assertNull(out.getSecond().getNote());
  }

  private static <T> RowEncoder<T> evolvingCodec(Class<T> beanClass) {
    return Encoders.buildBeanCodec(beanClass).withSchemaEvolution().build().get();
  }
}

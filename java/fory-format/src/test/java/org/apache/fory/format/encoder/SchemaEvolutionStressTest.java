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
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.apache.fory.exception.ClassNotCompatibleException;
import org.apache.fory.format.annotation.ForySchema;
import org.apache.fory.format.annotation.ForyVersion;
import org.apache.fory.reflect.TypeRef;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Stress tests for row-codec schema evolution. Each test probes a specific edge case; the names
 * say what is being stressed. Tests that surfaced real bugs are kept with a note pointing at the
 * fix; tests kept for coverage are short.
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
   * different historical schemas: v1, v2-3, and v4 (since 'a' is removed and a new field 'e'
   * shows up in v5; 'a' removal makes v5 differ from v4).
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
    RowEncoder<ChainV1> w1 =
        Encoders.buildBeanCodec(ChainV1.class).withSchemaEvolution().build().get();
    RowEncoder<ChainV2> w2 =
        Encoders.buildBeanCodec(ChainV2.class).withSchemaEvolution().build().get();
    RowEncoder<ChainV3> w3 =
        Encoders.buildBeanCodec(ChainV3.class).withSchemaEvolution().build().get();
    RowEncoder<ChainV4> w4 =
        Encoders.buildBeanCodec(ChainV4.class).withSchemaEvolution().build().get();
    RowEncoder<ChainV5> reader =
        Encoders.buildBeanCodec(ChainV5.class).withSchemaEvolution().build().get();

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
    RowEncoder<DefaultsV1> writer =
        Encoders.buildBeanCodec(DefaultsV1.class).withSchemaEvolution().build().get();
    RowEncoder<DefaultsV2> reader =
        Encoders.buildBeanCodec(DefaultsV2.class).withSchemaEvolution().build().get();
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
    RowEncoder<GappedWindow> codec =
        Encoders.buildBeanCodec(GappedWindow.class).withSchemaEvolution().build().get();
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

  @Test
  public void removedNestedStructField() {
    RowEncoder<StructRefV1> writer =
        Encoders.buildBeanCodec(StructRefV1.class).withSchemaEvolution().build().get();
    RowEncoder<StructRefV2> reader =
        Encoders.buildBeanCodec(StructRefV2.class).withSchemaEvolution().build().get();
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
    RowEncoder<CollectionsV1> writer =
        Encoders.buildBeanCodec(CollectionsV1.class).withSchemaEvolution().build().get();
    RowEncoder<CollectionsV2> reader =
        Encoders.buildBeanCodec(CollectionsV2.class).withSchemaEvolution().build().get();
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
    RowEncoder<RetypeV1> writer =
        Encoders.buildBeanCodec(RetypeV1.class).withSchemaEvolution().build().get();
    RowEncoder<RetypeV3> reader =
        Encoders.buildBeanCodec(RetypeV3.class).withSchemaEvolution().build().get();
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
    RowEncoder<WideV1> writer =
        Encoders.buildBeanCodec(WideV1.class).withSchemaEvolution().build().get();
    RowEncoder<WideV2> reader =
        Encoders.buildBeanCodec(WideV2.class).withSchemaEvolution().build().get();
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
    RowEncoder<DefaultsV1> writer =
        Encoders.buildBeanCodec(DefaultsV1.class).withSchemaEvolution().build().get();
    RowEncoder<DefaultsV2> r1 =
        Encoders.buildBeanCodec(DefaultsV2.class).withSchemaEvolution().build().get();
    RowEncoder<DefaultsV2> r2 =
        Encoders.buildBeanCodec(DefaultsV2.class).withSchemaEvolution().build().get();
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
    Encoders.buildBeanCodec(OverlapMisconfig.class).withSchemaEvolution().build().get();
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
  // Documented as wire-incompatible. Verify the failure mode is a clear
  // ClassNotCompatibleException, not silent garbage.
  // ---------------------------------------------------------------------------

  @Test
  public void evolutionFlagAsymmetryFailsLoud() {
    RowEncoder<DefaultsV1> withFlag =
        Encoders.buildBeanCodec(DefaultsV1.class).withSchemaEvolution().build().get();
    RowEncoder<DefaultsV1> noFlag = Encoders.buildBeanCodec(DefaultsV1.class).build().get();
    DefaultsV1 in = new DefaultsV1();
    in.setName("hi");
    byte[] withFlagBytes = withFlag.encode(in);
    try {
      noFlag.decode(withFlagBytes);
      Assert.fail("expected ClassNotCompatibleException");
    } catch (ClassNotCompatibleException expected) {
      // ok
    }
    byte[] noFlagBytes = noFlag.encode(in);
    try {
      withFlag.decode(noFlagBytes);
      Assert.fail("expected ClassNotCompatibleException");
    } catch (ClassNotCompatibleException expected) {
      // ok
    }
  }

  // ---------------------------------------------------------------------------
  // Map with a versioned bean as the KEY (rare; documented as not dispatched).
  // Verify the codec at least builds and the current-version round-trip works,
  // confirming the documented behavior doesn't crash.
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
    RowEncoder<NullableStructV1> writer =
        Encoders.buildBeanCodec(NullableStructV1.class).withSchemaEvolution().build().get();
    RowEncoder<NullableStructV2> reader =
        Encoders.buildBeanCodec(NullableStructV2.class).withSchemaEvolution().build().get();
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
    RowEncoder<NestedListV1> writer =
        Encoders.buildBeanCodec(NestedListV1.class).withSchemaEvolution().build().get();
    RowEncoder<NestedListV2> reader =
        Encoders.buildBeanCodec(NestedListV2.class).withSchemaEvolution().build().get();
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

  // TODO: nested versioned beans inside another versioned bean are not yet dispatched. The
  // strict hash naturally encodes inner-struct shape, but SchemaHistory.build does not
  // currently cross-product over nested-bean versions, so no projection codec is generated for
  // the older inner shape. Re-enable when implemented.
  @Test(enabled = false)
  public void nestedInnerEvolution_readerInnerNewerThanWriter() {
    // Writer uses the "older shape" inner. Both writer and reader are evolution-on so they
    // agree on strict-hash framing.
    RowEncoder<NestedOuterWriter> writer =
        Encoders.buildBeanCodec(NestedOuterWriter.class).withSchemaEvolution().build().get();
    RowEncoder<NestedOuterV2> reader =
        Encoders.buildBeanCodec(NestedOuterV2.class).withSchemaEvolution().build().get();

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
}


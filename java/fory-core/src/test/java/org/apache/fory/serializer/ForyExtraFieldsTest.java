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

package org.apache.fory.serializer;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.apache.fory.Fory;
import org.apache.fory.builder.Generated;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests for foryextrafields in compatible mode.
 *
 * <p>The two core scenarios are:
 *
 * <ol>
 *   <li>No sink (opt-out): unmatched remote fields are silently dropped — existing behavior,
 *       preserved unchanged.
 *   <li>Sink declared (opt-in): unmatched remote fields are captured into a {@link ForyExtraFields}
 *       field and can be replayed on re-serialization so a downstream peer with the full schema
 *       recovers them.
 * </ol>
 *
 * <p>The feature has two independent {@code write()} implementations — the interpreter's in {@link
 * CompatibleSerializer} and the generated one from {@code CompatibleCodecBuilder}. Every test runs
 * against both via the {@code config} data provider, which crosses {@code codegen} (false →
 * interpreter, true → generated) with {@code refTracking} (false and true) — four combinations per
 * test. The two ref-sharing tests, {@link #refSharingPreservedBetweenLocalAndExtraField} and {@link
 * #roundTripNestedReferenceSharing}, instead use the {@code configRefTracking} provider and run
 * only with ref-tracking enabled, since identity preservation is meaningless otherwise. {@link
 * #roundTripAfterJitCompilation} additionally covers the interpreter→JIT handoff under async
 * compilation.
 */
public class ForyExtraFieldsTest {

  // -------------------------------------------------------------------------
  // Model classes
  // -------------------------------------------------------------------------

  public static class UpstreamPrimitive {
    public int f9;

    public UpstreamPrimitive(int f9) {
      this.f9 = f9;
    }
  }

  public static class DownstreamPrimitiveWithSink {
    public ForyExtraFields extraFields;
  }

  public static class Upstream {
    public int f0, f1, f2, f3, f4, f5, f6, f7, f8;
    public int f9; // missing on DownstreamNoSink, captured by DownstreamWithSink

    public Upstream() {}

    public Upstream(int base) {
      f0 = base;
      f1 = base + 1;
      f2 = base + 2;
      f3 = base + 3;
      f4 = base + 4;
      f5 = base + 5;
      f6 = base + 6;
      f7 = base + 7;
      f8 = base + 8;
      f9 = base + 9;
    }
  }

  /** Partial-schema peer with NO sink: f9 is silently dropped on deserialization. */
  public static class DownstreamNoSink {
    public int f0, f1, f2, f3, f4, f5, f6, f7, f8;
  }

  /** Partial-schema peer WITH a sink: f9 is captured into {@code extraFields}. */
  public static class DownstreamWithSink {
    public int f0, f1, f2, f3, f4, f5, f6, f7, f8;
    public ForyExtraFields extraFields;
  }

  /**
   * Peer that has an object field in addition to primitives, for ref-tracking tests. Uses {@code
   * int[]} (a mutable heap object) so Fory ref-tracking preserves identity.
   */
  public static class UpstreamWithRef {
    public int[] shared;
    public int[] alias;
    public int value;

    public UpstreamWithRef() {}

    public UpstreamWithRef(int[] arr, int v) {
      this.shared = arr;
      this.alias = arr; // intentional alias — same array object
      this.value = v;
    }
  }

  public static class DownstreamRefSink {
    public int value;
    public ForyExtraFields extraFields;
  }

  public static class UpstreamMixed {
    public int age;
    public String name;
    public int score;

    public UpstreamMixed() {}

    public UpstreamMixed(int age, String name, int score) {
      this.age = age;
      this.name = name;
      this.score = score;
    }
  }

  public static class DownstreamMixedSink {
    public int age;
    public ForyExtraFields extraFields;
  }

  public static class DownstreamTwoHopSink {
    public int age;
    public String name;
    public ForyExtraFields extraFields;
  }

  public static class UpstreamScore {
    public int age;
    public int score;

    public UpstreamScore() {}

    public UpstreamScore(int age, int score) {
      this.age = age;
      this.score = score;
    }
  }

  public static class UpstreamLevel {
    public int age;
    public int level;

    public UpstreamLevel() {}

    public UpstreamLevel(int age, int level) {
      this.age = age;
      this.level = level;
    }
  }

  public static class DownstreamAgeSink {
    public int age;
    public ForyExtraFields extraFields;
  }

  public static class DownstreamMixedArray {
    public int age;
    public String name;
    public int score;

    public DownstreamMixedArray() {}

    public DownstreamMixedArray(int age, String name, int score) {
      this.age = age;
      this.name = name;
      this.score = score;
    }
  }

  public static class UpstreamList {
    public int id;
    public List<Integer> values;

    public UpstreamList() {}

    public UpstreamList(int id, List<Integer> values) {
      this.id = id;
      this.values = values;
    }
  }

  public static class DownstreamListWithSink {
    public int id;
    public ForyExtraFields extraFields;
  }

  public static class Address {
    public String city;
    public int zip;

    public Address() {}

    public Address(String city, int zip) {
      this.city = city;
      this.zip = zip;
    }
  }

  public static class UpstreamNested {
    public int age;
    public Address address;

    public UpstreamNested() {}

    public UpstreamNested(int age, Address address) {
      this.age = age;
      this.address = address;
    }
  }

  public static class DownstreamNestedSink {
    public int age;

    public ForyExtraFields extraFields;
  }

  public static class UpstreamNestedRef {
    public Address home;
    public Address work;

    public UpstreamNestedRef() {}

    public UpstreamNestedRef(Address address) {
      this.home = address;
      this.work = address;
    }
  }

  public static class DownstreamNestedRefSink {
    public ForyExtraFields extraFields;
  }

  public static class DownstreamTotalSink {
    public ForyExtraFields extraFields;
  }

  public abstract static class ExtraFieldsBase {
    public ForyExtraFields extraFields;
  }

  public static class DownstreamInheritedSink extends ExtraFieldsBase {}

  @Test(dataProvider = "config")
  public void roundTripInheritedExtraFieldSink(boolean codegen, boolean refTracking) {
    Fory foryA = compatibleFory(codegen, refTracking);

    Address address = new Address("Manhattan", 66502);
    UpstreamNested original = new UpstreamNested(30, address);

    byte[] aToBBytes = foryA.serialize(original);

    Fory foryB = compatibleFory(codegen, refTracking);

    DownstreamInheritedSink intermediate =
        foryB.deserialize(aToBBytes, DownstreamInheritedSink.class);

    assertNotNull(intermediate.extraFields);

    Object captured = intermediate.extraFields.get("address");
    assertNotNull(captured);
    assertTrue(captured instanceof Address);

    Address capturedAddress = (Address) captured;
    assertEquals(capturedAddress.city, "Manhattan");
    assertEquals(capturedAddress.zip, 66502);

    byte[] bToCBytes = foryB.serialize(intermediate);

    Fory foryC = compatibleFory(codegen, refTracking);

    UpstreamNested recovered = foryC.deserialize(bToCBytes, UpstreamNested.class);

    assertEquals(recovered.age, original.age);
    assertNotNull(recovered.address);
    assertEquals(recovered.address.city, original.address.city);
    assertEquals(recovered.address.zip, original.address.zip);
  }

  @Test(dataProvider = "config")
  public void roundTripAllFieldsCapturedInExtraField(boolean codegen, boolean refTracking) {
    Fory foryA = compatibleFory(codegen, refTracking);

    Address address = new Address("Manhattan", 66502);
    UpstreamNested original = new UpstreamNested(30, address);

    byte[] aToBBytes = foryA.serialize(original);

    Fory foryB = compatibleFory(codegen, refTracking);

    DownstreamTotalSink intermediate = foryB.deserialize(aToBBytes, DownstreamTotalSink.class);

    assertNotNull(intermediate.extraFields);

    Object capturedAge = intermediate.extraFields.get("age");
    assertNotNull(capturedAge);
    assertEquals(capturedAge, 30);

    Object capturedAddress = intermediate.extraFields.get("address");
    assertNotNull(capturedAddress);
    assertTrue(capturedAddress instanceof Address);

    Address captured = (Address) capturedAddress;
    assertEquals(captured.city, "Manhattan");
    assertEquals(captured.zip, 66502);

    byte[] bToCBytes = foryB.serialize(intermediate);

    Fory foryC = compatibleFory(codegen, refTracking);

    UpstreamNested recovered = foryC.deserialize(bToCBytes, UpstreamNested.class);

    assertEquals(recovered.age, original.age);
    assertNotNull(recovered.address);
    assertEquals(recovered.address.city, original.address.city);
    assertEquals(recovered.address.zip, original.address.zip);
  }

  @Test(dataProvider = "config")
  public void roundTripNestedExtraField(boolean codegen, boolean refTracking) {
    Fory foryA = compatibleFory(codegen, refTracking);

    Address address = new Address("Manhattan", 66502);
    UpstreamNested original = new UpstreamNested(30, address);

    byte[] aToBBytes = foryA.serialize(original);

    Fory foryB = compatibleFory(codegen, refTracking);

    DownstreamNestedSink intermediate = foryB.deserialize(aToBBytes, DownstreamNestedSink.class);

    assertEquals(intermediate.age, original.age);
    assertNotNull(intermediate.extraFields);

    Object captured = intermediate.extraFields.get("address");
    assertNotNull(captured);
    assertTrue(captured instanceof Address);

    Address capturedAddress = (Address) captured;
    assertEquals(capturedAddress.city, "Manhattan");
    assertEquals(capturedAddress.zip, 66502);

    byte[] bToCBytes = foryB.serialize(intermediate);

    Fory foryC = compatibleFory(codegen, refTracking);

    UpstreamNested recovered = foryC.deserialize(bToCBytes, UpstreamNested.class);

    assertEquals(recovered.age, original.age);
    assertNotNull(recovered.address);
    assertEquals(recovered.address.city, original.address.city);
    assertEquals(recovered.address.zip, original.address.zip);
  }

  @Test(dataProvider = "configRefTracking")
  public void roundTripNestedReferenceSharing(boolean codegen, boolean refTracking) {
    Fory foryA = compatibleFory(codegen, refTracking);

    Address address = new Address("Manhattan", 66502);
    UpstreamNestedRef original = new UpstreamNestedRef(address);

    byte[] aToBBytes = foryA.serialize(original);

    Fory foryB = compatibleFory(codegen, refTracking);

    DownstreamNestedRefSink intermediate =
        foryB.deserialize(aToBBytes, DownstreamNestedRefSink.class);

    assertNotNull(intermediate.extraFields);

    Object capturedHome = intermediate.extraFields.get("home");
    Object capturedWork = intermediate.extraFields.get("work");

    assertNotNull(capturedHome);
    assertNotNull(capturedWork);
    assertSame(capturedHome, capturedWork);

    byte[] bToCBytes = foryB.serialize(intermediate);

    Fory foryC = compatibleFory(codegen, refTracking);

    UpstreamNestedRef recovered = foryC.deserialize(bToCBytes, UpstreamNestedRef.class);

    assertNotNull(recovered.home);
    assertNotNull(recovered.work);
    assertSame(recovered.home, recovered.work);

    assertEquals(recovered.home.city, address.city);
    assertEquals(recovered.home.zip, address.zip);
  }

  /**
   * @param codegen {@code false} → interpreter ({@link CompatibleSerializer}); {@code true} →
   *     generated serializer ({@code CompatibleCodecBuilder}).
   * @param refTracking whether reference tracking is enabled on the built {@link Fory}.
   */
  private static Fory compatibleFory(boolean codegen, boolean refTracking) {
    return Fory.builder()
        .withXlang(false)
        .withRefTracking(refTracking)
        .withCompatible(true)
        .requireClassRegistration(false)
        .withCodegen(codegen)
        .build();
  }

  /**
   * Crosses {@code codegen} (interpreter vs generated write path) with {@code refTracking} (off vs
   * on) — four combinations, so every test exercises both write paths under both ref modes.
   */
  @DataProvider(name = "config")
  public static Object[][] config() {
    return new Object[][] {{false, false}, {false, true}, {true, false}, {true, true}};
  }

  /**
   * Like {@link #config} but pins {@code refTracking} on — for the ref-sharing tests, whose
   * identity assertions are meaningless without reference tracking.
   */
  @DataProvider(name = "configRefTracking")
  public static Object[][] configRefTracking() {
    return new Object[][] {{false, true}, {true, true}};
  }

  // -------------------------------------------------------------------------
  // No-sink: unmatched fields silently dropped
  // -------------------------------------------------------------------------

  @Test(dataProvider = "config")
  public void deserializeIgnoresMissingFields(boolean codegen, boolean refTracking) {
    Fory writer = compatibleFory(codegen, refTracking);
    Upstream upstream = new Upstream(100);
    byte[] bytes = writer.serialize(upstream);

    Fory reader = compatibleFory(codegen, refTracking);
    DownstreamNoSink result = reader.deserialize(bytes, DownstreamNoSink.class);

    // f0..f8 preserved; f9 is silently dropped (no sink declared)
    assertEquals(result.f0, upstream.f0);
    assertEquals(result.f1, upstream.f1);
    assertEquals(result.f2, upstream.f2);
    assertEquals(result.f3, upstream.f3);
    assertEquals(result.f4, upstream.f4);
    assertEquals(result.f5, upstream.f5);
    assertEquals(result.f6, upstream.f6);
    assertEquals(result.f7, upstream.f7);
    assertEquals(result.f8, upstream.f8);
  }

  /**
   * When the serializer handles primitive fields, it correctly converts them into their boxed
   * object equivalents when storing them as objects.
   */
  @Test(dataProvider = "config")
  public void capturesPrimitiveAndBoxesCorrectly(boolean codegen, boolean refTracking) {
    Fory writer = compatibleFory(codegen, refTracking);
    UpstreamPrimitive upstream = new UpstreamPrimitive(100);
    byte[] bytes = writer.serialize(upstream);

    Fory reader = compatibleFory(codegen, refTracking);
    DownstreamPrimitiveWithSink result =
        reader.deserialize(bytes, DownstreamPrimitiveWithSink.class);

    assertNotNull(result.extraFields);

    Object value = result.extraFields.get("f9");

    // primitive int stored inside Object becomes Integer
    assertTrue(value instanceof Integer);
    assertEquals(100, value);
  }

  @Test(dataProvider = "config")
  public void sinkIsNullWhenNoFieldsMissing(boolean codegen, boolean refTracking) {
    Fory fory = compatibleFory(codegen, refTracking);
    DownstreamWithSink obj = new DownstreamWithSink();
    obj.f0 = 7;
    obj.f3 = 42;
    byte[] bytes = fory.serialize(obj);
    DownstreamWithSink result = fory.deserialize(bytes, DownstreamWithSink.class);
    assertNull(result.extraFields);
  }

  // -------------------------------------------------------------------------
  // Round-trip test: A (full) → B (partial + sink) → C (full) recovers f9
  // -------------------------------------------------------------------------

  @Test(dataProvider = "config")
  public void roundTripRecoversMissingField(boolean codegen, boolean refTracking) {
    // --- A: full-schema writer ---
    Fory foryA = compatibleFory(codegen, refTracking);
    Upstream original = new Upstream(200);
    byte[] aToBBytes = foryA.serialize(original);

    // --- B: partial-schema peer with sink ---
    Fory foryB = compatibleFory(codegen, refTracking);
    DownstreamWithSink intermediate = foryB.deserialize(aToBBytes, DownstreamWithSink.class);

    // B captured f9
    assertNotNull(intermediate.extraFields);
    assertEquals(intermediate.extraFields.get("f9"), original.f9);

    // B re-serializes — must emit the remote (full) TypeDef + all 10 fields
    byte[] bToCBytes = foryB.serialize(intermediate);

    // --- C: full-schema receiver recovers all 10 fields ---
    Fory foryC = compatibleFory(codegen, refTracking);
    Upstream recovered = foryC.deserialize(bToCBytes, Upstream.class);

    assertEquals(recovered.f0, original.f0);
    assertEquals(recovered.f1, original.f1);
    assertEquals(recovered.f2, original.f2);
    assertEquals(recovered.f3, original.f3);
    assertEquals(recovered.f4, original.f4);
    assertEquals(recovered.f5, original.f5);
    assertEquals(recovered.f6, original.f6);
    assertEquals(recovered.f7, original.f7);
    assertEquals(recovered.f8, original.f8);
    assertEquals(recovered.f9, original.f9);
  }

  // -------------------------------------------------------------------------
  // Ref-sharing: aliased objects captured in sink preserve identity
  // -------------------------------------------------------------------------

  @Test(dataProvider = "configRefTracking")
  public void refSharingPreservedBetweenLocalAndExtraField(boolean codegen, boolean refTracking) {
    Fory writer = compatibleFory(codegen, refTracking);
    int[] arr = {1, 2, 3};
    UpstreamWithRef original = new UpstreamWithRef(arr, 99);
    // original.shared == original.alias (same array instance)
    byte[] bytes = writer.serialize(original);

    Fory reader = compatibleFory(codegen, refTracking);
    DownstreamRefSink result = reader.deserialize(bytes, DownstreamRefSink.class);

    assertEquals(result.value, original.value);
    assertNotNull(result.extraFields);

    Object capturedShared = result.extraFields.get("shared");
    Object capturedAlias = result.extraFields.get("alias");
    assertNotNull(capturedShared);
    assertSame(capturedShared, capturedAlias);
  }

  // -------------------------------------------------------------------------
  // multiple extra fields of mixed types
  // -------------------------------------------------------------------------

  @Test(dataProvider = "config")
  public void roundTripMultipleExtraFieldsMixedTypes(boolean codegen, boolean refTracking) {
    Fory foryA = compatibleFory(codegen, refTracking);
    UpstreamMixed original = new UpstreamMixed(30, "Alice", 95);
    byte[] aToBBytes = foryA.serialize(original);

    Fory foryB = compatibleFory(codegen, refTracking);
    DownstreamMixedSink intermediate = foryB.deserialize(aToBBytes, DownstreamMixedSink.class);
    assertEquals(intermediate.age, original.age);
    assertNotNull(intermediate.extraFields);
    assertEquals(intermediate.extraFields.get("name"), original.name);
    assertEquals(((Number) intermediate.extraFields.get("score")).intValue(), original.score);

    byte[] bToCBytes = foryB.serialize(intermediate);

    Fory foryC = compatibleFory(codegen, refTracking);
    UpstreamMixed recovered = foryC.deserialize(bToCBytes, UpstreamMixed.class);
    assertEquals(recovered.age, original.age);
    assertEquals(recovered.name, original.name);
    assertEquals(recovered.score, original.score);
  }

  // -------------------------------------------------------------------------
  // null extra field value survives round-trip
  // -------------------------------------------------------------------------

  @Test(dataProvider = "config")
  public void roundTripNullExtraField(boolean codegen, boolean refTracking) {
    // A null String field captured in the sink must be replayed as null, not omitted.
    Fory foryA = compatibleFory(codegen, refTracking);
    UpstreamMixed original = new UpstreamMixed(30, null, 95);
    byte[] aToBBytes = foryA.serialize(original);

    Fory foryB = compatibleFory(codegen, refTracking);
    DownstreamMixedSink intermediate = foryB.deserialize(aToBBytes, DownstreamMixedSink.class);
    assertEquals(intermediate.age, original.age);
    assertNotNull(intermediate.extraFields);
    assertTrue(intermediate.extraFields.containsKey("name"));
    assertNull(intermediate.extraFields.get("name"));

    byte[] bToCBytes = foryB.serialize(intermediate);

    Fory foryC = compatibleFory(codegen, refTracking);
    UpstreamMixed recovered = foryC.deserialize(bToCBytes, UpstreamMixed.class);
    assertEquals(recovered.age, original.age);
    assertNull(recovered.name);
    assertEquals(recovered.score, original.score);
  }

  // -------------------------------------------------------------------------
  // two-hop chain — each hop re-stamps the original TypeDef
  // -------------------------------------------------------------------------

  @Test(dataProvider = "config")
  public void twoHopChainRecoversAllFields(boolean codegen, boolean refTracking) {
    // A (full: age, name, score) → B (age only, sink) → C (age+name, sink) → D (full)
    // Each hop re-serializes using the remote (A) TypeDef so the next peer can decode.
    Fory foryA = compatibleFory(codegen, refTracking);

    UpstreamMixed original = new UpstreamMixed(25, "Bob", 88);
    byte[] aToBBytes = foryA.serialize(original);

    // B captures name and score
    Fory foryB = compatibleFory(codegen, refTracking);
    DownstreamMixedSink b = foryB.deserialize(aToBBytes, DownstreamMixedSink.class);
    assertEquals(b.age, original.age);
    assertNotNull(b.extraFields);

    byte[] bToCBytes = foryB.serialize(b);

    // C receives the full A-schema bytes, matches age and name, captures score
    Fory foryC = compatibleFory(codegen, refTracking);
    DownstreamTwoHopSink c = foryC.deserialize(bToCBytes, DownstreamTwoHopSink.class);
    assertEquals(c.age, original.age);
    assertEquals(c.name, original.name);
    assertNotNull(c.extraFields);
    assertEquals(((Number) c.extraFields.get("score")).intValue(), original.score);
    assertNull(c.extraFields.get("name")); // name is matched, not captured

    byte[] cToDBytes = foryC.serialize(c);

    // D recovers all three fields
    Fory foryD = compatibleFory(codegen, refTracking);
    UpstreamMixed recovered = foryD.deserialize(cToDBytes, UpstreamMixed.class);
    assertEquals(recovered.age, original.age);
    assertEquals(recovered.name, original.name);
    assertEquals(recovered.score, original.score);
  }

  // -------------------------------------------------------------------------
  // Two distinct remote schemas replayed through one Fory instance
  // -------------------------------------------------------------------------

  /**
   * Two {@link DownstreamAgeSink} instances whose sinks come from two *different* remote schemas
   * ({@link UpstreamScore} and {@link UpstreamLevel}) both pass through the same Fory instance.
   *
   * <p>Both objects share one local class, so replay serializers are keyed by (class, remote
   * TypeDef id); each object must be routed to the serializer of its own captured schema and emit
   * that schema's TypeDef header, so each downstream peer recovers its own extra field.
   */
  @Test(dataProvider = "config")
  public void twoDistinctRemoteSchemasReplayed(boolean codegen, boolean refTracking) {
    Fory foryScore = compatibleFory(codegen, refTracking);
    UpstreamScore score = new UpstreamScore(30, 95);
    byte[] scoreBytes = foryScore.serialize(score);

    Fory foryLevel = compatibleFory(codegen, refTracking);
    UpstreamLevel level = new UpstreamLevel(25, 5);
    byte[] levelBytes = foryLevel.serialize(level);

    // Same Fory instance deserializes both — both become DownstreamAgeSink but carry
    // different remote TypeDefs in their sinks.
    Fory foryB = compatibleFory(codegen, refTracking);
    DownstreamAgeSink fromScore = foryB.deserialize(scoreBytes, DownstreamAgeSink.class);
    DownstreamAgeSink fromLevel = foryB.deserialize(levelBytes, DownstreamAgeSink.class);

    assertEquals(fromScore.age, 30);
    assertEquals(fromLevel.age, 25);
    assertNotNull(fromScore.extraFields);
    assertNotNull(fromLevel.extraFields);

    assertNotNull(fromScore.extraFields.getTypeDef());
    assertNotNull(fromLevel.extraFields.getTypeDef());
    assertTrue(
        fromScore.extraFields.getTypeDef().getId() != fromLevel.extraFields.getTypeDef().getId(),
        "sinkTypeDefId must differ between the two remote schemas");

    // each must emit its own remote TypeDef header
    byte[] replayedScore = foryB.serialize(fromScore);
    byte[] replayedLevel = foryB.serialize(fromLevel);

    Fory foryC = compatibleFory(codegen, refTracking);
    UpstreamScore recoveredScore = foryC.deserialize(replayedScore, UpstreamScore.class);
    assertEquals(recoveredScore.age, 30);
    assertEquals(recoveredScore.score, 95);

    Fory foryD = compatibleFory(codegen, refTracking);
    UpstreamLevel recoveredLevel = foryD.deserialize(replayedLevel, UpstreamLevel.class);
    assertEquals(recoveredLevel.age, 25);
    assertEquals(recoveredLevel.level, 5);
  }

  // -------------------------------------------------------------------------
  // writeReplace indirection — exercises the bare WriteContext.writeNonRef(Object) overload
  // -------------------------------------------------------------------------

  /**
   * A class with a {@code writeReplace} method is handled by {@link ReplaceResolveSerializer}. When
   * the replacement object's runtime type differs from the wrapper's declared type, {@code
   * ReplaceResolveSerializer.write} routes the replacement through {@code
   * WriteContext.writeNonRef(Object)} directly
   */
  public static class ReplaceWrapper implements java.io.Serializable {
    public DownstreamWithSink replacement;

    public ReplaceWrapper(DownstreamWithSink replacement) {
      this.replacement = replacement;
    }

    private Object writeReplace() {
      return replacement;
    }
  }

  @Test(dataProvider = "config")
  public void writeNonRefReplaysExtraFields(boolean codegen, boolean refTracking) {
    Fory foryA = compatibleFory(codegen, refTracking);
    Upstream original = new Upstream(300);
    byte[] aToBBytes = foryA.serialize(original);

    Fory foryB = compatibleFory(codegen, refTracking);
    DownstreamWithSink intermediate = foryB.deserialize(aToBBytes, DownstreamWithSink.class);
    assertNotNull(intermediate.extraFields);
    assertEquals(intermediate.extraFields.get("f9"), original.f9);

    // wrapper.writeReplace() substitutes `intermediate` (a different runtime type), forcing
    // ReplaceResolveSerializer down the REPLACED_NEW_TYPE branch, which calls
    // WriteContext.writeNonRef(Object) directly on `intermediate`.
    ReplaceWrapper wrapper = new ReplaceWrapper(intermediate);
    byte[] wrapperBytes = foryB.serialize(wrapper);

    Fory foryC = compatibleFory(codegen, refTracking);
    Object recovered = foryC.deserialize(wrapperBytes);

    assertTrue(recovered instanceof Upstream);
    Upstream recoveredUpstream = (Upstream) recovered;
    assertEquals(recoveredUpstream.f0, original.f0);
    assertEquals(recoveredUpstream.f1, original.f1);
    assertEquals(recoveredUpstream.f2, original.f2);
    assertEquals(recoveredUpstream.f3, original.f3);
    assertEquals(recoveredUpstream.f4, original.f4);
    assertEquals(recoveredUpstream.f5, original.f5);
    assertEquals(recoveredUpstream.f6, original.f6);
    assertEquals(recoveredUpstream.f7, original.f7);
    assertEquals(recoveredUpstream.f8, original.f8);
    assertEquals(recoveredUpstream.f9, original.f9);
  }

  // -------------------------------------------------------------------------
  // Arrays and collections
  // -------------------------------------------------------------------------

  @Test(dataProvider = "config")
  public void roundTripUnmatchedCollectionField(boolean codegen, boolean refTracking) {
    Fory writer = compatibleFory(codegen, refTracking);
    UpstreamList original = new UpstreamList(1, Arrays.asList(10, 20, 30));
    byte[] writerBytes = writer.serialize(original);

    Fory reader = compatibleFory(codegen, refTracking);
    DownstreamListWithSink downstream =
        reader.deserialize(writerBytes, DownstreamListWithSink.class);

    assertEquals(downstream.id, 1);
    assertNotNull(downstream.extraFields);

    byte[] replayedBytes = reader.serialize(downstream);

    Fory verify = compatibleFory(codegen, refTracking);
    UpstreamList recovered = verify.deserialize(replayedBytes, UpstreamList.class);

    assertEquals(recovered.id, original.id);
    assertEquals(recovered.values, original.values);
  }

  // -------------------------------------------------------------------------
  // Nested object with its own sink — exercises writeNonRef path
  // -------------------------------------------------------------------------

  public static class UpstreamOuter {
    public int id;
    public NestedFull nested;

    public UpstreamOuter() {}

    public UpstreamOuter(int id, NestedFull nested) {
      this.id = id;
      this.nested = nested;
    }
  }

  public static class NestedFull {
    public int f0;
    public int f1;

    public NestedFull() {}

    public NestedFull(int f0, int f1) {
      this.f0 = f0;
      this.f1 = f1;
    }
  }

  /** Partial-schema outer with no sink; nested has a sink. */
  public static class DownstreamOuterNoSink {
    public int id;
    public NestedPartialSink nested;

    public DownstreamOuterNoSink() {}
  }

  /** Partial-schema nested with a sink — captures f1 from NestedFull. */
  public static class NestedPartialSink {
    public int f0;
    public ForyExtraFields extraFields;

    public NestedPartialSink() {}
  }

  public static class DownstreamNested {
    int id;

    ForyExtraFields extraFields;
  }

  // -------------------------------------------------------------------------
  // async compilation — interpreter bootstraps, JIT takes over
  // -------------------------------------------------------------------------

  /**
   * Exercises the interpreter→JIT handoff. Under async compilation the first serialize uses the
   * interpreter {@code write()}; once compilation completes the serializer is swapped for a {@link
   * Generated} one. We wait deterministically for that swap and assert the second serialize
   * genuinely ran through the generated path — then confirm both wire outputs replay the captured
   * field correctly.
   */
  @Test(timeOut = 30000)
  public void roundTripAfterJitCompilation() throws InterruptedException {
    Fory foryA = compatibleFory(false, true);
    UpstreamMixed original = new UpstreamMixed(40, "Carol", 77);
    byte[] aToBBytes = foryA.serialize(original);

    Fory foryB =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .withCompatible(true)
            .requireClassRegistration(false)
            .withAsyncCompilation(true) // interpreter first, generated serializer swaps in
            .build();

    DownstreamMixedSink intermediate = foryB.deserialize(aToBBytes, DownstreamMixedSink.class);
    assertNotNull(intermediate.extraFields);

    // First serialize triggers async JIT and runs through the interpreter write path.
    byte[] fromInterpreter = foryB.serialize(intermediate);

    // Wait for the generated serializer to be swapped in, then serialize via the compiled path.
    while (!(foryB.getTypeResolver().getSerializer(DownstreamMixedSink.class)
        instanceof Generated)) {
      Thread.sleep(10);
    }
    byte[] fromJit = foryB.serialize(intermediate);

    // Both wire outputs must recover all three fields on a full-schema peer.
    for (byte[] bytes : new byte[][] {fromInterpreter, fromJit}) {
      Fory foryC = compatibleFory(false, true);
      UpstreamMixed recovered = foryC.deserialize(bytes, UpstreamMixed.class);
      assertEquals(recovered.age, original.age);
      assertEquals(recovered.name, original.name);
      assertEquals(recovered.score, original.score);
    }
  }

  public static class OuterFull {
    public int id;
    public MiddleFull middle;

    public OuterFull() {}

    public OuterFull(int id, MiddleFull middle) {
      this.id = id;
      this.middle = middle;
    }
  }

  public static class MiddleFull {
    public int id;
    public InnerFull inner;

    public MiddleFull() {}

    public MiddleFull(int id, InnerFull inner) {
      this.id = id;
      this.inner = inner;
    }
  }

  public static class InnerFull {
    public int f0;
    public int f1;

    public InnerFull() {}

    public InnerFull(int f0, int f1) {
      this.f0 = f0;
      this.f1 = f1;
    }
  }

  public static class OuterPartial {
    public int id;
    public MiddlePartial middle;

    public OuterPartial() {}
  }

  public static class MiddlePartial {
    public int id;
    public InnerPartialSink inner;

    public MiddlePartial() {}
  }

  public static class InnerPartialSink {
    public int f0;
    public ForyExtraFields extraFields;

    public InnerPartialSink() {}
  }

  /**
   * A (full: OuterFull → MiddleFull → InnerFull) → B (partial: OuterPartial → MiddlePartial →
   * InnerPartialSink) → C (full: OuterFull → MiddleFull → InnerFull).
   *
   * <p>Each nested level is partially known by B. The innermost object captures the unknown f1
   * field in its sink. On re-serialization, the local schema should be written for each object,
   * while the sink preserves the remote fields. C must recover the full nested structure. passes
   * through writenonref(obj,typeinfo) path
   */
  @Test(dataProvider = "config")
  public void roundTripTripleNestedObjectWithInnerSink(boolean codegen, boolean refTracking) {
    Fory foryA = compatibleFory(codegen, refTracking);

    InnerFull inner = new InnerFull(10, 20);
    MiddleFull middle = new MiddleFull(2, inner);
    OuterFull original = new OuterFull(1, middle);

    byte[] aToBBytes = foryA.serialize(original);

    Fory foryB = compatibleFory(codegen, refTracking);
    OuterPartial intermediate = foryB.deserialize(aToBBytes, OuterPartial.class);

    assertEquals(intermediate.id, original.id);
    assertNotNull(intermediate.middle);
    assertEquals(intermediate.middle.id, original.middle.id);

    assertNotNull(intermediate.middle.inner);
    assertEquals(intermediate.middle.inner.f0, original.middle.inner.f0);

    assertNotNull(intermediate.middle.inner.extraFields);
    assertEquals(intermediate.middle.inner.extraFields.get("f1"), original.middle.inner.f1);

    byte[] bToCBytes = foryB.serialize(intermediate);

    Fory foryC = compatibleFory(codegen, refTracking);
    OuterFull recovered = foryC.deserialize(bToCBytes, OuterFull.class);

    // C's recovered object state:
    //
    // OuterFull
    //   id = 1
    //   middle =
    //     MiddleFull
    //       id = 2
    //       inner =
    //         InnerFull
    //           f0 = 10
    //           f1 = 20
    //
    assertEquals(recovered.id, original.id);

    assertNotNull(recovered.middle);
    assertEquals(recovered.middle.id, original.middle.id);

    assertNotNull(recovered.middle.inner);
    assertEquals(recovered.middle.inner.f0, original.middle.inner.f0);
    assertEquals(recovered.middle.inner.f1, original.middle.inner.f1);
  }

  public static class UpstreamOuterTwoNested {
    public int id;
    public NestedFull left;
    public NestedFull right;

    public UpstreamOuterTwoNested() {}

    public UpstreamOuterTwoNested(int id, NestedFull left, NestedFull right) {
      this.id = id;
      this.left = left;
      this.right = right;
    }
  }

  public static class DownstreamOuterTwoNested {
    public int id;
    public NestedPartialSink left;
    public NestedPartialSink right;

    public DownstreamOuterTwoNested() {}
  }

  /**
   * A (full: OuterWithTwoNestedFulls) → B (partial: OuterWithTwoNestedSinks) → C (full:
   * OuterWithTwoNestedFulls).
   *
   * <p>Two sibling nested objects have independent sinks. The serializer must preserve the sink
   * belonging to each object and must not reuse the TypeDef/schema from the first nested object
   * when writing the second one.
   */
  // this test goes through the writenonref(obj,cached typedinfoholder) path
  @Test(dataProvider = "config")
  public void roundTripMultipleNestedObjectsWithOwnSinks(boolean codegen, boolean refTracking) {
    Fory foryA = compatibleFory(codegen, refTracking);

    NestedFull left = new NestedFull(10, 100);
    NestedFull right = new NestedFull(20, 200);
    UpstreamOuterTwoNested original = new UpstreamOuterTwoNested(1, left, right);

    byte[] aToBBytes = foryA.serialize(original);

    Fory foryB = compatibleFory(codegen, refTracking);
    DownstreamOuterTwoNested intermediate =
        foryB.deserialize(aToBBytes, DownstreamOuterTwoNested.class);

    assertEquals(intermediate.id, original.id);

    assertNotNull(intermediate.left);
    assertEquals(intermediate.left.f0, left.f0);
    assertNotNull(intermediate.left.extraFields);
    assertEquals(intermediate.left.extraFields.get("f1"), left.f1);

    assertNotNull(intermediate.right);
    assertEquals(intermediate.right.f0, right.f0);
    assertNotNull(intermediate.right.extraFields);
    assertEquals(intermediate.right.extraFields.get("f1"), right.f1);

    byte[] bToCBytes = foryB.serialize(intermediate);

    Fory foryC = compatibleFory(codegen, refTracking);
    UpstreamOuterTwoNested recovered = foryC.deserialize(bToCBytes, UpstreamOuterTwoNested.class);

    assertEquals(recovered.id, original.id);

    assertNotNull(recovered.left);
    assertEquals(recovered.left.f0, left.f0);
    assertEquals(recovered.left.f1, left.f1);

    assertNotNull(recovered.right);
    assertEquals(recovered.right.f0, right.f0);
    assertEquals(recovered.right.f1, right.f1);
  }
}

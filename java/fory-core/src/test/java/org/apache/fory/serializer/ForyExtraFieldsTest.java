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
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.apache.fory.Fory;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.builder.Generated;
import org.apache.fory.builder.ObjectCodecBuilder;
import org.apache.fory.collection.LongMap;
import org.apache.fory.meta.FieldInfo;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeResolver;
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

  public static class DownstreamPreInitSink {
    public int f0, f1, f2, f3, f4, f5, f6, f7, f8;
    public final ForyExtraFields extraFields = new ForyExtraFields();
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

  // -------------------------------------------------------------------------
  // Field hiding: a subclass legally hides the inherited ForyExtraFields sink
  // with a same-named field of a different type. The scanner returns the exact
  // inherited sink (declared on the base), so the generated codec must bind THAT
  // field, not re-resolve by beanClass + name (which would pick the subclass's
  // shadowing String field and write a ForyExtraFields reference into it).
  // -------------------------------------------------------------------------

  public static class HidingSinkBase {
    public ForyExtraFields extraFields;
  }

  /** Hides the inherited {@code extraFields} sink with a same-named, different-typed field. */
  public static class DownstreamHidingSink extends HidingSinkBase {
    public String extraFields;
  }

  @Test(dataProvider = "config")
  public void hiddenInheritedSinkCapturesIntoDeclaringClassField(
      boolean codegen, boolean refTracking) {
    // The upstream schema is fully unknown to DownstreamHidingSink, so every field is captured into
    // the inherited sink. A subclass field named `extraFields` (a String) legally hides the
    // inherited ForyExtraFields sink. The scanner returns the exact base sink, so capture must land
    // there; a beanClass + name lookup would instead bind the shadowing String field.
    Fory foryA = compatibleFory(codegen, refTracking);
    UpstreamMixed original = new UpstreamMixed(30, "Alice", 95);
    byte[] bytes = foryA.serialize(original);

    Fory foryB = compatibleFory(codegen, refTracking);
    DownstreamHidingSink result = foryB.deserialize(bytes, DownstreamHidingSink.class);

    // The shadowing subclass field must be left untouched...
    assertNull(result.extraFields);
    // ...and every captured field must land in the real inherited sink on the declaring class.
    ForyExtraFields sink = ((HidingSinkBase) result).extraFields;
    assertNotNull(sink);
    assertEquals(byName(sink, "age"), original.age);
    assertEquals(byName(sink, "name"), original.name);
    assertEquals(((Number) byName(sink, "score")).intValue(), original.score);
  }

  // -------------------------------------------------------------------------
  // Shadowed fields: a superclass and subclass may legally declare fields with
  // the SAME simple name. In the remote TypeDef these are two
  // distinct FieldInfo entries, distinguished by declaring class + field id. A
  // sink keyed by simple name collapses them: capture overwrites one value, and
  // replay writes both remote slots from the single survivor. The full identity
  // must be the sink key so each slot round-trips independently.
  // -------------------------------------------------------------------------

  public static class ShadowBase {
    public int x;

    public ShadowBase() {}
  }

  /** Legally hides {@code ShadowBase.x} with a same-named field of the same type. */
  public static class ShadowChild extends ShadowBase {
    public int x;

    public ShadowChild() {}

    public ShadowChild(int baseX, int childX) {
      super.x = baseX;
      this.x = childX;
    }
  }

  /** Partial peer with only a sink: both shadowed {@code x} fields are unmatched and captured. */
  public static class ShadowDownstreamSink {
    public ForyExtraFields extraFields;
  }

  @Test(dataProvider = "config")
  public void roundTripShadowedFieldsPreservedIndependently(boolean codegen, boolean refTracking) {
    // A: full schema, two distinct x fields (base=111, child=222)
    Fory foryA = compatibleFory(codegen, refTracking);
    ShadowChild original = new ShadowChild(111, 222);
    byte[] aToBBytes = foryA.serialize(original);

    // B: sink-only peer captures both remote x fields
    Fory foryB = compatibleFory(codegen, refTracking);
    ShadowDownstreamSink intermediate = foryB.deserialize(aToBBytes, ShadowDownstreamSink.class);
    assertNotNull(intermediate.extraFields);

    // B re-serializes under the remote schema; both slots must replay their own captured value.
    byte[] bToCBytes = foryB.serialize(intermediate);

    // C: full schema must recover BOTH x values, not the same value twice.
    Fory foryC = compatibleFory(codegen, refTracking);
    ShadowChild recovered = foryC.deserialize(bToCBytes, ShadowChild.class);
    assertEquals(((ShadowBase) recovered).x, 111, "ShadowBase.x must round-trip independently");
    assertEquals(recovered.x, 222, "ShadowChild.x must round-trip independently");
  }

  // -------------------------------------------------------------------------
  // Tagged / id-bearing fields: a field written by its fory field id (e.g.
  // @ForyField(id=N))
  // -------------------------------------------------------------------------

  public static class UpstreamTagged {
    @ForyField(id = 7)
    public int secret;

    public UpstreamTagged() {}

    public UpstreamTagged(int secret) {
      this.secret = secret;
    }
  }

  /** Sink-only peer: the id-bearing {@code secret} field is unmatched and captured by id. */
  public static class DownstreamTaggedSink {
    public ForyExtraFields extraFields;
  }

  @Test(dataProvider = "config")
  public void taggedFieldCapturedAndLookedUpById(boolean codegen, boolean refTracking) {
    Fory foryA = compatibleFory(codegen, refTracking);
    UpstreamTagged original = new UpstreamTagged(123);
    byte[] aToBBytes = foryA.serialize(original);

    Fory foryB = compatibleFory(codegen, refTracking);
    DownstreamTaggedSink intermediate = foryB.deserialize(aToBBytes, DownstreamTaggedSink.class);
    assertNotNull(intermediate.extraFields);

    // Keyed by the field id, not by a name: the field carried no name on the wire.
    ForyExtraFields.FieldIdentity byId = ForyExtraFields.FieldIdentity.ofId(7);
    assertTrue(intermediate.extraFields.containsKey(byId));
    assertEquals(intermediate.extraFields.get(byId), 123);
    assertFalse(intermediate.extraFields.containsKey(identity(UpstreamTagged.class, "secret")));
    assertFalse(intermediate.extraFields.containsKey(identity(UpstreamTagged.class, "$tag7")));

    // Round-trips back to a full-schema peer under the remote (id-bearing) schema.
    byte[] bToCBytes = foryB.serialize(intermediate);
    Fory foryC = compatibleFory(codegen, refTracking);
    UpstreamTagged recovered = foryC.deserialize(bToCBytes, UpstreamTagged.class);
    assertEquals(recovered.secret, original.secret);
  }

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

    Object captured = byName(intermediate.extraFields, "address");
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

    Object capturedAge = byName(intermediate.extraFields, "age");
    assertNotNull(capturedAge);
    assertEquals(capturedAge, 30);

    Object capturedAddress = byName(intermediate.extraFields, "address");
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

    Object captured = byName(intermediate.extraFields, "address");
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

    Object capturedHome = byName(intermediate.extraFields, "home");
    Object capturedWork = byName(intermediate.extraFields, "work");

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
  /** Builds a name-based field identity for sink lookups: {@code (declaringClass, name)}. */
  private static ForyExtraFields.FieldIdentity identity(Class<?> declaringClass, String name) {
    return ForyExtraFields.FieldIdentity.of(declaringClass.getName(), name);
  }

  /**
   * Resolves a captured value by remote field name through the public identity API. The declaring
   * class of a captured field is the deserialization target for root-class fields, so tests that
   * only care about the value look up by name instead of hardcoding that mapping. Returns {@code
   * null} when no such field was captured, mirroring the old name-based {@code get}.
   */
  private static Object byName(ForyExtraFields extra, String name) {
    for (ForyExtraFields.FieldIdentity id : extra.fieldIdentities()) {
      if (name.equals(id.getName())) {
        return extra.get(id);
      }
    }
    return null;
  }

  /** Whether a field with the given remote name was captured. */
  private static boolean hasName(ForyExtraFields extra, String name) {
    for (ForyExtraFields.FieldIdentity id : extra.fieldIdentities()) {
      if (name.equals(id.getName())) {
        return true;
      }
    }
    return false;
  }

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

    Object value = byName(result.extraFields, "f9");

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
    assertEquals(byName(intermediate.extraFields, "f9"), original.f9);

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

  /** Capture must bind the remote TypeDef to the existing instance too. */
  @Test(dataProvider = "config")
  public void roundTripRecoversFieldWithPreInitializedSink(boolean codegen, boolean refTracking) {
    // --- A: full-schema writer ---
    Fory foryA = compatibleFory(codegen, refTracking);
    Upstream original = new Upstream(300);
    byte[] aToBBytes = foryA.serialize(original);

    // --- B: partial-schema peer whose sink is pre-initialized by a field initializer ---
    Fory foryB = compatibleFory(codegen, refTracking);
    DownstreamPreInitSink intermediate = foryB.deserialize(aToBBytes, DownstreamPreInitSink.class);

    // The pre-initialized sink captured f9...
    assertNotNull(intermediate.extraFields);
    assertEquals(byName(intermediate.extraFields, "f9"), original.f9);
    // ...and the remote TypeDef must be bound to it even though it was never framework-allocated,
    // otherwise replay is silently skipped below.
    assertNotNull(
        ForyExtraFieldsSupport.getTypeDef(intermediate.extraFields),
        "remote TypeDef must be bound to a pre-initialized sink so replay can fire");

    // B re-serializes — replay must fire, emitting the remote (full) schema + f9.
    byte[] bToCBytes = foryB.serialize(intermediate);

    // --- C: full-schema receiver must recover f9 (dropped before the fix) ---
    Fory foryC = compatibleFory(codegen, refTracking);
    Upstream recovered = foryC.deserialize(bToCBytes, Upstream.class);
    assertEquals(recovered.f9, original.f9);
  }

  // -------------------------------------------------------------------------
  // Sink accessor must survive TypeInfo.copy() from numeric-id registration
  // -------------------------------------------------------------------------

  /**
   * Regression: {@code TypeInfo.copy(...)} (used by numeric class-id registration) must preserve
   * the resolved extra-fields sink accessor. If the serializer is materialized first — which
   * resolves and caches the accessor via {@code setSerializer(resolver, ...)} — and a numeric class
   * id is registered afterwards, the copied {@code TypeInfo} keeps the serializer but must not drop
   * the accessor. Otherwise {@code hasExtraFieldsSink()} becomes false on the cached TypeInfo and
   * the write path silently skips replay, losing the captured remote field.
   */
  @Test(dataProvider = "config")
  public void sinkAccessorSurvivesNumericIdRegistration(boolean codegen, boolean refTracking) {
    // --- A: full-schema writer ---
    Fory foryA = compatibleFory(codegen, refTracking);
    Upstream original = new Upstream(400);
    byte[] aToBBytes = foryA.serialize(original);

    // --- B: partial-schema peer with sink ---
    Fory foryB = compatibleFory(codegen, refTracking);
    // Materialize the serializer first: this resolves and caches the sink accessor on the TypeInfo.
    foryB.getTypeResolver().getSerializer(DownstreamWithSink.class);
    // Then register a numeric class id, which copies the TypeInfo. The copy must carry the
    // accessor.
    foryB.register(DownstreamWithSink.class, 500);

    DownstreamWithSink intermediate = foryB.deserialize(aToBBytes, DownstreamWithSink.class);
    assertNotNull(intermediate.extraFields);
    assertEquals(byName(intermediate.extraFields, "f9"), original.f9);

    // B re-serializes — replay must still fire, so the remote (full) schema + f9 are emitted.
    byte[] bToCBytes = foryB.serialize(intermediate);

    // --- C: full-schema receiver must recover f9 ---
    Fory foryC = compatibleFory(codegen, refTracking);
    Upstream recovered = foryC.deserialize(bToCBytes, Upstream.class);
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

    Object capturedShared = byName(result.extraFields, "shared");
    Object capturedAlias = byName(result.extraFields, "alias");
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
    assertEquals(byName(intermediate.extraFields, "name"), original.name);
    assertEquals(((Number) byName(intermediate.extraFields, "score")).intValue(), original.score);

    byte[] bToCBytes = foryB.serialize(intermediate);

    Fory foryC = compatibleFory(codegen, refTracking);
    UpstreamMixed recovered = foryC.deserialize(bToCBytes, UpstreamMixed.class);
    assertEquals(recovered.age, original.age);
    assertEquals(recovered.name, original.name);
    assertEquals(recovered.score, original.score);
  }

  // -------------------------------------------------------------------------
  // Converted field + extra field replayed together
  //
  // A compatible scalar conversion (remote int score -> local long score) yields
  // a descriptor with a fieldConverter but NO fieldAccessor / no local Field. It
  // is a MATCHED field, reached through the converter, not an unmatched/extra one.
  // The replay (write) path only runs when the sink is non-empty, so we pair the
  // converted score with an extra field to populate the sink.
  //
  // This test is
  // pinned to refTracking=true (via configRefTracking) rather than the full config
  // matrix: a BigDecimal-sourced converter field would be the natural third case here,
  // but BigDecimal is ref-tracked by default while its convertible local targets
  // (int/long/String) are primitive-family types that can never carry ref-tracking, so
  // that combination is schema-incompatible independent of this feature (see
  // FieldConverters#isRefTrackedScalarSchemaMismatch).
  // -------------------------------------------------------------------------

  /**
   * Remote/full schema. {@code score} (int) and {@code level} (boxed Integer) both become converter
   * fields downstream.
   */
  public static class UpstreamConverted {
    public int age;
    public int score; // remote int -> local long: becomes a converter field downstream
    public Integer level; // remote Integer -> local Long: non-batched converter field
    public int bonus; // unmatched downstream -> captured into the sink

    public UpstreamConverted() {}

    public UpstreamConverted(int age, int score, Integer level, int bonus) {
      this.age = age;
      this.score = score;
      this.level = level;
      this.bonus = bonus;
    }
  }

  /** Local peer: {@code long score} forces a scalar converter; {@code bonus} lands in the sink. */
  public static class DownstreamConvertedSink {
    public int age;
    public long score; // remote int -> local long: converter field (no local Field on the remote
    // descriptor)
    public Long level; // remote Integer -> local Long: non-batched converter field
    public ForyExtraFields extraFields;
  }

  @Test(dataProvider = "configRefTracking")
  public void roundTripConvertedFieldWithExtraField(boolean codegen, boolean refTracking) {
    // --- A: full schema, int score + an extra bonus field ---
    Fory foryA = compatibleFory(codegen, refTracking);
    UpstreamConverted original = new UpstreamConverted(30, 95, 12, 7);
    byte[] aToBBytes = foryA.serialize(original);

    // --- B: converts score int->long, captures bonus into the (now non-empty) sink ---
    Fory foryB = compatibleFory(codegen, refTracking);
    DownstreamConvertedSink intermediate =
        foryB.deserialize(aToBBytes, DownstreamConvertedSink.class);
    assertEquals(intermediate.age, original.age);
    assertEquals(intermediate.score, (long) original.score); // widened via converter
    assertEquals(intermediate.level, Long.valueOf(original.level)); // boxed converter field
    assertNotNull(intermediate.extraFields);
    assertEquals(((Number) byName(intermediate.extraFields, "bonus")).intValue(), original.bonus);
    // score/level are matched (converter) fields, but under Option A their untouched remote-typed
    // values are ALSO preserved in the sink (not just the locally converted value), so replay can
    // emit the exact original bytes rather than re-deriving them from the (possibly lossy) local
    // value.
    assertEquals(((Number) byName(intermediate.extraFields, "score")).intValue(), original.score);
    assertEquals(
        ((Number) byName(intermediate.extraFields, "level")).intValue(), (int) original.level);

    // --- B re-serializes: the converter fields must replay under the remote schema ---
    byte[] bToCBytes = foryB.serialize(intermediate);

    // --- C: full-schema peer must recover every field, including the converted ones ---
    Fory foryC = compatibleFory(codegen, refTracking);
    UpstreamConverted recovered = foryC.deserialize(bToCBytes, UpstreamConverted.class);
    assertEquals(recovered.age, original.age);
    assertEquals(recovered.score, original.score);
    assertEquals(recovered.level, original.level);
    assertEquals(recovered.bonus, original.bonus);
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
    assertTrue(hasName(intermediate.extraFields, "name"));
    assertNull(byName(intermediate.extraFields, "name"));

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
    assertEquals(((Number) byName(c.extraFields, "score")).intValue(), original.score);
    assertNull(byName(c.extraFields, "name")); // name is matched, not captured

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
    assertEquals(byName(intermediate.extraFields, "f9"), original.f9);

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
  // Generated-code gating: the replay branch is native compatible mode only,
  // so xlang/same-schema write codegen stays byte-for-byte as before. We assert
  // on the generated write code for a class whose non-final field is sink-bearing
  // (DownstreamOuterNoSink.nested -> NestedPartialSink), since the tryWriteExtraFieldsSchema
  // call is emitted by the field's writer, not by the sink class's own serializer.
  // -------------------------------------------------------------------------

  private static String writeCode(Class<?> cls, boolean compatible) {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withCompatible(compatible)
            .requireClassRegistration(false)
            .withCodegen(true)
            .build();
    return new ObjectCodecBuilder(cls, fory).genCode();
  }

  /**
   * Positive counterpart to {@link #extraFieldsBranchAbsentInSameSchema}: in native compatible mode
   * the generated writer for a sink-bearing field must still emit the replay branch. Guards against
   * the config gate over-reaching and silently disabling the feature.
   */
  @Test
  public void extraFieldsBranchPresentInCompatibleMode() {
    String code = writeCode(DownstreamOuterNoSink.class, true);
    assertTrue(code.contains("tryWriteExtraFieldsSchema"), code);
  }

  /**
   * The replay branch is native compatible mode only. Under same-schema mode the same class must
   * generate write code with no sink check and no replay call, so the hot path is unchanged.
   */
  @Test
  public void extraFieldsBranchAbsentInSameSchema() {
    String code = writeCode(DownstreamOuterNoSink.class, false);
    assertFalse(code.contains("tryWriteExtraFieldsSchema"), code);
    assertFalse(code.contains("hasExtraFieldsSink"), code);
  }

  // -------------------------------------------------------------------------
  // Sink is a framework field, not a data field: it must be excluded from the
  // owner's local schema. Otherwise a fresh (non-replay) sink-bearing object
  // exposes `extraFields` in its local TypeDef and writes the sink out as an
  // ordinary nested object instead of it being reserved for remote-schema replay.
  // -------------------------------------------------------------------------

  private static boolean localTypeDefContainsField(Class<?> cls, String fieldName) {
    Fory fory = compatibleFory(false, false);
    TypeDef typeDef = TypeDef.buildTypeDef(fory.getTypeResolver(), cls);
    for (FieldInfo fieldInfo : typeDef.getFieldsInfo()) {
      if (fieldName.equals(fieldInfo.getFieldName())) {
        return true;
      }
    }
    return false;
  }

  @Test
  public void sinkFieldAbsentFromLocalTypeDef() {
    assertTrue(
        localTypeDefContainsField(DownstreamWithSink.class, "f0"),
        "data field f0 should be in the local TypeDef");
    assertFalse(
        localTypeDefContainsField(DownstreamWithSink.class, "extraFields"),
        "sink field extraFields must be excluded from the local TypeDef");
  }

  @Test
  public void inheritedSinkFieldAbsentFromLocalTypeDef() {
    // The selected sink may be declared on a superclass; it must still be excluded.
    assertFalse(
        localTypeDefContainsField(DownstreamInheritedSink.class, "extraFields"),
        "inherited sink field extraFields must be excluded from the local TypeDef");
  }

  // -------------------------------------------------------------------------
  // async compilation — interpreter bootstraps, JIT takes over
  // -------------------------------------------------------------------------

  /**
   * Exercises the interpreter→JIT handoff on the replay path. The captured extra fields are
   * re-emitted through the serializer stored in {@code extraFieldsSerializers} (see {@link
   * org.apache.fory.context.WriteContext} replay), which is a cache distinct from the normal {@link
   * TypeInfo} serializer. Under async compilation the interpreter registers that entry first and
   * the generated serializer must supersede it once compilation completes. We poll and assert on
   * that replay owner directly — then confirm both wire outputs replay the captured field
   * correctly.
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

    TypeResolver resolverB = foryB.getTypeResolver();
    // The remote schema id the fields were captured under; the replay cache is keyed by it.
    long remoteTypeDefId = ForyExtraFieldsSupport.getTypeDef(intermediate.extraFields).getId();

    // First serialize triggers async JIT; the captured fields replay through the interpreter entry.
    byte[] fromInterpreter = foryB.serialize(intermediate);

    // Wait for the generated serializer to supersede the interpreter in the replay cache — the
    // actual owner used to re-emit the captured fields, then serialize via the compiled path.
    while (!(resolverB.getExtraFieldsWriteSerializer(DownstreamMixedSink.class, remoteTypeDefId)
        instanceof Generated)) {
      Thread.sleep(10);
    }
    assertTrue(
        resolverB.getExtraFieldsWriteSerializer(DownstreamMixedSink.class, remoteTypeDefId)
            instanceof Generated);
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

  @Test(dataProvider = "config")
  public void unknownStructHeaderDoesNotCorruptForwardedStream(boolean codegen, boolean refTracking)
      throws Exception {
    Fory foryA = compatibleFory(codegen, refTracking);
    UpstreamMixed original = new UpstreamMixed(40, "Carol", 77);
    byte[] aToB = foryA.serialize(original);

    Fory foryB =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(refTracking)
            .withCompatible(true)
            .requireClassRegistration(false)
            .withCodegen(codegen)
            .withDeserializeUnknownClass(true)
            .build();
    DownstreamMixedSink intermediate = foryB.deserialize(aToB, DownstreamMixedSink.class);
    assertNotNull(intermediate.extraFields);
    long sinkTypeDefId = ForyExtraFieldsSupport.getTypeDef(intermediate.extraFields).getId();

    // A class genuinely unknown to foryB, so foryB reads it as an UnknownStruct with a real
    // TypeInfo cached under its own TypeDef id
    String pkg = "org.apache.fory.serializer.extrafields.gen";
    String className = "TrulyUnknownPeer";
    Class<?> unknownClass =
        ClassUtils.loadClass(
            pkg,
            className,
            "package " + pkg + ";\npublic class " + className + " { public int x = 1; }");
    byte[] unknownBytes = foryA.serialize(unknownClass.getConstructor().newInstance());
    UnknownClass.UnknownStruct unknownStruct =
        (UnknownClass.UnknownStruct) foryB.deserialize(unknownBytes);

    TypeResolver resolverB = foryB.getTypeResolver();
    TypeInfo unknownHeaderTypeInfo =
        resolverB.getTypeInfoByTypeDefId(unknownStruct.typeDef.getId());
    assertNotNull(unknownHeaderTypeInfo);
    assertEquals(unknownHeaderTypeInfo.getType(), UnknownClass.UnknownStruct.class);
    assertEquals(unknownHeaderTypeInfo.getTypeId(), ClassResolver.NONEXISTENT_META_SHARED_ID);

    Object extRegistry = ReflectionUtils.getObjectFieldValue(resolverB, "extRegistry");
    @SuppressWarnings("unchecked")
    LongMap<TypeInfo> typeInfoByTypeDefId =
        (LongMap<TypeInfo>) ReflectionUtils.getObjectFieldValue(extRegistry, "typeInfoByTypeDefId");
    typeInfoByTypeDefId.put(sinkTypeDefId, unknownHeaderTypeInfo);

    byte[] bToC = foryB.serialize(intermediate);

    Fory foryC = compatibleFory(codegen, refTracking);
    UpstreamMixed recovered = foryC.deserialize(bToC, UpstreamMixed.class);
    assertEquals(recovered.age, 40);
    assertEquals(recovered.name, "Carol");
    assertEquals(recovered.score, 77);
  }

  /** Holds two polymorphic elements so both go through the replay-checked writeRef path. */
  public static class SinkContainer {
    public Object first;
    public Object second;

    public SinkContainer() {}

    public SinkContainer(Object first, Object second) {
      this.first = first;
      this.second = second;
    }
  }

  /**
   * Two remote *versions of the same class* (same fully-qualified name, different schemas) captured
   * into one sink class and forwarded together in a single root graph. Each carries its own remote
   * {@link TypeDef} id, but both share the one local sink class, so meta-share must key by the
   * checked TypeDef identity so each version emits its own schema header.
   */
  @Test(dataProvider = "config")
  public void twoRemoteVersionsOfSameClassForwardedInOneGraph(boolean codegen, boolean refTracking)
      throws Exception {
    String pkg = "org.apache.fory.serializer.extrafields.gen";
    String className = "EvolvingMember";
    Class<?> scoreVersion =
        ClassUtils.loadClass(
            pkg,
            className,
            "package "
                + pkg
                + ";\n"
                + "public class "
                + className
                + " {\n"
                + "  public int age;\n"
                + "  public int score;\n"
                + "  public "
                + className
                + "() {}\n"
                + "  public "
                + className
                + "(int age, int score) { this.age = age; this.score = score; }\n"
                + "}");
    Class<?> levelVersion =
        ClassUtils.loadClass(
            pkg,
            className,
            "package "
                + pkg
                + ";\n"
                + "public class "
                + className
                + " {\n"
                + "  public int age;\n"
                + "  public int level;\n"
                + "  public "
                + className
                + "() {}\n"
                + "  public "
                + className
                + "(int age, int level) { this.age = age; this.level = level; }\n"
                + "}");
    Class<?> sinkVersion =
        ClassUtils.loadClass(
            pkg,
            className,
            "package "
                + pkg
                + ";\n"
                + "import org.apache.fory.serializer.ForyExtraFields;\n"
                + "public class "
                + className
                + " {\n"
                + "  public int age;\n"
                + "  public ForyExtraFields extraFields;\n"
                + "}");

    Fory foryScore = compatibleFory(codegen, refTracking);
    byte[] scoreBytes =
        foryScore.serialize(scoreVersion.getConstructor(int.class, int.class).newInstance(30, 95));

    Fory foryLevel = compatibleFory(codegen, refTracking);
    byte[] levelBytes =
        foryLevel.serialize(levelVersion.getConstructor(int.class, int.class).newInstance(25, 5));

    Fory foryB =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(refTracking)
            .withCompatible(true)
            .requireClassRegistration(false)
            .withCodegen(codegen)
            .withClassLoader(sinkVersion.getClassLoader())
            .build();
    Object fromScore = foryB.deserialize(scoreBytes);
    Object fromLevel = foryB.deserialize(levelBytes);
    assertSame(fromScore.getClass(), sinkVersion);
    assertSame(fromLevel.getClass(), sinkVersion);

    ForyExtraFields scoreExtra =
        (ForyExtraFields) ReflectionUtils.getObjectFieldValue(fromScore, "extraFields");
    ForyExtraFields levelExtra =
        (ForyExtraFields) ReflectionUtils.getObjectFieldValue(fromLevel, "extraFields");
    assertNotNull(scoreExtra);
    assertNotNull(levelExtra);
    assertNotEquals(
        scoreExtra.getTypeDef().getId(),
        levelExtra.getTypeDef().getId(),
        "the two remote versions must have distinct TypeDef ids");

    // Both captured objects forwarded together in ONE root graph.
    byte[] forwarded = foryB.serialize(new SinkContainer(fromScore, fromLevel));

    SinkContainer recovered = (SinkContainer) foryB.deserialize(forwarded);
    ForyExtraFields recoveredScore =
        (ForyExtraFields) ReflectionUtils.getObjectFieldValue(recovered.first, "extraFields");
    ForyExtraFields recoveredLevel =
        (ForyExtraFields) ReflectionUtils.getObjectFieldValue(recovered.second, "extraFields");

    // Each element must round-trip under its OWN remote schema: no cross-wiring, no reader drift.
    assertEquals(byName(recoveredScore, "score"), 95);
    assertFalse(hasName(recoveredScore, "level"));
    assertEquals(byName(recoveredLevel, "level"), 5);
    assertFalse(hasName(recoveredLevel, "score"));
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
    assertEquals(byName(intermediate.middle.inner.extraFields, "f1"), original.middle.inner.f1);

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
    assertEquals(byName(intermediate.left.extraFields, "f1"), left.f1);

    assertNotNull(intermediate.right);
    assertEquals(intermediate.right.f0, right.f0);
    assertNotNull(intermediate.right.extraFields);
    assertEquals(byName(intermediate.right.extraFields, "f1"), right.f1);

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

  @Test
  public void noExtraFieldsBranchWhenFeatureInactive() {
    Fory fory = Fory.builder().withCompatible(false).withCodegen(true).build();
    String code = new ObjectCodecBuilder(Upstream.class, fory).genCode();
    assertTrue(!code.contains("tryWriteExtraFieldsSchema"));
    assertTrue(!code.contains("hasExtraFieldsSink"));
  }

  // -------------------------------------------------------------------------
  // Static ForyExtraFields fields must be ignored during sink discovery
  // -------------------------------------------------------------------------

  public static class StaticSinkOnly {
    public static final ForyExtraFields SHARED = new ForyExtraFields();
    public int f0, f1, f2, f3, f4, f5, f6, f7, f8;
  }

  public static class StaticAndInstanceSink {
    public static final ForyExtraFields SHARED = new ForyExtraFields();
    public int f0, f1, f2, f3, f4, f5, f6, f7, f8;
    public ForyExtraFields extraFields;
  }

  @Test
  public void staticSinkFieldIgnoredByDiscovery() {
    // A lone static field is not a sink.
    assertNull(ForyExtraFieldsSupport.findSinkAccessor(StaticSinkOnly.class));
    assertEquals(
        ForyExtraFieldsSupport.findSinkAccessor(StaticAndInstanceSink.class).getField().getName(),
        "extraFields");
  }
}

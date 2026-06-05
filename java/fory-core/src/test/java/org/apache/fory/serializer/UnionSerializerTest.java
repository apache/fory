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
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.type.Types;
import org.apache.fory.type.union.Union;
import org.apache.fory.type.union.Union2;
import org.apache.fory.type.union.Union3;
import org.apache.fory.type.union.Union4;
import org.apache.fory.type.union.Union5;
import org.apache.fory.type.union.Union6;
import org.apache.fory.type.union.UnknownCase;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests for {@link UnionSerializer}. Union serialization is only used for xlang mode. Union types
 * are serialized as part of struct fields, where the declared field type determines which Union
 * serializer is used.
 */
public class UnionSerializerTest extends ForyTestBase {

  @DataProvider(name = "compatibleMode")
  public static Object[][] compatibleModeProvider() {
    return new Object[][] {{true}, {false}};
  }

  private Fory createXlangFory(boolean compatible) {
    Fory fory =
        Fory.builder()
            .withXlang(true)
            .withCompatible(compatible)
            .requireClassRegistration(true)
            .build();
    fory.register(StructWithUnion.class);
    fory.register(StructWithUnion2.class);
    fory.register(StructWithUnion3.class);
    fory.register(StructWithUnion4.class);
    fory.register(StructWithUnion5.class);
    fory.register(StructWithUnion6.class);
    return fory;
  }

  @Test
  public void testUnknownCaseIdZeroPreserved() {
    Fory fory = createXlangFory(true);
    MemoryBuffer buffer = MemoryUtils.buffer(64);
    withWriteContext(
        fory,
        buffer,
        context -> UnionSerializer.writeUnknownValue(context, new UnknownCase(0, "zero")));
    assertEquals(buffer.readVarUInt32(), 0);
    UnknownCase unknown =
        withReadContext(fory, buffer, context -> UnionSerializer.readUnknownValue(context, 0));
    assertEquals(unknown.caseId(), 0);
    assertEquals(unknown.value(), "zero");
  }

  @Test
  public void testCopyUnknownValueCopiesPayload() {
    Fory fory =
        Fory.builder()
            .withXlang(true)
            .withRefTracking(true)
            .withRefCopy(true)
            .requireClassRegistration(true)
            .withCompatible(true)
            .build();
    ArrayList<String> payload = new ArrayList<>();
    payload.add("future");
    UnknownCase unknown = UnknownCase.ofRuntime(7, Types.LIST, payload);

    UnknownCase copied =
        withCopyContext(fory, context -> UnionSerializer.copyUnknownValue(context, unknown));

    assertNotSame(copied, unknown);
    assertEquals(copied.caseId(), unknown.caseId());
    assertEquals(copied.typeId(), unknown.typeId());
    assertEquals(copied.value(), payload);
    assertNotSame(copied.value(), payload);
  }

  @Test
  public void testGenericUnionWireCaseIds() {
    Fory fory = createXlangFory(true);
    UnionSerializer serializer = new UnionSerializer(fory.getTypeResolver(), Union2.class);

    Union text = writeReadUnion(fory, serializer, Union2.ofT1("hello"), 0);
    assertTrue(text instanceof Union2);
    assertEquals(text.getIndex(), 0);
    assertEquals(text.getValue(), "hello");

    Union number = writeReadUnion(fory, serializer, Union2.ofT2(100L), 1);
    assertTrue(number instanceof Union2);
    assertEquals(number.getIndex(), 1);
    assertEquals(number.getValue(), 100L);
  }

  @Test
  public void testCustomUnionWireCaseIds() {
    Fory fory = createXlangFory(true);
    UnionSerializer serializer = new UnionSerializer(fory.getTypeResolver(), SchemaUnion.class);
    Union union = writeReadUnion(fory, serializer, new SchemaUnion(0, "hello", Types.STRING), 0);
    assertTrue(union instanceof SchemaUnion);
    assertEquals(union.getIndex(), 0);
    assertEquals(union.getValue(), "hello");
  }

  @Test
  public void testRegisterUnionDottedName() {
    Fory fory =
        Fory.builder().withXlang(true).requireClassRegistration(true).withCompatible(true).build();
    UnionSerializer serializer = new UnionSerializer(fory.getTypeResolver(), SchemaUnion.class);
    fory.registerUnion(SchemaUnion.class, "demo.SchemaUnion", serializer);

    assertEquals(fory.getTypeResolver().getTypeInfo(SchemaUnion.class).decodeNamespace(), "demo");
    assertEquals(
        fory.getTypeResolver().getTypeInfo(SchemaUnion.class).decodeTypeName(), "SchemaUnion");

    UnionSerializer invalidSerializer = new UnionSerializer(fory.getTypeResolver(), Union2.class);
    org.testng.Assert.assertThrows(
        IllegalArgumentException.class,
        () -> fory.registerUnion(Union2.class, "demo", "Union.Two", invalidSerializer));
  }

  private static Union writeReadUnion(
      Fory fory, UnionSerializer serializer, Union value, int expectedCaseId) {
    MemoryBuffer buffer = MemoryUtils.buffer(64);
    writeSerializer(fory, serializer, buffer, value);
    assertEquals(buffer.readVarUInt32(), expectedCaseId);
    buffer.readerIndex(0);
    return readSerializer(fory, serializer, buffer);
  }

  // Test struct classes with Union fields
  public static class StructWithUnion {
    public Union union;

    public StructWithUnion() {}

    public StructWithUnion(Union union) {
      this.union = union;
    }
  }

  public static class SchemaUnion extends Union {
    public SchemaUnion(int caseId, Object value) {
      super(caseId, value);
    }

    public SchemaUnion(int caseId, Object value, int typeId) {
      super(caseId, value, typeId);
    }
  }

  public static class StructWithUnion2 {
    public Union2<String, Long> union;

    public StructWithUnion2() {}

    public StructWithUnion2(Union2<String, Long> union) {
      this.union = union;
    }
  }

  public static class StructWithUnion3 {
    public Union3<Integer, String, Double> union;

    public StructWithUnion3() {}

    public StructWithUnion3(Union3<Integer, String, Double> union) {
      this.union = union;
    }
  }

  public static class StructWithUnion4 {
    public Union4<Integer, String, Double, Boolean> union;

    public StructWithUnion4() {}

    public StructWithUnion4(Union4<Integer, String, Double, Boolean> union) {
      this.union = union;
    }
  }

  public static class StructWithUnion5 {
    public Union5<Integer, String, Double, Boolean, Long> union;

    public StructWithUnion5() {}

    public StructWithUnion5(Union5<Integer, String, Double, Boolean, Long> union) {
      this.union = union;
    }
  }

  public static class StructWithUnion6 {
    public Union6<Integer, String, Double, Boolean, Long, Float> union;

    public StructWithUnion6() {}

    public StructWithUnion6(Union6<Integer, String, Double, Boolean, Long, Float> union) {
      this.union = union;
    }
  }

  @Test(dataProvider = "compatibleMode")
  public void testUnionBasicTypes(boolean compatible) {
    Fory fory = createXlangFory(compatible);

    // Test with Integer value via struct
    StructWithUnion struct = new StructWithUnion(new Union(0, 42));
    byte[] bytes = fory.serialize(struct);
    StructWithUnion deserialized = (StructWithUnion) fory.deserialize(bytes);
    assertEquals(deserialized.union.getValue(), 42);
    assertEquals(deserialized.union.getIndex(), 0);

    // Test with String value
    struct = new StructWithUnion(new Union(1, "hello"));
    bytes = fory.serialize(struct);
    deserialized = (StructWithUnion) fory.deserialize(bytes);
    assertEquals(deserialized.union.getValue(), "hello");
    assertEquals(deserialized.union.getIndex(), 1);
  }

  @Test(dataProvider = "compatibleMode")
  public void testUnion2Serialization(boolean compatible) {
    Fory fory = createXlangFory(compatible);

    // Test with T1 (String) via struct field
    StructWithUnion2 struct = new StructWithUnion2(Union2.ofT1("hello"));
    byte[] bytes = fory.serialize(struct);
    StructWithUnion2 deserialized = (StructWithUnion2) fory.deserialize(bytes);
    assertEquals(deserialized.union.getValue(), "hello");
    assertEquals(deserialized.union.getIndex(), 0);
    assertTrue(deserialized.union.isT1());
    assertTrue(deserialized.union instanceof Union2);

    // Test with T2 (Long)
    struct = new StructWithUnion2(Union2.ofT2(100L));
    bytes = fory.serialize(struct);
    deserialized = (StructWithUnion2) fory.deserialize(bytes);
    assertEquals(deserialized.union.getValue(), 100L);
    assertEquals(deserialized.union.getIndex(), 1);
    assertTrue(deserialized.union.isT2());
  }

  @Test(dataProvider = "compatibleMode")
  public void testUnion3Serialization(boolean compatible) {
    Fory fory = createXlangFory(compatible);

    // Test with T1
    StructWithUnion3 struct = new StructWithUnion3(Union3.ofT1(42));
    byte[] bytes = fory.serialize(struct);
    StructWithUnion3 deserialized = (StructWithUnion3) fory.deserialize(bytes);
    assertEquals(deserialized.union.getValue(), 42);
    assertEquals(deserialized.union.getIndex(), 0);
    assertTrue(deserialized.union.isT1());
    assertTrue(deserialized.union instanceof Union3);

    // Test with T2
    struct = new StructWithUnion3(Union3.ofT2("test"));
    bytes = fory.serialize(struct);
    deserialized = (StructWithUnion3) fory.deserialize(bytes);
    assertEquals(deserialized.union.getValue(), "test");
    assertEquals(deserialized.union.getIndex(), 1);
    assertTrue(deserialized.union.isT2());

    // Test with T3
    struct = new StructWithUnion3(Union3.ofT3(3.14));
    bytes = fory.serialize(struct);
    deserialized = (StructWithUnion3) fory.deserialize(bytes);
    assertEquals((Double) deserialized.union.getValue(), 3.14, 0.0001);
    assertEquals(deserialized.union.getIndex(), 2);
    assertTrue(deserialized.union.isT3());
  }

  @Test(dataProvider = "compatibleMode")
  public void testUnion4Serialization(boolean compatible) {
    Fory fory = createXlangFory(compatible);

    // Test with T1
    StructWithUnion4 struct = new StructWithUnion4(Union4.ofT1(42));
    byte[] bytes = fory.serialize(struct);
    StructWithUnion4 deserialized = (StructWithUnion4) fory.deserialize(bytes);
    assertEquals(deserialized.union.getValue(), 42);
    assertEquals(deserialized.union.getIndex(), 0);
    assertTrue(deserialized.union.isT1());
    assertTrue(deserialized.union instanceof Union4);

    // Test with T4
    struct = new StructWithUnion4(Union4.ofT4(true));
    bytes = fory.serialize(struct);
    deserialized = (StructWithUnion4) fory.deserialize(bytes);
    assertEquals(deserialized.union.getValue(), true);
    assertEquals(deserialized.union.getIndex(), 3);
    assertTrue(deserialized.union.isT4());
  }

  @Test(dataProvider = "compatibleMode")
  public void testUnion5Serialization(boolean compatible) {
    Fory fory = createXlangFory(compatible);

    // Test with T1
    StructWithUnion5 struct = new StructWithUnion5(Union5.ofT1(42));
    byte[] bytes = fory.serialize(struct);
    StructWithUnion5 deserialized = (StructWithUnion5) fory.deserialize(bytes);
    assertEquals(deserialized.union.getValue(), 42);
    assertEquals(deserialized.union.getIndex(), 0);
    assertTrue(deserialized.union.isT1());
    assertTrue(deserialized.union instanceof Union5);

    // Test with T5
    struct = new StructWithUnion5(Union5.ofT5(999L));
    bytes = fory.serialize(struct);
    deserialized = (StructWithUnion5) fory.deserialize(bytes);
    assertEquals(deserialized.union.getValue(), 999L);
    assertEquals(deserialized.union.getIndex(), 4);
    assertTrue(deserialized.union.isT5());
  }

  @Test(dataProvider = "compatibleMode")
  public void testUnion6Serialization(boolean compatible) {
    Fory fory = createXlangFory(compatible);

    // Test with T1
    StructWithUnion6 struct = new StructWithUnion6(Union6.ofT1(42));
    byte[] bytes = fory.serialize(struct);
    StructWithUnion6 deserialized = (StructWithUnion6) fory.deserialize(bytes);
    assertEquals(deserialized.union.getValue(), 42);
    assertEquals(deserialized.union.getIndex(), 0);
    assertTrue(deserialized.union.isT1());
    assertTrue(deserialized.union instanceof Union6);

    // Test with T6
    struct = new StructWithUnion6(Union6.ofT6(1.5f));
    bytes = fory.serialize(struct);
    deserialized = (StructWithUnion6) fory.deserialize(bytes);
    assertEquals(deserialized.union.getValue(), 1.5f);
    assertEquals(deserialized.union.getIndex(), 5);
    assertTrue(deserialized.union.isT6());
  }

  @Test(dataProvider = "compatibleMode")
  public void testUnionWithCollections(boolean compatible) {
    Fory fory = createXlangFory(compatible);

    // Test with List
    List<Integer> list = new ArrayList<>();
    list.add(1);
    list.add(2);
    list.add(3);
    StructWithUnion struct = new StructWithUnion(new Union(0, list));
    byte[] bytes = fory.serialize(struct);
    StructWithUnion deserialized = (StructWithUnion) fory.deserialize(bytes);
    assertTrue(deserialized.union.getValue() instanceof List);
    assertEquals(deserialized.union.getValue(), list);

    // Test with Map
    Map<String, Integer> map = new HashMap<>();
    map.put("a", 1);
    map.put("b", 2);
    struct = new StructWithUnion(new Union(1, map));
    bytes = fory.serialize(struct);
    deserialized = (StructWithUnion) fory.deserialize(bytes);
    assertTrue(deserialized.union.getValue() instanceof Map);
    assertEquals(deserialized.union.getValue(), map);
  }

  @Test(dataProvider = "compatibleMode")
  public void testUnionWithNull(boolean compatible) {
    Fory fory = createXlangFory(compatible);

    Union union = new Union(0, null);
    assertFalse(union.hasValue());

    StructWithUnion struct = new StructWithUnion(union);
    byte[] bytes = fory.serialize(struct);
    StructWithUnion deserialized = (StructWithUnion) fory.deserialize(bytes);
    assertNotNull(deserialized.union);
    assertNull(deserialized.union.getValue());
    assertEquals(deserialized.union.getIndex(), 0);
  }

  @Test
  public void testUnion2TypeSafety() {
    Union2<String, Long> union = Union2.ofT1("hello");
    assertTrue(union.isT1());
    assertFalse(union.isT2());
    assertEquals(union.getT1(), "hello");
    assertEquals(union.getIndex(), 0);

    Union2<String, Long> union2 = Union2.ofT2(100L);
    assertFalse(union2.isT1());
    assertTrue(union2.isT2());
    assertEquals(union2.getT2(), Long.valueOf(100L));
    assertEquals(union2.getIndex(), 1);
  }

  @Test
  public void testUnion3TypeSafety() {
    Union3<Integer, String, Double> union = Union3.ofT2("test");
    assertFalse(union.isT1());
    assertTrue(union.isT2());
    assertFalse(union.isT3());
    assertEquals(union.getT2(), "test");
  }

  @Test
  public void testUnion4TypeSafety() {
    Union4<Integer, String, Double, Boolean> union = Union4.ofT3(3.14);
    assertFalse(union.isT1());
    assertFalse(union.isT2());
    assertTrue(union.isT3());
    assertFalse(union.isT4());
    assertEquals(union.getT3(), 3.14);
  }

  @Test
  public void testUnion5TypeSafety() {
    Union5<Integer, String, Double, Boolean, Long> union = Union5.ofT3(3.14);
    assertFalse(union.isT1());
    assertFalse(union.isT2());
    assertTrue(union.isT3());
    assertFalse(union.isT4());
    assertFalse(union.isT5());
    assertEquals(union.getT3(), 3.14);
  }

  @Test
  public void testUnion6TypeSafety() {
    Union6<Integer, String, Double, Boolean, Long, Float> union = Union6.ofT4(true);
    assertFalse(union.isT1());
    assertFalse(union.isT2());
    assertFalse(union.isT3());
    assertTrue(union.isT4());
    assertFalse(union.isT5());
    assertFalse(union.isT6());
    assertEquals(union.getT4(), true);
  }

  @Test
  public void testUnionEquality() {
    Union union1 = new Union(0, 42);
    Union union2 = new Union(0, 42);
    assertEquals(union1, union2);
    assertEquals(union1.hashCode(), union2.hashCode());

    Union2<String, Long> u2a = Union2.ofT1("hello");
    Union2<String, Long> u2b = Union2.ofT1("hello");
    assertEquals(u2a, u2b);
    assertEquals(u2a.hashCode(), u2b.hashCode());

    // Union2-6 extends Union, so they should be equal to Union with same index/value
    Union baseUnion = new Union(0, "hello");
    assertEquals(u2a, baseUnion);
    assertEquals(baseUnion, u2a);
  }

  @Test
  public void testUnionToString() {
    Union union = new Union(0, 42);
    String str = union.toString();
    assertTrue(str.contains("42"));
    assertTrue(str.contains("0"));

    Union2<String, Long> union2 = Union2.ofT1("hello");
    String str2 = union2.toString();
    assertTrue(str2.contains("hello"));
  }

  @Test
  public void testUnionGetValueTyped() {
    Union union = new Union(0, 42);
    Integer value = union.getValue(Integer.class);
    assertEquals(value, Integer.valueOf(42));
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void testUnion2InvalidIndex() {
    Union2.of(5, "test"); // Index out of bounds for Union2
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void testUnion3InvalidIndex() {
    Union3.of(5, "test"); // Index out of bounds for Union3
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void testUnion4InvalidIndex() {
    Union4.of(5, "test"); // Index out of bounds for Union4
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void testUnion5InvalidIndex() {
    Union5.of(6, "test"); // Index out of bounds for Union5
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void testUnion6InvalidIndex() {
    Union6.of(7, "test"); // Index out of bounds for Union6
  }
}

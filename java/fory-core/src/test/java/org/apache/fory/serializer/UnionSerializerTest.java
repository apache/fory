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
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.Language;
import org.apache.fory.type.union.Union;
import org.apache.fory.type.union.Union2;
import org.apache.fory.type.union.Union3;
import org.apache.fory.type.union.Union4;
import org.apache.fory.type.union.Union5;
import org.apache.fory.type.union.Union6;
import org.testng.annotations.Test;

/** Tests for {@link UnionSerializer}. Union serialization is only used for xlang mode. */
public class UnionSerializerTest extends ForyTestBase {

  private Fory createXlangFory() {
    return Fory.builder().withLanguage(Language.XLANG).requireClassRegistration(false).build();
  }

  @Test
  public void testUnionBasicTypes() {
    Fory fory = createXlangFory();

    // Test with Integer value
    Union unionInt = new Union(0, 42);
    byte[] bytes = fory.serialize(unionInt);
    Union deserialized = (Union) fory.deserialize(bytes);
    assertEquals(deserialized.getValue(), 42);
    assertEquals(deserialized.getIndex(), 0);

    // Test with String value
    Union unionStr = new Union(1, "hello");
    bytes = fory.serialize(unionStr);
    deserialized = (Union) fory.deserialize(bytes);
    assertEquals(deserialized.getValue(), "hello");
    assertEquals(deserialized.getIndex(), 1);
  }

  @Test
  public void testUnion2Serialization() {
    Fory fory = createXlangFory();

    // Test with T1 (String) - serialize Union2 directly
    Union2<String, Long> union1 = Union2.ofT1("hello");
    byte[] bytes = fory.serialize(union1);
    Union2<?, ?> deserialized = (Union2<?, ?>) fory.deserialize(bytes);
    assertEquals(deserialized.getValue(), "hello");
    assertEquals(deserialized.getIndex(), 0);
    assertTrue(deserialized.isT1());

    // Test with T2 (Long) - serialize Union2 directly
    Union2<String, Long> union2 = Union2.ofT2(100L);
    bytes = fory.serialize(union2);
    deserialized = (Union2<?, ?>) fory.deserialize(bytes);
    assertEquals(deserialized.getValue(), 100L);
    assertEquals(deserialized.getIndex(), 1);
    assertTrue(deserialized.isT2());
  }

  @Test
  public void testUnion3Serialization() {
    Fory fory = createXlangFory();

    // Test with T1 - serialize Union3 directly
    Union3<Integer, String, Double> union1 = Union3.ofT1(42);
    byte[] bytes = fory.serialize(union1);
    Union3<?, ?, ?> deserialized = (Union3<?, ?, ?>) fory.deserialize(bytes);
    assertEquals(deserialized.getValue(), 42);
    assertEquals(deserialized.getIndex(), 0);
    assertTrue(deserialized.isT1());

    // Test with T2 - serialize Union3 directly
    Union3<Integer, String, Double> union2 = Union3.ofT2("test");
    bytes = fory.serialize(union2);
    deserialized = (Union3<?, ?, ?>) fory.deserialize(bytes);
    assertEquals(deserialized.getValue(), "test");
    assertEquals(deserialized.getIndex(), 1);
    assertTrue(deserialized.isT2());

    // Test with T3 - serialize Union3 directly
    Union3<Integer, String, Double> union3 = Union3.ofT3(3.14);
    bytes = fory.serialize(union3);
    deserialized = (Union3<?, ?, ?>) fory.deserialize(bytes);
    assertEquals((Double) deserialized.getValue(), 3.14, 0.0001);
    assertEquals(deserialized.getIndex(), 2);
    assertTrue(deserialized.isT3());
  }

  @Test
  public void testUnion4Serialization() {
    Fory fory = createXlangFory();

    // Test with T1 - serialize Union4 directly
    Union4<Integer, String, Double, Boolean> union1 = Union4.ofT1(42);
    byte[] bytes = fory.serialize(union1);
    Union4<?, ?, ?, ?> deserialized = (Union4<?, ?, ?, ?>) fory.deserialize(bytes);
    assertEquals(deserialized.getValue(), 42);
    assertEquals(deserialized.getIndex(), 0);
    assertTrue(deserialized.isT1());

    // Test with T4 - serialize Union4 directly
    Union4<Integer, String, Double, Boolean> union4 = Union4.ofT4(true);
    bytes = fory.serialize(union4);
    deserialized = (Union4<?, ?, ?, ?>) fory.deserialize(bytes);
    assertEquals(deserialized.getValue(), true);
    assertEquals(deserialized.getIndex(), 3);
    assertTrue(deserialized.isT4());
  }

  @Test
  public void testUnion5Serialization() {
    Fory fory = createXlangFory();

    // Test with T1 - serialize Union5 directly
    Union5<Integer, String, Double, Boolean, Long> union1 = Union5.ofT1(42);
    byte[] bytes = fory.serialize(union1);
    Union5<?, ?, ?, ?, ?> deserialized = (Union5<?, ?, ?, ?, ?>) fory.deserialize(bytes);
    assertEquals(deserialized.getValue(), 42);
    assertEquals(deserialized.getIndex(), 0);
    assertTrue(deserialized.isT1());

    // Test with T5 - serialize Union5 directly
    Union5<Integer, String, Double, Boolean, Long> union5 = Union5.ofT5(999L);
    bytes = fory.serialize(union5);
    deserialized = (Union5<?, ?, ?, ?, ?>) fory.deserialize(bytes);
    assertEquals(deserialized.getValue(), 999L);
    assertEquals(deserialized.getIndex(), 4);
    assertTrue(deserialized.isT5());
  }

  @Test
  public void testUnion6Serialization() {
    Fory fory = createXlangFory();

    // Test with T1 - serialize Union6 directly
    Union6<Integer, String, Double, Boolean, Long, Float> union1 = Union6.ofT1(42);
    byte[] bytes = fory.serialize(union1);
    Union6<?, ?, ?, ?, ?, ?> deserialized = (Union6<?, ?, ?, ?, ?, ?>) fory.deserialize(bytes);
    assertEquals(deserialized.getValue(), 42);
    assertEquals(deserialized.getIndex(), 0);
    assertTrue(deserialized.isT1());

    // Test with T6 - serialize Union6 directly
    Union6<Integer, String, Double, Boolean, Long, Float> union6 = Union6.ofT6(1.5f);
    bytes = fory.serialize(union6);
    deserialized = (Union6<?, ?, ?, ?, ?, ?>) fory.deserialize(bytes);
    assertEquals(deserialized.getValue(), 1.5f);
    assertEquals(deserialized.getIndex(), 5);
    assertTrue(deserialized.isT6());
  }

  @Test
  public void testUnionWithCollections() {
    Fory fory = createXlangFory();

    // Test with List
    List<Integer> list = new ArrayList<>();
    list.add(1);
    list.add(2);
    list.add(3);
    Union unionList = new Union(0, list);
    byte[] bytes = fory.serialize(unionList);
    Union deserialized = (Union) fory.deserialize(bytes);
    assertTrue(deserialized.getValue() instanceof List);
    assertEquals(deserialized.getValue(), list);

    // Test with Map
    Map<String, Integer> map = new HashMap<>();
    map.put("a", 1);
    map.put("b", 2);
    Union unionMap = new Union(1, map);
    bytes = fory.serialize(unionMap);
    deserialized = (Union) fory.deserialize(bytes);
    assertTrue(deserialized.getValue() instanceof Map);
    assertEquals(deserialized.getValue(), map);
  }

  @Test
  public void testUnionWithNull() {
    Fory fory = createXlangFory();

    Union union = new Union(0, null);
    assertFalse(union.hasValue());

    byte[] bytes = fory.serialize(union);
    Union deserialized = (Union) fory.deserialize(bytes);
    assertNotNull(deserialized);
    assertNull(deserialized.getValue());
    assertEquals(deserialized.getIndex(), 0);
  }

  @Test
  public void testUnionCopy() {
    Fory fory = createXlangFory();

    Union union = new Union(0, 42);
    Union copy = fory.copy(union);
    assertEquals(copy.getValue(), 42);
    assertEquals(copy.getIndex(), 0);

    // Test Union2 copy
    Union2<String, Long> union2 = Union2.ofT1("hello");
    Union2<?, ?> copy2 = fory.copy(union2);
    assertEquals(copy2.getValue(), "hello");
    assertEquals(copy2.getIndex(), 0);
    assertTrue(copy2.isT1());
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

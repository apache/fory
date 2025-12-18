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
import org.apache.fory.type.Union;
import org.testng.annotations.Test;

public class UnionSerializerTest extends ForyTestBase {

  @Test
  public void testUnionBasicTypes() {
    Fory fory = Fory.builder().requireClassRegistration(false).build();

    // Test with Integer value
    Union unionInt = Union.of(Integer.class, String.class);
    unionInt.setValue(42);
    byte[] bytes = fory.serialize(unionInt);
    Union deserialized = (Union) fory.deserialize(bytes);
    assertEquals(deserialized.getValue(), 42);
    assertTrue(deserialized.getValue() instanceof Integer);

    // Test with String value
    Union unionStr = Union.of(Integer.class, String.class);
    unionStr.setValue("hello");
    bytes = fory.serialize(unionStr);
    deserialized = (Union) fory.deserialize(bytes);
    assertEquals(deserialized.getValue(), "hello");
    assertTrue(deserialized.getValue() instanceof String);
  }

  @Test
  public void testUnionMultipleTypes() {
    Fory fory = Fory.builder().requireClassRegistration(false).build();

    // Test with Integer
    Union union1 = Union.of(Integer.class, String.class, Double.class);
    union1.setValue(123);
    byte[] bytes = fory.serialize(union1);
    Union deserialized = (Union) fory.deserialize(bytes);
    assertEquals(deserialized.getValue(), 123);

    // Test with String
    Union union2 = Union.of(Integer.class, String.class, Double.class);
    union2.setValue("test");
    bytes = fory.serialize(union2);
    deserialized = (Union) fory.deserialize(bytes);
    assertEquals(deserialized.getValue(), "test");

    // Test with Double
    Union union3 = Union.of(Integer.class, String.class, Double.class);
    union3.setValue(3.14);
    bytes = fory.serialize(union3);
    deserialized = (Union) fory.deserialize(bytes);
    assertEquals((Double) deserialized.getValue(), 3.14, 0.0001);
  }

  @Test
  public void testUnionWithCollections() {
    Fory fory = Fory.builder().requireClassRegistration(false).build();

    // Test with List
    Union unionList = Union.of(List.class, Map.class);
    List<Integer> list = new ArrayList<>();
    list.add(1);
    list.add(2);
    list.add(3);
    unionList.setValue(list);
    byte[] bytes = fory.serialize(unionList);
    Union deserialized = (Union) fory.deserialize(bytes);
    assertTrue(deserialized.getValue() instanceof List);
    assertEquals(deserialized.getValue(), list);

    // Test with Map
    Union unionMap = Union.of(List.class, Map.class);
    Map<String, Integer> map = new HashMap<>();
    map.put("a", 1);
    map.put("b", 2);
    unionMap.setValue(map);
    bytes = fory.serialize(unionMap);
    deserialized = (Union) fory.deserialize(bytes);
    assertTrue(deserialized.getValue() instanceof Map);
    assertEquals(deserialized.getValue(), map);
  }

  @Test
  public void testUnionEmpty() {
    Fory fory = Fory.builder().requireClassRegistration(false).build();

    Union union = Union.of(Integer.class, String.class);
    // No value set, activeIndex = -1
    assertFalse(union.hasValue());

    byte[] bytes = fory.serialize(union);
    Union deserialized = (Union) fory.deserialize(bytes);
    assertNotNull(deserialized);
  }

  @Test
  public void testUnionWithNull() {
    Fory fory = Fory.builder().requireClassRegistration(false).withRefTracking(true).build();

    Union union = Union.of(Integer.class, String.class);
    union.setValue(null);
    assertFalse(union.hasValue());
    assertEquals(union.getActiveIndex(), -1);

    byte[] bytes = fory.serialize(union);
    Union deserialized = (Union) fory.deserialize(bytes);
    assertNotNull(deserialized);
    assertNull(deserialized.getValue());
  }

  @Test
  public void testUnionCopy() {
    Fory fory = Fory.builder().requireClassRegistration(false).build();

    Union union = Union.of(Integer.class, String.class);
    union.setValue(42);

    Union copy = fory.copy(union);
    assertEquals(copy.getValue(), 42);
    assertEquals(copy.getActiveIndex(), 0);
  }

  @Test
  public void testUnionXlang() {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).requireClassRegistration(false).build();

    // Test with Integer value
    Union unionInt = Union.of(Integer.class, String.class);
    unionInt.setValue(42);
    byte[] bytes = fory.serialize(unionInt);
    Union deserialized = (Union) fory.deserialize(bytes);
    assertEquals(deserialized.getValue(), 42);

    // Test with String value
    Union unionStr = Union.of(Integer.class, String.class);
    unionStr.setValue("hello");
    bytes = fory.serialize(unionStr);
    deserialized = (Union) fory.deserialize(bytes);
    assertEquals(deserialized.getValue(), "hello");
  }

  @Test
  public void testUnionOfValue() {
    Fory fory = Fory.builder().requireClassRegistration(false).build();

    // Create Union directly with value
    Union union = Union.ofValue(42, Integer.class, String.class);
    assertEquals(union.getValue(), 42);
    assertEquals(union.getActiveIndex(), 0);
    assertTrue(union.isType(Integer.class));

    byte[] bytes = fory.serialize(union);
    Union deserialized = (Union) fory.deserialize(bytes);
    assertEquals(deserialized.getValue(), 42);
  }

  @Test
  public void testUnionValueAt() {
    Union union = Union.of(Integer.class, String.class, Double.class);

    union.setValueAt(0, 100);
    assertEquals(union.getValue(), 100);
    assertEquals(union.getActiveIndex(), 0);

    union.setValueAt(1, "test");
    assertEquals(union.getValue(), "test");
    assertEquals(union.getActiveIndex(), 1);

    union.setValueAt(2, 3.14);
    assertEquals(union.getValue(), 3.14);
    assertEquals(union.getActiveIndex(), 2);
  }

  @Test
  public void testUnionGetValue() {
    Union union = Union.of(Integer.class, String.class);
    union.setValue(42);

    Integer intValue = union.getValue(Integer.class);
    assertEquals(intValue, Integer.valueOf(42));
  }

  @Test
  public void testUnionEquality() {
    Union union1 = Union.of(Integer.class, String.class);
    union1.setValue(42);

    Union union2 = Union.of(Integer.class, String.class);
    union2.setValue(42);

    assertEquals(union1, union2);
    assertEquals(union1.hashCode(), union2.hashCode());
  }

  @Test
  public void testUnionToString() {
    Union union = Union.of(Integer.class, String.class);
    union.setValue(42);
    String str = union.toString();
    assertTrue(str.contains("Integer"));
    assertTrue(str.contains("42"));
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void testUnionInvalidValue() {
    Union union = Union.of(Integer.class, String.class);
    union.setValue(3.14); // Double is not in alternative types
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void testUnionEmptyTypes() {
    Union.of(); // Should throw exception
  }

  @Test(expectedExceptions = IndexOutOfBoundsException.class)
  public void testUnionInvalidIndex() {
    Union union = Union.of(Integer.class, String.class);
    union.setValueAt(5, "test"); // Index out of bounds
  }
}

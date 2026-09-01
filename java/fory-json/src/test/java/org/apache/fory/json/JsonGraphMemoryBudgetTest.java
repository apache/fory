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

package org.apache.fory.json;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.apache.fory.json.annotation.JsonAnyProperty;
import org.apache.fory.json.annotation.JsonByteArray;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.apache.fory.json.annotation.JsonUnwrapped;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.serializer.GraphMemoryEstimates;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class JsonGraphMemoryBudgetTest extends ForyJsonTestModels {
  private static final int REF_BYTES = GraphMemoryEstimates.REFERENCE_BYTES;

  @Factory(dataProvider = "enableCodegen")
  public JsonGraphMemoryBudgetTest(boolean codegen) {
    super(codegen);
  }

  @Test
  public void configBounds() {
    assertEquals(ForyJson.DEFAULT_MAX_GRAPH_MEMORY_BYTES, 128L * 1024 * 1024);
    assertThrows(IllegalArgumentException.class, () -> newJsonBuilder().withMaxGraphMemoryBytes(0));
    assertThrows(
        IllegalArgumentException.class, () -> newJsonBuilder().withMaxGraphMemoryBytes(-1));
  }

  @Test
  public void mutableAndCreatorOwners() {
    int mutableBytes = shallow(MutableValue.class);
    MutableValue mutable =
        assertClassBudget("{\"number\":1,\"text\":\"x\"}", MutableValue.class, mutableBytes);
    assertEquals(mutable.number, 1);
    assertEquals(mutable.text, "x");

    int creatorBytes = shallow(CreatorValue.class);
    CreatorValue creator = assertClassBudget("{\"number\":2}", CreatorValue.class, creatorBytes);
    assertEquals(creator.number, 2);
  }

  @Test
  public void nestedOwnersAccumulate() {
    long ownerBytes = shallow(NestedValue.class) + shallow(MutableValue.class);
    NestedValue value =
        assertClassBudget(
            "{\"child\":{\"number\":3,\"text\":\"nested\"}}", NestedValue.class, ownerBytes);
    assertEquals(value.child.number, 3);
  }

  @Test
  public void typedContainerOwners() {
    TypeRef<List<String>> listType = new TypeRef<List<String>>() {};
    long listBytes = shallow(ArrayList.class) + 2L * REF_BYTES;
    assertEquals(assertTypeBudget("[\"a\",\"b\"]", listType, listBytes), Arrays.asList("a", "b"));

    String stagedInput = "[\"0\",\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\",\"9\"]";
    List<String> stagedExpected = Arrays.asList("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
    long stagedBytes = shallow(ArrayList.class) + 10L * REF_BYTES;
    assertEquals(assertTypeBudget(stagedInput, listType, stagedBytes), stagedExpected);
    assertEquals(
        assertTypeBytesBudget(stagedInput.getBytes(StandardCharsets.UTF_8), listType, stagedBytes),
        stagedExpected);

    TypeRef<Set<String>> setType = new TypeRef<Set<String>>() {};
    long setBytes = linkedHashSetBytes(2);
    assertEquals(
        assertTypeBudget("[\"a\",\"b\"]", setType, setBytes),
        new LinkedHashSet<>(Arrays.asList("a", "b")));

    TypeRef<Map<String, String>> mapType = new TypeRef<Map<String, String>>() {};
    long mapBytes = shallow(LinkedHashMap.class) + 2L * 2 * REF_BYTES;
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("a", "x");
    expected.put("b", "y");
    assertEquals(assertTypeBudget("{\"a\":\"x\",\"b\":\"y\"}", mapType, mapBytes), expected);
  }

  @Test
  public void concreteContainerFields() {
    TypeRef<FieldList<String>> listType = new TypeRef<FieldList<String>>() {};
    int listBytes = shallow(FieldList.class);
    assertEquals(listBytes - shallow(PlainList.class), Long.BYTES + Integer.BYTES);
    assertEquals(assertTypeBudget("[]", listType, listBytes), new FieldList<>());

    TypeRef<FieldMap<String, String>> mapType = new TypeRef<FieldMap<String, String>>() {};
    int mapBytes = shallow(FieldMap.class);
    assertEquals(mapBytes - shallow(PlainMap.class), Long.BYTES + Integer.BYTES);
    assertEquals(assertTypeBudget("{}", mapType, mapBytes), new FieldMap<>());
  }

  @Test
  public void naturalContainerOwners() {
    long arrayBytes = shallow(JsonArray.class) + 2L * REF_BYTES;
    Object array = assertClassBudget("[1,2]", Object.class, arrayBytes);
    assertTrue(array instanceof JsonArray);

    long objectBytes = shallow(JsonObject.class) + 2L * 2 * REF_BYTES;
    Object object = assertClassBudget("{\"a\":1,\"b\":2}", Object.class, objectBytes);
    assertTrue(object instanceof JsonObject);

    long nestedBytes =
        shallow(JsonObject.class) + 2L * REF_BYTES + shallow(JsonArray.class) + 2L * REF_BYTES;
    Object nested = assertClassBudget("{\"values\":[1,2]}", Object.class, nestedBytes);
    assertTrue(((JsonObject) nested).get("values") instanceof JsonArray);
  }

  @Test
  public void referenceArrayOwners() {
    long arrayBytes = GraphMemoryEstimates.objectArrayBytes() + 2L * REF_BYTES;
    assertEquals(
        assertClassBudget("[\"a\",\"b\"]", String[].class, arrayBytes), new String[] {"a", "b"});
    assertEquals(
        assertClassBudget("[1,\"b\"]", Object[].class, arrayBytes), new Object[] {1L, "b"});
  }

  @Test
  public void referenceArrayBatches() {
    String input = childArray(1024);
    long budget = 1023L * shallow(CountingChild.class);
    CountingChild.creations = 0;
    assertThrows(
        ForyJsonException.class,
        () -> jsonWithBudget(budget).fromJson(input, CountingChild[].class));
    assertEquals(CountingChild.creations, 1023);

    TypeRef<AtomicReferenceArray<CountingChild>> type =
        new TypeRef<AtomicReferenceArray<CountingChild>>() {};
    CountingChild.creations = 0;
    assertThrows(ForyJsonException.class, () -> jsonWithBudget(budget).fromJson(input, type));
    assertEquals(CountingChild.creations, 1023);
  }

  @Test
  public void primitiveArrayOwners() {
    int headerBytes = GraphMemoryEstimates.objectArrayBytes();
    assertEquals(assertClassBudget("[]", int[].class, headerBytes), new int[0]);
    assertEquals(
        assertClassBudget("[true]", boolean[].class, headerBytes + Byte.BYTES),
        new boolean[] {true});
    assertEquals(
        assertClassBudget("[2]", short[].class, headerBytes + Short.BYTES), new short[] {2});
    assertEquals(assertClassBudget("[3]", int[].class, headerBytes + Integer.BYTES), new int[] {3});
    assertEquals(assertClassBudget("[4]", long[].class, headerBytes + Long.BYTES), new long[] {4});
    assertEquals(
        assertClassBudget("[5.5]", float[].class, headerBytes + Float.BYTES), new float[] {5.5f});
    assertEquals(
        assertClassBudget("[6.5]", double[].class, headerBytes + Double.BYTES),
        new double[] {6.5d});
    assertEquals(
        assertClassBudget("[\"值\"]", char[].class, headerBytes + Character.BYTES),
        new char[] {'值'});
    assertEquals(
        assertClassBytesBudget(
            "[7]".getBytes(StandardCharsets.UTF_8), int[].class, headerBytes + Integer.BYTES),
        new int[] {7});
  }

  @Test
  public void byteArrayRepresentations() {
    long arrayBytes = shallow(ArrayBytes.class) + GraphMemoryEstimates.objectArrayBytes() + 1;
    assertEquals(
        assertClassBudget("{\"bytes\":[1]}", ArrayBytes.class, arrayBytes).bytes, new byte[] {1});
    assertEquals(
        assertClassBytesBudget(
                "{\"bytes\":[1]}".getBytes(StandardCharsets.UTF_8), ArrayBytes.class, arrayBytes)
            .bytes,
        new byte[] {1});
    ForyJson binaryJson = jsonWithBudget(shallow(BinaryBytes.class));
    for (String encoded : new String[] {"AQ==", "A\\u0051=="}) {
      String input = "{\"bytes\":\"" + encoded + "\"}";
      assertEquals(binaryJson.fromJson(input, BinaryBytes.class).bytes, new byte[] {1});
      assertEquals(
          binaryJson.fromJson(input.getBytes(StandardCharsets.UTF_8), BinaryBytes.class).bytes,
          new byte[] {1});
      assertEquals(jsonWithBudget(1).fromJson("\"" + encoded + "\"", byte[].class), new byte[] {1});
    }
  }

  public static final class ArrayBytes {
    @JsonByteArray(JsonByteArray.Format.ARRAY)
    public byte[] bytes;
  }

  public static final class BinaryBytes {
    @JsonByteArray(JsonByteArray.Format.BASE64)
    public byte[] bytes;
  }

  @Test
  public void primitiveArrayBatches() {
    int headerBytes = GraphMemoryEstimates.objectArrayBytes();
    int[] values =
        assertClassBudget(intArray(1024), int[].class, headerBytes + 1024L * Integer.BYTES);
    assertEquals(values.length, 1024);
    assertEquals(values[1023], 1023);

    long budget = headerBytes + 1023L * Integer.BYTES;
    ForyJson json = newJsonBuilder().withMaxGraphMemoryBytes(budget).build();
    assertThrows(ForyJsonException.class, () -> json.fromJson(intArray(1024), int[].class));
  }

  @Test
  public void unwrappedOwners() {
    long parentBytes = shallow(UnwrappedValue.class);
    UnwrappedValue absent = assertClassBudget("{}", UnwrappedValue.class, parentBytes);
    assertEquals(absent.child, null);

    long presentBytes = parentBytes + shallow(UnwrappedChild.class);
    UnwrappedValue present =
        assertClassBudget("{\"child_name\":\"x\"}", UnwrappedValue.class, presentBytes);
    assertEquals(present.child.name, "x");
  }

  @Test
  public void subtypeOwnerChargedOnce() {
    long ownerBytes = shallow(SubValue.class);
    BaseValue value =
        assertClassBudget("{\"kind\":\"sub\",\"number\":7}", BaseValue.class, ownerBytes);
    assertEquals(((SubValue) value).number, 7);
  }

  @Test
  public void compositeOwners() {
    TypeRef<AtomicReference<String>> referenceType = new TypeRef<AtomicReference<String>>() {};
    long referenceBytes = shallow(AtomicReference.class);
    assertEquals(assertTypeBudget("\"x\"", referenceType, referenceBytes).get(), "x");

    TypeRef<AtomicReferenceArray<String>> arrayType =
        new TypeRef<AtomicReferenceArray<String>>() {};
    long arrayBytes =
        shallow(AtomicReferenceArray.class)
            + GraphMemoryEstimates.objectArrayBytes()
            + 2L * REF_BYTES;
    AtomicReferenceArray<String> array = assertTypeBudget("[\"a\",\"b\"]", arrayType, arrayBytes);
    assertEquals(array.get(0), "a");
    assertEquals(array.get(1), "b");

    TypeRef<Optional<String>> optionalType = new TypeRef<Optional<String>>() {};
    long optionalBytes = shallow(Optional.class);
    assertEquals(
        assertTypeBudget("\"present\"", optionalType, optionalBytes), Optional.of("present"));
  }

  @Test
  public void candidateSlotsGateChildren() {
    TypeRef<CountingList<CountingChild>> listType = new TypeRef<CountingList<CountingChild>>() {};
    String listInput = childArray(1024);
    long listBudget = shallow(CountingList.class) + 1023L * shallow(CountingChild.class);
    ForyJson listJson = jsonWithBudget(listBudget);
    CountingChild.creations = 0;
    CountingList.adds = 0;
    assertThrows(ForyJsonException.class, () -> listJson.fromJson(listInput, listType));
    assertEquals(CountingChild.creations, 1023);
    assertEquals(CountingList.adds, 1023);

    TypeRef<CountingMap<String, CountingChild>> mapType =
        new TypeRef<CountingMap<String, CountingChild>>() {};
    String mapInput = childMap(1024);
    long mapBudget = shallow(CountingMap.class) + 1023L * shallow(CountingChild.class);
    ForyJson mapJson = jsonWithBudget(mapBudget);
    CountingChild.creations = 0;
    CountingMap.puts = 0;
    assertThrows(ForyJsonException.class, () -> mapJson.fromJson(mapInput, mapType));
    assertEquals(CountingChild.creations, 1023);
    assertEquals(CountingMap.puts, 1023);
  }

  @Test
  public void stagedCollectionBatches() {
    TypeRef<List<CountingChild>> type = new TypeRef<List<CountingChild>>() {};
    int prefixSize = 9;
    int completed = prefixSize + 1023;
    String input = childArray(completed + 1);
    long budget =
        shallow(ArrayList.class)
            + (long) prefixSize * REF_BYTES
            + (long) completed * shallow(CountingChild.class);
    CountingChild.creations = 0;
    assertThrows(
        ForyJsonException.class,
        () -> jsonWithBudget(budget).fromJson(input.getBytes(StandardCharsets.UTF_8), type));
    assertEquals(CountingChild.creations, completed);
  }

  @Test
  public void ninthSlotGuardsArrayListStorage() {
    TypeRef<List<CountingChild>> type = new TypeRef<List<CountingChild>>() {};
    long budget = shallow(ArrayList.class) + 8L * REF_BYTES + 9L * shallow(CountingChild.class);

    assertNinthSlotGuard(childArray(9), type, budget, 8);
    assertNinthSlotGuard(
        childArray(9).getBytes(StandardCharsets.UTF_8), type, budget, codegenEnabled() ? 9 : 8);
    assertNinthSlotGuard(utf16ChildArray(9), type, budget, 8);
  }

  @Test
  public void duplicateSlotsAreCharged() {
    TypeRef<Set<String>> setType = new TypeRef<Set<String>>() {};
    Set<String> set = assertTypeBudget("[\"same\",\"same\"]", setType, linkedHashSetBytes(2));
    assertEquals(set, new LinkedHashSet<>(Arrays.asList("same")));

    TypeRef<Map<String, String>> mapType = new TypeRef<Map<String, String>>() {};
    long mapBytes = shallow(LinkedHashMap.class) + 2L * 2 * REF_BYTES;
    Map<String, String> map =
        assertTypeBudget("{\"same\":\"first\",\"same\":\"last\"}", mapType, mapBytes);
    assertEquals(map.size(), 1);
    assertEquals(map.get("same"), "last");
  }

  @Test
  public void enumContainerBackingOwners() {
    TypeRef<EnumMap<SmallKey, String>> mapType = new TypeRef<EnumMap<SmallKey, String>>() {};
    long mapBytes =
        shallow(EnumMap.class)
            + GraphMemoryEstimates.objectArrayBytes()
            + (long) SmallKey.values().length * REF_BYTES
            + 2L * REF_BYTES;
    EnumMap<SmallKey, String> map = assertTypeBudget("{\"A\":\"x\"}", mapType, mapBytes);
    assertEquals(map.get(SmallKey.A), "x");

    TypeRef<EnumSet<JumboKey>> setType = new TypeRef<EnumSet<JumboKey>>() {};
    Class<?> ownerType = EnumSet.noneOf(JumboKey.class).getClass();
    long words = (JumboKey.values().length + Long.SIZE - 1L) / Long.SIZE;
    long setBytes =
        shallow(ownerType)
            + GraphMemoryEstimates.objectArrayBytes()
            + words * Long.BYTES
            + REF_BYTES;
    EnumSet<JumboKey> set = assertTypeBudget("[\"V00\"]", setType, setBytes);
    assertEquals(set, EnumSet.of(JumboKey.V00));
  }

  @Test
  public void anyMapGatesChildren() {
    long budget =
        shallow(CountingAny.class)
            + shallow(CountingMap.class)
            + 2L * REF_BYTES
            + shallow(CountingChild.class);
    ForyJson json = jsonWithBudget(budget);
    CountingChild.creations = 0;
    CountingMap.puts = 0;
    assertThrows(
        ForyJsonException.class,
        () ->
            json.fromJson(
                "{\"first\":{\"number\":1},\"second\":{\"number\":2}}", CountingAny.class));
    assertEquals(CountingChild.creations, 1);
    assertEquals(CountingMap.puts, 1);
  }

  @Test
  public void dedicatedLeavesAreUncharged() {
    ForyJson json = jsonWithBudget(1);
    assertEquals(json.fromJson("123", Long.class), Long.valueOf(123));
    assertEquals(json.fromJson("\"a long leaf string\"", String.class), "a long leaf string");
  }

  @Test
  public void allRootReaderOverloads() {
    long ownerBytes = shallow(MutableValue.class);
    assertClassBudget("{\"number\":1}", MutableValue.class, ownerBytes);
    assertClassBudget("{\"名称\":\"值\",\"number\":2}", MutableValue.class, ownerBytes);
    assertClassBytesBudget(
        "{\"number\":3}".getBytes(StandardCharsets.UTF_8), MutableValue.class, ownerBytes);

    TypeRef<List<String>> type = new TypeRef<List<String>>() {};
    long listBytes = shallow(ArrayList.class) + REF_BYTES;
    assertTypeBudget("[\"a\"]", type, listBytes);
    assertTypeBudget("[\"值\"]", type, listBytes);
    assertTypeBytesBudget("[\"值\"]".getBytes(StandardCharsets.UTF_8), type, listBytes);
  }

  @Test
  public void failedRootResetsBudget() {
    long ownerBytes = shallow(MutableValue.class);
    ForyJson json = jsonWithBudget(ownerBytes);
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"child\":{\"number\":1}}", NestedValue.class));
    MutableValue value = json.fromJson("{\"number\":2}", MutableValue.class);
    assertNotNull(value);
    assertEquals(value.number, 2);
  }

  private <T> T assertClassBudget(String input, Class<T> type, long ownerBytes) {
    assertThrows(
        ForyJsonException.class, () -> jsonWithBudget(ownerBytes - 1).fromJson(input, type));
    return jsonWithBudget(ownerBytes).fromJson(input, type);
  }

  private <T> T assertClassBytesBudget(byte[] input, Class<T> type, long ownerBytes) {
    assertThrows(
        ForyJsonException.class, () -> jsonWithBudget(ownerBytes - 1).fromJson(input, type));
    return jsonWithBudget(ownerBytes).fromJson(input, type);
  }

  private <T> T assertTypeBudget(String input, TypeRef<T> type, long ownerBytes) {
    assertThrows(
        ForyJsonException.class, () -> jsonWithBudget(ownerBytes - 1).fromJson(input, type));
    return jsonWithBudget(ownerBytes).fromJson(input, type);
  }

  private <T> T assertTypeBytesBudget(byte[] input, TypeRef<T> type, long ownerBytes) {
    assertThrows(
        ForyJsonException.class, () -> jsonWithBudget(ownerBytes - 1).fromJson(input, type));
    return jsonWithBudget(ownerBytes).fromJson(input, type);
  }

  private ForyJson jsonWithBudget(long ownerBytes) {
    return newJsonBuilder().withMaxGraphMemoryBytes(ownerBytes).build();
  }

  private static int shallow(Class<?> type) {
    return GraphMemoryEstimates.shallowObjectBytes(type);
  }

  private static long linkedHashSetBytes(int slots) {
    return shallow(LinkedHashSet.class) + shallow(LinkedHashMap.class) + (long) slots * REF_BYTES;
  }

  private static String childArray(int size) {
    StringBuilder input = new StringBuilder(size * 16);
    input.append('[');
    for (int i = 0; i < size; i++) {
      if (i != 0) {
        input.append(',');
      }
      input.append("{\"number\":").append(i).append('}');
    }
    return input.append(']').toString();
  }

  private static String utf16ChildArray(int size) {
    String input = childArray(size);
    return input.replaceFirst("\\{", "{\"ignored\":\"Ā\",");
  }

  private <T> void assertNinthSlotGuard(
      String input, TypeRef<T> type, long budget, int expectedCreations) {
    CountingChild.creations = 0;
    assertThrows(ForyJsonException.class, () -> jsonWithBudget(budget).fromJson(input, type));
    assertEquals(CountingChild.creations, expectedCreations);
  }

  private <T> void assertNinthSlotGuard(
      byte[] input, TypeRef<T> type, long budget, int expectedCreations) {
    CountingChild.creations = 0;
    assertThrows(ForyJsonException.class, () -> jsonWithBudget(budget).fromJson(input, type));
    assertEquals(CountingChild.creations, expectedCreations);
  }

  private static String childMap(int size) {
    StringBuilder input = new StringBuilder(size * 24);
    input.append('{');
    for (int i = 0; i < size; i++) {
      if (i != 0) {
        input.append(',');
      }
      input.append("\"v").append(i).append("\":{\"number\":").append(i).append('}');
    }
    return input.append('}').toString();
  }

  private static String intArray(int size) {
    StringBuilder input = new StringBuilder(size * 4);
    input.append('[');
    for (int i = 0; i < size; i++) {
      if (i != 0) {
        input.append(',');
      }
      input.append(i);
    }
    return input.append(']').toString();
  }

  public static class MutableValue {
    public int number;
    public String text;
  }

  public static final class CreatorValue {
    public final int number;

    @JsonCreator({"number"})
    public CreatorValue(int number) {
      this.number = number;
    }
  }

  public static class NestedValue {
    public MutableValue child;
  }

  public static final class CountingChild {
    static int creations;
    public final int number;

    @JsonCreator({"number"})
    public CountingChild(int number) {
      creations++;
      this.number = number;
    }
  }

  public static final class CountingList<E> extends ArrayList<E> {
    static int adds;

    @Override
    public boolean add(E value) {
      adds++;
      return super.add(value);
    }
  }

  public static final class CountingMap<K, V> extends LinkedHashMap<K, V> {
    static int puts;

    @Override
    public V put(K key, V value) {
      puts++;
      return super.put(key, value);
    }
  }

  public static final class PlainList<E> extends ArrayList<E> {}

  public static class FieldListBase<E> extends ArrayList<E> {
    private long inheritedField;
  }

  public static final class FieldList<E> extends FieldListBase<E> {
    private int directField;
  }

  public static final class PlainMap<K, V> extends LinkedHashMap<K, V> {}

  public static class FieldMapBase<K, V> extends LinkedHashMap<K, V> {
    private long inheritedField;
  }

  public static final class FieldMap<K, V> extends FieldMapBase<K, V> {
    private int directField;
  }

  public static final class CountingAny {
    @JsonAnyProperty public CountingMap<String, CountingChild> values;
  }

  public static class UnwrappedValue {
    @JsonUnwrapped(prefix = "child_")
    public UnwrappedChild child;
  }

  public static class UnwrappedChild {
    public String name;
  }

  @JsonSubTypes(
      property = "kind",
      value = {@JsonSubTypes.Type(value = SubValue.class, name = "sub")})
  public interface BaseValue {}

  public static final class SubValue implements BaseValue {
    public int number;
  }

  public enum SmallKey {
    A,
    B,
    C
  }

  public enum JumboKey {
    V00,
    V01,
    V02,
    V03,
    V04,
    V05,
    V06,
    V07,
    V08,
    V09,
    V10,
    V11,
    V12,
    V13,
    V14,
    V15,
    V16,
    V17,
    V18,
    V19,
    V20,
    V21,
    V22,
    V23,
    V24,
    V25,
    V26,
    V27,
    V28,
    V29,
    V30,
    V31,
    V32,
    V33,
    V34,
    V35,
    V36,
    V37,
    V38,
    V39,
    V40,
    V41,
    V42,
    V43,
    V44,
    V45,
    V46,
    V47,
    V48,
    V49,
    V50,
    V51,
    V52,
    V53,
    V54,
    V55,
    V56,
    V57,
    V58,
    V59,
    V60,
    V61,
    V62,
    V63,
    V64
  }
}

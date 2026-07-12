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
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.primitives.ImmutableIntArray;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.AbstractCollection;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractQueue;
import java.util.AbstractSequentialList;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.apache.fory.json.data.FastContainers;
import org.apache.fory.json.data.Kind;
import org.apache.fory.json.data.MapKeyFields;
import org.apache.fory.json.data.Nested;
import org.apache.fory.json.data.TokenValues;
import org.apache.fory.reflect.TypeRef;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class JsonContainerTest extends ForyJsonTestModels {
  @Factory(dataProvider = "enableCodegen")
  public JsonContainerTest(boolean codegen) {
    super(codegen);
  }

  @Test
  public void writeNestedCollections() {
    ForyJson json = newJson();
    assertEquals(
        json.toJson(new Nested()),
        "{\"kind\":\"FAST\",\"names\":[\"a\",\"b\"],\"scores\":{\"one\":1,\"two\":2}}");
  }

  @Test
  public void readTypeRefList() {
    ForyJson json = newJson();
    List<TokenValues> values =
        json.fromJson(
            "[{\"count\":1,\"name\":\"alpha\",\"tags\":[\"x\",\"y\"],\"total\":2},"
                + "{\"count\":3,\"name\":\"beta\",\"tags\":[\"z\"],\"total\":4}]",
            new TypeRef<List<TokenValues>>() {});
    assertEquals(values.size(), 2);
    assertEquals(values.get(0).name, "alpha");
    assertEquals(values.get(0).tags, Arrays.asList("x", "y"));
    assertEquals(values.get(1).count, 3);
    assertEquals(values.get(1).total, 4);
  }

  @Test
  public void readParameterizedPojo() {
    ForyJson json = newJson();
    GenericBox<String> strings =
        json.fromJson(
            "{\"value\":\"latin\",\"values\":[\"one\",\"two\"]}",
            new TypeRef<GenericBox<String>>() {});
    assertEquals(strings.value, "latin");
    assertEquals(strings.values, Arrays.asList("one", "two"));

    String objectJson =
        "{\"value\":{\"count\":1,\"name\":\"你好，Fory\",\"tags\":[],\"total\":2},"
            + "\"values\":[{\"count\":3,\"name\":\"utf8\",\"tags\":[],\"total\":4}]}";
    GenericBox<TokenValues> objects =
        json.fromJson(
            objectJson.getBytes(StandardCharsets.UTF_8), new TypeRef<GenericBox<TokenValues>>() {});
    assertEquals(objects.value.getClass(), TokenValues.class);
    assertEquals(objects.value.name, ZH_TEXT);
    assertEquals(objects.values.get(0).getClass(), TokenValues.class);
    assertEquals(objects.values.get(0).name, "utf8");
  }

  @Test
  public void readCollectionSubclassElementType() {
    ForyJson json = newJson();
    Shelf shelf = json.fromJson("{\"notes\":[{\"title\":\"first\"}]}", Shelf.class);
    assertEquals(shelf.notes.get(0).getClass(), Note.class);
    assertEquals(shelf.notes.get(0).title, "first");
  }

  @Test
  public void readMapSubclassValueType() {
    ForyJson json = newJson();
    PaletteGroups groups = json.fromJson("{\"warm\":{\"primary\":\"red\"}}", PaletteGroups.class);
    assertEquals(groups.get("warm").getClass(), PaletteCodes.class);
    assertEquals(groups.get("warm").get("primary"), "red");
  }

  @Test
  public void readTypeRefMapBytes() {
    ForyJson json = newJson();
    byte[] bytes =
        "{\"first\":{\"count\":5,\"name\":\"gamma\",\"tags\":[\"u\"],\"total\":6}}"
            .getBytes(StandardCharsets.UTF_8);
    Map<String, TokenValues> values =
        json.fromJson(bytes, new TypeRef<Map<String, TokenValues>>() {});
    assertEquals(values.size(), 1);
    assertEquals(values.get("first").name, "gamma");
    assertEquals(values.get("first").tags, Arrays.asList("u"));
    assertEquals(values.get("first").total, 6);
  }

  @Test
  public void readJsonContainers() {
    ForyJson json = newJson();
    JsonObject object =
        json.fromJson("{\"name\":\"fory\",\"items\":[1,\"你好，Fory\"]}", JsonObject.class);
    assertEquals(object.get("name"), "fory");
    assertTrue(object.get("items") instanceof JsonArray);
    JsonArray items = (JsonArray) object.get("items");
    assertEquals(items.get(0), Long.valueOf(1));
    assertEquals(items.get(1), ZH_TEXT);

    Object natural = json.fromJson("{\"items\":[true]}", Object.class);
    assertTrue(natural instanceof JsonObject);
    assertTrue(((JsonObject) natural).get("items") instanceof JsonArray);
  }

  @Test
  public void parsedContainersStartSmall() {
    ForyJson json = newJson();
    JsonArray array = json.fromJson("[1]", JsonArray.class);
    assertEquals(arrayCapacity(array), 1);
    assertEquals(arrayCapacity(json.fromJson("[]", JsonArray.class)), 0);

    List<Object> list = json.fromJson("[1]", new TypeRef<List<Object>>() {});
    assertTrue(list instanceof ArrayList);
    assertEquals(arrayCapacity((ArrayList<?>) list), 1);

    List<String> strings =
        json.fromJson(
            "[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\"]".getBytes(StandardCharsets.UTF_8),
            new TypeRef<List<String>>() {});
    assertTrue(strings instanceof ArrayList);
    assertEquals(strings, Arrays.asList("a", "b", "c", "d", "e", "f", "g"));
    assertEquals(arrayCapacity((ArrayList<?>) strings), 7);

    List<Note> notes =
        json.fromJson(
            ("[{\"title\":\"a\"},{\"title\":\"b\"},null,{\"title\":\"d\"},"
                    + "{\"title\":\"e\"},{\"title\":\"f\"},{\"title\":\"g\"}]")
                .getBytes(StandardCharsets.UTF_8),
            new TypeRef<List<Note>>() {});
    assertTrue(notes instanceof ArrayList);
    assertEquals(notes.size(), 7);
    assertEquals(notes.get(0).title, "a");
    assertEquals(notes.get(2), null);
    assertEquals(notes.get(6).title, "g");
    assertEquals(arrayCapacity((ArrayList<?>) notes), 7);

    JsonObject object = json.fromJson("{\"x\":1}", JsonObject.class);
    assertEquals(mapCapacity(object), 2);
    assertEquals(mapCapacity(json.fromJson("{}", JsonObject.class)), 0);

    Map<String, Object> map = json.fromJson("{\"x\":1}", new TypeRef<Map<String, Object>>() {});
    assertTrue(map instanceof LinkedHashMap);
    assertEquals(mapCapacity((LinkedHashMap<?, ?>) map), 2);
  }

  @Test
  public void writeJsonContainers() {
    ForyJson json = newJson();
    JsonObject object = new JsonObject();
    JsonArray values = new JsonArray();
    values.add(Integer.valueOf(1));
    values.add(ZH_TEXT);
    object.put("values", values);
    object.put("name", "fory");
    String expected = "{\"values\":[1,\"你好，Fory\"],\"name\":\"fory\"}";
    assertEquals(json.toJson(object), expected);
    assertEquals(new String(json.toJsonBytes(object), StandardCharsets.UTF_8), expected);
  }

  @Test
  public void writeReadMapKeyFields() {
    ForyJson json = newJson();
    String expected =
        "{\"intNames\":{\"1\":\"one\",\"2\":\"two\"},\"scores\":{\"FAST\":1,\"SMALL\":2}}";
    assertEquals(json.toJson(new MapKeyFields()), expected);
    MapKeyFields read = json.fromJson(expected, MapKeyFields.class);
    assertEquals(read.intNames, intNames());
    assertEquals(read.scores, enumScores());
  }

  @Test
  public void writeRootMapNumericKeys() {
    ForyJson json = newJson();
    Map<Integer, Integer> value =
        json.fromJson("{\"7\":70,\"8\":80}", new TypeRef<Map<Integer, Integer>>() {});
    assertEquals(json.toJson(new LinkedHashMap<>(value)), "{\"7\":70,\"8\":80}");
  }

  @Test
  public void readTypeRefOptional() {
    ForyJson json = newJson();
    Optional<TokenValues> value =
        json.fromJson(
            "{\"count\":9,\"name\":\"optional\",\"tags\":[\"a\"],\"total\":10}",
            new TypeRef<Optional<TokenValues>>() {});
    assertTrue(value.isPresent());
    assertEquals(value.get().name, "optional");
    assertEquals(value.get().tags, Arrays.asList("a"));
    assertEquals(json.fromJson("null", new TypeRef<Optional<TokenValues>>() {}), Optional.empty());
  }

  @Test
  public void readTypeRefMapKeys() {
    ForyJson json = newJson();
    Map<Integer, String> value =
        json.fromJson("{\"1\":\"one\",\"2\":\"two\"}", new TypeRef<Map<Integer, String>>() {});
    assertEquals(value, intNames());

    Map<Integer, String> escaped =
        json.fromJson("{\"\\u0031\":\"one\"}", new TypeRef<Map<Integer, String>>() {});
    assertEquals(escaped.get(1), "one");

    Map<Long, String> longs =
        json.fromJson(
            "{\"-1\":\"negative\",\"9223372036854775807\":\"max\"}",
            new TypeRef<Map<Long, String>>() {});
    Map<Long, String> expected = new LinkedHashMap<>();
    expected.put(-1L, "negative");
    expected.put(Long.MAX_VALUE, "max");
    assertEquals(longs, expected);
    assertEquals(
        json.fromJson(
            "{\"-1\":\"negative\",\"9223372036854775807\":\"max\"}"
                .getBytes(StandardCharsets.UTF_8),
            new TypeRef<Map<Long, String>>() {}),
        expected);
  }

  @Test
  public void readFastContainerTypeRefs() {
    ForyJson json = newJson();
    assertEquals(
        json.fromJson("[\"alpha\",\"你好，Fory\"]", new TypeRef<List<String>>() {}),
        Arrays.asList("alpha", ZH_TEXT));
    assertEquals(json.fromJson("[1,2]", new TypeRef<List<Integer>>() {}), Arrays.asList(1, 2));
    assertEquals(
        json.fromJson("[true,false]", new TypeRef<List<Boolean>>() {}),
        Arrays.asList(Boolean.TRUE, Boolean.FALSE));
    assertEquals(
        json.fromJson(
            "{\"one\":1,\"two\":2}".getBytes(StandardCharsets.UTF_8),
            new TypeRef<Map<String, Integer>>() {}),
        scores());
    assertEquals(
        json.fromJson(
            "{\"enabled\":true,\"disabled\":false}", new TypeRef<Map<String, Boolean>>() {}),
        flags());
    Map<String, String> aliases = new LinkedHashMap<>();
    aliases.put("zh", ZH_TEXT);
    aliases.put("eu", EU_TEXT);
    assertEquals(
        json.fromJson(
            "{\"zh\":\"你好，Fory\",\"eu\":\"café crème Österreich € ČšŽ\"}",
            new TypeRef<Map<String, String>>() {}),
        aliases);
    assertEquals(
        json.fromJson("{\"1\":\"one\",\"2\":\"two\"}", new TypeRef<Map<Integer, String>>() {}),
        intNames());
  }

  @Test
  public void readNullableScalarContainers() {
    ForyJson json = newJson();
    assertEquals(
        json.fromJson("[\"\u0100\",null]", new TypeRef<List<String>>() {}),
        Arrays.asList("\u0100", null));
    assertEquals(
        json.fromJson("[true,null]", new TypeRef<List<Boolean>>() {}),
        Arrays.asList(Boolean.TRUE, null));
    assertEquals(
        json.fromJson("[1,null]".getBytes(StandardCharsets.UTF_8), new TypeRef<List<Integer>>() {}),
        Arrays.asList(Integer.valueOf(1), null));
    assertEquals(
        json.fromJson("[1,null]", new TypeRef<List<Long>>() {}),
        Arrays.asList(Long.valueOf(1), null));
    assertEquals(
        json.fromJson("[1,null]", new TypeRef<List<Short>>() {}),
        Arrays.asList(Short.valueOf((short) 1), null));
    assertEquals(
        json.fromJson("[1,null]", new TypeRef<List<Byte>>() {}),
        Arrays.asList(Byte.valueOf((byte) 1), null));
    assertEquals(
        json.fromJson("[1.5,null]", new TypeRef<List<Float>>() {}),
        Arrays.asList(Float.valueOf(1.5f), null));
    assertEquals(
        json.fromJson("[1.5,null]", new TypeRef<List<Double>>() {}),
        Arrays.asList(Double.valueOf(1.5d), null));
    assertEquals(
        json.fromJson("[123,null]", new TypeRef<List<BigInteger>>() {}),
        Arrays.asList(BigInteger.valueOf(123), null));
    assertEquals(
        json.fromJson("[1.25,null]", new TypeRef<List<BigDecimal>>() {}),
        Arrays.asList(new BigDecimal("1.25"), null));

    assertEquals(
        json.fromJson("{\"key\":null}", new TypeRef<Map<String, String>>() {}),
        Collections.<String, String>singletonMap("key", null));
    assertEquals(
        json.fromJson("{\"key\":null}", new TypeRef<Map<String, Boolean>>() {}),
        Collections.<String, Boolean>singletonMap("key", null));
    assertEquals(
        json.fromJson(
            "{\"key\":null}".getBytes(StandardCharsets.UTF_8),
            new TypeRef<Map<String, Integer>>() {}),
        Collections.<String, Integer>singletonMap("key", null));
    assertEquals(
        json.fromJson("{\"key\":null}", new TypeRef<Map<String, Long>>() {}),
        Collections.<String, Long>singletonMap("key", null));
    assertEquals(
        json.fromJson("{\"key\":null}", new TypeRef<Map<String, Short>>() {}),
        Collections.<String, Short>singletonMap("key", null));
    assertEquals(
        json.fromJson("{\"key\":null}", new TypeRef<Map<String, Byte>>() {}),
        Collections.<String, Byte>singletonMap("key", null));
    assertEquals(
        json.fromJson("{\"key\":null}", new TypeRef<Map<String, Float>>() {}),
        Collections.<String, Float>singletonMap("key", null));
    assertEquals(
        json.fromJson("{\"key\":null}", new TypeRef<Map<String, Double>>() {}),
        Collections.<String, Double>singletonMap("key", null));
    assertEquals(
        json.fromJson("{\"key\":null}", new TypeRef<Map<String, BigInteger>>() {}),
        Collections.<String, BigInteger>singletonMap("key", null));
    assertEquals(
        json.fromJson("{\"\u0100\":null}", new TypeRef<Map<String, BigDecimal>>() {}),
        Collections.<String, BigDecimal>singletonMap("\u0100", null));
  }

  @Test
  public void readDeclaredJdkContainers() {
    ForyJson json = newJson();
    DeclaredJdkContainers value =
        json.fromJson(
            "{\"collection\":[\"a\",\"b\"],\"list\":[\"c\"],\"sequential\":[\"d\"],"
                + "\"set\":[\"e\",\"f\"],\"queue\":[\"g\",\"h\"],\"blockingQueue\":[\"i\"],"
                + "\"blockingDeque\":[\"j\"],\"map\":{\"one\":1,\"two\":2}}",
            DeclaredJdkContainers.class);
    assertTrue(value.collection instanceof ArrayList);
    assertEquals(new ArrayList<>(value.collection), Arrays.asList("a", "b"));
    assertTrue(value.list instanceof ArrayList);
    assertEquals(value.list, Collections.singletonList("c"));
    assertTrue(value.sequential instanceof LinkedList);
    assertEquals(value.sequential, Collections.singletonList("d"));
    assertTrue(value.set instanceof LinkedHashSet);
    assertEquals(value.set, new LinkedHashSet<>(Arrays.asList("e", "f")));
    assertTrue(value.queue instanceof LinkedBlockingQueue);
    assertEquals(new ArrayList<>(value.queue), Arrays.asList("g", "h"));
    assertTrue(value.blockingQueue instanceof LinkedBlockingQueue);
    assertEquals(new ArrayList<>(value.blockingQueue), Collections.singletonList("i"));
    assertTrue(value.blockingDeque instanceof LinkedBlockingDeque);
    assertEquals(new ArrayList<>(value.blockingDeque), Collections.singletonList("j"));
    assertTrue(value.map instanceof LinkedHashMap);
    assertEquals(value.map, scores());
  }

  @Test
  public void readMutableContainersUnchanged() {
    ForyJson json = newJson();
    List<String> list = json.fromJson("[\"a\"]", new TypeRef<List<String>>() {});
    assertTrue(list instanceof ArrayList);
    assertEquals(list, Collections.singletonList("a"));

    ArrayList<String> arrayList = json.fromJson("[\"b\"]", new TypeRef<ArrayList<String>>() {});
    assertTrue(arrayList instanceof ArrayList);
    assertEquals(arrayList, Collections.singletonList("b"));

    Set<String> set = json.fromJson("[\"c\"]", new TypeRef<Set<String>>() {});
    assertTrue(set instanceof LinkedHashSet);
    assertEquals(set, Collections.singleton("c"));

    Map<String, Integer> map =
        json.fromJson("{\"one\":1,\"two\":2}", new TypeRef<Map<String, Integer>>() {});
    assertTrue(map instanceof LinkedHashMap);
    assertEquals(map, scores());

    HashMap<String, Integer> hashMap =
        json.fromJson("{\"one\":1,\"two\":2}", new TypeRef<HashMap<String, Integer>>() {});
    assertTrue(hashMap instanceof HashMap);
    assertEquals(hashMap, scores());
  }

  @Test
  public void readEnumContainersStillWork() {
    ForyJson json = newJson();
    EnumContainers value =
        json.fromJson(
            "{\"kinds\":[\"FAST\",\"SMALL\"],\"scores\":{\"FAST\":1}}", EnumContainers.class);
    assertTrue(value.kinds instanceof EnumSet);
    assertEquals(value.kinds, EnumSet.of(Kind.FAST, Kind.SMALL));
    assertTrue(value.scores instanceof EnumMap);
    assertEquals(value.scores.get(Kind.FAST), Integer.valueOf(1));
  }

  @Test
  public void rejectRuntimeContainerClasses() {
    ForyJson json = newJson();
    assertUnsupportedCollectionClass(json, Arrays.asList("a"));
    assertUnsupportedCollectionClass(json, Collections.emptyList());
    assertUnsupportedCollectionClass(json, Collections.singletonList("a"));
    assertUnsupportedCollectionClass(json, Collections.unmodifiableList(new ArrayList<>()));
    jdk9List("a").ifPresent(list -> assertUnsupportedCollectionClass(json, list));
    jdk9Set("a").ifPresent(set -> assertUnsupportedCollectionClass(json, set));
    assertUnsupportedCollectionClass(json, ImmutableList.of("a"));
    assertUnsupportedCollectionClass(json, ImmutableSet.of("a"));
    assertUnsupportedMapClass(json, Collections.emptyMap());
    assertUnsupportedMapClass(json, Collections.singletonMap("one", 1));
    assertUnsupportedMapClass(json, Collections.unmodifiableMap(scores()));
    jdk9Map("one", 1).ifPresent(map -> assertUnsupportedMapClass(json, map));
    assertUnsupportedMapClass(json, ImmutableMap.of("one", 1));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("[\"a\"]", new TypeRef<ArrayBlockingQueue<String>>() {}));
  }

  @Test
  public void readGuavaImmutableContainers() {
    ForyJson json = newJson();
    GuavaContainers value =
        json.fromJson(
            "{\"list\":[\"a\",\"b\"],\"set\":[\"c\",\"d\"],"
                + "\"map\":{\"one\":1,\"two\":2},\"biMap\":{\"left\":1,\"right\":2},"
                + "\"sortedSet\":[\"b\",\"a\"],\"sortedMap\":{\"two\":2,\"one\":1},"
                + "\"ints\":[1,2,3]}",
            GuavaContainers.class);
    assertEquals(value.list, ImmutableList.of("a", "b"));
    assertEquals(value.set, ImmutableSet.of("c", "d"));
    assertEquals(value.map, ImmutableMap.of("one", 1, "two", 2));
    assertEquals(value.biMap, ImmutableBiMap.of("left", 1, "right", 2));
    assertEquals(value.sortedSet, ImmutableSortedSet.of("a", "b"));
    assertEquals(value.sortedMap, ImmutableSortedMap.of("one", 1, "two", 2));
    assertEquals(value.ints, ImmutableIntArray.of(1, 2, 3));
    assertEquals(
        json.fromJson("[\"x\"]", new TypeRef<ImmutableList<String>>() {}), ImmutableList.of("x"));
    assertEquals(json.fromJson("[4,5]", ImmutableIntArray.class), ImmutableIntArray.of(4, 5));
    assertEquals(json.fromJson(json.toJsonBytes(value.ints), ImmutableIntArray.class), value.ints);
  }

  @Test
  public void rejectGuavaImmutableNulls() {
    ForyJson json = newJson();
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("[\"a\",null]", new TypeRef<ImmutableList<String>>() {}));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"one\":null}", new TypeRef<ImmutableMap<String, Integer>>() {}));
    assertThrows(ForyJsonException.class, () -> json.fromJson("[1,null]", ImmutableIntArray.class));
  }

  @Test
  public void writeReadFastContainerFields() {
    ForyJson json = newJson();
    String input =
        "{\"booleans\":[true,false],\"flags\":{\"enabled\":true,\"disabled\":false},"
            + "\"intNames\":{\"1\":\"one\",\"2\":\"two\"},\"ints\":[1,2],"
            + "\"names\":[\"alpha\",\"你好，Fory\"],\"scores\":{\"one\":1,\"two\":2}}";
    FastContainers read = json.fromJson(input, FastContainers.class);
    assertFastContainers(read);
    assertFastContainers(
        json.fromJson(json.toJsonBytes(new FastContainers()), FastContainers.class));
  }

  @Test
  public void readPrimitiveArrayRoots() {
    ForyJson json = newJson();
    assertEquals(json.toJson(new int[] {1, 2}), "[1,2]");
    assertEquals(json.fromJson("[1,2]", int[].class), new int[] {1, 2});
    assertEquals(
        json.fromJson("[1,2]".getBytes(StandardCharsets.UTF_8), int[].class), new int[] {1, 2});
    assertEquals(
        json.fromJson("[true,false]".getBytes(StandardCharsets.UTF_8), boolean[].class),
        new boolean[] {true, false});
    assertEquals(json.fromJson("[1,-2,3]", byte[].class), new byte[] {1, -2, 3});
    assertEquals(json.fromJson("[\"a\",\"你\"]", char[].class), new char[] {'a', '你'});
    assertThrows(ForyJsonException.class, () -> json.fromJson("[1,null]", int[].class));
  }

  @Test
  public void readStringArrays() {
    ForyJson json = newJson();
    assertStringArrayPaths(json, "[\"a\"]", new String[] {"a"});
    assertStringArrayPaths(
        json,
        "[\"a\",null,\"b\",\"c\",\"d\",\"e\",\"f\",\"g\"]",
        new String[] {"a", null, "b", "c", "d", "e", "f", "g"});
    assertStringArrayPaths(
        json,
        "[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\"]",
        new String[] {"a", "b", "c", "d", "e", "f", "g", "h", "i"});
    assertStringArrayPaths(
        json,
        "[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\",\"" + ZH_TEXT + "\"]",
        new String[] {"a", "b", "c", "d", "e", "f", "g", "h", "i", ZH_TEXT});
  }

  @Test
  public void readLongArrays() {
    ForyJson json = newJson();
    assertLongArrayPaths(json, "[7]", new long[] {7L});
    assertLongArrayPaths(json, "[1,2,3,4,5,6,7,8]", new long[] {1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L});
    assertLongArrayPaths(
        json, "[1,2,3,4,5,6,7,8,9]", new long[] {1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L});
    LongArrayUtf16Root root =
        json.fromJson(
            "{\"values\":[1,2,3,4,5,6,7,8,9],\"text\":\"" + ZH_TEXT + "\"}",
            LongArrayUtf16Root.class);
    assertEquals(root.values, new long[] {1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L});
    assertEquals(root.text, ZH_TEXT);
    assertThrows(ForyJsonException.class, () -> json.fromJson("[1,null]", long[].class));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("[1,null]".getBytes(StandardCharsets.UTF_8), long[].class));
  }

  @Test
  public void writeReadBoxedPrimitiveArrays() {
    ForyJson json = newJson();
    assertEquals(json.toJson(new Integer[] {1, null, -2}), "[1,null,-2]");
    assertEquals(
        json.fromJson("[1,null,-2]".getBytes(StandardCharsets.UTF_8), Integer[].class),
        new Integer[] {1, null, -2});
    assertEquals(
        json.fromJson("[9223372036854775807,null,-9]", Long[].class),
        new Long[] {Long.MAX_VALUE, null, -9L});
    assertEquals(
        json.fromJson("[true,null,false]".getBytes(StandardCharsets.UTF_8), Boolean[].class),
        new Boolean[] {Boolean.TRUE, null, Boolean.FALSE});
    assertEquals(
        json.fromJson("[32767,null,-32768]", Short[].class),
        new Short[] {Short.MAX_VALUE, null, Short.MIN_VALUE});
    assertEquals(
        json.fromJson("[127,null,-128]", Byte[].class),
        new Byte[] {Byte.MAX_VALUE, null, Byte.MIN_VALUE});
    assertEquals(
        json.fromJson("[\"a\",null,\"你\"]", Character[].class), new Character[] {'a', null, '你'});
    assertEquals(
        json.fromJson("[1.5,null,-2.25]", Float[].class), new Float[] {1.5f, null, -2.25f});
    assertEquals(
        json.fromJson("[1.5,null,-2.25]".getBytes(StandardCharsets.UTF_8), Double[].class),
        new Double[] {1.5d, null, -2.25d});
    assertEquals(
        new String(json.toJsonBytes(new Character[] {'x', null, '文'}), StandardCharsets.UTF_8),
        "[\"x\",null,\"文\"]");

    assertThrows(ForyJsonException.class, () -> json.fromJson("[128]", Byte[].class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("[\"ab\"]", Character[].class));
  }

  @Test
  public void readReferenceObjectArrays() {
    ForyJson json = newJson();
    Note[] notes = json.fromJson("[{\"title\":\"one\"},null,{\"title\":\"two\"}]", Note[].class);
    assertEquals(notes.getClass(), Note[].class);
    assertEquals(notes.length, 3);
    assertEquals(notes[0].title, "one");
    assertEquals(notes[1], null);
    assertEquals(notes[2].title, "two");

    Note[] empty = json.fromJson("[]".getBytes(StandardCharsets.UTF_8), Note[].class);
    assertEquals(empty.getClass(), Note[].class);
    assertEquals(empty.length, 0);

    Note[][] grid =
        json.fromJson(
            "[[{\"title\":\"left\"}],null,[{\"title\":\"right\"},null]]"
                .getBytes(StandardCharsets.UTF_8),
            Note[][].class);
    assertEquals(grid.getClass(), Note[][].class);
    assertEquals(grid.length, 3);
    assertEquals(grid[0].getClass(), Note[].class);
    assertEquals(grid[0][0].title, "left");
    assertEquals(grid[1], null);
    assertEquals(grid[2].getClass(), Note[].class);
    assertEquals(grid[2][0].title, "right");
    assertEquals(grid[2][1], null);
  }

  @Test
  public void readReentrantObjectArray() {
    ForyJson json = newJson();
    ArrayNode[] roots =
        json.fromJson(
            "[{\"name\":\"root\",\"children\":[{\"name\":\"leaf\",\"children\":[]}]}]"
                .getBytes(StandardCharsets.UTF_8),
            ArrayNode[].class);
    assertEquals(roots.getClass(), ArrayNode[].class);
    assertEquals(roots.length, 1);
    assertEquals(roots[0].name, "root");
    assertEquals(roots[0].children.getClass(), ArrayNode[].class);
    assertEquals(roots[0].children.length, 1);
    assertEquals(roots[0].children[0].name, "leaf");
    assertEquals(roots[0].children[0].children.getClass(), ArrayNode[].class);
    assertEquals(roots[0].children[0].children.length, 0);

    ArrayNode[] deep =
        json.fromJson(arrayNodeJson(9).getBytes(StandardCharsets.UTF_8), ArrayNode[].class);
    assertEquals(deep.getClass(), ArrayNode[].class);
    assertEquals(deep.length, 1);
    ArrayNode node = deep[0];
    for (int i = 0; i < 9; i++) {
      assertEquals(node.name, "node-" + i);
      assertEquals(node.children.getClass(), ArrayNode[].class);
      if (i == 8) {
        assertEquals(node.children.length, 0);
      } else {
        assertEquals(node.children.length, 1);
        node = node.children[0];
      }
    }
  }

  @Test
  public void writeReadAtomicArrays() {
    ForyJson json = newJson();
    assertEquals(json.toJson(new AtomicIntegerArray(new int[] {1, -2, 3})), "[1,-2,3]");
    assertAtomicInts(json.fromJson("[1,-2,3]", AtomicIntegerArray.class), 1, -2, 3);
    assertEquals(
        new String(
            json.toJsonBytes(new AtomicLongArray(new long[] {4L, -5L})), StandardCharsets.UTF_8),
        "[4,-5]");
    assertAtomicLongs(
        json.fromJson("[4,-5]".getBytes(StandardCharsets.UTF_8), AtomicLongArray.class), 4L, -5L);

    AtomicReferenceArray<String> refs =
        new AtomicReferenceArray<>(new String[] {"a", null, ZH_TEXT});
    assertEquals(json.toJson(refs), "[\"a\",null,\"你好，Fory\"]");
    assertAtomicRefs(
        json.fromJson(
            "[\"a\",null,\"你好，Fory\"]".getBytes(StandardCharsets.UTF_8),
            new TypeRef<AtomicReferenceArray<String>>() {}),
        "a",
        null,
        ZH_TEXT);

    String fieldsJson = "{\"ints\":[7,8],\"longs\":[9,-10],\"refs\":[\"b\",null]}";
    AtomicArrayFields fields =
        json.fromJson(fieldsJson.getBytes(StandardCharsets.UTF_8), AtomicArrayFields.class);
    assertAtomicInts(fields.ints, 7, 8);
    assertAtomicLongs(fields.longs, 9L, -10L);
    assertAtomicRefs(fields.refs, "b", null);
    assertEquals(json.toJson(fields), fieldsJson);

    assertThrows(
        ForyJsonException.class, () -> json.fromJson("[1,null]", AtomicIntegerArray.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("[1,null]", AtomicLongArray.class));
  }

  public static final class Shelf {
    public NoteList notes;
  }

  public static final class GenericBox<T> {
    public T value;
    public List<T> values;
  }

  public static final class NoteList extends ArrayList<Note> {}

  public static final class Note {
    public String title;
  }

  public static final class ArrayNode {
    public String name;
    public ArrayNode[] children;
  }

  public static final class PaletteGroups extends HashMap<String, PaletteCodes> {}

  public static final class PaletteCodes extends HashMap<String, String> {}

  public static final class DeclaredJdkContainers {
    public AbstractCollection<String> collection;
    public AbstractList<String> list;
    public AbstractSequentialList<String> sequential;
    public AbstractSet<String> set;
    public AbstractQueue<String> queue;
    public BlockingQueue<String> blockingQueue;
    public BlockingDeque<String> blockingDeque;
    public AbstractMap<String, Integer> map;
  }

  public static final class EnumContainers {
    public EnumSet<Kind> kinds;
    public EnumMap<Kind, Integer> scores;
  }

  public static final class GuavaContainers {
    public ImmutableList<String> list;
    public ImmutableSet<String> set;
    public ImmutableMap<String, Integer> map;
    public ImmutableBiMap<String, Integer> biMap;
    public ImmutableSortedSet<String> sortedSet;
    public ImmutableSortedMap<String, Integer> sortedMap;
    public ImmutableIntArray ints;
  }

  public static final class AtomicArrayFields {
    public AtomicIntegerArray ints = new AtomicIntegerArray(new int[] {7, 8});
    public AtomicLongArray longs = new AtomicLongArray(new long[] {9L, -10L});
    public AtomicReferenceArray<String> refs = new AtomicReferenceArray<>(new String[] {"b", null});
  }

  public static final class LongArrayUtf16Root {
    public long[] values;
    public String text;
  }

  private static void assertStringArrayPaths(ForyJson json, String input, String[] expected) {
    assertEquals(json.fromJson(input, String[].class), expected);
    assertEquals(json.fromJson(input.getBytes(StandardCharsets.UTF_8), String[].class), expected);
  }

  private static void assertLongArrayPaths(ForyJson json, String input, long[] expected) {
    assertEquals(json.fromJson(input, long[].class), expected);
    assertEquals(json.fromJson(input.getBytes(StandardCharsets.UTF_8), long[].class), expected);
  }

  private static String arrayNodeJson(int depth) {
    return "[" + nodeJson(0, depth) + "]";
  }

  private static String nodeJson(int index, int depth) {
    String children = index + 1 == depth ? "[]" : "[" + nodeJson(index + 1, depth) + "]";
    return "{\"name\":\"node-" + index + "\",\"children\":" + children + "}";
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void assertUnsupportedCollectionClass(ForyJson json, Object value) {
    assertThrows(ForyJsonException.class, () -> json.fromJson("[\"a\"]", (Class) value.getClass()));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void assertUnsupportedMapClass(ForyJson json, Object value) {
    assertThrows(
        ForyJsonException.class, () -> json.fromJson("{\"one\":1}", (Class) value.getClass()));
  }

  @SuppressWarnings("unchecked")
  private static Optional<List<String>> jdk9List(String value) {
    try {
      Method factory = List.class.getMethod("of", Object[].class);
      return Optional.of((List<String>) factory.invoke(null, new Object[] {new Object[] {value}}));
    } catch (NoSuchMethodException e) {
      return Optional.empty();
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Optional<Set<String>> jdk9Set(String value) {
    try {
      Method factory = Set.class.getMethod("of", Object[].class);
      return Optional.of((Set<String>) factory.invoke(null, new Object[] {new Object[] {value}}));
    } catch (NoSuchMethodException e) {
      return Optional.empty();
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Optional<Map<String, Integer>> jdk9Map(String key, int value) {
    try {
      Method factory = Map.class.getMethod("of", Object.class, Object.class);
      return Optional.of((Map<String, Integer>) factory.invoke(null, key, value));
    } catch (NoSuchMethodException e) {
      return Optional.empty();
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static void assertAtomicInts(AtomicIntegerArray array, int... expected) {
    assertEquals(array.length(), expected.length);
    for (int i = 0; i < expected.length; i++) {
      assertEquals(array.get(i), expected[i]);
    }
  }

  private static void assertAtomicLongs(AtomicLongArray array, long... expected) {
    assertEquals(array.length(), expected.length);
    for (int i = 0; i < expected.length; i++) {
      assertEquals(array.get(i), expected[i]);
    }
  }

  private static void assertAtomicRefs(AtomicReferenceArray<String> array, String... expected) {
    assertEquals(array.length(), expected.length);
    for (int i = 0; i < expected.length; i++) {
      assertEquals(array.get(i), expected[i]);
    }
  }
}

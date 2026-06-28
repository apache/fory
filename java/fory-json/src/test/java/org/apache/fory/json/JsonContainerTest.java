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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.fory.json.data.FastContainers;
import org.apache.fory.json.data.MapKeyFields;
import org.apache.fory.json.data.Nested;
import org.apache.fory.json.data.TokenValues;
import org.apache.fory.reflect.TypeRef;
import org.testng.annotations.Test;

public class JsonContainerTest extends ForyJsonTestModels {
  @Test
  public void writeNestedCollections() {
    ForyJson json = ForyJson.builder().build();
    assertEquals(
        json.toJson(new Nested()),
        "{\"kind\":\"FAST\",\"names\":[\"a\",\"b\"],\"scores\":{\"one\":1,\"two\":2}}");
  }

  @Test
  public void readTypeRefList() {
    ForyJson json = ForyJson.builder().build();
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
  public void readTypeRefMapBytes() {
    ForyJson json = ForyJson.builder().build();
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
    ForyJson json = ForyJson.builder().build();
    JSONObject object =
        json.fromJson("{\"name\":\"fory\",\"items\":[1,\"你好，Fory\"]}", JSONObject.class);
    assertEquals(object.get("name"), "fory");
    assertTrue(object.get("items") instanceof JSONArray);
    JSONArray items = (JSONArray) object.get("items");
    assertEquals(items.get(0), Long.valueOf(1));
    assertEquals(items.get(1), ZH_TEXT);

    Object natural = json.fromJson("{\"items\":[true]}", Object.class);
    assertTrue(natural instanceof JSONObject);
    assertTrue(((JSONObject) natural).get("items") instanceof JSONArray);
  }

  @Test
  public void parsedContainersStartSmall() {
    ForyJson json = ForyJson.builder().build();
    JSONArray array = json.fromJson("[1]", JSONArray.class);
    assertEquals(arrayCapacity(array), 1);
    assertEquals(arrayCapacity(json.fromJson("[]", JSONArray.class)), 0);

    List<Object> list = json.fromJson("[1]", new TypeRef<List<Object>>() {});
    assertTrue(list instanceof ArrayList);
    assertEquals(arrayCapacity((ArrayList<?>) list), 1);

    JSONObject object = json.fromJson("{\"x\":1}", JSONObject.class);
    assertEquals(mapCapacity(object), 2);
    assertEquals(mapCapacity(json.fromJson("{}", JSONObject.class)), 0);

    Map<String, Object> map = json.fromJson("{\"x\":1}", new TypeRef<Map<String, Object>>() {});
    assertTrue(map instanceof LinkedHashMap);
    assertEquals(mapCapacity((LinkedHashMap<?, ?>) map), 2);
  }

  @Test
  public void writeJsonContainers() {
    ForyJson json = ForyJson.builder().build();
    JSONObject object = new JSONObject();
    JSONArray values = new JSONArray();
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
    ForyJson json = ForyJson.builder().build();
    String expected =
        "{\"intNames\":{\"1\":\"one\",\"2\":\"two\"},\"scores\":{\"FAST\":1,\"SMALL\":2}}";
    assertEquals(json.toJson(new MapKeyFields()), expected);
    MapKeyFields read = json.fromJson(expected, MapKeyFields.class);
    assertEquals(read.intNames, intNames());
    assertEquals(read.scores, enumScores());
  }

  @Test
  public void readTypeRefOptional() {
    ForyJson json = ForyJson.builder().build();
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
    ForyJson json = ForyJson.builder().build();
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
    ForyJson json = ForyJson.builder().build();
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
  public void writeReadFastContainerFields() {
    ForyJson json = ForyJson.builder().build();
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
    ForyJson json = ForyJson.builder().build();
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
}

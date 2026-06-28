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
import java.util.Map;
import org.apache.fory.json.data.DepthNode;
import org.apache.fory.reflect.TypeRef;
import org.testng.annotations.Test;

public class JsonDepthTest extends ForyJsonTestModels {
  @Test
  public void defaultMaxDepth() {
    ForyJson json = ForyJson.builder().build();
    assertTrue(
        json.fromJson(nestedArray(ForyJson.DEFAULT_MAX_DEPTH), Object.class) instanceof JSONArray);
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson(nestedArray(ForyJson.DEFAULT_MAX_DEPTH + 1), Object.class));
  }

  @Test
  public void readMaxDepth() {
    ForyJson json = ForyJson.builder().maxDepth(2).build();
    assertEquals(json.fromJson("{\"child\":{\"value\":1}}", DepthNode.class).child.value, 1);
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"child\":{\"child\":{\"value\":1}}}", DepthNode.class));
    assertThrows(
        ForyJsonException.class,
        () ->
            json.fromJson(
                "{\"child\":{\"child\":{\"value\":1}}}".getBytes(StandardCharsets.UTF_8),
                DepthNode.class));
  }

  @Test
  public void readContainerMaxDepth() {
    ForyJson json = ForyJson.builder().maxDepth(2).build();
    assertTrue(json.fromJson("[[1]]", Object.class) instanceof JSONArray);
    assertThrows(ForyJsonException.class, () -> json.fromJson("[[[1]]]", Object.class));
    assertEquals(
        json.fromJson("{\"a\":{\"b\":1}}", new TypeRef<Map<String, Object>>() {}).size(), 1);
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"a\":{\"b\":{\"c\":1}}}", new TypeRef<Map<String, Object>>() {}));

    ForyJson nestedJson = ForyJson.builder().maxDepth(3).build();
    assertEquals(
        nestedJson
            .fromJson("{\"children\":[{\"value\":2}]}", DepthNode.class)
            .children
            .get(0)
            .value,
        2);
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"children\":[{\"value\":2}]}", DepthNode.class));
    assertEquals(
        nestedJson
            .fromJson("{\"nodes\":{\"a\":{\"value\":3}}}", DepthNode.class)
            .nodes
            .get("a")
            .value,
        3);
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"nodes\":{\"a\":{\"value\":3}}}", DepthNode.class));
  }

  @Test
  public void readJsonObjectMaxDepth() {
    ForyJson json = ForyJson.builder().maxDepth(2).build();
    JSONObject object = json.fromJson("{\"items\":[1]}", JSONObject.class);
    assertTrue(object.get("items") instanceof JSONArray);
    assertThrows(
        ForyJsonException.class, () -> json.fromJson("{\"items\":[{}]}", JSONObject.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("[[{}]]", JSONArray.class));
  }

  @Test
  public void readDepthReset() {
    ForyJson json = ForyJson.builder().maxDepth(1).build();
    assertThrows(
        ForyJsonException.class, () -> json.fromJson("{\"child\":{\"value\":1}}", DepthNode.class));
    assertEquals(json.fromJson("{\"value\":2}", DepthNode.class).value, 2);
    assertThrows(
        ForyJsonException.class,
        () ->
            json.fromJson(
                "{\"child\":{\"value\":1}}".getBytes(StandardCharsets.UTF_8), DepthNode.class));
    assertEquals(
        json.fromJson("{\"value\":3}".getBytes(StandardCharsets.UTF_8), DepthNode.class).value, 3);
  }

  @Test
  public void rejectInvalidMaxDepth() {
    assertThrows(IllegalArgumentException.class, () -> ForyJson.builder().maxDepth(0));
  }
}

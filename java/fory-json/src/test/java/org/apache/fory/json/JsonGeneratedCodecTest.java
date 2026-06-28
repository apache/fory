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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.apache.fory.json.data.GeneratedCollectionFields;
import org.apache.fory.json.data.RecursiveChild;
import org.apache.fory.json.data.RecursiveParent;
import org.apache.fory.json.data.TokenGroup;
import org.apache.fory.json.data.TokenValues;
import org.testng.annotations.Test;

public class JsonGeneratedCodecTest extends ForyJsonTestModels {
  @Test
  public void writeRecursiveGeneratedTypes() {
    ForyJson json = ForyJson.builder().build();
    RecursiveParent value = new RecursiveParent();
    assertEquals(json.toJson(value), "{\"child\":{\"name\":\"child\"},\"name\":\"parent\"}");
    assertEquals(
        new String(json.toJsonBytes(value), StandardCharsets.UTF_8),
        "{\"child\":{\"name\":\"child\"},\"name\":\"parent\"}");
    assertGeneratedWhenSupported(json, RecursiveParent.class);
    assertGeneratedWhenSupported(json, RecursiveChild.class);
  }

  @Test
  public void writeGeneratedTokenChanges() {
    ForyJson json = ForyJson.builder().build();
    TokenValues value = new TokenValues();
    String first = "{\"count\":1,\"name\":\"alpha\",\"tags\":[\"x\",\"y\"],\"total\":2}";
    assertEquals(json.toJson(value), first);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), first);
    assertEquals(json.toJson(value), first);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), first);
    value.count = 7;
    value.name = "beta";
    value.tags = Arrays.asList("z", "x");
    value.total = 9;
    String second = "{\"count\":7,\"name\":\"beta\",\"tags\":[\"z\",\"x\"],\"total\":9}";
    assertEquals(json.toJson(value), second);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), second);
    assertGeneratedWhenSupported(json, TokenValues.class);
  }

  @Test
  public void writeGeneratedTokenLanes() {
    ForyJson json = ForyJson.builder().build();
    TokenGroup group = new TokenGroup();
    group.values =
        Arrays.asList(
            tokenValue(1, "alpha", Arrays.asList("x", "y"), 2),
            tokenValue(3, "beta", Arrays.asList("z", "x"), 4),
            tokenValue(5, "gamma", Arrays.asList("y", "z"), 6));
    String first =
        "{\"values\":[{\"count\":1,\"name\":\"alpha\",\"tags\":[\"x\",\"y\"],\"total\":2},"
            + "{\"count\":3,\"name\":\"beta\",\"tags\":[\"z\",\"x\"],\"total\":4},"
            + "{\"count\":5,\"name\":\"gamma\",\"tags\":[\"y\",\"z\"],\"total\":6}]}";
    assertEquals(json.toJson(group), first);
    assertEquals(new String(json.toJsonBytes(group), StandardCharsets.UTF_8), first);
    assertEquals(json.toJson(group), first);
    assertEquals(new String(json.toJsonBytes(group), StandardCharsets.UTF_8), first);
    TokenValues middle = group.values.get(1);
    middle.count = 7;
    middle.name = "delta";
    middle.tags = Arrays.asList("q", "x");
    middle.total = 8;
    String second =
        "{\"values\":[{\"count\":1,\"name\":\"alpha\",\"tags\":[\"x\",\"y\"],\"total\":2},"
            + "{\"count\":7,\"name\":\"delta\",\"tags\":[\"q\",\"x\"],\"total\":8},"
            + "{\"count\":5,\"name\":\"gamma\",\"tags\":[\"y\",\"z\"],\"total\":6}]}";
    assertEquals(json.toJson(group), second);
    assertEquals(new String(json.toJsonBytes(group), StandardCharsets.UTF_8), second);
    assertGeneratedWhenSupported(json, TokenGroup.class);
    assertGeneratedWhenSupported(json, TokenValues.class);
  }

  @Test
  public void readGeneratedObjectCollection() {
    ForyJson json = ForyJson.builder().build();
    String input = "{\"values\":[{\"count\":1,\"name\":\"alpha\",\"tags\":[\"x\"],\"total\":2}]}";
    TokenGroup stringValue = json.fromJson(input, TokenGroup.class);
    TokenGroup utf8Value = json.fromJson(input.getBytes(StandardCharsets.UTF_8), TokenGroup.class);
    assertEquals(stringValue.values.size(), 1);
    assertEquals(stringValue.values.get(0).name, "alpha");
    assertEquals(stringValue.values.get(0).tags, Arrays.asList("x"));
    assertEquals(utf8Value.values.size(), 1);
    assertEquals(utf8Value.values.get(0).total, 2);
    assertGeneratedWhenSupported(json, TokenGroup.class);
    assertGeneratedWhenSupported(json, TokenValues.class);
  }

  @Test
  public void readGeneratedCollectionFields() {
    ForyJson json = ForyJson.builder().build();
    String input =
        "{\"kinds\":[\"FAST\",\"SMALL\"],\"names\":[\"alpha\",\"你好，Fory\"]," + "\"numbers\":[1,2]}";
    assertGeneratedCollections(json.fromJson(input, GeneratedCollectionFields.class));
    assertGeneratedCollections(
        json.fromJson(input.getBytes(StandardCharsets.UTF_8), GeneratedCollectionFields.class));
    assertGeneratedWhenSupported(json, GeneratedCollectionFields.class);
  }
}

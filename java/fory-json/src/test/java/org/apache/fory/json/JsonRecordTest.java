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

import static org.apache.fory.json.JsonTestSupport.nullCodec;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.fory.platform.JdkVersion;
import org.testng.SkipException;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class JsonRecordTest extends ForyJsonTestModels {
  @Factory(dataProvider = "enableCodegen")
  public JsonRecordTest(boolean codegen) {
    super(codegen);
  }

  @Test
  public void writeReadRecordClass() throws Exception {
    if (JdkVersion.MAJOR_VERSION < 17) {
      throw new SkipException("Java record test requires JDK 17+");
    }
    Class<?> type =
        compileRecordClass(
            "JsonRecordValue",
            "package org.apache.fory.json.records;\n"
                + "import java.util.List;\n"
                + "public record JsonRecordValue(int id, String name, List<String> tags, "
                + "Child child) {\n"
                + "  public record Child(String label) {}\n"
                + "}\n");
    Class<?> childType = Class.forName(type.getName() + "$Child", true, type.getClassLoader());
    Object child = childType.getConstructor(String.class).newInstance("kid");
    Object value =
        type.getConstructor(int.class, String.class, List.class, childType)
            .newInstance(7, ZH_TEXT, Arrays.asList("a", "b"), child);
    ForyJson json = newJson();
    String expected =
        "{\"id\":7,\"name\":\"你好，Fory\",\"tags\":[\"a\",\"b\"]," + "\"child\":{\"label\":\"kid\"}}";
    assertEquals(json.toJson(value), expected);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), expected);
    assertGeneratedWhenSupported(json, type);
    assertRecordValue(json.fromJson(expected, type), childType);
    assertRecordValue(json.fromJson(expected.getBytes(StandardCharsets.UTF_8), type), childType);

    Object missing = json.fromJson("{\"name\":\"missing\"}", type);
    assertEquals(type.getMethod("id").invoke(missing), Integer.valueOf(0));
    assertEquals(type.getMethod("name").invoke(missing), "missing");
    assertEquals(type.getMethod("tags").invoke(missing), null);
    assertEquals(type.getMethod("child").invoke(missing), null);
  }

  @Test
  public void customPrimitiveNull() throws Exception {
    if (JdkVersion.MAJOR_VERSION < 17) {
      throw new SkipException("Java record test requires JDK 17+");
    }
    Class<?> type =
        compileRecordClass(
            "JsonPrimitiveRecord",
            "package org.apache.fory.json.records;\n"
                + "public record JsonPrimitiveRecord(int value) {}\n");
    ForyJson json = newJsonBuilder().registerCodec(int.class, nullCodec()).build();
    assertThrows(ForyJsonException.class, () -> json.fromJson("{\"value\":null}", type));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"ignored\":\"\u0100\",\"value\":null}", type));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"value\":null}".getBytes(StandardCharsets.UTF_8), type));
    assertGeneratedWhenSupported(json, type);
  }

  @Test
  public void renamedRecordComponent() throws Exception {
    if (JdkVersion.MAJOR_VERSION < 17) {
      throw new SkipException("Java record test requires JDK 17+");
    }
    Class<?> type =
        compileRecordClass(
            "JsonRenamedRecord",
            "package org.apache.fory.json.records;\n"
                + "import org.apache.fory.json.annotation.JsonProperty;\n"
                + "public record JsonRenamedRecord(@JsonProperty(\"user_id\") int id, "
                + "String displayName) {}\n");
    Object value = type.getConstructor(int.class, String.class).newInstance(7, "alice");
    ForyJson json =
        newJsonBuilder().withPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE).build();
    String text = "{\"user_id\":7,\"display_name\":\"alice\"}";
    assertEquals(json.toJson(value), text);
    Object decoded = json.fromJson(text, type);
    assertEquals(type.getMethod("id").invoke(decoded), Integer.valueOf(7));
    assertEquals(type.getMethod("displayName").invoke(decoded), "alice");
  }

  @Test
  public void canonicalCreator() throws Exception {
    if (JdkVersion.MAJOR_VERSION < 17) {
      throw new SkipException("Java record test requires JDK 17+");
    }
    Class<?> emptyType =
        compileRecordClass(
            "JsonEmptyRecord",
            "package org.apache.fory.json.records;\npublic record JsonEmptyRecord() {}\n");
    ForyJson json = newJson();
    assertEquals(json.toJson(emptyType.getConstructor().newInstance()), "{}");
    assertEquals(json.fromJson("{}", emptyType), emptyType.getConstructor().newInstance());

    Class<?> type =
        compileRecordClass(
            "JsonCanonicalRecord",
            "package org.apache.fory.json.records;\n"
                + "public record JsonCanonicalRecord(int id, String name) {\n"
                + "  public JsonCanonicalRecord { name = name.trim(); }\n"
                + "  public JsonCanonicalRecord(int id) { this(id, \"aux\"); }\n"
                + "  @Override public String name() { return name.toUpperCase(); }\n"
                + "}\n");
    Object value = type.getConstructor(int.class, String.class).newInstance(9, " value ");
    assertEquals(json.toJson(value), "{\"id\":9,\"name\":\"VALUE\"}");
    Object decoded = json.fromJson("{\"id\":10,\"name\":\" next \"}", type);
    assertEquals(type.getMethod("id").invoke(decoded), Integer.valueOf(10));
    assertEquals(type.getMethod("name").invoke(decoded), "NEXT");
  }

  @Test
  public void ignoredComponent() throws Exception {
    if (JdkVersion.MAJOR_VERSION < 17) {
      throw new SkipException("Java record test requires JDK 17+");
    }
    Class<?> type =
        compileRecordClass(
            "JsonIgnoredRecord",
            "package org.apache.fory.json.records;\n"
                + "import java.util.Map;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "public record JsonIgnoredRecord(\n"
                + "    @JsonIgnore(ignoreWrite = false) int id,\n"
                + "    @JsonAnyProperty Map<String, Object> extra) {}\n");
    ForyJson json = newJson();
    byte[] bytes = "{\"id\":9,\"unknown\":1}".getBytes(StandardCharsets.UTF_8);
    for (Object decoded :
        Arrays.asList(
            json.fromJson(new String(bytes, StandardCharsets.UTF_8), type),
            json.fromJson(bytes, type))) {
      assertEquals(type.getMethod("id").invoke(decoded), Integer.valueOf(0));
      Map<?, ?> extra = (Map<?, ?>) type.getMethod("extra").invoke(decoded);
      assertEquals(extra.size(), 1);
      assertTrue(extra.containsKey("unknown"));
    }
  }
}

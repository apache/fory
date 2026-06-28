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
import java.util.List;
import org.apache.fory.platform.JdkVersion;
import org.testng.SkipException;
import org.testng.annotations.Test;

public class JsonRecordTest extends ForyJsonTestModels {
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
    ForyJson json = ForyJson.builder().build();
    String expected =
        "{\"child\":{\"label\":\"kid\"},\"id\":7,\"name\":\"你好，Fory\"," + "\"tags\":[\"a\",\"b\"]}";
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
}

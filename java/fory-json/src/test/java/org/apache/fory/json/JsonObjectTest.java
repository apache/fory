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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.fory.json.data.DeclaredParentField;
import org.apache.fory.json.data.DirectionalIgnore;
import org.apache.fory.json.data.FirstIntField;
import org.apache.fory.json.data.MethodsIgnored;
import org.apache.fory.json.data.ParentValue;
import org.apache.fory.json.data.PrivateFields;
import org.apache.fory.json.data.PublicFields;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class JsonObjectTest extends ForyJsonTestModels {
  @Factory(dataProvider = "enableCodegen")
  public JsonObjectTest(boolean codegen) {
    super(codegen);
  }

  @Test
  public void writeJsonToOutputStream() {
    ForyJson json = newJson();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    json.writeJsonTo(new PublicFields(), output);
    assertEquals(
        new String(output.toByteArray(), StandardCharsets.UTF_8),
        "{\"active\":true,\"id\":7,\"name\":\"fory\"}");

    output.reset();
    json.writeJsonTo(null, output);
    assertEquals(new String(output.toByteArray(), StandardCharsets.UTF_8), "null");

    OutputStream failing =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            throw new IOException("closed");
          }

          @Override
          public void write(byte[] b, int off, int len) throws IOException {
            throw new IOException("closed");
          }
        };
    assertThrows(ForyJsonException.class, () -> json.writeJsonTo(new PublicFields(), failing));
  }

  @Test
  public void writeFirstIntGenerated() {
    ForyJson json = newJson();
    String expected = "{\"count\":2,\"name\":\"first\"}";
    assertEquals(json.toJson(new FirstIntField()), expected);
    assertEquals(
        new String(json.toJsonBytes(new FirstIntField()), StandardCharsets.UTF_8), expected);
    assertGeneratedWhenSupported(json, FirstIntField.class);
  }

  @Test
  public void sharedFacadeThreads() throws Exception {
    ForyJson json = newJson();
    String expected = "{\"active\":true,\"id\":7,\"name\":\"fory\"}";
    int threads = 8;
    int iterations = 200;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();
    try {
      for (int t = 0; t < threads; t++) {
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  for (int i = 0; i < iterations; i++) {
                    assertEquals(json.toJson(new PublicFields()), expected);
                    assertEquals(
                        new String(json.toJsonBytes(new PublicFields()), StandardCharsets.UTF_8),
                        expected);
                    PublicFields value = json.fromJson(expected, PublicFields.class);
                    assertEquals(value.name, "fory");
                    assertEquals(value.id, 7);
                    assertEquals(value.active, true);
                  }
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> future : futures) {
        future.get();
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void publicFieldPaths() {
    ForyJson json = newJson();
    String expected = "{\"active\":true,\"id\":7,\"name\":\"fory\"}";
    assertEquals(json.toJson(new PublicFields()), expected);
    assertEquals(
        new String(json.toJsonBytes(new PublicFields()), StandardCharsets.UTF_8), expected);

    PublicFields latin1 = json.fromJson(expected, PublicFields.class);
    assertEquals(latin1.active, true);
    assertEquals(latin1.id, 7);
    assertEquals(latin1.name, "fory");

    PublicFields utf16 =
        json.fromJson(
            "{\"ignored\":\"" + ZH_TEXT + "\",\"active\":false,\"id\":8,\"name\":\"json\"}",
            PublicFields.class);
    assertEquals(utf16.active, false);
    assertEquals(utf16.id, 8);
    assertEquals(utf16.name, "json");

    PublicFields utf8 =
        json.fromJson(expected.getBytes(StandardCharsets.UTF_8), PublicFields.class);
    assertEquals(utf8.active, true);
    assertEquals(utf8.id, 7);
    assertEquals(utf8.name, "fory");
    assertGeneratedWhenSupported(json, PublicFields.class);
  }

  @Test
  public void writeNullFields() {
    ForyJson json = newJsonBuilder().writeNullFields(true).build();
    assertEquals(
        json.toJson(new PublicFields()),
        "{\"active\":true,\"id\":7,\"name\":\"fory\",\"missing\":null}");
  }

  @Test
  public void fieldOnlyModeIgnoresMethods() {
    ForyJson json = newJsonBuilder().withFieldMode(true).build();
    assertEquals(
        json.toJson(new MethodsIgnored()),
        "{\"setterCalls\":0,\"value\":\"field\",\"hidden\":\"hidden\"}");
    MethodsIgnored value =
        json.fromJson("{\"hidden\":\"json\",\"value\":\"json\"}", MethodsIgnored.class);
    assertEquals(hiddenValue(value), "json");
    assertEquals(value.setterCalls, 0);
    assertEquals(value.value, "json");
  }

  @Test
  public void writeDeclaredFields() {
    ForyJson json = newJson();
    String expected = "{\"id\":11,\"name\":\"private\"}";
    assertEquals(json.toJson(new PrivateFields()), expected);
    assertEquals(
        new String(json.toJsonBytes(new PrivateFields()), StandardCharsets.UTF_8), expected);
    assertGeneratedWhenSupported(json, PrivateFields.class);
    PrivateFields value =
        json.fromJson("{\"id\":12,\"name\":\"json\",\"nullable\":\"value\"}", PrivateFields.class);
    assertEquals(privateId(value), 12);
    assertEquals(privateName(value), "json");
    assertEquals(privateNullable(value), "value");
    assertEquals(privateTransientValue(value), "transient");
    assertEquals(privateStaticValue(), "static");
  }

  @Test
  public void writeDirectionalIgnore() {
    ForyJson json = newJson();
    assertEquals(json.toJson(new DirectionalIgnore()), "{\"writeOnly\":2}");
  }

  @Test
  public void writeDeclaredObjectFieldType() {
    ForyJson json = newJson();
    String expected = "{\"value\":{\"parent\":1}}";
    assertEquals(json.toJson(new DeclaredParentField()), expected);
    assertEquals(
        new String(json.toJsonBytes(new DeclaredParentField()), StandardCharsets.UTF_8), expected);
    DeclaredParentField read =
        json.fromJson("{\"value\":{\"child\":9,\"parent\":3}}", DeclaredParentField.class);
    assertEquals(read.value.getClass(), ParentValue.class);
    assertEquals(read.value.parent, 3);
  }

  @Test
  public void readPublicFields() {
    ForyJson json = newJson();
    PublicFields fields =
        json.fromJson(
            "{\"unknown\":[1,true,{\"x\":\"y\"}],\"name\":\"fory\",\"id\":7,\"active\":true}",
            PublicFields.class);
    assertEquals(fields.name, "fory");
    assertEquals(fields.id, 7);
    assertEquals(fields.active, true);
  }

  @Test
  public void readUtf8Bytes() {
    ForyJson json = newJson();
    byte[] bytes =
        "{\"name\":\"\uD83D\uDE00\u1234\",\"id\":8,\"active\":false}"
            .getBytes(StandardCharsets.UTF_8);
    PublicFields fields = json.fromJson(bytes, PublicFields.class);
    assertEquals(fields.name, "\uD83D\uDE00\u1234");
    assertEquals(fields.id, 8);
    assertEquals(fields.active, false);
  }

  @Test
  public void readDirectionalIgnore() {
    ForyJson json = newJson();
    DirectionalIgnore value =
        json.fromJson("{\"both\":7,\"writeOnly\":8,\"readOnly\":9}", DirectionalIgnore.class);
    assertEquals(value.both, 1);
    assertEquals(value.writeOnly, 2);
    assertEquals(value.readOnly, 9);
  }
}

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.fory.json.data.BoxedScalars;
import org.apache.fory.json.data.DeclaredParentField;
import org.apache.fory.json.data.DirectionalIgnore;
import org.apache.fory.json.data.FirstIntField;
import org.apache.fory.json.data.MethodsIgnored;
import org.apache.fory.json.data.ParentValue;
import org.apache.fory.json.data.PrivateFields;
import org.apache.fory.json.data.PublicFields;
import org.testng.annotations.Test;

public class JsonObjectTest extends ForyJsonTestModels {
  @Test
  public void writePublicFields() {
    ForyJson json = ForyJson.builder().build();
    assertEquals(json.toJson(new PublicFields()), "{\"active\":true,\"id\":7,\"name\":\"fory\"}");
    assertEquals(
        new String(json.toJsonBytes(new PublicFields()), StandardCharsets.UTF_8),
        "{\"active\":true,\"id\":7,\"name\":\"fory\"}");
  }

  @Test
  public void writeFirstIntGenerated() {
    ForyJson json = ForyJson.builder().build();
    String expected = "{\"count\":2,\"name\":\"first\"}";
    assertEquals(json.toJson(new FirstIntField()), expected);
    assertEquals(
        new String(json.toJsonBytes(new FirstIntField()), StandardCharsets.UTF_8), expected);
    assertGeneratedWhenSupported(json, FirstIntField.class);
  }

  @Test
  public void sharedFacadeThreads() throws Exception {
    ForyJson json = ForyJson.builder().build();
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
  public void useGeneratedWriter() {
    ForyJson json = ForyJson.builder().build();
    assertEquals(json.toJson(new PublicFields()), "{\"active\":true,\"id\":7,\"name\":\"fory\"}");
    assertGeneratedWhenSupported(json, PublicFields.class);
  }

  @Test
  public void disableGeneratedWriter() {
    ForyJson json = ForyJson.builder().withCodegen(false).build();
    assertEquals(json.toJson(new PublicFields()), "{\"active\":true,\"id\":7,\"name\":\"fory\"}");
    PublicFields fields =
        json.fromJson("{\"active\":false,\"id\":8,\"name\":\"json\"}", PublicFields.class);
    assertEquals(fields.active, false);
    assertEquals(fields.id, 8);
    assertEquals(fields.name, "json");
    BoxedScalars scalars =
        json.fromJson(
            "{\"bool\":true,\"byteValue\":2,\"charValue\":\"x\",\"doubleValue\":2.5,"
                + "\"floatValue\":1.5,\"intValue\":4,\"longValue\":5,\"shortValue\":3}",
            BoxedScalars.class);
    assertEquals(scalars.byteValue, Byte.valueOf((byte) 2));
    assertEquals(scalars.shortValue, Short.valueOf((short) 3));
    assertEquals(json.hasGeneratedWriter(PublicFields.class), false);
  }

  @Test
  public void writeNullFields() {
    ForyJson json = ForyJson.builder().writeNullFields(true).build();
    assertEquals(
        json.toJson(new PublicFields()),
        "{\"active\":true,\"id\":7,\"missing\":null,\"name\":\"fory\"}");
  }

  @Test
  public void ignoreMethods() {
    ForyJson json = ForyJson.builder().build();
    assertEquals(
        json.toJson(new MethodsIgnored()),
        "{\"hidden\":\"hidden\",\"setterCalls\":0,\"value\":\"field\"}");
    MethodsIgnored value =
        json.fromJson("{\"hidden\":\"json\",\"value\":\"json\"}", MethodsIgnored.class);
    assertEquals(hiddenValue(value), "json");
    assertEquals(value.setterCalls, 0);
    assertEquals(value.value, "json");
  }

  @Test
  public void writeDeclaredFields() {
    ForyJson json = ForyJson.builder().build();
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
    ForyJson json = ForyJson.builder().build();
    assertEquals(json.toJson(new DirectionalIgnore()), "{\"writeOnly\":2}");
  }

  @Test
  public void writeDeclaredObjectFieldType() {
    ForyJson json = ForyJson.builder().build();
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
    ForyJson json = ForyJson.builder().build();
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
    ForyJson json = ForyJson.builder().build();
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
    ForyJson json = ForyJson.builder().build();
    DirectionalIgnore value =
        json.fromJson("{\"both\":7,\"writeOnly\":8,\"readOnly\":9}", DirectionalIgnore.class);
    assertEquals(value.both, 1);
    assertEquals(value.writeOnly, 2);
    assertEquals(value.readOnly, 9);
  }
}

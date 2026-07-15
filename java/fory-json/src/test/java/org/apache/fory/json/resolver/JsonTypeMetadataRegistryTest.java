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

package org.apache.fory.json.resolver;

import static org.apache.fory.json.meta.JsonMetadataDecoderTest.record;
import static org.apache.fory.json.meta.JsonMetadataDecoderTest.stream;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;

import org.apache.fory.json.meta.JsonDecodedMetadata;
import org.apache.fory.json.meta.JsonMetadataDecoderTest.Bytes;
import org.apache.fory.json.meta.JsonMetadataFormat;
import org.apache.fory.json.meta.JsonTypeMetadata;
import org.apache.fory.json.meta.JsonTypeMetadataData;
import org.testng.annotations.Test;

public class JsonTypeMetadataRegistryTest {
  @Test
  public void testLazySectionAndSelection() {
    JsonTypeMetadataRegistry registry = new JsonTypeMetadataRegistry();
    JsonTypeMetadataRegistryTest_Model_ForyJsonMetadata.reset();

    JsonDecodedMetadata first = registry.decodedSection(Model.class, JsonTypeMetadata.OBJECT);
    assertSame(first, registry.decodedSection(Model.class, JsonTypeMetadata.OBJECT));
    assertEquals(JsonTypeMetadataRegistryTest_Model_ForyJsonMetadata.metadataCalls, 1);
    assertEquals(JsonTypeMetadataRegistryTest_Model_ForyJsonMetadata.typeCalls, 0);
    assertEquals(JsonTypeMetadataRegistryTest_Model_ForyJsonMetadata.operationCalls, 0);

    assertSame(
        registry.generatedType(Model.class, JsonTypeMetadata.OBJECT, first, 0), String.class);
    assertEquals(JsonTypeMetadataRegistryTest_Model_ForyJsonMetadata.typeCalls, 1);
    assertSame(
        registry.directOperation(
            Model.class,
            JsonTypeMetadata.OBJECT,
            first,
            0,
            JsonMetadataFormat.CODEC_FACTORY,
            Runnable.class),
        JsonTypeMetadataRegistryTest_Model_ForyJsonMetadata.OPERATION);
    assertEquals(JsonTypeMetadataRegistryTest_Model_ForyJsonMetadata.operationCalls, 1);
  }

  static final class Model {}
}

final class JsonTypeMetadataRegistryTest_Model_ForyJsonMetadata extends JsonTypeMetadata {
  static final Runnable OPERATION = () -> {};
  static int metadataCalls;
  static int typeCalls;
  static int operationCalls;

  public JsonTypeMetadataRegistryTest_Model_ForyJsonMetadata(Class<?> requested) {
    super(requested, JsonTypeMetadataRegistryTest.Model.class.getName(), ABI_VERSION);
  }

  static void reset() {
    metadataCalls = 0;
    typeCalls = 0;
    operationCalls = 0;
  }

  @Override
  public Object metadata(int section) {
    metadataCalls++;
    if (section != OBJECT) {
      return new JsonTypeMetadataData(new byte[][] {stream(section)}, null);
    }
    Bytes token = new Bytes();
    token.index(0).byteValue(JsonMetadataFormat.TOKEN_DIRECT).index(0);
    Bytes operation = new Bytes();
    operation
        .index(0)
        .byteValue(JsonMetadataFormat.CODEC_FACTORY)
        .byteValue(JsonMetadataFormat.OP_DIRECT)
        .byteValue(JsonMetadataFormat.READ | JsonMetadataFormat.WRITE)
        .index(0);
    return new JsonTypeMetadataData(
        new byte[][] {
          stream(
              section,
              record(JsonMetadataFormat.TOKEN, token.bytes()),
              record(JsonMetadataFormat.OPERATION, operation.bytes()))
        },
        null);
  }

  @Override
  public Class<?> metadataType(int section, int index) {
    typeCalls++;
    return String.class;
  }

  @Override
  public Object metadataOperation(int section, int index) {
    operationCalls++;
    return OPERATION;
  }
}

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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.apache.fory.reflect.TypeRef;
import org.testng.annotations.Test;

public class ForyJsonInputStreamTest {
  @Test
  public void inputStreamOverloads() {
    ForyJson json = ForyJson.builder().build();
    InputValue value = json.fromJson(input("{\"name\":\"stream\"}"), InputValue.class);
    List<Integer> values = json.fromJson(input("[1,2,3]"), new TypeRef<List<Integer>>() {});

    assertEquals(value.name, "stream");
    assertEquals(values, Arrays.asList(1, 2, 3));
  }

  private static ByteArrayInputStream input(String json) {
    return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
  }

  public static final class InputValue {
    public String name;
  }
}

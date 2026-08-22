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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.apache.fory.reflect.TypeRef;
import org.testng.annotations.Test;

public class ForyJsonByteRangeTest {
  private final ForyJson json =
      ForyJson.builder().withAsyncCompilation(false).withConcurrencyLevel(1).build();

  @Test
  public void readClassRange() {
    String name = repeat('a', 80);
    String document = "{\"name\":\"" + name + "\",\"count\":7}";
    byte[] bytes = ("prefix" + document + "suffix").getBytes(StandardCharsets.UTF_8);

    RangeValue value = json.fromJson(bytes, "prefix".length(), document.length(), RangeValue.class);

    assertEquals(value.name, name);
    assertEquals(value.count, 7);
  }

  @Test
  public void readGenericRange() {
    byte[] bytes = "xx[1,2,3]yy".getBytes(StandardCharsets.UTF_8);
    List<Integer> values = json.fromJson(bytes, 2, 7, new TypeRef<List<Integer>>() {});
    assertEquals(values, Arrays.asList(1, 2, 3));
  }

  @Test
  public void ignoreBytesOutsideRange() {
    byte[] number = "xx12 999yy".getBytes(StandardCharsets.UTF_8);
    assertEquals(json.fromJson(number, 2, 2, Integer.class), Integer.valueOf(12));

    byte[] string = "xx\"abc\"yy".getBytes(StandardCharsets.UTF_8);
    assertThrows(ForyJsonException.class, () -> json.fromJson(string, 2, 4, String.class));

    byte[] bool = "xxtrueyy".getBytes(StandardCharsets.UTF_8);
    assertThrows(ForyJsonException.class, () -> json.fromJson(bool, 2, 3, Boolean.class));
  }

  @Test
  public void rejectInvalidRange() {
    byte[] bytes = "null".getBytes(StandardCharsets.UTF_8);
    assertThrows(
        NullPointerException.class, () -> json.fromJson((byte[]) null, -1, 1, Object.class));
    assertThrows(IndexOutOfBoundsException.class, () -> json.fromJson(bytes, -1, 1, Object.class));
    assertThrows(IndexOutOfBoundsException.class, () -> json.fromJson(bytes, 0, -1, Object.class));
    assertThrows(IndexOutOfBoundsException.class, () -> json.fromJson(bytes, 3, 2, Object.class));
    assertThrows(
        IndexOutOfBoundsException.class,
        () -> json.fromJson(bytes, Integer.MAX_VALUE, 1, Object.class));
  }

  private static String repeat(char value, int count) {
    char[] chars = new char[count];
    Arrays.fill(chars, value);
    return new String(chars);
  }

  public static final class RangeValue {
    public String name;
    public int count;

    public RangeValue() {}
  }
}

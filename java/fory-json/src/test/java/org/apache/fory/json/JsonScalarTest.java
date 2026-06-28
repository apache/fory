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
import java.util.Optional;
import org.apache.fory.json.data.BoxedScalars;
import org.apache.fory.json.data.CoreScalarFields;
import org.apache.fory.json.data.NaturalObjectValue;
import org.apache.fory.json.data.NaturalValues;
import org.apache.fory.json.data.NumericBoundaries;
import org.apache.fory.json.data.PublicFields;
import org.testng.annotations.Test;

public class JsonScalarTest extends ForyJsonTestModels {
  @Test
  public void writeBoxedScalars() {
    ForyJson json = ForyJson.builder().build();
    String expected =
        "{\"bool\":true,\"byteValue\":2,\"charValue\":\"x\",\"doubleValue\":2.5,"
            + "\"floatValue\":1.5,\"intValue\":4,\"longValue\":5,\"shortValue\":3}";
    assertEquals(json.toJson(new BoxedScalars()), expected);
    assertEquals(
        new String(json.toJsonBytes(new BoxedScalars()), StandardCharsets.UTF_8), expected);
  }

  @Test
  public void writeNaturalObjectValues() {
    ForyJson json = ForyJson.builder().build();
    String expected =
        "{\"bool\":true,\"list\":[\"a\",1,false],\"map\":{\"name\":\"fory\",\"score\":9},"
            + "\"number\":7,\"text\":\"fory\"}";
    assertEquals(json.toJson(new NaturalValues()), expected);
    assertEquals(
        new String(json.toJsonBytes(new NaturalValues()), StandardCharsets.UTF_8), expected);
  }

  @Test
  public void writeNaturalEmptyObject() {
    ForyJson json = ForyJson.builder().build();
    String expected = "{\"value\":{}}";
    assertEquals(json.toJson(new NaturalObjectValue()), expected);
    assertEquals(
        new String(json.toJsonBytes(new NaturalObjectValue()), StandardCharsets.UTF_8), expected);
  }

  @Test
  public void readBoxedScalars() {
    ForyJson json = ForyJson.builder().build();
    BoxedScalars value =
        json.fromJson(
            "{\"bool\":false,\"byteValue\":6,\"charValue\":\"z\",\"doubleValue\":3.5,"
                + "\"floatValue\":2.5,\"intValue\":8,\"longValue\":9,\"shortValue\":7}",
            BoxedScalars.class);
    assertEquals(value.bool, Boolean.FALSE);
    assertEquals(value.byteValue, Byte.valueOf((byte) 6));
    assertEquals(value.charValue, Character.valueOf('z'));
    assertEquals(value.doubleValue, Double.valueOf(3.5));
    assertEquals(value.floatValue, Float.valueOf(2.5f));
    assertEquals(value.intValue, Integer.valueOf(8));
    assertEquals(value.longValue, Long.valueOf(9));
    assertEquals(value.shortValue, Short.valueOf((short) 7));
  }

  @Test
  public void readNumericBoundaries() {
    ForyJson json = ForyJson.builder().build();
    String latin1 =
        "{\"intMax\":2147483647,\"intMin\":-2147483648,"
            + "\"longMax\":9223372036854775807,\"longMin\":-9223372036854775808,"
            + "\"small\":-7,\"text\":\"café\"}";
    String utf16 = latin1.replace("café", ZH_TEXT);
    assertNumericBoundaries(json.fromJson(latin1, NumericBoundaries.class), "café");
    assertNumericBoundaries(json.fromJson(utf16, NumericBoundaries.class), ZH_TEXT);
    assertNumericBoundaries(
        json.fromJson(utf16.getBytes(StandardCharsets.UTF_8), NumericBoundaries.class), ZH_TEXT);

    assertThrows(ForyJsonException.class, () -> json.fromJson("2147483648", int.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("-2147483649", int.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("1.0", int.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("9223372036854775808", long.class));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("-9223372036854775809".getBytes(StandardCharsets.UTF_8), long.class));
    assertThrows(
        ForyJsonException.class,
        () ->
            json.fromJson(
                "{\"intMax\":2147483648,\"text\":\"" + ZH_TEXT + "\"}", NumericBoundaries.class));
  }

  @Test
  public void writeReadCoreScalarFields() {
    ForyJson json = ForyJson.builder().build();
    CoreScalarFields value = new CoreScalarFields();
    String expected =
        "{\"atomicInt\":7,\"bigDecimal\":12345.6789,\"bigInteger\":12345678901234567890,"
            + "\"builder\":\"build\",\"bytes\":[1,-2,3],\"calendar\":123456789,"
            + "\"charset\":\"UTF-8\",\"currency\":\"EUR\",\"date\":\"2026-06-21\","
            + "\"instant\":\"2026-06-21T01:02:03Z\",\"locale\":\"zh-Hans-CN\","
            + "\"maybe\":\"yes\",\"optionalInt\":4,\"timeZone\":\"UTC\",\"type\":\""
            + PublicFields.class.getName()
            + "\",\"uri\":\"https://fory.apache.org/json\","
            + "\"url\":\"https://fory.apache.org/\","
            + "\"uuid\":\"123e4567-e89b-12d3-a456-426614174000\"}";
    assertEquals(json.toJson(value), expected);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), expected);
    CoreScalarFields read = json.fromJson(expected, CoreScalarFields.class);
    assertEquals(read.atomicInt.get(), 7);
    assertEquals(read.bigDecimal, value.bigDecimal);
    assertEquals(read.bigInteger, value.bigInteger);
    assertEquals(read.builder.toString(), "build");
    assertEquals(byteBufferBytes(read.bytes), new byte[] {1, -2, 3});
    assertEquals(read.calendar.getTimeInMillis(), 123456789L);
    assertEquals(read.charset, StandardCharsets.UTF_8);
    assertEquals(read.currency, value.currency);
    assertEquals(read.date, value.date);
    assertEquals(read.instant, value.instant);
    assertEquals(read.locale, value.locale);
    assertEquals(read.maybe, Optional.of("yes"));
    assertEquals(read.optionalInt.getAsInt(), 4);
    assertEquals(read.timeZone.getID(), "UTC");
    assertEquals(read.type, PublicFields.class);
    assertEquals(read.uri, value.uri);
    assertEquals(read.url, value.url);
    assertEquals(read.uuid, value.uuid);
  }

  @Test
  public void readScalarRoots() {
    ForyJson json = ForyJson.builder().build();
    assertEquals(json.fromJson("7", int.class), Integer.valueOf(7));
    assertEquals(json.fromJson("true", boolean.class), Boolean.TRUE);
    assertEquals(json.fromJson("\"fory\"".getBytes(StandardCharsets.UTF_8), String.class), "fory");
    assertEquals(
        json.fromJson("\"\uD83D\uDE00\u1234\"".getBytes(StandardCharsets.UTF_8), String.class),
        "\uD83D\uDE00\u1234");
  }

  @Test
  public void rejectLeadingZero() {
    ForyJson json = ForyJson.builder().build();
    assertThrows(ForyJsonException.class, () -> json.fromJson("01", int.class));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"id\":01}".getBytes(StandardCharsets.UTF_8), PublicFields.class));
  }
}

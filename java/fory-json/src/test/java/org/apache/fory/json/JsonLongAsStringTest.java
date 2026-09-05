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

import static org.apache.fory.json.JsonTestSupport.generatedUtf8WriterClass;
import static org.apache.fory.json.JsonTestSupport.newStringWriter;
import static org.apache.fory.json.JsonTestSupport.newUtf8Reader;
import static org.apache.fory.json.JsonTestSupport.newUtf8Writer;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.codec.AbstractJsonValueCodec;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.writer.JsonWriter;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.reflect.TypeRef;
import org.testng.annotations.Test;

public class JsonLongAsStringTest extends ForyJsonTestModels {
  private static final long UNSAFE_INTEGER = 9_007_199_254_740_992L;

  @Test(dataProvider = "enableCodegen")
  public void writesLongBindings(boolean codegen) {
    ForyJson json = newJsonBuilder(codegen).writeLongAsString(true).withConcurrencyLevel(1).build();
    LongValues value = LongValues.create();
    String expectedPrefix =
        "{\"aFirst\":\"-9223372036854775808\",\"boxed\":\"9223372036854775807\","
            + "\"boxedArray\":[\"-1\",null,\"0\"],\"dynamic\":\"9007199254740992\","
            + "\"list\":[\"1\",\"9007199254740992\"],\"map\":{\"max\":"
            + "\"9223372036854775807\"},\"primitiveArray\":[\"-9223372036854775808\","
            + "\"0\",\"9223372036854775807\"],\"text\":\"雪\",";
    String expected = expectedPrefix + "\"\\u603b\\u6570\":\"7\"}";

    String stringJson = json.toJson(value);
    String utf8Json = new String(json.toJsonBytes(value), StandardCharsets.UTF_8);
    assertEquals(normalizeUnicodeName(stringJson), expected);
    assertEquals(normalizeUnicodeName(utf8Json), expected);
    assertEquals(json.toJson(Long.MIN_VALUE), "\"-9223372036854775808\"");
    assertEquals(
        new String(json.toJsonBytes(Long.MAX_VALUE), StandardCharsets.UTF_8),
        "\"9223372036854775807\"");
    assertEquals(json.toJson(new long[] {-1, 0, 1}), "[\"-1\",\"0\",\"1\"]");
    assertEquals(json.toJson(new Long[] {-1L, null, 1L}), "[\"-1\",null,\"1\"]");
    assertEquals(json.toJson(Arrays.asList(-1L, 1L)), "[\"-1\",\"1\"]");

    LongValues decoded = json.fromJson(stringJson, LongValues.class);
    assertEquals(decoded.aFirst, Long.MIN_VALUE);
    assertEquals(decoded.boxed, Long.valueOf(Long.MAX_VALUE));
    assertEquals(decoded.primitiveArray, value.primitiveArray);
    assertEquals(decoded.boxedArray, value.boxedArray);
    assertEquals(decoded.list, value.list);
    assertEquals(decoded.map, value.map);
    assertEquals(decoded.unicode, 7L);
  }

  @Test(dataProvider = "enableCodegen")
  public void keepsNumericDefault(boolean codegen) {
    ForyJson json = newJson(codegen);
    assertEquals(json.toJson(Long.MIN_VALUE), "-9223372036854775808");
    assertEquals(json.toJson(new long[] {-1, 0, 1}), "[-1,0,1]");
    assertEquals(json.toJson(Arrays.asList(-1L, 1L)), "[-1,1]");
    assertEquals(json.toJson(new AtomicLong(Long.MAX_VALUE)), "9223372036854775807");
    assertEquals(json.toJson(new AtomicLongArray(new long[] {-1, 0})), "[-1,0]");
    assertEquals(json.toJson(OptionalLong.of(Long.MIN_VALUE)), "-9223372036854775808");
  }

  @Test(dataProvider = "enableCodegen")
  public void readsBothTokenShapes(boolean codegen) {
    ForyJson json = newJson(codegen);
    assertEquals(json.fromJson("9223372036854775807", long.class), Long.MAX_VALUE);
    assertEquals(json.fromJson("\"9223372036854775807\"", long.class), Long.MAX_VALUE);
    assertEquals(
        json.fromJson("[\"-9223372036854775808\",0]", long[].class),
        new long[] {Long.MIN_VALUE, 0});
    assertEquals(
        json.fromJson(
                "{\"aFirst\":\"-1\",\"boxed\":2,\"boxedArray\":[],\"dynamic\":3,"
                    + "\"list\":[],\"map\":{},\"primitiveArray\":[],\"总数\":\"4\"}",
                LongValues.class)
            .unicode,
        4L);
  }

  @Test
  public void writesUnsignedDigitsAsString() {
    StringJsonWriter stringWriter = newStringWriter();
    stringWriter.writeUnsignedLongAsString(-1L);
    assertEquals(stringWriter.toJson(), "\"18446744073709551615\"");

    Utf8JsonWriter utf8Writer = newUtf8Writer();
    utf8Writer.writeUnsignedLongAsString(-1L);
    assertEquals(
        new String(utf8Writer.toJsonBytes(), StandardCharsets.UTF_8), "\"18446744073709551615\"");

    assertEquals(
        newUtf8Reader("\"18446744073709551615\"".getBytes(StandardCharsets.UTF_8))
            .readUnsignedLong(),
        -1L);
    assertThrows(
        ForyJsonException.class,
        () ->
            newUtf8Reader("\"18446744073709551616\"".getBytes(StandardCharsets.UTF_8))
                .readUnsignedLong());
  }

  @Test
  public void snapshotsBuilderSetting() {
    ForyJsonBuilder builder = ForyJson.builder().withCodegen(false).writeLongAsString(false);
    ForyJson numeric = builder.build();
    ForyJson quoted = builder.writeLongAsString(true).build();
    builder.writeLongAsString(false);

    assertEquals(numeric.toJson(7L), "7");
    assertEquals(quoted.toJson(7L), "\"7\"");
  }

  @Test(dataProvider = "enableCodegen")
  public void writesLongWrappers(boolean codegen) {
    ForyJson json = newJsonBuilder(codegen).writeLongAsString(true).build();
    LongWrappers value = LongWrappers.create();
    String expected =
        "{\"atomic\":\"9223372036854775807\",\"atomicArray\":[\"-1\",\"0\"],"
            + "\"optional\":\"9007199254740992\","
            + "\"optionalLong\":\"-9223372036854775808\",\"reference\":\"7\"}";

    assertEquals(json.toJson(value), expected);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), expected);
    assertEquals(json.toJson(new AtomicLong(Long.MAX_VALUE)), "\"9223372036854775807\"");
    assertEquals(json.toJson(OptionalLong.of(Long.MIN_VALUE)), "\"-9223372036854775808\"");
    assertEquals(
        json.toJson(Optional.of(Long.MAX_VALUE), new TypeRef<Optional<Long>>() {}),
        "\"9223372036854775807\"");

    LongWrappers decoded = json.fromJson(expected, LongWrappers.class);
    assertEquals(decoded.atomic.get(), Long.MAX_VALUE);
    assertEquals(decoded.atomicArray.get(0), -1L);
    assertEquals(decoded.atomicArray.get(1), 0L);
    assertEquals(decoded.optional, Optional.of(UNSAFE_INTEGER));
    assertEquals(decoded.optionalLong, OptionalLong.of(Long.MIN_VALUE));
    assertEquals(decoded.reference.get(), Long.valueOf(7L));
  }

  @Test
  public void keepsOtherLongCarriers() {
    ForyJson numeric = ForyJson.builder().withCodegen(false).build();
    ForyJson quoted = ForyJson.builder().withCodegen(false).writeLongAsString(true).build();
    Object[] values = {
      new Date(123456789L),
      BitSet.valueOf(new long[] {Long.MIN_VALUE}),
      new NumberValue(Long.MAX_VALUE)
    };
    for (Object value : values) {
      assertEquals(quoted.toJson(value), numeric.toJson(value), value.getClass().getName());
    }
    assertEquals(
        quoted.toJson(new CustomValue(Long.MAX_VALUE)), "{\"value\":\"long:9223372036854775807\"}");
  }

  @Test
  public void isolatesGeneratedWriters() {
    LongValues value = LongValues.create();
    ForyJson numeric = ForyJson.builder().withAsyncCompilation(false).build();
    ForyJson quoted =
        ForyJson.builder().writeLongAsString(true).withAsyncCompilation(false).build();

    assertEquals(numeric.toJsonBytes(value)[10], (byte) '-');
    assertEquals(quoted.toJson(value).charAt(10), '"');
    assertNotSame(
        generatedUtf8WriterClass(numeric, LongValues.class),
        generatedUtf8WriterClass(quoted, LongValues.class));
  }

  public static final class LongValues {
    @JsonProperty(index = 0)
    public long aFirst;

    @JsonProperty(index = 1)
    public Long boxed;

    @JsonProperty(index = 2)
    public Long[] boxedArray;

    @JsonProperty(index = 3)
    public Object dynamic;

    @JsonProperty(index = 4)
    public List<Long> list;

    @JsonProperty(index = 5)
    public Map<String, Long> map;

    @JsonProperty(index = 6)
    public long[] primitiveArray;

    @JsonProperty(index = 7)
    public String text;

    @JsonProperty(value = "总数", index = 8)
    public long unicode;

    static LongValues create() {
      LongValues value = new LongValues();
      value.aFirst = Long.MIN_VALUE;
      value.boxed = Long.MAX_VALUE;
      value.boxedArray = new Long[] {-1L, null, 0L};
      value.dynamic = UNSAFE_INTEGER;
      value.list = Arrays.asList(1L, UNSAFE_INTEGER);
      value.map = new LinkedHashMap<>();
      value.map.put("max", Long.MAX_VALUE);
      value.primitiveArray = new long[] {Long.MIN_VALUE, 0, Long.MAX_VALUE};
      value.text = "雪";
      value.unicode = 7;
      return value;
    }
  }

  private static String normalizeUnicodeName(String json) {
    return json.replace("\"总数\"", "\"\\u603b\\u6570\"");
  }

  public static final class NumberValue {
    public Number value;

    NumberValue(long value) {
      this.value = value;
    }
  }

  public static final class LongWrappers {
    @JsonProperty(index = 0)
    public AtomicLong atomic;

    @JsonProperty(index = 1)
    public AtomicLongArray atomicArray;

    @JsonProperty(index = 2)
    public Optional<Long> optional;

    @JsonProperty(index = 3)
    public OptionalLong optionalLong;

    @JsonProperty(index = 4)
    public AtomicReference<Long> reference;

    static LongWrappers create() {
      LongWrappers value = new LongWrappers();
      value.atomic = new AtomicLong(Long.MAX_VALUE);
      value.atomicArray = new AtomicLongArray(new long[] {-1, 0});
      value.optional = Optional.of(UNSAFE_INTEGER);
      value.optionalLong = OptionalLong.of(Long.MIN_VALUE);
      value.reference = new AtomicReference<>(7L);
      return value;
    }
  }

  public static final class CustomValue {
    @JsonCodec(LongTextCodec.class)
    public long value;

    CustomValue(long value) {
      this.value = value;
    }
  }

  public static final class LongTextCodec extends AbstractJsonValueCodec<Long> {
    @Override
    public void write(JsonWriter writer, Long value) {
      writer.writeString("long:" + value);
    }

    @Override
    public Long read(JsonReader reader) {
      return Long.valueOf(reader.readString().substring(5));
    }
  }
}

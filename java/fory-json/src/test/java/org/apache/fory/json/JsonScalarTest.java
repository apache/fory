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
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.chrono.HijrahChronology;
import java.time.chrono.HijrahDate;
import java.time.chrono.JapaneseDate;
import java.time.chrono.MinguoDate;
import java.time.chrono.ThaiBuddhistDate;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.fory.json.codec.JsonCodec;
import org.apache.fory.json.data.BoxedScalars;
import org.apache.fory.json.data.CoreScalarFields;
import org.apache.fory.json.data.JsonTestData;
import org.apache.fory.json.data.NaturalObjectValue;
import org.apache.fory.json.data.NaturalValues;
import org.apache.fory.json.data.NumericBoundaries;
import org.apache.fory.json.data.PublicFields;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.JsonWriter;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.serializer.StringSerializer;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class JsonScalarTest extends ForyJsonTestModels {
  private static final int BIG_NUMBER_LIMIT = 10_000;

  @Factory(dataProvider = "codegen")
  public JsonScalarTest(boolean codegen) {
    super(codegen);
  }

  @Test
  public void writeBoxedScalars() {
    ForyJson json = newJson();
    String expected =
        "{\"bool\":true,\"byteValue\":2,\"charValue\":\"x\",\"doubleValue\":2.5,"
            + "\"floatValue\":1.5,\"intValue\":4,\"longValue\":5,\"shortValue\":3}";
    assertEquals(json.toJson(new BoxedScalars()), expected);
    assertEquals(
        new String(json.toJsonBytes(new BoxedScalars()), StandardCharsets.UTF_8), expected);
  }

  @Test
  public void writeUtf8Doubles() {
    double[] values = {
      0.0d,
      -0.0d,
      2.5d,
      -3.75d,
      Math.nextUp(1.0d),
      32.389082173209815d,
      69.85922221416756d,
      1.0e-4d,
      1.0e-3d,
      1.0e7d,
      Double.MIN_VALUE,
      Double.MAX_VALUE
    };
    for (double value : values) {
      Utf8JsonWriter writer = new Utf8JsonWriter(false, new byte[4]);
      writer.writeDouble(value);
      assertEquals(
          new String(writer.toJsonBytes(), StandardCharsets.UTF_8), Double.toString(value));
    }
    Utf8JsonWriter writer = new Utf8JsonWriter(false, new byte[4]);
    writer.writeDouble(Double.NaN);
    assertEquals(new String(writer.toJsonBytes(), StandardCharsets.UTF_8), "\"NaN\"");
  }

  @Test
  public void writeReadNonFiniteFloats() {
    ForyJson json = newJson();
    assertEquals(json.toJson(Double.NaN), "\"NaN\"");
    assertEquals(json.toJson(Double.POSITIVE_INFINITY), "\"Infinity\"");
    assertEquals(json.toJson(Double.NEGATIVE_INFINITY), "\"-Infinity\"");
    assertEquals(json.toJson(Float.NaN), "\"NaN\"");
    assertEquals(
        new String(json.toJsonBytes(Float.NEGATIVE_INFINITY), StandardCharsets.UTF_8),
        "\"-Infinity\"");
    assertEquals(
        json.toJson(new double[] {Double.NaN, Double.POSITIVE_INFINITY}), "[\"NaN\",\"Infinity\"]");
    assertEquals(json.toJson(new Float[] {Float.NEGATIVE_INFINITY, null}), "[\"-Infinity\",null]");

    assertTrue(Double.isNaN(json.fromJson("\"NaN\"", Double.class)));
    assertEquals(json.fromJson("\"Infinity\"", double.class), Double.POSITIVE_INFINITY);
    assertEquals(
        json.fromJson("\"-Infinity\"".getBytes(StandardCharsets.UTF_8), double.class),
        Double.NEGATIVE_INFINITY);
    assertTrue(Float.isNaN(json.fromJson("\"NaN\"".getBytes(StandardCharsets.UTF_8), Float.class)));
    assertEquals(json.fromJson("\"Infinity\"", float.class), Float.POSITIVE_INFINITY);
    assertTrue(
        Double.isNaN(new Utf8JsonReader("\"NaN\"".getBytes(StandardCharsets.UTF_8)).readDouble()));
    assertEquals(
        new Latin1JsonReader(latin1Bytes("\"Infinity\"")).readDouble(), Double.POSITIVE_INFINITY);
    assertEquals(utf16Reader("\"-Infinity\"").readDouble(), Double.NEGATIVE_INFINITY);

    NonFiniteNumbers numbers =
        json.fromJson(
            "{\"nan\":\"NaN\",\"neg\":\"-Infinity\",\"pos\":\"Infinity\",\"boxed\":\"NaN\"}",
            NonFiniteNumbers.class);
    assertTrue(Double.isNaN(numbers.nan));
    assertEquals(numbers.neg, Float.NEGATIVE_INFINITY);
    assertEquals(numbers.pos, Double.POSITIVE_INFINITY);
    assertTrue(Float.isNaN(numbers.boxed));

    double[] doubles = json.fromJson("[\"NaN\",\"Infinity\",\"-Infinity\"]", double[].class);
    assertTrue(Double.isNaN(doubles[0]));
    assertEquals(doubles[1], Double.POSITIVE_INFINITY);
    assertEquals(doubles[2], Double.NEGATIVE_INFINITY);
    Float[] floats = json.fromJson("[\"NaN\",null,\"-Infinity\"]", Float[].class);
    assertTrue(Float.isNaN(floats[0]));
    assertEquals(floats[1], null);
    assertEquals(floats[2], Float.NEGATIVE_INFINITY);
    List<Float> list = json.fromJson("[\"Infinity\",\"NaN\"]", new TypeRef<List<Float>>() {});
    assertEquals(list.get(0), Float.POSITIVE_INFINITY);
    assertTrue(Float.isNaN(list.get(1)));
    Map<String, Double> map =
        json.fromJson(
            "{\"nan\":\"NaN\",\"pos\":\"Infinity\",\"neg\":\"-Infinity\"}",
            new TypeRef<Map<String, Double>>() {});
    assertTrue(Double.isNaN(map.get("nan")));
    assertEquals(map.get("pos"), Double.POSITIVE_INFINITY);
    assertEquals(map.get("neg"), Double.NEGATIVE_INFINITY);
    OptionalDouble optional = json.fromJson("\"Infinity\"", OptionalDouble.class);
    assertTrue(optional.isPresent());
    assertEquals(optional.getAsDouble(), Double.POSITIVE_INFINITY);

    assertThrows(ForyJsonException.class, () -> json.fromJson("\"1.0\"", Double.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("\"nan\"", Float.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("NaN", Double.class));
  }

  @Test
  public void writeNaturalObjectValues() {
    ForyJson json = newJson();
    String expected =
        "{\"bool\":true,\"list\":[\"a\",1,false],\"map\":{\"name\":\"fory\",\"score\":9},"
            + "\"number\":7,\"text\":\"fory\"}";
    assertEquals(json.toJson(new NaturalValues()), expected);
    assertEquals(
        new String(json.toJsonBytes(new NaturalValues()), StandardCharsets.UTF_8), expected);
  }

  @Test
  public void writeNaturalEmptyObject() {
    ForyJson json = newJson();
    String expected = "{\"value\":{}}";
    assertEquals(json.toJson(new NaturalObjectValue()), expected);
    assertEquals(
        new String(json.toJsonBytes(new NaturalObjectValue()), StandardCharsets.UTF_8), expected);
  }

  @Test
  public void readBoxedScalars() {
    ForyJson json = newJson();
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
    ForyJson json = newJson();
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
  public void readUtf8DoubleTokens() {
    assertEquals(
        new Utf8JsonReader("12.375".getBytes(StandardCharsets.UTF_8)).readDoubleTokenValue(),
        12.375d);
    assertEquals(
        Double.doubleToRawLongBits(
            new Utf8JsonReader("-0.0".getBytes(StandardCharsets.UTF_8)).readDoubleTokenValue()),
        Double.doubleToRawLongBits(-0.0d));
    assertEquals(
        new Utf8JsonReader("1.25e2".getBytes(StandardCharsets.UTF_8)).readDoubleTokenValue(),
        125.0d);
    assertThrows(
        ForyJsonException.class,
        () -> new Utf8JsonReader("01.5".getBytes(StandardCharsets.UTF_8)).readDoubleTokenValue());
  }

  @Test
  public void readLatin1DoubleTokens() {
    assertEquals(new Latin1JsonReader(latin1Bytes("12.375")).readDouble(), 12.375d);
    assertEquals(
        Double.doubleToRawLongBits(new Latin1JsonReader(latin1Bytes("-0.0")).readDouble()),
        Double.doubleToRawLongBits(-0.0d));
    assertEquals(new Latin1JsonReader(latin1Bytes("1.25e2")).readDouble(), 125.0d);
    assertThrows(
        ForyJsonException.class, () -> new Latin1JsonReader(latin1Bytes("01.5")).readDouble());
  }

  @Test
  public void readUtf8LongBlocks() {
    assertEquals(
        new Utf8JsonReader("123456789012345678".getBytes(StandardCharsets.UTF_8))
            .readLongTokenValue(),
        123456789012345678L);
    assertEquals(
        new Utf8JsonReader("-123456789012345678".getBytes(StandardCharsets.UTF_8))
            .readLongTokenValue(),
        -123456789012345678L);
    assertEquals(
        new Utf8JsonReader("9223372036854775807".getBytes(StandardCharsets.UTF_8))
            .readLongTokenValue(),
        Long.MAX_VALUE);
    assertEquals(
        new Utf8JsonReader("-9223372036854775808".getBytes(StandardCharsets.UTF_8))
            .readLongTokenValue(),
        Long.MIN_VALUE);
    assertThrows(
        ForyJsonException.class,
        () ->
            new Utf8JsonReader("9223372036854775808".getBytes(StandardCharsets.UTF_8))
                .readLongTokenValue());
  }

  @Test
  public void readLatin1LongBlocks() {
    assertEquals(
        new Latin1JsonReader(latin1Bytes("123456789012345678")).readLongTokenValue(),
        123456789012345678L);
    assertEquals(
        new Latin1JsonReader(latin1Bytes("-123456789012345678")).readLongTokenValue(),
        -123456789012345678L);
    assertEquals(
        new Latin1JsonReader(latin1Bytes("9223372036854775807")).readLongTokenValue(),
        Long.MAX_VALUE);
    assertEquals(
        new Latin1JsonReader(latin1Bytes("-9223372036854775808")).readLongTokenValue(),
        Long.MIN_VALUE);
    assertThrows(
        ForyJsonException.class,
        () -> new Latin1JsonReader(latin1Bytes("9223372036854775808")).readLongTokenValue());
    assertThrows(
        ForyJsonException.class,
        () -> new Latin1JsonReader(latin1Bytes("-9223372036854775809")).readLongTokenValue());
  }

  @Test
  public void writeNumericBoundaries() {
    ForyJson json = newJson();
    NumericBoundaries value = new NumericBoundaries();
    value.intMax = Integer.MAX_VALUE;
    value.intMin = Integer.MIN_VALUE;
    value.longMax = Long.MAX_VALUE;
    value.longMin = Long.MIN_VALUE;
    value.small = -7;
    value.text = "ok";
    String expected =
        "{\"intMax\":2147483647,\"intMin\":-2147483648,"
            + "\"longMax\":9223372036854775807,\"longMin\":-9223372036854775808,"
            + "\"small\":-7,\"text\":\"ok\"}";
    assertEquals(json.toJson(value), expected);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), expected);
  }

  @Test
  public void writeUtf16Numbers() {
    StringJsonWriter writer = new StringJsonWriter(false);
    writer.writeArrayStart();
    writer.writeString(ZH_TEXT);
    writer.writeComma(1);
    writer.writeLong(Long.MAX_VALUE);
    writer.writeComma(2);
    writer.writeLong(2_147_483_648L);
    writer.writeComma(3);
    writer.writeInt(Integer.MIN_VALUE);
    writer.writeArrayEnd();
    assertEquals(
        writer.toJson(), "[\"" + ZH_TEXT + "\",9223372036854775807,2147483648,-2147483648]");

    ForyJson json = newJson();
    Utf16NumericFields value = new Utf16NumericFields();
    value.prefix = ZH_TEXT;
    value.zero = 0;
    value.one = 7;
    value.twoDigits = 42;
    value.threeDigits = 321;
    value.fourDigits = 9999;
    value.fiveDigits = 10000;
    value.eightDigits = 99999999;
    value.nineDigits = 100000000;
    value.intMax = Integer.MAX_VALUE;
    value.intMin = Integer.MIN_VALUE;
    value.aroundIntMax = 2_147_483_648L;
    value.longMax = Long.MAX_VALUE;
    value.longMin = Long.MIN_VALUE;
    value.negative = -12345;
    String expected =
        "{\"prefix\":\""
            + ZH_TEXT
            + "\",\"zero\":0,\"one\":7,\"twoDigits\":42,\"threeDigits\":321,"
            + "\"fourDigits\":9999,\"fiveDigits\":10000,\"eightDigits\":99999999,"
            + "\"nineDigits\":100000000,\"intMax\":2147483647,"
            + "\"intMin\":-2147483648,\"aroundIntMax\":2147483648,"
            + "\"longMax\":9223372036854775807,\"longMin\":-9223372036854775808,"
            + "\"negative\":-12345}";
    assertEquals(json.toJson(value), expected);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), expected);
  }

  @Test
  public void writeReadCoreScalarFields() {
    ForyJson json = newJson();
    CoreScalarFields value = new CoreScalarFields();
    String expected =
        "{\"atomicInt\":7,\"bigDecimal\":12345.6789,\"bigInteger\":12345678901234567890,"
            + "\"builder\":\"build\",\"bytes\":[1,-2,3],\"calendar\":123456789,"
            + "\"charset\":\"UTF-8\",\"currency\":\"EUR\",\"date\":\"2026-06-21\","
            + "\"instant\":\"2026-06-21T01:02:03Z\",\"locale\":\"zh-Hans-CN\","
            + "\"maybe\":\"yes\",\"optionalInt\":4,\"timeZone\":\"UTC\","
            + "\"uri\":\"https://fory.apache.org/json\","
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
    assertEquals(read.uri, value.uri);
    assertEquals(read.uuid, value.uuid);
  }

  @Test
  public void rejectUrlByDefault() {
    ForyJson json = newJson();
    URL url = JsonTestData.url("https://fory.apache.org/");
    assertThrows(ForyJsonException.class, () -> json.toJson(url));
    assertThrows(
        ForyJsonException.class, () -> json.fromJson("\"https://fory.apache.org/\"", URL.class));
  }

  @Test
  public void registeredUrlCodec() {
    ForyJson json = newJsonBuilder().registerCodec(URL.class, new UrlStringCodec()).build();
    URL url = JsonTestData.url("https://fory.apache.org/");
    String encoded = "\"https://fory.apache.org/\"";
    assertEquals(json.toJson(url), encoded);
    assertEquals(json.fromJson(encoded, URL.class), url);
  }

  @Test
  public void writeReadAtomicScalars() {
    ForyJson json = newJson();
    assertEquals(json.toJson(new AtomicBoolean(true)), "true");
    assertEquals(json.fromJson("false", AtomicBoolean.class).get(), false);
    assertEquals(json.toJson(new AtomicInteger(12)), "12");
    assertEquals(json.fromJson("13", AtomicInteger.class).get(), 13);
    assertEquals(json.toJson(new AtomicLong(14L)), "14");
    assertEquals(json.fromJson("15", AtomicLong.class).get(), 15L);
    assertEquals(json.toJson(new AtomicReference<>("value")), "\"value\"");

    AtomicReference<String> value =
        json.fromJson("\"typed\"", new TypeRef<AtomicReference<String>>() {});
    assertEquals(value.get(), "typed");
    AtomicReference<String> nullValue =
        json.fromJson("null", new TypeRef<AtomicReference<String>>() {});
    assertEquals(nullValue.get(), null);
  }

  @Test
  public void writeUtf8ScalarFormats() {
    ForyJson json = newJson();
    UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    assertEquals(
        new String(json.toJsonBytes(uuid), StandardCharsets.UTF_8),
        "\"123e4567-e89b-12d3-a456-426614174000\"");
    assertEquals(
        new String(json.toJsonBytes(LocalDate.of(2024, 2, 3)), StandardCharsets.UTF_8),
        "\"2024-02-03\"");

    OffsetDateTimeFields fields = new OffsetDateTimeFields();
    fields.value = OffsetDateTime.of(2024, 2, 3, 4, 5, 0, 0, ZoneOffset.UTC);
    assertEquals(
        new String(json.toJsonBytes(fields), StandardCharsets.UTF_8),
        "{\"value\":\"2024-02-03T04:05Z\"}");
    fields.value = OffsetDateTime.of(2024, 2, 3, 4, 5, 0, 1_000_000, ZoneOffset.UTC);
    assertEquals(
        new String(json.toJsonBytes(fields), StandardCharsets.UTF_8),
        "{\"value\":\"2024-02-03T04:05:00.001Z\"}");
    fields.value = OffsetDateTime.of(2024, 2, 3, 4, 5, 6, 120_000_000, ZoneOffset.UTC);
    assertEquals(
        new String(json.toJsonBytes(fields), StandardCharsets.UTF_8),
        "{\"value\":\"2024-02-03T04:05:06.120Z\"}");
    fields.value = OffsetDateTime.of(2024, 2, 3, 4, 5, 6, 123_400_000, ZoneOffset.UTC);
    assertEquals(
        new String(json.toJsonBytes(fields), StandardCharsets.UTF_8),
        "{\"value\":\"2024-02-03T04:05:06.123400Z\"}");
    fields.value = OffsetDateTime.of(2024, 2, 3, 4, 5, 6, 1_000, ZoneOffset.UTC);
    assertEquals(
        new String(json.toJsonBytes(fields), StandardCharsets.UTF_8),
        "{\"value\":\"2024-02-03T04:05:06.000001Z\"}");
    fields.value = OffsetDateTime.of(2024, 2, 3, 4, 5, 6, 123456789, ZoneOffset.UTC);
    assertEquals(
        new String(json.toJsonBytes(fields), StandardCharsets.UTF_8),
        "{\"value\":\"2024-02-03T04:05:06.123456789Z\"}");

    OffsetDateTime offset =
        OffsetDateTime.of(2024, 2, 3, 4, 5, 6, 123456789, ZoneOffset.ofHoursMinutes(8, 30));
    fields.value = offset;
    assertEquals(
        new String(json.toJsonBytes(fields), StandardCharsets.UTF_8),
        "{\"value\":\"" + offset + "\"}");
  }

  @Test
  public void writeGeneratedUtf8Scalars() {
    ForyJson json = newJson();
    Utf8ScalarFields fields = new Utf8ScalarFields();
    fields.uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    fields.decimal = new BigDecimal("12345.6789");
    fields.date = LocalDate.of(2024, 2, 3);
    fields.timestamp = OffsetDateTime.of(2024, 2, 3, 4, 5, 6, 123456789, ZoneOffset.UTC);
    String expected =
        "{\"uuid\":\"123e4567-e89b-12d3-a456-426614174000\","
            + "\"decimal\":12345.6789,"
            + "\"date\":\"2024-02-03\","
            + "\"timestamp\":\"2024-02-03T04:05:06.123456789Z\"}";
    assertEquals(new String(json.toJsonBytes(fields), StandardCharsets.UTF_8), expected);
    assertEquals(json.toJson(fields), expected);
    assertGeneratedWhenSupported(json, Utf8ScalarFields.class);
  }

  @Test
  public void writeCommonScalarFastFormats() {
    ForyJson json = newJson();
    UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    assertEquals(json.toJson(new StringBuilder("build")), "\"build\"");
    assertEquals(
        new String(json.toJsonBytes(new StringBuffer("buffer")), StandardCharsets.UTF_8),
        "\"buffer\"");
    assertEquals(json.toJson(BigInteger.valueOf(123456789L)), "123456789");
    assertEquals(json.toJson(new BigDecimal("123456789")), "123456789");
    assertEquals(json.toJson(uuid), "\"123e4567-e89b-12d3-a456-426614174000\"");
    assertEquals(
        new String(json.toJsonBytes(uuid), StandardCharsets.UTF_8),
        "\"123e4567-e89b-12d3-a456-426614174000\"");

    Object[] values = {
      LocalTime.of(4, 5, 6, 123_000_000),
      LocalDateTime.of(2024, 2, 3, 4, 5, 6, 123_000_000),
      Instant.parse("2024-02-03T04:05:06.123Z"),
      Duration.ofSeconds(3661, 123_000_000),
      Duration.ofSeconds(-1, 500_000_000),
      ZoneOffset.ofHoursMinutes(8, 30),
      ZoneId.of("Asia/Shanghai"),
      ZonedDateTime.parse("2024-02-03T04:05:06.123+08:00[Asia/Shanghai]"),
      Year.of(2024),
      YearMonth.of(2024, 2),
      MonthDay.of(2, 3),
      Period.of(1, -2, 3),
      OffsetTime.of(4, 5, 6, 123_000_000, ZoneOffset.ofHours(8)),
      OffsetDateTime.of(2024, 2, 3, 4, 5, 6, 123_000_000, ZoneOffset.ofHours(8))
    };
    for (Object value : values) {
      String expected = "\"" + value + "\"";
      assertEquals(json.toJson(value), expected);
      assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), expected);
    }
    for (Year value : new Year[] {Year.of(1), Year.of(0), Year.of(-1)}) {
      String expected = "\"" + value + "\"";
      assertEquals(json.toJson(value), expected);
      assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), expected);
    }
  }

  @Test
  public void readScalarRoots() {
    ForyJson json = newJson();
    assertEquals(json.fromJson("7", int.class), Integer.valueOf(7));
    assertEquals(json.fromJson("true", boolean.class), Boolean.TRUE);
    assertEquals(json.fromJson("0.100", BigDecimal.class), new BigDecimal("0.100"));
    assertEquals(json.fromJson("\"fory\"".getBytes(StandardCharsets.UTF_8), String.class), "fory");
    assertEquals(
        json.fromJson("\"\uD83D\uDE00\u1234\"".getBytes(StandardCharsets.UTF_8), String.class),
        "\uD83D\uDE00\u1234");
    assertEquals(
        json.fromJson("0.100".getBytes(StandardCharsets.UTF_8), BigDecimal.class),
        new BigDecimal("0.100"));
    assertEquals(
        json.fromJson(
            "12345678901234567890.123".getBytes(StandardCharsets.UTF_8), BigDecimal.class),
        new BigDecimal("12345678901234567890.123"));
    assertEquals(
        json.fromJson(
            "\"123e4567-e89b-12d3-a456-426614174000\"".getBytes(StandardCharsets.UTF_8),
            UUID.class),
        UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
    assertEquals(
        json.fromJson("\"123e4567-e89b-12d3-a456-426614174000\"", UUID.class),
        UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
  }

  @Test
  public void readCommonScalarFastFormats() {
    ForyJson json = newJson();
    assertEquals(json.fromJson("123456789", BigInteger.class), BigInteger.valueOf(123456789L));
    assertEquals(json.fromJson("123456789", BigDecimal.class), new BigDecimal("123456789"));
    assertEquals(
        json.fromJson("\"04:05:06.123\"", LocalTime.class), LocalTime.of(4, 5, 6, 123_000_000));
    assertEquals(
        json.fromJson(
            "\"2024-02-03T04:05:06.123\"".getBytes(StandardCharsets.UTF_8), LocalDateTime.class),
        LocalDateTime.of(2024, 2, 3, 4, 5, 6, 123_000_000));
    assertEquals(
        json.fromJson("\"2024-02-03T04:05:06.123Z\"", Instant.class),
        Instant.parse("2024-02-03T04:05:06.123Z"));
    assertEquals(
        json.fromJson("\"PT1H1M1.123S\"", Duration.class), Duration.ofSeconds(3661, 123_000_000));
    assertEquals(json.fromJson("\"+08:30\"", ZoneOffset.class), ZoneOffset.ofHoursMinutes(8, 30));
    assertEquals(
        json.fromJson("\"2024-02-03T04:05:06.123+08:00[Asia/Shanghai]\"", ZonedDateTime.class),
        ZonedDateTime.parse("2024-02-03T04:05:06.123+08:00[Asia/Shanghai]"));
    assertEquals(json.fromJson("\"2024\"", Year.class), Year.of(2024));
    assertEquals(json.fromJson("\"1\"", Year.class), Year.of(1));
    assertEquals(json.fromJson("\"1\"".getBytes(StandardCharsets.UTF_8), Year.class), Year.of(1));
    assertEquals(json.fromJson("\"2024-02\"", YearMonth.class), YearMonth.of(2024, 2));
    assertEquals(json.fromJson("\"--02-03\"", MonthDay.class), MonthDay.of(2, 3));
    assertEquals(json.fromJson("\"P1Y-2M3D\"", Period.class), Period.of(1, -2, 3));
    assertEquals(
        json.fromJson("\"04:05:06.123+08:00\"", OffsetTime.class),
        OffsetTime.of(4, 5, 6, 123_000_000, ZoneOffset.ofHours(8)));
  }

  @Test
  public void readCommonScalarReaders() {
    String uuid = "\"123e4567-e89b-12d3-a456-426614174000\"";
    UUID expectedUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    assertEquals(
        new Utf8JsonReader(uuid.getBytes(StandardCharsets.UTF_8)).readUuid(), expectedUuid);
    assertEquals(new Latin1JsonReader(latin1Bytes(uuid)).readUuid(), expectedUuid);
    assertEquals(utf16Reader(uuid).readUuid(), expectedUuid);

    assertEquals(
        new Utf8JsonReader("123456789".getBytes(StandardCharsets.UTF_8)).readBigInteger(),
        BigInteger.valueOf(123456789L));
    assertEquals(
        new Latin1JsonReader(latin1Bytes("123456789")).readBigInteger(),
        BigInteger.valueOf(123456789L));
    assertEquals(utf16Reader("123456789").readBigInteger(), BigInteger.valueOf(123456789L));
    assertEquals(
        new Utf8JsonReader("123.45".getBytes(StandardCharsets.UTF_8)).readBigDecimal(),
        new BigDecimal("123.45"));
    assertEquals(
        new Latin1JsonReader(latin1Bytes("123.45")).readBigDecimal(), new BigDecimal("123.45"));
    assertEquals(utf16Reader("123.45").readBigDecimal(), new BigDecimal("123.45"));

    String time = "\"04:05:06.123\"";
    LocalTime expectedTime = LocalTime.of(4, 5, 6, 123_000_000);
    assertEquals(
        new Utf8JsonReader(time.getBytes(StandardCharsets.UTF_8)).readIsoLocalTime(), expectedTime);
    assertEquals(new Latin1JsonReader(latin1Bytes(time)).readIsoLocalTime(), expectedTime);
    assertEquals(utf16Reader(time).readIsoLocalTime(), expectedTime);

    String duration = "\"PT1H1M1.123S\"";
    Duration expectedDuration = Duration.ofSeconds(3661, 123_000_000);
    assertEquals(
        new Utf8JsonReader(duration.getBytes(StandardCharsets.UTF_8)).readDuration(),
        expectedDuration);
    assertEquals(new Latin1JsonReader(latin1Bytes(duration)).readDuration(), expectedDuration);
    assertEquals(utf16Reader(duration).readDuration(), expectedDuration);
  }

  @Test
  public void writeReadSqlTimeScalars() {
    ForyJson json = newJson();
    java.sql.Date date = new java.sql.Date(123456789L);
    Time time = new Time(234567890L);
    Timestamp timestamp = new Timestamp(345678901L);
    assertEquals(json.toJson(date), "123456789");
    assertEquals(json.toJson(time), "234567890");
    assertEquals(json.toJson(timestamp), "345678901");
    assertEquals(json.fromJson("123456789", java.sql.Date.class), date);
    assertEquals(json.fromJson("234567890".getBytes(StandardCharsets.UTF_8), Time.class), time);
    assertEquals(json.fromJson("345678901", Timestamp.class), timestamp);
  }

  @Test
  public void readDoubleDecimalContainers() {
    ForyJson json = newJson();
    byte[] doublesJson = "[12.5,-0.0,1.25e2]".getBytes(StandardCharsets.UTF_8);
    double[] doubles = json.fromJson(doublesJson, double[].class);
    assertEquals(doubles, new double[] {12.5d, -0.0d, 125.0d});
    Double[] boxed = json.fromJson("[12.5,null,1.25e2]", Double[].class);
    assertEquals(boxed, new Double[] {12.5d, null, 125.0d});
    List<Double> list =
        json.fromJson(
            "[12.5,-0.0,1.25e2]".getBytes(StandardCharsets.UTF_8), new TypeRef<List<Double>>() {});
    assertEquals(list, Arrays.asList(12.5d, -0.0d, 125.0d));
    Map<String, Double> map =
        json.fromJson(
            "{\"a\":12.5,\"b\":-0.0,\"c\":1.25e2}".getBytes(StandardCharsets.UTF_8),
            new TypeRef<Map<String, Double>>() {});
    Map<String, Double> expected = new LinkedHashMap<>();
    expected.put("a", 12.5d);
    expected.put("b", -0.0d);
    expected.put("c", 125.0d);
    assertEquals(map, expected);

    List<BigDecimal> decimals =
        json.fromJson(
            "[0.100,12345678901234567890.123]".getBytes(StandardCharsets.UTF_8),
            new TypeRef<List<BigDecimal>>() {});
    assertEquals(
        decimals,
        Arrays.asList(new BigDecimal("0.100"), new BigDecimal("12345678901234567890.123")));
    Map<String, BigDecimal> decimalMap =
        json.fromJson(
            "{\"small\":0.100,\"large\":12345678901234567890.123}".getBytes(StandardCharsets.UTF_8),
            new TypeRef<Map<String, BigDecimal>>() {});
    assertEquals(decimalMap.get("small"), new BigDecimal("0.100"));
    assertEquals(decimalMap.get("large"), new BigDecimal("12345678901234567890.123"));
  }

  @Test
  public void readGeneratedUtf8BigDecimal() {
    ForyJson json = newJson();
    byte[] input =
        ("{\"uuid\":\"123e4567-e89b-12d3-a456-426614174000\","
                + "\"decimal\":0.12345678901234567,"
                + "\"date\":\"2024-02-03\","
                + "\"timestamp\":\"2024-02-03T04:05:06Z\"}")
            .getBytes(StandardCharsets.UTF_8);
    Utf8ScalarFields fields = json.fromJson(input, Utf8ScalarFields.class);
    assertEquals(fields.uuid, UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
    assertEquals(fields.decimal, new BigDecimal("0.12345678901234567"));
  }

  @Test
  public void readGeneratedLatin1Scalars() {
    ForyJson json = newJson();
    String input =
        "{\"uuid\":\"123e4567-e89b-12d3-a456-426614174000\","
            + "\"decimal\":0.12345678901234567,"
            + "\"date\":\"2024-02-03\","
            + "\"timestamp\":\"2024-02-03T04:05:06.123456789Z\"}";
    Utf8ScalarFields fields = json.fromJson(input, Utf8ScalarFields.class);
    assertEquals(fields.uuid, UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
    assertEquals(fields.decimal, new BigDecimal("0.12345678901234567"));
    assertEquals(fields.date, LocalDate.of(2024, 2, 3));
    assertEquals(
        fields.timestamp, OffsetDateTime.of(2024, 2, 3, 4, 5, 6, 123456789, ZoneOffset.UTC));
  }

  @Test
  public void readUntypedLargeInteger() {
    ForyJson json = newJson();
    BigInteger unsigned = new BigInteger("18446744073709550616");
    assertEquals(json.fromJson(unsigned.toString(), Object.class), unsigned);
    JsonObject object = json.fromJson("{\"count\":18446744073709550616}", JsonObject.class);
    assertEquals(object.get("count"), unsigned);
  }

  @Test
  public void writeReadDeclaredNumber() {
    ForyJson json = newJson();
    assertEquals(json.fromJson("7", Number.class), Long.valueOf(7));
    assertEquals(
        json.fromJson("9223372036854775808", Number.class), new BigInteger("9223372036854775808"));
    assertEquals(json.fromJson("1.25e2", Number.class), Double.valueOf(125.0d));
    assertEquals(json.fromJson("7", Object.class), Long.valueOf(7));
    assertThrows(ForyJsonException.class, () -> json.fromJson("01", Number.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("1.", Number.class));

    NumberFields fields = new NumberFields();
    fields.value = Integer.valueOf(7);
    assertEquals(json.toJson(fields), "{\"value\":7}");
    fields.value = new BigDecimal("0.100");
    assertEquals(new String(json.toJsonBytes(fields), StandardCharsets.UTF_8), "{\"value\":0.100}");

    NumberFields read = json.fromJson("{\"value\":9223372036854775808}", NumberFields.class);
    assertEquals(read.value, new BigInteger("9223372036854775808"));
    assertThrows(ForyJsonException.class, () -> json.toJson(new CustomNumber()));
    assertThrows(ForyJsonException.class, () -> json.fromJson("1", CustomNumber.class));
    fields.value = new CustomNumber();
    assertThrows(ForyJsonException.class, () -> json.toJson(fields));
  }

  @Test
  public void writeReadCharSequence() {
    ForyJson json = newJson();
    CharSequence root = json.fromJson("\"fory\"", CharSequence.class);
    assertEquals(root, "fory");
    assertEquals(root.getClass(), String.class);

    CharSequenceFields fields = new CharSequenceFields();
    fields.text = new StringBuilder("build");
    assertEquals(json.toJson(fields), "{\"text\":\"build\"}");
    fields = json.fromJson("{\"text\":\"中文\"}", CharSequenceFields.class);
    assertEquals(fields.text, "中文");
    assertEquals(fields.text.getClass(), String.class);
    assertThrows(ForyJsonException.class, () -> json.toJson(new CustomCharSequence("x")));
  }

  @Test
  public void writeReadBitSet() {
    ForyJson json = newJson();
    BitSet empty = new BitSet();
    assertEquals(json.toJson(empty), "[]");
    assertEquals(json.fromJson("[]", BitSet.class), empty);

    BitSet bits = new BitSet();
    bits.set(0);
    bits.set(63);
    bits.set(130);
    String encoded = json.toJson(bits);
    assertEquals(json.fromJson(encoded, BitSet.class), bits);
    assertEquals(json.fromJson(encoded.getBytes(StandardCharsets.UTF_8), BitSet.class), bits);

    BitSet dense = new BitSet();
    dense.set(0, 70);
    assertEquals(json.fromJson(json.toJson(dense), BitSet.class), dense);

    BitSetFields fields = new BitSetFields();
    fields.value = bits;
    BitSetFields read = json.fromJson(json.toJson(fields), BitSetFields.class);
    assertEquals(read.value, bits);
    assertEquals(json.fromJson("{\"value\":null}", BitSetFields.class).value, null);
    assertThrows(ForyJsonException.class, () -> json.fromJson("[1.5]", BitSet.class));
  }

  @Test
  public void writeReadChronoDates() {
    ForyJson json = newJson();
    LocalDate iso = LocalDate.of(2024, 2, 3);
    ChronoDateFields fields = new ChronoDateFields();
    fields.hijrah = HijrahChronology.INSTANCE.date(iso);
    fields.japanese = JapaneseDate.from(iso);
    fields.minguo = MinguoDate.from(iso);
    fields.thai = ThaiBuddhistDate.from(iso);

    assertEquals(json.fromJson(json.toJson(fields.hijrah), HijrahDate.class), fields.hijrah);
    assertEquals(json.fromJson(json.toJson(fields.japanese), JapaneseDate.class), fields.japanese);
    assertEquals(json.fromJson(json.toJson(fields.minguo), MinguoDate.class), fields.minguo);
    assertEquals(json.fromJson(json.toJson(fields.thai), ThaiBuddhistDate.class), fields.thai);

    String encoded = json.toJson(fields);
    ChronoDateFields read = json.fromJson(encoded, ChronoDateFields.class);
    assertEquals(read.hijrah, fields.hijrah);
    assertEquals(read.japanese, fields.japanese);
    assertEquals(read.minguo, fields.minguo);
    assertEquals(read.thai, fields.thai);
    assertChronoRejects(json);
  }

  @Test
  public void rejectNetworkAddressTypes() {
    ForyJson json = newJson();
    assertThrows(ForyJsonException.class, () -> json.toJson(InetAddress.getLoopbackAddress()));
    assertThrows(
        ForyJsonException.class,
        () -> json.toJson(InetSocketAddress.createUnresolved("example.com", 80)));
    assertThrows(ForyJsonException.class, () -> json.fromJson("\"127.0.0.1\"", InetAddress.class));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"host\":\"example.com\",\"port\":80}", InetSocketAddress.class));
  }

  @Test
  public void rejectClassTypeByDefault() {
    ForyJson json = newJson();
    assertThrows(ForyJsonException.class, () -> json.toJson(String.class));
    assertThrows(ForyJsonException.class, () -> json.toJson(int.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("\"java.lang.String\"", Class.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("\"int\"", Class.class));
  }

  @Test
  public void skipClassFields() {
    ForyJson json = newJson();
    assertEquals(json.toJson(new ClassFieldHolder()), "{}");
    ClassFieldHolder holder =
        json.fromJson("{\"type\":\"java.lang.Integer\"}", ClassFieldHolder.class);
    assertEquals(holder.type, String.class);
  }

  @Test
  public void rejectClassArrays() {
    ForyJson json = newJson();
    assertThrows(ForyJsonException.class, () -> json.toJson(new Class<?>[] {String.class}));
    assertThrows(
        ForyJsonException.class, () -> json.fromJson("[\"java.lang.String\"]", Class[].class));
    assertThrows(ForyJsonException.class, () -> json.toJson(new ClassArrayFields()));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"types\":[\"java.lang.String\"]}", ClassArrayFields.class));
  }

  @Test
  public void writeReadFileAndPath() {
    ForyJson json = newJson();
    File file = new File("fory-json-file.txt");
    Path path = Paths.get("fory-json-path.txt");
    assertEquals(json.toJson(file), "\"fory-json-file.txt\"");
    assertEquals(json.fromJson("\"fory-json-file.txt\"", File.class), file);
    assertEquals(json.toJson(path), "\"fory-json-path.txt\"");
    assertEquals(json.fromJson("\"fory-json-path.txt\"", Path.class), path);

    FilePathFields fields =
        json.fromJson(
            "{\"file\":\"fory-json-file.txt\",\"path\":\"fory-json-path.txt\"}",
            FilePathFields.class);
    assertEquals(fields.file, file);
    assertEquals(fields.path, path);
    assertEquals(
        json.toJson(fields), "{\"file\":\"fory-json-file.txt\",\"path\":\"fory-json-path.txt\"}");
  }

  @Test
  public void readLocalDateFromDateTime() {
    ForyJson json = newJson();
    LocalDate expected = LocalDate.of(2023, 7, 2);
    assertEquals(json.fromJson("\"2023-07-02T16:00:00.000Z\"", LocalDate.class), expected);
    assertEquals(
        json.fromJson(
            "\"2023-07-02T16:00:00.000Z\"".getBytes(StandardCharsets.UTF_8), LocalDate.class),
        expected);
    LocalDateFields fields =
        json.fromJson("{\"value\":\"2023-07-02T16:00:00.000Z\"}", LocalDateFields.class);
    assertEquals(fields.value, expected);
  }

  @Test
  public void readLocalDateFallbackForms() {
    ForyJson json = newJson();
    LocalDate extended = LocalDate.of(10000, 2, 3);
    assertEquals(json.fromJson("\"+10000-02-03\"", LocalDate.class), extended);
    assertEquals(
        json.fromJson("\"+10000-02-03\"".getBytes(StandardCharsets.UTF_8), LocalDate.class),
        extended);
    assertEquals(utf16Reader("\"+10000-02-03\"").readIsoLocalDate(), extended);
    assertThrows(RuntimeException.class, () -> json.fromJson("\"2024-99-03\"", LocalDate.class));
  }

  @Test
  public void readOffsetDateTime() {
    ForyJson json = newJson();
    OffsetDateTime utc = OffsetDateTime.of(2024, 2, 3, 4, 5, 6, 0, ZoneOffset.UTC);
    assertEquals(json.fromJson("\"2024-02-03T04:05:06Z\"", OffsetDateTime.class), utc);
    assertEquals(
        json.fromJson(
            "\"2024-02-03T04:05:06\\u005A\"".getBytes(StandardCharsets.UTF_8),
            OffsetDateTime.class),
        utc);

    OffsetDateTime nanos =
        OffsetDateTime.of(2024, 2, 3, 4, 5, 6, 123456789, ZoneOffset.ofHoursMinutes(8, 30));
    assertEquals(
        json.fromJson(
            "\"2024-02-03T04:05:06.123456789+08:30\"".getBytes(StandardCharsets.UTF_8),
            OffsetDateTime.class),
        nanos);

    OffsetDateTime minutePrecision =
        OffsetDateTime.of(2024, 2, 3, 4, 5, 0, 0, ZoneOffset.ofHoursMinutes(-5, -30));
    OffsetDateTimeFields fields =
        json.fromJson("{\"value\":\"2024-02-03T04:05-05:30\"}", OffsetDateTimeFields.class);
    assertEquals(fields.value, minutePrecision);
  }

  @Test
  public void readOffsetDateTimeFallbackForms() {
    ForyJson json = newJson();
    OffsetDateTime extended = OffsetDateTime.of(10000, 2, 3, 4, 5, 6, 0, ZoneOffset.ofHours(8));
    String input = "\"+10000-02-03T04:05:06+08:00\"";
    assertEquals(json.fromJson(input, OffsetDateTime.class), extended);
    assertEquals(
        json.fromJson(input.getBytes(StandardCharsets.UTF_8), OffsetDateTime.class), extended);
    assertEquals(utf16Reader(input).readIsoOffsetDateTime(), extended);
    assertThrows(
        RuntimeException.class,
        () -> json.fromJson("\"2024-02-03T04:05:06+99:00\"", OffsetDateTime.class));
  }

  @Test
  public void rejectInvalidTemporalStrings() {
    ForyJson json = newJson();
    assertEquals(
        json.fromJson("\"04:05:06.123456789\"", LocalTime.class), LocalTime.of(4, 5, 6, 123456789));
    assertThrows(
        ForyJsonException.class, () -> json.fromJson("\"04:05:06.1234567890\"", LocalTime.class));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("\"04:05:06.1234567890+08:00\"", OffsetTime.class));
    assertThrows(
        ForyJsonException.class,
        () ->
            json.fromJson(
                "\"2024-02-03T04:05:06.1234567890+08:30\"".getBytes(StandardCharsets.UTF_8),
                OffsetDateTime.class));
    assertThrows(
        ForyJsonException.class,
        () -> utf16Reader("\"2024-02-03T04:05:06.1234567890+08:30\"").readIsoOffsetDateTime());
  }

  @Test
  public void rejectNumericTemporalTokens() {
    ForyJson json = newJson();
    assertThrows(ForyJsonException.class, () -> json.fromJson("1.25", Instant.class));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("1.25".getBytes(StandardCharsets.UTF_8), Instant.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("1.25", Duration.class));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("1.25".getBytes(StandardCharsets.UTF_8), Duration.class));
  }

  @Test
  public void readUtf16TemporalScalars() {
    LocalDate date = LocalDate.of(2023, 7, 2);
    Utf16JsonReader dateReader = utf16Reader("\"2023-07-02T16:00:00.000Z\"");
    assertEquals(dateReader.readIsoLocalDate(), date);
    dateReader.finish();

    OffsetDateTime timestamp =
        OffsetDateTime.of(2024, 2, 3, 4, 5, 6, 123456789, ZoneOffset.ofHoursMinutes(8, 30));
    Utf16JsonReader timestampReader = utf16Reader("\"2024-02-03T04:05:06.123456789+08:30\"");
    assertEquals(timestampReader.readIsoOffsetDateTime(), timestamp);
    timestampReader.finish();

    ForyJson json = newJson();
    Utf16TemporalFields fields =
        json.fromJson(
            "{\"text\":\"中文\",\"date\":\"2023-07-02\","
                + "\"timestamp\":\"2024-02-03T04:05:06.123456789+08:30\"}",
            Utf16TemporalFields.class);
    assertEquals(fields.text, "中文");
    assertEquals(fields.date, date);
    assertEquals(fields.timestamp, timestamp);
  }

  @Test
  public void objectFieldUsesUtf8Codec() {
    ForyJson json =
        newJsonBuilder().registerCodec(ModeAwareValue.class, new ModeAwareCodec()).build();
    ModeAwareHolder holder =
        json.fromJson("{\"value\":{}}".getBytes(StandardCharsets.UTF_8), ModeAwareHolder.class);
    assertEquals(holder.value.mode, codegenEnabled() ? "utf8" : "generic");
  }

  @Test
  public void byteInputUsesUtf8Codec() {
    ForyJson json =
        newJsonBuilder().registerCodec(ModeAwareValue.class, new ModeAwareCodec()).build();
    ModeAwareValue value =
        json.fromJson("{}".getBytes(StandardCharsets.UTF_8), ModeAwareValue.class);
    assertEquals(value.mode, "utf8");
  }

  @Test
  public void stringInputUsesLatin1Codec() {
    ForyJson json =
        newJsonBuilder().registerCodec(ModeAwareValue.class, new ModeAwareCodec()).build();
    ModeAwareValue value = json.fromJson("{}", ModeAwareValue.class);
    String expected = StringSerializer.isBytesBackedString() ? "latin1" : "utf16";
    assertEquals(value.mode, expected);
  }

  @Test
  public void stringInputUsesUtf16Codec() {
    ForyJson json =
        newJsonBuilder().registerCodec(ModeAwareValue.class, new ModeAwareCodec()).build();
    ModeAwareValue value = json.fromJson("{\"ignored\":\"\u0100\"}", ModeAwareValue.class);
    assertEquals(value.mode, "utf16");
  }

  @Test
  public void rejectMalformedStringScalar() {
    ForyJson json = newJson();
    assertThrows(
        RuntimeException.class,
        () -> json.fromJson("\"2024-02-03 04:05:06\"", LocalDateTime.class));
  }

  @Test
  public void guardBigIntegerLength() {
    ForyJson json = newJson();
    String accepted = repeat('1', BIG_NUMBER_LIMIT);
    assertEquals(
        json.fromJson(accepted.getBytes(StandardCharsets.UTF_8), BigInteger.class),
        new BigInteger(accepted));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson(repeat('1', BIG_NUMBER_LIMIT + 1), BigInteger.class));
  }

  @Test
  public void guardBigDecimalLength() {
    ForyJson json = newJson();
    String accepted = repeat('1', BIG_NUMBER_LIMIT);
    assertEquals(
        json.fromJson(accepted.getBytes(StandardCharsets.UTF_8), BigDecimal.class),
        new BigDecimal(accepted));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson(repeat('1', BIG_NUMBER_LIMIT + 1), BigDecimal.class));
  }

  @Test
  public void guardBigDecimalScale() {
    ForyJson json = newJson();
    assertThrows(ForyJsonException.class, () -> json.fromJson("1e-10001", BigDecimal.class));
    String fastPathScale = "0." + repeat('0', BIG_NUMBER_LIMIT) + "1";
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson(fastPathScale.getBytes(StandardCharsets.UTF_8), BigDecimal.class));
  }

  @Test
  public void guardUntypedBigIntegerFallback() {
    ForyJson json = newJson();
    String oversized = repeat('1', BIG_NUMBER_LIMIT + 1);
    assertThrows(ForyJsonException.class, () -> json.fromJson(oversized, Object.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson(oversized, Number.class));
  }

  @Test
  public void primitiveOverflowRemainsOverflow() {
    String oversized = repeat('1', BIG_NUMBER_LIMIT + 1);
    ForyJsonException intError =
        expectThrows(
            ForyJsonException.class,
            () -> new Utf8JsonReader(oversized.getBytes(StandardCharsets.UTF_8)).readInt());
    assertTrue(intError.getMessage().contains("Integer overflow"));
    ForyJsonException longError =
        expectThrows(
            ForyJsonException.class, () -> new Latin1JsonReader(latin1Bytes(oversized)).readLong());
    assertTrue(longError.getMessage().contains("Long overflow"));
  }

  @Test
  public void concreteDoubleReadersParseFastValues() {
    String token = "12.5";
    byte[] utf8 = token.getBytes(StandardCharsets.UTF_8);
    byte[] latin1 = latin1Bytes(token);
    assertEquals(new Utf8JsonReader(utf8).readDouble(), 12.5d);
    assertEquals(new Utf8JsonReader(utf8).readDoubleTokenValue(), 12.5d);
    assertEquals(new Latin1JsonReader(latin1).readDouble(), 12.5d);
    assertEquals(new Latin1JsonReader(latin1).readDoubleTokenValue(), 12.5d);
  }

  @Test
  public void readFloatAvoidsDoubleRounding() {
    String token = "1.0000000596046448";
    int expected = Float.floatToRawIntBits(Float.parseFloat(token));
    assertEquals(
        Float.floatToRawIntBits(
            new Utf8JsonReader(token.getBytes(StandardCharsets.UTF_8)).readFloat()),
        expected);
    assertEquals(
        Float.floatToRawIntBits(new Latin1JsonReader(latin1Bytes(token)).readFloat()), expected);
    assertEquals(Float.floatToRawIntBits(utf16Reader(token).readFloat()), expected);
  }

  @Test
  public void rejectLeadingZero() {
    ForyJson json = newJson();
    assertThrows(ForyJsonException.class, () -> json.fromJson("01", int.class));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"id\":01}".getBytes(StandardCharsets.UTF_8), PublicFields.class));
  }

  public static final class ClassFieldHolder {
    public Class<?> type = String.class;
  }

  private static byte[] latin1Bytes(String value) {
    return value.getBytes(StandardCharsets.ISO_8859_1);
  }

  public static final class ClassArrayFields {
    public Class<?>[] types = new Class<?>[] {String.class};
  }

  public static final class FilePathFields {
    public File file;
    public Path path;
  }

  public static final class LocalDateFields {
    public LocalDate value;
  }

  public static final class OffsetDateTimeFields {
    public OffsetDateTime value;
  }

  public static final class Utf8ScalarFields {
    public UUID uuid;
    public BigDecimal decimal;
    public LocalDate date;
    public OffsetDateTime timestamp;
  }

  public static final class Utf16TemporalFields {
    public String text;
    public LocalDate date;
    public OffsetDateTime timestamp;
  }

  public static final class NumberFields {
    public Number value;
  }

  public static final class CharSequenceFields {
    public CharSequence text;
  }

  public static final class BitSetFields {
    public BitSet value;
  }

  public static final class ChronoDateFields {
    public HijrahDate hijrah;
    public JapaneseDate japanese;
    public MinguoDate minguo;
    public ThaiBuddhistDate thai;
  }

  public static final class Utf16NumericFields {
    public String prefix;
    public int zero;
    public int one;
    public int twoDigits;
    public int threeDigits;
    public int fourDigits;
    public int fiveDigits;
    public int eightDigits;
    public int nineDigits;
    public int intMax;
    public int intMin;
    public long aroundIntMax;
    public long longMax;
    public long longMin;
    public int negative;
  }

  public static final class NonFiniteNumbers {
    public double nan;
    public float neg;
    public Double pos;
    public Float boxed;
  }

  public static final class ModeAwareHolder {
    public ModeAwareValue value;
  }

  public static final class ModeAwareValue {
    public final String mode;

    ModeAwareValue(String mode) {
      this.mode = mode;
    }
  }

  private static final class CustomNumber extends Number {
    @Override
    public int intValue() {
      return 1;
    }

    @Override
    public long longValue() {
      return 1;
    }

    @Override
    public float floatValue() {
      return 1;
    }

    @Override
    public double doubleValue() {
      return 1;
    }
  }

  private static final class CustomCharSequence implements CharSequence {
    private final String value;

    private CustomCharSequence(String value) {
      this.value = value;
    }

    @Override
    public int length() {
      return value.length();
    }

    @Override
    public char charAt(int index) {
      return value.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return value.subSequence(start, end);
    }

    @Override
    public String toString() {
      return value;
    }
  }

  private static void assertChronoRejects(ForyJson json) {
    try {
      json.fromJson("\"not-a-date\"", HijrahDate.class);
      throw new AssertionError("Expected ForyJsonException");
    } catch (ForyJsonException e) {
      assertTrue(e.getCause() instanceof DateTimeException);
    }
  }

  private static final class UrlStringCodec implements JsonCodec {
    @Override
    public void write(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeString(value.toString());
    }

    @Override
    public void writeString(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeString(value.toString());
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeString(value.toString());
    }

    @Override
    public Object read(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      return JsonTestData.url(reader.readString());
    }
  }

  private static final class ModeAwareCodec implements JsonCodec {
    @Override
    public void write(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeNull();
    }

    @Override
    public void writeString(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeNull();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeNull();
    }

    @Override
    public Object read(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.skipValue();
      return new ModeAwareValue("generic");
    }

    @Override
    public Object readLatin1(
        Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.skipValue();
      return new ModeAwareValue("latin1");
    }

    @Override
    public Object readUtf16(
        Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.skipValue();
      return new ModeAwareValue("utf16");
    }

    @Override
    public Object readUtf8(
        Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.skipValue();
      return new ModeAwareValue("utf8");
    }
  }

  private static Utf16JsonReader utf16Reader(String input) {
    byte[] bytes = new byte[input.length() << 1];
    StringSerializer.copyStringCharsToBytes(input, bytes);
    return new Utf16JsonReader().reset(input, bytes);
  }
}

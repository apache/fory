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

import static org.apache.fory.json.JsonTestSupport.newLatin1Reader;
import static org.apache.fory.json.JsonTestSupport.newStringWriter;
import static org.apache.fory.json.JsonTestSupport.newUtf16Reader;
import static org.apache.fory.json.JsonTestSupport.newUtf8Reader;
import static org.apache.fory.json.JsonTestSupport.newUtf8Writer;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
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
import java.util.Locale;
import java.util.Random;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.codec.ScalarCodecs;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.testng.annotations.Test;

public class JsonTemporalTest extends ForyJsonTestModels {
  @Test
  public void readYearSlices() {
    Utf8JsonReader reader = newUtf8Reader(new byte[0]);
    for (int value : new int[] {0, 1, 999, 1000, 2024, 9999}) {
      String text = String.format(Locale.ROOT, "%04d", value);
      assertToken(ScalarCodecs.YearCodec.INSTANCE, text, Year.of(value));
      byte[] token = ('"' + text + '"').getBytes(StandardCharsets.UTF_8);
      for (int offset = 0; offset < 8; offset++) {
        byte[] bytes = new byte[offset + token.length + 8];
        System.arraycopy(token, 0, bytes, offset, token.length);
        for (int length = 0; length < token.length; length++) {
          reader.reset(bytes, offset, length);
          assertThrows(ForyJsonException.class, () -> reader.readYear());
        }
        reader.reset(bytes, offset, token.length);
        assertEquals(reader.readYear(), Year.of(value));
        reader.finish();
        for (int digit = 1; digit <= 4; digit++) {
          byte saved = bytes[offset + digit];
          bytes[offset + digit] = 'x';
          reader.reset(bytes, offset, token.length);
          assertThrows(ForyJsonException.class, () -> reader.readYear());
          bytes[offset + digit] = saved;
        }
      }
    }
    for (int value : new int[] {-999999999, -10000, -1, 0, 1, 10000, 999999999}) {
      assertToken(ScalarCodecs.YearCodec.INSTANCE, Integer.toString(value), Year.of(value));
    }
    assertToken(ScalarCodecs.YearCodec.INSTANCE, "+2024", Year.of(2024));
    assertEscapes(ScalarCodecs.YearCodec.INSTANCE, Year.of(2024));
  }

  @Test
  public void readZoneOffsetSlices() {
    Utf8JsonReader reader = newUtf8Reader(new byte[0]);
    for (int seconds = -64800; seconds <= 64800; seconds++) {
      ZoneOffset expected = ZoneOffset.ofTotalSeconds(seconds);
      byte[] token = ('"' + expected.getId() + '"').getBytes(StandardCharsets.UTF_8);
      reader.reset(token);
      assertEquals(ScalarCodecs.ZoneOffsetCodec.INSTANCE.readUtf8(reader), expected);
      reader.finish();
    }
    for (String text : new String[] {"Z", "+01:30", "-07:20:13", "+18:00", "-18:00"}) {
      ZoneOffset expected = ZoneOffset.of(text);
      assertToken(ScalarCodecs.ZoneOffsetCodec.INSTANCE, text, expected);
      byte[] token = ('"' + text + '"').getBytes(StandardCharsets.UTF_8);
      for (int offset = 0; offset < 8; offset++) {
        byte[] bytes = new byte[offset + token.length + 8];
        System.arraycopy(token, 0, bytes, offset, token.length);
        for (int length = 0; length < token.length; length++) {
          reader.reset(bytes, offset, length);
          assertThrows(RuntimeException.class, () -> reader.readZoneOffset());
        }
        reader.reset(bytes, offset, token.length);
        assertEquals(reader.readZoneOffset(), expected);
        reader.finish();
      }
      assertEscapes(ScalarCodecs.ZoneOffsetCodec.INSTANCE, expected);
    }
    for (String text : new String[] {"+1", "-01", "+0130", "-072013", "+00", "-00"}) {
      assertToken(ScalarCodecs.ZoneOffsetCodec.INSTANCE, text, ZoneOffset.of(text));
    }
    for (String text : new String[] {"+19:00", "-18:00:01", "+0x:30", "+01:x0", "+01:30:0x"}) {
      rejectToken(ScalarCodecs.ZoneOffsetCodec.INSTANCE, text);
    }
    byte[] input = " [null, \"+01:30\",\"Z\"]".getBytes(StandardCharsets.UTF_8);
    ForyJson json = ForyJson.builder().build();
    assertEquals(
        json.fromJson(input, ZoneOffset[].class),
        new ZoneOffset[] {null, ZoneOffset.ofHoursMinutes(1, 30), ZoneOffset.UTC});
    assertThrows(
        RuntimeException.class,
        () ->
            json.fromJson("[\"-18:00:01\"]".getBytes(StandardCharsets.UTF_8), ZoneOffset[].class));
    assertEquals(
        json.fromJson(input, ZoneOffset[].class),
        new ZoneOffset[] {null, ZoneOffset.ofHoursMinutes(1, 30), ZoneOffset.UTC});
  }

  @Test
  public void readTemporalComponents() {
    Random random = new Random(8045792L);
    ZoneId[] zones = {
      ZoneOffset.UTC,
      ZoneOffset.ofHoursMinutesSeconds(-7, -20, -13),
      ZoneId.of("Europe/Paris"),
      ZoneId.of("America/New_York")
    };
    for (int i = 0; i < 256; i++) {
      LocalDate date = LocalDate.ofEpochDay(random.nextInt(200_000) - 100_000);
      LocalTime time =
          LocalTime.ofSecondOfDay(random.nextInt(86_400)).withNano(random.nextInt(1_000_000_000));
      LocalDateTime dateTime = LocalDateTime.of(date, time);
      Instant instant = dateTime.toInstant(ZoneOffset.UTC);
      OffsetTime offsetTime = OffsetTime.of(time, ZoneOffset.ofTotalSeconds((i - 128) * 60));
      ZonedDateTime zoned = dateTime.atZone(zones[i % zones.length]);
      YearMonth yearMonth = YearMonth.from(date);
      MonthDay monthDay = MonthDay.from(date);
      Duration duration = Duration.ofSeconds(random.nextLong(), random.nextInt(1_000_000_000));
      Period period = Period.of(random.nextInt(), random.nextInt(), random.nextInt());
      assertToken(ScalarCodecs.LocalTimeCodec.INSTANCE, time.toString(), time);
      assertToken(ScalarCodecs.LocalDateTimeCodec.INSTANCE, dateTime.toString(), dateTime);
      assertToken(ScalarCodecs.InstantCodec.INSTANCE, instant.toString(), instant);
      assertToken(ScalarCodecs.OffsetTimeCodec.INSTANCE, offsetTime.toString(), offsetTime);
      assertToken(ScalarCodecs.ZonedDateTimeCodec.INSTANCE, zoned.toString(), zoned);
      assertToken(ScalarCodecs.YearMonthCodec.INSTANCE, yearMonth.toString(), yearMonth);
      assertToken(ScalarCodecs.MonthDayCodec.INSTANCE, monthDay.toString(), monthDay);
      assertToken(ScalarCodecs.DurationCodec.INSTANCE, duration.toString(), duration);
      assertToken(ScalarCodecs.PeriodCodec.INSTANCE, period.toString(), period);
    }
  }

  @Test
  public void readTemporalGrammar() {
    for (String text :
        new String[] {"00:00", "23:59:59", "12:30:45.", "12:30:45.1", "12:30:45.000000001"}) {
      assertToken(ScalarCodecs.LocalTimeCodec.INSTANCE, text, LocalTime.parse(text));
    }
    for (String text :
        new String[] {
          "2024-02-29T23:59:59.999999999Z",
          "2016-12-31T23:59:60Z",
          "2020-01-01T24:00:00Z",
          "2020-01-01t00:00:00z",
          "2020-01-01T00:00:00+01:00",
          Instant.MIN.toString(),
          Instant.MAX.toString()
        }) {
      Instant expected;
      try {
        expected = Instant.parse(text);
      } catch (java.time.format.DateTimeParseException e) {
        // JDK 8's ISO_INSTANT grammar accepts only UTC offsets.
        rejectToken(ScalarCodecs.InstantCodec.INSTANCE, text);
        continue;
      }
      assertToken(ScalarCodecs.InstantCodec.INSTANCE, text, expected);
    }
    for (String text :
        new String[] {
          "2024-03-31T02:30:00+01:00[Europe/Paris]",
          "2024-10-27T02:30:00+02:00[Europe/Paris]",
          "2024-10-27T02:30:00+01:00[Europe/Paris]",
          "2024-01-01T00:00:00+03:00[Europe/Paris]",
          "+10000-01-01T12:30:00Z",
          "-0001-01-01T00:00:00-01:02:03"
        }) {
      int bracket = text.indexOf('[');
      ZonedDateTime expected =
          bracket < 0
              ? OffsetDateTime.parse(text).toZonedDateTime()
              : OffsetDateTime.parse(text.substring(0, bracket))
                  .atZoneSameInstant(ZoneId.of(text.substring(bracket + 1, text.length() - 1)));
      assertToken(ScalarCodecs.ZonedDateTimeCodec.INSTANCE, text, expected);
    }
    for (String text :
        new String[] {
          "PT0S",
          "PT1H2M3.000000001S",
          "PT1.1S",
          "PT1.S",
          "P2D",
          "-PT1H",
          "pt1h2m",
          Duration.ofSeconds(Long.MAX_VALUE, 999999999).toString(),
          Duration.ofSeconds(Long.MIN_VALUE).toString()
        }) {
      assertToken(ScalarCodecs.DurationCodec.INSTANCE, text, Duration.parse(text));
    }
    for (String text : new String[] {"P0D", "P1Y2M3D", "P-2147483648Y", "P2W", "-P1Y2M", "p1y"}) {
      assertToken(ScalarCodecs.PeriodCodec.INSTANCE, text, Period.parse(text));
    }
    assertToken(
        ScalarCodecs.DurationCodec.INSTANCE,
        "PT-1H-30M-0.1S",
        Duration.ofSeconds(-5401, 900000000));
    assertToken(ScalarCodecs.YearMonthCodec.INSTANCE, "+10000-01", YearMonth.of(10000, 1));
    assertToken(ScalarCodecs.LocalTimeCodec.INSTANCE, "12\\u003a30", LocalTime.of(12, 30));
  }

  @Test
  public void rejectInvalidTemporalComponents() {
    rejectToken(ScalarCodecs.LocalTimeCodec.INSTANCE, "24:00");
    rejectToken(ScalarCodecs.LocalTimeCodec.INSTANCE, "12:60:00");
    rejectToken(ScalarCodecs.LocalTimeCodec.INSTANCE, "12:00:60");
    rejectToken(ScalarCodecs.LocalTimeCodec.INSTANCE, "12:30:45.1234567890");
    rejectToken(ScalarCodecs.LocalDateTimeCodec.INSTANCE, "2023-02-29T12:30:00");
    rejectToken(ScalarCodecs.OffsetTimeCodec.INSTANCE, "12:30:00+01:60");
    rejectToken(ScalarCodecs.OffsetTimeCodec.INSTANCE, "12:30:00Z00:00");
    rejectToken(ScalarCodecs.MonthDayCodec.INSTANCE, "--02-30");
    rejectToken(ScalarCodecs.YearMonthCodec.INSTANCE, "2024-13");
    for (String text :
        new String[] {"PT", "PT1.2H", "PT1S1H", "PT9223372036854775808S", "PT1.1234567890S"}) {
      rejectToken(ScalarCodecs.DurationCodec.INSTANCE, text);
    }
    for (String text : new String[] {"P", "P1D1Y", "P2147483648D"}) {
      rejectToken(ScalarCodecs.PeriodCodec.INSTANCE, text);
    }
  }

  @Test
  public void readEscapedTemporal() {
    LocalTime time = LocalTime.of(12, 34, 56, 123456789);
    LocalDateTime dateTime = LocalDateTime.of(LocalDate.of(2024, 2, 29), time);
    ZoneOffset offset = ZoneOffset.ofHoursMinutesSeconds(-7, -20, -13);
    assertEscapes(ScalarCodecs.LocalDateCodec.INSTANCE, dateTime.toLocalDate());
    assertEscapes(ScalarCodecs.LocalTimeCodec.INSTANCE, time);
    assertEscapes(ScalarCodecs.LocalDateTimeCodec.INSTANCE, dateTime);
    assertEscapes(ScalarCodecs.InstantCodec.INSTANCE, dateTime.toInstant(ZoneOffset.UTC));
    assertEscapes(ScalarCodecs.OffsetTimeCodec.INSTANCE, OffsetTime.of(time, offset));
    assertEscapes(ScalarCodecs.OffsetDateTimeCodec.INSTANCE, OffsetDateTime.of(dateTime, offset));
    assertEscapes(
        ScalarCodecs.ZonedDateTimeCodec.INSTANCE, dateTime.atZone(ZoneId.of("Europe/Paris")));
    assertEscapes(ScalarCodecs.YearMonthCodec.INSTANCE, YearMonth.of(2024, 2));
    assertEscapes(ScalarCodecs.MonthDayCodec.INSTANCE, MonthDay.of(2, 29));
  }

  private static <T> void assertEscapes(JsonValueCodec<T> codec, T value) {
    String text = value.toString();
    for (int i = 0; i < text.length(); i++) {
      String escaped =
          text.substring(0, i)
              + String.format("\\u%04x", (int) text.charAt(i))
              + text.substring(i + 1);
      assertToken(codec, escaped, value);
    }
  }

  @Test
  public void writeInstantBoundaries() {
    long[] seconds = {
      Instant.MIN.getEpochSecond(),
      Instant.MAX.getEpochSecond(),
      -1,
      0,
      LocalDate.of(-9999, 1, 1).atStartOfDay().toEpochSecond(ZoneOffset.UTC),
      LocalDate.of(-1, 1, 1).atStartOfDay().toEpochSecond(ZoneOffset.UTC),
      LocalDate.of(0, 1, 1).atStartOfDay().toEpochSecond(ZoneOffset.UTC),
      LocalDate.of(9999, 12, 31).atStartOfDay().toEpochSecond(ZoneOffset.UTC),
      LocalDate.of(10000, 1, 1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
    };
    int[] nanos = {0, 1, 1000, 1_000_000, 1_000_010, 123456789, 999999999};
    for (long second : seconds) {
      for (int nano : nanos) {
        Instant value = Instant.ofEpochSecond(second, nano);
        for (int capacity = 0; capacity <= 40; capacity++) {
          Utf8JsonWriter writer = newUtf8Writer(new byte[capacity]);
          String prefix = "       ".substring(0, capacity & 7);
          writer.writeRawValue(prefix);
          writer.writeIsoInstant(second, nano);
          assertEquals(
              new String(writer.toJsonBytes(), StandardCharsets.UTF_8),
              prefix + '"' + value.toString() + '"');
        }
      }
    }
    for (int nano : nanos) {
      assertWriter(ScalarCodecs.DurationCodec.INSTANCE, Duration.ofSeconds(3661, nano));
    }
  }

  @Test
  public void writeZoneOffsetTokens() {
    Utf8JsonWriter writer = newUtf8Writer(new byte[0]);
    for (int seconds = -64800; seconds <= 64800; seconds++) {
      ZoneOffset value = ZoneOffset.ofTotalSeconds(seconds);
      writer.reset();
      ScalarCodecs.ZoneOffsetCodec.INSTANCE.writeUtf8(writer, value);
      assertEquals(
          new String(writer.toJsonBytes(), StandardCharsets.UTF_8), '"' + value.getId() + '"');
    }
    for (int seconds : new int[] {0, 1, -1, 60, -60, 64800, -64800}) {
      ZoneOffset value = ZoneOffset.ofTotalSeconds(seconds);
      for (int capacity = 0; capacity <= 16; capacity++) {
        writer = newUtf8Writer(new byte[capacity]);
        String prefix = "       ".substring(0, capacity & 7);
        writer.writeRawValue(prefix);
        ScalarCodecs.ZoneOffsetCodec.INSTANCE.writeUtf8(writer, value);
        assertEquals(
            new String(writer.toJsonBytes(), StandardCharsets.UTF_8),
            prefix + '"' + value.getId() + '"');
      }
    }
    writer.reset();
    ScalarCodecs.ZoneOffsetCodec.INSTANCE.writeUtf8(writer, null);
    assertEquals(new String(writer.toJsonBytes(), StandardCharsets.UTF_8), "null");
  }

  @Test
  public void writeYearBoundaries() {
    int[] years = {
      Year.MIN_VALUE,
      -10000,
      -1000,
      -1,
      0,
      1,
      9,
      10,
      99,
      100,
      999,
      1000,
      9999,
      10000,
      Year.MAX_VALUE
    };
    for (int year : years) {
      for (int capacity = 0; capacity <= 16; capacity++) {
        Utf8JsonWriter writer = newUtf8Writer(new byte[capacity]);
        String prefix = "       ".substring(0, capacity & 7);
        writer.writeRawValue(prefix);
        ScalarCodecs.YearCodec.INSTANCE.writeUtf8(writer, Year.of(year));
        assertEquals(
            new String(writer.toJsonBytes(), StandardCharsets.UTF_8), prefix + '"' + year + '"');
      }
    }
  }

  @Test
  public void writeDateCalendar() {
    Utf8JsonWriter utf8 = newUtf8Writer(new byte[1]);
    StringJsonWriter string = newStringWriter(new byte[1]);
    LocalDate first = LocalDate.of(1600, 3, 1);
    for (int day = 0; day < 146097; day++) {
      assertDate(utf8, string, first.plusDays(day));
    }
    Random random = new Random(2701);
    for (int year = 0; year <= 9999; year++) {
      LocalDate date = LocalDate.of(year, 1, 1);
      assertDate(utf8, string, date.plusDays(random.nextInt(date.lengthOfYear())));
    }
    for (int capacity = 0; capacity <= 16; capacity++) {
      utf8 = newUtf8Writer(new byte[capacity]);
      String prefix = "       ".substring(0, capacity & 7);
      utf8.writeRawValue(prefix);
      utf8.writeLocalDate(LocalDate.of(9999, 12, 31));
      assertEquals(
          new String(utf8.toJsonBytes(), StandardCharsets.UTF_8), prefix + "\"9999-12-31\"");
    }
  }

  private static void assertDate(Utf8JsonWriter utf8, StringJsonWriter string, LocalDate value) {
    utf8.reset();
    string.reset();
    utf8.writeLocalDate(value);
    string.writeLocalDate(value);
    String expected = '"' + value.toString() + '"';
    assertEquals(new String(utf8.toJsonBytes(), StandardCharsets.UTF_8), expected);
    assertEquals(string.toJson(), expected);
  }

  @Test
  public void writeDurationBoundaries() {
    long[] seconds = {
      Long.MIN_VALUE,
      -2147483648L * 3600,
      -2147483647L * 3600,
      -3661,
      -3600,
      -60,
      -1,
      0,
      1,
      60,
      3600,
      3661,
      2147483647L * 3600,
      2147483648L * 3600,
      Long.MAX_VALUE
    };
    int[] nanos = {0, 1, 1000, 1_000_000, 1_000_010, 123456789, 999999999};
    for (long second : seconds) {
      for (int nano : nanos) {
        Duration value = Duration.ofSeconds(second, nano);
        StringJsonWriter string = newStringWriter(new byte[1]);
        string.writeDuration(value);
        for (int capacity = 0; capacity <= 40; capacity++) {
          Utf8JsonWriter writer = newUtf8Writer(new byte[capacity]);
          String prefix = "       ".substring(0, capacity & 7);
          writer.writeRawValue(prefix);
          writer.writeDuration(value);
          assertEquals(
              new String(writer.toJsonBytes(), StandardCharsets.UTF_8), prefix + string.toJson());
        }
      }
    }
  }

  @Test
  public void writeTemporalFormats() {
    int[] years = {-999999999, -1, 0, 1, 9999, 10000, 999999999};
    int[] nanos = {0, 1, 10, 100, 1000, 1000010, 100000000, 123456789, 999999999};
    ZoneOffset[] offsets = {
      ZoneOffset.UTC,
      ZoneOffset.ofHours(18),
      ZoneOffset.ofTotalSeconds(-64800),
      ZoneOffset.ofHoursMinutesSeconds(-7, -20, -13)
    };
    for (int year : years) {
      LocalDate date = LocalDate.of(year, 2, 28);
      assertWriter(ScalarCodecs.YearMonthCodec.INSTANCE, YearMonth.from(date));
      assertWriter(ScalarCodecs.MonthDayCodec.INSTANCE, MonthDay.from(date));
      for (int nano : nanos) {
        LocalTime time = LocalTime.of(12, 30, nano % 2 == 0 ? 0 : 59, nano);
        LocalDateTime dateTime = LocalDateTime.of(date, time);
        assertWriter(ScalarCodecs.LocalTimeCodec.INSTANCE, time);
        assertWriter(ScalarCodecs.LocalDateTimeCodec.INSTANCE, dateTime);
        for (ZoneOffset offset : offsets) {
          assertWriter(ScalarCodecs.OffsetTimeCodec.INSTANCE, OffsetTime.of(time, offset));
          assertWriter(
              ScalarCodecs.OffsetDateTimeCodec.INSTANCE, OffsetDateTime.of(dateTime, offset));
          assertWriter(ScalarCodecs.ZonedDateTimeCodec.INSTANCE, dateTime.atZone(offset));
        }
        assertWriter(
            ScalarCodecs.ZonedDateTimeCodec.INSTANCE, dateTime.atZone(ZoneId.of("Europe/Paris")));
        assertWriter(ScalarCodecs.ZonedDateTimeCodec.INSTANCE, dateTime.atZone(ZoneId.of("UTC")));
      }
    }
  }

  private static <T> void assertWriter(JsonValueCodec<T> codec, T value) {
    StringJsonWriter string = newStringWriter(new byte[1]);
    codec.writeString(string, value);
    Utf8JsonWriter utf8 = newUtf8Writer(new byte[1]);
    codec.writeUtf8(utf8, value);
    assertEquals(new String(utf8.toJsonBytes(), StandardCharsets.UTF_8), string.toJson());
  }

  @Test(dataProvider = "enableCodegen")
  public void readTemporalFields(boolean codegen) {
    ForyJson json = newJson(codegen);
    TemporalFields value = new TemporalFields();
    value.label = "\u0100";
    value.instants = new Instant[] {Instant.EPOCH, Instant.parse("2024-02-29T23:59:59.123456789Z")};
    value.time = LocalTime.of(12, 30, 45, 100_000_000);
    value.dateTime = LocalDateTime.of(2024, 2, 29, 12, 30, 45);
    value.duration = Duration.ofSeconds(Long.MAX_VALUE, 1);
    value.period = Period.of(Integer.MIN_VALUE, -20, 12);
    String text = json.toJson(value);
    assertFields(json.fromJson(text, TemporalFields.class), value);
    assertFields(json.fromJson(text.getBytes(StandardCharsets.UTF_8), TemporalFields.class), value);
  }

  private static void assertFields(TemporalFields actual, TemporalFields expected) {
    assertEquals(actual.label, expected.label);
    assertEquals(actual.instants, expected.instants);
    assertEquals(actual.time, expected.time);
    assertEquals(actual.dateTime, expected.dateTime);
    assertEquals(actual.duration, expected.duration);
    assertEquals(actual.period, expected.period);
  }

  private static <T> void assertToken(JsonValueCodec<T> codec, String text, T expected) {
    String token = "\"" + text + "\"";
    byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
    Utf8JsonReader utf8 = newUtf8Reader(bytes);
    Latin1JsonReader latin1 = newLatin1Reader(bytes);
    Utf16JsonReader utf16 = newUtf16Reader(token);
    assertEquals(codec.readUtf8(utf8), expected, text);
    assertEquals(codec.readLatin1(latin1), expected, text);
    assertEquals(codec.readUtf16(utf16), expected, text);
    utf8.finish();
    latin1.finish();
    utf16.finish();
  }

  private static <T> void rejectToken(JsonValueCodec<T> codec, String text) {
    String token = "\"" + text + "\"";
    byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
    assertThrows(RuntimeException.class, () -> codec.readUtf8(newUtf8Reader(bytes)));
    assertThrows(RuntimeException.class, () -> codec.readLatin1(newLatin1Reader(bytes)));
    assertThrows(RuntimeException.class, () -> codec.readUtf16(newUtf16Reader(token)));
  }

  public static class TemporalFields {
    public String label;
    public Instant[] instants;
    public LocalTime time;
    public LocalDateTime dateTime;
    public Duration duration;
    public Period period;
  }
}

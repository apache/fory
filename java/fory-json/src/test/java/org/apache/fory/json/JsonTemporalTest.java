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
import java.time.format.DateTimeFormatter;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.time.zone.ZoneRulesProvider;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.NavigableMap;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
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
  public void readZoneIds() {
    ScalarCodecs.ZoneIdCodec codec = ScalarCodecs.ZoneIdCodec.INSTANCE;
    for (String id : ZoneId.getAvailableZoneIds()) {
      assertToken(codec, id, ZoneId.of(id));
      ZoneId decoded =
          codec.readUtf8(newUtf8Reader(('"' + id + '"').getBytes(StandardCharsets.US_ASCII)));
      assertEquals(decoded.getId().hashCode(), new String(id.toCharArray()).hashCode());
    }
    for (String id :
        new String[] {
          "UT",
          "UTC",
          "GMT",
          "Z",
          "+18:00",
          "-18:00",
          "+07:13:29",
          "UT+07:13:29",
          "UTC-07:13:29",
          "GMT+07:13:29",
          "Europe/Paris"
        }) {
      ZoneId expected = ZoneId.of(id);
      assertToken(codec, id, expected);
      for (int i = 0; i < id.length(); i++) {
        String escaped =
            id.substring(0, i)
                + String.format(Locale.ROOT, "\\u%04x", (int) id.charAt(i))
                + id.substring(i + 1);
        assertToken(codec, escaped, expected);
      }
    }
    for (boolean codegen : new boolean[] {false, true}) {
      ForyJson json = ForyJson.builder().withCodegen(codegen).build();
      assertEquals(json.fromJson("  null", ZoneId.class), null);
      for (String id :
          new String[] {"", "A", "0Paris", "NoSuch/Zone", "Asia/\u4e0a\u6d77", "Europe:Paris"}) {
        String token = json.toJson(id);
        assertThrows(RuntimeException.class, () -> json.fromJson(token, ZoneId.class));
        assertThrows(
            RuntimeException.class,
            () -> json.fromJson(token.getBytes(StandardCharsets.UTF_8), ZoneId.class));
        assertEquals(json.fromJson("\"Europe/Paris\"", ZoneId.class), ZoneId.of("Europe/Paris"));
      }
    }
  }

  @Test
  public void readPrefixedZones() {
    ScalarCodecs.ZoneIdCodec codec = ScalarCodecs.ZoneIdCodec.INSTANCE;
    for (String prefix : new String[] {"UT", "UTC", "GMT"}) {
      for (int minutes = -1080; minutes <= 1080; minutes++) {
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(minutes * 60);
        String id = prefix + (minutes == 0 ? "+00:00" : offset.getId());
        ZoneId expected = ZoneId.of(id);
        assertToken(codec, id, expected);
        ZoneId actual =
            codec.readUtf8(newUtf8Reader(('"' + id + '"').getBytes(StandardCharsets.US_ASCII)));
        assertEquals(actual.getId(), expected.getId());
        assertEquals(actual.getRules(), expected.getRules());
      }
      for (String suffix :
          new String[] {"", "0", "+1", "-01", "+0130", "-01:30:29", "+00:00", "-00:00"}) {
        String id = prefix + suffix;
        ZoneId expected;
        try {
          expected = ZoneId.of(id);
        } catch (java.time.DateTimeException e) {
          rejectToken(codec, id);
          continue;
        }
        assertToken(codec, id, expected);
      }
      for (String suffix : new String[] {"+18:01", "-18:01", "+19:00", "+01:60", "+0a:00"}) {
        rejectToken(codec, prefix + suffix);
      }
    }
  }

  @Test
  public void readZoneProviderRules() {
    ZoneNameProvider provider = new ZoneNameProvider();
    ZoneRulesProvider.registerProvider(provider);
    ScalarCodecs.ZoneIdCodec codec = ScalarCodecs.ZoneIdCodec.INSTANCE;
    for (String id : provider.ids) {
      assertToken(codec, id, ZoneId.of(id));
    }
    String id = "ForyJson/Rules";
    ForyJson json = ForyJson.builder().build();
    String token = json.toJson(id);
    int queries = provider.queries;
    ZoneId first = json.fromJson(token, ZoneId.class);
    assertEquals(provider.queries, queries + 1);
    assertEquals(first.getRules(), provider.rules);
    ZoneRules previous = provider.rules;
    provider.rules = ZoneRules.of(ZoneOffset.ofHours(2));
    ZoneId second = json.fromJson(token.getBytes(StandardCharsets.UTF_8), ZoneId.class);
    assertEquals(provider.queries, queries + 2);
    assertEquals(first.getRules(), previous);
    assertEquals(second.getRules(), provider.rules);
    ZoneId lazy = json.fromJson("\"ForyJson/Lazy\"", ZoneId.class);
    assertEquals(provider.queries, queries + 3);
    assertEquals(lazy.getRules(), provider.rules);
    provider.rules = previous;
    assertEquals(lazy.getRules(), previous);
  }

  @Test
  public void readMonthDayComponents() {
    Utf8JsonReader reader = newUtf8Reader(new byte[0]);
    for (int month = 0; month <= 13; month++) {
      for (int day = 0; day <= 32; day++) {
        String token = String.format(Locale.ROOT, "\"--%02d-%02d\" 17", month, day);
        reader.reset(token.getBytes(StandardCharsets.US_ASCII));
        MonthDay expected;
        try {
          expected = MonthDay.of(month, day);
        } catch (java.time.DateTimeException e) {
          assertThrows(RuntimeException.class, reader::readMonthDay);
          continue;
        }
        assertEquals(reader.readMonthDay(), expected);
        assertEquals(reader.readInt(), 17);
        reader.finish();
      }
    }
  }

  @Test
  public void readMonthDayWords() {
    Utf8JsonReader reader = newUtf8Reader(new byte[0]);
    byte[] token = "\"--12-31\",17".getBytes(StandardCharsets.US_ASCII);
    for (int offset = 0; offset < 8; offset++) {
      byte[] bytes = new byte[offset + token.length + 8];
      System.arraycopy(token, 0, bytes, offset, token.length);
      reader.reset(bytes, offset, token.length);
      assertEquals(reader.readMonthDay(), MonthDay.of(12, 31));
      reader.expectNextToken(',');
      assertEquals(reader.readInt(), 17);
      reader.finish();
      for (int length = 0; length < 9; length++) {
        reader.reset(bytes, offset, length);
        assertThrows(RuntimeException.class, reader::readMonthDay);
      }
      for (int lane : new int[] {3, 4, 6, 7}) {
        byte saved = bytes[offset + lane];
        for (int value = 0; value < 256; value++) {
          if (value >= '0' && value <= '9') {
            continue;
          }
          bytes[offset + lane] = (byte) value;
          reader.reset(bytes, offset, token.length);
          assertThrows(RuntimeException.class, reader::readMonthDay);
        }
        bytes[offset + lane] = saved;
      }
    }
  }

  @Test
  public void writeMonthDayWords() {
    for (int capacity = 0; capacity <= 16; capacity++) {
      Utf8JsonWriter writer = newUtf8Writer(new byte[capacity]);
      LocalDate first = LocalDate.of(2000, 1, 1);
      for (int day = 0; day < 366; day++) {
        MonthDay value = MonthDay.from(first.plusDays(day));
        writer.reset();
        String prefix = "       ".substring(0, day & 7);
        writer.writeRawValue(prefix);
        writer.writeMonthDay(value);
        writer.writeComma(1);
        writer.writeInt(17);
        assertEquals(
            new String(writer.toJsonBytes(), StandardCharsets.US_ASCII),
            prefix + '"' + value.toString() + "\",17");
      }
    }
  }

  @Test
  public void readYearMonthComponents() {
    Utf8JsonReader reader = newUtf8Reader(new byte[0]);
    for (int year = 0; year <= 9999; year++) {
      YearMonth expected = YearMonth.of(year, 1 + year % 12);
      reader.reset(('"' + expected.toString() + "\" 17").getBytes(StandardCharsets.US_ASCII));
      assertEquals(reader.readYearMonth(), expected);
      assertEquals(reader.readInt(), 17);
      reader.finish();
    }
    for (int year : new int[] {-999999999, -1, 0, 1, 9999, 10000, 999999999}) {
      for (int month = 1; month <= 12; month++) {
        YearMonth expected = YearMonth.of(year, month);
        // YearMonth.parse requires a plus on extended positive years, but toString omits it.
        String text = (year > 9999 ? "+" : "") + expected;
        assertToken(ScalarCodecs.YearMonthCodec.INSTANCE, text, expected);
      }
    }
    ForyJson json = ForyJson.builder().build();
    for (String text : new String[] {"0000-00", "2024-13", "9999-99", "+1000000000-01"}) {
      byte[] token = ('"' + text + '"').getBytes(StandardCharsets.US_ASCII);
      assertThrows(RuntimeException.class, () -> json.fromJson(token, YearMonth.class));
      assertEquals(
          json.fromJson("\"2000-02\"".getBytes(StandardCharsets.US_ASCII), YearMonth.class),
          YearMonth.of(2000, 2));
    }
  }

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
  public void readNullableOffsetTime() {
    assertNullableTemporal(
        ScalarCodecs.OffsetTimeCodec.INSTANCE, OffsetTime.of(1, 2, 3, 4, ZoneOffset.ofHours(5)));
  }

  @Test
  public void readNullableInstant() {
    assertNullableTemporal(ScalarCodecs.InstantCodec.INSTANCE, Instant.ofEpochSecond(-123456, 789));
  }

  @Test
  public void readNullableZoneId() {
    for (String id : new String[] {"Z", "+03:00", "UTC-05:30", "Europe/Paris"}) {
      assertNullableTemporal(ScalarCodecs.ZoneIdCodec.INSTANCE, ZoneId.of(id));
    }
  }

  private static <T> void assertNullableTemporal(JsonValueCodec<T> codec, T value) {
    for (String prefix : new String[] {"", " ", "\t\r\n"}) {
      for (boolean isNull : new boolean[] {true, false}) {
        String token = prefix + (isNull ? "null" : '"' + value.toString() + '"') + ",17";
        byte[] bytes = token.getBytes(StandardCharsets.US_ASCII);
        Utf8JsonReader utf8 = newUtf8Reader(bytes);
        Latin1JsonReader latin1 = newLatin1Reader(bytes);
        Utf16JsonReader utf16 = newUtf16Reader(token);
        T expected = isNull ? null : value;
        assertEquals(codec.readUtf8(utf8), expected);
        assertEquals(codec.readLatin1(latin1), expected);
        assertEquals(codec.readUtf16(utf16), expected);
        utf8.expectNextToken(',');
        latin1.expectNextToken(',');
        utf16.expectNextToken(',');
        assertEquals(utf8.readInt(), 17);
        assertEquals(latin1.readInt(), 17);
        assertEquals(utf16.readInt(), 17);
        utf8.finish();
        latin1.finish();
        utf16.finish();
      }
      for (String text : new String[] {"", "n", "nu", "nul", "nulp"}) {
        String token = prefix + text;
        byte[] bytes = token.getBytes(StandardCharsets.US_ASCII);
        assertThrows(RuntimeException.class, () -> codec.readUtf8(newUtf8Reader(bytes)));
        assertThrows(RuntimeException.class, () -> codec.readLatin1(newLatin1Reader(bytes)));
        assertThrows(RuntimeException.class, () -> codec.readUtf16(newUtf16Reader(token)));
      }
    }
  }

  @Test
  public void readOffsetDigitLanes() {
    Utf8JsonReader reader = newUtf8Reader(new byte[0]);
    Latin1JsonReader latin1 = newLatin1Reader(new byte[0]);
    byte[] token = "\"+07:20:13\"".getBytes(StandardCharsets.US_ASCII);
    for (int index : new int[] {2, 3, 5, 6}) {
      byte saved = token[index];
      for (int digit = 0; digit < 256; digit++) {
        token[index] = (byte) digit;
        reader.reset(token);
        // Preserve the unchanged text parser's grammar, including noncanonical offsets.
        ZoneOffset expected = null;
        try {
          latin1.reset(token);
          expected = latin1.readZoneOffset();
          latin1.finish();
        } catch (RuntimeException e) {
          expected = null;
        }
        if (expected == null) {
          assertThrows(
              RuntimeException.class,
              () -> {
                reader.readZoneOffset();
                reader.finish();
              });
        } else {
          assertEquals(reader.readZoneOffset(), expected);
          reader.finish();
        }
      }
      token[index] = saved;
    }
  }

  @Test
  public void readTimeDigitPairs() {
    Utf8JsonReader reader = newUtf8Reader(new byte[0]);
    // Keep seconds absent so this test exercises parse2.
    byte[] token = "\"01:02\"".getBytes(StandardCharsets.UTF_8);
    for (int first = 0; first < 256; first++) {
      for (int second : new int[] {'0', '9', 0, 47, 58, 127, 128, 255}) {
        token[4] = (byte) first;
        token[5] = (byte) second;
        reader.reset(token);
        if (first >= '0' && first <= '5' && second >= '0' && second <= '9') {
          assertEquals(
              reader.readIsoLocalTime(), LocalTime.of(1, (first - '0') * 10 + second - '0'));
          reader.finish();
        } else {
          assertThrows(RuntimeException.class, () -> reader.readIsoLocalTime());
        }
      }
    }
    for (int minutes = 0; minutes < 60; minutes++) {
      LocalTime time = LocalTime.of(1, minutes);
      assertToken(ScalarCodecs.LocalTimeCodec.INSTANCE, time.toString(), time);
    }
  }

  @Test
  public void readFractionPrefixes() {
    Utf8JsonReader reader = newUtf8Reader(new byte[0]);
    Random random = new Random(4615);
    for (int length = 0; length <= 9; length++) {
      for (int sample = 0; sample < 64; sample++) {
        StringBuilder fraction = new StringBuilder();
        for (int i = 0; i < length; i++) {
          fraction.append((char) ('0' + random.nextInt(10)));
        }
        String timeText = "01:02:03." + fraction;
        LocalTime expected = LocalTime.parse(timeText);
        String token = '"' + timeText + '"';
        // Trailing tokens provide eight readable bytes even for short fractional prefixes.
        byte[] bytes = (token + ",123456789").getBytes(StandardCharsets.UTF_8);
        reader.reset(bytes);
        assertEquals(reader.readIsoLocalTime(), expected);
        reader.expectNextToken(',');
        assertEquals(reader.readInt(), 123456789);
        reader.finish();
        for (int offset = 0; offset < 4; offset++) {
          byte[] slice = new byte[offset + bytes.length];
          System.arraycopy(bytes, 0, slice, offset, bytes.length);
          reader.reset(slice, offset, token.length());
          assertEquals(reader.readIsoLocalTime(), expected);
          reader.finish();
          reader.reset(slice, offset, token.length() - 1);
          assertThrows(RuntimeException.class, () -> reader.readIsoLocalTime());
        }
        assertToken(ScalarCodecs.LocalTimeCodec.INSTANCE, timeText, expected);
        assertToken(
            ScalarCodecs.OffsetTimeCodec.INSTANCE,
            timeText + "+01:30",
            OffsetTime.of(expected, ZoneOffset.ofHoursMinutes(1, 30)));
      }
    }
    for (int position = 0; position < 9; position++) {
      for (int value : new int[] {0, 31, 47, 58, 127, 128, 192, 255}) {
        byte[] bytes = "\"01:02:03.123456789\",123456789".getBytes(StandardCharsets.UTF_8);
        bytes[10 + position] = (byte) value;
        reader.reset(bytes);
        assertThrows(RuntimeException.class, () -> reader.readIsoLocalTime());
      }
    }
    assertEscapes(ScalarCodecs.LocalTimeCodec.INSTANCE, LocalTime.of(1, 2, 3, 123456789));
    rejectToken(ScalarCodecs.LocalTimeCodec.INSTANCE, "01:02:03.1234567890");
  }

  @Test
  public void readDateCalendar() {
    Utf8JsonReader reader = newUtf8Reader(new byte[0]);
    LocalDate first = LocalDate.of(0, 3, 1);
    for (int day = 0; day < 146097; day++) {
      LocalDate expected = first.plusDays(day);
      reader.reset(('"' + expected.toString() + '"').getBytes(StandardCharsets.US_ASCII));
      assertEquals(reader.readIsoLocalDate(), expected);
      reader.finish();
      reader.reset(
          ('"' + expected.toString() + "T23:59:59.999999999\",17")
              .getBytes(StandardCharsets.US_ASCII));
      assertEquals(reader.readIsoLocalDateTime(), expected.atTime(LocalTime.MAX));
      reader.expectNextToken(',');
      assertEquals(reader.readInt(), 17);
      reader.finish();
    }
    for (String text : new String[] {"0000-01-01", "1900-02-28", "2000-02-29", "9999-12-31"}) {
      assertToken(ScalarCodecs.LocalDateCodec.INSTANCE, text, LocalDate.parse(text));
    }
    ForyJson json = ForyJson.builder().build();
    for (String text :
        new String[] {
          "2024-00-01",
          "2024-13-01",
          "2024-01-00",
          "2024-01-32",
          "2024-04-31",
          "1900-02-29",
          "2024-02-30"
        }) {
      byte[] date = ('"' + text + '"').getBytes(StandardCharsets.US_ASCII);
      byte[] dateTime = ('"' + text + "T23:59:59\"").getBytes(StandardCharsets.US_ASCII);
      assertThrows(RuntimeException.class, () -> json.fromJson(date, LocalDate.class));
      assertThrows(RuntimeException.class, () -> json.fromJson(dateTime, LocalDateTime.class));
      assertEquals(
          json.fromJson("\"2000-02-29\"".getBytes(StandardCharsets.US_ASCII), LocalDate.class),
          LocalDate.of(2000, 2, 29));
    }
  }

  @Test
  public void readTimeComponents() {
    Utf8JsonReader reader = newUtf8Reader(new byte[0]);
    int[] seconds = {0, 1, 30, 59};
    int[] nanos = {0, 1, 999999999};
    for (int hour = 0; hour < 24; hour++) {
      for (int minute = 0; minute < 60; minute++) {
        for (int second : seconds) {
          for (int nano : nanos) {
            LocalTime expected = LocalTime.of(hour, minute, second, nano);
            byte[] bytes =
                ('"' + expected.toString() + "\",17").getBytes(StandardCharsets.US_ASCII);
            reader.reset(bytes);
            assertEquals(reader.readIsoLocalTime(), expected);
            reader.expectNextToken(',');
            assertEquals(reader.readInt(), 17);
            reader.finish();
          }
        }
      }
    }
    for (String text :
        new String[] {"24:00:00", "00:60:00", "00:00:60", "99:99:99", "23:59:59.9999999999"}) {
      reader.reset(('"' + text + '"').getBytes(StandardCharsets.US_ASCII));
      assertThrows(RuntimeException.class, reader::readIsoLocalTime);
      reader.reset("\"23:59:59.999999999\"".getBytes(StandardCharsets.US_ASCII));
      assertEquals(reader.readIsoLocalTime(), LocalTime.MAX);
      reader.finish();
    }
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
  public void readDurationSlices() {
    long[] seconds = {Long.MIN_VALUE, -3601, -60, -1, 0, 1, 60, 3601, Long.MAX_VALUE};
    int[] nanos = {0, 1, 100_000_000, 123_456_789, 999_999_999};
    Utf8JsonReader reader = newUtf8Reader(new byte[0]);
    for (long second : seconds) {
      for (int nano : nanos) {
        Duration expected = Duration.ofSeconds(second, nano);
        String text = expected.toString();
        assertToken(ScalarCodecs.DurationCodec.INSTANCE, text, expected);
        byte[] token = ('"' + text + '"').getBytes(StandardCharsets.US_ASCII);
        for (int offset = 0; offset < 8; offset++) {
          byte[] input = new byte[offset + token.length + 8];
          System.arraycopy(token, 0, input, offset, token.length);
          for (int length = 0; length < token.length; length++) {
            reader.reset(input, offset, length);
            assertThrows(ForyJsonException.class, reader::readDuration);
          }
          reader.reset(input, offset, token.length);
          assertEquals(reader.readDuration(), expected);
          reader.finish();
        }
        byte[] adjacent = ('"' + text + "\" 17").getBytes(StandardCharsets.US_ASCII);
        reader.reset(adjacent);
        assertEquals(reader.readDuration(), expected);
        assertEquals(reader.readInt(), 17);
      }
    }
    for (String text :
        new String[] {"PT1H2M3.000000001S", "PT1H-2M-0.1S", "P2D", "-PT1H", "pt1h"}) {
      Duration expected =
          text.equals("PT1H-2M-0.1S")
              ? Duration.ofSeconds(3479, 900_000_000)
              : Duration.parse(text);
      assertToken(ScalarCodecs.DurationCodec.INSTANCE, text, expected);
    }
    assertToken(
        ScalarCodecs.DurationCodec.INSTANCE,
        "\\u0050T1\\u00482M3.1S",
        Duration.ofSeconds(3723, 100_000_000));
    ForyJson json = ForyJson.builder().build();
    for (String text :
        new String[] {
          "PT1S1H",
          "PT9223372036854775808S",
          "PT-9223372036854775808.1S",
          "PT1.1234567890S",
          "PT1.2H",
          "PT1H\\x"
        }) {
      byte[] token = ('"' + text + '"').getBytes(StandardCharsets.UTF_8);
      assertThrows(ForyJsonException.class, () -> json.fromJson(token, Duration.class));
      assertEquals(
          json.fromJson("\"PT1S\"".getBytes(StandardCharsets.UTF_8), Duration.class),
          Duration.ofSeconds(1));
    }
  }

  @Test
  public void readPeriodSlices() {
    int[] amounts = {Integer.MIN_VALUE, -1000000000, -1, 0, 1, 1000000000, Integer.MAX_VALUE};
    Utf8JsonReader reader = newUtf8Reader(new byte[0]);
    for (int years : amounts) {
      for (int months : amounts) {
        for (int days : amounts) {
          Period expected = Period.of(years, months, days);
          String text = expected.toString();
          assertToken(ScalarCodecs.PeriodCodec.INSTANCE, text, expected);
          byte[] token = ('"' + text + '"').getBytes(StandardCharsets.US_ASCII);
          for (int offset = 0; offset < 8; offset++) {
            byte[] input = new byte[offset + token.length + 8];
            System.arraycopy(token, 0, input, offset, token.length);
            for (int length = 0; length < token.length; length++) {
              reader.reset(input, offset, length);
              assertThrows(ForyJsonException.class, reader::readPeriod);
            }
            reader.reset(input, offset, token.length);
            assertEquals(reader.readPeriod(), expected);
            reader.finish();
          }
          reader.reset(('"' + text + "\" 17").getBytes(StandardCharsets.US_ASCII));
          assertEquals(reader.readPeriod(), expected);
          assertEquals(reader.readInt(), 17);
        }
      }
    }
    for (String text :
        new String[] {
          "P+1Y+2M+3D", "P01Y002M0003D", "P-00Y-01M-002D", "P2W", "-P1Y2M", "p1y", "P0Y0M0D"
        }) {
      assertToken(ScalarCodecs.PeriodCodec.INSTANCE, text, Period.parse(text));
    }
    assertToken(ScalarCodecs.PeriodCodec.INSTANCE, "\\u00501\\u00592M3D", Period.of(1, 2, 3));
    ForyJson json = ForyJson.builder().build();
    for (String text :
        new String[] {
          "P", "P1D1Y", "P1Y1Y", "P2147483648D", "P-2147483649M", "P1.0Y", "P1e0D", "P1Y\\x"
        }) {
      byte[] token = ('"' + text + '"').getBytes(StandardCharsets.UTF_8);
      assertThrows(ForyJsonException.class, () -> json.fromJson(token, Period.class));
      assertEquals(
          json.fromJson("\"P1Y2M3D\"".getBytes(StandardCharsets.UTF_8), Period.class),
          Period.of(1, 2, 3));
    }
  }

  @Test
  public void readInstantCalendar() {
    Utf8JsonReader reader = newUtf8Reader(new byte[0]);
    for (int second = 0; second < 86400; second++) {
      Instant expected = Instant.ofEpochSecond(second, second * 1001);
      reader.reset(('"' + expected.toString() + "\",17").getBytes(StandardCharsets.US_ASCII));
      assertEquals(reader.readIsoInstant(), expected);
      reader.expectNextToken(',');
      assertEquals(reader.readInt(), 17);
      reader.finish();
    }
    int[] nanos = {0, 123000000, 123456000, 123456789};
    int[] extraYears = {400, 1600, 1900, 1970, 2000, 2100, 2400, 9999};
    for (int index = 0; index < 400 + extraYears.length; index++) {
      int year = index < 400 ? index : extraYears[index - 400];
      for (int month = 1; month <= 12; month++) {
        LocalDate first = LocalDate.of(year, month, 1);
        for (int day = 1; day <= first.lengthOfMonth(); day++) {
          Instant expected =
              LocalDateTime.of(
                      year,
                      month,
                      day,
                      (year + month) % 24,
                      (year + day) % 60,
                      (month + day) % 60,
                      nanos[(year + month + day) & 3])
                  .toInstant(ZoneOffset.UTC);
          reader.reset(('"' + expected.toString() + '"').getBytes(StandardCharsets.US_ASCII));
          assertEquals(reader.readIsoInstant(), expected);
          reader.finish();
        }
      }
    }
    for (String value :
        new String[] {
          "0000-02-29T23:59:59.000000001Z", "1970-01-01T00:00:00Z", "9999-12-31T23:59:59.999999999Z"
        }) {
      byte[] token = ('"' + value + '"').getBytes(StandardCharsets.US_ASCII);
      for (int offset = 0; offset < 8; offset++) {
        byte[] bytes = new byte[offset + token.length + 8];
        System.arraycopy(token, 0, bytes, offset, token.length);
        for (int length = 0; length < token.length; length++) {
          reader.reset(bytes, offset, length);
          assertThrows(ForyJsonException.class, reader::readIsoInstant);
        }
        reader.reset(bytes, offset, token.length);
        assertEquals(reader.readIsoInstant(), Instant.parse(value));
        reader.finish();
      }
    }
    ForyJson json = newJson();
    for (String value :
        new String[] {
          "1900-02-29T00:00:00Z",
          "2000-02-30T00:00:00Z",
          "2000-04-31T00:00:00Z",
          "2000-00-01T00:00:00Z",
          "2000-13-01T00:00:00Z",
          "2000-01-00T00:00:00Z",
          "2000-01-32T00:00:00Z",
          "2000-01-01T25:00:00Z",
          "2000-01-01T00:60:00Z",
          "2000-01-01T00:00:61Z",
          "2000-01-01T00:00:00.1234567890Z"
        }) {
      byte[] bytes = ('"' + value + '"').getBytes(StandardCharsets.US_ASCII);
      assertThrows(ForyJsonException.class, () -> json.fromJson(bytes, Instant.class));
      assertEquals(
          json.fromJson(
              "\"1970-01-01T00:00:00Z\"".getBytes(StandardCharsets.US_ASCII), Instant.class),
          Instant.EPOCH);
    }
  }

  @Test
  public void readZonedCalendar() {
    ZoneId[] zones = {ZoneId.of("Europe/Paris"), ZoneId.of("America/New_York"), ZoneId.of("UTC")};
    for (int year : new int[] {0, 1, 399, 400, 1970, 2000, 2024, 9999}) {
      for (int month = 1; month <= 12; month++) {
        LocalDateTime dateTime = LocalDateTime.of(year, month, 1, 0, 0, 0, 123456789);
        for (ZoneId zone : zones) {
          for (ZoneOffset offset :
              new ZoneOffset[] {ZoneOffset.UTC, ZoneOffset.MIN, ZoneOffset.MAX}) {
            String text = dateTime.toString() + offset + '[' + zone.getId() + ']';
            assertToken(
                ScalarCodecs.ZonedDateTimeCodec.INSTANCE,
                text,
                ZonedDateTime.ofInstant(dateTime, offset, zone));
          }
        }
      }
    }
  }

  @Test
  public void readZonedRegionBounds() {
    String dateTime = "2024-01-01T12:34:56+01:00[";
    ZonedDateTime expected = ZonedDateTime.parse(dateTime + "Europe/Paris]");
    for (String id : new String[] {"Europe/Paris", "Europe\\/Paris", "Europe/Par\\u0069s"}) {
      byte[] token = ('"' + dateTime + id + "]\"").getBytes(StandardCharsets.US_ASCII);
      for (int offset = 0; offset < 8; offset++) {
        byte[] bytes = new byte[offset + token.length + 3];
        System.arraycopy(token, 0, bytes, offset, token.length);
        bytes[offset + token.length] = ',';
        bytes[offset + token.length + 1] = '1';
        bytes[offset + token.length + 2] = '7';
        Utf8JsonReader reader = newUtf8Reader(bytes);
        reader.reset(bytes, offset, token.length + 3);
        assertEquals(reader.readZonedDateTime(), expected);
        reader.expect(',');
        assertEquals(reader.readInt(), 17);
        reader.reset(bytes, offset, token.length - 2);
        assertThrows(RuntimeException.class, reader::readZonedDateTime);
      }
    }
    ForyJson json = ForyJson.builder().build();
    for (String id :
        new String[] {
          "Europe/Par\"is", "Europe/Par\nis", "Europe/Par\u0000is", "Europe/Paris\",17"
        }) {
      byte[] token = ('"' + dateTime + id + "]\"").getBytes(StandardCharsets.US_ASCII);
      assertThrows(RuntimeException.class, () -> json.fromJson(token, ZonedDateTime.class));
      assertEquals(
          json.fromJson('"' + dateTime + "Europe/Paris]\"", ZonedDateTime.class), expected);
    }
  }

  @Test
  public void readZonedTransitions() {
    for (String id :
        new String[] {
          "Europe/Paris",
          "America/New_York",
          "Australia/Lord_Howe",
          "Pacific/Apia",
          "Asia/Kathmandu"
        }) {
      ZoneId zone = ZoneId.of(id);
      for (int year : new int[] {1890, 1910, 1940, 1970, 1990, 2011, 2024, 2100}) {
        Instant probe = LocalDate.of(year, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
        ZoneOffsetTransition transition = zone.getRules().nextTransition(probe);
        if (transition == null) {
          continue;
        }
        for (LocalDateTime boundary :
            new LocalDateTime[] {transition.getDateTimeBefore(), transition.getDateTimeAfter()}) {
          for (int seconds : new int[] {-1, 0, 1, 1800}) {
            LocalDateTime dateTime = boundary.plusSeconds(seconds).withNano(123456789);
            for (ZoneOffset offset :
                new ZoneOffset[] {
                  transition.getOffsetBefore(),
                  transition.getOffsetAfter(),
                  ZoneOffset.UTC,
                  ZoneOffset.MAX
                }) {
              ZonedDateTime expected = ZonedDateTime.ofInstant(dateTime, offset, zone);
              String text = dateTime.toString() + offset + "[" + id + "]";
              assertToken(ScalarCodecs.ZonedDateTimeCodec.INSTANCE, text, expected);
              Utf8JsonReader reader =
                  newUtf8Reader(("\"" + text + "\",17").getBytes(StandardCharsets.UTF_8));
              assertEquals(reader.readZonedDateTime(), expected);
              reader.expect(',');
              assertEquals(reader.readInt(), 17);
            }
          }
        }
      }
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
  public void writeInstantFractions() {
    Utf8JsonWriter writer = newUtf8Writer(new byte[0]);
    for (int digits = 0; digits < 1000; digits++) {
      int[] nanos = {digits * 1_000_000, digits * 1000, digits * 1_001_000};
      for (int nano : nanos) {
        Instant value = Instant.ofEpochSecond(digits * 61L, nano);
        writer.reset();
        writer.writeIsoInstant(value.getEpochSecond(), value.getNano());
        assertEquals(
            new String(writer.toJsonBytes(), StandardCharsets.UTF_8), '"' + value.toString() + '"');
      }
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
  public void writeInstantCalendar() {
    Utf8JsonWriter utf8 = newUtf8Writer(new byte[1]);
    StringJsonWriter string = newStringWriter(new byte[1]);
    long[] starts = {
      Instant.MIN.getEpochSecond(),
      LocalDate.of(-400, 3, 1).atStartOfDay().toEpochSecond(ZoneOffset.UTC),
      LocalDate.of(1600, 3, 1).atStartOfDay().toEpochSecond(ZoneOffset.UTC),
      Instant.MAX.getEpochSecond() - 146096L * 86400
    };
    for (long start : starts) {
      for (int day = 0; day < 146097; day++) {
        assertInstant(utf8, string, Instant.ofEpochSecond(start + day * 86400L, day));
      }
    }
    Random random = new Random(481907L);
    long minimum = Instant.MIN.getEpochSecond();
    long range = Instant.MAX.getEpochSecond() - minimum + 1;
    for (int i = 0; i < 10000; i++) {
      long second = minimum + Math.floorMod(random.nextLong(), range);
      assertInstant(utf8, string, Instant.ofEpochSecond(second, random.nextInt(1_000_000_000)));
    }
  }

  private static void assertInstant(Utf8JsonWriter utf8, StringJsonWriter string, Instant value) {
    utf8.reset();
    string.reset();
    utf8.writeIsoInstant(value.getEpochSecond(), value.getNano());
    string.writeIsoInstant(value.getEpochSecond(), value.getNano());
    String expected = '"' + value.toString() + '"';
    assertEquals(new String(utf8.toJsonBytes(), StandardCharsets.UTF_8), expected);
    assertEquals(string.toJson(), expected);
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
  public void writeZonedRegions() {
    Set<ZoneId> zones = new HashSet<>();
    for (String id : ZoneId.getAvailableZoneIds()) {
      zones.add(ZoneId.of(id));
    }
    zones.add(ZoneOffset.UTC);
    zones.add(ZoneOffset.ofHoursMinutesSeconds(-7, -13, -29));
    for (ZoneId zone : zones) {
      for (int nanos : new int[] {0, 1, 123456789}) {
        ZonedDateTime value = LocalDateTime.of(2024, 2, 29, 12, 13, 14, nanos).atZone(zone);
        StringJsonWriter string = newStringWriter(new byte[1]);
        string.writeZonedDateTime(value);
        String expected = string.toJson();
        for (int capacity :
            new int[] {0, 1, expected.length() - 1, expected.length(), expected.length() + 1}) {
          Utf8JsonWriter writer = newUtf8Writer(new byte[capacity]);
          writer.writeArrayStart();
          writer.writeZonedDateTime(value);
          writer.writeComma(1);
          writer.writeInt(17);
          writer.writeArrayEnd();
          assertEquals(
              new String(writer.toJsonBytes(), StandardCharsets.UTF_8), "[" + expected + ",17]");
          writer.reset();
          writer.writeZonedDateTime(value);
          assertEquals(new String(writer.toJsonBytes(), StandardCharsets.UTF_8), expected);
        }
      }
    }
  }

  @Test
  public void writeOffsetLayouts() {
    LocalTime time = LocalTime.of(12, 34, 56);
    Utf8JsonWriter writer = newUtf8Writer(new byte[0]);
    for (int seconds = -64800; seconds <= 64800; seconds++) {
      OffsetTime value = OffsetTime.of(time, ZoneOffset.ofTotalSeconds(seconds));
      writer.reset();
      writer.writeOffsetTime(value);
      writer.writeComma(1);
      writer.writeInt(17);
      String expected = '"' + DateTimeFormatter.ISO_OFFSET_TIME.format(value) + "\",17";
      assertEquals(new String(writer.toJsonBytes(), StandardCharsets.UTF_8), expected);
    }
  }

  @Test
  public void writeOffsetTokens() {
    LocalTime time = LocalTime.of(12, 34, 56, 123456789);
    for (int seconds :
        new int[] {
          -64800, -3601, -3600, -3599, -61, -60, -1, 0, 1, 59, 60, 61, 3599, 3600, 64800
        }) {
      OffsetTime value = OffsetTime.of(time, ZoneOffset.ofTotalSeconds(seconds));
      String expected = '"' + DateTimeFormatter.ISO_OFFSET_TIME.format(value) + '"';
      for (String prefix : new String[] {"", "[0,"}) {
        for (int capacity : new int[] {1, 28, 29, prefix.length() + expected.length()}) {
          Utf8JsonWriter writer = newUtf8Writer(new byte[capacity]);
          writer.writeRawValue(prefix);
          writer.writeOffsetTime(value);
          writer.writeArrayEnd();
          assertEquals(
              new String(writer.toJsonBytes(), StandardCharsets.UTF_8), prefix + expected + ']');
        }
      }
    }
  }

  @Test
  public void writeClockComponents() {
    LocalDate date = LocalDate.of(2024, 2, 29);
    ZoneOffset offset = ZoneOffset.ofHoursMinutes(1, 30);
    for (int component = 0; component < 60; component++) {
      for (int nano : new int[] {0, 123456789}) {
        LocalTime time = LocalTime.of(component % 24, component, 59 - component, nano);
        LocalDateTime dateTime = LocalDateTime.of(date, time);
        assertWriter(ScalarCodecs.LocalTimeCodec.INSTANCE, time);
        assertWriter(ScalarCodecs.OffsetTimeCodec.INSTANCE, OffsetTime.of(time, offset));
        assertWriter(ScalarCodecs.LocalDateTimeCodec.INSTANCE, dateTime);
        assertWriter(
            ScalarCodecs.OffsetDateTimeCodec.INSTANCE, OffsetDateTime.of(dateTime, offset));
        assertWriter(ScalarCodecs.ZonedDateTimeCodec.INSTANCE, dateTime.atZone(offset));
      }
    }
  }

  @Test
  public void writeCalendarComponents() {
    for (int month = 1; month <= 12; month++) {
      int days = LocalDate.of(2024, month, 1).lengthOfMonth();
      for (int day = 1; day <= days; day++) {
        assertWriter(ScalarCodecs.LocalDateCodec.INSTANCE, LocalDate.of(2024, month, day));
      }
    }
  }

  @Test
  public void writeTimeFractions() {
    for (int digits = 0; digits < 1000; digits++) {
      int[] nanos = {
        digits,
        digits * 1000,
        digits * 1_000_000,
        digits * 1_001_000,
        digits * 1_000_000 + 999,
        999_000_000 + digits * 1000
      };
      for (int nano : nanos) {
        assertWriter(ScalarCodecs.LocalTimeCodec.INSTANCE, LocalTime.of(12, 34, 56, nano));
      }
    }
    int[] nanos = {
      0, 1, 10, 100, 1000, 10000, 100000, 1000000, 10000000, 100000000, 100010000, 123456789,
      999999990, 999999999
    };
    ZoneOffset offset = ZoneOffset.ofHoursMinutesSeconds(-7, -13, -29);
    for (int nano : nanos) {
      LocalTime time = LocalTime.of(12, 34, 56, nano);
      LocalDateTime dateTime = LocalDateTime.of(LocalDate.of(2024, 2, 29), time);
      assertTemporalCapacity(ScalarCodecs.LocalTimeCodec.INSTANCE, time);
      assertTemporalCapacity(ScalarCodecs.LocalDateTimeCodec.INSTANCE, dateTime);
      assertTemporalCapacity(ScalarCodecs.OffsetTimeCodec.INSTANCE, OffsetTime.of(time, offset));
      assertTemporalCapacity(
          ScalarCodecs.OffsetDateTimeCodec.INSTANCE, OffsetDateTime.of(dateTime, offset));
      assertTemporalCapacity(
          ScalarCodecs.ZonedDateTimeCodec.INSTANCE, dateTime.atZone(ZoneId.of("Europe/Paris")));
    }
  }

  private static <T> void assertTemporalCapacity(JsonValueCodec<T> codec, T value) {
    StringJsonWriter string = newStringWriter(new byte[1]);
    codec.writeString(string, value);
    for (int capacity = 0; capacity <= 40; capacity++) {
      for (int padding = 0; padding < 8; padding++) {
        Utf8JsonWriter writer = newUtf8Writer(new byte[capacity]);
        String prefix = "       ".substring(0, padding);
        writer.writeRawValue(prefix);
        codec.writeUtf8(writer, value);
        writer.writeRawValue(",17");
        assertEquals(
            new String(writer.toJsonBytes(), StandardCharsets.UTF_8),
            prefix + string.toJson() + ",17");
        writer.reset();
        codec.writeUtf8(writer, value);
        assertEquals(new String(writer.toJsonBytes(), StandardCharsets.UTF_8), string.toJson());
      }
    }
  }

  @Test
  public void writeTemporalFormats() {
    int[] years = {-999999999, -1, 0, 1, 9999, 10000, 999999999};
    int[] nanos = {
      0, 1, 10, 100, 1000, 9999, 10000, 10001, 100000, 1000000, 1000010, 10000000, 99999999,
      100000000, 100000001, 123456789, 999990000, 999999999
    };
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

  private static final class ZoneNameProvider extends ZoneRulesProvider {
    private final Set<String> ids =
        new HashSet<>(
            Arrays.asList(
                "ForyJson/Rules", "ForyJson/Lazy", "UT_ForyJson", "UTC_ForyJson", "GMT_ForyJson"));
    private ZoneRules rules = ZoneRules.of(ZoneOffset.ofHours(1));
    private int queries;

    private ZoneNameProvider() {
      String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789/~._+-";
      for (int i = 0; i < characters.length(); i++) {
        ids.add("ForyJson/Characters/" + characters.charAt(i));
      }
      String pairCharacters = "AZaz09/~._+-";
      for (int i = 0; i < pairCharacters.length(); i++) {
        for (int j = 0; j < pairCharacters.length(); j++) {
          String pair = "ForyJson/" + pairCharacters.charAt(i) + pairCharacters.charAt(j);
          for (String tail : new String[] {"", "A", "AA", "AAA"}) {
            ids.add(pair + tail);
          }
        }
      }
    }

    @Override
    protected Set<String> provideZoneIds() {
      return ids;
    }

    @Override
    protected ZoneRules provideRules(String zoneId, boolean forCaching) {
      if (forCaching) {
        queries++;
        if (zoneId.equals("ForyJson/Lazy")) {
          return null;
        }
      }
      return rules;
    }

    @Override
    protected NavigableMap<String, ZoneRules> provideVersions(String zoneId) {
      NavigableMap<String, ZoneRules> versions = new TreeMap<>();
      versions.put("test", rules);
      return versions;
    }
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

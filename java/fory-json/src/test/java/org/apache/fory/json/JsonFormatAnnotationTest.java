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

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.chrono.HijrahDate;
import java.time.chrono.JapaneseDate;
import java.time.chrono.MinguoDate;
import java.time.chrono.ThaiBuddhistDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.apache.fory.json.annotation.JsonAnyProperty;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonFormat;
import org.apache.fory.json.annotation.JsonMixin;
import org.apache.fory.json.annotation.JsonMixinRemove;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonRawValue;
import org.apache.fory.json.annotation.JsonUnwrapped;
import org.apache.fory.json.annotation.JsonValue;
import org.apache.fory.json.codec.Base64ByteArrayCodec;
import org.apache.fory.platform.JdkVersion;
import org.testng.SkipException;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class JsonFormatAnnotationTest extends ForyJsonTestModels {
  private static final String DATE_PATTERN = "dd/MM/uuuu";
  private static final String LOCAL_TIMESTAMP_PATTERN = "uuuu-MM-dd HH:mm:ss";
  private static final String TIMESTAMP_PATTERN = "uuuu-MM-dd HH:mm:ss XXX";
  private static final String ZONED_TIMESTAMP_PATTERN = "uuuu-MM-dd HH:mm:ss VV";
  private static final String ZONE_NAME_TIMESTAMP_PATTERN = "uuuu-MM-dd HH:mm:ss z";

  @Factory(dataProvider = "enableCodegen")
  public JsonFormatAnnotationTest(boolean codegen) {
    super(codegen);
  }

  @Test
  public void fieldRoundTrip() {
    ForyJson json = newJson();
    FormattedFields value = new FormattedFields();
    value.dayFirst = LocalDate.of(2024, 1, 2);
    value.monthFirst = LocalDate.of(2025, 3, 4);
    value.unicode = LocalDateTime.of(2026, 5, 6, 7, 8);
    String expected =
        "{\"dayFirst\":\"02/01/2024\",\"monthFirst\":\"03-04-2025\","
            + "\"unicode\":\"2026年05月06日 07:08\"}";
    assertEquals(json.toJson(value), expected);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), expected);
    assertFormattedFields(json.fromJson(expected, FormattedFields.class));
    assertFormattedFields(
        json.fromJson(expected.getBytes(StandardCharsets.UTF_8), FormattedFields.class));
    assertEquals(json.toJson(LocalDate.of(2024, 1, 2)), "\"2024-01-02\"");
    assertGeneratedWhenSupported(json, FormattedFields.class);
  }

  @Test
  public void wrapperRoundTrip() {
    ForyJson json = newJson();
    WrapperFields value = new WrapperFields();
    value.optional = Optional.of(LocalDate.of(2024, 1, 2));
    value.list = new ArrayList<>(Arrays.asList(LocalDate.of(2024, 1, 3), null));
    value.set = new LinkedHashSet<>(Arrays.asList(LocalDate.of(2024, 1, 4), null));
    value.map = new LinkedHashMap<>();
    value.map.put("a", LocalDate.of(2024, 1, 5));
    value.map.put("b", null);
    value.array = new LocalDate[] {LocalDate.of(2024, 1, 6), null};
    value.atomic = new AtomicReference<>(LocalDate.of(2024, 1, 7));
    value.atomicArray =
        new AtomicReferenceArray<>(new LocalDate[] {LocalDate.of(2024, 1, 8), null});
    String text = json.toJson(value);
    assertTrue(text.contains("\"optional\":\"02/01/2024\""), text);
    assertTrue(text.contains("\"list\":[\"03/01/2024\",null]"), text);
    assertTrue(text.contains("\"set\":[\"04/01/2024\",null]"), text);
    assertTrue(text.contains("\"map\":{\"a\":\"05/01/2024\",\"b\":null}"), text);
    assertTrue(text.contains("\"array\":[\"06/01/2024\",null]"), text);
    assertTrue(text.contains("\"atomic\":\"07/01/2024\""), text);
    assertTrue(text.contains("\"atomicArray\":[\"08/01/2024\",null]"), text);
    assertWrapperFields(json.fromJson(text, WrapperFields.class));
    byte[] bytes = json.toJsonBytes(value);
    assertEquals(new String(bytes, StandardCharsets.UTF_8), text);
    assertWrapperFields(json.fromJson(bytes, WrapperFields.class));
    assertGeneratedWhenSupported(json, WrapperFields.class);
  }

  @Test
  public void temporalTypes() {
    ForyJson json = newJson();
    TemporalFields value = temporalFields();
    String text = json.toJson(value);
    assertTrue(text.contains("\"instant\":\"2024/01/02 03:04:05.006\""), text);
    assertTemporalFields(json.fromJson(text, TemporalFields.class), value);
    byte[] bytes = json.toJsonBytes(value);
    assertEquals(new String(bytes, StandardCharsets.UTF_8), text);
    assertTemporalFields(json.fromJson(bytes, TemporalFields.class), value);
    assertGeneratedWhenSupported(json, TemporalFields.class);
  }

  @Test
  public void timezoneRoundTrip() {
    ForyJson json = newJson();
    Instant instant = Instant.parse("2024-01-02T03:04:05Z");
    TimezoneFields value = new TimezoneFields();
    value.instant = instant;
    value.zonedDateTime = instant.atZone(ZoneId.of("Europe/Paris"));
    value.offsetDateTime = instant.atOffset(ZoneOffset.ofHours(-4));
    value.values = Arrays.asList(instant, instant.plusSeconds(3600));
    String expected =
        "{\"instant\":\"2024-01-02 11:04:05\","
            + "\"zonedDateTime\":\"2024-01-02 11:04:05 +08:00\","
            + "\"offsetDateTime\":\"2024-01-02 11:04:05 +08:00\","
            + "\"values\":[\"2024-01-02 11:04:05 +08:00\","
            + "\"2024-01-02 12:04:05 +08:00\"]}";
    assertEquals(json.toJson(value), expected);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), expected);
    assertTimezoneFields(json.fromJson(expected, TimezoneFields.class), instant);
    assertTimezoneFields(
        json.fromJson(expected.getBytes(StandardCharsets.UTF_8), TimezoneFields.class), instant);
    assertGeneratedWhenSupported(json, TimezoneFields.class);
  }

  @Test
  public void offsetTimezoneParsing() {
    ForyJson json = newJson();
    String zoneNameText = "2024-11-03 01:30:00 EST";
    String input =
        "{\"local\":\"2024-01-02 11:04:05\","
            + "\"explicit\":\"2024-01-02 07:04:05 +04:00\","
            + "\"explicitZone\":\"2024-01-02 04:04:05 Europe/Paris\","
            + "\"zoneName\":\""
            + zoneNameText
            + "\"}";
    OffsetTimezoneFields value = json.fromJson(input, OffsetTimezoneFields.class);
    Instant instant = Instant.parse("2024-01-02T03:04:05Z");
    assertEquals(value.local.toInstant(), instant);
    assertEquals(value.local.getOffset(), ZoneOffset.ofHours(8));
    assertEquals(value.explicit.toInstant(), instant);
    assertEquals(value.explicit.getOffset(), ZoneOffset.ofHours(4));
    assertEquals(value.explicitZone.toInstant(), instant);
    assertEquals(value.explicitZone.getOffset(), ZoneOffset.ofHours(1));
    ZonedDateTime resolvedZoneName =
        DateTimeFormatter.ofPattern(ZONE_NAME_TIMESTAMP_PATTERN, Locale.ROOT)
            .withZone(ZoneId.of("Asia/Shanghai"))
            .parse(zoneNameText, ZonedDateTime::from);
    assertEquals(value.zoneName.toInstant(), resolvedZoneName.toInstant());
    assertEquals(value.zoneName.getOffset(), resolvedZoneName.getOffset());
    assertGeneratedWhenSupported(json, OffsetTimezoneFields.class);
  }

  @Test
  public void creatorRoundTrip() {
    ForyJson json = newJson();
    CreatorField value = new CreatorField(LocalDate.of(2024, 1, 2));
    assertEquals(json.toJson(value), "{\"value\":\"02/01/2024\"}");
    assertEquals(
        json.fromJson("{\"value\":\"03/01/2024\"}", CreatorField.class).value,
        LocalDate.of(2024, 1, 3));
    assertGeneratedWhenSupported(json, CreatorField.class);
  }

  @Test
  public void recordRoundTrip() throws Exception {
    if (JdkVersion.MAJOR_VERSION < 17) {
      throw new SkipException("Java record test requires JDK 17+");
    }
    Class<?> type =
        compileRecordClass(
            "JsonFormatRecord",
            "package org.apache.fory.json.records;\n"
                + "import java.time.Instant;\n"
                + "import org.apache.fory.json.annotation.JsonFormat;\n"
                + "public record JsonFormatRecord("
                + "@JsonFormat(pattern = \"uuuu-MM-dd HH:mm:ss XXX\", "
                + "timezone = \"Asia/Shanghai\") Instant value) {}\n");
    Instant value = Instant.parse("2024-01-02T03:04:05Z");
    Object record = type.getConstructor(Instant.class).newInstance(value);
    for (ForyJson json : new ForyJson[] {newJson(), newJsonBuilder().withFieldMode(true).build()}) {
      assertEquals(json.toJson(record), "{\"value\":\"2024-01-02 11:04:05 +08:00\"}");
      Object decoded = json.fromJson("{\"value\":\"2024-01-02 12:04:05 +08:00\"}", type);
      assertEquals(type.getMethod("value").invoke(decoded), value.plusSeconds(3600));
    }
  }

  @Test
  public void mixinRoundTrip() {
    ForyJson mixinJson = newJsonBuilder().registerMixin(FormatMixin.class).build();
    MixinTarget value = new MixinTarget();
    value.value = Instant.parse("2024-01-02T03:04:05Z");
    assertEquals(mixinJson.toJson(value), "{\"value\":\"2024-01-02 11:04:05 +08:00\"}");
    assertEquals(
        mixinJson.fromJson("{\"value\":\"2024-01-02 12:04:05 +08:00\"}", MixinTarget.class).value,
        value.value.plusSeconds(3600));

    ForyJson removalJson = newJsonBuilder().registerMixin(RemoveFormatMixin.class).build();
    IntrinsicTarget intrinsic = new IntrinsicTarget();
    intrinsic.value = LocalDate.of(2024, 1, 2);
    assertEquals(removalJson.toJson(intrinsic), "{\"value\":\"2024-01-02\"}");
  }

  @Test
  public void nullAndInvalidInput() {
    ForyJson json = newJson();
    assertEquals(json.toJson(new AlwaysFormat()), "{\"value\":null}");
    assertEquals(
        new String(json.toJsonBytes(new AlwaysFormat()), StandardCharsets.UTF_8),
        "{\"value\":null}");
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"value\":\"2024-01-02\"}", AlwaysFormat.class));
  }

  @Test
  public void rejectAmbiguousDeclarations() {
    ForyJson json = newJson();
    assertThrows(ForyJsonException.class, () -> json.toJson(new NestedWrapper()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new MapKeyFormat()));
    ForyJsonException rawError =
        expectThrows(ForyJsonException.class, () -> json.toJson(new RawWrapper()));
    assertTrue(rawError.getMessage().contains("@JsonFormat"), rawError.getMessage());
    ForyJsonException wildcardError =
        expectThrows(ForyJsonException.class, () -> json.toJson(new WildcardWrapper()));
    assertTrue(wildcardError.getMessage().contains("@JsonFormat"), wildcardError.getMessage());
    assertThrows(ForyJsonException.class, () -> json.toJson(new ValueWrapperField()));
    ForyJson mixinJson = newJsonBuilder().registerMixin(ValueWrapperMixin.class).build();
    assertThrows(ForyJsonException.class, () -> mixinJson.toJson(new ValueWrapperTarget()));
  }

  @Test
  public void rejectInvalidDeclarations() {
    ForyJson json = newJson();
    assertThrows(ForyJsonException.class, () -> json.toJson(new EmptyPattern()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new InvalidPattern()));
    ForyJsonException timezoneError =
        expectThrows(ForyJsonException.class, () -> json.toJson(new InvalidTimezone()));
    assertTrue(timezoneError.getMessage().contains("timezone"), timezoneError.getMessage());
    assertThrows(ForyJsonException.class, () -> json.toJson(new LocalTimezone()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new OffsetTimeTimezone()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new StringFormat()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new DurationFormat()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new LegacyDateFormat()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new CalendarFormat()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new SqlDateFormat()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new StaticFormat()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new TransientFormat()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new CodecFormat()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new RawFormat()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new AnyFormat()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new UnwrappedFormat()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new ValueFormat()));
  }

  private static void assertFormattedFields(FormattedFields value) {
    assertEquals(value.dayFirst, LocalDate.of(2024, 1, 2));
    assertEquals(value.monthFirst, LocalDate.of(2025, 3, 4));
    assertEquals(value.unicode, LocalDateTime.of(2026, 5, 6, 7, 8));
  }

  private static void assertWrapperFields(WrapperFields value) {
    assertEquals(value.optional, Optional.of(LocalDate.of(2024, 1, 2)));
    assertEquals(value.list, Arrays.asList(LocalDate.of(2024, 1, 3), null));
    assertEquals(value.set, new LinkedHashSet<>(Arrays.asList(LocalDate.of(2024, 1, 4), null)));
    Map<String, LocalDate> expectedMap = new LinkedHashMap<>();
    expectedMap.put("a", LocalDate.of(2024, 1, 5));
    expectedMap.put("b", null);
    assertEquals(value.map, expectedMap);
    assertEquals(value.array, new LocalDate[] {LocalDate.of(2024, 1, 6), null});
    assertEquals(value.atomic.get(), LocalDate.of(2024, 1, 7));
    assertEquals(value.atomicArray.length(), 2);
    assertEquals(value.atomicArray.get(0), LocalDate.of(2024, 1, 8));
    assertEquals(value.atomicArray.get(1), null);
  }

  private static void assertTimezoneFields(TimezoneFields value, Instant instant) {
    assertEquals(value.instant, instant);
    assertEquals(value.zonedDateTime.toInstant(), instant);
    assertEquals(value.zonedDateTime.getZone(), ZoneId.of("Asia/Shanghai"));
    assertEquals(value.offsetDateTime.toInstant(), instant);
    assertEquals(value.offsetDateTime.getOffset(), ZoneOffset.ofHours(8));
    assertEquals(value.values, Arrays.asList(instant, instant.plusSeconds(3600)));
  }

  private static TemporalFields temporalFields() {
    TemporalFields value = new TemporalFields();
    Instant instant = Instant.parse("2024-01-02T03:04:05.006Z");
    value.localDate = LocalDate.of(2024, 1, 2);
    value.localTime = LocalTime.of(3, 4, 5);
    value.localDateTime = LocalDateTime.of(2024, 1, 2, 3, 4, 5);
    value.instant = instant;
    value.zonedDateTime = ZonedDateTime.of(value.localDateTime, ZoneId.of("Europe/Paris"));
    value.year = Year.of(2024);
    value.yearMonth = YearMonth.of(2024, 1);
    value.monthDay = MonthDay.of(1, 2);
    value.offsetTime = OffsetTime.of(value.localTime, ZoneOffset.ofHoursMinutes(5, 30));
    value.offsetDateTime = OffsetDateTime.of(value.localDateTime, ZoneOffset.ofHours(-4));
    value.hijrahDate = HijrahDate.from(value.localDate);
    value.japaneseDate = JapaneseDate.from(value.localDate);
    value.minguoDate = MinguoDate.from(value.localDate);
    value.thaiBuddhistDate = ThaiBuddhistDate.from(value.localDate);
    return value;
  }

  private static void assertTemporalFields(TemporalFields actual, TemporalFields expected) {
    assertEquals(actual.localDate, expected.localDate);
    assertEquals(actual.localTime, expected.localTime);
    assertEquals(actual.localDateTime, expected.localDateTime);
    assertEquals(actual.instant, expected.instant);
    assertEquals(actual.zonedDateTime, expected.zonedDateTime);
    assertEquals(actual.year, expected.year);
    assertEquals(actual.yearMonth, expected.yearMonth);
    assertEquals(actual.monthDay, expected.monthDay);
    assertEquals(actual.offsetTime, expected.offsetTime);
    assertEquals(actual.offsetDateTime, expected.offsetDateTime);
    assertEquals(actual.hijrahDate, expected.hijrahDate);
    assertEquals(actual.japaneseDate, expected.japaneseDate);
    assertEquals(actual.minguoDate, expected.minguoDate);
    assertEquals(actual.thaiBuddhistDate, expected.thaiBuddhistDate);
  }

  public static final class FormattedFields {
    @JsonFormat(pattern = DATE_PATTERN)
    public LocalDate dayFirst;

    @JsonFormat(pattern = "MM-dd-uuuu")
    public LocalDate monthFirst;

    @JsonFormat(pattern = "uuuu'年'MM'月'dd'日' HH:mm")
    public LocalDateTime unicode;
  }

  public static final class WrapperFields {
    @JsonFormat(pattern = DATE_PATTERN)
    public Optional<LocalDate> optional;

    @JsonFormat(pattern = DATE_PATTERN)
    public List<LocalDate> list;

    @JsonFormat(pattern = DATE_PATTERN)
    public Set<LocalDate> set;

    @JsonFormat(pattern = DATE_PATTERN)
    public Map<String, LocalDate> map;

    @JsonFormat(pattern = DATE_PATTERN)
    public LocalDate[] array;

    @JsonFormat(pattern = DATE_PATTERN)
    public AtomicReference<LocalDate> atomic;

    @JsonFormat(pattern = DATE_PATTERN)
    public AtomicReferenceArray<LocalDate> atomicArray;
  }

  public static final class TemporalFields {
    @JsonFormat(pattern = DATE_PATTERN)
    public LocalDate localDate;

    @JsonFormat(pattern = "HH-mm-ss")
    public LocalTime localTime;

    @JsonFormat(pattern = "uuuuMMdd-HHmmss")
    public LocalDateTime localDateTime;

    @JsonFormat(pattern = "uuuu/MM/dd HH:mm:ss.SSS")
    public Instant instant;

    @JsonFormat(pattern = "uuuu/MM/dd HH:mm:ss VV")
    public ZonedDateTime zonedDateTime;

    @JsonFormat(pattern = "uuuu")
    public Year year;

    @JsonFormat(pattern = "uuuu/MM")
    public YearMonth yearMonth;

    @JsonFormat(pattern = "MM/dd")
    public MonthDay monthDay;

    @JsonFormat(pattern = "HH:mm:ssXXX")
    public OffsetTime offsetTime;

    @JsonFormat(pattern = "uuuu/MM/dd HH:mm:ssXXX")
    public OffsetDateTime offsetDateTime;

    @JsonFormat(pattern = DATE_PATTERN)
    public HijrahDate hijrahDate;

    @JsonFormat(pattern = DATE_PATTERN)
    public JapaneseDate japaneseDate;

    @JsonFormat(pattern = DATE_PATTERN)
    public MinguoDate minguoDate;

    @JsonFormat(pattern = DATE_PATTERN)
    public ThaiBuddhistDate thaiBuddhistDate;
  }

  public static final class TimezoneFields {
    @JsonFormat(pattern = LOCAL_TIMESTAMP_PATTERN, timezone = "Asia/Shanghai")
    public Instant instant;

    @JsonFormat(pattern = TIMESTAMP_PATTERN, timezone = "Asia/Shanghai")
    public ZonedDateTime zonedDateTime;

    @JsonFormat(pattern = TIMESTAMP_PATTERN, timezone = "Asia/Shanghai")
    public OffsetDateTime offsetDateTime;

    @JsonFormat(pattern = TIMESTAMP_PATTERN, timezone = "Asia/Shanghai")
    public List<Instant> values;
  }

  public static final class OffsetTimezoneFields {
    @JsonFormat(pattern = LOCAL_TIMESTAMP_PATTERN, timezone = "Asia/Shanghai")
    public OffsetDateTime local;

    @JsonFormat(pattern = TIMESTAMP_PATTERN, timezone = "Asia/Shanghai")
    public OffsetDateTime explicit;

    @JsonFormat(pattern = ZONED_TIMESTAMP_PATTERN, timezone = "Asia/Shanghai")
    public OffsetDateTime explicitZone;

    @JsonFormat(pattern = ZONE_NAME_TIMESTAMP_PATTERN, timezone = "Asia/Shanghai")
    public OffsetDateTime zoneName;
  }

  public static final class CreatorField {
    @JsonFormat(pattern = DATE_PATTERN)
    public final LocalDate value;

    @JsonCreator({"value"})
    public CreatorField(LocalDate value) {
      this.value = value;
    }
  }

  public static final class MixinTarget {
    public Instant value;
  }

  @JsonMixin(target = MixinTarget.class)
  public abstract static class FormatMixin {
    @JsonFormat(pattern = TIMESTAMP_PATTERN, timezone = "Asia/Shanghai")
    Instant value;
  }

  public static final class IntrinsicTarget {
    @JsonFormat(pattern = DATE_PATTERN)
    public LocalDate value;
  }

  @JsonMixin(target = IntrinsicTarget.class)
  public abstract static class RemoveFormatMixin {
    @JsonMixinRemove(JsonFormat.class)
    LocalDate value;
  }

  public static final class AlwaysFormat {
    @JsonFormat(pattern = DATE_PATTERN)
    @JsonProperty(include = JsonProperty.Include.ALWAYS)
    public LocalDate value;
  }

  public static final class NestedWrapper {
    @JsonFormat(pattern = DATE_PATTERN)
    public List<Optional<LocalDate>> value;
  }

  public static final class MapKeyFormat {
    @JsonFormat(pattern = DATE_PATTERN)
    public Map<LocalDate, String> value;
  }

  @SuppressWarnings("rawtypes")
  public static final class RawWrapper {
    @JsonFormat(pattern = DATE_PATTERN)
    public List value;
  }

  public static final class WildcardWrapper {
    @JsonFormat(pattern = DATE_PATTERN)
    public List<? extends LocalDate> value;
  }

  public static final class ValueList extends ArrayList<LocalDate> {
    public ValueList() {}

    @JsonCreator
    public ValueList(String value) {}

    @JsonValue
    public String value() {
      return "value";
    }
  }

  public static final class ValueWrapperField {
    @JsonFormat(pattern = DATE_PATTERN)
    public ValueList value;
  }

  public static final class ValueWrapperTarget {
    public ValueList value;
  }

  @JsonMixin(target = ValueWrapperTarget.class)
  public abstract static class ValueWrapperMixin {
    @JsonFormat(pattern = DATE_PATTERN)
    ValueList value;
  }

  public static final class EmptyPattern {
    @JsonFormat(pattern = "")
    public LocalDate value;
  }

  public static final class InvalidPattern {
    @JsonFormat(pattern = "invalid")
    public LocalDate value;
  }

  public static final class InvalidTimezone {
    @JsonFormat(pattern = TIMESTAMP_PATTERN, timezone = "Not/AZone")
    public Instant value;
  }

  public static final class LocalTimezone {
    @JsonFormat(pattern = DATE_PATTERN, timezone = "UTC")
    public LocalDate value;
  }

  public static final class OffsetTimeTimezone {
    @JsonFormat(pattern = "HH:mm:ss XXX", timezone = "UTC")
    public OffsetTime value;
  }

  public static final class StringFormat {
    @JsonFormat(pattern = DATE_PATTERN)
    public String value;
  }

  public static final class DurationFormat {
    @JsonFormat(pattern = DATE_PATTERN)
    public Duration value;
  }

  public static final class LegacyDateFormat {
    @JsonFormat(pattern = DATE_PATTERN)
    public java.util.Date value;
  }

  public static final class CalendarFormat {
    @JsonFormat(pattern = DATE_PATTERN)
    public java.util.Calendar value;
  }

  public static final class SqlDateFormat {
    @JsonFormat(pattern = DATE_PATTERN)
    public Date value;
  }

  public static final class StaticFormat {
    @JsonFormat(pattern = DATE_PATTERN)
    public static LocalDate value;
  }

  public static final class TransientFormat {
    @JsonFormat(pattern = DATE_PATTERN)
    public transient LocalDate value;
  }

  public static final class CodecFormat {
    @JsonFormat(pattern = DATE_PATTERN)
    @JsonCodec(Base64ByteArrayCodec.class)
    public LocalDate value;
  }

  public static final class RawFormat {
    @JsonFormat(pattern = DATE_PATTERN)
    @JsonRawValue
    public LocalDate value;
  }

  public static final class AnyFormat {
    @JsonFormat(pattern = DATE_PATTERN)
    @JsonAnyProperty
    public Map<String, LocalDate> value;
  }

  public static final class UnwrappedFormat {
    @JsonFormat(pattern = DATE_PATTERN)
    @JsonUnwrapped
    public LocalDate value;
  }

  public static final class ValueFormat {
    @JsonFormat(pattern = DATE_PATTERN)
    @JsonValue
    public LocalDate value;
  }
}

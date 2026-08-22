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

package org.apache.fory.json.codec;

import java.time.DateTimeException;
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
import java.time.chrono.HijrahChronology;
import java.time.chrono.HijrahDate;
import java.time.chrono.JapaneseChronology;
import java.time.chrono.JapaneseDate;
import java.time.chrono.MinguoChronology;
import java.time.chrono.MinguoDate;
import java.time.chrono.ThaiBuddhistChronology;
import java.time.chrono.ThaiBuddhistDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalQueries;
import java.util.Locale;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;

/** Property-local codec for one validated {@code JsonFormat} date/time field. */
final class DateTimeFormatCodec implements JsonValueCodec<Object> {
  private static final int LOCAL_DATE = 1;
  private static final int LOCAL_TIME = 2;
  private static final int LOCAL_DATE_TIME = 3;
  private static final int INSTANT = 4;
  private static final int ZONED_DATE_TIME = 5;
  private static final int YEAR = 6;
  private static final int YEAR_MONTH = 7;
  private static final int MONTH_DAY = 8;
  private static final int OFFSET_TIME = 9;
  private static final int OFFSET_DATE_TIME = 10;
  private static final int HIJRAH_DATE = 11;
  private static final int JAPANESE_DATE = 12;
  private static final int MINGUO_DATE = 13;
  private static final int THAI_BUDDHIST_DATE = 14;
  private static final int OFFSET_DATE_TIME_WITH_ZONE = 15;

  private final Class<?> type;
  private final int kind;
  private final DateTimeFormatter formatter;

  static JsonValueCodec<?> create(Class<?> type, String pattern, String timezone) {
    if (pattern.isEmpty()) {
      throw invalidPattern(type, pattern, null);
    }
    int kind = kind(type);
    DateTimeFormatter formatter;
    try {
      formatter = DateTimeFormatter.ofPattern(pattern, Locale.ROOT);
    } catch (IllegalArgumentException e) {
      throw invalidPattern(type, pattern, e);
    }
    if (timezone.isEmpty()) {
      if (kind == INSTANT) {
        formatter = formatter.withZone(ZoneOffset.UTC);
      }
    } else {
      if (kind != INSTANT && kind != ZONED_DATE_TIME && kind != OFFSET_DATE_TIME) {
        throw unsupportedTimezone(type);
      }
      try {
        formatter = formatter.withZone(ZoneId.of(timezone));
      } catch (DateTimeException e) {
        throw invalidTimezone(type, timezone, e);
      }
      if (kind == OFFSET_DATE_TIME) {
        kind = OFFSET_DATE_TIME_WITH_ZONE;
      }
    }
    if (kind == HIJRAH_DATE) {
      formatter = formatter.withChronology(HijrahChronology.INSTANCE);
    } else if (kind == JAPANESE_DATE) {
      formatter = formatter.withChronology(JapaneseChronology.INSTANCE);
    } else if (kind == MINGUO_DATE) {
      formatter = formatter.withChronology(MinguoChronology.INSTANCE);
    } else if (kind == THAI_BUDDHIST_DATE) {
      formatter = formatter.withChronology(ThaiBuddhistChronology.INSTANCE);
    }
    return new DateTimeFormatCodec(type, kind, formatter);
  }

  private DateTimeFormatCodec(Class<?> type, int kind, DateTimeFormatter formatter) {
    this.type = type;
    this.kind = kind;
    this.formatter = formatter;
  }

  @Override
  public void writeString(StringJsonWriter writer, Object value) {
    if (value == null) {
      writer.writeNull();
    } else {
      writer.writeTemporal((TemporalAccessor) value, formatter);
    }
  }

  @Override
  public void writeUtf8(Utf8JsonWriter writer, Object value) {
    if (value == null) {
      writer.writeNull();
    } else {
      writer.writeTemporal((TemporalAccessor) value, formatter);
    }
  }

  @Override
  public Object readLatin1(Latin1JsonReader reader) {
    CharSequence value = reader.readQuotedText();
    return value == null ? null : parse(value);
  }

  @Override
  public Object readUtf16(Utf16JsonReader reader) {
    CharSequence value = reader.readQuotedText();
    return value == null ? null : parse(value);
  }

  @Override
  public Object readUtf8(Utf8JsonReader reader) {
    CharSequence value = reader.readQuotedText();
    return value == null ? null : parse(value);
  }

  private Object parse(CharSequence value) {
    try {
      TemporalAccessor parsed = formatter.parse(value);
      switch (kind) {
        case LOCAL_DATE:
          return LocalDate.from(parsed);
        case LOCAL_TIME:
          return LocalTime.from(parsed);
        case LOCAL_DATE_TIME:
          return LocalDateTime.from(parsed);
        case INSTANT:
          return Instant.from(parsed);
        case ZONED_DATE_TIME:
          return ZonedDateTime.from(parsed);
        case YEAR:
          return Year.from(parsed);
        case YEAR_MONTH:
          return YearMonth.from(parsed);
        case MONTH_DAY:
          return MonthDay.from(parsed);
        case OFFSET_TIME:
          return OffsetTime.from(parsed);
        case OFFSET_DATE_TIME:
          return OffsetDateTime.from(parsed);
        case OFFSET_DATE_TIME_WITH_ZONE:
          return parseOffsetDateTime(parsed);
        case HIJRAH_DATE:
          return HijrahDate.from(parsed);
        case JAPANESE_DATE:
          return JapaneseDate.from(parsed);
        case MINGUO_DATE:
          return MinguoDate.from(parsed);
        case THAI_BUDDHIST_DATE:
          return ThaiBuddhistDate.from(parsed);
        default:
          throw new AssertionError(kind);
      }
    } catch (RuntimeException e) {
      throw invalidValue(type, value, e);
    }
  }

  private OffsetDateTime parseOffsetDateTime(TemporalAccessor parsed) {
    LocalDateTime dateTime = LocalDateTime.from(parsed);
    ZoneOffset offset = parsed.query(TemporalQueries.offset());
    // An effective region ZoneId does not synthesize OFFSET_SECONDS. Keep an explicit offset
    // authoritative. The rule-owned offset is allocation-free for historical second-level offsets;
    // only reconstruct it when a zone name selected the other offset during an overlap.
    if (offset == null) {
      offset = parsed.query(TemporalQueries.zone()).getRules().getOffset(dateTime);
      long localEpochSeconds = dateTime.toEpochSecond(ZoneOffset.UTC);
      long instantSeconds = parsed.getLong(ChronoField.INSTANT_SECONDS);
      if (localEpochSeconds - offset.getTotalSeconds() != instantSeconds) {
        offset = ZoneOffset.ofTotalSeconds(Math.toIntExact(localEpochSeconds - instantSeconds));
      }
    }
    return OffsetDateTime.of(dateTime, offset);
  }

  static boolean supports(Class<?> type) {
    return type == LocalDate.class
        || type == LocalTime.class
        || type == LocalDateTime.class
        || type == Instant.class
        || type == ZonedDateTime.class
        || type == Year.class
        || type == YearMonth.class
        || type == MonthDay.class
        || type == OffsetTime.class
        || type == OffsetDateTime.class
        || type == HijrahDate.class
        || type == JapaneseDate.class
        || type == MinguoDate.class
        || type == ThaiBuddhistDate.class;
  }

  private static int kind(Class<?> type) {
    if (type == LocalDate.class) {
      return LOCAL_DATE;
    }
    if (type == LocalTime.class) {
      return LOCAL_TIME;
    }
    if (type == LocalDateTime.class) {
      return LOCAL_DATE_TIME;
    }
    if (type == Instant.class) {
      return INSTANT;
    }
    if (type == ZonedDateTime.class) {
      return ZONED_DATE_TIME;
    }
    if (type == Year.class) {
      return YEAR;
    }
    if (type == YearMonth.class) {
      return YEAR_MONTH;
    }
    if (type == MonthDay.class) {
      return MONTH_DAY;
    }
    if (type == OffsetTime.class) {
      return OFFSET_TIME;
    }
    if (type == OffsetDateTime.class) {
      return OFFSET_DATE_TIME;
    }
    if (type == HijrahDate.class) {
      return HIJRAH_DATE;
    }
    if (type == JapaneseDate.class) {
      return JAPANESE_DATE;
    }
    if (type == MinguoDate.class) {
      return MINGUO_DATE;
    }
    if (type == ThaiBuddhistDate.class) {
      return THAI_BUDDHIST_DATE;
    }
    throw new ForyJsonException("@JsonFormat is not supported on field type " + type.getTypeName());
  }

  private static ForyJsonException invalidPattern(Class<?> type, String pattern, Throwable cause) {
    return new ForyJsonException(
        "Invalid @JsonFormat pattern for " + type.getTypeName() + ": " + pattern, cause);
  }

  private static ForyJsonException invalidTimezone(
      Class<?> type, String timezone, Throwable cause) {
    return new ForyJsonException(
        "Invalid @JsonFormat timezone for " + type.getTypeName() + ": " + timezone, cause);
  }

  private static ForyJsonException unsupportedTimezone(Class<?> type) {
    return new ForyJsonException("@JsonFormat timezone is not supported for " + type.getTypeName());
  }

  private static ForyJsonException invalidValue(
      Class<?> type, CharSequence value, Throwable cause) {
    return new ForyJsonException(
        "Invalid @JsonFormat value for " + type.getTypeName() + ": " + value.toString(), cause);
  }
}

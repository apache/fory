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

package org.apache.fory.serializer;

import java.sql.Time;
import java.sql.Timestamp;
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
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import org.apache.fory.config.Config;
import org.apache.fory.context.CopyContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.util.DateTimeUtils;

/** Serializers for all time related types. */
public class TimeSerializers {
  public abstract static class TimeSerializer<T> extends Serializer<T> {
    protected final Config config;

    public TimeSerializer(Config config, Class<T> type) {
      super(config, type, !config.isTimeRefIgnored(), false);
      this.config = config;
    }

    public TimeSerializer(Config config, Class<T> type, boolean needToWriteRef) {
      super(config, type, needToWriteRef, false);
      this.config = config;
    }
  }

  public abstract static class ImmutableTimeSerializer<T> extends ImmutableSerializer<T> {
    protected final Config config;

    public ImmutableTimeSerializer(Config config, Class<T> type) {
      super(config, type, !config.isTimeRefIgnored());
      this.config = config;
    }

    public ImmutableTimeSerializer(Config config, Class<T> type, boolean needToWriteRef) {
      super(config, type, needToWriteRef);
      this.config = config;
    }
  }

  public abstract static class BaseDateSerializer<T extends Date> extends TimeSerializer<T> {
    public BaseDateSerializer(Config config, Class<T> type) {
      super(config, type);
    }

    public BaseDateSerializer(Config config, Class<T> type, boolean needToWriteRef) {
      super(config, type, needToWriteRef);
    }

    @Override
    public void write(WriteContext writeContext, T value) {
      writeContext.getBuffer().writeInt64(value.getTime());
    }

    @Override
    public T read(ReadContext readContext) {
      return newInstance(readContext.getBuffer().readInt64());
    }

    protected abstract T newInstance(long time);
  }

  public static final class DateSerializer extends BaseDateSerializer<Date> {
    public DateSerializer(Config config) {
      super(config, Date.class);
    }

    public DateSerializer(Config config, boolean needToWriteRef) {
      super(config, Date.class, needToWriteRef);
    }

    @Override
    protected Date newInstance(long time) {
      return new Date(time);
    }

    @Override
    public Date copy(CopyContext copyContext, Date value) {
      return newInstance(value.getTime());
    }
  }

  public static final class SqlDateSerializer extends BaseDateSerializer<java.sql.Date> {
    public SqlDateSerializer(Config config) {
      super(config, java.sql.Date.class);
    }

    public SqlDateSerializer(Config config, boolean needToWriteRef) {
      super(config, java.sql.Date.class, needToWriteRef);
    }

    @Override
    protected java.sql.Date newInstance(long time) {
      return new java.sql.Date(time);
    }

    @Override
    public java.sql.Date copy(CopyContext copyContext, java.sql.Date value) {
      return newInstance(value.getTime());
    }
  }

  public static final class SqlTimeSerializer extends BaseDateSerializer<Time> {

    public SqlTimeSerializer(Config config) {
      super(config, Time.class);
    }

    public SqlTimeSerializer(Config config, boolean needToWriteRef) {
      super(config, Time.class, needToWriteRef);
    }

    @Override
    protected Time newInstance(long time) {
      return new Time(time);
    }

    @Override
    public Time copy(CopyContext copyContext, Time value) {
      return newInstance(value.getTime());
    }
  }

  public static final class TimestampSerializer extends TimeSerializer<Timestamp> {

    public TimestampSerializer(Config config) {
      // conflict with instant
      super(config, Timestamp.class);
    }

    public TimestampSerializer(Config config, boolean needToWriteRef) {
      super(config, Timestamp.class, needToWriteRef);
    }

    @Override
    public void write(WriteContext writeContext, Timestamp value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      if (config.isXlang()) {
        Instant instant = value.toInstant();
        buffer.writeInt64(instant.getEpochSecond());
        buffer.writeInt32(instant.getNano());
      } else {
        long time = value.getTime() - (value.getNanos() / 1_000_000);
        buffer.writeInt64(time);
        buffer.writeInt32(value.getNanos());
      }
    }

    @Override
    public Timestamp read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      if (config.isXlang()) {
        long seconds = buffer.readInt64();
        int nanos = buffer.readInt32();
        return Timestamp.from(Instant.ofEpochSecond(seconds, nanos));
      }
      Timestamp t = new Timestamp(buffer.readInt64());
      t.setNanos(buffer.readInt32());
      return t;
    }

    @Override
    public Timestamp copy(CopyContext copyContext, Timestamp value) {
      return new Timestamp(value.getTime());
    }
  }

  public static final class LocalDateSerializer extends ImmutableTimeSerializer<LocalDate>
      implements Shareable {
    public LocalDateSerializer(Config config) {
      super(config, LocalDate.class);
    }

    public LocalDateSerializer(Config config, boolean needToWriteRef) {
      super(config, LocalDate.class, needToWriteRef);
    }

    @Override
    public void write(WriteContext writeContext, LocalDate value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      if (config.isXlang()) {
        // TODO use java encoding to support larger range.
        buffer.writeInt32(DateTimeUtils.localDateToDays(value));
      } else {
        writeLocalDate(buffer, value);
      }
    }

    public static void writeLocalDate(MemoryBuffer buffer, LocalDate value) {
      buffer.writeInt32(value.getYear());
      buffer.writeByte(value.getMonthValue());
      buffer.writeByte(value.getDayOfMonth());
    }

    @Override
    public LocalDate read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      if (config.isXlang()) {
        return DateTimeUtils.daysToLocalDate(buffer.readInt32());
      }
      return readLocalDate(buffer);
    }

    public static LocalDate readLocalDate(MemoryBuffer buffer) {
      int year = buffer.readInt32();
      int month = buffer.readByte();
      int dayOfMonth = buffer.readByte();
      return LocalDate.of(year, month, dayOfMonth);
    }
  }

  public static final class InstantSerializer extends ImmutableTimeSerializer<Instant>
      implements Shareable {
    public InstantSerializer(Config config) {
      super(config, Instant.class);
    }

    public InstantSerializer(Config config, boolean needToWriteRef) {
      super(config, Instant.class, needToWriteRef);
    }

    @Override
    public void write(WriteContext writeContext, Instant value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      buffer.writeInt64(value.getEpochSecond());
      buffer.writeInt32(value.getNano());
    }

    @Override
    public Instant read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      long seconds = buffer.readInt64();
      int nanos = buffer.readInt32();
      return Instant.ofEpochSecond(seconds, nanos);
    }
  }

  public static class DurationSerializer extends ImmutableTimeSerializer<Duration>
      implements Shareable {
    public DurationSerializer(Config config) {
      super(config, Duration.class);
    }

    public DurationSerializer(Config config, boolean needToWriteRef) {
      super(config, Duration.class, needToWriteRef);
    }

    @Override
    public void write(WriteContext writeContext, Duration value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      buffer.writeVarInt64(value.getSeconds());
      buffer.writeInt32(value.getNano());
    }

    @Override
    public Duration read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      long seconds = buffer.readVarInt64();
      int nanos = buffer.readInt32();
      return Duration.ofSeconds(seconds, nanos);
    }
  }

  public static class LocalDateTimeSerializer extends ImmutableTimeSerializer<LocalDateTime>
      implements Shareable {
    public LocalDateTimeSerializer(Config config) {
      super(config, LocalDateTime.class);
    }

    public LocalDateTimeSerializer(Config config, boolean needToWriteRef) {
      super(config, LocalDateTime.class, needToWriteRef);
    }

    @Override
    public void write(WriteContext writeContext, LocalDateTime value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      LocalDateSerializer.writeLocalDate(buffer, value.toLocalDate());
      LocalTimeSerializer.writeLocalTime(buffer, value.toLocalTime());
    }

    @Override
    public LocalDateTime read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      LocalDate date = LocalDateSerializer.readLocalDate(buffer);
      LocalTime time = LocalTimeSerializer.readLocalTime(buffer);
      return LocalDateTime.of(date, time);
    }
  }

  public static class LocalTimeSerializer extends ImmutableTimeSerializer<LocalTime>
      implements Shareable {
    public LocalTimeSerializer(Config config) {
      super(config, LocalTime.class);
    }

    public LocalTimeSerializer(Config config, boolean needToWriteRef) {
      super(config, LocalTime.class, needToWriteRef);
    }

    @Override
    public void write(WriteContext writeContext, LocalTime time) {
      MemoryBuffer buffer = writeContext.getBuffer();
      writeLocalTime(buffer, time);
    }

    static void writeLocalTime(MemoryBuffer buffer, LocalTime time) {
      if (time.getNano() == 0) {
        if (time.getSecond() == 0) {
          if (time.getMinute() == 0) {
            buffer.writeByte(~time.getHour());
          } else {
            buffer.writeByte(time.getHour());
            buffer.writeByte(~time.getMinute());
          }
        } else {
          buffer.writeByte(time.getHour());
          buffer.writeByte(time.getMinute());
          buffer.writeByte(~time.getSecond());
        }
      } else {
        buffer.writeByte(time.getHour());
        buffer.writeByte(time.getMinute());
        buffer.writeByte(time.getSecond());
        buffer.writeInt32(time.getNano());
      }
    }

    @Override
    public LocalTime read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      return readLocalTime(buffer);
    }

    static LocalTime readLocalTime(MemoryBuffer buffer) {
      int hour = buffer.readByte();
      int minute = 0;
      int second = 0;
      int nano = 0;
      if (hour < 0) {
        hour = ~hour;
      } else {
        minute = buffer.readByte();
        if (minute < 0) {
          minute = ~minute;
        } else {
          second = buffer.readByte();
          if (second < 0) {
            second = ~second;
          } else {
            nano = buffer.readInt32();
          }
        }
      }
      return LocalTime.of(hour, minute, second, nano);
    }
  }

  public static class TimeZoneSerializer extends TimeSerializer<TimeZone> {
    public TimeZoneSerializer(Config config, Class<TimeZone> type) {
      super(config, type);
    }

    public TimeZoneSerializer(Config config, boolean needToWriteRef) {
      super(config, TimeZone.class, needToWriteRef);
    }

    public void write(WriteContext writeContext, TimeZone object) {
      writeContext.writeString(object.getID());
    }

    public TimeZone read(ReadContext readContext) {
      return TimeZone.getTimeZone(readContext.readString());
    }

    @Override
    public TimeZone copy(CopyContext copyContext, TimeZone value) {
      return TimeZone.getTimeZone(value.getID());
    }
  }

  public static final class CalendarSerializer extends TimeSerializer<Calendar> {
    private static final long DEFAULT_GREGORIAN_CUTOVER = -12219292800000L;
    private final TimeZoneSerializer timeZoneSerializer;

    public CalendarSerializer(Config config, Class<Calendar> type) {
      super(config, type);
      timeZoneSerializer = new TimeZoneSerializer(config, TimeZone.class);
    }

    public CalendarSerializer(Config config, Class<Calendar> type, boolean needToWriteRef) {
      super(config, type, needToWriteRef);
      timeZoneSerializer = new TimeZoneSerializer(config, TimeZone.class);
    }

    public void write(WriteContext writeContext, Calendar object) {
      MemoryBuffer buffer = writeContext.getBuffer();
      timeZoneSerializer.write(writeContext, object.getTimeZone());
      buffer.writeInt64(object.getTimeInMillis());
      buffer.writeBoolean(object.isLenient());
      buffer.writeByte(object.getFirstDayOfWeek());
      buffer.writeByte(object.getMinimalDaysInFirstWeek());
      if (object instanceof GregorianCalendar) {
        buffer.writeInt64(((GregorianCalendar) object).getGregorianChange().getTime());
      } else {
        buffer.writeInt64(DEFAULT_GREGORIAN_CUTOVER);
      }
    }

    public Calendar read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      Calendar result = Calendar.getInstance(timeZoneSerializer.read(readContext));
      result.setTimeInMillis(buffer.readInt64());
      result.setLenient(buffer.readBoolean());
      result.setFirstDayOfWeek(buffer.readByte());
      result.setMinimalDaysInFirstWeek(buffer.readByte());
      long gregorianChange = buffer.readInt64();
      if (gregorianChange != DEFAULT_GREGORIAN_CUTOVER) {
        if (result instanceof GregorianCalendar) {
          ((GregorianCalendar) result).setGregorianChange(new Date(gregorianChange));
        }
      }
      return result;
    }

    @Override
    public Calendar copy(CopyContext copyContext, Calendar value) {
      Calendar copy = Calendar.getInstance(value.getTimeZone());
      copy.setTimeInMillis(value.getTimeInMillis());
      copy.setLenient(value.isLenient());
      copy.setFirstDayOfWeek(value.getFirstDayOfWeek());
      copy.setMinimalDaysInFirstWeek(value.getMinimalDaysInFirstWeek());
      if (value instanceof GregorianCalendar) {
        ((GregorianCalendar) copy)
            .setGregorianChange(((GregorianCalendar) value).getGregorianChange());
      }
      return copy;
    }
  }

  public static class ZoneIdSerializer extends ImmutableTimeSerializer<ZoneId>
      implements Shareable {
    public ZoneIdSerializer(Config config, Class<ZoneId> type) {
      super(config, type);
    }

    public ZoneIdSerializer(Config config, Class<ZoneId> type, boolean needToWriteRef) {
      super(config, type, needToWriteRef);
    }

    @Override
    public void write(WriteContext writeContext, ZoneId obj) {
      writeContext.writeString(obj.getId());
    }

    @Override
    public ZoneId read(ReadContext readContext) {
      return ZoneId.of(readContext.readString());
    }
  }

  public static class ZoneOffsetSerializer extends ImmutableTimeSerializer<ZoneOffset>
      implements Shareable {

    // cached zone offsets for the single byte representation, using this overrides the JDK zone
    // offset caching which uses a concurrent hash map for zone offsets that causes a noticeable
    // overhead (see ZoneOffset.ofTotalSeconds impl), cached each 15 minutes (in line with the
    // compression -72 to +72)
    private static final ZoneOffset[] COMPRESSED_ZONE_OFFSETS = new ZoneOffset[145];

    static {
      for (int i = 0; i < COMPRESSED_ZONE_OFFSETS.length; i++) {
        COMPRESSED_ZONE_OFFSETS[i] = ZoneOffset.ofTotalSeconds((i - 72) * 900);
      }
    }

    public ZoneOffsetSerializer(Config config) {
      super(config, ZoneOffset.class);
    }

    public ZoneOffsetSerializer(Config config, boolean needToWriteRef) {
      super(config, ZoneOffset.class, needToWriteRef);
    }

    public void write(WriteContext writeContext, ZoneOffset obj) {
      MemoryBuffer buffer = writeContext.getBuffer();
      writeZoneOffset(buffer, obj);
    }

    public static void writeZoneOffset(MemoryBuffer buffer, ZoneOffset obj) {
      final int offsetSecs = obj.getTotalSeconds();
      int offsetByte = offsetSecs % 900 == 0 ? offsetSecs / 900 : 127; // compress to -72 to +72
      buffer.writeByte(offsetByte);
      if (offsetByte == 127) {
        buffer.writeInt32(offsetSecs);
      }
    }

    public ZoneOffset read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      return readZoneOffset(buffer);
    }

    public static ZoneOffset readZoneOffset(MemoryBuffer buffer) {
      int offsetByte = buffer.readByte();
      if (offsetByte == 127) {
        return ZoneOffset.ofTotalSeconds(buffer.readInt32());
      }
      return COMPRESSED_ZONE_OFFSETS[offsetByte + 72];
    }
  }

  public static class ZonedDateTimeSerializer extends ImmutableTimeSerializer<ZonedDateTime>
      implements Shareable {

    public ZonedDateTimeSerializer(Config config) {
      super(config, ZonedDateTime.class);
    }

    public ZonedDateTimeSerializer(Config config, boolean needToWriteRef) {
      super(config, ZonedDateTime.class, needToWriteRef);
    }

    public void write(WriteContext writeContext, ZonedDateTime obj) {
      MemoryBuffer buffer = writeContext.getBuffer();
      LocalDateSerializer.writeLocalDate(buffer, obj.toLocalDate());
      LocalTimeSerializer.writeLocalTime(buffer, obj.toLocalTime());
      writeContext.writeString(obj.getZone().getId());
    }

    public ZonedDateTime read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      LocalDate date = LocalDateSerializer.readLocalDate(buffer);
      LocalTime time = LocalTimeSerializer.readLocalTime(buffer);
      ZoneId zone = ZoneId.of(readContext.readString());
      return ZonedDateTime.of(date, time, zone);
    }
  }

  public static class YearSerializer extends ImmutableTimeSerializer<Year> implements Shareable {
    public YearSerializer(Config config) {
      super(config, Year.class);
    }

    public YearSerializer(Config config, boolean needToWriteRef) {
      super(config, Year.class, needToWriteRef);
    }

    @Override
    public void write(WriteContext writeContext, Year obj) {
      MemoryBuffer buffer = writeContext.getBuffer();
      buffer.writeInt32(obj.getValue());
    }

    @Override
    public Year read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      return Year.of(buffer.readInt32());
    }
  }

  public static class YearMonthSerializer extends ImmutableTimeSerializer<YearMonth>
      implements Shareable {
    public YearMonthSerializer(Config config) {
      super(config, YearMonth.class);
    }

    public YearMonthSerializer(Config config, boolean needToWriteRef) {
      super(config, YearMonth.class, needToWriteRef);
    }

    @Override
    public void write(WriteContext writeContext, YearMonth obj) {
      MemoryBuffer buffer = writeContext.getBuffer();
      buffer.writeInt32(obj.getYear());
      buffer.writeByte(obj.getMonthValue());
    }

    @Override
    public YearMonth read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int year = buffer.readInt32();
      byte month = buffer.readByte();
      return YearMonth.of(year, month);
    }
  }

  public static class MonthDaySerializer extends ImmutableTimeSerializer<MonthDay>
      implements Shareable {
    public MonthDaySerializer(Config config) {
      super(config, MonthDay.class);
    }

    public MonthDaySerializer(Config config, boolean needToWriteRef) {
      super(config, MonthDay.class, needToWriteRef);
    }

    public void write(WriteContext writeContext, MonthDay obj) {
      MemoryBuffer buffer = writeContext.getBuffer();
      buffer.writeByte(obj.getMonthValue());
      buffer.writeByte(obj.getDayOfMonth());
    }

    public MonthDay read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      byte month = buffer.readByte();
      byte day = buffer.readByte();
      return MonthDay.of(month, day);
    }
  }

  public static class PeriodSerializer extends ImmutableTimeSerializer<Period>
      implements Shareable {
    public PeriodSerializer(Config config) {
      super(config, Period.class);
    }

    public PeriodSerializer(Config config, boolean needToWriteRef) {
      super(config, Period.class, needToWriteRef);
    }

    public void write(WriteContext writeContext, Period obj) {
      MemoryBuffer buffer = writeContext.getBuffer();
      buffer.writeInt32(obj.getYears());
      buffer.writeInt32(obj.getMonths());
      buffer.writeInt32(obj.getDays());
    }

    public Period read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int years = buffer.readInt32();
      int months = buffer.readInt32();
      int days = buffer.readInt32();
      return Period.of(years, months, days);
    }
  }

  public static class OffsetTimeSerializer extends ImmutableTimeSerializer<OffsetTime>
      implements Shareable {
    public OffsetTimeSerializer(Config config) {
      super(config, OffsetTime.class);
    }

    public OffsetTimeSerializer(Config config, boolean needToWriteRef) {
      super(config, OffsetTime.class, needToWriteRef);
    }

    public void write(WriteContext writeContext, OffsetTime obj) {
      MemoryBuffer buffer = writeContext.getBuffer();
      LocalTimeSerializer.writeLocalTime(buffer, obj.toLocalTime());
      ZoneOffsetSerializer.writeZoneOffset(buffer, obj.getOffset());
    }

    public OffsetTime read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      LocalTime time = LocalTimeSerializer.readLocalTime(buffer);
      ZoneOffset offset = ZoneOffsetSerializer.readZoneOffset(buffer);
      return OffsetTime.of(time, offset);
    }
  }

  public static class OffsetDateTimeSerializer extends ImmutableTimeSerializer<OffsetDateTime>
      implements Shareable {
    public OffsetDateTimeSerializer(Config config) {
      super(config, OffsetDateTime.class);
    }

    public OffsetDateTimeSerializer(Config config, boolean needToWriteRef) {
      super(config, OffsetDateTime.class, needToWriteRef);
    }

    public void write(WriteContext writeContext, OffsetDateTime obj) {
      MemoryBuffer buffer = writeContext.getBuffer();
      LocalDateSerializer.writeLocalDate(buffer, obj.toLocalDate());
      LocalTimeSerializer.writeLocalTime(buffer, obj.toLocalTime());
      ZoneOffsetSerializer.writeZoneOffset(buffer, obj.getOffset());
    }

    public OffsetDateTime read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      LocalDate date = LocalDateSerializer.readLocalDate(buffer);
      LocalTime time = LocalTimeSerializer.readLocalTime(buffer);
      ZoneOffset offset = ZoneOffsetSerializer.readZoneOffset(buffer);
      return OffsetDateTime.of(date, time, offset);
    }
  }

  public static void registerDefaultSerializers(TypeResolver resolver) {
    Config config = resolver.getConfig();
    resolver.registerInternalSerializer(Date.class, new DateSerializer(config));
    resolver.registerInternalSerializer(java.sql.Date.class, new SqlDateSerializer(config));
    resolver.registerInternalSerializer(Time.class, new SqlTimeSerializer(config));
    resolver.registerInternalSerializer(Timestamp.class, new TimestampSerializer(config));
    resolver.registerInternalSerializer(LocalDate.class, new LocalDateSerializer(config));
    resolver.registerInternalSerializer(LocalTime.class, new LocalTimeSerializer(config));
    resolver.registerInternalSerializer(LocalDateTime.class, new LocalDateTimeSerializer(config));
    resolver.registerInternalSerializer(Instant.class, new InstantSerializer(config));
    resolver.registerInternalSerializer(Duration.class, new DurationSerializer(config));
    resolver.registerInternalSerializer(ZoneOffset.class, new ZoneOffsetSerializer(config));
    resolver.registerInternalSerializer(ZonedDateTime.class, new ZonedDateTimeSerializer(config));
    resolver.registerInternalSerializer(Year.class, new YearSerializer(config));
    resolver.registerInternalSerializer(YearMonth.class, new YearMonthSerializer(config));
    resolver.registerInternalSerializer(MonthDay.class, new MonthDaySerializer(config));
    resolver.registerInternalSerializer(Period.class, new PeriodSerializer(config));
    resolver.registerInternalSerializer(OffsetTime.class, new OffsetTimeSerializer(config));
    resolver.registerInternalSerializer(OffsetDateTime.class, new OffsetDateTimeSerializer(config));
  }
}

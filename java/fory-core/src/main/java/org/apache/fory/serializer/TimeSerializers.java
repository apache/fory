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
import org.apache.fory.Fory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.util.DateTimeUtils;

/** Serializers for all time related types. */
public class TimeSerializers {
  public abstract static class TimeSerializer<T> extends Serializer<T> {

    public TimeSerializer(Fory fory, Class<T> type) {
      super(fory, type, !fory.getConfig().isTimeRefIgnored(), false);
    }

    public TimeSerializer(Fory fory, Class<T> type, boolean needToWriteRef) {
      super(fory, type, needToWriteRef, false);
    }
  }

  public abstract static class ImmutableTimeSerializer<T> extends ImmutableSerializer<T> {

    public ImmutableTimeSerializer(Fory fory, Class<T> type) {
      super(fory, type, !fory.getConfig().isTimeRefIgnored());
    }

    public ImmutableTimeSerializer(Fory fory, Class<T> type, boolean needToWriteRef) {
      super(fory, type, needToWriteRef);
    }
  }

  public abstract static class BaseDateSerializer<T extends Date> extends TimeSerializer<T> {
    public BaseDateSerializer(Fory fory, Class<T> type) {
      super(fory, type);
    }

    public BaseDateSerializer(Fory fory, Class<T> type, boolean needToWriteRef) {
      super(fory, type, needToWriteRef);
    }

    @Override
    public void write(MemoryBuffer buffer, T value) {
      buffer.writeInt64(value.getTime());
    }

    @Override
    public T read(MemoryBuffer buffer) {
      return newInstance(buffer.readInt64());
    }

    protected abstract T newInstance(long time);
  }

  public static final class DateSerializer extends BaseDateSerializer<Date> {
    public DateSerializer(Fory fory) {
      super(fory, Date.class);
    }

    public DateSerializer(Fory fory, boolean needToWriteRef) {
      super(fory, Date.class, needToWriteRef);
    }

    @Override
    protected Date newInstance(long time) {
      return new Date(time);
    }

    @Override
    public Date copy(Date value) {
      return newInstance(value.getTime());
    }
  }

  public static final class SqlDateSerializer extends BaseDateSerializer<java.sql.Date> {
    public SqlDateSerializer(Fory fory) {
      super(fory, java.sql.Date.class);
    }

    public SqlDateSerializer(Fory fory, boolean needToWriteRef) {
      super(fory, java.sql.Date.class, needToWriteRef);
    }

    @Override
    protected java.sql.Date newInstance(long time) {
      return new java.sql.Date(time);
    }

    @Override
    public java.sql.Date copy(java.sql.Date value) {
      return newInstance(value.getTime());
    }
  }

  public static final class SqlTimeSerializer extends BaseDateSerializer<Time> {

    public SqlTimeSerializer(Fory fory) {
      super(fory, Time.class);
    }

    public SqlTimeSerializer(Fory fory, boolean needToWriteRef) {
      super(fory, Time.class, needToWriteRef);
    }

    @Override
    protected Time newInstance(long time) {
      return new Time(time);
    }

    @Override
    public Time copy(Time value) {
      return newInstance(value.getTime());
    }
  }

  public static final class TimestampSerializer extends TimeSerializer<Timestamp> {

    public TimestampSerializer(Fory fory) {
      // conflict with instant
      super(fory, Timestamp.class);
    }

    public TimestampSerializer(Fory fory, boolean needToWriteRef) {
      super(fory, Timestamp.class, needToWriteRef);
    }

    @Override
    public void write(MemoryBuffer buffer, Timestamp value) {
      if (isJava) {
        long time = value.getTime() - (value.getNanos() / 1_000_000);
        buffer.writeInt64(time);
        buffer.writeInt32(value.getNanos());
      } else {
        Instant instant = value.toInstant();
        buffer.writeInt64(instant.getEpochSecond());
        buffer.writeInt32(instant.getNano());
      }
    }

    @Override
    public Timestamp read(MemoryBuffer buffer) {
      if (isJava) {
        Timestamp t = new Timestamp(buffer.readInt64());
        t.setNanos(buffer.readInt32());
        return t;
      } else {
        long seconds = buffer.readInt64();
        int nanos = buffer.readInt32();
        return Timestamp.from(Instant.ofEpochSecond(seconds, nanos));
      }
    }

    @Override
    public Timestamp copy(Timestamp value) {
      return new Timestamp(value.getTime());
    }
  }

  public static final class LocalDateSerializer extends ImmutableTimeSerializer<LocalDate> {
    public LocalDateSerializer(Fory fory) {
      super(fory, LocalDate.class);
    }

    public LocalDateSerializer(Fory fory, boolean needToWriteRef) {
      super(fory, LocalDate.class, needToWriteRef);
    }

    @Override
    public void write(MemoryBuffer buffer, LocalDate value) {
      if (isJava) {
        writeLocalDate(buffer, value);
      } else {
        // TODO use java encoding to support larger range.
        buffer.writeInt32(DateTimeUtils.localDateToDays(value));
      }
    }

    public static void writeLocalDate(MemoryBuffer buffer, LocalDate value) {
      buffer.writeInt32(value.getYear());
      buffer.writeByte(value.getMonthValue());
      buffer.writeByte(value.getDayOfMonth());
    }

    @Override
    public LocalDate read(MemoryBuffer buffer) {
      if (isJava) {
        return readLocalDate(buffer);
      } else {
        return DateTimeUtils.daysToLocalDate(buffer.readInt32());
      }
    }

    public static LocalDate readLocalDate(MemoryBuffer buffer) {
      int year = buffer.readInt32();
      int month = buffer.readByte();
      int dayOfMonth = buffer.readByte();
      return LocalDate.of(year, month, dayOfMonth);
    }
  }

  public static final class InstantSerializer extends ImmutableTimeSerializer<Instant> {
    public InstantSerializer(Fory fory) {
      super(fory, Instant.class);
    }

    public InstantSerializer(Fory fory, boolean needToWriteRef) {
      super(fory, Instant.class, needToWriteRef);
    }

    @Override
    public void write(MemoryBuffer buffer, Instant value) {
      buffer.writeInt64(value.getEpochSecond());
      buffer.writeInt32(value.getNano());
    }

    @Override
    public Instant read(MemoryBuffer buffer) {
      long seconds = buffer.readInt64();
      int nanos = buffer.readInt32();
      return Instant.ofEpochSecond(seconds, nanos);
    }
  }

  public static class DurationSerializer extends ImmutableTimeSerializer<Duration> {
    public DurationSerializer(Fory fory) {
      super(fory, Duration.class);
    }

    public DurationSerializer(Fory fory, boolean needToWriteRef) {
      super(fory, Duration.class, needToWriteRef);
    }

    @Override
    public void write(MemoryBuffer buffer, Duration value) {
      buffer.writeVarInt64(value.getSeconds());
      buffer.writeInt32(value.getNano());
    }

    @Override
    public Duration read(MemoryBuffer buffer) {
      long seconds = buffer.readVarInt64();
      int nanos = buffer.readInt32();
      return Duration.ofSeconds(seconds, nanos);
    }
  }

  public static class LocalDateTimeSerializer extends ImmutableTimeSerializer<LocalDateTime> {
    public LocalDateTimeSerializer(Fory fory) {
      super(fory, LocalDateTime.class);
    }

    public LocalDateTimeSerializer(Fory fory, boolean needToWriteRef) {
      super(fory, LocalDateTime.class, needToWriteRef);
    }

    @Override
    public void write(MemoryBuffer buffer, LocalDateTime value) {
      LocalDateSerializer.writeLocalDate(buffer, value.toLocalDate());
      LocalTimeSerializer.writeLocalTime(buffer, value.toLocalTime());
    }

    @Override
    public LocalDateTime read(MemoryBuffer buffer) {
      LocalDate date = LocalDateSerializer.readLocalDate(buffer);
      LocalTime time = LocalTimeSerializer.readLocalTime(buffer);
      return LocalDateTime.of(date, time);
    }
  }

  public static class LocalTimeSerializer extends ImmutableTimeSerializer<LocalTime> {
    public LocalTimeSerializer(Fory fory) {
      super(fory, LocalTime.class);
    }

    public LocalTimeSerializer(Fory fory, boolean needToWriteRef) {
      super(fory, LocalTime.class, needToWriteRef);
    }

    @Override
    public void write(MemoryBuffer buffer, LocalTime time) {
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
    public LocalTime read(MemoryBuffer buffer) {
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
    public TimeZoneSerializer(Fory fory, Class<TimeZone> type) {
      super(fory, type);
    }

    public TimeZoneSerializer(Fory fory, boolean needToWriteRef) {
      super(fory, TimeZone.class, needToWriteRef);
    }

    public void write(MemoryBuffer buffer, TimeZone object) {
      fory.writeString(buffer, object.getID());
    }

    public TimeZone read(MemoryBuffer buffer) {
      return TimeZone.getTimeZone(fory.readString(buffer));
    }

    @Override
    public TimeZone copy(TimeZone value) {
      return TimeZone.getTimeZone(value.getID());
    }
  }

  public static final class CalendarSerializer extends TimeSerializer<Calendar> {
    private static final long DEFAULT_GREGORIAN_CUTOVER = -12219292800000L;
    private final TimeZoneSerializer timeZoneSerializer;

    public CalendarSerializer(Fory fory, Class<Calendar> type) {
      super(fory, type);
      timeZoneSerializer = new TimeZoneSerializer(fory, TimeZone.class);
    }

    public CalendarSerializer(Fory fory, Class<Calendar> type, boolean needToWriteRef) {
      super(fory, type, needToWriteRef);
      timeZoneSerializer = new TimeZoneSerializer(fory, TimeZone.class);
    }

    public void write(MemoryBuffer buffer, Calendar object) {
      timeZoneSerializer.write(buffer, object.getTimeZone()); // can't be null
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

    public Calendar read(MemoryBuffer buffer) {
      Calendar result = Calendar.getInstance(timeZoneSerializer.read(buffer));
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
    public Calendar copy(Calendar value) {
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

  public static class ZoneIdSerializer extends ImmutableTimeSerializer<ZoneId> {
    public ZoneIdSerializer(Fory fory, Class<ZoneId> type) {
      super(fory, type);
    }

    public ZoneIdSerializer(Fory fory, Class<ZoneId> type, boolean needToWriteRef) {
      super(fory, type, needToWriteRef);
    }

    @Override
    public void write(MemoryBuffer buffer, ZoneId obj) {
      fory.writeString(buffer, obj.getId());
    }

    @Override
    public ZoneId read(MemoryBuffer buffer) {
      return ZoneId.of(fory.readString(buffer));
    }
  }

  public static class ZoneOffsetSerializer extends ImmutableTimeSerializer<ZoneOffset> {

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

    public ZoneOffsetSerializer(Fory fory) {
      super(fory, ZoneOffset.class);
    }

    public ZoneOffsetSerializer(Fory fory, boolean needToWriteRef) {
      super(fory, ZoneOffset.class, needToWriteRef);
    }

    public void write(MemoryBuffer buffer, ZoneOffset obj) {
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

    public ZoneOffset read(MemoryBuffer buffer) {
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

  public static class ZonedDateTimeSerializer extends ImmutableTimeSerializer<ZonedDateTime> {

    public ZonedDateTimeSerializer(Fory fory) {
      super(fory, ZonedDateTime.class);
    }

    public ZonedDateTimeSerializer(Fory fory, boolean needToWriteRef) {
      super(fory, ZonedDateTime.class, needToWriteRef);
    }

    public void write(MemoryBuffer buffer, ZonedDateTime obj) {
      LocalDateSerializer.writeLocalDate(buffer, obj.toLocalDate());
      LocalTimeSerializer.writeLocalTime(buffer, obj.toLocalTime());
      fory.writeString(buffer, obj.getZone().getId());
    }

    public ZonedDateTime read(MemoryBuffer buffer) {
      LocalDate date = LocalDateSerializer.readLocalDate(buffer);
      LocalTime time = LocalTimeSerializer.readLocalTime(buffer);
      ZoneId zone = ZoneId.of(fory.readString(buffer));
      return ZonedDateTime.of(date, time, zone);
    }
  }

  public static class YearSerializer extends ImmutableTimeSerializer<Year> {
    public YearSerializer(Fory fory) {
      super(fory, Year.class);
    }

    public YearSerializer(Fory fory, boolean needToWriteRef) {
      super(fory, Year.class, needToWriteRef);
    }

    @Override
    public void write(MemoryBuffer buffer, Year obj) {
      buffer.writeInt32(obj.getValue());
    }

    @Override
    public Year read(MemoryBuffer buffer) {
      return Year.of(buffer.readInt32());
    }
  }

  public static class YearMonthSerializer extends ImmutableTimeSerializer<YearMonth> {
    public YearMonthSerializer(Fory fory) {
      super(fory, YearMonth.class);
    }

    public YearMonthSerializer(Fory fory, boolean needToWriteRef) {
      super(fory, YearMonth.class, needToWriteRef);
    }

    @Override
    public void write(MemoryBuffer buffer, YearMonth obj) {
      buffer.writeInt32(obj.getYear());
      buffer.writeByte(obj.getMonthValue());
    }

    @Override
    public YearMonth read(MemoryBuffer buffer) {
      int year = buffer.readInt32();
      byte month = buffer.readByte();
      return YearMonth.of(year, month);
    }
  }

  public static class MonthDaySerializer extends ImmutableTimeSerializer<MonthDay> {
    public MonthDaySerializer(Fory fory) {
      super(fory, MonthDay.class);
    }

    public MonthDaySerializer(Fory fory, boolean needToWriteRef) {
      super(fory, MonthDay.class, needToWriteRef);
    }

    public void write(MemoryBuffer buffer, MonthDay obj) {
      buffer.writeByte(obj.getMonthValue());
      buffer.writeByte(obj.getDayOfMonth());
    }

    public MonthDay read(MemoryBuffer buffer) {
      byte month = buffer.readByte();
      byte day = buffer.readByte();
      return MonthDay.of(month, day);
    }
  }

  public static class PeriodSerializer extends ImmutableTimeSerializer<Period> {
    public PeriodSerializer(Fory fory) {
      super(fory, Period.class);
    }

    public PeriodSerializer(Fory fory, boolean needToWriteRef) {
      super(fory, Period.class, needToWriteRef);
    }

    public void write(MemoryBuffer buffer, Period obj) {
      buffer.writeInt32(obj.getYears());
      buffer.writeInt32(obj.getMonths());
      buffer.writeInt32(obj.getDays());
    }

    public Period read(MemoryBuffer buffer) {
      int years = buffer.readInt32();
      int months = buffer.readInt32();
      int days = buffer.readInt32();
      return Period.of(years, months, days);
    }
  }

  public static class OffsetTimeSerializer extends ImmutableTimeSerializer<OffsetTime> {
    public OffsetTimeSerializer(Fory fory) {
      super(fory, OffsetTime.class);
    }

    public OffsetTimeSerializer(Fory fory, boolean needToWriteRef) {
      super(fory, OffsetTime.class, needToWriteRef);
    }

    public void write(MemoryBuffer buffer, OffsetTime obj) {
      LocalTimeSerializer.writeLocalTime(buffer, obj.toLocalTime());
      ZoneOffsetSerializer.writeZoneOffset(buffer, obj.getOffset());
    }

    public OffsetTime read(MemoryBuffer buffer) {
      LocalTime time = LocalTimeSerializer.readLocalTime(buffer);
      ZoneOffset offset = ZoneOffsetSerializer.readZoneOffset(buffer);
      return OffsetTime.of(time, offset);
    }
  }

  public static class OffsetDateTimeSerializer extends ImmutableTimeSerializer<OffsetDateTime> {
    public OffsetDateTimeSerializer(Fory fory) {
      super(fory, OffsetDateTime.class);
    }

    public OffsetDateTimeSerializer(Fory fory, boolean needToWriteRef) {
      super(fory, OffsetDateTime.class, needToWriteRef);
    }

    public void write(MemoryBuffer buffer, OffsetDateTime obj) {
      LocalDateSerializer.writeLocalDate(buffer, obj.toLocalDate());
      LocalTimeSerializer.writeLocalTime(buffer, obj.toLocalTime());
      ZoneOffsetSerializer.writeZoneOffset(buffer, obj.getOffset());
    }

    public OffsetDateTime read(MemoryBuffer buffer) {
      LocalDate date = LocalDateSerializer.readLocalDate(buffer);
      LocalTime time = LocalTimeSerializer.readLocalTime(buffer);
      ZoneOffset offset = ZoneOffsetSerializer.readZoneOffset(buffer);
      return OffsetDateTime.of(date, time, offset);
    }
  }

  public static void registerDefaultSerializers(Fory fory) {
    TypeResolver resolver = fory.getTypeResolver();
    resolver.registerInternalSerializer(Date.class, new DateSerializer(fory));
    resolver.registerInternalSerializer(java.sql.Date.class, new SqlDateSerializer(fory));
    resolver.registerInternalSerializer(Time.class, new SqlTimeSerializer(fory));
    resolver.registerInternalSerializer(Timestamp.class, new TimestampSerializer(fory));
    resolver.registerInternalSerializer(LocalDate.class, new LocalDateSerializer(fory));
    resolver.registerInternalSerializer(LocalTime.class, new LocalTimeSerializer(fory));
    resolver.registerInternalSerializer(LocalDateTime.class, new LocalDateTimeSerializer(fory));
    resolver.registerInternalSerializer(Instant.class, new InstantSerializer(fory));
    resolver.registerInternalSerializer(Duration.class, new DurationSerializer(fory));
    resolver.registerInternalSerializer(ZoneOffset.class, new ZoneOffsetSerializer(fory));
    resolver.registerInternalSerializer(ZonedDateTime.class, new ZonedDateTimeSerializer(fory));
    resolver.registerInternalSerializer(Year.class, new YearSerializer(fory));
    resolver.registerInternalSerializer(YearMonth.class, new YearMonthSerializer(fory));
    resolver.registerInternalSerializer(MonthDay.class, new MonthDaySerializer(fory));
    resolver.registerInternalSerializer(Period.class, new PeriodSerializer(fory));
    resolver.registerInternalSerializer(OffsetTime.class, new OffsetTimeSerializer(fory));
    resolver.registerInternalSerializer(OffsetDateTime.class, new OffsetDateTimeSerializer(fory));
  }
}

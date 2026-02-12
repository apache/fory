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

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
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
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.Language;
import org.apache.fory.util.DateTimeUtils;
import org.testng.Assert;
import org.testng.annotations.Test;

public class TimeSerializersTest extends ForyTestBase {

  @Test
  public void testBasicTime() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    serDeCheckSerializerAndEqual(fory, new Date(), "Time");
    serDeCheckSerializerAndEqual(fory, new java.sql.Date(100), "Time");
    serDeCheckSerializerAndEqual(fory, new java.sql.Time(200), "Time");
    serDeCheckSerializerAndEqual(fory, new Timestamp(300), "Time");
    serDeCheckSerializerAndEqual(fory, new java.sql.Date(-100), "Time");
    serDeCheckSerializerAndEqual(fory, new java.sql.Time(-200), "Time");
    serDeCheckSerializerAndEqual(fory, new Timestamp(-300), "Time");
    serDeCheckSerializerAndEqual(fory, LocalDate.now(), "Time");
    serDeCheckSerializerAndEqual(fory, LocalTime.now(), "Time");
    serDeCheckSerializerAndEqual(fory, LocalDateTime.now(), "Time");
    serDeCheckSerializerAndEqual(
        fory, DateTimeUtils.truncateInstantToMicros(Instant.now()), "Time");
    serDeCheckSerializerAndEqual(
        fory, Duration.between((Instant.now()), Instant.ofEpochSecond(-1)), "Time");
    serDeCheckSerializerAndEqual(fory, Period.of(100, 11, 20), "Time");
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testBasicTime(Fory fory) {
    copyCheckWithoutSame(fory, new Date());
    copyCheckWithoutSame(fory, new java.sql.Date(100));
    copyCheckWithoutSame(fory, new java.sql.Time(200));
    copyCheckWithoutSame(fory, new Timestamp(300));
    copyCheckWithoutSame(fory, new java.sql.Date(-100));
    copyCheckWithoutSame(fory, new java.sql.Time(-200));
    copyCheckWithoutSame(fory, new Timestamp(-300));
    copyCheckWithoutSame(fory, LocalDate.now());
    copyCheckWithoutSame(fory, LocalTime.now());
    copyCheckWithoutSame(fory, LocalDateTime.now());
    copyCheckWithoutSame(fory, DateTimeUtils.truncateInstantToMicros(Instant.now()));
    copyCheckWithoutSame(fory, Duration.between((Instant.now()), Instant.ofEpochSecond(-1)));
    copyCheckWithoutSame(fory, Period.of(100, 11, 20));
  }

  @Test
  public void testCalendar() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    serDeCheckSerializerAndEqual(fory, GregorianCalendar.getInstance(), "Calendar");
    serDeCheckSerializerAndEqual(fory, Calendar.getInstance(), "Calendar");
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testCalendar(Fory fory) {
    copyCheckWithoutSame(fory, GregorianCalendar.getInstance());
    copyCheckWithoutSame(fory, Calendar.getInstance());
  }

  @Test
  public void testZoneOffset() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    serDeCheckSerializerAndEqual(fory, ZoneOffset.UTC, "ZoneOffsetSerializer");
    serDeCheckSerializerAndEqual(fory, ZoneOffset.ofHoursMinutes(1, 15), "ZoneOffsetSerializer");
    serDeCheckSerializerAndEqual(fory, ZoneOffset.ofHoursMinutes(1, 13), "ZoneOffsetSerializer");
    serDeCheckSerializerAndEqual(fory, ZoneOffset.ofHoursMinutes(-8, -15), "ZoneOffsetSerializer");
    serDeCheckSerializerAndEqual(fory, ZoneOffset.ofHoursMinutes(-8, -13), "ZoneOffsetSerializer");
  }

  @Test
  public void testZone() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    serDeCheckSerializerAndEqual(
        fory,
        ZonedDateTime.of(Year.MIN_VALUE, Month.JANUARY.getValue(), 1, 0, 0, 0, 0, ZoneOffset.UTC),
        "ZonedDateTimeSerializer");
    serDeCheckSerializerAndEqual(
        fory,
        ZonedDateTime.of(
            Year.MIN_VALUE, Month.JANUARY.getValue(), 1, 0, 0, 0, 0, ZoneId.of("Europe/Berlin")),
        "ZonedDateTimeSerializer");
    serDeCheckSerializerAndEqual(
        fory,
        ZonedDateTime.of(
            Year.MAX_VALUE,
            Month.DECEMBER.getValue(),
            31,
            23,
            59,
            59,
            999999999,
            ZoneId.of("Europe/Berlin")),
        "ZonedDateTimeSerializer");
    serDeCheckSerializerAndEqual(fory, Year.of(Year.MIN_VALUE), "YearSerializer");
    serDeCheckSerializerAndEqual(fory, Year.of(Year.MAX_VALUE), "YearSerializer");
    serDeCheckSerializerAndEqual(
        fory, YearMonth.of(Year.MIN_VALUE, Month.APRIL), "YearMonthSerializer");
    serDeCheckSerializerAndEqual(
        fory, YearMonth.of(Year.MAX_VALUE, Month.APRIL), "YearMonthSerializer");
    serDeCheckSerializerAndEqual(fory, MonthDay.of(Month.JANUARY, 11), "MonthDaySerializer");
    serDeCheckSerializerAndEqual(fory, MonthDay.of(Month.DECEMBER, 11), "MonthDaySerializer");
    serDeCheckSerializerAndEqual(
        fory, OffsetTime.of(1, 1, 1, 1, ZoneOffset.UTC), "OffsetTimeSerializer");
    serDeCheckSerializerAndEqual(
        fory, OffsetTime.of(23, 59, 59, 999999999, ZoneOffset.UTC), "OffsetTimeSerializer");
    serDeCheckSerializerAndEqual(
        fory,
        OffsetDateTime.of(Year.MIN_VALUE, Month.JANUARY.getValue(), 1, 0, 0, 0, 0, ZoneOffset.UTC),
        "OffsetDateTimeSerializer");
    serDeCheckSerializerAndEqual(
        fory,
        OffsetDateTime.of(
            Year.MAX_VALUE, Month.DECEMBER.getValue(), 31, 23, 59, 59, 999999999, ZoneOffset.UTC),
        "OffsetDateTimeSerializer");
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testZone(Fory fory) {
    copyCheckWithoutSame(
        fory,
        ZonedDateTime.of(Year.MIN_VALUE, Month.JANUARY.getValue(), 1, 0, 0, 0, 0, ZoneOffset.UTC));
    copyCheckWithoutSame(
        fory,
        ZonedDateTime.of(
            Year.MIN_VALUE, Month.JANUARY.getValue(), 1, 0, 0, 0, 0, ZoneId.of("Europe/Berlin")));
    copyCheckWithoutSame(
        fory,
        ZonedDateTime.of(
            Year.MAX_VALUE,
            Month.DECEMBER.getValue(),
            31,
            23,
            59,
            59,
            999999999,
            ZoneId.of("Europe/Berlin")));
    copyCheckWithoutSame(fory, Year.of(Year.MIN_VALUE));
    copyCheckWithoutSame(fory, Year.of(Year.MAX_VALUE));
    copyCheckWithoutSame(fory, YearMonth.of(Year.MIN_VALUE, Month.APRIL));
    copyCheckWithoutSame(fory, YearMonth.of(Year.MAX_VALUE, Month.APRIL));
    copyCheckWithoutSame(fory, MonthDay.of(Month.JANUARY, 11));
    copyCheckWithoutSame(fory, MonthDay.of(Month.DECEMBER, 11));
    copyCheckWithoutSame(fory, OffsetTime.of(1, 1, 1, 1, ZoneOffset.UTC));
    copyCheckWithoutSame(fory, OffsetTime.of(23, 59, 59, 999999999, ZoneOffset.UTC));
    copyCheckWithoutSame(
        fory,
        OffsetDateTime.of(Year.MIN_VALUE, Month.JANUARY.getValue(), 1, 0, 0, 0, 0, ZoneOffset.UTC));
    copyCheckWithoutSame(
        fory,
        OffsetDateTime.of(
            Year.MAX_VALUE, Month.DECEMBER.getValue(), 31, 23, 59, 59, 999999999, ZoneOffset.UTC));
  }

  @Data
  public static class TimeStruct {
    Date date;
    java.sql.Date sqlDate;
    java.sql.Time time;
    Timestamp timestamp;
    LocalDate localDate;
    LocalTime localTime;
    LocalDateTime localDateTime;
    Instant instant;
    Duration duration;
  }

  @Test
  public void testTimeStruct() {
    TimeStruct struct = new TimeStruct();
    struct.date = new Date();
    struct.sqlDate = new java.sql.Date(100);
    struct.time = new java.sql.Time(200);
    struct.timestamp = new Timestamp(300);
    struct.localDate = LocalDate.now();
    struct.localTime = LocalTime.now();
    struct.localDateTime = LocalDateTime.now();
    struct.instant = DateTimeUtils.truncateInstantToMicros(Instant.now());
    struct.duration = Duration.between(Instant.now(), Instant.ofEpochSecond(-1));
    {
      Fory fory =
          Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
      fory.registerSerializer(
          TimeStruct.class, CodegenSerializer.loadCodegenSerializer(fory, TimeStruct.class));
      serDe(fory, struct);
    }
    {
      Fory fory =
          Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
      fory.registerSerializer(TimeStruct.class, new ObjectSerializer(fory, TimeStruct.class));
      serDe(fory, struct);
    }
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testTimeStruct(Fory fory) {
    TimeStruct struct = new TimeStruct();
    struct.date = new Date();
    struct.sqlDate = new java.sql.Date(100);
    struct.time = new java.sql.Time(200);
    struct.timestamp = new Timestamp(300);
    struct.localDate = LocalDate.now();
    struct.localTime = LocalTime.now();
    struct.localDateTime = LocalDateTime.now();
    struct.instant = DateTimeUtils.truncateInstantToMicros(Instant.now());
    struct.duration = Duration.between(Instant.now(), Instant.ofEpochSecond(-1));
    copyCheck(fory, struct);
  }

  @Data
  public static class TimeStructRef {
    Date date1;
    Date date2;
    java.sql.Date sqlDate1;
    java.sql.Date sqlDate2;
    java.sql.Time time1;
    java.sql.Time time2;
    Instant instant1;
    Instant instant2;
    Duration duration1;
    Duration duration2;
  }

  public static class TimeStructRef1 extends TimeStructRef {}

  public static class TimeStructRef2 extends TimeStructRef {}

  private TimeStructRef createTimeStructRef(TimeStructRef struct) {
    struct.date1 = new Date();
    struct.date2 = struct.date1;
    struct.sqlDate1 = new java.sql.Date(100);
    struct.sqlDate2 = struct.sqlDate1;
    struct.time1 = new java.sql.Time(200);
    struct.time2 = struct.time1;
    struct.instant1 = DateTimeUtils.truncateInstantToMicros(Instant.now());
    struct.instant2 = struct.instant1;
    struct.duration1 = Duration.between(Instant.now(), Instant.ofEpochSecond(-1));
    struct.duration2 = struct.duration1;
    return struct;
  }

  @Test
  public void testTimeStructRef() {
    {
      Fory fory =
          Fory.builder()
              .withLanguage(Language.JAVA)
              .requireClassRegistration(false)
              .withCodegen(true)
              .withRefTracking(true)
              .ignoreTimeRef(true)
              .build();
      fory.registerSerializer(
          TimeStructRef.class, CodegenSerializer.loadCodegenSerializer(fory, TimeStructRef.class));
      TimeStructRef struct = createTimeStructRef(new TimeStructRef());
      TimeStructRef struct1 = (TimeStructRef) serDeCheck(fory, struct);
      Assert.assertNotSame(struct1.date1, struct1.date2);
      Assert.assertNotSame(struct1.sqlDate1, struct1.sqlDate2);
      Assert.assertNotSame(struct1.time1, struct1.time2);
      Assert.assertNotSame(struct1.instant1, struct1.instant2);
      Assert.assertNotSame(struct1.duration1, struct1.duration2);
    }
    {
      Fory fory =
          Fory.builder()
              .withLanguage(Language.JAVA)
              .requireClassRegistration(false)
              .withRefTracking(true)
              .ignoreTimeRef(false)
              .build();
      fory.registerSerializer(
          TimeStruct.class, CodegenSerializer.loadCodegenSerializer(fory, TimeStruct.class));
      fory.registerSerializer(
          TimeStructRef1.class,
          CodegenSerializer.loadCodegenSerializer(fory, TimeStructRef1.class));
      TimeStructRef1 struct = (TimeStructRef1) createTimeStructRef(new TimeStructRef1());
      TimeStructRef1 struct1 = (TimeStructRef1) serDeCheck(fory, struct);
      Assert.assertSame(struct1.date1, struct1.date2);
      Assert.assertSame(struct1.sqlDate1, struct1.sqlDate2);
      Assert.assertSame(struct1.time1, struct1.time2);
      Assert.assertSame(struct1.instant1, struct1.instant2);
      Assert.assertSame(struct1.duration1, struct1.duration2);
    }
    {
      Fory fory =
          Fory.builder()
              .withLanguage(Language.JAVA)
              .requireClassRegistration(false)
              .withCodegen(true)
              .withRefTracking(true)
              .ignoreTimeRef(true)
              .build();
      fory.registerSerializer(Date.class, new TimeSerializers.DateSerializer(fory, true));
      fory.registerSerializer(
          java.sql.Date.class, new TimeSerializers.SqlDateSerializer(fory, true));
      fory.registerSerializer(Instant.class, new TimeSerializers.InstantSerializer(fory, true));
      {
        TimeStructRef struct = createTimeStructRef(new TimeStructRef());
        TimeStructRef struct2 = (TimeStructRef) serDeCheck(fory, struct);

        // Despite a TimeStructRef codegen serializer having already been generated, the change to
        // enable ref tracking should now take effect due to improvements to the codegen caching
        Assert.assertSame(struct2.date1, struct2.date2);
      }
      {
        TimeStructRef struct = createTimeStructRef(new TimeStructRef2());
        TimeStructRef struct2 = (TimeStructRef) serDeCheck(fory, struct);
        Assert.assertSame(struct2.date1, struct2.date2);
        Assert.assertSame(struct2.sqlDate1, struct2.sqlDate2);
        Assert.assertSame(struct2.instant1, struct2.instant2);
        Assert.assertNotSame(struct2.time1, struct2.time2);
        Assert.assertNotSame(struct2.duration1, struct2.duration2);
      }
    }
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testTimeStructRef(Fory fory) {
    {
      fory.registerSerializer(
          TimeStructRef.class, CodegenSerializer.loadCodegenSerializer(fory, TimeStructRef.class));
      TimeStructRef struct = createTimeStructRef(new TimeStructRef());
      TimeStructRef struct1 = fory.copy(struct);
      Assert.assertSame(struct1.date1, struct1.date2);
      Assert.assertSame(struct1.sqlDate1, struct1.sqlDate2);
      Assert.assertSame(struct1.time1, struct1.time2);
      Assert.assertSame(struct1.instant1, struct1.instant2);
      Assert.assertSame(struct1.duration1, struct1.duration2);
    }
    {
      fory.registerSerializer(
          TimeStruct.class, CodegenSerializer.loadCodegenSerializer(fory, TimeStruct.class));
      fory.registerSerializer(
          TimeStructRef1.class,
          CodegenSerializer.loadCodegenSerializer(fory, TimeStructRef1.class));
      TimeStructRef1 struct = (TimeStructRef1) createTimeStructRef(new TimeStructRef1());
      TimeStructRef1 struct1 = fory.copy(struct);
      Assert.assertSame(struct1.date1, struct1.date2);
      Assert.assertSame(struct1.sqlDate1, struct1.sqlDate2);
      Assert.assertSame(struct1.time1, struct1.time2);
      Assert.assertSame(struct1.instant1, struct1.instant2);
      Assert.assertSame(struct1.duration1, struct1.duration2);
    }
    {
      fory.registerSerializer(Date.class, new TimeSerializers.DateSerializer(fory, true));
      fory.registerSerializer(
          java.sql.Date.class, new TimeSerializers.SqlDateSerializer(fory, true));
      fory.registerSerializer(Instant.class, new TimeSerializers.InstantSerializer(fory, true));
      {
        TimeStructRef struct = createTimeStructRef(new TimeStructRef());
        TimeStructRef struct2 = fory.copy(struct);
        // TimeStructRef serializer already generated, enable ref tracking doesn't take effect.
        Assert.assertSame(struct2.date1, struct2.date2);
      }
      {
        TimeStructRef struct = createTimeStructRef(new TimeStructRef2());
        TimeStructRef struct2 = fory.copy(struct);
        Assert.assertSame(struct2.date1, struct2.date2);
        Assert.assertSame(struct2.sqlDate1, struct2.sqlDate2);
        Assert.assertSame(struct2.instant1, struct2.instant2);
        Assert.assertSame(struct2.time1, struct2.time2);
        Assert.assertSame(struct2.duration1, struct2.duration2);
      }
    }
  }
}

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

package org.apache.fory.json.writer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Objects;
import java.util.UUID;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.JsonConfig;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;

/**
 * Representation-neutral JSON emission contract and writer operation state.
 *
 * <p>The base owner retains the resolver used by dynamic codecs and configured and current
 * container depth. Concrete writers own output storage and all direct representation-specific
 * scalar, string, field-token, temporal, and arbitrary-precision output. In particular, this base
 * class does not retain big-number scratch state or emit digits through virtual callbacks.
 *
 * <p>Writers are mutable and confined to one borrowed {@code ForyJson} state. A failed root write
 * is discarded and {@link #reset()} restores depth before reuse; nested codecs intentionally do not
 * add {@code try/finally} solely to decrement depth on an operation that already failed. Methods
 * accepting preformatted number text require a valid ASCII JSON number and copy it without
 * reparsing.
 */
public abstract class JsonWriter {
  private static final long MIN_ISO_INSTANT_SECOND = -31_557_014_167_219_200L;
  private static final long MAX_ISO_INSTANT_SECOND = 31_556_889_864_403_199L;
  private final JsonTypeResolver typeResolver;
  private final int maxDepth;
  private int depth;

  JsonWriter(JsonConfig config, JsonTypeResolver typeResolver) {
    this.typeResolver = Objects.requireNonNull(typeResolver, "typeResolver");
    maxDepth = config.maxDepth();
  }

  /**
   * Returns the resolver owned by this writer for custom codecs that resolve dynamic child types.
   */
  public final JsonTypeResolver typeResolver() {
    return typeResolver;
  }

  public void reset() {
    depth = 0;
  }

  @Internal
  public final int getDepth() {
    return depth;
  }

  // Generated codecs update depth only after they have emitted the complete common object-end
  // path. Keep the state owned here rather than exposing the field itself.
  @Internal
  public final void setDepth(int depth) {
    this.depth = depth;
  }

  protected final void enterDepth() {
    int nextDepth = depth + 1;
    if (nextDepth > maxDepth) {
      throwDepthExceeded(maxDepth);
    }
    depth = nextDepth;
  }

  protected final void exitDepth() {
    depth--;
  }

  private static void throwDepthExceeded(int maxDepth) {
    throw new ForyJsonException("JSON max depth " + maxDepth + " exceeded");
  }

  public abstract void writeNull();

  public abstract void writeBoolean(boolean value);

  public abstract void writeInt(int value);

  public abstract void writeLong(long value);

  /** Writes a signed 64-bit value as a quoted decimal JSON string. */
  public abstract void writeLongAsString(long value);

  /** Writes raw unsigned 32-bit bits as a decimal JSON number. */
  public void writeUnsignedInt(int value) {
    writeLong(Integer.toUnsignedLong(value));
  }

  /** Writes raw unsigned 64-bit bits as a decimal JSON number. */
  public abstract void writeUnsignedLong(long value);

  /** Writes raw unsigned 64-bit bits as a quoted decimal JSON string. */
  public abstract void writeUnsignedLongAsString(long value);

  public abstract void writeFloat(float value);

  public abstract void writeDouble(double value);

  public abstract void writeNumber(String value);

  public abstract void writeChar(char value);

  public abstract void writeString(String value);

  public void writeString(CharSequence value) {
    writeString(value.toString());
  }

  // Concrete writers own compact BigDecimal formatting and canonical arbitrary-precision text
  // copying. Bounded coefficients may use primitive digit arithmetic; larger magnitudes retain
  // the JDK's recursive conversion rather than a repeated allocating quotient/remainder loop.
  public abstract void writeBigInteger(BigInteger value);

  public abstract void writeBigDecimal(BigDecimal value);

  protected static void throwUnsupportedBigNumber(Class<?> type) {
    throw new ForyJsonException(
        "Unsupported JSON big-number subtype " + type + "; register an explicit codec");
  }

  public final void writeUuid(UUID value) {
    writeUuid(value.getMostSignificantBits(), value.getLeastSignificantBits());
  }

  /** Writes one canonical quoted UUID from its primitive 128-bit value. */
  public abstract void writeUuid(long high, long low);

  /** Writes one canonical quoted ISO-8601 instant from epoch seconds and nanoseconds. */
  public abstract void writeIsoInstant(long epochSecond, int nano);

  /** Converts a validated ISO instant epoch day into packed year, month, and day components. */
  protected static long isoDate(long epochSecond, int nano) {
    if (nano < 0
        || nano >= 1_000_000_000
        || epochSecond < MIN_ISO_INSTANT_SECOND
        || epochSecond > MAX_ISO_INSTANT_SECOND) {
      throw invalidIsoInstant(epochSecond, nano);
    }
    // Neri and Schneider, Proposition 6.3: https://arxiv.org/abs/2102.06959.
    // Floor division extends the March-based century decomposition to negative years while
    // keeping its remainder in [0, 146097). Valid Instant bounds keep all long products in range.
    long quarterDay = 4 * (Math.floorDiv(epochSecond, 86_400) + 719_468) + 3;
    int century = (int) Math.floorDiv(quarterDay, 146_097);
    int remainder = (int) (quarterDay - century * 146_097L);
    long yearProduct = 2_939_745L * (remainder | 3);
    int year = century * 100 + (int) (yearProduct >>> 32);
    int marchDay = (int) ((yearProduct & 0xffff_ffffL) / 2_939_745) >>> 2;
    int monthDay = 2141 * marchDay + 197913;
    int month = monthDay >>> 16;
    int day = (monthDay & 0xffff) / 2141 + 1;
    if (marchDay >= 306) {
      year++;
      month -= 12;
    }
    return ((long) year << 32) | ((long) month << 16) | day;
  }

  private static ForyJsonException invalidIsoInstant(long epochSecond, int nano) {
    return new ForyJsonException(
        "Invalid ISO instant components: epochSecond=" + epochSecond + ", nano=" + nano);
  }

  public void writeLocalDate(LocalDate value) {
    writeString(value.toString());
  }

  public void writeOffsetDateTime(OffsetDateTime value) {
    writeString(value.toString());
  }

  /** Writes a quoted ISO local time, including seconds and the shortest exact fraction. */
  @Internal
  public void writeLocalTime(LocalTime value) {
    writeTemporal(value, DateTimeFormatter.ISO_LOCAL_TIME);
  }

  /** Writes a quoted ISO local date-time. */
  @Internal
  public void writeLocalDateTime(LocalDateTime value) {
    writeTemporal(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
  }

  /** Writes a quoted ISO offset time. */
  @Internal
  public void writeOffsetTime(OffsetTime value) {
    writeTemporal(value, DateTimeFormatter.ISO_OFFSET_TIME);
  }

  /** Writes a quoted ISO zoned date-time, retaining a region ID when present. */
  @Internal
  public void writeZonedDateTime(ZonedDateTime value) {
    writeTemporal(value, DateTimeFormatter.ISO_ZONED_DATE_TIME);
  }

  /** Writes a quoted ISO year and month, with a sign for extended positive years. */
  @Internal
  public void writeYearMonth(YearMonth value) {
    writeTemporal(value, TemporalFormats.YEAR_MONTH);
  }

  /** Writes a quoted ISO month and day. */
  @Internal
  public void writeMonthDay(MonthDay value) {
    writeTemporal(value, TemporalFormats.MONTH_DAY);
  }

  private static final class TemporalFormats {
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("uuuu-MM");
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("--MM-dd");
  }

  public void writeTemporal(TemporalAccessor value, DateTimeFormatter formatter) {
    writeString(formatter.format(value));
  }

  public void writeDuration(Duration value) {
    writeString(value.toString());
  }

  /**
   * Writes a canonical quoted ISO duration from magnitude components.
   *
   * <p>Finite components are non-negative; minutes and seconds are below 60 and nanoseconds are
   * below one billion. Fractions use three, six, or nine digits, and a zero minute component is
   * retained between nonzero hours and seconds. Infinite values use {@code PT9999999999999H} and
   * require zero finite components. Negative zero is invalid.
   */
  public abstract void writeIsoDuration(
      boolean infinite, boolean negative, long hours, int minutes, int seconds, int nanos);

  /** Validates the primitive ISO-duration tuple before a concrete writer emits any bytes. */
  protected static void checkIsoDuration(
      boolean infinite, boolean negative, long hours, int minutes, int seconds, int nanos) {
    boolean zero = (hours | minutes | seconds | nanos) == 0;
    if (hours < 0
        || minutes < 0
        || minutes >= 60
        || seconds < 0
        || seconds >= 60
        || nanos < 0
        || nanos >= 1_000_000_000
        || infinite && !zero
        || !infinite && negative && zero) {
      throw invalidIsoDuration(infinite, negative, hours, minutes, seconds, nanos);
    }
  }

  /** Returns whether {@link Duration#toString()} matches the primitive ISO-duration spelling. */
  protected static boolean matchesIsoDurationShape(
      long hours, int minutes, int seconds, int nanos) {
    if (hours != 0 && minutes == 0 && (seconds != 0 || nanos != 0)) {
      return false;
    }
    if (nanos == 0) {
      return true;
    }
    if (nanos % 1_000_000 == 0) {
      return nanos / 1_000_000 % 10 != 0;
    }
    if (nanos % 1000 == 0) {
      return nanos / 1000 % 10 != 0;
    }
    return nanos % 10 != 0;
  }

  private static ForyJsonException invalidIsoDuration(
      boolean infinite, boolean negative, long hours, int minutes, int seconds, int nanos) {
    return new ForyJsonException(
        "Invalid ISO duration components: infinite="
            + infinite
            + ", negative="
            + negative
            + ", hours="
            + hours
            + ", minutes="
            + minutes
            + ", seconds="
            + seconds
            + ", nanos="
            + nanos);
  }

  public void writePeriod(Period value) {
    writeString(value.toString());
  }

  public void writeYear(Year value) {
    writeString(value.toString());
  }

  public abstract void writeFieldName(String name);

  public abstract void writeFieldName(JsonFieldInfo field);

  public abstract void writeIntFieldName(int value);

  public abstract void writeLongFieldName(long value);

  /** Writes raw unsigned 32-bit bits as a decimal JSON member name. */
  public void writeUnsignedIntFieldName(int value) {
    writeLongFieldName(Integer.toUnsignedLong(value));
  }

  /** Writes raw unsigned 64-bit bits as a decimal JSON member name. */
  public abstract void writeUnsignedLongFieldName(long value);

  public abstract void writeObjectStart();

  public abstract void writeObjectEnd();

  public abstract void writeArrayStart();

  public abstract void writeArrayEnd();

  public abstract void writeComma(int index);
}

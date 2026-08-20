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
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.Year;
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

  /** Writes raw unsigned 32-bit bits as a decimal JSON number. */
  public void writeUnsignedInt(int value) {
    writeLong(Integer.toUnsignedLong(value));
  }

  /** Writes raw unsigned 64-bit bits as a decimal JSON number. */
  public abstract void writeUnsignedLong(long value);

  public abstract void writeFloat(float value);

  public abstract void writeDouble(double value);

  public abstract void writeNumber(String value);

  public abstract void writeChar(char value);

  public abstract void writeString(String value);

  public void writeString(CharSequence value) {
    writeString(value.toString());
  }

  // Concrete writers own compact BigDecimal formatting and canonical arbitrary-precision text
  // copying. BigInteger values outside long range use the JDK conversion, whose recursive large
  // magnitude algorithm avoids the repeated quotient/remainder allocation of a local chunk loop.
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
    long zeroDay = Math.floorDiv(epochSecond, 86_400) + 719_528 - 60;
    long adjust = 0;
    if (zeroDay < 0) {
      long adjustCycles = (zeroDay + 1) / 146_097 - 1;
      adjust = adjustCycles * 400;
      zeroDay += -adjustCycles * 146_097;
    }
    long year = (400 * zeroDay + 591) / 146_097;
    long dayOfYear = zeroDay - (365 * year + year / 4 - year / 100 + year / 400);
    if (dayOfYear < 0) {
      year--;
      dayOfYear = zeroDay - (365 * year + year / 4 - year / 100 + year / 400);
    }
    year += adjust;
    int marchDay = (int) dayOfYear;
    int marchMonth = (marchDay * 5 + 2) / 153;
    int month = (marchMonth + 2) % 12 + 1;
    int day = marchDay - (marchMonth * 306 + 5) / 10 + 1;
    year += marchMonth / 10;
    return (year << 32) | ((long) month << 16) | day;
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

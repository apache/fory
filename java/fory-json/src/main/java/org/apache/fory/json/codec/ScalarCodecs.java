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

import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import java.time.chrono.JapaneseChronology;
import java.time.chrono.JapaneseDate;
import java.time.chrono.MinguoChronology;
import java.time.chrono.MinguoDate;
import java.time.chrono.ThaiBuddhistChronology;
import java.time.chrono.ThaiBuddhistDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Calendar;
import java.util.Currency;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.regex.Pattern;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.meta.JsonAsciiToken;
import org.apache.fory.json.meta.JsonFieldNameHash;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.type.BFloat16;
import org.apache.fory.type.Float16;

/**
 * Exact semantic codecs for scalar, temporal, atomic, optional, buffer, and enum JSON mappings.
 *
 * <p>Primitive and boxed codecs are distinct where JSON {@code null} semantics differ. Exact
 * built-in instances also identify direct array, collection, map, field, and generated-code paths;
 * replacing one with a custom codec intentionally disables those built-in shortcuts. The dynamic
 * {@code Object} codec maps arrays and objects to {@link org.apache.fory.json.JsonArray} and {@link
 * org.apache.fory.json.JsonObject}, and dispatches writes through the active writer's resolver.
 *
 * <p>Arbitrary-precision resource guards remain owned by reader construction of {@link BigInteger}
 * and {@link BigDecimal}. Primitive numeric readers retain their direct overflow and IEEE-754
 * parsing behavior. Java time codecs use their documented string grammar and do not accept numeric
 * decimal timestamps.
 */
public final class ScalarCodecs {
  private static final DateTimeFormatter YEAR_MONTH_FORMATTER =
      DateTimeFormatter.ofPattern("uuuu-MM");
  private static final DateTimeFormatter MONTH_DAY_FORMATTER =
      DateTimeFormatter.ofPattern("--MM-dd");
  private static final DateTimeFormatter HIJRAH_DATE_FORMATTER =
      DateTimeFormatter.ISO_LOCAL_DATE.withChronology(HijrahChronology.INSTANCE);
  private static final DateTimeFormatter JAPANESE_DATE_FORMATTER =
      DateTimeFormatter.ISO_LOCAL_DATE.withChronology(JapaneseChronology.INSTANCE);
  private static final DateTimeFormatter MINGUO_DATE_FORMATTER =
      DateTimeFormatter.ISO_LOCAL_DATE.withChronology(MinguoChronology.INSTANCE);
  private static final DateTimeFormatter THAI_BUDDHIST_DATE_FORMATTER =
      DateTimeFormatter.ISO_LOCAL_DATE.withChronology(ThaiBuddhistChronology.INSTANCE);

  private ScalarCodecs() {}

  public static final class NaturalCodec implements JsonCodec<Object> {
    public static final NaturalCodec INSTANCE = new NaturalCodec();

    private NaturalCodec() {}

    @Override
    public void writeString(StringJsonWriter writer, Object value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      JsonTypeInfo typeInfo = writer.typeResolver().getRuntimeTypeInfo(value.getClass());
      typeInfo.stringWriter().writeString(writer, value);
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Object value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      JsonTypeInfo typeInfo = writer.typeResolver().getRuntimeTypeInfo(value.getClass());
      typeInfo.utf8Writer().writeUtf8(writer, value);
    }

    @Override
    public Object readLatin1(Latin1JsonReader reader) {
      char token = reader.peekToken();
      if (token == '"') {
        return reader.readString();
      } else if (token == '{') {
        return MapCodec.readUntyped(reader);
      } else if (token == '[') {
        return CollectionCodec.readUntyped(reader);
      } else if (token == 't' || token == 'f') {
        return reader.readBoolean();
      } else if (token == 'n') {
        reader.readNull();
        return null;
      }
      return reader.readNumber();
    }

    @Override
    public Object readUtf16(Utf16JsonReader reader) {
      char token = reader.peekToken();
      if (token == '"') {
        return reader.readString();
      } else if (token == '{') {
        return MapCodec.readUntyped(reader);
      } else if (token == '[') {
        return CollectionCodec.readUntyped(reader);
      } else if (token == 't' || token == 'f') {
        return reader.readBoolean();
      } else if (token == 'n') {
        reader.readNull();
        return null;
      }
      return reader.readNumber();
    }

    @Override
    public Object readUtf8(Utf8JsonReader reader) {
      char token = reader.peekToken();
      if (token == '"') {
        return reader.readString();
      } else if (token == '{') {
        return MapCodec.readUntyped(reader);
      } else if (token == '[') {
        return CollectionCodec.readUntyped(reader);
      } else if (token == 't' || token == 'f') {
        return reader.readBoolean();
      } else if (token == 'n') {
        reader.readNull();
        return null;
      }
      return reader.readNumber();
    }
  }

  public static final class StringCodec implements JsonCodec<String> {
    public static final StringCodec INSTANCE = new StringCodec();

    private StringCodec() {}

    @Override
    public void writeString(StringJsonWriter writer, String value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, String value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value);
      }
    }

    @Override
    public String readLatin1(Latin1JsonReader reader) {
      return reader.readNullableString();
    }

    @Override
    public String readUtf16(Utf16JsonReader reader) {
      return reader.readNullableString();
    }

    @Override
    public String readUtf8(Utf8JsonReader reader) {
      return reader.readNullableString();
    }
  }

  public static final class CharSequenceCodec implements JsonCodec<CharSequence> {
    public static final CharSequenceCodec INSTANCE = new CharSequenceCodec();

    private CharSequenceCodec() {}

    @Override
    public void writeString(StringJsonWriter writer, CharSequence value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, CharSequence value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value);
      }
    }

    @Override
    public CharSequence readLatin1(Latin1JsonReader reader) {
      return reader.readNullableString();
    }

    @Override
    public CharSequence readUtf16(Utf16JsonReader reader) {
      return reader.readNullableString();
    }

    @Override
    public CharSequence readUtf8(Utf8JsonReader reader) {
      return reader.readNullableString();
    }
  }

  public static final class VoidCodec implements JsonCodec<Void> {
    public static final VoidCodec INSTANCE = new VoidCodec();

    @Override
    public void writeString(StringJsonWriter writer, Void value) {
      writer.writeNull();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Void value) {
      writer.writeNull();
    }

    @Override
    public Void readLatin1(Latin1JsonReader reader) {
      reader.readNull();
      return null;
    }

    @Override
    public Void readUtf16(Utf16JsonReader reader) {
      reader.readNull();
      return null;
    }

    @Override
    public Void readUtf8(Utf8JsonReader reader) {
      reader.readNull();
      return null;
    }
  }

  public static final class BooleanCodec implements JsonCodec<Boolean> {
    public static final BooleanCodec PRIMITIVE = new BooleanCodec(true);
    public static final BooleanCodec BOXED = new BooleanCodec(false);
    private final boolean primitive;

    private BooleanCodec(boolean primitive) {
      this.primitive = primitive;
    }

    @Override
    public void writeString(StringJsonWriter writer, Boolean value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeBoolean(value);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Boolean value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeBoolean(value);
      }
    }

    @Override
    public Boolean readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return primitive ? primitiveNull(boolean.class) : null;
      }
      return reader.readBoolean();
    }

    @Override
    public Boolean readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return primitive ? primitiveNull(boolean.class) : null;
      }
      return reader.readBoolean();
    }

    @Override
    public Boolean readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return primitive ? primitiveNull(boolean.class) : null;
      }
      return reader.readBoolean();
    }
  }

  public static final class IntCodec implements JsonCodec<Integer> {
    public static final IntCodec PRIMITIVE = new IntCodec(true);
    public static final IntCodec BOXED = new IntCodec(false);
    private final boolean primitive;

    private IntCodec(boolean primitive) {
      this.primitive = primitive;
    }

    @Override
    public void writeString(StringJsonWriter writer, Integer value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeInt(value);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Integer value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeInt(value);
      }
    }

    @Override
    public Integer readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return primitive ? primitiveNull(int.class) : null;
      }
      return reader.readInt();
    }

    @Override
    public Integer readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return primitive ? primitiveNull(int.class) : null;
      }
      return reader.readInt();
    }

    @Override
    public Integer readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return primitive ? primitiveNull(int.class) : null;
      }
      return reader.readInt();
    }
  }

  public static final class LongCodec implements JsonCodec<Long> {
    public static final LongCodec PRIMITIVE = new LongCodec(true);
    public static final LongCodec BOXED = new LongCodec(false);
    private final boolean primitive;

    private LongCodec(boolean primitive) {
      this.primitive = primitive;
    }

    @Override
    public void writeString(StringJsonWriter writer, Long value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeLong(value);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Long value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeLong(value);
      }
    }

    @Override
    public Long readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return primitive ? primitiveNull(long.class) : null;
      }
      return reader.readLong();
    }

    @Override
    public Long readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return primitive ? primitiveNull(long.class) : null;
      }
      return reader.readLong();
    }

    @Override
    public Long readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return primitive ? primitiveNull(long.class) : null;
      }
      return reader.readLong();
    }
  }

  public static final class ShortCodec implements JsonCodec<Short> {
    public static final ShortCodec PRIMITIVE = new ShortCodec(true);
    public static final ShortCodec BOXED = new ShortCodec(false);
    private final boolean primitive;

    private ShortCodec(boolean primitive) {
      this.primitive = primitive;
    }

    @Override
    public void writeString(StringJsonWriter writer, Short value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeInt(value.intValue());
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Short value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeInt(value.intValue());
      }
    }

    @Override
    public Short readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return primitive ? primitiveNull(short.class) : null;
      }
      return checkedShort(reader.readInt());
    }

    @Override
    public Short readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return primitive ? primitiveNull(short.class) : null;
      }
      return checkedShort(reader.readInt());
    }

    @Override
    public Short readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return primitive ? primitiveNull(short.class) : null;
      }
      return checkedShort(reader.readInt());
    }

    private static short checkedShort(int value) {
      if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
        throw new ForyJsonException("Short overflow");
      }
      return (short) value;
    }
  }

  public static final class ByteCodec implements JsonCodec<Byte> {
    public static final ByteCodec PRIMITIVE = new ByteCodec(true);
    public static final ByteCodec BOXED = new ByteCodec(false);
    private final boolean primitive;

    private ByteCodec(boolean primitive) {
      this.primitive = primitive;
    }

    @Override
    public void writeString(StringJsonWriter writer, Byte value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeInt(value.intValue());
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Byte value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeInt(value.intValue());
      }
    }

    @Override
    public Byte readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return primitive ? primitiveNull(byte.class) : null;
      }
      return checkedByte(reader.readInt());
    }

    @Override
    public Byte readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return primitive ? primitiveNull(byte.class) : null;
      }
      return checkedByte(reader.readInt());
    }

    @Override
    public Byte readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return primitive ? primitiveNull(byte.class) : null;
      }
      return checkedByte(reader.readInt());
    }

    private static byte checkedByte(int value) {
      if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
        throw new ForyJsonException("Byte overflow");
      }
      return (byte) value;
    }
  }

  public static final class FloatCodec implements JsonCodec<Float> {
    public static final FloatCodec PRIMITIVE = new FloatCodec(true);
    public static final FloatCodec BOXED = new FloatCodec(false);
    private final boolean primitive;

    private FloatCodec(boolean primitive) {
      this.primitive = primitive;
    }

    @Override
    public void writeString(StringJsonWriter writer, Float value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeFloat(value);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Float value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeFloat(value);
      }
    }

    @Override
    public Float readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return primitive ? primitiveNull(float.class) : null;
      }
      return reader.readFloat();
    }

    @Override
    public Float readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return primitive ? primitiveNull(float.class) : null;
      }
      return reader.readFloat();
    }

    @Override
    public Float readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return primitive ? primitiveNull(float.class) : null;
      }
      return reader.readFloat();
    }
  }

  public static final class DoubleCodec implements JsonCodec<Double> {
    public static final DoubleCodec PRIMITIVE = new DoubleCodec(true);
    public static final DoubleCodec BOXED = new DoubleCodec(false);
    private final boolean primitive;

    private DoubleCodec(boolean primitive) {
      this.primitive = primitive;
    }

    @Override
    public void writeString(StringJsonWriter writer, Double value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeDouble(value);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Double value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeDouble(value);
      }
    }

    @Override
    public Double readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return primitive ? primitiveNull(double.class) : null;
      }
      return reader.readDouble();
    }

    @Override
    public Double readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return primitive ? primitiveNull(double.class) : null;
      }
      return reader.readDouble();
    }

    @Override
    public Double readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return primitive ? primitiveNull(double.class) : null;
      }
      return reader.readDouble();
    }
  }

  public static final class CharCodec implements JsonCodec<Character> {
    public static final CharCodec PRIMITIVE = new CharCodec(true);
    public static final CharCodec BOXED = new CharCodec(false);
    private final boolean primitive;

    private CharCodec(boolean primitive) {
      this.primitive = primitive;
    }

    @Override
    public void writeString(StringJsonWriter writer, Character value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeChar(value);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Character value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeChar(value);
      }
    }

    @Override
    public Character readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return primitive ? primitiveNull(char.class) : null;
      }
      return reader.readChar();
    }

    @Override
    public Character readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return primitive ? primitiveNull(char.class) : null;
      }
      return reader.readChar();
    }

    @Override
    public Character readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return primitive ? primitiveNull(char.class) : null;
      }
      return reader.readChar();
    }
  }

  private static <T> T primitiveNull(Class<?> type) {
    throw new ForyJsonException("Cannot read null into primitive " + type);
  }

  private static ForyJsonException invalidString(
      Class<?> type, String value, RuntimeException cause) {
    if (cause instanceof ForyJsonException) {
      return (ForyJsonException) cause;
    }
    return new ForyJsonException("Invalid " + type.getName() + " JSON string: " + value, cause);
  }

  public static final class NumberCodec implements JsonCodec<Number> {
    public static final NumberCodec INSTANCE = new NumberCodec();

    private NumberCodec() {}

    @Override
    public void writeString(StringJsonWriter writer, Number value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writeNumberValue(writer, value);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Number value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writeNumberValue(writer, value);
      }
    }

    @Override
    public Number readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.peekToken() == '"' ? reader.readDouble() : reader.readNumber();
    }

    @Override
    public Number readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.peekToken() == '"' ? reader.readDouble() : reader.readNumber();
    }

    @Override
    public Number readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.peekToken() == '"' ? reader.readDouble() : reader.readNumber();
    }

    private static void writeNumberValue(StringJsonWriter writer, Number value) {
      Class<?> type = value.getClass();
      if (type == Integer.class) {
        writer.writeInt(value.intValue());
      } else if (type == Long.class) {
        writer.writeLong(value.longValue());
      } else if (type == Short.class || type == Byte.class) {
        writer.writeInt(value.intValue());
      } else if (type == Float.class) {
        writer.writeFloat(value.floatValue());
      } else if (type == Double.class) {
        writer.writeDouble(value.doubleValue());
      } else if (type == BigInteger.class) {
        writer.writeBigInteger((BigInteger) value);
      } else if (type == BigDecimal.class) {
        writer.writeBigDecimal((BigDecimal) value);
      } else if (type == AtomicInteger.class) {
        writer.writeInt(((AtomicInteger) value).get());
      } else if (type == AtomicLong.class) {
        writer.writeLong(((AtomicLong) value).get());
      } else if (type == Float16.class || type == BFloat16.class) {
        writer.writeFloat(value.floatValue());
      } else {
        throw new ForyJsonException("Unsupported JSON number type " + type);
      }
    }

    private static void writeNumberValue(Utf8JsonWriter writer, Number value) {
      Class<?> type = value.getClass();
      if (type == Integer.class) {
        writer.writeInt(value.intValue());
      } else if (type == Long.class) {
        writer.writeLong(value.longValue());
      } else if (type == Short.class || type == Byte.class) {
        writer.writeInt(value.intValue());
      } else if (type == Float.class) {
        writer.writeFloat(value.floatValue());
      } else if (type == Double.class) {
        writer.writeDouble(value.doubleValue());
      } else if (type == BigInteger.class) {
        writer.writeBigInteger((BigInteger) value);
      } else if (type == BigDecimal.class) {
        writer.writeBigDecimal((BigDecimal) value);
      } else if (type == AtomicInteger.class) {
        writer.writeInt(((AtomicInteger) value).get());
      } else if (type == AtomicLong.class) {
        writer.writeLong(((AtomicLong) value).get());
      } else if (type == Float16.class || type == BFloat16.class) {
        writer.writeFloat(value.floatValue());
      } else {
        throw new ForyJsonException("Unsupported JSON number type " + type);
      }
    }
  }

  public static final class BigIntegerCodec implements JsonCodec<BigInteger> {
    public static final BigIntegerCodec INSTANCE = new BigIntegerCodec();

    @Override
    public void writeString(StringJsonWriter writer, BigInteger value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeBigInteger(value);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, BigInteger value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeBigInteger(value);
      }
    }

    @Override
    public BigInteger readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readBigInteger();
    }

    @Override
    public BigInteger readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readBigInteger();
    }

    @Override
    public BigInteger readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readBigInteger();
    }
  }

  public static final class BigDecimalCodec implements JsonCodec<BigDecimal> {
    public static final BigDecimalCodec INSTANCE = new BigDecimalCodec();

    @Override
    public void writeString(StringJsonWriter writer, BigDecimal value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeBigDecimal(value);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, BigDecimal value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeBigDecimal(value);
      }
    }

    @Override
    public BigDecimal readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readBigDecimal();
    }

    @Override
    public BigDecimal readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readBigDecimal();
    }

    @Override
    public BigDecimal readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readBigDecimal();
    }
  }

  public static final class Float16Codec implements JsonCodec<Float16> {
    public static final Float16Codec INSTANCE = new Float16Codec();

    @Override
    public void writeString(StringJsonWriter writer, Float16 value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeFloat(value.floatValue());
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Float16 value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeFloat(value.floatValue());
      }
    }

    @Override
    public Float16 readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : Float16.valueOf(reader.readFloat());
    }

    @Override
    public Float16 readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : Float16.valueOf(reader.readFloat());
    }

    @Override
    public Float16 readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : Float16.valueOf(reader.readFloat());
    }
  }

  public static final class BFloat16Codec implements JsonCodec<BFloat16> {
    public static final BFloat16Codec INSTANCE = new BFloat16Codec();

    @Override
    public void writeString(StringJsonWriter writer, BFloat16 value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeFloat(value.floatValue());
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, BFloat16 value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeFloat(value.floatValue());
      }
    }

    @Override
    public BFloat16 readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : BFloat16.valueOf(reader.readFloat());
    }

    @Override
    public BFloat16 readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : BFloat16.valueOf(reader.readFloat());
    }

    @Override
    public BFloat16 readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : BFloat16.valueOf(reader.readFloat());
    }
  }

  public static final class BitSetCodec implements JsonCodec<BitSet> {
    public static final BitSetCodec INSTANCE = new BitSetCodec();

    private BitSetCodec() {}

    @Override
    public void writeString(StringJsonWriter writer, BitSet value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      BitSet bitSet = value;
      long[] words = bitSet.toLongArray();
      writer.writeArrayStart();
      for (int i = 0; i < words.length; i++) {
        writer.writeComma(i);
        writer.writeLong(words[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, BitSet value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      BitSet bitSet = value;
      long[] words = bitSet.toLongArray();
      writer.writeArrayStart();
      for (int i = 0; i < words.length; i++) {
        writer.writeComma(i);
        writer.writeLong(words[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    public BitSet readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : readBitSet(reader);
    }

    @Override
    public BitSet readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : readBitSet(reader);
    }

    @Override
    public BitSet readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : readBitSet(reader);
    }

    private static BitSet readBitSet(Latin1JsonReader reader) {
      reader.enterDepth();
      reader.expect('[');
      long[] words = new long[4];
      int size = 0;
      if (!reader.consume(']')) {
        do {
          if (size == words.length) {
            words = Arrays.copyOf(words, words.length << 1);
          }
          words[size++] = reader.readLong();
        } while (reader.consume(','));
        reader.expect(']');
      }
      if (size != words.length) {
        words = Arrays.copyOf(words, size);
      }
      BitSet bitSet = BitSet.valueOf(words);
      reader.exitDepth();
      return bitSet;
    }

    private static BitSet readBitSet(Utf16JsonReader reader) {
      reader.enterDepth();
      reader.expect('[');
      long[] words = new long[4];
      int size = 0;
      if (!reader.consume(']')) {
        do {
          if (size == words.length) {
            words = Arrays.copyOf(words, words.length << 1);
          }
          words[size++] = reader.readLong();
        } while (reader.consume(','));
        reader.expect(']');
      }
      if (size != words.length) {
        words = Arrays.copyOf(words, size);
      }
      BitSet bitSet = BitSet.valueOf(words);
      reader.exitDepth();
      return bitSet;
    }

    private static BitSet readBitSet(Utf8JsonReader reader) {
      reader.enterDepth();
      reader.expect('[');
      long[] words = new long[4];
      int size = 0;
      if (!reader.consume(']')) {
        do {
          if (size == words.length) {
            words = Arrays.copyOf(words, words.length << 1);
          }
          words[size++] = reader.readLong();
        } while (reader.consume(','));
        reader.expect(']');
      }
      if (size != words.length) {
        words = Arrays.copyOf(words, size);
      }
      BitSet bitSet = BitSet.valueOf(words);
      reader.exitDepth();
      return bitSet;
    }
  }

  public static final class StringBuilderCodec implements JsonCodec<StringBuilder> {
    public static final StringBuilderCodec INSTANCE = new StringBuilderCodec();

    @Override
    public void writeString(StringJsonWriter writer, StringBuilder value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, StringBuilder value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value);
      }
    }

    @Override
    public StringBuilder readLatin1(Latin1JsonReader reader) {
      String value = reader.readNullableString();
      return value == null ? null : new StringBuilder(value);
    }

    @Override
    public StringBuilder readUtf16(Utf16JsonReader reader) {
      String value = reader.readNullableString();
      return value == null ? null : new StringBuilder(value);
    }

    @Override
    public StringBuilder readUtf8(Utf8JsonReader reader) {
      String value = reader.readNullableString();
      return value == null ? null : new StringBuilder(value);
    }
  }

  public static final class StringBufferCodec implements JsonCodec<StringBuffer> {
    public static final StringBufferCodec INSTANCE = new StringBufferCodec();

    @Override
    public void writeString(StringJsonWriter writer, StringBuffer value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, StringBuffer value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value);
      }
    }

    @Override
    public StringBuffer readLatin1(Latin1JsonReader reader) {
      String value = reader.readNullableString();
      return value == null ? null : new StringBuffer(value);
    }

    @Override
    public StringBuffer readUtf16(Utf16JsonReader reader) {
      String value = reader.readNullableString();
      return value == null ? null : new StringBuffer(value);
    }

    @Override
    public StringBuffer readUtf8(Utf8JsonReader reader) {
      String value = reader.readNullableString();
      return value == null ? null : new StringBuffer(value);
    }
  }

  public static final class FileCodec implements JsonCodec<File> {
    public static final FileCodec INSTANCE = new FileCodec();

    @Override
    public void writeString(StringJsonWriter writer, File value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.getPath());
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, File value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.getPath());
      }
    }

    @Override
    public File readLatin1(Latin1JsonReader reader) {
      String value = reader.readNullableString();
      return value == null ? null : new File(value);
    }

    @Override
    public File readUtf16(Utf16JsonReader reader) {
      String value = reader.readNullableString();
      return value == null ? null : new File(value);
    }

    @Override
    public File readUtf8(Utf8JsonReader reader) {
      String value = reader.readNullableString();
      return value == null ? null : new File(value);
    }
  }

  public static final class PathCodec implements JsonCodec<Path> {
    public static final PathCodec INSTANCE = new PathCodec();

    @Override
    public void writeString(StringJsonWriter writer, Path value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.toString());
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Path value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.toString());
      }
    }

    @Override
    public Path readLatin1(Latin1JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return Paths.get(value);
      } catch (RuntimeException e) {
        throw invalidString(Path.class, value, e);
      }
    }

    @Override
    public Path readUtf16(Utf16JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return Paths.get(value);
      } catch (RuntimeException e) {
        throw invalidString(Path.class, value, e);
      }
    }

    @Override
    public Path readUtf8(Utf8JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return Paths.get(value);
      } catch (RuntimeException e) {
        throw invalidString(Path.class, value, e);
      }
    }
  }

  public static final class CurrencyCodec implements JsonCodec<Currency> {
    public static final CurrencyCodec INSTANCE = new CurrencyCodec();

    @Override
    public void writeString(StringJsonWriter writer, Currency value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.getCurrencyCode());
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Currency value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.getCurrencyCode());
      }
    }

    @Override
    public Currency readLatin1(Latin1JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return Currency.getInstance(value);
      } catch (RuntimeException e) {
        throw invalidString(Currency.class, value, e);
      }
    }

    @Override
    public Currency readUtf16(Utf16JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return Currency.getInstance(value);
      } catch (RuntimeException e) {
        throw invalidString(Currency.class, value, e);
      }
    }

    @Override
    public Currency readUtf8(Utf8JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return Currency.getInstance(value);
      } catch (RuntimeException e) {
        throw invalidString(Currency.class, value, e);
      }
    }
  }

  public static final class UriCodec implements JsonCodec<URI> {
    public static final UriCodec INSTANCE = new UriCodec();

    @Override
    public void writeString(StringJsonWriter writer, URI value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.toString());
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, URI value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.toString());
      }
    }

    @Override
    public URI readLatin1(Latin1JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return URI.create(value);
      } catch (RuntimeException e) {
        throw invalidString(URI.class, value, e);
      }
    }

    @Override
    public URI readUtf16(Utf16JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return URI.create(value);
      } catch (RuntimeException e) {
        throw invalidString(URI.class, value, e);
      }
    }

    @Override
    public URI readUtf8(Utf8JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return URI.create(value);
      } catch (RuntimeException e) {
        throw invalidString(URI.class, value, e);
      }
    }
  }

  public static final class PatternCodec implements JsonCodec<Pattern> {
    public static final PatternCodec INSTANCE = new PatternCodec();

    @Override
    public void writeString(StringJsonWriter writer, Pattern value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.pattern());
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Pattern value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.pattern());
      }
    }

    @Override
    public Pattern readLatin1(Latin1JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return Pattern.compile(value);
      } catch (RuntimeException e) {
        throw invalidString(Pattern.class, value, e);
      }
    }

    @Override
    public Pattern readUtf16(Utf16JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return Pattern.compile(value);
      } catch (RuntimeException e) {
        throw invalidString(Pattern.class, value, e);
      }
    }

    @Override
    public Pattern readUtf8(Utf8JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return Pattern.compile(value);
      } catch (RuntimeException e) {
        throw invalidString(Pattern.class, value, e);
      }
    }
  }

  public static final class UuidCodec implements JsonCodec<UUID> {
    public static final UuidCodec INSTANCE = new UuidCodec();

    @Override
    public void writeUtf8(Utf8JsonWriter writer, UUID value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeUuid(value);
      }
    }

    @Override
    public void writeString(StringJsonWriter writer, UUID value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeUuid(value);
      }
    }

    @Override
    public UUID readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readUuid();
    }

    @Override
    public UUID readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readUuid();
    }

    @Override
    public UUID readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readUuid();
    }
  }

  public static final class LocaleCodec implements JsonCodec<Locale> {
    public static final LocaleCodec INSTANCE = new LocaleCodec();

    @Override
    public void writeString(StringJsonWriter writer, Locale value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.toLanguageTag());
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Locale value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.toLanguageTag());
      }
    }

    @Override
    public Locale readLatin1(Latin1JsonReader reader) {
      String value = reader.readNullableString();
      return value == null ? null : Locale.forLanguageTag(value);
    }

    @Override
    public Locale readUtf16(Utf16JsonReader reader) {
      String value = reader.readNullableString();
      return value == null ? null : Locale.forLanguageTag(value);
    }

    @Override
    public Locale readUtf8(Utf8JsonReader reader) {
      String value = reader.readNullableString();
      return value == null ? null : Locale.forLanguageTag(value);
    }
  }

  public static final class CharsetCodec implements JsonCodec<Charset> {
    public static final CharsetCodec INSTANCE = new CharsetCodec();

    @Override
    public void writeString(StringJsonWriter writer, Charset value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.name());
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Charset value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.name());
      }
    }

    @Override
    public Charset readLatin1(Latin1JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return Charset.forName(value);
      } catch (RuntimeException e) {
        throw invalidString(Charset.class, value, e);
      }
    }

    @Override
    public Charset readUtf16(Utf16JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return Charset.forName(value);
      } catch (RuntimeException e) {
        throw invalidString(Charset.class, value, e);
      }
    }

    @Override
    public Charset readUtf8(Utf8JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return Charset.forName(value);
      } catch (RuntimeException e) {
        throw invalidString(Charset.class, value, e);
      }
    }
  }

  public static final class DateCodec implements JsonCodec<Date> {
    public static final DateCodec INSTANCE = new DateCodec();

    @Override
    public void writeString(StringJsonWriter writer, Date value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeLong(value.getTime());
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Date value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeLong(value.getTime());
      }
    }

    @Override
    public Date readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : new Date(reader.readLong());
    }

    @Override
    public Date readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : new Date(reader.readLong());
    }

    @Override
    public Date readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : new Date(reader.readLong());
    }
  }

  public static final class CalendarCodec implements JsonCodec<Calendar> {
    public static final CalendarCodec INSTANCE = new CalendarCodec();

    @Override
    public void writeString(StringJsonWriter writer, Calendar value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeLong(value.getTimeInMillis());
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Calendar value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeLong(value.getTimeInMillis());
      }
    }

    @Override
    public Calendar readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : calendar(reader.readLong());
    }

    @Override
    public Calendar readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : calendar(reader.readLong());
    }

    @Override
    public Calendar readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : calendar(reader.readLong());
    }

    private static Calendar calendar(long millis) {
      Calendar calendar = new GregorianCalendar();
      calendar.setTimeInMillis(millis);
      return calendar;
    }
  }

  public static final class TimeZoneCodec implements JsonCodec<TimeZone> {
    public static final TimeZoneCodec INSTANCE = new TimeZoneCodec();

    @Override
    public void writeString(StringJsonWriter writer, TimeZone value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.getID());
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, TimeZone value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.getID());
      }
    }

    @Override
    public TimeZone readLatin1(Latin1JsonReader reader) {
      String value = reader.readNullableString();
      return value == null ? null : TimeZone.getTimeZone(value);
    }

    @Override
    public TimeZone readUtf16(Utf16JsonReader reader) {
      String value = reader.readNullableString();
      return value == null ? null : TimeZone.getTimeZone(value);
    }

    @Override
    public TimeZone readUtf8(Utf8JsonReader reader) {
      String value = reader.readNullableString();
      return value == null ? null : TimeZone.getTimeZone(value);
    }
  }

  public static final class LocalDateCodec implements JsonCodec<LocalDate> {
    public static final LocalDateCodec INSTANCE = new LocalDateCodec();

    @Override
    public void writeUtf8(Utf8JsonWriter writer, LocalDate value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeLocalDate(value);
      }
    }

    @Override
    public void writeString(StringJsonWriter writer, LocalDate value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeLocalDate(value);
      }
    }

    @Override
    public LocalDate readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readIsoLocalDate();
    }

    @Override
    public LocalDate readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readIsoLocalDate();
    }

    @Override
    public LocalDate readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readIsoLocalDate();
    }
  }

  public static final class LocalTimeCodec implements JsonCodec<LocalTime> {
    public static final LocalTimeCodec INSTANCE = new LocalTimeCodec();

    @Override
    public void writeString(StringJsonWriter writer, LocalTime value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeTemporal(value, DateTimeFormatter.ISO_LOCAL_TIME);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, LocalTime value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeTemporal(value, DateTimeFormatter.ISO_LOCAL_TIME);
      }
    }

    @Override
    public LocalTime readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readIsoLocalTime();
    }

    @Override
    public LocalTime readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readIsoLocalTime();
    }

    @Override
    public LocalTime readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readIsoLocalTime();
    }
  }

  public static final class LocalDateTimeCodec implements JsonCodec<LocalDateTime> {
    public static final LocalDateTimeCodec INSTANCE = new LocalDateTimeCodec();

    @Override
    public void writeString(StringJsonWriter writer, LocalDateTime value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeTemporal(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, LocalDateTime value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeTemporal(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
      }
    }

    @Override
    public LocalDateTime readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readIsoLocalDateTime();
    }

    @Override
    public LocalDateTime readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readIsoLocalDateTime();
    }

    @Override
    public LocalDateTime readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readIsoLocalDateTime();
    }
  }

  public static final class InstantCodec implements JsonCodec<Instant> {
    public static final InstantCodec INSTANCE = new InstantCodec();

    @Override
    public void writeString(StringJsonWriter writer, Instant value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeTemporal(value, DateTimeFormatter.ISO_INSTANT);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Instant value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeTemporal(value, DateTimeFormatter.ISO_INSTANT);
      }
    }

    @Override
    public Instant readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readIsoInstant();
    }

    @Override
    public Instant readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readIsoInstant();
    }

    @Override
    public Instant readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readIsoInstant();
    }
  }

  public static final class DurationCodec implements JsonCodec<Duration> {
    public static final DurationCodec INSTANCE = new DurationCodec();

    @Override
    public void writeString(StringJsonWriter writer, Duration value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeDuration(value);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Duration value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeDuration(value);
      }
    }

    @Override
    public Duration readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readDuration();
    }

    @Override
    public Duration readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readDuration();
    }

    @Override
    public Duration readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readDuration();
    }
  }

  public static final class ZoneOffsetCodec implements JsonCodec<ZoneOffset> {
    public static final ZoneOffsetCodec INSTANCE = new ZoneOffsetCodec();

    @Override
    public void writeString(StringJsonWriter writer, ZoneOffset value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.getId());
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, ZoneOffset value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.getId());
      }
    }

    @Override
    public ZoneOffset readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readZoneOffset();
    }

    @Override
    public ZoneOffset readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readZoneOffset();
    }

    @Override
    public ZoneOffset readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readZoneOffset();
    }
  }

  public static final class ZoneIdCodec implements JsonCodec<ZoneId> {
    public static final ZoneIdCodec INSTANCE = new ZoneIdCodec();

    @Override
    public void writeString(StringJsonWriter writer, ZoneId value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.getId());
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, ZoneId value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.getId());
      }
    }

    @Override
    public ZoneId readLatin1(Latin1JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return ZoneId.of(value);
      } catch (RuntimeException e) {
        throw invalidString(ZoneId.class, value, e);
      }
    }

    @Override
    public ZoneId readUtf16(Utf16JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return ZoneId.of(value);
      } catch (RuntimeException e) {
        throw invalidString(ZoneId.class, value, e);
      }
    }

    @Override
    public ZoneId readUtf8(Utf8JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return ZoneId.of(value);
      } catch (RuntimeException e) {
        throw invalidString(ZoneId.class, value, e);
      }
    }
  }

  public static final class ZonedDateTimeCodec implements JsonCodec<ZonedDateTime> {
    public static final ZonedDateTimeCodec INSTANCE = new ZonedDateTimeCodec();

    @Override
    public void writeString(StringJsonWriter writer, ZonedDateTime value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeTemporal(value, DateTimeFormatter.ISO_ZONED_DATE_TIME);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, ZonedDateTime value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeTemporal(value, DateTimeFormatter.ISO_ZONED_DATE_TIME);
      }
    }

    @Override
    public ZonedDateTime readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readZonedDateTime();
    }

    @Override
    public ZonedDateTime readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readZonedDateTime();
    }

    @Override
    public ZonedDateTime readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readZonedDateTime();
    }
  }

  public static final class YearCodec implements JsonCodec<Year> {
    public static final YearCodec INSTANCE = new YearCodec();

    @Override
    public void writeString(StringJsonWriter writer, Year value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeYear(value);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Year value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeYear(value);
      }
    }

    @Override
    public Year readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readYear();
    }

    @Override
    public Year readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readYear();
    }

    @Override
    public Year readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readYear();
    }
  }

  public static final class YearMonthCodec implements JsonCodec<YearMonth> {
    public static final YearMonthCodec INSTANCE = new YearMonthCodec();

    @Override
    public void writeString(StringJsonWriter writer, YearMonth value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeTemporal(value, YEAR_MONTH_FORMATTER);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, YearMonth value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeTemporal(value, YEAR_MONTH_FORMATTER);
      }
    }

    @Override
    public YearMonth readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readYearMonth();
    }

    @Override
    public YearMonth readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readYearMonth();
    }

    @Override
    public YearMonth readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readYearMonth();
    }
  }

  public static final class MonthDayCodec implements JsonCodec<MonthDay> {
    public static final MonthDayCodec INSTANCE = new MonthDayCodec();

    @Override
    public void writeString(StringJsonWriter writer, MonthDay value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeTemporal(value, MONTH_DAY_FORMATTER);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, MonthDay value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeTemporal(value, MONTH_DAY_FORMATTER);
      }
    }

    @Override
    public MonthDay readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readMonthDay();
    }

    @Override
    public MonthDay readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readMonthDay();
    }

    @Override
    public MonthDay readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readMonthDay();
    }
  }

  public static final class PeriodCodec implements JsonCodec<Period> {
    public static final PeriodCodec INSTANCE = new PeriodCodec();

    @Override
    public void writeString(StringJsonWriter writer, Period value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writePeriod(value);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Period value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writePeriod(value);
      }
    }

    @Override
    public Period readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readPeriod();
    }

    @Override
    public Period readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readPeriod();
    }

    @Override
    public Period readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readPeriod();
    }
  }

  public static final class OffsetTimeCodec implements JsonCodec<OffsetTime> {
    public static final OffsetTimeCodec INSTANCE = new OffsetTimeCodec();

    @Override
    public void writeString(StringJsonWriter writer, OffsetTime value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeTemporal(value, DateTimeFormatter.ISO_OFFSET_TIME);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, OffsetTime value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeTemporal(value, DateTimeFormatter.ISO_OFFSET_TIME);
      }
    }

    @Override
    public OffsetTime readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readOffsetTime();
    }

    @Override
    public OffsetTime readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readOffsetTime();
    }

    @Override
    public OffsetTime readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readOffsetTime();
    }
  }

  public static final class OffsetDateTimeCodec implements JsonCodec<OffsetDateTime> {
    public static final OffsetDateTimeCodec INSTANCE = new OffsetDateTimeCodec();

    @Override
    public void writeUtf8(Utf8JsonWriter writer, OffsetDateTime value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeOffsetDateTime(value);
      }
    }

    @Override
    public void writeString(StringJsonWriter writer, OffsetDateTime value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeOffsetDateTime(value);
      }
    }

    @Override
    public OffsetDateTime readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readIsoOffsetDateTime();
    }

    @Override
    public OffsetDateTime readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readIsoOffsetDateTime();
    }

    @Override
    public OffsetDateTime readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readIsoOffsetDateTime();
    }
  }

  public static final class HijrahDateCodec implements JsonCodec<HijrahDate> {
    public static final HijrahDateCodec INSTANCE = new HijrahDateCodec();

    @Override
    public void writeString(StringJsonWriter writer, HijrahDate value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(HIJRAH_DATE_FORMATTER.format(value));
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, HijrahDate value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(HIJRAH_DATE_FORMATTER.format(value));
      }
    }

    @Override
    public HijrahDate readLatin1(Latin1JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return HijrahDate.from(HIJRAH_DATE_FORMATTER.parse(value));
      } catch (RuntimeException e) {
        throw invalidString(HijrahDate.class, value, e);
      }
    }

    @Override
    public HijrahDate readUtf16(Utf16JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return HijrahDate.from(HIJRAH_DATE_FORMATTER.parse(value));
      } catch (RuntimeException e) {
        throw invalidString(HijrahDate.class, value, e);
      }
    }

    @Override
    public HijrahDate readUtf8(Utf8JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return HijrahDate.from(HIJRAH_DATE_FORMATTER.parse(value));
      } catch (RuntimeException e) {
        throw invalidString(HijrahDate.class, value, e);
      }
    }
  }

  public static final class JapaneseDateCodec implements JsonCodec<JapaneseDate> {
    public static final JapaneseDateCodec INSTANCE = new JapaneseDateCodec();

    @Override
    public void writeString(StringJsonWriter writer, JapaneseDate value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(JAPANESE_DATE_FORMATTER.format(value));
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, JapaneseDate value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(JAPANESE_DATE_FORMATTER.format(value));
      }
    }

    @Override
    public JapaneseDate readLatin1(Latin1JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return JapaneseDate.from(JAPANESE_DATE_FORMATTER.parse(value));
      } catch (RuntimeException e) {
        throw invalidString(JapaneseDate.class, value, e);
      }
    }

    @Override
    public JapaneseDate readUtf16(Utf16JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return JapaneseDate.from(JAPANESE_DATE_FORMATTER.parse(value));
      } catch (RuntimeException e) {
        throw invalidString(JapaneseDate.class, value, e);
      }
    }

    @Override
    public JapaneseDate readUtf8(Utf8JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return JapaneseDate.from(JAPANESE_DATE_FORMATTER.parse(value));
      } catch (RuntimeException e) {
        throw invalidString(JapaneseDate.class, value, e);
      }
    }
  }

  public static final class MinguoDateCodec implements JsonCodec<MinguoDate> {
    public static final MinguoDateCodec INSTANCE = new MinguoDateCodec();

    @Override
    public void writeString(StringJsonWriter writer, MinguoDate value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(MINGUO_DATE_FORMATTER.format(value));
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, MinguoDate value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(MINGUO_DATE_FORMATTER.format(value));
      }
    }

    @Override
    public MinguoDate readLatin1(Latin1JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return MinguoDate.from(MINGUO_DATE_FORMATTER.parse(value));
      } catch (RuntimeException e) {
        throw invalidString(MinguoDate.class, value, e);
      }
    }

    @Override
    public MinguoDate readUtf16(Utf16JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return MinguoDate.from(MINGUO_DATE_FORMATTER.parse(value));
      } catch (RuntimeException e) {
        throw invalidString(MinguoDate.class, value, e);
      }
    }

    @Override
    public MinguoDate readUtf8(Utf8JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return MinguoDate.from(MINGUO_DATE_FORMATTER.parse(value));
      } catch (RuntimeException e) {
        throw invalidString(MinguoDate.class, value, e);
      }
    }
  }

  public static final class ThaiBuddhistDateCodec implements JsonCodec<ThaiBuddhistDate> {
    public static final ThaiBuddhistDateCodec INSTANCE = new ThaiBuddhistDateCodec();

    @Override
    public void writeString(StringJsonWriter writer, ThaiBuddhistDate value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(THAI_BUDDHIST_DATE_FORMATTER.format(value));
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, ThaiBuddhistDate value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(THAI_BUDDHIST_DATE_FORMATTER.format(value));
      }
    }

    @Override
    public ThaiBuddhistDate readLatin1(Latin1JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return ThaiBuddhistDate.from(THAI_BUDDHIST_DATE_FORMATTER.parse(value));
      } catch (RuntimeException e) {
        throw invalidString(ThaiBuddhistDate.class, value, e);
      }
    }

    @Override
    public ThaiBuddhistDate readUtf16(Utf16JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return ThaiBuddhistDate.from(THAI_BUDDHIST_DATE_FORMATTER.parse(value));
      } catch (RuntimeException e) {
        throw invalidString(ThaiBuddhistDate.class, value, e);
      }
    }

    @Override
    public ThaiBuddhistDate readUtf8(Utf8JsonReader reader) {
      String value = reader.readNullableString();
      if (value == null) {
        return null;
      }
      try {
        return ThaiBuddhistDate.from(THAI_BUDDHIST_DATE_FORMATTER.parse(value));
      } catch (RuntimeException e) {
        throw invalidString(ThaiBuddhistDate.class, value, e);
      }
    }
  }

  public static final class AtomicBooleanCodec implements JsonCodec<AtomicBoolean> {
    public static final AtomicBooleanCodec INSTANCE = new AtomicBooleanCodec();

    @Override
    public void writeString(StringJsonWriter writer, AtomicBoolean value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeBoolean(value.get());
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, AtomicBoolean value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeBoolean(value.get());
      }
    }

    @Override
    public AtomicBoolean readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : new AtomicBoolean(reader.readBoolean());
    }

    @Override
    public AtomicBoolean readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : new AtomicBoolean(reader.readBoolean());
    }

    @Override
    public AtomicBoolean readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : new AtomicBoolean(reader.readBoolean());
    }
  }

  public static final class AtomicIntegerCodec implements JsonCodec<AtomicInteger> {
    public static final AtomicIntegerCodec INSTANCE = new AtomicIntegerCodec();

    @Override
    public void writeString(StringJsonWriter writer, AtomicInteger value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeInt(value.get());
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, AtomicInteger value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeInt(value.get());
      }
    }

    @Override
    public AtomicInteger readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : new AtomicInteger(reader.readInt());
    }

    @Override
    public AtomicInteger readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : new AtomicInteger(reader.readInt());
    }

    @Override
    public AtomicInteger readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : new AtomicInteger(reader.readInt());
    }
  }

  public static final class AtomicLongCodec implements JsonCodec<AtomicLong> {
    public static final AtomicLongCodec INSTANCE = new AtomicLongCodec();

    @Override
    public void writeString(StringJsonWriter writer, AtomicLong value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeLong(value.get());
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, AtomicLong value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeLong(value.get());
      }
    }

    @Override
    public AtomicLong readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : new AtomicLong(reader.readLong());
    }

    @Override
    public AtomicLong readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : new AtomicLong(reader.readLong());
    }

    @Override
    public AtomicLong readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : new AtomicLong(reader.readLong());
    }
  }

  public static final class AtomicReferenceCodec implements JsonCodec<AtomicReference<?>> {
    private final JsonTypeInfo valueTypeInfo;

    public AtomicReferenceCodec(java.lang.reflect.Type valueType, JsonTypeResolver resolver) {
      Class<?> valueRawType = CodecUtils.rawType(valueType, Object.class);
      valueTypeInfo = resolver.getTypeInfo(valueType, valueRawType);
    }

    @Override
    public void writeString(StringJsonWriter writer, AtomicReference<?> value) {
      if (value == null) {
        writer.writeNull();
      } else {
        valueTypeInfo.stringWriter().writeString(writer, value.get());
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, AtomicReference<?> value) {
      if (value == null) {
        writer.writeNull();
      } else {
        valueTypeInfo.utf8Writer().writeUtf8(writer, value.get());
      }
    }

    @Override
    public AtomicReference<?> readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return new AtomicReference<>();
      }
      return new AtomicReference<>(valueTypeInfo.latin1Reader().readLatin1(reader));
    }

    @Override
    public AtomicReference<?> readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return new AtomicReference<>();
      }
      return new AtomicReference<>(valueTypeInfo.utf16Reader().readUtf16(reader));
    }

    @Override
    public AtomicReference<?> readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return new AtomicReference<>();
      }
      return new AtomicReference<>(valueTypeInfo.utf8Reader().readUtf8(reader));
    }
  }

  public static final class AtomicIntegerArrayCodec implements JsonCodec<AtomicIntegerArray> {
    public static final AtomicIntegerArrayCodec INSTANCE = new AtomicIntegerArrayCodec();

    @Override
    public void writeString(StringJsonWriter writer, AtomicIntegerArray value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      AtomicIntegerArray array = value;
      writer.writeArrayStart();
      for (int i = 0, length = array.length(); i < length; i++) {
        writer.writeComma(i);
        writer.writeInt(array.get(i));
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, AtomicIntegerArray value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      AtomicIntegerArray array = value;
      writer.writeArrayStart();
      for (int i = 0, length = array.length(); i < length; i++) {
        writer.writeComma(i);
        writer.writeInt(array.get(i));
      }
      writer.writeArrayEnd();
    }

    @Override
    public AtomicIntegerArray readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : readArray(reader);
    }

    @Override
    public AtomicIntegerArray readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : readArray(reader);
    }

    @Override
    public AtomicIntegerArray readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : readArray(reader);
    }

    private static AtomicIntegerArray readArray(Latin1JsonReader reader) {
      reader.enterDepth();
      reader.expect('[');
      if (reader.consume(']')) {
        reader.exitDepth();
        return new AtomicIntegerArray(0);
      }
      int[] values = new int[8];
      int size = 0;
      do {
        if (reader.tryReadNull()) {
          throw new ForyJsonException("Cannot read null into AtomicIntegerArray element");
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readInt();
      } while (reader.consume(','));
      reader.expect(']');
      reader.exitDepth();
      return new AtomicIntegerArray(Arrays.copyOf(values, size));
    }

    private static AtomicIntegerArray readArray(Utf16JsonReader reader) {
      reader.enterDepth();
      reader.expect('[');
      if (reader.consume(']')) {
        reader.exitDepth();
        return new AtomicIntegerArray(0);
      }
      int[] values = new int[8];
      int size = 0;
      do {
        if (reader.tryReadNull()) {
          throw new ForyJsonException("Cannot read null into AtomicIntegerArray element");
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readInt();
      } while (reader.consume(','));
      reader.expect(']');
      reader.exitDepth();
      return new AtomicIntegerArray(Arrays.copyOf(values, size));
    }

    private static AtomicIntegerArray readArray(Utf8JsonReader reader) {
      reader.enterDepth();
      reader.expect('[');
      if (reader.consume(']')) {
        reader.exitDepth();
        return new AtomicIntegerArray(0);
      }
      int[] values = new int[8];
      int size = 0;
      do {
        if (reader.tryReadNull()) {
          throw new ForyJsonException("Cannot read null into AtomicIntegerArray element");
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readInt();
      } while (reader.consume(','));
      reader.expect(']');
      reader.exitDepth();
      return new AtomicIntegerArray(Arrays.copyOf(values, size));
    }
  }

  public static final class AtomicLongArrayCodec implements JsonCodec<AtomicLongArray> {
    public static final AtomicLongArrayCodec INSTANCE = new AtomicLongArrayCodec();

    @Override
    public void writeString(StringJsonWriter writer, AtomicLongArray value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      AtomicLongArray array = value;
      writer.writeArrayStart();
      for (int i = 0, length = array.length(); i < length; i++) {
        writer.writeComma(i);
        writer.writeLong(array.get(i));
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, AtomicLongArray value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      AtomicLongArray array = value;
      writer.writeArrayStart();
      for (int i = 0, length = array.length(); i < length; i++) {
        writer.writeComma(i);
        writer.writeLong(array.get(i));
      }
      writer.writeArrayEnd();
    }

    @Override
    public AtomicLongArray readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : readArray(reader);
    }

    @Override
    public AtomicLongArray readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : readArray(reader);
    }

    @Override
    public AtomicLongArray readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : readArray(reader);
    }

    private static AtomicLongArray readArray(Latin1JsonReader reader) {
      reader.enterDepth();
      reader.expect('[');
      if (reader.consume(']')) {
        reader.exitDepth();
        return new AtomicLongArray(0);
      }
      long[] values = new long[8];
      int size = 0;
      do {
        if (reader.tryReadNull()) {
          throw new ForyJsonException("Cannot read null into AtomicLongArray element");
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readLong();
      } while (reader.consume(','));
      reader.expect(']');
      reader.exitDepth();
      return new AtomicLongArray(Arrays.copyOf(values, size));
    }

    private static AtomicLongArray readArray(Utf16JsonReader reader) {
      reader.enterDepth();
      reader.expect('[');
      if (reader.consume(']')) {
        reader.exitDepth();
        return new AtomicLongArray(0);
      }
      long[] values = new long[8];
      int size = 0;
      do {
        if (reader.tryReadNull()) {
          throw new ForyJsonException("Cannot read null into AtomicLongArray element");
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readLong();
      } while (reader.consume(','));
      reader.expect(']');
      reader.exitDepth();
      return new AtomicLongArray(Arrays.copyOf(values, size));
    }

    private static AtomicLongArray readArray(Utf8JsonReader reader) {
      reader.enterDepth();
      reader.expect('[');
      if (reader.consume(']')) {
        reader.exitDepth();
        return new AtomicLongArray(0);
      }
      long[] values = new long[8];
      int size = 0;
      do {
        if (reader.tryReadNull()) {
          throw new ForyJsonException("Cannot read null into AtomicLongArray element");
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readLong();
      } while (reader.consume(','));
      reader.expect(']');
      reader.exitDepth();
      return new AtomicLongArray(Arrays.copyOf(values, size));
    }
  }

  public static final class AtomicReferenceArrayCodec
      implements JsonCodec<AtomicReferenceArray<?>> {
    private final JsonTypeInfo valueTypeInfo;

    public AtomicReferenceArrayCodec(java.lang.reflect.Type valueType, JsonTypeResolver resolver) {
      Class<?> valueRawType = CodecUtils.rawType(valueType, Object.class);
      valueTypeInfo = resolver.getTypeInfo(valueType, valueRawType);
    }

    @Override
    public void writeString(StringJsonWriter writer, AtomicReferenceArray<?> value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      AtomicReferenceArray<?> array = value;
      StringWriterCodec<Object> codec = valueTypeInfo.stringWriter();
      writer.writeArrayStart();
      for (int i = 0, length = array.length(); i < length; i++) {
        writer.writeComma(i);
        codec.writeString(writer, array.get(i));
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, AtomicReferenceArray<?> value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      AtomicReferenceArray<?> array = value;
      Utf8WriterCodec<Object> codec = valueTypeInfo.utf8Writer();
      writer.writeArrayStart();
      for (int i = 0, length = array.length(); i < length; i++) {
        writer.writeComma(i);
        codec.writeUtf8(writer, array.get(i));
      }
      writer.writeArrayEnd();
    }

    @Override
    public AtomicReferenceArray<?> readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken()
          ? null
          : readAtomicReferenceArray(reader, valueTypeInfo.latin1Reader());
    }

    @Override
    public AtomicReferenceArray<?> readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new AtomicReferenceArray<>(0);
      }
      Object[] values = new Object[8];
      int size = 0;
      Utf16ReaderCodec<Object> codec = valueTypeInfo.utf16Reader();
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = codec.readUtf16(reader);
      } while (reader.consumeNextCommaOrEndArray());
      reader.exitDepth();
      return new AtomicReferenceArray<>(Arrays.copyOf(values, size));
    }

    @Override
    public AtomicReferenceArray<?> readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new AtomicReferenceArray<>(0);
      }
      Object[] values = new Object[8];
      int size = 0;
      Utf8ReaderCodec<Object> codec = valueTypeInfo.utf8Reader();
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = codec.readUtf8(reader);
      } while (reader.consumeNextCommaOrEndArray());
      reader.exitDepth();
      return new AtomicReferenceArray<>(Arrays.copyOf(values, size));
    }

    private AtomicReferenceArray<?> readAtomicReferenceArray(
        Latin1JsonReader reader, Latin1ReaderCodec<Object> codec) {
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new AtomicReferenceArray<>(0);
      }
      Object[] values = new Object[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = codec.readLatin1(reader);
      } while (reader.consumeNextCommaOrEndArray());
      reader.exitDepth();
      return new AtomicReferenceArray<>(Arrays.copyOf(values, size));
    }
  }

  public static final class OptionalCodec implements JsonCodec<Optional<?>> {
    private final JsonTypeInfo valueTypeInfo;

    public OptionalCodec(java.lang.reflect.Type valueType, JsonTypeResolver resolver) {
      Class<?> valueRawType = CodecUtils.rawType(valueType, Object.class);
      valueTypeInfo = resolver.getTypeInfo(valueType, valueRawType);
    }

    @Override
    public void writeString(StringJsonWriter writer, Optional<?> value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      Optional<?> optional = value;
      if (optional.isPresent()) {
        valueTypeInfo.stringWriter().writeString(writer, optional.get());
      } else {
        writer.writeNull();
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Optional<?> value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      Optional<?> optional = value;
      if (optional.isPresent()) {
        valueTypeInfo.utf8Writer().writeUtf8(writer, optional.get());
      } else {
        writer.writeNull();
      }
    }

    @Override
    public Optional<?> readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return Optional.empty();
      }
      return Optional.ofNullable(valueTypeInfo.latin1Reader().readLatin1(reader));
    }

    @Override
    public Optional<?> readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return Optional.empty();
      }
      return Optional.ofNullable(valueTypeInfo.utf16Reader().readUtf16(reader));
    }

    @Override
    public Optional<?> readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return Optional.empty();
      }
      return Optional.ofNullable(valueTypeInfo.utf8Reader().readUtf8(reader));
    }
  }

  public static final class OptionalIntCodec implements JsonCodec<OptionalInt> {
    public static final OptionalIntCodec INSTANCE = new OptionalIntCodec();

    @Override
    public void writeString(StringJsonWriter writer, OptionalInt value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      OptionalInt optional = value;
      if (optional.isPresent()) {
        writer.writeInt(optional.getAsInt());
      } else {
        writer.writeNull();
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, OptionalInt value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      OptionalInt optional = value;
      if (optional.isPresent()) {
        writer.writeInt(optional.getAsInt());
      } else {
        writer.writeNull();
      }
    }

    @Override
    public OptionalInt readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? OptionalInt.empty() : OptionalInt.of(reader.readInt());
    }

    @Override
    public OptionalInt readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? OptionalInt.empty() : OptionalInt.of(reader.readInt());
    }

    @Override
    public OptionalInt readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? OptionalInt.empty() : OptionalInt.of(reader.readInt());
    }
  }

  public static final class OptionalLongCodec implements JsonCodec<OptionalLong> {
    public static final OptionalLongCodec INSTANCE = new OptionalLongCodec();

    @Override
    public void writeString(StringJsonWriter writer, OptionalLong value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      OptionalLong optional = value;
      if (optional.isPresent()) {
        writer.writeLong(optional.getAsLong());
      } else {
        writer.writeNull();
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, OptionalLong value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      OptionalLong optional = value;
      if (optional.isPresent()) {
        writer.writeLong(optional.getAsLong());
      } else {
        writer.writeNull();
      }
    }

    @Override
    public OptionalLong readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? OptionalLong.empty() : OptionalLong.of(reader.readLong());
    }

    @Override
    public OptionalLong readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? OptionalLong.empty() : OptionalLong.of(reader.readLong());
    }

    @Override
    public OptionalLong readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? OptionalLong.empty() : OptionalLong.of(reader.readLong());
    }
  }

  public static final class OptionalDoubleCodec implements JsonCodec<OptionalDouble> {
    public static final OptionalDoubleCodec INSTANCE = new OptionalDoubleCodec();

    @Override
    public void writeString(StringJsonWriter writer, OptionalDouble value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      OptionalDouble optional = value;
      if (optional.isPresent()) {
        writer.writeDouble(optional.getAsDouble());
      } else {
        writer.writeNull();
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, OptionalDouble value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      OptionalDouble optional = value;
      if (optional.isPresent()) {
        writer.writeDouble(optional.getAsDouble());
      } else {
        writer.writeNull();
      }
    }

    @Override
    public OptionalDouble readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken()
          ? OptionalDouble.empty()
          : OptionalDouble.of(reader.readDouble());
    }

    @Override
    public OptionalDouble readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken()
          ? OptionalDouble.empty()
          : OptionalDouble.of(reader.readDouble());
    }

    @Override
    public OptionalDouble readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken()
          ? OptionalDouble.empty()
          : OptionalDouble.of(reader.readDouble());
    }
  }

  public static final class ByteBufferCodec implements JsonCodec<ByteBuffer> {
    public static final ByteBufferCodec INSTANCE = new ByteBufferCodec();

    @Override
    public void writeString(StringJsonWriter writer, ByteBuffer value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      ByteBuffer buffer = value.duplicate();
      writer.writeArrayStart();
      int index = 0;
      while (buffer.hasRemaining()) {
        writer.writeComma(index++);
        writer.writeInt(buffer.get());
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, ByteBuffer value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      ByteBuffer buffer = value.duplicate();
      writer.writeArrayStart();
      int index = 0;
      while (buffer.hasRemaining()) {
        writer.writeComma(index++);
        writer.writeInt(buffer.get());
      }
      writer.writeArrayEnd();
    }

    @Override
    public ByteBuffer readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : readBuffer(reader);
    }

    @Override
    public ByteBuffer readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : readBuffer(reader);
    }

    @Override
    public ByteBuffer readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : readBuffer(reader);
    }

    private static ByteBuffer readBuffer(Latin1JsonReader reader) {
      reader.enterDepth();
      byte[] bytes = new byte[16];
      int size = 0;
      reader.expect('[');
      if (!reader.consume(']')) {
        do {
          if (size == bytes.length) {
            bytes = Arrays.copyOf(bytes, size << 1);
          }
          int value = reader.readInt();
          if (value < Byte.MIN_VALUE || value > 255) {
            throw new ForyJsonException("ByteBuffer element out of byte range: " + value);
          }
          bytes[size++] = (byte) value;
        } while (reader.consume(','));
        reader.expect(']');
      }
      reader.exitDepth();
      return ByteBuffer.wrap(Arrays.copyOf(bytes, size));
    }

    private static ByteBuffer readBuffer(Utf16JsonReader reader) {
      reader.enterDepth();
      byte[] bytes = new byte[16];
      int size = 0;
      reader.expect('[');
      if (!reader.consume(']')) {
        do {
          if (size == bytes.length) {
            bytes = Arrays.copyOf(bytes, size << 1);
          }
          int value = reader.readInt();
          if (value < Byte.MIN_VALUE || value > 255) {
            throw new ForyJsonException("ByteBuffer element out of byte range: " + value);
          }
          bytes[size++] = (byte) value;
        } while (reader.consume(','));
        reader.expect(']');
      }
      reader.exitDepth();
      return ByteBuffer.wrap(Arrays.copyOf(bytes, size));
    }

    private static ByteBuffer readBuffer(Utf8JsonReader reader) {
      reader.enterDepth();
      byte[] bytes = new byte[16];
      int size = 0;
      reader.expect('[');
      if (!reader.consume(']')) {
        do {
          if (size == bytes.length) {
            bytes = Arrays.copyOf(bytes, size << 1);
          }
          int value = reader.readInt();
          if (value < Byte.MIN_VALUE || value > 255) {
            throw new ForyJsonException("ByteBuffer element out of byte range: " + value);
          }
          bytes[size++] = (byte) value;
        } while (reader.consume(','));
        reader.expect(']');
      }
      reader.exitDepth();
      return ByteBuffer.wrap(Arrays.copyOf(bytes, size));
    }
  }

  public static final class EnumCodec implements JsonCodec<Enum<?>> {
    private final Class<?> type;
    private final long[] nameHashes;
    private final long[] tokenPrefixes;
    private final long[] tokenMasks;
    private final int[] tokenSuffixes;
    private final byte[] tokenSuffixLengths;
    private final int[] tokenLengths;
    private final Enum<?>[] values;
    private final Enum<?>[] tokenValues;
    private final int tokenCount;

    public EnumCodec(Class<?> type) {
      this.type = type;
      Enum<?>[] constants = (Enum<?>[]) type.getEnumConstants();
      nameHashes = new long[constants.length];
      tokenPrefixes = new long[constants.length];
      tokenMasks = new long[constants.length];
      tokenSuffixes = new int[constants.length];
      tokenSuffixLengths = new byte[constants.length];
      tokenLengths = new int[constants.length];
      values = new Enum<?>[constants.length];
      tokenValues = new Enum<?>[constants.length];
      int localTokenCount = 0;
      for (int i = 0; i < constants.length; i++) {
        Enum<?> constant = constants[i];
        String name = constant.name();
        nameHashes[i] = JsonFieldNameHash.hash(name);
        values[i] = constant;
        String token = "\"" + name + "\"";
        if (JsonAsciiToken.isPackable(token)) {
          int tokenLength = token.length();
          tokenPrefixes[localTokenCount] = JsonAsciiToken.prefix(token);
          tokenMasks[localTokenCount] = JsonAsciiToken.prefixMask(tokenLength);
          tokenSuffixes[localTokenCount] = JsonAsciiToken.suffix(token);
          tokenSuffixLengths[localTokenCount] = (byte) JsonAsciiToken.suffixLength(tokenLength);
          tokenLengths[localTokenCount] = tokenLength;
          tokenValues[localTokenCount] = constant;
          localTokenCount++;
        }
      }
      tokenCount = localTokenCount;
    }

    @Override
    public void writeString(StringJsonWriter writer, Enum<?> value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.name());
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Enum<?> value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.name());
      }
    }

    @Override
    public Enum<?> readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : (Enum<?>) readLatin1Enum(reader);
    }

    @Override
    public Enum<?> readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : (Enum<?>) readUtf16Enum(reader);
    }

    @Override
    public Enum<?> readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : (Enum<?>) readUtf8Enum(reader);
    }

    public Object readLatin1Enum(Latin1JsonReader reader) {
      return enumValue(reader.readPackedStringHash());
    }

    public Object readNextLatin1Enum(Latin1JsonReader reader) {
      Object value = readDirectLatin1EnumToken(reader);
      if (value != null) {
        return value;
      }
      return enumValue(reader.readNextPackedStringHash());
    }

    public Object readLatin1EnumToken(Latin1JsonReader reader) {
      Object value = readDirectLatin1EnumToken(reader);
      if (value != null) {
        return value;
      }
      return readLatin1EnumHashToken(reader);
    }

    public Object readLatin1EnumHashToken(Latin1JsonReader reader) {
      return enumValue(reader.readPackedStringHashTokenValue());
    }

    public Object readUtf16Enum(Utf16JsonReader reader) {
      return enumValue(reader.readPackedStringHash());
    }

    public Object readNextUtf16Enum(Utf16JsonReader reader) {
      return enumValue(reader.readNextPackedStringHash());
    }

    public Object readUtf8Enum(Utf8JsonReader reader) {
      return enumValue(reader.readPackedStringHash());
    }

    public Object readNextUtf8Enum(Utf8JsonReader reader) {
      Object value = readDirectUtf8EnumToken(reader);
      if (value != null) {
        return value;
      }
      return enumValue(reader.readNextPackedStringHash());
    }

    public Object readUtf8EnumToken(Utf8JsonReader reader) {
      Object value = readDirectUtf8EnumToken(reader);
      if (value != null) {
        return value;
      }
      return readUtf8EnumHashToken(reader);
    }

    public Object readUtf8EnumHashToken(Utf8JsonReader reader) {
      return enumValue(reader.readPackedStringHashTokenValue());
    }

    private Enum<?> enumValue(long nameHash) {
      long[] localHashes = nameHashes;
      for (int i = 0; i < localHashes.length; i++) {
        if (localHashes[i] == nameHash) {
          return values[i];
        }
      }
      throw new ForyJsonException("Unknown enum value for " + type);
    }

    private Object readDirectLatin1EnumToken(Latin1JsonReader reader) {
      for (int i = 0; i < tokenCount; i++) {
        boolean matched;
        switch (tokenSuffixLengths[i]) {
          case 0:
            matched =
                reader.tryReadNextStringToken0(tokenPrefixes[i], tokenMasks[i], tokenLengths[i]);
            break;
          case 1:
            matched =
                reader.tryReadNextStringToken1(
                    tokenPrefixes[i], tokenMasks[i], tokenSuffixes[i], tokenLengths[i]);
            break;
          case 2:
            matched =
                reader.tryReadNextStringToken2(
                    tokenPrefixes[i], tokenMasks[i], tokenSuffixes[i], tokenLengths[i]);
            break;
          default:
            matched =
                reader.tryReadNextStringToken3(
                    tokenPrefixes[i], tokenMasks[i], tokenSuffixes[i], tokenLengths[i]);
            break;
        }
        if (matched) {
          return tokenValues[i];
        }
      }
      return null;
    }

    private Object readDirectUtf8EnumToken(Utf8JsonReader reader) {
      for (int i = 0; i < tokenCount; i++) {
        boolean matched;
        switch (tokenSuffixLengths[i]) {
          case 0:
            matched =
                reader.tryReadNextStringToken0(tokenPrefixes[i], tokenMasks[i], tokenLengths[i]);
            break;
          case 1:
            matched =
                reader.tryReadNextStringToken1(
                    tokenPrefixes[i], tokenMasks[i], tokenSuffixes[i], tokenLengths[i]);
            break;
          case 2:
            matched =
                reader.tryReadNextStringToken2(
                    tokenPrefixes[i], tokenMasks[i], tokenSuffixes[i], tokenLengths[i]);
            break;
          default:
            matched =
                reader.tryReadNextStringToken3(
                    tokenPrefixes[i], tokenMasks[i], tokenSuffixes[i], tokenLengths[i]);
            break;
        }
        if (matched) {
          return tokenValues[i];
        }
      }
      return null;
    }
  }
}

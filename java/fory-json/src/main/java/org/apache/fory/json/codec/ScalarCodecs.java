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
import java.nio.file.Paths;
import java.time.DateTimeException;
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
import java.time.temporal.TemporalAccessor;
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
import org.apache.fory.json.meta.JsonFieldAccessor;
import org.apache.fory.json.meta.JsonFieldNameHash;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.JsonWriter;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.type.BFloat16;
import org.apache.fory.type.Float16;

public final class ScalarCodecs {
  private static final DateTimeFormatter YEAR_MONTH_FORMATTER =
      DateTimeFormatter.ofPattern("uuuu-MM");
  private static final DateTimeFormatter MONTH_DAY_FORMATTER =
      DateTimeFormatter.ofPattern("--MM-dd");

  private ScalarCodecs() {}

  public static final class NaturalCodec extends AbstractJsonCodec {
    public static final NaturalCodec INSTANCE = new NaturalCodec();

    private NaturalCodec() {}

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      JsonTypeInfo typeInfo = resolver.getRuntimeTypeInfo(value.getClass());
      typeInfo.codec().write(writer, value, resolver);
    }

    @Override
    void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      JsonTypeInfo typeInfo = resolver.getRuntimeTypeInfo(value.getClass());
      typeInfo.codec().writeString(writer, value, resolver);
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      JsonTypeInfo typeInfo = resolver.getRuntimeTypeInfo(value.getClass());
      typeInfo.codec().writeUtf8(writer, value, resolver);
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      char token = reader.peekToken();
      if (token == '"') {
        return reader.readString();
      } else if (token == '{') {
        return MapCodec.readUntyped(reader, resolver);
      } else if (token == '[') {
        return CollectionCodec.readUntyped(reader, resolver);
      } else if (token == 't' || token == 'f') {
        return reader.readBoolean();
      } else if (token == 'n') {
        reader.readNull();
        return null;
      }
      return reader.readNumber();
    }
  }

  public static final class StringCodec extends AbstractJsonCodec {
    public static final StringCodec INSTANCE = new StringCodec();

    private StringCodec() {}

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeString((String) value);
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeString((String) value);
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      return reader.readString();
    }
  }

  public static final class CharSequenceCodec extends AbstractJsonCodec {
    public static final CharSequenceCodec INSTANCE = new CharSequenceCodec();

    private CharSequenceCodec() {}

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeString((CharSequence) value);
    }

    @Override
    void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeString((CharSequence) value);
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeString((CharSequence) value);
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      return reader.readCharSequence();
    }
  }

  public static final class VoidCodec extends AbstractJsonCodec {
    public static final VoidCodec INSTANCE = new VoidCodec();

    @Override
    public Object read(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.readNull();
      return null;
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeNull();
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeNull();
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.readNull();
      return null;
    }
  }

  public static final class BooleanCodec extends AbstractJsonCodec {
    public static final BooleanCodec INSTANCE = new BooleanCodec();

    private BooleanCodec() {}

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeBoolean((Boolean) value);
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeBoolean((Boolean) value);
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      return reader.readBoolean();
    }

    @Override
    public void readField(
        JsonReader reader,
        Object object,
        JsonFieldAccessor accessor,
        JsonTypeInfo typeInfo,
        JsonTypeResolver resolver) {
      if (reader.peekNull()) {
        readFieldDefault(reader, object, accessor, typeInfo, resolver);
      } else if (typeInfo.primitive()) {
        accessor.putBoolean(object, reader.readBoolean());
      } else {
        accessor.putObject(object, reader.readBoolean());
      }
    }
  }

  public static final class IntCodec extends AbstractJsonCodec {
    public static final IntCodec INSTANCE = new IntCodec();

    private IntCodec() {}

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeInt((Integer) value);
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeInt((Integer) value);
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      return reader.readInt();
    }

    @Override
    public void readField(
        JsonReader reader,
        Object object,
        JsonFieldAccessor accessor,
        JsonTypeInfo typeInfo,
        JsonTypeResolver resolver) {
      if (reader.peekNull()) {
        readFieldDefault(reader, object, accessor, typeInfo, resolver);
      } else if (typeInfo.primitive()) {
        accessor.putInt(object, reader.readInt());
      } else {
        accessor.putObject(object, reader.readInt());
      }
    }
  }

  public static final class LongCodec extends AbstractJsonCodec {
    public static final LongCodec INSTANCE = new LongCodec();

    private LongCodec() {}

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeLong((Long) value);
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeLong((Long) value);
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      return reader.readLong();
    }

    @Override
    public void readField(
        JsonReader reader,
        Object object,
        JsonFieldAccessor accessor,
        JsonTypeInfo typeInfo,
        JsonTypeResolver resolver) {
      if (reader.peekNull()) {
        readFieldDefault(reader, object, accessor, typeInfo, resolver);
      } else if (typeInfo.primitive()) {
        accessor.putLong(object, reader.readLong());
      } else {
        accessor.putObject(object, reader.readLong());
      }
    }
  }

  public static final class ShortCodec extends AbstractJsonCodec {
    public static final ShortCodec INSTANCE = new ShortCodec();

    private ShortCodec() {}

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeInt(((Short) value).intValue());
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeInt(((Short) value).intValue());
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      int value = reader.readInt();
      if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
        throw new ForyJsonException("Short overflow");
      }
      return (short) value;
    }

    @Override
    public void readField(
        JsonReader reader,
        Object object,
        JsonFieldAccessor accessor,
        JsonTypeInfo typeInfo,
        JsonTypeResolver resolver) {
      if (reader.peekNull()) {
        readFieldDefault(reader, object, accessor, typeInfo, resolver);
      } else if (typeInfo.primitive()) {
        int value = reader.readInt();
        if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
          throw new ForyJsonException("Short overflow");
        }
        accessor.putShort(object, (short) value);
      } else {
        int value = reader.readInt();
        if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
          throw new ForyJsonException("Short overflow");
        }
        accessor.putObject(object, (short) value);
      }
    }
  }

  public static final class ByteCodec extends AbstractJsonCodec {
    public static final ByteCodec INSTANCE = new ByteCodec();

    private ByteCodec() {}

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeInt(((Byte) value).intValue());
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeInt(((Byte) value).intValue());
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      int value = reader.readInt();
      if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
        throw new ForyJsonException("Byte overflow");
      }
      return (byte) value;
    }

    @Override
    public void readField(
        JsonReader reader,
        Object object,
        JsonFieldAccessor accessor,
        JsonTypeInfo typeInfo,
        JsonTypeResolver resolver) {
      if (reader.peekNull()) {
        readFieldDefault(reader, object, accessor, typeInfo, resolver);
      } else if (typeInfo.primitive()) {
        int value = reader.readInt();
        if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
          throw new ForyJsonException("Byte overflow");
        }
        accessor.putByte(object, (byte) value);
      } else {
        int value = reader.readInt();
        if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
          throw new ForyJsonException("Byte overflow");
        }
        accessor.putObject(object, (byte) value);
      }
    }
  }

  public static final class FloatCodec extends AbstractJsonCodec {
    public static final FloatCodec INSTANCE = new FloatCodec();

    private FloatCodec() {}

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeFloat((Float) value);
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeFloat((Float) value);
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      return reader.readFloat();
    }

    @Override
    public void readField(
        JsonReader reader,
        Object object,
        JsonFieldAccessor accessor,
        JsonTypeInfo typeInfo,
        JsonTypeResolver resolver) {
      if (reader.peekNull()) {
        readFieldDefault(reader, object, accessor, typeInfo, resolver);
      } else if (typeInfo.primitive()) {
        accessor.putFloat(object, reader.readFloat());
      } else {
        accessor.putObject(object, reader.readFloat());
      }
    }
  }

  public static final class DoubleCodec extends AbstractJsonCodec {
    public static final DoubleCodec INSTANCE = new DoubleCodec();

    private DoubleCodec() {}

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeDouble((Double) value);
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeDouble((Double) value);
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      return reader.readDouble();
    }

    @Override
    public void readField(
        JsonReader reader,
        Object object,
        JsonFieldAccessor accessor,
        JsonTypeInfo typeInfo,
        JsonTypeResolver resolver) {
      if (reader.peekNull()) {
        readFieldDefault(reader, object, accessor, typeInfo, resolver);
      } else if (typeInfo.primitive()) {
        accessor.putDouble(object, reader.readDouble());
      } else {
        accessor.putObject(object, reader.readDouble());
      }
    }
  }

  public static final class CharCodec extends AbstractJsonCodec {
    public static final CharCodec INSTANCE = new CharCodec();

    private CharCodec() {}

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeChar((Character) value);
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeChar((Character) value);
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      return reader.readChar();
    }

    @Override
    public void readField(
        JsonReader reader,
        Object object,
        JsonFieldAccessor accessor,
        JsonTypeInfo typeInfo,
        JsonTypeResolver resolver) {
      if (reader.peekNull()) {
        readFieldDefault(reader, object, accessor, typeInfo, resolver);
      } else {
        char value = (Character) readNonNull(reader, typeInfo, resolver);
        if (typeInfo.primitive()) {
          accessor.putChar(object, value);
        } else {
          accessor.putObject(object, value);
        }
      }
    }
  }

  public abstract static class StringValueCodec extends AbstractJsonCodec {
    @Override
    final void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeString(toJsonString(value));
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeString(toJsonString(value));
    }

    @Override
    final Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      return readStringValue(reader.readString(), typeInfo);
    }

    final Object readStringValue(String value, JsonTypeInfo typeInfo) {
      try {
        return fromJsonString(value);
      } catch (ForyJsonException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new ForyJsonException(
            "Invalid " + typeInfo.rawType().getName() + " JSON string: " + value, e);
      }
    }

    abstract String toJsonString(Object value);

    abstract Object fromJsonString(String value);
  }

  public static final class NumberCodec extends AbstractJsonCodec {
    public static final NumberCodec INSTANCE = new NumberCodec();

    private NumberCodec() {}

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writeNumberValue(writer, (Number) value);
    }

    @Override
    void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      writeNumberValue(writer, (Number) value);
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writeNumberValue(writer, (Number) value);
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      return reader.readNumber();
    }

    private static void writeNumberValue(JsonWriter writer, Number value) {
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

  public static final class BigIntegerCodec extends AbstractJsonCodec {
    public static final BigIntegerCodec INSTANCE = new BigIntegerCodec();

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeBigInteger((BigInteger) value);
    }

    @Override
    void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeBigInteger((BigInteger) value);
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeBigInteger((BigInteger) value);
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      return reader.readBigInteger();
    }
  }

  public static final class BigDecimalCodec extends AbstractJsonCodec {
    public static final BigDecimalCodec INSTANCE = new BigDecimalCodec();

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeBigDecimal((BigDecimal) value);
    }

    @Override
    void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeBigDecimal((BigDecimal) value);
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeBigDecimal((BigDecimal) value);
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      return reader.readBigDecimal();
    }

    @Override
    public Object readUtf8(
        Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readBigDecimal();
    }

    @Override
    public Object readLatin1(
        Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readBigDecimal();
    }

    @Override
    public Object readUtf16(
        Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readBigDecimal();
    }
  }

  public static final class Float16Codec extends AbstractJsonCodec {
    public static final Float16Codec INSTANCE = new Float16Codec();

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeFloat(((Float16) value).floatValue());
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeFloat(((Float16) value).floatValue());
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      return Float16.valueOf(reader.readFloat());
    }
  }

  public static final class BFloat16Codec extends AbstractJsonCodec {
    public static final BFloat16Codec INSTANCE = new BFloat16Codec();

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeFloat(((BFloat16) value).floatValue());
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeFloat(((BFloat16) value).floatValue());
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      return BFloat16.valueOf(reader.readFloat());
    }
  }

  public static final class BitSetCodec extends AbstractJsonCodec {
    public static final BitSetCodec INSTANCE = new BitSetCodec();

    private BitSetCodec() {}

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writeBitSet(writer, (BitSet) value);
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writeBitSet(writer, (BitSet) value);
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      return readBitSet(reader);
    }

    private static void writeBitSet(JsonWriter writer, BitSet value) {
      long[] words = value.toLongArray();
      writer.writeArrayStart();
      for (int i = 0; i < words.length; i++) {
        writer.writeComma(i);
        writer.writeLong(words[i]);
      }
      writer.writeArrayEnd();
    }

    private static BitSet readBitSet(JsonReader reader) {
      reader.enterDepth();
      try {
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
        return BitSet.valueOf(words);
      } finally {
        reader.exitDepth();
      }
    }
  }

  public static final class StringBuilderCodec extends StringValueCodec {
    public static final StringBuilderCodec INSTANCE = new StringBuilderCodec();

    @Override
    void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeString((StringBuilder) value);
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeString((StringBuilder) value);
    }

    @Override
    String toJsonString(Object value) {
      return value.toString();
    }

    @Override
    Object fromJsonString(String value) {
      return new StringBuilder(value);
    }
  }

  public static final class StringBufferCodec extends StringValueCodec {
    public static final StringBufferCodec INSTANCE = new StringBufferCodec();

    @Override
    void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeString((StringBuffer) value);
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeString((StringBuffer) value);
    }

    @Override
    String toJsonString(Object value) {
      return value.toString();
    }

    @Override
    Object fromJsonString(String value) {
      return new StringBuffer(value);
    }
  }

  public static final class FileCodec extends StringValueCodec {
    public static final FileCodec INSTANCE = new FileCodec();

    @Override
    String toJsonString(Object value) {
      return ((File) value).getPath();
    }

    @Override
    Object fromJsonString(String value) {
      return new File(value);
    }
  }

  public static final class PathCodec extends StringValueCodec {
    public static final PathCodec INSTANCE = new PathCodec();

    @Override
    String toJsonString(Object value) {
      return value.toString();
    }

    @Override
    Object fromJsonString(String value) {
      return Paths.get(value);
    }
  }

  public static final class CurrencyCodec extends StringValueCodec {
    public static final CurrencyCodec INSTANCE = new CurrencyCodec();

    @Override
    String toJsonString(Object value) {
      return ((Currency) value).getCurrencyCode();
    }

    @Override
    Object fromJsonString(String value) {
      return Currency.getInstance(value);
    }
  }

  public static final class UriCodec extends StringValueCodec {
    public static final UriCodec INSTANCE = new UriCodec();

    @Override
    String toJsonString(Object value) {
      return value.toString();
    }

    @Override
    Object fromJsonString(String value) {
      return URI.create(value);
    }
  }

  public static final class PatternCodec extends StringValueCodec {
    public static final PatternCodec INSTANCE = new PatternCodec();

    @Override
    String toJsonString(Object value) {
      return ((Pattern) value).pattern();
    }

    @Override
    Object fromJsonString(String value) {
      return Pattern.compile(value);
    }
  }

  public static final class UuidCodec extends StringValueCodec {
    public static final UuidCodec INSTANCE = new UuidCodec();

    @Override
    String toJsonString(Object value) {
      return value.toString();
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeUuid((UUID) value);
    }

    @Override
    void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeUuid((UUID) value);
    }

    @Override
    Object fromJsonString(String value) {
      return UUID.fromString(value);
    }

    @Override
    public Object readUtf8(
        Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readUuid();
    }

    @Override
    public Object readLatin1(
        Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readUuid();
    }

    @Override
    public Object readUtf16(
        Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readUuid();
    }
  }

  public static final class LocaleCodec extends StringValueCodec {
    public static final LocaleCodec INSTANCE = new LocaleCodec();

    @Override
    String toJsonString(Object value) {
      return ((Locale) value).toLanguageTag();
    }

    @Override
    Object fromJsonString(String value) {
      return Locale.forLanguageTag(value);
    }
  }

  public static final class CharsetCodec extends StringValueCodec {
    public static final CharsetCodec INSTANCE = new CharsetCodec();

    @Override
    String toJsonString(Object value) {
      return ((Charset) value).name();
    }

    @Override
    Object fromJsonString(String value) {
      return Charset.forName(value);
    }
  }

  public static final class DateCodec extends AbstractJsonCodec {
    public static final DateCodec INSTANCE = new DateCodec();

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeLong(((Date) value).getTime());
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeLong(((Date) value).getTime());
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      return new Date(reader.readLong());
    }
  }

  public static final class CalendarCodec extends AbstractJsonCodec {
    public static final CalendarCodec INSTANCE = new CalendarCodec();

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeLong(((Calendar) value).getTimeInMillis());
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeLong(((Calendar) value).getTimeInMillis());
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      Calendar calendar = new GregorianCalendar();
      calendar.setTimeInMillis(reader.readLong());
      return calendar;
    }
  }

  public static final class TimeZoneCodec extends StringValueCodec {
    public static final TimeZoneCodec INSTANCE = new TimeZoneCodec();

    @Override
    String toJsonString(Object value) {
      return ((TimeZone) value).getID();
    }

    @Override
    Object fromJsonString(String value) {
      return TimeZone.getTimeZone(value);
    }
  }

  public static final class LocalDateCodec extends StringValueCodec {
    public static final LocalDateCodec INSTANCE = new LocalDateCodec();

    @Override
    String toJsonString(Object value) {
      return value.toString();
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeLocalDate((LocalDate) value);
    }

    @Override
    void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeLocalDate((LocalDate) value);
    }

    @Override
    Object fromJsonString(String value) {
      return parseIsoLocalDate(value);
    }

    @Override
    public Object readUtf8(
        Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readIsoLocalDate();
    }

    @Override
    public Object readLatin1(
        Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readIsoLocalDate();
    }

    @Override
    public Object readUtf16(
        Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readIsoLocalDate();
    }
  }

  public static final class LocalTimeCodec extends StringValueCodec {
    public static final LocalTimeCodec INSTANCE = new LocalTimeCodec();

    @Override
    String toJsonString(Object value) {
      return value.toString();
    }

    @Override
    void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeTemporal((LocalTime) value, DateTimeFormatter.ISO_LOCAL_TIME);
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeTemporal((LocalTime) value, DateTimeFormatter.ISO_LOCAL_TIME);
    }

    @Override
    Object fromJsonString(String value) {
      return LocalTime.parse(value);
    }

    @Override
    public Object readUtf8(
        Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readIsoLocalTime();
    }

    @Override
    public Object readLatin1(
        Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readIsoLocalTime();
    }

    @Override
    public Object readUtf16(
        Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readIsoLocalTime();
    }
  }

  public static final class LocalDateTimeCodec extends StringValueCodec {
    public static final LocalDateTimeCodec INSTANCE = new LocalDateTimeCodec();

    @Override
    String toJsonString(Object value) {
      return value.toString();
    }

    @Override
    void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeTemporal((LocalDateTime) value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeTemporal((LocalDateTime) value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    @Override
    Object fromJsonString(String value) {
      return LocalDateTime.parse(value);
    }

    @Override
    public Object readUtf8(
        Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readIsoLocalDateTime();
    }

    @Override
    public Object readLatin1(
        Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readIsoLocalDateTime();
    }

    @Override
    public Object readUtf16(
        Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readIsoLocalDateTime();
    }
  }

  public static final class InstantCodec extends StringValueCodec {
    public static final InstantCodec INSTANCE = new InstantCodec();

    @Override
    String toJsonString(Object value) {
      return value.toString();
    }

    @Override
    void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeTemporal((Instant) value, DateTimeFormatter.ISO_INSTANT);
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeTemporal((Instant) value, DateTimeFormatter.ISO_INSTANT);
    }

    @Override
    Object fromJsonString(String value) {
      return Instant.parse(value);
    }

    @Override
    public Object readUtf8(
        Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readIsoInstant();
    }

    @Override
    public Object readLatin1(
        Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readIsoInstant();
    }

    @Override
    public Object readUtf16(
        Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readIsoInstant();
    }
  }

  public static final class DurationCodec extends StringValueCodec {
    public static final DurationCodec INSTANCE = new DurationCodec();

    @Override
    String toJsonString(Object value) {
      return value.toString();
    }

    @Override
    void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeDuration((Duration) value);
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeDuration((Duration) value);
    }

    @Override
    Object fromJsonString(String value) {
      return Duration.parse(value);
    }

    @Override
    public Object readUtf8(
        Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readDuration();
    }

    @Override
    public Object readLatin1(
        Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readDuration();
    }

    @Override
    public Object readUtf16(
        Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readDuration();
    }
  }

  public static final class ZoneOffsetCodec extends StringValueCodec {
    public static final ZoneOffsetCodec INSTANCE = new ZoneOffsetCodec();

    @Override
    String toJsonString(Object value) {
      return value.toString();
    }

    @Override
    void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeString(((ZoneOffset) value).getId());
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeString(((ZoneOffset) value).getId());
    }

    @Override
    Object fromJsonString(String value) {
      return ZoneOffset.of(value);
    }

    @Override
    public Object readUtf8(
        Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readZoneOffset();
    }

    @Override
    public Object readLatin1(
        Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readZoneOffset();
    }

    @Override
    public Object readUtf16(
        Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readZoneOffset();
    }
  }

  public static final class ZoneIdCodec extends StringValueCodec {
    public static final ZoneIdCodec INSTANCE = new ZoneIdCodec();

    @Override
    String toJsonString(Object value) {
      return ((ZoneId) value).getId();
    }

    @Override
    void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeString(((ZoneId) value).getId());
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeString(((ZoneId) value).getId());
    }

    @Override
    Object fromJsonString(String value) {
      return ZoneId.of(value);
    }
  }

  public static final class ZonedDateTimeCodec extends StringValueCodec {
    public static final ZonedDateTimeCodec INSTANCE = new ZonedDateTimeCodec();

    @Override
    String toJsonString(Object value) {
      return value.toString();
    }

    @Override
    void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeTemporal((ZonedDateTime) value, DateTimeFormatter.ISO_ZONED_DATE_TIME);
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeTemporal((ZonedDateTime) value, DateTimeFormatter.ISO_ZONED_DATE_TIME);
    }

    @Override
    Object fromJsonString(String value) {
      return ZonedDateTime.parse(value);
    }

    @Override
    public Object readUtf8(
        Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readZonedDateTime();
    }

    @Override
    public Object readLatin1(
        Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readZonedDateTime();
    }

    @Override
    public Object readUtf16(
        Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readZonedDateTime();
    }
  }

  public static final class YearCodec extends StringValueCodec {
    public static final YearCodec INSTANCE = new YearCodec();

    @Override
    String toJsonString(Object value) {
      return value.toString();
    }

    @Override
    void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeYear((Year) value);
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeYear((Year) value);
    }

    @Override
    Object fromJsonString(String value) {
      return JsonReader.parseYearValue(value);
    }

    @Override
    public Object readUtf8(
        Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readYear();
    }

    @Override
    public Object readLatin1(
        Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readYear();
    }

    @Override
    public Object readUtf16(
        Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readYear();
    }
  }

  public static final class YearMonthCodec extends StringValueCodec {
    public static final YearMonthCodec INSTANCE = new YearMonthCodec();

    @Override
    String toJsonString(Object value) {
      return value.toString();
    }

    @Override
    void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeTemporal((YearMonth) value, YEAR_MONTH_FORMATTER);
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeTemporal((YearMonth) value, YEAR_MONTH_FORMATTER);
    }

    @Override
    Object fromJsonString(String value) {
      return YearMonth.parse(value);
    }

    @Override
    public Object readUtf8(
        Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readYearMonth();
    }

    @Override
    public Object readLatin1(
        Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readYearMonth();
    }

    @Override
    public Object readUtf16(
        Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readYearMonth();
    }
  }

  public static final class MonthDayCodec extends StringValueCodec {
    public static final MonthDayCodec INSTANCE = new MonthDayCodec();

    @Override
    String toJsonString(Object value) {
      return value.toString();
    }

    @Override
    void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeTemporal((MonthDay) value, MONTH_DAY_FORMATTER);
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeTemporal((MonthDay) value, MONTH_DAY_FORMATTER);
    }

    @Override
    Object fromJsonString(String value) {
      return MonthDay.parse(value);
    }

    @Override
    public Object readUtf8(
        Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readMonthDay();
    }

    @Override
    public Object readLatin1(
        Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readMonthDay();
    }

    @Override
    public Object readUtf16(
        Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readMonthDay();
    }
  }

  public static final class PeriodCodec extends StringValueCodec {
    public static final PeriodCodec INSTANCE = new PeriodCodec();

    @Override
    String toJsonString(Object value) {
      return value.toString();
    }

    @Override
    void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writePeriod((Period) value);
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writePeriod((Period) value);
    }

    @Override
    Object fromJsonString(String value) {
      return Period.parse(value);
    }

    @Override
    public Object readUtf8(
        Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readPeriod();
    }

    @Override
    public Object readLatin1(
        Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readPeriod();
    }

    @Override
    public Object readUtf16(
        Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readPeriod();
    }
  }

  public static final class OffsetTimeCodec extends StringValueCodec {
    public static final OffsetTimeCodec INSTANCE = new OffsetTimeCodec();

    @Override
    String toJsonString(Object value) {
      return value.toString();
    }

    @Override
    void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeTemporal((OffsetTime) value, DateTimeFormatter.ISO_OFFSET_TIME);
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeTemporal((OffsetTime) value, DateTimeFormatter.ISO_OFFSET_TIME);
    }

    @Override
    Object fromJsonString(String value) {
      return OffsetTime.parse(value);
    }

    @Override
    public Object readUtf8(
        Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readOffsetTime();
    }

    @Override
    public Object readLatin1(
        Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readOffsetTime();
    }

    @Override
    public Object readUtf16(
        Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readOffsetTime();
    }
  }

  public static final class OffsetDateTimeCodec extends StringValueCodec {
    public static final OffsetDateTimeCodec INSTANCE = new OffsetDateTimeCodec();

    @Override
    String toJsonString(Object value) {
      return value.toString();
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeOffsetDateTime((OffsetDateTime) value);
    }

    @Override
    void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeOffsetDateTime((OffsetDateTime) value);
    }

    @Override
    Object fromJsonString(String value) {
      return parseIsoOffsetDateTime(value);
    }

    @Override
    public Object readUtf8(
        Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readIsoOffsetDateTime();
    }

    @Override
    public Object readLatin1(
        Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readIsoOffsetDateTime();
    }

    @Override
    public Object readUtf16(
        Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return reader.readIsoOffsetDateTime();
    }
  }

  private abstract static class ChronoDateCodec extends StringValueCodec {
    private final DateTimeFormatter formatter;
    private final String typeName;

    ChronoDateCodec(DateTimeFormatter formatter, String typeName) {
      this.formatter = formatter;
      this.typeName = typeName;
    }

    @Override
    String toJsonString(Object value) {
      return formatter.format((TemporalAccessor) value);
    }

    @Override
    Object fromJsonString(String value) {
      try {
        return fromParsed(formatter.parse(value));
      } catch (DateTimeException e) {
        throw new ForyJsonException("Invalid " + typeName + " JSON string: " + value, e);
      }
    }

    abstract Object fromParsed(TemporalAccessor value);
  }

  public static final class HijrahDateCodec extends ChronoDateCodec {
    public static final HijrahDateCodec INSTANCE = new HijrahDateCodec();

    private HijrahDateCodec() {
      super(
          DateTimeFormatter.ISO_LOCAL_DATE.withChronology(HijrahChronology.INSTANCE),
          HijrahDate.class.getName());
    }

    @Override
    Object fromParsed(TemporalAccessor value) {
      return HijrahDate.from(value);
    }
  }

  public static final class JapaneseDateCodec extends ChronoDateCodec {
    public static final JapaneseDateCodec INSTANCE = new JapaneseDateCodec();

    private JapaneseDateCodec() {
      super(
          DateTimeFormatter.ISO_LOCAL_DATE.withChronology(JapaneseChronology.INSTANCE),
          JapaneseDate.class.getName());
    }

    @Override
    Object fromParsed(TemporalAccessor value) {
      return JapaneseDate.from(value);
    }
  }

  public static final class MinguoDateCodec extends ChronoDateCodec {
    public static final MinguoDateCodec INSTANCE = new MinguoDateCodec();

    private MinguoDateCodec() {
      super(
          DateTimeFormatter.ISO_LOCAL_DATE.withChronology(MinguoChronology.INSTANCE),
          MinguoDate.class.getName());
    }

    @Override
    Object fromParsed(TemporalAccessor value) {
      return MinguoDate.from(value);
    }
  }

  public static final class ThaiBuddhistDateCodec extends ChronoDateCodec {
    public static final ThaiBuddhistDateCodec INSTANCE = new ThaiBuddhistDateCodec();

    private ThaiBuddhistDateCodec() {
      super(
          DateTimeFormatter.ISO_LOCAL_DATE.withChronology(ThaiBuddhistChronology.INSTANCE),
          ThaiBuddhistDate.class.getName());
    }

    @Override
    Object fromParsed(TemporalAccessor value) {
      return ThaiBuddhistDate.from(value);
    }
  }

  private static LocalDate parseIsoLocalDate(String value) {
    int length = value.length();
    if (length >= 10
        && (length == 10 || value.charAt(10) == 'T')
        && value.charAt(4) == '-'
        && value.charAt(7) == '-') {
      try {
        return LocalDate.of(parse4(value, 0), parse2(value, 5), parse2(value, 8));
      } catch (RuntimeException e) {
        if (length > 10 && value.charAt(10) == 'T') {
          return LocalDate.parse(value.substring(0, 10));
        }
        return LocalDate.parse(value);
      }
    }
    return LocalDate.parse(value);
  }

  private static OffsetDateTime parseIsoOffsetDateTime(String value) {
    try {
      // java.time emits these ISO forms, and parsing them directly avoids DateTimeFormatter's
      // parsed-field maps on JSON scalar hot paths. Uncommon forms still use the JDK parser.
      return parseIsoOffsetDateTimeFast(value);
    } catch (RuntimeException e) {
      return OffsetDateTime.parse(value);
    }
  }

  private static OffsetDateTime parseIsoOffsetDateTimeFast(String value) {
    int length = value.length();
    if (length < 17
        || value.charAt(4) != '-'
        || value.charAt(7) != '-'
        || value.charAt(10) != 'T'
        || value.charAt(13) != ':') {
      throw new IllegalArgumentException();
    }
    int year = parse4(value, 0);
    int month = parse2(value, 5);
    int day = parse2(value, 8);
    int hour = parse2(value, 11);
    int minute = parse2(value, 14);
    int second = 0;
    int nano = 0;
    int index = 16;
    if (index < length && value.charAt(index) == ':') {
      second = parse2(value, index + 1);
      index += 3;
      if (index < length && value.charAt(index) == '.') {
        int fractionStart = index + 1;
        int fractionEnd = fractionStart;
        while (fractionEnd < length && isDigit(value.charAt(fractionEnd))) {
          fractionEnd++;
        }
        if (fractionEnd == fractionStart || fractionEnd - fractionStart > 9) {
          throw new IllegalArgumentException();
        }
        nano = parseNano(value, fractionStart, fractionEnd);
        index = fractionEnd;
      }
    }
    int offsetSeconds = parseOffsetSeconds(value, index);
    return OffsetDateTime.of(
        year, month, day, hour, minute, second, nano, ZoneOffset.ofTotalSeconds(offsetSeconds));
  }

  private static int parseOffsetSeconds(String value, int index) {
    int length = value.length();
    char offset = value.charAt(index);
    if (offset == 'Z') {
      if (index + 1 != length) {
        throw new IllegalArgumentException();
      }
      return 0;
    }
    if (offset != '+' && offset != '-') {
      throw new IllegalArgumentException();
    }
    if (index + 6 > length || value.charAt(index + 3) != ':') {
      throw new IllegalArgumentException();
    }
    int hour = parse2(value, index + 1);
    int minute = parse2(value, index + 4);
    int second = 0;
    int end = index + 6;
    if (end < length) {
      if (end + 3 != length || value.charAt(end) != ':') {
        throw new IllegalArgumentException();
      }
      second = parse2(value, end + 1);
    }
    int total = hour * 3600 + minute * 60 + second;
    return offset == '-' ? -total : total;
  }

  private static int parseNano(String value, int start, int end) {
    int nano = 0;
    for (int i = start; i < end; i++) {
      nano = nano * 10 + value.charAt(i) - '0';
    }
    for (int i = end - start; i < 9; i++) {
      nano *= 10;
    }
    return nano;
  }

  private static int parse4(String value, int index) {
    return parse2(value, index) * 100 + parse2(value, index + 2);
  }

  private static int parse2(String value, int index) {
    int high = value.charAt(index) - '0';
    int low = value.charAt(index + 1) - '0';
    if (high < 0 || high > 9 || low < 0 || low > 9) {
      throw new IllegalArgumentException();
    }
    return high * 10 + low;
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  public static final class AtomicBooleanCodec extends AbstractJsonCodec {
    public static final AtomicBooleanCodec INSTANCE = new AtomicBooleanCodec();

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeBoolean(((AtomicBoolean) value).get());
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeBoolean(((AtomicBoolean) value).get());
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      return new AtomicBoolean(reader.readBoolean());
    }
  }

  public static final class AtomicIntegerCodec extends AbstractJsonCodec {
    public static final AtomicIntegerCodec INSTANCE = new AtomicIntegerCodec();

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeInt(((AtomicInteger) value).get());
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeInt(((AtomicInteger) value).get());
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      return new AtomicInteger(reader.readInt());
    }
  }

  public static final class AtomicLongCodec extends AbstractJsonCodec {
    public static final AtomicLongCodec INSTANCE = new AtomicLongCodec();

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeLong(((AtomicLong) value).get());
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeLong(((AtomicLong) value).get());
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      return new AtomicLong(reader.readLong());
    }
  }

  public static final class AtomicReferenceCodec extends AbstractJsonCodec {
    private final JsonTypeInfo valueTypeInfo;
    private JsonCodec valueCodec;

    public AtomicReferenceCodec(java.lang.reflect.Type valueType, JsonTypeResolver resolver) {
      Class<?> valueRawType = CodecUtils.rawType(valueType, Object.class);
      valueTypeInfo = resolver.getTypeInfo(valueType, valueRawType);
      valueCodec = valueTypeInfo.codec();
      resolver.registerJITNotifyCallback(valueCodec, codec -> valueCodec = codec);
    }

    @Override
    public Object read(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      Object value =
          reader.peekNull()
              ? readNullValue(reader)
              : valueCodec.read(reader, valueTypeInfo, resolver);
      return new AtomicReference<>(value);
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      return new AtomicReference<>(valueCodec.read(reader, valueTypeInfo, resolver));
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      valueCodec.write(writer, ((AtomicReference<?>) value).get(), resolver);
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      valueCodec.writeUtf8(writer, ((AtomicReference<?>) value).get(), resolver);
    }
  }

  public static final class AtomicIntegerArrayCodec extends AbstractJsonCodec {
    public static final AtomicIntegerArrayCodec INSTANCE = new AtomicIntegerArrayCodec();

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      AtomicIntegerArray array = (AtomicIntegerArray) value;
      writer.writeArrayStart();
      for (int i = 0, length = array.length(); i < length; i++) {
        writer.writeComma(i);
        writer.writeInt(array.get(i));
      }
      writer.writeArrayEnd();
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      AtomicIntegerArray array = (AtomicIntegerArray) value;
      writer.writeArrayStart();
      for (int i = 0, length = array.length(); i < length; i++) {
        writer.writeComma(i);
        writer.writeInt(array.get(i));
      }
      writer.writeArrayEnd();
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
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

  public static final class AtomicLongArrayCodec extends AbstractJsonCodec {
    public static final AtomicLongArrayCodec INSTANCE = new AtomicLongArrayCodec();

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      AtomicLongArray array = (AtomicLongArray) value;
      writer.writeArrayStart();
      for (int i = 0, length = array.length(); i < length; i++) {
        writer.writeComma(i);
        writer.writeLong(array.get(i));
      }
      writer.writeArrayEnd();
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      AtomicLongArray array = (AtomicLongArray) value;
      writer.writeArrayStart();
      for (int i = 0, length = array.length(); i < length; i++) {
        writer.writeComma(i);
        writer.writeLong(array.get(i));
      }
      writer.writeArrayEnd();
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
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

  public static final class AtomicReferenceArrayCodec extends AbstractJsonCodec {
    private final JsonTypeInfo valueTypeInfo;
    private JsonCodec valueCodec;

    public AtomicReferenceArrayCodec(java.lang.reflect.Type valueType, JsonTypeResolver resolver) {
      Class<?> valueRawType = CodecUtils.rawType(valueType, Object.class);
      valueTypeInfo = resolver.getTypeInfo(valueType, valueRawType);
      valueCodec = valueTypeInfo.codec();
      resolver.registerJITNotifyCallback(valueCodec, codec -> valueCodec = codec);
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      AtomicReferenceArray<?> array = (AtomicReferenceArray<?>) value;
      writer.writeArrayStart();
      for (int i = 0, length = array.length(); i < length; i++) {
        writer.writeComma(i);
        valueCodec.write(writer, array.get(i), resolver);
      }
      writer.writeArrayEnd();
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      AtomicReferenceArray<?> array = (AtomicReferenceArray<?>) value;
      writer.writeArrayStart();
      for (int i = 0, length = array.length(); i < length; i++) {
        writer.writeComma(i);
        valueCodec.writeUtf8(writer, array.get(i), resolver);
      }
      writer.writeArrayEnd();
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      reader.expect('[');
      if (reader.consume(']')) {
        reader.exitDepth();
        return new AtomicReferenceArray<>(0);
      }
      Object[] values = new Object[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = valueCodec.read(reader, valueTypeInfo, resolver);
      } while (reader.consume(','));
      reader.expect(']');
      reader.exitDepth();
      return new AtomicReferenceArray<>(Arrays.copyOf(values, size));
    }
  }

  public static final class OptionalCodec extends AbstractJsonCodec {
    private final JsonTypeInfo valueTypeInfo;
    private JsonCodec valueCodec;

    public OptionalCodec(java.lang.reflect.Type valueType, JsonTypeResolver resolver) {
      Class<?> valueRawType = CodecUtils.rawType(valueType, Object.class);
      valueTypeInfo = resolver.getTypeInfo(valueType, valueRawType);
      valueCodec = valueTypeInfo.codec();
      resolver.registerJITNotifyCallback(valueCodec, codec -> valueCodec = codec);
    }

    @Override
    public Object read(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.peekNull()) {
        reader.readNull();
        return Optional.empty();
      }
      return Optional.ofNullable(valueCodec.read(reader, valueTypeInfo, resolver));
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      return Optional.ofNullable(valueCodec.read(reader, valueTypeInfo, resolver));
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      Optional<?> optional = (Optional<?>) value;
      if (optional.isPresent()) {
        valueCodec.write(writer, optional.get(), resolver);
      } else {
        writer.writeNull();
      }
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      Optional<?> optional = (Optional<?>) value;
      if (optional.isPresent()) {
        valueCodec.writeUtf8(writer, optional.get(), resolver);
      } else {
        writer.writeNull();
      }
    }
  }

  public static final class OptionalIntCodec extends AbstractJsonCodec {
    public static final OptionalIntCodec INSTANCE = new OptionalIntCodec();

    @Override
    public Object read(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.peekNull()) {
        reader.readNull();
        return OptionalInt.empty();
      }
      return OptionalInt.of(reader.readInt());
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      OptionalInt optional = (OptionalInt) value;
      if (optional.isPresent()) {
        writer.writeInt(optional.getAsInt());
      } else {
        writer.writeNull();
      }
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writeNonNull(writer, value, resolver);
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      return OptionalInt.of(reader.readInt());
    }
  }

  public static final class OptionalLongCodec extends AbstractJsonCodec {
    public static final OptionalLongCodec INSTANCE = new OptionalLongCodec();

    @Override
    public Object read(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.peekNull()) {
        reader.readNull();
        return OptionalLong.empty();
      }
      return OptionalLong.of(reader.readLong());
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      OptionalLong optional = (OptionalLong) value;
      if (optional.isPresent()) {
        writer.writeLong(optional.getAsLong());
      } else {
        writer.writeNull();
      }
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writeNonNull(writer, value, resolver);
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      return OptionalLong.of(reader.readLong());
    }
  }

  public static final class OptionalDoubleCodec extends AbstractJsonCodec {
    public static final OptionalDoubleCodec INSTANCE = new OptionalDoubleCodec();

    @Override
    public Object read(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.peekNull()) {
        reader.readNull();
        return OptionalDouble.empty();
      }
      return OptionalDouble.of(reader.readDouble());
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      OptionalDouble optional = (OptionalDouble) value;
      if (optional.isPresent()) {
        writer.writeDouble(optional.getAsDouble());
      } else {
        writer.writeNull();
      }
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writeNonNull(writer, value, resolver);
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      return OptionalDouble.of(reader.readDouble());
    }
  }

  public static final class ByteBufferCodec extends AbstractJsonCodec {
    public static final ByteBufferCodec INSTANCE = new ByteBufferCodec();

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writeBuffer(writer, (ByteBuffer) value);
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writeBuffer(writer, (ByteBuffer) value);
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
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

    private void writeBuffer(JsonWriter writer, ByteBuffer value) {
      ByteBuffer buffer = value.duplicate();
      writer.writeArrayStart();
      int index = 0;
      while (buffer.hasRemaining()) {
        writer.writeComma(index++);
        writer.writeInt(buffer.get());
      }
      writer.writeArrayEnd();
    }
  }

  public static final class EnumCodec extends AbstractJsonCodec {
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
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeString(((Enum<?>) value).name());
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeString(((Enum<?>) value).name());
    }

    @Override
    public Object readLatin1(
        Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return readLatin1Enum(reader);
    }

    @Override
    public Object readUtf16(
        Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return readUtf16Enum(reader);
    }

    @Override
    public Object readUtf8(
        Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return readUtf8Enum(reader);
    }

    public Object readEnum(JsonReader reader) {
      return enumValue(reader.readStringHash());
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

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      return readEnum(reader);
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

  private static Object readNullValue(JsonReader reader) {
    reader.readNull();
    return null;
  }
}

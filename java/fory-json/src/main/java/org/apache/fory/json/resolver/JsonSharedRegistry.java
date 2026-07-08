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

package org.apache.fory.json.resolver;

import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Path;
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
import java.time.chrono.HijrahDate;
import java.time.chrono.JapaneseDate;
import java.time.chrono.MinguoDate;
import java.time.chrono.ThaiBuddhistDate;
import java.util.BitSet;
import java.util.Calendar;
import java.util.Collection;
import java.util.Currency;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
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
import org.apache.fory.json.JsonConfig;
import org.apache.fory.json.codec.ArrayCodec;
import org.apache.fory.json.codec.BaseObjectCodec;
import org.apache.fory.json.codec.CodecUtils;
import org.apache.fory.json.codec.CollectionCodec;
import org.apache.fory.json.codec.GuavaCodecs;
import org.apache.fory.json.codec.JsonCodec;
import org.apache.fory.json.codec.MapCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.ScalarCodecs;
import org.apache.fory.json.codec.SqlJsonCodecs;
import org.apache.fory.json.codegen.JsonCodegen;
import org.apache.fory.json.codegen.JsonCodegen.GeneratedObjectCodecClasses;
import org.apache.fory.json.codegen.JsonJITContext;
import org.apache.fory.json.meta.JsonFieldKind;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.BFloat16;
import org.apache.fory.type.Float16;

/** Shared JSON codec registry used by all local resolvers for one {@code ForyJson}. */
public final class JsonSharedRegistry {
  private final CodecRegistry customCodecs;
  private final IdentityHashMap<Class<?>, JsonCodec> exactCodecs;
  private final JsonCodegen codegen;
  private final JsonJITContext jitContext;
  private final boolean propertyDiscoveryEnabled;

  public JsonSharedRegistry(JsonConfig config) {
    this.customCodecs = config.codecRegistry().copy();
    this.propertyDiscoveryEnabled = config.propertyDiscoveryEnabled();
    exactCodecs = new IdentityHashMap<>();
    boolean codegenEnabled = config.codegenEnabled();
    jitContext = new JsonJITContext(codegenEnabled && config.asyncCompilationEnabled());
    codegen =
        codegenEnabled ? new JsonCodegen(config.writeNullFields(), config.getCodegenHash()) : null;
    registerExactCodecs();
  }

  public JsonCodec createCodec(
      Class<?> rawType, TypeRef<?> typeRef, JsonTypeResolver localResolver) {
    JsonCodec customCodec = customCodecs.get(rawType);
    if (customCodec != null) {
      return customCodec;
    }
    JsonCodec codec = exactCodecs.get(rawType);
    if (codec != null) {
      return codec;
    }
    if (rawType == Class.class) {
      // JSON strings must not be treated as class-loading authority by the default codecs.
      throw new ForyJsonException("Unsupported JSON type " + rawType);
    }
    if (InetAddress.class.isAssignableFrom(rawType)
        || InetSocketAddress.class.isAssignableFrom(rawType)) {
      throw new ForyJsonException("Unsupported JSON type " + rawType);
    }
    if (Number.class.isAssignableFrom(rawType) || CharSequence.class.isAssignableFrom(rawType)) {
      throw new ForyJsonException("Unsupported JSON type " + rawType);
    }
    if (rawType.isEnum()) {
      return new ScalarCodecs.EnumCodec(rawType);
    }
    if (rawType.isArray()) {
      return ArrayCodec.create(rawType.getComponentType(), localResolver);
    }
    if (rawType == Optional.class) {
      return new ScalarCodecs.OptionalCodec(
          CodecUtils.elementType(typeRef.getType()), localResolver);
    }
    if (rawType == AtomicReference.class) {
      return new ScalarCodecs.AtomicReferenceCodec(
          CodecUtils.elementType(typeRef.getType()), localResolver);
    }
    if (rawType == AtomicReferenceArray.class) {
      return new ScalarCodecs.AtomicReferenceArrayCodec(
          CodecUtils.elementType(typeRef.getType()), localResolver);
    }
    if (Calendar.class.isAssignableFrom(rawType)) {
      return ScalarCodecs.CalendarCodec.INSTANCE;
    }
    if (Date.class.isAssignableFrom(rawType)) {
      return ScalarCodecs.DateCodec.INSTANCE;
    }
    if (ZoneId.class.isAssignableFrom(rawType)) {
      return ScalarCodecs.ZoneIdCodec.INSTANCE;
    }
    if (ByteBuffer.class.isAssignableFrom(rawType)) {
      return ScalarCodecs.ByteBufferCodec.INSTANCE;
    }
    if (File.class.isAssignableFrom(rawType)) {
      return ScalarCodecs.FileCodec.INSTANCE;
    }
    if (Path.class.isAssignableFrom(rawType)) {
      return ScalarCodecs.PathCodec.INSTANCE;
    }
    if (Collection.class.isAssignableFrom(rawType)) {
      return CollectionCodec.create(rawType, typeRef, localResolver);
    }
    if (Map.class.isAssignableFrom(rawType)) {
      return MapCodec.create(rawType, typeRef, localResolver);
    }
    return null;
  }

  public JsonFieldKind kind(Class<?> type) {
    if (type == boolean.class || type == Boolean.class) {
      return JsonFieldKind.BOOLEAN;
    }
    if (type == byte.class || type == Byte.class) {
      return JsonFieldKind.BYTE;
    }
    if (type == short.class || type == Short.class) {
      return JsonFieldKind.SHORT;
    }
    if (type == int.class || type == Integer.class) {
      return JsonFieldKind.INT;
    }
    if (type == long.class || type == Long.class) {
      return JsonFieldKind.LONG;
    }
    if (type == float.class || type == Float.class) {
      return JsonFieldKind.FLOAT;
    }
    if (type == double.class || type == Double.class) {
      return JsonFieldKind.DOUBLE;
    }
    if (type == char.class || type == Character.class) {
      return JsonFieldKind.CHAR;
    }
    if (type == String.class) {
      return JsonFieldKind.STRING;
    }
    if (type.isEnum()) {
      return JsonFieldKind.ENUM;
    }
    if (type.isArray()) {
      return JsonFieldKind.ARRAY;
    }
    if (Collection.class.isAssignableFrom(type)) {
      return JsonFieldKind.COLLECTION;
    }
    if (Map.class.isAssignableFrom(type)) {
      return JsonFieldKind.MAP;
    }
    return JsonFieldKind.OBJECT;
  }

  public BaseObjectCodec compileObject(
      ObjectCodec codec,
      JsonTypeResolver localResolver,
      JsonJITContext.ObjectJITCallback<GeneratedObjectCodecClasses> callback) {
    if (codegen == null || !codegen.canCompile(codec)) {
      return null;
    }
    GeneratedObjectCodecClasses classes = codegen.generatedClasses(codec.type());
    if (classes == null) {
      classes =
          jitContext.registerObjectJITCallback(
              () -> null,
              () -> jitContext.asyncVisitJson(this, ignored -> codegen.compileClasses(codec)),
              callback);
    }
    return classes == null ? null : codegen.newCodec(codec, localResolver, classes);
  }

  BaseObjectCodec newGeneratedCodec(
      ObjectCodec codec, JsonTypeResolver resolver, GeneratedObjectCodecClasses classes) {
    return codegen.newCodec(codec, resolver, classes);
  }

  GeneratedObjectCodecClasses generatedClasses(Class<?> type) {
    return codegen == null ? null : codegen.generatedClasses(type);
  }

  JsonJITContext jitContext() {
    return jitContext;
  }

  boolean hasJITResult(Object id) {
    return jitContext.hasJITResult(id);
  }

  boolean asyncCompilationEnabled() {
    return jitContext.asyncCompilationEnabled();
  }

  void registerJITNotifyCallback(Object id, JsonJITContext.NotifyCallback callback) {
    jitContext.registerJITNotifyCallback(id, callback);
  }

  boolean propertyDiscoveryEnabled() {
    return propertyDiscoveryEnabled;
  }

  private void registerExactCodecs() {
    exactCodecs.put(Object.class, ScalarCodecs.NaturalCodec.INSTANCE);
    exactCodecs.put(void.class, ScalarCodecs.VoidCodec.INSTANCE);
    exactCodecs.put(Void.class, ScalarCodecs.VoidCodec.INSTANCE);
    exactCodecs.put(Number.class, ScalarCodecs.NumberCodec.INSTANCE);
    exactCodecs.put(String.class, ScalarCodecs.StringCodec.INSTANCE);
    exactCodecs.put(CharSequence.class, ScalarCodecs.CharSequenceCodec.INSTANCE);
    exactCodecs.put(boolean.class, ScalarCodecs.BooleanCodec.INSTANCE);
    exactCodecs.put(Boolean.class, ScalarCodecs.BooleanCodec.INSTANCE);
    exactCodecs.put(int.class, ScalarCodecs.IntCodec.INSTANCE);
    exactCodecs.put(Integer.class, ScalarCodecs.IntCodec.INSTANCE);
    exactCodecs.put(long.class, ScalarCodecs.LongCodec.INSTANCE);
    exactCodecs.put(Long.class, ScalarCodecs.LongCodec.INSTANCE);
    exactCodecs.put(short.class, ScalarCodecs.ShortCodec.INSTANCE);
    exactCodecs.put(Short.class, ScalarCodecs.ShortCodec.INSTANCE);
    exactCodecs.put(byte.class, ScalarCodecs.ByteCodec.INSTANCE);
    exactCodecs.put(Byte.class, ScalarCodecs.ByteCodec.INSTANCE);
    exactCodecs.put(char.class, ScalarCodecs.CharCodec.INSTANCE);
    exactCodecs.put(Character.class, ScalarCodecs.CharCodec.INSTANCE);
    exactCodecs.put(float.class, ScalarCodecs.FloatCodec.INSTANCE);
    exactCodecs.put(Float.class, ScalarCodecs.FloatCodec.INSTANCE);
    exactCodecs.put(double.class, ScalarCodecs.DoubleCodec.INSTANCE);
    exactCodecs.put(Double.class, ScalarCodecs.DoubleCodec.INSTANCE);
    exactCodecs.put(BigInteger.class, ScalarCodecs.BigIntegerCodec.INSTANCE);
    exactCodecs.put(BigDecimal.class, ScalarCodecs.BigDecimalCodec.INSTANCE);
    exactCodecs.put(Float16.class, ScalarCodecs.Float16Codec.INSTANCE);
    exactCodecs.put(BFloat16.class, ScalarCodecs.BFloat16Codec.INSTANCE);
    exactCodecs.put(BitSet.class, ScalarCodecs.BitSetCodec.INSTANCE);
    exactCodecs.put(StringBuilder.class, ScalarCodecs.StringBuilderCodec.INSTANCE);
    exactCodecs.put(StringBuffer.class, ScalarCodecs.StringBufferCodec.INSTANCE);
    exactCodecs.put(AtomicBoolean.class, ScalarCodecs.AtomicBooleanCodec.INSTANCE);
    exactCodecs.put(AtomicInteger.class, ScalarCodecs.AtomicIntegerCodec.INSTANCE);
    exactCodecs.put(AtomicIntegerArray.class, ScalarCodecs.AtomicIntegerArrayCodec.INSTANCE);
    exactCodecs.put(AtomicLong.class, ScalarCodecs.AtomicLongCodec.INSTANCE);
    exactCodecs.put(AtomicLongArray.class, ScalarCodecs.AtomicLongArrayCodec.INSTANCE);
    exactCodecs.put(Currency.class, ScalarCodecs.CurrencyCodec.INSTANCE);
    exactCodecs.put(File.class, ScalarCodecs.FileCodec.INSTANCE);
    exactCodecs.put(URI.class, ScalarCodecs.UriCodec.INSTANCE);
    exactCodecs.put(URL.class, ScalarCodecs.UrlCodec.INSTANCE);
    exactCodecs.put(Path.class, ScalarCodecs.PathCodec.INSTANCE);
    exactCodecs.put(Pattern.class, ScalarCodecs.PatternCodec.INSTANCE);
    exactCodecs.put(UUID.class, ScalarCodecs.UuidCodec.INSTANCE);
    exactCodecs.put(Locale.class, ScalarCodecs.LocaleCodec.INSTANCE);
    exactCodecs.put(Charset.class, ScalarCodecs.CharsetCodec.INSTANCE);
    exactCodecs.put(Date.class, ScalarCodecs.DateCodec.INSTANCE);
    SqlJsonCodecs.register(exactCodecs);
    exactCodecs.put(Calendar.class, ScalarCodecs.CalendarCodec.INSTANCE);
    exactCodecs.put(TimeZone.class, ScalarCodecs.TimeZoneCodec.INSTANCE);
    exactCodecs.put(LocalDate.class, ScalarCodecs.LocalDateCodec.INSTANCE);
    exactCodecs.put(LocalTime.class, ScalarCodecs.LocalTimeCodec.INSTANCE);
    exactCodecs.put(LocalDateTime.class, ScalarCodecs.LocalDateTimeCodec.INSTANCE);
    exactCodecs.put(Instant.class, ScalarCodecs.InstantCodec.INSTANCE);
    exactCodecs.put(Duration.class, ScalarCodecs.DurationCodec.INSTANCE);
    exactCodecs.put(ZoneOffset.class, ScalarCodecs.ZoneOffsetCodec.INSTANCE);
    exactCodecs.put(ZoneId.class, ScalarCodecs.ZoneIdCodec.INSTANCE);
    exactCodecs.put(ZonedDateTime.class, ScalarCodecs.ZonedDateTimeCodec.INSTANCE);
    exactCodecs.put(Year.class, ScalarCodecs.YearCodec.INSTANCE);
    exactCodecs.put(YearMonth.class, ScalarCodecs.YearMonthCodec.INSTANCE);
    exactCodecs.put(MonthDay.class, ScalarCodecs.MonthDayCodec.INSTANCE);
    exactCodecs.put(Period.class, ScalarCodecs.PeriodCodec.INSTANCE);
    exactCodecs.put(OffsetTime.class, ScalarCodecs.OffsetTimeCodec.INSTANCE);
    exactCodecs.put(OffsetDateTime.class, ScalarCodecs.OffsetDateTimeCodec.INSTANCE);
    exactCodecs.put(HijrahDate.class, ScalarCodecs.HijrahDateCodec.INSTANCE);
    exactCodecs.put(JapaneseDate.class, ScalarCodecs.JapaneseDateCodec.INSTANCE);
    exactCodecs.put(MinguoDate.class, ScalarCodecs.MinguoDateCodec.INSTANCE);
    exactCodecs.put(ThaiBuddhistDate.class, ScalarCodecs.ThaiBuddhistDateCodec.INSTANCE);
    exactCodecs.put(OptionalInt.class, ScalarCodecs.OptionalIntCodec.INSTANCE);
    exactCodecs.put(OptionalLong.class, ScalarCodecs.OptionalLongCodec.INSTANCE);
    exactCodecs.put(OptionalDouble.class, ScalarCodecs.OptionalDoubleCodec.INSTANCE);
    exactCodecs.put(ByteBuffer.class, ScalarCodecs.ByteBufferCodec.INSTANCE);
    GuavaCodecs.registerExactCodecs(exactCodecs);
  }
}

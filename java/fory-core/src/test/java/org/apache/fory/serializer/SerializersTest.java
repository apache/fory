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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.io.Externalizable;
import java.io.Serializable;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.time.chrono.ChronoLocalDate;
import java.time.chrono.ChronoLocalDateTime;
import java.time.chrono.ChronoZonedDateTime;
import java.time.chrono.Era;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAmount;
import java.time.temporal.TemporalUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Currency;
import java.util.Deque;
import java.util.Enumeration;
import java.util.Formattable;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.PrimitiveIterator;
import java.util.Queue;
import java.util.RandomAccess;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.Spliterator;
import java.util.UUID;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.TransferQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.BaseStream;
import java.util.stream.Collector;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.exception.DeserializationException;
import org.apache.fory.exception.InsecureException;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.testng.Assert;
import org.testng.annotations.Test;

public class SerializersTest extends ForyTestBase {

  @Test(dataProvider = "crossLanguageReferenceTrackingConfig")
  public void testStringBuilder(boolean referenceTracking, boolean xlang) {
    ForyBuilder builder =
        Fory.builder()
            .withXlang(xlang)
            .withRefTracking(referenceTracking)
            .requireClassRegistration(false)
            .withCompatible(xlang);
    Fory fory1 = builder.build();
    Fory fory2 = builder.build();
    assertEquals("str", serDe(fory1, fory2, "str"));
    assertEquals("str", serDeObject(fory1, fory2, new StringBuilder("str")).toString());
    assertEquals("str", serDeObject(fory1, fory2, new StringBuffer("str")).toString());
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testBigInt(boolean referenceTracking) {
    ForyBuilder builder =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(referenceTracking)
            .requireClassRegistration(false)
            .withCompatible(false);
    Fory fory1 = builder.build();
    Fory fory2 = builder.build();
    assertEquals(BigInteger.valueOf(100), serDe(fory1, fory2, BigInteger.valueOf(100)));
    assertEquals(BigDecimal.valueOf(100, 2), serDe(fory1, fory2, BigDecimal.valueOf(100, 2)));
    BigInteger bigInteger = new BigInteger("999999999999999999999999999999999999999999999999");
    BigDecimal bigDecimal = new BigDecimal(bigInteger, 200, MathContext.DECIMAL128);
    serDeCheck(fory1, bigDecimal);
    serDeCheck(
        fory1, new BigInteger("11111111110101010000283895380202208220050200000000111111111"));
  }

  private static MemoryBuffer decimalScalePayload(int scale, boolean xlang) {
    MemoryBuffer buffer = MemoryUtils.buffer(16);
    if (xlang) {
      buffer.writeVarInt32(scale);
    } else {
      buffer.writeVarUInt32Small7(scale);
    }
    return buffer;
  }

  private static MemoryBuffer decimalOnePayload(int scale, boolean xlang) {
    MemoryBuffer buffer = MemoryUtils.buffer(16);
    if (xlang) {
      buffer.writeVarInt32(scale);
      buffer.writeVarUInt64(4L);
    } else {
      buffer.writeVarUInt32Small7(scale);
      buffer.writeVarUInt32Small7(0);
      buffer.writeVarUInt32Small7(1);
      buffer.writeByte(1);
    }
    return buffer;
  }

  private static MemoryBuffer bigIntegerPayload(BigInteger value, boolean xlang) {
    if (xlang) {
      return xlangDecimalPayload(0, value);
    }
    return nativeBigIntegerPayload(value.toByteArray());
  }

  private static MemoryBuffer nativeBigIntegerPayload(byte[] bytes) {
    MemoryBuffer buffer = MemoryUtils.buffer(16);
    buffer.writeVarUInt32Small7(bytes.length);
    buffer.writeBytes(bytes);
    return buffer;
  }

  private static MemoryBuffer bigDecimalPayload(BigInteger value, boolean xlang) {
    if (xlang) {
      return xlangDecimalPayload(0, value);
    }
    return nativeBigDecimalPayload(value.toByteArray());
  }

  private static MemoryBuffer nativeBigDecimalPayload(byte[] bytes) {
    MemoryBuffer buffer = MemoryUtils.buffer(16);
    buffer.writeVarUInt32Small7(0);
    buffer.writeVarUInt32Small7(0);
    buffer.writeVarUInt32Small7(bytes.length);
    buffer.writeBytes(bytes);
    return buffer;
  }

  private static MemoryBuffer xlangDecimalPayload(int scale, BigInteger value) {
    byte[] bytes = value.abs().toByteArray();
    int start = bytes.length > 1 && bytes[0] == 0 ? 1 : 0;
    int len = bytes.length - start;
    MemoryBuffer buffer = MemoryUtils.buffer(16);
    buffer.writeVarInt32(scale);
    long meta = ((long) len << 1) | (value.signum() < 0 ? 1 : 0);
    buffer.writeVarUInt64((meta << 1) | 1L);
    for (int i = bytes.length - 1; i >= start; i--) {
      buffer.writeByte(bytes[i]);
    }
    return buffer;
  }

  private static Fory numericFory(boolean xlang) {
    return Fory.builder()
        .withXlang(xlang)
        .withCompatible(false)
        .withRefTracking(false)
        .requireClassRegistration(false)
        .build();
  }

  @Test
  public void testDecimalScaleBounds() {
    int[] validScales = {-10_000, 10_000};
    int[] invalidScales = {Integer.MIN_VALUE, -10_001, 10_001, Integer.MAX_VALUE};
    for (boolean xlang : new boolean[] {false, true}) {
      Fory fory = numericFory(xlang);
      Serializer<BigDecimal> serializer = fory.getSerializer(BigDecimal.class);
      for (int scale : validScales) {
        BigDecimal value = new BigDecimal(BigInteger.ONE, scale);
        MemoryBuffer buffer = MemoryUtils.buffer(16);
        writeSerializer(fory, serializer, buffer, value);
        BigDecimal roundTrip = readSerializer(fory, serializer, buffer);
        assertEquals(roundTrip.scale(), scale);
        assertEquals(roundTrip.unscaledValue(), BigInteger.ONE);

        BigDecimal decoded = readSerializer(fory, serializer, decimalOnePayload(scale, xlang));
        assertEquals(decoded.scale(), scale);
        assertEquals(decoded.unscaledValue(), BigInteger.ONE);
      }
      for (int scale : invalidScales) {
        BigDecimal value = new BigDecimal(BigInteger.ONE, scale);
        MemoryBuffer writeBuffer = MemoryUtils.buffer(16);
        writeBuffer.writeByte(42);
        int writerIndex = writeBuffer.writerIndex();
        assertThrows(
            IllegalArgumentException.class,
            () -> writeSerializer(fory, serializer, writeBuffer, value));
        assertEquals(writeBuffer.writerIndex(), writerIndex);
        assertEquals(writeBuffer.getByte(0), (byte) 42);
        if (xlang) {
          assertThrows(
              IllegalArgumentException.class,
              () -> readSerializer(fory, serializer, decimalScalePayload(scale, true)));
        } else {
          assertThrows(
              DeserializationException.class,
              () -> readSerializer(fory, serializer, decimalScalePayload(scale, false)));
        }
      }
    }
  }

  @Test
  public void testXlangBigIntegerScaleFirst() {
    Fory fory = numericFory(true);
    Serializer<BigInteger> serializer = fory.getSerializer(BigInteger.class);
    int[] nonzeroScales = {Integer.MIN_VALUE, -10_001, -10_000, 10_000, 10_001, Integer.MAX_VALUE};
    for (int scale : nonzeroScales) {
      assertThrows(
          IllegalArgumentException.class,
          () -> readSerializer(fory, serializer, decimalScalePayload(scale, true)));
    }
  }

  @Test
  public void testNativeMagnitudeSignExtension() {
    int bodyLen = 10_001;
    byte[] positiveBytes = new byte[bodyLen];
    positiveBytes[bodyLen - 1] = 1;
    byte[] negativeBytes = new byte[bodyLen];
    Arrays.fill(negativeBytes, (byte) -1);

    Fory fory = numericFory(false);
    Serializer<BigInteger> bigIntegerSerializer = fory.getSerializer(BigInteger.class);
    assertEquals(
        readSerializer(fory, bigIntegerSerializer, nativeBigIntegerPayload(positiveBytes)),
        BigInteger.ONE);
    assertEquals(
        readSerializer(fory, bigIntegerSerializer, nativeBigIntegerPayload(negativeBytes)),
        BigInteger.ONE.negate());

    Serializer<BigDecimal> decimalSerializer = fory.getSerializer(BigDecimal.class);
    BigDecimal positive =
        readSerializer(fory, decimalSerializer, nativeBigDecimalPayload(positiveBytes));
    assertEquals(positive.scale(), 0);
    assertEquals(positive.unscaledValue(), BigInteger.ONE);
    BigDecimal negative =
        readSerializer(fory, decimalSerializer, nativeBigDecimalPayload(negativeBytes));
    assertEquals(negative.scale(), 0);
    assertEquals(negative.unscaledValue(), BigInteger.ONE.negate());
  }

  @Test
  public void testBigNumberMagnitudeBounds() {
    int maxLen = 10_000;
    assertEquals(DecimalSerializer.MAX_MAGNITUDE_BYTES, maxLen);
    int maxBits = maxLen * Byte.SIZE;
    BigInteger positiveBoundary = BigInteger.ONE.shiftLeft(maxBits - 1);
    BigInteger negativeBoundary = positiveBoundary.add(BigInteger.ONE).negate();
    BigInteger positiveOversized = BigInteger.ONE.shiftLeft(maxBits);
    BigInteger negativeOversized = positiveOversized.negate();
    BigInteger[] validValues = {positiveBoundary, negativeBoundary};
    BigInteger[] oversizedValues = {positiveOversized, negativeOversized};
    for (BigInteger value : validValues) {
      assertEquals((value.abs().bitLength() + Byte.SIZE - 1) / Byte.SIZE, maxLen);
      assertEquals(value.toByteArray().length, maxLen + 1);
    }
    for (BigInteger value : oversizedValues) {
      assertEquals((value.abs().bitLength() + Byte.SIZE - 1) / Byte.SIZE, maxLen + 1);
      assertEquals(value.toByteArray().length, maxLen + 1);
    }
    for (boolean xlang : new boolean[] {false, true}) {
      Fory fory = numericFory(xlang);
      Serializer<BigInteger> bigIntegerSerializer = fory.getSerializer(BigInteger.class);
      for (BigInteger value : validValues) {
        MemoryBuffer integerBuffer = MemoryUtils.buffer(16);
        writeSerializer(fory, bigIntegerSerializer, integerBuffer, value);
        assertEquals(readSerializer(fory, bigIntegerSerializer, integerBuffer), value);
        assertEquals(
            readSerializer(fory, bigIntegerSerializer, bigIntegerPayload(value, xlang)), value);
      }
      for (BigInteger value : oversizedValues) {
        MemoryBuffer writeBuffer = MemoryUtils.buffer(16);
        writeBuffer.writeByte(42);
        int writerIndex = writeBuffer.writerIndex();
        assertThrows(
            IllegalArgumentException.class,
            () -> writeSerializer(fory, bigIntegerSerializer, writeBuffer, value));
        assertEquals(writeBuffer.writerIndex(), writerIndex);
        assertEquals(writeBuffer.getByte(0), (byte) 42);
        if (xlang) {
          assertThrows(
              IllegalArgumentException.class,
              () -> readSerializer(fory, bigIntegerSerializer, bigIntegerPayload(value, true)));
        } else {
          assertThrows(
              DeserializationException.class,
              () -> readSerializer(fory, bigIntegerSerializer, bigIntegerPayload(value, false)));
        }
      }

      Serializer<BigDecimal> decimalSerializer = fory.getSerializer(BigDecimal.class);
      for (BigInteger value : validValues) {
        MemoryBuffer decimalBuffer = MemoryUtils.buffer(16);
        writeSerializer(fory, decimalSerializer, decimalBuffer, new BigDecimal(value, 0));
        BigDecimal roundTrip = readSerializer(fory, decimalSerializer, decimalBuffer);
        assertEquals(roundTrip.scale(), 0);
        assertEquals(roundTrip.unscaledValue(), value);
        BigDecimal decoded =
            readSerializer(fory, decimalSerializer, bigDecimalPayload(value, xlang));
        assertEquals(decoded.scale(), 0);
        assertEquals(decoded.unscaledValue(), value);
      }
      for (BigInteger value : oversizedValues) {
        MemoryBuffer writeBuffer = MemoryUtils.buffer(16);
        writeBuffer.writeByte(42);
        int writerIndex = writeBuffer.writerIndex();
        assertThrows(
            IllegalArgumentException.class,
            () -> writeSerializer(fory, decimalSerializer, writeBuffer, new BigDecimal(value, 0)));
        assertEquals(writeBuffer.writerIndex(), writerIndex);
        assertEquals(writeBuffer.getByte(0), (byte) 42);
        if (xlang) {
          assertThrows(
              IllegalArgumentException.class,
              () -> readSerializer(fory, decimalSerializer, bigDecimalPayload(value, true)));
        } else {
          assertThrows(
              DeserializationException.class,
              () -> readSerializer(fory, decimalSerializer, bigDecimalPayload(value, false)));
        }
      }
    }
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testXlangDecimalRoundTrip(boolean referenceTracking) {
    ForyBuilder builder =
        Fory.builder()
            .withXlang(true)
            .withCompatible(false)
            .withRefTracking(referenceTracking)
            .requireClassRegistration(false);
    Fory fory1 = builder.build();
    Fory fory2 = builder.build();
    List<BigDecimal> decimalValues =
        Arrays.asList(
            BigDecimal.ZERO,
            BigDecimal.ONE,
            BigDecimal.ONE.negate(),
            BigDecimal.valueOf(12345, 2),
            new BigDecimal(BigInteger.valueOf(Long.MAX_VALUE), 0),
            new BigDecimal(BigInteger.valueOf(Long.MIN_VALUE), 0),
            new BigDecimal(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE), 0),
            new BigDecimal(BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE), 0),
            new BigDecimal(new BigInteger("123456789012345678901234567890123456789"), 37),
            new BigDecimal(new BigInteger("-123456789012345678901234567890123456789"), -17));
    for (BigDecimal value : decimalValues) {
      assertEquals(serDe(fory1, fory2, value), value);
    }
  }

  @Test
  public void testXlangDecimalCodecCanonicalRoundTrip() {
    List<BigDecimal> values =
        Arrays.asList(
            BigDecimal.ZERO,
            BigDecimal.ONE,
            BigDecimal.ONE.negate(),
            BigDecimal.valueOf(100, 2),
            new BigDecimal(BigInteger.valueOf(Long.MAX_VALUE), 0),
            new BigDecimal(BigInteger.valueOf(Long.MIN_VALUE), 0),
            new BigDecimal(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE), 0),
            new BigDecimal(BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE), 0),
            new BigDecimal(new BigInteger("999999999999999999999999999999999999999999"), 200));
    for (BigDecimal value : values) {
      MemoryBuffer buffer = MemoryUtils.buffer(64);
      DecimalSerializer.writeXlangDecimal(buffer, value.scale(), value.unscaledValue());
      buffer.readerIndex(0);
      assertEquals(DecimalSerializer.readXlangDecimal(buffer), value);
    }
  }

  @Test
  public void testXlangDecimalCodecRejectsNonCanonicalBigPayloads() {
    MemoryBuffer zeroBigEncoding = MemoryUtils.buffer(16);
    zeroBigEncoding.writeVarInt32(0);
    zeroBigEncoding.writeVarUInt64(1L);
    zeroBigEncoding.readerIndex(0);
    assertThrows(
        IllegalArgumentException.class, () -> DecimalSerializer.readXlangDecimal(zeroBigEncoding));

    MemoryBuffer trailingZeroPayload = MemoryUtils.buffer(16);
    trailingZeroPayload.writeVarInt32(0);
    trailingZeroPayload.writeVarUInt64(9L);
    trailingZeroPayload.writeBytes(new byte[] {1, 0});
    trailingZeroPayload.readerIndex(0);
    assertThrows(
        IllegalArgumentException.class,
        () -> DecimalSerializer.readXlangDecimal(trailingZeroPayload));
  }

  @Test
  public void testDecimalSerializerSelectionByLanguage() {
    Fory nativeFory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    Fory xlangFory =
        Fory.builder()
            .withXlang(true)
            .withCompatible(false)
            .requireClassRegistration(false)
            .build();
    assertEquals(nativeFory.getSerializer(BigDecimal.class).getClass(), DecimalSerializer.class);
    assertEquals(xlangFory.getSerializer(BigDecimal.class).getClass(), DecimalSerializer.class);
    assertEquals(nativeFory.getSerializer(BigInteger.class).getClass(), BigIntegerSerializer.class);
    assertEquals(xlangFory.getSerializer(BigInteger.class).getClass(), BigIntegerSerializer.class);
  }

  @Test(dataProvider = "javaFory")
  public void testAtomic(Fory fory) {
    assertTrue(
        ((AtomicBoolean) serDeCheckSerializer(fory, new AtomicBoolean(true), "AtomicBoolean"))
            .get());

    Assert.assertEquals(
        ((AtomicInteger) serDeCheckSerializer(fory, new AtomicInteger(100), "AtomicInteger")).get(),
        100);
    Assert.assertEquals(
        ((AtomicLong) serDeCheckSerializer(fory, new AtomicLong(200), "AtomicLong")).get(), 200);
    Assert.assertEquals(
        ((AtomicReference)
                serDeCheckSerializer(fory, new AtomicReference<>(200), "AtomicReference"))
            .get(),
        200);
  }

  @Test
  public void testCurrency() {
    Assert.assertEquals(
        serDeCheckSerializer(getJavaFory(), Currency.getInstance("EUR"), "Currency"),
        Currency.getInstance("EUR"));
  }

  @Test
  public void testCharset() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    Assert.assertEquals(
        serDeCheckSerializer(fory, Charset.defaultCharset(), "Charset"), Charset.defaultCharset());
  }

  @Test
  public void testURI() throws URISyntaxException {
    Assert.assertEquals(serDeCheckSerializer(getJavaFory(), new URI(""), "URI"), new URI(""));
    Assert.assertEquals(serDeCheckSerializer(getJavaFory(), new URI("abc"), "URI"), new URI("abc"));
  }

  @Test
  public void testRegex() {
    Assert.assertEquals(
        serDeCheckSerializer(getJavaFory(), Pattern.compile("abc"), "Regex").toString(),
        Pattern.compile("abc").toString());
  }

  @Test
  public void testUUID() {
    UUID uuid = UUID.randomUUID();
    Assert.assertEquals(serDeCheckSerializer(getJavaFory(), uuid, "UUID"), uuid);
  }

  private static class TestClassSerialization {}

  private interface TestClassTokenInterface {
    void test();
  }

  private interface TestDefaultClassTokenInterface {
    default void test() {}
  }

  private static class TestReplaceClassSerialization {
    private Object writeReplace() {
      return 1;
    }
  }

  @Test
  public void testSerializeClass() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    // serialize both TestReplaceClassSerialization object and class.
    // Scala `object` native serialization will return ModuleSerializationProxy will write original
    // class.
    List<Object> list =
        serDe(
            fory,
            Arrays.asList(
                new TestReplaceClassSerialization(), TestReplaceClassSerialization.class));
    assertEquals(list.get(1), TestReplaceClassSerialization.class);
    serDeCheckSerializer(fory, TestClassSerialization.class, "ClassSerializer");
    serDeCheckSerializer(fory, TestReplaceClassSerialization.class, "ClassSerializer");
    serDe(fory, new TestReplaceClassSerialization());
  }

  @Test
  public void testClassTokens() {
    Class<?>[] safeInterfaces = {
      Serializable.class,
      CharSequence.class,
      Cloneable.class,
      Comparable.class,
      Iterable.class,
      Runnable.class,
      ChronoLocalDate.class,
      ChronoLocalDateTime.class,
      ChronoZonedDateTime.class,
      Era.class,
      Temporal.class,
      TemporalAccessor.class,
      TemporalAmount.class,
      TemporalUnit.class,
      Iterator.class,
      ListIterator.class,
      Collection.class,
      List.class,
      Set.class,
      Map.class,
      Map.Entry.class,
      Queue.class,
      Deque.class,
      SortedSet.class,
      NavigableSet.class,
      SortedMap.class,
      NavigableMap.class,
      Comparator.class,
      Enumeration.class,
      Formattable.class,
      PrimitiveIterator.class,
      PrimitiveIterator.OfDouble.class,
      PrimitiveIterator.OfInt.class,
      PrimitiveIterator.OfLong.class,
      RandomAccess.class,
      Spliterator.class,
      Spliterator.OfPrimitive.class,
      Spliterator.OfDouble.class,
      Spliterator.OfInt.class,
      Spliterator.OfLong.class,
      BlockingDeque.class,
      BlockingQueue.class,
      Callable.class,
      ConcurrentMap.class,
      ConcurrentNavigableMap.class,
      TransferQueue.class,
      BaseStream.class,
      Stream.class,
      DoubleStream.class,
      IntStream.class,
      LongStream.class,
      Collector.class,
      Function.class
    };
    Class<?>[] registeredInterfaces = {
      Externalizable.class,
      Type.class,
      Connection.class,
      TestClassTokenInterface.class,
      TestDefaultClassTokenInterface.class
    };
    Fory unregisteredFory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(true)
            .withCompatible(false)
            .build();
    for (Class<?> type : safeInterfaces) {
      assertTrue(type.isInterface());
      assertSame(serDe(unregisteredFory, type), type);
    }
    for (Class<?> type : registeredInterfaces) {
      assertThrows(InsecureException.class, () -> serDe(unregisteredFory, type));
    }

    Fory checkedFory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(true)
            .withTypeChecker((resolver, className) -> !className.equals(Collection.class.getName()))
            .withCompatible(false)
            .build();
    assertThrows(InsecureException.class, () -> serDe(checkedFory, Collection.class));

    Fory registeredFory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(true)
            .withCompatible(false)
            .build();
    for (Class<?> type : registeredInterfaces) {
      registeredFory.register(type);
    }
    for (Class<?> type : registeredInterfaces) {
      assertSame(serDe(registeredFory, type), type);
    }
  }

  @Test
  public void testEmptyObject() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(true)
            .withCompatible(false)
            .build();
    assertSame(serDe(fory, new Object()).getClass(), Object.class);
  }
}

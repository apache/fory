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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.ForyBuilder;
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
            .requireClassRegistration(false);
    Fory fory1 = builder.build();
    Fory fory2 = builder.build();
    assertEquals("str", serDe(fory1, fory2, "str"));
    assertEquals("str", serDeObject(fory1, fory2, new StringBuilder("str")).toString());
    assertEquals("str", serDeObject(fory1, fory2, new StringBuffer("str")).toString());
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testBigInt(boolean referenceTracking) {
    ForyBuilder builder =
        Fory.builder().withRefTracking(referenceTracking).requireClassRegistration(false);
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

  @Test(dataProvider = "referenceTrackingConfig")
  public void testXlangDecimalRoundTrip(boolean referenceTracking) {
    ForyBuilder builder =
        Fory.builder()
            .withXlang(true)
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
    Fory nativeFory = Fory.builder().withXlang(false).requireClassRegistration(false).build();
    Fory xlangFory = Fory.builder().withXlang(true).requireClassRegistration(false).build();
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
    Fory fory = Fory.builder().withXlang(false).requireClassRegistration(false).build();
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

  private static class TestReplaceClassSerialization {
    private Object writeReplace() {
      return 1;
    }
  }

  @Test
  public void testSerializeClass() {
    Fory fory = Fory.builder().requireClassRegistration(false).build();
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
  public void testEmptyObject() {
    Fory fory = Fory.builder().requireClassRegistration(true).build();
    assertSame(serDe(fory, new Object()).getClass(), Object.class);
  }
}

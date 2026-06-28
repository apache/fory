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

package org.apache.fory.json.data;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Calendar;
import java.util.Currency;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class CoreScalarFields {
  public AtomicInteger atomicInt = new AtomicInteger(7);
  public BigDecimal bigDecimal = new BigDecimal("12345.6789");
  public BigInteger bigInteger = new BigInteger("12345678901234567890");
  public StringBuilder builder = new StringBuilder("build");
  public ByteBuffer bytes = ByteBuffer.wrap(new byte[] {1, -2, 3});
  public Calendar calendar = JsonTestData.calendar(123456789L);
  public Charset charset = StandardCharsets.UTF_8;
  public Currency currency = Currency.getInstance("EUR");
  public LocalDate date = LocalDate.of(2026, 6, 21);
  public Instant instant = Instant.parse("2026-06-21T01:02:03Z");
  public Locale locale = Locale.forLanguageTag("zh-Hans-CN");
  public Optional<String> maybe = Optional.of("yes");
  public OptionalInt optionalInt = OptionalInt.of(4);
  public TimeZone timeZone = TimeZone.getTimeZone("UTC");
  public Class<?> type = PublicFields.class;
  public URI uri = URI.create("https://fory.apache.org/json");
  public URL url = JsonTestData.url("https://fory.apache.org/");
  public UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
}

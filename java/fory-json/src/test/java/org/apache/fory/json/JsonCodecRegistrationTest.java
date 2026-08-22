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

package org.apache.fory.json;

import static org.apache.fory.json.JsonTestSupport.nullCodec;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.resolver.CodecRegistry;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.reflect.TypeRef;
import org.testng.annotations.Test;

public class JsonCodecRegistrationTest {
  private static final Class<?>[] DEDICATED_TYPES = {
    boolean.class,
    Boolean.class,
    byte.class,
    Byte.class,
    short.class,
    Short.class,
    int.class,
    Integer.class,
    long.class,
    Long.class,
    float.class,
    Float.class,
    double.class,
    Double.class,
    char.class,
    Character.class,
    String.class,
    CharSequence.class,
    Number.class,
    BigInteger.class,
    BigDecimal.class,
    UUID.class,
    LocalDate.class,
    LocalTime.class,
    LocalDateTime.class,
    Instant.class,
    Duration.class,
    ZoneOffset.class,
    ZonedDateTime.class,
    Year.class,
    YearMonth.class,
    MonthDay.class,
    Period.class,
    OffsetTime.class,
    OffsetDateTime.class,
    byte[].class,
    String[].class,
    long[].class
  };

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void dedicatedTypeRegistrationsRejected() {
    JsonCodecFactory factory = (type, resolver, runtimeType) -> null;
    for (Class<?> type : DEDICATED_TYPES) {
      assertThrows(
          IllegalArgumentException.class,
          () -> ForyJson.builder().registerCodec((Class) type, nullCodec()));
      assertThrows(
          IllegalArgumentException.class,
          () -> ForyJson.builder().registerCodec((Class) type, factory));
    }
  }

  @Test
  public void factoryHandledDedicatedTypeRejected() {
    CodecRegistry registry = new CodecRegistry();
    JsonCodecFactory factory =
        new JsonCodecFactory() {
          @Override
          public JsonValueCodec<?> create(
              TypeRef<?> type, JsonTypeResolver resolver, boolean runtimeType) {
            return null;
          }

          @Override
          public List<Class<?>> handledRuntimeClasses() {
            return Collections.singletonList(String.class);
          }
        };
    assertThrows(
        IllegalArgumentException.class, () -> registry.registerFactory(Object.class, factory));
    assertFalse(registry.contains(Object.class));
  }

  @Test
  public void sameNamedHandledClassesAllowed() throws Exception {
    Class<?> first = JsonTestSupport.shadowClass(ApplicationValue.class);
    Class<?> second = JsonTestSupport.shadowClass(ApplicationValue.class);
    assertNotSame(first, second);

    CodecRegistry registry = new CodecRegistry();
    JsonCodecFactory factory =
        new JsonCodecFactory() {
          @Override
          public JsonValueCodec<?> create(
              TypeRef<?> type, JsonTypeResolver resolver, boolean runtimeType) {
            return null;
          }

          @Override
          public List<Class<?>> handledRuntimeClasses() {
            return Arrays.asList(first, second);
          }
        };
    registry.registerFactory(Object.class, factory);

    List<Class<?>> handled = registry.getFactory(Object.class).handledRuntimeClasses();
    assertEquals(handled.size(), 2);
    assertTrue(handled.contains(first));
    assertTrue(handled.contains(second));
  }

  @Test
  public void moduleExactDedicatedTypeRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ForyJson.builder()
                .withModule(context -> context.registerCodec(String.class, nullCodec()))
                .build());
  }

  @Test
  public void applicationTypeRegistrationAllowed() {
    CodecRegistry registry = new CodecRegistry();
    registry.register(ApplicationValue.class, nullCodec());
    assertTrue(registry.contains(ApplicationValue.class));
  }

  @Test
  public void otherBuiltinRegistrationAllowed() {
    CodecRegistry registry = new CodecRegistry();
    registry.register(File.class, nullCodec());
    assertTrue(registry.contains(File.class));
  }

  @Test
  public void objectCodecRegistrationRejected() {
    ForyJson source = ForyJson.builder().build();
    ObjectCodec<ApplicationValue> codec =
        JsonTestSupport.currentTypeResolver(source).getObjectCodec(ApplicationValue.class);
    assertThrows(
        IllegalArgumentException.class,
        () -> ForyJson.builder().registerCodec(ApplicationValue.class, codec));
  }

  public static final class ApplicationValue {}
}

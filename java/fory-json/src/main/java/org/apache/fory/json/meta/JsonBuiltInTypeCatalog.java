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

package org.apache.fory.json.meta;

import org.apache.fory.annotation.Internal;

/** Versioned binary-name catalog shared by runtime built-in resolution and source processing. */
@Internal
public final class JsonBuiltInTypeCatalog {
  public static final int VERSION = 1;

  private static final String[] EXACT = {
    "java.lang.Object",
    "java.lang.Void",
    "java.lang.Number",
    "java.lang.String",
    "java.lang.CharSequence",
    "java.lang.Boolean",
    "java.lang.Integer",
    "java.lang.Long",
    "java.lang.Short",
    "java.lang.Byte",
    "java.lang.Character",
    "java.lang.Float",
    "java.lang.Double",
    "java.math.BigInteger",
    "java.math.BigDecimal",
    "org.apache.fory.type.Float16",
    "org.apache.fory.type.BFloat16",
    "java.util.BitSet",
    "java.lang.StringBuilder",
    "java.lang.StringBuffer",
    "java.util.concurrent.atomic.AtomicBoolean",
    "java.util.concurrent.atomic.AtomicInteger",
    "java.util.concurrent.atomic.AtomicIntegerArray",
    "java.util.concurrent.atomic.AtomicLong",
    "java.util.concurrent.atomic.AtomicLongArray",
    "java.util.Currency",
    "java.io.File",
    "java.net.URI",
    "java.nio.file.Path",
    "java.util.regex.Pattern",
    "java.util.UUID",
    "java.util.Locale",
    "java.nio.charset.Charset",
    "java.util.Date",
    "java.sql.Date",
    "java.sql.Time",
    "java.sql.Timestamp",
    "java.util.Calendar",
    "java.util.TimeZone",
    "java.time.LocalDate",
    "java.time.LocalTime",
    "java.time.LocalDateTime",
    "java.time.Instant",
    "java.time.Duration",
    "java.time.ZoneOffset",
    "java.time.ZoneId",
    "java.time.ZonedDateTime",
    "java.time.Year",
    "java.time.YearMonth",
    "java.time.MonthDay",
    "java.time.Period",
    "java.time.OffsetTime",
    "java.time.OffsetDateTime",
    "java.time.chrono.HijrahDate",
    "java.time.chrono.JapaneseDate",
    "java.time.chrono.MinguoDate",
    "java.time.chrono.ThaiBuddhistDate",
    "java.util.OptionalInt",
    "java.util.OptionalLong",
    "java.util.OptionalDouble",
    "java.nio.ByteBuffer",
    "java.util.Optional",
    "java.util.concurrent.atomic.AtomicReference",
    "java.util.concurrent.atomic.AtomicReferenceArray",
    "com.google.common.primitives.ImmutableIntArray"
  };

  private static final String[] ASSIGNABLE = {
    "java.net.InetAddress",
    "java.net.InetSocketAddress",
    "java.net.URL",
    "java.lang.Number",
    "java.lang.CharSequence",
    "java.util.Calendar",
    "java.util.Date",
    "java.time.ZoneId",
    "java.nio.ByteBuffer",
    "java.io.File",
    "java.nio.file.Path",
    "java.util.Collection",
    "java.util.Map"
  };

  private JsonBuiltInTypeCatalog() {}

  public static String[] exactBinaryNames() {
    return EXACT.clone();
  }

  public static String[] assignableBinaryNames() {
    return ASSIGNABLE.clone();
  }
}

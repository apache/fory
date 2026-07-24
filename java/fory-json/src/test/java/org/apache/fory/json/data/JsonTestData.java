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

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Calendar;
import java.util.EnumMap;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

public final class JsonTestData {
  public static final String TWO_BYTE_TEXT = "\u0100\u07ff\u03a9";
  public static final String THREE_BYTE_TEXT = "\u0800\u20ac\u4f60\ud7ff\ue000";
  public static final String SUPPLEMENTARY_TEXT = "\uD834\uDD1E\uD83D\uDE00\uD83C\uDF0D";
  public static final String MIXED_SCRIPT_TEXT = "\u0100\u03a9\u0416\u05d0\u0627\u0905\u0e01\u4f60";
  public static final String COMBINING_TEXT = "e\u0301\u200d\uD83D\uDCBB";
  public static final String ZH_TEXT = "你好，Fory";
  public static final String EU_TEXT = "café crème Österreich € ČšŽ";

  private JsonTestData() {}

  public static Map<String, Integer> scores() {
    Map<String, Integer> scores = new LinkedHashMap<>();
    scores.put("one", 1);
    scores.put("two", 2);
    return scores;
  }

  public static Map<String, Boolean> flags() {
    Map<String, Boolean> flags = new LinkedHashMap<>();
    flags.put("enabled", Boolean.TRUE);
    flags.put("disabled", Boolean.FALSE);
    return flags;
  }

  public static Map<Integer, String> intNames() {
    Map<Integer, String> values = new LinkedHashMap<>();
    values.put(1, "one");
    values.put(2, "two");
    return values;
  }

  public static EnumMap<Kind, Integer> enumScores() {
    EnumMap<Kind, Integer> values = new EnumMap<>(Kind.class);
    values.put(Kind.FAST, 1);
    values.put(Kind.SMALL, 2);
    return values;
  }

  public static Calendar calendar(long millis) {
    Calendar calendar = new GregorianCalendar();
    calendar.setTimeZone(TimeZone.getTimeZone("UTC"));
    calendar.setTimeInMillis(millis);
    return calendar;
  }

  public static URL url(String value) {
    try {
      return new URL(value);
    } catch (MalformedURLException e) {
      throw new IllegalArgumentException(e);
    }
  }

  public static Map<String, String> unicodeMap() {
    Map<String, String> values = new LinkedHashMap<>();
    values.put(TWO_BYTE_TEXT, THREE_BYTE_TEXT);
    values.put(ZH_TEXT, EU_TEXT);
    values.put("\u043a\u043b\u044e\u0447", "\uD83D\uDE00");
    values.put("\u0645\u0631\u062d\u0628\u0627", "\u0928\u092e\u0938\u094d\u0924\u0947");
    return values;
  }

  public static Map<String, Object> values() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("name", "fory");
    values.put("score", 9);
    return values;
  }
}

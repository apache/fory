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

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.apache.fory.Fory;
import org.apache.fory.collection.Tuple3;
import org.apache.fory.memory.MemoryBuffer;

/** Local serializer for {@link Locale}. */
public final class LocaleSerializer extends ImmutableSerializer<Locale> {
  // Using `new HashMap<>` to ensure thread safety by java constructor semantics.
  private static final Map<Tuple3<String, String, String>, Locale> LOCALE_CACHE =
      new HashMap<>(createCacheMap());

  static Map<Tuple3<String, String, String>, Locale> createCacheMap() {
    Map<Tuple3<String, String, String>, Locale> map = new HashMap<>();
    populateMap(map, Locale.US);
    populateMap(map, Locale.SIMPLIFIED_CHINESE);
    populateMap(map, Locale.CHINESE);
    populateMap(map, Locale.TRADITIONAL_CHINESE);
    populateMap(map, Locale.ENGLISH);
    populateMap(map, Locale.GERMAN);
    populateMap(map, Locale.FRENCH);
    populateMap(map, Locale.ITALIAN);
    populateMap(map, Locale.JAPANESE);
    populateMap(map, Locale.KOREAN);
    populateMap(map, Locale.CHINA);
    populateMap(map, Locale.TAIWAN);
    populateMap(map, Locale.UK);
    populateMap(map, Locale.GERMANY);
    populateMap(map, Locale.FRANCE);
    populateMap(map, Locale.ITALY);
    populateMap(map, Locale.JAPAN);
    populateMap(map, Locale.KOREA);
    populateMap(map, Locale.PRC);
    populateMap(map, Locale.CANADA);
    populateMap(map, Locale.CANADA_FRENCH);
    populateMap(map, Locale.ROOT);
    populateMap(map, new Locale("es", "", ""));
    populateMap(map, new Locale("es", "ES", ""));
    return map;
  }

  private static void populateMap(Map<Tuple3<String, String, String>, Locale> map, Locale locale) {
    map.put(Tuple3.of(locale.getCountry(), locale.getLanguage(), locale.getVariant()), locale);
  }

  public LocaleSerializer(Fory fory) {
    super(fory, Locale.class);
  }

  public void write(MemoryBuffer buffer, Locale l) {
    fory.writeString(buffer, l.getLanguage());
    fory.writeString(buffer, l.getCountry());
    fory.writeString(buffer, l.getVariant());
  }

  public Locale read(MemoryBuffer buffer) {
    String language = fory.readString(buffer);
    String country = fory.readString(buffer);
    String variant = fory.readString(buffer);
    // Fast path for Default/US/SIMPLIFIED_CHINESE
    Locale defaultLocale = Locale.getDefault();
    if (isSame(defaultLocale, language, country, variant)) {
      return defaultLocale;
    }
    if (defaultLocale != Locale.US && isSame(Locale.US, language, country, variant)) {
      return Locale.US;
    }
    if (isSame(Locale.SIMPLIFIED_CHINESE, language, country, variant)) {
      return Locale.SIMPLIFIED_CHINESE;
    }
    Object o = LOCALE_CACHE.get(Tuple3.of(language, country, variant));
    if (o != null) {
      return (Locale) o;
    }
    return new Locale(language, country, variant);
  }

  static boolean isSame(Locale locale, String language, String country, String variant) {
    return (locale.getLanguage().equals(language)
        && locale.getCountry().equals(country)
        && locale.getVariant().equals(variant));
  }
}

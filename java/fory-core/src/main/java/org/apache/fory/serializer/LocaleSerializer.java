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
import java.util.Objects;
import org.apache.fory.config.Config;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;

/** Local serializer for {@link Locale}. */
public final class LocaleSerializer extends ImmutableSerializer<Locale> implements Shareable {
  private static final class LocaleKey {
    private final String language;
    private final String country;
    private final String variant;
    private final String script;
    private final Map<Character, String> extensions;

    private LocaleKey(
        String language,
        String country,
        String variant,
        String script,
        Map<Character, String> extensions) {
      this.language = language;
      this.country = country;
      this.variant = variant;
      this.script = script;
      this.extensions = extensions;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof LocaleKey)) {
        return false;
      }
      LocaleKey other = (LocaleKey) o;
      return language.equals(other.language)
          && country.equals(other.country)
          && variant.equals(other.variant)
          && script.equals(other.script)
          && extensions.equals(other.extensions);
    }

    @Override
    public int hashCode() {
      return Objects.hash(language, country, variant, script, extensions);
    }
  }

  // Using `new HashMap<>` to ensure thread safety by java constructor semantics.
  private static final Map<LocaleKey, Locale> LOCALE_CACHE = new HashMap<>(createCacheMap());

  static Map<LocaleKey, Locale> createCacheMap() {
    Map<LocaleKey, Locale> map = new HashMap<>();
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

  private static void populateMap(Map<LocaleKey, Locale> map, Locale locale) {
    Map<Character, String> extensions = new HashMap<>();
    for (Character key : locale.getExtensionKeys()) {
      extensions.put(key, locale.getExtension(key));
    }
    map.put(
        new LocaleKey(
            locale.getLanguage(),
            locale.getCountry(),
            locale.getVariant(),
            locale.getScript(),
            extensions),
        locale);
  }

  public LocaleSerializer(Config config) {
    super(config, Locale.class);
  }

  public void write(WriteContext writeContext, Locale l) {
    writeContext.writeString(l.getLanguage());
    writeContext.writeString(l.getCountry());
    writeContext.writeString(l.getVariant());
    writeContext.writeString(l.getScript());
    writeContext.writeInt32(l.getExtensionKeys().size());
    for (Character key : l.getExtensionKeys()) {
      writeContext.writeChar(key);
      writeContext.writeString(l.getExtension(key));
    }
  }

  public Locale read(ReadContext readContext) {
    String language = readContext.readString();
    String country = readContext.readString();
    String variant = readContext.readString();
    String script = readContext.readString();
    int extensionCount = readContext.readInt32();

    Map<Character, String> extensions = new HashMap<>();
    for (int i = 0; i < extensionCount; i++) {
      Character key = readContext.readChar();
      String value = readContext.readString();
      extensions.put(key, value);
    }

    Locale defaultLocale = Locale.getDefault();
    if (isSame(defaultLocale, language, country, variant, script, extensions)) {
      return defaultLocale;
    }
    if (defaultLocale != Locale.US
        && isSame(Locale.US, language, country, variant, script, extensions)) {
      return Locale.US;
    }
    if (isSame(Locale.SIMPLIFIED_CHINESE, language, country, variant, script, extensions)) {
      return Locale.SIMPLIFIED_CHINESE;
    }

    Locale cached = LOCALE_CACHE.get(new LocaleKey(language, country, variant, script, extensions));

    if (cached != null) {
      return cached;
    }

    Locale.Builder builder =
        new Locale.Builder()
            .setLanguage(language)
            .setRegion(country)
            .setVariant(variant)
            .setScript(script);
    for (Map.Entry<Character, String> entry : extensions.entrySet()) {
      builder.setExtension(entry.getKey(), entry.getValue());
    }

    return builder.build();
  }

  static boolean isSame(
      Locale locale,
      String language,
      String country,
      String variant,
      String script,
      Map<Character, String> extensions) {

    return locale.getLanguage().equals(language)
        && locale.getCountry().equals(country)
        && locale.getVariant().equals(variant)
        && locale.getScript().equals(script)
        && locale.getExtensionKeys().equals(extensions.keySet())
        && extensions.entrySet().stream()
            .allMatch(entry -> entry.getValue().equals(locale.getExtension(entry.getKey())));
  }
}

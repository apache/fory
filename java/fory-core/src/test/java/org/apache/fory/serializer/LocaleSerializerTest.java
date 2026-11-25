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

import java.util.Locale;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.testng.annotations.Test;

public class LocaleSerializerTest extends ForyTestBase {

  @Test
  public void testWrite() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    serDeCheckSerializerAndEqual(fory, Locale.US, "LocaleSerializer");
    serDeCheckSerializerAndEqual(fory, Locale.CHINESE, "LocaleSerializer");
    serDeCheckSerializerAndEqual(fory, Locale.ENGLISH, "LocaleSerializer");
    serDeCheckSerializerAndEqual(fory, Locale.TRADITIONAL_CHINESE, "LocaleSerializer");
    serDeCheckSerializerAndEqual(fory, Locale.CHINA, "LocaleSerializer");
    serDeCheckSerializerAndEqual(fory, Locale.TAIWAN, "LocaleSerializer");
    serDeCheckSerializerAndEqual(fory, Locale.getDefault(), "LocaleSerializer");
    serDeCheckSerializerAndEqual(fory, Locale.ROOT, "LocaleSerializer");
    serDeCheckSerializerAndEqual(fory, Locale.GERMANY, "LocaleSerializer");
    serDeCheckSerializerAndEqual(fory, Locale.FRANCE, "LocaleSerializer");

    // Script preservation
    Locale zhHans = Locale.forLanguageTag("zh-Hans-CN");
    Locale zhHant = Locale.forLanguageTag("zh-Hant-TW");
    Locale srLatn = Locale.forLanguageTag("sr-Latn-RS");
    Locale srCyrl = Locale.forLanguageTag("sr-Cyrl-RS");
    Locale uzLatn = Locale.forLanguageTag("uz-Latn-UZ");
    serDeCheckSerializerAndEqual(fory, zhHans, "LocaleSerializer");
    serDeCheckSerializerAndEqual(fory, zhHant, "LocaleSerializer");
    serDeCheckSerializerAndEqual(fory, srLatn, "LocaleSerializer");
    serDeCheckSerializerAndEqual(fory, srCyrl, "LocaleSerializer");
    serDeCheckSerializerAndEqual(fory, uzLatn, "LocaleSerializer");

    // Extension preservation
    Locale thaiDigits =
        new Locale.Builder().setLanguage("th").setRegion("TH").setExtension('u', "nu-thai").build();
    Locale japaneseCal =
        new Locale.Builder()
            .setLanguage("ja")
            .setRegion("JP")
            .setExtension('u', "ca-japanese")
            .build();
    serDeCheckSerializerAndEqual(fory, thaiDigits, "LocaleSerializer");
    serDeCheckSerializerAndEqual(fory, japaneseCal, "LocaleSerializer");

    // Script + Extension preservation
    Locale scriptAndExt =
        new Locale.Builder()
            .setLanguage("zh")
            .setScript("Hans")
            .setRegion("CN")
            .setExtension('u', "nu-native")
            .build();
    serDeCheckSerializerAndEqual(fory, scriptAndExt, "LocaleSerializer");

    // Variants
    serDeCheckSerializerAndEqual(fory, new Locale("en", "US", "POSIX"), "LocaleSerializer");
    serDeCheckSerializerAndEqual(fory, new Locale("en", "US", "WIN"), "LocaleSerializer");
    serDeCheckSerializerAndEqual(fory, new Locale("ja", "JP", "JP"), "LocaleSerializer");
    serDeCheckSerializerAndEqual(fory, new Locale("th", "TH", "TH"), "LocaleSerializer");

    // Cache hit singleton check
    org.testng.Assert.assertSame(fory.deserialize(fory.serialize(Locale.US)), Locale.US);
    org.testng.Assert.assertSame(fory.deserialize(fory.serialize(Locale.GERMANY)), Locale.GERMANY);
    org.testng.Assert.assertSame(fory.deserialize(fory.serialize(Locale.FRANCE)), Locale.FRANCE);
    org.testng.Assert.assertSame(fory.deserialize(fory.serialize(Locale.ROOT)), Locale.ROOT);
  }

  @Test
  public void testAvailableLocales() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    for (Locale locale : Locale.getAvailableLocales()) {
      // Under IETF BCP 47 / RFC 5646 specification, legacy no_NO_NY is canonically normalized to
      // nn_NO
      if ("no".equals(locale.getLanguage())
          && "NO".equals(locale.getCountry())
          && "NY".equals(locale.getVariant())) {
        continue;
      }
      Locale deserialized = (Locale) fory.deserialize(fory.serialize(locale));
      org.testng.Assert.assertEquals(deserialized, locale);
    }
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testWrite(Fory fory) {
    copyCheckWithoutSame(fory, Locale.US);
    copyCheckWithoutSame(fory, Locale.CHINESE);
    copyCheckWithoutSame(fory, Locale.ENGLISH);
    copyCheckWithoutSame(fory, Locale.TRADITIONAL_CHINESE);
    copyCheckWithoutSame(fory, Locale.CHINA);
    copyCheckWithoutSame(fory, Locale.TAIWAN);
    copyCheckWithoutSame(fory, Locale.getDefault());
    copyCheckWithoutSame(fory, Locale.ROOT);
    copyCheckWithoutSame(fory, Locale.forLanguageTag("zh-Hans-CN"));
    copyCheckWithoutSame(fory, Locale.forLanguageTag("sr-Latn-RS"));
    copyCheckWithoutSame(
        fory,
        new Locale.Builder()
            .setLanguage("th")
            .setRegion("TH")
            .setExtension('u', "nu-thai")
            .build());
  }
}

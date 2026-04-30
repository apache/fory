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
    Fory fory = Fory.builder().withXlang(false).requireClassRegistration(false).build();
    serDeCheckSerializerAndEqual(fory, Locale.US, "LocaleSerializer");
    serDeCheckSerializerAndEqual(fory, Locale.CHINESE, "LocaleSerializer");
    serDeCheckSerializerAndEqual(fory, Locale.ENGLISH, "LocaleSerializer");
    serDeCheckSerializerAndEqual(fory, Locale.TRADITIONAL_CHINESE, "LocaleSerializer");
    serDeCheckSerializerAndEqual(fory, Locale.CHINA, "LocaleSerializer");
    serDeCheckSerializerAndEqual(fory, Locale.TAIWAN, "LocaleSerializer");
    serDeCheckSerializerAndEqual(fory, Locale.getDefault(), "LocaleSerializer");
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
  }
}

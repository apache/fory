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

package org.apache.fory.graalvm.kotlin;

import java.util.Arrays;
import org.apache.fory.integration.kotlin.json.corpus.PlatformAccount;
import org.apache.fory.integration.kotlin.json.corpus.PlatformCorpusChecks;
import org.apache.fory.integration.kotlin.json.corpus.PlatformDefaults;
import org.apache.fory.integration.kotlin.json.corpus.PlatformJavaProfileMixin;
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.annotation.ForyJsonProvider;
import org.apache.fory.json.kotlin.ForyJsonKotlin;

/** Native Image acceptance application for provider-added Kotlin JSON capabilities. */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    check(
        KotlinJsonProvider.class.isAnnotationPresent(ForyJsonProvider.class),
        "Native configuration provider is not reachable");

    ForyJson json =
        ForyJsonKotlin.builder()
            .registerMixin(PlatformJavaProfileMixin.class)
            .withAsyncCompilation(false)
            .build();
    PlatformCorpusChecks.verifyRoundTrip(json);
    String account = "{\"id\":1,\"name\":\"test\",\"label\":null}";
    check(json.fromJson(account, PlatformAccount.class).getLabel() == null, "SET property");
    ForyJson skip = new KotlinJsonProvider().skippingConfiguration();
    check(
        "corpus-default".equals(skip.fromJson(account, PlatformAccount.class).getLabel()),
        "SKIP property");
    check(
        skip.fromJson("{\"values\":[null,1,null,2]}", PlatformDefaults.class)
            .getValues()
            .equals(Arrays.asList(1, 2)),
        "SKIP content");
    ForyJson fail = new KotlinJsonProvider().failingConfiguration();
    try {
      fail.fromJson(account, PlatformAccount.class);
      throw new AssertionError("FAIL property");
    } catch (ForyJsonException expected) {
      check(
          fail.fromJson("{\"id\":1,\"name\":\"test\"}", PlatformAccount.class).getId() == 1,
          "Failed root cleanup");
    }
    try {
      fail.fromJson("{\"values\":[null]}", PlatformDefaults.class);
      throw new AssertionError("FAIL content");
    } catch (ForyJsonException expected) {
      check(
          fail.fromJson("{\"values\":[1]}", PlatformDefaults.class)
              .getValues()
              .equals(Arrays.asList(1)),
          "Failed container cleanup");
    }
    System.out.println("Fory Kotlin JSON Native Image succeed");
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}

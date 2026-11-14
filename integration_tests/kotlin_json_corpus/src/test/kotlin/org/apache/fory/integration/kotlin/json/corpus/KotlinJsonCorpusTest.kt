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

package org.apache.fory.integration.kotlin.json.corpus

import org.apache.fory.json.kotlin.ForyJsonKotlin
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotSame
import org.testng.annotations.Test

public class KotlinJsonCorpusTest {
  @Test
  public fun defaultInclusion(): Unit {
    for (codegen in listOf(false, true)) {
      val json =
        ForyJsonKotlin.builder()
          .withCodegen(codegen)
          .withAsyncCompilation(false)
          .registerMixin(PlatformDefaultMixin::class.java)
          .build()
      assertEquals(json.toJson(PlatformDefaults()), "{\"retained\":7}")
      assertEquals(json.toJsonBytes(PlatformDefaults()).decodeToString(), "{\"retained\":7}")
      assertEquals(json.toPrettyJson(PlatformDefaultTarget()), "{ }")
      assertEquals(json.toPrettyJsonBytes(PlatformDefaultTarget()).decodeToString(), "{ }")
      assertEquals(json.toJson(PlatformDefaultTarget("漢")), "{\"text\":\"漢\"}")
      val first = json.fromJson("{}", PlatformDefaults::class.java)
      val second = json.fromJson("{}".encodeToByteArray(), PlatformDefaults::class.java)
      assertNotSame(first.values, second.values)
      first.values += 2
      assertEquals(second.values, listOf(1))
    }
  }

  @Test
  public fun mixinRepeatedDefaults(): Unit {
    for (codegen in listOf(false, true)) {
      val json =
        ForyJsonKotlin.builder()
          .withCodegen(codegen)
          .withAsyncCompilation(false)
          .registerMixin(PlatformMixinDefaults::class.java)
          .build()
      val value = PlatformMixinDefaultsTarget(PlatformToken("a"), PlatformLabel("b"))
      val text = json.toJson(value)
      assertEquals(text, "{\"token\":\"t:a\",\"label\":\"l:b\"}")
      assertEquals(json.fromJson(text, PlatformMixinDefaultsTarget::class.java), value)
    }
  }

  @Test
  public fun sharedRoundTrip(): Unit {
    val json =
      ForyJsonKotlin.builder()
        .registerMixin(PlatformJavaProfileMixin::class.java)
        .withAsyncCompilation(false)
        .build()
    PlatformCorpusChecks.verifyRoundTrip(json)
    val empty = PlatformJavaProfile("")
    assertEquals(json.toJson(empty), "{}")
    assertEquals(json.toJsonBytes(empty).decodeToString(), "{}")
  }
}

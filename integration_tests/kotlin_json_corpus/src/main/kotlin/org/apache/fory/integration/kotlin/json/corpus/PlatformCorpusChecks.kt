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

import org.apache.fory.json.ForyJson

/** Executes the same representative round trip on the JVM, Android, and Native Image. */
public object PlatformCorpusChecks {
  @JvmStatic
  public fun verifyRoundTrip(json: ForyJson) {
    val type = KotlinJsonCorpus.rootType()
    val decoded = json.fromJson(KotlinJsonCorpus.rootJson(), type)
    verifyRoot(decoded)
    val text = json.toJson(decoded, type)
    check(text.contains("\"display_label\":\"mixin\""))
    verifyRoot(json.fromJson(text, type))
    verifyRoot(json.fromJson(json.toJsonBytes(decoded, type), type))
  }

  private fun verifyRoot(actual: PlatformRoot) {
    val expected = KotlinJsonCorpus.rootValue()
    check(actual.account == expected.account)
    check(actual.id == expected.id)
    check(actual.unsigned == expected.unsigned)
    check(actual.shape == expected.shape)
    check(actual.profile.label == expected.profile.label)
    check(actual.token == expected.token)
    check(actual.box == expected.box)
  }
}

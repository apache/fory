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

package org.apache.fory.android

import org.apache.fory.integration.kotlin.json.corpus.PlatformCorpusChecks
import org.apache.fory.integration.kotlin.json.corpus.PlatformJavaProfileMixin
import org.apache.fory.json.annotation.JsonType
import org.apache.fory.json.annotation.JsonMixin
import org.apache.fory.json.annotation.JsonSubTypes
import org.apache.fory.json.kotlin.ForyJsonKotlin
import org.apache.fory.json.kotlin.jsonTypeRef

@JsonType
internal data class AndroidKotlinAccount(
    val id: Int,
    val name: String,
    val label: String? = "android-default",
)

@JsonType internal object AndroidKotlinMarker

@JsonType
internal data class AndroidKotlinDefaults(
    val id: Int,
    val v0: Int = 0,
    val v1: Int = 1,
    val v2: Int = 2,
    val v3: Int = 3,
    val v4: Int = 4,
    val v5: Int = 5,
    val v6: Int = 6,
    val v7: Int = 7,
    val v8: Int = 8,
    val v9: Int = 9,
    val v10: Int = 10,
    val v11: Int = 11,
    val v12: Int = 12,
    val v13: Int = 13,
    val v14: Int = 14,
    val v15: Int = 15,
    val v16: Int = 16,
    val v17: Int = 17,
    val v18: Int = 18,
    val v19: Int = 19,
    val v20: Int = 20,
    val v21: Int = 21,
    val v22: Int = 22,
    val v23: Int = 23,
    val v24: Int = 24,
    val v25: Int = 25,
    val v26: Int = 26,
    val v27: Int = 27,
    val v28: Int = 28,
    val v29: Int = 29,
    val v30: Int = 30,
    val v31: Int = 31,
    val v32: Int = 32,
)

internal object AndroidKotlinJsonScenarios {
    @JsonMixin(target = KspJavaShape::class)
    @JsonSubTypes(property = "kind")
    private interface KspJavaShapeMixin

    @JvmStatic
    fun metadataSurvivesMinification() {
        val json =
            ForyJsonKotlin.builder()
                .registerMixin(PlatformJavaProfileMixin::class.java)
                .withAsyncCompilation(false)
                .build()
        val accountType = jsonTypeRef<AndroidKotlinAccount>()
        val value = AndroidKotlinAccount(26, "android", null)
        check(json.fromJson(json.toJson(value, accountType), accountType) == value)
        check(
            json.fromJson("{\"id\":27,\"name\":\"default\"}", accountType) ==
                AndroidKotlinAccount(27, "default")
        )
        val defaults =
            json.fromJson(
                "{\"id\":28,\"v32\":320}",
                jsonTypeRef<AndroidKotlinDefaults>(),
            )
        check(defaults.v0 == 0)
        check(defaults.v31 == 31)
        check(defaults.v32 == 320)
        check(json.fromJson("{}", jsonTypeRef<AndroidKotlinMarker>()) === AndroidKotlinMarker)

        PlatformCorpusChecks.verifyRoundTrip(json)
    }

    @JvmStatic
    fun javaSealedMixinSurvivesMinification() {
        val json = ForyJsonKotlin.builder().registerMixin(KspJavaShapeMixin::class.java).build()
        val text = json.toJson(KspJavaShape.Circle(37), KspJavaShape::class.java)
        check(text == "{\"kind\":\"Circle\",\"radius\":37}")
        val decoded = json.fromJson(text, KspJavaShape::class.java)
        check(decoded is KspJavaShape.Circle && decoded.radius == 37)
    }

}

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

package org.apache.fory.json.kotlin

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.annotation.JsonProperty
import org.apache.fory.json.annotation.JsonProperty.NullHandling

@OptIn(ExperimentalUnsignedTypes::class)
class KotlinNullHandlingTest {
  data class Values(
    val text: String = "initial",
    val list: List<String> = listOf("initial"),
    val unsigned: UIntArray = uintArrayOf(7u),
    @get:JsonProperty(onNullRead = NullHandling.SET) val nullable: String? = "initial",
    @get:JsonProperty(onContentNullRead = NullHandling.SET)
    val retained: List<String?> = emptyList(),
  ) {
    var deferred: String = "initial"
  }

  data class Required(val text: String)

  data class Shallow(
    @get:JsonProperty(onContentNullRead = NullHandling.SKIP) val lists: List<List<String?>>,
    @get:JsonProperty(onContentNullRead = NullHandling.SKIP) val unsigned: UIntArray,
  )

  @Test
  fun propertyAndContentNulls() {
    for (mode in KotlinJsonTestMode.entries) {
      val json =
        newKotlinJson(mode) {
          onNullRead(NullHandling.SKIP)
          onContentNullRead(NullHandling.SKIP)
        }
      val input = """{"text":null,"list":null,"unsigned":null,"nullable":null,"deferred":null}"""
      repeat(if (mode == KotlinJsonTestMode.ASYNCHRONOUS) 2 else 1) {
        for (value in
          listOf(
            json.fromJson(input, Values::class.java),
            json.fromJson(input.toByteArray(), Values::class.java)
          )) {
          assertEquals("initial", value.text)
          assertEquals(listOf("initial"), value.list)
          assertContentEquals(uintArrayOf(7u), value.unsigned)
          assertEquals(null, value.nullable)
          assertEquals("initial", value.deferred)
        }
        val contents =
          """{"text":"中","list":[null,"a",null],"unsigned":[null,1,null,2],
          "retained":[null,"b"]}"""
        for (value in
          listOf(
            json.fromJson(contents, Values::class.java),
            json.fromJson(contents.toByteArray(), Values::class.java)
          )) {
          assertEquals(listOf("a"), value.list)
          assertContentEquals(uintArrayOf(1u, 2u), value.unsigned)
          assertEquals(listOf(null, "b"), value.retained)
        }
        assertEquals(
          "new",
          json.fromJson("""{"text":"new","text":null}""", Values::class.java).text
        )
        assertFailsWith<ForyJsonException> {
          json.fromJson("""{"text":null}""", Required::class.java)
        }
        assertEquals("ok", json.fromJson("""{"text":"ok"}""", Required::class.java).text)
        if (mode == KotlinJsonTestMode.ASYNCHRONOUS) awaitAsyncCodegen(json)
      }
    }
  }

  @Test
  fun shallowAndUnsignedRoots() {
    val defaults = ForyJsonKotlin.builder().withAsyncCompilation(false).build()
    val value =
      defaults.fromJson(
        """{"lists":[null,[null,"中"]],"unsigned":[null,1,null]}""",
        Shallow::class.java
      )
    assertEquals(listOf(listOf(null, "中")), value.lists)
    assertContentEquals(uintArrayOf(1u), value.unsigned)
    val skip = ForyJsonKotlin.builder().onContentNullRead(NullHandling.SKIP).build()
    assertContentEquals(ubyteArrayOf(1u), skip.fromJson("[null,1,null]", jsonTypeRef<UByteArray>()))
    assertContentEquals(
      ushortArrayOf(1u),
      skip.fromJson("[null,1,null]", jsonTypeRef<UShortArray>())
    )
    assertContentEquals(uintArrayOf(1u), skip.fromJson("[null,1,null]", jsonTypeRef<UIntArray>()))
    assertContentEquals(ulongArrayOf(1u), skip.fromJson("[null,1,null]", jsonTypeRef<ULongArray>()))
    assertEquals(
      mapOf(1u to "a"),
      skip.fromJson("""{"1":"a","1":null,"2":null}""", jsonTypeRef<Map<UInt, String>>())
    )
  }
}

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

import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.apache.fory.json.ForyJsonException
import org.apache.fory.type.Types

class ForyJsonKotlinTest {
  data class Account(val id: Long, val name: String, val label: String? = null)

  data class Box<T>(val value: T)

  data class Required(val id: Long, val name: String)

  data class EvaluatedDefault(
    val seed: Int,
    val values: MutableList<Int> = mutableListOf(nextDefault++),
  ) {
    companion object {
      var nextDefault: Int = 1
    }
  }

  data class ReferencedDefault(val base: Int, val derived: Int = base + 1)

  data class UnsignedValues(val count: UInt, val total: ULong, val optional: UInt?)

  @JvmInline value class SignedLongId(val value: Long)

  @JvmInline value class UnsignedLongId(val value: ULong)

  @OptIn(ExperimentalUnsignedTypes::class)
  data class LongStringValues(
    val signed: Long,
    val signedNullable: Long?,
    val unsigned: ULong,
    val nullable: ULong?,
    val unsignedArray: ULongArray,
    val unsignedList: List<ULong>,
    val unsignedMap: Map<String, ULong>,
    val signedId: SignedLongId,
    val unsignedId: UnsignedLongId,
    val atomic: AtomicLong,
  )

  object Marker

  @Test
  fun dataClass() {
    val fory = ForyJsonKotlin.builder().withAsyncCompilation(false).build()
    val account = Account(7, "Ada", "owner")
    val json = fory.toJson(account, jsonTypeRef<Account>())
    assertEquals(account, fory.fromJson(json, jsonTypeRef<Account>()))
    assertEquals(
      account,
      fory.fromJson(fory.toJsonBytes(account, jsonTypeRef<Account>()), jsonTypeRef<Account>())
    )
  }

  @Test
  fun defaultArgument() {
    val fory = ForyJsonKotlin.builder().withAsyncCompilation(false).build()
    assertEquals(
      Account(9, "default"),
      fory.fromJson("{\"id\":9,\"name\":\"default\"}", jsonTypeRef<Account>())
    )
    assertEquals(
      Account(9, "explicit", null),
      fory.fromJson("{\"id\":9,\"name\":\"explicit\",\"label\":null}", jsonTypeRef<Account>())
    )
    assertTrue(
      fory.toJson(Account(9, "default"), jsonTypeRef<Account>()).contains("\"label\":null")
    )
  }

  @Test
  fun requiredArguments() {
    val fory = ForyJsonKotlin.builder().withAsyncCompilation(false).build()
    assertFailsWith<ForyJsonException> { fory.fromJson("{\"id\":9}", jsonTypeRef<Required>()) }
    assertFailsWith<ForyJsonException> {
      fory.fromJson("{\"id\":9,\"name\":null}", jsonTypeRef<Required>())
    }
  }

  @Test
  fun evaluatesDefaultPerObject() {
    val fory = ForyJsonKotlin.builder().withAsyncCompilation(false).build()
    EvaluatedDefault.nextDefault = 1
    val first = fory.fromJson("{\"seed\":1}", jsonTypeRef<EvaluatedDefault>())
    val second = fory.fromJson("{\"seed\":2}", jsonTypeRef<EvaluatedDefault>())
    assertEquals(listOf(1), first.values)
    assertEquals(listOf(2), second.values)
    first.values += 9
    assertEquals(listOf(2), second.values)
  }

  @Test
  fun defaultReferencesEarlierArgument() {
    val fory = ForyJsonKotlin.builder().withAsyncCompilation(false).build()
    assertEquals(
      ReferencedDefault(41, 42),
      fory.fromJson("{\"base\":41}", jsonTypeRef<ReferencedDefault>()),
    )
  }

  @Test
  fun genericClass() {
    val fory = ForyJsonKotlin.builder().withAsyncCompilation(false).build()
    val type = jsonTypeRef<Box<List<String>>>()
    val value = Box(listOf("one", "two"))
    assertEquals(value, fory.fromJson(fory.toJson(value, type), type))
  }

  @Test
  fun singleton() {
    val fory = ForyJsonKotlin.builder().withAsyncCompilation(false).build()
    assertEquals("{}", fory.toJson(Marker, jsonTypeRef<Marker>()))
    assertSame(Marker, fory.fromJson("{}", jsonTypeRef<Marker>()))
  }

  @OptIn(ExperimentalUnsignedTypes::class)
  @Test
  fun unsignedValues() {
    val fory = ForyJsonKotlin.builder().withAsyncCompilation(false).build()
    val value = UnsignedValues(UInt.MAX_VALUE, ULong.MAX_VALUE, 17u)
    val json = fory.toJson(value, jsonTypeRef<UnsignedValues>())
    assertEquals(value, fory.fromJson(json, jsonTypeRef<UnsignedValues>()))
    assertEquals("4294967295", fory.toJson(UInt.MAX_VALUE, jsonTypeRef<UInt>()))
    assertEquals(UInt.MAX_VALUE, fory.fromJson("4294967295", jsonTypeRef<UInt>()))
    assertEquals(ULong.MAX_VALUE, fory.fromJson("18446744073709551615", jsonTypeRef<ULong>()))
    val listType = jsonTypeRef<List<UInt>>()
    assertEquals(listOf(0u, UInt.MAX_VALUE), fory.fromJson("[0,4294967295]", listType))
  }

  @OptIn(ExperimentalUnsignedTypes::class)
  @Test
  fun longAsString() {
    val value =
      LongStringValues(
        Long.MIN_VALUE,
        9_007_199_254_740_992L,
        ULong.MAX_VALUE,
        7uL,
        ulongArrayOf(0uL, ULong.MAX_VALUE),
        listOf(1uL, ULong.MAX_VALUE),
        linkedMapOf("max" to ULong.MAX_VALUE),
        SignedLongId(Long.MAX_VALUE),
        UnsignedLongId(ULong.MAX_VALUE),
        AtomicLong(Long.MAX_VALUE),
      )
    val type = jsonTypeRef<LongStringValues>()
    for (mode in listOf(KotlinJsonTestMode.INTERPRETED, KotlinJsonTestMode.SYNCHRONOUS)) {
      val json = newKotlinJson(mode) { writeLongAsString(true) }
      val encoded = json.toJson(value, type)
      assertTrue(encoded.contains("\"signed\":\"-9223372036854775808\""), encoded)
      assertTrue(encoded.contains("\"signedNullable\":\"9007199254740992\""), encoded)
      assertTrue(encoded.contains("\"unsigned\":\"18446744073709551615\""), encoded)
      assertTrue(encoded.contains("\"nullable\":\"7\""), encoded)
      assertTrue(
        encoded.contains("\"unsignedArray\":[\"0\",\"18446744073709551615\"]"),
        encoded,
      )
      assertTrue(
        encoded.contains("\"unsignedList\":[\"1\",\"18446744073709551615\"]"),
        encoded,
      )
      assertTrue(
        encoded.contains("\"unsignedMap\":{\"max\":\"18446744073709551615\"}"),
        encoded,
      )
      assertTrue(encoded.contains("\"signedId\":\"9223372036854775807\""), encoded)
      assertTrue(
        encoded.contains("\"unsignedId\":\"18446744073709551615\""),
        encoded,
      )
      assertTrue(encoded.contains("\"atomic\":\"9223372036854775807\""), encoded)
      val decoded = json.fromJson(encoded, type)
      assertEquals(value.signed, decoded.signed)
      assertEquals(value.signedNullable, decoded.signedNullable)
      assertEquals(value.unsigned, decoded.unsigned)
      assertEquals(value.nullable, decoded.nullable)
      assertTrue(value.unsignedArray.contentEquals(decoded.unsignedArray))
      assertEquals(value.unsignedList, decoded.unsignedList)
      assertEquals(value.unsignedMap, decoded.unsignedMap)
      assertEquals(value.signedId, decoded.signedId)
      assertEquals(value.unsignedId, decoded.unsignedId)
      assertEquals(value.atomic.get(), decoded.atomic.get())
      assertEquals(encoded, json.toJsonBytes(value, type).toString(Charsets.UTF_8))
      assertEquals(ULong.MAX_VALUE, json.fromJson("18446744073709551615", jsonTypeRef<ULong>()))
      assertEquals(
        ULong.MAX_VALUE,
        json.fromJson("\"18446744073709551615\"", jsonTypeRef<ULong>()),
      )
      assertFailsWith<ForyJsonException> {
        json.fromJson("\"18446744073709551616\"", jsonTypeRef<ULong>())
      }

      if (mode == KotlinJsonTestMode.SYNCHRONOUS) {
        val refs =
          generatedClassBytes(json, "LongStringVal")
            .filterKeys { it.contains("WriterForyJsonCodec") }
            .values
            .flatMap(::generatedMethodRefs)
        assertTrue(
          refs.any {
            it.owner == "org/apache/fory/json/kotlin/KotlinUnsignedCodecs" &&
              it.name == "writeULongAsStringRaw" &&
              it.descriptor == "(Lorg/apache/fory/json/writer/JsonWriter;J)V"
          },
          refs.toString(),
        )
        assertTrue(
          refs.any {
            it.owner == "org/apache/fory/json/writer/StringJsonWriter" &&
              it.name == "writeLongAsStringField"
          },
          refs.toString(),
        )
        assertTrue(
          refs.any {
            it.owner == "org/apache/fory/json/writer/Utf8JsonWriter" &&
              it.name == "writeLongAsStringField"
          },
          refs.toString(),
        )
        assertFalse(
          refs.any {
            it.owner == "org/apache/fory/json/resolver/JsonTypeResolver" &&
              it.name == "writeLongAsString" &&
              it.descriptor == "()Z"
          }
        )
      }
    }
  }

  @Test
  fun unsignedMetadata() {
    val model = KotlinMetadataModels.objectModel(jsonTypeRef<UnsignedValues>())
    assertEquals(
      listOf(Types.UINT32, Types.UINT64, Types.UINT32),
      model.propertyTypes().map { it.typeExtMeta.typeId() },
    )
  }
}

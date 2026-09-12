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

import java.util.Optional
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.apache.fory.json.ForyJson
import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.annotation.JsonProperty
import org.apache.fory.meta.TypeExtMeta
import org.apache.fory.reflect.TypeRef

class KotlinNullabilityRuntimeTest {
  data class ArrayValue(val text: String)

  data class ConstructorNulls(
    val required: String,
    val nullable: String?,
    val count: Int,
    val nullableCount: Int?,
    val defaultNullable: String? = "nullable-default",
    val defaultNonNull: String = "non-null-default",
  )

  data class CollectionModel(
    val readOnlyList: List<String>,
    val mutableList: MutableList<Int>,
    val readOnlySet: Set<String>,
    val mutableSet: MutableSet<Int>,
    val readOnlyMap: Map<String, Int>,
    val mutableMap: MutableMap<String, String>,
    val deque: ArrayDeque<Int>,
    val array: Array<String>,
  )

  data class OmissionModel(
    val id: Int,
    val defaultNull: String? = null,
    val requiredNull: String?,
  )

  class DeferredNullableModel(val id: Int) {
    var value: String? = "initializer"

    override fun equals(other: Any?): Boolean =
      other is DeferredNullableModel && id == other.id && value == other.value

    override fun hashCode(): Int = 31 * id + (value?.hashCode() ?: 0)
  }

  data class NullOmission(
    @get:JsonProperty(include = JsonProperty.Include.NON_NULL) val value: String?,
  )

  data class EmptyValues(
    val required: List<String>,
    val defaults: List<String> = listOf("default"),
    val nullable: String? = "default",
    val optional: Optional<String> = Optional.of("default"),
  ) {
    var deferred: String = "initializer"
  }

  data class EmptyOmission(
    @get:JsonProperty(include = JsonProperty.Include.NON_EMPTY)
    val value: List<String> = emptyList(),
  )

  class DeferredOmission {
    @get:JsonProperty(include = JsonProperty.Include.NON_EMPTY) var value: String = "initializer"
  }

  @JvmInline value class EmptyText(val value: String)

  data class ValueClassOmission(
    @get:JsonProperty(include = JsonProperty.Include.NON_EMPTY) val value: EmptyText,
  )

  @JvmInline value class TextSequence(val value: String) : CharSequence by value

  data class SequenceOmission(
    @get:JsonProperty(include = JsonProperty.Include.NON_EMPTY) val value: TextSequence,
  )

  data class GenericOmission<T>(
    @get:JsonProperty(include = JsonProperty.Include.NON_EMPTY) val value: T,
  )

  data class InclusionModel(
    var id: Int? = null,
    var name: String? = null,
    var list: List<String>? = null,
  )

  data class PropertyInclusion(
    val id: Int,
    @get:JsonProperty(include = JsonProperty.Include.NON_NULL) val name: String? = null,
    @JsonProperty(include = JsonProperty.Include.NON_EMPTY) val list: List<String>? = null,
    @get:JsonProperty(include = JsonProperty.Include.ALWAYS) val retained: List<String>? = null,
  )

  @Test
  fun propertyInclusion() {
    for (mode in KotlinJsonTestMode.entries) {
      for (inclusion in JsonProperty.Include.values()) {
        val json =
          newKotlinJson(mode) {
            if (inclusion != JsonProperty.Include.DEFAULT) defaultPropertyInclusion(inclusion)
          }
        val value = InclusionModel(1, list = emptyList())
        val expected =
          when (inclusion) {
            JsonProperty.Include.ALWAYS -> """{"id":1,"name":null,"list":[]}"""
            JsonProperty.Include.NON_EMPTY -> """{"id":1}"""
            else -> """{"id":1,"list":[]}"""
          }
        val annotated = PropertyInclusion(1, list = emptyList())
        repeat(if (mode == KotlinJsonTestMode.ASYNCHRONOUS) 2 else 1) {
          assertEquals(expected, json.toJson(value))
          assertEquals(expected, json.toJsonBytes(value).decodeToString())
          val type = jsonTypeRef<InclusionModel>()
          val decoded = json.fromJson(expected, type)
          assertEquals(decoded, json.fromJson(expected.toByteArray(), type))
          assertEquals(
            if (inclusion == JsonProperty.Include.NON_EMPTY) null else emptyList(),
            decoded.list
          )
          assertEquals("""{"id":1,"retained":null}""", json.toJson(annotated))
          assertEquals("""{"id":1,"retained":null}""", json.toJsonBytes(annotated).decodeToString())
          val retained = annotated.copy(retained = emptyList())
          assertEquals("""{"id":1,"retained":[]}""", json.toJson(retained))
          assertEquals("""{"id":1,"retained":[]}""", json.toJsonBytes(retained).decodeToString())
          val nonEmpty = annotated.copy(name = "kept", list = listOf("x"))
          val nonEmptyText = """{"id":1,"name":"kept","list":["x"],"retained":null}"""
          assertEquals(nonEmptyText, json.toJson(nonEmpty))
          assertEquals(nonEmptyText, json.toJsonBytes(nonEmpty).decodeToString())
          if (mode == KotlinJsonTestMode.ASYNCHRONOUS) awaitAsyncCodegen(json)
        }
      }
    }
  }

  @Test
  fun emptyInclusion() {
    for (codegen in listOf(false, true)) {
      val json =
        ForyJsonKotlin.builder()
          .withCodegen(codegen)
          .withAsyncCompilation(false)
          .defaultPropertyInclusion(JsonProperty.Include.NON_EMPTY)
          .build()
      val value = EmptyValues(emptyList(), emptyList(), null, Optional.empty())
      value.deferred = ""
      val type = jsonTypeRef<EmptyValues>()
      assertEquals("{}", json.toJson(value, type))
      assertEquals("{}", json.toJsonBytes(value, type).decodeToString())
      assertFailsWith<ForyJsonException> { json.fromJson("{}", type) }
      assertFailsWith<ForyJsonException> { json.fromJson("{}".toByteArray(), type) }
      val present = """{"required":[]}"""
      for (decoded in
        listOf(json.fromJson(present, type), json.fromJson(present.toByteArray(), type))) {
        assertEquals(emptyList(), decoded.required)
        assertEquals(listOf("default"), decoded.defaults)
        assertEquals("default", decoded.nullable)
        assertEquals(Optional.of("default"), decoded.optional)
        assertEquals("initializer", decoded.deferred)
      }
      val wrapped = ValueClassOmission(EmptyText(""))
      val wrappedType = jsonTypeRef<ValueClassOmission>()
      assertEquals("""{"value":""}""", json.toJson(wrapped, wrappedType))
      assertEquals(wrapped, json.fromJson(json.toJsonBytes(wrapped, wrappedType), wrappedType))
      val generic = GenericOmission(EmptyText(""))
      val genericType = jsonTypeRef<GenericOmission<EmptyText>>()
      assertEquals("""{"value":""}""", json.toJson(generic, genericType))
      assertEquals(generic, json.fromJson(json.toJsonBytes(generic, genericType), genericType))
      val empty = EmptyOmission()
      val emptyType = jsonTypeRef<EmptyOmission>()
      assertEquals("{}", json.toJson(empty, emptyType))
      assertEquals("{}", json.toJsonBytes(empty, emptyType).decodeToString())
      assertEquals(empty, json.fromJson("{}", emptyType))
      val deferred = DeferredOmission().apply { this.value = "" }
      assertEquals("{}", json.toJson(deferred))
      assertEquals("{}", json.toJsonBytes(deferred).decodeToString())
      assertEquals("initializer", json.fromJson("{}", jsonTypeRef<DeferredOmission>()).value)
      assertFailsWith<ForyJsonException> { json.toJson(SequenceOmission(TextSequence(""))) }
      assertEquals(
        "{}",
        json
          .toJsonBytes(
            GenericOmission(TextSequence("")),
            jsonTypeRef<GenericOmission<TextSequence>>()
          )
          .decodeToString()
      )
    }
  }

  @Test
  fun rootNullability() {
    forEachJsonMode { json ->
      val nonNull = jsonTypeRef<String>()
      val nullable = jsonTypeRef<String?>()
      assertFailsWith<ForyJsonException> { json.fromJson("null", nonNull) }
      assertFailsWith<ForyJsonException> { json.fromJson("null".toByteArray(), nonNull) }
      assertNull(json.fromJson("null", nullable))
      assertNull(json.fromJson("null".toByteArray(), nullable))
      assertEquals("null", json.toJson(null, nullable))
      assertEquals("null", json.toJsonBytes(null, nullable).decodeToString())
      assertEquals("漢字", json.fromJson("\"漢字\"", nonNull))
      assertEquals("text", json.fromJson("\"text\"".toByteArray(), nonNull))
    }
  }

  @Test
  fun constructorPresenceAndNull() {
    forEachJsonMode({ writeNullFields(true) }) { json ->
      val type = jsonTypeRef<ConstructorNulls>()
      val defaultsJson = """{"required":"漢","nullable":null,"count":1,"nullableCount":null}"""
      val defaults =
        ConstructorNulls(
          required = "漢",
          nullable = null,
          count = 1,
          nullableCount = null,
        )
      assertEquals(defaults, json.fromJson(defaultsJson, type))
      assertEquals(defaults, json.fromJson(defaultsJson.toByteArray(), type))

      val explicitNull =
        """{"required":"value","nullable":null,"count":1,"nullableCount":null,"defaultNullable":null,"defaultNonNull":"set"}"""
      assertEquals(
        ConstructorNulls("value", null, 1, null, null, "set"),
        json.fromJson(explicitNull, type),
      )

      assertFailsWith<ForyJsonException> {
        json.fromJson("""{"required":"value","count":1,"nullableCount":null}""", type)
      }
      assertFailsWith<ForyJsonException> {
        json.fromJson(
          """{"required":null,"nullable":null,"count":1,"nullableCount":null}""",
          type,
        )
      }
      assertFailsWith<ForyJsonException> {
        json.fromJson(
          """{"required":"value","nullable":null,"count":null,"nullableCount":null}""",
          type,
        )
      }
      assertFailsWith<ForyJsonException> {
        json.fromJson(
          """{"required":"value","nullable":null,"count":1,"nullableCount":null,"defaultNonNull":null}""",
          type,
        )
      }
      assertEquals(defaults, json.fromJson(json.toJson(defaults, type), type))
      assertEquals(defaults, json.fromJson(json.toJsonBytes(defaults, type), type))
    }
  }

  @Test
  fun nestedNullability() {
    forEachJsonMode { json ->
      val nullableList = jsonTypeRef<List<String?>>()
      assertEquals(listOf("a", null), json.fromJson("[\"a\",null]", nullableList))
      assertEquals(
        listOf("漢", null),
        json.fromJson("[\"漢\",null]", nullableList),
      )
      assertEquals(
        listOf("a", null),
        json.fromJson("[\"a\",null]".toByteArray(), nullableList),
      )
      assertFailsWith<ForyJsonException> {
        json.fromJson("[\"a\",null]", jsonTypeRef<List<String>>())
      }

      val nullableMap = jsonTypeRef<Map<String, String?>>()
      assertEquals(mapOf("value" to null), json.fromJson("{\"value\":null}", nullableMap))
      assertFailsWith<ForyJsonException> {
        json.fromJson("{\"value\":null}", jsonTypeRef<Map<String, String>>())
      }
      assertFailsWith<ForyJsonException> { json.fromJson("{}", jsonTypeRef<Map<String?, Int>>()) }

      val nullableArray = jsonTypeRef<Array<String?>>()
      assertContentEquals(
        arrayOf("a", null),
        json.fromJson("[\"a\",null]", nullableArray),
      )
      assertFailsWith<ForyJsonException> {
        json.fromJson("[\"a\",null]", jsonTypeRef<Array<String>>())
      }
    }
  }

  @Test
  fun boxedArrayNullability() {
    forEachJsonMode { json ->
      val ints = jsonTypeRef<Array<Int>>()
      val nullableInts = jsonTypeRef<Array<Int?>>()
      assertArrayRoundTrip(json, arrayOf(1, 2), ints)
      assertArrayRoundTrip(json, arrayOf(1, null), nullableInts)
      assertArrayNullRejected(json, ints, arrayOf(1, null), "[1,null]")
    }
  }

  @Test
  fun objectArrayNullability() {
    forEachJsonMode { json ->
      val values = jsonTypeRef<Array<ArrayValue>>()
      val nullableValues = jsonTypeRef<Array<ArrayValue?>>()
      assertArrayRoundTrip(json, arrayOf(ArrayValue("漢")), values)
      assertArrayRoundTrip(json, arrayOf(ArrayValue("a"), null), nullableValues)
      assertArrayNullRejected(
        json,
        values,
        arrayOf(ArrayValue("a"), null),
        """[{"text":"a"},null]""",
      )
    }
  }

  @Test
  fun atomicArrayNullability() {
    forEachJsonMode { json ->
      val atomic = jsonTypeRef<AtomicReferenceArray<String>>()
      val nullableAtomic = jsonTypeRef<AtomicReferenceArray<String?>>()
      assertAtomicRoundTrip(json, AtomicReferenceArray(arrayOf("a", "漢")), atomic)
      assertAtomicRoundTrip(json, AtomicReferenceArray(arrayOf("a", null)), nullableAtomic)
      assertFailsWith<ForyJsonException> { json.fromJson("[\"a\",null]", atomic) }
      assertFailsWith<ForyJsonException> { json.fromJson("[\"a\",null]".toByteArray(), atomic) }
      @Suppress("UNCHECKED_CAST")
      val invalidAtomic = AtomicReferenceArray(arrayOf("a", null)) as AtomicReferenceArray<String>
      assertFailsWith<ForyJsonException> { json.toJson(invalidAtomic, atomic) }
      assertFailsWith<ForyJsonException> { json.toJsonBytes(invalidAtomic, atomic) }
    }
  }

  @Test
  fun standardCollectionOwners() {
    val value =
      CollectionModel(
        readOnlyList = listOf("one", "two"),
        mutableList = mutableListOf(1, 2),
        readOnlySet = linkedSetOf("one", "two"),
        mutableSet = linkedSetOf(1, 2),
        readOnlyMap = linkedMapOf("one" to 1, "two" to 2),
        mutableMap = linkedMapOf("one" to "first"),
        deque = ArrayDeque(listOf(1, 2)),
        array = arrayOf("one", "two"),
      )
    forEachJsonMode { json ->
      val type = jsonTypeRef<CollectionModel>()
      val decoded = json.fromJson(json.toJson(value, type), type)
      assertCollections(value, decoded)
      assertCollections(value, json.fromJson(json.toJsonBytes(value, type), type))
      assertTrue(decoded.readOnlyList is ArrayList<*>)
      assertTrue(decoded.readOnlySet is LinkedHashSet<*>)
      assertTrue(decoded.readOnlyMap is LinkedHashMap<*, *>)
      @Suppress("UNCHECKED_CAST") (decoded.readOnlyList as MutableList<String>).add("mutable")
      assertEquals(listOf("one", "two", "mutable"), decoded.readOnlyList)
    }
  }

  @Test
  fun transparentWrapperNullability() {
    forEachJsonMode { json ->
      assertEquals(Optional.empty<String>(), json.fromJson("null", jsonTypeRef<Optional<String>>()))
      assertFailsWith<ForyJsonException> { json.fromJson("null", jsonTypeRef<Optional<String?>>()) }
      assertFailsWith<ForyJsonException> { json.fromJson("null", jsonTypeRef<Optional<String>?>()) }

      val childNullable = json.fromJson("null", jsonTypeRef<AtomicReference<String?>>())
      assertNull(childNullable.get())
      assertNull(json.fromJson("null", jsonTypeRef<AtomicReference<String>?>()))
      assertFailsWith<ForyJsonException> {
        json.fromJson("null", jsonTypeRef<AtomicReference<String>>())
      }
      assertFailsWith<ForyJsonException> {
        json.fromJson("null", jsonTypeRef<AtomicReference<String?>?>())
      }
    }
  }

  @Test
  fun nullInclusion() {
    KotlinJsonTestMode.entries.forEach { mode ->
      val json = newKotlinJson(mode) { writeNullFields(false) }
      val type = jsonTypeRef<OmissionModel>()
      val value = OmissionModel(id = 1, requiredNull = null)
      val text = json.toJson(value, type)
      assertEquals("""{"id":1}""", text)
      assertEquals(text, json.toJsonBytes(value, type).decodeToString())
      assertFailsWith<ForyJsonException> { json.fromJson(text, type) }
      assertFailsWith<ForyJsonException> { json.fromJson(text.toByteArray(), type) }

      val deferredType = jsonTypeRef<DeferredNullableModel>()
      val deferred = DeferredNullableModel(2).also { it.value = null }
      val deferredText = json.toJson(deferred, deferredType)
      assertEquals("""{"id":2}""", deferredText)
      assertEquals(deferredText, json.toJsonBytes(deferred, deferredType).decodeToString())
      assertEquals("initializer", json.fromJson(deferredText, deferredType).value)
      assertEquals("initializer", json.fromJson(deferredText.toByteArray(), deferredType).value)

      val always = newKotlinJson(mode) { writeNullFields(true) }
      assertEquals(value, always.fromJson(always.toJson(value, type), type))
      assertEquals(value, always.fromJson(always.toJsonBytes(value, type), type))
      assertEquals(deferred, always.fromJson(always.toJson(deferred, deferredType), deferredType))
      assertEquals(
        deferred,
        always.fromJson(always.toJsonBytes(deferred, deferredType), deferredType)
      )
      assertEquals("{}", always.toJson(NullOmission(null)))
      assertEquals("{}", always.toJsonBytes(NullOmission(null)).decodeToString())
    }
  }

  @Test
  fun unitAndNothingRoots() {
    forEachJsonMode { json ->
      val unit = jsonTypeRef<Unit>()
      assertEquals("{}", json.toJson(Unit, unit))
      assertEquals("{}", json.toJsonBytes(Unit, unit).decodeToString())
      assertSame(Unit, json.fromJson("{}", unit))
      assertSame(Unit, json.fromJson("{}".toByteArray(), unit))
      assertFailsWith<ForyJsonException> { json.fromJson("{\"value\":1}", unit) }
      assertFailsWith<ForyJsonException> { json.fromJson("null", unit) }

      val nullableUnit = jsonTypeRef<Unit?>()
      assertNull(json.fromJson("null", nullableUnit))
      assertSame(Unit, json.fromJson("{}", nullableUnit))
      assertEquals("null", json.toJson(null, nullableUnit))

      val nothing = jsonTypeRef<Nothing?>()
      assertNull(json.fromJson("null", nothing))
      assertNull(json.fromJson("null".toByteArray(), nothing))
      assertEquals("null", json.toJson(null, nothing))
      assertEquals("null", json.toJsonBytes(null, nothing).decodeToString())
      assertFailsWith<ForyJsonException> { json.fromJson("0", nothing) }
      val nonNullNothing =
        TypeRef.of(java.lang.Void::class.java, TypeExtMeta.of(0, false, false, false))
      assertFailsWith<ForyJsonException> { json.fromJson("null", nonNullNothing) }
      assertSame(Unit, json.fromJson("{}", unit))
    }
  }

  private fun assertCollections(expected: CollectionModel, actual: CollectionModel) {
    assertEquals(expected.readOnlyList, actual.readOnlyList)
    assertEquals(expected.mutableList, actual.mutableList)
    assertEquals(expected.readOnlySet, actual.readOnlySet)
    assertEquals(expected.mutableSet, actual.mutableSet)
    assertEquals(expected.readOnlyMap, actual.readOnlyMap)
    assertEquals(expected.mutableMap, actual.mutableMap)
    assertEquals(expected.deque, actual.deque)
    assertContentEquals(expected.array, actual.array)
  }

  private fun <T> assertArrayRoundTrip(json: ForyJson, value: Array<T>, type: TypeRef<Array<T>>) {
    assertContentEquals(value, json.fromJson(json.toJson(value, type), type))
    assertContentEquals(value, json.fromJson(json.toJsonBytes(value, type), type))
  }

  private fun <T : Any> assertArrayNullRejected(
    json: ForyJson,
    type: TypeRef<Array<T>>,
    nullableValue: Array<T?>,
    text: String,
  ) {
    assertFailsWith<ForyJsonException> { json.fromJson(text, type) }
    assertFailsWith<ForyJsonException> { json.fromJson(text.toByteArray(), type) }
    @Suppress("UNCHECKED_CAST") val invalid = nullableValue as Array<T>
    assertFailsWith<ForyJsonException> { json.toJson(invalid, type) }
    assertFailsWith<ForyJsonException> { json.toJsonBytes(invalid, type) }
  }

  private fun <T> assertAtomicRoundTrip(
    json: ForyJson,
    value: AtomicReferenceArray<T>,
    type: TypeRef<AtomicReferenceArray<T>>,
  ) {
    assertAtomicEquals(value, json.fromJson(json.toJson(value, type), type))
    assertAtomicEquals(value, json.fromJson(json.toJsonBytes(value, type), type))
  }

  private fun <T> assertAtomicEquals(
    expected: AtomicReferenceArray<T>,
    actual: AtomicReferenceArray<T>,
  ) {
    assertEquals(expected.length(), actual.length())
    repeat(expected.length()) { assertEquals(expected.get(it), actual.get(it)) }
  }
}

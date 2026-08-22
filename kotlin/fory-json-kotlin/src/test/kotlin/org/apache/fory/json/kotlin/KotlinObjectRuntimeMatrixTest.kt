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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import org.apache.fory.json.ForyJson
import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.annotation.JsonIgnore
import org.apache.fory.reflect.TypeRef

class KotlinObjectRuntimeMatrixTest {
  data class NestedModel(val id: Int, val text: String)

  data class OverloadedModel @JvmOverloads constructor(val value: String, val count: Int = 7)

  class DeferredModel(val id: Int) {
    var label: String = "initializer"

    override fun equals(other: Any?): Boolean =
      other is DeferredModel && id == other.id && label == other.label

    override fun hashCode(): Int = 31 * id + label.hashCode()
  }

  class JvmFieldModel(val id: Int) {
    @JvmField var label: String = "initializer"

    override fun equals(other: Any?): Boolean =
      other is JvmFieldModel && id == other.id && label == other.label

    override fun hashCode(): Int = 31 * id + label.hashCode()
  }

  class PrivateSetterModel(val id: Int) {
    var label: String = "initializer"
      private set
  }

  open class GenericBase<T> {
    var inherited: T? = null
  }

  class InheritedModel(val id: Int) : GenericBase<String>() {
    override fun equals(other: Any?): Boolean =
      other is InheritedModel && id == other.id && inherited == other.inherited

    override fun hashCode(): Int = 31 * id + (inherited?.hashCode() ?: 0)
  }

  class LateinitModel(val id: Int) {
    lateinit var required: String
  }

  class IgnoredComputed(val id: Int) {
    @get:JsonIgnore
    val computed: Int
      get() = id * 2

    override fun equals(other: Any?): Boolean = other is IgnoredComputed && id == other.id

    override fun hashCode(): Int = id
  }

  class ComputedModel(val id: Int) {
    val computed: Int
      get() = id * 2
  }

  class DelegatedModel(val id: Int) {
    val delegated: String by lazy { id.toString() }
  }

  inner class InnerModel(val id: Int)

  object Marker

  data object DataMarker

  object StatefulMarker {
    var state: Int = 1
  }

  class CompanionOwner {
    companion object
  }

  @Test
  fun ordinaryModelsAcrossRepresentations() {
    assertRoundTrip(NestedModel(7, "plain"), jsonTypeRef<NestedModel>())
    assertRoundTrip(
      NestedModel(8, "漢字"),
      jsonTypeRef<NestedModel>(),
      "{\"id\":8,\"text\":\"漢字\"}",
    )
  }

  @Test
  fun jvmOverloadsKeepsOneCreator() {
    forEachJsonMode { json ->
      val type = jsonTypeRef<OverloadedModel>()
      val value = OverloadedModel("漢")
      assertEquals(value, json.fromJson("{\"value\":\"漢\"}", type))
      assertEquals(value, json.fromJson("{\"value\":\"漢\"}".toByteArray(), type))
      assertEquals(value, json.fromJson(json.toJson(value, type), type))
      assertEquals(value, json.fromJson(json.toJsonBytes(value, type), type))
    }
  }

  @Test
  fun deferredAndInheritedProperties() {
    forEachJsonMode { json ->
      val type = jsonTypeRef<DeferredModel>()
      assertEquals(DeferredModel(1), json.fromJson("{\"id\":1}", type))
      val explicit = DeferredModel(1).also { it.label = "input" }
      assertEquals(explicit, json.fromJson("{\"id\":1,\"label\":\"input\"}", type))
      assertEquals(explicit, json.fromJson(json.toJsonBytes(explicit, type), type))

      val inheritedType = jsonTypeRef<InheritedModel>()
      val inherited = InheritedModel(2).also { it.inherited = "base" }
      assertEquals(inherited, json.fromJson(json.toJson(inherited, inheritedType), inheritedType))
      assertEquals(
        inherited,
        json.fromJson("{\"id\":2,\"inherited\":\"base\",\"unicode\":\"漢\"}", inheritedType),
      )
    }
  }

  @Test
  fun jvmFieldRoundTrip() {
    forEachJsonMode { json ->
      val type = jsonTypeRef<JvmFieldModel>()
      assertEquals(JvmFieldModel(1), json.fromJson("{\"id\":1}", type))
      val explicit = JvmFieldModel(1).also { it.label = "漢" }
      assertEquals(explicit, json.fromJson("{\"id\":1,\"label\":\"漢\"}", type))
      assertEquals(explicit, json.fromJson(json.toJson(explicit, type), type))
      assertEquals(explicit, json.fromJson(json.toJsonBytes(explicit, type), type))
    }
  }

  @Test
  fun privateSetterIsRejected() {
    val json = newKotlinJson(KotlinJsonTestMode.INTERPRETED)
    assertFailsWith<ForyJsonException> {
      json.fromJson(
        "{\"id\":1,\"label\":\"input\"}",
        jsonTypeRef<PrivateSetterModel>(),
      )
    }
  }

  @Test
  fun lateinitAndIgnoredBodyProperties() {
    forEachJsonMode { json ->
      val lateinitType = jsonTypeRef<LateinitModel>()
      assertFailsWith<ForyJsonException> { json.fromJson("{\"id\":1}", lateinitType) }
      val value = json.fromJson("{\"id\":1,\"required\":\"ready\"}", lateinitType)
      assertEquals(1, value.id)
      assertEquals("ready", value.required)

      val ignoredType = jsonTypeRef<IgnoredComputed>()
      val ignored = IgnoredComputed(3)
      assertEquals("{\"id\":3}", json.toJson(ignored, ignoredType))
      assertEquals(ignored, json.fromJson("{\"id\":3}", ignoredType))
    }
  }

  @Test
  fun unreconstructiblePropertiesAreRejected() {
    val json = newKotlinJson(KotlinJsonTestMode.INTERPRETED)
    assertFailsWith<ForyJsonException> {
      json.fromJson("{\"id\":1,\"computed\":2}", jsonTypeRef<ComputedModel>())
    }
    assertFailsWith<ForyJsonException> {
      json.fromJson("{\"id\":1,\"delegated\":\"1\"}", jsonTypeRef<DelegatedModel>())
    }
  }

  @Test
  fun unstableClassShapesAreRejected() {
    val json = newKotlinJson(KotlinJsonTestMode.INTERPRETED)
    assertFailsWith<ForyJsonException> { json.fromJson("{\"id\":1}", jsonTypeRef<InnerModel>()) }

    class LocalModel(val id: Int)
    assertFailsWith<ForyJsonException> { json.fromJson("{\"id\":1}", jsonTypeRef<LocalModel>()) }

    val anonymous =
      object {
        val id: Int = 1
      }
    assertFailsWith<ForyJsonException> { json.fromJson("{\"id\":1}", anonymous.javaClass) }
  }

  @Test
  fun singletonIdentityAndStrictShape() {
    forEachJsonMode { json ->
      assertSingleton(json, Marker, jsonTypeRef<Marker>())
      assertSingleton(json, DataMarker, jsonTypeRef<DataMarker>())
    }

    val budgeted = ForyJsonKotlin.builder().withCodegen(false).withMaxGraphMemoryBytes(1).build()
    assertSame(Marker, budgeted.fromJson("{}", jsonTypeRef<Marker>()))
    assertSame(DataMarker, budgeted.fromJson("{}", jsonTypeRef<DataMarker>()))
  }

  @Test
  fun statefulAndCompanionObjectsAreRejected() {
    val json = newKotlinJson(KotlinJsonTestMode.INTERPRETED)
    assertFailsWith<ForyJsonException> { json.fromJson("{}", jsonTypeRef<StatefulMarker>()) }
    assertFailsWith<ForyJsonException> {
      json.fromJson("{}", jsonTypeRef<CompanionOwner.Companion>())
    }
  }

  private fun <T : Any> assertSingleton(json: ForyJson, value: T, type: TypeRef<T>) {
    assertEquals("{}", json.toJson(value, type))
    assertEquals("{}", json.toJsonBytes(value, type).decodeToString())
    assertSame(value, json.fromJson("{}", type))
    assertSame(value, json.fromJson("{}".toByteArray(), type))
    assertFailsWith<ForyJsonException> { json.fromJson("{\"unexpected\":1}", type) }
    assertFailsWith<ForyJsonException> { json.fromJson("null", type) }
    assertSame(value, json.fromJson("{}", type))
  }

  private fun <T> assertRoundTrip(value: T, type: TypeRef<T>, utf16Json: String? = null) {
    forEachJsonMode { json ->
      val text = json.toJson(value, type)
      assertEquals(value, json.fromJson(text, type))
      assertEquals(value, json.fromJson(json.toJsonBytes(value, type), type))
      if (utf16Json != null) assertEquals(value, json.fromJson(utf16Json, type))
    }
  }
}

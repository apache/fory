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
import kotlin.jvm.JvmInline
import kotlin.jvm.JvmName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.PropertyNamingStrategy
import org.apache.fory.json.annotation.JsonCodec
import org.apache.fory.json.annotation.JsonCreator
import org.apache.fory.json.annotation.JsonIgnore
import org.apache.fory.json.annotation.JsonMixin
import org.apache.fory.json.annotation.JsonProperty
import org.apache.fory.json.annotation.JsonUnwrapped
import org.apache.fory.json.codec.AbstractJsonValueCodec
import org.apache.fory.json.codec.MapKeyCodec
import org.apache.fory.json.reader.JsonReader
import org.apache.fory.json.writer.JsonWriter

class TaggedKotlinStringCodec : AbstractJsonValueCodec<String>() {
  override fun write(writer: JsonWriter, value: String) {
    writer.writeString("tag:$value")
  }

  override fun read(reader: JsonReader): String {
    val value = reader.readString()
    if (!value.startsWith("tag:")) {
      throw ForyJsonException("Expected tagged Kotlin string")
    }
    return value.substring(4)
  }
}

class OtherKotlinStringCodec : AbstractJsonValueCodec<String>() {
  override fun write(writer: JsonWriter, value: String) {
    writer.writeString("other:$value")
  }

  override fun read(reader: JsonReader): String = reader.readString().removePrefix("other:")
}

class TaggedIntKeyCodec : MapKeyCodec {
  override fun toName(key: Any): String = "key:$key"

  override fun fromName(name: String): Any {
    if (!name.startsWith("key:")) throw ForyJsonException("Expected tagged integer key")
    return name.substring(4).toInt()
  }
}

@JvmInline value class ExplicitCreatorId(val value: Int)

@JvmInline internal value class FlattenedCount(val value: Int)

internal data class FlattenedLeaf(val id: FlattenedCount, val label: String = "leaf")

internal data class FlattenedMiddle(
  @get:JsonUnwrapped(prefix = "leaf_") val leaf: FlattenedLeaf,
)

internal data class FlattenedRoot(
  @get:JsonUnwrapped(prefix = "middle_") val middle: FlattenedMiddle,
  val tag: String = "root",
)

class KotlinAnnotationRuntimeTest {
  data class UseSiteModel(
    @field:JsonCodec(TaggedKotlinStringCodec::class) val fieldValue: String,
    @get:JsonCodec(TaggedKotlinStringCodec::class) val getterValue: String,
    @param:JsonCodec(TaggedKotlinStringCodec::class) val parameterValue: String,
    @set:JsonCodec(TaggedKotlinStringCodec::class) var setterValue: String,
    @setparam:JsonCodec(TaggedKotlinStringCodec::class) var setterParameterValue: String,
    @field:JsonCodec(TaggedKotlinStringCodec::class)
    @get:JsonCodec(TaggedKotlinStringCodec::class)
    val mergedValue: String,
  )

  data class ChildCodecModel(
    @field:JsonCodec(elementCodec = TaggedKotlinStringCodec::class) val values: List<String>,
    @field:JsonCodec(contentCodec = TaggedKotlinStringCodec::class) val optional: Optional<String>,
    @field:JsonCodec(valueCodec = TaggedKotlinStringCodec::class) val entries: Map<String, String>,
    @field:JsonCodec(keyCodec = TaggedIntKeyCodec::class) val keyed: Map<Int, String>,
  )

  data class ConflictingCodec(
    @field:JsonCodec(TaggedKotlinStringCodec::class)
    @get:JsonCodec(OtherKotlinStringCodec::class)
    val value: String,
  )

  data class InvalidSetterProperty(
    @setparam:JsonProperty("renamed") var value: String,
  )

  data class PropertyUseSites(
    @field:JsonProperty("field_name") val fieldValue: String,
    @get:JsonProperty("getter_name") val getterValue: String,
    @param:JsonProperty("parameter_name") val parameterValue: String,
    @set:JsonProperty("setter_name") var setterValue: String,
    @param:JsonProperty("bare_name") val bareValue: String,
  )

  class SecondaryCreator
  private constructor(
    val value: String,
    val count: Int,
    @get:JsonIgnore val selected: Boolean,
  ) {
    @JsonCreator(value = ["value", "count"])
    constructor(
      sourceValue: String,
      sourceCount: Int = 7,
    ) : this(sourceValue, sourceCount, true)

    override fun equals(other: Any?): Boolean =
      other is SecondaryCreator &&
        value == other.value &&
        count == other.count &&
        selected == other.selected

    override fun hashCode(): Int = 31 * (31 * value.hashCode() + count) + selected.hashCode()
  }

  class FactoryCreator
  private constructor(
    @get:JsonProperty("wire_value") val value: String,
    @get:JsonIgnore val selected: Boolean,
  ) {
    companion object {
      @JvmStatic
      @JsonCreator
      fun create(@JsonProperty("wire_value") sourceValue: String): FactoryCreator =
        FactoryCreator(sourceValue, true)
    }

    override fun equals(other: Any?): Boolean =
      other is FactoryCreator && value == other.value && selected == other.selected

    override fun hashCode(): Int = 31 * value.hashCode() + selected.hashCode()
  }

  class MangledFactoryCreator
  private constructor(
    @get:JsonProperty("wire_value") val value: String,
  ) {
    companion object {
      @JvmStatic
      @JvmName("create-value")
      @JsonCreator
      fun create(@JsonProperty("wire_value") sourceValue: String): MangledFactoryCreator =
        MangledFactoryCreator(sourceValue)
    }

    override fun equals(other: Any?): Boolean =
      other is MangledFactoryCreator && value == other.value

    override fun hashCode(): Int = value.hashCode()
  }

  class MixinSelectedCreator {
    val displayName: String
    @get:JsonIgnore val route: String

    constructor(sourceText: String) {
      displayName = sourceText
      route = "text"
    }

    constructor(sourceNumber: Int) {
      displayName = sourceNumber.toString()
      route = "number"
    }

    override fun equals(other: Any?): Boolean =
      other is MixinSelectedCreator && displayName == other.displayName && route == other.route

    override fun hashCode(): Int = 31 * displayName.hashCode() + route.hashCode()
  }

  @JsonMixin(target = MixinSelectedCreator::class)
  abstract class MixinSelectedCreatorAnnotations
  @JsonCreator(value = ["displayName"])
  constructor(sourceText: String)

  class ValueClassCreator
  private constructor(
    val id: ExplicitCreatorId,
    @get:JsonIgnore val selected: Boolean,
  ) {
    @JsonCreator(value = ["id"]) constructor(sourceId: ExplicitCreatorId) : this(sourceId, true)

    override fun equals(other: Any?): Boolean =
      other is ValueClassCreator && id == other.id && selected == other.selected

    override fun hashCode(): Int = 31 * id.hashCode() + selected.hashCode()
  }

  @Test
  fun codecUseSites() {
    val value = UseSiteModel("field", "getter", "parameter", "setter", "setparam", "merged")
    forEachJsonMode { json ->
      val type = jsonTypeRef<UseSiteModel>()
      val text = json.toJson(value, type)
      listOf("field", "getter", "parameter", "setter", "setparam", "merged").forEach {
        assertTrue(text.contains("\"tag:$it\""), text)
      }
      assertEquals(value, json.fromJson(text, type))
      assertEquals(value, json.fromJson(json.toJsonBytes(value, type), type))
      assertEquals(
        UseSiteModel("漢", "getter", "parameter", "setter", "setparam", "merged"),
        json.fromJson(
          """{"fieldValue":"tag:漢","getterValue":"tag:getter","parameterValue":"tag:parameter","setterValue":"tag:setter","setterParameterValue":"tag:setparam","mergedValue":"tag:merged"}""",
          type,
        ),
      )
    }
  }

  @Test
  fun childCodecSelections() {
    val value =
      ChildCodecModel(
        values = listOf("one", "two"),
        optional = Optional.of("optional"),
        entries = linkedMapOf("first" to "value"),
        keyed = linkedMapOf(7 to "seven"),
      )
    forEachJsonMode { json ->
      val type = jsonTypeRef<ChildCodecModel>()
      val text = json.toJson(value, type)
      assertTrue(text.contains("[\"tag:one\",\"tag:two\"]"), text)
      assertTrue(text.contains("\"optional\":\"tag:optional\""), text)
      assertTrue(text.contains("\"first\":\"tag:value\""), text)
      assertTrue(text.contains("\"key:7\":\"seven\""), text)
      assertEquals(value, json.fromJson(text, type))
      assertEquals(value, json.fromJson(json.toJsonBytes(value, type), type))
    }
  }

  @Test
  fun propertyUseSites() {
    val value = PropertyUseSites("field", "getter", "parameter", "setter", "bare")
    forEachJsonMode { json ->
      val type = jsonTypeRef<PropertyUseSites>()
      val text = json.toJson(value, type)
      listOf("field_name", "getter_name", "parameter_name", "setter_name", "bare_name").forEach {
        assertTrue(text.contains("\"$it\""), text)
      }
      assertEquals(value, json.fromJson(text, type))
      assertEquals(value, json.fromJson(json.toJsonBytes(value, type), type))
    }
  }

  @Test
  fun annotationConflictsAreColdFailures() {
    val json = newKotlinJson(KotlinJsonTestMode.INTERPRETED)
    assertFailsWith<ForyJsonException> {
      json.fromJson("{\"value\":\"tag:value\"}", jsonTypeRef<ConflictingCodec>())
    }
    assertFailsWith<ForyJsonException> {
      json.fromJson("{\"renamed\":\"value\"}", jsonTypeRef<InvalidSetterProperty>())
    }
  }

  @Test
  fun explicitSecondaryCreator() {
    forEachJsonMode { json ->
      val type = jsonTypeRef<SecondaryCreator>()
      val defaulted = SecondaryCreator("漢")
      assertEquals(defaulted, json.fromJson("{\"value\":\"漢\"}", type))
      assertEquals(defaulted, json.fromJson("{\"value\":\"漢\"}".toByteArray(), type))
      assertEquals(defaulted, json.fromJson(json.toJson(defaulted, type), type))
      assertEquals(defaulted, json.fromJson(json.toJsonBytes(defaulted, type), type))
      assertFailsWith<ForyJsonException> { json.fromJson("{}", type) }
      assertFailsWith<ForyJsonException> { json.fromJson("{\"value\":null}", type) }
    }
  }

  @Test
  fun explicitStaticFactory() {
    forEachJsonMode { json ->
      val type = jsonTypeRef<FactoryCreator>()
      val expected = FactoryCreator.create("漢")
      assertEquals(expected, json.fromJson("{\"wire_value\":\"漢\"}", type))
      assertEquals(expected, json.fromJson("{\"wire_value\":\"漢\"}".toByteArray(), type))
      assertEquals(expected, json.fromJson(json.toJson(expected, type), type))
      assertEquals(expected, json.fromJson(json.toJsonBytes(expected, type), type))
      assertFailsWith<ForyJsonException> { json.fromJson("{\"wire_value\":null}", type) }
    }
  }

  @Test
  fun mangledStaticFactory() {
    forEachJsonMode { json ->
      val type = jsonTypeRef<MangledFactoryCreator>()
      val expected = MangledFactoryCreator.create("漢")
      assertEquals(expected, json.fromJson("{\"wire_value\":\"漢\"}", type))
      assertEquals(expected, json.fromJson(json.toJsonBytes(expected, type), type))
    }
  }

  @Test
  fun mixinCreatorMapping() {
    KotlinJsonTestMode.entries.forEach { mode ->
      val json =
        newKotlinJson(mode) {
          registerMixin(MixinSelectedCreatorAnnotations::class.java)
          withPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)
        }
      val type = jsonTypeRef<MixinSelectedCreator>()
      val expected = MixinSelectedCreator("漢")
      assertEquals(expected, json.fromJson("{\"display_name\":\"漢\"}", type))
      assertEquals(expected, json.fromJson("{\"display_name\":\"漢\"}".toByteArray(), type))
      assertEquals("{\"display_name\":\"漢\"}", json.toJson(expected, type))
      assertEquals(expected, json.fromJson(json.toJsonBytes(expected, type), type))
    }
  }

  @Test
  fun valueClassCreator() {
    forEachJsonMode { json ->
      val type = jsonTypeRef<ValueClassCreator>()
      val expected = ValueClassCreator(ExplicitCreatorId(17))
      assertEquals(expected, json.fromJson("{\"id\":17}", type))
      assertEquals(expected, json.fromJson("{\"id\":17}".toByteArray(), type))
      assertEquals(expected, json.fromJson(json.toJson(expected, type), type))
      assertEquals(expected, json.fromJson(json.toJsonBytes(expected, type), type))
    }
  }

  @Test
  fun unwrappedDefaultsAndValueClass() {
    val type = jsonTypeRef<FlattenedRoot>()
    val defaulted = FlattenedRoot(FlattenedMiddle(FlattenedLeaf(FlattenedCount(17))))
    val utf16 = FlattenedRoot(FlattenedMiddle(FlattenedLeaf(FlattenedCount(17), "雪")))
    forEachJsonMode { json ->
      assertEquals(defaulted, json.fromJson("{\"middle_leaf_id\":17}", type))
      assertEquals(
        defaulted,
        json.fromJson("{\"middle_leaf_id\":17}".toByteArray(), type),
      )
      assertEquals(
        utf16,
        json.fromJson("{\"middle_leaf_id\":17,\"middle_leaf_label\":\"雪\"}", type),
      )
      assertEquals(defaulted, json.fromJson(json.toJson(defaulted, type), type))
      assertEquals(utf16, json.fromJson(json.toJsonBytes(utf16, type), type))
    }

    val generated = newKotlinJson(KotlinJsonTestMode.SYNCHRONOUS)
    generated.toJson(defaulted, type)
    generated.toJsonBytes(defaulted, type)
    generated.fromJson("{\"middle_leaf_id\":17}", type)
    generated.fromJson("{\"middle_leaf_id\":17,\"middle_leaf_label\":\"雪\"}", type)
    generated.fromJson("{\"middle_leaf_id\":17}".toByteArray(), type)
    val classes = generatedClassBytes(generated, "FlattenedRoot")
    assertEquals(3, classes.count { it.key.contains("ReaderForyJsonCodec") })
    assertEquals(2, classes.count { it.key.contains("WriterForyJsonCodec") })
    val leafOwner = FlattenedLeaf::class.java.name.replace('.', '/')
    val countOwner = FlattenedCount::class.java.name.replace('.', '/')
    val refs = classes.values.flatMap(::generatedMethodRefs)
    assertTrue(
      refs.any { it.owner == leafOwner && it.name.startsWith("getId-") && it.descriptor == "()I" },
      refs.toString(),
    )
    assertTrue(
      refs.any {
        it.owner == countOwner && it.name == "constructor-impl" && it.descriptor == "(I)I"
      },
      refs.toString(),
    )
  }
}

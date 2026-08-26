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

import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.annotation.JsonSubTypes

@JsonSubTypes(
  value =
    [
      JsonSubTypes.Type(value = PropertyCircle::class, name = "circle"),
      JsonSubTypes.Type(value = PropertyMarker::class, name = "marker"),
      JsonSubTypes.Type(value = ListedOpenBranch::class, name = "open"),
    ],
  property = "kind",
)
sealed interface PropertyShape

data class PropertyCircle(val radius: Int) : PropertyShape

data object PropertyMarker : PropertyShape

open class ListedOpenBranch(val value: Int) : PropertyShape

class UnlistedDescendant(value: Int) : ListedOpenBranch(value)

data class UnlistedShape(val value: String) : PropertyShape

@JsonSubTypes(
  value =
    [
      JsonSubTypes.Type(value = WrappedData::class, name = "data"),
      JsonSubTypes.Type(value = WrappedNumber::class, name = "number"),
    ],
  inclusion = JsonSubTypes.Inclusion.WRAPPER_OBJECT,
)
sealed interface ObjectWrappedShape

data class WrappedData(val value: String) : ObjectWrappedShape

@JvmInline value class WrappedNumber(val value: Int) : ObjectWrappedShape

@JsonSubTypes(
  value = [JsonSubTypes.Type(value = ArrayWrappedData::class, name = "data")],
  inclusion = JsonSubTypes.Inclusion.WRAPPER_ARRAY,
)
sealed interface ArrayWrappedShape

data class ArrayWrappedData(val value: String) : ArrayWrappedShape

@JsonSubTypes(
  value = [JsonSubTypes.Type(value = InvalidPropertyNumber::class, name = "number")],
  property = "kind",
)
sealed interface InvalidPropertyShape

@JvmInline value class InvalidPropertyNumber(val value: Int) : InvalidPropertyShape

@JsonSubTypes(property = "kind") sealed interface InferredShape

data class InferredCircle(val radius: Int) : InferredShape

data object InferredMarker : InferredShape

sealed interface InferredBranch : InferredShape

data class InferredLeaf(val value: String) : InferredBranch

open class InferredOpen(val value: Int) : InferredShape

class InferredDescendant(value: Int) : InferredOpen(value)

@JsonSubTypes(property = "kind") sealed interface InvalidInferredShape

abstract class OpenAbstractBranch : InvalidInferredShape

class KotlinSealedRuntimeTest {
  @Test
  fun propertyShape() {
    forEachJsonMode { json ->
      val type = jsonTypeRef<PropertyShape>()
      val circle: PropertyShape = PropertyCircle(3)
      assertEquals("{\"kind\":\"circle\",\"radius\":3}", json.toJson(circle, type))
      assertEquals(circle, json.fromJson(json.toJson(circle, type), type))
      assertEquals(circle, json.fromJson(json.toJsonBytes(circle, type), type))

      val marker: PropertyShape = PropertyMarker
      assertEquals("{\"kind\":\"marker\"}", json.toJson(marker, type))
      assertSame(PropertyMarker, json.fromJson("{\"kind\":\"marker\"}", type))
      assertSame(
        PropertyMarker,
        json.fromJson("{\"kind\":\"marker\"}".toByteArray(), type),
      )

      val nullable = jsonTypeRef<PropertyShape?>()
      assertNull(json.fromJson("null", nullable))
      assertEquals("null", json.toJson(null, nullable))
    }
  }

  @Test
  fun discriminatorFailures() {
    forEachJsonMode { json ->
      val type = jsonTypeRef<PropertyShape>()
      assertFailsWith<ForyJsonException> { json.fromJson("{\"radius\":3}", type) }
      assertFailsWith<ForyJsonException> {
        json.fromJson("{\"kind\":\"unknown\",\"radius\":3}", type)
      }
      assertFailsWith<ForyJsonException> {
        json.fromJson("{\"kind\":\"circle\",\"kind\":\"circle\",\"radius\":3}", type)
      }
      assertFailsWith<ForyJsonException> {
        json.fromJson("{\"kind\":\"marker\",\"unexpected\":1}", type)
      }
      assertEquals(PropertyCircle(4), json.fromJson("{\"kind\":\"circle\",\"radius\":4}", type))
    }
  }

  @Test
  fun unlistedRuntimeTypes() {
    forEachJsonMode { json ->
      val type = jsonTypeRef<PropertyShape>()
      assertFailsWith<ForyJsonException> { json.toJson(UnlistedShape("value"), type) }
      assertFailsWith<ForyJsonException> { json.toJson(UnlistedDescendant(1), type) }
      assertEquals(
        ListedOpenBranch::class.java,
        json.fromJson("{\"kind\":\"open\",\"value\":1}", type)::class.java,
      )
    }
  }

  @Test
  fun wrapperShapes() {
    forEachJsonMode { json ->
      val objectType = jsonTypeRef<ObjectWrappedShape>()
      val data: ObjectWrappedShape = WrappedData("漢")
      assertEquals("{\"data\":{\"value\":\"漢\"}}", json.toJson(data, objectType))
      assertEquals(data, json.fromJson(json.toJson(data, objectType), objectType))
      assertEquals(data, json.fromJson(json.toJsonBytes(data, objectType), objectType))

      val number: ObjectWrappedShape = WrappedNumber(9)
      assertEquals("{\"number\":9}", json.toJson(number, objectType))
      assertEquals(number, json.fromJson("{\"number\":9}", objectType))

      val arrayType = jsonTypeRef<ArrayWrappedShape>()
      val array: ArrayWrappedShape = ArrayWrappedData("value")
      assertEquals("[\"data\",{\"value\":\"value\"}]", json.toJson(array, arrayType))
      assertEquals(array, json.fromJson(json.toJson(array, arrayType), arrayType))
      assertEquals(array, json.fromJson(json.toJsonBytes(array, arrayType), arrayType))
    }
  }

  @Test
  fun propertyRequiresObjectBranch() {
    val json = newKotlinJson(KotlinJsonTestMode.INTERPRETED)
    assertFailsWith<ForyJsonException> {
      json.fromJson("{\"kind\":\"number\",\"value\":1}", jsonTypeRef<InvalidPropertyShape>())
    }
  }

  @Test
  fun inferredClosure() {
    forEachJsonMode { json ->
      val type = jsonTypeRef<InferredShape>()
      val values =
        listOf<InferredShape>(
          InferredCircle(3),
          InferredMarker,
          InferredLeaf("leaf"),
          InferredOpen(4),
        )
      val names = listOf("InferredCircle", "InferredMarker", "InferredLeaf", "InferredOpen")
      values.zip(names).forEach { (value, name) ->
        val text = json.toJson(value, type)
        assertEquals(true, text.contains("\"kind\":\"$name\""), text)
        for (decoded in
          listOf(json.fromJson(text, type), json.fromJson(text.toByteArray(), type))) {
          assertEquals(value::class, decoded::class)
          if (value is InferredOpen) assertEquals(value.value, (decoded as InferredOpen).value)
          else assertEquals(value, decoded)
        }
      }
      assertFailsWith<ForyJsonException> { json.toJson(InferredDescendant(5), type) }
    }
  }

  @Test
  fun inferredCheckerSubset() {
    KotlinJsonTestMode.entries.forEach { mode ->
      val json =
        newKotlinJson(mode) {
          withTypeChecker { name, _ -> name != InferredMarker::class.java.name }
        }
      val type = jsonTypeRef<InferredShape>()
      assertFailsWith<ForyJsonException> { json.toJson(InferredMarker, type) }
      assertEquals(
        InferredLeaf("accepted"),
        json.fromJson("{\"kind\":\"InferredLeaf\",\"value\":\"accepted\"}", type),
      )
    }
  }

  @Test
  fun rejectsInvalidInferredHierarchy() {
    val json = newKotlinJson(KotlinJsonTestMode.INTERPRETED)
    assertFailsWith<ForyJsonException> { json.fromJson("{}", jsonTypeRef<InvalidInferredShape>()) }
  }
}

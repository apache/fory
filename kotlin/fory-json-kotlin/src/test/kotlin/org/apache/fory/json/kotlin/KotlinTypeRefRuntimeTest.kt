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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.annotation.JsonMixin
import org.apache.fory.json.annotation.JsonMixinRemove
import org.apache.fory.json.annotation.JsonSubTypes

private typealias StringTokenBox = TokenBox<String>

data class TokenBox<T>(val value: T)

data class CovariantBox<out T>(val value: T)

data class NonNullBoundBox<T : Any>(val value: T)

class InvariantBox<T>(val value: T) {
  override fun equals(other: Any?): Boolean = other is InvariantBox<*> && value == other.value

  override fun hashCode(): Int = value?.hashCode() ?: 0
}

@JsonSubTypes(
  value = [JsonSubTypes.Type(value = DirectProjectionCircle::class, name = "circle")],
  property = "kind",
)
interface DirectProjectionShape

data class DirectProjectionCircle(val radius: Int) : DirectProjectionShape

data class DirectProjectionSquare(val size: Int) : DirectProjectionShape

data class DirectProjectionHolder(val value: InvariantBox<out DirectProjectionShape>)

@JsonMixin(target = DirectProjectionShape::class)
@JsonMixinRemove(value = [JsonSubTypes::class])
interface ProjectionRemovalMixin

@JsonMixin(target = DirectProjectionShape::class)
@JsonSubTypes(
  value = [JsonSubTypes.Type(value = DirectProjectionSquare::class, name = "square")],
  property = "kind",
)
interface ProjectionReplacementMixin

interface ContributedProjectionShape

data class ContributedProjectionValue(val label: String) : ContributedProjectionShape

data class ContributedProjectionHolder(val value: InvariantBox<out ContributedProjectionShape>)

@JsonMixin(target = ContributedProjectionShape::class)
@JsonSubTypes(
  value = [JsonSubTypes.Type(value = ContributedProjectionValue::class, name = "value")],
  property = "kind",
)
interface ProjectionContributionMixin

class KotlinTypeRefRuntimeTest {
  @Test
  fun typeAliasUsesExpandedBinding() {
    val alias = jsonTypeRef<StringTokenBox>()
    val expanded = jsonTypeRef<TokenBox<String>>()
    assertEquals(expanded, alias)

    forEachJsonMode { json ->
      val value = TokenBox("漢")
      assertEquals(value, json.fromJson(json.toJson(value, alias), alias))
      assertEquals(value, json.fromJson(json.toJsonBytes(value, alias), alias))
    }
  }

  @Test
  fun declarationAndUseSiteVariance() {
    forEachJsonMode { json ->
      val covariant = jsonTypeRef<CovariantBox<String>>()
      val covariantValue = CovariantBox("value")
      assertEquals(
        covariantValue,
        json.fromJson(json.toJson(covariantValue, covariant), covariant),
      )

      val projected = jsonTypeRef<InvariantBox<out String>>()
      val projectedValue: InvariantBox<out String> = InvariantBox("projected")
      assertEquals(
        projectedValue,
        json.fromJson(json.toJson(projectedValue, projected), projected),
      )
      assertEquals(
        projectedValue,
        json.fromJson(json.toJsonBytes(projectedValue, projected), projected),
      )
    }
  }

  @Test
  fun invalidProjections() {
    assertFailsWith<ForyJsonException> { jsonTypeRef<InvariantBox<in String>>() }
    assertFailsWith<ForyJsonException> { jsonTypeRef<InvariantBox<*>>() }

    val open = jsonTypeRef<InvariantBox<out CharSequence>>()
    val json = newKotlinJson(KotlinJsonTestMode.INTERPRETED)
    assertFailsWith<ForyJsonException> { json.fromJson("{\"value\":\"text\"}", open) }
  }

  @Test
  fun directProjectedSubtype() {
    val directType = jsonTypeRef<DirectProjectionHolder>()
    val direct = DirectProjectionHolder(InvariantBox(DirectProjectionCircle(3)))
    forEachJsonMode { json ->
      assertEquals(direct, json.fromJson(json.toJson(direct, directType), directType))
      assertEquals(direct, json.fromJson(json.toJsonBytes(direct, directType), directType))
    }
  }

  @Test
  fun removedProjectedSubtype() {
    val directType = jsonTypeRef<DirectProjectionHolder>()
    KotlinJsonTestMode.entries.forEach { mode ->
      val removed = newKotlinJson(mode) { registerMixin(ProjectionRemovalMixin::class.java) }
      val error =
        assertFailsWith<ForyJsonException> {
          removed.fromJson("{\"value\":{\"kind\":\"circle\",\"radius\":3}}", directType)
        }
      assertContains(
        error.message.orEmpty(),
        "Covariant JSON type must be final or declare effective @JsonSubTypes",
      )
    }
  }

  @Test
  fun contributedProjectedSubtype() {
    val contributedType = jsonTypeRef<ContributedProjectionHolder>()
    val contributedValue =
      ContributedProjectionHolder(InvariantBox(ContributedProjectionValue("漢")))
    KotlinJsonTestMode.entries.forEach { mode ->
      val contributed =
        newKotlinJson(mode) { registerMixin(ProjectionContributionMixin::class.java) }
      assertEquals(
        contributedValue,
        contributed.fromJson(
          contributed.toJson(contributedValue, contributedType),
          contributedType
        ),
      )
      assertEquals(
        contributedValue,
        contributed.fromJson(
          contributed.toJsonBytes(contributedValue, contributedType),
          contributedType
        ),
      )
    }
  }

  @Test
  fun replacedProjectedSubtype() {
    val directType = jsonTypeRef<DirectProjectionHolder>()
    val replacementValue = DirectProjectionHolder(InvariantBox(DirectProjectionSquare(4)))
    KotlinJsonTestMode.entries.forEach { mode ->
      val replacement =
        newKotlinJson(mode) { registerMixin(ProjectionReplacementMixin::class.java) }
      assertEquals(
        replacementValue,
        replacement.fromJson(replacement.toJson(replacementValue, directType), directType),
      )
      assertEquals(
        replacementValue,
        replacement.fromJson(replacement.toJsonBytes(replacementValue, directType), directType),
      )
    }
  }

  @Test
  fun rawGenericRootIsRejected() {
    val json = newKotlinJson(KotlinJsonTestMode.INTERPRETED)
    assertFailsWith<ForyJsonException> {
      json.fromJson("{\"value\":\"text\"}", TokenBox::class.java)
    }
    val type = jsonTypeRef<TokenBox<String>>()
    assertEquals(TokenBox("text"), json.fromJson("{\"value\":\"text\"}", type))
  }

  @Test
  fun substitutedNullability() {
    forEachJsonMode { json ->
      assertFailsWith<ForyJsonException> {
        json.fromJson("{\"value\":null}", jsonTypeRef<NonNullBoundBox<String>>())
      }
      assertEquals(
        TokenBox<String?>(null),
        json.fromJson("{\"value\":null}", jsonTypeRef<TokenBox<String?>>()),
      )
    }
  }
}

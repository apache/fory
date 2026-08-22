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
import org.apache.fory.json.ForyJsonException

class KotlinGenericRuntimeTest {
  data class GenericBox<T>(val value: T)

  data class GenericNode<T>(val value: T, val next: GenericNode<T>?)

  data class MutualLeft<T>(val value: T, val right: MutualRight<T>?)

  data class MutualRight<T>(val value: T, val left: MutualLeft<T>?)

  data class ListNode(val value: Int, val children: List<ListNode>)

  data class MapNode(val value: Int, val children: Map<String, MapNode>)

  class Expanding<T>(val next: Expanding<List<T>>?)

  class Swapping<A, B>(val next: Swapping<B, A>?)

  @Test
  fun distinctGenericBindings() {
    forEachJsonMode { json ->
      val stringType = jsonTypeRef<GenericBox<String>>()
      val intType = jsonTypeRef<GenericBox<Int>>()
      val stringValue = GenericBox("漢")
      val intValue = GenericBox(42)

      assertEquals(stringValue, json.fromJson(json.toJson(stringValue, stringType), stringType))
      assertEquals(intValue, json.fromJson(json.toJson(intValue, intType), intType))
      assertEquals(
        stringValue,
        json.fromJson("{\"value\":\"漢\"}", stringType),
      )
      assertEquals(
        intValue,
        json.fromJson(json.toJsonBytes(intValue, intType), intType),
      )
      assertEquals(stringValue, json.fromJson("{\"value\":\"漢\"}", stringType))
    }
  }

  @Test
  fun exactRecursiveBinding() {
    val value = GenericNode("root", GenericNode("leaf", null))
    forEachJsonMode { json ->
      val type = jsonTypeRef<GenericNode<String>>()
      assertEquals(value, json.fromJson(json.toJson(value, type), type))
      assertEquals(value, json.fromJson(json.toJsonBytes(value, type), type))
      assertEquals(
        GenericNode("漢", GenericNode("leaf", null)),
        json.fromJson(
          "{\"value\":\"漢\",\"next\":{\"value\":\"leaf\",\"next\":null}}",
          type,
        ),
      )
    }
  }

  @Test
  fun exactMutualCycle() {
    val value = MutualLeft("left", MutualRight("right", MutualLeft("tail", null)))
    forEachJsonMode { json ->
      val type = jsonTypeRef<MutualLeft<String>>()
      assertEquals(value, json.fromJson(json.toJson(value, type), type))
      assertEquals(value, json.fromJson(json.toJsonBytes(value, type), type))
    }
  }

  @Test
  fun recursiveCoreContainers() {
    val listValue = listOf(ListNode(1, listOf(ListNode(2, emptyList()))))
    val mapValue = MapNode(1, linkedMapOf("child" to MapNode(2, emptyMap())))
    forEachJsonMode { json ->
      val listType = jsonTypeRef<List<ListNode>>()
      assertEquals(listValue, json.fromJson(json.toJson(listValue, listType), listType))
      assertEquals(listValue, json.fromJson(json.toJsonBytes(listValue, listType), listType))

      val mapType = jsonTypeRef<MapNode>()
      assertEquals(mapValue, json.fromJson(json.toJson(mapValue, mapType), mapType))
      assertEquals(mapValue, json.fromJson(json.toJsonBytes(mapValue, mapType), mapType))
    }
  }

  @Test
  fun changingBindingRejectsAndRollsBack() {
    forEachJsonMode { json ->
      assertFailsWith<ForyJsonException> {
        json.fromJson("{\"next\":null}", jsonTypeRef<Expanding<String>>())
      }
      val validType = jsonTypeRef<GenericBox<String>>()
      assertEquals(GenericBox("valid"), json.fromJson("{\"value\":\"valid\"}", validType))

      assertFailsWith<ForyJsonException> {
        json.fromJson("{\"next\":null}", jsonTypeRef<Swapping<String, Int>>())
      }
      assertEquals(
        GenericNode("valid", null),
        json.fromJson(
          "{\"value\":\"valid\",\"next\":null}",
          jsonTypeRef<GenericNode<String>>(),
        ),
      )
    }
  }
}

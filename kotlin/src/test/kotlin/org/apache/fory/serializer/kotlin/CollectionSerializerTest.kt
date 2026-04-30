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

package org.apache.fory.serializer.kotlin

import org.apache.fory.Fory
import org.testng.Assert.assertEquals
import org.testng.annotations.Test

class CollectionSerializerTest {
  @Test
  fun testSerializeArrayDeque() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val arrayDeque = ArrayDeque(listOf(1, 2, 3, 4, 5))
    assertEquals(arrayDeque, fory.deserialize(fory.serialize(arrayDeque)))
  }

  @Test
  fun testSerializeArrayList() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()

    KotlinSerializers.registerSerializers(fory)
    val arrayList = arrayListOf(1, 2, 3, 4, 5)
    assertEquals(arrayList, fory.deserialize(fory.serialize(arrayList)))
  }

  @Test
  fun testSerializeEmptyList() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val emptyList = listOf<Int>()
    assertEquals(emptyList, fory.deserialize(fory.serialize(emptyList)))
  }

  @Test
  fun testSerializeList() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val list = listOf(1, 2, 3, 4, 5)
    assertEquals(list, fory.deserialize(fory.serialize(list)))
  }

  @Test
  fun testSerializeMutableList() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val mutableList = mutableListOf(1, 2, 3, 4, 5)
    assertEquals(mutableList, fory.deserialize(fory.serialize(mutableList)))
  }

  @Test
  fun testSerializeEmptySet() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val emptySet = setOf<Int>()
    assertEquals(emptySet, fory.deserialize(fory.serialize(emptySet)))
  }

  @Test
  fun testSerializeSet() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val set = setOf(1, 2, 3, 4, 5)
    assertEquals(set, fory.deserialize(fory.serialize(set)))
  }

  @Test
  fun testSerializeMutableSet() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val mutableSet = mutableSetOf(1, 2, 3, 4, 5)
    assertEquals(mutableSet, fory.deserialize(fory.serialize(mutableSet)))
  }

  @Test
  fun testSerializeHashSet() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val set = hashSetOf(1, 2, 3, 4, 5)
    assertEquals(set, fory.deserialize(fory.serialize(set)))
  }

  @Test
  fun testSerializeLinkedSet() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val set = linkedSetOf(1, 2, 3, 4, 5)
    assertEquals(set, fory.deserialize(fory.serialize(set)))
  }

  @Test
  fun testSerializeEmptyMap() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val emptyMap: Map<Int, String> = mapOf()
    assertEquals(emptyMap, fory.deserialize(fory.serialize(emptyMap)))
  }

  @Test
  fun testSerializeMap() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val map = mapOf(1 to "one", 2 to "two", 3 to "three")
    assertEquals(map, fory.deserialize(fory.serialize(map)))
  }

  @Test
  fun testSerializeMutableMap() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val mutableMap = mapOf(1 to "one", 2 to "two", 3 to "three")
    assertEquals(mutableMap, fory.deserialize(fory.serialize(mutableMap)))
  }

  @Test
  fun testSerializeHashMap() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val map = hashMapOf(1 to "one", 2 to "two", 3 to "three")
    assertEquals(map, fory.deserialize(fory.serialize(map)))
  }

  @Test
  fun testSerializeLinkedMap() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val map = linkedMapOf(1 to "one", 2 to "two", 3 to "three")
    assertEquals(map, fory.deserialize(fory.serialize(map)))
  }
}

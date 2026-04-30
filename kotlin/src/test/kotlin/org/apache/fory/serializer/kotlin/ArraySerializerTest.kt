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

@OptIn(ExperimentalUnsignedTypes::class)
class ArraySerializerTest {
  class RawCollectionHolder {
    var values: java.util.Collection<Any?>? = null
  }

  @Test
  fun testSimpleArray() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val array = arrayOf("Apple", "Banana", "Orange", "Pineapple")
    assertEquals(array, fory.deserialize(fory.serialize(array)))
  }

  @Test
  fun testMultidimensional() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val array = Array(2) { Array<Int>(2) { 0 } }
    assertEquals(array, fory.deserialize(fory.serialize(array)))
  }

  @Test
  fun testAnyArray() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val array = arrayOf<Any?>("Apple", 1, null, 3.141, 1.2f)
    assertEquals(array, fory.deserialize(fory.serialize(array)))
  }

  @Test
  fun testEmptyArray() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val array = emptyArray<Any?>()
    assertEquals(array, fory.deserialize(fory.serialize(array)))
  }

  @Test
  fun testBooleanArray() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val array = booleanArrayOf(true, false)
    assertEquals(array, fory.deserialize(fory.serialize(array)))
  }

  @Test
  fun testByteArray() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val array = byteArrayOf(0xFF.toByte(), 0xCA.toByte(), 0xFF.toByte())
    assertEquals(array, fory.deserialize(fory.serialize(array)))
  }

  @Test
  fun testCharArray() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val array = charArrayOf('a', 'b', 'c')
    assertEquals(array, fory.deserialize(fory.serialize(array)))
  }

  @Test
  fun testDoubleArray() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val array = doubleArrayOf(1.0, 2.0, 3.0)
    assertEquals(array, fory.deserialize(fory.serialize(array)))
  }

  @Test
  fun testFloatArray() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val array = floatArrayOf(1.0f, 2.0f)
    assertEquals(array, fory.deserialize(fory.serialize(array)))
  }

  @Test
  fun testIntArray() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val array = intArrayOf(1, 2, 3)
    assertEquals(array, fory.deserialize(fory.serialize(array)))
  }

  @Test
  fun testLongArray() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val array = longArrayOf(1L, 2L, 3L)
    assertEquals(array, fory.deserialize(fory.serialize(array)))
  }

  @Test
  fun testShortArray() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val array = shortArrayOf(1, 2, 3)
    assertEquals(array, fory.deserialize(fory.serialize(array)))
  }

  @Test
  fun testUByteArray() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val array = ubyteArrayOf(0xFFu, 0xEFu, 0x00u)
    assert(array.contentEquals(fory.deserialize(fory.serialize(array)) as UByteArray))
  }

  @Test
  fun testUShortArray() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val array = ushortArrayOf(1u, 2u)
    assert(array.contentEquals(fory.deserialize(fory.serialize(array)) as UShortArray))
  }

  @Test
  fun testUIntArray() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val array = uintArrayOf(1u, 2u)
    assert(array.contentEquals(fory.deserialize(fory.serialize(array)) as UIntArray))
  }

  @Test
  fun testULongArray() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)

    val array = ulongArrayOf(1u, 2u, 3u)
    assert(array.contentEquals(fory.deserialize(fory.serialize(array)) as ULongArray))
  }

  @Suppress("UNCHECKED_CAST")
  @Test
  fun testUnsignedArrayAsCollectionField() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()
    KotlinSerializers.registerSerializers(fory)
    fory.register(RawCollectionHolder::class.java)

    val array = ubyteArrayOf(0xFFu, 0xEFu, 0x00u)
    val holder = RawCollectionHolder()
    holder.values = array as java.util.Collection<Any?>

    val roundTripped = fory.deserialize(fory.serialize(holder)) as RawCollectionHolder
    val values = roundTripped.values
    assertEquals(values?.javaClass, UByteArray::class.java)
    assert(array.contentEquals(values as UByteArray))
  }
}

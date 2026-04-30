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
import org.testng.Assert
import org.testng.annotations.Test

/**
 * Nullable primitive serializer tests.
 *
 * Nullable primitives get translated into Boxed types in java. See:
 * https://kotlinlang.org/docs/numbers.html#numbers-representation-on-the-jvm
 */
class NullablePrimitiveSerializerTests {
  @Test
  fun testSerializeBoxedByteValue() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()

    KotlinSerializers.registerSerializers(fory)
    val value: Byte? = 42
    Assert.assertEquals(value, fory.deserialize(fory.serialize(value)))
  }

  @Test
  fun testSerializeBoxedIntValue() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()

    KotlinSerializers.registerSerializers(fory)
    val value: Int? = 42
    Assert.assertEquals(value, fory.deserialize(fory.serialize(value)))
  }

  @Test
  fun testSerializeBoxedShortValue() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()

    KotlinSerializers.registerSerializers(fory)
    val value: Short? = 42
    Assert.assertEquals(value, fory.deserialize(fory.serialize(value)))
  }

  @Test
  fun testSerializeBoxedLongValue() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()

    KotlinSerializers.registerSerializers(fory)
    val value: Long? = 42L
    Assert.assertEquals(value, fory.deserialize(fory.serialize(value)))
  }

  @Test
  fun testSerializeBoxedFloatValue() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()

    KotlinSerializers.registerSerializers(fory)
    val value: Float? = .42f
    Assert.assertEquals(value, fory.deserialize(fory.serialize(value)))
  }

  @Test
  fun testSerializeBoxedDoubleValue() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()

    KotlinSerializers.registerSerializers(fory)
    val value: Double? = .42
    Assert.assertEquals(value, fory.deserialize(fory.serialize(value)))
  }

  @Test
  fun testSerializeBoxedBooleanValue() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()

    KotlinSerializers.registerSerializers(fory)
    val value: Boolean? = true
    Assert.assertEquals(value, fory.deserialize(fory.serialize(value)))
  }

  @Test
  fun testSerializeBoxedCharValue() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()

    KotlinSerializers.registerSerializers(fory)
    val value: Char? = 'a'
    Assert.assertEquals(value, fory.deserialize(fory.serialize(value)))
  }

  @Test
  fun testSerializeBoxedUByteValue() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()

    KotlinSerializers.registerSerializers(fory)
    val value: UByte? = 42u
    Assert.assertEquals(value, fory.deserialize(fory.serialize(value)))
  }

  @Test
  fun testSerializeBoxedUShortValue() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()

    KotlinSerializers.registerSerializers(fory)
    val value: UShort? = 42u
    Assert.assertEquals(value, fory.deserialize(fory.serialize(value)))
  }

  @Test
  fun testSerializeBoxedUIntValue() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()

    KotlinSerializers.registerSerializers(fory)
    val value: UInt? = 42u
    Assert.assertEquals(value, fory.deserialize(fory.serialize(value)))
  }

  @Test
  fun testSerializeBoxedULongValue() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()

    KotlinSerializers.registerSerializers(fory)
    val value: ULong? = 42u
    Assert.assertEquals(value, fory.deserialize(fory.serialize(value)))
  }
}

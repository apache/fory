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

@ExperimentalUnsignedTypes
class UnsignedBoundarySerializerTests {
  @Test
  fun testUByteBoundarySerialization() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()

    KotlinSerializers.registerSerializers(fory)

    val boundaryMin = UByte.MIN_VALUE
    val boundaryMax = UByte.MAX_VALUE

    Assert.assertEquals(boundaryMin, fory.deserialize(fory.serialize(boundaryMin)))
    Assert.assertEquals(boundaryMax, fory.deserialize(fory.serialize(boundaryMax)))
  }

  @Test
  fun testUShortBoundarySerialization() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()

    KotlinSerializers.registerSerializers(fory)

    val boundaryMin = UShort.MIN_VALUE
    val boundaryMax = UShort.MAX_VALUE

    Assert.assertEquals(boundaryMin, fory.deserialize(fory.serialize(boundaryMin)))
    Assert.assertEquals(boundaryMax, fory.deserialize(fory.serialize(boundaryMax)))
  }

  @Test
  fun testUIntBoundarySerialization() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()

    KotlinSerializers.registerSerializers(fory)

    val boundaryMin = UInt.MIN_VALUE
    val boundaryMax = UInt.MAX_VALUE

    Assert.assertEquals(boundaryMin, fory.deserialize(fory.serialize(boundaryMin)))
    Assert.assertEquals(boundaryMax, fory.deserialize(fory.serialize(boundaryMax)))
  }

  @Test
  fun testULongBoundarySerialization() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()

    KotlinSerializers.registerSerializers(fory)

    val boundaryMin = ULong.MIN_VALUE
    val boundaryMax = ULong.MAX_VALUE

    Assert.assertEquals(boundaryMin, fory.deserialize(fory.serialize(boundaryMin)))
    Assert.assertEquals(boundaryMax, fory.deserialize(fory.serialize(boundaryMax)))
  }

  @Test
  fun testUByteArrayBoundarySerialization() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()

    KotlinSerializers.registerSerializers(fory)

    val array = ubyteArrayOf(UByte.MIN_VALUE, UByte.MAX_VALUE)

    assert(array.contentEquals(fory.deserialize(fory.serialize(array)) as UByteArray))
  }

  @Test
  fun testUShortArrayBoundarySerialization() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()

    KotlinSerializers.registerSerializers(fory)

    val array = ushortArrayOf(UShort.MIN_VALUE, UShort.MAX_VALUE)

    assert(array.contentEquals(fory.deserialize(fory.serialize(array)) as UShortArray))
  }

  @Test
  fun testUIntArrayBoundarySerialization() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()

    KotlinSerializers.registerSerializers(fory)

    val array = uintArrayOf(UInt.MIN_VALUE, UInt.MAX_VALUE)

    assert(array.contentEquals(fory.deserialize(fory.serialize(array)) as UIntArray))
  }

  @Test
  fun testULongArrayBoundarySerialization() {
    val fory: Fory =
      Fory.builder().withXlang(false).requireClassRegistration(true).build()

    KotlinSerializers.registerSerializers(fory)

    val array = ulongArrayOf(ULong.MIN_VALUE, ULong.MAX_VALUE)

    assert(array.contentEquals(fory.deserialize(fory.serialize(array)) as ULongArray))
  }
}

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
import org.apache.fory.config.CompatibleMode
import org.apache.fory.config.Language
import org.testng.Assert.assertEquals
import org.testng.annotations.Test

/** Test class for generic Kotlin data classes. See https://github.com/apache/fory/issues/2768 */
data class Change<T>(val old: T? = null, val new: T? = null)

class GenericDataClassTest {

  @Test
  fun testGenericDataClassSerialization() {
    val fory =
      Fory.builder()
        .requireClassRegistration(true)
        .withCodegen(true)
        .withLanguage(Language.JAVA)
        .withRefTracking(false)
        .withCompatibleMode(CompatibleMode.SCHEMA_CONSISTENT)
        .build()

    fory.register(Change::class.java)

    val change = Change("A", "B")

    val serializedChange = fory.serialize(change)

    val deserializedChange = fory.deserialize(serializedChange, Change::class.java)

    assertEquals(change, deserializedChange)
  }

  @Test
  fun testGenericDataClassWithNullValues() {
    val fory =
      Fory.builder()
        .requireClassRegistration(true)
        .withCodegen(true)
        .withLanguage(Language.JAVA)
        .withRefTracking(false)
        .withCompatibleMode(CompatibleMode.SCHEMA_CONSISTENT)
        .build()

    fory.register(Change::class.java)

    val change = Change<String>(old = null, new = "B")

    val serializedChange = fory.serialize(change)

    val deserializedChange = fory.deserialize(serializedChange, Change::class.java)

    assertEquals(change, deserializedChange)
  }

  @Test
  fun testGenericDataClassWithIntValues() {
    val fory =
      Fory.builder()
        .requireClassRegistration(true)
        .withCodegen(true)
        .withLanguage(Language.JAVA)
        .withRefTracking(false)
        .withCompatibleMode(CompatibleMode.SCHEMA_CONSISTENT)
        .build()

    fory.register(Change::class.java)

    val change = Change(1, 2)

    val serializedChange = fory.serialize(change)

    val deserializedChange = fory.deserialize(serializedChange, Change::class.java)

    assertEquals(change, deserializedChange)
  }
}

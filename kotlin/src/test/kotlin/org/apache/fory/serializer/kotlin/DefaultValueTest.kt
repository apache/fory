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
import org.testng.Assert.*
import org.testng.annotations.Test

// Test classes WITHOUT default values (for serialization)
data class ClassNoDefaults(val v: String) // No x field at all

data class ClassMultipleDefaultsNoDefaults(val v: String) // No x and y fields at all

data class ClassComplexDefaultsNoDefaults(val v: String) // No list field at all

// Test classes WITH default values (for deserialization)
data class ClassWithDefaults(val v: String, val x: Int = 1)

data class ClassMultipleDefaultsWithDefaults(val v: String, val x: Int = 1, val y: Double = 2.0)

data class ClassComplexDefaultsWithDefaults(val v: String, val list: List<Int> = listOf(1, 2, 3))

class DefaultValueTest {

  private val support = KotlinDefaultValueSupport()

  @Test
  fun testHasDefaultValues() {
    // Test classes with default values
    assertTrue(support.hasDefaultValues(ClassWithDefaults::class.java))
    assertTrue(support.hasDefaultValues(ClassMultipleDefaultsWithDefaults::class.java))
    assertTrue(support.hasDefaultValues(ClassComplexDefaultsWithDefaults::class.java))

    // Test classes without default values
    assertFalse(support.hasDefaultValues(ClassNoDefaults::class.java))
    assertFalse(support.hasDefaultValues(ClassMultipleDefaultsNoDefaults::class.java))
    assertFalse(support.hasDefaultValues(ClassComplexDefaultsNoDefaults::class.java))

    // Test non-data classes
    assertFalse(support.hasDefaultValues(String::class.java))
    assertFalse(support.hasDefaultValues(Int::class.java))
    assertFalse(support.hasDefaultValues(List::class.java))
  }

  @Test
  fun testGetAllDefaultValues() {
    // Test single default value
    val singleDefaults = support.getAllDefaultValues(ClassWithDefaults::class.java)
    assertEquals(1, singleDefaults.size)
    assertEquals(1, singleDefaults["x"])

    // Test multiple default values
    val multipleDefaults =
      support.getAllDefaultValues(ClassMultipleDefaultsWithDefaults::class.java)
    assertEquals(2, multipleDefaults.size)
    assertEquals(1, multipleDefaults["x"])
    assertEquals(2.0, multipleDefaults["y"])

    // Test complex default values
    val complexDefaults = support.getAllDefaultValues(ClassComplexDefaultsWithDefaults::class.java)
    assertEquals(1, complexDefaults.size)
    assertEquals(listOf(1, 2, 3), complexDefaults["list"])

    // Test classes without default values
    val noDefaults = support.getAllDefaultValues(ClassNoDefaults::class.java)
    assertTrue(noDefaults.isEmpty())
  }

  @Test
  fun testGetDefaultValue() {
    // Test existing default values
    assertEquals(1, support.getDefaultValue(ClassWithDefaults::class.java, "x"))
    assertEquals(1, support.getDefaultValue(ClassMultipleDefaultsWithDefaults::class.java, "x"))
    assertEquals(2.0, support.getDefaultValue(ClassMultipleDefaultsWithDefaults::class.java, "y"))
    assertEquals(
      listOf(1, 2, 3),
      support.getDefaultValue(ClassComplexDefaultsWithDefaults::class.java, "list")
    )

    // Test non-existent fields
    assertNull(support.getDefaultValue(ClassWithDefaults::class.java, "nonExistent"))
    assertNull(support.getDefaultValue(ClassNoDefaults::class.java, "x"))

    // Test fields without default values
    assertNull(support.getDefaultValue(ClassWithDefaults::class.java, "v"))
  }

  @Test
  fun testNonDataClasses() {
    // Test regular classes
    assertFalse(support.hasDefaultValues(RegularClass::class.java))
    assertTrue(support.getAllDefaultValues(RegularClass::class.java).isEmpty())
    assertNull(support.getDefaultValue(RegularClass::class.java, "field"))

    // Test interfaces
    assertFalse(support.hasDefaultValues(TestInterface::class.java))
    assertTrue(support.getAllDefaultValues(TestInterface::class.java).isEmpty())
    assertNull(support.getDefaultValue(TestInterface::class.java, "field"))

    // Test enums
    assertFalse(support.hasDefaultValues(TestEnum::class.java))
    assertTrue(support.getAllDefaultValues(TestEnum::class.java).isEmpty())
    assertNull(support.getDefaultValue(TestEnum::class.java, "field"))
  }

  @Test
  fun testDefaultValueDeserialization() {
    val fory =
      Fory.builder()
        .requireClassRegistration(false)
        .withCompatible(true)
        .build()
    KotlinSerializers.registerSerializers(fory)
    val obj = ClassNoDefaults("test")
    val serialized = fory.serialize(obj)
    val deserialized = fory.deserialize(serialized, ClassWithDefaults::class.java)
    assertEquals(deserialized.v, obj.v)
    assertEquals(deserialized.x, 1)

    val obj2 = ClassMultipleDefaultsNoDefaults("test")
    val serialized2 = fory.serialize(obj2)
    val deserialized2 = fory.deserialize(serialized2, ClassMultipleDefaultsWithDefaults::class.java)
    assertEquals(deserialized2.v, obj2.v)
    assertEquals(deserialized2.x, 1)
    assertEquals(deserialized2.y, 2.0)

    val obj3 = ClassComplexDefaultsNoDefaults("test")
    val serialized3 = fory.serialize(obj3)
    val deserialized3 = fory.deserialize(serialized3, ClassComplexDefaultsWithDefaults::class.java)
    assertEquals(deserialized3.v, obj3.v)
    assertEquals(deserialized3.list, listOf(1, 2, 3))
  }
}

// Additional test classes
class RegularClass(val field: String)

interface TestInterface {
  val field: String
}

enum class TestEnum {
  VALUE1,
  VALUE2
}

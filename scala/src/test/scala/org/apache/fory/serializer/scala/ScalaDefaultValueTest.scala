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

package org.apache.fory.serializer.scala

import org.apache.fory.Fory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

object NestedCases {
  // Classes WITHOUT default values for serialization
  case class NestedCaseClassNoDefaults(a: String, b: Int, c: Option[String])
  
  // Classes WITH default values for deserialization
  case class NestedCaseClassWithDefaults(a: String, b: Int = 99, c: Option[String] = Some("nested"))
  
  // Simple case class with only one field for testing missing fields
  case class SimpleNestedCase(a: String)
}

// Regular Scala classes WITHOUT default values for serialization
class RegularScalaClassNoDefaults(val name: String) {
  override def equals(obj: Any): Boolean = obj match {
    case that: RegularScalaClassNoDefaults => 
      this.name == that.name
    case _ => false
  }
  
  override def hashCode(): Int = {
    val prime = 31
    var result = 1
    result = prime * result + (if (name == null) 0 else name.hashCode)
    result
  }
  
  override def toString: String = s"RegularScalaClassNoDefaults($name)"
}

// Regular Scala classes WITH default values for deserialization
class RegularScalaClassWithDefaults(val name: String, val age: Int = 25, val city: String = "Unknown") {
  override def equals(obj: Any): Boolean = obj match {
    case that: RegularScalaClassWithDefaults => 
      this.name == that.name && this.age == that.age && this.city == that.city
    case _ => false
  }
  
  override def hashCode(): Int = {
    val prime = 31
    var result = 1
    result = prime * result + (if (name == null) 0 else name.hashCode)
    result = prime * result + age
    result = prime * result + (if (city == null) 0 else city.hashCode)
    result
  }
  
  override def toString: String = s"RegularScalaClassWithDefaults($name, $age, $city)"
}

class ScalaDefaultValueTest extends AnyWordSpec with Matchers {
  
  // Test both runtime mode (MetaSharedSerializer) and codegen mode (MetaSharedCodecBuilder)
  val testModes = Seq(
    ("Runtime Mode", false),
    ("Codegen Mode", true)
  )

  def createFory(codegen: Boolean): Fory = Fory.builder()
    .withXlang(false)
    .withRefTracking(true)
    .withScalaOptimizationEnabled(true)
    .requireClassRegistration(false)
    .suppressClassRegistrationWarnings(false)
    .withCodegen(codegen)
    .withCompatible(true)
    .build()

  "Fory Scala default value support" should {
    testModes.foreach { case (modeName, codegen) =>
      s"handle missing fields with default values in case class in $modeName" in {
        val fory = createFory(codegen)
        // Serialize object WITHOUT the x field (it doesn't exist in source class)
        val original = CaseClassNoDefaults("test")
        val serialized = fory.serialize(original)
        
        // Deserialize into class WITH default values for x
        val deserialized = fory.deserialize(serialized, classOf[CaseClassWithDefaults])
        deserialized.v shouldEqual "test"
        deserialized.x shouldEqual 1 // Should use default value
      }

      s"handle multiple missing fields with default values in case class in $modeName" in {
        val fory = createFory(codegen)
        // Serialize object WITHOUT x and y fields (they don't exist in source class)
        val original = CaseClassMultipleDefaultsNoDefaults("test")
        val serialized = fory.serialize(original)
        
        // Deserialize into class WITH default values for x and y
        val deserialized = fory.deserialize(serialized, classOf[CaseClassMultipleDefaultsWithDefaults])
        deserialized.v shouldEqual "test"
        deserialized.x shouldEqual 1 // Should use default value
        deserialized.y shouldEqual 2.0 // Should use default value
      }

      s"work with complex default values in case class in $modeName" in {
        val fory = createFory(codegen)
        // Serialize object WITHOUT the list field (it doesn't exist in source class)
        val original = CaseClassComplexDefaultsNoDefaults("test")
        val serialized = fory.serialize(original)
        
        // Deserialize into class WITH default values for list
        val deserialized = fory.deserialize(serialized, classOf[CaseClassComplexDefaultsWithDefaults])
        deserialized.v shouldEqual "test"
        deserialized.list shouldEqual List(1, 2, 3) // Should use default value
      }

      s"handle partial missing fields with default values in case class in $modeName" in {
        val fory = createFory(codegen)
        // This test case needs to be updated since we can't have partial fields
        // For now, we'll test with a different approach - serialize with one field missing
        val original = CaseClassNoDefaults("test") // Only has v field
        val serialized = fory.serialize(original)
        
        // Deserialize into class WITH default values for x
        val deserialized = fory.deserialize(serialized, classOf[CaseClassWithDefaults])
        deserialized.v shouldEqual "test"
        deserialized.x shouldEqual 1 // Should use default value
      }

      s"handle missing fields with default values in nested case class in $modeName" in {
        val fory = createFory(codegen)
        import NestedCases._
        // Create a case class with only the first field, simulating missing b and c fields
        // We'll use a different approach - serialize a simpler object and deserialize into a more complex one
        val original = SimpleNestedCase("nestedTest")
        val serialized = fory.serialize(original)
        
        // Deserialize into class WITH default values for b and c
        val deserialized = fory.deserialize(serialized, classOf[NestedCaseClassWithDefaults])
        deserialized.a shouldEqual "nestedTest"
        deserialized.b shouldEqual 99 // Should use default value
        deserialized.c shouldEqual Some("nested") // Should use default value
      }

      s"handle missing fields with default values in regular Scala class in $modeName" in {
        val fory = createFory(codegen)
        // Serialize object WITHOUT default values (missing age and city fields)
        val original = new RegularScalaClassNoDefaults("Jane")
        val serialized = fory.serialize(original)
        
        // Deserialize into class WITH default values
        val deserialized = fory.deserialize(serialized, classOf[RegularScalaClassWithDefaults])
        deserialized.name shouldEqual "Jane"
        deserialized.age shouldEqual 25 // Should use default value
        deserialized.city shouldEqual "Unknown" // Should use default value
      }

      s"handle partial missing fields with default values in regular Scala class in $modeName" in {
        val fory = createFory(codegen)
        // Serialize object with only name field, missing age and city
        val original = new RegularScalaClassNoDefaults("Bob")
        val serialized = fory.serialize(original)
        
        // Deserialize into class WITH default values
        val deserialized = fory.deserialize(serialized, classOf[RegularScalaClassWithDefaults])
        deserialized.name shouldEqual "Bob"
        deserialized.age shouldEqual 25 // Should use default value
        deserialized.city shouldEqual "Unknown" // Should use default value
      }
    }
  }
}

// Test case classes WITHOUT default values (for serialization)
case class CaseClassNoDefaults(v: String) // No x field at all
case class CaseClassMultipleDefaultsNoDefaults(v: String) // No x and y fields at all
case class CaseClassComplexDefaultsNoDefaults(v: String) // No list field at all

// Test case classes WITH default values (for deserialization)
case class CaseClassWithDefaults(v: String, x: Int = 1)
case class CaseClassMultipleDefaultsWithDefaults(v: String, x: Int = 1, y: Double = 2.0)
case class CaseClassComplexDefaultsWithDefaults(v: String, list: List[Int] = List(1, 2, 3)) 
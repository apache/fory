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

package org.apache.fory.util

import org.apache.fory.Fory
import org.apache.fory.`type`.Descriptor
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.Succeeded
import org.scalatest.matchers.should.Matchers._
import java.util.{List => JavaList, ArrayList}
import scala.jdk.CollectionConverters._

// Test case classes WITH default values for testing DefaultValueUtils
case class TestCaseClassWithDefaults(
  name: String,
  age: Int = 25,
  city: String = "Unknown",
  active: Boolean = true,
  score: Double = 0.0,
  tags: List[String] = List("default")
)

case class TestCaseClassMultipleDefaults(
  id: Int,
  name: String = "default",
  description: String = "no description",
  count: Int = 0,
  enabled: Boolean = false
)

case class TestCaseClassComplexDefaults(
  title: String,
  metadata: Map[String, String] = Map("type" -> "default"),
  numbers: List[Int] = List(1, 2, 3),
  optional: Option[String] = Some("default")
)

// Test case classes WITHOUT default values (for comparison)
case class TestCaseClassNoDefaults(name: String, age: Int, city: String)

// Regular Scala classes WITH default values
class TestRegularScalaClassWithDefaults(
  val name: String,
  val age: Int = 30,
  val city: String = "DefaultCity",
  val active: Boolean = false
) {
  override def equals(obj: Any): Boolean = obj match {
    case that: TestRegularScalaClassWithDefaults =>
      this.name == that.name && this.age == that.age && 
      this.city == that.city && this.active == that.active
    case _ => false
  }
  
  override def hashCode(): Int = {
    val prime = 31
    var result = 1
    result = prime * result + (if (name == null) 0 else name.hashCode)
    result = prime * result + age
    result = prime * result + (if (city == null) 0 else city.hashCode)
    result = prime * result + (if (active) 1 else 0)
    result
  }
  
  override def toString: String = s"TestRegularScalaClassWithDefaults($name, $age, $city, $active)"
}

// Regular Scala classes WITHOUT default values
class TestRegularScalaClassNoDefaults(val name: String, val age: Int) {
  override def equals(obj: Any): Boolean = obj match {
    case that: TestRegularScalaClassNoDefaults =>
      this.name == that.name && this.age == that.age
    case _ => false
  }
  
  override def hashCode(): Int = {
    val prime = 31
    var result = 1
    result = prime * result + (if (name == null) 0 else name.hashCode)
    result = prime * result + age
    result
  }
  
  override def toString: String = s"TestRegularScalaClassNoDefaults($name, $age)"
}

// Java-like class for testing non-Scala classes
class TestJavaClass(val name: String, val age: Int) {
  override def equals(obj: Any): Boolean = obj match {
    case that: TestJavaClass =>
      this.name == that.name && this.age == that.age
    case _ => false
  }
  
  override def hashCode(): Int = {
    val prime = 31
    var result = 1
    result = prime * result + (if (name == null) 0 else name.hashCode)
    result = prime * result + age
    result
  }
  
  override def toString: String = s"TestJavaClass($name, $age)"
}

// Object to contain truly nested case classes
object NestedClasses {
  case class NestedCaseClass(
    outer: String,
    inner: TestCaseClassWithDefaults = TestCaseClassWithDefaults("nested")
  )

  case class DeeplyNestedCaseClass(
    level1: String,
    level2: NestedCaseClass = NestedCaseClass("level2", TestCaseClassWithDefaults("deep")),
    level3: TestCaseClassMultipleDefaults = TestCaseClassMultipleDefaults(999, "deep3")
  )

  case class NestedCaseClassNoDefaults(
    outer: String,
    inner: TestCaseClassNoDefaults
  )
}

class ScalaDefaultValueUtilsTest extends AnyWordSpec with Matchers {

  def createFory(): Fory = Fory.builder()
    .withXlang(false)
    .withRefTracking(true)
    .withScalaOptimizationEnabled(true)
    .requireClassRegistration(false)
    .suppressClassRegistrationWarnings(false)
    .build()

  /**
   * Helper function to get default values as a Scala Map for easier testing
   */
  def getDefaultValuesAsScalaMap[T](cls: Class[T]): Map[String, Object] = {
    scalaDefaultValueSupport().getAllDefaultValues(cls).asScala.toMap
  }

  def scalaDefaultValueSupport(): DefaultValueUtils.DefaultValueSupport = {
    DefaultValueUtils.getScalaDefaultValueSupport()
  }

  /**
   * Helper function to build Scala default value fields for easier testing
   */
  def buildDefaultValueFields[T](cls: Class[T]): Array[DefaultValueUtils.DefaultValueField] = {
    val fory = createFory()
    val descriptors = new ArrayList[Descriptor]()
    scalaDefaultValueSupport().buildDefaultValueFields(fory, cls, descriptors)
  }

  "DefaultValueUtils" should {

    "detect Scala classes with default values correctly" in {
      // Test case classes with default values
      scalaDefaultValueSupport().hasDefaultValues(classOf[TestCaseClassWithDefaults]) shouldEqual true
      scalaDefaultValueSupport().hasDefaultValues(classOf[TestCaseClassMultipleDefaults]) shouldEqual true
      scalaDefaultValueSupport().hasDefaultValues(classOf[TestCaseClassComplexDefaults]) shouldEqual true
      
      // Test regular Scala classes with default values
      scalaDefaultValueSupport().hasDefaultValues(classOf[TestRegularScalaClassWithDefaults]) shouldEqual true
      
      // Test classes without default values
      scalaDefaultValueSupport().hasDefaultValues(classOf[TestCaseClassNoDefaults]) shouldEqual false
      scalaDefaultValueSupport().hasDefaultValues(classOf[TestRegularScalaClassNoDefaults]) shouldEqual false
      scalaDefaultValueSupport().hasDefaultValues(classOf[TestJavaClass]) shouldEqual false
      
             // Test built-in types
       scalaDefaultValueSupport().hasDefaultValues(classOf[String]) shouldEqual false
       // Skip primitive types as they don't have constructors
       scalaDefaultValueSupport().hasDefaultValues(classOf[List[_]]) shouldEqual false
    }

    "get all default values for Scala classes" in {
       // Test case class with defaults
       val caseClassDefaults = getDefaultValuesAsScalaMap(classOf[TestCaseClassWithDefaults])
       caseClassDefaults should not be empty
       caseClassDefaults("age") shouldEqual 25
       caseClassDefaults("city") shouldEqual "Unknown"
       caseClassDefaults("active") shouldEqual true
       caseClassDefaults("score") shouldEqual 0.0
       caseClassDefaults("tags") shouldEqual List("default")
       
       // Test case class with multiple defaults
       val multipleDefaults = getDefaultValuesAsScalaMap(classOf[TestCaseClassMultipleDefaults])
       multipleDefaults should not be empty
       multipleDefaults("name") shouldEqual "default"
       multipleDefaults("description") shouldEqual "no description"
       multipleDefaults("count") shouldEqual 0
       multipleDefaults("enabled") shouldEqual false
       
       // Test case class with complex defaults
       val complexDefaults = getDefaultValuesAsScalaMap(classOf[TestCaseClassComplexDefaults])
       complexDefaults should not be empty
       // Handle the metadata map - it might be a Scala Map or Java Map
       val metadataValue = complexDefaults("metadata")
       metadataValue should not be null
       // Check if it's a Scala Map or Java Map and handle accordingly
       metadataValue match {
         case javaMap: java.util.Map[String, String] =>
           javaMap.asScala.toMap shouldEqual Map("type" -> "default")
         case scalaMap: Map[String, String] =>
           scalaMap shouldEqual Map("type" -> "default")
         case _ =>
           fail(s"Unexpected metadata type: ${metadataValue.getClass}")
       }
       complexDefaults("numbers") shouldEqual List(1, 2, 3)
       complexDefaults("optional") shouldEqual Some("default")
       
       // Test regular Scala class with defaults
       val regularDefaults = getDefaultValuesAsScalaMap(classOf[TestRegularScalaClassWithDefaults])
       regularDefaults should not be empty
       regularDefaults("age") shouldEqual 30
       regularDefaults("city") shouldEqual "DefaultCity"
       regularDefaults("active") shouldEqual false
       
       // Test classes without defaults
       getDefaultValuesAsScalaMap(classOf[TestCaseClassNoDefaults]) shouldBe empty
       getDefaultValuesAsScalaMap(classOf[TestRegularScalaClassNoDefaults]) shouldBe empty
       getDefaultValuesAsScalaMap(classOf[TestJavaClass]) shouldBe empty
     }

    "build Scala default value fields correctly" in {
       // Test with case class that has defaults
       val fields = buildDefaultValueFields(classOf[TestCaseClassWithDefaults])
       
       fields should not be empty
       fields.foreach { field =>
         field.getFieldName should not be null
         field.getDefaultValue should not be null
         field.getFieldAccessor should not be null
       }
       
       // Test with case class without defaults
       val noDefaultFields = buildDefaultValueFields(classOf[TestCaseClassNoDefaults])
       noDefaultFields shouldBe empty
       
       // Test with Java class
       val javaFields = buildDefaultValueFields(classOf[TestJavaClass])
       javaFields shouldBe empty
     }

    "set Scala default values on objects correctly" in {
       val fory = createFory()
       
       // Create an object with missing fields
       val obj = new TestCaseClassWithDefaults("test", 0, null, false, 0.0, null)
       
       // Build default value fields
       val descriptors = new ArrayList[Descriptor]()
       val fields = scalaDefaultValueSupport().buildDefaultValueFields(
         fory, classOf[TestCaseClassWithDefaults], descriptors)
       
       // Set default values
       DefaultValueUtils.setDefaultValues(obj, fields)
       
       // Verify default values were set
       obj.age shouldEqual 25
       obj.city shouldEqual "Unknown"
       obj.active shouldEqual true
       obj.score shouldEqual 0.0
       obj.tags shouldEqual List("default")
     }

    "cache results for better performance" in {
      // Test that getAllDefaultValues caches results
      val firstCall = scalaDefaultValueSupport().getAllDefaultValues(classOf[TestCaseClassWithDefaults])
      val secondCall = scalaDefaultValueSupport().getAllDefaultValues(classOf[TestCaseClassWithDefaults])
      
      // Should return the same cached result
      firstCall should be theSameInstanceAs secondCall
      
             // Test that buildScalaDefaultValueFields caches results
       val fory = createFory()
       val descriptors = new ArrayList[Descriptor]()
       
       val firstFields = scalaDefaultValueSupport().buildDefaultValueFields(
         fory, classOf[TestCaseClassWithDefaults], descriptors)
       val secondFields = scalaDefaultValueSupport().buildDefaultValueFields(
         fory, classOf[TestCaseClassWithDefaults], descriptors)
      
      // Should return the same cached result
      firstFields should be theSameInstanceAs secondFields
    }

    "handle different field types correctly" in {
       // Test with different field types
       val fields = buildDefaultValueFields(classOf[TestCaseClassWithDefaults])
       
       fields.foreach { field =>
         field.getDefaultValue should not be null
         field.getFieldName should not be null
         field.getFieldAccessor should not be null
         field.getDispatchId.toInt should be >= 0
       }
     }

    "work with nested case classes" in {
       import NestedClasses._
       // Nested case classes with default values should be detected
       scalaDefaultValueSupport().hasDefaultValues(classOf[NestedCaseClass]) shouldEqual true
       
       val nestedDefaults = getDefaultValuesAsScalaMap(classOf[NestedCaseClass])
       nestedDefaults should not be empty
       nestedDefaults("inner") shouldEqual TestCaseClassWithDefaults("nested")
     }

     "work with deeply nested case classes" in {
       import NestedClasses._
       // Deeply nested case classes with default values should be detected
       scalaDefaultValueSupport().hasDefaultValues(classOf[DeeplyNestedCaseClass]) shouldEqual true
       
       val deepDefaults = getDefaultValuesAsScalaMap(classOf[DeeplyNestedCaseClass])
       deepDefaults should not be empty
       deepDefaults("level2") shouldEqual NestedCaseClass("level2", TestCaseClassWithDefaults("deep"))
       deepDefaults("level3") shouldEqual TestCaseClassMultipleDefaults(999, "deep3")
     }

     "work with nested case classes without defaults" in {
       import NestedClasses._
       // Nested case classes without default values should not be detected
       scalaDefaultValueSupport().hasDefaultValues(classOf[NestedCaseClassNoDefaults]) shouldEqual false
       
       val nestedDefaults = getDefaultValuesAsScalaMap(classOf[NestedCaseClassNoDefaults])
       nestedDefaults shouldBe empty
     }

    "handle error cases gracefully" in {
       val fory = createFory()
       
       // Test with classes that might cause reflection issues
       val descriptors = new ArrayList[Descriptor]()
       
       // These should not throw exceptions
       noException should be thrownBy {
         scalaDefaultValueSupport().buildDefaultValueFields(fory, classOf[Object], descriptors)
       }
       
       noException should be thrownBy {
         scalaDefaultValueSupport().buildDefaultValueFields(fory, classOf[String], descriptors)
       }
       
       noException should be thrownBy {
         scalaDefaultValueSupport().buildDefaultValueFields(fory, classOf[List[_]], descriptors)
       }
     }

    "test DefaultValueField inner class" in {
       // Test the getter methods of DefaultValueField
       // We can't create instances directly due to private constructor,
       // but we can test the getters if we get an instance from the utility methods
       
       // Try to get fields from a class with defaults
       val fields = buildDefaultValueFields(classOf[TestCaseClassWithDefaults])
       
       if (fields.nonEmpty) {
         val field = fields(0)
         field.getFieldName should not be null
         field.getDefaultValue should not be null
         field.getFieldAccessor should not be null
         field.getDispatchId.toInt should be >= 0
       }
     }
  }
}

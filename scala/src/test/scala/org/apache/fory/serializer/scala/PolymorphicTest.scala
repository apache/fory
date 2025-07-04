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
import org.apache.fory.config.Language
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

object PolymorphicTest {
  sealed trait Shape
  case class Circle(radius: Double) extends Shape
}

class PolymorphicTest extends AnyWordSpec with Matchers {
  import PolymorphicTest._

  def fory: Fory = {
    val fory = Fory.builder()
      .withLanguage(Language.JAVA)
      .withRefTracking(true)
      .withScalaOptimizationEnabled(true)
      .requireClassRegistration(false)
      .suppressClassRegistrationWarnings(false).build()
    ScalaSerializers.registerSerializers(fory)
    fory
  }

  "fory scala polymorphic support" should {
    "serialize/deserialize circle class" in {
      val c = Circle(5.0)
      fory.deserialize(fory.serialize(c)) shouldEqual c
    }
    "serialize/deserialize circle class (class hint)" in {
      val c = Circle(5.0)
      fory.deserialize(fory.serialize(c), classOf[Circle]) shouldEqual c
    }
    "serialize/deserialize circle class (shape hint)" in {
      val c = Circle(5.0)
      fory.deserialize(fory.serialize(c), classOf[Shape]) shouldEqual c
    }
    "serialize/deserialize circle class (Java object format)" in {
      val c = Circle(5.0)
      fory.deserializeJavaObject(fory.serializeJavaObject(c), classOf[Circle]) shouldEqual c
    }
    "serialize/deserialize circle class (Java object format - shape hint)" ignore {
      val c = Circle(5.0)
      fory.deserializeJavaObject(fory.serializeJavaObject(c), classOf[Shape]) shouldEqual c
    }
  }
}

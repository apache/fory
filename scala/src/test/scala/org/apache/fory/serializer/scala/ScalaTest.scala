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

import java.math.{BigDecimal => JBigDecimal, BigInteger}
import org.apache.fory.Fory
import org.apache.fory.config.Language
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

package object SomePackageObject {
  case class SomeClass(value: Int)
}

class ScalaTest extends AnyWordSpec with Matchers {
  def fory: Fory = Fory.builder()
    .withLanguage(Language.JAVA)
    .withRefTracking(true)
    .withScalaOptimizationEnabled(true)
    .requireClassRegistration(false)
    .suppressClassRegistrationWarnings(false).build()

  "fory scala support" should {
    "serialize/deserialize package object" in {
      val p = SomePackageObject.SomeClass(1)
      fory.deserialize(fory.serialize(p)) shouldEqual p
    }
    "serialize/deserialize java.math.BigDecimal" in {
      val values = Seq(
        JBigDecimal.ZERO,
        new JBigDecimal(BigInteger.ZERO, 3),
        JBigDecimal.ONE,
        JBigDecimal.ONE.negate(),
        JBigDecimal.valueOf(12345, 2),
        new JBigDecimal(BigInteger.valueOf(Long.MaxValue), 0),
        new JBigDecimal(BigInteger.valueOf(Long.MinValue), 0),
        new JBigDecimal(BigInteger.valueOf(Long.MaxValue).add(BigInteger.ONE), 0),
        new JBigDecimal(BigInteger.valueOf(Long.MinValue).subtract(BigInteger.ONE), 0),
        new JBigDecimal(new BigInteger("123456789012345678901234567890123456789"), 37)
      )
      Seq(Language.JAVA, Language.XLANG).foreach { language =>
        val decimalFory = Fory.builder()
          .withLanguage(language)
          .withRefTracking(true)
          .withScalaOptimizationEnabled(true)
          .requireClassRegistration(false)
          .suppressClassRegistrationWarnings(false)
          .build()
        values.foreach { value =>
          decimalFory.deserialize(decimalFory.serialize(value)) shouldEqual value
        }
      }
    }
  }
  "serialize/deserialize package object in app" in {
    // If we move code in main here, we can't reproduce https://github.com/apache/fory/issues/1165.
    PkgObjectMain.main(Array())
    PkgObjectMain2.main(Array())
  }
}


package object PkgObject {
  case class Id(value: Int)
  case class IdAnyVal(value: Int) extends AnyVal
}

// Test for https://github.com/apache/fory/issues/1165
object PkgObjectMain extends App {

  val fory = Fory
    .builder()
    .withScalaOptimizationEnabled(true)
    .requireClassRegistration(false)
    .withRefTracking(true).suppressClassRegistrationWarnings(false)
    .build()

  import PkgObject._

  case class SomeClass(v: Id)
  val o1 = SomeClass(Id(1))
  val o2 = fory.deserialize(fory.serialize(o1))
  if (o1 != o2) {
    throw new RuntimeException(s"$o1 is not equal to $o2")
  }
}

// Test for https://github.com/apache/fory/issues/1175
object PkgObjectMain2 extends App {
  val fory = Fory
    .builder()
    .withScalaOptimizationEnabled(true)
    .requireClassRegistration(false)
    .withRefTracking(true)
    .suppressClassRegistrationWarnings(false)
    .build()

  import PkgObject._

  case class SomeClass(v: List[IdAnyVal])
  val p = SomeClass(List.empty)
  val result = fory.deserialize(fory.serialize(p))
  if (result != p) {
    throw new RuntimeException(s"$result is not equal to $p")
  }
}

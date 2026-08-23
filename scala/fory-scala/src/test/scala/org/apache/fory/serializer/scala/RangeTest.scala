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
import org.apache.fory.exception.InsecureException
import org.apache.fory.scala.ForyScala
import org.apache.fory.serializer.GraphMemoryEstimates
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.immutable.NumericRange

final class RefInt(var value: Int) {
  def this() = this(0)

  override def equals(other: Any): Boolean = other match {
    case that: RefInt => value == that.value
    case _ => false
  }

  override def hashCode(): Int = value
}

final class RefIntIntegral extends Integral[RefInt] {
  override def plus(x: RefInt, y: RefInt): RefInt = new RefInt(x.value + y.value)

  override def minus(x: RefInt, y: RefInt): RefInt = new RefInt(x.value - y.value)

  override def times(x: RefInt, y: RefInt): RefInt = new RefInt(x.value * y.value)

  override def quot(x: RefInt, y: RefInt): RefInt = new RefInt(x.value / y.value)

  override def rem(x: RefInt, y: RefInt): RefInt = new RefInt(x.value % y.value)

  override def negate(x: RefInt): RefInt = new RefInt(-x.value)

  override def fromInt(x: Int): RefInt = new RefInt(x)

  override def parseString(str: String): Option[RefInt] =
    scala.util.Try(new RefInt(str.toInt)).toOption

  override def toInt(x: RefInt): Int = x.value

  override def toLong(x: RefInt): Long = x.value.toLong

  override def toFloat(x: RefInt): Float = x.value.toFloat

  override def toDouble(x: RefInt): Double = x.value.toDouble

  override def compare(x: RefInt, y: RefInt): Int = Integer.compare(x.value, y.value)

  override def min[T <: RefInt](x: T, y: T): T = if (compare(x, y) <= 0) x else y

  override def max[T <: RefInt](x: T, y: T): T = if (compare(x, y) >= 0) x else y
}

class RangeTest extends AnyWordSpec with Matchers {
  def fory: Fory = {
    newFory()
  }

  private def newFory(
      maxGraphMemoryBytes: Option[Long] = None,
      maxDepth: Option[Int] = None): Fory = {
    val builder = ForyScala.builder()
      .withXlang(false)
      .withRefTracking(true)
      .requireClassRegistration(true)
      .suppressClassRegistrationWarnings(false)
    maxGraphMemoryBytes.foreach(builder.withMaxGraphMemoryBytes)
    maxDepth.foreach(builder.withMaxDepth)
    builder.build()
  }

  private def nestedRangeFory(maxDepth: Int): Fory = {
    newFory(maxDepth = Some(maxDepth))
  }

  private def refRangeFory(): Fory = {
    val runtime = newFory()
    runtime.register(classOf[RefInt])
    runtime.register(classOf[RefIntIntegral])
    runtime
  }

  private def refRange(
      start: Int,
      end: Int,
      step: Int): NumericRange.Inclusive[RefInt] = {
    new NumericRange.Inclusive[RefInt](
      new RefInt(start),
      new RefInt(end),
      new RefInt(step))(new RefIntIntegral)
  }

  private def nestedRange(levels: Int): NumericRange.Inclusive[AnyRef] = {
    val leaf = NumericRange.inclusive(1, 2, 1)
    val integral = implicitly[Integral[Int]].asInstanceOf[Integral[AnyRef]]
    var nested: AnyRef = leaf
    var level = 1
    while (level < levels) {
      nested = new NumericRange.Inclusive[AnyRef](nested, leaf, leaf)(integral)
      level += 1
    }
    nested.asInstanceOf[NumericRange.Inclusive[AnyRef]]
  }

  private def assertCarrierBudget(value: AnyRef): Unit = {
    val bytes = fory.serialize(value)
    val required = GraphMemoryEstimates.shallowObjectBytes(value.getClass).toLong
    intercept[InsecureException] {
      newFory(maxGraphMemoryBytes = Some(required - 1)).deserialize(bytes)
    }
    newFory(maxGraphMemoryBytes = Some(required)).deserialize(bytes) shouldEqual value
  }

  "fory scala range support" should {
    "serialize/deserialize range object" in {
      val v = Range.inclusive(1, 10)
      fory.deserialize(fory.serialize(v)) shouldEqual v
      (fory.serialize(v).length < 8) shouldBe true
      val v1 = Range.apply(1, 10)
      fory.deserialize(fory.serialize(v1)) shouldEqual v1
      (fory.serialize(v1).length < 8) shouldBe true
    }
    "serialize/deserialize numeric range object" in {
      val v = NumericRange.inclusive(1, 10, 1)
      fory.deserialize(fory.serialize(v)) shouldEqual v
      (fory.serialize(v).length < 12) shouldBe true
      val v1 = NumericRange.apply(1, 10, 1)
      fory.deserialize(fory.serialize(v1)) shouldEqual v1
      (fory.serialize(v1).length < 12) shouldBe true
    }
    "preserve numeric range component ref state" in {
      val runtime = refRangeFory()
      val value = refRange(1, 4, 1)
      val values = Array[AnyRef](value, value)

      val decoded = runtime.deserialize(runtime.serialize(values)).asInstanceOf[Array[AnyRef]]
      val decodedRange = decoded(0).asInstanceOf[NumericRange.Inclusive[RefInt]]

      decodedRange.start.value shouldEqual 1
      decodedRange.end.value shouldEqual 4
      decodedRange.step.value shouldEqual 1
      decoded(1) shouldBe theSameInstanceAs(decodedRange)

      val next = refRange(2, 6, 2)
      val decodedNext =
        runtime
          .deserialize(runtime.serialize(next))
          .asInstanceOf[NumericRange.Inclusive[RefInt]]
      decodedNext.start.value shouldEqual 2
      decodedNext.end.value shouldEqual 6
      decodedNext.step.value shouldEqual 2
    }
    "reserve range carrier storage" in {
      Seq[AnyRef](
        Range.apply(1, 10),
        Range.inclusive(1, 10)).foreach(assertCarrierBudget)
    }
    "reserve numeric range carrier storage" in {
      Seq[AnyRef](
        NumericRange.apply(1, 10, 1),
        NumericRange.inclusive(1, 10, 1)).foreach(assertCarrierBudget)
    }
    "enforce numeric range depth" in {
      val value = nestedRange(4)
      val bytes = nestedRangeFory(64).serialize(value)
      val decoded =
        nestedRangeFory(5)
          .deserialize(bytes)
          .asInstanceOf[NumericRange.Inclusive[AnyRef]]
      decoded.start should not be null
      intercept[InsecureException] {
        nestedRangeFory(4).deserialize(bytes)
      }
    }
  }
}

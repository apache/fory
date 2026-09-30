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

import java.util.concurrent.{Callable, Executors, TimeUnit}

import org.apache.fory.{Fory, ThreadSafeFory}
import org.apache.fory.config.ForyBuilder
import org.apache.fory.scala.ForyScala
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration.{Duration, FiniteDuration}

case class NativePerson(github: String, age: Int, id: Long)

class NativePojo(val f1: Int, val f2: String) {
  override def equals(other: Any): Boolean = other match {
    case that: NativePojo => f1 == that.f1 && f2 == that.f2
    case _ => false
  }

  override def hashCode(): Int = f1 * 31 + f2.hashCode
}

object NativeSingleton {
  val value = 42
}

object NativeColor extends Enumeration {
  type NativeColor = Value
  val Red, Green, Blue = Value
}

case class NativeAddress(street: String, city: String)

case class NativeCompany(name: String, address: NativeAddress)

case class NativeEmployee(name: String, company: NativeCompany, tags: List[String])

case class NativeOptionHolder(
    some: Option[String],
    none: Option[String],
    nested: Option[Option[Int]],
    list: List[Option[Long]])

case class NativeEitherHolder(result: Either[String, Int], results: List[Either[String, Int]])

case class NativeSelfRef(name: String, var next: NativeSelfRef)

class NativeSerializationTest extends AnyWordSpec with Matchers {
  private val compatibleModes = Seq(true, false)

  private def builder(compatible: Boolean = true): ForyBuilder =
    ForyScala.builder()
      .withXlang(false)
      .withCompatible(compatible)
      .withRefTracking(true)
      .suppressClassRegistrationWarnings(false)

  private def roundTrip[T](fory: Fory, value: T): T =
    fory.deserialize(fory.serialize(value)).asInstanceOf[T]

  compatibleModes.foreach { compatible =>
    s"fory scala native mode (compatible=$compatible)" should {
      "round trip a registered case class" in {
        val fory = builder(compatible).build()
        fory.register(classOf[NativePerson])
        val p = NativePerson("https://github.com/chaokunyang", 18, 1)
        roundTrip(fory, p) shouldEqual p
      }

      "round trip a registered POJO class" in {
        val fory = builder(compatible).build()
        fory.register(classOf[NativePojo])
        roundTrip(fory, new NativePojo(1, "chaokunyang")) shouldEqual new NativePojo(1, "chaokunyang")
      }

      "preserve registered object singletons" in {
        val fory = builder(compatible).build()
        fory.register(NativeSingleton.getClass)
        val o1 = roundTrip(fory, NativeSingleton)
        val o2 = roundTrip(fory, NativeSingleton)
        o1 shouldBe theSameInstanceAs(NativeSingleton)
        o2 shouldBe theSameInstanceAs(o1)
      }

      "round trip Option roots" in {
        val fory = builder(compatible).requireClassRegistration(false).build()
        roundTrip(fory, Some(100L): Option[Long]) shouldEqual Some(100L)
        roundTrip(fory, None: Option[Long]) shouldBe None
        roundTrip(fory, Some("str")) shouldEqual Some("str")
      }

      "round trip Option fields" in {
        val fory = builder(compatible).requireClassRegistration(false).build()
        val holder = NativeOptionHolder(
          Some("a"),
          None,
          Some(Some(1)),
          List(Some(1L), None, Some(3L)))
        roundTrip(fory, holder) shouldEqual holder
        val empty = NativeOptionHolder(None, None, None, Nil)
        roundTrip(fory, empty) shouldEqual empty
      }

      "round trip Either values" in {
        val fory = builder(compatible).requireClassRegistration(false).build()
        val right: Either[String, Int] = Right(42)
        val left: Either[String, Int] = Left("error")
        roundTrip(fory, right) shouldEqual right
        roundTrip(fory, left) shouldEqual left
        val holder = NativeEitherHolder(left, List(right, left, Right(7)))
        roundTrip(fory, holder) shouldEqual holder
      }

      "round trip Scala 2 Enumeration values" in {
        val fory = builder(compatible).build()
        fory.register(Class.forName("scala.Enumeration$Val"))
        fory.register(NativeColor.getClass)
        NativeColor.values.foreach { color =>
          roundTrip(fory, color) shouldEqual color
        }
      }

      "round trip nested registered types" in {
        val fory = builder(compatible).build()
        fory.register(classOf[NativeAddress])
        fory.register(classOf[NativeCompany])
        fory.register(classOf[NativeEmployee])
        val employee = NativeEmployee(
          "John",
          NativeCompany("Acme", NativeAddress("123 Main St", "Springfield")),
          List("developer", "scala"))
        roundTrip(fory, employee) shouldEqual employee
      }

      "preserve shared and cyclic references" in {
        val fory = builder(compatible).build()
        fory.register(classOf[NativeSelfRef])
        fory.register(classOf[NativeAddress])
        val node = NativeSelfRef("a", null)
        node.next = node
        val decoded = roundTrip(fory, node)
        decoded.name shouldEqual "a"
        decoded.next shouldBe theSameInstanceAs(decoded)

        val address = NativeAddress("1 Main St", "Springfield")
        val shared = roundTrip(fory, List(address, address))
        shared.head shouldBe theSameInstanceAs(shared(1))
      }

      "round trip Duration values" in {
        val fory = builder(compatible).requireClassRegistration(false).build()
        val durations: Seq[Duration] = Seq(
          Duration.Zero,
          Duration(1, TimeUnit.NANOSECONDS),
          Duration(2, TimeUnit.MICROSECONDS),
          Duration(3, TimeUnit.MILLISECONDS),
          Duration(4, TimeUnit.SECONDS),
          Duration(5, TimeUnit.MINUTES),
          Duration(6, TimeUnit.HOURS),
          Duration(7, TimeUnit.DAYS),
          Duration(-8, TimeUnit.SECONDS),
          FiniteDuration(Long.MaxValue, TimeUnit.NANOSECONDS),
          Duration.Inf,
          Duration.MinusInf)
        durations.foreach { d =>
          roundTrip(fory, d) shouldEqual d
        }
        roundTrip(fory, Duration.Inf) shouldBe theSameInstanceAs(Duration.Inf)
        roundTrip(fory, Duration.Undefined).asInstanceOf[Duration].isFinite shouldBe false
      }

      "round trip tuples inside collections" in {
        val fory = builder(compatible).requireClassRegistration(false).build()
        val value = List((1, "a"), (2, "b"))
        roundTrip(fory, value) shouldEqual value
        val map = Map("a" -> (1, 2L), "b" -> (3, 4L))
        roundTrip(fory, map) shouldEqual map
      }

      "copy case classes with ref copy" in {
        val fory = builder(compatible)
          .withRefCopy(true)
          .requireClassRegistration(false)
          .build()
        val company = NativeCompany("Acme", NativeAddress("123 Main St", "Springfield"))
        val copied = fory.copy(company)
        copied shouldEqual company
        copied should not be theSameInstanceAs(company)
        copied.address should not be theSameInstanceAs(company.address)
        val node = NativeSelfRef("a", null)
        node.next = node
        val copiedNode = fory.copy(node)
        copiedNode should not be theSameInstanceAs(node)
        copiedNode.next shouldBe theSameInstanceAs(copiedNode)
      }
    }
  }

  "fory scala module installation" should {
    "install through Fory.builder().withModule(ForyScala)" in {
      val fory = Fory.builder()
        .withXlang(false)
        .withRefTracking(true)
        .withModule(ForyScala)
        .requireClassRegistration(true)
        .build()
      fory.register(classOf[NativePerson])
      val value = List(NativePerson("a", 1, 2L), NativePerson("b", 3, 4L))
      roundTrip(fory, value) shouldEqual value
      roundTrip(fory, Map("a" -> 1)) shouldEqual Map("a" -> 1)
    }

    "install once when the module is added twice" in {
      val fory = Fory.builder()
        .withXlang(false)
        .withModule(ForyScala)
        .withModule(ForyScala)
        .build()
      roundTrip(fory, Vector(1, 2, 3)) shouldEqual Vector(1, 2, 3)
    }

    "support ThreadSafeFory" in {
      val fory: ThreadSafeFory = builder()
        .requireClassRegistration(false)
        .buildThreadSafeFory()
      val pool = Executors.newFixedThreadPool(4)
      try {
        val tasks = (0 until 16).map { i =>
          pool.submit(new Callable[Boolean] {
            override def call(): Boolean = {
              val value = NativeEmployee(
                s"name-$i",
                NativeCompany("Acme", NativeAddress(s"$i Main St", "Springfield")),
                List.fill(i)("tag"))
              fory.deserialize(fory.serialize(value)) == value
            }
          })
        }
        tasks.forall(_.get(1, TimeUnit.MINUTES)) shouldBe true
      } finally {
        pool.shutdownNow()
      }
    }
  }
}

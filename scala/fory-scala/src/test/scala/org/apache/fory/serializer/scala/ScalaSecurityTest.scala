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

import java.util.Arrays

import org.apache.fory.Fory
import org.apache.fory.config.ForyBuilder
import org.apache.fory.exception.{ForyException, InsecureException}
import org.apache.fory.scala.ForyScala
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.{immutable, mutable}

case class SecurityNode(value: Int, children: List[SecurityNode])

case class SecurityUnregistered(value: String)

class ScalaSecurityTest extends AnyWordSpec with Matchers {
  private def builder(xlang: Boolean): ForyBuilder =
    ForyScala.builder()
      .withXlang(xlang)
      .withRefTracking(true)
      .requireClassRegistration(true)
      .suppressClassRegistrationWarnings(false)

  private def nested(levels: Int): SecurityNode =
    (1 until levels).foldLeft(SecurityNode(0, Nil)) { (child, i) =>
      SecurityNode(i, List(child))
    }

  private def nestedList(levels: Int): List[Any] =
    (1 until levels).foldLeft(List[Any](0)) { (child, _) => List(child) }

  Seq(false, true).foreach { xlang =>
    s"scala reader boundary (xlang=$xlang)" should {
      "reject unregistered case classes" in {
        val fory = builder(xlang).build()
        intercept[ForyException] {
          fory.serialize(SecurityUnregistered("a"))
        }
      }

      "enforce max depth for nested case classes" in {
        val writer = builder(xlang).build()
        writer.register(classOf[SecurityNode])
        val bytes = writer.serialize(nested(20))

        val reader = builder(xlang).withMaxDepth(5).build()
        reader.register(classOf[SecurityNode])
        intercept[InsecureException] {
          reader.deserialize(bytes)
        }
        // A failed root must not poison later roots on the same instance.
        reader.deserialize(reader.serialize(nested(2))) shouldEqual nested(2)
      }

      "enforce max depth for nested collections" in {
        val writer = builder(xlang).build()
        val bytes = writer.serialize(nestedList(20))
        val reader = builder(xlang).withMaxDepth(5).build()
        intercept[InsecureException] {
          reader.deserialize(bytes)
        }
      }
    }
  }

  "scala native reader boundary" should {
    "not materialize unregistered case classes" in {
      val writer = builder(xlang = false).requireClassRegistration(false).build()
      val bytes = writer.serialize(SecurityUnregistered("a"))
      val reader = builder(xlang = false).build()
      scala.util.Try(reader.deserialize(bytes)).toOption.foreach { decoded =>
        decoded should not be a[SecurityUnregistered]
      }
    }

    "enforce graph memory budget for collection kinds" in {
      val writer = builder(xlang = false).requireClassRegistration(false).build()
      val reader = builder(xlang = false)
        .requireClassRegistration(false)
        .withMaxGraphMemoryBytes(23)
        .build()
      Seq[AnyRef](
        Vector.fill(6)("v"),
        immutable.TreeSet(1, 2, 3, 4, 5, 6),
        immutable.TreeMap("a" -> 1, "b" -> 2, "c" -> 3),
        mutable.ArrayBuffer.fill(6)("v"),
        mutable.HashSet(1, 2, 3, 4, 5, 6),
        mutable.LinkedHashMap("a" -> 1, "b" -> 2, "c" -> 3)).foreach { value =>
        withClue(value.getClass.getName) {
          intercept[InsecureException] {
            reader.deserialize(writer.serialize(value))
          }
        }
      }
    }

    "reject truncated collection input and recover" in {
      val fory = builder(xlang = false).build()
      fory.register(classOf[SecurityNode])
      Seq[AnyRef](
        List.tabulate(64)(i => s"value-$i"),
        Map((0 until 64).map(i => s"k$i" -> i): _*),
        mutable.ArrayBuffer.tabulate(64)(identity),
        mutable.LinkedHashMap((0 until 64).map(i => i -> s"v$i"): _*),
        nested(10)).foreach { value =>
        withClue(value.getClass.getName) {
          val bytes = fory.serialize(value)
          Seq(1, bytes.length / 2, bytes.length - 1).foreach { length =>
            intercept[RuntimeException] {
              fory.deserialize(Arrays.copyOf(bytes, length))
            }
          }
          fory.deserialize(bytes) shouldEqual value
        }
      }
    }
  }
}

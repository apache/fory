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
import org.apache.fory.scala.ForyScala
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.{immutable, mutable}

case class CollectionKindsHolder(
    vector: Vector[Int],
    queue: immutable.Queue[String],
    arraySeq: immutable.ArraySeq[String],
    listSet: immutable.ListSet[String],
    treeSet: immutable.TreeSet[Int],
    listMap: immutable.ListMap[String, Int],
    treeMap: immutable.TreeMap[String, Int],
    treeSeqMap: immutable.TreeSeqMap[String, Int],
    arrayBuffer: mutable.ArrayBuffer[String],
    linkedHashSet: mutable.LinkedHashSet[String],
    linkedHashMap: mutable.LinkedHashMap[String, Int])

case class SpecificKindsHolder(
    intMap: immutable.IntMap[String],
    longMap: immutable.LongMap[String],
    bitSet: immutable.BitSet,
    mutableBitSet: mutable.BitSet,
    mutableLongMap: mutable.LongMap[String],
    stringBuilder: mutable.StringBuilder,
    intArraySeq: immutable.ArraySeq[Int],
    doubleArraySeq: mutable.ArraySeq[Double])

class CollectionKindsTest extends AnyWordSpec with Matchers {
  private def newFory(compatible: Boolean, requireClassRegistration: Boolean): Fory =
    ForyScala.builder()
      .withXlang(false)
      .withCompatible(compatible)
      .withRefTracking(true)
      .requireClassRegistration(requireClassRegistration)
      .suppressClassRegistrationWarnings(false)
      .build()

  private def roundTrip[T](fory: Fory, value: T): T =
    fory.deserialize(fory.serialize(value)).asInstanceOf[T]

  private def assertSameKind(fory: Fory, value: scala.collection.Iterable[_]): Unit = {
    val decoded = roundTrip(fory, value)
    decoded shouldEqual value
    decoded.getClass shouldBe value.getClass
  }

  private def assertOrdered(fory: Fory, value: scala.collection.Iterable[_]): Unit = {
    assertSameKind(fory, value)
    roundTrip(fory, value).toList shouldEqual value.toList
  }

  Seq(true, false).foreach { compatible =>
    s"pre-registered collection kinds (compatible=$compatible)" should {
      val fory = newFory(compatible, requireClassRegistration = true)

      "round trip immutable sequences" in {
        assertSameKind(fory, Nil)
        assertOrdered(fory, List(3, 1, 2))
        assertOrdered(fory, Vector(3, 1, 2))
        assertOrdered(fory, Vector.tabulate(40)(identity))
        assertOrdered(fory, Vector.tabulate(2000)(identity))
        assertOrdered(fory, immutable.Queue("c", "a", "b"))
        roundTrip(fory, LazyList(1, 2, 3)).toList shouldEqual List(1, 2, 3)
      }

      "round trip immutable sets" in {
        assertSameKind(fory, Set.empty[Int])
        assertSameKind(fory, Set(1))
        assertSameKind(fory, Set(1, 2))
        assertSameKind(fory, Set(1, 2, 3))
        assertSameKind(fory, Set(1, 2, 3, 4))
        assertSameKind(fory, immutable.HashSet(1, 2, 3, 4, 5, 6))
      }

      "round trip immutable maps" in {
        assertSameKind(fory, Map.empty[String, Int])
        assertSameKind(fory, Map("a" -> 1))
        assertSameKind(fory, Map("a" -> 1, "b" -> 2))
        assertSameKind(fory, Map("a" -> 1, "b" -> 2, "c" -> 3))
        assertSameKind(fory, Map("a" -> 1, "b" -> 2, "c" -> 3, "d" -> 4))
        assertSameKind(fory, immutable.HashMap((1 to 10).map(i => i.toString -> i): _*))
        assertOrdered(fory, immutable.TreeSeqMap("c" -> 1, "a" -> 2, "b" -> 3))
      }

      "round trip mutable sequences" in {
        assertSameKind(fory, mutable.ArrayBuffer.empty[String])
        assertOrdered(fory, mutable.ArrayBuffer(3, 1, 2))
        assertOrdered(fory, mutable.ListBuffer("c", "a", "b"))
        assertOrdered(fory, mutable.ArrayDeque(3, 1, 2))
        assertOrdered(fory, mutable.Queue(3, 1, 2))
        assertOrdered(fory, mutable.Stack(3, 1, 2))
      }

      "round trip mutable sets and maps" in {
        assertSameKind(fory, mutable.HashSet(1, 2, 3))
        assertOrdered(fory, mutable.LinkedHashSet("c", "a", "b"))
        assertSameKind(fory, mutable.HashMap("a" -> 1, "b" -> 2))
        assertOrdered(fory, mutable.LinkedHashMap("c" -> 1, "a" -> 2, "b" -> 3))
      }

      "preserve shared collection references" in {
        val shared = mutable.ArrayBuffer("a")
        val decoded = roundTrip(fory, List(shared, shared))
        decoded.head shouldBe theSameInstanceAs(decoded(1))
      }
    }

    s"collection kinds without registration (compatible=$compatible)" should {
      val fory = newFory(compatible, requireClassRegistration = false)

      "round trip array-backed and ordered kinds" in {
        assertSameKind(fory, Vector.empty[Int])
        assertOrdered(fory, immutable.ArraySeq("c", "a", "b"))
        assertOrdered(fory, immutable.ListSet("c", "a", "b"))
        assertOrdered(fory, immutable.ListMap("c" -> 1, "a" -> 2, "b" -> 3))
        assertOrdered(fory, mutable.ArraySeq("c", "a", "b"))
      }

      "round trip sorted kinds" in {
        assertOrdered(fory, immutable.TreeSet(3, 1, 2))
        assertOrdered(fory, immutable.TreeSet("c", "a", "b"))
        assertOrdered(fory, immutable.TreeMap("c" -> 1, "a" -> 2, "b" -> 3))
        assertOrdered(fory, mutable.TreeSet(3, 1, 2))
        assertOrdered(fory, mutable.TreeMap("c" -> 1, "a" -> 2, "b" -> 3))
      }

      "round trip sorted kinds with custom ordering" in {
        val set = immutable.TreeSet(1, 3, 2)(Ordering.Int.reverse)
        val decoded = roundTrip(fory, set)
        decoded.toList shouldEqual List(3, 2, 1)
        (decoded + 4).toList shouldEqual List(4, 3, 2, 1)
      }

      "round trip kinds with a specific factory" in {
        assertSameKind(fory, immutable.IntMap(3 -> "c", 1 -> "a"))
        assertSameKind(fory, immutable.IntMap.empty[String])
        assertSameKind(fory, immutable.LongMap(3L -> "c", 1L -> "a"))
        assertSameKind(fory, mutable.LongMap(3L -> "c", 1L -> "a"))
        assertSameKind(fory, immutable.BitSet(1, 70, 200))
        assertSameKind(fory, immutable.BitSet(3))
        assertSameKind(fory, mutable.BitSet(1, 70, 200))
        assertOrdered(fory, immutable.ArraySeq(3, 1, 2))
        assertOrdered(fory, immutable.ArraySeq(3L, 1L))
        assertOrdered(fory, immutable.ArraySeq('b', 'a'))
        assertOrdered(fory, mutable.ArraySeq(2.5, 1.5))
        assertOrdered(fory, mutable.ArraySeq(true, false))
      }

      "round trip StringBuilder" in {
        val decoded = roundTrip(fory, new mutable.StringBuilder("abc\u00e9\u4e2d"))
        decoded.getClass shouldBe classOf[mutable.StringBuilder]
        decoded.toString shouldEqual "abc\u00e9\u4e2d"
        decoded.append('!').toString shouldEqual "abc\u00e9\u4e2d!"
        val copied = fory.copy(new mutable.StringBuilder("xyz"))
        copied.toString shouldEqual "xyz"
      }

      "round trip kinds with a specific factory as case class fields" in {
        val fory = newFory(compatible, requireClassRegistration = false)
        fory.register(classOf[SpecificKindsHolder])
        val holder = SpecificKindsHolder(
          immutable.IntMap(1 -> "a"),
          immutable.LongMap(2L -> "b"),
          immutable.BitSet(1, 2),
          mutable.BitSet(3, 4),
          mutable.LongMap(5L -> "e"),
          new mutable.StringBuilder("sb"),
          immutable.ArraySeq(1, 2),
          mutable.ArraySeq(1.5, 2.5))
        val decoded = roundTrip(fory, holder)
        decoded.intMap shouldEqual holder.intMap
        decoded.longMap shouldEqual holder.longMap
        decoded.bitSet shouldEqual holder.bitSet
        decoded.mutableBitSet shouldEqual holder.mutableBitSet
        decoded.mutableLongMap shouldEqual holder.mutableLongMap
        decoded.stringBuilder.toString shouldEqual "sb"
        decoded.intArraySeq shouldEqual holder.intArraySeq
        decoded.intArraySeq.getClass shouldBe holder.intArraySeq.getClass
        decoded.doubleArraySeq shouldEqual holder.doubleArraySeq
        decoded.doubleArraySeq.getClass shouldBe holder.doubleArraySeq.getClass
      }

      "round trip every declared kind as case class fields" in {
        val fory = newFory(compatible, requireClassRegistration = false)
        fory.register(classOf[CollectionKindsHolder])
        val holder = CollectionKindsHolder(
          Vector(1, 2),
          immutable.Queue("q"),
          immutable.ArraySeq("s", "t"),
          immutable.ListSet("b", "a"),
          immutable.TreeSet(2, 1),
          immutable.ListMap("b" -> 1, "a" -> 2),
          immutable.TreeMap("b" -> 1, "a" -> 2),
          immutable.TreeSeqMap("b" -> 1, "a" -> 2),
          mutable.ArrayBuffer("z"),
          mutable.LinkedHashSet("b", "a"),
          mutable.LinkedHashMap("b" -> 1, "a" -> 2))
        val decoded = roundTrip(fory, holder)
        decoded shouldEqual holder
        decoded.listSet.toList shouldEqual holder.listSet.toList
        decoded.listMap.toList shouldEqual holder.listMap.toList
        decoded.treeSeqMap.toList shouldEqual holder.treeSeqMap.toList
        decoded.linkedHashSet.toList shouldEqual holder.linkedHashSet.toList
        decoded.linkedHashMap.toList shouldEqual holder.linkedHashMap.toList
      }
    }
  }
}

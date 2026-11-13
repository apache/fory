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

import scala.collection.{Seq, Map, Set, mutable}
import scala.collection.mutable.ListBuffer

class MutableCollectionSerializerTest extends AnyWordSpec with Matchers {
  val params: Seq[Boolean] = List(false, true)
  params.foreach { setFactory =>
    val builder = ForyScala.builder()
      .withXlang(false)
      .withRefTracking(true)
      .requireClassRegistration(false)
      .suppressClassRegistrationWarnings(false)
    if (setFactory) {
      builder.withSerializerFactory(new ScalaSerializerFactory())
    }
    val fory1: Fory = builder.build()
    s"fory scala collection support: setFactory $setFactory" should {
      "serialize/deserialize Seq" in {
        val seq = mutable.Seq(100, 10000L)
        fory1.deserialize(fory1.serialize(seq)) shouldEqual seq
      }
      "serialize/deserialize List" in {
        val list = mutable.ListBuffer(100, 10000L)
        fory1.deserialize(fory1.serialize(list)) shouldEqual list
        val list2 = mutable.ListBuffer(100, 10000L, 10000L, 10000L)
        fory1.deserialize(fory1.serialize(list2)) shouldEqual list2
      }
      "serialize/deserialize empty List" in {
        fory1.deserialize(fory1.serialize(ListBuffer.empty)) shouldEqual ListBuffer.empty
      }
      "serialize/deserialize Set" in {
        val set = mutable.Set(100, 10000L)
        fory1.deserialize(fory1.serialize(set)) shouldEqual set
      }
      "serialize/deserialize CollectionStruct2" in {
        val struct = CollectionStruct2(mutable.ListBuffer("a", "b"))
        fory1.deserialize(fory1.serialize(struct)) shouldEqual struct
      }
      "serialize/deserialize CollectionStruct2 with empty List" in {
        val struct1 = CollectionStruct2(ListBuffer.empty)
        fory1.deserialize(fory1.serialize(struct1)) shouldEqual struct1
      }
      "serialize/deserialize NestedCollectionStruct1" in {
        val struct = NestedCollectionStruct1(ListBuffer(ListBuffer("a", "b"), ListBuffer("a", "b")), mutable.Set(Set("c", "d")))
        fory1.deserialize(fory1.serialize(struct)) shouldEqual struct
      }
    }
    s"fory scala map support: setFactory $setFactory" should {
      "serialize/deserialize Map" in {
        val map = mutable.Map("a" -> 100, "b" -> 10000L)
        fory1.deserialize(fory1.serialize(map)) shouldEqual map
      }
      "serialize/deserialize MapStruct2" in {
        val struct = MapStruct2(mutable.Map("k1" -> "v1", "k2" -> "v2"))
        fory1.deserialize(fory1.serialize(struct)) shouldEqual struct
      }
      "serialize/deserialize MapStruct2 with empty map" in {
        val struct = MapStruct2(mutable.Map.empty)
        fory1.deserialize(fory1.serialize(struct)) shouldEqual struct
      }
      "serialize/deserialize NestedMapStruc1" in {
        val struct = NestedMapStruc1(mutable.Map("K1" -> mutable.Map("k1" -> "v1", "k2" -> "v2"), "K2" -> mutable.Map("k1" -> "v1")))
        fory1.deserialize(fory1.serialize(struct)) shouldEqual struct
      }
    }
  }

  "native mutable Scala collection copy" should {
    "copy mutable collections, maps, and fixed-size sequences" in {
      val runtime = ForyScala.builder()
        .withXlang(false)
        .withRefTracking(true)
        .withRefCopy(true)
        .requireClassRegistration(false)
        .build()
      val list = mutable.ListBuffer(1, 2)
      val copiedList = runtime.copy(list).asInstanceOf[mutable.ListBuffer[Int]]
      copiedList shouldEqual list
      copiedList should not be theSameInstanceAs(list)

      val indexed = mutable.ArraySeq[AnyRef](null)
      indexed.update(0, indexed)
      val copiedIndexed = runtime.copy(indexed).asInstanceOf[mutable.ArraySeq[AnyRef]]
      copiedIndexed(0) shouldBe theSameInstanceAs(copiedIndexed)

      val sortedSet = mutable.TreeSet(3, 1, 2)(Ordering.Int.reverse)
      val copiedSet = runtime.copy(sortedSet).asInstanceOf[mutable.TreeSet[Int]]
      copiedSet.toList shouldEqual List(3, 2, 1)
      copiedSet.ordering shouldBe Ordering.Int.reverse

      val sortedMap = mutable.TreeMap(3 -> "c", 1 -> "a", 2 -> "b")(Ordering.Int.reverse)
      val copiedMap = runtime.copy(sortedMap).asInstanceOf[mutable.TreeMap[Int, String]]
      copiedMap.toList shouldEqual List(3 -> "c", 2 -> "b", 1 -> "a")
      copiedMap.ordering shouldBe Ordering.Int.reverse
    }

    "preserve cycles in mutable collections and maps" in {
      val runtime = ForyScala.builder()
        .withXlang(false)
        .withRefTracking(true)
        .withRefCopy(true)
        .requireClassRegistration(false)
        .build()
      val list = mutable.ArrayBuffer.empty[AnyRef]
      list += list
      val copiedList = runtime.copy(list).asInstanceOf[mutable.ArrayBuffer[AnyRef]]
      copiedList should not be theSameInstanceAs(list)
      copiedList.head shouldBe theSameInstanceAs(copiedList)

      val map = mutable.LinkedHashMap.empty[String, AnyRef]
      map.put("self", map)
      val copiedMap = runtime.copy(map).asInstanceOf[mutable.LinkedHashMap[String, AnyRef]]
      copiedMap should not be theSameInstanceAs(map)
      copiedMap("self") shouldBe theSameInstanceAs(copiedMap)
    }
  }
}

case class CollectionStruct2(list: Seq[String])

case class NestedCollectionStruct1(list: Seq[Seq[String]], set: Set[Set[String]])

case class MapStruct2(map: Map[String, String])

case class NestedMapStruc1(map: Map[String, Map[String, String]])

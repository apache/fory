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
import org.apache.fory.exception.CopyException
import org.apache.fory.exception.InsecureException
import org.apache.fory.scala.ForyScala
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CollectionSerializerTest extends AnyWordSpec with Matchers {
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
        val seq = Seq(100, 10000L)
        fory1.deserialize(fory1.serialize(seq)) shouldEqual seq
      }
      "serialize/deserialize List" in {
        val list = List(100, 10000L)
        fory1.deserialize(fory1.serialize(list)) shouldEqual list
        val list2 = List(100, 10000L, 10000L, 10000L)
        fory1.deserialize(fory1.serialize(list2)) shouldEqual list2
      }
      "serialize/deserialize empty List" in {
        fory1.deserialize(fory1.serialize(List.empty)) shouldEqual List.empty
        fory1.deserialize(fory1.serialize(Nil)) shouldEqual Nil
      }
      "serialize/deserialize Set" in {
        val set = Set(100, 10000L)
        fory1.deserialize(fory1.serialize(set)) shouldEqual set
      }
      "serialize/deserialize CollectionStruct1" in {
        val struct = CollectionStruct1(List("a", "b"))
        fory1.deserialize(fory1.serialize(struct)) shouldEqual struct
      }
      "serialize/deserialize CollectionStruct1 with empty List" in {
        val struct1 = CollectionStruct1(List.empty)
        fory1.deserialize(fory1.serialize(struct1)) shouldEqual struct1
        val struct2 = CollectionStruct1(Nil)
        fory1.deserialize(fory1.serialize(struct2)) shouldEqual struct2
      }
      "serialize/deserialize NestedCollectionStruct" in {
        val struct = NestedCollectionStruct(List(List("a", "b"), List("a", "b")), Set(Set("c", "d")))
        fory1.deserialize(fory1.serialize(struct)) shouldEqual struct
      }
    }
    s"fory scala map support: setFactory $setFactory" should {
      "serialize/deserialize Map" in {
        val map = Map("a" -> 100, "b" -> 10000L)
        fory1.deserialize(fory1.serialize(map)) shouldEqual map
      }
      "serialize/deserialize MapStruct1" in {
        val struct = MapStruct1(Map("k1" -> "v1", "k2" -> "v2"))
        fory1.deserialize(fory1.serialize(struct)) shouldEqual struct
      }
      "serialize/deserialize MapStruct1 with empty map" in {
        val struct = MapStruct1(Map.empty)
        fory1.deserialize(fory1.serialize(struct)) shouldEqual struct
      }
      "serialize/deserialize NestedMapStruct" in {
        val struct = NestedMapStruct(Map("K1" -> Map("k1" -> "v1", "k2" -> "v2"), "K2" -> Map("k1" -> "v1")))
        fory1.deserialize(fory1.serialize(struct)) shouldEqual struct
      }
    }
  }

  "fory scala graph memory budget" should {
    def runtime(maxGraphMemoryBytes: Option[Long] = None): Fory = {
      val builder = ForyScala.builder()
        .withXlang(false)
        .withRefTracking(true)
        .requireClassRegistration(false)
        .suppressClassRegistrationWarnings(false)
        .withSerializerFactory(new ScalaSerializerFactory())
      maxGraphMemoryBytes.foreach(builder.withMaxGraphMemoryBytes)
      builder.build()
    }

    "reserve scala collection storage" in {
      val writer = runtime()
      val reader = runtime(maxGraphMemoryBytes = Some(23))
      intercept[InsecureException] {
        reader.deserialize(writer.serialize(List.fill(6)("v")))
      }
    }

    "reserve scala map storage" in {
      val writer = runtime()
      val reader = runtime(maxGraphMemoryBytes = Some(23))
      intercept[InsecureException] {
        reader.deserialize(writer.serialize(Map("a" -> 1, "b" -> 2, "c" -> 3)))
      }
    }
  }

  "native Scala collection copy" should {
    "copy immutable collections and retain their shape" in {
      val runtime = ForyScala.builder()
        .withXlang(false)
        .withRefTracking(true)
        .withRefCopy(true)
        .requireClassRegistration(false)
        .build()
      val list = List("a", "b")
      val copiedList = runtime.copy(list).asInstanceOf[List[String]]
      copiedList shouldEqual list
      copiedList should not be theSameInstanceAs(list)

      val sortedSet = scala.collection.immutable.TreeSet(3, 1, 2)
      val copiedSet = runtime.copy(sortedSet).asInstanceOf[scala.collection.immutable.TreeSet[Int]]
      copiedSet.toList shouldEqual List(1, 2, 3)
      copiedSet should not be theSameInstanceAs(sortedSet)

      val sortedMap = scala.collection.immutable.TreeMap(3 -> "c", 1 -> "a", 2 -> "b")
      val copiedMap =
        runtime.copy(sortedMap).asInstanceOf[scala.collection.immutable.TreeMap[Int, String]]
      copiedMap.toList shouldEqual List(1 -> "a", 2 -> "b", 3 -> "c")
      copiedMap should not be theSameInstanceAs(sortedMap)

      val holder = CollectionStruct1(list)
      val copiedHolder = runtime.copy(holder).asInstanceOf[CollectionStruct1]
      copiedHolder shouldEqual holder
      copiedHolder should not be theSameInstanceAs(holder)
      copiedHolder.list should not be theSameInstanceAs(holder.list)
    }

    "fail clearly when an immutable collection is the root of a cycle" in {
      val runtime = ForyScala.builder()
        .withXlang(false)
        .withRefTracking(true)
        .withRefCopy(true)
        .requireClassRegistration(false)
        .build()
      val mutableMap = scala.collection.mutable.LinkedHashMap.empty[String, AnyRef]
      val immutableList = List[AnyRef](mutableMap)
      mutableMap.put("list", immutableList)
      intercept[CopyException] {
        runtime.copy(immutableList)
      }

      val mutableList = scala.collection.mutable.ListBuffer.empty[AnyRef]
      val immutableMap = Map[String, AnyRef]("list" -> mutableList)
      mutableList += immutableMap
      intercept[CopyException] {
        runtime.copy(immutableMap)
      }
    }
  }
}

case class CollectionStruct1(list: List[String])

case class NestedCollectionStruct(list: List[List[String]], set: Set[Set[String]])

case class MapStruct1(map: Map[String, String])

case class NestedMapStruct(map: Map[String, Map[String, String]])

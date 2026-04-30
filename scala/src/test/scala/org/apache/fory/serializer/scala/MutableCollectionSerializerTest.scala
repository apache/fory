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

import scala.collection.{Seq, Map, Set, mutable}
import scala.collection.mutable.ListBuffer

class MutableCollectionSerializerTest extends AnyWordSpec with Matchers {
  val params: Seq[(Boolean, Boolean)] = List( (true, true))
  params.foreach{case (setOpt, setFactory) => {
    val fory1: Fory = Fory.builder()
      .withXlang(false)
      .withRefTracking(true)
      .withScalaOptimizationEnabled(setOpt)
      .requireClassRegistration(false)
      .suppressClassRegistrationWarnings(false)
      .build()
    if (setFactory) {
      ScalaSerializers.registerSerializers(fory1)
    }
    s"fory scala collection support: setOpt $setOpt, setFactory $setFactory" should {
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
    s"fory scala map support: setOpt $setOpt, setFactory $setFactory" should {
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
  }}
}

case class CollectionStruct2(list: Seq[String])

case class NestedCollectionStruct1(list: Seq[Seq[String]], set: Set[Set[String]])

case class MapStruct2(map: Map[String, String])

case class NestedMapStruc1(map: Map[String, Map[String, String]])

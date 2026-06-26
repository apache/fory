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
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.jdk.CollectionConverters._

class ScalaXlangSerializerTest extends AnyWordSpec with Matchers {
  def fory: Fory = {
    val runtime = ForyScala.builder()
      .withXlang(true)
      .withRefTracking(true)
      .withRefCopy(true)
      .requireClassRegistration(false)
      .suppressClassRegistrationWarnings(false)
      .build()
    runtime
  }

  "fory scala xlang support" should {
    "serialize collections with canonical xlang serializers" in {
      val runtime = fory
      val list = List("a", "b", "c")
      val set = Set("a", "b", "c")
      val map = Map("a" -> 1, "b" -> 2)
      runtime
        .deserialize(runtime.serialize(list))
        .asInstanceOf[java.util.List[String]]
        .asScala
        .toList shouldEqual list
      runtime
        .deserialize(runtime.serialize(set))
        .asInstanceOf[java.util.Set[String]]
        .asScala
        .toSet shouldEqual set
      runtime
        .deserialize(runtime.serialize(map))
        .asInstanceOf[java.util.Map[String, Int]]
        .asScala
        .toMap shouldEqual map
    }

    "copy mutable collections with cyclic references" in {
      val runtime = fory
      val list = scala.collection.mutable.ArrayBuffer.empty[AnyRef]
      list += list

      val copiedList = runtime.copy(list).asInstanceOf[scala.collection.Seq[AnyRef]]

      copiedList should not be theSameInstanceAs(list)
      copiedList.head shouldBe theSameInstanceAs(copiedList)
    }

    "copy mutable maps with cyclic references" in {
      val runtime = fory
      val map = scala.collection.mutable.LinkedHashMap.empty[String, AnyRef]
      map.put("self", map)

      val copiedMap = runtime.copy(map).asInstanceOf[scala.collection.Map[String, AnyRef]]

      copiedMap should not be theSameInstanceAs(map)
      copiedMap("self") shouldBe theSameInstanceAs(copiedMap)
    }

    "copy concrete mutable collection classes" in {
      val runtime = fory
      val set = scala.collection.mutable.HashSet("a", "b")
      val map = scala.collection.mutable.HashMap("a" -> "b", "c" -> "d")

      val copiedSet = runtime.copy(set).asInstanceOf[scala.collection.mutable.HashSet[String]]
      val copiedMap =
        runtime.copy(map).asInstanceOf[scala.collection.mutable.HashMap[String, String]]

      copiedSet shouldEqual set
      copiedSet should not be theSameInstanceAs(set)
      copiedMap shouldEqual map
      copiedMap should not be theSameInstanceAs(map)
    }

    "copy fixed-size mutable collections" in {
      val runtime = fory
      val arraySeq = scala.collection.mutable.ArraySeq("a", "b")
      val intArraySeq = scala.collection.mutable.ArraySeq(1, 2)
      val cyclic = scala.collection.mutable.ArraySeq[AnyRef](null)
      cyclic.update(0, cyclic)

      val copied =
        runtime.copy(arraySeq).asInstanceOf[scala.collection.mutable.ArraySeq[String]]
      val copiedIntArraySeq =
        runtime.copy(intArraySeq).asInstanceOf[scala.collection.mutable.ArraySeq[Int]]
      val copiedCyclic =
        runtime.copy(cyclic).asInstanceOf[scala.collection.mutable.ArraySeq[AnyRef]]

      copied shouldEqual arraySeq
      copied should not be theSameInstanceAs(arraySeq)
      copiedIntArraySeq shouldEqual intArraySeq
      copiedIntArraySeq should not be theSameInstanceAs(intArraySeq)
      copiedIntArraySeq.getClass shouldBe intArraySeq.getClass
      copiedCyclic should not be theSameInstanceAs(cyclic)
      copiedCyclic(0) shouldBe theSameInstanceAs(copiedCyclic)
    }

    "enforce container memory budget" in {
      val writer = fory
      val reader = ForyScala.builder()
        .withXlang(true)
        .withRefTracking(true)
        .withRefCopy(true)
        .requireClassRegistration(false)
        .suppressClassRegistrationWarnings(false)
        .withMaxContainerMemoryBytes(23)
        .build()

      intercept[InsecureException] {
        reader.deserialize(writer.serialize(List.empty[String]))
      }
      intercept[InsecureException] {
        reader.deserialize(writer.serialize(Map("k" -> 1)))
      }
    }
  }
}

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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.fory.serializer.scala

import org.apache.fory.Fory
import org.apache.fory.scala.ForyScala
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.mutable

private case class CollectionHolder(values: List[Int])

class NativeCopyTest extends AnyWordSpec with Matchers {
  private def fory: Fory =
    ForyScala.builder()
      .withXlang(false)
      .withRefTracking(true)
      .withRefCopy(true)
      .requireClassRegistration(false)
      .suppressClassRegistrationWarnings(false)
      .build()

  "native Scala collection copy" should {
    "copy immutable collections and ranges" in {
      val runtime = fory
      val list = List("a", "b")
      val copiedList = runtime.copy(list).asInstanceOf[List[String]]
      copiedList shouldEqual list
      copiedList should not be theSameInstanceAs(list)

      val range = Range.inclusive(1, 4)
      runtime.copy(range) shouldBe theSameInstanceAs(range)

      val numericRange = scala.collection.immutable.NumericRange.inclusive(1, 4, 1)
      runtime.copy(numericRange) shouldBe theSameInstanceAs(numericRange)

      val sortedSet = scala.collection.immutable.TreeSet(3, 1, 2)
      val copiedSet = runtime.copy(sortedSet).asInstanceOf[scala.collection.immutable.TreeSet[Int]]
      copiedSet.toList shouldEqual List(1, 2, 3)
      copiedSet should not be theSameInstanceAs(sortedSet)

      val sortedMap = scala.collection.immutable.TreeMap(3 -> "c", 1 -> "a", 2 -> "b")
      val copiedMap =
        runtime.copy(sortedMap).asInstanceOf[scala.collection.immutable.TreeMap[Int, String]]
      copiedMap.toList shouldEqual List(1 -> "a", 2 -> "b", 3 -> "c")
      copiedMap should not be theSameInstanceAs(sortedMap)

      val holder = CollectionHolder(list.map(_.length))
      val copiedHolder = runtime.copy(holder).asInstanceOf[CollectionHolder]
      copiedHolder shouldEqual holder
      copiedHolder should not be theSameInstanceAs(holder)
      copiedHolder.values should not be theSameInstanceAs(holder.values)
    }

    "copy mutable collections and maps" in {
      val runtime = fory
      val list = mutable.ListBuffer(1, 2)
      val copiedList = runtime.copy(list).asInstanceOf[mutable.ListBuffer[Int]]
      copiedList shouldEqual list
      copiedList should not be theSameInstanceAs(list)

      val indexed = mutable.ArraySeq[AnyRef](null)
      indexed.update(0, indexed)
      val copiedIndexed = runtime.copy(indexed).asInstanceOf[mutable.ArraySeq[AnyRef]]
      copiedIndexed(0) shouldBe theSameInstanceAs(copiedIndexed)
    }

    "preserve cycles in mutable collections and maps" in {
      val runtime = fory
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

    "preserve identity across immutable collection and mutable map cycles" in {
      val runtime = fory
      val map = mutable.LinkedHashMap.empty[String, AnyRef]
      val list = List[AnyRef](map)
      map.put("list", list)

      val copiedList = runtime.copy(list).asInstanceOf[List[AnyRef]]
      val copiedMap = copiedList.head.asInstanceOf[mutable.LinkedHashMap[String, AnyRef]]
      if (copiedList.asInstanceOf[AnyRef] eq list.asInstanceOf[AnyRef]) {
        throw new AssertionError("immutable list was not copied")
      }
      if (copiedMap.asInstanceOf[AnyRef] eq map.asInstanceOf[AnyRef]) {
        throw new AssertionError("mutable map was not copied")
      }
      if (!(copiedMap("list").asInstanceOf[AnyRef] eq copiedList.asInstanceOf[AnyRef])) {
        throw new AssertionError("copied mutable map must point to the outer copied immutable list")
      }
    }

    "preserve identity across immutable map and mutable collection cycles" in {
      val runtime = fory
      val list = mutable.ListBuffer.empty[AnyRef]
      val map = scala.collection.immutable.Map[String, AnyRef]("list" -> list)
      list += map

      val copiedMap = runtime.copy(map).asInstanceOf[scala.collection.immutable.Map[String, AnyRef]]
      val copiedList = copiedMap("list").asInstanceOf[mutable.ListBuffer[AnyRef]]
      if (copiedMap.asInstanceOf[AnyRef] eq map.asInstanceOf[AnyRef]) {
        throw new AssertionError("immutable map was not copied")
      }
      if (copiedList.asInstanceOf[AnyRef] eq list.asInstanceOf[AnyRef]) {
        throw new AssertionError("mutable list was not copied")
      }
      if (!(copiedList.head.asInstanceOf[AnyRef] eq copiedMap.asInstanceOf[AnyRef])) {
        throw new AssertionError("copied mutable list must point to the outer copied immutable map")
      }
    }
  }
}

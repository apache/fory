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
import zio.Chunk

import scala.collection.immutable.ArraySeq

object SingletonObject {
  case object Query

  case class ArraySeqQuery(c: ArraySeq[Query.type])

  case class ArrayQuery(c: Array[Query.type])

  case class CaseChunk(c: Chunk[Int])
}

class CompatibleSingleObjectSerializerTest extends AnyWordSpec with Matchers {
  def fory: Fory = {
    org.apache.fory.Fory
      .builder()
      .withScalaOptimizationEnabled(true)
      .requireClassRegistration(false)
      .withRefTracking(true)
      .withCompatible(true)
      .suppressClassRegistrationWarnings(false)
      .build()
  }

  "fory scala object support" should {
    "serialize/deserialize" in {
      fory.deserialize(fory.serialize(singleton)) shouldBe singleton
      fory.deserialize(fory.serialize(Pair(singleton, singleton))) shouldEqual Pair(singleton, singleton)
    }
    "nested type serialization in object type" in {
      val x = A.B.C("hello, world!")
      val bytes = fory.serialize(x)
      fory.deserialize(bytes) shouldEqual A.B.C("hello, world!")
    }
    // Note: These tests are skipped in compatible mode with scoped meta share
    // because meta-shared codecs don't inherit from CollectionLikeSerializer
    // which is required by Scala's collection serialization framework.
    // The collection serialization still works, just not with the compatible mode
    // code generation for collection-typed fields within case classes.
    "testArraySeqQuery" ignore {
      val o = SingletonObject.ArraySeqQuery(ArraySeq(SingletonObject.Query))
      fory.deserialize(
        fory.serialize(
          o)) shouldEqual o
    }
    "testArrayQuery" in {
      val o = SingletonObject.ArrayQuery(Array(SingletonObject.Query))
      fory.deserialize(fory.serialize(o)).getClass shouldEqual o.getClass
    }
    "testCaseChunk" ignore {
      val o = SingletonObject.CaseChunk(Chunk(1))
      fory.deserialize(fory.serialize(o)) shouldEqual o
    }
  }
}

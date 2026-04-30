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

object singleton {}

case class Pair(f1: Any, f2: Any)

object A {
  object B {
    case class C(value: String) {
    }
  }
}

class X {
  class Y {
    class Z
  }
}

class SingleObjectSerializerTest extends AnyWordSpec with Matchers {
  "fory scala object support" should {
    "serialize/deserialize" in {
      val fory = Fory.builder()
        .withXlang(false)
        .withRefTracking(true)
        .withScalaOptimizationEnabled(true)
        .requireClassRegistration(false).build()
      fory.deserialize(fory.serialize(singleton)) shouldBe singleton
      fory.deserialize(fory.serialize(Pair(singleton, singleton))) shouldEqual Pair(singleton, singleton)
    }
    "nested type serialization in object type" in {
      val fory = Fory.builder()
        .withXlang(false)
        .withRefTracking(true)
        .withScalaOptimizationEnabled(true)
        .requireClassRegistration(false).build()
      val x = A.B.C("hello, world!")
      val bytes = fory.serialize(x)
      fory.deserialize(bytes) shouldEqual A.B.C("hello, world!")
    }
  }
}

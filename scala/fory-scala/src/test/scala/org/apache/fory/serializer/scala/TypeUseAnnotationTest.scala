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
import org.apache.fory.annotation.{Int32Type, UInt32Type, UInt64Type}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class ScalaTypeUseCarrier {
  val fixedId: Int @Int32Type = 1
  val unsignedId: Int @UInt32Type = 2
  val unsignedValues: java.util.List[Long @UInt64Type] = Arrays.asList(3L)
}

class TypeUseAnnotationTest extends AnyWordSpec with Matchers {
  "Java scalar annotations" should {
    "compile at Scala type positions" in {
      val carrier = new ScalaTypeUseCarrier
      carrier.fixedId shouldBe 1
      carrier.unsignedId shouldBe 2
      carrier.unsignedValues shouldEqual Arrays.asList(3L)
    }
  }
}

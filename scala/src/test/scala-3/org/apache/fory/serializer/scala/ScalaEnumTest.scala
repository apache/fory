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
import org.apache.fory.config.Language
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

object ScalaEnumTest {
  enum ColorEnum { case Red, Green, Blue }

  case class Colors(set: Set[ColorEnum])
}

class ScalaEnumTest extends AnyWordSpec with Matchers {
  import ScalaEnumTest._

  val fory: Fory = Fory.builder()
    .withLanguage(Language.JAVA)
    .withRefTracking(true)
    .withScalaOptimizationEnabled(true)
    .requireClassRegistration(false).build()

  "fury scala enum support" should {
    "serialize/deserialize ColorEnum" in {
      val bytes = fory.serialize(ColorEnum.Green)
      fory.deserialize(bytes) shouldBe ColorEnum.Green
    }
    "serialize/deserialize Colors" in {
      val colors = Colors(Set(ColorEnum.Green, ColorEnum.Red))
      val bytes = fory.serialize(colors)
      fory.deserialize(bytes) shouldEqual colors
    }
  }
}

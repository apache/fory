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

import scala.concurrent.duration.DurationInt

import org.apache.fory.Fory
import org.apache.fory.config.Language
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class DurationTest extends AnyWordSpec with Matchers {
  val fory: Fory = Fory.builder()
    .withLanguage(Language.JAVA)
    .withRefTracking(true)
    .withScalaOptimizationEnabled(true)
    .requireClassRegistration(false).build()

  "fory scala duration support" should {
    "serialize/deserialize DurationInt" in {
      val d = 100.millis
      fory.deserialize(fory.serialize(d)) shouldEqual d
    }
  }
}

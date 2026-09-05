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

package org.apache.fory.json.scala

import org.apache.fory.json.ForyJson
import org.apache.fory.json.annotation.{ForyJsonProvider, JsonType}

object NativeWeekday extends Enumeration {
  val Monday, Tuesday = Value
}

object NativeNested {
  @JsonType
  case class Reading(value: Int, unit: String = "px")

  // No default: the companion is still needed to match `apply` against the primary constructor.
  @JsonType
  case class Plain(value: Int)

  object Deep {
    // Two levels inside an object: also depends on the nested-module literal name.
    @JsonType
    case class Nested(value: Int, unit: String = "em")
  }
}

@JsonType
case class NativeEnumerationSchedule(
    @JsonEnumeration(classOf[NativeWeekday.type]) day: NativeWeekday.Value,
    @JsonEnumeration(element = classOf[NativeWeekday.type]) days: List[NativeWeekday.Value]
)

@ForyJsonProvider
final class ScalaEnumerationNativeConfig {
  def json(): ForyJson = ForyJsonScala.builder().build()
}

object ScalaJsonEnumerationNativeImageMain {
  def main(args: Array[String]): Unit = {
    val json = new ScalaEnumerationNativeConfig().json()
    val value = NativeEnumerationSchedule(
      NativeWeekday.Tuesday,
      List(NativeWeekday.Monday, NativeWeekday.Tuesday)
    )
    require(json.fromJson(json.toJson(value), classOf[NativeEnumerationSchedule]) == value)
    // A case class declared inside an object binds `apply` and its constructor defaults on the
    // companion singleton, which the image must reach reflectively at runtime.
    val reading = NativeNested.Reading(3, "em")
    require(json.fromJson(json.toJson(reading), classOf[NativeNested.Reading]) == reading)
    require(json.fromJson("{\"value\":3}", classOf[NativeNested.Reading]).unit == "px")
    val plain = NativeNested.Plain(4)
    require(json.fromJson(json.toJson(plain), classOf[NativeNested.Plain]) == plain)
    val deep = NativeNested.Deep.Nested(5, "rem")
    require(json.fromJson(json.toJson(deep), classOf[NativeNested.Deep.Nested]) == deep)
    require(json.fromJson("{\"value\":5}", classOf[NativeNested.Deep.Nested]).unit == "em")
    println("Fory Scala 2 Enumeration native image succeeded")
  }
}

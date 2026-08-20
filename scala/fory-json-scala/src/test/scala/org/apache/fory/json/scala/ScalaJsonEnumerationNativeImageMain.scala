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
    println("Fory Scala 2 Enumeration native image succeeded")
  }
}

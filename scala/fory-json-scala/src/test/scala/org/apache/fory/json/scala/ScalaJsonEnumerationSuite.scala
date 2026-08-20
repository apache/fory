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

import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.annotation.{JsonCodec, JsonProperty}
import org.scalatest.funsuite.AnyFunSuite

import scala.annotation.meta.{field, getter, param}

object AnnotatedWeekday extends Enumeration {
  val Monday, Tuesday = Value
}

object AnnotatedMonth extends Enumeration {
  val January, February = Value
}

final class AnnotatedWeekdayCodec extends ScalaEnumerationCodec(AnnotatedWeekday)

case class AnnotatedSchedule(
    @JsonEnumeration(classOf[AnnotatedWeekday.type]) day: AnnotatedWeekday.Value,
    @JsonEnumeration(element = classOf[AnnotatedWeekday.type]) days: List[AnnotatedWeekday.Value],
    @JsonEnumeration(content = classOf[AnnotatedMonth.type]) month: Option[AnnotatedMonth.Value],
    @JsonEnumeration(
      mapKey = classOf[AnnotatedWeekday.type],
      mapValue = classOf[AnnotatedMonth.type]
    ) labels: Map[AnnotatedWeekday.Value, AnnotatedMonth.Value]
)

case class AnnotatedArray(
    @JsonEnumeration(element = classOf[AnnotatedWeekday.type]) values: Array[AnnotatedWeekday.Value]
)

case class NullableAnnotatedValue(
    @JsonEnumeration(classOf[AnnotatedWeekday.type])
    @JsonProperty(include = JsonProperty.Include.ALWAYS)
    value: AnnotatedWeekday.Value
)

case class InvalidEnumerationSlots(
    @JsonEnumeration(
      value = classOf[AnnotatedWeekday.type],
      element = classOf[AnnotatedWeekday.type]
    ) value: AnnotatedWeekday.Value
)

case class InvalidEnumerationShape(
    @JsonEnumeration(element = classOf[AnnotatedWeekday.type]) value: String
)

case class ConflictingEnumerationCodec(
    @JsonEnumeration(classOf[AnnotatedWeekday.type])
    @JsonCodec(value = classOf[AnnotatedWeekdayCodec])
    value: AnnotatedWeekday.Value
)

case class ConflictingEnumerationOwners(
    @(JsonEnumeration @param)(classOf[AnnotatedWeekday.type])
    @(JsonEnumeration @getter)(classOf[AnnotatedMonth.type])
    value: AnnotatedWeekday.Value
)

case class FieldEnumeration(
    @(JsonEnumeration @field)(classOf[AnnotatedWeekday.type]) value: AnnotatedWeekday.Value
)

class ScalaJsonEnumerationSuite extends AnyFunSuite {
  private def jsonInstances = Seq(
    ForyJsonScala.builder().withCodegen(false).build(),
    ForyJsonScala.builder().withAsyncCompilation(false).build()
  )

  test("annotation binds direct and composite values") {
    val value = AnnotatedSchedule(
      AnnotatedWeekday.Tuesday,
      List(AnnotatedWeekday.Monday, AnnotatedWeekday.Tuesday),
      Some(AnnotatedMonth.February),
      Map(AnnotatedWeekday.Monday -> AnnotatedMonth.January)
    )
    for (json <- jsonInstances) {
      val encoded = json.toJson(value)
      assert(encoded.contains("\"day\":\"Tuesday\""))
      assert(encoded.contains("\"days\":[\"Monday\",\"Tuesday\"]"))
      assert(encoded.contains("\"month\":\"February\""))
      assert(encoded.contains("\"Monday\":\"January\""))
      assert(json.fromJson(encoded, classOf[AnnotatedSchedule]) == value)
    }
  }

  test("annotation binds array elements and field declarations") {
    for (json <- jsonInstances) {
      val array = AnnotatedArray(Array(AnnotatedWeekday.Monday, AnnotatedWeekday.Tuesday))
      val decoded = json.fromJson(json.toJson(array), classOf[AnnotatedArray])
      assert(decoded.values.toSeq == array.values.toSeq)

      val field = FieldEnumeration(AnnotatedWeekday.Tuesday)
      assert(json.fromJson(json.toJson(field), classOf[FieldEnumeration]) == field)
    }
  }

  test("annotation preserves owner and null rules") {
    for (json <- jsonInstances) {
      assert(
        json.fromJson("{\"value\":null}", classOf[NullableAnnotatedValue]) ==
          NullableAnnotatedValue(null)
      )
      assert(json.toJson(NullableAnnotatedValue(null)) == "{\"value\":null}")
      assertThrows[ForyJsonException] {
        json.fromJson("{\"value\":\"Friday\"}", classOf[NullableAnnotatedValue])
      }
      val wrongOwner = AnnotatedMonth.January.asInstanceOf[AnnotatedWeekday.Value]
      assertThrows[ForyJsonException](json.toJson(NullableAnnotatedValue(wrongOwner)))
    }
  }

  test("annotation rejects invalid declarations") {
    val json = ForyJsonScala.builder().withCodegen(false).build()
    assertThrows[ForyJsonException](json.toJson(InvalidEnumerationSlots(AnnotatedWeekday.Monday)))
    assertThrows[ForyJsonException](json.toJson(InvalidEnumerationShape("Monday")))
    assertThrows[ForyJsonException] {
      json.toJson(ConflictingEnumerationCodec(AnnotatedWeekday.Monday))
    }
    assertThrows[ForyJsonException] {
      json.toJson(ConflictingEnumerationOwners(AnnotatedWeekday.Monday))
    }
  }
}

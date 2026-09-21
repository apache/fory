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

import java.nio.charset.StandardCharsets.UTF_8

import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.examples.EnumSchemas
import org.apache.fory.exception.InsecureException
import org.apache.fory.json.annotation.{JsonCodec, JsonProperty}
import org.apache.fory.reflect.TypeRef
import org.scalatest.funsuite.AnyFunSuite

import scala.annotation.meta.{field, getter, param}

object AnnotatedWeekday extends Enumeration {
  val Monday, Tuesday = Value
}

object AnnotatedMonth extends Enumeration {
  val January, February = Value
}

case class EnumerationValues[T](values: List[T], label: String)

object CardCases {
  sealed trait Suit
  case object Hearts extends Suit { override def toString: String = "overridden" }
  sealed trait Dark extends Suit
  case object Clubs extends Dark
  case object 中文 extends Dark
}

case class CardValues(value: CardCases.Suit, values: List[CardCases.Suit])

object TokenCases {
  sealed trait Value
  case object A extends Value
  case object Abcdefg extends Value
  case object Abcdefgh extends Value
  case object Abcdefghi extends Value
  case object Abcdefghij extends Value
  case object 中文 extends Value
}

object ManyCases {
  sealed trait Value
  case object A extends Value
  case object B extends Value
  case object C extends Value
  case object D extends Value
  case object E extends Value
  case object F extends Value
  case object G extends Value
  case object H extends Value
  case object I extends Value
}

object PackedCases {
  sealed trait Value
  case object Abcdefghijklmn extends Value
  case object A extends Value
  case object Abcdefg extends Value
}

object ReadTokenCases {
  sealed trait Value
  case object Abcdefghi extends Value
  case object A extends Value
  case object Abcdefgh extends Value
  case object Abcdefg extends Value
  case object Abcdefghij extends Value
}

case class ReadTokenValue(
    @JsonProperty(include = JsonProperty.Include.ALWAYS) value: ReadTokenCases.Value,
    label: String)

sealed trait MixedCard
case object EmptyCard extends MixedCard
final case class NamedCard(name: String) extends MixedCard

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

  test("type tokens retain enumeration owners") {
    val days = ScalaTypeRef[Array[AnnotatedWeekday.Value]]
    val months = ScalaTypeRef[Array[AnnotatedMonth.Value]]
    val nested = ScalaTypeRef[List[Option[AnnotatedWeekday.Value]]]
    val model = ScalaTypeRef[EnumerationValues[AnnotatedMonth.Value]]
    val weekday = AnnotatedWeekday
    val alias = ScalaTypeRef[weekday.Value]
    assert(alias == ScalaTypeRef[AnnotatedWeekday.Value])
    assert(days != months)
    for (json <- jsonInstances) {
      val expected = "[\"Monday\",null,\"Tuesday\"]"
      val values = Array(AnnotatedWeekday.Monday, null, AnnotatedWeekday.Tuesday)
      assert(json.toJson(values, days) == expected)
      assert(new String(json.toJsonBytes(values, days), UTF_8) == expected)
      assert(json.fromJson(expected, days).sameElements(values))
      assert(json.fromJson(expected.getBytes(UTF_8), days).sameElements(values))
      assert(json.fromJson("[\"January\"]", months).sameElements(Array(AnnotatedMonth.January)))
      assert(json.fromJson("[\"Monday\",null]", nested) == List(Some(AnnotatedWeekday.Monday), None))
      val boxed = EnumerationValues(List(AnnotatedMonth.February), "中文")
      val text = json.toJson(boxed, model)
      assert(json.fromJson(text, model).values.sameElements(boxed.values))
      assert(json.fromJson(text.getBytes(UTF_8), model).values.sameElements(boxed.values))
      assertThrows[ForyJsonException](json.fromJson("[\"Monday\"]", months))
      assertThrows[ForyJsonException](json.toJson(
        Array(AnnotatedMonth.January.asInstanceOf[AnnotatedWeekday.Value]), days))
      assertThrows[ForyJsonException](json.fromJson("\"Monday\"", new TypeRef[Enumeration#Value]() {}))
    }
  }

  test("singleton ADTs use an explicit string representation") {
    val factory = ScalaJsonCodec.stringEnum[CardCases.Suit]
    val arrayType = ScalaTypeRef[Array[CardCases.Suit]]
    for (codegen <- Seq(false, true)) {
      val json = ForyJsonScala.builder()
        .registerCodec(classOf[CardCases.Suit], factory)
        .withCodegen(codegen).withAsyncCompilation(false).build()
      val values: Array[CardCases.Suit] = Array(CardCases.Hearts, CardCases.Clubs, CardCases.中文, null)
      val expected = "[\"Hearts\",\"Clubs\",\"中文\",null]"
      assert(json.toJson(values, arrayType) == expected)
      assert(new String(json.toJsonBytes(values, arrayType), UTF_8) == expected)
      assert(json.fromJson(expected, arrayType).sameElements(values))
      assert(json.fromJson(expected.getBytes(UTF_8), arrayType).sameElements(values))
      assert(json.toJson(CardCases.Hearts) == "\"Hearts\"")
      val model = CardValues(CardCases.Hearts, List(CardCases.Clubs))
      val text = "{\"value\":\"Hearts\",\"values\":[\"Clubs\"]}"
      assert(json.toJson(model) == text)
      assert(new String(json.toJsonBytes(model), UTF_8) == text)
      assert(json.fromJson(text, classOf[CardValues]) == model)
      assert(json.fromJson(text.getBytes(UTF_8), classOf[CardValues]) == model)
      assertThrows[ForyJsonException](json.fromJson("\"Unknown\"", classOf[CardCases.Suit]))
      assertThrows[ForyJsonException](json.fromJson("{\"Hearts\":{}}", classOf[CardCases.Suit]))
    }
    val defaultJson = ForyJsonScala.builder()
      .registerCodec(classOf[CardCases.Suit], ScalaJsonCodec.derived[CardCases.Suit]).build()
    assert(defaultJson.toJson(CardCases.Hearts, classOf[CardCases.Suit]) == "{\"Hearts\":{}}")
    assert(factory.factoryKey() != ScalaJsonCodec.derived[CardCases.Suit].factoryKey())
  }

  test("string enums require a closed singleton schema") {
    assertDoesNotCompile("ScalaJsonCodec.stringEnum[MixedCard]")
    assertDoesNotCompile("ScalaJsonCodec.stringEnum[Product]")
    val json = ForyJsonScala.builder()
      .registerCodec(classOf[CardCases.Suit], ScalaJsonCodec.stringEnum[CardCases.Suit])
      .withTypeChecker((name, _) => name != CardCases.Clubs.getClass.getName).build()
    assertThrows[InsecureException](json.fromJson("\"Clubs\"", classOf[CardCases.Suit]))
  }

  test("string enum tokens preserve exact names") {
    val arrayType = ScalaTypeRef[Array[TokenCases.Value]]
    val values: Array[TokenCases.Value] = Array(
      TokenCases.A, TokenCases.Abcdefg, TokenCases.Abcdefgh, TokenCases.Abcdefghi,
      TokenCases.Abcdefghij, TokenCases.中文, null)
    val expected = "[\"A\",\"Abcdefg\",\"Abcdefgh\",\"Abcdefghi\",\"Abcdefghij\",\"中文\",null]"
    val escaped = "[\"\\u0041\", \"Abcdefg\",\"Abcdefgh\",\"Abcdefghi\",\"Abcdefghij\",\"中文\", null]"
    for (codegen <- Seq(false, true)) {
      val json = ForyJsonScala.builder()
        .registerCodec(classOf[TokenCases.Value], ScalaJsonCodec.stringEnum[TokenCases.Value])
        .withCodegen(codegen).withAsyncCompilation(false).build()
      assert(json.toJson(values, arrayType) == expected)
      assert(new String(json.toJsonBytes(values, arrayType), UTF_8) == expected)
      for (text <- Seq(expected, escaped)) {
        assert(json.fromJson(text, arrayType).sameElements(values))
        assert(json.fromJson(text.getBytes(UTF_8), arrayType).sameElements(values))
      }
      // ASCII-only input exercises the Latin1 reader, including a token at the input boundary.
      val ascii = "[\"A\",\"Abcdefg\",\"Abcdefgh\",\"Abcdefghi\",\"Abcdefghij\"]"
      assert(json.fromJson(ascii, arrayType).sameElements(values.take(5)))
      for (name <- Seq("A", "Abcdefg", "Abcdefgh", "Abcdefghi", "Abcdefghij")) {
        val text = "\"" + name + "\""
        val decoded = json.fromJson(text, classOf[TokenCases.Value])
        assert(json.toJson(decoded, classOf[TokenCases.Value]) == text)
        assert(json.fromJson(text.getBytes(UTF_8), classOf[TokenCases.Value]) eq decoded)
      }
      for (text <- Seq("[\"Abcdefgx\"]", "[\"Abcdefghx\"]", "[\"Abcdefghix\"]", "[\"A")) {
        assertThrows[ForyJsonException](json.fromJson(text, arrayType))
        assertThrows[ForyJsonException](json.fromJson(text.getBytes(UTF_8), arrayType))
      }
      assert(json.fromJson(ascii, arrayType).sameElements(values.take(5)))
    }
  }

  test("larger string enums retain table lookup") {
    val arrayType = ScalaTypeRef[Array[ManyCases.Value]]
    val values: Array[ManyCases.Value] = Array(
      ManyCases.A, ManyCases.B, ManyCases.C, ManyCases.D, ManyCases.E,
      ManyCases.F, ManyCases.G, ManyCases.H, ManyCases.I, null)
    val expected = "[\"A\",\"B\",\"C\",\"D\",\"E\",\"F\",\"G\",\"H\",\"I\",null]"
    val json = ForyJsonScala.builder()
      .registerCodec(classOf[ManyCases.Value], ScalaJsonCodec.stringEnum[ManyCases.Value]).build()
    assert(json.toJson(values, arrayType) == expected)
    assert(new String(json.toJsonBytes(values, arrayType), UTF_8) == expected)
    assert(json.fromJson(expected, arrayType).sameElements(values))
    assert(json.fromJson(expected.getBytes(UTF_8), arrayType).sameElements(values))
  }

  test("derived enum readers match complete tokens") {
    val values: Array[ReadTokenCases.Value] = Array(
      ReadTokenCases.A, ReadTokenCases.Abcdefg, ReadTokenCases.Abcdefgh,
      ReadTokenCases.Abcdefghi, ReadTokenCases.Abcdefghij, null)
    val arrayType = ScalaTypeRef[Array[ReadTokenCases.Value]]
    val expected = "[\"A\",\"Abcdefg\",\"Abcdefgh\",\"Abcdefghi\",\"Abcdefghij\",null]"
    for (codegen <- Seq(false, true)) {
      val json = ForyJsonScala.builder()
        .registerCodec(classOf[ReadTokenCases.Value], ScalaJsonCodec.stringEnum[ReadTokenCases.Value])
        .withCodegen(codegen).withAsyncCompilation(false).build()
      for (text <- Seq(expected, expected.replace("\"A\"", " \"\\u0041\" "))) {
        assert(json.fromJson(text, arrayType).sameElements(values))
        assert(json.fromJson(text.getBytes(UTF_8), arrayType).sameElements(values))
      }
      for (value <- values) {
        val scalar = json.toJson(value, classOf[ReadTokenCases.Value])
        assert(json.fromJson(scalar, classOf[ReadTokenCases.Value]) eq value)
        assert(json.fromJson(scalar.getBytes(UTF_8), classOf[ReadTokenCases.Value]) eq value)
        val model = ReadTokenValue(value, "中文")
        val text = json.toJson(model)
        assert(json.fromJson(text, classOf[ReadTokenValue]) == model)
        assert(json.fromJson(text.getBytes(UTF_8), classOf[ReadTokenValue]) == model)
      }
      for (text <- Seq("[\"Abcdefgx\"]", "[\"Abcdefghx\"]", "[\"Abcdefghix\"]", "[\"A")) {
        assertThrows[ForyJsonException](json.fromJson(text, arrayType))
        assertThrows[ForyJsonException](json.fromJson(text.getBytes(UTF_8), arrayType))
      }
      assert(json.fromJson(expected.getBytes(UTF_8), arrayType).sameElements(values))
    }
  }

  test("derived enum writers grow at token boundaries") {
    val arrayType = ScalaTypeRef[Array[PackedCases.Value]]
    val values: Array[PackedCases.Value] = Array.fill(32)(Array[PackedCases.Value](
      PackedCases.A, PackedCases.Abcdefg, PackedCases.Abcdefghijklmn, null)).flatten
    val expected = "[" + List.fill(32)("\"A\",\"Abcdefg\",\"Abcdefghijklmn\",null").mkString(",") + "]"
    for (codegen <- Seq(false, true); capacity <- Seq(1, 3, 8, 15, 16, 31)) {
      val json = ForyJsonScala.builder()
        .registerCodec(classOf[PackedCases.Value], ScalaJsonCodec.stringEnum[PackedCases.Value])
        .withBufferSizeLimitBytes(capacity).withCodegen(codegen).withAsyncCompilation(false).build()
      for (_ <- 0 until 2) {
        assert(new String(json.toJsonBytes(values, arrayType), UTF_8) == expected)
        assert(json.toJson(values, arrayType) == expected)
      }
      assert(json.fromJson(expected.getBytes(UTF_8), arrayType).sameElements(values))
    }
  }

  test("applications derive schemas outside the library package") {
    val json = ForyJsonScala.builder()
      .registerCodec(classOf[EnumSchemas.State], EnumSchemas.codec).build()
    assert(json.toJson(EnumSchemas.Ready, classOf[EnumSchemas.State]) == "\"Ready\"")
    assert(json.fromJson("\"Ready\"", classOf[EnumSchemas.State]) eq EnumSchemas.Ready)
  }

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

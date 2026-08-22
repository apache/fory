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
import org.apache.fory.json.annotation.{JsonIgnore, JsonProperty, JsonUnwrapped}
import org.apache.fory.json.codec.AbstractJsonValueCodec
import org.apache.fory.json.reader.JsonReader
import org.apache.fory.json.writer.JsonWriter
import org.apache.fory.reflect.TypeRef
import org.scalatest.funsuite.AnyFunSuite

case class Node(value: Int, next: Option[Node])

case class Media(
    @JsonProperty("media_uri") uri: String,
    @JsonIgnore internalId: String = "hidden",
    tags: List[String] = Nil,
    @JsonProperty(include = JsonProperty.Include.NON_NULL) title: String = null
)

case class BodyState(id: Int) {
  var label: String = "initial"
  var count: Int = 7
}

case class CurriedDefault(a: Int)(val b: Int = a + 1)

case class UnwrappedDetails(code: Int = 5) {
  var note: String = "default-note"
}

case class UnwrappedState(
    id: Int = 3,
    @JsonUnwrapped details: UnwrappedDetails = UnwrappedDetails()
) {
  var label: String = "default-label"
}

case class NullableRequired(value: String)

case class UserId(value: Int) extends AnyVal

case class UnitValue(value: Unit)

case class ExplicitNullable(
    @JsonProperty(include = JsonProperty.Include.ALWAYS) value: String
)

object StableToken

object StatefulToken {
  val value: Int = 1
}

object Weekday extends Enumeration {
  val Monday, Tuesday = Value
}

final class WeekdayCodec extends ScalaEnumerationCodec(Weekday)

final class TaggedStringCodec extends AbstractJsonValueCodec[String] {
  override def write(writer: JsonWriter, value: String): Unit =
    if (value == null) writer.writeNull() else writer.writeString("tag:" + value)

  override def read(reader: JsonReader): String = {
    if (reader.tryReadNullToken()) return null
    val value = reader.readString()
    if (!value.startsWith("tag:"))
      throw new org.apache.fory.json.ForyJsonException("Expected tagged string")
    value.substring(4)
  }
}

case class Schedule(
    @org.apache.fory.json.annotation.JsonCodec(value = classOf[WeekdayCodec]) day: Weekday.Value
)

case class CodecSlots(
    @org.apache.fory.json.annotation.JsonCodec(elementCodec = classOf[TaggedStringCodec])
    tags: List[String],
    @org.apache.fory.json.annotation.JsonCodec(contentCodec = classOf[TaggedStringCodec])
    note: Option[String],
    @org.apache.fory.json.annotation.JsonCodec(
      keyCodec = classOf[WeekdayCodec],
      valueCodec = classOf[TaggedStringCodec]
    )
    labels: Map[Weekday.Value, String]
)

class ScalaJsonSuite extends AnyFunSuite {
  test("case class collections and recursive option") {
    val json = ForyJsonScala.builder().withCodegen(false).build()
    val node = Node(1, Some(Node(2, None)))
    val encoded = json.toJson(node)
    assert(json.fromJson(encoded, classOf[Node]) == node)

    val media = Media("u", tags = List("a", "b"))
    val mediaJson = json.toJson(media)
    assert(mediaJson.contains("\"media_uri\""))
    assert(!mediaJson.contains("internalId"))
    assert(json.fromJson(mediaJson, classOf[Media]) == media)
  }

  test("generated case class reader uses constructor defaults") {
    val json = ForyJsonScala.builder().withAsyncCompilation(false).build()
    val media = Media("u", tags = List("a", "b"))
    val encoded = json.toJson(media)
    assert(!encoded.contains("internalId"))
    assert(json.fromJson(encoded, classOf[Media]) == media)
  }

  test("case class body vars are applied after construction") {
    for (json <- Seq(
        ForyJsonScala.builder().withCodegen(false).build(),
        ForyJsonScala.builder().withAsyncCompilation(false).build()
      )) {
      val value = json.fromJson("{\"count\":11,\"id\":3,\"label\":\"ready\"}", classOf[BodyState])
      assert(value.id == 3)
      assert(value.label == "ready")
      assert(value.count == 11)

      val defaults = json.fromJson("{\"id\":4}", classOf[BodyState])
      assert(defaults.label == "initial")
      assert(defaults.count == 7)
      assert(json.toJson(value).contains("\"label\":\"ready\""))
    }
  }

  test("constructor defaults use preceding parameter lists") {
    for (json <- Seq(
        ForyJsonScala.builder().withCodegen(false).build(),
        ForyJsonScala.builder().withAsyncCompilation(false).build()
      )) {
      val value = json.fromJson("{\"a\":4}", classOf[CurriedDefault])
      assert(value.a == 4)
      assert(value.b == 5)
    }
  }

  test("unwrapped creators apply defaults and body vars") {
    for (json <- Seq(
        ForyJsonScala.builder().withCodegen(false).build(),
        ForyJsonScala.builder().withAsyncCompilation(false).build()
      )) {
      val value = json.fromJson(
        "{\"label\":\"root\",\"note\":\"child\"}",
        classOf[UnwrappedState]
      )
      assert(value.id == 3)
      assert(value.label == "root")
      assert(value.details.code == 5)
      assert(value.details.note == "child")
    }
  }

  test("required constructor values cannot be omitted as null") {
    for (json <- Seq(
        ForyJsonScala.builder().withCodegen(false).build(),
        ForyJsonScala.builder().withAsyncCompilation(false).build()
      )) {
      assertThrows[org.apache.fory.json.ForyJsonException] {
        json.toJson(NullableRequired(null))
      }
      assert(json.toJson(ExplicitNullable(null)) == "{\"value\":null}")
      assert(json.fromJson("{\"value\":null}", classOf[ExplicitNullable]) == ExplicitNullable(null))
    }
  }

  test("declared Scala collection and algebraic types") {
    val json = ForyJsonScala.builder().withCodegen(false).build()
    val listType = new TypeRef[List[Int]]() {}
    assert(json.fromJson(json.toJson(List(1, 2, 3), listType), listType) == List(1, 2, 3))
    assert(json.toJson(List.empty[Int], listType) == "[]")

    val mapType = new TypeRef[Map[String, Option[Int]]]() {}
    val value = Map("a" -> Some(1), "b" -> None)
    assert(json.fromJson(json.toJson(value, mapType), mapType) == value)

    val someType = new TypeRef[Some[Int]]() {}
    assert(json.fromJson("1", someType) == Some(1))
    assertThrows[org.apache.fory.json.ForyJsonException](json.fromJson("null", someType))

    val optionType = new TypeRef[Option[Int]]() {}
    assert(json.fromJson("null", optionType) == None)
    assert(json.fromJson(json.toJson(None), classOf[None.type]) == None)
  }

  test("Either uses compact branch names and reads legacy names") {
    val json = ForyJsonScala.builder().withCodegen(false).build()
    val eitherType = new TypeRef[Either[Int, String]]() {}
    val leftType = new TypeRef[Left[Int, String]]() {}
    val rightType = new TypeRef[Right[Int, String]]() {}
    val left: Either[Int, String] = Left(7)
    val right: Either[Int, String] = Right("ok")

    assert(json.toJson(left, eitherType) == "{\"l\":7}")
    assert(json.toJson(right, eitherType) == "{\"r\":\"ok\"}")
    assert(new String(json.toJsonBytes(left, eitherType), UTF_8) == "{\"l\":7}")
    assert(new String(json.toJsonBytes(right, eitherType), UTF_8) == "{\"r\":\"ok\"}")

    assert(json.fromJson("{\"l\":7}", eitherType) == left)
    assert(json.fromJson("{\"left\":7}", eitherType) == left)
    assert(json.fromJson("{\"r\":\"ok\"}", eitherType) == right)
    assert(json.fromJson("{\"right\":\"ok\"}", eitherType) == right)
    assert(json.fromJson("{\"r\":\"中文\"}", eitherType) == Right("中文"))
    assert(json.fromJson("{\"l\":7}".getBytes(UTF_8), eitherType) == left)
    assert(json.fromJson("{\"left\":7}".getBytes(UTF_8), eitherType) == left)
    assert(json.fromJson("{\"r\":\"ok\"}".getBytes(UTF_8), eitherType) == right)
    assert(json.fromJson("{\"right\":\"ok\"}".getBytes(UTF_8), eitherType) == right)

    assert(json.fromJson("null", eitherType) == null)
    assert(json.toJson(null.asInstanceOf[Either[Int, String]], eitherType) == "null")
    assert(json.fromJson("{\"l\":7}", leftType) == Left(7))
    assert(json.fromJson("{\"r\":\"ok\"}".getBytes(UTF_8), rightType) == Right("ok"))
    assertThrows[ForyJsonException](json.fromJson("{\"r\":\"ok\"}", leftType))
    assertThrows[ForyJsonException](json.fromJson("{\"left\":7}".getBytes(UTF_8), rightType))

    val nullableType = new TypeRef[Either[String, String]]() {}
    assert(json.toJson(Left[String, String](null), nullableType) == "{\"l\":null}")
    assert(json.fromJson("{\"r\":null}", nullableType) == Right(null))

    for (invalid <- Seq("{}", "{\"l\":7,\"r\":\"ok\"}", "{\"x\":7}", "[7]")) {
      assertThrows[ForyJsonException](json.fromJson(invalid, eitherType))
      assertThrows[ForyJsonException](json.fromJson(invalid.getBytes(UTF_8), eitherType))
    }
  }

  test("range and duration shapes") {
    val json = ForyJsonScala.builder().withCodegen(false).build()
    val range = Range(1, 10, 2)
    assert(json.toJson(range) == "[1,3,5,7,9]")
    assert(json.fromJson("[1,3,5,7,9]", classOf[Range]).toList == range.toList)

    val duration = new scala.concurrent.duration.FiniteDuration(100, java.util.concurrent.TimeUnit.MILLISECONDS)
    val encoded = json.toJson(duration)
    assert(json.fromJson(encoded, classOf[scala.concurrent.duration.FiniteDuration]) == duration)

    val numericType = ScalaTypeRef[scala.collection.immutable.NumericRange[Int]]
    val numeric = scala.collection.immutable.NumericRange.inclusive(1, 9, 2)
    assert(json.toJson(numeric, numericType) == "[1,3,5,7,9]")
    assert(json.fromJson("[1,3,5,7,9]", numericType) == numeric)

    val exclusiveType = ScalaTypeRef[scala.collection.immutable.NumericRange.Exclusive[Long]]
    val exclusive = scala.collection.immutable.NumericRange(1L, 10L, 2L)
    assert(json.fromJson(json.toJson(exclusive, exclusiveType), exclusiveType) == exclusive)
  }

  test("strict collections maps and bit sets") {
    val json = ForyJsonScala.builder().withCodegen(false).build()

    val vectorType = new TypeRef[Vector[Int]]() {}
    assert(json.fromJson("[1,2,3]", vectorType) == Vector(1, 2, 3))
    val listSetType = new TypeRef[scala.collection.immutable.ListSet[Int]]() {}
    assert(json.fromJson("[1,2,2]", listSetType) == scala.collection.immutable.ListSet(1, 2))
    val linkedMapType = new TypeRef[scala.collection.mutable.LinkedHashMap[String, Int]]() {}
    assert(
      json.fromJson("{\"a\":1,\"b\":2}", linkedMapType) ==
        scala.collection.mutable.LinkedHashMap("a" -> 1, "b" -> 2)
    )
    val intMapType = new TypeRef[scala.collection.immutable.IntMap[String]]() {}
    assert(json.fromJson("{\"1\":\"a\"}", intMapType) == scala.collection.immutable.IntMap(1 -> "a"))

    assert(
      json.fromJson("[1,64,130]", classOf[scala.collection.immutable.BitSet]) ==
        scala.collection.immutable.BitSet(1, 64, 130)
    )
    assertThrows[org.apache.fory.json.ForyJsonException] {
      json.fromJson("[-1]", classOf[scala.collection.immutable.BitSet])
    }
    assertThrows[org.apache.fory.json.ForyJsonException] {
      json.fromJson("[100000000]", classOf[scala.collection.immutable.BitSet])
    }
    assertThrows[org.apache.fory.json.ForyJsonException] {
      val lazyType = new TypeRef[LazyList[Int]]() {}
      json.fromJson("[1]", lazyType)
    }

    val bounded = ForyJsonScala.builder().withCodegen(false).withMaxGraphMemoryBytes(48).build()
    assertThrows[org.apache.fory.json.ForyJsonException] {
      bounded.fromJson("[\"a\",\"b\",\"c\",\"d\"]", new TypeRef[Seq[String]]() {})
    }
    assertThrows[org.apache.fory.json.ForyJsonException] {
      bounded.fromJson("[\"a\",\"b\",\"c\",\"d\"]", new TypeRef[Iterable[String]]() {})
    }
  }

  test("tuples and owner-bound Scala Enumeration") {
    val json = ForyJsonScala.builder().withCodegen(false).build()
    val pairType = new TypeRef[(Int, String)]() {}
    assert(json.toJson((1, "a"), pairType) == "[1,\"a\"]")
    assert(json.fromJson("[1,\"a\"]", pairType) == ((1, "a")))
    assertThrows[org.apache.fory.json.ForyJsonException] {
      json.fromJson("[1]", pairType)
    }

    val tuple5Type = new TypeRef[(Int, String, Boolean, Long, Double)]() {}
    val tuple5 = (1, "a", true, 2L, 3.5)
    assert(json.fromJson(json.toJson(tuple5, tuple5Type), tuple5Type) == tuple5)

    val tuple22Type = new TypeRef[(Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int,
      Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int)]() {}
    val tuple22 = (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22)
    assert(json.fromJson(json.toJson(tuple22, tuple22Type), tuple22Type) == tuple22)

    val schedule = Schedule(Weekday.Tuesday)
    assert(json.fromJson(json.toJson(schedule), classOf[Schedule]) == schedule)
  }

  test("value class and Unit use scalar shapes") {
    val json = ForyJsonScala.builder().withCodegen(false).build()
    assert(json.toJson(UserId(7)) == "7")
    assert(json.fromJson("7", classOf[UserId]) == UserId(7))
    assert(json.toJson(UnitValue(())) == "{\"value\":null}")
    assert(json.fromJson("{\"value\":null}", classOf[UnitValue]) == UnitValue(()))
  }

  test("standalone object uses strict fixed object codec") {
    for (json <- Seq(
        ForyJsonScala.builder().withCodegen(false).build(),
        ForyJsonScala.builder().withAsyncCompilation(false).build()
      )) {
      assert(json.toJson(StableToken) == "{}")
      assert(json.fromJson("{}", StableToken.getClass) eq StableToken)
      assertThrows[ForyJsonException](json.fromJson("{\"extra\":1}", StableToken.getClass))
    }
  }

  test("stateful object requires an exact codec") {
    val json = ForyJsonScala.builder().withCodegen(false).build()
    assertThrows[ForyJsonException](json.toJson(StatefulToken))
  }

  test("Scala composite child codec annotations") {
    val value = CodecSlots(
      List("a", "b"),
      Some("note"),
      Map(Weekday.Monday -> "first", Weekday.Tuesday -> "second")
    )
    for (json <- Seq(
        ForyJsonScala.builder().withCodegen(false).build(),
        ForyJsonScala.builder().withAsyncCompilation(false).build()
      )) {
      val encoded = json.toJson(value)
      assert(encoded.contains("\"tag:a\""))
      assert(encoded.contains("\"tag:note\""))
      assert(encoded.contains("\"Monday\":\"tag:first\""))
      assert(json.fromJson(encoded, classOf[CodecSlots]) == value)
    }
  }
}

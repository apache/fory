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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import org.apache.fory.json.{ForyJson, ForyJsonException}
import org.apache.fory.json.annotation.{JsonIgnore, JsonProperty, JsonSubTypes, JsonUnwrapped}
import org.apache.fory.json.codec.{AbstractJsonValueCodec, MapKeyCodec}
import org.apache.fory.json.reader.JsonReader
import org.apache.fory.json.resolver.UnsupportedJsonTypeException
import org.apache.fory.json.writer.JsonWriter
import org.apache.fory.reflect.TypeRef
import org.apache.fory.serializer.GraphMemoryEstimates
import org.scalatest.funsuite.AnyFunSuite

import scala.annotation.nowarn
import scala.collection.immutable.NumericRange
import scala.concurrent.duration.{Duration, FiniteDuration}

case class Node(value: Int, next: Option[Node])

case class BigIntFields(value: BigInt, values: Vector[BigInt])

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

object NestedModels {
  case class Point(x: Int, y: String)

  case class Region(origin: Point, size: Int = 2)

  case class Span(from: Int)(val to: Int = from + 1)

  case class UnwrappedNested(code: Int = 5) {
    var note: String = "default-note"
  }

  case class UnwrappedOwner(
      id: Int = 3,
      @JsonUnwrapped nested: UnwrappedNested = UnwrappedNested()
  )

  object Inner {
    case class Depth(level: Int, unit: String = "px")
  }
}

class OuterHolder {
  case class Bound(id: Int)
}

// Declared in a method of an object, so it captures no outer instance and its companion is a
// local module with no MODULE$. A method-local case class inside a class hits the outer check
// instead.
object MethodLocalHolder {
  def create(): Any = {
    case class MethodLocal(id: Int)
    MethodLocal(1)
  }
}

case class NullableRequired(value: String)

case class EmptyRequired(value: String, items: java.util.List[String], numbers: Array[Int])

case class ExplicitEmptyRequired(
    @JsonProperty(include = JsonProperty.Include.NON_EMPTY) value: String
)

case class EmptyDefault(
    @JsonProperty(include = JsonProperty.Include.NON_EMPTY) value: String = ""
)

case class UserId(value: Int) extends AnyVal

case class LongId(value: Long) extends AnyVal

case class LongStringValues(
    aFirst: Long,
    boxed: java.lang.Long,
    values: Array[Long],
    id: LongId,
    atomic: AtomicLong
)

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

final class BooleanLabelCodec extends AbstractJsonValueCodec[Boolean] {
  override def write(writer: JsonWriter, value: Boolean): Unit =
    writer.writeString(if (value) "yes" else "no")

  override def read(reader: JsonReader): Boolean = reader.readString() == "yes"
}

final class LabeledIntCodec extends AbstractJsonValueCodec[Int] {
  override def write(writer: JsonWriter, value: Int): Unit = writer.writeString("id:" + value)

  override def read(reader: JsonReader): Int = reader.readString().stripPrefix("id:").toInt
}

case class IntSetValues(
    values: Set[Int],
    @org.apache.fory.json.annotation.JsonCodec(elementCodec = classOf[LabeledIntCodec])
    labeled: Set[Int]
)

case class BooleanArraySeqValue(
    @org.apache.fory.json.annotation.JsonCodec(elementCodec = classOf[BooleanLabelCodec])
    values: scala.collection.immutable.ArraySeq[Boolean]
)

@org.apache.fory.json.annotation.JsonMixin(target = classOf[java.lang.Boolean])
@org.apache.fory.json.annotation.JsonCodec(value = classOf[BooleanLabelCodec])
trait BooleanLabelMixin

case class BooleanCollectionsValue(
    @org.apache.fory.json.annotation.JsonCodec(elementCodec = classOf[BooleanLabelCodec])
    list: List[Boolean],
    @org.apache.fory.json.annotation.JsonCodec(elementCodec = classOf[BooleanLabelCodec])
    vector: Vector[Boolean]
)

case class Schedule(
    @org.apache.fory.json.annotation.JsonCodec(value = classOf[WeekdayCodec]) day: Weekday.Value
)

final class PrefixedIntKeyCodec extends MapKeyCodec {
  override def toName(key: Object): String = "key:" + key

  override def fromName(name: String): Object = {
    if (!name.startsWith("key:")) throw new ForyJsonException("Expected prefixed integer key")
    java.lang.Integer.valueOf(name.substring(4))
  }
}

case class IntMapCodecSlots(
    @org.apache.fory.json.annotation.JsonCodec(valueCodec = classOf[TaggedStringCodec])
    values: scala.collection.immutable.IntMap[String],
    @org.apache.fory.json.annotation.JsonCodec(
      keyCodec = classOf[PrefixedIntKeyCodec],
      valueCodec = classOf[TaggedStringCodec]
    )
    labels: scala.collection.immutable.IntMap[String]
)

final class PrefixedLongKeyCodec extends MapKeyCodec {
  override def toName(key: Object): String = "key:" + key

  override def fromName(name: String): Object = {
    if (!name.startsWith("key:")) throw new ForyJsonException("Expected prefixed long key")
    java.lang.Long.valueOf(name.substring(4))
  }
}

case class LongMapCodecSlots(
    @org.apache.fory.json.annotation.JsonCodec(valueCodec = classOf[TaggedStringCodec])
    values: scala.collection.mutable.LongMap[String],
    @org.apache.fory.json.annotation.JsonCodec(
      keyCodec = classOf[PrefixedLongKeyCodec],
      valueCodec = classOf[TaggedStringCodec]
    )
    labels: scala.collection.mutable.LongMap[String]
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

case class Label(value: String) extends AnyVal

case class Box[T](value: T)

case class Boxes(ints: Box[Int], strings: Box[String])

case class VarParams(var a: Int, var b: String = "b")

case class Optionals(
    a: Option[Int],
    b: Option[String] = None,
    c: Option[Option[Int]] = None
)

case class ScalarFields(
    decimal: BigDecimal,
    builder: scala.collection.mutable.StringBuilder,
    duration: Duration,
    finite: FiniteDuration,
    range: Range,
    pair: (Int, String),
    name: Label,
    names: List[Label],
    buffer: scala.collection.mutable.ArrayBuffer[String],
    listMap: scala.collection.immutable.ListMap[String, Int],
    linkedSet: scala.collection.mutable.LinkedHashSet[Int]
)

case class Wide(
    f1: Int, f2: Int, f3: Int, f4: Int, f5: Int, f6: Int, f7: Int, f8: Int, f9: Int, f10: Int,
    f11: Int, f12: Int, f13: Int, f14: Int, f15: Int, f16: Int, f17: Int, f18: Int, f19: Int,
    f20: Int, f21: Int, f22: Int, f23: Int, f24: Int = 24
)

case class LazyItems(items: LazyList[Int])
case class TriedValue(value: scala.util.Try[Int])
case class Transform(f: Int => Int)

case object Marker

object Outer {
  object Inner
}

@JsonSubTypes(
  value = Array(
    new JsonSubTypes.Type(value = classOf[Circle], name = "circle"),
    new JsonSubTypes.Type(value = classOf[Dot.type], name = "dot")
  ),
  property = "kind"
)
sealed trait Shape
case class Circle(radius: Double) extends Shape
case object Dot extends Shape

case class Shapes(shapes: List[Shape])

object Hue extends Enumeration {
  val Red = Value(1, "red")
  val Green = Value(2, "green")
}

final class HueCodec extends ScalaEnumerationCodec(Hue)

class ScalaJsonSuite extends AnyFunSuite {
  test("long as string") {
    val value =
      LongStringValues(
        Long.MinValue,
        Long.MaxValue,
        Array(-1L, 0L, Long.MaxValue),
        LongId(7L),
        new AtomicLong(Long.MaxValue)
      )
    val list = List(1L, 9007199254740992L)
    val map = Map("max" -> Long.MaxValue)
    val optional = Some(9007199254740992L): Option[Long]
    val listType = ScalaTypeRef[List[Long]]
    val mapType = ScalaTypeRef[Map[String, Long]]
    val optionType = ScalaTypeRef[Option[Long]]
    for (json <- Seq(
        ForyJsonScala.builder().writeLongAsString(true).withCodegen(false).build(),
        ForyJsonScala.builder().writeLongAsString(true).withAsyncCompilation(false).build()
      )) {
      val encoded = json.toJson(value)
      assert(encoded.contains("\"aFirst\":\"-9223372036854775808\""), encoded)
      assert(encoded.contains("\"boxed\":\"9223372036854775807\""), encoded)
      assert(
        encoded.contains("\"values\":[\"-1\",\"0\",\"9223372036854775807\"]"),
        encoded
      )
      assert(encoded.contains("\"id\":\"7\""), encoded)
      assert(encoded.contains("\"atomic\":\"9223372036854775807\""), encoded)
      assert(new String(json.toJsonBytes(value), UTF_8) == encoded)
      assert(json.toJson(list, listType) == "[\"1\",\"9007199254740992\"]")
      assert(json.toJson(map, mapType) == "{\"max\":\"9223372036854775807\"}")
      assert(json.toJson(optional, optionType) == "\"9007199254740992\"")
      assert(json.fromJson("[\"1\",9007199254740992]", listType) == list)
      assert(json.fromJson("{\"max\":\"9223372036854775807\"}", mapType) == map)
      assert(json.fromJson("\"9007199254740992\"", optionType) == optional)

      val decoded = json.fromJson(encoded, classOf[LongStringValues])
      assert(decoded.aFirst == value.aFirst)
      assert(decoded.boxed == value.boxed)
      assert(decoded.values.sameElements(value.values))
      assert(decoded.id == value.id)
      assert(decoded.atomic.get() == value.atomic.get())
      assert(json.fromJson("\"9223372036854775807\"", classOf[Long]) == Long.MaxValue)
      assert(json.fromJson("9223372036854775807", classOf[Long]) == Long.MaxValue)
    }
  }

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

  test("case class declared inside an object") {
    for (json <- Seq(
        ForyJsonScala.builder().withCodegen(false).build(),
        ForyJsonScala.builder().withAsyncCompilation(false).build()
      )) {
      val region = NestedModels.Region(NestedModels.Point(1, "a"), 4)
      val encoded = json.toJson(region)
      assert(encoded.contains("\"origin\""))
      assert(json.fromJson(encoded, classOf[NestedModels.Region]) == region)
      // Scala 2 keeps `apply` and the constructor defaults on the companion singleton because it
      // emits static forwarders only for a top-level companion.
      val defaulted = json.fromJson("{\"origin\":{\"x\":1,\"y\":\"a\"}}", classOf[NestedModels.Region])
      assert(defaulted.size == 2)
      // A doubly nested companion must also be spelled correctly by generated readers.
      val depth = NestedModels.Inner.Depth(3, "em")
      assert(json.fromJson(json.toJson(depth), classOf[NestedModels.Inner.Depth]) == depth)
      assert(json.fromJson("{\"level\":3}", classOf[NestedModels.Inner.Depth]).unit == "px")
    }
  }

  test("nested case class defaults use preceding parameter lists") {
    for (json <- Seq(
        ForyJsonScala.builder().withCodegen(false).build(),
        ForyJsonScala.builder().withAsyncCompilation(false).build()
      )) {
      assert(json.fromJson("{\"from\":4}", classOf[NestedModels.Span]).to == 5)
    }
  }

  test("nested unwrapped creators apply defaults") {
    for (json <- Seq(
        ForyJsonScala.builder().withCodegen(false).build(),
        ForyJsonScala.builder().withAsyncCompilation(false).build()
      )) {
      val value =
        json.fromJson("{\"note\":\"child\"}", classOf[NestedModels.UnwrappedOwner])
      assert(value.id == 3)
      assert(value.nested.code == 5)
      assert(value.nested.note == "child")
    }
  }

  test("case class declared inside a class is rejected") {
    val json = ForyJsonScala.builder().withCodegen(false).build()
    val holder = new OuterHolder
    // Both rejections assert their message: an outer-bound case class also has no reachable
    // companion, so only the message distinguishes the outer check from the companion check.
    val error = intercept[UnsupportedJsonTypeException](json.toJson(holder.Bound(1)))
    assert(error.getMessage.contains("without its outer instance"))
  }

  test("case class declared inside a method is rejected") {
    val json = ForyJsonScala.builder().withCodegen(false).build()
    val error = intercept[UnsupportedJsonTypeException](json.toJson(MethodLocalHolder.create()))
    assert(error.getMessage.contains("companion is not reachable"))
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

  test("required constructor values retain empty properties") {
    for (codegen <- Seq(false, true)) {
      val json = ForyJsonScala.builder()
        .withCodegen(codegen)
        .withAsyncCompilation(false)
        .defaultPropertyInclusion(JsonProperty.Include.NON_EMPTY)
        .build()
      val value = EmptyRequired("", new java.util.ArrayList[String](), Array.emptyIntArray)
      val text = json.toJson(value)
      assert(text == "{\"value\":\"\",\"items\":[],\"numbers\":[]}")
      assert(new String(json.toJsonBytes(value), UTF_8) == text)
      val decoded = json.fromJson(text, classOf[EmptyRequired])
      assert(decoded.value == "")
      assert(decoded.items.isEmpty)
      assert(decoded.numbers.isEmpty)
      assertThrows[ForyJsonException](json.toJson(ExplicitEmptyRequired("")))
      assert(json.toJson(EmptyDefault()) == "{}")
      assert(json.fromJson("{}", classOf[EmptyDefault]) == EmptyDefault())
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

  test("Boolean ArraySeq writers preserve element codecs") {
    import scala.collection.immutable.ArraySeq
    val booleanType = ScalaTypeRef[ArraySeq[Boolean]]
    val boxedType = ScalaTypeRef[ArraySeq[java.lang.Boolean]]
    for (json <- Seq(
        ForyJsonScala.builder().withCodegen(false).build(),
        ForyJsonScala.builder().withAsyncCompilation(false).build()
      )) {
      for (size <- Seq(0, 1, 2, 3, 4, 33, 1025)) {
        val values = ArraySeq.tabulate(size)(i => i % 3 == 0)
        val expected = values.mkString("[", ",", "]")
        assert(json.toJson(values) == expected)
        assert(new String(json.toJsonBytes(values), UTF_8) == expected)
        assert(json.toJson(values, booleanType) == expected)
        assert(new String(json.toJsonBytes(values, booleanType), UTF_8) == expected)
      }
      val boxed = ArraySeq[java.lang.Boolean](true, null, false)
      assert(json.toJson(boxed, boxedType) == "[true,null,false]")
      assert(new String(json.toJsonBytes(boxed, boxedType), UTF_8) == "[true,null,false]")
      val custom = BooleanArraySeqValue(ArraySeq(true, false, true))
      val expected = "{\"values\":[\"yes\",\"no\",\"yes\"]}"
      assert(json.toJson(custom) == expected)
      assert(new String(json.toJsonBytes(custom), UTF_8) == expected)
      assert(json.fromJson(expected, classOf[BooleanArraySeqValue]) == custom)
      assert(json.fromJson(expected.getBytes(UTF_8), classOf[BooleanArraySeqValue]) == custom)
      assert(json.fromJson("[true,null,false]", boxedType) == boxed)
      assert(json.fromJson("[true,null,false]".getBytes(UTF_8), boxedType) == boxed)
    }
  }

  test("Boolean collection writers preserve element codecs") {
    val listType = ScalaTypeRef[List[Boolean]]
    val vectorType = ScalaTypeRef[Vector[Boolean]]
    val boxedListType = ScalaTypeRef[List[java.lang.Boolean]]
    val boxedVectorType = ScalaTypeRef[Vector[java.lang.Boolean]]
    for (json <- Seq(
        ForyJsonScala.builder().withCodegen(false).build(),
        ForyJsonScala.builder().withAsyncCompilation(false).build()
      )) {
      for (size <- Seq(0, 1, 2, 33, 1025)) {
        val vector = Vector.tabulate(size)(i => i % 3 == 0)
        val list = vector.toList
        val expected = vector.mkString("[", ",", "]")
        assert(new String(json.toJsonBytes(list), UTF_8) == expected)
        assert(new String(json.toJsonBytes(vector), UTF_8) == expected)
        assert(new String(json.toJsonBytes(list, listType), UTF_8) == expected)
        assert(new String(json.toJsonBytes(vector, vectorType), UTF_8) == expected)
      }
      for (first <- Seq(false, true); second <- Seq(false, true)) {
        val vector = Vector(false, first, second)
        val expected = vector.mkString("[", ",", "]")
        assert(new String(json.toJsonBytes(vector), UTF_8) == expected)
        assert(new String(json.toJsonBytes(vector.toList), UTF_8) == expected)
      }
      val iterableType = ScalaTypeRef[scala.collection.Iterable[Boolean]]
      for (values <- Seq[scala.collection.Iterable[Boolean]](
          Vector(true, false, true),
          List(false, true, false),
          Set(true, false)
        )) {
        assert(new String(json.toJsonBytes(values, iterableType), UTF_8) == values.mkString("[", ",", "]"))
      }
      val boxed = List[java.lang.Boolean](null, true, false, null, false)
      assert(new String(json.toJsonBytes(boxed, boxedListType), UTF_8) == "[null,true,false,null,false]")
      assert(
        new String(json.toJsonBytes(boxed.toVector, boxedVectorType), UTF_8) ==
          "[null,true,false,null,false]"
      )
      val mixed = List[Any](true, false, null, 7, "x", true)
      assert(new String(json.toJsonBytes(mixed), UTF_8) == "[true,false,null,7,\"x\",true]")
      assert(new String(json.toJsonBytes(mixed.toVector), UTF_8) == "[true,false,null,7,\"x\",true]")
      val custom = BooleanCollectionsValue(List(true, false, true), Vector(false, true, false))
      val expected = "{\"list\":[\"yes\",\"no\",\"yes\"],\"vector\":[\"no\",\"yes\",\"no\"]}"
      assert(new String(json.toJsonBytes(custom), UTF_8) == expected)
      assert(json.fromJson(expected.getBytes(UTF_8), classOf[BooleanCollectionsValue]) == custom)
    }
  }

  test("Boolean collection writers respect scalar Mixins") {
    val json = ForyJsonScala.builder().registerMixin(classOf[BooleanLabelMixin]).build()
    val values = Vector(false, true, false, true)
    val expected = "[\"no\",\"yes\",\"no\",\"yes\"]"
    assert(new String(json.toJsonBytes(values), UTF_8) == expected)
    assert(new String(json.toJsonBytes(values.toList), UTF_8) == expected)
    val boxed = Vector[java.lang.Boolean](false, true, false, true)
    assert(
      new String(json.toJsonBytes(boxed, ScalaTypeRef[Vector[java.lang.Boolean]]), UTF_8) == expected
    )
    assert(
      new String(json.toJsonBytes(boxed.toList, ScalaTypeRef[List[java.lang.Boolean]]), UTF_8) == expected
    )
    // An exact boxed Boolean Mixin does not overlay the primitive Boolean schema.
    val native = values.mkString("[", ",", "]")
    assert(new String(json.toJsonBytes(values, ScalaTypeRef[Vector[Boolean]]), UTF_8) == native)
    assert(new String(json.toJsonBytes(values.toList, ScalaTypeRef[List[Boolean]]), UTF_8) == native)
  }

  test("Boolean ArraySeq reading") {
    import scala.collection.immutable.ArraySeq
    val booleanType = ScalaTypeRef[ArraySeq[Boolean]]
    val anyType = ScalaTypeRef[ArraySeq[Any]]
    for (json <- Seq(
        ForyJsonScala.builder().withCodegen(false).build(),
        ForyJsonScala.builder().withAsyncCompilation(false).build()
      )) {
      for (size <- Seq(0, 1, 8, 9, 1024, 1025, 17)) {
        val expected = ArraySeq.tabulate(size)(i => i % 3 == 0)
        val input = expected.mkString("[", ",", "]")
        val first = json.fromJson(input.getBytes(UTF_8), booleanType)
        assert(first == expected)
        assert(json.fromJson(input, booleanType) == expected)
        assert(json.fromJson("[\"true\",false]", booleanType) == ArraySeq(true, false))
        assert(json.fromJson("[\"true\",false]".getBytes(UTF_8), booleanType) == ArraySeq(true, false))
        assert(first == expected)
      }
      assert(json.fromJson("null", booleanType) == null)
      assert(json.fromJson("null".getBytes(UTF_8), booleanType) == null)
      assert(
        json.fromJson("[true,\"false\",null]".getBytes(UTF_8), anyType) ==
          ArraySeq[Any](true, "false", null)
      )
      for (invalid <- Seq("[true,", "[null]", "[[true]]", "[\"bad\"]")) {
        assertThrows[ForyJsonException](json.fromJson(invalid, booleanType))
        assertThrows[ForyJsonException](json.fromJson(invalid.getBytes(UTF_8), booleanType))
        assert(json.fromJson("[true]", booleanType) == ArraySeq(true))
        assert(json.fromJson("[false]".getBytes(UTF_8), booleanType) == ArraySeq(false))
      }
    }
  }

  test("Boolean ArraySeq memory and depth") {
    import scala.collection.immutable.ArraySeq
    val booleanType = ScalaTypeRef[ArraySeq[Boolean]]
    val nestedType = ScalaTypeRef[Vector[ArraySeq[Boolean]]]
    val wrapperBytes = GraphMemoryEstimates.shallowObjectBytes(classOf[ArraySeq.ofBoolean])
    val headerBytes = GraphMemoryEstimates.objectArrayBytes()
    for (size <- Seq(0, 17, 1024, 1025)) {
      val budget = wrapperBytes + headerBytes + size
      val json = ForyJsonScala.builder().withCodegen(false).withMaxGraphMemoryBytes(budget).build()
      val expected = ArraySeq.fill(size)(true)
      val input = expected.mkString("[", ",", "]")
      assert(json.fromJson(input, booleanType) == expected)
      assert(json.fromJson(input.getBytes(UTF_8), booleanType) == expected)
      val tooMany = ArraySeq.fill(size + 1)(true).mkString("[", ",", "]")
      assertThrows[ForyJsonException](json.fromJson(tooMany, booleanType))
      assertThrows[ForyJsonException](json.fromJson(tooMany.getBytes(UTF_8), booleanType))
      assert(json.fromJson(input.getBytes(UTF_8), booleanType) == expected)
    }
    val depthOne = ForyJsonScala.builder().withCodegen(false).maxDepth(1).build()
    for (input <- Seq("[[true]]", "[[\"true\"]]")) {
      assertThrows[ForyJsonException](depthOne.fromJson(input, nestedType))
      assertThrows[ForyJsonException](depthOne.fromJson(input.getBytes(UTF_8), nestedType))
      assert(depthOne.fromJson("[true]", booleanType) == ArraySeq(true))
    }
    val depthTwo = ForyJsonScala.builder().withCodegen(false).maxDepth(2).build()
    assert(depthTwo.fromJson("[[true]]", nestedType) == Vector(ArraySeq(true)))
    assert(depthTwo.fromJson("[[true]]".getBytes(UTF_8), nestedType) == Vector(ArraySeq(true)))
  }

  test("primitive int maps") {
    val mapType = new TypeRef[scala.collection.immutable.IntMap[String]]() {}
    val nestedType = new TypeRef[List[scala.collection.immutable.IntMap[String]]]() {}
    val keys = Seq(Int.MinValue, -1000000000, -1, 0, 1, 1000000000, Int.MaxValue)
    val expected = scala.collection.immutable.IntMap(keys.map(k => k -> k.toString): _*)
    for (json <- Seq(
        ForyJsonScala.builder().withCodegen(false).build(),
        ForyJsonScala.builder().withAsyncCompilation(false).build()
      )) {
      val input = keys.map(k => "\"" + k + "\":\"" + k + "\"").mkString("{", ",", "}")
      assert(json.fromJson(input, mapType) == expected)
      assert(json.fromJson(input.getBytes(UTF_8), mapType) == expected)
      assert(json.fromJson("[" + input + "]", nestedType) == List(expected))
      assert(json.fromJson(("[" + input + "]").getBytes(UTF_8), nestedType) == List(expected))
      assert(json.fromJson("null", mapType) == null)
      assert(json.fromJson("null".getBytes(UTF_8), mapType) == null)
      for (text <- Seq("{}", "{\"1\":\"a\",\"1\":\"你\"}", "{\"\\u0031\":\"你\"}", "{\"1\":null}")) {
        val value =
          if (text == "{}") scala.collection.immutable.IntMap.empty[String]
          else scala.collection.immutable.IntMap(1 -> (if (text.contains("null")) null else "你"))
        assert(json.fromJson(text, mapType) == value)
        assert(json.fromJson(text.getBytes(UTF_8), mapType) == value)
      }
      for (size <- Seq(1023, 1024, 1025)) {
        val value = scala.collection.immutable.IntMap((0 until size).map(i => i -> i.toString): _*)
        val text = json.toJson(value, mapType)
        assert(json.fromJson(text, mapType) == value)
        assert(json.fromJson(text.getBytes(UTF_8), mapType) == value)
      }
      for (text <- Seq("{\"2147483648\":null}", "{\"1\":", "{\"1\":\"a\",}")) {
        assertThrows[RuntimeException](json.fromJson(text, mapType))
        assertThrows[RuntimeException](json.fromJson(text.getBytes(UTF_8), mapType))
        assert(json.fromJson(input.getBytes(UTF_8), mapType) == expected)
      }
    }
    val bounded = ForyJsonScala.builder().withCodegen(false).withMaxGraphMemoryBytes(48).build()
    val input = (0 until 1025).map(i => "\"" + i + "\":null").mkString("{", ",", "}")
    assertThrows[ForyJsonException](bounded.fromJson(input, mapType))
    assertThrows[ForyJsonException](bounded.fromJson(input.getBytes(UTF_8), mapType))
    assert(bounded.fromJson("{}".getBytes(UTF_8), mapType).isEmpty)
    val shallow = ForyJsonScala.builder().withCodegen(false).maxDepth(1).build()
    assertThrows[ForyJsonException](shallow.fromJson("[{\"1\":null}]", nestedType))
    assertThrows[ForyJsonException](shallow.fromJson("[{\"1\":null}]".getBytes(UTF_8), nestedType))
    assert(shallow.fromJson("{}".getBytes(UTF_8), mapType).isEmpty)
  }

  test("int map key order") {
    val mapType = new TypeRef[scala.collection.immutable.IntMap[String]]() {}
    val random = new scala.util.Random(431)
    for (json <- Seq(
        ForyJsonScala.builder().withCodegen(false).build(),
        ForyJsonScala.builder().withAsyncCompilation(false).build()
      ); size <- Seq(0, 1, 2, 7, 8, 9, 15, 16, 17, 31, 32, 33, 63, 64, 65, 127, 128, 129, 255, 256, 257, 511, 512, 513, 1025)) {
      val keys = Vector.tabulate(size) { i =>
        if (i == 0) Int.MinValue
        else if (i == 1) Int.MaxValue
        else if (i % 7 == 0) 0
        else random.nextInt()
      }
      for (ordered <- Seq(keys.sorted, keys.sorted.reverse, random.shuffle(keys), keys.indices.toVector)) {
        val entries = ordered.zipWithIndex.map { case (key, i) =>
          key -> (if (i % 5 == 0) null else "界" + i)
        }
        val expected = entries.foldLeft(scala.collection.immutable.IntMap.empty[String]) {
          case (map, (key, value)) => map.updated(key, value)
        }
        val text = entries.map { case (key, value) =>
          "\"" + key + "\":" + (if (value == null) "null" else "\"" + value + "\"")
        }.mkString("{", ",", "}")
        val ascii = text.replace("界", "\\u754c")
        for (actual <- Seq(json.fromJson(text, mapType), json.fromJson(ascii, mapType),
            json.fromJson(text.getBytes(UTF_8), mapType))) {
          assert(actual == expected)
          assert(actual.iterator.toList == expected.iterator.toList)
        }
      }
    }
  }

  test("int map entries") {
    val mapType = new TypeRef[scala.collection.immutable.IntMap[String]]() {}
    val nestedType = new TypeRef[List[scala.collection.immutable.IntMap[String]]]() {}
    val keys = Seq(Int.MinValue, -1, 0, 1, Int.MaxValue) ++ (2 until 1002 by 7)
    val populated = scala.collection.immutable.IntMap(
      keys.zipWithIndex.map { case (key, index) =>
        key -> (if (index % 3 == 0) null else "value:\"\u0100/" + index)
      }: _*
    )
    for (json <- Seq(
        ForyJsonScala.builder().withCodegen(false).build(),
        ForyJsonScala.builder().withAsyncCompilation(false).build()
      ); value <- Seq(scala.collection.immutable.IntMap.empty[String],
        scala.collection.immutable.IntMap(7 -> "seven"), populated)) {
      val expected = value.iterator.map { case (key, entryValue) =>
        "\"" + key + "\":" + json.toJson(entryValue)
      }.mkString("{", ",", "}")
      assert(json.toJson(value, mapType) == expected)
      assert(new String(json.toJsonBytes(value, mapType), UTF_8) == expected)
      assert(json.toJson(List(value), nestedType) == "[" + expected + "]")
      assert(new String(json.toJsonBytes(List(value), nestedType), UTF_8) == "[" + expected + "]")
      assert(json.fromJson(expected, mapType) == value)
      assert(json.toJson(null, mapType) == "null")
      assert(new String(json.toJsonBytes(null, mapType), UTF_8) == "null")
    }
  }

  test("int map codec slots") {
    val value = IntMapCodecSlots(
      scala.collection.immutable.IntMap(1 -> "one"),
      scala.collection.immutable.IntMap(-1 -> "minus")
    )
    for (json <- Seq(
        ForyJsonScala.builder().withCodegen(false).build(),
        ForyJsonScala.builder().withAsyncCompilation(false).build()
      )) {
      val text = json.toJson(value)
      assert(text.contains("\"1\":\"tag:one\""))
      assert(text.contains("\"key:-1\":\"tag:minus\""))
      assert(new String(json.toJsonBytes(value), UTF_8) == text)
      assert(json.fromJson(text, classOf[IntMapCodecSlots]) == value)
      assert(json.fromJson(text.getBytes(UTF_8), classOf[IntMapCodecSlots]) == value)
    }
  }

  test("primitive long maps") {
    val mapType = new TypeRef[scala.collection.mutable.LongMap[String]]() {}
    val nestedType = new TypeRef[List[scala.collection.mutable.LongMap[String]]]() {}
    val keys = Seq(Long.MinValue, -1000000000L, -1L, 0L, 1L, 0x100000001L, 0x200000002L, Long.MaxValue)
    val expected = scala.collection.mutable.LongMap(keys.map(k => k -> k.toString): _*)
    for (json <- Seq(
        ForyJsonScala.builder().withCodegen(false).build(),
        ForyJsonScala.builder().withAsyncCompilation(false).build()
      )) {
      val input = keys.map(k => "\"" + k + "\":\"" + k + "\"").mkString("{", ",", "}")
      assert(json.fromJson(input, mapType) == expected)
      assert(json.fromJson(input.getBytes(UTF_8), mapType) == expected)
      assert(json.fromJson("[" + input + "]", nestedType) == List(expected))
      assert(json.fromJson(("[" + input + "]").getBytes(UTF_8), nestedType) == List(expected))
      assert(json.fromJson("null", mapType) == null)
      assert(json.fromJson("null".getBytes(UTF_8), mapType) == null)
      for (text <- Seq("{}", "{\"1\":\"a\",\"1\":\"你\"}", "{\"\\u0031\":\"你\"}", "{\"1\":null}")) {
        val value =
          if (text == "{}") scala.collection.mutable.LongMap.empty[String]
          else scala.collection.mutable.LongMap(1L -> (if (text.contains("null")) null else "你"))
        assert(json.fromJson(text, mapType) == value)
        assert(json.fromJson(text.getBytes(UTF_8), mapType) == value)
      }
      for (size <- Seq(0, 1, 7, 8, 9, 15, 16, 17, 31, 32, 33, 1023, 1024, 1025)) {
        val value = scala.collection.mutable.LongMap((0 until size).map(i => i.toLong -> i.toString): _*)
        val text = json.toJson(value, mapType)
        assert(json.fromJson(text, mapType) == value)
        assert(json.fromJson(text.getBytes(UTF_8), mapType) == value)
        val duplicates = (0 until size).map(i => "\"1\":\"" + i + "\"").mkString("{", ",", "}")
        val last =
          if (size == 0) scala.collection.mutable.LongMap.empty[String]
          else scala.collection.mutable.LongMap(1L -> (size - 1).toString)
        assert(json.fromJson(duplicates, mapType) == last)
        assert(json.fromJson(duplicates.getBytes(UTF_8), mapType) == last)
      }
      for (text <- Seq("{\"9223372036854775808\":null}", "{\"1\":", "{\"1\":\"a\",}")) {
        assertThrows[RuntimeException](json.fromJson(text, mapType))
        assertThrows[RuntimeException](json.fromJson(text.getBytes(UTF_8), mapType))
        assert(json.fromJson(input.getBytes(UTF_8), mapType) == expected)
      }
    }
    val bounded = ForyJsonScala.builder().withCodegen(false).withMaxGraphMemoryBytes(48).build()
    val input = (0 until 1025).map(i => "\"" + i + "\":null").mkString("{", ",", "}")
    assertThrows[ForyJsonException](bounded.fromJson(input, mapType))
    assertThrows[ForyJsonException](bounded.fromJson(input.getBytes(UTF_8), mapType))
    assert(bounded.fromJson("{}".getBytes(UTF_8), mapType).isEmpty)
    val shallow = ForyJsonScala.builder().withCodegen(false).maxDepth(1).build()
    assertThrows[ForyJsonException](shallow.fromJson("[{\"1\":null}]", nestedType))
    assertThrows[ForyJsonException](shallow.fromJson("[{\"1\":null}]".getBytes(UTF_8), nestedType))
    assert(shallow.fromJson("{}".getBytes(UTF_8), mapType).isEmpty)
  }

  test("long map entries") {
    val mapType = new TypeRef[scala.collection.mutable.LongMap[String]]() {}
    val nestedType = new TypeRef[List[scala.collection.mutable.LongMap[String]]]() {}
    val keys = Seq(Long.MinValue, Long.MaxValue, 0L, -1L, 1L) ++
      (2L to 1026L).map(i => (i << 32) | i)
    val populated = scala.collection.mutable.LongMap(
      keys.zipWithIndex.map { case (key, index) =>
        key -> (if (index % 3 == 0) null else "value:\"\u0100/" + index)
      }: _*
    )
    keys.drop(5).zipWithIndex.foreach { case (key, index) =>
      if (index % 4 == 0) populated.remove(key)
    }
    for (json <- Seq(
        ForyJsonScala.builder().withCodegen(false).build(),
        ForyJsonScala.builder().withAsyncCompilation(false).build()
      ); value <- Seq(scala.collection.mutable.LongMap.empty[String],
        scala.collection.mutable.LongMap(7L -> "seven"), populated)) {
      val expected = value.iterator.map { case (key, entryValue) =>
        "\"" + key + "\":" + json.toJson(entryValue)
      }.mkString("{", ",", "}")
      assert(json.toJson(value, mapType) == expected)
      assert(new String(json.toJsonBytes(value, mapType), UTF_8) == expected)
      assert(json.toJson(value) == expected)
      assert(new String(json.toJsonBytes(value), UTF_8) == expected)
      assert(json.toJson(List(value), nestedType) == "[" + expected + "]")
      assert(new String(json.toJsonBytes(List(value), nestedType), UTF_8) == "[" + expected + "]")
      assert(json.fromJson(expected, mapType) == value)
      assert(json.toJson(null, mapType) == "null")
      assert(new String(json.toJsonBytes(null, mapType), UTF_8) == "null")
    }
  }

  test("long map codec slots") {
    val value = LongMapCodecSlots(
      scala.collection.mutable.LongMap(1L -> "one"),
      scala.collection.mutable.LongMap(-1L -> "minus")
    )
    for (json <- Seq(
        ForyJsonScala.builder().withCodegen(false).build(),
        ForyJsonScala.builder().withAsyncCompilation(false).build()
      )) {
      val text = json.toJson(value)
      assert(text.contains("\"1\":\"tag:one\""))
      assert(text.contains("\"key:-1\":\"tag:minus\""))
      assert(new String(json.toJsonBytes(value), UTF_8) == text)
      assert(json.fromJson(text, classOf[LongMapCodecSlots]) == value)
      assert(json.fromJson(text.getBytes(UTF_8), classOf[LongMapCodecSlots]) == value)
    }
  }

  test("integer set decoding") {
    val setType = ScalaTypeRef[Set[Int]]
    val hashType = ScalaTypeRef[scala.collection.immutable.HashSet[Int]]
    val boxedType = ScalaTypeRef[Set[java.lang.Integer]]
    for (codegen <- Seq(false, true)) {
      val json = ForyJsonScala.builder().withCodegen(codegen).withAsyncCompilation(false).build()
      val model = IntSetValues(Set(Int.MinValue, Int.MaxValue), Set(-7, 8))
      val encoded = json.toJson(model)
      assert(encoded.contains("\"id:-7\""))
      assert(json.fromJson(encoded, classOf[IntSetValues]) == model)
      assert(json.fromJson(encoded.getBytes(UTF_8), classOf[IntSetValues]) == model)
      for (size <- Seq(0, 1, 8, 9, 32, 512, 1024, 1025)) {
        val values = (0 until size).map(i => (i * 1498724053) ^ (i >>> 3))
        val expected = values.toSet
        val text = (values ++ values.reverse).map { i =>
          if ((i & 1) == 0) i.toString else "\"" + i + "\""
        }.mkString("[", ",", "]")
        assert(json.fromJson(text, setType) == expected)
        assert(json.fromJson(text.getBytes(UTF_8), setType) == expected)
        assert(json.fromJson(text.getBytes(UTF_8), hashType) == expected)
      }
      assert(json.fromJson("null", setType) == null)
      assert(json.fromJson("null".getBytes(UTF_8), setType) == null)
      assert(json.fromJson("[null,1]", boxedType) == Set(null, java.lang.Integer.valueOf(1)))
      for (text <- Seq("[null]", "[2147483648]", "[-2147483649]", "[1.0]", "[1e0]", "[1,]")) {
        assertThrows[org.apache.fory.json.ForyJsonException] {
          json.fromJson(text.getBytes(UTF_8), setType)
        }
        assert(json.fromJson("[-2147483648,2147483647,0]".getBytes(UTF_8), setType) ==
          Set(Int.MinValue, Int.MaxValue, 0))
      }
    }
    val bounded = ForyJsonScala.builder().withCodegen(false).withMaxGraphMemoryBytes(128).build()
    assertThrows[org.apache.fory.json.ForyJsonException] {
      bounded.fromJson((0 until 1025).mkString("[", ",", "]").getBytes(UTF_8), setType)
    }
    assert(bounded.fromJson("[1,1]".getBytes(UTF_8), setType) == Set(1))
    val ownerBytes = org.apache.fory.serializer.GraphMemoryEstimates.shallowObjectBytes(
      classOf[scala.collection.immutable.HashSet[_]]
    )
    val repeated = List.fill(9)(7).mkString("[", ",", "]").getBytes(UTF_8)
    val exact = ForyJsonScala.builder().withMaxGraphMemoryBytes(ownerBytes + 9 * 4).build()
    assert(exact.fromJson(repeated, setType) == Set(7))
    val short = ForyJsonScala.builder().withMaxGraphMemoryBytes(ownerBytes + 9 * 4 - 1).build()
    assertThrows[org.apache.fory.json.ForyJsonException] {
      short.fromJson(repeated, setType)
    }
  }

  test("mutable hash set growth") {
    val setType = new TypeRef[scala.collection.mutable.Set[String]]() {}
    for (codegen <- Seq(false, true)) {
      val json = ForyJsonScala.builder().withCodegen(codegen).withAsyncCompilation(false).build()
      for (size <- Seq(0, 1, 10, 20, 100, 1024, 1025)) {
        val expected = scala.collection.mutable.HashSet(
          (0 until size).map(i => if (i == 0) null else "值" + i): _*
        )
        val text = json.toJson(expected, setType)
        val first = json.fromJson(text.getBytes(UTF_8), setType)
        assert(first == expected)
        assert(json.fromJson(text, setType) == expected)
        assert(first.add("added"))
        assert(first.remove("added"))
        assert(first == expected)
      }
      val keys = (0 until 128).map { i =>
        (0 until 7).map(bit => if ((i & (1 << bit)) == 0) "Aa" else "BB").mkString
      }
      assert(keys.map(_.hashCode).distinct.size == 1)
      val expected = scala.collection.mutable.HashSet(keys: _*)
      val text = json.toJson(expected, setType)
      val duplicate = text.dropRight(1) + ",\"" + keys.head + "\"]"
      assert(json.fromJson(duplicate, setType) == expected)
      assert(json.fromJson(duplicate.getBytes(UTF_8), setType) == expected)
    }
  }

  test("mutable hash map growth") {
    val mapType = new TypeRef[scala.collection.mutable.Map[String, String]]() {}
    for (codegen <- Seq(false, true)) {
      val json = ForyJsonScala.builder().withCodegen(codegen).withAsyncCompilation(false).build()
      for (size <- Seq(0, 1, 10, 20, 100, 1024, 1025)) {
        val expected = scala.collection.mutable.HashMap(
          (0 until size).map(i => i.toString -> (if (i % 3 == 0) null else "值" + i)): _*
        )
        val text = json.toJson(expected, mapType)
        val first = json.fromJson(text.getBytes(UTF_8), mapType)
        assert(first == expected)
        assert(json.fromJson(text, mapType) == expected)
        first.update("added", "after reading")
        assert(first.remove("added").contains("after reading"))
        assert(first == expected)
      }
      val keys = (0 until 128).map { i =>
        (0 until 7).map(bit => if ((i & (1 << bit)) == 0) "Aa" else "BB").mkString
      }
      assert(keys.map(_.hashCode).distinct.size == 1)
      val expected = scala.collection.mutable.HashMap(keys.map(k => k -> k): _*)
      val text = json.toJson(expected, mapType)
      assert(json.fromJson(text, mapType) == expected)
      assert(json.fromJson(text.getBytes(UTF_8), mapType) == expected)
      val duplicate = text.dropRight(1) + ",\"" + keys.head + "\":null}"
      expected.update(keys.head, null)
      assert(json.fromJson(duplicate, mapType) == expected)
      assert(json.fromJson(duplicate.getBytes(UTF_8), mapType) == expected)
    }
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

  test("big integer representations") {
    val values = Seq(BigInt(0), BigInt(-1), BigInt(Long.MinValue), BigInt(Long.MaxValue),
      BigInt(Long.MinValue) - 1, BigInt(Long.MaxValue) + 1, BigInt(1) << 127, -(BigInt(1) << 256))
    for (codegen <- Seq(false, true)) {
      val json = ForyJsonScala.builder().withCodegen(codegen).build()
      for (value <- values; prefix <- Seq("", " \t\r\n"); quoted <- Seq(false, true)) {
        val number = value.toString
        val token = prefix + (if (quoted) "\"" + number + "\"" else number)
        assert(json.fromJson(token, classOf[BigInt]) == value)
        val fromBytes = json.fromJson(token.getBytes(UTF_8), classOf[BigInt])
        assert(fromBytes == value)
        assert(json.toJson(fromBytes) == number)
        assert(new String(json.toJsonBytes(fromBytes), UTF_8) == number)
        assert(json.toJson(value) == number)
        assert(new String(json.toJsonBytes(value), UTF_8) == number)
      }
      val fields = BigIntFields(values.last, values.toVector :+ null)
      val input = "{\"value\":" + fields.value + ",\"values\":" +
        values.mkString("[", ",", ",null]") + ",\"ignored\":\"\u0100\"}"
      // The non-Latin1 field forces the String input through the UTF16 reader.
      assert(json.fromJson(input, classOf[BigIntFields]) == fields)
      assert(json.fromJson(input.getBytes(UTF_8), classOf[BigIntFields]) == fields)
      assert(json.fromJson(json.toJson(fields), classOf[BigIntFields]) == fields)
      assert(json.fromJson(json.toJsonBytes(fields), classOf[BigIntFields]) == fields)
      val array = values.mkString("[", ",", "]")
      assert(json.fromJson(array, classOf[Array[BigInt]]).toSeq == values)
      assert(json.fromJson(array.getBytes(UTF_8), classOf[Array[BigInt]]).toSeq == values)
      assert(json.fromJson("  null", classOf[BigInt]) == null)
      assert(json.fromJson("  null".getBytes(UTF_8), classOf[BigInt]) == null)
      for (invalid <- Seq("1.0", "1e2", "\"1.0\"", "\"1e2\"", "-", "n")) {
        assertThrows[ForyJsonException](json.fromJson(invalid, classOf[BigInt]))
        assertThrows[ForyJsonException](json.fromJson(invalid.getBytes(UTF_8), classOf[BigInt]))
        assert(json.fromJson("1".getBytes(UTF_8), classOf[BigInt]) == BigInt(1))
      }
    }
    val ownerBytes = GraphMemoryEstimates.shallowObjectBytes(classOf[BigInt])
    val bounded = ForyJsonScala.builder().withMaxGraphMemoryBytes(ownerBytes - 1).build()
    assertThrows[ForyJsonException](bounded.fromJson("1", classOf[BigInt]))
    assertThrows[ForyJsonException](bounded.fromJson("1".getBytes(UTF_8), classOf[BigInt]))
    assert(bounded.fromJson("null", classOf[BigInt]) == null)
    val exact = ForyJsonScala.builder().withMaxGraphMemoryBytes(ownerBytes).build()
    assert(exact.fromJson("1", classOf[BigInt]) == BigInt(1))
  }

  test("bit set representations") {
    for (codegen <- Seq(false, true)) {
      val json = ForyJsonScala.builder().withCodegen(codegen).build()
      val cases = Seq(Seq.empty[Int], Seq(0), Seq(1, 63, 64, 130), Seq(130, 1, 64, 1), 0 until 1025)
      for (indices <- cases) {
        val immutable = scala.collection.immutable.BitSet(indices: _*)
        val mutable = scala.collection.mutable.BitSet(indices: _*)
        for (quoted <- Seq(false, true)) {
          val input = indices
            .map(i => if (quoted) "\"" + i + "\"" else i.toString)
            .mkString("[ ", " , ", " ]")
          assert(json.fromJson(input, classOf[scala.collection.immutable.BitSet]) == immutable)
          assert(
            json.fromJson(input.getBytes(UTF_8), classOf[scala.collection.immutable.BitSet]) == immutable
          )
          assert(json.fromJson(input, classOf[scala.collection.mutable.BitSet]) == mutable)
          assert(
            json.fromJson(input.getBytes(UTF_8), classOf[scala.collection.mutable.BitSet]) == mutable
          )
        }
        val expected = immutable.mkString("[", ",", "]")
        assert(json.toJson(immutable) == expected)
        assert(new String(json.toJsonBytes(immutable), UTF_8) == expected)
        assert(json.toJson(mutable) == expected)
        assert(new String(json.toJsonBytes(mutable), UTF_8) == expected)
      }
      for (input <- Seq("[-1]", "[2147483648]", "[1.5]", "[1e2]", "[100000000]", "[1,")) {
        assertThrows[ForyJsonException] {
          json.fromJson(input, classOf[scala.collection.immutable.BitSet])
        }
        assertThrows[ForyJsonException] {
          json.fromJson(input.getBytes(UTF_8), classOf[scala.collection.mutable.BitSet])
        }
        assert(
          json.fromJson("[1]".getBytes(UTF_8), classOf[scala.collection.immutable.BitSet]) ==
            scala.collection.immutable.BitSet(1)
        )
      }
      assert(json.fromJson("null", classOf[scala.collection.immutable.BitSet]) == null)
      assert(json.fromJson("null".getBytes(UTF_8), classOf[scala.collection.mutable.BitSet]) == null)
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

  private def runtimes: Seq[ForyJson] = Seq(
    ForyJsonScala.builder().withCodegen(false).build(),
    ForyJsonScala.builder().withAsyncCompilation(false).build()
  )

  private def quoted(text: String): String = '"'.toString + text + '"'

  private val durationType = classOf[Duration]
  private val finiteType = classOf[FiniteDuration]

  // AnyRefMap and Stream are deprecated in the standard library but still have module codecs.
  // Plain TypeRef subclasses keep the deprecation inside the annotated member on Scala 3.
  @nowarn("cat=deprecation")
  private val anyRefMapCase = (
    new TypeRef[scala.collection.mutable.AnyRefMap[String, Int]]() {},
    classOf[scala.collection.mutable.AnyRefMap[_, _]]
  )
  @nowarn("cat=deprecation")
  private def anyRefMap = scala.collection.mutable.AnyRefMap("a" -> 1)
  @nowarn("cat=deprecation")
  private val streamType = new TypeRef[Stream[Int]]() {}

  private def roundTrip[T](json: ForyJson, value: T, typeRef: TypeRef[T]): T = {
    val text = json.toJson(value, typeRef)
    val bytes = json.toJsonBytes(value, typeRef)
    assert(new String(bytes, UTF_8) == text)
    val fromText = json.fromJson(text, typeRef)
    val fromBytes = json.fromJson(bytes, typeRef)
    assert(fromText == fromBytes, text)
    fromText
  }


  test("BigDecimal") {
    val values = Seq(
      BigDecimal(0),
      BigDecimal("-1.5"),
      BigDecimal("1E-30"),
      BigDecimal("123456789012345678901234567890.123456789"),
      BigDecimal("1E+20")
    )
    for (json <- runtimes; value <- values) {
      val text = json.toJson(value)
      assert(json.fromJson(text, classOf[BigDecimal]) == value, text)
      assert(json.fromJson(text.getBytes(UTF_8), classOf[BigDecimal]) == value, text)
      assert(json.fromJson(quoted(text), classOf[BigDecimal]) == value, text)
      assert(json.fromJson(" \n" + text, classOf[BigDecimal]) == value, text)
    }
    val json = runtimes.head
    assert(json.fromJson("null", classOf[BigDecimal]) == null)
    assert(json.toJson(null.asInstanceOf[BigDecimal]) == "null")
    for (invalid <- Seq("-", "n", quoted("x"), "[1]", "1.2.3")) {
      assertThrows[ForyJsonException](json.fromJson(invalid, classOf[BigDecimal]))
      assertThrows[ForyJsonException](json.fromJson(invalid.getBytes(UTF_8), classOf[BigDecimal]))
    }
    val ownerBytes = GraphMemoryEstimates.shallowObjectBytes(classOf[BigDecimal])
    val bounded = ForyJsonScala.builder().withMaxGraphMemoryBytes(ownerBytes - 1).build()
    assertThrows[ForyJsonException](bounded.fromJson("1.5", classOf[BigDecimal]))
    assert(bounded.fromJson("null", classOf[BigDecimal]) == null)
  }

  test("StringBuilder") {
    val builderType = classOf[scala.collection.mutable.StringBuilder]
    for (json <- runtimes; text <- Seq("", "abc", """中文 "quoted" """ + "\n")) {
      val value = new scala.collection.mutable.StringBuilder(text)
      val encoded = json.toJson(value)
      assert(new String(json.toJsonBytes(value), UTF_8) == encoded)
      assert(json.fromJson(encoded, builderType).toString == text)
      assert(json.fromJson(encoded.getBytes(UTF_8), builderType).toString == text)
    }
    val json = runtimes.head
    assert(json.fromJson("null", builderType) == null)
    assertThrows[ForyJsonException](json.fromJson("1", builderType))
    val ownerBytes =
      GraphMemoryEstimates.shallowObjectBytes(builderType) +
        GraphMemoryEstimates.shallowObjectBytes(classOf[java.lang.StringBuilder]) +
        GraphMemoryEstimates.objectArrayBytes() + (3 + 16) * Character.BYTES
    val bounded = ForyJsonScala.builder().withMaxGraphMemoryBytes(ownerBytes - 1).build()
    assertThrows[ForyJsonException](bounded.fromJson(quoted("abc"), builderType))
    val exact = ForyJsonScala.builder().withMaxGraphMemoryBytes(ownerBytes).build()
    assert(exact.fromJson(quoted("abc"), builderType).toString == "abc")
  }


  test("Duration specials and invalid objects") {
    for (json <- runtimes) {
      assert(json.toJson(Duration.Inf, durationType) == """{"special":"INF"}""")
      assert(json.toJson(Duration.MinusInf, durationType) == """{"special":"MINUS_INF"}""")
      assert(json.fromJson("""{"special":"INF"}""", durationType) eq Duration.Inf)
      assert(json.fromJson("""{"special":"MINUS_INF"}""".getBytes(UTF_8), durationType) eq Duration.MinusInf)
      assert(json.fromJson("""{"special":"UNDEFINED"}""", durationType) eq Duration.Undefined)
      assertThrows[ForyJsonException](json.fromJson("""{"special":"INF"}""", finiteType))
      assertThrows[ForyJsonException](json.fromJson("""{"special":"NAN"}""", durationType))

      for (unit <- TimeUnit.values()) {
        val value = FiniteDuration(3, unit)
        val text = json.toJson(value, finiteType)
        assert(text == s"""{"length":3,"unit":"${unit.name}"}""", text)
        assert(json.fromJson(text, finiteType) == value)
        assert(json.fromJson(text, durationType) == value)
      }
      val extreme = FiniteDuration(Long.MaxValue, TimeUnit.NANOSECONDS)
      assert(json.fromJson(json.toJson(extreme, finiteType), finiteType) == extreme)
      assert(json.fromJson("null", finiteType) == null)
      assert(json.toJson(null.asInstanceOf[Duration], durationType) == "null")

      for (
        invalid <- Seq(
          "{}",
          """{"length":1}""",
          """{"unit":"SECONDS"}""",
          """{"length":1,"unit":"FORTNIGHTS"}""",
          """{"length":1,"length":2,"unit":"SECONDS"}""",
          """{"length":1,"unit":"SECONDS","special":"INF"}""",
          """{"length":1,"unit":"SECONDS","extra":0}""",
          """{"length":"x","unit":"SECONDS"}""",
          """{"length":1,"unit":1}""",
          """[1,"SECONDS"]"""
        )
      ) {
        assertThrows[ForyJsonException](json.fromJson(invalid, durationType))
        assertThrows[ForyJsonException](json.fromJson(invalid.getBytes(UTF_8), finiteType))
      }
    }
  }

  test("Range shapes") {
    val inclusiveType = classOf[Range.Inclusive]
    val exclusiveType = classOf[Range.Exclusive]
    for (json <- runtimes) {
      assert(json.toJson(Range.inclusive(1, 0)) == "[]")
      assert(json.fromJson("[]", classOf[Range]).isEmpty)
      assert(json.fromJson("[]", inclusiveType).isEmpty)
      assert(json.fromJson("[]", exclusiveType).isEmpty)
      assert(json.fromJson("[5]", inclusiveType).toList == List(5))
      assert(json.fromJson("[5]", exclusiveType).toList == List(5))
      assert(json.fromJson("[9,7,5]", inclusiveType).toList == List(9, 7, 5))
      assert(json.fromJson("[9,7,5]".getBytes(UTF_8), exclusiveType).toList == List(9, 7, 5))
      assert(json.fromJson("[-3,-1,1]", classOf[Range]).toList == List(-3, -1, 1))
      val negative = Range(10, 0, -3)
      assert(json.fromJson(json.toJson(negative), classOf[Range]).toList == negative.toList)
      assert(json.fromJson("null", classOf[Range]) == null)
      assert(json.toJson(null.asInstanceOf[Range]) == "null")
      for (
        invalid <- Seq(
          "[1,2,4]",
          "[1,1]",
          "[1,2,3,3]",
          "[2147483647]",
          "[2147483646,2147483647]",
          "[-2147483648,2147483647]",
          "[1,",
          "[1.5]",
          "{}"
        )
      ) {
        assertThrows[ForyJsonException](json.fromJson(invalid, exclusiveType))
      }
      assert(json.fromJson("[2147483647]", inclusiveType).toList == List(Int.MaxValue))
      assertThrows[ForyJsonException](json.fromJson("[1,2,4]", inclusiveType))
      assertThrows[ForyJsonException](json.fromJson("[1,1]", inclusiveType))
      assertThrows[ForyJsonException](json.fromJson("[-2147483648,2147483647]", inclusiveType))
    }
  }

  test("NumericRange element types") {
    val json = runtimes.head
    val byteType = ScalaTypeRef[NumericRange[Byte]]
    val shortType = ScalaTypeRef[NumericRange[Short]]
    val charType = ScalaTypeRef[NumericRange[Char]]
    val bigType = ScalaTypeRef[NumericRange[BigInt]]
    val longType = ScalaTypeRef[NumericRange[Long]]
    val doubleType = ScalaTypeRef[NumericRange[Double]]

    val bytes = NumericRange.inclusive[Byte](1, 5, 2)
    assert(json.toJson(bytes, byteType) == "[1,3,5]")
    assert(json.fromJson("[1,3,5]", byteType) == bytes)
    val shorts = NumericRange[Short](10, 0, -5)
    assert(json.fromJson(json.toJson(shorts, shortType), shortType) == shorts)
    val chars: NumericRange[Char] = NumericRange.inclusive('a', 'e', 2)
    val charText = json.toJson(chars, charType)
    assert(json.fromJson(charText, charType) == chars, charText)
    val bigs = NumericRange.inclusive(BigInt(1) << 70, (BigInt(1) << 70) + 4, BigInt(2))
    assert(json.fromJson(json.toJson(bigs, bigType), bigType) == bigs)
    assert(json.fromJson("[]", longType).isEmpty)
    assert(json.fromJson("[7]", longType) == NumericRange.inclusive(7L, 7L, 1L))

    assertThrows[ForyJsonException](json.fromJson("[127,128]", byteType))
    assertThrows[ForyJsonException](json.fromJson("[-128,127]", byteType))
    assertThrows[ForyJsonException](json.fromJson("[1,2,4]", longType))
    assertThrows[ForyJsonException](json.fromJson("[1,1]", longType))
    assertThrows[ForyJsonException](json.fromJson("[9223372036854775806,9223372036854775807]", ScalaTypeRef[NumericRange.Exclusive[Long]]))
    assertThrows[UnsupportedJsonTypeException](json.fromJson("[1.0]", doubleType))
    assertThrows[ForyJsonException](json.fromJson("[1,2]", classOf[NumericRange[_]]))
    assert(json.toJson(NumericRange.inclusive(1L, 3L, 1L)) == "[1,2,3]")
  }


  test("declared collection kinds") {
    import scala.collection.{immutable => im, mutable => mu}
    val cases: Seq[(TypeRef[_ <: scala.collection.Iterable[Int]], Class[_])] = Seq(
      (ScalaTypeRef[mu.ArrayBuffer[Int]], classOf[mu.ArrayBuffer[_]]),
      (ScalaTypeRef[mu.ListBuffer[Int]], classOf[mu.ListBuffer[_]]),
      (ScalaTypeRef[mu.ArrayDeque[Int]], classOf[mu.ArrayDeque[_]]),
      (ScalaTypeRef[mu.Queue[Int]], classOf[mu.Queue[_]]),
      (ScalaTypeRef[mu.LinkedHashSet[Int]], classOf[mu.LinkedHashSet[_]]),
      (ScalaTypeRef[im.IndexedSeq[Int]], classOf[Vector[_]]),
      (ScalaTypeRef[scala.collection.IndexedSeq[Int]], classOf[Vector[_]]),
      (ScalaTypeRef[scala.collection.LinearSeq[Int]], classOf[im.::[_]]),
      (ScalaTypeRef[im.LinearSeq[Int]], classOf[im.::[_]]),
      (ScalaTypeRef[mu.Buffer[Int]], classOf[mu.ArrayBuffer[_]]),
      (ScalaTypeRef[mu.Seq[Int]], classOf[mu.ArrayBuffer[_]]),
      (ScalaTypeRef[mu.IndexedSeq[Int]], classOf[mu.ArrayBuffer[_]]),
      (ScalaTypeRef[mu.Iterable[Int]], classOf[mu.ArrayBuffer[_]]),
      (ScalaTypeRef[scala.collection.Set[Int]], classOf[im.HashSet[_]])
    )
    for (json <- runtimes; (typeRef, runtimeClass) <- cases) {
      val anyRef = typeRef.asInstanceOf[TypeRef[scala.collection.Iterable[Int]]]
      val decoded = json.fromJson("[3,1,2]", anyRef)
      assert(runtimeClass.isInstance(decoded), s"$typeRef -> ${decoded.getClass}")
      assert(decoded.toSet == Set(1, 2, 3), s"$typeRef")
      if (!decoded.isInstanceOf[scala.collection.Set[_]]) assert(decoded.toList == List(3, 1, 2))
      assert(json.fromJson("[]", anyRef).isEmpty)
      val encoded = json.toJson(decoded, anyRef)
      assert(json.fromJson(encoded.getBytes(UTF_8), anyRef) == decoded, s"$typeRef")
      assert(json.fromJson("null", anyRef) == null)
    }
    val json = runtimes.head
    val linked = json.fromJson("[3,1,2,1]", ScalaTypeRef[mu.LinkedHashSet[Int]])
    assert(linked.toList == List(3, 1, 2))
    assert(json.toJson(linked) == "[3,1,2]")
    val nilType = ScalaTypeRef[Nil.type]
    assert(json.fromJson("[]", nilType) eq Nil)
    assert(json.toJson(Nil) == "[]")
    assertThrows[ForyJsonException](json.fromJson("[1]", nilType))
    val consType = ScalaTypeRef[im.::[Int]]
    assert(json.fromJson("[1,2]", consType) == List(1, 2))
    assertThrows[ForyJsonException](json.fromJson("[]", consType))
  }

  test("declared map kinds") {
    import scala.collection.{immutable => im, mutable => mu}
    val cases: Seq[(TypeRef[_ <: scala.collection.Map[String, Int]], Class[_])] = Seq(
      (ScalaTypeRef[im.VectorMap[String, Int]], classOf[im.VectorMap[_, _]]),
      (ScalaTypeRef[im.ListMap[String, Int]], classOf[im.ListMap[_, _]]),
      (ScalaTypeRef[im.SeqMap[String, Int]], classOf[im.VectorMap[_, _]]),
      (ScalaTypeRef[mu.SeqMap[String, Int]], classOf[mu.LinkedHashMap[_, _]]),
      anyRefMapCase,
      (ScalaTypeRef[mu.HashMap[String, Int]], classOf[mu.HashMap[_, _]]),
      (ScalaTypeRef[im.HashMap[String, Int]], classOf[im.HashMap[_, _]])
    )
    for (json <- runtimes; (typeRef, runtimeClass) <- cases) {
      val anyRef = typeRef.asInstanceOf[TypeRef[scala.collection.Map[String, Int]]]
      val decoded = json.fromJson("""{"c":3,"a":1,"b":2}""", anyRef)
      assert(runtimeClass.isInstance(decoded), s"$typeRef -> ${decoded.getClass}")
      assert(decoded == Map("a" -> 1, "b" -> 2, "c" -> 3), s"$typeRef")
      if (decoded.isInstanceOf[scala.collection.SeqMap[_, _]]) {
        assert(decoded.keys.toList == List("c", "a", "b"), s"$typeRef")
        assert(json.toJson(decoded, anyRef) == """{"c":3,"a":1,"b":2}""")
      }
      assert(json.fromJson("{}", anyRef).isEmpty)
      val encoded = json.toJson(decoded, anyRef)
      assert(json.fromJson(encoded.getBytes(UTF_8), anyRef) == decoded, s"$typeRef")
      assert(json.fromJson("null", anyRef) == null)
      assertThrows[ForyJsonException](json.fromJson("[1]", anyRef))
    }
    val json = runtimes.head
    val intKeyType = ScalaTypeRef[Map[Int, String]]
    assert(json.toJson(Map(1 -> "a", 2 -> "b"), intKeyType) == """{"1":"a","2":"b"}""")
    assert(json.fromJson("""{"1":"a","2":"b"}""", intKeyType) == Map(1 -> "a", 2 -> "b"))
    assertThrows[ForyJsonException](json.fromJson("""{"x":"a"}""", intKeyType))
    val nestedType = ScalaTypeRef[Map[String, List[Option[Int]]]]
    val nested = Map("a" -> List(Some(1), None), "b" -> Nil)
    assert(roundTrip(json, nested, nestedType) == nested)
    val listListType = ScalaTypeRef[List[List[Int]]]
    assert(roundTrip(json, List(List(1), Nil, List(2, 3)), listListType) == List(List(1), Nil, List(2, 3)))
    val nullableList = ScalaTypeRef[List[String]]
    assert(roundTrip(json, List("a", null, "b"), nullableList) == List("a", null, "b"))
  }

  test("runtime collection writes and unsupported families") {
    import scala.collection.{immutable => im, mutable => mu}
    val json = runtimes.head
    assert(json.toJson(mu.ArrayBuffer(1, 2)) == "[1,2]")
    assert(json.toJson(mu.ListBuffer(1, 2)) == "[1,2]")
    assert(json.toJson(mu.ArrayDeque(1, 2)) == "[1,2]")
    assert(json.toJson(mu.Queue(1, 2)) == "[1,2]")
    assert(json.toJson(mu.LinkedHashSet(2, 1)) == "[2,1]")
    assert(json.toJson(im.VectorMap("b" -> 1, "a" -> 2)) == """{"b":1,"a":2}""")
    assert(json.toJson(im.ListMap("b" -> 1, "a" -> 2)) == """{"b":1,"a":2}""")
    assert(json.toJson(anyRefMap) == """{"a":1}""")
    assert(json.toJson(mu.LinkedHashMap("b" -> 1, "a" -> 2)) == """{"b":1,"a":2}""")
    // Five entries select the HashMap runtime class rather than Map1..Map4.
    val hashed = im.Map("a" -> 1, "b" -> 2, "c" -> 3, "d" -> 4, "e" -> 5)
    assert(json.fromJson(json.toJson(hashed), ScalaTypeRef[Map[String, Int]]) == hashed)

    assertThrows[ForyJsonException](json.toJson(im.TreeSet(1, 2)))
    assertThrows[ForyJsonException](json.toJson(im.TreeMap("a" -> 1)))
    assertThrows[ForyJsonException](json.toJson(mu.TreeSet(1, 2)))
    assertThrows[ForyJsonException](json.fromJson("[1]", ScalaTypeRef[im.TreeSet[Int]]))
    assertThrows[ForyJsonException](json.fromJson("""{"a":1}""", ScalaTypeRef[im.SortedMap[String, Int]]))
    assertThrows[ForyJsonException](json.fromJson("[1]", ScalaTypeRef[im.SortedSet[Int]]))
  }

  test("large List and Vector batches") {
    for (json <- runtimes; size <- Seq(1023, 1024, 1025, 2049)) {
      val list = (0 until size).toList
      val listType = ScalaTypeRef[List[Int]]
      assert(roundTrip(json, list, listType) == list)
      val vector = list.toVector
      val vectorType = ScalaTypeRef[Vector[Int]]
      assert(roundTrip(json, vector, vectorType) == vector)
      val strings = list.map(i => "v" + i)
      val stringListType = ScalaTypeRef[List[String]]
      assert(roundTrip(json, strings, stringListType) == strings)
    }
    val bounded = ForyJsonScala.builder().withMaxGraphMemoryBytes(4096).build()
    val big = (0 until 4096).mkString("[", ",", "]")
    assertThrows[ForyJsonException](bounded.fromJson(big, ScalaTypeRef[List[Int]]))
  }


  test("rejected runtime-state families") {
    val json = runtimes.head
    val roots: Seq[TypeRef[_]] = Seq(
      streamType,
      ScalaTypeRef[Iterator[Int]],
      ScalaTypeRef[scala.collection.View[Int]],
      ScalaTypeRef[scala.util.Try[Int]],
      ScalaTypeRef[scala.util.Success[Int]],
      ScalaTypeRef[scala.util.Failure[Int]],
      ScalaTypeRef[scala.concurrent.Future[Int]],
      ScalaTypeRef[scala.concurrent.Promise[Int]],
      new TypeRef[scala.concurrent.duration.Deadline]() {},
      new TypeRef[scala.util.matching.Regex]() {},
      new TypeRef[Symbol]() {},
      ScalaTypeRef[Int => Int],
      ScalaTypeRef[PartialFunction[Int, Int]],
      ScalaTypeRef[Ordering[Int]]
    )
    for (typeRef <- roots) {
      val error = intercept[UnsupportedJsonTypeException] {
        json.fromJson("null", typeRef.asInstanceOf[TypeRef[AnyRef]])
      }
      assert(error.getMessage.contains("runtime-state or lazy type"), s"$typeRef: ${error.getMessage}")
    }
    assertThrows[UnsupportedJsonTypeException](json.toJson(scala.util.Success(1)))
    assertThrows[UnsupportedJsonTypeException](json.toJson(Symbol("x")))
    assertThrows[UnsupportedJsonTypeException](json.toJson("a.*".r))
    assertThrows[UnsupportedJsonTypeException](json.toJson(LazyItems(LazyList(1))))
    assertThrows[UnsupportedJsonTypeException](json.toJson(TriedValue(scala.util.Success(1))))
    assertThrows[UnsupportedJsonTypeException](json.toJson(Transform(identity)))
    assertThrows[UnsupportedJsonTypeException](json.fromJson("""{"items":[1]}""", classOf[LazyItems]))
  }


  test("Option shapes") {
    for (json <- runtimes) {
      assert(json.toJson(Optionals(Some(1))) == """{"a":1,"b":null,"c":null}""")
      assert(json.fromJson("""{"a":1}""", classOf[Optionals]) == Optionals(Some(1)))
      assert(json.fromJson("""{"a":null}""", classOf[Optionals]) == Optionals(None))
      assertThrows[ForyJsonException](json.fromJson("{}", classOf[Optionals]))
      assert(
        json.fromJson("""{"a":1,"b":"x","c":2}""", classOf[Optionals]) ==
          Optionals(Some(1), Some("x"), Some(Some(2)))
      )
      // Some(None) is written as null and therefore decodes as None; this pins that shape.
      assert(json.toJson(Optionals(None, None, Some(None))) == """{"a":null,"b":null,"c":null}""")
      val nestedType = ScalaTypeRef[Option[Option[Int]]]
      assert(json.toJson(Some(None): Option[Option[Int]], nestedType) == "null")
      assert(json.fromJson("null", nestedType) == None)
      assert(json.fromJson("3", nestedType) == Some(Some(3)))
      val stringOption = ScalaTypeRef[Option[String]]
      assert(json.toJson(Some(null): Option[String], stringOption) == "null")
      assert(json.fromJson(quoted("中").getBytes(UTF_8), stringOption) == Some("中"))
    }
  }

  test("tuple shapes") {
    for (json <- runtimes) {
      val tuple1Type = ScalaTypeRef[Tuple1[String]]
      assert(json.toJson(Tuple1("a"), tuple1Type) == """["a"]""")
      assert(json.fromJson("""["a"]""", tuple1Type) == Tuple1("a"))
      assert(json.fromJson("""["中"]""".getBytes(UTF_8), tuple1Type) == Tuple1("中"))
      assert(json.fromJson("""["中"]""", tuple1Type) == Tuple1("中"))
      assertThrows[ForyJsonException](json.fromJson("[]", tuple1Type))
      assertThrows[ForyJsonException](json.fromJson("""["a","b"]""", tuple1Type))

      val pairType = ScalaTypeRef[(Int, String)]
      assertThrows[ForyJsonException](json.fromJson("""[1,"a",2]""", pairType))
      assertThrows[ForyJsonException](json.fromJson("""["a",1]""", pairType))
      assertThrows[ForyJsonException](json.fromJson("""{"_1":1}""", pairType))
      assert(json.fromJson("null", pairType) == null)
      assert(json.toJson(null.asInstanceOf[(Int, String)], pairType) == "null")
      assert(json.fromJson("[1,null]", pairType) == ((1, null)))
      assert(json.toJson((1, null): (Int, String), pairType) == "[1,null]")
      assert(json.fromJson("""[1,"中"]""".getBytes(UTF_8), pairType) == ((1, "中")))
      assert(json.fromJson("""[1,"中"]""", pairType) == ((1, "中")))

      val nestedType = ScalaTypeRef[((Int, String), List[(String, Option[Int])])]
      val nested = ((1, "z"), List(("a", Some(1)), ("b", None)))
      assert(roundTrip(json, nested, nestedType) == nested)

      val tuple3Type = ScalaTypeRef[(Int, String, Boolean)]
      val value = (1, "a", true)
      assert(roundTrip(json, value, tuple3Type) == value)
    }
  }

  test("value class shapes") {
    for (json <- runtimes) {
      assert(json.toJson(Label("x")) == quoted("x"))
      assert(json.fromJson(quoted("x"), classOf[Label]) == Label("x"))
      assert(json.fromJson(quoted("中").getBytes(UTF_8), classOf[Label]) == Label("中"))
      assert(json.fromJson("null", classOf[Label].asInstanceOf[Class[AnyRef]]) == null)
      assertThrows[ForyJsonException](json.fromJson("1", classOf[Label]))
      val listType = ScalaTypeRef[List[Label]]
      assert(roundTrip(json, List(Label("a"), Label("b")), listType) == List(Label("a"), Label("b")))
      val mapType = ScalaTypeRef[Map[String, Label]]
      assert(roundTrip(json, Map("k" -> Label("v")), mapType) == Map("k" -> Label("v")))
      val optionType = ScalaTypeRef[Option[Label]]
      assert(roundTrip(json, Some(Label("v")): Option[Label], optionType) == Some(Label("v")))
    }
  }

  test("Unit and singletons") {
    for (json <- runtimes) {
      val unitType = classOf[scala.runtime.BoxedUnit]
      assert(json.toJson(scala.runtime.BoxedUnit.UNIT, unitType) == "null")
      assert(json.fromJson("null", unitType) != null)
      assertThrows[ForyJsonException](json.fromJson("1", unitType))
      assertThrows[ForyJsonException](json.fromJson("""{"value":1}""", classOf[UnitValue]))

      assert(json.toJson(Marker) == "{}")
      assert(json.fromJson("{}", Marker.getClass) eq Marker)
      assert(json.fromJson("{}".getBytes(UTF_8), Marker.getClass) eq Marker)
      assertThrows[ForyJsonException](json.fromJson("""{"x":1}""", Marker.getClass))
      assertThrows[ForyJsonException](json.fromJson("[]", Marker.getClass))
      assert(json.toJson(Outer.Inner) == "{}")
      assert(json.fromJson("{}", Outer.Inner.getClass) eq Outer.Inner)
      assert(json.fromJson("null", Marker.getClass) == null)
    }
  }


  test("generic and wide case classes") {
    for (json <- runtimes) {
      val erasedIntBox = new TypeRef[Box[Int]]() {}
      assert(json.toJson(Box(1), erasedIntBox) == """{"value":1}""")
      assert(json.fromJson("""{"value":1}""", erasedIntBox) == Box(1))
      val holder = Boxes(Box(2), Box("s"))
      assert(json.toJson(holder) == """{"ints":{"value":2},"strings":{"value":"s"}}""")
      assert(json.fromJson(json.toJson(holder), classOf[Boxes]) == holder)
      val listBox = ScalaTypeRef[Box[List[String]]]
      assert(roundTrip(json, Box(List("a", "中")), listBox) == Box(List("a", "中")))

      val vars = VarParams(1)
      vars.b = "changed"
      assert(json.toJson(vars) == """{"a":1,"b":"changed"}""")
      assert(json.fromJson("""{"a":2}""", classOf[VarParams]) == VarParams(2))
      assert(json.fromJson("""{"a":2,"b":"z"}""", classOf[VarParams]) == VarParams(2, "z"))

      val wide = Wide(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23)
      val text = json.toJson(wide)
      assert(text.contains(quoted("f24") + ":24"), text)
      assert(json.fromJson(text, classOf[Wide]) == wide)
      assert(json.fromJson(text.getBytes(UTF_8), classOf[Wide]) == wide)
      val withoutDefault = text.replace(""","f24":24""", "")
      assert(json.fromJson(withoutDefault, classOf[Wide]) == wide)
      val missing = intercept[ForyJsonException](json.fromJson("""{"f1":1}""", classOf[Wide]))
      assert(missing.getMessage != null)

      // Unknown properties are skipped for case classes like other object codecs.
      assert(json.fromJson("""{"a":2,"zzz":{"deep":[1,2]}}""", classOf[VarParams]) == VarParams(2))
    }
    val shallow = ForyJsonScala.builder().withCodegen(false).maxDepth(2).build()
    assertThrows[ForyJsonException](shallow.fromJson("""{"value":1,"next":{"value":2,"next":{"value":3,"next":null}}}""", classOf[Node]))
    assert(shallow.fromJson("""{"value":1,"next":null}""", classOf[Node]) == Node(1, None))
  }

  test("explicit sealed hierarchy subtypes") {
    for (json <- runtimes) {
      val shapes = Shapes(List(Circle(1.5), Dot))
      val text = json.toJson(shapes)
      assert(text == """{"shapes":[{"kind":"circle","radius":1.5},{"kind":"dot"}]}""", text)
      val decoded = json.fromJson(text, classOf[Shapes])
      assert(decoded == shapes)
      assert(decoded.shapes(1).asInstanceOf[AnyRef] eq Dot)
      assert(json.fromJson(text.getBytes(UTF_8), classOf[Shapes]) == shapes)
      val single = json.toJson(Circle(2.0): Shape, classOf[Shape])
      assert(single == """{"kind":"circle","radius":2.0}""", single)
      assert(json.fromJson(single, classOf[Shape]) == Circle(2.0))
      assertThrows[ForyJsonException](json.fromJson("""{"kind":"square"}""", classOf[Shape]))
      assertThrows[ForyJsonException](json.fromJson("""{"radius":1.0}""", classOf[Shape]))
    }
  }


  test("Enumeration codec registered directly") {
    val codec = new HueCodec
    for (
      json <- Seq(
        ForyJsonScala.builder().withCodegen(false).registerCodec(classOf[Hue.Value].asInstanceOf[Class[Enumeration#Value]], codec).build(),
        ForyJsonScala.builder().withAsyncCompilation(false).registerCodec(classOf[Hue.Value].asInstanceOf[Class[Enumeration#Value]], codec).build()
      )
    ) {
      assert(json.toJson(Hue.Red, classOf[Hue.Value]) == quoted("red"))
      assert(json.fromJson(quoted("green"), classOf[Hue.Value]) eq Hue.Green)
      assert(json.fromJson(quoted("green").getBytes(UTF_8), classOf[Hue.Value]) eq Hue.Green)
      assertThrows[ForyJsonException](json.fromJson(quoted("Green"), classOf[Hue.Value]))
      assertThrows[ForyJsonException](json.fromJson(quoted("blue"), classOf[Hue.Value]))
      assertThrows[ForyJsonException](json.fromJson("1", classOf[Hue.Value]))
      assert(json.fromJson("null", classOf[Hue.Value]) == null)
      val listType = ScalaTypeRef[List[Hue.Value]]
      assert(json.fromJson("""["red","green"]""", listType) == List(Hue.Red, Hue.Green))
    }
    assertThrows[IllegalArgumentException] {
      object Duplicate extends Enumeration {
        val A = Value("same")
        val B = Value("same")
      }
      new ScalaEnumerationCodec(Duplicate) {}
    }
  }


  test("scalar families as case-class fields with codegen") {
    val value = ScalarFields(
      BigDecimal("1.25"),
      new scala.collection.mutable.StringBuilder("sb"),
      Duration.Inf,
      FiniteDuration(2, TimeUnit.SECONDS),
      Range.inclusive(1, 5, 2),
      (1, "中"),
      Label("n"),
      List(Label("a"), Label("b")),
      scala.collection.mutable.ArrayBuffer("x", "y"),
      scala.collection.immutable.ListMap("z" -> 1, "y" -> 2),
      scala.collection.mutable.LinkedHashSet(3, 1, 2)
    )
    for (json <- runtimes) {
      val text = json.toJson(value)
      assert(new String(json.toJsonBytes(value), UTF_8) == text)
      for (decoded <- Seq(json.fromJson(text, classOf[ScalarFields]), json.fromJson(text.getBytes(UTF_8), classOf[ScalarFields]))) {
        assert(decoded.decimal == value.decimal)
        assert(decoded.builder.toString == "sb")
        assert(decoded.duration eq Duration.Inf)
        assert(decoded.finite == value.finite)
        assert(decoded.range.toList == value.range.toList)
        assert(decoded.pair == value.pair)
        assert(decoded.name == value.name)
        assert(decoded.names == value.names)
        assert(decoded.buffer == value.buffer)
        assert(decoded.listMap.toList == value.listMap.toList)
        assert(decoded.linkedSet.toList == value.linkedSet.toList)
      }
    }
  }
}

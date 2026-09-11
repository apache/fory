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
import java.util.concurrent.atomic.AtomicLong

import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.annotation.{JsonIgnore, JsonProperty, JsonUnwrapped}
import org.apache.fory.json.codec.{AbstractJsonValueCodec, MapKeyCodec}
import org.apache.fory.json.reader.JsonReader
import org.apache.fory.json.resolver.UnsupportedJsonTypeException
import org.apache.fory.json.writer.JsonWriter
import org.apache.fory.reflect.TypeRef
import org.apache.fory.serializer.GraphMemoryEstimates
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
      for (size <- Seq(1023, 1024, 1025)) {
        val value = scala.collection.mutable.LongMap((0 until size).map(i => i.toLong -> i.toString): _*)
        val text = json.toJson(value, mapType)
        assert(json.fromJson(text, mapType) == value)
        assert(json.fromJson(text.getBytes(UTF_8), mapType) == value)
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
      assert(json.fromJson(text, classOf[LongMapCodecSlots]) == value)
      assert(json.fromJson(text.getBytes(UTF_8), classOf[LongMapCodecSlots]) == value)
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
}

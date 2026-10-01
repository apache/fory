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

import org.apache.fory.json.{ForyJson, ForyJsonException, JsonCodecFactory}
import org.apache.fory.json.annotation.{JsonByteArray, JsonCodec, JsonFormat, JsonIgnore, JsonInclude, JsonMixin, JsonProperty, JsonRawValue, JsonSubTypes, JsonUnwrapped}
import org.apache.fory.json.codec.{AbstractJsonValueCodec, MapKeyCodec, ObjectCodec}
import org.apache.fory.json.reader.JsonReader
import org.apache.fory.json.resolver.{JsonTypeResolver, UnsupportedJsonTypeException}
import org.apache.fory.json.writer.JsonWriter
import org.apache.fory.reflect.{ReflectionUtils, TypeRef}
import org.apache.fory.serializer.GraphMemoryEstimates
import org.scalatest.funsuite.AnyFunSuite

import scala.annotation.nowarn
import scala.collection.immutable.NumericRange
import scala.concurrent.duration.{Duration, FiniteDuration}

case class Node(value: Int, next: Option[Node])

case class BigIntFields(value: BigInt, values: Vector[BigInt])

case class BinaryFields(
    label: String,
    bytes: Array[Byte],
    nested: List[Array[Byte]],
    mapped: Map[String, Array[Byte]],
    optional: Option[Array[Byte]]
)

case class StringScalarFields(
    @JsonFormat(shape = JsonFormat.Shape.STRING) active: Boolean,
    @JsonFormat(shape = JsonFormat.Shape.STRING) count: Int,
    @JsonFormat(shape = JsonFormat.Shape.STRING) total: BigInt,
    @JsonFormat(shape = JsonFormat.Shape.STRING) fraction: BigDecimal,
    label: String
)

case class ArtifactState(expired: Boolean, label: String)

@JsonMixin(target = classOf[ArtifactState])
abstract class ArtifactStateMixin {
  @JsonFormat(shape = JsonFormat.Shape.STRING) var expired: Boolean = false
}

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

case class NullContents(
    @JsonProperty(onContentNullRead = JsonProperty.NullHandling.SKIP)
    values: List[List[String]]
)

case class NullNode(
    value: Int,
    @JsonProperty(onContentNullRead = JsonProperty.NullHandling.SKIP) children: List[NullNode]
)

case class OpaqueNullContents(
    @JsonProperty(onContentNullRead = JsonProperty.NullHandling.SET)
    @JsonCodec(classOf[NullListCodec]) values: List[String]
)

final class NullListCodec extends AbstractJsonValueCodec[List[String]] {
  override def write(writer: JsonWriter, value: List[String]): Unit = writer.writeString("list")
  override def read(reader: JsonReader): List[String] = List(reader.readString())
}

@JsonInclude(JsonProperty.Include.NON_DEFAULT)
case class CurriedDefault(a: Int)(
    @JsonProperty(include = JsonProperty.Include.NON_DEFAULT) val b: Int = a + 1
)

case class KeywordDefault(switch: Int)(
    @JsonProperty(include = JsonProperty.Include.NON_DEFAULT) val high: Int = switch + 1
)

@JsonInclude(JsonProperty.Include.NON_DEFAULT)
case class InclusionDefaults(
    required: Int,
    flag: Boolean,
    optional: Option[Int],
    number: Int = 2,
    currency: String = "USD",
    selected: Option[Int] = Some(7),
    values: List[Int] = List(1),
    text: String = null
)

case class EmptyScalaFields(
    option: Option[String],
    list: List[Int],
    vector: Vector[Int],
    set: Set[Int],
    map: Map[String, Int],
    buffer: scala.collection.mutable.ArrayBuffer[Int],
    none: None.type,
    dynamic: Any
)

case class EmptyScalaRanges[T](
    bits: scala.collection.immutable.BitSet,
    mutableBits: scala.collection.mutable.BitSet,
    range: Range,
    numeric: NumericRange[T]
)

class OptionTextCodec extends AbstractJsonValueCodec[Option[String]] {
  override def write(writer: JsonWriter, value: Option[String]): Unit = writer.writeString("custom")
  override def read(reader: JsonReader): Option[String] = Option(reader.readString())
}

final class EmptyOptionTextCodec extends OptionTextCodec {
  override def isEmpty(writer: JsonWriter, value: Option[String]): Boolean = value.isEmpty
}

case class CustomEmptyOptions(
    @JsonCodec(classOf[OptionTextCodec]) retained: Option[String],
    @JsonCodec(classOf[EmptyOptionTextCodec]) omitted: Option[String]
)

@JsonInclude(JsonProperty.Include.NON_DEFAULT)
case class ArrayDefaults(
    ints: Array[Int] = Array(1, 2),
    nested: Array[Array[Int]] = Array(Array(3)),
    fraction: Double = 0.0,
    single: Float = 0.0f
)

@JsonInclude(JsonProperty.Include.NON_DEFAULT)
case class RetainedDefault(@JsonProperty(include = JsonProperty.Include.ALWAYS) value: Int = 2)

case class MixinDefaults(number: Int = 2, optional: Option[Int] = None, currency: String = "USD")

@JsonMixin(target = classOf[MixinDefaults])
@JsonInclude(JsonProperty.Include.NON_NULL)
abstract class InclusionMixin {
  @JsonProperty(include = JsonProperty.Include.NON_DEFAULT) var number: Int = 0
  @JsonProperty(include = JsonProperty.Include.NON_EMPTY) var optional: Option[Int] = None
}

@JsonInclude(JsonProperty.Include.NON_DEFAULT)
case class ScalarDefaults(
    byte: Byte = 1,
    short: Short = 2,
    char: Char = 'x',
    long: Long = 3L,
    @JsonFormat(shape = JsonFormat.Shape.STRING) flag: Boolean = true,
    @JsonFormat(shape = JsonFormat.Shape.STRING) number: Int = 4,
    @JsonRawValue raw: String = "[]",
    id: UserId = UserId(5)
)

@JsonInclude(JsonProperty.Include.NON_DEFAULT)
case class NonFiniteDefaults(
    value: Double = Double.PositiveInfinity,
    values: Array[Float] = Array(Float.NaN)
)

@JsonInclude(JsonProperty.Include.NON_DEFAULT)
case class FreshDefaults(
    values: scala.collection.mutable.ArrayBuffer[Int] = scala.collection.mutable.ArrayBuffer(1)
)

case class HiddenDependency(@JsonIgnore a: Int = 5)(
    @JsonProperty(include = JsonProperty.Include.NON_DEFAULT) val b: Int = a + 1
)

@JsonInclude(JsonProperty.Include.NON_DEFAULT)
case class MissingDeclaredDefault(value: Option[Int])

case class RequiredMixinValue(id: String, count: Int = 3)

@JsonMixin(target = classOf[RequiredMixinValue])
@JsonInclude(JsonProperty.Include.NON_DEFAULT)
abstract class RequiredDefaultMixin

case class ExplicitMissingDefault(
    @JsonProperty(include = JsonProperty.Include.NON_DEFAULT) value: Option[Int]
)

@JsonInclude(JsonProperty.Include.NON_DEFAULT)
case class BodyDefault() {
  var count: Int = 3
}

case class UnitDefault(@JsonProperty(include = JsonProperty.Include.NON_DEFAULT) value: Unit = ())

object ObservedDefault {
  var calls = 0
  var fail = false
  def value: Int = {
    calls += 1
    if (fail) throw new IllegalStateException("default failure")
    1
  }
}

case class EvaluatedDefault(
    @JsonProperty(include = JsonProperty.Include.NON_DEFAULT) value: Int = ObservedDefault.value
)

case class MissingUnwrapped(@JsonUnwrapped point: NestedModels.Point)

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

  case class Span(from: Int)(
      @JsonProperty(include = JsonProperty.Include.NON_DEFAULT) val to: Int = from + 1
  )

  case class Optional(value: Option[String], fallback: Option[String] = Some("default"))

  case class OptionalOnly(value: Option[String])

  case class OptionalDefault(value: Option[String])(val selected: String = value.getOrElse("default"))

  case class UnwrappedNested(@JsonProperty(include = JsonProperty.Include.NON_DEFAULT) code: Int = 5) {
    var note: String = "default-note"
  }

  @JsonInclude(JsonProperty.Include.NON_DEFAULT)
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

case class RequiredProperties(
    text: String,
    number: Int,
    enabled: Boolean,
    option: Option[String],
    values: List[Int],
    arrays: Array[Int],
    default: Int = 7,
    @JsonIgnore ignored: Int
)

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

case class UnitFields(value: Unit, option: Option[Unit], values: List[Unit], array: Array[Unit])

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

case class TypedLabel[T](value: T) extends AnyVal

case class Box[T](value: T)

case class NamedBox[T](name: String, value: T)

case class Boxes(ints: Box[Int], strings: Box[String])

case class VarParams(var a: Int, var b: String = "b")

case class PrivateConstructor private(value: Int)

case class SecondaryConstructor(value: Int) {
  def this(value: String) = this(value.toInt)
}

case class AmbiguousConstructor(value: Int) {
  def this(value: String) = this(value.toInt)
}

object AmbiguousConstructor {
  def apply(value: String): AmbiguousConstructor = new AmbiguousConstructor(value)
}

trait NamedValue {
  val name: String
}

case class InheritedValue(name: String) extends NamedValue

class CustomBuffer extends scala.collection.mutable.ArrayBuffer[Int]

case class AlgebraicFields[T](
    name: String,
    queue: scala.collection.immutable.Queue[T],
    arraySeq: scala.collection.mutable.ArraySeq[T],
    pair: (T, T),
    option: Option[T],
    either: Either[String, T],
    range: NumericRange[T]
)

case class Optionals(
    a: Option[Int],
    b: Option[String] = None,
    c: Option[Option[Int]] = None
)

case class OptionalFields(
    value: Option[String],
    fallback: Option[Int] = Some(7),
    nested: Option[Option[Int]],
    @JsonIgnore ignored: Option[String]
)

case class MissingValues(
    byte: Byte, short: Short, int: Int, long: Long, float: Float, double: Double,
    boolean: Boolean, char: Char, boxed: java.lang.Integer, big: BigInt, decimal: BigDecimal,
    text: String, child: Node, @JsonIgnore ignored: Int, explicit: Int = 9
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
  test("null read handling") {
    for (codegen <- Seq(false, true)) {
      val json = ForyJsonScala.builder().withCodegen(codegen).withAsyncCompilation(false)
        .onNullRead(JsonProperty.NullHandling.SKIP)
        .onContentNullRead(JsonProperty.NullHandling.SKIP).build()
      val defaults = "{\"media_uri\":\"u\",\"tags\":null,\"title\":null}"
      assert(json.fromJson(defaults, classOf[Media]) == Media("u"))
      val body = json.fromJson("{\"id\":1,\"label\":null,\"count\":null}", classOf[BodyState])
      assert(body.label == "initial" && body.count == 7)
      assert(json.fromJson("{\"id\":1,\"label\":\"new\",\"label\":null}", classOf[BodyState]).label == "new")
      val listType = ScalaTypeRef[List[String]]
      val vectorType = ScalaTypeRef[Vector[String]]
      val mapType = ScalaTypeRef[Map[String, String]]
      val values = "[null,\"中\",null]"
      assert(json.fromJson(values, listType) == List("中"))
      assert(json.fromJson(values.getBytes(UTF_8), listType) == List("中"))
      assert(json.fromJson(values, vectorType) == Vector("中"))
      assert(json.fromJson("[null,null]", vectorType).isEmpty)
      assert(json.fromJson("{\"a\":\"中\",\"a\":null,\"b\":null}", mapType) == Map("a" -> "中"))
      assert(json.fromJson("[null,1,null,2]", ScalaTypeRef[scala.collection.immutable.HashSet[Int]]) == Set(1, 2))
      assert(json.fromJson("[null,true,null,false]", ScalaTypeRef[scala.collection.immutable.ArraySeq[Boolean]]) == Seq(true, false))
      assert(json.fromJson("[null,1,null,2]", ScalaTypeRef[scala.collection.immutable.BitSet]) == scala.collection.immutable.BitSet(1, 2))
      val intMapType = ScalaTypeRef[scala.collection.immutable.IntMap[String]]
      val longMapType = ScalaTypeRef[scala.collection.mutable.LongMap[String]]
      for (size <- Seq(0, 1, 16, 17, 1025)) {
        val fields = (0 until size).map(i => s"\"$i\":\"中\",\"$i\":null")
        val input = (fields :+ "\"9999\":null").mkString("{", ",", "}")
        val expected = (0 until size).map(_ -> "中").toMap
        assert(json.fromJson(input, intMapType).toMap == expected)
        assert(json.fromJson(input.getBytes(UTF_8), intMapType).toMap == expected)
        assert(json.fromJson(input, longMapType).iterator.toMap == expected.map { case (k, v) => k.toLong -> v })
      }
      val annotationJson = ForyJsonScala.builder().withCodegen(codegen).withAsyncCompilation(false).build()
      val shallow = annotationJson.fromJson(
        "{\"values\":[null,[null,\"中\"]]}", classOf[NullContents])
      assert(shallow.values == List(List(null, "中")))
      val nodes = annotationJson.fromJson(
        "[{\"value\":1,\"children\":[null,{\"value\":2,\"children\":[]}]}]",
        ScalaTypeRef[List[NullNode]])
      assert(nodes == List(NullNode(1, List(NullNode(2, Nil)))))
      assert(json.fromJson("{\"values\":\"a\"}", classOf[OpaqueNullContents]).values == List("a"))
    }
  }

  private def assertWriterGeneration(json: ForyJson, model: Class[_], enabled: Boolean): Unit = {
    val slots = ReflectionUtils.getObjectFieldValue(json, "slots").asInstanceOf[Array[AnyRef]]
    val state = ReflectionUtils.getObjectFieldValue(slots(0), "state")
    val resolver = ReflectionUtils.getObjectFieldValue(state, "typeResolver")
      .asInstanceOf[JsonTypeResolver]
    resolver.lockJIT()
    try {
      val info = resolver.getRuntimeTypeInfo(model)
      assert(info.stringWriter().isInstanceOf[ObjectCodec[_]] == !enabled)
      assert(info.utf8Writer().isInstanceOf[ObjectCodec[_]] == !enabled)
    } finally resolver.unlockJIT()
  }

  test("non ASCII escaping") {
    val value = Map("é" -> List(Some("汉😀")))
    val valueType = ScalaTypeRef[Map[String, List[Option[String]]]]
    val expected = "{\"\\u00e9\":[\"\\u6c49\\ud83d\\ude00\"]}"
    for (codegen <- Seq(false, true)) {
      val json = ForyJsonScala.builder().escapeNonAscii(true)
        .withCodegen(codegen).withAsyncCompilation(false).build()
      assert(json.toJson(value, valueType) == expected)
      assert(new String(json.toJsonBytes(value, valueType), UTF_8) == expected)
      assert(json.fromJson(expected, valueType) == value)
      assert(json.fromJson(json.toPrettyJsonBytes(value), valueType) == value)
      val fields = BinaryFields("汉😀", Array[Byte](1), Nil, Map.empty, None)
      val text = json.toJson(fields)
      assert(text.contains("\"label\":\"\\u6c49\\ud83d\\ude00\""))
      assert(new String(json.toJsonBytes(fields), UTF_8) == text)
      assert(json.fromJson(text, classOf[BinaryFields]).label == fields.label)
      assertWriterGeneration(json, classOf[BinaryFields], codegen)
    }
  }

  test("byte array formats") {
    val bytes = Array[Byte](1, -2, 3)
    val formats = Seq(
      JsonByteArray.Format.BASE64 -> "\"Af4D\"",
      JsonByteArray.Format.BASE16 -> "\"01fe03\"",
      JsonByteArray.Format.ARRAY -> "[1,-2,3]"
    )
    for (codegen <- Seq(false, true); (format, encoded) <- formats) {
      val json = ForyJsonScala.builder().byteArrayFormat(format)
        .withCodegen(codegen).withAsyncCompilation(false).build()
      val byteType = ScalaTypeRef[Array[Byte]]
      assert(json.toJson(bytes, byteType) == encoded)
      assert(json.fromJson(encoded, byteType).sameElements(bytes))
      assert(json.fromJson(encoded.getBytes(UTF_8), byteType).sameElements(bytes))
      val value = BinaryFields("汉", bytes, List(bytes), Map("data" -> bytes), Some(bytes))
      val text = json.toJson(value)
      assert(text.contains("\"bytes\":" + encoded))
      assert(text.contains("\"nested\":[" + encoded + "]"))
      assert(text.contains("\"mapped\":{\"data\":" + encoded + "}"))
      assert(text.contains("\"optional\":" + encoded))
      assert(new String(json.toJsonBytes(value), UTF_8) == text)
      for {
        input <- Seq(text, json.toPrettyJson(value))
        decoded <- Seq(
          json.fromJson(input, classOf[BinaryFields]),
          json.fromJson(input.getBytes(UTF_8), classOf[BinaryFields])
        )
      } {
        assert(decoded.bytes.sameElements(bytes))
        assert(decoded.nested.head.sameElements(bytes))
        assert(decoded.mapped("data").sameElements(bytes))
        assert(decoded.optional.get.sameElements(bytes))
      }
      assertWriterGeneration(json, classOf[BinaryFields], codegen)
    }
  }

  test("scalar string fields and Mixins") {
    for (codegen <- Seq(false, true)) {
      val json = ForyJsonScala.builder().registerMixin(classOf[ArtifactStateMixin])
        .withCodegen(codegen).withAsyncCompilation(false).build()
      for (label <- Seq("ascii", "中文")) {
        val value = StringScalarFields(false, 7, BigInt("123456789012345678901"), BigDecimal("1.25"), label)
        val text = json.toJson(value)
        assert(text.contains("\"active\":\"false\""))
        assert(text.contains("\"count\":\"7\""))
        assert(text.contains("\"total\":\"123456789012345678901\""))
        assert(text.contains("\"fraction\":\"1.25\""))
        assert(new String(json.toJsonBytes(value), UTF_8) == text)
        assert(json.fromJson(text, classOf[StringScalarFields]) == value)
        assert(json.fromJson(text.getBytes(UTF_8), classOf[StringScalarFields]) == value)
        val state = ArtifactState(false, label)
        val encoded = "{\"expired\":\"false\",\"label\":\"" + label + "\"}"
        assert(json.toJson(state) == encoded)
        assert(new String(json.toJsonBytes(state), UTF_8) == encoded)
        assert(json.fromJson(encoded, classOf[ArtifactState]) == state)
        assert(json.fromJson(encoded.getBytes(UTF_8), classOf[ArtifactState]) == state)
        assert(json.fromJson(encoded.replace("\"false\"", "false"), classOf[ArtifactState]) == state)
      }
    }
    val factory: JsonCodecFactory = (_, _, _) => internal.ScalaBigIntCodec
    val customJson = ForyJsonScala.builder().registerCodec(classOf[BigInt], factory).build()
    assertThrows[ForyJsonException] {
      customJson.toJson(StringScalarFields(false, 7, BigInt(1), BigDecimal(2), "custom"))
    }
  }

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

  test("missing reference values use null") {
    for (json <- Seq(
        ForyJsonScala.builder().withCodegen(false).build(),
        ForyJsonScala.builder().withAsyncCompilation(false).build()
      )) {
      assert(json.toJson(NullableRequired(null)) == "{}")
      assert(json.fromJson("{}", classOf[NullableRequired]) == NullableRequired(null))
      assert(json.toJson(ExplicitNullable(null)) == "{\"value\":null}")
      assert(json.fromJson("{\"value\":null}", classOf[ExplicitNullable]) == ExplicitNullable(null))
    }
  }

  test("constructor values follow empty inclusion") {
    for (codegen <- Seq(false, true)) {
      val json = ForyJsonScala.builder()
        .withCodegen(codegen)
        .withAsyncCompilation(false)
        .defaultPropertyInclusion(JsonProperty.Include.NON_EMPTY)
        .build()
      val value = EmptyRequired("", new java.util.ArrayList[String](), Array.emptyIntArray)
      val text = json.toJson(value)
      assert(text == "{}")
      assert(new String(json.toJsonBytes(value), UTF_8) == text)
      val decoded = json.fromJson(text, classOf[EmptyRequired])
      assert(decoded.value == null)
      assert(decoded.items.isEmpty)
      assert(decoded.numbers.isEmpty)
      assert(json.toJson(ExplicitEmptyRequired("")) == "{}")
      assert(json.fromJson("{}", classOf[ExplicitEmptyRequired]) == ExplicitEmptyRequired(null))
      assert(json.toJson(EmptyDefault()) == "{}")
      assert(json.fromJson("{}", classOf[EmptyDefault]) == EmptyDefault())
    }
  }

  test("Scala property omission uses logical emptiness") {
    for (codegen <- Seq(false, true)) {
      val json = ForyJsonScala.builder().withCodegen(codegen).withAsyncCompilation(false)
        .defaultPropertyInclusion(JsonProperty.Include.NON_EMPTY).build()
      val empty = EmptyScalaFields(None, Nil, Vector.empty, Set.empty, Map.empty,
        scala.collection.mutable.ArrayBuffer.empty, None, List.empty[Int])
      assert(json.toJson(empty) == "{}")
      assert(new String(json.toJsonBytes(empty), UTF_8) == "{}")
      assert(json.toPrettyJson(empty) == "{ }")
      assert(new String(json.toPrettyJsonBytes(empty), UTF_8) == "{ }")
      assertWriterGeneration(json, classOf[EmptyScalaFields], codegen)
      val ranges = EmptyScalaRanges(scala.collection.immutable.BitSet.empty,
        scala.collection.mutable.BitSet.empty, Range(1, 1), NumericRange(1L, 1L, 1L))
      val rangesType = ScalaTypeRef[EmptyScalaRanges[Long]]
      assert(json.toJson(ranges, rangesType) == "{}")
      assert(new String(json.toJsonBytes(ranges, rangesType), UTF_8) == "{}")
      val nonEmptyRanges = ranges.copy(bits = scala.collection.immutable.BitSet(1),
        mutableBits = scala.collection.mutable.BitSet(2), range = Range(3, 4),
        numeric = NumericRange(4L, 5L, 1L))
      val expectedRanges = "{\"bits\":[1],\"mutableBits\":[2],\"range\":[3],\"numeric\":[4]}"
      assert(json.toJson(nonEmptyRanges, rangesType) == expectedRanges)
      assert(new String(json.toJsonBytes(nonEmptyRanges, rangesType), UTF_8) == expectedRanges)
      val dynamicEmpty = Seq[Any](None, Nil, Vector.empty, Set.empty, Map.empty,
        scala.collection.mutable.ArrayBuffer.empty, ranges.bits, ranges.mutableBits,
        ranges.range, ranges.numeric)
      for (value <- dynamicEmpty) {
        assert(json.toJson(empty.copy(dynamic = value)) == "{}")
        assert(new String(json.toJsonBytes(empty.copy(dynamic = value)), UTF_8) == "{}")
        // Pretty entry points are runtime-typed; NumericRange keeps its dynamic codec here.
        assert(json.toPrettyJson(empty.copy(dynamic = value)) == "{ }")
        assert(new String(json.toPrettyJsonBytes(empty.copy(dynamic = value)), UTF_8) == "{ }")
      }
      val custom = CustomEmptyOptions(None, None)
      assert(json.toJson(custom) == "{\"retained\":\"custom\"}")
      assert(new String(json.toJsonBytes(custom), UTF_8) == "{\"retained\":\"custom\"}")
      assert(json.toPrettyJson(custom) == "{\n  \"retained\" : \"custom\"\n}")
      assert(new String(json.toPrettyJsonBytes(custom), UTF_8) == json.toPrettyJson(custom))
      assertWriterGeneration(json, classOf[CustomEmptyOptions], codegen)
      for (option <- Seq(Some(""), Some(null), Some("漢"))) {
        val text = json.toJson(empty.copy(option = option, dynamic = Some(Nil)))
        assert(text.contains("\"option\":"))
        assert(text.contains("\"dynamic\":[]"))
        assert(new String(json.toJsonBytes(empty.copy(option = option, dynamic = Some(Nil))), UTF_8) == text)
      }
    }
  }

  test("Scala property omission distinguishes declared defaults") {
    for (codegen <- Seq(false, true); async <- Seq(false, true)) {
      val json = ForyJsonScala.builder().withCodegen(codegen).withAsyncCompilation(async)
        .build()
      val reader = ForyJsonScala.builder().withCodegen(codegen).withAsyncCompilation(false).build()
      val value = InclusionDefaults(0, false, None)
      val expected = "{\"required\":0,\"flag\":false,\"optional\":null}"
      assert(json.toJson(value) == expected)
      assert(new String(json.toJsonBytes(value), UTF_8) == expected)
      val pretty = "{\n  \"required\" : 0,\n  \"flag\" : false,\n  \"optional\" : null\n}"
      assert(json.toPrettyJson(value) == pretty)
      assert(new String(json.toPrettyJsonBytes(value), UTF_8) == pretty)
      assert(reader.fromJson(expected, classOf[InclusionDefaults]) == value)
      assert(reader.fromJson(json.toPrettyJson(value), classOf[InclusionDefaults]) == value)
      assert(reader.fromJson(json.toPrettyJsonBytes(value), classOf[InclusionDefaults]) == value)
      val changed = value.copy(number = 0, currency = null, selected = None, values = Nil, text = "漢")
      val text = json.toJson(changed)
      assert(text == "{\"required\":0,\"flag\":false,\"optional\":null,\"number\":0,\"currency\":null,\"selected\":null,\"values\":[],\"text\":\"漢\"}")
      assert(new String(json.toJsonBytes(changed), UTF_8) == text)
      assert(reader.fromJson(text, classOf[InclusionDefaults]) == changed)
      assert(json.toJson(RetainedDefault()) == "{\"value\":2}")
      assert(json.toJson(NestedModels.OptionalOnly(None)) == "{\"value\":null}")
      if (!async) assertWriterGeneration(json, classOf[InclusionDefaults], codegen)
    }
  }

  test("Scala property omission handles dependencies and arrays") {
    for (codegen <- Seq(false, true)) {
      val json = ForyJsonScala.builder().withCodegen(codegen).withAsyncCompilation(false)
        .build()
      assert(json.toJson(CurriedDefault(5)()) == "{\"a\":5}")
      assert(json.toJson(CurriedDefault(5)(2)) == "{\"a\":5,\"b\":2}")
      assert(json.fromJson("{\"a\":5}", classOf[CurriedDefault]).b == 6)
      for (high <- Seq(6, 2)) {
        val value = KeywordDefault(5)(high)
        val text = if (high == 6) "{\"switch\":5}" else "{\"switch\":5,\"high\":2}"
        val pretty = if (high == 6) "{\n  \"switch\" : 5\n}"
          else "{\n  \"switch\" : 5,\n  \"high\" : 2\n}"
        assert(json.toJson(value) == text)
        assert(new String(json.toJsonBytes(value), UTF_8) == text)
        assert(json.toPrettyJson(value) == pretty)
        assert(new String(json.toPrettyJsonBytes(value), UTF_8) == pretty)
        assert(json.fromJson(text, classOf[KeywordDefault]).high == high)
        assert(json.fromJson(pretty.getBytes(UTF_8), classOf[KeywordDefault]).high == high)
      }
      assertWriterGeneration(json, classOf[KeywordDefault], codegen)
      assert(json.toJson(NestedModels.Span(5)()) == "{\"from\":5}")
      assert(new String(json.toJsonBytes(NestedModels.Span(5)(2)), UTF_8) == "{\"from\":5,\"to\":2}")
      assert(json.toJson(ArrayDefaults()) == "{}")
      assert(new String(json.toJsonBytes(ArrayDefaults()), UTF_8) == "{}")
      val negative = ArrayDefaults(fraction = -0.0, single = -0.0f)
      val text = json.toJson(negative)
      assert(text.contains("\"fraction\":-0.0"))
      assert(text.contains("\"single\":-0.0"))
      assert(new String(json.toJsonBytes(negative), UTF_8) == text)
      assert(json.toJson(ArrayDefaults(ints = Array.emptyIntArray)).contains("\"ints\":[]"))
      val unwrapped = NestedModels.UnwrappedOwner()
      assert(json.toJson(unwrapped) == "{}")
      assert(new String(json.toJsonBytes(unwrapped), UTF_8) == "{}")
      assert(json.toPrettyJson(unwrapped) == "{ }")
      assert(new String(json.toPrettyJsonBytes(unwrapped), UTF_8) == "{ }")
      assert(json.fromJson("{}", classOf[NestedModels.UnwrappedOwner]) == unwrapped)
      val changed = NestedModels.UnwrappedOwner(nested = NestedModels.UnwrappedNested(8))
      val output = "{\"code\":8,\"note\":\"default-note\"}"
      assert(json.toJson(changed) == output)
      assert(new String(json.toJsonBytes(changed), UTF_8) == output)
      assertWriterGeneration(json, classOf[CurriedDefault], codegen)
      assertWriterGeneration(json, classOf[NestedModels.UnwrappedOwner], codegen)
    }
  }

  test("Scala default omission respects field formats and Mixins") {
    for (codegen <- Seq(false, true)) {
      val json = ForyJsonScala.builder().withCodegen(codegen).withAsyncCompilation(false)
        .build()
      assert(json.toJson(ScalarDefaults()) == "{}")
      assert(new String(json.toJsonBytes(ScalarDefaults()), UTF_8) == "{}")
      val changed = ScalarDefaults(0, 0, '漢', 0L, false, 0, null, UserId(0))
      val expected = "{\"byte\":0,\"short\":0,\"char\":\"漢\",\"long\":0,\"flag\":\"false\",\"number\":\"0\",\"raw\":null,\"id\":0}"
      assert(json.toJson(changed) == expected)
      assert(new String(json.toJsonBytes(changed), UTF_8) == expected)
      assertWriterGeneration(json, classOf[ScalarDefaults], codegen)
      assert(json.fromJson(json.toPrettyJson(changed), classOf[ScalarDefaults]) == changed)
      assert(json.fromJson(json.toPrettyJsonBytes(changed), classOf[ScalarDefaults]) == changed)
      val mixin = ForyJsonScala.builder().withCodegen(codegen).withAsyncCompilation(false)
        .registerMixin(classOf[InclusionMixin]).build()
      val text = mixin.toJson(MixinDefaults())
      assert(!text.contains("\"number\""))
      assert(!text.contains("\"optional\""))
      assert(text.contains("\"currency\":\"USD\""))
      assert(new String(mixin.toJsonBytes(MixinDefaults()), UTF_8) == text)
      intercept[ForyJsonException](json.toJson(HiddenDependency()()))
      assert(json.toJson(MissingDeclaredDefault(None)) == "{\"value\":null}")
      assert(new String(json.toJsonBytes(MissingDeclaredDefault(None)), UTF_8) == "{\"value\":null}")
      assert(json.toJson(BodyDefault()) == "{\"count\":3}")
      assert(new String(json.toJsonBytes(BodyDefault()), UTF_8) == "{\"count\":3}")
      assert(json.toJson(ExplicitMissingDefault(None)) == "{\"value\":null}")
      assert(new String(json.toJsonBytes(ExplicitMissingDefault(None)), UTF_8) == "{\"value\":null}")
      val requiredMixin = ForyJsonScala.builder().withCodegen(codegen)
        .withAsyncCompilation(false).registerMixin(classOf[RequiredDefaultMixin]).build()
      val required = RequiredMixinValue("漢")
      val requiredText = "{\"id\":\"漢\"}"
      assert(requiredMixin.toJson(required) == requiredText)
      assert(new String(requiredMixin.toJsonBytes(required), UTF_8) == requiredText)
      assert(requiredMixin.fromJson(requiredText, classOf[RequiredMixinValue]) == required)
      assert(requiredMixin.fromJson(requiredMixin.toPrettyJsonBytes(required), classOf[RequiredMixinValue]) == required)
      assertWriterGeneration(requiredMixin, classOf[RequiredMixinValue], codegen)
      // A JVM void method supplies no comparable default value; retain the Unit property.
      assert(json.toJson(UnitDefault()) == "{\"value\":null}")
      assert(new String(json.toJsonBytes(UnitDefault()), UTF_8) == "{\"value\":null}")
      val fresh1 = json.fromJson("{}", classOf[FreshDefaults])
      val fresh2 = json.fromJson("{}".getBytes(UTF_8), classOf[FreshDefaults])
      fresh1.values += 2
      assert(fresh2.values == Seq(1))
    }
  }

  test("Scala default omission preserves failures and root reuse") {
    for (codegen <- Seq(false, true)) {
      val json = ForyJsonScala.builder().withCodegen(codegen).withAsyncCompilation(false)
        .build()
      for (value <- Seq(NonFiniteDefaults(), NonFiniteDefaults(value = 0.0))) {
        val text = json.toJson(value)
        assert(text.contains("\"value\":"))
        assert(text.contains("\"values\":[\"NaN\"]"))
        assert(new String(json.toJsonBytes(value), UTF_8) == text)
      }
      ObservedDefault.calls = 0
      val value = EvaluatedDefault(1)
      assert(json.toJson(value) == "{}")
      assert(ObservedDefault.calls == 1)
      assert(new String(json.toJsonBytes(value), UTF_8) == "{}")
      assert(ObservedDefault.calls == 2)
      ObservedDefault.fail = true
      try {
        intercept[Exception](json.toPrettyJson(value))
        intercept[Exception](json.toPrettyJsonBytes(value))
      } finally ObservedDefault.fail = false
      assert(json.toJson(value) == "{}")
      assert(new String(json.toJsonBytes(value), UTF_8) == "{}")
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

    val optionsType = new TypeRef[List[Option[String]]]() {}
    for (text <- Seq("ascii", "中文")) {
      val input = "[null, \t\r\n\"" + text + "\", \nnull, \"null\"]"
      val expected = List(None, Some(text), None, Some("null"))
      assert(json.fromJson(input, optionsType) == expected)
      assert(json.fromJson(input.getBytes(UTF_8), optionsType) == expected)
    }
    assertThrows[ForyJsonException](json.fromJson(" \t\r\nnull".getBytes(UTF_8), someType))
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
      assert(json.toJson(Duration.Undefined, durationType) == """{"special":"UNDEFINED"}""")
      assert(new String(json.toJsonBytes(Duration.Undefined, durationType), UTF_8) == """{"special":"UNDEFINED"}""")
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
      val negative = FiniteDuration(-Long.MaxValue, TimeUnit.NANOSECONDS)
      assert(json.fromJson(json.toJsonBytes(negative, finiteType), finiteType) == negative)
      // FiniteDuration's supported range excludes Long.MinValue nanoseconds.
      assertThrows[RuntimeException](json.fromJson(
        """{"length":-9223372036854775808,"unit":"NANOSECONDS"}""", finiteType))
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
      (ScalaTypeRef[im.Queue[Int]], classOf[im.Queue[_]]),
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
      assert(new String(json.toJsonBytes(decoded, anyRef), UTF_8) == encoded)
      assert(json.toJson(decoded) == encoded)
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
      assert(new String(json.toJsonBytes(decoded, anyRef), UTF_8) == encoded)
      assert(json.toJson(decoded) == encoded)
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

  test("primitive mutable ArraySeq") {
    import scala.collection.mutable.ArraySeq
    val cases: Seq[(AnyRef, TypeRef[_], String)] = Seq(
      (ArraySeq[Boolean](true, false), ScalaTypeRef[ArraySeq[Boolean]], "[true,false]"),
      (ArraySeq[Byte](1, 2), ScalaTypeRef[ArraySeq[Byte]], "[1,2]"),
      (ArraySeq[Short](1, 2), ScalaTypeRef[ArraySeq[Short]], "[1,2]"),
      (ArraySeq[Char]('a', '中'), ScalaTypeRef[ArraySeq[Char]], """["a","中"]"""),
      (ArraySeq[Int](1, 2), ScalaTypeRef[ArraySeq[Int]], "[1,2]"),
      (ArraySeq[Long](1, 2), ScalaTypeRef[ArraySeq[Long]], "[1,2]"),
      (ArraySeq[Float](1.5f), ScalaTypeRef[ArraySeq[Float]], "[1.5]"),
      (ArraySeq[Double](1.5), ScalaTypeRef[ArraySeq[Double]], "[1.5]"),
      (ArraySeq[String]("a"), ScalaTypeRef[ArraySeq[String]], """["a"]""")
    )
    for (json <- runtimes; (value, declared, expected) <- cases) {
      val typeRef = declared.asInstanceOf[TypeRef[AnyRef]]
      assert(roundTrip(json, value, typeRef) == value)
      assert(json.toJson(value, typeRef) == expected)
      assert(json.toJson(value) == expected)
      assert(new String(json.toJsonBytes(value), UTF_8) == expected)
    }
  }

  test("runtime collection writes and unsupported families") {
    import scala.collection.{immutable => im, mutable => mu}
    val json = runtimes.head
    assert(json.toJson(im.Queue(1, 2)) == "[1,2]")
    assert(new String(json.toJsonBytes(im.Queue(1, 2)), UTF_8) == "[1,2]")
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
    val custom = new CustomBuffer
    custom += 1
    assertThrows[ForyJsonException](json.toJson(custom))
    assertThrows[ForyJsonException](json.toJson(custom, ScalaTypeRef[mu.ArrayBuffer[Int]]))
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
      val fieldType = TypeRef.ofDeclaredTypeArguments(
        classOf[Box[_]], null, java.util.Collections.singletonList(typeRef), null)
      assertThrows[UnsupportedJsonTypeException](json.fromJson("{}", fieldType))
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
      assert(json.fromJson("{}", classOf[Optionals]) == Optionals(None))
      assert(json.fromJson("{}".getBytes(UTF_8), classOf[Optionals]) == Optionals(None))
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

  test("Option constructor defaults") {
    for (json <- runtimes) {
      val empty = OptionalFields(None, Some(7), None, None)
      for (text <- Seq("{}", """{"unknown":"中"}""")) {
        assert(json.fromJson(text, classOf[OptionalFields]) == empty)
        assert(json.fromJson(text.getBytes(UTF_8), classOf[OptionalFields]) == empty)
        assert(json.fromJson(text, classOf[NestedModels.Optional]) == NestedModels.Optional(None))
        assert(
          json.fromJson(text.getBytes(UTF_8), classOf[NestedModels.Optional]) ==
            NestedModels.Optional(None))
        assert(
          json.fromJson(text, classOf[NestedModels.OptionalOnly]) == NestedModels.OptionalOnly(None))
        assert(json.fromJson(text, classOf[NestedModels.OptionalDefault]).selected == "default")
      }
      val present = """{"value":"中","fallback":null,"nested":2}"""
      val expected = OptionalFields(Some("中"), None, Some(Some(2)), None)
      assert(json.fromJson(present, classOf[OptionalFields]) == expected)
      assert(json.fromJson(present.getBytes(UTF_8), classOf[OptionalFields]) == expected)
      assert(
        json.fromJson("""{"value":"present"}""", classOf[NestedModels.OptionalDefault]).selected ==
          "present")
      val boxType = ScalaTypeRef[Box[Option[String]]]
      assert(json.fromJson("{}", boxType) == Box(None))
      assert(json.fromJson("{}".getBytes(UTF_8), boxType) == Box(None))
      assert(json.fromJson("""{"value":1}""", classOf[Node]) == Node(1, None))
      assert(json.fromJson("{}", classOf[Node]) == Node(0, None))
      assert(json.fromJson(json.toJson(empty), classOf[OptionalFields]) == empty)
    }
  }

  test("missing constructor values use type defaults") {
    for (json <- runtimes; text <- Seq("{}", """{"unknown":"中"}""")) {
      val expected = MissingValues(0, 0, 0, 0L, 0F, 0D, false, 0.toChar, 0, BigInt(0), BigDecimal(0), null, null, 0)
      assert(json.fromJson(text, classOf[MissingValues]) == expected)
      assert(json.fromJson(text.getBytes(UTF_8), classOf[MissingValues]) == expected)
      assert(json.fromJson(text, classOf[CurriedDefault]).b == 1)
      assert(json.fromJson(text, classOf[NestedModels.Span]).to == 1)
      assert(json.fromJson(text, classOf[BodyState]).id == 0)
      val numbers = json.fromJson(text, ScalaTypeRef[Box[Array[Int]]]).value
      assert(numbers.isEmpty)
      val objects = json.fromJson(text, ScalaTypeRef[Box[Array[Node]]]).value
      assert(objects.isEmpty)
      assert(json.fromJson(text, classOf[MissingUnwrapped]) == MissingUnwrapped(null))
      val flattened = """{"x":2,"unknown":"中"}"""
      val point = MissingUnwrapped(NestedModels.Point(2, null))
      assert(json.fromJson(flattened, classOf[MissingUnwrapped]) == point)
      assert(json.fromJson(flattened.getBytes(UTF_8), classOf[MissingUnwrapped]) == point)
    }
  }

  test("required constructor properties preserve defaults") {
    for (codegen <- Seq(false, true)) {
      val strict = ForyJsonScala.builder().withCodegen(codegen).withAsyncCompilation(false)
        .failOnMissingRequiredProperties(true).build()
      val lenient = ForyJsonScala.builder().withCodegen(codegen).withAsyncCompilation(false).build()
      for (text <- Seq("{}", """{"text":"中","number":0}""", """{"text":"ok","enabled":false}""")) {
        assertThrows[ForyJsonException](strict.fromJson(text, classOf[RequiredProperties]))
        assertThrows[ForyJsonException](strict.fromJson(text.getBytes(UTF_8), classOf[RequiredProperties]))
        assert(lenient.fromJson("{}", classOf[RequiredProperties]).number == 0)
      }
      for (text <- Seq("""{"text":null,"number":0,"enabled":false}""",
          """{"enabled":false,"unknown":1,"text":"中","number":0}""")) {
        val value = strict.fromJson(text, classOf[RequiredProperties])
        assert(value.option == None && value.values.isEmpty && value.arrays.isEmpty)
        assert(value.default == 7 && value.ignored == 0)
        val bytes = strict.fromJson(text.getBytes(UTF_8), classOf[RequiredProperties])
        assert(bytes.text == value.text && bytes.arrays.isEmpty && bytes.default == 7)
      }
      assert(strict.fromJson("{}", classOf[EmptyDefault]).value == "")
      assert(strict.fromJson("{}", classOf[NestedModels.OptionalOnly]).value == None)
      assert(strict.fromJson("{\"a\":5}", classOf[CurriedDefault]).b == 6)
      assertThrows[ForyJsonException](strict.fromJson("{}", classOf[CurriedDefault]))
      val first = strict.fromJson("{\"text\":\"x\",\"number\":0,\"enabled\":false}", classOf[RequiredProperties])
      val second = strict.fromJson(strict.toPrettyJsonBytes(first), classOf[RequiredProperties])
      assert(second.arrays ne first.arrays)
    }
  }

  test("missing collections use independent empty values") {
    import scala.collection.{immutable => im, mutable => mu}
    val types = Seq[TypeRef[_]](
      ScalaTypeRef[Box[List[String]]], ScalaTypeRef[Box[Vector[String]]],
      ScalaTypeRef[Box[Seq[String]]], ScalaTypeRef[Box[im.Queue[String]]],
      ScalaTypeRef[Box[im.ArraySeq[String]]], ScalaTypeRef[Box[im.HashSet[String]]],
      ScalaTypeRef[Box[im.ListSet[String]]], ScalaTypeRef[Box[im.BitSet]],
      ScalaTypeRef[Box[mu.ArrayBuffer[String]]], ScalaTypeRef[Box[mu.ListBuffer[String]]],
      ScalaTypeRef[Box[mu.ArraySeq[String]]], ScalaTypeRef[Box[mu.ArrayDeque[String]]],
      ScalaTypeRef[Box[mu.Queue[String]]], ScalaTypeRef[Box[mu.HashSet[String]]],
      ScalaTypeRef[Box[mu.LinkedHashSet[String]]], ScalaTypeRef[Box[mu.BitSet]],
      ScalaTypeRef[Box[Map[String, String]]], ScalaTypeRef[Box[im.VectorMap[String, String]]],
      ScalaTypeRef[Box[im.ListMap[String, String]]], ScalaTypeRef[Box[im.IntMap[String]]],
      ScalaTypeRef[Box[im.LongMap[String]]], ScalaTypeRef[Box[mu.HashMap[String, String]]],
      ScalaTypeRef[Box[mu.LinkedHashMap[String, String]]],
      ScalaTypeRef[Box[mu.AnyRefMap[String, String]]], ScalaTypeRef[Box[mu.LongMap[String]]]
    )
    for (json <- runtimes) {
      for (kind <- types) {
        val first = json.fromJson("{}", kind).asInstanceOf[Box[scala.collection.Iterable[_]]].value
        val second = json.fromJson("{}".getBytes(UTF_8), kind).asInstanceOf[Box[scala.collection.Iterable[_]]].value
        assert(first.isEmpty && second.isEmpty)
        if (first.isInstanceOf[mu.Iterable[_]]) assert(first ne second)
      }
      val kind = ScalaTypeRef[Box[mu.ArrayBuffer[String]]]
      val first = json.fromJson("{}", kind).value
      first += "changed"
      assert(json.fromJson("{}", kind).value.isEmpty)
      val queue = json.fromJson("{}", ScalaTypeRef[Box[java.util.PriorityQueue[String]]]).value
      assert(queue.isEmpty)
      val mapType = ScalaTypeRef[Box[java.util.concurrent.ConcurrentMap[String, String]]]
      val map = json.fromJson("{}", mapType).value
      map.put("key", "changed")
      assert(json.fromJson("{}", mapType).value.isEmpty)
      val enumSet = ScalaTypeRef[Box[java.util.EnumSet[java.util.concurrent.TimeUnit]]]
      val enumMap = ScalaTypeRef[Box[java.util.EnumMap[java.util.concurrent.TimeUnit, String]]]
      assert(json.fromJson("{}", enumSet).value.isEmpty)
      assert(json.fromJson("{}", enumMap).value.isEmpty)
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
      val genericType = ScalaTypeRef[TypedLabel[String]]
      assert(roundTrip(json, TypedLabel("中"), genericType) == TypedLabel("中"))
      assertThrows[UnsupportedJsonTypeException](json.fromJson("null", classOf[TypedLabel[_]]))
    }
  }

  test("specialized tuples") {
    val cases: Seq[(Product, TypeRef[_], String)] = Seq(
      (Tuple1(1), ScalaTypeRef[Tuple1[Int]], "[1]"),
      ((1, 2), ScalaTypeRef[(Int, Int)], "[1,2]"),
      ((1L, 2.5), ScalaTypeRef[(Long, Double)], "[1,2.5]"),
      ((true, '中'), ScalaTypeRef[(Boolean, Char)], """[true,"中"]""")
    )
    for (json <- runtimes; (value, declared, expected) <- cases) {
      val typeRef = declared.asInstanceOf[TypeRef[Product]]
      assert(roundTrip(json, value, typeRef) == value)
      assert(json.toJson(value, typeRef) == expected)
      assert(json.toJson(value) == expected)
      assert(new String(json.toJsonBytes(value), UTF_8) == expected)
    }
  }

  test("pretty object and collection output") {
    val value = Box(List("ascii", "中文", "[]{}\\\""))
    val declared = ScalaTypeRef[Box[List[String]]]
    val expected = "{\n  \"value\" : [\n    \"ascii\",\n    \"中文\",\n    \"[]{}\\\\\\\"\"\n  ]\n}"
    for (codegen <- Seq(false, true)) {
      val json = ForyJsonScala
        .builder()
        .withCodegen(codegen)
        .withAsyncCompilation(false)
        .build()
      assert(json.toPrettyJson(value) == expected)
      assert(new String(json.toPrettyJsonBytes(value), UTF_8) == expected)
      assert(json.fromJson(expected, declared) == value)
      assert(json.fromJson(expected.getBytes(UTF_8), declared) == value)
      val booleans = "[\n  true,\n  false,\n  true\n]"
      for (values <- Seq(
          List(true, false, true),
          Vector(true, false, true),
          scala.collection.immutable.ArraySeq(true, false, true))) {
        assert(json.toPrettyJson(values) == booleans)
        assert(new String(json.toPrettyJsonBytes(values), UTF_8) == booleans)
        assert(json.toJson(values) == "[true,false,true]")
      }
      assert(
        json.toPrettyJson((List.empty[Int], List(1, 2))) ==
          "[\n  [ ],\n  [\n    1,\n    2\n  ]\n]")
    }
  }

  test("primitive generic case classes") {
    val cases: Seq[(Any, TypeRef[_], TypeRef[_])] = Seq(
      (true, ScalaTypeRef[Box[Boolean]], ScalaTypeRef[NamedBox[Boolean]]),
      (1.toByte, ScalaTypeRef[Box[Byte]], ScalaTypeRef[NamedBox[Byte]]),
      (2.toShort, ScalaTypeRef[Box[Short]], ScalaTypeRef[NamedBox[Short]]),
      ('中', ScalaTypeRef[Box[Char]], ScalaTypeRef[NamedBox[Char]]),
      (3, ScalaTypeRef[Box[Int]], ScalaTypeRef[NamedBox[Int]]),
      (4L, ScalaTypeRef[Box[Long]], ScalaTypeRef[NamedBox[Long]]),
      (1.5f, ScalaTypeRef[Box[Float]], ScalaTypeRef[NamedBox[Float]]),
      (2.5, ScalaTypeRef[Box[Double]], ScalaTypeRef[NamedBox[Double]])
    )
    for (json <- runtimes; (value, boxType, namedType) <- cases) {
      assert(roundTrip(json, Box(value), boxType.asInstanceOf[TypeRef[Box[Any]]]) == Box(value))
      val named = NamedBox("中", value)
      assert(roundTrip(json, named, namedType.asInstanceOf[TypeRef[NamedBox[Any]]]) == named)
    }
  }

  test("nested generic case classes") {
    val typeRef = ScalaTypeRef[Box[Box[Option[Int]]]]
    val value = Box(Box(Some(1): Option[Int]))
    for (json <- runtimes) {
      assert(roundTrip(json, value, typeRef) == value)
      assert(json.toJson(value, typeRef) == """{"value":{"value":1}}""")
    }
  }

  test("Unit type tokens") {
    type Completion = Unit
    val unitType = ScalaTypeRef[Completion]
    assert(unitType.getRawType == classOf[scala.runtime.BoxedUnit])
    for (json <- runtimes) {
      assert(json.toJson((), unitType) == "null")
      assert(new String(json.toJsonBytes((), unitType), UTF_8) == "null")
      json.fromJson("null", unitType)
      val boxedType = unitType.asInstanceOf[TypeRef[AnyRef]]
      assert(json.fromJson("null", boxedType) eq scala.runtime.BoxedUnit.UNIT)
      assert(json.fromJson("null".getBytes(UTF_8), boxedType) eq scala.runtime.BoxedUnit.UNIT)
      assertThrows[ForyJsonException](json.fromJson("1", unitType))
      assertThrows[IllegalArgumentException](json.toJson((), classOf[Unit]))
    }
  }

  test("Unit in parameterized types") {
    val optionType = ScalaTypeRef[Option[Unit]]
    val listType = ScalaTypeRef[List[Unit]]
    val arrayType = ScalaTypeRef[Array[Unit]]
    val boxType = ScalaTypeRef[Box[Unit]]
    val nestedType = ScalaTypeRef[List[Option[Unit]]]
    for (json <- runtimes) {
      assert(json.toJson(Some(()): Option[Unit], optionType) == "null")
      assert(json.fromJson("null", optionType) == None)
      assertThrows[ForyJsonException](json.fromJson("1", optionType))
      val values = roundTrip(json, List((), ()), listType)
      assert(values.asInstanceOf[List[AnyRef]].forall(_ eq scala.runtime.BoxedUnit.UNIT))
      assert(json.toJson(values, listType) == "[null,null]")
      assert(json.toJson(Array((), ()), arrayType) == "[null,null]")
      assert(new String(json.toJsonBytes(Array((), ()), arrayType), UTF_8) == "[null,null]")
      for (array <- Seq(
          json.fromJson("[null,null]", arrayType),
          json.fromJson("[null,null]".getBytes(UTF_8), arrayType)
        )) {
        assert(array.length == 2)
        assert(array.asInstanceOf[Array[AnyRef]].forall(_ eq scala.runtime.BoxedUnit.UNIT))
      }
      val box = roundTrip(json, Box(()), boxType)
      assert(box.asInstanceOf[Box[AnyRef]].value eq scala.runtime.BoxedUnit.UNIT)
      assert(roundTrip(json, List(Some(()), None), nestedType) == List(None, None))
    }
  }

  test("Unit case-class fields") {
    for (json <- runtimes) {
      val value = UnitFields((), Some(()), List(()), Array(()))
      val text = """{"option":null,"values":[null],"array":[null],"value":null}"""
      assert(json.toJson(value) == text)
      assert(new String(json.toJsonBytes(value), UTF_8) == text)
      for (decoded <- Seq(
          json.fromJson(text, classOf[UnitFields]),
          json.fromJson(text.getBytes(UTF_8), classOf[UnitFields])
        )) {
        assert(decoded.productElement(0) == (()))
        assert(decoded.option == None)
        assert(decoded.values.asInstanceOf[List[AnyRef]].head eq scala.runtime.BoxedUnit.UNIT)
        assert(decoded.array.asInstanceOf[Array[AnyRef]].head eq scala.runtime.BoxedUnit.UNIT)
      }
      assert(json.fromJson("{}", classOf[UnitValue]) == UnitValue(()))
      assertThrows[ForyJsonException](json.fromJson("""{"value":1}""", classOf[UnitValue]))
    }
  }

  test("singletons") {
    for (json <- runtimes) {
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
      val missing = json.fromJson("""{"f1":1}""", classOf[Wide])
      assert(missing.f1 == 1 && missing.f2 == 0 && missing.f23 == 0 && missing.f24 == 24)

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

  test("case class constructor shapes") {
    for (json <- runtimes) {
      // Scala 2 exposes this private constructor and its apply as public bytecode; Scala 3
      // hides apply, so JSON cannot identify a supported public primary constructor there.
      if (classOf[PrivateConstructor].getMethods.exists(_.getName == "apply")) {
        assert(json.fromJson("""{"value":1}""", classOf[PrivateConstructor]).value == 1)
      } else {
        assertThrows[ForyJsonException](json.fromJson("""{"value":1}""", classOf[PrivateConstructor]))
      }
      assertThrows[ForyJsonException](json.fromJson("""{"value":1}""", classOf[AmbiguousConstructor]))
      val secondary = new SecondaryConstructor("2")
      assert(json.fromJson(json.toJson(secondary), classOf[SecondaryConstructor]) == secondary)
      val inherited = InheritedValue("中")
      assert(json.fromJson(json.toJsonBytes(inherited), classOf[InheritedValue]) == inherited)
      assert(json.fromJson("""{"value":1}""", classOf[Box[_]]).value == 1L)
    }
    val bounded = ForyJsonScala.builder().maxDepth(2).build()
    val nested = """{"value":1,"next":{"value":2,"next":{"value":3,"next":null}}}"""
    assertThrows[ForyJsonException](bounded.fromJson(nested, classOf[Node]))
    assert(bounded.fromJson("""{"value":4,"next":null}""", classOf[Node]) == Node(4, None))
  }

  test("algebraic fields with codegen") {
    val typeRef = ScalaTypeRef[AlgebraicFields[Int]]
    val value = AlgebraicFields(
      "中", scala.collection.immutable.Queue(1, 2), scala.collection.mutable.ArraySeq(3, 4),
      (5, 6), Some(7), Right(8), NumericRange.inclusive(1, 3, 1))
    for (json <- runtimes) {
      assert(roundTrip(json, value, typeRef) == value)
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

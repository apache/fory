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

package org.apache.fory.json.scala.internal

import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.annotation.JsonCodec
import org.apache.fory.json.codec.CompositeJsonCodec
import org.apache.fory.json.reader.{JsonReader, Latin1JsonReader, Utf16JsonReader, Utf8JsonReader}
import org.apache.fory.json.resolver.{JsonTypeInfo, JsonTypeResolver}
import org.apache.fory.json.writer.{StringJsonWriter, Utf8JsonWriter}
import org.apache.fory.reflect.TypeRef
import org.apache.fory.serializer.GraphMemoryEstimates

import scala.collection.immutable.NumericRange
import scala.math.Integral

private[scala] final class ScalaNumericRangeCodec(exclusive: Boolean, runtimeType: Boolean)
    extends CompositeJsonCodec[NumericRange[Any]] {
  private val ownerBytes = GraphMemoryEstimates.shallowObjectBytes(
    if (exclusive) classOf[NumericRange.Exclusive[_]] else classOf[NumericRange.Inclusive[_]]
  )
  private var elementInfo: JsonTypeInfo = _
  private var arithmetic: RangeArithmetic = _

  override def resolveTypes(typeRef: TypeRef[_], resolver: JsonTypeResolver): Unit = {
    bind(typeRef, resolver, null)
  }

  override def resolveTypes(
      typeRef: TypeRef[_],
      resolver: JsonTypeResolver,
      childCodecs: JsonCodec
  ): Unit = {
    ScalaTypeSupport.requireElementCodec(childCodecs, "NumericRange")
    bind(typeRef, resolver, childCodecs)
  }

  private def bind(
      typeRef: TypeRef[_],
      resolver: JsonTypeResolver,
      childCodecs: JsonCodec
  ): Unit = {
    if (runtimeType && !typeRef.getType.isInstanceOf[java.lang.reflect.ParameterizedType]) {
      arithmetic = null
      elementInfo = resolver.getTypeInfo(classOf[Object], classOf[Object])
      return
    }
    val arguments = ScalaTypeSupport.arguments(typeRef, 1, "NumericRange")
    val elementType = arguments(0)
    val rawType = ScalaTypeSupport.rawType(elementType)
    arithmetic = RangeArithmetic.forType(rawType, typeRef)
    elementInfo =
      if (childCodecs == null) resolver.getTypeInfo(elementType, rawType)
      else resolver.getTypeInfo(elementType, rawType, childCodecs.elementCodec())
  }

  override def writeString(writer: StringJsonWriter, value: NumericRange[Any]): Unit = {
    if (value == null) {
      writer.writeNull()
      return
    }
    val codec = elementInfo.stringWriter()
    val iterator = value.iterator
    writer.writeArrayStart()
    var index = 0
    while (iterator.hasNext) {
      writer.writeComma(index)
      codec.writeString(writer, iterator.next())
      index += 1
    }
    writer.writeArrayEnd()
  }

  override def writeUtf8(writer: Utf8JsonWriter, value: NumericRange[Any]): Unit = {
    if (value == null) {
      writer.writeNull()
      return
    }
    val codec = elementInfo.utf8Writer()
    val iterator = value.iterator
    writer.writeArrayStart()
    var index = 0
    while (iterator.hasNext) {
      writer.writeComma(index)
      codec.writeUtf8(writer, iterator.next())
      index += 1
    }
    writer.writeArrayEnd()
  }

  override def readLatin1(reader: Latin1JsonReader): NumericRange[Any] = {
    if (reader.tryReadNullToken()) return null
    requireArithmetic()
    reader.enterDepth()
    reader.expectNextToken('[')
    if (reader.consumeNextToken(']')) {
      reader.reserveGraphMemory(ownerBytes)
      val empty = arithmetic.empty(exclusive)
      reader.exitDepth()
      return empty
    }
    val codec = elementInfo.latin1Reader()
    val first = codec.readLatin1(reader)
    if (!reader.consumeNextCommaOrEndArray()) {
      reader.reserveGraphMemory(ownerBytes)
      val single = arithmetic.single(first, exclusive)
      reader.exitDepth()
      return single
    }
    var current = codec.readLatin1(reader)
    val step = arithmetic.subtract(current, first)
    if (arithmetic.isZero(step)) throw invalidRange()
    while (reader.consumeNextCommaOrEndArray()) {
      val next = codec.readLatin1(reader)
      if (!arithmetic.equal(arithmetic.subtract(next, current), step)) throw invalidRange()
      current = next
    }
    reader.reserveGraphMemory(ownerBytes)
    val result = arithmetic.create(first, current, step, exclusive)
    reader.exitDepth()
    result
  }

  override def readUtf16(reader: Utf16JsonReader): NumericRange[Any] = {
    if (reader.tryReadNullToken()) return null
    requireArithmetic()
    reader.enterDepth()
    reader.expectNextToken('[')
    if (reader.consumeNextToken(']')) {
      reader.reserveGraphMemory(ownerBytes)
      val empty = arithmetic.empty(exclusive)
      reader.exitDepth()
      return empty
    }
    val codec = elementInfo.utf16Reader()
    val first = codec.readUtf16(reader)
    if (!reader.consumeNextCommaOrEndArray()) {
      reader.reserveGraphMemory(ownerBytes)
      val single = arithmetic.single(first, exclusive)
      reader.exitDepth()
      return single
    }
    var current = codec.readUtf16(reader)
    val step = arithmetic.subtract(current, first)
    if (arithmetic.isZero(step)) throw invalidRange()
    while (reader.consumeNextCommaOrEndArray()) {
      val next = codec.readUtf16(reader)
      if (!arithmetic.equal(arithmetic.subtract(next, current), step)) throw invalidRange()
      current = next
    }
    reader.reserveGraphMemory(ownerBytes)
    val result = arithmetic.create(first, current, step, exclusive)
    reader.exitDepth()
    result
  }

  override def readUtf8(reader: Utf8JsonReader): NumericRange[Any] = {
    if (reader.tryReadNullToken()) return null
    requireArithmetic()
    reader.enterDepth()
    reader.expectNextToken('[')
    if (reader.consumeNextToken(']')) {
      reader.reserveGraphMemory(ownerBytes)
      val empty = arithmetic.empty(exclusive)
      reader.exitDepth()
      return empty
    }
    val codec = elementInfo.utf8Reader()
    val first = codec.readUtf8(reader)
    if (!reader.consumeNextCommaOrEndArray()) {
      reader.reserveGraphMemory(ownerBytes)
      val single = arithmetic.single(first, exclusive)
      reader.exitDepth()
      return single
    }
    var current = codec.readUtf8(reader)
    val step = arithmetic.subtract(current, first)
    if (arithmetic.isZero(step)) throw invalidRange()
    while (reader.consumeNextCommaOrEndArray()) {
      val next = codec.readUtf8(reader)
      if (!arithmetic.equal(arithmetic.subtract(next, current), step)) throw invalidRange()
      current = next
    }
    reader.reserveGraphMemory(ownerBytes)
    val result = arithmetic.create(first, current, step, exclusive)
    reader.exitDepth()
    result
  }

  private def requireArithmetic(): Unit = {
    if (arithmetic == null)
      throw new ForyJsonException("Raw NumericRange is write-only; decoding requires TypeRef[NumericRange[T]]")
  }

  private def invalidRange(): ForyJsonException =
    new ForyJsonException("Scala NumericRange JSON must be a non-overflowing arithmetic progression")
}

private sealed abstract class RangeArithmetic(val integral: Integral[Any]) {
  final def equal(left: Any, right: Any): Boolean = integral.equiv(left, right)
  final def isZero(value: Any): Boolean = integral.equiv(value, integral.zero)
  def subtract(left: Any, right: Any): Any
  def add(left: Any, right: Any): Any

  final def empty(exclusive: Boolean): NumericRange[Any] = {
    if (exclusive) NumericRange(integral.zero, integral.zero, integral.one)(integral)
    else NumericRange.inclusive(integral.one, integral.zero, integral.one)(integral)
  }

  final def single(value: Any, exclusive: Boolean): NumericRange[Any] = {
    if (exclusive) NumericRange(value, add(value, integral.one), integral.one)(integral)
    else NumericRange.inclusive(value, value, integral.one)(integral)
  }

  final def create(
      first: Any,
      last: Any,
      step: Any,
      exclusive: Boolean
  ): NumericRange[Any] = {
    if (exclusive) NumericRange(first, add(last, step), step)(integral)
    else NumericRange.inclusive(first, last, step)(integral)
  }
}

private object RangeArithmetic {
  private object ByteArithmetic
      extends RangeArithmetic(scala.math.Numeric.ByteIsIntegral.asInstanceOf[Integral[Any]]) {
    override def subtract(left: Any, right: Any): Any = checkedByte(left.asInstanceOf[Byte].toInt - right.asInstanceOf[Byte].toInt)
    override def add(left: Any, right: Any): Any = checkedByte(left.asInstanceOf[Byte].toInt + right.asInstanceOf[Byte].toInt)
  }

  private object ShortArithmetic
      extends RangeArithmetic(scala.math.Numeric.ShortIsIntegral.asInstanceOf[Integral[Any]]) {
    override def subtract(left: Any, right: Any): Any = checkedShort(left.asInstanceOf[Short].toInt - right.asInstanceOf[Short].toInt)
    override def add(left: Any, right: Any): Any = checkedShort(left.asInstanceOf[Short].toInt + right.asInstanceOf[Short].toInt)
  }

  private object IntArithmetic
      extends RangeArithmetic(scala.math.Numeric.IntIsIntegral.asInstanceOf[Integral[Any]]) {
    override def subtract(left: Any, right: Any): Any =
      try Math.subtractExact(left.asInstanceOf[Int], right.asInstanceOf[Int])
      catch { case _: ArithmeticException => throw invalid() }
    override def add(left: Any, right: Any): Any =
      try Math.addExact(left.asInstanceOf[Int], right.asInstanceOf[Int])
      catch { case _: ArithmeticException => throw invalid() }
  }

  private object LongArithmetic
      extends RangeArithmetic(scala.math.Numeric.LongIsIntegral.asInstanceOf[Integral[Any]]) {
    override def subtract(left: Any, right: Any): Any =
      try Math.subtractExact(left.asInstanceOf[Long], right.asInstanceOf[Long])
      catch { case _: ArithmeticException => throw invalid() }
    override def add(left: Any, right: Any): Any =
      try Math.addExact(left.asInstanceOf[Long], right.asInstanceOf[Long])
      catch { case _: ArithmeticException => throw invalid() }
  }

  private object CharArithmetic
      extends RangeArithmetic(scala.math.Numeric.CharIsIntegral.asInstanceOf[Integral[Any]]) {
    override def subtract(left: Any, right: Any): Any = checkedChar(left.asInstanceOf[Char].toInt - right.asInstanceOf[Char].toInt)
    override def add(left: Any, right: Any): Any = checkedChar(left.asInstanceOf[Char].toInt + right.asInstanceOf[Char].toInt)
  }

  private object BigIntArithmetic
      extends RangeArithmetic(scala.math.Numeric.BigIntIsIntegral.asInstanceOf[Integral[Any]]) {
    override def subtract(left: Any, right: Any): Any = left.asInstanceOf[BigInt] - right.asInstanceOf[BigInt]
    override def add(left: Any, right: Any): Any = left.asInstanceOf[BigInt] + right.asInstanceOf[BigInt]
  }

  def forType(rawType: Class[_], owner: TypeRef[_]): RangeArithmetic = {
    if (rawType == java.lang.Byte.TYPE || rawType == classOf[java.lang.Byte]) ByteArithmetic
    else if (rawType == java.lang.Short.TYPE || rawType == classOf[java.lang.Short]) ShortArithmetic
    else if (rawType == java.lang.Integer.TYPE || rawType == classOf[java.lang.Integer]) IntArithmetic
    else if (rawType == java.lang.Long.TYPE || rawType == classOf[java.lang.Long]) LongArithmetic
    else if (rawType == java.lang.Character.TYPE || rawType == classOf[java.lang.Character]) CharArithmetic
    else if (rawType == classOf[BigInt]) BigIntArithmetic
    else throw ScalaTypeSupport.unsupported(owner, "NumericRange element type has no built-in Integral")
  }

  private def checkedByte(value: Int): Byte = {
    if (value < Byte.MinValue || value > Byte.MaxValue) throw invalid()
    value.toByte
  }

  private def checkedShort(value: Int): Short = {
    if (value < Short.MinValue || value > Short.MaxValue) throw invalid()
    value.toShort
  }

  private def checkedChar(value: Int): Char = {
    if (value < Char.MinValue.toInt || value > Char.MaxValue.toInt) throw invalid()
    value.toChar
  }

  private def invalid(): ForyJsonException =
    new ForyJsonException("Scala NumericRange arithmetic overflow")
}

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

import java.util.concurrent.TimeUnit

import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.codec.AbstractJsonValueCodec
import org.apache.fory.json.reader.JsonReader
import org.apache.fory.json.writer.JsonWriter
import org.apache.fory.serializer.GraphMemoryEstimates

private[scala] object ScalaBigIntCodec extends AbstractJsonValueCodec[BigInt] {
  private val OwnerBytes = GraphMemoryEstimates.shallowObjectBytes(classOf[BigInt])

  override def write(writer: JsonWriter, value: BigInt): Unit = {
    if (value == null) writer.writeNull() else writer.writeBigInteger(value.bigInteger)
  }

  override def read(reader: JsonReader): BigInt = {
    if (reader.tryReadNullToken()) return null
    val value = reader.readBigInteger()
    reader.reserveGraphMemory(OwnerBytes)
    BigInt(value)
  }
}

private[scala] object ScalaBigDecimalCodec extends AbstractJsonValueCodec[BigDecimal] {
  private val OwnerBytes = GraphMemoryEstimates.shallowObjectBytes(classOf[BigDecimal])

  override def write(writer: JsonWriter, value: BigDecimal): Unit = {
    if (value == null) writer.writeNull() else writer.writeBigDecimal(value.bigDecimal)
  }

  override def read(reader: JsonReader): BigDecimal = {
    if (reader.tryReadNullToken()) return null
    val value = reader.readBigDecimal()
    reader.reserveGraphMemory(OwnerBytes)
    BigDecimal(value)
  }
}

private[scala] object ScalaStringBuilderCodec
    extends AbstractJsonValueCodec[scala.collection.mutable.StringBuilder] {
  private val OwnerBytes =
    GraphMemoryEstimates.shallowObjectBytes(classOf[scala.collection.mutable.StringBuilder]) +
      GraphMemoryEstimates.shallowObjectBytes(classOf[java.lang.StringBuilder])
  private val ArrayHeaderBytes = GraphMemoryEstimates.objectArrayBytes()

  override def write(
      writer: JsonWriter,
      value: scala.collection.mutable.StringBuilder
  ): Unit = {
    if (value == null) writer.writeNull() else writer.writeString(value)
  }

  override def read(reader: JsonReader): scala.collection.mutable.StringBuilder = {
    if (reader.tryReadNullToken()) return null
    val value = reader.readString()
    val capacityBytes = Math.multiplyExact(Math.addExact(value.length, 16), Character.BYTES)
    reader.reserveGraphMemory(Math.addExact(OwnerBytes, Math.addExact(ArrayHeaderBytes, capacityBytes)))
    new scala.collection.mutable.StringBuilder(value)
  }
}

private[scala] final class ScalaRangeCodec(exclusive: Boolean)
    extends AbstractJsonValueCodec[Range] {
  private val OwnerBytes = GraphMemoryEstimates.shallowObjectBytes(classOf[Range])

  override def write(writer: JsonWriter, value: Range): Unit = {
    if (value == null) {
      writer.writeNull()
      return
    }
    writer.writeArrayStart()
    val iterator = value.iterator
    var index = 0
    while (iterator.hasNext) {
      writer.writeComma(index)
      writer.writeInt(iterator.next())
      index += 1
    }
    writer.writeArrayEnd()
  }

  override def read(reader: JsonReader): Range = {
    if (reader.tryReadNullToken()) return null
    reader.enterDepth()
    reader.expectNextToken('[')
    if (reader.consumeNextToken(']')) {
      reader.reserveGraphMemory(OwnerBytes)
      reader.exitDepth()
      return if (exclusive) Range(0, 0) else Range.inclusive(0, -1)
    }
    val first = reader.readInt()
    if (!reader.consumeNextCommaOrEndArray()) {
      reader.reserveGraphMemory(OwnerBytes)
      reader.exitDepth()
      return if (exclusive) {
        try Range(first, Math.addExact(first, 1))
        catch { case _: ArithmeticException => throw invalidRange() }
      } else Range.inclusive(first, first)
    }
    var current = reader.readInt()
    val stepLong = current.toLong - first.toLong
    if (stepLong == 0L || stepLong < Int.MinValue || stepLong > Int.MaxValue) throw invalidRange()
    val step = stepLong.toInt
    while (reader.consumeNextCommaOrEndArray()) {
      val next = reader.readInt()
      if (next.toLong - current.toLong != stepLong) throw invalidRange()
      current = next
    }
    reader.reserveGraphMemory(OwnerBytes)
    val value =
      try {
        if (exclusive) Range(first, Math.addExact(current, step), step)
        else Range.inclusive(first, current, step)
      } catch {
        case _: ArithmeticException | _: IllegalArgumentException => throw invalidRange()
      }
    reader.exitDepth()
    value
  }

  private def invalidRange(): ForyJsonException =
    new ForyJsonException("Scala Range JSON must be a non-overflowing arithmetic progression")
}

private[scala] final class ScalaDurationCodec(finiteOnly: Boolean)
    extends AbstractJsonValueCodec[scala.concurrent.duration.Duration] {
  private val OwnerBytes = GraphMemoryEstimates.shallowObjectBytes(
    classOf[scala.concurrent.duration.FiniteDuration]
  )

  override def write(writer: JsonWriter, value: scala.concurrent.duration.Duration): Unit = {
    if (value == null) {
      writer.writeNull()
      return
    }
    writer.writeObjectStart()
    value match {
      case finite: scala.concurrent.duration.FiniteDuration =>
        writer.writeFieldName("length")
        writer.writeLong(finite.length)
        writer.writeComma(1)
        writer.writeFieldName("unit")
        writer.writeString(finite.unit.name())
      case scala.concurrent.duration.Duration.Inf =>
        writer.writeFieldName("special")
        writer.writeString("INF")
      case scala.concurrent.duration.Duration.MinusInf =>
        writer.writeFieldName("special")
        writer.writeString("MINUS_INF")
      case scala.concurrent.duration.Duration.Undefined =>
        writer.writeFieldName("special")
        writer.writeString("UNDEFINED")
      case _ => throw invalidDuration()
    }
    writer.writeObjectEnd()
  }

  override def read(reader: JsonReader): scala.concurrent.duration.Duration = {
    if (reader.tryReadNullToken()) return null
    reader.enterDepth()
    reader.expectNextToken('{')
    var length = 0L
    var unit: TimeUnit = null
    var special: String = null
    var seenLength = false
    var seenUnit = false
    var seenSpecial = false
    if (!reader.consumeNextToken('}')) {
      var more = true
      while (more) {
        val name = reader.readFieldName()
        reader.expectNextToken(':')
        name match {
          case "length" if !seenLength =>
            length = reader.readLong()
            seenLength = true
          case "unit" if !seenUnit =>
            val name = reader.readString()
            try unit = TimeUnit.valueOf(name)
            catch { case _: IllegalArgumentException => throw invalidDuration() }
            seenUnit = true
          case "special" if !seenSpecial =>
            special = reader.readString()
            seenSpecial = true
          case _ => throw invalidDuration()
        }
        more = reader.consumeNextCommaOrEndObject()
      }
    }
    val value =
      if (seenSpecial && !seenLength && !seenUnit && !finiteOnly) {
        special match {
          case "INF"       => scala.concurrent.duration.Duration.Inf
          case "MINUS_INF" => scala.concurrent.duration.Duration.MinusInf
          case "UNDEFINED" => scala.concurrent.duration.Duration.Undefined
          case _           => throw invalidDuration()
        }
      } else if (!seenSpecial && seenLength && seenUnit) {
        reader.reserveGraphMemory(OwnerBytes)
        new scala.concurrent.duration.FiniteDuration(length, unit)
      } else throw invalidDuration()
    reader.exitDepth()
    value
  }

  private def invalidDuration(): ForyJsonException =
    new ForyJsonException("Invalid Scala Duration JSON object")
}

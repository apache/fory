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

package org.apache.fory.json.kotlin

import kotlin.ranges.CharProgression
import kotlin.ranges.IntProgression
import kotlin.ranges.LongProgression
import kotlin.ranges.UIntProgression
import kotlin.ranges.ULongProgression
import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.codec.JsonValueCodec
import org.apache.fory.json.meta.JsonFieldNameHash
import org.apache.fory.json.reader.JsonReader
import org.apache.fory.json.reader.Latin1JsonReader
import org.apache.fory.json.reader.Utf16JsonReader
import org.apache.fory.json.reader.Utf8JsonReader
import org.apache.fory.json.writer.JsonWriter
import org.apache.fory.json.writer.StringJsonWriter
import org.apache.fory.json.writer.Utf8JsonWriter
import org.apache.fory.reflect.TypeRef
import org.apache.fory.serializer.GraphMemoryEstimates

/**
 * Primitive progression codecs which validate the canonical stored last element before allocation.
 */
@OptIn(ExperimentalUnsignedTypes::class)
internal object KotlinProgressionCodecs {
  private const val FIRST = 0
  private const val LAST = 1
  private const val STEP = 2
  private const val ALL_FIELDS = 0b111
  private val firstHash = JsonFieldNameHash.hash("first")
  private val lastHash = JsonFieldNameHash.hash("last")
  private val stepHash = JsonFieldNameHash.hash("step")

  fun create(type: TypeRef<*>): JsonValueCodec<*>? =
    when (type.rawType) {
      CharProgression::class.java -> CharProgressionCodec
      IntProgression::class.java -> IntProgressionCodec
      LongProgression::class.java -> LongProgressionCodec
      UIntProgression::class.java -> UIntProgressionCodec
      ULongProgression::class.java -> ULongProgressionCodec
      else -> null
    }

  private object CharProgressionCodec : JsonValueCodec<CharProgression> {
    private val ownerBytes = GraphMemoryEstimates.shallowObjectBytes(CharProgression::class.java)

    override fun writeString(writer: StringJsonWriter, value: CharProgression?) =
      write(writer, value)

    override fun writeUtf8(writer: Utf8JsonWriter, value: CharProgression?) = write(writer, value)

    override fun readLatin1(reader: Latin1JsonReader): CharProgression? = read(reader)

    override fun readUtf16(reader: Utf16JsonReader): CharProgression? = read(reader)

    override fun readUtf8(reader: Utf8JsonReader): CharProgression? = read(reader)

    private fun write(writer: JsonWriter, value: CharProgression?) {
      if (value == null) {
        writer.writeNull()
        return
      }
      writer.writeObjectStart()
      writer.writeFieldName("first")
      writer.writeChar(value.first)
      writer.writeComma(1)
      writer.writeFieldName("last")
      writer.writeChar(value.last)
      writer.writeComma(2)
      writer.writeFieldName("step")
      writer.writeInt(value.step)
      writer.writeObjectEnd()
    }

    private fun read(reader: JsonReader): CharProgression? {
      if (reader.tryReadNullToken()) return null
      reader.enterDepth()
      reader.expectNextToken('{')
      var first = 0.toChar()
      var last = 0.toChar()
      var step = 0
      var seen = 0
      if (!reader.consumeNextToken('}')) {
        do {
          val index = readField(reader, seen)
          seen = seen or (1 shl index)
          when (index) {
            FIRST -> first = reader.readChar()
            LAST -> last = reader.readChar()
            STEP -> step = reader.readInt()
          }
        } while (reader.consumeNextToken(','))
        reader.expectNextToken('}')
      }
      requireFields(seen)
      validateStep(step)
      if (normalizedLast(first.code, last.code, step) != last.code) invalidLast()
      reader.exitDepth()
      reader.reserveGraphMemory(ownerBytes)
      return CharProgression.fromClosedRange(first, last, step)
    }
  }

  private object IntProgressionCodec : JsonValueCodec<IntProgression> {
    private val ownerBytes = GraphMemoryEstimates.shallowObjectBytes(IntProgression::class.java)

    override fun writeString(writer: StringJsonWriter, value: IntProgression?) =
      write(writer, value)

    override fun writeUtf8(writer: Utf8JsonWriter, value: IntProgression?) = write(writer, value)

    override fun readLatin1(reader: Latin1JsonReader): IntProgression? = read(reader)

    override fun readUtf16(reader: Utf16JsonReader): IntProgression? = read(reader)

    override fun readUtf8(reader: Utf8JsonReader): IntProgression? = read(reader)

    private fun write(writer: JsonWriter, value: IntProgression?) {
      if (value == null) {
        writer.writeNull()
        return
      }
      writeHeader(writer)
      writer.writeInt(value.first)
      writeLast(writer)
      writer.writeInt(value.last)
      writeStep(writer)
      writer.writeInt(value.step)
      writer.writeObjectEnd()
    }

    private fun read(reader: JsonReader): IntProgression? {
      if (reader.tryReadNullToken()) return null
      reader.enterDepth()
      reader.expectNextToken('{')
      var first = 0
      var last = 0
      var step = 0
      var seen = 0
      if (!reader.consumeNextToken('}')) {
        do {
          val index = readField(reader, seen)
          seen = seen or (1 shl index)
          when (index) {
            FIRST -> first = reader.readInt()
            LAST -> last = reader.readInt()
            STEP -> step = reader.readInt()
          }
        } while (reader.consumeNextToken(','))
        reader.expectNextToken('}')
      }
      requireFields(seen)
      validateStep(step)
      if (normalizedLast(first, last, step) != last) invalidLast()
      reader.exitDepth()
      reader.reserveGraphMemory(ownerBytes)
      return IntProgression.fromClosedRange(first, last, step)
    }
  }

  private object LongProgressionCodec : JsonValueCodec<LongProgression> {
    private val ownerBytes = GraphMemoryEstimates.shallowObjectBytes(LongProgression::class.java)

    override fun writeString(writer: StringJsonWriter, value: LongProgression?) =
      write(writer, value)

    override fun writeUtf8(writer: Utf8JsonWriter, value: LongProgression?) = write(writer, value)

    override fun readLatin1(reader: Latin1JsonReader): LongProgression? = read(reader)

    override fun readUtf16(reader: Utf16JsonReader): LongProgression? = read(reader)

    override fun readUtf8(reader: Utf8JsonReader): LongProgression? = read(reader)

    private fun write(writer: JsonWriter, value: LongProgression?) {
      if (value == null) {
        writer.writeNull()
        return
      }
      writeHeader(writer)
      writer.writeLong(value.first)
      writeLast(writer)
      writer.writeLong(value.last)
      writeStep(writer)
      writer.writeLong(value.step)
      writer.writeObjectEnd()
    }

    private fun read(reader: JsonReader): LongProgression? {
      if (reader.tryReadNullToken()) return null
      reader.enterDepth()
      reader.expectNextToken('{')
      var first = 0L
      var last = 0L
      var step = 0L
      var seen = 0
      if (!reader.consumeNextToken('}')) {
        do {
          val index = readField(reader, seen)
          seen = seen or (1 shl index)
          when (index) {
            FIRST -> first = reader.readLong()
            LAST -> last = reader.readLong()
            STEP -> step = reader.readLong()
          }
        } while (reader.consumeNextToken(','))
        reader.expectNextToken('}')
      }
      requireFields(seen)
      validateStep(step)
      if (normalizedLast(first, last, step) != last) invalidLast()
      reader.exitDepth()
      reader.reserveGraphMemory(ownerBytes)
      return LongProgression.fromClosedRange(first, last, step)
    }
  }

  private object UIntProgressionCodec : JsonValueCodec<UIntProgression> {
    private val ownerBytes = GraphMemoryEstimates.shallowObjectBytes(UIntProgression::class.java)

    override fun writeString(writer: StringJsonWriter, value: UIntProgression?) =
      write(writer, value)

    override fun writeUtf8(writer: Utf8JsonWriter, value: UIntProgression?) = write(writer, value)

    override fun readLatin1(reader: Latin1JsonReader): UIntProgression? = read(reader)

    override fun readUtf16(reader: Utf16JsonReader): UIntProgression? = read(reader)

    override fun readUtf8(reader: Utf8JsonReader): UIntProgression? = read(reader)

    private fun write(writer: JsonWriter, value: UIntProgression?) {
      if (value == null) {
        writer.writeNull()
        return
      }
      writeHeader(writer)
      writer.writeUnsignedInt(value.first.toInt())
      writeLast(writer)
      writer.writeUnsignedInt(value.last.toInt())
      writeStep(writer)
      writer.writeInt(value.step)
      writer.writeObjectEnd()
    }

    private fun read(reader: JsonReader): UIntProgression? {
      if (reader.tryReadNullToken()) return null
      reader.enterDepth()
      reader.expectNextToken('{')
      var first = 0u
      var last = 0u
      var step = 0
      var seen = 0
      if (!reader.consumeNextToken('}')) {
        do {
          val index = readField(reader, seen)
          seen = seen or (1 shl index)
          when (index) {
            FIRST -> first = reader.readUnsignedInt().toUInt()
            LAST -> last = reader.readUnsignedInt().toUInt()
            STEP -> step = reader.readInt()
          }
        } while (reader.consumeNextToken(','))
        reader.expectNextToken('}')
      }
      requireFields(seen)
      validateStep(step)
      if (normalizedLast(first, last, step) != last) invalidLast()
      reader.exitDepth()
      reader.reserveGraphMemory(ownerBytes)
      return UIntProgression.fromClosedRange(first, last, step)
    }
  }

  private object ULongProgressionCodec : JsonValueCodec<ULongProgression> {
    private val ownerBytes = GraphMemoryEstimates.shallowObjectBytes(ULongProgression::class.java)

    override fun writeString(writer: StringJsonWriter, value: ULongProgression?) =
      write(writer, value)

    override fun writeUtf8(writer: Utf8JsonWriter, value: ULongProgression?) = write(writer, value)

    override fun readLatin1(reader: Latin1JsonReader): ULongProgression? = read(reader)

    override fun readUtf16(reader: Utf16JsonReader): ULongProgression? = read(reader)

    override fun readUtf8(reader: Utf8JsonReader): ULongProgression? = read(reader)

    private fun write(writer: JsonWriter, value: ULongProgression?) {
      if (value == null) {
        writer.writeNull()
        return
      }
      writeHeader(writer)
      writer.writeUnsignedLong(value.first.toLong())
      writeLast(writer)
      writer.writeUnsignedLong(value.last.toLong())
      writeStep(writer)
      writer.writeLong(value.step)
      writer.writeObjectEnd()
    }

    private fun read(reader: JsonReader): ULongProgression? {
      if (reader.tryReadNullToken()) return null
      reader.enterDepth()
      reader.expectNextToken('{')
      var first = 0uL
      var last = 0uL
      var step = 0L
      var seen = 0
      if (!reader.consumeNextToken('}')) {
        do {
          val index = readField(reader, seen)
          seen = seen or (1 shl index)
          when (index) {
            FIRST -> first = reader.readUnsignedLong().toULong()
            LAST -> last = reader.readUnsignedLong().toULong()
            STEP -> step = reader.readLong()
          }
        } while (reader.consumeNextToken(','))
        reader.expectNextToken('}')
      }
      requireFields(seen)
      validateStep(step)
      if (normalizedLast(first, last, step) != last) invalidLast()
      reader.exitDepth()
      reader.reserveGraphMemory(ownerBytes)
      return ULongProgression.fromClosedRange(first, last, step)
    }
  }

  private fun readField(reader: JsonReader, seen: Int): Int {
    val hash = reader.readFieldNameHash()
    val index =
      when (hash) {
        firstHash -> FIRST
        lastHash -> LAST
        stepHash -> STEP
        else -> unknownField()
      }
    if (seen and (1 shl index) != 0) duplicateField()
    reader.expectNextToken(':')
    return index
  }

  private fun writeHeader(writer: JsonWriter) {
    writer.writeObjectStart()
    writer.writeFieldName("first")
  }

  private fun writeLast(writer: JsonWriter) {
    writer.writeComma(1)
    writer.writeFieldName("last")
  }

  private fun writeStep(writer: JsonWriter) {
    writer.writeComma(2)
    writer.writeFieldName("step")
  }

  private fun requireFields(seen: Int) {
    if (seen != ALL_FIELDS) missingField()
  }

  private fun validateStep(step: Int) {
    if (step == 0 || step == Int.MIN_VALUE) invalidStep()
  }

  private fun validateStep(step: Long) {
    if (step == 0L || step == Long.MIN_VALUE) invalidStep()
  }

  private fun normalizedLast(first: Int, last: Int, step: Int): Int =
    if (step > 0) last - difference(last, first, step) else last + difference(first, last, -step)

  private fun normalizedLast(first: Long, last: Long, step: Long): Long =
    if (step > 0) last - difference(last, first, step) else last + difference(first, last, -step)

  private fun normalizedLast(first: UInt, last: UInt, step: Int): UInt =
    if (step > 0) last - difference(last, first, step.toUInt())
    else last + difference(first, last, (-step).toUInt())

  private fun normalizedLast(first: ULong, last: ULong, step: Long): ULong =
    if (step > 0) last - difference(last, first, step.toULong())
    else last + difference(first, last, (-step).toULong())

  private fun difference(a: Int, b: Int, divisor: Int): Int =
    Math.floorMod(Math.floorMod(a, divisor) - Math.floorMod(b, divisor), divisor)

  private fun difference(a: Long, b: Long, divisor: Long): Long =
    Math.floorMod(Math.floorMod(a, divisor) - Math.floorMod(b, divisor), divisor)

  private fun difference(a: UInt, b: UInt, divisor: UInt): UInt {
    val left = a % divisor
    val right = b % divisor
    return if (left >= right) left - right else left - right + divisor
  }

  private fun difference(a: ULong, b: ULong, divisor: ULong): ULong {
    val left = a % divisor
    val right = b % divisor
    return if (left >= right) left - right else left - right + divisor
  }

  private fun unknownField(): Nothing =
    throw ForyJsonException("Unknown Kotlin progression JSON field")

  private fun duplicateField(): Nothing =
    throw ForyJsonException("Duplicate Kotlin progression JSON field")

  private fun missingField(): Nothing =
    throw ForyJsonException("Kotlin progression JSON requires first, last, and step")

  private fun invalidStep(): Nothing =
    throw ForyJsonException("Kotlin progression step must be non-zero and not the minimum value")

  private fun invalidLast(): Nothing =
    throw ForyJsonException("Kotlin progression last must be normalized for first and step")
}

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

import kotlin.ranges.CharRange
import kotlin.ranges.IntRange
import kotlin.ranges.LongRange
import kotlin.ranges.UIntRange
import kotlin.ranges.ULongRange
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
 * Fixed primitive endpoint codecs for Kotlin's concrete range types.
 *
 * ClosedRange bridge getters return boxed Comparable values while these constructors and their
 * inherited progression storage use primitives. Direct scalar access keeps one unambiguous schema
 * and avoids boxing the endpoints.
 */
@OptIn(ExperimentalUnsignedTypes::class)
internal object KotlinRangeCodecs {
  private const val START = 0
  private const val END_INCLUSIVE = 1
  private const val ALL_FIELDS = 0b11
  private val startHash = JsonFieldNameHash.hash("start")
  private val endInclusiveHash = JsonFieldNameHash.hash("endInclusive")

  fun create(type: TypeRef<*>): JsonValueCodec<*>? =
    when (type.rawType) {
      CharRange::class.java -> CharRangeCodec
      IntRange::class.java -> IntRangeCodec
      LongRange::class.java -> LongRangeCodec
      UIntRange::class.java -> UIntRangeCodec
      ULongRange::class.java -> ULongRangeCodec
      else -> null
    }

  private object CharRangeCodec : JsonValueCodec<CharRange> {
    private val ownerBytes = GraphMemoryEstimates.shallowObjectBytes(CharRange::class.java)

    override fun writeString(writer: StringJsonWriter, value: CharRange?) = write(writer, value)

    override fun writeUtf8(writer: Utf8JsonWriter, value: CharRange?) = write(writer, value)

    override fun readLatin1(reader: Latin1JsonReader): CharRange? = read(reader)

    override fun readUtf16(reader: Utf16JsonReader): CharRange? = read(reader)

    override fun readUtf8(reader: Utf8JsonReader): CharRange? = read(reader)

    private fun write(writer: JsonWriter, value: CharRange?) {
      if (value == null) {
        writer.writeNull()
        return
      }
      writeHeader(writer)
      writer.writeChar(value.first)
      writeEndInclusive(writer)
      writer.writeChar(value.last)
      writer.writeObjectEnd()
    }

    private fun read(reader: JsonReader): CharRange? {
      if (reader.tryReadNullToken()) return null
      reader.enterDepth()
      reader.expectNextToken('{')
      var start = 0.toChar()
      var endInclusive = 0.toChar()
      var seen = 0
      if (!reader.consumeNextToken('}')) {
        do {
          val index = readField(reader, seen)
          seen = seen or (1 shl index)
          when (index) {
            START -> start = reader.readChar()
            END_INCLUSIVE -> endInclusive = reader.readChar()
          }
        } while (reader.consumeNextToken(','))
        reader.expectNextToken('}')
      }
      requireFields(seen)
      reader.exitDepth()
      reader.reserveGraphMemory(ownerBytes)
      return CharRange(start, endInclusive)
    }
  }

  private object IntRangeCodec : JsonValueCodec<IntRange> {
    private val ownerBytes = GraphMemoryEstimates.shallowObjectBytes(IntRange::class.java)

    override fun writeString(writer: StringJsonWriter, value: IntRange?) = write(writer, value)

    override fun writeUtf8(writer: Utf8JsonWriter, value: IntRange?) = write(writer, value)

    override fun readLatin1(reader: Latin1JsonReader): IntRange? = read(reader)

    override fun readUtf16(reader: Utf16JsonReader): IntRange? = read(reader)

    override fun readUtf8(reader: Utf8JsonReader): IntRange? = read(reader)

    private fun write(writer: JsonWriter, value: IntRange?) {
      if (value == null) {
        writer.writeNull()
        return
      }
      writeHeader(writer)
      writer.writeInt(value.first)
      writeEndInclusive(writer)
      writer.writeInt(value.last)
      writer.writeObjectEnd()
    }

    private fun read(reader: JsonReader): IntRange? {
      if (reader.tryReadNullToken()) return null
      reader.enterDepth()
      reader.expectNextToken('{')
      var start = 0
      var endInclusive = 0
      var seen = 0
      if (!reader.consumeNextToken('}')) {
        do {
          val index = readField(reader, seen)
          seen = seen or (1 shl index)
          when (index) {
            START -> start = reader.readInt()
            END_INCLUSIVE -> endInclusive = reader.readInt()
          }
        } while (reader.consumeNextToken(','))
        reader.expectNextToken('}')
      }
      requireFields(seen)
      reader.exitDepth()
      reader.reserveGraphMemory(ownerBytes)
      return IntRange(start, endInclusive)
    }
  }

  private object LongRangeCodec : JsonValueCodec<LongRange> {
    private val ownerBytes = GraphMemoryEstimates.shallowObjectBytes(LongRange::class.java)

    override fun writeString(writer: StringJsonWriter, value: LongRange?) = write(writer, value)

    override fun writeUtf8(writer: Utf8JsonWriter, value: LongRange?) = write(writer, value)

    override fun readLatin1(reader: Latin1JsonReader): LongRange? = read(reader)

    override fun readUtf16(reader: Utf16JsonReader): LongRange? = read(reader)

    override fun readUtf8(reader: Utf8JsonReader): LongRange? = read(reader)

    private fun write(writer: JsonWriter, value: LongRange?) {
      if (value == null) {
        writer.writeNull()
        return
      }
      writeHeader(writer)
      writer.writeLong(value.first)
      writeEndInclusive(writer)
      writer.writeLong(value.last)
      writer.writeObjectEnd()
    }

    private fun read(reader: JsonReader): LongRange? {
      if (reader.tryReadNullToken()) return null
      reader.enterDepth()
      reader.expectNextToken('{')
      var start = 0L
      var endInclusive = 0L
      var seen = 0
      if (!reader.consumeNextToken('}')) {
        do {
          val index = readField(reader, seen)
          seen = seen or (1 shl index)
          when (index) {
            START -> start = reader.readLong()
            END_INCLUSIVE -> endInclusive = reader.readLong()
          }
        } while (reader.consumeNextToken(','))
        reader.expectNextToken('}')
      }
      requireFields(seen)
      reader.exitDepth()
      reader.reserveGraphMemory(ownerBytes)
      return LongRange(start, endInclusive)
    }
  }

  private object UIntRangeCodec : JsonValueCodec<UIntRange> {
    private val ownerBytes = GraphMemoryEstimates.shallowObjectBytes(UIntRange::class.java)

    override fun writeString(writer: StringJsonWriter, value: UIntRange?) = write(writer, value)

    override fun writeUtf8(writer: Utf8JsonWriter, value: UIntRange?) = write(writer, value)

    override fun readLatin1(reader: Latin1JsonReader): UIntRange? = read(reader)

    override fun readUtf16(reader: Utf16JsonReader): UIntRange? = read(reader)

    override fun readUtf8(reader: Utf8JsonReader): UIntRange? = read(reader)

    private fun write(writer: JsonWriter, value: UIntRange?) {
      if (value == null) {
        writer.writeNull()
        return
      }
      writeHeader(writer)
      writer.writeUnsignedInt(value.first.toInt())
      writeEndInclusive(writer)
      writer.writeUnsignedInt(value.last.toInt())
      writer.writeObjectEnd()
    }

    private fun read(reader: JsonReader): UIntRange? {
      if (reader.tryReadNullToken()) return null
      reader.enterDepth()
      reader.expectNextToken('{')
      var start = 0u
      var endInclusive = 0u
      var seen = 0
      if (!reader.consumeNextToken('}')) {
        do {
          val index = readField(reader, seen)
          seen = seen or (1 shl index)
          when (index) {
            START -> start = reader.readUnsignedInt().toUInt()
            END_INCLUSIVE -> endInclusive = reader.readUnsignedInt().toUInt()
          }
        } while (reader.consumeNextToken(','))
        reader.expectNextToken('}')
      }
      requireFields(seen)
      reader.exitDepth()
      reader.reserveGraphMemory(ownerBytes)
      return UIntRange(start, endInclusive)
    }
  }

  private object ULongRangeCodec : JsonValueCodec<ULongRange> {
    private val ownerBytes = GraphMemoryEstimates.shallowObjectBytes(ULongRange::class.java)

    override fun writeString(writer: StringJsonWriter, value: ULongRange?) = write(writer, value)

    override fun writeUtf8(writer: Utf8JsonWriter, value: ULongRange?) = write(writer, value)

    override fun readLatin1(reader: Latin1JsonReader): ULongRange? = read(reader)

    override fun readUtf16(reader: Utf16JsonReader): ULongRange? = read(reader)

    override fun readUtf8(reader: Utf8JsonReader): ULongRange? = read(reader)

    private fun write(writer: JsonWriter, value: ULongRange?) {
      if (value == null) {
        writer.writeNull()
        return
      }
      writeHeader(writer)
      writer.writeUnsignedLong(value.first.toLong())
      writeEndInclusive(writer)
      writer.writeUnsignedLong(value.last.toLong())
      writer.writeObjectEnd()
    }

    private fun read(reader: JsonReader): ULongRange? {
      if (reader.tryReadNullToken()) return null
      reader.enterDepth()
      reader.expectNextToken('{')
      var start = 0uL
      var endInclusive = 0uL
      var seen = 0
      if (!reader.consumeNextToken('}')) {
        do {
          val index = readField(reader, seen)
          seen = seen or (1 shl index)
          when (index) {
            START -> start = reader.readUnsignedLong().toULong()
            END_INCLUSIVE -> endInclusive = reader.readUnsignedLong().toULong()
          }
        } while (reader.consumeNextToken(','))
        reader.expectNextToken('}')
      }
      requireFields(seen)
      reader.exitDepth()
      reader.reserveGraphMemory(ownerBytes)
      return ULongRange(start, endInclusive)
    }
  }

  private fun readField(reader: JsonReader, seen: Int): Int {
    val hash = reader.readFieldNameHash()
    val index =
      when (hash) {
        startHash -> START
        endInclusiveHash -> END_INCLUSIVE
        else -> unknownField()
      }
    if (seen and (1 shl index) != 0) duplicateField()
    reader.expectNextToken(':')
    return index
  }

  private fun writeHeader(writer: JsonWriter) {
    writer.writeObjectStart()
    writer.writeFieldName("start")
  }

  private fun writeEndInclusive(writer: JsonWriter) {
    writer.writeComma(1)
    writer.writeFieldName("endInclusive")
  }

  private fun requireFields(seen: Int) {
    if (seen != ALL_FIELDS) missingField()
  }

  private fun unknownField(): Nothing = throw ForyJsonException("Unknown Kotlin range JSON field")

  private fun duplicateField(): Nothing =
    throw ForyJsonException("Duplicate Kotlin range JSON field")

  private fun missingField(): Nothing =
    throw ForyJsonException("Kotlin range JSON requires start and endInclusive")
}

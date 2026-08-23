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

import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.codec.ArrayCodec
import org.apache.fory.json.codec.JsonValueCodec
import org.apache.fory.json.reader.Latin1JsonReader
import org.apache.fory.json.reader.Utf16JsonReader
import org.apache.fory.json.reader.Utf8JsonReader
import org.apache.fory.json.writer.StringJsonWriter
import org.apache.fory.json.writer.Utf8JsonWriter
import org.apache.fory.serializer.GraphMemoryEstimates
import org.apache.fory.type.Types

/**
 * Kotlin unsigned-array boxes over the exact core primitive-array capabilities.
 *
 * Only boxed root and container occurrences select these codecs. Direct object occurrences expose
 * their primitive backing and bind the core array codec without a wrapper round trip. The erased
 * JsonValueCodec read bridge performs the actual Kotlin box after each typed read returns, so every
 * typed read reserves the wrapper before returning its no-copy unsigned view.
 */
@OptIn(ExperimentalUnsignedTypes::class)
internal object KotlinUnsignedArrayCodecs {
  fun create(rawType: Class<*>, typeId: Int): JsonValueCodec<*>? =
    when (rawType) {
      UByteArray::class.java -> requireType(rawType, typeId, Types.UINT8_ARRAY, UByteArrayCodec)
      UShortArray::class.java -> requireType(rawType, typeId, Types.UINT16_ARRAY, UShortArrayCodec)
      UIntArray::class.java -> requireType(rawType, typeId, Types.UINT32_ARRAY, UIntArrayCodec)
      ULongArray::class.java -> requireType(rawType, typeId, Types.UINT64_ARRAY, ULongArrayCodec)
      else -> null
    }

  private fun requireType(
    rawType: Class<*>,
    actual: Int,
    expected: Int,
    codec: JsonValueCodec<*>,
  ): JsonValueCodec<*> {
    if (actual != expected) {
      throw ForyJsonException(
        "Kotlin unsigned-array carrier ${rawType.name} does not match semantic type id $actual",
      )
    }
    return codec
  }

  private object UByteArrayCodec : JsonValueCodec<UByteArray> {
    private val delegate =
      ArrayCodec.createUnsignedPrimitive(ByteArray::class.java, Types.UINT8_ARRAY)
    private val wrapperBytes = GraphMemoryEstimates.shallowObjectBytes(UByteArray::class.java)

    override fun writeString(writer: StringJsonWriter, value: UByteArray?) =
      delegate.writeString(writer, value?.asByteArray())

    override fun writeUtf8(writer: Utf8JsonWriter, value: UByteArray?) =
      delegate.writeUtf8(writer, value?.asByteArray())

    override fun readLatin1(reader: Latin1JsonReader): UByteArray? =
      wrap(reader, delegate.readLatin1(reader))

    override fun readUtf16(reader: Utf16JsonReader): UByteArray? =
      wrap(reader, delegate.readUtf16(reader))

    override fun readUtf8(reader: Utf8JsonReader): UByteArray? =
      wrap(reader, delegate.readUtf8(reader))

    private fun wrap(
      reader: org.apache.fory.json.reader.JsonReader,
      value: ByteArray?
    ): UByteArray? {
      if (value == null) return null
      reader.reserveGraphMemory(wrapperBytes)
      return value.asUByteArray()
    }
  }

  private object UShortArrayCodec : JsonValueCodec<UShortArray> {
    private val delegate =
      ArrayCodec.createUnsignedPrimitive(ShortArray::class.java, Types.UINT16_ARRAY)
    private val wrapperBytes = GraphMemoryEstimates.shallowObjectBytes(UShortArray::class.java)

    override fun writeString(writer: StringJsonWriter, value: UShortArray?) =
      delegate.writeString(writer, value?.asShortArray())

    override fun writeUtf8(writer: Utf8JsonWriter, value: UShortArray?) =
      delegate.writeUtf8(writer, value?.asShortArray())

    override fun readLatin1(reader: Latin1JsonReader): UShortArray? =
      wrap(reader, delegate.readLatin1(reader))

    override fun readUtf16(reader: Utf16JsonReader): UShortArray? =
      wrap(reader, delegate.readUtf16(reader))

    override fun readUtf8(reader: Utf8JsonReader): UShortArray? =
      wrap(reader, delegate.readUtf8(reader))

    private fun wrap(
      reader: org.apache.fory.json.reader.JsonReader,
      value: ShortArray?,
    ): UShortArray? {
      if (value == null) return null
      reader.reserveGraphMemory(wrapperBytes)
      return value.asUShortArray()
    }
  }

  private object UIntArrayCodec : JsonValueCodec<UIntArray> {
    private val delegate =
      ArrayCodec.createUnsignedPrimitive(IntArray::class.java, Types.UINT32_ARRAY)
    private val wrapperBytes = GraphMemoryEstimates.shallowObjectBytes(UIntArray::class.java)

    override fun writeString(writer: StringJsonWriter, value: UIntArray?) =
      delegate.writeString(writer, value?.asIntArray())

    override fun writeUtf8(writer: Utf8JsonWriter, value: UIntArray?) =
      delegate.writeUtf8(writer, value?.asIntArray())

    override fun readLatin1(reader: Latin1JsonReader): UIntArray? =
      wrap(reader, delegate.readLatin1(reader))

    override fun readUtf16(reader: Utf16JsonReader): UIntArray? =
      wrap(reader, delegate.readUtf16(reader))

    override fun readUtf8(reader: Utf8JsonReader): UIntArray? =
      wrap(reader, delegate.readUtf8(reader))

    private fun wrap(reader: org.apache.fory.json.reader.JsonReader, value: IntArray?): UIntArray? {
      if (value == null) return null
      reader.reserveGraphMemory(wrapperBytes)
      return value.asUIntArray()
    }
  }

  private object ULongArrayCodec : JsonValueCodec<ULongArray> {
    private val delegate =
      ArrayCodec.createUnsignedPrimitive(LongArray::class.java, Types.UINT64_ARRAY)
    private val wrapperBytes = GraphMemoryEstimates.shallowObjectBytes(ULongArray::class.java)

    override fun writeString(writer: StringJsonWriter, value: ULongArray?) =
      delegate.writeString(writer, value?.asLongArray())

    override fun writeUtf8(writer: Utf8JsonWriter, value: ULongArray?) =
      delegate.writeUtf8(writer, value?.asLongArray())

    override fun readLatin1(reader: Latin1JsonReader): ULongArray? =
      wrap(reader, delegate.readLatin1(reader))

    override fun readUtf16(reader: Utf16JsonReader): ULongArray? =
      wrap(reader, delegate.readUtf16(reader))

    override fun readUtf8(reader: Utf8JsonReader): ULongArray? =
      wrap(reader, delegate.readUtf8(reader))

    private fun wrap(
      reader: org.apache.fory.json.reader.JsonReader,
      value: LongArray?
    ): ULongArray? {
      if (value == null) return null
      reader.reserveGraphMemory(wrapperBytes)
      return value.asULongArray()
    }
  }
}

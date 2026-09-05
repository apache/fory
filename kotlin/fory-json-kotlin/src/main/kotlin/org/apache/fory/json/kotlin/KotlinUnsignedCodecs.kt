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

import java.lang.reflect.Method
import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.codec.DirectUnboxedValueCodec
import org.apache.fory.json.codec.JsonValueCodec
import org.apache.fory.json.reader.JsonReader
import org.apache.fory.json.reader.Latin1JsonReader
import org.apache.fory.json.reader.Utf16JsonReader
import org.apache.fory.json.reader.Utf8JsonReader
import org.apache.fory.json.writer.JsonWriter
import org.apache.fory.json.writer.StringJsonWriter
import org.apache.fory.json.writer.Utf8JsonWriter
import org.apache.fory.type.Types

/** Width- and carrier-specialized codecs for Kotlin unsigned scalars. */
@OptIn(ExperimentalUnsignedTypes::class)
internal object KotlinUnsignedCodecs {
  fun scalar(
    typeId: Int,
    boxedResult: Boolean,
    nullable: Boolean,
    writeLongAsString: Boolean,
  ): JsonValueCodec<Any?> =
    when (typeId) {
      Types.UINT8 ->
        if (nullable) UByteCodec.NULLABLE
        else if (boxedResult) UByteCodec.BOXED else UByteCarrierCodec
      Types.UINT16 ->
        if (nullable) UShortCodec.NULLABLE
        else if (boxedResult) UShortCodec.BOXED else UShortCarrierCodec
      Types.UINT32 ->
        if (nullable) UIntCodec.NULLABLE else if (boxedResult) UIntCodec.BOXED else UIntCarrierCodec
      Types.UINT64 ->
        if (writeLongAsString) {
          if (nullable) ULongAsStringCodec.NULLABLE
          else if (boxedResult) ULongAsStringCodec.BOXED else ULongAsStringCarrierCodec
        } else {
          if (nullable) ULongCodec.NULLABLE
          else if (boxedResult) ULongCodec.BOXED else ULongCarrierCodec
        }
      else -> throw ForyJsonException("Unknown Kotlin unsigned JSON type id $typeId")
    }

  @JvmStatic
  @JvmName("readUByteRaw")
  fun readUByteRaw(reader: JsonReader): Byte {
    val value = reader.readUnsignedInt()
    if (Integer.compareUnsigned(value, UByte.MAX_VALUE.toInt()) > 0) ubyteOverflow()
    return value.toByte()
  }

  @JvmStatic
  @JvmName("writeUByteRaw")
  fun writeUByteRaw(writer: JsonWriter, value: Byte) =
    writer.writeUnsignedInt(value.toInt() and 0xff)

  @JvmStatic
  @JvmName("readUShortRaw")
  fun readUShortRaw(reader: JsonReader): Short {
    val value = reader.readUnsignedInt()
    if (Integer.compareUnsigned(value, UShort.MAX_VALUE.toInt()) > 0) ushortOverflow()
    return value.toShort()
  }

  @JvmStatic
  @JvmName("writeUShortRaw")
  fun writeUShortRaw(writer: JsonWriter, value: Short) =
    writer.writeUnsignedInt(value.toInt() and 0xffff)

  @JvmStatic
  @JvmName("readUIntRaw")
  fun readUIntRaw(reader: JsonReader): Int = reader.readUnsignedInt()

  @JvmStatic
  @JvmName("writeUIntRaw")
  fun writeUIntRaw(writer: JsonWriter, value: Int) = writer.writeUnsignedInt(value)

  @JvmStatic
  @JvmName("readULongRaw")
  fun readULongRaw(reader: JsonReader): Long = reader.readUnsignedLong()

  @JvmStatic
  @JvmName("writeULongRaw")
  fun writeULongRaw(writer: JsonWriter, value: Long) = writer.writeUnsignedLong(value)

  @JvmStatic
  @JvmName("writeULongAsStringRaw")
  fun writeULongAsStringRaw(writer: JsonWriter, value: Long) =
    writer.writeUnsignedLongAsString(value)

  private class UByteCodec(private val nullable: Boolean) : JsonValueCodec<Any?> {
    companion object {
      val BOXED: JsonValueCodec<Any?> = UByteCodec(false)
      val NULLABLE: JsonValueCodec<Any?> = UByteCodec(true)
    }

    override fun writeString(writer: StringJsonWriter, value: Any?) = write(writer, value)

    override fun writeUtf8(writer: Utf8JsonWriter, value: Any?) = write(writer, value)

    override fun readLatin1(reader: Latin1JsonReader): Any? = read(reader)

    override fun readUtf16(reader: Utf16JsonReader): Any? = read(reader)

    override fun readUtf8(reader: Utf8JsonReader): Any? = read(reader)

    private fun write(writer: JsonWriter, value: Any?) {
      if (value == null) {
        writeNull(writer, nullable)
        return
      }
      writeUByteRaw(writer, (value as UByte).toByte())
    }

    private fun read(reader: JsonReader): Any? {
      if (reader.tryReadNullToken()) return readNull(nullable)
      return readUByteRaw(reader).toUByte()
    }
  }

  private object UByteCarrierCodec : JsonValueCodec<Any?>, DirectUnboxedValueCodec {
    private object Methods {
      val read: Method =
        KotlinUnsignedCodecs::class.java.getMethod("readUByteRaw", JsonReader::class.java)
      val write: Method =
        KotlinUnsignedCodecs::class
          .java
          .getMethod("writeUByteRaw", JsonWriter::class.java, java.lang.Byte.TYPE)
    }

    override fun writeString(writer: StringJsonWriter, value: Any?) =
      writeStringCarrier(writer, value ?: rejectNull())

    override fun writeUtf8(writer: Utf8JsonWriter, value: Any?) =
      writeUtf8Carrier(writer, value ?: rejectNull())

    override fun readLatin1(reader: Latin1JsonReader): Any = readLatin1Carrier(reader)

    override fun readUtf16(reader: Utf16JsonReader): Any = readUtf16Carrier(reader)

    override fun readUtf8(reader: Utf8JsonReader): Any = readUtf8Carrier(reader)

    override fun carrierType(): Class<*> = java.lang.Byte.TYPE

    override fun readLatin1Carrier(reader: Latin1JsonReader): Any = readUByteRaw(reader)

    override fun readUtf16Carrier(reader: Utf16JsonReader): Any = readUByteRaw(reader)

    override fun readUtf8Carrier(reader: Utf8JsonReader): Any = readUByteRaw(reader)

    override fun writeStringCarrier(writer: StringJsonWriter, carrier: Any) =
      writeUByteRaw(writer, carrier as Byte)

    override fun writeUtf8Carrier(writer: Utf8JsonWriter, carrier: Any) =
      writeUByteRaw(writer, carrier as Byte)

    override fun readCarrierMethod(): Method = Methods.read

    override fun writeCarrierMethod(): Method = Methods.write
  }

  private class UShortCodec(private val nullable: Boolean) : JsonValueCodec<Any?> {
    companion object {
      val BOXED: JsonValueCodec<Any?> = UShortCodec(false)
      val NULLABLE: JsonValueCodec<Any?> = UShortCodec(true)
    }

    override fun writeString(writer: StringJsonWriter, value: Any?) = write(writer, value)

    override fun writeUtf8(writer: Utf8JsonWriter, value: Any?) = write(writer, value)

    override fun readLatin1(reader: Latin1JsonReader): Any? = read(reader)

    override fun readUtf16(reader: Utf16JsonReader): Any? = read(reader)

    override fun readUtf8(reader: Utf8JsonReader): Any? = read(reader)

    private fun write(writer: JsonWriter, value: Any?) {
      if (value == null) {
        writeNull(writer, nullable)
        return
      }
      writeUShortRaw(writer, (value as UShort).toShort())
    }

    private fun read(reader: JsonReader): Any? {
      if (reader.tryReadNullToken()) return readNull(nullable)
      return readUShortRaw(reader).toUShort()
    }
  }

  private object UShortCarrierCodec : JsonValueCodec<Any?>, DirectUnboxedValueCodec {
    private object Methods {
      val read: Method =
        KotlinUnsignedCodecs::class.java.getMethod("readUShortRaw", JsonReader::class.java)
      val write: Method =
        KotlinUnsignedCodecs::class
          .java
          .getMethod("writeUShortRaw", JsonWriter::class.java, java.lang.Short.TYPE)
    }

    override fun writeString(writer: StringJsonWriter, value: Any?) =
      writeStringCarrier(writer, value ?: rejectNull())

    override fun writeUtf8(writer: Utf8JsonWriter, value: Any?) =
      writeUtf8Carrier(writer, value ?: rejectNull())

    override fun readLatin1(reader: Latin1JsonReader): Any = readLatin1Carrier(reader)

    override fun readUtf16(reader: Utf16JsonReader): Any = readUtf16Carrier(reader)

    override fun readUtf8(reader: Utf8JsonReader): Any = readUtf8Carrier(reader)

    override fun carrierType(): Class<*> = java.lang.Short.TYPE

    override fun readLatin1Carrier(reader: Latin1JsonReader): Any = readUShortRaw(reader)

    override fun readUtf16Carrier(reader: Utf16JsonReader): Any = readUShortRaw(reader)

    override fun readUtf8Carrier(reader: Utf8JsonReader): Any = readUShortRaw(reader)

    override fun writeStringCarrier(writer: StringJsonWriter, carrier: Any) =
      writeUShortRaw(writer, carrier as Short)

    override fun writeUtf8Carrier(writer: Utf8JsonWriter, carrier: Any) =
      writeUShortRaw(writer, carrier as Short)

    override fun readCarrierMethod(): Method = Methods.read

    override fun writeCarrierMethod(): Method = Methods.write
  }

  private class UIntCodec(private val nullable: Boolean) : JsonValueCodec<Any?> {
    companion object {
      val BOXED: JsonValueCodec<Any?> = UIntCodec(false)
      val NULLABLE: JsonValueCodec<Any?> = UIntCodec(true)
    }

    override fun writeString(writer: StringJsonWriter, value: Any?) = write(writer, value)

    override fun writeUtf8(writer: Utf8JsonWriter, value: Any?) = write(writer, value)

    override fun readLatin1(reader: Latin1JsonReader): Any? = read(reader)

    override fun readUtf16(reader: Utf16JsonReader): Any? = read(reader)

    override fun readUtf8(reader: Utf8JsonReader): Any? = read(reader)

    private fun write(writer: JsonWriter, value: Any?) {
      if (value == null) {
        writeNull(writer, nullable)
        return
      }
      writeUIntRaw(writer, (value as UInt).toInt())
    }

    private fun read(reader: JsonReader): Any? {
      if (reader.tryReadNullToken()) return readNull(nullable)
      return readUIntRaw(reader).toUInt()
    }
  }

  private object UIntCarrierCodec : JsonValueCodec<Any?>, DirectUnboxedValueCodec {
    private object Methods {
      val read: Method =
        KotlinUnsignedCodecs::class.java.getMethod("readUIntRaw", JsonReader::class.java)
      val write: Method =
        KotlinUnsignedCodecs::class
          .java
          .getMethod("writeUIntRaw", JsonWriter::class.java, java.lang.Integer.TYPE)
    }

    override fun writeString(writer: StringJsonWriter, value: Any?) =
      writeStringCarrier(writer, value ?: rejectNull())

    override fun writeUtf8(writer: Utf8JsonWriter, value: Any?) =
      writeUtf8Carrier(writer, value ?: rejectNull())

    override fun readLatin1(reader: Latin1JsonReader): Any = readLatin1Carrier(reader)

    override fun readUtf16(reader: Utf16JsonReader): Any = readUtf16Carrier(reader)

    override fun readUtf8(reader: Utf8JsonReader): Any = readUtf8Carrier(reader)

    override fun carrierType(): Class<*> = java.lang.Integer.TYPE

    override fun readLatin1Carrier(reader: Latin1JsonReader): Any = readUIntRaw(reader)

    override fun readUtf16Carrier(reader: Utf16JsonReader): Any = readUIntRaw(reader)

    override fun readUtf8Carrier(reader: Utf8JsonReader): Any = readUIntRaw(reader)

    override fun writeStringCarrier(writer: StringJsonWriter, carrier: Any) =
      writeUIntRaw(writer, carrier as Int)

    override fun writeUtf8Carrier(writer: Utf8JsonWriter, carrier: Any) =
      writeUIntRaw(writer, carrier as Int)

    override fun readCarrierMethod(): Method = Methods.read

    override fun writeCarrierMethod(): Method = Methods.write
  }

  private class ULongCodec(private val nullable: Boolean) : JsonValueCodec<Any?> {
    companion object {
      val BOXED: JsonValueCodec<Any?> = ULongCodec(false)
      val NULLABLE: JsonValueCodec<Any?> = ULongCodec(true)
    }

    override fun writeString(writer: StringJsonWriter, value: Any?) = write(writer, value)

    override fun writeUtf8(writer: Utf8JsonWriter, value: Any?) = write(writer, value)

    override fun readLatin1(reader: Latin1JsonReader): Any? = read(reader)

    override fun readUtf16(reader: Utf16JsonReader): Any? = read(reader)

    override fun readUtf8(reader: Utf8JsonReader): Any? = read(reader)

    private fun write(writer: JsonWriter, value: Any?) {
      if (value == null) {
        writeNull(writer, nullable)
        return
      }
      writeULongRaw(writer, (value as ULong).toLong())
    }

    private fun read(reader: JsonReader): Any? {
      if (reader.tryReadNullToken()) return readNull(nullable)
      return readULongRaw(reader).toULong()
    }
  }

  private object ULongCarrierCodec : JsonValueCodec<Any?>, DirectUnboxedValueCodec {
    private object Methods {
      val read: Method =
        KotlinUnsignedCodecs::class.java.getMethod("readULongRaw", JsonReader::class.java)
      val write: Method =
        KotlinUnsignedCodecs::class
          .java
          .getMethod("writeULongRaw", JsonWriter::class.java, java.lang.Long.TYPE)
    }

    override fun writeString(writer: StringJsonWriter, value: Any?) =
      writeStringCarrier(writer, value ?: rejectNull())

    override fun writeUtf8(writer: Utf8JsonWriter, value: Any?) =
      writeUtf8Carrier(writer, value ?: rejectNull())

    override fun readLatin1(reader: Latin1JsonReader): Any = readLatin1Carrier(reader)

    override fun readUtf16(reader: Utf16JsonReader): Any = readUtf16Carrier(reader)

    override fun readUtf8(reader: Utf8JsonReader): Any = readUtf8Carrier(reader)

    override fun carrierType(): Class<*> = java.lang.Long.TYPE

    override fun readLatin1Carrier(reader: Latin1JsonReader): Any = readULongRaw(reader)

    override fun readUtf16Carrier(reader: Utf16JsonReader): Any = readULongRaw(reader)

    override fun readUtf8Carrier(reader: Utf8JsonReader): Any = readULongRaw(reader)

    override fun writeStringCarrier(writer: StringJsonWriter, carrier: Any) =
      writeULongRaw(writer, carrier as Long)

    override fun writeUtf8Carrier(writer: Utf8JsonWriter, carrier: Any) =
      writeULongRaw(writer, carrier as Long)

    override fun readCarrierMethod(): Method = Methods.read

    override fun writeCarrierMethod(): Method = Methods.write
  }

  private class ULongAsStringCodec(private val nullable: Boolean) : JsonValueCodec<Any?> {
    companion object {
      val BOXED: JsonValueCodec<Any?> = ULongAsStringCodec(false)
      val NULLABLE: JsonValueCodec<Any?> = ULongAsStringCodec(true)
    }

    override fun writeString(writer: StringJsonWriter, value: Any?) = write(writer, value)

    override fun writeUtf8(writer: Utf8JsonWriter, value: Any?) = write(writer, value)

    override fun readLatin1(reader: Latin1JsonReader): Any? = read(reader)

    override fun readUtf16(reader: Utf16JsonReader): Any? = read(reader)

    override fun readUtf8(reader: Utf8JsonReader): Any? = read(reader)

    private fun write(writer: JsonWriter, value: Any?) {
      if (value == null) {
        writeNull(writer, nullable)
        return
      }
      writeULongAsStringRaw(writer, (value as ULong).toLong())
    }

    private fun read(reader: JsonReader): Any? {
      if (reader.tryReadNullToken()) return readNull(nullable)
      return readULongRaw(reader).toULong()
    }
  }

  private object ULongAsStringCarrierCodec : JsonValueCodec<Any?>, DirectUnboxedValueCodec {
    private object Methods {
      val read: Method =
        KotlinUnsignedCodecs::class.java.getMethod("readULongRaw", JsonReader::class.java)
      val write: Method =
        KotlinUnsignedCodecs::class
          .java
          .getMethod("writeULongAsStringRaw", JsonWriter::class.java, java.lang.Long.TYPE)
    }

    override fun writeString(writer: StringJsonWriter, value: Any?) =
      writeStringCarrier(writer, value ?: rejectNull())

    override fun writeUtf8(writer: Utf8JsonWriter, value: Any?) =
      writeUtf8Carrier(writer, value ?: rejectNull())

    override fun readLatin1(reader: Latin1JsonReader): Any = readLatin1Carrier(reader)

    override fun readUtf16(reader: Utf16JsonReader): Any = readUtf16Carrier(reader)

    override fun readUtf8(reader: Utf8JsonReader): Any = readUtf8Carrier(reader)

    override fun carrierType(): Class<*> = java.lang.Long.TYPE

    override fun readLatin1Carrier(reader: Latin1JsonReader): Any = readULongRaw(reader)

    override fun readUtf16Carrier(reader: Utf16JsonReader): Any = readULongRaw(reader)

    override fun readUtf8Carrier(reader: Utf8JsonReader): Any = readULongRaw(reader)

    override fun writeStringCarrier(writer: StringJsonWriter, carrier: Any) =
      writeULongAsStringRaw(writer, carrier as Long)

    override fun writeUtf8Carrier(writer: Utf8JsonWriter, carrier: Any) =
      writeULongAsStringRaw(writer, carrier as Long)

    override fun readCarrierMethod(): Method = Methods.read

    override fun writeCarrierMethod(): Method = Methods.write
  }

  private fun writeNull(writer: JsonWriter, nullable: Boolean) {
    if (!nullable) rejectNull()
    writer.writeNull()
  }

  private fun readNull(nullable: Boolean): Any? {
    if (!nullable) rejectNull()
    return null
  }

  private fun rejectNull(): Nothing =
    throw ForyJsonException("Kotlin unsigned value is not nullable")

  private fun ubyteOverflow(): Nothing = throw ForyJsonException("UByte overflow")

  private fun ushortOverflow(): Nothing = throw ForyJsonException("UShort overflow")
}

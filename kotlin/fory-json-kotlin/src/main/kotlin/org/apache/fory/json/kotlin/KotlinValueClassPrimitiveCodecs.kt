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

import org.apache.fory.json.codec.JsonValueCodec
import org.apache.fory.json.codec.ScalarCodecs
import org.apache.fory.json.meta.JsonCreatorFieldInfo
import org.apache.fory.json.reader.Latin1JsonReader
import org.apache.fory.json.reader.Utf16JsonReader
import org.apache.fory.json.reader.Utf8JsonReader
import org.apache.fory.json.resolver.JsonTypeInfo
import org.apache.fory.json.writer.StringJsonWriter
import org.apache.fory.json.writer.Utf8JsonWriter
import org.apache.fory.type.Types

/** Cold selection of the fixed primitive or generic value-class execution capability. */
internal object KotlinValueClassCapabilities {
  fun bind(
    shape: KotlinValueClassShape,
    operations: KotlinValueClassOperations,
    child: JsonTypeInfo,
  ): KotlinValueClassCapability {
    val terminal = shape.terminalType
    val typeId = terminal.typeExtMeta?.typeId() ?: Types.UNKNOWN
    val carrier = shape.layers.last().carrierClass
    when (carrier) {
      java.lang.Boolean.TYPE -> {
        val expected = ScalarCodecs.BooleanCodec.PRIMITIVE
        if (typeId == Types.UNKNOWN && exact(child, expected)) {
          operations.typed<KotlinBooleanValueClassOperations<Any>>()?.let {
            return BooleanCapability(it)
          }
        }
      }
      java.lang.Byte.TYPE -> {
        if (typeId == Types.UINT8) {
          val expected = KotlinUnsignedCodecs.scalar(typeId, false, false, false)
          if (exact(child, expected)) {
            operations.typed<KotlinByteValueClassOperations<Any>>()?.let {
              return UByteCapability(it)
            }
          }
        } else if (typeId == Types.UNKNOWN && exact(child, ScalarCodecs.ByteCodec.PRIMITIVE)) {
          operations.typed<KotlinByteValueClassOperations<Any>>()?.let {
            return ByteCapability(it)
          }
        }
      }
      java.lang.Short.TYPE -> {
        if (typeId == Types.UINT16) {
          val expected = KotlinUnsignedCodecs.scalar(typeId, false, false, false)
          if (exact(child, expected)) {
            operations.typed<KotlinShortValueClassOperations<Any>>()?.let {
              return UShortCapability(it)
            }
          }
        } else if (typeId == Types.UNKNOWN && exact(child, ScalarCodecs.ShortCodec.PRIMITIVE)) {
          operations.typed<KotlinShortValueClassOperations<Any>>()?.let {
            return ShortCapability(it)
          }
        }
      }
      java.lang.Integer.TYPE -> {
        if (typeId == Types.UINT32) {
          val expected = KotlinUnsignedCodecs.scalar(typeId, false, false, false)
          if (exact(child, expected)) {
            operations.typed<KotlinIntValueClassOperations<Any>>()?.let {
              return UIntCapability(it)
            }
          }
        } else if (typeId == Types.UNKNOWN && exact(child, ScalarCodecs.IntCodec.PRIMITIVE)) {
          operations.typed<KotlinIntValueClassOperations<Any>>()?.let {
            return IntCapability(it)
          }
        }
      }
      java.lang.Long.TYPE -> {
        if (typeId == Types.UINT64) {
          val numeric = KotlinUnsignedCodecs.scalar(typeId, false, false, false)
          if (exact(child, numeric)) {
            operations.typed<KotlinLongValueClassOperations<Any>>()?.let {
              return ULongCapability(it)
            }
          }
          val quoted = KotlinUnsignedCodecs.scalar(typeId, false, false, true)
          if (exact(child, quoted)) {
            operations.typed<KotlinLongValueClassOperations<Any>>()?.let {
              return ULongAsStringCapability(it)
            }
          }
        } else if (typeId == Types.UNKNOWN && exact(child, ScalarCodecs.LongCodec.PRIMITIVE)) {
          operations.typed<KotlinLongValueClassOperations<Any>>()?.let {
            return LongCapability(it)
          }
        } else if (
          typeId == Types.UNKNOWN && exact(child, ScalarCodecs.LongAsStringCodec.PRIMITIVE)
        ) {
          operations.typed<KotlinLongValueClassOperations<Any>>()?.let {
            return LongAsStringCapability(it)
          }
        }
      }
      java.lang.Float.TYPE ->
        if (typeId == Types.UNKNOWN && exact(child, ScalarCodecs.FloatCodec.PRIMITIVE)) {
          operations.typed<KotlinFloatValueClassOperations<Any>>()?.let {
            return FloatCapability(it)
          }
        }
      java.lang.Double.TYPE ->
        if (typeId == Types.UNKNOWN && exact(child, ScalarCodecs.DoubleCodec.PRIMITIVE)) {
          operations.typed<KotlinDoubleValueClassOperations<Any>>()?.let {
            return DoubleCapability(it)
          }
        }
      java.lang.Character.TYPE ->
        if (typeId == Types.UNKNOWN && exact(child, ScalarCodecs.CharCodec.PRIMITIVE)) {
          operations.typed<KotlinCharValueClassOperations<Any>>()?.let {
            return CharCapability(it)
          }
        }
    }
    return GenericValueClassCapability(boxedOperations(operations), child)
  }

  private fun exact(child: JsonTypeInfo, expected: JsonValueCodec<*>): Boolean =
    child.stringWriter() === expected &&
      child.utf8Writer() === expected &&
      child.latin1Reader() === expected &&
      child.utf16Reader() === expected &&
      child.utf8Reader() === expected

  @Suppress("UNCHECKED_CAST")
  private inline fun <reified T : KotlinValueClassOperations> KotlinValueClassOperations.typed():
    T? = this as? T
}

private class BooleanCapability(
  private val operations: KotlinBooleanValueClassOperations<Any>,
) : KotlinValueClassCapability {
  override fun writeString(writer: StringJsonWriter, value: Any) =
    writer.writeBoolean(operations.unboxBoolean(value))

  override fun writeUtf8(writer: Utf8JsonWriter, value: Any) =
    writer.writeBoolean(operations.unboxBoolean(value))

  override fun readLatin1(reader: Latin1JsonReader): Any =
    operations.constructBoolean(reader, reader.readBooleanValue())

  override fun readUtf16(reader: Utf16JsonReader): Any =
    operations.constructBoolean(reader, reader.readBooleanValue())

  override fun readUtf8(reader: Utf8JsonReader): Any =
    operations.constructBoolean(reader, reader.readBooleanValue())
}

private class ByteCapability(
  private val operations: KotlinByteValueClassOperations<Any>,
) : KotlinValueClassCapability {
  override fun writeString(writer: StringJsonWriter, value: Any) =
    writer.writeInt(operations.unboxByte(value).toInt())

  override fun writeUtf8(writer: Utf8JsonWriter, value: Any) =
    writer.writeInt(operations.unboxByte(value).toInt())

  override fun readLatin1(reader: Latin1JsonReader): Any =
    operations.constructByte(reader, JsonCreatorFieldInfo.checkedByte(reader.readIntValue()))

  override fun readUtf16(reader: Utf16JsonReader): Any =
    operations.constructByte(reader, JsonCreatorFieldInfo.checkedByte(reader.readIntValue()))

  override fun readUtf8(reader: Utf8JsonReader): Any =
    operations.constructByte(reader, JsonCreatorFieldInfo.checkedByte(reader.readIntValue()))
}

private class ShortCapability(
  private val operations: KotlinShortValueClassOperations<Any>,
) : KotlinValueClassCapability {
  override fun writeString(writer: StringJsonWriter, value: Any) =
    writer.writeInt(operations.unboxShort(value).toInt())

  override fun writeUtf8(writer: Utf8JsonWriter, value: Any) =
    writer.writeInt(operations.unboxShort(value).toInt())

  override fun readLatin1(reader: Latin1JsonReader): Any =
    operations.constructShort(reader, JsonCreatorFieldInfo.checkedShort(reader.readIntValue()))

  override fun readUtf16(reader: Utf16JsonReader): Any =
    operations.constructShort(reader, JsonCreatorFieldInfo.checkedShort(reader.readIntValue()))

  override fun readUtf8(reader: Utf8JsonReader): Any =
    operations.constructShort(reader, JsonCreatorFieldInfo.checkedShort(reader.readIntValue()))
}

private class IntCapability(
  private val operations: KotlinIntValueClassOperations<Any>,
) : KotlinValueClassCapability {
  override fun writeString(writer: StringJsonWriter, value: Any) =
    writer.writeInt(operations.unboxInt(value))

  override fun writeUtf8(writer: Utf8JsonWriter, value: Any) =
    writer.writeInt(operations.unboxInt(value))

  override fun readLatin1(reader: Latin1JsonReader): Any =
    operations.constructInt(reader, reader.readIntValue())

  override fun readUtf16(reader: Utf16JsonReader): Any =
    operations.constructInt(reader, reader.readIntValue())

  override fun readUtf8(reader: Utf8JsonReader): Any =
    operations.constructInt(reader, reader.readIntValue())
}

private class LongCapability(
  private val operations: KotlinLongValueClassOperations<Any>,
) : KotlinValueClassCapability {
  override fun writeString(writer: StringJsonWriter, value: Any) =
    writer.writeLong(operations.unboxLong(value))

  override fun writeUtf8(writer: Utf8JsonWriter, value: Any) =
    writer.writeLong(operations.unboxLong(value))

  override fun readLatin1(reader: Latin1JsonReader): Any =
    operations.constructLong(reader, reader.readLongValue())

  override fun readUtf16(reader: Utf16JsonReader): Any =
    operations.constructLong(reader, reader.readLongValue())

  override fun readUtf8(reader: Utf8JsonReader): Any =
    operations.constructLong(reader, reader.readLongValue())
}

private class LongAsStringCapability(
  private val operations: KotlinLongValueClassOperations<Any>,
) : KotlinValueClassCapability {
  override fun writeString(writer: StringJsonWriter, value: Any) =
    writer.writeLongAsString(operations.unboxLong(value))

  override fun writeUtf8(writer: Utf8JsonWriter, value: Any) =
    writer.writeLongAsString(operations.unboxLong(value))

  override fun readLatin1(reader: Latin1JsonReader): Any =
    operations.constructLong(reader, reader.readLongValue())

  override fun readUtf16(reader: Utf16JsonReader): Any =
    operations.constructLong(reader, reader.readLongValue())

  override fun readUtf8(reader: Utf8JsonReader): Any =
    operations.constructLong(reader, reader.readLongValue())
}

private class FloatCapability(
  private val operations: KotlinFloatValueClassOperations<Any>,
) : KotlinValueClassCapability {
  override fun writeString(writer: StringJsonWriter, value: Any) =
    writer.writeFloat(operations.unboxFloat(value))

  override fun writeUtf8(writer: Utf8JsonWriter, value: Any) =
    writer.writeFloat(operations.unboxFloat(value))

  override fun readLatin1(reader: Latin1JsonReader): Any =
    operations.constructFloat(reader, reader.readFloat())

  override fun readUtf16(reader: Utf16JsonReader): Any =
    operations.constructFloat(reader, reader.readFloat())

  override fun readUtf8(reader: Utf8JsonReader): Any =
    operations.constructFloat(reader, reader.readFloat())
}

private class DoubleCapability(
  private val operations: KotlinDoubleValueClassOperations<Any>,
) : KotlinValueClassCapability {
  override fun writeString(writer: StringJsonWriter, value: Any) =
    writer.writeDouble(operations.unboxDouble(value))

  override fun writeUtf8(writer: Utf8JsonWriter, value: Any) =
    writer.writeDouble(operations.unboxDouble(value))

  override fun readLatin1(reader: Latin1JsonReader): Any =
    operations.constructDouble(reader, reader.readDouble())

  override fun readUtf16(reader: Utf16JsonReader): Any =
    operations.constructDouble(reader, reader.readDouble())

  override fun readUtf8(reader: Utf8JsonReader): Any =
    operations.constructDouble(reader, reader.readDouble())
}

private class CharCapability(
  private val operations: KotlinCharValueClassOperations<Any>,
) : KotlinValueClassCapability {
  override fun writeString(writer: StringJsonWriter, value: Any) =
    writer.writeChar(operations.unboxChar(value))

  override fun writeUtf8(writer: Utf8JsonWriter, value: Any) =
    writer.writeChar(operations.unboxChar(value))

  override fun readLatin1(reader: Latin1JsonReader): Any =
    operations.constructChar(reader, reader.readChar())

  override fun readUtf16(reader: Utf16JsonReader): Any =
    operations.constructChar(reader, reader.readChar())

  override fun readUtf8(reader: Utf8JsonReader): Any =
    operations.constructChar(reader, reader.readChar())
}

private class UByteCapability(
  private val operations: KotlinByteValueClassOperations<Any>,
) : KotlinValueClassCapability {
  override fun writeString(writer: StringJsonWriter, value: Any) =
    KotlinUnsignedCodecs.writeUByteRaw(writer, operations.unboxByte(value))

  override fun writeUtf8(writer: Utf8JsonWriter, value: Any) =
    KotlinUnsignedCodecs.writeUByteRaw(writer, operations.unboxByte(value))

  override fun readLatin1(reader: Latin1JsonReader): Any =
    operations.constructByte(reader, KotlinUnsignedCodecs.readUByteRaw(reader))

  override fun readUtf16(reader: Utf16JsonReader): Any =
    operations.constructByte(reader, KotlinUnsignedCodecs.readUByteRaw(reader))

  override fun readUtf8(reader: Utf8JsonReader): Any =
    operations.constructByte(reader, KotlinUnsignedCodecs.readUByteRaw(reader))
}

private class UShortCapability(
  private val operations: KotlinShortValueClassOperations<Any>,
) : KotlinValueClassCapability {
  override fun writeString(writer: StringJsonWriter, value: Any) =
    KotlinUnsignedCodecs.writeUShortRaw(writer, operations.unboxShort(value))

  override fun writeUtf8(writer: Utf8JsonWriter, value: Any) =
    KotlinUnsignedCodecs.writeUShortRaw(writer, operations.unboxShort(value))

  override fun readLatin1(reader: Latin1JsonReader): Any =
    operations.constructShort(reader, KotlinUnsignedCodecs.readUShortRaw(reader))

  override fun readUtf16(reader: Utf16JsonReader): Any =
    operations.constructShort(reader, KotlinUnsignedCodecs.readUShortRaw(reader))

  override fun readUtf8(reader: Utf8JsonReader): Any =
    operations.constructShort(reader, KotlinUnsignedCodecs.readUShortRaw(reader))
}

private class UIntCapability(
  private val operations: KotlinIntValueClassOperations<Any>,
) : KotlinValueClassCapability {
  override fun writeString(writer: StringJsonWriter, value: Any) =
    KotlinUnsignedCodecs.writeUIntRaw(writer, operations.unboxInt(value))

  override fun writeUtf8(writer: Utf8JsonWriter, value: Any) =
    KotlinUnsignedCodecs.writeUIntRaw(writer, operations.unboxInt(value))

  override fun readLatin1(reader: Latin1JsonReader): Any =
    operations.constructInt(reader, KotlinUnsignedCodecs.readUIntRaw(reader))

  override fun readUtf16(reader: Utf16JsonReader): Any =
    operations.constructInt(reader, KotlinUnsignedCodecs.readUIntRaw(reader))

  override fun readUtf8(reader: Utf8JsonReader): Any =
    operations.constructInt(reader, KotlinUnsignedCodecs.readUIntRaw(reader))
}

private class ULongCapability(
  private val operations: KotlinLongValueClassOperations<Any>,
) : KotlinValueClassCapability {
  override fun writeString(writer: StringJsonWriter, value: Any) =
    KotlinUnsignedCodecs.writeULongRaw(writer, operations.unboxLong(value))

  override fun writeUtf8(writer: Utf8JsonWriter, value: Any) =
    KotlinUnsignedCodecs.writeULongRaw(writer, operations.unboxLong(value))

  override fun readLatin1(reader: Latin1JsonReader): Any =
    operations.constructLong(reader, KotlinUnsignedCodecs.readULongRaw(reader))

  override fun readUtf16(reader: Utf16JsonReader): Any =
    operations.constructLong(reader, KotlinUnsignedCodecs.readULongRaw(reader))

  override fun readUtf8(reader: Utf8JsonReader): Any =
    operations.constructLong(reader, KotlinUnsignedCodecs.readULongRaw(reader))
}

private class ULongAsStringCapability(
  private val operations: KotlinLongValueClassOperations<Any>,
) : KotlinValueClassCapability {
  override fun writeString(writer: StringJsonWriter, value: Any) =
    KotlinUnsignedCodecs.writeULongAsStringRaw(writer, operations.unboxLong(value))

  override fun writeUtf8(writer: Utf8JsonWriter, value: Any) =
    KotlinUnsignedCodecs.writeULongAsStringRaw(writer, operations.unboxLong(value))

  override fun readLatin1(reader: Latin1JsonReader): Any =
    operations.constructLong(reader, KotlinUnsignedCodecs.readULongRaw(reader))

  override fun readUtf16(reader: Utf16JsonReader): Any =
    operations.constructLong(reader, KotlinUnsignedCodecs.readULongRaw(reader))

  override fun readUtf8(reader: Utf8JsonReader): Any =
    operations.constructLong(reader, KotlinUnsignedCodecs.readULongRaw(reader))
}

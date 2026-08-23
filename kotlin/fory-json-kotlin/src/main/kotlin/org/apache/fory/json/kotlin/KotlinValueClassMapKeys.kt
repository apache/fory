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
import org.apache.fory.json.codec.MapKeyCodec
import org.apache.fory.json.meta.JsonCreatorFieldInfo
import org.apache.fory.json.reader.JsonReader
import org.apache.fory.json.writer.JsonWriter
import org.apache.fory.type.Types

/** Cold selection of the fixed typed member-name operation for a value-class key chain. */
internal object KotlinValueClassMapKeys {
  fun create(
    shape: KotlinValueClassShape,
    operations: KotlinValueClassOperations,
    terminal: MapKeyCodec,
  ): MapKeyCodec {
    val type = shape.terminalType
    val typeId = type.typeExtMeta?.typeId() ?: Types.UNKNOWN
    val rawType = type.rawType
    // A generic value-class layer has an Object carrier even when its substituted terminal is a
    // primitive logical type. That JVM ABI must use the reference operation path; selecting a
    // primitive operation from the substituted TypeRef would invent a carrier the class does not
    // have.
    if (shape.layers.last().carrierClass != Any::class.java) {
      when (typeId) {
        Types.UINT8 ->
          operations.typed<KotlinByteMapKeyOperations<Any>>()?.let {
            return UByteKey(it)
          }
        Types.UINT16 ->
          operations.typed<KotlinShortMapKeyOperations<Any>>()?.let {
            return UShortKey(it)
          }
        Types.UINT32 ->
          operations.typed<KotlinIntMapKeyOperations<Any>>()?.let {
            return UIntKey(it)
          }
        Types.UINT64 ->
          operations.typed<KotlinLongMapKeyOperations<Any>>()?.let {
            return ULongKey(it)
          }
      }
      when (rawType) {
        Byte::class.javaPrimitiveType,
        Byte::class.javaObjectType ->
          operations.typed<KotlinByteMapKeyOperations<Any>>()?.let {
            return ByteKey(it)
          }
        Short::class.javaPrimitiveType,
        Short::class.javaObjectType ->
          operations.typed<KotlinShortMapKeyOperations<Any>>()?.let {
            return ShortKey(it)
          }
        Int::class.javaPrimitiveType,
        Int::class.javaObjectType ->
          operations.typed<KotlinIntMapKeyOperations<Any>>()?.let {
            return IntKey(it)
          }
        Long::class.javaPrimitiveType,
        Long::class.javaObjectType ->
          operations.typed<KotlinLongMapKeyOperations<Any>>()?.let {
            return LongKey(it)
          }
      }
    }
    val boxed =
      operations as? UnchargedBoxedOperations
        ?: throw ForyJsonException(
          "Kotlin value-class map-key operations cannot construct ${shape.ownerClass.name}",
        )
    return ReferenceKey(boxed, terminal)
  }

  @Suppress("UNCHECKED_CAST")
  private inline fun <reified T : KotlinValueClassOperations> KotlinValueClassOperations.typed():
    T? = this as? T
}

private class ReferenceKey(
  private val operations: UnchargedBoxedOperations,
  private val terminal: MapKeyCodec,
) : MapKeyCodec {
  override fun toName(key: Any): String = terminal.toName(operations.unbox(key))

  override fun fromName(name: String): Any = operations.constructUncharged(terminal.fromName(name))

  override fun writeName(writer: JsonWriter, key: Any) =
    terminal.writeName(writer, operations.unbox(key))

  override fun readName(reader: JsonReader): Any =
    operations.construct(reader, terminal.readName(reader))
}

private class ByteKey(
  private val operations: KotlinByteMapKeyOperations<Any>,
) : MapKeyCodec {
  override fun toName(key: Any): String = operations.unboxByte(key).toString()

  override fun fromName(name: String): Any =
    operations.constructByteUncharged(KotlinMapKeyParsing.byte(name))

  override fun writeName(writer: JsonWriter, key: Any) =
    writer.writeIntFieldName(operations.unboxByte(key).toInt())

  override fun readName(reader: JsonReader): Any =
    operations.constructByte(reader, JsonCreatorFieldInfo.checkedByte(reader.readFieldNameInt()))
}

private class ShortKey(
  private val operations: KotlinShortMapKeyOperations<Any>,
) : MapKeyCodec {
  override fun toName(key: Any): String = operations.unboxShort(key).toString()

  override fun fromName(name: String): Any =
    operations.constructShortUncharged(KotlinMapKeyParsing.short(name))

  override fun writeName(writer: JsonWriter, key: Any) =
    writer.writeIntFieldName(operations.unboxShort(key).toInt())

  override fun readName(reader: JsonReader): Any =
    operations.constructShort(reader, JsonCreatorFieldInfo.checkedShort(reader.readFieldNameInt()))
}

private class IntKey(
  private val operations: KotlinIntMapKeyOperations<Any>,
) : MapKeyCodec {
  override fun toName(key: Any): String = operations.unboxInt(key).toString()

  override fun fromName(name: String): Any =
    operations.constructIntUncharged(KotlinMapKeyParsing.int(name))

  override fun writeName(writer: JsonWriter, key: Any) =
    writer.writeIntFieldName(operations.unboxInt(key))

  override fun readName(reader: JsonReader): Any =
    operations.constructInt(reader, reader.readFieldNameInt())
}

private class LongKey(
  private val operations: KotlinLongMapKeyOperations<Any>,
) : MapKeyCodec {
  override fun toName(key: Any): String = operations.unboxLong(key).toString()

  override fun fromName(name: String): Any =
    operations.constructLongUncharged(KotlinMapKeyParsing.long(name))

  override fun writeName(writer: JsonWriter, key: Any) =
    writer.writeLongFieldName(operations.unboxLong(key))

  override fun readName(reader: JsonReader): Any =
    operations.constructLong(reader, reader.readFieldNameLong())
}

private class UByteKey(
  private val operations: KotlinByteMapKeyOperations<Any>,
) : MapKeyCodec {
  override fun toName(key: Any): String = (operations.unboxByte(key).toInt() and 0xff).toString()

  override fun fromName(name: String): Any =
    operations.constructByteUncharged(KotlinMapKeyParsing.ubyte(name))

  override fun writeName(writer: JsonWriter, key: Any) =
    writer.writeUnsignedIntFieldName(operations.unboxByte(key).toInt() and 0xff)

  override fun readName(reader: JsonReader): Any =
    operations.constructByte(
      reader,
      KotlinMapKeyParsing.checkedUByte(reader.readFieldNameUnsignedInt()),
    )
}

private class UShortKey(
  private val operations: KotlinShortMapKeyOperations<Any>,
) : MapKeyCodec {
  override fun toName(key: Any): String = (operations.unboxShort(key).toInt() and 0xffff).toString()

  override fun fromName(name: String): Any =
    operations.constructShortUncharged(KotlinMapKeyParsing.ushort(name))

  override fun writeName(writer: JsonWriter, key: Any) =
    writer.writeUnsignedIntFieldName(operations.unboxShort(key).toInt() and 0xffff)

  override fun readName(reader: JsonReader): Any =
    operations.constructShort(
      reader,
      KotlinMapKeyParsing.checkedUShort(reader.readFieldNameUnsignedInt()),
    )
}

private class UIntKey(
  private val operations: KotlinIntMapKeyOperations<Any>,
) : MapKeyCodec {
  override fun toName(key: Any): String = Integer.toUnsignedString(operations.unboxInt(key))

  override fun fromName(name: String): Any =
    operations.constructIntUncharged(KotlinMapKeyParsing.uint(name))

  override fun writeName(writer: JsonWriter, key: Any) =
    writer.writeUnsignedIntFieldName(operations.unboxInt(key))

  override fun readName(reader: JsonReader): Any =
    operations.constructInt(reader, reader.readFieldNameUnsignedInt())
}

private class ULongKey(
  private val operations: KotlinLongMapKeyOperations<Any>,
) : MapKeyCodec {
  override fun toName(key: Any): String = java.lang.Long.toUnsignedString(operations.unboxLong(key))

  override fun fromName(name: String): Any =
    operations.constructLongUncharged(KotlinMapKeyParsing.ulong(name))

  override fun writeName(writer: JsonWriter, key: Any) =
    writer.writeUnsignedLongFieldName(operations.unboxLong(key))

  override fun readName(reader: JsonReader): Any =
    operations.constructLong(reader, reader.readFieldNameUnsignedLong())
}

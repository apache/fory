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
import org.apache.fory.json.codec.JsonValueCodec
import org.apache.fory.json.codec.MapCodec
import org.apache.fory.json.codec.MapKeyCodec
import org.apache.fory.json.reader.JsonReader
import org.apache.fory.json.resolver.JsonTypeResolver
import org.apache.fory.json.writer.JsonWriter
import org.apache.fory.reflect.TypeRef
import org.apache.fory.type.Types

/** Supplies semantic unsigned member-name conversion to the existing core map codec. */
internal object KotlinMapKeyCodecs {
  fun create(type: TypeRef<*>, resolver: JsonTypeResolver): JsonValueCodec<*>? {
    if (!Map::class.java.isAssignableFrom(type.rawType)) return null
    val arguments = type.typeArguments
    if (arguments.size != 2) return null
    val keyType = arguments[0]
    val typeId = keyType.typeExtMeta?.typeId() ?: 0
    val keyCodec = keyCodec(keyType) ?: return null
    if (keyType.typeExtMeta?.nullable() == true) {
      throw ForyJsonException("Kotlin unsigned JSON map keys cannot be nullable")
    }
    resolver.checkMapKeySecure(keyType.rawType)
    val valueTypeInfo = resolver.getTypeInfo(arguments[1])
    return MapCodec.createUncheckedKeyCodec(type.rawType, keyClass(typeId), valueTypeInfo, keyCodec)
  }

  /** Returns the terminal member-name codec for an exact boxed U* or primitive physical carrier. */
  fun keyCodec(type: TypeRef<*>): MapKeyCodec? {
    val typeId = type.typeExtMeta?.typeId() ?: return null
    val rawType = type.rawType
    return when (typeId) {
      Types.UINT8 ->
        requireCarrier(
          type,
          typeId,
          rawType == UByte::class.java || rawType == java.lang.Byte.TYPE,
          UByteKeyCodec,
        )
      Types.UINT16 ->
        requireCarrier(
          type,
          typeId,
          rawType == UShort::class.java || rawType == java.lang.Short.TYPE,
          UShortKeyCodec,
        )
      Types.UINT32 ->
        requireCarrier(
          type,
          typeId,
          rawType == UInt::class.java || rawType == java.lang.Integer.TYPE,
          UIntKeyCodec,
        )
      Types.UINT64 ->
        requireCarrier(
          type,
          typeId,
          rawType == ULong::class.java || rawType == java.lang.Long.TYPE,
          ULongKeyCodec,
        )
      else -> null
    }
  }

  private fun requireCarrier(
    type: TypeRef<*>,
    typeId: Int,
    matches: Boolean,
    codec: MapKeyCodec,
  ): MapKeyCodec {
    if (!matches) {
      throw ForyJsonException(
        "Kotlin unsigned map-key carrier ${type.rawType.name} does not match semantic type id " +
          typeId,
      )
    }
    return codec
  }

  private fun keyClass(typeId: Int): Class<*> =
    when (typeId) {
      Types.UINT8 -> UByte::class.java
      Types.UINT16 -> UShort::class.java
      Types.UINT32 -> UInt::class.java
      Types.UINT64 -> ULong::class.java
      else -> throw ForyJsonException("Unknown Kotlin unsigned JSON map-key type id $typeId")
    }

  private object UByteKeyCodec : MapKeyCodec {
    override fun toName(key: Any): String = (key as UByte).toString()

    override fun fromName(name: String): Any = KotlinMapKeyParsing.ubyte(name).toUByte()

    override fun writeName(writer: JsonWriter, key: Any) =
      writer.writeUnsignedIntFieldName((key as UByte).toInt())

    override fun readName(reader: JsonReader): Any =
      KotlinMapKeyParsing.checkedUByte(reader.readFieldNameUnsignedInt()).toUByte()
  }

  private object UShortKeyCodec : MapKeyCodec {
    override fun toName(key: Any): String = (key as UShort).toString()

    override fun fromName(name: String): Any = KotlinMapKeyParsing.ushort(name).toUShort()

    override fun writeName(writer: JsonWriter, key: Any) =
      writer.writeUnsignedIntFieldName((key as UShort).toInt())

    override fun readName(reader: JsonReader): Any =
      KotlinMapKeyParsing.checkedUShort(reader.readFieldNameUnsignedInt()).toUShort()
  }

  private object UIntKeyCodec : MapKeyCodec {
    override fun toName(key: Any): String = (key as UInt).toString()

    override fun fromName(name: String): Any = KotlinMapKeyParsing.uint(name).toUInt()

    override fun writeName(writer: JsonWriter, key: Any) =
      writer.writeUnsignedIntFieldName((key as UInt).toInt())

    override fun readName(reader: JsonReader): Any = reader.readFieldNameUnsignedInt().toUInt()
  }

  private object ULongKeyCodec : MapKeyCodec {
    override fun toName(key: Any): String = (key as ULong).toString()

    override fun fromName(name: String): Any = KotlinMapKeyParsing.ulong(name).toULong()

    override fun writeName(writer: JsonWriter, key: Any) =
      writer.writeUnsignedLongFieldName((key as ULong).toLong())

    override fun readName(reader: JsonReader): Any = reader.readFieldNameUnsignedLong().toULong()
  }
}

/** Shared lexical and width checks for Kotlin integral member names. */
internal object KotlinMapKeyParsing {
  fun byte(name: String): Byte = name.toByteOrNull() ?: invalid("Byte", name)

  fun short(name: String): Short = name.toShortOrNull() ?: invalid("Short", name)

  fun int(name: String): Int = name.toIntOrNull() ?: invalid("Int", name)

  fun long(name: String): Long = name.toLongOrNull() ?: invalid("Long", name)

  fun ubyte(name: String): Byte = name.toUByteOrNull()?.toByte() ?: invalid("UByte", name)

  fun ushort(name: String): Short = name.toUShortOrNull()?.toShort() ?: invalid("UShort", name)

  fun uint(name: String): Int = name.toUIntOrNull()?.toInt() ?: invalid("UInt", name)

  fun ulong(name: String): Long = name.toULongOrNull()?.toLong() ?: invalid("ULong", name)

  fun checkedUByte(value: Int): Byte {
    if (Integer.compareUnsigned(value, UByte.MAX_VALUE.toInt()) > 0) invalid("UByte", value)
    return value.toByte()
  }

  fun checkedUShort(value: Int): Short {
    if (Integer.compareUnsigned(value, UShort.MAX_VALUE.toInt()) > 0) invalid("UShort", value)
    return value.toShort()
  }

  private fun invalid(type: String, value: Any): Nothing =
    throw ForyJsonException("Invalid $type JSON map key: $value")
}

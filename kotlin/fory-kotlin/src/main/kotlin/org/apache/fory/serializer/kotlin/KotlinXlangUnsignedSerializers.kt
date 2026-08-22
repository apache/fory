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

package org.apache.fory.serializer.kotlin

import org.apache.fory.config.Config
import org.apache.fory.context.ReadContext
import org.apache.fory.context.WriteContext
import org.apache.fory.serializer.ImmutableSerializer
import org.apache.fory.serializer.Serializer
import org.apache.fory.serializer.Shareable
import org.apache.fory.type.Types

/** Kotlin unsigned serializers used by generated xlang collection/map field generics. */
@OptIn(ExperimentalUnsignedTypes::class)
public object KotlinXlangUnsignedSerializers {
  @JvmStatic
  public fun serializer(config: Config, typeId: Int, kotlinTypeName: String): Serializer<*> =
    when (kotlinTypeName) {
      "UByte" -> UByteXlangSerializer(config)
      "UShort" -> UShortXlangSerializer(config)
      "UInt" ->
        when (typeId) {
          Types.UINT32 -> FixedUIntXlangSerializer(config)
          Types.VAR_UINT32 -> VarUIntXlangSerializer(config)
          else -> throw unsupported(typeId, kotlinTypeName)
        }
      "ULong" ->
        when (typeId) {
          Types.UINT64 -> FixedULongXlangSerializer(config)
          Types.VAR_UINT64 -> VarULongXlangSerializer(config)
          Types.TAGGED_UINT64 -> TaggedULongXlangSerializer(config)
          else -> throw unsupported(typeId, kotlinTypeName)
        }
      else -> throw unsupported(typeId, kotlinTypeName)
    }

  private fun unsupported(typeId: Int, kotlinTypeName: String): IllegalArgumentException =
    IllegalArgumentException("Unsupported Kotlin unsigned xlang type $kotlinTypeName/$typeId")

  private class UByteXlangSerializer(config: Config) :
    ImmutableSerializer<UByte>(config, UByte::class.java, false, true), Shareable {
    override fun readDataAlwaysAdvances(): Boolean = true

    override fun write(writeContext: WriteContext, value: UByte) {
      writeContext.buffer.writeByte(value.toInt())
    }

    override fun read(readContext: ReadContext): UByte = readContext.buffer.readByte().toUByte()
  }

  private class UShortXlangSerializer(config: Config) :
    ImmutableSerializer<UShort>(config, UShort::class.java, false, true), Shareable {
    override fun readDataAlwaysAdvances(): Boolean = true

    override fun write(writeContext: WriteContext, value: UShort) {
      writeContext.buffer.writeInt16(value.toShort())
    }

    override fun read(readContext: ReadContext): UShort = readContext.buffer.readInt16().toUShort()
  }

  private class FixedUIntXlangSerializer(config: Config) :
    ImmutableSerializer<UInt>(config, UInt::class.java, false, true), Shareable {
    override fun readDataAlwaysAdvances(): Boolean = true

    override fun write(writeContext: WriteContext, value: UInt) {
      writeContext.buffer.writeInt32(value.toInt())
    }

    override fun read(readContext: ReadContext): UInt = readContext.buffer.readInt32().toUInt()
  }

  private class VarUIntXlangSerializer(config: Config) :
    ImmutableSerializer<UInt>(config, UInt::class.java, false, true), Shareable {
    override fun readDataAlwaysAdvances(): Boolean = true

    override fun write(writeContext: WriteContext, value: UInt) {
      writeContext.buffer.writeVarUInt32(value.toInt())
    }

    override fun read(readContext: ReadContext): UInt = readContext.buffer.readVarUInt32().toUInt()
  }

  private class FixedULongXlangSerializer(config: Config) :
    ImmutableSerializer<ULong>(config, ULong::class.java, false, true), Shareable {
    override fun readDataAlwaysAdvances(): Boolean = true

    override fun write(writeContext: WriteContext, value: ULong) {
      writeContext.buffer.writeInt64(value.toLong())
    }

    override fun read(readContext: ReadContext): ULong = readContext.buffer.readInt64().toULong()
  }

  private class VarULongXlangSerializer(config: Config) :
    ImmutableSerializer<ULong>(config, ULong::class.java, false, true), Shareable {
    override fun readDataAlwaysAdvances(): Boolean = true

    override fun write(writeContext: WriteContext, value: ULong) {
      writeContext.buffer.writeVarUInt64(value.toLong())
    }

    override fun read(readContext: ReadContext): ULong =
      readContext.buffer.readVarUInt64().toULong()
  }

  private class TaggedULongXlangSerializer(config: Config) :
    ImmutableSerializer<ULong>(config, ULong::class.java, false, true), Shareable {
    override fun readDataAlwaysAdvances(): Boolean = true

    override fun write(writeContext: WriteContext, value: ULong) {
      writeContext.buffer.writeTaggedUInt64(value.toLong())
    }

    override fun read(readContext: ReadContext): ULong =
      readContext.buffer.readTaggedUInt64().toULong()
  }
}

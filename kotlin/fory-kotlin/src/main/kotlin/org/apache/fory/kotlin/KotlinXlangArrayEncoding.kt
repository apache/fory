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

@file:OptIn(ExperimentalUnsignedTypes::class)

package org.apache.fory.kotlin

import org.apache.fory.context.ReadContext
import org.apache.fory.context.WriteContext
import org.apache.fory.exception.DeserializationException
import org.apache.fory.memory.MemoryBuffer
import org.apache.fory.type.Types

/** Kotlin/JVM carrier helpers for Fory xlang dense unsigned array bodies. */
public object KotlinXlangArrayEncoding {
  @JvmStatic
  public fun writeUByteArray(writeContext: WriteContext, value: UByteArray) {
    val buffer = writeContext.buffer
    if (writeContext.bufferCallback == null) {
      buffer.writeVarUInt32Small7(value.size)
      for (i in value.indices) {
        buffer.writeByte(value[i].toInt())
      }
    } else {
      writeContext.writeBufferObject(UnsignedArrayBufferObject(value, Types.UINT8_ARRAY))
    }
  }

  @JvmStatic
  public fun readUByteArray(readContext: ReadContext): UByteArray {
    val buffer = arrayBuffer(readContext)
    val size = arrayByteSize(readContext, buffer, 1)
    return UByteArray(size) { buffer.readByte().toUByte() }
  }

  @JvmStatic
  public fun writeUShortArray(writeContext: WriteContext, value: UShortArray) {
    val buffer = writeContext.buffer
    if (writeContext.bufferCallback == null) {
      buffer.writeVarUInt32Small7(value.size * Short.SIZE_BYTES)
      for (i in value.indices) {
        buffer.writeInt16(value[i].toShort())
      }
    } else {
      writeContext.writeBufferObject(UnsignedArrayBufferObject(value, Types.UINT16_ARRAY))
    }
  }

  @JvmStatic
  public fun readUShortArray(readContext: ReadContext): UShortArray {
    val buffer = arrayBuffer(readContext)
    val size = arrayByteSize(readContext, buffer, Short.SIZE_BYTES)
    return UShortArray(size / Short.SIZE_BYTES) { buffer.readInt16().toUShort() }
  }

  @JvmStatic
  public fun writeUIntArray(writeContext: WriteContext, value: UIntArray) {
    val buffer = writeContext.buffer
    if (writeContext.bufferCallback == null) {
      buffer.writeVarUInt32Small7(value.size * Int.SIZE_BYTES)
      for (i in value.indices) {
        buffer.writeInt32(value[i].toInt())
      }
    } else {
      writeContext.writeBufferObject(UnsignedArrayBufferObject(value, Types.UINT32_ARRAY))
    }
  }

  @JvmStatic
  public fun readUIntArray(readContext: ReadContext): UIntArray {
    val buffer = arrayBuffer(readContext)
    val size = arrayByteSize(readContext, buffer, Int.SIZE_BYTES)
    return UIntArray(size / Int.SIZE_BYTES) { buffer.readInt32().toUInt() }
  }

  @JvmStatic
  public fun writeULongArray(writeContext: WriteContext, value: ULongArray) {
    val buffer = writeContext.buffer
    if (writeContext.bufferCallback == null) {
      buffer.writeVarUInt32Small7(value.size * Long.SIZE_BYTES)
      for (i in value.indices) {
        buffer.writeInt64(value[i].toLong())
      }
    } else {
      writeContext.writeBufferObject(UnsignedArrayBufferObject(value, Types.UINT64_ARRAY))
    }
  }

  @JvmStatic
  public fun readULongArray(readContext: ReadContext): ULongArray {
    val buffer = arrayBuffer(readContext)
    val size = arrayByteSize(readContext, buffer, Long.SIZE_BYTES)
    return ULongArray(size / Long.SIZE_BYTES) { buffer.readInt64().toULong() }
  }

  @JvmStatic public fun toUByteArray(value: ByteArray): UByteArray = value.toUByteArray()

  @JvmStatic public fun toUShortArray(value: ShortArray): UShortArray = value.toUShortArray()

  @JvmStatic public fun toUIntArray(value: IntArray): UIntArray = value.toUIntArray()

  @JvmStatic public fun toULongArray(value: LongArray): ULongArray = value.toULongArray()

  @JvmStatic
  public fun toByteArray(value: UByteArray): ByteArray {
    val result = ByteArray(value.size)
    for (i in value.indices) {
      result[i] = value[i].toByte()
    }
    return result
  }

  @JvmStatic
  public fun toShortArray(value: UShortArray): ShortArray {
    val result = ShortArray(value.size)
    for (i in value.indices) {
      result[i] = value[i].toShort()
    }
    return result
  }

  @JvmStatic
  public fun toIntArray(value: UIntArray): IntArray {
    val result = IntArray(value.size)
    for (i in value.indices) {
      result[i] = value[i].toInt()
    }
    return result
  }

  @JvmStatic
  public fun toLongArray(value: ULongArray): LongArray {
    val result = LongArray(value.size)
    for (i in value.indices) {
      result[i] = value[i].toLong()
    }
    return result
  }

  private fun arrayBuffer(readContext: ReadContext): MemoryBuffer =
    if (readContext.isPeerOutOfBandEnabled) readContext.readBufferObject() else readContext.buffer

  private fun arrayByteSize(readContext: ReadContext, buffer: MemoryBuffer, elementSize: Int): Int {
    val size =
      if (readContext.isPeerOutOfBandEnabled) buffer.remaining() else buffer.readVarUInt32Small7()
    if (size < 0) {
      throw DeserializationException("Array byte size must be non-negative: $size")
    }
    if (size % elementSize != 0) {
      throw DeserializationException(
        "Array byte size $size is not aligned to element size $elementSize"
      )
    }
    buffer.checkReadableBytes(size)
    return size
  }

  private class UnsignedArrayBufferObject(private val value: Any, private val typeId: Int) :
    org.apache.fory.serializer.BufferObject {
    override fun totalBytes(): Int =
      when (typeId) {
        Types.UINT8_ARRAY -> (value as UByteArray).size
        Types.UINT16_ARRAY -> (value as UShortArray).size * Short.SIZE_BYTES
        Types.UINT32_ARRAY -> (value as UIntArray).size * Int.SIZE_BYTES
        Types.UINT64_ARRAY -> (value as ULongArray).size * Long.SIZE_BYTES
        else -> throw IllegalArgumentException("Unsupported unsigned array type $typeId")
      }

    override fun writeTo(buffer: MemoryBuffer) {
      when (typeId) {
        Types.UINT8_ARRAY -> {
          val array = value as UByteArray
          for (i in array.indices) {
            buffer.writeByte(array[i].toInt())
          }
        }
        Types.UINT16_ARRAY -> {
          val array = value as UShortArray
          for (i in array.indices) {
            buffer.writeInt16(array[i].toShort())
          }
        }
        Types.UINT32_ARRAY -> {
          val array = value as UIntArray
          for (i in array.indices) {
            buffer.writeInt32(array[i].toInt())
          }
        }
        Types.UINT64_ARRAY -> {
          val array = value as ULongArray
          for (i in array.indices) {
            buffer.writeInt64(array[i].toLong())
          }
        }
        else -> throw IllegalArgumentException("Unsupported unsigned array type $typeId")
      }
    }

    override fun toBuffer(): MemoryBuffer {
      val buffer = MemoryBuffer.newHeapBuffer(totalBytes())
      writeTo(buffer)
      return buffer.slice(0, buffer.writerIndex())
    }
  }
}

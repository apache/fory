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

import org.apache.fory.Fory
import org.apache.fory.memory.MemoryBuffer
import org.apache.fory.serializer.Serializers

/**
 * UByteSerializer
 *
 * UByte is mapped to Type.UINT8
 */
public class UByteSerializer(
  fory: Fory,
) : Serializers.CrossLanguageCompatibleSerializer<UByte>(fory, UByte::class.java, false, true) {

  override fun write(buffer: MemoryBuffer, value: UByte) {
    buffer.writeByte(value.toInt())
  }

  override fun read(buffer: MemoryBuffer): UByte {
    return buffer.readByte().toUByte()
  }
}

/**
 * UShortSerializer
 *
 * UShort is mapped to Type.UINT16.
 */
public class UShortSerializer(
  fory: Fory,
) : Serializers.CrossLanguageCompatibleSerializer<UShort>(fory, UShort::class.java, false, true) {
  override fun write(buffer: MemoryBuffer, value: UShort) {
    buffer.writeVarUint32(value.toInt())
  }

  override fun read(buffer: MemoryBuffer): UShort {
    return buffer.readVarUint32().toUShort()
  }
}

/**
 * UInt Serializer
 *
 * UInt is mapped to Type.UINT32.
 */
public class UIntSerializer(
  fory: Fory,
) : Serializers.CrossLanguageCompatibleSerializer<UInt>(fory, UInt::class.java, false, true) {

  override fun write(buffer: MemoryBuffer, value: UInt) {
    buffer.writeVarUint32(value.toInt())
  }

  override fun read(buffer: MemoryBuffer): UInt {
    return buffer.readVarUint32().toUInt()
  }
}

/**
 * ULong Serializer
 *
 * ULong is mapped to Type.UINT64.
 */
public class ULongSerializer(
  fory: Fory,
) : Serializers.CrossLanguageCompatibleSerializer<ULong>(fory, ULong::class.java, false, true) {
  override fun write(buffer: MemoryBuffer, value: ULong) {
    buffer.writeVarUint64(value.toLong())
  }

  override fun read(buffer: MemoryBuffer): ULong {
    return buffer.readVarUint64().toULong()
  }
}

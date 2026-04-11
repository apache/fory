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
import org.apache.fory.serializer.Shareable

/**
 * UByteSerializer
 *
 * UByte is mapped to Type.UINT8
 */
public class UByteSerializer(
  config: Config,
) : ImmutableSerializer<UByte>(config, UByte::class.java, false, true), Shareable {

  override fun write(writeContext: WriteContext, value: UByte) {
    writeContext.buffer.writeByte(value.toInt())
  }

  override fun read(readContext: ReadContext): UByte {
    return readContext.buffer.readByte().toUByte()
  }
}

/**
 * UShortSerializer
 *
 * UShort is mapped to Type.UINT16.
 */
public class UShortSerializer(
  config: Config,
) : ImmutableSerializer<UShort>(config, UShort::class.java, false, true), Shareable {
  override fun write(writeContext: WriteContext, value: UShort) {
    writeContext.buffer.writeVarUint32(value.toInt())
  }

  override fun read(readContext: ReadContext): UShort {
    return readContext.buffer.readVarUint32().toUShort()
  }
}

/**
 * UInt Serializer
 *
 * UInt is mapped to Type.UINT32.
 */
public class UIntSerializer(
  config: Config,
) : ImmutableSerializer<UInt>(config, UInt::class.java, false, true), Shareable {

  override fun write(writeContext: WriteContext, value: UInt) {
    writeContext.buffer.writeVarUint32(value.toInt())
  }

  override fun read(readContext: ReadContext): UInt {
    return readContext.buffer.readVarUint32().toUInt()
  }
}

/**
 * ULong Serializer
 *
 * ULong is mapped to Type.UINT64.
 */
public class ULongSerializer(
  config: Config,
) : ImmutableSerializer<ULong>(config, ULong::class.java, false, true), Shareable {
  override fun write(writeContext: WriteContext, value: ULong) {
    writeContext.buffer.writeVarUint64(value.toLong())
  }

  override fun read(readContext: ReadContext): ULong {
    return readContext.buffer.readVarUint64().toULong()
  }
}

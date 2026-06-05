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

package org.apache.fory.serializer;

import java.nio.ByteBuffer;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.exception.DeserializationException;
import org.apache.fory.memory.ByteBufferUtil;
import org.apache.fory.memory.MemoryBuffer;
import org.testng.annotations.Test;

public class BufferSerializersTest extends ForyTestBase {

  @Test
  public void testByteBuffer() {
    Fory fory = Fory.builder().withXlang(false).withCompatible(false).build();
    ByteBuffer buffer1 = ByteBuffer.allocate(32);
    buffer1.putLong(1000L);
    ByteBufferUtil.rewind(buffer1);
    serDeCheck(fory, buffer1);
    ByteBuffer buffer2 = ByteBuffer.allocateDirect(32);
    buffer2.putDouble(1.0 / 3);
    ByteBufferUtil.rewind(buffer2);
    serDeCheck(fory, buffer2);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testByteBuffer(Fory fory) {
    ByteBuffer buffer1 = ByteBuffer.allocate(32);
    buffer1.putLong(1000L);
    ByteBufferUtil.rewind(buffer1);
    copyCheck(fory, buffer1);
    ByteBuffer buffer2 = ByteBuffer.allocateDirect(32);
    buffer2.putDouble(1.0 / 3);
    ByteBufferUtil.rewind(buffer2);
    copyCheck(fory, buffer2);
  }

  @Test
  public void testByteBufferRejectsMalformedPayload() {
    Fory fory = Fory.builder().withXlang(false).withCompatible(false).build();
    Serializer<ByteBuffer> serializer =
        new BufferSerializers.ByteBufferSerializer(fory.getTypeResolver(), ByteBuffer.class);

    MemoryBuffer zeroSize = MemoryBuffer.newHeapBuffer(16);
    zeroSize.writeBoolean(true);
    zeroSize.writeVarUInt32Aligned(0);
    org.testng.Assert.assertThrows(
        DeserializationException.class, () -> readSerializer(fory, serializer, zeroSize));

    MemoryBuffer invalidOrder = MemoryBuffer.newHeapBuffer(16);
    invalidOrder.writeBoolean(true);
    invalidOrder.writeVarUInt32Aligned(1);
    invalidOrder.writeByte(2);
    org.testng.Assert.assertThrows(
        DeserializationException.class, () -> readSerializer(fory, serializer, invalidOrder));
  }

  @Test
  public void testBufferObjectRejectsInvalidInBandSizeWithoutBinaryCap() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).build();
    Serializer<ByteBuffer> serializer =
        new BufferSerializers.ByteBufferSerializer(fory.getTypeResolver(), ByteBuffer.class);

    MemoryBuffer negativeSize = MemoryBuffer.newHeapBuffer(16);
    negativeSize.writeBoolean(true);
    negativeSize.writeVarUInt32(-1);
    org.testng.Assert.assertThrows(
        IllegalArgumentException.class, () -> readSerializer(fory, serializer, negativeSize));

    MemoryBuffer truncated = MemoryBuffer.newHeapBuffer(16);
    truncated.writeBoolean(true);
    truncated.writeVarUInt32(2);
    truncated.writeByte(0);
    MemoryBuffer truncatedPayload = truncated.slice(0, truncated.writerIndex());
    org.testng.Assert.assertThrows(
        IndexOutOfBoundsException.class, () -> readSerializer(fory, serializer, truncatedPayload));
  }
}

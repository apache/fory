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

import com.google.protobuf.ByteString;
import org.apache.fory.Fory;
import org.apache.fory.memory.MemoryBuffer;

public class ByteStringSerializer extends Serializer<ByteString> {
  public ByteStringSerializer(Fory fory, Class<ByteString> type) {
    super(fory, type);
  }

  @Override
  public void write(MemoryBuffer buffer, ByteString value) {
    int size = value.size();
    buffer.writeVarUint32(size);
    buffer.grow(size);
    byte[] heapMemory = buffer.getHeapMemory();
    if (heapMemory != null) {
      int writerIndex = buffer._unsafeHeapWriterIndex();
      value.copyTo(heapMemory, writerIndex);
    } else {
      value.copyTo(buffer.sliceAsByteBuffer(buffer.writerIndex(), size));
    }
    buffer.increaseWriterIndex(size);
  }

  @Override
  public ByteString read(MemoryBuffer buffer) {
    int size = buffer.readVarUint32Small14();
    buffer.checkReadableBytes(size);
    byte[] heapMemory = buffer.getHeapMemory();
    if (heapMemory != null) {
      ByteString bytes = ByteString.copyFrom(heapMemory, buffer._unsafeHeapReaderIndex(), size);
      buffer.increaseReaderIndex(size);
      return bytes;
    } else {
      ByteString bytes = ByteString.copyFrom(buffer.sliceAsByteBuffer(buffer.readerIndex(), size));
      buffer.increaseReaderIndex(size);
      return bytes;
    }
  }
}

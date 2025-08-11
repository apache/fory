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

package org.apache.fory.extension.serializer;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.Message;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import org.apache.fory.Fory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.util.unsafe._JDKAccess;

@SuppressWarnings({"rawtypes", "unchecked"})
public class ProtobufSerializer extends Serializer<Message> {
  private static final int SMALL_BYTES_THRESHOLD = 32;

  private static final ClassValue<MethodHandle[]> parseFromMethodCache =
      new ClassValue<MethodHandle[]>() {
        @Override
        protected MethodHandle[] computeValue(Class<?> type) {
          try {
            MethodHandles.Lookup lookup = _JDKAccess._trustedLookup(type);
            MethodHandle parseFrom1 =
                lookup.findStatic(
                    type, "parseFrom", MethodType.methodType(type, CodedInputStream.class));
            MethodHandle parseFrom2 =
                lookup.findStatic(type, "parseFrom", MethodType.methodType(type, ByteBuffer.class));
            return new MethodHandle[] {parseFrom1, parseFrom2};
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }
      };
  private final MethodHandle parseFromStream;
  private final ExtensionRegistryLite emptyRegistry;
  private final MethodHandle parseFromByteBuffer;

  public ProtobufSerializer(Fory fory, Class type) {
    super(fory, type, true);
    MethodHandle[] methodHandles = parseFromMethodCache.get(type);
    this.parseFromStream = methodHandles[0];
    this.parseFromByteBuffer = methodHandles[0];
    emptyRegistry = ExtensionRegistryLite.getEmptyRegistry();
  }

  @Override
  public void write(MemoryBuffer buffer, Message value) {
    int size = value.getSerializedSize();
    buffer.writeVarUint32(size);
    buffer.grow(size);
    byte[] heapMemory = buffer.getHeapMemory();
    try {
      if (heapMemory != null) {
        int writerIndex = buffer._unsafeHeapWriterIndex();
        CodedOutputStream stream = CodedOutputStream.newInstance(heapMemory, writerIndex, size);
        value.writeTo(stream);
        buffer.increaseWriterIndex(size);
      } else {
        if (size < SMALL_BYTES_THRESHOLD) {
          buffer.writeBytes(value.toByteArray());
        } else {
          ByteBuffer buf = buffer.sliceAsByteBuffer(buffer._unsafeHeapWriterIndex(), size);
          CodedOutputStream stream = CodedOutputStream.newInstance(buf);
          value.writeTo(stream);
          buffer.increaseWriterIndex(size);
        }
      }
    } catch (IOException e) {
      Platform.throwException(e);
    }
  }

  @Override
  public Message read(MemoryBuffer buffer) {
    int size = buffer.readVarUint32Small14();
    buffer.checkReadableBytes(size);
    byte[] heapMemory = buffer.getHeapMemory();
    try {
      if (heapMemory != null) {
        CodedInputStream stream =
            CodedInputStream.newInstance(heapMemory, buffer._unsafeHeapReaderIndex(), size);
        buffer.increaseReaderIndex(size);
        return (Message) parseFromStream.invoke(stream);
      } else {
        if (size < SMALL_BYTES_THRESHOLD) {
          byte[] bytes = buffer.readBytes(size);
          return (Message) parseFromStream.invoke(bytes, emptyRegistry);
        } else {
          ByteBuffer byteBuffer = buffer.sliceAsByteBuffer(buffer.readerIndex(), size);
          buffer.increaseReaderIndex(size);
          return (Message) parseFromByteBuffer.invoke(byteBuffer);
        }
      }
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }
}

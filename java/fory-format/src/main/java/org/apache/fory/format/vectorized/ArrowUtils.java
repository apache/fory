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

package org.apache.fory.format.vectorized;

import java.io.IOException;
import java.nio.channels.Channels;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ReadChannel;
import org.apache.arrow.vector.ipc.WriteChannel;
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch;
import org.apache.arrow.vector.ipc.message.MessageSerializer;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.fory.format.type.ArrowSchemaConverter;
import org.apache.fory.io.MemoryBufferInputStream;
import org.apache.fory.io.MemoryBufferOutputStream;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.util.DecimalUtils;

/** Arrow utils. */
public class ArrowUtils {
  // RootAllocator is thread-safe, so we don't have to use thread-local.
  // FIXME JDK17: Unable to make field long java.nio.Buffer.address
  //   accessible: module java.base does not "opens java.nio" to unnamed module @405e4200
  public static RootAllocator allocator = new RootAllocator();
  private static final ThreadLocal<ArrowBuf> decimalArrowBuf =
      ThreadLocal.withInitial(() -> buffer(DecimalUtils.DECIMAL_BYTE_LENGTH));

  public static ArrowBuf buffer(final long initialRequestSize) {
    return allocator.buffer(initialRequestSize);
  }

  public static ArrowBuf decimalArrowBuf() {
    return decimalArrowBuf.get();
  }

  public static VectorSchemaRoot createVectorSchemaRoot(Schema arrowSchema) {
    return VectorSchemaRoot.create(arrowSchema, allocator);
  }

  public static VectorSchemaRoot createVectorSchemaRoot(
      org.apache.fory.format.type.Schema forySchema) {
    return VectorSchemaRoot.create(ArrowSchemaConverter.toArrowSchema(forySchema), allocator);
  }

  public static ArrowWriter createArrowWriter(Schema arrowSchema) {
    VectorSchemaRoot root = VectorSchemaRoot.create(arrowSchema, allocator);
    return new ArrowWriter(root);
  }

  public static ArrowWriter createArrowWriter(org.apache.fory.format.type.Schema forySchema) {
    VectorSchemaRoot root =
        VectorSchemaRoot.create(ArrowSchemaConverter.toArrowSchema(forySchema), allocator);
    return new ArrowWriter(root);
  }

  public static void serializeRecordBatch(ArrowRecordBatch recordBatch, MemoryBuffer buffer) {
    // TODO(chaokunyang) add custom WritableByteChannel to avoid copy in `WritableByteChannelImpl`
    try (WriteChannel channel =
        new WriteChannel(Channels.newChannel(new MemoryBufferOutputStream(buffer)))) {
      MessageSerializer.serialize(channel, recordBatch);
    } catch (IOException e) {
      throw new RuntimeException(String.format("Serialize record batch %s failed", recordBatch), e);
    }
  }

  public static ArrowRecordBatch deserializeRecordBatch(MemoryBuffer recordBatchMessageBuffer) {
    // TODO(chaokunyang) add custom ReadableByteChannel to avoid copy in `ReadableByteChannelImpl`
    try (ReadChannel channel =
        new ReadChannel(
            Channels.newChannel(new MemoryBufferInputStream(recordBatchMessageBuffer)))) {
      return MessageSerializer.deserializeRecordBatch(channel, allocator);
    } catch (IOException e) {
      throw new RuntimeException("Deserialize record batch failed", e);
    }
  }
}

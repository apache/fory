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
  private static final RuntimeException ALLOCATOR_ERROR;

  // RootAllocator is thread-safe, so we don't have to use thread-local.
  // Arrow 18.x initializes its own sun.misc.Unsafe memory facade eagerly. Keep Fory class loading
  // possible under JDK25 deny mode and fail only when the vectorized Arrow path is used.
  public static RootAllocator allocator;

  static {
    RootAllocator rootAllocator = null;
    RuntimeException allocatorError = null;
    if (isUnsafeMemoryDenied()) {
      allocatorError =
          new UnsupportedOperationException(
              "Apache Arrow vectorized format is unavailable when JDK Unsafe memory access is "
                  + "denied. Apache Arrow initializes sun.misc.Unsafe memory access internally.");
    } else {
      try {
        rootAllocator = new RootAllocator();
      } catch (RuntimeException | ExceptionInInitializerError e) {
        if (!isUnsafeMemoryAccessFailure(e)) {
          throw e;
        }
        allocatorError =
            new UnsupportedOperationException(
                "Apache Arrow vectorized format is unavailable when Apache Arrow cannot initialize "
                    + "its sun.misc.Unsafe memory access.",
                e);
      }
    }
    allocator = rootAllocator;
    ALLOCATOR_ERROR = allocatorError;
  }

  private static final ThreadLocal<ArrowBuf> decimalArrowBuf =
      ThreadLocal.withInitial(() -> buffer(DecimalUtils.DECIMAL_BYTE_LENGTH));

  public static ArrowBuf buffer(final long initialRequestSize) {
    return requireAllocator().buffer(initialRequestSize);
  }

  public static ArrowBuf decimalArrowBuf() {
    return decimalArrowBuf.get();
  }

  public static VectorSchemaRoot createVectorSchemaRoot(Schema arrowSchema) {
    return VectorSchemaRoot.create(arrowSchema, requireAllocator());
  }

  public static VectorSchemaRoot createVectorSchemaRoot(
      org.apache.fory.format.type.Schema forySchema) {
    return VectorSchemaRoot.create(
        ArrowSchemaConverter.toArrowSchema(forySchema), requireAllocator());
  }

  public static ArrowWriter createArrowWriter(Schema arrowSchema) {
    VectorSchemaRoot root = VectorSchemaRoot.create(arrowSchema, requireAllocator());
    return new ArrowWriter(root);
  }

  public static ArrowWriter createArrowWriter(org.apache.fory.format.type.Schema forySchema) {
    VectorSchemaRoot root =
        VectorSchemaRoot.create(ArrowSchemaConverter.toArrowSchema(forySchema), requireAllocator());
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
      return MessageSerializer.deserializeRecordBatch(channel, requireAllocator());
    } catch (IOException e) {
      throw new RuntimeException("Deserialize record batch failed", e);
    }
  }

  private static RootAllocator requireAllocator() {
    if (allocator != null) {
      return allocator;
    }
    throw ALLOCATOR_ERROR;
  }

  private static boolean isUnsafeMemoryDenied() {
    return Runtime.version().feature() >= 25
        && "deny".equals(System.getProperty("sun.misc.unsafe.memory.access"));
  }

  private static boolean isUnsafeMemoryAccessFailure(Throwable throwable) {
    Throwable cause = throwable;
    while (cause != null) {
      if (cause instanceof UnsupportedOperationException
          && cause.getMessage() != null
          && cause.getMessage().contains("arrayBaseOffset")) {
        return true;
      }
      if (cause instanceof UnsupportedOperationException) {
        for (StackTraceElement element : cause.getStackTrace()) {
          if ("sun.misc.Unsafe".equals(element.getClassName())) {
            return true;
          }
        }
      }
      if ("java.lang.reflect.InaccessibleObjectException".equals(cause.getClass().getName())
          && cause.getMessage() != null
          && cause.getMessage().contains("java.nio")) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }
}

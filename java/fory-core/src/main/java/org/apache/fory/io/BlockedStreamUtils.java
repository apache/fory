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

package org.apache.fory.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.fory.Fory;
import org.apache.fory.exception.DeserializationException;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.serializer.BufferCallback;
import org.apache.fory.util.ExceptionUtils;

/**
 * A serialization helper as the fallback of streaming serialization/deserialization in {@link
 * ForyInputStream}/{@link ForyReadableChannel}. {@link ForyInputStream}/{@link ForyReadableChannel}
 * will buffer and read more data, which makes the original passed stream when constructing {@link
 * ForyInputStream} not usable. If this is not possible, use this {@link BlockedStreamUtils} instead
 * for streaming serialization and deserialization.
 *
 * <p>Note that this mode will disable streaming in essence. It's just a helper for make the usage
 * in streaming interface more easily. The deserialization will read whole bytes before do the
 * actual deserialization, which don't have any streaming behaviour under the hood.
 */
public class BlockedStreamUtils {
  private static final int MAX_CONSECUTIVE_ZERO_READS = 100;

  public static void serialize(Fory fory, OutputStream outputStream, Object obj) {
    serializeToStream(fory, outputStream, buf -> fory.serialize(buf, obj, null));
  }

  public static void serialize(
      Fory fory, OutputStream outputStream, Object obj, BufferCallback callback) {
    serializeToStream(fory, outputStream, buf -> fory.serialize(buf, obj, callback));
  }

  public static Object deserialize(Fory fory, InputStream inputStream) {
    return deserialize(fory, inputStream, (Iterable<MemoryBuffer>) null);
  }

  public static Object deserialize(
      Fory fory, InputStream inputStream, Iterable<MemoryBuffer> outOfBandBuffers) {
    return deserializeFromStream(fory, inputStream, buf -> fory.deserialize(buf, outOfBandBuffers));
  }

  public static Object deserialize(Fory fory, ReadableByteChannel channel) {
    return readFromChannel(fory, channel, b -> fory.deserialize(b, (Iterable<MemoryBuffer>) null));
  }

  public static Object deserialize(
      Fory fory, ReadableByteChannel channel, Iterable<MemoryBuffer> outOfBandBuffers) {
    return readFromChannel(fory, channel, b -> fory.deserialize(b, outOfBandBuffers));
  }

  @SuppressWarnings("unchecked")
  public static <T> T deserialize(Fory fory, InputStream inputStream, Class<T> type) {
    return (T) deserializeFromStream(fory, inputStream, buf -> fory.deserialize(buf, type));
  }

  public static <T> T deserialize(Fory fory, ReadableByteChannel channel, Class<T> type) {
    return type.cast(readFromChannel(fory, channel, b -> fory.deserialize(b, type)));
  }

  private static Object readFromChannel(
      Fory fory, ReadableByteChannel channel, Function<MemoryBuffer, Object> action) {
    try {
      MemoryBuffer buf = fory.getBuffer();
      // resetBuffer may shrink the reusable buffer below the fixed frame header size.
      buf.ensure(4);
      buf.readerIndex(0);
      readByteBuffer(channel, buf.sliceAsByteBuffer(0, 4), 4);
      int size = readFrameSize(buf);
      readFrameBody(channel, buf, size);
      return action.apply(buf.slice(0, size));
    } catch (Throwable t) {
      throw ExceptionUtils.handleReadFailed(fory, t);
    } finally {
      fory.resetBuffer();
    }
  }

  private static void readByteBuffer(ReadableByteChannel channel, ByteBuffer buffer, int size) {
    int read = 0;
    int zeroReads = 0;
    buffer.limit(buffer.position() + size);
    try {
      while (read < size) {
        int len = channel.read(buffer);
        if (len == -1) {
          throw new DeserializationException(
              String.format("Channel only have %s, but need %s", read, size));
        }
        if (len == 0) {
          // Zero is a legal transient channel result. Keep the current frame position instead of
          // abandoning a partial header/body, but bound retries so a broken or non-ready channel
          // cannot spin forever.
          if (++zeroReads >= MAX_CONSECUTIVE_ZERO_READS) {
            throw new DeserializationException("Channel made no progress while reading a frame");
          }
          continue;
        }
        zeroReads = 0;
        read += len;
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    buffer.rewind();
  }

  private static void serializeToStream(
      Fory fory, OutputStream outputStream, Consumer<MemoryBuffer> function) {
    MemoryBuffer buf = fory.getBuffer();
    buf.writerIndex(0);
    try {
      buf.writeInt32(-1);
      function.accept(buf);
      buf.putInt32(0, buf.writerIndex() - 4);
      byte[] bytes = buf.getHeapMemory();
      if (bytes != null) {
        outputStream.write(bytes, 0, buf.writerIndex());
      } else {
        outputStream.write(buf.getBytes(0, buf.writerIndex()));
      }
      outputStream.flush();
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      fory.resetBuffer();
    }
  }

  private static Object deserializeFromStream(
      Fory fory, InputStream inputStream, Function<MemoryBuffer, Object> function) {
    MemoryBuffer buf = fory.getBuffer();
    try {
      MemoryBuffer frame = readToBufferFromStream(inputStream, buf);
      return function.apply(frame);
    } catch (Throwable t) {
      throw ExceptionUtils.handleReadFailed(fory, t);
    } finally {
      fory.resetBuffer();
    }
  }

  private static MemoryBuffer readToBufferFromStream(InputStream inputStream, MemoryBuffer buffer)
      throws IOException {
    // resetBuffer may shrink the reusable buffer below the fixed frame header size.
    buffer.ensure(4);
    buffer.readerIndex(0);
    int read = readBytes(inputStream, buffer.getHeapMemory(), 0, 4);
    if (read != 4) {
      throw new DeserializationException(
          String.format("Input stream only has %s frame header bytes, but needs 4", read));
    }
    int size = readFrameSize(buffer);
    readFrameBody(inputStream, buffer, size);
    return buffer.slice(0, size);
  }

  private static int readFrameSize(MemoryBuffer buffer) {
    int size = buffer.getInt32(0);
    if (size < 0) {
      throw new DeserializationException("Frame size must be non-negative: " + size);
    }
    return size;
  }

  private static void readFrameBody(InputStream inputStream, MemoryBuffer buffer, int frameSize)
      throws IOException {
    int read = 0;
    while (read < frameSize) {
      if (read == buffer.size()) {
        growFrameBuffer(buffer, frameSize);
      }
      int chunkSize = Math.min(frameSize - read, buffer.size() - read);
      int count = readBytes(inputStream, buffer.getHeapMemory(), read, chunkSize);
      read += Math.max(count, 0);
      if (count != chunkSize) {
        throw new DeserializationException(
            String.format("Input stream only has %s frame bytes, but needs %s", read, frameSize));
      }
    }
  }

  private static void readFrameBody(
      ReadableByteChannel channel, MemoryBuffer buffer, int frameSize) {
    int read = 0;
    while (read < frameSize) {
      if (read == buffer.size()) {
        growFrameBuffer(buffer, frameSize);
      }
      int chunkSize = Math.min(frameSize - read, buffer.size() - read);
      readByteBuffer(channel, buffer.sliceAsByteBuffer(read, chunkSize), chunkSize);
      read += chunkSize;
    }
  }

  private static void growFrameBuffer(MemoryBuffer buffer, int frameSize) {
    int capacity = buffer.size();
    // Grow only after the current capacity has been filled with bytes from the stream. Doubling
    // keeps copying linear while ensuring a declared frame size cannot trigger eager allocation.
    int newCapacity = capacity <= frameSize - capacity ? capacity << 1 : frameSize;
    buffer.ensure(newCapacity);
  }

  private static int readBytes(InputStream inputStream, byte[] buffer, int offset, int size)
      throws IOException {
    int read = 0;
    int count = 0;
    while (read < size) {
      if ((count = inputStream.read(buffer, offset + read, size - read)) == -1) {
        break;
      }
      read += count;
    }
    return (read == 0 && count == -1) ? -1 : read;
  }
}

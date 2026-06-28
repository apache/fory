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
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import javax.annotation.concurrent.NotThreadSafe;
import org.apache.fory.exception.DeserializationException;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.util.Preconditions;

@NotThreadSafe
public class ForyReadableChannel implements ForyStreamReader, ReadableByteChannel {
  private final ReadableByteChannel channel;
  private final SeekableByteChannel seekableChannel;
  private final MemoryBuffer memoryBuffer;
  private ByteBuffer byteBuffer;

  public ForyReadableChannel(ReadableByteChannel channel) {
    this(
        channel,
        AndroidSupport.IS_ANDROID ? ByteBuffer.allocate(4096) : ByteBuffer.allocateDirect(4096),
        null);
  }

  public ForyReadableChannel(SeekableByteChannel channel) {
    this(
        channel,
        AndroidSupport.IS_ANDROID ? ByteBuffer.allocate(4096) : ByteBuffer.allocateDirect(4096),
        channel);
  }

  public ForyReadableChannel(ReadableByteChannel channel, ByteBuffer buffer) {
    this(channel, buffer, null);
  }

  public ForyReadableChannel(SeekableByteChannel channel, ByteBuffer buffer) {
    this(channel, buffer, channel);
  }

  private ForyReadableChannel(
      ReadableByteChannel channel, ByteBuffer buffer, SeekableByteChannel seekableChannel) {
    Preconditions.checkArgument(
        !buffer.isReadOnly(), "ForyReadableChannel requires writable ByteBuffer.");
    this.channel = channel;
    this.seekableChannel = seekableChannel;
    if (AndroidSupport.IS_ANDROID && buffer.isDirect()) {
      buffer = ByteBuffer.allocate(buffer.capacity());
    }
    this.byteBuffer = buffer;
    if (buffer.isDirect()) {
      this.memoryBuffer = MemoryBuffer.fromDirectByteBuffer(buffer, 0, this);
    } else if (buffer.hasArray()) {
      this.memoryBuffer =
          MemoryBuffer.fromByteArray(
              buffer.array(), buffer.arrayOffset() + buffer.position(), 0, this);
    } else {
      throw new IllegalArgumentException(
          "ForyReadableChannel requires direct or array-backed ByteBuffer.");
    }
  }

  @Override
  public int fillBuffer(int minFillSize) {
    if (minFillSize < 0) {
      throw new DeserializationException("Negative minimum fill size " + minFillSize);
    }
    if (minFillSize == 0) {
      return 0;
    }
    try {
      int totalRead = 0;
      SeekableByteChannel seekableChannel = this.seekableChannel;
      boolean checkedSeekableRemaining = seekableChannel == null;
      while (totalRead < minFillSize) {
        ByteBuffer byteBuf = byteBuffer;
        MemoryBuffer memoryBuf = memoryBuffer;
        int position = byteBuf.position();
        int remainingNeeded = minFillSize - totalRead;
        long targetSize = (long) position + remainingNeeded;
        if (targetSize > Integer.MAX_VALUE) {
          throw new DeserializationException("Stream buffer size exceeds supported range");
        }
        if (targetSize > byteBuf.capacity()) {
          int newCapacity = 0;
          if (!checkedSeekableRemaining) {
            checkedSeekableRemaining = true;
            // Query exact channel remaining bytes only as a one-shot fast path. Otherwise grow
            // from bytes already buffered so truncated channels fail before reserving the body.
            if (seekableChannel.size() - seekableChannel.position() >= remainingNeeded) {
              newCapacity = (int) targetSize;
            }
          }
          if (newCapacity == 0 && position == byteBuf.capacity()) {
            newCapacity = nextBufferSize(byteBuf.capacity(), (int) targetSize);
          }
          if (newCapacity != 0) {
            byteBuf = growBuffer(byteBuf, memoryBuf, position, newCapacity);
          }
        }
        byteBuf.limit(byteBuf.capacity());
        int read = channel.read(byteBuf);
        if (read <= 0) {
          throw new DeserializationException("Unexpected end of byte channel");
        }
        totalRead += read;
        memoryBuf.increaseSize(read);
        byteBuf.limit(byteBuf.position());
      }
      return totalRead;
    } catch (IOException e) {
      throw new DeserializationException("Failed to read the provided byte channel", e);
    }
  }

  private ByteBuffer growBuffer(
      ByteBuffer byteBuf, MemoryBuffer memoryBuf, int position, int newCapacity) {
    int oldCapacity = byteBuf.capacity();
    if (newCapacity <= oldCapacity) {
      throw new DeserializationException("Stream buffer size exceeds supported range");
    }
    ByteBuffer newByteBuf =
        byteBuf.isDirect()
            ? ByteBuffer.allocateDirect(newCapacity)
            : ByteBuffer.allocate(newCapacity);
    byteBuf.position(0);
    byteBuf.limit(position);
    newByteBuf.put(byteBuf);
    byteBuffer = newByteBuf;
    memoryBuf.initByteBuffer(newByteBuf, position);
    return newByteBuf;
  }

  private static int nextBufferSize(int oldCapacity, int targetSize) {
    long grown = oldCapacity == 0 ? 1L : (long) oldCapacity << 1;
    if (grown > Integer.MAX_VALUE) {
      grown = Integer.MAX_VALUE;
    }
    return (int) Math.min(grown, targetSize);
  }

  @Override
  public int read(ByteBuffer dst) throws IOException {
    int length = dst.remaining();
    MemoryBuffer buf = memoryBuffer;
    int remaining = buf.remaining();
    if (remaining >= length) {
      buf.read(dst, length);
      return length;
    } else {
      buf.read(dst, remaining);
      return channel.read(dst) + remaining;
    }
  }

  @Override
  public void readTo(byte[] dst, int dstIndex, int length) {
    MemoryBuffer buf = memoryBuffer;
    int remaining = buf.remaining();
    if (remaining >= length) {
      buf.readBytes(dst, dstIndex, length);
    } else {
      buf.readBytes(dst, dstIndex, remaining);
      try {
        ByteBuffer buffer = ByteBuffer.wrap(dst, dstIndex + remaining, length - remaining);
        readFully(buffer, length - remaining);
      } catch (IOException e) {
        throw new DeserializationException("Failed to read the provided byte channel", e);
      }
    }
  }

  @Override
  public void readBooleans(boolean[] dst, int dstIndex, int length) {
    ensureBuffered(length);
    memoryBuffer.readBooleans(dst, dstIndex, length);
  }

  @Override
  public void readChars(char[] dst, int dstIndex, int length) {
    ensureBuffered(Math.multiplyExact(length, 2));
    memoryBuffer.readChars(dst, dstIndex, length);
  }

  @Override
  public void readShorts(short[] dst, int dstIndex, int length) {
    ensureBuffered(Math.multiplyExact(length, 2));
    memoryBuffer.readShorts(dst, dstIndex, length);
  }

  @Override
  public void readInts(int[] dst, int dstIndex, int length) {
    ensureBuffered(Math.multiplyExact(length, 4));
    memoryBuffer.readInts(dst, dstIndex, length);
  }

  @Override
  public void readLongs(long[] dst, int dstIndex, int length) {
    ensureBuffered(Math.multiplyExact(length, 8));
    memoryBuffer.readLongs(dst, dstIndex, length);
  }

  @Override
  public void readFloats(float[] dst, int dstIndex, int length) {
    ensureBuffered(Math.multiplyExact(length, 4));
    memoryBuffer.readFloats(dst, dstIndex, length);
  }

  @Override
  public void readDoubles(double[] dst, int dstIndex, int length) {
    ensureBuffered(Math.multiplyExact(length, 8));
    memoryBuffer.readDoubles(dst, dstIndex, length);
  }

  private void ensureBuffered(int numBytes) {
    MemoryBuffer buf = memoryBuffer;
    int remaining = buf.remaining();
    if (remaining < numBytes) {
      fillBuffer(numBytes - remaining);
    }
  }

  @Override
  public void readToByteBuffer(ByteBuffer dst, int length) {
    MemoryBuffer buf = memoryBuffer;
    int remaining = buf.remaining();
    if (remaining >= length) {
      buf.read(dst, length);
    } else {
      buf.read(dst, remaining);
      try {
        int dstLimit = dst.limit();
        int newLimit = dst.position() + length - remaining;
        if (dstLimit > newLimit) {
          dst.limit(newLimit);
          try {
            readFully(dst, length - remaining);
          } finally {
            dst.limit(dstLimit);
          }
        } else {
          readFully(dst, length - remaining);
        }
      } catch (IOException e) {
        throw new DeserializationException("Failed to read the provided byte channel", e);
      }
    }
  }

  @Override
  public int readToByteBuffer(ByteBuffer dst) {
    MemoryBuffer buf = memoryBuffer;
    int remaining = buf.remaining();
    if (remaining > 0) {
      buf.read(dst, remaining);
    }
    try {
      return channel.read(dst) + remaining;
    } catch (IOException e) {
      throw new DeserializationException("Failed to read the provided byte channel", e);
    }
  }

  @Override
  public boolean isOpen() {
    return channel.isOpen();
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }

  @Override
  public MemoryBuffer getBuffer() {
    return memoryBuffer;
  }

  private void readFully(ByteBuffer dst, int length) throws IOException {
    int remaining = length;
    while (remaining > 0) {
      int read = channel.read(dst);
      if (read <= 0) {
        throw new DeserializationException("Unexpected end of byte channel");
      }
      remaining -= read;
    }
  }
}

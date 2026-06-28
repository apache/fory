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
import java.nio.ByteBuffer;
import javax.annotation.concurrent.NotThreadSafe;
import org.apache.fory.memory.MemoryBuffer;

/**
 * A buffered stream by fory. Do not use original {@link InputStream} when this stream object
 * created. This stream will try to buffer data inside, the date read from original stream won't be
 * the data you expected. Use this stream as a wrapper instead.
 */
@NotThreadSafe
public class ForyInputStream extends InputStream implements ForyStreamReader {
  private final InputStream stream;
  private final int bufferSize;
  private final MemoryBuffer buffer;

  public ForyInputStream(InputStream stream) {
    this(stream, 4096);
  }

  public ForyInputStream(InputStream stream, int bufferSize) {
    this.stream = stream;
    this.bufferSize = bufferSize;
    byte[] bytes = new byte[bufferSize];
    this.buffer = MemoryBuffer.fromByteArray(bytes, 0, 0, this);
  }

  @Override
  public int fillBuffer(int minFillSize) {
    MemoryBuffer buffer = this.buffer;
    if (minFillSize < 0) {
      throw new IndexOutOfBoundsException("Negative minimum fill size " + minFillSize);
    }
    if (minFillSize == 0) {
      return 0;
    }
    int totalRead = 0;
    boolean checkedAvailable = false;
    try {
      while (totalRead < minFillSize) {
        byte[] heapMemory = buffer.getHeapMemory();
        int offset = buffer.size();
        int remainingNeeded = minFillSize - totalRead;
        long targetSize = (long) offset + remainingNeeded;
        if (targetSize > Integer.MAX_VALUE - 8L) {
          throw new IndexOutOfBoundsException("Stream buffer size exceeds supported range");
        }
        if (targetSize > heapMemory.length) {
          int newSize = 0;
          if (!checkedAvailable) {
            checkedAvailable = true;
            // Use available() only as a one-shot growth hint. It may be expensive or
            // conservative, so failed hints fall back to bounded doubling. Final value
            // allocation still waits for fillBuffer to complete successfully.
            if (stream.available() >= remainingNeeded) {
              newSize = (int) targetSize;
            }
          }
          if (newSize == 0 && offset == heapMemory.length) {
            newSize = nextBufferSize(heapMemory.length, (int) targetSize);
          }
          if (newSize != 0) {
            heapMemory = growBuffer(buffer, newSize);
          }
        }
        int read = stream.read(heapMemory, offset, heapMemory.length - offset);
        if (read <= 0) {
          throw new IndexOutOfBoundsException("No enough data in the stream " + stream);
        }
        if (read > 0) {
          buffer.increaseSize(read);
          totalRead += read;
        }
      }
      return totalRead;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private byte[] growBuffer(MemoryBuffer buffer, int newSize) {
    int size = buffer.size();
    if (newSize <= size) {
      throw new IndexOutOfBoundsException("Stream buffer size exceeds supported range");
    }
    byte[] newBuffer = new byte[newSize];
    byte[] heapMemory = buffer.getHeapMemory();
    System.arraycopy(heapMemory, 0, newBuffer, 0, size);
    buffer.initHeapBuffer(newBuffer, 0, size);
    heapMemory = newBuffer;
    return heapMemory;
  }

  private static int nextBufferSize(int size, int targetSize) {
    long grown = size == 0 ? 1L : (long) size << 1;
    int maxSize = Integer.MAX_VALUE - 8;
    if (grown > maxSize) {
      grown = maxSize;
    }
    return (int) Math.min(grown, targetSize);
  }

  @Override
  public void readTo(byte[] dst, int dstIndex, int len) {
    MemoryBuffer buf = buffer;
    int remaining = buf.remaining();
    if (remaining >= len) {
      buf.readBytes(dst, dstIndex, len);
    } else {
      buf.readBytes(dst, dstIndex, remaining);
      len -= remaining;
      dstIndex += remaining;
      try {
        int read = stream.read(dst, dstIndex, len);
        while (read < len) {
          int newRead = stream.read(dst, dstIndex + read, len - read);
          if (newRead < 0) {
            throw new IndexOutOfBoundsException("No enough data in the stream " + stream);
          }
          read += newRead;
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public void readBooleans(boolean[] dst, int dstIndex, int length) {
    ensureBuffered(length);
    buffer.readBooleans(dst, dstIndex, length);
  }

  @Override
  public void readChars(char[] dst, int dstIndex, int length) {
    ensureBuffered(Math.multiplyExact(length, 2));
    buffer.readChars(dst, dstIndex, length);
  }

  @Override
  public void readShorts(short[] dst, int dstIndex, int length) {
    ensureBuffered(Math.multiplyExact(length, 2));
    buffer.readShorts(dst, dstIndex, length);
  }

  @Override
  public void readInts(int[] dst, int dstIndex, int length) {
    ensureBuffered(Math.multiplyExact(length, 4));
    buffer.readInts(dst, dstIndex, length);
  }

  @Override
  public void readLongs(long[] dst, int dstIndex, int length) {
    ensureBuffered(Math.multiplyExact(length, 8));
    buffer.readLongs(dst, dstIndex, length);
  }

  @Override
  public void readFloats(float[] dst, int dstIndex, int length) {
    ensureBuffered(Math.multiplyExact(length, 4));
    buffer.readFloats(dst, dstIndex, length);
  }

  @Override
  public void readDoubles(double[] dst, int dstIndex, int length) {
    ensureBuffered(Math.multiplyExact(length, 8));
    buffer.readDoubles(dst, dstIndex, length);
  }

  private void ensureBuffered(int numBytes) {
    MemoryBuffer buf = buffer;
    int remaining = buf.remaining();
    if (remaining < numBytes) {
      fillBuffer(numBytes - remaining);
    }
  }

  @Override
  public void readToByteBuffer(ByteBuffer dst, int length) {
    MemoryBuffer buf = buffer;
    int remaining = buf.remaining();
    if (remaining < length) {
      fillBuffer(length - remaining);
    }
    byte[] heapMemory = buf.getHeapMemory();
    dst.put(heapMemory, buf._unsafeHeapReaderIndex(), length);
    buf.increaseReaderIndex(length);
  }

  @Override
  public int readToByteBuffer(ByteBuffer dst) {
    MemoryBuffer buf = buffer;
    int remaining = buf.remaining();
    int len = dst.remaining();
    if (remaining >= len) {
      buf.read(dst, len);
      return len;
    } else {
      try {
        buf.read(dst, remaining);
        int available = stream.available();
        if (available > 0) {
          fillBuffer(available);
          int newRemaining = buf.remaining();
          buf.read(dst, newRemaining);
          return newRemaining + remaining;
        } else {
          return remaining;
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public MemoryBuffer getBuffer() {
    return buffer;
  }

  public InputStream getStream() {
    return stream;
  }

  /**
   * Shrink buffer to release memory, do not invoke this method is the deserialization for an object
   * didn't finish.
   */
  public void shrinkBuffer() {
    int remaining = buffer.remaining();
    int bufferSize = this.bufferSize;
    if (remaining > bufferSize || buffer.size() > bufferSize) {
      byte[] heapMemory = buffer.getHeapMemory();
      byte[] newBuffer = new byte[Math.max(bufferSize, remaining)];
      System.arraycopy(heapMemory, buffer.readerIndex(), newBuffer, 0, remaining);
      buffer.initHeapBuffer(newBuffer, 0, remaining);
      buffer.readerIndex(0);
    }
  }

  @Override
  public int read() throws IOException {
    MemoryBuffer buf = buffer;
    if (buf.remaining() > 0) {
      return buf.readByte() & 0xFF;
    }
    int available = stream.available();
    if (available > 0) {
      fillBuffer(1);
      return buf.readByte() & 0xFF;
    }
    return stream.read();
  }

  @Override
  public int read(byte[] b) throws IOException {
    return read(b, 0, b.length);
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    MemoryBuffer buf = buffer;
    int remaining = buf.remaining();
    if (remaining >= len) {
      buf.readBytes(b, off, len);
      return len;
    } else {
      buf.readBytes(b, off, remaining);
      return stream.read(b, off + remaining, len - remaining) + remaining;
    }
  }

  @Override
  public long skip(long n) throws IOException {
    MemoryBuffer buf = buffer;
    int remaining = buf.remaining();
    if (remaining >= n) {
      buf.increaseReaderIndex((int) n);
      return n;
    } else {
      buf.increaseReaderIndex(remaining);
      return stream.skip(n - remaining) + remaining;
    }
  }

  @Override
  public int available() throws IOException {
    return buffer.remaining() + stream.available();
  }

  @Override
  public void close() throws IOException {
    stream.close();
  }
}

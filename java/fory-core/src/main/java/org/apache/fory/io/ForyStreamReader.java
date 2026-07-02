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

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import org.apache.fory.memory.MemoryBuffer;

/**
 * A streaming reader to make {@link MemoryBuffer} to support streaming reading.
 *
 * <p>Typed array defaults are the stream fallback for MemoryBuffer payload readers. Keep callers on
 * typed destination arrays instead of raw object offsets; implementations may override these
 * methods for specialized bulk IO.
 */
public interface ForyStreamReader {

  /**
   * Maximum buffer size to use when growing stream-backed buffers: the largest array size commonly
   * supported by JVM implementations.
   */
  int MAX_BUFFER_SIZE = Integer.MAX_VALUE - 8;

  /**
   * Returns the next geometric growth step for a stream-backed buffer of the given capacity,
   * bounded by {@link #MAX_BUFFER_SIZE}. Growth must not be capped to the immediate fill target:
   * for small fills that degenerates into constant-size growth steps which copy the whole buffer on
   * every small read and make stream deserialization O(n^2) overall.
   */
  static int nextBufferSize(int currentCapacity) {
    long grown = currentCapacity == 0 ? 1L : (long) currentCapacity << 1;
    return (int) Math.min(grown, MAX_BUFFER_SIZE);
  }

  /**
   * Read stream and fill the data to underlying {@link MemoryBuffer}, which is also the buffer
   * returned by {@link #getBuffer}.
   */
  int fillBuffer(int minFillSize);

  /**
   * Read data into `dst`. This method will block until the enough data are written into the `dst`.
   */
  void readTo(byte[] dst, int dstIndex, int length);

  /**
   * Read boolean data into `dst`. This method will block until enough data is written into `dst`.
   */
  default void readBooleans(boolean[] dst, int dstIndex, int length) {
    MemoryBuffer buffer = getBuffer();
    int remaining = buffer.remaining();
    if (remaining < length) {
      fillBuffer(length - remaining);
    }
    buffer.readBooleans(dst, dstIndex, length);
  }

  /** Read char data into `dst`. This method will block until enough data is written into `dst`. */
  default void readChars(char[] dst, int dstIndex, int length) {
    int numBytes = Math.multiplyExact(length, 2);
    MemoryBuffer buffer = getBuffer();
    int remaining = buffer.remaining();
    if (remaining < numBytes) {
      fillBuffer(numBytes - remaining);
    }
    buffer.readChars(dst, dstIndex, length);
  }

  /** Read short data into `dst`. This method will block until enough data is written into `dst`. */
  default void readShorts(short[] dst, int dstIndex, int length) {
    int numBytes = Math.multiplyExact(length, 2);
    MemoryBuffer buffer = getBuffer();
    int remaining = buffer.remaining();
    if (remaining < numBytes) {
      fillBuffer(numBytes - remaining);
    }
    buffer.readShorts(dst, dstIndex, length);
  }

  /** Read int data into `dst`. This method will block until enough data is written into `dst`. */
  default void readInts(int[] dst, int dstIndex, int length) {
    int numBytes = Math.multiplyExact(length, 4);
    MemoryBuffer buffer = getBuffer();
    int remaining = buffer.remaining();
    if (remaining < numBytes) {
      fillBuffer(numBytes - remaining);
    }
    buffer.readInts(dst, dstIndex, length);
  }

  /** Read long data into `dst`. This method will block until enough data is written into `dst`. */
  default void readLongs(long[] dst, int dstIndex, int length) {
    int numBytes = Math.multiplyExact(length, 8);
    MemoryBuffer buffer = getBuffer();
    int remaining = buffer.remaining();
    if (remaining < numBytes) {
      fillBuffer(numBytes - remaining);
    }
    buffer.readLongs(dst, dstIndex, length);
  }

  /** Read float data into `dst`. This method will block until enough data is written into `dst`. */
  default void readFloats(float[] dst, int dstIndex, int length) {
    int numBytes = Math.multiplyExact(length, 4);
    MemoryBuffer buffer = getBuffer();
    int remaining = buffer.remaining();
    if (remaining < numBytes) {
      fillBuffer(numBytes - remaining);
    }
    buffer.readFloats(dst, dstIndex, length);
  }

  /**
   * Read double data into `dst`. This method will block until enough data is written into `dst`.
   */
  default void readDoubles(double[] dst, int dstIndex, int length) {
    int numBytes = Math.multiplyExact(length, 8);
    MemoryBuffer buffer = getBuffer();
    int remaining = buffer.remaining();
    if (remaining < numBytes) {
      fillBuffer(numBytes - remaining);
    }
    buffer.readDoubles(dst, dstIndex, length);
  }

  /**
   * Read data into `dst`. This method will block until the enough data are written into the `dst`.
   */
  void readToByteBuffer(ByteBuffer dst, int length);

  int readToByteBuffer(ByteBuffer dst);

  /**
   * Returns the underlying {@link MemoryBuffer}. This method will return same instance of buffer
   * for same {@link ForyStreamReader} instance.
   */
  MemoryBuffer getBuffer();

  /**
   * Create a {@link ForyInputStream} from the provided {@link InputStream}. Note that the provided
   * stream will be owned by the returned {@link ForyInputStream}, <bold>do not</bold> read the
   * provided {@link InputStream} anymore, read the returned stream instead.
   */
  static ForyInputStream of(InputStream stream) {
    return new ForyInputStream(stream);
  }

  /**
   * Create a {@link ForyReadableChannel} from the provided {@link SeekableByteChannel}. Note that
   * the provided channel will be owned by the returned {@link ForyReadableChannel}, <bold>do
   * not</bold> read the provided {@link SeekableByteChannel} anymore, read the returned stream
   * instead.
   */
  static ForyReadableChannel of(SeekableByteChannel channel) {
    return new ForyReadableChannel(channel);
  }
}

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

/** A streaming reader to make {@link MemoryBuffer} to support streaming reading. */
public interface ForyStreamReader {

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
   * Read data into `target`. This method will block until the enough data are written into the
   * `target`.
   */

  //  void readCharsTo(char[] target, int offset, int length);
  //
  //  void readLongsTo(long[] target, int offset, int length);
  //
  //  void readBoolsTo(boolean[] target, int offset, int length);
  //
  //  void readToUnsafe(Object target, long targetPointer, int numBytes);

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

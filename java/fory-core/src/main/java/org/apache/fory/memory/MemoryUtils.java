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

package org.apache.fory.memory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.apache.fory.util.Preconditions;

/** Memory utils for fory. */
public class MemoryUtils {

  // Lazy load offset and also follow graalvm offset auto replace pattern.
  private static class Offset {
    private static final long BAS_BUF_BUF;
    private static final long BAS_BUF_COUNT;
    private static final long BIS_BUF_BUF;
    private static final long BIS_BUF_POS;
    private static final long BIS_BUF_COUNT;

    static {
      try {
        BAS_BUF_BUF =
            Platform.objectFieldOffset(ByteArrayOutputStream.class.getDeclaredField("buf"));
        BAS_BUF_COUNT =
            Platform.objectFieldOffset(ByteArrayOutputStream.class.getDeclaredField("count"));
        BIS_BUF_BUF =
            Platform.objectFieldOffset(ByteArrayInputStream.class.getDeclaredField("buf"));
        BIS_BUF_POS =
            Platform.objectFieldOffset(ByteArrayInputStream.class.getDeclaredField("pos"));
        BIS_BUF_COUNT =
            Platform.objectFieldOffset(ByteArrayInputStream.class.getDeclaredField("count"));
      } catch (NoSuchFieldException e) {
        throw new RuntimeException(e);
      }
    }
  }

  /**
   * Wrap a {@link ByteArrayOutputStream} into a {@link MemoryBuffer}. The writerIndex of buffer
   * will be the count of stream.
   */
  public static void wrap(ByteArrayOutputStream stream, MemoryBuffer buffer) {
    Preconditions.checkNotNull(stream);
    byte[] buf = (byte[]) Platform.getObject(stream, Offset.BAS_BUF_BUF);
    int count = Platform.getInt(stream, Offset.BAS_BUF_COUNT);
    buffer.pointTo(buf, 0, buf.length);
    buffer.writerIndex(count);
  }

  /**
   * Wrap a @{link MemoryBuffer} into a {@link ByteArrayOutputStream}. The count of stream will be
   * the writerIndex of buffer.
   */
  public static void wrap(MemoryBuffer buffer, ByteArrayOutputStream stream) {
    Preconditions.checkNotNull(stream);
    byte[] bytes = buffer.getHeapMemory();
    Preconditions.checkNotNull(bytes);
    Platform.putObject(stream, Offset.BAS_BUF_BUF, bytes);
    Platform.putInt(stream, Offset.BAS_BUF_COUNT, buffer.writerIndex());
  }

  /**
   * Wrap a {@link ByteArrayInputStream} into a {@link MemoryBuffer}. The readerIndex of buffer will
   * be the pos of stream.
   */
  public static void wrap(ByteArrayInputStream stream, MemoryBuffer buffer) {
    Preconditions.checkNotNull(stream);
    byte[] buf = (byte[]) Platform.getObject(stream, Offset.BIS_BUF_BUF);
    int count = Platform.getInt(stream, Offset.BIS_BUF_COUNT);
    int pos = Platform.getInt(stream, Offset.BIS_BUF_POS);
    buffer.pointTo(buf, 0, count);
    buffer.readerIndex(pos);
  }
}

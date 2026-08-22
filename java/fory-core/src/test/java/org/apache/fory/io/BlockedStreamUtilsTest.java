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

import static org.testng.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.exception.DeserializationException;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.test.bean.Foo;
import org.testng.annotations.Test;

public class BlockedStreamUtilsTest extends ForyTestBase {

  @Test
  public void testDeserializeStream() {
    Fory fory = getJavaFory();
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    Foo foo = Foo.create();
    BlockedStreamUtils.serialize(fory, stream, foo);
    BlockedStreamUtils.serialize(fory, stream, foo);
    ByteArrayInputStream inputStream = new ByteArrayInputStream(stream.toByteArray());
    assertEquals(BlockedStreamUtils.deserialize(fory, inputStream), foo);
    assertEquals(BlockedStreamUtils.deserialize(fory, inputStream, Foo.class), foo);
  }

  @Test
  public void testDeserializeChannel() {
    Fory fory = builder().withCodegen(false).build();
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    Foo foo = Foo.create();
    BlockedStreamUtils.serialize(fory, stream, foo);
    BlockedStreamUtils.serialize(fory, stream, foo);
    try (MemoryBufferReadableChannel channel =
        new MemoryBufferReadableChannel(MemoryBuffer.fromByteArray(stream.toByteArray()))) {
      assertEquals(BlockedStreamUtils.deserialize(fory, channel), foo);
      assertEquals(BlockedStreamUtils.deserialize(fory, channel, Foo.class), foo);
    }
  }

  @Test
  public void testDeserializeChunkedChannel() throws IOException {
    Fory fory = builder().withCodegen(false).build();
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    Foo foo = Foo.create();
    BlockedStreamUtils.serialize(fory, stream, foo);
    BlockedStreamUtils.serialize(fory, stream, foo);
    try (ChunkedReadableByteChannel channel =
        new ChunkedReadableByteChannel(stream.toByteArray(), 1)) {
      assertEquals(BlockedStreamUtils.deserialize(fory, channel), foo);
      assertEquals(BlockedStreamUtils.deserialize(fory, channel, Foo.class), foo);
    }
  }

  @Test
  public void testTransientChannelZeroRead() {
    Fory fory = builder().withCodegen(false).build();
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    Foo foo = Foo.create();
    BlockedStreamUtils.serialize(fory, stream, foo);
    BlockedStreamUtils.serialize(fory, stream, foo);
    byte[] frames = stream.toByteArray();
    for (int zeroPosition : new int[] {0, 2, Integer.BYTES + 2}) {
      try (TransientZeroReadableByteChannel channel =
          new TransientZeroReadableByteChannel(frames, zeroPosition)) {
        assertEquals(BlockedStreamUtils.deserialize(fory, channel), foo);
        assertEquals(BlockedStreamUtils.deserialize(fory, channel, Foo.class), foo);
        assertTrue(channel.returnedZero);
      }
    }
  }

  @Test(timeOut = 5000)
  public void testPersistentChannelZeroRead() {
    Fory fory = builder().withCodegen(false).build();
    try (PersistentZeroReadableByteChannel channel = new PersistentZeroReadableByteChannel()) {
      assertThrows(
          DeserializationException.class, () -> BlockedStreamUtils.deserialize(fory, channel));
      assertTrue(channel.readCount > 1);
      assertTrue(channel.readCount < 1000);
    }
  }

  @Test
  public void testSmallBufferStreamReuse() {
    Fory writerFory = builder().withCodegen(false).build();
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    byte[] value = new byte[1024];
    BlockedStreamUtils.serialize(writerFory, stream, value);
    BlockedStreamUtils.serialize(writerFory, stream, value);

    Fory readerFory = builder().withCodegen(false).withBufferSizeLimitBytes(1).build();
    ByteArrayInputStream inputStream = new ByteArrayInputStream(stream.toByteArray());
    assertEquals((byte[]) BlockedStreamUtils.deserialize(readerFory, inputStream), value);
    assertEquals(readerFory.getBuffer().size(), 1);
    assertEquals(BlockedStreamUtils.deserialize(readerFory, inputStream, byte[].class), value);
  }

  @Test
  public void testSmallBufferChannelReuse() {
    Fory writerFory = builder().withCodegen(false).build();
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    byte[] value = new byte[1024];
    BlockedStreamUtils.serialize(writerFory, stream, value);
    BlockedStreamUtils.serialize(writerFory, stream, value);

    Fory readerFory = builder().withCodegen(false).withBufferSizeLimitBytes(1).build();
    try (MemoryBufferReadableChannel channel =
        new MemoryBufferReadableChannel(MemoryBuffer.fromByteArray(stream.toByteArray()))) {
      assertEquals((byte[]) BlockedStreamUtils.deserialize(readerFory, channel), value);
      assertEquals(readerFory.getBuffer().size(), 1);
      assertEquals(BlockedStreamUtils.deserialize(readerFory, channel, byte[].class), value);
    }
  }

  @Test
  public void testTruncatedFramesDoNotPreallocate() throws IOException {
    byte[] header = frameHeader(16 * 1024 * 1024);

    Fory streamFory = builder().withCodegen(false).build();
    int streamCapacity = streamFory.getBuffer().size();
    assertThrows(
        RuntimeException.class,
        () -> BlockedStreamUtils.deserialize(streamFory, new ByteArrayInputStream(header)));
    assertEquals(streamFory.getBuffer().size(), streamCapacity);

    Fory channelFory = builder().withCodegen(false).build();
    int channelCapacity = channelFory.getBuffer().size();
    try (MemoryBufferReadableChannel channel =
        new MemoryBufferReadableChannel(MemoryBuffer.fromByteArray(header))) {
      assertThrows(
          RuntimeException.class, () -> BlockedStreamUtils.deserialize(channelFory, channel));
    }
    assertEquals(channelFory.getBuffer().size(), channelCapacity);
  }

  private static byte[] frameHeader(int size) {
    return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(size).array();
  }

  private static final class ChunkedReadableByteChannel implements ReadableByteChannel {
    private final byte[] data;
    private final int chunkSize;
    private int position;
    private boolean open = true;

    private ChunkedReadableByteChannel(byte[] data, int chunkSize) {
      this.data = data;
      this.chunkSize = chunkSize;
    }

    @Override
    public int read(ByteBuffer dst) {
      if (position >= data.length) {
        return -1;
      }
      int length = Math.min(Math.min(dst.remaining(), chunkSize), data.length - position);
      dst.put(data, position, length);
      position += length;
      return length;
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public void close() throws IOException {
      open = false;
    }
  }

  private static final class TransientZeroReadableByteChannel implements ReadableByteChannel {
    private final byte[] data;
    private final int zeroPosition;
    private int position;
    private boolean returnedZero;
    private boolean open = true;

    private TransientZeroReadableByteChannel(byte[] data, int zeroPosition) {
      this.data = data;
      this.zeroPosition = zeroPosition;
    }

    @Override
    public int read(ByteBuffer dst) {
      if (!returnedZero && position == zeroPosition) {
        returnedZero = true;
        return 0;
      }
      if (position >= data.length) {
        return -1;
      }
      int length = Math.min(1, Math.min(dst.remaining(), data.length - position));
      dst.put(data, position, length);
      position += length;
      return length;
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public void close() {
      open = false;
    }
  }

  private static final class PersistentZeroReadableByteChannel implements ReadableByteChannel {
    private int readCount;
    private boolean open = true;

    @Override
    public int read(ByteBuffer dst) {
      readCount++;
      return 0;
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public void close() {
      open = false;
    }
  }
}

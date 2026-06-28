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

package org.apache.fory;

import static org.apache.fory.io.ForyStreamReader.of;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import com.google.common.collect.Lists;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fory.exception.DeserializationException;
import org.apache.fory.io.ForyInputStream;
import org.apache.fory.io.ForyReadableChannel;
import org.apache.fory.io.ForyStreamReader;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.test.bean.BeanA;
import org.testng.Assert;
import org.testng.annotations.Test;

public class StreamTest extends ForyTestBase {

  @Test
  public void testBufferStream() {
    MemoryBuffer buffer0 = MemoryBuffer.newHeapBuffer(10);
    for (int i = 0; i < 10; i++) {
      buffer0.writeByte(i);
      buffer0.writeChar((char) i);
      buffer0.writeInt16((short) i);
      buffer0.writeInt32(i);
      buffer0.writeInt64(i);
      buffer0.writeFloat32(i);
      buffer0.writeFloat64(i);
      buffer0.writeVarInt32(i);
      buffer0.writeVarInt32(Integer.MIN_VALUE);
      buffer0.writeVarInt32(Integer.MAX_VALUE);
      buffer0.writeVarUInt32(i);
      buffer0.writeVarUInt32(Integer.MIN_VALUE);
      buffer0.writeVarUInt32(Integer.MAX_VALUE);
      buffer0.writeVarInt64(i);
      buffer0.writeVarInt64(Long.MIN_VALUE);
      buffer0.writeVarInt64(Long.MAX_VALUE);
      buffer0.writeVarUInt64(i);
      buffer0.writeVarUInt64(Long.MIN_VALUE);
      buffer0.writeVarUInt64(Long.MAX_VALUE);
      buffer0.writeTaggedInt64(i);
      buffer0.writeTaggedInt64(Long.MIN_VALUE);
      buffer0.writeTaggedInt64(Long.MAX_VALUE);
    }
    byte[] bytes = buffer0.getBytes(0, buffer0.writerIndex());
    ForyInputStream stream = ForyStreamReader.of(new ChunkedInputStream(bytes, 1));
    MemoryBuffer buffer = stream.getBuffer();
    for (int i = 0; i < 10; i++) {
      assertEquals(buffer.readByte(), i);
      assertEquals(buffer.readChar(), i);
      assertEquals(buffer.readInt16(), i);
      assertEquals(buffer.readInt32(), i);
      assertEquals(buffer.readInt64(), i);
      assertEquals(buffer.readFloat32(), i);
      assertEquals(buffer.readFloat64(), i);
      assertEquals(buffer.readVarInt32(), i);
      assertEquals(buffer.readVarInt32(), Integer.MIN_VALUE);
      assertEquals(buffer.readVarInt32(), Integer.MAX_VALUE);
      assertEquals(buffer.readVarUInt32(), i);
      assertEquals(buffer.readVarUInt32(), Integer.MIN_VALUE);
      assertEquals(buffer.readVarUInt32(), Integer.MAX_VALUE);
      assertEquals(buffer.readVarInt64(), i);
      assertEquals(buffer.readVarInt64(), Long.MIN_VALUE);
      assertEquals(buffer.readVarInt64(), Long.MAX_VALUE);
      assertEquals(buffer.readVarUInt64(), i);
      assertEquals(buffer.readVarUInt64(), Long.MIN_VALUE);
      assertEquals(buffer.readVarUInt64(), Long.MAX_VALUE);
      assertEquals(buffer.readTaggedInt64(), i);
      assertEquals(buffer.readTaggedInt64(), Long.MIN_VALUE);
      assertEquals(buffer.readTaggedInt64(), Long.MAX_VALUE);
    }
  }

  @Test
  public void testBufferReset() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    byte[] bytes = fory.serialize(new byte[1000 * 1000]);
    checkBuffer(fory);
    // assertEquals(fory.deserialize(bytes), new byte[1000 * 1000]);
    assertEquals(fory.deserialize(of(new ByteArrayInputStream(bytes))), new byte[1000 * 1000]);

    bytes = fory.serialize(new byte[1000 * 1000]);
    checkBuffer(fory);
    assertEquals(fory.deserialize(bytes, byte[].class), new byte[1000 * 1000]);

    bytes = fory.serialize(new byte[1000 * 1000]);
    checkBuffer(fory);
    assertEquals(fory.deserialize(bytes), new byte[1000 * 1000]);

    ByteArrayOutputStream bas = new ByteArrayOutputStream();
    fory.serialize(bas, new byte[1000 * 1000]);
    checkBuffer(fory);
    Object o = fory.deserialize(of(new ByteArrayInputStream(bas.toByteArray())));
    assertEquals(o, new byte[1000 * 1000]);
    assertEquals(fory.deserialize(bas.toByteArray()), new byte[1000 * 1000]);

    bas.reset();
    fory.serialize(bas, new byte[1000 * 1000]);
    checkBuffer(fory);
    o = fory.deserialize(of(new ByteArrayInputStream(bas.toByteArray())), byte[].class);
    assertEquals(o, new byte[1000 * 1000]);

    bas.reset();
    fory.serialize(bas, new byte[1000 * 1000]);
    checkBuffer(fory);
    o = fory.deserialize(of(new ByteArrayInputStream(bas.toByteArray())));
    assertEquals(o, new byte[1000 * 1000]);
  }

  private void checkBuffer(Fory fory) {
    Object buf = ReflectionUtils.getObjectFieldValue(fory, "buffer");
    MemoryBuffer buffer = (MemoryBuffer) buf;
    assert buffer != null;
    assertTrue(buffer.size() < 1000 * 1000);
  }

  @Test
  public void testOutputStream() throws IOException {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    ByteArrayOutputStream bas = new ByteArrayOutputStream();
    BeanA beanA = BeanA.createBeanA(2);
    fory.serialize(bas, beanA);
    fory.serialize(bas, beanA);
    bas.flush();
    ByteArrayInputStream bis = new ByteArrayInputStream(bas.toByteArray());
    ForyInputStream stream = of(bis);
    MemoryBuffer buf = MemoryBuffer.fromByteArray(bas.toByteArray());
    Object newObj = fory.deserialize(stream);
    assertEquals(newObj, beanA);
    newObj = fory.deserialize(buf);
    assertEquals(newObj, beanA);
    newObj = fory.deserialize(stream);
    assertEquals(newObj, beanA);
    newObj = fory.deserialize(buf);
    assertEquals(newObj, beanA);

    fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    // test reader buffer grow
    bis = new ByteArrayInputStream(bas.toByteArray());
    stream = of(bis);
    buf = MemoryBuffer.fromByteArray(bas.toByteArray());
    newObj = fory.deserialize(stream);
    assertEquals(newObj, beanA);
    newObj = fory.deserialize(buf);
    assertEquals(newObj, beanA);
    newObj = fory.deserialize(stream);
    assertEquals(newObj, beanA);
    newObj = fory.deserialize(buf);
    assertEquals(newObj, beanA);
  }

  @Test
  public void testBufferedStream() throws IOException {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    ByteArrayOutputStream bas = new ByteArrayOutputStream();
    BeanA beanA = BeanA.createBeanA(2);
    fory.serialize(bas, beanA);
    fory.serialize(bas, beanA);
    bas.flush();
    InputStream bis =
        new BufferedInputStream(new ByteArrayInputStream(bas.toByteArray())) {
          @Override
          public synchronized int read(byte[] b, int off, int len) throws IOException {
            return in.read(b, off, Math.min(len, 100));
          }
        };
    bis.mark(10);
    ForyInputStream stream = of(bis);
    Object newObj = fory.deserialize(stream);
    assertEquals(newObj, beanA);
    newObj = fory.deserialize(stream);
    assertEquals(newObj, beanA);

    fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    // test reader buffer grow
    bis = new ByteArrayInputStream(bas.toByteArray());
    stream = of(bis);
    MemoryBuffer buf = MemoryBuffer.fromByteArray(bas.toByteArray());
    newObj = fory.deserialize(stream);
    assertEquals(newObj, beanA);
    newObj = fory.deserialize(buf);
    assertEquals(newObj, beanA);

    newObj = fory.deserialize(stream);
    assertEquals(newObj, beanA);
    newObj = fory.deserialize(buf);
    assertEquals(newObj, beanA);
  }

  @Test
  public void testOutputStreamWithType() throws IOException {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    BeanA beanA = BeanA.createBeanA(2);
    ByteArrayOutputStream bas = new ByteArrayOutputStream();
    fory.serialize(bas, beanA);
    fory.serialize(bas, beanA);
    bas.flush();
    ByteArrayInputStream bis = new ByteArrayInputStream(bas.toByteArray());
    ForyInputStream stream = of(bis);
    MemoryBuffer buf = MemoryBuffer.fromByteArray(bas.toByteArray());
    Object newObj = fory.deserialize(stream, BeanA.class);
    assertEquals(newObj, beanA);
    newObj = fory.deserialize(buf, BeanA.class);
    assertEquals(newObj, beanA);
    newObj = fory.deserialize(stream, BeanA.class);
    assertEquals(newObj, beanA);
    newObj = fory.deserialize(buf, BeanA.class);
    assertEquals(newObj, beanA);
  }

  @Test
  public void testReadableChannel() throws IOException {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    BeanA beanA = BeanA.createBeanA(2);
    {
      ByteArrayOutputStream bas = new ByteArrayOutputStream();
      fory.serialize(bas, beanA);

      Path tempFile = Files.createTempFile("readable_channel_test", "data_1");
      Files.write(tempFile, bas.toByteArray());

      try (ForyReadableChannel channel = of(Files.newByteChannel(tempFile))) {
        Object newObj = fory.deserialize(channel);
        assertEquals(newObj, beanA);
      } finally {
        Files.delete(tempFile);
      }
    }
    {
      ByteArrayOutputStream bas = new ByteArrayOutputStream();
      fory.serialize(bas, beanA);

      Path tempFile = Files.createTempFile("readable_channel_test", "data_2");
      Files.write(tempFile, bas.toByteArray());

      try (ForyReadableChannel channel = of(Files.newByteChannel(tempFile))) {
        Object newObj = fory.deserialize(channel, BeanA.class);
        assertEquals(newObj, beanA);
      } finally {
        Files.delete(tempFile);
      }
    }
  }

  @Test
  public void testReadableChannelRequiresExactReads() throws IOException {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    BeanA beanA = BeanA.createBeanA(2);
    byte[] serialized = fory.serialize(beanA);

    try (ForyReadableChannel channel =
        new ForyReadableChannel(new ChunkedReadableByteChannel(serialized, 1))) {
      Assert.assertEquals(fory.deserialize(channel), beanA);
    }

    byte[] truncated = new byte[serialized.length - 1];
    System.arraycopy(serialized, 0, truncated, 0, truncated.length);
    try (ForyReadableChannel channel =
        new ForyReadableChannel(new ChunkedReadableByteChannel(truncated, 1))) {
      Assert.assertThrows(DeserializationException.class, () -> fory.deserialize(channel));
    }
  }

  @Test
  public void testStreamFillGrowsFromBufferedBytes() throws IOException {
    byte[] complete = new byte[100];
    ForyInputStream inputWithAvailable = new ForyInputStream(new ByteArrayInputStream(complete), 4);
    assertEquals(inputWithAvailable.fillBuffer(100), 100);
    assertEquals(inputWithAvailable.getBuffer().getHeapMemory().length, 100);

    byte[] truncated = new byte[17];
    ForyInputStream input = new ForyInputStream(new ByteArrayInputStream(truncated), 4);
    Assert.assertThrows(IndexOutOfBoundsException.class, () -> input.fillBuffer(100));
    int inputCapacity = input.getBuffer().getHeapMemory().length;
    assertTrue(inputCapacity < 100);
    assertTrue(inputCapacity <= 32);

    try (ForyReadableChannel channel =
        new ForyReadableChannel(
            new ChunkedReadableByteChannel(truncated, truncated.length), ByteBuffer.allocate(4))) {
      Assert.assertThrows(DeserializationException.class, () -> channel.fillBuffer(100));
      int channelCapacity = channel.getBuffer().getHeapMemory().length;
      assertTrue(channelCapacity < 100);
      assertTrue(channelCapacity <= 32);
    }

    Path tempFile = Files.createTempFile("readable_channel_available", "data");
    Files.write(tempFile, complete);
    try (ForyReadableChannel channel =
        new ForyReadableChannel(Files.newByteChannel(tempFile), ByteBuffer.allocate(4))) {
      assertEquals(channel.fillBuffer(100), 100);
      assertEquals(channel.getBuffer().getHeapMemory().length, 100);
    } finally {
      Files.delete(tempFile);
    }
  }

  @Test
  public void testScopedMetaShare() throws IOException {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(true)
            .withScopedMetaShare(true)
            .build();
    ByteArrayOutputStream bas = new ByteArrayOutputStream();
    ArrayList<Integer> list = Lists.newArrayList(1, 2, 3);
    fory.serialize(bas, list);
    HashMap<String, String> map = new HashMap<>();
    map.put("key", "value");
    fory.serialize(bas, map);
    ArrayList<Integer> list2 = Lists.newArrayList(10, 9, 7);
    fory.serialize(bas, list2);
    bas.flush();

    InputStream bis = new ByteArrayInputStream(bas.toByteArray());
    ForyInputStream stream = of(bis);
    Assert.assertEquals(fory.deserialize(stream), list);
    Assert.assertEquals(fory.deserialize(stream), map);
    Assert.assertEquals(fory.deserialize(stream), list2);
  }

  private static final class ChunkedReadableByteChannel implements ReadableByteChannel {
    private final byte[] data;
    private final int chunkSize;
    private int index;
    private boolean open = true;

    private ChunkedReadableByteChannel(byte[] data, int chunkSize) {
      this.data = data;
      this.chunkSize = chunkSize;
    }

    @Override
    public int read(ByteBuffer dst) {
      if (!open) {
        throw new IllegalStateException("Channel is closed");
      }
      if (!dst.hasRemaining()) {
        return 0;
      }
      if (index == data.length) {
        return -1;
      }
      int length = Math.min(chunkSize, Math.min(dst.remaining(), data.length - index));
      dst.put(data, index, length);
      index += length;
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

  private static final class ChunkedInputStream extends ByteArrayInputStream {
    private final int chunkSize;

    private ChunkedInputStream(byte[] data, int chunkSize) {
      super(data);
      this.chunkSize = chunkSize;
    }

    @Override
    public synchronized int read(byte[] b, int off, int len) {
      return super.read(b, off, Math.min(chunkSize, len));
    }
  }

  private static final class TrackingForyInputStream extends ForyInputStream {
    private boolean readIntsCalled;

    private TrackingForyInputStream(InputStream stream, int bufferSize) {
      super(stream, bufferSize);
    }

    @Override
    public void readInts(int[] dst, int dstIndex, int length) {
      readIntsCalled = true;
      super.readInts(dst, dstIndex, length);
    }
  }

  private static final class TrackingForyReadableChannel extends ForyReadableChannel {
    private boolean readLongsCalled;

    private TrackingForyReadableChannel(ReadableByteChannel channel, ByteBuffer buffer) {
      super(channel, buffer);
    }

    @Override
    public void readLongs(long[] dst, int dstIndex, int length) {
      readLongsCalled = true;
      super.readLongs(dst, dstIndex, length);
    }
  }

  @Test
  public void testBigBufferStreamingMetaShare() throws IOException {
    Fory fory = builder().withCompatible(true).build();
    ByteArrayOutputStream bas = new ByteArrayOutputStream();
    List<Integer> list = new ArrayList<>();
    HashMap<String, String> map = new HashMap<>();
    for (int i = 0; i < 5000; i++) {
      list.add(i);
      map.put("key" + i, "value" + i);
    }
    fory.serialize(bas, list);
    fory.serialize(bas, map);
    fory.serialize(bas, list);
    fory.serialize(bas, new long[5000]);
    fory.serialize(bas, new int[5000]);
    bas.flush();

    InputStream bis = new ByteArrayInputStream(bas.toByteArray());
    ForyInputStream stream = of(bis);
    assertEquals(fory.deserialize(stream), list);
    assertEquals(fory.deserialize(stream), map);
    assertEquals(fory.deserialize(stream), list);
    assertEquals(fory.deserialize(stream), new long[5000]);
    assertEquals(fory.deserialize(stream), new int[5000]);
  }

  @Test
  public void testStreamPrimitiveArrayBody() throws IOException {
    Fory fory = builder().requireClassRegistration(false).build();

    int[] ints = new int[257];
    for (int i = 0; i < ints.length; i++) {
      ints[i] = i * 17;
    }
    TrackingForyInputStream input =
        new TrackingForyInputStream(new ChunkedInputStream(fory.serialize(ints), 1), 3);
    Assert.assertEquals((int[]) fory.deserialize(input), ints);
    assertFalse(input.readIntsCalled);

    long[] longs = new long[257];
    for (int i = 0; i < longs.length; i++) {
      longs[i] = ((long) i << 40) + i;
    }
    byte[] serialized = fory.serialize(longs);
    try (TrackingForyReadableChannel channel =
        new TrackingForyReadableChannel(
            new ChunkedReadableByteChannel(serialized, 1), ByteBuffer.allocateDirect(5))) {
      Assert.assertEquals((long[]) fory.deserialize(channel), longs);
      assertFalse(channel.readLongsCalled);
    }

    ByteBuffer limitedDirectBuffer = ByteBuffer.allocateDirect(serialized.length + 8);
    limitedDirectBuffer.limit(5);
    try (TrackingForyReadableChannel channel =
        new TrackingForyReadableChannel(
            new ChunkedReadableByteChannel(serialized, 1), limitedDirectBuffer)) {
      Assert.assertEquals((long[]) fory.deserialize(channel), longs);
      assertFalse(channel.readLongsCalled);
    }
  }

  @Test
  public void testReadNullChunkMapOnFillBound() {
    Fory fory = builder().build();
    Map<String, String> m = new HashMap<>();
    m.put("1", null);
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream(100);
    fory.serialize(outputStream, m);
    InputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
    ForyInputStream input = new ForyInputStream(inputStream);
    assertEquals(fory.deserialize(input), m);
  }

  public static class SimpleType {
    public double dVal;

    public SimpleType() {
      dVal = 0.5;
    }
  }

  // For issue https://github.com/apache/fory/issues/2060
  @Test
  public void testReadPrimitivesOnBufferFillBound() {
    Fory fory = builder().build();
    fory.register(SimpleType.class);
    SimpleType v = new SimpleType();
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    fory.serialize(outputStream, v);
    InputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
    ForyInputStream input = new ForyInputStream(inputStream, 11);
    SimpleType newValue = (SimpleType) fory.deserialize(input);
    Assert.assertEquals(v.dVal, newValue.dVal, 0.001);
  }
}

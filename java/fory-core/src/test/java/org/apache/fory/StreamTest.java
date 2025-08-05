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
import static org.testng.Assert.assertTrue;

import com.google.common.collect.Lists;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fory.config.CompatibleMode;
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
      buffer0.writeVarUint32(i);
      buffer0.writeVarUint32(Integer.MIN_VALUE);
      buffer0.writeVarUint32(Integer.MAX_VALUE);
      buffer0.writeVarInt64(i);
      buffer0.writeVarInt64(Long.MIN_VALUE);
      buffer0.writeVarInt64(Long.MAX_VALUE);
      buffer0.writeVarUint64(i);
      buffer0.writeVarUint64(Long.MIN_VALUE);
      buffer0.writeVarUint64(Long.MAX_VALUE);
      buffer0.writeSliInt64(i);
      buffer0.writeSliInt64(Long.MIN_VALUE);
      buffer0.writeSliInt64(Long.MAX_VALUE);
    }
    byte[] bytes = buffer0.getBytes(0, buffer0.writerIndex());
    ForyInputStream stream =
        ForyStreamReader.of(
            new ByteArrayInputStream(bytes) {
              @Override
              public synchronized int read(byte[] b, int off, int len) {
                buffer0.readBytes(b, off, 1);
                return 1;
              }
            });
    MemoryBuffer buffer = new MemoryBuffer(bytes, 0, 0, stream);
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
      assertEquals(buffer.readVarUint32(), i);
      assertEquals(buffer.readVarUint32(), Integer.MIN_VALUE);
      assertEquals(buffer.readVarUint32(), Integer.MAX_VALUE);
      assertEquals(buffer.readVarInt64(), i);
      assertEquals(buffer.readVarInt64(), Long.MIN_VALUE);
      assertEquals(buffer.readVarInt64(), Long.MAX_VALUE);
      assertEquals(buffer.readVarUint64(), i);
      assertEquals(buffer.readVarUint64(), Long.MIN_VALUE);
      assertEquals(buffer.readVarUint64(), Long.MAX_VALUE);
      assertEquals(buffer.readSliInt64(), i);
      assertEquals(buffer.readSliInt64(), Long.MIN_VALUE);
      assertEquals(buffer.readSliInt64(), Long.MAX_VALUE);
    }
  }

  @Test
  public void testBufferReset() {
    Fory fory = Fory.builder().withRefTracking(true).requireClassRegistration(false).build();
    byte[] bytes = fory.serialize(new byte[1000 * 1000]);
    checkBuffer(fory);
    // assertEquals(fory.deserialize(bytes), new byte[1000 * 1000]);
    assertEquals(fory.deserialize(of(new ByteArrayInputStream(bytes))), new byte[1000 * 1000]);

    bytes = fory.serializeJavaObject(new byte[1000 * 1000]);
    checkBuffer(fory);
    assertEquals(fory.deserializeJavaObject(bytes, byte[].class), new byte[1000 * 1000]);

    bytes = fory.serializeJavaObjectAndClass(new byte[1000 * 1000]);
    checkBuffer(fory);
    assertEquals(fory.deserializeJavaObjectAndClass(bytes), new byte[1000 * 1000]);

    ByteArrayOutputStream bas = new ByteArrayOutputStream();
    fory.serialize(bas, new byte[1000 * 1000]);
    checkBuffer(fory);
    Object o = fory.deserialize(of(new ByteArrayInputStream(bas.toByteArray())));
    assertEquals(o, new byte[1000 * 1000]);
    assertEquals(fory.deserialize(bas.toByteArray()), new byte[1000 * 1000]);

    bas.reset();
    fory.serializeJavaObject(bas, new byte[1000 * 1000]);
    checkBuffer(fory);
    o = fory.deserializeJavaObject(of(new ByteArrayInputStream(bas.toByteArray())), byte[].class);
    assertEquals(o, new byte[1000 * 1000]);

    bas.reset();
    fory.serializeJavaObjectAndClass(bas, new byte[1000 * 1000]);
    checkBuffer(fory);
    o = fory.deserializeJavaObjectAndClass(of(new ByteArrayInputStream(bas.toByteArray())));
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
    Fory fory = Fory.builder().requireClassRegistration(false).build();
    ByteArrayOutputStream bas = new ByteArrayOutputStream();
    BeanA beanA = BeanA.createBeanA(2);
    fory.serialize(bas, beanA);
    fory.serialize(bas, beanA);
    bas.flush();
    ByteArrayInputStream bis = new ByteArrayInputStream(bas.toByteArray());
    ForyInputStream stream = of(bis);
    MemoryBuffer buf = MemoryBuffer.wrap(bas.toByteArray());
    Object newObj = fory.deserialize(stream);
    assertEquals(newObj, beanA);
    newObj = fory.deserialize(buf);
    assertEquals(newObj, beanA);
    newObj = fory.deserialize(stream);
    assertEquals(newObj, beanA);
    newObj = fory.deserialize(buf);
    assertEquals(newObj, beanA);

    fory = Fory.builder().requireClassRegistration(false).build();
    // test reader buffer grow
    bis = new ByteArrayInputStream(bas.toByteArray());
    stream = of(bis);
    buf = MemoryBuffer.wrap(bas.toByteArray());
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
    Fory fory = Fory.builder().requireClassRegistration(false).build();
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

    fory = Fory.builder().requireClassRegistration(false).build();
    // test reader buffer grow
    bis = new ByteArrayInputStream(bas.toByteArray());
    stream = of(bis);
    MemoryBuffer buf = MemoryBuffer.wrap(bas.toByteArray());
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
  public void testJavaOutputStream() throws IOException {
    Fory fory = Fory.builder().requireClassRegistration(false).build();
    BeanA beanA = BeanA.createBeanA(2);
    {
      ByteArrayOutputStream bas = new ByteArrayOutputStream();
      fory.serializeJavaObject(bas, beanA);
      fory.serializeJavaObject(bas, beanA);
      bas.flush();
      ByteArrayInputStream bis = new ByteArrayInputStream(bas.toByteArray());
      ForyInputStream stream = of(bis);
      MemoryBuffer buf = MemoryBuffer.wrap(bas.toByteArray());
      Object newObj = fory.deserializeJavaObject(stream, BeanA.class);
      assertEquals(newObj, beanA);
      newObj = fory.deserializeJavaObject(buf, BeanA.class);
      assertEquals(newObj, beanA);
      newObj = fory.deserializeJavaObject(stream, BeanA.class);
      assertEquals(newObj, beanA);
      newObj = fory.deserializeJavaObject(buf, BeanA.class);
      assertEquals(newObj, beanA);
    }
    {
      ByteArrayOutputStream bas = new ByteArrayOutputStream();
      fory.serializeJavaObjectAndClass(bas, beanA);
      fory.serializeJavaObjectAndClass(bas, beanA);
      bas.flush();
      ByteArrayInputStream bis = new ByteArrayInputStream(bas.toByteArray());
      ForyInputStream stream = of(bis);
      MemoryBuffer buf = MemoryBuffer.wrap(bas.toByteArray());
      Object newObj = fory.deserializeJavaObjectAndClass(stream);
      assertEquals(newObj, beanA);
      newObj = fory.deserializeJavaObjectAndClass(buf);
      assertEquals(newObj, beanA);
      newObj = fory.deserializeJavaObjectAndClass(stream);
      assertEquals(newObj, beanA);
      newObj = fory.deserializeJavaObjectAndClass(buf);
      assertEquals(newObj, beanA);

      fory = Fory.builder().requireClassRegistration(false).build();
      // test reader buffer grow
      bis = new ByteArrayInputStream(bas.toByteArray());
      stream = of(bis);
      buf = MemoryBuffer.wrap(bas.toByteArray());
      newObj = fory.deserializeJavaObjectAndClass(stream);
      assertEquals(newObj, beanA);
      newObj = fory.deserializeJavaObjectAndClass(buf);
      assertEquals(newObj, beanA);
      newObj = fory.deserializeJavaObjectAndClass(stream);
      assertEquals(newObj, beanA);
      newObj = fory.deserializeJavaObjectAndClass(buf);
      assertEquals(newObj, beanA);
    }
  }

  @Test
  public void testReadableChannel() throws IOException {
    Fory fory = Fory.builder().requireClassRegistration(false).build();
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
      fory.serializeJavaObject(bas, beanA);

      Path tempFile = Files.createTempFile("readable_channel_test", "data_2");
      Files.write(tempFile, bas.toByteArray());

      try (ForyReadableChannel channel = of(Files.newByteChannel(tempFile))) {
        Object newObj = fory.deserializeJavaObject(channel, BeanA.class);
        assertEquals(newObj, beanA);
      } finally {
        Files.delete(tempFile);
      }
    }
    {
      ByteArrayOutputStream bas = new ByteArrayOutputStream();
      fory.serializeJavaObjectAndClass(bas, beanA);

      Path tempFile = Files.createTempFile("readable_channel_test", "data_3");
      Files.write(tempFile, bas.toByteArray());

      try (ForyReadableChannel channel = of(Files.newByteChannel(tempFile))) {
        Object newObj = fory.deserializeJavaObjectAndClass(channel);
        assertEquals(newObj, beanA);
      } finally {
        Files.delete(tempFile);
      }
    }
  }

  @Test
  public void testScopedMetaShare() throws IOException {
    Fory fory =
        Fory.builder()
            .requireClassRegistration(false)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
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

  @Test
  public void testBigBufferStreamingMetaShared() throws IOException {
    Fory fory = builder().withCompatibleMode(CompatibleMode.COMPATIBLE).build();
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

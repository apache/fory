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

package org.apache.fory.resolver;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.fory.context.MetaStringReader;
import org.apache.fory.context.MetaStringWriter;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.meta.EncodedMetaString;
import org.apache.fory.meta.Encoders;
import org.apache.fory.meta.MetaString;
import org.apache.fory.meta.MetaStringEncoder;
import org.apache.fory.util.StringUtils;
import org.testng.annotations.Test;

public class MetaStringIOTest {
  @Test
  public void testWriteMetaString() {
    SharedRegistry sharedRegistry = new SharedRegistry();
    MetaStringWriter writer = new MetaStringWriter(sharedRegistry);
    MetaStringReader reader = new MetaStringReader();
    MemoryBuffer buffer = MemoryUtils.buffer(32);
    String str = StringUtils.random(128, 0);
    MetaStringRef metaStringRef = writer.getOrCreateGenericMetaStringBytes(str);
    for (int i = 0; i < 128; i++) {
      writer.writeMetaStringBytes(buffer, metaStringRef);
    }
    for (int i = 0; i < 128; i++) {
      String metaString = reader.readMetaString(buffer);
      assertEquals(metaString.hashCode(), str.hashCode());
      assertEquals(metaString.getBytes(), str.getBytes());
    }
    assertTrue(buffer.writerIndex() < str.getBytes().length + 128 * 4);
  }

  @Test
  public void testWriteSmallMetaString() {
    for (MemoryBuffer buffer :
        new MemoryBuffer[] {
          MemoryUtils.buffer(32), MemoryUtils.wrap(ByteBuffer.allocateDirect(32)),
        }) {
      for (int i = 0; i < 32; i++) {
        String str = StringUtils.random(i, 0);
        SharedRegistry sharedRegistry = new SharedRegistry();
        MetaStringWriter writer = new MetaStringWriter(sharedRegistry);
        MetaStringReader reader = new MetaStringReader();
        writer.writeMetaStringBytes(buffer, writer.getOrCreateGenericMetaStringBytes(str));
        String metaString = reader.readMetaString(buffer);
        assertEquals(metaString.hashCode(), str.hashCode());
        assertEquals(metaString.getBytes(), str.getBytes());
        buffer.readerIndex(0);
        buffer.writerIndex(0);
      }
    }
  }

  @Test
  public void testSharedRegistrySharesEncodedMetaStringsAcrossWriters() {
    SharedRegistry sharedRegistry = new SharedRegistry();
    MetaStringWriter writer1 = new MetaStringWriter(sharedRegistry);
    MetaStringWriter writer2 = new MetaStringWriter(sharedRegistry);

    MetaStringRef bytes1 = writer1.getOrCreateGenericMetaStringBytes("thread_safe_fory");
    MetaStringRef bytes2 = writer2.getOrCreateGenericMetaStringBytes("thread_safe_fory");

    assertNotSame(bytes1, bytes2);
    assertSame(bytes1.getEncoded(), bytes2.getEncoded());
    assertSame(bytes1.getEncoded().bytes, bytes2.getEncoded().bytes);
  }

  @Test
  public void testSharedRegistryDoesNotMergeDifferentEncoderTypeKeys() {
    SharedRegistry sharedRegistry = new SharedRegistry();
    MetaStringWriter writer1 = new MetaStringWriter(sharedRegistry);
    MetaStringWriter writer2 = new MetaStringWriter(sharedRegistry);
    MetaStringEncoder encoder = new MetaStringEncoder('$', '_');

    MetaStringRef typeNameBytes =
        writer1.getOrCreateMetaStringBytes(
            "ExampleValue",
            encoder,
            MetaString.Encoding.LOWER_UPPER_DIGIT_SPECIAL,
            Encoders.TYPE_NAME_ENCODER_TYPE_KEY);
    MetaStringRef fieldNameBytes =
        writer2.getOrCreateMetaStringBytes(
            "ExampleValue",
            encoder,
            MetaString.Encoding.LOWER_UPPER_DIGIT_SPECIAL,
            Encoders.FIELD_NAME_ENCODER_TYPE_KEY);

    assertNotSame(typeNameBytes, fieldNameBytes);
    assertNotSame(typeNameBytes.getEncoded(), fieldNameBytes.getEncoded());
    assertNotSame(typeNameBytes.getEncoded().bytes, fieldNameBytes.getEncoded().bytes);
  }

  @Test
  public void testSharedRegistryCreatesMetaStringOnlyOnCacheMiss() {
    SharedRegistry sharedRegistry = new SharedRegistry();
    MetaStringWriter writer1 = new MetaStringWriter(sharedRegistry);
    MetaStringWriter writer2 = new MetaStringWriter(sharedRegistry);
    CountingMetaStringEncoder encoder = new CountingMetaStringEncoder('.', '_');

    MetaStringRef bytes1 =
        writer1.getOrCreateMetaStringBytes(
            "thread_safe_fory",
            encoder,
            MetaString.Encoding.LOWER_SPECIAL,
            Encoders.GENERIC_ENCODER_TYPE_KEY);
    MetaStringRef bytes2 =
        writer2.getOrCreateMetaStringBytes(
            "thread_safe_fory",
            encoder,
            MetaString.Encoding.LOWER_SPECIAL,
            Encoders.GENERIC_ENCODER_TYPE_KEY);

    assertEquals(encoder.encodeBinaryCalls.get(), 1);
    assertNotSame(bytes1, bytes2);
    assertSame(bytes1.getEncoded(), bytes2.getEncoded());
    assertSame(bytes1.getEncoded().bytes, bytes2.getEncoded().bytes);
  }

  @Test
  public void testMetaStringWriterResetClearsDynamicWriteState() {
    SharedRegistry sharedRegistry = new SharedRegistry();
    MetaStringWriter writer = new MetaStringWriter(sharedRegistry);
    MetaStringReader reader = new MetaStringReader();
    MetaStringRef metaStringRef = writer.getOrCreateGenericMetaStringBytes("thread_safe_fory");
    MemoryBuffer buffer = MemoryUtils.buffer(64);

    writer.writeMetaStringBytes(buffer, metaStringRef);
    writer.reset();
    assertEquals(
        metaStringRef.dynamicWriteStringId, MetaStringRef.DEFAULT_DYNAMIC_WRITE_STRING_ID);
    buffer.writerIndex(0);
    buffer.readerIndex(0);

    writer.writeMetaStringBytes(buffer, metaStringRef);

    assertEquals(reader.readMetaString(buffer), "thread_safe_fory");
  }

  private static final class CountingMetaStringEncoder extends MetaStringEncoder {
    private final AtomicInteger encodeBinaryCalls = new AtomicInteger();

    private CountingMetaStringEncoder(char specialChar1, char specialChar2) {
      super(specialChar1, specialChar2);
    }

    @Override
    public EncodedMetaString encodeBinary(String input, MetaString.Encoding encoding) {
      encodeBinaryCalls.incrementAndGet();
      return super.encodeBinary(input, encoding);
    }
  }
}

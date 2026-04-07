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
import org.apache.fory.context.MetaStringReader;
import org.apache.fory.context.MetaStringWriter;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.meta.EncodedMetaString;
import org.apache.fory.meta.Encoders;
import org.apache.fory.util.StringUtils;
import org.testng.annotations.Test;

public class MetaStringIOTest {
  @Test
  public void testWriteMetaString() {
    SharedRegistry sharedRegistry = new SharedRegistry();
    MetaStringWriter writer = new MetaStringWriter();
    MetaStringReader reader = new MetaStringReader(sharedRegistry);
    MemoryBuffer buffer = MemoryUtils.buffer(32);
    String str = StringUtils.random(128, 0);
    EncodedMetaString encodedMetaString = newGenericMetaString(str);
    for (int i = 0; i < 128; i++) {
      writer.writeMetaString(buffer, encodedMetaString);
    }
    for (int i = 0; i < 128; i++) {
      String decoded = reader.readMetaString(buffer).decode(Encoders.GENERIC_DECODER);
      assertEquals(decoded.hashCode(), str.hashCode());
      assertEquals(decoded.getBytes(), str.getBytes());
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
        MetaStringWriter writer = new MetaStringWriter();
        MetaStringReader reader = new MetaStringReader(sharedRegistry);
        writer.writeMetaString(buffer, newGenericMetaString(str));
        String metaString = reader.readMetaString(buffer).decode(Encoders.GENERIC_DECODER);
        assertEquals(metaString.hashCode(), str.hashCode());
        assertEquals(metaString.getBytes(), str.getBytes());
        buffer.readerIndex(0);
        buffer.writerIndex(0);
      }
    }
  }

  @Test
  public void testMetaStringWriterResetClearsDynamicWriteState() {
    SharedRegistry sharedRegistry = new SharedRegistry();
    MetaStringWriter writer = new MetaStringWriter();
    MetaStringReader reader = new MetaStringReader(sharedRegistry);
    EncodedMetaString metaString = newGenericMetaString("thread_safe_fory");
    MemoryBuffer buffer = MemoryUtils.buffer(64);

    writer.writeMetaString(buffer, metaString);
    writer.reset();
    buffer.writerIndex(0);
    buffer.readerIndex(0);

    writer.writeMetaString(buffer, metaString);

    assertEquals(
        reader.readMetaString(buffer).decode(Encoders.GENERIC_DECODER), "thread_safe_fory");
  }

  @Test
  public void testMetaStringReaderUsesSharedRegistryInstances() {
    SharedRegistry sharedRegistry = new SharedRegistry();
    MetaStringWriter writer = new MetaStringWriter();
    MetaStringReader reader = new MetaStringReader(sharedRegistry);
    EncodedMetaString encodedMetaString = newGenericMetaString("shared_meta_string");
    MemoryBuffer buffer = MemoryUtils.buffer(64);

    writer.writeMetaString(buffer, encodedMetaString);

    EncodedMetaString readMetaString = reader.readMetaString(buffer);
    EncodedMetaString cachedMetaString =
        sharedRegistry.getOrCreateEncodedMetaString(
            encodedMetaString.bytes, encodedMetaString.hash);

    assertSame(readMetaString, cachedMetaString);
  }

  @Test
  public void testSharedRegistrySkipsLongEncodedMetaStrings() {
    SharedRegistry sharedRegistry = new SharedRegistry();
    String str = StringUtils.random(2050, 0);

    EncodedMetaString first = newGenericMetaString(sharedRegistry, str);
    EncodedMetaString second = newGenericMetaString(sharedRegistry, str);

    assertNotSame(first, second);
  }

  @Test
  public void testSharedRegistryCapsEncodedMetaStringCount() {
    SharedRegistry sharedRegistry = new SharedRegistry();
    EncodedMetaString first = null;
    for (int i = 0; i < 32768; i++) {
      EncodedMetaString encodedMetaString = newGenericMetaString(sharedRegistry, "meta_" + i);
      if (i == 0) {
        first = encodedMetaString;
      }
    }

    EncodedMetaString overflow1 = newGenericMetaString(sharedRegistry, "meta_overflow");
    EncodedMetaString overflow2 = newGenericMetaString(sharedRegistry, "meta_overflow");

    assertSame(first, newGenericMetaString(sharedRegistry, "meta_0"));
    assertNotSame(overflow1, overflow2);
  }

  private static EncodedMetaString newGenericMetaString(String str) {
    return Encoders.GENERIC_ENCODER.encodeBinary(str, Encoders.computeGenericEncoding(str));
  }

  private static EncodedMetaString newGenericMetaString(SharedRegistry sharedRegistry, String str) {
    EncodedMetaString encodedMetaString = newGenericMetaString(str);
    return sharedRegistry.getOrCreateEncodedMetaString(
        encodedMetaString.bytes, encodedMetaString.hash);
  }
}

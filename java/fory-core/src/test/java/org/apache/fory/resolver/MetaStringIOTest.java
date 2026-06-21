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
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.nio.ByteBuffer;
import org.apache.fory.TestUtils;
import org.apache.fory.collection.LongLongByteMap;
import org.apache.fory.context.MetaStringReader;
import org.apache.fory.context.MetaStringWriter;
import org.apache.fory.exception.ForyException;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.meta.EncodedMetaString;
import org.apache.fory.meta.Encoders;
import org.apache.fory.util.StringUtils;
import org.testng.annotations.Test;

public class MetaStringIOTest {
  private static SharedRegistry newSharedRegistry() {
    return new SharedRegistry();
  }

  @Test
  public void testWriteMetaString() {
    SharedRegistry sharedRegistry = newSharedRegistry();
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
        SharedRegistry sharedRegistry = newSharedRegistry();
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
    SharedRegistry sharedRegistry = newSharedRegistry();
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
    SharedRegistry sharedRegistry = newSharedRegistry();
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
    SharedRegistry sharedRegistry = newSharedRegistry();
    String str = StringUtils.random(2050, 0);

    EncodedMetaString first = newGenericMetaString(sharedRegistry, str);
    EncodedMetaString second = newGenericMetaString(sharedRegistry, str);

    assertNotSame(first, second);
  }

  @Test
  public void testSharedRegistryCapsEncodedMetaStringCount() {
    SharedRegistry sharedRegistry = newSharedRegistry();
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

  @Test
  public void testReadBigMetaStringRejectsNonCanonicalHash() {
    SharedRegistry sharedRegistry = newSharedRegistry();
    MetaStringReader reader = new MetaStringReader(sharedRegistry);
    EncodedMetaString encodedMetaString = newGenericMetaString(StringUtils.random(32, 0));
    MemoryBuffer buffer = MemoryUtils.buffer(64);

    buffer.writeVarUInt32Small7(encodedMetaString.bytes.length << 1);
    buffer.writeInt64(encodedMetaString.hash + 0x100);
    buffer.writeBytes(encodedMetaString.bytes);

    expectThrows(ForyException.class, () -> reader.readMetaString(buffer));
  }

  @Test
  public void testCachedBigMetaStringReusesHeaderCache() {
    SharedRegistry sharedRegistry = newSharedRegistry();
    MetaStringReader reader = new MetaStringReader(sharedRegistry);
    EncodedMetaString encodedMetaString = newGenericMetaString(StringUtils.random(32, 0));
    MemoryBuffer buffer = MemoryUtils.buffer(128);

    buffer.writeVarUInt32Small7(encodedMetaString.bytes.length << 1);
    buffer.writeInt64(encodedMetaString.hash);
    buffer.writeBytes(encodedMetaString.bytes);
    assertSame(
        reader.readMetaString(buffer),
        sharedRegistry.getOrCreateEncodedMetaString(
            encodedMetaString.bytes, encodedMetaString.hash));
    assertEquals(buffer.readerIndex(), buffer.writerIndex());
  }

  @Test
  public void testReadSmallMetaStringKeyIncludesLengthAndEncoding() {
    SharedRegistry sharedRegistry = newSharedRegistry();
    MetaStringReader reader = new MetaStringReader(sharedRegistry);
    MemoryBuffer buffer = MemoryUtils.buffer(32);

    buffer.writeVarUInt32Small7(1 << 1);
    buffer.writeByte(0);
    buffer.writeByte('a');
    buffer.writeVarUInt32Small7(2 << 1);
    buffer.writeByte(0);
    buffer.writeByte('a');
    buffer.writeByte(0);

    EncodedMetaString oneByte = reader.readMetaString(buffer);
    EncodedMetaString twoBytes = reader.readMetaString(buffer);

    assertEquals(oneByte.bytes.length, 1);
    assertEquals(twoBytes.bytes.length, 2);
    assertNotEquals(oneByte.hash, twoBytes.hash);
  }

  @Test
  public void testMetaStringReaderResetClearsDynamicIdsOnly() {
    SharedRegistry sharedRegistry = newSharedRegistry();
    MetaStringReader reader = new MetaStringReader(sharedRegistry);
    MemoryBuffer buffer = MemoryUtils.buffer(32);

    buffer.writeVarUInt32Small7(1 << 1);
    buffer.writeByte(0);
    buffer.writeByte('a');
    reader.readMetaString(buffer);

    LongLongByteMap<?> readCache = TestUtils.getFieldValue(reader, "longLongMetaStringMap");
    assertEquals(readCache.size, 1);
    reader.reset();
    assertEquals(readCache.size, 1);

    MemoryBuffer refBuffer = MemoryUtils.buffer(8);
    refBuffer.writeVarUInt32Small7((1 << 1) | 1);
    expectThrows(ForyException.class, () -> reader.readMetaString(refBuffer));
  }

  @Test
  public void testTypeNameBytesUsesBytesWhenHashesMatch() {
    EncodedMetaString namespace1 = new EncodedMetaString(new byte[] {'a'}, 0x100);
    EncodedMetaString namespace2 = new EncodedMetaString(new byte[] {'b'}, 0x100);
    EncodedMetaString typeName = new EncodedMetaString(new byte[] {'C'}, 0x200);

    assertNotEquals(
        new TypeNameBytes(namespace1, typeName), new TypeNameBytes(namespace2, typeName));
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

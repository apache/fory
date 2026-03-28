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
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.meta.EncodedMetaString;
import org.apache.fory.meta.Encoders;
import org.apache.fory.meta.MetaString;
import org.apache.fory.meta.MetaStringEncoder;
import org.apache.fory.util.StringUtils;
import org.testng.annotations.Test;

public class MetaStringResolverTest {

  @Test
  public void testWriteMetaString() {
    MemoryBuffer buffer = MemoryUtils.buffer(32);
    String str = StringUtils.random(128, 0);
    MetaStringResolver stringResolver = new MetaStringResolver();
    for (int i = 0; i < 128; i++) {
      stringResolver.writeMetaStringBytes(
          buffer, stringResolver.getOrCreateGenericMetaStringBytes(str));
    }
    for (int i = 0; i < 128; i++) {
      String metaString = stringResolver.readMetaString(buffer);
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
        MetaStringResolver resolver = new MetaStringResolver();
        resolver.writeMetaStringBytes(buffer, resolver.getOrCreateGenericMetaStringBytes(str));
        String metaString2 = resolver.readMetaString(buffer);
        assertEquals(metaString2.hashCode(), str.hashCode());
        assertEquals(metaString2.getBytes(), str.getBytes());
      }
    }
  }

  @Test
  public void testSharedRegistrySharesEncodedBytesButKeepsDynamicIdsLocal() {
    SharedRegistry sharedRegistry = new SharedRegistry();
    MetaStringResolver resolver1 = new MetaStringResolver(sharedRegistry);
    MetaStringResolver resolver2 = new MetaStringResolver(sharedRegistry);

    MetaStringRef bytes1 = resolver1.getOrCreateGenericMetaStringBytes("thread_safe_fory");
    MetaStringRef bytes2 = resolver2.getOrCreateGenericMetaStringBytes("thread_safe_fory");

    assertNotSame(bytes1, bytes2);
    assertSame(bytes1.encoded, bytes2.encoded);
    assertSame(bytes1.encoded.bytes, bytes2.encoded.bytes);
    assertEquals(bytes1.dynamicWriteStringId, MetaStringRef.DEFAULT_DYNAMIC_WRITE_STRING_ID);
    assertEquals(bytes2.dynamicWriteStringId, MetaStringRef.DEFAULT_DYNAMIC_WRITE_STRING_ID);

    MemoryBuffer buffer = MemoryUtils.buffer(32);
    resolver1.writeMetaStringBytes(buffer, bytes1);

    assertTrue(bytes1.dynamicWriteStringId >= 0);
    assertEquals(bytes2.dynamicWriteStringId, MetaStringRef.DEFAULT_DYNAMIC_WRITE_STRING_ID);
  }

  @Test
  public void testSharedRegistryDoesNotMergeDifferentEncoderTypeKeys() {
    SharedRegistry sharedRegistry = new SharedRegistry();
    MetaStringResolver resolver1 = new MetaStringResolver(sharedRegistry);
    MetaStringResolver resolver2 = new MetaStringResolver(sharedRegistry);
    MetaStringEncoder encoder = new MetaStringEncoder('$', '_');

    MetaStringRef typeNameBytes =
        resolver1.getOrCreateMetaStringBytes(
            "ExampleValue",
            encoder,
            MetaString.Encoding.LOWER_UPPER_DIGIT_SPECIAL,
            Encoders.TYPE_NAME_ENCODER_TYPE_KEY);
    MetaStringRef fieldNameBytes =
        resolver2.getOrCreateMetaStringBytes(
            "ExampleValue",
            encoder,
            MetaString.Encoding.LOWER_UPPER_DIGIT_SPECIAL,
            Encoders.FIELD_NAME_ENCODER_TYPE_KEY);

    assertNotSame(typeNameBytes, fieldNameBytes);
    assertNotSame(typeNameBytes.encoded, fieldNameBytes.encoded);
    assertNotSame(typeNameBytes.encoded.bytes, fieldNameBytes.encoded.bytes);
  }

  @Test
  public void testSharedRegistryCreatesMetaStringOnlyOnCacheMiss() {
    SharedRegistry sharedRegistry = new SharedRegistry();
    MetaStringResolver resolver1 = new MetaStringResolver(sharedRegistry);
    MetaStringResolver resolver2 = new MetaStringResolver(sharedRegistry);
    CountingMetaStringEncoder encoder = new CountingMetaStringEncoder('.', '_');

    MetaStringRef bytes1 =
        resolver1.getOrCreateMetaStringBytes(
            "thread_safe_fory",
            encoder,
            MetaString.Encoding.LOWER_SPECIAL,
            Encoders.GENERIC_ENCODER_TYPE_KEY);
    MetaStringRef bytes2 =
        resolver2.getOrCreateMetaStringBytes(
            "thread_safe_fory",
            encoder,
            MetaString.Encoding.LOWER_SPECIAL,
            Encoders.GENERIC_ENCODER_TYPE_KEY);

    assertEquals(encoder.encodeBinaryCalls.get(), 1);
    assertNotSame(bytes1, bytes2);
    assertSame(bytes1.encoded, bytes2.encoded);
    assertSame(bytes1.encoded.bytes, bytes2.encoded.bytes);
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

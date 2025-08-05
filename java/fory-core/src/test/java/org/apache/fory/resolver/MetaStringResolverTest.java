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
import static org.testng.Assert.assertTrue;

import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.MetaString;
import org.apache.fory.meta.MetaStringEncoder;
import org.apache.fory.util.StringUtils;
import org.testng.annotations.Test;

public class MetaStringResolverTest {

  @Test
  public void testWriteMetaString() {
    MemoryBuffer buffer = MemoryBuffer.buffer(32);
    String str = StringUtils.random(128, 0);
    MetaStringResolver stringResolver = new MetaStringResolver();
    for (int i = 0; i < 128; i++) {
      MetaString metaString = new MetaStringEncoder('.', '_').encode(str);
      stringResolver.writeMetaStringBytes(
          buffer, stringResolver.getOrCreateMetaStringBytes(metaString));
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
          MemoryBuffer.buffer(32), MemoryBuffer.bufferDirect(32),
        }) {
      for (int i = 0; i < 32; i++) {
        String str = StringUtils.random(i, 0);
        MetaStringResolver resolver = new MetaStringResolver();
        resolver.writeMetaStringBytes(
            buffer,
            resolver.getOrCreateMetaStringBytes(new MetaStringEncoder('.', '_').encode(str)));
        String metaString2 = resolver.readMetaString(buffer);
        assertEquals(metaString2.hashCode(), str.hashCode());
        assertEquals(metaString2.getBytes(), str.getBytes());
      }
    }
  }
}

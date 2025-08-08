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
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
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
    BlockedStreamUtils.serializeJavaObject(fory, stream, foo);
    ByteArrayInputStream inputStream = new ByteArrayInputStream(stream.toByteArray());
    assertEquals(BlockedStreamUtils.deserialize(fory, inputStream), foo);
    assertEquals(BlockedStreamUtils.deserializeJavaObject(fory, inputStream, Foo.class), foo);
  }

  @Test
  public void testDeserializeChannel() {
    Fory fory = builder().withCodegen(false).build();
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    Foo foo = Foo.create();
    BlockedStreamUtils.serialize(fory, stream, foo);
    BlockedStreamUtils.serializeJavaObject(fory, stream, foo);
    try (MemoryBufferReadableChannel channel =
        new MemoryBufferReadableChannel(MemoryBuffer.wrap(stream.toByteArray()))) {
      assertEquals(BlockedStreamUtils.deserialize(fory, channel), foo);
      assertEquals(BlockedStreamUtils.deserializeJavaObject(fory, channel, Foo.class), foo);
    }
  }
}

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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import org.apache.fory.Fory;
import org.apache.fory.memory.MemoryBuffer;
import org.testng.annotations.Test;

public class MemoryBufferObjectOutputTest {

  @Test
  public void testForyObjectOutput() throws IOException {
    Fory fory = Fory.builder().build();
    MemoryBuffer buffer = MemoryBuffer.buffer(32);
    try (MemoryBufferObjectOutput output = new MemoryBufferObjectOutput(fory, buffer)) {
      output.writeByte(1);
      output.writeInt(2);
      output.writeLong(3);
      output.writeBoolean(true);
      output.writeFloat(4.1f);
      output.writeDouble(4.2);
      output.writeChars("abc");
      output.writeUTF("abc");
    }
    try (MemoryBufferObjectInput input = new MemoryBufferObjectInput(fory, buffer)) {
      assertEquals(input.readByte(), 1);
      assertEquals(input.readInt(), 2);
      assertEquals(input.readLong(), 3);
      assertTrue(input.readBoolean());
      assertEquals(input.readFloat(), 4.1f);
      assertEquals(input.readDouble(), 4.2);
      assertEquals(input.readUTF(), "abc");
      assertEquals(input.readUTF(), "abc");
    }
  }
}

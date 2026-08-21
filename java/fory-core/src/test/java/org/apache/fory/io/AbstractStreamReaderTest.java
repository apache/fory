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

import static org.testng.Assert.assertThrows;

import java.nio.ByteBuffer;
import org.apache.fory.memory.MemoryBuffer;
import org.testng.annotations.Test;

public class AbstractStreamReaderTest {
  @Test
  public void testIncompleteReaderThrows() {
    IncompleteStreamReader reader = new IncompleteStreamReader();
    MemoryBuffer buffer = reader.getBuffer();

    assertThrows(IndexOutOfBoundsException.class, buffer::readInt64);
    assertThrows(IndexOutOfBoundsException.class, () -> buffer.readBytes(new byte[1], 0, 1));
    assertThrows(IndexOutOfBoundsException.class, () -> buffer.read(ByteBuffer.allocate(1), 1));
    assertThrows(IndexOutOfBoundsException.class, () -> buffer.read(ByteBuffer.allocate(1)));
  }

  private static final class IncompleteStreamReader extends AbstractStreamReader {
    private final MemoryBuffer buffer;

    private IncompleteStreamReader() {
      buffer = MemoryBuffer.fromByteArray(new byte[0], 0, 0, this);
    }

    @Override
    public MemoryBuffer getBuffer() {
      return buffer;
    }
  }
}

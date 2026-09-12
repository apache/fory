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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ForyInputStreamTest {

  @Test
  public void testReadToEof() throws IOException {
    for (int dstIndex : new int[] {0, 3}) {
      EofInputStream stream = new EofInputStream();
      byte[] dst = new byte[10];
      int length = dst.length - dstIndex;
      try (ForyInputStream in = new ForyInputStream(stream)) {
        Assert.assertThrows(RuntimeException.class, () -> in.readTo(dst, dstIndex, length));
        Assert.assertEquals(stream.readCalls, 1);
      }
    }
  }

  private static class EofInputStream extends ByteArrayInputStream {
    private int readCalls;

    private EofInputStream() {
      super(new byte[0]);
    }

    @Override
    public synchronized int read(byte[] dst, int offset, int length) {
      readCalls++;
      return super.read(dst, offset, length);
    }
  }
}

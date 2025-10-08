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

package org.apache.fory.format.encoder;

import static org.testng.Assert.assertTrue;
import static org.testng.AssertJUnit.assertEquals;

import java.util.concurrent.atomic.AtomicLong;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.test.bean.BeanA;
import org.apache.fory.test.bean.BeanB;
import org.apache.fory.test.bean.Foo;
import org.testng.annotations.Test;

public class CodecBuilderTest {
  @Test
  public void genCode() {
    new RowEncoderBuilder(Foo.class).genCode();
    new RowEncoderBuilder(BeanA.class).genCode();
    new RowEncoderBuilder(BeanB.class).genCode();
  }

  @Test
  public void loadOrGenRowCodecClass() {
    Class<?> codecClass = Encoders.loadOrGenRowCodecClass(BeanA.class, DefaultCodecFormat.INSTANCE);
    assertTrue(GeneratedRowEncoder.class.isAssignableFrom(codecClass));
    assertTrue(
        GeneratedRowEncoder.class.isAssignableFrom(
            Encoders.loadOrGenRowCodecClass(BeanB.class, DefaultCodecFormat.INSTANCE)));
    assertTrue(
        GeneratedRowEncoder.class.isAssignableFrom(
            Encoders.loadOrGenRowCodecClass(AtomicLong.class, DefaultCodecFormat.INSTANCE)));
  }

  static void testStreamingEncode(Encoder encoder, Object object) {
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(32);
    for (int i = 0; i < 1; i++) {
      buffer.writerIndex(0);
      buffer.readerIndex(0);
      for (int j = 0; j <= i; j++) {
        buffer.writerIndex(0);
        buffer.readerIndex(0);
        buffer.writeByte(-1);
        buffer.readByte();
        encoder.encode(buffer, object);
        encoder.encode(buffer, object);
        assertEquals(object, encoder.decode(buffer));
        assertEquals(object, encoder.decode(buffer));
      }
    }
  }
}

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

package org.apache.fory.extension.meta;

import org.apache.fory.exception.InvalidDataException;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ZstdMetaCompressorTest {
  @Test
  public void testDecompressBoundaries() {
    ZstdMetaCompressor compressor = new ZstdMetaCompressor();
    byte[] data = new byte[256];
    byte[] compressed = compressor.compress(data, 0, data.length);

    Assert.assertEquals(compressor.decompress(compressed, 0, compressed.length, data.length), data);
    Assert.assertEquals(compressor.decompress(compressed, 0, compressed.length), data);
    InvalidDataException e =
        Assert.expectThrows(
            InvalidDataException.class,
            () -> compressor.decompress(compressed, 0, compressed.length, data.length - 1));
    Assert.assertTrue(e.getMessage().contains("Declared"));
  }
}

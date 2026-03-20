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

package org.apache.fory.meta;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.apache.fory.exception.InvalidDataException;
import org.testng.Assert;
import org.testng.annotations.Test;

public class DeflaterMetaCompressorTest {
  private final DeflaterMetaCompressor compressor = new DeflaterMetaCompressor();

  @Test
  public void testCompressDecompressRoundTrip() {
    byte[] input = sampleInput();
    byte[] compressed = compressor.compress(input, 0, input.length);
    byte[] decompressed = compressor.decompress(compressed, 0, compressed.length);
    assertEquals(decompressed, input);
  }

  @Test(timeOut = 5_000)
  public void testDecompressTruncatedInputThrowsQuickly() {
    byte[] compressed = compressor.compress(sampleInput(), 0, sampleInput().length);
    byte[] truncated = Arrays.copyOf(compressed, compressed.length - 1);
    InvalidDataException e =
        Assert.expectThrows(
            InvalidDataException.class,
            () -> compressor.decompress(truncated, 0, truncated.length));
    assertTrue(e.getMessage().contains("truncated"));
  }

  @Test(timeOut = 5_000)
  public void testDecompressCorruptedInputThrows() {
    byte[] compressed = compressor.compress(sampleInput(), 0, sampleInput().length);
    byte[] corrupted = Arrays.copyOf(compressed, compressed.length);
    corrupted[corrupted.length / 2] ^= 0x40;
    InvalidDataException e =
        Assert.expectThrows(
            InvalidDataException.class,
            () -> compressor.decompress(corrupted, 0, corrupted.length));
    assertTrue(e.getMessage().contains("Invalid compressed metadata"));
  }

  private static byte[] sampleInput() {
    return "0123456789abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz"
        .getBytes(StandardCharsets.UTF_8);
  }
}

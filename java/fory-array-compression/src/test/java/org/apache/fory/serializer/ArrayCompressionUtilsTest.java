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

package org.apache.fory.serializer;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.util.Random;
import org.apache.fory.util.ArrayCompressionUtils;
import org.apache.fory.util.PrimitiveArrayCompressionType;
import org.testng.SkipException;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class ArrayCompressionUtilsTest {

  @Test
  public void testIntArrayCompressionDetection() {
    // Test byte range compression - make array size >= 16
    int[] byteRangeArray = new int[16];
    for (int i = 0; i < 16; i++) {
      byteRangeArray[i] = (i % 256) - 128; // byte range values
    }
    assertEquals(
        ArrayCompressionUtils.determineIntCompressionType(byteRangeArray),
        PrimitiveArrayCompressionType.INT_TO_BYTE);

    // Test short range compression - make array size >= 16
    int[] shortRangeArray = new int[16];
    for (int i = 0; i < 16; i++) {
      shortRangeArray[i] = (i % 65536) - 32768; // short range values
    }
    assertEquals(
        ArrayCompressionUtils.determineIntCompressionType(shortRangeArray),
        PrimitiveArrayCompressionType.INT_TO_SHORT);

    // Test no compression for large values - make array size >= 16
    int[] largeArray = new int[16];
    for (int i = 0; i < 16; i++) {
      largeArray[i] = i % 2 == 0 ? Integer.MIN_VALUE : Integer.MAX_VALUE;
    }
    assertEquals(
        ArrayCompressionUtils.determineIntCompressionType(largeArray),
        PrimitiveArrayCompressionType.NONE);

    // Test small arrays don't compress
    int[] smallArray = {1, 2, 3};
    assertEquals(
        ArrayCompressionUtils.determineIntCompressionType(smallArray),
        PrimitiveArrayCompressionType.NONE);
  }

  @Test
  public void testLongArrayCompressionDetection() {
    // Test int range compression - make array size >= 16
    long[] intRangeArray = new long[16];
    for (int i = 0; i < 16; i++) {
      intRangeArray[i] = i % 2 == 0 ? Integer.MIN_VALUE : Integer.MAX_VALUE;
    }
    assertEquals(
        ArrayCompressionUtils.determineLongCompressionType(intRangeArray),
        PrimitiveArrayCompressionType.LONG_TO_INT);

    // Test no compression for large values - make array size >= 16
    long[] largeArray = new long[16];
    for (int i = 0; i < 16; i++) {
      largeArray[i] = i % 2 == 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
    }
    assertEquals(
        ArrayCompressionUtils.determineLongCompressionType(largeArray),
        PrimitiveArrayCompressionType.NONE);

    // Test small arrays don't compress
    long[] smallArray = {1L, 2L, 3L};
    assertEquals(
        ArrayCompressionUtils.determineLongCompressionType(smallArray),
        PrimitiveArrayCompressionType.NONE);
  }

  @Test
  public void testCompressionRoundTrip() {
    // Test int to byte compression - use arrays >= 16 for proper testing
    int[] originalInts = new int[16];
    for (int i = 0; i < 16; i++) {
      originalInts[i] = (i % 201) - 100; // byte range values
    }
    byte[] compressed = ArrayCompressionUtils.compressToBytes(originalInts);
    int[] decompressed = ArrayCompressionUtils.decompressFromBytes(compressed);
    assertEquals(decompressed, originalInts);

    // Test int to short compression
    int[] originalShorts = new int[16];
    for (int i = 0; i < 16; i++) {
      originalShorts[i] = (i % 60001) - 30000; // short range values
    }
    short[] compressedShorts = ArrayCompressionUtils.compressToShorts(originalShorts);
    int[] decompressedShorts = ArrayCompressionUtils.decompressFromShorts(compressedShorts);
    assertEquals(decompressedShorts, originalShorts);

    // Test long to int compression
    long[] originalLongs = new long[16];
    for (int i = 0; i < 16; i++) {
      originalLongs[i] = i % 2 == 0 ? -2000000000L : 2000000000L; // int range values
    }
    int[] compressedInts = ArrayCompressionUtils.compressToInts(originalLongs);
    long[] decompressedLongs = ArrayCompressionUtils.decompressFromInts(compressedInts);
    assertEquals(decompressedLongs, originalLongs);
  }

  @Test
  public void testLargeArraysWithSIMD() {
    Random random = new Random(42);

    // Test large array that compresses to bytes
    int[] largeByteArray = new int[10000];
    for (int i = 0; i < largeByteArray.length; i++) {
      largeByteArray[i] = random.nextInt(256) - 128; // byte range
    }
    assertEquals(
        ArrayCompressionUtils.determineIntCompressionType(largeByteArray),
        PrimitiveArrayCompressionType.INT_TO_BYTE);

    // Test large array that compresses to shorts
    int[] largeShortArray = new int[10000];
    for (int i = 0; i < largeShortArray.length; i++) {
      largeShortArray[i] = random.nextInt(65536) - 32768; // short range
    }
    assertEquals(
        ArrayCompressionUtils.determineIntCompressionType(largeShortArray),
        PrimitiveArrayCompressionType.INT_TO_SHORT);

    // Test large array that doesn't compress
    int[] largeArray = new int[10000];
    for (int i = 0; i < largeArray.length; i++) {
      largeArray[i] = random.nextInt();
    }
    assertEquals(
        ArrayCompressionUtils.determineIntCompressionType(largeArray),
        PrimitiveArrayCompressionType.NONE);
  }

  @Test
  public void testLargeLongArraysWithSIMD() {
    Random random = new Random(42);

    // Test large array that compresses to ints
    long[] largeIntArray = new long[10000];
    for (int i = 0; i < largeIntArray.length; i++) {
      largeIntArray[i] = random.nextInt(); // int range
    }
    assertEquals(
        ArrayCompressionUtils.determineLongCompressionType(largeIntArray),
        PrimitiveArrayCompressionType.LONG_TO_INT);

    // Test large array that doesn't compress
    long[] largeArray = new long[10000];
    for (int i = 0; i < largeArray.length; i++) {
      largeArray[i] = random.nextLong();
    }
    assertEquals(
        ArrayCompressionUtils.determineLongCompressionType(largeArray),
        PrimitiveArrayCompressionType.NONE);
  }

  @Test
  public void testEdgeCases() {
    // Test empty arrays
    int[] emptyIntArray = {};
    assertEquals(
        ArrayCompressionUtils.determineIntCompressionType(emptyIntArray),
        PrimitiveArrayCompressionType.NONE);

    long[] emptyLongArray = {};
    assertEquals(
        ArrayCompressionUtils.determineLongCompressionType(emptyLongArray),
        PrimitiveArrayCompressionType.NONE);

    // Test boundary values - use arrays >= 16
    int[] byteBoundary = new int[16];
    for (int i = 0; i < 16; i++) {
      byteBoundary[i] = i % 2 == 0 ? Byte.MIN_VALUE : Byte.MAX_VALUE;
    }
    assertEquals(
        ArrayCompressionUtils.determineIntCompressionType(byteBoundary),
        PrimitiveArrayCompressionType.INT_TO_BYTE);

    int[] shortBoundary = new int[16];
    for (int i = 0; i < 16; i++) {
      shortBoundary[i] = i % 2 == 0 ? Short.MIN_VALUE : Short.MAX_VALUE;
    }
    assertEquals(
        ArrayCompressionUtils.determineIntCompressionType(shortBoundary),
        PrimitiveArrayCompressionType.INT_TO_SHORT);

    long[] intBoundary = new long[16];
    for (int i = 0; i < 16; i++) {
      intBoundary[i] = i % 2 == 0 ? Integer.MIN_VALUE : Integer.MAX_VALUE;
    }
    assertEquals(
        ArrayCompressionUtils.determineLongCompressionType(intBoundary),
        PrimitiveArrayCompressionType.LONG_TO_INT);

    // Test just outside boundaries
    int[] outsideByte = new int[16];
    for (int i = 0; i < 16; i++) {
      outsideByte[i] = i % 2 == 0 ? Byte.MIN_VALUE - 1 : Byte.MAX_VALUE + 1;
    }
    assertEquals(
        ArrayCompressionUtils.determineIntCompressionType(outsideByte),
        PrimitiveArrayCompressionType.INT_TO_SHORT);

    int[] outsideShort = new int[16];
    for (int i = 0; i < 16; i++) {
      outsideShort[i] = i % 2 == 0 ? Short.MIN_VALUE - 1 : Short.MAX_VALUE + 1;
    }
    assertEquals(
        ArrayCompressionUtils.determineIntCompressionType(outsideShort),
        PrimitiveArrayCompressionType.NONE);

    long[] outsideInt = new long[16];
    for (int i = 0; i < 16; i++) {
      outsideInt[i] = i % 2 == 0 ? Integer.MIN_VALUE - 1L : Integer.MAX_VALUE + 1L;
    }
    assertEquals(
        ArrayCompressionUtils.determineLongCompressionType(outsideInt),
        PrimitiveArrayCompressionType.NONE);
  }

  @Test
  public void testCompressionEffectiveness() {
    // Test compression size reduction
    int[] byteRangeArray = new int[1000];
    for (int i = 0; i < byteRangeArray.length; i++) {
      byteRangeArray[i] = i % 200 - 100; // byte range
    }

    byte[] compressed = ArrayCompressionUtils.compressToBytes(byteRangeArray);
    assertEquals(compressed.length, byteRangeArray.length); // 4x smaller
    assertTrue(compressed.length < byteRangeArray.length * 4);

    // Verify decompression
    int[] decompressed = ArrayCompressionUtils.decompressFromBytes(compressed);
    assertEquals(decompressed, byteRangeArray);
  }

  @Test
  public void testMixedValues() {
    // Test array with mixed compression potential
    int[] mixedArray = new int[1000];
    // First half can compress to byte
    for (int i = 0; i < 500; i++) {
      mixedArray[i] = i % 200 - 100;
    }
    // Second half requires full int
    for (int i = 500; i < 1000; i++) {
      mixedArray[i] = Integer.MAX_VALUE - i;
    }

    assertEquals(
        ArrayCompressionUtils.determineIntCompressionType(mixedArray),
        PrimitiveArrayCompressionType.NONE);
  }
}

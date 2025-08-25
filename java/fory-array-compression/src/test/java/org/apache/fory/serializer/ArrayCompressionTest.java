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
import org.apache.fory.Fory;
import org.apache.fory.config.ForyBuilder;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class ArrayCompressionTest {

  @DataProvider(name = "intArrayData")
  public Object[][] intArrayData() {
    return new Object[][] {
      // {description, array }
      {"Empty array", new int[] {}},
      {"Small array", new int[] {1, 2, 3}},
      {"Byte range array", createByteRangeArray(1_000)},
      {"Short range array", createShortRangeArray(1_000)},
      {"Large value array", createLargeValueArray(1_000)},
      {"Mixed value array", createMixedValueArray(1_000)}
    };
  }

  @DataProvider(name = "longArrayData")
  public Object[][] longArrayData() {
    return new Object[][] {
      // {description, array }
      {"Empty array", new long[] {}},
      {"Small array", new long[] {1L, 2L, 3L}},
      {"Int range array", createIntRangeLongArray(1000)},
      {"Large value array", createLargeLongValueArray(1000)}
    };
  }

  @Test(dataProvider = "intArrayData")
  public void testIntArrayCompressionRoundTrip(String description, int[] originalArray) {
    Fory foryWithCompression = new ForyBuilder().withIntArrayCompressed(true).build();
    byte[] serializedWithCompression = foryWithCompression.serialize(originalArray);
    int[] deserializedWithCompression =
        (int[]) foryWithCompression.deserialize(serializedWithCompression);
    assertEquals(
        deserializedWithCompression,
        originalArray,
        "Round trip failed with compression: " + description);
  }

  @Test(dataProvider = "longArrayData")
  public void testLongArrayCompressionRoundTrip(String description, long[] originalArray) {
    Fory foryWithCompression = new ForyBuilder().withLongArrayCompressed(true).build();
    byte[] serializedWithCompression = foryWithCompression.serialize(originalArray);
    long[] deserializedWithCompression =
        (long[]) foryWithCompression.deserialize(serializedWithCompression);
    assertEquals(
        deserializedWithCompression,
        originalArray,
        "Round trip failed with compression: " + description);
  }

  @Test
  public void testCompressionRatios() {
    Fory foryWithCompression =
        new ForyBuilder().withIntArrayCompressed(true).withLongArrayCompressed(true).build();

    Fory foryWithoutCompression =
        new ForyBuilder().withIntArrayCompressed(false).withLongArrayCompressed(false).build();

    // Test byte-range int array compression (should achieve ~4x compression)
    int[] byteRangeArray = createByteRangeArray(10_000);
    byte[] compressedBytes = foryWithCompression.serialize(byteRangeArray);
    byte[] uncompressedBytes = foryWithoutCompression.serialize(byteRangeArray);

    double compressionRatio = (double) compressedBytes.length / uncompressedBytes.length;
    assertTrue(
        compressionRatio < 0.30,
        "Expected significant compression for byte-range array, got ratio: " + compressionRatio);

    // Test short-range int array compression (should achieve ~2x compression)
    int[] shortRangeArray = createShortRangeArray(10_000);
    compressedBytes = foryWithCompression.serialize(shortRangeArray);
    uncompressedBytes = foryWithoutCompression.serialize(shortRangeArray);

    compressionRatio = (double) compressedBytes.length / uncompressedBytes.length;
    assertTrue(
        compressionRatio < 0.55,
        "Expected moderate compression for short-range array, got ratio: " + compressionRatio);

    // Test int-range long array compression (should achieve ~2x compression)
    long[] intRangeLongArray = createIntRangeLongArray(10_000);
    compressedBytes = foryWithCompression.serialize(intRangeLongArray);
    uncompressedBytes = foryWithoutCompression.serialize(intRangeLongArray);

    compressionRatio = (double) compressedBytes.length / uncompressedBytes.length;
    assertTrue(
        compressionRatio < 0.55,
        "Expected compression for int-range long array, got ratio: " + compressionRatio);
  }

  @Test
  public void testLargeArrays() {
    Fory fory =
        new ForyBuilder().withIntArrayCompressed(true).withLongArrayCompressed(true).build();

    // Test very large compressible arrays
    int[] largeIntArray = createByteRangeArray(100_000);
    byte[] serialized = fory.serialize(largeIntArray);
    int[] deserialized = (int[]) fory.deserialize(serialized);
    assertEquals(deserialized, largeIntArray);

    long[] largeLongArray = createIntRangeLongArray(100_000);
    serialized = fory.serialize(largeLongArray);
    long[] deserializedLong = (long[]) fory.deserialize(serialized);
    assertEquals(deserializedLong, largeLongArray);
  }

  // Helper methods to create test data
  private int[] createByteRangeArray(int size) {
    int[] array = new int[size];
    Random random = new Random(42);
    for (int i = 0; i < size; i++) {
      array[i] = random.nextInt(256) - 128; // -128 to 127
    }
    return array;
  }

  private int[] createShortRangeArray(int size) {
    int[] array = new int[size];
    Random random = new Random(42);
    for (int i = 0; i < size; i++) {
      array[i] = random.nextInt(65536) - 32768; // -32768 to 32767
    }
    return array;
  }

  private int[] createLargeValueArray(int size) {
    int[] array = new int[size];
    Random random = new Random(42);
    for (int i = 0; i < size; i++) {
      array[i] = random.nextInt();
    }
    return array;
  }

  private int[] createMixedValueArray(int size) {
    int[] array = new int[size];
    Random random = new Random(42);
    for (int i = 0; i < size; i++) {
      if (i < size / 2) {
        array[i] = random.nextInt(256) - 128; // byte range
      } else {
        array[i] = random.nextInt(); // full int range
      }
    }
    return array;
  }

  private long[] createIntRangeLongArray(int size) {
    long[] array = new long[size];
    Random random = new Random(42);
    for (int i = 0; i < size; i++) {
      array[i] = random.nextInt(); // int range
    }
    return array;
  }

  private long[] createLargeLongValueArray(int size) {
    long[] array = new long[size];
    Random random = new Random(42);
    for (int i = 0; i < size; i++) {
      array[i] = random.nextLong();
    }
    return array;
  }
}

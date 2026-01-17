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

package org.apache.fory.serializer.collection;

import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.Language;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Performance test for ShortSet serialization, comparing Fory's default serializer with
 * FastUtilSerializer to demonstrate the message size optimization brought by the new serializer.
 */
public class FastUtilShortSetPerformanceTest extends ForyTestBase {

  @Test
  public void testShortSetSizeComparison() {
    // Create Fory instance without FastUtilSerializer (using default serializer)
    Fory foryDefault =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();

    // Create Fory instance with FastUtilSerializer registered
    Fory foryFastUtil =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();
    FastUtilSerializer.register(foryFastUtil, ShortOpenHashSet.class);

    // Create test data with various sizes
    int[] sizes = {10, 100, 1000};

    for (int size : sizes) {
      // Create ShortOpenHashSet
      ShortOpenHashSet set = new ShortOpenHashSet();
      for (int i = 0; i < size; i++) {
        set.add((short) i);
      }

      // Serialize with default Fory serializer
      byte[] defaultBytes = foryDefault.serialize(set);

      // Serialize with FastUtilSerializer
      byte[] fastUtilBytes = foryFastUtil.serialize(set);

      // Print comparison
      System.out.println("=== Size Comparison for " + size + " elements ===");
      System.out.println("Fory Default Serializer: " + defaultBytes.length + " bytes");
      System.out.println("Fory with FastUtilSerializer: " + fastUtilBytes.length + " bytes");

      long bytesSaved = defaultBytes.length - fastUtilBytes.length;
      double percentageSaved = ((double) bytesSaved / defaultBytes.length) * 100;

      System.out.println("Bytes saved: " + bytesSaved + " bytes");
      System.out.println("Size reduction: " + String.format("%.2f", percentageSaved) + "%");
      System.out.println();

      // Verify that FastUtilSerializer is more efficient
      Assert.assertTrue(
          fastUtilBytes.length <= defaultBytes.length,
          "FastUtilSerializer should be at least as efficient as default serializer");

      // Verify deserialization works correctly for both
      ShortOpenHashSet deserializedDefault =
          foryDefault.deserialize(defaultBytes, ShortOpenHashSet.class);
      ShortOpenHashSet deserializedFastUtil =
          foryFastUtil.deserialize(fastUtilBytes, ShortOpenHashSet.class);

      Assert.assertEquals(deserializedDefault.size(), set.size());
      Assert.assertEquals(deserializedDefault, set);
      Assert.assertEquals(deserializedFastUtil.size(), set.size());
      Assert.assertEquals(deserializedFastUtil, set);
    }
  }

  @Test
  public void testShortSetSizeOptimizationDetails() {
    // Create Fory instance without FastUtilSerializer
    Fory foryDefault =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .build();

    // Create Fory instance with FastUtilSerializer
    Fory foryFastUtil =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .build();
    FastUtilSerializer.register(foryFastUtil, ShortOpenHashSet.class);

    // Test with a specific size to show detailed comparison
    int size = 1000;
    ShortOpenHashSet set = new ShortOpenHashSet();

    for (int i = 0; i < size; i++) {
      short value = (short) (i % Short.MAX_VALUE);
      set.add(value);
    }

    byte[] defaultBytes = foryDefault.serialize(set);
    byte[] fastUtilBytes = foryFastUtil.serialize(set);

    System.out.println("=== Detailed Size Analysis for " + size + " elements ===");
    System.out.println("Fory Default Serializer: " + defaultBytes.length + " bytes");
    System.out.println("Fory with FastUtilSerializer: " + fastUtilBytes.length + " bytes");
    System.out.println();
    System.out.println("Optimization achieved:");
    long bytesSaved = defaultBytes.length - fastUtilBytes.length;
    double percentageSaved = ((double) bytesSaved / defaultBytes.length) * 100;
    System.out.println("  - Bytes saved: " + bytesSaved + " bytes");
    System.out.println("  - Size reduction: " + String.format("%.2f", percentageSaved) + "%");
    System.out.println(
        "  - Compression ratio: "
            + String.format("%.2f", ((double) defaultBytes.length / fastUtilBytes.length))
            + "x");

    // Verify correctness
    ShortOpenHashSet deserializedDefault =
        foryDefault.deserialize(defaultBytes, ShortOpenHashSet.class);
    ShortOpenHashSet deserializedFastUtil =
        foryFastUtil.deserialize(fastUtilBytes, ShortOpenHashSet.class);
    Assert.assertEquals(deserializedDefault, set);
    Assert.assertEquals(deserializedFastUtil, set);
  }

  @Test
  public void testShortSetWithSparseData() {
    // Create Fory instance without FastUtilSerializer
    Fory foryDefault =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .build();

    // Create Fory instance with FastUtilSerializer
    Fory foryFastUtil =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .build();
    FastUtilSerializer.register(foryFastUtil, ShortOpenHashSet.class);

    // Test with sparse data (large gaps between values)
    ShortOpenHashSet sparseSet = new ShortOpenHashSet();

    for (int i = 0; i < 100; i++) {
      short value = (short) (i * 100); // Large gaps
      sparseSet.add(value);
    }

    byte[] defaultBytes = foryDefault.serialize(sparseSet);
    byte[] fastUtilBytes = foryFastUtil.serialize(sparseSet);

    System.out.println("=== Sparse Data Comparison (100 elements with large gaps) ===");
    System.out.println("Fory Default Serializer: " + defaultBytes.length + " bytes");
    System.out.println("Fory with FastUtilSerializer: " + fastUtilBytes.length + " bytes");
    long bytesSaved = defaultBytes.length - fastUtilBytes.length;
    double percentageSaved = ((double) bytesSaved / defaultBytes.length) * 100;
    System.out.println(
        "Savings: " + bytesSaved + " bytes (" + String.format("%.2f", percentageSaved) + "%)");

    ShortOpenHashSet deserializedDefault =
        foryDefault.deserialize(defaultBytes, ShortOpenHashSet.class);
    ShortOpenHashSet deserializedFastUtil =
        foryFastUtil.deserialize(fastUtilBytes, ShortOpenHashSet.class);
    Assert.assertEquals(deserializedDefault, sparseSet);
    Assert.assertEquals(deserializedFastUtil, sparseSet);
  }

  @Test
  public void testShortSetWithDenseData() {
    // Create Fory instance without FastUtilSerializer
    Fory foryDefault =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .build();

    // Create Fory instance with FastUtilSerializer
    Fory foryFastUtil =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .build();
    FastUtilSerializer.register(foryFastUtil, ShortOpenHashSet.class);

    // Test with dense data (consecutive values)
    ShortOpenHashSet denseSet = new ShortOpenHashSet();

    for (int i = 0; i < 1000; i++) {
      denseSet.add((short) i);
    }

    byte[] defaultBytes = foryDefault.serialize(denseSet);
    byte[] fastUtilBytes = foryFastUtil.serialize(denseSet);

    System.out.println("=== Dense Data Comparison (1000 consecutive elements) ===");
    System.out.println("Fory Default Serializer: " + defaultBytes.length + " bytes");
    System.out.println("Fory with FastUtilSerializer: " + fastUtilBytes.length + " bytes");
    long bytesSaved = defaultBytes.length - fastUtilBytes.length;
    double percentageSaved = ((double) bytesSaved / defaultBytes.length) * 100;
    System.out.println(
        "Savings: " + bytesSaved + " bytes (" + String.format("%.2f", percentageSaved) + "%)");

    ShortOpenHashSet deserializedDefault =
        foryDefault.deserialize(defaultBytes, ShortOpenHashSet.class);
    ShortOpenHashSet deserializedFastUtil =
        foryFastUtil.deserialize(fastUtilBytes, ShortOpenHashSet.class);
    Assert.assertEquals(deserializedDefault, denseSet);
    Assert.assertEquals(deserializedFastUtil, denseSet);
  }

  @Test
  public void testShortSetPerformanceBenchmark() {
    // Create Fory instance without FastUtilSerializer
    Fory foryDefault =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .build();

    // Create Fory instance with FastUtilSerializer
    Fory foryFastUtil =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .build();
    FastUtilSerializer.register(foryFastUtil, ShortOpenHashSet.class);

    // Create test data
    ShortOpenHashSet set = new ShortOpenHashSet();
    for (int i = 0; i < 5000; i++) {
      set.add((short) i);
    }

    // Warm up
    for (int i = 0; i < 10; i++) {
      foryDefault.serialize(set);
      foryFastUtil.serialize(set);
    }

    // Benchmark serialization
    int iterations = 1000;
    long startTime, endTime;

    startTime = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      foryDefault.serialize(set);
    }
    endTime = System.nanoTime();
    long defaultSerializeTime = endTime - startTime;

    startTime = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      foryFastUtil.serialize(set);
    }
    endTime = System.nanoTime();
    long fastUtilSerializeTime = endTime - startTime;

    // Benchmark deserialization
    byte[] defaultBytes = foryDefault.serialize(set);
    byte[] fastUtilBytes = foryFastUtil.serialize(set);

    startTime = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      foryDefault.deserialize(defaultBytes, ShortOpenHashSet.class);
    }
    endTime = System.nanoTime();
    long defaultDeserializeTime = endTime - startTime;

    startTime = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      foryFastUtil.deserialize(fastUtilBytes, ShortOpenHashSet.class);
    }
    endTime = System.nanoTime();
    long fastUtilDeserializeTime = endTime - startTime;

    System.out.println(
        "=== Performance Benchmark (5000 elements, " + iterations + " iterations) ===");
    System.out.println("Serialization:");
    System.out.println("  Default Serializer: " + (defaultSerializeTime / 1_000_000.0) + " ms");
    System.out.println("  FastUtilSerializer: " + (fastUtilSerializeTime / 1_000_000.0) + " ms");
    System.out.println(
        "  Speedup: "
            + String.format("%.2f", (double) defaultSerializeTime / fastUtilSerializeTime)
            + "x");
    System.out.println("Deserialization:");
    System.out.println("  Default Serializer: " + (defaultDeserializeTime / 1_000_000.0) + " ms");
    System.out.println("  FastUtilSerializer: " + (fastUtilDeserializeTime / 1_000_000.0) + " ms");
    System.out.println(
        "  Speedup: "
            + String.format("%.2f", (double) defaultDeserializeTime / fastUtilDeserializeTime)
            + "x");
    System.out.println("Size:");
    System.out.println("  Default Serializer: " + defaultBytes.length + " bytes");
    System.out.println("  FastUtilSerializer: " + fastUtilBytes.length + " bytes");
    System.out.println(
        "  Size reduction: "
            + String.format(
                "%.2f",
                ((double) (defaultBytes.length - fastUtilBytes.length) / defaultBytes.length) * 100)
            + "%");
  }

  public void testLargeSizeShortSetSizeComparison() {
    // Create Fory instance without FastUtilSerializer (using default serializer)
    Fory foryDefault =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();

    // Create Fory instance with FastUtilSerializer registered
    Fory foryFastUtil =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();
    FastUtilSerializer.register(foryFastUtil, ShortOpenHashSet.class);

    // Create test data with various sizes
    int size = 10000;

    // Create ShortOpenHashSet
    ShortOpenHashSet set = new ShortOpenHashSet();
    for (int i = 0; i < size; i++) {
      set.add((short) i);
    }

    // Serialize with default Fory serializer
    byte[] defaultBytes = foryDefault.serialize(set);

    // Serialize with FastUtilSerializer
    byte[] fastUtilBytes = foryFastUtil.serialize(set);

    // Print comparison
    System.out.println("=== Size Comparison for " + size + " elements ===");
    System.out.println("Fory Default Serializer: " + defaultBytes.length + " bytes");
    System.out.println("Fory with FastUtilSerializer: " + fastUtilBytes.length + " bytes");

    long bytesSaved = defaultBytes.length - fastUtilBytes.length;
    double percentageSaved = ((double) bytesSaved / defaultBytes.length) * 100;

    System.out.println("Bytes saved: " + bytesSaved + " bytes");
    System.out.println("Size reduction: " + String.format("%.2f", percentageSaved) + "%");
    System.out.println();

    // Verify that FastUtilSerializer is more efficient
    Assert.assertTrue(
        fastUtilBytes.length > defaultBytes.length,
        "The default serializer performs better when the elements in the ShortSet are large");

    // Verify deserialization works correctly for both
    ShortOpenHashSet deserializedDefault =
        foryDefault.deserialize(defaultBytes, ShortOpenHashSet.class);
    ShortOpenHashSet deserializedFastUtil =
        foryFastUtil.deserialize(fastUtilBytes, ShortOpenHashSet.class);

    Assert.assertEquals(deserializedDefault.size(), set.size());
    Assert.assertEquals(deserializedDefault, set);
    Assert.assertEquals(deserializedFastUtil.size(), set.size());
    Assert.assertEquals(deserializedFastUtil, set);
  }
}

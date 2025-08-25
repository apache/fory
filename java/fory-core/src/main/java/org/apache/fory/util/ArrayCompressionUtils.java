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

package org.apache.fory.util;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * SIMD-accelerated compression utilities for primitive arrays.
 *
 * <p>This utility provides compression for primitive arrays by detecting when values can fit in
 * smaller data types:
 *
 * <ul>
 *   <li>int[] → byte[] when all values are in [-128, 127] range (75% size reduction)
 *   <li>int[] → short[] when all values are in [-32768, 32767] range (50% size reduction)
 *   <li>long[] → int[] when all values fit in integer range (50% size reduction)
 * </ul>
 *
 * <p>Uses SIMD operations (Vector API) when available on Java 16+ for optimal performance. Falls
 * back to scalar processing on older versions or when arrays are too small.
 *
 * <p>Compression is only applied to arrays with at least 16 elements to justify the analysis
 * overhead.
 */
public final class ArrayCompressionUtils {

  // Java version and Vector API availability check
  private static final int JAVA_VERSION = getJavaVersion();
  private static final boolean COMPRESSION_ENABLED = JAVA_VERSION >= 16;
  private static final boolean VECTOR_API_AVAILABLE;
  private static final VectorSpecies<Integer> INT_SPECIES;
  private static final VectorSpecies<Long> LONG_SPECIES;
  private static final int INT_VECTOR_LENGTH;
  private static final int LONG_VECTOR_LENGTH;

  // Minimum array size to justify compression overhead
  private static final int MIN_COMPRESSION_SIZE = 16;

  static {
    boolean vectorAvailable = false;
    VectorSpecies<Integer> intSpecies = null;
    VectorSpecies<Long> longSpecies = null;
    int intVectorLength = 1;
    int longVectorLength = 1;

    try {
      if (COMPRESSION_ENABLED) {
        intSpecies = IntVector.SPECIES_PREFERRED;
        longSpecies = LongVector.SPECIES_PREFERRED;
        intVectorLength = intSpecies.length();
        longVectorLength = longSpecies.length();
        vectorAvailable = intVectorLength > 1 && longVectorLength > 1;
      }
    } catch (NoClassDefFoundError | UnsupportedOperationException e) {
      // Vector API not available, fallback to scalar processing
    }

    VECTOR_API_AVAILABLE = vectorAvailable;
    INT_SPECIES = intSpecies;
    LONG_SPECIES = longSpecies;
    INT_VECTOR_LENGTH = intVectorLength;
    LONG_VECTOR_LENGTH = longVectorLength;
  }

  private static int getJavaVersion() {
    String version = System.getProperty("java.version");
    if (version.startsWith("1.")) {
      version = version.substring(2, 3);
    } else {
      int dot = version.indexOf(".");
      if (dot != -1) {
        version = version.substring(0, dot);
      }
    }
    try {
      return Integer.parseInt(version);
    } catch (NumberFormatException e) {
      return 8; // Default to Java 8 if parsing fails
    }
  }

  /**
   * Determine the best compression type for int array using SIMD.
   *
   * @param array the int array to analyze
   * @return compression type (NONE, INT_TO_BYTE, or INT_TO_SHORT)
   * @throws NullPointerException if array is null
   */
  public static PrimitiveArrayCompressionType determineIntCompressionType(int[] array) {
    if (array == null) {
      throw new NullPointerException("Array cannot be null");
    }
    if (!COMPRESSION_ENABLED || array.length < MIN_COMPRESSION_SIZE || !VECTOR_API_AVAILABLE) {
      return PrimitiveArrayCompressionType.NONE;
    }
    return determineIntCompressionTypeSIMD(array);
  }

  /**
   * Determine the best compression type for long array using SIMD.
   *
   * @param array the long array to analyze
   * @return compression type (NONE or LONG_TO_INT)
   * @throws NullPointerException if array is null
   */
  public static PrimitiveArrayCompressionType determineLongCompressionType(long[] array) {
    if (array == null) {
      throw new NullPointerException("Array cannot be null");
    }
    if (!COMPRESSION_ENABLED || array.length < MIN_COMPRESSION_SIZE || !VECTOR_API_AVAILABLE) {
      return PrimitiveArrayCompressionType.NONE;
    }
    return determineLongCompressionTypeSIMD(array);
  }

  private static PrimitiveArrayCompressionType determineIntCompressionTypeSIMD(int[] array) {
    final int byteMax = Byte.MAX_VALUE;
    final int shortMax = Short.MAX_VALUE;
    final int byteMin = Byte.MIN_VALUE;
    final int shortMin = Short.MIN_VALUE;

    boolean canCompressToByte = true;
    boolean canCompressToShort = true;

    int i = 0;
    final int loopBound = INT_VECTOR_LENGTH * (array.length / INT_VECTOR_LENGTH);

    // SIMD processing
    for (; i < loopBound && (canCompressToByte || canCompressToShort); i += INT_VECTOR_LENGTH) {
      IntVector vector = IntVector.fromArray(INT_SPECIES, array, i);

      // Calculate min/max once per vector
      int max = vector.reduceLanes(VectorOperators.MAX);
      int min = vector.reduceLanes(VectorOperators.MIN);

      // Combined boundary check for byte compression
      if (canCompressToByte && (max > byteMax || min < byteMin)) {
        canCompressToByte = false;
      }

      // Only check short compression if still viable
      if (canCompressToShort && (max > shortMax || min < shortMin)) {
        canCompressToShort = false;
      }
    }

    // Handle remaining elements
    for (; i < array.length && (canCompressToByte || canCompressToShort); i++) {
      int value = array[i];
      if (canCompressToByte && (value > byteMax || value < byteMin)) {
        canCompressToByte = false;
      }
      if (value > shortMax || value < shortMin) {
        canCompressToShort = false;
      }
    }

    if (canCompressToByte) {
      return PrimitiveArrayCompressionType.INT_TO_BYTE;
    }
    if (canCompressToShort) {
      return PrimitiveArrayCompressionType.INT_TO_SHORT;
    }
    return PrimitiveArrayCompressionType.NONE;
  }

  private static PrimitiveArrayCompressionType determineLongCompressionTypeSIMD(long[] array) {
    final long intMax = Integer.MAX_VALUE;
    final long intMin = Integer.MIN_VALUE;

    boolean canCompressToInt = true;

    int i = 0;
    final int loopBound = LONG_VECTOR_LENGTH * (array.length / LONG_VECTOR_LENGTH);

    // SIMD processing
    for (; i < loopBound && canCompressToInt; i += LONG_VECTOR_LENGTH) {
      LongVector vector = LongVector.fromArray(LONG_SPECIES, array, i);

      long max = vector.reduceLanes(VectorOperators.MAX);
      long min = vector.reduceLanes(VectorOperators.MIN);
      if (max > intMax || min < intMin) {
        canCompressToInt = false;
        break;
      }
    }

    // Handle remaining elements
    for (; i < array.length && canCompressToInt; i++) {
      long value = array[i];
      if (value > intMax || value < intMin) {
        canCompressToInt = false;
      }
    }

    return canCompressToInt
        ? PrimitiveArrayCompressionType.LONG_TO_INT
        : PrimitiveArrayCompressionType.NONE;
  }

  /**
   * Compress int array to byte array.
   *
   * @param array the int array to compress
   * @return compressed byte array
   * @throws NullPointerException if array is null
   */
  public static byte[] compressToBytes(int[] array) {
    if (array == null) {
      throw new NullPointerException("Array cannot be null");
    }
    byte[] compressed = new byte[array.length];
    for (int i = 0; i < array.length; i++) {
      compressed[i] = (byte) array[i];
    }
    return compressed;
  }

  /**
   * Compress int array to short array.
   *
   * @param array the int array to compress (values must be in short range)
   * @return compressed short array
   * @throws NullPointerException if array is null
   */
  public static short[] compressToShorts(int[] array) {
    if (array == null) {
      throw new NullPointerException("Array cannot be null");
    }
    short[] compressed = new short[array.length];
    for (int i = 0; i < array.length; i++) {
      compressed[i] = (short) array[i];
    }
    return compressed;
  }

  /**
   * Compress long array to int array.
   *
   * @param array the long array to compress (values must be in int range)
   * @return compressed int array
   * @throws NullPointerException if array is null
   */
  public static int[] compressToInts(long[] array) {
    if (array == null) {
      throw new NullPointerException("Array cannot be null");
    }
    int[] compressed = new int[array.length];
    for (int i = 0; i < array.length; i++) {
      compressed[i] = (int) array[i];
    }
    return compressed;
  }

  /**
   * Decompress byte array to int array.
   *
   * @param array the byte array to decompress
   * @return decompressed int array
   * @throws NullPointerException if array is null
   */
  public static int[] decompressFromBytes(byte[] array) {
    if (array == null) {
      throw new NullPointerException("Array cannot be null");
    }
    int[] decompressed = new int[array.length];
    for (int i = 0; i < array.length; i++) {
      decompressed[i] = array[i];
    }
    return decompressed;
  }

  /**
   * Decompress short array to int array.
   *
   * @param array the short array to decompress
   * @return decompressed int array
   * @throws NullPointerException if array is null
   */
  public static int[] decompressFromShorts(short[] array) {
    if (array == null) {
      throw new NullPointerException("Array cannot be null");
    }
    int[] decompressed = new int[array.length];
    for (int i = 0; i < array.length; i++) {
      decompressed[i] = array[i];
    }
    return decompressed;
  }

  /**
   * Decompress int array to long array.
   *
   * @param array the int array to decompress
   * @return decompressed long array
   * @throws NullPointerException if array is null
   */
  public static long[] decompressFromInts(int[] array) {
    if (array == null) {
      throw new NullPointerException("Array cannot be null");
    }
    long[] decompressed = new long[array.length];
    for (int i = 0; i < array.length; i++) {
      decompressed[i] = array[i];
    }
    return decompressed;
  }

  /** Check if array compression is supported (requires Java 16+). */
  public static boolean isCompressionSupported() {
    return COMPRESSION_ENABLED;
  }

  /** Get the current Java version. */
  public static int getCurrentJavaVersion() {
    return JAVA_VERSION;
  }
}

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
 * Utility class for primitive array compression operations. It uses SIMD-accelerated array
 * compression using Java 17+ Vector API.
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
 * <p>Uses the best available compression provider via ServiceLoader pattern. SIMD optimizations are
 * used when fory-array-compression module is available. When no compression provider is available,
 * compression is disabled entirely.
 */
public final class ArrayCompressionUtils {
  private static final VectorSpecies<Integer> INT_SPECIES = IntVector.SPECIES_PREFERRED;
  private static final VectorSpecies<Long> LONG_SPECIES = LongVector.SPECIES_PREFERRED;

    // Minimum array size to justify compression overhead
    private static final int MIN_COMPRESSION_SIZE = 16;

  /**
   * Determine the best compression type for int array.
   *
   * @param array the int array to analyze
   * @return compression type (NONE, INT_TO_BYTE, or INT_TO_SHORT)
   * @throws NullPointerException if array is null
   */
  public static PrimitiveArrayCompressionType determineIntCompressionType(int[] array) {
      if (array == null) {
          throw new NullPointerException("Input array cannot be null");
      }
      if (array.length < MIN_COMPRESSION_SIZE) {
        // No compression for empty or too small arrays
      return PrimitiveArrayCompressionType.NONE;
    }

    boolean canCompressToByte = true;
    boolean canCompressToShort = true;

    int i = 0;
    final int upperBound = INT_SPECIES.loopBound(array.length);

    // SIMD loop
    for (; i < upperBound && (canCompressToByte || canCompressToShort); i += INT_SPECIES.length()) {
      IntVector vector = IntVector.fromArray(INT_SPECIES, array, i);
      
      // Check byte compression using mask operations
      if (canCompressToByte) {
        var byteMaxMask = vector.compare(VectorOperators.GT, Byte.MAX_VALUE);
        var byteMinMask = vector.compare(VectorOperators.LT, Byte.MIN_VALUE);
        if (byteMaxMask.anyTrue() || byteMinMask.anyTrue()) {
          canCompressToByte = false;
        }
      }
      
      // Check short compression using mask operations
      if (canCompressToShort) {
        var shortMaxMask = vector.compare(VectorOperators.GT, Short.MAX_VALUE);
        var shortMinMask = vector.compare(VectorOperators.LT, Short.MIN_VALUE);
        if (shortMaxMask.anyTrue() || shortMinMask.anyTrue()) {
          canCompressToShort = false;
        }
      }
    }

    // Handle remaining elements with scalar code
    for (; i < array.length && (canCompressToByte || canCompressToShort); i++) {
      int value = array[i];
      if (canCompressToByte && (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE)) {
        canCompressToByte = false;
      }
      if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
        canCompressToShort = false;
      }
    }

    if (canCompressToByte) {
      return PrimitiveArrayCompressionType.INT_TO_BYTE;
    } else if (canCompressToShort) {
      return PrimitiveArrayCompressionType.INT_TO_SHORT;
    } else {
      return PrimitiveArrayCompressionType.NONE;
    }
  }

  /**
   * Determine the best compression type for long array.
   *
   * @param array the long array to analyze
   * @return compression type (NONE or LONG_TO_INT)
   * @throws NullPointerException if array is null
   */
  public static PrimitiveArrayCompressionType determineLongCompressionType(long[] array) {
      if (array == null) {
          throw new NullPointerException("Input array cannot be null");
      }
   if (array.length < MIN_COMPRESSION_SIZE) {
      return PrimitiveArrayCompressionType.NONE;
    }
    boolean canCompressToInt = true;

    int i = 0;
    int upperBound = LONG_SPECIES.loopBound(array.length);

    // SIMD loop
    for (; i < upperBound && canCompressToInt; i += LONG_SPECIES.length()) {
      LongVector vector = LongVector.fromArray(LONG_SPECIES, array, i);
      
      // Check int compression using mask operations
      var maxMask = vector.compare(VectorOperators.GT, Integer.MAX_VALUE);
      var minMask = vector.compare(VectorOperators.LT, Integer.MIN_VALUE);
      if (maxMask.anyTrue() || minMask.anyTrue()) {
        canCompressToInt = false;
      }
    }

      // Handle remaining elements
      for (; i < array.length && canCompressToInt; i++) {
          long value = array[i];
          if (value >  Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
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
}

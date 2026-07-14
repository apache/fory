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

/**
 * Utility methods for primitive-array width compression.
 *
 * <p>The compressed array serializers use these helpers when every value in a primitive array fits
 * in a narrower primitive type:
 *
 * <ul>
 *   <li>{@code int[]} to {@code byte[]} when all values are in byte range.
 *   <li>{@code int[]} to {@code short[]} when all values are in short range.
 *   <li>{@code long[]} to {@code int[]} when all values are in int range.
 * </ul>
 *
 * <p>This base implementation performs scalar range analysis on JDK 8 through 15. On JDK 16 and
 * later, the multi-release {@code fory-core} JAR automatically replaces this class with the Vector
 * API implementation; applications must resolve the incubator module with {@code
 * --add-modules=jdk.incubator.vector}. Both implementations expose the same API, make identical
 * compression decisions, and use the same serialized format.
 */
public final class ArrayCompressionUtils {
  // Minimum array size to justify compression analysis and the compressed payload marker overhead.
  static final int MIN_COMPRESSION_SIZE = 1 << 9;

  private ArrayCompressionUtils() {}

  /**
   * Determines the best compression type for an int array.
   *
   * @param array the array to analyze
   * @return {@link PrimitiveArrayCompressionType#INT_TO_BYTE}, {@link
   *     PrimitiveArrayCompressionType#INT_TO_SHORT}, or {@link PrimitiveArrayCompressionType#NONE}
   * @throws NullPointerException if {@code array} is null
   */
  public static PrimitiveArrayCompressionType determineIntCompressionType(int[] array) {
    if (array == null) {
      throw new NullPointerException("Input array cannot be null");
    }
    if (array.length < MIN_COMPRESSION_SIZE) {
      return PrimitiveArrayCompressionType.NONE;
    }
    boolean canCompressToByte = true;
    boolean canCompressToShort = true;
    for (int value : array) {
      if (canCompressToByte && (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE)) {
        canCompressToByte = false;
      }
      if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
        canCompressToShort = false;
      }
      if (!canCompressToByte && !canCompressToShort) {
        return PrimitiveArrayCompressionType.NONE;
      }
    }
    return canCompressToByte
        ? PrimitiveArrayCompressionType.INT_TO_BYTE
        : PrimitiveArrayCompressionType.INT_TO_SHORT;
  }

  /**
   * Determines the best compression type for a long array.
   *
   * @param array the array to analyze
   * @return {@link PrimitiveArrayCompressionType#LONG_TO_INT} or {@link
   *     PrimitiveArrayCompressionType#NONE}
   * @throws NullPointerException if {@code array} is null
   */
  public static PrimitiveArrayCompressionType determineLongCompressionType(long[] array) {
    if (array == null) {
      throw new NullPointerException("Input array cannot be null");
    }
    if (array.length < MIN_COMPRESSION_SIZE) {
      return PrimitiveArrayCompressionType.NONE;
    }
    for (long value : array) {
      if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
        return PrimitiveArrayCompressionType.NONE;
      }
    }
    return PrimitiveArrayCompressionType.LONG_TO_INT;
  }

  /**
   * Compresses an int array to a byte array.
   *
   * @param array the int array to compress; values must be in byte range
   * @return compressed byte array
   * @throws NullPointerException if {@code array} is null
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
   * Compresses an int array to a short array.
   *
   * @param array the int array to compress; values must be in short range
   * @return compressed short array
   * @throws NullPointerException if {@code array} is null
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
   * Compresses a long array to an int array.
   *
   * @param array the long array to compress; values must be in int range
   * @return compressed int array
   * @throws NullPointerException if {@code array} is null
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
   * Decompresses a byte array to an int array.
   *
   * @param array the byte array to decompress
   * @return decompressed int array
   * @throws NullPointerException if {@code array} is null
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
   * Decompresses a short array to an int array.
   *
   * @param array the short array to decompress
   * @return decompressed int array
   * @throws NullPointerException if {@code array} is null
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
   * Decompresses an int array to a long array.
   *
   * @param array the int array to decompress
   * @return decompressed long array
   * @throws NullPointerException if {@code array} is null
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

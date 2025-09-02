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
 * Compression types for primitive arrays.
 *
 * <p>Defines the available compression strategies for reducing the size of primitive arrays by
 * detecting when values can fit in smaller data types.
 */
public enum PrimitiveArrayCompressionType {
  // No compression applied
  NONE(0),

  // Compression for int arrays:
  // int[] → byte[] compression (75% size reduction)
  INT_TO_BYTE(1),
  // int[] → short[] compression (50% size reduction)
  INT_TO_SHORT(2),
  // Compression for long arrays: long[] → int[] compression (50% size reduction)
  LONG_TO_INT(3);

  private final int value;

  PrimitiveArrayCompressionType(int value) {
    this.value = value;
  }

  /**
   * Gets the numeric value for this compression type.
   *
   * @return the numeric value
   */
  public int getValue() {
    return value;
  }

  /**
   * Gets the compression type from its numeric value.
   *
   * @param value the numeric value
   * @return the corresponding compression type
   * @throws IllegalArgumentException if the value is not valid
   */
  public static PrimitiveArrayCompressionType fromValue(int value) {
    switch (value) {
      case 0:
        return NONE;
      case 1:
        return INT_TO_BYTE;
      case 2:
        return INT_TO_SHORT;
      case 3:
        return LONG_TO_INT;
      default:
        throw new IllegalArgumentException("Unknown compression type value: " + value);
    }
  }

  /** Compression utilities for int arrays. Supports compression to byte[] and short[] formats. */
  public static final class IntArrayCompression {
    /**
     * Determines the best compression type for the given int array.
     *
     * @param array the array to analyze
     * @return the optimal compression type
     */
    public static PrimitiveArrayCompressionType determine(int[] array) {
      return ArrayCompressionUtils.determineIntCompressionType(array);
    }

    /**
     * Checks if the compression type is supported for int arrays.
     *
     * @param type the compression type to check
     * @return true if supported
     */
    public static boolean isSupported(PrimitiveArrayCompressionType type) {
      return type == NONE || type == INT_TO_BYTE || type == INT_TO_SHORT;
    }
  }

  public static final class LongArrayCompression {
    /**
     * Determines the best compression type for the given long array.
     *
     * @param array the array to analyze
     * @return the optimal compression type
     */
    public static PrimitiveArrayCompressionType determine(long[] array) {
      return ArrayCompressionUtils.determineLongCompressionType(array);
    }

    /**
     * Checks if the compression type is supported for long arrays.
     *
     * @param type the compression type to check
     * @return true if supported
     */
    public static boolean isSupported(PrimitiveArrayCompressionType type) {
      return type == NONE || type == LONG_TO_INT;
    }
  }
}

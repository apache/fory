/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.fory.memory;

import sun.misc.Unsafe;

/**
 * Provides byte-by-byte memory access operations for platforms that require aligned access. This
 * class is used as a fallback when direct Unsafe operations may fail due to unaligned memory
 * addresses on certain platforms (like ARM64 with newer JDKs).
 *
 * <p>Methods with 'L' suffix read/write in little-endian byte order. Methods with 'B' suffix
 * read/write in big-endian byte order.
 */
final class Unaligned {
  private static final Unsafe UNSAFE = Platform.UNSAFE;

  private Unaligned() {}

  static short getShortB(Object o, long offset) {
    return (short) ((UNSAFE.getByte(o, offset) << 8) | (UNSAFE.getByte(o, offset + 1) & 0xFF));
  }

  static short getShortL(Object o, long offset) {
    return (short) ((UNSAFE.getByte(o, offset) & 0xFF) | (UNSAFE.getByte(o, offset + 1) << 8));
  }

  static void putShortB(Object o, long offset, short value) {
    UNSAFE.putByte(o, offset, (byte) (value >> 8));
    UNSAFE.putByte(o, offset + 1, (byte) value);
  }

  static void putShortL(Object o, long offset, short value) {
    UNSAFE.putByte(o, offset, (byte) value);
    UNSAFE.putByte(o, offset + 1, (byte) (value >> 8));
  }

  static char getCharB(Object o, long offset) {
    return (char) ((UNSAFE.getByte(o, offset) << 8) | (UNSAFE.getByte(o, offset + 1) & 0xFF));
  }

  static char getCharL(Object o, long offset) {
    return (char) ((UNSAFE.getByte(o, offset) & 0xFF) | (UNSAFE.getByte(o, offset + 1) << 8));
  }

  static void putCharB(Object o, long offset, char value) {
    UNSAFE.putByte(o, offset, (byte) (value >> 8));
    UNSAFE.putByte(o, offset + 1, (byte) value);
  }

  static void putCharL(Object o, long offset, char value) {
    UNSAFE.putByte(o, offset, (byte) value);
    UNSAFE.putByte(o, offset + 1, (byte) (value >> 8));
  }

  static int getIntB(Object o, long offset) {
    return ((UNSAFE.getByte(o, offset) & 0xFF) << 24)
        | ((UNSAFE.getByte(o, offset + 1) & 0xFF) << 16)
        | ((UNSAFE.getByte(o, offset + 2) & 0xFF) << 8)
        | (UNSAFE.getByte(o, offset + 3) & 0xFF);
  }

  static int getIntL(Object o, long offset) {
    return (UNSAFE.getByte(o, offset) & 0xFF)
        | ((UNSAFE.getByte(o, offset + 1) & 0xFF) << 8)
        | ((UNSAFE.getByte(o, offset + 2) & 0xFF) << 16)
        | ((UNSAFE.getByte(o, offset + 3) & 0xFF) << 24);
  }

  static void putIntB(Object o, long offset, int value) {
    UNSAFE.putByte(o, offset, (byte) (value >> 24));
    UNSAFE.putByte(o, offset + 1, (byte) (value >> 16));
    UNSAFE.putByte(o, offset + 2, (byte) (value >> 8));
    UNSAFE.putByte(o, offset + 3, (byte) value);
  }

  static void putIntL(Object o, long offset, int value) {
    UNSAFE.putByte(o, offset, (byte) value);
    UNSAFE.putByte(o, offset + 1, (byte) (value >> 8));
    UNSAFE.putByte(o, offset + 2, (byte) (value >> 16));
    UNSAFE.putByte(o, offset + 3, (byte) (value >> 24));
  }

  static long getLongB(Object o, long offset) {
    return ((long) (UNSAFE.getByte(o, offset) & 0xFF) << 56)
        | ((long) (UNSAFE.getByte(o, offset + 1) & 0xFF) << 48)
        | ((long) (UNSAFE.getByte(o, offset + 2) & 0xFF) << 40)
        | ((long) (UNSAFE.getByte(o, offset + 3) & 0xFF) << 32)
        | ((long) (UNSAFE.getByte(o, offset + 4) & 0xFF) << 24)
        | ((long) (UNSAFE.getByte(o, offset + 5) & 0xFF) << 16)
        | ((long) (UNSAFE.getByte(o, offset + 6) & 0xFF) << 8)
        | ((long) (UNSAFE.getByte(o, offset + 7) & 0xFF));
  }

  static long getLongL(Object o, long offset) {
    return ((long) (UNSAFE.getByte(o, offset) & 0xFF))
        | ((long) (UNSAFE.getByte(o, offset + 1) & 0xFF) << 8)
        | ((long) (UNSAFE.getByte(o, offset + 2) & 0xFF) << 16)
        | ((long) (UNSAFE.getByte(o, offset + 3) & 0xFF) << 24)
        | ((long) (UNSAFE.getByte(o, offset + 4) & 0xFF) << 32)
        | ((long) (UNSAFE.getByte(o, offset + 5) & 0xFF) << 40)
        | ((long) (UNSAFE.getByte(o, offset + 6) & 0xFF) << 48)
        | ((long) (UNSAFE.getByte(o, offset + 7) & 0xFF) << 56);
  }

  static void putLongB(Object o, long offset, long value) {
    UNSAFE.putByte(o, offset, (byte) (value >> 56));
    UNSAFE.putByte(o, offset + 1, (byte) (value >> 48));
    UNSAFE.putByte(o, offset + 2, (byte) (value >> 40));
    UNSAFE.putByte(o, offset + 3, (byte) (value >> 32));
    UNSAFE.putByte(o, offset + 4, (byte) (value >> 24));
    UNSAFE.putByte(o, offset + 5, (byte) (value >> 16));
    UNSAFE.putByte(o, offset + 6, (byte) (value >> 8));
    UNSAFE.putByte(o, offset + 7, (byte) value);
  }

  static void putLongL(Object o, long offset, long value) {
    UNSAFE.putByte(o, offset, (byte) value);
    UNSAFE.putByte(o, offset + 1, (byte) (value >> 8));
    UNSAFE.putByte(o, offset + 2, (byte) (value >> 16));
    UNSAFE.putByte(o, offset + 3, (byte) (value >> 24));
    UNSAFE.putByte(o, offset + 4, (byte) (value >> 32));
    UNSAFE.putByte(o, offset + 5, (byte) (value >> 40));
    UNSAFE.putByte(o, offset + 6, (byte) (value >> 48));
    UNSAFE.putByte(o, offset + 7, (byte) (value >> 56));
  }
}

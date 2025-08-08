/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.fory.memory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.apache.fory.util.unsafe._JDKAccess;
import sun.misc.Unsafe;

// Derived from
// https://github.com/apache/spark/blob/921fb289f003317d89120faa6937e4abd359195c/common/unsafe/src/main/java/org/apache/spark/unsafe/Platform.java.

/** A utility class for unsafe memory operations. */
@SuppressWarnings("restriction")
public final class Platform {
  @SuppressWarnings("restriction")
  public static final Unsafe UNSAFE = _JDKAccess.UNSAFE;

  public static final boolean IS_ANDROID = _JDKAccess.IS_ANDROID;
  public static final int JAVA_VERSION = _JDKAccess.JAVA_VERSION;
  public static final ByteOrder NATIVE_BYTE_ORDER = ByteOrder.nativeOrder();
  public static final boolean IS_LITTLE_ENDIAN = NATIVE_BYTE_ORDER == ByteOrder.LITTLE_ENDIAN;
  public static final Class<?> HEAP_BYTE_BUFFER_CLASS = ByteBuffer.allocate(1).getClass();
  public static final Class<?> DIRECT_BYTE_BUFFER_CLASS = ByteBuffer.allocateDirect(1).getClass();
  public static final int BOOLEAN_ARRAY_OFFSET;

  public static final int BYTE_ARRAY_OFFSET;

  public static final int CHAR_ARRAY_OFFSET;

  public static final int SHORT_ARRAY_OFFSET;

  public static final int INT_ARRAY_OFFSET;

  public static final int LONG_ARRAY_OFFSET;

  public static final int FLOAT_ARRAY_OFFSET;

  public static final int DOUBLE_ARRAY_OFFSET;

  private static final boolean unaligned;

  /**
   * Limits the number of bytes to copy per {@link Unsafe#copyMemory(long, long, long)} to allow
   * safepoint polling during a large copy.
   */
  private static final long UNSAFE_COPY_THRESHOLD = 1024L * 1024L;

  static {
    BOOLEAN_ARRAY_OFFSET = UNSAFE.arrayBaseOffset(boolean[].class);
    BYTE_ARRAY_OFFSET = UNSAFE.arrayBaseOffset(byte[].class);
    CHAR_ARRAY_OFFSET = UNSAFE.arrayBaseOffset(char[].class);
    SHORT_ARRAY_OFFSET = UNSAFE.arrayBaseOffset(short[].class);
    INT_ARRAY_OFFSET = UNSAFE.arrayBaseOffset(int[].class);
    LONG_ARRAY_OFFSET = UNSAFE.arrayBaseOffset(long[].class);
    FLOAT_ARRAY_OFFSET = UNSAFE.arrayBaseOffset(float[].class);
    DOUBLE_ARRAY_OFFSET = UNSAFE.arrayBaseOffset(double[].class);
  }

  // This requires `JAVA_VERSION` and `_UNSAFE`.
  static {
    boolean unalign;
    String arch = System.getProperty("os.arch", "");
    if ("ppc64le".equals(arch) || "ppc64".equals(arch) || "s390x".equals(arch)) {
      // Since java.nio.Bits.unaligned() doesn't return true on ppc (See JDK-8165231), but
      // ppc64 and ppc64le support it
      unalign = true;
    } else {
      try {
        Class<?> bitsClass =
            Class.forName("java.nio.Bits", false, ClassLoader.getSystemClassLoader());
        if (JAVA_VERSION >= 9) {
          // Java 9/10 and 11/12 have different field names.
          Field unalignedField =
              bitsClass.getDeclaredField(JAVA_VERSION >= 11 ? "UNALIGNED" : "unaligned");
          unalign =
              UNSAFE.getBoolean(
                  UNSAFE.staticFieldBase(unalignedField), UNSAFE.staticFieldOffset(unalignedField));
        } else {
          Method unalignedMethod = bitsClass.getDeclaredMethod("unaligned");
          unalignedMethod.setAccessible(true);
          unalign = Boolean.TRUE.equals(unalignedMethod.invoke(null));
        }
      } catch (Throwable t) {
        // We at least know x86 and x64 support unaligned access.
        //noinspection DynamicRegexReplaceableByCompiledPattern
        unalign = arch.matches("^(i[3-6]86|x86(_64)?|x64|amd64|aarch64)$");
      }
    }
    unaligned = unalign;
  }

  /**
   * Returns true when running JVM is having sun's Unsafe package available in it and underlying
   * system having unaligned-access capability.
   */
  public static boolean unaligned() {
    return unaligned;
  }

  public static long objectFieldOffset(Field f) {
    return UNSAFE.objectFieldOffset(f);
  }

  public static int getInt(Object object, long offset) {
    return UNSAFE.getInt(object, offset);
  }

  public static void putInt(Object object, long offset, int value) {
    UNSAFE.putInt(object, offset, value);
  }

  public static boolean getBoolean(Object object, long offset) {
    return UNSAFE.getBoolean(object, offset);
  }

  public static void putBoolean(Object object, long offset, boolean value) {
    UNSAFE.putBoolean(object, offset, value);
  }

  public static byte getByte(Object object, long offset) {
    return UNSAFE.getByte(object, offset);
  }

  public static void putByte(Object object, long offset, byte value) {
    UNSAFE.putByte(object, offset, value);
  }

  public static short getShort(Object object, long offset) {
    return UNSAFE.getShort(object, offset);
  }

  public static void putShort(Object object, long offset, short value) {
    UNSAFE.putShort(object, offset, value);
  }

  public static char getChar(Object obj, long offset) {
    return Platform.UNSAFE.getChar(obj, offset);
  }

  public static void putChar(Object obj, long offset, char value) {
    Platform.UNSAFE.putChar(obj, offset, value);
  }

  public static long getLong(Object object, long offset) {
    return UNSAFE.getLong(object, offset);
  }

  public static void putLong(Object object, long offset, long value) {
    UNSAFE.putLong(object, offset, value);
  }

  public static float getFloat(Object object, long offset) {
    return UNSAFE.getFloat(object, offset);
  }

  public static void putFloat(Object object, long offset, float value) {
    UNSAFE.putFloat(object, offset, value);
  }

  public static double getDouble(Object object, long offset) {
    return UNSAFE.getDouble(object, offset);
  }

  public static void putDouble(Object object, long offset, double value) {
    UNSAFE.putDouble(object, offset, value);
  }

  public static Object getObject(Object o, long offset) {
    return UNSAFE.getObject(o, offset);
  }

  public static void putObject(Object object, long offset, Object value) {
    UNSAFE.putObject(object, offset, value);
  }

  public static Object getObjectVolatile(Object object, long offset) {
    return UNSAFE.getObjectVolatile(object, offset);
  }

  public static void putObjectVolatile(Object object, long offset, Object value) {
    UNSAFE.putObjectVolatile(object, offset, value);
  }

  public static long allocateMemory(long size) {
    return UNSAFE.allocateMemory(size);
  }

  public static void freeMemory(long address) {
    UNSAFE.freeMemory(address);
  }

  public static long reallocateMemory(long address, long oldSize, long newSize) {
    long newMemory = UNSAFE.allocateMemory(newSize);
    copyMemory(null, address, null, newMemory, oldSize);
    freeMemory(address);
    return newMemory;
  }

  public static void setMemory(Object object, long offset, long size, byte value) {
    UNSAFE.setMemory(object, offset, size, value);
  }

  public static void setMemory(long address, byte value, long size) {
    UNSAFE.setMemory(address, size, value);
  }

  @Deprecated
  public static void copyMemory(
      Object src, long srcOffset, Object dst, long dstOffset, long length) {
    if (length < UNSAFE_COPY_THRESHOLD) {
      UNSAFE.copyMemory(src, srcOffset, dst, dstOffset, length);
    } else {
      while (length > 0) {
        long size = Math.min(length, UNSAFE_COPY_THRESHOLD);
        UNSAFE.copyMemory(src, srcOffset, dst, dstOffset, size);
        length -= size;
        srcOffset += size;
        dstOffset += size;
      }
    }
  }

  public static Object[] copyObjectArray(Object[] arr) {
    Object[] objects = new Object[arr.length];
    System.arraycopy(arr, 0, objects, 0, arr.length);
    return objects;
  }

  /**
   * Optimized byte array equality check for byte arrays.
   *
   * @return true if the arrays are equal, false otherwise
   */
  public static boolean arrayEquals(
      Object leftBase, long leftOffset, Object rightBase, long rightOffset, final long length) {
    int i = 0;

    // check if stars align and we can get both offsets to be aligned
    if ((leftOffset % 8) == (rightOffset % 8)) {
      while ((leftOffset + i) % 8 != 0 && i < length) {
        if (Platform.getByte(leftBase, leftOffset + i)
            != Platform.getByte(rightBase, rightOffset + i)) {
          return false;
        }
        i += 1;
      }
    }
    // for architectures that support unaligned accesses, chew it up 8 bytes at a time
    if (unaligned || (((leftOffset + i) % 8 == 0) && ((rightOffset + i) % 8 == 0))) {
      while (i <= length - 8) {
        if (Platform.getLong(leftBase, leftOffset + i)
            != Platform.getLong(rightBase, rightOffset + i)) {
          return false;
        }
        i += 8;
      }
    }
    // this will finish off the unaligned comparisons, or do the entire aligned
    // comparison whichever is needed.
    while (i < length) {
      if (Platform.getByte(leftBase, leftOffset + i)
          != Platform.getByte(rightBase, rightOffset + i)) {
        return false;
      }
      i += 1;
    }
    return true;
  }

  private static <T extends Throwable> void sneakyThrow(Throwable throwable) throws T {
    throw (T) throwable; // 将受检异常强制转换为一个未知的泛型异常类型并抛出
  }

  /** Raises an exception bypassing compiler checks for checked exceptions. */
  public static void throwException(Throwable t) {
    if (IS_ANDROID) {
      sneakyThrow(t);
    }
    UNSAFE.throwException(t);
  }

  /** Create an instance of <code>type</code>. This method don't call constructor. */
  public static <T> T newInstance(Class<T> type) {
    try {
      return type.cast(UNSAFE.allocateInstance(type));
    } catch (InstantiationException e) {
      throwException(e);
    }
    throw new IllegalStateException("unreachable");
  }
}

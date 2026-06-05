package org.apache.fory.memory;

import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.internal._UnsafeUtils;
import sun.misc.Unsafe;

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

public class LittleEndian {
  private static final Unsafe UNSAFE = AndroidSupport.IS_ANDROID ? null : _UnsafeUtils.UNSAFE;
  private static final int BYTE_ARRAY_OFFSET;

  // Keep arrayBaseOffset as a direct static-field store for GraalVM native-image recomputation.
  static {
    if (AndroidSupport.IS_ANDROID) {
      BYTE_ARRAY_OFFSET = 0;
    } else {
      BYTE_ARRAY_OFFSET = UNSAFE.arrayBaseOffset(byte[].class);
    }
  }

  public static int putVarUint36Small(byte[] arr, int index, long v) {
    if (v >>> 7 == 0) {
      arr[index] = (byte) v;
      return 1;
    }
    if (v >>> 14 == 0) {
      arr[index++] = (byte) ((v & 0x7F) | 0x80);
      arr[index] = (byte) (v >>> 7);
      return 2;
    }
    return bigWriteUint36(arr, index, v);
  }

  private static int bigWriteUint36(byte[] arr, int index, long v) {
    if (v >>> 21 == 0) {
      arr[index++] = (byte) ((v & 0x7F) | 0x80);
      arr[index++] = (byte) (v >>> 7 | 0x80);
      arr[index] = (byte) (v >>> 14);
      return 3;
    }
    if (v >>> 28 == 0) {
      arr[index++] = (byte) ((v & 0x7F) | 0x80);
      arr[index++] = (byte) (v >>> 7 | 0x80);
      arr[index++] = (byte) (v >>> 14 | 0x80);
      arr[index] = (byte) (v >>> 21);
      return 4;
    }
    arr[index++] = (byte) ((v & 0x7F) | 0x80);
    arr[index++] = (byte) (v >>> 7 | 0x80);
    arr[index++] = (byte) (v >>> 14 | 0x80);
    arr[index++] = (byte) (v >>> 21 | 0x80);
    arr[index] = (byte) (v >>> 28);
    return 5;
  }

  public static long getInt64(byte[] o, int index) {
    if (AndroidSupport.IS_ANDROID) {
      return MemoryOps.getInt64(o, index);
    }
    // Unsafe object offsets are long. Keep the cast so JDK8-compiled bytecode calls
    // getLong(Object, long) when the artifact runs on JDK9+.
    long v = UNSAFE.getLong(o, (long) BYTE_ARRAY_OFFSET + index);
    return NativeByteOrder.IS_LITTLE_ENDIAN ? v : Long.reverseBytes(v);
  }

  public static void putInt64(byte[] o, int index, long value) {
    if (AndroidSupport.IS_ANDROID) {
      MemoryOps.putInt64(o, index, value);
      return;
    }
    if (!NativeByteOrder.IS_LITTLE_ENDIAN) {
      value = Long.reverseBytes(value);
    }
    // See getInt64: the cast controls the Unsafe method descriptor in bytecode.
    UNSAFE.putLong(o, (long) BYTE_ARRAY_OFFSET + index, value);
  }
}

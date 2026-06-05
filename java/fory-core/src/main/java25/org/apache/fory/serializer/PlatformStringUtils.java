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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.nio.ByteOrder;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.NativeByteOrder;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.platform.internal._JDKAccess;
import org.apache.fory.util.Preconditions;

/** JDK25 string internals used by {@link StringSerializer}. */
final class PlatformStringUtils {
  private static final StringHandles STRING_HANDLES = stringHandles();

  static final boolean JDK_STRING_FIELD_ACCESS = STRING_HANDLES.fieldAccess;
  static final boolean STRING_VALUE_FIELD_IS_CHARS =
      JDK_STRING_FIELD_ACCESS && STRING_HANDLES.valueFieldIsChars;
  static final boolean STRING_VALUE_FIELD_IS_BYTES =
      JDK_STRING_FIELD_ACCESS && STRING_HANDLES.valueFieldIsBytes;
  static final boolean STRING_HAS_COUNT_OFFSET = JDK_STRING_FIELD_ACCESS && STRING_HANDLES.counted;

  private static final VarHandle STRING_VALUE_HANDLE = STRING_HANDLES.value;
  private static final VarHandle STRING_CODER_HANDLE = STRING_HANDLES.coder;
  private static final VarHandle STRING_COUNT_HANDLE = STRING_HANDLES.count;
  private static final VarHandle STRING_OFFSET_HANDLE = STRING_HANDLES.offset;

  private static final VarHandle BYTE_ARRAY_LONG =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());
  private static final VarHandle BYTE_ARRAY_CHAR =
      MethodHandles.byteArrayViewVarHandle(char[].class, ByteOrder.nativeOrder());

  private PlatformStringUtils() {}

  private static StringHandles stringHandles() {
    if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE || !_JDKAccess.JDK_INTERNAL_FIELD_ACCESS) {
      return StringHandles.noAccess();
    }
    try {
      Field valueField = String.class.getDeclaredField("value");
      boolean valueFieldIsChars = valueField.getType() == char[].class;
      boolean valueFieldIsBytes = valueField.getType() == byte[].class;
      Field countField = getStringFieldNullable("count");
      Field offsetField = getStringFieldNullable("offset");
      boolean counted = false;
      if (countField != null || offsetField != null) {
        Preconditions.checkArgument(
            countField != null && offsetField != null, "Current jdk not supported");
        Preconditions.checkArgument(
            countField.getType() == int.class && offsetField.getType() == int.class,
            "Current jdk not supported");
        counted = true;
      }
      try {
        Lookup stringLookup = _JDKAccess._trustedLookup(String.class);
        return new StringHandles(
            true,
            valueFieldIsChars,
            valueFieldIsBytes,
            counted,
            stringLookup.findVarHandle(String.class, "value", valueField.getType()),
            valueFieldIsBytes
                ? stringLookup.findVarHandle(String.class, "coder", byte.class)
                : null,
            countField == null
                ? null
                : stringLookup.findVarHandle(String.class, "count", int.class),
            offsetField == null
                ? null
                : stringLookup.findVarHandle(String.class, "offset", int.class));
      } catch (Throwable e) {
        throw new IllegalStateException(
            "JDK25+ string internals are inaccessible. " + _JDKAccess.jdk25AccessMessage(),
            e);
      }
    } catch (NoSuchFieldException e) {
      throw new RuntimeException(e);
    }
  }

  private static Field getStringFieldNullable(String fieldName) {
    try {
      return String.class.getDeclaredField(fieldName);
    } catch (NoSuchFieldException e) {
      return null;
    }
  }

  private static final class StringHandles {
    private final boolean fieldAccess;
    private final boolean valueFieldIsChars;
    private final boolean valueFieldIsBytes;
    private final boolean counted;
    private final VarHandle value;
    private final VarHandle coder;
    private final VarHandle count;
    private final VarHandle offset;

    private StringHandles(
        boolean fieldAccess,
        boolean valueFieldIsChars,
        boolean valueFieldIsBytes,
        boolean counted,
        VarHandle value,
        VarHandle coder,
        VarHandle count,
        VarHandle offset) {
      this.fieldAccess = fieldAccess;
      this.valueFieldIsChars = valueFieldIsChars;
      this.valueFieldIsBytes = valueFieldIsBytes;
      this.counted = counted;
      this.value = value;
      this.coder = coder;
      this.count = count;
      this.offset = offset;
    }

    private static StringHandles noAccess() {
      return new StringHandles(false, false, false, false, null, null, null, null);
    }
  }

  static Object getStringValue(String value) {
    return STRING_VALUE_HANDLE.get(value);
  }

  static byte getStringCoder(String value) {
    return (byte) STRING_CODER_HANDLE.get(value);
  }

  static int getStringOffset(String value) {
    return (int) STRING_OFFSET_HANDLE.get(value);
  }

  static int getStringCount(String value) {
    return (int) STRING_COUNT_HANDLE.get(value);
  }

  static long getCharsLong(char[] chars, int charIndex) {
    long c0 = chars[charIndex];
    long c1 = chars[charIndex + 1];
    long c2 = chars[charIndex + 2];
    long c3 = chars[charIndex + 3];
    if (NativeByteOrder.IS_LITTLE_ENDIAN) {
      return c0 | (c1 << 16) | (c2 << 32) | (c3 << 48);
    } else {
      return (c0 << 48) | (c1 << 32) | (c2 << 16) | c3;
    }
  }

  static long getBytesLong(byte[] bytes, int byteIndex) {
    return (long) BYTE_ARRAY_LONG.get(bytes, byteIndex);
  }

  static char getBytesChar(byte[] bytes, int byteIndex) {
    return (char) BYTE_ARRAY_CHAR.get(bytes, byteIndex);
  }

  static void copyCharsToBytes(
      char[] chars, int charOffset, byte[] target, int byteOffset, int numBytes) {
    int charIndex = charOffset;
    if (NativeByteOrder.IS_LITTLE_ENDIAN) {
      for (int i = byteOffset, end = byteOffset + numBytes; i < end; i += 2) {
        char c = chars[charIndex++];
        target[i] = (byte) c;
        target[i + 1] = (byte) (c >>> 8);
      }
    } else {
      for (int i = byteOffset, end = byteOffset + numBytes; i < end; i += 2) {
        char c = chars[charIndex++];
        target[i] = (byte) (c >>> 8);
        target[i + 1] = (byte) c;
      }
    }
  }

  static void putBytes(MemoryBuffer buffer, int writerIndex, byte[] bytes, int numBytes) {
    buffer.put(writerIndex, bytes, 0, numBytes);
  }
}

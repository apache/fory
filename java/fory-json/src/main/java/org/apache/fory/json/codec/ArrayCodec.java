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

package org.apache.fory.json.codec;

import java.lang.reflect.Array;
import java.util.Arrays;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;

public abstract class ArrayCodec<T> implements JsonCodec<T> {
  final Class<?> componentType;

  ArrayCodec(Class<?> componentType) {
    this.componentType = componentType;
  }

  public static ArrayCodec<?> create(Class<?> componentType, JsonTypeResolver resolver) {
    JsonTypeInfo componentTypeInfo = resolver.getTypeInfo(componentType, componentType);
    Object componentCodec = componentTypeInfo.stringWriter();
    if (componentType == int.class && componentCodec == ScalarCodecs.IntCodec.PRIMITIVE) {
      return IntArrayCodec.INSTANCE;
    } else if (componentType == long.class && componentCodec == ScalarCodecs.LongCodec.PRIMITIVE) {
      return LongArrayCodec.INSTANCE;
    } else if (componentType == boolean.class
        && componentCodec == ScalarCodecs.BooleanCodec.PRIMITIVE) {
      return BooleanArrayCodec.INSTANCE;
    } else if (componentType == short.class
        && componentCodec == ScalarCodecs.ShortCodec.PRIMITIVE) {
      return ShortArrayCodec.INSTANCE;
    } else if (componentType == byte.class && componentCodec == ScalarCodecs.ByteCodec.PRIMITIVE) {
      return ByteArrayCodec.INSTANCE;
    } else if (componentType == char.class && componentCodec == ScalarCodecs.CharCodec.PRIMITIVE) {
      return CharArrayCodec.INSTANCE;
    } else if (componentType == float.class
        && componentCodec == ScalarCodecs.FloatCodec.PRIMITIVE) {
      return FloatArrayCodec.INSTANCE;
    } else if (componentType == double.class
        && componentCodec == ScalarCodecs.DoubleCodec.PRIMITIVE) {
      return DoubleArrayCodec.INSTANCE;
    } else if (componentType == Integer.class && componentCodec == ScalarCodecs.IntCodec.BOXED) {
      return BoxedIntArrayCodec.INSTANCE;
    } else if (componentType == Long.class && componentCodec == ScalarCodecs.LongCodec.BOXED) {
      return BoxedLongArrayCodec.INSTANCE;
    } else if (componentType == Boolean.class
        && componentCodec == ScalarCodecs.BooleanCodec.BOXED) {
      return BoxedBooleanArrayCodec.INSTANCE;
    } else if (componentType == Short.class && componentCodec == ScalarCodecs.ShortCodec.BOXED) {
      return BoxedShortArrayCodec.INSTANCE;
    } else if (componentType == Byte.class && componentCodec == ScalarCodecs.ByteCodec.BOXED) {
      return BoxedByteArrayCodec.INSTANCE;
    } else if (componentType == Character.class && componentCodec == ScalarCodecs.CharCodec.BOXED) {
      return BoxedCharArrayCodec.INSTANCE;
    } else if (componentType == Float.class && componentCodec == ScalarCodecs.FloatCodec.BOXED) {
      return BoxedFloatArrayCodec.INSTANCE;
    } else if (componentType == Double.class && componentCodec == ScalarCodecs.DoubleCodec.BOXED) {
      return BoxedDoubleArrayCodec.INSTANCE;
    } else if (componentType == String.class
        && componentCodec == ScalarCodecs.StringCodec.INSTANCE) {
      return StringArrayCodec.INSTANCE;
    }
    if (componentType.isPrimitive()) {
      return new CustomPrimitiveArrayCodec(componentType, componentTypeInfo);
    }
    return new ObjectArrayCodec(componentType, componentTypeInfo);
  }

  public static final class IntArrayCodec extends ArrayCodec<int[]> {
    private static final IntArrayCodec INSTANCE = new IntArrayCodec();

    private IntArrayCodec() {
      super(int.class);
    }

    @Override
    public void writeString(StringJsonWriter writer, int[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      int[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        writer.writeInt(array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, int[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      int[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        writer.writeInt(array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    public int[] readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new int[0];
      }
      int[] values = new int[8];
      int size = 0;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readIntValue();
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public int[] readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new int[0];
      }
      int[] values = new int[8];
      int size = 0;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readIntValue();
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public int[] readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new int[0];
      }
      int[] values = new int[8];
      int size = 0;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readIntValue();
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class LongArrayCodec extends ArrayCodec<long[]> {
    private static final LongArrayCodec INSTANCE = new LongArrayCodec();

    private LongArrayCodec() {
      super(long.class);
    }

    @Override
    public void writeString(StringJsonWriter writer, long[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      long[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        writer.writeLong(array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, long[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      long[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        writer.writeLong(array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    public long[] readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new long[0];
      }
      rejectNull(reader);
      long v0 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new long[] {v0};
      }
      rejectNull(reader);
      long v1 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new long[] {v0, v1};
      }
      rejectNull(reader);
      long v2 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new long[] {v0, v1, v2};
      }
      rejectNull(reader);
      long v3 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new long[] {v0, v1, v2, v3};
      }
      return readLatin1Tail(reader, v0, v1, v2, v3);
    }

    private long[] readLatin1Tail(Latin1JsonReader reader, long v0, long v1, long v2, long v3) {
      rejectNull(reader);
      long v4 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new long[] {v0, v1, v2, v3, v4};
      }
      rejectNull(reader);
      long v5 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new long[] {v0, v1, v2, v3, v4, v5};
      }
      rejectNull(reader);
      long v6 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new long[] {v0, v1, v2, v3, v4, v5, v6};
      }
      rejectNull(reader);
      long v7 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new long[] {v0, v1, v2, v3, v4, v5, v6, v7};
      }
      return readLatin1LongTail(reader, v0, v1, v2, v3, v4, v5, v6, v7);
    }

    private long[] readLatin1LongTail(
        Latin1JsonReader reader,
        long v0,
        long v1,
        long v2,
        long v3,
        long v4,
        long v5,
        long v6,
        long v7) {
      long[] values = new long[16];
      values[0] = v0;
      values[1] = v1;
      values[2] = v2;
      values[3] = v3;
      values[4] = v4;
      values[5] = v5;
      values[6] = v6;
      values[7] = v7;
      int size = 8;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readLongValue();
      } while (reader.consumeNextCommaOrEndArray());
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public long[] readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new long[0];
      }
      rejectNull(reader);
      long v0 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new long[] {v0};
      }
      rejectNull(reader);
      long v1 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new long[] {v0, v1};
      }
      rejectNull(reader);
      long v2 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new long[] {v0, v1, v2};
      }
      rejectNull(reader);
      long v3 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new long[] {v0, v1, v2, v3};
      }
      return readUtf16Tail(reader, v0, v1, v2, v3);
    }

    private long[] readUtf16Tail(Utf16JsonReader reader, long v0, long v1, long v2, long v3) {
      rejectNull(reader);
      long v4 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new long[] {v0, v1, v2, v3, v4};
      }
      rejectNull(reader);
      long v5 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new long[] {v0, v1, v2, v3, v4, v5};
      }
      rejectNull(reader);
      long v6 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new long[] {v0, v1, v2, v3, v4, v5, v6};
      }
      rejectNull(reader);
      long v7 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new long[] {v0, v1, v2, v3, v4, v5, v6, v7};
      }
      return readUtf16LongTail(reader, v0, v1, v2, v3, v4, v5, v6, v7);
    }

    private long[] readUtf16LongTail(
        Utf16JsonReader reader,
        long v0,
        long v1,
        long v2,
        long v3,
        long v4,
        long v5,
        long v6,
        long v7) {
      long[] values = new long[16];
      values[0] = v0;
      values[1] = v1;
      values[2] = v2;
      values[3] = v3;
      values[4] = v4;
      values[5] = v5;
      values[6] = v6;
      values[7] = v7;
      int size = 8;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readLongValue();
      } while (reader.consumeNextCommaOrEndArray());
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public long[] readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new long[0];
      }
      rejectNull(reader);
      long v0 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new long[] {v0};
      }
      rejectNull(reader);
      long v1 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new long[] {v0, v1};
      }
      rejectNull(reader);
      long v2 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new long[] {v0, v1, v2};
      }
      rejectNull(reader);
      long v3 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new long[] {v0, v1, v2, v3};
      }
      return readUtf8Tail(reader, v0, v1, v2, v3);
    }

    private long[] readUtf8Tail(Utf8JsonReader reader, long v0, long v1, long v2, long v3) {
      rejectNull(reader);
      long v4 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new long[] {v0, v1, v2, v3, v4};
      }
      rejectNull(reader);
      long v5 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new long[] {v0, v1, v2, v3, v4, v5};
      }
      rejectNull(reader);
      long v6 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new long[] {v0, v1, v2, v3, v4, v5, v6};
      }
      rejectNull(reader);
      long v7 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new long[] {v0, v1, v2, v3, v4, v5, v6, v7};
      }
      return readUtf8LongTail(reader, v0, v1, v2, v3, v4, v5, v6, v7);
    }

    // Keep dynamic growth out of the exact small-array path so C2 can inline the common cases
    // without pulling the uncommon grow/copy loop into the caller's inline budget.
    private long[] readUtf8LongTail(
        Utf8JsonReader reader,
        long v0,
        long v1,
        long v2,
        long v3,
        long v4,
        long v5,
        long v6,
        long v7) {
      long[] values = new long[16];
      values[0] = v0;
      values[1] = v1;
      values[2] = v2;
      values[3] = v3;
      values[4] = v4;
      values[5] = v5;
      values[6] = v6;
      values[7] = v7;
      int size = 8;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readLongValue();
      } while (reader.consumeNextCommaOrEndArray());
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class BooleanArrayCodec extends ArrayCodec<boolean[]> {
    private static final BooleanArrayCodec INSTANCE = new BooleanArrayCodec();

    private BooleanArrayCodec() {
      super(boolean.class);
    }

    @Override
    public void writeString(StringJsonWriter writer, boolean[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      boolean[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        writer.writeBoolean(array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, boolean[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      boolean[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        writer.writeBoolean(array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    public boolean[] readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new boolean[0];
      }
      boolean[] values = new boolean[8];
      int size = 0;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readBooleanValue();
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public boolean[] readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new boolean[0];
      }
      boolean[] values = new boolean[8];
      int size = 0;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readBooleanValue();
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public boolean[] readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new boolean[0];
      }
      boolean[] values = new boolean[8];
      int size = 0;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readBooleanValue();
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class ShortArrayCodec extends ArrayCodec<short[]> {
    private static final ShortArrayCodec INSTANCE = new ShortArrayCodec();

    private ShortArrayCodec() {
      super(short.class);
    }

    @Override
    public void writeString(StringJsonWriter writer, short[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      short[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        writer.writeInt(array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, short[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      short[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        writer.writeInt(array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    public short[] readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new short[0];
      }
      short[] values = new short[8];
      int size = 0;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = readShort(reader.readIntValue());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public short[] readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new short[0];
      }
      short[] values = new short[8];
      int size = 0;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = readShort(reader.readIntValue());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public short[] readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new short[0];
      }
      short[] values = new short[8];
      int size = 0;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = readShort(reader.readIntValue());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class ByteArrayCodec extends ArrayCodec<byte[]> {
    private static final ByteArrayCodec INSTANCE = new ByteArrayCodec();

    private ByteArrayCodec() {
      super(byte.class);
    }

    @Override
    public void writeString(StringJsonWriter writer, byte[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      byte[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        writer.writeInt(array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, byte[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      byte[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        writer.writeInt(array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    public byte[] readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new byte[0];
      }
      byte[] values = new byte[8];
      int size = 0;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = readByte(reader.readIntValue());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public byte[] readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new byte[0];
      }
      byte[] values = new byte[8];
      int size = 0;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = readByte(reader.readIntValue());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public byte[] readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new byte[0];
      }
      byte[] values = new byte[8];
      int size = 0;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = readByte(reader.readIntValue());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class CharArrayCodec extends ArrayCodec<char[]> {
    private static final CharArrayCodec INSTANCE = new CharArrayCodec();

    private CharArrayCodec() {
      super(char.class);
    }

    @Override
    public void writeString(StringJsonWriter writer, char[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      char[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        writer.writeChar(array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, char[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      char[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        writer.writeChar(array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    public char[] readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new char[0];
      }
      char[] values = new char[8];
      int size = 0;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = readChar(reader.readString());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public char[] readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new char[0];
      }
      char[] values = new char[8];
      int size = 0;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = readChar(reader.readString());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public char[] readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new char[0];
      }
      char[] values = new char[8];
      int size = 0;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = readChar(reader.readString());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class FloatArrayCodec extends ArrayCodec<float[]> {
    private static final FloatArrayCodec INSTANCE = new FloatArrayCodec();

    private FloatArrayCodec() {
      super(float.class);
    }

    @Override
    public void writeString(StringJsonWriter writer, float[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      float[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        writer.writeFloat(array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, float[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      float[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        writer.writeFloat(array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    public float[] readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new float[0];
      }
      float[] values = new float[8];
      int size = 0;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readNextFloatValue();
      } while (reader.consumeNextCommaOrEndArray());
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public float[] readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new float[0];
      }
      float[] values = new float[8];
      int size = 0;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readNextFloatValue();
      } while (reader.consumeNextCommaOrEndArray());
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public float[] readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new float[0];
      }
      float[] values = new float[8];
      int size = 0;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readNextFloatValue();
      } while (reader.consumeNextCommaOrEndArray());
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class DoubleArrayCodec extends ArrayCodec<double[]> {
    private static final DoubleArrayCodec INSTANCE = new DoubleArrayCodec();

    private DoubleArrayCodec() {
      super(double.class);
    }

    @Override
    public void writeString(StringJsonWriter writer, double[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      double[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        writer.writeDouble(array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, double[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      double[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        writer.writeDouble(array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    public double[] readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new double[0];
      }
      double[] values = new double[8];
      int size = 0;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readNextDoubleValue();
      } while (reader.consumeNextCommaOrEndArray());
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public double[] readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new double[0];
      }
      double[] values = new double[8];
      int size = 0;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readNextDoubleValue();
      } while (reader.consumeNextCommaOrEndArray());
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public double[] readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new double[0];
      }
      double[] values = new double[8];
      int size = 0;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readNextDoubleValue();
      } while (reader.consumeNextCommaOrEndArray());
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class StringArrayCodec extends ArrayCodec<String[]> {
    private static final StringArrayCodec INSTANCE = new StringArrayCodec();

    private StringArrayCodec() {
      super(String.class);
    }

    @Override
    public void writeString(StringJsonWriter writer, String[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      String[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeStringElement(i, array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, String[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      String[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeStringElement(i, array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    public String[] readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new String[0];
      }
      String v0 = reader.readNextNullableString();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new String[] {v0};
      }
      String v1 = reader.readNextNullableString();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new String[] {v0, v1};
      }
      String v2 = reader.readNextNullableString();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new String[] {v0, v1, v2};
      }
      String v3 = reader.readNextNullableString();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new String[] {v0, v1, v2, v3};
      }
      return readLatin1Tail(reader, v0, v1, v2, v3);
    }

    private String[] readLatin1Tail(
        Latin1JsonReader reader, String v0, String v1, String v2, String v3) {
      String v4 = reader.readNextNullableString();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new String[] {v0, v1, v2, v3, v4};
      }
      String v5 = reader.readNextNullableString();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new String[] {v0, v1, v2, v3, v4, v5};
      }
      String v6 = reader.readNextNullableString();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new String[] {v0, v1, v2, v3, v4, v5, v6};
      }
      String v7 = reader.readNextNullableString();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new String[] {v0, v1, v2, v3, v4, v5, v6, v7};
      }
      String v8 = reader.readNextNullableString();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new String[] {v0, v1, v2, v3, v4, v5, v6, v7, v8};
      }
      return readLatin1LongTail(reader, v0, v1, v2, v3, v4, v5, v6, v7, v8);
    }

    private String[] readLatin1LongTail(
        Latin1JsonReader reader,
        String v0,
        String v1,
        String v2,
        String v3,
        String v4,
        String v5,
        String v6,
        String v7,
        String v8) {
      String[] values = new String[16];
      values[0] = v0;
      values[1] = v1;
      values[2] = v2;
      values[3] = v3;
      values[4] = v4;
      values[5] = v5;
      values[6] = v6;
      values[7] = v7;
      values[8] = v8;
      int size = 9;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readNextNullableString();
      } while (reader.consumeNextCommaOrEndArray());
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public String[] readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new String[0];
      }
      String v0 = reader.readNextNullableString();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new String[] {v0};
      }
      String v1 = reader.readNextNullableString();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new String[] {v0, v1};
      }
      String v2 = reader.readNextNullableString();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new String[] {v0, v1, v2};
      }
      String v3 = reader.readNextNullableString();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new String[] {v0, v1, v2, v3};
      }
      return readUtf16Tail(reader, v0, v1, v2, v3);
    }

    private String[] readUtf16Tail(
        Utf16JsonReader reader, String v0, String v1, String v2, String v3) {
      String v4 = reader.readNextNullableString();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new String[] {v0, v1, v2, v3, v4};
      }
      String v5 = reader.readNextNullableString();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new String[] {v0, v1, v2, v3, v4, v5};
      }
      String v6 = reader.readNextNullableString();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new String[] {v0, v1, v2, v3, v4, v5, v6};
      }
      String v7 = reader.readNextNullableString();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new String[] {v0, v1, v2, v3, v4, v5, v6, v7};
      }
      String v8 = reader.readNextNullableString();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new String[] {v0, v1, v2, v3, v4, v5, v6, v7, v8};
      }
      return readUtf16LongTail(reader, v0, v1, v2, v3, v4, v5, v6, v7, v8);
    }

    private String[] readUtf16LongTail(
        Utf16JsonReader reader,
        String v0,
        String v1,
        String v2,
        String v3,
        String v4,
        String v5,
        String v6,
        String v7,
        String v8) {
      String[] values = new String[16];
      values[0] = v0;
      values[1] = v1;
      values[2] = v2;
      values[3] = v3;
      values[4] = v4;
      values[5] = v5;
      values[6] = v6;
      values[7] = v7;
      values[8] = v8;
      int size = 9;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readNextNullableString();
      } while (reader.consumeNextCommaOrEndArray());
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public String[] readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new String[0];
      }
      String v0 = reader.readNextNullableString();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new String[] {v0};
      }
      String v1 = reader.readNextNullableString();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new String[] {v0, v1};
      }
      String v2 = reader.readNextNullableString();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new String[] {v0, v1, v2};
      }
      String v3 = reader.readNextNullableString();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new String[] {v0, v1, v2, v3};
      }
      return readUtf8Tail(reader, v0, v1, v2, v3);
    }

    private String[] readUtf8Tail(
        Utf8JsonReader reader, String v0, String v1, String v2, String v3) {
      String v4 = reader.readNextNullableString();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new String[] {v0, v1, v2, v3, v4};
      }
      String v5 = reader.readNextNullableString();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new String[] {v0, v1, v2, v3, v4, v5};
      }
      String v6 = reader.readNextNullableString();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new String[] {v0, v1, v2, v3, v4, v5, v6};
      }
      String v7 = reader.readNextNullableString();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new String[] {v0, v1, v2, v3, v4, v5, v6, v7};
      }
      String v8 = reader.readNextNullableString();
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        return new String[] {v0, v1, v2, v3, v4, v5, v6, v7, v8};
      }
      return readUtf8LongTail(reader, v0, v1, v2, v3, v4, v5, v6, v7, v8);
    }

    // Keep dynamic growth out of the exact small-array path so C2 can inline the common cases
    // without pulling the uncommon grow/copy loop into the caller's inline budget.
    private String[] readUtf8LongTail(
        Utf8JsonReader reader,
        String v0,
        String v1,
        String v2,
        String v3,
        String v4,
        String v5,
        String v6,
        String v7,
        String v8) {
      String[] values = new String[16];
      values[0] = v0;
      values[1] = v1;
      values[2] = v2;
      values[3] = v3;
      values[4] = v4;
      values[5] = v5;
      values[6] = v6;
      values[7] = v7;
      values[8] = v8;
      int size = 9;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readNextNullableString();
      } while (reader.consumeNextCommaOrEndArray());
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class ObjectArrayCodec extends ArrayCodec<Object[]> {
    private static final int VALUES_CACHE_DEPTH = 8;
    private static final int INITIAL_VALUES_SIZE = 8;
    private static final int MAX_CACHED_VALUES_SIZE = 1024;

    private final JsonTypeInfo elementTypeInfo;
    // Recursive object-array reads borrow one scratch slot per active depth.
    private final Object[][] valuesCache = new Object[VALUES_CACHE_DEPTH][];
    private int valuesDepth;

    private ObjectArrayCodec(Class<?> componentType, JsonTypeInfo elementTypeInfo) {
      super(componentType);
      this.elementTypeInfo = elementTypeInfo;
    }

    @Override
    public void writeString(StringJsonWriter writer, Object[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      Object[] array = value;
      StringWriterCodec<Object> codec = elementTypeInfo.stringWriter();
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        codec.writeString(writer, array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Object[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      Object[] array = value;
      Utf8WriterCodec<Object> codec = elementTypeInfo.utf8Writer();
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        codec.writeUtf8(writer, array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    public Object[] readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      int depth = valuesDepth;
      boolean useCache = depth < VALUES_CACHE_DEPTH;
      Object[] values = null;
      if (useCache) {
        values = valuesCache[depth];
        valuesCache[depth] = null;
      }
      if (values == null) {
        values = new Object[INITIAL_VALUES_SIZE];
      }
      int size = 0;
      boolean success = false;
      valuesDepth = depth + 1;
      Latin1ReaderCodec<Object> codec = elementTypeInfo.latin1Reader();
      try {
        reader.expectNextToken('[');
        if (!reader.consumeNextToken(']')) {
          do {
            if (size == values.length) {
              values = Arrays.copyOf(values, values.length << 1);
            }
            values[size++] = codec.readLatin1(reader);
          } while (reader.consumeNextCommaOrEndArray());
        }
        Object[] array = (Object[]) Array.newInstance(componentType, size);
        System.arraycopy(values, 0, array, 0, size);
        success = true;
        return array;
      } finally {
        releaseValues(values, size, depth, useCache, success);
        reader.exitDepth();
      }
    }

    @Override
    public Object[] readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      int depth = valuesDepth;
      boolean useCache = depth < VALUES_CACHE_DEPTH;
      Object[] values = null;
      if (useCache) {
        values = valuesCache[depth];
        valuesCache[depth] = null;
      }
      if (values == null) {
        values = new Object[INITIAL_VALUES_SIZE];
      }
      int size = 0;
      boolean success = false;
      valuesDepth = depth + 1;
      Utf16ReaderCodec<Object> codec = elementTypeInfo.utf16Reader();
      try {
        reader.expectNextToken('[');
        if (!reader.consumeNextToken(']')) {
          do {
            if (size == values.length) {
              values = Arrays.copyOf(values, values.length << 1);
            }
            values[size++] = codec.readUtf16(reader);
          } while (reader.consumeNextCommaOrEndArray());
        }
        Object[] array = (Object[]) Array.newInstance(componentType, size);
        System.arraycopy(values, 0, array, 0, size);
        success = true;
        return array;
      } finally {
        releaseValues(values, size, depth, useCache, success);
        reader.exitDepth();
      }
    }

    @Override
    public Object[] readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      int depth = valuesDepth;
      boolean useCache = depth < VALUES_CACHE_DEPTH;
      Object[] values = null;
      if (useCache) {
        values = valuesCache[depth];
        valuesCache[depth] = null;
      }
      if (values == null) {
        values = new Object[INITIAL_VALUES_SIZE];
      }
      int size = 0;
      boolean success = false;
      valuesDepth = depth + 1;
      Utf8ReaderCodec<Object> codec = elementTypeInfo.utf8Reader();
      try {
        reader.expectNextToken('[');
        if (!reader.consumeNextToken(']')) {
          do {
            if (size == values.length) {
              values = Arrays.copyOf(values, values.length << 1);
            }
            values[size++] = codec.readUtf8(reader);
          } while (reader.consumeNextCommaOrEndArray());
        }
        Object[] array = (Object[]) Array.newInstance(componentType, size);
        System.arraycopy(values, 0, array, 0, size);
        success = true;
        return array;
      } finally {
        releaseValues(values, size, depth, useCache, success);
        reader.exitDepth();
      }
    }

    private void releaseValues(
        Object[] values, int size, int depth, boolean useCache, boolean success) {
      // Failed reads drop the scratch array, because it may contain partially parsed user values.
      if (success && useCache) {
        if (values.length <= MAX_CACHED_VALUES_SIZE) {
          Arrays.fill(values, 0, size, null);
          valuesCache[depth] = values;
        } else {
          // Keep the depth slot usable without retaining a grown array from one large value.
          valuesCache[depth] = new Object[INITIAL_VALUES_SIZE];
        }
      }
      // Restore the codec recursion depth after the matching cache slot has been handled.
      valuesDepth = depth;
    }
  }

  private static final class CustomPrimitiveArrayCodec extends ArrayCodec<Object> {
    private static final int INITIAL_SIZE = 8;

    private final JsonTypeInfo elementTypeInfo;

    private CustomPrimitiveArrayCodec(Class<?> componentType, JsonTypeInfo elementTypeInfo) {
      super(componentType);
      this.elementTypeInfo = elementTypeInfo;
    }

    @Override
    public void writeString(StringJsonWriter writer, Object value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      StringWriterCodec<Object> codec = elementTypeInfo.stringWriter();
      writer.writeArrayStart();
      int length = Array.getLength(value);
      for (int i = 0; i < length; i++) {
        writer.writeComma(i);
        codec.writeString(writer, Array.get(value, i));
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Object value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      Utf8WriterCodec<Object> codec = elementTypeInfo.utf8Writer();
      writer.writeArrayStart();
      int length = Array.getLength(value);
      for (int i = 0; i < length; i++) {
        writer.writeComma(i);
        codec.writeUtf8(writer, Array.get(value, i));
      }
      writer.writeArrayEnd();
    }

    @Override
    public Object readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return Array.newInstance(componentType, 0);
      }
      Object values = Array.newInstance(componentType, INITIAL_SIZE);
      int size = 0;
      Latin1ReaderCodec<Object> codec = elementTypeInfo.latin1Reader();
      do {
        if (size == Array.getLength(values)) {
          Object grown = Array.newInstance(componentType, size << 1);
          System.arraycopy(values, 0, grown, 0, size);
          values = grown;
        }
        Object element = codec.readLatin1(reader);
        putElement(values, size++, element);
      } while (reader.consumeNextCommaOrEndArray());
      Object result = copyArray(values, size);
      reader.exitDepth();
      return result;
    }

    @Override
    public Object readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return Array.newInstance(componentType, 0);
      }
      Object values = Array.newInstance(componentType, INITIAL_SIZE);
      int size = 0;
      Utf16ReaderCodec<Object> codec = elementTypeInfo.utf16Reader();
      do {
        if (size == Array.getLength(values)) {
          Object grown = Array.newInstance(componentType, size << 1);
          System.arraycopy(values, 0, grown, 0, size);
          values = grown;
        }
        Object element = codec.readUtf16(reader);
        putElement(values, size++, element);
      } while (reader.consumeNextCommaOrEndArray());
      Object result = copyArray(values, size);
      reader.exitDepth();
      return result;
    }

    @Override
    public Object readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return Array.newInstance(componentType, 0);
      }
      Object values = Array.newInstance(componentType, INITIAL_SIZE);
      int size = 0;
      Utf8ReaderCodec<Object> codec = elementTypeInfo.utf8Reader();
      do {
        if (size == Array.getLength(values)) {
          Object grown = Array.newInstance(componentType, size << 1);
          System.arraycopy(values, 0, grown, 0, size);
          values = grown;
        }
        Object element = codec.readUtf8(reader);
        putElement(values, size++, element);
      } while (reader.consumeNextCommaOrEndArray());
      Object result = copyArray(values, size);
      reader.exitDepth();
      return result;
    }

    private void putElement(Object values, int index, Object element) {
      if (element == null) {
        throw new ForyJsonException("Cannot read null into primitive array element");
      }
      Array.set(values, index, element);
    }

    private Object copyArray(Object values, int size) {
      if (size == Array.getLength(values)) {
        return values;
      }
      Object result = Array.newInstance(componentType, size);
      System.arraycopy(values, 0, result, 0, size);
      return result;
    }
  }

  public static final class BoxedIntArrayCodec extends ArrayCodec<Integer[]> {
    private static final BoxedIntArrayCodec INSTANCE = new BoxedIntArrayCodec();

    private BoxedIntArrayCodec() {
      super(Integer.class);
    }

    @Override
    public void writeString(StringJsonWriter writer, Integer[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      Integer[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        Integer element = array[i];
        if (element == null) {
          writer.writeNull();
        } else {
          writer.writeInt(element);
        }
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Integer[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      Integer[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        Integer element = array[i];
        if (element == null) {
          writer.writeNull();
        } else {
          writer.writeInt(element);
        }
      }
      writer.writeArrayEnd();
    }

    @Override
    public Integer[] readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new Integer[0];
      }
      Integer[] values = new Integer[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken() ? null : Integer.valueOf(reader.readIntValue());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public Integer[] readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new Integer[0];
      }
      Integer[] values = new Integer[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken() ? null : Integer.valueOf(reader.readIntValue());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public Integer[] readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new Integer[0];
      }
      Integer[] values = new Integer[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken() ? null : Integer.valueOf(reader.readIntValue());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class BoxedLongArrayCodec extends ArrayCodec<Long[]> {
    private static final BoxedLongArrayCodec INSTANCE = new BoxedLongArrayCodec();

    private BoxedLongArrayCodec() {
      super(Long.class);
    }

    @Override
    public void writeString(StringJsonWriter writer, Long[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      Long[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        Long element = array[i];
        if (element == null) {
          writer.writeNull();
        } else {
          writer.writeLong(element);
        }
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Long[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      Long[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        Long element = array[i];
        if (element == null) {
          writer.writeNull();
        } else {
          writer.writeLong(element);
        }
      }
      writer.writeArrayEnd();
    }

    @Override
    public Long[] readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new Long[0];
      }
      Long[] values = new Long[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken() ? null : Long.valueOf(reader.readLongValue());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public Long[] readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new Long[0];
      }
      Long[] values = new Long[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken() ? null : Long.valueOf(reader.readLongValue());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public Long[] readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new Long[0];
      }
      Long[] values = new Long[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken() ? null : Long.valueOf(reader.readLongValue());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class BoxedBooleanArrayCodec extends ArrayCodec<Boolean[]> {
    private static final BoxedBooleanArrayCodec INSTANCE = new BoxedBooleanArrayCodec();

    private BoxedBooleanArrayCodec() {
      super(Boolean.class);
    }

    @Override
    public void writeString(StringJsonWriter writer, Boolean[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      Boolean[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        Boolean element = array[i];
        if (element == null) {
          writer.writeNull();
        } else {
          writer.writeBoolean(element);
        }
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Boolean[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      Boolean[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        Boolean element = array[i];
        if (element == null) {
          writer.writeNull();
        } else {
          writer.writeBoolean(element);
        }
      }
      writer.writeArrayEnd();
    }

    @Override
    public Boolean[] readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new Boolean[0];
      }
      Boolean[] values = new Boolean[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken() ? null : Boolean.valueOf(reader.readBooleanValue());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public Boolean[] readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new Boolean[0];
      }
      Boolean[] values = new Boolean[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken() ? null : Boolean.valueOf(reader.readBooleanValue());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public Boolean[] readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new Boolean[0];
      }
      Boolean[] values = new Boolean[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken() ? null : Boolean.valueOf(reader.readBooleanValue());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class BoxedShortArrayCodec extends ArrayCodec<Short[]> {
    private static final BoxedShortArrayCodec INSTANCE = new BoxedShortArrayCodec();

    private BoxedShortArrayCodec() {
      super(Short.class);
    }

    @Override
    public void writeString(StringJsonWriter writer, Short[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      Short[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        Short element = array[i];
        if (element == null) {
          writer.writeNull();
        } else {
          writer.writeInt(element);
        }
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Short[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      Short[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        Short element = array[i];
        if (element == null) {
          writer.writeNull();
        } else {
          writer.writeInt(element);
        }
      }
      writer.writeArrayEnd();
    }

    @Override
    public Short[] readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new Short[0];
      }
      Short[] values = new Short[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken() ? null : Short.valueOf(readShort(reader.readIntValue()));
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public Short[] readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new Short[0];
      }
      Short[] values = new Short[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken() ? null : Short.valueOf(readShort(reader.readIntValue()));
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public Short[] readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new Short[0];
      }
      Short[] values = new Short[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken() ? null : Short.valueOf(readShort(reader.readIntValue()));
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class BoxedByteArrayCodec extends ArrayCodec<Byte[]> {
    private static final BoxedByteArrayCodec INSTANCE = new BoxedByteArrayCodec();

    private BoxedByteArrayCodec() {
      super(Byte.class);
    }

    @Override
    public void writeString(StringJsonWriter writer, Byte[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      Byte[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        Byte element = array[i];
        if (element == null) {
          writer.writeNull();
        } else {
          writer.writeInt(element);
        }
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Byte[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      Byte[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        Byte element = array[i];
        if (element == null) {
          writer.writeNull();
        } else {
          writer.writeInt(element);
        }
      }
      writer.writeArrayEnd();
    }

    @Override
    public Byte[] readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new Byte[0];
      }
      Byte[] values = new Byte[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken() ? null : Byte.valueOf(readByte(reader.readIntValue()));
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public Byte[] readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new Byte[0];
      }
      Byte[] values = new Byte[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken() ? null : Byte.valueOf(readByte(reader.readIntValue()));
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public Byte[] readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new Byte[0];
      }
      Byte[] values = new Byte[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken() ? null : Byte.valueOf(readByte(reader.readIntValue()));
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class BoxedCharArrayCodec extends ArrayCodec<Character[]> {
    private static final BoxedCharArrayCodec INSTANCE = new BoxedCharArrayCodec();

    private BoxedCharArrayCodec() {
      super(Character.class);
    }

    @Override
    public void writeString(StringJsonWriter writer, Character[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      Character[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        Character element = array[i];
        if (element == null) {
          writer.writeNull();
        } else {
          writer.writeChar(element);
        }
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Character[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      Character[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        Character element = array[i];
        if (element == null) {
          writer.writeNull();
        } else {
          writer.writeChar(element);
        }
      }
      writer.writeArrayEnd();
    }

    @Override
    public Character[] readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new Character[0];
      }
      Character[] values = new Character[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        String element = reader.readNextNullableString();
        values[size++] = element == null ? null : Character.valueOf(readChar(element));
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public Character[] readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new Character[0];
      }
      Character[] values = new Character[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        String element = reader.readNextNullableString();
        values[size++] = element == null ? null : Character.valueOf(readChar(element));
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public Character[] readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new Character[0];
      }
      Character[] values = new Character[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        String element = reader.readNextNullableString();
        values[size++] = element == null ? null : Character.valueOf(readChar(element));
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class BoxedFloatArrayCodec extends ArrayCodec<Float[]> {
    private static final BoxedFloatArrayCodec INSTANCE = new BoxedFloatArrayCodec();

    private BoxedFloatArrayCodec() {
      super(Float.class);
    }

    @Override
    public void writeString(StringJsonWriter writer, Float[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      Float[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        Float element = array[i];
        if (element == null) {
          writer.writeNull();
        } else {
          writer.writeFloat(element);
        }
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Float[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      Float[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        Float element = array[i];
        if (element == null) {
          writer.writeNull();
        } else {
          writer.writeFloat(element);
        }
      }
      writer.writeArrayEnd();
    }

    @Override
    public Float[] readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new Float[0];
      }
      Float[] values = new Float[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken() ? null : Float.valueOf(reader.readNextFloatValue());
      } while (reader.consumeNextCommaOrEndArray());
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public Float[] readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new Float[0];
      }
      Float[] values = new Float[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken() ? null : Float.valueOf(reader.readNextFloatValue());
      } while (reader.consumeNextCommaOrEndArray());
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public Float[] readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new Float[0];
      }
      Float[] values = new Float[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken() ? null : Float.valueOf(reader.readNextFloatValue());
      } while (reader.consumeNextCommaOrEndArray());
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class BoxedDoubleArrayCodec extends ArrayCodec<Double[]> {
    private static final BoxedDoubleArrayCodec INSTANCE = new BoxedDoubleArrayCodec();

    private BoxedDoubleArrayCodec() {
      super(Double.class);
    }

    @Override
    public void writeString(StringJsonWriter writer, Double[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      Double[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        Double element = array[i];
        if (element == null) {
          writer.writeNull();
        } else {
          writer.writeDouble(element);
        }
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Double[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      Double[] array = value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        Double element = array[i];
        if (element == null) {
          writer.writeNull();
        } else {
          writer.writeDouble(element);
        }
      }
      writer.writeArrayEnd();
    }

    @Override
    public Double[] readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new Double[0];
      }
      Double[] values = new Double[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken() ? null : Double.valueOf(reader.readNextDoubleValue());
      } while (reader.consumeNextCommaOrEndArray());
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public Double[] readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new Double[0];
      }
      Double[] values = new Double[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken() ? null : Double.valueOf(reader.readNextDoubleValue());
      } while (reader.consumeNextCommaOrEndArray());
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public Double[] readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new Double[0];
      }
      Double[] values = new Double[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken() ? null : Double.valueOf(reader.readNextDoubleValue());
      } while (reader.consumeNextCommaOrEndArray());
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  private static void rejectNull(JsonReader reader) {
    if (reader.tryReadNull()) {
      throw new ForyJsonException("Cannot read null into primitive array element");
    }
  }

  private static void rejectNull(Latin1JsonReader reader) {
    if (reader.tryReadNullToken()) {
      throw new ForyJsonException("Cannot read null into primitive array element");
    }
  }

  private static void rejectNull(Utf16JsonReader reader) {
    if (reader.tryReadNullToken()) {
      throw new ForyJsonException("Cannot read null into primitive array element");
    }
  }

  private static void rejectNull(Utf8JsonReader reader) {
    if (reader.tryReadNullToken()) {
      throw new ForyJsonException("Cannot read null into primitive array element");
    }
  }

  private static short readShort(int value) {
    if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
      throw new ForyJsonException("Short overflow");
    }
    return (short) value;
  }

  private static byte readByte(int value) {
    if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
      throw new ForyJsonException("Byte overflow");
    }
    return (byte) value;
  }

  private static char readChar(JsonReader reader) {
    return readChar(reader.readString());
  }

  private static char readChar(String value) {
    if (value.length() != 1) {
      throw new ForyJsonException("Expected one-character JSON string for char");
    }
    return value.charAt(0);
  }
}

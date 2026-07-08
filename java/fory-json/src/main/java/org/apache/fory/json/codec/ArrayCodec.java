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
import org.apache.fory.json.writer.JsonWriter;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;

public abstract class ArrayCodec extends AbstractJsonCodec {
  final Class<?> componentType;

  ArrayCodec(Class<?> componentType) {
    this.componentType = componentType;
  }

  public static ArrayCodec create(Class<?> componentType, JsonTypeResolver resolver) {
    if (componentType == int.class) {
      return IntArrayCodec.INSTANCE;
    } else if (componentType == long.class) {
      return LongArrayCodec.INSTANCE;
    } else if (componentType == boolean.class) {
      return BooleanArrayCodec.INSTANCE;
    } else if (componentType == short.class) {
      return ShortArrayCodec.INSTANCE;
    } else if (componentType == byte.class) {
      return ByteArrayCodec.INSTANCE;
    } else if (componentType == char.class) {
      return CharArrayCodec.INSTANCE;
    } else if (componentType == float.class) {
      return FloatArrayCodec.INSTANCE;
    } else if (componentType == double.class) {
      return DoubleArrayCodec.INSTANCE;
    } else if (componentType == Integer.class) {
      return BoxedIntArrayCodec.INSTANCE;
    } else if (componentType == Long.class) {
      return BoxedLongArrayCodec.INSTANCE;
    } else if (componentType == Boolean.class) {
      return BoxedBooleanArrayCodec.INSTANCE;
    } else if (componentType == Short.class) {
      return BoxedShortArrayCodec.INSTANCE;
    } else if (componentType == Byte.class) {
      return BoxedByteArrayCodec.INSTANCE;
    } else if (componentType == Character.class) {
      return BoxedCharArrayCodec.INSTANCE;
    } else if (componentType == Float.class) {
      return BoxedFloatArrayCodec.INSTANCE;
    } else if (componentType == Double.class) {
      return BoxedDoubleArrayCodec.INSTANCE;
    } else if (componentType == String.class) {
      return StringArrayCodec.INSTANCE;
    }
    return new ObjectArrayCodec(
        componentType, resolver.getTypeInfo(componentType, componentType), resolver);
  }

  @Override
  void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
    writeNonNull(writer, value, resolver);
  }

  @Override
  void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
    writeNonNull(writer, value, resolver);
  }

  public static final class IntArrayCodec extends ArrayCodec {
    private static final IntArrayCodec INSTANCE = new IntArrayCodec();

    private IntArrayCodec() {
      super(int.class);
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      int[] array = (int[]) value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        writer.writeInt(array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      reader.expect('[');
      if (reader.consume(']')) {
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
        values[size++] = reader.readInt();
      } while (reader.consume(','));
      reader.expect(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public Object readLatin1(
        Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
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
    public Object readUtf16(
        Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
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
    public Object readUtf8(
        Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
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

  public static final class LongArrayCodec extends ArrayCodec {
    private static final LongArrayCodec INSTANCE = new LongArrayCodec();

    private LongArrayCodec() {
      super(long.class);
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      long[] array = (long[]) value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        writer.writeLong(array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      reader.expect('[');
      if (reader.consume(']')) {
        reader.exitDepth();
        return new long[0];
      }
      long[] values = new long[8];
      int size = 0;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readLong();
      } while (reader.consume(','));
      reader.expect(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public Object readLatin1(
        Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
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
    public Object readUtf16(
        Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
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
    public Object readUtf8(
        Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
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

  public static final class BooleanArrayCodec extends ArrayCodec {
    private static final BooleanArrayCodec INSTANCE = new BooleanArrayCodec();

    private BooleanArrayCodec() {
      super(boolean.class);
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      boolean[] array = (boolean[]) value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        writer.writeBoolean(array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      reader.expect('[');
      if (reader.consume(']')) {
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
        values[size++] = reader.readBoolean();
      } while (reader.consume(','));
      reader.expect(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public Object readLatin1(
        Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
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
    public Object readUtf16(
        Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
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
    public Object readUtf8(
        Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
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

  public static final class ShortArrayCodec extends ArrayCodec {
    private static final ShortArrayCodec INSTANCE = new ShortArrayCodec();

    private ShortArrayCodec() {
      super(short.class);
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      short[] array = (short[]) value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        writer.writeInt(array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      reader.expect('[');
      if (reader.consume(']')) {
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
        values[size++] = readShort(reader.readInt());
      } while (reader.consume(','));
      reader.expect(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class ByteArrayCodec extends ArrayCodec {
    private static final ByteArrayCodec INSTANCE = new ByteArrayCodec();

    private ByteArrayCodec() {
      super(byte.class);
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      byte[] array = (byte[]) value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        writer.writeInt(array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      reader.expect('[');
      if (reader.consume(']')) {
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
        values[size++] = readByte(reader.readInt());
      } while (reader.consume(','));
      reader.expect(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class CharArrayCodec extends ArrayCodec {
    private static final CharArrayCodec INSTANCE = new CharArrayCodec();

    private CharArrayCodec() {
      super(char.class);
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      char[] array = (char[]) value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        writer.writeChar(array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      reader.expect('[');
      if (reader.consume(']')) {
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
        values[size++] = readChar(reader);
      } while (reader.consume(','));
      reader.expect(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class FloatArrayCodec extends ArrayCodec {
    private static final FloatArrayCodec INSTANCE = new FloatArrayCodec();

    private FloatArrayCodec() {
      super(float.class);
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      float[] array = (float[]) value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        writer.writeFloat(array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      reader.expect('[');
      if (reader.consume(']')) {
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
        values[size++] = reader.readFloat();
      } while (reader.consume(','));
      reader.expect(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class DoubleArrayCodec extends ArrayCodec {
    private static final DoubleArrayCodec INSTANCE = new DoubleArrayCodec();

    private DoubleArrayCodec() {
      super(double.class);
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      double[] array = (double[]) value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        writer.writeDouble(array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      reader.expect('[');
      if (reader.consume(']')) {
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
        values[size++] = reader.readDouble();
      } while (reader.consume(','));
      reader.expect(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class StringArrayCodec extends ArrayCodec {
    private static final StringArrayCodec INSTANCE = new StringArrayCodec();

    private StringArrayCodec() {
      super(String.class);
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      String[] array = (String[]) value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        if (array[i] == null) {
          writer.writeNull();
        } else {
          writer.writeString(array[i]);
        }
      }
      writer.writeArrayEnd();
    }

    @Override
    void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      String[] array = (String[]) value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeStringElement(i, array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      String[] array = (String[]) value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeStringElement(i, array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      reader.expect('[');
      if (reader.consume(']')) {
        reader.exitDepth();
        return new String[0];
      }
      String[] values = new String[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readNullableString();
      } while (reader.consume(','));
      reader.expect(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    @Override
    public Object readLatin1(
        Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
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
    public Object readUtf16(
        Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
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
    public Object readUtf8(
        Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
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

  public static final class ObjectArrayCodec extends ArrayCodec {
    private static final int VALUES_CACHE_DEPTH = 8;
    private static final int INITIAL_VALUES_SIZE = 8;
    private static final int MAX_CACHED_VALUES_SIZE = 1024;

    private final JsonTypeInfo elementTypeInfo;
    private JsonCodec elementCodec;
    // Recursive object-array reads borrow one scratch slot per active depth.
    private final Object[][] valuesCache = new Object[VALUES_CACHE_DEPTH][];
    private int valuesDepth;

    private ObjectArrayCodec(
        Class<?> componentType, JsonTypeInfo elementTypeInfo, JsonTypeResolver resolver) {
      super(componentType);
      this.elementTypeInfo = elementTypeInfo;
      elementCodec = elementTypeInfo.codec();
      resolver.registerJITNotifyCallback(elementCodec, codec -> elementCodec = codec);
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      Object[] array = (Object[]) value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        elementCodec.write(writer, array[i], resolver);
      }
      writer.writeArrayEnd();
    }

    @Override
    void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      Object[] array = (Object[]) value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        elementCodec.writeString(writer, array[i], resolver);
      }
      writer.writeArrayEnd();
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      Object[] array = (Object[]) value;
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        elementCodec.writeUtf8(writer, array[i], resolver);
      }
      writer.writeArrayEnd();
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
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
      try {
        reader.expect('[');
        if (!reader.consume(']')) {
          do {
            if (size == values.length) {
              values = Arrays.copyOf(values, values.length << 1);
            }
            values[size++] = elementCodec.read(reader, elementTypeInfo, resolver);
          } while (reader.consume(','));
          reader.expect(']');
        }
        Object[] array = (Object[]) Array.newInstance(componentType, size);
        System.arraycopy(values, 0, array, 0, size);
        success = true;
        return array;
      } finally {
        reader.exitDepth();
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
  }

  public static final class BoxedIntArrayCodec extends ArrayCodec {
    private static final BoxedIntArrayCodec INSTANCE = new BoxedIntArrayCodec();

    private BoxedIntArrayCodec() {
      super(Integer.class);
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      Integer[] array = (Integer[]) value;
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
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      reader.expect('[');
      if (reader.consume(']')) {
        reader.exitDepth();
        return new Integer[0];
      }
      Integer[] values = new Integer[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.tryReadNull() ? null : reader.readInt();
      } while (reader.consume(','));
      reader.expect(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class BoxedLongArrayCodec extends ArrayCodec {
    private static final BoxedLongArrayCodec INSTANCE = new BoxedLongArrayCodec();

    private BoxedLongArrayCodec() {
      super(Long.class);
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      Long[] array = (Long[]) value;
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
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      reader.expect('[');
      if (reader.consume(']')) {
        reader.exitDepth();
        return new Long[0];
      }
      Long[] values = new Long[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.tryReadNull() ? null : reader.readLong();
      } while (reader.consume(','));
      reader.expect(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class BoxedBooleanArrayCodec extends ArrayCodec {
    private static final BoxedBooleanArrayCodec INSTANCE = new BoxedBooleanArrayCodec();

    private BoxedBooleanArrayCodec() {
      super(Boolean.class);
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      Boolean[] array = (Boolean[]) value;
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
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      reader.expect('[');
      if (reader.consume(']')) {
        reader.exitDepth();
        return new Boolean[0];
      }
      Boolean[] values = new Boolean[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.tryReadNull() ? null : reader.readBoolean();
      } while (reader.consume(','));
      reader.expect(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class BoxedShortArrayCodec extends ArrayCodec {
    private static final BoxedShortArrayCodec INSTANCE = new BoxedShortArrayCodec();

    private BoxedShortArrayCodec() {
      super(Short.class);
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      Short[] array = (Short[]) value;
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
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      reader.expect('[');
      if (reader.consume(']')) {
        reader.exitDepth();
        return new Short[0];
      }
      Short[] values = new Short[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.tryReadNull() ? null : readShort(reader.readInt());
      } while (reader.consume(','));
      reader.expect(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class BoxedByteArrayCodec extends ArrayCodec {
    private static final BoxedByteArrayCodec INSTANCE = new BoxedByteArrayCodec();

    private BoxedByteArrayCodec() {
      super(Byte.class);
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      Byte[] array = (Byte[]) value;
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
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      reader.expect('[');
      if (reader.consume(']')) {
        reader.exitDepth();
        return new Byte[0];
      }
      Byte[] values = new Byte[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.tryReadNull() ? null : readByte(reader.readInt());
      } while (reader.consume(','));
      reader.expect(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class BoxedCharArrayCodec extends ArrayCodec {
    private static final BoxedCharArrayCodec INSTANCE = new BoxedCharArrayCodec();

    private BoxedCharArrayCodec() {
      super(Character.class);
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      Character[] array = (Character[]) value;
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
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      reader.expect('[');
      if (reader.consume(']')) {
        reader.exitDepth();
        return new Character[0];
      }
      Character[] values = new Character[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        String value = reader.readNullableString();
        values[size++] = value == null ? null : readChar(value);
      } while (reader.consume(','));
      reader.expect(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class BoxedFloatArrayCodec extends ArrayCodec {
    private static final BoxedFloatArrayCodec INSTANCE = new BoxedFloatArrayCodec();

    private BoxedFloatArrayCodec() {
      super(Float.class);
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      Float[] array = (Float[]) value;
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
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      reader.expect('[');
      if (reader.consume(']')) {
        reader.exitDepth();
        return new Float[0];
      }
      Float[] values = new Float[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.tryReadNull() ? null : reader.readFloat();
      } while (reader.consume(','));
      reader.expect(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class BoxedDoubleArrayCodec extends ArrayCodec {
    private static final BoxedDoubleArrayCodec INSTANCE = new BoxedDoubleArrayCodec();

    private BoxedDoubleArrayCodec() {
      super(Double.class);
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      Double[] array = (Double[]) value;
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
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      reader.expect('[');
      if (reader.consume(']')) {
        reader.exitDepth();
        return new Double[0];
      }
      Double[] values = new Double[8];
      int size = 0;
      do {
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.tryReadNull() ? null : reader.readDouble();
      } while (reader.consume(','));
      reader.expect(']');
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

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
import org.apache.fory.annotation.Internal;
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
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.serializer.GraphMemoryEstimates;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.type.Types;

/**
 * Codec family for Java primitive, boxed, String, and object arrays.
 *
 * <p>The factory resolves the component binding once and selects a direct specialized codec when
 * the exact built-in component codec is active. Custom primitive codecs and reference components
 * retain one resolved {@link JsonTypeInfo}; array loops load the concrete capability once per root
 * operation rather than resolving a codec per element. Each concrete reader owns array allocation
 * and growth because JSON carries no trusted element count.
 */
public abstract class ArrayCodec<T> implements JsonValueCodec<T> {
  private static final int REFERENCE_BYTES = GraphMemoryEstimates.REFERENCE_BYTES;
  private static final int ARRAY_HEADER_BYTES = GraphMemoryEstimates.objectArrayBytes();
  private static final int ARRAY_BATCH_SIZE = 1024;
  private static final int ARRAY_BATCH_MASK = ARRAY_BATCH_SIZE - 1;
  private static final int REFERENCE_BATCH_BYTES = ARRAY_BATCH_SIZE * REFERENCE_BYTES;
  final Class<?> componentType;

  ArrayCodec(Class<?> componentType) {
    this.componentType = componentType;
  }

  public static <T> JsonValueCodec<T> create(
      Class<T> arrayType, TypeRef<?> arrayTypeRef, JsonTypeResolver resolver) {
    if (!arrayType.isArray()) {
      throw new ForyJsonException("Unsupported JSON array type " + arrayType);
    }
    Class<?> componentType = arrayType.getComponentType();
    TypeRef<?> componentTypeRef = arrayTypeRef.getComponentType();
    JsonTypeInfo componentTypeInfo = resolver.getTypeInfo(componentTypeRef);
    return create(arrayType, componentTypeInfo);
  }

  @Internal
  public static <T> JsonValueCodec<T> create(Class<T> arrayType, JsonTypeInfo componentTypeInfo) {
    if (!arrayType.isArray()) {
      throw new ForyJsonException("Unsupported JSON array type " + arrayType);
    }
    Class<?> componentType = arrayType.getComponentType();
    Object componentCodec = componentTypeInfo.stringWriter();
    if (componentType == int.class && componentCodec == ScalarCodecs.IntCodec.PRIMITIVE) {
      return bind(IntArrayCodec.INSTANCE);
    } else if (componentType == long.class && componentCodec == ScalarCodecs.LongCodec.PRIMITIVE) {
      return bind(LongArrayCodec.INSTANCE);
    } else if (componentType == long.class
        && componentCodec == ScalarCodecs.LongAsStringCodec.PRIMITIVE) {
      return bind(LongAsStringArrayCodec.INSTANCE);
    } else if (componentType == boolean.class
        && componentCodec == ScalarCodecs.BooleanCodec.PRIMITIVE) {
      return bind(BooleanArrayCodec.INSTANCE);
    } else if (componentType == short.class
        && componentCodec == ScalarCodecs.ShortCodec.PRIMITIVE) {
      return bind(ShortArrayCodec.INSTANCE);
    } else if (componentType == byte.class && componentCodec == ScalarCodecs.ByteCodec.PRIMITIVE) {
      return bind(Base64ByteArrayCodec.INSTANCE);
    } else if (componentType == char.class && componentCodec == ScalarCodecs.CharCodec.PRIMITIVE) {
      return bind(CharArrayCodec.INSTANCE);
    } else if (componentType == float.class
        && componentCodec == ScalarCodecs.FloatCodec.PRIMITIVE) {
      return bind(FloatArrayCodec.INSTANCE);
    } else if (componentType == double.class
        && componentCodec == ScalarCodecs.DoubleCodec.PRIMITIVE) {
      return bind(DoubleArrayCodec.INSTANCE);
    } else if (componentType == Integer.class && componentCodec == ScalarCodecs.IntCodec.BOXED) {
      return bind(
          componentTypeInfo.rejectsNull()
              ? BoxedIntArrayCodec.NON_NULL
              : BoxedIntArrayCodec.INSTANCE);
    } else if (componentType == Long.class && componentCodec == ScalarCodecs.LongCodec.BOXED) {
      return bind(
          componentTypeInfo.rejectsNull()
              ? BoxedLongArrayCodec.NON_NULL
              : BoxedLongArrayCodec.INSTANCE);
    } else if (componentType == Long.class
        && componentCodec == ScalarCodecs.LongAsStringCodec.BOXED) {
      return bind(
          componentTypeInfo.rejectsNull()
              ? BoxedLongAsStringArrayCodec.NON_NULL
              : BoxedLongAsStringArrayCodec.INSTANCE);
    } else if (componentType == Boolean.class
        && componentCodec == ScalarCodecs.BooleanCodec.BOXED) {
      return bind(
          componentTypeInfo.rejectsNull()
              ? BoxedBooleanArrayCodec.NON_NULL
              : BoxedBooleanArrayCodec.INSTANCE);
    } else if (componentType == Short.class && componentCodec == ScalarCodecs.ShortCodec.BOXED) {
      return bind(
          componentTypeInfo.rejectsNull()
              ? BoxedShortArrayCodec.NON_NULL
              : BoxedShortArrayCodec.INSTANCE);
    } else if (componentType == Byte.class && componentCodec == ScalarCodecs.ByteCodec.BOXED) {
      return bind(
          componentTypeInfo.rejectsNull()
              ? BoxedByteArrayCodec.NON_NULL
              : BoxedByteArrayCodec.INSTANCE);
    } else if (componentType == Character.class && componentCodec == ScalarCodecs.CharCodec.BOXED) {
      return bind(
          componentTypeInfo.rejectsNull()
              ? BoxedCharArrayCodec.NON_NULL
              : BoxedCharArrayCodec.INSTANCE);
    } else if (componentType == Float.class && componentCodec == ScalarCodecs.FloatCodec.BOXED) {
      return bind(
          componentTypeInfo.rejectsNull()
              ? BoxedFloatArrayCodec.NON_NULL
              : BoxedFloatArrayCodec.INSTANCE);
    } else if (componentType == Double.class && componentCodec == ScalarCodecs.DoubleCodec.BOXED) {
      return bind(
          componentTypeInfo.rejectsNull()
              ? BoxedDoubleArrayCodec.NON_NULL
              : BoxedDoubleArrayCodec.INSTANCE);
    } else if (componentType == String.class
        && componentCodec == ScalarCodecs.StringCodec.INSTANCE) {
      return bind(
          componentTypeInfo.rejectsNull() ? StringArrayCodec.NON_NULL : StringArrayCodec.INSTANCE);
    }
    if (componentType.isPrimitive()) {
      return new CustomPrimitiveArrayCodec<>(componentType, componentTypeInfo);
    }
    return componentTypeInfo.rejectsNull()
        ? new NonNullObjectArrayCodec<>(componentType, componentTypeInfo)
        : new ObjectArrayCodec<>(componentType, componentTypeInfo);
  }

  /** Returns the exact unsigned primitive-array specialization for one semantic array id. */
  @Internal
  public static <T> JsonValueCodec<T> createUnsignedPrimitive(
      Class<T> arrayType, int typeId, boolean writeLongAsString) {
    if (arrayType == byte[].class && typeId == Types.UINT8_ARRAY) {
      return bind(ByteArrayCodec.UNSIGNED);
    }
    if (arrayType == short[].class && typeId == Types.UINT16_ARRAY) {
      return bind(ShortArrayCodec.UNSIGNED);
    }
    if (arrayType == int[].class && typeId == Types.UINT32_ARRAY) {
      return bind(IntArrayCodec.UNSIGNED);
    }
    if (arrayType == long[].class && typeId == Types.UINT64_ARRAY) {
      return bind(
          writeLongAsString
              ? UnsignedLongAsStringArrayCodec.INSTANCE
              : UnsignedLongArrayCodec.INSTANCE);
    }
    throw new ForyJsonException(
        "Unsigned JSON array semantic id " + typeId + " does not match " + arrayType.getName());
  }

  @SuppressWarnings("unchecked")
  private static <T> JsonValueCodec<T> bind(JsonValueCodec<?> codec) {
    // The factory has matched the runtime array class to this exact singleton implementation.
    return (JsonValueCodec<T>) codec;
  }

  // Package visibility lets Java 8 nested codecs call these helpers without synthetic accessors.
  static void reserveReferenceBatch(JsonReader reader, int size) {
    // Reserve each batch before reading its final element. This bounds unreserved reference storage
    // to 1023 slots without adding a budget call for every array element.
    if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
      reader.reserveGraphMemory(REFERENCE_BATCH_BYTES);
    }
  }

  static void finishReferenceArray(JsonReader reader, int size) {
    int tailSize = size & ARRAY_BATCH_MASK;
    reader.reserveGraphMemory(ARRAY_HEADER_BYTES + tailSize * REFERENCE_BYTES);
    reader.exitDepth();
  }

  static void finishPrimitiveArray(JsonReader reader, int size, int elementBytes) {
    int tailSize = size & ARRAY_BATCH_MASK;
    reader.reserveGraphMemory(ARRAY_HEADER_BYTES + tailSize * elementBytes);
    reader.exitDepth();
  }

  // Byte, short, and int signed/unsigned codecs intentionally share these exact element hooks.
  // Their representation-specific reader call sites remain within the measured performance gate;
  // duplicate complete loops only when a concrete C2 regression proves that ownership necessary.
  public abstract static class IntArrayCodec extends ArrayCodec<int[]> {
    private static final IntArrayCodec INSTANCE = new SignedIntArrayCodec();
    private static final IntArrayCodec UNSIGNED = new UnsignedIntArrayCodec();

    private IntArrayCodec() {
      super(int.class);
    }

    abstract void writeElement(StringJsonWriter writer, int value);

    abstract void writeElement(Utf8JsonWriter writer, int value);

    abstract int readElement(Latin1JsonReader reader);

    abstract int readElement(Utf16JsonReader reader);

    abstract int readElement(Utf8JsonReader reader);

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
        writeElement(writer, array[i]);
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
        writeElement(writer, array[i]);
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
        finishPrimitiveArray(reader, 0, Integer.BYTES);
        return new int[0];
      }
      int[] values = new int[8];
      int size = 0;
      do {
        rejectNull(reader);
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * Integer.BYTES);
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = readElement(reader);
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishPrimitiveArray(reader, size, Integer.BYTES);
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
        finishPrimitiveArray(reader, 0, Integer.BYTES);
        return new int[0];
      }
      int[] values = new int[8];
      int size = 0;
      do {
        rejectNull(reader);
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * Integer.BYTES);
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = readElement(reader);
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishPrimitiveArray(reader, size, Integer.BYTES);
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
        finishPrimitiveArray(reader, 0, Integer.BYTES);
        return new int[0];
      }
      int[] values = new int[8];
      int size = 0;
      do {
        rejectNull(reader);
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * Integer.BYTES);
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = readElement(reader);
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishPrimitiveArray(reader, size, Integer.BYTES);
      return Arrays.copyOf(values, size);
    }
  }

  private static final class SignedIntArrayCodec extends IntArrayCodec {
    @Override
    void writeElement(StringJsonWriter writer, int value) {
      writer.writeInt(value);
    }

    @Override
    void writeElement(Utf8JsonWriter writer, int value) {
      writer.writeInt(value);
    }

    @Override
    int readElement(Latin1JsonReader reader) {
      return reader.readIntValue();
    }

    @Override
    int readElement(Utf16JsonReader reader) {
      return reader.readIntValue();
    }

    @Override
    int readElement(Utf8JsonReader reader) {
      return reader.readIntValue();
    }
  }

  private static final class UnsignedIntArrayCodec extends IntArrayCodec {
    @Override
    void writeElement(StringJsonWriter writer, int value) {
      writer.writeUnsignedInt(value);
    }

    @Override
    void writeElement(Utf8JsonWriter writer, int value) {
      writer.writeUnsignedInt(value);
    }

    @Override
    int readElement(Latin1JsonReader reader) {
      return reader.readUnsignedInt();
    }

    @Override
    int readElement(Utf16JsonReader reader) {
      return reader.readUnsignedInt();
    }

    @Override
    int readElement(Utf8JsonReader reader) {
      return reader.readUnsignedInt();
    }
  }

  // Keep the signed codec as the exact final loop owner. Routing signed long arrays through a
  // semantic element hook makes C2 inline the root and array loop into one unstable graph; the
  // unsigned codec below owns separate primitive operations so neither warmed path branches.
  public static class LongArrayCodec extends ArrayCodec<long[]> {
    private static final LongArrayCodec INSTANCE = new LongArrayCodec();

    protected LongArrayCodec() {
      super(long.class);
    }

    private static void finishArray(JsonReader reader, int size) {
      finishPrimitiveArray(reader, size, Long.BYTES);
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
        finishArray(reader, 0);
        return new long[0];
      }
      rejectNull(reader);
      long v0 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishArray(reader, 1);
        return new long[] {v0};
      }
      rejectNull(reader);
      long v1 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishArray(reader, 2);
        return new long[] {v0, v1};
      }
      rejectNull(reader);
      long v2 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishArray(reader, 3);
        return new long[] {v0, v1, v2};
      }
      rejectNull(reader);
      long v3 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishArray(reader, 4);
        return new long[] {v0, v1, v2, v3};
      }
      return readLatin1Tail(reader, v0, v1, v2, v3);
    }

    private long[] readLatin1Tail(Latin1JsonReader reader, long v0, long v1, long v2, long v3) {
      rejectNull(reader);
      long v4 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishArray(reader, 5);
        return new long[] {v0, v1, v2, v3, v4};
      }
      rejectNull(reader);
      long v5 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishArray(reader, 6);
        return new long[] {v0, v1, v2, v3, v4, v5};
      }
      rejectNull(reader);
      long v6 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishArray(reader, 7);
        return new long[] {v0, v1, v2, v3, v4, v5, v6};
      }
      rejectNull(reader);
      long v7 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishArray(reader, 8);
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
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * Long.BYTES);
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readLongValue();
      } while (reader.consumeNextCommaOrEndArray());
      finishArray(reader, size);
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
        finishArray(reader, 0);
        return new long[0];
      }
      rejectNull(reader);
      long v0 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishArray(reader, 1);
        return new long[] {v0};
      }
      rejectNull(reader);
      long v1 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishArray(reader, 2);
        return new long[] {v0, v1};
      }
      rejectNull(reader);
      long v2 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishArray(reader, 3);
        return new long[] {v0, v1, v2};
      }
      rejectNull(reader);
      long v3 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishArray(reader, 4);
        return new long[] {v0, v1, v2, v3};
      }
      return readUtf16Tail(reader, v0, v1, v2, v3);
    }

    private long[] readUtf16Tail(Utf16JsonReader reader, long v0, long v1, long v2, long v3) {
      rejectNull(reader);
      long v4 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishArray(reader, 5);
        return new long[] {v0, v1, v2, v3, v4};
      }
      rejectNull(reader);
      long v5 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishArray(reader, 6);
        return new long[] {v0, v1, v2, v3, v4, v5};
      }
      rejectNull(reader);
      long v6 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishArray(reader, 7);
        return new long[] {v0, v1, v2, v3, v4, v5, v6};
      }
      rejectNull(reader);
      long v7 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishArray(reader, 8);
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
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * Long.BYTES);
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readLongValue();
      } while (reader.consumeNextCommaOrEndArray());
      finishArray(reader, size);
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
        finishArray(reader, 0);
        return new long[0];
      }
      rejectNull(reader);
      long v0 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishArray(reader, 1);
        return new long[] {v0};
      }
      rejectNull(reader);
      long v1 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishArray(reader, 2);
        return new long[] {v0, v1};
      }
      rejectNull(reader);
      long v2 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishArray(reader, 3);
        return new long[] {v0, v1, v2};
      }
      rejectNull(reader);
      long v3 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishArray(reader, 4);
        return new long[] {v0, v1, v2, v3};
      }
      return readUtf8Tail(reader, v0, v1, v2, v3);
    }

    private long[] readUtf8Tail(Utf8JsonReader reader, long v0, long v1, long v2, long v3) {
      rejectNull(reader);
      long v4 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishArray(reader, 5);
        return new long[] {v0, v1, v2, v3, v4};
      }
      rejectNull(reader);
      long v5 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishArray(reader, 6);
        return new long[] {v0, v1, v2, v3, v4, v5};
      }
      rejectNull(reader);
      long v6 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishArray(reader, 7);
        return new long[] {v0, v1, v2, v3, v4, v5, v6};
      }
      rejectNull(reader);
      long v7 = reader.readLongValue();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishArray(reader, 8);
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
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * Long.BYTES);
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readLongValue();
      } while (reader.consumeNextCommaOrEndArray());
      finishArray(reader, size);
      return Arrays.copyOf(values, size);
    }
  }

  /** Exact signed long-array specialization which writes each element as a JSON string. */
  public static final class LongAsStringArrayCodec extends LongArrayCodec {
    private static final LongAsStringArrayCodec INSTANCE = new LongAsStringArrayCodec();

    private LongAsStringArrayCodec() {}

    @Override
    public void writeString(StringJsonWriter writer, long[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      writer.writeArrayStart();
      for (int i = 0; i < value.length; i++) {
        writer.writeComma(i);
        writer.writeLongAsString(value[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, long[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      writer.writeArrayStart();
      for (int i = 0; i < value.length; i++) {
        writer.writeComma(i);
        writer.writeLongAsString(value[i]);
      }
      writer.writeArrayEnd();
    }
  }

  private static class UnsignedLongArrayCodec extends ArrayCodec<long[]> {
    private static final UnsignedLongArrayCodec INSTANCE = new UnsignedLongArrayCodec();

    private UnsignedLongArrayCodec() {
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
        writer.writeUnsignedLong(array[i]);
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
        writer.writeUnsignedLong(array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    public long[] readLatin1(Latin1JsonReader reader) {
      return readUnsignedArray(reader);
    }

    @Override
    public long[] readUtf16(Utf16JsonReader reader) {
      return readUnsignedArray(reader);
    }

    @Override
    public long[] readUtf8(Utf8JsonReader reader) {
      return readUnsignedArray(reader);
    }

    private long[] readUnsignedArray(JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        finishPrimitiveArray(reader, 0, Long.BYTES);
        return new long[0];
      }
      rejectNull(reader);
      long v0 = reader.readUnsignedLong();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishPrimitiveArray(reader, 1, Long.BYTES);
        return new long[] {v0};
      }
      rejectNull(reader);
      long v1 = reader.readUnsignedLong();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishPrimitiveArray(reader, 2, Long.BYTES);
        return new long[] {v0, v1};
      }
      rejectNull(reader);
      long v2 = reader.readUnsignedLong();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishPrimitiveArray(reader, 3, Long.BYTES);
        return new long[] {v0, v1, v2};
      }
      rejectNull(reader);
      long v3 = reader.readUnsignedLong();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishPrimitiveArray(reader, 4, Long.BYTES);
        return new long[] {v0, v1, v2, v3};
      }
      rejectNull(reader);
      long v4 = reader.readUnsignedLong();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishPrimitiveArray(reader, 5, Long.BYTES);
        return new long[] {v0, v1, v2, v3, v4};
      }
      rejectNull(reader);
      long v5 = reader.readUnsignedLong();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishPrimitiveArray(reader, 6, Long.BYTES);
        return new long[] {v0, v1, v2, v3, v4, v5};
      }
      rejectNull(reader);
      long v6 = reader.readUnsignedLong();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishPrimitiveArray(reader, 7, Long.BYTES);
        return new long[] {v0, v1, v2, v3, v4, v5, v6};
      }
      rejectNull(reader);
      long v7 = reader.readUnsignedLong();
      if (!reader.consumeNextCommaOrEndArray()) {
        finishPrimitiveArray(reader, 8, Long.BYTES);
        return new long[] {v0, v1, v2, v3, v4, v5, v6, v7};
      }
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
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * Long.BYTES);
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readUnsignedLong();
      } while (reader.consumeNextCommaOrEndArray());
      finishPrimitiveArray(reader, size, Long.BYTES);
      return Arrays.copyOf(values, size);
    }
  }

  private static final class UnsignedLongAsStringArrayCodec extends UnsignedLongArrayCodec {
    private static final UnsignedLongAsStringArrayCodec INSTANCE =
        new UnsignedLongAsStringArrayCodec();

    private UnsignedLongAsStringArrayCodec() {}

    @Override
    public void writeString(StringJsonWriter writer, long[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      writer.writeArrayStart();
      for (int i = 0; i < value.length; i++) {
        writer.writeComma(i);
        writer.writeUnsignedLongAsString(value[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, long[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      writer.writeArrayStart();
      for (int i = 0; i < value.length; i++) {
        writer.writeComma(i);
        writer.writeUnsignedLongAsString(value[i]);
      }
      writer.writeArrayEnd();
    }
  }

  public static final class BooleanArrayCodec extends ArrayCodec<boolean[]> {
    private static final BooleanArrayCodec INSTANCE = new BooleanArrayCodec();
    private static final int ELEMENT_BYTES = 1;

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
        finishPrimitiveArray(reader, 0, ELEMENT_BYTES);
        return new boolean[0];
      }
      boolean[] values = new boolean[8];
      int size = 0;
      do {
        rejectNull(reader);
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * ELEMENT_BYTES);
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readBooleanValue();
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishPrimitiveArray(reader, size, ELEMENT_BYTES);
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
        finishPrimitiveArray(reader, 0, ELEMENT_BYTES);
        return new boolean[0];
      }
      boolean[] values = new boolean[8];
      int size = 0;
      do {
        rejectNull(reader);
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * ELEMENT_BYTES);
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readBooleanValue();
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishPrimitiveArray(reader, size, ELEMENT_BYTES);
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
        finishPrimitiveArray(reader, 0, ELEMENT_BYTES);
        return new boolean[0];
      }
      boolean[] values = new boolean[8];
      int size = 0;
      do {
        rejectNull(reader);
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * ELEMENT_BYTES);
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readBooleanValue();
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishPrimitiveArray(reader, size, ELEMENT_BYTES);
      return Arrays.copyOf(values, size);
    }
  }

  public abstract static class ShortArrayCodec extends ArrayCodec<short[]> {
    private static final ShortArrayCodec INSTANCE = new SignedShortArrayCodec();
    private static final ShortArrayCodec UNSIGNED = new UnsignedShortArrayCodec();

    private ShortArrayCodec() {
      super(short.class);
    }

    abstract void writeElement(StringJsonWriter writer, short value);

    abstract void writeElement(Utf8JsonWriter writer, short value);

    abstract short readElement(Latin1JsonReader reader);

    abstract short readElement(Utf16JsonReader reader);

    abstract short readElement(Utf8JsonReader reader);

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
        writeElement(writer, array[i]);
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
        writeElement(writer, array[i]);
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
        finishPrimitiveArray(reader, 0, Short.BYTES);
        return new short[0];
      }
      short[] values = new short[8];
      int size = 0;
      do {
        rejectNull(reader);
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * Short.BYTES);
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = readElement(reader);
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishPrimitiveArray(reader, size, Short.BYTES);
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
        finishPrimitiveArray(reader, 0, Short.BYTES);
        return new short[0];
      }
      short[] values = new short[8];
      int size = 0;
      do {
        rejectNull(reader);
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * Short.BYTES);
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = readElement(reader);
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishPrimitiveArray(reader, size, Short.BYTES);
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
        finishPrimitiveArray(reader, 0, Short.BYTES);
        return new short[0];
      }
      short[] values = new short[8];
      int size = 0;
      do {
        rejectNull(reader);
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * Short.BYTES);
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = readElement(reader);
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishPrimitiveArray(reader, size, Short.BYTES);
      return Arrays.copyOf(values, size);
    }
  }

  private static final class SignedShortArrayCodec extends ShortArrayCodec {
    @Override
    void writeElement(StringJsonWriter writer, short value) {
      writer.writeInt(value);
    }

    @Override
    void writeElement(Utf8JsonWriter writer, short value) {
      writer.writeInt(value);
    }

    @Override
    short readElement(Latin1JsonReader reader) {
      return readShort(reader.readIntValue());
    }

    @Override
    short readElement(Utf16JsonReader reader) {
      return readShort(reader.readIntValue());
    }

    @Override
    short readElement(Utf8JsonReader reader) {
      return readShort(reader.readIntValue());
    }
  }

  private static final class UnsignedShortArrayCodec extends ShortArrayCodec {
    @Override
    void writeElement(StringJsonWriter writer, short value) {
      writer.writeInt(Short.toUnsignedInt(value));
    }

    @Override
    void writeElement(Utf8JsonWriter writer, short value) {
      writer.writeInt(Short.toUnsignedInt(value));
    }

    @Override
    short readElement(Latin1JsonReader reader) {
      return readUnsignedShort(reader.readUnsignedInt());
    }

    @Override
    short readElement(Utf16JsonReader reader) {
      return readUnsignedShort(reader.readUnsignedInt());
    }

    @Override
    short readElement(Utf8JsonReader reader) {
      return readUnsignedShort(reader.readUnsignedInt());
    }
  }

  public abstract static class ByteArrayCodec extends ArrayCodec<byte[]> {
    private static final ByteArrayCodec UNSIGNED = new UnsignedByteArrayCodec();

    private ByteArrayCodec() {
      super(byte.class);
    }

    abstract void writeElement(StringJsonWriter writer, byte value);

    abstract void writeElement(Utf8JsonWriter writer, byte value);

    abstract byte readElement(Latin1JsonReader reader);

    abstract byte readElement(Utf16JsonReader reader);

    abstract byte readElement(Utf8JsonReader reader);

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
        writeElement(writer, array[i]);
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
        writeElement(writer, array[i]);
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
        finishPrimitiveArray(reader, 0, Byte.BYTES);
        return new byte[0];
      }
      byte[] values = new byte[8];
      int size = 0;
      do {
        rejectNull(reader);
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * Byte.BYTES);
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = readElement(reader);
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishPrimitiveArray(reader, size, Byte.BYTES);
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
        finishPrimitiveArray(reader, 0, Byte.BYTES);
        return new byte[0];
      }
      byte[] values = new byte[8];
      int size = 0;
      do {
        rejectNull(reader);
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * Byte.BYTES);
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = readElement(reader);
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishPrimitiveArray(reader, size, Byte.BYTES);
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
        finishPrimitiveArray(reader, 0, Byte.BYTES);
        return new byte[0];
      }
      byte[] values = new byte[8];
      int size = 0;
      do {
        rejectNull(reader);
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * Byte.BYTES);
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = readElement(reader);
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishPrimitiveArray(reader, size, Byte.BYTES);
      return Arrays.copyOf(values, size);
    }
  }

  /** A complete {@code byte[]} codec using a JSON array of signed byte values. */
  public static final class SignedByteArrayCodec extends ByteArrayCodec {
    public SignedByteArrayCodec() {}

    @Override
    void writeElement(StringJsonWriter writer, byte value) {
      writer.writeInt(value);
    }

    @Override
    void writeElement(Utf8JsonWriter writer, byte value) {
      writer.writeInt(value);
    }

    @Override
    byte readElement(Latin1JsonReader reader) {
      return readByte(reader.readIntValue());
    }

    @Override
    byte readElement(Utf16JsonReader reader) {
      return readByte(reader.readIntValue());
    }

    @Override
    byte readElement(Utf8JsonReader reader) {
      return readByte(reader.readIntValue());
    }
  }

  private static final class UnsignedByteArrayCodec extends ByteArrayCodec {
    @Override
    void writeElement(StringJsonWriter writer, byte value) {
      writer.writeInt(Byte.toUnsignedInt(value));
    }

    @Override
    void writeElement(Utf8JsonWriter writer, byte value) {
      writer.writeInt(Byte.toUnsignedInt(value));
    }

    @Override
    byte readElement(Latin1JsonReader reader) {
      return readUnsignedByte(reader.readUnsignedInt());
    }

    @Override
    byte readElement(Utf16JsonReader reader) {
      return readUnsignedByte(reader.readUnsignedInt());
    }

    @Override
    byte readElement(Utf8JsonReader reader) {
      return readUnsignedByte(reader.readUnsignedInt());
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
        finishPrimitiveArray(reader, 0, Character.BYTES);
        return new char[0];
      }
      char[] values = new char[8];
      int size = 0;
      do {
        rejectNull(reader);
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * Character.BYTES);
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = readChar(reader.readString());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishPrimitiveArray(reader, size, Character.BYTES);
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
        finishPrimitiveArray(reader, 0, Character.BYTES);
        return new char[0];
      }
      char[] values = new char[8];
      int size = 0;
      do {
        rejectNull(reader);
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * Character.BYTES);
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = readChar(reader.readString());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishPrimitiveArray(reader, size, Character.BYTES);
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
        finishPrimitiveArray(reader, 0, Character.BYTES);
        return new char[0];
      }
      char[] values = new char[8];
      int size = 0;
      do {
        rejectNull(reader);
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * Character.BYTES);
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = readChar(reader.readString());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishPrimitiveArray(reader, size, Character.BYTES);
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
        finishPrimitiveArray(reader, 0, Float.BYTES);
        return new float[0];
      }
      float[] values = new float[8];
      int size = 0;
      do {
        rejectNull(reader);
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * Float.BYTES);
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readNextFloatValue();
      } while (reader.consumeNextCommaOrEndArray());
      finishPrimitiveArray(reader, size, Float.BYTES);
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
        finishPrimitiveArray(reader, 0, Float.BYTES);
        return new float[0];
      }
      float[] values = new float[8];
      int size = 0;
      do {
        rejectNull(reader);
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * Float.BYTES);
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readNextFloatValue();
      } while (reader.consumeNextCommaOrEndArray());
      finishPrimitiveArray(reader, size, Float.BYTES);
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
        finishPrimitiveArray(reader, 0, Float.BYTES);
        return new float[0];
      }
      float[] values = new float[8];
      int size = 0;
      do {
        rejectNull(reader);
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * Float.BYTES);
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readNextFloatValue();
      } while (reader.consumeNextCommaOrEndArray());
      finishPrimitiveArray(reader, size, Float.BYTES);
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
        finishPrimitiveArray(reader, 0, Double.BYTES);
        return new double[0];
      }
      double[] values = new double[8];
      int size = 0;
      do {
        rejectNull(reader);
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * Double.BYTES);
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readNextDoubleValue();
      } while (reader.consumeNextCommaOrEndArray());
      finishPrimitiveArray(reader, size, Double.BYTES);
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
        finishPrimitiveArray(reader, 0, Double.BYTES);
        return new double[0];
      }
      double[] values = new double[8];
      int size = 0;
      do {
        rejectNull(reader);
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * Double.BYTES);
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readNextDoubleValue();
      } while (reader.consumeNextCommaOrEndArray());
      finishPrimitiveArray(reader, size, Double.BYTES);
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
        finishPrimitiveArray(reader, 0, Double.BYTES);
        return new double[0];
      }
      double[] values = new double[8];
      int size = 0;
      do {
        rejectNull(reader);
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * Double.BYTES);
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readNextDoubleValue();
      } while (reader.consumeNextCommaOrEndArray());
      finishPrimitiveArray(reader, size, Double.BYTES);
      return Arrays.copyOf(values, size);
    }
  }

  public static final class StringArrayCodec extends ArrayCodec<String[]> {
    private static final StringArrayCodec INSTANCE = new StringArrayCodec(false);
    private static final StringArrayCodec NON_NULL = new StringArrayCodec(true);
    private final boolean rejectsNull;

    private StringArrayCodec(boolean rejectsNull) {
      super(String.class);
      this.rejectsNull = rejectsNull;
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
        String element = array[i];
        if (element == null && rejectsNull) {
          rejectNullElement();
        }
        writer.writeStringElement(i, element);
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
        String element = array[i];
        if (element == null && rejectsNull) {
          rejectNullElement();
        }
        writer.writeStringElement(i, element);
      }
      writer.writeArrayEnd();
    }

    private String readElement(Latin1JsonReader reader) {
      return requireElement(reader.readNextNullableString());
    }

    private String readElement(Utf16JsonReader reader) {
      return requireElement(reader.readNextNullableString());
    }

    private String readElement(Utf8JsonReader reader) {
      return requireElement(reader.readNextNullableString());
    }

    private String requireElement(String element) {
      if (element == null && rejectsNull) {
        return rejectNullElement();
      }
      return element;
    }

    private static String rejectNullElement() {
      throw new ForyJsonException("JSON array element is not nullable");
    }

    @Override
    public String[] readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        finishReferenceArray(reader, 0);
        return new String[0];
      }
      String v0 = readElement(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        finishReferenceArray(reader, 1);
        return new String[] {v0};
      }
      String v1 = readElement(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        finishReferenceArray(reader, 2);
        return new String[] {v0, v1};
      }
      String v2 = readElement(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        finishReferenceArray(reader, 3);
        return new String[] {v0, v1, v2};
      }
      String v3 = readElement(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        finishReferenceArray(reader, 4);
        return new String[] {v0, v1, v2, v3};
      }
      return readLatin1Tail(reader, v0, v1, v2, v3);
    }

    private String[] readLatin1Tail(
        Latin1JsonReader reader, String v0, String v1, String v2, String v3) {
      String v4 = readElement(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        finishReferenceArray(reader, 5);
        return new String[] {v0, v1, v2, v3, v4};
      }
      String v5 = readElement(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        finishReferenceArray(reader, 6);
        return new String[] {v0, v1, v2, v3, v4, v5};
      }
      String v6 = readElement(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        finishReferenceArray(reader, 7);
        return new String[] {v0, v1, v2, v3, v4, v5, v6};
      }
      String v7 = readElement(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        finishReferenceArray(reader, 8);
        return new String[] {v0, v1, v2, v3, v4, v5, v6, v7};
      }
      String v8 = readElement(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        finishReferenceArray(reader, 9);
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
        reserveReferenceBatch(reader, size);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = readElement(reader);
      } while (reader.consumeNextCommaOrEndArray());
      finishReferenceArray(reader, size);
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
        finishReferenceArray(reader, 0);
        return new String[0];
      }
      String v0 = readElement(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        finishReferenceArray(reader, 1);
        return new String[] {v0};
      }
      String v1 = readElement(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        finishReferenceArray(reader, 2);
        return new String[] {v0, v1};
      }
      String v2 = readElement(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        finishReferenceArray(reader, 3);
        return new String[] {v0, v1, v2};
      }
      String v3 = readElement(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        finishReferenceArray(reader, 4);
        return new String[] {v0, v1, v2, v3};
      }
      return readUtf16Tail(reader, v0, v1, v2, v3);
    }

    private String[] readUtf16Tail(
        Utf16JsonReader reader, String v0, String v1, String v2, String v3) {
      String v4 = readElement(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        finishReferenceArray(reader, 5);
        return new String[] {v0, v1, v2, v3, v4};
      }
      String v5 = readElement(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        finishReferenceArray(reader, 6);
        return new String[] {v0, v1, v2, v3, v4, v5};
      }
      String v6 = readElement(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        finishReferenceArray(reader, 7);
        return new String[] {v0, v1, v2, v3, v4, v5, v6};
      }
      String v7 = readElement(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        finishReferenceArray(reader, 8);
        return new String[] {v0, v1, v2, v3, v4, v5, v6, v7};
      }
      String v8 = readElement(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        finishReferenceArray(reader, 9);
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
        reserveReferenceBatch(reader, size);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = readElement(reader);
      } while (reader.consumeNextCommaOrEndArray());
      finishReferenceArray(reader, size);
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
        finishReferenceArray(reader, 0);
        return new String[0];
      }
      String v0 = readElement(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        finishReferenceArray(reader, 1);
        return new String[] {v0};
      }
      String v1 = readElement(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        finishReferenceArray(reader, 2);
        return new String[] {v0, v1};
      }
      String v2 = readElement(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        finishReferenceArray(reader, 3);
        return new String[] {v0, v1, v2};
      }
      String v3 = readElement(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        finishReferenceArray(reader, 4);
        return new String[] {v0, v1, v2, v3};
      }
      return readUtf8Tail(reader, v0, v1, v2, v3);
    }

    private String[] readUtf8Tail(
        Utf8JsonReader reader, String v0, String v1, String v2, String v3) {
      String v4 = readElement(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        finishReferenceArray(reader, 5);
        return new String[] {v0, v1, v2, v3, v4};
      }
      String v5 = readElement(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        finishReferenceArray(reader, 6);
        return new String[] {v0, v1, v2, v3, v4, v5};
      }
      String v6 = readElement(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        finishReferenceArray(reader, 7);
        return new String[] {v0, v1, v2, v3, v4, v5, v6};
      }
      String v7 = readElement(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        finishReferenceArray(reader, 8);
        return new String[] {v0, v1, v2, v3, v4, v5, v6, v7};
      }
      String v8 = readElement(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        finishReferenceArray(reader, 9);
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
        reserveReferenceBatch(reader, size);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = readElement(reader);
      } while (reader.consumeNextCommaOrEndArray());
      finishReferenceArray(reader, size);
      return Arrays.copyOf(values, size);
    }
  }

  private static class ObjectArrayCodec<T> extends ArrayCodec<T> {
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
    public void writeString(StringJsonWriter writer, T value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      Object[] array = (Object[]) value;
      StringWriterCodec<Object> codec = elementTypeInfo.stringWriter();
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        writeElement(writer, codec, array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, T value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      Object[] array = (Object[]) value;
      Utf8WriterCodec<Object> codec = elementTypeInfo.utf8Writer();
      writer.writeArrayStart();
      for (int i = 0; i < array.length; i++) {
        writer.writeComma(i);
        writeElement(writer, codec, array[i]);
      }
      writer.writeArrayEnd();
    }

    @Override
    public T readLatin1(Latin1JsonReader reader) {
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
            reserveReferenceBatch(reader, size);
            if (size == values.length) {
              values = Arrays.copyOf(values, values.length << 1);
            }
            values[size++] = readElement(reader, codec);
          } while (reader.consumeNextCommaOrEndArray());
        }
        reader.reserveGraphMemory(ARRAY_HEADER_BYTES + (size & ARRAY_BATCH_MASK) * REFERENCE_BYTES);
        T array = newArray(size);
        System.arraycopy(values, 0, array, 0, size);
        success = true;
        return array;
      } finally {
        releaseValues(values, size, depth, useCache, success);
        reader.exitDepth();
      }
    }

    @Override
    public T readUtf16(Utf16JsonReader reader) {
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
            reserveReferenceBatch(reader, size);
            if (size == values.length) {
              values = Arrays.copyOf(values, values.length << 1);
            }
            values[size++] = readElement(reader, codec);
          } while (reader.consumeNextCommaOrEndArray());
        }
        reader.reserveGraphMemory(ARRAY_HEADER_BYTES + (size & ARRAY_BATCH_MASK) * REFERENCE_BYTES);
        T array = newArray(size);
        System.arraycopy(values, 0, array, 0, size);
        success = true;
        return array;
      } finally {
        releaseValues(values, size, depth, useCache, success);
        reader.exitDepth();
      }
    }

    @Override
    public T readUtf8(Utf8JsonReader reader) {
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
            reserveReferenceBatch(reader, size);
            if (size == values.length) {
              values = Arrays.copyOf(values, values.length << 1);
            }
            values[size++] = readElement(reader, codec);
          } while (reader.consumeNextCommaOrEndArray());
        }
        reader.reserveGraphMemory(ARRAY_HEADER_BYTES + (size & ARRAY_BATCH_MASK) * REFERENCE_BYTES);
        T array = newArray(size);
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

    void writeElement(StringJsonWriter writer, StringWriterCodec<Object> codec, Object element) {
      codec.writeString(writer, element);
    }

    void writeElement(Utf8JsonWriter writer, Utf8WriterCodec<Object> codec, Object element) {
      codec.writeUtf8(writer, element);
    }

    Object readElement(Latin1JsonReader reader, Latin1ReaderCodec<Object> codec) {
      return codec.readLatin1(reader);
    }

    Object readElement(Utf16JsonReader reader, Utf16ReaderCodec<Object> codec) {
      return codec.readUtf16(reader);
    }

    Object readElement(Utf8JsonReader reader, Utf8ReaderCodec<Object> codec) {
      return codec.readUtf8(reader);
    }

    @SuppressWarnings("unchecked")
    private T newArray(int size) {
      // The factory constructs this codec only for reference-array components.
      return (T) Array.newInstance(componentType, size);
    }
  }

  private static final class NonNullObjectArrayCodec<T> extends ObjectArrayCodec<T> {
    private NonNullObjectArrayCodec(Class<?> componentType, JsonTypeInfo elementTypeInfo) {
      super(componentType, elementTypeInfo);
    }

    @Override
    void writeElement(StringJsonWriter writer, StringWriterCodec<Object> codec, Object element) {
      if (element == null) {
        rejectNullElement();
      }
      codec.writeString(writer, element);
    }

    @Override
    void writeElement(Utf8JsonWriter writer, Utf8WriterCodec<Object> codec, Object element) {
      if (element == null) {
        rejectNullElement();
      }
      codec.writeUtf8(writer, element);
    }

    @Override
    Object readElement(Latin1JsonReader reader, Latin1ReaderCodec<Object> codec) {
      if (reader.tryReadNullToken()) {
        return rejectNullElement();
      }
      return codec.readLatin1(reader);
    }

    @Override
    Object readElement(Utf16JsonReader reader, Utf16ReaderCodec<Object> codec) {
      if (reader.tryReadNullToken()) {
        return rejectNullElement();
      }
      return codec.readUtf16(reader);
    }

    @Override
    Object readElement(Utf8JsonReader reader, Utf8ReaderCodec<Object> codec) {
      if (reader.tryReadNullToken()) {
        return rejectNullElement();
      }
      return codec.readUtf8(reader);
    }
  }

  private static Object rejectNullElement() {
    throw new ForyJsonException("JSON array element is not nullable");
  }

  private static final class CustomPrimitiveArrayCodec<T> extends ArrayCodec<T> {
    private static final int INITIAL_SIZE = 8;

    private final JsonTypeInfo elementTypeInfo;
    private final int elementBytes;

    private CustomPrimitiveArrayCodec(Class<?> componentType, JsonTypeInfo elementTypeInfo) {
      super(componentType);
      this.elementTypeInfo = elementTypeInfo;
      elementBytes = TypeUtils.getSizeOfPrimitiveType(componentType);
    }

    @Override
    public void writeString(StringJsonWriter writer, T value) {
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
    public void writeUtf8(Utf8JsonWriter writer, T value) {
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
    public T readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      // Exact-capacity scratch arrays are returned directly, so their header must be reserved
      // before allocation rather than with a later exact-result copy.
      reader.reserveGraphMemory(ARRAY_HEADER_BYTES);
      if (reader.consumeNextToken(']')) {
        finishArray(reader, 0);
        return newArray(0);
      }
      Object values = Array.newInstance(componentType, INITIAL_SIZE);
      int size = 0;
      Latin1ReaderCodec<Object> codec = elementTypeInfo.latin1Reader();
      do {
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * elementBytes);
        }
        if (size == Array.getLength(values)) {
          Object grown = Array.newInstance(componentType, size << 1);
          System.arraycopy(values, 0, grown, 0, size);
          values = grown;
        }
        Object element = codec.readLatin1(reader);
        putElement(values, size++, element);
      } while (reader.consumeNextCommaOrEndArray());
      finishArray(reader, size);
      return copyArray(values, size);
    }

    @Override
    public T readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      reader.reserveGraphMemory(ARRAY_HEADER_BYTES);
      if (reader.consumeNextToken(']')) {
        finishArray(reader, 0);
        return newArray(0);
      }
      Object values = Array.newInstance(componentType, INITIAL_SIZE);
      int size = 0;
      Utf16ReaderCodec<Object> codec = elementTypeInfo.utf16Reader();
      do {
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * elementBytes);
        }
        if (size == Array.getLength(values)) {
          Object grown = Array.newInstance(componentType, size << 1);
          System.arraycopy(values, 0, grown, 0, size);
          values = grown;
        }
        Object element = codec.readUtf16(reader);
        putElement(values, size++, element);
      } while (reader.consumeNextCommaOrEndArray());
      finishArray(reader, size);
      return copyArray(values, size);
    }

    @Override
    public T readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('[');
      reader.reserveGraphMemory(ARRAY_HEADER_BYTES);
      if (reader.consumeNextToken(']')) {
        finishArray(reader, 0);
        return newArray(0);
      }
      Object values = Array.newInstance(componentType, INITIAL_SIZE);
      int size = 0;
      Utf8ReaderCodec<Object> codec = elementTypeInfo.utf8Reader();
      do {
        if ((size & ARRAY_BATCH_MASK) == ARRAY_BATCH_MASK) {
          reader.reserveGraphMemory(ARRAY_BATCH_SIZE * elementBytes);
        }
        if (size == Array.getLength(values)) {
          Object grown = Array.newInstance(componentType, size << 1);
          System.arraycopy(values, 0, grown, 0, size);
          values = grown;
        }
        Object element = codec.readUtf8(reader);
        putElement(values, size++, element);
      } while (reader.consumeNextCommaOrEndArray());
      finishArray(reader, size);
      return copyArray(values, size);
    }

    private void finishArray(JsonReader reader, int size) {
      int tailSize = size & ARRAY_BATCH_MASK;
      if (tailSize != 0) {
        reader.reserveGraphMemory(tailSize * elementBytes);
      }
      reader.exitDepth();
    }

    private void putElement(Object values, int index, Object element) {
      if (element == null) {
        throw new ForyJsonException("Cannot read null into primitive array element");
      }
      Array.set(values, index, element);
    }

    private T copyArray(Object values, int size) {
      if (size == Array.getLength(values)) {
        return castArray(values);
      }
      T result = newArray(size);
      System.arraycopy(values, 0, result, 0, size);
      return result;
    }

    @SuppressWarnings("unchecked")
    private T newArray(int size) {
      return (T) Array.newInstance(componentType, size);
    }

    @SuppressWarnings("unchecked")
    private T castArray(Object value) {
      return (T) value;
    }
  }

  public static final class BoxedIntArrayCodec extends ArrayCodec<Integer[]> {
    private static final BoxedIntArrayCodec INSTANCE = new BoxedIntArrayCodec(false);
    private static final BoxedIntArrayCodec NON_NULL = new BoxedIntArrayCodec(true);
    private final boolean rejectsNull;

    private BoxedIntArrayCodec(boolean rejectsNull) {
      super(Integer.class);
      this.rejectsNull = rejectsNull;
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
          writeReferenceNull(writer, rejectsNull);
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
          writeReferenceNull(writer, rejectsNull);
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
        finishReferenceArray(reader, 0);
        return new Integer[0];
      }
      Integer[] values = new Integer[8];
      int size = 0;
      do {
        reserveReferenceBatch(reader, size);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken()
                ? referenceNull(rejectsNull)
                : Integer.valueOf(reader.readIntValue());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishReferenceArray(reader, size);
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
        finishReferenceArray(reader, 0);
        return new Integer[0];
      }
      Integer[] values = new Integer[8];
      int size = 0;
      do {
        reserveReferenceBatch(reader, size);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken()
                ? referenceNull(rejectsNull)
                : Integer.valueOf(reader.readIntValue());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishReferenceArray(reader, size);
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
        finishReferenceArray(reader, 0);
        return new Integer[0];
      }
      Integer[] values = new Integer[8];
      int size = 0;
      do {
        reserveReferenceBatch(reader, size);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken()
                ? referenceNull(rejectsNull)
                : Integer.valueOf(reader.readIntValue());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishReferenceArray(reader, size);
      return Arrays.copyOf(values, size);
    }
  }

  public static class BoxedLongArrayCodec extends ArrayCodec<Long[]> {
    private static final BoxedLongArrayCodec INSTANCE = new BoxedLongArrayCodec(false);
    private static final BoxedLongArrayCodec NON_NULL = new BoxedLongArrayCodec(true);
    private final boolean rejectsNull;

    protected BoxedLongArrayCodec(boolean rejectsNull) {
      super(Long.class);
      this.rejectsNull = rejectsNull;
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
          writeReferenceNull(writer, rejectsNull);
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
          writeReferenceNull(writer, rejectsNull);
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
        finishReferenceArray(reader, 0);
        return new Long[0];
      }
      Long[] values = new Long[8];
      int size = 0;
      do {
        reserveReferenceBatch(reader, size);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken()
                ? referenceNull(rejectsNull)
                : Long.valueOf(reader.readLongValue());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishReferenceArray(reader, size);
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
        finishReferenceArray(reader, 0);
        return new Long[0];
      }
      Long[] values = new Long[8];
      int size = 0;
      do {
        reserveReferenceBatch(reader, size);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken()
                ? referenceNull(rejectsNull)
                : Long.valueOf(reader.readLongValue());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishReferenceArray(reader, size);
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
        finishReferenceArray(reader, 0);
        return new Long[0];
      }
      Long[] values = new Long[8];
      int size = 0;
      do {
        reserveReferenceBatch(reader, size);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken()
                ? referenceNull(rejectsNull)
                : Long.valueOf(reader.readLongValue());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishReferenceArray(reader, size);
      return Arrays.copyOf(values, size);
    }
  }

  private static final class BoxedLongAsStringArrayCodec extends BoxedLongArrayCodec {
    private static final BoxedLongAsStringArrayCodec INSTANCE =
        new BoxedLongAsStringArrayCodec(false);
    private static final BoxedLongAsStringArrayCodec NON_NULL =
        new BoxedLongAsStringArrayCodec(true);

    private final boolean rejectsNull;

    private BoxedLongAsStringArrayCodec(boolean rejectsNull) {
      super(rejectsNull);
      this.rejectsNull = rejectsNull;
    }

    @Override
    public void writeString(StringJsonWriter writer, Long[] value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      writer.writeArrayStart();
      for (int i = 0; i < value.length; i++) {
        writer.writeComma(i);
        Long element = value[i];
        if (element == null) {
          writeReferenceNull(writer, rejectsNull);
        } else {
          writer.writeLongAsString(element);
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
      writer.writeArrayStart();
      for (int i = 0; i < value.length; i++) {
        writer.writeComma(i);
        Long element = value[i];
        if (element == null) {
          writeReferenceNull(writer, rejectsNull);
        } else {
          writer.writeLongAsString(element);
        }
      }
      writer.writeArrayEnd();
    }
  }

  public static final class BoxedBooleanArrayCodec extends ArrayCodec<Boolean[]> {
    private static final BoxedBooleanArrayCodec INSTANCE = new BoxedBooleanArrayCodec(false);
    private static final BoxedBooleanArrayCodec NON_NULL = new BoxedBooleanArrayCodec(true);
    private final boolean rejectsNull;

    private BoxedBooleanArrayCodec(boolean rejectsNull) {
      super(Boolean.class);
      this.rejectsNull = rejectsNull;
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
          writeReferenceNull(writer, rejectsNull);
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
          writeReferenceNull(writer, rejectsNull);
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
        finishReferenceArray(reader, 0);
        return new Boolean[0];
      }
      Boolean[] values = new Boolean[8];
      int size = 0;
      do {
        reserveReferenceBatch(reader, size);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken()
                ? referenceNull(rejectsNull)
                : Boolean.valueOf(reader.readBooleanValue());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishReferenceArray(reader, size);
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
        finishReferenceArray(reader, 0);
        return new Boolean[0];
      }
      Boolean[] values = new Boolean[8];
      int size = 0;
      do {
        reserveReferenceBatch(reader, size);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken()
                ? referenceNull(rejectsNull)
                : Boolean.valueOf(reader.readBooleanValue());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishReferenceArray(reader, size);
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
        finishReferenceArray(reader, 0);
        return new Boolean[0];
      }
      Boolean[] values = new Boolean[8];
      int size = 0;
      do {
        reserveReferenceBatch(reader, size);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken()
                ? referenceNull(rejectsNull)
                : Boolean.valueOf(reader.readBooleanValue());
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishReferenceArray(reader, size);
      return Arrays.copyOf(values, size);
    }
  }

  public static final class BoxedShortArrayCodec extends ArrayCodec<Short[]> {
    private static final BoxedShortArrayCodec INSTANCE = new BoxedShortArrayCodec(false);
    private static final BoxedShortArrayCodec NON_NULL = new BoxedShortArrayCodec(true);
    private final boolean rejectsNull;

    private BoxedShortArrayCodec(boolean rejectsNull) {
      super(Short.class);
      this.rejectsNull = rejectsNull;
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
          writeReferenceNull(writer, rejectsNull);
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
          writeReferenceNull(writer, rejectsNull);
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
        finishReferenceArray(reader, 0);
        return new Short[0];
      }
      Short[] values = new Short[8];
      int size = 0;
      do {
        reserveReferenceBatch(reader, size);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken()
                ? referenceNull(rejectsNull)
                : Short.valueOf(readShort(reader.readIntValue()));
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishReferenceArray(reader, size);
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
        finishReferenceArray(reader, 0);
        return new Short[0];
      }
      Short[] values = new Short[8];
      int size = 0;
      do {
        reserveReferenceBatch(reader, size);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken()
                ? referenceNull(rejectsNull)
                : Short.valueOf(readShort(reader.readIntValue()));
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishReferenceArray(reader, size);
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
        finishReferenceArray(reader, 0);
        return new Short[0];
      }
      Short[] values = new Short[8];
      int size = 0;
      do {
        reserveReferenceBatch(reader, size);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken()
                ? referenceNull(rejectsNull)
                : Short.valueOf(readShort(reader.readIntValue()));
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishReferenceArray(reader, size);
      return Arrays.copyOf(values, size);
    }
  }

  public static final class BoxedByteArrayCodec extends ArrayCodec<Byte[]> {
    private static final BoxedByteArrayCodec INSTANCE = new BoxedByteArrayCodec(false);
    private static final BoxedByteArrayCodec NON_NULL = new BoxedByteArrayCodec(true);
    private final boolean rejectsNull;

    private BoxedByteArrayCodec(boolean rejectsNull) {
      super(Byte.class);
      this.rejectsNull = rejectsNull;
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
          writeReferenceNull(writer, rejectsNull);
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
          writeReferenceNull(writer, rejectsNull);
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
        finishReferenceArray(reader, 0);
        return new Byte[0];
      }
      Byte[] values = new Byte[8];
      int size = 0;
      do {
        reserveReferenceBatch(reader, size);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken()
                ? referenceNull(rejectsNull)
                : Byte.valueOf(readByte(reader.readIntValue()));
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishReferenceArray(reader, size);
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
        finishReferenceArray(reader, 0);
        return new Byte[0];
      }
      Byte[] values = new Byte[8];
      int size = 0;
      do {
        reserveReferenceBatch(reader, size);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken()
                ? referenceNull(rejectsNull)
                : Byte.valueOf(readByte(reader.readIntValue()));
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishReferenceArray(reader, size);
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
        finishReferenceArray(reader, 0);
        return new Byte[0];
      }
      Byte[] values = new Byte[8];
      int size = 0;
      do {
        reserveReferenceBatch(reader, size);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken()
                ? referenceNull(rejectsNull)
                : Byte.valueOf(readByte(reader.readIntValue()));
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishReferenceArray(reader, size);
      return Arrays.copyOf(values, size);
    }
  }

  public static final class BoxedCharArrayCodec extends ArrayCodec<Character[]> {
    private static final BoxedCharArrayCodec INSTANCE = new BoxedCharArrayCodec(false);
    private static final BoxedCharArrayCodec NON_NULL = new BoxedCharArrayCodec(true);
    private final boolean rejectsNull;

    private BoxedCharArrayCodec(boolean rejectsNull) {
      super(Character.class);
      this.rejectsNull = rejectsNull;
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
          writeReferenceNull(writer, rejectsNull);
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
          writeReferenceNull(writer, rejectsNull);
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
        finishReferenceArray(reader, 0);
        return new Character[0];
      }
      Character[] values = new Character[8];
      int size = 0;
      do {
        reserveReferenceBatch(reader, size);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        String element = reader.readNextNullableString();
        values[size++] =
            element == null ? referenceNull(rejectsNull) : Character.valueOf(readChar(element));
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishReferenceArray(reader, size);
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
        finishReferenceArray(reader, 0);
        return new Character[0];
      }
      Character[] values = new Character[8];
      int size = 0;
      do {
        reserveReferenceBatch(reader, size);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        String element = reader.readNextNullableString();
        values[size++] =
            element == null ? referenceNull(rejectsNull) : Character.valueOf(readChar(element));
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishReferenceArray(reader, size);
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
        finishReferenceArray(reader, 0);
        return new Character[0];
      }
      Character[] values = new Character[8];
      int size = 0;
      do {
        reserveReferenceBatch(reader, size);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        String element = reader.readNextNullableString();
        values[size++] =
            element == null ? referenceNull(rejectsNull) : Character.valueOf(readChar(element));
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
      finishReferenceArray(reader, size);
      return Arrays.copyOf(values, size);
    }
  }

  public static final class BoxedFloatArrayCodec extends ArrayCodec<Float[]> {
    private static final BoxedFloatArrayCodec INSTANCE = new BoxedFloatArrayCodec(false);
    private static final BoxedFloatArrayCodec NON_NULL = new BoxedFloatArrayCodec(true);
    private final boolean rejectsNull;

    private BoxedFloatArrayCodec(boolean rejectsNull) {
      super(Float.class);
      this.rejectsNull = rejectsNull;
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
          writeReferenceNull(writer, rejectsNull);
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
          writeReferenceNull(writer, rejectsNull);
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
        finishReferenceArray(reader, 0);
        return new Float[0];
      }
      Float[] values = new Float[8];
      int size = 0;
      do {
        reserveReferenceBatch(reader, size);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken()
                ? referenceNull(rejectsNull)
                : Float.valueOf(reader.readNextFloatValue());
      } while (reader.consumeNextCommaOrEndArray());
      finishReferenceArray(reader, size);
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
        finishReferenceArray(reader, 0);
        return new Float[0];
      }
      Float[] values = new Float[8];
      int size = 0;
      do {
        reserveReferenceBatch(reader, size);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken()
                ? referenceNull(rejectsNull)
                : Float.valueOf(reader.readNextFloatValue());
      } while (reader.consumeNextCommaOrEndArray());
      finishReferenceArray(reader, size);
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
        finishReferenceArray(reader, 0);
        return new Float[0];
      }
      Float[] values = new Float[8];
      int size = 0;
      do {
        reserveReferenceBatch(reader, size);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken()
                ? referenceNull(rejectsNull)
                : Float.valueOf(reader.readNextFloatValue());
      } while (reader.consumeNextCommaOrEndArray());
      finishReferenceArray(reader, size);
      return Arrays.copyOf(values, size);
    }
  }

  public static final class BoxedDoubleArrayCodec extends ArrayCodec<Double[]> {
    private static final BoxedDoubleArrayCodec INSTANCE = new BoxedDoubleArrayCodec(false);
    private static final BoxedDoubleArrayCodec NON_NULL = new BoxedDoubleArrayCodec(true);
    private final boolean rejectsNull;

    private BoxedDoubleArrayCodec(boolean rejectsNull) {
      super(Double.class);
      this.rejectsNull = rejectsNull;
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
          writeReferenceNull(writer, rejectsNull);
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
          writeReferenceNull(writer, rejectsNull);
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
        finishReferenceArray(reader, 0);
        return new Double[0];
      }
      Double[] values = new Double[8];
      int size = 0;
      do {
        reserveReferenceBatch(reader, size);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken()
                ? referenceNull(rejectsNull)
                : Double.valueOf(reader.readNextDoubleValue());
      } while (reader.consumeNextCommaOrEndArray());
      finishReferenceArray(reader, size);
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
        finishReferenceArray(reader, 0);
        return new Double[0];
      }
      Double[] values = new Double[8];
      int size = 0;
      do {
        reserveReferenceBatch(reader, size);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken()
                ? referenceNull(rejectsNull)
                : Double.valueOf(reader.readNextDoubleValue());
      } while (reader.consumeNextCommaOrEndArray());
      finishReferenceArray(reader, size);
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
        finishReferenceArray(reader, 0);
        return new Double[0];
      }
      Double[] values = new Double[8];
      int size = 0;
      do {
        reserveReferenceBatch(reader, size);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] =
            reader.tryReadNextNullToken()
                ? referenceNull(rejectsNull)
                : Double.valueOf(reader.readNextDoubleValue());
      } while (reader.consumeNextCommaOrEndArray());
      finishReferenceArray(reader, size);
      return Arrays.copyOf(values, size);
    }
  }

  private static <T> T referenceNull(boolean rejectsNull) {
    if (rejectsNull) {
      throw new ForyJsonException("JSON array element is not nullable");
    }
    return null;
  }

  private static void writeReferenceNull(JsonWriter writer, boolean rejectsNull) {
    if (rejectsNull) {
      throw new ForyJsonException("JSON array element is not nullable");
    }
    writer.writeNull();
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

  // JsonReader.readUnsignedInt returns raw int bits, so narrowing gates must compare them unsigned.
  private static short readUnsignedShort(int value) {
    if (Integer.compareUnsigned(value, 0xffff) > 0) {
      throw new ForyJsonException("Unsigned short overflow");
    }
    return (short) value;
  }

  private static byte readUnsignedByte(int value) {
    if (Integer.compareUnsigned(value, 0xff) > 0) {
      throw new ForyJsonException("Unsigned byte overflow");
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

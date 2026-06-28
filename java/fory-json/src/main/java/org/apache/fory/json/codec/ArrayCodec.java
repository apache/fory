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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
    }
    return new ObjectArrayCodec(componentType, resolver.getTypeInfo(componentType, componentType));
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
      long[] values = new long[8];
      int size = 0;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readLongValue();
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
        return new long[0];
      }
      long[] values = new long[8];
      int size = 0;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readLongValue();
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
        return new long[0];
      }
      long[] values = new long[8];
      int size = 0;
      do {
        rejectNull(reader);
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readLongValue();
      } while (reader.consumeNextToken(','));
      reader.expectNextToken(']');
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
        values[size++] = Float.parseFloat(reader.readNumber());
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
        values[size++] = Double.parseDouble(reader.readNumber());
      } while (reader.consume(','));
      reader.expect(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }

  public static final class ObjectArrayCodec extends ArrayCodec {
    private final JsonTypeInfo elementTypeInfo;
    private final JsonCodec elementCodec;

    private ObjectArrayCodec(Class<?> componentType, JsonTypeInfo elementTypeInfo) {
      super(componentType);
      this.elementTypeInfo = elementTypeInfo;
      elementCodec = elementTypeInfo.codec();
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
      List<Object> values = new ArrayList<>(0);
      reader.expect('[');
      if (!reader.consume(']')) {
        do {
          values.add(elementCodec.read(reader, elementTypeInfo, resolver));
        } while (reader.consume(','));
        reader.expect(']');
      }
      reader.exitDepth();
      return toArray(values);
    }

    private Object toArray(List<Object> values) {
      Object array = Array.newInstance(componentType, values.size());
      for (int i = 0; i < values.size(); i++) {
        Array.set(array, i, values.get(i));
      }
      return array;
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
    String value = reader.readString();
    if (value.length() != 1) {
      throw new ForyJsonException("Expected one-character JSON string for char");
    }
    return value.charAt(0);
  }
}

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

import java.util.Arrays;
import org.apache.fory.config.Config;
import org.apache.fory.config.Int64Encoding;
import org.apache.fory.context.CopyContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.type.BFloat16Array;
import org.apache.fory.type.Float16Array;

/** Serializers for primitive dense-array carriers. */
public final class PrimitiveArraySerializers {
  private PrimitiveArraySerializers() {}

  public static final class PrimitiveArrayBufferObject implements BufferObject {
    private final Object array;
    private final int offset;
    private final int elemSize;
    private final int length;

    public PrimitiveArrayBufferObject(Object array, int offset, int elemSize, int length) {
      this.array = array;
      this.offset = offset;
      this.elemSize = elemSize;
      this.length = length;
    }

    @Override
    public int totalBytes() {
      return length * elemSize;
    }

    @Override
    public void writeTo(MemoryBuffer buffer) {
      int size = Math.multiplyExact(length, elemSize);
      int writerIndex = buffer.writerIndex();
      int end = writerIndex + size;
      buffer.ensure(end);
      buffer.copyFromUnsafe(writerIndex, array, offset, size);
      buffer.writerIndex(end);
    }

    @Override
    public MemoryBuffer toBuffer() {
      MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(totalBytes());
      writeTo(buffer);
      return buffer.slice(0, buffer.writerIndex());
    }
  }

  public abstract static class PrimitiveArraySerializer<T> extends Serializer<T>
      implements Shareable {
    protected final Config config;

    public PrimitiveArraySerializer(TypeResolver typeResolver, Class<T> cls) {
      super(typeResolver.getConfig(), cls);
      this.config = typeResolver.getConfig();
    }
  }

  public static final class BooleanArraySerializer extends PrimitiveArraySerializer<boolean[]> {
    public BooleanArraySerializer(TypeResolver typeResolver) {
      super(typeResolver, boolean[].class);
    }

    @Override
    public void write(WriteContext writeContext, boolean[] value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      if (writeContext.getBufferCallback() == null) {
        int size = Math.multiplyExact(value.length, 1);
        buffer.writePrimitiveArrayWithSize(value, Platform.BOOLEAN_ARRAY_OFFSET, size);
      } else {
        writeContext.writeBufferObject(
            new PrimitiveArrayBufferObject(value, Platform.BOOLEAN_ARRAY_OFFSET, 1, value.length));
      }
    }

    @Override
    public boolean[] copy(CopyContext copyContext, boolean[] originArray) {
      return Arrays.copyOf(originArray, originArray.length);
    }

    @Override
    public boolean[] read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      if (readContext.isPeerOutOfBandEnabled()) {
        MemoryBuffer buf = readContext.readBufferObject();
        int size = buf.remaining();
        boolean[] values = new boolean[size];
        buf.copyToUnsafe(0, values, Platform.BOOLEAN_ARRAY_OFFSET, size);
        return values;
      }
      int size = buffer.readVarUInt32Small7();
      boolean[] values = new boolean[size];
      buffer.readToUnsafe(values, Platform.BOOLEAN_ARRAY_OFFSET, size);
      return values;
    }
  }

  public static final class ByteArraySerializer extends PrimitiveArraySerializer<byte[]> {
    public ByteArraySerializer(TypeResolver typeResolver) {
      super(typeResolver, byte[].class);
    }

    @Override
    public void write(WriteContext writeContext, byte[] value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      if (writeContext.getBufferCallback() == null) {
        int size = Math.multiplyExact(value.length, 1);
        buffer.writePrimitiveArrayWithSize(value, Platform.BYTE_ARRAY_OFFSET, size);
      } else {
        writeContext.writeBufferObject(
            new PrimitiveArrayBufferObject(value, Platform.BYTE_ARRAY_OFFSET, 1, value.length));
      }
    }

    @Override
    public byte[] copy(CopyContext copyContext, byte[] originArray) {
      return Arrays.copyOf(originArray, originArray.length);
    }

    @Override
    public byte[] read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      if (readContext.isPeerOutOfBandEnabled()) {
        MemoryBuffer buf = readContext.readBufferObject();
        int size = buf.remaining();
        byte[] values = new byte[size];
        buf.copyToUnsafe(0, values, Platform.BYTE_ARRAY_OFFSET, size);
        return values;
      }
      int size = buffer.readVarUInt32Small7();
      byte[] values = new byte[size];
      buffer.readToUnsafe(values, Platform.BYTE_ARRAY_OFFSET, size);
      return values;
    }
  }

  public static final class CharArraySerializer extends PrimitiveArraySerializer<char[]> {
    public CharArraySerializer(TypeResolver typeResolver) {
      super(typeResolver, char[].class);
    }

    @Override
    public void write(WriteContext writeContext, char[] value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      if (config.isXlang()) {
        throw new UnsupportedOperationException();
      }
      if (writeContext.getBufferCallback() == null) {
        int size = Math.multiplyExact(value.length, 2);
        if (Platform.IS_LITTLE_ENDIAN) {
          buffer.writePrimitiveArrayWithSize(value, Platform.CHAR_ARRAY_OFFSET, size);
        } else {
          writeCharBySwapEndian(buffer, value);
        }
      } else {
        writeContext.writeBufferObject(
            new PrimitiveArrayBufferObject(value, Platform.CHAR_ARRAY_OFFSET, 2, value.length));
      }
    }

    private void writeCharBySwapEndian(MemoryBuffer buffer, char[] value) {
      int idx = buffer.writerIndex();
      int length = value.length;
      buffer.ensure(idx + 5 + length * 2);
      idx += buffer._unsafeWriteVarUInt32(length * 2);
      for (int i = 0; i < length; i++) {
        buffer._unsafePutInt16(idx + i * 2, (short) value[i]);
      }
      buffer._unsafeWriterIndex(idx + length * 2);
    }

    @Override
    public char[] copy(CopyContext copyContext, char[] originArray) {
      return Arrays.copyOf(originArray, originArray.length);
    }

    @Override
    public char[] read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      if (config.isXlang()) {
        throw new UnsupportedOperationException();
      }
      if (readContext.isPeerOutOfBandEnabled()) {
        MemoryBuffer buf = readContext.readBufferObject();
        int size = buf.remaining();
        int numElements = size / 2;
        char[] values = new char[numElements];
        if (Platform.IS_LITTLE_ENDIAN) {
          buf.copyToUnsafe(0, values, Platform.CHAR_ARRAY_OFFSET, size);
        } else {
          readCharBySwapEndian(buf, values, numElements);
        }
        return values;
      }
      int size = buffer.readVarUInt32Small7();
      int numElements = size / 2;
      char[] values = new char[numElements];
      if (Platform.IS_LITTLE_ENDIAN) {
        buffer.readToUnsafe(values, Platform.CHAR_ARRAY_OFFSET, size);
      } else {
        readCharBySwapEndian(buffer, values, numElements);
      }
      return values;
    }

    private void readCharBySwapEndian(MemoryBuffer buffer, char[] values, int numElements) {
      int idx = buffer.readerIndex();
      int size = numElements * 2;
      buffer.checkReadableBytes(size);
      for (int i = 0; i < numElements; i++) {
        values[i] = (char) buffer._unsafeGetInt16(idx + i * 2);
      }
      buffer._increaseReaderIndexUnsafe(size);
    }
  }

  public static final class ShortArraySerializer extends PrimitiveArraySerializer<short[]> {
    public ShortArraySerializer(TypeResolver typeResolver) {
      super(typeResolver, short[].class);
    }

    @Override
    public void write(WriteContext writeContext, short[] value) {
      writeShortBits(writeContext, value);
    }

    @Override
    public short[] copy(CopyContext copyContext, short[] originArray) {
      return Arrays.copyOf(originArray, originArray.length);
    }

    @Override
    public short[] read(ReadContext readContext) {
      return readShortBits(readContext);
    }
  }

  public static final class IntArraySerializer extends PrimitiveArraySerializer<int[]> {
    public IntArraySerializer(TypeResolver typeResolver) {
      super(typeResolver, int[].class);
    }

    @Override
    public void write(WriteContext writeContext, int[] value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      if (writeContext.getBufferCallback() == null) {
        if (!config.isXlang() && config.compressIntArray()) {
          writeInt32Compressed(buffer, value);
          return;
        }
        int size = Math.multiplyExact(value.length, 4);
        if (Platform.IS_LITTLE_ENDIAN) {
          buffer.writePrimitiveArrayWithSize(value, Platform.INT_ARRAY_OFFSET, size);
        } else {
          writeInt32BySwapEndian(buffer, value);
        }
      } else {
        writeContext.writeBufferObject(
            new PrimitiveArrayBufferObject(value, Platform.INT_ARRAY_OFFSET, 4, value.length));
      }
    }

    private void writeInt32BySwapEndian(MemoryBuffer buffer, int[] value) {
      int idx = buffer.writerIndex();
      int length = value.length;
      buffer.ensure(idx + 5 + length * 4);
      idx += buffer._unsafeWriteVarUInt32(length * 4);
      for (int i = 0; i < length; i++) {
        buffer._unsafePutInt32(idx + i * 4, value[i]);
      }
      buffer._unsafeWriterIndex(idx + length * 4);
    }

    @Override
    public int[] copy(CopyContext copyContext, int[] originArray) {
      return Arrays.copyOf(originArray, originArray.length);
    }

    @Override
    public int[] read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      if (readContext.isPeerOutOfBandEnabled()) {
        MemoryBuffer buf = readContext.readBufferObject();
        int size = buf.remaining();
        int numElements = size / 4;
        int[] values = new int[numElements];
        if (size > 0) {
          if (Platform.IS_LITTLE_ENDIAN) {
            buf.copyToUnsafe(0, values, Platform.INT_ARRAY_OFFSET, size);
          } else {
            readInt32BySwapEndian(buf, values, numElements);
          }
        }
        return values;
      }
      if (!config.isXlang() && config.compressIntArray()) {
        return readInt32Compressed(buffer);
      }
      int size = buffer.readVarUInt32Small7();
      int numElements = size / 4;
      int[] values = new int[numElements];
      if (size > 0) {
        if (Platform.IS_LITTLE_ENDIAN) {
          buffer.readToUnsafe(values, Platform.INT_ARRAY_OFFSET, size);
        } else {
          readInt32BySwapEndian(buffer, values, numElements);
        }
      }
      return values;
    }

    private void readInt32BySwapEndian(MemoryBuffer buffer, int[] values, int numElements) {
      int idx = buffer.readerIndex();
      int size = numElements * 4;
      buffer.checkReadableBytes(size);
      for (int i = 0; i < numElements; i++) {
        values[i] = buffer._unsafeGetInt32(idx + i * 4);
      }
      buffer._increaseReaderIndexUnsafe(size);
    }

    private void writeInt32Compressed(MemoryBuffer buffer, int[] value) {
      buffer.writeVarUInt32Small7(value.length);
      for (int i : value) {
        buffer.writeVarInt32(i);
      }
    }

    private int[] readInt32Compressed(MemoryBuffer buffer) {
      int numElements = buffer.readVarUInt32Small7();
      int[] values = new int[numElements];
      for (int i = 0; i < numElements; i++) {
        values[i] = buffer.readVarInt32();
      }
      return values;
    }
  }

  public static final class LongArraySerializer extends PrimitiveArraySerializer<long[]> {
    private final boolean compressLongArray;

    public LongArraySerializer(TypeResolver typeResolver) {
      super(typeResolver, long[].class);
      compressLongArray =
          !typeResolver.getConfig().isXlang()
              && typeResolver.getConfig().compressLongArray()
              && typeResolver.getConfig().longEncoding() != Int64Encoding.FIXED;
    }

    @Override
    public void write(WriteContext writeContext, long[] value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      if (writeContext.getBufferCallback() == null) {
        if (compressLongArray) {
          writeInt64Compressed(buffer, value, config.longEncoding());
          return;
        }
        int size = Math.multiplyExact(value.length, 8);
        if (Platform.IS_LITTLE_ENDIAN) {
          buffer.writePrimitiveArrayWithSize(value, Platform.LONG_ARRAY_OFFSET, size);
        } else {
          writeInt64BySwapEndian(buffer, value);
        }
      } else {
        writeContext.writeBufferObject(
            new PrimitiveArrayBufferObject(value, Platform.LONG_ARRAY_OFFSET, 8, value.length));
      }
    }

    private void writeInt64BySwapEndian(MemoryBuffer buffer, long[] value) {
      int idx = buffer.writerIndex();
      int length = value.length;
      buffer.ensure(idx + 5 + length * 8);
      idx += buffer._unsafeWriteVarUInt32(length * 8);
      for (int i = 0; i < length; i++) {
        buffer._unsafePutInt64(idx + i * 8, value[i]);
      }
      buffer._unsafeWriterIndex(idx + length * 8);
    }

    @Override
    public long[] copy(CopyContext copyContext, long[] originArray) {
      return Arrays.copyOf(originArray, originArray.length);
    }

    @Override
    public long[] read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      if (readContext.isPeerOutOfBandEnabled()) {
        MemoryBuffer buf = readContext.readBufferObject();
        int size = buf.remaining();
        int numElements = size / 8;
        long[] values = new long[numElements];
        if (size > 0) {
          if (Platform.IS_LITTLE_ENDIAN) {
            buf.copyToUnsafe(0, values, Platform.LONG_ARRAY_OFFSET, size);
          } else {
            readInt64BySwapEndian(buf, values, numElements);
          }
        }
        return values;
      }
      if (compressLongArray) {
        return readInt64Compressed(buffer, config.longEncoding());
      }
      int size = buffer.readVarUInt32Small7();
      int numElements = size / 8;
      long[] values = new long[numElements];
      if (size > 0) {
        if (Platform.IS_LITTLE_ENDIAN) {
          buffer.readToUnsafe(values, Platform.LONG_ARRAY_OFFSET, size);
        } else {
          readInt64BySwapEndian(buffer, values, numElements);
        }
      }
      return values;
    }

    private void readInt64BySwapEndian(MemoryBuffer buffer, long[] values, int numElements) {
      int idx = buffer.readerIndex();
      int size = numElements * 8;
      buffer.checkReadableBytes(size);
      for (int i = 0; i < numElements; i++) {
        values[i] = buffer._unsafeGetInt64(idx + i * 8);
      }
      buffer._increaseReaderIndexUnsafe(size);
    }

    private void writeInt64Compressed(
        MemoryBuffer buffer, long[] value, Int64Encoding longEncoding) {
      int length = value.length;
      buffer.writeVarUInt32Small7(length);
      if (longEncoding == Int64Encoding.TAGGED) {
        for (int i = 0; i < length; i++) {
          buffer.writeTaggedInt64(value[i]);
        }
        return;
      }
      for (int i = 0; i < length; i++) {
        buffer.writeVarInt64(value[i]);
      }
    }

    private long[] readInt64Compressed(MemoryBuffer buffer, Int64Encoding longEncoding) {
      int numElements = buffer.readVarUInt32Small7();
      long[] values = new long[numElements];
      if (longEncoding == Int64Encoding.TAGGED) {
        for (int i = 0; i < numElements; i++) {
          values[i] = buffer.readTaggedInt64();
        }
      } else {
        for (int i = 0; i < numElements; i++) {
          values[i] = buffer.readVarInt64();
        }
      }
      return values;
    }
  }

  public static final class FloatArraySerializer extends PrimitiveArraySerializer<float[]> {
    public FloatArraySerializer(TypeResolver typeResolver) {
      super(typeResolver, float[].class);
    }

    @Override
    public void write(WriteContext writeContext, float[] value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      if (writeContext.getBufferCallback() == null) {
        int size = Math.multiplyExact(value.length, 4);
        if (Platform.IS_LITTLE_ENDIAN) {
          buffer.writePrimitiveArrayWithSize(value, Platform.FLOAT_ARRAY_OFFSET, size);
        } else {
          writeFloat32BySwapEndian(buffer, value);
        }
      } else {
        writeContext.writeBufferObject(
            new PrimitiveArrayBufferObject(value, Platform.FLOAT_ARRAY_OFFSET, 4, value.length));
      }
    }

    private void writeFloat32BySwapEndian(MemoryBuffer buffer, float[] value) {
      int idx = buffer.writerIndex();
      int length = value.length;
      buffer.ensure(idx + 5 + length * 4);
      idx += buffer._unsafeWriteVarUInt32(length * 4);
      for (int i = 0; i < length; i++) {
        buffer._unsafePutInt32(idx + i * 4, Float.floatToRawIntBits(value[i]));
      }
      buffer._unsafeWriterIndex(idx + length * 4);
    }

    @Override
    public float[] copy(CopyContext copyContext, float[] originArray) {
      return Arrays.copyOf(originArray, originArray.length);
    }

    @Override
    public float[] read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      if (readContext.isPeerOutOfBandEnabled()) {
        MemoryBuffer buf = readContext.readBufferObject();
        int size = buf.remaining();
        int numElements = size / 4;
        float[] values = new float[numElements];
        if (Platform.IS_LITTLE_ENDIAN) {
          buf.copyToUnsafe(0, values, Platform.FLOAT_ARRAY_OFFSET, size);
        } else {
          readFloat32BySwapEndian(buf, values, numElements);
        }
        return values;
      }
      int size = buffer.readVarUInt32Small7();
      int numElements = size / 4;
      float[] values = new float[numElements];
      if (Platform.IS_LITTLE_ENDIAN) {
        buffer.readToUnsafe(values, Platform.FLOAT_ARRAY_OFFSET, size);
      } else {
        readFloat32BySwapEndian(buffer, values, numElements);
      }
      return values;
    }

    private void readFloat32BySwapEndian(MemoryBuffer buffer, float[] values, int numElements) {
      int idx = buffer.readerIndex();
      int size = numElements * 4;
      buffer.checkReadableBytes(size);
      for (int i = 0; i < numElements; i++) {
        values[i] = Float.intBitsToFloat(buffer._unsafeGetInt32(idx + i * 4));
      }
      buffer._increaseReaderIndexUnsafe(size);
    }
  }

  public static final class DoubleArraySerializer extends PrimitiveArraySerializer<double[]> {
    public DoubleArraySerializer(TypeResolver typeResolver) {
      super(typeResolver, double[].class);
    }

    @Override
    public void write(WriteContext writeContext, double[] value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      if (writeContext.getBufferCallback() == null) {
        int size = Math.multiplyExact(value.length, 8);
        if (Platform.IS_LITTLE_ENDIAN) {
          buffer.writePrimitiveArrayWithSize(value, Platform.DOUBLE_ARRAY_OFFSET, size);
        } else {
          writeFloat64BySwapEndian(buffer, value);
        }
      } else {
        writeContext.writeBufferObject(
            new PrimitiveArrayBufferObject(value, Platform.DOUBLE_ARRAY_OFFSET, 8, value.length));
      }
    }

    private void writeFloat64BySwapEndian(MemoryBuffer buffer, double[] value) {
      int idx = buffer.writerIndex();
      int length = value.length;
      buffer.ensure(idx + 5 + length * 8);
      idx += buffer._unsafeWriteVarUInt32(length * 8);
      for (int i = 0; i < length; i++) {
        buffer._unsafePutInt64(idx + i * 8, Double.doubleToRawLongBits(value[i]));
      }
      buffer._unsafeWriterIndex(idx + length * 8);
    }

    @Override
    public double[] copy(CopyContext copyContext, double[] originArray) {
      return Arrays.copyOf(originArray, originArray.length);
    }

    @Override
    public double[] read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      if (readContext.isPeerOutOfBandEnabled()) {
        MemoryBuffer buf = readContext.readBufferObject();
        int size = buf.remaining();
        int numElements = size / 8;
        double[] values = new double[numElements];
        if (Platform.IS_LITTLE_ENDIAN) {
          buf.copyToUnsafe(0, values, Platform.DOUBLE_ARRAY_OFFSET, size);
        } else {
          readFloat64BySwapEndian(buf, values, numElements);
        }
        return values;
      }
      int size = buffer.readVarUInt32Small7();
      int numElements = size / 8;
      double[] values = new double[numElements];
      if (Platform.IS_LITTLE_ENDIAN) {
        buffer.readToUnsafe(values, Platform.DOUBLE_ARRAY_OFFSET, size);
      } else {
        readFloat64BySwapEndian(buffer, values, numElements);
      }
      return values;
    }

    private void readFloat64BySwapEndian(MemoryBuffer buffer, double[] values, int numElements) {
      int idx = buffer.readerIndex();
      int size = numElements * 8;
      buffer.checkReadableBytes(size);
      for (int i = 0; i < numElements; i++) {
        values[i] = Double.longBitsToDouble(buffer._unsafeGetInt64(idx + i * 8));
      }
      buffer._increaseReaderIndexUnsafe(size);
    }
  }

  public static final class Float16ArraySerializer extends PrimitiveArraySerializer<Float16Array> {
    public Float16ArraySerializer(TypeResolver typeResolver) {
      super(typeResolver, Float16Array.class);
    }

    @Override
    public void write(WriteContext writeContext, Float16Array value) {
      writeShortBits(writeContext, value.getBitsArray());
    }

    @Override
    public Float16Array copy(CopyContext copyContext, Float16Array originArray) {
      return Float16Array.fromBits(originArray.getBitsArray());
    }

    @Override
    public Float16Array read(ReadContext readContext) {
      return Float16Array.wrapBits(readShortBits(readContext));
    }
  }

  public static final class BFloat16ArraySerializer
      extends PrimitiveArraySerializer<BFloat16Array> {
    public BFloat16ArraySerializer(TypeResolver typeResolver) {
      super(typeResolver, BFloat16Array.class);
    }

    @Override
    public void write(WriteContext writeContext, BFloat16Array value) {
      writeShortBits(writeContext, value.getBitsArray());
    }

    @Override
    public BFloat16Array copy(CopyContext copyContext, BFloat16Array originArray) {
      return BFloat16Array.fromBits(originArray.getBitsArray());
    }

    @Override
    public BFloat16Array read(ReadContext readContext) {
      return BFloat16Array.wrapBits(readShortBits(readContext));
    }
  }

  private static void writeShortBits(WriteContext writeContext, short[] value) {
    MemoryBuffer buffer = writeContext.getBuffer();
    if (writeContext.getBufferCallback() == null) {
      int size = Math.multiplyExact(value.length, 2);
      if (Platform.IS_LITTLE_ENDIAN) {
        buffer.writePrimitiveArrayWithSize(value, Platform.SHORT_ARRAY_OFFSET, size);
      } else {
        writeInt16BySwapEndian(buffer, value);
      }
    } else {
      writeContext.writeBufferObject(
          new PrimitiveArrayBufferObject(value, Platform.SHORT_ARRAY_OFFSET, 2, value.length));
    }
  }

  private static void writeInt16BySwapEndian(MemoryBuffer buffer, short[] value) {
    int idx = buffer.writerIndex();
    int length = value.length;
    buffer.ensure(idx + 5 + length * 2);
    idx += buffer._unsafeWriteVarUInt32(length * 2);
    for (int i = 0; i < length; i++) {
      buffer._unsafePutInt16(idx + i * 2, value[i]);
    }
    buffer._unsafeWriterIndex(idx + length * 2);
  }

  private static short[] readShortBits(ReadContext readContext) {
    MemoryBuffer buffer = readContext.getBuffer();
    if (readContext.isPeerOutOfBandEnabled()) {
      MemoryBuffer buf = readContext.readBufferObject();
      int size = buf.remaining();
      int numElements = size / 2;
      short[] values = new short[numElements];
      if (Platform.IS_LITTLE_ENDIAN) {
        buf.copyToUnsafe(0, values, Platform.SHORT_ARRAY_OFFSET, size);
      } else {
        readInt16BySwapEndian(buf, values, numElements);
      }
      return values;
    }
    int size = buffer.readVarUInt32Small7();
    int numElements = size / 2;
    short[] values = new short[numElements];
    if (Platform.IS_LITTLE_ENDIAN) {
      buffer.readToUnsafe(values, Platform.SHORT_ARRAY_OFFSET, size);
    } else {
      readInt16BySwapEndian(buffer, values, numElements);
    }
    return values;
  }

  private static void readInt16BySwapEndian(MemoryBuffer buffer, short[] values, int numElements) {
    int idx = buffer.readerIndex();
    int size = numElements * 2;
    buffer.checkReadableBytes(size);
    for (int i = 0; i < numElements; i++) {
      values[i] = buffer._unsafeGetInt16(idx + i * 2);
    }
    buffer._increaseReaderIndexUnsafe(size);
  }

  public static void registerDefaultSerializers(TypeResolver resolver) {
    resolver.registerInternalSerializer(byte[].class, new ByteArraySerializer(resolver));
    resolver.registerInternalSerializer(char[].class, new CharArraySerializer(resolver));
    resolver.registerInternalSerializer(short[].class, new ShortArraySerializer(resolver));
    resolver.registerInternalSerializer(int[].class, new IntArraySerializer(resolver));
    resolver.registerInternalSerializer(long[].class, new LongArraySerializer(resolver));
    resolver.registerInternalSerializer(float[].class, new FloatArraySerializer(resolver));
    resolver.registerInternalSerializer(double[].class, new DoubleArraySerializer(resolver));
    resolver.registerInternalSerializer(boolean[].class, new BooleanArraySerializer(resolver));
    resolver.registerInternalSerializer(Float16Array.class, new Float16ArraySerializer(resolver));
    resolver.registerInternalSerializer(BFloat16Array.class, new BFloat16ArraySerializer(resolver));
  }

  static void writePrimitiveArray(
      MemoryBuffer buffer, Object arr, int offset, int numElements, int elemSize) {
    int size = Math.multiplyExact(numElements, elemSize);
    buffer.writeVarUInt32Small7(size);
    int writerIndex = buffer.writerIndex();
    int end = writerIndex + size;
    buffer.ensure(end);
    buffer.copyFromUnsafe(writerIndex, arr, offset, size);
    buffer.writerIndex(end);
  }

  public static PrimitiveArrayBufferObject byteArrayBufferObject(byte[] array) {
    return new PrimitiveArrayBufferObject(array, Platform.BYTE_ARRAY_OFFSET, 1, array.length);
  }
}

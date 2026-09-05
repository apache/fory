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
import org.apache.fory.exception.DeserializationException;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.type.BFloat16Array;
import org.apache.fory.type.Float16Array;
import org.apache.fory.type.Types;

/** Serializers for primitive dense-array carriers. */
public final class PrimitiveArraySerializers {
  private PrimitiveArraySerializers() {}

  public static final class PrimitiveArrayBufferObject implements BufferObject {
    private final Object array;
    private final int primitiveArrayTypeId;
    private final int elemSize;
    private final int length;

    public PrimitiveArrayBufferObject(
        Object array, int primitiveArrayTypeId, int elemSize, int length) {
      this.array = array;
      this.primitiveArrayTypeId = primitiveArrayTypeId;
      this.elemSize = elemSize;
      this.length = length;
    }

    @Override
    public int totalBytes() {
      return length * elemSize;
    }

    @Override
    public void writeTo(MemoryBuffer buffer) {
      switch (primitiveArrayTypeId) {
        case Types.BOOL_ARRAY:
          buffer.writeBooleans((boolean[]) array, 0, length);
          return;
        case Types.INT8_ARRAY:
        case Types.UINT8_ARRAY:
          buffer.writeBytes((byte[]) array, 0, length);
          return;
        case Types.UINT16_ARRAY:
          buffer.writeChars((char[]) array, 0, length);
          return;
        case Types.INT16_ARRAY:
        case Types.FLOAT16_ARRAY:
        case Types.BFLOAT16_ARRAY:
          buffer.writeShorts((short[]) array, 0, length);
          return;
        case Types.INT32_ARRAY:
        case Types.UINT32_ARRAY:
          buffer.writeInts((int[]) array, 0, length);
          return;
        case Types.INT64_ARRAY:
        case Types.UINT64_ARRAY:
          buffer.writeLongs((long[]) array, 0, length);
          return;
        case Types.FLOAT32_ARRAY:
          buffer.writeFloats((float[]) array, 0, length);
          return;
        case Types.FLOAT64_ARRAY:
          buffer.writeDoubles((double[]) array, 0, length);
          return;
        default:
          throw new IllegalArgumentException(
              "Unsupported primitive array type " + primitiveArrayTypeId);
      }
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
    private final int arrayOwnerBytes;

    public PrimitiveArraySerializer(TypeResolver typeResolver, Class<T> cls) {
      super(typeResolver.getConfig(), cls);
      this.config = typeResolver.getConfig();
      int ownerBytes = GraphMemoryEstimates.objectArrayBytes();
      if (!cls.isArray()) {
        ownerBytes = Math.addExact(ownerBytes, GraphMemoryEstimates.shallowObjectBytes(cls));
      }
      arrayOwnerBytes = ownerBytes;
    }

    @Override
    public final boolean readDataAlwaysAdvances() {
      return true;
    }

    protected final void reserveArray(ReadContext readContext, int length, int elemSize) {
      readContext.reserveGraphMemory(arrayOwnerBytes + (long) length * elemSize);
    }

    protected final short[] readShortBits(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      if (readContext.isPeerOutOfBandEnabled()) {
        MemoryBuffer buf = readContext.readBufferObject();
        int size = buf.remaining();
        if ((size & 1) != 0) {
          throwUnalignedBinarySize(size, 2);
        }
        int numElements = size >>> 1;
        buf.checkReadableBytes(size);
        reserveArray(readContext, numElements, 2);
        short[] values = new short[numElements];
        buf.readInt16ArrayBytes(values, size);
        return values;
      }
      int size = buffer.readVarUInt32Small7();
      if ((size & 1) != 0) {
        throwUnalignedBinarySize(size, 2);
      }
      if (size < 0) {
        throwNegativeBinarySize(size);
      }
      int numElements = size >>> 1;
      buffer.checkReadableBytes(size);
      reserveArray(readContext, numElements, 2);
      short[] values = new short[numElements];
      buffer.readInt16ArrayBytes(values, size);
      return values;
    }
  }

  private static void throwNegativeBinarySize(int size) {
    throw new DeserializationException("Binary body size must be non-negative: " + size);
  }

  private static void throwNegativeElementCount(int numElements) {
    throw new DeserializationException("Element count must be non-negative: " + numElements);
  }

  private static void throwUnalignedBinarySize(int size, int elemSize) {
    throw new DeserializationException(
        "Binary body size " + size + " is not aligned to element size " + elemSize);
  }

  public static final class BooleanArraySerializer extends PrimitiveArraySerializer<boolean[]> {
    public BooleanArraySerializer(TypeResolver typeResolver) {
      super(typeResolver, boolean[].class);
    }

    @Override
    public void write(WriteContext writeContext, boolean[] value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      if (writeContext.getBufferCallback() == null) {
        buffer.writeBooleansWithSize(value);
      } else {
        writeContext.writeBufferObject(
            new PrimitiveArrayBufferObject(value, Types.BOOL_ARRAY, 1, value.length));
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
        buf.checkReadableBytes(size);
        reserveArray(readContext, size, 1);
        boolean[] values = new boolean[size];
        buf.readBooleanArrayBytes(values, size);
        return values;
      }
      int size = buffer.readVarUInt32Small7();
      if (size < 0) {
        throwNegativeBinarySize(size);
      }
      buffer.checkReadableBytes(size);
      reserveArray(readContext, size, 1);
      boolean[] values = new boolean[size];
      buffer.readBooleanArrayBytes(values, size);
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
        buffer.writeBytesWithSize(value);
      } else {
        writeContext.writeBufferObject(
            new PrimitiveArrayBufferObject(value, Types.INT8_ARRAY, 1, value.length));
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
        buf.checkReadableBytes(size);
        reserveArray(readContext, size, 1);
        byte[] values = new byte[size];
        buf.readByteArrayBytes(values, size);
        return values;
      }
      int size = buffer.readVarUInt32Small7();
      if (size < 0) {
        throwNegativeBinarySize(size);
      }
      buffer.checkReadableBytes(size);
      reserveArray(readContext, size, 1);
      byte[] values = new byte[size];
      buffer.readByteArrayBytes(values, size);
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
        buffer.writeCharsWithSize(value);
      } else {
        writeContext.writeBufferObject(
            new PrimitiveArrayBufferObject(value, Types.UINT16_ARRAY, 2, value.length));
      }
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
        if ((size & 1) != 0) {
          throwUnalignedBinarySize(size, 2);
        }
        int numElements = size >>> 1;
        buf.checkReadableBytes(size);
        reserveArray(readContext, numElements, 2);
        char[] values = new char[numElements];
        buf.readCharArrayBytes(values, size);
        return values;
      }
      int size = buffer.readVarUInt32Small7();
      if ((size & 1) != 0) {
        throwUnalignedBinarySize(size, 2);
      }
      if (size < 0) {
        throwNegativeBinarySize(size);
      }
      int numElements = size >>> 1;
      buffer.checkReadableBytes(size);
      reserveArray(readContext, numElements, 2);
      char[] values = new char[numElements];
      buffer.readCharArrayBytes(values, size);
      return values;
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
        buffer.writeIntsWithSize(value);
      } else {
        writeContext.writeBufferObject(
            new PrimitiveArrayBufferObject(value, Types.INT32_ARRAY, 4, value.length));
      }
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
        if ((size & 3) != 0) {
          throwUnalignedBinarySize(size, 4);
        }
        int numElements = size >>> 2;
        buf.checkReadableBytes(size);
        reserveArray(readContext, numElements, 4);
        int[] values = new int[numElements];
        if (size > 0) {
          buf.readInt32ArrayBytes(values, size);
        }
        return values;
      }
      if (!config.isXlang() && config.compressIntArray()) {
        return readInt32Compressed(readContext);
      }
      int size = buffer.readVarUInt32Small7();
      if ((size & 3) != 0) {
        throwUnalignedBinarySize(size, 4);
      }
      if (size < 0) {
        throwNegativeBinarySize(size);
      }
      int numElements = size >>> 2;
      buffer.checkReadableBytes(size);
      reserveArray(readContext, numElements, 4);
      int[] values = new int[numElements];
      if (size > 0) {
        buffer.readInt32ArrayBytes(values, size);
      }
      return values;
    }

    private void writeInt32Compressed(MemoryBuffer buffer, int[] value) {
      buffer.writeVarUInt32Small7(value.length);
      for (int i : value) {
        buffer.writeVarInt32(i);
      }
    }

    private int[] readInt32Compressed(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = buffer.readVarUInt32Small7();
      if (numElements < 0) {
        throwNegativeElementCount(numElements);
      }
      buffer.checkReadableBytes(numElements);
      reserveArray(readContext, numElements, 4);
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
        buffer.writeLongsWithSize(value);
      } else {
        writeContext.writeBufferObject(
            new PrimitiveArrayBufferObject(value, Types.INT64_ARRAY, 8, value.length));
      }
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
        if ((size & 7) != 0) {
          throwUnalignedBinarySize(size, 8);
        }
        int numElements = size >>> 3;
        buf.checkReadableBytes(size);
        reserveArray(readContext, numElements, 8);
        long[] values = new long[numElements];
        if (size > 0) {
          buf.readInt64ArrayBytes(values, size);
        }
        return values;
      }
      if (compressLongArray) {
        return readInt64Compressed(readContext, config.longEncoding());
      }
      int size = buffer.readVarUInt32Small7();
      if ((size & 7) != 0) {
        throwUnalignedBinarySize(size, 8);
      }
      if (size < 0) {
        throwNegativeBinarySize(size);
      }
      int numElements = size >>> 3;
      buffer.checkReadableBytes(size);
      reserveArray(readContext, numElements, 8);
      long[] values = new long[numElements];
      if (size > 0) {
        buffer.readInt64ArrayBytes(values, size);
      }
      return values;
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

    private long[] readInt64Compressed(ReadContext readContext, Int64Encoding longEncoding) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = buffer.readVarUInt32Small7();
      if (numElements < 0) {
        throwNegativeElementCount(numElements);
      }
      buffer.checkReadableBytes(numElements);
      reserveArray(readContext, numElements, 8);
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
        buffer.writeFloatsWithSize(value);
      } else {
        writeContext.writeBufferObject(
            new PrimitiveArrayBufferObject(value, Types.FLOAT32_ARRAY, 4, value.length));
      }
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
        if ((size & 3) != 0) {
          throwUnalignedBinarySize(size, 4);
        }
        int numElements = size >>> 2;
        buf.checkReadableBytes(size);
        reserveArray(readContext, numElements, 4);
        float[] values = new float[numElements];
        buf.readFloat32ArrayBytes(values, size);
        return values;
      }
      int size = buffer.readVarUInt32Small7();
      if ((size & 3) != 0) {
        throwUnalignedBinarySize(size, 4);
      }
      if (size < 0) {
        throwNegativeBinarySize(size);
      }
      int numElements = size >>> 2;
      buffer.checkReadableBytes(size);
      reserveArray(readContext, numElements, 4);
      float[] values = new float[numElements];
      buffer.readFloat32ArrayBytes(values, size);
      return values;
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
        buffer.writeDoublesWithSize(value);
      } else {
        writeContext.writeBufferObject(
            new PrimitiveArrayBufferObject(value, Types.FLOAT64_ARRAY, 8, value.length));
      }
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
        if ((size & 7) != 0) {
          throwUnalignedBinarySize(size, 8);
        }
        int numElements = size >>> 3;
        buf.checkReadableBytes(size);
        reserveArray(readContext, numElements, 8);
        double[] values = new double[numElements];
        buf.readFloat64ArrayBytes(values, size);
        return values;
      }
      int size = buffer.readVarUInt32Small7();
      if ((size & 7) != 0) {
        throwUnalignedBinarySize(size, 8);
      }
      if (size < 0) {
        throwNegativeBinarySize(size);
      }
      int numElements = size >>> 3;
      buffer.checkReadableBytes(size);
      reserveArray(readContext, numElements, 8);
      double[] values = new double[numElements];
      buffer.readFloat64ArrayBytes(values, size);
      return values;
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
      buffer.writeShortsWithSize(value);
    } else {
      writeContext.writeBufferObject(
          new PrimitiveArrayBufferObject(value, Types.INT16_ARRAY, 2, value.length));
    }
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

  public static PrimitiveArrayBufferObject byteArrayBufferObject(byte[] array) {
    return new PrimitiveArrayBufferObject(array, Types.INT8_ARRAY, 1, array.length);
  }
}

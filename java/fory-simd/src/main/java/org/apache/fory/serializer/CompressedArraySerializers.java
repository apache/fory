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
import org.apache.fory.Fory;
import org.apache.fory.ThreadSafeFory;
import org.apache.fory.context.CopyContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.PrimitiveArraySerializers.PrimitiveArrayBufferObject;
import org.apache.fory.serializer.PrimitiveArraySerializers.PrimitiveArraySerializer;
import org.apache.fory.util.ArrayCompressionUtils;
import org.apache.fory.util.PrimitiveArrayCompressionType;

/**
 * Compressed array serializers using Java 16+ Vector API for SIMD acceleration.
 *
 * <p>To use these serializers, simply call {@code CompressedArraySerializers.register(fory)} on
 * your Fory instance. These will override the default array serializers for {@code int[]} and
 * {@code long[]} arrays with compressed versions that can significantly reduce serialization size
 * when arrays contain values that fit in smaller primitive types.
 */
public final class CompressedArraySerializers {

  private CompressedArraySerializers() {
    // Utility class
  }

  /**
   * Register compressed array serializers with the given Fory instance.
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * Fory fory = Fory.builder()
   *     .withConfig(Config.compressIntArray(true).compressLongArray(true))
   *     .build();
   * CompressedArraySerializers.registerSerializers(fory);
   * }</pre>
   *
   * @param fory the Fory instance to register serializers with
   */
  public static void registerSerializers(Fory fory) {
    registerIfEnabled(fory);
  }

  /**
   * Register compressed array serializers based on Fory configuration flags. This is called
   * internally by registerSerializers().
   *
   * @param fory the Fory instance to configure
   */
  static void registerIfEnabled(Fory fory) {
    ClassResolver resolver = (ClassResolver) fory.getTypeResolver();
    boolean compressInt = resolver.getConfig().compressIntArray();
    boolean compressLong = resolver.getConfig().compressLongArray();

    if (compressInt) {
      resolver.registerInternalSerializer(int[].class, new CompressedIntArraySerializer(resolver));
    }
    if (compressLong) {
      resolver.registerInternalSerializer(
          long[].class, new CompressedLongArraySerializer(resolver));
    }
  }

  /**
   * Register compressed array serializers with the given Fory instance.
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * ThreadSafeFory fory = Fory.builder()
   *     .withConfig(Config.compressIntArray(true).compressLongArray(true))
   *     .buildThreadSafeFory();
   * CompressedArraySerializers.registerSerializers(fory);
   * }</pre>
   *
   * @param fory the ThreadSafeFory instance to register serializers with
   */
  public static void registerIfEnabled(ThreadSafeFory fory) {
    fory.registerCallback(CompressedArraySerializers::registerIfEnabled);
  }

  /**
   * Register compressed array serializers with the given Fory instance.
   *
   * <p>This will replace the default {@code int[]} and {@code long[]} serializers with compressed
   * versions that use the Java 16+ Vector API for analysis and can serialize arrays more
   * efficiently when values fit in smaller data types.
   *
   * @param fory the Fory instance to register serializers with
   */
  public static void register(Fory fory) {
    ClassResolver resolver = (ClassResolver) fory.getTypeResolver();
    resolver.registerInternalSerializer(int[].class, new CompressedIntArraySerializer(resolver));
    resolver.registerInternalSerializer(long[].class, new CompressedLongArraySerializer(resolver));
  }

  /** Register compressed array serializers with the given Fory instance. */
  public static void register(ThreadSafeFory fory) {
    fory.registerCallback(CompressedArraySerializers::register);
  }

  public static final class CompressedIntArraySerializer extends PrimitiveArraySerializer<int[]> {

    public CompressedIntArraySerializer(TypeResolver typeResolver) {
      super(typeResolver, int[].class);
    }

    @Override
    public void write(WriteContext writeContext, int[] value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      if (writeContext.getBufferCallback() != null) {
        writeContext.writeBufferObject(
            new PrimitiveArrayBufferObject(value, Platform.INT_ARRAY_OFFSET, 4, value.length));
        return;
      }

      final PrimitiveArrayCompressionType compressionType =
          PrimitiveArrayCompressionType.IntArrayCompression.determine(value);
      buffer.writeByte((byte) compressionType.getValue());

      switch (compressionType) {
        case NONE:
          writeUncompressed(buffer, value);
          break;
        case INT_TO_BYTE:
          writeCompressedBytes(buffer, value);
          break;
        case INT_TO_SHORT:
          writeCompressedShorts(buffer, value);
          break;
        default:
          throw new IllegalStateException("Unsupported compression type: " + compressionType);
      }
    }

    private void writeUncompressed(MemoryBuffer buffer, int[] value) {
      int size = Math.multiplyExact(value.length, 4);
      buffer.writePrimitiveArrayWithSize(value, Platform.INT_ARRAY_OFFSET, size);
    }

    private void writeCompressedBytes(MemoryBuffer buffer, int[] value) {
      byte[] compressed = ArrayCompressionUtils.compressToBytes(value);
      int byteOffset = Platform.INT_ARRAY_OFFSET;
      buffer.writePrimitiveArrayWithSize(compressed, byteOffset, compressed.length);
    }

    private void writeCompressedShorts(MemoryBuffer buffer, int[] value) {
      short[] compressed = ArrayCompressionUtils.compressToShorts(value);
      int shortOffset = Platform.SHORT_ARRAY_OFFSET;
      int shortElemSize = 2;
      int size = Math.multiplyExact(compressed.length, shortElemSize);
      buffer.writePrimitiveArrayWithSize(compressed, shortOffset, size);
    }

    @Override
    public int[] copy(CopyContext copyContext, int[] originArray) {
      return Arrays.copyOf(originArray, originArray.length);
    }

    @Override
    public int[] read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      if (readContext.isPeerOutOfBandEnabled()) {
        return readFromBufferObject(readContext);
      }

      int compressionTypeValue = buffer.readByte() & 0xFF;
      PrimitiveArrayCompressionType compressionType =
          PrimitiveArrayCompressionType.fromValue(compressionTypeValue);

      if (!PrimitiveArrayCompressionType.IntArrayCompression.isSupported(compressionType)) {
        throw new IllegalStateException("Unsupported int[] compression type: " + compressionType);
      }

      switch (compressionType) {
        case INT_TO_BYTE:
          return readCompressedFromBytes(buffer);
        case INT_TO_SHORT:
          return readCompressedFromShorts(buffer);
        case NONE:
          return readUncompressed(buffer);
        default:
          throw new IllegalStateException("Unsupported compression type: " + compressionType);
      }
    }

    private int[] readFromBufferObject(ReadContext readContext) {
      MemoryBuffer buf = readContext.readBufferObject();
      int size = buf.remaining();
      int numElements = size / 4;
      int[] values = new int[numElements];
      if (size > 0) {
        buf.copyToUnsafe(0, values, Platform.INT_ARRAY_OFFSET, size);
      }
      return values;
    }

    private int[] readCompressedFromBytes(MemoryBuffer buffer) {
      int size = buffer.readVarUInt32Small7();
      int byteOffset = Platform.BYTE_ARRAY_OFFSET;
      byte[] values = new byte[size];
      buffer.readToUnsafe(values, byteOffset, size);
      return ArrayCompressionUtils.decompressFromBytes(values);
    }

    private int[] readCompressedFromShorts(MemoryBuffer buffer) {
      int size = buffer.readVarUInt32Small7();
      int shortOffset = Platform.SHORT_ARRAY_OFFSET;
      int shortElemSize = 2;
      int numElements = size / shortElemSize;
      short[] values = new short[numElements];
      buffer.readToUnsafe(values, shortOffset, size);
      return ArrayCompressionUtils.decompressFromShorts(values);
    }

    private int[] readUncompressed(MemoryBuffer buffer) {
      int size = buffer.readVarUInt32Small7();
      int numElements = size / 4;
      int[] values = new int[numElements];
      if (size > 0) {
        buffer.readToUnsafe(values, Platform.INT_ARRAY_OFFSET, size);
      }
      return values;
    }
  }

  public static final class CompressedLongArraySerializer extends PrimitiveArraySerializer<long[]> {

    public CompressedLongArraySerializer(TypeResolver typeResolver) {
      super(typeResolver, long[].class);
    }

    @Override
    public void write(WriteContext writeContext, long[] value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      if (writeContext.getBufferCallback() != null) {
        writeContext.writeBufferObject(
            new PrimitiveArrayBufferObject(value, Platform.LONG_ARRAY_OFFSET, 8, value.length));
        return;
      }

      final PrimitiveArrayCompressionType compressionType =
          PrimitiveArrayCompressionType.LongArrayCompression.determine(value);
      buffer.writeByte((byte) compressionType.getValue());

      switch (compressionType) {
        case LONG_TO_INT:
          writeCompressedInts(buffer, value);
          break;
        case NONE:
          writeUncompressed(buffer, value);
          break;
        default:
          throw new IllegalStateException("Unsupported compression type: " + compressionType);
      }
    }

    private void writeCompressedInts(MemoryBuffer buffer, long[] value) {
      int[] compressed = ArrayCompressionUtils.compressToInts(value);
      var intOffset = Platform.INT_ARRAY_OFFSET;
      var intElemSize = 4;
      int size = Math.multiplyExact(compressed.length, intElemSize);
      buffer.writePrimitiveArrayWithSize(compressed, intOffset, size);
    }

    private void writeUncompressed(MemoryBuffer buffer, long[] value) {
      int size = Math.multiplyExact(value.length, 8);
      buffer.writePrimitiveArrayWithSize(value, Platform.LONG_ARRAY_OFFSET, size);
    }

    @Override
    public long[] copy(CopyContext copyContext, long[] originArray) {
      return Arrays.copyOf(originArray, originArray.length);
    }

    @Override
    public long[] read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      if (readContext.isPeerOutOfBandEnabled()) {
        return readFromBufferObject(readContext);
      }

      int compressionTypeValue = buffer.readByte() & 0xFF;
      PrimitiveArrayCompressionType compressionType =
          PrimitiveArrayCompressionType.fromValue(compressionTypeValue);

      if (!PrimitiveArrayCompressionType.LongArrayCompression.isSupported(compressionType)) {
        throw new IllegalStateException("Unsupported long[] compression type: " + compressionType);
      }

      switch (compressionType) {
        case LONG_TO_INT:
          return readCompressedFromInts(buffer);
        case NONE:
          return readUncompressed(buffer);
        default:
          throw new IllegalStateException("Unsupported compression type: " + compressionType);
      }
    }

    private long[] readFromBufferObject(ReadContext readContext) {
      MemoryBuffer buf = readContext.readBufferObject();
      int size = buf.remaining();
      int numElements = size / 8;
      long[] values = new long[numElements];
      if (size > 0) {
        buf.copyToUnsafe(0, values, Platform.LONG_ARRAY_OFFSET, size);
      }
      return values;
    }

    private long[] readCompressedFromInts(MemoryBuffer buffer) {
      int size = buffer.readVarUInt32Small7();
      int intOffset = Platform.INT_ARRAY_OFFSET;
      int intElemSize = 4;
      int numElements = size / intElemSize;
      int[] values = new int[numElements];
      if (size > 0) {
        buffer.readToUnsafe(values, intOffset, size);
      }
      return ArrayCompressionUtils.decompressFromInts(values);
    }

    private long[] readUncompressed(MemoryBuffer buffer) {
      int size = buffer.readVarUInt32Small7();
      int numElements = size / 8;
      long[] values = new long[numElements];
      if (size > 0) {
        buffer.readToUnsafe(values, Platform.LONG_ARRAY_OFFSET, size);
      }
      return values;
    }
  }
}

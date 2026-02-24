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

import java.lang.reflect.Array;
import java.util.Arrays;
import org.apache.fory.Fory;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.LongEncoding;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.RefResolver;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeInfoHolder;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.collection.CollectionFlags;
import org.apache.fory.serializer.collection.ForyArrayAsListSerializer;
import org.apache.fory.type.GenericType;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.util.Preconditions;

/** Serializers for array types. */
public class ArraySerializers {

  /** May be multi-dimension array, or multi-dimension primitive array. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static final class ObjectArraySerializer<T> extends Serializer<T[]> {
    private final Class<T> innerType;
    private final Serializer componentTypeSerializer;
    private final TypeInfoHolder classInfoHolder;
    private final int[] stubDims;
    private final GenericType componentGenericType;

    public ObjectArraySerializer(Fory fory, Class<T[]> cls) {
      super(fory, cls);
      fory.getClassResolver().setSerializer(cls, this);
      Preconditions.checkArgument(cls.isArray());
      Class<?> t = cls;
      Class<?> innerType = cls;
      int dimension = 0;
      while (t != null && t.isArray()) {
        dimension++;
        t = t.getComponentType();
        if (t != null) {
          innerType = t;
        }
      }
      this.innerType = (Class<T>) innerType;
      Class<?> componentType = cls.getComponentType();
      componentGenericType = fory.getClassResolver().buildGenericType(componentType);
      if (fory.getClassResolver().isMonomorphic(componentType)) {
        if (fory.isCrossLanguage()) {
          this.componentTypeSerializer = null;
        } else {
          this.componentTypeSerializer = fory.getClassResolver().getSerializer(componentType);
        }
      } else {
        // TODO add TypeInfo cache for non-final component type.
        this.componentTypeSerializer = null;
      }
      this.stubDims = new int[dimension];
      classInfoHolder = fory.getClassResolver().nilTypeInfoHolder();
    }

    @Override
    public void write(MemoryBuffer buffer, T[] arr) {
      if (!isJava) {
        int len = arr.length;
        buffer.writeVarUint32Small7(len);
        // TODO(chaokunyang) use generics by creating component serializers to multi-dimension array.
        for (T t : arr) {
          fory.writeRef(buffer, t);
        }
        return;
      }
      int len = arr.length;
      RefResolver refResolver = fory.getRefResolver();
      Serializer componentSerializer = this.componentTypeSerializer;
      int header = componentSerializer != null ? 0b1 : 0b0;
      buffer.writeVarUint32Small7(len << 1 | header);
      if (componentSerializer != null) {
        for (T t : arr) {
          if (!refResolver.writeRefOrNull(buffer, t)) {
            componentSerializer.write(buffer, t);
          }
        }
      } else {
        Fory fory = this.fory;
        ClassResolver classResolver = fory.getClassResolver();
        TypeInfo typeInfo = null;
        Class<?> elemClass = null;
        for (T t : arr) {
          if (!refResolver.writeRefOrNull(buffer, t)) {
            Class<?> clz = t.getClass();
            if (clz != elemClass) {
              elemClass = clz;
              typeInfo = classResolver.getTypeInfo(clz);
            }
            fory.writeNonRef(buffer, t, typeInfo);
          }
        }
      }
    }

    @Override
    public T[] copy(T[] originArray) {
      int length = originArray.length;
      Object[] newArray = newArray(length);
      if (needToCopyRef) {
        fory.reference(originArray, newArray);
      }
      Serializer componentSerializer = this.componentTypeSerializer;
      if (componentSerializer != null) {
        if (componentSerializer.isImmutable()) {
          System.arraycopy(originArray, 0, newArray, 0, length);
        } else {
          for (int i = 0; i < length; i++) {
            newArray[i] = componentSerializer.copy(originArray[i]);
          }
        }
      } else {
        for (int i = 0; i < length; i++) {
          newArray[i] = fory.copyObject(originArray[i]);
        }
      }
      return (T[]) newArray;
    }

    @Override
    public T[] read(MemoryBuffer buffer) {
      if (!isJava) {
        int numElements = buffer.readVarUint32Small7();
        Object[] value = newArray(numElements);
        fory.getGenerics().pushGenericType(componentGenericType);
        for (int i = 0; i < numElements; i++) {
          Object x = fory.readRef(buffer);
          value[i] = x;
        }
        fory.getGenerics().popGenericType();
        return (T[]) value;
      }
      int numElements = buffer.readVarUint32Small7();
      boolean isFinal = (numElements & 0b1) != 0;
      numElements >>>= 1;
      Object[] value = newArray(numElements);
      RefResolver refResolver = fory.getRefResolver();
      refResolver.reference(value);
      if (isFinal) {
        final Serializer componentTypeSerializer = this.componentTypeSerializer;
        for (int i = 0; i < numElements; i++) {
          Object elem;
          int nextReadRefId = refResolver.tryPreserveRefId(buffer);
          if (nextReadRefId >= Fory.NOT_NULL_VALUE_FLAG) {
            elem = componentTypeSerializer.read(buffer);
            refResolver.setReadObject(nextReadRefId, elem);
          } else {
            elem = refResolver.getReadObject();
          }
          value[i] = elem;
        }
      } else {
        Fory fory = this.fory;
        TypeInfoHolder classInfoHolder = this.classInfoHolder;
        for (int i = 0; i < numElements; i++) {
          int nextReadRefId = refResolver.tryPreserveRefId(buffer);
          Object o;
          if (nextReadRefId >= Fory.NOT_NULL_VALUE_FLAG) {
            // ref value or not-null value
            o = fory.readNonRef(buffer, classInfoHolder);
            refResolver.setReadObject(nextReadRefId, o);
          } else {
            o = refResolver.getReadObject();
          }
          value[i] = o;
        }
      }
      return (T[]) value;
    }

    private Object[] newArray(int numElements) {
      Object[] value;
      if ((Class) type == Object[].class) {
        value = new Object[numElements];
      } else {
        stubDims[0] = numElements;
        value = (Object[]) Array.newInstance(innerType, stubDims);
      }
      return value;
    }
  }

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

  // Implement all read/write methods in subclasses to avoid
  // virtual method call cost.
  public abstract static class PrimitiveArraySerializer<T>
      extends Serializers.CrossLanguageCompatibleSerializer<T> {

    public PrimitiveArraySerializer(Fory fory, Class<T> cls) {
      super(fory, cls);
    }
  }

  public static final class BooleanArraySerializer extends PrimitiveArraySerializer<boolean[]> {

    public BooleanArraySerializer(Fory fory) {
      super(fory, boolean[].class);
    }

    @Override
    public void write(MemoryBuffer buffer, boolean[] value) {
      if (fory.getBufferCallback() == null) {
        int size = Math.multiplyExact(value.length, 1);
        buffer.writePrimitiveArrayWithSize(value, Platform.BOOLEAN_ARRAY_OFFSET, size);
      } else {
        fory.writeBufferObject(
            buffer,
            new PrimitiveArrayBufferObject(value, Platform.BOOLEAN_ARRAY_OFFSET, 1, value.length));
      }
    }

    @Override
    public boolean[] copy(boolean[] originArray) {
      return Arrays.copyOf(originArray, originArray.length);
    }

    @Override
    public boolean[] read(MemoryBuffer buffer) {
      if (fory.isPeerOutOfBandEnabled()) {
        MemoryBuffer buf = fory.readBufferObject(buffer);
        int size = buf.remaining();
        int numElements = size;
        boolean[] values = new boolean[numElements];
        buf.copyToUnsafe(0, values, Platform.BOOLEAN_ARRAY_OFFSET, size);
        return values;
      } else {
        int size = buffer.readVarUint32Small7();
        int numElements = size;
        boolean[] values = new boolean[numElements];
        buffer.readToUnsafe(values, Platform.BOOLEAN_ARRAY_OFFSET, size);
        return values;
      }
    }
  }

  public static final class ByteArraySerializer extends PrimitiveArraySerializer<byte[]> {

    public ByteArraySerializer(Fory fory) {
      super(fory, byte[].class);
    }

    @Override
    public void write(MemoryBuffer buffer, byte[] value) {
      if (fory.getBufferCallback() == null) {
        int size = Math.multiplyExact(value.length, 1);
        buffer.writePrimitiveArrayWithSize(value, Platform.BYTE_ARRAY_OFFSET, size);
      } else {
        fory.writeBufferObject(
            buffer,
            new PrimitiveArrayBufferObject(value, Platform.BYTE_ARRAY_OFFSET, 1, value.length));
      }
    }

    @Override
    public byte[] copy(byte[] originArray) {
      return Arrays.copyOf(originArray, originArray.length);
    }

    @Override
    public byte[] read(MemoryBuffer buffer) {
      if (fory.isPeerOutOfBandEnabled()) {
        MemoryBuffer buf = fory.readBufferObject(buffer);
        int size = buf.remaining();
        byte[] values = new byte[size];
        buf.copyToUnsafe(0, values, Platform.BYTE_ARRAY_OFFSET, size);
        return values;
      } else {
        int size = buffer.readVarUint32Small7();
        byte[] values = new byte[size];
        buffer.readToUnsafe(values, Platform.BYTE_ARRAY_OFFSET, size);
        return values;
      }
    }
  }

  public static final class CharArraySerializer extends PrimitiveArraySerializer<char[]> {

    public CharArraySerializer(Fory fory) {
      super(fory, char[].class);
    }

    @Override
    public void write(MemoryBuffer buffer, char[] value) {
      if (!isJava) {
        throw new UnsupportedOperationException();
      }
      if (fory.getBufferCallback() == null) {
        int size = Math.multiplyExact(value.length, 2);
        if (Platform.IS_LITTLE_ENDIAN) {
          buffer.writePrimitiveArrayWithSize(value, Platform.CHAR_ARRAY_OFFSET, size);
        } else {
          writeCharBySwapEndian(buffer, value);
        }
      } else {
        fory.writeBufferObject(
            buffer,
            new PrimitiveArrayBufferObject(value, Platform.CHAR_ARRAY_OFFSET, 2, value.length));
      }
    }

    private void writeCharBySwapEndian(MemoryBuffer buffer, char[] value) {
      int idx = buffer.writerIndex();
      int length = value.length;
      buffer.ensure(idx + 5 + length * 2);
      idx += buffer._unsafeWriteVarUint32(length * 2);
      for (int i = 0; i < length; i++) {
        buffer._unsafePutInt16(idx + i * 2, (short) value[i]);
      }
      buffer._unsafeWriterIndex(idx + length * 2);
    }

    @Override
    public char[] copy(char[] originArray) {
      return Arrays.copyOf(originArray, originArray.length);
    }

    @Override
    public char[] read(MemoryBuffer buffer) {
      if (!isJava) {
        throw new UnsupportedOperationException();
      }
      if (fory.isPeerOutOfBandEnabled()) {
        MemoryBuffer buf = fory.readBufferObject(buffer);
        int size = buf.remaining();
        int numElements = size / 2;
        char[] values = new char[numElements];
        if (Platform.IS_LITTLE_ENDIAN) {
          buf.copyToUnsafe(0, values, Platform.CHAR_ARRAY_OFFSET, size);
        } else {
          readCharBySwapEndian(buf, values, numElements);
        }
        return values;
      } else {
        int size = buffer.readVarUint32Small7();
        int numElements = size / 2;
        char[] values = new char[numElements];
        if (Platform.IS_LITTLE_ENDIAN) {
          buffer.readToUnsafe(values, Platform.CHAR_ARRAY_OFFSET, size);
        } else {
          readCharBySwapEndian(buffer, values, numElements);
        }
        return values;
      }
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

    public ShortArraySerializer(Fory fory) {
      super(fory, short[].class);
    }

    @Override
    public void write(MemoryBuffer buffer, short[] value) {
      if (fory.getBufferCallback() == null) {
        int size = Math.multiplyExact(value.length, 2);
        if (Platform.IS_LITTLE_ENDIAN) {
          buffer.writePrimitiveArrayWithSize(value, Platform.SHORT_ARRAY_OFFSET, size);
        } else {
          writeInt16BySwapEndian(buffer, value);
        }
      } else {
        fory.writeBufferObject(
            buffer,
            new PrimitiveArrayBufferObject(value, Platform.SHORT_ARRAY_OFFSET, 2, value.length));
      }
    }

    private void writeInt16BySwapEndian(MemoryBuffer buffer, short[] value) {
      int idx = buffer.writerIndex();
      int length = value.length;
      buffer.ensure(idx + 5 + length * 2);
      idx += buffer._unsafeWriteVarUint32(length * 2);
      for (int i = 0; i < length; i++) {
        buffer._unsafePutInt16(idx + i * 2, value[i]);
      }
      buffer._unsafeWriterIndex(idx + length * 2);
    }

    @Override
    public short[] copy(short[] originArray) {
      return Arrays.copyOf(originArray, originArray.length);
    }

    @Override
    public short[] read(MemoryBuffer buffer) {
      if (fory.isPeerOutOfBandEnabled()) {
        MemoryBuffer buf = fory.readBufferObject(buffer);
        int size = buf.remaining();
        int numElements = size / 2;
        short[] values = new short[numElements];
        if (Platform.IS_LITTLE_ENDIAN) {
          buf.copyToUnsafe(0, values, Platform.SHORT_ARRAY_OFFSET, size);
        } else {
          readInt16BySwapEndian(buf, values, numElements);
        }
        return values;
      } else {
        int size = buffer.readVarUint32Small7();
        int numElements = size / 2;
        short[] values = new short[numElements];
        if (Platform.IS_LITTLE_ENDIAN) {
          buffer.readToUnsafe(values, Platform.SHORT_ARRAY_OFFSET, size);
        } else {
          readInt16BySwapEndian(buffer, values, numElements);
        }
        return values;
      }
    }

    private void readInt16BySwapEndian(MemoryBuffer buffer, short[] values, int numElements) {
      int idx = buffer.readerIndex();
      int size = numElements * 2;
      buffer.checkReadableBytes(size);
      for (int i = 0; i < numElements; i++) {
        values[i] = buffer._unsafeGetInt16(idx + i * 2);
      }
      buffer._increaseReaderIndexUnsafe(size);
    }
  }

  public static final class IntArraySerializer extends PrimitiveArraySerializer<int[]> {

    public IntArraySerializer(Fory fory) {
      super(fory, int[].class);
    }

    @Override
    public void write(MemoryBuffer buffer, int[] value) {
      if (fory.getBufferCallback() == null) {
        if (fory.getConfig().compressIntArray()) {
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
        fory.writeBufferObject(
            buffer,
            new PrimitiveArrayBufferObject(value, Platform.INT_ARRAY_OFFSET, 4, value.length));
      }
    }

    private void writeInt32BySwapEndian(MemoryBuffer buffer, int[] value) {
      int idx = buffer.writerIndex();
      int length = value.length;
      buffer.ensure(idx + 5 + length * 4);
      idx += buffer._unsafeWriteVarUint32(length * 4);
      for (int i = 0; i < length; i++) {
        buffer._unsafePutInt32(idx + i * 4, value[i]);
      }
      buffer._unsafeWriterIndex(idx + length * 4);
    }

    @Override
    public int[] copy(int[] originArray) {
      return Arrays.copyOf(originArray, originArray.length);
    }

    @Override
    public int[] read(MemoryBuffer buffer) {
      if (fory.isPeerOutOfBandEnabled()) {
        MemoryBuffer buf = fory.readBufferObject(buffer);
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
      if (fory.getConfig().compressIntArray()) {
        return readInt32Compressed(buffer);
      }
      int size = buffer.readVarUint32Small7();
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
      buffer.writeVarUint32Small7(value.length);
      for (int i : value) {
        buffer.writeVarInt32(i);
      }
    }

    private int[] readInt32Compressed(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32Small7();
      int[] values = new int[numElements];

      for (int i = 0; i < numElements; i++) {
        values[i] = buffer.readVarInt32();
      }
      return values;
    }
  }

  public static final class LongArraySerializer extends PrimitiveArraySerializer<long[]> {
    private final boolean compressLongArray;

    public LongArraySerializer(Fory fory) {
      super(fory, long[].class);
      compressLongArray =
          fory.getConfig().compressLongArray()
              && fory.getConfig().longEncoding() != LongEncoding.FIXED;
    }

    @Override
    public void write(MemoryBuffer buffer, long[] value) {
      if (fory.getBufferCallback() == null) {
        if (compressLongArray) {
          writeInt64Compressed(buffer, value, fory.getConfig().longEncoding());
          return;
        }
        int size = Math.multiplyExact(value.length, 8);
        if (Platform.IS_LITTLE_ENDIAN) {
          buffer.writePrimitiveArrayWithSize(value, Platform.LONG_ARRAY_OFFSET, size);
        } else {
          writeInt64BySwapEndian(buffer, value);
        }
      } else {
        fory.writeBufferObject(
            buffer,
            new PrimitiveArrayBufferObject(value, Platform.LONG_ARRAY_OFFSET, 8, value.length));
      }
    }

    private void writeInt64BySwapEndian(MemoryBuffer buffer, long[] value) {
      int idx = buffer.writerIndex();
      int length = value.length;
      buffer.ensure(idx + 5 + length * 8);
      idx += buffer._unsafeWriteVarUint32(length * 8);
      for (int i = 0; i < length; i++) {
        buffer._unsafePutInt64(idx + i * 8, value[i]);
      }
      buffer._unsafeWriterIndex(idx + length * 8);
    }

    @Override
    public long[] copy(long[] originArray) {
      return Arrays.copyOf(originArray, originArray.length);
    }

    @Override
    public long[] read(MemoryBuffer buffer) {
      if (fory.isPeerOutOfBandEnabled()) {
        MemoryBuffer buf = fory.readBufferObject(buffer);
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
        return readInt64Compressed(buffer, fory.getConfig().longEncoding());
      }
      int size = buffer.readVarUint32Small7();
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
        MemoryBuffer buffer, long[] value, LongEncoding longEncoding) {
      int length = value.length;
      buffer.writeVarUint32Small7(length);

      if (longEncoding == LongEncoding.TAGGED) {
        for (int i = 0; i < length; i++) {
          buffer.writeTaggedInt64(value[i]);
        }
        return;
      }
      for (int i = 0; i < length; i++) {
        buffer.writeVarInt64(value[i]);
      }
    }

    private long[] readInt64Compressed(MemoryBuffer buffer, LongEncoding longEncoding) {
      int numElements = buffer.readVarUint32Small7();
      long[] values = new long[numElements];

      if (longEncoding == LongEncoding.TAGGED) {
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

    public FloatArraySerializer(Fory fory) {
      super(fory, float[].class);
    }

    @Override
    public void write(MemoryBuffer buffer, float[] value) {
      if (fory.getBufferCallback() == null) {
        int size = Math.multiplyExact(value.length, 4);
        if (Platform.IS_LITTLE_ENDIAN) {
          buffer.writePrimitiveArrayWithSize(value, Platform.FLOAT_ARRAY_OFFSET, size);
        } else {
          writeFloat32BySwapEndian(buffer, value);
        }
      } else {
        fory.writeBufferObject(
            buffer,
            new PrimitiveArrayBufferObject(value, Platform.FLOAT_ARRAY_OFFSET, 4, value.length));
      }
    }

    private void writeFloat32BySwapEndian(MemoryBuffer buffer, float[] value) {
      int idx = buffer.writerIndex();
      int length = value.length;
      buffer.ensure(idx + 5 + length * 4);
      idx += buffer._unsafeWriteVarUint32(length * 4);
      for (int i = 0; i < length; i++) {
        buffer._unsafePutInt32(idx + i * 4, Float.floatToRawIntBits(value[i]));
      }
      buffer._unsafeWriterIndex(idx + length * 4);
    }

    @Override
    public float[] copy(float[] originArray) {
      return Arrays.copyOf(originArray, originArray.length);
    }

    @Override
    public float[] read(MemoryBuffer buffer) {
      if (fory.isPeerOutOfBandEnabled()) {
        MemoryBuffer buf = fory.readBufferObject(buffer);
        int size = buf.remaining();
        int numElements = size / 4;
        float[] values = new float[numElements];
        if (Platform.IS_LITTLE_ENDIAN) {
          buf.copyToUnsafe(0, values, Platform.FLOAT_ARRAY_OFFSET, size);
        } else {
          readFloat32BySwapEndian(buf, values, numElements);
        }
        return values;
      } else {
        int size = buffer.readVarUint32Small7();
        int numElements = size / 4;
        float[] values = new float[numElements];
        if (Platform.IS_LITTLE_ENDIAN) {
          buffer.readToUnsafe(values, Platform.FLOAT_ARRAY_OFFSET, size);
        } else {
          readFloat32BySwapEndian(buffer, values, numElements);
        }
        return values;
      }
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

    public DoubleArraySerializer(Fory fory) {
      super(fory, double[].class);
    }

    @Override
    public void write(MemoryBuffer buffer, double[] value) {
      if (fory.getBufferCallback() == null) {
        int size = Math.multiplyExact(value.length, 8);
        if (Platform.IS_LITTLE_ENDIAN) {
          buffer.writePrimitiveArrayWithSize(value, Platform.DOUBLE_ARRAY_OFFSET, size);
        } else {
          writeFloat64BySwapEndian(buffer, value);
        }
      } else {
        fory.writeBufferObject(
            buffer,
            new PrimitiveArrayBufferObject(value, Platform.DOUBLE_ARRAY_OFFSET, 8, value.length));
      }
    }

    private void writeFloat64BySwapEndian(MemoryBuffer buffer, double[] value) {
      int idx = buffer.writerIndex();
      int length = value.length;
      buffer.ensure(idx + 5 + length * 8);
      idx += buffer._unsafeWriteVarUint32(length * 8);
      for (int i = 0; i < length; i++) {
        buffer._unsafePutInt64(idx + i * 8, Double.doubleToRawLongBits(value[i]));
      }
      buffer._unsafeWriterIndex(idx + length * 8);
    }

    @Override
    public double[] copy(double[] originArray) {
      return Arrays.copyOf(originArray, originArray.length);
    }

    @Override
    public double[] read(MemoryBuffer buffer) {
      if (fory.isPeerOutOfBandEnabled()) {
        MemoryBuffer buf = fory.readBufferObject(buffer);
        int size = buf.remaining();
        int numElements = size / 8;
        double[] values = new double[numElements];
        if (Platform.IS_LITTLE_ENDIAN) {
          buf.copyToUnsafe(0, values, Platform.DOUBLE_ARRAY_OFFSET, size);
        } else {
          readFloat64BySwapEndian(buf, values, numElements);
        }
        return values;
      } else {
        int size = buffer.readVarUint32Small7();
        int numElements = size / 8;
        double[] values = new double[numElements];
        if (Platform.IS_LITTLE_ENDIAN) {
          buffer.readToUnsafe(values, Platform.DOUBLE_ARRAY_OFFSET, size);
        } else {
          readFloat64BySwapEndian(buffer, values, numElements);
        }
        return values;
      }
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

  public static final class StringArraySerializer extends Serializer<String[]> {
    private final StringSerializer stringSerializer;
    private final ForyArrayAsListSerializer collectionSerializer;
    private final ForyArrayAsListSerializer.ArrayAsList list;

    public StringArraySerializer(Fory fory) {
      super(fory, String[].class);
      stringSerializer = new StringSerializer(fory);
      collectionSerializer = new ForyArrayAsListSerializer(fory);
      list = new ForyArrayAsListSerializer.ArrayAsList(0);
    }

    @Override
    public void write(MemoryBuffer buffer, String[] value) {
      if (!isJava) {
        int len = value.length;
        buffer.writeVarUint32Small7(len);
        for (String elem : value) {
          if (elem != null) {
            buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
            stringSerializer.writeString(buffer, elem);
          } else {
            buffer.writeByte(Fory.NULL_FLAG);
          }
        }
        return;
      }
      int len = value.length;
      buffer.writeVarUint32Small7(len);
      if (len == 0) {
        return;
      }
      list.setArray(value);
      // TODO reference support
      // this method won't throw exception.
      int flags = collectionSerializer.writeNullabilityHeader(buffer, list);
      list.clearArray(); // clear for gc
      StringSerializer stringSerializer = this.stringSerializer;
      if ((flags & CollectionFlags.HAS_NULL) != CollectionFlags.HAS_NULL) {
        for (String elem : value) {
          stringSerializer.write(buffer, elem);
        }
      } else {
        for (String elem : value) {
          if (elem == null) {
            buffer.writeByte(Fory.NULL_FLAG);
          } else {
            buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
            stringSerializer.write(buffer, elem);
          }
        }
      }
    }

    @Override
    public String[] copy(String[] originArray) {
      String[] newArray = new String[originArray.length];
      System.arraycopy(originArray, 0, newArray, 0, originArray.length);
      return newArray;
    }

    @Override
    public String[] read(MemoryBuffer buffer) {
      if (!isJava) {
        int numElements = buffer.readVarUint32Small7();
        String[] value = new String[numElements];
        for (int i = 0; i < numElements; i++) {
          if (buffer.readByte() >= Fory.NOT_NULL_VALUE_FLAG) {
            value[i] = stringSerializer.readString(buffer);
          } else {
            value[i] = null;
          }
        }
        return value;
      }
      int numElements = buffer.readVarUint32Small7();
      String[] value = new String[numElements];
      if (numElements == 0) {
        return value;
      }
      int flags = buffer.readByte();
      StringSerializer serializer = this.stringSerializer;
      if ((flags & CollectionFlags.HAS_NULL) != CollectionFlags.HAS_NULL) {
        for (int i = 0; i < numElements; i++) {
          value[i] = serializer.readJavaString(buffer);
        }
      } else {
        for (int i = 0; i < numElements; i++) {
          if (buffer.readByte() != Fory.NULL_FLAG) {
            value[i] = serializer.readJavaString(buffer);
          }
        }
      }
      return value;
    }
  }

  public static void registerDefaultSerializers(Fory fory) {
    TypeResolver resolver = fory.getTypeResolver();
    resolver.registerInternalSerializer(
        Object[].class, new ObjectArraySerializer<>(fory, Object[].class));
    resolver.registerInternalSerializer(
        Class[].class, new ObjectArraySerializer<>(fory, Class[].class));
    resolver.registerInternalSerializer(byte[].class, new ByteArraySerializer(fory));
    resolver.registerInternalSerializer(
        Byte[].class, new ObjectArraySerializer<>(fory, Byte[].class));
    resolver.registerInternalSerializer(char[].class, new CharArraySerializer(fory));
    resolver.registerInternalSerializer(
        Character[].class, new ObjectArraySerializer<>(fory, Character[].class));
    resolver.registerInternalSerializer(short[].class, new ShortArraySerializer(fory));
    resolver.registerInternalSerializer(
        Short[].class, new ObjectArraySerializer<>(fory, Short[].class));
    resolver.registerInternalSerializer(int[].class, new IntArraySerializer(fory));
    resolver.registerInternalSerializer(
        Integer[].class, new ObjectArraySerializer<>(fory, Integer[].class));
    resolver.registerInternalSerializer(long[].class, new LongArraySerializer(fory));
    resolver.registerInternalSerializer(
        Long[].class, new ObjectArraySerializer<>(fory, Long[].class));
    resolver.registerInternalSerializer(float[].class, new FloatArraySerializer(fory));
    resolver.registerInternalSerializer(
        Float[].class, new ObjectArraySerializer<>(fory, Float[].class));
    resolver.registerInternalSerializer(double[].class, new DoubleArraySerializer(fory));
    resolver.registerInternalSerializer(
        Double[].class, new ObjectArraySerializer<>(fory, Double[].class));
    resolver.registerInternalSerializer(boolean[].class, new BooleanArraySerializer(fory));
    resolver.registerInternalSerializer(
        Boolean[].class, new ObjectArraySerializer<>(fory, Boolean[].class));
    resolver.registerInternalSerializer(String[].class, new StringArraySerializer(fory));
  }

  // ########################## utils ##########################

  static void writePrimitiveArray(
      MemoryBuffer buffer, Object arr, int offset, int numElements, int elemSize) {
    int size = Math.multiplyExact(numElements, elemSize);
    buffer.writeVarUint32Small7(size);
    int writerIndex = buffer.writerIndex();
    int end = writerIndex + size;
    buffer.ensure(end);
    buffer.copyFromUnsafe(writerIndex, arr, offset, size);
    buffer.writerIndex(end);
  }

  public static PrimitiveArrayBufferObject byteArrayBufferObject(byte[] array) {
    return new PrimitiveArrayBufferObject(array, Platform.BYTE_ARRAY_OFFSET, 1, array.length);
  }

  public abstract static class AbstractUnknownArraySerializer extends Serializer {
    protected final String className;
    private final int dims;

    public AbstractUnknownArraySerializer(Fory fory, String className, Class<?> stubClass) {
      super(fory, stubClass);
      this.className = className;
      this.dims = TypeUtils.getArrayDimensions(stubClass);
    }

    @Override
    public void write(MemoryBuffer buffer, Object value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Object[] read(MemoryBuffer buffer) {
      switch (dims) {
        case 1:
          return read1DArray(buffer);
        case 2:
          return read2DArray(buffer);
        case 3:
          return read3DArray(buffer);
        default:
          throw new UnsupportedOperationException(
              String.format("Unsupported array dimension %s for class %s", dims, className));
      }
    }

    protected abstract Object readInnerElement(MemoryBuffer buffer);

    private Object[] read1DArray(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32Small7();
      boolean isFinal = (numElements & 0b1) != 0;
      numElements >>>= 1;
      RefResolver refResolver = fory.getRefResolver();
      Object[] value = new Object[numElements];
      refResolver.reference(value);

      if (isFinal) {
        for (int i = 0; i < numElements; i++) {
          Object elem;
          int nextReadRefId = refResolver.tryPreserveRefId(buffer);
          if (nextReadRefId >= Fory.NOT_NULL_VALUE_FLAG) {
            elem = readInnerElement(buffer);
            refResolver.setReadObject(nextReadRefId, elem);
          } else {
            elem = refResolver.getReadObject();
          }
          value[i] = elem;
        }
      } else {
        for (int i = 0; i < numElements; i++) {
          value[i] = fory.readRef(buffer);
        }
      }
      return value;
    }

    private Object[][] read2DArray(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32Small7();
      boolean isFinal = (numElements & 0b1) != 0;
      numElements >>>= 1;
      RefResolver refResolver = fory.getRefResolver();
      Object[][] value = new Object[numElements][];
      refResolver.reference(value);
      if (isFinal) {
        for (int i = 0; i < numElements; i++) {
          Object[] elem;
          int nextReadRefId = refResolver.tryPreserveRefId(buffer);
          if (nextReadRefId >= Fory.NOT_NULL_VALUE_FLAG) {
            elem = read1DArray(buffer);
            refResolver.setReadObject(nextReadRefId, elem);
          } else {
            elem = (Object[]) refResolver.getReadObject();
          }
          value[i] = elem;
        }
      } else {
        for (int i = 0; i < numElements; i++) {
          value[i] = (Object[]) fory.readRef(buffer);
        }
      }
      return value;
    }

    private Object[] read3DArray(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32Small7();
      boolean isFinal = (numElements & 0b1) != 0;
      numElements >>>= 1;
      RefResolver refResolver = fory.getRefResolver();
      Object[][][] value = new Object[numElements][][];
      refResolver.reference(value);
      if (isFinal) {
        for (int i = 0; i < numElements; i++) {
          Object[][] elem;
          int nextReadRefId = refResolver.tryPreserveRefId(buffer);
          if (nextReadRefId >= Fory.NOT_NULL_VALUE_FLAG) {
            elem = read2DArray(buffer);
            refResolver.setReadObject(nextReadRefId, elem);
          } else {
            elem = (Object[][]) refResolver.getReadObject();
          }
          value[i] = elem;
        }
      } else {
        for (int i = 0; i < numElements; i++) {
          value[i] = (Object[][]) fory.readRef(buffer);
        }
      }
      return value;
    }
  }

  @SuppressWarnings("rawtypes")
  public static final class UnknownArraySerializer extends AbstractUnknownArraySerializer {
    private final Serializer componentSerializer;

    public UnknownArraySerializer(Fory fory, Class<?> cls) {
      this(fory, "Unknown", cls);
    }

    public UnknownArraySerializer(Fory fory, String className, Class<?> cls) {
      super(fory, className, cls);
      if (TypeUtils.getArrayComponent(cls).isEnum()) {
        componentSerializer = new UnknownClassSerializers.UnknownEnumSerializer(fory);
      } else {
        if (fory.getConfig().getCompatibleMode() == CompatibleMode.COMPATIBLE) {
          componentSerializer = new ObjectSerializer<>(fory, UnknownClass.UnknownEmptyStruct.class);
        } else {
          componentSerializer = null;
        }
      }
    }

    @Override
    protected Object readInnerElement(MemoryBuffer buffer) {
      if (componentSerializer == null) {
        throw new IllegalStateException(
            String.format("Class %s should serialize elements as non-morphic", className));
      }
      return componentSerializer.read(buffer);
    }
  }
}

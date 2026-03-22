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

package org.apache.fory.serializer.collection;

import org.apache.fory.Fory;
import org.apache.fory.collection.BoolList;
import org.apache.fory.collection.Float32List;
import org.apache.fory.collection.Float64List;
import org.apache.fory.collection.Int16List;
import org.apache.fory.collection.Int32List;
import org.apache.fory.collection.Int64List;
import org.apache.fory.collection.Int8List;
import org.apache.fory.collection.Uint16List;
import org.apache.fory.collection.Uint32List;
import org.apache.fory.collection.Uint64List;
import org.apache.fory.collection.Uint8List;
import org.apache.fory.config.LongEncoding;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.Serializers;

/** Serializers for primitive list types. */
@SuppressWarnings({"rawtypes", "unchecked"})
public class PrimitiveListSerializers {

  public static final class BoolListSerializer
      extends Serializers.CrossLanguageCompatibleSerializer<BoolList> {
    public BoolListSerializer(Fory fory) {
      super(fory, BoolList.class, false, true);
    }

    @Override
    public void write(MemoryBuffer buffer, BoolList value) {
      buffer.writeVarUint32Small7(value.size());
      boolean[] array = value.getArray();
      for (int i = 0; i < value.size(); i++) {
        buffer.writeBoolean(array[i]);
      }
    }

    @Override
    public BoolList read(MemoryBuffer buffer) {
      int size = buffer.readVarUint32Small7();
      BoolList list = new BoolList(size);
      for (int i = 0; i < size; i++) {
        list.add(buffer.readBoolean());
      }
      return list;
    }
  }

  public static final class Int8ListSerializer
      extends Serializers.CrossLanguageCompatibleSerializer<Int8List> {
    public Int8ListSerializer(Fory fory) {
      super(fory, Int8List.class, false, true);
    }

    @Override
    public void write(MemoryBuffer buffer, Int8List value) {
      buffer.writeVarUint32Small7(value.size());
      buffer.writeBytes(value.copyArray());
    }

    @Override
    public Int8List read(MemoryBuffer buffer) {
      int size = buffer.readVarUint32Small7();
      int maxBinarySize = fory.getConfig().maxBinarySize();
      if (maxBinarySize > 0 && size > maxBinarySize) {
        throw new IllegalArgumentException(
            "Binary size " + size + " exceeds configured maxBinarySize " + maxBinarySize);
      }
      byte[] array = new byte[size];
      buffer.readBytes(array);
      return new Int8List(array);
    }
  }

  public static final class Int16ListSerializer
      extends Serializers.CrossLanguageCompatibleSerializer<Int16List> {
    public Int16ListSerializer(Fory fory) {
      super(fory, Int16List.class, false, true);
    }

    @Override
    public void write(MemoryBuffer buffer, Int16List value) {
      int size = value.size();
      int byteSize = size * 2;
      buffer.writeVarUint32Small7(byteSize);
      short[] array = value.getArray();
      if (Platform.IS_LITTLE_ENDIAN) {
        buffer.writePrimitiveArray(array, Platform.SHORT_ARRAY_OFFSET, byteSize);
      } else {
        for (int i = 0; i < size; i++) {
          buffer.writeInt16(array[i]);
        }
      }
    }

    @Override
    public Int16List read(MemoryBuffer buffer) {
      int byteSize = buffer.readVarUint32Small7();
      int size = byteSize / 2;
      short[] array = new short[size];
      if (Platform.IS_LITTLE_ENDIAN) {
        buffer.readToUnsafe(array, Platform.SHORT_ARRAY_OFFSET, byteSize);
      } else {
        for (int i = 0; i < size; i++) {
          array[i] = buffer.readInt16();
        }
      }
      return new Int16List(array);
    }
  }

  public static final class Int32ListSerializer
      extends Serializers.CrossLanguageCompatibleSerializer<Int32List> {
    public Int32ListSerializer(Fory fory) {
      super(fory, Int32List.class, false, true);
    }

    @Override
    public void write(MemoryBuffer buffer, Int32List value) {
      if (fory.getConfig().compressIntArray()) {
        writeInt32Compressed(buffer, value);
        return;
      }
      int size = value.size();
      int byteSize = size * 4;
      buffer.writeVarUint32Small7(byteSize);
      int[] array = value.getArray();
      if (Platform.IS_LITTLE_ENDIAN) {
        buffer.writePrimitiveArray(array, Platform.INT_ARRAY_OFFSET, byteSize);
      } else {
        for (int i = 0; i < size; i++) {
          buffer.writeInt32(array[i]);
        }
      }
    }

    @Override
    public Int32List read(MemoryBuffer buffer) {
      if (fory.getConfig().compressIntArray()) {
        return readInt32Compressed(buffer);
      }
      int byteSize = buffer.readVarUint32Small7();
      int size = byteSize / 4;
      int[] array = new int[size];
      if (Platform.IS_LITTLE_ENDIAN) {
        buffer.readToUnsafe(array, Platform.INT_ARRAY_OFFSET, byteSize);
      } else {
        for (int i = 0; i < size; i++) {
          array[i] = buffer.readInt32();
        }
      }
      return new Int32List(array);
    }

    private void writeInt32Compressed(MemoryBuffer buffer, Int32List value) {
      buffer.writeVarUint32Small7(value.size());
      int[] array = value.getArray();
      for (int i = 0; i < value.size(); i++) {
        buffer.writeVarInt32(array[i]);
      }
    }

    private Int32List readInt32Compressed(MemoryBuffer buffer) {
      int size = buffer.readVarUint32Small7();
      Int32List list = new Int32List(size);
      for (int i = 0; i < size; i++) {
        list.add(buffer.readVarInt32());
      }
      return list;
    }
  }

  public static final class Int64ListSerializer
      extends Serializers.CrossLanguageCompatibleSerializer<Int64List> {
    private final boolean compressLongArray;

    public Int64ListSerializer(Fory fory) {
      super(fory, Int64List.class, false, true);
      compressLongArray =
          fory.getConfig().compressLongArray()
              && fory.getConfig().longEncoding() != LongEncoding.FIXED;
    }

    @Override
    public void write(MemoryBuffer buffer, Int64List value) {
      if (compressLongArray) {
        writeInt64Compressed(buffer, value, fory.getConfig().longEncoding());
        return;
      }
      int size = value.size();
      int byteSize = size * 8;
      buffer.writeVarUint32Small7(byteSize);
      long[] array = value.getArray();
      if (Platform.IS_LITTLE_ENDIAN) {
        buffer.writePrimitiveArray(array, Platform.LONG_ARRAY_OFFSET, byteSize);
      } else {
        for (int i = 0; i < size; i++) {
          buffer.writeInt64(array[i]);
        }
      }
    }

    @Override
    public Int64List read(MemoryBuffer buffer) {
      if (compressLongArray) {
        return readInt64Compressed(buffer, fory.getConfig().longEncoding());
      }
      int byteSize = buffer.readVarUint32Small7();
      int size = byteSize / 8;
      long[] array = new long[size];
      if (Platform.IS_LITTLE_ENDIAN) {
        buffer.readToUnsafe(array, Platform.LONG_ARRAY_OFFSET, byteSize);
      } else {
        for (int i = 0; i < size; i++) {
          array[i] = buffer.readInt64();
        }
      }
      return new Int64List(array);
    }

    private void writeInt64Compressed(
        MemoryBuffer buffer, Int64List value, LongEncoding longEncoding) {
      int size = value.size();
      buffer.writeVarUint32Small7(size);
      long[] array = value.getArray();
      if (longEncoding == LongEncoding.TAGGED) {
        for (int i = 0; i < size; i++) {
          buffer.writeTaggedInt64(array[i]);
        }
      } else {
        for (int i = 0; i < size; i++) {
          buffer.writeVarInt64(array[i]);
        }
      }
    }

    private Int64List readInt64Compressed(MemoryBuffer buffer, LongEncoding longEncoding) {
      int size = buffer.readVarUint32Small7();
      Int64List list = new Int64List(size);
      if (longEncoding == LongEncoding.TAGGED) {
        for (int i = 0; i < size; i++) {
          list.add(buffer.readTaggedInt64());
        }
      } else {
        for (int i = 0; i < size; i++) {
          list.add(buffer.readVarInt64());
        }
      }
      return list;
    }
  }

  public static final class Uint8ListSerializer
      extends Serializers.CrossLanguageCompatibleSerializer<Uint8List> {
    public Uint8ListSerializer(Fory fory) {
      super(fory, Uint8List.class, false, true);
    }

    @Override
    public void write(MemoryBuffer buffer, Uint8List value) {
      buffer.writeVarUint32Small7(value.size());
      buffer.writeBytes(value.copyArray());
    }

    @Override
    public Uint8List read(MemoryBuffer buffer) {
      int size = buffer.readVarUint32Small7();
      int maxBinarySize = fory.getConfig().maxBinarySize();
      if (maxBinarySize > 0 && size > maxBinarySize) {
        throw new IllegalArgumentException(
            "Binary size " + size + " exceeds configured maxBinarySize " + maxBinarySize);
      }
      byte[] array = new byte[size];
      buffer.readBytes(array);
      return new Uint8List(array);
    }
  }

  public static final class Uint16ListSerializer
      extends Serializers.CrossLanguageCompatibleSerializer<Uint16List> {
    public Uint16ListSerializer(Fory fory) {
      super(fory, Uint16List.class, false, true);
    }

    @Override
    public void write(MemoryBuffer buffer, Uint16List value) {
      int size = value.size();
      int byteSize = size * 2;
      buffer.writeVarUint32Small7(byteSize);
      short[] array = value.getArray();
      if (Platform.IS_LITTLE_ENDIAN) {
        buffer.writePrimitiveArray(array, Platform.SHORT_ARRAY_OFFSET, byteSize);
      } else {
        for (int i = 0; i < size; i++) {
          buffer.writeInt16(array[i]);
        }
      }
    }

    @Override
    public Uint16List read(MemoryBuffer buffer) {
      int byteSize = buffer.readVarUint32Small7();
      int size = byteSize / 2;
      short[] array = new short[size];
      if (Platform.IS_LITTLE_ENDIAN) {
        buffer.readToUnsafe(array, Platform.SHORT_ARRAY_OFFSET, byteSize);
      } else {
        for (int i = 0; i < size; i++) {
          array[i] = buffer.readInt16();
        }
      }
      return new Uint16List(array);
    }
  }

  public static final class Uint32ListSerializer
      extends Serializers.CrossLanguageCompatibleSerializer<Uint32List> {
    public Uint32ListSerializer(Fory fory) {
      super(fory, Uint32List.class, false, true);
    }

    @Override
    public void write(MemoryBuffer buffer, Uint32List value) {
      if (fory.getConfig().compressIntArray()) {
        writeUint32Compressed(buffer, value);
        return;
      }
      int size = value.size();
      int byteSize = size * 4;
      buffer.writeVarUint32Small7(byteSize);
      int[] array = value.getArray();
      if (Platform.IS_LITTLE_ENDIAN) {
        buffer.writePrimitiveArray(array, Platform.INT_ARRAY_OFFSET, byteSize);
      } else {
        for (int i = 0; i < size; i++) {
          buffer.writeInt32(array[i]);
        }
      }
    }

    @Override
    public Uint32List read(MemoryBuffer buffer) {
      if (fory.getConfig().compressIntArray()) {
        return readUint32Compressed(buffer);
      }
      int byteSize = buffer.readVarUint32Small7();
      int size = byteSize / 4;
      int[] array = new int[size];
      if (Platform.IS_LITTLE_ENDIAN) {
        buffer.readToUnsafe(array, Platform.INT_ARRAY_OFFSET, byteSize);
      } else {
        for (int i = 0; i < size; i++) {
          array[i] = buffer.readInt32();
        }
      }
      return new Uint32List(array);
    }

    private void writeUint32Compressed(MemoryBuffer buffer, Uint32List value) {
      buffer.writeVarUint32Small7(value.size());
      int[] array = value.getArray();
      for (int i = 0; i < value.size(); i++) {
        buffer.writeVarInt32(array[i]);
      }
    }

    private Uint32List readUint32Compressed(MemoryBuffer buffer) {
      int size = buffer.readVarUint32Small7();
      Uint32List list = new Uint32List(size);
      for (int i = 0; i < size; i++) {
        list.add(buffer.readVarInt32());
      }
      return list;
    }
  }

  public static final class Uint64ListSerializer
      extends Serializers.CrossLanguageCompatibleSerializer<Uint64List> {
    private final boolean compressLongArray;

    public Uint64ListSerializer(Fory fory) {
      super(fory, Uint64List.class, false, true);
      compressLongArray =
          fory.getConfig().compressLongArray()
              && fory.getConfig().longEncoding() != LongEncoding.FIXED;
    }

    @Override
    public void write(MemoryBuffer buffer, Uint64List value) {
      if (compressLongArray) {
        writeUint64Compressed(buffer, value, fory.getConfig().longEncoding());
        return;
      }
      int size = value.size();
      int byteSize = size * 8;
      buffer.writeVarUint32Small7(byteSize);
      long[] array = value.getArray();
      if (Platform.IS_LITTLE_ENDIAN) {
        buffer.writePrimitiveArray(array, Platform.LONG_ARRAY_OFFSET, byteSize);
      } else {
        for (int i = 0; i < size; i++) {
          buffer.writeInt64(array[i]);
        }
      }
    }

    @Override
    public Uint64List read(MemoryBuffer buffer) {
      if (compressLongArray) {
        return readUint64Compressed(buffer, fory.getConfig().longEncoding());
      }
      int byteSize = buffer.readVarUint32Small7();
      int size = byteSize / 8;
      long[] array = new long[size];
      if (Platform.IS_LITTLE_ENDIAN) {
        buffer.readToUnsafe(array, Platform.LONG_ARRAY_OFFSET, byteSize);
      } else {
        for (int i = 0; i < size; i++) {
          array[i] = buffer.readInt64();
        }
      }
      return new Uint64List(array);
    }

    private void writeUint64Compressed(
        MemoryBuffer buffer, Uint64List value, LongEncoding longEncoding) {
      int size = value.size();
      buffer.writeVarUint32Small7(size);
      long[] array = value.getArray();
      if (longEncoding == LongEncoding.TAGGED) {
        for (int i = 0; i < size; i++) {
          buffer.writeTaggedInt64(array[i]);
        }
      } else {
        for (int i = 0; i < size; i++) {
          buffer.writeVarInt64(array[i]);
        }
      }
    }

    private Uint64List readUint64Compressed(MemoryBuffer buffer, LongEncoding longEncoding) {
      int size = buffer.readVarUint32Small7();
      Uint64List list = new Uint64List(size);
      if (longEncoding == LongEncoding.TAGGED) {
        for (int i = 0; i < size; i++) {
          list.add(buffer.readTaggedInt64());
        }
      } else {
        for (int i = 0; i < size; i++) {
          list.add(buffer.readVarInt64());
        }
      }
      return list;
    }
  }

  public static final class Float32ListSerializer
      extends Serializers.CrossLanguageCompatibleSerializer<Float32List> {
    public Float32ListSerializer(Fory fory) {
      super(fory, Float32List.class, false, true);
    }

    @Override
    public void write(MemoryBuffer buffer, Float32List value) {
      int size = value.size();
      int byteSize = size * 4;
      buffer.writeVarUint32Small7(byteSize);
      float[] array = value.getArray();
      if (Platform.IS_LITTLE_ENDIAN) {
        buffer.writePrimitiveArray(array, Platform.FLOAT_ARRAY_OFFSET, byteSize);
      } else {
        for (int i = 0; i < size; i++) {
          buffer.writeFloat32(array[i]);
        }
      }
    }

    @Override
    public Float32List read(MemoryBuffer buffer) {
      int byteSize = buffer.readVarUint32Small7();
      int size = byteSize / 4;
      float[] array = new float[size];
      if (Platform.IS_LITTLE_ENDIAN) {
        buffer.readToUnsafe(array, Platform.FLOAT_ARRAY_OFFSET, byteSize);
      } else {
        for (int i = 0; i < size; i++) {
          array[i] = buffer.readFloat32();
        }
      }
      return new Float32List(array);
    }
  }

  public static final class Float64ListSerializer
      extends Serializers.CrossLanguageCompatibleSerializer<Float64List> {
    public Float64ListSerializer(Fory fory) {
      super(fory, Float64List.class, false, true);
    }

    @Override
    public void write(MemoryBuffer buffer, Float64List value) {
      int size = value.size();
      int byteSize = size * 8;
      buffer.writeVarUint32Small7(byteSize);
      double[] array = value.getArray();
      if (Platform.IS_LITTLE_ENDIAN) {
        buffer.writePrimitiveArray(array, Platform.DOUBLE_ARRAY_OFFSET, byteSize);
      } else {
        for (int i = 0; i < size; i++) {
          buffer.writeFloat64(array[i]);
        }
      }
    }

    @Override
    public Float64List read(MemoryBuffer buffer) {
      int byteSize = buffer.readVarUint32Small7();
      int size = byteSize / 8;
      double[] array = new double[size];
      if (Platform.IS_LITTLE_ENDIAN) {
        buffer.readToUnsafe(array, Platform.DOUBLE_ARRAY_OFFSET, byteSize);
      } else {
        for (int i = 0; i < size; i++) {
          array[i] = buffer.readFloat64();
        }
      }
      return new Float64List(array);
    }
  }

  public static void registerDefaultSerializers(Fory fory) {
    // Note: Classes are already registered in ClassResolver.initialize()
    // We only need to register serializers here
    TypeResolver resolver = fory.getTypeResolver();
    resolver.registerInternalSerializer(BoolList.class, new BoolListSerializer(fory));
    resolver.registerInternalSerializer(Int8List.class, new Int8ListSerializer(fory));
    resolver.registerInternalSerializer(Int16List.class, new Int16ListSerializer(fory));
    resolver.registerInternalSerializer(Int32List.class, new Int32ListSerializer(fory));
    resolver.registerInternalSerializer(Int64List.class, new Int64ListSerializer(fory));
    resolver.registerInternalSerializer(Uint8List.class, new Uint8ListSerializer(fory));
    resolver.registerInternalSerializer(Uint16List.class, new Uint16ListSerializer(fory));
    resolver.registerInternalSerializer(Uint32List.class, new Uint32ListSerializer(fory));
    resolver.registerInternalSerializer(Uint64List.class, new Uint64ListSerializer(fory));
    resolver.registerInternalSerializer(Float32List.class, new Float32ListSerializer(fory));
    resolver.registerInternalSerializer(Float64List.class, new Float64ListSerializer(fory));
  }
}

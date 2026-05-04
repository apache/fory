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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.fory.collection.BFloat16List;
import org.apache.fory.collection.BoolList;
import org.apache.fory.collection.Float16List;
import org.apache.fory.collection.Float32List;
import org.apache.fory.collection.Float64List;
import org.apache.fory.collection.Int16List;
import org.apache.fory.collection.Int32List;
import org.apache.fory.collection.Int64List;
import org.apache.fory.collection.Int8List;
import org.apache.fory.collection.UInt16List;
import org.apache.fory.collection.UInt32List;
import org.apache.fory.collection.UInt64List;
import org.apache.fory.collection.UInt8List;
import org.apache.fory.config.Int64Encoding;
import org.apache.fory.context.CopyContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.PrimitiveArraySerializers;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.Shareable;
import org.apache.fory.type.BFloat16;
import org.apache.fory.type.Float16;
import org.apache.fory.type.Types;

/** Serializers for primitive list types. */
@SuppressWarnings({"rawtypes", "unchecked"})
public class PrimitiveListSerializers {

  private abstract static class PrimitiveListSerializer<T> extends CollectionLikeSerializer<T>
      implements Shareable {
    private PrimitiveListSerializer(TypeResolver typeResolver, Class<T> cls) {
      super(typeResolver, cls, false, false);
    }

    @Override
    public final Collection onCollectionWrite(WriteContext writeContext, T value) {
      throw new IllegalStateException("supportCodegenHook is disabled for " + type.getName());
    }

    @Override
    public final T onCollectionRead(Collection collection) {
      throw new IllegalStateException("supportCodegenHook is disabled for " + type.getName());
    }
  }

  public static final class BoolListSerializer extends PrimitiveListSerializer<BoolList> {
    public BoolListSerializer(TypeResolver typeResolver) {
      super(typeResolver, BoolList.class);
    }

    @Override
    public void write(WriteContext writeContext, BoolList value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      buffer.writeVarUInt32Small7(value.size());
      boolean[] array = value.getArray();
      for (int i = 0; i < value.size(); i++) {
        buffer.writeBoolean(array[i]);
      }
    }

    @Override
    public BoolList read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int size = buffer.readVarUInt32Small7();
      BoolList list = new BoolList(size);
      for (int i = 0; i < size; i++) {
        list.add(buffer.readBoolean());
      }
      return list;
    }

    @Override
    public BoolList copy(CopyContext copyContext, BoolList value) {
      return new BoolList(value.copyArray());
    }
  }

  public static final class Int8ListSerializer extends PrimitiveListSerializer<Int8List> {
    public Int8ListSerializer(TypeResolver typeResolver) {
      super(typeResolver, Int8List.class);
    }

    @Override
    public void write(WriteContext writeContext, Int8List value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      buffer.writeVarUInt32Small7(value.size());
      buffer.writeBytes(value.copyArray());
    }

    @Override
    public Int8List read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int size = buffer.readVarUInt32Small7();
      byte[] array = new byte[size];
      buffer.readBytes(array);
      return new Int8List(array);
    }

    @Override
    public Int8List copy(CopyContext copyContext, Int8List value) {
      return new Int8List(value.copyArray());
    }
  }

  public static final class Int16ListSerializer extends PrimitiveListSerializer<Int16List> {
    public Int16ListSerializer(TypeResolver typeResolver) {
      super(typeResolver, Int16List.class);
    }

    @Override
    public void write(WriteContext writeContext, Int16List value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      int size = value.size();
      int byteSize = size * 2;
      buffer.writeVarUInt32Small7(byteSize);
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
    public Int16List read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int byteSize = buffer.readVarUInt32Small7();
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

    @Override
    public Int16List copy(CopyContext copyContext, Int16List value) {
      return new Int16List(value.copyArray());
    }
  }

  public static final class Int32ListSerializer extends PrimitiveListSerializer<Int32List> {
    public Int32ListSerializer(TypeResolver typeResolver) {
      super(typeResolver, Int32List.class);
    }

    @Override
    public void write(WriteContext writeContext, Int32List value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      if (!config.isXlang() && config.compressIntArray()) {
        writeInt32Compressed(buffer, value);
        return;
      }
      int size = value.size();
      int byteSize = size * 4;
      buffer.writeVarUInt32Small7(byteSize);
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
    public Int32List read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      if (!config.isXlang() && config.compressIntArray()) {
        return readInt32Compressed(buffer);
      }
      int byteSize = buffer.readVarUInt32Small7();
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
      buffer.writeVarUInt32Small7(value.size());
      int[] array = value.getArray();
      for (int i = 0; i < value.size(); i++) {
        buffer.writeVarInt32(array[i]);
      }
    }

    private Int32List readInt32Compressed(MemoryBuffer buffer) {
      int size = buffer.readVarUInt32Small7();
      Int32List list = new Int32List(size);
      for (int i = 0; i < size; i++) {
        list.add(buffer.readVarInt32());
      }
      return list;
    }

    @Override
    public Int32List copy(CopyContext copyContext, Int32List value) {
      return new Int32List(value.copyArray());
    }
  }

  public static final class Int64ListSerializer extends PrimitiveListSerializer<Int64List> {
    private final boolean compressLongArray;

    public Int64ListSerializer(TypeResolver typeResolver) {
      super(typeResolver, Int64List.class);
      compressLongArray =
          !config.isXlang()
              && config.compressLongArray()
              && config.longEncoding() != Int64Encoding.FIXED;
    }

    @Override
    public void write(WriteContext writeContext, Int64List value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      if (compressLongArray) {
        writeInt64Compressed(buffer, value, config.longEncoding());
        return;
      }
      int size = value.size();
      int byteSize = size * 8;
      buffer.writeVarUInt32Small7(byteSize);
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
    public Int64List read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      if (compressLongArray) {
        return readInt64Compressed(buffer, config.longEncoding());
      }
      int byteSize = buffer.readVarUInt32Small7();
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
        MemoryBuffer buffer, Int64List value, Int64Encoding longEncoding) {
      int size = value.size();
      buffer.writeVarUInt32Small7(size);
      long[] array = value.getArray();
      if (longEncoding == Int64Encoding.TAGGED) {
        for (int i = 0; i < size; i++) {
          buffer.writeTaggedInt64(array[i]);
        }
      } else {
        for (int i = 0; i < size; i++) {
          buffer.writeVarInt64(array[i]);
        }
      }
    }

    private Int64List readInt64Compressed(MemoryBuffer buffer, Int64Encoding longEncoding) {
      int size = buffer.readVarUInt32Small7();
      Int64List list = new Int64List(size);
      if (longEncoding == Int64Encoding.TAGGED) {
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

    @Override
    public Int64List copy(CopyContext copyContext, Int64List value) {
      return new Int64List(value.copyArray());
    }
  }

  public static final class UInt8ListSerializer extends PrimitiveListSerializer<UInt8List> {
    public UInt8ListSerializer(TypeResolver typeResolver) {
      super(typeResolver, UInt8List.class);
    }

    @Override
    public void write(WriteContext writeContext, UInt8List value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      buffer.writeVarUInt32Small7(value.size());
      buffer.writeBytes(value.copyArray());
    }

    @Override
    public UInt8List read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int size = buffer.readVarUInt32Small7();
      byte[] array = new byte[size];
      buffer.readBytes(array);
      return new UInt8List(array);
    }

    @Override
    public UInt8List copy(CopyContext copyContext, UInt8List value) {
      return new UInt8List(value.copyArray());
    }
  }

  public static final class UInt16ListSerializer extends PrimitiveListSerializer<UInt16List> {
    public UInt16ListSerializer(TypeResolver typeResolver) {
      super(typeResolver, UInt16List.class);
    }

    @Override
    public void write(WriteContext writeContext, UInt16List value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      int size = value.size();
      int byteSize = size * 2;
      buffer.writeVarUInt32Small7(byteSize);
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
    public UInt16List read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int byteSize = buffer.readVarUInt32Small7();
      int size = byteSize / 2;
      short[] array = new short[size];
      if (Platform.IS_LITTLE_ENDIAN) {
        buffer.readToUnsafe(array, Platform.SHORT_ARRAY_OFFSET, byteSize);
      } else {
        for (int i = 0; i < size; i++) {
          array[i] = buffer.readInt16();
        }
      }
      return new UInt16List(array);
    }

    @Override
    public UInt16List copy(CopyContext copyContext, UInt16List value) {
      return new UInt16List(value.copyArray());
    }
  }

  public static final class UInt32ListSerializer extends PrimitiveListSerializer<UInt32List> {
    public UInt32ListSerializer(TypeResolver typeResolver) {
      super(typeResolver, UInt32List.class);
    }

    @Override
    public void write(WriteContext writeContext, UInt32List value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      if (!config.isXlang() && config.compressIntArray()) {
        writeUInt32Compressed(buffer, value);
        return;
      }
      int size = value.size();
      int byteSize = size * 4;
      buffer.writeVarUInt32Small7(byteSize);
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
    public UInt32List read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      if (!config.isXlang() && config.compressIntArray()) {
        return readUInt32Compressed(buffer);
      }
      int byteSize = buffer.readVarUInt32Small7();
      int size = byteSize / 4;
      int[] array = new int[size];
      if (Platform.IS_LITTLE_ENDIAN) {
        buffer.readToUnsafe(array, Platform.INT_ARRAY_OFFSET, byteSize);
      } else {
        for (int i = 0; i < size; i++) {
          array[i] = buffer.readInt32();
        }
      }
      return new UInt32List(array);
    }

    private void writeUInt32Compressed(MemoryBuffer buffer, UInt32List value) {
      buffer.writeVarUInt32Small7(value.size());
      int[] array = value.getArray();
      for (int i = 0; i < value.size(); i++) {
        buffer.writeVarInt32(array[i]);
      }
    }

    private UInt32List readUInt32Compressed(MemoryBuffer buffer) {
      int size = buffer.readVarUInt32Small7();
      UInt32List list = new UInt32List(size);
      for (int i = 0; i < size; i++) {
        list.add(buffer.readVarInt32());
      }
      return list;
    }

    @Override
    public UInt32List copy(CopyContext copyContext, UInt32List value) {
      return new UInt32List(value.copyArray());
    }
  }

  public static final class UInt64ListSerializer extends PrimitiveListSerializer<UInt64List> {
    private final boolean compressLongArray;

    public UInt64ListSerializer(TypeResolver typeResolver) {
      super(typeResolver, UInt64List.class);
      compressLongArray =
          !config.isXlang()
              && config.compressLongArray()
              && config.longEncoding() != Int64Encoding.FIXED;
    }

    @Override
    public void write(WriteContext writeContext, UInt64List value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      if (compressLongArray) {
        writeUInt64Compressed(buffer, value, config.longEncoding());
        return;
      }
      int size = value.size();
      int byteSize = size * 8;
      buffer.writeVarUInt32Small7(byteSize);
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
    public UInt64List read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      if (compressLongArray) {
        return readUInt64Compressed(buffer, config.longEncoding());
      }
      int byteSize = buffer.readVarUInt32Small7();
      int size = byteSize / 8;
      long[] array = new long[size];
      if (Platform.IS_LITTLE_ENDIAN) {
        buffer.readToUnsafe(array, Platform.LONG_ARRAY_OFFSET, byteSize);
      } else {
        for (int i = 0; i < size; i++) {
          array[i] = buffer.readInt64();
        }
      }
      return new UInt64List(array);
    }

    private void writeUInt64Compressed(
        MemoryBuffer buffer, UInt64List value, Int64Encoding longEncoding) {
      int size = value.size();
      buffer.writeVarUInt32Small7(size);
      long[] array = value.getArray();
      if (longEncoding == Int64Encoding.TAGGED) {
        for (int i = 0; i < size; i++) {
          buffer.writeTaggedInt64(array[i]);
        }
      } else {
        for (int i = 0; i < size; i++) {
          buffer.writeVarInt64(array[i]);
        }
      }
    }

    private UInt64List readUInt64Compressed(MemoryBuffer buffer, Int64Encoding longEncoding) {
      int size = buffer.readVarUInt32Small7();
      UInt64List list = new UInt64List(size);
      if (longEncoding == Int64Encoding.TAGGED) {
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

    @Override
    public UInt64List copy(CopyContext copyContext, UInt64List value) {
      return new UInt64List(value.copyArray());
    }
  }

  public static final class Float32ListSerializer extends PrimitiveListSerializer<Float32List> {
    public Float32ListSerializer(TypeResolver typeResolver) {
      super(typeResolver, Float32List.class);
    }

    @Override
    public void write(WriteContext writeContext, Float32List value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      int size = value.size();
      int byteSize = size * 4;
      buffer.writeVarUInt32Small7(byteSize);
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
    public Float32List read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int byteSize = buffer.readVarUInt32Small7();
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

    @Override
    public Float32List copy(CopyContext copyContext, Float32List value) {
      return new Float32List(value.copyArray());
    }
  }

  public static final class Float64ListSerializer extends PrimitiveListSerializer<Float64List> {
    public Float64ListSerializer(TypeResolver typeResolver) {
      super(typeResolver, Float64List.class);
    }

    @Override
    public void write(WriteContext writeContext, Float64List value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      int size = value.size();
      int byteSize = size * 8;
      buffer.writeVarUInt32Small7(byteSize);
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
    public Float64List read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int byteSize = buffer.readVarUInt32Small7();
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

    @Override
    public Float64List copy(CopyContext copyContext, Float64List value) {
      return new Float64List(value.copyArray());
    }
  }

  public static final class Float16ListSerializer extends PrimitiveListSerializer<Float16List> {
    public Float16ListSerializer(TypeResolver typeResolver) {
      super(typeResolver, Float16List.class);
    }

    @Override
    public void write(WriteContext writeContext, Float16List value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      int size = value.size();
      int byteSize = size * 2;
      buffer.writeVarUInt32Small7(byteSize);
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
    public Float16List read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int byteSize = buffer.readVarUInt32Small7();
      int size = byteSize / 2;
      short[] array = new short[size];
      if (Platform.IS_LITTLE_ENDIAN) {
        buffer.readToUnsafe(array, Platform.SHORT_ARRAY_OFFSET, byteSize);
      } else {
        for (int i = 0; i < size; i++) {
          array[i] = buffer.readInt16();
        }
      }
      return new Float16List(array);
    }

    @Override
    public Float16List copy(CopyContext copyContext, Float16List value) {
      return new Float16List(value.copyArray());
    }
  }

  public static final class BFloat16ListSerializer extends PrimitiveListSerializer<BFloat16List> {
    public BFloat16ListSerializer(TypeResolver typeResolver) {
      super(typeResolver, BFloat16List.class);
    }

    @Override
    public void write(WriteContext writeContext, BFloat16List value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      int size = value.size();
      int byteSize = size * 2;
      buffer.writeVarUInt32Small7(byteSize);
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
    public BFloat16List read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int byteSize = buffer.readVarUInt32Small7();
      int size = byteSize / 2;
      short[] array = new short[size];
      if (Platform.IS_LITTLE_ENDIAN) {
        buffer.readToUnsafe(array, Platform.SHORT_ARRAY_OFFSET, byteSize);
      } else {
        for (int i = 0; i < size; i++) {
          array[i] = buffer.readInt16();
        }
      }
      return new BFloat16List(array);
    }

    @Override
    public BFloat16List copy(CopyContext copyContext, BFloat16List value) {
      return new BFloat16List(value.copyArray());
    }
  }

  public static Serializer<?> createArraySerializer(TypeResolver resolver, Class<?> type) {
    if (type == BoolList.class) {
      return new BoolListSerializer(resolver);
    }
    if (type == Int8List.class) {
      return new Int8ListSerializer(resolver);
    }
    if (type == Int16List.class) {
      return new Int16ListSerializer(resolver);
    }
    if (type == Int32List.class) {
      return new Int32ListSerializer(resolver);
    }
    if (type == Int64List.class) {
      return new Int64ListSerializer(resolver);
    }
    if (type == UInt8List.class) {
      return new UInt8ListSerializer(resolver);
    }
    if (type == UInt16List.class) {
      return new UInt16ListSerializer(resolver);
    }
    if (type == UInt32List.class) {
      return new UInt32ListSerializer(resolver);
    }
    if (type == UInt64List.class) {
      return new UInt64ListSerializer(resolver);
    }
    if (type == Float16List.class) {
      return new Float16ListSerializer(resolver);
    }
    if (type == BFloat16List.class) {
      return new BFloat16ListSerializer(resolver);
    }
    if (type == Float32List.class) {
      return new Float32ListSerializer(resolver);
    }
    if (type == Float64List.class) {
      return new Float64ListSerializer(resolver);
    }
    throw new IllegalArgumentException("Unsupported primitive list type " + type);
  }

  public static final class BoxedArrayAsListSerializer extends Serializer<List<?>>
      implements Shareable {
    private final int typeId;
    private final String fieldName;
    private final Serializer<?> arraySerializer;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public BoxedArrayAsListSerializer(TypeResolver typeResolver, int typeId, String fieldName) {
      super(typeResolver.getConfig(), (Class) List.class);
      this.typeId = typeId;
      this.fieldName = fieldName;
      switch (typeId) {
        case Types.BOOL_ARRAY:
          arraySerializer = new PrimitiveArraySerializers.BooleanArraySerializer(typeResolver);
          break;
        case Types.INT8_ARRAY:
        case Types.UINT8_ARRAY:
          arraySerializer = new PrimitiveArraySerializers.ByteArraySerializer(typeResolver);
          break;
        case Types.INT16_ARRAY:
        case Types.UINT16_ARRAY:
        case Types.FLOAT16_ARRAY:
        case Types.BFLOAT16_ARRAY:
          arraySerializer = new PrimitiveArraySerializers.ShortArraySerializer(typeResolver);
          break;
        case Types.INT32_ARRAY:
        case Types.UINT32_ARRAY:
          arraySerializer = new PrimitiveArraySerializers.IntArraySerializer(typeResolver);
          break;
        case Types.INT64_ARRAY:
        case Types.UINT64_ARRAY:
          arraySerializer = new PrimitiveArraySerializers.LongArraySerializer(typeResolver);
          break;
        case Types.FLOAT32_ARRAY:
          arraySerializer = new PrimitiveArraySerializers.FloatArraySerializer(typeResolver);
          break;
        case Types.FLOAT64_ARRAY:
          arraySerializer = new PrimitiveArraySerializers.DoubleArraySerializer(typeResolver);
          break;
        default:
          throw new IllegalArgumentException("Unsupported array type id " + typeId);
      }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void write(WriteContext writeContext, List<?> value) {
      ((Serializer<Object>) arraySerializer).write(writeContext, toPrimitiveArray(value));
    }

    @Override
    public List<?> read(ReadContext readContext) {
      Object primitiveArray = arraySerializer.read(readContext);
      return toBoxedList(primitiveArray);
    }

    @Override
    public List<?> copy(CopyContext copyContext, List<?> value) {
      return new ArrayList<>(value);
    }

    private Object toPrimitiveArray(List<?> value) {
      switch (typeId) {
        case Types.BOOL_ARRAY:
          return toBooleanArray(value);
        case Types.INT8_ARRAY:
          return toByteArray(value, false);
        case Types.UINT8_ARRAY:
          return toByteArray(value, true);
        case Types.INT16_ARRAY:
          return toShortArray(value, false);
        case Types.UINT16_ARRAY:
          return toShortArray(value, true);
        case Types.INT32_ARRAY:
          return toIntArray(value, false);
        case Types.UINT32_ARRAY:
          return toIntArray(value, true);
        case Types.INT64_ARRAY:
          return toLongArray(value);
        case Types.UINT64_ARRAY:
          return toLongArray(value);
        case Types.FLOAT16_ARRAY:
          return toFloat16Bits(value);
        case Types.BFLOAT16_ARRAY:
          return toBFloat16Bits(value);
        case Types.FLOAT32_ARRAY:
          return toFloatArray(value);
        case Types.FLOAT64_ARRAY:
          return toDoubleArray(value);
        default:
          throw new IllegalStateException("Unsupported array type id " + typeId);
      }
    }

    private List<?> toBoxedList(Object primitiveArray) {
      if (primitiveArray instanceof boolean[]) {
        boolean[] values = (boolean[]) primitiveArray;
        ArrayList<Boolean> list = new ArrayList<>(values.length);
        for (boolean value : values) {
          list.add(value);
        }
        return list;
      } else if (primitiveArray instanceof byte[]) {
        byte[] values = (byte[]) primitiveArray;
        ArrayList<Object> list = new ArrayList<>(values.length);
        for (byte value : values) {
          list.add(typeId == Types.UINT8_ARRAY ? Byte.toUnsignedInt(value) : value);
        }
        return list;
      } else if (primitiveArray instanceof short[]) {
        short[] values = (short[]) primitiveArray;
        ArrayList<Object> list = new ArrayList<>(values.length);
        for (short value : values) {
          if (typeId == Types.UINT16_ARRAY) {
            list.add(Short.toUnsignedInt(value));
          } else if (typeId == Types.FLOAT16_ARRAY) {
            list.add(Float16.fromBits(value));
          } else if (typeId == Types.BFLOAT16_ARRAY) {
            list.add(BFloat16.fromBits(value));
          } else {
            list.add(value);
          }
        }
        return list;
      } else if (primitiveArray instanceof int[]) {
        int[] values = (int[]) primitiveArray;
        ArrayList<Object> list = new ArrayList<>(values.length);
        for (int value : values) {
          list.add(typeId == Types.UINT32_ARRAY ? Integer.toUnsignedLong(value) : value);
        }
        return list;
      } else if (primitiveArray instanceof long[]) {
        long[] values = (long[]) primitiveArray;
        ArrayList<Long> list = new ArrayList<>(values.length);
        for (long value : values) {
          list.add(value);
        }
        return list;
      } else if (primitiveArray instanceof float[]) {
        float[] values = (float[]) primitiveArray;
        ArrayList<Float> list = new ArrayList<>(values.length);
        for (float value : values) {
          list.add(value);
        }
        return list;
      } else if (primitiveArray instanceof double[]) {
        double[] values = (double[]) primitiveArray;
        ArrayList<Double> list = new ArrayList<>(values.length);
        for (double value : values) {
          list.add(value);
        }
        return list;
      }
      throw new IllegalStateException("Unsupported array value " + primitiveArray.getClass());
    }

    private boolean[] toBooleanArray(List<?> value) {
      boolean[] array = new boolean[value.size()];
      for (int i = 0; i < value.size(); i++) {
        Object element = requireElement(value, i);
        if (!(element instanceof Boolean)) {
          throw wrongElementType(i, "Boolean", element);
        }
        array[i] = (Boolean) element;
      }
      return array;
    }

    private byte[] toByteArray(List<?> value, boolean unsigned) {
      byte[] array = new byte[value.size()];
      for (int i = 0; i < value.size(); i++) {
        int element = requireNumber(value, i).intValue();
        if (unsigned) {
          requireRange(i, element, 0, 0xFF);
        } else {
          requireRange(i, element, Byte.MIN_VALUE, Byte.MAX_VALUE);
        }
        array[i] = (byte) element;
      }
      return array;
    }

    private short[] toShortArray(List<?> value, boolean unsigned) {
      short[] array = new short[value.size()];
      for (int i = 0; i < value.size(); i++) {
        int element = requireNumber(value, i).intValue();
        if (unsigned) {
          requireRange(i, element, 0, 0xFFFF);
        } else {
          requireRange(i, element, Short.MIN_VALUE, Short.MAX_VALUE);
        }
        array[i] = (short) element;
      }
      return array;
    }

    private int[] toIntArray(List<?> value, boolean unsigned) {
      int[] array = new int[value.size()];
      for (int i = 0; i < value.size(); i++) {
        long element = requireNumber(value, i).longValue();
        if (unsigned) {
          requireRange(i, element, 0L, 0xFFFF_FFFFL);
        } else {
          requireRange(i, element, Integer.MIN_VALUE, Integer.MAX_VALUE);
        }
        array[i] = (int) element;
      }
      return array;
    }

    private long[] toLongArray(List<?> value) {
      long[] array = new long[value.size()];
      for (int i = 0; i < value.size(); i++) {
        array[i] = requireNumber(value, i).longValue();
      }
      return array;
    }

    private short[] toFloat16Bits(List<?> value) {
      short[] array = new short[value.size()];
      for (int i = 0; i < value.size(); i++) {
        Object element = requireElement(value, i);
        if (!(element instanceof Float16)) {
          throw wrongElementType(i, "Float16", element);
        }
        array[i] = ((Float16) element).toBits();
      }
      return array;
    }

    private short[] toBFloat16Bits(List<?> value) {
      short[] array = new short[value.size()];
      for (int i = 0; i < value.size(); i++) {
        Object element = requireElement(value, i);
        if (!(element instanceof BFloat16)) {
          throw wrongElementType(i, "BFloat16", element);
        }
        array[i] = ((BFloat16) element).toBits();
      }
      return array;
    }

    private float[] toFloatArray(List<?> value) {
      float[] array = new float[value.size()];
      for (int i = 0; i < value.size(); i++) {
        array[i] = requireNumber(value, i).floatValue();
      }
      return array;
    }

    private double[] toDoubleArray(List<?> value) {
      double[] array = new double[value.size()];
      for (int i = 0; i < value.size(); i++) {
        array[i] = requireNumber(value, i).doubleValue();
      }
      return array;
    }

    private Object requireElement(List<?> value, int index) {
      Object element = value.get(index);
      if (element == null) {
        throw new IllegalArgumentException(
            "@ArrayType List field " + fieldName + " contains null element at index " + index);
      }
      return element;
    }

    private Number requireNumber(List<?> value, int index) {
      Object element = requireElement(value, index);
      if (!(element instanceof Number)) {
        throw wrongElementType(index, "Number", element);
      }
      return (Number) element;
    }

    private void requireRange(int index, long value, long min, long max) {
      if (value < min || value > max) {
        throw new IllegalArgumentException(
            "@ArrayType List field "
                + fieldName
                + " element "
                + index
                + " value "
                + value
                + " is outside ["
                + min
                + ", "
                + max
                + "]");
      }
    }

    private IllegalArgumentException wrongElementType(int index, String expected, Object actual) {
      return new IllegalArgumentException(
          "@ArrayType List field "
              + fieldName
              + " element "
              + index
              + " must be "
              + expected
              + ", but got "
              + actual.getClass().getName());
    }
  }

  public static void registerDefaultSerializers(TypeResolver resolver) {
    resolver.registerInternalSerializer(BoolList.class, new BoolListSerializer(resolver));
    resolver.registerInternalSerializer(Int8List.class, new Int8ListSerializer(resolver));
    resolver.registerInternalSerializer(Int16List.class, new Int16ListSerializer(resolver));
    resolver.registerInternalSerializer(Int32List.class, new Int32ListSerializer(resolver));
    resolver.registerInternalSerializer(Int64List.class, new Int64ListSerializer(resolver));
    resolver.registerInternalSerializer(UInt8List.class, new UInt8ListSerializer(resolver));
    resolver.registerInternalSerializer(UInt16List.class, new UInt16ListSerializer(resolver));
    resolver.registerInternalSerializer(UInt32List.class, new UInt32ListSerializer(resolver));
    resolver.registerInternalSerializer(UInt64List.class, new UInt64ListSerializer(resolver));
    resolver.registerInternalSerializer(Float32List.class, new Float32ListSerializer(resolver));
    resolver.registerInternalSerializer(Float64List.class, new Float64ListSerializer(resolver));
    resolver.registerInternalSerializer(Float16List.class, new Float16ListSerializer(resolver));
    resolver.registerInternalSerializer(BFloat16List.class, new BFloat16ListSerializer(resolver));
  }
}

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

import static org.apache.fory.type.TypeUtils.PRIMITIVE_LONG_TYPE;

import org.apache.fory.Fory;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Invoke;
import org.apache.fory.config.LongEncoding;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.Serializers.CrossLanguageCompatibleSerializer;
import org.apache.fory.util.Preconditions;

/** Serializers for java primitive types. */
@SuppressWarnings({"rawtypes", "unchecked"})
public class PrimitiveSerializers {
  public static final class BooleanSerializer extends CrossLanguageCompatibleSerializer<Boolean> {
    public BooleanSerializer(Fory fory, Class<?> cls) {
      super(fory, (Class) cls, false, true);
    }

    @Override
    public void write(MemoryBuffer buffer, Boolean value) {
      buffer.writeBoolean(value);
    }

    @Override
    public Boolean read(MemoryBuffer buffer) {
      return buffer.readBoolean();
    }
  }

  public static final class ByteSerializer extends CrossLanguageCompatibleSerializer<Byte> {
    public ByteSerializer(Fory fory, Class<?> cls) {
      super(fory, (Class) cls, false, true);
    }

    @Override
    public void write(MemoryBuffer buffer, Byte value) {
      buffer.writeByte(value);
    }

    @Override
    public Byte read(MemoryBuffer buffer) {
      return buffer.readByte();
    }
  }

  public static final class Uint8Serializer extends CrossLanguageCompatibleSerializer<Integer> {
    public Uint8Serializer(Fory fory) {
      super(fory, Integer.class);
    }

    @Override
    public void write(MemoryBuffer buffer, Integer value) {
      Preconditions.checkArgument(value >= 0 && value <= 255);
      buffer.writeByte(value.byteValue());
    }

    @Override
    public Integer read(MemoryBuffer buffer) {
      int b = buffer.readByte();
      return b >>> 24;
    }
  }

  public static final class Uint16Serializer extends CrossLanguageCompatibleSerializer<Integer> {
    public Uint16Serializer(Fory fory) {
      super(fory, Integer.class);
    }

    @Override
    public void write(MemoryBuffer buffer, Integer value) {
      Preconditions.checkArgument(value >= 0 && value <= 65535);
      buffer.writeByte(value.byteValue());
    }

    @Override
    public Integer read(MemoryBuffer buffer) {
      int b = buffer.readByte();
      return b >>> 16;
    }
  }

  public static final class CharSerializer extends ImmutableSerializer<Character> {
    public CharSerializer(Fory fory, Class<?> cls) {
      super(fory, (Class) cls, false);
    }

    @Override
    public void write(MemoryBuffer buffer, Character value) {
      buffer.writeChar(value);
    }

    @Override
    public Character read(MemoryBuffer buffer) {
      return buffer.readChar();
    }
  }

  public static final class ShortSerializer extends CrossLanguageCompatibleSerializer<Short> {
    public ShortSerializer(Fory fory, Class<?> cls) {
      super(fory, (Class) cls, false, true);
    }

    @Override
    public void write(MemoryBuffer buffer, Short value) {
      buffer.writeInt16(value);
    }

    @Override
    public Short read(MemoryBuffer buffer) {
      return buffer.readInt16();
    }
  }

  public static final class IntSerializer extends CrossLanguageCompatibleSerializer<Integer> {
    private final boolean compressNumber;

    public IntSerializer(Fory fory, Class<?> cls) {
      super(fory, (Class) cls, false, true);
      compressNumber = fory.compressInt();
    }

    @Override
    public void write(MemoryBuffer buffer, Integer value) {
      if (isJava && compressNumber) {
        buffer.writeVarInt32(value);
      } else if (isJava) {
        buffer.writeInt32(value);
      } else {
        // TODO support varint in cross-language serialization
        buffer.writeVarInt32(value);
      }
    }

    @Override
    public Integer read(MemoryBuffer buffer) {
      if (isJava && compressNumber) {
        return buffer.readVarInt32();
      } else if (isJava) {
        return buffer.readInt32();
      } else {
        return buffer.readVarInt32();
      }
    }
  }

  public static final class VarUint32Serializer extends Serializer<Integer> {
    public VarUint32Serializer(Fory fory) {
      super(fory, Integer.class);
    }

    @Override
    public void write(MemoryBuffer buffer, Integer value) {
      Preconditions.checkArgument(value >= 0);
      buffer.writeVarUint32(value);
    }

    @Override
    public Integer read(MemoryBuffer buffer) {
      return buffer.readVarUint32();
    }

  }

  public static final class LongSerializer extends CrossLanguageCompatibleSerializer<Long> {
    private final LongEncoding longEncoding;

    public LongSerializer(Fory fory, Class<?> cls) {
      super(fory, (Class) cls, false, true);
      longEncoding = fory.longEncoding();
    }

    @Override
    public void write(MemoryBuffer buffer, Long value) {
      if (isJava) {
        writeInt64(buffer, value, longEncoding);
      } else {
        // TODO(chaokunyang) support var long in cross-language serialization
        buffer.writeVarInt64(value);
      }
    }

    @Override
    public Long read(MemoryBuffer buffer) {
      if (isJava) {
        return readInt64(buffer, longEncoding);
      } else {
        return buffer.readVarInt64();
      }
    }

    public static Expression writeInt64(
        Expression buffer, Expression v, LongEncoding longEncoding, boolean ensureBounds) {
      switch (longEncoding) {
        case FIXED:
          return new Invoke(buffer, "writeInt64", v);
        case TAGGED:
          return new Invoke(
              buffer, ensureBounds ? "writeTaggedInt64" : "_unsafeWriteTaggedInt64", v);
        case VARINT:
          return new Invoke(buffer, ensureBounds ? "writeVarInt64" : "_unsafeWriteVarInt64", v);
        default:
          throw new UnsupportedOperationException("Unsupported long encoding " + longEncoding);
      }
    }

    public static void writeInt64(MemoryBuffer buffer, long value, LongEncoding longEncoding) {
      if (longEncoding == LongEncoding.TAGGED) {
        buffer.writeTaggedInt64(value);
      } else if (longEncoding == LongEncoding.FIXED) {
        buffer.writeInt64(value);
      } else {
        buffer.writeVarInt64(value);
      }
    }

    public static long readInt64(MemoryBuffer buffer, LongEncoding longEncoding) {
      if (longEncoding == LongEncoding.TAGGED) {
        return buffer.readTaggedInt64();
      } else if (longEncoding == LongEncoding.FIXED) {
        return buffer.readInt64();
      } else {
        return buffer.readVarInt64();
      }
    }

    public static Expression readInt64(Expression buffer, LongEncoding longEncoding) {
      return new Invoke(buffer, readLongFunc(longEncoding), PRIMITIVE_LONG_TYPE);
    }

    public static String readLongFunc(LongEncoding longEncoding) {
      switch (longEncoding) {
        case FIXED:
          return Platform.IS_LITTLE_ENDIAN ? "_readInt64OnLE" : "_readInt64OnBE";
        case TAGGED:
          return Platform.IS_LITTLE_ENDIAN ? "_readTaggedInt64OnLE" : "_readTaggedInt64OnBE";
        case VARINT:
          return Platform.IS_LITTLE_ENDIAN ? "_readVarInt64OnLE" : "_readVarInt64OnBE";
        default:
          throw new UnsupportedOperationException("Unsupported long encoding " + longEncoding);
      }
    }

  }

  public static final class VarUint64Serializer extends Serializer<Long> {
    public VarUint64Serializer(Fory fory) {
      super(fory, Long.class);
    }

    @Override
    public void write(MemoryBuffer buffer, Long value) {
      Preconditions.checkArgument(value >= 0);
      buffer.writeVarUint64(value);
    }

    @Override
    public Long read(MemoryBuffer buffer) {
      return buffer.readVarUint64();
    }

  }

  public static final class FloatSerializer extends CrossLanguageCompatibleSerializer<Float> {
    public FloatSerializer(Fory fory, Class<?> cls) {
      super(fory, (Class) cls, false, true);
    }

    @Override
    public void write(MemoryBuffer buffer, Float value) {
      buffer.writeFloat32(value);
    }

    @Override
    public Float read(MemoryBuffer buffer) {
      return buffer.readFloat32();
    }
  }

  public static final class DoubleSerializer extends CrossLanguageCompatibleSerializer<Double> {
    public DoubleSerializer(Fory fory, Class<?> cls) {
      super(fory, (Class) cls, false, true);
    }

    @Override
    public void write(MemoryBuffer buffer, Double value) {
      buffer.writeFloat64(value);
    }

    @Override
    public Double read(MemoryBuffer buffer) {
      return buffer.readFloat64();
    }
  }

  public static void registerDefaultSerializers(Fory fory) {
    // primitive types will be boxed.
    TypeResolver resolver = fory.getTypeResolver();
    resolver.registerInternalSerializer(boolean.class, new BooleanSerializer(fory, boolean.class));
    resolver.registerInternalSerializer(byte.class, new ByteSerializer(fory, byte.class));
    resolver.registerInternalSerializer(short.class, new ShortSerializer(fory, short.class));
    resolver.registerInternalSerializer(char.class, new CharSerializer(fory, char.class));
    resolver.registerInternalSerializer(int.class, new IntSerializer(fory, int.class));
    resolver.registerInternalSerializer(long.class, new LongSerializer(fory, long.class));
    resolver.registerInternalSerializer(float.class, new FloatSerializer(fory, float.class));
    resolver.registerInternalSerializer(double.class, new DoubleSerializer(fory, double.class));
    resolver.registerInternalSerializer(Boolean.class, new BooleanSerializer(fory, Boolean.class));
    resolver.registerInternalSerializer(Byte.class, new ByteSerializer(fory, Byte.class));
    resolver.registerInternalSerializer(Short.class, new ShortSerializer(fory, Short.class));
    resolver.registerInternalSerializer(Character.class, new CharSerializer(fory, Character.class));
    resolver.registerInternalSerializer(Integer.class, new IntSerializer(fory, Integer.class));
    resolver.registerInternalSerializer(Long.class, new LongSerializer(fory, Long.class));
    resolver.registerInternalSerializer(Float.class, new FloatSerializer(fory, Float.class));
    resolver.registerInternalSerializer(Double.class, new DoubleSerializer(fory, Double.class));
  }
}

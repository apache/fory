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

import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Invoke;
import org.apache.fory.config.Config;
import org.apache.fory.config.LongEncoding;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.type.Float16;
import org.apache.fory.util.Preconditions;

/** Serializers for java primitive types. */
@SuppressWarnings({"rawtypes", "unchecked"})
public class PrimitiveSerializers {
  public static final class BooleanSerializer extends ImmutableSerializer<Boolean> {
    public BooleanSerializer(Config config, Class<?> cls) {
      super(config, (Class) cls, false);
    }

    @Override
    public void write(WriteContext writeContext, Boolean value) {
      writeContext.getBuffer().writeBoolean(value);
    }

    @Override
    public Boolean read(ReadContext readContext) {
      return readContext.getBuffer().readBoolean();
    }

    @Override
    public boolean shareable() {
      return true;
    }
  }

  public static final class ByteSerializer extends ImmutableSerializer<Byte> {
    public ByteSerializer(Config config, Class<?> cls) {
      super(config, (Class) cls, false);
    }

    @Override
    public void write(WriteContext writeContext, Byte value) {
      writeContext.getBuffer().writeByte(value);
    }

    @Override
    public Byte read(ReadContext readContext) {
      return readContext.getBuffer().readByte();
    }

    @Override
    public boolean shareable() {
      return true;
    }
  }

  public static final class Uint8Serializer extends ImmutableSerializer<Integer> {
    public Uint8Serializer(Config config) {
      super(config, Integer.class);
    }

    @Override
    public void write(WriteContext writeContext, Integer value) {
      Preconditions.checkArgument(value >= 0 && value <= 255);
      writeContext.getBuffer().writeByte(value.byteValue());
    }

    @Override
    public Integer read(ReadContext readContext) {
      int b = readContext.getBuffer().readByte();
      return b >>> 24;
    }

    @Override
    public boolean shareable() {
      return true;
    }
  }

  public static final class Uint16Serializer extends ImmutableSerializer<Integer> {
    public Uint16Serializer(Config config) {
      super(config, Integer.class);
    }

    @Override
    public void write(WriteContext writeContext, Integer value) {
      Preconditions.checkArgument(value >= 0 && value <= 65535);
      writeContext.getBuffer().writeByte(value.byteValue());
    }

    @Override
    public Integer read(ReadContext readContext) {
      int b = readContext.getBuffer().readByte();
      return b >>> 16;
    }

    @Override
    public boolean shareable() {
      return true;
    }
  }

  public static final class CharSerializer extends ImmutableSerializer<Character> {
    public CharSerializer(Config config, Class<?> cls) {
      super(config, (Class) cls, false);
    }

    @Override
    public void write(WriteContext writeContext, Character value) {
      writeContext.getBuffer().writeChar(value);
    }

    @Override
    public Character read(ReadContext readContext) {
      return readContext.getBuffer().readChar();
    }

    @Override
    public boolean shareable() {
      return true;
    }
  }

  public static final class ShortSerializer extends ImmutableSerializer<Short> {
    public ShortSerializer(Config config, Class<?> cls) {
      super(config, (Class) cls, false);
    }

    @Override
    public void write(WriteContext writeContext, Short value) {
      writeContext.getBuffer().writeInt16(value);
    }

    @Override
    public Short read(ReadContext readContext) {
      return readContext.getBuffer().readInt16();
    }

    @Override
    public boolean shareable() {
      return true;
    }
  }

  public static final class IntSerializer extends ImmutableSerializer<Integer> {
    private final boolean compressNumber;

    public IntSerializer(Config config, Class<?> cls) {
      super(config, (Class) cls, false);
      // Cross-language encoding always uses varint; Java mode follows compressInt config.
      compressNumber = config.isXlang() || config.compressInt();
    }

    @Override
    public void write(WriteContext writeContext, Integer value) {
      if (compressNumber) {
        writeContext.getBuffer().writeVarInt32(value);
      } else {
        writeContext.getBuffer().writeInt32(value);
      }
    }

    @Override
    public Integer read(ReadContext readContext) {
      if (compressNumber) {
        return readContext.getBuffer().readVarInt32();
      }
      return readContext.getBuffer().readInt32();
    }

    @Override
    public boolean shareable() {
      return true;
    }
  }

  public static final class VarUint32Serializer extends Serializer<Integer> {
    public VarUint32Serializer(Config config) {
      super(config, Integer.class);
    }

    @Override
    public void write(WriteContext writeContext, Integer value) {
      Preconditions.checkArgument(value >= 0);
      writeContext.getBuffer().writeVarUint32(value);
    }

    @Override
    public Integer read(ReadContext readContext) {
      return readContext.getBuffer().readVarUint32();
    }

    @Override
    public boolean shareable() {
      return true;
    }
  }

  public static final class LongSerializer extends ImmutableSerializer<Long> {
    private final LongEncoding longEncoding;

    public LongSerializer(Config config, Class<?> cls) {
      super(config, (Class) cls, false);
      longEncoding = config.isXlang() ? LongEncoding.VARINT : config.longEncoding();
    }

    @Override
    public void write(WriteContext writeContext, Long value) {
      writeInt64(writeContext.getBuffer(), value, longEncoding);
    }

    @Override
    public Long read(ReadContext readContext) {
      return readInt64(readContext.getBuffer(), longEncoding);
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

    @Override
    public boolean shareable() {
      return true;
    }
  }

  public static final class VarUint64Serializer extends Serializer<Long> {
    public VarUint64Serializer(Config config) {
      super(config, Long.class);
    }

    @Override
    public void write(WriteContext writeContext, Long value) {
      Preconditions.checkArgument(value >= 0);
      writeContext.getBuffer().writeVarUint64(value);
    }

    @Override
    public Long read(ReadContext readContext) {
      return readContext.getBuffer().readVarUint64();
    }

    @Override
    public boolean shareable() {
      return true;
    }
  }

  public static final class FloatSerializer extends ImmutableSerializer<Float> {
    public FloatSerializer(Config config, Class<?> cls) {
      super(config, (Class) cls, false);
    }

    @Override
    public void write(WriteContext writeContext, Float value) {
      writeContext.getBuffer().writeFloat32(value);
    }

    @Override
    public Float read(ReadContext readContext) {
      return readContext.getBuffer().readFloat32();
    }

    @Override
    public boolean shareable() {
      return true;
    }
  }

  public static final class DoubleSerializer extends ImmutableSerializer<Double> {
    public DoubleSerializer(Config config, Class<?> cls) {
      super(config, (Class) cls, false);
    }

    @Override
    public void write(WriteContext writeContext, Double value) {
      writeContext.getBuffer().writeFloat64(value);
    }

    @Override
    public Double read(ReadContext readContext) {
      return readContext.getBuffer().readFloat64();
    }

    @Override
    public boolean shareable() {
      return true;
    }
  }

  public static final class Float16Serializer extends ImmutableSerializer<Float16> {
    public Float16Serializer(Config config, Class<?> cls) {
      super(config, (Class) cls, false);
    }

    @Override
    public void write(WriteContext writeContext, Float16 value) {
      writeContext.getBuffer().writeInt16(value.toBits());
    }

    @Override
    public Float16 read(ReadContext readContext) {
      return Float16.fromBits(readContext.getBuffer().readInt16());
    }

    @Override
    public boolean shareable() {
      return true;
    }
  }

  public static void registerDefaultSerializers(TypeResolver resolver) {
    // primitive types will be boxed.
    Config config = resolver.getConfig();
    resolver.registerInternalSerializer(
        boolean.class, new BooleanSerializer(config, boolean.class));
    resolver.registerInternalSerializer(byte.class, new ByteSerializer(config, byte.class));
    resolver.registerInternalSerializer(short.class, new ShortSerializer(config, short.class));
    resolver.registerInternalSerializer(char.class, new CharSerializer(config, char.class));
    resolver.registerInternalSerializer(int.class, new IntSerializer(config, int.class));
    resolver.registerInternalSerializer(long.class, new LongSerializer(config, long.class));
    resolver.registerInternalSerializer(float.class, new FloatSerializer(config, float.class));
    resolver.registerInternalSerializer(double.class, new DoubleSerializer(config, double.class));
    resolver.registerInternalSerializer(
        Boolean.class, new BooleanSerializer(config, Boolean.class));
    resolver.registerInternalSerializer(Byte.class, new ByteSerializer(config, Byte.class));
    resolver.registerInternalSerializer(Short.class, new ShortSerializer(config, Short.class));
    resolver.registerInternalSerializer(
        Character.class, new CharSerializer(config, Character.class));
    resolver.registerInternalSerializer(Integer.class, new IntSerializer(config, Integer.class));
    resolver.registerInternalSerializer(Long.class, new LongSerializer(config, Long.class));
    resolver.registerInternalSerializer(Float.class, new FloatSerializer(config, Float.class));
    resolver.registerInternalSerializer(Double.class, new DoubleSerializer(config, Double.class));
    resolver.registerInternalSerializer(
        Float16.class, new Float16Serializer(config, Float16.class));
  }
}

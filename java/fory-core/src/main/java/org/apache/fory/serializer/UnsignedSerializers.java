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

import org.apache.fory.Fory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.type.unsigned.Uint16;
import org.apache.fory.type.unsigned.Uint32;
import org.apache.fory.type.unsigned.Uint64;
import org.apache.fory.type.unsigned.Uint8;

/** Serializers for unsigned numeric types. */
@SuppressWarnings({"rawtypes", "unchecked"})
public class UnsignedSerializers {

  public static final class Uint8Serializer
      extends Serializers.CrossLanguageCompatibleSerializer<Uint8> {
    public Uint8Serializer(Fory fory) {
      super(fory, Uint8.class, false, true);
    }

    @Override
    public void write(MemoryBuffer buffer, Uint8 value) {
      buffer.writeByte(value.byteValue());
    }

    @Override
    public Uint8 read(MemoryBuffer buffer) {
      return new Uint8(buffer.readByte());
    }
  }

  public static final class Uint16Serializer
      extends Serializers.CrossLanguageCompatibleSerializer<Uint16> {
    public Uint16Serializer(Fory fory) {
      super(fory, Uint16.class, false, true);
    }

    @Override
    public void write(MemoryBuffer buffer, Uint16 value) {
      buffer.writeInt16(value.shortValue());
    }

    @Override
    public Uint16 read(MemoryBuffer buffer) {
      return new Uint16(buffer.readInt16());
    }
  }

  public static final class Uint32Serializer
      extends Serializers.CrossLanguageCompatibleSerializer<Uint32> {
    public Uint32Serializer(Fory fory) {
      super(fory, Uint32.class, false, true);
    }

    @Override
    public void write(MemoryBuffer buffer, Uint32 value) {
      buffer.writeInt32(value.intValue());
    }

    @Override
    public Uint32 read(MemoryBuffer buffer) {
      return new Uint32(buffer.readInt32());
    }
  }

  public static final class Uint64Serializer
      extends Serializers.CrossLanguageCompatibleSerializer<Uint64> {
    public Uint64Serializer(Fory fory) {
      super(fory, Uint64.class, false, true);
    }

    @Override
    public void write(MemoryBuffer buffer, Uint64 value) {
      buffer.writeInt64(value.longValue());
    }

    @Override
    public Uint64 read(MemoryBuffer buffer) {
      return new Uint64(buffer.readInt64());
    }
  }

  public static void registerDefaultSerializers(Fory fory) {
    // Note: Classes are already registered in ClassResolver.initialize()
    // We only need to register serializers here
    TypeResolver resolver = fory.getTypeResolver();
    resolver.registerInternalSerializer(Uint8.class, new Uint8Serializer(fory));
    resolver.registerInternalSerializer(Uint16.class, new Uint16Serializer(fory));
    resolver.registerInternalSerializer(Uint32.class, new Uint32Serializer(fory));
    resolver.registerInternalSerializer(Uint64.class, new Uint64Serializer(fory));
  }
}

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

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.apache.fory.Fory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.TypeResolver;

/**
 * Serializers for {@link Optional}, {@link OptionalInt}, {@link OptionalLong} and {@link
 * OptionalDouble}.
 */
public final class OptionalSerializers {
  public static final class OptionalSerializer extends Serializer<Optional> {

    public OptionalSerializer(Fory fory) {
      super(fory, Optional.class);
    }

    @Override
    public void write(MemoryBuffer buffer, Optional value) {
      Object nullable = value.isPresent() ? value.get() : null;
      fory.writeRef(buffer, nullable);
    }

    @Override
    public Optional copy(Optional originOptional) {
      if (originOptional.isPresent()) {
        return Optional.ofNullable(fory.copyObject(originOptional.get()));
      }
      return originOptional;
    }

    @Override
    public Optional read(MemoryBuffer buffer) {
      return Optional.ofNullable(fory.readRef(buffer));
    }
  }

  public static final class OptionalIntSerializer extends ImmutableSerializer<OptionalInt> {
    public OptionalIntSerializer(Fory fory) {
      super(fory, OptionalInt.class);
    }

    @Override
    public void write(MemoryBuffer buffer, OptionalInt value) {
      boolean present = value.isPresent();
      buffer.writeBoolean(present);
      if (present) {
        buffer.writeInt32(value.getAsInt());
      }
    }

    @Override
    public OptionalInt read(MemoryBuffer buffer) {
      if (buffer.readBoolean()) {
        return OptionalInt.of(buffer.readInt32());
      } else {
        return OptionalInt.empty();
      }
    }
  }

  public static final class OptionalLongSerializer extends ImmutableSerializer<OptionalLong> {
    public OptionalLongSerializer(Fory fory) {
      super(fory, OptionalLong.class);
    }

    @Override
    public void write(MemoryBuffer buffer, OptionalLong value) {
      boolean present = value.isPresent();
      buffer.writeBoolean(present);
      if (present) {
        buffer.writeInt64(value.getAsLong());
      }
    }

    @Override
    public OptionalLong read(MemoryBuffer buffer) {
      if (buffer.readBoolean()) {
        return OptionalLong.of(buffer.readInt64());
      } else {
        return OptionalLong.empty();
      }
    }
  }

  public static final class OptionalDoubleSerializer extends ImmutableSerializer<OptionalDouble> {
    public OptionalDoubleSerializer(Fory fory) {
      super(fory, OptionalDouble.class);
    }

    @Override
    public void write(MemoryBuffer buffer, OptionalDouble value) {
      boolean present = value.isPresent();
      buffer.writeBoolean(present);
      if (present) {
        buffer.writeFloat64(value.getAsDouble());
      }
    }

    @Override
    public OptionalDouble read(MemoryBuffer buffer) {
      if (buffer.readBoolean()) {
        return OptionalDouble.of(buffer.readFloat64());
      } else {
        return OptionalDouble.empty();
      }
    }
  }

  public static void registerDefaultSerializers(Fory fory) {
    TypeResolver resolver = fory.getTypeResolver();
    resolver.registerInternalSerializer(Optional.class, new OptionalSerializer(fory));
    resolver.registerInternalSerializer(OptionalInt.class, new OptionalIntSerializer(fory));
    resolver.registerInternalSerializer(OptionalLong.class, new OptionalLongSerializer(fory));
    resolver.registerInternalSerializer(OptionalDouble.class, new OptionalDoubleSerializer(fory));
  }
}

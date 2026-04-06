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
import org.apache.fory.config.Config;
import org.apache.fory.context.CopyContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.resolver.TypeResolver;

/**
 * Serializers for {@link Optional}, {@link OptionalInt}, {@link OptionalLong} and {@link
 * OptionalDouble}.
 */
public final class OptionalSerializers {
  public static final class OptionalSerializer extends Serializer<Optional> {

    public OptionalSerializer(Config config) {
      super(config, Optional.class);
    }

    @Override
    public void write(WriteContext writeContext, Optional value) {
      Object nullable = value.isPresent() ? value.get() : null;
      writeContext.writeRef(nullable);
    }

    @Override
    public Optional copy(CopyContext copyContext, Optional originOptional) {
      if (originOptional.isPresent()) {
        return Optional.ofNullable(copyContext.copyObject(originOptional.get()));
      }
      return originOptional;
    }

    @Override
    public Optional read(ReadContext readContext) {
      return Optional.ofNullable(readContext.readRef());
    }
  }

  public static final class OptionalIntSerializer extends ImmutableSerializer<OptionalInt> {
    public OptionalIntSerializer(Config config) {
      super(config, OptionalInt.class);
    }

    @Override
    public void write(WriteContext writeContext, OptionalInt value) {
      boolean present = value.isPresent();
      writeContext.getBuffer().writeBoolean(present);
      if (present) {
        writeContext.getBuffer().writeInt32(value.getAsInt());
      }
    }

    @Override
    public OptionalInt read(ReadContext readContext) {
      if (readContext.getBuffer().readBoolean()) {
        return OptionalInt.of(readContext.getBuffer().readInt32());
      } else {
        return OptionalInt.empty();
      }
    }

    @Override
    public boolean shareable() {
      return true;
    }
  }

  public static final class OptionalLongSerializer extends ImmutableSerializer<OptionalLong> {
    public OptionalLongSerializer(Config config) {
      super(config, OptionalLong.class);
    }

    @Override
    public void write(WriteContext writeContext, OptionalLong value) {
      boolean present = value.isPresent();
      writeContext.getBuffer().writeBoolean(present);
      if (present) {
        writeContext.getBuffer().writeInt64(value.getAsLong());
      }
    }

    @Override
    public OptionalLong read(ReadContext readContext) {
      if (readContext.getBuffer().readBoolean()) {
        return OptionalLong.of(readContext.getBuffer().readInt64());
      } else {
        return OptionalLong.empty();
      }
    }

    @Override
    public boolean shareable() {
      return true;
    }
  }

  public static final class OptionalDoubleSerializer extends ImmutableSerializer<OptionalDouble> {
    public OptionalDoubleSerializer(Config config) {
      super(config, OptionalDouble.class);
    }

    @Override
    public void write(WriteContext writeContext, OptionalDouble value) {
      boolean present = value.isPresent();
      writeContext.getBuffer().writeBoolean(present);
      if (present) {
        writeContext.getBuffer().writeFloat64(value.getAsDouble());
      }
    }

    @Override
    public OptionalDouble read(ReadContext readContext) {
      if (readContext.getBuffer().readBoolean()) {
        return OptionalDouble.of(readContext.getBuffer().readFloat64());
      } else {
        return OptionalDouble.empty();
      }
    }

    @Override
    public boolean shareable() {
      return true;
    }
  }

  public static void registerDefaultSerializers(TypeResolver resolver) {
    Config config = resolver.getConfig();
    resolver.registerInternalSerializer(Optional.class, new OptionalSerializer(config));
    resolver.registerInternalSerializer(OptionalInt.class, new OptionalIntSerializer(config));
    resolver.registerInternalSerializer(OptionalLong.class, new OptionalLongSerializer(config));
    resolver.registerInternalSerializer(OptionalDouble.class, new OptionalDoubleSerializer(config));
  }
}

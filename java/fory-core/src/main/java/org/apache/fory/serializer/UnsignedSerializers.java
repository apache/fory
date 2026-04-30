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

import org.apache.fory.config.Config;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.type.unsigned.UInt16;
import org.apache.fory.type.unsigned.UInt32;
import org.apache.fory.type.unsigned.UInt64;
import org.apache.fory.type.unsigned.UInt8;

/** Serializers for unsigned numeric types. */
@SuppressWarnings({"rawtypes", "unchecked"})
public class UnsignedSerializers {

  public static final class UInt8Serializer extends ImmutableSerializer<UInt8>
      implements Shareable {
    public UInt8Serializer(Config config) {
      super(config, UInt8.class, false);
    }

    @Override
    public void write(WriteContext writeContext, UInt8 value) {
      writeContext.getBuffer().writeByte(value.byteValue());
    }

    @Override
    public UInt8 read(ReadContext readContext) {
      return new UInt8(readContext.getBuffer().readByte());
    }
  }

  public static final class UInt16Serializer extends ImmutableSerializer<UInt16>
      implements Shareable {
    public UInt16Serializer(Config config) {
      super(config, UInt16.class, false);
    }

    @Override
    public void write(WriteContext writeContext, UInt16 value) {
      writeContext.getBuffer().writeInt16(value.shortValue());
    }

    @Override
    public UInt16 read(ReadContext readContext) {
      return new UInt16(readContext.getBuffer().readInt16());
    }
  }

  public static final class UInt32Serializer extends ImmutableSerializer<UInt32>
      implements Shareable {
    public UInt32Serializer(Config config) {
      super(config, UInt32.class, false);
    }

    @Override
    public void write(WriteContext writeContext, UInt32 value) {
      writeContext.getBuffer().writeInt32(value.intValue());
    }

    @Override
    public UInt32 read(ReadContext readContext) {
      return new UInt32(readContext.getBuffer().readInt32());
    }
  }

  public static final class UInt64Serializer extends ImmutableSerializer<UInt64>
      implements Shareable {
    public UInt64Serializer(Config config) {
      super(config, UInt64.class, false);
    }

    @Override
    public void write(WriteContext writeContext, UInt64 value) {
      writeContext.getBuffer().writeInt64(value.longValue());
    }

    @Override
    public UInt64 read(ReadContext readContext) {
      return new UInt64(readContext.getBuffer().readInt64());
    }
  }

  public static void registerDefaultSerializers(TypeResolver resolver) {
    // Note: Classes are already registered in ClassResolver.initialize()
    // We only need to register serializers here
    Config config = resolver.getConfig();
    resolver.registerInternalSerializer(UInt8.class, new UInt8Serializer(config));
    resolver.registerInternalSerializer(UInt16.class, new UInt16Serializer(config));
    resolver.registerInternalSerializer(UInt32.class, new UInt32Serializer(config));
    resolver.registerInternalSerializer(UInt64.class, new UInt64Serializer(config));
  }
}

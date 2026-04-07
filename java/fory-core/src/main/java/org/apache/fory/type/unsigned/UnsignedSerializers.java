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

package org.apache.fory.type.unsigned;

import org.apache.fory.config.Config;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.ImmutableSerializer;

/** Utility class for registering Uint wrapper type serializers. */
public class UnsignedSerializers {

  /** Register all Uint wrapper type serializers. */
  public static void registerSerializers(TypeResolver resolver) {
    Config config = resolver.getConfig();
    resolver.registerSerializer(Uint8.class, new Uint8Serializer(config));
    resolver.registerSerializer(Uint16.class, new Uint16Serializer(config));
    resolver.registerSerializer(Uint32.class, new Uint32Serializer(config));
    resolver.registerSerializer(Uint64.class, new Uint64Serializer(config));
  }

  /** Serializer for Uint8 wrapper type. */
  public static class Uint8Serializer extends ImmutableSerializer<Uint8> {
    public Uint8Serializer(Config config) {
      super(config, Uint8.class);
    }

    @Override
    public void write(WriteContext writeContext, Uint8 value) {
      writeContext.getBuffer().writeByte(value.byteValue());
    }

    @Override
    public Uint8 read(ReadContext readContext) {
      return Uint8.valueOf(readContext.getBuffer().readByte());
    }

    @Override
    public boolean shareable() {
      return true;
    }
  }

  /** Serializer for Uint16 wrapper type. */
  public static class Uint16Serializer extends ImmutableSerializer<Uint16> {
    public Uint16Serializer(Config config) {
      super(config, Uint16.class);
    }

    @Override
    public void write(WriteContext writeContext, Uint16 value) {
      writeContext.getBuffer().writeInt16((short) value.toInt());
    }

    @Override
    public Uint16 read(ReadContext readContext) {
      return Uint16.valueOf(readContext.getBuffer().readInt16());
    }

    @Override
    public boolean shareable() {
      return true;
    }
  }

  /** Serializer for Uint32 wrapper type. */
  public static class Uint32Serializer extends ImmutableSerializer<Uint32> {
    public Uint32Serializer(Config config) {
      super(config, Uint32.class);
    }

    @Override
    public void write(WriteContext writeContext, Uint32 value) {
      writeContext.getBuffer().writeInt32((int) value.toLong());
    }

    @Override
    public Uint32 read(ReadContext readContext) {
      return Uint32.valueOf(readContext.getBuffer().readInt32());
    }

    @Override
    public boolean shareable() {
      return true;
    }
  }

  /** Serializer for Uint64 wrapper type. */
  public static class Uint64Serializer extends ImmutableSerializer<Uint64> {
    public Uint64Serializer(Config config) {
      super(config, Uint64.class);
    }

    @Override
    public void write(WriteContext writeContext, Uint64 value) {
      writeContext.getBuffer().writeInt64(value.toLong());
    }

    @Override
    public Uint64 read(ReadContext readContext) {
      return Uint64.valueOf(readContext.getBuffer().readInt64());
    }

    @Override
    public boolean shareable() {
      return true;
    }
  }
}

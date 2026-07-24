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

package org.apache.fory.json;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.CodecRegistry;
import org.apache.fory.json.resolver.JsonSharedRegistry;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.serializer.StringSerializer;

final class JsonTestSupport {
  private static final JsonConfig CONFIG =
      new JsonConfig(
          false,
          false,
          false,
          true,
          PropertyNamingStrategy.LOWER_CAMEL_CASE,
          JsonTestSupport.class.getClassLoader(),
          ForyJson.DEFAULT_MAX_DEPTH,
          ForyJson.DEFAULT_MAX_CACHED_FIELD_NAMES,
          1,
          2 * 1024 * 1024,
          new CodecRegistry(),
          Collections.<Class<?>, Class<?>>emptyMap(),
          null);
  private static final JsonSharedRegistry REGISTRY = new JsonSharedRegistry(CONFIG);
  private static final JsonValueCodec<Object> NULL_CODEC =
      new JsonValueCodec<Object>() {
        @Override
        public void writeString(StringJsonWriter writer, Object value) {
          writer.writeNull();
        }

        @Override
        public void writeUtf8(Utf8JsonWriter writer, Object value) {
          writer.writeNull();
        }

        @Override
        public Object readLatin1(Latin1JsonReader reader) {
          reader.skipValue();
          return null;
        }

        @Override
        public Object readUtf16(Utf16JsonReader reader) {
          reader.skipValue();
          return null;
        }

        @Override
        public Object readUtf8(Utf8JsonReader reader) {
          reader.skipValue();
          return null;
        }
      };

  static Utf8JsonWriter newUtf8Writer() {
    return new Utf8JsonWriter(CONFIG, newResolver());
  }

  static Utf8JsonWriter newUtf8Writer(byte[] buffer) {
    return new Utf8JsonWriter(CONFIG, newResolver(), buffer);
  }

  static StringJsonWriter newStringWriter() {
    return new StringJsonWriter(CONFIG, newResolver());
  }

  static StringJsonWriter newStringWriter(byte[] buffer) {
    return new StringJsonWriter(CONFIG, newResolver(), buffer);
  }

  static Utf8JsonReader newUtf8Reader(byte[] input) {
    return new Utf8JsonReader(CONFIG, newResolver(), input);
  }

  static Latin1JsonReader newLatin1Reader(byte[] input) {
    return new Latin1JsonReader(CONFIG, newResolver(), input);
  }

  static Latin1JsonReader newLatin1Reader(String input) {
    return new Latin1JsonReader(CONFIG, newResolver(), input);
  }

  static Utf16JsonReader newUtf16Reader() {
    return new Utf16JsonReader(CONFIG, newResolver());
  }

  static Utf16JsonReader newUtf16Reader(String input) {
    return new Utf16JsonReader(CONFIG, newResolver(), input);
  }

  @SuppressWarnings("unchecked")
  static <T> JsonValueCodec<T> nullCodec() {
    return (JsonValueCodec<T>) NULL_CODEC;
  }

  static JsonTypeResolver primaryTypeResolver(ForyJson json) {
    return (JsonTypeResolver) primaryStateField(json, "typeResolver");
  }

  static Object primaryStateField(ForyJson json, String name) {
    try {
      AtomicReference<?> primarySlot = (AtomicReference<?>) field(json, "primarySlot");
      Object pooledState = primarySlot.get();
      Object state = field(pooledState, "state");
      return field(state, name);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  static Object secondaryStateField(ForyJson json, int index, String name) {
    try {
      AtomicReferenceArray<?> slots = (AtomicReferenceArray<?>) field(json, "slots");
      Object pooledState = slots.get(index);
      Object state = field(pooledState, "state");
      return field(state, name);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  static JsonConfig config(ForyJson json) {
    try {
      return (JsonConfig) field(json, "config");
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  static String stringReaderPath(String input) {
    return StringSerializer.isBytesBackedString()
            && StringSerializer.isLatin1Coder(StringSerializer.getStringCoder(input))
        ? "latin1"
        : "utf16";
  }

  static int pooledStateCount(ForyJson json) {
    try {
      return ((AtomicReferenceArray<?>) field(json, "slots")).length() + 1;
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static JsonTypeResolver newResolver() {
    return new JsonTypeResolver(REGISTRY);
  }

  private static Object field(Object owner, String name) throws ReflectiveOperationException {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private JsonTestSupport() {}
}

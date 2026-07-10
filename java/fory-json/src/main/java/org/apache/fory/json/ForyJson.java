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

import java.io.OutputStream;
import java.lang.reflect.Type;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.apache.fory.json.codec.GeneratedObjectCodec;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonSharedRegistry;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.serializer.StringSerializer;

/** Thread-safe public facade for Fory JSON serialization and parsing. */
public final class ForyJson {
  private static final int PREFERRED_SLOT_RETRIES = 2;
  private static final int INITIAL_BUFFER_SIZE = 8192;
  private static final int RETAINED_UTF16_BYTES = 64 * 1024;
  private static final int PRIMARY_SLOT = -1;
  private static final int TEMPORARY_SLOT = -2;
  private static final byte[] EMPTY_BYTES = new byte[0];
  private static final int DEFAULT_POOL_SIZE =
      Math.max(1, Runtime.getRuntime().availableProcessors() * 4);

  /** Default maximum nested JSON object/array depth accepted while reading or writing. */
  public static final int DEFAULT_MAX_DEPTH = 20;

  private final JsonConfig config;
  private final JsonSharedRegistry sharedRegistry;
  private final int poolSize;
  private final AtomicReference<PooledState> primarySlot;
  private final AtomicReferenceArray<PooledState> slots;

  ForyJson(JsonConfig config) {
    this.config = config;
    sharedRegistry = new JsonSharedRegistry(config);
    poolSize = DEFAULT_POOL_SIZE;
    primarySlot =
        new AtomicReference<>(new PooledState(new JsonState(config, sharedRegistry), PRIMARY_SLOT));
    slots = new AtomicReferenceArray<>(poolSize);
    for (int i = 0; i < poolSize; i++) {
      slots.set(i, new PooledState(new JsonState(config, sharedRegistry), i));
    }
  }

  public static ForyJsonBuilder builder() {
    return new ForyJsonBuilder();
  }

  public String toJson(Object value) {
    PooledState entry = acquire();
    JsonState state = entry.state;
    StringJsonWriter writer = state.stringWriter;
    try {
      if (value == null) {
        writer.writeNull();
      } else {
        JsonTypeResolver resolver = state.typeResolver;
        JsonTypeInfo typeInfo = state.rootTypeInfo(value.getClass());
        typeInfo.codec().writeString(writer, value, resolver);
      }
      return writer.toJson();
    } finally {
      writer.reset();
      release(entry);
    }
  }

  public byte[] toJsonBytes(Object value) {
    PooledState entry = acquire();
    JsonState state = entry.state;
    Utf8JsonWriter writer = state.utf8Writer;
    try {
      if (value == null) {
        writer.writeNull();
      } else {
        JsonTypeResolver resolver = state.typeResolver;
        JsonTypeInfo typeInfo = state.rootTypeInfo(value.getClass());
        typeInfo.codec().writeUtf8(writer, value, resolver);
      }
      return writer.toJsonBytes();
    } finally {
      writer.reset();
      release(entry);
    }
  }

  /** Serializes {@code value} as UTF-8 JSON to {@code output}. */
  public void writeJsonTo(Object value, OutputStream output) {
    Objects.requireNonNull(output, "output");
    PooledState entry = acquire();
    JsonState state = entry.state;
    Utf8JsonWriter writer = state.utf8Writer;
    try {
      if (value == null) {
        writer.writeNull();
      } else {
        JsonTypeResolver resolver = state.typeResolver;
        JsonTypeInfo typeInfo = state.rootTypeInfo(value.getClass());
        typeInfo.codec().writeUtf8(writer, value, resolver);
      }
      writer.writeTo(output);
    } finally {
      writer.reset();
      release(entry);
    }
  }

  public <T> T fromJson(String json, Class<T> type) {
    PooledState entry = acquire();
    JsonState state = entry.state;
    try {
      return castValue(readJavaStringValue(json, type, type, state), type);
    } finally {
      state.clearStringReaders();
      release(entry);
    }
  }

  /** Parses JSON using a generic type captured by {@link TypeRef}. */
  public <T> T fromJson(String json, TypeRef<T> typeRef) {
    PooledState entry = acquire();
    JsonState state = entry.state;
    try {
      Object value = readJavaStringValue(json, typeRef.getType(), typeRef.getRawType(), state);
      return castValue(value, typeRef);
    } finally {
      state.clearStringReaders();
      release(entry);
    }
  }

  public <T> T fromJson(byte[] bytes, Class<T> type) {
    PooledState entry = acquire();
    JsonState state = entry.state;
    try {
      return castValue(readUtf8Value(state.utf8Reader(bytes), type, type, state), type);
    } finally {
      state.clearUtf8Reader();
      release(entry);
    }
  }

  /** Parses UTF-8 JSON bytes using a generic type captured by {@link TypeRef}. */
  public <T> T fromJson(byte[] bytes, TypeRef<T> typeRef) {
    PooledState entry = acquire();
    JsonState state = entry.state;
    try {
      Object value =
          readUtf8Value(state.utf8Reader(bytes), typeRef.getType(), typeRef.getRawType(), state);
      return castValue(value, typeRef);
    } finally {
      state.clearUtf8Reader();
      release(entry);
    }
  }

  boolean hasGeneratedCodec(Class<?> type) {
    PooledState entry = acquire();
    try {
      return entry.state.typeResolver.getObjectCodec(type) instanceof GeneratedObjectCodec;
    } finally {
      release(entry);
    }
  }

  private PooledState acquire() {
    PooledState entry = primarySlot.get();
    if (entry != null && primarySlot.compareAndSet(entry, null)) {
      return entry;
    }
    int slotIndex = slotIndexForCurrentThread();
    entry = tryBorrowPreferredSlots(slotIndex);
    if (entry != null) {
      return entry;
    }
    return new PooledState(new JsonState(config, sharedRegistry), TEMPORARY_SLOT);
  }

  private void release(PooledState entry) {
    if (entry.homeIndex == PRIMARY_SLOT) {
      primarySlot.lazySet(entry);
    } else if (entry.homeIndex >= 0) {
      slots.lazySet(entry.homeIndex, entry);
    }
  }

  private PooledState tryBorrowPreferredSlots(int slotIndex) {
    PooledState entry = tryBorrowSlot(slotIndex);
    if (entry != null) {
      return entry;
    }
    for (int i = 1; i < PREFERRED_SLOT_RETRIES; i++) {
      entry = tryBorrowSlot(slotIndex);
      if (entry != null) {
        return entry;
      }
    }
    int index = slotIndex + 1;
    if (index == poolSize) {
      index = 0;
    }
    for (int i = 1; i < poolSize; i++) {
      entry = tryBorrowSlot(index);
      if (entry != null) {
        return entry;
      }
      index++;
      if (index == poolSize) {
        index = 0;
      }
    }
    return null;
  }

  private PooledState tryBorrowSlot(int index) {
    return slots.getAndSet(index, null);
  }

  private int slotIndexForCurrentThread() {
    return Math.floorMod(spread(System.identityHashCode(Thread.currentThread())), poolSize);
  }

  private static int spread(int hash) {
    return hash ^ (hash >>> 16);
  }

  private Object readJavaStringValue(String json, Type type, Class<?> fallback, JsonState state) {
    if (StringSerializer.isBytesBackedString()) {
      byte coder = StringSerializer.getStringCoder(json);
      if (StringSerializer.isLatin1Coder(coder)) {
        // Keep String input on its reader owner even when ASCII Latin1 bytes match UTF-8;
        // custom JsonCodec implementations can observe readLatin1/readUtf8 dispatch.
        return readLatin1Value(state.latin1Reader(json), type, fallback, state);
      }
      if (StringSerializer.isUtf16Coder(coder)) {
        return readUtf16Value(state.utf16Reader(json), type, fallback, state);
      }
    }
    return readUtf16Value(state.legacyUtf16Reader(json), type, fallback, state);
  }

  private Object readLatin1Value(
      Latin1JsonReader reader, Type type, Class<?> fallback, JsonState state) {
    JsonTypeResolver resolver = state.typeResolver;
    JsonTypeInfo typeInfo = state.rootTypeInfo(type, fallback);
    Object value = typeInfo.codec().readLatin1(reader, typeInfo, resolver);
    reader.finish();
    return value;
  }

  private Object readUtf16Value(
      Utf16JsonReader reader, Type type, Class<?> fallback, JsonState state) {
    JsonTypeResolver resolver = state.typeResolver;
    JsonTypeInfo typeInfo = state.rootTypeInfo(type, fallback);
    Object value = typeInfo.codec().readUtf16(reader, typeInfo, resolver);
    reader.finish();
    return value;
  }

  private Object readUtf8Value(
      Utf8JsonReader reader, Type type, Class<?> fallback, JsonState state) {
    JsonTypeResolver resolver = state.typeResolver;
    JsonTypeInfo typeInfo = state.rootTypeInfo(type, fallback);
    Object value = typeInfo.codec().readUtf8(reader, typeInfo, resolver);
    reader.finish();
    return value;
  }

  @SuppressWarnings("unchecked")
  private static <T> T castValue(Object value, Class<T> type) {
    return type.isPrimitive() ? (T) value : type.cast(value);
  }

  @SuppressWarnings("unchecked")
  private static <T> T castValue(Object value, TypeRef<T> typeRef) {
    Class<?> rawType = typeRef.getRawType();
    return rawType.isPrimitive() ? (T) value : (T) rawType.cast(value);
  }

  private static final class PooledState {
    private final JsonState state;
    private final int homeIndex;

    private PooledState(JsonState state, int homeIndex) {
      this.state = state;
      this.homeIndex = homeIndex;
    }
  }

  private static final class JsonState {
    private final Utf8JsonWriter utf8Writer;
    private final StringJsonWriter stringWriter;
    private final Utf8JsonReader utf8Reader;
    private final Latin1JsonReader latin1Reader;
    private final Utf16JsonReader utf16Reader;
    private final JsonTypeResolver typeResolver;
    private byte[] legacyUtf16Bytes;
    private Type lastRootType;
    private Class<?> lastRootFallback;
    private JsonTypeInfo lastRootInfo;

    private JsonState(JsonConfig config, JsonSharedRegistry sharedRegistry) {
      utf8Writer = new Utf8JsonWriter(config, new byte[INITIAL_BUFFER_SIZE]);
      stringWriter = new StringJsonWriter(config, new byte[INITIAL_BUFFER_SIZE]);
      utf8Reader = new Utf8JsonReader(config);
      latin1Reader = new Latin1JsonReader(config);
      utf16Reader = new Utf16JsonReader(config);
      typeResolver = new JsonTypeResolver(sharedRegistry);
      legacyUtf16Bytes = EMPTY_BYTES;
    }

    private Latin1JsonReader latin1Reader(String input) {
      latin1Reader.reset(input);
      return latin1Reader;
    }

    private Utf16JsonReader utf16Reader(String input) {
      utf16Reader.reset(input);
      return utf16Reader;
    }

    private Utf16JsonReader legacyUtf16Reader(String input) {
      int length = input.length();
      if (length > (Integer.MAX_VALUE >>> 1)) {
        throw new IllegalArgumentException("String is too large");
      }
      int numBytes = length << 1;
      byte[] bytes;
      if (numBytes <= RETAINED_UTF16_BYTES) {
        bytes = legacyUtf16Bytes;
        if (bytes.length < numBytes) {
          bytes = new byte[Math.max(numBytes, INITIAL_BUFFER_SIZE)];
          legacyUtf16Bytes = bytes;
        }
      } else {
        bytes = new byte[numBytes];
      }
      // Legacy char[]-backed Strings are converted once so parsing still uses UTF16 byte loads.
      StringSerializer.copyStringCharsToBytes(input, bytes);
      utf16Reader.reset(input, bytes);
      return utf16Reader;
    }

    private Utf8JsonReader utf8Reader(byte[] input) {
      utf8Reader.reset(input);
      return utf8Reader;
    }

    // Clear only readers reset by the current public parse entry; clearing the unused readers shows
    // up on small byte-input parses and does not release additional retained input.
    private void clearStringReaders() {
      latin1Reader.clear();
      utf16Reader.clear();
    }

    private void clearUtf8Reader() {
      utf8Reader.clear();
    }

    private JsonTypeInfo rootTypeInfo(Class<?> type) {
      return rootTypeInfo(type, type);
    }

    private JsonTypeInfo rootTypeInfo(Type type, Class<?> fallback) {
      JsonTypeInfo typeInfo = lastRootInfo;
      if (lastRootType == type && lastRootFallback == fallback && typeInfo != null) {
        return typeInfo;
      }
      typeInfo = typeResolver.getTypeInfo(type, fallback);
      lastRootType = type;
      lastRootFallback = fallback;
      lastRootInfo = typeInfo;
      return typeInfo;
    }
  }
}

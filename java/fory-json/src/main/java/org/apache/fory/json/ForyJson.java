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
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
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

/**
 * Thread-safe facade for serializing Java values to JSON and parsing JSON into Java values.
 *
 * <p>One instance shares its {@link JsonConfig configuration}, custom and built-in codec
 * definitions, type-check results, and generated classes. Mutable execution state is not shared:
 * each pooled {@code JsonState} owns one {@link JsonTypeResolver}, one writer for each output form,
 * and one reader for each input representation. A state is borrowed by only one root operation at a
 * time, so reader positions, writer buffers, resolver caches, and ordinary generated-codec fields
 * need no per-value synchronization.
 *
 * <p>A root operation holds its resolver-local JIT lock from root type resolution through
 * completion of the codec graph. Asynchronous generated-capability installation therefore cannot
 * replace a {@link JsonTypeInfo} slot or a generated parent child field midway through that graph.
 * Different pooled states use different locks and remain concurrent. Writer output materialization,
 * writer reset, and reader clear happen after JIT unlock; reset or clear completes before the state
 * is returned to the pool.
 *
 * <p>String input preserves its concrete representation path: compact Latin1 strings use {@link
 * Latin1JsonReader}, compact UTF16 strings use {@link Utf16JsonReader}, and char-backed strings are
 * converted once to reusable UTF16 bytes. UTF-8 byte input always uses {@link Utf8JsonReader}. This
 * path selection is observable by custom codecs and is therefore not interchangeable even when a
 * Latin1 string contains only ASCII.
 *
 * <p>The facade has no close lifecycle. Contended operations borrow another pooled state or create
 * a temporary unpooled state instead of serializing all callers through one root lock. Java {@code
 * null} writes as JSON {@code null}; JSON {@code null} returns {@code null} for reference targets
 * and is rejected for primitive root targets.
 */
public final class ForyJson {
  private static final int PREFERRED_SLOT_RETRIES = 2;
  private static final int INITIAL_BUFFER_SIZE = 8192;
  private static final int RETAINED_UTF16_BYTES = 64 * 1024;
  private static final int PRIMARY_SLOT = -1;
  private static final int UNPOOLED_SLOT = -2;
  private static final byte[] EMPTY_BYTES = new byte[0];

  /** Default maximum nested JSON object/array depth accepted while reading or writing. */
  public static final int DEFAULT_MAX_DEPTH = 20;

  /** Default maximum number of short, unescaped ASCII field names cached by each JSON reader. */
  public static final int DEFAULT_MAX_CACHED_FIELD_NAMES = 8192;

  private final JsonConfig config;
  private final JsonSharedRegistry sharedRegistry;
  private final int secondaryPoolSize;
  private final AtomicReference<PooledState> primarySlot;
  private final AtomicReferenceArray<PooledState> slots;

  ForyJson(JsonConfig config) {
    this(config, new JsonSharedRegistry(config));
  }

  ForyJson(JsonConfig config, JsonSharedRegistry sharedRegistry) {
    this.config = config;
    this.sharedRegistry = sharedRegistry;
    secondaryPoolSize = config.concurrencyLevel() - 1;
    primarySlot =
        new AtomicReference<>(new PooledState(new JsonState(config, sharedRegistry), PRIMARY_SLOT));
    slots = new AtomicReferenceArray<>(secondaryPoolSize);
    for (int i = 0; i < secondaryPoolSize; i++) {
      slots.set(i, new PooledState(new JsonState(config, sharedRegistry), i));
    }
  }

  /** Returns a builder initialized with the documented default configuration. */
  public static ForyJsonBuilder builder() {
    return new ForyJsonBuilder();
  }

  /** Serializes {@code value} as one complete JSON document backed by a detached String. */
  public String toJson(Object value) {
    PooledState entry = acquire();
    JsonState state = entry.state;
    StringJsonWriter writer = state.stringWriter;
    try {
      state.typeResolver.lockJIT();
      try {
        if (value == null) {
          writer.writeNull();
        } else {
          JsonTypeInfo typeInfo = state.rootTypeInfo(value.getClass());
          typeInfo.stringWriter().writeString(writer, value);
        }
      } finally {
        state.typeResolver.unlockJIT();
      }
      return writer.toJson();
    } finally {
      try {
        writer.reset();
      } finally {
        release(entry);
      }
    }
  }

  /**
   * Serializes {@code value} using {@code declaredType}'s codec rather than runtime-type dispatch.
   *
   * <p>This overload is required when the declared type owns a closed {@code JsonSubTypes} table. A
   * non-null value must be assignable to the declared type. Primitive declarations accept only
   * their exact boxed carrier and reject null; {@code void} is never a JSON value type.
   */
  public <T> String toJson(T value, Class<T> declaredType) {
    requireDeclaredType(declaredType);
    validateWriteValue(value, declaredType);
    return toJsonDeclared(value, declaredType, declaredType);
  }

  /**
   * Serializes {@code value} using the generic codec captured by {@code declaredType}.
   *
   * <p>An explicit declared type controls the complete root schema, including closed subtype
   * metadata inside generic containers. A non-null value must be assignable to its raw type.
   */
  public <T> String toJson(T value, TypeRef<T> declaredType) {
    requireDeclaredType(declaredType);
    validateDeclaredType(declaredType.getType());
    Class<?> rawType = declaredType.getRawType();
    validateWriteValue(value, rawType);
    return toJsonDeclared(value, declaredType.getType(), rawType);
  }

  /** Serializes {@code value} as one complete JSON document in a detached UTF-8 byte array. */
  public byte[] toJsonBytes(Object value) {
    PooledState entry = acquire();
    JsonState state = entry.state;
    Utf8JsonWriter writer = state.utf8Writer;
    try {
      state.typeResolver.lockJIT();
      try {
        if (value == null) {
          writer.writeNull();
        } else {
          JsonTypeInfo typeInfo = state.rootTypeInfo(value.getClass());
          typeInfo.utf8Writer().writeUtf8(writer, value);
        }
      } finally {
        state.typeResolver.unlockJIT();
      }
      return writer.toJsonBytes();
    } finally {
      try {
        writer.reset();
      } finally {
        release(entry);
      }
    }
  }

  /** Serializes {@code value} as UTF-8 using {@code declaredType}'s codec. */
  public <T> byte[] toJsonBytes(T value, Class<T> declaredType) {
    requireDeclaredType(declaredType);
    validateWriteValue(value, declaredType);
    return toJsonBytesDeclared(value, declaredType, declaredType);
  }

  /** Serializes {@code value} as UTF-8 using the generic codec captured by {@code declaredType}. */
  public <T> byte[] toJsonBytes(T value, TypeRef<T> declaredType) {
    requireDeclaredType(declaredType);
    validateDeclaredType(declaredType.getType());
    Class<?> rawType = declaredType.getRawType();
    validateWriteValue(value, rawType);
    return toJsonBytesDeclared(value, declaredType.getType(), rawType);
  }

  /**
   * Serializes {@code value} as UTF-8 JSON to {@code output}.
   *
   * <p>The complete document is buffered before one write to the stream. This method neither
   * flushes nor closes the caller-owned stream.
   */
  public void writeJsonTo(Object value, OutputStream output) {
    Objects.requireNonNull(output, "output");
    PooledState entry = acquire();
    JsonState state = entry.state;
    Utf8JsonWriter writer = state.utf8Writer;
    try {
      state.typeResolver.lockJIT();
      try {
        if (value == null) {
          writer.writeNull();
        } else {
          JsonTypeInfo typeInfo = state.rootTypeInfo(value.getClass());
          typeInfo.utf8Writer().writeUtf8(writer, value);
        }
      } finally {
        state.typeResolver.unlockJIT();
      }
      writer.writeTo(output);
    } finally {
      try {
        writer.reset();
      } finally {
        release(entry);
      }
    }
  }

  /**
   * Writes UTF-8 JSON using {@code declaredType}'s codec without flushing or closing {@code
   * output}.
   */
  public <T> void writeJsonTo(T value, Class<T> declaredType, OutputStream output) {
    requireDeclaredType(declaredType);
    validateWriteValue(value, declaredType);
    writeJsonDeclared(value, declaredType, declaredType, output);
  }

  /**
   * Writes UTF-8 JSON using the generic codec captured by {@code declaredType}, without flushing or
   * closing {@code output}.
   */
  public <T> void writeJsonTo(T value, TypeRef<T> declaredType, OutputStream output) {
    requireDeclaredType(declaredType);
    validateDeclaredType(declaredType.getType());
    Class<?> rawType = declaredType.getRawType();
    validateWriteValue(value, rawType);
    writeJsonDeclared(value, declaredType.getType(), rawType, output);
  }

  private String toJsonDeclared(Object value, Type type, Class<?> fallback) {
    PooledState entry = acquire();
    JsonState state = entry.state;
    StringJsonWriter writer = state.stringWriter;
    try {
      state.typeResolver.lockJIT();
      try {
        state.rootTypeInfo(type, fallback).stringWriter().writeString(writer, value);
      } finally {
        state.typeResolver.unlockJIT();
      }
      return writer.toJson();
    } finally {
      try {
        writer.reset();
      } finally {
        release(entry);
      }
    }
  }

  private byte[] toJsonBytesDeclared(Object value, Type type, Class<?> fallback) {
    PooledState entry = acquire();
    JsonState state = entry.state;
    Utf8JsonWriter writer = state.utf8Writer;
    try {
      state.typeResolver.lockJIT();
      try {
        state.rootTypeInfo(type, fallback).utf8Writer().writeUtf8(writer, value);
      } finally {
        state.typeResolver.unlockJIT();
      }
      return writer.toJsonBytes();
    } finally {
      try {
        writer.reset();
      } finally {
        release(entry);
      }
    }
  }

  private void writeJsonDeclared(Object value, Type type, Class<?> fallback, OutputStream output) {
    Objects.requireNonNull(output, "output");
    PooledState entry = acquire();
    JsonState state = entry.state;
    Utf8JsonWriter writer = state.utf8Writer;
    try {
      state.typeResolver.lockJIT();
      try {
        state.rootTypeInfo(type, fallback).utf8Writer().writeUtf8(writer, value);
      } finally {
        state.typeResolver.unlockJIT();
      }
      writer.writeTo(output);
    } finally {
      try {
        writer.reset();
      } finally {
        release(entry);
      }
    }
  }

  private static void validateWriteValue(Object value, Class<?> declaredType) {
    if (declaredType == void.class || declaredType == Void.class) {
      throw new IllegalArgumentException("void is not a JSON value type");
    }
    if (declaredType.isPrimitive()) {
      if (value == null) {
        throw new IllegalArgumentException("Cannot write null as primitive " + declaredType);
      }
      Class<?> carrier = primitiveCarrier(declaredType);
      if (value.getClass() != carrier) {
        throw new IllegalArgumentException(
            "Value type " + value.getClass() + " does not match primitive " + declaredType);
      }
    } else if (value != null && !declaredType.isInstance(value)) {
      throw new IllegalArgumentException(
          "Value type " + value.getClass() + " is not assignable to " + declaredType);
    }
  }

  private static void requireDeclaredType(Object declaredType) {
    if (declaredType == null) {
      throw new IllegalArgumentException("declaredType must not be null");
    }
  }

  private static Class<?> primitiveCarrier(Class<?> type) {
    if (type == boolean.class) {
      return Boolean.class;
    }
    if (type == byte.class) {
      return Byte.class;
    }
    if (type == short.class) {
      return Short.class;
    }
    if (type == int.class) {
      return Integer.class;
    }
    if (type == long.class) {
      return Long.class;
    }
    if (type == float.class) {
      return Float.class;
    }
    if (type == double.class) {
      return Double.class;
    }
    return Character.class;
  }

  private static void validateDeclaredType(Type type) {
    if (type instanceof TypeVariable || type instanceof WildcardType) {
      throw new IllegalArgumentException("Typed JSON writes require a fully bound type: " + type);
    }
    if (type instanceof GenericArrayType) {
      validateDeclaredType(((GenericArrayType) type).getGenericComponentType());
      return;
    }
    if (type instanceof ParameterizedType) {
      ParameterizedType parameterized = (ParameterizedType) type;
      Type owner = parameterized.getOwnerType();
      if (owner != null) {
        validateDeclaredType(owner);
      }
      for (Type argument : parameterized.getActualTypeArguments()) {
        validateDeclaredType(argument);
      }
    }
  }

  /**
   * Parses exactly one JSON value from {@code json} using {@code type} as its declared Java type.
   * Trailing non-whitespace content is rejected.
   */
  public <T> T fromJson(String json, Class<T> type) {
    PooledState entry = acquire();
    JsonState state = entry.state;
    try {
      state.typeResolver.lockJIT();
      try {
        return castValue(readJavaStringValue(json, type, type, state), type);
      } finally {
        state.typeResolver.unlockJIT();
      }
    } finally {
      try {
        state.clearStringReaders();
      } finally {
        release(entry);
      }
    }
  }

  /**
   * Parses exactly one JSON value using a generic type captured by {@link TypeRef}. Trailing
   * non-whitespace content is rejected.
   */
  public <T> T fromJson(String json, TypeRef<T> typeRef) {
    PooledState entry = acquire();
    JsonState state = entry.state;
    try {
      state.typeResolver.lockJIT();
      try {
        Object value = readJavaStringValue(json, typeRef.getType(), typeRef.getRawType(), state);
        return castValue(value, typeRef);
      } finally {
        state.typeResolver.unlockJIT();
      }
    } finally {
      try {
        state.clearStringReaders();
      } finally {
        release(entry);
      }
    }
  }

  /**
   * Parses exactly one UTF-8 JSON value from {@code bytes} using {@code type} as its declared Java
   * type. Trailing non-whitespace content is rejected.
   */
  public <T> T fromJson(byte[] bytes, Class<T> type) {
    PooledState entry = acquire();
    JsonState state = entry.state;
    try {
      state.typeResolver.lockJIT();
      try {
        return castValue(readUtf8Value(state.utf8Reader(bytes), type, type, state), type);
      } finally {
        state.typeResolver.unlockJIT();
      }
    } finally {
      try {
        state.clearUtf8Reader();
      } finally {
        release(entry);
      }
    }
  }

  /**
   * Parses exactly one UTF-8 JSON value using a generic type captured by {@link TypeRef}. Trailing
   * non-whitespace content is rejected.
   */
  public <T> T fromJson(byte[] bytes, TypeRef<T> typeRef) {
    PooledState entry = acquire();
    JsonState state = entry.state;
    try {
      state.typeResolver.lockJIT();
      try {
        Object value =
            readUtf8Value(state.utf8Reader(bytes), typeRef.getType(), typeRef.getRawType(), state);
        return castValue(value, typeRef);
      } finally {
        state.typeResolver.unlockJIT();
      }
    } finally {
      try {
        state.clearUtf8Reader();
      } finally {
        release(entry);
      }
    }
  }

  private PooledState acquire() {
    PooledState entry = primarySlot.get();
    if (entry != null && primarySlot.compareAndSet(entry, null)) {
      return entry;
    }
    if (secondaryPoolSize == 0) {
      return new PooledState(new JsonState(config, sharedRegistry), UNPOOLED_SLOT);
    }
    int slotIndex = slotIndexForCurrentThread();
    entry = tryBorrowPreferredSlots(slotIndex);
    if (entry != null) {
      return entry;
    }
    return new PooledState(new JsonState(config, sharedRegistry), UNPOOLED_SLOT);
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
    if (index == secondaryPoolSize) {
      index = 0;
    }
    for (int i = 1; i < secondaryPoolSize; i++) {
      entry = tryBorrowSlot(index);
      if (entry != null) {
        return entry;
      }
      index++;
      if (index == secondaryPoolSize) {
        index = 0;
      }
    }
    return null;
  }

  private PooledState tryBorrowSlot(int index) {
    return slots.getAndSet(index, null);
  }

  private int slotIndexForCurrentThread() {
    return Math.floorMod(
        spread(System.identityHashCode(Thread.currentThread())), secondaryPoolSize);
  }

  private static int spread(int hash) {
    return hash ^ (hash >>> 16);
  }

  private Object readJavaStringValue(String json, Type type, Class<?> fallback, JsonState state) {
    if (StringSerializer.isBytesBackedString()) {
      byte coder = StringSerializer.getStringCoder(json);
      if (StringSerializer.isLatin1Coder(coder)) {
        // Keep String input on its reader owner even when ASCII Latin1 bytes match UTF-8;
        // custom JsonValueCodec implementations can observe readLatin1/readUtf16 dispatch.
        return readLatin1Value(state.latin1Reader(json), type, fallback, state);
      }
      if (StringSerializer.isUtf16Coder(coder)) {
        return readUtf16Value(state.utf16Reader(json), type, fallback, state);
      }
    }
    return readUtf16Value(state.charBackedUtf16Reader(json), type, fallback, state);
  }

  private Object readLatin1Value(
      Latin1JsonReader reader, Type type, Class<?> fallback, JsonState state) {
    JsonTypeInfo typeInfo = state.rootTypeInfo(type, fallback);
    Object value = typeInfo.latin1Reader().readLatin1(reader);
    reader.finish();
    return value;
  }

  private Object readUtf16Value(
      Utf16JsonReader reader, Type type, Class<?> fallback, JsonState state) {
    JsonTypeInfo typeInfo = state.rootTypeInfo(type, fallback);
    Object value = typeInfo.utf16Reader().readUtf16(reader);
    reader.finish();
    return value;
  }

  private Object readUtf8Value(
      Utf8JsonReader reader, Type type, Class<?> fallback, JsonState state) {
    JsonTypeInfo typeInfo = state.rootTypeInfo(type, fallback);
    Object value = typeInfo.utf8Reader().readUtf8(reader);
    reader.finish();
    return value;
  }

  @SuppressWarnings("unchecked")
  private static <T> T castValue(Object value, Class<T> type) {
    if (!type.isPrimitive()) {
      return type.cast(value);
    }
    if (value == null) {
      throw primitiveNull(type);
    }
    return (T) value;
  }

  @SuppressWarnings("unchecked")
  private static <T> T castValue(Object value, TypeRef<T> typeRef) {
    Class<?> rawType = typeRef.getRawType();
    if (!rawType.isPrimitive()) {
      return (T) rawType.cast(value);
    }
    if (value == null) {
      throw primitiveNull(rawType);
    }
    return (T) value;
  }

  private static ForyJsonException primitiveNull(Class<?> type) {
    return new ForyJsonException("Cannot read null into primitive " + type);
  }

  /** Associates a reusable state with the slot to which it may be returned. */
  private static final class PooledState {
    private final JsonState state;
    private final int homeIndex;

    private PooledState(JsonState state, int homeIndex) {
      this.state = state;
      this.homeIndex = homeIndex;
    }
  }

  /**
   * Complete mutable execution state for one borrowed root operation.
   *
   * <p>The resolver is constructed first and retained by all five readers and writers. Codecs
   * obtain dynamic child bindings from the active reader or writer instead of receiving a resolver
   * through every capability call. The last-root cache is state-local and only avoids repeated
   * resolver lookup for an identical declared type and fallback pair.
   */
  private static final class JsonState {
    private final JsonTypeResolver typeResolver;
    private final Utf8JsonWriter utf8Writer;
    private final StringJsonWriter stringWriter;
    private final Utf8JsonReader utf8Reader;
    private final Latin1JsonReader latin1Reader;
    private final Utf16JsonReader utf16Reader;
    private byte[] charBackedUtf16Bytes;
    private Type lastRootType;
    private Class<?> lastRootFallback;
    private JsonTypeInfo lastRootInfo;

    private JsonState(JsonConfig config, JsonSharedRegistry sharedRegistry) {
      typeResolver = new JsonTypeResolver(sharedRegistry);
      utf8Writer = new Utf8JsonWriter(config, typeResolver, new byte[INITIAL_BUFFER_SIZE]);
      stringWriter = new StringJsonWriter(config, typeResolver, new byte[INITIAL_BUFFER_SIZE]);
      utf8Reader = new Utf8JsonReader(config, typeResolver);
      latin1Reader = new Latin1JsonReader(config, typeResolver);
      utf16Reader = new Utf16JsonReader(config, typeResolver);
      charBackedUtf16Bytes = EMPTY_BYTES;
    }

    private Latin1JsonReader latin1Reader(String input) {
      latin1Reader.reset(input);
      return latin1Reader;
    }

    private Utf16JsonReader utf16Reader(String input) {
      utf16Reader.reset(input);
      return utf16Reader;
    }

    private Utf16JsonReader charBackedUtf16Reader(String input) {
      int length = input.length();
      if (length > (Integer.MAX_VALUE >>> 1)) {
        throw new IllegalArgumentException("String is too large");
      }
      int numBytes = length << 1;
      byte[] bytes;
      if (numBytes <= RETAINED_UTF16_BYTES) {
        bytes = charBackedUtf16Bytes;
        if (bytes.length < numBytes) {
          bytes = new byte[Math.max(numBytes, INITIAL_BUFFER_SIZE)];
          charBackedUtf16Bytes = bytes;
        }
      } else {
        bytes = new byte[numBytes];
      }
      // JDK 8 char[]-backed Strings are converted once so parsing still uses UTF16 byte loads.
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

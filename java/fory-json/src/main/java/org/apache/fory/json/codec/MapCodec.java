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

package org.apache.fory.json.codec;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.AbstractMap;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import org.apache.fory.annotation.Internal;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.JsonObject;
import org.apache.fory.json.meta.JsonFieldNameHash;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.JsonWriter;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.reflect.TypeRef;

/**
 * Codec family for declared Java map types and JSON object member-name conversion.
 *
 * <p>The factory consumes the declared {@link TypeRef} during cold construction, resolves key and
 * value semantics once, and retains only a {@link MapFactory}, a key codec when needed, and the
 * resolved value {@link JsonTypeInfo}. JSON object keys are strings on the wire, so {@link
 * MapKeyCodec} owns the supported string, enum, and numeric name conversions. Value loops select
 * one concrete reader or writer capability for the active representation rather than resolving a
 * binding for every entry.
 *
 * <p>Readers do not preallocate from input because JSON has no trusted member count. Dynamic {@code
 * Object} values use insertion-ordered {@link JsonObject}; typed targets use the selected map
 * factory and optional immutable finish conversion.
 */
public abstract class MapCodec<T extends Map<?, ?>> implements JsonValueCodec<T> {
  private static final Class<?> UNTYPED_MAP = LinkedHashMap.class;
  private static final MapKeyCodec STRING_KEY_CODEC =
      new MapKeyCodec() {
        @Override
        public String toName(Object key) {
          return (String) key;
        }

        @Override
        public Object fromName(String name) {
          return name;
        }
      };
  private static final MapKeyCodec OBJECT_KEY_CODEC =
      new MapKeyCodec() {
        @Override
        public String toName(Object key) {
          if (key instanceof String) {
            return (String) key;
          }
          if (key instanceof Number || key instanceof Boolean || key instanceof Character) {
            return key.toString();
          }
          if (key instanceof Enum) {
            return ((Enum<?>) key).name();
          }
          throw new ForyJsonException("Unsupported JSON map key type " + key.getClass());
        }

        @Override
        public Object fromName(String name) {
          return name;
        }
      };

  private final MapFactory factory;

  MapCodec(MapFactory factory) {
    this.factory = factory;
  }

  public static MapCodec<?> create(
      Class<?> rawType, TypeRef<?> typeRef, JsonTypeResolver resolver) {
    Tuple2<TypeRef<?>, TypeRef<?>> keyValueTypeRefs = CodecUtils.mapKeyValueTypeRefs(typeRef);
    Type keyType = keyValueTypeRefs.f0.getType();
    Class<?> keyRawType = CodecUtils.rawType(keyType, Object.class);
    Type valueType = keyValueTypeRefs.f1.getType();
    Class<?> valueRawType = CodecUtils.rawType(valueType, Object.class);
    resolver.checkMapKeySecure(keyRawType);
    MapFactory factory = mapFactory(rawType, keyRawType);
    JsonTypeInfo valueTypeInfo = resolver.getTypeInfo(valueType, valueRawType);
    return create(factory, keyRawType, valueTypeInfo);
  }

  @Internal
  public static MapCodec<?> create(
      Class<?> rawType, Class<?> keyRawType, JsonTypeInfo valueTypeInfo) {
    return create(mapFactory(rawType, keyRawType), keyRawType, valueTypeInfo);
  }

  @Internal
  public static MapCodec<?> create(
      Class<?> rawType, Class<?> keyRawType, JsonTypeInfo valueTypeInfo, MapKeyCodec keyCodec) {
    return new GenericMapCodec(
        mapFactory(rawType, keyRawType),
        new CheckedMapKeyCodec(keyRawType, keyCodec),
        valueTypeInfo);
  }

  private static MapCodec<?> create(
      MapFactory factory, Class<?> keyRawType, JsonTypeInfo valueTypeInfo) {
    Object valueCodec = valueTypeInfo.stringWriter();
    if (keyRawType == String.class) {
      if (valueCodec == ScalarCodecs.StringCodec.INSTANCE) {
        return new StringStringMapCodec(factory);
      }
      if (valueCodec == ScalarCodecs.BooleanCodec.BOXED) {
        return new StringBooleanMapCodec(factory);
      }
      if (valueCodec == ScalarCodecs.IntCodec.BOXED) {
        return new StringIntMapCodec(factory);
      }
      if (valueCodec == ScalarCodecs.LongCodec.BOXED) {
        return new StringLongMapCodec(factory);
      }
      if (valueCodec == ScalarCodecs.ShortCodec.BOXED) {
        return new StringShortMapCodec(factory);
      }
      if (valueCodec == ScalarCodecs.ByteCodec.BOXED) {
        return new StringByteMapCodec(factory);
      }
      if (valueCodec == ScalarCodecs.FloatCodec.BOXED) {
        return new StringFloatMapCodec(factory);
      }
      if (valueCodec == ScalarCodecs.DoubleCodec.BOXED) {
        return new StringDoubleMapCodec(factory);
      }
      if (valueCodec == ScalarCodecs.BigIntegerCodec.INSTANCE) {
        return new StringBigIntegerMapCodec(factory);
      }
      if (valueCodec == ScalarCodecs.BigDecimalCodec.INSTANCE) {
        return new StringBigDecimalMapCodec(factory);
      }
    }
    if (keyRawType == Object.class) {
      return new GenericMapCodec(factory, OBJECT_KEY_CODEC, valueTypeInfo);
    }
    if (valueCodec == ScalarCodecs.StringCodec.INSTANCE && isNumericKey(keyRawType)) {
      return new NumberStringMapCodec(factory, defaultKeyCodec(keyRawType));
    }
    return new GenericMapCodec(factory, defaultKeyCodec(keyRawType), valueTypeInfo);
  }

  static Map<Object, Object> readUntyped(Latin1JsonReader reader) {
    JsonTypeInfo valueInfo = reader.typeResolver().getTypeInfo(Object.class, Object.class);
    Map<Object, Object> map = (Map<Object, Object>) (Map<?, ?>) new JsonObject();
    Latin1ReaderCodec<Object> codec = valueInfo.latin1Reader();
    reader.enterDepth();
    reader.expectNextToken('{');
    if (!reader.consumeNextToken('}')) {
      do {
        Object key = STRING_KEY_CODEC.readName(reader);
        reader.expectNextToken(':');
        map.put(key, codec.readLatin1(reader));
      } while (reader.consumeNextToken(','));
      reader.expectNextToken('}');
    }
    reader.exitDepth();
    return map;
  }

  static Map<Object, Object> readUntyped(Utf16JsonReader reader) {
    JsonTypeInfo valueInfo = reader.typeResolver().getTypeInfo(Object.class, Object.class);
    Map<Object, Object> map = (Map<Object, Object>) (Map<?, ?>) new JsonObject();
    Utf16ReaderCodec<Object> codec = valueInfo.utf16Reader();
    reader.enterDepth();
    reader.expectNextToken('{');
    if (!reader.consumeNextToken('}')) {
      do {
        Object key = STRING_KEY_CODEC.readName(reader);
        reader.expectNextToken(':');
        map.put(key, codec.readUtf16(reader));
      } while (reader.consumeNextToken(','));
      reader.expectNextToken('}');
    }
    reader.exitDepth();
    return map;
  }

  static Map<Object, Object> readUntyped(Utf8JsonReader reader) {
    JsonTypeInfo valueInfo = reader.typeResolver().getTypeInfo(Object.class, Object.class);
    Map<Object, Object> map = (Map<Object, Object>) (Map<?, ?>) new JsonObject();
    Utf8ReaderCodec<Object> codec = valueInfo.utf8Reader();
    reader.enterDepth();
    reader.expectNextToken('{');
    if (!reader.consumeNextToken('}')) {
      do {
        Object key = STRING_KEY_CODEC.readName(reader);
        reader.expectNextToken(':');
        map.put(key, codec.readUtf8(reader));
      } while (reader.consumeNextToken(','));
      reader.expectNextToken('}');
    }
    reader.exitDepth();
    return map;
  }

  final Map<Object, Object> newMap() {
    return factory.newMap();
  }

  final Map<?, ?> finishMap(Map<Object, Object> map) {
    return factory.finish(map);
  }

  private static void writeKey(JsonWriter writer, Object key, MapKeyCodec keyCodec) {
    if (key == null) {
      throw new ForyJsonException("JSON map key cannot be null");
    }
    keyCodec.writeName(writer, key);
  }

  private static MapKeyCodec defaultKeyCodec(Class<?> rawType) {
    if (rawType == String.class) {
      return STRING_KEY_CODEC;
    }
    if (rawType == Object.class) {
      return OBJECT_KEY_CODEC;
    }
    if (rawType.isEnum()) {
      return new EnumKeyCodec(rawType);
    }
    if (rawType == int.class || rawType == Integer.class) {
      return IntKeyCodec.INSTANCE;
    }
    if (rawType == long.class || rawType == Long.class) {
      return LongKeyCodec.INSTANCE;
    }
    if (rawType == short.class || rawType == Short.class) {
      return ShortKeyCodec.INSTANCE;
    }
    if (rawType == byte.class || rawType == Byte.class) {
      return ByteKeyCodec.INSTANCE;
    }
    throw new ForyJsonException("Unsupported JSON map key type " + rawType);
  }

  @SuppressWarnings("unchecked")
  private static MapFactory mapFactory(Class<?> rawType, Class<?> keyRawType) {
    if (unsupportedMapType(rawType) || GuavaCodecs.isUnsupportedImmutableImpl(rawType)) {
      return unsupportedMapFactory(rawType);
    }
    MapFactory guavaFactory = GuavaCodecs.mapFactory(rawType);
    if (guavaFactory != null) {
      return guavaFactory;
    }
    if (rawType == JsonObject.class) {
      return () -> (Map<Object, Object>) (Map<?, ?>) new JsonObject();
    }
    if (rawType == EnumMap.class) {
      if (!keyRawType.isEnum()) {
        throw new ForyJsonException("EnumMap requires an enum key type");
      }
      return () -> new EnumMap(keyRawType);
    }
    if (rawType == AbstractMap.class) {
      return () -> new LinkedHashMap<>(0);
    }
    if (rawType == UNTYPED_MAP || rawType.isInterface()) {
      if (ConcurrentMap.class.isAssignableFrom(rawType)) {
        if (NavigableMap.class.isAssignableFrom(rawType)
            || SortedMap.class.isAssignableFrom(rawType)) {
          return ConcurrentSkipListMap::new;
        }
        return ConcurrentHashMap::new;
      }
      if (NavigableMap.class.isAssignableFrom(rawType)
          || SortedMap.class.isAssignableFrom(rawType)) {
        return TreeMap::new;
      }
      return () -> new LinkedHashMap<>(0);
    }
    return () -> {
      try {
        return (Map<Object, Object>) rawType.newInstance();
      } catch (ReflectiveOperationException e) {
        throw new ForyJsonException("Cannot create map " + rawType, e);
      }
    };
  }

  private static MapFactory unsupportedMapFactory(Class<?> rawType) {
    return () -> {
      throw new ForyJsonException("Unsupported JSON map type " + rawType);
    };
  }

  private static boolean unsupportedMapType(Class<?> rawType) {
    String name = rawType.getName();
    return name.startsWith("java.util.ImmutableCollections$")
        || name.startsWith("java.util.Collections$Empty")
        || name.startsWith("java.util.Collections$Singleton")
        || name.startsWith("java.util.Collections$Unmodifiable");
  }

  private static boolean isNumericKey(Class<?> type) {
    return type == int.class
        || type == Integer.class
        || type == long.class
        || type == Long.class
        || type == short.class
        || type == Short.class
        || type == byte.class
        || type == Byte.class;
  }

  interface MapFactory {
    Map<Object, Object> newMap();

    default Map<?, ?> finish(Map<Object, Object> map) {
      return map;
    }
  }

  public static final class GenericMapCodec extends MapCodec<Map<?, ?>> {
    private final MapKeyCodec keyCodec;
    private final JsonTypeInfo valueTypeInfo;

    private GenericMapCodec(MapFactory factory, MapKeyCodec keyCodec, JsonTypeInfo valueTypeInfo) {
      super(factory);
      this.keyCodec = keyCodec;
      this.valueTypeInfo = valueTypeInfo;
    }

    @Override
    public void writeString(StringJsonWriter writer, Map<?, ?> value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      StringWriterCodec<Object> codec = valueTypeInfo.stringWriter();
      writer.writeObjectStart();
      int index = 0;
      for (Map.Entry<?, ?> entry : value.entrySet()) {
        writer.writeComma(index++);
        writeKey(writer, entry.getKey(), keyCodec);
        codec.writeString(writer, entry.getValue());
      }
      writer.writeObjectEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Map<?, ?> value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      Utf8WriterCodec<Object> codec = valueTypeInfo.utf8Writer();
      writer.writeObjectStart();
      int index = 0;
      for (Map.Entry<?, ?> entry : value.entrySet()) {
        writer.writeComma(index++);
        writeKey(writer, entry.getKey(), keyCodec);
        codec.writeUtf8(writer, entry.getValue());
      }
      writer.writeObjectEnd();
    }

    @Override
    public Map<?, ?> readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      Map<Object, Object> map = newMap();
      Latin1ReaderCodec<Object> codec = valueTypeInfo.latin1Reader();
      reader.expectNextToken('{');
      if (!reader.consumeNextToken('}')) {
        do {
          Object key = keyCodec.readName(reader);
          reader.expectNextToken(':');
          map.put(key, codec.readLatin1(reader));
        } while (reader.consumeNextToken(','));
        reader.expectNextToken('}');
      }
      reader.exitDepth();
      return finishMap(map);
    }

    @Override
    public Map<?, ?> readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      Map<Object, Object> map = newMap();
      Utf16ReaderCodec<Object> codec = valueTypeInfo.utf16Reader();
      reader.expectNextToken('{');
      if (!reader.consumeNextToken('}')) {
        do {
          Object key = keyCodec.readName(reader);
          reader.expectNextToken(':');
          map.put(key, codec.readUtf16(reader));
        } while (reader.consumeNextToken(','));
        reader.expectNextToken('}');
      }
      reader.exitDepth();
      return finishMap(map);
    }

    @Override
    public Map<?, ?> readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      Map<Object, Object> map = newMap();
      Utf8ReaderCodec<Object> codec = valueTypeInfo.utf8Reader();
      reader.expectNextToken('{');
      if (!reader.consumeNextToken('}')) {
        do {
          Object key = keyCodec.readName(reader);
          reader.expectNextToken(':');
          map.put(key, codec.readUtf8(reader));
        } while (reader.consumeNextToken(','));
        reader.expectNextToken('}');
      }
      reader.exitDepth();
      return finishMap(map);
    }
  }

  public abstract static class StringKeyMapCodec extends MapCodec<Map<?, ?>> {
    StringKeyMapCodec(MapFactory factory) {
      super(factory);
    }

    @Override
    public final Map<?, ?> readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      Map<Object, Object> map = newMap();
      reader.expectNextToken('{');
      if (!reader.consumeNextToken('}')) {
        do {
          String key = reader.readString();
          reader.expectNextToken(':');
          map.put(key, readLatin1Value(reader));
        } while (reader.consumeNextToken(','));
        reader.expectNextToken('}');
      }
      reader.exitDepth();
      return finishMap(map);
    }

    @Override
    public final Map<?, ?> readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      Map<Object, Object> map = newMap();
      reader.expectNextToken('{');
      if (!reader.consumeNextToken('}')) {
        do {
          String key = reader.readString();
          reader.expectNextToken(':');
          map.put(key, readUtf16Value(reader));
        } while (reader.consumeNextToken(','));
        reader.expectNextToken('}');
      }
      reader.exitDepth();
      return finishMap(map);
    }

    @Override
    public final Map<?, ?> readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      Map<Object, Object> map = newMap();
      reader.expectNextToken('{');
      if (!reader.consumeNextToken('}')) {
        do {
          String key = reader.readString();
          reader.expectNextToken(':');
          map.put(key, readUtf8Value(reader));
        } while (reader.consumeNextToken(','));
        reader.expectNextToken('}');
      }
      reader.exitDepth();
      return finishMap(map);
    }

    // Each scalar specialization owns the complete map value, including JSON null.
    abstract Object readLatin1Value(Latin1JsonReader reader);

    abstract Object readUtf16Value(Utf16JsonReader reader);

    abstract Object readUtf8Value(Utf8JsonReader reader);
  }

  public static final class StringStringMapCodec extends StringKeyMapCodec {
    private StringStringMapCodec(MapFactory factory) {
      super(factory);
    }

    private void writeMap(JsonWriter writer, Object value) {
      writer.writeObjectStart();
      int index = 0;
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        writer.writeComma(index++);
        writer.writeFieldName((String) entry.getKey());
        Object element = entry.getValue();
        if (element == null) {
          writer.writeNull();
        } else {
          writer.writeString((String) element);
        }
      }
      writer.writeObjectEnd();
    }

    @Override
    public void writeString(StringJsonWriter writer, Map<?, ?> value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      writeMap(writer, value);
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Map<?, ?> value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      writeMap(writer, value);
    }

    @Override
    Object readLatin1Value(Latin1JsonReader reader) {
      return reader.readNullableString();
    }

    @Override
    Object readUtf16Value(Utf16JsonReader reader) {
      return reader.readNullableString();
    }

    @Override
    Object readUtf8Value(Utf8JsonReader reader) {
      return reader.readNullableString();
    }
  }

  public static final class StringBooleanMapCodec extends StringKeyMapCodec {
    private StringBooleanMapCodec(MapFactory factory) {
      super(factory);
    }

    private void writeMap(JsonWriter writer, Object value) {
      writer.writeObjectStart();
      int index = 0;
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        writer.writeComma(index++);
        writer.writeFieldName((String) entry.getKey());
        Object element = entry.getValue();
        if (element == null) {
          writer.writeNull();
        } else {
          writer.writeBoolean((boolean) element);
        }
      }
      writer.writeObjectEnd();
    }

    @Override
    public void writeString(StringJsonWriter writer, Map<?, ?> value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      writeMap(writer, value);
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Map<?, ?> value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      writeMap(writer, value);
    }

    @Override
    Object readLatin1Value(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readBooleanValue();
    }

    @Override
    Object readUtf16Value(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readBooleanValue();
    }

    @Override
    Object readUtf8Value(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readBooleanValue();
    }
  }

  public abstract static class StringNumberMapCodec extends StringKeyMapCodec {
    StringNumberMapCodec(MapFactory factory) {
      super(factory);
    }

    private void writeMap(JsonWriter writer, Object value) {
      writer.writeObjectStart();
      int index = 0;
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        writer.writeComma(index++);
        writer.writeFieldName((String) entry.getKey());
        Object element = entry.getValue();
        if (element == null) {
          writer.writeNull();
        } else {
          writeNumber(writer, element);
        }
      }
      writer.writeObjectEnd();
    }

    @Override
    public final void writeString(StringJsonWriter writer, Map<?, ?> value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      writeMap(writer, value);
    }

    @Override
    public final void writeUtf8(Utf8JsonWriter writer, Map<?, ?> value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      writeMap(writer, value);
    }

    abstract void writeNumber(JsonWriter writer, Object value);
  }

  public static final class StringIntMapCodec extends StringNumberMapCodec {
    private StringIntMapCodec(MapFactory factory) {
      super(factory);
    }

    @Override
    void writeNumber(JsonWriter writer, Object value) {
      writer.writeInt((int) value);
    }

    @Override
    Object readLatin1Value(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readIntValue();
    }

    @Override
    Object readUtf16Value(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readIntValue();
    }

    @Override
    Object readUtf8Value(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readIntValue();
    }
  }

  public static final class StringLongMapCodec extends StringNumberMapCodec {
    private StringLongMapCodec(MapFactory factory) {
      super(factory);
    }

    @Override
    void writeNumber(JsonWriter writer, Object value) {
      writer.writeLong((long) value);
    }

    @Override
    Object readLatin1Value(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readLongValue();
    }

    @Override
    Object readUtf16Value(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readLongValue();
    }

    @Override
    Object readUtf8Value(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readLongValue();
    }
  }

  public static final class StringShortMapCodec extends StringNumberMapCodec {
    private StringShortMapCodec(MapFactory factory) {
      super(factory);
    }

    @Override
    void writeNumber(JsonWriter writer, Object value) {
      writer.writeInt((short) value);
    }

    @Override
    Object readLatin1Value(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : readShort(reader.readIntValue());
    }

    @Override
    Object readUtf16Value(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : readShort(reader.readIntValue());
    }

    @Override
    Object readUtf8Value(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : readShort(reader.readIntValue());
    }
  }

  public static final class StringByteMapCodec extends StringNumberMapCodec {
    private StringByteMapCodec(MapFactory factory) {
      super(factory);
    }

    @Override
    void writeNumber(JsonWriter writer, Object value) {
      writer.writeInt((byte) value);
    }

    @Override
    Object readLatin1Value(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : readByte(reader.readIntValue());
    }

    @Override
    Object readUtf16Value(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : readByte(reader.readIntValue());
    }

    @Override
    Object readUtf8Value(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : readByte(reader.readIntValue());
    }
  }

  public static final class StringFloatMapCodec extends StringNumberMapCodec {
    private StringFloatMapCodec(MapFactory factory) {
      super(factory);
    }

    @Override
    void writeNumber(JsonWriter writer, Object value) {
      writer.writeFloat((float) value);
    }

    @Override
    Object readLatin1Value(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readFloatTokenValue();
    }

    @Override
    Object readUtf16Value(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readFloatTokenValue();
    }

    @Override
    Object readUtf8Value(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readFloatTokenValue();
    }
  }

  public static final class StringDoubleMapCodec extends StringNumberMapCodec {
    private StringDoubleMapCodec(MapFactory factory) {
      super(factory);
    }

    @Override
    void writeNumber(JsonWriter writer, Object value) {
      writer.writeDouble((double) value);
    }

    @Override
    Object readLatin1Value(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readDoubleTokenValue();
    }

    @Override
    Object readUtf16Value(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readDoubleTokenValue();
    }

    @Override
    Object readUtf8Value(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readDoubleTokenValue();
    }
  }

  public static final class StringBigIntegerMapCodec extends StringNumberMapCodec {
    private StringBigIntegerMapCodec(MapFactory factory) {
      super(factory);
    }

    @Override
    void writeNumber(JsonWriter writer, Object value) {
      writer.writeBigInteger((BigInteger) value);
    }

    @Override
    Object readLatin1Value(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readBigInteger();
    }

    @Override
    Object readUtf16Value(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readBigInteger();
    }

    @Override
    Object readUtf8Value(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readBigInteger();
    }
  }

  public static final class StringBigDecimalMapCodec extends StringNumberMapCodec {
    private StringBigDecimalMapCodec(MapFactory factory) {
      super(factory);
    }

    @Override
    void writeNumber(JsonWriter writer, Object value) {
      writer.writeBigDecimal((BigDecimal) value);
    }

    @Override
    Object readLatin1Value(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readBigDecimal();
    }

    @Override
    Object readUtf16Value(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readBigDecimal();
    }

    @Override
    Object readUtf8Value(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : reader.readBigDecimal();
    }
  }

  public static final class NumberStringMapCodec extends MapCodec<Map<?, ?>> {
    private final MapKeyCodec keyCodec;

    private NumberStringMapCodec(MapFactory factory, MapKeyCodec keyCodec) {
      super(factory);
      this.keyCodec = keyCodec;
    }

    private void writeMap(JsonWriter writer, Object value) {
      writer.writeObjectStart();
      int index = 0;
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        writer.writeComma(index++);
        writeKey(writer, entry.getKey(), keyCodec);
        Object element = entry.getValue();
        if (element == null) {
          writer.writeNull();
        } else {
          writer.writeString((String) element);
        }
      }
      writer.writeObjectEnd();
    }

    @Override
    public void writeString(StringJsonWriter writer, Map<?, ?> value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      writeMap(writer, value);
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Map<?, ?> value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      writeMap(writer, value);
    }

    @Override
    public Map<?, ?> readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      Map<Object, Object> map = newMap();
      reader.expectNextToken('{');
      if (!reader.consumeNextToken('}')) {
        do {
          Object key = keyCodec.readName(reader);
          reader.expectNextToken(':');
          map.put(key, reader.readNullableString());
        } while (reader.consumeNextToken(','));
        reader.expectNextToken('}');
      }
      reader.exitDepth();
      return finishMap(map);
    }

    @Override
    public Map<?, ?> readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      Map<Object, Object> map = newMap();
      reader.expectNextToken('{');
      if (!reader.consumeNextToken('}')) {
        do {
          Object key = keyCodec.readName(reader);
          reader.expectNextToken(':');
          map.put(key, reader.readNullableString());
        } while (reader.consumeNextToken(','));
        reader.expectNextToken('}');
      }
      reader.exitDepth();
      return finishMap(map);
    }

    @Override
    public Map<?, ?> readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      Map<Object, Object> map = newMap();
      reader.expectNextToken('{');
      if (!reader.consumeNextToken('}')) {
        do {
          Object key = keyCodec.readName(reader);
          reader.expectNextToken(':');
          map.put(key, reader.readNullableString());
        } while (reader.consumeNextToken(','));
        reader.expectNextToken('}');
      }
      reader.exitDepth();
      return finishMap(map);
    }
  }

  private static final class CheckedMapKeyCodec implements MapKeyCodec {
    private final Class<?> keyType;
    private final MapKeyCodec delegate;

    private CheckedMapKeyCodec(Class<?> keyType, MapKeyCodec delegate) {
      this.keyType = keyType;
      this.delegate = delegate;
    }

    @Override
    public String toName(Object key) {
      return delegate.toName(key);
    }

    @Override
    public Object fromName(String name) {
      return checkKey(delegate.fromName(name));
    }

    @Override
    public void writeName(JsonWriter writer, Object key) {
      delegate.writeName(writer, key);
    }

    @Override
    public Object readName(JsonReader reader) {
      return checkKey(delegate.readName(reader));
    }

    private Object checkKey(Object key) {
      if (key == null || keyType != Object.class && !keyType.isInstance(key)) {
        throw new ForyJsonException(
            "JSON map key codec returned "
                + (key == null ? "null" : key.getClass().getName())
                + " for "
                + keyType.getName());
      }
      return key;
    }
  }

  public static final class EnumKeyCodec implements MapKeyCodec {
    private final Class<?> type;
    private final long[] nameHashes;
    private final Enum<?>[] values;

    @SuppressWarnings("unchecked")
    private EnumKeyCodec(Class<?> type) {
      this.type = type;
      Enum<?>[] constants = (Enum<?>[]) type.getEnumConstants();
      nameHashes = new long[constants.length];
      values = new Enum<?>[constants.length];
      for (int i = 0; i < constants.length; i++) {
        Enum<?> constant = constants[i];
        nameHashes[i] = JsonFieldNameHash.hash(constant.name());
        values[i] = constant;
      }
    }

    @Override
    public String toName(Object key) {
      return ((Enum<?>) key).name();
    }

    @Override
    public Object fromName(String name) {
      return enumValue(JsonFieldNameHash.hash(name));
    }

    @Override
    public Object readName(JsonReader reader) {
      return enumValue(reader.readFieldNameHash());
    }

    private Enum<?> enumValue(long nameHash) {
      long[] localHashes = nameHashes;
      for (int i = 0; i < localHashes.length; i++) {
        if (localHashes[i] == nameHash) {
          return values[i];
        }
      }
      throw new ForyJsonException("Unknown enum map key for " + type);
    }
  }

  public static final class IntKeyCodec implements MapKeyCodec {
    private static final IntKeyCodec INSTANCE = new IntKeyCodec();

    @Override
    public String toName(Object key) {
      return String.valueOf((int) key);
    }

    @Override
    public Object fromName(String name) {
      return Integer.parseInt(name);
    }

    @Override
    public void writeName(JsonWriter writer, Object key) {
      writer.writeIntFieldName((int) key);
    }

    @Override
    public Object readName(JsonReader reader) {
      return reader.readFieldNameInt();
    }
  }

  public static final class LongKeyCodec implements MapKeyCodec {
    private static final LongKeyCodec INSTANCE = new LongKeyCodec();

    @Override
    public String toName(Object key) {
      return String.valueOf((long) key);
    }

    @Override
    public Object fromName(String name) {
      return Long.parseLong(name);
    }

    @Override
    public void writeName(JsonWriter writer, Object key) {
      writer.writeLongFieldName((long) key);
    }

    @Override
    public Object readName(JsonReader reader) {
      return reader.readFieldNameLong();
    }
  }

  public static final class ShortKeyCodec implements MapKeyCodec {
    private static final ShortKeyCodec INSTANCE = new ShortKeyCodec();

    @Override
    public String toName(Object key) {
      return String.valueOf((short) key);
    }

    @Override
    public Object fromName(String name) {
      return readShort(Integer.parseInt(name));
    }

    @Override
    public void writeName(JsonWriter writer, Object key) {
      writer.writeIntFieldName((short) key);
    }

    @Override
    public Object readName(JsonReader reader) {
      return readShort(reader.readFieldNameInt());
    }
  }

  public static final class ByteKeyCodec implements MapKeyCodec {
    private static final ByteKeyCodec INSTANCE = new ByteKeyCodec();

    @Override
    public String toName(Object key) {
      return String.valueOf((byte) key);
    }

    @Override
    public Object fromName(String name) {
      return readByte(Integer.parseInt(name));
    }

    @Override
    public void writeName(JsonWriter writer, Object key) {
      writer.writeIntFieldName((byte) key);
    }

    @Override
    public Object readName(JsonReader reader) {
      return readByte(reader.readFieldNameInt());
    }
  }

  private static short readShort(int value) {
    if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
      throw new ForyJsonException("Short overflow");
    }
    return (short) value;
  }

  private static byte readByte(int value) {
    if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
      throw new ForyJsonException("Byte overflow");
    }
    return (byte) value;
  }
}

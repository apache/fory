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

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Map;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.PropertyNamingStrategy;
import org.apache.fory.json.meta.JsonCreatorFieldInfo;
import org.apache.fory.json.meta.JsonCreatorInfo;
import org.apache.fory.json.meta.JsonFieldAccessor;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldNameHash;
import org.apache.fory.json.meta.JsonFieldTable;
import org.apache.fory.json.meta.JsonTypeUse;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.platform.internal._JDKAccess;
import org.apache.fory.reflect.ObjectInstantiator;
import org.apache.fory.reflect.TypeRef;

/**
 * Reflection-backed semantic codec and metadata owner for one Java object type.
 *
 * <p>Construction discovers eligible fields and JavaBean properties, merges each
 * field/getter/setter group into one logical property, applies its name, inclusion, serialization
 * order, and directional ignore rules, resolves generic member types against the owner {@link
 * TypeRef}, and builds separate read and write field arrays. Class-valued fields and properties are
 * never JSON members. Records retain constructor metadata and field defaults; ordinary objects
 * retain an allocation strategy plus field or accessor sinks.
 *
 * <p>This codec is the interpreted implementation and the semantic source. Only an exact raw-class
 * instance of this class is eligible for generated capability replacement. Parameterized object
 * codecs retain binding-specific member types and remain the owner of all five slots. Generated
 * code may replace paths independently, but it is built from this codec's immutable field metadata
 * and preserves the same null, unknown-field, record, and member-discovery semantics.
 */
public class ObjectCodec<T> implements JsonValueCodec<T> {
  private static final int MUTABLE = 0;
  private static final int RECORD = 1;
  private static final int CREATOR = 2;

  protected final Class<?> type;
  protected final JsonFieldInfo[] writeFields;
  protected final JsonFieldInfo[] readFields;
  protected final JsonFieldTable readTable;
  protected final ObjectInstantiator<?> instantiator;
  private final int creationKind;
  private final JsonCreatorInfo creatorInfo;
  private final AnyInfo anyInfo;
  private final RecordConstruction recordConstruction;
  private final Object[] recordReadDefaults;

  private ObjectCodec(
      Class<?> type,
      JsonFieldInfo[] writeFields,
      JsonFieldInfo[] readFields,
      JsonCreatorInfo creatorInfo,
      AnyInfo anyInfo,
      String[] skippedNames,
      ObjectInstantiator<?> instantiator,
      RecordConstruction recordConstruction) {
    this.type = type;
    this.writeFields = writeFields;
    this.readFields = readFields;
    this.anyInfo = anyInfo;
    readTable =
        anyInfo == null
            ? new JsonFieldTable(readFields)
            : new JsonFieldTable(readFields, skippedNames);
    this.instantiator = instantiator;
    this.creatorInfo = creatorInfo;
    creationKind = recordConstruction != null ? RECORD : creatorInfo == null ? MUTABLE : CREATOR;
    if (creationKind == RECORD) {
      this.recordConstruction = recordConstruction;
      recordReadDefaults = recordConstruction.readDefaults;
    } else {
      this.recordConstruction = null;
      recordReadDefaults = null;
    }
  }

  @Internal
  public static <T> ObjectCodec<T> build(
      TypeRef<T> ownerType,
      boolean propertyDiscoveryEnabled,
      PropertyNamingStrategy propertyNamingStrategy,
      boolean writeNullFields) {
    return build(
        ownerType, null, propertyDiscoveryEnabled, propertyNamingStrategy, writeNullFields);
  }

  @Internal
  public static <T> ObjectCodec<T> build(
      TypeRef<T> ownerType,
      JsonTypeUse ownerTypeUse,
      boolean propertyDiscoveryEnabled,
      PropertyNamingStrategy propertyNamingStrategy,
      boolean writeNullFields) {
    return ObjectCodecBuilder.build(
        ownerType,
        ownerTypeUse,
        propertyDiscoveryEnabled,
        propertyNamingStrategy,
        writeNullFields,
        null);
  }

  /** Builds one object codec with generated Android metadata owned by {@code resolver}. */
  @Internal
  public static <T> ObjectCodec<T> build(
      TypeRef<T> ownerType,
      JsonTypeUse ownerTypeUse,
      boolean propertyDiscoveryEnabled,
      PropertyNamingStrategy propertyNamingStrategy,
      boolean writeNullFields,
      JsonTypeResolver resolver) {
    return ObjectCodecBuilder.build(
        ownerType,
        ownerTypeUse,
        propertyDiscoveryEnabled,
        propertyNamingStrategy,
        writeNullFields,
        resolver);
  }

  static <T> ObjectCodec<T> createCodec(
      TypeRef<T> ownerType,
      JsonFieldInfo[] writeFields,
      JsonFieldInfo[] readFields,
      JsonCreatorInfo creatorInfo,
      AnyInfo anyInfo,
      String[] skippedNames,
      ObjectInstantiator<?> instantiator,
      RecordConstruction recordConstruction) {
    Class<?> type = ownerType.getRawType();
    if (ownerType.getType() instanceof Class) {
      return new ObjectCodec<>(
          type,
          writeFields,
          readFields,
          creatorInfo,
          anyInfo,
          skippedNames,
          instantiator,
          recordConstruction);
    }
    return new ParameterizedObjectCodec<>(
        type,
        writeFields,
        readFields,
        creatorInfo,
        anyInfo,
        skippedNames,
        instantiator,
        recordConstruction);
  }

  public final Class<?> type() {
    return type;
  }

  public final JsonFieldInfo[] writeFields() {
    return writeFields;
  }

  public final JsonFieldInfo[] readFields() {
    return readFields;
  }

  public final JsonFieldTable readTable() {
    return readTable;
  }

  public final boolean isRecord() {
    return creationKind == RECORD;
  }

  public final JsonCreatorInfo creatorInfo() {
    return creatorInfo;
  }

  @Internal
  public final AnyInfo anyInfo() {
    return anyInfo;
  }

  /** Returns whether a generated Any reader stores this object's reader for recursive values. */
  @Internal
  public final boolean storesSelfReader() {
    AnyInfo any = anyInfo;
    return any != null
        && any.readEnabled()
        && storesSelfReader(type, readFields, creatorInfo != null, any);
  }

  /** Returns the recursive-reader storage decision shared by resolver and source generation. */
  @Internal
  public static boolean storesSelfReader(
      Class<?> type, JsonFieldInfo[] fields, boolean creator, AnyInfo any) {
    if (any.valueRawType() == type && any.valueTypeInfo().usesDefaultObjectCodec()) {
      return true;
    }
    if (creator) {
      return false;
    }
    for (JsonFieldInfo field : fields) {
      if (field.readNestedType() == type) {
        return true;
      }
    }
    return false;
  }

  @Internal
  public final Object requireCreatorResult(Object value) {
    if (value == null || value.getClass() != type) {
      throw new ForyJsonException("JSON creator must return an exact non-null " + type.getName());
    }
    return value;
  }

  @Internal
  public final ForyJsonException creatorFailure(Throwable cause) {
    if (cause instanceof Error) {
      throw (Error) cause;
    }
    return new ForyJsonException("JSON creator failed for " + type.getName(), cause);
  }

  public final void resolveTypes(JsonTypeResolver typeResolver) {
    for (JsonFieldInfo field : writeFields) {
      field.resolveTypes(typeResolver);
    }
    for (JsonFieldInfo field : readFields) {
      field.resolveTypes(typeResolver);
    }
    if (creatorInfo != null) {
      creatorInfo.resolveTypes(typeResolver);
    }
    if (anyInfo != null) {
      anyInfo.resolveTypes(typeResolver);
    }
  }

  @SuppressWarnings("unchecked")
  public final T newInstance() {
    return (T) instantiator.newInstance();
  }

  @Internal
  public final Object[] newRecordFieldValues() {
    return Arrays.copyOf(recordReadDefaults, recordReadDefaults.length);
  }

  @Internal
  @SuppressWarnings("unchecked")
  public final T newRecord(Object[] values) {
    RecordConstruction construction = recordConstruction;
    int[] componentToRead = construction.componentToRead;
    Object[] componentDefaults = construction.componentDefaults;
    Object[] arguments = construction.arguments;
    for (int i = 0; i < componentToRead.length; i++) {
      int readIndex = componentToRead[i];
      arguments[i] = readIndex < 0 ? componentDefaults[i] : values[readIndex];
    }
    Object object = instantiator.newInstanceWithArguments(arguments);
    Arrays.fill(arguments, null);
    return (T) object;
  }

  @Internal
  public final Map<Object, Object> newAnyMap() {
    return anyInfo.mapCodec.newMap();
  }

  @Internal
  public final Map<?, ?> finishAnyMap(Map<Object, Object> map) {
    return anyInfo.mapCodec.finishMap(map);
  }

  @Internal
  public final void putAnyMap(Map<Object, Object> map, String name, Object value) {
    try {
      map.put(name, value);
    } catch (UnsupportedOperationException e) {
      throw immutableAnyMap(e);
    }
  }

  @Internal
  public final ForyJsonException nullFinalAnyMap() {
    return new ForyJsonException(
        "Final @JsonAnyProperty field must hold a mutable Map on " + type.getName());
  }

  @Internal
  public final ForyJsonException nullPrimitiveAnyValue() {
    return new ForyJsonException(
        "Cannot read null into primitive @JsonAnySetter parameter " + anyInfo.setterValueRawType);
  }

  @Internal
  public final ForyJsonException anyAccessorFailure(String memberName, Throwable cause) {
    return new ForyJsonException(
        "Cannot access JSON Any member " + memberName + " on " + type.getName(), cause);
  }

  private ForyJsonException immutableAnyMap(UnsupportedOperationException cause) {
    return new ForyJsonException(
        "@JsonAnyProperty field must hold a mutable Map on " + type.getName(), cause);
  }

  @Internal
  public final int writeStringAny(
      StringJsonWriter writer, Map<?, ?> map, StringWriterCodec<Object> codec, int written) {
    if (map == null) {
      return written;
    }
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      Object key = entry.getKey();
      if (key == null || key.getClass() != String.class) {
        throw invalidAnyKey(key);
      }
      String name = (String) key;
      long hash = JsonFieldNameHash.hash(name);
      if (reservedAnyHash(hash)) {
        throw reservedAnyName(name);
      }
      writer.writeComma(written);
      writer.writeFieldName(name);
      codec.writeString(writer, entry.getValue());
      written = 1;
    }
    return written;
  }

  @Internal
  public final int writeUtf8Any(
      Utf8JsonWriter writer, Map<?, ?> map, Utf8WriterCodec<Object> codec, int written) {
    if (map == null) {
      return written;
    }
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      Object key = entry.getKey();
      if (key == null || key.getClass() != String.class) {
        throw invalidAnyKey(key);
      }
      String name = (String) key;
      long hash = JsonFieldNameHash.hash(name);
      if (reservedAnyHash(hash)) {
        throw reservedAnyName(name);
      }
      writer.writeComma(written);
      writer.writeFieldName(name);
      codec.writeUtf8(writer, entry.getValue());
      written = 1;
    }
    return written;
  }

  private boolean reservedAnyHash(long hash) {
    // These names belong to the child's declared schema and must never be reintroduced through
    // Any. Inline discriminators are different: their parent codec owns the fixed-schema check and
    // deliberately leaves runtime Any keys to the application.
    return readTable.containsHash(hash) || creatorInfo != null && creatorInfo.index(hash) >= 0;
  }

  private ForyJsonException invalidAnyKey(Object key) {
    String actualType = key == null ? "null" : key.getClass().getName();
    return new ForyJsonException(
        "JSON Any Map key must be an exact String on "
            + type.getName()
            + "; actual type is "
            + actualType);
  }

  private ForyJsonException reservedAnyName(String name) {
    return new ForyJsonException(
        "JSON Any member conflicts with a reserved property on " + type.getName() + ": " + name);
  }

  @Override
  public void writeString(StringJsonWriter writer, T value) {
    StringWriterCodec<T> codec = writer.typeResolver().stringWriter(this);
    if (codec != this) {
      codec.writeString(writer, value);
    } else if (value == null) {
      writer.writeNull();
    } else {
      writeStringObject(writer, value);
    }
  }

  @Override
  public void writeUtf8(Utf8JsonWriter writer, T value) {
    Utf8WriterCodec<T> codec = writer.typeResolver().utf8Writer(this);
    if (codec != this) {
      codec.writeUtf8(writer, value);
    } else if (value == null) {
      writer.writeNull();
    } else {
      writeUtf8Object(writer, value);
    }
  }

  // Raw and parameterized bindings share the same interpreted object algorithms inside this
  // top-level owner. Package access avoids Java 8 synthetic accessors from the nested binding;
  // these methods are not codec entries and must not be used for capability dispatch.
  final T readLatin1Object(Latin1JsonReader reader) {
    if (anyInfo != null && anyInfo.readEnabled()) {
      return readLatin1AnyObject(reader, readTable);
    }
    return readLatin1FixedObject(reader);
  }

  final T readLatin1Object(Latin1JsonReader reader, JsonFieldTable table) {
    return readLatin1AnyObject(reader, table);
  }

  private T readLatin1FixedObject(Latin1JsonReader reader) {
    reader.enterDepth();
    if (creationKind != MUTABLE) {
      if (creationKind == CREATOR) {
        Object[] arguments = readLatin1CreatorArguments(reader);
        reader.exitDepth();
        return create(arguments);
      }
      T object = readLatin1Record(reader);
      reader.exitDepth();
      return object;
    }
    T object = newInstance();
    reader.expect('{');
    if (reader.consume('}')) {
      reader.exitDepth();
      return object;
    }
    do {
      JsonFieldInfo field = reader.readField(readTable);
      reader.expect(':');
      if (field == null) {
        reader.skipValue();
      } else {
        field.readLatin1(reader, object);
      }
    } while (reader.consume(','));
    reader.expect('}');
    reader.exitDepth();
    return object;
  }

  final T readUtf16Object(Utf16JsonReader reader) {
    if (anyInfo != null && anyInfo.readEnabled()) {
      return readUtf16AnyObject(reader, readTable);
    }
    return readUtf16FixedObject(reader);
  }

  final T readUtf16Object(Utf16JsonReader reader, JsonFieldTable table) {
    return readUtf16AnyObject(reader, table);
  }

  private T readUtf16FixedObject(Utf16JsonReader reader) {
    reader.enterDepth();
    if (creationKind != MUTABLE) {
      if (creationKind == CREATOR) {
        Object[] arguments = readUtf16CreatorArguments(reader);
        reader.exitDepth();
        return create(arguments);
      }
      T object = readUtf16Record(reader);
      reader.exitDepth();
      return object;
    }
    T object = newInstance();
    reader.expect('{');
    if (reader.consume('}')) {
      reader.exitDepth();
      return object;
    }
    do {
      JsonFieldInfo field = reader.readField(readTable);
      reader.expect(':');
      if (field == null) {
        reader.skipValue();
      } else {
        field.readUtf16(reader, object);
      }
    } while (reader.consume(','));
    reader.expect('}');
    reader.exitDepth();
    return object;
  }

  final T readUtf8Object(Utf8JsonReader reader) {
    if (anyInfo != null && anyInfo.readEnabled()) {
      return readUtf8AnyObject(reader, readTable);
    }
    return readUtf8FixedObject(reader);
  }

  final T readUtf8Object(Utf8JsonReader reader, JsonFieldTable table) {
    return readUtf8AnyObject(reader, table);
  }

  private T readUtf8FixedObject(Utf8JsonReader reader) {
    reader.enterDepth();
    if (creationKind != MUTABLE) {
      if (creationKind == CREATOR) {
        Object[] arguments = readUtf8CreatorArguments(reader);
        reader.exitDepth();
        return create(arguments);
      }
      T object = readUtf8Record(reader);
      reader.exitDepth();
      return object;
    }
    T object = newInstance();
    reader.expect('{');
    if (reader.consume('}')) {
      reader.exitDepth();
      return object;
    }
    do {
      JsonFieldInfo field = reader.readField(readTable);
      reader.expect(':');
      if (field == null) {
        reader.skipValue();
      } else {
        field.readUtf8(reader, object);
      }
    } while (reader.consume(','));
    reader.expect('}');
    reader.exitDepth();
    return object;
  }

  @Override
  public T readLatin1(Latin1JsonReader reader) {
    Latin1ReaderCodec<T> codec = reader.typeResolver().latin1Reader(this);
    if (codec != this) {
      return codec.readLatin1(reader);
    }
    if (reader.tryReadNullToken()) {
      return null;
    }
    return readLatin1Object(reader);
  }

  @Override
  public T readUtf16(Utf16JsonReader reader) {
    Utf16ReaderCodec<T> codec = reader.typeResolver().utf16Reader(this);
    if (codec != this) {
      return codec.readUtf16(reader);
    }
    if (reader.tryReadNullToken()) {
      return null;
    }
    return readUtf16Object(reader);
  }

  @Override
  public T readUtf8(Utf8JsonReader reader) {
    Utf8ReaderCodec<T> codec = reader.typeResolver().utf8Reader(this);
    if (codec != this) {
      return codec.readUtf8(reader);
    }
    if (reader.tryReadNullToken()) {
      return null;
    }
    return readUtf8Object(reader);
  }

  private T readLatin1AnyObject(Latin1JsonReader reader, JsonFieldTable table) {
    reader.enterDepth();
    T object;
    if (creationKind == CREATOR) {
      object = create(readLatin1AnyCreatorArguments(reader, table));
    } else if (creationKind == RECORD) {
      object = readLatin1AnyRecord(reader, table);
    } else {
      object = readLatin1AnyMutable(reader, table);
    }
    reader.exitDepth();
    return object;
  }

  private T readUtf16AnyObject(Utf16JsonReader reader, JsonFieldTable table) {
    reader.enterDepth();
    T object;
    if (creationKind == CREATOR) {
      object = create(readUtf16AnyCreatorArguments(reader, table));
    } else if (creationKind == RECORD) {
      object = readUtf16AnyRecord(reader, table);
    } else {
      object = readUtf16AnyMutable(reader, table);
    }
    reader.exitDepth();
    return object;
  }

  private T readUtf8AnyObject(Utf8JsonReader reader, JsonFieldTable table) {
    reader.enterDepth();
    T object;
    if (creationKind == CREATOR) {
      object = create(readUtf8AnyCreatorArguments(reader, table));
    } else if (creationKind == RECORD) {
      object = readUtf8AnyRecord(reader, table);
    } else {
      object = readUtf8AnyMutable(reader, table);
    }
    reader.exitDepth();
    return object;
  }

  private T readLatin1AnyMutable(Latin1JsonReader reader, JsonFieldTable table) {
    T object = newInstance();
    Map<Object, Object> anyMap = null;
    boolean newMap = false;
    Latin1ReaderCodec<Object> anyReader = anyInfo.valueTypeInfo.latin1Reader();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int start = reader.position();
        long hash = reader.readFieldNameHash();
        int match = table.match(hash);
        reader.expect(':');
        if (match >= 0) {
          readFields[match].readLatin1(reader, object);
        } else if (match == JsonFieldTable.SKIP) {
          reader.skipValue();
        } else {
          String name = reader.materializeFieldName(start);
          Object value = anyReader.readLatin1(reader);
          if (anyInfo.fieldRead()) {
            if (anyMap == null) {
              anyMap = anyInfo.readMap(object);
              if (anyMap == null) {
                if (anyInfo.finalReadField()) {
                  throw nullFinalAnyMap();
                }
                anyMap = newAnyMap();
                newMap = true;
              }
            }
            putAnyMap(anyMap, name, value);
          } else {
            anyInfo.put(object, name, value);
          }
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    if (newMap) {
      anyInfo.setReadMap(object, finishAnyMap(anyMap));
    }
    return object;
  }

  private T readUtf16AnyMutable(Utf16JsonReader reader, JsonFieldTable table) {
    T object = newInstance();
    Map<Object, Object> anyMap = null;
    boolean newMap = false;
    Utf16ReaderCodec<Object> anyReader = anyInfo.valueTypeInfo.utf16Reader();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int start = reader.position();
        long hash = reader.readFieldNameHash();
        int match = table.match(hash);
        reader.expect(':');
        if (match >= 0) {
          readFields[match].readUtf16(reader, object);
        } else if (match == JsonFieldTable.SKIP) {
          reader.skipValue();
        } else {
          String name = reader.materializeFieldName(start);
          Object value = anyReader.readUtf16(reader);
          if (anyInfo.fieldRead()) {
            if (anyMap == null) {
              anyMap = anyInfo.readMap(object);
              if (anyMap == null) {
                if (anyInfo.finalReadField()) {
                  throw nullFinalAnyMap();
                }
                anyMap = newAnyMap();
                newMap = true;
              }
            }
            putAnyMap(anyMap, name, value);
          } else {
            anyInfo.put(object, name, value);
          }
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    if (newMap) {
      anyInfo.setReadMap(object, finishAnyMap(anyMap));
    }
    return object;
  }

  private T readUtf8AnyMutable(Utf8JsonReader reader, JsonFieldTable table) {
    T object = newInstance();
    Map<Object, Object> anyMap = null;
    boolean newMap = false;
    Utf8ReaderCodec<Object> anyReader = anyInfo.valueTypeInfo.utf8Reader();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int start = reader.position();
        long hash = reader.readFieldNameHash();
        int match = table.match(hash);
        reader.expect(':');
        if (match >= 0) {
          readFields[match].readUtf8(reader, object);
        } else if (match == JsonFieldTable.SKIP) {
          reader.skipValue();
        } else {
          String name = reader.materializeFieldName(start);
          Object value = anyReader.readUtf8(reader);
          if (anyInfo.fieldRead()) {
            if (anyMap == null) {
              anyMap = anyInfo.readMap(object);
              if (anyMap == null) {
                if (anyInfo.finalReadField()) {
                  throw nullFinalAnyMap();
                }
                anyMap = newAnyMap();
                newMap = true;
              }
            }
            putAnyMap(anyMap, name, value);
          } else {
            anyInfo.put(object, name, value);
          }
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    if (newMap) {
      anyInfo.setReadMap(object, finishAnyMap(anyMap));
    }
    return object;
  }

  private T readLatin1AnyRecord(Latin1JsonReader reader, JsonFieldTable table) {
    Object[] values = newRecordFieldValues();
    Map<Object, Object> anyMap = null;
    Latin1ReaderCodec<Object> anyReader = anyInfo.valueTypeInfo.latin1Reader();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int start = reader.position();
        long hash = reader.readFieldNameHash();
        int match = table.match(hash);
        reader.expect(':');
        if (match >= 0) {
          JsonFieldInfo field = readFields[match];
          values[field.readIndex()] = field.readLatin1Value(reader);
        } else if (match == JsonFieldTable.SKIP) {
          reader.skipValue();
        } else {
          String name = reader.materializeFieldName(start);
          Object value = anyReader.readLatin1(reader);
          if (anyMap == null) {
            anyMap = newAnyMap();
          }
          putAnyMap(anyMap, name, value);
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    if (anyMap != null) {
      values[anyInfo.constructionIndex] = finishAnyMap(anyMap);
    }
    return newRecord(values);
  }

  private T readUtf16AnyRecord(Utf16JsonReader reader, JsonFieldTable table) {
    Object[] values = newRecordFieldValues();
    Map<Object, Object> anyMap = null;
    Utf16ReaderCodec<Object> anyReader = anyInfo.valueTypeInfo.utf16Reader();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int start = reader.position();
        long hash = reader.readFieldNameHash();
        int match = table.match(hash);
        reader.expect(':');
        if (match >= 0) {
          JsonFieldInfo field = readFields[match];
          values[field.readIndex()] = field.readUtf16Value(reader);
        } else if (match == JsonFieldTable.SKIP) {
          reader.skipValue();
        } else {
          String name = reader.materializeFieldName(start);
          Object value = anyReader.readUtf16(reader);
          if (anyMap == null) {
            anyMap = newAnyMap();
          }
          putAnyMap(anyMap, name, value);
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    if (anyMap != null) {
      values[anyInfo.constructionIndex] = finishAnyMap(anyMap);
    }
    return newRecord(values);
  }

  private T readUtf8AnyRecord(Utf8JsonReader reader, JsonFieldTable table) {
    Object[] values = newRecordFieldValues();
    Map<Object, Object> anyMap = null;
    Utf8ReaderCodec<Object> anyReader = anyInfo.valueTypeInfo.utf8Reader();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int start = reader.position();
        long hash = reader.readFieldNameHash();
        int match = table.match(hash);
        reader.expect(':');
        if (match >= 0) {
          JsonFieldInfo field = readFields[match];
          values[field.readIndex()] = field.readUtf8Value(reader);
        } else if (match == JsonFieldTable.SKIP) {
          reader.skipValue();
        } else {
          String name = reader.materializeFieldName(start);
          Object value = anyReader.readUtf8(reader);
          if (anyMap == null) {
            anyMap = newAnyMap();
          }
          putAnyMap(anyMap, name, value);
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    if (anyMap != null) {
      values[anyInfo.constructionIndex] = finishAnyMap(anyMap);
    }
    return newRecord(values);
  }

  private Object[] readLatin1AnyCreatorArguments(Latin1JsonReader reader, JsonFieldTable table) {
    Object[] arguments = creatorInfo.newArguments();
    JsonCreatorFieldInfo[] fields = creatorInfo.fields();
    Map<Object, Object> anyMap = null;
    Latin1ReaderCodec<Object> anyReader = anyInfo.valueTypeInfo.latin1Reader();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int start = reader.position();
        long hash = reader.readFieldNameHash();
        int index = creatorInfo.index(hash);
        reader.expect(':');
        if (index >= 0) {
          JsonCreatorFieldInfo field = fields[index];
          arguments[field.argumentIndex()] = field.readLatin1(reader);
        } else {
          int match = table.match(hash);
          if (match != JsonFieldTable.UNKNOWN) {
            reader.skipValue();
          } else {
            String name = reader.materializeFieldName(start);
            Object value = anyReader.readLatin1(reader);
            if (anyMap == null) {
              anyMap = newAnyMap();
            }
            putAnyMap(anyMap, name, value);
          }
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    if (anyMap != null) {
      arguments[anyInfo.constructionIndex] = finishAnyMap(anyMap);
    }
    return arguments;
  }

  private Object[] readUtf16AnyCreatorArguments(Utf16JsonReader reader, JsonFieldTable table) {
    Object[] arguments = creatorInfo.newArguments();
    JsonCreatorFieldInfo[] fields = creatorInfo.fields();
    Map<Object, Object> anyMap = null;
    Utf16ReaderCodec<Object> anyReader = anyInfo.valueTypeInfo.utf16Reader();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int start = reader.position();
        long hash = reader.readFieldNameHash();
        int index = creatorInfo.index(hash);
        reader.expect(':');
        if (index >= 0) {
          JsonCreatorFieldInfo field = fields[index];
          arguments[field.argumentIndex()] = field.readUtf16(reader);
        } else {
          int match = table.match(hash);
          if (match != JsonFieldTable.UNKNOWN) {
            reader.skipValue();
          } else {
            String name = reader.materializeFieldName(start);
            Object value = anyReader.readUtf16(reader);
            if (anyMap == null) {
              anyMap = newAnyMap();
            }
            putAnyMap(anyMap, name, value);
          }
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    if (anyMap != null) {
      arguments[anyInfo.constructionIndex] = finishAnyMap(anyMap);
    }
    return arguments;
  }

  private Object[] readUtf8AnyCreatorArguments(Utf8JsonReader reader, JsonFieldTable table) {
    Object[] arguments = creatorInfo.newArguments();
    JsonCreatorFieldInfo[] fields = creatorInfo.fields();
    Map<Object, Object> anyMap = null;
    Utf8ReaderCodec<Object> anyReader = anyInfo.valueTypeInfo.utf8Reader();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int start = reader.position();
        long hash = reader.readFieldNameHash();
        int index = creatorInfo.index(hash);
        reader.expect(':');
        if (index >= 0) {
          JsonCreatorFieldInfo field = fields[index];
          arguments[field.argumentIndex()] = field.readUtf8(reader);
        } else {
          int match = table.match(hash);
          if (match != JsonFieldTable.UNKNOWN) {
            reader.skipValue();
          } else {
            String name = reader.materializeFieldName(start);
            Object value = anyReader.readUtf8(reader);
            if (anyMap == null) {
              anyMap = newAnyMap();
            }
            putAnyMap(anyMap, name, value);
          }
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    if (anyMap != null) {
      arguments[anyInfo.constructionIndex] = finishAnyMap(anyMap);
    }
    return arguments;
  }

  private T readLatin1Record(Latin1JsonReader reader) {
    Object[] values = newRecordFieldValues();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        JsonFieldInfo field = reader.readField(readTable);
        reader.expect(':');
        if (field == null) {
          reader.skipValue();
        } else {
          values[field.readIndex()] = field.readLatin1Value(reader);
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    return newRecord(values);
  }

  private T readUtf16Record(Utf16JsonReader reader) {
    Object[] values = newRecordFieldValues();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        JsonFieldInfo field = reader.readField(readTable);
        reader.expect(':');
        if (field == null) {
          reader.skipValue();
        } else {
          values[field.readIndex()] = field.readUtf16Value(reader);
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    return newRecord(values);
  }

  private T readUtf8Record(Utf8JsonReader reader) {
    Object[] values = newRecordFieldValues();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        JsonFieldInfo field = reader.readField(readTable);
        reader.expect(':');
        if (field == null) {
          reader.skipValue();
        } else {
          values[field.readIndex()] = field.readUtf8Value(reader);
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    return newRecord(values);
  }

  private Object[] readLatin1CreatorArguments(Latin1JsonReader reader) {
    Object[] arguments = creatorInfo.newArguments();
    JsonCreatorFieldInfo[] fields = creatorInfo.fields();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int index = creatorInfo.index(reader.readFieldNameHash());
        reader.expect(':');
        if (index < 0) {
          reader.skipValue();
        } else {
          JsonCreatorFieldInfo field = fields[index];
          arguments[field.argumentIndex()] = field.readLatin1(reader);
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    return arguments;
  }

  private Object[] readUtf16CreatorArguments(Utf16JsonReader reader) {
    Object[] arguments = creatorInfo.newArguments();
    JsonCreatorFieldInfo[] fields = creatorInfo.fields();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int index = creatorInfo.index(reader.readFieldNameHash());
        reader.expect(':');
        if (index < 0) {
          reader.skipValue();
        } else {
          JsonCreatorFieldInfo field = fields[index];
          arguments[field.argumentIndex()] = field.readUtf16(reader);
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    return arguments;
  }

  private Object[] readUtf8CreatorArguments(Utf8JsonReader reader) {
    Object[] arguments = creatorInfo.newArguments();
    JsonCreatorFieldInfo[] fields = creatorInfo.fields();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int index = creatorInfo.index(reader.readFieldNameHash());
        reader.expect(':');
        if (index < 0) {
          reader.skipValue();
        } else {
          JsonCreatorFieldInfo field = fields[index];
          arguments[field.argumentIndex()] = field.readUtf8(reader);
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    return arguments;
  }

  @SuppressWarnings("unchecked")
  private T create(Object[] arguments) {
    return (T) creatorInfo.create(arguments);
  }

  final void writeStringObject(StringJsonWriter writer, T value) {
    writer.writeObjectStart();
    writeMembers(writer, value, 0);
    writer.writeObjectEnd();
  }

  final void writeUtf8Object(Utf8JsonWriter writer, T value) {
    writer.writeObjectStart();
    writeMembers(writer, value, 0);
    writer.writeObjectEnd();
  }

  // ClosedSubtypeCodec owns the open object and discriminator for PROPERTY inclusion. Keep this
  // interpreted traversal package-local instead of publishing partial-object writing as a child
  // codec capability; a complete generated writer cannot safely enter an object already in
  // progress. Only this object layer is interpreted: JsonFieldInfo still writes every nested
  // complete value through its normal codec entry, where ordinary child code generation remains
  // active.
  final void writeMembers(StringJsonWriter writer, T value, int written) {
    if (anyInfo != null && anyInfo.writeEnabled()) {
      writeAnyMembers(writer, value, written);
      return;
    }
    writeFixedMembers(writer, value, written);
  }

  final void writeMembers(Utf8JsonWriter writer, T value, int written) {
    if (anyInfo != null && anyInfo.writeEnabled()) {
      writeAnyMembers(writer, value, written);
      return;
    }
    writeFixedMembers(writer, value, written);
  }

  private void writeFixedMembers(StringJsonWriter writer, T value, int written) {
    JsonFieldInfo[] fields = writeFields;
    int length = fields.length;
    int i = 0;
    while (i + 4 <= length) {
      if (fields[i++].writeString(writer, value, written)) {
        written++;
      }
      if (fields[i++].writeString(writer, value, written)) {
        written++;
      }
      if (fields[i++].writeString(writer, value, written)) {
        written++;
      }
      if (fields[i++].writeString(writer, value, written)) {
        written++;
      }
    }
    while (i < length) {
      if (fields[i++].writeString(writer, value, written)) {
        written++;
      }
    }
  }

  private void writeFixedMembers(Utf8JsonWriter writer, T value, int written) {
    JsonFieldInfo[] fields = writeFields;
    int length = fields.length;
    int i = 0;
    while (i + 4 <= length) {
      if (fields[i++].writeUtf8(writer, value, written)) {
        written++;
      }
      if (fields[i++].writeUtf8(writer, value, written)) {
        written++;
      }
      if (fields[i++].writeUtf8(writer, value, written)) {
        written++;
      }
      if (fields[i++].writeUtf8(writer, value, written)) {
        written++;
      }
    }
    while (i < length) {
      if (fields[i++].writeUtf8(writer, value, written)) {
        written++;
      }
    }
  }

  private void writeAnyMembers(StringJsonWriter writer, T value, int written) {
    int anyIndex = anyInfo.writeIndex;
    JsonFieldInfo[] fields = writeFields;
    for (int i = 0; i < anyIndex; i++) {
      if (fields[i].writeString(writer, value, written)) {
        written++;
      }
    }
    written =
        writeStringAny(
            writer, anyInfo.writeMap(value), anyInfo.valueTypeInfo.stringWriter(), written);
    for (int i = anyIndex; i < fields.length; i++) {
      if (fields[i].writeString(writer, value, written)) {
        written++;
      }
    }
  }

  private void writeAnyMembers(Utf8JsonWriter writer, T value, int written) {
    int anyIndex = anyInfo.writeIndex;
    JsonFieldInfo[] fields = writeFields;
    for (int i = 0; i < anyIndex; i++) {
      if (fields[i].writeUtf8(writer, value, written)) {
        written++;
      }
    }
    written =
        writeUtf8Any(writer, anyInfo.writeMap(value), anyInfo.valueTypeInfo.utf8Writer(), written);
    for (int i = anyIndex; i < fields.length; i++) {
      if (fields[i].writeUtf8(writer, value, written)) {
        written++;
      }
    }
  }

  /** Record construction metadata resolved once by the owning codec builder. */
  static final class RecordConstruction {
    private final int[] componentToRead;
    private final Object[] componentDefaults;
    private final Object[] arguments;
    private final Object[] readDefaults;

    RecordConstruction(int[] componentToRead, Object[] componentDefaults, int readCount) {
      if (componentToRead.length != componentDefaults.length) {
        throw new IllegalArgumentException("Record component metadata length mismatch");
      }
      this.componentToRead = componentToRead;
      this.componentDefaults = componentDefaults;
      arguments = new Object[componentToRead.length];
      readDefaults = new Object[readCount];
      for (int i = 0; i < componentToRead.length; i++) {
        int readIndex = componentToRead[i];
        if (readIndex >= 0) {
          readDefaults[readIndex] = componentDefaults[i];
        }
      }
    }
  }

  @Internal
  public static class AnyInfo {
    private final Field writeField;
    private final Method writeGetter;
    private final Field readField;
    private final Method readSetter;
    final Class<?> setterValueRawType;
    private final JsonFieldAccessor writeAccessor;
    private final JsonFieldAccessor readAccessor;
    private final MethodHandle setterHandle;
    private final Type mapType;
    private final Class<?> mapRawType;
    private final Type valueType;
    private final Class<?> valueRawType;
    private final JsonTypeUse valueTypeUse;
    private final int writeIndex;
    private final int constructionIndex;
    private JsonTypeInfo valueTypeInfo;
    private MapCodec<?> mapCodec;

    AnyInfo(
        Field writeField,
        Method writeGetter,
        Field readField,
        Method readSetter,
        Type mapType,
        Class<?> mapRawType,
        Type valueType,
        Class<?> valueRawType,
        JsonTypeUse valueTypeUse,
        int writeIndex,
        int constructionIndex) {
      this.writeField = writeField;
      this.writeGetter = writeGetter;
      this.readField = readField;
      this.readSetter = readSetter;
      setterValueRawType = readSetter == null ? null : readSetter.getParameterTypes()[1];
      writeAccessor =
          writeGetter != null
              ? JsonFieldAccessor.forGetter(writeGetter)
              : writeField == null ? null : JsonFieldAccessor.forField(writeField);
      readAccessor = readField == null ? null : JsonFieldAccessor.forField(readField);
      setterHandle = readSetter == null ? null : methodHandle(readSetter);
      this.mapType = mapType;
      this.mapRawType = mapRawType;
      this.valueType = valueType;
      this.valueRawType = valueRawType;
      this.valueTypeUse = valueTypeUse;
      this.writeIndex = writeIndex;
      this.constructionIndex = constructionIndex;
    }

    /** Creates Any metadata from validated generated Android member facts. */
    @Internal
    AnyInfo(
        JsonFieldAccessor writeAccessor,
        JsonFieldAccessor readAccessor,
        Class<?> setterValueRawType,
        Type mapType,
        Class<?> mapRawType,
        Type valueType,
        Class<?> valueRawType,
        JsonTypeUse valueTypeUse,
        int writeIndex,
        int constructionIndex) {
      writeField = null;
      writeGetter = null;
      readField = null;
      readSetter = null;
      this.setterValueRawType = setterValueRawType;
      this.writeAccessor = writeAccessor;
      this.readAccessor = readAccessor;
      setterHandle = null;
      this.mapType = mapType;
      this.mapRawType = mapRawType;
      this.valueType = valueType;
      this.valueRawType = valueRawType;
      this.valueTypeUse = valueTypeUse;
      this.writeIndex = writeIndex;
      this.constructionIndex = constructionIndex;
    }

    private void resolveTypes(JsonTypeResolver resolver) {
      if (fieldRead() && valueTypeUse != null) {
        resolver.checkMapKeySecure(String.class);
      }
      valueTypeInfo =
          valueTypeUse == null
              ? resolver.getTypeInfo(valueType, valueRawType)
              : resolver.getTypeInfo(valueTypeUse);
      if (fieldRead()) {
        mapCodec =
            valueTypeUse == null
                ? MapCodec.create(mapRawType, TypeRef.of(mapType), resolver)
                : MapCodec.create(mapRawType, String.class, valueTypeInfo);
      }
    }

    public Field writeField() {
      return writeField;
    }

    public Method writeGetter() {
      return writeGetter;
    }

    public Field readField() {
      return readField;
    }

    public Method readSetter() {
      return readSetter;
    }

    public Class<?> valueRawType() {
      return valueRawType;
    }

    public JsonTypeInfo valueTypeInfo() {
      return valueTypeInfo;
    }

    public int writeIndex() {
      return writeIndex;
    }

    public int constructionIndex() {
      return constructionIndex;
    }

    boolean writeEnabled() {
      return writeAccessor != null;
    }

    boolean readEnabled() {
      return readAccessor != null || readSetter != null;
    }

    boolean fieldRead() {
      return readField != null;
    }

    boolean finalReadField() {
      return readField != null && Modifier.isFinal(readField.getModifiers());
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> writeMap(Object target) {
      try {
        return (Map<?, ?>) writeAccessor.getObject(target);
      } catch (ForyJsonException e) {
        if (e.getCause() instanceof Error) {
          throw (Error) e.getCause();
        }
        throw e;
      }
    }

    @SuppressWarnings("unchecked")
    private Map<Object, Object> readMap(Object target) {
      return (Map<Object, Object>) readAccessor.getObject(target);
    }

    private void setReadMap(Object target, Map<?, ?> map) {
      readAccessor.putObject(target, map);
    }

    void put(Object target, String name, Object value) {
      if (value == null && setterValueRawType.isPrimitive()) {
        throw new ForyJsonException(
            "Cannot read null into primitive @JsonAnySetter parameter " + setterValueRawType);
      }
      try {
        setterHandle.invoke(target, name, value);
      } catch (Throwable cause) {
        if (cause instanceof Error) {
          throw (Error) cause;
        }
        throw new ForyJsonException("Cannot invoke @JsonAnySetter " + readSetter, cause);
      }
    }

    private static MethodHandle methodHandle(Method method) {
      try {
        return _JDKAccess._trustedLookup(method.getDeclaringClass()).unreflect(method);
      } catch (IllegalAccessException e) {
        throw new ForyJsonException("Cannot access @JsonAnySetter " + method, e);
      }
    }
  }

  /** Owns one parameterized POJO binding whose child types differ from the raw-class binding. */
  private static final class ParameterizedObjectCodec<T> extends ObjectCodec<T> {
    private ParameterizedObjectCodec(
        Class<?> type,
        JsonFieldInfo[] writeFields,
        JsonFieldInfo[] readFields,
        JsonCreatorInfo creatorInfo,
        AnyInfo anyInfo,
        String[] skippedNames,
        ObjectInstantiator<?> instantiator,
        RecordConstruction recordConstruction) {
      super(
          type,
          writeFields,
          readFields,
          creatorInfo,
          anyInfo,
          skippedNames,
          instantiator,
          recordConstruction);
    }

    @Override
    public void writeString(StringJsonWriter writer, T value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writeStringObject(writer, value);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, T value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writeUtf8Object(writer, value);
      }
    }

    @Override
    public T readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : readLatin1Object(reader);
    }

    @Override
    public T readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : readUtf16Object(reader);
    }

    @Override
    public T readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : readUtf8Object(reader);
    }
  }
}

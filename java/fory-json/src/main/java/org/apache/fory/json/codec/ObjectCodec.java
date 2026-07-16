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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Map;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.PropertyNamingStrategy;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.codec.JsonUnwrappedInfo.Declaration;
import org.apache.fory.json.codec.JsonUnwrappedInfo.Group;
import org.apache.fory.json.codec.JsonUnwrappedInfo.ReadRoute;
import org.apache.fory.json.codec.JsonUnwrappedInfo.WriteEntry;
import org.apache.fory.json.meta.JsonAnySetterAccessor;
import org.apache.fory.json.meta.JsonCreatorFieldInfo;
import org.apache.fory.json.meta.JsonCreatorInfo;
import org.apache.fory.json.meta.JsonFieldAccessor;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldNameHash;
import org.apache.fory.json.meta.JsonFieldTable;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonSharedRegistry;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.platform.AndroidSupport;
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
 * never JSON members. Records and explicit creators retain ordered creator metadata; mutable
 * objects retain an allocation strategy plus field or accessor sinks.
 *
 * <p>This codec is the interpreted implementation and the semantic fallback. Only an exact
 * raw-class instance of this class is eligible for generated capability replacement. Parameterized
 * object codecs retain binding-specific member types and remain the owner of all five slots.
 * Generated code may replace paths independently, but it is built from this codec's immutable field
 * metadata and preserves the same null, unknown-field, creator, and member-discovery semantics.
 */
public class ObjectCodec<T> implements JsonValueCodec<T> {
  protected final Class<?> type;
  protected final JsonFieldInfo[] writeFields;
  protected final JsonFieldInfo[] readFields;
  protected final JsonFieldTable readTable;
  protected final ObjectInstantiator<?> instantiator;
  private final JsonCreatorInfo creatorInfo;
  private final AnyInfo anyInfo;
  private final JsonUnwrappedInfo unwrappedInfo;
  private boolean directTypesResolved;

  private ObjectCodec(
      Class<?> type,
      JsonFieldInfo[] writeFields,
      JsonFieldInfo[] readFields,
      JsonCreatorInfo creatorInfo,
      AnyInfo anyInfo,
      String[] skippedNames,
      JsonUnwrappedInfo unwrappedInfo,
      ObjectInstantiator<?> instantiator) {
    this.type = type;
    this.writeFields = writeFields;
    this.readFields = readFields;
    this.anyInfo = anyInfo;
    this.unwrappedInfo = unwrappedInfo;
    readTable =
        anyInfo == null
            ? new JsonFieldTable(readFields)
            : new JsonFieldTable(readFields, skippedNames);
    this.instantiator = instantiator;
    this.creatorInfo = creatorInfo;
  }

  @Internal
  public static <T> ObjectCodec<T> build(
      TypeRef<T> ownerType,
      boolean propertyDiscoveryEnabled,
      PropertyNamingStrategy propertyNamingStrategy,
      boolean writeNullFields,
      JsonSharedRegistry sharedRegistry,
      GeneratedJsonCodec<?> generatedCodec) {
    try {
      return ObjectCodecBuilder.build(
          ownerType,
          propertyDiscoveryEnabled,
          propertyNamingStrategy,
          writeNullFields,
          sharedRegistry,
          generatedCodec);
    } catch (ForyJsonException e) {
      throw sharedRegistry.mixinSchemaFailure(ownerType.getRawType(), e);
    }
  }

  static <T> ObjectCodec<T> createCodec(
      TypeRef<T> ownerType,
      JsonFieldInfo[] writeFields,
      JsonFieldInfo[] readFields,
      JsonCreatorInfo creatorInfo,
      AnyInfo anyInfo,
      String[] skippedNames,
      JsonUnwrappedInfo unwrappedInfo,
      ObjectInstantiator<?> instantiator) {
    Class<?> type = ownerType.getRawType();
    if (ownerType.getType() instanceof Class) {
      return new ObjectCodec<>(
          type,
          writeFields,
          readFields,
          creatorInfo,
          anyInfo,
          skippedNames,
          unwrappedInfo,
          instantiator);
    }
    return new ParameterizedObjectCodec<>(
        type,
        writeFields,
        readFields,
        creatorInfo,
        anyInfo,
        skippedNames,
        unwrappedInfo,
        instantiator);
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

  public final JsonCreatorInfo creatorInfo() {
    return creatorInfo;
  }

  @Internal
  public final AnyInfo anyInfo() {
    return anyInfo;
  }

  @Internal
  public final JsonUnwrappedInfo unwrappedInfo() {
    return unwrappedInfo;
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
    resolveDirectTypes(typeResolver);
    if (unwrappedInfo != null) {
      unwrappedInfo.resolve(this, typeResolver);
    }
  }

  @Internal
  public final void resolveDirectTypes(JsonTypeResolver typeResolver) {
    if (directTypesResolved) {
      return;
    }
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
    directTypesResolved = true;
  }

  @SuppressWarnings("unchecked")
  public final T newInstance() {
    return (T) instantiator.newInstance();
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
    return readTable.containsHash(hash)
        || creatorInfo != null && creatorInfo.index(hash) >= 0
        || unwrappedInfo != null && unwrappedInfo.containsHash(hash);
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
    if (unwrappedInfo != null) {
      return readLatin1UnwrappedObject(reader, readTable);
    }
    if (anyInfo != null && anyInfo.readEnabled()) {
      return readLatin1AnyObject(reader, readTable);
    }
    return readLatin1FixedObject(reader);
  }

  final T readLatin1Object(Latin1JsonReader reader, JsonFieldTable table) {
    if (unwrappedInfo != null) {
      return readLatin1UnwrappedObject(reader, table);
    }
    return readLatin1AnyObject(reader, table);
  }

  private T readLatin1FixedObject(Latin1JsonReader reader) {
    reader.enterDepth();
    if (creatorInfo != null) {
      Object[] arguments = readLatin1CreatorArguments(reader);
      reader.exitDepth();
      return create(arguments);
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
    if (unwrappedInfo != null) {
      return readUtf16UnwrappedObject(reader, readTable);
    }
    if (anyInfo != null && anyInfo.readEnabled()) {
      return readUtf16AnyObject(reader, readTable);
    }
    return readUtf16FixedObject(reader);
  }

  final T readUtf16Object(Utf16JsonReader reader, JsonFieldTable table) {
    if (unwrappedInfo != null) {
      return readUtf16UnwrappedObject(reader, table);
    }
    return readUtf16AnyObject(reader, table);
  }

  private T readUtf16FixedObject(Utf16JsonReader reader) {
    reader.enterDepth();
    if (creatorInfo != null) {
      Object[] arguments = readUtf16CreatorArguments(reader);
      reader.exitDepth();
      return create(arguments);
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
    if (unwrappedInfo != null) {
      return readUtf8UnwrappedObject(reader, readTable);
    }
    if (anyInfo != null && anyInfo.readEnabled()) {
      return readUtf8AnyObject(reader, readTable);
    }
    return readUtf8FixedObject(reader);
  }

  final T readUtf8Object(Utf8JsonReader reader, JsonFieldTable table) {
    if (unwrappedInfo != null) {
      return readUtf8UnwrappedObject(reader, table);
    }
    return readUtf8AnyObject(reader, table);
  }

  private T readUtf8FixedObject(Utf8JsonReader reader) {
    reader.enterDepth();
    if (creatorInfo != null) {
      Object[] arguments = readUtf8CreatorArguments(reader);
      reader.exitDepth();
      return create(arguments);
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

  private T readLatin1UnwrappedObject(Latin1JsonReader reader, JsonFieldTable directTable) {
    reader.enterDepth();
    Object rootWorkspace = newUnwrappedWorkspace();
    Group[] resolvedGroups = unwrappedInfo.groups();
    Object[] groupWorkspaces = new Object[resolvedGroups.length];
    boolean[] present = new boolean[resolvedGroups.length];
    Map<Object, Object> anyMap = null;
    boolean newAnyMap = false;
    Latin1ReaderCodec<Object> anyReader =
        anyInfo != null && anyInfo.readEnabled() ? anyInfo.valueTypeInfo.latin1Reader() : null;
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int start = reader.position();
        long hash = reader.readFieldNameHash();
        int direct = creatorInfo != null ? creatorInfo.index(hash) : directTable.match(hash);
        reader.expect(':');
        if (direct >= 0) {
          if (creatorInfo == null) {
            readFields[direct].readLatin1(reader, rootWorkspace);
          } else {
            JsonCreatorFieldInfo field = creatorInfo.fields()[direct];
            ((Object[]) rootWorkspace)[field.argumentIndex()] = field.readLatin1(reader);
          }
          continue;
        }
        int directMiss = creatorInfo != null ? directTable.match(hash) : direct;
        if (directMiss == JsonFieldTable.SKIP) {
          reader.skipValue();
          continue;
        }
        int routeIndex = unwrappedInfo.match(hash);
        if (routeIndex >= 0) {
          readLatin1Route(reader, unwrappedInfo.readRoutes()[routeIndex], groupWorkspaces, present);
        } else if (routeIndex == JsonUnwrappedInfo.SKIP || anyReader == null) {
          reader.skipValue();
        } else {
          String name = reader.materializeFieldName(start);
          Object value = anyReader.readLatin1(reader);
          if (creatorInfo == null && !anyInfo.fieldRead()) {
            anyInfo.put(rootWorkspace, name, value);
          } else {
            if (anyMap == null) {
              if (creatorInfo == null) {
                anyMap = anyInfo.readMap(rootWorkspace);
              }
              if (anyMap == null) {
                if (creatorInfo == null && anyInfo.finalReadField()) {
                  throw nullFinalAnyMap();
                }
                anyMap = newAnyMap();
                newAnyMap = true;
              }
            }
            putAnyMap(anyMap, name, value);
          }
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    finishUnwrappedAny(rootWorkspace, anyMap, newAnyMap);
    finishUnwrappedGroups(rootWorkspace, groupWorkspaces, present);
    T object = castFinished(finishUnwrappedWorkspace(rootWorkspace));
    reader.exitDepth();
    return object;
  }

  private T readUtf16UnwrappedObject(Utf16JsonReader reader, JsonFieldTable directTable) {
    reader.enterDepth();
    Object rootWorkspace = newUnwrappedWorkspace();
    Group[] resolvedGroups = unwrappedInfo.groups();
    Object[] groupWorkspaces = new Object[resolvedGroups.length];
    boolean[] present = new boolean[resolvedGroups.length];
    Map<Object, Object> anyMap = null;
    boolean newAnyMap = false;
    Utf16ReaderCodec<Object> anyReader =
        anyInfo != null && anyInfo.readEnabled() ? anyInfo.valueTypeInfo.utf16Reader() : null;
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int start = reader.position();
        long hash = reader.readFieldNameHash();
        int direct = creatorInfo != null ? creatorInfo.index(hash) : directTable.match(hash);
        reader.expect(':');
        if (direct >= 0) {
          if (creatorInfo == null) {
            readFields[direct].readUtf16(reader, rootWorkspace);
          } else {
            JsonCreatorFieldInfo field = creatorInfo.fields()[direct];
            ((Object[]) rootWorkspace)[field.argumentIndex()] = field.readUtf16(reader);
          }
          continue;
        }
        int directMiss = creatorInfo != null ? directTable.match(hash) : direct;
        if (directMiss == JsonFieldTable.SKIP) {
          reader.skipValue();
          continue;
        }
        int routeIndex = unwrappedInfo.match(hash);
        if (routeIndex >= 0) {
          readUtf16Route(reader, unwrappedInfo.readRoutes()[routeIndex], groupWorkspaces, present);
        } else if (routeIndex == JsonUnwrappedInfo.SKIP || anyReader == null) {
          reader.skipValue();
        } else {
          String name = reader.materializeFieldName(start);
          Object value = anyReader.readUtf16(reader);
          if (creatorInfo == null && !anyInfo.fieldRead()) {
            anyInfo.put(rootWorkspace, name, value);
          } else {
            if (anyMap == null) {
              if (creatorInfo == null) {
                anyMap = anyInfo.readMap(rootWorkspace);
              }
              if (anyMap == null) {
                if (creatorInfo == null && anyInfo.finalReadField()) {
                  throw nullFinalAnyMap();
                }
                anyMap = newAnyMap();
                newAnyMap = true;
              }
            }
            putAnyMap(anyMap, name, value);
          }
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    finishUnwrappedAny(rootWorkspace, anyMap, newAnyMap);
    finishUnwrappedGroups(rootWorkspace, groupWorkspaces, present);
    T object = castFinished(finishUnwrappedWorkspace(rootWorkspace));
    reader.exitDepth();
    return object;
  }

  private T readUtf8UnwrappedObject(Utf8JsonReader reader, JsonFieldTable directTable) {
    reader.enterDepth();
    Object rootWorkspace = newUnwrappedWorkspace();
    Group[] resolvedGroups = unwrappedInfo.groups();
    Object[] groupWorkspaces = new Object[resolvedGroups.length];
    boolean[] present = new boolean[resolvedGroups.length];
    Map<Object, Object> anyMap = null;
    boolean newAnyMap = false;
    Utf8ReaderCodec<Object> anyReader =
        anyInfo != null && anyInfo.readEnabled() ? anyInfo.valueTypeInfo.utf8Reader() : null;
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int start = reader.position();
        long hash = reader.readFieldNameHash();
        int direct = creatorInfo != null ? creatorInfo.index(hash) : directTable.match(hash);
        reader.expect(':');
        if (direct >= 0) {
          if (creatorInfo == null) {
            readFields[direct].readUtf8(reader, rootWorkspace);
          } else {
            JsonCreatorFieldInfo field = creatorInfo.fields()[direct];
            ((Object[]) rootWorkspace)[field.argumentIndex()] = field.readUtf8(reader);
          }
          continue;
        }
        int directMiss = creatorInfo != null ? directTable.match(hash) : direct;
        if (directMiss == JsonFieldTable.SKIP) {
          reader.skipValue();
          continue;
        }
        int routeIndex = unwrappedInfo.match(hash);
        if (routeIndex >= 0) {
          readUtf8Route(reader, unwrappedInfo.readRoutes()[routeIndex], groupWorkspaces, present);
        } else if (routeIndex == JsonUnwrappedInfo.SKIP || anyReader == null) {
          reader.skipValue();
        } else {
          String name = reader.materializeFieldName(start);
          Object value = anyReader.readUtf8(reader);
          if (creatorInfo == null && !anyInfo.fieldRead()) {
            anyInfo.put(rootWorkspace, name, value);
          } else {
            if (anyMap == null) {
              if (creatorInfo == null) {
                anyMap = anyInfo.readMap(rootWorkspace);
              }
              if (anyMap == null) {
                if (creatorInfo == null && anyInfo.finalReadField()) {
                  throw nullFinalAnyMap();
                }
                anyMap = newAnyMap();
                newAnyMap = true;
              }
            }
            putAnyMap(anyMap, name, value);
          }
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    finishUnwrappedAny(rootWorkspace, anyMap, newAnyMap);
    finishUnwrappedGroups(rootWorkspace, groupWorkspaces, present);
    T object = castFinished(finishUnwrappedWorkspace(rootWorkspace));
    reader.exitDepth();
    return object;
  }

  private void readLatin1Route(
      Latin1JsonReader reader, ReadRoute route, Object[] groupWorkspaces, boolean[] present) {
    Object workspace = ensureUnwrappedGroup(route.group(), groupWorkspaces, present);
    ObjectCodec<?> child = route.group().childCodec();
    if (child.creatorInfo == null) {
      route.field().readLatin1(reader, workspace);
    } else {
      JsonCreatorFieldInfo field = route.creatorField();
      ((Object[]) workspace)[field.argumentIndex()] = field.readLatin1(reader);
    }
  }

  private void readUtf16Route(
      Utf16JsonReader reader, ReadRoute route, Object[] groupWorkspaces, boolean[] present) {
    Object workspace = ensureUnwrappedGroup(route.group(), groupWorkspaces, present);
    ObjectCodec<?> child = route.group().childCodec();
    if (child.creatorInfo == null) {
      route.field().readUtf16(reader, workspace);
    } else {
      JsonCreatorFieldInfo field = route.creatorField();
      ((Object[]) workspace)[field.argumentIndex()] = field.readUtf16(reader);
    }
  }

  private void readUtf8Route(
      Utf8JsonReader reader, ReadRoute route, Object[] groupWorkspaces, boolean[] present) {
    Object workspace = ensureUnwrappedGroup(route.group(), groupWorkspaces, present);
    ObjectCodec<?> child = route.group().childCodec();
    if (child.creatorInfo == null) {
      route.field().readUtf8(reader, workspace);
    } else {
      JsonCreatorFieldInfo field = route.creatorField();
      ((Object[]) workspace)[field.argumentIndex()] = field.readUtf8(reader);
    }
  }

  private void finishUnwrappedAny(
      Object rootWorkspace, Map<Object, Object> anyMap, boolean newAnyMap) {
    if (anyMap == null) {
      return;
    }
    if (creatorInfo == null) {
      if (newAnyMap) {
        anyInfo.setReadMap(rootWorkspace, finishAnyMap(anyMap));
      }
    } else {
      ((Object[]) rootWorkspace)[anyInfo.constructionIndex] = finishAnyMap(anyMap);
    }
  }

  @SuppressWarnings("unchecked")
  private T castFinished(Object value) {
    return (T) value;
  }

  private T readLatin1AnyObject(Latin1JsonReader reader, JsonFieldTable table) {
    reader.enterDepth();
    T object;
    if (creatorInfo != null) {
      object = create(readLatin1AnyCreatorArguments(reader, table));
    } else {
      object = readLatin1AnyMutable(reader, table);
    }
    reader.exitDepth();
    return object;
  }

  private T readUtf16AnyObject(Utf16JsonReader reader, JsonFieldTable table) {
    reader.enterDepth();
    T object;
    if (creatorInfo != null) {
      object = create(readUtf16AnyCreatorArguments(reader, table));
    } else {
      object = readUtf16AnyMutable(reader, table);
    }
    reader.exitDepth();
    return object;
  }

  private T readUtf8AnyObject(Utf8JsonReader reader, JsonFieldTable table) {
    reader.enterDepth();
    T object;
    if (creatorInfo != null) {
      object = create(readUtf8AnyCreatorArguments(reader, table));
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
    if (unwrappedInfo != null) {
      writeUnwrappedMembers(writer, value, written);
      return;
    }
    if (anyInfo != null && anyInfo.writeEnabled()) {
      writeAnyMembers(writer, value, written);
      return;
    }
    writeFixedMembers(writer, value, written);
  }

  final void writeMembers(Utf8JsonWriter writer, T value, int written) {
    if (unwrappedInfo != null) {
      writeUnwrappedMembers(writer, value, written);
      return;
    }
    if (anyInfo != null && anyInfo.writeEnabled()) {
      writeAnyMembers(writer, value, written);
      return;
    }
    writeFixedMembers(writer, value, written);
  }

  private int writeUnwrappedMembers(StringJsonWriter writer, Object value, int written) {
    WriteEntry[] steps = unwrappedInfo.writeSteps();
    int[] depths = unwrappedInfo.writeDepths();
    int[] ends = unwrappedInfo.writeEnds();
    Object[] owners = new Object[unwrappedInfo.maxWriteDepth() + 1];
    owners[0] = value;
    for (int i = 0; i < steps.length; ) {
      WriteEntry entry = steps[i];
      int depth = depths[i];
      Object current = owners[depth];
      if (entry.kind() == JsonUnwrappedInfo.DIRECT) {
        if (entry.field().writeString(writer, current, written)) {
          written++;
        }
        i++;
      } else if (entry.kind() == JsonUnwrappedInfo.ANY) {
        written =
            writeStringAny(
                writer, anyInfo.writeMap(current), anyInfo.valueTypeInfo.stringWriter(), written);
        i++;
      } else {
        Object child = entry.group().declaration().writeAccessor().getObject(current);
        if (child == null) {
          i = ends[i];
        } else {
          owners[depth + 1] = child;
          i++;
        }
      }
    }
    return written;
  }

  private int writeUnwrappedMembers(Utf8JsonWriter writer, Object value, int written) {
    WriteEntry[] steps = unwrappedInfo.writeSteps();
    int[] depths = unwrappedInfo.writeDepths();
    int[] ends = unwrappedInfo.writeEnds();
    Object[] owners = new Object[unwrappedInfo.maxWriteDepth() + 1];
    owners[0] = value;
    for (int i = 0; i < steps.length; ) {
      WriteEntry entry = steps[i];
      int depth = depths[i];
      Object current = owners[depth];
      if (entry.kind() == JsonUnwrappedInfo.DIRECT) {
        if (entry.field().writeUtf8(writer, current, written)) {
          written++;
        }
        i++;
      } else if (entry.kind() == JsonUnwrappedInfo.ANY) {
        written =
            writeUtf8Any(
                writer, anyInfo.writeMap(current), anyInfo.valueTypeInfo.utf8Writer(), written);
        i++;
      } else {
        Object child = entry.group().declaration().writeAccessor().getObject(current);
        if (child == null) {
          i = ends[i];
        } else {
          owners[depth + 1] = child;
          i++;
        }
      }
    }
    return written;
  }

  private Object newUnwrappedWorkspace() {
    return creatorInfo == null ? newInstance() : creatorInfo.newArguments();
  }

  private Object finishUnwrappedWorkspace(Object workspace) {
    return creatorInfo == null ? workspace : create((Object[]) workspace);
  }

  private Object ensureUnwrappedGroup(Group group, Object[] groupWorkspaces, boolean[] present) {
    int target = group.readIndex();
    int[] parents = unwrappedInfo.groupParents();
    int current = target;
    while (parents[current] >= 0 && !present[parents[current]]) {
      current = parents[current];
    }
    int[] ends = unwrappedInfo.groupEnds();
    ObjectCodec<?>[] codecs = unwrappedInfo.groupCodecs();
    while (true) {
      if (!present[current]) {
        groupWorkspaces[current] = codecs[current].newUnwrappedWorkspace();
        present[current] = true;
      }
      if (current == target) {
        return groupWorkspaces[target];
      }
      current++;
      while (ends[current] < target) {
        current = ends[current] + 1;
      }
    }
  }

  private void finishUnwrappedGroups(
      Object rootWorkspace, Object[] groupWorkspaces, boolean[] present) {
    Group[] resolvedGroups = unwrappedInfo.groups();
    for (int i = resolvedGroups.length - 1; i >= 0; i--) {
      if (!present[i]) {
        continue;
      }
      Group group = resolvedGroups[i];
      Object child = group.childCodec().finishUnwrappedWorkspace(groupWorkspaces[i]);
      Group parent = group.parent();
      Object parentWorkspace = parent == null ? rootWorkspace : groupWorkspaces[parent.readIndex()];
      group.parentCodec().assignUnwrapped(group.declaration(), parentWorkspace, child);
    }
  }

  private void assignUnwrapped(Declaration declaration, Object workspace, Object child) {
    if (creatorInfo == null) {
      declaration.readAccessor().putObject(workspace, child);
    } else {
      ((Object[]) workspace)[declaration.constructionIndex()] = child;
    }
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

  @Internal
  public static final class AnyInfo {
    private final Field writeField;
    private final Method writeGetter;
    private final Field readField;
    private final Method readSetter;
    private final Class<?> setterValueRawType;
    private final JsonFieldAccessor writeAccessor;
    private final JsonFieldAccessor readAccessor;
    private final JsonAnySetterAccessor generatedSetter;
    private final MethodHandle setterHandle;
    private final Type mapType;
    private final Class<?> mapRawType;
    private final Type valueType;
    private final Class<?> valueRawType;
    private final JsonCodec valueCodecAnnotation;
    private final Class<? extends JsonValueCodec<?>> valueCodecClass;
    private final int writeIndex;
    private final int constructionIndex;
    private JsonTypeInfo valueTypeInfo;
    private MapCodec<?> mapCodec;

    AnyInfo(
        Field writeField,
        Method writeGetter,
        Field readField,
        Method readSetter,
        JsonFieldAccessor writeAccessor,
        JsonFieldAccessor readAccessor,
        JsonAnySetterAccessor generatedSetter,
        Type mapType,
        Class<?> mapRawType,
        Type valueType,
        Class<?> valueRawType,
        JsonCodec valueCodecAnnotation,
        Class<? extends JsonValueCodec<?>> valueCodecClass,
        int writeIndex,
        int constructionIndex) {
      this.writeField = writeField;
      this.writeGetter = writeGetter;
      this.readField = readField;
      this.readSetter = readSetter;
      setterValueRawType = readSetter == null ? null : readSetter.getParameterTypes()[1];
      this.writeAccessor = writeAccessor;
      this.readAccessor = readAccessor;
      this.generatedSetter = generatedSetter;
      setterHandle =
          readSetter == null || generatedSetter != null || AndroidSupport.IS_ANDROID
              ? null
              : methodHandle(readSetter);
      if (readSetter != null && generatedSetter == null && AndroidSupport.IS_ANDROID) {
        readSetter.setAccessible(true);
      }
      this.mapType = mapType;
      this.mapRawType = mapRawType;
      this.valueType = valueType;
      this.valueRawType = valueRawType;
      this.valueCodecAnnotation = valueCodecAnnotation;
      this.valueCodecClass = valueCodecClass;
      this.writeIndex = writeIndex;
      this.constructionIndex = constructionIndex;
    }

    private void resolveTypes(JsonTypeResolver resolver) {
      if (readField != null && (valueCodecAnnotation != null || valueCodecClass != null)) {
        resolver.checkMapKeySecure(String.class);
      }
      valueTypeInfo =
          valueCodecAnnotation != null
              ? resolver.getTypeInfo(valueType, valueRawType, valueCodecAnnotation)
              : valueCodecClass != null
                  ? resolver.getTypeInfo(valueType, valueRawType, valueCodecClass)
                  : resolver.getTypeInfo(valueType, valueRawType);
      if (readField != null) {
        mapCodec =
            valueCodecAnnotation == null && valueCodecClass == null
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

    private boolean writeEnabled() {
      return writeAccessor != null;
    }

    private boolean readEnabled() {
      return constructionIndex >= 0 || readAccessor != null || readSetter != null;
    }

    private boolean fieldRead() {
      return readField != null;
    }

    private boolean finalReadField() {
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

    private void put(Object target, String name, Object value) {
      if (value == null && setterValueRawType.isPrimitive()) {
        throw new ForyJsonException(
            "Cannot read null into primitive @JsonAnySetter parameter " + setterValueRawType);
      }
      if (generatedSetter != null) {
        generatedSetter.put(target, name, value);
        return;
      }
      try {
        if (AndroidSupport.IS_ANDROID) {
          readSetter.invoke(target, name, value);
        } else {
          setterHandle.invoke(target, name, value);
        }
      } catch (Throwable e) {
        Throwable cause =
            e instanceof InvocationTargetException ? ((InvocationTargetException) e).getCause() : e;
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
        JsonUnwrappedInfo unwrappedInfo,
        ObjectInstantiator<?> instantiator) {
      super(
          type,
          writeFields,
          readFields,
          creatorInfo,
          anyInfo,
          skippedNames,
          unwrappedInfo,
          instantiator);
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

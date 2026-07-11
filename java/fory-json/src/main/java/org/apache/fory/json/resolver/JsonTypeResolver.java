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

package org.apache.fory.json.resolver;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.codec.CodecUtils;
import org.apache.fory.json.codec.CollectionCodec.ObjectCollectionCodec;
import org.apache.fory.json.codec.JsonCodec;
import org.apache.fory.json.codec.Latin1ReaderCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.StringWriterCodec;
import org.apache.fory.json.codec.Utf16ReaderCodec;
import org.apache.fory.json.codec.Utf8ReaderCodec;
import org.apache.fory.json.codec.Utf8WriterCodec;
import org.apache.fory.json.codegen.JsonJITContext;
import org.apache.fory.reflect.TypeRef;

/**
 * Local JSON type dispatcher used exclusively by one borrowed {@code ForyJson} state at a time.
 *
 * <p>Async compiler threads publish immutable generated classes only. Resolver-local capability
 * installation always runs on this state's owning thread through {@link JsonJITContext.LocalState}.
 */
public final class JsonTypeResolver {
  private final Map<Object, ObjectCodec<?>> objectCodecs;
  private final Map<Object, JsonTypeInfo> typeInfos;
  private final JsonSharedRegistry sharedRegistry;
  private final JsonJITContext.LocalState jitState;

  private enum RuntimeObjectKey {
    INSTANCE
  }

  public JsonTypeResolver(JsonSharedRegistry sharedRegistry) {
    this.sharedRegistry = sharedRegistry;
    objectCodecs = new HashMap<>();
    typeInfos = new HashMap<>();
    jitState = sharedRegistry.jitContext().newLocalState(this);
  }

  public <T> ObjectCodec<T> getObjectCodec(Class<T> type) {
    return getObjectCodec(TypeRef.of(type));
  }

  @SuppressWarnings("unchecked")
  private <T> ObjectCodec<T> getObjectCodec(TypeRef<T> ownerType) {
    Class<?> rawType = ownerType.getRawType();
    Object key = typeInfoKey(ownerType.getType(), rawType);
    ObjectCodec<?> codec = objectCodecs.get(key);
    if (codec != null) {
      return (ObjectCodec<T>) codec;
    }
    return buildObjectCodec(ownerType, key);
  }

  public JsonTypeInfo getTypeInfo(Type declaredType, Class<?> fallback) {
    Class<?> rawType = CodecUtils.rawType(declaredType, fallback);
    Object key = typeInfoKey(declaredType, rawType);
    JsonTypeInfo typeInfo = typeInfos.get(key);
    if (typeInfo != null) {
      return typeInfo;
    }
    typeInfo = buildTypeInfo(rawType, declaredType);
    typeInfos.put(key, typeInfo);
    return typeInfo;
  }

  public JsonTypeInfo getRuntimeTypeInfo(Class<?> runtimeType) {
    Object key = runtimeType == Object.class ? RuntimeObjectKey.INSTANCE : runtimeType;
    JsonTypeInfo typeInfo = typeInfos.get(key);
    if (typeInfo != null) {
      return typeInfo;
    }
    typeInfo = buildRuntimeTypeInfo(runtimeType);
    typeInfos.put(key, typeInfo);
    return typeInfo;
  }

  public void checkSecure(Class<?> type) {
    sharedRegistry.checkSecure(type);
  }

  @SuppressWarnings("unchecked")
  public <T> StringWriterCodec<T> stringWriter(ObjectCodec<T> codec) {
    return (StringWriterCodec<T>) (StringWriterCodec<?>) jitState.stringWriter(codec);
  }

  @SuppressWarnings("unchecked")
  public <T> Utf8WriterCodec<T> utf8Writer(ObjectCodec<T> codec) {
    return (Utf8WriterCodec<T>) (Utf8WriterCodec<?>) jitState.utf8Writer(codec);
  }

  @SuppressWarnings("unchecked")
  public <T> Latin1ReaderCodec<T> latin1Reader(ObjectCodec<T> codec) {
    return (Latin1ReaderCodec<T>) (Latin1ReaderCodec<?>) jitState.latin1Reader(codec);
  }

  @SuppressWarnings("unchecked")
  public <T> Utf16ReaderCodec<T> utf16Reader(ObjectCodec<T> codec) {
    return (Utf16ReaderCodec<T>) (Utf16ReaderCodec<?>) jitState.utf16Reader(codec);
  }

  @SuppressWarnings("unchecked")
  public <T> Utf8ReaderCodec<T> utf8Reader(ObjectCodec<T> codec) {
    return (Utf8ReaderCodec<T>) (Utf8ReaderCodec<?>) jitState.utf8Reader(codec);
  }

  public void registerStringWriterUpdate(
      Class<?> type, Consumer<StringWriterCodec<Object>> updater) {
    jitState.registerStringWriterUpdate(type, updater);
  }

  public void registerUtf8WriterUpdate(Class<?> type, Consumer<Utf8WriterCodec<Object>> updater) {
    jitState.registerUtf8WriterUpdate(type, updater);
  }

  public void registerLatin1ReaderUpdate(
      Class<?> type, Consumer<Latin1ReaderCodec<Object>> updater) {
    jitState.registerLatin1ReaderUpdate(type, updater);
  }

  public void registerUtf16ReaderUpdate(Class<?> type, Consumer<Utf16ReaderCodec<Object>> updater) {
    jitState.registerUtf16ReaderUpdate(type, updater);
  }

  public void registerUtf8ReaderUpdate(Class<?> type, Consumer<Utf8ReaderCodec<Object>> updater) {
    jitState.registerUtf8ReaderUpdate(type, updater);
  }

  @Internal
  public void installStringWriter(Class<?> type, StringWriterCodec<Object> codec) {
    for (JsonTypeInfo typeInfo : typeInfos.values()) {
      if (matchesObjectType(typeInfo, type)) {
        typeInfo.setStringWriter(codec);
      }
    }
  }

  @Internal
  public void installUtf8Writer(Class<?> type, Utf8WriterCodec<Object> codec) {
    for (JsonTypeInfo typeInfo : typeInfos.values()) {
      if (matchesObjectType(typeInfo, type)) {
        typeInfo.setUtf8Writer(codec);
      }
    }
  }

  @Internal
  public void installLatin1Reader(Class<?> type, Latin1ReaderCodec<Object> codec) {
    for (JsonTypeInfo typeInfo : typeInfos.values()) {
      if (matchesObjectType(typeInfo, type)) {
        typeInfo.setLatin1Reader(codec);
      }
    }
  }

  @Internal
  public void installUtf16Reader(Class<?> type, Utf16ReaderCodec<Object> codec) {
    for (JsonTypeInfo typeInfo : typeInfos.values()) {
      if (matchesObjectType(typeInfo, type)) {
        typeInfo.setUtf16Reader(codec);
      }
    }
  }

  @Internal
  public void installUtf8Reader(Class<?> type, Utf8ReaderCodec<Object> codec) {
    for (JsonTypeInfo typeInfo : typeInfos.values()) {
      if (matchesObjectType(typeInfo, type)) {
        typeInfo.setUtf8Reader(codec);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private <T> ObjectCodec<T> buildObjectCodec(TypeRef<T> ownerType, Object key) {
    ObjectCodec<?> cached = objectCodecs.get(key);
    if (cached != null) {
      return (ObjectCodec<T>) cached;
    }
    Class<?> type = ownerType.getRawType();
    sharedRegistry.checkSecure(type);
    ObjectCodec<T> codec = ObjectCodec.build(ownerType, sharedRegistry.propertyDiscoveryEnabled());
    // Publish the complete declared-type owner before resolving fields so recursive parameterized
    // bindings resolve back to the same field table rather than the raw-class binding.
    objectCodecs.put(key, codec);
    try {
      codec.resolveTypes(this);
      return codec;
    } catch (RuntimeException | Error e) {
      objectCodecs.remove(key, codec);
      throw e;
    }
  }

  private JsonTypeInfo buildTypeInfo(Class<?> rawType, Type declaredType) {
    sharedRegistry.checkSecure(rawType);
    TypeRef<?> typeRef = typeRef(declaredType, rawType);
    JsonCodec<?> codec = sharedRegistry.createCodec(rawType, typeRef, this);
    if (codec == null) {
      codec = getObjectCodec(typeRef);
    }
    JsonTypeInfo typeInfo = newTypeInfo(declaredType, typeRef, rawType, codec);
    bindInstalled(typeInfo);
    return typeInfo;
  }

  private JsonTypeInfo buildRuntimeTypeInfo(Class<?> rawType) {
    sharedRegistry.checkSecure(rawType);
    TypeRef<?> typeRef = TypeRef.of(rawType);
    JsonCodec<?> codec =
        rawType == Object.class
            ? getObjectCodec(Object.class)
            : sharedRegistry.createCodec(rawType, typeRef, this);
    if (codec == null) {
      codec = getObjectCodec(rawType);
    }
    JsonTypeInfo typeInfo = newTypeInfo(rawType, typeRef, rawType, codec);
    bindInstalled(typeInfo);
    return typeInfo;
  }

  private void bindInstalled(JsonTypeInfo typeInfo) {
    if (!typeInfo.usesDefaultObjectCodec()) {
      return;
    }
    Class<?> type = typeInfo.rawType();
    StringWriterCodec<Object> stringWriter = jitState.installedStringWriter(type);
    if (stringWriter != null) {
      typeInfo.setStringWriter(stringWriter);
    }
    Utf8WriterCodec<Object> utf8Writer = jitState.installedUtf8Writer(type);
    if (utf8Writer != null) {
      typeInfo.setUtf8Writer(utf8Writer);
    }
    Latin1ReaderCodec<Object> latin1Reader = jitState.installedLatin1Reader(type);
    if (latin1Reader != null) {
      typeInfo.setLatin1Reader(latin1Reader);
    }
    Utf16ReaderCodec<Object> utf16Reader = jitState.installedUtf16Reader(type);
    if (utf16Reader != null) {
      typeInfo.setUtf16Reader(utf16Reader);
    }
    Utf8ReaderCodec<Object> utf8Reader = jitState.installedUtf8Reader(type);
    if (utf8Reader != null) {
      typeInfo.setUtf8Reader(utf8Reader);
    }
  }

  private JsonTypeInfo newTypeInfo(
      Type type, TypeRef<?> typeRef, Class<?> rawType, JsonCodec<?> codec) {
    boolean objectCollectionCodec = codec.getClass() == ObjectCollectionCodec.class;
    boolean collectionCreatesArrayList =
        objectCollectionCodec && ((ObjectCollectionCodec) codec).createsArrayList();
    return new JsonTypeInfo(
        type,
        typeRef,
        rawType,
        sharedRegistry.kind(rawType),
        bindCodec(codec),
        objectCollectionCodec,
        collectionCreatesArrayList);
  }

  private static boolean matchesObjectType(JsonTypeInfo typeInfo, Class<?> type) {
    return typeInfo.usesDefaultObjectCodec() && typeInfo.rawType() == type;
  }

  private static Object typeInfoKey(Type declaredType, Class<?> rawType) {
    return declaredType instanceof Class ? rawType : declaredType;
  }

  private static TypeRef<?> typeRef(Type declaredType, Class<?> rawType) {
    if (declaredType == null || declaredType == Object.class && rawType != Object.class) {
      return TypeRef.of(rawType);
    }
    return TypeRef.of(declaredType);
  }

  @SuppressWarnings("unchecked")
  private static JsonCodec<Object> bindCodec(JsonCodec<?> codec) {
    // The resolver has already matched the codec to this binding's declared type. JsonTypeInfo is
    // deliberately heterogeneous, so erase that proven relation once instead of casting in every
    // root, field, container, and generated hot call.
    return (JsonCodec<Object>) codec;
  }
}

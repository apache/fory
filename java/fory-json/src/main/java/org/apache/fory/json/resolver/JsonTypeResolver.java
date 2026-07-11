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
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.codec.CodecUtils;
import org.apache.fory.json.codec.JsonCodec;
import org.apache.fory.json.codec.Latin1ObjectReaderCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.StringObjectWriterCodec;
import org.apache.fory.json.codec.StringWriterCodec;
import org.apache.fory.json.codec.Utf16ObjectReaderCodec;
import org.apache.fory.json.codec.Utf8ObjectReaderCodec;
import org.apache.fory.json.codec.Utf8ObjectWriterCodec;
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
  private final Map<Class<?>, ObjectCodec> objectCodecs;
  private final Map<Object, JsonTypeInfo> typeInfos;
  private final JsonSharedRegistry sharedRegistry;
  private final JsonJITContext.LocalState jitState;

  private enum RuntimeObjectKey {
    INSTANCE
  }

  public JsonTypeResolver(JsonSharedRegistry sharedRegistry) {
    this.sharedRegistry = sharedRegistry;
    objectCodecs = new IdentityHashMap<>();
    typeInfos = new HashMap<>();
    jitState = sharedRegistry.jitContext().newLocalState(this);
  }

  public ObjectCodec getObjectCodec(Class<?> type) {
    ObjectCodec codec = objectCodecs.get(type);
    if (codec != null) {
      return codec;
    }
    return buildObjectCodec(type);
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

  public StringWriterCodec stringWriter(ObjectCodec codec) {
    return jitState.stringWriter(codec);
  }

  public Utf8WriterCodec utf8Writer(ObjectCodec codec) {
    return jitState.utf8Writer(codec);
  }

  public Latin1ObjectReaderCodec latin1Reader(ObjectCodec codec) {
    return jitState.latin1Reader(codec);
  }

  public Utf16ObjectReaderCodec utf16Reader(ObjectCodec codec) {
    return jitState.utf16Reader(codec);
  }

  public Utf8ObjectReaderCodec utf8Reader(ObjectCodec codec) {
    return jitState.utf8Reader(codec);
  }

  public void registerStringWriterUpdate(Class<?> type, Consumer<StringWriterCodec> updater) {
    jitState.registerStringWriterUpdate(type, updater);
  }

  public void registerUtf8WriterUpdate(Class<?> type, Consumer<Utf8WriterCodec> updater) {
    jitState.registerUtf8WriterUpdate(type, updater);
  }

  public void registerLatin1ReaderUpdate(Class<?> type, Consumer<Latin1ObjectReaderCodec> updater) {
    jitState.registerLatin1ReaderUpdate(type, updater);
  }

  public void registerUtf16ReaderUpdate(Class<?> type, Consumer<Utf16ObjectReaderCodec> updater) {
    jitState.registerUtf16ReaderUpdate(type, updater);
  }

  public void registerUtf8ReaderUpdate(Class<?> type, Consumer<Utf8ObjectReaderCodec> updater) {
    jitState.registerUtf8ReaderUpdate(type, updater);
  }

  @Internal
  public void installStringWriter(Class<?> type, StringObjectWriterCodec codec) {
    for (JsonTypeInfo typeInfo : typeInfos.values()) {
      if (matchesObjectType(typeInfo, type)) {
        typeInfo.setStringWriter(codec);
      }
    }
  }

  @Internal
  public void installUtf8Writer(Class<?> type, Utf8ObjectWriterCodec codec) {
    for (JsonTypeInfo typeInfo : typeInfos.values()) {
      if (matchesObjectType(typeInfo, type)) {
        typeInfo.setUtf8Writer(codec);
      }
    }
  }

  @Internal
  public void installLatin1Reader(Class<?> type, Latin1ObjectReaderCodec codec) {
    for (JsonTypeInfo typeInfo : typeInfos.values()) {
      if (matchesObjectType(typeInfo, type)) {
        typeInfo.setLatin1Reader(codec);
      }
    }
  }

  @Internal
  public void installUtf16Reader(Class<?> type, Utf16ObjectReaderCodec codec) {
    for (JsonTypeInfo typeInfo : typeInfos.values()) {
      if (matchesObjectType(typeInfo, type)) {
        typeInfo.setUtf16Reader(codec);
      }
    }
  }

  @Internal
  public void installUtf8Reader(Class<?> type, Utf8ObjectReaderCodec codec) {
    for (JsonTypeInfo typeInfo : typeInfos.values()) {
      if (matchesObjectType(typeInfo, type)) {
        typeInfo.setUtf8Reader(codec);
      }
    }
  }

  private ObjectCodec buildObjectCodec(Class<?> type) {
    ObjectCodec cached = objectCodecs.get(type);
    if (cached != null) {
      return cached;
    }
    sharedRegistry.checkSecure(type);
    ObjectCodec codec = ObjectCodec.build(type, sharedRegistry.propertyDiscoveryEnabled());
    // Resolve recursive fields against this stable interpreted metadata owner.
    objectCodecs.put(type, codec);
    try {
      codec.resolveTypes(this);
      return codec;
    } catch (RuntimeException | Error e) {
      objectCodecs.remove(type, codec);
      throw e;
    }
  }

  private JsonTypeInfo buildTypeInfo(Class<?> rawType, Type declaredType) {
    sharedRegistry.checkSecure(rawType);
    TypeRef<?> typeRef = typeRef(declaredType, rawType);
    JsonCodec codec = sharedRegistry.createCodec(rawType, typeRef, this);
    if (codec == null) {
      codec = getObjectCodec(rawType);
    }
    JsonTypeInfo typeInfo =
        new JsonTypeInfo(declaredType, typeRef, rawType, sharedRegistry.kind(rawType), codec);
    bindInstalled(typeInfo);
    return typeInfo;
  }

  private JsonTypeInfo buildRuntimeTypeInfo(Class<?> rawType) {
    sharedRegistry.checkSecure(rawType);
    TypeRef<?> typeRef = TypeRef.of(rawType);
    JsonCodec codec =
        rawType == Object.class
            ? getObjectCodec(Object.class)
            : sharedRegistry.createCodec(rawType, typeRef, this);
    if (codec == null) {
      codec = getObjectCodec(rawType);
    }
    JsonTypeInfo typeInfo =
        new JsonTypeInfo(rawType, typeRef, rawType, sharedRegistry.kind(rawType), codec);
    bindInstalled(typeInfo);
    return typeInfo;
  }

  private void bindInstalled(JsonTypeInfo typeInfo) {
    if (!typeInfo.usesDefaultObjectCodec()) {
      return;
    }
    Class<?> type = typeInfo.rawType();
    StringObjectWriterCodec stringWriter = jitState.installedStringWriter(type);
    if (stringWriter != null) {
      typeInfo.setStringWriter(stringWriter);
    }
    Utf8ObjectWriterCodec utf8Writer = jitState.installedUtf8Writer(type);
    if (utf8Writer != null) {
      typeInfo.setUtf8Writer(utf8Writer);
    }
    Latin1ObjectReaderCodec latin1Reader = jitState.installedLatin1Reader(type);
    if (latin1Reader != null) {
      typeInfo.setLatin1Reader(latin1Reader);
    }
    Utf16ObjectReaderCodec utf16Reader = jitState.installedUtf16Reader(type);
    if (utf16Reader != null) {
      typeInfo.setUtf16Reader(utf16Reader);
    }
    Utf8ObjectReaderCodec utf8Reader = jitState.installedUtf8Reader(type);
    if (utf8Reader != null) {
      typeInfo.setUtf8Reader(utf8Reader);
    }
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
}

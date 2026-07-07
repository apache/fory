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
import org.apache.fory.json.codec.BaseObjectCodec;
import org.apache.fory.json.codec.CodecUtils;
import org.apache.fory.json.codec.JsonCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.ObjectCodecs;
import org.apache.fory.reflect.TypeRef;

/**
 * Local JSON type dispatcher and cache used by one borrowed {@code ForyJson} state at a time.
 *
 * <p>This cache is limited to schema/static metadata such as resolved codecs and object layouts.
 * Runtime JSON values, including non-enumerated string or number values/tokens, must stay uncached.
 */
public final class JsonTypeResolver {
  private final IdentityHashMap<Class<?>, BaseObjectCodec> objectCodecs = new IdentityHashMap<>();
  private final Map<Object, JsonTypeInfo> typeInfos = new HashMap<>();
  private final JsonSharedRegistry sharedRegistry;

  private enum RuntimeObjectKey {
    INSTANCE
  }

  public JsonTypeResolver(JsonSharedRegistry sharedRegistry) {
    this.sharedRegistry = sharedRegistry;
  }

  public BaseObjectCodec getObjectCodec(Class<?> type) {
    BaseObjectCodec codec = objectCodecs.get(type);
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
    return buildTypeInfo(key, rawType, declaredType);
  }

  public JsonTypeInfo getRuntimeTypeInfo(Class<?> runtimeType) {
    Object key = runtimeType == Object.class ? RuntimeObjectKey.INSTANCE : runtimeType;
    JsonTypeInfo typeInfo = typeInfos.get(key);
    if (typeInfo != null) {
      return typeInfo;
    }
    return buildRuntimeTypeInfo(key, runtimeType);
  }

  private BaseObjectCodec buildObjectCodec(Class<?> type) {
    BaseObjectCodec cached = objectCodecs.get(type);
    if (cached != null) {
      return cached;
    }
    ObjectCodec codec = BaseObjectCodec.build(type, sharedRegistry.propertyDiscoveryEnabled());
    // Codegen may ask for nested object metadata that points back to this type.
    // Publishing before compiling keeps recursive ownership in this resolver cache.
    objectCodecs.put(type, codec);
    try {
      codec.resolveTypes(this);
      ObjectCodecs codecs = sharedRegistry.compileObject(codec, this);
      if (codecs != null) {
        BaseObjectCodec generated = codec.withCodecs(codecs);
        objectCodecs.put(type, generated);
        return generated;
      }
      return codec;
    } catch (RuntimeException | Error e) {
      objectCodecs.remove(type, codec);
      throw e;
    }
  }

  private JsonTypeInfo buildTypeInfo(Object key, Class<?> rawType, Type declaredType) {
    JsonTypeInfo cached = typeInfos.get(key);
    if (cached != null) {
      return cached;
    }
    JsonTypeInfo typeInfo = buildTypeInfo(rawType, declaredType);
    typeInfos.put(key, typeInfo);
    return typeInfo;
  }

  private JsonTypeInfo buildTypeInfo(Class<?> rawType, Type declaredType) {
    TypeRef<?> typeRef = typeRef(declaredType, rawType);
    JsonCodec codec = sharedRegistry.createCodec(rawType, typeRef, this);
    if (codec == null) {
      codec = getObjectCodec(rawType);
    }
    return new JsonTypeInfo(declaredType, typeRef, rawType, sharedRegistry.kind(rawType), codec);
  }

  private JsonTypeInfo buildRuntimeTypeInfo(Object key, Class<?> rawType) {
    JsonTypeInfo cached = typeInfos.get(key);
    if (cached != null) {
      return cached;
    }
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
    typeInfos.put(key, typeInfo);
    return typeInfo;
  }

  private static Object typeInfoKey(Type declaredType, Class<?> rawType) {
    if (declaredType instanceof Class) {
      return rawType;
    }
    return declaredType;
  }

  private static TypeRef<?> typeRef(Type declaredType, Class<?> rawType) {
    if (declaredType == null || declaredType == Object.class && rawType != Object.class) {
      return TypeRef.of(rawType);
    }
    return TypeRef.of(declaredType);
  }
}

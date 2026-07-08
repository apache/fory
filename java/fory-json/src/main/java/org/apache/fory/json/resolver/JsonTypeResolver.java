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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.apache.fory.json.codec.BaseObjectCodec;
import org.apache.fory.json.codec.CodecUtils;
import org.apache.fory.json.codec.JsonCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codegen.JsonJITContext;
import org.apache.fory.reflect.TypeRef;

/**
 * Local JSON type dispatcher and cache used by one borrowed {@code ForyJson} state at a time.
 *
 * <p>This cache is limited to schema/static metadata such as resolved codecs and object layouts.
 * Runtime JSON values, including non-enumerated string or number values/tokens, must stay uncached.
 */
public final class JsonTypeResolver {
  private final Map<Class<?>, BaseObjectCodec> objectCodecs;
  private final Map<Object, JsonTypeInfo> typeInfos;
  private final JsonSharedRegistry sharedRegistry;

  private enum RuntimeObjectKey {
    INSTANCE
  }

  public JsonTypeResolver(JsonSharedRegistry sharedRegistry) {
    this.sharedRegistry = sharedRegistry;
    if (sharedRegistry.asyncCompilationEnabled()) {
      objectCodecs = new ConcurrentHashMap<>();
      typeInfos = new ConcurrentHashMap<>();
    } else {
      objectCodecs = new IdentityHashMap<>();
      typeInfos = new HashMap<>();
    }
  }

  public BaseObjectCodec getObjectCodec(Class<?> type) {
    BaseObjectCodec codec = objectCodecs.get(type);
    if (codec != null) {
      return codec;
    }
    try {
      sharedRegistry.jitContext().lock();
      codec = objectCodecs.get(type);
      if (codec != null) {
        return codec;
      }
      return buildObjectCodec(type);
    } finally {
      sharedRegistry.jitContext().unlock();
    }
  }

  public JsonTypeInfo getTypeInfo(Type declaredType, Class<?> fallback) {
    Class<?> rawType = CodecUtils.rawType(declaredType, fallback);
    Object key = typeInfoKey(declaredType, rawType);
    JsonTypeInfo typeInfo = typeInfos.get(key);
    if (typeInfo != null) {
      return typeInfo;
    }
    try {
      sharedRegistry.jitContext().lock();
      typeInfo = typeInfos.get(key);
      if (typeInfo != null) {
        return typeInfo;
      }
      return buildTypeInfo(key, rawType, declaredType);
    } finally {
      sharedRegistry.jitContext().unlock();
    }
  }

  public JsonTypeInfo getRuntimeTypeInfo(Class<?> runtimeType) {
    Object key = runtimeType == Object.class ? RuntimeObjectKey.INSTANCE : runtimeType;
    JsonTypeInfo typeInfo = typeInfos.get(key);
    if (typeInfo != null) {
      return typeInfo;
    }
    try {
      sharedRegistry.jitContext().lock();
      typeInfo = typeInfos.get(key);
      if (typeInfo != null) {
        return typeInfo;
      }
      return buildRuntimeTypeInfo(key, runtimeType);
    } finally {
      sharedRegistry.jitContext().unlock();
    }
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
      BaseObjectCodec compiled =
          sharedRegistry.compileObject(
              codec,
              this,
              new JsonJITContext.ObjectJITCallback<BaseObjectCodec>() {
                @Override
                public void onSuccess(BaseObjectCodec result) {
                  if (result != null) {
                    setObjectCodec(type, result);
                  }
                }

                @Override
                public Object id() {
                  return codec;
                }
              });
      if (compiled != null && compiled != codec) {
        setObjectCodec(type, compiled);
        return compiled;
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
    JsonTypeInfo typeInfo =
        new JsonTypeInfo(declaredType, typeRef, rawType, sharedRegistry.kind(rawType), codec);
    registerCodecUpdate(typeInfo, codec);
    return typeInfo;
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
    registerCodecUpdate(typeInfo, codec);
    typeInfos.put(key, typeInfo);
    return typeInfo;
  }

  public void registerJITNotifyCallback(JsonCodec currentCodec, Consumer<JsonCodec> updater) {
    if (!(currentCodec instanceof BaseObjectCodec)) {
      return;
    }
    BaseObjectCodec objectCodec = (BaseObjectCodec) currentCodec;
    Class<?> type = objectCodec.type();
    if (!sharedRegistry.hasJITResult(objectCodec)) {
      BaseObjectCodec latest = getObjectCodec(type);
      if (latest != objectCodec) {
        updater.accept(latest);
      }
      return;
    }
    sharedRegistry.registerJITNotifyCallback(
        objectCodec,
        new JsonJITContext.NotifyCallback() {
          @Override
          public void onNotifyResult(Object result) {
            if (result instanceof JsonCodec) {
              updater.accept((JsonCodec) result);
            } else {
              onNotifyMissed();
            }
          }

          @Override
          public void onNotifyMissed() {
            BaseObjectCodec latest = getObjectCodec(type);
            if (latest != objectCodec) {
              updater.accept(latest);
            }
          }
        });
  }

  private void registerCodecUpdate(JsonTypeInfo typeInfo, JsonCodec codec) {
    registerJITNotifyCallback(codec, typeInfo::setCodec);
  }

  private void setObjectCodec(Class<?> type, BaseObjectCodec codec) {
    objectCodecs.put(type, codec);
    JsonTypeInfo typeInfo = typeInfos.get(type);
    if (typeInfo != null) {
      typeInfo.setCodec(codec);
    }
    JsonTypeInfo runtimeTypeInfo = typeInfos.get(RuntimeObjectKey.INSTANCE);
    if (type == Object.class && runtimeTypeInfo != null) {
      runtimeTypeInfo.setCodec(codec);
    }
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

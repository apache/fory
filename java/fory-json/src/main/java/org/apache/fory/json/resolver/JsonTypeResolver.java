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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.CodecUtils;
import org.apache.fory.json.codec.JsonCodec;
import org.apache.fory.json.codec.Latin1ReaderCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.StringWriterCodec;
import org.apache.fory.json.codec.Utf16ReaderCodec;
import org.apache.fory.json.codec.Utf8ReaderCodec;
import org.apache.fory.json.codec.Utf8WriterCodec;
import org.apache.fory.json.codegen.JsonCodegen;
import org.apache.fory.json.codegen.JsonJITContext;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.internal._JDKAccess;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;

/**
 * Local JSON type dispatcher used exclusively by one borrowed {@code ForyJson} state at a time.
 *
 * <p>This class corresponds to Fory core's {@code ClassResolver}: it owns terminal capabilities,
 * generated codec construction, capability-slot publication, and generated parent child-field
 * callbacks. Root codec execution and completion callbacks use the same resolver-local JIT lock.
 * {@link JsonJITContext} only orders generic JIT and notify callbacks under that lock; it does not
 * know any JSON capability, codec, generated class, or field metadata.
 */
public final class JsonTypeResolver {
  private final Map<Object, ObjectCodec<?>> objectCodecs;
  private final Map<Object, JsonTypeInfo> typeInfos;
  private final JsonSharedRegistry sharedRegistry;
  private final JsonCodegen codegen;
  private final JsonJITContext jitContext;
  private final Map<Class<?>, StringWriterCodec<Object>> stringWriters;
  private final Map<Class<?>, Utf8WriterCodec<Object>> utf8Writers;
  private final Map<Class<?>, Latin1ReaderCodec<Object>> latin1Readers;
  private final Map<Class<?>, Utf16ReaderCodec<Object>> utf16Readers;
  private final Map<Class<?>, Utf8ReaderCodec<Object>> utf8Readers;

  private enum RuntimeObjectKey {
    INSTANCE
  }

  public JsonTypeResolver(JsonSharedRegistry sharedRegistry) {
    this.sharedRegistry = sharedRegistry;
    objectCodecs = new HashMap<>();
    typeInfos = new HashMap<>();
    codegen = sharedRegistry.codegen();
    jitContext = sharedRegistry.newJITContext();
    stringWriters = new IdentityHashMap<>();
    utf8Writers = new IdentityHashMap<>();
    latin1Readers = new IdentityHashMap<>();
    utf16Readers = new IdentityHashMap<>();
    utf8Readers = new IdentityHashMap<>();
  }

  @Internal
  public void lockJIT() {
    jitContext.lock();
  }

  @Internal
  public void unlockJIT() {
    jitContext.unlock();
  }

  @Internal
  public boolean jitLockedByCurrentThread() {
    return jitContext.lockedByCurrentThread();
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
    JsonTypeInfo recursiveTypeInfo = typeInfos.get(key);
    if (recursiveTypeInfo != null) {
      // Object metadata is published before its fields resolve. A recursive field can therefore
      // create this binding while the outer build is still running; retain that canonical owner so
      // every recursive field observes later capability installation.
      return recursiveTypeInfo;
    }
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
    JsonTypeInfo recursiveTypeInfo = typeInfos.get(key);
    if (recursiveTypeInfo != null) {
      return recursiveTypeInfo;
    }
    typeInfos.put(key, typeInfo);
    return typeInfo;
  }

  public void checkSecure(Class<?> type) {
    sharedRegistry.checkSecure(type);
  }

  @SuppressWarnings("unchecked")
  public <T> StringWriterCodec<T> stringWriter(ObjectCodec<T> codec) {
    requireJITLock();
    ObjectCodec<Object> owner = erase(codec);
    StringWriterCodec<Object> installed = stringWriters.get(owner.type());
    if (installed == null && codegen != null) {
      if (!codegen.canCompileWriter(owner)) {
        keepStringWriter(owner);
      } else {
        jitContext.registerJITCallback(
            () -> owner.getClass(),
            () -> codegen.compileStringWriter(owner),
            new JsonJITContext.JITCallback<Class<?>>() {
              @Override
              public void onSuccess(Class<?> generated) {
                publishStringWriter(owner, generated);
              }

              @Override
              public void onFailure(Throwable failure) {
                keepStringWriter(owner);
              }

              @Override
              public Object id() {
                return codegen.stringWriterJITId(owner.type());
              }
            });
      }
      installed = stringWriters.get(owner.type());
    }
    return (StringWriterCodec<T>) (installed == null ? owner : installed);
  }

  @SuppressWarnings("unchecked")
  public <T> Utf8WriterCodec<T> utf8Writer(ObjectCodec<T> codec) {
    requireJITLock();
    ObjectCodec<Object> owner = erase(codec);
    Utf8WriterCodec<Object> installed = utf8Writers.get(owner.type());
    if (installed == null && codegen != null) {
      if (!codegen.canCompileWriter(owner)) {
        keepUtf8Writer(owner);
      } else {
        jitContext.registerJITCallback(
            () -> owner.getClass(),
            () -> codegen.compileUtf8Writer(owner),
            new JsonJITContext.JITCallback<Class<?>>() {
              @Override
              public void onSuccess(Class<?> generated) {
                publishUtf8Writer(owner, generated);
              }

              @Override
              public void onFailure(Throwable failure) {
                keepUtf8Writer(owner);
              }

              @Override
              public Object id() {
                return codegen.utf8WriterJITId(owner.type());
              }
            });
      }
      installed = utf8Writers.get(owner.type());
    }
    return (Utf8WriterCodec<T>) (installed == null ? owner : installed);
  }

  @SuppressWarnings("unchecked")
  public <T> Latin1ReaderCodec<T> latin1Reader(ObjectCodec<T> codec) {
    requireJITLock();
    ObjectCodec<Object> owner = erase(codec);
    Latin1ReaderCodec<Object> installed = latin1Readers.get(owner.type());
    if (installed == null && codegen != null) {
      if (!codegen.canCompileReader(owner)) {
        keepLatin1Reader(owner);
      } else {
        jitContext.registerJITCallback(
            () -> owner.getClass(),
            () -> codegen.compileLatin1Reader(owner),
            new JsonJITContext.JITCallback<Class<?>>() {
              @Override
              public void onSuccess(Class<?> generated) {
                publishLatin1Reader(owner, generated);
              }

              @Override
              public void onFailure(Throwable failure) {
                keepLatin1Reader(owner);
              }

              @Override
              public Object id() {
                return codegen.latin1ReaderJITId(owner.type());
              }
            });
      }
      installed = latin1Readers.get(owner.type());
    }
    return (Latin1ReaderCodec<T>) (installed == null ? owner : installed);
  }

  @SuppressWarnings("unchecked")
  public <T> Utf16ReaderCodec<T> utf16Reader(ObjectCodec<T> codec) {
    requireJITLock();
    ObjectCodec<Object> owner = erase(codec);
    Utf16ReaderCodec<Object> installed = utf16Readers.get(owner.type());
    if (installed == null && codegen != null) {
      if (!codegen.canCompileReader(owner)) {
        keepUtf16Reader(owner);
      } else {
        jitContext.registerJITCallback(
            () -> owner.getClass(),
            () -> codegen.compileUtf16Reader(owner),
            new JsonJITContext.JITCallback<Class<?>>() {
              @Override
              public void onSuccess(Class<?> generated) {
                publishUtf16Reader(owner, generated);
              }

              @Override
              public void onFailure(Throwable failure) {
                keepUtf16Reader(owner);
              }

              @Override
              public Object id() {
                return codegen.utf16ReaderJITId(owner.type());
              }
            });
      }
      installed = utf16Readers.get(owner.type());
    }
    return (Utf16ReaderCodec<T>) (installed == null ? owner : installed);
  }

  @SuppressWarnings("unchecked")
  public <T> Utf8ReaderCodec<T> utf8Reader(ObjectCodec<T> codec) {
    requireJITLock();
    ObjectCodec<Object> owner = erase(codec);
    Utf8ReaderCodec<Object> installed = utf8Readers.get(owner.type());
    if (installed == null && codegen != null) {
      if (!codegen.canCompileReader(owner)) {
        keepUtf8Reader(owner);
      } else {
        jitContext.registerJITCallback(
            () -> owner.getClass(),
            () -> codegen.compileUtf8Reader(owner),
            new JsonJITContext.JITCallback<Class<?>>() {
              @Override
              public void onSuccess(Class<?> generated) {
                publishUtf8Reader(owner, generated);
              }

              @Override
              public void onFailure(Throwable failure) {
                keepUtf8Reader(owner);
              }

              @Override
              public Object id() {
                return codegen.utf8ReaderJITId(owner.type());
              }
            });
      }
      installed = utf8Readers.get(owner.type());
    }
    return (Utf8ReaderCodec<T>) (installed == null ? owner : installed);
  }

  private void installStringWriter(Class<?> type, StringWriterCodec<Object> codec) {
    for (JsonTypeInfo typeInfo : typeInfos.values()) {
      if (matchesObjectType(typeInfo, type)) {
        typeInfo.setStringWriter(codec);
      }
    }
  }

  private void installUtf8Writer(Class<?> type, Utf8WriterCodec<Object> codec) {
    for (JsonTypeInfo typeInfo : typeInfos.values()) {
      if (matchesObjectType(typeInfo, type)) {
        typeInfo.setUtf8Writer(codec);
      }
    }
  }

  private void installLatin1Reader(Class<?> type, Latin1ReaderCodec<Object> codec) {
    for (JsonTypeInfo typeInfo : typeInfos.values()) {
      if (matchesObjectType(typeInfo, type)) {
        typeInfo.setLatin1Reader(codec);
      }
    }
  }

  private void installUtf16Reader(Class<?> type, Utf16ReaderCodec<Object> codec) {
    for (JsonTypeInfo typeInfo : typeInfos.values()) {
      if (matchesObjectType(typeInfo, type)) {
        typeInfo.setUtf16Reader(codec);
      }
    }
  }

  private void installUtf8Reader(Class<?> type, Utf8ReaderCodec<Object> codec) {
    for (JsonTypeInfo typeInfo : typeInfos.values()) {
      if (matchesObjectType(typeInfo, type)) {
        typeInfo.setUtf8Reader(codec);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private StringWriterCodec<Object> newStringWriter(ObjectCodec<?> owner, Class<?> generatedClass) {
    JsonFieldInfo[] fields = owner.writeFields();
    StringWriterCodec<Object>[] codecs =
        (StringWriterCodec<Object>[]) new StringWriterCodec<?>[fields.length];
    for (int i = 0; i < fields.length; i++) {
      JsonFieldInfo field = fields[i];
      if (JsonCodegen.usesWriteCodec(field)) {
        JsonTypeInfo typeInfo = field.writeTypeInfo();
        StringWriterCodec<Object> codec = typeInfo.stringWriter();
        if (JsonCodegen.writeNestedType(field) != null
            && typeInfo.rawType() != owner.type()
            && codec instanceof ObjectCodec) {
          codec = stringWriter((ObjectCodec<Object>) codec);
        }
        codecs[i] = codec;
      }
    }
    return instantiateStringWriter(generatedClass, fields, codecs);
  }

  @SuppressWarnings("unchecked")
  private Utf8WriterCodec<Object> newUtf8Writer(ObjectCodec<?> owner, Class<?> generatedClass) {
    JsonFieldInfo[] fields = owner.writeFields();
    Utf8WriterCodec<Object>[] codecs =
        (Utf8WriterCodec<Object>[]) new Utf8WriterCodec<?>[fields.length];
    for (int i = 0; i < fields.length; i++) {
      JsonFieldInfo field = fields[i];
      if (JsonCodegen.usesWriteCodec(field)) {
        JsonTypeInfo typeInfo = field.writeTypeInfo();
        Utf8WriterCodec<Object> codec = typeInfo.utf8Writer();
        if (JsonCodegen.writeNestedType(field) != null
            && typeInfo.rawType() != owner.type()
            && codec instanceof ObjectCodec) {
          codec = utf8Writer((ObjectCodec<Object>) codec);
        }
        codecs[i] = codec;
      }
    }
    return instantiateUtf8Writer(generatedClass, fields, codecs);
  }

  @SuppressWarnings("unchecked")
  private Latin1ReaderCodec<Object> newLatin1Reader(ObjectCodec<?> owner, Class<?> generatedClass) {
    JsonFieldInfo[] fields = owner.readFields();
    Latin1ReaderCodec<Object>[] codecs =
        (Latin1ReaderCodec<Object>[]) new Latin1ReaderCodec<?>[fields.length << 1];
    for (int i = 0; i < fields.length; i++) {
      JsonFieldInfo field = fields[i];
      JsonTypeInfo typeInfo = field.readTypeInfo();
      if (JsonCodegen.usesReadCodec(field)) {
        codecs[i] = typeInfo.latin1Reader();
      }
      Class<?> nestedType = JsonCodegen.readNestedType(field);
      if (nestedType != null && nestedType != owner.type()) {
        Latin1ReaderCodec<Object> codec = typeInfo.latin1Reader();
        codecs[fields.length + i] =
            codec instanceof ObjectCodec ? latin1Reader((ObjectCodec<Object>) codec) : codec;
      }
    }
    return instantiateLatin1Reader(generatedClass, owner, fields, codecs);
  }

  @SuppressWarnings("unchecked")
  private Utf16ReaderCodec<Object> newUtf16Reader(ObjectCodec<?> owner, Class<?> generatedClass) {
    JsonFieldInfo[] fields = owner.readFields();
    Utf16ReaderCodec<Object>[] codecs =
        (Utf16ReaderCodec<Object>[]) new Utf16ReaderCodec<?>[fields.length << 1];
    for (int i = 0; i < fields.length; i++) {
      JsonFieldInfo field = fields[i];
      JsonTypeInfo typeInfo = field.readTypeInfo();
      if (JsonCodegen.usesReadCodec(field)) {
        codecs[i] = typeInfo.utf16Reader();
      }
      Class<?> nestedType = JsonCodegen.readNestedType(field);
      if (nestedType != null && nestedType != owner.type()) {
        Utf16ReaderCodec<Object> codec = typeInfo.utf16Reader();
        codecs[fields.length + i] =
            codec instanceof ObjectCodec ? utf16Reader((ObjectCodec<Object>) codec) : codec;
      }
    }
    return instantiateUtf16Reader(generatedClass, owner, fields, codecs);
  }

  @SuppressWarnings("unchecked")
  private Utf8ReaderCodec<Object> newUtf8Reader(ObjectCodec<?> owner, Class<?> generatedClass) {
    JsonFieldInfo[] fields = owner.readFields();
    Utf8ReaderCodec<Object>[] codecs =
        (Utf8ReaderCodec<Object>[]) new Utf8ReaderCodec<?>[fields.length << 1];
    for (int i = 0; i < fields.length; i++) {
      JsonFieldInfo field = fields[i];
      JsonTypeInfo typeInfo = field.readTypeInfo();
      if (JsonCodegen.usesReadCodec(field)) {
        codecs[i] = typeInfo.utf8Reader();
      }
      Class<?> nestedType = JsonCodegen.readNestedType(field);
      if (nestedType != null && nestedType != owner.type()) {
        Utf8ReaderCodec<Object> codec = typeInfo.utf8Reader();
        codecs[fields.length + i] =
            codec instanceof ObjectCodec ? utf8Reader((ObjectCodec<Object>) codec) : codec;
      }
    }
    return instantiateUtf8Reader(generatedClass, owner, fields, codecs);
  }

  private Field[] writerChildFields(Object parent, ObjectCodec<?> owner) {
    Field[] childFields = null;
    JsonFieldInfo[] fields = owner.writeFields();
    for (int i = 0; i < fields.length; i++) {
      Class<?> nestedType = JsonCodegen.writeNestedType(fields[i]);
      if (nestedType != null && nestedType != owner.type()) {
        if (childFields == null) {
          childFields = new Field[fields.length];
        }
        childFields[i] = ReflectionUtils.getField(parent.getClass(), "w" + i);
      }
    }
    return childFields;
  }

  private Field[] readerChildFields(Object parent, ObjectCodec<?> owner) {
    Field[] childFields = null;
    JsonFieldInfo[] fields = owner.readFields();
    for (int i = 0; i < fields.length; i++) {
      Class<?> nestedType = JsonCodegen.readNestedType(fields[i]);
      if (nestedType != null && nestedType != owner.type()) {
        if (childFields == null) {
          childFields = new Field[fields.length];
        }
        childFields[i] = ReflectionUtils.getField(parent.getClass(), "o" + i);
      }
    }
    return childFields;
  }

  private void publishStringWriter(ObjectCodec<Object> owner, Class<?> generated) {
    requireJITLock();
    StringWriterCodec<Object> codec = newStringWriter(owner, generated);
    Field[] childFields = writerChildFields(codec, owner);
    registerStringWriterCallbacks(codec, owner, childFields);
    stringWriters.put(owner.type(), codec);
    installStringWriter(owner.type(), codec);
  }

  private void publishUtf8Writer(ObjectCodec<Object> owner, Class<?> generated) {
    requireJITLock();
    Utf8WriterCodec<Object> codec = newUtf8Writer(owner, generated);
    Field[] childFields = writerChildFields(codec, owner);
    registerUtf8WriterCallbacks(codec, owner, childFields);
    utf8Writers.put(owner.type(), codec);
    installUtf8Writer(owner.type(), codec);
  }

  private void publishLatin1Reader(ObjectCodec<Object> owner, Class<?> generated) {
    requireJITLock();
    Latin1ReaderCodec<Object> codec = newLatin1Reader(owner, generated);
    Field[] childFields = readerChildFields(codec, owner);
    registerLatin1ReaderCallbacks(codec, owner, childFields);
    latin1Readers.put(owner.type(), codec);
    installLatin1Reader(owner.type(), codec);
  }

  private void publishUtf16Reader(ObjectCodec<Object> owner, Class<?> generated) {
    requireJITLock();
    Utf16ReaderCodec<Object> codec = newUtf16Reader(owner, generated);
    Field[] childFields = readerChildFields(codec, owner);
    registerUtf16ReaderCallbacks(codec, owner, childFields);
    utf16Readers.put(owner.type(), codec);
    installUtf16Reader(owner.type(), codec);
  }

  private void publishUtf8Reader(ObjectCodec<Object> owner, Class<?> generated) {
    requireJITLock();
    Utf8ReaderCodec<Object> codec = newUtf8Reader(owner, generated);
    Field[] childFields = readerChildFields(codec, owner);
    registerUtf8ReaderCallbacks(codec, owner, childFields);
    utf8Readers.put(owner.type(), codec);
    installUtf8Reader(owner.type(), codec);
  }

  private void keepStringWriter(ObjectCodec<Object> owner) {
    requireJITLock();
    stringWriters.put(owner.type(), owner);
  }

  private void keepUtf8Writer(ObjectCodec<Object> owner) {
    requireJITLock();
    utf8Writers.put(owner.type(), owner);
  }

  private void keepLatin1Reader(ObjectCodec<Object> owner) {
    requireJITLock();
    latin1Readers.put(owner.type(), owner);
  }

  private void keepUtf16Reader(ObjectCodec<Object> owner) {
    requireJITLock();
    utf16Readers.put(owner.type(), owner);
  }

  private void keepUtf8Reader(ObjectCodec<Object> owner) {
    requireJITLock();
    utf8Readers.put(owner.type(), owner);
  }

  private void registerStringWriterCallbacks(
      StringWriterCodec<Object> parent, ObjectCodec<?> owner, Field[] fields) {
    if (fields == null) {
      return;
    }
    JsonFieldInfo[] properties = owner.writeFields();
    for (int i = 0; i < fields.length; i++) {
      Field field = fields[i];
      if (field != null) {
        JsonTypeInfo child = properties[i].writeTypeInfo();
        jitContext.registerJITNotifyCallback(
            codegen.stringWriterJITId(child.rawType()),
            new JsonJITContext.NotifyCallback() {
              @Override
              public void onNotifyResult(Object result) {
                StringWriterCodec<Object> codec = child.stringWriter();
                checkGeneratedClass(result, codec);
                ReflectionUtils.setObjectFieldValue(parent, field, codec);
              }

              @Override
              public void onNotifyMissed() {
                ReflectionUtils.setObjectFieldValue(parent, field, child.stringWriter());
              }
            });
      }
    }
  }

  private void registerUtf8WriterCallbacks(
      Utf8WriterCodec<Object> parent, ObjectCodec<?> owner, Field[] fields) {
    if (fields == null) {
      return;
    }
    JsonFieldInfo[] properties = owner.writeFields();
    for (int i = 0; i < fields.length; i++) {
      Field field = fields[i];
      if (field != null) {
        JsonTypeInfo child = properties[i].writeTypeInfo();
        jitContext.registerJITNotifyCallback(
            codegen.utf8WriterJITId(child.rawType()),
            new JsonJITContext.NotifyCallback() {
              @Override
              public void onNotifyResult(Object result) {
                Utf8WriterCodec<Object> codec = child.utf8Writer();
                checkGeneratedClass(result, codec);
                ReflectionUtils.setObjectFieldValue(parent, field, codec);
              }

              @Override
              public void onNotifyMissed() {
                ReflectionUtils.setObjectFieldValue(parent, field, child.utf8Writer());
              }
            });
      }
    }
  }

  private void registerLatin1ReaderCallbacks(
      Latin1ReaderCodec<Object> parent, ObjectCodec<?> owner, Field[] fields) {
    if (fields == null) {
      return;
    }
    JsonFieldInfo[] properties = owner.readFields();
    for (int i = 0; i < fields.length; i++) {
      Field field = fields[i];
      if (field != null) {
        JsonTypeInfo child = properties[i].readTypeInfo();
        jitContext.registerJITNotifyCallback(
            codegen.latin1ReaderJITId(child.rawType()),
            new JsonJITContext.NotifyCallback() {
              @Override
              public void onNotifyResult(Object result) {
                Latin1ReaderCodec<Object> codec = child.latin1Reader();
                checkGeneratedClass(result, codec);
                ReflectionUtils.setObjectFieldValue(parent, field, codec);
              }

              @Override
              public void onNotifyMissed() {
                ReflectionUtils.setObjectFieldValue(parent, field, child.latin1Reader());
              }
            });
      }
    }
  }

  private void registerUtf16ReaderCallbacks(
      Utf16ReaderCodec<Object> parent, ObjectCodec<?> owner, Field[] fields) {
    if (fields == null) {
      return;
    }
    JsonFieldInfo[] properties = owner.readFields();
    for (int i = 0; i < fields.length; i++) {
      Field field = fields[i];
      if (field != null) {
        JsonTypeInfo child = properties[i].readTypeInfo();
        jitContext.registerJITNotifyCallback(
            codegen.utf16ReaderJITId(child.rawType()),
            new JsonJITContext.NotifyCallback() {
              @Override
              public void onNotifyResult(Object result) {
                Utf16ReaderCodec<Object> codec = child.utf16Reader();
                checkGeneratedClass(result, codec);
                ReflectionUtils.setObjectFieldValue(parent, field, codec);
              }

              @Override
              public void onNotifyMissed() {
                ReflectionUtils.setObjectFieldValue(parent, field, child.utf16Reader());
              }
            });
      }
    }
  }

  private void registerUtf8ReaderCallbacks(
      Utf8ReaderCodec<Object> parent, ObjectCodec<?> owner, Field[] fields) {
    if (fields == null) {
      return;
    }
    JsonFieldInfo[] properties = owner.readFields();
    for (int i = 0; i < fields.length; i++) {
      Field field = fields[i];
      if (field != null) {
        JsonTypeInfo child = properties[i].readTypeInfo();
        jitContext.registerJITNotifyCallback(
            codegen.utf8ReaderJITId(child.rawType()),
            new JsonJITContext.NotifyCallback() {
              @Override
              public void onNotifyResult(Object result) {
                Utf8ReaderCodec<Object> codec = child.utf8Reader();
                checkGeneratedClass(result, codec);
                ReflectionUtils.setObjectFieldValue(parent, field, codec);
              }

              @Override
              public void onNotifyMissed() {
                ReflectionUtils.setObjectFieldValue(parent, field, child.utf8Reader());
              }
            });
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
    StringWriterCodec<Object> stringWriter = stringWriters.get(type);
    if (stringWriter != null) {
      typeInfo.setStringWriter(stringWriter);
    }
    Utf8WriterCodec<Object> utf8Writer = utf8Writers.get(type);
    if (utf8Writer != null) {
      typeInfo.setUtf8Writer(utf8Writer);
    }
    Latin1ReaderCodec<Object> latin1Reader = latin1Readers.get(type);
    if (latin1Reader != null) {
      typeInfo.setLatin1Reader(latin1Reader);
    }
    Utf16ReaderCodec<Object> utf16Reader = utf16Readers.get(type);
    if (utf16Reader != null) {
      typeInfo.setUtf16Reader(utf16Reader);
    }
    Utf8ReaderCodec<Object> utf8Reader = utf8Readers.get(type);
    if (utf8Reader != null) {
      typeInfo.setUtf8Reader(utf8Reader);
    }
  }

  private JsonTypeInfo newTypeInfo(
      Type type, TypeRef<?> typeRef, Class<?> rawType, JsonCodec<?> codec) {
    return new JsonTypeInfo(type, typeRef, rawType, sharedRegistry.kind(rawType), bindCodec(codec));
  }

  private static boolean matchesObjectType(JsonTypeInfo typeInfo, Class<?> type) {
    return typeInfo.usesDefaultObjectCodec() && typeInfo.rawType() == type;
  }

  private static void checkGeneratedClass(Object result, Object codec) {
    if (codec.getClass() != result) {
      throw new IllegalStateException(
          "Generated JSON callback does not match installed capability");
    }
  }

  @SuppressWarnings("unchecked")
  private static StringWriterCodec<Object> instantiateStringWriter(
      Class<?> type, JsonFieldInfo[] fields, StringWriterCodec<Object>[] codecs) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(JsonFieldInfo[].class, StringWriterCodec[].class);
        constructor.setAccessible(true);
        return (StringWriterCodec<Object>) constructor.newInstance(fields, codecs);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class, JsonFieldInfo[].class, StringWriterCodec[].class));
      return (StringWriterCodec<Object>) constructor.invoke(fields, codecs);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON String writer", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Utf8WriterCodec<Object> instantiateUtf8Writer(
      Class<?> type, JsonFieldInfo[] fields, Utf8WriterCodec<Object>[] codecs) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(JsonFieldInfo[].class, Utf8WriterCodec[].class);
        constructor.setAccessible(true);
        return (Utf8WriterCodec<Object>) constructor.newInstance(fields, codecs);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class, JsonFieldInfo[].class, Utf8WriterCodec[].class));
      return (Utf8WriterCodec<Object>) constructor.invoke(fields, codecs);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON UTF8 writer", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Latin1ReaderCodec<Object> instantiateLatin1Reader(
      Class<?> type,
      ObjectCodec<?> owner,
      JsonFieldInfo[] fields,
      Latin1ReaderCodec<Object>[] codecs) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(
                ObjectCodec.class, JsonFieldInfo[].class, Latin1ReaderCodec[].class);
        constructor.setAccessible(true);
        return (Latin1ReaderCodec<Object>) constructor.newInstance(owner, fields, codecs);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class,
                      ObjectCodec.class,
                      JsonFieldInfo[].class,
                      Latin1ReaderCodec[].class));
      return (Latin1ReaderCodec<Object>) constructor.invoke(owner, fields, codecs);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON Latin1 reader", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Utf16ReaderCodec<Object> instantiateUtf16Reader(
      Class<?> type,
      ObjectCodec<?> owner,
      JsonFieldInfo[] fields,
      Utf16ReaderCodec<Object>[] codecs) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(
                ObjectCodec.class, JsonFieldInfo[].class, Utf16ReaderCodec[].class);
        constructor.setAccessible(true);
        return (Utf16ReaderCodec<Object>) constructor.newInstance(owner, fields, codecs);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class,
                      ObjectCodec.class,
                      JsonFieldInfo[].class,
                      Utf16ReaderCodec[].class));
      return (Utf16ReaderCodec<Object>) constructor.invoke(owner, fields, codecs);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON UTF16 reader", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Utf8ReaderCodec<Object> instantiateUtf8Reader(
      Class<?> type,
      ObjectCodec<?> owner,
      JsonFieldInfo[] fields,
      Utf8ReaderCodec<Object>[] codecs) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(
                ObjectCodec.class, JsonFieldInfo[].class, Utf8ReaderCodec[].class);
        constructor.setAccessible(true);
        return (Utf8ReaderCodec<Object>) constructor.newInstance(owner, fields, codecs);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class,
                      ObjectCodec.class,
                      JsonFieldInfo[].class,
                      Utf8ReaderCodec[].class));
      return (Utf8ReaderCodec<Object>) constructor.invoke(owner, fields, codecs);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON UTF8 reader", e);
    }
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

  private void requireJITLock() {
    if (!jitContext.lockedByCurrentThread()) {
      throw new IllegalStateException("JSON resolver access requires the local JIT lock");
    }
  }

  @SuppressWarnings("unchecked")
  private static ObjectCodec<Object> erase(ObjectCodec<?> codec) {
    return (ObjectCodec<Object>) codec;
  }

  @SuppressWarnings("unchecked")
  private static JsonCodec<Object> bindCodec(JsonCodec<?> codec) {
    // The resolver has already matched the codec to this binding's declared type. JsonTypeInfo is
    // deliberately heterogeneous, so erase that proven relation once instead of casting in every
    // root, field, container, and generated hot call.
    return (JsonCodec<Object>) codec;
  }
}

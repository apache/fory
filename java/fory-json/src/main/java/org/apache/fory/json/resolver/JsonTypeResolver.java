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
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.apache.fory.annotation.Internal;
import org.apache.fory.collection.IdentityMap;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.ClosedSubtypeCodec;
import org.apache.fory.json.codec.CodecUtils;
import org.apache.fory.json.codec.JsonCodec;
import org.apache.fory.json.codec.JsonSubTypesInfo;
import org.apache.fory.json.codec.Latin1ReaderCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.ObjectCodec.AnyInfo;
import org.apache.fory.json.codec.StringObjectWriter;
import org.apache.fory.json.codec.StringWriterCodec;
import org.apache.fory.json.codec.Utf16ReaderCodec;
import org.apache.fory.json.codec.Utf8ObjectWriter;
import org.apache.fory.json.codec.Utf8ReaderCodec;
import org.apache.fory.json.codec.Utf8WriterCodec;
import org.apache.fory.json.codegen.JsonCodegen;
import org.apache.fory.json.codegen.JsonJITContext;
import org.apache.fory.json.meta.JsonCreatorFieldInfo;
import org.apache.fory.json.meta.JsonCreatorInfo;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldTable;
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
 * know any JSON capability, codec, generated class, or field metadata. Compilation failure leaves
 * the interpreted capability in its {@link JsonTypeInfo} slot; no parallel requested or failure
 * state is retained, so a later operation may retry compilation.
 *
 * <p>{@code typeInfos} owns declared and parameterized bindings. {@code objectCodecs} breaks
 * recursive object-metadata construction by publishing the complete object owner before resolving
 * its fields. {@code objectTypeInfos} contains only canonical raw-class default-object bindings and
 * is the direct publication index for generated capabilities; custom, parameterized, container,
 * scalar, and dynamic bindings never enter it.
 */
public final class JsonTypeResolver {
  private final Map<Object, ObjectCodec<?>> objectCodecs;
  private final Map<Object, JsonTypeInfo> typeInfos;
  private final JsonSharedRegistry sharedRegistry;
  private final JsonCodegen codegen;
  private final JsonJITContext jitContext;
  private final IdentityMap<Class<?>, JsonTypeInfo> objectTypeInfos;

  private enum RuntimeObjectKey {
    INSTANCE
  }

  public JsonTypeResolver(JsonSharedRegistry sharedRegistry) {
    this.sharedRegistry = sharedRegistry;
    objectCodecs = new HashMap<>();
    typeInfos = new HashMap<>();
    codegen = sharedRegistry.codegen();
    jitContext = sharedRegistry.newJITContext();
    objectTypeInfos = new IdentityMap<>();
  }

  @Internal
  public void lockJIT() {
    jitContext.lock();
  }

  @Internal
  public void unlockJIT() {
    jitContext.unlock();
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
    if (!sharedRegistry.hasCustomCodec(rawType)) {
      JsonSubTypesInfo definition = sharedRegistry.subTypesInfo(rawType);
      if (definition != null) {
        sharedRegistry.checkSecure(rawType);
        ClosedSubtypeCodec codec = new ClosedSubtypeCodec(rawType, definition);
        typeInfo = newTypeInfo(declaredType, rawType, codec);
        Set<Object> priorTypeKeys = new HashSet<>(typeInfos.keySet());
        Set<Object> priorObjectKeys = new HashSet<>(objectCodecs.keySet());
        // Closed graphs may recursively refer to their declared base through a subtype field or
        // container. Publish the complete dispatcher shell before resolving every finite branch;
        // failed tables are removed and can never leak a partially resolved binding.
        typeInfos.put(key, typeInfo);
        try {
          codec.resolve(this);
          return typeInfo;
        } catch (RuntimeException | Error e) {
          rollbackClosedMetadata(priorTypeKeys, priorObjectKeys);
          throw e;
        }
      }
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
    registerObjectTypeInfo(typeInfo);
    return typeInfo;
  }

  private void rollbackClosedMetadata(Set<Object> priorTypeKeys, Set<Object> priorObjectKeys) {
    // Branch metadata created during the failed transaction may point to the provisional closed
    // dispatcher through recursive fields. Remove only those new owners; metadata and active JIT
    // work that predated this closed-table resolution remain canonical and untouched.
    Iterator<Map.Entry<Object, JsonTypeInfo>> typeIterator = typeInfos.entrySet().iterator();
    while (typeIterator.hasNext()) {
      Map.Entry<Object, JsonTypeInfo> entry = typeIterator.next();
      if (!priorTypeKeys.contains(entry.getKey())) {
        JsonTypeInfo value = entry.getValue();
        if (objectTypeInfos.get(value.rawType()) == value) {
          objectTypeInfos.remove(value.rawType());
        }
        typeIterator.remove();
      }
    }
    Iterator<Object> objectIterator = objectCodecs.keySet().iterator();
    while (objectIterator.hasNext()) {
      if (!priorObjectKeys.contains(objectIterator.next())) {
        objectIterator.remove();
      }
    }
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
    registerObjectTypeInfo(typeInfo);
    return typeInfo;
  }

  public void checkSecure(Class<?> type) {
    sharedRegistry.checkSecure(type);
  }

  @SuppressWarnings("unchecked")
  public <T> StringWriterCodec<T> stringWriter(ObjectCodec<T> codec) {
    requireJITLock();
    ObjectCodec<Object> owner = erase(codec);
    JsonTypeInfo typeInfo = objectTypeInfos.get(owner.type());
    if (typeInfo == null) {
      return codec;
    }
    StringWriterCodec<Object> installed = typeInfo.stringWriter();
    if (installed == owner && codegen != null && codegen.canCompileWriter(owner)) {
      jitContext.registerJITCallback(
          () -> owner.getClass(),
          () -> codegen.compileStringWriter(owner),
          new JsonJITContext.JITCallback<Class<?>>() {
            @Override
            public void onSuccess(Class<?> generated) {
              publishStringWriter(owner, typeInfo, generated);
            }

            @Override
            public void onFailure(Throwable failure) {}

            @Override
            public Object id() {
              return codegen.stringWriterJITId(owner.type());
            }
          });
      installed = typeInfo.stringWriter();
    }
    return (StringWriterCodec<T>) installed;
  }

  @SuppressWarnings("unchecked")
  public <T> Utf8WriterCodec<T> utf8Writer(ObjectCodec<T> codec) {
    requireJITLock();
    ObjectCodec<Object> owner = erase(codec);
    JsonTypeInfo typeInfo = objectTypeInfos.get(owner.type());
    if (typeInfo == null) {
      return codec;
    }
    Utf8WriterCodec<Object> installed = typeInfo.utf8Writer();
    if (installed == owner && codegen != null && codegen.canCompileWriter(owner)) {
      jitContext.registerJITCallback(
          () -> owner.getClass(),
          () -> codegen.compileUtf8Writer(owner),
          new JsonJITContext.JITCallback<Class<?>>() {
            @Override
            public void onSuccess(Class<?> generated) {
              publishUtf8Writer(owner, typeInfo, generated);
            }

            @Override
            public void onFailure(Throwable failure) {}

            @Override
            public Object id() {
              return codegen.utf8WriterJITId(owner.type());
            }
          });
      installed = typeInfo.utf8Writer();
    }
    return (Utf8WriterCodec<T>) installed;
  }

  /**
   * Returns the current String member writer and requests JIT refinement when available.
   *
   * <p>The caller must hold this resolver's JIT lock.
   */
  @Internal
  @SuppressWarnings("unchecked")
  public <T> StringObjectWriter<T> stringObjectWriter(ObjectCodec<T> codec) {
    requireJITLock();
    ObjectCodec<Object> owner = erase(codec);
    JsonTypeInfo typeInfo = objectTypeInfos.get(owner.type());
    if (typeInfo == null) {
      return codec;
    }
    StringWriterCodec<Object> installed = typeInfo.stringWriter();
    if ((installed == owner || !(installed instanceof StringObjectWriter))
        && codegen != null
        && codegen.canCompileWriter(owner)) {
      jitContext.registerJITCallback(
          () -> owner.getClass(),
          () -> codegen.compileStringObjectWriter(owner),
          new JsonJITContext.JITCallback<Class<?>>() {
            @Override
            public void onSuccess(Class<?> generated) {
              publishStringWriter(owner, typeInfo, generated);
            }

            @Override
            public void onFailure(Throwable failure) {}

            @Override
            public Object id() {
              return codegen.stringObjectWriterJITId(owner.type());
            }
          });
      installed = typeInfo.stringWriter();
    }
    return installed instanceof StringObjectWriter ? (StringObjectWriter<T>) installed : codec;
  }

  /**
   * Returns the current UTF-8 member writer and requests JIT refinement when available.
   *
   * <p>The caller must hold this resolver's JIT lock.
   */
  @Internal
  @SuppressWarnings("unchecked")
  public <T> Utf8ObjectWriter<T> utf8ObjectWriter(ObjectCodec<T> codec) {
    requireJITLock();
    ObjectCodec<Object> owner = erase(codec);
    JsonTypeInfo typeInfo = objectTypeInfos.get(owner.type());
    if (typeInfo == null) {
      return codec;
    }
    Utf8WriterCodec<Object> installed = typeInfo.utf8Writer();
    if ((installed == owner || !(installed instanceof Utf8ObjectWriter))
        && codegen != null
        && codegen.canCompileWriter(owner)) {
      jitContext.registerJITCallback(
          () -> owner.getClass(),
          () -> codegen.compileUtf8ObjectWriter(owner),
          new JsonJITContext.JITCallback<Class<?>>() {
            @Override
            public void onSuccess(Class<?> generated) {
              publishUtf8Writer(owner, typeInfo, generated);
            }

            @Override
            public void onFailure(Throwable failure) {}

            @Override
            public Object id() {
              return codegen.utf8ObjectWriterJITId(owner.type());
            }
          });
      installed = typeInfo.utf8Writer();
    }
    return installed instanceof Utf8ObjectWriter ? (Utf8ObjectWriter<T>) installed : codec;
  }

  @SuppressWarnings("unchecked")
  public <T> Latin1ReaderCodec<T> latin1Reader(ObjectCodec<T> codec) {
    requireJITLock();
    ObjectCodec<Object> owner = erase(codec);
    JsonTypeInfo typeInfo = objectTypeInfos.get(owner.type());
    if (typeInfo == null) {
      return codec;
    }
    Latin1ReaderCodec<Object> installed = typeInfo.latin1Reader();
    if (installed == owner && codegen != null && codegen.canCompileReader(owner)) {
      jitContext.registerJITCallback(
          () -> owner.getClass(),
          () -> codegen.compileLatin1Reader(owner),
          new JsonJITContext.JITCallback<Class<?>>() {
            @Override
            public void onSuccess(Class<?> generated) {
              publishLatin1Reader(owner, typeInfo, generated);
            }

            @Override
            public void onFailure(Throwable failure) {}

            @Override
            public Object id() {
              return codegen.latin1ReaderJITId(owner.type());
            }
          });
      installed = typeInfo.latin1Reader();
    }
    return (Latin1ReaderCodec<T>) installed;
  }

  @SuppressWarnings("unchecked")
  public <T> Utf16ReaderCodec<T> utf16Reader(ObjectCodec<T> codec) {
    requireJITLock();
    ObjectCodec<Object> owner = erase(codec);
    JsonTypeInfo typeInfo = objectTypeInfos.get(owner.type());
    if (typeInfo == null) {
      return codec;
    }
    Utf16ReaderCodec<Object> installed = typeInfo.utf16Reader();
    if (installed == owner && codegen != null && codegen.canCompileReader(owner)) {
      jitContext.registerJITCallback(
          () -> owner.getClass(),
          () -> codegen.compileUtf16Reader(owner),
          new JsonJITContext.JITCallback<Class<?>>() {
            @Override
            public void onSuccess(Class<?> generated) {
              publishUtf16Reader(owner, typeInfo, generated);
            }

            @Override
            public void onFailure(Throwable failure) {}

            @Override
            public Object id() {
              return codegen.utf16ReaderJITId(owner.type());
            }
          });
      installed = typeInfo.utf16Reader();
    }
    return (Utf16ReaderCodec<T>) installed;
  }

  @SuppressWarnings("unchecked")
  public <T> Utf8ReaderCodec<T> utf8Reader(ObjectCodec<T> codec) {
    requireJITLock();
    ObjectCodec<Object> owner = erase(codec);
    JsonTypeInfo typeInfo = objectTypeInfos.get(owner.type());
    if (typeInfo == null) {
      return codec;
    }
    Utf8ReaderCodec<Object> installed = typeInfo.utf8Reader();
    if (installed == owner && codegen != null && codegen.canCompileReader(owner)) {
      jitContext.registerJITCallback(
          () -> owner.getClass(),
          () -> codegen.compileUtf8Reader(owner),
          new JsonJITContext.JITCallback<Class<?>>() {
            @Override
            public void onSuccess(Class<?> generated) {
              publishUtf8Reader(owner, typeInfo, generated);
            }

            @Override
            public void onFailure(Throwable failure) {}

            @Override
            public Object id() {
              return codegen.utf8ReaderJITId(owner.type());
            }
          });
      installed = typeInfo.utf8Reader();
    }
    return (Utf8ReaderCodec<T>) installed;
  }

  @Internal
  public void resolveInlineAnyReaders(
      ClosedSubtypeCodec parent, int index, ObjectCodec<?> codec, JsonFieldTable readTable) {
    requireJITLock();
    ObjectCodec<Object> owner = erase(codec);
    JsonTypeInfo typeInfo = objectTypeInfos.get(owner.type());
    if (typeInfo == null || codegen == null || !codegen.canCompileReader(owner)) {
      return;
    }
    resolveInlineLatin1Reader(parent, index, owner, typeInfo, readTable);
    resolveInlineUtf16Reader(parent, index, owner, typeInfo, readTable);
    resolveInlineUtf8Reader(parent, index, owner, typeInfo, readTable);
  }

  private void resolveInlineLatin1Reader(
      ClosedSubtypeCodec parent,
      int index,
      ObjectCodec<Object> owner,
      JsonTypeInfo typeInfo,
      JsonFieldTable readTable) {
    Latin1ReaderCodec<Object> current = latin1Reader(owner);
    if (current != owner) {
      parent.setInlineLatin1Reader(
          index, newInlineLatin1Reader(owner, current.getClass(), readTable, current));
      return;
    }
    jitContext.registerJITNotifyCallback(
        codegen.latin1ReaderJITId(owner.type()),
        new JsonJITContext.NotifyCallback() {
          @Override
          public void onNotifyResult(Object result) {
            Latin1ReaderCodec<Object> installed = typeInfo.latin1Reader();
            checkGeneratedClass(result, installed);
            parent.setInlineLatin1Reader(
                index, newInlineLatin1Reader(owner, (Class<?>) result, readTable, installed));
          }

          @Override
          public void onNotifyMissed() {
            Latin1ReaderCodec<Object> installed = typeInfo.latin1Reader();
            if (installed != owner) {
              parent.setInlineLatin1Reader(
                  index, newInlineLatin1Reader(owner, installed.getClass(), readTable, installed));
            }
          }
        });
  }

  private void resolveInlineUtf16Reader(
      ClosedSubtypeCodec parent,
      int index,
      ObjectCodec<Object> owner,
      JsonTypeInfo typeInfo,
      JsonFieldTable readTable) {
    Utf16ReaderCodec<Object> current = utf16Reader(owner);
    if (current != owner) {
      parent.setInlineUtf16Reader(
          index, newInlineUtf16Reader(owner, current.getClass(), readTable, current));
      return;
    }
    jitContext.registerJITNotifyCallback(
        codegen.utf16ReaderJITId(owner.type()),
        new JsonJITContext.NotifyCallback() {
          @Override
          public void onNotifyResult(Object result) {
            Utf16ReaderCodec<Object> installed = typeInfo.utf16Reader();
            checkGeneratedClass(result, installed);
            parent.setInlineUtf16Reader(
                index, newInlineUtf16Reader(owner, (Class<?>) result, readTable, installed));
          }

          @Override
          public void onNotifyMissed() {
            Utf16ReaderCodec<Object> installed = typeInfo.utf16Reader();
            if (installed != owner) {
              parent.setInlineUtf16Reader(
                  index, newInlineUtf16Reader(owner, installed.getClass(), readTable, installed));
            }
          }
        });
  }

  private void resolveInlineUtf8Reader(
      ClosedSubtypeCodec parent,
      int index,
      ObjectCodec<Object> owner,
      JsonTypeInfo typeInfo,
      JsonFieldTable readTable) {
    Utf8ReaderCodec<Object> current = utf8Reader(owner);
    if (current != owner) {
      parent.setInlineUtf8Reader(
          index, newInlineUtf8Reader(owner, current.getClass(), readTable, current));
      return;
    }
    jitContext.registerJITNotifyCallback(
        codegen.utf8ReaderJITId(owner.type()),
        new JsonJITContext.NotifyCallback() {
          @Override
          public void onNotifyResult(Object result) {
            Utf8ReaderCodec<Object> installed = typeInfo.utf8Reader();
            checkGeneratedClass(result, installed);
            parent.setInlineUtf8Reader(
                index, newInlineUtf8Reader(owner, (Class<?>) result, readTable, installed));
          }

          @Override
          public void onNotifyMissed() {
            Utf8ReaderCodec<Object> installed = typeInfo.utf8Reader();
            if (installed != owner) {
              parent.setInlineUtf8Reader(
                  index, newInlineUtf8Reader(owner, installed.getClass(), readTable, installed));
            }
          }
        });
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
    AnyInfo any = owner.anyInfo();
    if (any == null || any.writeField() == null && any.writeGetter() == null) {
      return instantiateStringWriter(generatedClass, fields, codecs);
    }
    if (!storesAnyCodec(owner, any)) {
      return instantiateAnyStringWriter(generatedClass, owner, fields, codecs);
    }
    StringWriterCodec<Object> anyCodec = any.valueTypeInfo().stringWriter();
    if (any.valueTypeInfo().usesDefaultObjectCodec()
        && any.valueRawType() != owner.type()
        && anyCodec instanceof ObjectCodec) {
      anyCodec = stringWriter((ObjectCodec<Object>) anyCodec);
    }
    return instantiateAnyStringWriter(generatedClass, owner, fields, codecs, anyCodec);
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
    AnyInfo any = owner.anyInfo();
    if (any == null || any.writeField() == null && any.writeGetter() == null) {
      return instantiateUtf8Writer(generatedClass, fields, codecs);
    }
    if (!storesAnyCodec(owner, any)) {
      return instantiateAnyUtf8Writer(generatedClass, owner, fields, codecs);
    }
    Utf8WriterCodec<Object> anyCodec = any.valueTypeInfo().utf8Writer();
    if (any.valueTypeInfo().usesDefaultObjectCodec()
        && any.valueRawType() != owner.type()
        && anyCodec instanceof ObjectCodec) {
      anyCodec = utf8Writer((ObjectCodec<Object>) anyCodec);
    }
    return instantiateAnyUtf8Writer(generatedClass, owner, fields, codecs, anyCodec);
  }

  @SuppressWarnings("unchecked")
  private Latin1ReaderCodec<Object> newLatin1Reader(ObjectCodec<?> owner, Class<?> generatedClass) {
    return newLatin1Reader(owner, generatedClass, owner.readTable());
  }

  @SuppressWarnings("unchecked")
  private Latin1ReaderCodec<Object> newLatin1Reader(
      ObjectCodec<?> owner, Class<?> generatedClass, JsonFieldTable readTable) {
    JsonFieldInfo[] fields = owner.readFields();
    JsonCreatorInfo creator = owner.creatorInfo();
    if (creator != null) {
      JsonCreatorFieldInfo[] creatorFields = creator.fields();
      Latin1ReaderCodec<Object>[] codecs =
          (Latin1ReaderCodec<Object>[]) new Latin1ReaderCodec<?>[creatorFields.length];
      for (int i = 0; i < creatorFields.length; i++) {
        codecs[i] = creatorFields[i].typeInfo().latin1Reader();
      }
      AnyInfo any = owner.anyInfo();
      if (any == null || any.readField() == null && any.readSetter() == null) {
        return instantiateLatin1Reader(generatedClass, owner, fields, codecs);
      }
      if (!storesAnyCodec(owner, any)) {
        return instantiateAnyLatin1Reader(generatedClass, owner, readTable, fields, codecs);
      }
      Latin1ReaderCodec<Object> anyCodec = any.valueTypeInfo().latin1Reader();
      if (any.valueTypeInfo().usesDefaultObjectCodec()
          && any.valueRawType() != owner.type()
          && anyCodec instanceof ObjectCodec) {
        anyCodec = latin1Reader((ObjectCodec<Object>) anyCodec);
      }
      return instantiateAnyLatin1Reader(generatedClass, owner, readTable, fields, codecs, anyCodec);
    }
    Latin1ReaderCodec<Object>[] codecs =
        (Latin1ReaderCodec<Object>[]) new Latin1ReaderCodec<?>[fields.length];
    for (int i = 0; i < fields.length; i++) {
      JsonFieldInfo field = fields[i];
      JsonTypeInfo typeInfo = field.readTypeInfo();
      if (JsonCodegen.usesReadCodec(field)) {
        codecs[i] = typeInfo.latin1Reader();
      } else if (JsonCodegen.readNestedType(field) != null && field.readRawType() != owner.type()) {
        Latin1ReaderCodec<Object> codec = typeInfo.latin1Reader();
        codecs[i] =
            codec instanceof ObjectCodec ? latin1Reader((ObjectCodec<Object>) codec) : codec;
      }
    }
    AnyInfo any = owner.anyInfo();
    if (any == null || any.readField() == null && any.readSetter() == null) {
      return instantiateLatin1Reader(generatedClass, owner, fields, codecs);
    }
    if (!storesAnyCodec(owner, any)) {
      return instantiateAnyLatin1Reader(generatedClass, owner, readTable, fields, codecs);
    }
    Latin1ReaderCodec<Object> anyCodec = any.valueTypeInfo().latin1Reader();
    if (any.valueTypeInfo().usesDefaultObjectCodec()
        && any.valueRawType() != owner.type()
        && anyCodec instanceof ObjectCodec) {
      anyCodec = latin1Reader((ObjectCodec<Object>) anyCodec);
    }
    return instantiateAnyLatin1Reader(generatedClass, owner, readTable, fields, codecs, anyCodec);
  }

  @SuppressWarnings("unchecked")
  private Utf16ReaderCodec<Object> newUtf16Reader(ObjectCodec<?> owner, Class<?> generatedClass) {
    return newUtf16Reader(owner, generatedClass, owner.readTable());
  }

  @SuppressWarnings("unchecked")
  private Utf16ReaderCodec<Object> newUtf16Reader(
      ObjectCodec<?> owner, Class<?> generatedClass, JsonFieldTable readTable) {
    JsonFieldInfo[] fields = owner.readFields();
    JsonCreatorInfo creator = owner.creatorInfo();
    if (creator != null) {
      JsonCreatorFieldInfo[] creatorFields = creator.fields();
      Utf16ReaderCodec<Object>[] codecs =
          (Utf16ReaderCodec<Object>[]) new Utf16ReaderCodec<?>[creatorFields.length];
      for (int i = 0; i < creatorFields.length; i++) {
        codecs[i] = creatorFields[i].typeInfo().utf16Reader();
      }
      AnyInfo any = owner.anyInfo();
      if (any == null || any.readField() == null && any.readSetter() == null) {
        return instantiateUtf16Reader(generatedClass, owner, fields, codecs);
      }
      if (!storesAnyCodec(owner, any)) {
        return instantiateAnyUtf16Reader(generatedClass, owner, readTable, fields, codecs);
      }
      Utf16ReaderCodec<Object> anyCodec = any.valueTypeInfo().utf16Reader();
      if (any.valueTypeInfo().usesDefaultObjectCodec()
          && any.valueRawType() != owner.type()
          && anyCodec instanceof ObjectCodec) {
        anyCodec = utf16Reader((ObjectCodec<Object>) anyCodec);
      }
      return instantiateAnyUtf16Reader(generatedClass, owner, readTable, fields, codecs, anyCodec);
    }
    Utf16ReaderCodec<Object>[] codecs =
        (Utf16ReaderCodec<Object>[]) new Utf16ReaderCodec<?>[fields.length];
    for (int i = 0; i < fields.length; i++) {
      JsonFieldInfo field = fields[i];
      JsonTypeInfo typeInfo = field.readTypeInfo();
      if (JsonCodegen.usesReadCodec(field)) {
        codecs[i] = typeInfo.utf16Reader();
      } else if (JsonCodegen.readNestedType(field) != null && field.readRawType() != owner.type()) {
        Utf16ReaderCodec<Object> codec = typeInfo.utf16Reader();
        codecs[i] = codec instanceof ObjectCodec ? utf16Reader((ObjectCodec<Object>) codec) : codec;
      }
    }
    AnyInfo any = owner.anyInfo();
    if (any == null || any.readField() == null && any.readSetter() == null) {
      return instantiateUtf16Reader(generatedClass, owner, fields, codecs);
    }
    if (!storesAnyCodec(owner, any)) {
      return instantiateAnyUtf16Reader(generatedClass, owner, readTable, fields, codecs);
    }
    Utf16ReaderCodec<Object> anyCodec = any.valueTypeInfo().utf16Reader();
    if (any.valueTypeInfo().usesDefaultObjectCodec()
        && any.valueRawType() != owner.type()
        && anyCodec instanceof ObjectCodec) {
      anyCodec = utf16Reader((ObjectCodec<Object>) anyCodec);
    }
    return instantiateAnyUtf16Reader(generatedClass, owner, readTable, fields, codecs, anyCodec);
  }

  @SuppressWarnings("unchecked")
  private Utf8ReaderCodec<Object> newUtf8Reader(ObjectCodec<?> owner, Class<?> generatedClass) {
    return newUtf8Reader(owner, generatedClass, owner.readTable());
  }

  @SuppressWarnings("unchecked")
  private Utf8ReaderCodec<Object> newUtf8Reader(
      ObjectCodec<?> owner, Class<?> generatedClass, JsonFieldTable readTable) {
    JsonFieldInfo[] fields = owner.readFields();
    JsonCreatorInfo creator = owner.creatorInfo();
    if (creator != null) {
      JsonCreatorFieldInfo[] creatorFields = creator.fields();
      Utf8ReaderCodec<Object>[] codecs =
          (Utf8ReaderCodec<Object>[]) new Utf8ReaderCodec<?>[creatorFields.length];
      for (int i = 0; i < creatorFields.length; i++) {
        codecs[i] = creatorFields[i].typeInfo().utf8Reader();
      }
      AnyInfo any = owner.anyInfo();
      if (any == null || any.readField() == null && any.readSetter() == null) {
        return instantiateUtf8Reader(generatedClass, owner, fields, codecs);
      }
      if (!storesAnyCodec(owner, any)) {
        return instantiateAnyUtf8Reader(generatedClass, owner, readTable, fields, codecs);
      }
      Utf8ReaderCodec<Object> anyCodec = any.valueTypeInfo().utf8Reader();
      if (any.valueTypeInfo().usesDefaultObjectCodec()
          && any.valueRawType() != owner.type()
          && anyCodec instanceof ObjectCodec) {
        anyCodec = utf8Reader((ObjectCodec<Object>) anyCodec);
      }
      return instantiateAnyUtf8Reader(generatedClass, owner, readTable, fields, codecs, anyCodec);
    }
    Utf8ReaderCodec<Object>[] codecs =
        (Utf8ReaderCodec<Object>[]) new Utf8ReaderCodec<?>[fields.length];
    for (int i = 0; i < fields.length; i++) {
      JsonFieldInfo field = fields[i];
      JsonTypeInfo typeInfo = field.readTypeInfo();
      if (JsonCodegen.usesReadCodec(field)) {
        codecs[i] = typeInfo.utf8Reader();
      } else if (JsonCodegen.readNestedType(field) != null && field.readRawType() != owner.type()) {
        Utf8ReaderCodec<Object> codec = typeInfo.utf8Reader();
        codecs[i] = codec instanceof ObjectCodec ? utf8Reader((ObjectCodec<Object>) codec) : codec;
      }
    }
    AnyInfo any = owner.anyInfo();
    if (any == null || any.readField() == null && any.readSetter() == null) {
      return instantiateUtf8Reader(generatedClass, owner, fields, codecs);
    }
    if (!storesAnyCodec(owner, any)) {
      return instantiateAnyUtf8Reader(generatedClass, owner, readTable, fields, codecs);
    }
    Utf8ReaderCodec<Object> anyCodec = any.valueTypeInfo().utf8Reader();
    if (any.valueTypeInfo().usesDefaultObjectCodec()
        && any.valueRawType() != owner.type()
        && anyCodec instanceof ObjectCodec) {
      anyCodec = utf8Reader((ObjectCodec<Object>) anyCodec);
    }
    return instantiateAnyUtf8Reader(generatedClass, owner, readTable, fields, codecs, anyCodec);
  }

  private Latin1ReaderCodec<Object> newInlineLatin1Reader(
      ObjectCodec<Object> owner,
      Class<?> generatedClass,
      JsonFieldTable readTable,
      Latin1ReaderCodec<Object> ordinaryReader) {
    Latin1ReaderCodec<Object> codec = newLatin1Reader(owner, generatedClass, readTable);
    setInlineSelfReader(owner, codec, ordinaryReader);
    Field[] childFields = readerChildFields(codec, owner);
    registerLatin1ReaderCallbacks(codec, owner, childFields);
    registerLatin1AnyReaderCallback(codec, owner);
    return codec;
  }

  private Utf16ReaderCodec<Object> newInlineUtf16Reader(
      ObjectCodec<Object> owner,
      Class<?> generatedClass,
      JsonFieldTable readTable,
      Utf16ReaderCodec<Object> ordinaryReader) {
    Utf16ReaderCodec<Object> codec = newUtf16Reader(owner, generatedClass, readTable);
    setInlineSelfReader(owner, codec, ordinaryReader);
    Field[] childFields = readerChildFields(codec, owner);
    registerUtf16ReaderCallbacks(codec, owner, childFields);
    registerUtf16AnyReaderCallback(codec, owner);
    return codec;
  }

  private Utf8ReaderCodec<Object> newInlineUtf8Reader(
      ObjectCodec<Object> owner,
      Class<?> generatedClass,
      JsonFieldTable readTable,
      Utf8ReaderCodec<Object> ordinaryReader) {
    Utf8ReaderCodec<Object> codec = newUtf8Reader(owner, generatedClass, readTable);
    setInlineSelfReader(owner, codec, ordinaryReader);
    Field[] childFields = readerChildFields(codec, owner);
    registerUtf8ReaderCallbacks(codec, owner, childFields);
    registerUtf8AnyReaderCallback(codec, owner);
    return codec;
  }

  private static <T> void setInlineSelfReader(
      ObjectCodec<?> owner, T inlineReader, T ordinaryReader) {
    if (JsonCodegen.storesSelfReader(owner)) {
      Field field = ReflectionUtils.getField(inlineReader.getClass(), "selfReader");
      ReflectionUtils.setObjectFieldValue(inlineReader, field, ordinaryReader);
    }
  }

  private static boolean storesAnyCodec(ObjectCodec<?> owner, AnyInfo any) {
    return !any.valueTypeInfo().usesDefaultObjectCodec() || any.valueRawType() != owner.type();
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
    JsonCreatorInfo creator = owner.creatorInfo();
    if (creator != null) {
      JsonCreatorFieldInfo[] fields = creator.fields();
      for (int i = 0; i < fields.length; i++) {
        JsonTypeInfo child = fields[i].typeInfo();
        if (child.usesDefaultObjectCodec() && child.rawType() != owner.type()) {
          if (childFields == null) {
            childFields = new Field[fields.length];
          }
          childFields[i] = ReflectionUtils.getField(parent.getClass(), "r" + i);
        }
      }
      return childFields;
    }
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

  // Publication runs under the local JIT lock: construct the resolver-local instance, resolve
  // every replaceable child Field, register child notifications, then write the canonical
  // JsonTypeInfo slot captured when the compilation request is created. Capturing that exact slot
  // is required because a failed closed-subtype transaction can remove its provisional metadata
  // and later build a new canonical slot for the same class before an old async task completes.
  // The old task may refine only its now-unreachable slot; a type lookup here would let it corrupt
  // the replacement generation. Construction and field lookup are the fallible phase. Publication
  // is deterministic ordinary field assignment and is never modeled as a transaction or rolled
  // back; a failure there is a generated-code invariant violation.
  private void publishStringWriter(
      ObjectCodec<Object> owner, JsonTypeInfo typeInfo, Class<?> generated) {
    requireJITLock();
    StringWriterCodec<Object> current = typeInfo.stringWriter();
    // Inline subtype member writers refine the complete-writer capability in the same canonical
    // slot. A concurrently compiled complete writer must never downgrade that refinement when its
    // callback finishes later. The interpreted owner also implements member writing, so identity
    // distinguishes it from an installed generated refinement.
    if (!StringObjectWriter.class.isAssignableFrom(generated)
        && current != owner
        && current instanceof StringObjectWriter) {
      return;
    }
    StringWriterCodec<Object> codec = newStringWriter(owner, generated);
    Field[] childFields = writerChildFields(codec, owner);
    registerStringWriterCallbacks(codec, owner, childFields);
    registerStringAnyWriterCallback(codec, owner);
    typeInfo.setStringWriter(codec);
  }

  private void publishUtf8Writer(
      ObjectCodec<Object> owner, JsonTypeInfo typeInfo, Class<?> generated) {
    requireJITLock();
    Utf8WriterCodec<Object> current = typeInfo.utf8Writer();
    if (!Utf8ObjectWriter.class.isAssignableFrom(generated)
        && current != owner
        && current instanceof Utf8ObjectWriter) {
      return;
    }
    Utf8WriterCodec<Object> codec = newUtf8Writer(owner, generated);
    Field[] childFields = writerChildFields(codec, owner);
    registerUtf8WriterCallbacks(codec, owner, childFields);
    registerUtf8AnyWriterCallback(codec, owner);
    typeInfo.setUtf8Writer(codec);
  }

  private void publishLatin1Reader(
      ObjectCodec<Object> owner, JsonTypeInfo typeInfo, Class<?> generated) {
    requireJITLock();
    Latin1ReaderCodec<Object> codec = newLatin1Reader(owner, generated);
    Field[] childFields = readerChildFields(codec, owner);
    registerLatin1ReaderCallbacks(codec, owner, childFields);
    registerLatin1AnyReaderCallback(codec, owner);
    typeInfo.setLatin1Reader(codec);
  }

  private void publishUtf16Reader(
      ObjectCodec<Object> owner, JsonTypeInfo typeInfo, Class<?> generated) {
    requireJITLock();
    Utf16ReaderCodec<Object> codec = newUtf16Reader(owner, generated);
    Field[] childFields = readerChildFields(codec, owner);
    registerUtf16ReaderCallbacks(codec, owner, childFields);
    registerUtf16AnyReaderCallback(codec, owner);
    typeInfo.setUtf16Reader(codec);
  }

  private void publishUtf8Reader(
      ObjectCodec<Object> owner, JsonTypeInfo typeInfo, Class<?> generated) {
    requireJITLock();
    Utf8ReaderCodec<Object> codec = newUtf8Reader(owner, generated);
    Field[] childFields = readerChildFields(codec, owner);
    registerUtf8ReaderCallbacks(codec, owner, childFields);
    registerUtf8AnyReaderCallback(codec, owner);
    typeInfo.setUtf8Reader(codec);
  }

  // A generated parent captures the current child slot during construction. If a child task is
  // active, notification updates only the matching concrete field after the child slot is
  // published. If no task is active, onNotifyMissed installs the already-current slot immediately.
  // The callback list is notification state, not a resolver dependency graph or task-dedup map.
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
                checkGeneratedWriter(result, codec, StringObjectWriter.class);
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
                checkGeneratedWriter(result, codec, Utf8ObjectWriter.class);
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
    for (int i = 0; i < fields.length; i++) {
      Field field = fields[i];
      if (field != null) {
        JsonTypeInfo child = readerChildTypeInfo(owner, i);
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
    for (int i = 0; i < fields.length; i++) {
      Field field = fields[i];
      if (field != null) {
        JsonTypeInfo child = readerChildTypeInfo(owner, i);
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
    for (int i = 0; i < fields.length; i++) {
      Field field = fields[i];
      if (field != null) {
        JsonTypeInfo child = readerChildTypeInfo(owner, i);
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

  private void registerStringAnyWriterCallback(
      StringWriterCodec<Object> parent, ObjectCodec<?> owner) {
    AnyInfo any = owner.anyInfo();
    if (!hasGeneratedAnyWriteChild(owner, any)) {
      return;
    }
    Field field = ReflectionUtils.getField(parent.getClass(), "anyWriter");
    JsonTypeInfo child = any.valueTypeInfo();
    jitContext.registerJITNotifyCallback(
        codegen.stringWriterJITId(child.rawType()),
        new JsonJITContext.NotifyCallback() {
          @Override
          public void onNotifyResult(Object result) {
            StringWriterCodec<Object> codec = child.stringWriter();
            checkGeneratedWriter(result, codec, StringObjectWriter.class);
            ReflectionUtils.setObjectFieldValue(parent, field, codec);
          }

          @Override
          public void onNotifyMissed() {
            ReflectionUtils.setObjectFieldValue(parent, field, child.stringWriter());
          }
        });
  }

  private void registerUtf8AnyWriterCallback(Utf8WriterCodec<Object> parent, ObjectCodec<?> owner) {
    AnyInfo any = owner.anyInfo();
    if (!hasGeneratedAnyWriteChild(owner, any)) {
      return;
    }
    Field field = ReflectionUtils.getField(parent.getClass(), "anyWriter");
    JsonTypeInfo child = any.valueTypeInfo();
    jitContext.registerJITNotifyCallback(
        codegen.utf8WriterJITId(child.rawType()),
        new JsonJITContext.NotifyCallback() {
          @Override
          public void onNotifyResult(Object result) {
            Utf8WriterCodec<Object> codec = child.utf8Writer();
            checkGeneratedWriter(result, codec, Utf8ObjectWriter.class);
            ReflectionUtils.setObjectFieldValue(parent, field, codec);
          }

          @Override
          public void onNotifyMissed() {
            ReflectionUtils.setObjectFieldValue(parent, field, child.utf8Writer());
          }
        });
  }

  private void registerLatin1AnyReaderCallback(
      Latin1ReaderCodec<Object> parent, ObjectCodec<?> owner) {
    AnyInfo any = owner.anyInfo();
    if (!hasGeneratedAnyReadChild(owner, any)) {
      return;
    }
    Field field = ReflectionUtils.getField(parent.getClass(), "anyReader");
    JsonTypeInfo child = any.valueTypeInfo();
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

  private void registerUtf16AnyReaderCallback(
      Utf16ReaderCodec<Object> parent, ObjectCodec<?> owner) {
    AnyInfo any = owner.anyInfo();
    if (!hasGeneratedAnyReadChild(owner, any)) {
      return;
    }
    Field field = ReflectionUtils.getField(parent.getClass(), "anyReader");
    JsonTypeInfo child = any.valueTypeInfo();
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

  private void registerUtf8AnyReaderCallback(Utf8ReaderCodec<Object> parent, ObjectCodec<?> owner) {
    AnyInfo any = owner.anyInfo();
    if (!hasGeneratedAnyReadChild(owner, any)) {
      return;
    }
    Field field = ReflectionUtils.getField(parent.getClass(), "anyReader");
    JsonTypeInfo child = any.valueTypeInfo();
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

  private static boolean hasGeneratedAnyWriteChild(ObjectCodec<?> owner, AnyInfo any) {
    return any != null
        && (any.writeField() != null || any.writeGetter() != null)
        && any.valueTypeInfo().usesDefaultObjectCodec()
        && any.valueRawType() != owner.type();
  }

  private static boolean hasGeneratedAnyReadChild(ObjectCodec<?> owner, AnyInfo any) {
    return any != null
        && (any.readField() != null || any.readSetter() != null)
        && any.valueTypeInfo().usesDefaultObjectCodec()
        && any.valueRawType() != owner.type();
  }

  private static JsonTypeInfo readerChildTypeInfo(ObjectCodec<?> owner, int index) {
    JsonCreatorInfo creator = owner.creatorInfo();
    return creator == null
        ? owner.readFields()[index].readTypeInfo()
        : creator.fields()[index].typeInfo();
  }

  @SuppressWarnings("unchecked")
  private <T> ObjectCodec<T> buildObjectCodec(TypeRef<T> ownerType, Object key) {
    ObjectCodec<?> cached = objectCodecs.get(key);
    if (cached != null) {
      return (ObjectCodec<T>) cached;
    }
    Class<?> type = ownerType.getRawType();
    sharedRegistry.checkSecure(type);
    ObjectCodec<T> codec =
        ObjectCodec.build(
            ownerType,
            sharedRegistry.propertyDiscoveryEnabled(),
            sharedRegistry.propertyNamingStrategy(),
            sharedRegistry.writeNullFields());
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
    return newTypeInfo(declaredType, rawType, codec);
  }

  private JsonTypeInfo buildRuntimeTypeInfo(Class<?> rawType) {
    sharedRegistry.checkSecure(rawType);
    TypeRef<?> typeRef = TypeRef.of(rawType);
    JsonCodec<?> codec =
        rawType == Object.class
            ? getObjectCodec(typeRef)
            : sharedRegistry.createCodec(rawType, typeRef, this);
    if (codec == null) {
      codec = getObjectCodec(typeRef);
    }
    return newTypeInfo(rawType, rawType, codec);
  }

  private JsonTypeInfo newTypeInfo(Type type, Class<?> rawType, JsonCodec<?> codec) {
    return new JsonTypeInfo(type, rawType, sharedRegistry.kind(rawType), bindCodec(codec));
  }

  private void registerObjectTypeInfo(JsonTypeInfo typeInfo) {
    if (typeInfo.usesDefaultObjectCodec()
        && typeInfo.type() instanceof Class
        && typeInfo.rawType() != Object.class) {
      objectTypeInfos.put(typeInfo.rawType(), typeInfo);
    }
  }

  private static void checkGeneratedClass(Object result, Object codec) {
    if (codec.getClass() != result) {
      throw new IllegalStateException(
          "Generated JSON callback does not match installed capability");
    }
  }

  private static void checkGeneratedWriter(Object result, Object codec, Class<?> refinement) {
    if (codec.getClass() != result && !refinement.isInstance(codec)) {
      throw new IllegalStateException(
          "Generated JSON callback does not match installed writer capability");
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
  private static StringWriterCodec<Object> instantiateAnyStringWriter(
      Class<?> type,
      ObjectCodec<?> owner,
      JsonFieldInfo[] fields,
      StringWriterCodec<Object>[] codecs) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(
                ObjectCodec.class, JsonFieldInfo[].class, StringWriterCodec[].class);
        constructor.setAccessible(true);
        return (StringWriterCodec<Object>) constructor.newInstance(owner, fields, codecs);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class,
                      ObjectCodec.class,
                      JsonFieldInfo[].class,
                      StringWriterCodec[].class));
      return (StringWriterCodec<Object>) constructor.invoke(owner, fields, codecs);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON Any String writer", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static StringWriterCodec<Object> instantiateAnyStringWriter(
      Class<?> type,
      ObjectCodec<?> owner,
      JsonFieldInfo[] fields,
      StringWriterCodec<Object>[] codecs,
      StringWriterCodec<Object> anyCodec) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(
                ObjectCodec.class,
                JsonFieldInfo[].class,
                StringWriterCodec[].class,
                StringWriterCodec.class);
        constructor.setAccessible(true);
        return (StringWriterCodec<Object>) constructor.newInstance(owner, fields, codecs, anyCodec);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class,
                      ObjectCodec.class,
                      JsonFieldInfo[].class,
                      StringWriterCodec[].class,
                      StringWriterCodec.class));
      return (StringWriterCodec<Object>) constructor.invoke(owner, fields, codecs, anyCodec);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON Any String writer", e);
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
  private static Utf8WriterCodec<Object> instantiateAnyUtf8Writer(
      Class<?> type,
      ObjectCodec<?> owner,
      JsonFieldInfo[] fields,
      Utf8WriterCodec<Object>[] codecs) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(
                ObjectCodec.class, JsonFieldInfo[].class, Utf8WriterCodec[].class);
        constructor.setAccessible(true);
        return (Utf8WriterCodec<Object>) constructor.newInstance(owner, fields, codecs);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class,
                      ObjectCodec.class,
                      JsonFieldInfo[].class,
                      Utf8WriterCodec[].class));
      return (Utf8WriterCodec<Object>) constructor.invoke(owner, fields, codecs);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON Any UTF8 writer", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Utf8WriterCodec<Object> instantiateAnyUtf8Writer(
      Class<?> type,
      ObjectCodec<?> owner,
      JsonFieldInfo[] fields,
      Utf8WriterCodec<Object>[] codecs,
      Utf8WriterCodec<Object> anyCodec) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(
                ObjectCodec.class,
                JsonFieldInfo[].class,
                Utf8WriterCodec[].class,
                Utf8WriterCodec.class);
        constructor.setAccessible(true);
        return (Utf8WriterCodec<Object>) constructor.newInstance(owner, fields, codecs, anyCodec);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class,
                      ObjectCodec.class,
                      JsonFieldInfo[].class,
                      Utf8WriterCodec[].class,
                      Utf8WriterCodec.class));
      return (Utf8WriterCodec<Object>) constructor.invoke(owner, fields, codecs, anyCodec);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON Any UTF8 writer", e);
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
  private static Latin1ReaderCodec<Object> instantiateAnyLatin1Reader(
      Class<?> type,
      ObjectCodec<?> owner,
      JsonFieldTable readTable,
      JsonFieldInfo[] fields,
      Latin1ReaderCodec<Object>[] codecs) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(
                ObjectCodec.class,
                JsonFieldTable.class,
                JsonFieldInfo[].class,
                Latin1ReaderCodec[].class);
        constructor.setAccessible(true);
        return (Latin1ReaderCodec<Object>)
            constructor.newInstance(owner, readTable, fields, codecs);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class,
                      ObjectCodec.class,
                      JsonFieldTable.class,
                      JsonFieldInfo[].class,
                      Latin1ReaderCodec[].class));
      return (Latin1ReaderCodec<Object>) constructor.invoke(owner, readTable, fields, codecs);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON Any Latin1 reader", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Latin1ReaderCodec<Object> instantiateAnyLatin1Reader(
      Class<?> type,
      ObjectCodec<?> owner,
      JsonFieldTable readTable,
      JsonFieldInfo[] fields,
      Latin1ReaderCodec<Object>[] codecs,
      Latin1ReaderCodec<Object> anyCodec) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(
                ObjectCodec.class,
                JsonFieldTable.class,
                JsonFieldInfo[].class,
                Latin1ReaderCodec[].class,
                Latin1ReaderCodec.class);
        constructor.setAccessible(true);
        return (Latin1ReaderCodec<Object>)
            constructor.newInstance(owner, readTable, fields, codecs, anyCodec);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class,
                      ObjectCodec.class,
                      JsonFieldTable.class,
                      JsonFieldInfo[].class,
                      Latin1ReaderCodec[].class,
                      Latin1ReaderCodec.class));
      return (Latin1ReaderCodec<Object>)
          constructor.invoke(owner, readTable, fields, codecs, anyCodec);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON Any Latin1 reader", e);
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
  private static Utf16ReaderCodec<Object> instantiateAnyUtf16Reader(
      Class<?> type,
      ObjectCodec<?> owner,
      JsonFieldTable readTable,
      JsonFieldInfo[] fields,
      Utf16ReaderCodec<Object>[] codecs) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(
                ObjectCodec.class,
                JsonFieldTable.class,
                JsonFieldInfo[].class,
                Utf16ReaderCodec[].class);
        constructor.setAccessible(true);
        return (Utf16ReaderCodec<Object>) constructor.newInstance(owner, readTable, fields, codecs);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class,
                      ObjectCodec.class,
                      JsonFieldTable.class,
                      JsonFieldInfo[].class,
                      Utf16ReaderCodec[].class));
      return (Utf16ReaderCodec<Object>) constructor.invoke(owner, readTable, fields, codecs);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON Any UTF16 reader", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Utf16ReaderCodec<Object> instantiateAnyUtf16Reader(
      Class<?> type,
      ObjectCodec<?> owner,
      JsonFieldTable readTable,
      JsonFieldInfo[] fields,
      Utf16ReaderCodec<Object>[] codecs,
      Utf16ReaderCodec<Object> anyCodec) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(
                ObjectCodec.class,
                JsonFieldTable.class,
                JsonFieldInfo[].class,
                Utf16ReaderCodec[].class,
                Utf16ReaderCodec.class);
        constructor.setAccessible(true);
        return (Utf16ReaderCodec<Object>)
            constructor.newInstance(owner, readTable, fields, codecs, anyCodec);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class,
                      ObjectCodec.class,
                      JsonFieldTable.class,
                      JsonFieldInfo[].class,
                      Utf16ReaderCodec[].class,
                      Utf16ReaderCodec.class));
      return (Utf16ReaderCodec<Object>)
          constructor.invoke(owner, readTable, fields, codecs, anyCodec);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON Any UTF16 reader", e);
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

  @SuppressWarnings("unchecked")
  private static Utf8ReaderCodec<Object> instantiateAnyUtf8Reader(
      Class<?> type,
      ObjectCodec<?> owner,
      JsonFieldTable readTable,
      JsonFieldInfo[] fields,
      Utf8ReaderCodec<Object>[] codecs) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(
                ObjectCodec.class,
                JsonFieldTable.class,
                JsonFieldInfo[].class,
                Utf8ReaderCodec[].class);
        constructor.setAccessible(true);
        return (Utf8ReaderCodec<Object>) constructor.newInstance(owner, readTable, fields, codecs);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class,
                      ObjectCodec.class,
                      JsonFieldTable.class,
                      JsonFieldInfo[].class,
                      Utf8ReaderCodec[].class));
      return (Utf8ReaderCodec<Object>) constructor.invoke(owner, readTable, fields, codecs);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON Any UTF8 reader", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Utf8ReaderCodec<Object> instantiateAnyUtf8Reader(
      Class<?> type,
      ObjectCodec<?> owner,
      JsonFieldTable readTable,
      JsonFieldInfo[] fields,
      Utf8ReaderCodec<Object>[] codecs,
      Utf8ReaderCodec<Object> anyCodec) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(
                ObjectCodec.class,
                JsonFieldTable.class,
                JsonFieldInfo[].class,
                Utf8ReaderCodec[].class,
                Utf8ReaderCodec.class);
        constructor.setAccessible(true);
        return (Utf8ReaderCodec<Object>)
            constructor.newInstance(owner, readTable, fields, codecs, anyCodec);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class,
                      ObjectCodec.class,
                      JsonFieldTable.class,
                      JsonFieldInfo[].class,
                      Utf8ReaderCodec[].class,
                      Utf8ReaderCodec.class));
      return (Utf8ReaderCodec<Object>)
          constructor.invoke(owner, readTable, fields, codecs, anyCodec);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON Any UTF8 reader", e);
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

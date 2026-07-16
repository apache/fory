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

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.apache.fory.annotation.Internal;
import org.apache.fory.collection.IdentityMap;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.codec.ArrayCodec;
import org.apache.fory.json.codec.ClosedSubtypeCodec;
import org.apache.fory.json.codec.CodecUtils;
import org.apache.fory.json.codec.CollectionCodec;
import org.apache.fory.json.codec.GeneratedJsonCodec;
import org.apache.fory.json.codec.JsonSubTypesInfo;
import org.apache.fory.json.codec.JsonUnwrappedInfo;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.codec.Latin1ReaderCodec;
import org.apache.fory.json.codec.MapCodec;
import org.apache.fory.json.codec.MapKeyCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.ObjectCodec.AnyInfo;
import org.apache.fory.json.codec.ScalarCodecs;
import org.apache.fory.json.codec.StringWriterCodec;
import org.apache.fory.json.codec.Utf16ReaderCodec;
import org.apache.fory.json.codec.Utf8ReaderCodec;
import org.apache.fory.json.codec.Utf8WriterCodec;
import org.apache.fory.json.codegen.JsonCodegen;
import org.apache.fory.json.codegen.JsonJITContext;
import org.apache.fory.json.meta.JsonCreatorFieldInfo;
import org.apache.fory.json.meta.JsonCreatorInfo;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldKind;
import org.apache.fory.json.meta.JsonFieldTable;
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
 * its fields. {@code rawObjectTypeInfos} contains only canonical raw-class default-object bindings
 * and is the publication index for generated capabilities. {@code canonicalObjectTypeInfos} indexes
 * the same bindings by exact codec identity so custom and parameterized codecs never enter
 * raw-class JIT dispatch.
 */
public final class JsonTypeResolver {
  private final Map<Object, ObjectCodec<?>> objectCodecs;
  private final Map<Object, JsonTypeInfo> typeInfos;
  private final JsonSharedRegistry sharedRegistry;
  private final JsonCodegen codegen;
  private final JsonJITContext jitContext;
  private final IdentityMap<Class<?>, JsonTypeInfo> rawObjectTypeInfos;
  private final IdentityMap<ObjectCodec<?>, JsonTypeInfo> canonicalObjectTypeInfos;
  private int resolutionDepth;

  private enum RuntimeObjectKey {
    INSTANCE
  }

  public JsonTypeResolver(JsonSharedRegistry sharedRegistry) {
    this.sharedRegistry = sharedRegistry;
    objectCodecs = new HashMap<>();
    typeInfos = new HashMap<>();
    codegen = sharedRegistry.codegen();
    jitContext = sharedRegistry.newJITContext();
    rawObjectTypeInfos = new IdentityMap<>();
    canonicalObjectTypeInfos = new IdentityMap<>();
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
    return getObjectCodec(ownerType, key);
  }

  @SuppressWarnings("unchecked")
  private <T> ObjectCodec<T> getObjectCodec(TypeRef<T> ownerType, Object key) {
    ObjectCodec<?> codec = objectCodecs.get(key);
    if (codec != null) {
      return (ObjectCodec<T>) codec;
    }
    ResolutionSnapshot snapshot = beginResolution();
    try {
      return buildObjectCodec(ownerType, key);
    } catch (RuntimeException | Error e) {
      rollbackResolution(snapshot);
      throw e;
    } finally {
      endResolution();
    }
  }

  @Internal
  public ObjectCodec<?> getUnwrappedObjectCodec(Class<?> rawType) {
    TypeRef<?> ownerType = TypeRef.of(rawType);
    Object key = typeInfoKey(rawType, rawType);
    JsonTypeInfo typeInfo = typeInfos.get(key);
    if (typeInfo != null) {
      // Generated capabilities replace type-info slots; objectCodecs retains the stable metadata
      // owner used to build an unwrapped parent.
      return typeInfo.usesDefaultObjectCodec() ? objectCodecs.get(key) : null;
    }
    if (customTypeInfo(rawType, rawType) != null || sharedRegistry.subTypesInfo(rawType) != null) {
      return null;
    }
    JsonValueCodec<?> selected = sharedRegistry.createCodec(rawType, ownerType, this);
    if (selected != null) {
      return null;
    }
    ObjectCodec<?> codec = objectCodecs.get(key);
    if (codec == null) {
      codec = newObjectCodec(ownerType);
      objectCodecs.put(key, codec);
    }
    typeInfo = newTypeInfo(rawType, rawType, codec);
    typeInfos.put(key, typeInfo);
    registerObjectTypeInfo(typeInfo);
    return codec;
  }

  public JsonTypeInfo getTypeInfo(Type declaredType, Class<?> fallback) {
    Class<?> rawType = CodecUtils.rawType(declaredType, fallback);
    Object key = typeInfoKey(declaredType, rawType);
    JsonTypeInfo typeInfo = typeInfos.get(key);
    if (typeInfo != null) {
      return typeInfo;
    }
    ResolutionSnapshot snapshot = beginResolution();
    try {
      return resolveTypeInfo(declaredType, rawType, key);
    } catch (RuntimeException | Error e) {
      rollbackResolution(snapshot);
      throw e;
    } finally {
      endResolution();
    }
  }

  @Internal
  public JsonTypeInfo getTypeInfo(Type declaredType, Class<?> fallback, JsonCodec annotation) {
    if (annotation == null) {
      return getTypeInfo(declaredType, fallback);
    }
    Class<?> rawType = CodecUtils.rawType(declaredType, fallback);
    ResolutionSnapshot snapshot = beginResolution();
    try {
      return resolveTypeInfo(declaredType, rawType, annotation);
    } catch (RuntimeException | Error e) {
      rollbackResolution(snapshot);
      throw e;
    } finally {
      endResolution();
    }
  }

  @Internal
  public JsonTypeInfo getTypeInfo(
      Type declaredType, Class<?> fallback, Class<? extends JsonValueCodec<?>> codecClass) {
    Class<?> rawType = CodecUtils.rawType(declaredType, fallback);
    return annotationTypeInfo(declaredType, rawType, codecClass);
  }

  private JsonTypeInfo resolveTypeInfo(Type declaredType, Class<?> rawType, Object key) {
    JsonTypeInfo typeInfo = customTypeInfo(declaredType, rawType);
    if (typeInfo != null) {
      typeInfos.put(key, typeInfo);
      return typeInfo;
    }
    JsonSubTypesInfo definition = sharedRegistry.subTypesInfo(rawType);
    if (definition != null) {
      sharedRegistry.checkSecure(rawType);
      ClosedSubtypeCodec codec = new ClosedSubtypeCodec(rawType, definition);
      typeInfo = newTypeInfo(declaredType, rawType, codec);
      // Closed graphs may recursively refer to their declared base through a subtype field or
      // container. Publish the complete dispatcher shell before resolving every finite branch.
      // The outer cold-resolution transaction removes the complete provisional graph on failure.
      typeInfos.put(key, typeInfo);
      codec.resolve(this);
      return typeInfo;
    }
    return buildTypeInfo(rawType, declaredType, key);
  }

  private JsonTypeInfo resolveTypeInfo(Type declaredType, Class<?> rawType, JsonCodec annotation) {
    Class<? extends JsonValueCodec<?>> valueCodec = annotation.value();
    Class<? extends JsonValueCodec<?>> elementCodec = annotation.elementCodec();
    Class<? extends JsonValueCodec<?>> contentCodec = annotation.contentCodec();
    Class<? extends MapKeyCodec> keyCodec = annotation.keyCodec();
    Class<? extends JsonValueCodec<?>> mapValueCodec = annotation.valueCodec();
    boolean hasValue = valueCodec != JsonCodec.NoJsonValueCodec.class;
    boolean hasElement = elementCodec != JsonCodec.NoJsonValueCodec.class;
    boolean hasContent = contentCodec != JsonCodec.NoJsonValueCodec.class;
    boolean hasKey = keyCodec != JsonCodec.NoMapKeyCodec.class;
    boolean hasMapValue = mapValueCodec != JsonCodec.NoJsonValueCodec.class;
    boolean hasChild = hasElement || hasContent || hasKey || hasMapValue;
    if (!hasValue && !hasChild) {
      throw invalidCodecConfig(rawType, "must select at least one codec");
    }
    if (hasValue && hasChild) {
      throw invalidCodecConfig(rawType, "value cannot be combined with a child codec");
    }
    if (hasValue) {
      return annotationTypeInfo(declaredType, rawType, valueCodec);
    }
    if (sharedRegistry.customCodec(rawType) != null
        || sharedRegistry.codecDeclaration(rawType) != null
        || sharedRegistry.subTypesInfo(rawType) != null) {
      throw invalidCodecConfig(
          rawType, "a child codec is hidden by the complete codec for the current value");
    }
    sharedRegistry.checkSecure(rawType);
    TypeRef<?> typeRef = typeRef(declaredType, rawType);
    if (rawType.isArray()) {
      requireSlots(rawType, hasElement, !hasContent && !hasKey && !hasMapValue, "elementCodec");
      Type elementType =
          declaredType instanceof GenericArrayType
              ? ((GenericArrayType) declaredType).getGenericComponentType()
              : rawType.getComponentType();
      requireConcreteChild(elementType, rawType, "elementCodec");
      Class<?> elementRawType = CodecUtils.rawType(elementType, rawType.getComponentType());
      JsonTypeInfo elementInfo = annotationTypeInfo(elementType, elementRawType, elementCodec);
      return newTypeInfo(declaredType, rawType, ArrayCodec.create(rawType, elementInfo));
    }
    if (rawType == AtomicReferenceArray.class) {
      requireSlots(rawType, hasElement, !hasContent && !hasKey && !hasMapValue, "elementCodec");
      TypeRef<?> elementType = directElementType(typeRef, rawType, "elementCodec");
      JsonTypeInfo elementInfo =
          annotationTypeInfo(elementType.getType(), elementType.getRawType(), elementCodec);
      return newTypeInfo(
          declaredType, rawType, new ScalarCodecs.AtomicReferenceArrayCodec(elementInfo));
    }
    if (Collection.class.isAssignableFrom(rawType)) {
      requireSlots(rawType, hasElement, !hasContent && !hasKey && !hasMapValue, "elementCodec");
      TypeRef<?> elementType = directElementType(typeRef, rawType, "elementCodec");
      JsonTypeInfo elementInfo =
          annotationTypeInfo(elementType.getType(), elementType.getRawType(), elementCodec);
      return newTypeInfo(
          declaredType,
          rawType,
          CollectionCodec.create(rawType, elementType.getRawType(), elementInfo));
    }
    if (Map.class.isAssignableFrom(rawType)) {
      if (hasElement || hasContent || !hasKey && !hasMapValue) {
        throw invalidCodecConfig(rawType, "supports only keyCodec and valueCodec child slots");
      }
      requireTypeArguments(typeRef, rawType);
      Tuple2<TypeRef<?>, TypeRef<?>> children = CodecUtils.mapKeyValueTypeRefs(typeRef);
      TypeRef<?> keyType = children.f0;
      TypeRef<?> mapValueType = children.f1;
      if (hasKey) {
        requireConcreteChild(keyType.getType(), rawType, "keyCodec");
      }
      if (hasMapValue) {
        requireConcreteChild(mapValueType.getType(), rawType, "valueCodec");
      }
      Class<?> keyRawType = keyType.getRawType();
      JsonTypeInfo valueInfo =
          hasMapValue
              ? annotationTypeInfo(mapValueType.getType(), mapValueType.getRawType(), mapValueCodec)
              : getTypeInfo(mapValueType.getType(), mapValueType.getRawType());
      checkMapKeySecure(keyRawType);
      MapCodec<?> codec =
          hasKey
              ? MapCodec.create(
                  rawType, keyRawType, valueInfo, sharedRegistry.mapKeyCodec(keyRawType, keyCodec))
              : MapCodec.create(rawType, keyRawType, valueInfo);
      return newTypeInfo(declaredType, rawType, codec);
    }
    if (rawType == Optional.class || rawType == AtomicReference.class) {
      requireSlots(rawType, hasContent, !hasElement && !hasKey && !hasMapValue, "contentCodec");
      TypeRef<?> contentType = directElementType(typeRef, rawType, "contentCodec");
      JsonTypeInfo contentInfo =
          annotationTypeInfo(contentType.getType(), contentType.getRawType(), contentCodec);
      JsonValueCodec<?> codec =
          rawType == Optional.class
              ? new ScalarCodecs.OptionalCodec(contentInfo)
              : new ScalarCodecs.AtomicReferenceCodec(contentInfo);
      return newTypeInfo(declaredType, rawType, codec);
    }
    throw invalidCodecConfig(rawType, "does not support child codecs");
  }

  private JsonTypeInfo customTypeInfo(Type declaredType, Class<?> rawType) {
    JsonValueCodec<?> codec = sharedRegistry.customCodec(rawType);
    if (codec != null) {
      sharedRegistry.checkCustomSecure(rawType);
      return newTypeInfo(declaredType, rawType, JsonFieldKind.OBJECT, codec, false);
    }
    JsonCodecDeclaration declaration = sharedRegistry.codecDeclaration(rawType);
    if (declaration != null) {
      if (!declaration.inherited()) {
        rejectConflictingValue(rawType);
      }
      codec = sharedRegistry.annotationCodec(rawType, declaration.codecClass());
      codec = declaration.bind(declaredType, rawType, codec);
      return newTypeInfo(declaredType, rawType, JsonFieldKind.OBJECT, codec, true);
    }
    JsonValueDeclaration value = sharedRegistry.valueDeclaration(rawType);
    if (value == null) {
      return null;
    }
    sharedRegistry.checkSecure(rawType);
    return newTypeInfo(declaredType, rawType, JsonFieldKind.OBJECT, value.codec(), true);
  }

  private JsonTypeInfo annotationTypeInfo(
      Type type, Class<?> rawType, Class<? extends JsonValueCodec<?>> codecClass) {
    JsonValueCodec<?> codec = sharedRegistry.annotationCodec(rawType, codecClass);
    return newTypeInfo(type, rawType, JsonFieldKind.OBJECT, codec, true);
  }

  private static TypeRef<?> directElementType(TypeRef<?> typeRef, Class<?> rawType, String slot) {
    requireTypeArguments(typeRef, rawType);
    TypeRef<?> elementType = CodecUtils.elementTypeRef(typeRef);
    requireConcreteChild(elementType.getType(), rawType, slot);
    return elementType;
  }

  private static void requireTypeArguments(TypeRef<?> typeRef, Class<?> rawType) {
    if (!typeRef.hasExplicitTypeArguments() && rawType.getTypeParameters().length != 0) {
      throw invalidCodecConfig(rawType, "child codecs require concrete type arguments");
    }
  }

  private static void requireConcreteChild(Type type, Class<?> rawType, String slot) {
    if (type instanceof TypeVariable || type instanceof WildcardType) {
      throw invalidCodecConfig(rawType, slot + " requires a concrete direct child type");
    }
    if (type instanceof ParameterizedType
        && !(((ParameterizedType) type).getRawType() instanceof Class)) {
      throw invalidCodecConfig(rawType, slot + " requires a concrete direct child type");
    }
  }

  private static void requireSlots(
      Class<?> rawType, boolean required, boolean noOtherSlots, String slot) {
    if (!required || !noOtherSlots) {
      throw invalidCodecConfig(rawType, "supports only " + slot + " as a child codec");
    }
  }

  private static ForyJsonException invalidCodecConfig(Class<?> rawType, String reason) {
    return new ForyJsonException("Invalid @JsonCodec for " + rawType.getTypeName() + ": " + reason);
  }

  private void rejectConflictingValue(Class<?> rawType) {
    if (sharedRegistry.valueDeclaration(rawType) != null) {
      throw new ForyJsonException(
          "Conflicting type-level @JsonCodec and effective @JsonValue on " + rawType.getName());
    }
  }

  private ResolutionSnapshot beginResolution() {
    ResolutionSnapshot snapshot =
        resolutionDepth == 0
            ? new ResolutionSnapshot(
                new HashSet<>(typeInfos.keySet()), new HashSet<>(objectCodecs.keySet()))
            : null;
    resolutionDepth++;
    return snapshot;
  }

  private void endResolution() {
    resolutionDepth--;
  }

  private void rollbackResolution(ResolutionSnapshot snapshot) {
    if (snapshot == null) {
      return;
    }
    // Metadata created anywhere in a failed recursive graph may retain a provisional parent.
    // Remove every new owner while preserving metadata and active JIT work that predated the
    // outermost cold lookup.
    Iterator<Map.Entry<Object, JsonTypeInfo>> typeIterator = typeInfos.entrySet().iterator();
    while (typeIterator.hasNext()) {
      Map.Entry<Object, JsonTypeInfo> entry = typeIterator.next();
      if (!snapshot.typeKeys.contains(entry.getKey())) {
        JsonTypeInfo value = entry.getValue();
        if (rawObjectTypeInfos.get(value.rawType()) == value) {
          rawObjectTypeInfos.remove(value.rawType());
          ObjectCodec<?> owner = objectCodecs.get(value.rawType());
          if (owner != null) {
            canonicalObjectTypeInfos.remove(owner);
          }
        }
        typeIterator.remove();
      }
    }
    Iterator<Object> objectIterator = objectCodecs.keySet().iterator();
    while (objectIterator.hasNext()) {
      if (!snapshot.objectKeys.contains(objectIterator.next())) {
        objectIterator.remove();
      }
    }
  }

  private static final class ResolutionSnapshot {
    private final Set<Object> typeKeys;
    private final Set<Object> objectKeys;

    private ResolutionSnapshot(Set<Object> typeKeys, Set<Object> objectKeys) {
      this.typeKeys = typeKeys;
      this.objectKeys = objectKeys;
    }
  }

  public JsonTypeInfo getRuntimeTypeInfo(Class<?> runtimeType) {
    Object key = runtimeType == Object.class ? RuntimeObjectKey.INSTANCE : runtimeType;
    JsonTypeInfo typeInfo = typeInfos.get(key);
    if (typeInfo != null) {
      return typeInfo;
    }
    ResolutionSnapshot snapshot = beginResolution();
    try {
      return resolveRuntimeTypeInfo(runtimeType, key);
    } catch (RuntimeException | Error e) {
      rollbackResolution(snapshot);
      throw e;
    } finally {
      endResolution();
    }
  }

  private JsonTypeInfo resolveRuntimeTypeInfo(Class<?> runtimeType, Object key) {
    JsonTypeInfo typeInfo;
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

  @Internal
  public void checkMapKeySecure(Class<?> type) {
    sharedRegistry.checkMapKeySecure(type);
  }

  @SuppressWarnings("unchecked")
  public <T> StringWriterCodec<T> stringWriter(ObjectCodec<T> codec) {
    requireJITLock();
    ObjectCodec<Object> owner = erase(codec);
    JsonTypeInfo typeInfo = canonicalObjectTypeInfos.get(owner);
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
    JsonTypeInfo typeInfo = canonicalObjectTypeInfos.get(owner);
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

  @SuppressWarnings("unchecked")
  public <T> Latin1ReaderCodec<T> latin1Reader(ObjectCodec<T> codec) {
    requireJITLock();
    ObjectCodec<Object> owner = erase(codec);
    JsonTypeInfo typeInfo = canonicalObjectTypeInfos.get(owner);
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
    JsonTypeInfo typeInfo = canonicalObjectTypeInfos.get(owner);
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
    JsonTypeInfo typeInfo = canonicalObjectTypeInfos.get(owner);
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
    JsonTypeInfo typeInfo = canonicalObjectTypeInfos.get(owner);
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
    if (owner.unwrappedInfo() != null) {
      return newUnwrappedStringWriter(owner, generatedClass);
    }
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
      return GeneratedCodecInstantiator.instantiateStringWriter(generatedClass, fields, codecs);
    }
    if (!storesAnyCodec(owner, any)) {
      return GeneratedCodecInstantiator.instantiateAnyStringWriter(
          generatedClass, owner, fields, codecs);
    }
    StringWriterCodec<Object> anyCodec = any.valueTypeInfo().stringWriter();
    if (any.valueTypeInfo().usesDefaultObjectCodec()
        && any.valueRawType() != owner.type()
        && anyCodec instanceof ObjectCodec) {
      anyCodec = stringWriter((ObjectCodec<Object>) anyCodec);
    }
    return GeneratedCodecInstantiator.instantiateAnyStringWriter(
        generatedClass, owner, fields, codecs, anyCodec);
  }

  @SuppressWarnings("unchecked")
  private Utf8WriterCodec<Object> newUtf8Writer(ObjectCodec<?> owner, Class<?> generatedClass) {
    if (owner.unwrappedInfo() != null) {
      return newUnwrappedUtf8Writer(owner, generatedClass);
    }
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
      return GeneratedCodecInstantiator.instantiateUtf8Writer(generatedClass, fields, codecs);
    }
    if (!storesAnyCodec(owner, any)) {
      return GeneratedCodecInstantiator.instantiateAnyUtf8Writer(
          generatedClass, owner, fields, codecs);
    }
    Utf8WriterCodec<Object> anyCodec = any.valueTypeInfo().utf8Writer();
    if (any.valueTypeInfo().usesDefaultObjectCodec()
        && any.valueRawType() != owner.type()
        && anyCodec instanceof ObjectCodec) {
      anyCodec = utf8Writer((ObjectCodec<Object>) anyCodec);
    }
    return GeneratedCodecInstantiator.instantiateAnyUtf8Writer(
        generatedClass, owner, fields, codecs, anyCodec);
  }

  @SuppressWarnings("unchecked")
  private StringWriterCodec<Object> newUnwrappedStringWriter(
      ObjectCodec<?> owner, Class<?> generatedClass) {
    JsonFieldInfo[] fields = unwrappedWriteFields(owner);
    StringWriterCodec<Object>[] codecs =
        (StringWriterCodec<Object>[]) new StringWriterCodec<?>[fields.length];
    for (int i = 0; i < fields.length; i++) {
      JsonFieldInfo field = fields[i];
      if (JsonCodegen.usesWriteCodec(field)) {
        JsonTypeInfo child = field.writeTypeInfo();
        StringWriterCodec<Object> codec = child.stringWriter();
        if (JsonCodegen.writeNestedType(field) != null
            && child.rawType() != owner.type()
            && codec instanceof ObjectCodec) {
          codec = stringWriter((ObjectCodec<Object>) codec);
        }
        codecs[i] = codec;
      }
    }
    AnyInfo any = owner.anyInfo();
    if (any == null || any.writeField() == null && any.writeGetter() == null) {
      return GeneratedCodecInstantiator.instantiateStringWriter(generatedClass, fields, codecs);
    }
    if (!storesAnyCodec(owner, any)) {
      return GeneratedCodecInstantiator.instantiateAnyStringWriter(
          generatedClass, owner, fields, codecs);
    }
    StringWriterCodec<Object> anyCodec = any.valueTypeInfo().stringWriter();
    if (any.valueTypeInfo().usesDefaultObjectCodec()
        && any.valueRawType() != owner.type()
        && anyCodec instanceof ObjectCodec) {
      anyCodec = stringWriter((ObjectCodec<Object>) anyCodec);
    }
    return GeneratedCodecInstantiator.instantiateAnyStringWriter(
        generatedClass, owner, fields, codecs, anyCodec);
  }

  @SuppressWarnings("unchecked")
  private Utf8WriterCodec<Object> newUnwrappedUtf8Writer(
      ObjectCodec<?> owner, Class<?> generatedClass) {
    JsonFieldInfo[] fields = unwrappedWriteFields(owner);
    Utf8WriterCodec<Object>[] codecs =
        (Utf8WriterCodec<Object>[]) new Utf8WriterCodec<?>[fields.length];
    for (int i = 0; i < fields.length; i++) {
      JsonFieldInfo field = fields[i];
      if (JsonCodegen.usesWriteCodec(field)) {
        JsonTypeInfo child = field.writeTypeInfo();
        Utf8WriterCodec<Object> codec = child.utf8Writer();
        if (JsonCodegen.writeNestedType(field) != null
            && child.rawType() != owner.type()
            && codec instanceof ObjectCodec) {
          codec = utf8Writer((ObjectCodec<Object>) codec);
        }
        codecs[i] = codec;
      }
    }
    AnyInfo any = owner.anyInfo();
    if (any == null || any.writeField() == null && any.writeGetter() == null) {
      return GeneratedCodecInstantiator.instantiateUtf8Writer(generatedClass, fields, codecs);
    }
    if (!storesAnyCodec(owner, any)) {
      return GeneratedCodecInstantiator.instantiateAnyUtf8Writer(
          generatedClass, owner, fields, codecs);
    }
    Utf8WriterCodec<Object> anyCodec = any.valueTypeInfo().utf8Writer();
    if (any.valueTypeInfo().usesDefaultObjectCodec()
        && any.valueRawType() != owner.type()
        && anyCodec instanceof ObjectCodec) {
      anyCodec = utf8Writer((ObjectCodec<Object>) anyCodec);
    }
    return GeneratedCodecInstantiator.instantiateAnyUtf8Writer(
        generatedClass, owner, fields, codecs, anyCodec);
  }

  private static JsonFieldInfo[] unwrappedWriteFields(ObjectCodec<?> owner) {
    return owner.unwrappedInfo().writeFields();
  }

  @SuppressWarnings("unchecked")
  private Latin1ReaderCodec<Object> newLatin1Reader(ObjectCodec<?> owner, Class<?> generatedClass) {
    return newLatin1Reader(owner, generatedClass, owner.readTable());
  }

  @SuppressWarnings("unchecked")
  private Latin1ReaderCodec<Object> newLatin1Reader(
      ObjectCodec<?> owner, Class<?> generatedClass, JsonFieldTable readTable) {
    if (owner.unwrappedInfo() != null) {
      return newUnwrappedLatin1Reader(owner, generatedClass, readTable);
    }
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
        return GeneratedCodecInstantiator.instantiateLatin1Reader(
            generatedClass, owner, fields, codecs);
      }
      if (!storesAnyCodec(owner, any)) {
        return GeneratedCodecInstantiator.instantiateAnyLatin1Reader(
            generatedClass, owner, readTable, fields, codecs);
      }
      Latin1ReaderCodec<Object> anyCodec = any.valueTypeInfo().latin1Reader();
      if (any.valueTypeInfo().usesDefaultObjectCodec()
          && any.valueRawType() != owner.type()
          && anyCodec instanceof ObjectCodec) {
        anyCodec = latin1Reader((ObjectCodec<Object>) anyCodec);
      }
      return GeneratedCodecInstantiator.instantiateAnyLatin1Reader(
          generatedClass, owner, readTable, fields, codecs, anyCodec);
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
      return GeneratedCodecInstantiator.instantiateLatin1Reader(
          generatedClass, owner, fields, codecs);
    }
    if (!storesAnyCodec(owner, any)) {
      return GeneratedCodecInstantiator.instantiateAnyLatin1Reader(
          generatedClass, owner, readTable, fields, codecs);
    }
    Latin1ReaderCodec<Object> anyCodec = any.valueTypeInfo().latin1Reader();
    if (any.valueTypeInfo().usesDefaultObjectCodec()
        && any.valueRawType() != owner.type()
        && anyCodec instanceof ObjectCodec) {
      anyCodec = latin1Reader((ObjectCodec<Object>) anyCodec);
    }
    return GeneratedCodecInstantiator.instantiateAnyLatin1Reader(
        generatedClass, owner, readTable, fields, codecs, anyCodec);
  }

  @SuppressWarnings("unchecked")
  private Utf16ReaderCodec<Object> newUtf16Reader(ObjectCodec<?> owner, Class<?> generatedClass) {
    return newUtf16Reader(owner, generatedClass, owner.readTable());
  }

  @SuppressWarnings("unchecked")
  private Utf16ReaderCodec<Object> newUtf16Reader(
      ObjectCodec<?> owner, Class<?> generatedClass, JsonFieldTable readTable) {
    if (owner.unwrappedInfo() != null) {
      return newUnwrappedUtf16Reader(owner, generatedClass, readTable);
    }
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
        return GeneratedCodecInstantiator.instantiateUtf16Reader(
            generatedClass, owner, fields, codecs);
      }
      if (!storesAnyCodec(owner, any)) {
        return GeneratedCodecInstantiator.instantiateAnyUtf16Reader(
            generatedClass, owner, readTable, fields, codecs);
      }
      Utf16ReaderCodec<Object> anyCodec = any.valueTypeInfo().utf16Reader();
      if (any.valueTypeInfo().usesDefaultObjectCodec()
          && any.valueRawType() != owner.type()
          && anyCodec instanceof ObjectCodec) {
        anyCodec = utf16Reader((ObjectCodec<Object>) anyCodec);
      }
      return GeneratedCodecInstantiator.instantiateAnyUtf16Reader(
          generatedClass, owner, readTable, fields, codecs, anyCodec);
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
      return GeneratedCodecInstantiator.instantiateUtf16Reader(
          generatedClass, owner, fields, codecs);
    }
    if (!storesAnyCodec(owner, any)) {
      return GeneratedCodecInstantiator.instantiateAnyUtf16Reader(
          generatedClass, owner, readTable, fields, codecs);
    }
    Utf16ReaderCodec<Object> anyCodec = any.valueTypeInfo().utf16Reader();
    if (any.valueTypeInfo().usesDefaultObjectCodec()
        && any.valueRawType() != owner.type()
        && anyCodec instanceof ObjectCodec) {
      anyCodec = utf16Reader((ObjectCodec<Object>) anyCodec);
    }
    return GeneratedCodecInstantiator.instantiateAnyUtf16Reader(
        generatedClass, owner, readTable, fields, codecs, anyCodec);
  }

  @SuppressWarnings("unchecked")
  private Utf8ReaderCodec<Object> newUtf8Reader(ObjectCodec<?> owner, Class<?> generatedClass) {
    return newUtf8Reader(owner, generatedClass, owner.readTable());
  }

  @SuppressWarnings("unchecked")
  private Utf8ReaderCodec<Object> newUtf8Reader(
      ObjectCodec<?> owner, Class<?> generatedClass, JsonFieldTable readTable) {
    if (owner.unwrappedInfo() != null) {
      return newUnwrappedUtf8Reader(owner, generatedClass, readTable);
    }
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
        return GeneratedCodecInstantiator.instantiateUtf8Reader(
            generatedClass, owner, fields, codecs);
      }
      if (!storesAnyCodec(owner, any)) {
        return GeneratedCodecInstantiator.instantiateAnyUtf8Reader(
            generatedClass, owner, readTable, fields, codecs);
      }
      Utf8ReaderCodec<Object> anyCodec = any.valueTypeInfo().utf8Reader();
      if (any.valueTypeInfo().usesDefaultObjectCodec()
          && any.valueRawType() != owner.type()
          && anyCodec instanceof ObjectCodec) {
        anyCodec = utf8Reader((ObjectCodec<Object>) anyCodec);
      }
      return GeneratedCodecInstantiator.instantiateAnyUtf8Reader(
          generatedClass, owner, readTable, fields, codecs, anyCodec);
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
      return GeneratedCodecInstantiator.instantiateUtf8Reader(
          generatedClass, owner, fields, codecs);
    }
    if (!storesAnyCodec(owner, any)) {
      return GeneratedCodecInstantiator.instantiateAnyUtf8Reader(
          generatedClass, owner, readTable, fields, codecs);
    }
    Utf8ReaderCodec<Object> anyCodec = any.valueTypeInfo().utf8Reader();
    if (any.valueTypeInfo().usesDefaultObjectCodec()
        && any.valueRawType() != owner.type()
        && anyCodec instanceof ObjectCodec) {
      anyCodec = utf8Reader((ObjectCodec<Object>) anyCodec);
    }
    return GeneratedCodecInstantiator.instantiateAnyUtf8Reader(
        generatedClass, owner, readTable, fields, codecs, anyCodec);
  }

  @SuppressWarnings("unchecked")
  private Latin1ReaderCodec<Object> newUnwrappedLatin1Reader(
      ObjectCodec<?> owner, Class<?> generatedClass, JsonFieldTable readTable) {
    JsonFieldInfo[] fields = unwrappedReadFields(owner);
    JsonTypeInfo[] children = unwrappedReadTypeInfos(owner);
    Latin1ReaderCodec<Object>[] codecs =
        (Latin1ReaderCodec<Object>[]) new Latin1ReaderCodec<?>[children.length];
    for (int i = 0; i < children.length; i++) {
      JsonTypeInfo child = children[i];
      Latin1ReaderCodec<Object> codec = child.latin1Reader();
      if (child.usesDefaultObjectCodec()
          && child.rawType() != owner.type()
          && codec instanceof ObjectCodec) {
        codec = latin1Reader((ObjectCodec<Object>) codec);
      }
      codecs[i] = codec;
    }
    AnyInfo any = owner.anyInfo();
    if (any == null || any.readField() == null && any.readSetter() == null) {
      return GeneratedCodecInstantiator.instantiateLatin1Reader(
          generatedClass, owner, fields, codecs);
    }
    if (!storesAnyCodec(owner, any)) {
      return GeneratedCodecInstantiator.instantiateAnyLatin1Reader(
          generatedClass, owner, readTable, fields, codecs);
    }
    Latin1ReaderCodec<Object> anyCodec = any.valueTypeInfo().latin1Reader();
    if (any.valueTypeInfo().usesDefaultObjectCodec()
        && any.valueRawType() != owner.type()
        && anyCodec instanceof ObjectCodec) {
      anyCodec = latin1Reader((ObjectCodec<Object>) anyCodec);
    }
    return GeneratedCodecInstantiator.instantiateAnyLatin1Reader(
        generatedClass, owner, readTable, fields, codecs, anyCodec);
  }

  @SuppressWarnings("unchecked")
  private Utf16ReaderCodec<Object> newUnwrappedUtf16Reader(
      ObjectCodec<?> owner, Class<?> generatedClass, JsonFieldTable readTable) {
    JsonFieldInfo[] fields = unwrappedReadFields(owner);
    JsonTypeInfo[] children = unwrappedReadTypeInfos(owner);
    Utf16ReaderCodec<Object>[] codecs =
        (Utf16ReaderCodec<Object>[]) new Utf16ReaderCodec<?>[children.length];
    for (int i = 0; i < children.length; i++) {
      JsonTypeInfo child = children[i];
      Utf16ReaderCodec<Object> codec = child.utf16Reader();
      if (child.usesDefaultObjectCodec()
          && child.rawType() != owner.type()
          && codec instanceof ObjectCodec) {
        codec = utf16Reader((ObjectCodec<Object>) codec);
      }
      codecs[i] = codec;
    }
    AnyInfo any = owner.anyInfo();
    if (any == null || any.readField() == null && any.readSetter() == null) {
      return GeneratedCodecInstantiator.instantiateUtf16Reader(
          generatedClass, owner, fields, codecs);
    }
    if (!storesAnyCodec(owner, any)) {
      return GeneratedCodecInstantiator.instantiateAnyUtf16Reader(
          generatedClass, owner, readTable, fields, codecs);
    }
    Utf16ReaderCodec<Object> anyCodec = any.valueTypeInfo().utf16Reader();
    if (any.valueTypeInfo().usesDefaultObjectCodec()
        && any.valueRawType() != owner.type()
        && anyCodec instanceof ObjectCodec) {
      anyCodec = utf16Reader((ObjectCodec<Object>) anyCodec);
    }
    return GeneratedCodecInstantiator.instantiateAnyUtf16Reader(
        generatedClass, owner, readTable, fields, codecs, anyCodec);
  }

  @SuppressWarnings("unchecked")
  private Utf8ReaderCodec<Object> newUnwrappedUtf8Reader(
      ObjectCodec<?> owner, Class<?> generatedClass, JsonFieldTable readTable) {
    JsonFieldInfo[] fields = unwrappedReadFields(owner);
    JsonTypeInfo[] children = unwrappedReadTypeInfos(owner);
    Utf8ReaderCodec<Object>[] codecs =
        (Utf8ReaderCodec<Object>[]) new Utf8ReaderCodec<?>[children.length];
    for (int i = 0; i < children.length; i++) {
      JsonTypeInfo child = children[i];
      Utf8ReaderCodec<Object> codec = child.utf8Reader();
      if (child.usesDefaultObjectCodec()
          && child.rawType() != owner.type()
          && codec instanceof ObjectCodec) {
        codec = utf8Reader((ObjectCodec<Object>) codec);
      }
      codecs[i] = codec;
    }
    AnyInfo any = owner.anyInfo();
    if (any == null || any.readField() == null && any.readSetter() == null) {
      return GeneratedCodecInstantiator.instantiateUtf8Reader(
          generatedClass, owner, fields, codecs);
    }
    if (!storesAnyCodec(owner, any)) {
      return GeneratedCodecInstantiator.instantiateAnyUtf8Reader(
          generatedClass, owner, readTable, fields, codecs);
    }
    Utf8ReaderCodec<Object> anyCodec = any.valueTypeInfo().utf8Reader();
    if (any.valueTypeInfo().usesDefaultObjectCodec()
        && any.valueRawType() != owner.type()
        && anyCodec instanceof ObjectCodec) {
      anyCodec = utf8Reader((ObjectCodec<Object>) anyCodec);
    }
    return GeneratedCodecInstantiator.instantiateAnyUtf8Reader(
        generatedClass, owner, readTable, fields, codecs, anyCodec);
  }

  private static JsonFieldInfo[] unwrappedReadFields(ObjectCodec<?> owner) {
    JsonCreatorInfo creator = owner.creatorInfo();
    int directCount = creator == null ? owner.readFields().length : creator.fields().length;
    JsonUnwrappedInfo.ReadRoute[] routes = owner.unwrappedInfo().readRoutes();
    JsonFieldInfo[] fields = new JsonFieldInfo[directCount + routes.length];
    if (creator == null) {
      System.arraycopy(owner.readFields(), 0, fields, 0, directCount);
    }
    for (int i = 0; i < routes.length; i++) {
      fields[directCount + i] = routes[i].field();
    }
    return fields;
  }

  private static JsonTypeInfo[] unwrappedReadTypeInfos(ObjectCodec<?> owner) {
    JsonCreatorInfo creator = owner.creatorInfo();
    int directCount = creator == null ? owner.readFields().length : creator.fields().length;
    JsonUnwrappedInfo.ReadRoute[] routes = owner.unwrappedInfo().readRoutes();
    JsonTypeInfo[] children = new JsonTypeInfo[directCount + routes.length];
    if (creator == null) {
      for (int i = 0; i < directCount; i++) {
        children[i] = owner.readFields()[i].readTypeInfo();
      }
    } else {
      for (int i = 0; i < directCount; i++) {
        children[i] = creator.fields()[i].typeInfo();
      }
    }
    for (int i = 0; i < routes.length; i++) {
      JsonUnwrappedInfo.ReadRoute route = routes[i];
      children[directCount + i] =
          route.field() == null ? route.creatorField().typeInfo() : route.field().readTypeInfo();
    }
    return children;
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
    JsonFieldInfo[] fields =
        owner.unwrappedInfo() == null ? owner.writeFields() : unwrappedWriteFields(owner);
    for (int i = 0; i < fields.length; i++) {
      Class<?> nestedType = JsonCodegen.writeNestedType(fields[i]);
      JsonTypeInfo child = fields[i].writeTypeInfo();
      if (nestedType != null
          && nestedType != owner.type()
          && rawObjectTypeInfos.get(child.rawType()) == child) {
        if (childFields == null) {
          childFields = new Field[fields.length];
        }
        childFields[i] = ReflectionUtils.getField(parent.getClass(), "w" + i);
      }
    }
    return childFields;
  }

  private Field[] readerChildFields(Object parent, ObjectCodec<?> owner) {
    if (owner.unwrappedInfo() != null) {
      return unwrappedReaderChildFields(parent, owner);
    }
    Field[] childFields = null;
    JsonCreatorInfo creator = owner.creatorInfo();
    if (creator != null) {
      JsonCreatorFieldInfo[] fields = creator.fields();
      for (int i = 0; i < fields.length; i++) {
        JsonTypeInfo child = fields[i].typeInfo();
        if (child.usesDefaultObjectCodec()
            && child.rawType() != owner.type()
            && rawObjectTypeInfos.get(child.rawType()) == child) {
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
      JsonTypeInfo child = fields[i].readTypeInfo();
      if (nestedType != null
          && nestedType != owner.type()
          && rawObjectTypeInfos.get(child.rawType()) == child) {
        if (childFields == null) {
          childFields = new Field[fields.length];
        }
        childFields[i] = ReflectionUtils.getField(parent.getClass(), "o" + i);
      }
    }
    return childFields;
  }

  private Field[] unwrappedReaderChildFields(Object parent, ObjectCodec<?> owner) {
    JsonTypeInfo[] children = unwrappedReadTypeInfos(owner);
    JsonCreatorInfo creator = owner.creatorInfo();
    int directCount = creator == null ? owner.readFields().length : creator.fields().length;
    JsonUnwrappedInfo.ReadRoute[] routes = owner.unwrappedInfo().readRoutes();
    Field[] childFields = null;
    for (int i = 0; i < children.length; i++) {
      JsonTypeInfo child = children[i];
      if (!child.usesDefaultObjectCodec()
          || child.rawType() == owner.type()
          || rawObjectTypeInfos.get(child.rawType()) != child) {
        continue;
      }
      String fieldName;
      if (i < directCount) {
        if (creator == null && JsonCodegen.readNestedType(owner.readFields()[i]) == null) {
          continue;
        }
        fieldName = creator == null ? "o" + i : "r" + i;
      } else {
        JsonUnwrappedInfo.ReadRoute route = routes[i - directCount];
        if (route.field() != null && JsonCodegen.readNestedType(route.field()) == null) {
          continue;
        }
        fieldName = (route.field() == null ? "r" : "o") + i;
      }
      if (childFields == null) {
        childFields = new Field[children.length];
      }
      childFields[i] = ReflectionUtils.getField(parent.getClass(), fieldName);
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
    StringWriterCodec<Object> codec = newStringWriter(owner, generated);
    Field[] childFields = writerChildFields(codec, owner);
    registerStringWriterCallbacks(codec, owner, childFields);
    registerStringAnyWriterCallback(codec, owner);
    typeInfo.setStringWriter(codec);
  }

  private void publishUtf8Writer(
      ObjectCodec<Object> owner, JsonTypeInfo typeInfo, Class<?> generated) {
    requireJITLock();
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
    JsonFieldInfo[] properties =
        owner.unwrappedInfo() == null ? owner.writeFields() : unwrappedWriteFields(owner);
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
    JsonFieldInfo[] properties =
        owner.unwrappedInfo() == null ? owner.writeFields() : unwrappedWriteFields(owner);
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
            checkGeneratedClass(result, codec);
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
            checkGeneratedClass(result, codec);
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

  private boolean hasGeneratedAnyWriteChild(ObjectCodec<?> owner, AnyInfo any) {
    return any != null
        && (any.writeField() != null || any.writeGetter() != null)
        && any.valueTypeInfo().usesDefaultObjectCodec()
        && any.valueRawType() != owner.type()
        && rawObjectTypeInfos.get(any.valueRawType()) == any.valueTypeInfo();
  }

  private boolean hasGeneratedAnyReadChild(ObjectCodec<?> owner, AnyInfo any) {
    return any != null
        && (any.readField() != null || any.readSetter() != null)
        && any.valueTypeInfo().usesDefaultObjectCodec()
        && any.valueRawType() != owner.type()
        && rawObjectTypeInfos.get(any.valueRawType()) == any.valueTypeInfo();
  }

  private static JsonTypeInfo readerChildTypeInfo(ObjectCodec<?> owner, int index) {
    if (owner.unwrappedInfo() != null) {
      return unwrappedReadTypeInfos(owner)[index];
    }
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
    ObjectCodec<T> codec = newObjectCodec(ownerType);
    // Publish the complete declared-type owner before resolving fields so recursive parameterized
    // bindings resolve back to the same field table rather than the raw-class binding.
    objectCodecs.put(key, codec);
    // The outer resolution transaction owns failure cleanup. Keep this owner published until that
    // rollback removes its canonical identity index and every other provisional graph entry.
    codec.resolveTypes(this);
    return codec;
  }

  private <T> ObjectCodec<T> newObjectCodec(TypeRef<T> ownerType) {
    Class<?> rawType = ownerType.getRawType();
    sharedRegistry.checkSecure(rawType);
    if (rawType.isInterface()
        || Modifier.isAbstract(rawType.getModifiers())
        || rawType.isPrimitive()
        || rawType.isArray()
        || rawType.isEnum()) {
      throw new ForyJsonException("Unsupported JSON object type " + rawType);
    }
    GeneratedJsonCodec<?> generatedCodec = sharedRegistry.generatedCodec(rawType);
    return ObjectCodec.build(
        ownerType,
        sharedRegistry.propertyDiscoveryEnabled(),
        sharedRegistry.propertyNamingStrategy(),
        sharedRegistry.writeNullFields(),
        generatedCodec);
  }

  private JsonTypeInfo buildTypeInfo(Class<?> rawType, Type declaredType, Object key) {
    sharedRegistry.checkSecure(rawType);
    TypeRef<?> typeRef = typeRef(declaredType, rawType);
    JsonValueCodec<?> codec = sharedRegistry.createCodec(rawType, typeRef, this);
    if (codec == null) {
      return buildObjectTypeInfo(typeRef, key);
    }
    JsonTypeInfo recursiveTypeInfo = typeInfos.get(key);
    if (recursiveTypeInfo != null) {
      return recursiveTypeInfo;
    }
    JsonTypeInfo typeInfo = newTypeInfo(declaredType, rawType, codec);
    typeInfos.put(key, typeInfo);
    registerObjectTypeInfo(typeInfo);
    return typeInfo;
  }

  private JsonTypeInfo buildObjectTypeInfo(TypeRef<?> ownerType, Object key) {
    JsonTypeInfo typeInfo = typeInfos.get(key);
    if (typeInfo != null) {
      return typeInfo;
    }
    ObjectCodec<?> codec = objectCodecs.get(key);
    if (codec == null) {
      codec = newObjectCodec(ownerType);
      typeInfo = newTypeInfo(ownerType.getType(), ownerType.getRawType(), codec);
      // The object codec and its heterogeneous type owner are one recursive metadata unit. Both
      // must be visible before any field resolves so self-references reuse the same field table and
      // capability slots. The outer cold-resolution transaction removes both on failure.
      objectCodecs.put(key, codec);
      typeInfos.put(key, typeInfo);
      registerObjectTypeInfo(typeInfo);
      codec.resolveTypes(this);
      return typeInfo;
    }
    // A public getObjectCodec call may already own construction of this shell. Bind its type info
    // now; the outer owner finishes field resolution before returning the codec to its caller.
    typeInfo = newTypeInfo(ownerType.getType(), ownerType.getRawType(), codec);
    typeInfos.put(key, typeInfo);
    registerObjectTypeInfo(typeInfo);
    return typeInfo;
  }

  private JsonTypeInfo buildRuntimeTypeInfo(Class<?> rawType) {
    JsonTypeInfo custom = customTypeInfo(rawType, rawType);
    if (custom != null) {
      return custom;
    }
    sharedRegistry.checkSecure(rawType);
    TypeRef<?> typeRef = TypeRef.of(rawType);
    JsonValueCodec<?> codec =
        rawType == Object.class
            ? getObjectCodec(typeRef)
            : sharedRegistry.createCodec(rawType, typeRef, this);
    if (codec == null) {
      codec = getObjectCodec(typeRef);
    }
    return newTypeInfo(rawType, rawType, codec);
  }

  private JsonTypeInfo newTypeInfo(Type type, Class<?> rawType, JsonValueCodec<?> codec) {
    return new JsonTypeInfo(type, rawType, sharedRegistry.kind(rawType), bindCodec(codec));
  }

  private JsonTypeInfo newTypeInfo(
      Type type,
      Class<?> rawType,
      JsonFieldKind kind,
      JsonValueCodec<?> codec,
      boolean annotationCodec) {
    return new JsonTypeInfo(type, rawType, kind, bindCodec(codec), annotationCodec);
  }

  private void registerObjectTypeInfo(JsonTypeInfo typeInfo) {
    if (typeInfo.usesDefaultObjectCodec()
        && typeInfo.type() instanceof Class
        && typeInfo.rawType() != Object.class) {
      ObjectCodec<?> owner = (ObjectCodec<?>) typeInfo.stringWriter();
      rawObjectTypeInfos.put(typeInfo.rawType(), typeInfo);
      canonicalObjectTypeInfos.put(owner, typeInfo);
    }
  }

  private static void checkGeneratedClass(Object result, Object codec) {
    if (codec.getClass() != result) {
      throw new IllegalStateException(
          "Generated JSON callback does not match installed capability");
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
  private static JsonValueCodec<Object> bindCodec(JsonValueCodec<?> codec) {
    // The resolver has already matched the codec to this binding's declared type. JsonTypeInfo is
    // deliberately heterogeneous, so erase that proven relation once instead of casting in every
    // root, field, container, and generated hot call.
    return (JsonValueCodec<Object>) codec;
  }
}

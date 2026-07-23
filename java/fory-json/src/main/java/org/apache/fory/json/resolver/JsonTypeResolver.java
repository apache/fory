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

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
import org.apache.fory.reflect.TypeRef;

/**
 * Local JSON type dispatcher used exclusively by one borrowed {@code ForyJson} state at a time.
 *
 * <p>This class corresponds to Fory core's {@code ClassResolver}: it owns terminal capability
 * construction, final child wiring, and capability-slot publication. After an outer metadata
 * resolution succeeds, it registers every eligible representation graph once. Root codec execution
 * and graph completion use the same resolver-local JIT lock. {@link JsonJITContext} only orders the
 * completion under that lock; it does not know any JSON capability, generated class, or field
 * metadata. Compilation failure leaves the interpreted capability in its {@link JsonTypeInfo} slot
 * without adding failure or request state to the hot codec.
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
  private final IdentityMap<JsonTypeInfo, CollectionCodec<?>> collectionCodecs;
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
    collectionCodecs = new IdentityMap<>();
  }

  /** Returns the shared registry that owns this resolver and its reader cache domain. */
  @Internal
  public JsonSharedRegistry sharedRegistry() {
    return sharedRegistry;
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
      ObjectCodec<T> result = buildObjectCodec(ownerType, key);
      completeResolution(snapshot);
      return result;
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
      return canonicalObjectCodec(typeInfo);
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
    registerTypeInfoOwner(typeInfo, codec);
    return codec;
  }

  /**
   * Returns the stable metadata owner for a canonical raw-class object binding, or {@code null}.
   *
   * <p>This lookup never constructs metadata or requests compilation. Source generation may call it
   * outside a root operation; the short resolver-local lock protects the ordinary owner maps
   * without coupling one generated class compilation to another.
   */
  @Internal
  public ObjectCodec<?> canonicalObjectCodec(JsonTypeInfo typeInfo) {
    jitContext.lock();
    try {
      return canonicalObjectOwner(typeInfo);
    } finally {
      jitContext.unlock();
    }
  }

  private ObjectCodec<?> canonicalObjectOwner(JsonTypeInfo typeInfo) {
    if (rawObjectTypeInfos.get(typeInfo.rawType()) != typeInfo) {
      return null;
    }
    return objectCodecs.get(typeInfo.rawType());
  }

  /** Returns an exact declared ArrayList-backed UTF-8 collection owner, or {@code null}. */
  @Internal
  public CollectionCodec<?> exactUtf8Collection(JsonTypeInfo typeInfo) {
    jitContext.lock();
    try {
      return exactUtf8CollectionOwner(typeInfo);
    } finally {
      jitContext.unlock();
    }
  }

  private CollectionCodec<?> exactUtf8CollectionOwner(JsonTypeInfo typeInfo) {
    CollectionCodec<?> owner = collectionCodecs.get(typeInfo);
    if (owner == null
        || !owner.createsArrayList()
        || !(typeInfo.type() instanceof ParameterizedType)) {
      return null;
    }
    Type declaredElement = CodecUtils.elementType(typeInfo.type());
    JsonTypeInfo element = declaredCollectionElement(typeInfo);
    if (element == null
        || !declaredElement.equals(element.type())
        || canonicalObjectOwner(element) == null
            && !(owner instanceof CollectionCodec.DirectCollectionCodec)) {
      return null;
    }
    return owner;
  }

  private JsonTypeInfo declaredCollectionElement(JsonTypeInfo collection) {
    Type elementType = CodecUtils.elementType(collection.type());
    Class<?> rawType = CodecUtils.rawType(elementType, Object.class);
    return typeInfos.get(typeInfoKey(elementType, rawType));
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
      JsonTypeInfo result = resolveTypeInfo(declaredType, rawType, key);
      completeResolution(snapshot);
      return result;
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
      JsonTypeInfo result = resolveTypeInfo(declaredType, rawType, annotation);
      completeResolution(snapshot);
      return result;
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
          CollectionCodec.create(rawType, elementType.getRawType(), elementInfo, this));
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

  private void completeResolution(ResolutionSnapshot snapshot) {
    if (snapshot == null || codegen == null) {
      return;
    }
    ArrayList<JsonTypeInfo> roots = new ArrayList<>();
    for (Map.Entry<Object, JsonTypeInfo> entry : typeInfos.entrySet()) {
      if (!snapshot.typeKeys.contains(entry.getKey())) {
        roots.add(entry.getValue());
      }
    }
    if (!roots.isEmpty()) {
      requestCapabilities(roots);
    }
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
        collectionCodecs.remove(value);
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
      JsonTypeInfo result = resolveRuntimeTypeInfo(runtimeType, key);
      completeResolution(snapshot);
      return result;
    } catch (RuntimeException | Error e) {
      rollbackResolution(snapshot);
      throw e;
    } finally {
      endResolution();
    }
  }

  private JsonTypeInfo resolveRuntimeTypeInfo(Class<?> runtimeType, Object key) {
    JsonTypeInfo typeInfo = customTypeInfo(runtimeType, runtimeType);
    JsonValueCodec<?> codec = null;
    if (typeInfo == null) {
      sharedRegistry.checkSecure(runtimeType);
      TypeRef<?> typeRef = TypeRef.of(runtimeType);
      codec =
          runtimeType == Object.class
              ? getObjectCodec(typeRef)
              : sharedRegistry.createCodec(runtimeType, typeRef, this);
      if (codec == null) {
        codec = getObjectCodec(typeRef);
      }
      typeInfo = newTypeInfo(runtimeType, runtimeType, codec);
    }
    JsonTypeInfo recursiveTypeInfo = typeInfos.get(key);
    if (recursiveTypeInfo != null) {
      return recursiveTypeInfo;
    }
    typeInfos.put(key, typeInfo);
    if (codec != null) {
      registerTypeInfoOwner(typeInfo, codec);
    }
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
    return (StringWriterCodec<T>) typeInfo.stringWriter();
  }

  @SuppressWarnings("unchecked")
  public <T> Utf8WriterCodec<T> utf8Writer(ObjectCodec<T> codec) {
    requireJITLock();
    ObjectCodec<Object> owner = erase(codec);
    JsonTypeInfo typeInfo = canonicalObjectTypeInfos.get(owner);
    if (typeInfo == null) {
      return codec;
    }
    return (Utf8WriterCodec<T>) typeInfo.utf8Writer();
  }

  @SuppressWarnings("unchecked")
  public <T> Latin1ReaderCodec<T> latin1Reader(ObjectCodec<T> codec) {
    requireJITLock();
    ObjectCodec<Object> owner = erase(codec);
    JsonTypeInfo typeInfo = canonicalObjectTypeInfos.get(owner);
    if (typeInfo == null) {
      return codec;
    }
    return (Latin1ReaderCodec<T>) typeInfo.latin1Reader();
  }

  @SuppressWarnings("unchecked")
  public <T> Utf16ReaderCodec<T> utf16Reader(ObjectCodec<T> codec) {
    requireJITLock();
    ObjectCodec<Object> owner = erase(codec);
    JsonTypeInfo typeInfo = canonicalObjectTypeInfos.get(owner);
    if (typeInfo == null) {
      return codec;
    }
    return (Utf16ReaderCodec<T>) typeInfo.utf16Reader();
  }

  @SuppressWarnings("unchecked")
  public <T> Utf8ReaderCodec<T> utf8Reader(ObjectCodec<T> codec) {
    requireJITLock();
    ObjectCodec<Object> owner = erase(codec);
    JsonTypeInfo typeInfo = canonicalObjectTypeInfos.get(owner);
    if (typeInfo == null) {
      return codec;
    }
    return (Utf8ReaderCodec<T>) typeInfo.utf8Reader();
  }

  @SuppressWarnings("unchecked")
  private StringWriterCodec<Object> newStringWriter(
      ObjectCodec<?> owner,
      Class<?> generatedClass,
      IdentityMap<JsonTypeInfo, Object> capabilities) {
    if (owner.unwrappedInfo() != null) {
      return newUnwrappedStringWriter(owner, generatedClass, capabilities);
    }
    JsonFieldInfo[] fields = owner.writeFields();
    StringWriterCodec<Object>[] codecs =
        (StringWriterCodec<Object>[]) new StringWriterCodec<?>[fields.length];
    for (int i = 0; i < fields.length; i++) {
      JsonFieldInfo field = fields[i];
      if (JsonCodegen.usesWriteCodec(field)) {
        JsonTypeInfo typeInfo = field.writeTypeInfo();
        codecs[i] = resolvedCapability(typeInfo, capabilities, CapabilityKind.STRING_WRITER);
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
    StringWriterCodec<Object> anyCodec =
        resolvedCapability(any.valueTypeInfo(), capabilities, CapabilityKind.STRING_WRITER);
    return GeneratedCodecInstantiator.instantiateAnyStringWriter(
        generatedClass, owner, fields, codecs, anyCodec);
  }

  @SuppressWarnings("unchecked")
  private Utf8WriterCodec<Object> newUtf8Writer(
      ObjectCodec<?> owner,
      Class<?> generatedClass,
      IdentityMap<JsonTypeInfo, Object> capabilities) {
    if (owner.unwrappedInfo() != null) {
      return newUnwrappedUtf8Writer(owner, generatedClass, capabilities);
    }
    JsonFieldInfo[] fields = owner.writeFields();
    Utf8WriterCodec<Object>[] codecs =
        (Utf8WriterCodec<Object>[]) new Utf8WriterCodec<?>[fields.length];
    for (int i = 0; i < fields.length; i++) {
      JsonFieldInfo field = fields[i];
      if (JsonCodegen.usesWriteCodec(field)) {
        JsonTypeInfo typeInfo = field.writeTypeInfo();
        codecs[i] = resolvedCapability(typeInfo, capabilities, CapabilityKind.UTF8_WRITER);
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
    Utf8WriterCodec<Object> anyCodec =
        resolvedCapability(any.valueTypeInfo(), capabilities, CapabilityKind.UTF8_WRITER);
    return GeneratedCodecInstantiator.instantiateAnyUtf8Writer(
        generatedClass, owner, fields, codecs, anyCodec);
  }

  @SuppressWarnings("unchecked")
  private StringWriterCodec<Object> newUnwrappedStringWriter(
      ObjectCodec<?> owner,
      Class<?> generatedClass,
      IdentityMap<JsonTypeInfo, Object> capabilities) {
    JsonFieldInfo[] fields = unwrappedWriteFields(owner);
    StringWriterCodec<Object>[] codecs =
        (StringWriterCodec<Object>[]) new StringWriterCodec<?>[fields.length];
    for (int i = 0; i < fields.length; i++) {
      JsonFieldInfo field = fields[i];
      if (JsonCodegen.usesWriteCodec(field)) {
        JsonTypeInfo child = field.writeTypeInfo();
        codecs[i] = resolvedCapability(child, capabilities, CapabilityKind.STRING_WRITER);
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
    StringWriterCodec<Object> anyCodec =
        resolvedCapability(any.valueTypeInfo(), capabilities, CapabilityKind.STRING_WRITER);
    return GeneratedCodecInstantiator.instantiateAnyStringWriter(
        generatedClass, owner, fields, codecs, anyCodec);
  }

  @SuppressWarnings("unchecked")
  private Utf8WriterCodec<Object> newUnwrappedUtf8Writer(
      ObjectCodec<?> owner,
      Class<?> generatedClass,
      IdentityMap<JsonTypeInfo, Object> capabilities) {
    JsonFieldInfo[] fields = unwrappedWriteFields(owner);
    Utf8WriterCodec<Object>[] codecs =
        (Utf8WriterCodec<Object>[]) new Utf8WriterCodec<?>[fields.length];
    for (int i = 0; i < fields.length; i++) {
      JsonFieldInfo field = fields[i];
      if (JsonCodegen.usesWriteCodec(field)) {
        JsonTypeInfo child = field.writeTypeInfo();
        codecs[i] = resolvedCapability(child, capabilities, CapabilityKind.UTF8_WRITER);
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
    Utf8WriterCodec<Object> anyCodec =
        resolvedCapability(any.valueTypeInfo(), capabilities, CapabilityKind.UTF8_WRITER);
    return GeneratedCodecInstantiator.instantiateAnyUtf8Writer(
        generatedClass, owner, fields, codecs, anyCodec);
  }

  private static JsonFieldInfo[] unwrappedWriteFields(ObjectCodec<?> owner) {
    return owner.unwrappedInfo().writeFields();
  }

  @SuppressWarnings("unchecked")
  private Latin1ReaderCodec<Object> newLatin1Reader(
      ObjectCodec<?> owner,
      Class<?> generatedClass,
      IdentityMap<JsonTypeInfo, Object> capabilities) {
    return newLatin1Reader(owner, generatedClass, owner.readTable(), capabilities, null);
  }

  @SuppressWarnings("unchecked")
  private Latin1ReaderCodec<Object> newLatin1Reader(
      ObjectCodec<?> owner,
      Class<?> generatedClass,
      JsonFieldTable readTable,
      IdentityMap<JsonTypeInfo, Object> capabilities,
      Latin1ReaderCodec<Object> selfReader) {
    if (owner.unwrappedInfo() != null) {
      return newUnwrappedLatin1Reader(owner, generatedClass, readTable, capabilities, selfReader);
    }
    JsonFieldInfo[] fields = owner.readFields();
    JsonCreatorInfo creator = owner.creatorInfo();
    if (creator != null) {
      JsonCreatorFieldInfo[] creatorFields = creator.fields();
      Latin1ReaderCodec<Object>[] codecs =
          (Latin1ReaderCodec<Object>[]) new Latin1ReaderCodec<?>[creatorFields.length];
      for (int i = 0; i < creatorFields.length; i++) {
        codecs[i] =
            resolvedCapability(
                creatorFields[i].typeInfo(), capabilities, CapabilityKind.LATIN1_READER);
      }
      AnyInfo any = owner.anyInfo();
      if (any == null || any.readField() == null && any.readSetter() == null) {
        return GeneratedCodecInstantiator.instantiateLatin1Reader(
            generatedClass, owner, fields, codecs);
      }
      if (!storesAnyCodec(owner, any)) {
        return GeneratedCodecInstantiator.instantiateAnyLatin1Reader(
            generatedClass, owner, readTable, fields, codecs, selfReader);
      }
      Latin1ReaderCodec<Object> anyCodec =
          resolvedCapability(any.valueTypeInfo(), capabilities, CapabilityKind.LATIN1_READER);
      return GeneratedCodecInstantiator.instantiateAnyLatin1Reader(
          generatedClass, owner, readTable, fields, codecs, selfReader, anyCodec);
    }
    Latin1ReaderCodec<Object>[] codecs =
        (Latin1ReaderCodec<Object>[]) new Latin1ReaderCodec<?>[fields.length];
    for (int i = 0; i < fields.length; i++) {
      JsonFieldInfo field = fields[i];
      JsonTypeInfo typeInfo = field.readTypeInfo();
      if (JsonCodegen.usesReadCodec(field, this)) {
        codecs[i] = resolvedCapability(typeInfo, capabilities, CapabilityKind.LATIN1_READER);
      } else if (JsonCodegen.readNestedType(field, this) != null
          && field.readRawType() != owner.type()) {
        codecs[i] = resolvedCapability(typeInfo, capabilities, CapabilityKind.LATIN1_READER);
      }
    }
    AnyInfo any = owner.anyInfo();
    if (any == null || any.readField() == null && any.readSetter() == null) {
      return GeneratedCodecInstantiator.instantiateLatin1Reader(
          generatedClass, owner, fields, codecs);
    }
    if (!storesAnyCodec(owner, any)) {
      return GeneratedCodecInstantiator.instantiateAnyLatin1Reader(
          generatedClass, owner, readTable, fields, codecs, selfReader);
    }
    Latin1ReaderCodec<Object> anyCodec =
        resolvedCapability(any.valueTypeInfo(), capabilities, CapabilityKind.LATIN1_READER);
    return GeneratedCodecInstantiator.instantiateAnyLatin1Reader(
        generatedClass, owner, readTable, fields, codecs, selfReader, anyCodec);
  }

  @SuppressWarnings("unchecked")
  private Utf16ReaderCodec<Object> newUtf16Reader(
      ObjectCodec<?> owner,
      Class<?> generatedClass,
      IdentityMap<JsonTypeInfo, Object> capabilities) {
    return newUtf16Reader(owner, generatedClass, owner.readTable(), capabilities, null);
  }

  @SuppressWarnings("unchecked")
  private Utf16ReaderCodec<Object> newUtf16Reader(
      ObjectCodec<?> owner,
      Class<?> generatedClass,
      JsonFieldTable readTable,
      IdentityMap<JsonTypeInfo, Object> capabilities,
      Utf16ReaderCodec<Object> selfReader) {
    if (owner.unwrappedInfo() != null) {
      return newUnwrappedUtf16Reader(owner, generatedClass, readTable, capabilities, selfReader);
    }
    JsonFieldInfo[] fields = owner.readFields();
    JsonCreatorInfo creator = owner.creatorInfo();
    if (creator != null) {
      JsonCreatorFieldInfo[] creatorFields = creator.fields();
      Utf16ReaderCodec<Object>[] codecs =
          (Utf16ReaderCodec<Object>[]) new Utf16ReaderCodec<?>[creatorFields.length];
      for (int i = 0; i < creatorFields.length; i++) {
        codecs[i] =
            resolvedCapability(
                creatorFields[i].typeInfo(), capabilities, CapabilityKind.UTF16_READER);
      }
      AnyInfo any = owner.anyInfo();
      if (any == null || any.readField() == null && any.readSetter() == null) {
        return GeneratedCodecInstantiator.instantiateUtf16Reader(
            generatedClass, owner, fields, codecs);
      }
      if (!storesAnyCodec(owner, any)) {
        return GeneratedCodecInstantiator.instantiateAnyUtf16Reader(
            generatedClass, owner, readTable, fields, codecs, selfReader);
      }
      Utf16ReaderCodec<Object> anyCodec =
          resolvedCapability(any.valueTypeInfo(), capabilities, CapabilityKind.UTF16_READER);
      return GeneratedCodecInstantiator.instantiateAnyUtf16Reader(
          generatedClass, owner, readTable, fields, codecs, selfReader, anyCodec);
    }
    Utf16ReaderCodec<Object>[] codecs =
        (Utf16ReaderCodec<Object>[]) new Utf16ReaderCodec<?>[fields.length];
    for (int i = 0; i < fields.length; i++) {
      JsonFieldInfo field = fields[i];
      JsonTypeInfo typeInfo = field.readTypeInfo();
      if (JsonCodegen.usesReadCodec(field, this)) {
        codecs[i] = resolvedCapability(typeInfo, capabilities, CapabilityKind.UTF16_READER);
      } else if (JsonCodegen.readNestedType(field, this) != null
          && field.readRawType() != owner.type()) {
        codecs[i] = resolvedCapability(typeInfo, capabilities, CapabilityKind.UTF16_READER);
      }
    }
    AnyInfo any = owner.anyInfo();
    if (any == null || any.readField() == null && any.readSetter() == null) {
      return GeneratedCodecInstantiator.instantiateUtf16Reader(
          generatedClass, owner, fields, codecs);
    }
    if (!storesAnyCodec(owner, any)) {
      return GeneratedCodecInstantiator.instantiateAnyUtf16Reader(
          generatedClass, owner, readTable, fields, codecs, selfReader);
    }
    Utf16ReaderCodec<Object> anyCodec =
        resolvedCapability(any.valueTypeInfo(), capabilities, CapabilityKind.UTF16_READER);
    return GeneratedCodecInstantiator.instantiateAnyUtf16Reader(
        generatedClass, owner, readTable, fields, codecs, selfReader, anyCodec);
  }

  @SuppressWarnings("unchecked")
  private Utf8ReaderCodec<Object> newUtf8Reader(
      ObjectCodec<?> owner,
      Class<?> generatedClass,
      IdentityMap<JsonTypeInfo, Object> capabilities) {
    return newUtf8Reader(owner, generatedClass, owner.readTable(), capabilities, null);
  }

  @SuppressWarnings("unchecked")
  private Utf8ReaderCodec<Object> newUtf8Reader(
      ObjectCodec<?> owner,
      Class<?> generatedClass,
      JsonFieldTable readTable,
      IdentityMap<JsonTypeInfo, Object> capabilities,
      Utf8ReaderCodec<Object> selfReader) {
    if (owner.unwrappedInfo() != null) {
      return newUnwrappedUtf8Reader(owner, generatedClass, readTable, capabilities, selfReader);
    }
    JsonFieldInfo[] fields = owner.readFields();
    JsonCreatorInfo creator = owner.creatorInfo();
    if (creator != null) {
      JsonCreatorFieldInfo[] creatorFields = creator.fields();
      Utf8ReaderCodec<Object>[] codecs =
          (Utf8ReaderCodec<Object>[]) new Utf8ReaderCodec<?>[creatorFields.length];
      for (int i = 0; i < creatorFields.length; i++) {
        codecs[i] =
            resolvedCapability(
                creatorFields[i].typeInfo(), capabilities, CapabilityKind.UTF8_READER);
      }
      AnyInfo any = owner.anyInfo();
      if (any == null || any.readField() == null && any.readSetter() == null) {
        return GeneratedCodecInstantiator.instantiateUtf8Reader(
            generatedClass, owner, fields, codecs);
      }
      if (!storesAnyCodec(owner, any)) {
        return GeneratedCodecInstantiator.instantiateAnyUtf8Reader(
            generatedClass, owner, readTable, fields, codecs, selfReader);
      }
      Utf8ReaderCodec<Object> anyCodec =
          resolvedCapability(any.valueTypeInfo(), capabilities, CapabilityKind.UTF8_READER);
      return GeneratedCodecInstantiator.instantiateAnyUtf8Reader(
          generatedClass, owner, readTable, fields, codecs, selfReader, anyCodec);
    }
    Utf8ReaderCodec<Object>[] codecs =
        (Utf8ReaderCodec<Object>[]) new Utf8ReaderCodec<?>[fields.length];
    for (int i = 0; i < fields.length; i++) {
      JsonFieldInfo field = fields[i];
      JsonTypeInfo typeInfo = field.readTypeInfo();
      if (JsonCodegen.usesReadCodec(field, this)) {
        codecs[i] = resolvedCapability(typeInfo, capabilities, CapabilityKind.UTF8_READER);
      } else if (JsonCodegen.readNestedType(field, this) != null
          && field.readRawType() != owner.type()) {
        codecs[i] = resolvedCapability(typeInfo, capabilities, CapabilityKind.UTF8_READER);
      }
    }
    AnyInfo any = owner.anyInfo();
    if (any == null || any.readField() == null && any.readSetter() == null) {
      return GeneratedCodecInstantiator.instantiateUtf8Reader(
          generatedClass, owner, fields, codecs);
    }
    if (!storesAnyCodec(owner, any)) {
      return GeneratedCodecInstantiator.instantiateAnyUtf8Reader(
          generatedClass, owner, readTable, fields, codecs, selfReader);
    }
    Utf8ReaderCodec<Object> anyCodec =
        resolvedCapability(any.valueTypeInfo(), capabilities, CapabilityKind.UTF8_READER);
    return GeneratedCodecInstantiator.instantiateAnyUtf8Reader(
        generatedClass, owner, readTable, fields, codecs, selfReader, anyCodec);
  }

  @SuppressWarnings("unchecked")
  private Latin1ReaderCodec<Object> newUnwrappedLatin1Reader(
      ObjectCodec<?> owner,
      Class<?> generatedClass,
      JsonFieldTable readTable,
      IdentityMap<JsonTypeInfo, Object> capabilities,
      Latin1ReaderCodec<Object> selfReader) {
    JsonFieldInfo[] fields = unwrappedReadFields(owner);
    JsonTypeInfo[] children = unwrappedReadTypeInfos(owner);
    Latin1ReaderCodec<Object>[] codecs =
        (Latin1ReaderCodec<Object>[]) new Latin1ReaderCodec<?>[children.length];
    for (int i = 0; i < children.length; i++) {
      JsonTypeInfo child = children[i];
      codecs[i] = resolvedCapability(child, capabilities, CapabilityKind.LATIN1_READER);
    }
    AnyInfo any = owner.anyInfo();
    if (any == null || any.readField() == null && any.readSetter() == null) {
      return GeneratedCodecInstantiator.instantiateLatin1Reader(
          generatedClass, owner, fields, codecs);
    }
    if (!storesAnyCodec(owner, any)) {
      return GeneratedCodecInstantiator.instantiateAnyLatin1Reader(
          generatedClass, owner, readTable, fields, codecs, selfReader);
    }
    Latin1ReaderCodec<Object> anyCodec =
        resolvedCapability(any.valueTypeInfo(), capabilities, CapabilityKind.LATIN1_READER);
    return GeneratedCodecInstantiator.instantiateAnyLatin1Reader(
        generatedClass, owner, readTable, fields, codecs, selfReader, anyCodec);
  }

  @SuppressWarnings("unchecked")
  private Utf16ReaderCodec<Object> newUnwrappedUtf16Reader(
      ObjectCodec<?> owner,
      Class<?> generatedClass,
      JsonFieldTable readTable,
      IdentityMap<JsonTypeInfo, Object> capabilities,
      Utf16ReaderCodec<Object> selfReader) {
    JsonFieldInfo[] fields = unwrappedReadFields(owner);
    JsonTypeInfo[] children = unwrappedReadTypeInfos(owner);
    Utf16ReaderCodec<Object>[] codecs =
        (Utf16ReaderCodec<Object>[]) new Utf16ReaderCodec<?>[children.length];
    for (int i = 0; i < children.length; i++) {
      JsonTypeInfo child = children[i];
      codecs[i] = resolvedCapability(child, capabilities, CapabilityKind.UTF16_READER);
    }
    AnyInfo any = owner.anyInfo();
    if (any == null || any.readField() == null && any.readSetter() == null) {
      return GeneratedCodecInstantiator.instantiateUtf16Reader(
          generatedClass, owner, fields, codecs);
    }
    if (!storesAnyCodec(owner, any)) {
      return GeneratedCodecInstantiator.instantiateAnyUtf16Reader(
          generatedClass, owner, readTable, fields, codecs, selfReader);
    }
    Utf16ReaderCodec<Object> anyCodec =
        resolvedCapability(any.valueTypeInfo(), capabilities, CapabilityKind.UTF16_READER);
    return GeneratedCodecInstantiator.instantiateAnyUtf16Reader(
        generatedClass, owner, readTable, fields, codecs, selfReader, anyCodec);
  }

  @SuppressWarnings("unchecked")
  private Utf8ReaderCodec<Object> newUnwrappedUtf8Reader(
      ObjectCodec<?> owner,
      Class<?> generatedClass,
      JsonFieldTable readTable,
      IdentityMap<JsonTypeInfo, Object> capabilities,
      Utf8ReaderCodec<Object> selfReader) {
    JsonFieldInfo[] fields = unwrappedReadFields(owner);
    JsonTypeInfo[] children = unwrappedReadTypeInfos(owner);
    Utf8ReaderCodec<Object>[] codecs =
        (Utf8ReaderCodec<Object>[]) new Utf8ReaderCodec<?>[children.length];
    for (int i = 0; i < children.length; i++) {
      JsonTypeInfo child = children[i];
      codecs[i] = resolvedCapability(child, capabilities, CapabilityKind.UTF8_READER);
    }
    AnyInfo any = owner.anyInfo();
    if (any == null || any.readField() == null && any.readSetter() == null) {
      return GeneratedCodecInstantiator.instantiateUtf8Reader(
          generatedClass, owner, fields, codecs);
    }
    if (!storesAnyCodec(owner, any)) {
      return GeneratedCodecInstantiator.instantiateAnyUtf8Reader(
          generatedClass, owner, readTable, fields, codecs, selfReader);
    }
    Utf8ReaderCodec<Object> anyCodec =
        resolvedCapability(any.valueTypeInfo(), capabilities, CapabilityKind.UTF8_READER);
    return GeneratedCodecInstantiator.instantiateAnyUtf8Reader(
        generatedClass, owner, readTable, fields, codecs, selfReader, anyCodec);
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

  private boolean storesAnyCodec(ObjectCodec<?> owner, AnyInfo any) {
    return canonicalObjectCodec(any.valueTypeInfo()) == null || any.valueRawType() != owner.type();
  }

  private enum CapabilityKind {
    STRING_WRITER,
    UTF8_WRITER,
    LATIN1_READER,
    UTF16_READER,
    UTF8_READER
  }

  private ArrayList<JsonTypeInfo> capabilityChildren(ObjectCodec<?> owner, CapabilityKind kind) {
    ArrayList<JsonTypeInfo> children = new ArrayList<>();
    AnyInfo any = owner.anyInfo();
    boolean writer = kind == CapabilityKind.STRING_WRITER || kind == CapabilityKind.UTF8_WRITER;
    if (writer) {
      JsonFieldInfo[] fields =
          owner.unwrappedInfo() == null ? owner.writeFields() : unwrappedWriteFields(owner);
      for (int i = 0; i < fields.length; i++) {
        JsonFieldInfo field = fields[i];
        if (JsonCodegen.usesWriteCodec(field)
            && (field.writeRawType() != owner.type()
                || canonicalObjectOwner(field.writeTypeInfo()) == null)) {
          children.add(field.writeTypeInfo());
        }
      }
      if (any != null
          && (any.writeField() != null || any.writeGetter() != null)
          && storesAnyCodec(owner, any)) {
        children.add(any.valueTypeInfo());
      }
      return children;
    }
    if (owner.unwrappedInfo() != null) {
      JsonCreatorInfo creator = owner.creatorInfo();
      if (creator == null) {
        JsonFieldInfo[] fields = owner.readFields();
        for (int i = 0; i < fields.length; i++) {
          addReadDependency(children, owner, fields[i]);
        }
      } else {
        JsonCreatorFieldInfo[] fields = creator.fields();
        for (int i = 0; i < fields.length; i++) {
          children.add(fields[i].typeInfo());
        }
      }
      JsonUnwrappedInfo.ReadRoute[] routes = owner.unwrappedInfo().readRoutes();
      for (int i = 0; i < routes.length; i++) {
        JsonUnwrappedInfo.ReadRoute route = routes[i];
        if (route.field() == null) {
          children.add(route.creatorField().typeInfo());
        } else {
          addReadDependency(children, owner, route.field());
        }
      }
    } else if (owner.creatorInfo() == null) {
      JsonFieldInfo[] fields = owner.readFields();
      for (int i = 0; i < fields.length; i++) {
        addReadDependency(children, owner, fields[i]);
      }
    } else {
      JsonCreatorFieldInfo[] fields = owner.creatorInfo().fields();
      for (int i = 0; i < fields.length; i++) {
        children.add(fields[i].typeInfo());
      }
    }
    if (any != null
        && (any.readField() != null || any.readSetter() != null)
        && storesAnyCodec(owner, any)) {
      children.add(any.valueTypeInfo());
    }
    return children;
  }

  private void addReadDependency(
      ArrayList<JsonTypeInfo> children, ObjectCodec<?> owner, JsonFieldInfo field) {
    if (JsonCodegen.usesReadCodec(field, this)
        || JsonCodegen.readNestedType(field, this) != null && field.readRawType() != owner.type()) {
      children.add(field.readTypeInfo());
    }
  }

  /** Returns whether a generated writer must traverse this cyclic edge through its type slot. */
  @Internal
  public boolean usesWriterSlot(Class<?> ownerType, JsonTypeInfo child) {
    jitContext.lock();
    try {
      JsonTypeInfo owner = rawObjectTypeInfos.get(ownerType);
      return owner != null
          && child != owner
          && canonicalObjectOwner(child) != null
          && reachesWriter(child, owner, new IdentityMap<>());
    } finally {
      jitContext.unlock();
    }
  }

  /** Returns whether a generated reader must traverse this cyclic edge through its type slot. */
  @Internal
  public boolean usesReaderSlot(Class<?> ownerType, JsonTypeInfo child) {
    jitContext.lock();
    try {
      JsonTypeInfo ownerInfo = rawObjectTypeInfos.get(ownerType);
      ObjectCodec<?> owner = ownerInfo == null ? null : canonicalObjectOwner(ownerInfo);
      return owner != null
          && owner.unwrappedInfo() == null
          && child != ownerInfo
          && canonicalObjectOwner(child) != null
          && reachesReader(child, ownerInfo, new IdentityMap<>());
    } finally {
      jitContext.unlock();
    }
  }

  private boolean reachesWriter(
      JsonTypeInfo current, JsonTypeInfo target, IdentityMap<JsonTypeInfo, Boolean> visited) {
    if (current == target) {
      return true;
    }
    if (visited.put(current, Boolean.TRUE) != null) {
      return false;
    }
    ObjectCodec<?> owner = canonicalObjectOwner(current);
    if (owner != null) {
      ArrayList<JsonTypeInfo> children = capabilityChildren(owner, CapabilityKind.STRING_WRITER);
      for (int i = 0; i < children.size(); i++) {
        JsonTypeInfo child = children.get(i);
        if (child == target
            || canonicalObjectOwner(child) != null && reachesWriter(child, target, visited)) {
          return true;
        }
        Object capability = child.stringWriter();
        if (capability instanceof ClosedSubtypeCodec
            && reachesWriter((ClosedSubtypeCodec) capability, target, visited)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean reachesWriter(
      ClosedSubtypeCodec subtype, JsonTypeInfo target, IdentityMap<JsonTypeInfo, Boolean> visited) {
    for (int i = 0; i < subtype.childCount(); i++) {
      JsonTypeInfo child = subtype.child(i);
      if (child == target || reachesWriter(child, target, visited)) {
        return true;
      }
    }
    return false;
  }

  private boolean reachesReader(
      JsonTypeInfo current, JsonTypeInfo target, IdentityMap<JsonTypeInfo, Boolean> visited) {
    if (current == target) {
      return true;
    }
    if (visited.put(current, Boolean.TRUE) != null) {
      return false;
    }
    ObjectCodec<?> owner = canonicalObjectOwner(current);
    if (owner != null && owner.unwrappedInfo() == null) {
      ArrayList<JsonTypeInfo> children = capabilityChildren(owner, CapabilityKind.UTF8_READER);
      for (int i = 0; i < children.size(); i++) {
        JsonTypeInfo child = children.get(i);
        if (child == target
            || canonicalObjectOwner(child) != null && reachesReader(child, target, visited)) {
          return true;
        }
        Object capability = child.utf8Reader();
        if (capability instanceof ClosedSubtypeCodec
            && reachesReader((ClosedSubtypeCodec) capability, target, visited)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean reachesReader(
      ClosedSubtypeCodec subtype, JsonTypeInfo target, IdentityMap<JsonTypeInfo, Boolean> visited) {
    for (int i = 0; i < subtype.childCount(); i++) {
      JsonTypeInfo child = subtype.child(i);
      if (child == target || reachesReader(child, target, visited)) {
        return true;
      }
    }
    return false;
  }

  private boolean canCompile(ObjectCodec<?> owner, CapabilityKind kind) {
    return kind == CapabilityKind.STRING_WRITER || kind == CapabilityKind.UTF8_WRITER
        ? codegen.canCompileWriter(owner)
        : codegen.canCompileReader(owner);
  }

  private static Object currentCapability(JsonTypeInfo typeInfo, CapabilityKind kind) {
    switch (kind) {
      case STRING_WRITER:
        return typeInfo.stringWriter();
      case UTF8_WRITER:
        return typeInfo.utf8Writer();
      case LATIN1_READER:
        return typeInfo.latin1Reader();
      case UTF16_READER:
        return typeInfo.utf16Reader();
      case UTF8_READER:
        return typeInfo.utf8Reader();
      default:
        throw new IllegalStateException("Unknown JSON capability kind " + kind);
    }
  }

  private CompletableFuture<Class<?>> generatedClass(CapabilityNode node, CapabilityKind kind) {
    if (node.subtypeOwner != null) {
      throw new IllegalStateException("Inline subtype readers reuse child generated classes");
    }
    if (node.collectionOwner != null) {
      return sharedRegistry.utf8CollectionReaderClass(node.typeInfo.type(), node.collectionOwner);
    }
    switch (kind) {
      case STRING_WRITER:
        return sharedRegistry.stringWriterClass(node.objectOwner, this);
      case UTF8_WRITER:
        return sharedRegistry.utf8WriterClass(node.objectOwner, this);
      case LATIN1_READER:
        return sharedRegistry.latin1ReaderClass(node.objectOwner, this);
      case UTF16_READER:
        return sharedRegistry.utf16ReaderClass(node.objectOwner, this);
      case UTF8_READER:
        return sharedRegistry.utf8ReaderClass(node.objectOwner, this);
      default:
        throw new IllegalStateException("Unknown JSON capability kind " + kind);
    }
  }

  private Object newCapability(
      CapabilityNode node,
      Class<?> generatedClass,
      IdentityMap<JsonTypeInfo, Object> capabilities,
      CapabilityKind kind) {
    if (node.subtypeOwner != null) {
      return newSubtypeReaders(node.subtypeOwner, capabilities, kind);
    }
    if (node.collectionOwner != null) {
      JsonTypeInfo element = declaredCollectionElement(node.typeInfo);
      Utf8ReaderCodec<Object> elementReader = resolvedCapability(element, capabilities, kind);
      return GeneratedCodecInstantiator.instantiateUtf8CollectionReader(
          generatedClass, elementReader);
    }
    switch (kind) {
      case STRING_WRITER:
        return newStringWriter(node.objectOwner, generatedClass, capabilities);
      case UTF8_WRITER:
        return newUtf8Writer(node.objectOwner, generatedClass, capabilities);
      case LATIN1_READER:
        return newLatin1Reader(node.objectOwner, generatedClass, capabilities);
      case UTF16_READER:
        return newUtf16Reader(node.objectOwner, generatedClass, capabilities);
      case UTF8_READER:
        return newUtf8Reader(node.objectOwner, generatedClass, capabilities);
      default:
        throw new IllegalStateException("Unknown JSON capability kind " + kind);
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T resolvedCapability(
      JsonTypeInfo typeInfo, IdentityMap<JsonTypeInfo, Object> capabilities, CapabilityKind kind) {
    Object capability = capabilities.get(typeInfo);
    if (capability != null) {
      return (T) capability;
    }
    return (T) currentCapability(typeInfo, kind);
  }

  private static void installCapability(
      JsonTypeInfo typeInfo, Object capability, CapabilityKind kind) {
    switch (kind) {
      case STRING_WRITER:
        typeInfo.setStringWriter((StringWriterCodec<Object>) capability);
        return;
      case UTF8_WRITER:
        typeInfo.setUtf8Writer((Utf8WriterCodec<Object>) capability);
        return;
      case LATIN1_READER:
        typeInfo.setLatin1Reader((Latin1ReaderCodec<Object>) capability);
        return;
      case UTF16_READER:
        typeInfo.setUtf16Reader((Utf16ReaderCodec<Object>) capability);
        return;
      case UTF8_READER:
        typeInfo.setUtf8Reader((Utf8ReaderCodec<Object>) capability);
        return;
      default:
        throw new IllegalStateException("Unknown JSON capability kind " + kind);
    }
  }

  @SuppressWarnings("unchecked")
  private Object newSubtypeReaders(
      ClosedSubtypeCodec subtype,
      IdentityMap<JsonTypeInfo, Object> capabilities,
      CapabilityKind kind) {
    int childCount = subtype.childCount();
    switch (kind) {
      case LATIN1_READER:
        Latin1ReaderCodec<Object>[] latin1Readers =
            (Latin1ReaderCodec<Object>[]) new Latin1ReaderCodec<?>[childCount];
        for (int i = 0; i < childCount; i++) {
          JsonFieldTable table = subtype.inlineReadTable(i);
          if (table != null) {
            JsonTypeInfo child = subtype.child(i);
            ObjectCodec<Object> owner = erase(requireObjectOwner(child));
            Latin1ReaderCodec<Object> canonical = resolvedCapability(child, capabilities, kind);
            latin1Readers[i] =
                newLatin1Reader(owner, canonical.getClass(), table, capabilities, canonical);
          }
        }
        return latin1Readers;
      case UTF16_READER:
        Utf16ReaderCodec<Object>[] utf16Readers =
            (Utf16ReaderCodec<Object>[]) new Utf16ReaderCodec<?>[childCount];
        for (int i = 0; i < childCount; i++) {
          JsonFieldTable table = subtype.inlineReadTable(i);
          if (table != null) {
            JsonTypeInfo child = subtype.child(i);
            ObjectCodec<Object> owner = erase(requireObjectOwner(child));
            Utf16ReaderCodec<Object> canonical = resolvedCapability(child, capabilities, kind);
            utf16Readers[i] =
                newUtf16Reader(owner, canonical.getClass(), table, capabilities, canonical);
          }
        }
        return utf16Readers;
      case UTF8_READER:
        Utf8ReaderCodec<Object>[] utf8Readers =
            (Utf8ReaderCodec<Object>[]) new Utf8ReaderCodec<?>[childCount];
        for (int i = 0; i < childCount; i++) {
          JsonFieldTable table = subtype.inlineReadTable(i);
          if (table != null) {
            JsonTypeInfo child = subtype.child(i);
            ObjectCodec<Object> owner = erase(requireObjectOwner(child));
            Utf8ReaderCodec<Object> canonical = resolvedCapability(child, capabilities, kind);
            utf8Readers[i] =
                newUtf8Reader(owner, canonical.getClass(), table, capabilities, canonical);
          }
        }
        return utf8Readers;
      default:
        throw new IllegalStateException("Writer graph cannot construct inline subtype readers");
    }
  }

  private ObjectCodec<?> requireObjectOwner(JsonTypeInfo typeInfo) {
    ObjectCodec<?> owner = canonicalObjectOwner(typeInfo);
    if (owner == null) {
      throw new IllegalStateException(
          "Inline subtype lost its canonical object owner: " + typeInfo.rawType().getName());
    }
    return owner;
  }

  private static boolean readerKind(CapabilityKind kind) {
    return kind == CapabilityKind.LATIN1_READER
        || kind == CapabilityKind.UTF16_READER
        || kind == CapabilityKind.UTF8_READER;
  }

  private static boolean hasInlineReadTable(ClosedSubtypeCodec subtype) {
    for (int i = 0; i < subtype.childCount(); i++) {
      if (subtype.inlineReadTable(i) != null) {
        return true;
      }
    }
    return false;
  }

  private static Object currentSubtypeReaders(ClosedSubtypeCodec subtype, CapabilityKind kind) {
    switch (kind) {
      case LATIN1_READER:
        return subtype.inlineLatin1Readers();
      case UTF16_READER:
        return subtype.inlineUtf16Readers();
      case UTF8_READER:
        return subtype.inlineUtf8Readers();
      default:
        throw new IllegalStateException("Writer graph has no inline subtype readers");
    }
  }

  @SuppressWarnings("unchecked")
  private static void installSubtypeReaders(
      ClosedSubtypeCodec subtype, Object readers, CapabilityKind kind) {
    switch (kind) {
      case LATIN1_READER:
        subtype.installInlineLatin1Readers((Latin1ReaderCodec<Object>[]) readers);
        return;
      case UTF16_READER:
        subtype.installInlineUtf16Readers((Utf16ReaderCodec<Object>[]) readers);
        return;
      case UTF8_READER:
        subtype.installInlineUtf8Readers((Utf8ReaderCodec<Object>[]) readers);
        return;
      default:
        throw new IllegalStateException("Writer graph has no inline subtype readers");
    }
  }

  private void requestCapabilities(ArrayList<JsonTypeInfo> roots) {
    for (CapabilityKind kind : CapabilityKind.values()) {
      CapabilityGraph graph = new CapabilityGraph(kind);
      if (graph.addRoots(roots) && !graph.ordered.isEmpty()) {
        requestGraph(graph);
      }
    }
  }

  private void requestGraph(CapabilityGraph graph) {
    jitContext.registerJITFuture(
        () -> graph.classesReady().thenApply(ignored -> graph),
        new JsonJITContext.JITCallback<CapabilityGraph>() {
          @Override
          public void onSuccess(CapabilityGraph result) {
            result.publish();
          }

          @Override
          public void onFailure(Throwable failure) {}

          @Override
          public Object id() {
            return graph;
          }
        });
  }

  /**
   * One representation graph whose constructor dependencies are acyclic after canonical
   * multi-object cycles become slot edges. Every class future is submitted before dependency order
   * is applied to resolver-local instance construction and one lock-held publication loop.
   */
  private final class CapabilityGraph {
    private final CapabilityKind kind;
    private final IdentityMap<JsonTypeInfo, CapabilityNode> nodes = new IdentityMap<>();
    private final IdentityMap<ClosedSubtypeCodec, Boolean> subtypes = new IdentityMap<>();
    private final ArrayList<CapabilityNode> ordered = new ArrayList<>();

    private CapabilityGraph(CapabilityKind kind) {
      this.kind = kind;
    }

    private boolean addRoots(ArrayList<JsonTypeInfo> roots) {
      for (int i = 0; i < roots.size(); i++) {
        if (!addDependency(roots.get(i))) {
          return false;
        }
      }
      return true;
    }

    private boolean addDependency(JsonTypeInfo typeInfo) {
      return addDependency(typeInfo, false);
    }

    private boolean addDependency(JsonTypeInfo typeInfo, boolean slotEdge) {
      ObjectCodec<?> objectOwner = canonicalObjectOwner(typeInfo);
      if (objectOwner != null) {
        return addObject(objectOwner, typeInfo, slotEdge);
      }
      if (kind == CapabilityKind.UTF8_READER) {
        CollectionCodec<?> collectionOwner = exactUtf8CollectionOwner(typeInfo);
        if (collectionOwner != null) {
          return addCollection(collectionOwner, typeInfo);
        }
      }
      Object capability = currentCapability(typeInfo, kind);
      return !(capability instanceof ClosedSubtypeCodec)
          || addSubtype(typeInfo, (ClosedSubtypeCodec) capability);
    }

    private boolean addSubtype(JsonTypeInfo typeInfo, ClosedSubtypeCodec subtype) {
      Boolean complete = subtypes.get(subtype);
      if (complete != null) {
        return complete;
      }
      subtypes.put(subtype, Boolean.FALSE);
      for (int i = 0; i < subtype.childCount(); i++) {
        if (!addDependency(subtype.child(i))) {
          return false;
        }
      }
      subtypes.put(subtype, Boolean.TRUE);
      if (readerKind(kind) && hasInlineReadTable(subtype)) {
        Object initial = currentSubtypeReaders(subtype, kind);
        if (initial == null) {
          ordered.add(new CapabilityNode(typeInfo, subtype, initial));
        }
      }
      return true;
    }

    private boolean addObject(ObjectCodec<?> rawOwner, JsonTypeInfo typeInfo, boolean slotEdge) {
      ObjectCodec<Object> owner = erase(rawOwner);
      Object initial = currentCapability(typeInfo, kind);
      if (initial != owner) {
        return true;
      }
      CapabilityNode existing = nodes.get(typeInfo);
      if (existing != null) {
        return existing.complete || slotEdge;
      }
      if (!canCompile(owner, kind)) {
        return false;
      }
      CapabilityNode node = new CapabilityNode(typeInfo, owner, initial);
      nodes.put(typeInfo, node);
      ArrayList<JsonTypeInfo> children = capabilityChildren(owner, kind);
      for (int i = 0; i < children.size(); i++) {
        JsonTypeInfo child = children.get(i);
        boolean writer = kind == CapabilityKind.STRING_WRITER || kind == CapabilityKind.UTF8_WRITER;
        boolean childSlot =
            writer ? usesWriterSlot(owner.type(), child) : usesReaderSlot(owner.type(), child);
        if (!addDependency(child, childSlot)) {
          return false;
        }
      }
      node.complete = true;
      ordered.add(node);
      return true;
    }

    private boolean addCollection(CollectionCodec<?> owner, JsonTypeInfo typeInfo) {
      Object initial = currentCapability(typeInfo, kind);
      if (initial != owner) {
        return true;
      }
      CapabilityNode existing = nodes.get(typeInfo);
      if (existing != null) {
        return existing.complete;
      }
      JsonTypeInfo element = declaredCollectionElement(typeInfo);
      if (element == null
          || element.rawType() == Object.class
          || element.usesAnnotationCodec()
          || element.kind() == JsonFieldKind.OBJECT
              && canonicalObjectOwner(element) == null
              && !(element.utf8Reader() instanceof ClosedSubtypeCodec)) {
        return false;
      }
      CapabilityNode node = new CapabilityNode(typeInfo, owner, initial);
      nodes.put(typeInfo, node);
      if (!addDependency(element)) {
        return false;
      }
      node.complete = true;
      ordered.add(node);
      return true;
    }

    private CompletableFuture<Void> classesReady() {
      ArrayList<CompletableFuture<?>> futures = new ArrayList<>();
      for (int i = 0; i < ordered.size(); i++) {
        CapabilityNode node = ordered.get(i);
        if (node.subtypeOwner != null) {
          continue;
        }
        node.classFuture = generatedClass(node, kind);
        futures.add(node.classFuture);
      }
      return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]));
    }

    private void publish() {
      requireJITLock();
      IdentityMap<JsonTypeInfo, Object> capabilities = new IdentityMap<>();
      ArrayList<CapabilityNode> unpublished = new ArrayList<>();
      for (int i = 0; i < ordered.size(); i++) {
        CapabilityNode node = ordered.get(i);
        if (!node.metadataMatches(kind)) {
          return;
        }
        Object current = node.current(kind);
        if (current != node.initial) {
          if (node.subtypeOwner == null) {
            capabilities.put(node.typeInfo, current);
          }
          continue;
        }
        Class<?> generatedClass = null;
        if (node.subtypeOwner == null) {
          generatedClass = node.classFuture.getNow(null);
          if (generatedClass == null) {
            throw new IllegalStateException("Generated JSON class is not ready");
          }
        }
        node.instance = newCapability(node, generatedClass, capabilities, kind);
        if (node.subtypeOwner == null) {
          capabilities.put(node.typeInfo, node.instance);
        }
        unpublished.add(node);
      }
      for (int i = 0; i < unpublished.size(); i++) {
        CapabilityNode node = unpublished.get(i);
        if (!node.metadataMatches(kind) || node.current(kind) != node.initial) {
          return;
        }
      }
      for (int i = 0; i < unpublished.size(); i++) {
        CapabilityNode node = unpublished.get(i);
        node.install(kind);
      }
    }
  }

  private final class CapabilityNode {
    private final JsonTypeInfo typeInfo;
    private final ObjectCodec<Object> objectOwner;
    private final CollectionCodec<?> collectionOwner;
    private final ClosedSubtypeCodec subtypeOwner;
    private final Object initial;
    private boolean complete;
    private CompletableFuture<Class<?>> classFuture;
    private Object instance;

    private CapabilityNode(JsonTypeInfo typeInfo, ObjectCodec<Object> owner, Object initial) {
      this.typeInfo = typeInfo;
      objectOwner = owner;
      collectionOwner = null;
      subtypeOwner = null;
      this.initial = initial;
    }

    private CapabilityNode(JsonTypeInfo typeInfo, CollectionCodec<?> owner, Object initial) {
      this.typeInfo = typeInfo;
      objectOwner = null;
      collectionOwner = owner;
      subtypeOwner = null;
      this.initial = initial;
    }

    private CapabilityNode(JsonTypeInfo typeInfo, ClosedSubtypeCodec owner, Object initial) {
      this.typeInfo = typeInfo;
      objectOwner = null;
      collectionOwner = null;
      subtypeOwner = owner;
      this.initial = initial;
    }

    private boolean metadataMatches(CapabilityKind kind) {
      if (subtypeOwner != null) {
        return currentCapability(typeInfo, kind) == subtypeOwner;
      }
      if (objectOwner != null) {
        return canonicalObjectOwner(typeInfo) == objectOwner;
      }
      return collectionCodecs.get(typeInfo) == collectionOwner
          && typeInfos.get(typeInfoKey(typeInfo.type(), typeInfo.rawType())) == typeInfo;
    }

    private Object current(CapabilityKind kind) {
      return subtypeOwner == null
          ? currentCapability(typeInfo, kind)
          : currentSubtypeReaders(subtypeOwner, kind);
    }

    private void install(CapabilityKind kind) {
      if (subtypeOwner == null) {
        installCapability(typeInfo, instance, kind);
      } else {
        installSubtypeReaders(subtypeOwner, instance, kind);
      }
    }
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
        sharedRegistry,
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
    registerTypeInfoOwner(typeInfo, codec);
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
      registerTypeInfoOwner(typeInfo, codec);
      codec.resolveTypes(this);
      return typeInfo;
    }
    // A public getObjectCodec call may already own construction of this shell. Bind its type info
    // now; the outer owner finishes field resolution before returning the codec to its caller.
    typeInfo = newTypeInfo(ownerType.getType(), ownerType.getRawType(), codec);
    typeInfos.put(key, typeInfo);
    registerTypeInfoOwner(typeInfo, codec);
    return typeInfo;
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

  private void registerTypeInfoOwner(JsonTypeInfo typeInfo, JsonValueCodec<?> initialCodec) {
    if (initialCodec instanceof CollectionCodec) {
      collectionCodecs.put(typeInfo, (CollectionCodec<?>) initialCodec);
    }
    if (initialCodec.getClass() == ObjectCodec.class
        && typeInfo.type() instanceof Class
        && typeInfo.rawType() != Object.class) {
      ObjectCodec<?> owner = (ObjectCodec<?>) initialCodec;
      rawObjectTypeInfos.put(typeInfo.rawType(), typeInfo);
      canonicalObjectTypeInfos.put(owner, typeInfo);
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

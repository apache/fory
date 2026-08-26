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

import java.lang.reflect.Executable;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
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
import org.apache.fory.json.JsonCodecFactory;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonFormat;
import org.apache.fory.json.codec.ArrayCodec;
import org.apache.fory.json.codec.ClosedSubtypeCodec;
import org.apache.fory.json.codec.CodecUtils;
import org.apache.fory.json.codec.CollectionCodec;
import org.apache.fory.json.codec.CompositeJsonCodec;
import org.apache.fory.json.codec.GeneratedJsonCodec;
import org.apache.fory.json.codec.JsonObjectModel;
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
import org.apache.fory.json.codec.UnboxedValueCodec;
import org.apache.fory.json.codec.Utf16ReaderCodec;
import org.apache.fory.json.codec.Utf8ReaderCodec;
import org.apache.fory.json.codec.Utf8WriterCodec;
import org.apache.fory.json.codegen.GeneratedCodecKey;
import org.apache.fory.json.codegen.JsonCodegen;
import org.apache.fory.json.codegen.JsonJITContext;
import org.apache.fory.json.meta.JsonCreatorDeclaration;
import org.apache.fory.json.meta.JsonCreatorFieldInfo;
import org.apache.fory.json.meta.JsonCreatorInfo;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldKind;
import org.apache.fory.json.meta.JsonFieldTable;
import org.apache.fory.meta.TypeExtMeta;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.Types;

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
 * its fields. {@code canonicalObjectTypeInfos} indexes every exact declared object binding by codec
 * identity so parameterized and language-semantic bindings own distinct generated capabilities.
 */
public final class JsonTypeResolver {
  private static final TypeExtMeta NON_NULL_SUBTYPE_TYPE =
      TypeExtMeta.of(Types.UNKNOWN, false, false, false, false);

  private final Map<Object, ObjectCodec<?>> objectCodecs;
  private final Map<Object, JsonTypeInfo> typeInfos;
  private final IdentityHashMap<Class<?>, JsonTypeInfo> runtimeTypeInfos;
  private final JsonSharedRegistry sharedRegistry;
  private final JsonCodegen codegen;
  private final JsonJITContext jitContext;
  private final IdentityMap<ObjectCodec<?>, JsonTypeInfo> canonicalObjectTypeInfos;
  private final IdentityMap<JsonTypeInfo, CollectionCodec<?>> collectionCodecs;
  private final IdentityMap<JsonTypeInfo, Class<?>> subtypeTypeRoots;
  private final IdentityHashMap<Class<?>, TypeRef<?>> activeGenericBindings;
  // A runtime composite publishes its shell before binding children. Keep that exact shell visible
  // through an arbitrarily deep declared child graph so a reverse edge can close the cycle without
  // authorizing the runtime-only binding as a later declared schema.
  private JsonTypeInfo activeRuntimeTypeInfo;
  private int resolutionDepth;
  private Class<?> subtypeResolutionBase;
  private boolean isolateSubtypeResolution;

  public JsonTypeResolver(JsonSharedRegistry sharedRegistry) {
    this.sharedRegistry = sharedRegistry;
    objectCodecs = new HashMap<>();
    typeInfos = new HashMap<>();
    runtimeTypeInfos = new IdentityHashMap<>();
    codegen = sharedRegistry.codegen();
    jitContext = sharedRegistry.newJITContext();
    canonicalObjectTypeInfos = new IdentityMap<>();
    collectionCodecs = new IdentityMap<>();
    subtypeTypeRoots = new IdentityMap<>();
    activeGenericBindings = new IdentityHashMap<>();
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
    Object key = resolutionTypeKey(ownerType.getType(), rawType);
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
    Object key = resolutionTypeKey(rawType, rawType);
    JsonTypeInfo typeInfo = typeInfos.get(key);
    if (typeInfo != null) {
      // Generated capabilities replace type-info slots; objectCodecs retains the stable metadata
      // owner used to build an unwrapped parent.
      return canonicalObjectCodec(typeInfo);
    }
    if (customTypeInfo(rawType, rawType) != null || sharedRegistry.hasSubTypes(rawType)) {
      return null;
    }
    JsonSharedRegistry.ResolvedCodec selected =
        sharedRegistry.resolveCodec(rawType, ownerType, this, null, false);
    if (selected != null) {
      if (!(selected.codec() instanceof ObjectCodec)) {
        return null;
      }
      // A language object model still produces the standard ObjectCodec; only its constructor and
      // accessors differ. Publish that shell without resolving it so JsonUnwrappedInfo remains the
      // sole owner of iterative flattened-graph resolution and cycle detection.
      ObjectCodec<?> codec = (ObjectCodec<?>) selected.codec();
      objectCodecs.put(key, codec);
      typeInfo = newRegisteredTypeInfo(ownerType, codec, selected.factoryKey());
      publishTypeInfo(key, typeInfo);
      registerTypeInfoOwner(typeInfo, codec);
      return codec;
    }
    ObjectCodec<?> codec = objectCodecs.get(key);
    if (codec == null) {
      codec = newObjectCodec(ownerType);
      objectCodecs.put(key, codec);
    }
    typeInfo = newTypeInfo(rawType, rawType, codec);
    publishTypeInfo(key, typeInfo);
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
    ObjectCodec<?> owner = objectCodecs.get(metadataKey(typeInfo));
    if (owner != null && canonicalObjectTypeInfos.get(owner) == typeInfo) {
      return owner;
    }
    for (Map.Entry<ObjectCodec<?>, JsonTypeInfo> entry : canonicalObjectTypeInfos.iterable()) {
      if (entry.getValue() == typeInfo) {
        return entry.getKey();
      }
    }
    return null;
  }

  String factoryKey(ObjectCodec<?> owner) {
    JsonTypeInfo typeInfo = canonicalObjectTypeInfos.get(owner);
    return typeInfo == null ? null : typeInfo.factoryKey();
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
    CollectionCodec<?> owner = exactDeclaredCollectionOwner(typeInfo);
    if (owner == null || !owner.createsArrayList()) {
      return null;
    }
    JsonTypeInfo element = declaredCollectionElement(typeInfo);
    if (canonicalObjectOwner(element) == null
        && !(owner instanceof CollectionCodec.DirectCollectionCodec)) {
      return null;
    }
    return owner;
  }

  /** Returns an exact declared UTF-8 collection writer owner, or {@code null}. */
  @Internal
  public CollectionCodec<?> exactUtf8WriterCollection(JsonTypeInfo typeInfo) {
    jitContext.lock();
    try {
      return exactUtf8WriterCollectionOwner(typeInfo);
    } finally {
      jitContext.unlock();
    }
  }

  private CollectionCodec<?> exactUtf8WriterCollectionOwner(JsonTypeInfo typeInfo) {
    CollectionCodec<?> owner = exactDeclaredCollectionOwner(typeInfo);
    // A generated collection writer owns only the ArrayList common loop. Keep declarations that
    // cannot legally contain an ArrayList on their existing codec instead of compiling a class
    // whose common branch is unreachable.
    if (owner == null || !typeInfo.rawType().isAssignableFrom(ArrayList.class)) {
      return null;
    }
    if (owner instanceof CollectionCodec.DirectCollectionCodec) {
      return owner;
    }
    JsonTypeInfo element = declaredCollectionElement(typeInfo);
    return owner instanceof CollectionCodec.ObjectCollectionCodec
            && canonicalObjectOwner(element) != null
        ? owner
        : null;
  }

  private CollectionCodec<?> exactDeclaredCollectionOwner(JsonTypeInfo typeInfo) {
    CollectionCodec<?> owner = collectionCodecs.get(typeInfo);
    if (owner == null || !(typeInfo.type() instanceof ParameterizedType)) {
      return null;
    }
    JsonTypeInfo element = declaredCollectionElement(typeInfo);
    Type declaredElement = CodecUtils.elementType(typeInfo.type());
    if (element == null
        || element.rawType() == Object.class
        || element.usesAnnotationCodec()
        || !declaredElement.equals(element.type())) {
      return null;
    }
    return owner;
  }

  private JsonTypeInfo declaredCollectionElement(JsonTypeInfo collection) {
    Type elementType = CodecUtils.elementType(collection.type());
    Class<?> rawType = CodecUtils.rawType(elementType, Object.class);
    Class<?> subtypeRoot = subtypeTypeRoots.get(collection);
    Object key =
        subtypeRoot == null
            ? typeInfoKey(elementType, rawType)
            : subtypeTypeKey(subtypeRoot, elementType, rawType);
    return typeInfos.get(key);
  }

  public JsonTypeInfo getTypeInfo(Type declaredType, Class<?> fallback) {
    Class<?> rawType = CodecUtils.rawType(declaredType, fallback);
    Object key = resolutionTypeKey(declaredType, rawType);
    JsonTypeInfo typeInfo = typeInfos.get(key);
    if (typeInfo == null
        && key == rawType
        && activeRuntimeTypeInfo != null
        && activeRuntimeTypeInfo.rawType() == rawType) {
      typeInfo = activeRuntimeTypeInfo;
    }
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

  /** Resolves one complete declared type without discarding nested type-use metadata. */
  @Internal
  public JsonTypeInfo getTypeInfo(TypeRef<?> declaredType) {
    Class<?> rawType = declaredType.getRawType();
    Object key = resolutionTypeKey(declaredType);
    JsonTypeInfo typeInfo = typeInfos.get(key);
    if (typeInfo == null
        && key == rawType
        && activeRuntimeTypeInfo != null
        && activeRuntimeTypeInfo.rawType() == rawType) {
      typeInfo = activeRuntimeTypeInfo;
    }
    if (typeInfo != null) {
      return typeInfo;
    }
    ResolutionSnapshot snapshot = beginResolution();
    try {
      JsonTypeInfo result = resolveTypeInfo(declaredType, key);
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
    return getTypeInfo(
        typeRef(declaredType, CodecUtils.rawType(declaredType, fallback)), annotation);
  }

  /** Resolves an annotation-selected representation without dropping occurrence metadata. */
  @Internal
  public JsonTypeInfo getTypeInfo(TypeRef<?> declaredType, JsonCodec annotation) {
    if (annotation == null) {
      return getTypeInfo(declaredType);
    }
    ResolutionSnapshot snapshot = beginResolution();
    try {
      JsonTypeInfo result = resolveTypeInfo(declaredType, annotation);
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
    return getTypeInfo(
        typeRef(declaredType, CodecUtils.rawType(declaredType, fallback)), codecClass);
  }

  /** Resolves one exact annotation codec without dropping occurrence metadata. */
  @Internal
  public JsonTypeInfo getTypeInfo(
      TypeRef<?> declaredType, Class<? extends JsonValueCodec<?>> codecClass) {
    return annotationTypeInfo(declaredType, codecClass);
  }

  @Internal
  public JsonTypeInfo getTypeInfo(Type declaredType, Class<?> fallback, JsonFormat annotation) {
    return getTypeInfo(
        typeRef(declaredType, CodecUtils.rawType(declaredType, fallback)), annotation);
  }

  /** Resolves an exact format occurrence without dropping occurrence metadata. */
  @Internal
  public JsonTypeInfo getTypeInfo(TypeRef<?> declaredType, JsonFormat annotation) {
    ResolutionSnapshot snapshot = beginResolution();
    try {
      JsonTypeInfo result = resolveTypeInfo(declaredType, annotation);
      completeResolution(snapshot);
      return result;
    } catch (RuntimeException | Error e) {
      rollbackResolution(snapshot);
      throw e;
    } finally {
      endResolution();
    }
  }

  /** Generates hosted capabilities and returns their language-neutral object metadata owners. */
  @Internal
  public List<ObjectCodec<?>> generateHostedCodecs(Class<?> type) {
    if (!sharedRegistry.hostedCodegen()) {
      throw new IllegalStateException("Hosted JSON codec generation requires a hosted registry");
    }
    JsonTypeInfo typeInfo;
    try {
      typeInfo = getTypeInfo(type, type);
    } catch (ExactTypeRequiredException ignored) {
      // Analysis reachability retains a declaration but does not make its raw Class a schema.
      // Exact semantic occurrences are generated when a selected parent resolves them.
      return java.util.Collections.emptyList();
    }
    ArrayList<JsonTypeInfo> roots = new ArrayList<>(1);
    roots.add(typeInfo);
    // A preceding selected model may already have resolved this type inside an uncodegenable graph.
    // Cached metadata is still a generation root; otherwise that earlier graph can suppress every
    // capability for an independently eligible annotated model.
    requestCapabilities(roots);
    Set<ObjectCodec<?>> models = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    models.addAll(objectCodecs.values());
    return new ArrayList<>(models);
  }

  /** Returns effective creator declarations for exact language-metadata signature mapping. */
  @Internal
  public List<JsonCreatorDeclaration> creatorDeclarations(Class<?> type) {
    return JsonCreatorDeclaration.findAll(type, sharedRegistry);
  }

  private JsonTypeInfo resolveTypeInfo(Type declaredType, Class<?> rawType, Object key) {
    return resolveTypeInfo(typeRef(declaredType, rawType), key);
  }

  private JsonTypeInfo resolveTypeInfo(TypeRef<?> declaredType, Object key) {
    validateCovariant(declaredType);
    Class<?> rawType = declaredType.getRawType();
    JsonTypeInfo typeInfo = customTypeInfo(declaredType, rawType);
    if (typeInfo != null) {
      publishTypeInfo(key, typeInfo);
      return typeInfo;
    }
    JsonSubTypesInfo definition = sharedRegistry.explicitSubTypesInfo(rawType);
    if (definition != null) {
      sharedRegistry.checkSecure(rawType);
      ClosedSubtypeCodec codec = new ClosedSubtypeCodec(rawType, definition);
      typeInfo = newTypeInfo(declaredType, codec);
      // Closed graphs may recursively refer to their declared base through a subtype field or
      // container. Publish the complete dispatcher shell before resolving every finite branch.
      // The outer cold-resolution transaction removes the complete provisional graph on failure.
      publishTypeInfo(key, typeInfo);
      codec.resolveTypes(declaredType, this);
      return typeInfo;
    }
    return buildTypeInfo(rawType, declaredType, key);
  }

  private JsonTypeInfo resolveTypeInfo(TypeRef<?> declaredType, JsonCodec annotation) {
    validateCovariant(declaredType);
    Class<?> rawType = declaredType.getRawType();
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
      return annotationTypeInfo(declaredType, valueCodec);
    }
    if (sharedRegistry.customCodec(rawType) != null
        || sharedRegistry.codecDeclaration(rawType) != null
        || sharedRegistry.hasSubTypes(rawType)) {
      throw invalidCodecConfig(
          rawType, "a child codec is hidden by the complete codec for the current value");
    }
    sharedRegistry.checkSecure(rawType);
    TypeRef<?> typeRef = declaredType;
    if (rawType.isArray()) {
      requireSlots(rawType, hasElement, !hasContent && !hasKey && !hasMapValue, "elementCodec");
      TypeRef<?> elementType = typeRef.getComponentType();
      requireConcreteChild(elementType.getType(), rawType, "elementCodec");
      JsonTypeInfo elementInfo = annotationTypeInfo(elementType, elementCodec);
      return newTypeInfo(declaredType, ArrayCodec.create(rawType, elementInfo));
    }
    if (rawType == AtomicReferenceArray.class) {
      requireSlots(rawType, hasElement, !hasContent && !hasKey && !hasMapValue, "elementCodec");
      TypeRef<?> elementType = directElementType(typeRef, rawType, "elementCodec");
      JsonTypeInfo elementInfo = annotationTypeInfo(elementType, elementCodec);
      return newTypeInfo(declaredType, ScalarCodecs.AtomicReferenceArrayCodec.create(elementInfo));
    }
    if (Collection.class.isAssignableFrom(rawType)) {
      requireSlots(rawType, hasElement, !hasContent && !hasKey && !hasMapValue, "elementCodec");
      TypeRef<?> elementType = directElementType(typeRef, rawType, "elementCodec");
      JsonTypeInfo elementInfo = annotationTypeInfo(elementType, elementCodec);
      return newTypeInfo(
          declaredType,
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
          hasMapValue ? annotationTypeInfo(mapValueType, mapValueCodec) : getTypeInfo(mapValueType);
      checkMapKeySecure(keyRawType);
      MapCodec<?> codec =
          hasKey
              ? MapCodec.create(
                  rawType, keyType, valueInfo, sharedRegistry.mapKeyCodec(keyRawType, keyCodec))
              : MapCodec.create(rawType, keyType, valueInfo);
      return newTypeInfo(declaredType, codec);
    }
    if (rawType == Optional.class || rawType == AtomicReference.class) {
      requireSlots(rawType, hasContent, !hasElement && !hasKey && !hasMapValue, "contentCodec");
      TypeRef<?> contentType = directElementType(typeRef, rawType, "contentCodec");
      JsonTypeInfo contentInfo = annotationTypeInfo(contentType, contentCodec);
      JsonValueCodec<?> codec =
          rawType == Optional.class
              ? new ScalarCodecs.OptionalCodec(declaredType, contentInfo)
              : ScalarCodecs.AtomicReferenceCodec.create(declaredType, contentInfo);
      return newTypeInfo(declaredType, codec);
    }
    JsonValueCodec<?> codec = sharedRegistry.createCodec(rawType, typeRef, this);
    if (codec instanceof CompositeJsonCodec) {
      JsonTypeInfo typeInfo = newTypeInfo(declaredType, JsonFieldKind.OBJECT, codec, true);
      ((CompositeJsonCodec<?>) codec).resolveTypes(typeRef, this, annotation);
      return typeInfo;
    }
    throw invalidCodecConfig(rawType, "does not support child codecs");
  }

  private JsonTypeInfo resolveTypeInfo(TypeRef<?> declaredType, JsonFormat annotation) {
    validateCovariant(declaredType);
    Class<?> rawType = declaredType.getRawType();
    if (ScalarCodecs.supportsDateTimeFormat(rawType)) {
      return formatTypeInfo(declaredType, annotation);
    }
    if (sharedRegistry.customCodec(rawType) != null
        || sharedRegistry.codecDeclaration(rawType) != null
        || sharedRegistry.valueDeclaration(rawType) != null
        || sharedRegistry.hasSubTypes(rawType)) {
      throw invalidFormatConfig(rawType, "a complete representation hides its direct child");
    }
    sharedRegistry.checkSecure(rawType);
    TypeRef<?> typeRef = declaredType;
    if (rawType.isArray()) {
      TypeRef<?> elementType = typeRef.getComponentType();
      requireConcreteChild(elementType.getType(), rawType, "element", "@JsonFormat");
      JsonTypeInfo elementInfo = formatTypeInfo(elementType, annotation);
      return newTypeInfo(declaredType, ArrayCodec.create(rawType, elementInfo));
    }
    if (rawType == AtomicReferenceArray.class) {
      TypeRef<?> elementType = directElementType(typeRef, rawType, "element", "@JsonFormat");
      JsonTypeInfo elementInfo = formatTypeInfo(elementType, annotation);
      return newTypeInfo(declaredType, ScalarCodecs.AtomicReferenceArrayCodec.create(elementInfo));
    }
    if (Collection.class.isAssignableFrom(rawType)) {
      TypeRef<?> elementType = directElementType(typeRef, rawType, "element", "@JsonFormat");
      JsonTypeInfo elementInfo = formatTypeInfo(elementType, annotation);
      return newTypeInfo(
          declaredType,
          CollectionCodec.create(rawType, elementType.getRawType(), elementInfo, this));
    }
    if (Map.class.isAssignableFrom(rawType)) {
      requireTypeArguments(typeRef, rawType, "@JsonFormat");
      Tuple2<TypeRef<?>, TypeRef<?>> children = CodecUtils.mapKeyValueTypeRefs(typeRef);
      TypeRef<?> valueType = children.f1;
      requireConcreteChild(valueType.getType(), rawType, "value", "@JsonFormat");
      JsonTypeInfo valueInfo = formatTypeInfo(valueType, annotation);
      Class<?> keyRawType = children.f0.getRawType();
      checkMapKeySecure(keyRawType);
      return newTypeInfo(declaredType, MapCodec.create(rawType, children.f0, valueInfo));
    }
    if (rawType == Optional.class || rawType == AtomicReference.class) {
      TypeRef<?> contentType = directElementType(typeRef, rawType, "content", "@JsonFormat");
      JsonTypeInfo contentInfo = formatTypeInfo(contentType, annotation);
      JsonValueCodec<?> codec =
          rawType == Optional.class
              ? new ScalarCodecs.OptionalCodec(declaredType, contentInfo)
              : ScalarCodecs.AtomicReferenceCodec.create(declaredType, contentInfo);
      return newTypeInfo(declaredType, codec);
    }
    throw invalidFormatConfig(rawType, "requires a date/time value or supported direct wrapper");
  }

  private JsonTypeInfo formatTypeInfo(TypeRef<?> type, JsonFormat annotation) {
    validateCovariant(type);
    Class<?> rawType = type.getRawType();
    sharedRegistry.checkSecure(rawType);
    JsonValueCodec<?> codec =
        ScalarCodecs.dateTimeFormatCodec(rawType, annotation.pattern(), annotation.timezone());
    return newTypeInfo(type, JsonFieldKind.OBJECT, codec, true);
  }

  private JsonTypeInfo customTypeInfo(Type declaredType, Class<?> rawType) {
    return customTypeInfo(typeRef(declaredType, rawType), rawType);
  }

  private JsonTypeInfo customTypeInfo(TypeRef<?> declaredType, Class<?> rawType) {
    JsonValueCodec<?> codec = sharedRegistry.customCodec(rawType);
    if (codec != null) {
      sharedRegistry.checkCustomSecure(rawType);
      return newRegisteredTypeInfo(declaredType, codec, null);
    }
    JsonCodecDeclaration declaration = sharedRegistry.codecDeclaration(rawType);
    if (declaration != null) {
      if (!declaration.inherited()) {
        rejectConflictingValue(rawType);
      }
      codec = sharedRegistry.annotationCodec(rawType, declaration.codecClass());
      codec = declaration.bind(declaredType.getType(), rawType, codec);
      return newTypeInfo(declaredType, JsonFieldKind.OBJECT, codec, true);
    }
    JsonValueDeclaration value = sharedRegistry.valueDeclaration(rawType);
    if (value == null) {
      return null;
    }
    sharedRegistry.checkSecure(rawType);
    return newTypeInfo(declaredType, JsonFieldKind.OBJECT, value.codec(), true);
  }

  private JsonTypeInfo annotationTypeInfo(
      TypeRef<?> type, Class<? extends JsonValueCodec<?>> codecClass) {
    validateCovariant(type);
    Class<?> rawType = type.getRawType();
    JsonValueCodec<?> codec = sharedRegistry.annotationCodec(rawType, codecClass);
    return newTypeInfo(type, JsonFieldKind.OBJECT, codec, true);
  }

  private void validateCovariant(TypeRef<?> type) {
    TypeExtMeta metadata = type.getTypeExtMeta();
    if (metadata == null || !metadata.covariant()) {
      return;
    }
    Class<?> rawType = type.getRawType();
    if (!Modifier.isFinal(rawType.getModifiers()) && !sharedRegistry.hasSubTypes(rawType)) {
      throw new ForyJsonException(
          "Covariant JSON type must be final or declare effective @JsonSubTypes: " + type);
    }
  }

  private static TypeRef<?> directElementType(TypeRef<?> typeRef, Class<?> rawType, String slot) {
    return directElementType(typeRef, rawType, slot, "@JsonCodec");
  }

  private static TypeRef<?> directElementType(
      TypeRef<?> typeRef, Class<?> rawType, String slot, String annotation) {
    requireTypeArguments(typeRef, rawType, annotation);
    TypeRef<?> elementType = CodecUtils.elementTypeRef(typeRef);
    requireConcreteChild(elementType.getType(), rawType, slot, annotation);
    return elementType;
  }

  private static void requireTypeArguments(TypeRef<?> typeRef, Class<?> rawType) {
    requireTypeArguments(typeRef, rawType, "@JsonCodec");
  }

  private static void requireTypeArguments(
      TypeRef<?> typeRef, Class<?> rawType, String annotation) {
    if (!typeRef.hasExplicitTypeArguments() && rawType.getTypeParameters().length != 0) {
      throw invalidConfig(rawType, annotation, "direct child requires concrete type arguments");
    }
  }

  private static void requireConcreteChild(Type type, Class<?> rawType, String slot) {
    requireConcreteChild(type, rawType, slot, "@JsonCodec");
  }

  private static void requireConcreteChild(
      Type type, Class<?> rawType, String slot, String annotation) {
    if (type instanceof TypeVariable || type instanceof WildcardType) {
      throw invalidConfig(rawType, annotation, slot + " requires a concrete direct child type");
    }
    if (type instanceof ParameterizedType
        && !(((ParameterizedType) type).getRawType() instanceof Class)) {
      throw invalidConfig(rawType, annotation, slot + " requires a concrete direct child type");
    }
  }

  private static void requireSlots(
      Class<?> rawType, boolean required, boolean noOtherSlots, String slot) {
    if (!required || !noOtherSlots) {
      throw invalidCodecConfig(rawType, "supports only " + slot + " as a child codec");
    }
  }

  private static ForyJsonException invalidCodecConfig(Class<?> rawType, String reason) {
    return invalidConfig(rawType, "@JsonCodec", reason);
  }

  private static ForyJsonException invalidFormatConfig(Class<?> rawType, String reason) {
    return invalidConfig(rawType, "@JsonFormat", reason);
  }

  private static ForyJsonException invalidConfig(
      Class<?> rawType, String annotation, String reason) {
    return new ForyJsonException(
        "Invalid " + annotation + " for " + rawType.getTypeName() + ": " + reason);
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
                new HashSet<>(typeInfos.keySet()),
                new HashSet<>(objectCodecs.keySet()),
                new HashSet<>(runtimeTypeInfos.keySet()),
                copyIdentityMap(canonicalObjectTypeInfos))
            : null;
    resolutionDepth++;
    return snapshot;
  }

  private void endResolution() {
    resolutionDepth--;
  }

  private void completeResolution(ResolutionSnapshot snapshot) {
    if (snapshot == null) {
      return;
    }
    ArrayList<JsonTypeInfo> roots = new ArrayList<>();
    for (Map.Entry<Object, JsonTypeInfo> entry : typeInfos.entrySet()) {
      if (!snapshot.typeKeys.contains(entry.getKey())) {
        roots.add(entry.getValue());
      }
    }
    for (Map.Entry<Class<?>, JsonTypeInfo> entry : runtimeTypeInfos.entrySet()) {
      if (!snapshot.runtimeKeys.contains(entry.getKey()) && !roots.contains(entry.getValue())) {
        roots.add(entry.getValue());
      }
    }
    if (roots.isEmpty()) {
      return;
    }
    if (!sharedRegistry.generatedCapabilitiesEnabled()) {
      return;
    }
    requestCapabilities(roots);
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
        collectionCodecs.remove(value);
        subtypeTypeRoots.remove(value);
        typeIterator.remove();
      }
    }
    Iterator<Object> objectIterator = objectCodecs.keySet().iterator();
    while (objectIterator.hasNext()) {
      if (!snapshot.objectKeys.contains(objectIterator.next())) {
        objectIterator.remove();
      }
    }
    Iterator<Class<?>> runtimeIterator = runtimeTypeInfos.keySet().iterator();
    while (runtimeIterator.hasNext()) {
      if (!snapshot.runtimeKeys.contains(runtimeIterator.next())) {
        runtimeIterator.remove();
      }
    }
    restoreIdentityMap(canonicalObjectTypeInfos, snapshot.canonicalObjectTypeInfos);
  }

  private static final class ResolutionSnapshot {
    private final Set<Object> typeKeys;
    private final Set<Object> objectKeys;
    private final Set<Class<?>> runtimeKeys;
    private final IdentityHashMap<ObjectCodec<?>, JsonTypeInfo> canonicalObjectTypeInfos;

    private ResolutionSnapshot(
        Set<Object> typeKeys,
        Set<Object> objectKeys,
        Set<Class<?>> runtimeKeys,
        IdentityHashMap<ObjectCodec<?>, JsonTypeInfo> canonicalObjectTypeInfos) {
      this.typeKeys = typeKeys;
      this.objectKeys = objectKeys;
      this.runtimeKeys = runtimeKeys;
      this.canonicalObjectTypeInfos = canonicalObjectTypeInfos;
    }
  }

  private static <K, V> IdentityHashMap<K, V> copyIdentityMap(IdentityMap<K, V> source) {
    IdentityHashMap<K, V> copy = new IdentityHashMap<>(source.size);
    for (Map.Entry<K, V> entry : source.iterable()) {
      copy.put(entry.getKey(), entry.getValue());
    }
    return copy;
  }

  private static <K, V> void restoreIdentityMap(
      IdentityMap<K, V> target, IdentityHashMap<K, V> snapshot) {
    target.clear();
    for (Map.Entry<K, V> entry : snapshot.entrySet()) {
      target.put(entry.getKey(), entry.getValue());
    }
  }

  public JsonTypeInfo getRuntimeTypeInfo(Class<?> runtimeType) {
    JsonTypeInfo typeInfo = runtimeTypeInfos.get(runtimeType);
    if (typeInfo != null) {
      return typeInfo;
    }
    ResolutionSnapshot snapshot = beginResolution();
    try {
      JsonTypeInfo result = resolveRuntimeTypeInfo(runtimeType);
      completeResolution(snapshot);
      return result;
    } catch (RuntimeException | Error e) {
      rollbackResolution(snapshot);
      throw e;
    } finally {
      endResolution();
    }
  }

  private JsonTypeInfo resolveRuntimeTypeInfo(Class<?> runtimeType) {
    JsonTypeInfo typeInfo = customTypeInfo(runtimeType, runtimeType);
    if (typeInfo != null) {
      runtimeTypeInfos.put(runtimeType, typeInfo);
      return typeInfo;
    }
    sharedRegistry.checkSecure(runtimeType);
    Class<?> aliasTarget = sharedRegistry.runtimeCodecTarget(runtimeType);
    if (aliasTarget != null) {
      typeInfo = getTypeInfo(aliasTarget, aliasTarget);
      runtimeTypeInfos.put(runtimeType, typeInfo);
      return typeInfo;
    }
    if (runtimeType == Object.class) {
      // Declared Object uses the natural JSON codec, but an actual Object instance has the
      // structural empty-object representation. Keep those two owners separate so natural
      // runtime dispatch cannot resolve back to itself.
      TypeRef<?> typeRef = TypeRef.of(runtimeType);
      JsonValueCodec<?> codec = getObjectCodec(typeRef);
      typeInfo = newTypeInfo(runtimeType, runtimeType, codec);
      registerTypeInfoOwner(typeInfo, codec);
      runtimeTypeInfos.put(runtimeType, typeInfo);
      return typeInfo;
    }
    typeInfo = typeInfos.get(runtimeType);
    if (typeInfo != null) {
      // An exact declared binding is authoritative for values of that same raw class. The runtime
      // factory path exists for implementation classes that have no declared binding.
      runtimeTypeInfos.put(runtimeType, typeInfo);
      return typeInfo;
    }
    TypeRef<?> typeRef = TypeRef.of(runtimeType);
    JsonValueCodec<?> codec = sharedRegistry.createRuntimeCodec(runtimeType, this);
    if (codec == null) {
      // The default Java object model is the declared schema for this exact class. Reuse that
      // binding so generated capabilities stay on the direct root path. Only a codec actually
      // selected by a runtime factory needs the runtime-only publication below.
      typeInfo = getTypeInfo(typeRef);
      runtimeTypeInfos.put(runtimeType, typeInfo);
      return typeInfo;
    }
    if (codec instanceof ClosedSubtypeCodec) {
      Class<?> baseType = ((ClosedSubtypeCodec) codec).baseType();
      if (baseType != runtimeType) {
        if (!baseType.isAssignableFrom(runtimeType)) {
          throw new ForyJsonException(
              "Closed JSON root " + baseType.getName() + " does not own " + runtimeType.getName());
        }
        // A module may recognize a runtime branch by returning its closed root codec. Resolve the
        // declared root before publishing the runtime alias so child binding cannot observe that
        // root shell as the branch's own recursive metadata.
        typeInfo = getTypeInfo(baseType, baseType);
        runtimeTypeInfos.put(runtimeType, typeInfo);
        return typeInfo;
      }
    }
    typeInfo = typeInfos.get(runtimeType);
    if (typeInfo == null) {
      typeInfo = newTypeInfo(runtimeType, runtimeType, codec);
      // A dynamic write binding must never authorize a declared read schema. Publish every
      // runtime codec only in the runtime cache; activeRuntimeTypeInfo closes recursive binding.
      runtimeTypeInfos.put(runtimeType, typeInfo);
      registerTypeInfoOwner(typeInfo, codec);
      JsonTypeInfo previousRuntimeTypeInfo = activeRuntimeTypeInfo;
      activeRuntimeTypeInfo = typeInfo;
      try {
        resolveCodecTypes(codec, typeRef);
      } finally {
        activeRuntimeTypeInfo = previousRuntimeTypeInfo;
      }
    }
    runtimeTypeInfos.put(runtimeType, typeInfo);
    return typeInfo;
  }

  /**
   * Resolves one branch, honoring an exact registration before its parent-selected object codec.
   */
  @Internal
  public JsonTypeInfo getSubtypeTypeInfo(
      Class<?> baseType, TypeRef<?> subtypeType, boolean isolated, JsonCodecFactory childFactory) {
    Class<?> previousBase = subtypeResolutionBase;
    boolean previousIsolation = isolateSubtypeResolution;
    subtypeResolutionBase = baseType;
    isolateSubtypeResolution = isolated;
    try {
      // A discriminator selects one concrete branch value, so its outer occurrence is non-null.
      // Preserve the resolved generic children while giving language factories that exact type.
      TypeRef<?> declaredType =
          TypeRef.ofSemanticTypeArguments(
              subtypeType.getType(),
              NON_NULL_SUBTYPE_TYPE,
              subtypeType.getTypeArguments(),
              subtypeType.isArray() ? subtypeType.getComponentType() : null);
      if (childFactory == null) {
        return getTypeInfo(declaredType);
      }
      Class<?> rawType = declaredType.getRawType();
      Object key = resolutionTypeKey(declaredType);
      JsonTypeInfo typeInfo = typeInfos.get(key);
      if (typeInfo != null) {
        return typeInfo;
      }
      ResolutionSnapshot snapshot = beginResolution();
      try {
        validateCovariant(declaredType);
        typeInfo = customTypeInfo(declaredType, rawType);
        if (typeInfo != null) {
          publishTypeInfo(key, typeInfo);
        } else {
          typeInfo = buildTypeInfo(rawType, declaredType, key, childFactory);
        }
        completeResolution(snapshot);
        return typeInfo;
      } catch (RuntimeException | Error e) {
        rollbackResolution(snapshot);
        throw e;
      } finally {
        endResolution();
      }
    } finally {
      subtypeResolutionBase = previousBase;
      isolateSubtypeResolution = previousIsolation;
    }
  }

  public void checkSecure(Class<?> type) {
    sharedRegistry.checkSecure(type);
  }

  /** Returns whether the exact type requests language-owned sealed subtype inference. */
  @Internal
  public boolean isInferredSubtype(Class<?> type) {
    return sharedRegistry.inferredSubTypes(type);
  }

  /**
   * Creates a dispatcher from an already published inferred table, or returns {@code null}.
   *
   * <p>This lets a language module avoid parsing hierarchy metadata again while keeping child
   * codecs resolver-local.
   */
  @Internal
  public JsonValueCodec<?> cachedInferredSubtypeCodec(
      TypeRef<?> type, JsonCodecFactory childFactory) {
    JsonSubTypesInfo definition = sharedRegistry.cachedSubTypesInfo(type.getRawType());
    if (definition == null) {
      JsonNativeSubtypeRegistry.Table table =
          JsonNativeSubtypeRegistry.table(
              type.getRawType(), sharedRegistry.mixinType(type.getRawType()));
      if (table != null) {
        definition =
            sharedRegistry.inferredSubTypesInfo(type.getRawType(), table.classes, table.names);
      }
    }
    return definition == null
        ? null
        : new ClosedSubtypeCodec(type.getRawType(), definition, type, childFactory);
  }

  /** Returns the accepted closed-subtype branches already resolved for hosted reachability. */
  @Internal
  public Class<?>[] resolvedSubtypeClasses(Class<?> type) {
    JsonSubTypesInfo definition = sharedRegistry.cachedSubTypesInfo(type);
    return definition == null ? new Class<?>[0] : definition.classes();
  }

  /**
   * Validates and atomically publishes one language-produced sealed subtype table.
   *
   * <p>The producer supplies trusted static hierarchy metadata. Common validation applies fixed
   * disallows and the configured checker before JSON input can select a logical name.
   */
  @Internal
  public JsonValueCodec<?> createInferredSubtypeCodec(
      TypeRef<?> type,
      Class<?>[] classes,
      String[] names,
      JsonCodecFactory childFactory,
      Object[] fixedInstances) {
    JsonSubTypesInfo definition =
        sharedRegistry.inferredSubTypesInfo(type.getRawType(), classes, names);
    Object[] acceptedFixed = alignFixedInstances(definition, classes, fixedInstances);
    return new ClosedSubtypeCodec(type.getRawType(), definition, type, childFactory, acceptedFixed);
  }

  private static Object[] alignFixedInstances(
      JsonSubTypesInfo definition, Class<?>[] classes, Object[] fixedInstances) {
    if (fixedInstances == null) {
      return null;
    }
    if (classes == null || classes.length != fixedInstances.length) {
      throw new IllegalArgumentException("Subtype fixed values do not match candidate classes");
    }
    Object[] accepted = new Object[definition.size()];
    for (int i = 0; i < classes.length; i++) {
      int index = definition.classIndex(classes[i]);
      if (index >= 0) {
        accepted[index] = fixedInstances[i];
      }
    }
    return accepted;
  }

  /** Builds an unresolved object codec from language-module construction metadata. */
  @Internal
  public ObjectCodec<?> createObjectCodec(TypeRef<?> ownerType, JsonObjectModel objectModel) {
    Class<?> type = ownerType.getRawType();
    sharedRegistry.checkSecure(type);
    validateObjectModel(ownerType, objectModel);
    // The language module already owns the exact construction/accessor model. A Java generated
    // companion may still supply faster operations, but its absence must not override that model.
    GeneratedJsonCodec<?> generatedCodec = sharedRegistry.generatedCodecIfPresent(ownerType);
    return ObjectCodec.build(
        ownerType,
        sharedRegistry.propertyDiscoveryEnabled(),
        sharedRegistry.propertyNamingStrategy(),
        sharedRegistry.writeNullFields(),
        sharedRegistry,
        generatedCodec,
        objectModel);
  }

  private static void validateObjectModel(TypeRef<?> ownerType, JsonObjectModel objectModel) {
    Class<?> type = ownerType.getRawType();
    Object fixedInstance = objectModel.fixedInstance();
    if (fixedInstance != null) {
      if (fixedInstance.getClass() != type) {
        throw new ForyJsonException("Invalid fixed JSON object model for " + type.getName());
      }
    } else {
      Executable creator = objectModel.creator();
      Executable invocationCreator = objectModel.invocationCreator();
      int modifiers = creator.getModifiers();
      if (creator.getDeclaringClass() != type
          || creator.isSynthetic()
          || creator.isVarArgs()
          || creator.getTypeParameters().length != 0
          || creator instanceof Method
              && (!Modifier.isPublic(modifiers)
                  || !Modifier.isStatic(modifiers)
                  || ((Method) creator).isBridge()
                  || ((Method) creator).getReturnType() != type)) {
        throw new ForyJsonException("Invalid JSON object-model creator " + creator);
      }
      int invocationModifiers = invocationCreator.getModifiers();
      if (invocationCreator.getDeclaringClass() != type
          || !Modifier.isPublic(invocationModifiers)
          || invocationCreator.isVarArgs()
          || invocationCreator.getTypeParameters().length != 0
          || invocationCreator == creator && !Modifier.isPublic(modifiers)
          || invocationCreator instanceof Method
              && (!Modifier.isStatic(invocationModifiers)
                  || ((Method) invocationCreator).isBridge()
                  || ((Method) invocationCreator).getReturnType() != type)) {
        throw new ForyJsonException("Invalid JSON object-model invocation " + invocationCreator);
      }
      Class<?>[] parameterTypes = creator.getParameterTypes();
      Type[] genericParameterTypes = creator.getGenericParameterTypes();
      TypeRef<?>[] logicalParameterTypes = objectModel.parameterTypes();
      Method[] accessors = objectModel.accessors();
      for (int i = 0; i < accessors.length; i++) {
        if (!compatibleObjectModelType(
            ownerType,
            genericParameterTypes[i],
            parameterTypes[i],
            parameterTypes[i],
            logicalParameterTypes[i])) {
          throw new ForyJsonException(
              "Invalid JSON object-model creator parameter " + creator + " at index " + i);
        }
        Method accessor = accessors[i];
        if (accessor == null) {
          continue;
        }
        int accessorModifiers = accessor.getModifiers();
        if (accessor.getParameterCount() != 0
            || !compatibleObjectModelType(
                ownerType,
                accessor.getGenericReturnType(),
                accessor.getReturnType(),
                parameterTypes[i],
                logicalParameterTypes[i])
            || !Modifier.isPublic(accessorModifiers)
            || Modifier.isStatic(accessorModifiers)
            || accessor.isBridge()
            || accessor.isSynthetic()
            || !accessor.getDeclaringClass().isAssignableFrom(type)) {
          throw new ForyJsonException("Invalid JSON object-model accessor " + accessor);
        }
      }
    }
    String[] propertyNames = objectModel.propertyNames();
    Method[] getters = objectModel.propertyGetters();
    Method[] setters = objectModel.propertySetters();
    TypeRef<?>[] propertyTypes = objectModel.propertyTypes();
    for (int i = 0; i < propertyNames.length; i++) {
      Method getter = getters[i];
      Method setter = setters[i];
      if (getter != null
          && (getter.getParameterCount() != 0
              || !compatibleObjectModelType(
                  ownerType,
                  getter.getGenericReturnType(),
                  getter.getReturnType(),
                  getter.getReturnType(),
                  propertyTypes[i])
              || !validObjectModelMethod(type, getter))) {
        throw new ForyJsonException("Invalid JSON object-model getter " + getter);
      }
      if (setter != null
          && (setter.getParameterCount() != 1
              || !compatibleObjectModelType(
                  ownerType,
                  setter.getGenericParameterTypes()[0],
                  setter.getParameterTypes()[0],
                  setter.getParameterTypes()[0],
                  propertyTypes[i])
              || setter.getReturnType() != void.class
              || !validObjectModelMethod(type, setter))) {
        throw new ForyJsonException("Invalid JSON object-model setter " + setter);
      }
    }
  }

  private static boolean compatibleObjectModelType(
      TypeRef<?> ownerType,
      Type memberGenericType,
      Class<?> memberType,
      Class<?> invocationType,
      TypeRef<?> logicalType) {
    if (JsonObjectModel.compatibleType(ownerType.resolveType(memberGenericType), logicalType)) {
      return true;
    }
    // A language value may be lowered to a different parent carrier. Do not resolve the logical
    // child here: the parent ObjectCodec shell has not been published yet and its underlying value
    // can recursively refer back to this owner. The published shell's phase-two field binding must
    // obtain the canonical logical codec and prove its exact UnboxedValueCodec carrier.
    if (memberType == invocationType
        && UnboxedValueCodec.requiresCarrier(memberType, logicalType)) {
      return true;
    }
    if (memberType == void.class) {
      return invocationType.getName().equals("scala.runtime.BoxedUnit")
          || logicalType.getRawType().getName().equals("scala.runtime.BoxedUnit")
          || logicalType.getRawType().getName().equals("kotlin.Unit");
    }
    // Scala 3 emits a BoxedUnit method descriptor with a void generic signature for a Unit
    // case-class accessor. Reflection therefore reports BoxedUnit as the raw return type and void
    // as the generic return type even though the constructor and logical property both use
    // BoxedUnit.
    return memberGenericType == void.class
        && memberType.getName().equals("scala.runtime.BoxedUnit")
        && invocationType == memberType
        && logicalType.getRawType() == memberType;
  }

  private static boolean validObjectModelMethod(Class<?> type, Method method) {
    int modifiers = method.getModifiers();
    return Modifier.isPublic(modifiers)
        && !Modifier.isStatic(modifiers)
        && !method.isBridge()
        && !method.isSynthetic()
        && method.getDeclaringClass().isAssignableFrom(type);
  }

  @Internal
  public void checkMapKeySecure(Class<?> type) {
    sharedRegistry.checkMapKeySecure(type);
  }

  /** Returns the built-in object-member codec for one approved map key type. */
  @Internal
  public MapKeyCodec getMapKeyCodec(Class<?> type) {
    checkMapKeySecure(type);
    return MapCodec.keyCodec(type);
  }

  /** Returns one annotation-selected object-member codec for an approved map key type. */
  @Internal
  public MapKeyCodec getMapKeyCodec(Class<?> type, Class<? extends MapKeyCodec> codecClass) {
    checkMapKeySecure(type);
    return sharedRegistry.mapKeyCodec(type, codecClass);
  }

  /** Creates uncached metadata for one parent-local closed-subtype leaf. */
  @Internal
  public JsonTypeInfo createSubtypeLeaf(TypeRef<?> type, JsonValueCodec<?> codec) {
    sharedRegistry.checkSecure(type.getRawType());
    return newTypeInfo(type, codec);
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
      if (storesWriteCapability(owner, field, false)) {
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
      if (storesWriteCapability(owner, field, true)) {
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
      if (storesWriteCapability(owner, field, false)) {
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
      if (storesWriteCapability(owner, field, true)) {
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
      if (storesReadCapability(owner, field)) {
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
      if (storesReadCapability(owner, field)) {
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
      if (storesReadCapability(owner, field)) {
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

  enum CapabilityKind {
    STRING_WRITER,
    UTF8_WRITER,
    LATIN1_READER,
    UTF16_READER,
    UTF8_READER
  }

  private boolean storesWriteCapability(
      ObjectCodec<?> owner, JsonFieldInfo field, boolean utf8Writer) {
    boolean usesCodec =
        utf8Writer
            ? JsonCodegen.usesUtf8WriteCodec(field, this)
            : JsonCodegen.usesWriteCodec(field);
    return usesCodec
        && (field.writeRawType() != owner.type()
            || canonicalObjectOwner(field.writeTypeInfo()) == null);
  }

  private boolean storesReadCapability(ObjectCodec<?> owner, JsonFieldInfo field) {
    if (JsonCodegen.usesReadCodec(field, this)) {
      return true;
    }
    Class<?> nestedType = JsonCodegen.readNestedType(field, this);
    return nestedType != null && nestedType != owner.type();
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
        boolean storesCapability =
            storesWriteCapability(owner, field, kind == CapabilityKind.UTF8_WRITER);
        if (storesCapability) {
          children.add(field.writeTypeInfo());
        }
      }
      boolean storesAny =
          any != null
              && (any.writeField() != null || any.writeGetter() != null)
              && storesAnyCodec(owner, any);
      if (storesAny) {
        children.add(any.valueTypeInfo());
      }
      return children;
    }
    JsonCreatorInfo creator = owner.creatorInfo();
    if (creator == null) {
      JsonFieldInfo[] fields = owner.readFields();
      for (int i = 0; i < fields.length; i++) {
        addReadDependency(children, owner, fields[i]);
      }
    } else {
      JsonCreatorFieldInfo[] fields = creator.fields();
      for (int i = 0; i < fields.length; i++) {
        JsonCreatorFieldInfo field = fields[i];
        children.add(field.typeInfo());
      }
    }
    if (owner.unwrappedInfo() != null) {
      JsonUnwrappedInfo.ReadRoute[] routes = owner.unwrappedInfo().readRoutes();
      for (int i = 0; i < routes.length; i++) {
        JsonUnwrappedInfo.ReadRoute route = routes[i];
        if (route.field() == null) {
          children.add(route.creatorField().typeInfo());
        } else {
          addReadDependency(children, owner, route.field());
        }
      }
    }
    boolean storesAny =
        any != null
            && (any.readField() != null || any.readSetter() != null)
            && storesAnyCodec(owner, any);
    if (storesAny) {
      children.add(any.valueTypeInfo());
    }
    return children;
  }

  private void addReadDependency(
      ArrayList<JsonTypeInfo> children, ObjectCodec<?> owner, JsonFieldInfo field) {
    boolean storesCapability = storesReadCapability(owner, field);
    if (storesCapability) {
      children.add(field.readTypeInfo());
    }
  }

  /** Returns whether a generated writer must traverse this cyclic edge through its type slot. */
  @Internal
  public boolean usesWriterSlot(ObjectCodec<?> ownerCodec, JsonTypeInfo child) {
    jitContext.lock();
    try {
      JsonTypeInfo owner = canonicalObjectTypeInfos.get(ownerCodec);
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
  public boolean usesReaderSlot(ObjectCodec<?> ownerCodec, JsonTypeInfo child) {
    jitContext.lock();
    try {
      JsonTypeInfo owner = canonicalObjectTypeInfos.get(ownerCodec);
      return owner != null
          && ownerCodec.unwrappedInfo() == null
          && child != owner
          && canonicalObjectOwner(child) != null
          && reachesReader(child, owner, new IdentityMap<>());
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
      if (kind == CapabilityKind.UTF8_WRITER) {
        return sharedRegistry.utf8CollectionWriterClass(node.generatedKey);
      }
      if (kind == CapabilityKind.UTF8_READER) {
        return sharedRegistry.utf8CollectionReaderClass(node.generatedKey);
      }
      throw new IllegalStateException("Unsupported generated JSON collection capability " + kind);
    }
    switch (kind) {
      case STRING_WRITER:
        return sharedRegistry.stringWriterClass(node.generatedKey, node.objectOwner, this);
      case UTF8_WRITER:
        return sharedRegistry.utf8WriterClass(node.generatedKey, node.objectOwner, this);
      case LATIN1_READER:
        return sharedRegistry.latin1ReaderClass(node.generatedKey, node.objectOwner, this);
      case UTF16_READER:
        return sharedRegistry.utf16ReaderClass(node.generatedKey, node.objectOwner, this);
      case UTF8_READER:
        return sharedRegistry.utf8ReaderClass(node.generatedKey, node.objectOwner, this);
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
      if (kind == CapabilityKind.UTF8_WRITER) {
        Utf8WriterCodec<Object> elementWriter = resolvedCapability(element, capabilities, kind);
        Utf8WriterCodec<Object> fallback = eraseUtf8Writer(node.collectionOwner);
        if (node.collectionOwner instanceof CollectionCodec.StringCollectionCodec) {
          return GeneratedCodecInstantiator.instantiateUtf8CollectionWriter(
              generatedClass, fallback);
        }
        return GeneratedCodecInstantiator.instantiateUtf8CollectionWriter(
            generatedClass, fallback, elementWriter);
      }
      if (kind == CapabilityKind.UTF8_READER) {
        Utf8ReaderCodec<Object> elementReader = resolvedCapability(element, capabilities, kind);
        return GeneratedCodecInstantiator.instantiateUtf8CollectionReader(
            generatedClass, elementReader);
      }
      throw new IllegalStateException("Unsupported generated JSON collection capability " + kind);
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

  @SuppressWarnings("unchecked")
  private static Utf8WriterCodec<Object> eraseUtf8Writer(CollectionCodec<?> codec) {
    return (Utf8WriterCodec<Object>) (Utf8WriterCodec<?>) codec;
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
            ClosedSubtypeCodec.InlineReader fixed = subtype.fixedInlineReader(i);
            if (fixed != null) {
              latin1Readers[i] = fixed;
            } else {
              JsonTypeInfo child = subtype.child(i);
              ObjectCodec<Object> owner = erase(requireObjectOwner(child));
              Latin1ReaderCodec<Object> canonical = resolvedCapability(child, capabilities, kind);
              latin1Readers[i] =
                  newLatin1Reader(owner, canonical.getClass(), table, capabilities, canonical);
            }
          }
        }
        return latin1Readers;
      case UTF16_READER:
        Utf16ReaderCodec<Object>[] utf16Readers =
            (Utf16ReaderCodec<Object>[]) new Utf16ReaderCodec<?>[childCount];
        for (int i = 0; i < childCount; i++) {
          JsonFieldTable table = subtype.inlineReadTable(i);
          if (table != null) {
            ClosedSubtypeCodec.InlineReader fixed = subtype.fixedInlineReader(i);
            if (fixed != null) {
              utf16Readers[i] = fixed;
            } else {
              JsonTypeInfo child = subtype.child(i);
              ObjectCodec<Object> owner = erase(requireObjectOwner(child));
              Utf16ReaderCodec<Object> canonical = resolvedCapability(child, capabilities, kind);
              utf16Readers[i] =
                  newUtf16Reader(owner, canonical.getClass(), table, capabilities, canonical);
            }
          }
        }
        return utf16Readers;
      case UTF8_READER:
        Utf8ReaderCodec<Object>[] utf8Readers =
            (Utf8ReaderCodec<Object>[]) new Utf8ReaderCodec<?>[childCount];
        for (int i = 0; i < childCount; i++) {
          JsonFieldTable table = subtype.inlineReadTable(i);
          if (table != null) {
            ClosedSubtypeCodec.InlineReader fixed = subtype.fixedInlineReader(i);
            if (fixed != null) {
              utf8Readers[i] = fixed;
            } else {
              JsonTypeInfo child = subtype.child(i);
              ObjectCodec<Object> owner = erase(requireObjectOwner(child));
              Utf8ReaderCodec<Object> canonical = resolvedCapability(child, capabilities, kind);
              utf8Readers[i] =
                  newUtf8Reader(owner, canonical.getClass(), table, capabilities, canonical);
            }
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

  static boolean readerKind(CapabilityKind kind) {
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
      for (int i = 0; i < roots.size(); i++) {
        JsonTypeInfo root = roots.get(i);
        // Probe each cold root independently so an interpreter-only graph cannot reject unrelated
        // eligible roots. Successful roots are then rebuilt into one graph to preserve the existing
        // atomic parent/child publication boundary.
        CapabilityGraph candidate = new CapabilityGraph(kind);
        if (candidate.addDependency(root) && !graph.addDependency(root)) {
          throw new IllegalStateException(
              "Cannot merge eligible JSON capability graph for " + root.type());
        }
      }
      if (!graph.ordered.isEmpty()) {
        requestGraph(graph);
      }
    }
  }

  private void requestGraph(CapabilityGraph graph) {
    if (sharedRegistry.hostedCodegen()) {
      graph.classesReady().join();
      return;
    }
    if (sharedRegistry.nativeGeneratedClasses()) {
      graph.publish();
      return;
    }
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

    private boolean addDependency(JsonTypeInfo typeInfo) {
      return addDependency(typeInfo, false);
    }

    private boolean addDependency(JsonTypeInfo typeInfo, boolean slotEdge) {
      ObjectCodec<?> objectOwner = canonicalObjectOwner(typeInfo);
      if (objectOwner != null) {
        return addObject(objectOwner, typeInfo, slotEdge);
      }
      if (kind == CapabilityKind.UTF8_WRITER || kind == CapabilityKind.UTF8_READER) {
        CollectionCodec<?> collectionOwner =
            kind == CapabilityKind.UTF8_WRITER
                ? exactUtf8WriterCollectionOwner(typeInfo)
                : exactUtf8CollectionOwner(typeInfo);
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
      // A fixed object is already the complete canonical body capability. It is a resolved leaf in
      // a generated parent or closed-subtype graph and must not reject that graph merely because
      // the singleton body itself has no generated class.
      if (owner.fixedInstance()) {
        return true;
      }
      CapabilityNode existing = nodes.get(typeInfo);
      if (existing != null) {
        return existing.complete || slotEdge;
      }
      GeneratedCodecKeyBuilder keyBuilder =
          GeneratedCodecKeyBuilder.object(JsonTypeResolver.this, typeInfo, owner, kind);
      ArrayList<JsonTypeInfo> children = capabilityChildren(owner, kind);
      boolean writer = kind == CapabilityKind.STRING_WRITER || kind == CapabilityKind.UTF8_WRITER;
      boolean[] childSlots = new boolean[children.size()];
      for (int i = 0; i < children.size(); i++) {
        JsonTypeInfo child = children.get(i);
        childSlots[i] = writer ? usesWriterSlot(owner, child) : usesReaderSlot(owner, child);
      }
      keyBuilder.addCycleSlots(childSlots);
      GeneratedCodecKey generatedKey = keyBuilder.build();
      Class<?> generatedClass = sharedRegistry.nativeGeneratedClass(generatedKey);
      if (sharedRegistry.nativeGeneratedClasses()) {
        if (generatedClass == null) {
          return false;
        }
      } else if (codegen == null
          || (kind == CapabilityKind.STRING_WRITER || kind == CapabilityKind.UTF8_WRITER
              ? !codegen.canCompileWriter(generatedKey, owner)
              : !codegen.canCompileReader(generatedKey, owner))) {
        return false;
      }
      CapabilityNode node = new CapabilityNode(typeInfo, owner, initial, generatedKey);
      node.generatedClass = generatedClass;
      nodes.put(typeInfo, node);
      for (int i = 0; i < children.size(); i++) {
        if (!addDependency(children.get(i), childSlots[i])) {
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
      if (element == null) {
        return false;
      }
      GeneratedCodecKey generatedKey = GeneratedCodecKeyBuilder.collection(typeInfo, owner, kind);
      Class<?> generatedClass = sharedRegistry.nativeGeneratedClass(generatedKey);
      if (sharedRegistry.nativeGeneratedClasses() ? generatedClass == null : codegen == null) {
        return false;
      }
      CapabilityNode node = new CapabilityNode(typeInfo, owner, initial, generatedKey);
      node.generatedClass = generatedClass;
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
          generatedClass =
              node.generatedClass == null ? node.classFuture.getNow(null) : node.generatedClass;
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
    private final GeneratedCodecKey generatedKey;
    private final Object initial;
    private boolean complete;
    private CompletableFuture<Class<?>> classFuture;
    private Class<?> generatedClass;
    private Object instance;

    private CapabilityNode(
        JsonTypeInfo typeInfo,
        ObjectCodec<Object> owner,
        Object initial,
        GeneratedCodecKey generatedKey) {
      this.typeInfo = typeInfo;
      objectOwner = owner;
      collectionOwner = null;
      subtypeOwner = null;
      this.generatedKey = generatedKey;
      this.initial = initial;
    }

    private CapabilityNode(
        JsonTypeInfo typeInfo,
        CollectionCodec<?> owner,
        Object initial,
        GeneratedCodecKey generatedKey) {
      this.typeInfo = typeInfo;
      objectOwner = null;
      collectionOwner = owner;
      subtypeOwner = null;
      this.generatedKey = generatedKey;
      this.initial = initial;
    }

    private CapabilityNode(JsonTypeInfo typeInfo, ClosedSubtypeCodec owner, Object initial) {
      this.typeInfo = typeInfo;
      objectOwner = null;
      collectionOwner = null;
      subtypeOwner = owner;
      generatedKey = null;
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
          && typeInfos.get(metadataKey(typeInfo)) == typeInfo;
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
    boolean bindingOwner = enterObjectBinding(ownerType);
    try {
      ObjectCodec<T> codec = newObjectCodec(ownerType);
      // Publish the complete declared-type owner before resolving fields so recursive parameterized
      // bindings resolve back to the same field table rather than the raw-class binding.
      objectCodecs.put(key, codec);
      // The outer resolution transaction owns failure cleanup. Keep this owner published until that
      // rollback removes its canonical identity index and every other provisional graph entry.
      codec.resolveTypes(this);
      return codec;
    } finally {
      exitObjectBinding(ownerType, bindingOwner);
    }
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
    GeneratedJsonCodec<?> generatedCodec = sharedRegistry.generatedCodec(ownerType);
    return ObjectCodec.build(
        ownerType,
        sharedRegistry.propertyDiscoveryEnabled(),
        sharedRegistry.propertyNamingStrategy(),
        sharedRegistry.writeNullFields(),
        sharedRegistry,
        generatedCodec);
  }

  private JsonTypeInfo buildTypeInfo(Class<?> rawType, TypeRef<?> typeRef, Object key) {
    return buildTypeInfo(rawType, typeRef, key, null);
  }

  private JsonTypeInfo buildTypeInfo(
      Class<?> rawType, TypeRef<?> typeRef, Object key, JsonCodecFactory childFactory) {
    sharedRegistry.checkSecure(rawType);
    JsonSharedRegistry.ResolvedCodec resolved =
        sharedRegistry.resolveCodec(rawType, typeRef, this, childFactory, false);
    if (resolved == null) {
      return buildObjectTypeInfo(typeRef, key);
    }
    JsonValueCodec<?> codec = resolved.codec();
    JsonTypeInfo recursiveTypeInfo = typeInfos.get(key);
    if (recursiveTypeInfo != null) {
      return recursiveTypeInfo;
    }
    if (codec instanceof ObjectCodec) {
      boolean bindingOwner = enterObjectBinding(typeRef);
      try {
        JsonTypeInfo typeInfo = newRegisteredTypeInfo(typeRef, codec, resolved.factoryKey());
        objectCodecs.put(key, (ObjectCodec<?>) codec);
        publishTypeInfo(key, typeInfo);
        registerTypeInfoOwner(typeInfo, codec);
        resolveCodecTypes(codec, typeRef);
        return typeInfo;
      } finally {
        exitObjectBinding(typeRef, bindingOwner);
      }
    }
    JsonTypeInfo typeInfo =
        resolved.factoryKey() == null
            ? newTypeInfo(typeRef, codec)
            : newRegisteredTypeInfo(typeRef, codec, resolved.factoryKey());
    publishTypeInfo(key, typeInfo);
    registerTypeInfoOwner(typeInfo, codec);
    resolveCodecTypes(codec, typeRef);
    return typeInfo;
  }

  private void resolveCodecTypes(JsonValueCodec<?> codec, TypeRef<?> type) {
    if (codec instanceof CompositeJsonCodec) {
      ((CompositeJsonCodec<?>) codec).resolveTypes(type, this);
    }
  }

  private JsonTypeInfo buildObjectTypeInfo(TypeRef<?> ownerType, Object key) {
    JsonTypeInfo typeInfo = typeInfos.get(key);
    if (typeInfo != null) {
      return typeInfo;
    }
    ObjectCodec<?> codec = objectCodecs.get(key);
    if (codec == null) {
      boolean bindingOwner = enterObjectBinding(ownerType);
      try {
        codec = newObjectCodec(ownerType);
        typeInfo = newTypeInfo(ownerType, codec);
        // The object codec and its heterogeneous type owner are one recursive metadata unit. Both
        // must be visible before any field resolves so self-references reuse the same field table
        // and
        // capability slots. The outer cold-resolution transaction removes both on failure.
        objectCodecs.put(key, codec);
        publishTypeInfo(key, typeInfo);
        registerTypeInfoOwner(typeInfo, codec);
        codec.resolveTypes(this);
        return typeInfo;
      } finally {
        exitObjectBinding(ownerType, bindingOwner);
      }
    }
    // A public getObjectCodec call may already own construction of this shell. Bind its type info
    // now; the outer owner finishes field resolution before returning the codec to its caller.
    typeInfo = newTypeInfo(ownerType, codec);
    publishTypeInfo(key, typeInfo);
    registerTypeInfoOwner(typeInfo, codec);
    return typeInfo;
  }

  private boolean enterObjectBinding(TypeRef<?> type) {
    Class<?> rawType = type.getRawType();
    if (rawType.getTypeParameters().length == 0) {
      return false;
    }
    TypeRef<?> active = activeGenericBindings.get(rawType);
    if (active == null) {
      activeGenericBindings.put(rawType, type);
      return true;
    }
    if (!active.getTypeArguments().equals(type.getTypeArguments())) {
      throw expandingGenericType(rawType, active, type);
    }
    return false;
  }

  private void exitObjectBinding(TypeRef<?> type, boolean owner) {
    if (owner) {
      activeGenericBindings.remove(type.getRawType());
    }
  }

  private static ForyJsonException expandingGenericType(
      Class<?> rawType, TypeRef<?> active, TypeRef<?> nested) {
    return new ForyJsonException(
        "JSON generic recursion expands "
            + rawType.getName()
            + " from "
            + active
            + " to "
            + nested);
  }

  private JsonTypeInfo newTypeInfo(Type type, Class<?> rawType, JsonValueCodec<?> codec) {
    return newTypeInfo(typeRef(type, rawType), codec);
  }

  private JsonTypeInfo newTypeInfo(TypeRef<?> typeRef, JsonValueCodec<?> codec) {
    return new JsonTypeInfo(typeRef, sharedRegistry.kind(typeRef.getRawType()), bindCodec(codec));
  }

  private JsonTypeInfo newTypeInfo(
      TypeRef<?> typeRef, JsonFieldKind kind, JsonValueCodec<?> codec, boolean annotationCodec) {
    return new JsonTypeInfo(typeRef, kind, bindCodec(codec), annotationCodec);
  }

  private JsonTypeInfo newRegisteredTypeInfo(
      TypeRef<?> typeRef, JsonValueCodec<?> codec, String factoryKey) {
    return new JsonTypeInfo(
        typeRef,
        sharedRegistry.kind(typeRef.getRawType()),
        bindCodec(codec),
        false,
        factoryKey,
        factoryKey == null && !(codec instanceof ObjectCodec<?>) ? codec.getClass() : null);
  }

  private void registerTypeInfoOwner(JsonTypeInfo typeInfo, JsonValueCodec<?> initialCodec) {
    if (initialCodec instanceof CollectionCodec) {
      collectionCodecs.put(typeInfo, (CollectionCodec<?>) initialCodec);
    }
    if (initialCodec instanceof ObjectCodec && typeInfo.rawType() != Object.class) {
      ObjectCodec<?> owner = (ObjectCodec<?>) initialCodec;
      canonicalObjectTypeInfos.put(owner, typeInfo);
    }
  }

  private void publishTypeInfo(Object key, JsonTypeInfo typeInfo) {
    typeInfos.put(key, typeInfo);
    if (key instanceof SubtypeTypeKey) {
      subtypeTypeRoots.put(typeInfo, ((SubtypeTypeKey) key).baseType);
    }
  }

  private Object metadataKey(JsonTypeInfo typeInfo) {
    Class<?> subtypeRoot = subtypeTypeRoots.get(typeInfo);
    Object key = typeInfoKey(typeInfo.typeRef());
    return subtypeRoot == null ? key : new SubtypeTypeKey(subtypeRoot, key);
  }

  private Object resolutionTypeKey(Type declaredType, Class<?> rawType) {
    if (!isolateSubtypeResolution || subtypeResolutionBase == null) {
      return typeInfoKey(declaredType, rawType);
    }
    // An exact closed-sum factory owns its branch metadata. Scope every type containing a branch
    // to that root so recursive binding can reuse it without making the branch a declared schema.
    return subtypeTypeKey(subtypeResolutionBase, declaredType, rawType);
  }

  private Object resolutionTypeKey(TypeRef<?> declaredType) {
    if (!isolateSubtypeResolution || subtypeResolutionBase == null) {
      return typeInfoKey(declaredType);
    }
    Object key = typeInfoKey(declaredType);
    return referencesSubtype(
            subtypeResolutionBase, declaredType.getType(), declaredType.getRawType())
        ? new SubtypeTypeKey(subtypeResolutionBase, key)
        : key;
  }

  private static Object subtypeTypeKey(Class<?> baseType, Type declaredType, Class<?> rawType) {
    Object key = typeInfoKey(declaredType, rawType);
    return referencesSubtype(baseType, declaredType, rawType)
        ? new SubtypeTypeKey(baseType, key)
        : key;
  }

  private static boolean referencesSubtype(Class<?> baseType, Type declaredType, Class<?> rawType) {
    if (rawType != baseType && baseType.isAssignableFrom(rawType)) {
      return true;
    }
    if (declaredType instanceof ParameterizedType) {
      Type[] arguments = ((ParameterizedType) declaredType).getActualTypeArguments();
      for (Type argument : arguments) {
        Class<?> argumentType = CodecUtils.rawType(argument, Object.class);
        if (referencesSubtype(baseType, argument, argumentType)) {
          return true;
        }
      }
    } else if (declaredType instanceof GenericArrayType) {
      Type elementType = ((GenericArrayType) declaredType).getGenericComponentType();
      return referencesSubtype(
          baseType, elementType, CodecUtils.rawType(elementType, Object.class));
    } else if (declaredType instanceof WildcardType) {
      WildcardType wildcard = (WildcardType) declaredType;
      for (Type bound : wildcard.getUpperBounds()) {
        if (referencesSubtype(baseType, bound, CodecUtils.rawType(bound, Object.class))) {
          return true;
        }
      }
      for (Type bound : wildcard.getLowerBounds()) {
        if (referencesSubtype(baseType, bound, CodecUtils.rawType(bound, Object.class))) {
          return true;
        }
      }
    }
    return false;
  }

  private static Object typeInfoKey(Type declaredType, Class<?> rawType) {
    return declaredType instanceof Class ? rawType : declaredType;
  }

  private static Object typeInfoKey(TypeRef<?> declaredType) {
    if (declaredType.hasTypeExtMeta()) {
      return declaredType;
    }
    return typeInfoKey(declaredType.getType(), declaredType.getRawType());
  }

  private static final class SubtypeTypeKey {
    private final Class<?> baseType;
    private final Object typeKey;

    private SubtypeTypeKey(Class<?> baseType, Object typeKey) {
      this.baseType = baseType;
      this.typeKey = typeKey;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof SubtypeTypeKey)) {
        return false;
      }
      SubtypeTypeKey that = (SubtypeTypeKey) other;
      return baseType == that.baseType && typeKey.equals(that.typeKey);
    }

    @Override
    public int hashCode() {
      return 31 * System.identityHashCode(baseType) + typeKey.hashCode();
    }
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

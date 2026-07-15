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

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.chrono.HijrahDate;
import java.time.chrono.JapaneseDate;
import java.time.chrono.MinguoDate;
import java.time.chrono.ThaiBuddhistDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Currency;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.regex.Pattern;
import org.apache.fory.exception.InsecureException;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.JsonConfig;
import org.apache.fory.json.JsonTypeCheckContext;
import org.apache.fory.json.JsonTypeChecker;
import org.apache.fory.json.PropertyNamingStrategy;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.apache.fory.json.annotation.JsonSubTypes.Inclusion;
import org.apache.fory.json.codec.ArrayCodec;
import org.apache.fory.json.codec.CodecUtils;
import org.apache.fory.json.codec.CollectionCodec;
import org.apache.fory.json.codec.GuavaCodecs;
import org.apache.fory.json.codec.JsonSubTypesInfo;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.codec.MapCodec;
import org.apache.fory.json.codec.ScalarCodecs;
import org.apache.fory.json.codec.SqlJsonCodecs;
import org.apache.fory.json.codegen.JsonCodegen;
import org.apache.fory.json.codegen.JsonJITContext;
import org.apache.fory.json.meta.JsonClassNames;
import org.apache.fory.json.meta.JsonCodecFactory;
import org.apache.fory.json.meta.JsonFieldKind;
import org.apache.fory.json.meta.JsonTypeUse;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.DisallowedList;
import org.apache.fory.type.BFloat16;
import org.apache.fory.type.Float16;
import org.apache.fory.type.TypeUtils;

/**
 * Shared codec definitions and cold caches for all local resolvers of one {@code ForyJson}.
 *
 * <p>This owner snapshots custom codecs, registers exact built-in codecs, classifies field kinds,
 * applies class-name security checks, and selects the semantic codec family for a resolved type.
 * Default exact codecs bypass the user checker because their behavior is fixed and does not create
 * object metadata; a custom binding for the same class restores normal checking. The disallow list
 * is always applied before the user checker.
 *
 * <p>Accepted type-check results are cached by class name up to a bounded 8192-entry shared cache.
 * Once full, new names are checked on every resolution rather than growing attacker-controlled
 * state. Generated classes are shared through one {@link JsonCodegen}; generated instances,
 * ordinary type bindings, JIT locks, and callbacks remain resolver-local. A fresh generic {@link
 * JsonJITContext} is therefore created for every pooled JSON state.
 */
public class JsonSharedRegistry {
  private static final int TYPE_CHECK_CACHE_LIMIT = 8192;
  private static final boolean CODEC_ANNOTATIONS_ENABLED = !AndroidSupport.IS_ANDROID;
  static final Comparator<JsonCodecCandidate> DECLARATION_ORDER =
      new Comparator<JsonCodecCandidate>() {
        @Override
        public int compare(JsonCodecCandidate left, JsonCodecCandidate right) {
          int result = left.declarationType.getName().compareTo(right.declarationType.getName());
          if (result != 0) {
            return result;
          }
          return left.codecClass.getName().compareTo(right.codecClass.getName());
        }
      };

  private final CodecRegistry customCodecs;
  private final IdentityHashMap<Class<?>, JsonValueCodec<?>> exactCodecs;
  private final JsonTypeChecker typeChecker;
  private final JsonTypeCheckContext typeCheckContext;
  private final ConcurrentHashMap<String, Boolean> typeCheckCache;
  private final Object typeCheckCacheLock;
  private final JsonCodegen codegen;
  private final boolean asyncCompilationEnabled;
  private final ExecutorService compilationService;
  private final boolean propertyDiscoveryEnabled;
  private final PropertyNamingStrategy propertyNamingStrategy;
  private final boolean writeNullFields;
  private final ClassLoader classLoader;
  final IdentityHashMap<Class<?>, JsonSubTypesInfo> subTypesCache;
  final IdentityHashMap<Class<?>, JsonCodecDeclaration> codecDeclarations;
  final Set<Class<?>> typesWithoutCodecDeclaration;
  final ConcurrentHashMap<Class<? extends JsonValueCodec<?>>, JsonValueCodec<?>> annotationCodecs;

  /** Creates the platform registry for one immutable JSON configuration. */
  public static JsonSharedRegistry create(JsonConfig config) {
    return AndroidSupport.IS_ANDROID
        ? AndroidJsonSharedRegistry.newRegistry(config)
        : new JsonSharedRegistry(config, null);
  }

  JsonSharedRegistry(JsonConfig config, ExecutorService compilationService) {
    this(config, compilationService, config.codegenEnabled());
  }

  JsonSharedRegistry(
      JsonConfig config, ExecutorService compilationService, boolean codegenEnabled) {
    this.customCodecs = config.codecRegistry().copy();
    typeChecker = config.typeChecker();
    typeCheckContext = config.typeCheckContext();
    typeCheckCache = typeChecker == null ? null : new ConcurrentHashMap<>();
    typeCheckCacheLock = typeChecker == null ? null : new Object();
    this.propertyDiscoveryEnabled = config.propertyDiscoveryEnabled();
    propertyNamingStrategy = config.propertyNamingStrategy();
    writeNullFields = config.writeNullFields();
    classLoader = config.classLoader();
    exactCodecs = new IdentityHashMap<>();
    subTypesCache = new IdentityHashMap<>();
    codecDeclarations = new IdentityHashMap<>();
    typesWithoutCodecDeclaration =
        Collections.newSetFromMap(new IdentityHashMap<Class<?>, Boolean>());
    annotationCodecs = new ConcurrentHashMap<>();
    codegen = codegenEnabled ? new JsonCodegen(config.getCodegenHash(), classLoader) : null;
    asyncCompilationEnabled = codegenEnabled && config.asyncCompilationEnabled();
    this.compilationService = compilationService;
    registerExactCodecs();
  }

  JsonTypeMetadataRegistry metadataRegistry() {
    return null;
  }

  Class<?> loadGeneratedValueType(String className) {
    checkSecureName(className);
    Class<?> type = loadClass(className);
    if (!type.getName().equals(className)) {
      throw new ForyJsonException("Generated JSON type name mismatch for " + className);
    }
    checkSecure(type);
    return type;
  }

  public JsonValueCodec<?> createCodec(
      Class<?> rawType, TypeRef<?> typeRef, JsonTypeResolver localResolver) {
    JsonValueCodec<?> customCodec = customCodecs.get(rawType);
    if (customCodec != null) {
      return customCodec;
    }
    JsonValueCodec<?> codec = exactCodecs.get(rawType);
    if (codec != null) {
      return codec;
    }
    if (rawType == Class.class) {
      // JSON strings must not be treated as class-loading authority by the default codecs.
      throw new ForyJsonException("Unsupported JSON type " + rawType);
    }
    if (InetAddress.class.isAssignableFrom(rawType)
        || InetSocketAddress.class.isAssignableFrom(rawType)) {
      throw new ForyJsonException("Unsupported JSON type " + rawType);
    }
    if (URL.class.isAssignableFrom(rawType)) {
      throw new ForyJsonException("Unsupported JSON type " + rawType);
    }
    if (Number.class.isAssignableFrom(rawType) || CharSequence.class.isAssignableFrom(rawType)) {
      throw new ForyJsonException("Unsupported JSON type " + rawType);
    }
    if (rawType.isEnum()) {
      return new ScalarCodecs.EnumCodec(rawType);
    }
    if (rawType.isArray()) {
      return ArrayCodec.create(rawType, localResolver);
    }
    if (rawType == Optional.class) {
      return new ScalarCodecs.OptionalCodec(
          CodecUtils.elementType(typeRef.getType()), localResolver);
    }
    if (rawType == AtomicReference.class) {
      return new ScalarCodecs.AtomicReferenceCodec(
          CodecUtils.elementType(typeRef.getType()), localResolver);
    }
    if (rawType == AtomicReferenceArray.class) {
      return new ScalarCodecs.AtomicReferenceArrayCodec(
          CodecUtils.elementType(typeRef.getType()), localResolver);
    }
    if (Calendar.class.isAssignableFrom(rawType)) {
      return ScalarCodecs.CalendarCodec.INSTANCE;
    }
    if (Date.class.isAssignableFrom(rawType)) {
      return ScalarCodecs.DateCodec.INSTANCE;
    }
    if (ZoneId.class.isAssignableFrom(rawType)) {
      return ScalarCodecs.ZoneIdCodec.INSTANCE;
    }
    if (ByteBuffer.class.isAssignableFrom(rawType)) {
      return ScalarCodecs.ByteBufferCodec.INSTANCE;
    }
    if (File.class.isAssignableFrom(rawType)) {
      return ScalarCodecs.FileCodec.INSTANCE;
    }
    if (Path.class.isAssignableFrom(rawType)) {
      return ScalarCodecs.PathCodec.INSTANCE;
    }
    if (Collection.class.isAssignableFrom(rawType)) {
      return CollectionCodec.create(rawType, typeRef, localResolver);
    }
    if (Map.class.isAssignableFrom(rawType)) {
      return MapCodec.create(rawType, typeRef, localResolver);
    }
    return null;
  }

  JsonValueCodec<?> createCodec(JsonTypeUse typeUse, JsonTypeResolver localResolver) {
    Class<?> rawType = typeUse.rawType();
    if (rawType.isArray()) {
      JsonTypeUse component = typeUse.arrayComponent();
      return ArrayCodec.create(rawType, localResolver.getTypeInfo(component));
    }
    if (rawType == Optional.class) {
      return new ScalarCodecs.OptionalCodec(localResolver.getTypeInfo(typeUse.argument(0)));
    }
    if (rawType == AtomicReference.class) {
      return new ScalarCodecs.AtomicReferenceCodec(localResolver.getTypeInfo(typeUse.argument(0)));
    }
    if (rawType == AtomicReferenceArray.class) {
      return new ScalarCodecs.AtomicReferenceArrayCodec(
          localResolver.getTypeInfo(typeUse.argument(0)));
    }
    if (Collection.class.isAssignableFrom(rawType)) {
      JsonTypeUse collection = typeUse.projectTo(Collection.class, rawType.getTypeName());
      JsonTypeUse element = collection.argument(0);
      return CollectionCodec.create(rawType, element.rawType(), localResolver.getTypeInfo(element));
    }
    if (Map.class.isAssignableFrom(rawType)) {
      JsonTypeUse map = typeUse.projectTo(Map.class, rawType.getTypeName());
      JsonTypeUse key = map.argument(0);
      key.rejectMapKey(rawType.getTypeName());
      localResolver.checkMapKeySecure(key.rawType());
      JsonTypeUse value = map.argument(1);
      return MapCodec.create(rawType, key.rawType(), localResolver.getTypeInfo(value));
    }
    JsonValueCodec<?> codec = createCodec(rawType, TypeRef.of(typeUse.type()), localResolver);
    if (codec != null) {
      typeUse.rejectExplicitDescendants(rawType.getTypeName());
    }
    return codec;
  }

  public JsonFieldKind kind(Class<?> type) {
    // A registered codec owns the full representation. Resolve that choice before object metadata
    // and codegen specialize fields so generated and interpreted paths cannot bypass the codec.
    if (customCodecs.get(type) != null) {
      return JsonFieldKind.OBJECT;
    }
    if (type == boolean.class || type == Boolean.class) {
      return JsonFieldKind.BOOLEAN;
    }
    if (type == byte.class || type == Byte.class) {
      return JsonFieldKind.BYTE;
    }
    if (type == short.class || type == Short.class) {
      return JsonFieldKind.SHORT;
    }
    if (type == int.class || type == Integer.class) {
      return JsonFieldKind.INT;
    }
    if (type == long.class || type == Long.class) {
      return JsonFieldKind.LONG;
    }
    if (type == float.class || type == Float.class) {
      return JsonFieldKind.FLOAT;
    }
    if (type == double.class || type == Double.class) {
      return JsonFieldKind.DOUBLE;
    }
    if (type == char.class || type == Character.class) {
      return JsonFieldKind.CHAR;
    }
    if (type == String.class) {
      return JsonFieldKind.STRING;
    }
    if (type.isEnum()) {
      return JsonFieldKind.ENUM;
    }
    if (type.isArray()) {
      return JsonFieldKind.ARRAY;
    }
    if (Collection.class.isAssignableFrom(type)) {
      return JsonFieldKind.COLLECTION;
    }
    if (Map.class.isAssignableFrom(type)) {
      return JsonFieldKind.MAP;
    }
    return JsonFieldKind.OBJECT;
  }

  JsonJITContext newJITContext() {
    return new JsonJITContext(asyncCompilationEnabled, compilationService);
  }

  JsonCodegen codegen() {
    return codegen;
  }

  boolean propertyDiscoveryEnabled() {
    return propertyDiscoveryEnabled;
  }

  PropertyNamingStrategy propertyNamingStrategy() {
    return propertyNamingStrategy;
  }

  boolean writeNullFields() {
    return writeNullFields;
  }

  ClassLoader classLoader() {
    return classLoader;
  }

  JsonValueCodec<?> customCodec(Class<?> type) {
    return customCodecs.get(type);
  }

  JsonCodecDeclaration codecDeclaration(Class<?> targetType) {
    if (!CODEC_ANNOTATIONS_ENABLED || targetType.isAnnotation()) {
      return null;
    }
    synchronized (codecDeclarations) {
      JsonCodecDeclaration cached = codecDeclarations.get(targetType);
      if (cached != null) {
        return cached;
      }
      if (typesWithoutCodecDeclaration.contains(targetType)) {
        return null;
      }
      JsonCodecDeclaration resolved = resolveCodecDeclaration(targetType);
      if (resolved == null) {
        typesWithoutCodecDeclaration.add(targetType);
      } else {
        codecDeclarations.put(targetType, resolved);
      }
      return resolved;
    }
  }

  JsonValueCodec<?> annotationCodec(
      Class<?> targetType, Class<? extends JsonValueCodec<?>> codecClass) {
    checkCustomSecure(targetType);
    return annotationCodecs.computeIfAbsent(codecClass, JsonSharedRegistry::newAnnotationCodec);
  }

  JsonValueCodec<?> annotationCodec(
      Class<?> targetType,
      Class<? extends JsonValueCodec<?>> codecClass,
      JsonCodecFactory factory) {
    return annotationCodec(targetType, codecClass);
  }

  private JsonCodecDeclaration resolveCodecDeclaration(Class<?> targetType) {
    JsonCodecCandidate direct = declaredCodec(targetType);
    if (direct != null) {
      return new JsonCodecDeclaration(direct.codecClass, new Class<?>[] {targetType}, false);
    }
    List<JsonCodecCandidate> candidates = inheritedCodecCandidates(targetType);
    if (candidates.isEmpty()) {
      return null;
    }
    List<JsonCodecCandidate> frontier = mostSpecificDeclarations(candidates);
    Collections.sort(frontier, DECLARATION_ORDER);
    Class<? extends JsonValueCodec<?>> codecClass = frontier.get(0).codecClass;
    for (int i = 1; i < frontier.size(); i++) {
      if (frontier.get(i).codecClass != codecClass) {
        throw inheritedCodecConflict(targetType, frontier);
      }
    }
    Class<?>[] origins = new Class<?>[frontier.size()];
    for (int i = 0; i < frontier.size(); i++) {
      origins[i] = frontier.get(i).declarationType;
    }
    return new JsonCodecDeclaration(codecClass, origins, true);
  }

  static List<JsonCodecCandidate> inheritedCodecCandidates(Class<?> targetType) {
    List<JsonCodecCandidate> candidates = new ArrayList<>();
    Set<Class<?>> visited = Collections.newSetFromMap(new IdentityHashMap<Class<?>, Boolean>());
    Deque<Class<?>> pending = new ArrayDeque<>();
    addParents(targetType, pending);
    while (!pending.isEmpty()) {
      Class<?> current = pending.removeFirst();
      if (!visited.add(current)) {
        continue;
      }
      if (!current.isAnnotation()) {
        JsonCodecCandidate candidate = declaredCodec(current);
        if (candidate != null) {
          candidates.add(candidate);
        }
      }
      addParents(current, pending);
    }
    return candidates;
  }

  private static void addParents(Class<?> type, Deque<Class<?>> pending) {
    Class<?> superclass = type.getSuperclass();
    if (superclass != null) {
      pending.addLast(superclass);
    }
    Class<?>[] interfaces = type.getInterfaces();
    for (Class<?> interfaceType : interfaces) {
      pending.addLast(interfaceType);
    }
  }

  static List<JsonCodecCandidate> mostSpecificDeclarations(List<JsonCodecCandidate> candidates) {
    List<JsonCodecCandidate> frontier = new ArrayList<>(candidates.size());
    for (JsonCodecCandidate candidate : candidates) {
      boolean dominated = false;
      for (JsonCodecCandidate other : candidates) {
        if (candidate.declarationType != other.declarationType
            && candidate.declarationType.isAssignableFrom(other.declarationType)) {
          dominated = true;
          break;
        }
      }
      if (!dominated) {
        frontier.add(candidate);
      }
    }
    return frontier;
  }

  private static JsonCodecCandidate declaredCodec(Class<?> declarationType) {
    JsonCodec annotation;
    try {
      annotation = declarationType.getDeclaredAnnotation(JsonCodec.class);
    } catch (RuntimeException | LinkageError e) {
      throw new ForyJsonException(
          "Cannot read @JsonCodec declared on " + declarationType.getName(), e);
    }
    if (annotation == null) {
      return null;
    }
    try {
      return new JsonCodecCandidate(declarationType, annotation.value());
    } catch (RuntimeException | LinkageError e) {
      throw new ForyJsonException(
          "Cannot resolve @JsonCodec declared on " + declarationType.getName(), e);
    }
  }

  static ForyJsonException inheritedCodecConflict(
      Class<?> targetType, List<JsonCodecCandidate> frontier) {
    StringBuilder message =
        new StringBuilder("Conflicting inherited @JsonCodec declarations for ")
            .append(targetType.getName())
            .append(':');
    for (JsonCodecCandidate candidate : frontier) {
      message
          .append(' ')
          .append(candidate.declarationType.getName())
          .append(" -> ")
          .append(candidate.codecClass.getName())
          .append(';');
    }
    message
        .append(" declare @JsonCodec on ")
        .append(targetType.getName())
        .append(", annotate the current type use, or register an exact codec for ")
        .append(targetType.getName())
        .append(".");
    return new ForyJsonException(message.toString());
  }

  private static JsonValueCodec<?> newAnnotationCodec(
      Class<? extends JsonValueCodec<?>> codecClass) {
    validateCodecClass(codecClass);
    Constructor<? extends JsonValueCodec<?>> constructor;
    try {
      constructor = codecClass.getConstructor();
    } catch (NoSuchMethodException e) {
      throw invalidCodecClass(codecClass, "must have a public no-argument constructor", e);
    } catch (SecurityException e) {
      throw inaccessibleCodecClass(codecClass, e);
    } catch (LinkageError e) {
      throw invalidCodecClass(codecClass, "constructor cannot be inspected", e);
    }
    try {
      return constructor.newInstance();
    } catch (IllegalAccessException e) {
      // A public constructor in an exported package is directly invocable. A package that is only
      // opened to Fory requires access elevation; a closed package fails before application code
      // can run.
      try {
        constructor.setAccessible(true);
        return constructor.newInstance();
      } catch (InvocationTargetException retryFailure) {
        throw invalidCodecClass(codecClass, "constructor failed", retryFailure.getCause());
      } catch (ReflectiveOperationException | LinkageError | RuntimeException retryFailure) {
        throw inaccessibleCodecClass(codecClass, retryFailure);
      }
    } catch (InvocationTargetException e) {
      throw invalidCodecClass(codecClass, "constructor failed", e.getCause());
    } catch (ReflectiveOperationException | LinkageError e) {
      throw invalidCodecClass(codecClass, "cannot be constructed", e);
    } catch (RuntimeException e) {
      throw invalidCodecClass(codecClass, "cannot be constructed", e);
    }
  }

  static void validateCodecClass(Class<? extends JsonValueCodec<?>> codecClass) {
    int modifiers = codecClass.getModifiers();
    if (!Modifier.isPublic(modifiers)) {
      throw invalidCodecClass(codecClass, "must be public", null);
    }
    if (codecClass.isInterface() || Modifier.isAbstract(modifiers)) {
      throw invalidCodecClass(codecClass, "must be concrete", null);
    }
    if (codecClass.getEnclosingClass() != null
        && (!codecClass.isMemberClass() || !Modifier.isStatic(modifiers))) {
      throw invalidCodecClass(codecClass, "must be top-level or a static nested class", null);
    }
    for (Class<?> enclosing = codecClass.getEnclosingClass();
        enclosing != null;
        enclosing = enclosing.getEnclosingClass()) {
      if (!Modifier.isPublic(enclosing.getModifiers())) {
        throw invalidCodecClass(codecClass, "must be enclosed only by public classes", null);
      }
    }
  }

  private static ForyJsonException inaccessibleCodecClass(
      Class<? extends JsonValueCodec<?>> codecClass, Throwable cause) {
    Package codecPackage = codecClass.getPackage();
    String packageName = codecPackage == null ? "the unnamed package" : codecPackage.getName();
    return new ForyJsonException(
        "Cannot access the public no-argument constructor of JSON codec "
            + codecClass.getName()
            + "; export or open package "
            + packageName
            + " to module org.apache.fory.json.",
        cause);
  }

  static ForyJsonException invalidCodecClass(
      Class<? extends JsonValueCodec<?>> codecClass, String reason, Throwable cause) {
    String message = "JSON codec " + codecClass.getName() + ' ' + reason + '.';
    return cause == null ? new ForyJsonException(message) : new ForyJsonException(message, cause);
  }

  JsonSubTypesInfo subTypesInfo(Class<?> baseType) {
    JsonSubTypes annotation = baseType.getDeclaredAnnotation(JsonSubTypes.class);
    if (annotation == null) {
      return null;
    }
    synchronized (subTypesCache) {
      JsonSubTypesInfo cached = subTypesCache.get(baseType);
      if (cached != null) {
        return cached;
      }
      JsonSubTypesInfo resolved = buildSubTypesInfo(baseType, annotation);
      subTypesCache.put(baseType, resolved);
      return resolved;
    }
  }

  private JsonSubTypesInfo buildSubTypesInfo(Class<?> baseType, JsonSubTypes annotation) {
    Inclusion inclusion = annotation.inclusion();
    String property = annotation.property();
    JsonSubTypes.Type[] entries = annotation.value();
    validateSubtypeHeader(baseType, inclusion, property, entries.length);
    String[] names = new String[entries.length];
    String[] classNames = new String[entries.length];
    boolean hasStringEntry = false;
    Set<String> logicalNames = new HashSet<>();
    Set<Long> logicalHashes = new HashSet<>();
    for (int i = 0; i < entries.length; i++) {
      JsonSubTypes.Type entry = entries[i];
      String name = entry.name();
      validateSubtypeName(name, logicalNames, logicalHashes);
      names[i] = name;
      boolean literal = entry.value() != Void.class;
      boolean byName = !entry.className().isEmpty();
      if (literal == byName) {
        throw new ForyJsonException(
            "JSON subtype must declare exactly one of value or className for " + name);
      }
      if (byName) {
        JsonClassNames.requireBinaryName(entry.className(), "@JsonSubTypes className");
        classNames[i] = entry.className();
        hasStringEntry = true;
      }
    }
    validateStringSubtypeMode(baseType, hasStringEntry);
    for (String className : classNames) {
      if (className != null) {
        checkSecureName(className);
      }
    }
    if (hasStringEntry) {
      validateSubtypeBaseLoader(baseType);
    }
    Class<?>[] classes = new Class<?>[entries.length];
    Set<Class<?>> classIdentities = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    Set<String> binaryNames = new HashSet<>();
    for (int i = 0; i < entries.length; i++) {
      Class<?> subtype = classNames[i] == null ? entries[i].value() : loadExactClass(classNames[i]);
      validateSubtypeClass(baseType, subtype, classIdentities, binaryNames);
      classes[i] = subtype;
    }
    return new JsonSubTypesInfo(inclusion, property, classes, names);
  }

  static void validateSubtypeHeader(
      Class<?> baseType, Inclusion inclusion, String property, int entryCount) {
    if (!baseType.isInterface() && !Modifier.isAbstract(baseType.getModifiers())) {
      throw new ForyJsonException(
          "@JsonSubTypes requires an interface or abstract type " + baseType);
    }
    if (inclusion == Inclusion.PROPERTY) {
      if (property.isEmpty()) {
        throw new ForyJsonException("PROPERTY @JsonSubTypes requires a discriminator property");
      }
      validateJsonName(property, "subtype discriminator");
    } else if (!property.isEmpty()) {
      throw new ForyJsonException(inclusion + " @JsonSubTypes must not declare property");
    }
    if (entryCount == 0) {
      throw new ForyJsonException("@JsonSubTypes must declare at least one subtype");
    }
  }

  static void validateSubtypeName(String name, Set<String> logicalNames, Set<Long> logicalHashes) {
    validateJsonName(name, "subtype");
    if (!logicalNames.add(name)) {
      throw new ForyJsonException("Invalid or duplicate JSON subtype name " + name);
    }
    long hash = org.apache.fory.json.meta.JsonFieldNameHash.hash(name);
    if (!logicalHashes.add(Long.valueOf(hash))) {
      throw new ForyJsonException("JSON subtype name hash collision for " + name);
    }
  }

  static void validateStringSubtypeMode(Class<?> baseType, boolean hasStringEntry) {
    if (hasStringEntry && GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      throw new ForyJsonException(
          "GraalVM native image requires @JsonSubTypes class literals on " + baseType.getName());
    }
  }

  void validateSubtypeBaseLoader(Class<?> baseType) {
    Class<?> loadedBase = loadClass(baseType.getName());
    if (loadedBase != baseType) {
      throw new ForyJsonException(
          "Configured class loader resolves a different subtype base " + baseType.getName());
    }
  }

  void validateSubtypeClass(
      Class<?> baseType, Class<?> subtype, Set<Class<?>> classIdentities, Set<String> binaryNames) {
    checkSubtypeSecure(subtype);
    int modifiers = subtype.getModifiers();
    if (subtype == Void.class
        || subtype.isPrimitive()
        || subtype.isArray()
        || subtype.isInterface()
        || Modifier.isAbstract(modifiers)
        || !baseType.isAssignableFrom(subtype)) {
      throw new ForyJsonException(
          "Invalid closed JSON subtype " + subtype.getName() + " for " + baseType.getName());
    }
    if (!classIdentities.add(subtype) || !binaryNames.add(subtype.getName())) {
      throw new ForyJsonException("Duplicate closed JSON subtype " + subtype.getName());
    }
  }

  boolean hasIntrinsicCodec(Class<?> type) {
    // Android exposes bootstrap classes through BootClassLoader rather than the JVM's null loader.
    // Compare loader identity so platform collections remain intrinsic without treating an
    // application-defined Collection or Map subclass as platform-owned.
    return customCodecs.get(type) == null
        && (exactCodecs.containsKey(type)
            || type.isArray()
            || type == Optional.class
            || type == AtomicReference.class
            || type == AtomicReferenceArray.class
            || (type.getClassLoader() == Object.class.getClassLoader() && isBuiltInFamily(type)));
  }

  static boolean isBuiltInFamily(Class<?> type) {
    return InetAddress.class.isAssignableFrom(type)
        || InetSocketAddress.class.isAssignableFrom(type)
        || URL.class.isAssignableFrom(type)
        || Number.class.isAssignableFrom(type)
        || CharSequence.class.isAssignableFrom(type)
        || Calendar.class.isAssignableFrom(type)
        || Date.class.isAssignableFrom(type)
        || ZoneId.class.isAssignableFrom(type)
        || ByteBuffer.class.isAssignableFrom(type)
        || File.class.isAssignableFrom(type)
        || Path.class.isAssignableFrom(type)
        || Collection.class.isAssignableFrom(type)
        || Map.class.isAssignableFrom(type);
  }

  void checkSecureName(String className) {
    DisallowedList.checkNotInDisallowedList(className);
    JsonTypeChecker checker = typeChecker;
    if (checker != null && !checkType(className, checker)) {
      throw forbiddenClass(className);
    }
  }

  private void checkSubtypeSecure(Class<?> type) {
    DisallowedList.checkNotInDisallowedList(type.getName());
    JsonTypeChecker checker = typeChecker;
    if (checker != null && !checkType(type.getName(), checker)) {
      throw forbiddenClass(type.getName());
    }
  }

  Class<?> loadExactClass(String className) {
    Class<?> type = loadClass(className);
    if (!type.getName().equals(className)) {
      throw new ForyJsonException("Subtype binary name mismatch for " + className);
    }
    return type;
  }

  private Class<?> loadClass(String className) {
    try {
      return Class.forName(className, false, classLoader);
    } catch (ClassNotFoundException | LinkageError e) {
      throw new ForyJsonException("Cannot resolve closed JSON subtype " + className, e);
    }
  }

  private static void validateJsonName(String value, String role) {
    if (value.isEmpty()) {
      throw new ForyJsonException("JSON " + role + " name must not be empty");
    }
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (Character.isHighSurrogate(ch)) {
        if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
          throw new ForyJsonException("Unpaired surrogate in JSON " + role + " name");
        }
      } else if (Character.isLowSurrogate(ch)) {
        throw new ForyJsonException("Unpaired surrogate in JSON " + role + " name");
      }
    }
  }

  void checkSecure(Class<?> type) {
    boolean secure = customCodecs.get(type) == null ? isSecureType(type) : isCustomSecureType(type);
    if (!secure) {
      throw forbiddenClass(type.getName());
    }
  }

  void checkCustomSecure(Class<?> type) {
    if (!isCustomSecureType(type)) {
      throw forbiddenClass(type.getName());
    }
  }

  void checkMapKeySecure(Class<?> type) {
    if (!isMapKeySecureType(type)) {
      throw forbiddenClass(type.getName());
    }
  }

  private boolean isSecureType(Class<?> type) {
    if (type.isArray()) {
      return isSecureType(TypeUtils.getArrayComponent(type));
    }
    if (!type.isEnum() && Enum.class.isAssignableFrom(type) && type != Enum.class) {
      Class<?> enclosingClass = type.getEnclosingClass();
      if (enclosingClass != null && enclosingClass.isEnum()) {
        return isSecureType(enclosingClass);
      }
    }
    String className = type.getName();
    DisallowedList.checkNotInDisallowedList(className);
    // Built-in codec exemption follows the same Class identity key as exact codec dispatch. A
    // same-named class from another loader must still pass the configured checker.
    if (exactCodecs.containsKey(type) && customCodecs.get(type) == null) {
      return true;
    }
    JsonTypeChecker checker = typeChecker;
    if (checker == null) {
      return true;
    }
    return checkType(className, checker);
  }

  private boolean isCustomSecureType(Class<?> type) {
    if (type.isArray()) {
      return isCustomSecureType(TypeUtils.getArrayComponent(type));
    }
    if (!type.isEnum() && Enum.class.isAssignableFrom(type) && type != Enum.class) {
      Class<?> enclosingClass = type.getEnclosingClass();
      if (enclosingClass != null && enclosingClass.isEnum()) {
        return isCustomSecureType(enclosingClass);
      }
    }
    String className = type.getName();
    DisallowedList.checkNotInDisallowedList(className);
    JsonTypeChecker checker = typeChecker;
    return checker == null || checkType(className, checker);
  }

  private boolean isMapKeySecureType(Class<?> type) {
    if (type.isArray()) {
      return isMapKeySecureType(TypeUtils.getArrayComponent(type));
    }
    String className = type.getName();
    DisallowedList.checkNotInDisallowedList(className);
    // A JsonValueCodec registration has no authority over a map-key occurrence. Preserve the
    // ordinary exact built-in exemption even when the same raw class has a registered value codec.
    if (exactCodecs.containsKey(type)) {
      return true;
    }
    JsonTypeChecker checker = typeChecker;
    return checker == null || checkType(className, checker);
  }

  private boolean checkType(String className, JsonTypeChecker checker) {
    ConcurrentHashMap<String, Boolean> cache = typeCheckCache;
    Boolean cached = cache.get(className);
    if (cached != null) {
      return cached.booleanValue();
    }
    if (cache.size() >= TYPE_CHECK_CACHE_LIMIT) {
      return checker.checkType(className, typeCheckContext);
    }
    // Keep cacheable cold-path misses under one lock so concurrent duplicate names publish
    // exactly one checker decision without allocating per-name futures or holders.
    synchronized (typeCheckCacheLock) {
      cached = cache.get(className);
      if (cached != null) {
        return cached.booleanValue();
      }
      if (cache.size() >= TYPE_CHECK_CACHE_LIMIT) {
        return checker.checkType(className, typeCheckContext);
      }
      boolean allowed;
      try {
        allowed = checker.checkType(className, typeCheckContext);
      } catch (InsecureException e) {
        cache.put(className, false);
        throw e;
      }
      cache.put(className, allowed);
      return allowed;
    }
  }

  private static InsecureException forbiddenClass(String className) {
    return new InsecureException(
        String.format("Class %s is forbidden for Fory JSON serialization.", className));
  }

  private void registerExactCodecs() {
    exactCodecs.put(Object.class, ScalarCodecs.NaturalCodec.INSTANCE);
    exactCodecs.put(void.class, ScalarCodecs.VoidCodec.INSTANCE);
    exactCodecs.put(Void.class, ScalarCodecs.VoidCodec.INSTANCE);
    exactCodecs.put(Number.class, ScalarCodecs.NumberCodec.INSTANCE);
    exactCodecs.put(String.class, ScalarCodecs.StringCodec.INSTANCE);
    exactCodecs.put(CharSequence.class, ScalarCodecs.CharSequenceCodec.INSTANCE);
    exactCodecs.put(boolean.class, ScalarCodecs.BooleanCodec.PRIMITIVE);
    exactCodecs.put(Boolean.class, ScalarCodecs.BooleanCodec.BOXED);
    exactCodecs.put(int.class, ScalarCodecs.IntCodec.PRIMITIVE);
    exactCodecs.put(Integer.class, ScalarCodecs.IntCodec.BOXED);
    exactCodecs.put(long.class, ScalarCodecs.LongCodec.PRIMITIVE);
    exactCodecs.put(Long.class, ScalarCodecs.LongCodec.BOXED);
    exactCodecs.put(short.class, ScalarCodecs.ShortCodec.PRIMITIVE);
    exactCodecs.put(Short.class, ScalarCodecs.ShortCodec.BOXED);
    exactCodecs.put(byte.class, ScalarCodecs.ByteCodec.PRIMITIVE);
    exactCodecs.put(Byte.class, ScalarCodecs.ByteCodec.BOXED);
    exactCodecs.put(char.class, ScalarCodecs.CharCodec.PRIMITIVE);
    exactCodecs.put(Character.class, ScalarCodecs.CharCodec.BOXED);
    exactCodecs.put(float.class, ScalarCodecs.FloatCodec.PRIMITIVE);
    exactCodecs.put(Float.class, ScalarCodecs.FloatCodec.BOXED);
    exactCodecs.put(double.class, ScalarCodecs.DoubleCodec.PRIMITIVE);
    exactCodecs.put(Double.class, ScalarCodecs.DoubleCodec.BOXED);
    exactCodecs.put(BigInteger.class, ScalarCodecs.BigIntegerCodec.INSTANCE);
    exactCodecs.put(BigDecimal.class, ScalarCodecs.BigDecimalCodec.INSTANCE);
    exactCodecs.put(Float16.class, ScalarCodecs.Float16Codec.INSTANCE);
    exactCodecs.put(BFloat16.class, ScalarCodecs.BFloat16Codec.INSTANCE);
    exactCodecs.put(BitSet.class, ScalarCodecs.BitSetCodec.INSTANCE);
    exactCodecs.put(StringBuilder.class, ScalarCodecs.StringBuilderCodec.INSTANCE);
    exactCodecs.put(StringBuffer.class, ScalarCodecs.StringBufferCodec.INSTANCE);
    exactCodecs.put(AtomicBoolean.class, ScalarCodecs.AtomicBooleanCodec.INSTANCE);
    exactCodecs.put(AtomicInteger.class, ScalarCodecs.AtomicIntegerCodec.INSTANCE);
    exactCodecs.put(AtomicIntegerArray.class, ScalarCodecs.AtomicIntegerArrayCodec.INSTANCE);
    exactCodecs.put(AtomicLong.class, ScalarCodecs.AtomicLongCodec.INSTANCE);
    exactCodecs.put(AtomicLongArray.class, ScalarCodecs.AtomicLongArrayCodec.INSTANCE);
    exactCodecs.put(Currency.class, ScalarCodecs.CurrencyCodec.INSTANCE);
    exactCodecs.put(File.class, ScalarCodecs.FileCodec.INSTANCE);
    exactCodecs.put(URI.class, ScalarCodecs.UriCodec.INSTANCE);
    exactCodecs.put(Path.class, ScalarCodecs.PathCodec.INSTANCE);
    exactCodecs.put(Pattern.class, ScalarCodecs.PatternCodec.INSTANCE);
    exactCodecs.put(UUID.class, ScalarCodecs.UuidCodec.INSTANCE);
    exactCodecs.put(Locale.class, ScalarCodecs.LocaleCodec.INSTANCE);
    exactCodecs.put(Charset.class, ScalarCodecs.CharsetCodec.INSTANCE);
    exactCodecs.put(Date.class, ScalarCodecs.DateCodec.INSTANCE);
    SqlJsonCodecs.register(exactCodecs);
    exactCodecs.put(Calendar.class, ScalarCodecs.CalendarCodec.INSTANCE);
    exactCodecs.put(TimeZone.class, ScalarCodecs.TimeZoneCodec.INSTANCE);
    exactCodecs.put(LocalDate.class, ScalarCodecs.LocalDateCodec.INSTANCE);
    exactCodecs.put(LocalTime.class, ScalarCodecs.LocalTimeCodec.INSTANCE);
    exactCodecs.put(LocalDateTime.class, ScalarCodecs.LocalDateTimeCodec.INSTANCE);
    exactCodecs.put(Instant.class, ScalarCodecs.InstantCodec.INSTANCE);
    exactCodecs.put(Duration.class, ScalarCodecs.DurationCodec.INSTANCE);
    exactCodecs.put(ZoneOffset.class, ScalarCodecs.ZoneOffsetCodec.INSTANCE);
    exactCodecs.put(ZoneId.class, ScalarCodecs.ZoneIdCodec.INSTANCE);
    exactCodecs.put(ZonedDateTime.class, ScalarCodecs.ZonedDateTimeCodec.INSTANCE);
    exactCodecs.put(Year.class, ScalarCodecs.YearCodec.INSTANCE);
    exactCodecs.put(YearMonth.class, ScalarCodecs.YearMonthCodec.INSTANCE);
    exactCodecs.put(MonthDay.class, ScalarCodecs.MonthDayCodec.INSTANCE);
    exactCodecs.put(Period.class, ScalarCodecs.PeriodCodec.INSTANCE);
    exactCodecs.put(OffsetTime.class, ScalarCodecs.OffsetTimeCodec.INSTANCE);
    exactCodecs.put(OffsetDateTime.class, ScalarCodecs.OffsetDateTimeCodec.INSTANCE);
    exactCodecs.put(HijrahDate.class, ScalarCodecs.HijrahDateCodec.INSTANCE);
    exactCodecs.put(JapaneseDate.class, ScalarCodecs.JapaneseDateCodec.INSTANCE);
    exactCodecs.put(MinguoDate.class, ScalarCodecs.MinguoDateCodec.INSTANCE);
    exactCodecs.put(ThaiBuddhistDate.class, ScalarCodecs.ThaiBuddhistDateCodec.INSTANCE);
    exactCodecs.put(OptionalInt.class, ScalarCodecs.OptionalIntCodec.INSTANCE);
    exactCodecs.put(OptionalLong.class, ScalarCodecs.OptionalLongCodec.INSTANCE);
    exactCodecs.put(OptionalDouble.class, ScalarCodecs.OptionalDoubleCodec.INSTANCE);
    exactCodecs.put(ByteBuffer.class, ScalarCodecs.ByteBufferCodec.INSTANCE);
    GuavaCodecs.registerExactCodecs(exactCodecs);
  }
}

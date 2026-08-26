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
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
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
import java.util.HashMap;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.apache.fory.annotation.Internal;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.GeneratedClassNames;
import org.apache.fory.exception.InsecureException;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.JsonCodecFactory;
import org.apache.fory.json.JsonConfig;
import org.apache.fory.json.JsonTypeCheckContext;
import org.apache.fory.json.JsonTypeChecker;
import org.apache.fory.json.PropertyNamingStrategy;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.apache.fory.json.annotation.JsonSubTypes.Inclusion;
import org.apache.fory.json.annotation.JsonType;
import org.apache.fory.json.codec.ArrayCodec;
import org.apache.fory.json.codec.ClosedSubtypeCodec;
import org.apache.fory.json.codec.CodecUtils;
import org.apache.fory.json.codec.CollectionCodec;
import org.apache.fory.json.codec.GeneratedJsonCodec;
import org.apache.fory.json.codec.GeneratedJsonSubtypeTable;
import org.apache.fory.json.codec.GuavaCodecs;
import org.apache.fory.json.codec.JsonSubTypesInfo;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.codec.MapCodec;
import org.apache.fory.json.codec.MapKeyCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.ScalarCodecs;
import org.apache.fory.json.codec.SqlJsonCodecs;
import org.apache.fory.json.codegen.GeneratedCodecKey;
import org.apache.fory.json.codegen.JsonCodegen;
import org.apache.fory.json.codegen.JsonJITContext;
import org.apache.fory.json.meta.JsonAnySetterAccessor;
import org.apache.fory.json.meta.JsonFieldAccessor;
import org.apache.fory.json.meta.JsonFieldKind;
import org.apache.fory.json.resolver.CodecRegistry.FactoryBinding;
import org.apache.fory.json.resolver.JsonGeneratedClassRegistry.CompanionKey;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.DisallowedList;
import org.apache.fory.type.BFloat16;
import org.apache.fory.type.Float16;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.type.Types;
import org.apache.fory.util.record.RecordUtils;

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
 * state. Common short field names admitted by reader-local caches are published here for
 * best-effort String reference reuse across readers. Source-generated model companions and
 * JIT-generated class futures are shared here; concrete JIT codec instances, ordinary type
 * bindings, graph construction, JIT locks, and publication remain resolver-local. A fresh {@link
 * JsonJITContext} is therefore created for every pooled JSON state.
 */
public final class JsonSharedRegistry {
  private static final int TYPE_CHECK_CACHE_LIMIT = 8192;
  private static final Comparator<DeclarationCandidate> DECLARATION_ORDER =
      new Comparator<DeclarationCandidate>() {
        @Override
        public int compare(DeclarationCandidate left, DeclarationCandidate right) {
          int result = left.declarationType.getName().compareTo(right.declarationType.getName());
          if (result != 0) {
            return result;
          }
          return left.codecClass.getName().compareTo(right.codecClass.getName());
        }
      };

  private final CodecRegistry customCodecs;
  private final JsonCodecFactory[] codecFactories;
  private final String[] codecFactoryIdentities;
  private final IdentityHashMap<Class<?>, ExactFactoryBinding> runtimeFactories;
  private final IdentityHashMap<Class<?>, JsonValueCodec<?>> exactCodecs;
  private final JsonTypeChecker typeChecker;
  private final JsonTypeCheckContext typeCheckContext;
  private final ConcurrentHashMap<String, Boolean> typeCheckCache;
  private final Object typeCheckCacheLock;
  private final JsonCodegen codegen;
  private final boolean nativeCodegenEnabled;
  private final boolean hostedCodegen;
  private final boolean asyncCompilationEnabled;
  private final ExecutorService compilationService;
  private final boolean propertyDiscoveryEnabled;
  private final PropertyNamingStrategy propertyNamingStrategy;
  private final boolean writeNullFields;
  private final ClassLoader classLoader;
  private final JsonMixinAnnotations mixinAnnotations;
  private final IdentityHashMap<Class<?>, JsonSubTypesInfo> subTypesCache;
  private final IdentityHashMap<Class<?>, JsonCodecDeclaration> codecDeclarations;
  private final Set<Class<?>> typesWithoutCodecDeclaration;
  private final IdentityHashMap<Class<?>, JsonValueDeclaration> valueDeclarations;
  private final Set<Class<?>> typesWithoutValueDeclaration;
  private final ConcurrentHashMap<Class<? extends JsonValueCodec<?>>, JsonValueCodec<?>>
      annotationCodecs;
  private final ConcurrentHashMap<Class<? extends MapKeyCodec>, MapKeyCodec> mapKeyCodecs;
  private final ConcurrentHashMap<Class<?>, GeneratedJsonCodec<?>> generatedCodecs;
  private final Set<Class<?>> typesWithoutGeneratedCodec;
  private final ConcurrentHashMap<CompanionKey, GeneratedJsonCodec<?>> generatedCodecCapabilities;
  private final ConcurrentHashMap<GeneratedCodecKey, CompletableFuture<Class<?>>>
      generatedClassFutures;
  // Only ForyJson's fixed-pool reader-local caches publish production entries here, and each reader
  // owns its configured entry limit. This reference-reuse table does not own a second capacity
  // policy.
  private final ConcurrentHashMap<Long, CachedFieldName> cachedFieldNames;

  public JsonSharedRegistry(JsonConfig config) {
    this(config, null, false);
  }

  JsonSharedRegistry(JsonConfig config, ExecutorService compilationService) {
    this(config, compilationService, false);
  }

  private JsonSharedRegistry(
      JsonConfig config, ExecutorService compilationService, boolean hostedCodegen) {
    this.customCodecs = config.codecRegistry();
    codecFactories = config.codecFactories();
    codecFactoryIdentities = config.codecFactoryIdentities();
    runtimeFactories = new IdentityHashMap<>();
    for (Map.Entry<Class<?>, FactoryBinding> entry : customCodecs.factoryBindings().entrySet()) {
      Class<?> target = entry.getKey();
      FactoryBinding binding = entry.getValue();
      for (Class<?> runtimeType : binding.handledRuntimeClasses()) {
        ExactFactoryBinding previous =
            runtimeFactories.put(runtimeType, new ExactFactoryBinding(target, binding));
        if (previous != null && previous.binding != binding) {
          throw new IllegalArgumentException(
              "Conflicting JSON runtime codec factories for " + runtimeType.getName());
        }
      }
    }
    // Hosted compilation shares classes only for equal generated-class keys. Runtime type policy
    // is intentionally not part of that key and remains enforced by each
    // runtime resolver before it installs a generated capability.
    typeChecker = hostedCodegen ? null : config.typeChecker();
    typeCheckContext = hostedCodegen ? null : config.typeCheckContext();
    typeCheckCache = typeChecker == null ? null : new ConcurrentHashMap<>();
    typeCheckCacheLock = typeChecker == null ? null : new Object();
    this.propertyDiscoveryEnabled = config.propertyDiscoveryEnabled();
    propertyNamingStrategy = config.propertyNamingStrategy();
    writeNullFields = config.writeNullFields();
    classLoader = config.classLoader();
    mixinAnnotations = new JsonMixinAnnotations(config);
    exactCodecs = new IdentityHashMap<>();
    subTypesCache = new IdentityHashMap<>();
    codecDeclarations = new IdentityHashMap<>();
    typesWithoutCodecDeclaration =
        Collections.newSetFromMap(new IdentityHashMap<Class<?>, Boolean>());
    valueDeclarations = new IdentityHashMap<>();
    typesWithoutValueDeclaration =
        Collections.newSetFromMap(new IdentityHashMap<Class<?>, Boolean>());
    annotationCodecs = new ConcurrentHashMap<>();
    mapKeyCodecs = new ConcurrentHashMap<>();
    generatedCodecs = new ConcurrentHashMap<>();
    typesWithoutGeneratedCodec = ConcurrentHashMap.newKeySet();
    generatedCodecCapabilities = new ConcurrentHashMap<>();
    generatedClassFutures = new ConcurrentHashMap<>();
    cachedFieldNames = new ConcurrentHashMap<>();
    boolean codegenEnabled = config.codegenEnabled();
    this.hostedCodegen = hostedCodegen;
    boolean createCompiler =
        codegenEnabled && (hostedCodegen || !GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE);
    codegen = createCompiler ? new JsonCodegen(hostedCodegen) : null;
    nativeCodegenEnabled =
        codegenEnabled && GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE && !hostedCodegen;
    asyncCompilationEnabled = createCompiler && !hostedCodegen && config.asyncCompilationEnabled();
    this.compilationService = compilationService;
    registerExactCodecs();
  }

  /** Creates the transient synchronous compiler registry owned by Native Image hosted analysis. */
  @Internal
  public static JsonSharedRegistry forHostedCodegen(JsonConfig config) {
    if (!GraalvmSupport.isGraalBuildTime()) {
      throw new IllegalStateException("Hosted Fory JSON code generation requires image build time");
    }
    if (!config.codegenEnabled()) {
      throw new IllegalArgumentException("Hosted Fory JSON configuration has codegen disabled");
    }
    return new JsonSharedRegistry(config, null, true);
  }

  /** Returns a complete immutable snapshot of every synchronously generated class. */
  GeneratedClasses generatedClasses() {
    if (codegen == null || asyncCompilationEnabled) {
      throw new IllegalStateException("Generated class snapshots require synchronous codegen");
    }
    Map<GeneratedCodecKey, Class<?>> classes = completedClasses(generatedClassFutures);
    Map<CompanionKey, GeneratedJsonCodec<?>> sourceCodecs =
        generatedCodecCapabilities.isEmpty()
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(generatedCodecCapabilities));
    return new GeneratedClasses(classes, sourceCodecs);
  }

  private static Map<GeneratedCodecKey, Class<?>> completedClasses(
      Map<GeneratedCodecKey, CompletableFuture<Class<?>>> futures) {
    Map<GeneratedCodecKey, Class<?>> classes = new HashMap<>(futures.size());
    for (Map.Entry<GeneratedCodecKey, CompletableFuture<Class<?>>> entry : futures.entrySet()) {
      CompletableFuture<Class<?>> future = entry.getValue();
      if (!future.isDone() || future.isCompletedExceptionally()) {
        throw new IllegalStateException(
            "Fory JSON generated class is incomplete: " + entry.getKey());
      }
      Class<?> generatedClass = future.getNow(null);
      if (generatedClass == null) {
        throw new IllegalStateException("Fory JSON generated class is null: " + entry.getKey());
      }
      classes.put(entry.getKey(), generatedClass);
    }
    return Collections.unmodifiableMap(classes);
  }

  static final class GeneratedClasses {
    private final Map<GeneratedCodecKey, Class<?>> classes;
    private final Map<CompanionKey, GeneratedJsonCodec<?>> sourceCodecs;

    private GeneratedClasses(
        Map<GeneratedCodecKey, Class<?>> classes,
        Map<CompanionKey, GeneratedJsonCodec<?>> sourceCodecs) {
      this.classes = classes;
      this.sourceCodecs = sourceCodecs;
    }

    Map<GeneratedCodecKey, Class<?>> classes() {
      return classes;
    }

    Map<CompanionKey, GeneratedJsonCodec<?>> sourceCodecs() {
      return sourceCodecs;
    }
  }

  CompletableFuture<Class<?>> stringWriterClass(
      GeneratedCodecKey key, ObjectCodec<?> owner, JsonTypeResolver resolver) {
    return generatedClassFuture(key, () -> codegen.compileStringWriter(key, owner, resolver));
  }

  CompletableFuture<Class<?>> utf8WriterClass(
      GeneratedCodecKey key, ObjectCodec<?> owner, JsonTypeResolver resolver) {
    return generatedClassFuture(key, () -> codegen.compileUtf8Writer(key, owner, resolver));
  }

  CompletableFuture<Class<?>> latin1ReaderClass(
      GeneratedCodecKey key, ObjectCodec<?> owner, JsonTypeResolver resolver) {
    return generatedClassFuture(key, () -> codegen.compileLatin1Reader(key, owner, resolver));
  }

  CompletableFuture<Class<?>> utf16ReaderClass(
      GeneratedCodecKey key, ObjectCodec<?> owner, JsonTypeResolver resolver) {
    return generatedClassFuture(key, () -> codegen.compileUtf16Reader(key, owner, resolver));
  }

  CompletableFuture<Class<?>> utf8ReaderClass(
      GeneratedCodecKey key, ObjectCodec<?> owner, JsonTypeResolver resolver) {
    return generatedClassFuture(key, () -> codegen.compileUtf8Reader(key, owner, resolver));
  }

  CompletableFuture<Class<?>> utf8CollectionWriterClass(GeneratedCodecKey key) {
    return generatedClassFuture(key, () -> codegen.compileUtf8CollectionWriter(key));
  }

  CompletableFuture<Class<?>> utf8CollectionReaderClass(GeneratedCodecKey key) {
    return generatedClassFuture(key, () -> codegen.compileUtf8CollectionReader(key));
  }

  boolean generatedCapabilitiesEnabled() {
    return codegen != null || nativeCodegenEnabled;
  }

  boolean hostedCodegen() {
    return hostedCodegen;
  }

  boolean nativeGeneratedClasses() {
    return nativeCodegenEnabled;
  }

  Class<?> nativeGeneratedClass(GeneratedCodecKey key) {
    return nativeCodegenEnabled ? JsonGeneratedClassRegistry.generatedClass(key) : null;
  }

  private CompletableFuture<Class<?>> generatedClassFuture(
      GeneratedCodecKey key, Supplier<Class<?>> compiler) {
    CompletableFuture<Class<?>> existing = generatedClassFutures.get(key);
    if (existing != null) {
      return existing;
    }
    CompletableFuture<Class<?>> candidate = new CompletableFuture<>();
    existing = generatedClassFutures.putIfAbsent(key, candidate);
    if (existing != null) {
      return existing;
    }
    Runnable task =
        () -> {
          try {
            candidate.complete(compiler.get());
          } catch (Throwable failure) {
            generatedClassFutures.remove(key, candidate);
            candidate.completeExceptionally(failure);
          }
        };
    if (!asyncCompilationEnabled) {
      task.run();
      return candidate;
    }
    ExecutorService service = compilationService;
    if (service == null) {
      service = CodeGenerator.getCompilationService();
    }
    try {
      service.execute(task);
    } catch (RuntimeException | Error failure) {
      generatedClassFutures.remove(key, candidate);
      candidate.completeExceptionally(failure);
      throw failure;
    }
    return candidate;
  }

  /** Returns the immutable cached entry for {@code hash}, or null when none was published. */
  @Internal
  public CachedFieldName cachedFieldName(long hash) {
    return cachedFieldNames.get(hash);
  }

  /** Publishes one already validated short ASCII field name, or returns the existing hash owner. */
  @Internal
  public CachedFieldName cacheFieldName(long hash, String name, long word0, long word1) {
    CachedFieldName candidate = new CachedFieldName(name, word0, word1);
    CachedFieldName existing = cachedFieldNames.putIfAbsent(hash, candidate);
    return existing == null ? candidate : existing;
  }

  /** Immutable field-name hash owner retained for best-effort cross-reader reference reuse. */
  @Internal
  public static final class CachedFieldName {
    private final String name;
    private final long word0;
    private final long word1;

    private CachedFieldName(String name, long word0, long word1) {
      this.name = name;
      this.word0 = word0;
      this.word1 = word1;
    }

    public String name() {
      return name;
    }

    public boolean matches(int length, long candidateWord0, long candidateWord1) {
      return name.length() == length && word0 == candidateWord0 && word1 == candidateWord1;
    }
  }

  GeneratedJsonCodec<?> generatedCodec(TypeRef<?> type) {
    return generatedCodec(type, true);
  }

  private GeneratedJsonCodec<?> generatedCodec(TypeRef<?> type, boolean requireCompanion) {
    if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE && !hostedCodegen) {
      // Native hosted analysis owns reflection reachability and generated capabilities. A Java
      // annotation-processor companion is an optional faster operation source, not a prerequisite.
      return JsonGeneratedClassRegistry.sourceCodec(
          new CompanionKey(type, mixinType(type.getRawType())));
    }
    GeneratedJsonCodec<?> codec =
        generatedCodec(type.getRawType(), requireCompanion && !hostedCodegen);
    if (codec != null && hostedCodegen) {
      CompanionKey key = new CompanionKey(type, mixinType(type.getRawType()));
      GeneratedJsonCodec<?> previous = generatedCodecCapabilities.putIfAbsent(key, codec);
      if (previous != null && previous != codec) {
        throw new IllegalStateException("Conflicting generated JSON companions for " + type);
      }
    }
    return codec;
  }

  private GeneratedJsonCodec<?> generatedCodec(Class<?> type, boolean requireCompanion) {
    Class<?> mixinType = mixinType(type);
    boolean directType = type.getDeclaredAnnotation(JsonType.class) != null;
    if (!directType && mixinType == null) {
      return null;
    }
    try {
      GeneratedJsonCodec<?> codec = generatedCodecIfPresent(type, mixinType);
      if (codec == null && requireCompanion && directType) {
        throw missingGeneratedCodec(type, mixinType, "JSON object model");
      }
      return codec;
    } catch (ForyJsonException e) {
      throw mixinSchemaFailure(type, e);
    }
  }

  GeneratedJsonCodec<?> generatedCodecIfPresent(TypeRef<?> type) {
    return generatedCodec(type, false);
  }

  private GeneratedJsonCodec<?> generatedCodecIfPresent(Class<?> type, Class<?> mixinType) {
    GeneratedJsonCodec<?> codec = generatedCodecs.get(type);
    if (codec != null) {
      return codec;
    }
    if (!typesWithoutGeneratedCodec.contains(type)) {
      synchronized (generatedCodecs) {
        codec = generatedCodecs.get(type);
        if (codec == null && !typesWithoutGeneratedCodec.contains(type)) {
          codec = loadGeneratedCodec(type, mixinType);
          if (codec == null) {
            typesWithoutGeneratedCodec.add(type);
          } else {
            generatedCodecs.put(type, codec);
          }
        }
      }
    }
    return codec;
  }

  private static ForyJsonException missingGeneratedCodec(
      Class<?> type, Class<?> mixinType, String representation) {
    String name =
        mixinType == null
            ? generatedCodecBinaryName(type)
            : generatedMixinCodecBinaryName(mixinType, type);
    return new ForyJsonException(
        "Missing generated JSON codec "
            + name
            + " for "
            + representation
            + " "
            + type.getName()
            + "; enable the Fory annotation processor and preserve its generated R8 rules");
  }

  private static ForyJsonException missingGeneratedSubtypeTable(Class<?> type, Class<?> mixinType) {
    String name =
        mixinType == null
            ? generatedSubtypeTableBinaryName(type)
            : generatedMixinSubtypeTableBinaryName(mixinType, type);
    return new ForyJsonException(
        "Missing generated JSON subtype table "
            + name
            + " for "
            + type.getName()
            + "; enable the Fory annotation processor and preserve its generated R8 rules");
  }

  private GeneratedJsonCodec<?> loadGeneratedCodec(Class<?> type, Class<?> mixinType) {
    String generatedName =
        mixinType == null
            ? generatedCodecBinaryName(type)
            : generatedMixinCodecBinaryName(mixinType, type);
    Class<?> generatedClass;
    try {
      generatedClass =
          Class.forName(
              generatedName,
              false,
              mixinType == null ? type.getClassLoader() : mixinType.getClassLoader());
    } catch (ClassNotFoundException e) {
      return null;
    } catch (LinkageError e) {
      throw new ForyJsonException("Cannot load generated JSON codec " + generatedName, e);
    }
    if (!GeneratedJsonCodec.class.isAssignableFrom(generatedClass)) {
      throw new ForyJsonException(
          "Generated JSON codec "
              + generatedName
              + " does not extend "
              + GeneratedJsonCodec.class.getName());
    }
    @SuppressWarnings("unchecked")
    Class<? extends GeneratedJsonCodec<?>> codecClass =
        (Class<? extends GeneratedJsonCodec<?>>)
            generatedClass.asSubclass(GeneratedJsonCodec.class);
    return validateGeneratedCodec(type, newGeneratedCodec(codecClass));
  }

  private static GeneratedJsonCodec<?> newGeneratedCodec(
      Class<? extends GeneratedJsonCodec<?>> codecClass) {
    Constructor<? extends GeneratedJsonCodec<?>> publicConstructor;
    try {
      publicConstructor = codecClass.getConstructor();
    } catch (NoSuchMethodException e) {
      throw new ForyJsonException(
          "Generated JSON codec must declare a public no-argument constructor: "
              + codecClass.getName(),
          e);
    }
    if (!Modifier.isPublic(codecClass.getModifiers())) {
      throw new ForyJsonException("Generated JSON codec must be public: " + codecClass.getName());
    }
    if (AndroidSupport.IS_ANDROID) {
      try {
        return publicConstructor.newInstance();
      } catch (ReflectiveOperationException e) {
        throw new ForyJsonException(
            "Cannot construct generated JSON codec " + codecClass.getName(), unwrap(e));
      }
    }
    try {
      // Generated companions may live in named application modules that are not opened to Fory.
      // The trusted handle is used only once while the shared registry publishes the companion.
      MethodHandle constructor = ReflectionUtils.getCtrHandle(codecClass);
      return (GeneratedJsonCodec<?>) constructor.invoke();
    } catch (Throwable e) {
      throw new ForyJsonException(
          "Cannot construct generated JSON codec " + codecClass.getName(), unwrap(e));
    }
  }

  /** Validates and initializes a source-generated codec before shared publication. */
  @Internal
  public static GeneratedJsonCodec<?> validateGeneratedCodec(
      Class<?> type, GeneratedJsonCodec<?> codec) {
    if (codec == null) {
      throw new ForyJsonException("Generated JSON codec is null for " + type.getName());
    }
    Class<?> declaredType;
    JsonFieldAccessor[] accessors;
    JsonAnySetterAccessor anySetter;
    String[] creatorNames;
    Class<?>[] creatorTypes;
    String creatorFactory;
    Method[] validatorMethods;
    boolean record;
    try {
      declaredType = codec.type();
      accessors = codec.fieldAccessors();
      anySetter = codec.anySetterAccessor();
      creatorNames = codec.creatorParameterNames();
      creatorTypes = codec.creatorParameterTypes();
      creatorFactory = codec.creatorFactoryName();
      validatorMethods = codec.validatorMethods();
      record = codec.isRecord();
    } catch (RuntimeException | LinkageError e) {
      throw new ForyJsonException(
          "Cannot read generated JSON codec metadata for " + type.getName(), e);
    }
    if (declaredType != type) {
      throw invalidGeneratedCodec(type, "type() must return the exact model class");
    }
    if (accessors == null) {
      throw invalidGeneratedCodec(type, "fieldAccessors() returned null");
    }
    Map<Member, JsonFieldAccessor> memberAccessors = new HashMap<>();
    for (JsonFieldAccessor accessor : accessors) {
      validateGeneratedAccessor(type, accessor, memberAccessors);
    }
    Method anySetterMethod = anySetter == null ? null : validateGeneratedAnySetter(type, anySetter);
    Executable creator =
        validateGeneratedCreator(
            type, memberAccessors, creatorNames, creatorTypes, creatorFactory, record);
    validateGeneratedValidators(type, validatorMethods);
    if (!AndroidSupport.IS_ANDROID && RecordUtils.isRecord(type) != record) {
      throw invalidGeneratedCodec(type, "isRecord() does not match the runtime model class");
    }
    codec.initializeValidated(
        memberAccessors,
        anySetter,
        anySetterMethod,
        creatorNames,
        creatorTypes,
        creatorFactory,
        creator,
        record,
        validatorMethods);
    return codec;
  }

  private static void validateGeneratedValidators(Class<?> type, Method[] methods) {
    if (methods == null) {
      return;
    }
    Set<Method> unique = new HashSet<>();
    for (Method method : methods) {
      if (method == null) {
        throw invalidGeneratedCodec(type, "validatorMethods() contains null");
      }
      int modifiers = method.getModifiers();
      if (!method.getDeclaringClass().isAssignableFrom(type)
          || !Modifier.isPublic(modifiers)
          || Modifier.isStatic(modifiers)
          || Modifier.isAbstract(modifiers)
          || method.isSynthetic()
          || method.isBridge()
          || method.getParameterCount() != 0
          || method.getReturnType() != void.class) {
        throw invalidGeneratedCodec(type, "generated validator has an invalid shape");
      }
      if (!unique.add(method)) {
        throw invalidGeneratedCodec(type, "duplicate generated validator " + method);
      }
    }
  }

  private static void validateGeneratedAccessor(
      Class<?> type, JsonFieldAccessor accessor, Map<Member, JsonFieldAccessor> memberAccessors) {
    if (accessor == null) {
      throw invalidGeneratedCodec(type, "fieldAccessors() contains null");
    }
    Field field = accessor.field();
    Method getter = accessor.getter();
    Method setter = accessor.setter();
    int memberCount = (field == null ? 0 : 1) + (getter == null ? 0 : 1) + (setter == null ? 0 : 1);
    if (memberCount != 1) {
      throw invalidGeneratedCodec(type, "each field accessor must identify exactly one member");
    }
    Member member = field != null ? field : getter != null ? getter : setter;
    if (!member.getDeclaringClass().isAssignableFrom(type)
        || Modifier.isStatic(member.getModifiers())) {
      throw invalidGeneratedCodec(type, "field accessor does not belong to the model hierarchy");
    }
    if (field != null
        && (field.isSynthetic()
            || Modifier.isTransient(field.getModifiers())
            || field.getType() == Class.class)) {
      throw invalidGeneratedCodec(type, "generated field is not an eligible JSON member");
    }
    if (member instanceof Method
        && (((Method) member).isSynthetic() || ((Method) member).isBridge())) {
      throw invalidGeneratedCodec(type, "generated method is not an eligible JSON member");
    }
    if (getter != null
        && (getter.getParameterCount() != 0 || getter.getReturnType() == void.class)) {
      throw invalidGeneratedCodec(type, "generated getter has an invalid signature");
    }
    if (setter != null
        && (setter.getParameterCount() != 1 || setter.getReturnType() != void.class)) {
      throw invalidGeneratedCodec(type, "generated setter has an invalid signature");
    }
    if (memberAccessors.put(member, accessor) != null) {
      throw invalidGeneratedCodec(type, "duplicate generated accessor for " + member);
    }
  }

  private static Method validateGeneratedAnySetter(Class<?> type, JsonAnySetterAccessor accessor) {
    Method method = accessor.setter();
    if (method == null
        || !method.getDeclaringClass().isAssignableFrom(type)
        || !Modifier.isPublic(method.getModifiers())
        || Modifier.isStatic(method.getModifiers())
        || method.isSynthetic()
        || method.isBridge()
        || method.isVarArgs()
        || method.getTypeParameters().length != 0
        || method.getReturnType() != void.class
        || method.getParameterCount() != 2
        || method.getParameterTypes()[0] != String.class) {
      throw invalidGeneratedCodec(type, "generated any setter has an invalid signature");
    }
    return method;
  }

  private static Executable validateGeneratedCreator(
      Class<?> type,
      Map<Member, JsonFieldAccessor> memberAccessors,
      String[] creatorNames,
      Class<?>[] creatorTypes,
      String creatorFactory,
      boolean record) {
    if ((creatorNames == null) != (creatorTypes == null)) {
      throw invalidGeneratedCodec(
          type, "creator names and parameter types must both be null or non-null");
    }
    if (creatorNames == null) {
      if (creatorFactory != null || record) {
        throw invalidGeneratedCodec(type, "creator metadata is incomplete");
      }
      return null;
    }
    if (record && creatorFactory != null) {
      throw invalidGeneratedCodec(type, "a Record creator must be a constructor");
    }
    if (creatorNames.length != creatorTypes.length) {
      throw invalidGeneratedCodec(type, "creator name and parameter type counts differ");
    }
    Set<String> names = new HashSet<>();
    for (int i = 0; i < creatorNames.length; i++) {
      if (creatorNames[i] == null
          || creatorNames[i].isEmpty()
          || !names.add(creatorNames[i])
          || creatorTypes[i] == null
          || creatorTypes[i] == void.class) {
        throw invalidGeneratedCodec(type, "invalid generated creator parameter at index " + i);
      }
    }
    if (creatorFactory != null && creatorFactory.isEmpty()) {
      throw invalidGeneratedCodec(type, "creator factory name must not be empty");
    }
    Executable creator;
    if (creatorFactory == null) {
      Constructor<?> constructor;
      try {
        constructor = type.getDeclaredConstructor(creatorTypes);
      } catch (NoSuchMethodException e) {
        if (!record) {
          // A language object model may lower its logical creator to a different accessibility or
          // default bridge. ObjectCodecBuilder validates that exact model once it is available.
          return null;
        }
        throw invalidGeneratedCodec(type, "creator constructor signature does not exist");
      }
      if (!record && !validGeneratedExecutable(constructor)) {
        return null;
      }
      creator = constructor;
    } else {
      Method factory;
      try {
        factory = type.getDeclaredMethod(creatorFactory, creatorTypes);
      } catch (NoSuchMethodException e) {
        if (!record) {
          return null;
        }
        throw invalidGeneratedCodec(type, "creator factory signature does not exist");
      }
      if (!record && !validGeneratedExecutable(factory)) {
        return null;
      }
      validateGeneratedExecutable(type, factory);
      if (!Modifier.isStatic(factory.getModifiers()) || factory.getReturnType() != type) {
        throw invalidGeneratedCodec(
            type, "creator factory must be static and return the model type");
      }
      creator = factory;
    }
    if (record) {
      validateGeneratedRecordAccessors(type, memberAccessors, creatorNames, creatorTypes);
    }
    return creator;
  }

  private static void validateGeneratedExecutable(Class<?> type, Executable creator) {
    if (!validGeneratedExecutable(creator)) {
      throw invalidGeneratedCodec(type, "creator executable has an invalid shape");
    }
  }

  private static boolean validGeneratedExecutable(Executable creator) {
    return Modifier.isPublic(creator.getModifiers())
        && !creator.isSynthetic()
        && !creator.isVarArgs()
        && creator.getParameterCount() != 0
        && creator.getTypeParameters().length == 0
        && (!(creator instanceof Method) || !((Method) creator).isBridge());
  }

  private static void validateGeneratedRecordAccessors(
      Class<?> type,
      Map<Member, JsonFieldAccessor> memberAccessors,
      String[] componentNames,
      Class<?>[] componentTypes) {
    for (int i = 0; i < componentNames.length; i++) {
      Method getter;
      try {
        getter = type.getDeclaredMethod(componentNames[i]);
      } catch (NoSuchMethodException e) {
        throw invalidGeneratedCodec(type, "Record component getter does not exist at index " + i);
      }
      JsonFieldAccessor accessor = memberAccessors.get(getter);
      if (getter.getReturnType() != componentTypes[i]
          || getter.getParameterCount() != 0
          || accessor == null
          || !getter.equals(accessor.getter())) {
        throw invalidGeneratedCodec(type, "invalid generated Record accessor at index " + i);
      }
    }
  }

  private static ForyJsonException invalidGeneratedCodec(Class<?> type, String reason) {
    return new ForyJsonException(
        "Invalid generated JSON codec for " + type.getName() + ": " + reason);
  }

  private static Throwable unwrap(Throwable throwable) {
    return throwable instanceof InvocationTargetException
        ? ((InvocationTargetException) throwable).getCause()
        : throwable;
  }

  private static String generatedCodecBinaryName(Class<?> type) {
    return GeneratedClassNames.withSuffix(type.getName(), "_ForyJsonCodec");
  }

  private static String generatedSubtypeTableBinaryName(Class<?> type) {
    return GeneratedClassNames.withSuffix(type.getName(), "_ForyJsonSubTypes");
  }

  private static String generatedMixinCodecBinaryName(Class<?> mixinType, Class<?> targetType) {
    String sourceName = mixinType.getName();
    int packageEnd = sourceName.lastIndexOf('.');
    String sourcePackage = packageEnd < 0 ? "" : sourceName.substring(0, packageEnd + 1);
    String sourceSimple = packageEnd < 0 ? sourceName : sourceName.substring(packageEnd + 1);
    return sourcePackage
        + GeneratedClassNames.escapeBinarySimpleName(sourceSimple)
        + "_ForyJsonMixin_"
        + GeneratedClassNames.escapeBinarySimpleName(targetType.getName())
        + "_ForyJsonCodec";
  }

  private static String generatedMixinSubtypeTableBinaryName(
      Class<?> mixinType, Class<?> targetType) {
    String codecName = generatedMixinCodecBinaryName(mixinType, targetType);
    return codecName.substring(0, codecName.length() - "_ForyJsonCodec".length())
        + "_ForyJsonSubTypes";
  }

  public JsonValueCodec<?> createCodec(
      Class<?> rawType, TypeRef<?> typeRef, JsonTypeResolver localResolver) {
    ResolvedCodec resolved = resolveCodec(rawType, typeRef, localResolver, null, false);
    return resolved == null ? null : resolved.codec;
  }

  ResolvedCodec resolveCodec(
      Class<?> rawType,
      TypeRef<?> typeRef,
      JsonTypeResolver localResolver,
      JsonCodecFactory childFactory,
      boolean runtimeType) {
    JsonValueCodec<?> customCodec = customCodec(rawType);
    if (customCodec != null) {
      return new ResolvedCodec(customCodec, null);
    }
    FactoryBinding exactFactory = customCodecs.getFactory(rawType);
    if (exactFactory != null) {
      return new ResolvedCodec(
          createExactCodec(rawType, typeRef, exactFactory, localResolver, runtimeType),
          exactFactory.key());
    }
    if (childFactory != null) {
      // A parent-derived subtype is only the default model. Exact application registration above
      // must remain authoritative, including when the closed parent selected this child first.
      JsonValueCodec<?> childCodec = childFactory.create(typeRef, localResolver, false);
      if (!(childCodec instanceof ObjectCodec) || ((ObjectCodec<?>) childCodec).type() != rawType) {
        throw new ForyJsonException(
            "Closed JSON subtype factory did not create the exact ObjectCodec for "
                + rawType.getName());
      }
      return new ResolvedCodec(childCodec, childFactory.factoryKey());
    }
    if (typeRef.getTypeExtMeta() != null
        && (rawType == OptionalInt.class
            || rawType == OptionalLong.class
            || rawType == OptionalDouble.class)) {
      if (typeRef.getTypeExtMeta().nullable() || typeRef.getTypeExtMeta().nullableWrapper()) {
        throw new ForyJsonException("Nullable Optional has ambiguous JSON null: " + typeRef);
      }
      if (rawType == OptionalInt.class) {
        return new ResolvedCodec(ScalarCodecs.OptionalIntCodec.NON_NULL, null);
      }
      if (rawType == OptionalLong.class) {
        return new ResolvedCodec(ScalarCodecs.OptionalLongCodec.NON_NULL, null);
      }
      return new ResolvedCodec(ScalarCodecs.OptionalDoubleCodec.NON_NULL, null);
    }
    boolean semanticToken =
        typeRef.getTypeExtMeta() != null && typeRef.getTypeExtMeta().typeId() != Types.UNKNOWN;
    if (semanticToken) {
      // The exact JVM carrier codec cannot erase an explicit semantic type. The installed module
      // owns that representation even when the semantic value uses a primitive carrier.
      ResolvedCodec resolved = createModuleCodec(typeRef, localResolver, runtimeType);
      if (resolved == null) {
        throw new ForyJsonException("No installed JSON module owns semantic type " + typeRef);
      }
      return resolved;
    }
    JsonValueCodec<?> codec = exactCodecs.get(rawType);
    if (codec != null) {
      return new ResolvedCodec(codec, null);
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
    if (rawType.isEnum()) {
      return new ResolvedCodec(new ScalarCodecs.EnumCodec(rawType), null);
    }
    if (rawType.isArray()) {
      return new ResolvedCodec(ArrayCodec.create(rawType, typeRef, localResolver), null);
    }
    if (rawType == Optional.class) {
      return new ResolvedCodec(new ScalarCodecs.OptionalCodec(typeRef, localResolver), null);
    }
    if (rawType == AtomicReference.class) {
      JsonTypeInfo contentInfo = localResolver.getTypeInfo(CodecUtils.elementTypeRef(typeRef));
      return new ResolvedCodec(
          ScalarCodecs.AtomicReferenceCodec.create(typeRef, contentInfo), null);
    }
    if (rawType == AtomicReferenceArray.class) {
      JsonTypeInfo elementInfo = localResolver.getTypeInfo(CodecUtils.elementTypeRef(typeRef));
      return new ResolvedCodec(ScalarCodecs.AtomicReferenceArrayCodec.create(elementInfo), null);
    }
    if (Calendar.class.isAssignableFrom(rawType)) {
      return new ResolvedCodec(ScalarCodecs.CalendarCodec.INSTANCE, null);
    }
    if (Date.class.isAssignableFrom(rawType)) {
      return new ResolvedCodec(ScalarCodecs.DateCodec.INSTANCE, null);
    }
    if (ZoneId.class.isAssignableFrom(rawType)) {
      return new ResolvedCodec(ScalarCodecs.ZoneIdCodec.INSTANCE, null);
    }
    if (ByteBuffer.class.isAssignableFrom(rawType)) {
      return new ResolvedCodec(ScalarCodecs.ByteBufferCodec.INSTANCE, null);
    }
    if (File.class.isAssignableFrom(rawType)) {
      return new ResolvedCodec(ScalarCodecs.FileCodec.INSTANCE, null);
    }
    if (Path.class.isAssignableFrom(rawType)) {
      return new ResolvedCodec(ScalarCodecs.PathCodec.INSTANCE, null);
    }
    ResolvedCodec resolved = createModuleCodec(typeRef, localResolver, runtimeType);
    if (resolved != null) {
      return resolved;
    }
    if (inferredSubTypes(rawType)) {
      return new ResolvedCodec(
          new ClosedSubtypeCodec(rawType, subTypesInfo(rawType), typeRef), null);
    }
    if (Number.class.isAssignableFrom(rawType) || CharSequence.class.isAssignableFrom(rawType)) {
      throw new ForyJsonException("Unsupported JSON type " + rawType);
    }
    if (Collection.class.isAssignableFrom(rawType)) {
      return new ResolvedCodec(CollectionCodec.create(rawType, typeRef, localResolver), null);
    }
    if (Map.class.isAssignableFrom(rawType)) {
      return new ResolvedCodec(MapCodec.create(rawType, typeRef, localResolver), null);
    }
    return null;
  }

  JsonValueCodec<?> createRuntimeCodec(Class<?> rawType, JsonTypeResolver localResolver) {
    ResolvedCodec resolved = resolveCodec(rawType, TypeRef.of(rawType), localResolver, null, true);
    return resolved == null ? null : resolved.codec;
  }

  private JsonValueCodec<?> createExactCodec(
      Class<?> target,
      TypeRef<?> typeRef,
      FactoryBinding binding,
      JsonTypeResolver localResolver,
      boolean runtimeType) {
    JsonValueCodec<?> codec;
    try {
      codec = binding.factory().create(typeRef, localResolver, runtimeType);
    } catch (UnsupportedJsonTypeException e) {
      throw new ForyJsonException("Exact JSON codec factory rejected " + target.getTypeName(), e);
    }
    if (codec == null) {
      throw new ForyJsonException(
          "Exact JSON codec factory returned null for " + target.getTypeName());
    }
    return codec;
  }

  Class<?> runtimeCodecTarget(Class<?> runtimeType) {
    ExactFactoryBinding binding = runtimeFactories.get(runtimeType);
    if (binding == null) {
      return null;
    }
    checkSecure(binding.target);
    return binding.target;
  }

  private ResolvedCodec createModuleCodec(
      TypeRef<?> typeRef, JsonTypeResolver localResolver, boolean runtimeType) {
    JsonValueCodec<?> selected = null;
    UnsupportedJsonTypeException unsupported = null;
    ArrayList<String> claims = null;
    for (int i = 0; i < codecFactories.length; i++) {
      JsonValueCodec<?> codec = null;
      UnsupportedJsonTypeException rejected = null;
      try {
        codec = codecFactories[i].create(typeRef, localResolver, runtimeType);
      } catch (UnsupportedJsonTypeException e) {
        rejected = e;
      }
      if (codec == null && rejected == null) {
        continue;
      }
      if (claims == null) {
        claims = new ArrayList<>(2);
      }
      claims.add(codecFactoryIdentities[i]);
      if (codec != null && selected == null) {
        selected = codec;
      }
      if (rejected != null && unsupported == null) {
        unsupported = rejected;
      }
    }
    if (claims == null) {
      return null;
    }
    if (claims.size() == 1) {
      if (selected != null) {
        return new ResolvedCodec(selected, claims.get(0));
      }
      throw unsupported;
    }
    claims.sort(String::compareTo);
    throw new ForyJsonException(
        "Conflicting JSON codec factories for " + typeRef.getType() + ": " + claims);
  }

  static final class ResolvedCodec {
    private final JsonValueCodec<?> codec;
    private final String factoryKey;

    private ResolvedCodec(JsonValueCodec<?> codec, String factoryKey) {
      this.codec = codec;
      this.factoryKey = factoryKey;
    }

    JsonValueCodec<?> codec() {
      return codec;
    }

    String factoryKey() {
      return factoryKey;
    }
  }

  private static final class ExactFactoryBinding {
    private final Class<?> target;
    private final FactoryBinding binding;

    private ExactFactoryBinding(Class<?> target, FactoryBinding binding) {
      this.target = target;
      this.binding = binding;
    }
  }

  public JsonFieldKind kind(Class<?> type) {
    // A registered codec owns the full representation. Resolve that choice before object metadata
    // and codegen specialize fields so generated and interpreted paths cannot bypass the codec.
    if (customCodecs.get(type) != null
        || customCodecs.getFactory(type) != null
        || runtimeFactories.containsKey(type)) {
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
    return new JsonJITContext(asyncCompilationEnabled);
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

  /** Returns the effective annotation for one declaration in an exact target context. */
  @Internal
  public <A extends Annotation> A annotation(
      Class<?> targetType, AnnotatedElement declaration, Class<A> annotationType) {
    JsonMixinAnnotations.TargetOverlay overlay = mixinAnnotations.overlay(targetType);
    return overlay == null
        ? JsonMixinAnnotations.targetAnnotation(declaration, annotationType)
        : overlay.annotation(declaration, annotationType);
  }

  Class<?> mixinType(Class<?> targetType) {
    JsonMixinAnnotations.TargetOverlay overlay = mixinAnnotations.overlay(targetType);
    return overlay == null ? null : overlay.mixinType();
  }

  /** Adds exact pair context to a cold effective-schema validation failure. */
  @Internal
  public ForyJsonException mixinSchemaFailure(Class<?> targetType, ForyJsonException failure) {
    Class<?> mixinType = mixinType(targetType);
    if (mixinType == null) {
      return failure;
    }
    return new ForyJsonException(
        "Invalid effective JSON Mixin schema for target "
            + targetType.getName()
            + " from source "
            + mixinType.getName()
            + ": "
            + failure.getMessage(),
        failure);
  }

  /** Resolves one target-Mixin overlay for hosted metadata discovery. */
  @Internal
  public static JsonMixinView resolveMixin(Class<?> targetType, Class<?> mixinType) {
    return new JsonMixinView(JsonMixinAnnotations.resolve(targetType, mixinType));
  }

  /** Read-only hosted view of one fully validated structural Mixin overlay. */
  @Internal
  public static final class JsonMixinView {
    private final JsonMixinAnnotations.TargetOverlay overlay;

    private JsonMixinView(JsonMixinAnnotations.TargetOverlay overlay) {
      this.overlay = overlay;
    }

    public Set<AnnotatedElement> sourceDeclarations() {
      return overlay.sourceDeclarations();
    }

    public Set<AnnotatedElement> targetDeclarations() {
      return overlay.targetDeclarations();
    }

    public <A extends Annotation> A annotation(
        AnnotatedElement declaration, Class<A> annotationType) {
      return overlay.annotation(declaration, annotationType);
    }
  }

  JsonValueCodec<?> customCodec(Class<?> type) {
    return customCodecs.get(type);
  }

  JsonCodecDeclaration codecDeclaration(Class<?> targetType) {
    if (targetType.isAnnotation()) {
      return null;
    }
    try {
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
    } catch (ForyJsonException e) {
      throw mixinSchemaFailure(targetType, e);
    }
  }

  JsonValueDeclaration valueDeclaration(Class<?> targetType) {
    if (targetType.isAnnotation()) {
      return null;
    }
    try {
      synchronized (valueDeclarations) {
        JsonValueDeclaration cached = valueDeclarations.get(targetType);
        if (cached != null) {
          return cached;
        }
        if (typesWithoutValueDeclaration.contains(targetType)) {
          return null;
        }
        Class<?> mixinType = mixinType(targetType);
        boolean loadGeneratedCodec =
            !GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE
                && (targetType.getDeclaredAnnotation(JsonType.class) != null || mixinType != null);
        GeneratedJsonCodec<?> generatedCodec =
            loadGeneratedCodec ? generatedCodecIfPresent(targetType, mixinType) : null;
        JsonValueDeclaration resolved =
            JsonValueDeclaration.resolve(targetType, generatedCodec, this);
        if (resolved == null) {
          typesWithoutValueDeclaration.add(targetType);
        } else {
          valueDeclarations.put(targetType, resolved);
        }
        return resolved;
      }
    } catch (ForyJsonException e) {
      throw mixinSchemaFailure(targetType, e);
    }
  }

  JsonValueCodec<?> annotationCodec(
      Class<?> targetType, Class<? extends JsonValueCodec<?>> codecClass) {
    checkCustomSecure(targetType);
    return annotationCodecs.computeIfAbsent(codecClass, JsonSharedRegistry::newAnnotationCodec);
  }

  MapKeyCodec mapKeyCodec(Class<?> targetType, Class<? extends MapKeyCodec> codecClass) {
    checkMapKeySecure(targetType);
    return mapKeyCodecs.computeIfAbsent(codecClass, JsonSharedRegistry::newMapKeyCodec);
  }

  private JsonCodecDeclaration resolveCodecDeclaration(Class<?> targetType) {
    DeclarationCandidate direct = declaredCodec(targetType, targetType);
    if (direct != null) {
      return new JsonCodecDeclaration(direct.codecClass, new Class<?>[] {targetType}, false);
    }
    List<DeclarationCandidate> candidates = inheritedCodecCandidates(targetType);
    if (candidates.isEmpty()) {
      return null;
    }
    List<DeclarationCandidate> frontier = mostSpecificDeclarations(candidates);
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

  private List<DeclarationCandidate> inheritedCodecCandidates(Class<?> targetType) {
    List<DeclarationCandidate> candidates = new ArrayList<>();
    Set<Class<?>> visited = Collections.newSetFromMap(new IdentityHashMap<Class<?>, Boolean>());
    Deque<Class<?>> pending = new ArrayDeque<>();
    addParents(targetType, pending);
    while (!pending.isEmpty()) {
      Class<?> current = pending.removeFirst();
      if (!visited.add(current)) {
        continue;
      }
      if (!current.isAnnotation()) {
        DeclarationCandidate candidate = declaredCodec(targetType, current);
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

  private static List<DeclarationCandidate> mostSpecificDeclarations(
      List<DeclarationCandidate> candidates) {
    List<DeclarationCandidate> frontier = new ArrayList<>(candidates.size());
    for (DeclarationCandidate candidate : candidates) {
      boolean dominated = false;
      for (DeclarationCandidate other : candidates) {
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

  private DeclarationCandidate declaredCodec(Class<?> targetType, Class<?> declarationType) {
    JsonCodec annotation;
    try {
      annotation = annotation(targetType, declarationType, JsonCodec.class);
    } catch (ForyJsonException e) {
      throw e;
    } catch (RuntimeException | LinkageError e) {
      throw new ForyJsonException(
          "Cannot read @JsonCodec declared on " + declarationType.getName(), e);
    }
    if (annotation == null) {
      return null;
    }
    try {
      Class<? extends JsonValueCodec<?>> codecClass = annotation.value();
      if (codecClass == JsonCodec.NoJsonValueCodec.class
          || annotation.elementCodec() != JsonCodec.NoJsonValueCodec.class
          || annotation.contentCodec() != JsonCodec.NoJsonValueCodec.class
          || annotation.keyCodec() != JsonCodec.NoMapKeyCodec.class
          || annotation.valueCodec() != JsonCodec.NoJsonValueCodec.class) {
        throw new ForyJsonException(
            "@JsonCodec on type "
                + declarationType.getName()
                + " must set only the complete value codec");
      }
      return new DeclarationCandidate(declarationType, codecClass);
    } catch (RuntimeException | LinkageError e) {
      if (e instanceof ForyJsonException) {
        throw (ForyJsonException) e;
      }
      throw new ForyJsonException(
          "Cannot resolve @JsonCodec declared on " + declarationType.getName(), e);
    }
  }

  private static ForyJsonException inheritedCodecConflict(
      Class<?> targetType, List<DeclarationCandidate> frontier) {
    StringBuilder message =
        new StringBuilder("Conflicting inherited @JsonCodec declarations for ")
            .append(targetType.getName())
            .append(':');
    for (DeclarationCandidate candidate : frontier) {
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
        .append(" or register an exact codec for ")
        .append(targetType.getName())
        .append(".");
    return new ForyJsonException(message.toString());
  }

  private static JsonValueCodec<?> newAnnotationCodec(
      Class<? extends JsonValueCodec<?>> codecClass) {
    return newCodec(codecClass, "JSON codec");
  }

  private static MapKeyCodec newMapKeyCodec(Class<? extends MapKeyCodec> codecClass) {
    return newCodec(codecClass, "JSON map key codec");
  }

  @SuppressWarnings("unchecked")
  private static <T> T newCodec(Class<? extends T> codecClass, String role) {
    validateCodecClass(codecClass, role);
    if (GraalvmSupport.isGraalRuntime()) {
      try {
        return (T) ReflectionUtils.getCtrHandle(codecClass, new Class<?>[0]).invoke();
      } catch (Throwable e) {
        throw invalidCodecClass(codecClass, role, "constructor failed", e);
      }
    }
    Constructor<? extends T> constructor;
    try {
      constructor = codecClass.getConstructor();
    } catch (NoSuchMethodException e) {
      throw invalidCodecClass(codecClass, role, "must have a public no-argument constructor", e);
    } catch (SecurityException e) {
      throw inaccessibleCodecClass(codecClass, role, e);
    } catch (LinkageError e) {
      throw invalidCodecClass(codecClass, role, "constructor cannot be inspected", e);
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
        throw invalidCodecClass(codecClass, role, "constructor failed", retryFailure.getCause());
      } catch (ReflectiveOperationException | LinkageError | RuntimeException retryFailure) {
        throw inaccessibleCodecClass(codecClass, role, retryFailure);
      }
    } catch (InvocationTargetException e) {
      throw invalidCodecClass(codecClass, role, "constructor failed", e.getCause());
    } catch (ReflectiveOperationException | LinkageError e) {
      throw invalidCodecClass(codecClass, role, "cannot be constructed", e);
    } catch (RuntimeException e) {
      throw invalidCodecClass(codecClass, role, "cannot be constructed", e);
    }
  }

  private static void validateCodecClass(Class<?> codecClass, String role) {
    int modifiers = codecClass.getModifiers();
    if (!Modifier.isPublic(modifiers)) {
      throw invalidCodecClass(codecClass, role, "must be public", null);
    }
    if (codecClass.isInterface() || Modifier.isAbstract(modifiers)) {
      throw invalidCodecClass(codecClass, role, "must be concrete", null);
    }
    if (codecClass.getEnclosingClass() != null
        && (!codecClass.isMemberClass() || !Modifier.isStatic(modifiers))) {
      throw invalidCodecClass(codecClass, role, "must be top-level or a static nested class", null);
    }
    for (Class<?> enclosing = codecClass.getEnclosingClass();
        enclosing != null;
        enclosing = enclosing.getEnclosingClass()) {
      if (!Modifier.isPublic(enclosing.getModifiers())) {
        throw invalidCodecClass(codecClass, role, "must be enclosed only by public classes", null);
      }
    }
  }

  private static ForyJsonException inaccessibleCodecClass(
      Class<?> codecClass, String role, Throwable cause) {
    Package codecPackage = codecClass.getPackage();
    String packageName = codecPackage == null ? "the unnamed package" : codecPackage.getName();
    return new ForyJsonException(
        "Cannot access the public no-argument constructor of "
            + role
            + ' '
            + codecClass.getName()
            + "; export or open package "
            + packageName
            + " to module org.apache.fory.json.",
        cause);
  }

  private static ForyJsonException invalidCodecClass(
      Class<?> codecClass, String role, String reason, Throwable cause) {
    String message = role + ' ' + codecClass.getName() + ' ' + reason + '.';
    return cause == null ? new ForyJsonException(message) : new ForyJsonException(message, cause);
  }

  boolean hasSubTypes(Class<?> baseType) {
    return effectiveSubTypes(baseType) != null;
  }

  boolean inferredSubTypes(Class<?> baseType) {
    JsonSubTypes annotation = effectiveSubTypes(baseType);
    return annotation != null && annotation.value().length == 0;
  }

  JsonSubTypesInfo explicitSubTypesInfo(Class<?> baseType) {
    JsonSubTypes annotation = effectiveSubTypes(baseType);
    return annotation == null || annotation.value().length == 0 ? null : subTypesInfo(baseType);
  }

  JsonSubTypesInfo cachedSubTypesInfo(Class<?> baseType) {
    synchronized (subTypesCache) {
      return subTypesCache.get(baseType);
    }
  }

  JsonSubTypesInfo subTypesInfo(Class<?> baseType) {
    try {
      JsonSubTypes annotation = effectiveSubTypes(baseType);
      if (annotation == null) {
        return null;
      }
      synchronized (subTypesCache) {
        JsonSubTypesInfo cached = subTypesCache.get(baseType);
        if (cached != null) {
          return cached;
        }
        JsonSubTypesInfo resolved;
        if (annotation.value().length == 0) {
          InferredSubtypes inferred = javaInferredSubtypes(baseType);
          resolved =
              buildInferredSubTypesInfo(baseType, annotation, inferred.classes, inferred.names);
        } else {
          resolved = buildExplicitSubTypesInfo(baseType, annotation);
        }
        subTypesCache.put(baseType, resolved);
        return resolved;
      }
    } catch (ForyJsonException e) {
      throw mixinSchemaFailure(baseType, e);
    }
  }

  JsonSubTypesInfo inferredSubTypesInfo(
      Class<?> baseType, Class<?>[] candidateClasses, String[] candidateNames) {
    try {
      JsonSubTypes annotation = effectiveSubTypes(baseType);
      if (annotation == null || annotation.value().length != 0) {
        throw new ForyJsonException(
            "Inferred subtype metadata requires empty @JsonSubTypes on " + baseType.getName());
      }
      synchronized (subTypesCache) {
        JsonSubTypesInfo cached = subTypesCache.get(baseType);
        if (cached != null) {
          return cached;
        }
        JsonSubTypesInfo resolved =
            buildInferredSubTypesInfo(baseType, annotation, candidateClasses, candidateNames);
        subTypesCache.put(baseType, resolved);
        return resolved;
      }
    } catch (ForyJsonException e) {
      throw mixinSchemaFailure(baseType, e);
    }
  }

  private JsonSubTypes effectiveSubTypes(Class<?> baseType) {
    return annotation(baseType, baseType, JsonSubTypes.class);
  }

  private static void validateSubTypesDeclaration(Class<?> baseType, JsonSubTypes annotation) {
    if (!baseType.isInterface() && !Modifier.isAbstract(baseType.getModifiers())) {
      throw new ForyJsonException(
          "@JsonSubTypes requires an interface or abstract type " + baseType);
    }
    Inclusion inclusion = annotation.inclusion();
    String property = annotation.property();
    if (inclusion == Inclusion.PROPERTY) {
      if (property.isEmpty()) {
        throw new ForyJsonException("PROPERTY @JsonSubTypes requires a discriminator property");
      }
      validateJsonName(property, "subtype discriminator");
    } else if (!property.isEmpty()) {
      throw new ForyJsonException(inclusion + " @JsonSubTypes must not declare property");
    }
  }

  private JsonSubTypesInfo buildExplicitSubTypesInfo(Class<?> baseType, JsonSubTypes annotation) {
    validateSubTypesDeclaration(baseType, annotation);
    Inclusion inclusion = annotation.inclusion();
    String property = annotation.property();
    JsonSubTypes.Type[] entries = annotation.value();
    String[] names = new String[entries.length];
    String[] classNames = new String[entries.length];
    boolean hasStringEntry = false;
    Set<String> logicalNames = new HashSet<>();
    Set<Long> logicalHashes = new HashSet<>();
    for (int i = 0; i < entries.length; i++) {
      JsonSubTypes.Type entry = entries[i];
      String name = entry.name();
      validateJsonName(name, "subtype");
      if (!logicalNames.add(name)) {
        throw new ForyJsonException("Invalid or duplicate JSON subtype name " + name);
      }
      long hash = org.apache.fory.json.meta.JsonFieldNameHash.hash(name);
      if (!logicalHashes.add(Long.valueOf(hash))) {
        throw new ForyJsonException("JSON subtype name hash collision for " + name);
      }
      names[i] = name;
      boolean literal = entry.value() != Void.class;
      boolean byName = !entry.className().isEmpty();
      if (literal == byName) {
        throw new ForyJsonException(
            "JSON subtype must declare exactly one of value or className for " + name);
      }
      if (byName) {
        validateBinaryName(entry.className());
        classNames[i] = entry.className();
        hasStringEntry = true;
      }
    }
    if (hasStringEntry && GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      throw new ForyJsonException(
          "GraalVM native image requires @JsonSubTypes class literals on " + baseType.getName());
    }
    for (String className : classNames) {
      if (className != null) {
        checkSecureName(className);
      }
    }
    if (hasStringEntry) {
      Class<?> loadedBase = loadClass(baseType.getName());
      if (loadedBase != baseType) {
        throw new ForyJsonException(
            "Configured class loader resolves a different subtype base " + baseType.getName());
      }
    }
    Class<?>[] classes = new Class<?>[entries.length];
    Set<Class<?>> classIdentities = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    Set<String> binaryNames = new HashSet<>();
    for (int i = 0; i < entries.length; i++) {
      Class<?> subtype = classNames[i] == null ? entries[i].value() : loadExactClass(classNames[i]);
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
      classes[i] = subtype;
    }
    return new JsonSubTypesInfo(inclusion, property, classes, names);
  }

  private JsonSubTypesInfo buildInferredSubTypesInfo(
      Class<?> baseType,
      JsonSubTypes annotation,
      Class<?>[] candidateClasses,
      String[] candidateNames) {
    validateSubTypesDeclaration(baseType, annotation);
    if (candidateClasses == null || candidateNames == null) {
      throw new ForyJsonException("Missing inferred subtype metadata for " + baseType.getName());
    }
    if (candidateClasses.length == 0 || candidateClasses.length != candidateNames.length) {
      throw new ForyJsonException("Invalid inferred subtype metadata for " + baseType.getName());
    }
    Class<?>[] classes = candidateClasses.clone();
    String[] names = candidateNames.clone();
    for (Class<?> subtype : classes) {
      if (subtype == null) {
        throw new ForyJsonException(
            "Inferred subtype metadata contains null for " + baseType.getName());
      }
    }
    sortSubtypes(classes, names);
    Set<Class<?>> classIdentities =
        Collections.newSetFromMap(new IdentityHashMap<Class<?>, Boolean>());
    Set<String> binaryNames = new HashSet<>();
    for (Class<?> subtype : classes) {
      validateSubtype(baseType, subtype);
      if (!classIdentities.add(subtype) || !binaryNames.add(subtype.getName())) {
        throw new ForyJsonException("Duplicate closed JSON subtype " + subtype.getName());
      }
      // The selected static base authorizes its complete sealed closure. The fixed disallow list
      // can invalidate that schema; the configurable checker below may only narrow exact entries.
      DisallowedList.checkNotInDisallowedList(subtype.getName());
    }
    // Validate the complete static closure before configuration-level narrowing. A checker cannot
    // turn an ambiguous schema into a valid table by hiding one duplicate or colliding name.
    validateSubtypeNames(names);
    JsonNativeSubtypeRegistry.publish(baseType, mixinType(baseType), classes, names);
    JsonTypeChecker checker = typeChecker;
    if (checker != null) {
      int accepted = 0;
      for (int i = 0; i < classes.length; i++) {
        boolean allowed;
        try {
          allowed = checkType(classes[i].getName(), checker);
        } catch (InsecureException ignored) {
          allowed = false;
        }
        if (allowed) {
          classes[accepted] = classes[i];
          names[accepted] = names[i];
          accepted++;
        }
      }
      if (accepted == 0) {
        throw new ForyJsonException(
            "JsonTypeChecker rejected every inferred subtype of " + baseType.getName());
      }
      classes = java.util.Arrays.copyOf(classes, accepted);
      names = java.util.Arrays.copyOf(names, accepted);
    }
    return new JsonSubTypesInfo(annotation.inclusion(), annotation.property(), classes, names);
  }

  private static void validateSubtype(Class<?> baseType, Class<?> subtype) {
    if (subtype == null) {
      throw new ForyJsonException(
          "Inferred subtype metadata contains null for " + baseType.getName());
    }
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
  }

  private static void validateSubtypeNames(String[] names) {
    Set<String> logicalNames = new HashSet<>();
    Set<Long> logicalHashes = new HashSet<>();
    for (String name : names) {
      validateJsonName(name, "subtype");
      if (!logicalNames.add(name)) {
        throw new ForyJsonException("Invalid or duplicate JSON subtype name " + name);
      }
      long hash = org.apache.fory.json.meta.JsonFieldNameHash.hash(name);
      if (!logicalHashes.add(Long.valueOf(hash))) {
        throw new ForyJsonException("JSON subtype name hash collision for " + name);
      }
    }
  }

  private static void sortSubtypes(Class<?>[] classes, String[] names) {
    for (int i = 1; i < classes.length; i++) {
      Class<?> subtype = classes[i];
      String name = names[i];
      int j = i;
      while (j > 0 && classes[j - 1].getName().compareTo(subtype.getName()) > 0) {
        classes[j] = classes[j - 1];
        names[j] = names[j - 1];
        j--;
      }
      classes[j] = subtype;
      names[j] = name;
    }
  }

  private InferredSubtypes javaInferredSubtypes(Class<?> baseType) {
    JsonNativeSubtypeRegistry.Table nativeTable =
        JsonNativeSubtypeRegistry.table(baseType, mixinType(baseType));
    if (nativeTable != null) {
      return new InferredSubtypes(nativeTable.classes, nativeTable.names);
    }
    // Native Image runtime must consume the closure embedded during hosted analysis. Its Java
    // sealed reflection metadata may be incomplete, so rediscovery here could silently change the
    // authorized table instead of reporting a missing build-time schema root.
    if (GraalvmSupport.isGraalRuntime()) {
      throw new ForyJsonException(
          "Missing embedded inferred subtype table for " + baseType.getName());
    }
    // A generated table owns source-level discriminator names after class-file obfuscation. Prefer
    // it on every JVM; sealed reflection remains the unprocessed Java 17 fallback.
    GeneratedJsonSubtypeTable table = loadGeneratedSubtypeTable(baseType);
    if (table != null) {
      return generatedSubtypes(baseType, table);
    }
    if (AndroidSupport.IS_ANDROID) {
      throw missingGeneratedSubtypeTable(baseType, mixinType(baseType));
    }
    Class<?>[] classes = SealedClassSupport.subtypes(baseType);
    if (classes != null) {
      String[] names = new String[classes.length];
      for (int i = 0; i < classes.length; i++) {
        names[i] = classes[i].getSimpleName();
      }
      return new InferredSubtypes(classes, names);
    }
    throw new ForyJsonException(
        "Empty @JsonSubTypes requires Java 17 sealed metadata or a generated subtype table for "
            + baseType.getName());
  }

  private static InferredSubtypes generatedSubtypes(
      Class<?> baseType, GeneratedJsonSubtypeTable table) {
    try {
      if (table.type() != baseType) {
        throw new ForyJsonException(
            "Generated JSON subtype table has the wrong base for " + baseType.getName());
      }
      return new InferredSubtypes(table.subtypes(), table.names());
    } catch (RuntimeException | LinkageError e) {
      if (e instanceof ForyJsonException) {
        throw (ForyJsonException) e;
      }
      throw new ForyJsonException(
          "Cannot read generated JSON subtype metadata for " + baseType.getName(), e);
    }
  }

  private GeneratedJsonSubtypeTable loadGeneratedSubtypeTable(Class<?> baseType) {
    Class<?> mixinType = mixinType(baseType);
    String generatedName =
        mixinType == null
            ? generatedSubtypeTableBinaryName(baseType)
            : generatedMixinSubtypeTableBinaryName(mixinType, baseType);
    Class<?> generatedClass;
    try {
      generatedClass =
          Class.forName(
              generatedName,
              false,
              mixinType == null ? baseType.getClassLoader() : mixinType.getClassLoader());
    } catch (ClassNotFoundException e) {
      return null;
    } catch (LinkageError e) {
      throw new ForyJsonException("Cannot load generated JSON subtype table " + generatedName, e);
    }
    if (!GeneratedJsonSubtypeTable.class.isAssignableFrom(generatedClass)
        || !Modifier.isPublic(generatedClass.getModifiers())) {
      throw new ForyJsonException("Invalid generated JSON subtype table " + generatedName);
    }
    try {
      return (GeneratedJsonSubtypeTable) generatedClass.getConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      throw new ForyJsonException(
          "Cannot construct generated JSON subtype table " + generatedName, unwrap(e));
    }
  }

  private static final class InferredSubtypes {
    private final Class<?>[] classes;
    private final String[] names;

    private InferredSubtypes(Class<?>[] classes, String[] names) {
      this.classes = classes;
      this.names = names;
    }
  }

  private void checkSecureName(String className) {
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

  private Class<?> loadExactClass(String className) {
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

  private static void validateBinaryName(String className) {
    if (className.isEmpty()
        || !className.equals(className.trim())
        || className.startsWith(".")
        || className.endsWith(".")
        || className.contains("..")
        || className.indexOf('[') >= 0
        || className.indexOf(']') >= 0
        || className.indexOf(';') >= 0
        || className.indexOf('/') >= 0
        || className.indexOf('\\') >= 0
        || className.equals("void")
        || className.equals("boolean")
        || className.equals("byte")
        || className.equals("short")
        || className.equals("char")
        || className.equals("int")
        || className.equals("long")
        || className.equals("float")
        || className.equals("double")) {
      throw new ForyJsonException("Invalid JSON subtype binary name " + className);
    }
    for (int i = 0; i < className.length(); i++) {
      if (Character.isWhitespace(className.charAt(i))) {
        throw new ForyJsonException("Invalid JSON subtype binary name " + className);
      }
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

  private static final class DeclarationCandidate {
    private final Class<?> declarationType;
    private final Class<? extends JsonValueCodec<?>> codecClass;

    private DeclarationCandidate(
        Class<?> declarationType, Class<? extends JsonValueCodec<?>> codecClass) {
      this.declarationType = declarationType;
      this.codecClass = codecClass;
    }
  }
}

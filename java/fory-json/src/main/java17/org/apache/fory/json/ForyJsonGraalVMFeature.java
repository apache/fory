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

package org.apache.fory.json;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.fory.json.annotation.ForyJsonProvider;
import org.apache.fory.json.annotation.JsonAnySetter;
import org.apache.fory.json.annotation.JsonBase64;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonMixin;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.apache.fory.json.annotation.JsonType;
import org.apache.fory.json.annotation.JsonUnwrapped;
import org.apache.fory.json.annotation.JsonValidator;
import org.apache.fory.json.annotation.JsonValue;
import org.apache.fory.json.codec.Base64ByteArrayCodec;
import org.apache.fory.json.codec.JsonUnwrappedInfo;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.ObjectCodec.AnyInfo;
import org.apache.fory.json.codegen.JsonCodegen;
import org.apache.fory.json.meta.JsonCreatorInfo;
import org.apache.fory.json.meta.JsonFieldAccessor;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonValidatorInfo;
import org.apache.fory.json.resolver.CodecRegistry.FactoryBinding;
import org.apache.fory.json.resolver.JsonGeneratedClassRegistry;
import org.apache.fory.json.resolver.JsonNativeSubtypeRegistry;
import org.apache.fory.json.resolver.JsonSharedRegistry;
import org.apache.fory.json.resolver.JsonSharedRegistry.JsonMixinView;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.platform.internal._JDKAccess;
import org.apache.fory.reflect.ObjectInstantiators;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.util.record.RecordUtils;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

/** Prepares reachable Fory JSON models and exact generated codecs for Native Image. */
final class ForyJsonGraalVMFeature implements Feature {
  private static final String SCALA_DERIVED_CODEC_METHOD = "derived$ScalaJsonCodec";
  private static final String SCALA_JSON_CODEC_CLASS = "org.apache.fory.json.scala.ScalaJsonCodec";
  private static final String SCALA_JSON_CODEC_FACTORY =
      "org.apache.fory.json.scala.internal.ScalaTypeCodecFactory$";
  private static final String SCALA_ENUMERATION_ANNOTATION =
      "org.apache.fory.json.scala.JsonEnumeration";
  private static final String[] SCALA_ENUMERATION_SLOTS = {
    "value", "element", "content", "mapKey", "mapValue"
  };
  private static final String SCALA_ENUM_CLASS = "scala.reflect.Enum";
  private static final String SCALA_PRODUCT_CLASS = "scala.Product";
  private static final String[] SQL_TYPES = {
    "java.sql.Date", "java.sql.Time", "java.sql.Timestamp"
  };

  private final Set<Class<?>> reachableTypes = ConcurrentHashMap.newKeySet();
  private final Set<Class<?>> processedReachableTypes = ConcurrentHashMap.newKeySet();
  private final Set<Class<?>> processedDeclarations = ConcurrentHashMap.newKeySet();
  private final Set<Class<?>> processedModels = ConcurrentHashMap.newKeySet();
  private final Set<Class<?>> processedMixins = ConcurrentHashMap.newKeySet();
  private final Map<Class<?>, Set<Class<?>>> reachableMixins = new LinkedHashMap<>();
  private final Set<Class<?>> processedProviders = ConcurrentHashMap.newKeySet();
  private final Set<Class<?>> processedFactoryModels = ConcurrentHashMap.newKeySet();
  private final Set<Class<?>> scalaFactoryModels = ConcurrentHashMap.newKeySet();
  private final Set<Class<?>> scalaEnumerationOwners = ConcurrentHashMap.newKeySet();
  private final Set<Class<?>> processedCodecs = ConcurrentHashMap.newKeySet();
  private final Set<Class<?>> processedContainers = ConcurrentHashMap.newKeySet();
  private final Set<Executable> processedCreators = new LinkedHashSet<>();
  // Reflection-only registrations are tracked apart from processedCreators, whose membership also
  // means a creator handle was retained.
  private final Set<Method> processedReflectiveMethods = new LinkedHashSet<>();
  private final Set<Class<?>> languageSingletonOwners = new LinkedHashSet<>();
  private final Set<ObjectCodec<?>> processedObjectModels =
      Collections.newSetFromMap(new IdentityHashMap<>());
  private final ArrayList<HostedConfiguration> hostedConfigurations = new ArrayList<>();

  @Override
  public String getDescription() {
    return "Prepares reachable Fory JSON models for GraalVM Native Image";
  }

  @Override
  public void beforeAnalysis(BeforeAnalysisAccess access) {
    // native-image.properties owns class initialization; this Feature owns reachability metadata.
    access.registerSubtypeReachabilityHandler(this::processReachableType, Object.class);
  }

  private void processReachableType(DuringAnalysisAccess ignored, Class<?> type) {
    reachableTypes.add(type);
  }

  @Override
  public void duringAnalysis(DuringAnalysisAccess access) {
    if (!reachableTypes.contains(ForyJson.class)) {
      return;
    }
    boolean changed = false;
    List<Class<?>> orderedTypes = new ArrayList<>(reachableTypes);
    orderedTypes.sort(Comparator.comparing(Class::getName));
    for (Class<?> type : orderedTypes) {
      if (processedReachableTypes.add(type)) {
        changed |= registerContainer(type);
        changed |= registerDeclarations(type);
        JsonMixin mixin = type.getDeclaredAnnotation(JsonMixin.class);
        if (mixin == null) {
          boolean scalaModel = registerScalaModel(access, type);
          changed |= scalaModel;
          if ((type.getDeclaredAnnotation(JsonType.class) != null
                  || type.getDeclaredAnnotation(JsonSubTypes.class) != null)
              && !scalaModel) {
            changed |= registerModel(access, type);
          }
        } else {
          changed |= registerMixin(access, type, mixin.target());
        }
        if (type.getDeclaredAnnotation(ForyJsonProvider.class) != null) {
          changed |= registerProvider(access, type);
        }
        if (type == ForyJson.class) {
          registerBuiltInTypes(access);
          hostedConfigurations.add(
              new HostedConfiguration(ForyJson.builder().buildConfig()));
          changed = true;
        }
      }
    }
    changed |= generateConfigurations(access);
    if (changed) {
      access.requireAnalysisIteration();
    }
  }

  private boolean registerScalaModel(DuringAnalysisAccess access, Class<?> type) {
    boolean scalaEnum = implementsInterface(type, SCALA_ENUM_CLASS);
    boolean scalaProduct = implementsInterface(type, SCALA_PRODUCT_CLASS);
    boolean derivedSchema = scalaEnum;
    if (!derivedSchema) {
      JsonSubTypes subTypes = type.getDeclaredAnnotation(JsonSubTypes.class);
      derivedSchema = subTypes != null && subTypes.value().length == 0;
    }
    if (!derivedSchema
        && !(scalaProduct && type.getDeclaredAnnotation(JsonType.class) != null)) {
      return false;
    }
    Method method;
    try {
      method = type.getDeclaredMethod(SCALA_DERIVED_CODEC_METHOD);
    } catch (NoSuchMethodException ignored) {
      JsonType jsonType = type.getDeclaredAnnotation(JsonType.class);
      if (scalaEnum && jsonType != null) {
        try {
          method = type.getMethod("values");
        } catch (NoSuchMethodException missingValues) {
          return false;
        }
        int modifiers = method.getModifiers();
        if (!Modifier.isPublic(modifiers)
            || !Modifier.isStatic(modifiers)
            || method.getParameterCount() != 0
            || !method.getReturnType().isArray()
            || method.getReturnType().getComponentType() != type) {
          return false;
        }
        RuntimeReflection.register(method);
      } else if (!scalaProduct || jsonType == null) {
        return false;
      }
      scalaFactoryModels.add(type);
      registerFactoryModel(access, type);
      return true;
    }
    int modifiers = method.getModifiers();
    if (!Modifier.isPublic(modifiers)
        || !Modifier.isStatic(modifiers)
        || method.getParameterCount() != 0
        || !method.getReturnType().getName().equals(SCALA_JSON_CODEC_CLASS)) {
      return false;
    }
    RuntimeReflection.register(method);
    JsonCodecFactory factory;
    try {
      factory = (JsonCodecFactory) method.invoke(null);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "Cannot load derived Scala JSON codec for " + type.getName(), e);
    }
    if (factory == null) {
      throw new IllegalStateException(
          "Derived Scala JSON codec is null for " + type.getName());
    }
    scalaFactoryModels.add(type);
    registerFactoryModel(access, type);
    for (Class<?> runtimeType : factory.handledRuntimeClasses()) {
      registerFactoryModel(access, runtimeType);
    }
    return true;
  }

  private boolean registerProvider(DuringAnalysisAccess access, Class<?> providerClass) {
    if (!processedProviders.add(providerClass)) {
      return false;
    }
    int modifiers = providerClass.getModifiers();
    if (!Modifier.isPublic(modifiers)
        || Modifier.isAbstract(modifiers)
        || providerClass.isInterface()
        || providerClass.isEnum()) {
      throw providerFailure(providerClass, "must be a public concrete class", null);
    }
    Constructor<?> constructor;
    try {
      constructor = providerClass.getConstructor();
    } catch (NoSuchMethodException e) {
      throw providerFailure(providerClass, "must have a public no-argument constructor", e);
    }
    MethodHandle providerConstructor = providerConstructor(providerClass, constructor);
    Object provider;
    try {
      provider = providerConstructor.invoke();
    } catch (Throwable e) {
      throw providerFailure(providerClass, "cannot be constructed", e);
    }
    List<Method> methods = providerMethods(providerClass);
    if (methods.isEmpty()) {
      throw providerFailure(providerClass, "does not declare an effective ForyJson method", null);
    }
    boolean changed = false;
    for (Method method : methods) {
      MethodHandle providerMethod = providerMethod(providerClass, method);
      ForyJson json;
      try {
        json = (ForyJson) providerMethod.invoke(provider);
      } catch (Throwable e) {
        throw providerFailure(providerClass, "cannot invoke provider method " + method, e);
      }
      if (json == null) {
        throw providerFailure(providerClass, "provider method returned null: " + method, null);
      }
      JsonConfig config = json.config();
      if (!config.codegenEnabled()) {
        throw providerFailure(
            providerClass, "provider method returned a codegen-disabled ForyJson: " + method, null);
      }
      HostedConfiguration configuration = new HostedConfiguration(config);
      hostedConfigurations.add(configuration);
      changed = true;
      ArrayList<Map.Entry<Class<?>, FactoryBinding>> bindings =
          new ArrayList<>(config.codecRegistry().factoryBindings().entrySet());
      bindings.sort(Comparator.comparing(entry -> entry.getKey().getName()));
      for (Map.Entry<Class<?>, FactoryBinding> entry : bindings) {
        changed |= addFactoryRoot(access, configuration, entry.getKey());
        for (Class<?> runtimeType : entry.getValue().handledRuntimeClasses()) {
          changed |= registerFactoryModel(access, runtimeType);
        }
      }
    }
    return changed;
  }

  private boolean addFactoryRoot(
      DuringAnalysisAccess access, HostedConfiguration configuration, Class<?> type) {
    boolean changed = configuration.factoryModels.add(type);
    return registerFactoryModel(access, type) || changed;
  }

  private boolean registerFactoryModel(DuringAnalysisAccess access, Class<?> type) {
    if (processedModels.contains(type) || !processedFactoryModels.add(type)) {
      return false;
    }
    RuntimeReflection.register(type);
    registerContainer(type);
    registerDeclarations(type);
    registerModelHierarchy(access, type);
    if (type.isRecord()) {
      registerRecord(type);
    }
    return true;
  }

  private static MethodHandle providerConstructor(
      Class<?> providerClass, Constructor<?> constructor) {
    try {
      return _JDKAccess._trustedLookup(providerClass).unreflectConstructor(constructor);
    } catch (IllegalAccessException e) {
      throw providerFailure(providerClass, "cannot access its constructor", e);
    }
  }

  private static MethodHandle providerMethod(Class<?> providerClass, Method method) {
    try {
      return _JDKAccess._trustedLookup(method.getDeclaringClass()).unreflect(method);
    } catch (IllegalAccessException e) {
      throw providerFailure(providerClass, "cannot access method " + method, e);
    }
  }

  private static List<Method> providerMethods(Class<?> providerClass) {
    Map<MethodSignature, Method> effective = new HashMap<>();
    for (Method method : providerClass.getMethods()) {
      if (method.isBridge()
          || method.isSynthetic()
          || Modifier.isStatic(method.getModifiers())
          || method.getParameterCount() != 0
          || method.getReturnType() != ForyJson.class) {
        continue;
      }
      MethodSignature signature = new MethodSignature(method);
      Method previous = effective.get(signature);
      if (previous == null
          || previous.getDeclaringClass().isAssignableFrom(method.getDeclaringClass())) {
        effective.put(signature, method);
      } else if (!method.getDeclaringClass().isAssignableFrom(previous.getDeclaringClass())
          && method.getDeclaringClass().getName().compareTo(previous.getDeclaringClass().getName())
              < 0) {
        effective.put(signature, method);
      }
    }
    ArrayList<Method> methods = new ArrayList<>(effective.values());
    methods.sort(
        Comparator.comparing(Method::getName)
            .thenComparing(method -> method.getDeclaringClass().getName())
            .thenComparing(Method::toGenericString));
    return methods;
  }

  private boolean generateConfigurations(DuringAnalysisAccess access) {
    boolean changed = false;
    for (HostedConfiguration configuration : hostedConfigurations) {
      LinkedHashSet<Class<?>> selectedModels = new LinkedHashSet<>(processedModels);
      selectedModels.addAll(configuration.factoryModels);
      if (configuration.scalaJsonCodecs) {
        selectedModels.addAll(scalaFactoryModels);
      }
      for (Map.Entry<Class<?>, Set<Class<?>>> mixin : reachableMixins.entrySet()) {
        if (mixin.getValue().contains(configuration.mixins.get(mixin.getKey()))) {
          selectedModels.add(mixin.getKey());
        }
      }
      ArrayList<Class<?>> models = new ArrayList<>(selectedModels);
      models.sort(Comparator.comparing(Class::getName));
      boolean generated = false;
      for (Class<?> model : models) {
        // A raw generic Class is not a schema. Hosted capabilities are generated only when a
        // concrete TypeRef occurrence is reached from a selected non-generic root; eagerly
        // resolving the raw class would also make unreached bindings available in the image.
        if (model.getTypeParameters().length != 0) {
          continue;
        }
        if (!configuration.processedModels.add(model)) {
          continue;
        }
        List<ObjectCodec<?>> objectModels;
        try {
          objectModels = configuration.resolver.generateHostedCodecs(model);
        } catch (RuntimeException | LinkageError e) {
          throw new IllegalStateException(
              "Cannot generate Fory JSON codecs for " + model.getName(), e);
        }
        objectModels.sort(Comparator.comparing(codec -> codec.type().getName()));
        for (ObjectCodec<?> objectModel : objectModels) {
          registerObjectModel(access, objectModel);
        }
        boolean scalaModel = scalaFactoryModels.contains(model);
        for (Class<?> subtype : configuration.resolver.resolvedSubtypeClasses(model)) {
          changed |=
              scalaModel
                  ? registerFactoryModel(access, subtype)
                  : registerModel(access, subtype);
        }
        generated = true;
        changed = true;
      }
      if (generated) {
        Set<Class<?>> generatedClasses = JsonGeneratedClassRegistry.register(configuration.registry);
        for (Class<?> generatedClass : generatedClasses) {
          registerGeneratedClass(generatedClass);
        }
      }
    }
    return changed;
  }

  private void registerObjectModel(DuringAnalysisAccess access, ObjectCodec<?> objectModel) {
    if (!processedObjectModels.add(objectModel)) {
      return;
    }
    JsonCreatorInfo creator = objectModel.creatorInfo();
    if (creator != null && !creator.fixedInstance()) {
      registerCreator(creator.executable());
      registerCreator(creator.invocationExecutable());
      if (creator.defaultConstructor() != null) {
        registerCreator(creator.defaultConstructor());
      }
      for (int i = 0; i < creator.argumentCount(); i++) {
        Method defaultMethod = creator.defaultMethod(i);
        if (defaultMethod == null) {
          continue;
        }
        if (Modifier.isStatic(defaultMethod.getModifiers())) {
          registerCreator(defaultMethod);
        } else if (processedReflectiveMethods.add(defaultMethod)) {
          // An instance default is bound to its owning singleton when the creator metadata is
          // rebuilt at image runtime. creatorHandle spreads an argument array over the exact
          // parameter count, which does not describe a method that also takes a receiver, so the
          // method is only made reflectively available here.
          RuntimeReflection.register(defaultMethod);
        }
      }
      registerLanguageSingletonOwner(creator.executable().getDeclaringClass());
    }
    for (JsonFieldInfo field : objectModel.writeFields()) {
      registerFieldAccessor(access, field.writeField(), field.writeGetter(), null);
    }
    for (JsonFieldInfo field : objectModel.readFields()) {
      registerFieldAccessor(access, field.readField(), null, field.readSetter());
    }
    AnyInfo any = objectModel.anyInfo();
    if (any != null) {
      registerFieldAccessor(access, any.writeField(), any.writeGetter(), null);
      registerFieldAccessor(access, any.readField(), null, any.readSetter());
      if (any.readSetter() != null) {
        ObjectCodec.AnyInfo.anySetterHandle(any.readSetter());
      }
    }
    JsonUnwrappedInfo unwrapped = objectModel.unwrappedInfo();
    if (unwrapped != null) {
      for (JsonUnwrappedInfo.Declaration declaration : unwrapped.declarations()) {
        registerFieldAccessor(access, declaration.writeAccessor());
        registerFieldAccessor(access, declaration.readAccessor());
      }
    }
  }

  private static void registerFieldAccessor(
      DuringAnalysisAccess access, JsonFieldAccessor accessor) {
    if (accessor != null) {
      registerFieldAccessor(access, accessor.field(), accessor.getter(), accessor.setter());
    }
  }

  private static void registerFieldAccessor(
      DuringAnalysisAccess access, Field field, Method getter, Method setter) {
    if (field != null) {
      RuntimeReflection.register(field);
      JsonFieldAccessor.forField(field);
      if (Runtime.version().feature() <= 24) {
        access.registerAsUnsafeAccessed(field);
      }
    }
    if (getter != null) {
      RuntimeReflection.register(getter);
      JsonFieldAccessor.forGetter(getter);
    }
    if (setter != null) {
      RuntimeReflection.register(setter);
      JsonFieldAccessor.forSetter(setter);
    }
  }

  private void registerGeneratedClass(Class<?> generatedClass) {
    Constructor<?>[] constructors = generatedClass.getDeclaredConstructors();
    if (constructors.length == 0) {
      throw new IllegalStateException(
          "Generated Fory JSON class has no constructor: " + generatedClass.getName());
    }
    for (Constructor<?> constructor : constructors) {
      ReflectionUtils.getCtrHandle(
          constructor.getDeclaringClass(), constructor.getParameterTypes());
    }
  }

  private static IllegalStateException providerFailure(
      Class<?> providerClass, String reason, Throwable cause) {
    String message = "Invalid @ForyJsonProvider " + providerClass.getName() + ": " + reason;
    return cause == null
        ? new IllegalStateException(message)
        : new IllegalStateException(message, cause);
  }

  private boolean registerModel(DuringAnalysisAccess access, Class<?> type) {
    if (!processedModels.add(type)) {
      return false;
    }
    RuntimeReflection.register(type);
    registerContainer(type);
    registerDeclarations(type);
    JsonCodec directTypeCodec = type.getDeclaredAnnotation(JsonCodec.class);
    boolean hasTypeCodec = directTypeCodec != null || hasInheritedTypeCodec(type, null);
    boolean hasJsonValue =
        (!hasTypeCodec || isCompleteTypeCodec(directTypeCodec))
            && registerJsonValueDeclarations(access, type, null);
    JsonSubTypes subTypes = type.getDeclaredAnnotation(JsonSubTypes.class);
    boolean intrinsicType =
        type.isEnum()
            || Collection.class.isAssignableFrom(type)
            || Map.class.isAssignableFrom(type);
    if (!intrinsicType && !hasTypeCodec && !hasJsonValue && subTypes == null) {
      registerModelHierarchy(access, type);
      if (type.isRecord()) {
        registerRecord(type);
      } else if (!Modifier.isAbstract(type.getModifiers())) {
        ObjectInstantiators.getObjectInstantiator(type);
        if (GraalvmSupport.needReflectionRegisterForCreation(type)) {
          RuntimeReflection.registerForReflectiveInstantiation(type);
        }
      }
    }
    if (!hasTypeCodec && !hasJsonValue) {
      registerSubtypes(access, type);
    }
    return true;
  }

  private boolean registerMixin(
      DuringAnalysisAccess access, Class<?> mixinType, Class<?> targetType) {
    if (!processedMixins.add(mixinType)) {
      return false;
    }
    reachableMixins.computeIfAbsent(targetType, ignored -> new LinkedHashSet<>()).add(mixinType);
    JsonMixinView annotations = JsonSharedRegistry.resolveMixin(targetType, mixinType);
    RuntimeReflection.register(mixinType);
    registerReflectiveDeclarations(annotations.sourceDeclarations());
    registerReflectiveDeclarations(annotations.targetDeclarations());
    // Retain every directly declared hierarchy codec plus the exact Mixin replacement. Runtime
    // resolution remains the sole owner of codec precedence and conflict validation.
    registerDeclarations(targetType);
    JsonCodec directTypeCodec = annotations.annotation(targetType, JsonCodec.class);
    registerCodecs(directTypeCodec);
    RuntimeReflection.register(targetType);
    registerContainer(targetType);
    boolean intrinsicTarget =
        targetType.isEnum()
            || Collection.class.isAssignableFrom(targetType)
            || Map.class.isAssignableFrom(targetType);
    boolean hasDirectTypeCodec = directTypeCodec != null;
    boolean hasTypeCodec = hasDirectTypeCodec || hasInheritedTypeCodec(targetType, annotations);
    boolean hasJsonValue =
        (!hasTypeCodec || isCompleteTypeCodec(directTypeCodec))
            && registerJsonValueDeclarations(access, targetType, annotations);
    JsonSubTypes subTypes = annotation(annotations, targetType, JsonSubTypes.class);
    // Annotation-selected complete representations make ordinary object metadata unreachable.
    // Keep builder registrations and built-in codec policy runtime-owned by treating only the
    // effective annotations visible here as hosted reachability facts.
    if (!intrinsicTarget && !hasTypeCodec && !hasJsonValue && subTypes == null) {
      registerModelHierarchy(access, targetType, annotations);
      if (targetType.isRecord()) {
        registerRecord(targetType);
      } else if (!Modifier.isAbstract(targetType.getModifiers())) {
        ObjectInstantiators.getObjectInstantiator(targetType);
        if (GraalvmSupport.needReflectionRegisterForCreation(targetType)) {
          RuntimeReflection.registerForReflectiveInstantiation(targetType);
        }
      }
    }
    if (!hasTypeCodec && !hasJsonValue) {
      registerSubtypes(access, targetType, annotations);
    }
    return true;
  }

  private boolean hasInheritedTypeCodec(Class<?> type, JsonMixinView annotations) {
    Class<?> superclass = type.getSuperclass();
    if (superclass != null
        && (annotation(annotations, superclass, JsonCodec.class) != null
            || hasInheritedTypeCodec(superclass, annotations))) {
      return true;
    }
    for (Class<?> interfaceType : type.getInterfaces()) {
      if (annotation(annotations, interfaceType, JsonCodec.class) != null
          || hasInheritedTypeCodec(interfaceType, annotations)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isCompleteTypeCodec(JsonCodec annotation) {
    return annotation != null
        && annotation.value() != JsonCodec.NoJsonValueCodec.class
        && annotation.elementCodec() == JsonCodec.NoJsonValueCodec.class
        && annotation.contentCodec() == JsonCodec.NoJsonValueCodec.class
        && annotation.keyCodec() == JsonCodec.NoMapKeyCodec.class
        && annotation.valueCodec() == JsonCodec.NoJsonValueCodec.class;
  }

  private boolean registerJsonValueDeclarations(
      DuringAnalysisAccess access, Class<?> type, JsonMixinView annotations) {
    boolean hasValue = false;
    for (Class<?> current = type;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      for (Field field : current.getDeclaredFields()) {
        if (annotation(annotations, field, JsonValue.class) != null) {
          hasValue = true;
          RuntimeReflection.register(field);
          if (!field.getDeclaringClass().isRecord()) {
            JsonFieldAccessor.forField(field);
          }
          if (!field.getDeclaringClass().isRecord() && Runtime.version().feature() <= 24) {
            access.registerAsUnsafeAccessed(field);
          }
          registerOccurrenceCodecs(annotations, field);
        }
      }
    }
    for (Method method : type.getMethods()) {
      if (annotation(annotations, method, JsonValue.class) != null) {
        hasValue = true;
        RuntimeReflection.register(method);
        JsonFieldAccessor.forGetter(method);
        registerOccurrenceCodecs(annotations, method);
      }
    }
    for (Class<?> current = type;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      for (Method method : current.getDeclaredMethods()) {
        if (!Modifier.isPublic(method.getModifiers())
            && annotation(annotations, method, JsonValue.class) != null) {
          hasValue = true;
          RuntimeReflection.register(method);
          JsonFieldAccessor.forGetter(method);
          registerOccurrenceCodecs(annotations, method);
        }
      }
    }
    if (!hasValue) {
      return false;
    }
    if (type.isRecord()) {
      registerRecord(type);
    }
    for (Constructor<?> constructor : type.getDeclaredConstructors()) {
      if (annotation(annotations, constructor, JsonCreator.class) != null) {
        registerCreator(constructor);
      }
    }
    for (Method method : type.getDeclaredMethods()) {
      if (annotation(annotations, method, JsonCreator.class) != null) {
        registerCreator(method);
      }
    }
    return true;
  }

  private void registerReflectiveDeclarations(Set<AnnotatedElement> declarations) {
    for (AnnotatedElement declaration : declarations) {
      if (declaration instanceof Field) {
        RuntimeReflection.register((Field) declaration);
      } else if (declaration instanceof Method) {
        RuntimeReflection.register((Method) declaration);
      } else if (declaration instanceof Constructor<?>) {
        RuntimeReflection.register((Constructor<?>) declaration);
      } else if (declaration instanceof Parameter) {
        RuntimeReflection.register(((Parameter) declaration).getDeclaringExecutable());
      }
    }
  }

  @Override
  public void afterAnalysis(AfterAnalysisAccess access) {
    JsonNativeSubtypeRegistry.freeze();
    JsonGeneratedClassRegistry.freeze();
    JsonCodegen.resetGeneratedClassCache();
  }

  private void registerModelHierarchy(DuringAnalysisAccess access, Class<?> type) {
    registerModelHierarchy(access, type, null);
  }

  private void registerModelHierarchy(
      DuringAnalysisAccess access, Class<?> type, JsonMixinView annotations) {
    registerScalaEnumerationOwners(type);
    TypeRef<?> ownerType = TypeRef.of(type);
    boolean record = type.isRecord();
    for (Class<?> current = type;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      // Runtime configurations still select members from semantic reflection metadata. Accessor
      // construction itself is hosted and cached below.
      RuntimeReflection.register(current);
      RuntimeReflection.register(current.getDeclaredFields());
      RuntimeReflection.register(current.getDeclaredMethods());
      RuntimeReflection.register(current.getDeclaredConstructors());
      for (Field field : current.getDeclaredFields()) {
        if (isJsonField(field)) {
          if (!record) {
            JsonFieldAccessor.forField(field);
          }
          if (!current.isRecord() && Runtime.version().feature() <= 24) {
            access.registerAsUnsafeAccessed(field);
          }
          registerOccurrenceCodecs(annotations, field);
          Type resolvedType = ownerType.resolveType(field.getGenericType()).getType();
          registerResolvedType(resolvedType);
          if (annotation(annotations, field, JsonUnwrapped.class) != null) {
            registerNestedModel(access, resolvedType);
          }
        }
      }
      for (Method method : current.getDeclaredMethods()) {
        if (annotation(annotations, method, JsonValue.class) != null) {
          resolveMethodAccessors(annotations, method);
        }
      }
    }
    for (Method method : type.getMethods()) {
      boolean mixinSelector = hasMixinSelector(annotations, method);
      if (ObjectCodec.usesJsonMetadata(method, record) || mixinSelector) {
        resolveMethodAccessors(annotations, method);
        if (annotation(annotations, method, JsonValidator.class) != null
            && JsonValidatorInfo.isValidatorMethod(method)) {
          RuntimeReflection.register(method);
          JsonValidatorInfo.validatorHandle(method);
        }
        if (method.getDeclaringClass().isInterface()) {
          RuntimeReflection.register(method);
        }
        if (ObjectCodec.usesJsonReturn(method)
            || mixinSelector && method.getReturnType() != void.class) {
          registerOccurrenceCodecs(annotations, method);
          Type resolvedType = ownerType.resolveType(method.getGenericReturnType()).getType();
          registerResolvedType(resolvedType);
          if (annotation(annotations, method, JsonUnwrapped.class) != null) {
            registerNestedModel(access, resolvedType);
          }
        }
        if (ObjectCodec.usesJsonParameters(method)
            || mixinSelector && method.getParameterCount() != 0) {
          registerParameterCodecs(annotations, method.getParameters());
          registerResolvedParameterTypes(ownerType, method.getParameters());
          registerUnwrappedParameters(access, ownerType, annotations, method.getParameters());
        }
      }
    }
    for (Constructor<?> constructor : type.getDeclaredConstructors()) {
      if (annotation(annotations, constructor, JsonCreator.class) != null) {
        registerCreator(constructor);
        registerParameterCodecs(annotations, constructor.getParameters());
        registerResolvedParameterTypes(ownerType, constructor.getParameters());
        registerUnwrappedParameters(access, ownerType, annotations, constructor.getParameters());
      }
    }
    for (Method method : type.getDeclaredMethods()) {
      if (annotation(annotations, method, JsonCreator.class) != null) {
        registerCreator(method);
        registerParameterCodecs(annotations, method.getParameters());
        registerResolvedParameterTypes(ownerType, method.getParameters());
        registerUnwrappedParameters(access, ownerType, annotations, method.getParameters());
      }
    }
  }

  private void registerScalaEnumerationOwners(Class<?> type) {
    for (Class<?> current = type;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      for (Field field : current.getDeclaredFields()) {
        registerScalaEnumerationOwner(field);
      }
      for (Method method : current.getDeclaredMethods()) {
        registerScalaEnumerationOwner(method);
        for (Parameter parameter : method.getParameters()) {
          registerScalaEnumerationOwner(parameter);
        }
      }
      for (Constructor<?> constructor : current.getDeclaredConstructors()) {
        for (Parameter parameter : constructor.getParameters()) {
          registerScalaEnumerationOwner(parameter);
        }
      }
    }
  }

  private void registerScalaEnumerationOwner(AnnotatedElement element) {
    for (Annotation annotation : element.getDeclaredAnnotations()) {
      Class<? extends Annotation> annotationType = annotation.annotationType();
      if (!annotationType.getName().equals(SCALA_ENUMERATION_ANNOTATION)) {
        continue;
      }
      RuntimeReflection.register(annotationType);
      for (String slot : SCALA_ENUMERATION_SLOTS) {
        Method method;
        Class<?> owner;
        try {
          method = annotationType.getMethod(slot);
          owner = (Class<?>) method.invoke(annotation);
        } catch (ReflectiveOperationException | ClassCastException e) {
          throw new IllegalStateException("Invalid Scala JSON Enumeration annotation", e);
        }
        RuntimeReflection.register(method);
        if (owner != Void.class && scalaEnumerationOwners.add(owner)) {
          registerScalaEnumerationOwner(owner);
        }
      }
    }
  }

  private static void registerScalaEnumerationOwner(Class<?> owner) {
    RuntimeReflection.register(owner);
    Field field;
    try {
      field = owner.getField("MODULE$");
    } catch (NoSuchFieldException e) {
      throw new IllegalStateException(
          "Scala Enumeration owner has no public MODULE$ field: " + owner.getName(), e);
    }
    int modifiers = field.getModifiers();
    if (field.getType() != owner
        || !Modifier.isPublic(modifiers)
        || !Modifier.isStatic(modifiers)
        || !Modifier.isFinal(modifiers)) {
      throw new IllegalStateException(
          "Invalid Scala Enumeration owner singleton: " + owner.getName());
    }
    RuntimeReflection.register(field);
  }

  private void registerRecord(Class<?> type) {
    RuntimeReflection.registerAllRecordComponents(type);
    for (RecordComponent component : type.getRecordComponents()) {
      JsonFieldAccessor.forGetter(component.getAccessor());
    }
    Constructor<?> constructor = RecordUtils.getRecordConstructor(type).f0;
    registerCreator(constructor);
  }

  /**
   * Registers the singleton that owns a type's constructor metadata when the type does not carry
   * static forwarders for it. A Scala case class declared inside an object keeps `apply` and its
   * constructor defaults on the companion singleton, and the language module resolves that
   * singleton reflectively while rebuilding the object model at image runtime.
   */
  private void registerLanguageSingletonOwner(Class<?> type) {
    if (!languageSingletonOwners.add(type) || hasStaticFactory(type)) {
      return;
    }
    Class<?> companion;
    try {
      companion = Class.forName(type.getName() + "$", false, type.getClassLoader());
    } catch (ClassNotFoundException | LinkageError e) {
      return;
    }
    Field field;
    try {
      field = companion.getField("MODULE$");
    } catch (NoSuchFieldException e) {
      return;
    }
    int modifiers = field.getModifiers();
    if (field.getType() != companion
        || !Modifier.isPublic(companion.getModifiers())
        || !Modifier.isPublic(modifiers)
        || !Modifier.isStatic(modifiers)
        || !Modifier.isFinal(modifiers)) {
      return;
    }
    RuntimeReflection.register(companion);
    RuntimeReflection.register(field);
    for (Method method : companion.getMethods()) {
      if (method.getDeclaringClass() == companion
          && !Modifier.isStatic(method.getModifiers())
          && (method.getReturnType() == type
              || method.getName().startsWith("$lessinit$greater$default$"))
          && processedReflectiveMethods.add(method)) {
        RuntimeReflection.register(method);
      }
    }
  }

  private static boolean hasStaticFactory(Class<?> type) {
    for (Method method : type.getMethods()) {
      if (Modifier.isStatic(method.getModifiers())
          && method.getReturnType() == type
          && !method.isBridge()
          && !method.isSynthetic()
          && "apply".equals(method.getName())) {
        return true;
      }
    }
    return false;
  }

  private void registerCreator(Executable executable) {
    if (processedCreators.add(executable)) {
      RuntimeReflection.register(executable);
      JsonCreatorInfo.creatorHandle(executable);
    }
  }

  private static void resolveMethodAccessors(JsonMixinView annotations, Method method) {
    int modifiers = method.getModifiers();
    if (Modifier.isStatic(modifiers) || method.isSynthetic() || method.isBridge()) {
      return;
    }
    if (method.getParameterCount() == 0 && method.getReturnType() != void.class) {
      JsonFieldAccessor.forGetter(method);
    } else if (method.getParameterCount() == 1 && method.getReturnType() == void.class) {
      JsonFieldAccessor.forSetter(method);
    }
    if (annotation(annotations, method, JsonAnySetter.class) != null) {
      ObjectCodec.AnyInfo.anySetterHandle(method);
    }
  }

  private void registerNestedModel(DuringAnalysisAccess access, Type type) {
    Class<?> rawType = rawType(type);
    if (rawType != null && rawType != Object.class) {
      registerModel(access, rawType);
    }
  }

  private void registerUnwrappedParameters(
      DuringAnalysisAccess access,
      TypeRef<?> ownerType,
      JsonMixinView annotations,
      Parameter[] parameters) {
    for (Parameter parameter : parameters) {
      if (annotation(annotations, parameter, JsonUnwrapped.class) != null) {
        registerNestedModel(
            access, ownerType.resolveType(parameter.getParameterizedType()).getType());
      }
    }
  }

  private boolean registerDeclarations(Class<?> type) {
    if (type == null || type == Object.class || !processedDeclarations.add(type)) {
      return false;
    }
    boolean changed = false;
    JsonCodec annotation = type.getDeclaredAnnotation(JsonCodec.class);
    if (annotation != null) {
      RuntimeReflection.register(type);
      registerCodecs(annotation);
      changed = true;
    }
    changed |= registerDeclarations(type.getSuperclass());
    for (Class<?> interfaceType : type.getInterfaces()) {
      changed |= registerDeclarations(interfaceType);
    }
    return changed;
  }

  private void registerParameterCodecs(JsonMixinView annotations, Parameter[] parameters) {
    for (Parameter parameter : parameters) {
      registerCodecs(annotation(annotations, parameter, JsonCodec.class));
    }
  }

  private void registerOccurrenceCodecs(JsonMixinView annotations, AnnotatedElement element) {
    registerCodecs(annotation(annotations, element, JsonCodec.class));
    if (annotation(annotations, element, JsonBase64.class) != null) {
      registerCodec(Base64ByteArrayCodec.class);
    }
  }

  private static <A extends java.lang.annotation.Annotation> A annotation(
      JsonMixinView annotations, AnnotatedElement element, Class<A> annotationType) {
    return annotations == null
        ? element.getDeclaredAnnotation(annotationType)
        : annotations.annotation(element, annotationType);
  }

  private static boolean hasMixinSelector(JsonMixinView annotations, Executable executable) {
    if (annotations == null) {
      return false;
    }
    Set<AnnotatedElement> declarations = annotations.targetDeclarations();
    if (declarations.contains(executable)) {
      return true;
    }
    for (Parameter parameter : executable.getParameters()) {
      if (declarations.contains(parameter)) {
        return true;
      }
    }
    return false;
  }

  private void registerResolvedParameterTypes(TypeRef<?> ownerType, Parameter[] parameters) {
    for (Parameter parameter : parameters) {
      registerResolvedType(ownerType.resolveType(parameter.getParameterizedType()).getType());
    }
  }

  private void registerResolvedType(Type type) {
    Set<TypeVariable<?>> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
    registerResolvedType(type, visiting);
  }

  private void registerResolvedType(Type type, Set<TypeVariable<?>> visiting) {
    if (type == null) {
      return;
    }
    registerContainer(type);
    if (type instanceof ParameterizedType) {
      ParameterizedType parameterizedType = (ParameterizedType) type;
      registerResolvedType(parameterizedType.getOwnerType(), visiting);
      for (Type argument : parameterizedType.getActualTypeArguments()) {
        registerResolvedType(argument, visiting);
      }
    } else if (type instanceof GenericArrayType) {
      registerResolvedType(((GenericArrayType) type).getGenericComponentType(), visiting);
    } else if (type instanceof WildcardType) {
      WildcardType wildcardType = (WildcardType) type;
      registerResolvedTypes(wildcardType.getUpperBounds(), visiting);
      registerResolvedTypes(wildcardType.getLowerBounds(), visiting);
    } else if (type instanceof TypeVariable<?>) {
      TypeVariable<?> variable = (TypeVariable<?>) type;
      if (visiting.add(variable)) {
        registerResolvedTypes(variable.getBounds(), visiting);
        visiting.remove(variable);
      }
    }
  }

  private void registerResolvedTypes(Type[] types, Set<TypeVariable<?>> visiting) {
    for (Type type : types) {
      registerResolvedType(type, visiting);
    }
  }

  private void registerCodecs(JsonCodec annotation) {
    if (annotation == null) {
      return;
    }
    registerCodec(annotation.value());
    registerCodec(annotation.elementCodec());
    registerCodec(annotation.contentCodec());
    registerCodec(annotation.keyCodec());
    registerCodec(annotation.valueCodec());
  }

  private void registerCodec(Class<?> codecClass) {
    if (codecClass == JsonCodec.NoJsonValueCodec.class
        || codecClass == JsonCodec.NoMapKeyCodec.class) {
      return;
    }
    if (!processedCodecs.add(codecClass)) {
      return;
    }
    RuntimeReflection.register(codecClass);
    try {
      Constructor<?> constructor = codecClass.getConstructor();
      RuntimeReflection.register(constructor);
      ReflectionUtils.getCtrHandle(codecClass, new Class<?>[0]);
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(
          "JSON codec class must have a public no-argument constructor: " + codecClass.getName(),
          e);
    }
  }

  private boolean registerContainer(Type type) {
    Class<?> rawType = rawType(type);
    if (rawType == null
        || rawType.isInterface()
        || Modifier.isAbstract(rawType.getModifiers())
        || (!Collection.class.isAssignableFrom(rawType) && !Map.class.isAssignableFrom(rawType))
        || !processedContainers.add(rawType)) {
      return false;
    }
    registerContainerFields(rawType);
    try {
      Constructor<?> constructor = rawType.getConstructor();
      RuntimeReflection.register(constructor);
      ReflectionUtils.getCtrHandle(rawType, new Class<?>[0]);
    } catch (NoSuchMethodException ignored) {
      // Dedicated factories create supported containers such as EnumMap and EnumSet without a
      // public no-argument constructor. Other concrete containers preserve the runtime failure.
    }
    return true;
  }

  private void registerContainerFields(Class<?> type) {
    for (Class<?> current = type;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      // CollectionCodec and MapCodec derive the retained-owner estimate from the complete physical
      // field hierarchy at image runtime. Retaining only the constructor would silently reduce the
      // graph-memory charge for custom concrete containers.
      RuntimeReflection.register(current);
      RuntimeReflection.register(current.getDeclaredFields());
    }
  }

  private void registerSubtypes(DuringAnalysisAccess access, Class<?> type) {
    registerSubtypes(access, type, null);
  }

  private void registerSubtypes(
      DuringAnalysisAccess access, Class<?> type, JsonMixinView annotations) {
    JsonSubTypes subTypes = annotation(annotations, type, JsonSubTypes.class);
    if (subTypes == null) {
      return;
    }
    for (JsonSubTypes.Type entry : subTypes.value()) {
      Class<?> subtype = entry.value();
      if (subtype != Void.class) {
        registerModel(access, subtype);
      }
    }
  }

  private void registerSqlTypes(DuringAnalysisAccess access) {
    for (String className : SQL_TYPES) {
      Class<?> type = access.findClassByName(className);
      if (type != null) {
        RuntimeReflection.register(type);
        try {
          Constructor<?> constructor = type.getConstructor(long.class);
          RuntimeReflection.register(constructor);
          ReflectionUtils.getCtrHandle(type, long.class);
        } catch (NoSuchMethodException e) {
          throw new IllegalStateException("Missing Fory JSON SQL constructor for " + className, e);
        }
      }
    }
  }

  private void registerBuiltInTypes(DuringAnalysisAccess access) {
    registerSqlTypes(access);
    registerBigDecimalFields(access);
  }

  private void registerBigDecimalFields(BeforeAnalysisAccess access) {
    registerBigDecimalField(access, "intCompact", long.class);
    registerBigDecimalField(access, "intVal", BigInteger.class);
    registerBigDecimalField(access, "scale", int.class);
  }

  private void registerBigDecimalField(
      BeforeAnalysisAccess access, String fieldName, Class<?> fieldType) {
    try {
      Field field = BigDecimal.class.getDeclaredField(fieldName);
      if (field.getType() == fieldType) {
        RuntimeReflection.register(field);
        if (Runtime.version().feature() <= 24) {
          access.registerAsUnsafeAccessed(field);
        }
      }
    } catch (NoSuchFieldException ignored) {
      // BigDecimalFields preserves its public-JDK fallback if a future JDK changes this layout.
    }
  }

  private static boolean isJsonField(Field field) {
    int modifiers = field.getModifiers();
    return !Modifier.isStatic(modifiers)
        && !Modifier.isTransient(modifiers)
        && field.getType() != Class.class
        && !field.isSynthetic();
  }

  private static Class<?> rawType(Type type) {
    if (type instanceof Class<?>) {
      return (Class<?>) type;
    }
    if (type instanceof ParameterizedType) {
      Type rawType = ((ParameterizedType) type).getRawType();
      return rawType instanceof Class<?> ? (Class<?>) rawType : null;
    }
    return null;
  }

  private static boolean implementsInterface(Class<?> type, String interfaceName) {
    for (Class<?> interfaceType : type.getInterfaces()) {
      if (interfaceType.getName().equals(interfaceName)
          || implementsInterface(interfaceType, interfaceName)) {
        return true;
      }
    }
    Class<?> superType = type.getSuperclass();
    return superType != null && implementsInterface(superType, interfaceName);
  }

  private static final class HostedConfiguration {
    private final JsonSharedRegistry registry;
    private final JsonTypeResolver resolver;
    private final Map<Class<?>, Class<?>> mixins;
    private final boolean scalaJsonCodecs;
    private final Set<Class<?>> processedModels = new LinkedHashSet<>();
    private final Set<Class<?>> factoryModels = new LinkedHashSet<>();

    private HostedConfiguration(JsonConfig config) {
      registry = JsonSharedRegistry.forHostedCodegen(config);
      resolver = new JsonTypeResolver(registry);
      mixins = config.mixins();
      boolean hasScalaJsonCodecs = false;
      for (JsonCodecFactory factory : config.codecFactories()) {
        if (factory.getClass().getName().equals(SCALA_JSON_CODEC_FACTORY)) {
          hasScalaJsonCodecs = true;
          break;
        }
      }
      scalaJsonCodecs = hasScalaJsonCodecs;
    }
  }

  private static final class MethodSignature {
    private final String name;
    private final Class<?>[] parameterTypes;

    private MethodSignature(Method method) {
      name = method.getName();
      parameterTypes = method.getParameterTypes();
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof MethodSignature)) {
        return false;
      }
      MethodSignature that = (MethodSignature) other;
      return name.equals(that.name) && Arrays.equals(parameterTypes, that.parameterTypes);
    }

    @Override
    public int hashCode() {
      return 31 * name.hashCode() + Arrays.hashCode(parameterTypes);
    }
  }
}

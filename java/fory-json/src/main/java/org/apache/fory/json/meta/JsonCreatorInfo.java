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

package org.apache.fory.json.meta;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.fory.annotation.Internal;
import org.apache.fory.collection.ClassValueCache;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.GeneratedJsonCodec;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.platform.JdkVersion;
import org.apache.fory.platform.internal._JDKAccess;

/**
 * Immutable ordered construction metadata for one JSON object codec.
 *
 * <p>Record canonical constructors, property-based {@code JsonCreator} constructors, and
 * property-based {@code JsonCreator} factories share this owner. The separate complete-string
 * {@code JsonValue} representation owns its value creator in its value codec. The interpreted path
 * allocates one fixed-size construction workspace per object. Kotlin compiler defaults may add a
 * temporary exact invocation array and mask array; generated JIT readers instead consume the same
 * field metadata and executable with typed locals and primitive masks.
 */
@Internal
public final class JsonCreatorInfo {
  private static final boolean USE_NATIVE_REFLECTION =
      GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE && JdkVersion.MAJOR_VERSION >= 25;
  // The Feature resolves each discovered executable through creatorHandle during analysis, and
  // runtime configurations retrieve the retained handles through the same cache.
  private static final ClassValueCache<ConcurrentMap<Executable, CreatorHandles>> NATIVE_CREATORS =
      ClassValueCache.newClassKeyCache(32);

  private final Class<?> ownerType;
  private final Executable executable;
  private final Executable invocationExecutable;
  private final JsonCreatorFieldInfo[] fields;
  private final Object[] defaults;
  private final long[] hashes;
  private final MethodHandle invoker;
  private final GeneratedJsonCodec<?> generatedCodec;
  private final Method[] defaultMethods;
  private final MethodHandle[] defaultInvokers;
  private final Constructor<?> defaultConstructor;
  private final MethodHandle defaultConstructorInvoker;
  private final int[] defaultMaskBits;
  private final boolean[] parameterNullable;
  private final Object fixedInstance;
  private final String[] parameterNames;
  private final JsonFieldInfo[] deferredFields;
  private final boolean[] deferredRequired;
  private boolean[] nullCarriers;
  private static final Object MISSING = new Object();

  public JsonCreatorInfo(
      Class<?> ownerType,
      Executable executable,
      JsonCreatorFieldInfo[] fields,
      Object[] defaults,
      GeneratedJsonCodec<?> generatedCodec) {
    this(
        ownerType,
        executable,
        executable,
        fields,
        defaults,
        generatedCodec,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /** Creates creator metadata with compiler-mask defaults supplied by a language object model. */
  public JsonCreatorInfo(
      Class<?> ownerType,
      Executable executable,
      Executable invocationExecutable,
      JsonCreatorFieldInfo[] fields,
      Object[] defaults,
      GeneratedJsonCodec<?> generatedCodec,
      Method[] defaultMethods,
      String[] parameterNames,
      Constructor<?> defaultConstructor,
      int[] defaultMaskBits,
      boolean[] parameterNullable) {
    this(
        ownerType,
        executable,
        invocationExecutable,
        fields,
        defaults,
        generatedCodec,
        defaultMethods,
        parameterNames,
        defaultConstructor,
        defaultMaskBits,
        parameterNullable,
        null);
  }

  /** Creates a fixed-instance creator for a stateless language singleton. */
  public static JsonCreatorInfo fixedInstance(Class<?> ownerType, Object instance) {
    return new JsonCreatorInfo(
        ownerType,
        null,
        null,
        new JsonCreatorFieldInfo[0],
        new Object[0],
        null,
        null,
        null,
        null,
        null,
        null,
        instance);
  }

  /** Returns whether this creator returns a pre-existing singleton instead of allocating. */
  @Internal
  public boolean fixedInstance() {
    return fixedInstance != null;
  }

  private JsonCreatorInfo(
      Class<?> ownerType,
      Executable executable,
      Executable invocationExecutable,
      JsonCreatorFieldInfo[] fields,
      Object[] defaults,
      GeneratedJsonCodec<?> generatedCodec,
      Method[] defaultMethods,
      String[] parameterNames,
      Constructor<?> defaultConstructor,
      int[] defaultMaskBits,
      boolean[] parameterNullable,
      Object fixedInstance) {
    this.ownerType = ownerType;
    this.executable = executable;
    this.invocationExecutable = invocationExecutable;
    this.deferredFields = new JsonFieldInfo[0];
    this.deferredRequired = new boolean[0];
    this.fields = fields;
    this.defaults = defaults;
    this.generatedCodec = generatedCodec;
    this.defaultConstructor = defaultConstructor;
    this.defaultMaskBits = defaultMaskBits == null ? null : defaultMaskBits.clone();
    this.parameterNullable = parameterNullable == null ? null : parameterNullable.clone();
    this.fixedInstance = fixedInstance;
    this.parameterNames = parameterNames == null ? null : parameterNames.clone();
    this.defaultMethods = defaultMethods == null ? null : defaultMethods.clone();
    defaultInvokers =
        this.defaultMethods == null
            ? null
            : buildDefaultInvokers(ownerType, executable, this.defaultMethods);
    defaultConstructorInvoker =
        defaultConstructor == null
            ? null
            : buildInvoker(
                defaultConstructor,
                defaultConstructor.getParameterCount(),
                defaultConstructor.getParameterCount());
    invoker =
        generatedCodec == null && invocationExecutable != null
            ? buildInvoker(invocationExecutable, defaults.length, defaults.length)
            : null;
    hashes = new long[this.fields.length];
    for (int i = 0; i < this.fields.length; i++) {
      hashes[i] = this.fields[i].nameHash();
    }
  }

  private JsonCreatorInfo(
      JsonCreatorInfo source,
      JsonFieldInfo[] deferredFields,
      JsonFieldInfo[] directDeferredFields,
      boolean[] deferredRequired) {
    ownerType = source.ownerType;
    executable = source.executable;
    invocationExecutable = source.invocationExecutable;
    defaults = source.defaults;
    generatedCodec = source.generatedCodec;
    defaultMethods = source.defaultMethods;
    defaultInvokers = source.defaultInvokers;
    defaultConstructor = source.defaultConstructor;
    defaultConstructorInvoker = source.defaultConstructorInvoker;
    defaultMaskBits = source.defaultMaskBits;
    parameterNullable = source.parameterNullable;
    fixedInstance = source.fixedInstance;
    parameterNames = source.parameterNames;
    this.deferredFields = deferredFields;
    this.deferredRequired = deferredRequired;
    fields = Arrays.copyOf(source.fields, source.fields.length + directDeferredFields.length);
    for (int i = 0; i < directDeferredFields.length; i++) {
      int deferredIndex = identityIndex(deferredFields, directDeferredFields[i]);
      if (deferredIndex < 0) {
        throw new IllegalArgumentException(
            "Direct deferred field is not in the deferred field set");
      }
      fields[source.fields.length + i] =
          directDeferredFields[i].asCreatorField(defaults.length + deferredIndex);
    }
    invoker =
        generatedCodec == null
            ? buildInvoker(
                invocationExecutable, defaults.length, defaults.length + deferredFields.length)
            : null;
    hashes = new long[fields.length];
    for (int i = 0; i < fields.length; i++) {
      hashes[i] = fields[i].nameHash();
    }
  }

  /** Extends construction with deferred properties and required-presence flags. */
  public JsonCreatorInfo withDeferredFields(
      JsonFieldInfo[] fields, JsonFieldInfo[] directFields, boolean[] required) {
    if (fields.length == 0) {
      return this;
    }
    if (required.length != fields.length) {
      throw new IllegalArgumentException("Deferred JSON required flags must match fields");
    }
    if (deferredFields.length != 0) {
      throw new IllegalStateException("Deferred JSON properties are already installed");
    }
    return new JsonCreatorInfo(this, fields.clone(), directFields.clone(), required.clone());
  }

  private static int identityIndex(JsonFieldInfo[] fields, JsonFieldInfo target) {
    for (int i = 0; i < fields.length; i++) {
      if (fields[i] == target) {
        return i;
      }
    }
    return -1;
  }

  public Executable executable() {
    return executable;
  }

  /** Returns the exact full JVM invocation target selected during cold model validation. */
  @Internal
  public Executable invocationExecutable() {
    return invocationExecutable;
  }

  /** Returns the exact Kotlin compiler-default constructor, or {@code null}. */
  @Internal
  public Constructor<?> defaultConstructor() {
    return defaultConstructor;
  }

  /** Returns the compiler-default mask bit for one logical argument, or {@code -1}. */
  @Internal
  public int defaultMaskBit(int index) {
    return defaultMaskBits == null ? -1 : defaultMaskBits[index];
  }

  /** Returns the number of compiler-default mask words in the exact target descriptor. */
  @Internal
  public int defaultMaskCount() {
    return defaultConstructor == null
        ? 0
        : defaultConstructor.getParameterCount() - defaults.length - 1;
  }

  public JsonCreatorFieldInfo[] fields() {
    return fields;
  }

  /** Returns post-constructor mutable properties in construction-workspace order. */
  public JsonFieldInfo[] deferredFields() {
    return deferredFields;
  }

  /** Returns the construction-workspace slot for a deferred property. */
  public int deferredSlot(int index) {
    return defaults.length + index;
  }

  /** Returns whether one deferred property must be present before construction. */
  @Internal
  public boolean deferredRequired(int index) {
    return deferredRequired[index];
  }

  /** Returns the number of arguments passed to the constructor or factory. */
  public int argumentCount() {
    return defaults.length;
  }

  /** Returns whether construction includes post-constructor mutable properties. */
  public boolean hasDeferredFields() {
    return deferredFields.length != 0;
  }

  public Object[] newArguments() {
    Object[] arguments = Arrays.copyOf(defaults, defaults.length + deferredFields.length);
    if (defaultInvokers != null || defaultMaskBits != null || parameterNullable != null) {
      Arrays.fill(arguments, 0, defaults.length, MISSING);
    }
    if (deferredFields.length != 0) {
      Arrays.fill(arguments, defaults.length, arguments.length, MISSING);
    }
    return arguments;
  }

  public int index(long hash) {
    // Creator arity is deliberately finite and normally small. A linear exact-hash table avoids a
    // second object graph and is allocation-free.
    for (int i = 0; i < hashes.length; i++) {
      if (hashes[i] == hash) {
        return i;
      }
    }
    return -1;
  }

  public void resolveTypes(JsonTypeResolver resolver) {
    for (JsonCreatorFieldInfo field : fields) {
      field.resolveType(resolver);
      if (field.argumentIndex() < defaults.length && field.materializesNullCarrier()) {
        if (nullCarriers == null) {
          nullCarriers = new boolean[defaults.length];
        }
        nullCarriers[field.argumentIndex()] = true;
      }
    }
  }

  public Object create(Object[] arguments) {
    if (fixedInstance != null) {
      return fixedInstance;
    }
    validateLanguageArguments(arguments);
    validateDeferredArguments(arguments);
    Object value;
    if (generatedCodec != null) {
      try {
        value = requireResult(generatedCodec.newInstance(arguments));
      } catch (Throwable cause) {
        if (cause instanceof Error) {
          throw (Error) cause;
        }
        throw new ForyJsonException("JSON creator failed for " + ownerType.getName(), cause);
      }
    } else if (defaultConstructor != null) {
      value = invokeDefaultConstructor(arguments);
    } else if (invoker != null) {
      prepareArguments(arguments);
      value = invoke(arguments);
    } else {
      prepareArguments(arguments);
      value = invokeReflectiveCreator(arguments);
    }
    applyDeferred(value, arguments);
    return value;
  }

  /** Returns whether generated readers must track the presence of constructor arguments. */
  @Internal
  public boolean tracksArgumentPresence() {
    return defaultInvokers != null
        || defaultMaskBits != null
        || parameterNullable != null
        || deferredFields.length != 0;
  }

  /** Returns whether one constructor argument has a language-defined default. */
  @Internal
  public boolean hasDefault(int index) {
    return defaultInvokers != null && defaultInvokers[index] != null
        || defaultMaskBits != null && defaultMaskBits[index] >= 0;
  }

  /** Returns one prevalidated language-defined constructor default method. */
  @Internal
  public Method defaultMethod(int index) {
    return defaultMethods == null ? null : defaultMethods[index];
  }

  /** Evaluates one prevalidated language-defined constructor default. */
  @Internal
  public Object defaultValue(int index, Object[] arguments) {
    MethodHandle invoker = defaultInvokers == null ? null : defaultInvokers[index];
    if (invoker == null) {
      throw missingArgument(index);
    }
    try {
      return (Object) invoker.invokeExact(arguments);
    } catch (Throwable cause) {
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw new ForyJsonException(
          "JSON constructor default failed for " + ownerType.getName(), cause);
    }
  }

  /** Creates the missing-required-property failure outside generated common paths. */
  @Internal
  public ForyJsonException missingArgument(int index) {
    String name = parameterNames == null ? Integer.toString(index) : parameterNames[index];
    return new ForyJsonException(
        "Missing required JSON constructor property " + name + " for " + ownerType.getName());
  }

  /** Creates the missing-required-deferred-property failure outside generated common paths. */
  @Internal
  public ForyJsonException missingDeferred(int index) {
    return new ForyJsonException(
        "Missing required deferred JSON property "
            + deferredFields[index].name()
            + " for "
            + ownerType.getName());
  }

  /** Throws the cold missing-deferred failure from a generated presence branch. */
  @Internal
  public void requireDeferred(int index) {
    throw missingDeferred(index);
  }

  /** Returns whether one construction-workspace slot has not been read. */
  @Internal
  public static boolean isMissing(Object value) {
    return value == MISSING;
  }

  private void prepareArguments(Object[] arguments) {
    if (defaultInvokers == null) {
      return;
    }
    for (int i = 0; i < defaults.length; i++) {
      if (arguments[i] == MISSING) {
        arguments[i] = defaultValue(i, arguments);
      }
    }
  }

  private void validateLanguageArguments(Object[] arguments) {
    if (parameterNullable == null) {
      return;
    }
    for (int i = 0; i < defaults.length; i++) {
      Object argument = arguments[i];
      if (argument == MISSING) {
        if (!hasDefault(i)) {
          throw missingArgument(i);
        }
      } else if (argument == null && !parameterNullable[i] && !materializesNullCarrier(i)) {
        throw nullArgument(i);
      }
    }
  }

  private void validateDeferredArguments(Object[] arguments) {
    for (int i = 0; i < deferredRequired.length; i++) {
      if (deferredRequired[i] && arguments[defaults.length + i] == MISSING) {
        throw missingDeferred(i);
      }
    }
  }

  private Object invokeDefaultConstructor(Object[] arguments) {
    int parameterCount = defaults.length;
    int maskCount = defaultConstructor.getParameterCount() - parameterCount - 1;
    boolean useDefault = false;
    for (int i = 0; i < parameterCount; i++) {
      Object argument = arguments[i];
      if (argument == MISSING) {
        int bit = defaultMaskBits[i];
        if (bit < 0) {
          throw missingArgument(i);
        }
        useDefault = true;
      } else if (argument == null
          && parameterNullable != null
          && !parameterNullable[i]
          && !materializesNullCarrier(i)) {
        throw nullArgument(i);
      }
    }
    if (!useDefault) {
      return invoker == null ? invokeReflectiveCreator(arguments) : invoke(arguments);
    }
    int[] masks = new int[maskCount];
    Object[] invocation = new Object[defaultConstructor.getParameterCount()];
    for (int i = 0; i < parameterCount; i++) {
      Object argument = arguments[i];
      invocation[i] = argument == MISSING ? defaults[i] : argument;
      if (argument == MISSING) {
        int bit = defaultMaskBits[i];
        masks[bit >>> 5] |= 1 << (bit & 31);
      }
    }
    for (int i = 0; i < maskCount; i++) {
      invocation[parameterCount + i] = Integer.valueOf(masks[i]);
    }
    invocation[invocation.length - 1] = null;
    return invokeDefaultTarget(invocation);
  }

  private Object invokeDefaultTarget(Object[] arguments) {
    if (defaultConstructorInvoker != null) {
      try {
        return requireResult((Object) defaultConstructorInvoker.invokeExact(arguments));
      } catch (Throwable cause) {
        if (cause instanceof Error) {
          throw (Error) cause;
        }
        throw new ForyJsonException("JSON creator failed for " + ownerType.getName(), cause);
      }
    }
    return invokeReflectiveTarget(defaultConstructor, arguments);
  }

  private Object invokeReflectiveCreator(Object[] workspace) {
    int logicalCount = defaults.length;
    int invocationCount = invocationExecutable.getParameterCount();
    Object[] arguments = workspace;
    if (workspace.length != invocationCount || invocationCount != logicalCount) {
      // The workspace may append deferred properties, while an accessibility constructor may
      // append its language marker. Only logical creator arguments belong to the invocation.
      arguments = new Object[invocationCount];
      System.arraycopy(workspace, 0, arguments, 0, logicalCount);
    }
    return invokeReflectiveTarget(invocationExecutable, arguments);
  }

  private Object invokeReflectiveTarget(Executable target, Object[] arguments) {
    try {
      Object value =
          target instanceof Constructor
              ? ((Constructor<?>) target).newInstance(arguments)
              : ((Method) target).invoke(null, arguments);
      return requireResult(value);
    } catch (InstantiationException | IllegalAccessException e) {
      throw new ForyJsonException("Failed to invoke JSON creator for " + ownerType.getName(), e);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw new ForyJsonException("JSON creator failed for " + ownerType.getName(), cause);
    }
  }

  private ForyJsonException nullArgument(int index) {
    String name = parameterNames == null ? Integer.toString(index) : parameterNames[index];
    return new ForyJsonException(
        "JSON constructor property " + name + " is not nullable for " + ownerType.getName());
  }

  private boolean materializesNullCarrier(int index) {
    return nullCarriers != null && nullCarriers[index];
  }

  private void applyDeferred(Object value, Object[] arguments) {
    for (int i = 0; i < deferredFields.length; i++) {
      Object deferred = arguments[defaults.length + i];
      if (deferred != MISSING) {
        deferredFields[i].putValue(value, deferred);
      }
    }
  }

  private static MethodHandle[] buildDefaultInvokers(
      Class<?> ownerType, Executable executable, Method[] defaultMethods) {
    if (defaultMethods.length != executable.getParameterCount()) {
      throw new ForyJsonException("Constructor default count does not match " + executable);
    }
    MethodHandle[] invokers = new MethodHandle[defaultMethods.length];
    Class<?>[] parameterTypes = executable.getParameterTypes();
    for (int i = 0; i < defaultMethods.length; i++) {
      Method method = defaultMethods[i];
      if (method == null) {
        continue;
      }
      if ((method.getDeclaringClass() != ownerType
              || !java.lang.reflect.Modifier.isStatic(method.getModifiers()))
          || !method.getName().equals("$lessinit$greater$default$" + (i + 1))
          || method.getParameterCount() > i
          || !java.lang.reflect.Modifier.isPublic(method.getModifiers())
          || !boxed(parameterTypes[i]).isAssignableFrom(boxed(method.getReturnType()))) {
        throw new ForyJsonException("Invalid JSON constructor default method " + method);
      }
      Class<?>[] dependencyTypes = method.getParameterTypes();
      for (int j = 0; j < dependencyTypes.length; j++) {
        if (dependencyTypes[j] != parameterTypes[j]) {
          throw new ForyJsonException("Invalid JSON constructor default method " + method);
        }
      }
      try {
        MethodHandle target =
            _JDKAccess._trustedLookup(method.getDeclaringClass()).unreflect(method);
        invokers[i] = workspaceInvoker(target, dependencyTypes);
      } catch (IllegalAccessException e) {
        throw new ForyJsonException("Cannot access JSON constructor default " + method, e);
      }
    }
    return invokers;
  }

  private static Class<?> boxed(Class<?> type) {
    if (!type.isPrimitive()) {
      return type;
    }
    if (type == boolean.class) {
      return Boolean.class;
    }
    if (type == byte.class) {
      return Byte.class;
    }
    if (type == short.class) {
      return Short.class;
    }
    if (type == int.class) {
      return Integer.class;
    }
    if (type == long.class) {
      return Long.class;
    }
    if (type == float.class) {
      return Float.class;
    }
    if (type == double.class) {
      return Double.class;
    }
    return Character.class;
  }

  private Object invoke(Object[] arguments) {
    Object value;
    try {
      value = (Object) invoker.invokeExact(arguments);
    } catch (Throwable cause) {
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw new ForyJsonException("JSON creator failed for " + ownerType.getName(), cause);
    }
    return requireResult(value);
  }

  private Object requireResult(Object value) {
    if (value == null || value.getClass() != ownerType) {
      throw new ForyJsonException(
          "JSON creator must return an exact non-null " + ownerType.getName());
    }
    return value;
  }

  private static MethodHandle buildInvoker(
      Executable executable, int logicalCount, int workspaceSize) {
    if (AndroidSupport.IS_ANDROID) {
      executable.setAccessible(true);
      return null;
    }
    if (USE_NATIVE_REFLECTION) {
      try {
        // Creator shape validation guarantees a public executable; accessibility is needed only
        // when its declaring class is non-public.
        executable.setAccessible(true);
        return null;
      } catch (RuntimeException inaccessible) {
        // A named application may keep its model package unexported. Fall through to the trusted
        // creator handle retained by the Native Image Feature instead of requiring module exports.
      }
    }
    Class<?>[] parameterTypes = executable.getParameterTypes();
    if (logicalCount == parameterTypes.length && workspaceSize == parameterTypes.length) {
      return creatorHandle(executable);
    }
    MethodHandle target =
        GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE
            ? nativeCreatorHandles(executable).target
            : creatorTarget(executable);
    if (parameterTypes.length == logicalCount + 1 && !parameterTypes[logicalCount].isPrimitive()) {
      target = MethodHandles.insertArguments(target, logicalCount, new Object[] {null});
      parameterTypes = Arrays.copyOf(parameterTypes, logicalCount);
    }
    if (workspaceSize == parameterTypes.length) {
      return arrayInvoker(target, parameterTypes.length);
    }
    return workspaceInvoker(target, parameterTypes);
  }

  /** Returns the array-argument invocation handle for one JSON creator. */
  @Internal
  public static MethodHandle creatorHandle(Executable executable) {
    if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      return nativeCreatorHandles(executable).arrayInvoker;
    }
    return arrayInvoker(creatorTarget(executable), executable.getParameterCount());
  }

  private static MethodHandle arrayInvoker(MethodHandle target, int parameterCount) {
    // The interpreted reader already owns one trusted fixed-size argument array. Spread that
    // exact array into the creator without a second carrier or per-call reflective access check.
    return target
        .asSpreader(Object[].class, parameterCount)
        .asType(MethodType.methodType(Object.class, Object[].class));
  }

  private static MethodHandle workspaceInvoker(MethodHandle target, Class<?>[] parameterTypes) {
    MethodHandle elementGetter = MethodHandles.arrayElementGetter(Object[].class);
    MethodHandle[] filters = new MethodHandle[parameterTypes.length];
    for (int i = 0; i < parameterTypes.length; i++) {
      filters[i] =
          MethodHandles.insertArguments(elementGetter, 1, i)
              .asType(MethodType.methodType(parameterTypes[i], Object[].class));
    }
    MethodHandle filtered = MethodHandles.filterArguments(target, 0, filters);
    int[] reorder = new int[parameterTypes.length];
    return MethodHandles.permuteArguments(
            filtered, MethodType.methodType(target.type().returnType(), Object[].class), reorder)
        .asType(MethodType.methodType(Object.class, Object[].class));
  }

  /** Returns the one-String-argument creator used by a JsonValue representation. */
  @Internal
  public static MethodHandle stringCreatorHandle(Executable executable) {
    if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      return nativeCreatorHandles(executable).stringInvoker;
    }
    return creatorTarget(executable).asType(MethodType.methodType(Object.class, String.class));
  }

  private static CreatorHandles nativeCreatorHandles(Executable executable) {
    ConcurrentMap<Executable, CreatorHandles> creators =
        NATIVE_CREATORS.get(executable.getDeclaringClass(), ConcurrentHashMap::new);
    return creators.computeIfAbsent(executable, JsonCreatorInfo::newCreatorHandles);
  }

  private static CreatorHandles newCreatorHandles(Executable executable) {
    MethodHandle target = creatorTarget(executable);
    MethodHandle stringInvoker =
        executable.getParameterCount() == 1 && executable.getParameterTypes()[0] == String.class
            ? target.asType(MethodType.methodType(Object.class, String.class))
            : null;
    return new CreatorHandles(
        target, arrayInvoker(target, executable.getParameterCount()), stringInvoker);
  }

  private static MethodHandle creatorTarget(Executable executable) {
    Class<?> declaringClass = executable.getDeclaringClass();
    try {
      // A target-class trusted lookup has full member access without requiring the application
      // module to export or open its model package.
      return executable instanceof Constructor
          ? _JDKAccess._trustedLookup(declaringClass)
              .unreflectConstructor((Constructor<?>) executable)
          : _JDKAccess._trustedLookup(declaringClass).unreflect((Method) executable);
    } catch (IllegalAccessException e) {
      throw new ForyJsonException("Cannot access JSON creator " + executable, e);
    }
  }

  private static final class CreatorHandles {
    private final MethodHandle target;
    private final MethodHandle arrayInvoker;
    private final MethodHandle stringInvoker;

    private CreatorHandles(
        MethodHandle target, MethodHandle arrayInvoker, MethodHandle stringInvoker) {
      this.target = target;
      this.arrayInvoker = arrayInvoker;
      this.stringInvoker = stringInvoker;
    }
  }
}

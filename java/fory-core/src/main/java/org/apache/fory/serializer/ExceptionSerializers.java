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

package org.apache.fory.serializer;

import static org.apache.fory.collection.Collections.ofHashSet;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.apache.fory.builder.LayerMarkerClassGenerator;
import org.apache.fory.config.Config;
import org.apache.fory.context.MetaReadContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.exception.ForyException;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.platform.JdkVersion;
import org.apache.fory.platform.internal._JDKAccess;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.reflect.ObjectInstantiator;
import org.apache.fory.reflect.ObjectInstantiators;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.TypeResolver;

/** Serializers for {@link Throwable} and {@link StackTraceElement}. */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class ExceptionSerializers {
  private static final Set<Class<?>> THROWABLE_SUPER_CLASSES = ofHashSet(Throwable.class);

  private ExceptionSerializers() {}

  public static final class ExceptionSerializer<T extends Throwable> extends Serializer<T> {
    private final Config config;
    private final TypeResolver typeResolver;
    private final ObjectInstantiator<T> objectInstantiator;
    private final Constructor<T> messageConstructor;
    private volatile Serializer[] slotsSerializers;
    private volatile boolean rebuildSlotsSerializersAtRuntime;

    public ExceptionSerializer(TypeResolver typeResolver, Class<T> type) {
      super(typeResolver.getConfig(), type);
      this.config = typeResolver.getConfig();
      this.typeResolver = typeResolver;
      messageConstructor = getOptionalMessageConstructor(type);
      objectInstantiator =
          messageConstructor == null && MemoryUtils.JDK_LANG_FIELD_ACCESS
              ? createThrowableObjectInstantiator(typeResolver, type)
              : null;
      slotsSerializers = buildSlotsSerializers(typeResolver, type);
      if (!MemoryUtils.JDK_LANG_FIELD_ACCESS
          && isJdkThrowable(type)
          && hasSubclassFields(slotsSerializers)) {
        throw new ForyException(
            "Throwable serialization for JDK type "
                + type.getName()
                + " with subclass fields requires JDK internal field access. "
                + jdkFieldAccessMessage());
      }
      // Native-image runtime must rebuild slot serializers once so field accessors and
      // descriptors are created against the runtime heap layout instead of reusing
      // any build-time initialized state.
      rebuildSlotsSerializersAtRuntime = GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE;
    }

    @Override
    public void write(WriteContext writeContext, T value) {
      Serializer[] slotsSerializers = getSlotsSerializers();
      writeContext.writeRef(value.getStackTrace());
      writeContext.writeRef(value.getCause());
      writeContext.writeStringRef(value.getMessage());
      Throwable[] suppressedExceptions = value.getSuppressed();
      MemoryBuffer buffer = writeContext.getBuffer();
      buffer.writeVarUInt32(suppressedExceptions.length);
      for (Throwable suppressedException : suppressedExceptions) {
        writeContext.writeRef(suppressedException);
      }
      buffer.writeVarUInt32(0);
      writeNumClassLayers(buffer, slotsSerializers);
      for (Serializer slotsSerializer : slotsSerializers) {
        slotsSerializer.write(writeContext, value);
      }
    }

    @Override
    public T read(ReadContext readContext) {
      Serializer[] slotsSerializers = getSlotsSerializers();
      StackTraceElement[] stackTrace = (StackTraceElement[]) readContext.readRef();
      if (!MemoryUtils.JDK_LANG_FIELD_ACCESS) {
        return readAndroidThrowableWithoutDetailMessageField(
            readContext, stackTrace, slotsSerializers);
      }
      T obj = newThrowableForRead();
      readContext.reference(obj);
      Throwable cause = (Throwable) readContext.readRef();
      String detailMessage = readContext.readStringRef();
      List<Throwable> suppressedExceptions = readSuppressedExceptions(readContext);
      skipExtraFields(readContext);
      ThrowableAccessors.DETAIL_MESSAGE_ACCESSOR.putObject(obj, detailMessage);
      if (stackTrace != null) {
        obj.setStackTrace(stackTrace);
      }
      ThrowableAccessors.setCause(obj, cause);
      ThrowableAccessors.setSuppressedExceptions(obj, suppressedExceptions);
      readAndSetFields(readContext, obj, slotsSerializers, config);
      return obj;
    }

    private T readAndroidThrowableWithoutDetailMessageField(
        ReadContext readContext, StackTraceElement[] stackTrace, Serializer[] slotsSerializers) {
      if (messageConstructor == null) {
        throw new ForyException(
            "Deserializing Throwable type "
                + type.getName()
                + " without a String message constructor requires JDK internal field access. "
                + jdkFieldAccessMessage());
      }
      int refId = readContext.lastPreservedRefId();
      if (refId >= 0) {
        readContext.setReadRef(refId, PendingThrowable.INSTANCE);
      }
      Throwable cause = (Throwable) readContext.readRef();
      String detailMessage = readContext.readStringRef();
      List<Throwable> suppressedExceptions = readSuppressedExceptions(readContext);
      skipExtraFields(readContext);
      if (containsPendingThrowable(cause) || containsPendingThrowable(suppressedExceptions)) {
        throw new ForyException(
            "Deserializing cyclic Throwable references for type "
                + type.getName()
                + " requires JDK internal field access. "
                + jdkFieldAccessMessage());
      }
      T obj = newThrowableWithMessage(detailMessage);
      readContext.reference(obj);
      if (stackTrace != null) {
        obj.setStackTrace(stackTrace);
      }
      if (cause != null) {
        obj.initCause(cause);
      }
      addSuppressedExceptions(obj, suppressedExceptions);
      readAndSetFields(readContext, obj, slotsSerializers, config);
      return obj;
    }

    private T newThrowableForRead() {
      if (messageConstructor != null) {
        return newThrowableWithMessage(null);
      }
      if (AndroidSupport.IS_ANDROID) {
        throw new ForyException(
            "Android doesn't support deserializing Throwable type "
                + type.getName()
                + " without a String message constructor because it requires Unsafe allocation "
                + "or unsupported private-field access.");
      }
      return objectInstantiator.newInstance();
    }

    private T newThrowableWithMessage(String detailMessage) {
      try {
        return messageConstructor.newInstance(detailMessage);
      } catch (Throwable t) {
        throw new ForyException(
            "Failed to construct Throwable type "
                + type.getName()
                + " with a String message constructor.",
            t);
      }
    }

    private Serializer[] getSlotsSerializers() {
      if (!rebuildSlotsSerializersAtRuntime || !GraalvmSupport.isGraalRuntime()) {
        return slotsSerializers;
      }
      synchronized (this) {
        if (rebuildSlotsSerializersAtRuntime) {
          slotsSerializers = buildSlotsSerializers(typeResolver, type);
          rebuildSlotsSerializersAtRuntime = false;
        }
        return slotsSerializers;
      }
    }

    private void skipExtraFields(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numExtraFields = buffer.readVarUInt32();
      for (int i = 0; i < numExtraFields; i++) {
        readContext.readString();
        readContext.readRef();
      }
    }

    private void writeNumClassLayers(MemoryBuffer buffer, Serializer[] slotsSerializers) {
      buffer.writeVarUInt32Small7(slotsSerializers.length);
    }
  }

  private static String jdkFieldAccessMessage() {
    if (!AndroidSupport.IS_ANDROID && JdkVersion.MAJOR_VERSION >= 25) {
      return _JDKAccess.jdk25AccessMessage();
    }
    return "This Throwable shape is unsupported on runtimes without JDK internal field access.";
  }

  public static final class StackTraceElementSerializer extends Serializer<StackTraceElement> {
    private static final MethodHandles.Lookup LOOKUP =
        AndroidSupport.IS_ANDROID
            ? null
            : (MemoryUtils.JDK_LANG_FIELD_ACCESS
                ? _JDKAccess._trustedLookup(StackTraceElement.class)
                : MethodHandles.publicLookup());
    private static final MethodHandle CLASS_LOADER_NAME_GETTER =
        AndroidSupport.IS_ANDROID ? null : getOptionalGetter("getClassLoaderName");
    private static final MethodHandle MODULE_NAME_GETTER = getOptionalGetter("getModuleName");
    private static final MethodHandle MODULE_VERSION_GETTER = getOptionalGetter("getModuleVersion");
    private static final MethodHandle STACK_TRACE_ELEMENT_CTR_V1 =
        getOptionalCtr(String.class, String.class, String.class, int.class);
    private static final MethodHandle STACK_TRACE_ELEMENT_CTR_V2 =
        AndroidSupport.IS_ANDROID
            ? null
            : getOptionalCtr(
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                int.class);

    public StackTraceElementSerializer(Config config) {
      super(config, StackTraceElement.class, false, true);
    }

    @Override
    public void write(WriteContext writeContext, StackTraceElement value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      writeContext.writeStringRef(invokeStringGetter(CLASS_LOADER_NAME_GETTER, value));
      writeContext.writeStringRef(invokeStringGetter(MODULE_NAME_GETTER, value));
      writeContext.writeStringRef(invokeStringGetter(MODULE_VERSION_GETTER, value));
      writeContext.writeStringRef(value.getClassName());
      writeContext.writeStringRef(value.getMethodName());
      writeContext.writeStringRef(value.getFileName());
      buffer.writeInt32(value.getLineNumber());
      buffer.writeVarUInt32(0);
    }

    @Override
    public StackTraceElement read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      String classLoaderName = readContext.readStringRef();
      String moduleName = readContext.readStringRef();
      String moduleVersion = readContext.readStringRef();
      String declaringClass = readContext.readStringRef();
      String methodName = readContext.readStringRef();
      String fileName = readContext.readStringRef();
      int lineNumber = buffer.readInt32();
      int numExtraFields = buffer.readVarUInt32();
      for (int i = 0; i < numExtraFields; i++) {
        readContext.readString();
        readContext.readRef();
      }
      return newStackTraceElement(
          classLoaderName,
          moduleName,
          moduleVersion,
          declaringClass,
          methodName,
          fileName,
          lineNumber);
    }

    private static MethodHandle getOptionalGetter(String methodName) {
      if (LOOKUP == null) {
        return null;
      }
      try {
        return LOOKUP.findVirtual(
            StackTraceElement.class, methodName, MethodType.methodType(String.class));
      } catch (NoSuchMethodException e) {
        return null;
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }

    private static MethodHandle getOptionalCtr(Class<?>... parameterTypes) {
      if (LOOKUP == null) {
        return null;
      }
      try {
        return LOOKUP.findConstructor(
            StackTraceElement.class, MethodType.methodType(void.class, parameterTypes));
      } catch (NoSuchMethodException e) {
        return null;
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }

    private static String invokeStringGetter(MethodHandle getter, StackTraceElement value) {
      if (getter == null) {
        return null;
      }
      try {
        return (String) getter.invoke(value);
      } catch (Throwable t) {
        throw new RuntimeException(t);
      }
    }

    private static StackTraceElement newStackTraceElement(
        String classLoaderName,
        String moduleName,
        String moduleVersion,
        String declaringClass,
        String methodName,
        String fileName,
        int lineNumber) {
      if (AndroidSupport.IS_ANDROID) {
        return new StackTraceElement(declaringClass, methodName, fileName, lineNumber);
      }
      try {
        if (STACK_TRACE_ELEMENT_CTR_V2 != null) {
          return (StackTraceElement)
              STACK_TRACE_ELEMENT_CTR_V2.invoke(
                  classLoaderName,
                  moduleName,
                  moduleVersion,
                  declaringClass,
                  methodName,
                  fileName,
                  lineNumber);
        }
        if (STACK_TRACE_ELEMENT_CTR_V1 != null) {
          return (StackTraceElement)
              STACK_TRACE_ELEMENT_CTR_V1.invoke(declaringClass, methodName, fileName, lineNumber);
        }
        return new StackTraceElement(declaringClass, methodName, fileName, lineNumber);
      } catch (Throwable t) {
        throw new RuntimeException(t);
      }
    }
  }

  private static <T extends Throwable> ObjectInstantiator<T> createThrowableObjectInstantiator(
      TypeResolver typeResolver, Class<T> type) {
    if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE || JdkVersion.MAJOR_VERSION >= 25) {
      return typeResolver.getObjectInstantiator(type);
    }
    if (ReflectionUtils.getCtrHandle(type, false) != null) {
      return typeResolver.getObjectInstantiator(type);
    }
    return new ObjectInstantiators.ParentNoArgCtrInstantiator<>(type);
  }

  private static <T extends Throwable> Constructor<T> getOptionalMessageConstructor(Class<T> type) {
    Constructor<T> constructor;
    try {
      constructor = type.getDeclaredConstructor(String.class);
    } catch (NoSuchMethodException e) {
      return null;
    }
    try {
      constructor.setAccessible(true);
      return constructor;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static boolean isJdkThrowable(Class<?> type) {
    String name = type.getName();
    return name.startsWith("java.")
        || name.startsWith("javax.")
        || name.startsWith("jdk.")
        || name.startsWith("sun.");
  }

  private static boolean hasSubclassFields(Serializer[] slotsSerializers) {
    for (Serializer slotsSerializer : slotsSerializers) {
      if (slotsSerializer instanceof ObjectSerializer) {
        if (((ObjectSerializer) slotsSerializer).getNumFields() > 0) {
          return true;
        }
      } else if (slotsSerializer instanceof CompatibleLayerSerializerBase) {
        if (((CompatibleLayerSerializerBase) slotsSerializer).getNumFields() > 0) {
          return true;
        }
      }
    }
    return false;
  }

  private static <T> Serializer[] buildSlotsSerializers(TypeResolver typeResolver, Class<T> type) {
    Config config = typeResolver.getConfig();
    List<Serializer> serializers = new ArrayList<>();
    int layerIndex = 0;
    while (!THROWABLE_SUPER_CLASSES.contains(type)) {
      Serializer slotsSerializer;
      if (config.isCompatible()) {
        TypeDef layerTypeDef = typeResolver.getTypeDef(type, false);
        Class<?> layerMarkerClass = LayerMarkerClassGenerator.getOrCreate(type, layerIndex);
        slotsSerializer =
            new CompatibleLayerSerializer(typeResolver, type, layerTypeDef, layerMarkerClass);
      } else {
        // Throwable slot serializers populate fields on the throwable allocated by
        // ExceptionSerializer.
        slotsSerializer = new ObjectSerializer<>(typeResolver, type, false, null);
      }
      serializers.add(slotsSerializer);
      type = (Class<T>) type.getSuperclass();
      layerIndex++;
    }
    Collections.reverse(serializers);
    return serializers.toArray(new Serializer[0]);
  }

  private static void readAndSetFields(
      ReadContext readContext, Object target, Serializer[] slotsSerializers, Config config) {
    readAndCheckNumClassLayers(readContext, target.getClass(), slotsSerializers.length);
    for (Serializer slotsSerializer : slotsSerializers) {
      if (slotsSerializer instanceof CompatibleLayerSerializer) {
        CompatibleLayerSerializer compatibleSerializer =
            (CompatibleLayerSerializer) slotsSerializer;
        if (config.isMetaShareEnabled()) {
          readAndSkipLayerClassMeta(readContext);
        }
        compatibleSerializer.readAndSetFields(readContext, target);
      } else {
        ((ObjectSerializer) slotsSerializer).readAndSetFields(readContext, target);
      }
    }
  }

  private static void readAndCheckNumClassLayers(
      ReadContext readContext, Class<?> type, int expectedNumClassLayers) {
    MemoryBuffer buffer = readContext.getBuffer();
    int numClassLayers = buffer.readVarUInt32Small7();
    if (numClassLayers != expectedNumClassLayers) {
      // Layer payloads do not carry per-layer class identity here, so mismatches cannot be skipped
      // safely. Fail before consuming field payloads.
      throw new ForyException(
          "Class layer count mismatch for Throwable type "
              + type.getName()
              + ": expected "
              + expectedNumClassLayers
              + ", got "
              + numClassLayers);
    }
  }

  private static void readAndSkipLayerClassMeta(ReadContext readContext) {
    MemoryBuffer buffer = readContext.getBuffer();
    MetaReadContext metaReadContext = readContext.getMetaReadContext();
    if (metaReadContext == null) {
      return;
    }
    int indexMarker = buffer.readVarUInt32Small14();
    boolean isRef = (indexMarker & 1) == 1;
    int index = indexMarker >>> 1;
    if (isRef) {
      if (index >= metaReadContext.readTypeInfos.size) {
        throw new ForyException("Invalid layer metadata reference id " + index);
      }
      return;
    }
    long typeDefId = buffer.readInt64();
    TypeDef.skipTypeDef(buffer, typeDefId);
    metaReadContext.readTypeInfos.add(null);
  }

  private static List<Throwable> readSuppressedExceptions(ReadContext readContext) {
    MemoryBuffer buffer = readContext.getBuffer();
    int numSuppressedExceptions = buffer.readVarUInt32();
    int maxCollectionSize = readContext.getConfig().maxCollectionSize();
    if (numSuppressedExceptions < 0 || numSuppressedExceptions > maxCollectionSize) {
      throw new ForyException(
          "Throwable suppressed exception count "
              + numSuppressedExceptions
              + " exceeds max collection size "
              + maxCollectionSize);
    }
    List<Throwable> suppressedExceptions = new ArrayList<>(numSuppressedExceptions);
    for (int i = 0; i < numSuppressedExceptions; i++) {
      suppressedExceptions.add((Throwable) readContext.readRef());
    }
    return suppressedExceptions;
  }

  private static void addSuppressedExceptions(Throwable obj, List<Throwable> suppressedExceptions) {
    for (Throwable suppressedException : suppressedExceptions) {
      obj.addSuppressed(suppressedException);
    }
  }

  private static boolean containsPendingThrowable(List<Throwable> throwables) {
    for (Throwable throwable : throwables) {
      if (containsPendingThrowable(throwable)) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsPendingThrowable(Throwable throwable) {
    return containsPendingThrowable(throwable, Collections.newSetFromMap(new IdentityHashMap<>()));
  }

  private static boolean containsPendingThrowable(Throwable throwable, Set<Throwable> seen) {
    if (throwable == null) {
      return false;
    }
    if (throwable == PendingThrowable.INSTANCE) {
      return true;
    }
    if (!seen.add(throwable)) {
      return false;
    }
    if (containsPendingThrowable(throwable.getCause(), seen)) {
      return true;
    }
    for (Throwable suppressedException : throwable.getSuppressed()) {
      if (containsPendingThrowable(suppressedException, seen)) {
        return true;
      }
    }
    return false;
  }

  private static final class ThrowableAccessors {
    private static final FieldAccessor DETAIL_MESSAGE_ACCESSOR;
    private static final FieldAccessor CAUSE_ACCESSOR;
    private static final FieldAccessor SUPPRESSED_ACCESSOR;
    private static final Object DEFAULT_SUPPRESSED_EXCEPTIONS;

    static {
      try {
        Field detailMessageField = Throwable.class.getDeclaredField("detailMessage");
        DETAIL_MESSAGE_ACCESSOR = FieldAccessor.createAccessor(detailMessageField);
        Field causeField = Throwable.class.getDeclaredField("cause");
        CAUSE_ACCESSOR = FieldAccessor.createAccessor(causeField);
        Field suppressedField = Throwable.class.getDeclaredField("suppressedExceptions");
        SUPPRESSED_ACCESSOR = FieldAccessor.createAccessor(suppressedField);
        DEFAULT_SUPPRESSED_EXCEPTIONS = SUPPRESSED_ACCESSOR.getObject(new Throwable());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    private static void setCause(Throwable throwable, Throwable cause) {
      // Constructor-bypassing Throwable creation does not run Throwable field initializers.
      // Restore the sentinels directly; initCause would reject the absent cause sentinel.
      CAUSE_ACCESSOR.putObject(throwable, cause == null ? throwable : cause);
    }

    private static void setSuppressedExceptions(
        Throwable throwable, List<Throwable> suppressedExceptions) {
      SUPPRESSED_ACCESSOR.putObject(
          throwable,
          suppressedExceptions.isEmpty()
              ? DEFAULT_SUPPRESSED_EXCEPTIONS
              : new ArrayList<>(suppressedExceptions));
    }
  }

  private static final class PendingThrowable extends Throwable {
    private static final PendingThrowable INSTANCE = new PendingThrowable();

    private PendingThrowable() {
      super(null, null, false, false);
    }
  }
}

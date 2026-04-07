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
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.apache.fory.builder.LayerMarkerClassGenerator;
import org.apache.fory.config.Config;
import org.apache.fory.context.MetaReadContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.reflect.ObjectCreator;
import org.apache.fory.reflect.ObjectCreators;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.util.GraalvmSupport;
import org.apache.fory.util.unsafe._JDKAccess;

/** Serializers for {@link Throwable} and {@link StackTraceElement}. */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class ExceptionSerializers {
  private static final Set<Class<?>> THROWABLE_SUPER_CLASSES = ofHashSet(Throwable.class);

  private ExceptionSerializers() {}

  public static final class ExceptionSerializer<T extends Throwable> extends Serializer<T> {
    private final Config config;
    private final TypeResolver typeResolver;
    private final ObjectCreator<T> objectCreator;
    private volatile Serializer[] slotsSerializers;
    private volatile boolean rebuildSlotsSerializersAtRuntime;

    public ExceptionSerializer(TypeResolver typeResolver, Class<T> type) {
      super(typeResolver.getConfig(), type);
      this.config = typeResolver.getConfig();
      this.typeResolver = typeResolver;
      objectCreator = createThrowableObjectCreator(type);
      slotsSerializers = buildSlotsSerializers(typeResolver, type);
      // Native-image runtime must rebuild slot serializers once so field accessors and
      // descriptors are created against the runtime heap layout instead of reusing
      // any build-time initialized state.
      rebuildSlotsSerializersAtRuntime = GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE;
    }

    @Override
    public void write(WriteContext writeContext, T value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      Serializer[] slotsSerializers = getSlotsSerializers();
      writeContext.writeRef(value.getStackTrace());
      writeContext.writeRef(value.getCause());
      writeContext.writeStringRef(value.getMessage());
      buffer.writeVarUint32(0);
      for (Serializer slotsSerializer : slotsSerializers) {
        slotsSerializer.write(writeContext, value);
      }
    }

    @Override
    public T read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      Serializer[] slotsSerializers = getSlotsSerializers();
      T obj = objectCreator.newInstance();
      readContext.reference(obj);
      StackTraceElement[] stackTrace = (StackTraceElement[]) readContext.readRef();
      Throwable cause = (Throwable) readContext.readRef();
      String detailMessage = readContext.readStringRef();
      skipExtraFields(readContext);
      Platform.putObject(obj, ThrowableOffsets.DETAIL_MESSAGE_FIELD_OFFSET, detailMessage);
      Platform.putObject(obj, ThrowableOffsets.CAUSE_FIELD_OFFSET, cause == null ? obj : cause);
      Platform.putObject(obj, ThrowableOffsets.STACK_TRACE_FIELD_OFFSET, stackTrace);
      readAndSetFields(readContext, obj, slotsSerializers, config);
      return obj;
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
      int numExtraFields = buffer.readVarUint32();
      for (int i = 0; i < numExtraFields; i++) {
        readContext.readString();
        readContext.readRef();
      }
    }
  }

  public static final class StackTraceElementSerializer extends Serializer<StackTraceElement> {
    private static final MethodHandles.Lookup LOOKUP =
        _JDKAccess._trustedLookup(StackTraceElement.class);
    private static final MethodHandle CLASS_LOADER_NAME_GETTER =
        getOptionalGetter("getClassLoaderName");
    private static final MethodHandle MODULE_NAME_GETTER = getOptionalGetter("getModuleName");
    private static final MethodHandle MODULE_VERSION_GETTER = getOptionalGetter("getModuleVersion");
    private static final MethodHandle STACK_TRACE_ELEMENT_CTR_V1 =
        ReflectionUtils.getCtrHandle(
            StackTraceElement.class, String.class, String.class, String.class, int.class);
    private static final MethodHandle STACK_TRACE_ELEMENT_CTR_V2 =
        getOptionalCtr(
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
      buffer.writeVarUint32(0);
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
      int numExtraFields = buffer.readVarUint32();
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
        return (StackTraceElement)
            STACK_TRACE_ELEMENT_CTR_V1.invoke(declaringClass, methodName, fileName, lineNumber);
      } catch (Throwable t) {
        throw new RuntimeException(t);
      }
    }
  }

  private static <T extends Throwable> ObjectCreator<T> createThrowableObjectCreator(
      Class<T> type) {
    if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      return new ObjectCreators.UnsafeObjectCreator<>(type);
    }
    if (ReflectionUtils.getCtrHandle(type, false) != null) {
      return ObjectCreators.getObjectCreator(type);
    }
    return new ObjectCreators.ParentNoArgCtrObjectCreator<>(type);
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
            new MetaSharedLayerSerializer(typeResolver, type, layerTypeDef, layerMarkerClass);
      } else {
        slotsSerializer = new ObjectSerializer<>(typeResolver, type, false);
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
    for (Serializer slotsSerializer : slotsSerializers) {
      if (slotsSerializer instanceof MetaSharedLayerSerializer) {
        MetaSharedLayerSerializer metaSerializer = (MetaSharedLayerSerializer) slotsSerializer;
        if (config.isMetaShareEnabled()) {
          readAndSkipLayerClassMeta(readContext);
        }
        metaSerializer.readAndSetFields(readContext, target);
      } else {
        ((ObjectSerializer) slotsSerializer).readAndSetFields(readContext, target);
      }
    }
  }

  private static void readAndSkipLayerClassMeta(ReadContext readContext) {
    MemoryBuffer buffer = readContext.getBuffer();
    MetaReadContext metaReadContext = readContext.getMetaReadContext();
    if (metaReadContext == null) {
      return;
    }
    int indexMarker = buffer.readVarUint32Small14();
    boolean isRef = (indexMarker & 1) == 1;
    if (isRef) {
      return;
    }
    long typeDefId = buffer.readInt64();
    TypeDef.skipTypeDef(buffer, typeDefId);
    metaReadContext.readTypeInfos.add(null);
  }

  private static final class ThrowableOffsets {
    // Graalvm unsafe offset substitution support: Make the call followed by a field store
    // directly or by a sign extend node followed directly by a field store.
    private static final long DETAIL_MESSAGE_FIELD_OFFSET;
    private static final long CAUSE_FIELD_OFFSET;
    private static final long STACK_TRACE_FIELD_OFFSET;

    static {
      try {
        Field detailMessageField = Throwable.class.getDeclaredField("detailMessage");
        DETAIL_MESSAGE_FIELD_OFFSET = Platform.UNSAFE.objectFieldOffset(detailMessageField);
        Field causeField = Throwable.class.getDeclaredField("cause");
        CAUSE_FIELD_OFFSET = Platform.UNSAFE.objectFieldOffset(causeField);
        Field stackTraceField = Throwable.class.getDeclaredField("stackTrace");
        STACK_TRACE_FIELD_OFFSET = Platform.UNSAFE.objectFieldOffset(stackTraceField);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }
}

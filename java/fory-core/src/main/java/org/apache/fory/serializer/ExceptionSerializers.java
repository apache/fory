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
import org.apache.fory.Fory;
import org.apache.fory.builder.LayerMarkerClassGenerator;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.reflect.ObjectCreator;
import org.apache.fory.reflect.ObjectCreators;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.MetaContext;
import org.apache.fory.util.GraalvmSupport;
import org.apache.fory.util.unsafe._JDKAccess;

/** Serializers for {@link Throwable} and {@link StackTraceElement}. */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class ExceptionSerializers {
  private static final Set<Class<?>> THROWABLE_SUPER_CLASSES = ofHashSet(Throwable.class);

  private ExceptionSerializers() {}

  public static final class ExceptionSerializer<T extends Throwable> extends Serializer<T> {
    private final ObjectCreator<T> objectCreator;
    private volatile Serializer[] slotsSerializers;
    private volatile boolean rebuildSlotsSerializersAtRuntime;

    public ExceptionSerializer(Fory fory, Class<T> type) {
      super(fory, type);
      objectCreator = createThrowableObjectCreator(type);
      slotsSerializers = buildSlotsSerializers(fory, type);
      // Native-image runtime must rebuild slot serializers once so field accessors and
      // descriptors are created against the runtime heap layout instead of reusing
      // any build-time initialized state.
      rebuildSlotsSerializersAtRuntime = GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE;
    }

    @Override
    public void write(MemoryBuffer buffer, T value) {
      Serializer[] slotsSerializers = getSlotsSerializers();
      fory.writeRef(buffer, value.getStackTrace());
      fory.writeRef(buffer, value.getCause());
      fory.writeStringRef(buffer, value.getMessage());
      buffer.writeVarUint32(0);
      for (Serializer slotsSerializer : slotsSerializers) {
        slotsSerializer.write(buffer, value);
      }
    }

    @Override
    public T read(MemoryBuffer buffer) {
      Serializer[] slotsSerializers = getSlotsSerializers();
      T obj = objectCreator.newInstance();
      refResolver.reference(obj);
      StackTraceElement[] stackTrace = (StackTraceElement[]) fory.readRef(buffer);
      Throwable cause = (Throwable) fory.readRef(buffer);
      String detailMessage = fory.readStringRef(buffer);
      skipExtraFields(buffer);
      Platform.putObject(obj, ThrowableOffsets.DETAIL_MESSAGE_FIELD_OFFSET, detailMessage);
      Platform.putObject(obj, ThrowableOffsets.CAUSE_FIELD_OFFSET, cause == null ? obj : cause);
      Platform.putObject(obj, ThrowableOffsets.STACK_TRACE_FIELD_OFFSET, stackTrace);
      readAndSetFields(fory, buffer, obj, slotsSerializers);
      return obj;
    }

    private Serializer[] getSlotsSerializers() {
      if (!rebuildSlotsSerializersAtRuntime || !GraalvmSupport.isGraalRuntime()) {
        return slotsSerializers;
      }
      synchronized (this) {
        if (rebuildSlotsSerializersAtRuntime) {
          slotsSerializers = buildSlotsSerializers(fory, type);
          rebuildSlotsSerializersAtRuntime = false;
        }
        return slotsSerializers;
      }
    }

    private void skipExtraFields(MemoryBuffer buffer) {
      int numExtraFields = buffer.readVarUint32();
      for (int i = 0; i < numExtraFields; i++) {
        fory.readString(buffer);
        fory.readRef(buffer);
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

    public StackTraceElementSerializer(Fory fory) {
      super(fory, StackTraceElement.class, false, true);
    }

    @Override
    public void write(MemoryBuffer buffer, StackTraceElement value) {
      fory.writeStringRef(buffer, invokeStringGetter(CLASS_LOADER_NAME_GETTER, value));
      fory.writeStringRef(buffer, invokeStringGetter(MODULE_NAME_GETTER, value));
      fory.writeStringRef(buffer, invokeStringGetter(MODULE_VERSION_GETTER, value));
      fory.writeStringRef(buffer, value.getClassName());
      fory.writeStringRef(buffer, value.getMethodName());
      fory.writeStringRef(buffer, value.getFileName());
      buffer.writeInt32(value.getLineNumber());
      buffer.writeVarUint32(0);
    }

    @Override
    public StackTraceElement read(MemoryBuffer buffer) {
      String classLoaderName = fory.readStringRef(buffer);
      String moduleName = fory.readStringRef(buffer);
      String moduleVersion = fory.readStringRef(buffer);
      String declaringClass = fory.readStringRef(buffer);
      String methodName = fory.readStringRef(buffer);
      String fileName = fory.readStringRef(buffer);
      int lineNumber = buffer.readInt32();
      int numExtraFields = buffer.readVarUint32();
      for (int i = 0; i < numExtraFields; i++) {
        fory.readString(buffer);
        fory.readRef(buffer);
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

  private static <T> Serializer[] buildSlotsSerializers(Fory fory, Class<T> type) {
    List<Serializer> serializers = new ArrayList<>();
    int layerIndex = 0;
    while (!THROWABLE_SUPER_CLASSES.contains(type)) {
      Serializer slotsSerializer;
      if (fory.getConfig().isCompatible()) {
        TypeDef layerTypeDef = fory.getTypeResolver().getTypeDef(type, false);
        Class<?> layerMarkerClass = LayerMarkerClassGenerator.getOrCreate(fory, type, layerIndex);
        slotsSerializer = new MetaSharedLayerSerializer(fory, type, layerTypeDef, layerMarkerClass);
      } else {
        slotsSerializer = new ObjectSerializer<>(fory, type, false);
      }
      serializers.add(slotsSerializer);
      type = (Class<T>) type.getSuperclass();
      layerIndex++;
    }
    Collections.reverse(serializers);
    return serializers.toArray(new Serializer[0]);
  }

  private static void readAndSetFields(
      Fory fory, MemoryBuffer buffer, Object target, Serializer[] slotsSerializers) {
    for (Serializer slotsSerializer : slotsSerializers) {
      if (slotsSerializer instanceof MetaSharedLayerSerializer) {
        MetaSharedLayerSerializer metaSerializer = (MetaSharedLayerSerializer) slotsSerializer;
        if (fory.getConfig().isMetaShareEnabled()) {
          readAndSkipLayerClassMeta(fory, buffer);
        }
        metaSerializer.readAndSetFields(buffer, target);
      } else {
        ((ObjectSerializer) slotsSerializer).readAndSetFields(buffer, target);
      }
    }
  }

  private static void readAndSkipLayerClassMeta(Fory fory, MemoryBuffer buffer) {
    MetaContext metaContext = fory.getSerializationContext().getMetaContext();
    if (metaContext == null) {
      return;
    }
    int indexMarker = buffer.readVarUint32Small14();
    boolean isRef = (indexMarker & 1) == 1;
    if (isRef) {
      return;
    }
    long typeDefId = buffer.readInt64();
    TypeDef.skipTypeDef(buffer, typeDefId);
    metaContext.readTypeInfos.add(null);
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

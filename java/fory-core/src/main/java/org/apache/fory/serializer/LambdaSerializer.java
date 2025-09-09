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

import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.SerializedLambda;
import java.util.Objects;
import org.apache.fory.Fory;
import org.apache.fory.collection.ClassValueCache;
import org.apache.fory.exception.ForyException;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.function.SerializableFunction;
import org.apache.fory.util.unsafe._JDKAccess;

/**
 * Serializer for java serializable lambda. Use fory to serialize java lambda instead of JDK
 * serialization to avoid serialization captured values in closure using JDK, which is slow and not
 * secure(will work around type white-list).
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class LambdaSerializer extends Serializer {
  public static Class<?> STUB_LAMBDA_CLASS =
      ((SerializableFunction<Integer, Integer>) (x -> x * 2)).getClass();
  private static final Class<SerializedLambda> SERIALIZED_LAMBDA = SerializedLambda.class;
  private static final MethodHandle READ_RESOLVE_HANDLE;
  private static final boolean SERIALIZED_LAMBDA_HAS_JDK_WRITE =
      JavaSerializer.getWriteObjectMethod(SERIALIZED_LAMBDA) != null;
  private static final boolean SERIALIZED_LAMBDA_HAS_JDK_READ =
      JavaSerializer.getReadObjectMethod(SERIALIZED_LAMBDA) != null;
  private static final ClassValueCache<MethodHandle> writeReplaceMethodCache =
      ClassValueCache.newClassKeySoftCache(32);

  private final MethodHandle writeReplaceHandle;
  private Serializer dataSerializer;

  static {
    try {
      // Initialize READ_RESOLVE_HANDLE
      MethodHandles.Lookup lookup = _JDKAccess._trustedLookup(SERIALIZED_LAMBDA);
      Object readResolveMethod =
          ReflectionUtils.getObjectFieldValue(
              ObjectStreamClass.lookup(SERIALIZED_LAMBDA), "readResolveMethod");
      READ_RESOLVE_HANDLE = lookup.unreflect((java.lang.reflect.Method) readResolveMethod);
    } catch (IllegalAccessException e) {
      throw new ForyException(e);
    }
  }

  public LambdaSerializer(Fory fory, Class<?> cls) {
    super(fory, cls);
    if (cls != ReplaceStub.class) {
      if (!Serializable.class.isAssignableFrom(cls)) {
        String msg =
            String.format(
                "Lambda %s needs to implement %s for serialization",
                cls, Serializable.class.getName());
        throw new UnsupportedOperationException(msg);
      }
      MethodHandle methodHandle = writeReplaceMethodCache.getIfPresent(cls);
      if (methodHandle == null) {
        try {
          MethodHandles.Lookup lookup = _JDKAccess._trustedLookup(cls);
          Object writeReplaceMethod =
              ReflectionUtils.getObjectFieldValue(
                  ObjectStreamClass.lookup(cls), "writeReplaceMethod");
          methodHandle =
              lookup.unreflect(
                  (java.lang.reflect.Method) Objects.requireNonNull(writeReplaceMethod));
          writeReplaceMethodCache.put(cls, methodHandle);
        } catch (Throwable e) {
          throw new RuntimeException(
              String.format("Failed to create writeReplace MethodHandle for %s", cls), e);
        }
      }
      writeReplaceHandle = methodHandle;
    } else {
      writeReplaceHandle = null;
    }
    if (cls == STUB_LAMBDA_CLASS) {
      getDataSerializer();
    }
  }

  @Override
  public void write(MemoryBuffer buffer, Object value) {
    assert value.getClass() != ReplaceStub.class;
    try {
      Object replacement = writeReplaceHandle.invoke(value);
      Preconditions.checkArgument(SERIALIZED_LAMBDA.isInstance(replacement));
      getDataSerializer().write(buffer, replacement);
    } catch (Throwable e) {
      throw new RuntimeException("Can't serialize lambda " + value, e);
    }
  }

  @Override
  public Object copy(Object value) {
    try {
      Object replacement = writeReplaceHandle.invoke(value);
      Object newReplacement = getDataSerializer().copy(replacement);
      return READ_RESOLVE_HANDLE.invoke(newReplacement);
    } catch (Throwable e) {
      throw new RuntimeException("Can't copy lambda " + value, e);
    }
  }

  @Override
  public Object read(MemoryBuffer buffer) {
    try {
      Object replacement = getDataSerializer().read(buffer);
      return READ_RESOLVE_HANDLE.invoke(replacement);
    } catch (Throwable e) {
      throw new RuntimeException("Can't deserialize lambda", e);
    }
  }

  private Serializer getDataSerializer() {
    // Create serializer lazily to avoid creation cost if no lambda to be serialized.
    Serializer dataSerializer = this.dataSerializer;
    if (dataSerializer == null) {
      Class<? extends Serializer> sc;
      if (SERIALIZED_LAMBDA_HAS_JDK_WRITE || SERIALIZED_LAMBDA_HAS_JDK_READ) {
        sc = fory.getDefaultJDKStreamSerializerType();
      } else {
        sc =
            fory.getJITContext()
                .registerSerializerJITCallback(
                    () -> ObjectSerializer.class,
                    () -> CodegenSerializer.loadCodegenSerializer(fory, SERIALIZED_LAMBDA),
                    c -> {
                      this.dataSerializer = Serializers.newSerializer(fory, SERIALIZED_LAMBDA, c);
                      fory.getClassResolver().clearSerializer(SERIALIZED_LAMBDA);
                    });
      }
      this.dataSerializer = dataSerializer = Serializers.newSerializer(fory, SERIALIZED_LAMBDA, sc);
      fory.getClassResolver().clearSerializer(SERIALIZED_LAMBDA);
    }
    return dataSerializer;
  }

  /**
   * Class name of dynamic generated class is not fixed, so we use a stub class to mock dynamic
   * class.
   */
  public static class ReplaceStub {}
}

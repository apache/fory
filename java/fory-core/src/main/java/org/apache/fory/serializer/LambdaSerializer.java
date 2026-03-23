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

import java.io.Serializable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import org.apache.fory.Fory;
import org.apache.fory.collection.ClassValueCache;
import org.apache.fory.memory.MemoryBuffer;
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
  public static final Class<?> STUB_LAMBDA_CLASS = stubLambdaClass();
  private static final ClassValueCache<MethodHandle> writeReplaceMethodCache =
      ClassValueCache.newClassKeySoftCache(32);

  private final MethodHandle writeReplaceHandle;
  private final SerializedLambdaSerializer serializedLambdaSerializer;

  private static Class<?> stubLambdaClass() {
    SerializableFunction<Integer, Integer> function = x -> x * 2;
    return function.getClass();
  }

  public LambdaSerializer(Fory fory, Class<?> cls) {
    super(fory, cls);
    serializedLambdaSerializer =
        (SerializedLambdaSerializer)
            fory.getTypeResolver().getSerializer(SerializedLambdaSerializer.SERIALIZED_LAMBDA);
    if (cls == ReplaceStub.class) {
      writeReplaceHandle = null;
      return;
    }
    if (!Serializable.class.isAssignableFrom(cls)) {
      String msg =
          String.format(
              "Lambda %s needs to implement %s for serialization",
              cls, Serializable.class.getName());
      throw new UnsupportedOperationException(msg);
    }
    MethodHandle methodHandle = writeReplaceMethodCache.getIfPresent(cls);
    if (methodHandle == null) {
      methodHandle = createWriteReplaceHandle(cls);
      writeReplaceMethodCache.put(cls, methodHandle);
    }
    writeReplaceHandle = methodHandle;
  }

  @Override
  public void write(MemoryBuffer buffer, Object value) {
    serializedLambdaSerializer.write(buffer, extractSerializedLambda(value, "serialize"));
  }

  @Override
  public Object copy(Object value) {
    SerializedLambda serializedLambda = extractSerializedLambda(value, "copy");
    return SerializedLambdaSerializer.readResolve(
        serializedLambdaSerializer.copy(serializedLambda));
  }

  @Override
  public Object read(MemoryBuffer buffer) {
    try {
      return SerializedLambdaSerializer.readResolve(
          serializedLambdaSerializer.readUnresolved(buffer));
    } catch (Throwable e) {
      throw new RuntimeException("Can't deserialize lambda", e);
    }
  }

  private MethodHandle createWriteReplaceHandle(Class<?> cls) {
    try {
      Method writeReplaceMethod = JavaSerializer.getWriteReplaceMethod(cls);
      Preconditions.checkNotNull(writeReplaceMethod, "Missing writeReplace for " + cls);
      return _JDKAccess._trustedLookup(cls).unreflect(writeReplaceMethod);
    } catch (Throwable e) {
      throw new RuntimeException(
          String.format("Failed to create writeReplace MethodHandle for %s", cls), e);
    }
  }

  private SerializedLambda extractSerializedLambda(Object value, String operation) {
    Preconditions.checkState(
        writeReplaceHandle != null, "Stub lambda serializer should not be used at runtime");
    Preconditions.checkArgument(value.getClass() != ReplaceStub.class);
    try {
      Object replacement = writeReplaceHandle.invoke(value);
      Preconditions.checkArgument(
          SerializedLambdaSerializer.SERIALIZED_LAMBDA.isInstance(replacement));
      return (SerializedLambda) replacement;
    } catch (Throwable e) {
      throw new RuntimeException("Can't " + operation + " lambda " + value, e);
    }
  }

  /**
   * Class name of dynamic generated class is not fixed, so we use a stub class to mock dynamic
   * class.
   */
  public static class ReplaceStub {}
}

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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import org.apache.fory.Fory;
import org.apache.fory.exception.ForyException;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.unsafe._JDKAccess;

/**
 * Serializer for {@link SerializedLambda}. It writes the JDK lambda payload through the public
 * getter API, applies {@code readResolve} on read, and preserves unresolved {@code
 * SerializedLambda} form on direct copy.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class SerializedLambdaSerializer extends Serializer {
  static final Class<SerializedLambda> SERIALIZED_LAMBDA = SerializedLambda.class;
  private static final MethodHandle READ_RESOLVE_HANDLE;

  static {
    try {
      Method readResolveMethod = JavaSerializer.getReadResolveMethod(SERIALIZED_LAMBDA);
      Preconditions.checkNotNull(readResolveMethod, "Missing readResolve for " + SERIALIZED_LAMBDA);
      READ_RESOLVE_HANDLE =
          _JDKAccess._trustedLookup(SERIALIZED_LAMBDA).unreflect(readResolveMethod);
    } catch (IllegalAccessException e) {
      throw new ForyException(e);
    }
  }

  public SerializedLambdaSerializer(Fory fory, Class<?> cls) {
    super(fory, cls);
    Preconditions.checkArgument(cls == SERIALIZED_LAMBDA);
  }

  @Override
  public void write(MemoryBuffer buffer, Object value) {
    SerializedLambda serializedLambda = (SerializedLambda) value;
    fory.writeStringRef(buffer, serializedLambda.getCapturingClass());
    fory.writeStringRef(buffer, serializedLambda.getFunctionalInterfaceClass());
    fory.writeStringRef(buffer, serializedLambda.getFunctionalInterfaceMethodName());
    fory.writeStringRef(buffer, serializedLambda.getFunctionalInterfaceMethodSignature());
    fory.writeStringRef(buffer, serializedLambda.getImplClass());
    fory.writeStringRef(buffer, serializedLambda.getImplMethodName());
    fory.writeStringRef(buffer, serializedLambda.getImplMethodSignature());
    buffer.writeVarInt32(serializedLambda.getImplMethodKind());
    fory.writeStringRef(buffer, serializedLambda.getInstantiatedMethodType());
    int capturedArgCount = serializedLambda.getCapturedArgCount();
    buffer.writeVarUint32Small7(capturedArgCount);
    for (int i = 0; i < capturedArgCount; i++) {
      fory.writeRef(buffer, serializedLambda.getCapturedArg(i));
    }
  }

  @Override
  public Object copy(Object value) {
    SerializedLambda serializedLambda = (SerializedLambda) value;
    int capturedArgCount = serializedLambda.getCapturedArgCount();
    Object[] capturedArgs = new Object[capturedArgCount];
    for (int i = 0; i < capturedArgCount; i++) {
      capturedArgs[i] = fory.copyObject(serializedLambda.getCapturedArg(i));
    }
    return newSerializedLambda(
        serializedLambda.getCapturingClass(),
        serializedLambda.getFunctionalInterfaceClass(),
        serializedLambda.getFunctionalInterfaceMethodName(),
        serializedLambda.getFunctionalInterfaceMethodSignature(),
        serializedLambda.getImplMethodKind(),
        serializedLambda.getImplClass(),
        serializedLambda.getImplMethodName(),
        serializedLambda.getImplMethodSignature(),
        serializedLambda.getInstantiatedMethodType(),
        capturedArgs);
  }

  @Override
  public Object read(MemoryBuffer buffer) {
    return readResolve(readUnresolved(buffer));
  }

  Object readUnresolved(MemoryBuffer buffer) {
    String capturingClass = fory.readStringRef(buffer);
    String functionalInterfaceClass = fory.readStringRef(buffer);
    String functionalInterfaceMethodName = fory.readStringRef(buffer);
    String functionalInterfaceMethodSignature = fory.readStringRef(buffer);
    String implClass = fory.readStringRef(buffer);
    String implMethodName = fory.readStringRef(buffer);
    String implMethodSignature = fory.readStringRef(buffer);
    int implMethodKind = buffer.readVarInt32();
    String instantiatedMethodType = fory.readStringRef(buffer);
    int capturedArgCount = buffer.readVarUint32Small7();
    Object[] capturedArgs = new Object[capturedArgCount];
    for (int i = 0; i < capturedArgCount; i++) {
      capturedArgs[i] = fory.readRef(buffer);
    }
    return newSerializedLambda(
        capturingClass,
        functionalInterfaceClass,
        functionalInterfaceMethodName,
        functionalInterfaceMethodSignature,
        implMethodKind,
        implClass,
        implMethodName,
        implMethodSignature,
        instantiatedMethodType,
        capturedArgs);
  }

  static Object readResolve(Object replacement) {
    try {
      return READ_RESOLVE_HANDLE.invoke(replacement);
    } catch (Throwable e) {
      throw new RuntimeException("Can't deserialize lambda", e);
    }
  }

  private SerializedLambda newSerializedLambda(
      String capturingClass,
      String functionalInterfaceClass,
      String functionalInterfaceMethodName,
      String functionalInterfaceMethodSignature,
      int implMethodKind,
      String implClass,
      String implMethodName,
      String implMethodSignature,
      String instantiatedMethodType,
      Object[] capturedArgs) {
    return new SerializedLambda(
        loadCapturingClass(capturingClass),
        functionalInterfaceClass,
        functionalInterfaceMethodName,
        functionalInterfaceMethodSignature,
        implMethodKind,
        implClass,
        implMethodName,
        implMethodSignature,
        instantiatedMethodType,
        capturedArgs);
  }

  private Class<?> loadCapturingClass(String className) {
    String binaryClassName = className.replace('/', '.');
    try {
      return Class.forName(binaryClassName, false, fory.getClassLoader());
    } catch (ClassNotFoundException e) {
      try {
        return Class.forName(
            binaryClassName, false, Thread.currentThread().getContextClassLoader());
      } catch (ClassNotFoundException ex) {
        throw new RuntimeException("Can't load capturing class " + binaryClassName, ex);
      }
    }
  }
}

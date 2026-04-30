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
import org.apache.fory.context.CopyContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.exception.ForyException;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.TypeResolver;
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
  private final TypeResolver typeResolver;

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

  public SerializedLambdaSerializer(TypeResolver typeResolver, Class<?> cls) {
    super(typeResolver.getConfig(), cls);
    this.typeResolver = typeResolver;
    Preconditions.checkArgument(cls == SERIALIZED_LAMBDA);
  }

  @Override
  public void write(WriteContext writeContext, Object value) {
    MemoryBuffer buffer = writeContext.getBuffer();
    SerializedLambda serializedLambda = (SerializedLambda) value;
    writeContext.writeStringRef(serializedLambda.getCapturingClass());
    writeContext.writeStringRef(serializedLambda.getFunctionalInterfaceClass());
    writeContext.writeStringRef(serializedLambda.getFunctionalInterfaceMethodName());
    writeContext.writeStringRef(serializedLambda.getFunctionalInterfaceMethodSignature());
    writeContext.writeStringRef(serializedLambda.getImplClass());
    writeContext.writeStringRef(serializedLambda.getImplMethodName());
    writeContext.writeStringRef(serializedLambda.getImplMethodSignature());
    buffer.writeVarInt32(serializedLambda.getImplMethodKind());
    writeContext.writeStringRef(serializedLambda.getInstantiatedMethodType());
    int capturedArgCount = serializedLambda.getCapturedArgCount();
    buffer.writeVarUInt32Small7(capturedArgCount);
    for (int i = 0; i < capturedArgCount; i++) {
      writeContext.writeRef(serializedLambda.getCapturedArg(i));
    }
  }

  @Override
  public Object copy(CopyContext copyContext, Object value) {
    SerializedLambda serializedLambda = (SerializedLambda) value;
    int capturedArgCount = serializedLambda.getCapturedArgCount();
    Object[] capturedArgs = new Object[capturedArgCount];
    for (int i = 0; i < capturedArgCount; i++) {
      capturedArgs[i] = copyContext.copyObject(serializedLambda.getCapturedArg(i));
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
  public Object read(ReadContext readContext) {
    return readResolve(readUnresolved(readContext));
  }

  Object readUnresolved(ReadContext readContext) {
    MemoryBuffer buffer = readContext.getBuffer();
    String capturingClass = readContext.readStringRef();
    String functionalInterfaceClass = readContext.readStringRef();
    String functionalInterfaceMethodName = readContext.readStringRef();
    String functionalInterfaceMethodSignature = readContext.readStringRef();
    String implClass = readContext.readStringRef();
    String implMethodName = readContext.readStringRef();
    String implMethodSignature = readContext.readStringRef();
    int implMethodKind = buffer.readVarInt32();
    String instantiatedMethodType = readContext.readStringRef();
    int capturedArgCount = buffer.readVarUInt32Small7();
    Object[] capturedArgs = new Object[capturedArgCount];
    for (int i = 0; i < capturedArgCount; i++) {
      capturedArgs[i] = readContext.readRef();
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
      return Class.forName(binaryClassName, false, typeResolver.getClassLoader());
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

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

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.apache.fory.context.CopyContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.memory.Platform;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.util.GraalvmSupport;
import org.apache.fory.util.Preconditions;

/** Serializer for jdk {@link Proxy}. */
@SuppressWarnings({"rawtypes", "unchecked"})
public class JdkProxySerializer extends Serializer {
  // Make offset compatible with graalvm native image.
  private static final Field FIELD;
  private static final long PROXY_HANDLER_FIELD_OFFSET;

  static {
    FIELD = ReflectionUtils.getField(Proxy.class, InvocationHandler.class);
    PROXY_HANDLER_FIELD_OFFSET = Platform.objectFieldOffset(FIELD);
  }

  private static class StubInvocationHandler implements InvocationHandler {
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      throw new IllegalStateException("Deserialization stub handler still active");
    }
  }

  private static final InvocationHandler STUB_HANDLER = new StubInvocationHandler();

  private interface StubInterface {
    int apply();
  }

  public static Object SUBT_PROXY =
      Proxy.newProxyInstance(
          Serializer.class.getClassLoader(), new Class[] {StubInterface.class}, STUB_HANDLER);

  private final TypeResolver typeResolver;

  public JdkProxySerializer(TypeResolver typeResolver, Class cls) {
    super(typeResolver.getConfig(), cls);
    this.typeResolver = typeResolver;
    if (cls != ReplaceStub.class) {
      // Skip proxy class validation in GraalVM native image runtime to avoid issues with proxy
      // detection
      if (!GraalvmSupport.isGraalRuntime()) {
        Preconditions.checkArgument(ReflectionUtils.isJdkProxy(cls), "Require a jdk proxy class");
      }
    }
  }

  @Override
  public void write(WriteContext writeContext, Object value) {
    writeContext.writeRef(value.getClass().getInterfaces());
    writeContext.writeRef(Proxy.getInvocationHandler(value));
  }

  @Override
  public Object copy(CopyContext copyContext, Object value) {
    Class<?>[] interfaces = value.getClass().getInterfaces();
    InvocationHandler invocationHandler = Proxy.getInvocationHandler(value);
    Preconditions.checkNotNull(interfaces);
    Preconditions.checkNotNull(invocationHandler);
    Object proxy = Proxy.newProxyInstance(typeResolver.getClassLoader(), interfaces, STUB_HANDLER);
    copyContext.reference(value, proxy);
    Platform.putObject(
        proxy, PROXY_HANDLER_FIELD_OFFSET, copyContext.copyObject(invocationHandler));
    return proxy;
  }

  @Override
  public Object read(ReadContext readContext) {
    final int refId = readContext.lastPreservedRefId();
    final Class<?>[] interfaces = (Class<?>[]) readContext.readRef();
    Preconditions.checkNotNull(interfaces);
    Object proxy = Proxy.newProxyInstance(typeResolver.getClassLoader(), interfaces, STUB_HANDLER);
    readContext.setReadRef(refId, proxy);
    InvocationHandler invocationHandler = (InvocationHandler) readContext.readRef();
    Preconditions.checkNotNull(invocationHandler);
    Platform.putObject(proxy, PROXY_HANDLER_FIELD_OFFSET, invocationHandler);
    return proxy;
  }

  public static class ReplaceStub {}
}

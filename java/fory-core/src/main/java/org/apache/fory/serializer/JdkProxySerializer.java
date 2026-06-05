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
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.util.Preconditions;

/** Serializer for jdk {@link Proxy}. */
@SuppressWarnings({"rawtypes", "unchecked"})
public class JdkProxySerializer extends Serializer {
  private static class StubInvocationHandler implements InvocationHandler {
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      throw new IllegalStateException("Deserialization stub handler still active");
    }
  }

  private static final InvocationHandler STUB_HANDLER = new StubInvocationHandler();

  private static final class DeferredInvocationHandler implements InvocationHandler {
    private volatile InvocationHandler delegate;

    void setDelegate(InvocationHandler delegate) {
      if (delegate == null) {
        throw new NullPointerException("delegate cannot be null");
      }
      this.delegate = unwrapInvocationHandler(delegate);
    }

    InvocationHandler getDelegate() {
      InvocationHandler handler = delegate;
      if (handler == null) {
        throw new IllegalStateException(
            "Proxy handler not yet initialized. "
                + "Cannot call methods on proxy during deserialization or logging. "
                + "On Android, proxy must not be used as Map/Set key or printed before handler is ready.");
      }
      return handler;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      return getDelegate().invoke(proxy, method, args);
    }
  }

  private static final class ProxyHandlerField {
    private static final Field FIELD =
        ReflectionUtils.getField(Proxy.class, InvocationHandler.class);
    private static final FieldAccessor ACCESSOR = FieldAccessor.createAccessor(FIELD);
  }

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
    writeContext.writeRef(unwrapInvocationHandler(Proxy.getInvocationHandler(value)));
  }

  @Override
  public Object copy(CopyContext copyContext, Object value) {
    Class<?>[] interfaces = value.getClass().getInterfaces();
    InvocationHandler invocationHandler =
        unwrapInvocationHandler(Proxy.getInvocationHandler(value));
    Preconditions.checkNotNull(interfaces);
    if (!copyContext.copyTrackingRef()) {
      InvocationHandler copyHandler = copyContext.copyObject(invocationHandler);
      Preconditions.checkNotNull(copyHandler);
      return Proxy.newProxyInstance(typeResolver.getClassLoader(), interfaces, copyHandler);
    }
    if (!MemoryUtils.JDK_PROXY_FIELD_ACCESS) {
      DeferredInvocationHandler deferredHandler = new DeferredInvocationHandler();
      Object proxy =
          Proxy.newProxyInstance(typeResolver.getClassLoader(), interfaces, deferredHandler);
      copyContext.reference(value, proxy);
      InvocationHandler copyHandler = copyContext.copyObject(invocationHandler);
      Preconditions.checkNotNull(copyHandler);
      deferredHandler.setDelegate(copyHandler);
      return proxy;
    }
    Object proxy = Proxy.newProxyInstance(typeResolver.getClassLoader(), interfaces, STUB_HANDLER);
    copyContext.reference(value, proxy);
    ProxyHandlerField.ACCESSOR.putObject(proxy, copyContext.copyObject(invocationHandler));
    return proxy;
  }

  @Override
  public Object read(ReadContext readContext) {
    final int refId = needToWriteRef ? readContext.lastPreservedRefId() : -1;
    final Class<?>[] interfaces = (Class<?>[]) readContext.readRef();
    Preconditions.checkNotNull(interfaces);
    if (!needToWriteRef) {
      InvocationHandler invocationHandler =
          unwrapInvocationHandler((InvocationHandler) readContext.readRef());
      return Proxy.newProxyInstance(typeResolver.getClassLoader(), interfaces, invocationHandler);
    }
    if (!MemoryUtils.JDK_PROXY_FIELD_ACCESS) {
      DeferredInvocationHandler deferredHandler = new DeferredInvocationHandler();
      Object proxy =
          Proxy.newProxyInstance(typeResolver.getClassLoader(), interfaces, deferredHandler);
      readContext.setReadRef(refId, proxy);
      InvocationHandler invocationHandler =
          unwrapInvocationHandler((InvocationHandler) readContext.readRef());
      deferredHandler.setDelegate(invocationHandler);
      return proxy;
    }
    Object proxy = Proxy.newProxyInstance(typeResolver.getClassLoader(), interfaces, STUB_HANDLER);
    readContext.setReadRef(refId, proxy);
    InvocationHandler invocationHandler =
        unwrapInvocationHandler((InvocationHandler) readContext.readRef());
    ProxyHandlerField.ACCESSOR.putObject(proxy, invocationHandler);
    return proxy;
  }

  private static InvocationHandler unwrapInvocationHandler(InvocationHandler invocationHandler) {
    Preconditions.checkNotNull(invocationHandler);
    while (invocationHandler instanceof DeferredInvocationHandler) {
      invocationHandler = ((DeferredInvocationHandler) invocationHandler).getDelegate();
    }
    return invocationHandler;
  }

  public static class ReplaceStub {}
}

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
import org.apache.fory.Fory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.RefResolver;
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

  public JdkProxySerializer(Fory fory, Class cls) {
    super(fory, cls);
    if (cls != ReplaceStub.class) {
      Preconditions.checkArgument(ReflectionUtils.isJdkProxy(cls), "Require a jdk proxy class");
    }
  }

  @Override
  public void write(MemoryBuffer buffer, Object value) {
    fory.writeRef(buffer, value.getClass().getInterfaces());
    fory.writeRef(buffer, Proxy.getInvocationHandler(value));
  }

  @Override
  public Object copy(Object value) {
    Class<?>[] interfaces = value.getClass().getInterfaces();
    InvocationHandler invocationHandler = Proxy.getInvocationHandler(value);
    Preconditions.checkNotNull(interfaces);
    Preconditions.checkNotNull(invocationHandler);
    Object proxy = Proxy.newProxyInstance(fory.getClassLoader(), interfaces, STUB_HANDLER);
    if (needToCopyRef) {
      fory.reference(value, proxy);
    }
    Platform.putObject(proxy, PROXY_HANDLER_FIELD_OFFSET, fory.copyObject(invocationHandler));
    return proxy;
  }

  @Override
  public Object read(MemoryBuffer buffer) {
    final RefResolver resolver = fory.getRefResolver();
    final int refId = resolver.lastPreservedRefId();
    final Class<?>[] interfaces = (Class<?>[]) fory.readRef(buffer);
    Preconditions.checkNotNull(interfaces);
    Object proxy = Proxy.newProxyInstance(fory.getClassLoader(), interfaces, STUB_HANDLER);
    resolver.setReadObject(refId, proxy);
    InvocationHandler invocationHandler = (InvocationHandler) fory.readRef(buffer);
    Preconditions.checkNotNull(invocationHandler);
    Platform.putObject(proxy, PROXY_HANDLER_FIELD_OFFSET, invocationHandler);
    return proxy;
  }

  public static class ReplaceStub {}
}

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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertTrue;

import java.io.ObjectStreamException;
import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Function;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.reflect.ReflectionUtils;
import org.testng.annotations.Test;

@SuppressWarnings({"unchecked", "rawtypes"})
public class JdkProxySerializerTest extends ForyTestBase {

  private static class TestInvocationHandler implements InvocationHandler, Serializable {

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      return 1;
    }
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testJdkProxy(boolean referenceTracking) {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(referenceTracking)
            .requireClassRegistration(false)
            .build();
    Function function =
        (Function)
            Proxy.newProxyInstance(
                fory.getClassLoader(), new Class[] {Function.class}, new TestInvocationHandler());
    Function deserializedFunction = (Function) fory.deserialize(fory.serialize(function));
    assertEquals(deserializedFunction.apply(null), 1);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testJdkProxy(Fory fory) {
    Function function =
        (Function)
            Proxy.newProxyInstance(
                fory.getClassLoader(), new Class[] {Function.class}, new TestInvocationHandler());
    Function copy = fory.copy(function);
    assertNotSame(copy, function);
    assertEquals(copy.apply(null), 1);
  }

  private static class RefTestInvocationHandler implements InvocationHandler, Serializable {

    private Function proxy;

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if (method.getName().equals("equals")) {
        return args[0] == this.proxy;
      }
      return "Hello world from "
          + (proxy == null
              ? "null"
              : proxy.getClass().getName() + "@" + System.identityHashCode(proxy));
    }

    private void setProxy(Function myProxy) {
      this.proxy = myProxy;
    }

    private Function getProxy() {
      return proxy;
    }
  }

  @Test
  public void testJdkProxyRef() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();
    RefTestInvocationHandler hdlr = new RefTestInvocationHandler();
    Function function =
        (Function)
            Proxy.newProxyInstance(fory.getClassLoader(), new Class[] {Function.class}, hdlr);
    hdlr.setProxy(function);
    assertEquals(hdlr.getProxy(), function);

    Function deserializedFunction = (Function) fory.deserialize(fory.serialize(function));
    RefTestInvocationHandler deserializedHandler =
        (RefTestInvocationHandler) Proxy.getInvocationHandler(deserializedFunction);
    assertEquals(deserializedHandler.getProxy(), deserializedFunction);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testJdkProxyRef(Fory fory) {
    RefTestInvocationHandler hdlr = new RefTestInvocationHandler();
    Function function =
        (Function)
            Proxy.newProxyInstance(fory.getClassLoader(), new Class[] {Function.class}, hdlr);
    hdlr.setProxy(function);
    assertEquals(hdlr.getProxy(), function);

    Function copy = fory.copy(function);
    RefTestInvocationHandler copyHandler =
        (RefTestInvocationHandler) Proxy.getInvocationHandler(copy);
    assertEquals(copyHandler.getProxy(), copy);
  }

  @Test
  public void testSerializeProxyWriteReplace() {
    final Fory fory = Fory.builder().withXlang(false).requireClassRegistration(false).build();

    final Object o = ProxyFactory.createProxy(TestInterface.class);
    final byte[] s = fory.serialize(o);
    assertTrue(ReflectionUtils.isJdkProxy(fory.deserialize(s).getClass()));
  }

  interface TestInterface {
    void test();
  }

  static class ProxyFactory {

    static <T> T createProxy(final Class<T> type) {
      return new JdkProxyFactory().createProxy(type);
    }

    public interface IWriteReplace {
      Object writeReplace() throws ObjectStreamException;
    }

    static final class JdkProxyFactory {

      @SuppressWarnings("unchecked")
      <T> T createProxy(final Class<T> type) {
        final JdkHandler handler = new JdkHandler(type);
        try {
          final ClassLoader cl = Thread.currentThread().getContextClassLoader();
          return (T)
              Proxy.newProxyInstance(
                  cl, new Class[] {type, IWriteReplace.class, Serializable.class}, handler);
        } catch (IllegalArgumentException e) {
          throw new RuntimeException("Could not create proxy for type [" + type.getName() + "]", e);
        }
      }

      static class JdkHandler implements InvocationHandler, IWriteReplace, Serializable {

        private final String typeName;

        private JdkHandler(Class<?> type) {
          typeName = type.getName();
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
          if (isWriteReplaceMethod(method)) {
            return writeReplace();
          }
          return null;
        }

        public Object writeReplace() throws ObjectStreamException {
          return new ProxyReplacement(typeName);
        }

        static boolean isWriteReplaceMethod(final Method method) {
          return (method.getReturnType() == Object.class)
              && (method.getParameterTypes().length == 0)
              && method.getName().equals("writeReplace");
        }
      }

      public static final class ProxyReplacement implements Serializable {

        private final String type;

        public ProxyReplacement(final String type) {
          this.type = type;
        }

        private Object readResolve() throws ObjectStreamException {
          try {
            final Class<?> clazz =
                Class.forName(type, false, Thread.currentThread().getContextClassLoader());
            return ProxyFactory.createProxy(clazz);
          } catch (ClassNotFoundException ex) {
            throw new RuntimeException(ex);
          }
        }
      }
    }
  }
}

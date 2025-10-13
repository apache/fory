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

package org.apache.fory.graalvm;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.apache.fory.Fory;
import org.apache.fory.config.Language;
import org.apache.fory.resolver.TypeResolver;

public class FeatureTestExample {

  public static class PrivateConstructorClass {
    private String value;

    private PrivateConstructorClass() {
      // Private constructor
    }

    public PrivateConstructorClass(String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }

    public void setValue(String value) {
      this.value = value;
    }
  }

  // Test interface for proxy
  public interface TestInterface {
    String getValue();

    void setValue(String value);
  }

  // Simple invocation handler
  public static class TestInvocationHandler implements InvocationHandler {
    private String value;

    public TestInvocationHandler(String value) {
      this.value = value;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if ("getValue".equals(method.getName())) {
        return value;
      } else if ("setValue".equals(method.getName())) {
        value = (String) args[0];
        return null;
      }
      return null;
    }
  }

  public static void main(String[] args) {
    System.out.println("Testing Fory GraalVM Feature...");

    Fory fory =
        Fory.builder().withLanguage(Language.JAVA).withRefTracking(true).withCodegen(false).build();

    fory.register(PrivateConstructorClass.class);
    fory.register(TestInvocationHandler.class);

    // Register proxy interface
    TypeResolver.addProxyInterface(TestInterface.class);

    try {
      // Test 1: Serialize/deserialize class with private constructor
      PrivateConstructorClass original = new PrivateConstructorClass("test-value");
      byte[] serialized = fory.serialize(original);
      PrivateConstructorClass deserialized = (PrivateConstructorClass) fory.deserialize(serialized);

      if (!"test-value".equals(deserialized.getValue())) {
        throw new RuntimeException("Private constructor class test failed");
      }
      System.out.println("Private constructor class serialization test passed");

      // Test 2: Serialize/deserialize proxy object
      TestInterface proxy =
          (TestInterface)
              Proxy.newProxyInstance(
                  TestInterface.class.getClassLoader(),
                  new Class[] {TestInterface.class},
                  new TestInvocationHandler("proxy-value"));

      byte[] proxySerialised = fory.serialize(proxy);
      TestInterface deserializedProxy = (TestInterface) fory.deserialize(proxySerialised);

      if (!"proxy-value".equals(deserializedProxy.getValue())) {
        throw new RuntimeException("Proxy test failed");
      }
      System.out.println("Proxy serialization test passed");

      System.out.println("All GraalVM Feature tests passed!");

    } catch (Exception e) {
      System.err.println("GraalVM Feature test failed: " + e.getMessage());
      e.printStackTrace();
      System.exit(1);
    }
  }
}

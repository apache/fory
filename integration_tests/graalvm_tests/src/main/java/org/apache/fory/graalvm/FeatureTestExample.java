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
import org.apache.fory.builder.Generated;
import org.apache.fory.util.GraalvmSupport;
import org.apache.fory.util.Preconditions;

public class FeatureTestExample {

  public static class PrivateConstructorClass {
    private String value;

    private PrivateConstructorClass() {}

    public PrivateConstructorClass(String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }
  }

  public interface TestInterface {
    String getValue();
  }

  public static class TestInvocationHandler implements InvocationHandler {
    private final String value;

    public TestInvocationHandler(String value) {
      this.value = value;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      if ("getValue".equals(method.getName())) {
        return value;
      }
      return null;
    }
  }

  static Fory fory;

  static {
    fory = createFory();
  }

  private static Fory createFory() {
    Fory fory =
        Fory.builder()
            .withName(FeatureTestExample.class.getName())
            .requireClassRegistration(true)
            .build();
    fory.register(PrivateConstructorClass.class);
    fory.register(TestInvocationHandler.class);
    GraalvmSupport.registerProxySupport(TestInterface.class);
    fory.ensureSerializersCompiled();
    return fory;
  }

  public static void main(String[] args) {
    System.out.println("Testing Fory GraalVM Feature...");

    // Test class with private constructor
    PrivateConstructorClass original = new PrivateConstructorClass("test-value");
    PrivateConstructorClass deserialized =
        (PrivateConstructorClass) fory.deserialize(fory.serialize(original));
    Preconditions.checkArgument(
        fory.getTypeResolver().getSerializer(PrivateConstructorClass.class) instanceof Generated);
    Preconditions.checkArgument("test-value".equals(deserialized.getValue()));
    System.out.println("Private constructor class test passed");

    // Test proxy serialization
    TestInterface proxy =
        (TestInterface)
            Proxy.newProxyInstance(
                TestInterface.class.getClassLoader(),
                new Class[] {TestInterface.class},
                new TestInvocationHandler("proxy-value"));
    TestInterface deserializedProxy = (TestInterface) fory.deserialize(fory.serialize(proxy));
    Preconditions.checkArgument("proxy-value".equals(deserializedProxy.getValue()));
    System.out.println("Proxy serialization test passed");

    System.out.println("FeatureTestExample succeed");
  }
}

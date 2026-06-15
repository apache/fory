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

package org.apache.fory.platform.internal;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.fory.platform.JdkVersion;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

public class JDKAccessTest {

  private Object func1() {
    return this;
  }

  private void func2() {}

  private void func3(int x) {}

  private void func4(Object x) {}

  @Test
  public void testMakeJDKFunction() throws NoSuchMethodException, IllegalAccessException {
    JDKAccessTest accessTest = new JDKAccessTest();
    MethodHandles.Lookup lookup = _JDKAccess._trustedLookup(JDKAccessTest.class);
    {
      Function<Object, Object> func1 =
          _JDKAccess.makeJDKFunction(
              lookup,
              lookup.findVirtual(
                  JDKAccessTest.class, "func1", MethodType.methodType(Object.class)));
      Assert.assertSame(func1.apply(accessTest), accessTest);
    }
    {
      Consumer<Object> func =
          _JDKAccess.makeJDKConsumer(
              lookup,
              lookup.findVirtual(JDKAccessTest.class, "func2", MethodType.methodType(void.class)));
      func.accept(accessTest);
    }
    {
      BiConsumer<Object, Object> func =
          _JDKAccess.makeJDKBiConsumer(
              lookup,
              lookup.findVirtual(
                  JDKAccessTest.class, "func3", MethodType.methodType(void.class, int.class)));
      func.accept(accessTest, 1);
    }
    {
      BiConsumer<Object, Object> func =
          _JDKAccess.makeJDKBiConsumer(
              lookup,
              lookup.findVirtual(
                  JDKAccessTest.class, "func4", MethodType.methodType(void.class, Object.class)));
      func.accept(accessTest, 1);
    }
  }

  @Test
  public void testMakeJDKConsumer() {}

  @Test
  public void testMakeJDKBiConsumer() {}

  public interface JDK11StringCtr {
    String apply(byte[] data, byte coder);
  }

  @Test
  public void testMakeFunctionFailed() throws NoSuchMethodException, IllegalAccessException {
    if (JdkVersion.MAJOR_VERSION != 11) {
      throw new SkipException("Skip on jdk" + JdkVersion.MAJOR_VERSION);
    }
    MethodHandles.Lookup lookup = _JDKAccess._trustedLookup(String.class);
    MethodHandle handle =
        lookup.findConstructor(
            String.class, MethodType.methodType(void.class, byte[].class, byte.class));
    // JDK11StringCtr not exist in JDK bootstrap classloader.
    Assert.assertThrows(
        NoClassDefFoundError.class,
        () -> _JDKAccess.makeFunction(lookup, handle, JDK11StringCtr.class));
  }

  static class A {
    private int add(String x, int y) {
      return Integer.parseInt(x) + y;
    }
  }

  interface Add1 {
    int add(A a, String x, int y);
  }

  interface Add2 {
    int apply(A a, String x, int y);

    default int otherMethod() {
      return 1;
    }
  }

  @Test
  public void testMakeFunction() throws Exception {
    MethodHandles.Lookup lookup = _JDKAccess._trustedLookup(A.class);
    MethodType methodType = MethodType.methodType(int.class, String.class, int.class);
    MethodHandle handle = lookup.findVirtual(A.class, "add", methodType);
    Assert.assertEquals(
        _JDKAccess.makeFunction(lookup, handle, Add1.class).add(new A(), "1", 1), 2);
    Assert.assertEquals(
        _JDKAccess.makeFunction(lookup, handle, Add2.class).apply(new A(), "1", 1), 2);
  }
}

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

package org.apache.fory.util;

import java.io.StringReader;
import org.apache.fory.Fory;
import org.apache.fory.codegen.CompileUnit;
import org.apache.fory.codegen.JaninoUtils;
import org.codehaus.janino.SimpleCompiler;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class ClassLoaderUtilsTest {

  @Test
  public void testClassloader() throws Exception {
    String classname = String.format("A%d", System.currentTimeMillis());
    String classCode =
        String.format(
            ""
                + "package demo.pkg1;\n"
                + "public final class %s implements java.io.Serializable {\n"
                + "  public int f1;\n"
                + "  public long f2;\n"
                + "}",
            classname);
    SimpleCompiler compiler = new SimpleCompiler();
    compiler.setParentClassLoader(Fory.class.getClassLoader().getParent());
    compiler.cook(new StringReader(classCode));
    ClassLoader classLoader = compiler.getClassLoader();
    Class<?> clz = classLoader.loadClass("demo.pkg1." + classname);
    Fory fory = Fory.builder().withXlang(false).requireClassRegistration(false).build();
    Thread.currentThread().setContextClassLoader(classLoader);
    byte[] bytes = fory.serialize(clz.newInstance());
    fory.deserialize(bytes);
    Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
  }

  @DataProvider(name = "packages")
  public static Object[][] packages() {
    return new Object[][] {{"demo.pkg1"}, {ClassLoaderUtils.class.getPackage().getName()}};
  }

  @Test(dataProvider = "packages")
  public void testTryDefineClassesInClassLoader(String pkg) {
    String classname = String.format("A%d", System.currentTimeMillis());
    String classCode =
        String.format(
            ""
                + "package %s;\n"
                + "public final class %s implements java.io.Serializable {\n"
                + "  public int f1;\n"
                + "  public long f2;\n"
                + "}",
            pkg, classname);
    byte[] bytes =
        JaninoUtils.toBytecode(
                getClass().getClassLoader(), new CompileUnit(pkg, classname, classCode))
            .values()
            .iterator()
            .next();
    if (ClassLoaderUtils.class.getPackage().getName().equals(pkg)) {
      Class<?> cls =
          ClassLoaderUtils.tryDefineClassesInClassLoader(
              pkg + "." + classname,
              ClassLoaderUtils.class,
              ClassLoaderUtils.class.getClassLoader(),
              bytes);
      Assert.assertNotNull(cls);
      Assert.assertEquals(cls.getSimpleName(), classname);
    } else {
      Class<?> cls =
          ClassLoaderUtils.tryDefineClassesInClassLoader(
              pkg + "." + classname, null, getClass().getClassLoader(), bytes);
      Assert.assertNotNull(cls);
    }
  }
}

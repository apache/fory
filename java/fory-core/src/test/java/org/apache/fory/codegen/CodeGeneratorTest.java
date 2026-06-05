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

package org.apache.fory.codegen;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.fory.Fory;
import org.apache.fory.builder.ObjectCodecBuilder;
import org.apache.fory.collection.MultiKeyWeakMap;
import org.apache.fory.test.bean.Foo;
import org.apache.fory.util.ClassLoaderUtils;
import org.apache.fory.util.ClassLoaderUtils.ByteArrayClassLoader;
import org.apache.fory.util.ClassLoaderUtils.ForyJarClassLoader;
import org.apache.fory.util.DelayedRef;
import org.testng.Assert;
import org.testng.annotations.Test;

@SuppressWarnings({"rawtypes", "unchecked"})
public class CodeGeneratorTest {
  private static WeakHashMap<ClassLoader, DelayedRef<CodeGenerator>> sharedCodeGenerator;
  private static MultiKeyWeakMap<DelayedRef<CodeGenerator>> sharedCodeGenerator2;

  static {
    try {
      Field field1 = CodeGenerator.class.getDeclaredField("sharedCodeGenerator");
      field1.setAccessible(true);
      sharedCodeGenerator = (WeakHashMap) field1.get(null);
      Field field2 = CodeGenerator.class.getDeclaredField("sharedCodeGenerator2");
      field2.setAccessible(true);
      sharedCodeGenerator2 = (MultiKeyWeakMap) field2.get(null);
    } catch (IllegalAccessException | NoSuchFieldException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void testGetSharedCodeGenerator() {
    CodeGenerator.getSharedCodeGenerator(getClass().getClassLoader());
    System.gc();
    assertNotNull(sharedCodeGenerator.get(getClass().getClassLoader()).get());
    CodeGenerator.getSharedCodeGenerator(getClass().getClassLoader(), Fory.class.getClassLoader());
    System.gc();
    assertNotNull(
        sharedCodeGenerator2
            .get(new Object[] {getClass().getClassLoader(), Fory.class.getClassLoader()})
            .get());
    CodeGenerator.getSharedCodeGenerator(
        ForyJarClassLoader.getInstance(), getClass().getClassLoader());
    System.gc();
    assertNotNull(
        sharedCodeGenerator2
            .get(new Object[] {ForyJarClassLoader.getInstance(), getClass().getClassLoader()})
            .get());
  }

  @Test
  public void tryDuplicateCompileConcurrent() throws InterruptedException {
    ExecutorService executorService = Executors.newFixedThreadPool(10);
    ByteArrayClassLoader classLoader = new ByteArrayClassLoader(new HashMap<>());
    AtomicBoolean hasException = new AtomicBoolean(false);
    AtomicReference<ClassLoader> prevLoader = new AtomicReference<>();
    for (int i = 0; i < 1000; i++) {
      executorService.execute(
          () -> {
            try {
              ClassLoader newLoader = tryDuplicateCompile(classLoader);
              if (prevLoader.get() != null) {
                Assert.assertSame(newLoader, prevLoader.get());
                prevLoader.set(newLoader);
              }
            } catch (Exception e) {
              hasException.set(true);
            }
          });
    }
    executorService.shutdown();
    assertTrue(executorService.awaitTermination(30, TimeUnit.SECONDS));
    assertFalse(hasException.get());
  }

  @Test
  public void tryDuplicateCompile() {
    tryDuplicateCompile(new ByteArrayClassLoader(new HashMap<>()));
  }

  public ClassLoader tryDuplicateCompile(ClassLoader loader) {
    CodeGenerator codeGenerator = CodeGenerator.getSharedCodeGenerator(loader);
    ObjectCodecBuilder codecBuilder =
        new ObjectCodecBuilder(
            Foo.class,
            Fory.builder()
                .withXlang(false)
                .requireClassRegistration(false)
                .withCompatible(false)
                .build());
    CompileUnit compileUnit =
        new CompileUnit(
            Foo.class.getPackage().getName(),
            codecBuilder.codecClassName(Foo.class),
            codecBuilder::genCode);
    ClassLoader loader1 = codeGenerator.compile(compileUnit);
    ClassLoader loader2 = codeGenerator.compile(compileUnit);
    Assert.assertSame(loader1, loader2);
    return loader1;
  }

  @Test
  public void tryDefineClassesInClassLoader() {
    ByteArrayClassLoader loader = new ByteArrayClassLoader(new HashMap<>());
    ObjectCodecBuilder codecBuilder =
        new ObjectCodecBuilder(
            Foo.class,
            Fory.builder()
                .withXlang(false)
                .requireClassRegistration(false)
                .withCompatible(false)
                .build());
    CompileUnit compileUnit =
        new CompileUnit(
            Foo.class.getPackage().getName(),
            codecBuilder.codecClassName(Foo.class),
            codecBuilder::genCode);
    Map<String, byte[]> byteCodeMap = JaninoUtils.toBytecode(loader, compileUnit);
    byte[] byteCodes = byteCodeMap.get(CodeGenerator.classFilepath(compileUnit));
    Assert.assertNotNull(
        ClassLoaderUtils.tryDefineClassesInClassLoader(
            CodeGenerator.fullClassName(compileUnit), null, loader, byteCodes));
    Assert.assertNull(
        ClassLoaderUtils.tryDefineClassesInClassLoader(
            CodeGenerator.fullClassName(compileUnit), null, loader, byteCodes));
  }

  @Test
  public void classFilepath() {
    String p =
        CodeGenerator.classFilepath(
            new CompileUnit(Foo.class.getPackage().getName(), Foo.class.getSimpleName(), ""));
    Assert.assertEquals(
        p,
        String.format(
            "%s/%s.class",
            Foo.class.getPackage().getName().replace(".", "/"), Foo.class.getSimpleName()));
  }

  @Test
  public void fullClassName() {
    CompileUnit unit =
        new CompileUnit(Foo.class.getPackage().getName(), Foo.class.getSimpleName(), "");
    Assert.assertEquals(CodeGenerator.fullClassName(unit), Foo.class.getName());
  }

  @Test
  public void testCompile() throws Exception {
    CodeGenerator codeGenerator = CodeGenerator.getSharedCodeGenerator(getClass().getClassLoader());
    CompileUnit unit1 =
        new CompileUnit(
            "demo.pkg1",
            "A",
            (""
                + "package demo.pkg1;\n"
                + "public class A {\n"
                + "  public static String hello() { return \"HELLO\"; }\n"
                + "}"));
    ClassLoader classLoader = codeGenerator.compile(unit1);
    Assert.assertEquals(classLoader.loadClass("demo.pkg1.A").getSimpleName(), "A");
    Assert.assertNotEquals(classLoader, getClass().getClassLoader());
    Assert.assertEquals(classLoader.getClass(), ClassLoaderUtils.ByteArrayClassLoader.class);
  }

  @Test
  public void testMultiCompile() throws Exception {
    CodeGenerator codeGenerator = new CodeGenerator(getClass().getClassLoader());
    CompileUnit unit1 =
        new CompileUnit(
            "demo.pkg1",
            "A",
            (""
                + "package demo.pkg1;\n"
                + "import demo.pkg2.*;\n"
                + "public class A {\n"
                + "  public static String main() { return B.hello(); }\n"
                + "  public static String hello() { return \"HELLO\"; }\n"
                + "}"));
    CompileUnit unit2 =
        new CompileUnit(
            "demo.pkg2",
            "B",
            (""
                + "package demo.pkg2;\n"
                + "import demo.pkg1.*;\n"
                + "public class B {\n"
                + "  public static String hello() { return A.hello(); }\n"
                + "}"));
    ClassLoader classLoader = codeGenerator.compile(unit1, unit2);
    Assert.assertEquals(
        "HELLO", classLoader.loadClass("demo.pkg1.A").getMethod("main").invoke(null));
    ClassLoader classLoader2 = codeGenerator.compile(unit1, unit2);
    Assert.assertSame(classLoader, classLoader2);
  }
}

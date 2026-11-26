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
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Field;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.apache.fory.Fory;
import org.apache.fory.codegen.CompileUnit;
import org.apache.fory.codegen.JaninoUtils;
import org.apache.fory.util.ClassLoaderUtils.ByteArrayClassLoader;
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
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    Thread.currentThread().setContextClassLoader(classLoader);
    byte[] bytes = fory.serialize(clz.newInstance());
    fory.deserialize(bytes);
    Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
  }

  @Test
  public void testClassLoadingWaitsForCompleteBatch() throws Exception {
    ByteArrayClassLoader loader =
        new ByteArrayClassLoader(Collections.emptyMap(), getClass().getClassLoader());
    CompileUnit parent =
        new CompileUnit(
            "generated.concurrent",
            "Parent",
            "package generated.concurrent; public class Parent {}");
    CompileUnit child =
        new CompileUnit(
            "generated.concurrent",
            "Child",
            "package generated.concurrent; public class Child extends Parent {}");
    Map<String, byte[]> bytecodes = JaninoUtils.toBytecode(loader, parent, child);
    Map<String, byte[]> ordered = new LinkedHashMap<>();
    ordered.put(
        "generated/concurrent/Child.class", bytecodes.get("generated/concurrent/Child.class"));
    ordered.put(
        "generated/concurrent/Parent.class", bytecodes.get("generated/concurrent/Parent.class"));
    CountDownLatch childAdded = new CountDownLatch(1);
    CountDownLatch allowParent = new CountDownLatch(1);
    Map<String, byte[]> pausedBatch =
        new AbstractMap<String, byte[]>() {
          @Override
          public Set<Entry<String, byte[]>> entrySet() {
            return new AbstractSet<Entry<String, byte[]>>() {
              @Override
              public int size() {
                return ordered.size();
              }

              @Override
              public Iterator<Entry<String, byte[]>> iterator() {
                Iterator<Entry<String, byte[]>> entries = ordered.entrySet().iterator();
                return new Iterator<Entry<String, byte[]>>() {
                  int index;

                  @Override
                  public boolean hasNext() {
                    return entries.hasNext();
                  }

                  @Override
                  public Entry<String, byte[]> next() {
                    if (index++ == 1) {
                      childAdded.countDown();
                      try {
                        Assert.assertTrue(allowParent.await(10, TimeUnit.SECONDS));
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                      }
                    }
                    return entries.next();
                  }
                };
              }
            };
          }
        };
    Field classes = ByteArrayClassLoader.class.getDeclaredField("classes");
    classes.setAccessible(true);
    int monitor = System.identityHashCode(classes.get(loader));
    FutureTask<Void> register =
        new FutureTask<>(
            () -> {
              loader.addClasses(pausedBatch);
              return null;
            });
    FutureTask<Class<?>> load =
        new FutureTask<>(() -> loader.loadClass(child.getQualifiedClassName()));
    Thread writer = new Thread(register, "register-bytecode"),
        reader = new Thread(load, "load-bytecode");
    writer.setDaemon(true);
    reader.setDaemon(true);
    try {
      writer.start();
      Assert.assertTrue(childAdded.await(10, TimeUnit.SECONDS));
      reader.start();
      boolean blocked = false;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (System.nanoTime() < deadline && !load.isDone()) {
        ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(reader.getId());
        if (info != null
            && info.getThreadState() == Thread.State.BLOCKED
            && info.getLockInfo() != null
            && info.getLockInfo().getIdentityHashCode() == monitor) {
          blocked = true;
          break;
        }
        Thread.yield();
      }
      Assert.assertTrue(blocked, "Class loading must wait for the complete registered batch");
      allowParent.countDown();
      register.get(10, TimeUnit.SECONDS);
      Assert.assertSame(
          load.get(10, TimeUnit.SECONDS).getSuperclass(),
          loader.loadClass(parent.getQualifiedClassName()));
    } finally {
      allowParent.countDown();
      register.cancel(true);
      load.cancel(true);
      writer.interrupt();
      reader.interrupt();
      writer.join(2000);
      reader.join(2000);
      Assert.assertFalse(writer.isAlive() || reader.isAlive());
    }
  }

  @Test
  public void testAddClassesPreservesRegisteredBytecode() throws Exception {
    ByteArrayClassLoader loader =
        new ByteArrayClassLoader(Collections.emptyMap(), getClass().getClassLoader());
    String prefix =
        "package generated.loader; public class Value { public static int value() { return ";
    Map<String, byte[]> first =
        JaninoUtils.toBytecode(
            loader, new CompileUnit("generated.loader", "Value", prefix + "1; } }"));
    Map<String, byte[]> replacement =
        JaninoUtils.toBytecode(
            loader, new CompileUnit("generated.loader", "Value", prefix + "2; } }"));
    loader.addClasses(first);
    loader.addClasses(replacement);
    Class<?> value = loader.loadClass("generated.loader.Value");
    Assert.assertSame(value.getClassLoader(), loader);
    Assert.assertEquals(value.getMethod("value").invoke(null), Integer.valueOf(1));
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

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

package org.apache.fory.reflect;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.apache.fory.TestUtils;
import org.apache.fory.exception.ForyException;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.reflect.ObjectInstantiators.ReflectionFactoryInstantiator;
import org.testng.Assert;
import org.testng.annotations.Test;

@SuppressWarnings("rawtypes")
public class ObjectInstantiatorsTest {

  static class NoCtrTestClass {
    int f1;

    public NoCtrTestClass(int f1) {
      this.f1 = f1;
    }
  }

  static class NonSerializableParentWithoutNoArg {
    static int constructorCalls;
    int parentValue;

    NonSerializableParentWithoutNoArg(int parentValue) {
      constructorCalls++;
      this.parentValue = parentValue;
    }
  }

  static class NonSerializableChildWithoutNoArg extends NonSerializableParentWithoutNoArg {
    int childValue;

    NonSerializableChildWithoutNoArg(int parentValue, int childValue) {
      super(parentValue);
      this.childValue = childValue;
    }
  }

  @Test
  public void testObjectInstantiator() {
    ReflectionFactoryInstantiator<ArrayBlockingQueue> instantiator =
        new ReflectionFactoryInstantiator<>(ArrayBlockingQueue.class);
    Assert.assertEquals(instantiator.newInstance().getClass(), ArrayBlockingQueue.class);
    Assert.assertEquals(
        new ReflectionFactoryInstantiator<>(NoCtrTestClass.class).newInstance().getClass(),
        NoCtrTestClass.class);
  }

  @Test
  public void testNonSerializableInstantiator() {
    NonSerializableParentWithoutNoArg.constructorCalls = 0;
    ReflectionFactoryInstantiator<NonSerializableChildWithoutNoArg> instantiator =
        new ReflectionFactoryInstantiator<>(NonSerializableChildWithoutNoArg.class);
    NonSerializableChildWithoutNoArg instance = instantiator.newInstance();
    Assert.assertEquals(instance.getClass(), NonSerializableChildWithoutNoArg.class);
    Assert.assertEquals(NonSerializableParentWithoutNoArg.constructorCalls, 0);
    Assert.assertEquals(instance.parentValue, 0);
    Assert.assertEquals(instance.childValue, 0);
  }

  @Test
  public void testAndroidObjectInstantiators() throws Exception {
    ProcessBuilder builder =
        new ProcessBuilder(TestUtils.javaCommand(AndroidObjectInstantiatorProbe.class));
    builder.environment().put("FORY_ANDROID_ENABLED", "0");
    Process process = builder.redirectErrorStream(true).start();
    String output = readFully(process.getInputStream());
    Assert.assertEquals(process.waitFor(), 0, output);
  }

  private static String readFully(InputStream inputStream) throws IOException {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    int read;
    while ((read = inputStream.read(buffer)) != -1) {
      outputStream.write(buffer, 0, read);
    }
    return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
  }

  public static final class AndroidObjectInstantiatorProbe {
    public static void main(String[] args) {
      System.setProperty("java.vm.name", "Dalvik");
      System.setProperty("java.runtime.name", "Android Runtime");
      check(AndroidSupport.IS_ANDROID, "AndroidSupport should detect Dalvik runtime");

      ObjectInstantiator<AndroidPrivateNoArg> instantiator =
          ObjectInstantiators.getObjectInstantiator(AndroidPrivateNoArg.class);
      AndroidPrivateNoArg instance = instantiator.newInstance();
      check(instance.value == 7, "Android constructor should initialize fields");

      ObjectInstantiator<AndroidNoNoArg> unsupported =
          ObjectInstantiators.getObjectInstantiator(AndroidNoNoArg.class);
      try {
        unsupported.newInstance();
        throw new AssertionError("Android instantiator without no-arg constructor should fail");
      } catch (ForyException expected) {
        check(
            expected.getMessage().contains("without an accessible no-arg constructor"),
            "Unexpected message: " + expected.getMessage());
      }

      verifyRecordConstruction();
    }

    private static void verifyRecordConstruction() {
      int feature = javaFeature();
      JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
      if (feature < 16 || compiler == null) {
        return;
      }
      Path directory = null;
      try {
        directory = Files.createTempDirectory("fory-android-record");
        Path source = directory.resolve("AndroidProbeRecord.java");
        Files.write(
            source,
            ("public record AndroidProbeRecord(int id, String name) {}")
                .getBytes(StandardCharsets.UTF_8));
        int result =
            compiler.run(
                null,
                null,
                null,
                "--release",
                Integer.toString(feature),
                "-d",
                directory.toString(),
                source.toString());
        check(result == 0, "Failed to compile Android record probe");
        try (URLClassLoader loader =
            new URLClassLoader(
                new URL[] {directory.toUri().toURL()},
                AndroidObjectInstantiatorProbe.class.getClassLoader())) {
          Class<?> recordType = Class.forName("AndroidProbeRecord", true, loader);
          ObjectInstantiator<?> recordInstantiator =
              ObjectInstantiators.getObjectInstantiator(recordType);
          Object record = recordInstantiator.newInstanceWithArguments(11, "record");
          FieldAccessor idAccessor =
              FieldAccessor.createAccessor(
                  recordType.getDeclaredField("id"), FieldAccessor.READ_ACCESS);
          FieldAccessor nameAccessor =
              FieldAccessor.createAccessor(
                  recordType.getDeclaredField("name"), FieldAccessor.READ_ACCESS);
          check(idAccessor.getInt(record) == 11, "Android record int component");
          check("record".equals(nameAccessor.getObject(record)), "Android record object component");
        }
      } catch (Exception e) {
        throw new AssertionError("Android record construction failed", e);
      } finally {
        deleteTree(directory);
      }
    }

    private static int javaFeature() {
      String version = System.getProperty("java.specification.version", "8");
      int dot = version.indexOf('.');
      return Integer.parseInt(dot < 0 ? version : version.substring(dot + 1));
    }

    private static void deleteTree(Path directory) {
      if (directory == null) {
        return;
      }
      try (Stream<Path> paths = Files.walk(directory)) {
        paths
            .sorted(Comparator.reverseOrder())
            .forEach(
                path -> {
                  try {
                    Files.delete(path);
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                });
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    private static void check(boolean value, String message) {
      if (!value) {
        throw new AssertionError(message);
      }
    }
  }

  private static final class AndroidPrivateNoArg {
    private final int value = 7;

    private AndroidPrivateNoArg() {}
  }

  private static final class AndroidNoNoArg {
    @SuppressWarnings("unused")
    private final int value;

    private AndroidNoNoArg(int value) {
      this.value = value;
    }
  }
}

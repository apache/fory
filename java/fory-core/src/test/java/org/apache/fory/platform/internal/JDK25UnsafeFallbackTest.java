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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.fory.Fory;
import org.apache.fory.TestUtils;
import org.apache.fory.platform.JdkVersion;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

public class JDK25UnsafeFallbackTest {

  @Test
  public void testStringFieldUnsafeFallback() throws Exception {
    if (JdkVersion.MAJOR_VERSION != 25) {
      throw new SkipException("Skip on jdk" + JdkVersion.MAJOR_VERSION);
    }
    runFallbackProbe(StringFieldFallbackProbe.class);
  }

  @Test
  public void testPrivateFieldUnsafeFallback() throws Exception {
    if (JdkVersion.MAJOR_VERSION != 25) {
      throw new SkipException("Skip on jdk" + JdkVersion.MAJOR_VERSION);
    }
    runFallbackProbe(PrivateFieldFallbackProbe.class);
  }

  @Test
  public void testForyRoundTripUnsafeFallback() throws Exception {
    if (JdkVersion.MAJOR_VERSION != 25) {
      throw new SkipException("Skip on jdk" + JdkVersion.MAJOR_VERSION);
    }
    runFallbackProbe(ForyRoundTripFallbackProbe.class);
  }

  private static void runFallbackProbe(Class<?> mainClass) throws Exception {
    List<String> command = fallbackCommand(mainClass);
    for (String commandPart : command) {
      Assert.assertFalse(commandPart.contains("java.base/java.lang.invoke"), command.toString());
      Assert.assertFalse(commandPart.contains("sun-misc-unsafe-memory-access=deny"));
    }
    ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
    processBuilder.environment().remove("JDK_JAVA_OPTIONS");
    processBuilder.environment().remove("JAVA_TOOL_OPTIONS");
    processBuilder.environment().remove("_JAVA_OPTIONS");
    Process process = processBuilder.start();
    String output = readFully(process.getInputStream());
    Assert.assertEquals(process.waitFor(), 0, output);
  }

  private static List<String> fallbackCommand(Class<?> mainClass) {
    ArrayList<String> command = new ArrayList<>();
    command.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
    command.add("-cp");
    command.add(TestUtils.forkClassPath());
    command.add(mainClass.getName());
    return command;
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

  private static void assertInvokeClosed(Class<?> probeClass) throws ReflectiveOperationException {
    Object javaBaseModule = Class.class.getMethod("getModule").invoke(MethodHandles.Lookup.class);
    Object probeModule = Class.class.getMethod("getModule").invoke(probeClass);
    Class<?> moduleClass = Class.forName("java.lang.Module");
    boolean invokeOpen =
        (boolean)
            moduleClass
                .getMethod("isOpen", String.class, moduleClass)
                .invoke(javaBaseModule, "java.lang.invoke", probeModule);
    if (invokeOpen) {
      throw new AssertionError("java.lang.invoke should not be open in this probe");
    }
  }

  public static final class StringFieldFallbackProbe {
    public static void main(String[] args) throws Throwable {
      assertInvokeClosed(StringFieldFallbackProbe.class);
      MethodHandles.Lookup stringLookup = _Lookup._trustedLookup(String.class);
      MethodHandle valueGetter = stringLookup.findGetter(String.class, "value", byte[].class);
      byte[] value = (byte[]) valueGetter.invoke("trusted");
      if (value.length == 0) {
        throw new AssertionError("String value field was not read");
      }
    }
  }

  public static final class PrivateFieldFallbackProbe {
    public static void main(String[] args) throws Throwable {
      assertInvokeClosed(PrivateFieldFallbackProbe.class);
      PrivateFieldTarget target = new PrivateFieldTarget();
      MethodHandles.Lookup targetLookup = _Lookup._trustedLookup(PrivateFieldTarget.class);
      MethodHandle fieldGetter =
          targetLookup.findGetter(PrivateFieldTarget.class, "field", int.class);
      MethodHandle fieldSetter =
          targetLookup.findSetter(PrivateFieldTarget.class, "field", int.class);
      if ((int) fieldGetter.invoke(target) != 7) {
        throw new AssertionError("Private field initial value was not read");
      }
      fieldSetter.invoke(target, 42);
      if ((int) fieldGetter.invoke(target) != 42) {
        throw new AssertionError("Private field value was not written");
      }
    }
  }

  public static final class ForyRoundTripFallbackProbe {
    public static void main(String[] args) throws Throwable {
      assertInvokeClosed(ForyRoundTripFallbackProbe.class);
      Fory fory =
          Fory.builder()
              .withXlang(false)
              .requireClassRegistration(false)
              .withCompatible(false)
              .build();
      RoundTripTarget value = new RoundTripTarget(7, "trusted");
      RoundTripTarget copy = (RoundTripTarget) fory.deserialize(fory.serialize(value));
      copy.assertState(7, "trusted");
    }
  }

  private static final class PrivateFieldTarget {
    private int field = 7;
  }

  public static final class RoundTripTarget {
    private int number = -1;
    private String text = "unset";

    public RoundTripTarget() {}

    RoundTripTarget(int number, String text) {
      this.number = number;
      this.text = text;
    }

    private void assertState(int expectedNumber, String expectedText) {
      if (number != expectedNumber || !expectedText.equals(text)) {
        throw new AssertionError("Unexpected round-trip state " + number + ", " + text);
      }
    }
  }
}

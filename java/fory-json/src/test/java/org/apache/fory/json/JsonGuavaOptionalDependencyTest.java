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

package org.apache.fory.json;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.fory.platform.JdkVersion;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class JsonGuavaOptionalDependencyTest extends ForyJsonTestModels {
  private static final String RESULT = "RESULT:ok";

  @Factory(dataProvider = "enableCodegen")
  public JsonGuavaOptionalDependencyTest(boolean codegen) {
    super(codegen);
  }

  @Test
  public void buildWithoutGuava() throws Exception {
    String filteredClassPath = removeGuavaFromClasspath(System.getProperty("java.class.path"));
    Process process =
        new ProcessBuilder(
                javaCommand(
                    filteredClassPath, NoGuavaMain.class, Boolean.toString(codegenEnabled())))
            .redirectErrorStream(true)
            .start();
    String output = readFully(process.getInputStream());
    assertEquals(process.waitFor(), 0, output);
    assertTrue(output.contains(RESULT), output);
  }

  private static List<String> javaCommand(String classPath, Class<?> mainClass, String codegen) {
    List<String> command =
        new java.util.ArrayList<>(
            Arrays.asList(
                System.getProperty("java.home")
                    + File.separator
                    + "bin"
                    + File.separator
                    + "java"));
    if (JdkVersion.MAJOR_VERSION >= 25) {
      command.add("--add-opens=java.base/java.lang.invoke=ALL-UNNAMED");
    }
    command.add("-cp");
    command.add(classPath);
    command.add(mainClass.getName());
    command.add(codegen);
    return command;
  }

  private static String removeGuavaFromClasspath(String classPath) {
    return Arrays.stream(classPath.split(java.util.regex.Pattern.quote(File.pathSeparator)))
        .filter(path -> !new File(path).getName().startsWith("guava-"))
        .collect(Collectors.joining(File.pathSeparator));
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

  public static final class NoGuavaMain {
    public static void main(String[] args) {
      assertNotLoadable("com.google.common.collect.ImmutableList");
      assertNotLoadable("com.google.common.primitives.ImmutableIntArray");

      boolean codegen = Boolean.parseBoolean(args[0]);
      ForyJson json = ForyJson.builder().withCodegen(codegen).withAsyncCompilation(false).build();
      PlainValue stringValue = json.fromJson("{\"name\":\"string\",\"count\":7}", PlainValue.class);
      assertValue(stringValue, "string", 7);

      byte[] utf8 = "{\"name\":\"bytes\",\"count\":8}".getBytes(StandardCharsets.UTF_8);
      PlainValue byteValue = json.fromJson(utf8, PlainValue.class);
      assertValue(byteValue, "bytes", 8);

      PlainValue written = new PlainValue("write", 9);
      PlainValue roundTrip = json.fromJson(json.toJson(written), PlainValue.class);
      assertValue(roundTrip, "write", 9);

      PlainValue byteRoundTrip = json.fromJson(json.toJsonBytes(written), PlainValue.class);
      assertValue(byteRoundTrip, "write", 9);

      System.out.println(RESULT);
    }

    private static void assertNotLoadable(String className) {
      try {
        Class.forName(className, false, NoGuavaMain.class.getClassLoader());
      } catch (ClassNotFoundException e) {
        return;
      }
      throw new AssertionError(className + " should not be loadable");
    }

    private static void assertValue(PlainValue value, String name, int count) {
      if (!name.equals(value.name) || value.count != count) {
        throw new AssertionError("Unexpected value " + value.name + "/" + value.count);
      }
    }
  }

  public static final class PlainValue {
    public String name;
    public int count;

    public PlainValue() {}

    public PlainValue(String name, int count) {
      this.name = name;
      this.count = count;
    }
  }
}

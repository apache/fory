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

package org.apache.fory;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.serializer.collection.GuavaCollectionSerializers;
import org.testng.annotations.Test;

public class GuavaOptionalDependencyTest {
  private static final String RESULT_PREFIX = "RESULT:";

  @Test
  public void testBuildWithoutGuavaAndReserveIds() throws Exception {
    assertTrue(GuavaCollectionSerializers.isGuavaAvailable());
    RegistrationIds inProcessIds = currentProcessIds();
    assertEquals(
        inProcessIds.enabledId - inProcessIds.disabledId,
        GuavaCollectionSerializers.getNumReservedTypeIds());
    RegistrationIds childIds = runWithoutGuava();
    assertEquals(childIds.enabledId, inProcessIds.enabledId);
    assertEquals(childIds.disabledId, inProcessIds.disabledId);
  }

  private static RegistrationIds currentProcessIds() {
    return new RegistrationIds(registeredInternalId(true), registeredInternalId(false));
  }

  private static int registeredInternalId(boolean registerGuavaTypes) {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .registerGuavaTypes(registerGuavaTypes)
            .requireClassRegistration(false)
            .suppressClassRegistrationWarnings(true)
            .build();
    ClassResolver resolver = (ClassResolver) fory.getTypeResolver();
    resolver.registerInternal(InternalSample.class);
    return resolver.getRegisteredClassId(InternalSample.class);
  }

  private static RegistrationIds runWithoutGuava() throws Exception {
    String javaBin =
        System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
    String filteredClassPath = removeGuavaFromClasspath(System.getProperty("java.class.path"));
    Process process =
        new ProcessBuilder(javaBin, "-cp", filteredClassPath, NoGuavaMain.class.getName())
            .redirectErrorStream(true)
            .start();
    String output = readFully(process.getInputStream());
    assertEquals(process.waitFor(), 0, output);
    return parseResult(output);
  }

  private static RegistrationIds parseResult(String output) {
    for (String line : output.split("\\R")) {
      if (line.startsWith(RESULT_PREFIX)) {
        String[] parts = line.substring(RESULT_PREFIX.length()).split(",");
        return new RegistrationIds(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
      }
    }
    throw new AssertionError("Missing result line in output:\n" + output);
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

  private static final class RegistrationIds {
    private final int enabledId;
    private final int disabledId;

    private RegistrationIds(int enabledId, int disabledId) {
      this.enabledId = enabledId;
      this.disabledId = disabledId;
    }
  }

  public static final class NoGuavaMain {
    public static void main(String[] args) {
      RegistrationIds ids = currentProcessIds();
      Fory fory =
          Fory.builder()
              .withXlang(false)
              .registerGuavaTypes(true)
              .requireClassRegistration(false)
              .suppressClassRegistrationWarnings(true)
              .build();
      byte[] bytes = fory.serialize(new SampleValue("fory"));
      SampleValue value = (SampleValue) fory.deserialize(bytes);
      if (!"fory".equals(value.value)) {
        throw new AssertionError("Unexpected round-trip value " + value.value);
      }
      System.out.println(RESULT_PREFIX + ids.enabledId + "," + ids.disabledId);
    }
  }

  public static final class InternalSample {}

  public static final class SampleValue {
    private final String value;

    public SampleValue(String value) {
      this.value = value;
    }
  }
}

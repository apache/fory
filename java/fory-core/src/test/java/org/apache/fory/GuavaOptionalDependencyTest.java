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
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.collection.GuavaCollectionSerializers;
import org.testng.annotations.Test;

public class GuavaOptionalDependencyTest {
  private static final String RESULT_PREFIX = "RESULT:";

  @Test
  public void testBuildWithoutGuavaAndReserveIds() throws Exception {
    assertTrue(GuavaCollectionSerializers.isGuavaAvailable());
    assertEquals(GuavaCollectionSerializers.getNumReservedTypeIds(), 13);
    assertGraalvmGuavaSerializers();
    RegistrationIds inProcessIds = currentProcessIds();
    assertEquals(
        inProcessIds.enabledId - inProcessIds.disabledId,
        GuavaCollectionSerializers.getNumReservedTypeIds());
    RegistrationIds childIds = runWithoutGuava();
    assertEquals(childIds.enabledId, inProcessIds.enabledId);
    assertEquals(childIds.disabledId, inProcessIds.disabledId);
  }

  @Test
  public void testPartialGuavaReservesIds() throws Exception {
    RegistrationIds inProcessIds = currentProcessIds();
    RegistrationIds childIds = runWithPartialGuava();
    assertEquals(childIds.enabledId, inProcessIds.enabledId);
    assertEquals(childIds.disabledId, inProcessIds.disabledId);
  }

  private static RegistrationIds currentProcessIds() {
    return new RegistrationIds(registeredInternalId(true), registeredInternalId(false));
  }

  private static void assertGraalvmGuavaSerializers() {
    Set<Class<? extends Serializer>> serializers = GraalvmSupport.getRegisteredSerializerClasses();
    assertTrue(serializers.contains(GuavaCollectionSerializers.ImmutableIntArraySerializer.class));
    assertTrue(serializers.contains(GuavaCollectionSerializers.ImmutableMapFormSerializer.class));
    assertTrue(serializers.contains(GuavaCollectionSerializers.ImmutableBiMapFormSerializer.class));
    assertTrue(serializers.contains(GuavaCollectionSerializers.HashBasedTableSerializer.class));
  }

  private static int registeredInternalId(boolean registerGuavaTypes) {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .registerGuavaTypes(registerGuavaTypes)
            .requireClassRegistration(false)
            .suppressClassRegistrationWarnings(true)
            .withCompatible(false)
            .build();
    ClassResolver resolver = (ClassResolver) fory.getTypeResolver();
    resolver.registerInternal(InternalSample.class);
    return resolver.getRegisteredClassId(InternalSample.class);
  }

  private static RegistrationIds runWithoutGuava() throws Exception {
    String filteredClassPath = removeGuavaFromClasspath(System.getProperty("java.class.path"));
    Process process =
        new ProcessBuilder(TestUtils.javaCommand(filteredClassPath, NoGuavaMain.class))
            .redirectErrorStream(true)
            .start();
    String output = readFully(process.getInputStream());
    assertEquals(process.waitFor(), 0, output);
    return parseResult(output);
  }

  private static RegistrationIds runWithPartialGuava() throws Exception {
    try (PartialGuavaClassLoader loader = new PartialGuavaClassLoader(classPathUrls())) {
      Class<?> main = Class.forName(PartialGuavaMain.class.getName(), true, loader);
      String output = (String) main.getMethod("run").invoke(null);
      return parseResult(output);
    }
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

  private static URL[] classPathUrls() {
    return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
        .map(GuavaOptionalDependencyTest::toUrl)
        .toArray(URL[]::new);
  }

  private static URL toUrl(String path) {
    try {
      return new File(path).toURI().toURL();
    } catch (MalformedURLException e) {
      throw new IllegalArgumentException(e);
    }
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
      assertSerializerMetadataLinked();
      RegistrationIds ids = currentProcessIds();
      Fory fory =
          Fory.builder()
              .withXlang(false)
              .registerGuavaTypes(true)
              .requireClassRegistration(false)
              .suppressClassRegistrationWarnings(true)
              .withCompatible(false)
              .build();
      byte[] bytes = fory.serialize(new SampleValue("fory"));
      SampleValue value = (SampleValue) fory.deserialize(bytes);
      if (!"fory".equals(value.value)) {
        throw new AssertionError("Unexpected round-trip value " + value.value);
      }
      System.out.println(RESULT_PREFIX + ids.enabledId + "," + ids.disabledId);
    }
  }

  public static final class PartialGuavaMain {
    public static String run() {
      assertSerializerMetadataLinked();
      RegistrationIds ids = currentProcessIds();
      Fory fory =
          Fory.builder()
              .withXlang(false)
              .registerGuavaTypes(true)
              .requireClassRegistration(false)
              .suppressClassRegistrationWarnings(true)
              .withCompatible(false)
              .build();
      byte[] bytes = fory.serialize(com.google.common.collect.ImmutableList.of("fory"));
      Object value = fory.deserialize(bytes);
      if (!com.google.common.collect.ImmutableList.of("fory").equals(value)) {
        throw new AssertionError("Unexpected round-trip value " + value);
      }
      return RESULT_PREFIX + ids.enabledId + "," + ids.disabledId;
    }
  }

  private static void assertSerializerMetadataLinked() {
    for (Class<? extends Serializer> serializerClass :
        GraalvmSupport.getRegisteredSerializerClasses()) {
      serializerClass.getDeclaredConstructors();
      serializerClass.getDeclaredMethods();
      serializerClass.getDeclaredFields();
    }
  }

  private static final class PartialGuavaClassLoader extends URLClassLoader {
    private static final String FILTERED_CLASS = "com.google.common.primitives.ImmutableIntArray";

    private PartialGuavaClassLoader(URL[] urls) {
      super(urls, null);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (name.equals(FILTERED_CLASS)) {
        throw new ClassNotFoundException(name);
      }
      return super.loadClass(name, resolve);
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

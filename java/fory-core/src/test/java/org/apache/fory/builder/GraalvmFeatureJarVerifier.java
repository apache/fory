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

package org.apache.fory.builder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/** Verifies the packaged GraalVM Feature and its Native Image activation metadata. */
public final class GraalvmFeatureJarVerifier {
  private static final String FEATURE_CLASS_NAME =
      "org.apache.fory.graalvm.feature.ForyGraalVMFeature";
  private static final String FEATURE_CLASS_FILE =
      "org/apache/fory/graalvm/feature/ForyGraalVMFeature.class";
  private static final String VERSION_17_FEATURE_CLASS =
      "META-INF/versions/17/" + FEATURE_CLASS_FILE;
  private static final String FEATURE_SOURCE_FILE =
      "org/apache/fory/graalvm/feature/ForyGraalVMFeature.java";
  private static final String VERSION_17_FEATURE_SOURCE =
      "META-INF/versions/17/" + FEATURE_SOURCE_FILE;
  private static final String NATIVE_IMAGE_PROPERTIES =
      "META-INF/native-image/org.apache.fory/fory-core/native-image.properties";
  private static final String FEATURE_SERVICE =
      "META-INF/services/org.graalvm.nativeimage.hosted.Feature";
  private static final String FEATURE_OPTION = "--features=" + FEATURE_CLASS_NAME;
  private static final String INITIALIZATION_OPTION = "--initialize-at-build-time=";

  private GraalvmFeatureJarVerifier() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      throw new IllegalArgumentException(
          "Usage: GraalvmFeatureJarVerifier <fory-core.jar> <fory-core-sources.jar>");
    }
    Path jarPath = Paths.get(args[0]);
    verifyBinaryJar(jarPath);
    verifySourceJar(Paths.get(args[1]));
    verifyFeatureLoading(jarPath);
  }

  private static void verifyBinaryJar(Path jarPath) throws IOException {
    try (JarFile jarFile = new JarFile(jarPath.toFile())) {
      Manifest manifest = jarFile.getManifest();
      check(manifest != null, "Packaged jar has no manifest");
      String multiRelease = manifest.getMainAttributes().getValue("Multi-Release");
      check("true".equalsIgnoreCase(multiRelease), "Missing Multi-Release: true manifest entry");
      check(countEntries(jarFile, FEATURE_CLASS_FILE) == 0, "Base Feature class must not exist");
      check(
          countEntriesEndingWith(jarFile, FEATURE_CLASS_FILE) == 1,
          "Expected exactly one packaged Feature class");
      check(
          countEntries(jarFile, VERSION_17_FEATURE_CLASS) == 1,
          "Expected exactly one Java 17 Feature class");
      check(countEntries(jarFile, FEATURE_SERVICE) == 0, "Feature service file must not exist");
      check(
          countEntries(jarFile, NATIVE_IMAGE_PROPERTIES) == 1,
          "Expected exactly one core native-image.properties");

      String properties = readEntry(jarFile, NATIVE_IMAGE_PROPERTIES);
      check(
          countOccurrences(properties, "--features=") == 1,
          "Expected exactly one --features option");
      check(properties.contains(FEATURE_OPTION), "Core Feature option is missing");
      check(
          properties.contains(INITIALIZATION_OPTION),
          "Build-time initialization option is missing");
    }
  }

  private static void verifySourceJar(Path sourceJarPath) throws IOException {
    try (JarFile jarFile = new JarFile(sourceJarPath.toFile())) {
      check(countEntries(jarFile, FEATURE_SOURCE_FILE) == 0, "Base Feature source must not exist");
      check(
          countEntriesEndingWith(jarFile, FEATURE_SOURCE_FILE) == 1,
          "Expected exactly one packaged Feature source");
      check(
          countEntries(jarFile, VERSION_17_FEATURE_SOURCE) == 1,
          "Expected exactly one versioned Java 17 Feature source");
    }
  }

  private static void verifyFeatureLoading(Path jarPath) throws Exception {
    URL[] urls = {jarPath.toUri().toURL()};
    try (URLClassLoader classLoader =
        new URLClassLoader(urls, GraalvmFeatureJarVerifier.class.getClassLoader())) {
      Class<?> featureClass = Class.forName(FEATURE_CLASS_NAME, true, classLoader);
      check(
          featureClass.getClassLoader() == classLoader, "Feature was not loaded from packaged jar");
      check(!Modifier.isPublic(featureClass.getModifiers()), "Feature must remain non-public");
      Constructor<?> constructor = featureClass.getDeclaredConstructor();
      constructor.setAccessible(true);
      Object feature = constructor.newInstance();
      Method getDescription = featureClass.getMethod("getDescription");
      getDescription.setAccessible(true);
      Object description = getDescription.invoke(feature);
      check(
          description instanceof String && ((String) description).contains("Fory"),
          "Packaged Feature returned an invalid description");
    }
  }

  private static int countEntries(JarFile jarFile, String expectedName) {
    int count = 0;
    Enumeration<JarEntry> entries = jarFile.entries();
    while (entries.hasMoreElements()) {
      if (expectedName.equals(entries.nextElement().getName())) {
        count++;
      }
    }
    return count;
  }

  private static int countEntriesEndingWith(JarFile jarFile, String suffix) {
    int count = 0;
    Enumeration<JarEntry> entries = jarFile.entries();
    while (entries.hasMoreElements()) {
      if (entries.nextElement().getName().endsWith(suffix)) {
        count++;
      }
    }
    return count;
  }

  private static String readEntry(JarFile jarFile, String entryName) throws IOException {
    JarEntry entry = jarFile.getJarEntry(entryName);
    check(entry != null, "Missing jar entry " + entryName);
    try (InputStream inputStream = jarFile.getInputStream(entry)) {
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int read;
      while ((read = inputStream.read(buffer)) >= 0) {
        outputStream.write(buffer, 0, read);
      }
      return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  private static int countOccurrences(String value, String target) {
    int count = 0;
    int offset = 0;
    while ((offset = value.indexOf(target, offset)) >= 0) {
      count++;
      offset += target.length();
    }
    return count;
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}

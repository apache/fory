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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Verifies the packaged JDK25 multi-release class graph. */
public final class Jdk25MultiReleaseJarVerifier {
  private static final String VERSION_25_PREFIX = "META-INF/versions/25/";
  private static final String FORY_CLASS_PREFIX = "org/apache/fory/";
  private static final String SHADED_CLASS_PREFIX = "org/apache/fory/shaded/";
  private static final String[] FORBIDDEN_CONSTANTS = {"sun/misc/Unsafe", "sun.misc.Unsafe"};
  private static final String[] REQUIRED_VERSION_25_CLASSES = {
    "module-info.class",
    "org/apache/fory/memory/LittleEndian.class",
    "org/apache/fory/memory/MemoryBuffer.class",
    "org/apache/fory/platform/internal/_Lookup.class",
    "org/apache/fory/platform/internal/_UnsafeUtils.class",
    "org/apache/fory/builder/UnsafeCodegenSupport.class",
    "org/apache/fory/reflect/InstanceFieldAccessors.class",
    "org/apache/fory/reflect/UnsafeObjectInstantiator.class",
    "org/apache/fory/serializer/PlatformStringUtils.class"
  };

  private Jdk25MultiReleaseJarVerifier() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 1) {
      throw new IllegalArgumentException("Usage: Jdk25MultiReleaseJarVerifier <fory-core.jar>");
    }
    verify(Paths.get(args[0]));
  }

  static void verify(Path jarPath) throws IOException {
    Map<String, byte[]> rootClasses = new HashMap<>();
    Map<String, byte[]> version25Classes = new HashMap<>();
    List<String> violations = new ArrayList<>();
    try (JarFile jarFile = new JarFile(jarPath.toFile())) {
      Enumeration<JarEntry> entries = jarFile.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
          continue;
        }
        String name = entry.getName();
        if (isForyRootClass(name)) {
          rootClasses.put(name, readFully(jarFile, entry));
        } else if (name.startsWith(VERSION_25_PREFIX)) {
          String className = name.substring(VERSION_25_PREFIX.length());
          if ("module-info.class".equals(className) || isForyRootClass(className)) {
            version25Classes.put(className, readFully(jarFile, entry));
          }
        }
      }
    }

    verifyRequiredClasses(rootClasses, version25Classes, violations);
    verifyForbiddenConstants(rootClasses, version25Classes, violations);
    if (!violations.isEmpty()) {
      throw new AssertionError(
          "Invalid JDK25 multi-release fory-core jar " + jarPath + ": " + violations);
    }
  }

  private static boolean isForyRootClass(String name) {
    return name.startsWith(FORY_CLASS_PREFIX) && !name.startsWith(SHADED_CLASS_PREFIX);
  }

  private static void verifyRequiredClasses(
      Map<String, byte[]> rootClasses, Map<String, byte[]> version25Classes, List<String> out) {
    if (rootClasses.containsKey("org/apache/fory/platform/UnsafeOps.class")) {
      out.add("Root UnsafeOps class must not be packaged");
    }
    if (version25Classes.containsKey("org/apache/fory/platform/UnsafeOps.class")) {
      out.add("JDK25 UnsafeOps class must not be packaged");
    }
    for (String requiredClass : REQUIRED_VERSION_25_CLASSES) {
      if (!version25Classes.containsKey(requiredClass)) {
        out.add("Missing JDK25 multi-release class " + requiredClass);
      }
    }
  }

  private static void verifyForbiddenConstants(
      Map<String, byte[]> rootClasses, Map<String, byte[]> version25Classes, List<String> out) {
    for (Map.Entry<String, byte[]> entry : rootClasses.entrySet()) {
      if (containsForbiddenConstant(entry.getValue())
          && !version25Classes.containsKey(entry.getKey())) {
        out.add(
            "Root class " + entry.getKey() + " has forbidden constants without JDK25 replacement");
      }
    }
    Set<String> resolvedClasses = new HashSet<>(rootClasses.keySet());
    resolvedClasses.addAll(version25Classes.keySet());
    resolvedClasses.remove("module-info.class");
    for (String className : resolvedClasses) {
      byte[] bytes =
          version25Classes.containsKey(className)
              ? version25Classes.get(className)
              : rootClasses.get(className);
      if (containsForbiddenConstant(bytes)) {
        out.add("JDK25 resolved class " + className + " has forbidden constants");
      }
    }
  }

  private static boolean containsForbiddenConstant(byte[] bytes) {
    for (String forbiddenConstant : FORBIDDEN_CONSTANTS) {
      if (containsAscii(bytes, forbiddenConstant)) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsAscii(byte[] bytes, String value) {
    byte[] target = value.getBytes(StandardCharsets.US_ASCII);
    int maxStart = bytes.length - target.length;
    for (int i = 0; i <= maxStart; i++) {
      int j = 0;
      while (j < target.length && bytes[i + j] == target[j]) {
        j++;
      }
      if (j == target.length) {
        return true;
      }
    }
    return false;
  }

  private static byte[] readFully(JarFile jarFile, JarEntry entry) throws IOException {
    try (InputStream inputStream = jarFile.getInputStream(entry)) {
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int read;
      while ((read = inputStream.read(buffer)) >= 0) {
        outputStream.write(buffer, 0, read);
      }
      return outputStream.toByteArray();
    }
  }
}

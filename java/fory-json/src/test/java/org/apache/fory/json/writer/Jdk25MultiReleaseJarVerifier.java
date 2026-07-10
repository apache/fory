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

package org.apache.fory.json.writer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/** Verifies the packaged JDK25 BigDecimal field-access implementation. */
public final class Jdk25MultiReleaseJarVerifier {
  private static final String CLASS_NAME = "org.apache.fory.json.writer.BigDecimalFields";
  private static final String CLASS_PATH = CLASS_NAME.replace('.', '/') + ".class";
  private static final String SOURCE_PATH = CLASS_NAME.replace('.', '/') + ".java";
  private static final String VERSION_PREFIX = "META-INF/versions/25/";

  private Jdk25MultiReleaseJarVerifier() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      throw new IllegalArgumentException(
          "Usage: Jdk25MultiReleaseJarVerifier <fory-json.jar> <fory-json-sources.jar>"
              + " <fory-core.jar>");
    }
    Path jarPath = Paths.get(args[0]);
    verify(jarPath, Paths.get(args[1]));
    verifyModulePath(jarPath, Paths.get(args[2]));
  }

  static void verify(Path jarPath, Path sourcesPath) throws Exception {
    byte[] versionClass;
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      Manifest manifest = jar.getManifest();
      require(manifest != null, "missing manifest");
      Attributes attributes = manifest.getMainAttributes();
      require("true".equalsIgnoreCase(attributes.getValue("Multi-Release")), "missing manifest");
      require(jar.getJarEntry(CLASS_PATH) != null, "missing root BigDecimalFields class");
      versionClass = read(jar, VERSION_PREFIX + CLASS_PATH);
    }
    try (JarFile sources = new JarFile(sourcesPath.toFile())) {
      require(
          sources.getJarEntry(VERSION_PREFIX + SOURCE_PATH) != null,
          "missing JDK25 BigDecimalFields source");
    }

    Class<?> type = new VersionClassLoader().define(versionClass);
    require(CLASS_NAME.equals(type.getName()), "wrong JDK25 class name");
    requireVarHandle(type, "INT_COMPACT");
    requireVarHandle(type, "SCALE");
  }

  private static void requireVarHandle(Class<?> type, String name) throws NoSuchFieldException {
    Field field = type.getDeclaredField(name);
    int modifiers = field.getModifiers();
    require(Modifier.isPrivate(modifiers), name + " must be private");
    require(Modifier.isStatic(modifiers), name + " must be static");
    require(Modifier.isFinal(modifiers), name + " must be final");
    require("java.lang.invoke.VarHandle".equals(field.getType().getName()), name + " type");
  }

  private static void verifyModulePath(Path jsonJar, Path coreJar) throws Exception {
    Path testClasses =
        Paths.get(
            Jdk25MultiReleaseJarVerifier.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    List<String> command = new ArrayList<>();
    command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
    command.add("--module-path");
    command.add(coreJar + File.pathSeparator + jsonJar);
    command.add("--add-modules");
    command.add("org.apache.fory.json");
    command.add("--add-opens");
    command.add("java.base/java.lang.invoke=org.apache.fory.core");
    command.add("--add-opens");
    command.add("org.apache.fory.json/org.apache.fory.json.writer=ALL-UNNAMED");
    command.add("-cp");
    command.add(testClasses.toString());
    command.add("org.apache.fory.json.verify.Jdk25ModulePathProbe");
    Process process = new ProcessBuilder(command).inheritIO().start();
    require(process.waitFor() == 0, "named-module BigDecimal writer probe failed");
  }

  private static byte[] read(JarFile jar, String name) throws IOException {
    JarEntry entry = jar.getJarEntry(name);
    require(entry != null, "missing " + name);
    try (InputStream input = jar.getInputStream(entry)) {
      long size = entry.getSize();
      ByteArrayOutputStream output =
          new ByteArrayOutputStream(size > 0 && size <= Integer.MAX_VALUE ? (int) size : 1024);
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) >= 0) {
        output.write(buffer, 0, count);
      }
      return output.toByteArray();
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError("Invalid JDK25 fory-json multi-release jar: " + message);
    }
  }

  private static final class VersionClassLoader extends ClassLoader {
    private VersionClassLoader() {
      super(Jdk25MultiReleaseJarVerifier.class.getClassLoader());
    }

    private Class<?> define(byte[] bytes) {
      return defineClass(null, bytes, 0, bytes.length);
    }
  }
}

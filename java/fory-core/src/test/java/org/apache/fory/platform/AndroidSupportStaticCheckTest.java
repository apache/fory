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

package org.apache.fory.platform;

import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.testng.annotations.Test;

public class AndroidSupportStaticCheckTest {
  private static final Pattern DIRECT_CLASS_VALUE =
      Pattern.compile("\\bClassValue\\s*<|new\\s+ClassValue\\s*<");
  private static final Pattern DIRECT_FIELD_GET_ANNOTATED_TYPE =
      Pattern.compile("\\.getAnnotatedType\\s*\\(");
  private static final Pattern DIRECT_ANNOTATED_TYPE_REFERENCE =
      Pattern.compile("\\bAnnotatedType\\b");
  private static final Pattern ANDROID_GATED_FIELD_GET_ANNOTATED_TYPE =
      Pattern.compile(
          "AndroidSupport\\.IS_ANDROID[\\s\\S]{0,160}\\.getAnnotatedType\\s*\\(", Pattern.DOTALL);
  private static final Pattern SCALA_TRUSTED_LOOKUP =
      Pattern.compile("_JDKAccess\\._trustedLookup\\(");
  private static final Pattern ANDROID_GATED_SCALA_TRUSTED_LOOKUP =
      Pattern.compile(
          "AndroidSupport\\.IS_ANDROID\\s*\\?\\s*null\\s*:\\s*"
              + "_JDKAccess\\._trustedLookup\\([^)]*\\)",
          Pattern.DOTALL);
  private static final Pattern ANDROID_REFLECTIVE_SCALA_DEFAULT_INVOKE =
      Pattern.compile(
          "if \\(AndroidSupport\\.IS_ANDROID\\).*method\\.setAccessible\\(true\\).*"
              + "method\\.invoke\\(target\\)",
          Pattern.DOTALL);

  @Test
  public void testNoDirectClassValueUsageInCoreSources() throws IOException {
    Path sourceRoot = Paths.get("src/main/java/org/apache/fory");
    List<String> violations = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(sourceRoot)) {
      paths
          .filter(path -> path.toString().endsWith(".java"))
          .forEach(
              path -> {
                try {
                  String relativePath = sourceRoot.relativize(path).toString().replace('\\', '/');
                  if ("collection/ClassValueCache.java".equals(relativePath)) {
                    return;
                  }
                  String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                  if (DIRECT_CLASS_VALUE.matcher(source).find()) {
                    violations.add(relativePath);
                  }
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
    }
    assertTrue(
        violations.isEmpty(),
        "Direct ClassValue usage is not Android-safe; use ClassValueCache instead: " + violations);
  }

  @Test
  public void testFieldAnnotatedTypeAccessStaysAndroidGated() throws IOException {
    Path sourceRoot = Paths.get("src/main/java/org/apache/fory");
    List<String> violations = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(sourceRoot)) {
      paths
          .filter(path -> path.toString().endsWith(".java"))
          .forEach(
              path -> {
                String relativePath = sourceRoot.relativize(path).toString().replace('\\', '/');
                if ("reflect/JvmTypeUseMetadata.java".equals(relativePath)) {
                  return;
                }
                try {
                  String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                  java.util.regex.Matcher matcher = DIRECT_FIELD_GET_ANNOTATED_TYPE.matcher(source);
                  while (matcher.find()) {
                    int start = Math.max(0, matcher.start() - 160);
                    String context = source.substring(start, matcher.end());
                    if (!ANDROID_GATED_FIELD_GET_ANNOTATED_TYPE.matcher(context).find()) {
                      violations.add(relativePath);
                      break;
                    }
                  }
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
    }
    assertTrue(
        violations.isEmpty(),
        "Field#getAnnotatedType is absent on Android API 26; gate direct access with AndroidSupport: "
            + violations);
  }

  @Test
  public void testAndroidLoadedRuntimePathsDoNotReferenceAnnotatedType() throws IOException {
    Path sourceRoot = Paths.get("src/main/java/org/apache/fory");
    List<String> violations = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(sourceRoot)) {
      paths
          .filter(path -> path.toString().endsWith(".java"))
          .forEach(
              path -> {
                try {
                  String relativePath = sourceRoot.relativize(path).toString().replace('\\', '/');
                  if ("reflect/JvmTypeUseMetadata.java".equals(relativePath)) {
                    return;
                  }
                  String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                  if (DIRECT_ANNOTATED_TYPE_REFERENCE.matcher(source).find()) {
                    violations.add(relativePath);
                  }
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
    }
    assertTrue(
        violations.isEmpty(),
        "Android resolves AnnotatedType method descriptors while shrinking Fory core; "
            + "use Object/Method based type-use reflection in core sources: "
            + violations);
  }

  @Test
  public void testScalaDefaultValuesDoNotUseTrustedLookupOnAndroid() throws IOException {
    Path sourcePath = Paths.get("src/main/java/org/apache/fory/util/DefaultValueUtils.java");
    String source = new String(Files.readAllBytes(sourcePath), StandardCharsets.UTF_8);
    List<String> violations = new ArrayList<>();
    Matcher matcher = SCALA_TRUSTED_LOOKUP.matcher(source);
    while (matcher.find()) {
      int start = Math.max(0, matcher.start() - 120);
      int end = Math.min(source.length(), matcher.end() + 160);
      String context = source.substring(start, end);
      if (!ANDROID_GATED_SCALA_TRUSTED_LOOKUP.matcher(context).find()) {
        violations.add(context);
      }
    }
    assertTrue(
        violations.isEmpty(),
        "Scala default values must not use _JDKAccess trusted lookup on Android: " + violations);
    assertTrue(
        ANDROID_REFLECTIVE_SCALA_DEFAULT_INVOKE.matcher(source).find(),
        "Android Scala default values must invoke default methods through direct reflection");
  }
}

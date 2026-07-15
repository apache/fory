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

package org.apache.fory.json.gradle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;

public class RuleCarrierFilesTest {
  private static final byte[] RULE_A = rule("A_ForyJsonMetadata");
  private static final byte[] RULE_B = rule("B_ForyJsonMetadata");

  @Test
  public void collectsInLogicalOrder() throws Exception {
    Path classes = Files.createTempDirectory("fory-json-classes");
    writeCarrier(classes, "b", RULE_B);
    writeCarrier(classes, "a", RULE_A);

    SortedMap<String, byte[]> rules =
        RuleCarrierFiles.collectFiles(Collections.emptyList(), singleton(classes));
    String output = new String(RuleCarrierFiles.canonicalRules(rules), StandardCharsets.UTF_8);
    assertTrue(output.indexOf("generated-a.pro") < output.indexOf("generated-b.pro"));
    assertTrue(output.contains("A_ForyJsonMetadata"));
    assertTrue(output.contains("B_ForyJsonMetadata"));
  }

  @Test
  public void collectsJarCarrier() throws Exception {
    Path jar = Files.createTempFile("fory-json-rules", ".jar");
    Files.write(jar, zip(Collections.singletonMap(carrierName("jar"), RULE_A)));
    SortedMap<String, byte[]> rules =
        RuleCarrierFiles.collectFiles(
            Collections.singletonList(jar.toFile()), Collections.emptyList());
    assertArrayEquals(RULE_A, rules.get(carrierName("jar")));
  }

  @Test
  public void rejectsDuplicateCarrier() throws Exception {
    Path first = Files.createTempDirectory("fory-json-first");
    Path second = Files.createTempDirectory("fory-json-second");
    writeCarrier(first, "same", RULE_A);
    writeCarrier(second, "same", RULE_A);
    assertThrows(
        GradleException.class,
        () ->
            RuleCarrierFiles.collectFiles(
                Collections.emptyList(), Arrays.asList(first.toFile(), second.toFile())));
  }

  @Test
  public void rejectsMalformedCarrier() throws Exception {
    Path classes = Files.createTempDirectory("fory-json-malformed");
    Path carrier = classes.resolve(RuleCarrierFiles.CARRIER_PREFIX).resolve("other.txt");
    Files.createDirectories(carrier.getParent());
    Files.write(carrier, RULE_A);
    assertThrows(
        GradleException.class,
        () -> RuleCarrierFiles.collectFiles(Collections.emptyList(), singleton(classes)));
  }

  @Test
  public void rejectsInvalidCarrierContent() throws Exception {
    Path classes = Files.createTempDirectory("fory-json-invalid");
    writeCarrier(classes, "utf8", new byte[] {(byte) 0xc3, 0x28});
    assertThrows(
        GradleException.class,
        () -> RuleCarrierFiles.collectFiles(Collections.emptyList(), singleton(classes)));
  }

  @Test
  public void rejectsUnexpectedDirective() throws Exception {
    Path classes = Files.createTempDirectory("fory-json-directive");
    writeCarrier(
        classes,
        "directive",
        (new String(RULE_A, StandardCharsets.UTF_8) + "-dontwarn example.**\n")
            .getBytes(StandardCharsets.UTF_8));
    assertThrows(
        GradleException.class,
        () -> RuleCarrierFiles.collectFiles(Collections.emptyList(), singleton(classes)));
  }

  @Test
  public void rejectsWildcardMember() throws Exception {
    Path classes = Files.createTempDirectory("fory-json-wildcard");
    writeCarrier(
        classes,
        "wildcard",
        ("-keep,allowoptimization class A_ForyJsonMetadata {\n  *** get*(...);\n}\n")
            .getBytes(StandardCharsets.UTF_8));
    assertThrows(
        GradleException.class,
        () -> RuleCarrierFiles.collectFiles(Collections.emptyList(), singleton(classes)));
  }

  @Test
  public void publishesAarRules() throws Exception {
    Map<String, byte[]> classes = new LinkedHashMap<>();
    classes.put("example/Model.class", new byte[] {1, 2, 3});
    classes.put(carrierName("b"), RULE_B);
    classes.put(carrierName("a"), RULE_A);
    Map<String, byte[]> aar = new LinkedHashMap<>();
    aar.put("AndroidManifest.xml", new byte[] {4, 5});
    aar.put("classes.jar", zip(classes));
    aar.put(
        RuleCarrierFiles.AAR_RULES, "-keep class existing.Type".getBytes(StandardCharsets.UTF_8));
    Path input = Files.createTempFile("fory-json-input", ".aar");
    Path output = Files.createTempFile("fory-json-output", ".aar");
    Files.write(input, zip(aar));

    RuleCarrierFiles.publishAar(input.toFile(), output.toFile());

    Map<String, byte[]> published = unzip(Files.readAllBytes(output));
    byte[] publishedRules = published.get(RuleCarrierFiles.AAR_RULES);
    byte[] existing = "-keep class existing.Type".getBytes(StandardCharsets.UTF_8);
    assertArrayEquals(existing, Arrays.copyOf(publishedRules, existing.length));
    String consumer = new String(publishedRules, StandardCharsets.UTF_8);
    assertTrue(consumer.startsWith("-keep class existing.Type\n"));
    assertTrue(consumer.indexOf("A_ForyJsonMetadata") < consumer.indexOf("B_ForyJsonMetadata"));
    Map<String, byte[]> publishedClasses = unzip(published.get("classes.jar"));
    assertTrue(publishedClasses.containsKey("example/Model.class"));
    for (String name : publishedClasses.keySet()) {
      assertFalse(name.startsWith(RuleCarrierFiles.CARRIER_PREFIX));
    }
    for (String name : published.keySet()) {
      assertFalse(name.startsWith(RuleCarrierFiles.CARRIER_PREFIX));
    }
  }

  @Test
  public void publishesDeterministicAar() throws Exception {
    Map<String, byte[]> firstClasses = new LinkedHashMap<>();
    firstClasses.put(carrierName("b"), RULE_B);
    firstClasses.put("example/Model.class", new byte[] {1});
    firstClasses.put(carrierName("a"), RULE_A);
    Map<String, byte[]> secondClasses = new LinkedHashMap<>();
    secondClasses.put(carrierName("a"), RULE_A);
    secondClasses.put(carrierName("b"), RULE_B);
    secondClasses.put("example/Model.class", new byte[] {1});
    Path first = aarWithClasses(zip(firstClasses));
    Path second = aarWithClasses(zip(secondClasses));
    Path firstOutput = Files.createTempFile("fory-json-first-output", ".aar");
    Path secondOutput = Files.createTempFile("fory-json-second-output", ".aar");

    RuleCarrierFiles.publishAar(first.toFile(), firstOutput.toFile());
    RuleCarrierFiles.publishAar(second.toFile(), secondOutput.toFile());

    assertArrayEquals(Files.readAllBytes(firstOutput), Files.readAllBytes(secondOutput));
  }

  @Test
  public void rejectsDuplicateClassesEntry() throws Exception {
    byte[] single = zip(Collections.singletonMap(carrierName("same"), RULE_A));
    Path input = aarWithClasses(duplicateLocalEntry(single));
    Path output = Files.createTempFile("fory-json-duplicate-output", ".aar");
    assertThrows(
        GradleException.class, () -> RuleCarrierFiles.publishAar(input.toFile(), output.toFile()));
  }

  @Test
  public void rejectsDuplicateAarEntry() throws Exception {
    byte[] single = zip(Collections.singletonMap("classes.jar", zip(Collections.emptyMap())));
    Path input = Files.createTempFile("fory-json-duplicate", ".aar");
    Files.write(input, duplicateLocalEntry(single));
    Path output = Files.createTempFile("fory-json-duplicate-output", ".aar");
    assertThrows(
        GradleException.class, () -> RuleCarrierFiles.publishAar(input.toFile(), output.toFile()));
  }

  @Test
  public void leavesAarWithoutRulesUnchanged() throws Exception {
    Map<String, byte[]> aar = new LinkedHashMap<>();
    aar.put("classes.jar", zip(Collections.singletonMap("example/Model.class", new byte[] {1})));
    byte[] inputBytes = zip(aar);
    Path input = Files.createTempFile("fory-json-plain", ".aar");
    Path output = Files.createTempFile("fory-json-plain-output", ".aar");
    Files.write(input, inputBytes);

    RuleCarrierFiles.publishAar(input.toFile(), output.toFile());

    assertArrayEquals(inputBytes, Files.readAllBytes(output));
  }

  private static void writeCarrier(Path root, String name, byte[] content) throws IOException {
    Path path = root.resolve(carrierName(name));
    Files.createDirectories(path.getParent());
    Files.write(path, content);
  }

  private static String carrierName(String name) {
    return RuleCarrierFiles.CARRIER_PREFIX
        + RuleCarrierFiles.FILE_PREFIX
        + name
        + RuleCarrierFiles.FILE_SUFFIX;
  }

  private static byte[] rule(String companion) {
    return ("-keep,allowoptimization class " + companion + " {\n}\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] zip(Map<String, byte[]> entries) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream output = new ZipOutputStream(bytes)) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        ZipEntry zipEntry = new ZipEntry(entry.getKey());
        zipEntry.setTime(0L);
        output.putNextEntry(zipEntry);
        output.write(entry.getValue());
        output.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  private static Path aarWithClasses(byte[] classes) throws IOException {
    Path aar = Files.createTempFile("fory-json", ".aar");
    Files.write(aar, zip(Collections.singletonMap("classes.jar", classes)));
    return aar;
  }

  private static byte[] duplicateLocalEntry(byte[] zip) throws IOException {
    int central = signature(zip, 0x02014b50);
    ByteArrayOutputStream output = new ByteArrayOutputStream(central * 2);
    output.write(zip, 0, central);
    output.write(zip, 0, central);
    return output.toByteArray();
  }

  private static int signature(byte[] bytes, int signature) {
    for (int i = 0; i <= bytes.length - 4; i++) {
      int value =
          (bytes[i] & 0xff)
              | (bytes[i + 1] & 0xff) << 8
              | (bytes[i + 2] & 0xff) << 16
              | (bytes[i + 3] & 0xff) << 24;
      if (value == signature) {
        return i;
      }
    }
    throw new AssertionError("ZIP central directory not found");
  }

  private static Map<String, byte[]> unzip(byte[] bytes) throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry entry;
      while ((entry = input.getNextEntry()) != null) {
        entries.put(entry.getName(), read(input));
      }
    }
    return entries;
  }

  private static byte[] read(ZipInputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    int count;
    while ((count = input.read(buffer)) != -1) {
      output.write(buffer, 0, count);
    }
    return output.toByteArray();
  }

  private static java.util.List<File> singleton(Path path) {
    return Collections.singletonList(path.toFile());
  }
}

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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.gradle.api.GradleException;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;

/** File contract shared by the application collector and AAR publisher tasks. */
final class RuleCarrierFiles {
  static final String CARRIER_PREFIX = "META-INF/fory-json/r8/";
  static final String FILE_PREFIX = "fory-json-generated-";
  static final String FILE_SUFFIX = ".pro";
  static final String AAR_RULES = "proguard.txt";
  private static final String BINARY_NAME =
      "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*"
          + "(?:\\.\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)*";
  private static final Pattern KEEP_HEADER =
      Pattern.compile(
          "^-keep(?:classmembers)?,allowoptimization class ("
              + BINARY_NAME
              + ")(?: extends ("
              + BINARY_NAME
              + "))? \\{$");
  private static final byte[] HEADER =
      "# Fory JSON generated rules\n".getBytes(StandardCharsets.UTF_8);

  private RuleCarrierFiles() {}

  static SortedMap<String, byte[]> collect(List<RegularFile> jars, List<Directory> directories)
      throws IOException {
    SortedMap<String, byte[]> rules = new TreeMap<>();
    for (RegularFile jar : jars) {
      collectZip(jar.getAsFile().toPath(), rules);
    }
    for (Directory directory : directories) {
      collectDirectory(directory.getAsFile().toPath(), rules);
    }
    return rules;
  }

  static SortedMap<String, byte[]> collectFiles(List<File> jars, List<File> directories)
      throws IOException {
    SortedMap<String, byte[]> rules = new TreeMap<>();
    for (File jar : jars) {
      collectZip(jar.toPath(), rules);
    }
    for (File directory : directories) {
      collectDirectory(directory.toPath(), rules);
    }
    return rules;
  }

  static void writeRules(SortedMap<String, byte[]> rules, File output) throws IOException {
    Path outputPath = output.toPath();
    Path parent = outputPath.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.write(outputPath, canonicalRules(rules));
  }

  static byte[] canonicalRules(SortedMap<String, byte[]> rules) throws IOException {
    if (rules.isEmpty()) {
      return new byte[0];
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    output.write(HEADER);
    boolean first = true;
    for (Map.Entry<String, byte[]> entry : rules.entrySet()) {
      if (!first) {
        output.write('\n');
      }
      first = false;
      output.write(("# " + entry.getKey() + "\n").getBytes(StandardCharsets.UTF_8));
      byte[] content = entry.getValue();
      output.write(content);
      if (content.length == 0 || content[content.length - 1] != '\n') {
        output.write('\n');
      }
    }
    return output.toByteArray();
  }

  static void publishAar(File input, File output) throws IOException {
    requireRegular(input.toPath(), "AAR input");
    byte[] inputBytes = Files.readAllBytes(input.toPath());
    AarContents contents = readAar(inputBytes);
    if (contents.rules.isEmpty()) {
      copyUnchanged(input.toPath(), output.toPath());
      return;
    }
    byte[] generated = canonicalRules(contents.rules);
    byte[] consumerRules = appendRules(contents.consumerRules, generated);
    byte[] outputBytes = writeAar(contents, consumerRules);
    Path outputPath = output.toPath();
    Path parent = outputPath.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.write(outputPath, outputBytes);
  }

  private static AarContents readAar(byte[] bytes) throws IOException {
    AarContents result = new AarContents();
    Set<String> names = new HashSet<>();
    try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry entry;
      while ((entry = input.getNextEntry()) != null) {
        String name = entry.getName();
        if (!names.add(name)) {
          throw invalid("Duplicate AAR entry " + name);
        }
        byte[] content = readEntry(input);
        if ("classes.jar".equals(name)) {
          content = readClassesJar(content, result.rules);
        } else if (AAR_RULES.equals(name)) {
          result.consumerRules = content;
        } else if (name.startsWith(CARRIER_PREFIX)) {
          throw invalid("Rule carrier must be inside AAR classes.jar: " + name);
        }
        result.entries.put(name, content);
      }
    }
    if (!result.entries.containsKey("classes.jar")) {
      throw invalid("AAR has no classes.jar");
    }
    return result;
  }

  private static byte[] readClassesJar(byte[] bytes, SortedMap<String, byte[]> rules)
      throws IOException {
    SortedMap<String, byte[]> entries = new TreeMap<>();
    Set<String> names = new HashSet<>();
    try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry entry;
      while ((entry = input.getNextEntry()) != null) {
        String name = entry.getName();
        if (!names.add(name)) {
          throw invalid("Duplicate classes.jar entry " + name);
        }
        byte[] content = readEntry(input);
        if (isCarrier(name)) {
          addRule(rules, name, content);
        } else if (!isCarrierDirectory(name)) {
          if (name.startsWith(CARRIER_PREFIX)) {
            throw invalid("Malformed rule carrier path " + name);
          }
          entries.put(name, content);
        }
      }
    }
    if (rules.isEmpty()) {
      return bytes;
    }
    return writeZip(entries);
  }

  private static byte[] writeAar(AarContents contents, byte[] consumerRules) throws IOException {
    SortedMap<String, byte[]> entries = new TreeMap<>(contents.entries);
    entries.put(AAR_RULES, consumerRules);
    return writeZip(entries);
  }

  private static byte[] writeZip(SortedMap<String, byte[]> entries) throws IOException {
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

  private static byte[] appendRules(byte[] existing, byte[] generated) throws IOException {
    ByteArrayOutputStream output =
        new ByteArrayOutputStream(existing.length + generated.length + 1);
    output.write(existing);
    if (existing.length != 0 && existing[existing.length - 1] != '\n') {
      output.write('\n');
    }
    output.write(generated);
    return output.toByteArray();
  }

  private static void collectDirectory(Path root, SortedMap<String, byte[]> rules)
      throws IOException {
    requireDirectory(root, "class directory input");
    Path carrierRoot = root.resolve(CARRIER_PREFIX);
    if (!Files.exists(carrierRoot, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    requireDirectory(carrierRoot, "rule carrier directory");
    try (Stream<Path> stream = Files.walk(carrierRoot)) {
      for (Path path : (Iterable<Path>) stream.sorted()::iterator) {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
          continue;
        }
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
          throw invalid("Rule carrier must be a regular non-symlink file: " + path);
        }
        String name = root.relativize(path).toString().replace(File.separatorChar, '/');
        if (!isCarrier(name)) {
          throw invalid("Malformed rule carrier path " + name);
        }
        addRule(rules, name, Files.readAllBytes(path));
      }
    }
  }

  private static void collectZip(Path path, SortedMap<String, byte[]> rules) throws IOException {
    requireRegular(path, "class JAR input");
    try (ZipInputStream input = new ZipInputStream(Files.newInputStream(path))) {
      ZipEntry entry;
      while ((entry = input.getNextEntry()) != null) {
        String name = entry.getName();
        if (isCarrier(name)) {
          if (entry.isDirectory()) {
            throw invalid("Rule carrier is a ZIP directory: " + name);
          }
          addRule(rules, name, readEntry(input));
        } else if (name.startsWith(CARRIER_PREFIX) && !isCarrierDirectory(name)) {
          throw invalid("Malformed rule carrier path " + name);
        }
      }
    }
  }

  private static void addRule(SortedMap<String, byte[]> rules, String name, byte[] content) {
    validateContent(name, content);
    if (rules.put(name, content) != null) {
      throw invalid("Duplicate Fory JSON rule carrier " + name);
    }
  }

  private static void validateContent(String name, byte[] content) {
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(content))
              .toString();
    } catch (CharacterCodingException e) {
      throw invalid("Rule carrier is not UTF-8: " + name, e);
    }
    validateExactRules(name, text);
  }

  private static void validateExactRules(String name, String text) {
    boolean inBlock = false;
    boolean companionKeep = false;
    String[] lines = text.split("\\r?\\n", -1);
    for (String line : lines) {
      String value = line.trim();
      if (value.isEmpty()) {
        continue;
      }
      if (!inBlock) {
        Matcher header = KEEP_HEADER.matcher(value);
        if (!header.matches()) {
          throw invalid("Rule carrier contains a non-exact R8 directive: " + name);
        }
        if (value.startsWith("-keep,allowoptimization")
            && header.group(1).endsWith("_ForyJsonMetadata")) {
          companionKeep = true;
        }
        inBlock = true;
      } else if ("}".equals(value)) {
        inBlock = false;
      } else if (!value.endsWith(";")
          || value.startsWith("-")
          || value.indexOf('*') >= 0
          || value.indexOf('?') >= 0
          || value.indexOf('#') >= 0
          || value.indexOf('{') >= 0
          || value.indexOf('}') >= 0) {
        throw invalid("Rule carrier contains a non-exact member rule: " + name);
      }
    }
    if (inBlock) {
      throw invalid("Rule carrier has an unterminated R8 rule: " + name);
    }
    if (!companionKeep) {
      throw invalid("Rule carrier has no exact generated companion keep: " + name);
    }
  }

  private static boolean isCarrier(String name) {
    if (!name.startsWith(CARRIER_PREFIX)) {
      return false;
    }
    String file = name.substring(CARRIER_PREFIX.length());
    return file.startsWith(FILE_PREFIX)
        && file.endsWith(FILE_SUFFIX)
        && file.length() > FILE_PREFIX.length() + FILE_SUFFIX.length()
        && file.indexOf('/') < 0
        && file.indexOf('\\') < 0;
  }

  private static boolean isCarrierDirectory(String name) {
    return name.equals("META-INF/")
        || name.equals("META-INF/fory-json/")
        || name.equals(CARRIER_PREFIX);
  }

  private static byte[] readEntry(ZipInputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int count;
    while ((count = input.read(buffer)) != -1) {
      output.write(buffer, 0, count);
    }
    return output.toByteArray();
  }

  private static void copyUnchanged(Path input, Path output) throws IOException {
    Path parent = output.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
  }

  private static void requireRegular(Path path, String role) {
    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw invalid(role + " must be a regular non-symlink file: " + path);
    }
  }

  private static void requireDirectory(Path path, String role) {
    if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      throw invalid(role + " must be a regular non-symlink directory: " + path);
    }
  }

  private static GradleException invalid(String message) {
    return new GradleException(message);
  }

  private static GradleException invalid(String message, Throwable cause) {
    return new GradleException(message, cause);
  }

  private static final class AarContents {
    final SortedMap<String, byte[]> entries = new TreeMap<>();
    final SortedMap<String, byte[]> rules = new TreeMap<>();
    byte[] consumerRules = new byte[0];
  }
}

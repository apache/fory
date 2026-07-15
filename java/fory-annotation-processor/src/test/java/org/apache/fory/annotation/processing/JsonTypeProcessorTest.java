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

package org.apache.fory.annotation.processing;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Member;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.apache.fory.json.meta.GeneratedJsonCodecMeta;
import org.apache.fory.json.meta.JsonTypeUse;
import org.testng.annotations.Test;

public class JsonTypeProcessorTest {
  @Test
  public void testUnannotatedType() throws Exception {
    CompilationResult result =
        compile("test.Plain", "package test; public class Plain { int id; }");
    assertTrue(result.success, result.diagnostics());
    assertFalse(result.hasGeneratedSource("test/Plain_ForyJsonCodecMeta.java"));
    assertFalse(
        result.hasGeneratedResource("META-INF/com.android.tools/r8/fory-json-test.Plain.pro"));
  }

  @Test
  public void testPlainJsonType() throws Exception {
    CompilationResult result =
        compile(
            "test.Plain",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonType @JsonPropertyOrder({\"id\"}) public class Plain {\n"
                + "  @JsonProperty public int id;\n"
                + "  private Plain() {}\n"
                + "  @JsonCreator public Plain(@JsonProperty(\"id\") int id) { this.id = id; }\n"
                + "  public int getId() { return id; }\n"
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    assertFalse(result.hasGeneratedSource("test/Plain_ForyJsonCodecMeta.java"));
    String rules =
        result.generatedResource("META-INF/com.android.tools/r8/fory-json-test.Plain.pro");
    assertTrue(rules.contains("-keepattributes Signature,RuntimeVisibleAnnotations"), rules);
    assertTrue(rules.contains("-keepattributes MethodParameters"), rules);
    assertTrue(rules.contains("int id;"), rules);
    assertTrue(rules.contains("int getId();"), rules);
    assertTrue(rules.contains("<init>();"), rules);
    assertTrue(rules.contains("<init>(int);"), rules);
    assertFalse(rules.contains("_ForyJsonCodecMeta implements"), rules);
  }

  @Test
  public void testNestedMetadata() throws Exception {
    CompilationResult result = compile("test.Outer_Name", nestedSource());
    assertTrue(result.success, result.diagnostics());
    String source =
        result.generatedSource("test/Outer_Name$Model_With_Underscore_ForyJsonCodecMeta.java");
    assertTrue(source.contains("implements GeneratedJsonCodecMeta"), source);
    assertTrue(
        source.contains("private static final Map<Member, JsonTypeUse[]> TYPE_USES"), source);
    assertTrue(source.contains("JsonTypeUse.ARRAY_COMPONENT"), source);
    assertTrue(source.contains("getDeclaredField(\"root\")"), source);
    assertTrue(source.contains("getDeclaredField(\"values\")"), source);
    assertTrue(source.contains("getDeclaredMethod(\"getInherited\""), source);
    assertTrue(source.contains("getDeclaredMethod(\"getInterfaceValue\""), source);
    assertFalse(source.contains("getSuppressedValues"), source);
    assertFalse(source.contains("SuppressedCodec"), source);
    assertTrue(source.contains("test.Outer_Name.Codec.class"), source);
    assertFalse(source.contains("classForName("), source);
    assertFalse(source.contains("import java.lang.reflect.*"), source);
    assertFalse(source.contains("import java.util.*"), source);

    try (URLClassLoader loader = result.classLoader()) {
      Class<?> metaType =
          loader.loadClass("test.Outer_Name$Model_With_Underscore_ForyJsonCodecMeta");
      GeneratedJsonCodecMeta meta =
          metaType.asSubclass(GeneratedJsonCodecMeta.class).getConstructor().newInstance();
      Map<Member, JsonTypeUse[]> first = meta.typeUses();
      Map<Member, JsonTypeUse[]> second = meta.typeUses();
      assertTrue(first == second);
      assertTrue(first.size() >= 3, first.toString());
      JsonTypeUse root = null;
      for (Map.Entry<Member, JsonTypeUse[]> entry : first.entrySet()) {
        if (entry.getKey().getName().equals("root")) {
          root = entry.getValue()[0];
        }
      }
      assertNotNull(root);
      assertTrue(root.hasCodec());
      assertThrows(UnsupportedOperationException.class, () -> first.clear());
    }

    String rules =
        result.generatedResource(
            "META-INF/com.android.tools/r8/fory-json-test.Outer_Name$Model_With_Underscore.pro");
    assertTrue(
        rules.contains(
            "-keep,allowoptimization class test.Outer_Name$Model_With_Underscore_ForyJsonCodecMeta"),
        rules);
    assertTrue(rules.contains("-keepattributes InnerClasses,EnclosingMethod"), rules);
    assertTrue(rules.contains("allowobfuscation class test.Outer_Name$Codec"), rules);
    assertFalse(rules.contains("SuppressedCodec"), rules);
  }

  @Test
  public void testWildcardPath() throws Exception {
    CompilationResult result =
        compile(
            "test.WildcardModel",
            "package test;\n"
                + "import java.util.*;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonType public class WildcardModel {\n"
                + "  public List<? extends @JsonCodec(Codec.class) String> values;\n"
                + codecSource()
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    String source = result.generatedSource("test/WildcardModel_ForyJsonCodecMeta.java");
    assertTrue(source.contains("JsonTypeUse.WILDCARD_UPPER_BOUND"), source);
    try (URLClassLoader loader = result.classLoader()) {
      Class<?> metaType = loader.loadClass("test.WildcardModel_ForyJsonCodecMeta");
      assertThrows(
          ExceptionInInitializerError.class, () -> metaType.getConstructor().newInstance());
    }
  }

  @Test
  public void testSubtypeAndFallback() throws Exception {
    CompilationResult result =
        compile(
            "test.Base",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonType @JsonSubTypes({\n"
                + "  @JsonSubTypes.Type(value = Child.class, name = \"child\"),\n"
                + "  @JsonSubTypes.Type(className = \"external.HiddenChild\", name = \"hidden\")\n"
                + "}) public abstract class Base {}\n"
                + "class Child extends Base { public int value; }\n");
    assertTrue(result.success, result.diagnostics());
    assertTrue(
        result.hasGeneratedResource("META-INF/com.android.tools/r8/fory-json-test.Base.pro"));
    assertTrue(
        result.hasGeneratedResource("META-INF/com.android.tools/r8/fory-json-test.Child.pro"));
    String rules =
        result.generatedResource("META-INF/com.android.tools/r8/fory-json-test.Base.pro");
    assertTrue(rules.contains("-keep,allowoptimization class external.HiddenChild"), rules);
  }

  @Test
  public void testDeclarationCodecRules() throws Exception {
    CompilationResult result =
        compile(
            "test.DeclarationModel",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonCodec(DeclarationModel.Codec.class) interface Contract {}\n"
                + "@JsonType public class DeclarationModel implements Contract {\n"
                + "  private String value;\n"
                + "  @JsonCodec(Codec.class) public String getValue() { return value; }\n"
                + codecSource()
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    assertFalse(result.hasGeneratedSource("test/DeclarationModel_ForyJsonCodecMeta.java"));
    String rules =
        result.generatedResource(
            "META-INF/com.android.tools/r8/fory-json-test.DeclarationModel.pro");
    assertTrue(rules.contains("allowobfuscation class test.DeclarationModel$Codec"), rules);
    assertTrue(rules.contains("public <init>();"), rules);
    assertTrue(rules.contains("-keepattributes InnerClasses,EnclosingMethod"), rules);
  }

  @Test
  public void testInaccessibleCodec() throws Exception {
    CompilationResult result =
        compile(
            "test.HiddenCodecModel",
            "package test;\n"
                + "import java.util.*;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonType public class HiddenCodecModel {\n"
                + "  public List<@JsonCodec(Codec.class) String> values;\n"
                + codecSource()
                    .replace("public static final class Codec", "private static final class Codec")
                + "}\n");
    assertFalse(result.success, result.diagnostics());
    assertTrue(result.diagnostics().contains("is not accessible from generated metadata"));
  }

  @Test
  public void testInaccessibleMember() throws Exception {
    CompilationResult result =
        compile(
            "test.HiddenMemberModel",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "public class HiddenMemberModel {\n"
                + "  private static final class Hidden {}\n"
                + "  @JsonType public static class Model {\n"
                + "    public void setValue(@JsonCodec(Codec.class) Hidden value) {}\n"
                + "  }\n"
                + codecSource()
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    String source = result.generatedSource("test/HiddenMemberModel$Model_ForyJsonCodecMeta.java");
    assertTrue(source.contains("classForName(\"test.HiddenMemberModel$Hidden\")"), source);
    assertTrue(source.contains("test.HiddenMemberModel.Codec.class"), source);
    String rules =
        result.generatedResource(
            "META-INF/com.android.tools/r8/fory-json-test.HiddenMemberModel$Model.pro");
    assertTrue(
        rules.contains("-keep,allowoptimization class test.HiddenMemberModel$Hidden"), rules);
  }

  @Test
  public void testUnrelatedAnnotation() throws Exception {
    Map<String, String> sources = new LinkedHashMap<>();
    sources.put(
        "test.other.JsonCodec",
        "package test.other;\n"
            + "import java.lang.annotation.*;\n"
            + "@Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.RUNTIME)\n"
            + "public @interface JsonCodec {}\n");
    sources.put(
        "test.CollisionModel",
        "package test;\n"
            + "import java.util.List;\n"
            + "import org.apache.fory.json.annotation.JsonType;\n"
            + "import test.other.JsonCodec;\n"
            + "@JsonType public class CollisionModel {\n"
            + "  public List<@JsonCodec String> values;\n"
            + "}\n");
    CompilationResult result = compile(sources);
    assertTrue(result.success, result.diagnostics());
    assertFalse(result.hasGeneratedSource("test/CollisionModel_ForyJsonCodecMeta.java"));
    String rules =
        result.generatedResource("META-INF/com.android.tools/r8/fory-json-test.CollisionModel.pro");
    assertFalse(rules.contains("test.other.JsonCodec"), rules);
  }

  @Test
  public void testIndependentDispatch() throws Exception {
    CompilationResult result =
        compile(
            "test.Both",
            "package test;\n"
                + "import org.apache.fory.annotation.ForyStruct;\n"
                + "import org.apache.fory.json.annotation.JsonType;\n"
                + "@ForyStruct @JsonType public class Both { public int id; public Both() {} }\n");
    assertTrue(result.success, result.diagnostics());
    assertTrue(result.hasGeneratedSource("test/Both_ForySerializer.java"));
    assertTrue(
        result.hasGeneratedResource("META-INF/proguard/fory-static-generated-test.Both.pro"));
    assertTrue(
        result.hasGeneratedResource("META-INF/com.android.tools/r8/fory-json-test.Both.pro"));
  }

  @Test
  public void testEnumConstants() throws Exception {
    CompilationResult result =
        compile(
            "test.Status",
            "package test;\n"
                + "import org.apache.fory.json.annotation.JsonType;\n"
                + "@JsonType public enum Status { READY, DONE }\n");
    assertTrue(result.success, result.diagnostics());
    String rules =
        result.generatedResource("META-INF/com.android.tools/r8/fory-json-test.Status.pro");
    assertTrue(rules.contains("test.Status READY;"), rules);
    assertTrue(rules.contains("test.Status DONE;"), rules);
    assertFalse(rules.contains("$VALUES"), rules);
  }

  @Test
  public void testValidationMemberRules() throws Exception {
    CompilationResult result =
        compile(
            "test.ValidationModel",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "class ValidationBase {\n"
                + "  @JsonProperty private String getHidden() { return null; }\n"
                + "}\n"
                + "@JsonType public class ValidationModel extends ValidationBase {\n"
                + "  public ValidationModel() {}\n"
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    String rules =
        result.generatedResource(
            "META-INF/com.android.tools/r8/fory-json-test.ValidationModel.pro");
    assertTrue(rules.contains("class test.ValidationBase"), rules);
    assertTrue(rules.contains("java.lang.String getHidden();"), rules);
  }

  @Test
  public void testDeterministicOutput() throws Exception {
    CompilationResult first = compile("test.Outer_Name", nestedSource());
    CompilationResult second = compile("test.Outer_Name", nestedSource());
    assertTrue(first.success, first.diagnostics());
    assertTrue(second.success, second.diagnostics());
    String metaPath = "test/Outer_Name$Model_With_Underscore_ForyJsonCodecMeta.java";
    String rulesPath =
        "META-INF/com.android.tools/r8/fory-json-test.Outer_Name$Model_With_Underscore.pro";
    assertEquals(first.generatedSource(metaPath), second.generatedSource(metaPath));
    assertEquals(first.generatedResource(rulesPath), second.generatedResource(rulesPath));
  }

  private static String nestedSource() {
    return "package test;\n"
        + "import java.util.*;\n"
        + "import org.apache.fory.json.annotation.*;\n"
        + "public class Outer_Name {\n"
        + "  public static class CodecBase {\n"
        + "    public java.lang.@JsonCodec(Codec.class) String root;\n"
        + "    public List<@JsonCodec(Codec.class) String[]> values;\n"
        + "    public List<@JsonCodec(Codec.class) String> getInherited() { return null; }\n"
        + "  }\n"
        + "  public interface Contract {\n"
        + "    default List<@JsonCodec(Codec.class) String> getInterfaceValue() { return null; }\n"
        + "  }\n"
        + "  public interface Suppressed {\n"
        + "    List<@JsonCodec(SuppressedCodec.class) String> getSuppressedValues();\n"
        + "  }\n"
        + "  @JsonType public static class Model_With_Underscore extends CodecBase implements Contract, Suppressed {\n"
        + "    public Model_With_Underscore() {}\n"
        + "    public List<String> getSuppressedValues() { return null; }\n"
        + "  }\n"
        + codecSource()
        + codecSource("SuppressedCodec")
        + "}\n";
  }

  private static String codecSource() {
    return codecSource("Codec");
  }

  private static String codecSource(String name) {
    return "  public static final class "
        + name
        + " implements org.apache.fory.json.codec.JsonValueCodec<String> {\n"
        + "    public "
        + name
        + "() {}\n"
        + "    public void writeString(org.apache.fory.json.writer.StringJsonWriter w, String v) {}\n"
        + "    public void writeUtf8(org.apache.fory.json.writer.Utf8JsonWriter w, String v) {}\n"
        + "    public String readLatin1(org.apache.fory.json.reader.Latin1JsonReader r) { return null; }\n"
        + "    public String readUtf16(org.apache.fory.json.reader.Utf16JsonReader r) { return null; }\n"
        + "    public String readUtf8(org.apache.fory.json.reader.Utf8JsonReader r) { return null; }\n"
        + "  }\n";
  }

  private static CompilationResult compile(String typeName, String source) throws IOException {
    Map<String, String> sources = new LinkedHashMap<>();
    sources.put(typeName, source);
    return compile(sources);
  }

  private static CompilationResult compile(Map<String, String> sources) throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "Tests require a JDK compiler");
    Path root = Files.createTempDirectory("fory-json-processor-test");
    Path sourceRoot = root.resolve("src");
    Path classRoot = root.resolve("classes");
    Path generatedRoot = root.resolve("generated");
    Files.createDirectories(sourceRoot);
    Files.createDirectories(classRoot);
    Files.createDirectories(generatedRoot);
    List<java.io.File> sourceFiles = new ArrayList<>();
    for (Map.Entry<String, String> source : sources.entrySet()) {
      Path sourceFile = sourceRoot.resolve(source.getKey().replace('.', '/') + ".java");
      Files.createDirectories(sourceFile.getParent());
      Files.write(sourceFile, source.getValue().getBytes(StandardCharsets.UTF_8));
      sourceFiles.add(sourceFile.toFile());
    }
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager fileManager =
        compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
      Iterable<? extends JavaFileObject> units =
          fileManager.getJavaFileObjectsFromFiles(sourceFiles);
      List<String> options =
          Arrays.asList(
              "-classpath",
              System.getProperty("java.class.path"),
              "-d",
              classRoot.toString(),
              "-s",
              generatedRoot.toString());
      JavaCompiler.CompilationTask task =
          compiler.getTask(null, fileManager, diagnostics, options, null, units);
      task.setProcessors(Collections.singletonList(new ForyStructProcessor()));
      return new CompilationResult(
          classRoot, generatedRoot, task.call(), diagnostics.getDiagnostics());
    }
  }

  private static final class CompilationResult {
    final Path classRoot;
    final Path generatedRoot;
    final boolean success;
    final List<Diagnostic<? extends JavaFileObject>> diagnostics;

    CompilationResult(
        Path classRoot,
        Path generatedRoot,
        boolean success,
        List<Diagnostic<? extends JavaFileObject>> diagnostics) {
      this.classRoot = classRoot;
      this.generatedRoot = generatedRoot;
      this.success = success;
      this.diagnostics = new ArrayList<>(diagnostics);
    }

    URLClassLoader classLoader() throws IOException {
      URL[] urls = {classRoot.toUri().toURL()};
      return new URLClassLoader(urls, JsonTypeProcessorTest.class.getClassLoader());
    }

    boolean hasGeneratedSource(String relativePath) {
      return Files.exists(generatedRoot.resolve(relativePath));
    }

    boolean hasGeneratedResource(String relativePath) {
      return Files.exists(classRoot.resolve(relativePath));
    }

    String generatedSource(String relativePath) throws IOException {
      return new String(
          Files.readAllBytes(generatedRoot.resolve(relativePath)), StandardCharsets.UTF_8);
    }

    String generatedResource(String relativePath) throws IOException {
      return new String(
          Files.readAllBytes(classRoot.resolve(relativePath)), StandardCharsets.UTF_8);
    }

    String diagnostics() {
      StringBuilder builder = new StringBuilder();
      for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
        builder.append(diagnostic).append('\n');
      }
      return builder.toString();
    }
  }
}

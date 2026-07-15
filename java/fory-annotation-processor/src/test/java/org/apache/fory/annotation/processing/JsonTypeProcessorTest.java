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
import static org.testng.Assert.assertTrue;

import java.io.IOException;
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
import org.testng.SkipException;
import org.testng.annotations.Test;

public class JsonTypeProcessorTest {
  private static final String RULE_PREFIX = "META-INF/proguard/fory-json-";

  @Test
  public void unannotatedType() throws Exception {
    CompilationResult result =
        compile("test.Plain", "package test; public class Plain { int id; }");
    assertTrue(result.success, result.diagnostics());
    assertFalse(result.hasGeneratedResource(RULE_PREFIX + "test.Plain.pro"));
  }

  @Test
  public void jsonTypeRules() throws Exception {
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
    String rules = result.generatedResource(RULE_PREFIX + "test.Plain.pro");
    assertTrue(rules.contains("-keepattributes Signature,RuntimeVisibleAnnotations"), rules);
    assertTrue(rules.contains("RuntimeVisibleParameterAnnotations"), rules);
    assertTrue(rules.contains("-keepattributes AnnotationDefault"), rules);
    assertTrue(rules.contains("-keepattributes MethodParameters"), rules);
    assertTrue(rules.contains("int id;"), rules);
    assertTrue(rules.contains("int getId();"), rules);
    assertTrue(rules.contains("<init>();"), rules);
    assertTrue(rules.contains("<init>(int);"), rules);
  }

  @Test
  public void codecMemberRules() throws Exception {
    CompilationResult result = compile("test.CodecModel", codecMemberSource());
    assertTrue(result.success, result.diagnostics());
    String rules = result.generatedResource(RULE_PREFIX + "test.CodecModel.pro");
    for (String codec :
        Arrays.asList(
            "RootCodec",
            "CollectionElementCodec",
            "ArrayElementCodec",
            "AtomicArrayElementCodec",
            "ContentCodec",
            "KeyCodec",
            "MapValueCodec",
            "GetterCodec",
            "SetterCodec",
            "CreatorCodec",
            "AnyGetterCodec",
            "AnySetterCodec")) {
      assertTrue(rules.contains("class test.CodecModel$" + codec + " { public <init>(); }"), rules);
    }
    assertFalse(rules.contains("JsonCodec$NoJsonValueCodec { public <init>(); }"), rules);
    assertFalse(rules.contains("JsonCodec$NoMapKeyCodec { public <init>(); }"), rules);
    assertTrue(rules.contains("java.lang.String getValue();"), rules);
    assertTrue(rules.contains("void setValue(java.lang.String);"), rules);
    assertTrue(rules.contains("<init>(java.lang.String);"), rules);
    assertTrue(rules.contains("void putExtra(java.lang.String,java.lang.Object);"), rules);
    assertTrue(rules.contains("class java.util.ArrayList { public <init>(); }"), rules);
    assertTrue(rules.contains("class java.util.HashMap { public <init>(); }"), rules);
  }

  @Test
  public void hierarchyRules() throws Exception {
    CompilationResult result = compile("test.Hierarchy", hierarchySource());
    assertTrue(result.success, result.diagnostics());
    String rules = result.generatedResource(RULE_PREFIX + "test.Hierarchy.pro");
    assertTrue(rules.contains("class test.Hierarchy$DeclarationCodec"), rules);
    assertTrue(rules.contains("class test.Hierarchy$InheritedCodec"), rules);
    assertTrue(rules.contains("class test.Hierarchy$InterfaceCodec"), rules);
    assertFalse(rules.contains("class test.Hierarchy$SuppressedCodec { public <init>(); }"), rules);
    assertTrue(rules.contains("class test.Base"), rules);
    assertTrue(rules.contains("class test.Contract"), rules);
  }

  @Test
  public void typeDeclarationCodecRules() throws Exception {
    CompilationResult result =
        compile(
            "test.DeclarationModel",
            "package test;\n"
                + "import java.util.List;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonCodec(DeclarationModel.ShadowedCodec.class) interface BaseContract {}\n"
                + "@JsonCodec(DeclarationModel.InheritedCodec.class)\n"
                + "interface ValueContract extends BaseContract {}\n"
                + "@JsonCodec(DeclarationModel.DirectCodec.class)\n"
                + "class DirectValue implements ValueContract {}\n"
                + "class InheritedValue implements ValueContract {}\n"
                + "@JsonCodec(DeclarationModel.ParameterCodec.class) class ParameterValue {}\n"
                + "@JsonType public class DeclarationModel {\n"
                + "  public DirectValue direct;\n"
                + "  public InheritedValue inherited;\n"
                + "  public List<DirectValue> nested;\n"
                + "  @JsonCreator public DeclarationModel(\n"
                + "      @JsonProperty(\"parameter\") ParameterValue parameter) {}\n"
                + valueCodecs("DirectCodec", "InheritedCodec", "ParameterCodec", "ShadowedCodec")
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    String rules = result.generatedResource(RULE_PREFIX + "test.DeclarationModel.pro");
    assertTrue(
        rules.contains("class test.DeclarationModel$DirectCodec { public <init>(); }"), rules);
    assertTrue(
        rules.contains("class test.DeclarationModel$InheritedCodec { public <init>(); }"), rules);
    assertTrue(
        rules.contains("class test.DeclarationModel$ParameterCodec { public <init>(); }"), rules);
    assertTrue(rules.contains("class test.DirectValue"), rules);
    assertTrue(rules.contains("class test.ValueContract"), rules);
    assertTrue(rules.contains("class test.ParameterValue"), rules);
    assertFalse(
        rules.contains("class test.DeclarationModel$ShadowedCodec { public <init>(); }"), rules);
    assertFalse(rules.contains("class test.BaseContract"), rules);
  }

  @Test
  public void genericMemberRules() throws Exception {
    CompilationResult result =
        compile(
            "test.GenericRuleModel",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonCodec(GenericRuleModel.FieldCodec.class) class FieldValue {}\n"
                + "@JsonCodec(GenericRuleModel.GetterCodec.class) class GetterValue {}\n"
                + "@JsonCodec(GenericRuleModel.SetterCodec.class) class SetterValue {}\n"
                + "class GenericBase<F, G, S> {\n"
                + "  public F field;\n"
                + "  public G getGetter() { return null; }\n"
                + "  public void setSetter(S value) {}\n"
                + "}\n"
                + "@JsonType public class GenericRuleModel\n"
                + "    extends GenericBase<FieldValue, GetterValue, SetterValue> {\n"
                + "  public GenericRuleModel() {}\n"
                + valueCodecs("FieldCodec", "GetterCodec", "SetterCodec")
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    String rules = result.generatedResource(RULE_PREFIX + "test.GenericRuleModel.pro");
    for (String codec : Arrays.asList("FieldCodec", "GetterCodec", "SetterCodec")) {
      assertTrue(
          rules.contains("class test.GenericRuleModel$" + codec + " { public <init>(); }"), rules);
    }
    assertTrue(rules.contains("class test.FieldValue"), rules);
    assertTrue(rules.contains("class test.GetterValue"), rules);
    assertTrue(rules.contains("class test.SetterValue"), rules);
  }

  @Test
  public void validationRules() throws Exception {
    CompilationResult result =
        compile(
            "test.ValidationModel",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "class ValidationBase {\n"
                + "  @JsonProperty private String getHidden() { return null; }\n"
                + "}\n"
                + "@JsonType public class ValidationModel extends ValidationBase {\n"
                + "  @JsonCodec(InvalidFieldCodec.class) private static String invalid;\n"
                + "  public void unrelated(@JsonCodec(InvalidParameterCodec.class) String value) {}\n"
                + "  public ValidationModel() {}\n"
                + valueCodecs("InvalidFieldCodec", "InvalidParameterCodec")
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    String rules = result.generatedResource(RULE_PREFIX + "test.ValidationModel.pro");
    assertTrue(rules.contains("java.lang.String getHidden();"), rules);
    assertTrue(rules.contains("java.lang.String invalid;"), rules);
    assertTrue(rules.contains("void unrelated(java.lang.String);"), rules);
    assertTrue(rules.contains("class test.ValidationModel$InvalidFieldCodec"), rules);
    assertTrue(rules.contains("class test.ValidationModel$InvalidParameterCodec"), rules);
  }

  @Test
  public void recordRules() throws Exception {
    assumeRecordSupport();
    CompilationResult result =
        compile(
            "test.CodecRecord",
            "package test;\n"
                + "import java.util.List;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonType public record CodecRecord(\n"
                + "    @JsonCodec(elementCodec = ElementCodec.class) List<String> values) {\n"
                + valueCodec("ElementCodec")
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    String rules = result.generatedResource(RULE_PREFIX + "test.CodecRecord.pro");
    assertTrue(rules.contains("java.util.List values;"), rules);
    assertTrue(rules.contains("java.util.List values();"), rules);
    assertTrue(rules.contains("<init>(java.util.List);"), rules);
    assertTrue(rules.contains("class test.CodecRecord$ElementCodec { public <init>(); }"), rules);
  }

  @Test
  public void subtypeRules() throws Exception {
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
    assertTrue(result.hasGeneratedResource(RULE_PREFIX + "test.Base.pro"));
    assertTrue(result.hasGeneratedResource(RULE_PREFIX + "test.Child.pro"));
    String rules = result.generatedResource(RULE_PREFIX + "test.Base.pro");
    assertTrue(rules.contains("-keep,allowoptimization class external.HiddenChild"), rules);
  }

  @Test
  public void enumRules() throws Exception {
    CompilationResult result =
        compile(
            "test.Status",
            "package test;\n"
                + "import org.apache.fory.json.annotation.JsonType;\n"
                + "@JsonType public enum Status { READY, DONE }\n");
    assertTrue(result.success, result.diagnostics());
    String rules = result.generatedResource(RULE_PREFIX + "test.Status.pro");
    assertTrue(rules.contains("test.Status READY;"), rules);
    assertTrue(rules.contains("test.Status DONE;"), rules);
    assertFalse(rules.contains("$VALUES"), rules);
  }

  @Test
  public void independentProcessors() throws Exception {
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
    assertTrue(result.hasGeneratedResource(RULE_PREFIX + "test.Both.pro"));
  }

  @Test
  public void deterministicRules() throws Exception {
    CompilationResult first = compile("test.CodecModel", codecMemberSource());
    CompilationResult second = compile("test.CodecModel", codecMemberSource());
    assertTrue(first.success, first.diagnostics());
    assertTrue(second.success, second.diagnostics());
    String path = RULE_PREFIX + "test.CodecModel.pro";
    assertEquals(first.generatedResource(path), second.generatedResource(path));
  }

  @Test
  public void valueAndRawRules() throws Exception {
    Map<String, String> sources = new LinkedHashMap<>();
    sources.put(
        "test.ValueModel",
        "package test;\n"
            + "import org.apache.fory.json.annotation.*;\n"
            + "@JsonType public final class ValueModel {\n"
            + "  private final String text;\n"
            + "  @JsonCreator public ValueModel(String text) { this.text = text; }\n"
            + "  @JsonValue @JsonRawValue public String value() { return text; }\n"
            + "}\n");
    sources.put(
        "test.RawModel",
        "package test;\n"
            + "import org.apache.fory.json.annotation.*;\n"
            + "@JsonType public final class RawModel {\n"
            + "  @JsonRawValue public String body;\n"
            + "  @JsonRawValue public byte[] bytes;\n"
            + "  private String other;\n"
            + "  @JsonRawValue public String getOther() { return other; }\n"
            + "  public void setOther(String other) { this.other = other; }\n"
            + "}\n");
    CompilationResult result = compile(sources);
    assertTrue(result.success, result.diagnostics());

    String valueRules = result.generatedResource(RULE_PREFIX + "test.ValueModel.pro");
    assertTrue(valueRules.contains("java.lang.String value();"), valueRules);
    assertTrue(valueRules.contains("<init>(java.lang.String);"), valueRules);
    assertTrue(
        valueRules.contains("@interface org.apache.fory.json.annotation.JsonValue"), valueRules);
    assertTrue(
        valueRules.contains("@interface org.apache.fory.json.annotation.JsonRawValue"), valueRules);

    String rawRules = result.generatedResource(RULE_PREFIX + "test.RawModel.pro");
    assertTrue(rawRules.contains("java.lang.String body;"), rawRules);
    assertTrue(rawRules.contains("byte[] bytes;"), rawRules);
    assertTrue(rawRules.contains("java.lang.String getOther();"), rawRules);
    assertTrue(
        rawRules.contains("@interface org.apache.fory.json.annotation.JsonRawValue"), rawRules);
  }

  @Test
  public void valueOverrideSuppression() throws Exception {
    CompilationResult result =
        compile(
            "test.ValueChild",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "class ValueBase {\n"
                + "  @JsonValue public String value() { return \"base\"; }\n"
                + "}\n"
                + "@JsonType public final class ValueChild extends ValueBase {\n"
                + "  @Override public String value() { return \"child\"; }\n"
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    String rules = result.generatedResource(RULE_PREFIX + "test.ValueChild.pro");
    assertFalse(rules.contains("JsonValue"), rules);
    assertFalse(rules.contains("value();"), rules);
  }

  private static String codecMemberSource() {
    return "package test;\n"
        + "import java.util.*;\n"
        + "import java.util.concurrent.atomic.AtomicReferenceArray;\n"
        + "import org.apache.fory.json.annotation.*;\n"
        + "@JsonType public class CodecModel {\n"
        + "  @JsonCodec(RootCodec.class) public String root;\n"
        + "  @JsonCodec(elementCodec = CollectionElementCodec.class) public List<String> list;\n"
        + "  @JsonCodec(elementCodec = ArrayElementCodec.class) public String[] array;\n"
        + "  @JsonCodec(elementCodec = AtomicArrayElementCodec.class)\n"
        + "  public AtomicReferenceArray<String> atomicArray;\n"
        + "  @JsonCodec(contentCodec = ContentCodec.class) public Optional<String> optional;\n"
        + "  @JsonCodec(keyCodec = KeyCodec.class, valueCodec = MapValueCodec.class)\n"
        + "  public Map<String, String> map;\n"
        + "  public ArrayList<String> concrete;\n"
        + "  public List<HashMap<String, String>> nestedConcrete;\n"
        + "  private String value;\n"
        + "  @JsonCodec(GetterCodec.class) public String getValue() { return value; }\n"
        + "  public void setValue(@JsonCodec(SetterCodec.class) String value) { this.value = value; }\n"
        + "  @JsonAnyGetter @JsonCodec(valueCodec = AnyGetterCodec.class)\n"
        + "  public Map<String, Object> getExtra() { return null; }\n"
        + "  @JsonAnySetter public void putExtra(\n"
        + "      String name, @JsonCodec(AnySetterCodec.class) Object value) {}\n"
        + "  @JsonCreator public CodecModel(\n"
        + "      @JsonProperty(\"created\") @JsonCodec(CreatorCodec.class) String created) {}\n"
        + valueCodecs(
            "RootCodec",
            "CollectionElementCodec",
            "ArrayElementCodec",
            "AtomicArrayElementCodec",
            "ContentCodec",
            "MapValueCodec",
            "GetterCodec",
            "SetterCodec",
            "CreatorCodec",
            "AnyGetterCodec",
            "AnySetterCodec")
        + mapKeyCodec("KeyCodec")
        + "}\n";
  }

  private static String hierarchySource() {
    return "package test;\n"
        + "import org.apache.fory.json.annotation.*;\n"
        + "@JsonCodec(Hierarchy.DeclarationCodec.class) interface Contract {}\n"
        + "class Base {\n"
        + "  @JsonCodec(Hierarchy.InheritedCodec.class) public String getInherited() { return null; }\n"
        + "  @JsonCodec(Hierarchy.SuppressedCodec.class) public String getSuppressed() { return null; }\n"
        + "}\n"
        + "interface Extra {\n"
        + "  @JsonCodec(Hierarchy.InterfaceCodec.class)\n"
        + "  default String getInterfaceValue() { return null; }\n"
        + "}\n"
        + "@JsonType public class Hierarchy extends Base implements Contract, Extra {\n"
        + "  public Hierarchy() {}\n"
        + "  @Override public String getSuppressed() { return null; }\n"
        + valueCodecs("DeclarationCodec", "InheritedCodec", "SuppressedCodec", "InterfaceCodec")
        + "}\n";
  }

  private static String valueCodecs(String... names) {
    StringBuilder builder = new StringBuilder();
    for (String name : names) {
      builder.append(valueCodec(name));
    }
    return builder.toString();
  }

  private static String valueCodec(String name) {
    return "  public static final class "
        + name
        + " implements org.apache.fory.json.codec.JsonValueCodec<Object> {\n"
        + "    public "
        + name
        + "() {}\n"
        + "    public void writeString(org.apache.fory.json.writer.StringJsonWriter w, Object v) {}\n"
        + "    public void writeUtf8(org.apache.fory.json.writer.Utf8JsonWriter w, Object v) {}\n"
        + "    public Object readLatin1(org.apache.fory.json.reader.Latin1JsonReader r) { return null; }\n"
        + "    public Object readUtf16(org.apache.fory.json.reader.Utf16JsonReader r) { return null; }\n"
        + "    public Object readUtf8(org.apache.fory.json.reader.Utf8JsonReader r) { return null; }\n"
        + "  }\n";
  }

  private static String mapKeyCodec(String name) {
    return "  public static final class "
        + name
        + " implements org.apache.fory.json.codec.MapKeyCodec {\n"
        + "    public "
        + name
        + "() {}\n"
        + "    public String toName(Object key) { return key.toString(); }\n"
        + "    public Object fromName(String name) { return name; }\n"
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

  private static void assumeRecordSupport() {
    String version = System.getProperty("java.specification.version");
    if (version.startsWith("1.")) {
      version = version.substring(2);
    }
    int dotIndex = version.indexOf('.');
    if (dotIndex >= 0) {
      version = version.substring(0, dotIndex);
    }
    if (Integer.parseInt(version) < 16) {
      throw new SkipException("Record source tests require JDK 16 or newer");
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

    boolean hasGeneratedSource(String relativePath) {
      return Files.exists(generatedRoot.resolve(relativePath));
    }

    boolean hasGeneratedResource(String relativePath) {
      return Files.exists(classRoot.resolve(relativePath));
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

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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.meta.JsonCreatorInvoker;
import org.apache.fory.json.meta.JsonDecodedMetadata;
import org.apache.fory.json.meta.JsonFieldAccessor;
import org.apache.fory.json.meta.JsonMetadataDecoder;
import org.apache.fory.json.meta.JsonMetadataFormat;
import org.apache.fory.json.meta.JsonTypeMetadata;
import org.apache.fory.json.meta.JsonTypeMetadataData;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

public class ForyJsonProcessorTest {
  @Test
  public void testGeneratedMetadataAndOperations() throws Exception {
    CompilationResult result = compileModel();
    Assert.assertTrue(result.success, result.diagnostics());
    String source = result.generatedSource("test/Model_ForyJsonMetadata.java");
    Assert.assertTrue(source.contains("extends JsonFieldAccessor"), source);
    Assert.assertTrue(source.contains("extends JsonCreatorInvoker"), source);
    Assert.assertTrue(source.contains("extends JsonAnySetterInvoker"), source);

    try (URLClassLoader loader = result.classLoader()) {
      Class<?> target = loader.loadClass("test.Model");
      Class<?> metadataClass = loader.loadClass("test.Model_ForyJsonMetadata");
      JsonTypeMetadata metadata =
          (JsonTypeMetadata) metadataClass.getConstructor(Class.class).newInstance(target);
      JsonTypeMetadataData transport =
          (JsonTypeMetadataData) metadata.metadata(JsonTypeMetadata.OBJECT);
      JsonDecodedMetadata decoded =
          JsonMetadataDecoder.decode(target, JsonTypeMetadata.OBJECT, transport);
      for (int section = 0; section < JsonTypeMetadata.SECTION_COUNT; section++) {
        JsonMetadataDecoder.decode(
            target, section, (JsonTypeMetadataData) metadata.metadata(section));
      }
      JsonDecodedMetadata.Field id = field(decoded, "id");
      Assert.assertEquals(id.descriptor(), "I");
      Assert.assertTrue(id.operation() >= 0);
      JsonFieldAccessor accessor =
          (JsonFieldAccessor)
              metadata.metadataOperation(
                  JsonTypeMetadata.OBJECT, decoded.operation(id.operation()).directIndex());
      Object model = target.getConstructor(int.class).newInstance(7);
      Assert.assertEquals(accessor.getInt(model), 7);
      accessor.putInt(model, 11);
      Assert.assertEquals(target.getField("id").getInt(model), 11);
      Assert.assertEquals(field(decoded, "secret").descriptor(), "Ljava/lang/String;");
      Assert.assertEquals(
          method(decoded, "attributes").flags() & JsonMetadataFormat.JSON_ANY_GETTER,
          JsonMetadataFormat.JSON_ANY_GETTER);
    }

    String rules =
        result.generatedResource("META-INF/fory-json/r8/fory-json-generated-test.Model.pro");
    Assert.assertTrue(rules.contains("-keep,allowoptimization class test.Model"), rules);
    Assert.assertTrue(rules.contains("class test.Model_ForyJsonMetadata"), rules);
    Assert.assertTrue(rules.contains("java.lang.String secret;"), rules);
  }

  @Test
  public void testAndroidAnyRuntime() throws Exception {
    CompilationResult methods =
        compile(
            "test.AnyMethodModel",
            "package test;\n"
                + "import java.util.*;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonType public class AnyMethodModel {\n"
                + "  public int id;\n"
                + "  private final Map<String, Integer> extra = new LinkedHashMap<>();\n"
                + "  @JsonAnyGetter public Map<String, Integer> extra() { return extra; }\n"
                + "  @JsonAnySetter public void put(String name, Integer value) {\n"
                + "    extra.put(name, value);\n"
                + "  }\n"
                + "}\n");
    Assert.assertTrue(methods.success, methods.diagnostics());
    runAndroidAnyProbe(methods, "test.AnyMethodModel", "method");

    CompilationResult field =
        compile(
            "test.AnyFieldModel",
            "package test;\n"
                + "import java.util.*;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonType public class AnyFieldModel {\n"
                + "  public int id;\n"
                + "  @JsonAnyProperty public final Map<String, Integer> extra = new LinkedHashMap<>();\n"
                + "}\n");
    Assert.assertTrue(field.success, field.diagnostics());
    runAndroidAnyProbe(field, "test.AnyFieldModel", "field");
  }

  @Test
  public void testInvalidAnySetterFails() throws Exception {
    CompilationResult result =
        compile(
            "test.InvalidModel",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonType public class InvalidModel {\n"
                + "  public InvalidModel() {}\n"
                + "  @JsonAnySetter public void put(int name, Object value) {}\n"
                + "}\n");
    Assert.assertFalse(result.success);
    Assert.assertTrue(result.diagnostics().contains("Invalid @JsonAnySetter"));
  }

  @Test
  public void testInvalidAnnotatedMethodsFail() throws Exception {
    assertCompilationFails(
        "test.InvalidProperty",
        "package test;\n"
            + "import org.apache.fory.json.annotation.*;\n"
            + "@JsonType public class InvalidProperty {\n"
            + "  @JsonProperty public String value(int index) { return null; }\n"
            + "}\n",
        "@JsonProperty requires a JSON getter or setter");
    assertCompilationFails(
        "test.ConflictingAny",
        "package test;\n"
            + "import org.apache.fory.json.annotation.*;\n"
            + "@JsonType public class ConflictingAny {\n"
            + "  @JsonAnyGetter @JsonAnySetter public void values() {}\n"
            + "}\n",
        "Conflicting JSON Any method annotations");
    assertCompilationFails(
        "test.PropertyAnySetter",
        "package test;\n"
            + "import org.apache.fory.json.annotation.*;\n"
            + "@JsonType public class PropertyAnySetter {\n"
            + "  @JsonProperty @JsonAnySetter public void put(String name, Object value) {}\n"
            + "}\n",
        "@JsonProperty is not supported on a JSON Any method");
    assertCompilationFails(
        "test.InvalidAnyGetter",
        "package test;\n"
            + "import java.util.List;\n"
            + "import org.apache.fory.json.annotation.*;\n"
            + "@JsonType public class InvalidAnyGetter {\n"
            + "  @JsonAnyGetter public List<Object> values() { return null; }\n"
            + "}\n",
        "Invalid @JsonAnyGetter");
  }

  @Test
  public void testBuiltInFamilyOmitsObjectSection() throws Exception {
    CompilationResult result =
        compile(
            "test.StringList",
            "package test;\n"
                + "import java.util.ArrayList;\n"
                + "import org.apache.fory.json.annotation.JsonType;\n"
                + "@JsonType public class StringList extends ArrayList<String> {}\n");
    Assert.assertTrue(result.success, result.diagnostics());
    String source = result.generatedSource("test/StringList_ForyJsonMetadata.java");
    Assert.assertTrue(source.contains("case 2: return Section2.DATA"), source);
    try (URLClassLoader loader = result.classLoader()) {
      Assert.assertEquals(
          decode(result, loader, "test.StringList", JsonTypeMetadata.OBJECT).fieldCount(), 0);
      Assert.assertEquals(
          decode(result, loader, "test.StringList", JsonTypeMetadata.SUBTYPES).subtypeTableCount(),
          0);
      Assert.assertEquals(
          decode(result, loader, "test.StringList", JsonTypeMetadata.DECLARATIONS).operationCount(),
          0);
    }
  }

  @Test
  public void testDeclarationCodecGeneratesFactory() throws Exception {
    CompilationResult result =
        compile(
            "test.CodecModel",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "import org.apache.fory.json.codec.JsonValueCodec;\n"
                + "import org.apache.fory.json.reader.*;\n"
                + "import org.apache.fory.json.writer.*;\n"
                + "@JsonType @JsonCodec(CodecModel.Codec.class) public class CodecModel {\n"
                + "  public static final class Codec implements JsonValueCodec<CodecModel> {\n"
                + "    public Codec() {}\n"
                + "    public void writeString(StringJsonWriter w, CodecModel v) {}\n"
                + "    public void writeUtf8(Utf8JsonWriter w, CodecModel v) {}\n"
                + "    public CodecModel readLatin1(Latin1JsonReader r) { return null; }\n"
                + "    public CodecModel readUtf16(Utf16JsonReader r) { return null; }\n"
                + "    public CodecModel readUtf8(Utf8JsonReader r) { return null; }\n"
                + "  }\n"
                + "}\n");
    Assert.assertTrue(result.success, result.diagnostics());
    String source = result.generatedSource("test/CodecModel_ForyJsonMetadata.java");
    Assert.assertTrue(source.contains("extends JsonCodecFactory"), source);
    Assert.assertTrue(source.contains("case 2: return Section2.DATA"), source);
  }

  @Test
  public void testNestedTypeUseCodec() throws Exception {
    CompilationResult result =
        compile(
            "test.TypeUseModel",
            "package test;\n"
                + "import java.util.*;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "import org.apache.fory.json.codec.JsonValueCodec;\n"
                + "import org.apache.fory.json.reader.*;\n"
                + "import org.apache.fory.json.writer.*;\n"
                + "@JsonType public class TypeUseModel {\n"
                + "  public List<@JsonCodec(Codec.class) String> values;\n"
                + "  public TypeUseModel() {}\n"
                + "  public static final class Codec implements JsonValueCodec<String> {\n"
                + "    public Codec() {}\n"
                + "    public void writeString(StringJsonWriter w, String v) {}\n"
                + "    public void writeUtf8(Utf8JsonWriter w, String v) {}\n"
                + "    public String readLatin1(Latin1JsonReader r) { return null; }\n"
                + "    public String readUtf16(Utf16JsonReader r) { return null; }\n"
                + "    public String readUtf8(Utf8JsonReader r) { return null; }\n"
                + "  }\n"
                + "}\n");
    Assert.assertTrue(result.success, result.diagnostics());
    try (URLClassLoader loader = result.classLoader()) {
      JsonDecodedMetadata metadata =
          decode(result, loader, "test.TypeUseModel", JsonTypeMetadata.OBJECT);
      JsonTypeNodeAssert.hasNestedCodec(metadata, field(metadata, "values").typeNode());
    }
  }

  @Test
  public void testSubtypeForms() throws Exception {
    CompilationResult result =
        compile(
            "test.Base",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonType @JsonSubTypes(value={\n"
                + "  @JsonSubTypes.Type(value=Base.Child.class,name=\"literal\"),\n"
                + "  @JsonSubTypes.Type(className=\"test.ExternalChild\",name=\"named\")},\n"
                + "  inclusion=JsonSubTypes.Inclusion.WRAPPER_ARRAY)\n"
                + "public abstract class Base { public static final class Child extends Base {} }\n");
    Assert.assertTrue(result.success, result.diagnostics());
    try (URLClassLoader loader = result.classLoader()) {
      JsonDecodedMetadata metadata = decode(result, loader, "test.Base", JsonTypeMetadata.SUBTYPES);
      Assert.assertEquals(metadata.subtypeTableCount(), 1);
      Assert.assertEquals(metadata.subtypeTable(0).entryCount(), 2);
      Assert.assertTrue(metadata.subtypeTable(0).entry(0).typeToken() >= 0);
      Assert.assertEquals(metadata.subtypeTable(0).entry(1).className(), "test.ExternalChild");
    }
    String rules =
        result.generatedResource("META-INF/fory-json/r8/fory-json-generated-test.Base.pro");
    Assert.assertTrue(rules.contains("class test.ExternalChild"), rules);
  }

  @Test
  public void testRecordAccessorAndConstructor() throws Exception {
    if (!recordCompilerAvailable()) {
      throw new SkipException("Record processing requires JDK 17 or later");
    }
    CompilationResult result =
        compile(
            "test.Point",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonType public record Point(@JsonProperty(\"x_value\") int x, "
                + "@JsonIgnore int ignored, String name) {}\n");
    Assert.assertTrue(result.success, result.diagnostics());
    try (URLClassLoader loader = result.classLoader()) {
      JsonDecodedMetadata metadata = decode(result, loader, "test.Point", JsonTypeMetadata.OBJECT);
      Assert.assertEquals(metadata.recordComponentCount(), 3);
      Assert.assertEquals(metadata.instantiatorCount(), 1);
      Assert.assertEquals(metadata.instantiator(0).kind(), JsonMetadataFormat.INSTANTIATOR_RECORD);
      Assert.assertTrue(methodOperation(metadata, "x") >= 0);
    }
    String source = result.generatedSource("test/Point_ForyJsonMetadata.java");
    Assert.assertTrue(source.contains("new test.Point("), source);
    Assert.assertTrue(source.contains(").x()"), source);
    stripRecordAttribute(result.classRoot.resolve("test/Point.class"));
    try (URLClassLoader loader = result.classLoader()) {
      Class<?> target = loader.loadClass("test.Point");
      Assert.assertFalse((Boolean) Class.class.getMethod("isRecord").invoke(target));
    }
    runAndroidRecordProbe(result, "test.Point");
  }

  private static void runAndroidRecordProbe(CompilationResult result, String typeName)
      throws Exception {
    String separator = System.getProperty("path.separator");
    String classPath = result.classRoot + separator + forkClassPath();
    ProcessBuilder builder =
        new ProcessBuilder(
            System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
            "-cp",
            classPath,
            AndroidRecordProbe.class.getName(),
            typeName);
    builder.environment().put("FORY_ANDROID_ENABLED", "1");
    Process process = builder.redirectErrorStream(true).start();
    String output = readFully(process.getInputStream());
    Assert.assertEquals(process.waitFor(), 0, output);
  }

  private static void runAndroidAnyProbe(
      CompilationResult result, String typeName, String accessKind) throws Exception {
    String separator = System.getProperty("path.separator");
    String classPath = result.classRoot + separator + forkClassPath();
    ProcessBuilder builder =
        new ProcessBuilder(
            System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
            "-cp",
            classPath,
            AndroidAnyProbe.class.getName(),
            typeName,
            accessKind);
    builder.environment().put("FORY_ANDROID_ENABLED", "1");
    Process process = builder.redirectErrorStream(true).start();
    String output = readFully(process.getInputStream());
    Assert.assertEquals(process.waitFor(), 0, output);
  }

  public static final class AndroidAnyProbe {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
      Class<?> type = Class.forName(args[0]);
      boolean methodAccess = args[1].equals("method");
      Object value = type.getConstructor().newInstance();
      type.getField("id").setInt(value, 7);
      if (methodAccess) {
        type.getMethod("put", String.class, Integer.class).invoke(value, "extra", 8);
      } else {
        ((java.util.Map<String, Integer>) type.getField("extra").get(value)).put("extra", 8);
      }
      ForyJson json = ForyJson.builder().withCodegen(true).build();
      String text = json.toJson(value);
      if (!"{\"id\":7,\"extra\":8}".equals(text)) {
        throw new AssertionError(text);
      }
      Object copy = json.fromJson(text, type);
      java.util.Map<String, Integer> extra =
          methodAccess
              ? (java.util.Map<String, Integer>) type.getMethod("extra").invoke(copy)
              : (java.util.Map<String, Integer>) type.getField("extra").get(copy);
      if (type.getField("id").getInt(copy) != 7 || !Integer.valueOf(8).equals(extra.get("extra"))) {
        throw new AssertionError("Generated Android Any access did not round-trip");
      }
    }
  }

  public static final class AndroidRecordProbe {
    public static void main(String[] args) throws Exception {
      Class<?> type = Class.forName(args[0]);
      if ((Boolean) Class.class.getMethod("isRecord").invoke(type)) {
        throw new AssertionError("Record attribute must be absent");
      }
      Object value =
          type.getConstructor(int.class, int.class, String.class).newInstance(7, 99, "point");
      ForyJson json = ForyJson.builder().withCodegen(true).build();
      String text = json.toJson(value);
      if (!"{\"x_value\":7,\"name\":\"point\"}".equals(text)) {
        throw new AssertionError(text);
      }
      Object copy = json.fromJson(text, type);
      Object x = type.getMethod("x").invoke(copy);
      Object ignored = type.getMethod("ignored").invoke(copy);
      Object name = type.getMethod("name").invoke(copy);
      if (!Integer.valueOf(7).equals(x)
          || !Integer.valueOf(0).equals(ignored)
          || !"point".equals(name)) {
        throw new AssertionError(
            "Generated record value was x=" + x + ", ignored=" + ignored + ", name=" + name);
      }
    }
  }

  private static void stripRecordAttribute(Path classFile) throws IOException {
    byte[] classBytes = Files.readAllBytes(classFile);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(classBytes.length);
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(classBytes));
        DataOutputStream output = new DataOutputStream(bytes)) {
      output.writeInt(input.readInt());
      output.writeShort(input.readUnsignedShort());
      output.writeShort(input.readUnsignedShort());
      String[] utf8 = copyConstantPool(input, output);
      copyClassHeader(input, output);
      copyMembers(input, output);
      copyMembers(input, output);
      copyClassAttributes(input, output, utf8);
      if (input.read() != -1) {
        throw new IOException("Trailing bytes after class attributes");
      }
    }
    Files.write(classFile, bytes.toByteArray());
  }

  private static String[] copyConstantPool(DataInputStream input, DataOutputStream output)
      throws IOException {
    int count = input.readUnsignedShort();
    output.writeShort(count);
    String[] utf8 = new String[count];
    for (int i = 1; i < count; i++) {
      int tag = input.readUnsignedByte();
      output.writeByte(tag);
      if (tag == 1) {
        int length = input.readUnsignedShort();
        byte[] value = readBytes(input, length);
        output.writeShort(length);
        output.write(value);
        utf8[i] = new String(value, StandardCharsets.UTF_8);
      } else if (tag == 3 || tag == 4) {
        output.writeInt(input.readInt());
      } else if (tag == 5 || tag == 6) {
        output.writeLong(input.readLong());
        i++;
      } else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20) {
        output.writeShort(input.readUnsignedShort());
      } else if (tag == 9 || tag == 10 || tag == 11 || tag == 12 || tag == 17 || tag == 18) {
        output.writeInt(input.readInt());
      } else if (tag == 15) {
        output.writeByte(input.readUnsignedByte());
        output.writeShort(input.readUnsignedShort());
      } else {
        throw new IOException("Unknown class constant tag " + tag);
      }
    }
    return utf8;
  }

  private static void copyClassHeader(DataInputStream input, DataOutputStream output)
      throws IOException {
    output.writeShort(input.readUnsignedShort());
    output.writeShort(input.readUnsignedShort());
    output.writeShort(input.readUnsignedShort());
    int interfaceCount = input.readUnsignedShort();
    output.writeShort(interfaceCount);
    for (int i = 0; i < interfaceCount; i++) {
      output.writeShort(input.readUnsignedShort());
    }
  }

  private static void copyMembers(DataInputStream input, DataOutputStream output)
      throws IOException {
    int count = input.readUnsignedShort();
    output.writeShort(count);
    for (int i = 0; i < count; i++) {
      output.writeShort(input.readUnsignedShort());
      output.writeShort(input.readUnsignedShort());
      output.writeShort(input.readUnsignedShort());
      copyAttributes(input, output);
    }
  }

  private static void copyAttributes(DataInputStream input, DataOutputStream output)
      throws IOException {
    int count = input.readUnsignedShort();
    output.writeShort(count);
    for (int i = 0; i < count; i++) {
      output.writeShort(input.readUnsignedShort());
      int length = input.readInt();
      output.writeInt(length);
      output.write(readBytes(input, length));
    }
  }

  private static void copyClassAttributes(
      DataInputStream input, DataOutputStream output, String[] utf8) throws IOException {
    int count = input.readUnsignedShort();
    List<ClassAttribute> attributes = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      int nameIndex = input.readUnsignedShort();
      int length = input.readInt();
      byte[] value = readBytes(input, length);
      if (!"Record".equals(utf8[nameIndex])) {
        attributes.add(new ClassAttribute(nameIndex, value));
      }
    }
    if (attributes.size() == count) {
      throw new IOException("Record class attribute was not found");
    }
    output.writeShort(attributes.size());
    for (ClassAttribute attribute : attributes) {
      output.writeShort(attribute.nameIndex);
      output.writeInt(attribute.value.length);
      output.write(attribute.value);
    }
  }

  private static byte[] readBytes(DataInputStream input, int length) throws IOException {
    byte[] bytes = new byte[length];
    input.readFully(bytes);
    return bytes;
  }

  private static String readFully(java.io.InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    int read;
    while ((read = input.read(buffer)) != -1) {
      output.write(buffer, 0, read);
    }
    return new String(output.toByteArray(), StandardCharsets.UTF_8);
  }

  private static String forkClassPath() {
    String classPath = System.getProperty("surefire.real.class.path");
    return classPath == null || classPath.isEmpty()
        ? System.getProperty("java.class.path")
        : classPath;
  }

  private static final class ClassAttribute {
    private final int nameIndex;
    private final byte[] value;

    private ClassAttribute(int nameIndex, byte[] value) {
      this.nameIndex = nameIndex;
      this.value = value;
    }
  }

  private static boolean recordCompilerAvailable() {
    try {
      TypeElement.class.getMethod("getRecordComponents");
      return !System.getProperty("java.specification.version", "1.8").equals("1.8");
    } catch (NoSuchMethodException e) {
      return false;
    }
  }

  @Test
  public void testGenericHierarchy() throws Exception {
    CompilationResult result =
        compile(
            "test.GenericModel",
            "package test;\n"
                + "import java.util.*;\n"
                + "import org.apache.fory.json.annotation.JsonType;\n"
                + "@JsonType public class GenericModel<T extends Number> {\n"
                + "  public List<T> values; public GenericModel() {}\n"
                + "}\n");
    Assert.assertTrue(result.success, result.diagnostics());
    try (URLClassLoader loader = result.classLoader()) {
      JsonDecodedMetadata metadata =
          decode(result, loader, "test.GenericModel", JsonTypeMetadata.OBJECT);
      Assert.assertTrue(metadata.typeParameterCount() > 0);
      Assert.assertTrue(metadata.hierarchyCount() > 0);
    }
  }

  @Test
  public void testChunkingAndDeterminism() throws Exception {
    StringBuilder source = new StringBuilder();
    source.append("package test; import org.apache.fory.json.annotation.JsonType; ");
    source.append("@JsonType public class WideModel { public WideModel() {} ");
    for (int i = 0; i < 140; i++) {
      source.append("public int field").append(i).append("; ");
    }
    source.append('}');
    CompilationResult first = compile("test.WideModel", source.toString());
    CompilationResult second = compile("test.WideModel", source.toString());
    Assert.assertTrue(first.success, first.diagnostics());
    Assert.assertTrue(second.success, second.diagnostics());
    String firstSource = first.generatedSource("test/WideModel_ForyJsonMetadata.java");
    Assert.assertTrue(firstSource.contains("Operations2_1"), firstSource);
    Assert.assertEquals(
        firstSource, second.generatedSource("test/WideModel_ForyJsonMetadata.java"));
    String resource = "META-INF/fory-json/r8/fory-json-generated-test.WideModel.pro";
    Assert.assertEquals(first.generatedResource(resource), second.generatedResource(resource));
  }

  @Test
  public void testNonStaticTargetFails() throws Exception {
    CompilationResult result =
        compile(
            "test.Owner",
            "package test; import org.apache.fory.json.annotation.JsonType; "
                + "public class Owner { @JsonType public class Model {} }");
    Assert.assertFalse(result.success);
    Assert.assertTrue(result.diagnostics().contains("must be static"), result.diagnostics());
  }

  @Test
  public void testPrivateOwnerConstructor() throws Exception {
    String binaryName = "test.PrivateConstructorOwner$Model";
    CompilationResult result =
        compile(
            "test.PrivateConstructorOwner",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "public class PrivateConstructorOwner {\n"
                + "  @JsonType private static final class Model {\n"
                + "    public final int id;\n"
                + "    @JsonCreator public Model(@JsonProperty(\"id\") int id) { this.id = id; }\n"
                + "  }\n"
                + "}\n");
    Assert.assertTrue(result.success, result.diagnostics());
    try (URLClassLoader loader = result.classLoader()) {
      Class<?> target = loader.loadClass(binaryName);
      JsonDecodedMetadata metadata = decode(result, loader, binaryName, JsonTypeMetadata.OBJECT);
      JsonDecodedMetadata.Creator creator = metadata.creator(0);
      JsonDecodedMetadata.Operation operation = metadata.operation(creator.operation());
      Assert.assertEquals(operation.mode(), JsonMetadataFormat.OP_HANDLE);
      Assert.assertEquals(operation.memberName(), "<init>");
      Assert.assertEquals(operation.descriptor(), "(I)V");
      Executable executable = target.getDeclaredConstructor(int.class);
      Object value = JsonCreatorInvoker.forExecutable(target, executable).create(new Object[] {7});
      Assert.assertEquals(readInt(target, value, "id"), 7);
    }
    assertCreatorRule(result, binaryName, "<init>(int);");
  }

  @Test
  public void testPrivateOwnerFactory() throws Exception {
    String binaryName = "test.PrivateFactoryOwner$Model";
    CompilationResult result =
        compile(
            "test.PrivateFactoryOwner",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "public class PrivateFactoryOwner {\n"
                + "  @JsonType private static final class Model {\n"
                + "    public final int id;\n"
                + "    private Model(int id) { this.id = id; }\n"
                + "    @JsonCreator public static Model create(@JsonProperty(\"id\") int id) {\n"
                + "      return new Model(id);\n"
                + "    }\n"
                + "  }\n"
                + "}\n");
    Assert.assertTrue(result.success, result.diagnostics());
    try (URLClassLoader loader = result.classLoader()) {
      Class<?> target = loader.loadClass(binaryName);
      JsonDecodedMetadata metadata = decode(result, loader, binaryName, JsonTypeMetadata.OBJECT);
      JsonDecodedMetadata.Creator creator = metadata.creator(0);
      JsonDecodedMetadata.Operation operation = metadata.operation(creator.operation());
      Assert.assertEquals(operation.mode(), JsonMetadataFormat.OP_HANDLE);
      Assert.assertEquals(operation.memberName(), "create");
      Assert.assertEquals(operation.descriptor(), "(I)Ltest/PrivateFactoryOwner$Model;");
      Executable executable = target.getDeclaredMethod("create", int.class);
      Object value = JsonCreatorInvoker.forExecutable(target, executable).create(new Object[] {9});
      Assert.assertEquals(readInt(target, value, "id"), 9);
    }
    assertCreatorRule(result, binaryName, "test.PrivateFactoryOwner$Model create(int);");
  }

  private static CompilationResult compileModel() throws IOException {
    return compile(
        "test.Model",
        "package test;\n"
            + "import java.util.*;\n"
            + "import org.apache.fory.json.annotation.*;\n"
            + "import org.apache.fory.json.codec.JsonValueCodec;\n"
            + "import org.apache.fory.json.reader.*;\n"
            + "import org.apache.fory.json.writer.*;\n"
            + "@JsonType public class Model {\n"
            + "  public int id;\n"
            + "  private String secret;\n"
            + "  public List<String> values;\n"
            + "  @JsonCreator public Model(@JsonProperty(\"id\") int id) { this.id = id; }\n"
            + "  public String getSecret() { return secret; }\n"
            + "  public void setSecret(String value) { secret = value; }\n"
            + "  @JsonAnyGetter public Map<String, Object> attributes() { return null; }\n"
            + "  @JsonAnySetter public void put(String name, Object value) {}\n"
            + "}\n");
  }

  private static JsonDecodedMetadata.Field field(JsonDecodedMetadata metadata, String name) {
    for (int i = 0; i < metadata.fieldCount(); i++) {
      if (metadata.field(i).name().equals(name)) {
        return metadata.field(i);
      }
    }
    throw new AssertionError("Missing field " + name);
  }

  private static void assertCreatorRule(CompilationResult result, String binaryName, String member)
      throws IOException {
    String resource =
        "META-INF/fory-json/r8/fory-json-generated-"
            + JsonTypeMetadata.escapedResourceName(binaryName)
            + ".pro";
    String rules = result.generatedResource(resource);
    Assert.assertTrue(
        rules.contains(
            "-keepclassmembers,allowoptimization class " + binaryName + " {\n  " + member + "\n}"),
        rules);
  }

  private static int readInt(Class<?> target, Object value, String name) throws Exception {
    Field field = target.getDeclaredField(name);
    field.setAccessible(true);
    return field.getInt(value);
  }

  private static int methodOperation(JsonDecodedMetadata metadata, String name) {
    for (int i = 0; i < metadata.methodCount(); i++) {
      if (metadata.method(i).name().equals(name)) {
        return metadata.method(i).operation();
      }
    }
    return -1;
  }

  private static JsonDecodedMetadata.Method method(JsonDecodedMetadata metadata, String name) {
    for (int i = 0; i < metadata.methodCount(); i++) {
      if (metadata.method(i).name().equals(name)) {
        return metadata.method(i);
      }
    }
    throw new AssertionError("Missing method " + name);
  }

  private static JsonDecodedMetadata decode(
      CompilationResult result, URLClassLoader loader, String name, int section) throws Exception {
    Class<?> target = loader.loadClass(name);
    Class<?> companion = loader.loadClass(JsonTypeMetadata.generatedBinaryName(name));
    JsonTypeMetadata metadata =
        (JsonTypeMetadata) companion.getConstructor(Class.class).newInstance(target);
    return JsonMetadataDecoder.decode(
        target, section, (JsonTypeMetadataData) metadata.metadata(section));
  }

  private static final class JsonTypeNodeAssert {
    static void hasNestedCodec(JsonDecodedMetadata metadata, int root) {
      org.apache.fory.json.meta.JsonTypeNode node = metadata.typeNode(root);
      Assert.assertEquals(node.kind(), JsonMetadataFormat.TYPE_DECLARED);
      Assert.assertEquals(node.childCount(), 1);
      Assert.assertTrue(metadata.typeNode(node.child(0)).hasCodec());
    }
  }

  private static CompilationResult compile(String typeName, String source) throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    Assert.assertNotNull(compiler, "Tests require a JDK compiler");
    Path root = Files.createTempDirectory("fory-json-processor-test");
    Path sourceRoot = root.resolve("src");
    Path classRoot = root.resolve("classes");
    Path generatedRoot = root.resolve("generated");
    Files.createDirectories(sourceRoot);
    Files.createDirectories(classRoot);
    Files.createDirectories(generatedRoot);
    Path sourceFile = sourceRoot.resolve(typeName.replace('.', '/') + ".java");
    Files.createDirectories(sourceFile.getParent());
    Files.write(sourceFile, source.getBytes(StandardCharsets.UTF_8));
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager fileManager =
        compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
      Iterable<? extends JavaFileObject> units =
          fileManager.getJavaFileObjectsFromFiles(Collections.singletonList(sourceFile.toFile()));
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
      task.setProcessors(Collections.singletonList(new ForyJsonProcessor()));
      return new CompilationResult(
          classRoot, generatedRoot, task.call(), diagnostics.getDiagnostics());
    }
  }

  private static void assertCompilationFails(String typeName, String source, String diagnostic)
      throws IOException {
    CompilationResult result = compile(typeName, source);
    Assert.assertFalse(result.success);
    Assert.assertTrue(result.diagnostics().contains(diagnostic), result.diagnostics());
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
      return new URLClassLoader(
          new URL[] {classRoot.toUri().toURL()}, ForyJsonProcessorTest.class.getClassLoader());
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

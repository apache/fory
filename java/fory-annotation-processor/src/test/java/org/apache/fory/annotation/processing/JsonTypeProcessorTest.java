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
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.GeneratedJsonCodec;
import org.apache.fory.json.codec.GeneratedJsonCodecFactory;
import org.apache.fory.json.meta.JsonAnySetterAccessor;
import org.apache.fory.json.meta.JsonFieldAccessor;
import org.testng.SkipException;
import org.testng.annotations.Test;

public class JsonTypeProcessorTest {
  private static final String RULE_PREFIX = "META-INF/proguard/fory-json-";
  private static final String MIXIN_RULE_PREFIX = "META-INF/proguard/fory-json-mixin-";
  private static final String NATIVE_IMAGE_PREFIX =
      "META-INF/native-image/org.apache.fory/fory-json-";

  @Test
  public void unannotatedType() throws Exception {
    CompilationResult result =
        compile("test.Plain", "package test; public class Plain { int id; }");
    assertTrue(result.success, result.diagnostics());
    assertFalse(result.hasGeneratedResource(RULE_PREFIX + "test.Plain.pro"));
  }

  @Test
  public void emptyMixin() throws Exception {
    CompilationResult result =
        compile(
            "test.Target",
            "package test;\n"
                + "import org.apache.fory.json.annotation.JsonMixin;\n"
                + "public class Target { public int id; }\n"
                + "@JsonMixin(target = Target.class) interface EmptyMixin {}\n");
    assertTrue(result.success, result.diagnostics());
    String companion = "test.EmptyMixin_ForyJsonMixin_test_x2e_Target_ForyJsonCodec";
    assertFalse(result.hasGeneratedSource(companion.replace('.', '/') + ".java"));
    assertFalse(result.hasGeneratedResource(MIXIN_RULE_PREFIX + "test.EmptyMixin.pro"));
    assertFalse(
        result.hasGeneratedResource(NATIVE_IMAGE_PREFIX + companion + "/native-image.properties"));
  }

  @Test
  public void mixinPairArtifacts() throws Exception {
    CompilationResult result =
        compile(
            "test.Person",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "public final class Person {\n"
                + "  public Address address;\n"
                + "  public String body;\n"
                + "  private Person(String body) { this.body = body; }\n"
                + "  public static Person create(String body) { return new Person(body); }\n"
                + "}\n"
                + "class Address { public String city; }\n"
                + "@JsonMixin(target = Person.class) abstract class PersonMixin {\n"
                + "  @JsonUnwrapped(prefix = \"address_\") Address address;\n"
                + "  @JsonRawValue String body;\n"
                + "  @JsonCreator({\"body\"}) abstract Person create(String body);\n"
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    String base = "PersonMixin_ForyJsonMixin_test_x2e_Person";
    assertTrue(result.hasGeneratedSource("test/" + base + "_ForyJsonCodec.java"));
    String rules = result.generatedResource(MIXIN_RULE_PREFIX + "test.PersonMixin.pro");
    assertTrue(rules.contains("-keep,allowoptimization class test.Person"), rules);
    assertTrue(rules.contains("-keep,allowoptimization class test.PersonMixin"), rules);
    assertTrue(rules.contains("test.Address address;"), rules);
    assertTrue(rules.contains("test.Person create(java.lang.String);"), rules);
    assertTrue(rules.contains("java.lang.String city;"), rules);
    assertTrue(rules.contains("class test." + base + "_ForyJsonCodec {"), rules);
  }

  @Test
  public void mixinTypeCodecRules() throws Exception {
    CompilationResult result =
        compile(
            "test.CodecTarget",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonCodec(CodecMixin.InheritedCodec.class) interface InheritedContract {}\n"
                + "public final class CodecTarget implements InheritedContract {\n"
                + "  public int selected;\n"
                + "  @JsonValue public String value;\n"
                + "  public String unrelated;\n"
                + "  public Child child;\n"
                + "  @JsonCreator public CodecTarget(String value) { this.value = value; }\n"
                + "  public static final class Child { public String nested; }\n"
                + "}\n"
                + "@JsonMixin(target = CodecTarget.class)\n"
                + "@JsonCodec(CodecMixin.Codec.class) abstract class CodecMixin {\n"
                + "  @JsonProperty int selected;\n"
                + valueCodec("Codec")
                + valueCodec("InheritedCodec")
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    String base = "CodecMixin_ForyJsonMixin_test_x2e_CodecTarget";
    assertFalse(result.hasGeneratedSource("test/" + base + "_ForyJsonCodec.java"));
    String rules = result.generatedResource(MIXIN_RULE_PREFIX + "test.CodecMixin.pro");
    assertTrue(
        rules.contains(
            "-keepclassmembers,allowoptimization class test.CodecTarget {\n"
                + "  <init>(java.lang.String);\n"
                + "  int selected;\n"
                + "  java.lang.String value;\n"
                + "}"),
        rules);
    assertTrue(rules.contains("class test.CodecMixin$Codec { public <init>(); }"), rules);
    assertFalse(rules.contains("class test.CodecMixin$InheritedCodec { public <init>(); }"), rules);
    assertFalse(rules.contains("class test.InheritedContract"), rules);
    assertFalse(rules.contains("java.lang.String unrelated;"), rules);
    assertFalse(rules.contains("test.CodecTarget$Child child;"), rules);
    assertFalse(rules.contains("java.lang.String nested;"), rules);
  }

  @Test
  public void mixinInheritedCodecRules() throws Exception {
    CompilationResult result =
        compile(
            "test.InheritedTarget",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonCodec(value = Codecs.RootCodec.class, "
                + "elementCodec = Codecs.RootCodec.class) interface Root {}\n"
                + "@JsonCodec(Codecs.ChildCodec.class) interface Child extends Root {}\n"
                + "public final class InheritedTarget implements Child {\n"
                + "  public int id;\n"
                + "  public String unrelated;\n"
                + "  @JsonUnwrapped public Details details;\n"
                + "  public static final class Details { public String nested; }\n"
                + "}\n"
                + "@JsonMixin(target = InheritedTarget.class) abstract class InheritedMixin {\n"
                + "  @JsonProperty int id;\n"
                + "}\n"
                + "final class Codecs {\n"
                + valueCodec("RootCodec")
                + valueCodec("ChildCodec")
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    String rules = result.generatedResource(MIXIN_RULE_PREFIX + "test.InheritedMixin.pro");
    assertTrue(rules.contains("class test.Codecs$RootCodec { public <init>(); }"), rules);
    assertTrue(rules.contains("class test.Codecs$ChildCodec { public <init>(); }"), rules);
    assertTrue(rules.contains("class test.Root"), rules);
    assertTrue(rules.contains("class test.Child"), rules);
    assertFalse(rules.contains("java.lang.String unrelated;"), rules);
    assertFalse(rules.contains("test.InheritedTarget$Details details;"), rules);
    assertFalse(rules.contains("java.lang.String nested;"), rules);
  }

  @Test
  public void mixinRuntimePipeline() throws Exception {
    CompilationResult result =
        compile(
            "test.MixinTarget",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "public final class MixinTarget {\n"
                + "  private final int id;\n"
                + "  private final String name;\n"
                + "  private MixinTarget(int id, String name) { this.id = id; this.name = name; }\n"
                + "  public int getId() { return id; }\n"
                + "  public String getName() { return name; }\n"
                + "  public static MixinTarget create(int id, String name) {\n"
                + "    return new MixinTarget(id, name);\n"
                + "  }\n"
                + "}\n"
                + "@JsonMixin(target = MixinTarget.class)\n"
                + "@JsonPropertyOrder({\"id\", \"name\"})\n"
                + "interface MixinTargetAnnotations {\n"
                + "  @JsonProperty(\"user_id\") int getId();\n"
                + "  @JsonCreator({\"id\", \"name\"}) MixinTarget create(int id, String name);\n"
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    ClassLoader loader = result.classLoader();
    Class<?> target = loader.loadClass("test.MixinTarget");
    Class<?> mixin = loader.loadClass("test.MixinTargetAnnotations");
    String base = "test.MixinTargetAnnotations_ForyJsonMixin_test_x2e_MixinTarget";
    GeneratedJsonCodec<?> codec = generatedCodec(loader, base + "_ForyJsonCodec");
    assertEquals(codec.type(), target);
    Object value = target.getMethod("create", int.class, String.class).invoke(null, 7, "pair");
    for (boolean codegen : new boolean[] {false, true}) {
      ForyJson json =
          ForyJson.builder()
              .withCodegen(codegen)
              .withAsyncCompilation(false)
              .withClassLoader(loader)
              .registerMixin(mixin)
              .build();
      String text = json.toJson(value);
      assertEquals(text, "{\"user_id\":7,\"name\":\"pair\"}");
      Object decoded = json.fromJson(text, target);
      assertEquals(target.getMethod("getId").invoke(decoded), 7);
      assertEquals(target.getMethod("getName").invoke(decoded), "pair");
    }
  }

  @Test
  public void mixinRecordPipeline() throws Exception {
    assumeJava16Source();
    CompilationResult result =
        compile(
            "test.MixinRecord",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "public record MixinRecord(int id, String name) {}\n"
                + "@JsonMixin(target = MixinRecord.class)\n"
                + "@JsonPropertyOrder({\"id\", \"name\"})\n"
                + "abstract class MixinRecordAnnotations {\n"
                + "  @JsonProperty(\"user_id\") int id;\n"
                + "  @JsonProperty(\"display_name\") String name;\n"
                + "  @JsonProperty(\"user_id\") abstract int id();\n"
                + "  @JsonProperty(\"display_name\") abstract String name();\n"
                + "  MixinRecordAnnotations(@JsonProperty(\"user_id\") int id,\n"
                + "      @JsonProperty(\"display_name\") String name) {}\n"
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    String base = "MixinRecordAnnotations_ForyJsonMixin_test_x2e_MixinRecord";
    assertTrue(result.hasGeneratedSource("test/" + base + "_ForyJsonCodec.java"));
    ClassLoader loader = result.classLoader();
    Class<?> target = loader.loadClass("test.MixinRecord");
    Class<?> mixin = loader.loadClass("test.MixinRecordAnnotations");
    Object value = target.getConstructor(int.class, String.class).newInstance(9, "record");
    for (boolean codegen : new boolean[] {false, true}) {
      ForyJson json =
          ForyJson.builder()
              .withCodegen(codegen)
              .withAsyncCompilation(false)
              .withClassLoader(loader)
              .registerMixin(mixin)
              .build();
      String text = json.toJson(value);
      assertEquals(text, "{\"user_id\":9,\"display_name\":\"record\"}");
      Object decoded = json.fromJson(text, target);
      assertEquals(target.getMethod("id").invoke(decoded), 9);
      assertEquals(target.getMethod("name").invoke(decoded), "record");
    }
  }

  @Test
  public void mixinValueRecordPipeline() throws Exception {
    assumeJava16Source();
    CompilationResult result =
        compile(
            "test.MixinValueRecord",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "public record MixinValueRecord(String value) {}\n"
                + "@JsonMixin(target = MixinValueRecord.class)\n"
                + "abstract class MixinValueRecordAnnotations {\n"
                + "  @JsonValue String value;\n"
                + "  @JsonValue abstract String value();\n"
                + "  @JsonCreator MixinValueRecordAnnotations(String value) {}\n"
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    String base = "MixinValueRecordAnnotations_ForyJsonMixin_test_x2e_MixinValueRecord";
    assertTrue(result.hasGeneratedSource("test/" + base + "_ForyJsonCodec.java"));
    ClassLoader loader = result.classLoader();
    Class<?> target = loader.loadClass("test.MixinValueRecord");
    Class<?> mixin = loader.loadClass("test.MixinValueRecordAnnotations");
    Object value = target.getConstructor(String.class).newInstance("write");
    for (boolean codegen : new boolean[] {false, true}) {
      ForyJson json =
          ForyJson.builder()
              .withCodegen(codegen)
              .withAsyncCompilation(false)
              .withClassLoader(loader)
              .registerMixin(mixin)
              .build();
      assertEquals(json.toJson(value), "\"write\"");
      Object decoded = json.fromJson("\"read\"", target);
      assertEquals(target.getMethod("value").invoke(decoded), "read");
    }
  }

  @Test
  public void mixinNamesDoNotCollide() throws Exception {
    Map<String, String> sources = new LinkedHashMap<>();
    sources.put("test.Target", "package test; public class Target { public int id; }\n");
    sources.put(
        "test.FirstMixin",
        "package test;\n"
            + "import org.apache.fory.json.annotation.*;\n"
            + "@JsonMixin(target = Target.class) public abstract class FirstMixin {\n"
            + "  @JsonProperty(\"first\") int id;\n"
            + "}\n");
    sources.put(
        "test.SecondMixin",
        "package test;\n"
            + "import org.apache.fory.json.annotation.*;\n"
            + "@JsonMixin(target = Target.class) public abstract class SecondMixin {\n"
            + "  @JsonProperty(\"second\") int id;\n"
            + "}\n");
    CompilationResult result = compile(sources);
    assertTrue(result.success, result.diagnostics());
    assertTrue(
        result.hasGeneratedSource(
            "test/FirstMixin_ForyJsonMixin_test_x2e_Target_ForyJsonCodec.java"));
    assertTrue(
        result.hasGeneratedSource(
            "test/SecondMixin_ForyJsonMixin_test_x2e_Target_ForyJsonCodec.java"));
    assertTrue(result.hasGeneratedResource(MIXIN_RULE_PREFIX + "test.FirstMixin.pro"));
    assertTrue(result.hasGeneratedResource(MIXIN_RULE_PREFIX + "test.SecondMixin.pro"));
  }

  @Test
  public void mixinSelectorShape() throws Exception {
    CompilationResult staticField =
        compile(
            "test.StaticFieldMixin",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "class Target { public int id; }\n"
                + "@JsonMixin(target = Target.class) abstract class StaticFieldMixin {\n"
                + "  @JsonProperty static int id;\n"
                + "}\n");
    assertFalse(staticField.success);
    assertTrue(
        staticField.diagnostics().contains("field selector must be an instance field"),
        staticField.diagnostics());

    CompilationResult concreteMethod =
        compile(
            "test.ConcreteMethodMixin",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "class Target { public String getName() { return null; } }\n"
                + "@JsonMixin(target = Target.class) abstract class ConcreteMethodMixin {\n"
                + "  @JsonProperty String getName() { return null; }\n"
                + "}\n");
    assertFalse(concreteMethod.success);
    assertTrue(
        concreteMethod.diagnostics().contains("method selector must be abstract"),
        concreteMethod.diagnostics());

    CompilationResult jsonTypeSource =
        compile(
            "test.JsonTypeMixin",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "class Target { public int id; }\n"
                + "@JsonType @JsonMixin(target = Target.class) interface JsonTypeMixin {}\n");
    assertFalse(jsonTypeSource.success);
    assertTrue(
        jsonTypeSource.diagnostics().contains("@JsonType cannot be declared by a JSON Mixin"),
        jsonTypeSource.diagnostics());
    assertFalse(jsonTypeSource.hasGeneratedResource(RULE_PREFIX + "test.JsonTypeMixin.pro"));
  }

  @Test
  public void mixinInaccessibleAnySetter() throws Exception {
    CompilationResult result =
        compile(
            "test.Owner",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "public final class Owner {\n"
                + "  private static final class HiddenValue { public int id; }\n"
                + "  public static final class Target {\n"
                + "    private HiddenValue extra;\n"
                + "    public void putExtra(String name, HiddenValue value) { extra = value; }\n"
                + "    public int getExtraId() { return extra.id; }\n"
                + "  }\n"
                + "  @JsonMixin(target = Target.class) public interface Mixin {\n"
                + "    @JsonAnySetter void putExtra(String name, HiddenValue value);\n"
                + "  }\n"
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    ClassLoader loader = result.classLoader();
    Class<?> target = loader.loadClass("test.Owner$Target");
    Class<?> mixin = loader.loadClass("test.Owner$Mixin");
    ForyJson json = ForyJson.builder().withClassLoader(loader).registerMixin(mixin).build();
    Object value = json.fromJson("{\"answer\":{\"id\":42}}", target);
    assertEquals(target.getMethod("getExtraId").invoke(value), 42);
  }

  @Test
  public void directMixinRemoveRejected() throws Exception {
    CompilationResult result =
        compile(
            "test.InvalidRemove",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonMixinRemove(JsonPropertyOrder.class) class InvalidRemove {}\n");
    assertFalse(result.success);
    assertTrue(
        result.diagnostics().contains("valid only inside a direct @JsonMixin source"),
        result.diagnostics());
  }

  @Test
  public void inheritedCodecRemovalRules() throws Exception {
    CompilationResult result =
        compile(
            "test.CodecBarrierMixin",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonCodec(Codec.class) class Base {}\n"
                + "@JsonCodec(EndpointCodec.class) class Endpoint {}\n"
                + "class Target extends Base { public Endpoint endpoint; }\n"
                + "@JsonMixin(target = Target.class)\n"
                + "@JsonMixinRemove(JsonCodec.class) interface CodecBarrierMixin {}\n"
                + "class Codec implements org.apache.fory.json.codec.JsonValueCodec<Object> {\n"
                + "  public Codec() {}\n"
                + "  public void writeString(org.apache.fory.json.writer.StringJsonWriter w, Object v) {}\n"
                + "  public void writeUtf8(org.apache.fory.json.writer.Utf8JsonWriter w, Object v) {}\n"
                + "  public Object readLatin1(org.apache.fory.json.reader.Latin1JsonReader r) { return null; }\n"
                + "  public Object readUtf16(org.apache.fory.json.reader.Utf16JsonReader r) { return null; }\n"
                + "  public Object readUtf8(org.apache.fory.json.reader.Utf8JsonReader r) { return null; }\n"
                + "}\n"
                + "final class EndpointCodec extends Codec { public EndpointCodec() {}\n"
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    String rules = result.generatedResource(MIXIN_RULE_PREFIX + "test.CodecBarrierMixin.pro");
    assertFalse(rules.contains("class test.Codec "), rules);
    assertFalse(rules.contains("class test.Base"), rules);
    assertTrue(rules.contains("class test.EndpointCodec { public <init>(); }"), rules);
  }

  @Test
  public void mixinCreatorIsExact() throws Exception {
    CompilationResult result =
        compile(
            "test.CreatorMixin",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "class Base { public static Target create(String name) { return null; } }\n"
                + "class Target extends Base {}\n"
                + "@JsonMixin(target = Target.class) interface CreatorMixin {\n"
                + "  @JsonCreator({\"name\"}) Target create(String name);\n"
                + "}\n");
    assertFalse(result.success);
    assertTrue(result.diagnostics().contains("does not match test.Target"), result.diagnostics());
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
    assertTrue(rules.contains("-keep,allowoptimization class test.Plain_ForyJsonCodec {"), rules);
    assertFalse(rules.contains("test.Plain_ForyJsonCodec$Factory"), rules);
    assertFalse(rules.contains("-keep,allowoptimization,allowobfuscation class test.Plain"), rules);
    assertTrue(result.hasGeneratedSource("test/Plain_ForyJsonCodec.java"));
    assertEquals(
        result.generatedResource(
            NATIVE_IMAGE_PREFIX + "test.Plain_ForyJsonCodec/native-image.properties"),
        "Args=--initialize-at-build-time=test.Plain_ForyJsonCodec$Factory\n");
  }

  @Test
  public void generatedAccessors() throws Exception {
    CompilationResult result =
        compile(
            "test.GeneratedModel",
            "package test;\n"
                + "import java.util.*;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonType public class GeneratedModel {\n"
                + "  public int id;\n"
                + "  private String name;\n"
                + "  public final Map<String, Object> extra = new HashMap<>();\n"
                + "  public GeneratedModel() {}\n"
                + "  public String getName() { return name; }\n"
                + "  public void setName(String name) { this.name = name; }\n"
                + "  @JsonAnySetter public void putExtra(String key, Object value) {\n"
                + "    extra.put(key, value);\n"
                + "  }\n"
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    ClassLoader loader = result.classLoader();
    Class<?> modelType = loader.loadClass("test.GeneratedModel");
    GeneratedJsonCodec<?> codec = generatedCodec(loader, "test.GeneratedModel_ForyJsonCodec");
    assertEquals(codec.type(), modelType);
    JsonFieldAccessor[] first = codec.fieldAccessors();
    JsonFieldAccessor[] second = codec.fieldAccessors();
    assertNotSame(first, second);

    Object model = modelType.getConstructor().newInstance();
    JsonFieldAccessor id = fieldAccessor(first, "id");
    id.putInt(model, 31);
    assertEquals(id.getInt(model), 31);
    JsonFieldAccessor setter = methodAccessor(first, "setName");
    JsonFieldAccessor getter = methodAccessor(first, "getName");
    setter.putObject(model, "fory");
    assertEquals(getter.getObject(model), "fory");

    JsonAnySetterAccessor anySetter = codec.anySetterAccessor();
    assertNotNull(anySetter);
    anySetter.put(model, "answer", 42);
    Field extra = modelType.getField("extra");
    assertEquals(((Map<?, ?>) extra.get(model)).get("answer"), 42);

    GeneratedJsonCodecFactory factory =
        (GeneratedJsonCodecFactory)
            loader
                .loadClass("test.GeneratedModel_ForyJsonCodec$Factory")
                .getConstructor()
                .newInstance();
    assertEquals(factory.create().type(), modelType);
  }

  @Test
  public void hiddenFieldAccessors() throws Exception {
    CompilationResult result =
        compile(
            "test.HiddenFieldModel",
            "package test;\n"
                + "import org.apache.fory.json.annotation.JsonType;\n"
                + "class HiddenFieldBase { public int value; }\n"
                + "@JsonType public class HiddenFieldModel extends HiddenFieldBase {\n"
                + "  public int value;\n"
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    ClassLoader loader = result.classLoader();
    Class<?> modelType = loader.loadClass("test.HiddenFieldModel");
    Class<?> baseType = loader.loadClass("test.HiddenFieldBase");
    GeneratedJsonCodec<?> codec = generatedCodec(loader, "test.HiddenFieldModel_ForyJsonCodec");
    JsonFieldAccessor base = fieldAccessor(codec.fieldAccessors(), baseType, "value");
    JsonFieldAccessor child = fieldAccessor(codec.fieldAccessors(), modelType, "value");
    Object model = modelType.getConstructor().newInstance();
    base.putInt(model, 11);
    child.putInt(model, 29);
    assertEquals(base.getInt(model), 11);
    assertEquals(child.getInt(model), 29);
    Field baseField = baseType.getField("value");
    baseField.setAccessible(true);
    assertEquals(baseField.getInt(model), 11);
    assertEquals(modelType.getField("value").getInt(model), 29);
  }

  @Test
  public void genericMemberDispatch() throws Exception {
    CompilationResult result =
        compile(
            "test.GenericDispatchModel",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "class GenericDispatchBase<T extends CharSequence> {\n"
                + "  public T value;\n"
                + "  public int baseSetterCalls;\n"
                + "  public int baseAnySetterCalls;\n"
                + "  @SuppressWarnings(\"unchecked\")\n"
                + "  public void setText(CharSequence value) {\n"
                + "    this.value = (T) value; baseSetterCalls++;\n"
                + "  }\n"
                + "  public T getValue() { return value; }\n"
                + "  public String getVirtualValue() { return \"base\"; }\n"
                + "  @SuppressWarnings(\"unchecked\")\n"
                + "  @JsonAnySetter public void putExtra(String name, CharSequence value) {\n"
                + "    this.value = (T) value; baseAnySetterCalls++;\n"
                + "  }\n"
                + "}\n"
                + "@JsonType public class GenericDispatchModel\n"
                + "    extends GenericDispatchBase<String> {\n"
                + "  public String value;\n"
                + "  public int overloadSetterCalls;\n"
                + "  public int overloadAnySetterCalls;\n"
                + "  public void setText(String value) { overloadSetterCalls++; }\n"
                + "  public void putExtra(String name, String value) { overloadAnySetterCalls++; }\n"
                + "  @Override public String getVirtualValue() { return \"child\"; }\n"
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    ClassLoader loader = result.classLoader();
    Class<?> modelType = loader.loadClass("test.GenericDispatchModel");
    Class<?> baseType = loader.loadClass("test.GenericDispatchBase");
    GeneratedJsonCodec<?> codec = generatedCodec(loader, "test.GenericDispatchModel_ForyJsonCodec");
    Object model = modelType.getConstructor().newInstance();
    JsonFieldAccessor baseField = fieldAccessor(codec.fieldAccessors(), baseType, "value");
    JsonFieldAccessor childField = fieldAccessor(codec.fieldAccessors(), modelType, "value");
    baseField.putObject(model, "base-field");
    childField.putObject(model, "child-field");
    assertEquals(baseField.getObject(model), "base-field");
    assertEquals(childField.getObject(model), "child-field");
    JsonFieldAccessor setter =
        methodAccessor(codec.fieldAccessors(), baseType, "setText", CharSequence.class);
    JsonFieldAccessor getter = methodAccessor(codec.fieldAccessors(), baseType, "getValue");
    setter.putObject(model, "base-setter");
    assertEquals(getter.getObject(model), "base-setter");
    Field baseSetterCalls = baseType.getField("baseSetterCalls");
    baseSetterCalls.setAccessible(true);
    assertEquals(baseSetterCalls.getInt(model), 1);
    assertEquals(modelType.getField("overloadSetterCalls").getInt(model), 0);

    JsonAnySetterAccessor anySetter = codec.anySetterAccessor();
    anySetter.put(model, "extra", "base-any");
    assertEquals(getter.getObject(model), "base-any");
    Field baseAnySetterCalls = baseType.getField("baseAnySetterCalls");
    baseAnySetterCalls.setAccessible(true);
    assertEquals(baseAnySetterCalls.getInt(model), 1);
    assertEquals(modelType.getField("overloadAnySetterCalls").getInt(model), 0);

    JsonFieldAccessor virtual =
        methodAccessor(codec.fieldAccessors(), modelType, "getVirtualValue");
    assertEquals(virtual.getObject(model), "child");
  }

  @Test
  public void generatedCreator() throws Exception {
    CompilationResult result =
        compile(
            "test.CreatorModel",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonType public final class CreatorModel {\n"
                + "  public final int id;\n"
                + "  public final String name;\n"
                + "  @JsonCreator({\"id\", \"name\"})\n"
                + "  public CreatorModel(int id, String name) { this.id = id; this.name = name; }\n"
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    ClassLoader loader = result.classLoader();
    GeneratedJsonCodec<?> codec = generatedCodec(loader, "test.CreatorModel_ForyJsonCodec");
    assertEquals(codec.creatorParameterNames(), new String[] {"id", "name"});
    assertEquals(codec.creatorParameterTypes(), new Class<?>[] {int.class, String.class});
    assertNotSame(codec.creatorParameterNames(), codec.creatorParameterNames());
    assertNotSame(codec.creatorParameterTypes(), codec.creatorParameterTypes());
    Object model = codec.newInstance(new Object[] {7, "json"});
    assertEquals(model.getClass().getField("id").getInt(model), 7);
    assertEquals(model.getClass().getField("name").get(model), "json");
  }

  @Test
  public void runtimePipeline() throws Exception {
    CompilationResult result =
        compile(
            "test.RuntimeModel",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonType public final class RuntimeModel {\n"
                + "  public final int id;\n"
                + "  private final String name;\n"
                + "  @JsonCreator({\"id\", \"name\"})\n"
                + "  public RuntimeModel(int id, String name) { this.id = id; this.name = name; }\n"
                + "  public String getName() { return name; }\n"
                + "  @Override public boolean equals(Object value) {\n"
                + "    if (!(value instanceof RuntimeModel)) { return false; }\n"
                + "    RuntimeModel other = (RuntimeModel) value;\n"
                + "    return id == other.id && name.equals(other.name);\n"
                + "  }\n"
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    assertRoundTrips(
        result,
        "test.RuntimeModel",
        new Class<?>[] {int.class, String.class},
        new Object[] {7, "json"});
  }

  @Test
  public void mutableRuntimePipeline() throws Exception {
    CompilationResult result =
        compile(
            "test.MutableModel",
            "package test;\n"
                + "import java.util.*;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonType public final class MutableModel {\n"
                + "  public int id;\n"
                + "  private String name;\n"
                + "  private final Map<String, Object> extra = new LinkedHashMap<>();\n"
                + "  public MutableModel() {}\n"
                + "  public MutableModel(int id, String name, Map<String, Object> extra) {\n"
                + "    this.id = id; this.name = name; this.extra.putAll(extra);\n"
                + "  }\n"
                + "  public String getName() { return name; }\n"
                + "  public void setName(String name) { this.name = name; }\n"
                + "  @JsonAnyGetter public Map<String, Object> getExtra() { return extra; }\n"
                + "  @JsonAnySetter public void putExtra(String key, Object value) {\n"
                + "    extra.put(key, value);\n"
                + "  }\n"
                + "  @Override public boolean equals(Object value) {\n"
                + "    if (!(value instanceof MutableModel)) { return false; }\n"
                + "    MutableModel other = (MutableModel) value;\n"
                + "    return id == other.id && Objects.equals(name, other.name)\n"
                + "        && extra.equals(other.extra);\n"
                + "  }\n"
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put("enabled", Boolean.TRUE);
    assertRoundTrips(
        result,
        "test.MutableModel",
        new Class<?>[] {int.class, String.class, Map.class},
        new Object[] {5, "mutable", extra});
  }

  @Test
  public void encodedRecordPipeline() throws Exception {
    assumeJava16Source();
    CompilationResult result =
        compile(
            "test.EncodedRecord",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonType public record EncodedRecord(\n"
                + "    @JsonRawValue String raw, @JsonBase64 byte[] bytes) {}\n");
    assertTrue(result.success, result.diagnostics());
    ClassLoader loader = result.classLoader();
    Class<?> type = loader.loadClass("test.EncodedRecord");
    Object value =
        type.getConstructor(String.class, byte[].class)
            .newInstance("{\"id\":1}", new byte[] {1, 2, 3});
    for (ForyJson json : jsonRuntimes(loader)) {
      assertEquals(json.toJson(value), "{\"raw\":{\"id\":1},\"bytes\":\"AQID\"}");
      Object decoded = json.fromJson("{\"raw\":\"text\",\"bytes\":\"AQI=\"}", type);
      assertEquals(type.getMethod("raw").invoke(decoded), "text");
      assertTrue(
          Arrays.equals((byte[]) type.getMethod("bytes").invoke(decoded), new byte[] {1, 2}));
    }
  }

  @Test
  public void encodedCreatorPipeline() throws Exception {
    CompilationResult result =
        compile(
            "test.EncodedCreator",
            "package test;\n"
                + "import java.util.Arrays;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonType public final class EncodedCreator {\n"
                + "  @JsonBase64 public final byte[] bytes;\n"
                + "  @JsonCreator({\"bytes\"}) public EncodedCreator(byte[] bytes) {\n"
                + "    this.bytes = bytes;\n"
                + "  }\n"
                + "  @Override public boolean equals(Object value) {\n"
                + "    return value instanceof EncodedCreator\n"
                + "        && Arrays.equals(bytes, ((EncodedCreator) value).bytes);\n"
                + "  }\n"
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    assertRoundTrips(
        result,
        "test.EncodedCreator",
        new Class<?>[] {byte[].class},
        new Object[] {new byte[] {1, 2, 3}});
  }

  @Test
  public void missingCompanionFails() throws Exception {
    CompilationResult result =
        compile(
            "test.MissingModel",
            "package test;\n"
                + "import org.apache.fory.json.annotation.JsonType;\n"
                + "@JsonType public final class MissingModel {\n"
                + "  public int id;\n"
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    assertTrue(
        Files.deleteIfExists(result.classRoot.resolve("test/MissingModel_ForyJsonCodec.class")));
    ClassLoader loader = result.classLoader();
    Class<?> type = loader.loadClass("test.MissingModel");
    Object value = type.getConstructor().newInstance();
    try {
      ForyJson.builder().withCodegen(false).withClassLoader(loader).build().toJson(value);
      throw new AssertionError("Missing generated JSON companion was accepted");
    } catch (ForyJsonException e) {
      assertTrue(
          e.getMessage().contains("Missing generated JSON codec test.MissingModel_ForyJsonCodec"),
          e.getMessage());
    }
  }

  @Test
  public void unannotatedRuntimePipeline() throws Exception {
    CompilationResult result =
        compile(
            "test.RuntimePlain",
            "package test;\n"
                + "public final class RuntimePlain {\n"
                + "  public int id;\n"
                + "  public String name;\n"
                + "  public RuntimePlain() {}\n"
                + "  public RuntimePlain(int id, String name) { this.id = id; this.name = name; }\n"
                + "  @Override public boolean equals(Object value) {\n"
                + "    if (!(value instanceof RuntimePlain)) { return false; }\n"
                + "    RuntimePlain other = (RuntimePlain) value;\n"
                + "    return id == other.id && name.equals(other.name);\n"
                + "  }\n"
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    assertFalse(result.hasGeneratedSource("test/RuntimePlain_ForyJsonCodec.java"));
    assertRoundTrips(
        result,
        "test.RuntimePlain",
        new Class<?>[] {int.class, String.class},
        new Object[] {5, "plain"});
  }

  @Test
  public void staleCompanionIgnored() throws Exception {
    Map<String, String> sources = new LinkedHashMap<>();
    sources.put(
        "test.StaleModel",
        "package test;\n"
            + "public final class StaleModel {\n"
            + "  public int id;\n"
            + "  public StaleModel() {}\n"
            + "}\n");
    sources.put(
        "test.StaleModel_ForyJsonCodec",
        "package test;\n"
            + "public final class StaleModel_ForyJsonCodec\n"
            + "    extends org.apache.fory.json.codec.GeneratedJsonCodec<StaleModel> {\n"
            + "  public Class<StaleModel> type() {\n"
            + "    throw new AssertionError(\"stale companion loaded\");\n"
            + "  }\n"
            + "  public org.apache.fory.json.meta.JsonFieldAccessor[] fieldAccessors() {\n"
            + "    return new org.apache.fory.json.meta.JsonFieldAccessor[0];\n"
            + "  }\n"
            + "}\n");
    CompilationResult result = compile(sources);
    assertTrue(result.success, result.diagnostics());
    ClassLoader loader = result.classLoader();
    Class<?> type = loader.loadClass("test.StaleModel");
    Object value = type.getConstructor().newInstance();
    type.getField("id").setInt(value, 7);
    for (ForyJson json : jsonRuntimes(loader)) {
      assertEquals(json.toJson(value), "{\"id\":7}");
    }
  }

  @Test
  public void recordRuntimePipeline() throws Exception {
    assumeJava16Source();
    CompilationResult result =
        compile(
            "test.RuntimeRecord",
            "package test;\n"
                + "import org.apache.fory.json.annotation.JsonType;\n"
                + "@JsonType public record RuntimeRecord(int id, String name) {}\n");
    assertTrue(result.success, result.diagnostics());
    assertRoundTrips(
        result,
        "test.RuntimeRecord",
        new Class<?>[] {int.class, String.class},
        new Object[] {11, "record"});
  }

  @Test
  public void recordValuePipeline() throws Exception {
    assumeJava16Source();
    CompilationResult result =
        compile(
            "test.RuntimeValueRecord",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonType public record RuntimeValueRecord(@JsonValue String value) {\n"
                + "  @JsonCreator public RuntimeValueRecord {}\n"
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    assertTrue(result.hasGeneratedSource("test/RuntimeValueRecord_ForyJsonCodec.java"));
    assertRoundTrips(
        result,
        "test.RuntimeValueRecord",
        new Class<?>[] {String.class},
        new Object[] {"record-value"});
  }

  @Test
  public void escapedCreatorNames() throws Exception {
    CompilationResult result =
        compile(
            "test.EscapedCreator",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonType public final class EscapedCreator {\n"
                + "  public final int value;\n"
                + "  @JsonCreator({\"line\\nfeed\\001\"})\n"
                + "  public EscapedCreator(int value) { this.value = value; }\n"
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    GeneratedJsonCodec<?> codec =
        generatedCodec(result.classLoader(), "test.EscapedCreator_ForyJsonCodec");
    assertEquals(codec.creatorParameterNames(), new String[] {"line\nfeed\u0001"});
  }

  @Test
  public void atomicSubtypeCompanion() throws Exception {
    CompilationResult result =
        compile(
            "test.AtomicModel",
            "package test;\n"
                + "import java.util.concurrent.atomic.AtomicReference;\n"
                + "import org.apache.fory.json.annotation.JsonType;\n"
                + "@JsonType public class AtomicModel extends AtomicReference<String> {\n"
                + "  public int tag;\n"
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    assertTrue(result.hasGeneratedSource("test/AtomicModel_ForyJsonCodec.java"));
  }

  @Test
  public void rejectedRuntimeFamily() throws Exception {
    CompilationResult result =
        compile(
            "test.SocketModel",
            "package test;\n"
                + "import java.net.InetSocketAddress;\n"
                + "import org.apache.fory.json.annotation.JsonType;\n"
                + "@JsonType public class SocketModel extends InetSocketAddress {\n"
                + "  public SocketModel() { super(0); }\n"
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    assertFalse(result.hasGeneratedSource("test/SocketModel_ForyJsonCodec.java"));
  }

  @Test
  public void packageMethodsSkipped() throws Exception {
    CompilationResult result =
        compile(
            "test.PackageMethods",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonType public class PackageMethods {\n"
                + "  @JsonProperty String getHidden() { return null; }\n"
                + "  @JsonProperty void setHidden(String value) {}\n"
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    GeneratedJsonCodec<?> codec =
        generatedCodec(result.classLoader(), "test.PackageMethods_ForyJsonCodec");
    assertEquals(codec.fieldAccessors().length, 0);
  }

  @Test
  public void inaccessibleOwners() throws Exception {
    Map<String, String> sources = new LinkedHashMap<>();
    sources.put(
        "hidden.VisibleBase",
        "package hidden;\n"
            + "class HiddenBase {\n"
            + "  public int id;\n"
            + "}\n"
            + "public class VisibleBase extends HiddenBase { public VisibleBase() {} }\n");
    sources.put(
        "test.InaccessibleModel",
        "package test;\n"
            + "import org.apache.fory.json.annotation.JsonType;\n"
            + "@JsonType public final class InaccessibleModel extends hidden.VisibleBase {\n"
            + "  public InaccessibleModel() {}\n"
            + "}\n");
    CompilationResult result = compile(sources);
    assertTrue(result.success, result.diagnostics());
    ClassLoader loader = result.classLoader();
    Class<?> type = loader.loadClass("test.InaccessibleModel");
    GeneratedJsonCodec<?> codec = generatedCodec(loader, "test.InaccessibleModel_ForyJsonCodec");
    assertEquals(codec.fieldAccessors().length, 0);
    Field id = type.getField("id");
    id.setAccessible(true);
    for (ForyJson json : jsonRuntimes(loader)) {
      byte[] bytes = "{\"id\":3}".getBytes(StandardCharsets.UTF_8);
      for (Object value :
          Arrays.asList(
              json.fromJson(new String(bytes, StandardCharsets.UTF_8), type),
              json.fromJson(bytes, type))) {
        assertEquals(id.getInt(value), 3);
        assertEquals(json.toJson(value), "{\"id\":3}");
      }
    }
  }

  @Test
  public void inaccessibleAnySetterType() throws Exception {
    Map<String, String> sources = new LinkedHashMap<>();
    sources.put(
        "hidden.AnyBase",
        "package hidden;\n"
            + "import org.apache.fory.json.annotation.JsonAnySetter;\n"
            + "class HiddenValue { public int value; }\n"
            + "public class AnyBase {\n"
            + "  private Object extraValue;\n"
            + "  @JsonAnySetter public void putExtra(String name, HiddenValue value) {\n"
            + "    extraValue = value;\n"
            + "  }\n"
            + "  public Object getExtraValue() { return extraValue; }\n"
            + "}\n");
    sources.put(
        "test.InaccessibleAnyModel",
        "package test;\n"
            + "import org.apache.fory.json.annotation.JsonType;\n"
            + "@JsonType public final class InaccessibleAnyModel extends hidden.AnyBase {}\n");
    CompilationResult result = compile(sources);
    assertTrue(result.success, result.diagnostics());
    ClassLoader loader = result.classLoader();
    Class<?> type = loader.loadClass("test.InaccessibleAnyModel");
    GeneratedJsonCodec<?> codec = generatedCodec(loader, "test.InaccessibleAnyModel_ForyJsonCodec");
    assertEquals(codec.anySetterAccessor(), null);
    Method getExtraValue = type.getMethod("getExtraValue");
    for (ForyJson json : jsonRuntimes(loader)) {
      byte[] bytes = "{\"unknown\":{\"value\":4}}".getBytes(StandardCharsets.UTF_8);
      for (Object value :
          Arrays.asList(
              json.fromJson(new String(bytes, StandardCharsets.UTF_8), type),
              json.fromJson(bytes, type))) {
        Object extra = getExtraValue.invoke(value);
        Field field = extra.getClass().getField("value");
        field.setAccessible(true);
        assertEquals(field.getInt(extra), 4);
      }
    }
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
  public void unwrappedMemberRules() throws Exception {
    CompilationResult result =
        compile(
            "test.UnwrappedModel",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonType public class UnwrappedModel {\n"
                + "  @JsonUnwrapped(prefix=\"field_\") public Child field;\n"
                + "  private Child property;\n"
                + "  @JsonUnwrapped(prefix=\"property_\") public Child getProperty() { return property; }\n"
                + "  public void setProperty(@JsonUnwrapped(prefix=\"property_\") Child value) { property = value; }\n"
                + "  @JsonCreator public UnwrappedModel(\n"
                + "      @JsonProperty(\"created\") @JsonUnwrapped(prefix=\"created_\") Child value) {}\n"
                + "  @JsonType public static class Child { public String name; }\n"
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    String rules = result.generatedResource(RULE_PREFIX + "test.UnwrappedModel.pro");
    assertTrue(rules.contains("test.UnwrappedModel$Child field;"), rules);
    assertTrue(rules.contains("test.UnwrappedModel$Child getProperty();"), rules);
    assertTrue(rules.contains("void setProperty(test.UnwrappedModel$Child);"), rules);
    assertTrue(rules.contains("<init>(test.UnwrappedModel$Child);"), rules);
    assertTrue(rules.contains("RuntimeVisibleParameterAnnotations"), rules);
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
    assertFalse(result.hasGeneratedSource("test/Hierarchy_ForyJsonCodec.java"));
  }

  @Test
  public void customTypeCodecSkipsCompanion() throws Exception {
    CompilationResult result =
        compile(
            "test.CustomModel",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonType @JsonCodec(CustomModel.Codec.class) public class CustomModel {\n"
                + valueCodec("Codec")
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    assertFalse(result.hasGeneratedSource("test/CustomModel_ForyJsonCodec.java"));
  }

  @Test
  public void nestedCompanionName() throws Exception {
    CompilationResult result =
        compile(
            "test.Outer",
            "package test;\n"
                + "import org.apache.fory.json.annotation.JsonType;\n"
                + "public class Outer {\n"
                + "  @JsonType public static class Inner_Name {\n"
                + "    public int id;\n"
                + "  }\n"
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    assertTrue(result.hasGeneratedSource("test/Outer_d_Inner_u_Name_ForyJsonCodec.java"));
    String rules = result.generatedResource(RULE_PREFIX + "test.Outer$Inner_Name.pro");
    assertTrue(rules.contains("class test.Outer_d_Inner_u_Name_ForyJsonCodec {"), rules);
  }

  @Test
  public void companionNamesDoNotCollide() throws Exception {
    Map<String, String> sources = new LinkedHashMap<>();
    sources.put(
        "test.A",
        "package test;\n"
            + "import org.apache.fory.json.annotation.JsonType;\n"
            + "public class A { @JsonType public static class u_X { public int id; } }\n");
    sources.put(
        "test.A_u",
        "package test;\n"
            + "import org.apache.fory.json.annotation.JsonType;\n"
            + "public class A_u { @JsonType public static class X { public int id; } }\n");
    CompilationResult result = compile(sources);
    assertTrue(result.success, result.diagnostics());
    assertTrue(result.hasGeneratedSource("test/A_d_u_u_X_ForyJsonCodec.java"));
    assertTrue(result.hasGeneratedSource("test/A_u_u_d_X_ForyJsonCodec.java"));
  }

  @Test
  public void inaccessibleModelDiagnostic() throws Exception {
    CompilationResult result =
        compile(
            "test.PrivateModelOwner",
            "package test;\n"
                + "import org.apache.fory.json.annotation.JsonType;\n"
                + "public class PrivateModelOwner {\n"
                + "  @JsonType private static class PrivateModel {}\n"
                + "}\n");
    assertFalse(result.success);
    assertTrue(
        result.diagnostics().contains("@JsonType model is not accessible to generated JSON code"),
        result.diagnostics());
  }

  @Test
  public void creatorDiagnostic() throws Exception {
    CompilationResult result =
        compile(
            "test.InvalidCreator",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonType public class InvalidCreator {\n"
                + "  @JsonCreator private InvalidCreator(@JsonProperty(\"id\") int id) {}\n"
                + "}\n");
    assertFalse(result.success);
    assertTrue(result.diagnostics().contains("@JsonCreator must be public"), result.diagnostics());
  }

  @Test
  public void memberCreatorConstructorDiagnostic() throws Exception {
    CompilationResult result =
        compile(
            "test.MemberCreatorOwner",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "public class MemberCreatorOwner {\n"
                + "  @JsonType public class Value {\n"
                + "    @JsonCreator public Value(@JsonProperty(\"id\") int id) {}\n"
                + "  }\n"
                + "}\n");
    assertFalse(result.success);
    assertTrue(
        result
            .diagnostics()
            .contains("A non-static member class must use a static @JsonCreator factory"),
        result.diagnostics());
  }

  @Test
  public void memberCreatorFactory() throws Exception {
    assumeJava16Source();
    CompilationResult result =
        compile(
            "test.MemberFactoryOwner",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "public class MemberFactoryOwner {\n"
                + "  @JsonType public class Value {\n"
                + "    public final int id;\n"
                + "    private Value(int id) { this.id = id; }\n"
                + "    @JsonCreator public static Value create(@JsonProperty(\"id\") int id) {\n"
                + "      return new MemberFactoryOwner().new Value(id);\n"
                + "    }\n"
                + "  }\n"
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    assertTrue(result.hasGeneratedSource("test/MemberFactoryOwner_d_Value_ForyJsonCodec.java"));
  }

  @Test
  public void creatorPropertyListDiagnostic() throws Exception {
    CompilationResult result =
        compile(
            "test.InvalidPropertyListCreator",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonType public class InvalidPropertyListCreator {\n"
                + "  public final int id;\n"
                + "  @JsonCreator({\"id\"})\n"
                + "  public InvalidPropertyListCreator(@JsonProperty(\"id\") int id) {\n"
                + "    this.id = id;\n"
                + "  }\n"
                + "}\n");
    assertFalse(result.success);
    assertTrue(
        result
            .diagnostics()
            .contains("Property-list @JsonCreator parameters cannot declare @JsonProperty"),
        result.diagnostics());
  }

  @Test
  public void typeDeclarationCodecRules() throws Exception {
    CompilationResult result =
        compile(
            "test.DeclarationModel",
            "package test;\n"
                + "import java.util.List;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonCodec(DeclarationModel.DominatedCodec.class) interface BaseContract {}\n"
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
                + valueCodecs("DirectCodec", "InheritedCodec", "ParameterCodec", "DominatedCodec")
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
    assertTrue(
        rules.contains("class test.DeclarationModel$DominatedCodec { public <init>(); }"), rules);
    assertTrue(rules.contains("class test.BaseContract"), rules);
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
    assumeJava16Source();
    CompilationResult result =
        compile(
            "test.CodecRecord",
            "package test;\n"
                + "import java.util.List;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonType public record CodecRecord(\n"
                + "    int id, String name,\n"
                + "    @JsonCodec(elementCodec = ElementCodec.class) List<String> values) {\n"
                + valueCodec("ElementCodec")
                + "}\n");
    assertTrue(result.success, result.diagnostics());
    String rules = result.generatedResource(RULE_PREFIX + "test.CodecRecord.pro");
    assertTrue(rules.contains("java.util.List values;"), rules);
    assertTrue(rules.contains("int id();"), rules);
    assertTrue(rules.contains("java.lang.String name();"), rules);
    assertTrue(rules.contains("java.util.List values();"), rules);
    assertTrue(rules.contains("<init>(int,java.lang.String,java.util.List);"), rules);
    assertTrue(rules.contains("class test.CodecRecord$ElementCodec { public <init>(); }"), rules);
    assertTrue(result.hasGeneratedSource("test/CodecRecord_ForyJsonCodec.java"));
    GeneratedJsonCodec<?> codec =
        generatedCodec(result.classLoader(), "test.CodecRecord_ForyJsonCodec");
    assertTrue(codec.isRecord());
    assertEquals(codec.creatorParameterNames(), new String[] {"id", "name", "values"});
    Object record =
        codec.newInstance(new Object[] {7, "record", Collections.singletonList("fory")});
    assertEquals(record.getClass().getMethod("id").invoke(record), 7);
    assertEquals(record.getClass().getMethod("name").invoke(record), "record");
    assertEquals(
        record.getClass().getMethod("values").invoke(record), Collections.singletonList("fory"));
  }

  @Test
  public void recordParameterAnnotation() throws Exception {
    assumeJava16Source();
    CompilationResult result =
        compile(
            "test.InvalidRecord",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonType public record InvalidRecord(Child child) {\n"
                + "  public InvalidRecord(@JsonUnwrapped(prefix=\"child_\") Child child) {\n"
                + "    this.child = child;\n"
                + "  }\n"
                + "  @JsonType public record Child(String name) {}\n"
                + "}\n");
    assertFalse(result.success);
    assertTrue(
        result
            .diagnostics()
            .contains(
                "Canonical Record constructor parameter @JsonUnwrapped must match the corresponding Record field or accessor"),
        result.diagnostics());

    CompilationResult overload =
        compile(
            "test.InvalidOverload",
            "package test;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonType public record InvalidOverload(Child child, int id) {\n"
                + "  public InvalidOverload(@JsonUnwrapped Child child) { this(child, 0); }\n"
                + "  @JsonType public record Child(String name) {}\n"
                + "}\n");
    assertFalse(overload.success);
    assertTrue(
        overload
            .diagnostics()
            .contains(
                "JSON property annotations are not supported on non-canonical Record constructor parameters"),
        overload.diagnostics());
  }

  @Test
  public void ignoredRecordComponent() throws Exception {
    assumeJava16Source();
    CompilationResult result =
        compile(
            "test.IgnoredRecord",
            "package test;\n"
                + "import java.util.Map;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "@JsonType public record IgnoredRecord(\n"
                + "    @JsonIgnore(ignoreWrite = false) int id,\n"
                + "    @JsonAnyProperty Map<String, Object> extra) {}\n");
    assertTrue(result.success, result.diagnostics());
    ClassLoader loader = result.classLoader();
    Class<?> type = loader.loadClass("test.IgnoredRecord");
    byte[] bytes = "{\"id\":9,\"unknown\":1}".getBytes(StandardCharsets.UTF_8);
    for (ForyJson json : jsonRuntimes(loader)) {
      for (Object value :
          Arrays.asList(
              json.fromJson(new String(bytes, StandardCharsets.UTF_8), type),
              json.fromJson(bytes, type))) {
        assertEquals(type.getMethod("id").invoke(value), 0);
        Map<?, ?> extra = (Map<?, ?>) type.getMethod("extra").invoke(value);
        assertEquals(extra.size(), 1);
        assertTrue(extra.containsKey("unknown"));
      }
    }
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
    assertFalse(result.hasGeneratedSource("test/Base_ForyJsonCodec.java"));
    assertFalse(result.hasGeneratedSource("test/Child_ForyJsonCodec.java"));
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
    assertTrue(result.hasGeneratedSource("test/Both_ForyJsonCodec.java"));
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
  public void valueRawAndBase64Rules() throws Exception {
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
            + "  @JsonBase64 public byte[] bytes;\n"
            + "  private String other;\n"
            + "  @JsonRawValue public String getOther() { return other; }\n"
            + "  public void setOther(String other) { this.other = other; }\n"
            + "}\n");
    CompilationResult result = compile(sources);
    assertTrue(result.success, result.diagnostics());

    String valueRules = result.generatedResource(RULE_PREFIX + "test.ValueModel.pro");
    assertFalse(result.hasGeneratedSource("test/ValueModel_ForyJsonCodec.java"));
    assertTrue(valueRules.contains("java.lang.String value();"), valueRules);
    assertTrue(valueRules.contains("<init>(java.lang.String);"), valueRules);
    assertTrue(
        valueRules.contains("@interface org.apache.fory.json.annotation.JsonValue"), valueRules);
    assertTrue(
        valueRules.contains("@interface org.apache.fory.json.annotation.JsonRawValue"), valueRules);

    String rawRules = result.generatedResource(RULE_PREFIX + "test.RawModel.pro");
    assertTrue(result.hasGeneratedSource("test/RawModel_ForyJsonCodec.java"));
    assertTrue(rawRules.contains("java.lang.String body;"), rawRules);
    assertTrue(rawRules.contains("byte[] bytes;"), rawRules);
    assertTrue(rawRules.contains("java.lang.String getOther();"), rawRules);
    assertTrue(
        rawRules.contains("@interface org.apache.fory.json.annotation.JsonRawValue"), rawRules);
    assertTrue(
        rawRules.contains("@interface org.apache.fory.json.annotation.JsonBase64"), rawRules);
    assertFalse(
        rawRules.contains("@interface org.apache.fory.json.annotation.JsonCodec"), rawRules);
    assertTrue(
        rawRules.contains(
            "class org.apache.fory.json.codec.Base64ByteArrayCodec { public <init>(); }"),
        rawRules);
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
    assertTrue(result.hasGeneratedSource("test/ValueChild_ForyJsonCodec.java"));
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

  private static GeneratedJsonCodec<?> generatedCodec(ClassLoader loader, String name)
      throws Exception {
    return (GeneratedJsonCodec<?>) loader.loadClass(name).getConstructor().newInstance();
  }

  private static void assertRoundTrips(
      CompilationResult result, String typeName, Class<?>[] parameterTypes, Object[] arguments)
      throws Exception {
    ClassLoader loader = result.classLoader();
    Class<?> type = loader.loadClass(typeName);
    Object value = type.getConstructor(parameterTypes).newInstance(arguments);
    for (ForyJson json : jsonRuntimes(loader)) {
      assertRoundTrip(json, type, value);
    }
  }

  private static ForyJson[] jsonRuntimes(ClassLoader loader) {
    return new ForyJson[] {
      ForyJson.builder().withCodegen(false).withClassLoader(loader).build(),
      ForyJson.builder()
          .withCodegen(true)
          .withAsyncCompilation(false)
          .withClassLoader(loader)
          .build()
    };
  }

  private static void assertRoundTrip(ForyJson json, Class<?> type, Object value) {
    String text = json.toJson(value);
    assertEquals(json.fromJson(text, type), value);
  }

  private static JsonFieldAccessor fieldAccessor(JsonFieldAccessor[] accessors, String name) {
    for (JsonFieldAccessor accessor : accessors) {
      if (accessor.field() != null && accessor.field().getName().equals(name)) {
        return accessor;
      }
    }
    throw new AssertionError("Missing generated field accessor " + name);
  }

  private static JsonFieldAccessor fieldAccessor(
      JsonFieldAccessor[] accessors, Class<?> owner, String name) {
    for (JsonFieldAccessor accessor : accessors) {
      if (accessor.field() != null
          && accessor.field().getDeclaringClass() == owner
          && accessor.field().getName().equals(name)) {
        return accessor;
      }
    }
    throw new AssertionError("Missing generated field accessor " + owner.getName() + "." + name);
  }

  private static JsonFieldAccessor methodAccessor(JsonFieldAccessor[] accessors, String name) {
    for (JsonFieldAccessor accessor : accessors) {
      Method method = accessor.getter() != null ? accessor.getter() : accessor.setter();
      if (method != null && method.getName().equals(name)) {
        return accessor;
      }
    }
    throw new AssertionError("Missing generated method accessor " + name);
  }

  private static JsonFieldAccessor methodAccessor(
      JsonFieldAccessor[] accessors, Class<?> owner, String name, Class<?>... parameterTypes) {
    for (JsonFieldAccessor accessor : accessors) {
      Method method = accessor.getter() != null ? accessor.getter() : accessor.setter();
      if (method != null
          && method.getDeclaringClass() == owner
          && method.getName().equals(name)
          && Arrays.equals(method.getParameterTypes(), parameterTypes)) {
        return accessor;
      }
    }
    throw new AssertionError("Missing generated method accessor " + owner.getName() + "." + name);
  }

  private static void assumeJava16Source() {
    String version = System.getProperty("java.specification.version");
    if (version.startsWith("1.")) {
      version = version.substring(2);
    }
    int dotIndex = version.indexOf('.');
    if (dotIndex >= 0) {
      version = version.substring(0, dotIndex);
    }
    if (Integer.parseInt(version) < 16) {
      throw new SkipException("Source test requires JDK 16 or newer");
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

    ClassLoader classLoader() throws IOException {
      return new URLClassLoader(
          new URL[] {classRoot.toUri().toURL()}, JsonTypeProcessorTest.class.getClassLoader());
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

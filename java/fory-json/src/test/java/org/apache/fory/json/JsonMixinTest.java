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

package org.apache.fory.json;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.fory.json.annotation.JsonAnyGetter;
import org.apache.fory.json.annotation.JsonAnyProperty;
import org.apache.fory.json.annotation.JsonAnySetter;
import org.apache.fory.json.annotation.JsonBase64;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonIgnore;
import org.apache.fory.json.annotation.JsonMixin;
import org.apache.fory.json.annotation.JsonMixinRemove;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonPropertyOrder;
import org.apache.fory.json.annotation.JsonRawValue;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.apache.fory.json.annotation.JsonType;
import org.apache.fory.json.annotation.JsonUnwrapped;
import org.apache.fory.json.annotation.JsonValue;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonSharedRegistry;
import org.apache.fory.json.resolver.JsonSharedRegistry.JsonMixinView;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.platform.JdkVersion;
import org.apache.fory.reflect.TypeRef;
import org.testng.SkipException;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class JsonMixinTest extends ForyJsonTestModels {
  @Factory(dataProvider = "enableCodegen")
  public JsonMixinTest(boolean codegen) {
    super(codegen);
  }

  @Test
  public void propertyAnnotations() {
    ForyJson json = newJsonBuilder().registerMixIn(BasicMixIn.class).build();
    BasicTarget value = basic("alpha");
    String expected =
        "{\"display_name\":\"alpha\",\"body\":{\"ok\":true},\"bytes\":\"AQID\","
            + "\"child_label\":\"kid\",\"ignored\":7}";
    assertEquals(json.toJson(value), expected);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), expected);
    assertEquals(json.toJson(value, BasicTarget.class), expected);

    TypeRef<BasicTarget> type = new TypeRef<BasicTarget>() {};
    assertEquals(json.toJson(value, type), expected);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    json.writeJsonTo(value, type, output);
    assertEquals(new String(output.toByteArray(), StandardCharsets.UTF_8), expected);

    String latin =
        "{\"display_name\":\"latin\",\"body\":\"text\",\"bytes\":\"AQID\","
            + "\"child_label\":\"child\",\"ignored\":99}";
    BasicTarget latinValue = json.fromJson(latin, BasicTarget.class);
    assertBasic(latinValue, "latin", "text", "child");
    assertEquals(latinValue.ignored, 0);

    String unicode = latin.replace("latin", "你好");
    assertBasic(json.fromJson(unicode, type), "你好", "text", "child");
    assertBasic(
        json.fromJson(latin.getBytes(StandardCharsets.UTF_8), BasicTarget.class),
        "latin",
        "text",
        "child");
    assertGeneratedWhenSupported(json, BasicTarget.class);
  }

  @Test
  public void anyAnnotations() {
    ForyJson fieldJson = newJsonBuilder().registerMixIn(AnyFieldMixIn.class).build();
    AnyFieldTarget field = new AnyFieldTarget();
    field.extra.put("dynamic", "one");
    assertEquals(fieldJson.toJson(field), "{\"dynamic\":\"A:one\"}");
    assertEquals(
        fieldJson.fromJson("{\"dynamic\":\"A:two\"}", AnyFieldTarget.class).extra.get("dynamic"),
        "two");

    ForyJson methodJson = newJsonBuilder().registerMixIn(AnyMethodMixIn.class).build();
    AnyMethodTarget method = new AnyMethodTarget();
    method.extra.put("dynamic", "three");
    assertEquals(methodJson.toJson(method), "{\"dynamic\":\"A:three\"}");
    assertEquals(
        methodJson.fromJson("{\"dynamic\":\"A:four\"}", AnyMethodTarget.class).extra.get("dynamic"),
        "four");
    assertGeneratedWhenSupported(fieldJson, AnyFieldTarget.class);
    assertGeneratedWhenSupported(methodJson, AnyMethodTarget.class);
  }

  @Test
  public void creatorsAndValue() {
    ForyJson constructorJson =
        newJsonBuilder().registerMixIn(ConstructorCreatorMixIn.class).build();
    CreatorTarget constructor =
        constructorJson.fromJson("{\"id\":1,\"name\":\"one\"}", CreatorTarget.class);
    assertEquals(constructor.route, "constructor");
    assertEquals(constructorJson.toJson(constructor), "{\"id\":1,\"name\":\"one\"}");

    ForyJson factoryJson = newJsonBuilder().registerMixIn(FactoryCreatorMixIn.class).build();
    CreatorTarget factory =
        factoryJson.fromJson("{\"id\":2,\"name\":\"two\"}", CreatorTarget.class);
    assertEquals(factory.route, "factory");

    ForyJson valueJson = newJsonBuilder().registerMixIn(ValueMixIn.class).build();
    assertEquals(valueJson.toJson(new ValueTarget("write")), "\"write\"");
    ValueTarget decoded = valueJson.fromJson("\"read\"", ValueTarget.class);
    assertEquals(decoded.text(), "read");
    assertEquals(decoded.route, "factory");
  }

  @Test
  public void typeAnnotations() {
    ForyJson subtypeJson = newJsonBuilder().registerMixIn(ShapeMixIn.class).build();
    Shape value = new Circle(3);
    assertEquals(subtypeJson.toJson(value, Shape.class), "{\"kind\":\"circle\",\"radius\":3}");
    Shape decoded = subtypeJson.fromJson("{\"kind\":\"circle\",\"radius\":4}", Shape.class);
    assertTrue(decoded instanceof Circle);
    assertEquals(((Circle) decoded).radius, 4);

    ForyJson codecJson = newJsonBuilder().registerMixIn(WholeTargetMixIn.class).build();
    assertEquals(codecJson.toJson(new WholeTarget("write")), "\"whole:write\"");
    assertEquals(codecJson.fromJson("\"whole:read\"", WholeTarget.class).value, "read");
  }

  @Test
  public void registrationLifecycle() {
    ForyJsonBuilder builder = newJsonBuilder().registerMixIn(FirstNameMixIn.class);
    ForyJson first = builder.build();
    builder.registerMixIn(SecondNameMixIn.class);
    ForyJson second = builder.build();

    assertEquals(first.toJson(new NameTarget("value")), "{\"first\":\"value\"}");
    assertEquals(second.toJson(new NameTarget("value")), "{\"second\":\"value\"}");
    assertEquals(first.toJson(new NameTarget("again")), "{\"first\":\"again\"}");

    ForyJson repeated =
        newJsonBuilder()
            .registerMixIn(FirstNameMixIn.class)
            .registerMixIn(FirstNameMixIn.class)
            .build();
    ForyJson equivalent = newJsonBuilder().registerMixIn(FirstNameMixIn.class).build();
    assertEquals(JsonTestSupport.config(repeated), JsonTestSupport.config(equivalent));
    assertEquals(
        JsonTestSupport.config(repeated).getCodegenHash(),
        JsonTestSupport.config(equivalent).getCodegenHash());
    assertNotEquals(JsonTestSupport.config(first), JsonTestSupport.config(second));
    assertNotEquals(
        JsonTestSupport.config(first).getCodegenHash(),
        JsonTestSupport.config(second).getCodegenHash());
  }

  @Test
  public void exactTargets() {
    ForyJson parentJson = newJsonBuilder().registerMixIn(ExactParentMixIn.class).build();
    assertEquals(parentJson.toJson(new ExactParent()), "{\"parent_name\":\"parent\"}");
    assertEquals(parentJson.toJson(new ExactChild()), "{\"name\":\"parent\",\"rank\":2}");

    ForyJson childJson = newJsonBuilder().registerMixIn(ExactChildMixIn.class).build();
    assertEquals(childJson.toJson(new ExactParent()), "{\"name\":\"parent\"}");
    assertEquals(childJson.toJson(new ExactChild()), "{\"child_name\":\"parent\",\"rank\":2}");

    ForyJson nestedJson =
        newJsonBuilder()
            .registerMixIn(BasicMixIn.class)
            .registerMixIn(BasicChildMixIn.class)
            .build();
    assertEquals(
        nestedJson.toJson(basic("nested")),
        "{\"display_name\":\"nested\",\"body\":{\"ok\":true},\"bytes\":\"AQID\","
            + "\"child_caption\":\"kid\",\"ignored\":7}");
  }

  @Test
  public void replacementAndRemoval() {
    ForyJson replacement = newJsonBuilder().registerMixIn(ReplacementMixIn.class).build();
    ReplacementTarget replacementValue = new ReplacementTarget();
    replacementValue.value = "value";
    assertEquals(
        replacement.toJson(replacementValue), "{\"mixed\":\"value\",\"new_label\":\"kid\"}");
    replacementValue.value = null;
    assertEquals(replacement.toJson(replacementValue), "{\"new_label\":\"kid\"}");
    ReplacementTarget replacementDecoded =
        replacement.fromJson(
            "{\"mixed\":\"read\",\"new_label\":\"child\"}", ReplacementTarget.class);
    assertEquals(replacementDecoded.value, "read");
    assertEquals(replacementDecoded.child.label, "child");

    ForyJson representation =
        newJsonBuilder().registerMixIn(RepresentationRemoveMixIn.class).build();
    assertEquals(
        representation.toJson(new RepresentationRemoveTarget()),
        "{\"name\":\"name\",\"raw\":\"1\",\"bytes\":[1],"
            + "\"child\":{\"label\":\"kid\"},\"hidden\":7}");

    ForyJson anyField = newJsonBuilder().registerMixIn(AnyFieldRemoveMixIn.class).build();
    assertEquals(anyField.toJson(new AnyFieldRemoveTarget()), "{\"extra\":{\"x\":1}}");

    ForyJson anyMethods = newJsonBuilder().registerMixIn(AnyMethodRemoveMixIn.class).build();
    assertEquals(anyMethods.toJson(new AnyMethodRemoveTarget()), "{\"extra\":{\"x\":1}}");
    AnyMethodRemoveTarget anyDecoded =
        anyMethods.fromJson("{\"unknown\":2}", AnyMethodRemoveTarget.class);
    assertEquals(anyDecoded.extra, map("x", 1));

    ForyJson value = newJsonBuilder().registerMixIn(ValueRemoveMixIn.class).build();
    assertEquals(value.toJson(new ValueRemoveTarget("write")), "{\"text\":\"write\"}");
    assertEquals(value.fromJson("{\"text\":\"read\"}", ValueRemoveTarget.class).text, "read");

    ForyJson creator = newJsonBuilder().registerMixIn(CreatorRemoveMixIn.class).build();
    CreatorRemoveTarget creatorValue = creator.fromJson("{\"id\":5}", CreatorRemoveTarget.class);
    assertEquals(creatorValue.id, 5);
    assertEquals(creatorValue.route, "default");

    ForyJson subtype = newJsonBuilder().registerMixIn(ShapeRemoveMixIn.class).build();
    assertThrows(
        ForyJsonException.class, () -> subtype.toJson(new RemovedCircle(), RemovedShape.class));

    ForyJson codec = newJsonBuilder().registerMixIn(CodecBarrierMixIn.class).build();
    assertEquals(newJson().toJson(new CodecBarrierChild()), "\"inherited\"");
    assertEquals(codec.toJson(new CodecBarrierChild()), "{\"name\":\"child\"}");

    ForyJson order = newJsonBuilder().registerMixIn(OrderBarrierMixIn.class).build();
    assertEquals(newJson().toJson(new OrderBarrierChild()), "{\"b\":2,\"a\":1}");
    assertEquals(order.toJson(new OrderBarrierChild()), "{\"a\":1,\"b\":2}");
  }

  @Test
  public void matching() {
    ForyJson overload = newJsonBuilder().registerMixIn(OverloadMixIn.class).build();
    OverloadTarget value = overload.fromJson("{\"text\":\"value\"}", OverloadTarget.class);
    assertEquals(value.value, "value");
    assertEquals(overload.toJson(value), "{\"text\":\"value\"}");

    ForyJson helper = newJsonBuilder().registerMixIn(HelperMixIn.class).build();
    assertEquals(helper.toJson(new SelectorTarget()), "{\"name\":\"target\"}");

    assertThrows(NullPointerException.class, () -> newJsonBuilder().registerMixIn(null));
    assertRegistrationInvalid(BasicTarget.class);
    assertRegistrationInvalid(ConcreteMixIn.class);
    assertRegistrationInvalid(SourceHierarchyMixIn.class);
    assertRegistrationInvalid(PrimitiveTargetMixIn.class);
    assertRegistrationInvalid(SelfMixIn.class);
    @JsonMixin(target = BasicTarget.class)
    abstract class LocalMixIn {}
    assertRegistrationInvalid(LocalMixIn.class);
    assertStructureInvalid(UnmatchedFieldMixIn.class);
    assertStructureInvalid(ReturnTypeMixIn.class);
    assertStructureInvalid(HiddenFieldMixIn.class);
    assertStructureInvalid(StaticFieldMixIn.class);
    assertStructureInvalid(ConcreteMethodMixIn.class);
    assertStructureInvalid(JsonTypeMixIn.class);
    assertStructureInvalid(RemoveJsonTypeMixIn.class);
    assertStructureInvalid(EmptyRemoveMixIn.class);
    assertStructureInvalid(DuplicateRemoveMixIn.class);
    assertStructureInvalid(UnsupportedRemoveMixIn.class);
    assertStructureInvalid(IllegalRemoveMixIn.class);
    assertStructureInvalid(AddRemoveMixIn.class);
  }

  @Test
  public void semanticFailureRollsBack() {
    ForyJson json = newJsonBuilder().registerMixIn(SemanticFailureMixIn.class).build();
    SemanticFailureTarget value = new SemanticFailureTarget();
    value.body = "text";
    ForyJsonException writeFailure =
        expectThrows(ForyJsonException.class, () -> json.toJson(value));
    assertSemanticContext(writeFailure);
    assertFalse(
        JsonTestSupport.resolverContains(json, "objectCodecs", SemanticFailureTarget.class));
    assertFalse(JsonTestSupport.resolverContains(json, "typeInfos", SemanticFailureTarget.class));

    ForyJsonException readFailure =
        expectThrows(
            ForyJsonException.class,
            () -> json.fromJson("{\"body\":\"text\"}", SemanticFailureTarget.class));
    assertSemanticContext(readFailure);
    assertFalse(
        JsonTestSupport.resolverContains(json, "objectCodecs", SemanticFailureTarget.class));
    assertFalse(JsonTestSupport.resolverContains(json, "typeInfos", SemanticFailureTarget.class));
    assertEquals(json.toJson(new NameTarget("valid")), "{\"name\":\"valid\"}");
  }

  @Test
  public void semanticDiagnostics() {
    assertPairFailure(InvalidTypeCodecMixIn.class, InvalidTypeCodecTarget.class);
    assertPairFailure(InvalidSubTypesMixIn.class, InvalidSubTypesTarget.class);
    assertPairFailure(InvalidValueMixIn.class, InvalidValueTarget.class);
    assertPairFailure(IntrinsicMemberMixIn.class, IntrinsicMemberTarget.class);
    assertPairFailure(CodecFamilyMixIn.class, CodecFamilyTarget.class);
    assertPairFailure(ValueFamilyMixIn.class, ValueFamilyTarget.class);
    assertPairFailure(SubtypeFamilyMixIn.class, SubtypeFamilyTarget.class);
    assertPairFailure(ObjectParameterMixIn.class, ObjectParameterTarget.class);
  }

  @Test
  public void directRemoveRejected() {
    for (Class<?> type :
        new Class<?>[] {
          DirectTypeRemove.class,
          DirectFieldRemove.class,
          DirectMethodRemove.class,
          DirectConstructorRemove.class,
          DirectParameterRemove.class
        }) {
      ForyJsonException failure =
          expectThrows(ForyJsonException.class, () -> newJson().fromJson("{}", type));
      assertTrue(failure.getMessage().contains("@JsonMixinRemove"), failure.getMessage());
    }
  }

  @Test
  public void hostedSourceClosure() throws Exception {
    JsonMixinView view =
        JsonSharedRegistry.resolveMixin(HostedSourceTarget.class, HostedSourceMixIn.class);
    assertEquals(view.sourceDeclarations().size(), 1);
    assertTrue(view.sourceDeclarations().contains(HostedSourceMixIn.class.getDeclaredField("id")));
    assertFalse(
        view.sourceDeclarations().contains(HostedSourceMixIn.class.getDeclaredField("helper")));
    assertThrows(
        ForyJsonException.class,
        () -> JsonSharedRegistry.resolveMixin(SelectorTarget.class, ConcreteMixIn.class));
  }

  @Test
  public void memberSourceConstructor() {
    ForyJson json = newJsonBuilder().registerMixIn(MemberCtorMixIn.class).build();
    assertEquals(json.fromJson("{\"id\":7}", MemberCtorTarget.class).id, 7);
  }

  private void assertPairFailure(Class<?> mixInType, Class<?> targetType) {
    ForyJson json = newJsonBuilder().registerMixIn(mixInType).build();
    ForyJsonException failure =
        expectThrows(ForyJsonException.class, () -> json.fromJson("{}", targetType));
    String message = failure.getMessage();
    assertTrue(message.contains(targetType.getName()), message);
    assertTrue(message.contains(mixInType.getName()), message);
  }

  private static void assertSemanticContext(ForyJsonException failure) {
    String message = failure.getMessage();
    assertTrue(message.contains(SemanticFailureTarget.class.getName()), message);
    assertTrue(message.contains(SemanticFailureMixIn.class.getName()), message);
    assertTrue(message.contains("body"), message);
  }

  @Test
  public void recordSelectors() throws Exception {
    if (JdkVersion.MAJOR_VERSION < 17) {
      throw new SkipException("Java record test requires JDK 17+");
    }
    String simpleName =
        codegenEnabled() ? "JsonMixinGeneratedRecord" : "JsonMixinInterpretedRecord";
    String mixInName = simpleName + "MixIn";
    Class<?> type =
        compileRecordClass(
            simpleName,
            "package org.apache.fory.json.records;\n"
                + "import org.apache.fory.json.annotation.*;\n"
                + "public record "
                + simpleName
                + "(String name, byte[] bytes) {}\n"
                + "@JsonMixin(target = "
                + simpleName
                + ".class)\n"
                + "@JsonPropertyOrder({\"display_name\", \"bytes\"})\n"
                + "abstract class "
                + mixInName
                + " {\n"
                + "  @JsonProperty(\"display_name\") String name;\n"
                + "  @JsonBase64 byte[] bytes;\n"
                + "  "
                + mixInName
                + "(\n"
                + "      @JsonProperty(\"display_name\") String name, byte[] bytes) {}\n"
                + "}\n");
    Class<?> mixIn =
        Class.forName("org.apache.fory.json.records." + mixInName, true, type.getClassLoader());
    Object record =
        type.getConstructor(String.class, byte[].class).newInstance("record", new byte[] {1, 2, 3});
    ForyJson json = newJsonBuilder().registerMixIn(mixIn).build();
    assertEquals(json.toJson(record), "{\"display_name\":\"record\",\"bytes\":\"AQID\"}");
    Object decoded = json.fromJson("{\"display_name\":\"read\",\"bytes\":\"AQID\"}", type);
    assertEquals(type.getMethod("name").invoke(decoded), "read");
    assertEquals(type.getMethod("bytes").invoke(decoded), new byte[] {1, 2, 3});
    assertGeneratedWhenSupported(json, type);
  }

  private void assertRegistrationInvalid(Class<?> mixIn) {
    assertThrows(IllegalArgumentException.class, () -> newJsonBuilder().registerMixIn(mixIn));
  }

  private void assertStructureInvalid(Class<?> mixIn) {
    assertThrows(ForyJsonException.class, () -> newJsonBuilder().registerMixIn(mixIn).build());
  }

  private static BasicTarget basic(String name) {
    BasicTarget value = new BasicTarget();
    value.name = name;
    value.body = "{\"ok\":true}";
    value.bytes = new byte[] {1, 2, 3};
    value.child = new BasicChild("kid");
    value.ignored = 7;
    return value;
  }

  private static void assertBasic(BasicTarget value, String name, String body, String child) {
    assertEquals(value.name, name);
    assertEquals(value.body, body);
    assertEquals(value.bytes, new byte[] {1, 2, 3});
    assertEquals(value.child.label, child);
  }

  private static Map<String, Integer> map(String name, int value) {
    Map<String, Integer> result = new LinkedHashMap<>();
    result.put(name, value);
    return result;
  }

  public static final class BasicTarget {
    public String name;
    public String body;
    public byte[] bytes;
    public BasicChild child;
    public int ignored;
  }

  public static final class BasicChild {
    public String label;

    public BasicChild() {}

    BasicChild(String label) {
      this.label = label;
    }
  }

  @JsonMixin(target = BasicTarget.class)
  @JsonPropertyOrder({"display_name", "body", "bytes", "child", "ignored"})
  public abstract static class BasicMixIn {
    @JsonProperty("display_name")
    String name;

    @JsonRawValue String body;
    @JsonBase64 byte[] bytes;

    @JsonUnwrapped(prefix = "child_")
    BasicChild child;

    @JsonIgnore(ignoreRead = true, ignoreWrite = false)
    int ignored;
  }

  @JsonMixin(target = BasicChild.class)
  public abstract static class BasicChildMixIn {
    @JsonProperty("caption")
    String label;
  }

  public static final class AnyFieldTarget {
    public Map<String, String> extra = new LinkedHashMap<>();
  }

  @JsonMixin(target = AnyFieldTarget.class)
  public abstract static class AnyFieldMixIn {
    @JsonAnyProperty
    @JsonCodec(valueCodec = JsonCodecAnnotationTest.AStringCodec.class)
    Map<String, String> extra;
  }

  public static final class AnyMethodTarget {
    private final Map<String, String> extra = new LinkedHashMap<>();

    public Map<String, String> getExtra() {
      return extra;
    }

    public void put(String name, String value) {
      extra.put(name, value);
    }
  }

  @JsonMixin(target = AnyMethodTarget.class)
  public interface AnyMethodMixIn {
    @JsonAnyGetter
    @JsonCodec(valueCodec = JsonCodecAnnotationTest.AStringCodec.class)
    Map<String, String> getExtra();

    @JsonAnySetter
    void put(String name, @JsonCodec(JsonCodecAnnotationTest.AStringCodec.class) String value);
  }

  public static final class CreatorTarget {
    public final int id;
    public final String name;

    @JsonIgnore public final String route;

    public CreatorTarget(int id, String name) {
      this(id, name, "constructor");
    }

    private CreatorTarget(int id, String name, String route) {
      this.id = id;
      this.name = name;
      this.route = route;
    }

    public static CreatorTarget create(int id, String name) {
      return new CreatorTarget(id, name, "factory");
    }
  }

  @JsonMixin(target = CreatorTarget.class)
  public abstract static class ConstructorCreatorMixIn {
    @JsonCreator({"id", "name"})
    ConstructorCreatorMixIn(int id, String name) {}
  }

  @JsonMixin(target = CreatorTarget.class)
  public interface FactoryCreatorMixIn {
    @JsonCreator({"id", "name"})
    CreatorTarget create(int id, String name);
  }

  public static final class ValueTarget {
    @JsonIgnore public final String route;
    private final String value;

    public ValueTarget(String value) {
      this(value, "constructor");
    }

    private ValueTarget(String value, String route) {
      this.value = value;
      this.route = route;
    }

    public String text() {
      return value;
    }

    public static ValueTarget create(String value) {
      return new ValueTarget(value, "factory");
    }
  }

  @JsonMixin(target = ValueTarget.class)
  public interface ValueMixIn {
    @JsonValue
    String text();

    @JsonCreator
    ValueTarget create(String value);
  }

  public interface Shape {}

  public static final class Circle implements Shape {
    public int radius;

    public Circle() {}

    Circle(int radius) {
      this.radius = radius;
    }
  }

  @JsonMixin(target = Shape.class)
  @JsonSubTypes(
      property = "kind",
      value = {@JsonSubTypes.Type(value = Circle.class, name = "circle")})
  public interface ShapeMixIn {}

  public static final class WholeTarget {
    public String value;

    public WholeTarget() {}

    WholeTarget(String value) {
      this.value = value;
    }
  }

  @JsonMixin(target = WholeTarget.class)
  @JsonCodec(WholeTargetCodec.class)
  public interface WholeTargetMixIn {}

  public static final class WholeTargetCodec implements JsonValueCodec<WholeTarget> {
    @Override
    public void writeString(StringJsonWriter writer, WholeTarget value) {
      writer.writeString("whole:" + value.value);
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, WholeTarget value) {
      writer.writeString("whole:" + value.value);
    }

    @Override
    public WholeTarget readLatin1(Latin1JsonReader reader) {
      return read(reader.readString());
    }

    @Override
    public WholeTarget readUtf16(Utf16JsonReader reader) {
      return read(reader.readString());
    }

    @Override
    public WholeTarget readUtf8(Utf8JsonReader reader) {
      return read(reader.readString());
    }

    private WholeTarget read(String value) {
      return new WholeTarget(value.substring("whole:".length()));
    }
  }

  public static final class NameTarget {
    public String name;

    NameTarget(String name) {
      this.name = name;
    }
  }

  @JsonMixin(target = NameTarget.class)
  public abstract static class FirstNameMixIn {
    @JsonProperty("first")
    String name;
  }

  @JsonMixin(target = NameTarget.class)
  public abstract static class SecondNameMixIn {
    @JsonProperty("second")
    String name;
  }

  public static class ExactParent {
    public String name = "parent";
  }

  public static final class ExactChild extends ExactParent {
    public int rank = 2;
  }

  @JsonMixin(target = ExactParent.class)
  public abstract static class ExactParentMixIn {
    @JsonProperty("parent_name")
    String name;
  }

  @JsonMixin(target = ExactChild.class)
  public abstract static class ExactChildMixIn {
    @JsonProperty("child_name")
    String name;
  }

  public static final class ReplacementTarget {
    @JsonProperty(value = "direct", index = 3, include = JsonProperty.Include.ALWAYS)
    public String value;

    @JsonUnwrapped(prefix = "old_", suffix = "_old")
    public BasicChild child = new BasicChild("kid");
  }

  @JsonMixin(target = ReplacementTarget.class)
  public abstract static class ReplacementMixIn {
    @JsonProperty("mixed")
    String value;

    @JsonUnwrapped(prefix = "new_")
    BasicChild child;
  }

  public static final class RepresentationRemoveTarget {
    @JsonProperty("renamed")
    public String name = "name";

    @JsonRawValue public String raw = "1";
    @JsonBase64 public byte[] bytes = new byte[] {1};

    @JsonUnwrapped(prefix = "child_")
    public BasicChild child = new BasicChild("kid");

    @JsonIgnore public int hidden = 7;
  }

  @JsonMixin(target = RepresentationRemoveTarget.class)
  public abstract static class RepresentationRemoveMixIn {
    @JsonMixinRemove(JsonProperty.class)
    String name;

    @JsonMixinRemove(JsonRawValue.class)
    String raw;

    @JsonMixinRemove(JsonBase64.class)
    byte[] bytes;

    @JsonMixinRemove(JsonUnwrapped.class)
    BasicChild child;

    @JsonMixinRemove(JsonIgnore.class)
    int hidden;
  }

  public static final class AnyFieldRemoveTarget {
    @JsonAnyProperty public Map<String, Integer> extra = map("x", 1);
  }

  @JsonMixin(target = AnyFieldRemoveTarget.class)
  public abstract static class AnyFieldRemoveMixIn {
    @JsonMixinRemove(JsonAnyProperty.class)
    Map<String, Integer> extra;
  }

  public static final class AnyMethodRemoveTarget {
    final Map<String, Integer> extra = map("x", 1);

    @JsonAnyGetter
    public Map<String, Integer> getExtra() {
      return extra;
    }

    @JsonAnySetter
    public void put(String name, Integer value) {
      extra.put(name, value);
    }
  }

  @JsonMixin(target = AnyMethodRemoveTarget.class)
  public interface AnyMethodRemoveMixIn {
    @JsonMixinRemove(JsonAnyGetter.class)
    Map<String, Integer> getExtra();

    @JsonMixinRemove(JsonAnySetter.class)
    void put(String name, Integer value);
  }

  public static final class ValueRemoveTarget {
    @JsonValue public String text;

    public ValueRemoveTarget() {}

    @JsonCreator
    public ValueRemoveTarget(String text) {
      this.text = text;
    }
  }

  @JsonMixin(target = ValueRemoveTarget.class)
  public abstract static class ValueRemoveMixIn {
    @JsonMixinRemove(JsonValue.class)
    String text;

    @JsonMixinRemove(JsonCreator.class)
    ValueRemoveMixIn(String text) {}
  }

  public static final class CreatorRemoveTarget {
    public int id;
    @JsonIgnore public String route;

    public CreatorRemoveTarget() {
      route = "default";
    }

    @JsonCreator({"id"})
    public CreatorRemoveTarget(int id) {
      this.id = id;
      route = "creator";
    }
  }

  @JsonMixin(target = CreatorRemoveTarget.class)
  public abstract static class CreatorRemoveMixIn {
    @JsonMixinRemove(JsonCreator.class)
    CreatorRemoveMixIn(int id) {}
  }

  @JsonSubTypes(
      property = "kind",
      value = {@JsonSubTypes.Type(value = RemovedCircle.class, name = "circle")})
  public interface RemovedShape {}

  public static final class RemovedCircle implements RemovedShape {
    public int radius = 1;
  }

  @JsonMixin(target = RemovedShape.class)
  @JsonMixinRemove(JsonSubTypes.class)
  public interface ShapeRemoveMixIn {}

  @JsonCodec(InheritedCodec.class)
  public static class CodecBarrierParent {
    public String name = "parent";
  }

  public static final class CodecBarrierChild extends CodecBarrierParent {
    CodecBarrierChild() {
      name = "child";
    }
  }

  public static final class InheritedCodec implements JsonValueCodec<CodecBarrierParent> {
    @Override
    public void writeString(StringJsonWriter writer, CodecBarrierParent value) {
      writer.writeString("inherited");
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, CodecBarrierParent value) {
      writer.writeString("inherited");
    }

    @Override
    public CodecBarrierParent readLatin1(Latin1JsonReader reader) {
      reader.skipValue();
      return new CodecBarrierParent();
    }

    @Override
    public CodecBarrierParent readUtf16(Utf16JsonReader reader) {
      reader.skipValue();
      return new CodecBarrierParent();
    }

    @Override
    public CodecBarrierParent readUtf8(Utf8JsonReader reader) {
      reader.skipValue();
      return new CodecBarrierParent();
    }
  }

  @JsonMixin(target = CodecBarrierChild.class)
  @JsonMixinRemove(JsonCodec.class)
  public interface CodecBarrierMixIn {}

  @JsonPropertyOrder({"b", "a"})
  public static class OrderBarrierParent {
    public int a = 1;
    public int b = 2;
  }

  public static final class OrderBarrierChild extends OrderBarrierParent {}

  @JsonMixin(target = OrderBarrierChild.class)
  @JsonMixinRemove(JsonPropertyOrder.class)
  public interface OrderBarrierMixIn {}

  public static final class OverloadTarget {
    String value;

    public String getValue() {
      return value;
    }

    public void setValue(String value) {
      this.value = value;
    }

    public static void setValue(int value) {
      throw new AssertionError(value);
    }
  }

  @JsonMixin(target = OverloadTarget.class)
  public interface OverloadMixIn {
    @JsonProperty("text")
    void setValue(String value);
  }

  public static final class SelectorTarget {
    public String name = "target";
  }

  public static final class SemanticFailureTarget {
    public String body;
  }

  @JsonMixin(target = SemanticFailureTarget.class)
  public abstract static class SemanticFailureMixIn {
    @JsonRawValue
    @JsonCodec(JsonCodecAnnotationTest.AStringCodec.class)
    String body;
  }

  public static final class InvalidTypeCodecTarget {}

  @JsonMixin(target = InvalidTypeCodecTarget.class)
  @JsonCodec(elementCodec = JsonCodecAnnotationTest.AStringCodec.class)
  public interface InvalidTypeCodecMixIn {}

  public static final class InvalidSubTypesTarget {}

  @JsonMixin(target = InvalidSubTypesTarget.class)
  @JsonSubTypes({@JsonSubTypes.Type(value = InvalidSubTypesTarget.class, name = "value")})
  public interface InvalidSubTypesMixIn {}

  public static final class InvalidValueTarget {
    public String first() {
      return "first";
    }

    public String second() {
      return "second";
    }
  }

  @JsonMixin(target = InvalidValueTarget.class)
  public interface InvalidValueMixIn {
    @JsonValue
    String first();

    @JsonValue
    String second();
  }

  public static final class HostedSourceTarget {
    public int id;
    public int helper;
  }

  @JsonMixin(target = HostedSourceTarget.class)
  public abstract static class HostedSourceMixIn {
    @JsonProperty("value")
    int id;

    int helper;
  }

  public static final class MemberCtorTarget {
    public final int id;

    public MemberCtorTarget(int id) {
      this.id = id;
    }
  }

  @JsonMixin(target = MemberCtorTarget.class)
  public abstract class MemberCtorMixIn {
    @JsonCreator
    MemberCtorMixIn(@JsonProperty("id") int id) {}
  }

  public abstract static class SourceHierarchyParent {
    @JsonProperty("inherited")
    String name;
  }

  @JsonMixin(target = SelectorTarget.class)
  public abstract static class SourceHierarchyMixIn extends SourceHierarchyParent {
    String unmatchedHelper;
  }

  @JsonMixin(target = SelectorTarget.class)
  public abstract static class HelperMixIn {
    String unmatchedHelper;
  }

  @JsonMixin(target = BasicTarget.class)
  public static final class ConcreteMixIn {}

  @JsonMixin(target = int.class)
  public abstract static class PrimitiveTargetMixIn {}

  @JsonMixin(target = SelfMixIn.class)
  public abstract static class SelfMixIn {}

  @JsonMixin(target = BasicTarget.class)
  public abstract static class UnmatchedFieldMixIn {
    @JsonProperty("missing")
    long missing;
  }

  @JsonMixin(target = SelectorMethodTarget.class)
  public interface ReturnTypeMixIn {
    @JsonProperty("name")
    CharSequence getName();
  }

  public static final class SelectorMethodTarget {
    public String getName() {
      return "name";
    }
  }

  public enum IntrinsicMemberTarget {
    VALUE;

    String label = "value";
  }

  @JsonMixin(target = IntrinsicMemberTarget.class)
  public abstract static class IntrinsicMemberMixIn {
    @JsonProperty("name")
    String label;
  }

  public static final class CodecFamilyTarget {
    public String value;
  }

  @JsonMixin(target = CodecFamilyTarget.class)
  @JsonCodec(CodecFamilyCodec.class)
  public abstract static class CodecFamilyMixIn {
    @JsonProperty("ignored")
    String value;
  }

  public static final class CodecFamilyCodec implements JsonValueCodec<CodecFamilyTarget> {
    @Override
    public void writeString(StringJsonWriter writer, CodecFamilyTarget value) {
      writer.writeString(value.value);
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, CodecFamilyTarget value) {
      writer.writeString(value.value);
    }

    @Override
    public CodecFamilyTarget readLatin1(Latin1JsonReader reader) {
      return read(reader.readString());
    }

    @Override
    public CodecFamilyTarget readUtf16(Utf16JsonReader reader) {
      return read(reader.readString());
    }

    @Override
    public CodecFamilyTarget readUtf8(Utf8JsonReader reader) {
      return read(reader.readString());
    }

    private CodecFamilyTarget read(String text) {
      CodecFamilyTarget value = new CodecFamilyTarget();
      value.value = text;
      return value;
    }
  }

  public static final class ValueFamilyTarget {
    public String text;
    public int ignored;

    public static ValueFamilyTarget create(String text) {
      ValueFamilyTarget value = new ValueFamilyTarget();
      value.text = text;
      return value;
    }
  }

  @JsonMixin(target = ValueFamilyTarget.class)
  public abstract static class ValueFamilyMixIn {
    @JsonValue String text;

    @JsonProperty("ignored")
    int ignored;

    @JsonCreator
    abstract ValueFamilyTarget create(String text);
  }

  public static final class ObjectParameterTarget {
    public String text;

    public ObjectParameterTarget(String text) {
      this.text = text;
    }
  }

  @JsonMixin(target = ObjectParameterTarget.class)
  public abstract static class ObjectParameterMixIn {
    ObjectParameterMixIn(@JsonProperty("text") String text) {}
  }

  public abstract static class SubtypeFamilyTarget {
    public int ignored;
  }

  public static final class SubtypeFamilyChild extends SubtypeFamilyTarget {}

  @JsonMixin(target = SubtypeFamilyTarget.class)
  @JsonSubTypes(
      value = @JsonSubTypes.Type(name = "child", value = SubtypeFamilyChild.class),
      property = "kind")
  public abstract static class SubtypeFamilyMixIn {
    @JsonProperty("ignored")
    int ignored;
  }

  @JsonMixinRemove(JsonPropertyOrder.class)
  public static final class DirectTypeRemove {}

  public static final class DirectFieldRemove {
    @JsonMixinRemove(JsonIgnore.class)
    public int id;
  }

  public static final class DirectMethodRemove {
    @JsonMixinRemove(JsonProperty.class)
    public int getId() {
      return 0;
    }
  }

  public static final class DirectConstructorRemove {
    @JsonMixinRemove(JsonCreator.class)
    public DirectConstructorRemove() {}
  }

  public static final class DirectParameterRemove {
    @JsonCreator
    public DirectParameterRemove(@JsonProperty("id") @JsonMixinRemove(JsonProperty.class) int id) {}
  }

  public static class HiddenFieldParent {
    public String name;
  }

  public static final class HiddenFieldChild extends HiddenFieldParent {
    public String name;
  }

  @JsonMixin(target = HiddenFieldChild.class)
  public abstract static class HiddenFieldMixIn {
    @JsonProperty("name")
    String name;
  }

  @JsonMixin(target = BasicTarget.class)
  public abstract static class StaticFieldMixIn {
    @JsonProperty("name")
    static final String name = null;
  }

  @JsonMixin(target = SelectorMethodTarget.class)
  public abstract static class ConcreteMethodMixIn {
    @JsonProperty("name")
    public String getName() {
      return "source";
    }
  }

  @JsonMixin(target = BasicTarget.class)
  @JsonType
  public abstract static class JsonTypeMixIn {}

  @JsonMixin(target = BasicTarget.class)
  @JsonMixinRemove(JsonType.class)
  public abstract static class RemoveJsonTypeMixIn {}

  @JsonMixin(target = BasicTarget.class)
  @JsonMixinRemove({})
  public abstract static class EmptyRemoveMixIn {}

  @JsonMixin(target = BasicTarget.class)
  @JsonMixinRemove({JsonProperty.class, JsonProperty.class})
  public abstract static class DuplicateRemoveMixIn {}

  @JsonMixin(target = BasicTarget.class)
  @JsonMixinRemove(Deprecated.class)
  public abstract static class UnsupportedRemoveMixIn {}

  @JsonMixin(target = BasicTarget.class)
  public abstract static class IllegalRemoveMixIn {
    @JsonMixinRemove(JsonPropertyOrder.class)
    String name;
  }

  @JsonMixin(target = BasicTarget.class)
  public abstract static class AddRemoveMixIn {
    @JsonProperty("name")
    @JsonMixinRemove(JsonProperty.class)
    String name;
  }
}

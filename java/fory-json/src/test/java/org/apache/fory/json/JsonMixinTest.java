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
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
    ForyJson json = newJsonBuilder().registerMixin(BasicMixin.class).build();
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
    ForyJson fieldJson = newJsonBuilder().registerMixin(AnyFieldMixin.class).build();
    AnyFieldTarget field = new AnyFieldTarget();
    field.extra.put("dynamic", "one");
    assertEquals(fieldJson.toJson(field), "{\"dynamic\":\"A:one\"}");
    assertEquals(
        fieldJson.fromJson("{\"dynamic\":\"A:two\"}", AnyFieldTarget.class).extra.get("dynamic"),
        "two");

    ForyJson methodJson = newJsonBuilder().registerMixin(AnyMethodMixin.class).build();
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
        newJsonBuilder().registerMixin(ConstructorCreatorMixin.class).build();
    CreatorTarget constructor =
        constructorJson.fromJson("{\"id\":1,\"name\":\"one\"}", CreatorTarget.class);
    assertEquals(constructor.route, "constructor");
    assertEquals(constructorJson.toJson(constructor), "{\"id\":1,\"name\":\"one\"}");

    ForyJson factoryJson = newJsonBuilder().registerMixin(FactoryCreatorMixin.class).build();
    CreatorTarget factory =
        factoryJson.fromJson("{\"id\":2,\"name\":\"two\"}", CreatorTarget.class);
    assertEquals(factory.route, "factory");

    ForyJson valueJson = newJsonBuilder().registerMixin(ValueMixin.class).build();
    assertEquals(valueJson.toJson(new ValueTarget("write")), "\"write\"");
    ValueTarget decoded = valueJson.fromJson("\"read\"", ValueTarget.class);
    assertEquals(decoded.text(), "read");
    assertEquals(decoded.route, "factory");
  }

  @Test
  public void typeAnnotations() {
    ForyJson subtypeJson = newJsonBuilder().registerMixin(ShapeMixin.class).build();
    Shape value = new Circle(3);
    assertEquals(subtypeJson.toJson(value, Shape.class), "{\"kind\":\"circle\",\"radius\":3}");
    Shape decoded = subtypeJson.fromJson("{\"kind\":\"circle\",\"radius\":4}", Shape.class);
    assertTrue(decoded instanceof Circle);
    assertEquals(((Circle) decoded).radius, 4);

    ForyJson codecJson = newJsonBuilder().registerMixin(WholeTargetMixin.class).build();
    assertEquals(codecJson.toJson(new WholeTarget("write")), "\"whole:write\"");
    assertEquals(codecJson.fromJson("\"whole:read\"", WholeTarget.class).value, "read");

    UUID uuid = UUID.fromString("0d57bb7d-e50e-4a4f-9d59-d159c48086ae");
    ForyJson uuidJson = newJsonBuilder().registerMixin(UuidMixin.class).build();
    assertEquals(uuidJson.toJson(uuid), "\"uuid:" + uuid + "\"");
    assertEquals(uuidJson.fromJson("\"uuid:" + uuid + "\"", UUID.class), uuid);
  }

  @Test
  public void registrationLifecycle() {
    ForyJson direct = newJsonBuilder().build();
    ForyJsonBuilder builder = newJsonBuilder().registerMixin(FirstNameMixin.class);
    ForyJson first = builder.build();
    builder.registerMixin(SecondNameMixin.class);
    ForyJson second = builder.build();

    assertEquals(direct.toJson(new NameTarget("value")), "{\"name\":\"value\"}");
    assertEquals(first.toJson(new NameTarget("value")), "{\"first\":\"value\"}");
    assertEquals(second.toJson(new NameTarget("value")), "{\"second\":\"value\"}");
    assertEquals(first.toJson(new NameTarget("again")), "{\"first\":\"again\"}");

    ForyJson reset =
        newJsonBuilder()
            .registerMixin(FirstNameMixin.class)
            .registerMixin(EmptyNameMixin.class)
            .build();
    assertEquals(reset.toJson(new NameTarget("value")), "{\"name\":\"value\"}");

    ForyJson replacedInvalid =
        newJsonBuilder().registerMixin(ConcreteMixin.class).registerMixin(BasicMixin.class).build();
    assertTrue(replacedInvalid.toJson(basic("valid")).contains("\"display_name\":\"valid\""));

    ForyJson repeated =
        newJsonBuilder()
            .registerMixin(FirstNameMixin.class)
            .registerMixin(FirstNameMixin.class)
            .build();
    ForyJson equivalent = newJsonBuilder().registerMixin(FirstNameMixin.class).build();
    assertEquals(JsonTestSupport.config(repeated), JsonTestSupport.config(equivalent));
    assertEquals(
        JsonTestSupport.config(repeated).getCodegenHash(),
        JsonTestSupport.config(equivalent).getCodegenHash());
    assertNotEquals(JsonTestSupport.config(first), JsonTestSupport.config(second));
    assertNotEquals(
        JsonTestSupport.config(first).getCodegenHash(),
        JsonTestSupport.config(second).getCodegenHash());
    assertGeneratedWhenSupported(first, NameTarget.class);
    assertGeneratedWhenSupported(second, NameTarget.class);
  }

  @Test
  public void exactTargets() {
    ForyJson parentJson = newJsonBuilder().registerMixin(ExactParentMixin.class).build();
    assertEquals(parentJson.toJson(new ExactParent()), "{\"parent_name\":\"parent\"}");
    assertEquals(parentJson.toJson(new ExactChild()), "{\"name\":\"parent\",\"rank\":2}");

    ForyJson childJson = newJsonBuilder().registerMixin(ExactChildMixin.class).build();
    assertEquals(childJson.toJson(new ExactParent()), "{\"name\":\"parent\"}");
    assertEquals(childJson.toJson(new ExactChild()), "{\"child_name\":\"parent\",\"rank\":2}");

    ForyJson nestedJson =
        newJsonBuilder()
            .registerMixin(BasicMixin.class)
            .registerMixin(BasicChildMixin.class)
            .build();
    assertEquals(
        nestedJson.toJson(basic("nested")),
        "{\"display_name\":\"nested\",\"body\":{\"ok\":true},\"bytes\":\"AQID\","
            + "\"child_caption\":\"kid\",\"ignored\":7}");
  }

  @Test
  public void replacementAndRemoval() {
    ForyJson replacement = newJsonBuilder().registerMixin(ReplacementMixin.class).build();
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
        newJsonBuilder().registerMixin(RepresentationRemoveMixin.class).build();
    assertEquals(
        representation.toJson(new RepresentationRemoveTarget()),
        "{\"name\":\"name\",\"raw\":\"1\",\"bytes\":[1],"
            + "\"child\":{\"label\":\"kid\"},\"hidden\":7}");

    ForyJson anyField = newJsonBuilder().registerMixin(AnyFieldRemoveMixin.class).build();
    assertEquals(anyField.toJson(new AnyFieldRemoveTarget()), "{\"extra\":{\"x\":1}}");

    ForyJson anyMethods = newJsonBuilder().registerMixin(AnyMethodRemoveMixin.class).build();
    assertEquals(anyMethods.toJson(new AnyMethodRemoveTarget()), "{\"extra\":{\"x\":1}}");
    AnyMethodRemoveTarget anyDecoded =
        anyMethods.fromJson("{\"unknown\":2}", AnyMethodRemoveTarget.class);
    assertEquals(anyDecoded.extra, map("x", 1));

    ForyJson value = newJsonBuilder().registerMixin(ValueRemoveMixin.class).build();
    assertEquals(value.toJson(new ValueRemoveTarget("write")), "{\"text\":\"write\"}");
    assertEquals(value.fromJson("{\"text\":\"read\"}", ValueRemoveTarget.class).text, "read");

    ForyJson creator = newJsonBuilder().registerMixin(CreatorRemoveMixin.class).build();
    CreatorRemoveTarget creatorValue = creator.fromJson("{\"id\":5}", CreatorRemoveTarget.class);
    assertEquals(creatorValue.id, 5);
    assertEquals(creatorValue.route, "default");

    ForyJson subtype = newJsonBuilder().registerMixin(ShapeRemoveMixin.class).build();
    assertThrows(
        ForyJsonException.class, () -> subtype.toJson(new RemovedCircle(), RemovedShape.class));

    ForyJson codec = newJsonBuilder().registerMixin(CodecBarrierMixin.class).build();
    assertEquals(newJson().toJson(new CodecBarrierChild()), "\"inherited\"");
    assertEquals(codec.toJson(new CodecBarrierChild()), "{\"name\":\"child\"}");

    ForyJson order = newJsonBuilder().registerMixin(OrderBarrierMixin.class).build();
    assertEquals(newJson().toJson(new OrderBarrierChild()), "{\"b\":2,\"a\":1}");
    assertEquals(order.toJson(new OrderBarrierChild()), "{\"a\":1,\"b\":2}");
  }

  @Test
  public void matching() {
    ForyJson overload = newJsonBuilder().registerMixin(OverloadMixin.class).build();
    OverloadTarget value = overload.fromJson("{\"text\":\"value\"}", OverloadTarget.class);
    assertEquals(value.value, "value");
    assertEquals(overload.toJson(value), "{\"text\":\"value\"}");

    ForyJson helper = newJsonBuilder().registerMixin(HelperMixin.class).build();
    assertEquals(helper.toJson(new SelectorTarget()), "{\"name\":\"target\"}");

    assertThrows(NullPointerException.class, () -> newJsonBuilder().registerMixin(null));
    assertRegistrationInvalid(BasicTarget.class);
    assertStructureInvalid(ConcreteMixin.class);
    assertStructureInvalid(SourceHierarchyMixin.class);
    assertStructureInvalid(PrimitiveTargetMixin.class);
    assertStructureInvalid(SelfMixin.class);
    @JsonMixin(target = BasicTarget.class)
    abstract class LocalMixin {}
    assertStructureInvalid(LocalMixin.class);
    assertStructureInvalid(UnmatchedFieldMixin.class);
    assertStructureInvalid(ReturnTypeMixin.class);
    assertStructureInvalid(HiddenFieldMixin.class);
    assertStructureInvalid(StaticFieldMixin.class);
    assertStructureInvalid(ConcreteMethodMixin.class);
    assertStructureInvalid(JsonTypeMixin.class);
    assertStructureInvalid(RemoveJsonTypeMixin.class);
    assertStructureInvalid(EmptyRemoveMixin.class);
    assertStructureInvalid(DuplicateRemoveMixin.class);
    assertStructureInvalid(UnsupportedRemoveMixin.class);
    assertStructureInvalid(IllegalRemoveMixin.class);
    assertStructureInvalid(AddRemoveMixin.class);
  }

  @Test
  public void semanticDiagnostics() {
    assertPairFailure(InvalidTypeCodecMixin.class, InvalidTypeCodecTarget.class);
    assertPairFailure(InvalidSubTypesMixin.class, InvalidSubTypesTarget.class);
    assertPairFailure(InvalidValueMixin.class, InvalidValueTarget.class);
    assertPairFailure(ObjectMethodMixin.class, ObjectMethodTarget.class);

    ForyJson intrinsic = newJsonBuilder().registerMixin(IntrinsicMemberMixin.class).build();
    assertEquals(intrinsic.toJson(IntrinsicMemberTarget.VALUE), "\"VALUE\"");

    ForyJson registered =
        newJsonBuilder()
            .registerCodec(WholeTarget.class, new WholeTargetCodec("registered:"))
            .registerMixin(WholeTargetMixin.class)
            .build();
    assertEquals(registered.toJson(new WholeTarget("value")), "\"registered:value\"");
    assertEquals(registered.fromJson("\"registered:read\"", WholeTarget.class).value, "read");
  }

  @Test
  public void directRemoveRejected() {
    for (Class<?> type :
        new Class<?>[] {
          DirectTypeRemove.class,
          DirectFieldRemove.class,
          DirectMethodRemove.class,
          DirectConstructorRemove.class,
          DirectParameterRemove.class,
          DirectEnumRemove.class
        }) {
      ForyJsonException failure =
          expectThrows(ForyJsonException.class, () -> newJson().fromJson("{}", type));
      assertTrue(failure.getMessage().contains("@JsonMixinRemove"), failure.getMessage());
    }
  }

  @Test
  public void directParameterAnnotation() {
    DirectParameterTarget value = new DirectParameterTarget();
    value.id = 3;
    assertEquals(newJson().toJson(value), "{\"id\":3}");
  }

  private void assertPairFailure(Class<?> mixinType, Class<?> targetType) {
    ForyJson json = newJsonBuilder().registerMixin(mixinType).build();
    ForyJsonException failure =
        expectThrows(ForyJsonException.class, () -> json.fromJson("{}", targetType));
    String message = failure.getMessage();
    assertTrue(message.contains(targetType.getName()), message);
    assertTrue(message.contains(mixinType.getName()), message);
  }

  @Test
  public void recordSelectors() throws Exception {
    if (JdkVersion.MAJOR_VERSION < 17) {
      throw new SkipException("Java record test requires JDK 17+");
    }
    String simpleName =
        codegenEnabled() ? "JsonMixinGeneratedRecord" : "JsonMixinInterpretedRecord";
    String mixinName = simpleName + "Mixin";
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
                + mixinName
                + " {\n"
                + "  @JsonProperty(\"display_name\") String name;\n"
                + "  @JsonBase64 byte[] bytes;\n"
                + "  "
                + mixinName
                + "(\n"
                + "      @JsonProperty(\"display_name\") String name, byte[] bytes) {}\n"
                + "}\n");
    Class<?> mixin =
        Class.forName("org.apache.fory.json.records." + mixinName, true, type.getClassLoader());
    Object record =
        type.getConstructor(String.class, byte[].class).newInstance("record", new byte[] {1, 2, 3});
    ForyJson json = newJsonBuilder().registerMixin(mixin).build();
    assertEquals(json.toJson(record), "{\"display_name\":\"record\",\"bytes\":\"AQID\"}");
    Object decoded = json.fromJson("{\"display_name\":\"read\",\"bytes\":\"AQID\"}", type);
    assertEquals(type.getMethod("name").invoke(decoded), "read");
    assertEquals(type.getMethod("bytes").invoke(decoded), new byte[] {1, 2, 3});
    assertGeneratedWhenSupported(json, type);
  }

  private void assertRegistrationInvalid(Class<?> mixin) {
    assertThrows(IllegalArgumentException.class, () -> newJsonBuilder().registerMixin(mixin));
  }

  private void assertStructureInvalid(Class<?> mixin) {
    assertThrows(ForyJsonException.class, () -> newJsonBuilder().registerMixin(mixin).build());
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
  public abstract static class BasicMixin {
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
  public abstract static class BasicChildMixin {
    @JsonProperty("caption")
    String label;
  }

  public static final class AnyFieldTarget {
    public Map<String, String> extra = new LinkedHashMap<>();
  }

  @JsonMixin(target = AnyFieldTarget.class)
  public abstract static class AnyFieldMixin {
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
  public interface AnyMethodMixin {
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
  public abstract static class ConstructorCreatorMixin {
    @JsonCreator({"id", "name"})
    ConstructorCreatorMixin(int id, String name) {}
  }

  @JsonMixin(target = CreatorTarget.class)
  public interface FactoryCreatorMixin {
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
  public interface ValueMixin {
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
  public interface ShapeMixin {}

  public static final class WholeTarget {
    public String value;

    public WholeTarget() {}

    WholeTarget(String value) {
      this.value = value;
    }
  }

  @JsonMixin(target = WholeTarget.class)
  @JsonCodec(WholeTargetCodec.class)
  public interface WholeTargetMixin {}

  public static final class WholeTargetCodec implements JsonValueCodec<WholeTarget> {
    private final String prefix;

    public WholeTargetCodec() {
      this("whole:");
    }

    WholeTargetCodec(String prefix) {
      this.prefix = prefix;
    }

    @Override
    public void writeString(StringJsonWriter writer, WholeTarget value) {
      writer.writeString(prefix + value.value);
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, WholeTarget value) {
      writer.writeString(prefix + value.value);
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
      return new WholeTarget(value.substring(prefix.length()));
    }
  }

  @JsonMixin(target = UUID.class)
  @JsonCodec(UuidCodec.class)
  public interface UuidMixin {}

  public static final class UuidCodec implements JsonValueCodec<UUID> {
    @Override
    public void writeString(StringJsonWriter writer, UUID value) {
      writer.writeString("uuid:" + value);
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, UUID value) {
      writer.writeString("uuid:" + value);
    }

    @Override
    public UUID readLatin1(Latin1JsonReader reader) {
      return read(reader.readString());
    }

    @Override
    public UUID readUtf16(Utf16JsonReader reader) {
      return read(reader.readString());
    }

    @Override
    public UUID readUtf8(Utf8JsonReader reader) {
      return read(reader.readString());
    }

    private UUID read(String value) {
      return UUID.fromString(value.substring("uuid:".length()));
    }
  }

  public static final class NameTarget {
    public String name;

    NameTarget(String name) {
      this.name = name;
    }
  }

  @JsonMixin(target = NameTarget.class)
  public abstract static class FirstNameMixin {
    @JsonProperty("first")
    String name;
  }

  @JsonMixin(target = NameTarget.class)
  public abstract static class SecondNameMixin {
    @JsonProperty("second")
    String name;
  }

  @JsonMixin(target = NameTarget.class)
  public interface EmptyNameMixin {}

  public static class ExactParent {
    public String name = "parent";
  }

  public static final class ExactChild extends ExactParent {
    public int rank = 2;
  }

  @JsonMixin(target = ExactParent.class)
  public abstract static class ExactParentMixin {
    @JsonProperty("parent_name")
    String name;
  }

  @JsonMixin(target = ExactChild.class)
  public abstract static class ExactChildMixin {
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
  public abstract static class ReplacementMixin {
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
  public abstract static class RepresentationRemoveMixin {
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
  public abstract static class AnyFieldRemoveMixin {
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
  public interface AnyMethodRemoveMixin {
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
  public abstract static class ValueRemoveMixin {
    @JsonMixinRemove(JsonValue.class)
    String text;

    @JsonMixinRemove(JsonCreator.class)
    ValueRemoveMixin(String text) {}
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
  public abstract static class CreatorRemoveMixin {
    @JsonMixinRemove(JsonCreator.class)
    CreatorRemoveMixin(int id) {}
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
  public interface ShapeRemoveMixin {}

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
  public interface CodecBarrierMixin {}

  @JsonPropertyOrder({"b", "a"})
  public static class OrderBarrierParent {
    public int a = 1;
    public int b = 2;
  }

  public static final class OrderBarrierChild extends OrderBarrierParent {}

  @JsonMixin(target = OrderBarrierChild.class)
  @JsonMixinRemove(JsonPropertyOrder.class)
  public interface OrderBarrierMixin {}

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
  public interface OverloadMixin {
    @JsonProperty("text")
    void setValue(String value);
  }

  public static final class SelectorTarget {
    public String name = "target";
  }

  public static final class InvalidTypeCodecTarget {}

  @JsonMixin(target = InvalidTypeCodecTarget.class)
  @JsonCodec(elementCodec = JsonCodecAnnotationTest.AStringCodec.class)
  public interface InvalidTypeCodecMixin {}

  public static final class InvalidSubTypesTarget {}

  @JsonMixin(target = InvalidSubTypesTarget.class)
  @JsonSubTypes({@JsonSubTypes.Type(value = InvalidSubTypesTarget.class, name = "value")})
  public interface InvalidSubTypesMixin {}

  public static final class InvalidValueTarget {
    public String first() {
      return "first";
    }

    public String second() {
      return "second";
    }
  }

  @JsonMixin(target = InvalidValueTarget.class)
  public interface InvalidValueMixin {
    @JsonValue
    String first();

    @JsonValue
    String second();
  }

  public abstract static class SourceHierarchyParent {
    @JsonProperty("inherited")
    String name;
  }

  @JsonMixin(target = SelectorTarget.class)
  public abstract static class SourceHierarchyMixin extends SourceHierarchyParent {
    String unmatchedHelper;
  }

  @JsonMixin(target = SelectorTarget.class)
  public abstract static class HelperMixin {
    String unmatchedHelper;
  }

  @JsonMixin(target = BasicTarget.class)
  public static final class ConcreteMixin {}

  @JsonMixin(target = int.class)
  public abstract static class PrimitiveTargetMixin {}

  @JsonMixin(target = SelfMixin.class)
  public abstract static class SelfMixin {}

  @JsonMixin(target = BasicTarget.class)
  public abstract static class UnmatchedFieldMixin {
    @JsonProperty("missing")
    long missing;
  }

  @JsonMixin(target = SelectorMethodTarget.class)
  public interface ReturnTypeMixin {
    @JsonProperty("name")
    CharSequence getName();
  }

  public static final class SelectorMethodTarget {
    public String getName() {
      return "name";
    }
  }

  public static final class ObjectMethodTarget {
    public int id;
  }

  @JsonMixin(target = ObjectMethodTarget.class)
  public abstract static class ObjectMethodMixin {
    @JsonCodec(JsonCodecAnnotationTest.AStringCodec.class)
    public abstract String toString();
  }

  public enum IntrinsicMemberTarget {
    VALUE;

    String label = "value";
  }

  @JsonMixin(target = IntrinsicMemberTarget.class)
  public abstract static class IntrinsicMemberMixin {
    @JsonProperty("name")
    String label;
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

  public static final class DirectParameterTarget {
    public int id;

    public void ignored(@JsonProperty("ignored") String value) {}
  }

  @JsonMixinRemove(JsonPropertyOrder.class)
  public enum DirectEnumRemove {
    VALUE
  }

  public static class HiddenFieldParent {
    public String name;
  }

  public static final class HiddenFieldChild extends HiddenFieldParent {
    public String name;
  }

  @JsonMixin(target = HiddenFieldChild.class)
  public abstract static class HiddenFieldMixin {
    @JsonProperty("name")
    String name;
  }

  @JsonMixin(target = BasicTarget.class)
  public abstract static class StaticFieldMixin {
    @JsonProperty("name")
    static final String name = null;
  }

  @JsonMixin(target = SelectorMethodTarget.class)
  public abstract static class ConcreteMethodMixin {
    @JsonProperty("name")
    public String getName() {
      return "source";
    }
  }

  @JsonMixin(target = BasicTarget.class)
  @JsonType
  public abstract static class JsonTypeMixin {}

  @JsonMixin(target = BasicTarget.class)
  @JsonMixinRemove(JsonType.class)
  public abstract static class RemoveJsonTypeMixin {}

  @JsonMixin(target = BasicTarget.class)
  @JsonMixinRemove({})
  public abstract static class EmptyRemoveMixin {}

  @JsonMixin(target = BasicTarget.class)
  @JsonMixinRemove({JsonProperty.class, JsonProperty.class})
  public abstract static class DuplicateRemoveMixin {}

  @JsonMixin(target = BasicTarget.class)
  @JsonMixinRemove(Deprecated.class)
  public abstract static class UnsupportedRemoveMixin {}

  @JsonMixin(target = BasicTarget.class)
  public abstract static class IllegalRemoveMixin {
    @JsonMixinRemove(JsonPropertyOrder.class)
    String name;
  }

  @JsonMixin(target = BasicTarget.class)
  public abstract static class AddRemoveMixin {
    @JsonProperty("name")
    @JsonMixinRemove(JsonProperty.class)
    String name;
  }
}

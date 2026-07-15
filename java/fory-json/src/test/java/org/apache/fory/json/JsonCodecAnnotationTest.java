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
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.apache.fory.json.annotation.JsonAnyGetter;
import org.apache.fory.json.annotation.JsonAnyProperty;
import org.apache.fory.json.annotation.JsonAnySetter;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonIgnore;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.codec.MapKeyCodec;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.writer.JsonWriter;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.platform.JdkVersion;
import org.testng.SkipException;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class JsonCodecAnnotationTest extends ForyJsonTestModels {
  @Factory(dataProvider = "enableCodegen")
  public JsonCodecAnnotationTest(boolean codegen) {
    super(codegen);
  }

  @Test
  public void representations() {
    ForyJson json = newJson();
    RepresentationModel value = new RepresentationModel();
    value.value = "x";
    String expected = "{\"value\":\"P:x\"}";
    assertEquals(json.toJson(value), expected);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), expected);
    assertEquals(
        json.fromJson(expected, RepresentationModel.class).value,
        JsonTestSupport.stringReaderPath(expected) + ":x");
    assertEquals(
        json.fromJson("{\"value\":\"P:你好\"}", RepresentationModel.class).value, "utf16:你好");
    assertEquals(
        json.fromJson(expected.getBytes(StandardCharsets.UTF_8), RepresentationModel.class).value,
        "utf8:x");
  }

  @Test
  public void propertyDeclarations() {
    ForyJson json = newJson();
    MergedProperty value = new MergedProperty();
    value.setValue("x");
    assertEquals(json.toJson(value), "{\"value\":\"A:x\"}");
    assertEquals(json.fromJson("{\"value\":\"A:y\"}", MergedProperty.class).getValue(), "y");

    GetterProperty getter = new GetterProperty();
    getter.setValue("getter");
    assertEquals(json.toJson(getter), "{\"value\":\"A:getter\"}");
    assertEquals(json.fromJson("{\"value\":\"A:read\"}", GetterProperty.class).getValue(), "read");

    SetterProperty setter = new SetterProperty();
    setter.setValue("setter");
    assertEquals(json.toJson(setter), "{\"value\":\"A:setter\"}");
    assertEquals(json.fromJson("{\"value\":\"A:read\"}", SetterProperty.class).getValue(), "read");
  }

  @Test
  public void propertyConflict() {
    assertFailure(() -> newJson().toJson(new ConflictingProperty()));
    assertFailure(() -> newJson().toJson(new PartialMapProperty()));
  }

  @Test
  public void creators() {
    ForyJson json = newJson();
    PropertyListCreator listed = json.fromJson("{\"value\":\"A:list\"}", PropertyListCreator.class);
    assertEquals(listed.value, "list");
    assertEquals(json.toJson(listed), "{\"value\":\"A:list\"}");

    ParameterLocalCreator local =
        json.fromJson("{\"value\":\"A:local\"}", ParameterLocalCreator.class);
    assertEquals(local.getValue(), "local");
    assertEquals(json.toJson(local), "{\"value\":\"A:local\"}");

    CreatorOnly only = json.fromJson("{\"input\":\"A:only\"}", CreatorOnly.class);
    assertEquals(only.decoded, "only");
    assertEquals(json.toJson(only), "{}");
  }

  @Test
  public void recordComponent() throws Exception {
    if (JdkVersion.MAJOR_VERSION < 17) {
      throw new SkipException("Java record test requires JDK 17+");
    }
    Class<?> type =
        compileRecordClass(
            "JsonCodecDeclarationRecord",
            "package org.apache.fory.json.records;\n"
                + "import org.apache.fory.json.annotation.JsonCodec;\n"
                + "import org.apache.fory.json.JsonCodecAnnotationTest.AStringCodec;\n"
                + "public record JsonCodecDeclarationRecord("
                + "@JsonCodec(AStringCodec.class) String value) {}\n");
    Object value = type.getConstructor(String.class).newInstance("x");
    assertEquals(jsonText(newJson(), value), "{\"value\":\"A:x\"}");
    Object decoded = newJson().fromJson("{\"value\":\"A:y\"}", type);
    assertEquals(type.getMethod("value").invoke(decoded), "y");

    Class<?> accessorType =
        compileRecordClass(
            "JsonCodecAccessorRecord",
            "package org.apache.fory.json.records;\n"
                + "import org.apache.fory.json.annotation.JsonCodec;\n"
                + "import org.apache.fory.json.JsonCodecAnnotationTest.AStringCodec;\n"
                + "public record JsonCodecAccessorRecord(String value) {\n"
                + "  @JsonCodec(AStringCodec.class) public String value() { return value; }\n"
                + "}\n");
    Object accessorValue = accessorType.getConstructor(String.class).newInstance("x");
    assertEquals(jsonText(newJson(), accessorValue), "{\"value\":\"A:x\"}");
    Object accessorDecoded = newJson().fromJson("{\"value\":\"A:y\"}", accessorType);
    assertEquals(accessorType.getMethod("value").invoke(accessorDecoded), "y");
  }

  @Test
  public void anyValues() {
    ForyJson json = newJson();
    FieldAny field = new FieldAny();
    field.values.put("x", "one");
    assertEquals(json.toJson(field), "{\"x\":\"A:one\"}");
    assertEquals(json.fromJson("{\"x\":\"A:two\"}", FieldAny.class).values.get("x"), "two");

    MethodAny method = new MethodAny();
    method.values.put("x", "three");
    assertEquals(json.toJson(method), "{\"x\":\"A:three\"}");
    assertEquals(json.fromJson("{\"x\":\"A:four\"}", MethodAny.class).values.get("x"), "four");

    ParameterAny parameter = new ParameterAny();
    parameter.values.put("x", "five");
    assertEquals(json.toJson(parameter), "{\"x\":\"A:five\"}");
    assertEquals(json.fromJson("{\"x\":\"A:six\"}", ParameterAny.class).values.get("x"), "six");
  }

  @Test
  public void anyRejections() {
    assertFailure(() -> newJson().toJson(new OuterAny()));
    assertFailure(() -> newJson().toJson(new MethodOuterAny()));
    assertFailure(() -> newJson().toJson(new KeyAny()));
    assertFailure(() -> newJson().toJson(new ConflictingAny()));
    assertFailure(
        () -> newJson().toJson(new StaticCodecField()),
        "@JsonCodec is not supported on JSON field");
  }

  @Test
  public void interfaceMethods() {
    assertEquals(newJson().toJson(new InterfaceGetter()), "{\"value\":\"A:x\"}");
    assertEquals(newJson().toJson(new OverriddenGetter()), "{\"value\":\"x\"}");
    assertFailure(
        () -> newJson().toJson(new UnrelatedMethod()),
        "@JsonCodec requires an effective ordinary JSON getter");
  }

  @Test
  public void scalarNodes() {
    ForyJson json = newJson();
    ScalarModel value = new ScalarModel();
    value.number = 7;
    value.text = "text";
    value.kind = ScalarKind.ONE;
    value.object = new Value("object");
    String text = json.toJson(value);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), text);
    assertTrue(text.contains("\"number\":1007"));
    assertTrue(text.contains("\"text\":\"A:text\""));
    assertTrue(text.contains("\"kind\":\"E:ONE\""));
    assertTrue(text.contains("\"object\":\"V:object\""));

    ScalarModel decoded =
        json.fromJson(
            "{\"number\":1008,\"text\":\"A:value\",\"kind\":\"E:TWO\","
                + "\"object\":\"V:nested\"}",
            ScalarModel.class);
    assertEquals(decoded.number, 8);
    assertEquals(decoded.text, "value");
    assertEquals(decoded.kind, ScalarKind.TWO);
    assertEquals(decoded.object.text, "nested");
  }

  @Test
  public void arrayNodes() {
    ForyJson json = newJson();
    WholeArray whole = new WholeArray();
    whole.value = new String[] {"ignored"};
    assertEquals(json.toJson(whole), "{\"value\":\"whole-array\"}");
    assertEquals(
        json.fromJson("{\"value\":\"whole-array\"}", WholeArray.class).value,
        new String[] {"whole-array"});

    ComponentArray components = new ComponentArray();
    components.value = new String[] {"x", "y"};
    assertEquals(json.toJson(components), "{\"value\":[\"A:x\",\"A:y\"]}");
    assertEquals(
        json.fromJson("{\"value\":[\"A:a\",\"A:b\"]}", ComponentArray.class).value,
        new String[] {"a", "b"});

    PrimitiveArray primitive = new PrimitiveArray();
    primitive.value = new int[] {1, 2};
    assertEquals(json.toJson(primitive), "{\"value\":[1001,1002]}");
    assertEquals(
        json.fromJson("{\"value\":[1003,1004]}", PrimitiveArray.class).value, new int[] {3, 4});

    MultiArray multi = new MultiArray();
    multi.value = new String[][] {{"x"}};
    assertEquals(json.toJson(multi), "{\"value\":[\"whole-array\"]}");
    assertEquals(
        json.fromJson("{\"value\":[\"whole-array\"]}", MultiArray.class).value[0],
        new String[] {"whole-array"});

    StringArrayModel generic = new StringArrayModel();
    generic.value = new String[] {"x"};
    assertEquals(json.toJson(generic), "{\"value\":[\"A:x\"]}");
    assertEquals(
        json.fromJson("{\"value\":[\"A:y\"]}", StringArrayModel.class).value, new String[] {"y"});
  }

  @Test
  public void collections() {
    ForyJson json = newJson();
    ListModel list = new ListModel();
    list.value = Arrays.asList("x", null);
    assertEquals(json.toJson(list), "{\"value\":[\"A:x\",null]}");
    assertEquals(
        json.fromJson("{\"value\":[\"A:y\",null]}", ListModel.class).value,
        Arrays.asList("y", null));

    SetModel set = new SetModel();
    set.value = new LinkedHashSet<>(Arrays.asList("x"));
    assertEquals(json.toJson(set), "{\"value\":[\"A:x\"]}");
    assertTrue(json.fromJson("{\"value\":[\"A:y\"]}", SetModel.class).value.contains("y"));

    CollectionModel collection = new CollectionModel();
    collection.value = Arrays.asList("x");
    assertEquals(json.toJson(collection), "{\"value\":[\"A:x\"]}");
    assertEquals(
        new ArrayList<>(json.fromJson("{\"value\":[\"A:y\"]}", CollectionModel.class).value),
        Arrays.asList("y"));

    ConcreteListModel concrete = new ConcreteListModel();
    concrete.value = new ArrayList<>(Arrays.asList("x"));
    assertEquals(json.toJson(concrete), "{\"value\":[\"A:x\"]}");
    assertEquals(
        json.fromJson("{\"value\":[\"A:y\"]}", ConcreteListModel.class).value, Arrays.asList("y"));
  }

  @Test
  public void reorderedCollection() {
    ForyJson json = newJson();
    ReorderedListModel reordered = new ReorderedListModel();
    reordered.value = new ReorderedList<>();
    reordered.value.add("x");
    assertEquals(json.toJson(reordered), "{\"value\":[\"A:x\"]}");
    assertEquals(
        json.fromJson("{\"value\":[\"A:y\"]}", ReorderedListModel.class).value, Arrays.asList("y"));
  }

  @Test
  public void maps() {
    ForyJson json = newJson();
    WholeMap whole = new WholeMap();
    whole.values.put("x", "ignored");
    assertEquals(json.toJson(whole), "{\"values\":\"whole-map\"}");
    assertTrue(json.fromJson("{\"values\":\"whole-map\"}", WholeMap.class).values.isEmpty());

    MapModel value = new MapModel();
    value.values.put("x", "one");
    assertEquals(json.toJson(value), "{\"values\":{\"x\":\"A:one\"}}");
    assertEquals(
        json.fromJson("{\"values\":{\"x\":\"A:two\"}}", MapModel.class).values.get("x"), "two");
    MapKeyModel keys = new MapKeyModel();
    keys.values.put(1, "one");
    assertEquals(json.toJson(keys), "{\"values\":{\"K:1\":\"one\"}}");
    assertEquals(
        json.fromJson("{\"values\":{\"K:2\":\"two\"}}", MapKeyModel.class).values.get(2), "two");

    CombinedMap combined = new CombinedMap();
    combined.values.put(3, "three");
    assertEquals(json.toJson(combined), "{\"values\":{\"K:3\":\"A:three\"}}");
    assertEquals(
        json.fromJson("{\"values\":{\"K:4\":\"A:four\"}}", CombinedMap.class).values.get(4),
        "four");

    DirectMapKeyCodec.reset();
    DirectMapKeyModel direct = new DirectMapKeyModel();
    direct.values.put(5, "five");
    assertEquals(json.toJson(direct), "{\"values\":{\"D:5\":\"five\"}}");
    assertEquals(
        json.fromJson("{\"values\":{\"D:6\":\"six\"}}", DirectMapKeyModel.class).values.get(6),
        "six");
    assertEquals(DirectMapKeyCodec.directWrites, 1);
    assertEquals(DirectMapKeyCodec.directReads, 1);
    assertEquals(DirectMapKeyCodec.fallbackCalls, 0);
  }

  @Test
  public void references() {
    ForyJson json = newJson();
    OptionalModel optional = new OptionalModel();
    optional.value = Optional.of("x");
    assertEquals(json.toJson(optional), "{\"value\":\"A:x\"}");
    assertEquals(json.fromJson("{\"value\":\"A:y\"}", OptionalModel.class).value, Optional.of("y"));

    AtomicModel atomic = new AtomicModel();
    atomic.value = new AtomicReference<>("x");
    assertEquals(json.toJson(atomic), "{\"value\":\"A:x\"}");
    assertEquals(json.fromJson("{\"value\":\"A:y\"}", AtomicModel.class).value.get(), "y");

    AtomicArrayModel array = new AtomicArrayModel();
    array.value = new AtomicReferenceArray<>(new String[] {"x"});
    assertEquals(json.toJson(array), "{\"value\":[\"A:x\"]}");
    assertEquals(json.fromJson("{\"value\":[\"A:y\"]}", AtomicArrayModel.class).value.get(0), "y");
  }

  @Test
  public void ownerSubstitution() {
    ForyJson json = newJson();
    EnvelopeOwner owner = new EnvelopeOwner();
    owner.envelope = new AnnotatedEnvelope<>();
    owner.envelope.values = Arrays.asList("x");
    assertEquals(json.toJson(owner), "{\"envelope\":{\"values\":[\"A:x\"]}}");
    assertEquals(
        json.fromJson("{\"envelope\":{\"values\":[\"A:y\"]}}", EnvelopeOwner.class).envelope.values,
        Arrays.asList("y"));

    FixedListModel fixed = new FixedListModel();
    fixed.values = new StringList();
    fixed.values.add("fixed");
    assertEquals(json.toJson(fixed), "{\"values\":[\"A:fixed\"]}");
    assertEquals(
        json.fromJson("{\"values\":[\"A:value\"]}", FixedListModel.class).values,
        Arrays.asList("value"));
  }

  @Test
  public void invalidChildren() {
    assertFailure(() -> newJson().toJson(new RawEnvelopeOwner()));
    assertFailure(() -> newJson().toJson(new RawCollectionModel()));
    assertFailure(() -> newJson().toJson(new WildcardCollectionModel()));
    assertFailure(() -> newJson().toJson(new RawMapModel()));
    assertFailure(() -> newJson().toJson(new WildcardMapModel()));
    assertFailure(() -> newJson().toJson(new WildcardContentModel()));
    assertFailure(() -> newJson().toJson(new IterableModel()));
    assertFailure(
        () -> newJson().toJson(new GenericArrayModel<>()),
        "elementCodec requires a concrete direct child type");
  }

  @Test
  public void cacheIsolation() {
    ForyJson json = newJson();
    CacheModel value = new CacheModel();
    value.plain = "plain";
    value.a = "one";
    value.sameA = "two";
    value.b = "three";
    String text = json.toJson(value);
    assertTrue(text.contains("\"plain\":\"plain\""));
    assertTrue(text.contains("\"a\":\"A:one\""));
    assertTrue(text.contains("\"sameA\":\"A:two\""));
    assertTrue(text.contains("\"b\":\"B:three\""));

    CacheModel decoded =
        json.fromJson(
            "{\"plain\":\"plain\",\"a\":\"A:one\",\"sameA\":\"A:two\"," + "\"b\":\"B:three\"}",
            CacheModel.class);
    assertEquals(decoded.plain, "plain");
    assertEquals(decoded.a, "one");
    assertEquals(decoded.sameA, "two");
    assertEquals(decoded.b, "three");

    ChildCacheModel children = new ChildCacheModel();
    children.plain = Arrays.asList("plain");
    children.a = Arrays.asList("one");
    children.b = Arrays.asList("two");
    String childText = json.toJson(children);
    assertTrue(childText.contains("\"a\":[\"A:one\"]"), childText);
    assertTrue(childText.contains("\"b\":[\"B:two\"]"), childText);
    assertTrue(childText.contains("\"plain\":[\"plain\"]"), childText);
    ChildCacheModel restored =
        json.fromJson(
            "{\"a\":[\"A:one\"],\"b\":[\"B:two\"],\"plain\":[\"plain\"]}", ChildCacheModel.class);
    assertEquals(restored.a, Arrays.asList("one"));
    assertEquals(restored.b, Arrays.asList("two"));
    assertEquals(restored.plain, Arrays.asList("plain"));
  }

  @Test
  public void precedence() {
    ForyJson defaults = newJson();
    assertEquals(defaults.toJson(new PrecedenceValue()), "\"declared\"");
    assertEquals(defaults.toJson(new InheritedPrecedenceValue()), "\"declared\"");

    ForyJson registered =
        newJsonBuilder().registerCodec(PrecedenceValue.class, new BuilderPrecedenceCodec()).build();
    PrecedenceHolder holder = new PrecedenceHolder();
    holder.local = new PrecedenceValue();
    holder.regular = new PrecedenceValue();
    String text = registered.toJson(holder);
    assertTrue(text.contains("\"local\":\"local\""));
    assertTrue(text.contains("\"regular\":\"builder\""));

    ForyJson inheritedRegistered =
        newJsonBuilder()
            .registerCodec(InheritedPrecedenceValue.class, new BuilderChildCodec())
            .build();
    InheritedPrecedenceHolder inherited = new InheritedPrecedenceHolder();
    inherited.local = new InheritedPrecedenceValue();
    inherited.regular = new InheritedPrecedenceValue();
    text = inheritedRegistered.toJson(inherited);
    assertTrue(text.contains("\"local\":\"local\""));
    assertTrue(text.contains("\"regular\":\"builder-child\""));
  }

  @Test
  public void valueAndChild() {
    assertFailure(() -> newJson().toJson(new InvalidComposition()));

    WholeContainer value = new WholeContainer();
    value.values = Arrays.asList(new DeclaredValue());
    assertEquals(newJson().toJson(value), "{\"values\":\"whole-default\"}");
  }

  @Test
  public void invalidShapes() {
    assertFailure(() -> newJson().toJson(new EmptyCodec()));
    assertFailure(() -> newJson().toJson(new WrongShape()));
    assertFailure(() -> newJson().toJson(new InvalidTypeDeclaration()));
    assertFailure(() -> newJson().toJson(new InvalidMapKey()));
    assertFailure(() -> newJson().toJson(new SetterMethodCodec()));
    assertFailure(() -> newJson().toJson(new UnrelatedParameterCodec()));
    assertFailure(() -> newJson().toJson(new AnyKeyCodec()));
    assertFailure(
        () -> newJsonBuilder().withFieldMode(true).build().toJson(new IgnoredCodecField()),
        "@JsonCodec has no JSON read or write direction");
    assertFailure(
        () -> newJson().toJson(new AtomicArrayContent()),
        "supports only elementCodec as a child codec");
    assertFailure(
        () -> newJson().fromJson("{\"values\":{\"wrong\":\"value\"}}", WrongMapKey.class));
    assertFailure(
        () -> newJson().toJson(new NullMapKey()), "JSON map key codec returned a null member name");
  }

  private static String jsonText(ForyJson json, Object value) {
    String text = json.toJson(value);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), text);
    return text;
  }

  private static void assertFailure(FailureCall call, String... fragments) {
    try {
      call.run();
      fail("Expected ForyJsonException");
    } catch (ForyJsonException e) {
      for (String fragment : fragments) {
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
      }
    }
  }

  private interface FailureCall {
    void run();
  }

  public abstract static class TaggedStringCodec implements JsonValueCodec<String> {
    protected abstract String tag();

    @Override
    public final void writeString(StringJsonWriter writer, String value) {
      writeTagged(writer, value, tag());
    }

    @Override
    public final void writeUtf8(Utf8JsonWriter writer, String value) {
      writeTagged(writer, value, tag());
    }

    @Override
    public final String readLatin1(Latin1JsonReader reader) {
      return readTagged(reader, tag());
    }

    @Override
    public final String readUtf16(Utf16JsonReader reader) {
      return readTagged(reader, tag());
    }

    @Override
    public final String readUtf8(Utf8JsonReader reader) {
      return readTagged(reader, tag());
    }
  }

  public static class AStringCodec extends TaggedStringCodec {
    @Override
    protected String tag() {
      return "A";
    }
  }

  public static class BStringCodec extends TaggedStringCodec {
    @Override
    protected String tag() {
      return "B";
    }
  }

  public static class TaggedMapKeyCodec implements MapKeyCodec {
    @Override
    public String toName(Object key) {
      return "K:" + key;
    }

    @Override
    public Object fromName(String name) {
      if (!name.startsWith("K:")) {
        throw new ForyJsonException("Expected K: map key");
      }
      return Integer.valueOf(name.substring(2));
    }
  }

  public static class DirectMapKeyCodec implements MapKeyCodec {
    private static int directWrites;
    private static int directReads;
    private static int fallbackCalls;

    static void reset() {
      directWrites = 0;
      directReads = 0;
      fallbackCalls = 0;
    }

    @Override
    public String toName(Object key) {
      fallbackCalls++;
      return "D:" + key;
    }

    @Override
    public Object fromName(String name) {
      fallbackCalls++;
      return Integer.valueOf(name.substring(2));
    }

    @Override
    public void writeName(JsonWriter writer, Object key) {
      directWrites++;
      writer.writeFieldName("D:" + key);
    }

    @Override
    public Object readName(JsonReader reader) {
      directReads++;
      return Integer.valueOf(reader.readString().substring(2));
    }
  }

  public static class NoDefaultMapKeyCodec implements MapKeyCodec {
    public NoDefaultMapKeyCodec(String ignored) {}

    @Override
    public String toName(Object key) {
      return key.toString();
    }

    @Override
    public Object fromName(String name) {
      return Integer.valueOf(name);
    }
  }

  public static class WrongMapKeyCodec implements MapKeyCodec {
    @Override
    public String toName(Object key) {
      return key.toString();
    }

    @Override
    public Object fromName(String name) {
      return name;
    }
  }

  public static class NullMapKeyCodec implements MapKeyCodec {
    @Override
    public String toName(Object key) {
      return null;
    }

    @Override
    public Object fromName(String name) {
      return Integer.valueOf(name);
    }
  }

  public static class PathStringCodec implements JsonValueCodec<String> {
    @Override
    public void writeString(StringJsonWriter writer, String value) {
      writeTagged(writer, value, "P");
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, String value) {
      writeTagged(writer, value, "P");
    }

    @Override
    public String readLatin1(Latin1JsonReader reader) {
      return "latin1:" + readTagged(reader, "P");
    }

    @Override
    public String readUtf16(Utf16JsonReader reader) {
      return "utf16:" + readTagged(reader, "P");
    }

    @Override
    public String readUtf8(Utf8JsonReader reader) {
      return "utf8:" + readTagged(reader, "P");
    }
  }

  public static class IntCodec implements JsonValueCodec<Integer> {
    @Override
    public void writeString(StringJsonWriter writer, Integer value) {
      writer.writeInt(value.intValue() + 1000);
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Integer value) {
      writer.writeInt(value.intValue() + 1000);
    }

    @Override
    public Integer readLatin1(Latin1JsonReader reader) {
      return Integer.valueOf(reader.readInt() - 1000);
    }

    @Override
    public Integer readUtf16(Utf16JsonReader reader) {
      return Integer.valueOf(reader.readInt() - 1000);
    }

    @Override
    public Integer readUtf8(Utf8JsonReader reader) {
      return Integer.valueOf(reader.readInt() - 1000);
    }
  }

  public static class EnumCodec implements JsonValueCodec<ScalarKind> {
    @Override
    public void writeString(StringJsonWriter writer, ScalarKind value) {
      writer.writeString("E:" + value.name());
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, ScalarKind value) {
      writer.writeString("E:" + value.name());
    }

    @Override
    public ScalarKind readLatin1(Latin1JsonReader reader) {
      return readEnum(reader);
    }

    @Override
    public ScalarKind readUtf16(Utf16JsonReader reader) {
      return readEnum(reader);
    }

    @Override
    public ScalarKind readUtf8(Utf8JsonReader reader) {
      return readEnum(reader);
    }
  }

  public static class ValueCodec implements JsonValueCodec<Value> {
    @Override
    public void writeString(StringJsonWriter writer, Value value) {
      writer.writeString("V:" + value.text);
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Value value) {
      writer.writeString("V:" + value.text);
    }

    @Override
    public Value readLatin1(Latin1JsonReader reader) {
      return readValue(reader);
    }

    @Override
    public Value readUtf16(Utf16JsonReader reader) {
      return readValue(reader);
    }

    @Override
    public Value readUtf8(Utf8JsonReader reader) {
      return readValue(reader);
    }
  }

  public abstract static class ConstantCodec<T> implements JsonValueCodec<T> {
    protected abstract String text();

    protected abstract T value();

    @Override
    public final void writeString(StringJsonWriter writer, T value) {
      writer.writeString(text());
    }

    @Override
    public final void writeUtf8(Utf8JsonWriter writer, T value) {
      writer.writeString(text());
    }

    @Override
    public final T readLatin1(Latin1JsonReader reader) {
      reader.skipValue();
      return value();
    }

    @Override
    public final T readUtf16(Utf16JsonReader reader) {
      reader.skipValue();
      return value();
    }

    @Override
    public final T readUtf8(Utf8JsonReader reader) {
      reader.skipValue();
      return value();
    }
  }

  public static class StringArrayCodec extends ConstantCodec<String[]> {
    @Override
    protected String text() {
      return "whole-array";
    }

    @Override
    protected String[] value() {
      return new String[] {"whole-array"};
    }
  }

  public static class WholeMapCodec extends ConstantCodec<Map<String, String>> {
    @Override
    protected String text() {
      return "whole-map";
    }

    @Override
    protected Map<String, String> value() {
      return new LinkedHashMap<>();
    }
  }

  public static class WholeStringListCodec extends ConstantCodec<List<String>> {
    @Override
    protected String text() {
      return "whole-explicit";
    }

    @Override
    protected List<String> value() {
      return new ArrayList<>();
    }
  }

  public static class WholeDeclaredListCodec extends ConstantCodec<List<DeclaredValue>> {
    @Override
    protected String text() {
      return "whole-default";
    }

    @Override
    protected List<DeclaredValue> value() {
      return new ArrayList<>();
    }
  }

  public abstract static class PrecedenceCodec extends ConstantCodec<PrecedenceValue> {
    private final String text;

    protected PrecedenceCodec(String text) {
      this.text = text;
    }

    @Override
    protected final String text() {
      return text;
    }

    @Override
    protected PrecedenceValue value() {
      return new PrecedenceValue();
    }
  }

  public static class DeclaredPrecedenceCodec extends PrecedenceCodec {
    public DeclaredPrecedenceCodec() {
      super("declared");
    }
  }

  public static class LocalPrecedenceCodec extends PrecedenceCodec {
    public LocalPrecedenceCodec() {
      super("local");
    }
  }

  public static class BuilderPrecedenceCodec extends PrecedenceCodec {
    public BuilderPrecedenceCodec() {
      super("builder");
    }
  }

  public static class BuilderChildCodec extends ConstantCodec<InheritedPrecedenceValue> {
    @Override
    protected String text() {
      return "builder-child";
    }

    @Override
    protected InheritedPrecedenceValue value() {
      return new InheritedPrecedenceValue();
    }
  }

  public static class DeclaredValueCodec extends ConstantCodec<DeclaredValue> {
    @Override
    protected String text() {
      return "declared-value";
    }

    @Override
    protected DeclaredValue value() {
      return new DeclaredValue();
    }
  }

  private static void writeTagged(JsonWriter writer, String value, String tag) {
    if (value == null) {
      writer.writeNull();
    } else {
      writer.writeString(tag + ':' + value);
    }
  }

  private static String readTagged(JsonReader reader, String tag) {
    String value = reader.readNullableString();
    if (value == null) {
      return null;
    }
    String prefix = tag + ':';
    if (!value.startsWith(prefix)) {
      throw new ForyJsonException("Expected " + prefix);
    }
    return value.substring(prefix.length());
  }

  private static ScalarKind readEnum(JsonReader reader) {
    return ScalarKind.valueOf(readTagged(reader, "E"));
  }

  private static Value readValue(JsonReader reader) {
    return new Value(readTagged(reader, "V"));
  }

  public static class RepresentationModel {
    @JsonCodec(PathStringCodec.class)
    public String value;
  }

  public static class MergedProperty {
    @JsonCodec(AStringCodec.class)
    private String value;

    @JsonCodec(AStringCodec.class)
    public String getValue() {
      return value;
    }

    public void setValue(@JsonCodec(AStringCodec.class) String value) {
      this.value = value;
    }
  }

  public static class GetterProperty {
    private String value;

    @JsonCodec(AStringCodec.class)
    public String getValue() {
      return value;
    }

    public void setValue(String value) {
      this.value = value;
    }
  }

  public static class SetterProperty {
    private String value;

    public String getValue() {
      return value;
    }

    public void setValue(@JsonCodec(AStringCodec.class) String value) {
      this.value = value;
    }
  }

  public static class ConflictingProperty {
    @JsonCodec(AStringCodec.class)
    private String value;

    @JsonCodec(BStringCodec.class)
    public String getValue() {
      return value;
    }

    public void setValue(@JsonCodec(AStringCodec.class) String value) {
      this.value = value;
    }
  }

  public static class PartialMapProperty {
    @JsonCodec(keyCodec = TaggedMapKeyCodec.class)
    private Map<Integer, String> values = new LinkedHashMap<>();

    @JsonCodec(valueCodec = AStringCodec.class)
    public Map<Integer, String> getValues() {
      return values;
    }

    public void setValues(Map<Integer, String> values) {
      this.values = values;
    }
  }

  public static class PropertyListCreator {
    @JsonCodec(AStringCodec.class)
    public final String value;

    @JsonCreator({"value"})
    public PropertyListCreator(@JsonCodec(AStringCodec.class) String value) {
      this.value = value;
    }
  }

  public static class ParameterLocalCreator {
    private final String value;

    @JsonCreator
    public ParameterLocalCreator(
        @JsonProperty("value") @JsonCodec(AStringCodec.class) String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }
  }

  public static class CreatorOnly {
    public transient String decoded;

    @JsonCreator
    public CreatorOnly(@JsonProperty("input") @JsonCodec(AStringCodec.class) String input) {
      decoded = input;
    }
  }

  public static class FieldAny {
    @JsonAnyProperty
    @JsonCodec(valueCodec = AStringCodec.class)
    public Map<String, String> values = new LinkedHashMap<>();
  }

  public static class MethodAny {
    public transient Map<String, String> values = new LinkedHashMap<>();

    @JsonAnyGetter
    @JsonCodec(valueCodec = AStringCodec.class)
    public Map<String, String> any() {
      return values;
    }

    @JsonAnySetter
    public void put(String key, @JsonCodec(AStringCodec.class) String value) {
      values.put(key, value);
    }
  }

  public static class ParameterAny {
    public transient Map<String, String> values = new LinkedHashMap<>();

    @JsonAnyGetter
    public Map<String, String> any() {
      return values;
    }

    @JsonAnySetter
    public void put(String key, @JsonCodec(AStringCodec.class) String value) {
      values.put(key, value);
    }
  }

  public static class ConflictingAny {
    public transient Map<String, String> values = new LinkedHashMap<>();

    @JsonAnyGetter
    @JsonCodec(valueCodec = AStringCodec.class)
    public Map<String, String> any() {
      return values;
    }

    @JsonAnySetter
    public void put(String key, @JsonCodec(BStringCodec.class) String value) {
      values.put(key, value);
    }
  }

  public static class OuterAny {
    @JsonAnyProperty
    @JsonCodec(WholeMapCodec.class)
    public Map<String, String> values = new LinkedHashMap<>();
  }

  public static class MethodOuterAny {
    @JsonAnyGetter
    @JsonCodec(WholeMapCodec.class)
    public Map<String, String> values() {
      return new LinkedHashMap<>();
    }
  }

  public static class KeyAny {
    @JsonAnyProperty
    @JsonCodec(keyCodec = TaggedMapKeyCodec.class)
    public Map<String, String> values = new LinkedHashMap<>();
  }

  public static class StaticCodecField {
    @JsonCodec(AStringCodec.class)
    public static String value;
  }

  public interface CodecGetter {
    @JsonCodec(AStringCodec.class)
    default String getValue() {
      return "x";
    }
  }

  public static class InterfaceGetter implements CodecGetter {}

  public static class OverriddenGetter implements CodecGetter {
    @Override
    public String getValue() {
      return "x";
    }
  }

  public static class UnrelatedMethod {
    @JsonCodec(AStringCodec.class)
    public String value() {
      return "x";
    }
  }

  public enum ScalarKind {
    ONE,
    TWO
  }

  public static class Value {
    public String text;

    public Value() {}

    public Value(String text) {
      this.text = text;
    }
  }

  public static class ScalarModel {
    @JsonCodec(IntCodec.class)
    public int number;

    @JsonCodec(AStringCodec.class)
    public String text;

    @JsonCodec(EnumCodec.class)
    public ScalarKind kind;

    @JsonCodec(ValueCodec.class)
    public Value object;
  }

  public static class WholeArray {
    @JsonCodec(StringArrayCodec.class)
    public String[] value;
  }

  public static class ComponentArray {
    @JsonCodec(elementCodec = AStringCodec.class)
    public String[] value;
  }

  public static class MultiArray {
    @JsonCodec(elementCodec = StringArrayCodec.class)
    public String[][] value;
  }

  public static class PrimitiveArray {
    @JsonCodec(elementCodec = IntCodec.class)
    public int[] value;
  }

  public static class GenericArrayModel<T> {
    @JsonCodec(elementCodec = AStringCodec.class)
    public T[] value;
  }

  public static class StringArrayModel extends GenericArrayModel<String> {}

  public static class ListModel {
    @JsonCodec(elementCodec = AStringCodec.class)
    public List<String> value;
  }

  public static class SetModel {
    @JsonCodec(elementCodec = AStringCodec.class)
    public Set<String> value;
  }

  public static class CollectionModel {
    @JsonCodec(elementCodec = AStringCodec.class)
    public Collection<String> value;
  }

  public static class ConcreteListModel {
    @JsonCodec(elementCodec = AStringCodec.class)
    public ArrayList<String> value;
  }

  public static class ReorderedList<K, V> extends ArrayList<V> {}

  public static class ReorderedListModel {
    @JsonCodec(elementCodec = AStringCodec.class)
    public ReorderedList<Integer, String> value;
  }

  public static class MapModel {
    @JsonCodec(valueCodec = AStringCodec.class)
    public Map<String, String> values = new LinkedHashMap<>();
  }

  public static class WholeMap {
    @JsonCodec(WholeMapCodec.class)
    public Map<String, String> values = new LinkedHashMap<>();
  }

  public static class MapKeyModel {
    @JsonCodec(keyCodec = TaggedMapKeyCodec.class)
    public Map<Integer, String> values = new LinkedHashMap<>();
  }

  public static class CombinedMap {
    @JsonCodec(keyCodec = TaggedMapKeyCodec.class, valueCodec = AStringCodec.class)
    public Map<Integer, String> values = new LinkedHashMap<>();
  }

  public static class DirectMapKeyModel {
    @JsonCodec(keyCodec = DirectMapKeyCodec.class)
    public Map<Integer, String> values = new LinkedHashMap<>();
  }

  public static class OptionalModel {
    @JsonCodec(contentCodec = AStringCodec.class)
    public Optional<String> value;
  }

  public static class AtomicModel {
    @JsonCodec(contentCodec = AStringCodec.class)
    public AtomicReference<String> value;
  }

  public static class AtomicArrayModel {
    @JsonCodec(elementCodec = AStringCodec.class)
    public AtomicReferenceArray<String> value;
  }

  public static class EnvelopeOwner {
    public AnnotatedEnvelope<String> envelope;
  }

  public static class AnnotatedEnvelope<T> {
    @JsonCodec(elementCodec = AStringCodec.class)
    public List<T> values;
  }

  @SuppressWarnings("rawtypes")
  public static class RawEnvelopeOwner {
    public AnnotatedEnvelope envelope = new AnnotatedEnvelope();
  }

  public static class StringList extends ArrayList<String> {}

  public static class FixedListModel {
    @JsonCodec(elementCodec = AStringCodec.class)
    public StringList values;
  }

  public static class RawCollectionModel {
    @SuppressWarnings("rawtypes")
    @JsonCodec(elementCodec = AStringCodec.class)
    public List values;
  }

  public static class WildcardCollectionModel {
    @JsonCodec(elementCodec = AStringCodec.class)
    public List<?> values;
  }

  public static class RawMapModel {
    @SuppressWarnings("rawtypes")
    @JsonCodec(valueCodec = AStringCodec.class)
    public Map values;
  }

  public static class WildcardMapModel {
    @JsonCodec(valueCodec = AStringCodec.class)
    public Map<String, ?> values;
  }

  public static class WildcardContentModel {
    @JsonCodec(contentCodec = AStringCodec.class)
    public Optional<?> value;
  }

  public static class IterableModel {
    @JsonCodec(elementCodec = AStringCodec.class)
    public Iterable<String> values;
  }

  public static class CacheModel {
    public String plain;

    @JsonCodec(AStringCodec.class)
    public String a;

    @JsonCodec(AStringCodec.class)
    public String sameA;

    @JsonCodec(BStringCodec.class)
    public String b;
  }

  public static class ChildCacheModel {
    public List<String> plain;

    @JsonCodec(elementCodec = AStringCodec.class)
    public List<String> a;

    @JsonCodec(elementCodec = BStringCodec.class)
    public List<String> b;
  }

  @JsonCodec(DeclaredPrecedenceCodec.class)
  public static class PrecedenceValue {}

  public static class InheritedPrecedenceValue extends PrecedenceValue {}

  public static class PrecedenceHolder {
    @JsonCodec(LocalPrecedenceCodec.class)
    public PrecedenceValue local;

    public PrecedenceValue regular;
  }

  public static class InheritedPrecedenceHolder {
    @JsonCodec(LocalPrecedenceCodec.class)
    public InheritedPrecedenceValue local;

    public InheritedPrecedenceValue regular;
  }

  public static class InvalidComposition {
    @JsonCodec(value = WholeStringListCodec.class, elementCodec = AStringCodec.class)
    public List<String> values;
  }

  @JsonCodec(DeclaredValueCodec.class)
  public static class DeclaredValue {}

  public static class WholeContainer {
    @JsonCodec(WholeDeclaredListCodec.class)
    public List<DeclaredValue> values;
  }

  public static class EmptyCodec {
    @JsonCodec public String value;
  }

  public static class WrongShape {
    @JsonCodec(elementCodec = AStringCodec.class)
    public String value;
  }

  public static class InvalidMapKey {
    @JsonCodec(keyCodec = NoDefaultMapKeyCodec.class)
    public Map<Integer, String> values = new LinkedHashMap<>();
  }

  public static class WrongMapKey {
    @JsonCodec(keyCodec = WrongMapKeyCodec.class)
    public Map<Integer, String> values = new LinkedHashMap<>();
  }

  public static class NullMapKey {
    @JsonCodec(keyCodec = NullMapKeyCodec.class)
    public Map<Integer, String> values = new LinkedHashMap<>();

    public NullMapKey() {
      values.put(1, "value");
    }
  }

  public static class AtomicArrayContent {
    @JsonCodec(contentCodec = AStringCodec.class)
    public AtomicReferenceArray<String> value;
  }

  public static class IgnoredCodecField {
    @JsonIgnore
    @JsonCodec(AStringCodec.class)
    public String value;
  }

  public static class SetterMethodCodec {
    private String value;

    public String getValue() {
      return value;
    }

    @JsonCodec(AStringCodec.class)
    public void setValue(String value) {
      this.value = value;
    }
  }

  public static class UnrelatedParameterCodec {
    public void accept(@JsonCodec(AStringCodec.class) String value) {}
  }

  public static class AnyKeyCodec {
    @JsonAnySetter
    public void put(@JsonCodec(AStringCodec.class) String key, String value) {}
  }

  @JsonCodec(elementCodec = AStringCodec.class)
  public static class InvalidTypeDeclaration {}
}

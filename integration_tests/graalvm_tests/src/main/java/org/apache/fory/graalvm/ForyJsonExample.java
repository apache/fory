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

package org.apache.fory.graalvm;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.apache.fory.graalvm.closed.ClosedJsonRecord;
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.PropertyNamingStrategy;
import org.apache.fory.json.annotation.JsonAnyProperty;
import org.apache.fory.json.annotation.JsonBase64;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonRawValue;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.apache.fory.json.annotation.JsonType;
import org.apache.fory.json.annotation.JsonUnwrapped;
import org.apache.fory.json.annotation.JsonValue;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.codec.MapKeyCodec;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.util.Preconditions;

/** Native-image acceptance coverage for the complete interpreted Fory JSON path. */
public final class ForyJsonExample {
  private ForyJsonExample() {}

  public static void main(String[] args) {
    testModels();
    testConfigurations();
    testCodecs();
    testValueAnnotations();
    testSubtypes();
    testContainerRoots();
    testGenericProperties();
    testUnwrapped();
    testBigDecimal();
    testSqlTypes();
    testClosedPackage();
    System.out.println("Fory JSON succeed");
  }

  private static void testClosedPackage() {
    ForyJson json = ForyJson.builder().build();
    ClosedJsonRecord value = new ClosedJsonRecord(17, "closed");
    String encoded = json.toJson(value);
    Preconditions.checkArgument(json.fromJson(encoded, ClosedJsonRecord.class).equals(value));
  }

  private static void testModels() {
    ForyJson json = ForyJson.builder().build();
    Model value = new Model();
    value.child = new Child(1, "first");
    value.children = List.of(new Child(2, "second"));
    value.childrenByName = Map.of("third", new Child(3, "third"));
    value.concreteChildren = new ArrayList<>(List.of(new Child(11, "concrete")));
    value.concreteChildrenByName = new HashMap<>();
    value.concreteChildrenByName.put("map", new Child(12, "concrete-map"));
    value.childArray = new Child[] {new Child(4, "fourth")};
    value.status = Status.ACTIVE;
    value.bean = new Bean("interface");
    value.record = new DataRecord(5, "record");
    value.creator = new CreatorValue(6, "creator");
    value.factory = FactoryValue.create(7, "factory");
    value.extra.put("dynamic", 8);

    byte[] bytes = json.toJsonBytes(value);
    Model decoded = json.fromJson(bytes, Model.class);
    Preconditions.checkArgument(decoded.inheritedId() == 10);
    Preconditions.checkArgument(decoded.child.id == 1);
    Preconditions.checkArgument(decoded.children.get(0).name.equals("second"));
    Preconditions.checkArgument(decoded.childrenByName.get("third").id == 3);
    Preconditions.checkArgument(decoded.concreteChildren.get(0).id == 11);
    Preconditions.checkArgument(decoded.concreteChildrenByName.get("map").id == 12);
    Preconditions.checkArgument(decoded.childArray[0].name.equals("fourth"));
    Preconditions.checkArgument(decoded.status == Status.ACTIVE);
    Preconditions.checkArgument(decoded.bean.getDisplayName().equals("interface"));
    Preconditions.checkArgument(decoded.record.equals(new DataRecord(5, "record")));
    Preconditions.checkArgument(decoded.creator.name.equals("creator"));
    Preconditions.checkArgument(decoded.factory.id == 7);
    Preconditions.checkArgument(decoded.extra.containsKey("dynamic"));
  }

  private static void testConfigurations() {
    ConfigValue value = new ConfigValue();
    value.camelName = "configured";
    String defaults = ForyJson.builder().build().toJson(value);
    Preconditions.checkArgument(defaults.contains("\"camelName\""));
    Preconditions.checkArgument(!defaults.contains("nullValue"));

    ForyJson configured =
        ForyJson.builder()
            .withFieldMode(true)
            .writeNullFields(true)
            .withPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)
            .build();
    String snakeCase = configured.toJson(value);
    Preconditions.checkArgument(snakeCase.contains("\"camel_name\""));
    Preconditions.checkArgument(snakeCase.contains("\"null_value\":null"));
    ConfigValue decoded = configured.fromJson(snakeCase, ConfigValue.class);
    Preconditions.checkArgument(decoded.camelName.equals("configured"));
  }

  private static void testCodecs() {
    ForyJson json = ForyJson.builder().build();
    CodecModel value = new CodecModel();
    value.direct = new DirectValue("direct");
    value.inherited = new InheritedValue("inherited");
    value.elements = List.of(new CodecValue("element"));
    value.array = new CodecValue[] {new CodecValue("array")};
    value.atomicArray =
        new AtomicReferenceArray<>(new CodecValue[] {new CodecValue("atomic-array")});
    value.mapped = Map.of(new CodecKey("key"), new CodecValue("mapped"));
    value.optional = Optional.of(new CodecValue("optional"));
    value.atomic = new AtomicReference<>(new CodecValue("atomic"));
    value.extra.put("dynamic", new CodecValue("any"));
    value.setParameterValue(new CodecValue("parameter"));
    value.getterValue = new CodecValue("getter");
    value.record = new CodecRecord(new CodecValue("record"));
    value.creator = new CodecCreator(new CodecValue("creator"));
    value.factory = CodecFactory.create(new CodecValue("factory"));

    String stringJson = json.toJson(value);
    Preconditions.checkArgument(stringJson.contains("string:direct"));
    Preconditions.checkArgument(stringJson.contains("string:inherited"));
    Preconditions.checkArgument(stringJson.contains("string:element"));
    Preconditions.checkArgument(stringJson.contains("string:array"));
    Preconditions.checkArgument(stringJson.contains("string:atomic-array"));
    Preconditions.checkArgument(stringJson.contains("\"key:key\""));
    Preconditions.checkArgument(stringJson.contains("string:mapped"));
    Preconditions.checkArgument(stringJson.contains("string:optional"));
    Preconditions.checkArgument(stringJson.contains("string:atomic"));
    Preconditions.checkArgument(stringJson.contains("string:any"));
    Preconditions.checkArgument(stringJson.contains("string:getter"));
    Preconditions.checkArgument(stringJson.contains("string:parameter"));
    String utf8Json = new String(json.toJsonBytes(value), StandardCharsets.UTF_8);
    Preconditions.checkArgument(utf8Json.contains("utf8:direct"));
    Preconditions.checkArgument(utf8Json.contains("utf8:element"));

    DirectValue stringValue = json.fromJson("\"value\"", DirectValue.class);
    DirectValue utf16 = json.fromJson("\"\u4f60\"", DirectValue.class);
    DirectValue utf8 =
        json.fromJson("\"value\"".getBytes(StandardCharsets.UTF_8), DirectValue.class);
    checkStringRead(stringValue.text, "value");
    Preconditions.checkArgument(utf16.text.equals("utf16:\u4f60"));
    Preconditions.checkArgument(utf8.text.equals("utf8:value"));

    CodecModel decoded = json.fromJson(stringJson, CodecModel.class);
    checkStringRead(decoded.direct.text, "string:direct");
    checkStringRead(decoded.inherited.text, "string:inherited");
    checkStringRead(decoded.elements.get(0).text, "string:element");
    checkStringRead(decoded.array[0].text, "string:array");
    checkStringRead(decoded.atomicArray.get(0).text, "string:atomic-array");
    checkStringRead(decoded.mapped.get(new CodecKey("key")).text, "string:mapped");
    checkStringRead(decoded.optional.orElseThrow().text, "string:optional");
    checkStringRead(decoded.atomic.get().text, "string:atomic");
    checkStringRead(decoded.extra.get("dynamic").text, "string:any");
    checkStringRead(decoded.getGetterValue().text, "string:getter");
    checkStringRead(decoded.getParameterValue().text, "string:parameter");
    checkStringRead(decoded.record.value.text, "string:record");
    checkStringRead(decoded.creator.value.text, "string:creator");
    checkStringRead(decoded.factory.value.text, "string:factory");
  }

  private static void checkStringRead(String actual, String value) {
    Preconditions.checkArgument(actual.equals("latin:" + value) || actual.equals("utf16:" + value));
  }

  private static void testValueAnnotations() {
    ForyJson json = ForyJson.builder().build();
    ValueId value = new ValueId("native-value");
    Preconditions.checkArgument(json.toJson(value).equals("\"native-value\""));
    Preconditions.checkArgument(
        new String(json.toJsonBytes(value), StandardCharsets.UTF_8).equals("\"native-value\""));
    Preconditions.checkArgument(
        json.fromJson("\"decoded\"", ValueId.class).value.equals("decoded"));
    Preconditions.checkArgument(
        json.fromJson("\"bytes\"".getBytes(StandardCharsets.UTF_8), ValueId.class)
            .value
            .equals("bytes"));

    RawValue raw = new RawValue();
    raw.body = "{\"id\":1}";
    Preconditions.checkArgument(json.toJson(raw).equals("{\"body\":{\"id\":1}}"));
    Preconditions.checkArgument(
        new String(json.toJsonBytes(raw), StandardCharsets.UTF_8).equals("{\"body\":{\"id\":1}}"));
    Preconditions.checkArgument(
        json.fromJson("{\"body\":\"text\"}", RawValue.class).body.equals("text"));
    Base64Bytes base64Bytes = new Base64Bytes();
    base64Bytes.value = new byte[] {1, 2, 3};
    Preconditions.checkArgument(json.toJson(base64Bytes).equals("{\"value\":\"AQID\"}"));
    Preconditions.checkArgument(
        new String(json.toJsonBytes(base64Bytes), StandardCharsets.UTF_8)
            .equals("{\"value\":\"AQID\"}"));
    Preconditions.checkArgument(
        Arrays.equals(
            json.fromJson("{\"value\":\"AQID\"}", Base64Bytes.class).value, new byte[] {1, 2, 3}));
  }

  private static void testSubtypes() {
    ForyJson json = ForyJson.builder().build();
    Shape value = new Circle(9);
    String encoded = json.toJson(value, Shape.class);
    Shape decoded = json.fromJson(encoded, Shape.class);
    Preconditions.checkArgument(decoded instanceof Circle);
    Preconditions.checkArgument(((Circle) decoded).radius == 9);
  }

  private static void testContainerRoots() {
    ForyJson json = ForyJson.builder().build();
    StringList list = json.fromJson("[\"first\",\"second\"]", StringList.class);
    Preconditions.checkArgument(list.equals(List.of("first", "second")));
    StringMap map = json.fromJson("{\"key\":\"value\"}", StringMap.class);
    Preconditions.checkArgument(map.equals(Map.of("key", "value")));
  }

  private static void testGenericProperties() {
    ForyJson json = ForyJson.builder().build();
    GenericModel value =
        json.fromJson("{\"values\":[{\"id\":13,\"name\":\"generic\"}]}", GenericModel.class);
    Object values = value.getValues();
    Preconditions.checkArgument(
        values.getClass().getName().equals(ForyJsonExample.class.getName() + "$ChildList"));
    Child child = (Child) ((List<?>) values).get(0);
    Preconditions.checkArgument(child.id == 13);
    Preconditions.checkArgument(child.name.equals("generic"));
  }

  private static void testUnwrapped() {
    ForyJson json = ForyJson.builder().build();
    UnwrappedModel value = new UnwrappedModel(14, new UnwrappedRecord("native", 15));
    String encoded = json.toJson(value);
    Preconditions.checkArgument(
        encoded.equals("{\"id\":14,\"child_name\":\"native\",\"child_rank\":15}"));
    UnwrappedModel decoded = json.fromJson(encoded, UnwrappedModel.class);
    Preconditions.checkArgument(decoded.id == 14);
    Preconditions.checkArgument(decoded.child.equals(new UnwrappedRecord("native", 15)));

    UnwrappedRootRecord record = new UnwrappedRootRecord(16, new UnwrappedRecord("record", 17));
    String recordJson = json.toJson(record);
    Preconditions.checkArgument(
        recordJson.equals("{\"id\":16,\"child_name\":\"record\",\"child_rank\":17}"));
    UnwrappedRootRecord decodedRecord = json.fromJson(recordJson, UnwrappedRootRecord.class);
    Preconditions.checkArgument(decodedRecord.equals(record));
  }

  private static void testBigDecimal() {
    ForyJson json = ForyJson.builder().build();
    BigDecimalHolder value = new BigDecimalHolder();
    value.value = new BigDecimalSubtype("12345678901234567890.125");
    String expected = "{\"value\":12345678901234567890.125}";
    Preconditions.checkArgument(json.toJson(value).equals(expected));
    Preconditions.checkArgument(
        new String(json.toJsonBytes(value), StandardCharsets.UTF_8).equals(expected));
  }

  private static void testSqlTypes() {
    ForyJson json = ForyJson.builder().build();
    SqlValues value = new SqlValues();
    value.date = new Date(1_000L);
    value.time = new Time(2_000L);
    value.timestamp = new Timestamp(3_000L);
    SqlValues decoded = json.fromJson(json.toJsonBytes(value), SqlValues.class);
    Preconditions.checkArgument(decoded.date.getTime() == 1_000L);
    Preconditions.checkArgument(decoded.time.getTime() == 2_000L);
    Preconditions.checkArgument(decoded.timestamp.getTime() == 3_000L);
  }

  public static class Parent {
    private int inheritedId = 10;

    int inheritedId() {
      return inheritedId;
    }
  }

  public static final class StringList extends ArrayList<String> {
    public StringList() {}
  }

  public static final class StringMap extends HashMap<String, String> {
    public StringMap() {}
  }

  public abstract static class GenericProperty<T> {
    private Object value;

    @SuppressWarnings("unchecked")
    public T getValues() {
      return (T) value;
    }

    public void setValues(T value) {
      this.value = value;
    }
  }

  @JsonType
  public static final class GenericModel extends GenericProperty<ChildList> {}

  public static final class ChildList extends ArrayList<Child> {
    public ChildList() {}
  }

  @JsonType
  public static final class BigDecimalHolder {
    public BigDecimal value;
  }

  private static final class BigDecimalSubtype extends BigDecimal {
    private BigDecimalSubtype(String value) {
      super(value);
    }

    @Override
    public String toString() {
      throw new AssertionError("BigDecimal subtype toString must not be invoked");
    }

    @Override
    public BigInteger unscaledValue() {
      throw new AssertionError("BigDecimal subtype unscaledValue must not be invoked");
    }

    @Override
    public int scale() {
      throw new AssertionError("BigDecimal subtype scale must not be invoked");
    }

    @Override
    public BigDecimal negate() {
      throw new AssertionError("BigDecimal subtype negate must not be invoked");
    }
  }

  @JsonType
  public static final class Model extends Parent {
    public Child child;
    public List<Child> children;
    public Map<String, Child> childrenByName;
    public ArrayList<Child> concreteChildren;
    public HashMap<String, Child> concreteChildrenByName;
    public Child[] childArray;
    public Status status;
    public Bean bean;
    public DataRecord record;
    public CreatorValue creator;
    public FactoryValue factory;

    @JsonAnyProperty public Map<String, Object> extra = new LinkedHashMap<>();
  }

  @JsonType
  public static final class Child {
    public int id;
    public String name;

    public Child() {}

    Child(int id, String name) {
      this.id = id;
      this.name = name;
    }
  }

  @JsonType
  public enum Status {
    ACTIVE,
    INACTIVE
  }

  public interface NamedBean {
    String getDisplayName();

    void setDisplayName(String value);
  }

  @JsonType
  public static final class Bean implements NamedBean {
    private String displayName;

    public Bean() {}

    Bean(String displayName) {
      this.displayName = displayName;
    }

    @Override
    public String getDisplayName() {
      return displayName;
    }

    @Override
    public void setDisplayName(String value) {
      displayName = value;
    }
  }

  @JsonType
  public record DataRecord(int id, String name) {}

  @JsonType
  public static final class UnwrappedModel {
    public final int id;

    @JsonUnwrapped(prefix = "child_")
    public final UnwrappedRecord child;

    @JsonCreator({"id", "child"})
    public UnwrappedModel(int id, UnwrappedRecord child) {
      this.id = id;
      this.child = child;
    }
  }

  @JsonType
  public record UnwrappedRecord(String name, int rank) {}

  @JsonType
  public record UnwrappedRootRecord(
      int id, @JsonUnwrapped(prefix = "child_") UnwrappedRecord child) {}

  @JsonType
  public static final class CreatorValue {
    public final int id;
    public final String name;

    @JsonCreator({"id", "name"})
    public CreatorValue(int id, String name) {
      this.id = id;
      this.name = name;
    }
  }

  @JsonType
  public static final class FactoryValue {
    public final int id;
    public final String name;

    private FactoryValue(int id, String name) {
      this.id = id;
      this.name = name;
    }

    @JsonCreator({"id", "name"})
    public static FactoryValue create(int id, String name) {
      return new FactoryValue(id, name);
    }
  }

  @JsonType
  public static final class ValueId {
    private final String value;

    @JsonCreator
    public ValueId(String value) {
      this.value = value;
    }

    @JsonValue
    public String value() {
      return value;
    }
  }

  @JsonType
  public static final class RawValue {
    @JsonRawValue public String body;
  }

  @JsonType
  public static final class Base64Bytes {
    @JsonBase64 public byte[] value;
  }

  @JsonType
  public static final class ConfigValue {
    public String camelName;
    public String nullValue;
  }

  @JsonType
  public static final class CodecModel {
    public DirectValue direct;
    public InheritedValue inherited;

    @JsonCodec(elementCodec = ValueCodec.class)
    public List<CodecValue> elements = new ArrayList<>();

    @JsonCodec(elementCodec = ValueCodec.class)
    public CodecValue[] array;

    @JsonCodec(elementCodec = ValueCodec.class)
    public AtomicReferenceArray<CodecValue> atomicArray;

    @JsonCodec(keyCodec = KeyCodec.class, valueCodec = ValueCodec.class)
    public Map<CodecKey, CodecValue> mapped;

    @JsonCodec(contentCodec = ValueCodec.class)
    public Optional<CodecValue> optional;

    @JsonCodec(contentCodec = ValueCodec.class)
    public AtomicReference<CodecValue> atomic;

    @JsonAnyProperty
    @JsonCodec(valueCodec = ValueCodec.class)
    public Map<String, CodecValue> extra = new LinkedHashMap<>();

    private CodecValue getterValue;
    private CodecValue parameterValue;
    public CodecRecord record;
    public CodecCreator creator;
    public CodecFactory factory;

    @JsonCodec(ValueCodec.class)
    public CodecValue getGetterValue() {
      return getterValue;
    }

    public void setGetterValue(CodecValue getterValue) {
      this.getterValue = getterValue;
    }

    public CodecValue getParameterValue() {
      return parameterValue;
    }

    public void setParameterValue(@JsonCodec(ValueCodec.class) CodecValue parameterValue) {
      this.parameterValue = parameterValue;
    }
  }

  @JsonCodec(DirectCodec.class)
  public static final class DirectValue implements TextValue {
    private final String text;

    DirectValue(String text) {
      this.text = text;
    }

    @Override
    public String text() {
      return text;
    }
  }

  @JsonCodec(InheritedCodec.class)
  public interface InheritedText extends TextValue {}

  public static final class InheritedValue implements InheritedText {
    private final String text;

    InheritedValue(String text) {
      this.text = text;
    }

    @Override
    public String text() {
      return text;
    }
  }

  public static final class CodecValue implements TextValue {
    private final String text;

    CodecValue(String text) {
      this.text = text;
    }

    @Override
    public String text() {
      return text;
    }
  }

  @JsonType
  public record CodecRecord(@JsonCodec(ValueCodec.class) CodecValue value) {}

  @JsonType
  public static final class CodecCreator {
    public final CodecValue value;

    @JsonCreator
    public CodecCreator(@JsonProperty("value") @JsonCodec(ValueCodec.class) CodecValue value) {
      this.value = value;
    }
  }

  @JsonType
  public static final class CodecFactory {
    public final CodecValue value;

    private CodecFactory(CodecValue value) {
      this.value = value;
    }

    @JsonCreator
    public static CodecFactory create(
        @JsonProperty("value") @JsonCodec(ValueCodec.class) CodecValue value) {
      return new CodecFactory(value);
    }
  }

  public interface TextValue {
    String text();
  }

  public abstract static class TextCodec<T extends TextValue> implements JsonValueCodec<T> {
    public TextCodec() {}

    protected abstract T create(String text);

    @Override
    public void writeString(StringJsonWriter writer, T value) {
      writer.writeString(value == null ? null : "string:" + value.text());
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, T value) {
      writer.writeString(value == null ? null : "utf8:" + value.text());
    }

    @Override
    public T readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : create("latin:" + reader.readString());
    }

    @Override
    public T readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : create("utf16:" + reader.readString());
    }

    @Override
    public T readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : create("utf8:" + reader.readString());
    }
  }

  public static final class DirectCodec extends TextCodec<DirectValue> {
    public DirectCodec() {}

    @Override
    protected DirectValue create(String text) {
      return new DirectValue(text);
    }
  }

  public static final class InheritedCodec extends TextCodec<InheritedValue> {
    public InheritedCodec() {}

    @Override
    protected InheritedValue create(String text) {
      return new InheritedValue(text);
    }
  }

  public static final class ValueCodec extends TextCodec<CodecValue> {
    public ValueCodec() {}

    @Override
    protected CodecValue create(String text) {
      return new CodecValue(text);
    }
  }

  public static final class CodecKey {
    private final String text;

    CodecKey(String text) {
      this.text = text;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof CodecKey && Objects.equals(text, ((CodecKey) other).text);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(text);
    }
  }

  public static final class KeyCodec implements MapKeyCodec {
    public KeyCodec() {}

    @Override
    public String toName(Object key) {
      return "key:" + ((CodecKey) key).text;
    }

    @Override
    public Object fromName(String name) {
      return new CodecKey(name.substring("key:".length()));
    }
  }

  @JsonType
  @JsonSubTypes(
      property = "kind",
      value = {@JsonSubTypes.Type(value = Circle.class, name = "circle")})
  public interface Shape {}

  public static final class Circle implements Shape {
    public int radius;

    public Circle() {}

    Circle(int radius) {
      this.radius = radius;
    }
  }

  @JsonType
  public static final class SqlValues {
    public Date date;
    public Time time;
    public Timestamp timestamp;
  }
}

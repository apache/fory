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

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.PropertyNamingStrategy;
import org.apache.fory.json.annotation.JsonAnyProperty;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.apache.fory.json.annotation.JsonType;
import org.apache.fory.json.codec.JsonValueCodec;
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
    testSubtypes();
    testSqlTypes();
    System.out.println("Fory JSON succeed");
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
    value.nested = List.of(new TypeUseValue("nested"));
    value.array = new TypeUseValue[] {new TypeUseValue("array")};
    value.mapped = Map.of("key", new TypeUseValue("mapped"));
    value.property = new TypeUseValue("property");
    value.record = new CodecRecord(new TypeUseValue("record"));
    value.creator = new CodecCreator(new TypeUseValue("creator"));
    value.factory = CodecFactory.create(new TypeUseValue("factory"));

    String stringJson = json.toJson(value);
    Preconditions.checkArgument(stringJson.contains("string:direct"));
    Preconditions.checkArgument(stringJson.contains("string:inherited"));
    Preconditions.checkArgument(stringJson.contains("string:nested"));
    Preconditions.checkArgument(stringJson.contains("string:array"));
    Preconditions.checkArgument(stringJson.contains("string:mapped"));
    String utf8Json = new String(json.toJsonBytes(value), StandardCharsets.UTF_8);
    Preconditions.checkArgument(utf8Json.contains("utf8:direct"));
    Preconditions.checkArgument(utf8Json.contains("utf8:property"));

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
    checkStringRead(decoded.nested.get(0).text, "string:nested");
    checkStringRead(decoded.array[0].text, "string:array");
    checkStringRead(decoded.mapped.get("key").text, "string:mapped");
    checkStringRead(decoded.property.text, "string:property");
    checkStringRead(decoded.record.value.text, "string:record");
    checkStringRead(decoded.creator.value.text, "string:creator");
    checkStringRead(decoded.factory.value.text, "string:factory");
  }

  private static void checkStringRead(String actual, String value) {
    Preconditions.checkArgument(actual.equals("latin:" + value) || actual.equals("utf16:" + value));
  }

  private static void testSubtypes() {
    ForyJson json = ForyJson.builder().build();
    Shape value = new Circle(9);
    String encoded = json.toJson(value, Shape.class);
    Shape decoded = json.fromJson(encoded, Shape.class);
    Preconditions.checkArgument(decoded instanceof Circle);
    Preconditions.checkArgument(((Circle) decoded).radius == 9);
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

  @JsonType
  public static class Parent {
    private int inheritedId = 10;

    int inheritedId() {
      return inheritedId;
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
  public static final class ConfigValue {
    public String camelName;
    public String nullValue;
  }

  @JsonType
  public static final class CodecModel {
    public DirectValue direct;
    public InheritedValue inherited;
    public List<@JsonCodec(TypeUseCodec.class) TypeUseValue> nested = new ArrayList<>();
    public @JsonCodec(TypeUseCodec.class) TypeUseValue[] array;
    public Map<String, @JsonCodec(TypeUseCodec.class) TypeUseValue> mapped;
    private TypeUseValue property;
    public CodecRecord record;
    public CodecCreator creator;
    public CodecFactory factory;

    public @JsonCodec(TypeUseCodec.class) TypeUseValue getProperty() {
      return property;
    }

    public void setProperty(@JsonCodec(TypeUseCodec.class) TypeUseValue property) {
      this.property = property;
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

  public static final class TypeUseValue implements TextValue {
    private final String text;

    TypeUseValue(String text) {
      this.text = text;
    }

    @Override
    public String text() {
      return text;
    }
  }

  @JsonType
  public record CodecRecord(@JsonCodec(TypeUseCodec.class) TypeUseValue value) {}

  @JsonType
  public static final class CodecCreator {
    public final TypeUseValue value;

    @JsonCreator
    public CodecCreator(@JsonProperty("value") @JsonCodec(TypeUseCodec.class) TypeUseValue value) {
      this.value = value;
    }
  }

  @JsonType
  public static final class CodecFactory {
    public final TypeUseValue value;

    private CodecFactory(TypeUseValue value) {
      this.value = value;
    }

    @JsonCreator
    public static CodecFactory create(
        @JsonProperty("value") @JsonCodec(TypeUseCodec.class) TypeUseValue value) {
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

  public static final class TypeUseCodec extends TextCodec<TypeUseValue> {
    public TypeUseCodec() {}

    @Override
    protected TypeUseValue create(String text) {
      return new TypeUseValue(text);
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

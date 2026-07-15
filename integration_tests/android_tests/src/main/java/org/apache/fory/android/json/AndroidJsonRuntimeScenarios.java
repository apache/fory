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

package org.apache.fory.android.json;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.PropertyNamingStrategy;
import org.apache.fory.json.annotation.JsonAnyProperty;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonPropertyOrder;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.apache.fory.json.annotation.JsonType;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.reflect.TypeRef;

public final class AndroidJsonRuntimeScenarios {
  private AndroidJsonRuntimeScenarios() {}

  public static void applicationModelsRoundTrip() {
    check(AndroidSupport.IS_ANDROID, "Fory JSON Android tests must run on Android");
    ForyJson json = ForyJson.builder().withCodegen(true).withAsyncCompilation(true).build();
    AppModel value = model(26, "android");

    String text = json.toJson(value);
    assertAppModel(json.fromJson(text, AppModel.class));
    assertAppModel(json.fromJson(text.getBytes(StandardCharsets.UTF_8), AppModel.class));

    byte[] bytes = json.toJsonBytes(value);
    assertEquals(text, new String(bytes, StandardCharsets.UTF_8));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    json.writeJsonTo(value, output);
    assertEquals(text, new String(output.toByteArray(), StandardCharsets.UTF_8));
  }

  public static void builderConfigurationsRemainIndependent() {
    AppModel value = model(11, null);

    ForyJson lowerCamel =
        ForyJson.builder()
            .withCodegen(true)
            .withAsyncCompilation(true)
            .writeNullFields(false)
            .build();
    ForyJson snake =
        ForyJson.builder()
            .withCodegen(false)
            .withAsyncCompilation(false)
            .writeNullFields(true)
            .withPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)
            .build();
    ForyJson fields = ForyJson.builder().withFieldMode(true).build();
    FieldModeModel fieldValue = new FieldModeModel();
    fieldValue.appId = 11;

    String camelText = lowerCamel.toJson(value);
    String snakeText = snake.toJson(value);
    String fieldText = fields.toJson(fieldValue);
    check(camelText.contains("\"appId\""), "lower camel property missing: " + camelText);
    check(!camelText.contains("nullableName"), "null property must be omitted: " + camelText);
    check(snakeText.contains("\"app_id\""), "snake property missing: " + snakeText);
    check(snakeText.contains("\"nullable_name\":null"), "null property missing: " + snakeText);
    check(fieldText.contains("\"appId\""), "field mode property missing: " + fieldText);

    assertEquals(11, lowerCamel.fromJson(camelText, AppModel.class).appId);
    assertEquals(11, snake.fromJson(snakeText, AppModel.class).appId);
    assertEquals(11, fields.fromJson(fieldText, FieldModeModel.class).appId);
  }

  public static void primitiveBeanUsesEveryTypedAccessor() {
    PrimitiveBean value = new PrimitiveBean();
    value.setBoolValue(true);
    value.setByteValue((byte) 2);
    value.setCharValue('A');
    value.setShortValue((short) 4);
    value.setIntValue(5);
    value.setLongValue(6L);
    value.setFloatValue(7.5f);
    value.setDoubleValue(8.5d);
    value.setTextValue("bean");

    ForyJson json = ForyJson.builder().build();
    PrimitiveBean copy = json.fromJson(json.toJsonBytes(value), PrimitiveBean.class);
    check(copy.isBoolValue(), "boolean accessor mismatch");
    assertEquals(2, copy.getByteValue());
    assertEquals('A', copy.getCharValue());
    assertEquals(4, copy.getShortValue());
    assertEquals(5, copy.getIntValue());
    assertEquals(6L, copy.getLongValue());
    check(Float.compare(7.5f, copy.getFloatValue()) == 0, "float accessor mismatch");
    check(Double.compare(8.5d, copy.getDoubleValue()) == 0, "double accessor mismatch");
    assertEquals("bean", copy.getTextValue());
  }

  public static void annotationsAndSubtypesRoundTrip() {
    ForyJson json = ForyJson.builder().build();

    PrivateCreatorModel creator = json.fromJson("{\"number\":17}", PrivateCreatorModel.class);
    assertEquals(17, creator.number);
    PrivateFactoryModel factory = json.fromJson("{\"number\":19}", PrivateFactoryModel.class);
    assertEquals(19, factory.number);

    AnyModel any = new AnyModel();
    any.fixed = "fixed";
    any.extra.put("dynamic", Integer.valueOf(9));
    AnyModel anyCopy = json.fromJson(json.toJson(any), AnyModel.class);
    assertEquals("fixed", anyCopy.fixed);
    assertEquals(9, anyCopy.extra.get("dynamic").intValue());

    CodedValue coded = json.fromJson("\"coded:value\"", CodedValue.class);
    assertEquals("value", coded.value);
    assertEquals("\"coded:value\"", json.toJson(coded));

    Shape square = json.fromJson(json.toJson(new Square(4), Shape.class), Shape.class);
    check(square instanceof Square, "literal subtype mismatch");
    assertEquals(4, ((Square) square).size);
    Shape circle = json.fromJson(json.toJson(new Circle(3), Shape.class), Shape.class);
    check(circle instanceof Circle, "class-name subtype mismatch");
    assertEquals(3, ((Circle) circle).radius);
  }

  public static void typeRefAndPrivateTypeSurviveR8() {
    ForyJson json = ForyJson.builder().build();
    TypeRef<List<NestedModel>> type = new TypeRef<List<NestedModel>>() {};
    List<NestedModel> values = Arrays.asList(new NestedModel(1, "one"), new NestedModel(2, "two"));
    List<NestedModel> copy = json.fromJson(json.toJson(values, type), type);
    assertEquals(2, copy.size());
    assertEquals("two", copy.get(1).displayName);

    PrivateStaticModel value = new PrivateStaticModel();
    value.setNumber(31);
    PrivateStaticModel privateCopy = json.fromJson(json.toJson(value), PrivateStaticModel.class);
    assertEquals(31, privateCopy.getNumber());
  }

  public static void recordsRoundTrip() {
    ForyJson json = ForyJson.builder().build();
    AppRecord value = new AppRecord(34, "record", Arrays.asList(1, 2));
    String text = json.toJson(value);
    assertEquals(value, json.fromJson(text, AppRecord.class));
    assertEquals(value, json.fromJson(text.getBytes(StandardCharsets.UTF_8), AppRecord.class));

    PrivateRecord privateValue = new PrivateRecord(9, "private");
    assertEquals(privateValue, json.fromJson(json.toJson(privateValue), PrivateRecord.class));
  }

  public static void platformSuperclassRequiresCodec() {
    PlatformChild value = new PlatformChild("platform");
    try {
      ForyJson.builder().build().toJson(value);
      throw new AssertionError("Platform superclass mapping must require an exact codec");
    } catch (ForyJsonException expected) {
      check(
          expected.getMessage().contains("crosses platform superclass java.lang.RuntimeException"),
          "Unexpected platform superclass failure: " + expected.getMessage());
    }

    ForyJson json =
        ForyJson.builder().registerCodec(PlatformChild.class, new PlatformChildCodec()).build();
    assertEquals("\"platform\"", json.toJson(value));
    assertEquals("platform", json.fromJson("\"platform\"", PlatformChild.class).value);
  }

  public static Object newWriteBenchmark() {
    return new WriteBenchmark(ForyJson.builder().build(), model(42, "benchmark"));
  }

  public static Object writeBenchmark(Object state) {
    WriteBenchmark benchmark = (WriteBenchmark) state;
    return benchmark.json.toJsonBytes(benchmark.value);
  }

  public static Object newReadBenchmark() {
    ForyJson json = ForyJson.builder().build();
    byte[] bytes = json.toJson(model(42, "benchmark")).getBytes(StandardCharsets.UTF_8);
    return new ReadBenchmark(json, bytes);
  }

  public static Object readBenchmark(Object state) {
    ReadBenchmark benchmark = (ReadBenchmark) state;
    return benchmark.json.fromJson(benchmark.bytes, AppModel.class);
  }

  private static AppModel model(int appId, String codedValue) {
    AppModel value = new AppModel();
    value.appId = appId;
    value.nested = new NestedModel(7, "nested");
    value.nested.scores.add(Integer.valueOf(3));
    value.codedValue = codedValue == null ? null : new CodedValue(codedValue);
    value.shape = new Square(5);
    return value;
  }

  private static void assertAppModel(AppModel value) {
    assertEquals(26, value.appId);
    assertEquals(7, value.nested.id);
    assertEquals("nested", value.nested.displayName);
    assertEquals(3, value.nested.scores.get(0).intValue());
    assertEquals("android", value.codedValue.value);
    check(value.shape instanceof Square, "nested subtype mismatch");
  }

  private static final class WriteBenchmark {
    private final ForyJson json;
    private final AppModel value;

    private WriteBenchmark(ForyJson json, AppModel value) {
      this.json = json;
      this.value = value;
    }
  }

  private static final class ReadBenchmark {
    private final ForyJson json;
    private final byte[] bytes;

    private ReadBenchmark(ForyJson json, byte[] bytes) {
      this.json = json;
      this.bytes = bytes;
    }
  }

  @JsonType
  @JsonPropertyOrder({"id", "displayName", "scores"})
  public static final class NestedModel {
    @JsonProperty(index = 0)
    public int id;

    public String displayName;
    public List<@JsonCodec(IntegerCodec.class) Integer> scores = new ArrayList<Integer>();

    public NestedModel() {}

    public NestedModel(int id, String displayName) {
      this.id = id;
      this.displayName = displayName;
    }
  }

  @JsonType
  public static final class PrimitiveBean {
    private boolean boolValue;
    private byte byteValue;
    private char charValue;
    private short shortValue;
    private int intValue;
    private long longValue;
    private float floatValue;
    private double doubleValue;
    private String textValue;

    public boolean isBoolValue() {
      return boolValue;
    }

    public void setBoolValue(boolean boolValue) {
      this.boolValue = boolValue;
    }

    public byte getByteValue() {
      return byteValue;
    }

    public void setByteValue(byte byteValue) {
      this.byteValue = byteValue;
    }

    public char getCharValue() {
      return charValue;
    }

    public void setCharValue(char charValue) {
      this.charValue = charValue;
    }

    public short getShortValue() {
      return shortValue;
    }

    public void setShortValue(short shortValue) {
      this.shortValue = shortValue;
    }

    public int getIntValue() {
      return intValue;
    }

    public void setIntValue(int intValue) {
      this.intValue = intValue;
    }

    public long getLongValue() {
      return longValue;
    }

    public void setLongValue(long longValue) {
      this.longValue = longValue;
    }

    public float getFloatValue() {
      return floatValue;
    }

    public void setFloatValue(float floatValue) {
      this.floatValue = floatValue;
    }

    public double getDoubleValue() {
      return doubleValue;
    }

    public void setDoubleValue(double doubleValue) {
      this.doubleValue = doubleValue;
    }

    public String getTextValue() {
      return textValue;
    }

    public void setTextValue(String textValue) {
      this.textValue = textValue;
    }
  }

  @JsonType
  @JsonPropertyOrder({"fixed", "extra"})
  public static final class AnyModel {
    public String fixed;

    @JsonAnyProperty public final Map<String, Integer> extra = new LinkedHashMap<>();
  }

  @JsonType
  @JsonCodec(CodedValueCodec.class)
  public static final class CodedValue {
    public final String value;

    public CodedValue(String value) {
      this.value = value;
    }
  }

  @JsonType
  @JsonSubTypes(
      value = {
        @JsonSubTypes.Type(value = Square.class, name = "square"),
        @JsonSubTypes.Type(
            className = "org.apache.fory.android.json.AndroidJsonRuntimeScenarios$Circle",
            name = "circle")
      },
      property = "kind")
  public interface Shape {}

  @JsonType
  public static final class Square implements Shape {
    public int size;

    public Square() {}

    public Square(int size) {
      this.size = size;
    }
  }

  @JsonType
  public static final class Circle implements Shape {
    public int radius;

    public Circle() {}

    public Circle(int radius) {
      this.radius = radius;
    }
  }

  @JsonType
  private static final class PrivateStaticModel {
    private int number;

    @JsonProperty("number")
    public int getNumber() {
      return number;
    }

    @JsonProperty("number")
    public void setNumber(int number) {
      this.number = number;
    }
  }

  @JsonType
  private static final class PrivateCreatorModel {
    private final int number;

    @JsonCreator
    public PrivateCreatorModel(@JsonProperty("number") int number) {
      this.number = number;
    }
  }

  @JsonType
  private static final class PrivateFactoryModel {
    private final int number;

    private PrivateFactoryModel(int number) {
      this.number = number;
    }

    @JsonCreator
    public static PrivateFactoryModel create(@JsonProperty("number") int number) {
      return new PrivateFactoryModel(number);
    }
  }

  @JsonType
  public static final class PlatformChild extends RuntimeException {
    public String value;

    public PlatformChild() {}

    PlatformChild(String value) {
      this.value = value;
    }
  }

  @JsonType
  public record AppRecord(int id, String name, List<Integer> values) {}

  @JsonType
  private record PrivateRecord(int id, String name) {}

  public static final class IntegerCodec implements JsonValueCodec<Integer> {
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

  public static final class CodedValueCodec implements JsonValueCodec<CodedValue> {
    @Override
    public void writeString(StringJsonWriter writer, CodedValue value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString("coded:" + value.value);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, CodedValue value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString("coded:" + value.value);
      }
    }

    @Override
    public CodedValue readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : decode(reader.readString());
    }

    @Override
    public CodedValue readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : decode(reader.readString());
    }

    @Override
    public CodedValue readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : decode(reader.readString());
    }

    private static CodedValue decode(String value) {
      if (!value.startsWith("coded:")) {
        throw new IllegalArgumentException("Expected coded value but got " + value);
      }
      return new CodedValue(value.substring("coded:".length()));
    }
  }

  public static final class PlatformChildCodec implements JsonValueCodec<PlatformChild> {
    @Override
    public void writeString(StringJsonWriter writer, PlatformChild value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.value);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, PlatformChild value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.value);
      }
    }

    @Override
    public PlatformChild readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : new PlatformChild(reader.readString());
    }

    @Override
    public PlatformChild readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : new PlatformChild(reader.readString());
    }

    @Override
    public PlatformChild readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : new PlatformChild(reader.readString());
    }
  }

  private static void assertEquals(Object expected, Object actual) {
    if (expected == null ? actual != null : !expected.equals(actual)) {
      throw new AssertionError("Expected " + expected + " but got " + actual);
    }
  }

  private static void assertEquals(long expected, long actual) {
    if (expected != actual) {
      throw new AssertionError("Expected " + expected + " but got " + actual);
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}

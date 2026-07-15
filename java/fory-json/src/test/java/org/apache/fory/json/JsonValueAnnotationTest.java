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
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonRawValue;
import org.apache.fory.json.annotation.JsonValue;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.platform.JdkVersion;
import org.testng.SkipException;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class JsonValueAnnotationTest extends ForyJsonTestModels {
  @Factory(dataProvider = "enableCodegen")
  public JsonValueAnnotationTest(boolean codegen) {
    super(codegen);
  }

  @Test
  public void fieldRoundTrip() {
    ForyJson json = newJson();
    FieldValue value = new FieldValue("a\"b");
    assertEquals(json.toJson(value), "\"a\\\"b\"");
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), "\"a\\\"b\"");
    assertEquals(json.fromJson("\"latin\"", FieldValue.class).value, "latin");
    assertEquals(json.fromJson("\"你好\"", FieldValue.class).value, "你好");
    assertEquals(
        json.fromJson("\"世界\"".getBytes(StandardCharsets.UTF_8), FieldValue.class).value, "世界");
    assertNull(json.fromJson("null", FieldValue.class));
    assertNull(json.fromJson("null".getBytes(StandardCharsets.UTF_8), FieldValue.class));
    assertEquals(json.toJson(null, FieldValue.class), "null");
  }

  @Test
  public void methodAndFactory() {
    ForyJson json = newJson();
    MethodValue value = new MethodValue("value");
    assertEquals(json.toJson(value), "\"value\"");
    assertEquals(json.fromJson("\"factory\"", MethodValue.class).text, "factory");
  }

  @Test
  public void nullMember() {
    ForyJson json = newJson();
    assertEquals(json.toJson(new FieldValue(null)), "null");
    assertEquals(
        new String(json.toJsonBytes(new FieldValue(null)), StandardCharsets.UTF_8), "null");
  }

  @Test
  public void writeOnlyWithoutCreator() {
    ForyJson json = newJson();
    assertEquals(json.toJson(new WriteOnlyValue("x")), "\"x\"");
    assertThrows(ForyJsonException.class, () -> json.fromJson("\"x\"", WriteOnlyValue.class));
  }

  @Test
  public void propertyCreatorNotInferred() {
    ForyJson json = newJson();
    assertEquals(json.toJson(new PropertyCreatorValue("x")), "\"x\"");
    assertThrows(ForyJsonException.class, () -> json.fromJson("\"x\"", PropertyCreatorValue.class));
  }

  @Test
  public void overrideSuppressesValue() {
    assertEquals(newJson().toJson(new SuppressedValue()), "{}");
  }

  @Test
  public void rejectInvalidDeclarations() {
    ForyJson json = newJson();
    assertThrows(ForyJsonException.class, () -> json.toJson(new MultipleValues()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new InvalidValueType()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new StaticValue()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new CodecConflict()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new InheritedInvalidValue()));
  }

  @Test
  public void typeCodecConflict() {
    assertThrows(ForyJsonException.class, () -> newJson().toJson(new TypeCodecConflict("x")));
  }

  @Test
  public void exactCodecOverridesValue() {
    ForyJson json =
        newJsonBuilder().registerCodec(OverriddenValue.class, new OverriddenValueCodec()).build();
    assertEquals(json.toJson(new OverriddenValue("x")), "\"override\"");
  }

  @Test
  public void occurrenceCodecOverridesValue() {
    OccurrenceHolder holder = new OccurrenceHolder();
    holder.value = new OverriddenValue("x");
    assertEquals(newJson().toJson(holder), "{\"value\":\"override\"}");
  }

  @Test
  public void inheritedTypeCodecOverridesValue() {
    assertEquals(newJson().toJson(new InheritedCodecValue()), "\"override\"");
  }

  @Test
  public void enumRoundTrip() {
    ForyJson json = newJson();
    assertEquals(json.toJson(ValueEnum.ONE), "\"one\"");
    assertEquals(json.fromJson("\"two\"", ValueEnum.class), ValueEnum.TWO);
  }

  @Test
  public void recordRoundTrip() throws Exception {
    if (JdkVersion.MAJOR_VERSION < 17) {
      throw new SkipException("Java record test requires JDK 17+");
    }
    Class<?> type =
        compileRecordClass(
            "JsonValueRecord",
            "package org.apache.fory.json.records;\n"
                + "import org.apache.fory.json.annotation.JsonCreator;\n"
                + "import org.apache.fory.json.annotation.JsonValue;\n"
                + "public record JsonValueRecord(@JsonValue String value) {\n"
                + "  @JsonCreator public JsonValueRecord {}\n"
                + "}\n");
    Object value = type.getConstructor(String.class).newInstance("record");
    ForyJson json = newJson();
    assertEquals(json.toJson(value), "\"record\"");
    Object decoded = json.fromJson("\"decoded\"", type);
    assertEquals(type.getMethod("value").invoke(decoded), "decoded");
  }

  @Test
  public void rawCombination() {
    ForyJson json = newJson();
    RawFragment value = new RawFragment("{\"id\":1,\"name\":\"你好\"}");
    assertEquals(json.toJson(value), "{\"id\":1,\"name\":\"你好\"}");
    assertEquals(
        new String(json.toJsonBytes(value), StandardCharsets.UTF_8), "{\"id\":1,\"name\":\"你好\"}");
    assertThrows(ForyJsonException.class, () -> json.fromJson("\"x\"", RawFragment.class));
    assertNull(json.fromJson("null", RawFragment.class));
  }

  public static final class FieldValue {
    @JsonValue private final String value;

    @JsonCreator
    public FieldValue(String value) {
      this.value = value;
    }
  }

  public static final class MethodValue {
    private final String text;

    private MethodValue(String text) {
      this.text = text;
    }

    @JsonValue
    public String arbitraryName() {
      return text;
    }

    @JsonCreator
    public static MethodValue from(String value) {
      return new MethodValue(value);
    }
  }

  public static final class WriteOnlyValue {
    @JsonValue public String value;

    public WriteOnlyValue(String value) {
      this.value = value;
    }
  }

  public static final class PropertyCreatorValue {
    @JsonValue public String value;

    @JsonCreator({"value"})
    public PropertyCreatorValue(String value) {
      this.value = value;
    }
  }

  public static class ParentValue {
    @JsonValue
    public String text() {
      return "parent";
    }
  }

  public static final class SuppressedValue extends ParentValue {
    @Override
    public String text() {
      return "child";
    }
  }

  public static final class MultipleValues {
    @JsonValue public String first = "a";
    @JsonValue public String second = "b";
  }

  public static final class InvalidValueType {
    @JsonValue public int value = 1;
  }

  public static final class StaticValue {
    @JsonValue public static String value = "x";
  }

  public static final class CodecConflict {
    @JsonValue
    @JsonCodec(OverrideCodec.class)
    public String value = "x";
  }

  public static class InvalidValueParent {
    @JsonValue
    protected String hidden() {
      return "parent";
    }
  }

  public static final class InheritedInvalidValue extends InvalidValueParent {
    @JsonValue public String value = "child";
  }

  @JsonCodec(OverrideCodec.class)
  public static final class TypeCodecConflict {
    @JsonValue public String value;

    public TypeCodecConflict(String value) {
      this.value = value;
    }
  }

  public static final class OverriddenValue {
    @JsonValue public String value;

    public OverriddenValue(String value) {
      this.value = value;
    }
  }

  public static final class OccurrenceHolder {
    @JsonCodec(OverrideCodec.class)
    public OverriddenValue value;
  }

  @JsonCodec(OverrideCodec.class)
  public static class CodecParent {}

  public static final class InheritedCodecValue extends CodecParent {
    @JsonValue public String value = "value";
  }

  public enum ValueEnum {
    ONE("one"),
    TWO("two");

    private final String value;

    ValueEnum(String value) {
      this.value = value;
    }

    @JsonValue
    public String value() {
      return value;
    }

    @JsonCreator
    public static ValueEnum from(String value) {
      return "one".equals(value) ? ONE : TWO;
    }
  }

  public static final class RawFragment {
    private final String value;

    public RawFragment(String value) {
      this.value = value;
    }

    @JsonValue
    @JsonRawValue
    public String value() {
      return value;
    }
  }

  public static final class OverrideCodec implements JsonValueCodec<Object> {
    @Override
    public void writeString(StringJsonWriter writer, Object value) {
      writer.writeString("override");
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Object value) {
      writer.writeString("override");
    }

    @Override
    public Object readLatin1(Latin1JsonReader reader) {
      return null;
    }

    @Override
    public Object readUtf16(Utf16JsonReader reader) {
      return null;
    }

    @Override
    public Object readUtf8(Utf8JsonReader reader) {
      return null;
    }
  }

  public static final class OverriddenValueCodec implements JsonValueCodec<OverriddenValue> {
    @Override
    public void writeString(StringJsonWriter writer, OverriddenValue value) {
      writer.writeString("override");
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, OverriddenValue value) {
      writer.writeString("override");
    }

    @Override
    public OverriddenValue readLatin1(Latin1JsonReader reader) {
      return null;
    }

    @Override
    public OverriddenValue readUtf16(Utf16JsonReader reader) {
      return null;
    }

    @Override
    public OverriddenValue readUtf8(Utf8JsonReader reader) {
      return null;
    }
  }
}

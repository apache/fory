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
import java.util.Map;
import org.apache.fory.json.annotation.JsonAnyGetter;
import org.apache.fory.json.annotation.JsonAnyProperty;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonIgnore;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonPropertyOrder;
import org.apache.fory.json.annotation.JsonRawValue;
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

public class JsonRawValueAnnotationTest extends ForyJsonTestModels {
  @Factory(dataProvider = "enableCodegen")
  public JsonRawValueAnnotationTest(boolean codegen) {
    super(codegen);
  }

  @Test
  public void fieldWriteAndStringRead() {
    ForyJson json = newJson();
    RawFields value = new RawFields();
    value.first = "{\"id\":1}";
    value.middle = "[\"你好😀\",2]";
    value.last = "true";
    String expected = "{\"first\":{\"id\":1},\"middle\":[\"你好😀\",2],\"last\":true}";
    assertEquals(json.toJson(value), expected);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), expected);

    RawFields read =
        json.fromJson(
            "{\"first\":\"a\",\"middle\":\"b\",\"last\":\"c\",\"extra\":1}", RawFields.class);
    assertEquals(read.first, "a");
    assertEquals(read.middle, "b");
    assertEquals(read.last, "c");
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"first\":{},\"middle\":\"b\"}", RawFields.class));
    assertGeneratedWhenSupported(json, RawFields.class, codegenEnabled());
  }

  @Test
  public void getterWrite() {
    GetterRaw value = new GetterRaw("{\"ok\":true}");
    ForyJson json = newJson();
    assertEquals(json.toJson(value), "{\"body\":{\"ok\":true}}");
    assertEquals(
        new String(json.toJsonBytes(value), StandardCharsets.UTF_8), "{\"body\":{\"ok\":true}}");
  }

  @Test
  public void binaryBase64RoundTrip() {
    ForyJson json = newJson();
    BinaryRaw value = new BinaryRaw();
    value.bytes = new byte[] {0, 1, 2, -1};
    assertEquals(json.toJson(value), "{\"bytes\":\"AAEC/w==\"}");
    assertEquals(
        new String(json.toJsonBytes(value), StandardCharsets.UTF_8), "{\"bytes\":\"AAEC/w==\"}");
    assertEquals(
        json.fromJson("{\"bytes\":\"AQID\"}", BinaryRaw.class).bytes, new byte[] {1, 2, 3});
    assertEquals(
        json.fromJson("{\"bytes\":\"AQI=\"}".getBytes(StandardCharsets.UTF_8), BinaryRaw.class)
            .bytes,
        new byte[] {1, 2});
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"bytes\":\"not base64\"}", BinaryRaw.class));
    assertGeneratedWhenSupported(json, BinaryRaw.class, codegenEnabled());
  }

  @Test
  public void binaryBase64PaddingAndEscapes() {
    ForyJson json = newJson();
    BinaryRaw value = new BinaryRaw();
    byte[][] values = {new byte[0], {1}, {1, 2}, {1, 2, 3}};
    String[] encoded = {"", "AQ==", "AQI=", "AQID"};
    for (int i = 0; i < values.length; i++) {
      value.bytes = values[i];
      assertEquals(json.toJson(value), "{\"bytes\":\"" + encoded[i] + "\"}");
      assertEquals(
          new String(json.toJsonBytes(value), StandardCharsets.UTF_8),
          "{\"bytes\":\"" + encoded[i] + "\"}");
      assertEquals(
          json.fromJson("{\"bytes\":\"" + encoded[i] + "\"}", BinaryRaw.class).bytes, values[i]);
    }
    assertEquals(
        json.fromJson("{\"bytes\":\"A\\u0051I=\"}", BinaryRaw.class).bytes, new byte[] {1, 2});
    assertThrows(
        ForyJsonException.class, () -> json.fromJson("{\"bytes\":\"A===\"}", BinaryRaw.class));
    assertThrows(
        ForyJsonException.class, () -> json.fromJson("{\"bytes\":\"AA=A\"}", BinaryRaw.class));
    assertThrows(
        ForyJsonException.class, () -> json.fromJson("{\"bytes\":\"A\"}", BinaryRaw.class));
  }

  @Test
  public void binaryBase64AfterUnicode() {
    ForyJson json = newJson();
    UnicodeBinaryRaw value = new UnicodeBinaryRaw();
    value.text = "你好";
    value.bytes = new byte[] {1};
    String expected = "{\"text\":\"你好\",\"bytes\":\"AQ==\"}";
    assertEquals(json.toJson(value), expected);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), expected);
    UnicodeBinaryRaw decoded = json.fromJson(expected, UnicodeBinaryRaw.class);
    assertEquals(decoded.text, "你好");
    assertEquals(decoded.bytes, new byte[] {1});
  }

  @Test
  public void binaryDirectionalIgnore() {
    ForyJson json = newJson();
    BinaryReadOnly readOnly = new BinaryReadOnly();
    readOnly.bytes = new byte[] {1};
    assertEquals(json.toJson(readOnly), "{}");
    assertEquals(
        json.fromJson("{\"bytes\":\"AQI=\"}", BinaryReadOnly.class).bytes, new byte[] {1, 2});

    BinaryWriteOnly writeOnly = new BinaryWriteOnly();
    writeOnly.bytes = new byte[] {1, 2};
    assertEquals(json.toJson(writeOnly), "{\"bytes\":\"AQI=\"}");
    assertNull(json.fromJson("{\"bytes\":\"AQID\"}", BinaryWriteOnly.class).bytes);
  }

  @Test
  public void binaryCreatorRoundTrip() {
    ForyJson json = newJson();
    byte[] bytes = {1, 2, 3};
    PropertyListBinary propertyList = new PropertyListBinary(bytes);
    assertEquals(json.toJson(propertyList), "{\"bytes\":\"AQID\"}");
    assertEquals(
        json.fromJson("{\"bytes\":\"AQI=\"}", PropertyListBinary.class).bytes, new byte[] {1, 2});

    ParameterLocalBinary parameterLocal = new ParameterLocalBinary(bytes);
    assertEquals(json.toJson(parameterLocal), "{\"bytes\":\"AQID\"}");
    assertEquals(
        json.fromJson("{\"bytes\":\"AQ==\"}", ParameterLocalBinary.class).bytes, new byte[] {1});
  }

  @Test
  public void recordRoundTrip() throws Exception {
    if (JdkVersion.MAJOR_VERSION < 17) {
      throw new SkipException("Java record test requires JDK 17+");
    }
    Class<?> type =
        compileRecordClass(
            "JsonRawRecord",
            "package org.apache.fory.json.records;\n"
                + "import org.apache.fory.json.annotation.JsonRawValue;\n"
                + "public record JsonRawRecord(@JsonRawValue String body, "
                + "@JsonRawValue byte[] bytes) {}\n");
    Object value =
        type.getConstructor(String.class, byte[].class)
            .newInstance("{\"id\":1}", new byte[] {1, 2, 3});
    ForyJson json = newJson();
    String expected = "{\"body\":{\"id\":1},\"bytes\":\"AQID\"}";
    assertEquals(json.toJson(value), expected);
    Object decoded = json.fromJson("{\"body\":\"text\",\"bytes\":\"AQI=\"}", type);
    assertEquals(type.getMethod("body").invoke(decoded), "text");
    assertEquals(type.getMethod("bytes").invoke(decoded), new byte[] {1, 2});
  }

  @Test
  public void nullInclusion() {
    ForyJson json = newJson();
    NullRaw value = new NullRaw();
    assertEquals(json.toJson(value), "{\"included\":null}");
    assertEquals(
        new String(json.toJsonBytes(value), StandardCharsets.UTF_8), "{\"included\":null}");
    NullRaw read = json.fromJson("{\"included\":null}", NullRaw.class);
    assertNull(read.included);
  }

  @Test
  public void rawTextIsNotValidated() {
    ForyJson json = newJson();
    RawFields value = new RawFields();
    value.first = "not-json";
    assertEquals(json.toJson(value), "{\"first\":not-json}");
    assertEquals(
        new String(json.toJsonBytes(value), StandardCharsets.UTF_8), "{\"first\":not-json}");
  }

  @Test
  public void rawWriteOverridesTypeCodec() {
    ForyJson json =
        newJsonBuilder().registerCodec(String.class, new ReplacingStringCodec()).build();
    RawFields value = new RawFields();
    value.first = "{\"id\":1}";
    assertEquals(json.toJson(value), "{\"first\":{\"id\":1}}");
    assertEquals(
        new String(json.toJsonBytes(value), StandardCharsets.UTF_8), "{\"first\":{\"id\":1}}");
  }

  @Test
  public void rejectInvalidDeclarations() {
    ForyJson json = newJson();
    assertThrows(ForyJsonException.class, () -> json.toJson(new NonStringRaw()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new StaticRaw()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new CodecRaw()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new AnyFieldRaw()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new AnyGetterRaw()));
  }

  public static final class RawFields {
    @JsonRawValue public String first;
    @JsonRawValue public String middle;
    @JsonRawValue public String last;
  }

  public static final class GetterRaw {
    private String body;

    public GetterRaw() {}

    public GetterRaw(String body) {
      this.body = body;
    }

    @JsonRawValue
    public String getBody() {
      return body;
    }

    public void setBody(String body) {
      this.body = body;
    }
  }

  public static final class BinaryRaw {
    @JsonRawValue public byte[] bytes;
  }

  @JsonPropertyOrder({"text", "bytes"})
  public static final class UnicodeBinaryRaw {
    public String text;
    @JsonRawValue public byte[] bytes;
  }

  public static final class BinaryReadOnly {
    @JsonRawValue
    @JsonIgnore(ignoreRead = false, ignoreWrite = true)
    public byte[] bytes;
  }

  public static final class BinaryWriteOnly {
    @JsonRawValue
    @JsonIgnore(ignoreRead = true, ignoreWrite = false)
    public byte[] bytes;
  }

  public static final class PropertyListBinary {
    @JsonRawValue public final byte[] bytes;

    @JsonCreator({"bytes"})
    public PropertyListBinary(byte[] bytes) {
      this.bytes = bytes;
    }
  }

  public static final class ParameterLocalBinary {
    @JsonRawValue public final byte[] bytes;

    @JsonCreator
    public ParameterLocalBinary(@JsonProperty("bytes") byte[] bytes) {
      this.bytes = bytes;
    }
  }

  public static final class NullRaw {
    @JsonRawValue public String omitted;

    @JsonRawValue
    @JsonProperty(include = JsonProperty.Include.ALWAYS)
    public String included;
  }

  public static final class NonStringRaw {
    @JsonRawValue public int value = 1;
  }

  public static final class StaticRaw {
    @JsonRawValue public static String value = "1";
  }

  public static final class CodecRaw {
    @JsonRawValue
    @JsonCodec(JsonValueAnnotationTest.OverrideCodec.class)
    public String value = "1";
  }

  public static final class AnyFieldRaw {
    @JsonRawValue @JsonAnyProperty public Map<String, String> values;
  }

  public static final class AnyGetterRaw {
    @JsonRawValue
    @JsonAnyGetter
    public Map<String, String> getValues() {
      return null;
    }
  }

  public static final class ReplacingStringCodec implements JsonValueCodec<String> {
    @Override
    public void writeString(StringJsonWriter writer, String value) {
      writer.writeString("replacement");
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, String value) {
      writer.writeString("replacement");
    }

    @Override
    public String readLatin1(Latin1JsonReader reader) {
      return reader.readString();
    }

    @Override
    public String readUtf16(Utf16JsonReader reader) {
      return reader.readString();
    }

    @Override
    public String readUtf8(Utf8JsonReader reader) {
      return reader.readString();
    }
  }
}

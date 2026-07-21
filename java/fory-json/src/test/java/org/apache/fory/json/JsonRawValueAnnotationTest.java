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
import org.apache.fory.json.annotation.JsonIgnore;
import org.apache.fory.json.annotation.JsonProperty;
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
  public void recordRoundTrip() throws Exception {
    if (JdkVersion.MAJOR_VERSION < 17) {
      throw new SkipException("Java record test requires JDK 17+");
    }
    Class<?> type =
        compileRecordClass(
            "JsonRawValueRecord",
            "package org.apache.fory.json.records;\n"
                + "import org.apache.fory.json.annotation.JsonRawValue;\n"
                + "public record JsonRawValueRecord(@JsonRawValue String value) {}\n");
    Object value = type.getConstructor(String.class).newInstance("{\"id\":1}");
    for (ForyJson json : new ForyJson[] {newJson(), newJsonBuilder().withFieldMode(true).build()}) {
      assertEquals(json.toJson(value), "{\"value\":{\"id\":1}}");
      Object decoded = json.fromJson("{\"value\":\"text\"}", type);
      assertEquals(type.getMethod("value").invoke(decoded), "text");
    }
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
    assertThrows(ForyJsonException.class, () -> json.toJson(new RawByteArray()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new StaticRaw()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new CodecRaw()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new IgnoredRaw()));
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

  public static final class RawByteArray {
    @JsonRawValue public byte[] bytes;
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

  public static final class IgnoredRaw {
    @JsonRawValue @JsonIgnore public String value = "1";
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

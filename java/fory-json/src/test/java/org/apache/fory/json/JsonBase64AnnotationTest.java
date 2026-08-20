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
import org.apache.fory.json.annotation.JsonBase64;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonIgnore;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonPropertyOrder;
import org.apache.fory.json.annotation.JsonRawValue;
import org.apache.fory.json.codec.Base64ByteArrayCodec;
import org.apache.fory.platform.JdkVersion;
import org.testng.SkipException;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class JsonBase64AnnotationTest extends ForyJsonTestModels {
  @Factory(dataProvider = "enableCodegen")
  public JsonBase64AnnotationTest(boolean codegen) {
    super(codegen);
  }

  @Test
  public void fieldRoundTrip() {
    ForyJson json = newJson();
    Base64Field value = new Base64Field();
    value.bytes = new byte[] {0, 1, 2, -1};
    assertEquals(json.toJson(value), "{\"bytes\":\"AAEC/w==\"}");
    assertEquals(
        new String(json.toJsonBytes(value), StandardCharsets.UTF_8), "{\"bytes\":\"AAEC/w==\"}");
    assertEquals(
        json.fromJson("{\"bytes\":\"AQID\"}", Base64Field.class).bytes, new byte[] {1, 2, 3});
    assertEquals(
        json.fromJson("{\"bytes\":\"AQI=\"}".getBytes(StandardCharsets.UTF_8), Base64Field.class)
            .bytes,
        new byte[] {1, 2});
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"bytes\":\"not base64\"}", Base64Field.class));
    assertGeneratedWhenSupported(json, Base64Field.class, codegenEnabled());
  }

  @Test
  public void paddingAndEscapes() {
    ForyJson json = newJson();
    Base64Field value = new Base64Field();
    byte[][] values = {new byte[0], {1}, {1, 2}, {1, 2, 3}};
    String[] encoded = {"", "AQ==", "AQI=", "AQID"};
    for (int i = 0; i < values.length; i++) {
      value.bytes = values[i];
      assertEquals(json.toJson(value), "{\"bytes\":\"" + encoded[i] + "\"}");
      assertEquals(
          new String(json.toJsonBytes(value), StandardCharsets.UTF_8),
          "{\"bytes\":\"" + encoded[i] + "\"}");
      assertEquals(
          json.fromJson("{\"bytes\":\"" + encoded[i] + "\"}", Base64Field.class).bytes, values[i]);
    }
    assertEquals(
        json.fromJson("{\"bytes\":\"A\\u0051I=\"}", Base64Field.class).bytes, new byte[] {1, 2});
    assertThrows(
        ForyJsonException.class, () -> json.fromJson("{\"bytes\":\"A===\"}", Base64Field.class));
    assertThrows(
        ForyJsonException.class, () -> json.fromJson("{\"bytes\":\"AA=A\"}", Base64Field.class));
    assertThrows(
        ForyJsonException.class, () -> json.fromJson("{\"bytes\":\"A\"}", Base64Field.class));
  }

  @Test
  public void afterUnicode() {
    ForyJson json = newJson();
    UnicodeBase64 value = new UnicodeBase64();
    value.text = "你好";
    value.bytes = new byte[] {1};
    String expected = "{\"text\":\"你好\",\"bytes\":\"AQ==\"}";
    assertEquals(json.toJson(value), expected);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), expected);
    UnicodeBase64 decoded = json.fromJson(expected, UnicodeBase64.class);
    assertEquals(decoded.text, "你好");
    assertEquals(decoded.bytes, new byte[] {1});
  }

  @Test
  public void getterRoundTrip() {
    Base64Getter value = new Base64Getter(new byte[] {1, 2, 3});
    ForyJson json = newJson();
    assertEquals(json.toJson(value), "{\"bytes\":\"AQID\"}");
    assertEquals(
        json.fromJson("{\"bytes\":\"AQI=\"}", Base64Getter.class).getBytes(), new byte[] {1, 2});
  }

  @Test
  public void directionalIgnore() {
    ForyJson json = newJson();
    Base64ReadOnly readOnly = new Base64ReadOnly();
    readOnly.bytes = new byte[] {1};
    assertEquals(json.toJson(readOnly), "{}");
    assertEquals(
        json.fromJson("{\"bytes\":\"AQI=\"}", Base64ReadOnly.class).bytes, new byte[] {1, 2});

    Base64WriteOnly writeOnly = new Base64WriteOnly();
    writeOnly.bytes = new byte[] {1, 2};
    assertEquals(json.toJson(writeOnly), "{\"bytes\":\"AQI=\"}");
    assertNull(json.fromJson("{\"bytes\":\"AQID\"}", Base64WriteOnly.class).bytes);
  }

  @Test
  public void creatorRoundTrip() {
    ForyJson json = newJson();
    byte[] bytes = {1, 2, 3};
    PropertyListBase64 propertyList = new PropertyListBase64(bytes);
    assertEquals(json.toJson(propertyList), "{\"bytes\":\"AQID\"}");
    assertEquals(
        json.fromJson("{\"bytes\":\"AQI=\"}", PropertyListBase64.class).bytes, new byte[] {1, 2});

    ParameterLocalBase64 parameterLocal = new ParameterLocalBase64(bytes);
    assertEquals(json.toJson(parameterLocal), "{\"bytes\":\"AQID\"}");
    assertEquals(
        json.fromJson("{\"bytes\":\"AQ==\"}", ParameterLocalBase64.class).bytes, new byte[] {1});
  }

  @Test
  public void recordRoundTrip() throws Exception {
    if (JdkVersion.MAJOR_VERSION < 17) {
      throw new SkipException("Java record test requires JDK 17+");
    }
    Class<?> type =
        compileRecordClass(
            "JsonBase64Record",
            "package org.apache.fory.json.records;\n"
                + "import org.apache.fory.json.annotation.JsonBase64;\n"
                + "public record JsonBase64Record(@JsonBase64 byte[] bytes) {}\n");
    Object value = type.getConstructor(byte[].class).newInstance((Object) new byte[] {1, 2, 3});
    for (ForyJson json : new ForyJson[] {newJson(), newJsonBuilder().withFieldMode(true).build()}) {
      assertEquals(json.toJson(value), "{\"bytes\":\"AQID\"}");
      Object decoded = json.fromJson("{\"bytes\":\"AQI=\"}", type);
      assertEquals(type.getMethod("bytes").invoke(decoded), new byte[] {1, 2});
    }
  }

  @Test
  public void nullInclusion() {
    ForyJson json = newJson();
    Base64Always value = new Base64Always();
    assertEquals(json.toJson(value), "{\"bytes\":null}");
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), "{\"bytes\":null}");
    value.bytes = new byte[] {1, 2, 3};
    assertEquals(json.toJson(value), "{\"bytes\":\"AQID\"}");
    assertEquals(
        new String(json.toJsonBytes(value), StandardCharsets.UTF_8), "{\"bytes\":\"AQID\"}");
    assertGeneratedWhenSupported(json, Base64Always.class, codegenEnabled());
  }

  @Test
  public void directCodec() {
    ForyJson json = newJson();
    DirectCodecBase64 value = new DirectCodecBase64();
    value.bytes = new byte[] {1, 2, 3};
    assertEquals(json.toJson(value), "{\"bytes\":\"AQID\"}");
    assertEquals(
        json.fromJson("{\"bytes\":\"AQI=\"}", DirectCodecBase64.class).bytes, new byte[] {1, 2});
  }

  @Test
  public void rejectInvalidDeclarations() {
    ForyJson json = newJson();
    assertThrows(ForyJsonException.class, () -> json.toJson(new NonBinaryBase64()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new StaticBase64()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new CodecBase64()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new RawBase64()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new IgnoredBase64()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new AnyFieldBase64()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new AnyGetterBase64()));
  }

  public static final class Base64Field {
    @JsonBase64 public byte[] bytes;
  }

  @JsonPropertyOrder({"text", "bytes"})
  public static final class UnicodeBase64 {
    public String text;
    @JsonBase64 public byte[] bytes;
  }

  public static final class Base64Getter {
    private byte[] bytes;

    public Base64Getter() {}

    public Base64Getter(byte[] bytes) {
      this.bytes = bytes;
    }

    @JsonBase64
    public byte[] getBytes() {
      return bytes;
    }

    public void setBytes(byte[] bytes) {
      this.bytes = bytes;
    }
  }

  public static final class Base64ReadOnly {
    @JsonBase64
    @JsonIgnore(ignoreRead = false, ignoreWrite = true)
    public byte[] bytes;
  }

  public static final class Base64WriteOnly {
    @JsonBase64
    @JsonIgnore(ignoreRead = true, ignoreWrite = false)
    public byte[] bytes;
  }

  public static final class PropertyListBase64 {
    @JsonBase64 public final byte[] bytes;

    @JsonCreator({"bytes"})
    public PropertyListBase64(byte[] bytes) {
      this.bytes = bytes;
    }
  }

  public static final class ParameterLocalBase64 {
    @JsonBase64 public final byte[] bytes;

    @JsonCreator
    public ParameterLocalBase64(@JsonProperty("bytes") byte[] bytes) {
      this.bytes = bytes;
    }
  }

  public static final class Base64Always {
    @JsonBase64
    @JsonProperty(include = JsonProperty.Include.ALWAYS)
    public byte[] bytes;
  }

  public static final class DirectCodecBase64 {
    @JsonCodec(Base64ByteArrayCodec.class)
    public byte[] bytes;
  }

  public static final class NonBinaryBase64 {
    @JsonBase64 public String value = "x";
  }

  public static final class StaticBase64 {
    @JsonBase64 public static byte[] value = {1};
  }

  public static final class CodecBase64 {
    @JsonBase64
    @JsonCodec(Base64ByteArrayCodec.class)
    public byte[] value = {1};
  }

  public static final class RawBase64 {
    @JsonBase64 @JsonRawValue public byte[] value = {1};
  }

  public static final class IgnoredBase64 {
    @JsonBase64 @JsonIgnore public byte[] value = {1};
  }

  public static final class AnyFieldBase64 {
    @JsonBase64 @JsonAnyProperty public Map<String, byte[]> values;
  }

  public static final class AnyGetterBase64 {
    @JsonBase64
    @JsonAnyGetter
    public Map<String, byte[]> getValues() {
      return null;
    }
  }
}

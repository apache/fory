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
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import org.apache.fory.json.data.CharValue;
import org.apache.fory.json.data.Kind;
import org.apache.fory.json.data.Nested;
import org.apache.fory.json.data.PublicFields;
import org.apache.fory.json.data.UnicodeEnumValue;
import org.apache.fory.json.data.UnicodeFieldNames;
import org.apache.fory.json.data.UnicodeKind;
import org.apache.fory.json.data.UnicodeMatrix;
import org.apache.fory.json.data.UnicodeValues;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.serializer.StringSerializer;
import org.testng.annotations.Test;

public class JsonStringTest extends ForyJsonTestModels {
  @Test
  public void escapeStrings() {
    ForyJson json = ForyJson.builder().build();
    PublicFields fields = new PublicFields();
    fields.name = "a\n\"b\"\\\u1234";
    String stringExpected = "{\"active\":true,\"id\":7,\"name\":\"a\\n\\\"b\\\"\\\\\u1234\"}";
    assertEquals(json.toJson(fields), stringExpected);
    assertEquals(json.fromJson(stringExpected, PublicFields.class).name, fields.name);
  }

  @Test
  public void writeUtf16StringText() {
    ForyJson json = ForyJson.builder().build();
    UnicodeValues values = new UnicodeValues();
    String expected =
        "{\"first\":\"\u1234\",\"second\":\"music \uD834\uDD1E\","
            + "\"tags\":[\"latin\",\"\u1234\",\"\uD83D\uDE00\"]}";
    assertEquals(json.toJson(values), expected);
    assertEquals(json.toJson(values), expected);
    assertEquals(new String(json.toJsonBytes(values), StandardCharsets.UTF_8), expected);
    assertEquals(json.fromJson(expected, UnicodeValues.class).second, values.second);
    assertEquals(json.fromJson(json.toJsonBytes(values), UnicodeValues.class).tags, values.tags);
  }

  @Test
  public void writeUtf16Char() {
    ForyJson json = ForyJson.builder().build();
    CharValue value = new CharValue();
    value.value = '\u1234';
    assertEquals(json.toJson(value), "{\"value\":\"\u1234\"}");
    assertEquals(
        new String(json.toJsonBytes(value), StandardCharsets.UTF_8), "{\"value\":\"\u1234\"}");
  }

  @Test
  public void writeNonLatin1Matrix() {
    ForyJson json = ForyJson.builder().build();
    UnicodeMatrix value = new UnicodeMatrix();
    String expected = unicodeMatrixJson();
    assertEquals(json.toJson(value), expected);
    assertEquals(json.toJson(value), expected);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), expected);
    assertUnicodeMatrix(json.fromJson(expected, UnicodeMatrix.class));
    assertUnicodeMatrix(
        json.fromJson(expected.getBytes(StandardCharsets.UTF_8), UnicodeMatrix.class));
    assertEquals(json.fromJson("\"" + MIXED_SCRIPT_TEXT + "\"", String.class), MIXED_SCRIPT_TEXT);
    assertEquals(
        json.fromJson(
            ("\"" + SUPPLEMENTARY_TEXT + "\"").getBytes(StandardCharsets.UTF_8), String.class),
        SUPPLEMENTARY_TEXT);
  }

  @Test
  public void writeReadZhEuStrings() {
    ForyJson json = ForyJson.builder().build();
    assertTextRoundTrip(json, ZH_TEXT);
    assertTextRoundTrip(json, EU_TEXT);
  }

  @Test
  public void stringWriterResetAfterMaterialize() {
    StringJsonWriter writer = new StringJsonWriter(false, new byte[16]);
    writer.writeString("你好，Fory");
    String utf16Json = writer.toJson();
    writer.reset();
    writer.writeString("ascii");
    assertEquals(utf16Json, "\"你好，Fory\"");
    assertEquals(writer.toJson(), "\"ascii\"");
    if (StringSerializer.isBytesBackedString()) {
      assertTrue(StringSerializer.isUtf16Coder(StringSerializer.getStringCoder(utf16Json)));
    }
  }

  @Test
  public void stringWriterShrinksOnReset() throws Exception {
    StringJsonWriter writer = new StringJsonWriter(false, new byte[16]);
    writer.writeString(repeat('a', 9000) + "你好，Fory");
    assertTrue(writerBufferLength(writer) > 8192);
    writer.toJson();
    writer.reset();
    assertEquals(writerBufferLength(writer), 8192);
    writer.writeString("café");
    assertEquals(writer.toJson(), "\"café\"");
  }

  @Test
  public void readStringInputLayouts() {
    ForyJson json = ForyJson.builder().build();
    String latin1Json = "{\"active\":true,\"id\":7,\"name\":\"café\"}";
    String utf16Json = "{\"active\":true,\"id\":7,\"name\":\"你好，Fory\"}";
    if (StringSerializer.isBytesBackedString()) {
      assertTrue(StringSerializer.isLatin1Coder(StringSerializer.getStringCoder(latin1Json)));
      assertTrue(StringSerializer.isUtf16Coder(StringSerializer.getStringCoder(utf16Json)));
    }
    assertEquals(json.fromJson(latin1Json, PublicFields.class).name, "café");
    assertEquals(json.fromJson(utf16Json, PublicFields.class).name, ZH_TEXT);
    assertEquals(json.fromJson("\"café\"", String.class), "café");
    assertEquals(json.fromJson("\"你好，Fory\"", String.class), ZH_TEXT);
  }

  @Test
  public void readUnicodeFieldNames() {
    ForyJson json = ForyJson.builder().build();
    String direct = "{\"café\":\"" + EU_TEXT + "\",\"你好\":\"" + ZH_TEXT + "\"}";
    String escaped = "{\"caf\\u00e9\":\"" + EU_TEXT + "\",\"\\u4f60\\u597d\":\"" + ZH_TEXT + "\"}";
    UnicodeFieldNames directValue = json.fromJson(direct, UnicodeFieldNames.class);
    UnicodeFieldNames escapedValue = json.fromJson(escaped, UnicodeFieldNames.class);
    UnicodeFieldNames bytesValue =
        json.fromJson(direct.getBytes(StandardCharsets.UTF_8), UnicodeFieldNames.class);
    assertEquals(directValue.café, EU_TEXT);
    assertEquals(directValue.你好, ZH_TEXT);
    assertEquals(escapedValue.café, EU_TEXT);
    assertEquals(escapedValue.你好, ZH_TEXT);
    assertEquals(bytesValue.café, EU_TEXT);
    assertEquals(bytesValue.你好, ZH_TEXT);
  }

  @Test
  public void readUnicodeEnum() {
    ForyJson json = ForyJson.builder().build();
    String direct = "{\"kind\":\"你好\"}";
    String escaped = "{\"kind\":\"\\u4f60\\u597d\"}";
    String asciiDirect = "{\"kind\":\"FAST\"}";
    String asciiEscaped = "{\"kind\":\"F\\u0041ST\"}";
    assertEquals(
        new String(json.toJsonBytes(new UnicodeEnumValue()), StandardCharsets.UTF_8), direct);
    assertEquals(json.fromJson(direct, UnicodeEnumValue.class).kind, UnicodeKind.你好);
    assertEquals(json.fromJson(escaped, UnicodeEnumValue.class).kind, UnicodeKind.你好);
    assertEquals(
        json.fromJson(direct.getBytes(StandardCharsets.UTF_8), UnicodeEnumValue.class).kind,
        UnicodeKind.你好);
    assertEquals(json.fromJson(asciiDirect, Nested.class).kind, Kind.FAST);
    assertEquals(
        json.fromJson(asciiDirect.getBytes(StandardCharsets.UTF_8), Nested.class).kind, Kind.FAST);
    assertEquals(json.fromJson(asciiEscaped, Nested.class).kind, Kind.FAST);
    assertEquals(
        json.fromJson(asciiEscaped.getBytes(StandardCharsets.UTF_8), Nested.class).kind, Kind.FAST);
  }

  @Test
  public void writeLatin1NonAsciiBytes() {
    ForyJson json = ForyJson.builder().build();
    PublicFields fields = new PublicFields();
    fields.name = "caf\u00e9";
    assertEquals(json.toJson(fields), "{\"active\":true,\"id\":7,\"name\":\"caf\u00e9\"}");
    assertEquals(
        new String(json.toJsonBytes(fields), StandardCharsets.UTF_8),
        "{\"active\":true,\"id\":7,\"name\":\"caf\u00e9\"}");
    fields.name = "\u0080";
    String expected = "{\"active\":true,\"id\":7,\"name\":\"\u0080\"}";
    assertEquals(json.toJson(fields), expected);
    assertEquals(new String(json.toJsonBytes(fields), StandardCharsets.UTF_8), expected);
    assertEquals(json.fromJson(json.toJsonBytes(fields), PublicFields.class).name, fields.name);
  }

  @Test
  public void rejectSurrogateChar() {
    ForyJson json = ForyJson.builder().build();
    CharValue value = new CharValue();
    value.value = '\uD800';
    assertThrows(ForyJsonException.class, () -> json.toJson(value));
    assertThrows(ForyJsonException.class, () -> json.toJsonBytes(value));
  }

  @Test
  public void rejectSurrogateString() {
    ForyJson json = ForyJson.builder().build();
    PublicFields fields = new PublicFields();
    fields.name = "\uD800";
    assertThrows(ForyJsonException.class, () -> json.toJson(fields));
    assertThrows(ForyJsonException.class, () -> json.toJsonBytes(fields));
  }

  @Test
  public void writeSurrogatePair() {
    ForyJson json = ForyJson.builder().build();
    PublicFields fields = new PublicFields();
    fields.name = "a\uD83D\uDE00";
    String stringExpected = "{\"active\":true,\"id\":7,\"name\":\"a\uD83D\uDE00\"}";
    String utf8Expected = "{\"active\":true,\"id\":7,\"name\":\"a\uD83D\uDE00\"}";
    assertEquals(json.toJson(fields), stringExpected);
    assertEquals(new String(json.toJsonBytes(fields), StandardCharsets.UTF_8), utf8Expected);
    assertEquals(json.fromJson(stringExpected, PublicFields.class).name, fields.name);
  }

  @Test
  public void readStringScanBoundaries() {
    ForyJson json = ForyJson.builder().build();
    for (int length : new int[] {7, 8, 15, 16, 23, 24}) {
      String value = repeat('a', length);
      String input = "\"" + value + "\"";
      assertEquals(json.fromJson(input, String.class), value);
      assertEquals(json.fromJson(input.getBytes(StandardCharsets.UTF_8), String.class), value);
    }
    String escapedValue = repeat('b', 16) + "\n";
    String escapedInput = "\"" + repeat('b', 16) + "\\n\"";
    assertEquals(json.fromJson(escapedInput, String.class), escapedValue);
    assertEquals(
        json.fromJson(escapedInput.getBytes(StandardCharsets.UTF_8), String.class), escapedValue);

    String utf8Value = repeat('c', 16) + ZH_TEXT;
    String utf8Input = "\"" + utf8Value + "\"";
    assertEquals(
        json.fromJson(utf8Input.getBytes(StandardCharsets.UTF_8), String.class), utf8Value);

    String rawControl = "\"" + repeat('d', 16) + "\u001f\"";
    assertThrows(ForyJsonException.class, () -> json.fromJson(rawControl, String.class));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson(rawControl.getBytes(StandardCharsets.UTF_8), String.class));
    byte[] invalidUtf8 = {'"', 'a', (byte) 0xC0, (byte) 0xAF, '"'};
    assertThrows(ForyJsonException.class, () -> json.fromJson(invalidUtf8, String.class));
  }

  @Test
  public void rejectInvalidSurrogates() {
    ForyJson json = ForyJson.builder().build();
    assertEquals(json.fromJson("\"\\uD834\\uDD1E\"", String.class), "\uD834\uDD1E");
    assertThrows(ForyJsonException.class, () -> json.fromJson("\"\\uD800\"", String.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("\"\\uDC00\"", String.class));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("\"" + Character.toString('\uD800') + "\"", String.class));
  }

  private static int writerBufferLength(StringJsonWriter writer) throws Exception {
    Field field = StringJsonWriter.class.getDeclaredField("buffer");
    field.setAccessible(true);
    return ((byte[]) field.get(writer)).length;
  }
}
